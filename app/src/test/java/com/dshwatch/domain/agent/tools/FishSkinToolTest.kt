package com.dshwatch.domain.agent.tools

import com.dshwatch.data.store.FishSkinStore
import com.dshwatch.data.usage.UsageRepository
import com.dshwatch.data.workspace.WorkspaceStore
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.DeviceTier
import com.dshwatch.domain.agent.ToolContext
import com.dshwatch.domain.fish.FishSkin
import com.dshwatch.domain.roleplay.Scenes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `fish_skin`：用户说一句「背景换成深蓝、名字叫阿鲤」，她自己把页面改掉。
 *
 * 这个工具最容易出的不是崩溃，而是**默默改错**：
 * 颜色写法认错 → 存成全透明 → 页面"改了但看不见"；
 * 图片路径没用上 → 背景还在 → 用户以为她没听懂。
 * 所以断言都盯着"存进去的那个值"。
 */
class FishSkinToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun profile() = DeviceProfile(
        tier = DeviceTier.MID,
        screenWidthPx = 410,
        screenHeightPx = 502,
        screenWidthDp = 205,
        screenHeightDp = 251,
        densityDpi = 320,
        cornerInsetDp = 14,
        autoCornerInsetDp = 14,
        totalMemMb = 4096,
        cores = 4,
        isWatch = true,
        sdkInt = 28,
        compact = true,
        squarish = false,
        isRound = false,
    )

    private suspend fun ctx(root: File): Pair<ToolContext, FishSkinStore> {
        val store = FishSkinStore(
            workspaceRoot = { root },
            json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
        )
        val c = ToolContext(
            appContext = null,
            workspace = WorkspaceStore(root),
            usage = UsageRepository(tmp.newFolder(), Json),
            profile = { profile() },
            workspaceRoot = { root.absolutePath },
            fishSkin = store,
        )
        return c to store
    }

    private fun args(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `只在养鱼场景出现`() {
        assertThat(FishSkinTool.onlyInScene).isEqualTo(Scenes.FISH)
    }

    @Test
    fun `名字会被写进外观`() = runTest {
        val root = tmp.newFolder("name")
        val (c, store) = ctx(root)

        val res = FishSkinTool.execute(args("name" to kotlinx.serialization.json.JsonPrimitive("阿鲤")), c)

        assertThat(res.ok).isTrue()
        assertThat(store.skin.value.name).isEqualTo("阿鲤")
        // 回话里要带上改了什么，否则她只能说"好了"，用户不知道到底成没成
        assertThat(res.content).contains("阿鲤")
    }

    @Test
    fun `只说名字时其他项保持原样`() = runTest {
        val root = tmp.newFolder("keep")
        val (c, store) = ctx(root)
        store.save(FishSkin(name = "旧名", textSize = 18, accent = 0xFFFF8FA3))

        FishSkinTool.execute(args("name" to kotlinx.serialization.json.JsonPrimitive("新名")), c)

        val now = store.skin.value
        assertThat(now.name).isEqualTo("新名")
        // 参数没提到的一律不动 —— 这是"只改他提到的那项"的实现保证
        assertThat(now.textSize).isEqualTo(18)
        assertThat(now.accent).isEqualTo(0xFFFF8FA3L)
    }

    @Test
    fun `颜色支持三种写法，非法写法会被拒绝且不落盘`() = runTest {
        assertThat(FishSkinTool.parseColor("#3A7BD5")).isEqualTo(0xFF3A7BD5L)
        assertThat(FishSkinTool.parseColor("3a7bd5")).isEqualTo(0xFF3A7BD5L)
        assertThat(FishSkinTool.parseColor("#803A7BD5")).isEqualTo(0x803A7BD5L)
        assertThat(FishSkinTool.parseColor("#12345")).isNull()
        assertThat(FishSkinTool.parseColor("深蓝")).isNull()

        val root = tmp.newFolder("color")
        val (c, store) = ctx(root)
        val res = FishSkinTool.execute(
            args("accent" to kotlinx.serialization.json.JsonPrimitive("深蓝")),
            c,
        )
        assertThat(res.ok).isFalse()
        // 报错时绝不能顺手把默认值写进去：用户看到"改失败"但页面已经变了更糟
        assertThat(store.skin.value.accent).isEqualTo(0L)
    }

    @Test
    fun `图片路径有效时会复制进 fish 目录`() = runTest {
        val root = tmp.newFolder("bg")
        val (c, store) = ctx(root)
        val img = File(root, "pic.png").apply { writeBytes(byteArrayOf(7, 7)) }

        val res = FishSkinTool.execute(
            args("background" to kotlinx.serialization.json.JsonPrimitive("pic.png")),
            c,
        )

        assertThat(res.ok).isTrue()
        // 存的是 relativе 路径，且文件真的在
        assertThat(store.skin.value.background).isEqualTo("fish/background.png")
        assertThat(File(root, "fish/background.png").isFile).isTrue()
    }

    @Test
    fun `图片路径不存在时明确报错`() = runTest {
        val root = tmp.newFolder("nofile")
        val (c, _) = ctx(root)
        val res = FishSkinTool.execute(
            args("avatar" to kotlinx.serialization.json.JsonPrimitive("/sdcard/nope.png")),
            c,
        )
        assertThat(res.ok).isFalse()
        assertThat(res.content).contains("找不到")
    }

    @Test
    fun `空串表示去掉这张图`() = runTest {
        val root = tmp.newFolder("clear")
        val (c, store) = ctx(root)
        val img = File(root, "a.png").apply { writeBytes(byteArrayOf(1)) }
        FishSkinTool.execute(args("avatar" to kotlinx.serialization.json.JsonPrimitive("a.png")), c)
        assertThat(store.skin.value.avatar).isNotEmpty()

        FishSkinTool.execute(args("avatar" to kotlinx.serialization.json.JsonPrimitive("")), c)
        assertThat(store.skin.value.avatar).isEmpty()
    }

    @Test
    fun `reset 会全部还原`() = runTest {
        val root = tmp.newFolder("reset")
        val (c, store) = ctx(root)
        store.save(FishSkin(name = "阿鲤", textSize = 19))

        val res = FishSkinTool.execute(args("reset" to kotlinx.serialization.json.JsonPrimitive(true)), c)

        assertThat(res.ok).isTrue()
        assertThat(store.skin.value).isEqualTo(FishSkin.DEFAULT)
        assertThat(res.content).contains("默认")
    }

    @Test
    fun `没有存储时直接说改不了，不假装成功`() = runTest {
        val c = ToolContext(
            appContext = null,
            workspace = WorkspaceStore(tmp.newFolder()),
            usage = UsageRepository(tmp.newFolder(), Json),
            profile = { profile() },
            workspaceRoot = { "/tmp" },
            fishSkin = null,
        )
        val res = FishSkinTool.execute(args("name" to kotlinx.serialization.json.JsonPrimitive("阿鲤")), c)
        assertThat(res.ok).isFalse()
    }

    @Test
    fun `越界数字会被夹回范围`() = runTest {
        val root = tmp.newFolder("clamp")
        val (c, store) = ctx(root)
        FishSkinTool.execute(
            args(
                "avatar_size" to kotlinx.serialization.json.JsonPrimitive(999),
                "text_size" to kotlinx.serialization.json.JsonPrimitive(2),
            ),
            c,
        )
        assertThat(store.skin.value.avatarSize).isEqualTo(FishSkin.AVATAR_SIZE_RANGE.last)
        assertThat(store.skin.value.textSize).isEqualTo(FishSkin.TEXT_SIZE_RANGE.first)
    }

    /**
     * 改完必须真的落盘。
     *
     * 用户报的是「她改完样式，退出重进又变回原样」。这条测的就是那件事：
     * 换一个 store 实例（等价于重启进程）从同一个目录读，得读到她刚改的样子。
     */
    @Test
    fun `改完换个实例重新读还在`() = runTest {
        val root = tmp.newFolder("persist")
        val (c, _) = ctx(root)

        val res = FishSkinTool.execute(
            args(
                "name" to kotlinx.serialization.json.JsonPrimitive("阿鲤"),
                "accent" to kotlinx.serialization.json.JsonPrimitive("#3A7BD5"),
                "text_size" to kotlinx.serialization.json.JsonPrimitive(18),
            ),
            c,
        )
        assertThat(res.ok).isTrue()

        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val reopened = FishSkinStore(workspaceRoot = { root }, json = json)
        val loaded = reopened.load()

        assertThat(loaded.name).isEqualTo("阿鲤")
        assertThat(loaded.accent).isEqualTo(0xFF3A7BD5L)
        assertThat(loaded.textSize).isEqualTo(18)
        // 文件本身要在 fish/skin.json，用户插上电脑能看到
        assertThat(File(root, "fish/skin.json").isFile).isTrue()
    }

}
