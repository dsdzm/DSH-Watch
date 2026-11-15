package com.dshwatch.ui.m3e

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 守 M3E 独立页面的**结构**约束。
 *
 * ## 为什么是读源码而不是 Compose 测试
 *
 * 这些约束是「有没有用错组件」，不是「像素对不对」：骨架是不是官方那套、
 * 有没有偷偷回去引用经典页面。Compose UI 测试在无 KVM 的沙箱里跑不起来，
 * 而这一层断言只需要文件内容 —— 它拦的是「有人图省事又写回 WatchScaffold」
 * 这类回归，那正是这一版重构要根治的东西。
 */
class M3ePageStructureTest {

    private fun src(path: String): String {
        val f = File("src/main/java/com/dshwatch/ui/m3e/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
    }

    /** 去掉注释：注释里会提到这些类名（说明"为什么不用"），不能算违规 */
    private fun code(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `四个 M3E 页面都用官方列表容器`() {
        val pages = listOf(
            "screens/M3eChatScreen.kt",
            "screens/M3eSessionsScreen.kt",
            "screens/M3eSettingsScreen.kt",
            "screens/M3eUsageScreen.kt",
        )
        for (p in pages) {
            val body = code(src(p))
            assertThat(body).contains("M3eList(")
            assertThat(body).contains("M3eScaffold(")
        }
    }

    @Test
    fun `M3E 页面不引用经典骨架`() {
        val pages = listOf(
            "screens/M3eChatScreen.kt",
            "screens/M3eSessionsScreen.kt",
            "screens/M3eSettingsScreen.kt",
            "screens/M3eUsageScreen.kt",
        )
        for (p in pages) {
            val body = code(src(p))
            // 经典骨架：一旦出现，说明这一页只是"换了张皮"，骨架还是手机的
            assertThat(body).doesNotContain("WatchScaffold")
            assertThat(body).doesNotContain("WearList")
            assertThat(body).doesNotContain("CircleScaffold")
            assertThat(body).doesNotContain("glassSurface")
        }
    }

    /**
     * 对话页必须直连官方卡片，不能回到 `M3eBubble`。
     *
     * `M3eBubble` 是上一版「在经典气泡里分派」的产物：它的外壳仍然由
     * `BubbleScaffold` 决定（用户气泡偏窄靠右、标签在气泡外），
     * 只是换了个配色。M3E 的对话页要的是全宽卡片 + 说话人靠容器色区分。
     */
    @Test
    fun `对话页不再走经典气泡`() {
        val body = code(src("screens/M3eChatScreen.kt"))
        assertThat(body).doesNotContain("M3eBubble")
        assertThat(body).doesNotContain("BubbleScaffold")
        assertThat(body).contains("primaryContainer")
    }

    /**
     * 分派层必须接上这四个页面。
     *
     * 只写了页面、忘了接到 `M3ePage`，界面上看不出任何变化 ——
     * 用户看到的是"你说重构了，但我打开还是老样子"。
     */
    @Test
    fun `分派层覆盖四个入口`() {
        val body = code(src("M3ePage.kt"))
        assertThat(body).contains("M3eChatScreen(")
        assertThat(body).contains("M3eSessionsScreen(")
        assertThat(body).contains("M3eSettingsScreen(")
        assertThat(body).contains("M3eUsageScreen(")
        // 酒馆 / 写作还没做 M3E 版，必须明确回落而不是硬画一个编程对话页
        assertThat(body).contains("Scenes.CODING")
    }

    /**
     * 列表封装必须真的用 `TransformingLazyColumn`。
     *
     * 这条看起来是同义反复，但它拦的是"有人把 M3eList 内部改回 LazyColumn
     * 以兼容某个老页面"—— 那样整套 M3E 的贴边变换会一起消失，
     * 而且不会有任何报错。
     */
    @Test
    fun `列表封装走官方变换容器`() {
        val body = code(src("M3eList.kt"))
        assertThat(body).contains("TransformingLazyColumn(")
        assertThat(body).doesNotContain("androidx.compose.foundation.lazy.LazyColumn")
    }

    /**
     * 列表必须裁切。
     *
     * `TransformingLazyColumn` 为了让贴边项能缩小淡出，绘制时**不裁剪** ——
     * 缩放后的项要画到列表边界外才好看。但这条自由一旦遇上「列表上方压着
     * 固定顶栏」的页面，滚上去的项就会直接画在顶栏和时间文字上面。
     *
     * 用户截图里那两处错乱（设置页的开关卡盖住返回键、对话页的消息盖住图标）
     * 都是这个，而且**不报错**。所以这里钉死：两个列表入口都必须有 clipToBounds。
     */
    @Test
    fun `列表容器必须裁切边界`() {
        val list = File("src/main/java/com/dshwatch/ui/m3e/M3eList.kt")
        assertThat(list.isFile).isTrue()
        assertThat(list.readText()).contains("clipToBounds")

        // 经典那个列表是全项目几十个页面的入口，同样必须裁
        val wear = File("src/main/java/com/dshwatch/ui/wear/WearLists.kt")
        assertThat(wear.isFile).isTrue()
        assertThat(wear.readText()).contains("clipToBounds")
    }

    /**
     * 顶栏与内容之间必须给时间文字让位，而且不能再去读用户的圆角内收。
     *
     * 时间文字是 `AppScaffold` 画的浮层，不参与布局 —— 不让位就会被压住。
     * 圆角内收那个参数用户可以设成 0，一设成 0 卡片就贴到屏幕边、被圆周切掉；
     * 圆屏的安全区是物理事实，不该跟着偏好开关走。
     */
    @Test
    fun `骨架给时间文字让位且不读用户内收`() {
        val body = code(File("src/main/java/com/dshwatch/ui/m3e/M3eScaffold.kt").readText())
        assertThat(body).contains("M3eTimeTextInset")
        assertThat(body).contains("clipToBounds")
        // 注释里会写「不再读 cornerInsetDp」，说明理由；代码里不许真去读
        assertThat(body).doesNotContain("cornerInsetDp")
    }
}
