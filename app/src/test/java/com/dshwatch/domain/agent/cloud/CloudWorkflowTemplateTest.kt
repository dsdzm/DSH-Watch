package com.dshwatch.domain.agent.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * 云端构建模板的安装步骤。
 *
 * 这组断言来自一次真实的 CI 失败（run 39002138855）：
 * `android-actions/setup-android@v3` 默认要装 `tools` 包，而该包已经从
 * SDK 仓库下架 —— `sdkmanager` 打印 "Failed to find package 'tools'" 后返回非零，
 * action 随即抛错，job 在「装 SDK」这一步就结束，连 Gradle 都没跑到。
 *
 * 现在改成直接用 runner 自带的 SDK，只补 platforms / build-tools。
 * 这几条测试锁住三件事：不再引用那个 action、装的包是现存的、
 * 以及装的 platform 与 `compileSdk` 对齐（升级 compileSdk 时不会漏改模板）。
 */
class CloudWorkflowTemplateTest {

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/cloud/$name"),
            File("app/src/main/assets/cloud/$name"),
        )
        return candidates.first { it.exists() }.readText()
    }

    private fun appGradle(): String {
        val candidates = listOf(
            File("build.gradle.kts"),
            File("app/build.gradle.kts"),
        )
        return candidates.first { it.exists() }.readText()
    }

    private fun compileSdk(): String {
        val match = Regex("""compileSdk\s*=\s*(\d+)""").find(appGradle())
        assertThat(match).isNotNull()
        return match!!.groupValues[1]
    }

    @Test
    fun `build template does not use the tools-installing action`() {
        val yaml = asset("dsh-build.yml")

        // 这个 action 的默认 packages 含已下架的 tools，正是那次失败的原因。
        // 只看 `uses:` 行：注释里提到它（解释为什么不用）是正常的。
        assertThat(yaml).doesNotContain("uses: android-actions/setup-android")
        assertThat(yaml).doesNotContain("sdkmanager tools")
    }

    @Test
    fun `build template sets up an SDK that actually exists`() {
        val yaml = asset("dsh-build.yml")

        // sdkmanager 的包名必须真实存在：platform-tools、platforms;android-NN、build-tools;NN.0.0
        assertThat(yaml).contains("platform-tools")
        assertThat(yaml).contains("platforms;android-${compileSdk()}")
        assertThat(yaml).contains("build-tools;35.0.0")

        // 安装参数里不能再出现 tools（注释里解释来源不算）
        assertThat(yaml).doesNotContain("\"tools\" \"")
    }

    @Test
    fun `build template exports SDK location for gradle`() {
        val yaml = asset("dsh-build.yml")

        // 没有 ANDROID_HOME，Gradle 会回落到 ~/Android/Sdk 并报 "SDK location not found"
        assertThat(yaml).contains("ANDROID_HOME")
        assertThat(yaml).contains("ANDROID_SDK_ROOT")
    }

    @Test
    fun `build template can locate sources when the repo is workflow-only`() {
        val yaml = asset("dsh-build.yml")

        // 用户把模板装进了一个空仓库（dsdzm/dsh-），checkout 后没有 gradlew，
        // 于是 `chmod +x ./gradlew` 直接失败 —— run 39003529450。
        // 模板必须能在这种仓库里自己把源码取过来。
        assertThat(yaml).contains("source_repo")
        assertThat(yaml).contains("PROJECT_DIR")
        assertThat(yaml).contains("git clone")
        assertThat(yaml).contains("cd \"\$PROJECT_DIR\"")
    }

    // ------------------------------------------------------------------
    // 构建耗时优化：这几条锁住「为什么这么配」，避免以后被"简化"回去
    // ------------------------------------------------------------------

    @Test
    fun `build template uses the official gradle action instead of hand-rolled cache`() {
        val yaml = asset("dsh-build.yml")

        // 手写 actions/cache 有两个真实缺陷：
        //  ① key 用 hashFiles('**/*.gradle*') 且没有 restore-keys ——
        //     改一行 build.gradle.kts 就 1.6GB 全额失效；
        //  ② 缓存整个 ~/.gradle/caches，含 973MB 的 transforms。
        // setup-gradle 自带分级 restore-keys 与 stale 清理。
        assertThat(yaml).contains("gradle/actions/setup-gradle@")
        assertThat(yaml).doesNotContain("uses: actions/cache")
    }

    @Test
    fun `build template keeps caching on the open-source provider`() {
        val yaml = asset("dsh-build.yml")

        // v6 的 cache-provider 默认值是 enhanced —— 闭源的 gradle-actions-caching，
        // 启用即接受 Gradle 的商业条款。开源项目用 basic（MIT）就够：
        // 分级恢复、清理这些主要收益都在。
        assertThat(yaml).contains("cache-provider: basic")
    }

    @Test
    fun `build template sizes the JVM heap from the runner instead of hard-coding`() {
        val yaml = asset("dsh-build.yml")

        // 仓库里的 gradle.properties 写死 4G，那是给 8G 容器调的。
        // 公开仓库 Runner 是 16G、私有仓库只有 7G —— 同一个数字一边浪费一边 OOM。
        // 模板必须按 /proc/meminfo 算，并且不能出现"照着建议调大"的固定值。
        assertThat(yaml).contains("MemTotal")
        assertThat(yaml).contains("kotlin.daemon.jvmargs")
        assertThat(yaml).doesNotContain("-Xmx6g")
        assertThat(yaml).doesNotContain("-Xmx8g")
    }

    @Test
    fun `build template reorders maven repos only after measuring`() {
        val yaml = asset("dsh-build.yml")

        // settings.gradle.kts 把阿里云排在最前（给国内网络）。GHA Runner 在海外，
        // 理论上上游更快 —— 但 Gradle 的缓存按仓库隔离（descriptor 路径里那段 hash
        // 就是仓库指纹），换源等于换 hash，已有缓存全部不命中、要全量重下 470MB。
        // 所以必须先测速再决定，不能写死成 google() 优先。
        assertThat(yaml).contains("RTT")
        assertThat(yaml).contains("probe")
        // 切换动作必须被 SWITCH 判断挡住 —— 没有这个闸门就等于无条件换源
        assertThat(yaml).contains("SWITCH")
        assertThat(yaml).contains("\"\$SWITCH\" = \"true\"")
    }

    @Test
    fun `build template emits a profile for diagnosing slow builds`() {
        val yaml = asset("dsh-build.yml")

        // 没有 profile 就只能靠猜。--profile 出 HTML 各阶段耗时，
        // --console=plain 才会打出 "> Task :x FROM-CACHE"，命中率才数得出来。
        assertThat(yaml).contains("--profile")
        assertThat(yaml).contains("--console=plain")
        assertThat(yaml).contains("FROM-CACHE")
    }

    @Test
    fun `repository copy of the template matches the bundled asset`() {
        // 手表端「安装 workflow 模板」写入的是 assets 里这份，
        // 而仓库自己 CI 跑的是 .github/workflows 那份。
        // 两者必须一致，否则"手表上装的和仓库跑的不是同一个东西"，
        // 排查时会对着两份不同的 YAML 互相矛盾。
        val repoCopy = listOf(
            File("../.github/workflows/dsh-build.yml"),
            File(".github/workflows/dsh-build.yml"),
        ).firstOrNull { it.exists() }

        assertThat(repoCopy).isNotNull()
        assertThat(repoCopy!!.readText()).isEqualTo(asset("dsh-build.yml"))
    }
}
