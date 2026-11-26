package com.dshwatch.ui.screens.fish

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshwatch.domain.model.Attachment
import com.dshwatch.domain.model.AttachmentKinds
import com.dshwatch.domain.model.MediaKind
import com.dshwatch.domain.model.MediaRef
import com.dshwatch.domain.model.MediaStatus
import com.dshwatch.ui.media.MediaStrip

/**
 * 用户随消息带过来的图片与文件。
 *
 * ## 为什么要单独一层
 *
 * 养鱼页原本只渲染文字：用户发一张照片过去，她能看见（多模态输入），
 * 但**用户自己看不见**自己发了什么 —— 屏幕上只剩下自己打的字，
 * 会以为照片没发出去。
 *
 * 这里把两类东西拼成一份可渲染的媒体列表：
 *  - 自己传的图片附件（`Attachment(kind=image)`）：转成 [MediaRef]，
 *    复用已有的缩略图 + 全屏查看器；
 *  - 模型生成/返回的媒体（`ChatMessage.media`）：原样交给 `MediaStrip`。
 *
 * 转一道的意义是不用再写一个图片查看器：`MediaStrip` 已经处理了
 * 本地文件、加载失败、视频首帧这些边界。
 */
internal fun userMedia(attachments: List<Attachment>, media: List<MediaRef>): List<MediaRef> {
    val images = attachments
        .filter { it.ok && it.kind == AttachmentKinds.IMAGE && it.path.isNotBlank() }
        .map { att ->
            MediaRef(
                id = att.id,
                kind = MediaKind.IMAGE,
                path = att.path,
                mime = att.mime,
                sizeBytes = att.sizeBytes,
                // 缩略图不依赖远端，本地文件在就能看
                status = MediaStatus.DONE,
                prompt = att.name,
            )
        }
    return images + media
}

/**
 * 非图片附件：一行一个文件名。
 *
 * 手表上打开一份 PDF 没有意义，用户要确认的是「我到底传了什么」。
 * 名字 + 字符数（或提取失败的原因）就把这件事说完了。
 */
@Composable
internal fun FishAttachmentNotes(
    attachments: List<Attachment>,
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    val files = attachments.filter { it.kind != AttachmentKinds.IMAGE }
    if (files.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        // 自己发的东西靠右，和气泡同一侧
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        files.forEach { att ->
            Row(
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(scheme.surfaceContainerHighest.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📄",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = " ${att.name} · ${att.summary()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (att.ok) scheme.onSurface else scheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 图片：缩略图一排，点开看大图。
 *
 * 和附件说明分开，是因为它们在界面上是两种东西：文件是一行元信息，
 * 图片是内容本身。混在一个列表里，图片会被压成和文件名一样宽。
 */
@Composable
internal fun FishMediaStrip(
    media: List<MediaRef>,
    mine: Boolean,
    onOpen: (MediaRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 250.dp),
            horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        ) {
            MediaStrip(media = media, onOpen = onOpen)
        }
    }
}

/** 空状态用不上，但保留一个占位尺寸常量，避免两侧各写一套魔法数 */
internal val FishMediaThumb: androidx.compose.ui.unit.Dp = 72.dp

/** 供测试断言：图片附件必须真的进渲染列表 */
internal fun isRenderableImage(att: Attachment): Boolean =
    att.ok && att.kind == AttachmentKinds.IMAGE && att.path.isNotBlank()
