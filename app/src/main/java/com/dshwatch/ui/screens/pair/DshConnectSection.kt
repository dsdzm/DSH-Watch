package com.dshwatch.ui.screens.pair

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.Provider
import com.dshwatch.net.ssh.DshSshStep
import com.dshwatch.net.ssh.SshError
import com.dshwatch.net.ssh.SshTarget
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.TextRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「怎么连上我那台电脑」这一块表单。
 *
 * ## 为什么不是「地址 + 配对码」
 *
 * 旧版这里要用户填 dsh web 的地址和一串配对码 —— 手表上敲 43 位令牌不现实，
 * 而那个配对接口在 dsh web 上根本不存在。现在改成 SSH：主机、用户名、密码三样，
 * 电脑上开一个 sshd 就行（macOS 自带，Linux 一条 apt）。
 *
 * ## 为什么粘贴整行也要认
 *
 * 用户手里最可能有的文本是 `ssh me@192.168.1.5 -p 2222`。让他把它拆成三个框手输，
 * 小屏上改错一个字符就得重来。所以主机那一栏接受整行，粘进去自动拆开。
 */
@Composable
fun DshConnectSection(
    container: AppContainer,
    draft: Provider,
    onDraftChange: (Provider) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }

    // 不能 remember 一次就算完：断开之后按钮还写着「断开连接」，用户点了没反应
    var linked by remember { mutableStateOf(container.dshLink.connected) }

    fun connect() {
        scope.launch {
            busy = true
            result = null
            failed = false
            status = "准备中…"
            val target = SshTarget(
                host = draft.sshHost.trim(),
                port = draft.sshPort,
                user = draft.sshUser.trim(),
                command = draft.sshCommand.ifBlank { "dsh web" },
                cwd = draft.sshCwd.trim(),
                fingerprint = draft.sshFingerprint,
            )
            runCatching {
                container.dshLink.connect(target, password) { step, message ->
                    // onStep 在 IO 线程回调，Compose 的状态只能在主线程写
                    scope.launch(Dispatchers.Main.immediate) {
                        status = stepLabel(step) + " · " + message
                    }
                }
            }.onSuccess { link ->
                // 首次连接把指纹记下来，之后每次比对 —— 变了就说明可能有人插在中间
                onDraftChange(draft.copy(sshFingerprint = link.fingerprint))
                linked = true
                val sessions = runCatching { container.dshLink.listSessions().size }.getOrDefault(-1)
                result = if (sessions >= 0) {
                    "已连接 · 远端端口 ${link.remotePort} · ${sessions} 个会话"
                } else {
                    "已连接 · 远端端口 ${link.remotePort}"
                }
                failed = false
                status = ""
            }.onFailure {
                val message = when (it) {
                    is SshError -> it.describeForUser()
                    else -> it.message ?: it.javaClass.simpleName
                }
                result = message
                failed = true
                status = ""
            }
            busy = false
        }
    }

    Text(
        text = "手表通过 SSH 连上电脑，在那边启动 dsh web，再把端口转发回手表。" +
            "电脑上要先开 SSH（macOS：系统设置 → 通用 → 共享 → 远程登录）。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
    Spacer(Modifier.height(6.dp))

    TextRow(
        label = "电脑地址",
        value = draft.sshHost,
        placeholder = "192.168.1.5 或 粘贴 ssh 整行",
        onValueChange = { raw ->
            // 粘贴 `ssh me@host -p 2222` 也能一次拆开 —— 小屏上手输三个字段是折磨
            val (host, port) = SshTarget.parseHostInput(raw)
            val user = SshTarget.parseUserInput(raw)
            onDraftChange(
                draft.copy(
                    sshHost = host,
                    sshPort = port ?: draft.sshPort,
                    sshUser = if (user.isNotBlank()) user else draft.sshUser,
                )
            )
        },
    )
    Spacer(Modifier.height(6.dp))

    TextRow(
        label = "端口",
        value = draft.sshPort.toString(),
        placeholder = "22",
        keyboardType = KeyboardType.Number,
        onValueChange = { raw ->
            val n = raw.filter { it.isDigit() }.take(5).toIntOrNull()
            onDraftChange(draft.copy(sshPort = n ?: 22))
        },
    )
    Spacer(Modifier.height(6.dp))

    TextRow(
        label = "用户名",
        value = draft.sshUser,
        placeholder = "电脑上的用户名",
        onValueChange = { onDraftChange(draft.copy(sshUser = it.trim())) },
    )
    Spacer(Modifier.height(6.dp))

    TextRow(
        label = "密码",
        value = password,
        placeholder = "SSH 登录密码",
        isSecret = true,
        onValueChange = onPasswordChange,
    )
    Spacer(Modifier.height(4.dp))

    SwitchRow(
        title = "高级",
        subtitle = "启动命令与工作目录",
        checked = advanced,
        onCheckedChange = { advanced = it },
    )
    if (advanced) {
        Spacer(Modifier.height(4.dp))
        TextRow(
            label = "启动命令",
            value = draft.sshCommand,
            placeholder = "dsh web",
            onValueChange = { onDraftChange(draft.copy(sshCommand = it)) },
        )
        Spacer(Modifier.height(6.dp))
        TextRow(
            label = "工作目录",
            value = draft.sshCwd,
            placeholder = "留空 = 登录目录",
            onValueChange = { onDraftChange(draft.copy(sshCwd = it)) },
        )
        Spacer(Modifier.height(6.dp))
    }

    Spacer(Modifier.height(4.dp))
    if (busy) {
        GhostButton(text = status.ifBlank { "连接中…" }, modifier = Modifier.fillMaxWidth(), onClick = {})
    } else if (linked) {
        GhostButton(
            text = "断开连接",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                container.dshLink.close()
                linked = false
                result = "已断开"
                failed = false
            },
        )
    } else {
        GhostButton(
            text = "连接电脑",
            modifier = Modifier.fillMaxWidth(),
            onClick = { if (!draft.sshHost.isBlank() && !draft.sshUser.isBlank()) connect() },
        )
    }

    result?.let { text ->
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        )
    }
}

private fun stepLabel(step: DshSshStep): String = when (step) {
    DshSshStep.SSH -> "SSH"
    DshSshStep.LAUNCH -> "启动 dsh web"
    DshSshStep.TUNNEL -> "端口转发"
    DshSshStep.LOGIN -> "登录"
}
