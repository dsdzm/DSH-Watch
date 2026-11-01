package com.dshwatch

import com.dshwatch.domain.bili.composeCookieHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备指纹的拼装规则。
 *
 * 背景：真机上点赞返回 `-401 非法访问`，而搜索、看视频、发评论都正常。
 * 这类「只有一个接口坏」的现象最容易被误判成「接口需要签名」，实际是
 * Cookie 里的设备指纹被拼成了两套。投币那条链路已经整个删掉了，
 * 但这条规则对剩下所有带写操作的接口都成立。
 */
class BiliCookieTest {

    @Test
    fun `登录 Cookie 自带的 buvid3 不会被匿名 b_nut 污染`() {
        val raw = "SESSDATA=s; bili_jct=j; DedeUserID=1; buvid3=LOGIN-3"
        val header = composeCookieHeader(raw, buvid = "ANON-3", buvid4 = "ANON-4", bNut = 1700000000L)

        assertTrue("必须保留登录 Cookie", header.contains("SESSDATA=s"))
        assertTrue("必须保留服务端下发的 buvid3", header.contains("buvid3=LOGIN-3"))
        assertFalse("不能注入匿名 buvid3", header.contains("ANON-3"))
        assertFalse("不能注入与 buvid3 不配套的 b_nut", header.contains("b_nut="))
    }

    @Test
    fun `没有 buvid3 时三件套整体注入`() {
        val header = composeCookieHeader(
            raw = "SESSDATA=s",
            buvid = "ANON-3",
            buvid4 = "ANON-4",
            bNut = 1700000000L,
        )
        assertTrue(header.contains("buvid3=ANON-3"))
        assertTrue(header.contains("buvid4=ANON-4"))
        assertTrue("buvid3 与 b_nut 必须成组出现", header.contains("b_nut=1700000000"))
    }

    @Test
    fun `登录 Cookie 已有 buvid3 时一个指纹都不补`() {
        val raw = "SESSDATA=s; buvid3=LOGIN-3"
        val header = composeCookieHeader(raw, buvid = "ANON-3", buvid4 = "ANON-4", bNut = 1L)
        assertTrue(header.contains("buvid3=LOGIN-3"))
        // 单独的 buvid4 也不能补：它跟服务端下发的 buvid3 不是一次签发的
        assertFalse(header.contains("ANON-4"))
        assertFalse(header.contains("b_nut="))
    }

    @Test
    fun `匿名请求全部为空时不产生空尾巴`() {
        val header = composeCookieHeader(raw = "", buvid = "", buvid4 = "", bNut = 0L)
        assertEquals("", header)
    }

}
