package com.dshwatch.net.dshweb

import kotlinx.coroutines.flow.Flow

/**
 * 「订阅一个会话的一回合」这件事的抽象。
 *
 * SSH 那条路是 `dsh web` 的 `/api/remote.mux`，Remote 那条路是 Noise 隧道里的
 * `session/follow`。两条路的帧格式完全相同（都是 Gateway 的 follow 快照 +
 * `assistant-stream` 增量），所以上层只需要这一个接口，不必知道自己连的是哪种。
 */
interface DshSessionChannel {
    fun follow(sessionId: String, maxMessages: Int = 60): Flow<MuxEvent>
}
