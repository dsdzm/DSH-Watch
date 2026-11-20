package com.dshwatch.net.qq.msf

import com.dshwatch.core.Logx
import com.dshwatch.net.qq.sso.ByteWriter
import com.dshwatch.net.qq.sso.ConfigPush
import com.dshwatch.net.qq.sso.DeviceProfile
import com.dshwatch.net.qq.sso.Ecdh
import com.dshwatch.net.qq.sso.MsfFrame
import com.dshwatch.net.qq.sso.QqSession
import com.dshwatch.net.qq.sso.StatSvcRegister
import com.dshwatch.net.qq.sso.SsoFrame
import com.dshwatch.net.qq.sso.TlvMap
import com.dshwatch.net.qq.sso.WatchDevice
import com.dshwatch.net.qq.sso.WtLogin
import com.dshwatch.net.qq.sso.t100
import com.dshwatch.net.qq.sso.t116
import com.dshwatch.net.qq.sso.t141
import com.dshwatch.net.qq.sso.t142
import com.dshwatch.net.qq.sso.t144
import com.dshwatch.net.qq.sso.t145
import com.dshwatch.net.qq.sso.t147
import com.dshwatch.net.qq.sso.t154
import com.dshwatch.net.qq.sso.t177
import com.dshwatch.net.qq.sso.t18
import com.dshwatch.net.qq.sso.t187
import com.dshwatch.net.qq.sso.t188
import com.dshwatch.net.qq.sso.t194
import com.dshwatch.net.qq.sso.t202
import com.dshwatch.net.qq.sso.t511
import com.dshwatch.net.qq.sso.t8
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import kotlin.random.Random

/**
 * QQ 的消息长连接（MSF）。
 *
 * 扫码换票用的是「一问一答就断」的短连接，能证明身份，但收不到任何消息 ——
 * 消息是服务端推过来的，必须有一条一直开着的 TCP。
 *
 * 这条链路的手续顺序是死的：
 *
 * ```
 * TCP 连上
 *   → wtlogin.exchange_emp   拿 tgt 重新认证（0x0A 包，全 0 密钥）
 *   → StatSvc.register       登记上线（0x0B 包，d2Key 加密，JCE 正文）
 *   → Heartbeat.Alive        心跳（0x0A 包，不加密）
 *   → MessageSvc.PbGetMsg    取私聊（0x0B 包，d2Key 加密，protobuf 正文）
 *   → MessageSvc.PbSendMsg   发消息
 * ```
 *
 * 中间那步 register 不能省：exchange_emp 只证明票据有效，服务端还不知道
 * 这条 TCP 属于谁、要不要给它推消息。少了它，业务包一律回 **-10008**
 * （「这条连接没登录」），改包体是改不出来的。
 *
 * **所有方法都是阻塞的**，调用方负责放到 IO 线程。
 */
internal class MsfClient(
    private val session: QqSession,
    private val device: WatchDevice,
    private val profile: DeviceProfile,
    private val host: String = "msfwifi.3g.qq.com",
    private val port: Int = 8080,
) : Closeable {

    private var socket: Socket? = null
    private var seq = Random.nextInt(0x1000, 0x7FFF)

    /** 服务端在响应里回显的会话 id，后续请求要带回去 */
    /**
     * 会话 id：**由客户端生成、全程不变**，4 个随机字节。
     *
     * 不能留空，也不该拿服务端回包里的那个值 —— mirai 里叫
     * `outgoingPacketSessionId`，一次连接一个，服务端只是回显。
     * 留空时 0x0A 的注册包会被服务端直接丢掉，症状是「一个包都不回」。
     */
    private val sessionId: ByteArray = ByteArray(4).also { java.util.Random().nextBytes(it) }

    /** 取消息的游标。第一次为空，之后用服务端下发的那个 */
    var syncCookie: ByteArray = ByteArray(0)
        private set

    /** 等响应期间被跳过的推送包，攒着给上层解析 */
    private val pending = java.util.ArrayDeque<MsfFrame.Response>()

    /**
     * 最近一次收到的回包长什么样。
     *
     * 只用于失败时把「到底收到了什么」写进错误信息：命令名对不上说明收错了包，
     * 命令名对得上而码不对才是服务端真的拒了 —— 这两种情况的下一步完全不同。
     */
    private var lastReply: String = "没有收到任何包"

    /** 取走攒下的推送包（取完即清空） */
    fun drainPushes(): List<MsfFrame.Response> {
        val out = pending.toList()
        pending.clear()
        return out
    }

    /**
     * 建连 + 换票。失败抛异常并带上服务端的原话。
     *
     * 这一步完成后连接只是「被认出来是谁」，还没上线 ——
     * 收消息前必须紧接着调 [register]，否则业务包一律 -10008。
     */
    fun open(): Unit = synchronized(this) {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = READ_TIMEOUT_MS
        socket = s
        try {
            exchange()
        } catch (t: Throwable) {
            close()
            throw t
        }
    }

    /** 建连 + 换票 + 上线，一把做完。给不需要分步计时的调用方用 */
    fun start() {
        open()
        register()
    }

    /**
     * `wtlogin.exchange_emp` 子命令 11：拿上次留下的 tgt 重新认证这条连接。
     *
     * 注意 0x144 的加密钥匙是 `MD5(d2Key)`，不是扫码时那把 tgtgtKey ——
     * 换票链条上这两把钥匙长得像但完全不同，用错的表现是服务端回 -10008。
     */
    private fun exchange() {
        val key = md5(session.d2Key)
        val map = TlvMap()
            .t18(appId = 16, uin = session.uin)
            .t100(device.ssoVersion, device.subAppId, WtLogin.MAIN_SIG_MAP)
            .put(0x10a, session.tgt)
            .t116(device.miscBitMap, device.subSigMap)
            .put(0x108, session.ksid)
            .t142(device.apkIdBytes)
            .put(0x143, session.d2)
            .t144(profile, device.guid, key)
            .t145(device.guid)
            .t147(device.subAppId, device.apkVersionBytes, device.apkSignMd5)
            .t154(seq)
            .t141(device.simVendor.toByteArray(), device.apn.toByteArray())
            .t8()
            .t511(WtLogin.ALLOWED_DOMAINS)
            .t177(profile.buildTime, profile.sdkVersion.toByteArray())
            .t187(profile.macAddress.toByteArray())
            .t188(profile.androidId)
            .t194(profile.imsiMd5)
            .t202(profile.wifiBssid.toByteArray(), profile.wifiSsid.toByteArray())

        val body = ByteWriter(1024).apply {
            short(11) // 子命令：快速登录
            short(map.size)
            bytes(map.encode())
        }.toByteArray()

        val ecdh = Ecdh.session()
        val payload = SsoFrame.oicq(ecdh, WtLogin.COMMAND_ID, body, uin = session.uin)
        val packet = SsoFrame.packet(
            seq = nextSeq(),
            appId = device.subAppId,
            command = "wtlogin.exchange_emp",
            payload = payload,
            sessionId = sessionId,
            device = device,
            ksid = session.ksid,
            qimei = ByteArray(0),
            uin = session.uin,
            tgt = session.tgt,
        )

        val resp = SsoFrame.unwrap(writeAndRead(packet, null), ecdh.shareKey)
        remember(resp.command, ByteArray(0))
        // 0x0A 响应正文：short 子命令、byte 返回码、short 保留、TLV 表
        val r = com.dshwatch.net.qq.sso.ByteReader(resp.plain)
        r.short()
        val code = r.byte()
        if (code != 0) {
            throw IllegalStateException("换票被拒（返回码 $code）。这条连接没认证成功，需要重新扫码")
        }
        Logx.i(TAG, "exchange_emp 通过")
    }

    /**
     * `StatSvc.register`：把这条 TCP 登记成「某个人在线」。
     *
     * 缺了它，服务端收得到包、也回得出错，但所有业务命令一律 -10008。
     *
     * ## 为什么要试三种
     *
     * 服务端对这一包的处理在不同通道上不一样，而**失败时它常常一个字节都不回**，
     * 只能靠换通道把结论试出来：
     *
     * | 方案 | 依据 |
     * |---|---|
     * | `0x0A` + D2 | mirai 的做法：外层带 d2、正文用 d2Key 加密 |
     * | `0x0B` + D2 | 业务通道。这条通道回包最稳定，只是有可能被判「未登录」 |
     * | `0x0A` + 空钥 | 和换票同一个形态，用来区分「D2 不被认」还是「包本身不被认」 |
     *
     * 返回一行说明（成功时含耗时与心跳间隔）；全部失败时抛异常，异常里带每一档的结果。
     */
    fun register(): String {
        val log = ArrayList<String>()
        for (plan in REGISTER_PLANS) {
            val t0 = System.currentTimeMillis()
            try {
                val resp = attemptRegister(plan)
                val code = StatSvcRegister.replyCode(resp)
                if (code != 0) {
                    log += "${plan.label} 被拒 replyCode=$code"
                    continue
                }
                val ms = System.currentTimeMillis() - t0
                val interval = StatSvcRegister.helloInterval(resp)
                return buildString {
                    append("通过（").append(plan.label).append("，").append(ms).append("ms")
                    if (interval > 0) append("，心跳 ").append(interval).append("s")
                    append("）")
                    if (log.isNotEmpty()) append("；此前：").append(log.joinToString("；"))
                }
            } catch (t: Throwable) {
                val why = t.message?.replace('\n', ' ')?.take(120) ?: t.javaClass.simpleName
                log += "${plan.label} $why"
                if (socket?.isClosed != false) break
            }
        }
        throw IllegalStateException("三种通道都没有回音：\n  " + log.joinToString("\n  "))
    }

    private class RegisterPlan(
        val label: String,
        val type: Int,
        val encrypt: MsfFrame.Encrypt,
        /** 外层额外数据是否放 d2（只有 0x0A+D2 需要） */
        val withD2: Boolean,
        val timeoutMs: Int,
    )

    private fun attemptRegister(plan: RegisterPlan): ByteArray {
        val body = StatSvcRegister.online(
            uin = session.uin,
            guid = device.guid,
            deviceName = profile.deviceName,
            osVersion = profile.osVersion,
            sdkLevel = profile.sdkLevel,
        )
        val seq = nextSeq()
        val packet = MsfFrame.build(
            type = plan.type,
            encrypt = plan.encrypt,
            key = session.d2Key,
            seq = seq,
            appId = device.subAppId,
            uin = session.uin,
            command = COMMAND_REGISTER,
            body = body,
            outerExtra = if (plan.withD2) session.d2 else ByteArray(0),
            innerExtra = if (plan.type == MsfFrame.PROTOCOL_LOGIN) session.tgt else ByteArray(0),
            imei = device.imeiBytes,
            ksid = session.ksid,
            sessionId = sessionId,
        )
        val raw = writeAndRead(packet, seq, COMMAND_REGISTER, plan.timeoutMs)
        val resp = MsfFrame.parse(raw, session.d2Key)
        val peeled = SsoFrame.peelOicq(resp.body)?.takeIf { it.isNotEmpty() }
        return when {
            peeled != null && StatSvcRegister.looksLikeReply(peeled) -> {
                Logx.i(TAG, "register 回包是 oicq 包装")
                peeled
            }
            StatSvcRegister.looksLikeReply(resp.body) -> resp.body
            else -> throw IllegalStateException(
                "回包认不出（码 ${resp.code}、正文 ${resp.body.size}B ${resp.body.hex(40)}）",
            )
        }
    }

    /** 心跳。服务端用这个判断客户端还在不在，一分钟一次就够 */
    fun heartbeat() {
        val seq = nextSeq()
        val packet = MsfFrame.build(
            type = MsfFrame.PROTOCOL_LOGIN,
            encrypt = MsfFrame.Encrypt.None,
            key = ByteArray(16),
            seq = seq,
            appId = device.subAppId,
            uin = session.uin,
            command = "Heartbeat.Alive",
            body = ByteArray(0),
            imei = device.imeiBytes,
            ksid = session.ksid,
        )
        // 心跳是 0x0A 的裸包，序号不像业务包那样严格回显，按命令名挑
        val resp = MsfFrame.parse(
            writeAndRead(packet, null, "Heartbeat.Alive"),
            session.d2Key,
        )
        if (resp.code != 0) throw IllegalStateException("心跳返回 ${resp.code}")
    }

    /**
     * 取一次私聊。
     *
     * 这是「拉」而不是「推」：服务端有新消息会先发通知包，客户端再来要。
     * 我们暂时不解析通知，直接按秒轮询，简单且够用。
     */
    fun pollPrivate(): List<ChatMsg> {
        val req = ProtoWriter().apply {
            int32(1, 0) // syncFlag: START
            if (syncCookie.isNotEmpty()) bytes(2, syncCookie)
            int32(3, 1) // rambleFlag
            int32(4, 20) // latestRambleNumber
            int32(5, 3) // otherRambleNumber
            int32(6, 1) // onlineSyncFlag
            int32(7, 1) // contextFlag
            int32(8, 0) // whisperSessionId
            int32(9, 1) // msgReqType: c2c
        }.toByteArray()

        val resp = call("MessageSvc.PbGetMsg", req)
        val fields = ProtoReader.parse(resp)
        val result = fields[1]?.firstOrNull()?.asInt ?: 0
        if (result != 0) {
            throw IllegalStateException(
                "取消息被拒（result=$result ${ProtoReader.text(fields, 2)}）",
            )
        }
        fields[3]?.firstOrNull()?.data?.let { if (it.isNotEmpty()) syncCookie = it }

        val out = ArrayList<ChatMsg>()
        fields[5]?.forEach { pair ->
            val p = ProtoReader.parse(pair.data)
            val peer = p[2]?.firstOrNull()?.asLong ?: 0L
            p[4]?.forEach { m -> parseMsg(m, peer)?.let(out::add) }
        }
        return out
    }

    /** 发一条纯文本私聊。返回服务端给的时间戳，0 表示失败 */
    fun sendText(toUin: Long, text: String): Int {
        val msgSeq = (Random.nextInt(1, 0x7FFF))
        val msgRand = Random.nextInt()
        val req = ProtoWriter().apply {
            message(1) { message(1) { varint(1, toUin) } } // routingHead.c2c.toUin
            message(2) {
                int32(1, 1) // pkgNum
                int32(2, 0) // pkgIndex
                int32(3, 0) // divSeq
            }
            message(3) { // msgBody
                message(1) { // richText
                    message(2) { // elems[0]
                        message(1) { string(1, text) } // elem.text.str
                    }
                }
            }
            int32(4, msgSeq)
            int32(5, msgRand)
        }.toByteArray()

        val resp = call("MessageSvc.PbSendMsg", req)
        val fields = ProtoReader.parse(resp)
        val result = fields[1]?.firstOrNull()?.asInt ?: -1
        if (result != 0) {
            throw IllegalStateException(
                "发送失败（result=$result ${ProtoReader.text(fields, 2)}）",
            )
        }
        return fields[3]?.firstOrNull()?.asInt ?: 0
    }

    // ---------------- 收发 ----------------

    /**
     * 发一个业务包并等响应。
     *
     * @param login 用 0x0A 还是 0x0B 包。
     *   `StatSvc.register` 虽然名字像业务命令，但它是**登录型包**（0x0A）：
     *   外层要带 d2、正文用 d2Key 加密。写成 0x0B 服务端一律回 -10008，
     *   因为 0x0B 是「已登录之后」的通道，而这一步正是去建立登录态的。
     */
    private fun call(command: String, body: ByteArray, login: Boolean = false): ByteArray {
        val seq = nextSeq()
        val packet = MsfFrame.build(
            type = if (login) MsfFrame.PROTOCOL_LOGIN else MsfFrame.PROTOCOL_SIMPLE,
            encrypt = MsfFrame.Encrypt.D2,
            key = session.d2Key,
            seq = seq,
            appId = device.subAppId,
            uin = session.uin,
            command = command,
            body = body,
            // 0x0A 的外层额外数据放 d2；0x0B 那一格是序号，由 build 自己写
            outerExtra = if (login) session.d2 else ByteArray(0),
            // SSO 头里的额外数据：只有登录型包（0x0A）放 tgt，
            // 服务端靠它把包和登记过的那条连接对上；业务型包（0x0B）这一格是签名位
            innerExtra = if (login) session.tgt else ByteArray(0),
            imei = device.imeiBytes,
            ksid = session.ksid,
            sessionId = sessionId,
        )
        // 序号或命令名任一匹配就算回包：0x0A 的响应不一定回显序号，
        // 0x0B 的响应不一定回显命令名。两个条件同时给，比只认一个稳。
        val resp = MsfFrame.parse(
            writeAndRead(packet, seq, command),
            session.d2Key,
        )
        lastReply = "收到「${resp.command.ifEmpty { "无命令名" }}」" +
            "码 ${resp.code}、正文 ${resp.body.size}B" +
            (if (login && resp.command.isNotEmpty() && resp.command != command) {
                "（不是 $command 的回包）"
            } else {
                ""
            })
        remember(resp.command, resp.sessionId)
        if (resp.code != 0) {
            throw IllegalStateException("$command 返回 ${resp.code}（$lastReply）")
        }
        return resp.body
    }

    private fun remember(command: String, sid: ByteArray) {
        // 只记日志：会话 id 由我们生成，服务端回包里的那个不拿来回填
        Logx.d(TAG, "收到 $command（服务端会话 id ${sid.size} 字节）")
    }

    /**
     * 写一个包，读回和它对应的响应。
     *
     * 长连接不是「发一个收一个」：服务端会随时插播推送包（新消息通知、
     * 配置下发、强制下线），它们和响应混在同一条流里。所以这里按序号挑，
     * 推送先攒进 [pending]，免得把推送当成响应用。
     *
     * @param expectSeq 期望的响应序号；0x0A 的响应不一定回显序号，这时传 null
     * @param expectCommand 期望的命令名。服务端会在响应之前插播推送包，
     *   它们同样是「码 0、有正文」的合法包 —— 只看序号或只看「收到了一个包」，
     *   就会把推送的内容当成 register 的 JCE 回包去解析，报一堆莫名其妙的越界。
     *   命令名为空视为匹配（有的版本不回显命令名，不能因此死等）。
     */
    private fun writeAndRead(
        packet: ByteArray,
        expectSeq: Int?,
        expectCommand: String? = null,
        timeoutMs: Int = READ_TIMEOUT_MS,
    ): ByteArray = synchronized(this) {
        val s = socket ?: throw IllegalStateException("连接还没建立")
        // 每种方案自己决定等多久：注册要试三档，不能每档都耗 15 秒
        s.soTimeout = timeoutMs
        s.getOutputStream().apply {
            write(packet)
            flush()
        }
        if (expectSeq == null && expectCommand == null) return readPacket(s.getInputStream())

        var skipped = 0
        val seen = ArrayList<String>()
        // 认不出的包不能当成响应返回：它既可能是我们还没支持的推送，也可能是真畸形。
        // 记下原始字节继续等，等不到时才把它连十六进制一起报出来。
        var unparsed: String? = null
        while (true) {
            val raw = try {
                readPacket(s.getInputStream())
            } catch (e: SocketTimeoutException) {
                // 超时是最需要诊断的场景：要么服务端压根没回，要么回的包我们认不出被跳过了。
                // 把这两种情况分开说清楚，并把认不出的那个包的前 32 字节带上。
                val detail = if (seen.isEmpty()) {
                    "，期间一个包都没收到"
                } else {
                    "，期间收到 ${seen.size} 个包但都不是：${seen.joinToString("、")}"
                }
                throw IllegalStateException(
                    "等「${expectCommand ?: "回包"}」超时 ${timeoutMs / 1000}s" + detail +
                        (unparsed?.let { "；最后一个认不出的包 $it" } ?: ""),
                    e,
                )
            }
            val parsed = runCatching { MsfFrame.parse(raw, session.d2Key) }
                .getOrElse { e ->
                    val why = e.message?.replace('\n', ' ')?.take(140).orEmpty()
                    unparsed = "${raw.size}B ${raw.hex(40)}（$why）"
                    null
                }
            if (parsed != null) {
                val seqOk = expectSeq != null && parsed.seq == expectSeq
                val cmdOk = expectCommand != null &&
                    (parsed.command.isEmpty() || parsed.command.equals(expectCommand, ignoreCase = true))
                if (seqOk || cmdOk) return raw

                pending.addLast(parsed)
                seen += "${parsed.command.ifEmpty { "无命令名" }}(${parsed.seq})"
                Logx.d(TAG, "插播推送 ${parsed.command}（seq ${parsed.seq}），先攒着")
                // 配置下发必须回执，不回服务端就不再往下走（注册会一直没回音）
                if (parsed.command == ConfigPush.COMMAND) ackConfigPush(parsed)
            } else {
                seen += "认不出的包"
            }
            if (++skipped > MAX_PUSH_SKIP) {
                throw IllegalStateException(
                    "等 $expectCommand 的回包，连续 ${seen.size} 个都不是：" +
                        seen.joinToString("、") +
                        (unparsed?.let { "；最后一个认不出的包：$it" } ?: ""),
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("unreachable")
    }

    /** 只发不收：推送回执发完继续等我们自己的回包 */
    private fun send(packet: ByteArray) {
        val s = socket ?: throw IllegalStateException("连接还没建立")
        s.getOutputStream().apply {
            write(packet)
            flush()
        }
    }

    /**
     * 回执 `ConfigPushSvc.PushResp`。
     *
     * 服务端在换票成功后立刻推 `PushReq`（服务器列表、文件服务器地址），
     * 客户端不回执它就一直等，之后的 `StatSvc.register` 不会有任何回应 ——
     * 自检看到的是「收到 3 个推送包，然后彻底安静」，很容易误判成网络问题。
     */
    private fun ackConfigPush(push: MsfFrame.Response) {
        val req = runCatching { ConfigPush.parse(push.body) }.getOrNull()
        if (req == null) {
            Logx.w(TAG, "ConfigPush 正文认不出（${push.body.size}B），没法回执")
            return
        }
        val packet = MsfFrame.build(
            type = MsfFrame.PROTOCOL_SIMPLE,
            encrypt = MsfFrame.Encrypt.D2,
            key = session.d2Key,
            seq = push.seq,
            appId = device.subAppId,
            uin = session.uin,
            command = ConfigPush.RESPONSE,
            body = ConfigPush.respBody(req.type, req.seq),
            imei = device.imeiBytes,
            sessionId = sessionId,
        )
        runCatching { send(packet) }
            .onSuccess { Logx.d(TAG, "已回执 ConfigPush.PushResp（type=${req.type} seq=${req.seq}）") }
            .onFailure { Logx.w(TAG, "回执 PushResp 失败：${it.message}") }
    }

    /**
     * 读一个完整包。
     *
     * 服务端会插播推送包（OnlinePush / PushNotify），它们和响应混在同一个流里，
     * 所以这里只负责切包，配不配得上序号由调用方判断。
     */
    private fun readPacket(input: InputStream): ByteArray {
        val head = readFully(input, 4)
        val total = be32(head)
        if (total < 4 || total > MAX_PACKET) throw IllegalStateException("响应长度异常：$total")
        return head + readFully(input, total - 4)
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw IllegalStateException("连接被关闭：只读到 $off/$n 字节")
            off += r
        }
        return out
    }

    private fun be32(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun nextSeq(): Int = synchronized(this) {
        seq += 1
        if (seq > 0x7FFFFFF0) seq = 0x1000
        seq
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    // ---------------- 消息解析 ----------------

    data class ChatMsg(val peerUin: Long, val text: String, val atMe: Boolean = false)

    /**
     * 从 Msg 里抠出纯文本。
     *
     * 结构是 `Msg{head, body{richText{elems[{text{str}}]}}}`。图片、表情、
     * 小程序这些元素没有 text 字段，跳过即可 —— 它们在这条链路上暂时不处理。
     */
    private fun parseMsg(field: ProtoField, peer: Long): ChatMsg? {
        val msg = ProtoReader.parse(field.data)
        val body = msg[2]?.firstOrNull() ?: return null
        val msgBody = ProtoReader.parse(body.data)
        val rich = msgBody[1]?.firstOrNull() ?: return null
        val richText = ProtoReader.parse(rich.data)

        val sb = StringBuilder()
        richText[2]?.forEach { elem ->
            val e = ProtoReader.parse(elem.data)
            e[1]?.firstOrNull()?.let { textElem ->
                val t = ProtoReader.parse(textElem.data)
                t[1]?.firstOrNull()?.asString?.let(sb::append)
            }
        }
        if (sb.isEmpty()) return null
        return ChatMsg(peerUin = peer, text = sb.toString())
    }

    private fun md5(b: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(b)

    private fun ByteArray.hex(n: Int = 32): String =
        take(n).joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    companion object {
        private const val TAG = "QqMsf"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_PACKET = 4 * 1024 * 1024
        /** 等响应时最多容忍几个插播包，防止服务端一直推、这边死等 */
        private const val MAX_PUSH_SKIP = 16
        private const val COMMAND_REGISTER = "StatSvc.register"

        /** 注册的三档方案，顺序即优先级 */
        private val REGISTER_PLANS = listOf(
            RegisterPlan("0x0A+D2", MsfFrame.PROTOCOL_LOGIN, MsfFrame.Encrypt.D2, withD2 = true, timeoutMs = 10_000),
            RegisterPlan("0x0B+D2", MsfFrame.PROTOCOL_SIMPLE, MsfFrame.Encrypt.D2, withD2 = false, timeoutMs = 10_000),
            RegisterPlan("0x0A+空钥", MsfFrame.PROTOCOL_LOGIN, MsfFrame.Encrypt.Empty, withD2 = false, timeoutMs = 8_000),
        )
    }
}
