package com.dshwatch.data.store

import com.dshwatch.core.Logx
import com.dshwatch.domain.fish.FishSkin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 养鱼页的外观。
 *
 * ## 为什么带一个 StateFlow
 *
 * 外观有两个写入方：用户在本页点选（[com.dshwatch.ui.screens.fish.FishSkinScreen]），
 * 和她在聊天里被要求改（工具 `fish_skin`）。后者发生时用户正盯着聊天页，
 * 如果只在"进页面时读一次"，改完得退出去再进来才看得见 —— 那一瞬间的失望
 * 比功能没做还糟。所以写入直接推给 flow，页面跟着重组。
 *
 * 图片单独放 `fish/` 目录，不塞进 json：一张壁纸几百 KB，每次读设置都过一遍
 * 主线程不划算，而且 json 里放 base64 会让设置文件大到没法手改。
 */
class FishSkinStore(
    private val workspaceRoot: () -> File,
    private val json: Json,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()
    private val _skin = MutableStateFlow(FishSkin.DEFAULT)

    /** 当前外观。页面直接 collect，改完立刻生效 */
    val skin: StateFlow<FishSkin> = _skin.asStateFlow()

    private fun dir(): File = File(workspaceRoot(), DIR)

    private fun file(): File = File(dir(), "skin.json")

    /** 从磁盘读一次，并同步到 [skin]。启动时调一次即可 */
    suspend fun load(): FishSkin = withContext(io) {
        mutex.withLock {
            val f = file()
            val tmp = File(f.parentFile ?: f.absoluteFile.parentFile, "${f.name}.tmp")
            /*
             * 崩在"写临时文件"和"改名"之间时，真身不在、临时文件在。
             * 这时候读临时文件比读空气强 —— 那里面就是用户刚改的样子。
             */
            if (!f.exists() && tmp.exists()) {
                runCatching { tmp.copyTo(f, overwrite = true); tmp.delete() }
            }
            val loaded = if (!f.exists()) {
                FishSkin.DEFAULT
            } else {
                runCatching { json.decodeFromString(FishSkin.serializer(), f.readText()) }
                    .onFailure {
                        /*
                         * 坏文件不能一直躺着：留着它，**每次启动都会解析失败、
                         * 每次都显示默认**，用户看到的就是"改了什么都会自己变回去"。
                         * 留一份备份以便排查，然后把坏文件清掉。
                         */
                        Logx.w(TAG, "外观文件坏了，回退默认", it)
                        runCatching { f.renameTo(File(f.parentFile, "skin.json.bad")) }
                    }
                    .getOrDefault(FishSkin.DEFAULT)
            }
            _skin.value = loaded
            loaded
        }
    }

    suspend fun save(next: FishSkin): FishSkin = withContext(io) {
        val sane = next.sanitized()
        mutex.withLock {
            // 写不进去也要让界面先动 —— 用户刚点完，界面卡住比文件丢了更难受。
            // 但失败必须**被调用方看见**，见 [saveOrThrow]。
            runCatching { writeLocked(sane) }
                .onFailure { Logx.w(TAG, "外观写入失败", it) }
            _skin.value = sane
            sane
        }
    }

    /**
     * 写盘，失败就抛。
     *
     * `fish_skin` 工具必须用这一条：以前它走 [save]，写盘失败只打一行
     * 被 R8 抹掉的日志，工具照样回「改好了」—— 用户退出重进发现全没了，
     * 而她刚刚才答应过他。宁可让她说「没存下来」，也不能让她撒谎。
     */
    suspend fun saveOrThrow(next: FishSkin): FishSkin = withContext(io) {
        val sane = next.sanitized()
        mutex.withLock {
            writeLocked(sane)
            _skin.value = sane
            sane
        }
    }

    /**
     * 真正写文件：先写临时文件再改名。
     *
     * 直接 `writeText` 覆盖时，如果进程在写一半被杀（手表上很常见：抬腕结束、
     * 内存回收），留下的是半个 JSON —— 下次启动解析失败，回退默认，
     * 用户看到的就是「改过的东西自己变回去了」。
     */
    private fun writeLocked(skin: FishSkin) {
        val target = file()
        val dir = target.parentFile ?: throw java.io.IOException("外观目录不可用")
        if (!dir.exists() && !dir.mkdirs()) throw java.io.IOException("建不了目录：${dir.absolutePath}")
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(json.encodeToString(FishSkin.serializer(), skin))
        // renameTo 在同一个目录里是原子的；个别文件系统上会失败，失败就退回覆盖写
        if (!tmp.renameTo(target)) {
            target.writeText(json.encodeToString(FishSkin.serializer(), skin))
            tmp.delete()
        }
        // 读回来对一遍：能写不能读（只读挂载、空间满）同样算没存下
        val back = runCatching {
            json.decodeFromString(FishSkin.serializer(), target.readText())
        }.getOrNull() ?: throw java.io.IOException("写进去了但读不回来：${target.absolutePath}")
        if (back != skin) throw java.io.IOException("存下来的和要存的不一致：${target.absolutePath}")
    }

    /** 全部恢复默认：配置与图片一起清。只清配置的话壁纸会一直占着容量却再也看不到 */
    suspend fun reset(): FishSkin {
        clearImages()
        return save(FishSkin.DEFAULT)
    }

    /**
     * 把一张外部图片收进 `fish/`。
     *
     * 为什么复制而不是直接引用原路径：用户选的图可能来自相册缓存，
     * 那个临时文件过两天就被系统删了 —— 到时候背景变成一片黑，
     * 而用户完全没做错任何事。
     *
     * @param slot "avatar" 或 "background"
     * @return 相对工作空间的路径，可直接写进 [FishSkin]
     */
    suspend fun importImage(source: File, slot: String): Result<String> = withContext(io) {
        runCatching {
            if (!source.isFile) throw IllegalArgumentException("找不到这个文件：${source.absolutePath}")
            if (!looksLikeImage(source)) {
                throw IllegalArgumentException("这不是能当图片用的文件（${source.name}）")
            }
            val target = File(dir(), "$slot.${source.extension.ifBlank { "png" }}")
            target.parentFile?.mkdirs()
            // 换扩展名时清掉旧的那张，否则 avatar.png 和 avatar.jpg 会同时留着
            dir().listFiles()?.filter {
                it.name.substringBeforeLast('.') == slot && it.absolutePath != target.absolutePath
            }?.forEach { runCatching { it.delete() } }
            source.copyTo(target, overwrite = true)
            // 存相对路径：工作空间整体搬过位置之后照样能找到
            "$DIR/${target.name}"
        }
    }

    /** 相对路径 → 绝对文件。读不到（被删了）返回 null，界面退回默认外观 */
    fun imageOf(rel: String): File? {
        if (rel.isBlank()) return null
        val f = if (rel.startsWith("/")) File(rel) else File(workspaceRoot(), rel)
        return f.takeIf { it.isFile }
    }

    private suspend fun clearImages(): Unit = withContext(io) {
        // 只删图片，别把 skin.json 一起带走 —— 它下一秒会被重写，但两次写之间
        // 如果进程被杀，文件就真的没了
        dir().listFiles()?.filter { it.extension.lowercase() != "json" }
            ?.forEach { runCatching { it.delete() } }
        Unit
    }

    /** 按扩展名判断"能不能当图"。判据是解码器认不认，不是相册能不能打开 */
    private fun looksLikeImage(f: File): Boolean =
        f.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "heic", "heif")

    companion object {
        private const val TAG = "FishSkinStore"
        const val DIR = "fish"
    }
}

/**
 * 把外部输入夹回合法范围。
 *
 * 模型和用户都会写出越界值（`avatar_size: 200`、`dim: 1`、名字五百字）。
 * 与其在每个渲染处判断，不如在写入口就夹一次 —— 存进去的就是能画的。
 */
fun FishSkin.sanitized(): FishSkin = copy(
    name = name.trim().take(FishSkin.NAME_MAX),
    greeting = greeting.trim().take(FishSkin.GREETING_MAX),
    avatarSize = avatarSize.coerceIn(FishSkin.AVATAR_SIZE_RANGE),
    textSize = textSize.coerceIn(FishSkin.TEXT_SIZE_RANGE),
    backgroundDim = backgroundDim.coerceIn(FishSkin.DIM_RANGE),
    avatar = avatar.trim(),
    background = background.trim(),
)
