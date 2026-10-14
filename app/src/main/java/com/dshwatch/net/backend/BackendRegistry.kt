package com.dshwatch.net.backend

import com.dshwatch.domain.model.ProviderType
import kotlinx.serialization.json.Json

open class BackendRegistry(json: Json) {

    val openAiCompat = OpenAiCompatBackend(json)
    val dshServer = DshServerBackend(json)

    /**
     * 对外暴露的后端统一包一层 [IoBackend]。
     *
     * 起因是真机上的一行报错：`拉取失败: NetworkOnMainThreadException` ——
     * `listModels()` 当时是裸的阻塞网络调用，被 UI 的 Main dispatcher 直接调了。
     * 这类问题在 JVM 单测里永远抓不到（没有主线程网络检查），所以用包装器把它结构性地堵死：
     * 只要从 [of] 拿后端，就不可能跑在主线程上。
     */
    private val ioOpenAi: ChatBackend = IoBackend(openAiCompat)
    private val ioDsh: ChatBackend = IoBackend(dshServer)

    /** open 是为了让单测替换成假后端，避免测试真的发网络请求 */
    open fun of(type: ProviderType): ChatBackend = when (type) {
        ProviderType.OPENAI_COMPAT -> ioOpenAi
        ProviderType.DSH_SERVER -> ioDsh
    }

    fun cancelAll() {
        openAiCompat.cancelActive()
        dshServer.cancelActive()
    }
}
