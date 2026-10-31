package com.dshwatch

import com.dshwatch.domain.bili.BiliApi
import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test

/**
 * 临时真机探针：直接打 B站接口，确认 WBI 签名、buvid、字幕链路都是通的。
 * 只在手动执行时跑（需要联网），不进常规测试集。
 */
class BiliLiveProbeTest {

    @Test
    @Ignore("真联网探针：手动执行才能验证 B站 链路，常规测试不跑")
    fun probe() = runBlocking {
        val api = BiliApi { "" }
        println("[probe] health(before)=" + api.health())
        val list = api.search("DeepSeek", 3)
        println("[probe] health(after)=" + api.health())
        println("[probe] search size=${list.size}")
        list.forEach { println("[probe]  ${it.bvid} | ${it.title} | ${it.author} | ${it.duration}") }
        val bv = list.firstOrNull()?.bvid ?: return@runBlocking
        val d = api.video(bv)
        println("[probe] video=${d.title} aid=${d.aid} cid=${d.cid} pages=${d.pages.size}")
        val cm = api.comments(bv, 3)
        println("[probe] comments=${cm.size} ${cm.firstOrNull()?.user}")
        val st = api.subtitle(bv, 1, 4000)
        println("[probe] subtitle available=${st.available} len=${st.text.length} lang=${st.lang} hint=${st.hint}")
    }
}
