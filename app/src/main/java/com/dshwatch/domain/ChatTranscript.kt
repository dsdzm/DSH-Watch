package com.dshwatch.domain

import com.dshwatch.domain.model.ChatMessage
import com.dshwatch.domain.model.MessageStatus
import com.dshwatch.domain.model.Role

/**
 * 对话的纯文本交换格式（导出 / 导入共用）。
 *
 * ## 格式
 *
 * 一轮对话是一个「块」：块头一行等号分隔线，里面按说话人分段，
 * 段与段之间用减号分隔线，每段以 `[角色]:` 起头：
 *
 * ```
 * #===========================================#
 * [USER]:
 * 雨下到第九天，涵洞的水位已经漫过了标尺的第三格。
 * #-------------------------------------------#
 * [AI]:
 * 他把手电往下压了压，光柱里全是斜着的雨……
 * ```
 *
 * ## 为什么用这种「丑」格式
 *
 * 1. **人眼能读**。导出的东西第一用途是拿给人看、贴进笔记或发给别人，
 *    不是喂给程序。分隔线足够显眼，扫一眼就知道哪句是谁说的。
 * 2. **不会和正文冲突**。Markdown 的 `#` 标题、`>` 引用、代码块里的
 *    任意符号都可能出现在正文里；`#====#` / `#----#` 这种整行重复字符
 *    几乎不可能被顺手打出来。解析时只认整行匹配，正文里的零星井号不受影响。
 * 3. **一行一个标记**。手表上粘贴进来的文本经常被输入法重新折行，
 *    靠缩进或空行分隔的结构一折就散；靠独立成行的标记则不会。
 *
 * ## 解析的容错（导入端）
 *
 * 别人手工拼的、或者从别处复制再编辑过的文本，不可能完全规范，所以：
 *  - 分隔线的等号 / 减号**长度不限**，只要整行都是同一个符号；
 *  - `[USER]:` 的冒号可省、可用全角；方括号可用全角；
 *  - 角色名认 `USER / 我 / 用户` 与 `AI / ASSISTANT / DSH / 助手`，大小写不限；
 *  - 一个块里只有一段、或没有分隔线，照样能读；
 *  - **完全没有标记**时，整段当作一条用户消息 —— 用户多半是想把一段设定或草稿
 *    导进来当上下文，直接拒绝不如让它落到会话里。
 */
object ChatTranscript {

    /** 块头：等号分隔线。长度写死成 43 个等号，只影响导出的观感，解析不依赖它 */
    const val BLOCK = "#===========================================#"

    /** 块内分段：减号分隔线 */
    const val DIVIDER = "#-------------------------------------------#"

    /** 解析出来的一个消息段。导入时一条对一个 [ChatMessage] */
    data class Turn(val role: Role, val text: String)

    private val blockRe = Regex("^#=+#$")
    private val dividerRe = Regex("^#-+#$")

    /**
     * `[USER]:` / `[AI]` / `［系统］：` 都认，冒号后直接跟正文也认。
     *
     * 角色名限 1~10 个字母或汉字：放宽到「任意长度」时，正文里
     * `[注]:` 这种写法会被误判成分段标记；超出的长度在这里直接落回正文。
     */
    private val headerRe =
        Regex("^[\\[［]\\s*([A-Za-z\\u4e00-\\u9fa5]{1,10})\\s*[\\]］]\\s*[:：]?\\s*(.*)$")

    private fun roleOf(tag: String): Role? = when (tag.trim().uppercase()) {
        "USER", "ME", "HUMAN", "我", "用户" -> Role.USER
        "AI", "ASSISTANT", "BOT", "DSH", "助手", "模型" -> Role.ASSISTANT
        "SYSTEM", "系统" -> Role.SYSTEM
        else -> null
    }

    private fun tagOf(role: Role): String = when (role) {
        Role.USER -> "USER"
        Role.ASSISTANT -> "AI"
        Role.SYSTEM -> "SYSTEM"
    }

    // ---------------- 导出 ----------------

    /**
     * 把消息序列渲染成上面的格式。
     *
     * 跳过空内容与出错 / 生成中的壳子：它们导出后只是两行空段，
     * 导回来却会变成真实的空消息。连续的同角色消息（agent 的多步回复）
     * 合并成一段，空行分隔 —— 否则一次工具调用链会切出七八个 `[AI]:`。
     */
    fun encode(messages: List<ChatMessage>): String {
        val turns = mutableListOf<Turn>()
        for (m in messages) {
            val text = m.content.trim()
            if (text.isEmpty()) continue
            if (m.status == MessageStatus.ERROR || m.status == MessageStatus.STREAMING) continue
            val last = turns.lastOrNull()
            if (last != null && last.role == m.role && m.role != Role.USER) {
                turns[turns.lastIndex] = last.copy(text = last.text + "\n\n" + text)
            } else {
                turns.add(Turn(m.role, text))
            }
        }
        return render(turns)
    }

    /** 把已经整理好的段落渲染成文本。导出预览与单元测试都走这里 */
    fun render(turns: List<Turn>): String {
        val sb = StringBuilder()
        var blockOpen = false
        var blockHasUser = false
        for (t in turns) {
            if (t.text.isBlank()) continue
            val isUser = t.role == Role.USER
            if (!blockOpen || (isUser && blockHasUser)) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(BLOCK).append('\n')
                blockOpen = true
                blockHasUser = false
            } else {
                sb.append(DIVIDER).append('\n')
            }
            sb.append('[').append(tagOf(t.role)).append("]:\n")
            sb.append(t.text.trim()).append('\n')
            if (isUser) blockHasUser = true
        }
        return sb.toString()
    }

    // ---------------- 导入 ----------------

    /**
     * 解析文本。行内空白原样保留（代码、缩进、诗歌都靠它），
     * 只去掉每段首尾的空行。
     */
    fun parse(raw: String): List<Turn> {
        if (raw.isBlank()) return emptyList()
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')
        val out = mutableListOf<Turn>()
        val buf = StringBuilder()
        var role: Role? = null
        var sawMarker = false

        fun flush() {
            val r = role ?: return
            val body = buf.toString().trim()
            if (body.isNotEmpty()) out.add(Turn(r, body))
            buf.setLength(0)
        }

        for (line in text.split('\n')) {
            val s = line.trim()
            if (blockRe.matches(s) || dividerRe.matches(s)) {
                flush()
                role = null
                continue
            }
            val header = headerRe.matchEntire(s)
            if (header != null) {
                val r = roleOf(header.groupValues[1])
                if (r != null) {
                    flush()
                    role = r
                    sawMarker = true
                    // 标记与正文同一行（`[AI]: 你好`）：正文跟在冒号后面。
                    // 手工整理的文本经常这么写，不认的话整段会丢失。
                    val inline = header.groupValues[2]
                    if (inline.isNotBlank()) buf.append(inline).append('\n')
                    continue
                }
            }
            // 标记之前的零散文字（比如手工加的标题）直接丢掉：
            // 它们没有角色归属，硬塞给谁都会让对话变形
            if (role != null) buf.append(line).append('\n')
        }
        flush()

        // 一个标记都没有：整段当成一条用户消息，让用户至少能把它导进会话里
        if (!sawMarker && out.isEmpty()) return listOf(Turn(Role.USER, text.trim()))
        return out
    }

    /**
     * 从内容里挑个标题。
     *
     * 取第一条用户消息的第一行，截断到 20 字 —— 手表列表只显示一行，
     * 长标题除了被省略号吃掉没有任何收益。
     */
    fun titleOf(turns: List<Turn>): String {
        val first = turns.firstOrNull { it.role == Role.USER && it.text.isNotBlank() } ?: turns.firstOrNull()
            ?: return "导入的对话"
        val line = first.text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (line.isBlank()) return "导入的对话"
        return if (line.length <= 20) line else line.take(20) + "…"
    }

    /** 导出对话框里的一句话摘要 */
    fun summary(turns: List<Turn>): String {
        val chars = turns.sumOf { it.text.length }
        return "${turns.count { it.role == Role.USER }} 轮 · ${turns.size} 条 · $chars 字"
    }
}
