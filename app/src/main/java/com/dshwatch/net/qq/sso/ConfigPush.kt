package com.dshwatch.net.qq.sso

/**
 * `ConfigPushSvc.PushReq`：服务端在登录刚完成时推下来的配置。
 *
 * 里面装的是服务器列表、文件服务器地址之类，**客户端必须回一条 `PushResp`**。
 * 不回的话服务端就一直停在那里等，后面的 `StatSvc.register` 不会有任何回应 ——
 * 表现为「换票成功、推送也收得到，但注册超时、一个包都不再来了」。
 *
 * 两种结构都很小：
 *
 * ```
 * PushReq  { 1: type(Int), 2: jcebuf(bytes), 3: seq(Long) }
 * PushResp { 1: type(Int), 2: seq(Long), 3: jcebuf(bytes，仅 type=3 时回) }
 * ```
 */
internal object ConfigPush {

    const val COMMAND = "ConfigPushSvc.PushReq"
    const val RESPONSE = "ConfigPushSvc.PushResp"
    private const val SERVANT = "QQService.ConfigPushSvc.MainServant"

    class Req(val type: Int, val seq: Long)

    fun parse(body: ByteArray): Req? {
        val struct = TarsPacket.unwrapStruct(body) ?: return null
        // PushReq 的第一个字段是 type（tag 1）；不对就说明这不是它
        if (TarsPacket.firstTag(struct) != 1) return null
        val type = TarsPacket.intOf(struct, 1) ?: return null
        return Req(type, TarsPacket.longOf(struct, 3) ?: 0L)
    }

    /** 回执正文：`RequestPacket{PushResp{type, seq}}` */
    fun respBody(type: Int, seq: Long): ByteArray {
        val struct = JceWriter(32).apply {
            int(1, type)
            long(2, seq)
            // jcebuf 只在 type=3（客户端日志上报）时要原样带回，这里不回
        }.toByteArray()
        return TarsPacket.request(SERVANT, "PushResp", TarsPacket.sBufferV3("PushResp", struct))
    }
}
