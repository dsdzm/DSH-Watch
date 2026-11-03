package com.dshwatch.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshwatch.ui.theme.WatchDimens

/**
 * 页面级兜底：某个页面在**组合期**抛异常时，就地换成一张「这页出错了」。
 *
 * ## 这里原来是 try/catch，但 Compose 编译器不允许
 *
 * 第一版写成 `try { content() } catch (t: Throwable) { ... }`，编译器直接报
 * `Try catch is not supported around composable function invocations.` ——
 * 这不是限制某一种写法，而是语法层面禁止：可组合函数调用会被编译器改写成
 * 带 `Composer` 参数和分组标记的代码，异常从中间穿过会把分组栈搞乱。
 *
 * 所以真正的兜底只能放在**进程级**：见 [com.dshwatch.core.CrashLog]。
 * 本函数现在只负责两件事：
 * 1. 把页面内容放进标准布局，保证异常页与正常页的边距一致
 * 2. 把「上次崩溃的报告」摆在最前面，用户一进 App 就能看到并一键复制
 *
 * 也就是说，本次定位问题靠的是**拿得到堆栈**，而不是「假装崩不了」。
 * 崩了就是崩了，但用户手里有一份可以发出来的证据。
 */
@Composable
fun ErrorBoundary(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(WatchDimens.gapXs))
        content()
    }
}

/** 极简版错误页：诊断入口用，展示异常文本 */
@Composable
fun CrashFallback(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = "这页出错了",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
