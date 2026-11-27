package com.dshwatch.ui.theme

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * 自定义背景的两条选图通路。
 *
 * 这组测试来自一个真实的闪退：华为手表把系统文件管理器裁掉了，没有任何 Activity
 * 响应 `ACTION_OPEN_DOCUMENT`，`ActivityResultLauncher.launch()` 抛
 * `ActivityNotFoundException`，Compose 的 launcher 不接 —— 用户点「选择图片」
 * 直接崩。附件与插件导入早就为这个坑做过兜底，只有背景这里漏了。
 */
class BackgroundPickerFallbackTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
    }

    @Test
    fun `系统选择器启动必须捕获 ActivityNotFoundException`() {
        val body = code("ui/screens/settings/BackgroundDialog.kt")
        // launch() 会抛异常：不包一层就是闪退，不是「没反应」
        assertThat(body).contains("runCatching { picker.launch(arrayOf(\"image/*\")) }")
        // 失败要真的切到内置浏览器 —— 用户的目标是选到图，不是知道哪条路不通
        assertThat(body).contains("if (!ok) onOpenBrowser()")
    }

    @Test
    fun `弹窗给出内置浏览器入口，面板由页面层托管`() {
        val dialog = code("ui/screens/settings/BackgroundDialog.kt")
        assertThat(dialog).contains("从文件里挑")
        assertThat(dialog).contains("onOpenBrowser")

        val screen = code("ui/screens/settings/SettingsGroupScreen.kt")
        /*
         * 面板必须在页面层。它是整屏的 WatchScaffold，而弹窗是独立 Window 的
         * Dialog：塞进内容区会被 maxContentHeight 裁成一条，不裁也会被
         * Dialog 的窗口整个盖住。
         */
        assertThat(screen).contains("FileBrowserPanel(")
        assertThat(screen).contains("workspace = container.workspaceStore.rootDir()")
        // 只列目录与图片：让用户在 .txt 里翻、选中才报「不是图片」是最差的体验
        assertThat(screen).contains("fileFilter = { CustomBackground.looksLikeImage(it) }")
        // 选完把文件递回弹窗，遮罩滑块还在手边
        assertThat(screen).contains("picked = backgroundPick")
    }

    @Test
    fun `内置浏览器给的 file 路径直接读盘，不经 ContentResolver`() {
        val body = code("ui/theme/CustomBackground.kt")
        /*
         * 内置浏览器拿到的是真实路径，转成 `file://` 之后没必要再走
         * ContentResolver —— 部分 ROM 对 file scheme 的 openAssetFileDescriptor
         * 行为不一致，而文件本身已经 canRead() 验过。
         */
        assertThat(body).contains("if (uri.scheme == \"file\")")
        assertThat(body).contains("decodeBoundedFile(")
        // 两条解码路径要共用同一套「限长边 + 软件位图」参数
        assertThat(body).contains("private fun configure(decoder: ImageDecoder, info: ImageDecoder.ImageInfo)")
    }

    @Test
    fun `图片过滤看扩展名，大小写不敏感`() {
        val dir = Files.createTempDirectory("bgtest").toFile()
        try {
            val jpg = File(dir, "a.JPG").apply { writeText("x") }
            val png = File(dir, "b.png").apply { writeText("x") }
            val heic = File(dir, "c.HEIC").apply { writeText("x") }
            val txt = File(dir, "d.txt").apply { writeText("x") }

            assertThat(CustomBackground.looksLikeImage(jpg)).isTrue()
            assertThat(CustomBackground.looksLikeImage(png)).isTrue()
            assertThat(CustomBackground.looksLikeImage(heic)).isTrue()
            assertThat(CustomBackground.looksLikeImage(txt)).isFalse()
            // 目录不是图片（不然点进去就变成「设为背景」）
            assertThat(CustomBackground.looksLikeImage(dir)).isFalse()
            // 不存在的路径也不能算
            assertThat(CustomBackground.looksLikeImage(File(dir, "nope.jpg"))).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }
}
