package com.dshwatch.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 工具的图标映射。
 *
 * 手表上工具卡片只有一行能放，文字之外必须有个一眼可辨的图形 ——
 * 用户扫一眼就知道"它在读文件"还是"它在打包"。
 *
 * 图标只用 `material-icons-core` 里有的那些（约 40 个），
 * 不引 extended 包 —— 那会让 APK 多出好几 MB，对手表不值得。
 */
object ToolVisuals {

    fun icon(tool: String): ImageVector = when (tool) {
        // 信息类
        "get_datetime" -> Icons.Filled.DateRange
        "calculate" -> Icons.Filled.Build
        "get_device_info" -> Icons.Filled.Settings
        "get_token_usage" -> Icons.Filled.ShoppingCart
        "http_fetch" -> Icons.Filled.Share
        "web_search" -> Icons.Filled.LocationOn
        "unit_convert" -> Icons.Filled.Refresh
        "get_clipboard" -> Icons.Filled.MailOutline

        // 提醒与日程
        "set_alarm" -> Icons.Filled.Notifications
        "list_alarms" -> Icons.Filled.Notifications
        "cancel_alarm" -> Icons.Filled.Clear
        "vibrate" -> Icons.Filled.Phone
        "create_event" -> Icons.Filled.DateRange

        // 交互 / 导航
        "ask_user" -> Icons.Filled.Face
        "todo_write" -> Icons.Filled.Check
        "open_file" -> Icons.Filled.ArrowForward
        "open_screen" -> Icons.Filled.Home

        // 文件类
        "list_files" -> Icons.Filled.List
        "read_file" -> Icons.Filled.Info
        "write_file" -> Icons.Filled.Create
        "edit_file" -> Icons.Filled.Edit
        "append_file" -> Icons.Filled.Add
        "make_dir" -> Icons.Filled.Add
        "move_file" -> Icons.Filled.ArrowForward
        "delete_file" -> Icons.Filled.Delete
        "glob" -> Icons.Filled.Search
        "grep" -> Icons.Filled.LocationOn

        "build_project" -> Icons.Filled.PlayArrow

        // 执行与云端
        "run_script" -> Icons.Filled.PlayArrow
        "cloud_build" -> Icons.Filled.Share
        "cloud_job" -> Icons.Filled.Info

        // MCP 工具名字形如 mcp_<server>_<tool>，用 Share 图标表示"数据会出去"，
        // 让用户一眼看出这不是 App 自带的能力
        else -> if (tool.startsWith("mcp_")) Icons.Filled.Share else Icons.Filled.Build
    }

    /** 会产出文件、值得渲染「产物卡片」的工具 */
    fun producesArtifact(tool: String): Boolean =
        tool == "build_project" || tool == "cloud_build" || tool == "cloud_job" ||
            tool == "run_script"
}
