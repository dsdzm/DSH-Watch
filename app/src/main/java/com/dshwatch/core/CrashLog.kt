package com.dshwatch.core

import android.content.Context
import android.os.Build
import com.dshwatch.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局未捕获异常兜底：把堆栈写进文件、留在内存里，下次启动由界面主动取走。
 *
 * ## 为什么不直接交给系统
 *
 * release 包开了 R8，系统崩溃对话框只会给用户看「屡次停止运行」，
 * 开发者这边什么都拿不到。用户能做的只有「再点一次」，我们连
 * 崩在哪一行都不知道 —— 上一轮排查崩溃就卡在这一步，最后只能靠猜。
 *
 * 所以这里做三件事：
 * 1. 崩溃瞬间把堆栈 + 机型 + 版本 + 最近几条 Logx 写到 `files/crash/last.txt`
 * 2. 仍然把异常交回默认处理器，让系统进程按正常流程结束（不吞崩溃）
 * 3. 下次启动时由 `CrashNotice` 读出来，在界面上让用户一键复制发给开发者
 *
 * 用**文件**而不是内存：崩溃后进程会没了，内存里的东西一起没；
 * 文件能跨进程存活，是唯一可靠的通道。
 */
object CrashLog {

    private const val DIR = "crash"
    private const val LAST = "last.txt"
    private const val LAST_OK_AT = "launch.ok.at"
    private const val ALIVE_AT = "alive.at"
    private const val FIRST_FRAME_AT = "launch.firstframe"

    /**
     * 「界面上来了」的标记。
     *
     * ## 为什么需要它：进程 ≠ 界面
     *
     * [beginLaunch] 挂在 `Application.onCreate` 上 —— 那是**进程**的起点。
     * 而这个 App 有一堆会在后台把进程拉起来的东西：B站的定时闹钟、
     * 前台服务的重启、更新检查。这些唤醒**不打开任何界面**，
     * 却同样会写下"启动中"标记，然后进程在几十秒内被系统回收。
     *
     * 下次用户真的打开 App，读到那个标记，就判成「上次没能启动完成」。
     * 用户看到的是"每次都弹一张错误报告"，而每一次都不是崩溃。
     *
     * 所以真正的判据不是"进程起来过"，而是"界面来过"。这个文件在
     * `MainActivity.onCreate` 写下，缺了它就说明那次只是后台唤醒。
     */
    private const val UI_AT = "launch.ui"

    /**
     * 启动收尾用的进程级协程。
     *
     * 不能挂在 Activity 的 `LaunchedEffect` 上 —— 那个协程跟着 Activity 销毁
     * 一起被取消。收尾要落两次盘（私有目录 + `Android/data`），这段时间里
     * 只要界面被系统回收，`endLaunch` 就永远不执行，启动标记留在磁盘上，
     * 下次启动就会报「上次没能启动完成」。
     *
     * 这不是理论风险：实测设备 412×502、192MB 堆、系统标记为低内存，
     * 20 分钟里界面被回收重建 5 次 —— 用户看到的是一开机弹一张错误卡片，
     * 而他自己什么都没遇到。
     */
    private val finishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 缓存在内存里的一份，供当前进程内的诊断页读取 */
    @Volatile
    var lastText: String? = null
        private set

    /**
     * 启动标记的判定规则（纯函数，便于单测）。
     *
     * 四个输入都是磁盘上四个小文件里的时间戳，0 表示那个文件不存在。
     * 判定顺序是有原因的，不能重排：
     *
     * 1. **没有界面（`uiAt == 0`）** → 后台唤醒。闹钟、前台服务重启、更新检查
     *    都会把进程拉起来，写标记，然后被系统回收 —— 这是"每次都弹"的头号来源。
     *    判据是文件在不在，确定、不需要拿时间猜，所以排第一。
     * 2. **有更新的正常收尾记录（`lastOkAt > wroteAt`）** → 残留。正常收尾是
     *    「先写成功时间，再删标记」，所以成功时间比标记新，说明那个标记早该被删了。
     * 3. **标记早于本次安装** → 覆盖安装杀了旧进程，不是崩溃。
     * 4. 其余才算崩溃。安装时间读不出来（部分 ROM 会抛）时也归到这里：
     *    宁可多报一次，也别漏掉真的。
     */
    internal fun judgeMarkerVerdict(
        wroteAt: Long,
        installedAt: Long,
        lastOkAt: Long,
        uiAt: Long,
    ): MarkerVerdict {
        if (uiAt <= 0L) return MarkerVerdict.BACKGROUND
        if (lastOkAt > wroteAt) return MarkerVerdict.STALE
        if (installedAt <= 0L) return MarkerVerdict.CRASHED
        return if (wroteAt < installedAt) MarkerVerdict.STALE else MarkerVerdict.CRASHED
    }

    /** 装到 Application 上。重复调用无副作用 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }

            /*
             * 手表上的死锁：崩一次 → 系统弹「屡次停止运行」→ 用户点「重新打开」
             * → 又回到那个会崩的页面 → 再崩。中间没有机会切走。
             *
             * 所以除了交回系统处理器，还起一个 60 秒后的定时出口：
             * 如果系统迟迟不把进程结束（有些厂商 ROM 的崩溃对话框会一直挂着），
             * 这里主动退出，下次启动就是一次干净的重来。
             *
             * 用 System.exit 而不是 Process.killProcess：前者会走 Runtime 的
             * shutdown hook，DataStore / 文件写入的收尾还能跑完。
             */
            runCatching {
                Timer(true).schedule(
                    object : TimerTask() {
                        override fun run() = runCatching { System.exit(2) }.let { }
                    },
                    60_000L,
                )
            }

            // 仍然交回系统：不吞崩溃，保持标准行为
            previous?.uncaughtException(thread, error)
        }
        // 读上一次留下的
        runCatching {
            val f = File(File(app.filesDir, DIR), LAST)
            if (f.exists()) lastText = f.readText()
        }
    }

    /** 崩溃报告文件。没有就返回 null */
    fun file(context: Context): File? =
        File(File(context.filesDir, DIR), LAST).takeIf { it.exists() && it.length() > 0 }

    /** 用户点了「知道了」之后清掉，避免每次启动都弹 */
    fun clear(context: Context) {
        lastText = null
        runCatching { file(context)?.delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("时间：$time")
            appendLine("线程：${thread.name}")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
            // 单独一行环境摘要：HarmonyOS 兼容层上的崩溃和真 Android 上的
            // 崩溃往往是两套完全不同的原因，不能混在一起统计
            appendLine("环境：${EnvironmentWarning.summary()}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("ROM：${Build.DISPLAY.orEmpty()} / 厂商补丁 ${Build.VERSION.INCREMENTAL.orEmpty()}")
            /*
             * 以前这里只要 launchMark 有值就印「崩在启动阶段」，于是跑了两小时的
             * 进程崩掉也这么写。beta.35 的用户报告正是这样：崩溃发生在第 21 分钟、
             * 界面重建之后，报告第一行却说「崩在启动阶段」，把排查带偏了一整轮。
             *
             * 真正的判据是**有没有画出过第一帧**：画过就说明启动早就完成了。
             */
            appendLine(
                "进程已运行：" + (System.currentTimeMillis() - (processStartAt.takeIf { it > 0 } ?: launchMark))
                    .coerceAtLeast(0) + "ms" +
                    if (firstFrameAt > 0) {
                        "（首帧 ${firstFrameAt}ms，启动已完成，不是启动阶段）"
                    } else {
                        "（还没画出第一帧，崩在启动阶段）"
                    },
            )
            appendLine("异常：${error.javaClass.name}: ${error.message.orEmpty()}")
            appendLine()
            appendLine(sw.toString())
            val recent = Logx.recent()
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("---- 崩溃前最近的操作 ----")
                recent.asReversed().take(20).forEach { appendLine(it) }
            }
        }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // 只留最后一次：用户连续崩十次，我们要的是最新那份，不是十份
        File(dir, LAST).writeText(text)
        lastText = text

        // 再抄一份到**用户拿得到的地方**。
        //
        // 这是华为手表 4 那次反馈的直接产物：用户说「装上就闪退，打不开」——
        // 报告写在自己进程的私有目录里，App 打不开就永远拿不出来。
        // 取证能力等于零，写再多也没用。
        runCatching { export(context, text, name = "崩溃-last.txt") }
    }

    /**
     * 构造一份「启动没走完」的报告，用于 native 崩溃 / 被 kill / watchdog 这类
     * 抓不到 Java 堆栈的情况。
     *
     * 内容全是启动那一刻**已经确定**的事实：机型、ROM、ABI、可用内存、
     * 走到哪一步、最后的 warning。没有堆栈不等于没有信息 ——
     * 「崩在 Application.onCreate 之前/之后」「ABI 是什么」这些已经能砍掉一大半可能。
     */
    fun buildLaunchFailureReport(context: Context): String {
        val rt = Runtime.getRuntime()
        return buildString {
            appendLine("时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("类型：上次启动没有走到主界面（没有 Java 堆栈）")
            appendLine("可能原因：native 崩溃 / 被系统回收 / 主线程卡死 / 启动期 OOM")
            if (launchMark > 0) {
                appendLine("本次启动已进行：${System.currentTimeMillis() - launchMark}ms")
            }
            appendLine()
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("环境：${EnvironmentWarning.summary()}")
            appendLine("品牌：${Build.BRAND} / 型号：${Build.DEVICE}")
            appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
            appendLine("ROM：${Build.DISPLAY.orEmpty()} / 补丁 ${Build.VERSION.INCREMENTAL.orEmpty()}")
            appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(",")}")
            appendLine("内存上限：${rt.maxMemory() / (1024 * 1024)}MB")
            appendLine("CPU 核数：${Runtime.getRuntime().availableProcessors()}")
            appendLine("屏幕：${screenDesc(context)}")
            val recent = Logx.recent()
            if (recent.isNotEmpty()) {
                appendLine()
                appendLine("---- 启动阶段最后几条 ----")
                recent.asReversed().take(30).forEach { appendLine(it) }
            } else {
                appendLine()
                appendLine("---- 启动阶段最后几条 ----")
                appendLine("（无。说明连第一条日志都没打出来，崩溃发生在 Application 之前）")
            }
        }
    }

    /** 体检里「上次启动」那一行该怎么写 */
    private fun verdictText(): String = when (launchMarkerVerdict) {
        MarkerVerdict.CLEAN -> "✅ 正常"
        MarkerVerdict.CRASHED -> "❌ 没走完（疑似闪退）"
        // 这一条补的是一个很常见的误报：覆盖安装会先杀进程，
        // 旧进程来不及抹标记，新版一启动就读到它，看起来像「上次崩了」。
        MarkerVerdict.STALE -> "➖ 忽略：标记是这次安装之前留下的（覆盖安装会杀进程，不算崩溃）"
        // 闹钟 / 服务 / 广播拉起进程 —— 没有界面，所以也不该有"启动完成"这一说
        MarkerVerdict.BACKGROUND -> "➖ 忽略：上次是后台唤醒（没打开过界面），不是崩溃"
    }

    /**
     * 毫秒时间戳转成可读时间；0 表示「不知道」。
     *
     * 体检里要写清楚"这个进程是什么时候起来的" —— 用户贴回来的第一份报告里
     * 时间是 17:29:55，但「上次启动没走完」是几天前的事还是刚才的事，
     * 光看那一行看不出来。有了绝对时间，我可以直接对上「你什么时候装的」。
     */
    private fun stamp(ms: Long): String =
        if (ms <= 0) {
            "未知"
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
        }

    /**
     * 当前堆占用。
     *
     * 「写这份体检的时候还剩多少」—— 在 192MB 上限的机器上，这个数字比
     * 「上限是多少」更有信息量：如果写到这里已经用了 150MB，
     * 那后面随便打开一个页面就够它 OOM 了。
     */
    private fun heapDesc(rt: Runtime): String = runCatching {
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val total = rt.totalMemory() / (1024 * 1024)
        val max = rt.maxMemory() / (1024 * 1024)
        val pct = if (max > 0) (used * 100 / max) else 0
        "已用 ${used}MB / 已分配 ${total}MB / 上限 ${max}MB（${pct}%）"
    }.getOrDefault("未知")

    /**
     * 一行内存判断，给「打不开 / 闪退」这类反馈用。
     *
     * 为什么单独做这一行：`Runtime.maxMemory()` 只是**这个进程的 Java 堆上限**，
     * 不是设备总内存。实测碰到过一台 `sprd` 平台的定制设备，这值是 **192MB** ——
     * 在那上面，OOM 是头号嫌疑，而 OOM 恰恰是
     * `Thread.setDefaultUncaughtExceptionHandler` 抓不到的那一类（很多时候
     * 系统直接调 `Runtime.getRuntime().halt()` 或者 native 层就 abort 了）。
     *
     * 所以报告里必须把「够不够用」直接写出来，不要让读报告的人自己换算。
     */
    private fun memoryVerdict(context: Context, rt: Runtime): String {
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val totalMb = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)).toInt()
        }.getOrDefault(-1)
        val lowRam = runCatching {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice
        }.getOrDefault(false)

        val level = when {
            maxMb < 256 -> "偏紧（OOM 风险高）"
            maxMb < 512 -> "一般"
            else -> "充裕"
        }
        return buildString {
            append(level)
            if (totalMb > 0) append(" · 设备总计 ${totalMb}MB")
            if (lowRam) append(" · 系统标记为低内存设备")
        }
    }

    private fun screenDesc(context: Context): String = runCatching {
        val dm = context.resources.displayMetrics
        val round = context.resources.configuration.isScreenRound
        "${dm.widthPixels}×${dm.heightPixels} @${dm.densityDpi}dpi · " +
            (if (round) "圆屏" else "方屏")
    }.getOrDefault("未知")

    /**
     * 启动体检：**不管崩不崩都写**，每次都覆盖。
     *
     * 这一条是为了对付最坏的情况 ——
     * 「装上就闪退，连一秒都撑不到，根本来不及点复制」。
     * 前面所有机制（CrashNotice、无堆栈报告、诊断页）都需要用户能进到界面，
     * 而这类反馈的原文就是"打不开"。
     *
     * 所以只要进程活到 MainActivity 这一步，就把设备信息、启动各阶段耗时、
     * 上次是否崩过全部落到外部目录。用户插上电脑拷出来发过来，
     * 我们至少能回答三个关键问题：
     *   1. 这个 App 在他机器上到底有没有跑起来过；
     *   2. 卡在哪一步；
     *   3. 是什么机器、什么 ROM、什么 ABI。
     *
     * 内容里**不含任何用户数据**：没有 API Key、没有对话、没有工作空间文件。
     * 只有机型、版本号、我们自己打的启动点。
     */
    /**
     * @param tier 当前性能档（LOW / MID / HIGH），由调用方从 PerfProfile 取。
     *   CrashLog 在 core 层，不该反向依赖 AppContainer，所以用字符串传。
     */
    fun writeStartupSnapshot(context: Context, tier: String? = null) {
        runCatching {
            val rt = Runtime.getRuntime()
            val text = buildString {
                appendLine("=== DSH Watch 启动体检 ===")
                appendLine("写入时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("上次启动：${verdictText()}")
                // 只写"疑似闪退"等于什么都没说。补上一条：上次那个进程活了多久。
                // 活了几个小时的，多半是用户在后台切走、被系统回收，不是故障。
                appendLine("上次进程：$previousRun")
                /*
                 * 有 Java 堆栈时把异常那一行也抄进来。
                 *
                 * 崩溃报告和体检是两份文件，用户一般只会发其中一份 ——
                 * 而他发过来的那份，往往正好缺另一半的信息。
                 */
                lastText?.lineSequence()
                    ?.firstOrNull { it.startsWith("异常：") }
                    ?.let { appendLine("上次崩溃：${it.removePrefix("异常：")}") }
                // 「本次启动」这一行以前写的是「进程活到现在多久」，
                // 于是用户点进设置、停留两分钟再导出，就会看到「本次启动：120000ms」——
                // 看上去像启动要两分钟。真实含义其实是两件事，必须拆开写：
                //   启动耗时 = 到第一帧画出来；已运行 = 这个进程活了多久。
                appendLine(
                    "本次启动：${
                        if (firstFrameAt > 0) "${firstFrameAt}ms 到第一帧" else "还没画出第一帧"
                    }",
                )
                appendLine(
                    "本进程已运行：${
                        if (processStartAt > 0) "${System.currentTimeMillis() - processStartAt}ms" else "未知"
                    }",
                )
                appendLine("本次进程启动于：${stamp(processStartAt)}")
                appendLine()
                appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / ${Build.BRAND} ${Build.DEVICE}")
                appendLine("Android：${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
                appendLine("ROM：${Build.DISPLAY.orEmpty()} / 补丁 ${Build.VERSION.INCREMENTAL.orEmpty()}")
                appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString(",")}")
                appendLine("内存上限：${rt.maxMemory() / (1024 * 1024)}MB")
                appendLine("堆使用：${heapDesc(rt)}")
                appendLine("内存诊断：${memoryVerdict(context, rt)}")
                // 这台实测设备很典型：物理内存 3906MB、4 核，但 ROM 把
                // ro.config.low_ram 设成了 true，于是 maxMemory 只有 192MB，
                // 我们据此判 LOW 并关掉动画/跟手光晕。用户看到的是"特效全没了"，
                // 却不知道可以自己在设置里改回来 —— 所以必须在报告里写出当前档位。
                appendLine("性能档位：${tier ?: "未知"}（设置 → 性能 可改）")
                appendLine("CPU 核数：${rt.availableProcessors()}")
                appendLine("屏幕：${screenDesc(context)}")
                appendLine()
                appendLine("---- 启动各阶段 ----")
                /*
                 * **顺序要正过来。**
                 *
                 * 环形缓冲里新的在末尾，`recent()` 返回的就是「旧 → 新」。
                 * 之前这里跟其它地方一样套了 `asReversed()`，把最新的排到了第一个 ——
                 * 用户贴回来的那份报告里，「首帧组合完成」排在最上面，
                 * 「Application.onCreate 开始」排在最后，读起来像是启动倒着走的。
                 *
                 * 启动阶段是**有先后因果**的东西，必须按时间正序读。
                 */
                Logx.recent().filter { it.startsWith("L/") }.forEach { appendLine(it) }

                /*
                 * 运行期事件单独一段。
                 *
                 * 上一版把「界面重建」也写进了启动阶段列表，而它排在「首帧完成」
                 * 后面、时间戳又是 11181ms —— 用户（和我自己）读到的都是
                 * "启动又走了一遍"。实际上它跟启动毫无关系：Application 只创建了一次，
                 * 是 Activity 这一层被系统回收后重建的（内存紧张时的标准行为）。
                 *
                 * 用不同的前缀（R/ 而非 L/）分开，比在同一段里加措辞更可靠 ——
                 * 措辞会被误读，前缀不会。
                 */
                val runtime = Logx.recent().filter { it.startsWith("R/") }
                if (runtime.isNotEmpty()) {
                    appendLine()
                    appendLine("---- 进程运行期事件（不属于启动）----")
                    runtime.forEach { appendLine(it) }
                }
            }
            export(context, text, name = "启动体检.txt")
        }
    }

    /**
     * 把报告抄到 Android/data/<包名>/files 下。
     *
     * 这个目录**不需要任何权限**（targetSdk 34 也一样），用户插上电脑、
     * 或者用系统文件管理器就能直接看到并拷出来 —— 这是"App 打不开"时唯一
     * 还够得着的通道。
     *
     * 注意不能用公共的 Documents 之类：targetSdk 30+ 起写公共目录要走 SAF
     * 或 MediaStore，两者都要用户交互，崩溃回调里做不了。
     */
    fun export(context: Context, text: String, name: String = "崩溃-last.txt"): File? {
        val dir = externalDir(context) ?: return null
        val out = File(dir, name)
        out.writeText(text)
        return out
    }

    /** 一份「启动中」标记，正常跑到主界面后删掉。见 [beginLaunch] */
    private const val LAUNCH_MARKER = "launch.inprogress"

    /**
     * 启动开始：写下标记。
     *
     * 为什么需要它：`Thread.setDefaultUncaughtExceptionHandler` 抓不到
     * **native 崩溃、OOM、被系统直接 kill、主线程 watchdog**。
     * 这几种情况下 `write()` 根本不会被执行，用户看到的是"闪退且没有报告"。
     *
     * 有了这个标记，下次启动时只要它还在，就说明**上次没跑到主界面**，
     * 即使没有任何 Java 堆栈，也能确定"崩在启动路径上"，并把
     * 启动阶段日志一并交出来。这是在没有堆栈时唯一能拿到的确定信息。
     */
    fun beginLaunch(context: Context) {
        launchMark = System.currentTimeMillis()
        // 只在进程第一次走到这里时定格。之后 Activity 重建不再改写 ——
        // 「本次启动耗时」问的是这个进程活了多久，不是这个 Activity 活了多久。
        if (processStartAt == 0L) processStartAt = launchMark
        // 上次的标记如果还在，说明**上一次**没走完。此刻必须先把这件事记下来，
        // 因为下面一行马上就要覆盖标记 —— 覆盖之后证据就没了。
        //
        // 但"标记还在"不等于"崩了"。见 [judgePreviousMarker]。
        launchMarkerVerdict = judgePreviousMarker(context)
        previousRun = previousRunDesc(context)
        touchAlive(context)
        launchWasIncomplete = launchMarkerVerdict == MarkerVerdict.CRASHED
        /*
         * 旧的一次性标记要在写新标记之前清掉。
         *
         * [UI_AT] / [FIRST_FRAME_AT] 描述的是"**这一次**进程有没有界面、有没有画到首帧"。
         * 上一个进程留下的那份必须在这里作废 —— 否则本次是后台唤醒（不写 UI_AT），
         * 下次启动读到的还是上一次的 UI_AT，又会判成"有界面却没收尾"。
         */
        runCatching { File(File(context.filesDir, DIR), UI_AT).delete() }
        runCatching { File(File(context.filesDir, DIR), FIRST_FRAME_AT).delete() }
        runCatching {
            File(File(context.filesDir, DIR).apply { mkdirs() }, LAUNCH_MARKER)
                .writeText(launchMark.toString())
        }
        // 标记同时往外部目录抄一份：私有目录里的标记一旦被系统清掉（清缓存、
        // 重装、卸载残留）就再也看不到"上次崩过"这件事，而外部目录
        // 8 天内即使卸载也还在，是我们唯一能穿透的时间窗。
        runCatching { externalDir(context)?.let { File(it, LAUNCH_MARKER).writeText(launchMark.toString()) } }
    }

    /** 上一次启动留下的标记该怎么解读 */
    enum class MarkerVerdict {
        /** 没有标记：上次是正常走到界面并抹掉的 */
        CLEAN,

        /** 标记还在，且写于本次安装之后 → 真的是上次进程没走到界面 */
        CRASHED,

        /** 标记还在，但写于本次安装**之前** → 覆盖安装杀掉了旧进程，不是崩溃 */
        STALE,

        /**
         * 标记还在，但那次**根本没有界面**（闹钟 / 服务 / 广播把进程拉起来的）。
         *
         * 这一类最容易被误判成崩溃：标记在、没有堆栈、存活时间还特别短 ——
         * 三条都跟"启动崩了"一模一样。区别只有一个：那次没写 [UI_AT]。
         */
        BACKGROUND,
    }

    /**
     * 判断上次留下的启动标记是什么意思。
     *
     * 这里有一个非常容易误判的场景，而且是**最常见**的场景：
     * 用户覆盖安装新版本时，系统必然先杀掉旧进程。旧进程没机会执行
     * [endLaunch]，标记就留在 `filesDir` 里。新版第一次启动读到它，
     * 就会报「上次启动没走完（疑似闪退）」—— 用户明明什么都没遇到。
     *
     * 实测就是这么来的：一份报告写着「上次启动：❌ 没走完」，
     * 而用户的原话是「我应用是正常启动的，没有闪退」。
     *
     * 区分办法是拿标记的时间戳和**本次安装时间**比：
     * 标记早于安装时间，那它一定是上一个安装实例留下的，跟崩溃无关。
     * 只有标记晚于安装时间，才说明是这个版本自己没走完。
     *
     * 再补一条更硬的判据：**[LAST_OK_AT] 比标记还新**。
     *
     * 每次正常收尾都会先写「成功时间」再删标记。所以只要磁盘上有一条
     * 比标记更新的成功记录，就说明**那个标记已经被处理过了**，它现在还在
     * 只可能是删除失败（外部存储被占、ROM 锁文件），不是崩溃。
     *
     * 这一条是为「每次都报」准备的：安装时间在同一台机器上读完就不变，
     * 而标记会被每次启动重写；一旦 endLaunch 因为生命周期被取消而漏删，
     * 旧判据会**永久**判 CRASHED，用户每开一次就弹一次卡片。加上这条之后，
     * 那种情况会被识别成残留并自愈。
     */
    private fun judgePreviousMarker(context: Context): MarkerVerdict {
        val dir = File(context.filesDir, DIR)
        val wroteAt = runCatching {
            val f = File(dir, LAUNCH_MARKER)
            if (f.exists()) f.readText().trim().toLongOrNull() else null
        }.getOrNull() ?: return MarkerVerdict.CLEAN

        return judgeMarkerVerdict(
            wroteAt = wroteAt,
            installedAt = lastUpdateTime(context),
            lastOkAt = readLong(context, File(dir, LAST_OK_AT)),
            uiAt = readLong(context, File(dir, UI_AT)),
        )
    }

    /**
     * 界面真的起来了：记一笔。
     *
     * 在 `MainActivity.onCreate` 调，越早越好 —— 它要和 [beginLaunch] 配对，
     * 两者之差就是「进程起来了」和「用户真的打开了」之间的区别。
     *
     * 同步写一个小文件（几十字节）：这一步不能异步，异步就等于给崩溃留了个
     * 窗口 —— 恰好崩在窗口里的，会被判成后台唤醒而漏报。
     */
    fun markUiAttached(context: Context) {
        runCatching {
            File(File(context.filesDir, DIR).apply { mkdirs() }, UI_AT)
                .writeText(System.currentTimeMillis().toString())
        }
    }

    /** 本次安装 / 更新的时间。兼容安装时被杀进程的判定要用它 */
    private fun lastUpdateTime(context: Context): Long = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** 主界面第一帧画完了：说明启动成功，抹掉标记 */
    fun endLaunch(context: Context) {
        // 先记一笔"这次是正常结束的"。抹标记之前写，抹完就无法区分
        // "正常退出"和"进程被杀"了。
        runCatching {
            File(File(context.filesDir, DIR), LAST_OK_AT).writeText(System.currentTimeMillis().toString())
        }
        touchAlive(context)
        runCatching { File(File(context.filesDir, DIR), LAUNCH_MARKER).delete() }
        runCatching { externalDir(context)?.let { File(it, LAUNCH_MARKER).delete() } }
        // 首帧标记也一起抹掉：它只在「进程没走到收尾」时有诊断价值
        runCatching { File(File(context.filesDir, DIR), FIRST_FRAME_AT).delete() }
        runCatching { File(File(context.filesDir, DIR), UI_AT).delete() }
    }

    /**
     * 启动收尾（进程级，不随 Activity 销毁取消）。
     *
     * 顺序是「首帧落盘 → 体检 → 抹标记」：
     *  - 首帧落盘让下一次报告能区分「崩在启动路径上」和「画完界面后在运行期没的」
     *  - 体检要在抹标记之前写，它读的是本次启动**开始时**快照下来的判定
     *
     * 用 [finishScope] 而不是调用方的协程，原因见那个字段的注释。
     */
    fun finishLaunchAsync(context: Context, tier: String? = null) {
        val app = context.applicationContext
        finishScope.launch {
            runCatching { persistFirstFrame(app) }
            runCatching { writeStartupSnapshot(app, tier) }
            runCatching { endLaunch(app) }
            Unit
        }
    }

    /** 把"这次画到首帧了"写下来，供下一次启动判断崩溃发生在哪个阶段 */
    private fun persistFirstFrame(context: Context) {
        val at = firstFrameAt
        if (at <= 0) return
        runCatching {
            File(File(context.filesDir, DIR).apply { mkdirs() }, FIRST_FRAME_AT)
                .writeText(at.toString())
        }
        touchAlive(context)
    }

    /**
     * 用户点了「知道了」：承认这一次，并且把标记清掉。
     *
     * 只把界面上的卡片收起来是不够的 —— 标记还在磁盘上，下次启动会再弹一次。
     * 那正是「每次进入应用都有这个错误报告」的成因。用户既然已经看过这份报告，
     * 就不该被同一份再拦一次。
     */
    fun dismissLaunchFailure(context: Context) {
        launchWasIncomplete = false
        runCatching { File(File(context.filesDir, DIR), LAUNCH_MARKER).delete() }
        runCatching { externalDir(context)?.let { File(it, LAUNCH_MARKER).delete() } }
        runCatching { File(File(context.filesDir, DIR), UI_AT).delete() }
    }

    /**
     * 心跳：进程还活着就刷一下这个时间。
     *
     * 为什么需要它：「上次没走完」只说明"上次那个进程没有正常走到 endLaunch"，
     * 但这件事有几种完全不同的原因，不看存活时长分不出来：
     *   - 启动几秒内就没的 → 启动期崩溃 / 被系统杀
     *   - 跑了两小时才没的 → 用户切走，系统在后台把它回收了（不算故障）
     * 没有心跳的话，报告里只有一句"疑似闪退"，等于什么都没说。
     */
    fun touchAlive(context: Context) {
        runCatching {
            File(File(context.filesDir, DIR).apply { mkdirs() }, ALIVE_AT)
                .writeText(System.currentTimeMillis().toString())
        }
    }

    /** 上一次进程留下的存活痕迹，给报告用 */
    private fun previousRunDesc(context: Context): String = runCatching {
        val dir = File(context.filesDir, DIR)
        val started = readLong(context, File(dir, LAUNCH_MARKER))
        if (started <= 0) return@runCatching "无记录"
        val alive = readLong(context, File(dir, ALIVE_AT))
        val lasted = if (alive > started) alive - started else 0
        val endedText = if (alive > started) "最后一次还活着：${stamp(alive)}" else "没有任何存活记录（进程起来就没）"
        /*
         * 补一句「崩在哪个阶段」。
         *
         * 「上次没走完」可能是启动期死的，也可能是画完界面之后在运行期没的 ——
         * 两者的排查方向完全相反，但原来那句话对二者一视同仁。
         * 首帧标记能分清：有它 = 界面画出来了；没它 = 连第一帧都没到。
         */
        val firstFrame = readLong(context, File(dir, FIRST_FRAME_AT))
        val phase = if (firstFrame > 0) {
            "，且已画出首帧（消失发生在运行期，不是启动崩）"
        } else {
            "，且没画到首帧（消失发生在启动路径上）"
        }
        "上个进程启动于 ${stamp(started)}，${endedText}，存活 ${lasted / 1000} 秒$phase"
    }.getOrDefault("无记录")

    private fun readLong(context: Context, f: File): Long =
        runCatching { if (f.exists()) f.readText().trim().toLongOrNull() ?: 0 else 0 }.getOrDefault(0)

    /** 上次启动是否没走完。读一次就够，界面自己缓存 */
    fun lastLaunchIncomplete(context: Context): Boolean =
        runCatching { File(File(context.filesDir, DIR), LAUNCH_MARKER).exists() }.getOrDefault(false)

    /**
     * 本次启动**开始时**发现上次没走完。
     *
     * 之所以要单独存这个标志，是因为 [lastLaunchIncomplete] 读的是磁盘，
     * 而 [beginLaunch] 在启动最开始就把磁盘上的旧标记覆盖掉了 ——
     * 等到界面画出来再去读，读到的是"本次"的标记，永远为真。
     * 界面上真正要回答的是"上一次崩没崩"，所以必须在覆盖之前快照。
     */
    @Volatile
    var launchWasIncomplete: Boolean = false
        private set

    /** 上次标记的判定结果，体检里要按它写不同的说法 */
    @Volatile
    var launchMarkerVerdict: MarkerVerdict = MarkerVerdict.CLEAN
        private set

    /** 上一次进程的存活情况，一句话 */
    @Volatile
    var previousRun: String = "无记录"
        private set

    @Volatile
    private var launchMark: Long = 0L

    /**
     * 第一帧画出来了。
     *
     * 跟普通 [mark] 分开，是因为「首帧」只能有一次。
     *
     * 用户贴回来的报告里，「首帧组合完成」出现了两次：589ms 和 10896ms。
     * 第二条不是重复打点，是**这个 Activity 被系统回收后重建了**
     * （192MB 堆、系统标记为低内存设备的机器上非常常见：切出去看个文件、
     * 锁屏再解锁，界面就被销毁重建）。但两条一模一样的文字放在报告里，
     * 读的人第一反应是"是不是打点写重了"，第二反应是"启动花了 11 秒"。
     *
     * 所以第一次记 [firstFrameAt]（这个数字**不会再变**，它才是真正的启动耗时），
     * 之后的都写成「界面重建」，并说明这不是闪退。
     */
    fun markFirstFrame() {
        runCatching {
            val base = processStartAt.takeIf { it > 0 } ?: launchMark
            val elapsed = (System.currentTimeMillis() - base).coerceAtLeast(0)
            if (firstFrameAt == 0L) {
                firstFrameAt = elapsed
                Logx.remember("L/${elapsed}ms 首帧组合完成")
            } else {
                rebuilds++
                Logx.remember(
                    "R/${elapsed}ms 界面重建第 ${rebuilds} 次" +
                        "（系统回收了界面后重建，不是重启也不是闪退）",
                )
            }
        }
    }

    /** 启动阶段打点。
     *
     * 只往 [Logx] 的环形缓冲里写，**不进 Logcat**：release 包里 Log.w 也是要
     * 走系统调用的，启动路径上每一步都打一条会拖慢冷启动。
     *
     * 这些点唯一的作用是：万一 App 在启动途中没了，下次启动时
     * [buildLaunchFailureReport] 能把"最后走到第几步"交出来。
     * 没有堆栈的情况下，这就是唯一能定位"崩在哪一段"的东西。
     */
    fun mark(step: String) {
        runCatching {
            // 时间戳相对**本次进程**的启动点，不是相对 launchMark。
            //
            // launchMark 是静态变量，只在 Application.onCreate 里赋值。进程没死、
            // 只是 Activity 被重建（用户切走再回来）时它还是上一次的值 ——
            // 实测见过一条「本次启动：53364ms 走到这里」，而真实值是 711ms。
            // 那不是启动慢，那是把上一次的时间也算了进去。
            val base = processStartAt.takeIf { it > 0 } ?: launchMark
            Logx.remember("L/${(System.currentTimeMillis() - base).coerceAtLeast(0)}ms $step")
        }
    }

    /** 进程第一次被点开的时间。进程活着就一直不变 */
    @Volatile
    private var processStartAt: Long = 0L

    /** 到第一帧用了多少毫秒。0 = 还没画出来。定格后不再改 */
    @Volatile
    private var firstFrameAt: Long = 0L

    /** 同一进程里界面被系统回收后重建了几次 */
    @Volatile
    private var rebuilds: Int = 0

    private fun externalDir(context: Context): File? = runCatching {
        context.getExternalFilesDir(null)?.let { root -> File(root, DIR).apply { mkdirs() } }
    }.getOrNull()
}
