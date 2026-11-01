package me.rerere.mediagen.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.mediagen.model.MediaGenerationInput
import me.rerere.mediagen.model.MediaGenerationError
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaGenerationOutput
import me.rerere.mediagen.model.MediaGenerationRequest
import me.rerere.mediagen.model.MediaGenerationStatus
import me.rerere.mediagen.model.MediaGenerationTask
import me.rerere.mediagen.model.MediaGenerationUsage
import me.rerere.mediagen.provider.MediaGenerationProvider
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.mediagen.provider.providers.executeJson
import me.rerere.mediagen.provider.providers.imageMimeType
import me.rerere.mediagen.provider.providers.long
import me.rerere.mediagen.provider.providers.obj
import me.rerere.mediagen.provider.providers.postJson
import me.rerere.mediagen.provider.providers.string
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * 同时兼容标准 OpenAI Images 协议和 RightAPI 的异步 OpenAI/Gemini 图像协议。
 */
class OpenAIMediaGenerationProvider(
    private val client: OkHttpClient,
) : MediaGenerationProvider<MediaGenerationProviderSetting.OpenAI> {
    override val id: String = PROVIDER_ID

    override suspend fun create(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): Result<MediaGenerationTask> = runCatching {
        val baseUrl = if (isRightApi(setting)) setting.baseUrl.rightApiBaseUrl()
        else setting.baseUrl.trimEnd('/')
        if (isRightApi(setting)) {
            if (isGeminiImageModel(model)) {
                val url = "$baseUrl/v1beta/models/${model.modelId}:generateContent"
                val httpRequest = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer ${setting.apiKey}")
                    .postJson(buildGeminiBody(model, request))
                    .build()
                return@runCatching parseRightApiTask(
                    client.executeJson(httpRequest, id),
                    model.modelId,
                )
            }

            val httpRequest = if (request.inputs.isEmpty()) {
                Request.Builder()
                    .url("$baseUrl/v1/images/generations")
                    .addHeader("Authorization", "Bearer ${setting.apiKey}")
                    .postJson(buildGenerationBody(setting, model, request, async = true))
                    .build()
            } else {
                Request.Builder()
                    .url("$baseUrl/v1/images/edits")
                    .addHeader("Authorization", "Bearer ${setting.apiKey}")
                    .post(buildEditBody(setting, model, request, async = true))
                    .build()
            }
            return@runCatching parseRightApiTask(client.executeJson(httpRequest, id), model.modelId)
        }

        val builder = Request.Builder().addHeader("Authorization", "Bearer ${setting.apiKey}")
        val httpRequest = if (request.inputs.isEmpty()) {
            builder.url("$baseUrl/images/generations").postJson(buildGenerationBody(setting, model, request))
        } else {
            builder.url("$baseUrl/images/edits").post(buildEditBody(setting, model, request))
        }.build()
        parseTask(client.executeJson(httpRequest, id), model.modelId)
    }

    override suspend fun query(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        taskId: String,
    ): Result<MediaGenerationTask> = runCatching {
        if (!isRightApi(setting)) throw UnsupportedOperationException("OpenAI image generation has no query endpoint")
        val url = "${setting.baseUrl.rightApiBaseUrl()}/v1/tasks/${taskId.encodeUrlPath()}"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${setting.apiKey}")
            .get()
            .build()
        parseRightApiTask(client.executeJson(request, id), model.modelId, taskId)
    }

    internal fun buildGenerationBody(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
        async: Boolean = false,
    ): JsonObject = buildJsonObject {
        validate(request)
        request.extraParameters.forEach { (key, value) -> put(key, value) }
        put("model", model.modelId)
        put("prompt", request.prompt)
        request.count?.let { put("n", it) }
        request.quality?.takeIf(String::isNotBlank)?.let { put("quality", it) }
        resolveSize(setting, model, request.resolution)?.let { put("size", it) }
        if (async) put("async", true)
    }

    internal fun buildEditBody(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
        async: Boolean = false,
    ): MultipartBody {
        validate(request)
        val fields = linkedMapOf<String, String>()
        request.extraParameters.forEach { (key, value) ->
            fields[key] = (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
        }
        fields["model"] = model.modelId
        fields["prompt"] = request.prompt.orEmpty()
        request.count?.let { fields["n"] = it.toString() }
        request.quality?.takeIf(String::isNotBlank)?.let { fields["quality"] = it }
        resolveSize(setting, model, request.resolution)?.let { fields["size"] = it }
        if (async) fields["async"] = "true"

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (key, value) -> builder.addFormDataPart(key, value) }

        val imageFieldName = if (request.inputs.size == 1) "image" else "image[]"
        request.inputs.forEach { input ->
            require(input is MediaGenerationInput.Image) {
                "OpenAI image generation does not support ${input::class.simpleName} input"
            }
            val file = File(input.url.removePrefix("file://"))
            require(file.exists()) { "Image file does not exist: ${input.url}" }
            val mediaType = EDIT_IMAGE_MEDIA_TYPES[file.extension.lowercase()]
                ?: throw IllegalArgumentException("Unsupported image file type for OpenAI edit: ${file.extension}")
            builder.addFormDataPart(imageFieldName, file.name, file.asRequestBody(mediaType.toMediaType()))
        }
        return builder.build()
    }

    internal fun buildGeminiBody(
        model: MediaGenerationModel,
        request: MediaGenerationRequest,
    ): JsonObject = buildJsonObject {
        validateGemini(request)
        put("async", true)
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    request.prompt?.takeIf(String::isNotBlank)?.let { prompt ->
                        add(buildJsonObject { put("text", prompt) })
                    }
                    request.inputs.forEach { input ->
                        require(input is MediaGenerationInput.Image) {
                            "Gemini image generation does not support ${input::class.simpleName} input"
                        }
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", imageMimeTypeForInput(input.url))
                                put("data", readImageBase64(input.url))
                            })
                        })
                    }
                })
            })
        })
        put("generationConfig", buildJsonObject {
            put("imageConfig", buildJsonObject {
                request.aspectRatio?.takeIf(String::isNotBlank)?.let { put("aspectRatio", it) }
                request.resolution?.takeIf(String::isNotBlank)?.let { put("imageSize", it) }
            })
        })
    }

    internal fun parseRightApiTask(
        root: JsonObject,
        fallbackModel: String? = null,
        fallbackTaskId: String? = null,
    ): MediaGenerationTask {
        val taskId = root.string("task_id") ?: fallbackTaskId
        val status = root.string("status")?.toRightApiStatus()
        if (taskId != null && root["data"] == null && root["candidates"] == null) {
            return MediaGenerationTask(
                id = taskId,
                provider = id,
                model = fallbackModel,
                status = status ?: MediaGenerationStatus.QUEUED,
                error = if (status == MediaGenerationStatus.FAILED) {
                    MediaGenerationError(message = root.errorMessage())
                } else null,
                metadata = root,
            )
        }

        val data = (root["data"] as? JsonArray).orEmpty()
        val openAiOutputs = data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            item.string("url")?.takeIf(String::isNotBlank)?.let { url ->
                MediaGenerationOutput(url = url, mimeType = "image/png")
            }
        }
        val geminiOutputs = root["candidates"]?.jsonArray.orEmpty().flatMap { candidate ->
            (candidate.jsonObject["content"]?.jsonObject?.get("parts") as? JsonArray)
                .orEmpty()
                .mapNotNull { part ->
                    (part as? JsonObject)?.string("text")?.takeIf { it.startsWith("http") }?.let { url ->
                        MediaGenerationOutput(url = url, mimeType = "image/png")
                    }
                }
        }
        val outputs = openAiOutputs + geminiOutputs
        return MediaGenerationTask(
            id = taskId ?: Uuid.random().toString(),
            provider = id,
            model = fallbackModel,
            status = if (outputs.isNotEmpty()) MediaGenerationStatus.SUCCEEDED
            else status ?: MediaGenerationStatus.FAILED,
            outputs = outputs,
            error = if (outputs.isEmpty() && status == MediaGenerationStatus.FAILED) {
                MediaGenerationError(message = root.errorMessage())
            } else null,
            createdAtEpochSeconds = root.long("created_at") ?: root.long("created"),
            metadata = root,
        )
    }

    internal fun parseTask(root: JsonObject, fallbackModel: String? = null): MediaGenerationTask {
        val defaultFormat = root.string("output_format")
        val data = root["data"] as? JsonArray ?: error("OpenAI response does not contain data")
        return MediaGenerationTask(
            // 同步接口没有服务端任务 ID
            id = Uuid.random().toString(),
            provider = id,
            model = fallbackModel,
            status = MediaGenerationStatus.SUCCEEDED,
            outputs = data.map { element ->
                val item = element.jsonObject
                // 部分中转同时给 url 和空字符串的 b64_json，空串当作没有
                val base64 = item.string("b64_json")?.takeIf { it.isNotBlank() }
                // dall-e 系列和部分中转不返回 b64_json，而是有时效的 url
                val url = item.string("url")?.takeIf { it.isNotBlank() }
                if (base64 == null && url == null) error("OpenAI image contains neither b64_json nor url")
                MediaGenerationOutput(
                    url = url,
                    data = base64?.let { Base64.decode(it.substringAfter("base64,")) },
                    mimeType = imageMimeType(item.string("output_format") ?: defaultFormat, "image/png"),
                    resolution = root.string("size"),
                )
            },
            createdAtEpochSeconds = root.long("created"),
            usage = root.obj("usage")?.let {
                MediaGenerationUsage(totalTokens = it.long("total_tokens"))
            },
            // data 里是整张图片的 base64，不放进 metadata
            metadata = JsonObject(root - "data"),
        )
    }

    private fun validate(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank()) { "OpenAI image generation requires a non-empty prompt" }
        require(request.quality == null || request.quality in setOf("auto", "low", "medium", "high", "xhigh", "max")) {
            "OpenAI image generation quality must be auto, low, medium, high, xhigh, or max"
        }
        require(request.aspectRatio == null) { "OpenAI image generation uses resolution instead of aspectRatio" }
        require(request.durationSeconds == null) { "OpenAI image generation does not expose duration" }
        require(request.generateAudio == null) { "OpenAI image generation does not expose generateAudio" }
        require(request.watermark == null) { "OpenAI image generation does not expose watermark" }
        require(request.seed == null) { "OpenAI image generation does not expose seed" }
        require(request.promptEnhancement == null) { "OpenAI image generation does not expose prompt enhancement" }
        require(request.callbackUrl == null) { "OpenAI image generation returns results synchronously" }
    }

    private fun validateGemini(request: MediaGenerationRequest) {
        require(!request.prompt.isNullOrBlank() || request.inputs.isNotEmpty()) {
            "Gemini image generation requires a prompt or image input"
        }
        require(request.count == null || request.count == 1) {
            "Gemini image generation supports one image per task"
        }
        require(request.resolution == null || request.resolution in setOf("1K", "2K", "4K")) {
            "Gemini image generation resolution must be 1K, 2K, or 4K"
        }
        require(request.durationSeconds == null) { "Gemini image generation does not expose duration" }
        require(request.generateAudio == null) { "Gemini image generation does not expose generateAudio" }
        require(request.watermark == null) { "Gemini image generation does not expose watermark" }
        require(request.seed == null) { "Gemini image generation does not expose seed" }
        require(request.promptEnhancement == null) { "Gemini image generation does not expose prompt enhancement" }
    }

    // "auto" 交给服务端默认值；Grok 的兼容接口不接受 size
    private fun resolveSize(
        setting: MediaGenerationProviderSetting.OpenAI,
        model: MediaGenerationModel,
        size: String?,
    ): String? {
        if (size.isNullOrBlank() || size.equals("auto", ignoreCase = true)) return null
        return size.takeUnless { isGrok(setting, model) }
    }

    // 只匹配 x.ai 本身及其子域名，避免 "xxx-max.ai" 之类的中转域名被误判
    private fun isGrok(setting: MediaGenerationProviderSetting.OpenAI, model: MediaGenerationModel): Boolean {
        val host = setting.baseUrl.toHttpUrlOrNull()?.host?.lowercase()
        return host == "x.ai" || host?.endsWith(".x.ai") == true || model.modelId.contains("grok", ignoreCase = true)
    }

    private fun isRightApi(setting: MediaGenerationProviderSetting.OpenAI): Boolean =
        setting.baseUrl.toHttpUrlOrNull()?.host?.equals("rightapi.ai", ignoreCase = true) == true

    /** RightAPI documents https://rightapi.ai as the base URL; accept /v1 too for user convenience. */
    private fun String.rightApiBaseUrl(): String =
        trimEnd('/').removeSuffix("/v1").trimEnd('/')

    private fun isGeminiImageModel(model: MediaGenerationModel): Boolean =
        model.modelId.startsWith("gemini-", ignoreCase = true) ||
            model.modelId.contains("nano-banana", ignoreCase = true)

    private fun String.toRightApiStatus(): MediaGenerationStatus = when (lowercase()) {
        "queued", "processing" -> MediaGenerationStatus.QUEUED
        "in_progress", "running" -> MediaGenerationStatus.RUNNING
        "failed" -> MediaGenerationStatus.FAILED
        "expired" -> MediaGenerationStatus.EXPIRED
        "canceled", "cancelled" -> MediaGenerationStatus.CANCELLED
        else -> MediaGenerationStatus.UNKNOWN
    }

    private fun JsonObject.errorMessage(): String {
        val error = this["error"]
        return when (error) {
            is JsonObject -> error.string("message") ?: error.toString()
            is JsonPrimitive -> error.contentOrNull ?: error.toString()
            else -> string("message") ?: "RightAPI image generation failed"
        }
    }

    private fun imageMimeTypeForInput(url: String): String {
        val lower = url.substringBefore('?').lowercase()
        return when {
            lower.startsWith("data:image/jpeg") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.startsWith("data:image/webp") || lower.endsWith(".webp") -> "image/webp"
            else -> "image/png"
        }
    }

    private fun readImageBase64(url: String): String {
        if (url.startsWith("data:")) return url.substringAfter(",")
        val file = File(url.removePrefix("file://"))
        require(file.isFile) { "Gemini reference image must be a local file or data URI: $url" }
        return Base64.encode(file.readBytes())
    }

    private fun String.encodeUrlPath(): String =
        java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")

    private companion object {
        const val PROVIDER_ID = "openai"
        val EDIT_IMAGE_MEDIA_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
        )
    }
}
