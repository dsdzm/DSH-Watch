package com.dshwatch.data.account

import android.content.Context
import android.net.Uri
import android.os.Build
import com.dshwatch.BuildConfig
import com.dshwatch.core.Logx
import com.dshwatch.data.settings.SecureKeyStore
import com.dshwatch.data.settings.SettingsRepository
import com.dshwatch.domain.model.Provider
import com.dshwatch.domain.model.ProviderType
import com.dshwatch.net.deepseek.DeepSeekAccountApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * DeepSeek 账号：登录态、余额，以及「登录之后聊天用哪个供应商」。
 *
 * ## 为什么账号不挂在某个供应商下面
 *
 * API Key 属于供应商（一个 key 对应一个 baseUrl）。账号不是：
 * 它属于**用户**，一个账号可以同时喂给官方 API、Harness、网页版。
 * 所以登录态存在这里（一份），供应商只是引用它 —— 退出登录时
 * 供应商还在，只是发请求前会告诉你「先登录」。
 *
 * ## token 存哪
 *
 * [SecureKeyStore]（Android Keystore 加密）。不写设置 JSON 的理由和 API Key 一样：
 * 设置会被导出、会被截图发群里，而 token 等于账号里的余额。
 */
class DeepSeekAccountRepo(
    private val context: Context,
    private val keyStore: SecureKeyStore,
    private val settingsRepo: SettingsRepository,
    private val scope: CoroutineScope,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val language: () -> String = { "zh" },
) {

    companion object {
        /** 账号登录生成的供应商 id。固定值：重启、重装、再登录都要认得出是同一个 */
        const val PROVIDER_ID = "deepseek-account"

        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"

        /** 官方当前两个对话模型；用户可以在供应商页改 */
        val DEFAULT_MODELS = listOf("deepseek-chat", "deepseek-reasoner")

        private const val TAG = "DeepSeekAccount"
    }

    private val api = DeepSeekAccountApi(
        clientVersion = appVersion,
        uiLanguage = language,
    )

    /**
     * 界面状态。
     *
     * `signedIn` 只表示**本地有 token**，不表示平台还认它 —— 真正的校验在
     * [refresh] 里，401 会把状态打回未登录。两者分开是为了让启动瞬间
     * 就能显示上次的昵称与余额，而不是先闪一下「未登录」再跳回来。
     */
    data class State(
        val signedIn: Boolean = false,
        val profile: DeepSeekAccountApi.Profile? = null,
        val balance: DeepSeekAccountApi.Balance? = null,
        val loading: Boolean = false,
        val error: String? = null,
    )

    /** 一次进行中的登录：授权页地址 + 回调要校验的三样东西 */
    data class SignInTicket(
        val authorizeUrl: String,
        val verifier: String,
        val state: String,
        val redirectUri: String,
        val authorizeId: String,
    )

    private val _state = MutableStateFlow(State(signedIn = keyStore.getDeepSeekToken() != null))
    val state: StateFlow<State> = _state.asStateFlow()

    val isSignedIn: Boolean get() = keyStore.getDeepSeekToken() != null

    /** 聊天请求要用的 token。空串表示没登录 */
    fun token(): String = keyStore.getDeepSeekToken().orEmpty()

    /** 启动时调一次：有 token 就把昵称与余额拉回来 */
    fun restore() {
        if (!isSignedIn) return
        scope.launch { refresh() }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 发起一次登录。
     *
     * 只做「拿授权页地址」这一步 —— 用户还要在网页上登录、点授权。
     * 返回值交给登录页去加载，回调再由 [completeSignIn] 收尾。
     *
     * [redirectUri] 必须是**这台机器上真的有人监听**的 loopback 地址：
     * 系统浏览器那条路要先起回调服务、拿到实际端口再调这里，
     * 所以不能写死成常量（端口被占时会顺延）。
     */
    suspend fun beginSignIn(
        redirectUri: String = DeepSeekAccountApi.callbackUrl(),
    ): Result<SignInTicket> = runCatching {
        val verifier = DeepSeekAccountApi.newVerifier()
        val state = DeepSeekAccountApi.newState()
        val redirect = redirectUri
        val init = api.startSignIn(
            codeChallenge = DeepSeekAccountApi.challengeOf(verifier),
            state = state,
            redirectUri = redirect,
        )
        Logx.i(TAG, "登录已发起，授权页=${init.authorizeUrl.substringBefore('?')}")
        SignInTicket(
            authorizeUrl = init.authorizeUrl,
            verifier = verifier,
            state = state,
            redirectUri = redirect,
            authorizeId = init.authorizeId,
        )
    }.onFailure { record(it, "发起登录失败") }

    /**
     * 收到浏览器回调：校验 → 换 token → 落盘 → 拉资料。
     *
     * 校验三件事，缺一不可：
     *  - 是不是我们指定的 loopback 地址；
     *  - `state` 是否与发起时一模一样（防别人构造一个 code 塞进来）；
     *  - `code` 是否存在且只出现一次。
     *
     * 这几条和官方桌面端一致。**不要为了「兼容」放宽 state 校验** ——
     * 授权码是一次性的，被换走就等于账号被别人登了。
     */
    suspend fun completeSignIn(callback: String, ticket: SignInTicket): Result<Unit> = runCatching {
        val uri = Uri.parse(callback)
        if (!uri.scheme.equals("http", true) || uri.host != "127.0.0.1" ||
            uri.path != DeepSeekAccountApi.CALLBACK_PATH
        ) {
            throw IllegalArgumentException("回调地址不对")
        }
        // 用户点了「拒绝」时平台不返 code，而是 error=access_denied。
        // 这种情况必须单独说清楚，否则会显示成「回调里没有授权码」，让人以为是我们的 bug
        uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }?.let {
            throw IllegalArgumentException(
                if (it == "access_denied") "你取消了这次授权" else "平台拒绝了授权：$it",
            )
        }
        val gotState = uri.getQueryParameter("state").orEmpty()
        if (gotState != ticket.state) throw IllegalArgumentException("state 不匹配，已拒绝这次回调")
        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("回调里没有授权码")

        val token = api.exchange(
            code = code,
            verifier = ticket.verifier,
            redirectUri = ticket.redirectUri,
            deviceId = deviceId(),
            deviceModel = deviceModel(),
            osVersion = osVersion(),
        )
        keyStore.putDeepSeekToken(token)
        _state.value = State(signedIn = true, loading = true)
        ensureProvider()
        refresh()
        Logx.i(TAG, "账号登录完成")
    }.onFailure { record(it, "登录失败") }

    /** 用户中途退出登录页：把这次票据在平台侧作废 */
    suspend fun cancelSignIn(ticket: SignInTicket?) {
        if (ticket == null) return
        api.cancel(ticket.authorizeId, ticket.verifier)
    }

    /**
     * 同上，但不等结果。
     *
     * 给「页面被销毁」用：那一刻界面的 `rememberCoroutineScope` 已经被取消，
     * 挂在上面的协程根本不会执行 —— 取消请求必须落到应用级作用域，
     * 否则每次中途退出都会在平台侧留一张等到过期的票据。
     */
    fun cancelSignInAsync(ticket: SignInTicket?) {
        if (ticket == null) return
        scope.launch { api.cancel(ticket.authorizeId, ticket.verifier) }
    }

    // ------------------------------------------------------------------ 资料

    /** 拉昵称与余额。两个请求互相独立：余额挂了不该让昵称也消失 */
    suspend fun refresh() {
        val token = keyStore.getDeepSeekToken()
        if (token == null) {
            _state.value = State(signedIn = false)
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)

        val profileResult = runCatching { api.profile(token) }
        val balanceResult = runCatching { api.balance(token) }

        val expired = listOf(profileResult, balanceResult).any {
            (it.exceptionOrNull() as? DeepSeekAccountApi.AccountException)?.code ==
                DeepSeekAccountApi.AccountException.Code.EXPIRED
        }
        if (expired) {
            // token 被平台判失效：本地必须一起清掉，否则界面会一直显示「已登录」但每次都 401
            keyStore.putDeepSeekToken(null)
            _state.value = State(signedIn = false, error = "登录已过期，请重新登录")
            return
        }

        val profile = profileResult.getOrNull() ?: _state.value.profile
        val balance = balanceResult.getOrNull() ?: _state.value.balance
        val error = profileResult.exceptionOrNull()?.let { friendly(it) }
            ?: balanceResult.exceptionOrNull()?.let { friendly(it) }
        _state.value = State(
            signedIn = true,
            profile = profile,
            balance = balance,
            loading = false,
            error = error,
        )
    }

    /** 退出登录：平台侧吊销 + 本地清 token。供应商保留，用户下次登录还是同一份配置 */
    suspend fun signOut() {
        val token = keyStore.getDeepSeekToken()
        _state.value = _state.value.copy(loading = true)
        if (token != null) withContext(Dispatchers.IO) { api.logout(token) }
        keyStore.putDeepSeekToken(null)
        _state.value = State(signedIn = false)
    }

    // ------------------------------------------------------------------ 供应商

    /**
     * 保证「DeepSeek（账号）」这条供应商存在。
     *
     * 只在第一次登录时创建；已存在就原样保留（用户改过模型、温度、提示词，
     * 不能被一次登录冲掉）。**不抢占** activeProvider：用户可能正在用别的
     * 供应商，登录只是多一个选择；只有当前一个都没有时才自动切过去。
     */
    private suspend fun ensureProvider() {
        settingsRepo.update { s ->
            val existing = s.providers.firstOrNull { it.id == PROVIDER_ID }
            if (existing != null) {
                // 老版本的这条可能是「API Key 模式」，登录后统一改成账号模式
                if (existing.accountAuth && existing.baseUrl == DEFAULT_BASE_URL) return@update s
                s.copy(
                    providers = s.providers.map {
                        if (it.id == PROVIDER_ID) {
                            it.copy(baseUrl = DEFAULT_BASE_URL, accountAuth = true)
                        } else it
                    },
                )
            } else {
                val provider = Provider(
                    id = PROVIDER_ID,
                    name = if (language().lowercase().startsWith("zh")) "DeepSeek（账号）" else "DeepSeek (account)",
                    type = ProviderType.OPENAI_COMPAT,
                    baseUrl = DEFAULT_BASE_URL,
                    models = DEFAULT_MODELS,
                    defaultModel = DEFAULT_MODELS.first(),
                    contextWindow = 128_000,
                    accountAuth = true,
                    // 官方定价（元/百万 token，标准价）。只用于费用估算，不改实际计费
                    priceInPerMillion = 2.0,
                    priceOutPerMillion = 8.0,
                )
                s.copy(
                    providers = s.providers + provider,
                    activeProviderId = if (s.activeProvider == null) PROVIDER_ID else s.activeProviderId,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 设备

    /**
     * 登录设备 id：本机生成一次，之后一直用。
     *
     * 不放进设置 JSON（那会被导出到别的设备上，两台设备就撞成同一台），
     * 而是和 token 一起进加密区；退出登录也不删。
     */
    private fun deviceId(): String {
        keyStore.getDeepSeekDeviceId()?.takeIf { it.isNotBlank() }?.let { return it }
        val id = UUID.randomUUID().toString()
        keyStore.putDeepSeekDeviceId(id)
        return id
    }

    private fun deviceModel(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return "android-$abi"
    }

    private fun osVersion(): String = "Android ${Build.VERSION.RELEASE}"

    private fun friendly(t: Throwable): String = when (t) {
        is DeepSeekAccountApi.AccountException -> t.message
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun record(t: Throwable, what: String) {
        Logx.w(TAG, "$what：${t.javaClass.simpleName} ${t.message}")
        _state.value = _state.value.copy(loading = false, error = friendly(t))
    }
}
