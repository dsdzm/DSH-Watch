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
        val plugin = ManifestPlugin(m)

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
        val plugin = ManifestPlugin(m)
        val text = plugin.contribute(PromptContext(settings("deepseek-chat", listOf(m)), ""))
        assertThat(text).isEqualTo("你是 deepseek-chat，由 测试 提供。")
    }

    @Test
    fun `unknown variable stays untouched`() {
        val m = manifest(append = "{{nope}} 保持原样")
        val plugin = ManifestPlugin(m)
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

    // ---------------- 工具面（schema 2） ----------------

    private fun toolManifest(
        hide: List<String> = emptyList(),
        only: List<String> = emptyList(),
        confirm: List<String> = emptyList(),
        append: String = "",
    ) = PluginManifest(
        id = "dsh-plugin-tools",
        name = "收窄",
        description = "测试用",
        prompt = if (append.isEmpty()) null else PromptPart(append = append),
        tools = ToolsPart(hide = hide, only = only, confirm = confirm),
    )

    @Test
    fun `hide blocks the tool in the registry`() {
        val m = toolManifest(hide = listOf("run_script", "adb_shell"))
        val s = settings("deepseek-chat", listOf(m))
        val registry = PluginRegistry.from(s)

        // 这是真的权限边界：不是「少给模型看」，是引擎执行前会拒
        assertThat(registry.deniedTools(s)).containsExactly("run_script", "adb_shell")
        assertThat(registry.allowOnlyTools(s)).isNull()
    }

    @Test
    fun `only keeps just the listed tools`() {
        val m = toolManifest(only = listOf("read_file", "web_search"))
        val s = settings("deepseek-chat", listOf(m))
        assertThat(PluginRegistry.from(s).allowOnlyTools(s))
            .containsExactly("read_file", "web_search")
    }

    @Test
    fun `two whitelists intersect instead of union`() {
        // 交集只会更严，并集会悄悄多放行 —— 这里守的是方向
        val a = toolManifest(only = listOf("read_file", "write_file")).copy(id = "dsh-plugin-a")
        val b = toolManifest(only = listOf("read_file", "run_script")).copy(id = "dsh-plugin-b")
        val s = settings("deepseek-chat", listOf(a, b))
        assertThat(PluginRegistry.from(s).allowOnlyTools(s)).containsExactly("read_file")
    }

    @Test
    fun `confirm tools add up across plugins`() {
        val a = toolManifest(confirm = listOf("write_file")).copy(id = "dsh-plugin-a")
        val b = toolManifest(confirm = listOf("edit_file")).copy(id = "dsh-plugin-b")
        val s = settings("deepseek-chat", listOf(a, b))
        assertThat(PluginRegistry.from(s).confirmTools(s)).containsExactly("write_file", "edit_file")
    }

    @Test
    fun `hide and only cannot be combined`() {
        val m = toolManifest(hide = listOf("run_script"), only = listOf("read_file"))
        assertThat(m.validate()).contains("只能写一个")
    }

    @Test
    fun `bad tool name is rejected`() {
        val m = toolManifest(hide = listOf("Run-Script!"))
        assertThat(m.validate()).contains("不是合法的工具名")
    }

    @Test
    fun `tools only manifest needs no prompt`() {
        // 纯收窄型插件不带一句提示词也应该能装
        assertThat(toolManifest(hide = listOf("run_script")).validate()).isNull()
    }

    @Test
    fun `empty tools section is rejected`() {
        assertThat(toolManifest().validate()).contains("不会产生任何效果")
    }

    @Test
    fun `condition also gates the tool face`() {
        val m = toolManifest(hide = listOf("run_script")).copy(
            tools = ToolsPart(hide = listOf("run_script"), condition = Condition("deepseek-*")),
        )
        val qwen = AppSettings(
            providers = listOf(Provider(id = "p", name = "测试", defaultModel = "qwen3-32b")),
            activeProviderId = "p",
        )
        assertThat(ManifestPlugin(m).denyTools(qwen)).isEmpty()
    }

    // ---------------- 只能收紧 ----------------

    @Test
    fun `approval widening fields are rejected outright`() {
        // 静默忽略会让用户以为免确认生效了 —— 这是最危险的一种「成功」
        val bad = """{"id":"dsh-plugin-x","name":"x","prompt":{"append":"a"},"autoApprove":["run_script"]}"""
        val r = PluginLoader.parse(bad)
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((r as PluginLoader.Parsed.Err).message).contains("只能收紧")
    }

    @Test
    fun `tools allow is rejected too`() {
        val bad = """{"id":"dsh-plugin-x","name":"x","tools":{"allow":["run_script"]}}"""
        val r = PluginLoader.parse(bad)
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((r as PluginLoader.Parsed.Err).message).contains("只能收紧")
    }

    @Test
    fun `harmless unknown fields are still ignored`() {
        val ok = """{"id":"dsh-plugin-xxx","name":"x","homepage":"https://e.com","prompt":{"append":"a"}}"""
        assertThat(PluginLoader.parse(ok)).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
    }

    @Test
    fun `schema 1 manifest still loads`() {
        val old = """{"schema":1,"id":"dsh-plugin-old","name":"旧","prompt":{"append":"规则"}}"""
        assertThat(PluginLoader.parse(old)).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
    }

    // ---------------- 导入与目录并存 ----------------

    @Test
    fun `imported plugins survive a directory rescan`() {
        val imported = manifest(id = "dsh-plugin-imported")
        val fromDir = manifest(id = "dsh-plugin-dir")
        val s = AppSettings(
            pluginManifests = listOf(fromDir),
            importedPlugins = listOf(imported),
        )
        // 目录扫描会整个替换 pluginManifests —— 导入的那份不能跟着丢
        assertThat(PluginRegistry.allFor(s).map { it.id })
            .containsAtLeast("dsh-plugin-imported", "dsh-plugin-dir")
    }

    @Test
    fun `imported wins when ids collide`() {
        val dir = manifest(id = "dsh-plugin-x").copy(name = "目录版")
        val imp = manifest(id = "dsh-plugin-x").copy(name = "导入版")
        val s = AppSettings(pluginManifests = listOf(dir), importedPlugins = listOf(imp))
        val same = PluginRegistry.allFor(s).filterIsInstance<ManifestPlugin>().filter { it.id == "dsh-plugin-x" }
        assertThat(same).hasSize(1)
        assertThat(same.first().displayName).isEqualTo("导入版")
    }

    // ---------------- 文档里的示例 ----------------

    /**
     * docs/plugins 下的每份示例都必须真的能通过校验。
     *
     * 用户是照着这些文件改的。示例一旦过期（比如 schema 升了、字段改名了），
     * 用户抄出来的第一份清单就是坏的，而他不会怀疑文档 —— 只会觉得插件功能坏了。
     * 这条测试把文档和校验器绑在一起。
     */
    @Test
    fun `bundled examples all parse`() {
        val dir = listOf(File("docs/plugins"), File("../docs/plugins"))
            .firstOrNull { it.isDirectory }
            ?: error("找不到 docs/plugins，测试的工作目录是 ${File(".").absolutePath}")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertThat(files.size).isAtLeast(3)
        files.forEach { f ->
            val r = PluginLoader.parse(f.readText(), f.name)
            assertThat(r).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
        }
    }
}
