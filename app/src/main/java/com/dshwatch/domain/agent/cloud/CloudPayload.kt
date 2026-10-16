package com.dshwatch.domain.agent.cloud

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把会话工作目录的内容打包成「可内联进命令」的载荷。
 *
 * ## 为什么需要它
 *
 * `cloud_build` 是在 GitHub 的**空仓库**里执行命令的 —— 手表上用 write_file 写出来的文件
 * 并不会跟着过去。用户第一次跑"随便一个 Python 项目"时，云端报的就是：
 *
 *     python: can't open file '/home/runner/work/dsh-/dsh-/main.py'
 *
 * 结果模型只好自己悟出"把源码 heredoc 内联进命令"这一招，
 * 还在回答里写"两个值得记住的坑"提醒用户下次注意。
 * **这不该由模型去记**：工具明明知道工作目录里有什么，就该自己带上。
 *
 * ## 为什么塞进命令、而不是给模板加参数
 *
 * 实测：给 workflow_dispatch 传模板没定义的 input，GitHub 直接回
 * `422 Unexpected inputs provided: ["payload"]` —— 也就是说
 * **"新 App + 用户仓库里的旧模板"会当场失败**，而我们没法替所有人更新模板。
 * 而 `command` 本身是一段自由的 shell 脚本，把载荷放进去，模板一个字都不用改，
 * 版本兼容问题自然不存在。
 *
 * ## 代价：长度
 *
 * 实测 `command` 在 30,000 字符时可用、100,000 字符时 GitHub 直接拒绝
 * （`inputs are too large`）。所以这里设硬上限，**在本地就拦住超限的情况并说清楚**，
 * 而不是把一个 422 丢给用户去猜。
 */
object CloudPayload {

    /**
     * 内联载荷的长度上限。
     *
     * 实测 30K 可用、100K 被拒 —— 取 20K 留足余量：请求里除了载荷还有命令、产物名等字段，
     * 而超限是**硬失败**，宁可保守。
     */
    const val MAX_BASE64_CHARS = 20_000

    /** base64 的字符集是 A-Za-z0-9+/=，没有下划线 —— 这个标记绝不会与载荷内容撞车 */
    private const val MARKER = "DSH_PAYLOAD_EOF"

    /** 这两个目录是工具自己的实现细节（执行临时文件、下载回来的产物），不该打包上传 */
    private val EXCLUDED_DIRS = setOf(".dsh-run", "artifacts")

    /** 打包结果 */
    data class Packed(
        val base64: String,
        val fileCount: Int,
        /** 压缩前的总字节数，给用户一个直观的量级 */
        val rawBytes: Long,
    )

    sealed interface Outcome {
        /** 打包成功 */
        data class Ok(val packed: Packed) : Outcome

        /** 没有可打包的文件 —— 不是错误，命令照原样执行 */
        data object Nothing : Outcome

        /** 超出内联长度上限：必须在本地拦住，否则云端会回一个没有任何上下文的 422 */
        data class TooLarge(
            val fileCount: Int,
            val rawBytes: Long,
            val needChars: Int,
        ) : Outcome

        data class Failed(val message: String) : Outcome
    }

    /**
     * 打包 [root] 下的文件，压缩成 zip 再 base64。
     *
     * [files] 为空时递归整个目录；给了就只打这些相对路径 ——
     * 文件太多、太大时，这是"只带必要的东西"的出口。
     */
    fun pack(root: File, files: List<String>): Outcome {
        // 目录不存在通常意味着"还没写过任何文件"，命令本身可能是自足的
        // （例如 `npm install`），所以这里不当作错误，让命令照原样跑
        if (!root.isDirectory) return Outcome.Nothing

        val targets: List<Pair<String, File>> = if (files.isEmpty()) {
            collect(root)
        } else {
            files.mapNotNull { raw ->
                val rel = raw.trim().trimStart('/').removePrefix("./")
                if (rel.isEmpty()) return@mapNotNull null
                val file = File(root, rel)
                // 只允许工作目录内的文件：路径参数来自模型，不能让它把本地任意文件打包上传
                if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    return@mapNotNull null
                }
                if (!file.isFile) return@mapNotNull null
                rel to file
            }
        }
        // 指定了文件却一个都没匹配上：这是模型用错了参数，必须说清楚，
        // 否则命令会在"文件不存在"上莫名其妙地失败
        if (files.isNotEmpty() && targets.isEmpty()) {
            return Outcome.Failed(
                "指定的文件在工作目录里都不存在：${files.joinToString("、")}。" +
                    "先用 list_files 看看目录里有什么；留空 files 则自动打包整个工作目录。"
            )
        }
        if (targets.isEmpty()) return Outcome.Nothing

        val rawBytes = targets.sumOf { it.second.length() }
        val zipped = runCatching {
            val buffer = ByteArrayOutputStream()
            ZipOutputStream(buffer).use { zip ->
                targets.forEach { (rel, file) ->
                    zip.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            buffer.toByteArray()
        }.getOrElse { e ->
            return Outcome.Failed("打包工作目录失败：${e.message ?: e::class.simpleName}")
        }

        // 折成 76 列：既好读，也避免超长单行带来的各种意外
        val base64 = Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(zipped)
        if (base64.length > MAX_BASE64_CHARS) {
            return Outcome.TooLarge(targets.size, rawBytes, base64.length)
        }
        return Outcome.Ok(Packed(base64, targets.size, rawBytes))
    }

    /**
     * 把载荷与命令拼成一段自解包的 shell 脚本。
     *
     * 解包失败时**明确报错并退出** —— 否则命令会在"文件不存在"上莫名其妙地失败，
     * 排查方向又跑偏（这正是这一版要消灭的那类问题）。
     */
    fun wrap(command: String, packed: Packed): String = buildString {
        append("mkdir -p /tmp/dsh-payload && base64 -d > /tmp/dsh-payload/p.zip <<'")
        append(MARKER)
        append("'\n")
        append(packed.base64)
        if (!packed.base64.endsWith("\n")) append('\n')
        append(MARKER)
        append('\n')
        append("unzip -q -o /tmp/dsh-payload/p.zip -d . || { echo '[dsh] 工作目录打包解压失败' >&2; exit 1; }\n")
        append("rm -rf /tmp/dsh-payload\n")
        append(command)
    }

    private fun collect(dir: File, prefix: String = ""): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        dir.listFiles()?.sortedBy { it.name }?.forEach { item ->
            val rel = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
            when {
                item.isDirectory && item.name in EXCLUDED_DIRS -> Unit
                item.isDirectory -> out += collect(item, rel)
                item.isFile -> out += rel to item
            }
        }
        return out
    }
}
