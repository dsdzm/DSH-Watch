package com.dshwatch.domain.update

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.dshwatch.core.Logx
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把下载好的安装包**存到本地**，留给用户自己装。
 *
 * ## 为什么需要这条路
 *
 * 应用内更新的正常出口是 `UpdateInstall` —— 调系统安装器。但那一步依赖
 * 「安装未知应用」这个 appop，而在手表上它经常根本不存在设置入口：
 * `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 抛 `ActivityNotFoundException`，
 * 于是 App 能给用户的只有一句 adb 命令，包里还没有包。
 *
 * 存到本地把这两件事拆开：**下载**由 App 完成，**安装**交给用户手边的任何工具
 * （数据线 `pm install`、手机端传输、有文件管理器的设备上点一下）。安装包的
 * 生命周期因此不再受缓存清理、进程被杀、系统回收影响。
 *
 * ## 落在哪里（按优先级）
 *
 * | 位置 | 实际路径 | 用户能看到 | 代价 |
 * |---|---|---|---|
 * | [Place.PUBLIC_DOWNLOAD] | `/sdcard/Download/DSH-Watch/` | 数据线、文件管理器都看得到 | ≤28 要写权限；≥30 要「所有文件访问」 |
 * | [Place.PUBLIC_DOWNLOAD]（MediaStore） | 同上 | 同上 | 无需权限，但拿不到一个可 stat 的 File |
 * | [Place.APP_EXTERNAL] | `/sdcard/Android/data/<包名>/files/Download/DSH-Watch/` | 数据线 / 部分文件管理器 | 无需任何权限 |
 * | [Place.APP_INTERNAL] | `/data/data/<包名>/files/export/DSH-Watch/` | 看不到 | 兜底：连外部存储都挂不上时 |
 *
 * 顺序不是随手排的：**公共下载目录**是唯一一个「用户拔掉线也能找到」的地方，
 * 所以只要拿得到就优先放那里；拿不到就退到应用外部目录（`adb pull` 照样能取，
 * 只是在文件管理器里要翻两层）；两条都失败才写内部存储，那时至少能导出。
 *
 * ## 绝不写进 cache
 *
 * `cacheDir` 是系统可以随时清掉的地方。把「用户特意保存的包」放进去，
 * 等于把需求本身做没了 —— 这也是 [UpdateChecker.download] 与这里分开的原因。
 */
object UpdateStore {

    private const val TAG = "Update"

    /** 落在哪个位置。界面据此决定能给出哪些后续命令 */
    enum class Place {
        /** 公共「下载」目录：用户可见，`pm install` 能直接装 */
        PUBLIC_DOWNLOAD,

        /** 应用外部目录：无需权限，`adb pull` / `pm install` 都能用 */
        APP_EXTERNAL,

        /** 应用内部目录：只有本应用和 root 能读 */
        APP_INTERNAL,
    }

    data class Saved(val file: File, val place: Place) {
        /** 展示给用户的路径。就是绝对路径，不换成 `file://` 之类看不懂的形式 */
        val path: String get() = file.absolutePath
    }

    /** 子目录名。带产品名而不是直接铺在 Download 根下，免得和别的 App 混在一起 */
    const val SUBDIR = "DSH-Watch"

    fun fileName(versionCode: Int): String = "dshwatch-$versionCode.apk"

    /**
     * 保存它。
     *
     * 失败只有三种原因，且都能给用户一句人话：空间不够、一个位置都写不进去、
     * 源文件已经不在了（缓存被系统清掉）。
     */
    suspend fun save(context: Context, apk: File): Result<Saved> = withContext(Dispatchers.IO) {
        runCatching {
            if (!apk.isFile || apk.length() <= 0L) throw IOException("安装包已不在缓存里")

            publicDownloadDir(context)?.let { dir ->
                copyInto(apk, File(dir, apk.name))?.let { return@runCatching Saved(it, Place.PUBLIC_DOWNLOAD) }
            }

            // Android 10+ 没有写权限时仍有 MediaStore 这条路：它不需要权限，
            // 落点同样是公共下载目录，只是回来后拿不到一个能 stat 的 File
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mediaStoreSave(context, apk)?.let {
                    return@runCatching Saved(it, Place.PUBLIC_DOWNLOAD)
                }
            }

            externalAppDir(context)?.let { dir ->
                copyInto(apk, File(dir, apk.name))?.let { return@runCatching Saved(it, Place.APP_EXTERNAL) }
            }

            File(File(context.filesDir, "export/$SUBDIR").apply { mkdirs() }, apk.name)
                .let { target ->
                    copyInto(apk, target)?.let { return@runCatching Saved(it, Place.APP_INTERNAL) }
                }

            throw IOException("没有可写入的位置")
        }.onFailure { Logx.w(TAG, "保存安装包失败：${it.message}") }
    }

    /**
     * 已经在本地存过的、比 [minVersionCode] 更新的包。
     *
     * 用于「上次下完存好了，但还没装」的场景：进程被杀、缓存被清之后，
     * 更新页仍应该直接给出「安装」，而不是让用户把 11MB 再下一遍。
     *
     * 只查公共下载目录和应用外部目录 —— 内部目录那一份是最后兜底，
     * 正常情况下不该有，而且它和服务端给的最新版未必对得上。
     */
    fun findSaved(context: Context, minVersionCode: Int): File? {
        val dirs = listOfNotNull(publicDownloadDir(context, requireWritable = false), externalAppDir(context))
        return dirs.asSequence()
            .mapNotNull { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.asSequence() }
            .flatten()
            /*
             * 只认自己读得到的文件。
             *
             * Android 11 起，没拿到「所有文件访问」时公共下载目录里的文件
             * `canRead()` 是 false —— 这时把它当成「已下载」，用户点安装会走到
             * FileProvider 抛 IllegalArgumentException，界面上只剩一句
             * 「无法调起系统安装器」，完全看不出真正原因。宁可让他重下一次。
             */
            .filter { it.canRead() && it.length() > 0 }
            // 只认比当前版本新的包：认错了会让人在「已是最新」的设备上点「安装」装回旧版，
            // 系统拒绝降级，而用户完全不知道为什么
            .filter { codeOf(it)?.let { code -> code > minVersionCode } == true }
            .maxByOrNull { codeOf(it) ?: 0 }
    }

    /**
     * 文件名里的 versionCode。认不出来就是 null。
     *
     * 首尾锚定是必须的：`dshwatch-84.apk.part` 是写了一半的临时文件，
     * 不加锚点会把它的 versionCode 认成 84，于是「未完成的包」看起来和完整的包一模一样。
     */
    fun codeOf(file: File): Int? = Regex("""^dshwatch-(\d+)\.apk$""")
        .find(file.name)?.groupValues?.get(1)?.toIntOrNull()

    /** 这台设备能不能直接往公共下载目录写（写不了才需要申请权限） */
    fun canWritePublic(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 需不需要先申请写权限。
     *
     * 只有 Android 9 及以下需要：那一档是靠 `WRITE_EXTERNAL_STORAGE` 按路径写的。
     * Android 10 起 MediaStore 取代了它，不必再打扰用户。
     */
    fun needsWritePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !canWritePublic(context)

    /** 这台设备上这条路径能不能被执行 `pm install` */
    fun installableFromShell(place: Place): Boolean = place != Place.APP_INTERNAL

    /**
     * 设备侧的安装命令。
     *
     * 用 `adb shell pm install` 而不是 `adb install`：后者吃的是**电脑上**的文件路径，
     * 而用户手上只有手表里的这一个。shell 用户能读 `/sdcard`，所以这条能直接成。
     */
    fun adbInstallCommand(saved: Saved): String? =
        if (installableFromShell(saved.place)) "adb shell pm install -r \"${saved.path}\"" else null

    /** 取回电脑再装：`adb pull` 之后 `adb install` 两步，但私有目录只有这一条路 */
    fun adbPullCommand(saved: Saved): String = "adb pull \"${saved.path}\""

    // ---- 具体落点 ----------------------------------------------------------

    /**
     * 公共下载目录里本应用那一格。
     *
     * @param requireWritable true 时只在真有写权限的情况下返回目录 ——
     *   没有权限时 `mkdirs()` 会静默失败，写进去的 copy 会抛一个看不出原因的
     *   IOException，不如早一步换路。
     */
    private fun publicDownloadDir(context: Context, requireWritable: Boolean = true): File? {
        if (requireWritable && !canWritePublic(context)) return null
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        return if (dir.isDirectory || dir.mkdirs()) dir else null
    }

    /** 应用外部目录。`getExternalFilesDir` 在少数 ROM 上返回 null，所以要兜住 */
    private fun externalAppDir(context: Context): File? = runCatching {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.let { File(it, SUBDIR).apply { mkdirs() } }
    }.getOrNull()

    /**
     * 拷到 [target]，返回真正写成的文件。
     *
     * 先写 `.part` 再改名：中途失败时不能留下一个**文件名和真包一样**的半截文件，
     * 否则下次 [findSaved] 会把它当成可安装的包，用户点了 `pm install` 得到的是
     * 「解析包时出现问题」—— 一个完全无法从现象反推的错误。
     */
    private fun copyInto(source: File, target: File): File? = runCatching {
        val parent = target.parentFile ?: return@runCatching null
        if (!parent.isDirectory && !parent.mkdirs()) return@runCatching null
        /* 空间检查放在写之前：写到一半 ENOSPC 会留下半截文件，虽然会被 .part 机制挡住，
           但用户看到的只是「保存失败」，不如直接说清楚 */
        if (parent.usableSpace in 1 until source.length()) throw IOException("存储空间不足")

        val part = File(parent, "${target.name}.part")
        source.inputStream().use { input ->
            part.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        }
        if (target.exists() && !target.delete()) throw IOException("无法覆盖旧文件")
        if (!part.renameTo(target)) throw IOException("无法写入 $parent")
        target
    }.getOrElse {
        Logx.w(TAG, "写入 ${target.absolutePath} 失败：${it.message}")
        runCatching { File(target.parentFile, "${target.name}.part").delete() }
        null
    }

    /**
     * Android 10+ 的公共下载目录，走 MediaStore。
     *
     * 好处是不需要任何权限就能落在用户看得见的地方；代价是拿不到可 stat 的 File ——
     * 这里按约定路径（`Download/DSH-Watch/文件名`）拼一个出来，只用于展示和给命令。
     *
     * `IS_PENDING` 必须成对设置：先置 1 写内容，写完置 0。少了第二步，
     * 文件在系统媒体库里永远是「待定」状态，文件管理器里看不到。
     */
    private fun mediaStoreSave(context: Context, apk: File): File? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@runCatching null
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // 同名旧包先删掉，否则会生成「dshwatch-83 (1).apk」，用户拿到两个不知道装哪个
        runCatching {
            resolver.delete(
                collection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf("%$SUBDIR%", apk.name),
            )
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUBDIR")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        runCatching {
            val out = resolver.openOutputStream(uri) ?: throw IOException("无法打开输出流")
            out.use { os -> apk.inputStream().use { it.copyTo(os, 64 * 1024) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
            throw it
        }

        File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR),
            apk.name,
        )
    }.getOrElse {
        Logx.w(TAG, "MediaStore 写入失败：${it.message}")
        null
    }
}
