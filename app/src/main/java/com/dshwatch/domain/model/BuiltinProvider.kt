package com.dshwatch.domain.model

/**
 * 内置供应商注册表。
 *
 * ## 为什么是「白名单」而不是拉 `/v1/models`
 *
 * 两家账号的模型列表接口都会把**付费档**一起列出来，而且不标价：
 * Agnes 的 9 个模型里有 4 个付费，智谱的列表里也有大量按量计费的模型。
 * 用户在手表上翻三层设置、改一次模型、再发一条消息，才发现自己选了不能用的那个 ——
 * 在 200dp 的屏幕上这个成本高得离谱。
 *
 * 所以这里各硬编码一份**只含免费模型**的名单，且列表不联网刷新。
 * 代价是官方调整免费档时要发版，收益是用户永远不会选到要付费的模型。
 *
 * ## 为什么 key 单独取
 *
 * 内置 key 通过 `BuildConfig` 注入（构建期从环境变量 / local.properties 读），
 * 不进 git。设置页对内置项**不显示也不允许修改** key 与地址 ——
 * 用户能改的只有模型参数（温度、上下文、max tokens…），这部分本来就需要调。
 */
object BuiltinProviders {

    const val AGNES_ID = "builtin-agnes"
    const val ZHIPU_ID = "builtin-zhipu"

    /** 一家内置供应商的静态描述。key 用函数取，避免在类加载期就把字符串钉在内存里 */
    data class Spec(
        val id: String,
        val name: String,
        val baseUrl: String,
        val models: List<String>,
        val defaultModel: String,
        val visionInput: Boolean,
        /** 真正能吃图片的模型。只有列在这里的才会把附图发出去 */
        val visionModels: Set<String>,
        val contextWindow: Int,
        val maxTokens: Int,
        /** 设置页给用户看的说明：为什么看不到 key */
        val note: String,
        val key: () -> String,
    )

    /**
     * Agnes：2 文本 + 2 出图 + 1 视频。
     *
     * 判定依据是逐个实测：这 5 个都能直接出结果；
     * `Pro` 系列与 `agnes-video-2.5` 返回 `insufficient_user_quota`。
     */
    val AGNES = Spec(
        id = AGNES_ID,
        name = "Agnes 内置",
        baseUrl = "https://api.agnes-ai.cn/v1",
        models = listOf(
            "agnes-3.0-flash",
            "agnes-2.5-flash",
            "agnes-image-2.5-flash",
            "agnes-image-2.1-flash",
            "agnes-video-2.5-flash",
        ),
        defaultModel = "agnes-3.0-flash",
        visionInput = true,
        // 实测这三个文本 / 出图模型都能吃 image_url；出视频模型走另一条链，不进对话
        visionModels = setOf("agnes-3.0-flash", "agnes-2.5-flash"),
        contextWindow = 128_000,
        maxTokens = 2048,
        note = "内置通道，开箱即用。Key 由应用统一提供，不显示也不能修改。",
        key = { com.dshwatch.BuildConfig.BUILTIN_API_KEY },
    )

    /**
     * 智谱 GLM 免费档：2 文本 + 1 出图。
     *
     * `glm-4.6v-flash` 是视觉模型（图片输入），但**不是**出图模型；
     * `cogview-3-flash` 才是出图，走 `/images/generations`。
     * 两个名字都带 flash，能力完全不同 —— 别靠命名猜，按能力登记。
     */
    val ZHIPU = Spec(
        id = ZHIPU_ID,
        name = "智谱 GLM 内置",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        models = listOf(
            "glm-4.7-flash",
            "glm-4.6v-flash",
            "cogview-3-flash",
        ),
        defaultModel = "glm-4.7-flash",
        visionInput = true,
        /*
         * 只有 V 系列能吃图。
         *
         * glm-4.7-flash 是纯文本模型，给它发 image_url 会直接 400；
         * 而供应商级的 visionInput 是给「整家能不能读图」用的，粒度太粗，
         * 所以这里再按模型收一道 —— 用户在智谱通道里选了文本模型又附了图时，
         * 图会留在会话里可回看，但不进请求。
         */
        visionModels = setOf("glm-4.6v-flash"),
        contextWindow = 128_000,
        maxTokens = 4096,
        note = "内置通道，只提供免费模型。Key 由应用统一提供，不显示也不能修改。" +
            "免费档高峰期会限流（429），换 Agnes 通道或稍后再试。",
        key = { com.dshwatch.BuildConfig.ZHIPU_API_KEY },
    )

    val all: List<Spec> = listOf(AGNES, ZHIPU)

    fun of(providerId: String): Spec? = all.firstOrNull { it.id == providerId }

    fun isBuiltin(providerId: String): Boolean = of(providerId) != null

    fun make(spec: Spec): Provider = Provider(
        id = spec.id,
        name = spec.name,
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = spec.baseUrl,
        models = spec.models,
        defaultModel = spec.defaultModel,
        temperature = 0.7f,
        topP = 1f,
        maxTokens = spec.maxTokens,
        contextWindow = spec.contextWindow,
        presetId = "vibe_coding",
        visionInput = spec.visionInput,
    )

    /**
     * 内置项的 key：用户没填过就用内置值。
     *
     * 这样「开箱能用」和「用户想换成自己的 key」可以共存 ——
     * 虽然界面上不展示这一栏，但存档里若已有用户自定义值（老版本填过的），
     * 仍然优先尊重它，不会因为隐藏输入框而把用户的 key 顶掉。
     */
    fun keyOf(providerId: String): String? =
        of(providerId)?.key?.invoke()?.takeIf { it.isNotBlank() }
}

/**
 * 兼容入口。
 *
 * 1.16 之前只有 Agnes 一家，代码里到处是 `BuiltinProvider.xxx`。
 * 保留这层薄壳，改造成本从「全库替换」降成「只在需要多家的地方用新 API」。
 */
object BuiltinProvider {

    val ID: String get() = BuiltinProviders.AGNES_ID
    const val NAME = "Agnes 内置"
    const val BASE_URL = "https://api.agnes-ai.cn/v1"
    val MODELS: List<String> get() = BuiltinProviders.AGNES.models
    const val DEFAULT_MODEL = "agnes-3.0-flash"

    fun isBuiltin(providerId: String): Boolean = BuiltinProviders.isBuiltin(providerId)

    fun matches(provider: Provider): Boolean = BuiltinProviders.isBuiltin(provider.id)

    fun make(): Provider = BuiltinProviders.make(BuiltinProviders.AGNES)
}
