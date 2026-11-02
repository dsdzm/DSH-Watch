package com.dshwatch.data.store

import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.domain.roleplay.CardField
import com.dshwatch.domain.roleplay.LoreEntry
import com.dshwatch.domain.roleplay.RoleCard
import com.dshwatch.domain.roleplay.Scenes
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 角色卡库。
 *
 * 守住两件会"让用户以为卡丢了"的事：
 *  1. 列表页读的是索引摘要，**条目数量必须在摘要里留着** ——
 *     一度把 entries 清成空表，界面就永远显示"世界书 0 条"，点进去却还在；
 *  2. 内置卡只灌一次。用户改了内置卡、或者干脆删了它，
 *     下次启动不能被 ensureBuiltins 又塞回来。
 */
class RoleCardStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun store() = RoleCardStore(
        dir = tmp.root,
        json = json,
        io = UnconfinedTestDispatcher(),
    )

    private fun card(id: String = "", name: String = "测试卡") = RoleCard(
        id = id,
        kind = Scenes.TAVERN,
        name = name,
        persona = "正文",
        fields = listOf(CardField("年龄", "20")),
        entries = listOf(
            LoreEntry(id = "e1", title = "世界", content = "很长的世界设定"),
            LoreEntry(id = "e2", title = "小事", content = "另一条"),
        ),
    )

    @Test
    fun `save assigns id and a reload finds it`() = runTest {
        val s = store()
        val saved = s.save(card(name = "老板娘"))
        assertThat(saved.id).isNotEmpty()
        assertThat(s.load(saved.id)?.name).isEqualTo("老板娘")
    }

    @Test
    fun `index keeps lore count but drops the body`() = runTest {
        val s = store()
        s.save(card(name = "A"))
        val listed = s.index(Scenes.TAVERN).single()
        // 条目骨架要在，正文要省 —— 列表页显示的是"N 条"，不是内容。
        // 注意 save 返回的是完整卡，index 读的才是摘要，这里必须查 index 的结果。
        assertThat(listed.entries).hasSize(2)
        assertThat(listed.entries.first().title).isEqualTo("世界")
        assertThat(listed.entries.first().content).isEmpty()
        assertThat(listed.persona).isEmpty()
        assertThat(listed.fields).isEmpty()
    }

    @Test
    fun `index is separated by kind`() = runTest {
        val s = store()
        s.save(card(name = "酒馆卡"))
        s.save(card(name = "写作卡").copy(kind = Scenes.WRITING))
        assertThat(s.index(Scenes.TAVERN).map { it.name }).containsExactly("酒馆卡")
        assertThat(s.index(Scenes.WRITING).map { it.name }).containsExactly("写作卡")
    }

    @Test
    fun `duplicate copies content but not the id`() = runTest {
        val s = store()
        val src = s.save(card(name = "原件"))
        val copy = s.duplicate(src.id)!!
        assertThat(copy.id).isNotEqualTo(src.id)
        assertThat(copy.name).contains("副本")
        assertThat(s.load(copy.id)?.entries).hasSize(2)
    }

    @Test
    fun `delete removes it from index and disk`() = runTest {
        val s = store()
        val c = s.save(card())
        s.delete(c.id)
        assertThat(s.index(Scenes.TAVERN)).isEmpty()
        assertThat(s.load(c.id)).isNull()
    }

    @Test
    fun `builtins are seeded once and never resurrected`() = runTest {
        val s = store()
        s.ensureBuiltins(BuiltinCards.all())
        val seeded = s.index(Scenes.TAVERN)
        assertThat(seeded).isNotEmpty()

        // 用户删掉一张内置卡
        val victim = seeded.first()
        s.delete(victim.id)
        assertThat(s.load(victim.id)).isNull()

        // 再次启动不能把它塞回来（索引非空即视为"用户已经有一批卡了"）
        s.ensureBuiltins(BuiltinCards.all())
        assertThat(s.load(victim.id)).isNull()
        assertThat(s.index(Scenes.TAVERN).map { it.id }).doesNotContain(victim.id)
    }

    @Test
    fun `user edits to a builtin card survive a reseed`() = runTest {
        val s = store()
        s.ensureBuiltins(BuiltinCards.all())
        val first = s.index(Scenes.TAVERN).first()
        val loaded = s.load(first.id)!!
        s.save(loaded.copy(name = "我改过的名字"))

        s.ensureBuiltins(BuiltinCards.all())
        assertThat(s.load(first.id)?.name).isEqualTo("我改过的名字")
    }

    @Test
    fun `builtins cover both scenes`() {
        val all = BuiltinCards.all()
        assertThat(all.map { it.kind }).contains(Scenes.TAVERN)
        assertThat(all.map { it.kind }).contains(Scenes.WRITING)
        // 内置卡是给用户照着改的模子：必须自带开场白和设定，不能是空壳
        all.forEach {
            assertThat(it.name).isNotEmpty()
            assertThat(it.greeting).isNotEmpty()
        }
    }
}
