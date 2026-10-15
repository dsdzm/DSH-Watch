package com.dshwatch.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.agent.cloud.CloudTargets
import com.dshwatch.domain.agent.exec.ExecGuard
import com.dshwatch.domain.agent.exec.PythonBridge
import com.dshwatch.domain.agent.exec.ShellRunner
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.components.GhostButton
import com.dshwatch.ui.components.PrimaryButton
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.StepperRow
import com.dshwatch.ui.components.SwitchRow
import com.dshwatch.ui.components.TextRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.WatchNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState

/**
 * 「执行与编译」设置页。
 *
 * 两件事放在一页，因为它们回答的是同一个问题：**「让 AI 把代码跑起来」到底放行到什么程度** ——
 * 上半页是"在手表上跑"（可选、默认关、每次确认），下半页是"交给云端跑"（可选、默认关、代码会出设备）。
 *
 * 设计上刻意把风险写在开关旁边，而不是藏在长按菜单里：
 * 这两个开关的后果是"AI 能执行任意代码""源码会离开设备"，
 * 用户必须在**点之前**就看见这句话。
 */
@Composable
fun ExecSettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val busy = ExecGuard.shared.busy.collectAsState().value

    // 能力自检：这些是"这台设备现在到底能不能干"，与用户的开关无关
    val pythonReady = remember { PythonBridge.classPresent() }
    val shellReady = remember { mutableStateOf<Boolean?>(null) }
    var cloudMessage by remember { mutableStateOf("") }
    var tokenDraft by remember { mutableStateOf(container.keyStore.getGithubToken().orEmpty()) }
    // 打开页面就显示**规范化之后**的值：填过 URL 的话这里会直接看到被纠正成 owner/repo，
    // 而不是等到点「测试连接」才发现拼进 API 的东西是错的
    var repoDraft by remember {
        mutableStateOf(CloudTargets.normalizeRepo(settings.cloudRepo) ?: settings.cloudRepo)
    }
    var refDraft by remember { mutableStateOf(settings.cloudRef) }
    var workflowDraft by remember {
        mutableStateOf(CloudTargets.normalizeWorkflow(settings.cloudWorkflowFile) ?: settings.cloudWorkflowFile)
    }

    // shell 探测要起进程，别在组合里做
    remember(Unit) {
        scope.launch {
            shellReady.value = withContext(Dispatchers.IO) { ShellRunner().detect() }
        }
        Unit
    }

    fun persist(block: (AppSettings) -> AppSettings) {
        scope.launch { container.settingsRepo.update(block) }
    }

    WatchScaffold(title = s.execScreenTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            // ---------------- 本地执行 ----------------
            SectionHeader(s.execSectionTitle)
            SettingCard {
                SwitchRow(
                    title = s.execEnable,
                    subtitle = s.execEnableDesc,
                    checked = settings.localExecEnabled,
                    onCheckedChange = { on -> persist { it.copy(localExecEnabled = on) } },
                )
                if (settings.localExecEnabled) {
                    SwitchRow(
                        title = "Shell",
                        subtitle = when (shellReady.value) {
                            true -> "设备上有可用的 sh"
                            false -> "这台设备没有可用的 /system/bin/sh"
                            null -> "检测中…"
                        },
                        checked = settings.allowShell,
                        onCheckedChange = { on -> persist { it.copy(allowShell = on) } },
                    )
                    SwitchRow(
                        title = "JavaScript",
                        subtitle = "Rhino 引擎，ES6 语法（不支持 async/await）",
                        checked = settings.allowJs,
                        onCheckedChange = { on -> persist { it.copy(allowJs = on) } },
                    )
                    SwitchRow(
                        title = "Python",
                        subtitle = if (pythonReady) s.execPythonReady else s.execPythonMissing,
                        checked = settings.allowPython,
                        onCheckedChange = { on -> persist { it.copy(allowPython = on) } },
                    )
                    StepperRow(
                        title = s.execTimeoutLabel,
                        value = "${settings.execTimeoutSec}${s.execSecondsSuffix}",
                        onDecrease = {
                            persist { it.copy(execTimeoutSec = (it.execTimeoutSec - 5).coerceAtLeast(5)) }
                        },
                        onIncrease = {
                            persist { it.copy(execTimeoutSec = (it.execTimeoutSec + 5).coerceAtMost(120)) }
                        },
                    )
                    StepperRow(
                        title = s.execMaxOutputLabel,
                        value = "${settings.execMaxOutputChars / 1000}k",
                        onDecrease = {
                            persist {
                                it.copy(
                                    execMaxOutputChars =
                                        (it.execMaxOutputChars - 5_000).coerceAtLeast(2_000)
                                )
                            }
                        },
                        onIncrease = {
                            persist {
                                it.copy(
                                    execMaxOutputChars =
                                        (it.execMaxOutputChars + 5_000).coerceAtMost(60_000)
                                )
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = s.execConfirmNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            // 闸门被占住时给一个明确的逃生口：Python 超时后解释器可能仍在后台跑
            if (busy) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "当前有一次执行占用中。如果它已经不会有结果，可以强制释放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
                GhostButton(
                    text = s.execForceRelease,
                    onClick = { ExecGuard.shared.forceRelease() },
                )
            }

            // ---------------- 云端编译 ----------------
            Spacer(Modifier.height(12.dp))
            SectionHeader(s.cloudEnable)
            SettingCard {
                SwitchRow(
                    title = s.cloudEnable,
                    subtitle = s.cloudEnableDesc,
                    checked = settings.cloudBuildEnabled,
                    onCheckedChange = { on -> persist { it.copy(cloudBuildEnabled = on) } },
                )
            }

            if (settings.cloudBuildEnabled) {
                Spacer(Modifier.height(8.dp))
                SettingCard {
                    TextRow(
                        label = s.cloudRepoLabel,
                        value = repoDraft,
                        onValueChange = { repoDraft = it },
                        placeholder = s.cloudRepoHint,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    TextRow(
                        label = s.cloudRefLabel,
                        value = refDraft,
                        onValueChange = { refDraft = it },
                        placeholder = "main",
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    TextRow(
                        label = s.cloudWorkflowLabel,
                        value = workflowDraft,
                        onValueChange = { workflowDraft = it },
                        placeholder = "dsh-run.yml",
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                    TextRow(
                        label = s.cloudTokenLabel,
                        value = tokenDraft,
                        onValueChange = { tokenDraft = it },
                        placeholder = s.cloudTokenHint,
                        isSecret = true,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = s.save,
                    onClick = {
                        val repo = CloudTargets.normalizeRepo(repoDraft)
                        val workflow = CloudTargets.normalizeWorkflow(workflowDraft)
                        // 仓库类设置进 DataStore，令牌单独进加密存储
                        persist {
                            it.copy(
                                cloudRepo = repo ?: repoDraft.trim(),
                                cloudRef = refDraft.trim().ifBlank { "main" },
                                cloudWorkflowFile = workflow ?: workflowDraft.trim().ifBlank { "dsh-run.yml" },
                            )
                        }
                        container.keyStore.putGithubToken(tokenDraft.trim().ifBlank { null })
                        // 归一化结果要**回显**：用户从地址栏粘了个 URL 进来，
                        // 必须让他看见被改成了什么，否则下次还会粘同样的东西
                        cloudMessage = buildString {
                            append("已保存。")
                            if (repo != null && repo != repoDraft.trim()) {
                                repoDraft = repo
                                append("\n仓库已规范化为 $repo。")
                            }
                            if (workflow != null && workflow != workflowDraft.trim()) {
                                workflowDraft = workflow
                                append("\nworkflow 已规范化为 $workflow。")
                            }
                            if (repo == null && repoDraft.isNotBlank()) {
                                append("\n仓库名没识别出来：要写成 owner/repo，不要带 https:// 、github.com 前缀或 .git。")
                            }
                        }
                    },
                )

                Spacer(Modifier.height(6.dp))
                GhostButton(
                    text = s.cloudTestLabel,
                    onClick = {
                        scope.launch {
                            cloudMessage = "检查中…"
                            val repo = CloudTargets
                                .normalizeRepo(repoDraft.trim().ifBlank { settings.cloudRepo })
                            val file = CloudTargets
                                .normalizeWorkflow(workflowDraft.trim().ifBlank { settings.cloudWorkflowFile })
                            cloudMessage = when {
                                repo == null ->
                                    "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch —— " +
                                        "不要带 https:// 、github.com 前缀或 .git 后缀。"
                                tokenDraft.isBlank() -> "先填 GitHub 令牌。"
                                file == null ->
                                    "「默认 workflow」要写文件名，例如 dsh-run.yml，不要写 .github/workflows/ 完整路径。"
                                else -> {
                                    container.keyStore.putGithubToken(tokenDraft.trim())
                                    runCatching {
                                        // 分两步：先确认仓库能访问，再看 workflow。
                                        // 合成一步的话，两种情况 GitHub 给的 404 一模一样，没法给出有用的提示。
                                        val repoProbe = container.githubApi.probeRepo(repo)
                                        if (!repoProbe.ok) return@runCatching repoProbe.message
                                        val workflowProbe = container.githubApi.probeWorkflow(repo, file)
                                        repoProbe.message + "\n" + workflowProbe.message
                                    }.getOrElse { e -> "检查失败：${e.message ?: "未知原因"}" }
                                }
                            }
                        }
                    },
                )

                Spacer(Modifier.height(6.dp))
                GhostButton(
                    text = s.cloudInstallTemplates,
                    onClick = {
                        scope.launch {
                            cloudMessage = "安装中…"
                            val repo = CloudTargets
                                .normalizeRepo(repoDraft.trim().ifBlank { settings.cloudRepo })
                            val branch = refDraft.trim().ifBlank { "main" }
                            if (repo == null) {
                                cloudMessage = "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch —— " +
                                    "不要带 https:// 、github.com 前缀或 .git 后缀。"
                                return@launch
                            }
                            if (tokenDraft.isBlank() && container.keyStore.getGithubToken().isNullOrBlank()) {
                                cloudMessage = "先填 GitHub 令牌。"
                                return@launch
                            }
                            if (tokenDraft.isNotBlank()) container.keyStore.putGithubToken(tokenDraft.trim())
                            cloudMessage = installTemplates(context.assets, container, repo, branch)
                        }
                    },
                )

                if (cloudMessage.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = cloudMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                    )
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "云端编译会把源码发到该仓库并运行在 GitHub 的机器上；" +
                        "只有在你确认后才会发出。产物下载回工作空间的 artifacts/ 目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/**
 * 把两个 workflow 模板写进用户仓库。
 *
 * 模板内容从 assets 读：这样"装上去的那份"和 App 里内置的永远是同一份，
 * 不会出现"照着文档手抄一遍，抄漏了一行"的情况。
 */
private suspend fun installTemplates(
    assets: android.content.res.AssetManager,
    container: AppContainer,
    repo: String,
    branch: String,
): String = withContext(Dispatchers.IO) {
    val slug = CloudTargets.normalizeRepo(repo)
        ?: return@withContext "「目标仓库」要写成 owner/repo，例如 dsdzm/DSH-Watch。"

    // 先确认仓库能访问：否则下面两个文件各自失败一次，
    // 用户看到两行一模一样的报错，却不知道"其实是一个原因"
    val probe = runCatching { container.githubApi.probeRepo(slug) }
        .getOrElse { e -> return@withContext "检查仓库失败：${e.message ?: "未知原因"}" }
    if (!probe.ok) return@withContext probe.message

    val templates = listOf(
        "cloud/dsh-run.yml" to ".github/workflows/dsh-run.yml",
        "cloud/dsh-build.yml" to ".github/workflows/dsh-build.yml",
    )
    val results = mutableListOf<String>()
    for ((asset, target) in templates) {
        val content = runCatching { assets.open(asset).bufferedReader().use { it.readText() } }
            .getOrElse { e -> return@withContext "读取内置模板失败：${e.message}" }
        runCatching {
            container.githubApi.putFile(
                repo = slug,
                path = target,
                content = content,
                message = "chore: 添加 DSH 云端执行模板",
                branch = branch,
            )
        }.onSuccess { ok ->
            results += if (ok) "✓ $target" else "✗ $target（写入被拒绝）"
        }.onFailure { e ->
            results += "✗ $target：${e.message ?: "未知原因"}"
        }
    }
    results.joinToString("\n") +
        "\n提交后需要几秒到十几秒 GitHub 才会索引新 workflow，之后即可触发。"
}
