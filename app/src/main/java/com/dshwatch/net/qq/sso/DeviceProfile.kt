package com.dshwatch.net.qq.sso

import java.security.MessageDigest

/**
 * 登录包里的设备画像。
 *
 * `wtlogin.login` 比取码多了一段 0x144：IMEI、Android ID、MAC、WiFi、机型、指纹……
 * 服务端拿它和设备 GUID 一起做风控画像 —— 同一个 guid 每次报上来不同的机型，
 * 比「机型不常见」更容易被拦。
 *
 * 所以这里**全部由 guid 派生**：只要 guid 不变，这套画像永远一致，
 * 不需要再往设置里加十几个字段，也不会因为换版本就变成「新设备」。
 */
internal class DeviceProfile(val device: WatchDevice) {

    private val seed: ByteArray = md5(device.guid)
    private val seed2: ByteArray = md5(device.guid + "profile".toByteArray())

    /** 16 位十六进制，和真机 android_id 的形状一致 */
    val androidId: ByteArray = hex(seed, 8).toByteArray(Charsets.UTF_8)

    val macAddress = "00:50:56:C0:00:08"
    val wifiBssid = macAddress
    val wifiSsid = "<unknown ssid>"
    val ip = byteArrayOf(10, 0, 1, 3)

    val imsiMd5: ByteArray = md5(device.guid + "imsi".toByteArray())

    val brand = "HUAWEI"
    val model = "ELE-AL00"

    /**
     * 扫码确认页上手机 QQ 显示的那个名字：「允许在 XXX 上登录」。
     *
     * 取码请求里有这一项，留空服务端就能过 —— 码照样出、也照样能扫 ——
     * 但手机点「确认」那一下会被拦：先弹「未识别到设备名称，请检验身份」，
     * 随后这次登录判成风控（客户端只看到 45）。
     *
     * 必须和 0x144 里的机型、0x16e 对得上，否则等于「报了一个查无此机的名字」。
     */
    val deviceName: String = "$brand $model"

    /**
     * 上线注册（`StatSvc.register`）要报的系统版本。
     *
     * 必须和取码包 0x16e 里那份一致：同一个 guid 一会儿报一套系统、
     * 一会儿报另一套，比「版本老」更容易被判成换机。
     */
    val osVersion: String = device.osVersion
    val sdkLevel: Long = 25L // Android 7.1.2

    val bootloader = "unknown"
    val codename = "REL"
    val incremental = "9.1.0.120"
    val baseBand = ""

    val fingerprint = "HUAWEI/ELE-AL00/HWELE:9/HUAWEIELE-AL00/9.1.0.120C00:user/release-keys"
    val procVersion = "Linux version 4.9.111-${hex(seed2, 4)} (android-build@xxx.xxx.xxx.xxx.com)"
    val bootId = uuid(seed2)

    /** 0x177 用的 SDK 版本，和新版 qqlite 一起发布的那串 */
    val sdkVersion = "6.0.0.2498"

    /** 构建时间戳，手表客户端的固定值（2019-06-04，跟着老版本一起写的，服务端不校验） */
    val buildTime = 1_559_564_731

    /**
     * 兜底用的 tgtgtKey。
     *
     * 扫码流程里真正的 key 由服务端在「已确认」时用 0x1e 下发，登录包必须用那一份 ——
     * 这里派生的只在对端没给 0x1e 时顶上（比如后续续期类请求）。
     * 两者混用的话，0x144 能用、0x119 就解不开，表现为「登录成功但拿不到 D2」。
     */
    val tgtgtKey: ByteArray = md5(device.guid + "tgtgt".toByteArray())

    /**
     * 0x52d：TCG 的 DeviceInfo，protobuf 编码，9 个字符串字段。
     *
     * 字段号是协议规定死的，顺序不能改：
     * `bootloader / procVersion / codename / incremental / fingerprint /
     *  bootId / androidId / baseBand / innerVersion`
     */
    fun deviceInfoProto(): ByteArray {
        val w = ByteWriter(256)
        protoString(w, 1, bootloader)
        protoString(w, 2, procVersion)
        protoString(w, 3, codename)
        protoString(w, 4, incremental)
        protoString(w, 5, fingerprint)
        protoString(w, 6, bootId)
        protoString(w, 7, String(androidId, Charsets.UTF_8))
        protoString(w, 8, baseBand)
        protoString(w, 9, incremental)
        return w.toByteArray()
    }

    /** protobuf 的 length-delimited 字符串字段：`(field<<3)|2` + varint 长度 + 内容 */
    private fun protoString(w: ByteWriter, field: Int, value: String) {
        // proto3 不写空字符串：少 2 字节，服务端按字段号取值，不影响语义。
        // 但长度必须和官方逐字节一致，否则整段 TLV 就错位了。
        if (value.isEmpty()) return
        val bytes = value.toByteArray(Charsets.UTF_8)
        w.byte((field shl 3) or 2)
        varint(w, bytes.size)
        w.bytes(bytes)
    }

    /** varint 最多 32 位就够：这些字段的长度不会超过 2^31 */
    private fun varint(w: ByteWriter, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            w.byte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        w.byte(v and 0x7F)
    }

    private fun hex(src: ByteArray, n: Int): String =
        (0 until n).joinToString("") { "%02x".format(src[it].toInt() and 0xFF) }

    private fun uuid(src: ByteArray): String {
        val h = hex(src, 16)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-" +
            "${h.substring(16, 20)}-${h.substring(20, 32)}"
    }

    private fun md5(b: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(b)
}
