package com.dshwatch.domain.plugin

import com.dshwatch.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 从链接装一份插件清单。
 *
 * ## 为什么要有这条路
 *
 * 手表上没有能舒服编辑 JSON 的输入法，也不方便从手机传文件进工作空间。
 * 但「分享一个链接」是所有人都已经会的动作 —— 作者把清单放到 gist、
 * 仓库或自己的博客，用户粘一条地址就装上了。
 *
 * ## 只接受 http/https，只读一次，有上限
 *
 * 这不是信任问题（清单本身在 [PluginLoader.parse] 里会被逐条校验），
 * 而是「不要因为一个坏链接把 App 卡住」：超时 12 秒、最多读 8KB、
 * 不跟随跳转到非 http 协议。读取上限与清单本身的上限一致，
 * 超过的部分根本不会进内存。
 */
object PluginImporter {

    /** 拉取超时。插件是几 KB 的文本，12 秒还没回来基本可以判定是坏链接 */
    private const val TIMEOUT_MS = 12_000L

    suspend fun fromUrl(raw: String): PluginLoader.Parsed = withContext(Dispatchers.IO) {
        val url = normalize(raw)
            ?: return@withContext PluginLoader.Parsed.Err(
                "链接要以 http:// 或 https:// 开头。\n" +
                    "例如 https://gist.githubusercontent.com/…/purge.json",
            )

        val client = Http.client.newBuilder()
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()

        val response = runCatching {
            client.newCall(
                Request.Builder()
                    .url(url)
                    // 有些静态托管会按 Accept 返回 HTML 页面（比如 gist 的网页版），
                    // 明确要 JSON 能少走一次「拉回来是一坨 HTML」的困惑
                    .header("Accept", "application/json, text/plain;q=0.9")
                    .build(),
            ).execute()
        }.getOrElse { t ->
            return@withContext PluginLoader.Parsed.Err(
                "拉取失败：${t.message.orEmpty().take(80)}。检查网络，或换成 https 链接。",
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                return@use PluginLoader.Parsed.Err(
                    "链接返回 ${resp.code}。确认这是个 raw 地址" +
                        "（网页版 gist 给的是 HTML，要点 Raw 再复制）。",
                )
            }
            val declared = resp.body?.contentLength() ?: -1L
            if (declared > PluginManifest.MAX_CHARS) {
                return@use PluginLoader.Parsed.Err("文件 ${declared / 1024}KB，超过上限，不像一份声明。")
            }
            val text = runCatching { resp.body?.string().orEmpty() }.getOrElse { t ->
                return@use PluginLoader.Parsed.Err("读取失败：${t.message.orEmpty().take(80)}")
            }
            PluginLoader.parse(text, "链接")
        }
    }

    /**
     * 规范化用户粘进来的地址。
     *
     * 只做两件事：去掉首尾空白与换行（分享面板经常带一串空白），
     * 校验协议。**不**自动补 `https://` —— 那样用户粘错一段文字，
     * 会得到一个「看起来像域名」的请求发出去，报错反而更难懂。
     */
    private fun normalize(raw: String): String? {
        val s = raw.trim().replace("\n", "").replace("\r", "")
        if (s.isEmpty() || s.length > 2_000) return null
        val lower = s.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return s
    }
}
