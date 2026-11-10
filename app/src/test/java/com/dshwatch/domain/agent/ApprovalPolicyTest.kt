package com.dshwatch.domain.agent

import com.dshwatch.domain.device.DeviceControlHub
import com.dshwatch.domain.model.AgentMode
import com.dshwatch.domain.model.AppSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

/**
 * 审批策略。
 *
 * 这是安全边界，必须用测试钉住 —— 不能只靠「在手表上点一遍看起来对」。
 * 特别是 shell：它在任何模式下都应该拦，只有本会话放行能绕过。
 */
class ApprovalPolicyTest {

    private fun shell() = args("shell")
    private fun js() = args("js")
    private fun python() = args("python")

    private fun args(language: String?) = buildJsonObject {
        if (language != null) put("language", language)
    }

    private fun settings(
        mode: AgentMode,
        autoApproved: Set<String> = emptySet(),
    ) = AppSettings(agentMode = mode, autoApprovedTools = autoApproved)

    @Test
    fun `shell asks even in full auto`() {
        val need = ApprovalPolicy.needsApproval(
            settings(AgentMode.AUTO_ALL), "run_script", ToolSafety.WRITE, shell(), emptySet()
        )
        assertThat(need).isTrue()
    }

    @Test
    fun `shell is released for the rest of the session after allow-session`() {
        val need = ApprovalPolicy.needsApproval(
            settings(AgentMode.AUTO_ALL), "run_script", ToolSafety.WRITE, shell(),
            sessionApproved = setOf("run_script:shell"),
        )
        assertThat(need).isFalse()
    }

    @Test
    fun `shell is released only when language is exactly shell`() {
        assertThat(ApprovalPolicy.sessionKey("run_script", shell())).isEqualTo("run_script:shell")
        assertThat(ApprovalPolicy.sessionKey("run_script", js())).isNull()
        assertThat(ApprovalPolicy.sessionKey("run_script", python())).isNull()
    }

    @Test
    fun `missing language is treated as shell`() {
        val need = ApprovalPolicy.needsApproval(
            settings(AgentMode.AUTO_ALL), "run_script", ToolSafety.WRITE, args(null), emptySet()
        )
        assertThat(need).isTrue()
    }

    @Test
    fun `js and python run without asking in full auto`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.AUTO_ALL), "run_script", ToolSafety.WRITE, js(), emptySet()
            )
        ).isFalse()
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.AUTO_ALL), "run_script", ToolSafety.WRITE, python(), emptySet()
            )
        ).isFalse()
    }

    @Test
    fun `js still asks when the user chose ask-every-step`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.ASK_EVERY_STEP), "run_script", ToolSafety.WRITE, js(), emptySet()
            )
        ).isTrue()
    }

    @Test
    fun `cloud build always asks, even if auto-approved`() {
        val need = ApprovalPolicy.needsApproval(
            settings(AgentMode.AUTO_ALL, autoApproved = setOf("cloud_build")),
            "cloud_build", ToolSafety.NETWORK, args(null), emptySet(),
        )
        assertThat(need).isTrue()
    }

    @Test
    fun `cloud job also always asks`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.AUTO_ALL), "cloud_job", ToolSafety.NETWORK, args(null), emptySet()
            )
        ).isTrue()
    }

    @Test
    fun `read only tools run silently in read-only auto mode`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.AUTO_READONLY), "read_file", ToolSafety.READ_ONLY, args(null), emptySet()
            )
        ).isFalse()
    }

    @Test
    fun `write tools still ask in read-only auto mode`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.AUTO_READONLY), "write_file", ToolSafety.WRITE, args(null), emptySet()
            )
        ).isTrue()
    }

    @Test
    fun `auto-approved non-dangerous tool is not asked again`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.ASK_EVERY_STEP, autoApproved = setOf("write_file")),
                "write_file", ToolSafety.WRITE, args(null), emptySet(),
            )
        ).isFalse()
    }

    @Test
    fun `session scoped flag only applies to shell`() {
        assertThat(ApprovalPolicy.isSessionScoped("run_script", shell())).isTrue()
        assertThat(ApprovalPolicy.isSessionScoped("run_script", js())).isFalse()
        assertThat(ApprovalPolicy.isSessionScoped("cloud_build", shell())).isFalse()
    }
    // ---------------- 设备会话 ----------------

    /*
     * 设备操控期间 App 一定在后台：审批弹窗画在一个用户看不见的 Activity 上，
     * 任务会一直等到超时。所以会话进行中这四个工具免问 —— 同意权在悬浮条的停止按钮上。
     */

    @Test
    fun `device tools are not asked while a device session is running`() {
        DeviceControlHub.begin("测试")
        try {
            listOf("device_screen", "device_act", "device_open", "device_handoff").forEach { name ->
                assertThat(
                    ApprovalPolicy.needsApproval(
                        settings(AgentMode.ASK_EVERY_STEP), name, ToolSafety.WRITE, args(null), emptySet(),
                    )
                ).isFalse()
            }
        } finally {
            DeviceControlHub.end()
        }
    }

    @Test
    fun `device tools are asked again once the session ends`() {
        DeviceControlHub.end()
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.ASK_EVERY_STEP), "device_act", ToolSafety.WRITE, args(null), emptySet(),
            )
        ).isTrue()
    }

    @Test
    fun `device session does not exempt unrelated write tools`() {
        DeviceControlHub.begin("测试")
        try {
            assertThat(
                ApprovalPolicy.needsApproval(
                    settings(AgentMode.ASK_EVERY_STEP), "write_file", ToolSafety.WRITE, args(null), emptySet(),
                )
            ).isTrue()
        } finally {
            DeviceControlHub.end()
        }
    }

    // ---------------- adb_shell（Shizuku 提权） ----------------

    private fun adb(cmd: String) = buildJsonObject {
        put("command", cmd)
        put("purpose", "测试用途")
    }

    @Test
    fun `adb shell asks on the first use of a category`() {
        assertThat(
            ApprovalPolicy.needsApproval(
                settings(AgentMode.AUTO_ALL), "adb_shell", ToolSafety.WRITE,
                adb("pm install -r /sdcard/a.apk"), emptySet(),
            )
        ).isTrue()
    }

    @Test
    fun `adb shell is released once its category is granted`() {
        val st = AppSettings(shellGrants = setOf("install"))
        assertThat(
            ApprovalPolicy.needsApproval(
                st, "adb_shell", ToolSafety.WRITE, adb("pm install -r /sdcard/a.apk"), emptySet(),
            )
        ).isFalse()
    }

    @Test
    fun `granting one category does not release another`() {
        val st = AppSettings(shellGrants = setOf("install"))
        assertThat(
            ApprovalPolicy.needsApproval(
                st, "adb_shell", ToolSafety.WRITE, adb("wm size 1080x2340"), emptySet(),
            )
        ).isTrue()
    }

    @Test
    fun `dangerous commands ignore grants and always ask`() {
        val st = AppSettings(shellGrants = setOf("install", "logs", "display", "battery", "other"))
        assertThat(
            ApprovalPolicy.needsApproval(
                st, "adb_shell", ToolSafety.WRITE, adb("rm -rf /system/app"), emptySet(),
            )
        ).isTrue()
    }

    @Test
    fun `adb shell is not exempted by a running device session`() {
        DeviceControlHub.begin("测试")
        try {
            assertThat(
                ApprovalPolicy.needsApproval(
                    settings(AgentMode.AUTO_ALL), "adb_shell", ToolSafety.WRITE,
                    adb("logcat -d"), emptySet(),
                )
            ).isTrue()
        } finally {
            DeviceControlHub.end()
        }
    }
}
