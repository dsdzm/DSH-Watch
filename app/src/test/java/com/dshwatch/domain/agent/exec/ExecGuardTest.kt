package com.dshwatch.domain.agent.exec

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 执行闸门。
 *
 * 它守的是手表上最糟糕的一种状态：多个失控脚本叠在一起跑，
 * 设备卡到用户连"停止"都点不动，只能长按电源键。
 */
class ExecGuardTest {

    @Test
    fun `only one execution may run at a time`() {
        val guard = ExecGuard()
        assertThat(guard.tryAcquire()).isTrue()
        assertThat(guard.tryAcquire()).isFalse()
        assertThat(guard.busy.value).isTrue()
    }

    @Test
    fun `releasing lets the next one in`() {
        val guard = ExecGuard()
        guard.tryAcquire()
        guard.release()
        assertThat(guard.busy.value).isFalse()
        assertThat(guard.tryAcquire()).isTrue()
    }

    @Test
    fun `busy flag follows the state`() {
        val guard = ExecGuard()
        assertThat(guard.busy.value).isFalse()
        guard.tryAcquire()
        assertThat(guard.busy.value).isTrue()
        guard.release()
        assertThat(guard.busy.value).isFalse()
    }

    @Test
    fun `force release frees a stuck gate`() {
        // Python 超时后解释器可能仍在跑，闸门会一直占着 —— 这是给用户的逃生口
        val guard = ExecGuard()
        guard.tryAcquire()
        guard.forceRelease()
        assertThat(guard.busy.value).isFalse()
        assertThat(guard.tryAcquire()).isTrue()
    }

    @Test
    fun `force release on a free gate does not create extra permits`() {
        // 多放出一个额度就等于并发限制失效，所以只在确实被占用时才放
        val guard = ExecGuard()
        guard.forceRelease()
        assertThat(guard.tryAcquire()).isTrue()
        assertThat(guard.tryAcquire()).isFalse()
    }

    @Test
    fun `shared instance is a singleton`() {
        assertThat(ExecGuard.shared).isSameInstanceAs(ExecGuard.shared)
    }
}
