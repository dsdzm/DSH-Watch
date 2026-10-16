package com.dshwatch.domain.agent.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * 工作目录打包。
 *
 * 起因是真机上的这一句报错：
 *
 *     python: can't open file '/home/runner/work/dsh-/dsh-/main.py'
 *
 * 云端是在一个**空仓库**里执行命令的，手表上 write_file 写出来的文件不会自己过去。
 * 结果是模型自己悟出"把源码 heredoc 内联进命令"，还把它写成"值得记住的坑"提醒用户。
 * 这不该由模型记 —— 工具知道目录里有什么，就该自己带上。
 *
 * 这里的每条断言都对着"用户能不能一次跑通"，以及"失败时说得清不清楚"。
 */
class CloudPayloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun unpack(base64: String): Map<String, String> {
        val bytes = Base64.getMimeDecoder().decode(base64)
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    // ---------------- 打包 ----------------

    @Test
    fun `packs files with their relative paths`() {
        val root = tmp.newFolder("ws")
        root.resolve("main.py").writeText("print('hi')")
        root.resolve("src").mkdirs()
        root.resolve("src/util.py").writeText("x = 1")

        val outcome = CloudPayload.pack(root, emptyList())

        assertThat(outcome).isInstanceOf(CloudPayload.Outcome.Ok::class.java)
        val files = unpack((outcome as CloudPayload.Outcome.Ok).packed.base64)
        assertThat(files.keys).containsExactly("main.py", "src/util.py")
        assertThat(files["main.py"]).isEqualTo("print('hi')")
    }

    @Test
    fun `excludes the tool's own working dirs`() {
        // .dsh-run 是执行临时文件、artifacts 是云端产物 —— 都不该打包上传
        val root = tmp.newFolder("ws")
        root.resolve("app.py").writeText("ok")
        root.resolve(".dsh-run").mkdirs()
        root.resolve(".dsh-run/run-1.sh").writeText("noise")
        root.resolve("artifacts").mkdirs()
        root.resolve("artifacts/old.zip").writeText("noise")

        val outcome = CloudPayload.pack(root, emptyList()) as CloudPayload.Outcome.Ok

        assertThat(unpack(outcome.packed.base64).keys).containsExactly("app.py")
    }

    @Test
    fun `empty directory is not an error`() {
        // 命令可能是自足的（例如 npm install），不该因为"没文件"就失败
        val root = tmp.newFolder("empty")
        assertThat(CloudPayload.pack(root, emptyList())).isEqualTo(CloudPayload.Outcome.Nothing)
    }

    @Test
    fun `missing directory is not an error either`() {
        val missing = tmp.newFolder("ws").resolve("nope")
        assertThat(CloudPayload.pack(missing, emptyList())).isEqualTo(CloudPayload.Outcome.Nothing)
    }

    @Test
    fun `explicit file list packs only those files`() {
        val root = tmp.newFolder("ws")
        root.resolve("a.py").writeText("A")
        root.resolve("b.py").writeText("B")
        root.resolve("huge.bin").writeText("C")

        val outcome = CloudPayload.pack(root, listOf("a.py", "b.py")) as CloudPayload.Outcome.Ok

        assertThat(unpack(outcome.packed.base64).keys).containsExactly("a.py", "b.py")
        assertThat(outcome.packed.fileCount).isEqualTo(2)
    }

    @Test
    fun `explicit list with nothing matching is a clear error`() {
        // 模型把文件名写错时必须当场说清，否则命令会在"文件不存在"上莫名其妙地失败
        val root = tmp.newFolder("ws")
        root.resolve("main.py").writeText("x")

        val outcome = CloudPayload.pack(root, listOf("app.py"))

        assertThat(outcome).isInstanceOf(CloudPayload.Outcome.Failed::class.java)
        assertThat((outcome as CloudPayload.Outcome.Failed).message).contains("app.py")
    }

    @Test
    fun `paths outside the workspace are rejected`() {
        // 路径参数来自模型，不能让它把工作目录之外的文件打包上传
        val root = tmp.newFolder("ws")
        val outside = tmp.newFolder("outside")
        outside.resolve("secret.txt").writeText("nope")

        val outcome = CloudPayload.pack(root, listOf("../outside/secret.txt"))

        assertThat(outcome).isInstanceOf(CloudPayload.Outcome.Failed::class.java)
    }

    @Test
    fun `oversized payload is caught locally instead of becoming a 422`() {
        // 实测：command 超过约 30K 字符时 GitHub 直接回 "inputs are too large"。
        // 本地拦住才能给出一条能照做的提示，而不是把一个 422 丢给用户
        val root = tmp.newFolder("ws")
        // 随机内容不可压缩，确保真的超限
        val random = java.util.Random(42)
        val blob = ByteArray(60_000).also { random.nextBytes(it) }
        root.resolve("big.bin").writeBytes(blob)

        val outcome = CloudPayload.pack(root, emptyList())

        assertThat(outcome).isInstanceOf(CloudPayload.Outcome.TooLarge::class.java)
        val tooLarge = outcome as CloudPayload.Outcome.TooLarge
        assertThat(tooLarge.fileCount).isEqualTo(1)
        assertThat(tooLarge.needChars).isGreaterThan(CloudPayload.MAX_BASE64_CHARS)
    }

    @Test
    fun `compressible text stays well under the limit`() {
        // 真实场景（源码）压缩率很高：一百多 KB 的纯文本应当能顺利装下
        val root = tmp.newFolder("ws")
        root.resolve("main.py").writeText(("print('hello world')\n").repeat(6_000))

        val outcome = CloudPayload.pack(root, emptyList())

        assertThat(outcome).isInstanceOf(CloudPayload.Outcome.Ok::class.java)
        val packed = (outcome as CloudPayload.Outcome.Ok).packed
        assertThat(packed.rawBytes).isGreaterThan(100_000)
        assertThat(packed.base64.length).isLessThan(CloudPayload.MAX_BASE64_CHARS)
    }

    // ---------------- 拼命令 ----------------

    @Test
    fun `wrap puts the payload before the command and self-extracts`() {
        val packed = CloudPayload.Packed("QUJD\nREVG\n", 1, 6)

        val script = CloudPayload.wrap("python main.py", packed)

        // 载荷在标记之间
        assertThat(script).contains("<<'DSH_PAYLOAD_EOF'")
        assertThat(script).contains("QUJD\nREVG\n")
        // 解包失败必须明确报错退出，否则命令会在"文件不存在"上莫名其妙地失败
        assertThat(script).contains("解压失败")
        assertThat(script).contains("exit 1")
        // 用户命令在最后（前面全部是准备步骤）
        assertThat(script.trimEnd().endsWith("python main.py")).isTrue()
    }

    @Test
    fun `marker cannot appear inside base64 content`() {
        // base64 字符集是 A-Za-z0-9+/=，不含下划线 —— heredoc 的结束标记绝不会提前出现。
        // 这是选择 "DSH_PAYLOAD_EOF" 而不是 "EOF" 的原因（后者在极端内容下有可能撞上）
        val packed = CloudPayload.Packed("AAAA\n", 1, 3)
        val script = CloudPayload.wrap("echo done", packed)

        val body = script.substringAfter("<<'DSH_PAYLOAD_EOF'\n").substringBefore("DSH_PAYLOAD_EOF\n")
        assertThat(body).doesNotContain("_")
    }
}
