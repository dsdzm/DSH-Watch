package com.dshwatch.net

import okhttp3.Request

/**
 * 自定义请求头的校验与应用。
 *
 * 让模型能传 `headers` 是为了解决一类很具体的失败：目标站点要 `Referer`、
 * `Authorization`、`X-Requested-With` 或特定 `User-Agent` 才返回内容，
 * 而写死的默认头只能碰运气。但「让模型自由写请求头」同时打开了几个口子，
 * 所以这里做了白名单式的清洗：
 *
 *  - **不允许改 `Host`**：改了它请求会被发到别的主机（且 TLS 证书对不上）；
 *  - **不允许 `Content-Length` / `Transfer-Encoding` / `Connection`**：
 *    这些由 OkHttp 自己算，手写只会制造协议错误；
 *  - **不允许换行**：`\r\n` 是请求头注入的经典手法，必须拦住；
 *  - 名称只允许 RFC 7230 的 token 字符，值上限 4KB，最多 20 个。
 *
 * 被拒绝的头**不会让整次请求失败** —— 其余合法的头照常生效，
 * 结果里会说明哪些被忽略了，模型据此可以改正。
 */
object CustomHeaders {

    private val VALID_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")

    /** 由传输层自己管理的头，手写会破坏协议或改变目标主机 */
    private val FORBIDDEN = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "upgrade",
        "te",
        "trailer",
    )

    const val MAX_COUNT = 20
    const val MAX_VALUE_CHARS = 4_096

    data class Sanitized(
        val values: Map<String, String>,
        val rejected: List<String>,
    ) {
        val isEmpty: Boolean get() = values.isEmpty()
    }

    fun sanitize(raw: Map<String, String>): Sanitized {
        val ok = LinkedHashMap<String, String>()
        val bad = ArrayList<String>()

        for ((rawName, rawValue) in raw) {
            val name = rawName.trim()
            val value = rawValue.trim()
            when {
                name.isEmpty() -> bad += "(空名称)"
                !VALID_NAME.matches(name) -> bad += name
                name.lowercase() in FORBIDDEN -> bad += name
                value.length > MAX_VALUE_CHARS -> bad += name
                value.contains('\r') || value.contains('\n') -> bad += name
                ok.size >= MAX_COUNT -> bad += name
                else -> ok[name] = value
            }
        }
        return Sanitized(ok, bad)
    }

    /**
     * 应用自定义头。同名的默认头会被**替换**而不是叠加 ——
     * OkHttp 的 `header()` 是追加语义，直接调会出现两个 `User-Agent`，
     * 服务端取哪个不确定。
     */
    fun apply(builder: Request.Builder, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            builder.removeHeader(name)
            builder.header(name, value)
        }
    }

    /** 给模型看的忽略说明；没有拒绝项时返回空串 */
    fun rejectedNote(rejected: List<String>): String =
        if (rejected.isEmpty()) "" else
            "（已忽略不合法的请求头：" + rejected.distinct().joinToString("、") +
                "。Host、Content-Length、Connection、Transfer-Encoding 不允许自定义，" +
                "头名称只能用字母数字与 -_ 等符号，值不能包含换行）"
}
