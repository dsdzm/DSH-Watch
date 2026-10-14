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

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(2, 1, TimeUnit.MINUTES))
            .build()
    }
}
