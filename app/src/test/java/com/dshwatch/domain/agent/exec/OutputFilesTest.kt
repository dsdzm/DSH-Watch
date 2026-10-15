package com.dshwatch.domain.agent.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 大文件的有界读取。
 *
 * 这一层的存在理由是手表内存：脚本失控打印时，输出文件可能几百 MB，
 * 一次性读进来直接 OOM。所以必须能"只看头尾"地把文件读完。
 */
class OutputFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `missing file yields empty`() {
        val (text, truncated) = OutputFiles.readCapped(tmp.root.resolve("nope.txt"), 100)
        assertThat(text).isEmpty()
        assertThat(truncated).isFalse()
    }

    @Test
    fun `empty file yields empty`() {
        val f = tmp.newFile("empty.txt")
        val (text, truncated) = OutputFiles.readCapped(f, 100)
        assertThat(text).isEmpty()
        assertThat(truncated).isFalse()
    }

    @Test
    fun `small file is returned whole`() {
        val f = tmp.newFile("small.txt")
        f.writeText("hello\nworld\n")
        val (text, truncated) = OutputFiles.readCapped(f, 1000)
        assertThat(text).isEqualTo("hello\nworld\n")
        assertThat(truncated).isFalse()
    }

    @Test
    fun `file larger than the limit keeps head and tail`() {
        val f = tmp.newFile("big.txt")
        f.writeText("BEGIN\n" + "x".repeat(500_000) + "\nEND")

        val (text, truncated) = OutputFiles.readCapped(f, 300)
        assertThat(truncated).isTrue()
        assertThat(text).startsWith("BEGIN")
        assertThat(text).endsWith("END")
        // 结果长度必须仍受上限约束，否则内存保护就是假的
        assertThat(text.length).isLessThan(3_000)
    }

    @Test
    fun `tail read never produces replacement characters on chinese text`() {
        // 随机 seek 必然落在某个汉字的中间字节上，直接解码会得到乱码
        val f = tmp.newFile("cn.txt")
        f.writeText("开头\n" + "中文内容测试".repeat(100_000) + "\n结束")

        val (text, truncated) = OutputFiles.readCapped(f, 400)
        assertThat(truncated).isTrue()
        assertThat(text).contains("开头")
        assertThat(text).contains("结束")
        assertThat(text).doesNotContain("\uFFFD")
    }

    @Test
    fun `decodeAligned drops the leading continuation byte`() {
        val bytes = "中".toByteArray(Charsets.UTF_8) // 3 字节
        val misaligned = bytes.copyOfRange(1, 3)     // 从中间开始
        assertThat(OutputFiles.decodeAligned(misaligned, fromTail = true)).isEmpty()
        assertThat(OutputFiles.decodeAligned(bytes, fromTail = true)).isEqualTo("中")
    }

    @Test
    fun `zero limit reports truncation without content`() {
        val f = tmp.newFile("z.txt")
        f.writeText("abc")
        val (text, truncated) = OutputFiles.readCapped(f, 0)
        assertThat(text).isEmpty()
        assertThat(truncated).isTrue()
    }

    @Test
    fun `output file is removed after a run`() {
        // ShellRunner 跑完会删掉临时输出，避免工作目录膨胀
        val scratch = tmp.newFolder(ShellRunner.SCRATCH_DIR)
        assertThat(scratch.listFiles()!!.size).isEqualTo(0)
    }
}
