package com.dshwatch.domain.plugin

import com.dshwatch.core.Logx
import com.dshwatch.data.workspace.WorkspaceStore
import kotlinx.serialization.json.Json

/**
 * 从工作空间的 `plugins/` 目录读声明式插件。
 *
 * ## 为什么走目录而不是「设置页里粘贴 JSON」
 *
 * 手表上没有能舒服编辑 JSON 的输入法。而这个项目的模型本来就有 `write_file`——
 * 用户对助手说「照这份清单给我写一个插件」，文件落在 `plugins/` 下，
 * 下次打开设置页就出现在列表里。这条路径比任何表单都短。
 *
 * 目录是固定的，不做递归、不认符号链接：插件是可执行策略的一部分，
 * 「它会从哪些文件里被读出来」必须是一句话能说完的。
 *
 * ## 一个坏文件不能拖垮整批
 *
 * 逐个解析、逐个校验，失败的收集成错误行返回，成功的照常进列表。
 * 用户写了一打插件、其中一个少了个引号，不该导致另外十一个也不生效。
 */
object PluginLoader {

    /** 相对工作空间根目录 */
    const val DIR = "plugins"

    /**
     * 上限 12 个。
     *
     * 每个插件的追加段最长 4000 字符，12 个就是 48000 字符 —— 已经比
     * 整个工具清单还长。再多就不是「插件」而是「把提示词库塞进系统提示」，
     * 每轮都发一遍，首字延迟和费用都会肉眼可见。
     */
    const val MAX_FILES = 12

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    data class Scan(
        val manifests: List<PluginManifest> = emptyList(),
        /** 给人看的失败原因，已带文件名 */
        val errors: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    suspend fun scan(workspace: WorkspaceStore): Scan {
        val entries = runCatching { workspace.list(DIR) }.getOrElse { t ->
            Logx.w(TAG, "读取插件目录失败", t)
            return Scan(errors = listOf("读不了 $DIR/：${t.message.orEmpty()}"))
        }
        val files = entries
            .filter { !it.isDir && it.path.endsWith(".json", ignoreCase = true) }
            .sortedBy { it.path }
            .take(MAX_FILES)

        if (files.isEmpty()) return Scan()

        val manifests = mutableListOf<PluginManifest>()
        val errors = mutableListOf<String>()

        for (file in files) {
            val name = file.path.substringAfterLast('/')
            val text = workspace.read(file.path, maxChars = PluginManifest.MAX_CHARS + 1).orEmpty()
            if (text.isBlank()) {
                errors += "$name：读不到内容"
                continue
            }
            if (text.length > PluginManifest.MAX_CHARS) {
                errors += "$name：超过 ${PluginManifest.MAX_CHARS} 字符，没加载"
                continue
            }
            val manifest: PluginManifest
            try {
                manifest = json.decodeFromString<PluginManifest>(text)
            } catch (t: Throwable) {
                errors += "$name：不是合法的插件清单（${t.message?.take(80).orEmpty()}）"
                continue
            }
            val reason = manifest.validate()
            if (reason != null) {
                errors += "$name：$reason"
                continue
            }
            // 同 id 后来者覆盖：用户改文件名重装同一份插件时，不该出现两条
            manifests.removeAll { it.id == manifest.id }
            manifests += manifest
        }

        return Scan(manifests = manifests, errors = errors)
    }

    private const val TAG = "PluginLoader"
}
