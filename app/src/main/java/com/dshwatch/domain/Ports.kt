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
}
