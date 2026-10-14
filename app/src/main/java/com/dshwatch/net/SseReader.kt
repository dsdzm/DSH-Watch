package com.dshwatch.net

import okio.BufferedSource

/**
 * 极简 SSE 解析器。
 *
 * 不依赖 OkHttp 的 EventSource：手写 40 行换来两个好处
 *  1) 完全掌控取消语义（阻塞读可以被 Call.cancel() 打断）；
 *  2) 不引入额外的线程与队列，手表上少一份内存占用。
 */
class SseReader(private val source: BufferedSource) {

    data class Event(val name: String?, val data: String)

    fun read(): Event? {
        val dataLines = StringBuilder()
        var eventName: String? = null
        var sawAnyField = false

        while (true) {
            val line = source.readUtf8Line() ?: return if (sawAnyField && dataLines.isNotEmpty()) {
                Event(eventName, dataLines.toString())
            } else {
                null
            }

            if (line.isEmpty()) {
                if (!sawAnyField) continue
                if (dataLines.isEmpty() && eventName == null) continue
                return Event(eventName, dataLines.toString())
            }
            if (line[0] == ':') continue // 注释/心跳

            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)

            sawAnyField = true
            when (field) {
                "data" -> {
                    if (dataLines.isNotEmpty()) dataLines.append('\n')
                    dataLines.append(value)
                }
                "event" -> eventName = value
                else -> Unit // id / retry 忽略
            }
        }
    }
}
