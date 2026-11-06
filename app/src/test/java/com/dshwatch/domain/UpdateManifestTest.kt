package com.dshwatch.domain

import com.dshwatch.core.Jsonx
import com.dshwatch.domain.update.UpdateChecker
import com.dshwatch.domain.update.UpdateManifest
import com.dshwatch.domain.update.UpdateResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 更新清单的解析边界。
 *
 * 这里的每一条都对应一种「服务端改了、客户端不能崩」的情况：
 * 发布脚本和 App 版本不同步是常态，而 GitHub 的资产命名、digest 字段
 * 都是外部契约，随时可能变。
 */
class UpdateManifestTest {

    /** 把任意文本塞进 JSON 字符串字面量里，省得每条用例都手写转义 */
    private fun jsonStr(s: String) =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun ghRelease(
        tag: String = "v1.15.19",
        assets: String = """
            {"name":"dshwatch-42.apk","size":8427539,
             "browser_download_url":"https://github.com/dsdzm/DSH-Watch/releases/download/v1.15.19/dshwatch-42.apk",
             "digest":"sha256:ab002ba2"}
        """.trimIndent(),
        body: String = "- 支持检查更新",
    ) = """
        {"tag_name":"$tag","name":"1.15.19","draft":false,"prerelease":false,
         "published_at":"2026-11-06T06:00:00Z","body":"${jsonStr(body)}",
         "assets":[$assets]}
    """.trimIndent()

    @Test
    fun `parses a full manifest`() {
        val json = """
            {
              "versionCode": 41,
              "versionName": "1.15.18",
              "size": 8394583,
              "sha256": "abc123",
              "url": "https://github.com/x/y.apk",
              "notes": ["支持检查更新", "自动检查开关"],
              "publishedAt": "2026-11-06T13:00:00+08:00"
            }
        """.trimIndent()

        val m = Jsonx.lenient.decodeFromString<UpdateManifest>(json)
        assertThat(m.versionCode).isEqualTo(41)
        assertThat(m.versionName).isEqualTo("1.15.18")
        assertThat(m.size).isEqualTo(8394583L)
        assertThat(m.sha256).isEqualTo("abc123")
        assertThat(m.notes).hasSize(2)
    }

    @Test
    fun `optional fields fall back instead of failing`() {
        // 最小清单：只有必填的三项
        val json = """{"versionCode":7,"versionName":"1.0.7","url":"https://x/y.apk"}"""
        val m = Jsonx.lenient.decodeFromString<UpdateManifest>(json)
        assertThat(m.size).isEqualTo(0L)
        assertThat(m.sha256).isEmpty()
        assertThat(m.notes).isEmpty()
        assertThat(m.publishedAt).isEmpty()
    }

    @Test
    fun `unknown fields do not break older clients`() {
        val json = """
            {"versionCode":42,"versionName":"1.15.19","url":"https://x/y.apk",
             "channel":"stable","minSupported":30,"rollout":0.3}
        """.trimIndent()
        val m = Jsonx.lenient.decodeFromString<UpdateManifest>(json)
        assertThat(m.versionCode).isEqualTo(42)
    }

    @Test
    fun `only a strictly greater version code counts as an update`() {
        assertThat(UpdateChecker.isNewer(41, 40)).isTrue()
        assertThat(UpdateChecker.isNewer(40, 40)).isFalse()
        // 服务端回滚到旧版本时不能提示“更新”，否则会引导用户降级安装（系统会拒绝）
        assertThat(UpdateChecker.isNewer(39, 40)).isFalse()
    }

    // ------------------------------------------------------------ GitHub 响应

    @Test
    fun `version code comes from the asset name, not the tag`() {
        // GitHub 的 API 里没有 versionCode，只能从 dshwatch-42.apk 这个命名里取。
        // 一旦发版脚本改了命名，这里会先红，而不是等到用户手机上「永远是最新」。
        val r = UpdateChecker.parseRelease(ghRelease(), currentVersionCode = 41)
        assertThat(r).isInstanceOf(UpdateResult.Available::class.java)
        val m = (r as UpdateResult.Available).manifest
        assertThat(m.versionCode).isEqualTo(42)
        assertThat(m.versionName).isEqualTo("1.15.19")
        assertThat(m.size).isEqualTo(8427539L)
        assertThat(m.sha256).isEqualTo("ab002ba2")
        assertThat(m.notes).containsExactly("支持检查更新")
    }

    @Test
    fun `release without an apk asset is a failure, not up-to-date`() {
        // 发版时漏传 APK，如果当成「已是最新」，用户会以为自己在最新版本
        val onlySums = """{"name":"SHA256SUMS.txt","size":198,
            "browser_download_url":"https://github.com/x/SHA256SUMS.txt"}"""
        val r = UpdateChecker.parseRelease(ghRelease(assets = onlySums), 41)
        assertThat(r).isInstanceOf(UpdateResult.Failed::class.java)
    }

    @Test
    fun `same version code means up to date`() {
        val r = UpdateChecker.parseRelease(ghRelease(), currentVersionCode = 42)
        assertThat(r).isEqualTo(UpdateResult.UpToDate)
    }

    @Test
    fun `missing digest just disables checksum, does not fail`() {
        // 老仓库的资产没有 digest 字段；这时只能靠 size 校验，不能因此拒绝更新
        val noDigest = """{"name":"dshwatch-42.apk","size":100,
            "browser_download_url":"https://github.com/x/dshwatch-42.apk"}"""
        val r = UpdateChecker.parseRelease(ghRelease(assets = noDigest), 41)
        val m = (r as UpdateResult.Available).manifest
        assertThat(m.sha256).isEmpty()
    }

    @Test
    fun `notes drop markdown noise`() {
        val body = """
            ## 新增
            - 支持检查更新
            * 自动检查开关

            ---
            ![截图](https://x/y.png)
        """.trimIndent()
        val m = (UpdateChecker.parseRelease(ghRelease(body = body), 41) as UpdateResult.Available).manifest
        assertThat(m.notes).containsExactly("支持检查更新", "自动检查开关").inOrder()
    }

    @Test
    fun `api url points at the public releases endpoint`() {
        // 写死在这里，防止有人改成私有仓库（私有仓库匿名读不到，表现是 404）
        assertThat(UpdateChecker.releaseApiUrl())
            .isEqualTo("https://api.github.com/repos/dsdzm/DSH-Watch/releases/latest")
    }

    // ------------------------------------------------------------ 下载源链

    @Test
    fun `mirrors come first and the github url stays as the last resort`() {
        // 顺序是性能问题不是风格问题：直链国内实测 9~25 KB/s，镜像 1.2~1.8 MB/s。
        // 直链排前面等于白等十几分钟才切源。
        val direct = "https://github.com/x/y/releases/download/v1/dshwatch-42.apk"
        val s = UpdateChecker.downloadSources(direct)
        assertThat(s).hasSize(3)
        assertThat(s[0]).isEqualTo("https://ghfast.top/$direct")
        assertThat(s[1]).isEqualTo("https://gh-proxy.com/$direct")
        assertThat(s.last()).isEqualTo(direct)
    }

    @Test
    fun `an url already behind a mirror is not wrapped twice`() {
        val mirrored = "https://ghfast.top/https://github.com/x/y.apk"
        val s = UpdateChecker.downloadSources(mirrored)
        assertThat(s.none { it.startsWith("https://ghfast.top/https://ghfast.top") }).isTrue()
        assertThat(s.last()).isEqualTo(mirrored)
    }
}
