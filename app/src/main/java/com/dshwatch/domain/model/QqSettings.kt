package com.dshwatch.domain.model

import kotlinx.serialization.Serializable

/**
 * QQ 桥接设置。
 *
 * ## 为什么是「本机后端」而不是内置协议
 *
 * QQ 的登录协议由腾讯把守，任何第三方实现都要逆向 + 持续对抗风控。
 * 所以这条链路的事实标准是 OneBot 11：NapCat / SnowLuma 这类后端负责
 * 登录与协议，对外只暴露一组 HTTP 接口。本 App 只跟这组接口说话。
 *
 * 手机上把后端跑在 Termux 里，地址就是 `127.0.0.1:3000` ——
 * 不经过任何外部服务器，消息不出设备。这也是本功能默认填回环地址的原因：
 * 填公网地址能用，但那意味着聊天内容要过一次别人的机器。
 *
 * ## 字段为什么都是字符串
 *
 * QQ 号在部分实现里会超出 Int（虽然目前还没有），而且它从来不参与运算。
 * 存字符串省掉一次「解析失败 → 整包设置读不回来」的风险。
 */
@Serializable
data class QqSettings(
    /** 总开关。关掉后前台服务不再轮询，已收到的消息也不会转发 */
    val enabled: Boolean = false,

    /** OneBot HTTP 地址，例如 `http://127.0.0.1:3000` */
    val baseUrl: String = "http://127.0.0.1:3000",

    /**
     * 访问令牌。OneBot 后端的 `access_token`，留空表示后端没设。
     *
     * 存在设置里而不是密钥库：它是本机回环上的口令，泄露面等于「谁能碰你手机」，
     * 和已经掌握在手里的 API Key 不是一个量级。真要用公网地址，用户自己会改。
     */
    val token: String = "",

    /** 机器人自己的 QQ 号；留空则启动时问后端要（`get_login_info`） */
    val selfId: String = "",

    // ---------------- 私聊 ----------------
    val privateEnabled: Boolean = true,

    /**
     * 私聊对象 QQ 号。
     *
     * **必填。** OneBot 的历史接口按 `user_id` 查，没有「列出所有新私聊」这种调用；
     * 想回复谁，就得先知道是谁。留空时私聊方向直接不轮询。
     */
    val privatePeer: String = "",

    // ---------------- 群聊 ----------------
    val groupEnabled: Boolean = false,

    /** 群号 */
    val groupPeer: String = "",

    // ---------------- 触发 ----------------
    /**
     * 群里是否必须 @ 才回。
     *
     * 默认 true：群里没有 @ 就接话，等于给整个群装了一个自动回复机器人，
     * 而用户很可能只是想「让 AI 帮我看一眼这个群」。
     */
    val requireAt: Boolean = true,

    /** 触发前缀，逗号分隔，例如 `小D,ds`。命中前缀等同被点名 */
    val prefixes: String = "",

    // ---------------- 行为 ----------------
    /** 轮询间隔（秒）。太小会把后端打满，太大则响应迟钝 */
    val pollSeconds: Int = 4,

    /**
     * 是否下载对方发来的图片并交给模型。
     *
     * 关掉后图片消息只剩文字部分（表情包就是空的），省流量也省 token；
     * 用的是纯文本模型时应该关掉。
     */
    val sendImages: Boolean = true,

    /**
     * 单条 QQ 消息最多交给模型多少字。
     *
     * 群里有人贴一整篇长文时，原样塞进去会顶掉正常上下文；
     * 截断比整段丢弃好 —— 模型至少还能回答「这段在说什么」。
     */
    val maxInputChars: Int = 4_000,

    /**
     * 手表协议登录用的设备 GUID（32 位十六进制）。
     *
     * 必须跨次登录保持不变。每次换一副新指纹，等于每次都在陌生设备上登录，
     * 风控会直接拦下来；空串表示还没生成过，首次扫码时会补一份。
     */
    val watchGuid: String = "",

    /** 同上，16 位数字的 imei */
    val watchImei: String = "",
) {
    fun normalizedBaseUrl(): String {
        var u = baseUrl.trim()
        if (u.isEmpty()) return "http://127.0.0.1:3000"
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        return u.trimEnd('/')
    }

    /** 前缀列表 */
    fun prefixList(): List<String> = prefixes
        .split(',', '，')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
}
