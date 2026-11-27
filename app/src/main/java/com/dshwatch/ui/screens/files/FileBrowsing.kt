package com.dshwatch.ui.screens.files

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 一次「挑个文件」的请求。
 *
 * @param filter 只留下满足条件的文件；目录永远保留（要能进得去）
 * @param onPicked 选中之后做什么。只在这一刻有效，[FileBrowsing.close] 之后就丢
 */
class FilePickRequest internal constructor(
    val filter: ((File) -> Boolean)?,
    val onPicked: (File) -> Unit,
)

/**
 * 全局的「唤出内置文件浏览器」。
 *
 * ## 为什么是全局单例，而不是每个页面各记一个布尔
 *
 * 这个 App 里挑文件的地方有八九处：附件、背景图、插件包、历史归档、皮肤图……
 * 之前每处都自己 `remember { mutableStateOf(false) }` + 一个 `if (open) FileBrowserPanel(...)`，
 * 于是同一个面板被复制了七八份，任何一处忘记兜 `ActivityNotFoundException`
 * 就是一次闪退（自定义背景就这么崩过）。
 *
 * 现在只有 [FileBrowserHost] 一处画面板，其余地方只调 [open]。
 * 「系统没有文件管理器」这个坑从此只有一条通路需要关心。
 *
 * ## 为什么不用 CompositionLocal
 *
 * CompositionLocal 需要每个页面在外层套 provider，本质上和「每个页面各画一份」
 * 是同一件事，只是换了个写法。这里的状态本来就该在整棵树之上 ——
 * 面板是**屏幕级**的，不是页面级的内容。
 *
 * ## 回调的生命周期
 *
 * [FilePickRequest] 持有一个 lambda，`close()` 时整条请求被丢弃，lambda 也随之
 * 可以被回收。所以不能把耗时操作（导入、解码）挂在回调里长跑 ——
 * 回调应该只做「记下这个文件」，真正的处理交给调用方自己的协程。
 */
object FileBrowsing {

    var request by mutableStateOf<FilePickRequest?>(null)
        private set

    val isOpen: Boolean get() = request != null

    /**
     * 打开浏览器。
     *
     * @param filter 只列这类文件。给图片就用 `CustomBackground::looksLikeImage`，
     *   给归档就用后缀判断 —— 不滤的话用户会在一屏 `.txt` 里翻找，选中了
     *   才被告知「这不是一张图」。
     */
    fun open(
        filter: ((File) -> Boolean)? = null,
        onPicked: (File) -> Unit,
    ) {
        request = FilePickRequest(filter, onPicked)
    }

    fun close() {
        request = null
    }
}

/**
 * 面板的唯一宿主。挂在导航树最外层（见 AppRoot），一屏一层。
 *
 * 放在最外层是关键：它要盖住顶栏、盖住页面内容、也盖住版本提示这类浮层。
 * 但**盖不住 `Dialog`** —— 那是另一个 Window。所以任何「在弹窗里点选文件」的
 * 调用方，都必须先收起弹窗再调 [FileBrowsing.open]，否则面板会被弹窗整块挡住。
 *
 * @param workspace 应用工作空间目录。没授权外部存储时的保底起点，永远可读
 */
@Composable
fun FileBrowserHost(workspace: File?) {
    val req = FileBrowsing.request ?: return
    FileBrowserPanel(
        workspace = workspace,
        fileFilter = req.filter,
        onClose = { FileBrowsing.close() },
        onPick = { file ->
            /*
             * 先关再回调。
             *
             * 回调里可能立刻做下一件事（导入、再开一次浏览器选第二个文件）。
             * 顺序反过来的话，`close()` 会把回调里刚设好的新请求一起清掉 ——
             * 表现为「选完一张之后再也点不开」。
             */
            FileBrowsing.close()
            req.onPicked(file)
        },
    )
}

/**
 * 统一的分派器：按设置里的「用内置文件浏览器」决定走哪条路。
 *
 * ## 为什么要有一个分派器，而不是各页面自己判断
 *
 * 挑文件的入口有九处（附件、背景、插件、历史归档、皮肤、Bili 配图……）。
 * 每处写一遍 `if (settings.builtinFilePicker) 内置 else 系统` 的结果是：
 * 九个地方九份 if，其中总有一两处忘了包 `runCatching` —— 那就是一次闪退。
 * 这里把它收成一个东西，调用方只写「我要挑一张图」。
 *
 * ## 关掉开关也不会崩
 *
 * 「用系统选择器」这条路依赖设备上真的有个文件管理器。华为手表这类 ROM 把它
 * 裁掉了，`launch()` 会抛 `ActivityNotFoundException`，而 Compose 的 launcher
 * 不会替你接住。所以**系统那条路失败时静默退回内置浏览器** ——
 * 用户要的是选到文件，不是知道哪条路不通。
 *
 * ## 为什么回调整体是 `Uri` 而不是 `File`
 *
 * 系统选择器只给 URI，拿不到路径（`content://` 背后可能是网盘）。
 * 内置浏览器给的是真实路径，包成 `file://` 即可 —— 下游只有一份处理逻辑。
 * 唯一要注意的是 `file://` 尽量别经 `ContentResolver`（部分 ROM 行为不一致），
 * 读取统一走 [com.dshwatch.domain.files.UriStreams]。
 */
class FilePicking internal constructor(
    /** 设置里的开关值。true = 内置浏览器优先 */
    val builtin: Boolean,
    private val dispatch: (mime: String, filter: ((File) -> Boolean)?, onPicked: (Uri) -> Unit) -> Unit,
) {
    /**
     * 挑一个文件。
     *
     * @param mime 系统选择器用的类型。手表上的文件管理器多数不认细分类型，
     *   限得太死只会让列表变空 —— 不确定就传「任意类型」，真正的过滤交给 [filter]
     * @param filter 内置浏览器用的过滤（目录永远保留）。给 null 表示不限
     * @param onPicked 拿到就回调。**不要在回调里做长耗时的事**：
     *   内置这条路上它是在面板关闭的那一刻调用的，界面此时可能正在重组
     */
    fun pick(
        mime: String = "*/*",
        filter: ((File) -> Boolean)? = null,
        onPicked: (Uri) -> Unit,
    ) = dispatch(mime, filter, onPicked)
}

/**
 * 声明一次分派器。必须在 Composable 作用域里创建 —— 它内部持有一个
 * `ActivityResultLauncher`，而那个东西只能在组合期间注册。
 *
 * @param builtin 默认读主题层提供的 [com.dshwatch.ui.theme.LocalBuiltinFilePicker]，
 *   也就是设置里那个开关。给参数只是为了测试能钉死两条分支
 */
@Composable
fun rememberFilePicking(
    builtin: Boolean = com.dshwatch.ui.theme.LocalBuiltinFilePicker.current,
): FilePicking {
    // 系统选择器是异步的：launch 之后界面可能重组好几次，回调得存在 state 里
    val pending = remember { mutableStateOf<((Uri) -> Unit)?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val cb = pending.value
        pending.value = null
        if (uri != null) cb?.invoke(uri)
    }

    return FilePicking(builtin) { mime, filter, onPicked ->
        if (builtin) {
            FileBrowsing.open(filter) { file -> onPicked(Uri.fromFile(file)) }
            return@FilePicking
        }
        pending.value = onPicked
        /*
         * 同步抛异常，必须接住 —— 这是整条链路里唯一会「点一下就崩」的地方。
         * 接住之后不解释、直接开内置面板：多一句「你的设备不支持」只会让人
         * 以为功能坏了，而他其实只想选个文件。
         */
        val launched = runCatching { launcher.launch(arrayOf(mime)) }.isSuccess
        if (!launched) {
            pending.value = null
            FileBrowsing.open(filter) { file -> onPicked(Uri.fromFile(file)) }
        }
    }
}
