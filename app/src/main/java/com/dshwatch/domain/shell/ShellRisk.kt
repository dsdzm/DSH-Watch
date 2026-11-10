package com.dshwatch.domain.shell

/**
 * 命令的风险分级与归类。
 *
 * 这是「提权」这件事的安全边界，所以刻意做成**纯 Kotlin、不碰 Android**：
 * 判定错一次就是把系统文件交给模型，必须能被单测逐条钉住。
 *
 * ## 两档
 *
 * - [ShellRisk.NORMAL]：一次确认，用户点「始终允许这类」之后同类别不再问；
 * - [ShellRisk.DANGER]：红色警告 + **两次**确认，且**永不记住** —— 每次都要重新问。
 *
 * ## 判定顺序
 *
 * 危险规则先于分类规则。宁可把 `rm -rf /sdcard/tmp` 误判成危险（多问一次），
 * 也不能把 `rm -rf /system/app` 漏成普通（少问一次就够毁机）。
 *
 * ## 为什么要分段
 *
 * `echo ok && rm -rf /system` 这种拼接很常见。只匹配整条命令的开头会漏掉后段，
 * 所以先按 `;` `&&` `||` `|` 切段，**任一段命中危险规则就整条危险**。
 */
enum class ShellRisk {
    NORMAL,
    DANGER,
}

/**
 * 授权类别。
 *
 * 「始终允许」按类别记，不按命令记：用户点过一次「装应用总是允许」，
 * 下次装另一个应用不该再问；但他没同意的事（比如改屏幕）仍然要问。
 */
enum class ShellCategory(val key: String, val label: String) {
    INSTALL("install", "安装应用"),
    LOGS("logs", "读取日志"),
    DISPLAY("display", "调整屏幕"),
    BATTERY("battery", "电量与电源"),
    OTHER("other", "其他命令"),
}

object ShellRules {

    /**
     * 受保护的路径前缀。
     *
     * 这些目录下删/改东西，轻则应用崩溃，重则开不了机。
     * `/sdcard`、`/data/local/tmp` 不在其中 —— 那是正常可写区。
     */
    private val PROTECTED_PREFIXES = listOf(
        "/system", "/vendor", "/product", "/odm", "/oem",
        "/data/app", "/data/system", "/data/data", "/data/adb", "/data/misc",
        "/sdcard/Android/data", "/storage/emulated/0/Android/data",
        "/apex", "/init.rc", "/default.prop",
    )

    /** 会破坏数据或设备的命令 */
    private val DESTRUCTIVE = setOf(
        "rm", "rmdir", "unlink", "shred", "dd", "mkfs", "mkfs.ext4", "fdisk",
        "mke2fs", "wipe", "fastboot",
    )

    private val SEGMENT = Regex("""\|\||&&|;|\n|\|""")

    /** 写入受保护路径（`> /system/x`、`>> /vendor/y`） */
    private val WRITE_PROTECTED = Regex(
        """>>?\s*(/system|/vendor|/product|/odm|/data/app|/data/system|/data/data|/apex)""",
    )

    /** 把下载来的内容直接喂给 shell：`curl x | sh` */
    private val PIPE_TO_SHELL = Regex("""\|\s*(sh|bash|su)(\s|$)""")

    fun isProtectedPath(path: String): Boolean {
        val full = path.trim().trim('"', '\'')
        return PROTECTED_PREFIXES.any { full == it || full.startsWith("$it/") }
    }

    /**
     * 命中危险规则时返回一句给用户看的原因；安全返回 null。
     */
    fun dangerReason(command: String): String? {
        val raw = command.trim()
        if (raw.isEmpty()) return "命令为空"

        if (PIPE_TO_SHELL.containsMatchIn(raw)) {
            return "把网络或管道内容直接交给 shell 执行"
        }
        if (WRITE_PROTECTED.containsMatchIn(raw)) {
            return "直接写入系统分区"
        }

        for (seg in raw.split(SEGMENT)) {
            val tokens = seg.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            // 去掉 `sudo` / `su -c` 这类前缀
            val head = tokens.first().substringAfterLast('/')
            when {
                head == "su" || head == "sudo" -> return "尝试获取 root 权限"

                head == "reboot" || head == "shutdown" || (head == "svc" && tokens.getOrNull(1) == "power") ->
                    return "重启或关机"

                head == "setenforce" -> return "修改 SELinux 状态"

                head == "mount" && seg.contains("remount") -> return "以可写方式重新挂载分区"

                head == "pm" && tokens.getOrNull(1) in setOf("uninstall", "clear", "disable-user", "disable") ->
                    return "卸载应用或清除其数据"

                head == "cmd" && tokens.getOrNull(1) == "package" &&
                    tokens.getOrNull(2) in setOf("uninstall", "clear") -> return "卸载应用或清除其数据"

                head in DESTRUCTIVE -> {
                    val targets = tokens.drop(1).filterNot { it.startsWith("-") }
                    if (targets.isEmpty()) return "破坏性命令缺少明确目标"
                    if (targets.any { isProtectedPath(it) }) {
                        return "删除或改写系统目录：${targets.first { isProtectedPath(it) }}"
                    }
                }
            }
        }
        return null
    }

    fun classify(command: String): ShellRisk =
        if (dangerReason(command) != null) ShellRisk.DANGER else ShellRisk.NORMAL

    fun categoryOf(command: String): ShellCategory {
        val c = command.lowercase()
        val head = c.trim().split(Regex("""\s+""")).firstOrNull().orEmpty().substringAfterLast('/')
        return when {
            // 注意 uninstall 也含 "install"，必须先排除
            head == "pm" && c.contains("install") && !c.contains("uninstall") -> ShellCategory.INSTALL
            head == "cmd" && c.contains("package install") -> ShellCategory.INSTALL
            head == "install" -> ShellCategory.INSTALL

            // 电量判定要排在日志前面：`dumpsys battery` 是改电量，不是读日志
            c.contains("battery") || c.contains("low_power") ||
                c.contains("svc power") || c.contains("power_save") -> ShellCategory.BATTERY

            head == "logcat" || head == "dumpsys" || head == "bugreport" ||
                head == "dmesg" || head == "log" -> ShellCategory.LOGS

            c.contains("wm size") || c.contains("wm density") || c.contains("wm overscan") ||
                c.contains("screen_off_timeout") || c.contains("font_scale") ||
                c.contains("display_density") -> ShellCategory.DISPLAY

            else -> ShellCategory.OTHER
        }
    }

    /** 界面上一句话概括这条命令要干什么（不依赖模型自述，防它美化） */
    fun brief(command: String): String {
        val c = command.trim()
        return when (categoryOf(c)) {
            ShellCategory.INSTALL -> "安装一个应用"
            ShellCategory.LOGS -> "读取系统日志"
            ShellCategory.DISPLAY -> "调整屏幕参数"
            ShellCategory.BATTERY -> "读取或修改电量相关设置"
            ShellCategory.OTHER -> c.take(40)
        }
    }
}
