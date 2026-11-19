package com.dshwatch.net.qq.sso

/**
 * TLV 表。QQ 登录包几乎全是 TLV 拼出来的，顺序也要保持插入顺序 ——
 * 服务端虽然按 tag 取值，但部分实现会因为顺序异常判为「非官方客户端」。
 */
internal class TlvMap {

    private val items = LinkedHashMap<Int, ByteArray>()

    fun put(tag: Int, body: ByteArray): TlvMap {
        items[tag] = body
        return this
    }

    fun put(tag: Int, body: ByteWriter.() -> Unit): TlvMap =
        put(tag, ByteWriter().apply(body).toByteArray())

    fun empty(tag: Int): TlvMap = put(tag, ByteArray(0))

    fun encode(): ByteArray {
        val w = ByteWriter()
        for ((tag, body) in items) {
            w.short(tag)
            w.short(body.size)
            w.bytes(body)
        }
        return w.toByteArray()
    }

    val size: Int get() = items.size

    companion object {
        /**
         * 解码 TLV 表。
         *
         * @param strict 为 true 时，某个 tag 的长度越界就抛异常；为 false 时遇到坏数据直接停下，
         *   返回已经解出来的部分。响应包里偶尔会跟一段非 TLV 的尾巴，后者更适合。
         */
        fun decode(r: ByteReader, strict: Boolean = false): Map<Int, ByteArray> {
            val out = LinkedHashMap<Int, ByteArray>()
            while (r.remaining >= 4) {
                val tag: Int
                val len: Int
                try {
                    tag = r.short()
                    len = r.short()
                } catch (e: IllegalStateException) {
                    break
                }
                if (len < 0 || r.remaining < len) {
                    if (strict) throw IllegalStateException("TLV 0x${tag.toString(16)} 长度 $len 越界")
                    break
                }
                out[tag] = r.bytes(len)
            }
            return out
        }
    }
}

// ---------------- 扫码登录用到的具体 TLV ----------------

/**
 * 0x16：客户端身份。服务端靠这一段判断「连上来的是哪个客户端」，
 * 版本号与 apkId 必须和签名里的对得上，否则直接返回 45。
 */
internal fun TlvMap.t16(
    ssoVersion: Int,
    subAppId: Long,
    guid: ByteArray,
    apkId: ByteArray,
    apkVersionName: ByteArray,
    apkSignatureMd5: ByteArray,
): TlvMap = put(0x16) {
    int(ssoVersion)
    int(16)
    int(subAppId.toInt())
    bytes(guid)
    lv16(apkId)
    lv16(apkVersionName)
    lv16(apkSignatureMd5)
}

/** 0x1b：二维码规格。size 是模块数，太小扫不出来，太大手表上显示不下 */
internal fun TlvMap.t1b(
    micro: Int = 0,
    version: Int = 0,
    size: Int = 3,
    margin: Int = 4,
    dpi: Int = 72,
    ecLevel: Int = 2,
    hint: Int = 2,
): TlvMap = put(0x1b) {
    int(micro)
    int(version)
    int(size)
    int(margin)
    int(dpi)
    int(ecLevel)
    int(hint)
    short(0)
}

/** 0x1d：能力位图 */
internal fun TlvMap.t1d(miscBitmap: Int): TlvMap = put(0x1d) {
    byte(1)
    int(miscBitmap)
    int(0)
    byte(0)
    int(0)
}

/** 0x1f：系统环境。手表协议这里写的是一台 Android 7.1.2 设备 */
internal fun TlvMap.t1f(
    isRoot: Boolean = false,
    osName: ByteArray,
    osVersion: ByteArray,
    simVendor: ByteArray,
    apn: ByteArray,
    networkType: Int = 2,
): TlvMap = put(0x1f) {
    byte(if (isRoot) 1 else 0)
    lv16(osName)
    lv16(osVersion)
    short(networkType)
    lv16(simVendor)
    lv16(ByteArray(0))
    lv16(apn)
}

/** 0x33：设备 GUID，和 0x16 里那个必须是同一份 */
internal fun TlvMap.t33(guid: ByteArray): TlvMap = put(0x33, guid)

/** 0x35：产品类型。手表协议是 8 */
internal fun TlvMap.t35(productType: Int): TlvMap = put(0x35) { int(productType) }

// ---------------- 登录（wtlogin.login）用到的 TLV ----------------
//
// 这一段和取码用的那几个是两套：取码只要「我是哪个客户端」，
// 登录还要「我运行在哪台设备上、上次的票据是什么」。少一个服务端都只回一个数字。

/** 0x18：登录用的 appId 与 uin */
internal fun TlvMap.t18(appId: Int, uin: Long): TlvMap = put(0x18) {
    short(1)
    int(1536)
    int(appId)
    int(0)
    int(uin.toInt())
    short(0)
    short(0)
}

/** 0x01：本机 IP 与时区偏移，服务端拿它判断登录地是否突变 */
internal fun TlvMap.t1(uin: Long, ip: ByteArray, random: Int = 0): TlvMap = put(0x01) {
    short(1)
    int(random)
    int(uin.toInt())
    int((System.currentTimeMillis() / 1000).toInt())
    bytes(ip)
    short(0)
}

/** 0x100：SSO 版本、子 appId、主签名位图 */
internal fun TlvMap.t100(ssoVersion: Int, subAppId: Long, mainSigMap: Int): TlvMap = put(0x100) {
    short(1)
    int(ssoVersion)
    int(16)
    int(subAppId.toInt())
    int(0)
    int(mainSigMap)
}

/** 0x107：图片验证码类型，没触发验证码时固定 0 */
internal fun TlvMap.t107(picType: Int = 0): TlvMap = put(0x107) {
    short(picType)
    byte(0)
    short(0)
    byte(1)
}

/** 0x116：能力位图 + 子签名位图 */
internal fun TlvMap.t116(miscBitmap: Int, subSigMap: Int): TlvMap = put(0x116) {
    byte(0)
    int(miscBitmap)
    int(subSigMap)
    byte(1)
    int(1600000226)
}

/** 0x142：apk 包名，限长 32 */
internal fun TlvMap.t142(apkId: ByteArray): TlvMap = put(0x142) {
    short(0)
    limited(apkId, 32)
}

/** 0x147：apkId 对应的版本名与签名摘要 */
internal fun TlvMap.t147(appId: Long, versionName: ByteArray, apkSign: ByteArray): TlvMap = put(0x147) {
    int(appId.toInt())
    limited(versionName, 32)
    limited(apkSign, 32)
}

/** 0x145：设备 GUID，和 0x16 里那份必须一致 */
internal fun TlvMap.t145(guid: ByteArray): TlvMap = put(0x145, guid)

/** 0x154：本次请求的序号，服务端回包会带回来 */
internal fun TlvMap.t154(seq: Int): TlvMap = put(0x154) { int(seq) }

/** 0x141：SIM 卡与 APN */
internal fun TlvMap.t141(simInfo: ByteArray, apn: ByteArray): TlvMap = put(0x141) {
    short(1)
    lv16(simInfo)
    short(2) // 网络类型：wifi
    lv16(apn)
}

/** 0x08：本地 id，随便给个稳定值即可 */
internal fun TlvMap.t8(localId: Int = 2052): TlvMap = put(0x08) {
    short(0)
    int(localId)
    short(0)
}

/** 0x511：允许登录的域名清单，少写几个不影响登录 */
internal fun TlvMap.t511(domains: List<String>): TlvMap = put(0x511) {
    short(domains.size)
    for (d in domains) {
        val b = d.toByteArray(Charsets.UTF_8)
        byte(1)
        short(b.size)
        bytes(b)
    }
}

/** 0x187 / 0x188：MAC 与 Android ID 的 MD5 */
internal fun TlvMap.t187(mac: ByteArray): TlvMap = put(0x187) { bytes(md5(mac)) }

internal fun TlvMap.t188(androidId: ByteArray): TlvMap = put(0x188) { bytes(md5(androidId)) }

/** 0x194：IMSI 的 MD5 */
internal fun TlvMap.t194(imsiMd5: ByteArray): TlvMap = put(0x194, imsiMd5)

/** 0x191：设备锁开关，关着就是 0 */
internal fun TlvMap.t191(k: Int = 0): TlvMap = put(0x191) { byte(k) }

/** 0x202：当前 WiFi 的 BSSID / SSID */
internal fun TlvMap.t202(bssid: ByteArray, ssid: ByteArray): TlvMap = put(0x202) {
    limited(bssid, 16)
    limited(ssid, 32)
}

/** 0x177：客户端构建时间与 SDK 版本 */
internal fun TlvMap.t177(buildTime: Int, sdkVersion: ByteArray): TlvMap = put(0x177) {
    byte(1)
    int(buildTime)
    short(sdkVersion.size)
    bytes(sdkVersion)
}

/** 0x516 / 0x521：两个固定值，长度写死在包体里 */
internal fun TlvMap.t516(): TlvMap = put(0x516) { int(0) }

internal fun TlvMap.t521(i: Int = 8): TlvMap = put(0x521) {
    int(i)
    short(0)
}

/**
 * 0x144：设备画像。整段用 tgtgtKey 加密 —— 这是登录包里唯一加密的 TLV，
 * 解不开服务端就不知道你是什么设备。
 */
internal fun TlvMap.t144(profile: DeviceProfile, guid: ByteArray, tgtgtKey: ByteArray): TlvMap {
    val inner = ByteWriter(256).apply {
        short(5) // 子 TLV 个数
        t109(profile.device.imeiBytes)
        t52d(profile.deviceInfoProto())
        t124(
            osType = "android".toByteArray(),
            osVersion = profile.device.osVersion.toByteArray(),
            simInfo = profile.device.simVendor.toByteArray(),
            apn = profile.device.apn.toByteArray(),
        )
        t128(
            buildModel = profile.model.toByteArray(),
            guid = guid,
            buildBrand = profile.brand.toByteArray(),
        )
        t16e(profile.model.toByteArray())
    }.toByteArray()
    return put(0x144, Tea.encrypt(inner, tgtgtKey))
}

/** 0x109：IMEI 的 MD5。名字叫 androidId 是历史原因，实际传的是 IMEI */
private fun ByteWriter.t109(imei: ByteArray) {
    short(0x109)
    short(16)
    bytes(md5(imei))
}

/** 0x52d：TCG DeviceInfo（protobuf 原文） */
private fun ByteWriter.t52d(devInfo: ByteArray) {
    short(0x52d)
    short(devInfo.size)
    bytes(devInfo)
}

/** 0x124：系统与运营商信息，各字段限长 16 */
private fun ByteWriter.t124(osType: ByteArray, osVersion: ByteArray, simInfo: ByteArray, apn: ByteArray) {
    val body = ByteWriter(64).apply {
        limited(osType, 16)
        limited(osVersion, 16)
        short(2) // wifi
        limited(simInfo, 16)
        limited(ByteArray(0), 16)
        limited(apn, 16)
    }.toByteArray()
    short(0x124)
    short(body.size)
    bytes(body)
}

/** 0x128：GUID 的来源与可用性标记 */
private fun ByteWriter.t128(buildModel: ByteArray, guid: ByteArray, buildBrand: ByteArray) {
    val body = ByteWriter(96).apply {
        short(0)
        byte(0) // guid 不来自文件
        byte(1) // guid 可用
        byte(0) // guid 未变更
        int(1 shl 24) // guidFlag
        limited(buildModel, 32)
        limited(guid, 16)
        limited(buildBrand, 16)
    }.toByteArray()
    short(0x128)
    short(body.size)
    bytes(body)
}

/** 0x16e：机型，明文 */
private fun ByteWriter.t16e(model: ByteArray) {
    short(0x16e)
    short(model.size)
    bytes(model)
}

/** 「2 字节长度 + 内容」，超长直接截断 —— 对应 Go 侧的 WriteTlvLimitedSize */
private fun ByteWriter.limited(data: ByteArray, max: Int) {
    val n = minOf(data.size, max)
    short(n)
    bytes(data.copyOfRange(0, n))
}

private fun md5(b: ByteArray): ByteArray = java.security.MessageDigest.getInstance("MD5").digest(b)
