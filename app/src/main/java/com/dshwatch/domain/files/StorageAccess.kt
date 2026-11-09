package com.dshwatch.domain.files

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.dshwatch.core.Logx

/**
 * 「能不能读沙盒外的文件」这件事的唯一出口。
 *
 * ## 为什么两套权限
 *
 * Android 10 及以前：`READ_EXTERNAL_STORAGE` 一个运行时权限，点了就能读整片
 * 外部存储。国内大量手表还停在这里，内置文件浏览器在这一档上是真好用 ——
 * 这也是「为没有文件管理器的设备兜底」最主要的战场。
 *
 * Android 11 起：分区存储把按路径读公共目录的口子收了，`READ_EXTERNAL_STORAGE`
 * 只剩媒体（图片/视频/音频）。要读 `Download` 里别人写的 `.txt`、`.pdf`，
 * 只有两个选择 —— 系统文件选择器（SAF），或者「所有文件访问」
 * (`MANAGE_EXTERNAL_STORAGE`)。前者的前提是设备上存在文件管理器，
 * 恰恰是这台机器可能没有的东西，所以只剩后者。
 *
 * ## 代价要说清楚
 *
 * `MANAGE_EXTERNAL_STORAGE` 是重权限：授予后本应用能读整片外部存储。
 * 它不会自动拿到 —— 必须在系统设置里单独打开，且 Google Play 上架需要申报理由
 * （本项目走 GitHub 发布，不涉及）。所以这一档**只在用户主动点「授权」时申请**，
 * 没授权时浏览器照样能翻应用自己的目录，只是公共目录会显示「没有权限」而不是空白。
 */
object StorageAccess {

    private const val TAG = "StorageAccess"

    /** 当前是否已经能读公共目录 */
    fun granted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> isAllFiles(context)
        else -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 授权要走「系统设置页」还是「运行时弹窗」。前者只能由用户手动点，无法用代码代劳 */
    fun needsSystemSettings(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 运行时权限名（< 30 用）；>= 30 返回空，因为那一档没有可申请的运行时权限 */
    fun runtimePermission(): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Manifest.permission.READ_EXTERNAL_STORAGE
        } else {
            ""
        }

    /**
     * 打开「所有文件访问」的授权页。
     *
     * 优先跳**本应用**的那一页（用户少走一步：不用在长长的应用列表里找自己），
     * 部分 ROM 没有这个 Activity，就退回到列表页。两个都失败才认输。
     */
    fun requestAllFiles(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        if (start(context, app)) return
        if (start(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) return
        // 再退一步：至少把本应用的详情页打开，用户能从「权限」里找到它
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }

    private fun isAllFiles(context: Context): Boolean {
        // 显式再判一次 SDK：Environment.isExternalStorageManager() 是 API 30 才有的，
        // 只在调用处判会漏掉 lint 的静态检查（它不看调用栈，只看这一行）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            Environment.isExternalStorageManager()
        }.onFailure { Logx.w(TAG, "查询所有文件访问失败：${it.message}") }.getOrDefault(false)
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        // 非 Activity 的 Context 起 Activity 必须带 NEW_TASK，否则直接抛异常
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
