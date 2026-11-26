package com.dshwatch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.dshwatch.MainActivity
import com.dshwatch.R
import com.dshwatch.core.Logx
import com.dshwatch.domain.fish.FishProactive
import com.dshwatch.domain.fish.FishProactiveSchedule
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.model.Role
import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.domain.roleplay.FishRhythm
import com.dshwatch.domain.roleplay.Scenes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 到点之后真正"让她说话"的那一段。
 *
 * ## 流程
 *
 * 1. 确保有一条养鱼会话（引擎里开着就沿用，否则接最近一条，都没有就新开）；
 * 2. 用 [ChatEngine.proactive] 发一轮生成 —— 这轮**不追加用户消息**，
 *    只在请求里塞一段对方看不见的提示；
 * 3. 等这一轮结束（后台没有界面，只能盯 `streaming`）；
 * 4. 把她说的第一句发成通知，点开就是跟她的对话。
 *
 * ## 为什么失败要安静
 *
 * 后台生成可能因为没网、欠费、模型抽风而失败。这时候**不发通知** ——
 * "她今天没说话"是正常的，"她发来一条报错"不是。日志里留一行就够，
 * 用户下次打开 App 会在对话页看到那条错误，位置也对。
 */
object FishProactiveRunner {

    private const val TAG = "FishProactive"

    /** 生成最长等这么久。超时就放弃发通知，消息本身仍在会话里（如果生成了） */
    private const val WAIT_MS = 150_000L

    /** 它会先追加占位消息、再切 streaming，两者差一帧；给 10 秒等它真的开始 */
    private const val START_WAIT_MS = 12_000L

    suspend fun run(context: Context, note: String) {
        val app = context.applicationContext as? com.dshwatch.DshWatchApp ?: return
        val c = app.container
        val settings = c.settingsRepo.current()
        val card = BuiltinCards.feiyu()

        /*
         * 总开关关着就什么都不做。
         *
         * 闹钟撤不干净（系统已经放出的那一次、升级前遗留的那一次）时，
         * 这里是最后一道闸 —— 不然用户关掉养鱼之后还会收到一条她的消息，
         * 那种"关了还在动"的观感比功能不可用糟糕得多。
         */
        if (!settings.fishEnabled) {
            Logx.i(TAG, "养鱼没开启，这一轮主动发言跳过")
            return
        }

        // 1) 会话
        val conv = ensureConversation(c, card.id)
        if (conv == null) {
            Logx.w(TAG, "没有可用的养鱼会话，跳过")
            return
        }

        // 2) 提示
        val recent = c.engine.messages.value
            .lastOrNull { it.role == Role.USER }?.content.orEmpty()
        val hint = FishProactive.hint(System.currentTimeMillis(), note, recent)
        if (!c.engine.proactive(hint, settings.fishProviderId, settings.fishModel)) {
            Logx.i(TAG, "引擎正忙，这一轮主动发言跳过")
            return
        }

        // 3) 等结束
        val finished = withTimeoutOrNull(WAIT_MS) {
            val started = withTimeoutOrNull(START_WAIT_MS) { c.engine.streaming.first { it } }
            if (started == null) return@withTimeoutOrNull false
            c.engine.streaming.first { !it }
            true
        } ?: false
        if (!finished) {
            Logx.w(TAG, "等待生成结束超时")
            return
        }

        // 4) 取正文
        val reply = c.engine.messages.value
            .lastOrNull { it.role == Role.ASSISTANT && it.content.isNotBlank() }
            ?.content.orEmpty()
        if (reply.isBlank()) {
            Logx.i(TAG, "这一轮没有产出正文")
            return
        }

        // 沉默是合法结果：她看过了，这次不想接
        if (FishRhythm.isSilent(reply)) {
            Logx.i(TAG, "她选择不说话")
            return
        }

        // 记忆压缩和界面里那一轮走同一个策略：一轮一次
        runCatching {
            val fishProvider = settings.providers.firstOrNull { it.id == settings.fishProviderId }
                ?: settings.activeProvider
            c.memoryCompressor.maybeCompress(
                conversationId = conv.id,
                cardId = card.id,
                scene = Scenes.FISH,
                contextWindow = fishProvider?.contextWindow ?: 0,
                providerId = settings.fishProviderId,
                model = settings.fishModel,
            )
        }

        // 今天要是还没有动态，顺手写一条 —— 她刚说完话，正好有话说
        runCatching { FishMomentWriter.writeIfDue(context) }

        if (!AppVisibility.visible) {
            FishNotifications.notifyMessage(
                context = context,
                title = card.displayName,
                body = FishProactive.preview(reply),
            )
        }
    }

    /**
     * 找到属于养鱼的那条会话。
     *
     * 注意顺序：**先看引擎当前会话**。用户可能正开着某一天的养鱼记录
     * 在看，后台这一轮应当接在那条后面，而不是跳到"最近一条"。
     *
     * ## 为什么不再"没有就新开一条"
     *
     * 以前这里会 `startNew`。于是「用户点进养鱼页看了一眼、一句话没说」之后，
     * 每天四个点各新开一轮生成 —— 屏幕上突然冒出一个自己没聊过的对话，
     * 还按次扣费。养活人这件事得先有人，一句都没说过就没有"她主动找你"可言。
     * 现在只有**已经聊过**（至少一条用户消息）才继续。
     */
    private suspend fun ensureConversation(
        c: com.dshwatch.core.AppContainer,
        cardId: String,
    ): com.dshwatch.domain.model.Conversation? {
        val current = c.engine.conversation.value
        if (current != null && Scenes.of(current.scene) == Scenes.FISH) {
            return current.takeIf { c.engine.messages.value.any { m -> m.role == Role.USER } }
        }

        val latest = runCatching { c.conversationStore.latestFor(Scenes.FISH, cardId) }.getOrNull()
            ?: return null
        runCatching { c.engine.open(latest.id) }
        val opened = c.engine.conversation.value ?: return null
        // 打开之后再看一眼有没有说过话：一条都没说的空会话同样不该被打扰
        return opened.takeIf { c.engine.messages.value.any { m -> m.role == Role.USER } }
    }

    /** 到点先把下一次排上，避免这一轮抛异常之后再也不会响 */
    suspend fun reschedule(context: Context) {
        val app = context.applicationContext as? com.dshwatch.DshWatchApp ?: return
        val settings = app.container.settingsRepo.current()
        val plans = runCatching { app.container.proactiveStore.pending().map { it.triggerAt } }
            .getOrDefault(emptyList())
        runCatching { FishProactiveSchedule.sync(context, settings, plans) }
            .onFailure { Logx.w(TAG, "重排失败：${it.message}") }
    }
}

/**
 * 主动消息的通知。
 *
 * 单独一个 channel，不让用户把「她来找我」和「正在生成」混成一组 ——
 * 前者是要读的消息，后者是进度。重要级别高：这类通知的全部意义就是
 * 把人叫来看一眼。
 */
object FishNotifications {

    private const val TAG = "FishNotify"
    const val CHANNEL_ID = "dsh_fish_proactive"

    /** 点通知时带给 MainActivity 的开关：落到养鱼对话 */
    const val EXTRA_OPEN_FISH = "com.dshwatch.extra.OPEN_FISH"

    fun notifyMessage(context: Context, title: String, body: String) {
        val manager = context.getSystemService<NotificationManager>() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "主动消息",
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = "她主动来找你的时候" },
                )
            }
        }

        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_FISH, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        runCatching { manager.notify(NOTIFY_ID, notification) }
            .onFailure { Logx.w(TAG, "发通知失败：${it.message}") }
    }

    private const val NOTIFY_ID = 0x7A31
    private const val REQUEST_OPEN = 0x7A32
}

/**
 * App 是否正显示在屏幕上。
 *
 * 只有一个用途：她主动说话时，人就在页面上看着，就别再弹一条通知。
 * 用 MainActivity 的 onResume/onPause 维护，不引 lifecycle-process ——
 * 这个项目只有一个 Activity，为一行布尔值加一个依赖不划算。
 */
object AppVisibility {
    @Volatile
    var visible: Boolean = false
}
