package com.dshwatch.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 相机入口在**手机和手表两端都要有**。
 *
 * 这条约束很容易在后续重构里悄悄丢掉一侧：两边是两套 UI 代码，
 * 谁改自己那半边都不会发现对面少了什么，而用户是两边都会用的。
 */
class CameraEntryTest {

    private fun read(path: String): String {
        val f = File(path)
        assertThat(f.isFile).isTrue()
        return f.readText()
    }

    @Test
    fun `拍照实现只此一份`() {
        val shot = read("src/main/java/com/dshwatch/ui/components/CameraShot.kt")
        // 原图，不是缩略图：TakePicturePreview 回来的是位图，发给模型没法看
        assertThat(shot).contains("ActivityResultContracts.TakePicture()")
        assertThat(shot).contains("cacheDir")
        assertThat(shot).contains("\"camera\"")
        // authority 必须跟清单里那个 provider 对齐
        assertThat(shot).contains("\${context.packageName}.files")
    }

    @Test
    fun `手机输入栏的加号旁有相机键`() {
        val chrome = read("src/main/java/com/dshwatch/ui/phone/PhoneChatChrome.kt")
        assertThat(chrome).contains("PhotoCamera")
        assertThat(chrome).contains("onCamera")
        // 两个键并排：相机在前、加号在后
        val cameraAt = chrome.indexOf("Icons.Outlined.PhotoCamera")
        val plusAt = chrome.indexOf("Icons.Filled.Add,")
        assertThat(cameraAt).isGreaterThan(0)
        assertThat(plusAt).isGreaterThan(cameraAt)
    }

    @Test
    fun `手机对话页把拍照接到附件管线`() {
        val chat = read("src/main/java/com/dshwatch/ui/phone/PhoneChatScreen.kt")
        assertThat(chat).contains("rememberCameraShot(")
        assertThat(chat).contains("onCamera = shoot")
        // 拍完必须走 addUri —— 直接拿 uri 塞进列表会绕过 AttachmentStore 的校验与去重
        assertThat(chat).contains("onPicked = { uri -> addUri(uri) }")
    }

    @Test
    fun `手表输入页也有拍照`() {
        val input = read("src/main/java/com/dshwatch/ui/screens/input/InputScreen.kt")
        assertThat(input).contains("rememberCameraShot(")
        assertThat(input).contains("拍照")
        assertThat(input).contains("onPicked = { uri -> absorb(uri) }")
    }

    @Test
    fun `FileProvider 映射了相机缓存目录`() {
        // 少这一条，getUriForFile 会抛 Failed to find configured root，
        // 表现是「点拍照什么都没发生」
        val paths = read("src/main/res/xml/file_paths.xml")
        assertThat(paths).contains("name=\"camera\"")
        assertThat(paths).contains("path=\"camera/\"")
    }
}
