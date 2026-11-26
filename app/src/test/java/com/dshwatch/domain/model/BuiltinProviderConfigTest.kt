package com.dshwatch.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 内置通道的「就绪」状态。
 *
 * 这里不硬断言「有 Key」或「没有 Key」—— 同一份源码会被编成两种包
 * （发版机上有 Key，别人的机器上没有），断言某一侧必然在另一台机器上失败。
 * 要守住的是**读数一致**：界面拿 [BuiltinProviders.isConfigured] 判断，
 * 请求拿 [BuiltinProviders.keyOf] 取值，两者在同一个包里必须给出同一个结论。
 * 一旦不一致，就会出现「界面说内置可用、一发消息就 401」。
 */
class BuiltinProviderConfigTest {

    @Test
    fun `就绪状态与实际取值一致`() {
        BuiltinProviders.all.forEach { spec ->
            val key = spec.key().trim()
            assertThat(spec.configured).isEqualTo(key.isNotEmpty())
            assertThat(BuiltinProviders.isConfigured(spec.id)).isEqualTo(key.isNotEmpty())
        }
    }

    @Test
    fun `没有 Key 时 keyOf 返回 null 而不是空串`() {
        BuiltinProviders.all.forEach { spec ->
            val resolved = BuiltinProviders.keyOf(spec.id)
            if (spec.key().isBlank()) {
                // 返回空串会让调用方以为"取到了"，一路走到请求头里才炸
                assertThat(resolved).isNull()
            } else {
                assertThat(resolved).isEqualTo(spec.key())
            }
        }
    }

    @Test
    fun `地址写死，不允许被存档改掉`() {
        // 内置额度的防线之一：请求只能打到自家端点。地址一旦可配，
        // 用户（或一次误操作）就能把内置 Key 发到别的服务器上
        assertThat(BuiltinProviders.AGNES.baseUrl).isEqualTo("https://api.agnes-ai.cn/v1")
        assertThat(BuiltinProviders.ZHIPU.baseUrl).isEqualTo("https://open.bigmodel.cn/api/paas/v4")
    }
}
