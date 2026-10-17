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
}
