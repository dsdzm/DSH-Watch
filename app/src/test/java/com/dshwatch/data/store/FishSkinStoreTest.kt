package com.dshwatch.data.store

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 养鱼页外观的存取。
 *
 * 重点不是"能不能存"，而是**存进去的东西一定能画出来**：
 * 越界的字号、300dp 的头像、把一张 txt 当背景 —— 这些一旦落盘，
 * 症状是"页面变空了"，而用户完全不知道是自己那句话导致的。
 */
class FishSkinStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(root: File) = FishSkinStore(
        workspaceRoot = { root },
        json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    )

    @Test
    fun `没有配置时给默认值，不报错`() = runTest {
        val root = tmp.newFolder("empty")
        val s = store(root)
        val skin = s.load()
        assertThat(skin).isEqualTo(com.dshwatch.domain.fish.FishSkin.DEFAULT)
        // 默认是全空的：名字空、没有头像、没有背景
        assertThat(skin.name).isEmpty()
        assertThat(skin.avatar).isEmpty()
        assertThat(skin.background).isEmpty()
    }

    @Test
    fun `越界的值在写入口就被夹回范围`() = runTest {
        val root = tmp.newFolder("clamp")
        val s = store(root)
        val saved = s.save(
            com.dshwatch.domain.fish.FishSkin(
                name = "一二三四五六七八九十十一十二",
                avatarSize = 200,
                textSize = 99,
                backgroundDim = 5f,
            ),
        )
        // 名字截到 12 个字、头像不超过 64dp、字号不超过 22、压暗不超过 0.85
        assertThat(saved.name.length).isEqualTo(12)
        assertThat(saved.avatarSize).isEqualTo(64)
        assertThat(saved.textSize).isEqualTo(22)
        assertThat(saved.backgroundDim).isEqualTo(0.85f)
    }

    @Test
    fun `写进去的能读回来，并且推送到了 flow`() = runTest {
        val root = tmp.newFolder("round")
        val s = store(root)
        s.save(com.dshwatch.domain.fish.FishSkin(name = "阿鲤", accent = 0xFF3A7BD5))
        // flow 是页面唯一的数据源：它不更新，改完界面就不动
        assertThat(s.skin.value.name).isEqualTo("阿鲤")

        val again = store(root)
        val loaded = again.load()
        assertThat(loaded.name).isEqualTo("阿鲤")
        assertThat(loaded.accent).isEqualTo(0xFF3A7BD5L)
    }

    @Test
    fun `图片会被复制进 fish 目录并返回相对路径`() = runTest {
        val root = tmp.newFolder("img")
        val src = File(tmp.newFolder("src"), "wall.JPG").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val s = store(root)

        val rel = s.importImage(src, "background").getOrThrow()

        assertThat(rel).isEqualTo("fish/background.JPG")
        assertThat(File(root, rel).isFile).isTrue()
        // 原图还在（我们只是复制，不该动用户的文件）
        assertThat(src.isFile).isTrue()
        assertThat(s.imageOf(rel)?.length()).isEqualTo(3L)
    }

    @Test
    fun `换扩展名时不会留下两张`() = runTest {
        val root = tmp.newFolder("swap")
        val s = store(root)
        val a = File(tmp.newFolder("a"), "x.png").apply { writeBytes(byteArrayOf(1)) }
        val b = File(tmp.newFolder("b"), "x.webp").apply { writeBytes(byteArrayOf(2)) }

        s.importImage(a, "avatar").getOrThrow()
        s.importImage(b, "avatar").getOrThrow()

        val left = File(root, "fish").listFiles()!!.filter { it.name.startsWith("avatar") }
        // 两张同时留着时 imageOf 会拿到旧的那张 —— 换头像看起来"没生效"
        assertThat(left).hasSize(1)
        assertThat(left.single().name).isEqualTo("avatar.webp")
    }

    @Test
    fun `不是图片的文件会被拒绝`() = runTest {
        val root = tmp.newFolder("bad")
        val src = File(tmp.newFolder("t"), "notes.txt").apply { writeText("hi") }
        val res = store(root).importImage(src, "avatar")
        assertThat(res.isFailure).isTrue()
        assertThat(res.exceptionOrNull()?.message).contains("图片")
    }

    @Test
    fun `reset 会把配置与图片一起清掉`() = runTest {
        val root = tmp.newFolder("reset")
        val s = store(root)
        val src = File(tmp.newFolder("r"), "a.png").apply { writeBytes(byteArrayOf(9)) }
        s.save(com.dshwatch.domain.fish.FishSkin(name = "阿鲤", avatar = s.importImage(src, "avatar").getOrThrow()))

        s.reset()

        assertThat(s.skin.value).isEqualTo(com.dshwatch.domain.fish.FishSkin.DEFAULT)
        // 只改配置不删图，用户的壁纸会一直占着几十 MB 却再也看不到
        val images = File(root, "fish").listFiles()?.filter { it.extension.lowercase() != "json" }
        assertThat(images).isEmpty()
    }

    /**
     * 写不进去时必须抛，不能假装写好了。
     *
     * 工具回「改好了」而磁盘上什么都没有，用户退出重进就发现全变回去 ——
     * 这比直接说"存不下来"糟糕得多。
     */
    @Test
    fun `写不进去时 saveOrThrow 会抛`() = runTest {
        val fakeRoot = tmp.newFile("not-a-dir")
        val s = store(fakeRoot)

        val err = runCatching {
            s.saveOrThrow(com.dshwatch.domain.fish.FishSkin(name = "阿鲤"))
        }.exceptionOrNull()

        assertThat(err).isNotNull()
    }

    /** 落盘是原子的：不能留下半个 json 让下次启动解析失败 */
    @Test
    fun `写完不留临时文件，并且能读回同样的值`() = runTest {
        val root = tmp.newFolder("atomic")
        val s = store(root)
        s.save(com.dshwatch.domain.fish.FishSkin(name = "阿鲤", backgroundDim = 0.5f, showAvatar = false))

        val fish = File(root, "fish")
        assertThat(fish.listFiles()!!.map { it.name }).containsExactly("skin.json")
        assertThat(store(root).load().showAvatar).isFalse()
        assertThat(store(root).load().backgroundDim).isEqualTo(0.5f)
    }

}
