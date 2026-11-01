package com.dshwatch

import com.dshwatch.ui.i18n.AppLanguage
import com.dshwatch.ui.i18n.En
import com.dshwatch.ui.i18n.Zh
import com.dshwatch.ui.i18n.stringsFor
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/**
 * 界面语言的解析规则。
 *
 * 这一组是「部分设备全英文」的回归保护：根因是旧实现只看
 * `Locale.getDefault()`（JVM 进程级），而 App 实际生效的是
 * `Configuration` 里的 locale。两者在 per-app language 和部分手表 ROM 上
 * 会不一致 —— 一旦不一致，界面就全英文。
 */
class LanguageTest {

    @Test
    fun `system mode follows config locale when it is chinese`() {
        val s = stringsFor(AppLanguage.SYSTEM, listOf(Locale.SIMPLIFIED_CHINESE))
        assertThat(s).isSameInstanceAs(Zh)
    }

    @Test
    fun `system mode falls back to english for a non chinese locale`() {
        val s = stringsFor(AppLanguage.SYSTEM, listOf(Locale.US))
        assertThat(s).isSameInstanceAs(En)
    }

    @Test
    fun `system mode accepts traditional and region variants`() {
        // zh-Hant / zh-CN / zh-TW 都只比较 language 段，任一命中就是中文
        listOf(
            Locale.TRADITIONAL_CHINESE,
            Locale.SIMPLIFIED_CHINESE,
            Locale("zh", "TW"),
            Locale("zh", "HK"),
        ).forEach { locale ->
            assertThat(stringsFor(AppLanguage.SYSTEM, listOf(locale))).isSameInstanceAs(Zh)
        }
    }

    @Test
    fun `system mode scans the whole locale list not just the first`() {
        // 用户的 locale 列表可能是 [fr, zh]（法语系统 + 中文偏好），
        // 只看第一个就会错判成英文
        val s = stringsFor(AppLanguage.SYSTEM, listOf(Locale.FRANCE, Locale.SIMPLIFIED_CHINESE))
        assertThat(s).isSameInstanceAs(Zh)
    }

    @Test
    fun `explicit choice overrides the system locale`() {
        // 中文系统里选英文，必须真的变英文 —— 这是「临时给外国同事看」的用法
        assertThat(stringsFor(AppLanguage.EN, listOf(Locale.SIMPLIFIED_CHINESE)))
            .isSameInstanceAs(En)
        // 反过来更重要：英文系统里选中文，用来兜住判定失效的那些设备
        assertThat(stringsFor(AppLanguage.ZH, listOf(Locale.US))).isSameInstanceAs(Zh)
    }

    @Test
    fun `unknown language id behaves like system`() {
        assertThat(stringsFor("klingon", listOf(Locale.SIMPLIFIED_CHINESE))).isSameInstanceAs(Zh)
        assertThat(stringsFor("", listOf(Locale.US))).isSameInstanceAs(En)
    }

    @Test
    fun `both languages cover the same keys`() {
        // 接口实现让「少写一个 key」编译不过，但两个实现可以各自写死不一样的文案；
        // 这里只钉住几处用户最先看到的，防止翻译时漏改
        assertThat(Zh.navSettings).isEqualTo("设置")
        assertThat(En.navSettings).isEqualTo("Settings")
        assertThat(Zh.language).isNotEqualTo(En.language)
    }
}
