package me.rerere.ai.provider.providers.google

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.runQuotaQuery
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.google.vertex.ServiceAccountTokenProvider
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.ai.util.removeElements
import me.rerere.ai.util.stringSafe
import me.rerere.common.http.await
import me.rerere.common.http.jsonPrimitiveOrNull
import me.rerere.common.http.sse.newEventSource
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.apache.commons.text.StringEscapeUtils
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GoogleProvider"
private const val IMAGE_TASK_POLL_INTERVAL_MS = 3_000L
private const val IMAGE_TASK_TIMEOUT_MS = 10 * 60 * 1_000L
private val IMAGE_FAILURE_STATUSES = setOf("failed", "expired", "canceled")

class GoogleProvider(private val client: OkHttpClient, context: Context? = null) : Provider<ProviderSetting.Google> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()
    private val serviceAccountTokenProvider by lazy {
        ServiceAccountTokenProvider(client)
    }
    private val interactionsAPI = InteractionsAPI(client = client, keyRoulette = keyRoulette)

    override suspend fun getQuota(providerSetting: ProviderSetting.Google): ProviderQuota? =
        client.runQuotaQuery(providerSetting) {
            keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = flow {
        val googleProviderSetting = providerSetting as? ProviderSetting.Google
            ?: error("Expected Google provider setting")
        repeat(params.numOfImages.coerceAtLeast(1)) {
            submitGoogleImageTask(
                providerSetting = googleProviderSetting,
                modelId = params.model.modelId,
                prompt = params.prompt,
                images = emptyList(),
                size = params.size,
                customHeaders = params.customHeaders,
                customBody = params.customBody,
            ).forEach { item -> emit(item) }
        }
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams,
    ): Flow<ImageGenerationItem> = flow {
        val googleProviderSetting = providerSetting as? ProviderSetting.Google
            ?: error("Expected Google provider setting")
        require(params.images.isNotEmpty()) { "At least one image is required" }
        repeat(params.numOfImages.coerceAtLeast(1)) {
            submitGoogleImageTask(
                providerSetting = googleProviderSetting,
                modelId = params.model.modelId,
                prompt = params.prompt,
                images = params.images,
                size = params.size,
                customHeaders = params.customHeaders,
                customBody = params.customBody,
            ).forEach { item -> emit(item) }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun submitGoogleImageTask(
        providerSetting: ProviderSetting.Google,
        modelId: String,
        prompt: String,
        images: List<String>,
        size: String,
        customHeaders: List<CustomHeader>,
        customBody: List<CustomBody>,
    ): List<ImageGenerationItem> = withContext(Dispatchers.IO) {
        if (providerSetting.usesCompatibleImageApi()) {
            return@withContext submitCompatibleImageTask(
                providerSetting = providerSetting,
                modelId = modelId,
                prompt = prompt,
                images = images,
                size = size,
                customHeaders = customHeaders,
                customBody = customBody,
            )
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = buildImageRequestBody(
            modelId = modelId,
            prompt = prompt,
            images = images,
            size = size,
        ).mergeCustomBody(customBody)
        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/$modelId:generateContent"
            } else {
                "models/$modelId:generateContent"
            },
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .headers(providerSetting.mergeCustomHeaders(customHeaders))
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)

        val request = transformRequest(providerSetting, requestBuilder.build())

        Log.i(TAG, "generateImage: ${json.encodeToString(requestBody)}")
        client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} $body")
            }
            val bodyJson = json.parseToJsonElement(body).jsonObject
            parseGoogleImageResult(bodyJson)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildImageRequestBody(
        modelId: String,
        prompt: String,
        images: List<String>,
        size: String,
    ): JsonObject = buildJsonObject {
        putJsonArray("contents") {
            add(buildJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    add(buildJsonObject { put("text", prompt) })
                    images.forEach { path ->
                        val file = File(path)
                        require(file.exists()) { "Image file does not exist: $path" }
                        val mimeType = file.imageMimeType()
                        val data = Base64.encode(file.readBytes())
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mimeType)
                                put("data", data)
                            })
                        })
                    }
                }
            })
        }
        put("generationConfig", buildJsonObject {
            putJsonArray("responseModalities") {
                add(JsonPrimitive("TEXT"))
                add(JsonPrimitive("IMAGE"))
            }
            if (size.isNotBlank() && !size.equals("auto", ignoreCase = true)) {
                put(
                    "imageConfig",
                    buildGeminiImageConfig(
                        size = size,
                        includeImageSize = !modelId.contains("2.5-flash-image", ignoreCase = true),
                    )
                )
            }
        })
    }

    private suspend fun submitCompatibleImageTask(
        providerSetting: ProviderSetting.Google,
        modelId: String,
        prompt: String,
        images: List<String>,
        size: String,
        customHeaders: List<CustomHeader>,
        customBody: List<CustomBody>,
    ): List<ImageGenerationItem> {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = providerSetting.baseUrl.trimEnd('/') +
            if (images.isEmpty()) "/images/generations/async" else "/images/edits/async"
        val request = if (images.isEmpty()) {
            val body = buildJsonObject {
                put("model", modelId)
                put("prompt", prompt)
                if (size.isNotBlank() && !size.equals("auto", ignoreCase = true)) put("size", size)
                put("response_format", "b64_json")
            }.mergeCustomBody(customBody)
            Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(customHeaders))
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        } else {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", modelId)
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("response_format", "b64_json")
                .apply {
                    if (size.isNotBlank() && !size.equals("auto", ignoreCase = true)) {
                        addFormDataPart("size", size)
                    }
                    images.forEach { path ->
                        val file = File(path)
                        require(file.exists()) { "Image file does not exist: $path" }
                        addFormDataPart(
                            "image",
                            file.name,
                            file.asRequestBody(file.imageMimeType().toMediaType()),
                        )
                    }
                    customBody.forEach { bodyPart ->
                        addFormDataPart(bodyPart.key, bodyPart.value.toString())
                    }
                }
                .build()
            Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(customHeaders))
                .addHeader("Authorization", "Bearer $key")
                .post(body)
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        }

        Log.i(TAG, "submitCompatibleImageTask: $url model=$modelId hasReference=${images.isNotEmpty()}")
        return client.newCall(request).await().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) error("Failed to submit image task: ${response.code} $body")
            val bodyJson = json.parseToJsonElement(body).jsonObject
            val taskId = bodyJson["task_id"]?.jsonPrimitive?.contentOrNull
            if (taskId == null) {
                parseImageApiResult(bodyJson)
            } else if (bodyJson["status"]?.jsonPrimitive?.contentOrNull in IMAGE_FAILURE_STATUSES) {
                error("Image generation failed: ${bodyJson.imageTaskError()}")
            } else {
                pollCompatibleImageTask(providerSetting, taskId, key, customHeaders)
            }
        }
    }

    private fun buildGeminiImageConfig(
        size: String,
        includeImageSize: Boolean = true,
    ): JsonObject = buildJsonObject {
        val dimensions = size.split('x', limit = 2)
            .takeIf { it.size == 2 }
            ?.mapNotNull(String::toIntOrNull)
        val width = dimensions?.getOrNull(0)
        val height = dimensions?.getOrNull(1)
        val aspectRatio = when {
            width == null || height == null || width <= 0 || height <= 0 -> null
            width == height -> "1:1"
            width == 1792 && height == 1024 -> "16:9"
            width == 1024 && height == 1792 -> "9:16"
            width * 3 == height * 2 -> "2:3"
            width * 2 == height * 3 -> "3:2"
            width * 4 == height * 3 -> "3:4"
            width * 3 == height * 4 -> "4:3"
            width * 9 == height * 16 -> "9:16"
            width * 16 == height * 9 -> "16:9"
            else -> null
        }
        if (aspectRatio != null) put("aspectRatio", aspectRatio)
        if (dimensions != null && includeImageSize) {
            val longestSide = maxOf(width ?: 0, height ?: 0)
            put("imageSize", when {
                longestSide >= 3840 -> "4K"
                longestSide >= 2048 -> "2K"
                else -> "1K"
            })
        }
    }

    private suspend fun pollGoogleImageTask(
        providerSetting: ProviderSetting.Google,
        taskId: String,
        key: String,
        customHeaders: List<CustomHeader>,
    ): List<ImageGenerationItem> = withTimeout(IMAGE_TASK_TIMEOUT_MS) {
        var result: List<ImageGenerationItem>? = null
        while (result == null) {
            delay(IMAGE_TASK_POLL_INTERVAL_MS)
            val request = Request.Builder()
                .url(buildImageTaskUrl(providerSetting, taskId))
                .headers(providerSetting.mergeCustomHeaders(customHeaders))
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()
            result = client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error("Failed to query image task: ${response.code} $body")
                }
                val bodyJson = json.parseToJsonElement(body).jsonObject
                if (bodyJson["status"]?.jsonPrimitive?.contentOrNull in IMAGE_FAILURE_STATUSES) {
                    error("Image generation failed: ${bodyJson.imageTaskError()}")
                }
                if (bodyJson["candidates"] != null || bodyJson["data"] != null || bodyJson["result"] != null) {
                    parseGoogleImageResult(bodyJson)
                } else {
                    null
                }
            }
        }
        checkNotNull(result)
    }

    private suspend fun pollCompatibleImageTask(
        providerSetting: ProviderSetting.Google,
        taskId: String,
        key: String,
        customHeaders: List<CustomHeader>,
    ): List<ImageGenerationItem> = withTimeout(IMAGE_TASK_TIMEOUT_MS) {
        var result: List<ImageGenerationItem>? = null
        while (result == null) {
            delay(IMAGE_TASK_POLL_INTERVAL_MS)
            val request = Request.Builder()
                .url(providerSetting.baseUrl.trimEnd('/') + "/images/tasks/$taskId")
                .headers(providerSetting.mergeCustomHeaders(customHeaders))
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()
            result = client.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) error("Failed to query image task: ${response.code} $body")
                val bodyJson = json.parseToJsonElement(body).jsonObject
                val status = bodyJson["status"]?.jsonPrimitive?.contentOrNull
                if (status in IMAGE_FAILURE_STATUSES) {
                    error("Image generation failed: ${bodyJson.imageTaskError()}")
                }
                if (status == "completed" || bodyJson["data"] != null || bodyJson["result"] != null) {
                    parseImageApiResult(bodyJson)
                } else {
                    null
                }
            }
        }
        checkNotNull(result)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun parseGoogleImageResult(body: JsonObject): List<ImageGenerationItem> {
        val candidates = body["candidates"]?.jsonArray ?: return parseImageApiResult(body)
        return candidates.flatMap { candidate ->
            candidate.jsonObject["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                .mapNotNull { part ->
                    val partObject = part.jsonObject
                    if (partObject["thought"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
                    val inlineData = partObject["inlineData"]?.jsonObject
                    if (inlineData != null) {
                        val mimeType = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                        val data = inlineData["data"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                        ImageGenerationItem(data = data, mimeType = mimeType)
                    } else {
                        val url = partObject["text"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                            ?: return@mapNotNull null
                        downloadGeneratedImage(url)
                    }
                }
        }
    }

    private suspend fun parseImageApiResult(body: JsonObject): List<ImageGenerationItem> {
        val result = body["result"]?.jsonObject ?: body
        return result["data"]?.jsonArray.orEmpty().mapNotNull { element ->
            val item = element.jsonObject
            val base64 = item["b64_json"]?.jsonPrimitive?.contentOrNull
            if (base64 != null) {
                ImageGenerationItem(data = base64, mimeType = "image/png")
            } else {
                item["url"]?.jsonPrimitive?.contentOrNull?.let { downloadGeneratedImage(it) }
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadGeneratedImage(url: String): ImageGenerationItem {
        client.newCall(Request.Builder().url(url).get().build()).await().use { response ->
            if (!response.isSuccessful) {
                error("Failed to download generated image: ${response.code} ${response.body.string()}")
            }
            val body = response.body
            return ImageGenerationItem(
                data = Base64.encode(body.bytes()),
                mimeType = body.contentType()?.toString() ?: "image/png",
            )
        }
    }

    private fun buildImageTaskUrl(
        providerSetting: ProviderSetting.Google,
        taskId: String,
    ): HttpUrl {
        return (providerSetting.baseUrl.trimEnd('/') + "/images/tasks/$taskId").toHttpUrl()
    }

    private fun JsonObject.imageTaskError(): String {
        val error = this["error"] ?: return "Unknown error"
        return (error as? JsonPrimitive)?.contentOrNull ?: error.toString()
    }

    private fun File.imageMimeType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    // Interactions API 目前只有 Gemini Developer API 提供，Vertex AI 仍走 generateContent
    private fun ProviderSetting.Google.usesInteractionsApi() = useInteractionsApi && !vertexAI

    private fun ProviderSetting.Google.usesCompatibleImageApi(): Boolean {
        val host = baseUrl.toHttpUrl().host
        return !vertexAI && host != "generativelanguage.googleapis.com"
    }

    private fun buildUrl(providerSetting: ProviderSetting.Google, path: String): HttpUrl {
        return if (!providerSetting.vertexAI) {
            "${providerSetting.baseUrl}/$path".toHttpUrl()
        } else if (providerSetting.useServiceAccount) {
            "https://aiplatform.googleapis.com/v1/projects/${providerSetting.projectId}/locations/${providerSetting.location}/$path".toHttpUrl()
        } else {
            "https://aiplatform.googleapis.com/v1/$path".toHttpUrl()
        }
    }

    private suspend fun transformRequest(
        providerSetting: ProviderSetting.Google,
        request: Request
    ): Request {
        return if (providerSetting.vertexAI && providerSetting.useServiceAccount) {
            val accessToken = serviceAccountTokenProvider.fetchAccessToken(
                serviceAccountEmail = providerSetting.serviceAccountEmail.trim(),
                privateKeyPem = StringEscapeUtils.unescapeJson(providerSetting.privateKey.trim()),
            )
            request.newBuilder()
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
        } else {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            if (providerSetting.vertexAI) {
                request.newBuilder()
                    .url(request.url.newBuilder().addQueryParameter("key", key).build())
                    .build()
            } else {
                request.newBuilder()
                    .addHeader("x-goog-api-key", key)
                    .build()
            }
        }
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Google): List<Model> =
        withContext(Dispatchers.IO) {
            val models = buildList {
                var pageToken: String? = null
                do {
                    val path = buildString {
                        append("models?pageSize=100")
                        pageToken?.let { append("&pageToken=").append(it) }
                    }
                    val response = client.newCall(
                        transformRequest(
                            providerSetting = providerSetting,
                            request = Request.Builder()
                                .url(buildUrl(providerSetting = providerSetting, path = path))
                                .headers(providerSetting.mergeCustomHeaders())
                                .get()
                                .build()
                        )
                    ).await()
                    if (!response.isSuccessful) break
                    val bodyObject = json.parseToJsonElement(response.body.string()).jsonObject
                    bodyObject["models"]?.jsonArray.orEmpty().forEach { element ->
                        val modelObject = element.jsonObject
                        val methods = modelObject["supportedGenerationMethods"]?.jsonArray
                            ?.map { method -> method.jsonPrimitive.content }
                            .orEmpty()
                        if ("generateContent" !in methods && "embedContent" !in methods) return@forEach

                        val modelId = modelObject["name"]?.jsonPrimitive?.contentOrNull
                            ?.substringAfter("/") ?: return@forEach
                        val catalogOutputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(modelId)
                        val imageModel = Modality.IMAGE in catalogOutputModalities ||
                            modelId.contains("image", ignoreCase = true)
                        val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(modelId)
                        val outputModalities = if (imageModel && Modality.IMAGE !in catalogOutputModalities) {
                            listOf(Modality.TEXT, Modality.IMAGE)
                        } else {
                            catalogOutputModalities
                        }
                        add(
                            Model(
                                modelId = modelId,
                                displayName = modelObject["displayName"]?.jsonPrimitive?.contentOrNull
                                    ?: modelId,
                                type = when {
                                    "embedContent" in methods && "generateContent" !in methods -> ModelType.EMBEDDING
                                    imageModel -> ModelType.IMAGE
                                    else -> ModelType.CHAT
                                },
                                inputModalities = if (imageModel && Modality.IMAGE !in inputModalities) {
                                    listOf(Modality.TEXT, Modality.IMAGE)
                                } else {
                                    inputModalities
                                },
                                outputModalities = outputModalities,
                                abilities = ModelRegistry.MODEL_ABILITIES.getData(modelId),
                            )
                        )
                    }
                    pageToken = bodyObject["nextPageToken"]?.jsonPrimitive?.contentOrNull
                } while (!pageToken.isNullOrBlank())
            }
            models.distinctBy { it.modelId }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        if (providerSetting.usesInteractionsApi()) {
            return@withContext interactionsAPI.generateText(providerSetting, messages, params)
        }

        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:generateContent"
            } else {
                "models/${params.model.modelId}:generateContent"
            }
        )

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .configureSessionHeaders(url.toString(), params.sessionId)
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            throw Exception("Failed to get response: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject

        val candidate = bodyJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: error(
                bodyJson["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull
                    ?.let { "Gemini blocked the prompt: $it" }
                    ?: "Gemini returned no candidates: $bodyStr"
            )
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
        if (parts.isNullOrEmpty()) {
            val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            error("Gemini returned no response content${finishReason?.let { " (finishReason=$it)" } ?: ""}")
        }
        TextGenerationResult(
            id = Uuid.random().toString(),
            model = params.model.modelId,
            message = parseMessage(candidate),
            finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull,
            usage = parseUsageMeta(bodyJson["usageMetadata"] as? JsonObject),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = if (providerSetting.usesInteractionsApi()) {
        interactionsAPI.streamText(providerSetting, messages, params)
    } else {
        streamGenerateContent(providerSetting, messages, params)
    }

    private fun streamGenerateContent(
        providerSetting: ProviderSetting.Google,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildCompletionRequestBody(messages, params)

        val url = buildUrl(
            providerSetting = providerSetting,
            path = if (providerSetting.vertexAI) {
                "publishers/google/models/${params.model.modelId}:streamGenerateContent"
            } else {
                "models/${params.model.modelId}:streamGenerateContent"
            }
        ).newBuilder().addQueryParameter("alt", "sse").build()

        val request = transformRequest(
            providerSetting = providerSetting,
            request = Request.Builder()
                .url(url)
                .headers(providerSetting.mergeCustomHeaders(params.customHeaders))
                .configureSessionHeaders(url.toString(), params.sessionId)
                .post(
                    json.encodeToString(requestBody).toRequestBody("application/json".toMediaType())
                )
                .configureReferHeaders(providerSetting.baseUrl)
                .build()
        )

        Log.i(TAG, "streamText: ${json.encodeToString(requestBody)}")

        val responseId = Uuid.random().toString()
        val decoder = GoogleStreamDecoder(responseId, params.model.modelId)

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.i(TAG, "onEvent: $data")

                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    sendChunks(result.chunks)
                    if (result.completed) close()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to parse stream event: $data", e)
                    close(e)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                var exception = t

                t?.printStackTrace()
                println("[onFailure] 发生错误: ${t?.message}")

                try {
                    if (t == null && response != null) {
                        val bodyStr = response.body.stringSafe()
                        if (!bodyStr.isNullOrEmpty()) {
                            val bodyElement = json.parseToJsonElement(bodyStr)
                            println(bodyElement)
                            if (bodyElement is JsonObject) {
                                exception = Exception(
                                    bodyElement["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                                        ?: "unknown"
                                )
                            }
                        } else {
                            exception = Exception("Unknown error: ${response.code}")
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    exception = e
                } finally {
                    close(exception ?: Exception("Stream failed"))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                println("[onClosed] 连接已关闭")
                try {
                    sendChunks(decoder.onClosed())
                    close()
                } catch (e: Throwable) {
                    close(e)
                }
            }
        }

        val eventSource = client.newEventSource(request, listener)

        awaitClose {
            println("[awaitClose] 关闭eventSource")
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): JsonObject = buildJsonObject {
        // System message if available
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        if (systemMessage != null && !params.model.outputModalities.contains(Modality.IMAGE)) {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") {
                    add(buildJsonObject {
                        put(
                            "text",
                            systemMessage.parts.filterIsInstance<UIMessagePart.Text>()
                                .joinToString { it.text })
                    })
                }
            })
        }

        // Generation config
        put("generationConfig", buildJsonObject {
            if (params.temperature != null) put("temperature", params.temperature)
            if (params.topP != null) put("topP", params.topP)
            if (params.maxTokens != null) put("maxOutputTokens", params.maxTokens)
            if (params.model.outputModalities.contains(Modality.IMAGE)) {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
            }
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)

                    val isGeminiPro =
                        params.model.modelId.contains(Regex("2\\.5.*pro", RegexOption.IGNORE_CASE))
                    val useThinkingLevel =
                        ModelRegistry.GEMINI_3_SERIES.match(modelId = params.model.modelId) ||
                            ModelRegistry.GEMINI_4.match(modelId = params.model.modelId)

                    when (params.reasoningLevel) {
                        ReasoningLevel.AUTO -> {} // 自动模式，不设置参数

                        ReasoningLevel.OFF -> {
                            if (useThinkingLevel) {
                                put("thinkingLevel", "minimal")
                            } else if (!isGeminiPro) {
                                put("thinkingBudget", 0)
                                put("includeThoughts", false)
                            }
                        }

                        else -> {
                            if (useThinkingLevel) {
                                when (params.reasoningLevel) {
                                    ReasoningLevel.LOW -> put("thinkingLevel", "low")
                                    ReasoningLevel.MEDIUM -> put("thinkingLevel", "medium")
                                    else -> put("thinkingLevel", "high") // HIGH, XHIGH
                                }
                            } else {
                                put("thinkingBudget", params.reasoningLevel.budgetTokens)
                            }
                        }
                    }
                })
            }
        })

        // Contents (user messages)
        put(
            "contents",
            buildContents(messages)
        )

        // Gemini 3+ supports combining server-side tools with function calling.
        // Older Gemini models reject that request unless the new server-side
        // invocation flag is supported, even when the built-in tool itself is
        // valid. Keep their legacy built-in-only request shape so Google Search
        // continues to work for Gemini 2.x and older models.
        val useBuiltInTools = params.model.tools.any {
            it == BuiltInTools.Search || it == BuiltInTools.UrlContext
        }
        val supportsMixedTools =
            ModelRegistry.GEMINI_3_SERIES.match(params.model.modelId) ||
                ModelRegistry.GEMINI_4.match(params.model.modelId)
        val useFunctionTools =
            params.tools.isNotEmpty() &&
                params.model.abilities.contains(ModelAbility.TOOL) &&
                (!useBuiltInTools || supportsMixedTools)
        if (useFunctionTools || useBuiltInTools) {
            putJsonArray("tools") {
                if (useFunctionTools) {
                    add(buildJsonObject {
                        putJsonArray("functionDeclarations") {
                            params.tools.forEach { tool ->
                                add(buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    put(
                                        key = "parameters",
                                        element = json.encodeToJsonElement(tool.parameters())
                                            .removeElements(
                                                listOf(
                                                    "const",
                                                    "exclusiveMaximum",
                                                    "exclusiveMinimum",
                                                    "format",
                                                    "additionalProperties",
                                                    "propertyNames",
                                                    "enum",
                                                )
                                            )
                                    )
                                })
                            }
                        }
                    })
                }
                params.model.tools.forEach { builtInTool ->
                    when (builtInTool) {
                        BuiltInTools.Search -> {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }

                        BuiltInTools.UrlContext -> {
                            add(buildJsonObject {
                                put("urlContext", buildJsonObject {})
                            })
                        }

                        else -> {}
                    }
                }
            }
        }
        if (useFunctionTools && useBuiltInTools) {
            put("toolConfig", buildJsonObject {
                put("includeServerSideToolInvocations", true)
            })
        }

        // Safety Settings
        putJsonArray("safetySettings") {
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HARASSMENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_HATE_SPEECH")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_SEXUALLY_EXPLICIT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_DANGEROUS_CONTENT")
                put("threshold", "OFF")
            })
            add(buildJsonObject {
                put("category", "HARM_CATEGORY_CIVIC_INTEGRITY")
                put("threshold", "OFF")
            })
        }
    }.mergeCustomBody(params.customBody)

    private fun commonRoleToGoogleRole(role: MessageRole): String {
        return when (role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.ASSISTANT -> "model"
            MessageRole.TOOL -> "user" // google api中, tool结果是用户role发送的
        }
    }

    private fun googleRoleToCommonRole(role: String): MessageRole {
        return when (role) {
            "user" -> MessageRole.USER
            "system" -> MessageRole.SYSTEM
            "model" -> MessageRole.ASSISTANT
            else -> error("Unknown role $role")
        }
    }

    private fun parseMessage(message: JsonObject): UIMessage {
        val role = googleRoleToCommonRole(
            message["role"]?.jsonPrimitive?.contentOrNull ?: "model"
        )
        val content = message["content"]?.jsonObject ?: error("No content")
        val parts = parseMessageParts(content["parts"]?.jsonArray)

        val groundingMetadata = message["groundingMetadata"]?.jsonObject
        Log.i(TAG, "parseMessage: $groundingMetadata")
        val annotations = parseSearchGroundingMetadata(groundingMetadata)

        return UIMessage(
            role = role,
            parts = parts,
            annotations = annotations
        )
    }

    private fun parseSearchGroundingMetadata(jsonObject: JsonObject?): List<UIMessageAnnotation> {
        if (jsonObject == null) return emptyList()
        val groundingChunks = jsonObject["groundingChunks"]?.jsonArray ?: emptyList()
        val chunks = groundingChunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            UIMessageAnnotation.UrlCitation(
                title = title,
                url = uri
            )
        }
        Log.i(TAG, "parseSearchGroundingMetadata: $chunks")
        return chunks
    }

    private fun parseMessageParts(parts: JsonArray?): List<UIMessagePart> = buildList {
        parts.orEmpty().forEachIndexed { index, element ->
            val part = parseMessagePart(element.jsonObject, index)
            if (part !is UIMessagePart.ServerTool) {
                add(part)
                return@forEachIndexed
            }

            val existingIndex = indexOfFirst {
                it is UIMessagePart.ServerTool && it.toolCallId == part.toolCallId
            }
            if (existingIndex < 0) {
                add(part)
            } else {
                val existing = get(existingIndex) as UIMessagePart.ServerTool
                set(
                    existingIndex, existing.copy(
                        toolName = part.toolName.ifBlank { existing.toolName },
                        input = part.input ?: existing.input,
                        output = part.output ?: existing.output,
                        status = if (part.isFinished) part.status else existing.status,
                        metadata = mergeGoogleMetadata(existing.metadata, part.metadata),
                    )
                )
            }
        }
    }

    private fun parseMessagePart(jsonObject: JsonObject, index: Int): UIMessagePart {
        return when {
            jsonObject.containsKey("text") -> {
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = jsonObject["text"]?.jsonPrimitive?.content ?: ""
                if (thought) UIMessagePart.Reasoning(
                    reasoning = text,
                    createdAt = Clock.System.now(),
                    finishedAt = null,
                    metadata = jsonObject.toGoogleThoughtMetadata(),
                ) else UIMessagePart.Text(
                    text = text,
                    metadata = jsonObject.toGoogleThoughtMetadata(),
                )
            }

            jsonObject.containsKey("functionCall") -> {
                val functionCall = jsonObject["functionCall"]!!.jsonObject
                val toolCallId = functionCall["id"]?.jsonPrimitive?.contentOrNull
                    ?: Uuid.random().toString()
                UIMessagePart.Tool(
                    toolCallId = toolCallId,
                    toolName = functionCall["name"]!!.jsonPrimitive.content,
                    input = json.encodeToString(functionCall["args"]),
                    output = emptyList(),
                    metadata = GoogleThoughtMetadata(
                        thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                )
            }

            jsonObject.containsKey("toolCall") -> {
                val toolCall = jsonObject["toolCall"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolCall["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolCall["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = toolCall["args"],
                    status = ServerToolStatus.IN_PROGRESS,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        call = jsonObject,
                        callIndex = index,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("toolResponse") -> {
                val toolResponse = jsonObject["toolResponse"]!!.jsonObject
                UIMessagePart.ServerTool(
                    toolCallId = toolResponse["id"]?.jsonPrimitive?.contentOrNull
                        ?: Uuid.random().toString(),
                    toolName = toolResponse["toolType"]?.jsonPrimitive?.contentOrNull ?: "",
                    output = toolResponse["response"],
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.GOOGLE_GENERATE_CONTENT,
                        result = jsonObject,
                        resultIndex = index,
                    ).toMetadata(),
                )
            }

            jsonObject.containsKey("inlineData") -> {
                val inlineData = jsonObject["inlineData"]!!.jsonObject
                val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: "image/png"
                val data = inlineData["data"]?.jsonPrimitive?.content ?: ""
                val thought = jsonObject["thought"]?.jsonPrimitive?.booleanOrNull ?: false
                val thoughtSignature = jsonObject["thoughtSignature"]?.jsonPrimitive?.contentOrNull
                require(mime.startsWith("image/")) {
                    "Only image mime type is supported"
                }
                // 如果是思考过程中的草稿图，直接忽略
                if (thought) {
                    return UIMessagePart.Reasoning(
                        reasoning = "[Draft Image]\n",
                        createdAt = Clock.System.now(),
                        finishedAt = null
                    )
                }
                UIMessagePart.Image(
                    url = "data:$mime;base64,$data",
                    metadata = GoogleThoughtMetadata(thoughtSignature = thoughtSignature)
                        .takeIf { thoughtSignature != null }
                        ?.toMetadata()
                )
            }

            else -> error("unknown message part type: $jsonObject")
        }
    }

    private fun buildContents(messages: List<UIMessage>): JsonArray {
        return buildJsonArray {
            messages
                .filter { it.role != MessageRole.SYSTEM && it.isValidToUpload() }
                .forEach { message ->
                    if (message.role == MessageRole.ASSISTANT) {
                        addModelMessage(message)
                    } else {
                        addUserMessage(message)
                    }
                }
        }
    }

    private fun JsonArrayBuilder.addModelMessage(message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val partsBuffer = mutableListOf<JsonObject>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    group.parts.flatMap { it.toGoogleParts() }.forEach { partsBuffer.add(it) }
                }

                is PartGroup.Tools -> {
                    // 添加 functionCall 到 parts 缓冲
                    group.tools.forEach { partsBuffer.add(it.toFunctionCallPart()) }

                    // 输出 model 消息
                    add(buildJsonObject {
                        put("role", "model")
                        putJsonArray("parts") { partsBuffer.forEach { add(it) } }
                    })
                    partsBuffer.clear()

                    // 紧跟 functionResponse
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            group.tools.forEach { add(it.toFunctionResponsePart()) }
                        }
                    })
                }
            }
        }

        // 输出剩余内容
        if (partsBuffer.isNotEmpty()) {
            add(buildJsonObject {
                put("role", "model")
                putJsonArray("parts") { partsBuffer.forEach { add(it) } }
            })
        }
    }

    private fun JsonArrayBuilder.addUserMessage(message: UIMessage) {
        add(buildJsonObject {
            put("role", commonRoleToGoogleRole(message.role))
            putJsonArray("parts") {
                message.parts.flatMap { it.toGoogleParts() }.forEach { add(it) }
            }
        })
    }

    private fun UIMessagePart.toGoogleParts(): List<JsonObject> = when (this) {
        is UIMessagePart.ServerTool -> toGoogleServerToolParts()
        else -> listOfNotNull(toGooglePart())
    }

    private fun UIMessagePart.toGooglePart(): JsonObject? = when (this) {
        is UIMessagePart.Text -> {
            val thoughtSignature = metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            buildJsonObject {
                put("text", text)
                thoughtSignature?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Reasoning -> {
            val thoughtSignature = metadataAs<GoogleThoughtMetadata>()?.thoughtSignature
            buildJsonObject {
                put("text", reasoning)
                put("thought", true)
                thoughtSignature?.let { put("thoughtSignature", it) }
            }
        }

        is UIMessagePart.Image -> {
            encodeBase64(false).getOrNull()?.let { encoded ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", encoded.mimeType)
                        put("data", encoded.base64)
                    })
                    metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
                        put("thoughtSignature", it)
                    }
                }
            }
        }

        is UIMessagePart.Video -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "video/mp4")
                        put("data", base64Data)
                    })
                }
            }
        }

        is UIMessagePart.Audio -> {
            encodeBase64(false).getOrNull()?.let { base64Data ->
                buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", "audio/mp3")
                        put("data", base64Data)
                    })
                }
            }
        }

        else -> null
    }

    private fun UIMessagePart.Tool.toFunctionCallPart() = buildJsonObject {
        put("functionCall", buildJsonObject {
            put("name", toolName)
            put("args", inputAsJson())
            put("id", toolCallId)
        })
        metadataAs<GoogleThoughtMetadata>()?.thoughtSignature?.let {
            put("thoughtSignature", it)
        }
    }

    private fun UIMessagePart.Tool.toFunctionResponsePart() = buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", toolName)
                put("id", toolCallId)

                // 1. 拆分出纯文本部分
                val textParts = output.filterIsInstance<UIMessagePart.Text>()
                
                // 2. 提取所有的多模态(图片/视频/音频)，并直接转为 Google 要求的格式
                // 过滤出最终包含 inlineData 的数据块
                val mediaGoogleParts = output
                    .filter { it !is UIMessagePart.Text }
                    .mapNotNull { it.toGooglePart() }
                    .filter { it.containsKey("inlineData") } 

                // 3. 构建给模型看的结构化 response 节点
                put("response", buildJsonObject {
                    // 处理文本结果
                    if (textParts.isNotEmpty()) {
                        put(
                            "result", 
                            textParts.joinToString("\n") { it.text }
                        )
                    } else if (mediaGoogleParts.isEmpty()) {
                        // 如果工具啥都没返回，给个兜底成功状态
                        put("result", " ")
                    }

                    // 处理媒体数据（图片、音频、视频），打上 $ref 标签
                    mediaGoogleParts.forEachIndexed { index, _ ->
                        val refName = "media_ref_$index"
                        put(refName, buildJsonObject {
                            put("\$ref", refName)
                        })
                    }
                })

                // 4. 将真实的 Base64 多媒体数据挂载到 parts 中，并建立指针绑定
                if (mediaGoogleParts.isNotEmpty()) {
                    putJsonArray("parts") {
                        mediaGoogleParts.forEachIndexed { index, googlePart ->
                            val refName = "media_ref_$index"
                            val inlineData = googlePart["inlineData"]!!.jsonObject

                            add(buildJsonObject {
                                // 重新组装 inlineData，并在内部注入 displayName
                                put("inlineData", buildJsonObject {
                                    // 复制原有的 mimeType 和 data
                                    inlineData.forEach { (k, v) -> put(k, v) }
                                    // 添加能够让 $ref 认出它的唯一名称
                                    put("displayName", refName)
                                })
                                
                                // 保留可能存在的其他字段
                                googlePart.forEach { (k, v) ->
                                    if (k != "inlineData") put(k, v)
                                }
                            })
                        }
                    }
                }
            })
        }

    private fun UIMessagePart.ServerTool.toGoogleServerToolParts(): List<JsonObject> {
        val metadata = metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.GOOGLE_GENERATE_CONTENT) {
            return emptyList()
        }

        return buildList {
            metadata?.call?.let(::add)
            metadata?.result?.let(::add)
        }
    }

    private fun JsonObject.toGoogleThoughtMetadata() =
        this["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let {
            GoogleThoughtMetadata(thoughtSignature = it).toMetadata()
        }

    private fun mergeGoogleMetadata(first: JsonObject?, second: JsonObject?): JsonObject? = when {
        first == null -> second
        second == null -> first
        else -> JsonObject(first + second)
    }

    private fun parseUsageMeta(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) {
            return null
        }
        val promptTokens = jsonObject["promptTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val thoughtTokens = jsonObject["thoughtsTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val cachedTokens = jsonObject["cachedContentTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val candidatesTokens = jsonObject["candidatesTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        val totalTokens = jsonObject["totalTokenCount"]?.jsonPrimitiveOrNull?.intOrNull ?: 0
        return TokenUsage(
            promptTokens = promptTokens,
            completionTokens = candidatesTokens + thoughtTokens,
            totalTokens = totalTokens,
            cachedTokens = cachedTokens
        )
    }
}
