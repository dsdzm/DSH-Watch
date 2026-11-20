package com.dshwatch.net.qq.sso

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `RequestPacket` 外壳与 `ConfigPushSvc` 的结构。
 *
 * 这两块合在一起测，是因为推送回执必须走同一套外壳 —— 外壳错一处，
 * 回执发出去服务端也不认，表现和「没回执」一模一样。
 */
class TarsPacketTest {

    @Test
    fun `sBuffer 是版本三的表，值是结构标记包起来的内层`() {
        val struct = byteArrayOf(0x08, 0x01) // 随便两个字节
        val sb = TarsPacket.sBufferV3("PushResp", struct)
        assertThat(sb[0]).isEqualTo(0x08) // map、tag 0
        assertThat(sb[4]).isEqualTo(1) // 元素个数
        assertThat(sb[5]).isEqualTo(0x06) // 键：tag 0 的短字符串
        // 值：0x0D（SIMPLE_LIST）0x00（元素 BYTE）长度 0A … 0B
        val keyEnd = 7 + "PushResp".length
        assertThat(sb[keyEnd]).isEqualTo(0x0D)
        assertThat(sb[keyEnd + 1]).isEqualTo(0x00)
        assertThat(sb[keyEnd + 6]).isEqualTo(0x0A)
        assertThat(sb[sb.size - 1]).isEqualTo(0x0B)
    }

    @Test
    fun `回包能被剥回内层结构`() {
        val struct = JceWriter(32).apply {
            long(0, 10001L)
            byte(2, 0)
        }.toByteArray()
        val body = TarsPacket.request(
            "PushService",
            "SvcReqRegister",
            TarsPacket.sBufferV3("SvcRespRegister", struct),
        )
        val back = TarsPacket.unwrapStruct(body)
        assertThat(back).isNotNull()
        assertThat(TarsPacket.firstTag(back!!)).isEqualTo(0)
        assertThat(TarsPacket.intOf(back, 2)).isEqualTo(0)
    }

    /**
     * protobuf 正文的字段 1 头恰好是 0x0A，结尾也可能凑出 0x0B。
     * 只比首尾就会把它当成结构 —— 所以第一个字段的 tag 必须一起看。
     */
    @Test
    fun `推送正文不会被误认成注册回包`() {
        val fake = byteArrayOf(0x0A, 0x05, 0x08, 0x01, 0x10, 0x02, 0x0B)
        val struct = TarsPacket.unwrapStruct(fake)
        assertThat(struct).isNotNull()
        assertThat(TarsPacket.firstTag(struct!!)).isEqualTo(0) // 0x08 → tag 0
        // 0x05 是 protobuf 的长度字节，不是 JCE 的整数头（0/2/3/12），注册那边不会认
        assertThat(TarsPacket.firstType(struct)).isEqualTo(5)
        assertThat(StatSvcRegister.looksLikeReply(fake)).isFalse()
    }

    // ---------------- ConfigPush ----------------

    /** 服务端推下来的 PushReq：type=1（服务器列表）、seq 是一串长整数 */
    @Test
    fun `能读出推送的 type 与 seq`() {
        val body = pushBody(type = 1, seq = -1070001368L)
        val req = ConfigPush.parse(body)
        assertThat(req).isNotNull()
        assertThat(req!!.type).isEqualTo(1)
        assertThat(req.seq).isEqualTo(-1070001368L)
    }

    @Test
    fun `别的命令的正文不会被当成推送`() {
        val struct = JceWriter(32).apply {
            long(0, 10001L)
            byte(2, 0)
        }.toByteArray()
        val body = TarsPacket.request("PushService", "SvcReqRegister", TarsPacket.sBufferV3("SvcRespRegister", struct))
        assertThat(ConfigPush.parse(body)).isNull()
    }

    /** 回执要把 type 与 seq 原样带回去，否则服务端认为没收到 */
    @Test
    fun `回执正文带回 type 与 seq`() {
        val body = ConfigPush.respBody(type = 2, seq = -862904854L)
        val struct = TarsPacket.unwrapStruct(body)
        assertThat(struct).isNotNull()
        assertThat(TarsPacket.firstTag(struct!!)).isEqualTo(1)
        assertThat(TarsPacket.intOf(struct, 1)).isEqualTo(2)
        assertThat(TarsPacket.longOf(struct, 2)).isEqualTo(-862904854L)
    }

    private fun pushBody(type: Int, seq: Long): ByteArray {
        val struct = JceWriter(64).apply {
            int(1, type)
            bytes(2, byteArrayOf(0x01, 0x02, 0x03)) // jcebuf 占位
            long(3, seq)
        }.toByteArray()
        return TarsPacket.request(
            "QQService.ConfigPushSvc.MainServant",
            "PushReq",
            TarsPacket.sBufferV3("PushReq", struct),
        )
    }
}
