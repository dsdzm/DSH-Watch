package com.dshwatch.ui.screens.community

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 社区四件事的接线防线：注册登录、投稿、浏览下载、导入。
 *
 * JVM 单测跑不了 Compose，只能读源码守结构 —— 守的是那种
 * 「编译通过、但用户点不到入口 / 数据没落盘」的故障：
 *
 *  - 页面写好了但 `ScreenHost` 没有分发（点进去是白屏 / 落回对话页）；
 *  - 投稿按钮做了但没真的调 `submit`（点了只弹一个"已提交"）；
 *  - 导入只显示了详情、没写进 `RoleCardStore`（用户以为装上了，回列表找不到）；
 *  - 酒馆 / 写作 / 手机端三个入口漏了一个（用户在某个界面里永远进不去社区）。
 */
class CommunityWiringTest {

    private val root = File("src/main/java/com/dshwatch")

    private fun read(path: String): String {
        val f = File(root, path)
        assertTrue("找不到 $path", f.isFile)
        return f.readText()
    }

    @Test
    fun `四个社区页面都在 ScreenHost 里分发`() {
        val appRoot = read("ui/AppRoot.kt")
        listOf(
            "CommunityScreen(",
            "CommunityCardScreen(",
            "CommunityAuthScreen(",
            "CommunityMineScreen(",
        ).forEach { entry ->
            assertTrue("ScreenHost 没有分发 $entry", appRoot.contains(entry))
        }
        assertTrue(appRoot.contains("Screen.Community ->"))
        assertTrue(appRoot.contains("is Screen.CommunityCard ->"))
        assertTrue(appRoot.contains("Screen.CommunityAuth ->"))
        assertTrue(appRoot.contains("Screen.CommunityMine ->"))
    }

    @Test
    fun `酒馆 写作 手机端三个入口都在`() {
        // 手表酒馆
        assertTrue(read("ui/screens/tavern/TavernScreen.kt").contains("Screen.Community"))
        // 手表写作
        assertTrue(read("ui/screens/writing/WritingScreen.kt").contains("Screen.Community"))
        // 手机版场景页（酒馆 / 写作共用同一个 Compose 函数）
        assertTrue(read("ui/phone/PhoneScenes.kt").contains("Screen.Community"))
    }

    @Test
    fun `编辑页的分享按钮真的会提交`() {
        val src = read("ui/screens/tavern/CardEditScreen.kt")
        assertTrue(src.contains("分享到社区"))
        // 真的发请求，而不是只改一个本地标记
        assertTrue(src.contains("communityApi.submit("))
        // 提交前必须先落盘：否则投出去的是上一次保存的内容
        assertTrue(src.contains("persist()"))
        // 未登录要能跳到登录页，而不是把按钮做成禁用（用户会以为功能坏了）
        assertTrue(src.contains("Screen.CommunityAuth"))
    }

    @Test
    fun `导入真的写进角色卡仓库并计数下载`() {
        val src = read("ui/screens/community/CommunityCardScreen.kt")
        assertTrue(src.contains("roleCardStore.save("))
        // download=1 只在这一处出现：点开详情不算一次下载
        assertTrue(src.contains("download = true"))
    }

    @Test
    fun `登录页把 token 交给账号态而不是自己存`() {
        val src = read("ui/screens/community/CommunityAuthScreen.kt")
        assertTrue(src.contains("communityApi.register("))
        assertTrue(src.contains("communityApi.login("))
        assertTrue(src.contains("communityAccount.signIn("))
        // 密码只在这一个请求里出现，绝不能顺手存下来
        assertTrue(!src.contains("putSecret") && !src.contains("putApiKey"))
    }

    @Test
    fun `我的投稿能显示拒绝理由`() {
        val src = read("ui/screens/community/CommunityMineScreen.kt")
        assertTrue(src.contains("communityApi.mine("))
        assertTrue(src.contains("card.reason"))
    }

    @Test
    fun `评论区内嵌在详情页，不再单独一页`() {
        // 以前的「评论 N 条」会 push 一页 CommunityComments，看完还得退回来才能导入。
        // 现在评论区就在点赞按钮下面，这里守两件事：页面真的接了接口、路由没有被加回来。
        val appRoot = read("ui/AppRoot.kt")
        assertTrue(!appRoot.contains("CommunityCommentsScreen("))

        val src = read("ui/screens/community/CommunityCardScreen.kt")
        assertTrue(src.contains("communityApi.comments("))
        assertTrue(src.contains("communityApi.postComment("))
        assertTrue(src.contains("communityApi.likeComment("))
        // 未登录要能去登录，而不是让输入框白写一通
        assertTrue(src.contains("Screen.CommunityAuth"))
        // 下滑续页靠 hasMore + 滚动位置，不能退回手动按钮才加载
        assertTrue(src.contains("commentsHasMore"))
        assertTrue(src.contains("scroll.maxValue"))
    }

    @Test
    fun `评分 举报 撤回三个动作都接在详情页`() {
        val src = read("ui/screens/community/CommunityCardScreen.kt")
        assertTrue(src.contains("communityApi.rate("))
        assertTrue(src.contains("communityApi.report("))
        assertTrue(src.contains("communityApi.deleteOwn("))
        // 撤回是破坏性操作，必须先确认再发请求
        assertTrue(src.contains("confirmDelete"))
        // 报告入口只在非作者身上显示：作者自己举报自己没意义
        assertTrue(src.contains("detail?.mine != true"))
        // 评分要能看出"我打了几分"，否则连点两次不知道生效没有
        assertTrue(src.contains("myScore"))
    }

    @Test
    fun `列表与详情都先读缓存再决定要不要发请求`() {
        // 这条守的是"每次进去都转圈"：只要有人把 LaunchedEffect 改回无条件 load，
        // 这两个断言就会挂
        val list = read("ui/screens/community/CommunityScreen.kt")
        assertTrue(list.contains("communityCache.page("))
        assertTrue(list.contains("CommunityCache.LIST_TTL"))
        assertTrue(list.contains("load(reset = true, silent ="))

        val detail = read("ui/screens/community/CommunityCardScreen.kt")
        assertTrue(detail.contains("communityCache.detail("))
        assertTrue(detail.contains("CommunityCache.DETAIL_TTL"))
    }
}
