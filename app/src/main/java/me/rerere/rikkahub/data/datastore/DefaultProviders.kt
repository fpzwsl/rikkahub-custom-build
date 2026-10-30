package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaQuery
import kotlin.uuid.Uuid

/** Previously bundled relay entries that should disappear from existing settings as well. */
internal val REMOVED_DEFAULT_PROVIDER_IDS = setOf(
    Uuid.parse("1b1395ed-b702-4aeb-8bc1-b681c4456953"), // AiHubMix
    Uuid.parse("2a05506f-3a59-450a-a493-33a82bc85a81"), // APIMart
    Uuid.parse("56a94d29-c88b-41c5-8e09-38a7612d6cf8"), // 硅基流动
    Uuid.parse("d5734028-d39b-4d41-9841-fd648d65440e"), // OpenRouter
    Uuid.parse("386e0f29-8228-4512-affe-8fd8add82d88"), // Vercel AI Gateway
    Uuid.parse("f76cae46-069a-4334-ab8e-224e4979e58c"), // 阿里云百炼
    Uuid.parse("3dfd6f9b-f9d9-417f-80c1-ff8d77184191"), // 火山引擎
    Uuid.parse("f4f8870e-82d3-495b-9b64-d58e508b3b2c"), // 阶跃星辰
    Uuid.parse("da93779f-3956-48cc-82ef-67bb482eaaf7"), // 302.AI
    Uuid.parse("ef5d149b-8e34-404b-818c-6ec242e5c3c5"), // 腾讯Hunyuan
    Uuid.parse("aecf04fd-cb5c-4582-aed2-e8bf393923fd"), // 随想AI网关
    Uuid.parse("afbc54ad-807e-4455-9594-7d7a546356ad"), // MaruCode
    Uuid.parse("a2bafe83-eaf8-47bf-a8c7-3dd82d89f637"), // MIMO
)

val DEFAULT_PROVIDERS = listOf(
    ProviderSetting.OpenAI(
        id = Uuid.parse("1eeea727-9ee5-4cae-93e6-6fb01a4d051e"),
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true
    ),
    ProviderSetting.Google(
        id = Uuid.parse("6ab18148-c138-4394-a46f-1cd8c8ceaa6d"),
        name = "Gemini",
        apiKey = "",
        enabled = true,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("f099ad5b-ef03-446d-8e78-7e36787f780b"),
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "",
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/user/balance",
            resultPath = "balance_infos[0].total_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3"),
        name = "月之暗面",
        baseUrl = "https://api.moonshot.cn/v1",
        apiKey = "",
        enabled = true,
        builtIn = true,
        quotaQuery = QuotaQuery(
            enabled = true,
            url = "{{baseUrl}}/users/me/balance",
            resultPath = "data.available_balance"
        )
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("3bc40dc1-b11a-46fa-863b-6306971223be"),
        name = "智谱AI开放平台",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        apiKey = "",
        enabled = false,
        builtIn = true
    ),
    ProviderSetting.OpenAI(
        id = Uuid.parse("ff3cde7e-0f65-43d7-8fb2-6475c99f5990"),
        name = "xAI",
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
        useResponseApi = true,
    ),
    ProviderSetting.Claude(
        id = Uuid.parse("b4deabea-20fb-4101-a74c-65679c7e4754"),
        name = "MiniMax",
        baseUrl = "https://api.minimaxi.com/anthropic/v1",
        apiKey = "",
        enabled = false,
        builtIn = true,
    ),
)
