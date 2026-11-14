package com.dshwatch.net.backend

import com.dshwatch.domain.model.Provider
import com.dshwatch.net.dshweb.DshLink
import com.dshwatch.net.dshweb.DshSessionChannel
import com.dshwatch.net.dshweb.DshWebError
import com.dshwatch.net.dshweb.MuxEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DSH 服务器模式：连接你自己电脑上运行的 DeepSeek Harness。
 *
 * ## 这里曾经错得很彻底
 *
 * 旧实现假想了一套 REST 接口：`POST /api/v1/sessions`、`/sessions/{id}/messages`
 * 配合 SSE 与 `Authorization: Bearer <设备令牌>`。那套东西在 `dsh web` 上
 * **一个都不存在** —— 它是照着「远程访问插件」猜的。所以用户填对地址也只会 404，
 * 界面上表现为「一直连不上」。
 *
 * 真实协议分三层（对照 dshgo 的 `packages/dsh_client`，0.2.x）：
 *
 *  1. **连接**：`GET /?token=…` 换一枚 `dsh-auth-*` cookie（见 net/dshweb）；
 *  2. **一元调用**：`POST /api/<endpoint>`，信封 `client-request` / `server-response`，
 *     业务失败也是 HTTP 200，必须看 `result.ok`；
 *  3. **对话流**：`/api/remote.mux` 上开 `session/follow` 逻辑流，增量靠它推回来。
 *
 * 所以本类只做「会话 → 提问 → 追流」三件事，其余（怎么连上电脑）在 [DshLink] 里。
 */
class DshServerBackend(
    private val json: Json,
    private val link: DshLink,
) : ChatBackend {

    override fun cancelActive() {
        link.cancelActive()
    }

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = channelFlow {
        val mux = link.mux()
        if (mux == null) {
            send(
                ChatEvent.Failure(
                    DshError.BadConfig("还没有连接电脑上的 DSH。到「供应商 → DSH 服务器」里点连接")
                )
            )
            return@channelFlow
        }

        val text = request.history.lastOrNull { it.role == "user" }?.content.orEmpty()
        if (text.isBlank()) {
            send(ChatEvent.Failure(DshError.BadConfig("消息为空")))
            return@channelFlow
        }

        var sessionId = request.remoteSessionId
        if (sessionId.isBlank()) {
            sessionId = try {
                link.createSession().also { send(ChatEvent.RemoteSession(it)) }
            } catch (t: Throwable) {
                send(ChatEvent.Failure(toDshError(t)))
                return@channelFlow
            }
        }

        followTurn(this, mux, sessionId, text)
        send(ChatEvent.Completed)
    }

    /**
     * 订阅这一回合，并在快照到达后把 prompt 投进去。
     *
     * 顺序不能反：先 prompt 再开 follow，中间那几百毫秒的增量就永久丢了
     * （Host 不重放已经发出的流式帧），表现是「回答开头缺几个字」。
     */
    private suspend fun followTurn(
        scope: ProducerScope<ChatEvent>,
        mux: DshSessionChannel,
        sessionId: String,
        text: String,
    ) {
        val prompted = AtomicBoolean(false)
        val sawDelta = AtomicBoolean(false)
        val finished = CompletableDeferred<Unit>()

        val job = scope.launch {
            try {
                mux.follow(sessionId).collect { event ->
                    when (event) {
                        is MuxEvent.Snapshot -> {
                            if (prompted.compareAndSet(false, true)) {
                                scope.launch {
                                    try {
                                        link.prompt(sessionId, text)
                                    } catch (t: Throwable) {
                                        scope.send(ChatEvent.Failure(toDshError(t)))
                                        finished.complete(Unit)
                                    }
                                }
                            }
                        }

                        is MuxEvent.Delta -> {
                            sawDelta.set(true)
                            scope.send(ChatEvent.Text(event.text))
                        }

                        is MuxEvent.Message -> {
                            // 定稿消息与流式增量是同一段内容：已经逐字显示过就别再补一遍
                            if (event.role == "assistant" && !sawDelta.get() && event.text.isNotBlank()) {
                                scope.send(ChatEvent.Text(event.text))
                            }
                        }

                        MuxEvent.TurnEnded, MuxEvent.Ended -> finished.complete(Unit)

                        is MuxEvent.Failed -> {
                            scope.send(ChatEvent.Failure(DshError.Server(event.reason)))
                            finished.complete(Unit)
                        }
                    }
                }
            } catch (t: Throwable) {
                scope.send(ChatEvent.Failure(toDshError(t)))
                finished.complete(Unit)
            } finally {
                runCatching { finished.complete(Unit) }
            }
        }

        // 一轮对话可能跑很久（工具调用、长文），但绝不该无限等：
        // 手表上没有「停止」的话，卡死等于整个界面失效
        withTimeoutOrNull(TURN_TIMEOUT_MS) { finished.await() }
        job.cancel()
    }

    // ---------------- 自检 / 诊断 ----------------

    override suspend fun listModels(provider: Provider, apiKey: String, serverToken: String): List<String> {
        // DSH 的模型由服务端与预设决定，手表这边不让选 —— 返回空而不是假装拉到
        return emptyList()
    }

    override suspend fun testConnection(provider: Provider, apiKey: String, serverToken: String): String {
        if (!link.connected) throw DshError.BadConfig("还没有连接电脑上的 DSH")
        val sessions = try {
            link.listSessions()
        } catch (t: Throwable) {
            throw toDshError(t)
        }
        return "已连接 ${provider.name.ifBlank { "DSH" }}（${link.describePath()}，${sessions.size} 个会话）"
    }

    /**
     * 只读诊断，不建会话、不往电脑上写东西。
     *
     * 三步对应这条链上三件会各自失败的事：隧道在不在、cookie 还有效吗、
     * 会话列表能不能读。合成一句「连接失败」等于什么都没说。
     */
    override suspend fun diagnose(provider: Provider, apiKey: String, serverToken: String): List<DiagStep> {
        val steps = ArrayList<DiagStep>(3)

        steps += runStep("连接通道") {
            if (!link.connected) throw DshError.BadConfig("还没有连接")
            link.describePath()
        }

        if (!link.connected) {
            steps += DiagStep("登录", false, "还没有连接电脑")
            return steps
        }

        steps += runStep("登录") {
            when (link.mode) {
                com.dshwatch.net.dshweb.DshLink.Mode.REMOTE -> "设备令牌有效（Noise 隧道已加密）"
                else -> "cookie 有效（${link.host()?.cookie?.name ?: "dsh-auth"}）"
            }
        }

        steps += runStep("会话列表") {
            val sessions = link.listSessions()
            "${sessions.size} 个会话"
        }

        return steps
    }

    private suspend fun runStep(name: String, block: suspend () -> String): DiagStep {
        val t0 = System.currentTimeMillis()
        return try {
            DiagStep(name, true, block(), System.currentTimeMillis() - t0)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            DiagStep(name, false, toDshError(t).describeForUser(), System.currentTimeMillis() - t0)
        }
    }

    private fun toDshError(t: Throwable): DshError = when (t) {
        is DshError -> t
        is DshWebError.Unauthorized -> DshError.Auth(t.describeForUser())
        is DshWebError.Http -> DshError.Http(t.code, t.body)
        is DshWebError.Protocol -> DshError.Parse(t.describeForUser())
        is DshWebError.Remote -> DshError.Server(t.describeForUser())
        is DshWebError.Network -> DshError.Network(t)
        is com.dshwatch.net.ssh.SshError -> DshError.BadConfig(t.describeForUser())
        else -> DshError.Server(t.message ?: t.javaClass.simpleName)
    }

    companion object {
        /** 一轮对话的上限。超过就当作卡住，把控制权还给用户 */
        private const val TURN_TIMEOUT_MS = 300_000L
    }
}
