package com.dshwatch.ui.screens.settings

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.dshwatch.core.Logx
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.net.qq.sso.LoginResult
import com.dshwatch.net.qq.sso.QqSession
import com.dshwatch.net.qq.sso.QqSessionStore
import com.dshwatch.net.qq.sso.QrLoginFlow
import com.dshwatch.net.qq.sso.QrStatus
import com.dshwatch.net.qq.sso.WatchDevice
import com.dshwatch.net.qq.sso.guidHex
import com.dshwatch.net.qq.sso.watchDeviceOrNull
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 手表协议扫码登录。
 *
 * 这一页的每一步都可能失败，而且失败原因差别很大：二维码出不来 = 网络或协议不对，
 * 出得来但扫完没反应 = 设备指纹被风控，扫完提示禁止登录 = 这个号不给第三方客户端登。
 * 所以状态要尽可能说清楚，而不是统一显示「失败」。
 */
@Composable
internal fun QqWatchLoginDialog(
    settings: AppSettings,
    update: Update,
    container: com.dshwatch.core.AppContainer,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    var phase by remember { mutableStateOf<Phase>(Phase.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        phase = Phase.Loading

        // 设备指纹必须稳定：没有就生成一份并落盘，之后每次都用这一份
        val device = settings.qq.watchDeviceOrNull()
            ?: WatchDevice.create().also { nd ->
                update { cur ->
                    cur.copy(qq = cur.qq.copy(watchGuid = nd.guidHex(), watchImei = nd.imei))
                }
            }

        val flow = QrLoginFlow(device)
        try {
            var qr = withContext(Dispatchers.IO) { flow.requestQr() }
            var bitmap = decode(qr.png)
                ?: throw IllegalStateException(
                    if (s.isChinese) "二维码图片解不开（${qr.png.size} 字节）" else "cannot decode qr png",
                )
            phase = Phase.Waiting(bitmap)

            var refreshes = 0
            while (isActive) {
                delay(POLL_MS)
                when (val st = withContext(Dispatchers.IO) { flow.poll(qr.sig) }) {
                    QrStatus.Waiting -> if (phase !is Phase.Waiting) phase = Phase.Waiting(bitmap)
                    QrStatus.Scanned -> phase = Phase.Scanned(bitmap)

                    QrStatus.Cancelled -> {
                        phase = Phase.Failed(
                            if (s.isChinese) "你在手机上取消了这次登录。" else "Cancelled on phone.",
                        )
                        return@LaunchedEffect
                    }

                    QrStatus.Expired -> {
                        if (++refreshes > MAX_REFRESH) {
                            phase = Phase.Failed(
                                if (s.isChinese) "二维码连续过期 3 次，先确认网络正常。" else "QR expired 3 times.",
                            )
                            return@LaunchedEffect
                        }
                        qr = withContext(Dispatchers.IO) { flow.requestQr() }
                        bitmap = decode(qr.png)
                            ?: throw IllegalStateException("二维码重新获取后解不开")
                        phase = Phase.Waiting(bitmap)
                    }

                    is QrStatus.Confirmed -> {
                        phase = Phase.SigningIn(st.uin)
                        phase = when (val res = withContext(Dispatchers.IO) { flow.login(st) }) {
                            is LoginResult.Success -> {
                                // 票据只在换票这一瞬间由服务端下发，不落盘就等于白扫
                                val saved = runCatching {
                                    QqSessionStore(container.keyStore).save(
                                        QqSession(
                                            uin = res.uin,
                                            nick = res.nick,
                                            a2 = res.a2,
                                            d2 = res.d2,
                                            d2Key = res.d2Key,
                                            ksid = res.ksid,
                                            tgt = res.tgt,
                                        ),
                                    )
                                }
                                if (saved.isFailure) {
                                    Logx.e("QqLogin", "登录态落盘失败", saved.exceptionOrNull())
                                }
                                Phase.SignedIn(res.uin, res.nick, res.d2Key.isNotEmpty(), saved.isSuccess)
                            }

                            is LoginResult.Failed -> Phase.Failed(res.message)
                        }
                        return@LaunchedEffect
                    }
                }
            }
        } catch (e: Throwable) {
            phase = Phase.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { flow.close() }
        }
    }

    GlassDialog(
        onDismissRequest = onClose,
        title = if (s.isChinese) "扫码登录 QQ" else "Sign in with QQ",
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (val p = phase) {
                    Phase.Loading -> Text(
                        text = if (s.isChinese) "正在向 QQ 要一张二维码…" else "Requesting QR code…",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    is Phase.Waiting -> {
                        QrPicture(p.image)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (s.isChinese) "用手机 QQ 扫一扫" else "Scan with mobile QQ",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(
                            if (s.isChinese) "手机 QQ → 右上角 + → 扫一扫。\n登录后这个号会显示「QQ手表在线」。"
                            else "Mobile QQ → + → Scan.",
                        )
                    }

                    is Phase.Scanned -> {
                        QrPicture(p.image)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (s.isChinese) "已扫到，请在手机上点确认" else "Scanned, confirm on phone",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is Phase.SigningIn -> {
                        Text(
                            text = if (s.isChinese) "已确认 · ${p.uin}" else "Confirmed · ${p.uin}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(if (s.isChinese) "正在换登录票据…" else "Exchanging tokens…")
                    }

                    is Phase.SignedIn -> {
                        Text(
                            text = if (s.isChinese) "登录成功 · ${p.uin}" else "Signed in · ${p.uin}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (p.nick.isNotEmpty()) {
                            Hint(if (s.isChinese) "昵称 ${p.nick}" else p.nick)
                        }
                        Hint(
                            if (s.isChinese) {
                                when {
                                    !p.hasKey -> "拿到了票据，但没解出消息密钥，后续请求会失败。"
                                    !p.saved -> "票据换到了，但没能存到本机 —— 重启后要重新扫码。"
                                    else -> "已加密保存到本机，重启不用再扫。手机 QQ 那边会显示「手表在线」。"
                                }
                            } else if (p.saved) "Saved on this device." else "Could not save the session.",
                        )
                    }

                    is Phase.Failed -> {
                        Text(
                            text = if (s.isChinese) "没成功" else "Failed",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Hint(p.message)
                    }
                }
            }
        },
        buttons = {
            when (phase) {
                is Phase.Failed -> {
                    GlassDialogButton(
                        label = if (s.isChinese) "重试" else "Retry",
                        primary = true,
                        onClick = { attempt++ },
                    )
                    GlassDialogButton(label = s.close, primary = false, onClick = onClose)
                }

                else -> GlassDialogButton(label = s.close, primary = true, onClick = onClose)
            }
        },
    )
}

@Composable
private fun QrPicture(image: ImageBitmap) {
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(190.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun Hint(text: String) {
    Spacer(Modifier.height(6.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun decode(bytes: ByteArray): ImageBitmap? =
    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

private sealed interface Phase {
    object Loading : Phase
    class Waiting(val image: ImageBitmap) : Phase
    class Scanned(val image: ImageBitmap) : Phase

    /** 扫完了，正在换票据 */
    class SigningIn(val uin: Long) : Phase

    /** 票据换到手了，并且已经落盘 */
    class SignedIn(
        val uin: Long,
        val nick: String,
        val hasKey: Boolean,
        val saved: Boolean,
    ) : Phase

    class Failed(val message: String) : Phase
}

/** 轮询间隔。太短会被服务端当成刷接口，太长则扫码后要干等 */
private const val POLL_MS = 1_500L

/** 二维码有效期约两分钟，允许自动续 3 次 */
private const val MAX_REFRESH = 3
