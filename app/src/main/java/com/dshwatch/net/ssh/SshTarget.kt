package com.dshwatch.net.ssh

import kotlinx.serialization.Serializable

/**
 * 一台可以通过 SSH 上去的电脑。
 *
 * ## 为什么手表选 SSH 而不是 DSH Remote 中继
 *
 * dshgo 主推的是「扫码 + 中继 + 端到端加密」，那套要三样东西同时成立：
 * DSH 账号、电脑上装了 Remote 插件、以及一台中继服务器；协议本身是
 * X25519 配对 + Noise IK 握手 + WebRTC/relay 双通道，**手表端要重写整个栈**。
 *
 * 更硬的一条：**Wear OS 手表没有摄像头**，扫不了码；而配对票据的长度
 * 手工输入也不现实。所以那条路在手表上是走不通的，不是难，是做不了。
 *
 * SSH 则是另一种形状：电脑上开一个 sshd（macOS 自带、Linux 一条 apt），
 * 手表记一次「主机 + 用户 + 密码」，之后每次连接都是同一套流程。
 * 它解决的正是「连不上」的两个真实原因 —— 地址太长记不住、端口被 NAT 挡住。
 */
@Serializable
data class SshTarget(
    /** 电脑的局域网 IP 或域名；也允许带端口的 `host:port`，会被拆开 */
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /**
     * 远端启动命令。
     *
     * 默认 `dsh web`，与 dshgo 的默认值一致。留成可改是因为有人用 nvm/volta
     * 装了 dsh 但非交互 shell 的 PATH 里没有，那种情况下可以写绝对路径。
     */
    val command: String = "dsh web",
    /** 远端工作目录（会话的 cwd）。空表示用 ssh 登录目录 */
    val cwd: String = "",
    /**
     * 首次连接时记下的主机密钥指纹（SHA-256，Base64 无填充）。
     *
     * 空表示「还没连过」。非空时每次连接都要比对：对不上就中止，
     * 因为这正是中间人攻击的样子。这不是洁癖 —— SSH 隧道里跑的是
     * 你电脑上的 shell，指纹变了还继续连，等于把 shell 交给对方。
     */
    val fingerprint: String = "",
) {
    val configured: Boolean get() = host.isNotBlank() && user.isNotBlank()

    /** `user@host:port`，给界面显示用 */
    val label: String get() = "$user@$host:$port"

    companion object {
        /**
         * 拆分用户可能填进来的各种写法。
         *
         * 手表上没有方便的键盘，用户很可能直接粘 `ssh me@192.168.1.5 -p 2222`
         * 这样的整行，或者 `me@192.168.1.5:2222`。这些都要能认出来，
         * 否则他只能一个个字段手输，而小屏上改错一个字符的代价是重来。
         */
        fun parseHostInput(raw: String): Pair<String, Int?> {
            var text = raw.trim()
            // 整行 `ssh -p 2222 me@host` 形式
            var port: Int? = null
            val tokens = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.firstOrNull()?.equals("ssh", ignoreCase = true) == true) {
                var i = 1
                while (i < tokens.size) {
                    val t = tokens[i]
                    when {
                        t == "-p" && i + 1 < tokens.size -> {
                            port = tokens[i + 1].toIntOrNull()
                            i += 2
                        }
                        t.startsWith("-p") && t.length > 2 -> {
                            port = t.substring(2).toIntOrNull()
                            i += 1
                        }
                        t.startsWith("-") -> i += 1
                        else -> {
                            text = t
                            i += 1
                        }
                    }
                }
            }
            // `user@host:port` / `[v6]:port`
            val at = text.lastIndexOf('@')
            val hostPart = if (at >= 0) text.substring(at + 1) else text
            if (hostPart.startsWith("[")) {
                val close = hostPart.indexOf(']')
                if (close > 0) {
                    val after = hostPart.substring(close + 1)
                    if (after.startsWith(":") && port == null) port = after.drop(1).toIntOrNull()
                    return hostPart.substring(1, close) to port
                }
            }
            // 只有一个冒号才当端口；裸 IPv6 有多个冒号，不能拆
            if (hostPart.count { it == ':' } == 1) {
                val host = hostPart.substringBefore(':')
                if (port == null) port = hostPart.substringAfter(':').toIntOrNull()
                return host to port
            }
            return hostPart to port
        }

        /** 从整行里取出用户名（`me@host` 或 `ssh me@host`） */
        fun parseUserInput(raw: String): String {
            val at = raw.lastIndexOf('@')
            if (at <= 0) return ""
            val head = raw.substring(0, at).trim().split(Regex("\\s+")).lastOrNull().orEmpty()
            return head.takeIf { it.isNotBlank() && !it.startsWith("-") }.orEmpty()
        }
    }
}
