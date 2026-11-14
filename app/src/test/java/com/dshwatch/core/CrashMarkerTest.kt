package com.dshwatch.core

import com.dshwatch.core.CrashLog.MarkerVerdict
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 启动标记判定。
 *
 * 这几条守的不是"代码能跑"，是**用户会不会被一张假错误卡片拦住**。
 *
 * 背景：1.23.0 上线后，用户每次打开 App 都看到「上次没能启动完成」。
 * 查下来是两处假阳性叠在一起 ——
 *
 *   1. 定时任务 / 前台服务把进程拉起来（Application.onCreate 照样写"启动中"
 *      标记），但那次根本没有界面，进程被回收后标记就留着；
 *   2. 收尾写在 Activity 的协程里，界面被回收重建时会被取消，标记漏删。
 *
 * 判定顺序就是按这两条的严重程度排的：先看有没有界面，再看有没有更新的
 * 成功记录，最后才拿安装时间兜底。
 */
class CrashMarkerTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `后台唤醒不报警`() {
        // 闹钟拉起进程：写了启动标记，但没有任何界面
        val verdict = CrashLog.judgeMarkerVerdict(
            wroteAt = t0,
            installedAt = t0 - 86_400_000,
            lastOkAt = 0,
            uiAt = 0,
        )
        assertThat(verdict).isEqualTo(MarkerVerdict.BACKGROUND)
    }

    @Test
    fun `有界面但没走到收尾 且没有更新的成功记录 才算崩溃`() {
        val verdict = CrashLog.judgeMarkerVerdict(
            wroteAt = t0,
            installedAt = t0 - 86_400_000,
            lastOkAt = t0 - 3_600_000,
            uiAt = t0 - 5,
        )
        assertThat(verdict).isEqualTo(MarkerVerdict.CRASHED)
    }

    @Test
    fun `成功记录比标记新 说明标记是漏删的残留`() {
        // 这是"每次启动都弹"的另一条路径：收尾跑到一半被打断，
        // 成功时间写了、标记没删掉。下次不能被它拦住。
        val verdict = CrashLog.judgeMarkerVerdict(
            wroteAt = t0,
            installedAt = t0 - 86_400_000,
            lastOkAt = t0 + 1,
            uiAt = t0 - 5,
        )
        assertThat(verdict).isEqualTo(MarkerVerdict.STALE)
    }

    @Test
    fun `标记早于安装时间 是覆盖安装杀的进程`() {
        val verdict = CrashLog.judgeMarkerVerdict(
            wroteAt = t0 - 86_400_000,
            installedAt = t0,
            lastOkAt = 0,
            uiAt = t0 - 86_400_000,
        )
        assertThat(verdict).isEqualTo(MarkerVerdict.STALE)
    }

    @Test
    fun `安装时间读不出来时宁可信其有`() {
        val verdict = CrashLog.judgeMarkerVerdict(
            wroteAt = t0,
            installedAt = 0,
            lastOkAt = 0,
            uiAt = t0 - 5,
        )
        assertThat(verdict).isEqualTo(MarkerVerdict.CRASHED)
    }
}
