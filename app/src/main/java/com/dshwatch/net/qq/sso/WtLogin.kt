package com.dshwatch.net.qq.sso

/**
 * `wtlogin.login`：用扫码确认拿到的票据换真正的登录态。
 *
 * 这是扫码流程的最后一步。前面几步只是「证明你是这个号」，换来的是三件凭据：
 *
 * | 字段 | 作用 |
 * |---|---|
 * | `tmpPwd`（0x106） | 服务端认可的临时密码，等价于「这次扫码的凭据」 |
 * | `noPicSig`（0x16a） | 免验证码签名，少了它会要求打图形验证码 |
 * | `tgtQr`（0x318） | 二维码票据，绑定这次扫码的设备 |
 *
 * 换回来的是 A2 / D2 / D2Key：A2 用于续期，D2 + D2Key 才是后续所有业务请求的加密密钥。
 *
 * 这个包有 24 个 TLV，且顺序照官方客户端抄 —— 服务端虽然按 tag 取值，
 * 但顺序异常会被判成「非官方客户端」，表现为直接返回一个 45。
 */
internal object WtLogin {

    /** 主签名位图：手表版声明它支持哪些票据类型 */
    internal const val MAIN_SIG_MAP = 16_724_722

    /** 登录命令号 */
    const val COMMAND_ID = 0x0810

    internal val ALLOWED_DOMAINS = listOf(
        "tenpay.com", "openmobile.qq.com", "docs.qq.com", "connect.qq.com",
        "qzone.qq.com", "vip.qq.com", "gamecenter.qq.com", "qun.qq.com", "game.qq.com",
        "qqweb.qq.com", "office.qq.com", "ti.qq.com", "mail.qq.com", "mma.qq.com",
    )

    /**
     * 组装登录请求。
     *
     * @param session 本次请求的 ECDH 会话，响应用同一份密钥解
     * @param tgtgtKey 扫码确认响应里 0x1e 带回的那把 key —— 0x144 用它加密、
     *   0x119 用它解，两头必须是服务端这次给的那一份，不能自己派生。
     */
    fun packet(
        seq: Int,
        session: Ecdh.Session,
        device: WatchDevice,
        profile: DeviceProfile,
        uin: Long,
        tmpPwd: ByteArray,
        noPicSig: ByteArray,
        tgtQr: ByteArray,
        tgtgtKey: ByteArray,
        sessionId: ByteArray,
        ksid: ByteArray,
        qimei: ByteArray,
    ): ByteArray {
        val body = body(device, profile, uin, tmpPwd, noPicSig, tgtQr, seq, tgtgtKey = tgtgtKey)

        val oicq = SsoFrame.oicq(session, COMMAND_ID, body, uin = uin)
        return SsoFrame.packet(
            seq = seq,
            appId = device.subAppId,
            command = CMD_LOGIN,
            payload = oicq,
            sessionId = sessionId,
            device = device,
            ksid = ksid,
            qimei = qimei,
        )
    }

    /**
     * 登录包的 TLV 正文（不含 oicq 与 SSO 外壳）。
     *
     * 单独拆出来是为了能对着官方实现逐字段核对 —— 这 24 条的长度口径
     * 只要有一条错，服务端就只回一个数字，从客户端完全看不出是哪条。
     */
    internal fun body(
        device: WatchDevice,
        profile: DeviceProfile,
        uin: Long,
        tmpPwd: ByteArray,
        noPicSig: ByteArray,
        tgtQr: ByteArray,
        seq: Int,
        ip: ByteArray = profile.ip,
        random: Int = java.util.Random().nextInt(),
        tgtgtKey: ByteArray = profile.tgtgtKey,
    ): ByteArray {
        val map = TlvMap()
            .t18(appId = 16, uin = uin)
            .t1(uin = uin, ip = ip, random = random)
            .put(0x106, tmpPwd)
            .t116(device.miscBitMap, device.subSigMap)
            .t100(device.ssoVersion, device.subAppId, MAIN_SIG_MAP)
            .t107()
            .t142(device.apkIdBytes)
            .t144(profile, device.guid, tgtgtKey)
            .t145(device.guid)
            .t147(device.subAppId, device.apkVersionBytes, device.apkSignMd5)
            .put(0x16a, noPicSig)
            .t154(seq)
            .t141(device.simVendor.toByteArray(), device.apn.toByteArray())
            .t8()
            .t511(ALLOWED_DOMAINS)
            .t187(profile.macAddress.toByteArray())
            .t188(profile.androidId)
            .t194(profile.imsiMd5)
            .t191()
            .t202(profile.wifiBssid.toByteArray(), profile.wifiSsid.toByteArray())
            .t177(profile.buildTime, profile.sdkVersion.toByteArray())
            .t516()
            .t521()
            .put(0x318, tgtQr)

        val count = map.size
        val tlvs = map.encode()

        // TLV 表外面还要套一层：[2 命令 9][2 TLV 个数][TLV…]
        return ByteWriter(tlvs.size + 8).apply {
            short(9)      // TLV 表的用途标记，登录固定 9
            short(count)  // TLV 个数，多一个少一个服务端都会读错位
            bytes(tlvs)
        }.toByteArray()
    }

    /**
     * 解析登录响应。
     *
     * 明文形如 `[2 子命令][1 结果码][2 保留][TLV…]`：结果码 0 才算成功，
     * 其它值都是「还差一步」（需要验证码、需要短信、账号被冻结…）。
     */
    fun parse(plain: ByteArray, tgtgtKey: ByteArray, uin: Long): LoginResult {
        val r = ByteReader(plain)
        r.short() // 子命令
        val code = r.byte()
        r.short()
        val tlv = TlvMap.decode(r)

        if (code != 0) {
            return LoginResult.Failed(code, describe(code), tlv)
        }

        val tokens = runCatching { decodeTokens(tlv[0x119], tgtgtKey) }.getOrNull()
        return LoginResult.Success(
            uin = uin,
            nick = tokens?.nick.orEmpty(),
            d2 = tokens?.d2 ?: ByteArray(0),
            d2Key = tokens?.d2Key ?: ByteArray(0),
            a2 = tokens?.a2 ?: ByteArray(0),
            ksid = tokens?.ksid ?: ByteArray(0),
            tgt = tokens?.tgt ?: ByteArray(0),
        )
    }

    /**
     * 0x119 是「票据包」：整段用 tgtgtKey 加密，解开后前 2 字节是版本号，
     * 之后才是 TLV 表。
     */
    private fun decodeTokens(encrypted: ByteArray?, tgtgtKey: ByteArray): Tokens {
        if (encrypted == null) throw IllegalStateException("响应里没有 0x119，登录态没拿到")
        val plain = Tea.decrypt(encrypted, tgtgtKey)
        val r = ByteReader(plain)
        r.skip(2)
        val m = TlvMap.decode(r)

        val nick = m[0x11a]?.let { read11a(it) }.orEmpty()
        return Tokens(
            nick = nick,
            a2 = m[0x106] ?: ByteArray(0),
            d2 = m[0x143] ?: ByteArray(0),
            d2Key = m[0x305] ?: ByteArray(0),
            ksid = m[0x108] ?: ByteArray(0),
            // 0x10a：换票凭证。断线重连时拿它重新认证，没有就得重新扫码
            tgt = m[0x10a] ?: ByteArray(0),
        )
    }

    /** 0x11a：`[2 保留][1 年龄][1 性别][1 昵称长度][昵称]` */
    private fun read11a(data: ByteArray): String {
        val r = ByteReader(data)
        r.short()
        r.byte() // 年龄
        r.byte() // 性别
        val len = r.byte()
        return String(r.bytes(minOf(len, r.remaining)), Charsets.UTF_8)
    }

    private fun describe(code: Int): String = when (code) {
        1 -> "服务端要求再确认一次设备（返回码 1）"
        2 -> "需要图形验证码或滑块，这个号在新设备上登录过（返回码 2）"
        40 -> "账号被冻结（返回码 40）"
        45 -> "被拒：这个号不允许在当前客户端登录（返回码 45）"
        160, 239 -> "需要短信验证（返回码 $code）"
        204 -> "设备信息没通过校验，服务端认不出这是哪台机器（返回码 204）"
        else -> "登录被拒（返回码 $code）"
    }

    private class Tokens(
        val nick: String,
        val a2: ByteArray,
        val d2: ByteArray,
        val d2Key: ByteArray,
        val ksid: ByteArray,
        val tgt: ByteArray,
    )

    const val CMD_LOGIN = "wtlogin.login"
}

/** 登录结果 */
internal sealed class LoginResult {
    class Success(
        val uin: Long,
        val nick: String,
        val d2: ByteArray,
        val d2Key: ByteArray,
        val a2: ByteArray,
        val ksid: ByteArray,
        val tgt: ByteArray = ByteArray(0),
    ) : LoginResult()

    class Failed(
        val code: Int,
        val message: String,
        val tlv: Map<Int, ByteArray> = emptyMap(),
    ) : LoginResult()
}
