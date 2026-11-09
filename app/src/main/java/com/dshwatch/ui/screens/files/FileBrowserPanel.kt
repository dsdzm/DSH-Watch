package com.dshwatch.ui.screens.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshwatch.domain.files.DeviceFiles
import com.dshwatch.domain.files.FileNode
import com.dshwatch.domain.files.FileRoot
import com.dshwatch.domain.files.Listing
import com.dshwatch.domain.files.RootKind
import com.dshwatch.domain.files.StorageAccess
import com.dshwatch.ui.components.CircleIconTap
import com.dshwatch.ui.components.EmptyState
import com.dshwatch.ui.components.HintText
import com.dshwatch.ui.components.WatchGhostButton
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.components.glassSurface
import com.dshwatch.ui.components.withHaptic
import com.dshwatch.ui.i18n.LocalStrings
import com.dshwatch.ui.wear.WearList
import com.dshwatch.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内置文件浏览器。
 *
 * ## 为什么要有它
 *
 * `ACTION_OPEN_DOCUMENT` 依赖设备上有一个响应它的 DocumentsUI。一部分手表 ROM
 * 把文件管理器整个删了，于是 `OpenDocument` 直接拿不到结果 —— 用户点「＋文件」
 * 之后**什么都不发生**，附件功能等于不存在。这里给一条不依赖任何第三方 App 的通路。
 *
 * ## 它是「面板」而不是导航页
 *
 * 挂在输入页上，用 [Box] 盖住整屏，而不是 `nav.push(Screen.FileBrowser)`。
 * 原因很具体：页面栈换页时旧页会被 `AnimatedContent` 销毁，
 * 输入页里的草稿文字和已选附件都是普通 `remember`，一 push 就没了 ——
 * 用户挑完文件回来，发现自己打的半句话不见了。面板留在同一棵组合里，什么都没丢。
 *
 * ## 圆屏
 *
 * 外壳用 [WatchScaffold]（圆屏自动分派到 CircleScaffold），列表用 [WearList]：
 * 它是 `TransformingLazyColumn`，带鱼眼缩放和表冠支持。圆屏上滚到上下两端时
 * 可用宽度只剩中间一条，普通 `LazyColumn` 会把文件名切掉半截，
 * 而鱼眼会让边缘的项整体缩小、向内收。
 *
 * @param workspace 应用工作空间目录，永远可读，是没授权时的保底起点
 * @param onPick 选中一个文件。调用方负责读它（[com.dshwatch.data.attachment.AttachmentStore]）
 * @param onSystemPicker 非空时在列表末尾给一个「用系统选择器」的出口 ——
 *   内置读不到的目录（Android 11+ 没授权时很常见）还能走 SAF 碰碰运气，反之亦然
 */
@Composable
fun FileBrowserPanel(
    workspace: File?,
    onClose: () -> Unit,
    onPick: (File) -> Unit,
    onSystemPicker: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val zh = LocalStrings.current.isChinese

    /** 当前所在目录的栈。空栈 = 起点列表。用栈而不是单值，是为了「返回上一级」不靠 parent 推算 */
    val stack = remember { mutableStateListOf<File>() }
    val current = stack.lastOrNull()

    var listing by remember { mutableStateOf<Listing?>(null) }
    var granted by remember { mutableStateOf(StorageAccess.granted(context)) }
    var tick by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    /*
     * 起点列表必须跟着权限一起重算。
     *
     * `roots()` 用 `isDirectory` 过滤 —— 没权限时 `/sdcard/Download` 的 stat 会失败，
     * 那一项就不会出现。如果只 `remember(workspace)`，用户授权完回来列表还是老样子，
     * 表现为「点了授权、还是只有工作空间」，比不显示更让人以为坏了。
     */
    val roots = remember(workspace, granted) { DeviceFiles.roots(workspace) }

    /*
     * 从系统设置授权完回来，必须重新判一次权限 —— 没有回调可订阅。
     * 只看 ON_RESUME 就够：授权页是一个 Activity，回来必然 resume。
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = StorageAccess.granted(context)
                tick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Android 10 及以前是运行时权限，走系统弹窗
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        granted = StorageAccess.granted(context)
        tick++
    }

    LaunchedEffect(current, tick) {
        listing = null
        listing = withContext(Dispatchers.IO) {
            current?.let { DeviceFiles.list(it) } ?: Listing.Ok(emptyList())
        }
    }

    fun back() {
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.lastIndex)
            note = ""
        } else {
            onClose()
        }
    }

    // 系统返回手势 / 返回键：先在目录里往上退，退到起点才关面板
    BackHandler(enabled = true) { back() }

    val title = when {
        current != null -> current.name
        zh -> "选择文件"
        else -> "Choose file"
    }

    /*
     * 面板自己铺一层不透明底。
     *
     * 玻璃主题里 `colorScheme.background` 是 **Transparent** —— 各页面的根
     * `background()` 等于「让位」，真正的不透明底由 DshWatchTheme 在最下面
     * 连同两团色晕一起画。这在导航页上没问题（底下就是那层底），但面板是
     * **盖在输入页上**的：让位的结果是输入框、快捷指令、发送按钮全透上来，
     * 看起来像两个界面叠在一起。所以这里自己铺实底 ——
     * 玻璃主题用 surface（近黑 / 浅灰），其它主题沿用原背景色。
     */
    val scheme = MaterialTheme.colorScheme
    val solid = if (scheme.background.alpha < 0.05f) scheme.surface else scheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(solid)
            /*
             * 把空白处的点击吃掉。
             *
             * 上层 Box 没有 pointer input 时，落在「没有子元素」的区域（列表外的留白、
             * 圆角外）会一路穿到下面的输入页 —— 面板看着是模态的，行为却不是：
             * 点一下空白就可能插进一条快捷指令。这里只是消费点击，不拦截滚动。
             */
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        WatchScaffold(
            title = title,
            subtitle = current?.let { pathHint(it, roots, zh) },
            onBack = { back() },
            actions = {
                CircleIconTap(
                    icon = Icons.Filled.Refresh,
                    contentDescription = if (zh) "刷新" else "Refresh",
                    onClick = { tick++ },
                )
            },
        ) {
            /*
             * 内容区在圆屏上是固定高度（不是 weight 撑满），所以这里让列表吃满剩余空间，
             * 而不是让整页再套一层 verticalScroll —— 两层滚动在圆屏上会互相抢手势。
             */
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (val l = listing) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.width(28.dp).height(28.dp))
                    }

                    else -> WearList(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (note.isNotBlank()) {
                            item(key = "__note__") {
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                )
                            }
                        }

                        if (!granted) {
                            item(key = "__access__") {
                                AccessCard(zh = zh) {
                                    if (StorageAccess.needsSystemSettings()) {
                                        StorageAccess.requestAllFiles(context)
                                    } else {
                                        permLauncher.launch(StorageAccess.runtimePermission())
                                    }
                                }
                            }
                        }

                        if (current == null) {
                            rootItems(
                                roots = roots,
                                zh = zh,
                                onEnter = { stack.add(it) },
                            )
                        } else {
                            when (l) {
                                is Listing.Denied -> item(key = "__denied__") {
                                    DeniedCard(zh = zh, path = l.path)
                                }

                                is Listing.Missing -> item(key = "__missing__") {
                                    EmptyState(
                                        title = if (zh) "这个目录不在了" else "Folder is gone",
                                        hint = if (zh) "可能被别的应用删掉了，返回上一级重试" else null,
                                    )
                                }

                                is Listing.Ok -> {
                                    if (l.nodes.isEmpty()) {
                                        item(key = "__empty__") {
                                            EmptyState(
                                                title = if (zh) "空的" else "Empty",
                                                hint = if (zh) "这个文件夹里没有文件" else null,
                                            )
                                        }
                                    }
                                    items(l.nodes.size, key = { l.nodes[it].file.absolutePath }) { i ->
                                        val node = l.nodes[i]
                                        FileRow(node = node, zh = zh) {
                                            if (node.isDir) {
                                                stack.add(node.file)
                                            } else if (!DeviceFiles.canOpen(node.file)) {
                                                note = if (zh) {
                                                    "系统不让读「${node.name}」，换系统选择器试试"
                                                } else {
                                                    "Cannot read ${node.name}. Try the system picker."
                                                }
                                            } else {
                                                note = ""
                                                onPick(node.file)
                                            }
                                        }
                                    }
                                    if (l.truncated > 0) {
                                        item(key = "__more__") {
                                            HintText(
                                                if (zh) {
                                                    "目录里还有 ${l.truncated} 项没显示（一次最多 ${DeviceFiles.MAX_ITEMS} 项）"
                                                } else {
                                                    "${l.truncated} more items not shown " +
                                                        "(max ${DeviceFiles.MAX_ITEMS})"
                                                },
                                                modifier = Modifier.padding(horizontal = 6.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (onSystemPicker != null) {
                            item(key = "__system__") {
                                WatchGhostButton(
                                    text = if (zh) "用系统文件选择器" else "System file picker",
                                    modifier = Modifier.padding(top = 2.dp),
                                    onClick = onSystemPicker,
                                )
                            }
                        }

                        item(key = "__hint__") {
                            HintText(
                                if (current == null) {
                                    if (zh) {
                                        "内置浏览器不依赖任何第三方 App。选一个位置进去，点文件即可添加。"
                                    } else {
                                        "Built in, no file manager needed. Open a location, then tap a file."
                                    }
                                } else {
                                    if (zh) "点文件添加；返回键上一层" else "Tap a file to add it. Back goes up."
                                },
                                modifier = Modifier.padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(node: FileNode, zh: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                onClick = withHaptic(onClick),
            )
            .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = iconOf(node),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = subtitleOf(node, zh),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (node.isDir) {
            Text(
                text = "›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 起点列表。抽出来是因为 [WearList] 的 scope 是自定义的，不能在别处复用 item 定义 */
private fun com.dshwatch.ui.wear.WearListScope.rootItems(
    roots: List<FileRoot>,
    zh: Boolean,
    onEnter: (File) -> Unit,
) {
    if (roots.isEmpty()) {
        item(key = "__noroot__") {
            EmptyState(
                title = if (zh) "找不到可浏览的目录" else "No browsable folder",
                hint = if (zh) "这台设备没有可用的外部存储" else null,
            )
        }
        return
    }
    roots.forEach { root ->
        item(key = "root:${root.dir.absolutePath}") {
            RootRow(root = root, zh = zh, onClick = { onEnter(root.dir) })
        }
    }
}

@Composable
private fun RootRow(root: FileRoot, zh: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(
                shape = RoundedCornerShape(22.dp),
                cornerRadius = 22.dp,
                onClick = withHaptic(onClick),
            )
            .padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "📁", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(20.dp))
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rootLabel(root.kind, zh),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = root.dir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccessCard(zh: Boolean, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(22.dp), cornerRadius = 22.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (zh) "只能读应用自己的文件" else "Only app files are readable",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (zh) {
                if (StorageAccess.needsSystemSettings()) {
                    "下载、文档这些公共目录需要「所有文件访问」。不授权也能用，只是看不到那里的文件。"
                } else {
                    "下载、文档这些公共目录需要存储权限。不授权也能用，只是看不到那里的文件。"
                }
            } else {
                "Public folders need storage access. Without it only app files are listed."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        WatchGhostButton(
            text = if (zh) "去授权" else "Grant access",
            onClick = onRequest,
        )
    }
}

@Composable
private fun DeniedCard(zh: Boolean, path: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(22.dp), cornerRadius = 22.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (zh) "读不到这个目录" else "Cannot read this folder",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (zh) {
                "系统不允许按路径访问这里。授权之后再看，或者用下面的系统选择器。"
            } else {
                "Android does not allow path access here. Grant access, or use the system picker."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ---------------------------------------------------------------------------

private fun rootLabel(kind: RootKind, zh: Boolean): String = when (kind) {
    RootKind.WORKSPACE -> if (zh) "工作空间" else "Workspace"
    RootKind.DOWNLOAD -> if (zh) "下载" else "Downloads"
    RootKind.DOCUMENTS -> if (zh) "文档" else "Documents"
    RootKind.PICTURES -> if (zh) "图片" else "Pictures"
    RootKind.DCIM -> if (zh) "相机" else "Camera"
    RootKind.MUSIC -> if (zh) "音乐" else "Music"
    RootKind.STORAGE -> if (zh) "存储根目录" else "Storage"
}

/**
 * 顶栏副标题里的路径。
 *
 * 显示「下载 / 照片 / 2024」而不是 `/storage/emulated/0/Download/照片/2024`：
 * 后者在 155dp 宽的圆屏标题行里只能看到开头那串没有信息量的前缀。
 */
private fun pathHint(dir: File, roots: List<FileRoot>, zh: Boolean): String {
    val root = roots.firstOrNull { r ->
        dir.absolutePath == r.dir.absolutePath ||
            dir.absolutePath.startsWith(r.dir.absolutePath + File.separator)
    }
    if (root == null) return dir.name
    val rel = dir.absolutePath.removePrefix(root.dir.absolutePath).trim(File.separatorChar)
    return if (rel.isBlank()) rootLabel(root.kind, zh) else
        rootLabel(root.kind, zh) + " / " + rel.replace(File.separatorChar, '/')
}

private fun subtitleOf(node: FileNode, zh: Boolean): String {
    if (node.isDir) return if (zh) "文件夹" else "Folder"
    val size = Fmt.bytes(node.size)
    val date = Fmt.date(node.modifiedAt)
    return listOf(size, date).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun iconOf(node: FileNode): String {
    if (node.isDir) return "📁"
    return when (node.name.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg" -> "🖼"
        "pdf" -> "📕"
        "mp3", "wav", "flac", "aac", "m4a", "ogg", "amr" -> "🎵"
        "mp4", "mkv", "mov", "avi", "webm", "ts" -> "🎬"
        "apk", "zip", "rar", "7z", "gz", "tar" -> "📦"
        else -> "📄"
    }
}
