package com.dshwatch.data.community

import com.dshwatch.core.Jsonx
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 社区缓存。
 *
 * 这几条都对应一个具体的线上体验：
 *
 *  - 切筛选回来还是空的 → 「内存命中」
 *  - 杀进程重进先闪一下"正在取卡" → 「落盘往返」
 *  - 缓存文件被写坏后每次启动都崩 → 「坏文件不该带崩启动」
 *  - 点赞完退回列表还是旧数字 → 「patch 同时改列表和详情」
 *  - 缓存目录无限长 → 「超出上限淘汰最旧」
 */
class CommunityCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun json() = Jsonx.lenient

    private fun card(id: Long, name: String = "卡$id", likes: Int = 0, downloads: Int = 0) =
        CommunityCard(
            id = id,
            kind = Scenes.TAVERN,
            name = name,
            tagline = "",
            author = "作者",
            downloads = downloads,
            likes = likes,
            createdAt = 1_700_000_000L,
        )

    private fun page(vararg ids: Long, at: Long = System.currentTimeMillis()) = CachedPage(
        at = at,
        hasMore = false,
        items = ids.map { card(it) },
        pages = 0,
    )

    private fun detail(id: Long, at: Long = System.currentTimeMillis()) = CachedDetail(
        at = at,
        meta = card(id),
        card = RoleCard(id = "", name = "社区卡$id", kind = Scenes.TAVERN, persona = "设定"),
    )

    @Test
    fun `写入后内存立刻可读`() = runTest {
        val cache = CommunityCache(tmp.newFolder("c"), json())
        val key = CommunityCache.listKey(Scenes.TAVERN, CommunityApi.SORT_NEW)

        cache.putPage(key, page(1, 2, 3))

        assertThat(cache.page(key)?.items?.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test
    fun `新建实例能从磁盘读回来`() = runTest {
        val dir = tmp.newFolder("c")
        val key = CommunityCache.listKey(null, CommunityApi.SORT_HOT)
        CommunityCache(dir, json()).putPage(key, page(7, 8))

        // 模拟杀进程重进：新实例、同一目录
        val again = CommunityCache(dir, json())

        assertThat(again.page(key)?.items?.map { it.id }).containsExactly(7L, 8L)
    }

    @Test
    fun `详情也能往返磁盘`() = runTest {
        val dir = tmp.newFolder("c")
        CommunityCache(dir, json()).putDetail(12, detail(12))

        val again = CommunityCache(dir, json())

        assertThat(again.detail(12)?.card?.name).isEqualTo("社区卡12")
        assertThat(again.detail(12)?.meta?.id).isEqualTo(12L)
    }

    @Test
    fun `坏文件不带崩启动`() {
        val dir = tmp.newFolder("c")
        File(dir, "cache.json").writeText("{ 这不是 json")

        val cache = CommunityCache(dir, json())

        assertThat(cache.page("all|new")).isNull()
    }

    @Test
    fun `TTL 判断`() {
        val cache = CommunityCache(tmp.newFolder("c"), json())
        val now = System.currentTimeMillis()

        assertThat(cache.isFresh(now, 60_000)).isTrue()
        assertThat(cache.isFresh(now - 120_000, 60_000)).isFalse()
        // 时钟往回跳（用户手动改时间）不该算出"新鲜"
        assertThat(cache.isFresh(now + 120_000, 60_000)).isFalse()
    }

    @Test
    fun `patch 同时改详情和列表`() = runTest {
        val cache = CommunityCache(tmp.newFolder("c"), json())
        val key = CommunityCache.listKey(null, CommunityApi.SORT_NEW)
        cache.putPage(key, page(5))
        cache.putDetail(5, detail(5))

        cache.patchDetail(5) { it.copy(likes = 9) }

        assertThat(cache.detail(5)?.meta?.likes).isEqualTo(9)
        assertThat(cache.page(key)?.items?.first()?.likes).isEqualTo(9)
    }

    @Test
    fun `dropMine 只清我的投稿`() = runTest {
        val cache = CommunityCache(tmp.newFolder("c"), json())
        val list = CommunityCache.listKey(Scenes.TAVERN, CommunityApi.SORT_NEW)
        val mine = CommunityCache.mineKey("站长")
        cache.putPage(list, page(1))
        cache.putPage(mine, page(2))

        cache.dropMine()

        assertThat(cache.page(mine)).isNull()
        assertThat(cache.page(list)).isNotNull()
    }

    @Test
    fun `详情超过上限时淘汰最旧的`() = runTest {
        val cache = CommunityCache(tmp.newFolder("c"), json())
        // 时间戳递增，保证淘汰顺序确定（LinkedHashMap 按写入顺序也行，但显式更稳）
        repeat(30) { i ->
            cache.putDetail(i + 1L, detail(i + 1L, at = 1_000L + i))
        }

        assertThat(cache.detail(1)).isNull()
        assertThat(cache.detail(30)).isNotNull()
    }

    @Test
    fun `clear 之后内存和磁盘都空`() = runTest {
        val dir = tmp.newFolder("c")
        val cache = CommunityCache(dir, json())
        val key = CommunityCache.listKey(null, CommunityApi.SORT_NEW)
        cache.putPage(key, page(1))
        cache.putDetail(1, detail(1))

        cache.clear()

        assertThat(cache.page(key)).isNull()
        assertThat(cache.detail(1)).isNull()
        assertThat(CommunityCache(dir, json()).page(key)).isNull()
    }

    @Test
    fun `key 区分筛选与排序`() {
        assertThat(CommunityCache.listKey(null, CommunityApi.SORT_NEW))
            .isNotEqualTo(CommunityCache.listKey(Scenes.TAVERN, CommunityApi.SORT_NEW))
        assertThat(CommunityCache.listKey(Scenes.TAVERN, CommunityApi.SORT_NEW))
            .isNotEqualTo(CommunityCache.listKey(Scenes.TAVERN, CommunityApi.SORT_HOT))
        assertThat(CommunityCache.mineKey("A")).isNotEqualTo(CommunityCache.mineKey("B"))
    }
}
