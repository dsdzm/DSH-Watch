package com.dshwatch.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「拍一张，直接当附件」。
 *
 * ## 为什么不是 TakePicturePreview
 *
 * 那个 contract 只回一张缩略图（一百多 dp 的位图）。发给模型等于把照片糊成
 * 一堆色块，问「这药盒上写的什么」只能得到胡话。这里走
 * `ActivityResultContracts.TakePicture`：相机把原图写进我们给的 uri，回来的是文件。
 *
 * ## 为什么不申请 CAMERA 权限
 *
 * `ACTION_IMAGE_CAPTURE` 是让**相机 App** 去拍，本进程全程不碰摄像头。
 * 加一条 `<uses-permission android:name="android.permission.CAMERA">` 只会让
 * 用户在设置里多看到一个可疑的权限，换不来任何能力。
 *
 * ## 为什么落在 cache/camera
 *
 * 拍完就随消息进 `AttachmentStore`，原件没必要长期留着。放缓存目录，
 * 系统空间紧张时能自己回收；`file_paths.xml` 里那条 `<cache-path name="camera">`
 * 就是为它开的 —— 少了它 `getUriForFile` 会抛
 * `IllegalArgumentException: Failed to find configured root`，表现是「点拍照没反应」。
 *
 * @param onPicked 拍成功后的 uri。调用方负责导入成附件。
 * @param onError 失败原因，直接展示给用户（「这台设备没有相机应用」这类）。
 * @return 一个无参函数，点相机按钮时调用。
 */
@Composable
internal fun rememberCameraShot(
    context: Context,
    onPicked: (Uri) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val dir = remember(context) { File(context.cacheDir, "camera").apply { mkdirs() } }
    /*
     * 相机是另一个进程。它拍完回来时，回调里必须知道「刚才那张写到哪个文件」——
     * 这个值只能存在我们自己的状态里，不能从 uri 反推。
     */
    var pending by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = pending
        pending = null
        if (!ok || file == null) return@rememberLauncherForActivityResult
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }.getOrNull()
        if (uri != null) onPicked(uri) else onError("照片没存下来，换个方式再试")
    }

    return remember(launcher, dir) {
        {
            val file = File(
                dir,
                "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg",
            )
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            }.getOrNull()
            if (uri == null) {
                onError("这台设备没有可用的相机")
            } else {
                pending = file
                // 没有相机 App 时 launch 会抛 ActivityNotFoundException，
                // 不能让它冒到顶层 —— 那会在用户眼里变成一次闪退
                val launched = runCatching { launcher.launch(uri) }.isSuccess
                if (!launched) {
                    pending = null
                    onError("这台设备没有相机应用")
                }
            }
        }
    }
}
