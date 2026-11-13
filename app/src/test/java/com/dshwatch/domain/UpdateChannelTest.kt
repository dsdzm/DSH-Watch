package com.dshwatch.domain

import com.dshwatch.domain.update.UpdateChannel
import com.dshwatch.domain.update.UpdateChecker
import com.dshwatch.domain.update.UpdateResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 测试通道：一次给一串发布，客户端得挑对那一条。
 *
 * 这些用例针对的全是「列表接口和单条接口不一样」的地方：顺序不是版本顺序、
 * 草稿与预发布混在一起、某一条漏传了安装包。任何一条判错，用户看到的都是
 * 「提示有新版本，装完还是旧的」—— 症状不明显，但会让人反复白装好几次。
 */
class UpdateChannelTest {

    private fun release(
        code: Int,
        tag: String = "v1.0.$code",
        draft: Boolean = false,
        prerelease: Boolean = false,
        asset: String? = """{"name":"dshwatch-$code.apk","size":100,
            "browser_download_url":"https://github.com/x/dshwatch-$code.apk",
            "digest":"sha256:abc$code"}""",
    ): String = """
        {"tag_name":"$tag","name":"$tag","draft":$draft,"prerelease":$prerelease,
         "published_at":"2026-11-13T06:00:00Z","body":"- 测试 $code",
         "assets":[${asset ?: ""}]}
    """.trimIndent()

    private fun list(vararg releases: String) = releases.joinToString(",", "[", "]")

    @Test
    fun `unknown channel id falls back to stable`() {
        // 这是「什么都不做」的那一侧：老存档里没有这个字段、或者将来改了名字，
        // 都应该继续收正式版，而不是收到测试版
        assertThat(UpdateChannel.of(null)).isEqualTo(UpdateChannel.STABLE)
        assertThat(UpdateChannel.of("")).isEqualTo(UpdateChannel.STABLE)
        assertThat(UpdateChannel.of("nightly")).isEqualTo(UpdateChannel.STABLE)
        assertThat(UpdateChannel.of("beta")).isEqualTo(UpdateChannel.BETA)
    }

    @Test
    fun `channels point at different endpoints`() {
        assertThat(UpdateChecker.releaseApiUrl())
            .isEqualTo("https://api.github.com/repos/dsdzm/DSH-Watch/releases/latest")
        // 列表接口才带预发布；/latest 永远不会返回它们
        assertThat(UpdateChecker.releaseApiUrl(UpdateChannel.BETA))
            .isEqualTo("https://api.github.com/repos/dsdzm/DSH-Watch/releases?per_page=30")
    }

    @Test
    fun `picks the highest version code, not the first in the list`() {
        // GitHub 按发布时间排，补发旧版本时顺序和版本号是反的。
        // 按第一条取 → 提示更新，装完版本号反而更小
        val json = list(release(70), release(74), release(72))
        val r = UpdateChecker.parseReleases(json, currentVersionCode = 70)
        assertThat(r).isInstanceOf(UpdateResult.Available::class.java)
        assertThat((r as UpdateResult.Available).manifest.versionCode).isEqualTo(74)
    }

    @Test
    fun `prerelease with a higher code is offered on the beta channel`() {
        val json = list(release(74), release(75, tag = "v1.22.5-beta.1", prerelease = true))
        val r = UpdateChecker.parseReleases(json, currentVersionCode = 74)
        val m = (r as UpdateResult.Available).manifest
        assertThat(m.versionCode).isEqualTo(75)
        // tag 原样去掉 v：界面上显示的就是 "1.22.5-beta.1"
        assertThat(m.versionName).isEqualTo("1.22.5-beta.1")
    }

    @Test
    fun `drafts are never offered`() {
        // 草稿只有仓库管理员看得见，普通用户拿到它的包也装不上 ——
        // 而且它往往是「还没写好说明的半成品」
        val json = list(release(74), release(80, draft = true))
        val r = UpdateChecker.parseReleases(json, currentVersionCode = 74)
        assertThat(r).isEqualTo(UpdateResult.UpToDate)
    }

    @Test
    fun `a release without an apk does not hide the one below it`() {
        // 同一次列表里，最新那条漏传了安装包；不能因此报「没有可用安装包」，
        // 也不能把 74 当成最新（74 就是当前版本）
        val json = list(release(75, asset = null), release(74), release(73))
        val r = UpdateChecker.parseReleases(json, currentVersionCode = 73)
        assertThat((r as UpdateResult.Available).manifest.versionCode).isEqualTo(74)
    }

    @Test
    fun `nothing newer means up to date`() {
        val json = list(release(73), release(72))
        assertThat(UpdateChecker.parseReleases(json, currentVersionCode = 74))
            .isEqualTo(UpdateResult.UpToDate)
    }

    @Test
    fun `http download urls are dropped from the list`() {
        // 列表通道多了一步「我们自己挑」，所以 https 过滤要在挑选之前做：
        // 否则会挑中一条 http 的、然后在 decide 里失败，把「有新版本」变成「检查失败」
        val insecure = """{"name":"dshwatch-76.apk","size":1,
            "browser_download_url":"http://x/dshwatch-76.apk"}"""
        val json = list(release(76, asset = insecure), release(74))
        val r = UpdateChecker.parseReleases(json, currentVersionCode = 73)
        assertThat((r as UpdateResult.Available).manifest.versionCode).isEqualTo(74)
    }

    @Test
    fun `an empty or malformed list is a failure, not up to date`() {
        // 「查不到」和「没有新版」必须分开：前者用户该重试，后者不该被打扰
        assertThat(UpdateChecker.parseReleases("[]", 74))
            .isInstanceOf(UpdateResult.Failed::class.java)
        assertThat(UpdateChecker.parseReleases("""{"message":"rate limited"}""", 74))
            .isInstanceOf(UpdateResult.Failed::class.java)
        assertThat(UpdateChecker.parseReleases("not json", 74))
            .isInstanceOf(UpdateResult.Failed::class.java)
    }

    @Test
    fun `beta still refuses to downgrade`() {
        // 用户从测试版切回稳定版后，再切到测试通道不能提示「更新」到更低的版本号 ——
        // 系统会拒绝降级安装
        val json = list(release(60), release(61))
        assertThat(UpdateChecker.parseReleases(json, currentVersionCode = 74))
            .isEqualTo(UpdateResult.UpToDate)
    }
}
