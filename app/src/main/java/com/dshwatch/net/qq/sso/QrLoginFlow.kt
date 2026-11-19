package com.dshwatch.net.qq.sso

import com.dshwatch.core.Logx
import java.io.Closeable

/** 一张待扫的二维码 */
internal class QrImage(val png: ByteArray, val sig: ByteArray)

/** 扫码状态 */
internal sealed class QrStatus {
    /** 还没人扫 */
    object Waiting : QrStatus()

    /** 手机扫了，等确认 */
    object Scanned : QrStatus()

    /** 二维码过期 */
    object Expired : QrStatus()

    /** 手机上取消了 */
    object Cancelled : QrStatus()

    /**
     * 手机已确认。
     *
     * 这四份数据是服务端认可这次扫码的凭据，还差最后一步提交才能换到真正的登录态。
     * 拿到它至少证明整条链路是通的。
     */
    class Confirmed(
        val uin: Long,
        val tmpPwd: ByteArray,
        val noPicSig: ByteArray,
        val tgtQr: ByteArray,
        val tgtgtKey: ByteArray,
    ) : QrStatus()
}

/**
 * 手表协议扫码登录。
 *
 * ```
 * 取二维码（0x31） ──> 手机扫码 ──> 轮询状态（0x12） ──> 已确认
 * ```
 *
 * 每个请求都是「新 TCP 连接 + 新 ECDH 密钥对 + 一次问答」，不保持长连接 ——
 * 登录阶段断开本来就该从头扫一次码，维护心跳得不偿失。
 */
internal class QrLoginFlow(
    private val device: WatchDevice,
    private val socket: SsoSocket = SsoSocket(),
) : Closeable {

    private var seq = (System.currentTimeMillis() and 0xFFFF).toInt()

    /** 4 字节会话 id，一次登录内保持不变 */
    private val sessionId = ByteArray(4).also { java.util.Random().nextBytes(it) }

    /** 设备画像由 guid 派生，登录包里那条加密的 0x144 就是它 */
    private val profile by lazy { DeviceProfile(device) }

    /** 首次登录没有 ksid，格式与官方一致即可 */
    private val ksid: ByteArray by lazy {
        "|${device.imei}|A8.2.7.27f6ea96".toByteArray(Charsets.UTF_8)
    }

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFFFF
        return seq
    }

    /**
     * 取一张二维码。
     *
     * PNG 是服务端直接生成的，解出来就能显示，不需要自己画。
     */
    fun requestQr(): QrImage {
        val plain = call(SUB_FETCH, tlvBody())
        return parseQr(plain)
    }

    /**
     * 查一次扫码状态。调用方每 1~2 秒一次，直到不再是 [QrStatus.Waiting]。
     */
    fun poll(sig: ByteArray): QrStatus {
        val plain = call(SUB_QUERY, pollBody(sig))
        return parseStatus(plain)
    }

    // ---------------- 组包 ----------------

    /**
     * 取码的业务正文。
     *
     * 注意 `short(6)` 是 TLV 个数，漏掉它后面全体错位一格 —— 服务端不会报错，
     * 只会安静地不给码。
     */
    private fun tlvBody(): ByteArray =
        fetchQrBody(device, profile, qrSize = QR_SIZE, qrMargin = QR_MARGIN, qrEc = QR_EC)

    private fun pollBody(sig: ByteArray): ByteArray = ByteWriter(sig.size + 32).apply {
        short(5)
        byte(1)
        int(8)
        int(16)
        short(sig.size)
        bytes(sig)
        long(0)
        byte(8)
        short(0)
        short(0)
    }.toByteArray()

    /**
     * 发一次请求并解出 TransEmp 正文。
     *
     * 每一次都用新的 ECDH 密钥对：公钥随包发出，响应用同一份共享密钥解。
     */
    private fun call(subCmd: Int, subBody: ByteArray): ByteArray {
        val command = 0x812

        val session = Ecdh.session()
        // code2d 那一层：加密标志 + 命令 + 定长头。
        // 这一层里**没有**时间戳 —— 时间戳在更外层，塞进来会让短长度字段少算 4 字节，
        // 服务端按长度读一条记录就会错位，表现为不报错也不回包。
        val code2d = ByteWriter(subBody.size + 64).apply {
            byte(2)
            short(43 + subBody.size + 1)
            short(subCmd)
            zeros(21)
            byte(3)
            short(0)
            short(50)
            int(0) // 子序号固定 0
            long(0) // 未登录 uin
            bytes(subBody)
            byte(3)
        }.toByteArray()

        // code2d 外面还有一层：加密标志 + 长度 + appId + 角色 + 时间戳
        val payload = ByteWriter(code2d.size + 32).apply {
            byte(0)
            short(code2d.size + 4)
            int(16)
            int(0x72)
            byte(0)
            byte(0)
            byte(0)
            int((System.currentTimeMillis() / 1000).toInt())
            bytes(code2d)
        }.toByteArray()

        val oicq = SsoFrame.oicq(session, command, payload)
        val packet = SsoFrame.packet(
            seq = nextSeq(),
            appId = device.subAppId,
            command = CMD_TRANS,
            payload = oicq,
            sessionId = sessionId,
            device = device,
            ksid = ksid,
            qimei = qimei,
        )
        val resp = SsoFrame.unwrap(roundTrip(packet), session.shareKey)
        return transEmpBody(resp)
    }

    /**
     * 用扫码确认拿到的票据换登录态。
     *
     * 和取码不是同一个命令（`wtlogin.login`），oicq 命令号也不同（0x0810），
     * 外面那层封装倒是完全一样 —— 所以共用 [SsoFrame.packet]。
     */
    fun login(info: QrStatus.Confirmed): LoginResult {
        val session = Ecdh.session()
        val packet = WtLogin.packet(
            seq = nextSeq(),
            session = session,
            device = device,
            profile = profile,
            uin = info.uin,
            tmpPwd = info.tmpPwd,
            noPicSig = info.noPicSig,
            tgtQr = info.tgtQr,
            tgtgtKey = info.tgtgtKey,
            sessionId = sessionId,
            ksid = ksid,
            qimei = qimei,
        )
        val resp = SsoFrame.unwrap(roundTrip(packet), session.shareKey)
        return WtLogin.parse(resp.plain, info.tgtgtKey, info.uin)
    }

    /** 一次「发一个包、读一个响应」，并把各种失败翻译成人话 */
    private fun roundTrip(packet: ByteArray): ByteArray = try {
        socket.roundTrip(packet)
    } catch (e: java.net.SocketTimeoutException) {
        // 服务端把包读不完时会一直等，不关连接也不报错，只能靠超时兜住
        throw IllegalStateException(
            "服务端没有回包（等待 ${SsoSocket.READ_TIMEOUT_MS / 1000} 秒超时）",
            e,
        )
    } catch (e: java.io.IOException) {
        throw IllegalStateException("连接被断开：${e.message}", e)
    }

    /**
     * 剥掉 TransEmp 那层定长头，返回业务正文。
     *
     * 明文形如 `[4 总长][1 加密标志][02][2 段长][2 命令][21 0][03][2][2][4 序号][8 uin][正文][03]`。
     * 这里的几个长度字段都不能拿来切正文 —— 实测有一段比真实内容短，
     * 照它切会截断。按固定字段走完，剩下的整体当正文，末尾那个 0x03 丢掉。
     */
    private fun transEmpBody(resp: SsoFrame.Response): ByteArray {
        val r = ByteReader(resp.plain)
        r.skip(4) // 整段长度
        val encrypt = r.byte()
        if (encrypt != 0) throw IllegalStateException("TransEmp 正文被加密（$encrypt），暂不支持")
        r.byte() // 固定 0x02
        r.short() // 段长
        val command = r.short()
        lastCommand = command
        r.skip(21)
        r.byte() // 固定 0x03
        r.short() // 0
        r.short() // 50
        r.int() // 子序号
        r.long() // uin
        val body = r.rest()
        return if (body.isNotEmpty() && body.last() == 0x03.toByte()) {
            body.copyOf(body.size - 1)
        } else {
            body
        }
    }

    /** 最近一次响应的子命令号，用来确认解码分支没走错 */
    private var lastCommand = 0

    // ---------------- 解析 ----------------

    private fun parseQr(plain: ByteArray): QrImage {
        val r = ByteReader(plain)
        r.short() // 0
        r.int() // 16
        val code = r.byte()
        if (code != 0) throw IllegalStateException("取二维码被拒，返回码 $code（原始：${plain.head()}）")
        val sig = r.bytes(r.short())
        r.short() // TLV 数量
        val tlv = TlvMap.decode(r)
        val png = tlv[0x17]
            ?: throw IllegalStateException("响应里没有 0x17（二维码图），原始：${plain.head()}")
        Logx.d(TAG, "二维码 ${png.size} 字节，sig ${sig.size} 字节")
        return QrImage(png, sig)
    }

    /**
     * 查状态。
     *
     * 这一段没有「短 0 / int 16」前缀，直接从变长头开始 —— 与取码不是同一套布局，
     * 照抄取码的偏移会把状态码读成乱码。
     */
    private fun parseStatus(plain: ByteArray): QrStatus {
        val r = ByteReader(plain)
        var len = r.short()
        if (len != 0) {
            len--
            if (r.byte() == 2) {
                r.long()
                len -= 8
            }
            if (len > 0) r.skip(len)
        }
        r.int() // app id
        return when (val code = r.byte()) {
            0x30 -> QrStatus.Waiting
            0x35 -> QrStatus.Scanned
            0x36 -> QrStatus.Cancelled
            0x11 -> QrStatus.Expired
            0x00 -> {
                val uin = r.long()
                r.int() // sig 生成时间
                r.short()
                val tlv = TlvMap.decode(r)
                val tmpPwd = tlv[0x18] ?: throw IllegalStateException("缺少 0x18")
                val noPicSig = tlv[0x19] ?: throw IllegalStateException("缺少 0x19")
                val tgtQr = tlv[0x65] ?: throw IllegalStateException("缺少 0x65")
                val tgtgtKey = tlv[0x1e] ?: throw IllegalStateException("缺少 0x1e")
                Logx.i(TAG, "扫码已确认，uin=$uin")
                QrStatus.Confirmed(uin, tmpPwd, noPicSig, tgtQr, tgtgtKey)
            }

            else -> throw IllegalStateException("未知扫码状态 0x${code.toString(16)}，原始：${plain.head()}")
        }
    }

    /**
     * QIMEI 一次登录内固定。
     *
     * 每个包换一个等于告诉服务端「同一台设备每次都在变编号」——
     * 取码和登录用同一份才像正常客户端。
     */
    private val qimei: ByteArray by lazy {
        ByteArray(18).also { java.util.Random().nextBytes(it) }
            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
            .toByteArray()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun ByteArray.head(): String =
        copyOfRange(0, minOf(48, size)).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private companion object {
        const val TAG = "QrLogin"

        /** 扫码走的命令名。和正式登录的 `wtlogin.login` 不是同一个 */
        const val CMD_TRANS = "wtlogin.trans_emp"

        /** 子命令：0x31 取码 */
        const val SUB_FETCH = 0x31

        /** 子命令：0x12 查扫码状态 */
        const val SUB_QUERY = 0x12

        /** 二维码规格。size 是模块边长，手表屏幕小，1 就够了 */
        const val QR_SIZE = 1
        const val QR_MARGIN = 2
        const val QR_EC = 1
    }
}

/**
 * 取码请求的业务正文。
 *
 * 拆成顶层函数只是为了能写单测：这段是整条链路上唯一「错了也不报错」的地方 ——
 * 字段少一个字节，服务端照样回包，只是回的内容不对，从客户端完全看不出是哪一项。
 *
 * 布局：
 * ```
 * [2 保留][4 appId][8 uin][1 标记][2 设备名长度 + 设备名][2 TLV 个数][TLV…]
 * ```
 */
internal fun fetchQrBody(
    device: WatchDevice,
    profile: DeviceProfile,
    qrSize: Int,
    qrMargin: Int,
    qrEc: Int,
): ByteArray {
    val guid = device.guid
    val tlvs = TlvMap()
        .t16(
            ssoVersion = device.ssoVersion,
            subAppId = device.subAppId,
            guid = guid,
            apkId = device.apkIdBytes,
            apkVersionName = device.apkVersionBytes,
            apkSignatureMd5 = device.apkSignMd5,
        )
        .t1b(size = qrSize, margin = qrMargin, ecLevel = qrEc)
        .t1d(device.miscBitMap)
        .t1f(
            osName = device.osName.toByteArray(),
            osVersion = device.osVersion.toByteArray(),
            simVendor = device.simVendor.toByteArray(),
            apn = device.apn.toByteArray(),
        )
        .t33(guid)
        .t35(WatchDevice.PRODUCT_TYPE)
        .encode()

    // 设备名不能留空。以前这里是长度 0 的空串：码能出、能扫、一路到「已确认」都正常，
    // 但手机点确认那一下会被挡 —— 手机 QQ 的确认页要显示「允许在 XXX 上登录」，
    // 名字为空就先弹「未识别到设备名称，请检验身份」，然后把这次登录判成风控。
    // 客户端这边只看到一个 45，看着像「号不允许第三方登录」，其实是被设备信息卡住。
    val deviceName = profile.deviceName.toByteArray(Charsets.UTF_8)

    return ByteWriter(256).apply {
        short(0)
        int(16)
        long(0)
        byte(8)
        short(deviceName.size)
        bytes(deviceName)
        short(6) // TLV 数量，漏掉这一项后面全体错位
        bytes(tlvs)
    }.toByteArray()
}
