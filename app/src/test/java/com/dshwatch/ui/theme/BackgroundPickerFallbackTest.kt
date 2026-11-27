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
        val body = code("ui/screens/files/FileBrowsing.kt")
        /*
         * `launch()` 会**同步**抛异常：华为手表这类 ROM 把文件管理器整个裁掉，
         * 没有任何 Activity 响应 ACTION_OPEN_DOCUMENT。Compose 的 launcher
         * 不会替你接住 —— 用户点一下，App 直接崩。
         *
         * 上一版把这个坑在「自定义背景」里单独兜了一次，结果是九个入口
         * 各写一遍、漏了哪一处就崩哪一处。现在只有分派器这一处兜。
         */
        assertThat(body).contains("runCatching { launcher.launch(arrayOf(mime)) }")
        // 兜住之后不能只弹一句「不支持」：用户要的是选到文件
        assertThat(body).contains("if (!launched)")
        assertThat(body).contains("FileBrowsing.open(filter)")
    }

    @Test
    fun `所有导入入口统一走分派器，不再各自持有系统 launcher`() {
        /*
         * 这条是本轮的核心约束：**一个开关管全部**。
         * 只要有一处又写了 `ActivityResultContracts.OpenDocument()`，
         * 那个入口就不受设置控制，而且很容易又忘掉 runCatching。
         */
        val offenders = listOf(
            "ui/screens/input/InputScreen.kt",
            "ui/screens/sessions/SessionsScreen.kt",
            "ui/screens/settings/DataGroup.kt",
            "ui/screens/settings/PluginImportDialog.kt",
            "ui/screens/settings/BackgroundDialog.kt",
            "ui/screens/fish/FishSkinScreen.kt",
            "ui/screens/fish/FishPhoneScreen.kt",
            "ui/phone/PhoneChatScreen.kt",
        )
        offenders.forEach { path ->
            val body = code(path)
            assertThat(body).doesNotContain("ActivityResultContracts.OpenDocument()")
            assertThat(body).doesNotContain("ActivityResultContracts.PickVisualMedia()")
            assertThat(body).doesNotContain("ActivityResultContracts.GetContent()")
        }
    }

    @Test
    fun `面板宿主挂在导航树外，一屏只有一份`() {
        val root = code("ui/AppRoot.kt")
        // 挂在页面里的话，面板会跟着转场淡出，而且每个页面都要复制一份调用代码
        assertThat(root).contains("FileBrowserHost(")
        assertThat(root).contains("container.workspaceStore.rootDir()")

        // 手机版是另一棵树，同样要挂 —— 不然那边开「内置浏览器」点了没反应
        val phone = code("ui/phone/PhoneRoot.kt")
        assertThat(phone).contains("FileBrowserHost(")
    }

    @Test
    fun `背景弹窗只有一个选图入口，走开关分派`() {
        val dialog = code("ui/screens/settings/BackgroundDialog.kt")
        // 曾经有两个按钮（系统相册 / 内置浏览器），同一件事两个按钮只会让人犹豫
        assertThat(dialog).doesNotContain("从文件里挑")
        assertThat(dialog).contains("onOpenBrowser")

        val screen = code("ui/screens/settings/SettingsGroupScreen.kt")
        assertThat(screen).contains("picking.pick(")
        assertThat(screen).contains("filter = { CustomBackground.looksLikeImage(it) }")
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
