package com.dshwatch.ui.screens.fish

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.fish.FishSkin
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.SectionHeader
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「她的样子」：这一页的自定义入口。
 *
 * ## 三件事分开做，是因为它们的感觉不同
 *
 * 名字是"她叫什么"，改完立刻在顶栏看见；头像是"她长什么样"，
 * 换一张整页的气质就变了；背景是"你们在哪儿聊天"。
 * 混成一长串设置项会让人不知道从哪儿下手，所以分成三块卡片。
 *
 * ## 为什么和对话里的 `fish_skin` 共存
 *
 * 两条路各有擅长：说话时想到什么就说什么（"背景换成深蓝"），
 * 或者坐下来慢慢挑一张图。工具擅长前者，这一页擅长后者 ——
 * 缺任何一条，用户都会在另一条上做不该做的事（在聊天框里描述颜色值，
 * 或者为了改个名字去找设置）。
 */
@Composable
fun FishSkinScreen(
    container: AppContainer,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val stored by container.fishSkin.skin.collectAsStateWithLifecycle()
    val card = remember { com.dshwatch.domain.roleplay.BuiltinCards.feiyu() }

    /*
     * 名字用本地草稿：每敲一个字就落盘会让输入框在中文输入法下反复跳
     * （保存 → flow 更新 → 重组 → 光标重置）。离开焦点或点保存时再写。
     */
    var nameDraft by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /** 选回来的图片先落到临时文件，再交给 store 收进 fish/ */
    fun importFrom(uri: Uri, slot: String) {
        scope.launch {
            busy = true
            note = ""
            try {
                val tmp = withContext(Dispatchers.IO) { copyToCache(context, uri) }
                if (tmp == null) {
                    note = "这张图读不出来，换一张试试"
                    return@launch
                }
                val saved = container.fishSkin.importImage(tmp, slot)
                    .getOrElse { e ->
                        note = e.message ?: "这张图用不了"
                        return@launch
                    }
                container.fishSkin.save(
                    if (slot == "avatar") {
                        container.fishSkin.skin.value.copy(avatar = saved)
                    } else {
                        container.fishSkin.skin.value.copy(background = saved)
                    },
                )
                note = if (slot == "avatar") "头像换好了" else "背景换好了"
            } finally {
                busy = false
            }
        }
    }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importFrom(uri, "avatar")
    }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importFrom(uri, "background")
    }

    WatchScaffold(title = "她的样子", subtitle = "只改这一页", onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            HintText("这些只影响养鱼这一页，别的页面不变。也可以在聊天里直接跟她说，例如「背景换成深蓝」。")
            Spacer(Modifier.height(6.dp))
            /*
             * 一眼看出"到底存下来没有"。
             *
             * 她说改好了、页面却没变，用户没法判断是没执行还是没落盘。
             * 这一行读的是**磁盘上的那份配置**（flow 就是它），
             * 于是"退出重进变回原样"这件事当场就能对上号。
             */
            HintText(
                run {
                    val done = buildList {
                        if (stored.name.isNotBlank()) add("名字")
                        if (stored.avatar.isNotBlank()) add("头像")
                        if (stored.background.isNotBlank()) add("背景")
                        if (stored.accent != 0L) add("主色")
                        if (stored.bubbleTheirs != 0L) add("她那侧气泡")
                        if (stored.textSize != com.dshwatch.domain.fish.FishSkin.DEFAULT.textSize) add("字号")
                        if (stored.avatarSize != com.dshwatch.domain.fish.FishSkin.DEFAULT.avatarSize) add("头像大小")
                        if (!stored.showAvatar) add("隐藏头像")
                        if (stored.greeting.isNotBlank()) add("开场白")
                    }
                    if (done.isEmpty()) {
                        "现在是默认样子（还没改过任何一项）"
                    } else {
                        "已经改过：${done.joinToString("、")}"
                    }
                },
            )
            Spacer(Modifier.height(8.dp))

            /* ---------- 名字 ---------- */
            SectionHeader("她叫什么")
            OutlinedTextField(
                value = nameDraft ?: stored.name,
                onValueChange = { nameDraft = it.take(FishSkin.NAME_MAX) },
                placeholder = { Text(card.displayName, style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            WatchGhostButton(
                text = "保存名字",
                modifier = Modifier.fillMaxWidth(),
                enabled = nameDraft != null && nameDraft != stored.name,
                onClick = {
                    val v = nameDraft.orEmpty().trim()
                    scope.launch {
                        container.fishSkin.save(stored.copy(name = v))
                        nameDraft = null
                        note = if (v.isBlank()) "名字改回默认了" else "现在叫「$v」"
                    }
                },
            )
            HintText("留空就是用角色卡上的名字（${card.displayName}）。")

            /* ---------- 头像 ---------- */
            Spacer(Modifier.height(10.dp))
            SectionHeader("长什么样")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FishAvatar(
                    image = rememberFishImage(stored.avatar, container.fishSkin),
                    size = 48.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (stored.avatar.isBlank()) "还没设头像" else "已经换过了",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WatchGhostButton(
                            text = "选一张",
                            enabled = !busy,
                            onClick = {
                                runCatching {
                                    pickAvatar.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                }
                            },
                        )
                        if (stored.avatar.isNotBlank()) {
                            WatchGhostButton(
                                text = "去掉",
                                enabled = !busy,
                                onClick = {
                                    scope.launch {
                                        container.fishSkin.save(stored.copy(avatar = ""))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            HintText("圆形裁切。图片会复制一份到工作空间，相册删了也不影响。")

            /* ---------- 背景 ---------- */
            Spacer(Modifier.height(10.dp))
            SectionHeader("在哪儿聊天")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WatchGhostButton(
                    text = if (stored.background.isBlank()) "选一张背景" else "换一张背景",
                    enabled = !busy,
                    onClick = {
                        runCatching {
                            pickBackground.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                )
                if (stored.background.isNotBlank()) {
                    WatchGhostButton(
                        text = "去掉",
                        enabled = !busy,
                        onClick = {
                            scope.launch { container.fishSkin.save(stored.copy(background = "")) }
                        },
                    )
                }
            }
            if (stored.background.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                // 压暗程度：壁纸亮的时候白字会糊，这个滑杆就是那个旋钮
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("压暗", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                    listOf(0.2f, 0.35f, 0.55f, 0.75f).forEach { level ->
                        val on = kotlin.math.abs(stored.backgroundDim - level) < 0.001f
                        Box(
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (on) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .clickable {
                                    scope.launch {
                                        container.fishSkin.save(stored.copy(backgroundDim = level))
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "${(level * 100).toInt()}%",
                                fontSize = 10.sp,
                                color = if (on) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            /* ---------- 颜色 ---------- */
            Spacer(Modifier.height(10.dp))
            SectionHeader("颜色")
            HintText("我这侧的气泡")
            ColorRow(
                current = stored.accent.takeIf { it != 0L }?.let { Color(it.toInt()) },
                onPick = { c -> scope.launch { container.fishSkin.save(stored.copy(accent = c)) } },
            )
            Spacer(Modifier.height(6.dp))
            HintText("她那侧的气泡")
            ColorRow(
                current = stored.bubbleTheirs.takeIf { it != 0L }?.let { Color(it.toInt()) },
                onPick = { c -> scope.launch { container.fishSkin.save(stored.copy(bubbleTheirs = c)) } },
            )

            /* ---------- 字号 ---------- */
            Spacer(Modifier.height(10.dp))
            SectionHeader("字号")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(13, 15, 17, 19).forEach { size ->
                    val on = stored.textSize == size
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (on) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .clickable {
                                scope.launch { container.fishSkin.save(stored.copy(textSize = size)) }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            "$size",
                            fontSize = 11.sp,
                            color = if (on) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            /* ---------- 还原 ---------- */
            Spacer(Modifier.height(14.dp))
            WatchGhostButton(
                text = "全部恢复默认",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        container.fishSkin.reset()
                        nameDraft = null
                        note = "已经恢复成原来的样子"
                    }
                },
            )

            if (note.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                HintText(note)
            }
        }
    }
}

/** 一排预设色 + 「跟随主题」 */
@Composable
private fun ColorRow(current: Color?, onPick: (Long) -> Unit) {
    /*
     * 只有六个预设，不做自由取色器。
     *
     * 手表上拖色轮是灾难（手指比滑块大），而"想要那个颜色"的需求，
     * 用一句话跟她说比调色轮快得多 —— 那条路是 fish_skin 工具。
     */
    val presets = listOf(
        Color(0xFF12B7F5),
        Color(0xFF3A7BD5),
        Color(0xFF6EC6F5),
        Color(0xFFFF8FA3),
        Color(0xFF8B6BF0),
        Color(0xFF2E2E38),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        presets.forEach { c ->
            val on = current != null && current.value == c.value
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(c)
                    .border(
                        width = if (on) 2.dp else 1.dp,
                        color = if (on) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .clickable { onPick(c.toArgb().toLong() and 0xFFFFFFFFL) },
            )
        }
        // 0 = 跟随主题。必须留这个"取消"—— 选了色之后想回到默认，
        // 没有别的办法能表达"没选过"
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onPick(0L) },
            contentAlignment = Alignment.Center,
        ) {
            Text("×", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 把相册给的 uri 复制到缓存目录。
 *
 * 不直接用 uri：`content://` 的生命周期跟着权限走，重启就失效；
 * 而外观要长期留着。也不走 [com.dshwatch.data.attachment.AttachmentStore]
 * —— 那个给聊天附件用，有 8MB 上限，一张相机直出的壁纸轻松超过。
 */
private fun copyToCache(context: Context, uri: Uri): File? = runCatching {
    val dir = File(context.cacheDir, "fish-pick").apply { mkdirs() }
    val out = File(dir, "pick-${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
    } ?: return@runCatching null
    // 扩展名丢不了：store 按扩展名判断"能不能当图"
    val name = queryDisplayName(context, uri)
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext.isNotBlank() && ext != out.extension) {
        val renamed = File(dir, "${out.name}.$ext")
        if (out.renameTo(renamed)) return@runCatching renamed
    }
    out
}.getOrNull()

private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx).orEmpty() else ""
    }.orEmpty()
}.getOrDefault("")
