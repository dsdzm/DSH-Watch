package com.dshwatch.domain

import com.dshwatch.domain.model.AppSettings

/**
 * 引擎依赖的最小接口。
 *
 * 抽出这两个接口的目的很具体：Gradle 的 JVM 单测跑不了 Android 的 Context，
 * 而 [ChatEngine] 又必须在单测里被完整驱动一遍（尤其是「首次发送」这条路径，
 * 历史上因为 startNew 自己 cancel 自己而出过「点发送毫无反应」的 bug）。
 * 有了接口，测试里塞两个假实现就能把整条链路跑起来。
 */
interface SettingsSource {
    suspend fun current(): AppSettings

    /** Agent 在用户点「总是允许」时需要写回配置 */
    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings
}

interface CredentialSource {
    fun getApiKey(providerId: String): String?
    fun getServerToken(providerId: String): String?

    /**
     * DeepSeek 账号 token（`x-dsh-auth-token`）。
     *
     * 默认返回 null：绝大多数实现（单测里的假凭据、QQ 桥接用的临时引擎）
     * 不关心账号登录，不该被迫实现它。真正接入账号的是 [com.dshwatch.data.settings.SecureKeyStore]。
     */
    fun getDeepSeekToken(): String? = null
}
