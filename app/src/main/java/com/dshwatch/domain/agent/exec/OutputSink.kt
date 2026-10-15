package com.dshwatch.domain.agent.exec

/**
 * 带上限的输出缓冲：**保头 + 保尾，丢中间**。
 *
 * 两个不能省的约束：
 *
 * 1. **超过上限后必须继续消费**。子进程写满管道缓冲区就会阻塞，
 *    我们一停止读取，脚本就卡死在某次 `println` 上 —— 现象是"脚本没反应"，
 *    实际是我们在堵它。所以永远读完，只是把中段丢掉。
 *
 * 2. **尾巴比脑袋重要**。脚本打了 2000 行日志，最后三行才是 `Traceback`。
 *    只截头等于把最有用的信息扔掉。所以头尾各留一半。
 *
 * 实现上分两个缓冲区，内存占用恒定 —— 输出 100MB 也不会 OOM。
 */
internal class OutputSink(private val maxChars: Int) {

    private val headCap = (maxChars / 2).coerceAtLeast(0)
    private val tailCap = (maxChars - headCap).coerceAtLeast(0)
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var total = 0

    val truncated: Boolean get() = total > maxChars

    fun append(text: String) {
        if (text.isEmpty()) return
        total += text.length

        if (head.length >= headCap) {
            tailAppend(text)
            return
        }
        val room = headCap - head.length
        if (text.length <= room) {
            head.append(text)
            return
        }
        head.append(text, 0, room)
        tailAppend(text.substring(room))
    }

    private fun tailAppend(text: String) {
        if (tailCap <= 0) return
        tail.append(text)
        // 攒到 2 倍再一次性裁，避免每行都搬一次数组
        if (tail.length > tailCap * 2) tail.delete(0, tail.length - tailCap)
    }

    fun text(): String {
        if (maxChars <= 0) return ""
        val tailText = if (tail.length > tailCap) tail.substring(tail.length - tailCap) else tail.toString()
        if (!truncated) return head.toString() + tailText
        val dropped = total - head.length - tailText.length
        return head.toString() + "\n…（省略 $dropped 个字符）…\n" + tailText
    }

    /** 文本 + 是否截断过，供构造 [ExecOutput] 用 */
    fun finalPair(): Pair<String, Boolean> = text() to truncated
}
