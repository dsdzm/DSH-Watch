package com.dshwatch.domain

import com.dshwatch.domain.update.UpdateStore
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保存安装包到本地」的约定。
 *
 * 这一层能离线测的部分不多 —— 真正写盘要 Context。但**命令与命名**必须钉住：
 * 用户在手表屏幕上看着那行命令，一个字都敲不动，只能整条复制。
 * 命令里少一个 `pm`、路径少了引号，用户拿到的是「终端里报一行看不懂的错」，
 * 而这在单测里一条断言就能守住。
 */
class UpdateStoreTest {

    private fun saved(path: String, place: UpdateStore.Place) =
        UpdateStore.Saved(File(path), place)

    @Test
    fun `文件名带上 versionCode`() {
        assertThat(UpdateStore.fileName(84)).isEqualTo("dshwatch-84.apk")
    }

    @Test
    fun `能从文件名认出版本号`() {
        assertThat(UpdateStore.codeOf(File("/sdcard/Download/DSH-Watch/dshwatch-84.apk"))).isEqualTo(84)
        // 别的包一律不认：认错了会让更新页把一个不相干的 apk 当成「已下载」
        assertThat(UpdateStore.codeOf(File("other.apk"))).isNull()
        assertThat(UpdateStore.codeOf(File("dshwatch-latest.apk"))).isNull()
        assertThat(UpdateStore.codeOf(File("dshwatch-84.apk.part"))).isNull()
    }

    @Test
    fun `公共目录给的是 pm install`() {
        val cmd = UpdateStore.adbInstallCommand(
            saved("/sdcard/Download/DSH-Watch/dshwatch-84.apk", UpdateStore.Place.PUBLIC_DOWNLOAD),
        )
        assertThat(cmd).isNotNull()
        // adb install 吃的是电脑上的路径，手表里这个文件只能靠 adb shell pm install
        assertThat(cmd).contains("adb shell pm install -r")
        // 路径必须带引号：用户复制过去之后，shell 才不会在奇怪字符上截断
        assertThat(cmd).contains("\"/sdcard/Download/DSH-Watch/dshwatch-84.apk\"")
    }

    @Test
    fun `应用外部目录同样能装`() {
        val cmd = UpdateStore.adbInstallCommand(
            saved(
                "/sdcard/Android/data/com.dshwatch/files/Download/DSH-Watch/dshwatch-84.apk",
                UpdateStore.Place.APP_EXTERNAL,
            ),
        )
        assertThat(cmd).contains("pm install")
    }

    @Test
    fun `私有目录不给安装命令，只给 pull`() {
        val priv = saved("/data/data/com.dshwatch/files/export/DSH-Watch/dshwatch-84.apk", UpdateStore.Place.APP_INTERNAL)
        // shell 读不到 /data/data：给一条必然失败的 pm install 是在骗用户
        assertThat(UpdateStore.adbInstallCommand(priv)).isNull()
        assertThat(UpdateStore.installableFromShell(UpdateStore.Place.APP_INTERNAL)).isFalse()
        assertThat(UpdateStore.adbPullCommand(priv)).contains("adb pull")
    }

    /**
     * 保存位置绝不落在缓存里。
     *
     * 这条是整件事的底线：`cacheDir` 会被系统在低存储时直接清空，
     * 而「保存到本地」的全部意义就是让这个包**不**被清掉。
     * 一旦有人图省事把 `save()` 指向 `context.cacheDir`，功能就静默失效了 ——
     * 用户点「保存」显示成功，第二天包没了。
     */
    @Test
    fun `保存位置不碰缓存目录`() {
        val src = File("src/main/java/com/dshwatch/domain/update/UpdateStore.kt")
        assertTrue("找不到 UpdateStore.kt：${src.absolutePath}", src.isFile)
        val code = src.readText()
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")
        assertTrue("UpdateStore 不允许出现 cacheDir —— 缓存会被系统清掉", !code.contains("cacheDir"))
    }
}
