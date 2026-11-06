package com.dshwatch.domain.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dshwatch.core.Logx
import java.io.File

/**
 * 把下载好的 APK 交给系统安装器。
 *
 * ## 这一环是整条链路最不可靠的地方
 *
 * 「安装未知应用」是一道 appop 门控，不是运行时权限：`REQUEST_INSTALL_PACKAGES`
 * 在安装时就授了，但真正能不能装由用户在系统设置里逐应用打开。
 *
 * 问题是 **Wear OS 上这个设置页经常被厂商藏掉**（Galaxy Watch 实测没有，
 * AOSP 有但多数 ROM 不暴露）。所以这里的策略是：
 *  1. 先 `canRequestPackageInstalls()` 实时判断（不能缓存，用户可能在外部关掉）；
 *  2. 没权限就去跳设置页，**跳不过去要如实返回 false**，由界面给出 adb 命令；
 *  3. 绝不假设「点一下就能装」。
 */
object UpdateInstall {

    fun canInstall(context: Context): Boolean {
        // canRequestPackageInstalls 是 API 26 才有的，minSdk 28 下直接用；
        // 仍保留判断，免得以后有人把 minSdk 往下调
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
    }

    /**
     * 跳到「安装未知应用」里本应用的那一页。
     *
     * @return 是否真的把设置页拉起来了。返回 false 通常是这台设备没有这个页面
     *   （`ActivityNotFoundException`），此时界面应该给 adb 命令而不是干等着。
     */
    fun openPermissionSettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        Logx.w(TAG, "没有「安装未知应用」设置页：${it.message}")
        false
    }

    /**
     * 调起系统安装器。
     *
     * 几个细节都是踩过的：
     *  - authority 用 `packageName + ".files"`，debug 包是 `com.dshwatch.debug.files`，不能写死；
     *  - 额外 `setClipData`：部分 OEM 安装器不消费 `FLAG_GRANT_READ_URI_PERMISSION`，
     *    但会读 clipData 里的 uri（这是系统分享的标准做法）；
     *  - 文件名保留 `.apk` 后缀，某些安装器靠后缀判断类型；
     *  - 不用 `ACTION_INSTALL_PACKAGE`，它在 API 29 就废弃了。
     */
    fun install(context: Context, file: File): Result<Unit> = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = ClipData.newRawUri("apk", uri)
        }
        context.startActivity(intent)
    }.onFailure {
        Logx.w(TAG, "调起安装器失败：${it.message}")
    }

    /** 装完 / 放弃后清理缓存，别让 8MB 的安装包一直占着手表空间 */
    fun clearDownload(context: Context) {
        runCatching { File(context.cacheDir, "apk").deleteRecursively() }
    }

    /** 没有设置页时的兜底：这条命令只需要执行一次，之后版本都能直接装 */
    fun adbCommand(packageName: String): String =
        "adb shell appops set $packageName REQUEST_INSTALL_PACKAGES allow"

    private const val TAG = "Update"
}
