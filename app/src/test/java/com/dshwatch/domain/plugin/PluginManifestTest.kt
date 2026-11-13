package com.dshwatch.domain.plugin

import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Provider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 声明式插件的规则。
 *
 * 这些断言守的不是「功能对不对」，而是**边界**：一份 JSON 能让模型看到什么、
 * 不能让它看到什么。dsh-purge 那类项目靠打补丁突破宿主限制，我们反过来 ——
 * 给一个足够用但明确封顶的扩展点，然后用测试把天花板钉住。
 */
class PluginManifestTest {

    private fun manifest(
        id: String = "dsh-plugin-demo",
        append: String = "回答时先给结论。",
        identity: String = "",
        model: String = "",
    ) = PluginManifest(
        id = id,
        name = "演示插件",
        description = "测试用",
        prompt = PromptPart(
            identity = identity,
            append = append,
            condition = Condition(model = model),
        ),
    )

    private fun settings(model: String = "deepseek-chat", manifests: List<PluginManifest>, on: Boolean = true) =
        AppSettings(
            providers = listOf(Provider(id = "p", name = "测试", defaultModel = model)),
            activeProviderId = "p",
            pluginManifests = manifests,
            enabledPlugins = if (on) manifests.map { it.id }.toSet() else emptySet(),
            pluginsCustomized = true,
        )

    // ---------------- 校验 ----------------

    @Test
    fun `valid manifest passes`() {
        assertThat(manifest().validate()).isNull()
    }

    @Test
    fun `id must carry the plugin prefix`() {
        val reason = manifest(id = "purge").validate()
        assertThat(reason).contains("dsh-plugin-")
    }

    @Test
    fun `id cannot collide with a builtin`() {
        // 内置 id 里带 plugin 段的只有 save-token，它是最容易被误用的那个
        assertThat(manifest(id = "dsh-plugin-save-token").validate()).contains("重名")
        // 普通的 dsh-plugin-xxx 不冲突
        assertThat(manifest(id = "dsh-plugin-whale").validate()).isNull()
    }

    @Test
    fun `empty prompt is rejected`() {
        val m = manifest(append = "", identity = "")
        assertThat(m.validate()).contains("不会产生任何效果")
    }

    @Test
    fun `oversized prompt is rejected`() {
        val m = manifest(append = "x".repeat(PluginManifest.MAX_PROMPT + 1))
        assertThat(m.validate()).contains("太长")
    }

    @Test
    fun `schema mismatch is rejected`() {
        val m = manifest().copy(schema = 99)
        assertThat(m.validate()).contains("版本")
    }

    // ---------------- 条件匹配 ----------------

    @Test
    fun `glob matches model families`() {
        assertThat(globMatches("deepseek-*", "deepseek-chat")).isTrue()
        assertThat(globMatches("deepseek-*", "qwen3-32b")).isFalse()
        assertThat(globMatches("*reasoner*", "deepseek-reasoner-2026")).isTrue()
        assertThat(globMatches("", "anything")).isTrue()
        assertThat(globMatches("DeepSeek-Chat", "deepseek-chat")).isTrue()
    }

    @Test
    fun `manifest only applies to matching model`() {
        val m = manifest(model = "deepseek-*")
        val plugin = ManifestPromptPlugin(m)

        assertThat(plugin.contribute(PromptContext(settings("deepseek-chat", listOf(m)), "")))
            .contains("先给结论")
        // 换模型后整份清单失效，而不是「只跳过条件那段」
        assertThat(plugin.contribute(PromptContext(settings("qwen3-32b", listOf(m)), ""))).isEmpty()
        assertThat(plugin.identity(PromptContext(settings("qwen3-32b", listOf(m)), ""))).isNull()
    }

    // ---------------- 变量 ----------------

    @Test
    fun `variables are filled from the active provider`() {
        val m = manifest(append = "你是 {{model}}，由 {{provider}} 提供。")
        val plugin = ManifestPromptPlugin(m)
        val text = plugin.contribute(PromptContext(settings("deepseek-chat", listOf(m)), ""))
        assertThat(text).isEqualTo("你是 deepseek-chat，由 测试 提供。")
    }

    @Test
    fun `unknown variable stays untouched`() {
        val m = manifest(append = "{{nope}} 保持原样")
        val plugin = ManifestPromptPlugin(m)
        assertThat(plugin.contribute(PromptContext(settings("m", listOf(m)), "")))
            .isEqualTo("{{nope}} 保持原样")
    }

    // ---------------- 接进注册表 ----------------

    @Test
    fun `manifest plugin joins the registry when switched on`() {
        val m = manifest()
        val registry = PluginRegistry.from(settings("deepseek-chat", listOf(m)))

        assertThat(registry.active.map { it.id }).contains(m.id)
        assertThat(registry.promptSuffix(PromptContext(settings("deepseek-chat", listOf(m)), "")))
            .contains("先给结论")
    }

    @Test
    fun `manifest plugin is off by default`() {
        val m = manifest()
        val s = AppSettings(pluginManifests = listOf(m))
        val registry = PluginRegistry.from(s)

        // 出厂默认集合里没有它 —— 用户装进来是一回事，让它改模型是另一回事
        assertThat(registry.active.map { it.id }).doesNotContain(m.id)
        assertThat(PluginRegistry.allFor(s).map { it.id }).contains(m.id)
    }

    // ---------------- 目录加载 ----------------

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `loader keeps good files and reports bad ones`() = runBlocking {
        val root = tmp.newFolder("ws")
        val dir = File(root, PluginLoader.DIR).apply { mkdirs() }
        File(dir, "good.json").writeText(
            """{"id":"dsh-plugin-good","name":"好","prompt":{"append":"规则"}}"""
        )
        File(dir, "broken.json").writeText("{ 少个引号")
        File(dir, "empty.json").writeText(
            """{"id":"dsh-plugin-empty","name":"空","prompt":{"append":""}}"""
        )

        val scan = PluginLoader.scan(WorkspaceStore(root))

        assertThat(scan.manifests.map { it.id }).containsExactly("dsh-plugin-good")
        assertThat(scan.errors).hasSize(2)
        assertThat(scan.errors.toString()).contains("broken.json")
        assertThat(scan.errors.toString()).contains("empty.json")
    }

    @Test
    fun `loader ignores non json and nested files`() = runBlocking {
        val root = tmp.newFolder("ws")
        val dir = File(root, PluginLoader.DIR).apply { mkdirs() }
        File(dir, "readme.md").writeText("说明")
        File(dir, "nested").mkdirs()
        File(File(dir, "nested"), "x.json").writeText("""{"id":"dsh-plugin-nested"}""")

        val scan = PluginLoader.scan(WorkspaceStore(root))

        // 目录是平的、只认 .json —— 「插件从哪些文件读出来」必须一句话说得完
        assertThat(scan.manifests).isEmpty()
    }
}
