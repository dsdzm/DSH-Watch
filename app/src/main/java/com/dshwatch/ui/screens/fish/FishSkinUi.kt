package com.dshwatch.ui.screens.fish

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.dshwatch.domain.fish.FishSkin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 自定义外观的三个零件：读图、头像、背景。
 *
 * 三处共用同一份实现，是因为"头像"在顶栏、气泡列表、设置预览里各有一颗 ——
 * 分别写一份的必然结局是某一处忘了兜底：图片被删之后顶栏正常、
 * 气泡那边一整片空白，用户以为是自己选错了图。
 */

/**
 * 把 `fish/xxx.png` 读成位图。
 *
 * 解码放在 IO 线程，并且**失败就是 null**：文件被删、格式不支持、
 * 内存不够都能发生在运行时，而这一页不该因为一张壁纸打不开就白屏。
 * key 用路径 + 修改时间 —— 用户换了一张同名图时也要重新解码。
 */
@Composable
fun rememberFishImage(
    path: String,
    store: com.dshwatch.data.store.FishSkinStore?,
): ImageBitmap? {
    if (path.isBlank() || store == null) return null
    val file = store.imageOf(path)
    val stamp = file?.lastModified() ?: 0L
    val state by produceState<ImageBitmap?>(initialValue = null, path, stamp) {
        value = file?.let { f ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    return state
}

/** 她那一侧的头像：有图用图，没图用默认那条鱼的渐变圆 */
@Composable
fun FishAvatar(
    image: ImageBitmap?,
    size: Dp = 36.dp,
    emoji: String = "🐟",
) {
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    val brush = Brush.linearGradient(listOf(Color(0xFF6EC6F5), Color(0xFF8B6BF0)))
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = (size.value * 0.46f).sp, color = Color.White)
    }
}

/**
 * 整页背景。
 *
 * 背景图之上永远盖一层 [dim] 的暗色：壁纸多半比气泡亮，不压一层的话
 * 浅色气泡白字会糊成一片。这个值用户能调（`background_dim`）——
 * 有人就喜欢亮一点，代价自己承担，但默认值必须是能看清字的。
 */
@Composable
fun FishBackdrop(
    image: ImageBitmap?,
    dim: Float,
    fallback: Color,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(fallback)) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (dim > 0f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.85f))),
                )
            }
        }
        content()
    }
}

/** 页面上显示的名字：用户改过就用改过的，没改过用角色卡 */
fun FishSkin.displayNameOr(cardName: String): String =
    name.takeIf { it.isNotBlank() } ?: cardName

/** 主色。没设置时返回 null，调用方回退主题色 */
fun FishSkin.accentOrNull(): Color? = accentColorInt()?.let { Color(it) }

/** 她那侧气泡底色。没设置时返回 null */
fun FishSkin.theirsOrNull(): Color? = theirsColorInt()?.let { Color(it) }
