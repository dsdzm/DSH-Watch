package com.dshwatch.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.core.CrashLog

/**
 * 启动时的「上次崩溃了」提示。
 *
 * ## 为什么值得单独做一个界面
 *
 * 前面几轮的 bug 都能自己复现、自己量像素解决。但「点进某个页面就崩」
 * 这类问题，开发者手里只有一张「屡次停止运行」的截图 —— 不知道崩在哪一行，
 * 只能靠猜 R8 规则、猜空指针，猜错一次就是一轮返工。
 *
 * 所以把崩溃现场做成用户能一键带走的东西：一个复制按钮，粘到聊天窗口就行。
 * 报告里有版本、机型、完整堆栈，还有崩溃前最后几条 warning。
 *
 * ## 只有第一次启动才弹
 *
 * 用户点「知道了」就删掉文件。否则每开一次 App 弹一次，比崩溃本身还烦。
 */
@Composable
fun CrashNotice() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(CrashLog.lastText) }

    val body = text ?: return
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(26.dp)

    GlassDialog(
        onDismissRequest = {
            CrashLog.clear(context)
            text = null
        },
        title = "上次崩溃了",
        maxContentHeight = 220,
        // 下面那段堆栈自带 verticalScroll（高度固定，方便选中复制），
        // 外层再叠一层滚动就是「滚动套滚动」，会拿到无限高直接崩
        scrollable = false,
        content = {
            Text(
                text = "把下面这段发给开发者，就能定位到具体哪一行。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            // 堆栈很长，默认只露前几行；展开后整段可选可滚动
            val shown = if (expanded) body else body.lineSequence().take(6).joinToString("\n")
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (expanded) 150.dp else 74.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = shown,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expanded) "收起" else "展开全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .tap { expanded = !expanded }
                    .padding(vertical = 4.dp),
            )
        },
        buttons = {
            GlassDialogButton("复制") {
                clipboard.setText(AnnotatedString(body))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
            GlassDialogButton("知道了", primary = true) {
                CrashLog.clear(context)
                text = null
            }
        },
    )
}

/**
 * 崩溃页共用的外壳：说明 + 报告 + 两个按钮。
 *
 * 真正「页面级的兜底」由 [ErrorBoundary] 实现，
 * 这里只负责把 [CrashLog] 里记下的最后一次崩溃展示出来。
 */
@Composable
fun CrashReportCard(
    title: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(CrashLog.lastText) }
    val body = text ?: return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(18.dp), cornerRadius = 18.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body.lineSequence().take(10).joinToString("\n"),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 10,
        )
        Spacer(Modifier.height(8.dp))
        GhostButton(text = "复制报告") {
            clipboard.setText(AnnotatedString(body))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
        Spacer(Modifier.height(4.dp))
        GhostButton(text = "清除") {
            CrashLog.clear(context)
            text = null
        }
    }
}
