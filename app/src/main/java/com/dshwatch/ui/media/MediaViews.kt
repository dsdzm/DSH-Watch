package com.dshwatch.ui.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dshwatch.domain.model.MediaKind
import com.dshwatch.domain.model.MediaRef
import com.dshwatch.domain.model.MediaStatus
import com.dshwatch.ui.components.shareFile
import com.dshwatch.ui.components.tap
import java.io.File

/**
 * 会话里的一条媒体。
 *
 * 三种状态各有各的形态，不能只画一个占位框：
 *  - 生成中：给进度，否则用户不知道「它是在跑还是卡死了」——
 *    出图要十几秒、出视频经常一分钟以上，没有进度就是一段无解释的空白；
 *  - 成功：图直接看，视频给首帧 + 播放角标；
 *  - 失败：把服务端原话写出来（「队列已满」和「额度不足」需要的应对完全不同）。
 */
@Composable
fun MediaCard(
    ref: MediaRef,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    var frame by remember(ref.id, ref.path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(ref.id, ref.path) { mutableStateOf(false) }

    LaunchedEffect(ref.id, ref.path, ref.status) {
        if (!ref.isReady) return@LaunchedEffect
        val source = ref.displaySource
        if (source.isBlank()) { failed = true; return@LaunchedEffect }
        val bitmap = if (ref.kind == MediaKind.VIDEO) {
            MediaLoader.videoFrame(ref.path, 480)
        } else {
            MediaLoader.load(source, 480)
        }
        frame = bitmap
        failed = bitmap == null
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(if (ref.kind == MediaKind.VIDEO) 16f / 9f else 1.4f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = frame
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap,
                    contentDescription = ref.prompt.ifBlank { "生成的图片" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (ref.kind == MediaKind.VIDEO) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    ref.durationMs.takeIf { it > 0 }?.let { ms ->
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = clock(ms),
                                color = Color.White,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
            }

            ref.status == MediaStatus.FAILED -> {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.BrokenImage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = ref.error.ifBlank { "生成失败" },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            ref.status == MediaStatus.CANCELED -> {
                Text(
                    text = "已取消",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            else -> {
                // 生成中：环形进度 + 百分比。视频可能跑几分钟，光转圈会让人以为死了
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (ref.kind == MediaKind.VIDEO) "生成视频 ${ref.progress}%" else "绘制中…",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (failed && ref.isReady) {
            Text(
                text = "图片加载失败",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // 只有真的能看的才给点击，避免点了弹出一个空白查看器
        if (bitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .tap(onClick = onClick),
            )
        }
    }
}

@Composable
fun MediaStrip(
    media: List<MediaRef>,
    modifier: Modifier = Modifier,
    onOpen: (MediaRef) -> Unit,
) {
    if (media.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 一条消息目前只产出一件媒体；循环写在这里是为了以后多图时不用改结构
        media.take(2).forEach { ref ->
            MediaCard(ref = ref, onClick = { onOpen(ref) })
        }
        if (media.size > 2) {
            Text(
                text = "另有 ${media.size - 2} 个",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * 全屏查看器。
 *
 * 手表屏幕只有 200 多 dp，缩略图里基本看不出细节，所以必须有一个能放大、
 * 能分享的出口。用 Dialog 而不是新页面：退出就是按一下返回，
 * 不会把导航栈搞乱。
 */
@Composable
fun MediaViewer(
    ref: MediaRef,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(ref.id) { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(ref.id) {
        val source = ref.displaySource
        if (source.isBlank()) { loadFailed = true; return@LaunchedEffect }
        val bmp = if (ref.kind == MediaKind.VIDEO) {
            MediaLoader.videoFrame(ref.path, 1024)
        } else {
            MediaLoader.load(source, 1024)
        }
        bitmap = bmp
        loadFailed = bmp == null
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = ref.prompt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .pointerInput(ref.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 4f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            }
                        },
                )
            } else {
                Text(
                    text = if (loadFailed) "无法打开这个文件" else "加载中…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // 关闭：右上角，圆屏上下两侧的弦宽最窄，角落反而好点
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .tap(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }

            // 底部一栏：元信息 + 播放 / 分享
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = buildString {
                        if (ref.width > 0 && ref.height > 0) append("${ref.width}×${ref.height}")
                        if (ref.sizeBytes > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(mb(ref.sizeBytes))
                        }
                        if (ref.kind == MediaKind.VIDEO && ref.durationMs > 0) {
                            if (isNotEmpty()) append(" · ")
                            append(clock(ref.durationMs))
                        }
                    }.ifBlank { ref.model },
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (ref.kind == MediaKind.VIDEO) {
                    RoundAction(icon = Icons.Filled.PlayArrow, label = "播放") {
                        playVideo(context, File(ref.path))
                    }
                }
                RoundAction(icon = Icons.Outlined.Share, label = "分享") {
                    shareFile(context, ref.localFile())
                }
            }
        }
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 交给系统播放器。手表上装什么播放器是用户自己的选择，App 不内置解码器 */
private fun playVideo(context: Context, file: File) {
    if (!file.isFile) return
    val uri: Uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }.getOrElse {
        // 文件不在 FileProvider 映射内时退化成分享（用户可以存到能播的地方）
        shareFile(context, file)
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        shareFile(context, file)
    } catch (t: Throwable) {
        shareFile(context, file)
    }
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

private fun mb(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
