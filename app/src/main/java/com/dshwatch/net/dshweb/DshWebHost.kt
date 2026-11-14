package com.dshwatch.net.dshweb

import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/**
 * 一台 `dsh web` 主机：地址 + 会话 cookie。
 *
 * ## 为什么不是「地址 + 配对码」
 *
 * 之前这里实现的是 `POST /api/v1/devices/pair`：填 IP、填六位配对码、换设备令牌。
 * 那套接口在 `dsh web` 上**不存在** —— 它是照着「远程访问插件」想象出来的，
 * 所以无论网络多好都只会得到 404 或连接被拒，用户看到的就是「一直连不上」。
 *
 * dshgo 的真实做法是另一条路：
 *
 *  1. `dsh web` 启动后在控制台打一行 `dsh web: http://127.0.0.1:3080/?token=xxxx`；
 *  2. 客户端 `GET /?token=xxxx`，服务端回 **302/303** 并在 `Set-Cookie` 里下发
 *     `dsh-auth-<base64url(sha256(authority))>=v1.<payload>.<signature>`；
 *  3. 之后所有请求带上这枚 cookie，就代表「这台机器的主人」。
 *
 * 所以这里的第一件事不是「配对」，而是**解析那行启动地址并换 cookie**。
 *
 * ## authority 为什么必须算准
 *
 * cookie 名里带着 `sha256(authority)`，服务端按它区分不同的 `host:port`。
 * 名字算错，cookie 就带不上，服务端一律 401 —— 而 401 看起来和「密码错了」
 * 一模一样，极难排查。规则来自服务端：默认端口不进 authority，IPv6 加方括号。
 */
data class DshWebHost(
    val baseUrl: String,
    val authority: String,
    val cookie: DshCookie? = null,
    /**
     * 请求时显式带上的 `Host` 头。
     *
     * 走 SSH 隧道时两者必须分开：连接落在 `127.0.0.1:<本地端口>`，
     * 但服务端是按 `127.0.0.1:<远端端口>` 来算 cookie 名、并按它做 Origin 围栏的。
     * 如果只按 URL 走默认 Host，服务端会认为这是一台「陌生主机」，
     * 于是每次请求都 401 —— 而 401 看起来和「密码错了」一模一样。
     */
    val hostHeader: String = "",
) {
    val paired: Boolean get() = cookie != null

    fun withCookie(value: DshCookie): DshWebHost = copy(cookie = value)
}

/**
 * 一次登录要用的两样东西。
 *
 * 令牌单独放在这里而不是塞进 [DshWebHost]：host 会被持久化，
 * 而启动令牌是一次性的敏感值，混在一起迟早会被写进日志或备份。
 */
data class DshWebLaunch(val host: DshWebHost, val token: String)

/** 一枚已登录的会话 cookie。值是 `v1.<payload>.<signature>`，客户端不解析 */
data class DshCookie(
    val name: String,
    val value: String,
    /** cookie 绑定的 `host:port`；请求必须带同一个 Host */
    val authority: String,
    val expiresAt: Long = 0L,
) {
    /** 直接作为 `Cookie` 头的值 */
    val header: String get() = "$name=$value"

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt > 0 && now >= expiresAt
}

object DshWeb {

    /** RPC 前缀。`POST /api/<endpoint>`，endpoint 形如 `session/list` */
    const val API_PREFIX = "/api"

    /** 多路复用的 WebSocket，一条连接承载所有逻辑流 */
    const val MUX_PATH = "/api/remote.mux"

    /** `$events` 流：连接代际的就绪握手走它 */
    const val HOST_EVENTS = "\$events"

    /**
     * 从 `dsh web` 打印的那一行里取出地址和启动令牌。
     *
     * 控制台原文是 `dsh web: http://127.0.0.1:3080/?token=abc`，用户也可能只贴
     * `http://1.2.3.4:3080/?token=abc`，或者把 `token` 单独贴在地址后面。
     * 三种都要认 —— 手表上让人手打 43 位令牌是不现实的。
     *
     * 返回 null 表示「这段话里没有可用的启动地址」，让界面提示去电脑上复制完整那行。
     */
    fun parseLaunchUrl(text: String): DshWebLaunch? {
        val match = Regex("""https?://\S+""").find(text.trim()) ?: return null
        val raw = match.value.trimEnd(',', '。', ')', '」', '"', '\'')
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val token = queryParam(raw, "token")
        // 有 token 才是一次有效的启动地址：没有 token 的地址无法换 cookie
        if (token.isNullOrBlank()) return null
        val host = build(uri) ?: return null
        return DshWebLaunch(host, token)
    }

    /** 只要地址、不要令牌（用于重新登录时保留 base） */
    fun parseBase(text: String): DshWebHost? {
        val match = Regex("""https?://\S+""").find(text.trim()) ?: return null
        val uri = runCatching { URI(match.value) }.getOrNull() ?: return null
        return build(uri)
    }

    /**
     * 从已保存的「地址 + cookie 头」还原一台主机。
     *
     * 这是冷启动的常规路径：进程被杀、手表重启之后，只剩下这两样东西。
     * cookie 名必须按当前 authority 重新算 —— 直接存名字会让用户改了端口之后
     * 带着一枚名字对不上的 cookie，然后收到一个查不出原因的 401。
     */
    fun hostFrom(baseUrl: String, cookieHeader: String?): DshWebHost? {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
        val host = build(uri) ?: return null
        val header = cookieHeader?.trim().orEmpty()
        if (header.isEmpty()) return host
        val eq = header.indexOf('=')
        if (eq <= 0) return host
        return host.withCookie(
            DshCookie(header.substring(0, eq).trim(), header.substring(eq + 1).trim(), host.authority)
        )
    }

    /** 登录用的请求地址：`GET /?token=…`，且**不跟随**重定向（要从 302 里读 cookie） */
    fun loginUrl(host: DshWebHost, token: String): String =
        host.baseUrl.trimEnd('/') + "/?token=" + encodeQuery(token)

    /** `POST /api/<endpoint>` */
    fun rpcUrl(host: DshWebHost, endpoint: String): String =
        host.baseUrl.trimEnd('/') + API_PREFIX + "/" + endpoint

    /** `ws(s)://…/api/remote.mux` */
    fun muxUrl(host: DshWebHost): String {
        val base = host.baseUrl.trimEnd('/')
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> return base
        }
        return ws + MUX_PATH
    }

    /**
     * cookie 名：`dsh-auth-<base64url(sha256(authority))>`。
     *
     * 无填充、URL 安全字母表 —— 与服务端 `crypto.subtle` 的默认输出一致。
     */
    fun cookieName(authority: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(authority.toByteArray(Charsets.UTF_8))
        return "dsh-auth-" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /** `host:port`，默认端口省略、IPv6 加方括号 */
    fun authorityOf(uri: URI): String {
        // Java 的 URI.getHost() 对 IPv6 会保留方括号，Dart 那边不带 —— 先统一去掉再补
        val host = bareHost(uri) ?: return ""
        val bracketed = if (host.contains(':')) "[$host]" else host
        val port = effectivePort(uri)
        val defaultPort = if (uri.scheme.equals("https", true)) 443 else 80
        return if (port == defaultPort) bracketed else "$bracketed:$port"
    }

    /**
     * 解析一条 `Set-Cookie`。
     *
     * 只取第一条 `dsh-auth-*`：登录响应理论上只下发一枚，多枚说明 Host 配置有问题，
     * 随便挑一枚会让后续请求时对时错 —— 那比直接失败更难查。
     */
    fun parseSetCookie(raw: String, authority: String, now: Long = System.currentTimeMillis()): DshCookie? {
        val parts = raw.split(';')
        val first = parts.firstOrNull()?.trim().orEmpty()
        val eq = first.indexOf('=')
        if (eq <= 0) return null
        val name = first.substring(0, eq).trim()
        val value = first.substring(eq + 1).trim()
        if (!name.startsWith("dsh-auth-") || value.isEmpty()) return null
        var expiresAt = 0L
        for (attr in parts.drop(1)) {
            val at = attr.indexOf('=')
            val key = (if (at < 0) attr else attr.substring(0, at)).trim().lowercase()
            val attrValue = if (at < 0) "" else attr.substring(at + 1).trim()
            if (key == "max-age") {
                val seconds = attrValue.toLongOrNull()
                if (seconds != null) expiresAt = now + seconds * 1000
            }
        }
        return DshCookie(name, value, authority, expiresAt)
    }

    private fun build(uri: URI): DshWebHost? {
        if (bareHost(uri) == null) return null
        val scheme = uri.scheme?.lowercase() ?: "http"
        if (scheme != "http" && scheme != "https") return null
        val port = effectivePort(uri)
        val defaultPort = if (scheme == "https") 443 else 80
        val authority = authorityOf(uri)
        // 只会保留 scheme://host[:port] 与前缀路径，丢掉 query 与 fragment：
        // token 是敏感值，不能跟着 baseUrl 到处存、到处打日志
        val path = uri.path.orEmpty().trimEnd('/')
        val host = bareHost(uri) ?: return null
        val hostPart = if (host.contains(':')) "[$host]" else host
        val base = if (port == defaultPort) "$scheme://$hostPart$path" else "$scheme://$hostPart:$port$path"
        return DshWebHost(base, authority)
    }

    private fun bareHost(uri: URI): String? =
        uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() }

    private fun effectivePort(uri: URI): Int =
        if (uri.port > 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun queryParam(raw: String, key: String): String? {
        val query = raw.substringAfter('?', "").substringBefore('#')
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            if (pair.substring(0, idx) == key) return decode(pair.substring(idx + 1))
        }
        return null
    }

    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, "UTF-8")
    }.getOrDefault(value)

    private fun encodeQuery(value: String): String = runCatching {
        java.net.URLEncoder.encode(value, "UTF-8")
    }.getOrDefault(value)
}
