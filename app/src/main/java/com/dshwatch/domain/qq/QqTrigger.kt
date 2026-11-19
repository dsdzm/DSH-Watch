package com.dshwatch.domain.qq

import com.dshwatch.domain.model.QqSettings
import com.dshwatch.net.qq.QqMessage

/**
 * 「这条消息该不该回」。
 *
 * 抽成独立对象而不是留在 [QqBridge] 里，是因为这是整条链路里**唯一**
 * 判断错了会让人立刻想卸载的地方：
 *  - 判宽了：群里每句话都回，一分钟刷十几条；
 *  - 判严了：@ 了也不回，用户以为坏了。
 *
 * 两种情况都没有报错可看，只能靠固定的样本回归。
 */
internal object QqTrigger {

    /**
     * @param selfId 机器人自己的 QQ 号。为空时按「不知道我是谁」处理，
     *   群聊里一律要求前缀 —— 宁可不回，也不要把群里每句 @别人 的话都接过来。
     * @param myMessageIds 本次运行里自己发出去、且还记着的消息 id。
     *   用来认「引用回复」：群里很多人习惯长按引用代替 @，只认 @ 会漏掉一半点名。
     */
    fun shouldReply(
        msg: QqMessage,
        cfg: QqSettings,
        selfId: String,
        myMessageIds: Set<String> = emptySet(),
    ): Boolean {
        if (msg.senderId.isNotBlank() && msg.senderId == selfId) return false
        if (!msg.isGroup) return cfg.privateEnabled

        if (!cfg.groupEnabled) return false
        if (msg.peerId.isNotBlank() && cfg.groupPeer.isNotBlank() &&
            msg.peerId != cfg.groupPeer.trim()
        ) {
            return false
        }
        if (!cfg.requireAt) return true
        if (selfId.isNotBlank() && msg.mentions(selfId)) return true
        // 引用的是我说过的话，等同于点名
        if (msg.replyId.isNotBlank() && msg.replyId in myMessageIds) return true
        val t = msg.text.trim()
        return cfg.prefixList().any { p -> t.startsWith(p, ignoreCase = true) }
    }

    /**
     * 去掉触发前缀，只把真正要问的话交给模型。
     *
     * 前缀后面常见的分隔符（冒号、逗号、空格）一并吃掉：`小D：今天几号`
     * 交给模型时应该是 `今天几号`，留着冒号它会当成「这是一个叫小D的人说的话」。
     */
    fun cleanInput(text: String, prefixes: List<String>): String {
        var t = text.trim()
        for (p in prefixes) {
            if (t.startsWith(p, ignoreCase = true)) {
                t = t.substring(p.length).trimStart(':', '：', ',', '，', ' ', '\n')
                break
            }
        }
        return t
    }
}
