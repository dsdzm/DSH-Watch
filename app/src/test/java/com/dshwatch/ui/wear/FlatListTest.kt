package com.dshwatch.ui.wear

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 三条「应该是普通列表」的页面，不许再挂鱼眼。
 *
 * ## 为什么要用结构测试守
 *
 * 鱼眼（`applyContainerTransformation`）不是 bug，是 [WearList] 的默认行为 ——
 * 谁把 `FlatList` 改回 `WearList`，编译照样过、界面照样能滚，只是首尾两条
 * 又变成半透明缩小的样子。用户连着报了三页（教程 / 工作空间 / 模型列表），
 * 而且手机上也有 —— 手机上根本没有圆屏，收窄纯属跟着手表一起继承来的。
 *
 * 这三页的共同点是「一屏要看清好几条」，和「一张卡占一屏」的浏览页不同，
 * 前者被压暗一条就等于少看一条。
 */
class FlatListTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `FlatList 不做容器变换`() {
        val src = code("ui/wear/WearLists.kt")
        val start = src.indexOf("fun FlatList(")
        assertThat(start).isGreaterThan(0)
        val body = src.substring(start).substringBefore("fun Modifier.wearVerticalScroll")

        // 变换由 WearList 那套（transformedHeight + applyContainerTransformation）提供，
        // FlatList 一件都不能沾；沾一件就说明它已经被复制成了第二份 WearList
        assertThat(body).doesNotContain("applyContainerTransformation")
        assertThat(body).doesNotContain("transformedHeight")
        assertThat(body).doesNotContain("rememberTransformationSpec")
        // 但表冠要留着：那是操作方式，不是视觉效果
        assertThat(body).contains("rotaryScrollable")
    }

    @Test
    fun `教程、工作空间、模型列表都用 FlatList`() {
        listOf(
            "ui/screens/help/HelpScreen.kt" to "rememberKeptLazyState(\"help\")",
            "ui/screens/provider/ProvidersScreen.kt" to "rememberKeptLazyState(\"providers\")",
            "ui/screens/workspace/WorkspaceScreen.kt" to "rememberKeptLazyState(\"workspace\")",
        ).forEach { (path, key) ->
            val src = code(path)
            assertThat(src).contains("FlatList(")
            assertThat(src).contains(key)
            // 挂回 WearList 就等于把鱼眼又装回去
            assertThat(src).doesNotContain("WearList(")
        }
    }
}
