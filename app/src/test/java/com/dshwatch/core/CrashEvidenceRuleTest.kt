package com.dshwatch.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 静态防线：崩溃取证链必须是通的。
 *
 * 背景（用户原话）：
 * > 「修复华为手表4使用闪退问题」
 * > 「没有日志，打开直接闪退」
 *
 * 这两句放在一起就是死循环：要修就得先知道崩在哪，可 App 打不开就拿不到日志。
 * 唯一能解开的是「不需要打开 App 也能取到的证据」。
 *
 * ## 为什么用静态测试守
 *
 * 这条链路是**跨文件、跨进程**的：Application 里写标记 → 主界面抹标记 →
 * 外部目录留报告。任何一环被后来的人删掉或改顺序，编译照过、单测照过，
 * 只有真正出事那天才会发现「什么都没记下来」——而那时已经在用户手上了。
 *
 * JVM 单测跑不了 Android 的 Application 生命周期，所以只能读源码守结构。
 */
class CrashEvidenceRuleTest {

    private val mainSrc = File("src/main/java/com/dshwatch")

    private fun read(path: String): String {
        val f = File(mainSrc, path)
        assertTrue("找不到 $path：${f.absolutePath}", f.isFile)
        return f.readText()
    }

    /** 把注释剥掉再扫：KDoc 里为了讲用法写的示例调用不是真调用 */
    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `Application 一开始就写启动标记`() {
        val s = read("DshWatchApp.kt")
        val onCreate = s.substringAfter("override fun onCreate()")
        val body = stripComments(onCreate)

        assertTrue(
            "beginLaunch 必须在 Application.onCreate 里调用，否则「装上就闪退」时什么都没记下",
            body.contains("CrashLog.beginLaunch("),
        )

        // 顺序：标记必须在 Logx / install / AppContainer **之前**。
        // 写在后面对「崩在 AppContainer 构造」这种情况就完全失明了。
        val beginAt = body.indexOf("CrashLog.beginLaunch(")
        listOf("AppContainer(", "CrashLog.install(").forEach { later ->
            val at = body.indexOf(later)
            assertTrue("$later 在 beginLaunch 之前出现了，标记就来不及写", at < 0 || at > beginAt)
        }
    }

    @Test
    fun `依赖容器构造失败也要留下现场`() {
        val body = stripComments(read("DshWatchApp.kt"))
        assertTrue(
            "AppContainer(this) 必须包 runCatching —— 容器构造不出来说明磁盘/权限异常，" +
                "此时至少要把报告写到外部目录再抛",
            body.contains("runCatching { AppContainer(") || body.contains("runCatching { AppContainer(this) }"),
        )
        assertTrue(
            "容器构造失败要导出「启动失败-容器.txt」",
            body.contains("启动失败-容器"),
        )
    }

    @Test
    fun `只画出内容就算启动成功，判定点不能放在 AppRoot`() {
        val m = stripComments(read("MainActivity.kt"))
        assertTrue(
            "MainActivity 里 settingsReady 之后必须抹掉启动标记",
            m.contains("CrashLog.endLaunch("),
        )
        assertTrue(
            "写文件要挪到 IO 线程：手表上主线程卡一下就掉帧",
            m.contains("Dispatchers.IO"),
        )
        // 顺序：先写体检（它要读「上次是否崩过」），再抹标记
        val snap = m.indexOf("writeStartupSnapshot")
        val end = m.indexOf("CrashLog.endLaunch")
        assertTrue("writeStartupSnapshot 要在 endLaunch 之前，否则体检永远写不出「上次没走完」", snap in 0..<end)

        /*
         * **不能再放回 AppRoot。**
         *
         * AppRoot 开头就有 `if (!onboardingDone) { 引导页; return }`，
         * 新用户装完先看 4 步引导，走不到后面的代码。他中途退出（很常见），
         * 标记留着，下次启动就报「上次没能启动完成」。
         *
         * 这个假阳性最坏的地方是它会污染「装上就闪退」这种反馈 ——
         * 用户说闪退，我们看到「上次没走完」，两条错信息撞在一起。
         */
        val root = stripComments(read("ui/AppRoot.kt"))
        assertTrue(
            "AppRoot 里不许再出现 endLaunch：它前面有引导页的 early return，" +
                "放在这里会让「没走完引导」被误判成闪退",
            !root.contains("CrashLog.endLaunch("),
        )
    }

    @Test
    fun `标记要在覆盖之前先快照上次结果`() {
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue(
            "beginLaunch 必须先把上一次的标记状态存进 launchWasIncomplete，再覆盖文件 —— " +
                "顺序反了，界面再去读磁盘读到的是本次的标记，永远为真",
            s.contains("launchWasIncomplete"),
        )
        // 现在"读"这一侧抽成了 judgePreviousMarker（因为它要跟安装时间比对）。
        // 断言仍然守同一件事：判定必须在写标记**之前**发生。
        val readAt = s.indexOf("judgePreviousMarker(context)")
        val writeAt = s.indexOf("LAUNCH_MARKER)")
        assertTrue(
            "必须先判定上次标记、再覆盖文件；顺序反了，界面读到的是本次的标记，永远报崩溃",
            readAt in 0..<writeAt,
        )
    }

    @Test
    fun `崩溃报告要抄到外部目录`() {
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue(
            "write() 必须调用 export()：私有目录里的报告，App 打不开就永远拿不出来",
            s.contains("export(context, text,"),
        )
        assertTrue(
            "外部目录必须是 getExternalFilesDir —— 它不需要任何权限，" +
                "是 App 打不开时唯一还够得着的通道",
            s.contains("getExternalFilesDir("),
        )
        assertTrue(
            "每次启动都要写一份体检，兜住「一秒都撑不到」那种闪退",
            s.contains("fun writeStartupSnapshot("),
        )
    }

    @Test
    fun `启动阶段必须按时间正序输出`() {
        /*
         * 用户贴回来的那份体检报告里，「首帧组合完成」排在最上面，
         * 「Application.onCreate 开始」排在最后 —— 读起来像启动倒着走。
         *
         * 原因：环形缓冲里新的在末尾，`recent()` 返回的就是「旧 → 新」，
         * 这里却跟别处一样套了 `asReversed()`。
         */
        // **必须剥注释再扫** —— 上面那段说明里本身就写着 asReversed()，
        // 不剥的话这条断言永远红。这已经是第三次被同一件事骗了：
        // PressFeedbackRuleTest 一次、GlassDialogScrimRuleTest 一次、这里一次。
        // 凡是「扫源码断言某些字不存在」的测试，一律先过 stripComments。
        val s = stripComments(read("core/CrashLog.kt"))
        val snapshot = s.substringAfter("fun writeStartupSnapshot(").substringBefore("fun export(")
        assertTrue(
            "启动阶段是先后有序的，不许反转",
            !snapshot.contains("asReversed()"),
        )
    }

    @Test
    fun `启动耗时不能用会被 Activity 重建污染的基准`() {
        /*
         * 实测报告里出现过「本次启动：53364ms 走到这里」，而真实值是 711ms。
         *
         * launchMark 只在 onCreate 里赋值，进程没死、Activity 被重建时它还是旧值。
         * 时间基准必须用「进程开始」那个只定格一次的变量。
         */
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue("需要一个进程级的时间锚点", s.contains("processStartAt"))
        assertTrue(
            "processStartAt 必须只在第一次赋值（if == 0L），否则重建后又被改写",
            s.contains("if (processStartAt == 0L) processStartAt ="),
        )
        assertTrue(
            "mark() 要基于 processStartAt 打点，不能再直接用 launchMark",
            s.contains("processStartAt.takeIf"),
        )
    }

    @Test
    fun `体检要写清内存够不够用`() {
        // 实测有一台 maxMemory() 只有 192MB 的设备。这种机器上 OOM 是头号嫌疑，
        // 而 OOM 恰好是 uncaught handler 抓不到的那类 —— 报告里必须直接说清楚，
        // 不能让读报告的人自己拿 192 去跟什么比较。
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue("要有一行内存诊断", s.contains("memoryVerdict"))
        assertTrue("要写当前堆占用", s.contains("heapDesc"))
        assertTrue(
            "偏紧 / OOM 风险要直接写出来",
            s.contains("OOM 风险高"),
        )
    }

    @Test
    fun `体检内容不许包含用户数据`() {
        val s = read("core/CrashLog.kt")
        val snap = s.substringAfter("fun writeStartupSnapshot(").substringBefore("fun export(")
        listOf("apiKey", "getApiKey", "biliCookie", "getBiliCookie", "conversation").forEach { secret ->
            assertTrue(
                "启动体检会写到外部目录、用户会直接发给我们，不能包含「$secret」",
                !snap.contains(secret, ignoreCase = true),
            )
        }
    }

    @Test
    fun `覆盖安装留下的旧标记不能被当成崩溃`() {
        /*
         * 实测踩到的误报：用户覆盖安装新版本后第一次启动，体检里写着
         * 「上次启动：❌ 没走完」，而用户原话是「我应用是正常启动的，没有闪退」。
         *
         * 原因是覆盖安装必然先杀旧进程，旧进程没机会抹标记；新版一启动
         * 就读到它。这是最常见的安装方式，所以这条误报几乎必然出现。
         *
         * 判据只能是「标记时间 vs 本次安装时间」：早于安装时间的标记
         * 属于上一个安装实例，跟崩溃无关。
         */
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue(
            "要读安装时间来判断标记归属",
            s.contains("lastUpdateTime"),
        )
        assertTrue(
            "要有 STALE 这个结论，不能只有「崩了/没崩」两态",
            s.contains("MarkerVerdict.STALE"),
        )
        assertTrue(
            "标记早于安装时间时必须判 STALE —— 这是覆盖安装的唯一区分点",
            s.contains("wroteAt < installedAt"),
        )
        // 时间读不出来时（部分 ROM 的 PackageManager 会抛）要保守判崩溃，
        // 宁可多报一次，也不能漏掉真的。
        assertTrue(
            "安装时间读不到时要保守判 CRASHED，不能默认忽略",
            s.contains("if (installedAt <= 0L) return MarkerVerdict.CRASHED"),
        )
    }

    @Test
    fun `首帧只记一次，重建要写成重建`() {
        /*
         * 另一份实测报告里「首帧组合完成」出现了两次：589ms 和 10896ms。
         * 第二条不是重复打点，是这个 Activity 被系统回收后重建了
         * （192MB 堆的机器上很常见）。但两条一样的文字放在报告里，
         * 读的人会以为启动花了 11 秒。
         */
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue("要有 markFirstFrame", s.contains("fun markFirstFrame"))
        assertTrue(
            "要单独存 firstFrameAt，而且只写一次",
            s.contains("firstFrameAt") && s.contains("firstFrameAt == 0L"),
        )
        assertTrue(
            "第二次起要写成「界面重建」并说明不是重启也不是闪退",
            s.contains("界面重建第") && s.contains("不是重启也不是闪退") &&
                s.contains("R/"),
        )
        // 运行期事件必须和启动阶段分开：上一版把「界面重建」混在启动列表里，
        // 时间戳 11181ms 排在「首帧 568ms」后面，读起来就是"启动又走了一遍"。
        assertTrue(
            "体检里要有单独的「进程运行期事件」段，不能和启动阶段混在一起",
            s.contains("进程运行期事件"),
        )
        // MainActivity 必须用 markFirstFrame，不能用通用的 mark
        val act = stripComments(read("MainActivity.kt"))
        assertTrue(
            "MainActivity 要用 markFirstFrame，否则重建时报告里会有两条「首帧」",
            act.contains("CrashLog.markFirstFrame()") && !act.contains("mark(\"首帧组合完成\")"),
        )
    }

    @Test
    fun `启动耗时要和进程运行时长分开写`() {
        /*
         * 「本次启动」以前写的是「进程活到现在多久」。用户点进设置、
         * 停两分钟再导出，就会看到「本次启动：120000ms」——
         * 看上去像启动要两分钟。真实是两件事：
         *   启动耗时 = 到第一帧；已运行 = 进程活了多久。
         */
        val s = stripComments(read("core/CrashLog.kt"))
        val snap = s.substringAfter("fun writeStartupSnapshot(").substringBefore("fun export(")
        assertTrue("要写「到第一帧」的耗时", snap.contains("到第一帧"))
        assertTrue("要单独写「本进程已运行」", snap.contains("本进程已运行"))
    }

    @Test
    fun `上次进程要写清活了多久`() {
        /*
         * 第二份报告里「上次启动：❌ 没走完」是**真的**（不是覆盖安装误报），
         * 但报告只说了这一句 —— 上个进程活了 3 秒还是 3 小时，看不出区别。
         *
         * 这两种情况性质完全不同：
         *   - 几秒内就没 → 启动期崩溃，是故障
         *   - 跑了几小时 → 用户切走、系统回收后台，正常
         * 所以必须有心跳，把存活时长交出来。
         */
        val s = stripComments(read("core/CrashLog.kt"))
        assertTrue("要有 touchAlive 心跳", s.contains("fun touchAlive"))
        assertTrue("要有上次进程存活描述", s.contains("previousRunDesc"))
        assertTrue("体检里要写出上次进程", s.contains("上次进程："))
        // 心跳必须真的在跑，只定义不调用等于没有
        val app = stripComments(read("DshWatchApp.kt"))
        assertTrue(
            "心跳要接进 Application，否则永远只有启动时那一次",
            app.contains("CrashLog.touchAlive"),
        )
        assertTrue(
            "正常结束时要记一笔，否则分不清「正常退出」和「进程被杀」",
            s.contains("LAST_OK_AT"),
        )
    }

    @Test
    fun `启动路径上每一步都打点`() {
        val app = stripComments(read("DshWatchApp.kt"))
        listOf(
            "Application.onCreate 开始",
            "崩溃兜底已安装",
            "依赖容器构造完成",
        ).forEach { step ->
            assertTrue("启动打点缺了「$step」，闪退时就说不清崩在哪一段", app.contains(step))
        }

        val container = stripComments(read("core/AppContainer.kt"))
        listOf("设置仓库已构造", "密钥库已构造", "对话引擎已构造").forEach { step ->
            assertTrue(
                "AppContainer 的属性是顺序初始化的，每个易碎字段都要打点：缺「$step」",
                container.contains(step),
            )
        }
    }
}
