package com.dshwatch.ui.screens.account

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.data.account.DeepSeekAccountRepo
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 账号页：DeepSeek 账号登录 + 开放平台的快捷入口。
 *
 * ## 为什么要做账号登录
 *
 * 以前用这个 App 必须先自己去开放平台建一条 API Key、复制、在手表上敲进去。
 * 而 DeepSeek Harness 桌面版支持「用平台账号登录，直接花平台余额」——
 * 同一套授权协议在手表上也能走通：App 打开平台自己的授权页，用户在页面上
 * 登录并点同意，App 拿回一枚 token。**密码从头到尾不进这个 App**。
 *
 * ## 快捷入口放在同一页的原因
 *
 * 登录之外，用户最常做的三件事是「看还剩多少钱」「建/换 API Key」「充值」。
 * 把这三件事藏在浏览器书签里，等于每次都要用户自己找一遍域名。
 */
@Composable
fun AccountScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val zh = s.isChinese
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account by container.deepSeekAccount.state.collectAsStateWithLifecycle()
    val settings by container.settings.collectAsStateWithLifecycle()
    val usingAccount = settings.activeProvider?.id == DeepSeekAccountRepo.PROVIDER_ID

    WatchScaffold(
        title = if (zh) "账号" else "Account",
        onBack = { nav.pop() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingCard {
                if (account.signedIn) {
                    SettingRow(
                        title = account.profile?.name?.takeIf { it.isNotBlank() }
                            ?: (if (zh) "已登录" else "Signed in"),
                        subtitle = account.profile?.maskedContact
                            ?: (if (zh) "DeepSeek 账号" else "DeepSeek account"),
                        trailing = "✓",
                        showDivider = true,
                    )
                    SettingRow(
                        title = if (zh) "余额" else "Balance",
                        subtitle = balanceLine(account.balance, zh),
                        trailing = if (account.loading) "…" else "",
                        showDivider = true,
                        onClick = if (account.loading) null else {
                            { scope.launch { container.deepSeekAccount.refresh() } }
                        },
                    )
                    SettingRow(
                        title = if (zh) "用它对话" else "Chat with it",
                        subtitle = if (usingAccount) {
                            if (zh) "当前对话正在使用这条供应商" else "Currently active"
                        } else {
                            if (zh) "把「DeepSeek（账号）」设为当前供应商" else "Make it the active provider"
                        },
                        trailing = if (usingAccount) "✓" else if (zh) "切换" else "Use",
                        showDivider = true,
                        onClick = if (usingAccount) null else {
                            {
                                scope.launch {
                                    container.settingsRepo.update {
                                        it.copy(activeProviderId = DeepSeekAccountRepo.PROVIDER_ID)
                                    }
                                }
                            }
                        },
                    )
                    SettingRow(
                        title = if (zh) "刷新余额与资料" else "Refresh",
                        subtitle = if (account.loading) {
                            if (zh) "正在刷新…" else "Refreshing…"
                        } else {
                            if (zh) "余额来自开放平台，可能有几分钟延迟" else "Balance comes from the platform"
                        },
                        trailing = if (account.loading) "" else "↻",
                        showDivider = false,
                        onClick = if (account.loading) null else {
                            { scope.launch { container.deepSeekAccount.refresh() } }
                        },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = if (zh) "登录 DeepSeek 账号" else "Sign in to DeepSeek",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (zh) {
                                "用开放平台账号登录后，对话直接花账号余额，不用再自己建 API Key。" +
                                    "密码只进平台自己的授权页，App 只保存一枚可随时撤销的令牌。"
                            } else {
                                "Sign in with your platform account and chat on its balance — no API key to create. " +
                                    "The password stays on the platform's own page; the app only keeps a revocable token."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(
                            text = if (zh) "去登录" else "Sign in",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                /*
                                 * 不再用「有没有 WebView」挡在门口。
                                 *
                                 * 登录的首选路径是**系统浏览器 + 回环回调**，WebView
                                 * 只是没有浏览器时的兜底 —— 手表上装了第三方浏览器
                                 * （腕上浏览器之类）就完全不需要 WebView。
                                 *
                                 * 以前这里先问 WebView，一问不到就弹「改用 API Key」，
                                 * 于是有浏览器的设备也被挡在外面：用户看到的正是
                                 * 那句提示，而不是浏览器里打开的平台登录页。
                                 *
                                 * 真正的可用性判断交给登录页：先试浏览器，打不开再
                                 * 退回 WebView，两者都没有才给一句人话。
                                 */
                                nav.push(Screen.DeepSeekLogin)
                            },
                        )
                    }
                }
            }

            if (account.error != null) {
                Text(
                    text = account.error.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }

            if (account.signedIn) {
                GhostButton(
                    text = if (zh) "退出登录" else "Sign out",
                    modifier = Modifier.fillMaxWidth(),
                    danger = true,
                    onClick = { scope.launch { container.deepSeekAccount.signOut() } },
                )
            }

            Text(
                text = if (zh) "开放平台" else "Platform",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )

            SettingCard {
                SettingRow(
                    title = if (zh) "API Keys 管理" else "API keys",
                    subtitle = if (zh) "创建、复制、吊销密钥" else "Create, copy, revoke",
                    trailing = "↗",
                    showDivider = true,
                    onClick = { openWeb(context, "https://platform.deepseek.com/api_keys", zh) },
                )
                SettingRow(
                    title = if (zh) "用量与账单" else "Usage & billing",
                    subtitle = if (zh) "看每天花了多少" else "Daily spend",
                    trailing = "↗",
                    showDivider = true,
                    onClick = { openWeb(context, "https://platform.deepseek.com/usage", zh) },
                )
                SettingRow(
                    title = if (zh) "充值" else "Top up",
                    subtitle = if (zh) "账号余额为 0 时来这里" else "When the balance runs out",
                    trailing = "↗",
                    showDivider = true,
                    onClick = { openWeb(context, "https://platform.deepseek.com/top_up", zh) },
                )
                SettingRow(
                    title = if (zh) "开放平台首页" else "Platform home",
                    subtitle = "platform.deepseek.com",
                    trailing = "↗",
                    showDivider = false,
                    onClick = { openWeb(context, "https://platform.deepseek.com/", zh) },
                )
            }

            SettingCard {
                SettingRow(
                    title = if (zh) "DeepSeek Harness 官网" else "DeepSeek Harness",
                    subtitle = if (zh) "桌面版下载与介绍" else "Desktop download",
                    trailing = "↗",
                    showDivider = false,
                    onClick = { openWeb(context, "https://www.deepseek.com/harness/", zh) },
                )
            }

            if (!account.signedIn) {
                Text(
                    text = if (zh) {
                        "不想登录？也可以在供应商里填一条自己的 API Key，两者都能用。"
                    } else {
                        "Prefer an API key? Add one under Providers — both work."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}

/** 余额那一行：`¥12.34 · 赠金 ¥3.00`。没有数据时给「—」而不是「0」，避免误以为没钱 */
private fun balanceLine(balance: com.dshwatch.net.deepseek.DeepSeekAccountApi.Balance?, zh: Boolean): String {
    if (balance == null) return if (zh) "点一下刷新" else "Tap to refresh"
    val main = balance.primary?.let { money(it.currency, it.balance) }
        ?: if (zh) "暂无余额" else "No balance"
    val bonus = balance.bonus.firstOrNull { it.currency == "CNY" && it.balance.toDoubleOrNull() != 0.0 }
    return if (bonus != null) {
        main + " · " + (if (zh) "赠金 " else "bonus ") + money(bonus.currency, bonus.balance)
    } else {
        main
    }
}

/** 平台的余额是字符串（可能是 `0E-16`），这里只负责显示：解析不了就原样给出去 */
private fun money(currency: String, raw: String): String {
    val symbol = if (currency == "USD") "$" else "¥"
    val shown = runCatching {
        BigDecimal(raw).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }.getOrDefault(raw)
    return "$symbol$shown"
}

/**
 * 打开外部链接。
 *
 * 手表上不一定有浏览器 —— `ActivityNotFoundException` 必须兜住，
 * 否则点一下「API Keys」就闪退。兜底给一句提示，至少用户知道发生了什么。
 */
private fun openWeb(context: Context, url: String, zh: Boolean) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(
            context,
            if (zh) "这台设备没有浏览器\n$url" else "No browser on this device\n$url",
            Toast.LENGTH_LONG,
        ).show()
    }
}
