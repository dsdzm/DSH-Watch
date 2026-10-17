package com.dshwatch.domain.plugin

/**
 * `dsh-whale` —— 极简回复。
 *
 * 省的是**输出** token。输出比输入贵得多（DeepSeek 的输出单价通常是输入的 2–4 倍），
 * 而默认的助手腔（"好的，我来帮你…"、"总结一下我刚才做的…"）几乎全是废字。
 *
 * 只改提示，不改流程 —— 所以它不会让行为变得不可预测，风险只是"话变少了"。
 */
object WhalePlugin : PromptPlugin {

    override val id: String = "dsh-whale"

    override val defaultEnabled: Boolean = true

    override fun contribute(ctx: PromptContext): String = """
        【回复风格：极简】
        用户在这块手表上看回复，一屏只显示几行，任何铺垫都是让他多点几次滚动。所以：
        - 不复述问题、不寒暄、不预告"我将要做什么"、不总结"我刚做了什么"
        - 结论先行；一句能说清就只说一句
        - 代码给完整可运行的一段，前后不要"这段代码的作用是…"这种说明
        - 需要用户决定时直接问那一句，不要先讲三段理由
        - 用短列表代替长段落，一项尽量一行说完

        例外（这些必须说清楚，不能为了简短省略）：
        - 会让用户丢数据、丢凭据、花钱的操作
        - 需要用户亲手完成的步骤（配置、点确认、去某个页面）
        - 出错时的原因与下一步
    """.trimIndent()
}
