package com.dshwatch.domain.agent.tools

import android.content.Context
import com.dshwatch.core.Logx
import com.dshwatch.domain.agent.AgentTool
import com.dshwatch.domain.agent.Schema
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.agent.ToolResult
import com.dshwatch.domain.agent.ToolSafety
import com.dshwatch.domain.agent.boolOf
import com.dshwatch.domain.agent.strOf
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 让模型**自己把东西递进对话**。
 *
 * ## 这两个工具补的是同一个缺口
 *
 * 以前模型只能"读字"：`device_screen` 给一份无障碍控件列表，`read_file` 给文本。
 * 而设备操控真正难的地方常常是**画面** —— 布局有没有错位、图里到底写了什么、
 * 用户卡在哪一屏。更要紧的是手表这类设备：它自己没有截图能力，用户只能
 * `adb_shell` 或脚本截一张图放在某处，然后——然后就没有然后了，
 * 模型看不见那张图，只能请人"帮我看看这张图上有什么"。
 *
 * 现在有两条路：
 *  - [AttachFileTool] `attach_file`：把**已经存在的**文件（图片或文本）递进来。
 *    截图能力怎么来的不管 —— adb、脚本、别人发给你、工作空间里生成的都行。
 *  - [DeviceScreenshotTool] `device_screenshot`：有 Shizuku 时一步到位，
 *    `screencap -p` 的结果直接作为图片进对话。
 *
 * ## 图片是怎么"进眼睛"的
 *
 * 工具结果只回一行字，图片路径通过 [ToolResult.images] 交给
 * [com.dshwatch.domain.ChatEngine]：它在下一轮请求里把这几张图附成一条
 * user 消息（OpenAI 协议的 `role=tool` 消息不允许带图，只能这样补）。
 * 所以模型看到的是"我刚截的这张图"，而不是一段描述。
 */
object AttachTools {

    fun all(): List<AgentTool> = listOf(AttachFileTool, DeviceScreenshotTool)

    /** 能被当成图片送进模型的扩展名。判据是模型认不认，不是"能不能打开" */
    private val IMAGE_EXT = setOf(
        "png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif",
    )

    /** 图片源文件上限，和 `ImagePayload` 保持一致：再大解码就可能把进程顶掉 */
    private const val MAX_IMAGE_BYTES = 24L * 1024 * 1024

    /** 文本文件单次最多读这么多字，和 `read_file` 一个量级 */
    private const val MAX_TEXT_CHARS = 24_000

    /**
     * 哪些路径允许读。
     *
     * 不放开全盘，但也不是只有工作空间 —— 这个工具的用途就是"读那些
     * 模型自己没有能力产生的东西"，所以常见的外部落点都要能用：
     * `/sdcard`（adb 截图、录屏、下载）、`/data/local/tmp`（adb 推上去的文件）、
     * 以及本应用自己的目录。
     *
     * 明确拒绝别的应用的私有目录（`/data/data/<包名>`）：那是别人的数据，
     * 而且真读起来多半也是权限错误 —— 提前说清楚，比让它猜"为什么读不到"强。
     */
    private fun allowed(path: String, context: Context?): String? {
        val ownDirs = if (context == null) {
            emptyList()
        } else {
            listOf(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
                .filterNotNull()
                .map { it.absolutePath }
        }
        return checkPath(path, context?.packageName.orEmpty(), ownDirs)
    }

    /**
     * 路径白名单的纯逻辑版本。
     *
     * 抽出来是为了能在 JVM 单测里钉住「哪些地方能读」—— 这条规则决定了
     * 模型能不能翻用户别的应用的私有目录，不该只靠肉眼看一遍。
     *
     * @param selfPackage 本应用包名（空串 = 判不出来，这时不拦私有目录）
     * @param ownDirs 本应用自己的目录（files / cache 等）绝对路径
     */
    internal fun checkPath(path: String, selfPackage: String, ownDirs: List<String>): String? {
        val f = runCatching { File(path).canonicalFile }.getOrNull()
            ?: return "路径不合法：$path"
        val p = f.absolutePath
        if (p.startsWith("/data/data/") || p.startsWith("/data/user/")) {
            val owner = p.removePrefix("/data/data/")
                .removePrefix("/data/user/0/")
                .substringBefore('/')
            if (selfPackage.isNotBlank() && owner != selfPackage) {
                return "读不了别的应用的私有目录（$p）。换个位置放图，例如 /sdcard/Download/。"
            }
        }
        val okRoots = listOf("/sdcard", "/storage", "/mnt/sdcard", "/data/local/tmp", "/tmp")
        val own = ownDirs.any { p.startsWith(it) }
        val looksExternal = okRoots.any { p.startsWith(it) }
        if (!own && !looksExternal) {
            return "这个路径不在可读范围内（$p）。可以放在工作空间、/sdcard 或 /data/local/tmp 下。"
        }
        return null
    }

    private fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    /**
     * 把图片复制进应用自己的目录再交给模型。
     *
     * 为什么不在原地读：`/sdcard` 上那张图随时会被删、被系统清理、
     * 被下一个 `screencap` 覆盖同名文件。而多模态请求是**延迟**构造的
     * （可能几十秒后才发），那时路径已经指向别的内容了 —— 模型看到的
     * 会是另一张图，而且没有任何提示。复制一份是唯一稳的做法。
     */
    private fun importImage(source: File, context: Context?, tag: String): File? = runCatching {
        val dir = File(
            context?.filesDir ?: return null,
            "tool-images",
        )
        dir.mkdirs()
        val target = File(dir, "$tag-${System.currentTimeMillis()}-${source.name.takeLast(40)}")
        source.copyTo(target, overwrite = true)
        // 只留最近 20 张：这些图用完就没人再读了，攒着只是占空间
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(20)?.forEach { it.delete() }
        target
    }.onFailure { Logx.w("Attach", "复制图片失败：${it.message}") }.getOrNull()

    // ------------------------------------------------------------------

    object AttachFileTool : AgentTool {
        override val name = "attach_file"

        override val description =
            "把本地文件递进对话，让你看得见：图片按图片给你，其他文件按文本读入。" +
                "路径可写工作空间相对路径或本机绝对路径（/sdcard/Download/a.png）。" +
                "设备自己截不了图时，先 adb_shell \"screencap -p /sdcard/Download/a.png\" 再传这条路径。"

        override val safety = ToolSafety.READ_ONLY

        override val parameters = Schema.obj(
            properties = mapOf(
                "path" to Schema.str(
                    "文件路径：相对工作目录，或本机绝对路径（/sdcard/... 、/data/local/tmp/...）",
                ),
                "note" to Schema.str("一句话说明为什么要看它，会显示在对话里；可以留空"),
            ),
            required = listOf("path"),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val raw = args.strOf("path")?.trim().orEmpty()
            if (raw.isEmpty()) return ToolResult.fail("缺少 path 参数")
            val note = args.strOf("note").orEmpty().trim()
            val context = ctx.appContext

            // 三种可能：绝对路径、工作空间相对路径、工作空间绝对路径（模型常把根路径一起写进来）
            val candidates = buildList {
                if (raw.startsWith("/")) add(File(raw))
                add(File(ctx.workspaceRoot(), raw))
                val scoped = ctx.scope(raw)
                if (scoped != null) add(File(ctx.workspaceRoot(), scoped))
            }
            val file = candidates.firstOrNull { it.isFile }
                ?: return ToolResult.fail(
                    "找不到「$raw」。绝对路径要写全（/sdcard/Download/a.png）；" +
                        "工作空间里的文件用相对路径（例如 notes/a.png）。可以先用 list_files 看看有什么。",
                )

            allowed(file.absolutePath, context)?.let { return ToolResult.fail(it) }

            val ext = extOf(file.name)
            if (ext in IMAGE_EXT) {
                if (file.length() > MAX_IMAGE_BYTES) {
                    return ToolResult.fail(
                        "这张图 ${file.length() / 1024 / 1024}MB，超过 ${MAX_IMAGE_BYTES / 1024 / 1024}MB 上限。" +
                            "先让它小一点（截图降分辨率、或转成 jpg）再递进来。",
                    )
                }
                val copy = importImage(file, context, tag = "attach")
                    ?: return ToolResult.fail("复制图片失败（可能是空间不够）。图片路径：${file.absolutePath}")
                val size = "${(file.length() + 1023) / 1024}KB"
                val head = if (note.isBlank()) "已收到 1 张图片" else "已收到 1 张图片（$note）"
                return ToolResult.withImages(
                    text = "$head：${file.name}（$size）。你可以直接看图，不用请用户再发一遍。",
                    images = listOf(copy.absolutePath),
                )
            }

            val text = runCatching { file.readText() }.getOrElse { e ->
                return ToolResult.fail(
                    "读不了「${file.name}」：${e.javaClass.simpleName}。" +
                        "如果它是二进制（音频、压缩包），告诉我你想从里面知道什么，换个工具来做。",
                )
            }
            if (text.isBlank()) return ToolResult.fail("「${file.name}」是空的")
            val body = if (text.length > MAX_TEXT_CHARS) {
                text.take(MAX_TEXT_CHARS) + "\n…（还有 ${text.length - MAX_TEXT_CHARS} 字没读，用 offset 分段读）"
            } else {
                text
            }
            return ToolResult.ok("【${file.name}】\n$body\n【${file.name} 结束】")
        }

        override fun summarize(args: JsonObject): String =
            args.strOf("path")?.let { "看 " + it.substringAfterLast('/') } ?: "看图"
    }

    // ------------------------------------------------------------------

    object DeviceScreenshotTool : AgentTool {
        override val name = "device_screenshot"

        override val description =
            "给这台设备截屏并把图直接给你看（需要 Shizuku）。" +
                "device_screen 只给控件文字，布局与图上的字只有截图看得出。调用前先把目标界面留在屏幕上。"

        override val safety = ToolSafety.READ_ONLY

        /**
         * 只在有 Shizuku 时才出现。
         *
         * 没有 Shizuku 的机器上这个工具必然失败，而工具表每轮都要进上下文 ——
         * 让一个注定失败的 schema 常驻是纯浪费，也容易把模型引到"怪路"上。
         */
        override val enabled: (com.dshwatch.domain.model.AppSettings) -> Boolean = { it.shizukuEnabled }

        override val parameters = Schema.obj(
            properties = mapOf(
                "note" to Schema.str("一句话说明为什么要看这一屏，会显示在对话里；可以留空"),
                "keep" to Schema.bool("true 时保留上一个截屏文件（默认 false，只留最新一张）"),
            ),
        )

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val context = ctx.requireContext()
            when (com.dshwatch.domain.shell.ShizukuShell.state(context)) {
                com.dshwatch.domain.shell.ShizukuShell.State.READY -> Unit
                com.dshwatch.domain.shell.ShizukuShell.State.NOT_INSTALLED -> return ToolResult.fail(
                    "设备上没有 Shizuku，截不了屏。可以改用 adb_shell 或脚本截图，再 attach_file 那条路径。",
                )

                com.dshwatch.domain.shell.ShizukuShell.State.NOT_RUNNING -> return ToolResult.fail(
                    "Shizuku 装在设备上但没在运行。让用户打开 Shizuku 启动服务后再试；" +
                        "在此之前可以先 attach_file 一张已有的图。",
                )

                com.dshwatch.domain.shell.ShizukuShell.State.NOT_GRANTED -> return ToolResult.fail(
                    "Shizuku 在运行但没给 DSH 授权。让用户到「设置 → 设备操控 → Shizuku 提权」点授权。",
                )
            }

            val note = args.strOf("note").orEmpty().trim()
            val dir = File(context.cacheDir, "shots").apply { mkdirs() }
            if (!args.boolOf("keep")) {
                dir.listFiles()?.forEach { it.delete() }
            }
            val out = File(dir, "shot-${System.currentTimeMillis()}.png")

            /*
             * 用 `exec-out` 而不是 `screencap -p > 文件`：
             * 前者把原始字节写到标准输出，后者经过一层 shell 重定向 ——
             * 部分旧 ROM 的 toybox 在这个组合下会把换行改写，PNG 头就烂了。
             * 这里干脆让进程的标准输出直接落进文件（见 runIntoFile）。
             */
            val result = com.dshwatch.domain.shell.ShizukuShell.runIntoFile(
                command = "screencap -p",
                outFile = out,
            )
            if (!result.ok && !(result.exitCode == 0)) {
                return ToolResult.fail(
                    "截图失败：${result.error.takeIf { it.isNotBlank() }
                        ?: result.stderr.trim().take(120).ifBlank { "退出码 ${result.exitCode}" }}",
                )
            }
            if (!out.isFile || out.length() < 1024) {
                // 小于 1KB 一定不是一张完整的 PNG：多半是权限或 SELinux 拦了
                return ToolResult.fail(
                    "截图命令跑了，但没有拿到有效图像（${out.length()} 字节）。" +
                        "这台设备可能不允许 shell 读帧缓冲，改用 adb_shell 把图存到 /sdcard 再 attach_file。",
                )
            }

            val head = if (note.isBlank()) "已截屏" else "已截屏（$note）"
            return ToolResult.withImages(
                text = "$head，${(out.length() + 1023) / 1024}KB。图中是这台设备此刻的画面上的内容，" +
                    "直接看图判断，不要凭 device_screen 的文字猜。",
                images = listOf(out.absolutePath),
            )
        }

        override fun summarize(args: JsonObject): String =
            args.strOf("note")?.takeIf { it.isNotBlank() }?.let { "截屏 · $it" } ?: "截屏"
    }
}
