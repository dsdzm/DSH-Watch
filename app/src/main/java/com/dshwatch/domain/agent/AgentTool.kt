package com.dshwatch.domain.agent

import android.content.Context
import com.dshwatch.data.workspace.WorkspacePaths
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.domain.DeviceProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 工具的安全等级。
 * 决定「只读自动」模式下哪些工具可以不经确认直接跑。
 */
enum class ToolSafety(val label: String) {
    /** 纯计算/读取，无副作用 */
    READ_ONLY("只读"),

    /** 会改动本机数据（笔记、剪贴板、闹钟） */
    WRITE("写操作"),

    /** 会发出网络请求 */
    NETWORK("联网"),
}

data class ToolResult(
    val ok: Boolean,
    val content: String,
    /**
     * 这次调用产生的产物在工作空间里的相对路径（目前只有 build_project 会填）。
     *
     * 界面用它渲染「产物卡片」：用户可以直接点开预览、或导出分享，
     * 不必再去「工作空间」页里翻。工具本身只管干活，怎么呈现交给 UI。
     */
    val artifact: String = "",
) {
    companion object {
        fun ok(text: String, artifact: String = "") = ToolResult(true, text, artifact)
        fun fail(text: String) = ToolResult(false, text)
    }
}

/**
 * 工具执行上下文。
 * 刻意把 Android 依赖集中在这里，工具实现本身只管「参数进、文本出」，
 * 这样单测可以只测参数解析与结果格式化。
 */
class ToolContext(
    /**
     * 允许为 null：JVM 单测里没法构造 Android Context（android.jar 是桩），
     * 但 Agent 循环本身需要被完整驱动一遍，所以测试里塞 null + 假工具，
     * 只有真正碰系统能力的工具才会调用 [requireContext]。
     */
    val appContext: Context?,
    val workspace: WorkspaceStore,
    val usage: UsageRepository,
    val profile: () -> DeviceProfile,
    /**
     * 本会话的工作目录（相对于工作空间根），例如 `progress/a1b2c3`。
     * 空串表示直接用工作空间根 —— 老会话就是这种，保持兼容。
     */
    val sessionDir: String = "",
    /** 工作空间根的**绝对**路径（实时获取，存放位置可切换）。用于把模型给的绝对路径剥壳 */
    val workspaceRoot: () -> String = { "" },
    /** `ask_user` 与界面之间的通道 */
    val prompter: UserPromptChannel = UserPromptChannel(),
    /** agent 设过的闹钟（AlarmManager 自己查不到，只能自己记账） */
    val alarms: com.dshwatch.data.store.AlarmStore? = null,
    /** 多步任务清单（按会话目录分键） */
    val todos: TodoBoard? = null,
    /** 需要界面才能完成的动作（打开页面、弹预览） */
    val uiActions: UiActionChannel? = null,
    /**
     * 读当前设置（本地执行开关、超时、云端编译配置等）。
     *
     * 用函数而不是值：设置在运行期间会被用户改（比如刚在设置页打开了本地执行），
     * 而 [ToolContext] 是构造时快照的 —— 传值会让工具一直看到旧设置，
     * 表现为"我明明打开了开关，模型还说没开"。
     */
    val settings: () -> com.dshwatch.domain.model.AppSettings = {
        com.dshwatch.domain.model.AppSettings()
    },
    /** 云端编译的 run 记录（按会话分键）；lite 之外都可用 */
    val cloudRuns: com.dshwatch.domain.agent.cloud.CloudRunStore? = null,
    /** GitHub API 入口（token 由 AppContainer 从加密存储里取），未配置时为 null */
    val github: com.dshwatch.domain.agent.cloud.GitHubApi? = null,
) {
    fun requireContext(): Context =
        appContext ?: error("该工具需要 Android Context，当前运行环境没有提供")

    /** 给系统提示用的可读目录名 */
    val workDirLabel: String get() = sessionDir.trim().trim('/').ifBlank { "（工作空间根目录）" }

    /**
     * 换一个会话目录的副本。
     * ToolRegistry 是应用级单例，而会话目录是执行时才确定的，所以每次执行都要换一副「眼镜」。
     */
    fun withSession(dir: String): ToolContext = ToolContext(
        appContext = appContext,
        workspace = workspace,
        usage = usage,
        profile = profile,
        sessionDir = dir,
        workspaceRoot = workspaceRoot,
        prompter = prompter,
        alarms = alarms,
        todos = todos,
        uiActions = uiActions,
        // 新字段必须在这里同步 —— 漏掉不会编译报错，但换会话后工具会静默看不到设置，
        // 那种 bug 极难定位（ToolContextTest 专门钉住这一点）
        settings = settings,
        cloudRuns = cloudRuns,
        github = github,
    )

    /** 模型给的路径 → 工作空间内的全局相对路径（落在本会话目录之下）。null = 路径非法 */
    fun scope(raw: String): String? =
        WorkspacePaths.resolveUnder(raw, sessionDir, workspaceRoot())

    /**
     * 全局相对路径 → 相对本会话目录的路径。
     * 模型看到的一律是「相对于当前工作目录」的短路径，换会话也不会串。
     */
    fun unscope(path: String): String = WorkspacePaths.stripBase(path, sessionDir)
}

/**
 * 一个可被模型调用的工具。
 *
 * 之所以把 `parameters` 定成 JsonObject 而不是让每个工具手写字符串：
 * 手写 JSON 字符串极易漏引号/逗号，而且编译期查不出来，
 * 走 [Schema] 构造器可以保证结构合法，也让 schema 本身可以被单测覆盖。
 */
interface AgentTool {
    val name: String
    val description: String
    val safety: ToolSafety

    /** JSON Schema（type: object） */
    val parameters: JsonObject

    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult

    /** 界面上一行参数摘要 */
    fun summarize(args: JsonObject): String = args.toString().take(90)
}

object Schema {

    fun obj(
        properties: Map<String, JsonObject>,
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            properties.forEach { (key, schema) -> put(key, schema) }
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
        put("additionalProperties", false)
    }

    fun str(desc: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", desc)
        if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
    }

    fun num(desc: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", desc)
    }

    fun int(desc: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", desc)
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

    fun bool(desc: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", desc)
    }

    /**
     * 任意键值对（值都是字符串）。
     *
     * 用于「表头」这类键名不固定的参数。注意 [obj] 会把外层对象的
     * `additionalProperties` 置为 false，所以这里必须单独给这个属性
     * 声明 `additionalProperties: {"type":"string"}`，否则模型传什么键都会被判非法。
     */
    fun strMap(desc: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("description", desc)
        putJsonObject("additionalProperties") { put("type", "string") }
    }

    @Suppress("unused")
    fun arr(desc: String, itemType: String = "string"): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", desc)
        putJsonObject("items") { put("type", itemType) }
    }
}

/** 从参数里安全取字符串 */
fun JsonObject.strOf(key: String): String? =
    this[key]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }
        ?.takeIf { it.isNotBlank() && it != "null" }

/**
 * 取原始字符串，**不过滤空串**。
 *
 * [strOf] 会把空串当成「没给」—— 这在大多数参数上是对的，但对
 * 「把这段替换成空（也就是删掉）」这种语义就是错的，所以单独开一个。
 */
fun JsonObject.rawStrOf(key: String): String? =
    this[key]?.let { if (it is kotlinx.serialization.json.JsonPrimitive) it.contentOrNull else null }

fun JsonObject.intOf(key: String): Int? =
    strOf(key)?.trim()?.toDoubleOrNull()?.toInt()

fun JsonObject.doubleOf(key: String): Double? = strOf(key)?.trim()?.toDoubleOrNull()

fun JsonObject.boolOf(key: String, default: Boolean = false): Boolean =
    strOf(key)?.trim()?.lowercase()?.let { it == "true" || it == "1" || it == "yes" } ?: default

@Suppress("unused")
fun jsonArrayOfStrings(values: List<String>): JsonArray = buildJsonArray { values.forEach { add(it) } }

/**
 * 读一个字符串数组参数。
 * 模型经常把数组写成 `"["a","b"]"`（字符串里裹着 JSON）而不是真数组，两种都要认。
 */
fun JsonObject.stringListOf(key: String): List<String> {
    val element = this[key] ?: return emptyList()
    if (element is JsonArray) {
        return element.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            .filter { it.isNotBlank() }
    }
    val raw = strOf(key) ?: return emptyList()
    val trimmed = raw.trim()
    if (trimmed.startsWith("[")) {
        runCatching {
            val arr = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
            if (arr is JsonArray) {
                return arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    .filter { it.isNotBlank() }
            }
        }
    }
    // 退化成用换行/分号分隔的纯文本，也比直接丢掉强
    return trimmed.split('\n', ';', '|').map { it.trim().trimStart('-', '*', ' ') }
        .filter { it.isNotBlank() }
}

/**
 * 读一个「键值对」参数（如自定义请求头）。
 *
 * 和 [stringListOf] 一样，模型有时把对象写成字符串里裹着的 JSON，
 * 两种形态都要认；值不是字符串的条目直接忽略（请求头只能是字符串）。
 */
fun JsonObject.stringMapOf(key: String): Map<String, String> {
    val element = this[key] ?: return emptyMap()
    val obj = when (element) {
        is JsonObject -> element
        is kotlinx.serialization.json.JsonPrimitive -> {
            val raw = element.contentOrNull?.trim().orEmpty()
            if (!raw.startsWith("{")) return emptyMap()
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
            }.getOrNull()
        }
        else -> null
    } ?: return emptyMap()

    val out = LinkedHashMap<String, String>()
    obj.forEach { (k, v) ->
        val value = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if (!k.isBlank() && value != null) out[k] = value
    }
    return out
}
