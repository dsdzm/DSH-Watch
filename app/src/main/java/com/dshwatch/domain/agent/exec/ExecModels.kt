package com.dshwatch.domain.agent.exec

/**
 * 一次脚本执行的完整结果。
 *
 * 设计取向：**宁可多告诉模型一句实话，也不要让它以为成功了**。
 * 所以超时与否、输出是否被截断、以及"可能还在后台跑"这类信息
 * 都是独立字段，工具层必须把这些如实写进返回文本里。
 */
data class ExecOutput(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    /** stdout/stderr 是否因为超过上限被截断 */
    val truncated: Boolean = false,
    /** 是否因超时被中止（或被放弃等待） */
    val timedOut: Boolean = false,
    val elapsedMs: Long = 0L,
    /**
     * 超时之后**脚本是否仍在后台跑**。
     *
     * 只有 Python 会出现这种情况：解释器与 App 同进程，Kotlin 侧没有安全手段终止它。
     * 它决定执行闸门要不要继续占着 —— 让一个失控脚本和新的脚本叠在一起跑，
     * 是手表上唯一能让用户开不了机的做法。
     */
    val stillRunning: Boolean = false,
    /**
     * 需要额外说明的话。
     * 例如 Python 超时后解释器仍在跑（进程内无法安全 kill），
     * 这里会写「脚本可能仍在后台运行」，工具层原样透传。
     */
    val note: String = "",
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    companion object {
        fun failure(message: String, exitCode: Int = 1) =
            ExecOutput(exitCode = exitCode, stdout = "", stderr = message)
    }
}

/** 单次执行的资源上限 */
data class ExecLimits(
    val timeoutMs: Long,
    val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    companion object {
        /** 单条输出流保留的字符数。手表屏幕小，模型上下文更贵，20k 字符约 6k token 已经很多了 */
        const val DEFAULT_MAX_CHARS = 20_000
    }
}

/**
 * 超长输出保留「头 + 尾」。
 *
 * 为什么不是只留头：报错信息几乎总是在最后 —— 一个脚本打印了两千行日志，
 * 最后三行才是 `Traceback`。只截头等于把最有用的部分丢掉。
 * 保留头是为了看"它到底在干什么"，保留尾是为了看"它怎么挂的"。
 */
fun truncateHeadTail(text: String, max: Int): Pair<String, Boolean> {
    if (max <= 0) return "" to text.isNotEmpty()
    if (text.length <= max) return text to false

    val marker = "\n…（省略 ${text.length - max} 个字符）…\n"
    val budget = (max - marker.length).coerceAtLeast(0)
    val headLen = safeCutIndex(text, budget / 2)
    val tailStart = safeCutIndex(text, text.length - (budget - headLen))
    return (text.take(headLen) + marker + text.substring(tailStart)) to true
}

/**
 * 把切点挪到不会切开代理对的位置。
 *
 * 手表上中文很常见，但真正会被切坏的是 emoji 和部分生僻字（占两个 char）。
 * 切一半的代理对是无效字符串，写进日志和 JSON 都会出问题。
 */
internal fun safeCutIndex(text: String, index: Int): Int = when {
    index <= 0 -> 0
    index >= text.length -> text.length
    Character.isLowSurrogate(text[index]) -> index - 1
    else -> index
}
