package com.dshwatch.ui.screens.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 「没有 WebView 也能登录」的入口规则。
 *
 * 用户看到的症状：手表上装了第三方浏览器，点「去登录 DeepSeek」却弹
 * 「这台设备没有 WebView，改用 API Key 方式」，根本进不到授权页。
 *
 * 根因不在登录页 —— 登录页本来就是「先浏览器、后 WebView」两条路，
 * 是账号页在门口先问了一句 WebView，问不到就把人挡回去了。
 * 浏览器那条路压根不需要 WebView。
 */
class DeepSeekLoginRouteTest {

    private fun code(path: String): String {
        val f = File("src/main/java/com/dshwatch/$path")
        assertThat(f.isFile).isTrue()
        return f.readText()
            .replace(Regex("(?<=\\s|^)\\Q/*\\E[\\s\\S]*?\\Q*/\\E"), "")
            .replace(Regex("(?<!:)//[^\\n]*"), "")
    }

    @Test
    fun `账号页不再用 WebView 挡住登录入口`() {
        val body = code("ui/screens/account/AccountScreen.kt")
        assertThat(body).contains("nav.push(Screen.DeepSeekLogin)")
        // 门口那句提示必须消失：有浏览器的设备被它挡了两版
        assertThat(body).doesNotContain("这台设备没有 WebView")
        assertThat(body).doesNotContain("WebViewSupport.isAvailable()")
    }

    @Test
    fun `登录页仍是浏览器优先`() {
        val body = code("ui/screens/account/DeepSeekLoginScreen.kt")
        // 第一条路：系统浏览器 + 回环回调
        assertThat(body).contains("if (!openInBrowser(context, themed.authorizeUrl))")
        // 只有打不开浏览器才退回内置页面
        assertThat(body).contains("mode = Mode.WebView")
    }

    @Test
    fun `两条路都断了才报错，不再让构造函数崩掉`() {
        val body = code("ui/screens/account/DeepSeekLoginScreen.kt")
        assertThat(body).contains("val hasWebView = remember { WebViewSupport.isAvailable() }")
        assertThat(body).contains("if (!hasWebView)")
        // 没有 WebView 时不显示「改用内置页面」的按钮
        assertThat(body).contains("if (hasWebView) {")
    }
}
