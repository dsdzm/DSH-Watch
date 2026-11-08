package com.dshwatch.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 内置通道的边界。
 *
 * 这里的用例都来自真实踩过的坑，不是补覆盖率：
 *
 *  - `isBuiltin` 曾经写成 `id == BuiltinProvider.ID`，加了智谱之后它只认 Agnes。
 *    后果是智谱那一项在设置页被当成普通供应商 —— 地址栏和 Key 输入框直接摊开，
 *    「拉取模型」还能把官方全量付费档拉进列表。用户看到的就是「说好的隐藏呢」。
 *  - 模型名单必须锁死成免费档，且默认模型必须在名单里，
 *    否则点开就是 400 / 额度不足。
 */
class BuiltinProviderTest {

    @Test
    fun `zhipu is recognized as builtin`() {
        val zhipu = BuiltinProviders.make(BuiltinProviders.ZHIPU)
        assertThat(zhipu.isBuiltin).isTrue()
    }

    @Test
    fun `agnes is still recognized as builtin`() {
        val agnes = BuiltinProviders.make(BuiltinProviders.AGNES)
        assertThat(agnes.isBuiltin).isTrue()
    }

    @Test
    fun `ordinary provider is not builtin`() {
        val custom = Provider(
            id = "custom-1",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
        )
        assertThat(custom.isBuiltin).isFalse()
    }

    @Test
    fun `legacy entry recognizes every builtin family`() {
        // 兼容壳 BuiltinProvider.matches 之前只比 Agnes 的 id，同样会漏掉智谱
        assertThat(BuiltinProvider.matches(BuiltinProviders.make(BuiltinProviders.ZHIPU))).isTrue()
        assertThat(BuiltinProvider.matches(BuiltinProviders.make(BuiltinProviders.AGNES))).isTrue()
    }

    @Test
    fun `every builtin exposes a non-empty fixed model list`() {
        BuiltinProviders.all.forEach { spec ->
            assertThat(spec.models).isNotEmpty()
            assertThat(spec.defaultModel).isIn(spec.models)
            assertThat(spec.baseUrl).startsWith("https://")
            assertThat(spec.id).startsWith("builtin-")
        }
    }

    @Test
    fun `zhipu only ships the free tier`() {
        val models = BuiltinProviders.ZHIPU.models
        assertThat(models).containsExactly(
            "glm-4.7-flash",
            "glm-4.6v-flash",
            "cogview-3-flash",
        )
        // 付费档一个都不能漏进来：这几个名字在官方 /v1/models 里是有的
        assertThat(models).containsNoneOf(
            "glm-4-plus",
            "glm-4.5",
            "glm-4.6",
            "glm-4v-plus",
            "cogview-4",
            "cogview-3-plus",
        )
    }

    @Test
    fun `zhipu only lets the vision model carry image input`() {
        // glm-4.7-flash 是纯文本，发 image_url 会 400
        assertThat(BuiltinProviders.ZHIPU.visionModels).containsExactly("glm-4.6v-flash")
        BuiltinProviders.ZHIPU.visionModels.forEach {
            assertThat(it).isIn(BuiltinProviders.ZHIPU.models)
        }
    }

    @Test
    fun `agnes vision models all exist in its list`() {
        BuiltinProviders.AGNES.visionModels.forEach {
            assertThat(it).isIn(BuiltinProviders.AGNES.models)
        }
    }
}
