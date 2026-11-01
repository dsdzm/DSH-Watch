package com.dshwatch.ui.screens.about

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dshwatch.ui.components.Chip
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.i18n.AppStrings
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll

/**
 * QQ 群号。数字不参与翻译：换一个数字就不是同一个群了。
 */
internal const val GROUP_ID = "1087183734"

/**
 * 社区与支持的三张入口卡。
 *
 * 抽成独立页面而不是塞在「关于」里，是因为三者的使用场景完全不同：
 * 反馈群是「出问题了才点」，捐赠是「想谢一下才点」，致谢是「想看看这是谁做的」。
 * 挤在关于页里，每一个都会被更新日志淹没 —— 手表一屏只有三四行。
 */
@Composable
fun CommunityEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
    if (showDivider) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        )
    }
}

/** 三张入口卡的图标，集中一处方便对齐观感 */
internal fun communityIcons(): Triple<ImageVector, ImageVector, ImageVector> =
    Triple(Icons.Filled.Group, Icons.Filled.VolunteerActivism, Icons.Filled.Favorite)

// ----------------------------------------------------------------------
// 加入群聊
// ----------------------------------------------------------------------

@Composable
fun GroupChatScreen(nav: WatchNavigator) {
    val s = LocalStrings.current
    val clipboard = LocalClipboardManager.current

    WatchScaffold(title = s.groupTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.groupIntro,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            QrCard(asset = "community/qq_group.jpg", contentDescription = s.groupTitle)

            Spacer(Modifier.height(10.dp))
            SettingCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = s.communityGroup,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = GROUP_ID,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    CopyChip(text = GROUP_ID, label = s.communityCopy, done = s.communityCopied)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = s.groupLongPress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ----------------------------------------------------------------------
// 捐赠
// ----------------------------------------------------------------------

@Composable
fun DonateScreen(nav: WatchNavigator) {
    val s = LocalStrings.current

    WatchScaffold(title = s.donateTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.donateIntro,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            QrCard(asset = "community/donate_wechat.jpg", contentDescription = s.donateTitle)

            Spacer(Modifier.height(6.dp))
            Text(
                text = s.donateScan,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            SettingCard {
                Text(
                    text = s.donateNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = s.donateThanks,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

// ----------------------------------------------------------------------
// 特别感谢
// ----------------------------------------------------------------------

@Composable
fun ThanksScreen(nav: WatchNavigator) {
    val s = LocalStrings.current

    WatchScaffold(title = s.thanksTitle, onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = s.thanksIntro,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader(s.thanksPeople)
            SettingCard {
                ThanksLine("东山の字幕", "作者、设计与全部代码")
                ThanksLine("反馈群里的每一位", "把 Bug、崩溃和「这样不对」都告诉了我")
                ThanksLine("早期内测的朋友", "在最难用的版本上用了下去")
            }

            SectionHeader(s.thanksProjects)
            SettingCard {
                ThanksLine("Jetpack Compose", "整个界面的地基")
                ThanksLine("Kotlin & kotlinx.serialization", "语言与序列化")
                ThanksLine("OkHttp", "所有网络请求")
                ThanksLine("Chaquopy / CPython", "full 版的 Python 运行时")
                ThanksLine("ZXing", "扫码登录")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = s.thanksFooter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ThanksLine(name: String, why: String) {
    Column(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = why,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ----------------------------------------------------------------------
// 共用件
// ----------------------------------------------------------------------

/**
 * 二维码卡片。
 *
 * 直接显示原图，不重绘：重新画一遍二维码（尤其是微信这种带中心头像的）
 * 很容易少几个模块，扫不出来。原图右上角那点锯齿无所谓，能扫才是第一位的。
 *
 * 白底是硬需求 —— 二维码是黑块，深色主题下直接贴在深灰背景上会变成
 * 「深灰底黑块」，对比度不够。所以不管主题是明是暗，这里都套一层白。
 */
@Composable
private fun QrCard(asset: String, contentDescription: String) {
    val context = LocalContext.current
    val bmp = remember(asset) {
        runCatching { context.assets.open(asset).use { BitmapFactory.decodeStream(it) } }
            .getOrNull()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (bmp == null) {
            Text(
                text = "图片资源缺失：$asset",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }
        Box(
            modifier = Modifier
                .width(200.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(8.dp),
        ) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CopyChip(text: String, label: String, done: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Chip(
        text = if (copied) done else label,
        onClick = {
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
    )
}
