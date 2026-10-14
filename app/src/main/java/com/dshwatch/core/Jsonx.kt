package com.dshwatch.core

import kotlinx.serialization.json.Json

object Jsonx {

    /** 网络/存储统一使用的宽松配置 */
    val lenient: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    val pretty: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }
}
