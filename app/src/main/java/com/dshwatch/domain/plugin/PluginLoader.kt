package com.dshwatch.domain.plugin

import com.dshwatch.core.Logx
import com.dshwatch.data.workspace.WorkspaceStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 声明式插件的解析入口。三条来源共用同一份校验：
 * `plugins/` 目录、剪贴板粘贴、从链接拉取。
 *
 * ## 一个坏文件不能拖垮整批
 *
 * 逐个解析、逐个校验，失败的收集成错误行返回，成功的照常进列表。
 * 用户写了一打插件、其中一个少了个引号，不该导致另外十一个也不生效。
 *
 * ## 目录不递归、不认符号链接
 *
 * 插件是策略的一部分，「它会从哪些文件里被读出来」必须是一句话能说完的。
 */
object PluginLoader {

    /** 相对工作空间根目录 */
    const val DIR = "plugins"

    /**
     * 目录里最多读 12 个。
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

    /** 解析结果。错误文案是直接给用户看的，所以每条都要带「怎么改」 */
    sealed class Parsed {
        data class Ok(val manifest: PluginManifest) : Parsed()
        data class Err(val message: String) : Parsed()
    }

    /**
     * 解析一段清单文本。
     *
     * [source] 只用于拼错误文案（文件名或链接），可以留空。
     */
    fun parse(text: String, source: String = ""): Parsed {
        val body = text.trim()
        if (body.isEmpty()) return Parsed.Err("内容是空的")
        if (body.length > PluginManifest.MAX_CHARS) {
            /*
             * 这条上限针对的是「清单文本」，不是文件大小。
             *
             * 用户最常见的撞墙方式：把网页地址当 raw 地址粘进「从链接导入」，
             * 拉回来一整页 HTML，几万字符 —— 而他看到的是「超过 8000 字符」，
             * 完全猜不到问题出在地址上。所以这里按来源给一句对得上号的话。
             */
            val where = if (source.isBlank()) "" else "$source："
            val hint = if (source.contains("链接")) {
                "拉回来的多半是一整页网页而不是原始文件 —— 打开页面点「Raw」，再复制那个地址。"
            } else {
                "插件清单不该这么长；如果这是压缩包，用「选择文件」导入，别复制文本。"
            }
            return Parsed.Err(
                "$where 内容有 ${body.length} 字符，超过 ${PluginManifest.MAX_CHARS} 上限，没加载。$hint",
            )
        }

        /*
         * 越权字段要在反序列化**之前**查。
         *
         * 反序列化会用 ignoreUnknownKeys 把不认识的键一律丢掉 ——
         * 用户写了 `"autoApprove": ["run_script"]`，App 静默忽略，
         * 他还以为免确认生效了，于是放心地把危险任务交出去。
         * 静默失败在这里比报错危险得多，所以宁可拒收整份清单。
         */
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (root != null) {
            forbidden(root, TOP_FORBIDDEN)?.let { return Parsed.Err(it) }
            (root["tools"] as? JsonObject)?.let { t ->
                forbidden(t, TOOLS_FORBIDDEN)?.let { return Parsed.Err(it) }
            }
        }

        val manifest = try {
            json.decodeFromString<PluginManifest>(body)
        } catch (t: Throwable) {
            val where = if (source.isBlank()) "" else "$source："
            return Parsed.Err("$where 不是合法的插件清单（${t.message?.take(80).orEmpty()}）")
        }
        manifest.validate()?.let { return Parsed.Err(it) }
        return Parsed.Ok(manifest)
    }

    /**
     * 已知的「想放宽权限」的字段名。
     *
     * 只列真正危险的：任意未知字段仍然忽略，因为兼容性比严格更重要
     * （别人写的清单会带 `homepage`、`license` 这类无害字段）。
     * 但这些词一旦出现，说明作者以为它能改审批 —— 必须当场说清楚。
     */
    private val TOP_FORBIDDEN = mapOf(
        "autoapprove" to "自动批准",
        "auto_approve" to "自动批准",
        "approval" to "审批规则",
        "approvals" to "审批规则",
        "allow" to "自动放行",
        "permissions" to "权限声明",
        "sandbox" to "沙箱策略",
        "script" to "脚本",
        "hooks" to "钩子",
    )

    private val TOOLS_FORBIDDEN = mapOf(
        "allow" to "自动放行",
        "allowlist" to "自动放行",
        "autoapprove" to "自动批准",
        "skip" to "跳过确认",
        "trust" to "信任",
    )

    private fun forbidden(obj: JsonObject, table: Map<String, String>): String? {
        val hit = obj.keys.firstOrNull { it.lowercase() in table } ?: return null
        val what = table.getValue(hit.lowercase())
        return "清单里有「$hit」（$what）—— 本 App 的插件只能收紧权限，不能放宽。" +
            "删掉这一段再导入；想少弹窗请到「AI 能力 → 审批」自己调。"
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
            when (val r = parse(text, name)) {
                is Parsed.Err -> errors += "$name：${r.message}"
                is Parsed.Ok -> {
                    // 同 id 后来者覆盖：用户改文件名重装同一份插件时，不该出现两条
                    manifests.removeAll { it.id == r.manifest.id }
                    manifests += r.manifest
                }
            }
        }

        return Scan(manifests = manifests, errors = errors)
    }

    private const val TAG = "PluginLoader"
}
