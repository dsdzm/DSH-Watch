package com.dshwatch.domain.plugin

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * TOON 编码器的语法测试。
 *
 * 每条用例都对着官方 format-overview 的示例形态写 —— 这里错一个字符，
 * 模型读到的就是另一份数据，而它不会报错，只会照着错的理解往下做。
 */
class ToonEncoderTest {

    private fun toon(json: String): String? = ToonEncoder.encode(Json.parseToJsonElement(json))

    @Test
    fun `simple object`() {
        assertThat(toon("""{"id":123,"name":"Ada","active":true}"""))
            .isEqualTo(
                """
                id: 123
                name: Ada
                active: true
                """.trimIndent()
            )
    }

    @Test
    fun `nested object indents two spaces`() {
        assertThat(toon("""{"user":{"id":1,"name":"Ada"}}"""))
            .isEqualTo(
                """
                user:
                  id: 1
                  name: Ada
                """.trimIndent()
            )
    }

    @Test
    fun `primitive array is inline with length`() {
        assertThat(toon("""{"tags":["admin","ops","dev"]}"""))
            .isEqualTo("tags[3]: admin,ops,dev")
    }

    @Test
    fun `uniform object array becomes tabular`() {
        assertThat(toon("""{"items":[{"sku":"A1","qty":2},{"sku":"B2","qty":1}]}"""))
            .isEqualTo(
                """
                items[2]{sku,qty}:
                  A1,2
                  B2,1
                """.trimIndent()
            )
    }

    @Test
    fun `nested uniform object folds into header`() {
        // 官方示例：orders[2]{id,customer{name,country},total}，行内按深度优先展开
        assertThat(
            toon(
                """{"orders":[
                   {"id":1,"customer":{"name":"Ada","country":"DK"},"total":99},
                   {"id":2,"customer":{"name":"Bob","country":"UK"},"total":149}
                ]}"""
            )
        ).isEqualTo(
            """
            orders[2]{id,customer{name,country},total}:
              1,Ada,DK,99
              2,Bob,UK,149
            """.trimIndent()
        )
    }

    @Test
    fun `object of uniform objects becomes keyed tabular`() {
        // 官方语法：[N:] 的冒号表示 keyed，每行是 "entrykey: 单元格,单元格"
        assertThat(
            toon("""{"users":{"alice":{"age":30,"city":"Berlin"},"bob":{"age":25,"city":"Oslo"}}}""")
        ).isEqualTo(
            """
            users[2:]{age,city}:
              alice: 30,Berlin
              bob: 25,Oslo
            """.trimIndent()
        )
    }

    @Test
    fun `single entry object stays nested`() {
        // 只有 1 个 entry 时 [1:] 比嵌套还长，官方也要求保持嵌套形态
        assertThat(toon("""{"users":{"alice":{"age":30}}}"""))
            .isEqualTo(
                """
                users:
                  alice:
                    age: 30
                """.trimIndent()
            )
    }

    @Test
    fun `non-uniform array falls back to unsupported instead of guessing`() {
        // 字段不一致 → 不是 tabular；本项目也不实现 list form → 整体放弃
        assertThat(toon("""{"items":[{"a":1},{"b":2}]}""")).isNull()
    }

    @Test
    fun `array of arrays is unsupported`() {
        assertThat(toon("""{"pairs":[[1,2],[3,4]]}""")).isNull()
    }

    @Test
    fun `empty array and empty object`() {
        assertThat(toon("""{"items":[],"meta":{}}"""))
            .isEqualTo(
                """
                items: []
                meta:
                """.trimIndent()
            )
    }

    @Test
    fun `root empty object yields empty document`() {
        assertThat(toon("{}")).isEmpty()
    }

    @Test
    fun `root array uses bare header`() {
        assertThat(toon("""[1,2,3]""")).isEqualTo("[3]: 1,2,3")
        assertThat(toon("""[{"a":1},{"a":2}]""")).isEqualTo("[2]{a}:\n  1\n  2")
    }

    @Test
    fun `strings that would be misread must be quoted`() {
        val out = toon(
            """{"empty":"","pad":" x","truth":"true","num":"05",
               "comma":"a,b","colon":"x:y","dash":"-abc","hash":"#tag"}"""
        )
        assertThat(out).isEqualTo(
            """
            empty: ""
            pad: " x"
            truth: "true"
            num: "05"
            comma: "a,b"
            colon: "x:y"
            dash: "-abc"
            hash: "#tag"
            """.trimIndent()
        )
    }

    @Test
    fun `numbers and booleans stay bare`() {
        assertThat(toon("""{"n":3.14,"m":-2,"ok":false,"nil":null}"""))
            .isEqualTo(
                """
                n: 3.14
                m: -2
                ok: false
                nil: null
                """.trimIndent()
            )
    }

    @Test
    fun `cjk and emoji need no quotes`() {
        assertThat(toon("""{"msg":"Hello 世界 👋"}""")).isEqualTo("msg: Hello 世界 👋")
    }

    @Test
    fun `control characters are escaped`() {
        // 换行 / 制表 / 引号 / 反斜杠 —— 全是官方允许的六种转义之内
        val out = toon("""{"t":"a\nb","tab":"a\tb","q":"say \"hi\"","bs":"c:\\tmp"}""")
        assertThat(out).isEqualTo(
            """
            t: "a\nb"
            tab: "a\tb"
            q: "say \"hi\""
            bs: "c:\\tmp"
            """.trimIndent()
        )
    }

    @Test
    fun `keys that need quoting are quoted too`() {
        // key 里含冒号/逗号时不加引号会把解析带偏
        assertThat(toon("""{"a:b":1,"c,d":2}""")).isEqualTo("\"a:b\": 1\n\"c,d\": 2")
    }

    @Test
    fun `tabular row quotes cells containing the delimiter`() {
        assertThat(toon("""{"u":[{"name":"Smith, Bob","role":"user"}]}"""))
            .isEqualTo("u[1]{name,role}:\n  \"Smith, Bob\",user")
    }

    @Test
    fun `null cell stays null keyword`() {
        assertThat(toon("""{"u":[{"id":1,"note":null},{"id":2,"note":"x"}]}"""))
            .isEqualTo("u[2]{id,note}:\n  1,null\n  2,x")
    }

    @Test
    fun `mixed primitive types in a column are still tabular`() {
        // 官方条件是"每列是原语"，不要求同一种原语
        assertThat(toon("""{"v":[{"x":1},{"x":"s"}]}""")).isEqualTo("v[2]{x}:\n  1\n  s")
    }

    @Test
    fun `column containing an array disqualifies tabular`() {
        assertThat(toon("""{"v":[{"x":[1]},{"x":[2]}]}""")).isNull()
    }

    @Test
    fun `column containing an empty object disqualifies tabular`() {
        // 空对象折进表头会变成 `x{}`，没有单元格可填
        assertThat(toon("""{"v":[{"x":{}},{"x":{}}]}""")).isNull()
    }

    @Test
    fun `deep nesting is allowed in nested field groups`() {
        assertThat(toon("""{"r":[{"a":{"b":{"c":1}}}]}"""))
            .isEqualTo("r[1]{a{b{c}}}:\n  1")
    }

    @Test
    fun `valid json encodes`() {
        assertThat(ToonEncoder.encode(Json.parseToJsonElement("""{"a":1}"""))).isEqualTo("a: 1")
    }
}
