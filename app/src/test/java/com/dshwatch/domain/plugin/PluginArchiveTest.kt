package com.dshwatch.domain.plugin

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 从压缩包里取清单。
 *
 * 这里用代码手搓 tar 而不是塞一个二进制样本：样本没法读，也不知道它到底
 * 覆盖了哪个分支；手搓的每个字节都对应一条规则（长文件名、路径穿越、
 * 只有 package.json 的包……），坏了能立刻指出是哪条。
 */
class PluginArchiveTest {

    // ---------------- 样本构造 ----------------

    private data class Entry(val name: String, val data: ByteArray, val type: Char = '0')

    private val goodJson = """{"id":"dsh-plugin-arc","name":"压缩包插件","prompt":{"append":"先给结论。"}}"""

    private fun entry(name: String, text: String) = Entry(name, text.toByteArray())

    private fun tarBytes(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        entries.forEach { e ->
            out.write(header(e.name, e.data.size, e.type))
            out.write(e.data)
            out.write(ByteArray((BLOCK - e.data.size % BLOCK) % BLOCK))
        }
        out.write(ByteArray(BLOCK * 2))
        return out.toByteArray()
    }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    private fun header(name: String, size: Int, type: Char): ByteArray {
        val h = ByteArray(BLOCK)
        name.toByteArray(Charsets.UTF_8).copyInto(h, 0, 0, minOf(name.length, 100))
        octal(h, 100, 8, 420L) // 0644
        octal(h, 108, 8, 0)
        octal(h, 116, 8, 0)
        octal(h, 124, 12, size.toLong())
        octal(h, 136, 12, 0)
        h[156] = type.code.toByte()
        "ustar".forEachIndexed { i, c -> h[257 + i] = c.code.toByte() }

        // 校验和：先把字段填成空格，算完整块的和，再写成「6 位八进制 + NUL + 空格」
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        "%06o".format(sum).toByteArray().copyInto(h, 148)
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun octal(h: ByteArray, off: Int, len: Int, v: Long) {
        val s = java.lang.Long.toOctalString(v).padStart(len - 1, '0')
        s.take(len - 1).toByteArray().copyInto(h, off)
        h[off + len - 1] = 0
    }

    /** pax 扩展头：`<长度> path=<value>\n`，长度含自身 */
    private fun paxHeader(path: String): Entry {
        val body = " path=$path\n"
        var len = body.toByteArray().size + 1
        while (true) {
            val candidate = "$len$body".toByteArray()
            if (candidate.size == len) return Entry("PaxHeader", candidate, 'x')
            len = candidate.size
        }
    }

    // ---------------- 正常路径 ----------------

    @Test
    fun `npm layout is read from package slash`() {
        val pkg = gzip(
            tarBytes(
                entry("package/package.json", """{"name":"dsh-purge","version":"1.1.84"}"""),
                entry("package/dsh-plugin.json", goodJson),
                entry("package/README.md", "# 说明"),
            ),
        )
        val r = PluginArchive.parse(pkg, "purge.tgz")
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
        assertThat((r as PluginLoader.Parsed.Ok).manifest.id).isEqualTo("dsh-plugin-arc")
    }

    /** 不压缩的 .tar 也认 —— 有人用 `tar cf` 打包 */
    @Test
    fun `plain tar works too`() {
        val tar = tarBytes(entry("dsh-plugin.json", goodJson))
        assertThat(PluginArchive.parse(tar)).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
    }

    /** 把 .json 改成 .tgz 是常见操作，按内容判断而不是按扩展名 */
    @Test
    fun `a renamed json file still loads`() {
        val r = PluginArchive.parse(goodJson.toByteArray(), "plugin.tgz")
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
    }

    /** 层级最浅的那份优先：作者常在 examples/ 下再放几份 */
    @Test
    fun `the shallowest manifest wins`() {
        val pkg = gzip(
            tarBytes(
                entry("package/examples/demo/dsh-plugin.json", goodJson.replace("dsh-plugin-arc", "dsh-plugin-demo")),
                entry("package/dsh-plugin.json", goodJson),
            ),
        )
        val r = PluginArchive.parse(pkg) as PluginLoader.Parsed.Ok
        assertThat(r.manifest.id).isEqualTo("dsh-plugin-arc")
    }

    /** 路径超 100 字符时 tar 会用 pax 头，不认 pax 就会把清单漏掉 */
    @Test
    fun `pax long paths are followed`() {
        val longPath = "package/" + "very-long-directory-name/".repeat(5) + "dsh-plugin.json"
        val pkg = gzip(
            tarBytes(
                paxHeader(longPath),
                // 真实 tar 会把这个条目的名字截断成前 100 字节
                Entry(longPath.take(99), goodJson.toByteArray()),
            ),
        )
        val r = PluginArchive.parse(pkg)
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Ok::class.java)
    }

    // ---------------- 该拒的 ----------------

    /**
     * 官方 Cordis 插件：有 package.json、有 JS，但没有我们的清单。
     *
     * 必须明确说「这是 Node 插件，装不了」，而不是把 package.json 的 name
     * 当成插件导进去 —— 那样用户会以为装成功了，然后发现什么都没变。
     */
    @Test
    fun `a cordis package is rejected with a reason`() {
        val pkg = gzip(
            tarBytes(
                entry("package/package.json", """{"name":"@dsh/purge","version":"1.1.84"}"""),
                entry("package/index.js", "module.exports = { apply(ctx) {} }"),
            ),
        )
        val r = PluginArchive.parse(pkg, "purge.tgz")
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        val msg = (r as PluginLoader.Parsed.Err).message
        assertThat(msg).contains("Cordis")
        assertThat(msg).contains("@dsh/purge")
        assertThat(msg).contains(PluginArchive.MANIFEST_NAME)
    }

    @Test
    fun `an empty archive says what it looked for`() {
        val r = PluginArchive.parse(gzip(tarBytes(entry("package/README.md", "# hi"))))
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((r as PluginLoader.Parsed.Err).message).contains(PluginArchive.MANIFEST_NAME)
    }

    /** 路径穿越的条目一律不认 —— 一个正常的打包工具不会产出这种名字 */
    @Test
    fun `traversal paths are ignored`() {
        val pkg = gzip(tarBytes(entry("../../dsh-plugin.json", goodJson)))
        assertThat(PluginArchive.parse(pkg)).isInstanceOf(PluginLoader.Parsed.Err::class.java)
    }

    @Test
    fun `broken gzip is reported as broken`() {
        val pkg = gzip(tarBytes(entry("dsh-plugin.json", goodJson)))
        val cut = pkg.copyOfRange(0, pkg.size / 2)
        val r = PluginArchive.parse(cut)
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
    }

    @Test
    fun `an oversized archive is refused before it is read`() {
        val big = ByteArray(PluginArchive.MAX_BYTES + 1)
        big[0] = 0x1f
        big[1] = 0x8b.toByte()
        val r = PluginArchive.parse(big)
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        assertThat((r as PluginLoader.Parsed.Err).message).contains("上限")
    }

    /** 包里的清单自己超长时，提示要指向清单而不是「没找到」 */
    @Test
    fun `an oversized manifest is not confused with a missing one`() {
        val fat = goodJson + " ".repeat(70 * 1024)
        val pkg = gzip(tarBytes(entry("dsh-plugin.json", fat)))
        val r = PluginArchive.parse(pkg)
        assertThat(r).isInstanceOf(PluginLoader.Parsed.Err::class.java)
        val msg = (r as PluginLoader.Parsed.Err).message
        assertThat(msg).contains("KB")
        assertThat(msg).doesNotContain("没找到")
    }

    /** 清单本身不合法（比如 id 写错）时，错误要透传出来，不能被包层掩盖 */
    @Test
    fun `validation errors inside the archive are passed through`() {
        val bad = """{"id":"purge","name":"x","prompt":{"append":"a"}}"""
        val pkg = gzip(tarBytes(entry("dsh-plugin.json", bad)))
        val r = PluginArchive.parse(pkg) as PluginLoader.Parsed.Err
        assertThat(r.message).contains("dsh-plugin-")
    }

    private companion object {
        const val BLOCK = 512
    }
}
