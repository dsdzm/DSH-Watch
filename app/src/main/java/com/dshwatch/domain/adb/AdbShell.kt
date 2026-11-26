package com.dshwatch.domain.adb

import android.content.Context
import com.dshwatch.core.Logx
import com.dshwatch.domain.shell.ShellOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 用 ADB 协议直连本机或同网段设备，替代 Shizuku 的 binder 通道。
 *
 * ## 为什么需要第二条通道
 *
 * Shizuku 依赖它的服务进程被「启动」—— 而启动本身要靠 adb（USB 或无线调试）。
 * 在部分机型上 Shizuku 服务起不来、被系统杀掉、或者厂商 ROM 不发 binder，
 * 用户就完全没有提权路径。这里把 adb 客户端做进应用：只要能连上设备的 5555 端口
 * 并让用户点一次「允许」，能力与 Shizuku 完全等价（同为 shell 用户）。
 *
 * ## 连接模型：用完即走
 *
 * 不常驻 socket。原因有三个：应用被冻结后 socket 会静默失效，恢复时表现为
 * 「命令全部超时」；手机重启后端口关闭，常驻连接只剩一个假状态；多线程同时
 * 用一条连接还要自己做流编号复用。每次执行重新握手约几十毫秒，换来的确定性更值。
 */
object AdbShell {

    private const val TAG = "AdbShell"
    private const val PREFS = "adb_bridge"
    private const val KEY_HOST = "host"
    private const val KEY_PORT = "port"

    /** 与 [com.dshwatch.domain.shell.ShizukuShell] 一致的输出上限 */
    private const val MAX_OUTPUT = 12_000

    sealed class State {
        /** 还没配置过地址 */
        data object Off : State()

        /** 上次连接成功 */
        data class On(val endpoint: String) : State()

        /** 上次连接失败，保留原因供界面显示 */
        data class Failed(val endpoint: String, val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Off)

    /** 给界面订阅；写操作串行执行，避免两条命令同时抢同一个端口 */
    val state: StateFlow<State> get() = _state

    private val gate = Mutex()

    fun savedEndpoint(context: Context): Pair<String, Int>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.trim().orEmpty()
        val port = prefs.getInt(KEY_PORT, 0)
        if (host.isEmpty() || port !in 1..65535) return null
        return host to port
    }

    fun isConfigured(context: Context): Boolean = savedEndpoint(context) != null

    fun restore(context: Context) {
        val ep = savedEndpoint(context) ?: return
        if (_state.value is State.Off) {
            _state.value = State.On("${ep.first}:${ep.second}")
        }
    }

    /**
     * 连接并验证。
     *
     * 验证用 `echo`，不是空跑一次握手 —— 认证通过但 shell 通道被 SELinux 拦住
     * 的情况真实存在，只判断 socket 会给出一个「已连接」的假状态。
     */
    suspend fun connect(context: Context, host: String, port: Int): ShellOutcome =
        withContext(Dispatchers.IO) {
            val cleanHost = host.trim().removePrefix("http://").removePrefix("https://")
                .substringBefore('/').substringBefore(':')
            if (cleanHost.isEmpty()) {
                return@withContext ShellOutcome(-1, "", "", error = "地址为空")
            }
            if (port !in 1..65535) {
                return@withContext ShellOutcome(-1, "", "", error = "端口应在 1 到 65535 之间")
            }

            gate.withLock {
                val key = AdbKey.loadOrCreate(File(context.filesDir, "adb"))
                val client = AdbClient(cleanHost, port, key)
                val out = ByteArrayOutputStream()
                val err = ByteArrayOutputStream()
                try {
                    client.connect()
                    val code = client.exec(
                        command = "echo dsh-adb-ok",
                        timeoutMs = 15_000,
                        onStdout = { out.write(it) },
                        onStderr = { err.write(it) },
                    )
                    val text = out.toString().trim()
                    /*
                     * 判定以「输出里有没有回显」为准，不看退出码：
                     * legacy 通道（shell,raw:）拿不到退出码，恒为 -1，
                     * 按退出码判会把一条完全正常的连接判成失败。
                     */
                    if (!text.contains("dsh-adb-ok")) {
                        /*
                         * 失败时把「设备实际回了什么」带出去。
                         * 只报「退出码 -1」等于什么都没说：既不知道是没输出、
                         * 还是输出格式不对。附一段原始字节，用户截图就能定因。
                         */
                        val hex = client.diagnostics
                        val why = buildString {
                            append(err.toString().trim().ifBlank { if (code < 0) "通道未返回数据" else "退出码 $code" })
                            if (hex.isNotBlank()) append("；收到字节 $hex")
                        }
                        _state.value = State.Failed("$cleanHost:$port", why)
                        return@withLock ShellOutcome(code, text, err.toString(), error = "设备返回异常：$why")
                    }
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    prefs.edit().putString(KEY_HOST, cleanHost).putInt(KEY_PORT, port).apply()
                    _state.value = State.On("$cleanHost:$port")
                    Logx.i(TAG, "已连接 $cleanHost:$port")
                    ShellOutcome(0, text, "")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    /*
                     * 捕获 Throwable 而不是 Exception：真机上出现过 OutOfMemoryError
                     * 与 IllegalStateException（已关闭的流）穿透到这里，一路冒到对话里
                     * 只剩一句「内部异常」，既不含通道信息也没有异常类型。
                     */
                    val detail = e.message?.takeIf { it.isNotBlank() }
                    val msg = buildString {
                        append("ADB 连接失败：")
                        append(detail ?: e.javaClass.simpleName)
                        if (detail != null) append("（").append(e.javaClass.simpleName).append("）")
                        val hex = client.diagnostics
                        if (hex.isNotBlank()) append("；收到字节 ").append(hex)
                    }
                    _state.value = State.Failed("$cleanHost:$port", msg)
                    Logx.w(TAG, "连接 $cleanHost:$port 失败：$msg")
                    ShellOutcome(-1, out.toString(), err.toString(), error = msg)
                } finally {
                    client.close()
                }
            }
        }

    fun disconnect(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _state.value = State.Off
    }

    suspend fun run(
        context: Context,
        command: String,
        timeoutMs: Long = 30_000,
    ): ShellOutcome = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext ShellOutcome(-1, "", "", error = "命令为空")
        val ep = savedEndpoint(context)
            ?: return@withContext ShellOutcome(
                -1, "", "",
                error = "ADB 通道未连接。请到 设置 → 设备操控 → ADB 直连 里填写地址并授权。",
            )

        gate.withLock {
            val key = AdbKey.loadOrCreate(File(context.filesDir, "adb"))
            val client = AdbClient(ep.first, ep.second, key)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            var truncated = false
            try {
                client.connect()
                val code = client.exec(
                    command = command,
                    timeoutMs = timeoutMs.toInt(),
                    onStdout = { chunk ->
                        val room = MAX_OUTPUT - out.size()
                        if (room <= 0) truncated = true else out.write(chunk, 0, minOf(room, chunk.size))
                    },
                    onStderr = { chunk ->
                        val room = MAX_OUTPUT - err.size()
                        if (room <= 0) truncated = true else err.write(chunk, 0, minOf(room, chunk.size))
                    },
                )
                _state.value = State.On(client.endpoint)
                var text = out.toString(Charsets.UTF_8)
                if (truncated) text += "\n…（输出超过 $MAX_OUTPUT 字符，已截断）"
                ShellOutcome(code, text, err.toString(Charsets.UTF_8))
            } catch (e: AdbException) {
                val timeout = e.message?.contains("超时") == true
                val hex = client.diagnostics
                val msg = buildString {
                    append(e.message ?: "ADB 执行失败")
                    if (hex.isNotBlank()) append("；收到字节 $hex")
                }
                if (!timeout) _state.value = State.Failed(client.endpoint, msg)
                ShellOutcome(
                    exitCode = -1,
                    stdout = out.toString(Charsets.UTF_8),
                    stderr = err.toString(Charsets.UTF_8),
                    timedOut = timeout,
                    error = msg,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = "ADB 执行失败：${e.message ?: e.javaClass.simpleName}（${e.javaClass.simpleName}）"
                _state.value = State.Failed(client.endpoint, msg)
                ShellOutcome(-1, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8), error = msg)
            } finally {
                client.close()
            }
        }
    }

    /**
     * 把命令的原始输出写进文件。
     *
     * 截图必须走这条：`screencap -p` 是二进制，按字符串收一遍再写回去，
     * 换行会被 UTF-8 解码器改写，PNG 头就烂了。
     */
    suspend fun runIntoFile(
        context: Context,
        command: String,
        outFile: File,
        timeoutMs: Long = 20_000,
    ): ShellOutcome = withContext(Dispatchers.IO) {
        if (command.isBlank()) return@withContext ShellOutcome(-1, "", "", error = "命令为空")
        val ep = savedEndpoint(context)
            ?: return@withContext ShellOutcome(
                -1, "", "",
                error = "ADB 通道未连接。请到 设置 → 设备操控 → ADB 直连 里填写地址并授权。",
            )

        gate.withLock {
            val key = AdbKey.loadOrCreate(File(context.filesDir, "adb"))
            val client = AdbClient(ep.first, ep.second, key)
            val err = ByteArrayOutputStream()
            outFile.parentFile?.mkdirs()
            try {
                client.connect()
                FileOutputStream(outFile).use { sink ->
                    val code = client.exec(
                        command = command,
                        timeoutMs = timeoutMs.toInt(),
                        onStdout = { chunk -> sink.write(chunk) },
                        onStderr = { chunk -> if (err.size() < MAX_OUTPUT) err.write(chunk) },
                    )
                    sink.flush()
                    ShellOutcome(code, "", err.toString(Charsets.UTF_8))
                }
            } catch (e: AdbException) {
                val timeout = e.message?.contains("超时") == true
                if (!timeout) _state.value = State.Failed(client.endpoint, e.message ?: "连接失败")
                ShellOutcome(-1, "", err.toString(Charsets.UTF_8), timedOut = timeout, error = e.message ?: "ADB 执行失败")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ShellOutcome(
                    -1, "", err.toString(Charsets.UTF_8),
                    error = "ADB 执行失败：${e.message ?: e.javaClass.simpleName}（${e.javaClass.simpleName}）",
                )
            } finally {
                client.close()
            }
        }
    }

    /**
     * 本机当前可被同网段访问的 IPv4 地址，用来预填输入框。
     *
     * 跳过 loopback、未启用的网卡与 VPN 隧道：VPN 地址在关闭后立刻失效，
     * 预填它等于给用户一个注定连不上的默认值。
     */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .filterNot { it.name.startsWith("tun") || it.name.startsWith("ppp") }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
