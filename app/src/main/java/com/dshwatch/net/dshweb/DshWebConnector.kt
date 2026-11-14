package com.dshwatch.net.dshweb

import kotlinx.serialization.json.JsonObject

/**
 * 「连不上」这件事的分步拆解。
 *
 * 原来的实现在失败时只抛一句 `pair failed`，用户能做的只有反复重试。
 * 但这条链路上有五个互相独立、失败原因完全不同的环节：地址解析、换 cookie、
 * 服务可达、会话列表、建会话。把它们分开报告，用户和我们都能立刻知道
 * 「是地址错了，还是电脑上根本没开 dsh web」。
 */
data class DshWebStep(
    val name: String,
    val ok: Boolean,
    val detail: String,
    val millis: Long = 0L,
)

class DshWebConnector(private val client: DshWebClient = DshWebClient()) {

    /** 用启动地址换 cookie。失败抛 [DshWebError] */
    suspend fun connect(launch: DshWebLaunch): DshWebHost = client.login(launch.host, launch.token)

    /**
     * 只读探测，**不建会话**：建会话会在电脑上留下一条空记录，
     * 用户点三次「测试连接」就多出三条垃圾，这种事不能做。
     */
    suspend fun diagnose(host: DshWebHost): List<DshWebStep> {
        val steps = ArrayList<DshWebStep>(4)

        steps += step(STEP_ADDRESS) {
            if (host.baseUrl.isBlank()) error("地址为空")
            if (host.authority.isBlank()) error("地址里没有 host")
            buildString {
                append(host.baseUrl)
                if (host.paired) {
                    append(" · cookie ")
                    append(if (host.cookie!!.isExpired()) "已过期" else "有效")
                } else {
                    append(" · 未登录")
                }
            }
        }

        if (!host.paired || host.cookie!!.isExpired()) {
            steps += DshWebStep(STEP_COOKIE, false, "没有有效 cookie，需要重新粘贴启动地址")
            return steps
        }

        steps += step(STEP_HEALTH) {
            if (!client.isReachable(host)) error("GET / 没有返回 200（cookie 可能失效）")
            "服务在运行，cookie 可用"
        }

        steps += step(STEP_SESSIONS) {
            val sessions = client.listSessions(host)
            val active = sessions.count { !it.archived }
            "${sessions.size} 个会话（${active} 个未归档）"
        }

        return steps
    }

    /** 建一条新会话；只在用户明确点「开始新对话」时调用 */
    suspend fun newSession(host: DshWebHost): String = client.createSession(host)

    suspend fun prompt(host: DshWebHost, sessionId: String, text: String): String =
        client.prompt(host, sessionId, text)

    suspend fun cancel(host: DshWebHost, sessionId: String) = client.cancel(host, sessionId)

    suspend fun call(host: DshWebHost, endpoint: String, args: JsonObject = JsonObject(emptyMap())) =
        client.call(host, endpoint, args)

    private inline fun step(name: String, block: () -> String): DshWebStep {
        val started = System.currentTimeMillis()
        return try {
            DshWebStep(name, true, block(), System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            val e = DshWebError.from(t)
            DshWebStep(name, false, e.describeForUser(), System.currentTimeMillis() - started)
        }
    }

    companion object {
        const val STEP_ADDRESS = "地址"
        const val STEP_COOKIE = "登录"
        const val STEP_HEALTH = "服务可达"
        const val STEP_SESSIONS = "会话列表"
    }
}
