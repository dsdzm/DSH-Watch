package com.dshwatch.net.remote

import android.content.Context
import com.dshwatch.data.settings.SecureKeyStore
import com.dshwatch.domain.model.Provider
import com.dshwatch.net.lan.PairingPortal
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 「手表开个网页，手机代劳登录」的编排层。
 *
 * 一次配对的状态机：
 *
 * ```text
 * NEED_SERVER ──填地址──> CHOOSING ──选登录方式──> AUTHORIZING ──授权完成──> DONE
 *                              └──手动令牌/密码──────────────┘
 * ```
 *
 * 所有需要联网的动作都由手表发起（手机只发指令），因为手机页面是手表提供的，
 * 让浏览器直接去打 DSH 服务器会撞跨域，也会把令牌的路经摊长一段。
 */
class RemotePairing(
    context: Context,
    private val keyStore: SecureKeyStore,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val clientVersion: String,
    json: Json,
) {

    /** 配对成功的结果，交给界面落库 */
    data class Result(
        val server: String,
        val deviceId: String,
        val accessToken: String,
        val refreshToken: String,
        val hosts: List<RemoteHost>,
        val identityKey: String,
    )

    private val portal = PairingPortal(context, json)
    private var client: RemoteServer? = null
    private var poller: Job? = null

    val state = portal.state
    val link = portal.link

    /** 配对完成时回调；在主线程之外，调用方自己切 */
    var onPaired: ((Result) -> Unit)? = null
    var onCancelled: (() -> Unit)? = null

    private var scopeKey = SCOPE

    fun open(provider: Provider?): String? {
        scopeKey = provider?.id?.takeIf { it.isNotBlank() } ?: SCOPE
        portal.onSubmission = ::handle
        val url = portal.start()
        if (url == null) {
            portal.update {
                it.copy(
                    phase = PairingPortal.Snapshot.Phase.FAILED,
                    title = "打不开局域网服务",
                    detail = "这台手表现在没有连 Wi-Fi，或者网络不允许入站连接。",
                )
            }
            return null
        }
        val hint = provider?.remoteServer?.takeIf { it.isNotBlank() }
        if (hint != null) {
            portal.update { it.copy(server = hint) }
            // 地址已经知道，直接进「选登录方式」，省掉手机上再填一次
            scope.launch { loadProviders(hint) }
        } else {
            portal.update {
                it.copy(
                    phase = PairingPortal.Snapshot.Phase.NEED_SERVER,
                    title = "用手机打开这个地址",
                    detail = "手机和手表要在同一个 Wi-Fi 下",
                )
            }
        }
        return url
    }

    fun close() {
        poller?.cancel()
        poller = null
        portal.onSubmission = null
        portal.stop()
    }

    // ---------------------------------------------------------------- 提交

    private fun handle(submission: PairingPortal.Submission) {
        when (submission) {
            is PairingPortal.Submission.SetServer -> scope.launch { loadProviders(submission.server) }
            is PairingPortal.Submission.StartLogin -> scope.launch { startLogin(submission.provider) }
            is PairingPortal.Submission.ManualToken -> scope.launch {
                val normalized = RemoteServer.normalize(submission.server)
                if (normalized == null) {
                    fail("服务器地址不对：要么是 https://，要么是局域网 IP")
                    return@launch
                }
                client = RemoteServer(normalized)
                finishWithAccountToken(normalized, submission.token)
            }
            is PairingPortal.Submission.ManualPassword -> scope.launch {
                val normalized = RemoteServer.normalize(submission.server)
                if (normalized == null) {
                    fail("服务器地址不对：要么是 https://，要么是局域网 IP")
                    return@launch
                }
                val api = RemoteServer(normalized)
                client = api
                portal.update { it.copy(title = "正在登录…", error = null) }
                runCatching { api.loginWithPassword(submission.email, submission.password) }
                    .onSuccess { finishWithAccountToken(normalized, it) }
                    .onFailure { fail(it) }
            }
            PairingPortal.Submission.Cancel -> {
                onCancelled?.invoke()
                close()
            }
        }
    }

    private suspend fun loadProviders(raw: String) {
        val normalized = RemoteServer.normalize(raw)
        if (normalized == null) {
            fail("服务器地址不对：要么是 https://，要么是局域网 IP")
            return
        }
        portal.update {
            it.copy(server = normalized, title = "正在读取登录方式…", error = null, detail = "")
        }
        val api = RemoteServer(normalized)
        client = api
        runCatching { api.providers() }
            .onSuccess { list ->
                portal.update {
                    it.copy(
                        phase = PairingPortal.Snapshot.Phase.CHOOSING,
                        title = "选择登录方式",
                        detail = "能在这台手机上完成",
                        providers = list.map { p -> p.id to p.displayName },
                        error = null,
                    )
                }
            }
            .onFailure { fail(it) }
    }

    private suspend fun startLogin(providerId: String) {
        val api = client ?: run { fail("还没填服务器地址"); return }
        val provider = RemoteProvider(
            id = providerId,
            displayName = providerId,
            qrStartPath = "/api/v1/auth/oauth/qr/start?provider=$providerId",
        )
        portal.update { it.copy(title = "正在打开授权页…", error = null) }
        val session = runCatching { api.startQr(provider) }.getOrElse { fail(it); return }
        portal.update {
            it.copy(
                phase = PairingPortal.Snapshot.Phase.AUTHORIZING,
                title = "等待授权",
                detail = "在手机上完成后回到这个页面",
                actionUrl = session.scanUrl,
                matrix = matrix(session.scanUrl),
                provider = provider.displayName,
                error = null,
            )
        }
        poller?.cancel()
        poller = scope.launch { poll(api, session, provider.displayName) }
    }

    private suspend fun poll(api: RemoteServer, session: QrSession, providerName: String) {
        val deadline = System.currentTimeMillis() + session.expiresInSeconds * 1000L
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            delay(1_500)
            val result = runCatching { api.pollQr(session.qrId) }
            if (result.isFailure) {
                // 网络抖一下就放弃太急，但要留一条不给用户看天书的出路
                val error = result.exceptionOrNull()
                if (error is RemoteError.Protocol) {
                    fail(error)
                    return
                }
                continue
            }
            when (val polled = result.getOrNull()) {
                is QrPoll.Complete -> {
                    portal.update { it.copy(title = "已授权，正在登记这台手表…", detail = "") }
                    finishWithAccountToken(api.origin, polled.accountToken)
                    return
                }
                QrPoll.Expired -> {
                    fail("二维码过期了，重新选一次 $providerName 登录")
                    return
                }
                else -> Unit
            }
        }
        if (scope.isActive) fail("等太久了，重新来一次")
    }

    /** 账号令牌只能用来注册设备；拿到设备令牌才算真的登进来 */
    private suspend fun finishWithAccountToken(server: String, accountToken: String) {
        val api = client ?: RemoteServer(server)
        val deviceId = keyStore.getRemoteDeviceId(scopeKey) ?: UUID.randomUUID().toString().also {
            keyStore.putRemoteDeviceId(scopeKey, it)
        }
        val identity = identityKey()
        val identityPublic = NoiseIk.publicOf(identity).toBase64Url()
        val descriptor = RemoteDevice(
            deviceId = deviceId,
            // 服务端按 UTF-8 字节算，上限 128：中文一个字 3 字节，留足余量
            name = deviceName.take(32).ifBlank { "DSH Watch" },
            platform = "wearos",
            identityKey = identityPublic,
            clientVersion = clientVersion.take(64),
        )
        val tokens = runCatching { api.register(descriptor, accountToken) }
            .getOrElse { fail(it); return }

        val hosts = runCatching { api.hosts(tokens.accessToken) }.getOrDefault(emptyList())
        keyStore.putRemoteAccess(scopeKey, tokens.accessToken)
        keyStore.putRemoteRefresh(scopeKey, tokens.refreshToken)

        onPaired?.invoke(
            Result(
                server = api.origin,
                deviceId = deviceId,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                hosts = hosts,
                identityKey = identityPublic,
            )
        )
        portal.update {
            it.copy(
                phase = PairingPortal.Snapshot.Phase.DONE,
                title = "已登录",
                detail = if (hosts.isEmpty()) {
                    "服务端还没看到可连的电脑。在电脑上打开 DSH Remote 插件后，回到手表就能看到。"
                } else {
                    "可以关掉这个页面了。"
                },
                hosts = hosts.map { h -> h.deviceId to h.name },
                error = null,
            )
        }
        // 这里不立刻关端口：页面可能还没轮到下一次轮询，先关就看不到「已登录」。
        // 空闲超时（5 分钟）会把它收掉，用户关掉页面也有同样的效果。
        poller?.cancel()
    }

    private fun identityKey(): ByteArray {
        val saved = keyStore.getIdentityKey(scopeKey)?.let {
            runCatching { it.fromBase64Url() }.getOrNull()?.takeIf { k -> k.size == 32 }
        }
        if (saved != null) return saved
        val fresh = NoiseIk.newPrivateKey()
        keyStore.putIdentityKey(scopeKey, fresh.toBase64Url())
        return fresh
    }

    private fun fail(t: Throwable) {
        val message = when (t) {
            is RemoteError.Unauthorized -> "登录已失效，重新扫码"
            is RemoteError.Http -> "服务器返回 ${t.code}：${t.body.take(80)}"
            is RemoteError.Network -> "连不上服务器：${t.message?.removePrefix("网络不通：")}"
            else -> t.message ?: "出错了"
        }
        portal.update {
            it.copy(
                phase = PairingPortal.Snapshot.Phase.FAILED,
                title = "没成功",
                detail = message,
                error = message,
            )
        }
    }

    private fun fail(message: String) {
        portal.update {
            it.copy(
                phase = PairingPortal.Snapshot.Phase.FAILED,
                title = "没成功",
                detail = message,
                error = message,
            )
        }
    }

    companion object {
        /** 令牌与身份的命名空间。一个手表只有一个「当前服务器」，用固定名足够 */
        const val SCOPE = "dshRemote"

        /**
         * 二维码矩阵，每行一串 0/1。
         *
         * 不传图片而是传矩阵：页面用 canvas 自己画，省掉一次 base64 编解码，
         * 也避免把 SVG/PNG 塞进 JSON 里再转义一层。
         */
        fun matrix(content: String): List<String> = runCatching {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
            (0 until bits.height).map { y ->
                buildString(bits.width) {
                    for (x in 0 until bits.width) append(if (bits.get(x, y)) '1' else '0')
                }
            }
        }.getOrDefault(emptyList())
    }
}
