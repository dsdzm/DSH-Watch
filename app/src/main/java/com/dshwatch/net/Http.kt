package com.dshwatch.net

import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 手表网络策略：
 *  - 连接池只留 2 条 idle 连接（内存受限）；
 *  - 不设 callTimeout（推理模型首 token 可能等很久）；
 *  - readTimeout 180s：两次收到数据之间的上限，防止死连接把进度条吊死；
 *  - 关闭 OkHttp 自带重试以外的额外重试，避免重复计费。
 */
object Http {

    val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    val SSE_MEDIA = "text/event-stream".toMediaType()

    private val pool = ConnectionPool(2, 1, TimeUnit.MINUTES)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(pool)
            .build()
    }

    /**
     * 后台一次性请求专用的客户端：**必须有硬超时**。
     *
     * 长期记忆整理、每日动态、标题这类请求和聊天不同 —— 聊天慢用户看得见
     * （字在一个个出来），而这些是"点一下然后什么都没有"。以前它们共用
     * [client]：不设 callTimeout、读超时 180 秒，于是网关吞了请求不回的
     * 时候，整个整理流程就吊在那儿 —— 界面上就是「一直正在整理」。
     *
     * 两次尝试（非流式 + 流式重试）各 45 秒，加起来还在用户能等住的范围内。
     * 连接池共用，不额外占内存。
     */
    val brief: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(40, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}