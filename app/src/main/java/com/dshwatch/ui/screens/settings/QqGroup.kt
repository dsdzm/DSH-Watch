package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.net.qq.sso.QqSessionStore
import com.dshwatch.net.qq.sso.watchDeviceOrNull
import com.dshwatch.service.QqService
import com.dshwatch.ui.components.GlassDialog
import com.dshwatch.ui.components.GlassDialogButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.StepperRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

/**
 * 「QQ」设置组。
 *
 * 结构按用户的配置顺序排：先决定开不开，再看通没通，然后填后端，
 * 最后才是「哪个私聊 / 哪个群要回」。把开关放在最上面是有意的 ——
 * 这一整页的大部分内容在没有后端时都是空的，用户得先看到那个总开关。
 */
@Composable
internal fun QqGroup(
    settings: AppSettings,
    update: Update,
    container: AppContainer,
    s: AppStrings,
) {
    val ctx = LocalContext.current
    val st by container.qqBridge.state.collectAsStateWithLifecycle()
    val cfg = settings.qq

    var editing by remember { mutableStateOf("") }

    /** 测试连接的结果；空串表示没有弹窗 */
    var probe by remember { mutableStateOf("") }

    /** 是否显示扫码登录弹窗 */
    var showLogin by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 直连登录的票据存在加密区；登录弹窗关闭后重新读一次，状态才会跟着变
    val sessionStore = remember(container) { QqSessionStore(container.keyStore) }
    var session by remember { mutableStateOf(sessionStore.load()) }

    /** 直连链路自检的输出；非空时弹窗展示 */
    var selfTest by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun setEnabled(on: Boolean) {
        update { it.copy(qq = it.qq.copy(enabled = on)) }
        if (on) QqService.start(ctx) else QqService.stop(ctx)
    }

    /**
     * 改完配置后让链路立刻按新配置跑。
     *
     * 先叫服务、再 poke 桥接：只 poke 的话，服务被杀掉之后 `poke` 会在
     * 进程里起一个**没有前台保护**的轮询循环 —— 看着在跑，锁屏几分钟就没了，
     * 而用户以为一直通着。
     */
    fun ensure() {
        if (cfg.enabled) QqService.start(ctx)
        container.qqBridge.poke()
    }

    // ---------------- 总开关 ----------------
    SettingCard {
        SwitchRow(
            title = s.groupQq,
            subtitle = if (s.isChinese) {
                "把 QQ 消息转进对话，并把回复发回去。需要本机跑着 NapCat / SnowLuma"
            } else {
                "Forward QQ messages into chats and send replies back (needs NapCat/SnowLuma)"
            },
            checked = cfg.enabled,
            onCheckedChange = { setEnabled(it) },
        )
    }

    SectionHeader(if (s.isChinese) "直连登录（实验）" else "Direct sign-in (experimental)")
    SettingCard {
        if (session == null) {
            SettingRow(
                title = if (s.isChinese) "扫码登录 QQ" else "Sign in with QQ",
                subtitle = if (s.isChinese) {
                    "手表协议；不经过后端，登录态加密存在本机"
                } else {
                    "watch protocol, no backend"
                },
                trailing = "›",
                onClick = { showLogin = true },
            )
        } else {
            val s0 = session!!
            SettingRow(
                title = if (s.isChinese) "已登录 · ${s0.uin}" else "Signed in · ${s0.uin}",
                subtitle = buildString {
                    if (s0.nick.isNotBlank()) append(s0.nick).append(" · ")
                    val age = s0.ageDays()
                    append(
                        when {
                            !s0.usable -> if (s.isChinese) "票据不完整，需要重新扫码" else "incomplete"
                            age <= 0 -> if (s.isChinese) "今天登录" else "today"
                            else -> if (s.isChinese) "${age} 天前登录" else "$age d ago"
                        },
                    )
                },
                trailing = if (s.isChinese) "退出" else "Sign out",
                onClick = {
                    sessionStore.clear()
                    session = null
                },
            )
            SettingRow(
                title = if (s.isChinese) "重新扫码" else "Scan again",
                subtitle = if (s.isChinese) "换号，或票据过期后重登" else "switch account or refresh",
                trailing = "›",
                onClick = { showLogin = true },
            )
            SettingRow(
                title = if (s.isChinese) "连接自检" else "Connection self-test",
                subtitle = if (testing) {
                    if (s.isChinese) "正在连接…" else "connecting…"
                } else if (s.isChinese) {
                    "验证长连接、心跳、取消息三步"
                } else {
                    "long connection, heartbeat, message sync"
                },
                trailing = "›",
                onClick = {
                    val dev = cfg.watchDeviceOrNull()
                    if (dev == null) {
                        selfTest = if (s.isChinese) "设备指纹丢失，先重新扫码" else "device id missing"
                        return@SettingRow
                    }
                    testing = true
                    scope.launch {
                        val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.dshwatch.net.qq.msf.directSelfTest(s0, dev, cfg.privatePeer.toLongOrNull())
                        }
                        selfTest = report
                        testing = false
                    }
                },
            )
        }
    }

    if (showLogin) {
        QqWatchLoginDialog(
            settings = settings,
            update = update,
            container = container,
            onClose = {
                showLogin = false
                session = sessionStore.load()
            },
        )
    }

    selfTest?.let { report ->
        GlassDialog(
            onDismissRequest = { selfTest = null },
            title = if (s.isChinese) "连接自检" else "Self-test",
            content = {
                Text(
                    text = report,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            buttons = {
                GlassDialogButton(
                    label = if (s.isChinese) "关闭" else "Close",
                    primary = true,
                    onClick = { selfTest = null },
                )
            },
        )
    }

    if (!cfg.enabled) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (s.isChinese) {
                "用法：手机上用 Termux 起一个 OneBot 后端（NapCat 或 SnowLuma），" +
                    "开 HTTP 服务并监听 127.0.0.1:3000，然后回到这里打开开关。"
            } else {
                "Run an OneBot backend (NapCat / SnowLuma) in Termux, expose HTTP on " +
                    "127.0.0.1:3000, then enable this switch."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        return
    }

    // ---------------- 状态 ----------------
    Spacer(Modifier.height(8.dp))
    SettingCard {
        SettingRow(
            title = if (s.isChinese) "连接状态" else "Status",
            subtitle = when {
                !st.running -> if (s.isChinese) "服务未启动" else "Service not running"
                st.connected -> if (s.isChinese) "已连上后端" else "Connected"
                st.error.isNotBlank() -> st.error
                else -> if (s.isChinese) "正在连接…" else "Connecting…"
            },
            trailing = when {
                st.connected -> "●"
                st.running -> "○"
                else -> "×"
            },
            showDivider = true,
            onClick = { ensure() },
        )
        SettingRow(
            title = if (s.isChinese) "登录账号" else "Account",
            subtitle = if (st.selfId.isBlank()) {
                if (s.isChinese) "还没拿到（检查 token 或后端是否已登录）" else "unknown"
            } else {
                st.selfId + if (st.nickname.isNotBlank()) " · ${st.nickname}" else ""
            },
            showDivider = true,
        )
        SettingRow(
            title = if (s.isChinese) "已处理" else "Handled",
            subtitle = st.lastLine.ifBlank {
                if (s.isChinese) "本次启动以来转发的消息数" else "messages since start"
            },
            trailing = "${st.handled}",
        )
    }

    // ---------------- 后端 ----------------
    SectionHeader(if (s.isChinese) "后端" else "Backend")
    SettingCard {
        SettingRow(
            title = if (s.isChinese) "地址" else "Address",
            subtitle = cfg.baseUrl.ifBlank { "http://127.0.0.1:3000" },
            trailing = "›",
            showDivider = true,
            onClick = { editing = FIELD_URL },
        )
        SettingRow(
            title = if (s.isChinese) "访问令牌" else "Access token",
            subtitle = if (cfg.token.isBlank()) {
                if (s.isChinese) "后端没设 token 就留空" else "leave empty if none"
            } else {
                "••••••"
            },
            showDivider = true,
            trailing = "›",
            onClick = { editing = FIELD_TOKEN },
        )
        SettingRow(
            title = if (s.isChinese) "我的 QQ 号" else "My QQ id",
            subtitle = if (cfg.selfId.isBlank()) {
                if (s.isChinese) "留空则启动时自动问后端要" else "auto-detect"
            } else {
                cfg.selfId
            },
            trailing = "›",
            onClick = { editing = FIELD_SELF },
            showDivider = true,
        )
        /*
         * 「连不上」是这个功能最常见的失败，而它有三个完全不同的原因：
         * 后端没起、地址填错、token 不对。全都在通知栏里写「未连接」等于没说。
         * 这一行直接调一次 get_login_info，把后端自己的原话摆出来。
         */
        SettingRow(
            title = if (s.isChinese) "测试连接" else "Test connection",
            subtitle = if (probing) {
                if (s.isChinese) "正在问后端…" else "asking backend…"
            } else {
                if (s.isChinese) "调一次 get_login_info 看通不通" else "calls get_login_info"
            },
            trailing = "◎",
            onClick = {
                if (probing) return@SettingRow
                probing = true
                scope.launch {
                    container.oneBot.update(cfg)
                    val r = runCatching { container.oneBot.loginInfo() }
                        .getOrElse {
                            com.dshwatch.net.qq.QqResult.Err(it.message.orEmpty())
                        }
                    probe = when (r) {
                        is com.dshwatch.net.qq.QqResult.Ok ->
                            (if (s.isChinese) "通了。后端登录的是 " else "OK, logged in as ") +
                                r.value.userId + " · " + r.value.nickname
                        is com.dshwatch.net.qq.QqResult.Err -> r.message
                    }
                    probing = false
                }
            },
        )
    }

    /*
     * 直连登录：不用后端，App 自己拿着手表协议去连 QQ。
     *
     * 和上面的 OneBot 是两条互不相干的路 —— 上面那条要旁边有个后端在跑，
     * 这条不用。代价是它走的协议腾讯随时能封，现阶段也还没有接上收发消息。
     */
    // ---------------- 私聊 ----------------
    SectionHeader(if (s.isChinese) "私聊" else "Private chat")
    SettingCard {
        SwitchRow(
            title = if (s.isChinese) "接收私聊" else "Private messages",
            subtitle = if (s.isChinese) {
                "只处理这一个人发来的消息；别人的私聊不动"
            } else {
                "Only this contact's messages are forwarded"
            },
            checked = cfg.privateEnabled,
            onCheckedChange = { on ->
                update { it.copy(qq = it.qq.copy(privateEnabled = on)) }
                ensure()
            },
        )
        if (cfg.privateEnabled) {
            SettingRow(
                title = if (s.isChinese) "对方 QQ 号" else "Contact QQ id",
                subtitle = cfg.privatePeer.ifBlank {
                    if (s.isChinese) "必填 —— 不知道回给谁" else "required"
                },
                trailing = "›",
                onClick = { editing = FIELD_PRIVATE },
            )
        }
    }

    // ---------------- 群 ----------------
    SectionHeader(if (s.isChinese) "群聊" else "Group chat")
    SettingCard {
        SwitchRow(
            title = if (s.isChinese) "接收群消息" else "Group messages",
            subtitle = if (s.isChinese) {
                "群里只有被点名才回，避免变成刷屏机器人"
            } else {
                "Only replies when mentioned"
            },
            checked = cfg.groupEnabled,
            onCheckedChange = { on ->
                update { it.copy(qq = it.qq.copy(groupEnabled = on)) }
                ensure()
            },
        )
        if (cfg.groupEnabled) {
            SettingRow(
                title = if (s.isChinese) "群号" else "Group id",
                subtitle = cfg.groupPeer.ifBlank {
                    if (s.isChinese) "必填" else "required"
                },
                showDivider = true,
                trailing = "›",
                onClick = { editing = FIELD_GROUP },
            )
            SwitchRow(
                title = if (s.isChinese) "必须 @ 我" else "Require @mention",
                subtitle = if (s.isChinese) {
                    "关掉后群里每句话都会回 —— 除非你确定，否则别关"
                } else {
                    "Off means every message triggers a reply"
                },
                checked = cfg.requireAt,
                onCheckedChange = { on ->
                    update { it.copy(qq = it.qq.copy(requireAt = on)) }
                },
            )
            SettingRow(
                title = if (s.isChinese) "触发前缀" else "Trigger prefixes",
                subtitle = cfg.prefixes.ifBlank {
                    if (s.isChinese) "逗号分隔，例如「小D,ds」；命中等同被 @"
                    else "comma separated"
                },
                trailing = "›",
                onClick = { editing = FIELD_PREFIX },
            )
        }
    }

    // ---------------- 行为 ----------------
    SectionHeader(if (s.isChinese) "行为" else "Behaviour")
    SettingCard {
        StepperRow(
            title = if (s.isChinese) "轮询间隔" else "Poll interval",
            subtitle = if (s.isChinese) {
                "越小越跟手，也越费电；本地回环 4 秒足够"
            } else {
                "lower = snappier, higher = less battery"
            },
            value = "${cfg.pollSeconds} 秒",
            onDecrease = {
                update { it.copy(qq = it.qq.copy(pollSeconds = (it.qq.pollSeconds - 1).coerceAtLeast(2))) }
            },
            onIncrease = {
                update { it.copy(qq = it.qq.copy(pollSeconds = (it.qq.pollSeconds + 1).coerceAtMost(60))) }
            },
        )
        SwitchRow(
            title = if (s.isChinese) "接收图片" else "Receive images",
            subtitle = if (s.isChinese) {
                "把对方发的图下载下来一起交给模型（最多 3 张）"
            } else {
                "Download up to 3 images and pass them to the model"
            },
            checked = cfg.sendImages,
            onCheckedChange = { on ->
                update { it.copy(qq = it.qq.copy(sendImages = on)) }
            },
        )
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = if (s.isChinese) {
            "回复不带任何工具能力：QQ 消息可能来自群里的任何人，不能让它在你手机上" +
                "装应用、改设置或读文件。对话会以「QQ xxx」的名字出现在会话列表里，" +
                "你可以直接进去接着聊。"
        } else {
            "Replies never use tools — messages may come from anyone in a group. " +
                "Chats appear in the session list as \"QQ …\" and can be continued in-app."
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(horizontal = 6.dp),
    )

    if (probe.isNotBlank()) {
        GlassDialog(
            onDismissRequest = { probe = "" },
            title = if (s.isChinese) "测试结果" else "Test result",
            content = {
                Text(
                    text = probe,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            buttons = {
                GlassDialogButton(s.close, primary = true, onClick = { probe = "" })
            },
        )
    }

    // ---------------- 编辑弹窗 ----------------
    if (editing.isNotEmpty()) {
        val title = when (editing) {
            FIELD_URL -> if (s.isChinese) "后端地址" else "Backend address"
            FIELD_TOKEN -> if (s.isChinese) "访问令牌" else "Access token"
            FIELD_SELF -> if (s.isChinese) "我的 QQ 号" else "My QQ id"
            FIELD_PRIVATE -> if (s.isChinese) "对方 QQ 号" else "Contact QQ id"
            FIELD_GROUP -> if (s.isChinese) "群号" else "Group id"
            else -> if (s.isChinese) "触发前缀" else "Trigger prefixes"
        }
        val initial = when (editing) {
            FIELD_URL -> cfg.baseUrl
            FIELD_TOKEN -> cfg.token
            FIELD_SELF -> cfg.selfId
            FIELD_PRIVATE -> cfg.privatePeer
            FIELD_GROUP -> cfg.groupPeer
            else -> cfg.prefixes
        }
        val placeholder = when (editing) {
            FIELD_URL -> "http://127.0.0.1:3000"
            FIELD_PRIVATE, FIELD_GROUP, FIELD_SELF -> "123456789"
            FIELD_PREFIX -> if (s.isChinese) "小D,ds" else "hey,ai"
            else -> ""
        }
        var value by remember(editing) { mutableStateOf(initial) }

        GlassDialog(
            onDismissRequest = { editing = "" },
            title = title,
            content = {
                TextRow(
                    label = title,
                    value = value,
                    onValueChange = { value = it },
                    placeholder = placeholder,
                    isSecret = editing == FIELD_TOKEN,
                )
            },
            buttons = {
                GlassDialogButton(s.cancel, onClick = { editing = "" })
                GlassDialogButton(
                    s.save,
                    primary = true,
                    onClick = {
                        val v = value.trim()
                        update {
                            it.copy(
                                qq = when (editing) {
                                    FIELD_URL -> it.qq.copy(baseUrl = v)
                                    FIELD_TOKEN -> it.qq.copy(token = v)
                                    FIELD_SELF -> it.qq.copy(selfId = v)
                                    FIELD_PRIVATE -> it.qq.copy(privatePeer = v)
                                    FIELD_GROUP -> it.qq.copy(groupPeer = v)
                                    else -> it.qq.copy(prefixes = v)
                                },
                            )
                        }
                        // 地址 / token 换了立刻重连，不用等下一轮 sleep
                        ensure()
                        editing = ""
                    },
                )
            },
        )
    }
}

private const val FIELD_URL = "url"
private const val FIELD_TOKEN = "token"
private const val FIELD_SELF = "self"
private const val FIELD_PRIVATE = "private"
private const val FIELD_GROUP = "group"
private const val FIELD_PREFIX = "prefix"