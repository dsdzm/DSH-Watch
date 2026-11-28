package com.dshwatch.ui.screens.community

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchButton
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 社区账号：登录 / 注册 / 退出。
 *
 * ## 为什么一个页面里塞下登录和注册
 *
 * 手表上一页只放得下两个输入框和两颗按钮，再拆成两页，用户要先选
 * 「我是新用户还是老用户」—— 而这个判断对「我想投稿」没有帮助。
 * 所以两个输入框共用，下面并排两颗按钮，点哪个就是哪个动作。
 *
 * ## 密码不落本地
 *
 * 只存服务器发回来的 token（在 [com.dshwatch.data.community.CommunityAccount]
 * 里走 Keystore 加密存）。密码只在这一次请求里出现过，之后连 App 自己都不知道。
 */
@Composable
fun CommunityAuthScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    val account by container.communityAccount.state.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    fun submit(register: Boolean) {
        if (busy) return
        val name = username.trim()
        if (name.isBlank() || password.isBlank()) {
            notice = "用户名和密码都要填"
            isError = true
            return
        }
        busy = true
        notice = null
        scope.launch {
            runCatching {
                if (register) {
                    container.communityApi.register(name, password)
                } else {
                    container.communityApi.login(name, password)
                }
            }.onSuccess { auth ->
                container.communityAccount.signIn(auth)
                password = ""
                notice = if (register) "注册好了，欢迎来到社区" else "登录成功"
                isError = false
            }.onFailure { e ->
                notice = describeError(e)
                isError = true
            }
            busy = false
        }
    }

    WatchScaffold(
        title = "社区账号",
        onBack = { nav.pop() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (account.signedIn) {
                SectionHeader("已登录")
                Text(
                    text = account.username.ifBlank { "（没记住名字）" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HintText("token 存在本机加密存储里，换了手表要重新登录")

                if (notice != null) {
                    Text(
                        text = notice.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(4.dp))
                WatchButton(
                    text = "我的投稿",
                    onClick = { nav.push(Screen.CommunityMine) },
                )
                WatchGhostButton(
                    text = "退出登录",
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val token = container.communityAccount.token
                    // 远端作废是"顺带做的事"：失败也要退，否则断网时退不掉
                    scope.launch { container.communityApi.logout(token) }
                    container.communityAccount.signOut()
                    username = ""
                    notice = "已退出"
                    isError = false
                }
                return@Column
            }

            SectionHeader("登录或注册")
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }

            WatchButton(
                text = if (busy) "请稍等…" else "登录",
                icon = Icons.Filled.Login,
                enabled = !busy,
                onClick = { submit(register = false) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WatchGhostButton(
                    text = "注册新账号",
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                    onClick = { submit(register = true) },
                )
            }
            HintText("用户名 2~20 位（中文/字母/数字/下划线），密码至少 6 位。投稿要审核，通过了才会出现在社区里。")
        }
    }
}
