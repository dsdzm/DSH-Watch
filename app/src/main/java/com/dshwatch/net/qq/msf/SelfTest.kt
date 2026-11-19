package com.dshwatch.net.qq.msf

import com.dshwatch.net.qq.sso.DeviceProfile
import com.dshwatch.net.qq.sso.QqSession
import com.dshwatch.net.qq.sso.WatchDevice

/**
 * 直连链路自检。
 *
 * 长连接有五个环节，任何一环断掉，从界面上看到的都是「收不到消息」，
 * 分不清是没连上、没认证、没上线、心跳挂了还是取消息被拒。所以按顺序跑一遍，
 * 每一步单独报时间与结果 —— 出问题时用户把这段文字贴回来就能定位。
 *
 * @param peerUin 配了「对方 QQ 号」时顺手发一条测试消息，把发送链路也验掉
 */
internal fun directSelfTest(session: QqSession, device: WatchDevice, peerUin: Long? = null): String {
    val sb = StringBuilder()
    val client = MsfClient(session, device, DeviceProfile(device))
    try {
        var t0 = System.currentTimeMillis()
        client.open()
        sb.append("① 建连 + 换票（wtlogin.exchange_emp）… 通过，")
            .append(System.currentTimeMillis() - t0).append("ms\n")

        t0 = System.currentTimeMillis()
        client.register()
        sb.append("② 上线注册（StatSvc.register）… 通过，")
            .append(System.currentTimeMillis() - t0).append("ms\n")

        t0 = System.currentTimeMillis()
        client.heartbeat()
        sb.append("③ 心跳（Heartbeat.Alive）… 通过，")
            .append(System.currentTimeMillis() - t0).append("ms\n")

        t0 = System.currentTimeMillis()
        val msgs = client.pollPrivate()
        sb.append("④ 取消息（MessageSvc.PbGetMsg）… 通过，")
            .append(System.currentTimeMillis() - t0).append("ms，")
            .append(msgs.size).append(" 条\n")
        msgs.takeLast(5).forEach { m ->
            sb.append("    ").append(m.peerUin).append("：").append(m.text.take(40)).append('\n')
        }

        if (peerUin != null && peerUin > 0 && msgs.isEmpty()) {
            t0 = System.currentTimeMillis()
            val sentAt = client.sendText(peerUin, "DSH 连接测试")
            sb.append("⑤ 发送（MessageSvc.PbSendMsg）… 通过，")
                .append(System.currentTimeMillis() - t0).append("ms，服务端时间 ")
                .append(sentAt).append('\n')
            sb.append("    手机上应该马上收到「DSH 连接测试」\n")
        } else if (msgs.isEmpty()) {
            sb.append("    （没有新私聊。让别人给你发一条再点一次自检；\n")
                .append("      填了「对方 QQ 号」的话，这里还会自动发一条测试消息）\n")
        }
        return sb.toString().trimEnd()
    } catch (t: Throwable) {
        sb.append("✗ ").append(t.javaClass.simpleName).append("：")
            .append(t.message ?: "没有错误信息")
        return sb.toString()
    } finally {
        runCatching { client.close() }
    }
}
