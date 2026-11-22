package com.dshwatch.ui.screens.account

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshwatch.core.AppContainer
import com.dshwatch.core.Logx
import com.dshwatch.data.account.DeepSeekAccountRepo
import com.dshwatch.net.deepseek.DeepSeekAccountApi
import com.dshwatch.net.deepseek.LoopbackCallbackServer
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * 授权登录页。
 *
 * ## 两条路，按顺序试
 *
 * 1. **系统浏览器 + 本机回调服务**（首选）。
 *    平台把授权页当普通网页，登录、验证码、已登录态的「确认授权」都在真浏览器里，
 *    密码管理器和短信自动填充也能用；回调跳到 `127.0.0.1:port/oauth/callback`，
 *    由 [LoopbackCallbackServer] 接住。这也是 DeepSeek 桌面端与 dsh-Go 的做法。
 * 2. **内置 WebView**（兜底）。
 *    手表等没有浏览器的设备走这条：回调地址不真的监听，直接在 WebView 里拦下。
 *
 * ## 为什么不能只用 WebView
 *
 * 平台前面有 WAF：OkHttp 默认 UA 直接 429，WebView 虽然能加载，但它是「壳里的浏览器」，
 * 登录态、验证码、cookie 隔离都比真浏览器差一截；手表小屏也要命。
 * 首选真浏览器，WebView 只在打不开浏览器时出现。
 *
 * ## App 不接触密码
 *
 * 页面域名始终是 `platform.deepseek.com`，输入框由平台渲染；
 * 我们只拿回 `code`，再用 PKCE verifier 换 token。
 */
@Composable
fun DeepSeekLoginScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val zh = s.isChinese
    val context = LocalContext.current
    val repo = container.deepSeekAccount
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()

    /** 走哪条路 */
    var mode by remember { mutableStateOf(Mode.Browser) }

    /*
     * 重试计数。
     *
     * 不能只靠 `mode` 当 LaunchedEffect 的 key：用户可能已经是浏览器模式，
     * 再点一次「重试」时 mode 没变，effect 不会重跑，看起来像点了没反应。
     */
    var attempt by remember { mutableStateOf(0) }
    var ticket by remember { mutableStateOf<DeepSeekAccountRepo.SignInTicket?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var finishing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }

    val serverRef = remember { AtomicReference<LoopbackCallbackServer?>(null) }
    val webRef = remember { AtomicReference<WebView?>(null) }

    fun fail(message: String) {
        error = message
        finishing = false
    }

    /** 拿到回调：校验 state → 换 token → 关页面 */
    fun finish(url: String, current: DeepSeekAccountRepo.SignInTicket) {
        if (finishing) return
        finishing = true
        scope.launch {
            repo.completeSignIn(url, current)
                .onSuccess {
                    completed = true
                    nav.pop()
                }
                .onFailure { fail(it.message ?: it.javaClass.simpleName) }
        }
    }

    /*
     * 第一条路：系统浏览器。
     *
     * 顺序不能反 —— 先起服务拿到**实际端口**，再拿这个地址去 auth_init；
     * 反过来的话平台会往一个没人监听的端口跳。
     */
    LaunchedEffect(mode, attempt) {
        if (mode != Mode.Browser) return@LaunchedEffect

        val server = LoopbackCallbackServer.start()
        if (server == null) {
            // 端口全被占：不报错，退回 WebView，用户照样能登录
            Logx.w("DsLogin", "没有可用端口，改用 WebView")
            mode = Mode.WebView
            return@LaunchedEffect
        }
        serverRef.set(server)

        try {
            val t = repo.beginSignIn(server.callbackUri).getOrElse {
                fail(it.message ?: it.javaClass.simpleName)
                return@LaunchedEffect
            }
            // 授权页跟随应用配色：深色表盘上弹出一张纯白页面很刺眼，
            // 官方客户端与 dsh-Go 都这么做（`?theme=dark`）
            val themed = t.copy(authorizeUrl = withTheme(t.authorizeUrl, dark))
            ticket = themed

            if (!openInBrowser(context, themed.authorizeUrl)) {
                // 没有浏览器（手表常见）：作废这张票据，改走 WebView 重新发起
                Logx.i("DsLogin", "打不开系统浏览器，改用 WebView")
                repo.cancelSignInAsync(t)
                ticket = null
                mode = Mode.WebView
                return@LaunchedEffect
            }

            // 票据有效期 10 分钟，这里给同样的窗口；用户慢一点也没关系
            val callback = server.await(10 * 60 * 1000L)
            finish(callback.url, themed)
        } catch (c: CancellationException) {
            // 页面被关掉 / 切到 WebView：不是错误，票据由 onDispose 作废
            throw c
        } catch (_: LoopbackCallbackServer.Timeout) {
            fail(if (zh) "等待授权超时，请重新登录" else "Timed out waiting for authorization")
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName)
        } finally {
            server.close()
            serverRef.compareAndSet(server, null)
        }
    }

    // 第二条路：WebView（没有浏览器，或端口全占）
    LaunchedEffect(mode, attempt) {
        if (mode != Mode.WebView) return@LaunchedEffect
        repo.beginSignIn()
            .onSuccess { ticket = it.copy(authorizeUrl = withTheme(it.authorizeUrl, dark)) }
            .onFailure { fail(it.message ?: it.javaClass.simpleName) }
    }

    /*
     * 页面关掉时收尾。
     *
     * 三条都要做：关掉监听端口、销毁 WebView、把没完成的授权在平台侧作废。
     * 最后一条用应用级作用域 —— 此刻页面的 scope 已经被取消。
     */
    DisposableEffect(Unit) {
        onDispose {
            serverRef.getAndSet(null)?.close()
            webRef.getAndSet(null)?.let {
                it.stopLoading()
                it.destroy()
            }
            if (!completed) repo.cancelSignInAsync(ticket)
        }
    }

    WatchScaffold(
        title = if (zh) "登录 DeepSeek" else "Sign in",
        onBack = { nav.pop() },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                error != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (zh) "登录没有完成" else "Sign-in did not finish",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    GhostButton(
                        text = if (zh) "重试" else "Retry",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            error = null
                            ticket = null
                            // 重试从「试浏览器」开始，别把用户永久钉在兜底路径上
                            mode = Mode.Browser
                            attempt++
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        text = if (zh) "改用内置页面登录" else "Use the in-app page",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            error = null
                            ticket = null
                            mode = Mode.WebView
                            attempt++
                        },
                    )
                }

                ticket == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (zh) "正在打开授权页…" else "Opening the authorization page…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                /*
                 * 浏览器已经打开：这一屏只是「你回来之后看到的东西」。
                 *
                 * 不显示「正在加载」以外的任何东西 —— 用户在浏览器里操作，
                 * 这里多写一句都可能被误读成「要在手表上输入密码」。
                 */
                mode == Mode.Browser -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = if (zh) "已打开浏览器" else "Browser opened",
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (zh) {
                            "在浏览器里登录并同意授权，完成后回到这里。"
                        } else {
                            "Sign in and approve in the browser, then come back here."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    GhostButton(
                        text = if (zh) "重新打开浏览器" else "Reopen browser",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { ticket?.let { openInBrowser(context, it.authorizeUrl) } },
                    )
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        text = if (zh) "改用内置页面" else "Use in-app page",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            repo.cancelSignInAsync(ticket)
                            ticket = null
                            mode = Mode.WebView
                            attempt++
                        },
                    )
                }

                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadsImagesAutomatically = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            /*
                             * 和协议请求用同一个浏览器 UA。
                             *
                             * 平台按 UA 判断客户端：`; wv` 标记会被降级，
                             * 而 OkHttp 那种 `okhttp/x` 更是直接 429。
                             */
                            settings.userAgentString = DeepSeekAccountApi.BROWSER_UA
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val url = request.url.toString()
                                    if (isCallback(url)) {
                                        ticket?.let { finish(url, it) }
                                        return true
                                    }
                                    return false
                                }

                                @Deprecated("旧 WebView 只回调这个重载")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                                    if (isCallback(url)) {
                                        ticket?.let { finish(url, it) }
                                        return true
                                    }
                                    return false
                                }

                                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                    // 平台可能自己跳一次 127.0.0.1；再兜一层，
                                    // 防止某些 WebView 版本不回调 shouldOverrideUrlLoading
                                    if (url != null && isCallback(url)) ticket?.let { finish(url, it) }
                                }
                            }
                            webRef.set(this)
                        }
                    },
                    update = { view ->
                        // factory 只跑一次；地址在拿到票据之后才有效，所以放在 update 里加载。
                        // `url == null` 是「还没加载过任何页面」，避免每次重组都重载授权页。
                        if (view.url == null) {
                            ticket?.let { view.loadUrl(it.authorizeUrl) }
                        }
                    },
                )
            }

            if (finishing) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (zh) "正在完成登录…" else "Finishing sign-in…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/** 登录走哪条路 */
private enum class Mode {
    /** 系统浏览器 + 本机回调服务 */
    Browser,

    /** 内置 WebView，拦截回调 */
    WebView,
}

/**
 * 用系统浏览器打开授权页。
 *
 * 返回 false 表示这台设备上没人能处理 https（手表常见），调用方据此退回 WebView。
 * 不用 `resolveActivity` 预判：Android 11 起看不到别的应用，预判会一律返回 null。
 */
private fun openInBrowser(context: Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
}.getOrElse {
    Logx.w("DsLogin", "打开浏览器失败：${it.javaClass.simpleName}")
    false
}

/**
 * 给授权页地址加上 `theme`。
 *
 * 平台按这个参数决定登录页用浅色还是深色 —— 不加就是白底，
 * 在手表上（也是深色表盘）会闪一下纯白。失败就原样返回，不能因为
 * 一个装饰性参数把登录挡住。
 */
private fun withTheme(url: String, dark: Boolean): String = runCatching {
    Uri.parse(url).buildUpon()
        .appendQueryParameter("theme", if (dark) "dark" else "light")
        .build()
        .toString()
}.getOrDefault(url)

/** 只认我们给出的那个回环地址；别的站点跳到 127.0.0.1 不管 */
private fun isCallback(url: String): Boolean =
    url.startsWith("http://127.0.0.1:", ignoreCase = true) &&
        url.contains(DeepSeekAccountApi.CALLBACK_PATH)
