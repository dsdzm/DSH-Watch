package com.dshwatch.domain.model

/**
 * 内置供应商：开箱即用的 Agnes 通道。
 *
 * ## 为什么不拉 `/v1/models`
 *
 * 该账号下 `/v1/models` 会列出 9 个模型，其中 4 个是**付费**的
 * （`agnes-2.5-pro` / `-pro-alpha` / `-pro-beta` / `agnes-video-2.5`）。
 * 列表接口不标价，用户点进去只会得到一句「用户额度不足，剩余额度 ￥0.000000」——
 * 在手表上要为此翻三层设置、改一次模型、再发一条消息才能发现自己选了不能用的模型。
 *
 * 所以这里硬编码一份**只含免费模型**的白名单，并且这个供应商的列表**不联网刷新**。
 * 代价是官方调整免费档时需要发版更新，收益是用户永远不会选到要付费的模型。
 */
object BuiltinProvider {

    const val ID = "builtin-agnes"
    const val NAME = "Agnes 内置"

    const val BASE_URL = "https://api.agnes-ai.cn/v1"

    /**
     * 免费档。顺序即展示顺序：先文本（日常用得最多），再出图，最后视频。
     *
     * 判定依据是逐个实测：这 5 个都能直接出结果；
     * `Pro` 系列与 `agnes-video-2.5` 返回 `insufficient_user_quota`。
     */
    val MODELS: List<String> = listOf(
        "agnes-3.0-flash",
        "agnes-2.5-flash",
        "agnes-image-2.5-flash",
        "agnes-image-2.1-flash",
        "agnes-video-2.5-flash",
    )

    const val DEFAULT_MODEL = "agnes-3.0-flash"

    fun isBuiltin(providerId: String): Boolean = providerId == ID

    fun matches(provider: Provider): Boolean = provider.id == ID || provider.isBuiltin

    fun make(): Provider = Provider(
        id = ID,
        name = NAME,
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = BASE_URL,
        models = MODELS,
        defaultModel = DEFAULT_MODEL,
        temperature = 0.7f,
        topP = 1f,
        maxTokens = 2048,
        contextWindow = 128_000,
        presetId = "vibe_coding",
        visionInput = true,
    )
}
