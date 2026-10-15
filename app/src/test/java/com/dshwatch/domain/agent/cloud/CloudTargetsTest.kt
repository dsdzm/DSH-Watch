package com.dshwatch.domain.agent.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 云端字段归一化与错误解释。
 *
 * 这些样本**直接来自真机故障与实测响应**：
 * 用户在「目标仓库」里填了浏览器地址栏那种写法，而当时只有一句
 * `repo.contains('/')` 的校验（URL 里当然有斜杠），于是被原样拼进了 API 路径。
 * GitHub 对这类请求返回的是**通用 404**，与"workflow 不存在"的响应**一模一样**，
 * 而旧代码把所有 404 都解释成后者 —— 提示把人带到了完全错误的方向。
 *
 * 所以这里每条断言都盯两件事：**能救回来的写法要救**，**救不回来时提示要指向真因**。
 */
class CloudTargetsTest {

    // ---------------- normalizeRepo ----------------

    @Test
    fun `plain owner slash repo passes through`() {
        assertThat(CloudTargets.normalizeRepo("dsdzm/DSH-Watch")).isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `browser url is rescued`() {
        // 真机故障的原始输入
        assertThat(CloudTargets.normalizeRepo("https://github.com/dsdzm/DSH-Watch"))
            .isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `host prefixed form is rescued`() {
        assertThat(CloudTargets.normalizeRepo("github.com/dsdzm/DSH-Watch"))
            .isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `dot git suffix is rescued`() {
        assertThat(CloudTargets.normalizeRepo("https://github.com/dsdzm/DSH-Watch.git"))
            .isEqualTo("dsdzm/DSH-Watch")
        assertThat(CloudTargets.normalizeRepo("dsdzm/DSH-Watch.git"))
            .isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `ssh form is rescued`() {
        assertThat(CloudTargets.normalizeRepo("git@github.com:dsdzm/DSH-Watch.git"))
            .isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `repo sub page url is rescued by taking first two segments`() {
        assertThat(CloudTargets.normalizeRepo("https://github.com/dsdzm/DSH-Watch/tree/main"))
            .isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `trailing slash and stray quotes are trimmed`() {
        assertThat(CloudTargets.normalizeRepo("  \"dsdzm/DSH-Watch/\" ")).isEqualTo("dsdzm/DSH-Watch")
        assertThat(CloudTargets.normalizeRepo("<dsdzm/DSH-Watch>")).isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `query and fragment are dropped`() {
        assertThat(CloudTargets.normalizeRepo("https://github.com/dsdzm/DSH-Watch?tab=readme#top"))
            .isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `leading slash is tolerated`() {
        assertThat(CloudTargets.normalizeRepo("/dsdzm/DSH-Watch")).isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `repo name without owner is rejected`() {
        // 只有一段：没法拼 API 路径，必须让用户补全，而不是硬发一个必然 404 的请求
        assertThat(CloudTargets.normalizeRepo("DSH-Watch")).isNull()
    }

    @Test
    fun `blank and null are rejected`() {
        assertThat(CloudTargets.normalizeRepo(null)).isNull()
        assertThat(CloudTargets.normalizeRepo("")).isNull()
        assertThat(CloudTargets.normalizeRepo("   ")).isNull()
    }

    @Test
    fun `bare host is rejected`() {
        assertThat(CloudTargets.normalizeRepo("https://github.com")).isNull()
        assertThat(CloudTargets.normalizeRepo("api.github.com")).isNull()
    }

    @Test
    fun `extra path segments are ignored because owner slash repo is the only sensible reading`() {
        // `/tree/main`（子页面地址）和 `/x y`（复制残留）无法区分，一律只取前两段。
        // 取舍理由：能落到这里的输入，前两段几乎总是用户真正想指的仓库；
        // 而拒绝它只会让用户对着一个"格式不对"的提示重试，却不知道该删掉哪一截。
        assertThat(CloudTargets.normalizeRepo("dsdzm/DSH-Watch/x y")).isEqualTo("dsdzm/DSH-Watch")
        assertThat(CloudTargets.normalizeRepo("dsdzm/DSH-Watch/issues/12")).isEqualTo("dsdzm/DSH-Watch")
    }

    @Test
    fun `illegal characters in the first two segments are rejected`() {
        // 前两段自己就不合法时不能再猜 —— 那才是真的写错了
        assertThat(CloudTargets.normalizeRepo("dsdzm/DSH Watch")).isNull()
        assertThat(CloudTargets.normalizeRepo("ds dzm/DSH-Watch")).isNull()
    }

    @Test
    fun `owner only with trailing slash is rejected`() {
        assertThat(CloudTargets.normalizeRepo("dsdzm/")).isNull()
    }

    // ---------------- normalizeWorkflow ----------------

    @Test
    fun `plain file name passes through`() {
        assertThat(CloudTargets.normalizeWorkflow("dsh-run.yml")).isEqualTo("dsh-run.yml")
        assertThat(CloudTargets.normalizeWorkflow("dsh-build.yaml")).isEqualTo("dsh-build.yaml")
    }

    @Test
    fun `full path becomes file name`() {
        // 界面上到处显示完整路径，用户照抄进输入框是最自然的动作；
        // 而 GitHub 的 Actions 端点只认文件名 —— 传完整路径会得到一个通用 404
        assertThat(CloudTargets.normalizeWorkflow(".github/workflows/dsh-run.yml"))
            .isEqualTo("dsh-run.yml")
        assertThat(CloudTargets.normalizeWorkflow("/.github/workflows/dsh-run.yml"))
            .isEqualTo("dsh-run.yml")
    }

    @Test
    fun `blob url becomes file name`() {
        assertThat(
            CloudTargets.normalizeWorkflow(
                "https://github.com/dsdzm/DSH-Watch/blob/main/.github/workflows/dsh-run.yml"
            )
        ).isEqualTo("dsh-run.yml")
    }

    @Test
    fun `missing extension is rejected`() {
        assertThat(CloudTargets.normalizeWorkflow("dsh-run")).isNull()
        assertThat(CloudTargets.normalizeWorkflow("dsh-run.txt")).isNull()
    }

    @Test
    fun `blank and null workflow are rejected`() {
        assertThat(CloudTargets.normalizeWorkflow(null)).isNull()
        assertThat(CloudTargets.normalizeWorkflow("  ")).isNull()
        assertThat(CloudTargets.normalizeWorkflow(".github/workflows/")).isNull()
    }

    // ---------------- CloudErrors：实测响应样本 ----------------

    /** 实测：仓库名对不上、或 Actions 端点的 workflow 参数写法不对 —— 两者响应**完全同形** */
    private val genericNotFound =
        """{"message": "Not Found", "documentation_url": "https://docs.github.com/rest", "status": "404"}"""

    /** 实测：分支不存在时 GitHub 会点名分支，这是唯一能靠响应体区分的 404 */
    private val branchNotFound =
        """{"message": "Branch master not found", """ +
            """"documentation_url": "https://docs.github.com/rest/repos/contents#create-or-update-file-contents", "status": "404"}"""

    @Test
    fun `generic 404 on repo scope points at the repo field`() {
        val text = CloudErrors.explain(404, genericNotFound, CloudErrors.Scope.REPO)
        // 提示必须说清"该怎么写"，而不是只说"找不到"
        assertThat(text).contains("owner/repo")
        assertThat(text).contains("https://")
        assertThat(text).contains(".git")
    }

    @Test
    fun `generic 404 on contents scope also points at the repo field`() {
        val text = CloudErrors.explain(404, genericNotFound, CloudErrors.Scope.CONTENTS)
        assertThat(text).contains("owner/repo")
    }

    @Test
    fun `generic 404 on workflow scope points at the file name`() {
        val text = CloudErrors.explain(404, genericNotFound, CloudErrors.Scope.WORKFLOW)
        assertThat(text).contains("dsh-run.yml")
        // 这条提示要能解释"为什么完整路径不行"
        assertThat(text).contains(".github/workflows/")
    }

    @Test
    fun `branch 404 is recognised by the body`() {
        val text = CloudErrors.explain(404, branchNotFound, CloudErrors.Scope.CONTENTS)
        assertThat(text).contains("分支")
        // 不能把它误报成仓库名的问题
        assertThat(text).doesNotContain("owner/repo")
    }

    @Test
    fun `401 tells the user where to fix the token`() {
        val text = CloudErrors.explain(401, """{"message":"Bad credentials"}""", CloudErrors.Scope.REPO)
        assertThat(text).contains("令牌")
    }

    @Test
    fun `403 mentions the permissions actually needed`() {
        val text = CloudErrors.explain(403, """{"message":"Resource not accessible"}""", CloudErrors.Scope.CONTENTS)
        assertThat(text).contains("Contents")
    }

    @Test
    fun `message extraction tolerates non json bodies`() {
        assertThat(CloudErrors.messageOf("not-json-at-all")).isEmpty()
        assertThat(CloudErrors.messageOf("")).isEmpty()
        assertThat(CloudErrors.messageOf("""{"message":"Not Found"}""")).isEqualTo("Not Found")
    }

    @Test
    fun `unknown status still says something actionable`() {
        val text = CloudErrors.explain(500, "", CloudErrors.Scope.REPO)
        assertThat(text).contains("500")
    }
}
