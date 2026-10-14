package com.dshwatch

import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.PerfDetector
import com.dshwatch.domain.PerfProfiles
import com.dshwatch.domain.model.PerfMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PerfProfileTest {

    @Test
    fun `low ram device is classified low`() {
        val tier = PerfDetector.classify(isLowRamDevice = true, totalMemMb = 4096, cores = 8)
        assertThat(tier).isEqualTo(DeviceTier.LOW)
    }

    @Test
    fun `4gb quad core watch is HIGH not LOW`() {
        // 这正是目标设备：4+64 / 1200mAh。
        // 旧逻辑会因为「是手表」和「Android 9」被罚到低功耗档，白关掉着色和渲染条数。
        val tier = PerfDetector.classify(isLowRamDevice = false, totalMemMb = 4096, cores = 4)
        assertThat(tier).isEqualTo(DeviceTier.HIGH)
    }

    @Test
    fun `2gb eight core watch is HIGH`() {
        val tier = PerfDetector.classify(isLowRamDevice = false, totalMemMb = 2048, cores = 8)
        assertThat(tier).isEqualTo(DeviceTier.HIGH)
    }

    @Test
    fun `1gb quad core watch is MID`() {
        val tier = PerfDetector.classify(isLowRamDevice = false, totalMemMb = 1024, cores = 4)
        assertThat(tier).isEqualTo(DeviceTier.MID)
    }

    @Test
    fun `512mb watch is LOW`() {
        val tier = PerfDetector.classify(isLowRamDevice = false, totalMemMb = 512, cores = 4)
        assertThat(tier).isEqualTo(DeviceTier.LOW)
    }

    @Test
    fun `corner inset estimate stays in a sane range`() {
        // 410px / 2x = 205dp  →  8% ≈ 16dp
        assertThat(com.dshwatch.domain.DeviceProfiler.estimateCornerInsetDp(205)).isEqualTo(16)
        assertThat(com.dshwatch.domain.DeviceProfiler.estimateCornerInsetDp(120)).isEqualTo(9)
        assertThat(com.dshwatch.domain.DeviceProfiler.estimateCornerInsetDp(400)).isEqualTo(20)
        assertThat(com.dshwatch.domain.DeviceProfiler.estimateCornerInsetDp(0)).isEqualTo(12)
    }

    @Test
    fun `power save mode forces the cheapest profile`() {
        val profile = PerfProfiles.of(PerfMode.POWER_SAVE, DeviceTier.HIGH)
        assertThat(profile.enableAnimations).isFalse()
        assertThat(profile.enableCodeHighlight).isFalse()
        assertThat(profile.maxRenderedMessages).isAtMost(24)
        assertThat(profile.streamThrottleMs).isAtLeast(260L)
    }

    @Test
    fun `performance mode enables everything`() {
        val profile = PerfProfiles.of(PerfMode.PERFORMANCE, DeviceTier.LOW)
        assertThat(profile.enableAnimations).isTrue()
        assertThat(profile.enableCodeHighlight).isTrue()
        assertThat(profile.streamThrottleMs).isEqualTo(60L)
    }

    @Test
    fun `low tier disables expensive rendering`() {
        val profile = PerfProfiles.of(PerfMode.AUTO, DeviceTier.LOW)
        assertThat(profile.enableCodeHighlight).isFalse()
        assertThat(profile.maxRenderedMessages).isEqualTo(30)
        assertThat(profile.requestHistoryLimit).isAtMost(20)
    }
}
