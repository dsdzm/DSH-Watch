package com.dshwatch.net.backend

import com.dshwatch.domain.model.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 把后端调用强制切到 IO 线程的装饰器。
 *
 * ## 为什么需要它
 *
 * 真机上踩到过 `NetworkOnMainThreadException`，文案是：
 *
 * ```
 * 拉取失败: NetworkOnMainThreadException
 * ```
 *
 * 原因是 `listModels()` 里是裸的 `call.execute()`（阻塞式网络），而「供应商」页
 * 是用 `rememberCoroutineScope()` 启动它的 —— 那是 Main dispatcher。
 * 于是点「拉取模型」必然抛异常，而用户完全看不出发生了什么。
 *
 * 讽刺的是 `streamChat` 当时没问题，因为它带了 `flowOn(Dispatchers.IO)`。
 * 恰恰是这种「有的方法记得切、有的忘了」最容易漏 —— 而 Android 只在**真机**上
 * 才会拦主线程网络（JVM 单测里没有这个检查），所以单元测试根本抓不到。
 *
 * ## 这一层做什么
 *
 * 只要通过 [BackendRegistry.of] 拿后端，就不可能跑在主线程上，
 * 不管具体实现有没有记得加 `withContext`。
 * 各实现内部已有的 `withContext(Dispatchers.IO)` 依然保留（那是根治手段），
 * 这里是兜底防线 —— 新增后端或新增接口方法时不必再想一遍这件事。
 */
class IoBackend(private val delegate: ChatBackend) : ChatBackend {

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> =
        delegate.streamChat(request).flowOn(Dispatchers.IO)

    override suspend fun listModels(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): List<String> = withContext(Dispatchers.IO) {
        delegate.listModels(provider, apiKey, serverToken)
    }

    override suspend fun testConnection(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): String = withContext(Dispatchers.IO) {
        delegate.testConnection(provider, apiKey, serverToken)
    }

    override suspend fun diagnose(
        provider: Provider,
        apiKey: String,
        serverToken: String,
    ): List<DiagStep> = withContext(Dispatchers.IO) {
        delegate.diagnose(provider, apiKey, serverToken)
    }

    override fun cancelActive() = delegate.cancelActive()
}
