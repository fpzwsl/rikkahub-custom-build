package me.rerere.ai.provider

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.fetchBody
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

private val TEMPLATE_VARIABLE = Regex("""\{\{\s*(\w+)\s*\}\}""")

/** 替换模板里的 `{{name}}`，引用了不存在的变量时报错 */
fun renderQuotaTemplate(template: String, variable: (String) -> String?): String =
    TEMPLATE_VARIABLE.replace(template) { match ->
        val name = match.groupValues[1]
        variable(name) ?: throw IllegalArgumentException("Unknown variable {{$name}}")
    }

/**
 * 执行 API Key 类提供商的自定义额度查询（GET），没有开启时返回 null。
 *
 * 先放提供商级的自定义请求头，再写入查询配置的请求头，同名时以查询配置为准。
 */
internal suspend fun OkHttpClient.runQuotaQuery(
    setting: ProviderSetting.ApiKey,
    nextApiKey: () -> String,
): ProviderQuota.Balance? = withContext(Dispatchers.IO) {
    val query = setting.quotaQuery.takeIf { it.enabled } ?: return@withContext null
    // URL 和请求头引用的是同一个 key
    val apiKey by lazy(nextApiKey)
    val render = { template: String ->
        renderQuotaTemplate(template) { name ->
            when (name) {
                QuotaQuery.VARIABLE_BASE_URL -> setting.baseUrl
                QuotaQuery.VARIABLE_API_KEY -> apiKey
                else -> null
            }
        }
    }
    if (query.javascript.isNotBlank()) {
        return@withContext runJavascriptQuotaQuery(this@runQuotaQuery, setting, query, render)
    }
    val request = Request.Builder()
        .url(render(query.url.trim()))
        .headers(setting.mergeCustomHeaders())
        .apply {
            query.headers.filter { it.name.isNotBlank() }
                .forEach { header(it.name.trim(), render(it.value)) }
        }
        .get()
        .build()
    val value = json.parseToJsonElement(fetchBody(request)).jsonObject.getByKey(query.resultPath)
    ProviderQuota.Balance(value.toDoubleOrNull()?.let { "%.2f".format(it) } ?: value)
}

private suspend fun runJavascriptQuotaQuery(
    client: OkHttpClient,
    setting: ProviderSetting.ApiKey,
    query: QuotaQuery,
    render: (String) -> String,
): ProviderQuota.Balance = withContext(Dispatchers.IO) {
    val config = evaluateQuotaConfig(query.javascript)
    val requestConfig = config["request"]?.jsonObject ?: error("JavaScript quota config.request is required")
    val url = render(requestConfig["url"]?.jsonPrimitive?.contentOrNull ?: error("request.url is required"))
    val method = requestConfig["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
    val builder = Request.Builder().url(url).headers(setting.mergeCustomHeaders())
    requestConfig["headers"]?.jsonObject?.forEach { (name, value) ->
        builder.header(name, render(value.jsonPrimitive.content))
    }
    val body = requestConfig["body"]?.let { render(it.toString()) }
    if (method == "GET" || method == "HEAD") builder.method(method, null)
    else {
        if (body != null) builder.header("Content-Type", "application/json")
        builder.method(method, body?.toRequestBody("application/json".toMediaType()))
    }
    val request = builder.build()
    val response = client.newCall(request).await().use { raw ->
        val text = raw.body.string()
        val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
        val responseObject = buildJsonObject {
            (parsed as? JsonObject)?.forEach { (key, value) -> put(key, value) }
            put("status", raw.code)
            putJsonObject("headers") { raw.headers.toMultimap().forEach { (key, values) -> put(key, values.firstOrNull().orEmpty()) } }
            put("body", text)
            if (parsed != null) put("json", parsed)
            if (!raw.isSuccessful) put("error", "HTTP ${raw.code}")
        }
        evaluateQuotaExtractor(query.javascript, responseObject)
    }
    val remaining = response["remaining"]?.jsonPrimitive?.contentOrNull
    ProviderQuota.Balance(
        value = remaining?.let { it.toDoubleOrNull()?.let { n -> "%.2f".format(n) } ?: it }
            ?: response["extra"]?.jsonPrimitive?.contentOrNull
            ?: response["invalidMessage"]?.jsonPrimitive?.contentOrNull
            ?: "-",
        isValid = response["isValid"]?.jsonPrimitive?.booleanOrNull,
        invalidMessage = response["invalidMessage"]?.jsonPrimitive?.contentOrNull,
        unit = response["unit"]?.jsonPrimitive?.contentOrNull,
        planName = response["planName"]?.jsonPrimitive?.contentOrNull,
        total = response["total"]?.jsonPrimitive?.doubleOrNull,
        used = response["used"]?.jsonPrimitive?.doubleOrNull,
        extra = response["extra"]?.jsonPrimitive?.contentOrNull,
    )
}

private suspend fun evaluateQuotaConfig(source: String): JsonObject = evaluateQuotaJavascript(
    """
    (() => {
      const config = (0, eval)(${JsonPrimitive(source)});
      if (!config || typeof config !== 'object') throw new Error('Quota config must be an object');
      return JSON.stringify({request: config.request || {}, hasExtractor: typeof config.extractor === 'function'});
    })()
    """.trimIndent()
).let { json.parseToJsonElement(it).jsonObject }

private suspend fun evaluateQuotaExtractor(source: String, response: JsonObject): JsonObject = evaluateQuotaJavascript(
    """
    (() => {
      const config = (0, eval)(${JsonPrimitive(source)});
      const response = ${json.encodeToString(response)};
      const parsed = typeof response === 'string' ? JSON.parse(response) : response;
      const result = typeof config.extractor === 'function' ? config.extractor(parsed) : null;
      return JSON.stringify(result || {});
    })()
    """.trimIndent()
).let { json.parseToJsonElement(it).jsonObject }

private suspend fun evaluateQuotaJavascript(code: String): String = try {
    quickJs(Dispatchers.Default) {
        memoryLimit = 16L * 1024 * 1024
        maxStackSize = 128L * 1024
        evaluationTimeoutMillis = 5_000
        evaluate<String?>(code) ?: error("JavaScript returned no result")
    }
} catch (e: QuickJsInterruptedException) {
    error("JavaScript quota extractor timed out")
} catch (e: QuickJsException) {
    error("JavaScript quota extractor failed: ${e.message}")
}
