package com.dshwatch.domain.agent.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 云端字段的归一化。
 *
 * 起因是一次真机故障：用户在「目标仓库」里填了 `https://github.com/dsdzm/DSH-Watch`
 * （浏览器地址栏里复制出来的那种写法），而当时的校验只有一句 `repo.contains('/')` ——
 * URL 里当然有斜杠，于是被当成合法的 `owner/repo` 直接拼进了 API 路径：
 *
 *     GET https://api.github.com/repos/https://github.com/dsdzm/DSH-Watch/...
 *
 * GitHub 对这种请求返回的是**通用 404**（`{"message":"Not Found"}`），
 * 而当时所有 404 都被解释成「workflow 文件名不对或模板没提交」——
 * 提示指向了完全错误的方向，用户按提示去重装模板，装一次错一次。
 *
 * 所以这里做两件事：
 *  - **尽量把用户会写的各种形式救回来**（URL、带 .git、带尾斜杠、SSH 形式、子页面地址）；
 *  - **救不回来的明确返回 null**，由调用方给出"该怎么写"的提示，而不是硬拼进 URL。
 *
 * 为什么不干脆只做严格校验、一律要求手写 `owner/repo`：从地址栏复制是最自然的动作，
 * 一个只会拒绝的输入框，用户要试两次才知道该怎么写；而"照着他写的也能懂"是更好的体验。
 */
object CloudTargets {

    /** 允许出现在 owner / repo / 文件名里的字符 */
    private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val GITHUB_HOST = Regex("^(?:www\\.|api\\.)?github\\.com/", RegexOption.IGNORE_CASE)
    private val SSH_PREFIX = Regex("^git@github\\.com:", RegexOption.IGNORE_CASE)

    /** 从各种写法里取出 `owner/repo`；认不出来返回 null */
    fun normalizeRepo(raw: String?): String? {
        var s = cleanup(raw) ?: return null

        s = SSH_PREFIX.replace(s, "")
        s = SCHEME.replace(s, "")
        s = GITHUB_HOST.replace(s, "")
        s = s.substringBefore('?').substringBefore('#')
        s = s.trimEnd('/')
        if (s.endsWith(".git", ignoreCase = true)) {
            s = s.dropLast(4).trimEnd('/')
        }

        // 过滤空段：`/owner/repo`、`owner//repo` 都能救回来
        val parts = s.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        // 只取前两段，多余的忽略：`owner/repo/tree/main`（子页面地址）与 `owner/repo/残留内容`
        // 无法区分，而 `owner/repo` 是唯一有意义的解释 —— 前两段自己就必须合法，不合法才算写错
        val owner = parts[0]
        val name = parts[1]
        if (!SEGMENT.matches(owner) || !SEGMENT.matches(name)) return null
        return "$owner/$name"
    }

    /**
     * 取出 workflow 的文件名。
     *
     * `dsh-run.yml` / `.github/workflows/dsh-run.yml` / 指向该文件的完整 URL 都接受 ——
     * 界面上到处展示的是完整路径，要求用户反直觉地"只写文件名"是不合理的，
     * 而 GitHub 的 Actions 端点偏偏只认文件名（传完整路径会得到一个通用 404）。
     */
    fun normalizeWorkflow(raw: String?): String? {
        var s = cleanup(raw) ?: return null

        s = SSH_PREFIX.replace(s, "")
        s = SCHEME.replace(s, "")
        s = GITHUB_HOST.replace(s, "")
        s = s.substringBefore('?').substringBefore('#')
        s = s.trimEnd('/')

        val slash = s.lastIndexOf('/')
        val name = (if (slash >= 0) s.substring(slash + 1) else s).trim()
        if (name.isEmpty() || !SEGMENT.matches(name)) return null

        val lower = name.lowercase()
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) return null
        return name
    }

    /** 去掉包裹的引号/尖括号与首尾空白（从聊天、Markdown 里复制过来经常带） */
    private fun cleanup(raw: String?): String? {
        val s = raw?.trim().orEmpty().trim('"', '\'', '`', '<', '>', ' ', '\t', '\n', '\r')
        return s.ifEmpty { null }
    }
}

/**
 * 把 GitHub 的错误响应翻译成「下一步该做什么」。
 *
 * 抽成纯函数是为了能单测 —— 用户看到的每一句话都在这里，
 * 而上一版正是因为在 404 上给了**似是而非的解释**才把排查带偏。
 */
internal object CloudErrors {

    /** 请求发生在哪类端点上。同一个 404 在不同端点上原因完全不同 */
    enum class Scope { REPO, CONTENTS, WORKFLOW, RUN }

    private val json = Json { isLenient = true }

    fun explain(code: Int, body: String, scope: Scope): String = when (code) {
        401 -> "令牌无效或已过期，请到设置里重新填写 GitHub 令牌。"
        403 -> "令牌权限不足，或触发了速率限制。触发/取消运行需要 Actions 权限，" +
            "安装模板需要 Contents 写权限。"
        404 -> explain404(body, scope)
        409 -> "状态冲突（该运行可能已经结束）。"
        422 -> "请求被拒绝（通常是分支名不存在，或 workflow 的 inputs 与模板不匹配）。"
        else -> "GitHub 返回 HTTP $code。"
    }

    /**
     * 404 的三种成因，只有一种能靠响应体区分：
     *
     * | 成因 | 响应体 |
     * | --- | --- |
     * | 分支不存在 | `{"message":"Branch master not found"}` —— 会点名分支 |
     * | 仓库名对不上 / 令牌看不到该仓库 | `{"message":"Not Found"}` |
     * | Actions 端点的 workflow 参数写法不对 | `{"message":"Not Found"}` —— 与上一条**完全同形** |
     *
     * 后两条无法从响应体区分，所以由调用方先探仓库、再探 workflow（见 `GitHubApi.probeRepo`），
     * 到这里时 [scope] 已经能锁定是哪一个。
     */
    private fun explain404(body: String, scope: Scope): String {
        val message = messageOf(body)
        if (message.contains("Branch", ignoreCase = true) &&
            message.contains("not found", ignoreCase = true)
        ) {
            return "分支不存在。GitHub 上要有「分支」字段里写的那个分支（新建仓库默认是 main）。"
        }
        return when (scope) {
            Scope.REPO, Scope.CONTENTS ->
                "仓库访问不到。检查「目标仓库」是否写成 owner/repo（例如 dsdzm/DSH-Watch）—— " +
                    "不要带 https:// 、github.com 前缀或 .git 后缀；也确认令牌有权访问这个仓库。"
            Scope.WORKFLOW ->
                "这个仓库里找不到该 workflow。文件名要写成 dsh-run.yml（不要写 .github/workflows/ 完整路径）；" +
                    "如果是刚提交上去的，等十几秒再试 —— GitHub 需要一点时间索引新 workflow。"
            Scope.RUN ->
                "找不到这次运行。run id 可能不对，或该运行的记录已被清理。"
        }
    }

    /** 取 GitHub 响应里的 message 字段；拿不到就返回空串 */
    fun messageOf(body: String): String = runCatching {
        json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")
}
