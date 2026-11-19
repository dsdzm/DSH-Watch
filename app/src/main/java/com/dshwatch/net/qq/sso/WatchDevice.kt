package com.dshwatch.net.qq.sso

/**
 * 一台「QQ 手表」的身份。
 *
 * 这些常量不是随便填的：服务端会用 apkId + 版本号 + 签名摘要 反查它认定的客户端。
 * 手表协议之所以能免签名服务，是因为登录包走 SSO v5 老链路，不需要 NTQQ 那种
 * libfekit 算出来的 energy。版本号必须跟着官方手表版走：报 2.0.8 会在换票阶段
 * 被服务端按「过期客户端」落库，回一个没有任何解释的 45。
 *
 * [guid] 与 [imei] 必须**跨次登录保持不变**。每次换一副新设备指纹，等于每次都在
 * 陌生设备上登录，风控会直接拦下来。
 */
internal data class WatchDevice(
    val guid: ByteArray,
    val imei: String,
    val apkId: String = APK_ID,
    val apkVersionName: String = APK_VERSION,
    val apkSignMd5: ByteArray = APK_SIGN,
    val ssoVersion: Int = SSO_VERSION,
    val subAppId: Long = SUB_APP_ID,
    val miscBitMap: Int = MISC_BITMAP,
    val subSigMap: Int = SUB_SIG_MAP,
    val osName: String = "android",
    val osVersion: String = "7.1.2",
    val simVendor: String = "China Mobile GSM",
    val apn: String = "wifi",
) {

    val apkIdBytes: ByteArray get() = apkId.toByteArray(Charsets.UTF_8)
    val apkVersionBytes: ByteArray get() = apkVersionName.toByteArray(Charsets.UTF_8)
    val imeiBytes: ByteArray get() = imei.toByteArray(Charsets.UTF_8)

    companion object {
        /** 手表 QQ（QQ Lite）的包名 */
        const val APK_ID = "com.tencent.qqlite"

        /** 跟着官方手表版走：老版本（2.0.8）在换票阶段会被判成不可信客户端 */
        const val APK_VERSION = "9.0.1"

        /** 签名的前 16 字节 MD5，服务端用来核对 apkId 与版本是否被改过 */
        const val APK_SIGN_HEX = "A6B745BF24A2C277527716F6F36EB68D"

        /** SSO v5。NTQQ 是 20，两者的包头与签名要求完全不同 */
        const val SSO_VERSION = 5

        const val SUB_APP_ID = 537065138L

        const val MISC_BITMAP = 16_252_796

        const val SUB_SIG_MAP = 0x10400

        /** 0x35 的产品类型，手表是 8 */
        const val PRODUCT_TYPE = 8

        val APK_SIGN: ByteArray get() = hexToBytes(APK_SIGN_HEX)

        /**
         * 造一副新设备指纹。
         *
         * imei 用 `86` 开头 15 位数字 —— 官方手表客户端上报的就是这个形状，
         * 换成 UUID 之类反而显眼。
         */
        fun create(random: java.util.Random = java.util.Random()): WatchDevice {
            val guid = ByteArray(16).also { random.nextBytes(it) }
            val sb = StringBuilder("86")
            repeat(13) { sb.append(random.nextInt(10)) }
            return WatchDevice(guid = guid, imei = sb.toString())
        }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filter { !it.isWhitespace() }
            require(clean.length % 2 == 0) { "十六进制长度必须是偶数" }
            return ByteArray(clean.length / 2) {
                clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        }
    }
}

// ---------------- 与设置互转 ----------------

/** 设备指纹的十六进制串，存进设置用 */
internal fun WatchDevice.guidHex(): String =
    guid.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

/**
 * 从设置里恢复设备指纹。
 *
 * 任一段长度不对就返回 null 而不是抛异常 —— 设置是用户可编辑的文本，
 * 手改坏了不该让整个设置页崩掉，按「还没有指纹」重新生成一份就行。
 *
 * imei 放宽到 14~16 位：早期版本生成的是 16 位，用户换过一版 APK 不该因此被
 * 当成「新设备」重新登录一次 —— 每换一副指纹都是一次陌生设备登录，风控会记账。
 */
internal fun com.dshwatch.domain.model.QqSettings.watchDeviceOrNull(): WatchDevice? {
    if (watchGuid.length != 32 || watchImei.length !in 14..16) return null
    if (!watchImei.all { it.isDigit() }) return null
    return runCatching { WatchDevice(WatchDevice.hexToBytes(watchGuid), watchImei) }.getOrNull()
}
