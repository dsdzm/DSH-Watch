package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.bili.BiliApi
import com.dshwatch.domain.bili.QrLogin
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.QrCode
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * B站扫码登录。
 *
 * ## 为什么值得做一个独立页面
 *
 * 手填 Cookie 对普通用户是不可能完成的任务：要在电脑上开开发者工具、
 * 从一堆 Set-Cookie 里翻出 SESSDATA 和 bili_jct、再原样敲进手表的输入框。
 * 而扫码只要「手机上点两下」——这是这个功能能不能被用起来的分水岭。
 *
 * ## 凭证怎么落地
 *
 * 登录成功后 B站会在 `Set-Cookie` 里给 SESSDATA / bili_jct / DedeUserID。
 * 这里只挑这几个键拼成 Cookie 串，交给 [com.dshwatch.data.settings.SecureKeyStore]
 * （Android Keystore 加密），界面永远只显示「✓ / ✗」，不显示明文。
 */
@Composable
fun BiliLoginScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val api = remember { BiliApi { container.keyStore.getBiliCookie().orEmpty() } }

    var qr by remember { mutableStateOf<QrLogin?>(null) }
    var step by remember { mutableStateOf(Step.LOADING) }
    var note by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    // 生成（或重新生成）二维码
    LaunchedEffect(attempt) {
        step = Step.LOADING
        note = ""
        runCatching { api.qrGenerate() }
            .onSuccess {
                qr = it
                step = Step.WAITING
            }
            .onFailure {
                qr = null
                step = Step.ERROR
                note = it.message.orEmpty().take(60)
            }
    }

    // 轮询扫码结果。2 秒一次，B站 的二维码 180 秒过期
    LaunchedEffect(qr) {
        val code = qr ?: return@LaunchedEffect
        var waited = 0
        var errors = 0
        while (isActive) {
            delay(2_000)
            waited += 2
            val result = runCatching { api.qrPoll(code.key) }
            val poll = result.getOrNull()
            if (poll == null) {
                errors++
                if (errors >= 3) {
                    step = Step.ERROR
                    note = result.exceptionOrNull()?.message.orEmpty().take(60)
                    break
                }
                continue
            }
            errors = 0
            when (poll.code) {
                0 -> {
                    val cookie = poll.cookie
                    if (cookie.isBlank()) {
                        step = Step.ERROR
                        note = "扫码成功但没拿到凭证，请重试"
                        break
                    }
                    container.keyStore.putBiliCookie(cookie)
                    // 昵称只是用来显示「已登录：xxx」，取不到不影响登录本身
                    val name = runCatching {
                        BiliApi { cookie }.me()
                    }.getOrNull()?.takeIf { it.loggedIn }?.name.orEmpty()
                    container.settingsRepo.update { it.copy(biliUserName = name) }
                    step = Step.DONE
                    delay(1_200)
                    nav.pop()
                    break
                }

                86090 -> step = Step.SCANNED
                86038 -> {
                    step = Step.EXPIRED
                    break
                }
                // 86101 = 未扫码；其余按未扫码处理，靠超时兜底
                else -> if (waited >= 180) {
                    step = Step.EXPIRED
                    break
                }
            }
        }
    }

    WatchScaffold(
        title = s.biliLogin,
        onBack = { nav.pop() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .wearVerticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = when (step) {
                    Step.LOADING -> s.biliLoginWaiting
                    Step.WAITING -> s.biliLoginWaiting
                    Step.SCANNED -> s.biliLoginScanned
                    Step.DONE -> s.biliLoginDone
                    Step.EXPIRED -> s.biliLoginExpired
                    Step.ERROR -> s.biliLoginExpired
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (step) {
                    Step.DONE -> MaterialTheme.colorScheme.primary
                    Step.EXPIRED, Step.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            // 手表是圆屏：正方形要缩到内切圆以内才不会被裁掉四角
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.74f)
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                when (step) {
                    Step.LOADING -> CircularProgressIndicator(
                        modifier = Modifier.fillMaxWidth(0.28f).aspectRatio(1f),
                        strokeWidth = 2.dp,
                    )

                    Step.WAITING, Step.SCANNED, Step.DONE -> {
                        val content = qr?.url
                        if (content != null) {
                            QrCode(content = content, modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Step.EXPIRED, Step.ERROR -> GhostButton(
                        text = s.biliLoginRetry,
                        onClick = { attempt++ },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
            }

            Text(
                text = s.biliLoginHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = s.biliLoginPrivacy,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

private enum class Step { LOADING, WAITING, SCANNED, DONE, EXPIRED, ERROR }
