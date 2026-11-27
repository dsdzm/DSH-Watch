package com.dshwatch.ui.screens.fish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.fish.FishMoment
import com.dshwatch.domain.fish.FishMoments
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.domain.roleplay.BuiltinCards
import com.dshwatch.service.FishMomentWriter
import com.dshwatch.ui.components.SettingCard
import com.dshwatch.ui.components.SettingRow
import com.dshwatch.ui.components.WatchScaffold
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.wear.wearVerticalScroll
import kotlinx.coroutines.launch

/**
 * 她的动态。
 *
 * ## 这是什么，不是什么
 *
 * 每天一条，她自己写：今天发生了什么、她怎么想。第一人称、有语气、不衰减 ——
 * 和「记忆」是两份东西（见 [com.dshwatch.domain.fish.FishMoment] 的注释）。
 *
 * ## 为什么进来会自动写一条
 *
 * 动态的素材是**当天聊过的话**。定时任务在凌晨写，写出来只能是空话；
 * 用户打开这一页时，今天该发生的多半已经发生了。所以"进来"本身就是触发条件，
 * 一天只补一次，写过就不再动（[FishMoments.AUTO_PER_DAY]）。
 *
 * ## 为什么给一个手动按钮
 *
 * 自动那次可能因为没网、模型抽风而失败，用户看到的是空白 —— 分不清
 * "她今天没话说"和"坏了"。一个「让她现在写一条」既是重试，也是参与感：
 * 点一下，她当着你面写。
 */
@Composable
fun FishMomentsScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val card = remember { BuiltinCards.feiyu() }

    var moments by remember { mutableStateOf<List<FishMoment>>(emptyList()) }
    var writing by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    suspend fun reload() {
        moments = runCatching { container.momentStore.all() }.getOrDefault(emptyList())
        loaded = true
    }

    LaunchedEffect(Unit) {
        reload()
        // 打开这一页就是一次"今天该写了"的检查；已经写过就立刻返回
        runCatching { FishMomentWriter.writeIfDue(context) }
        reload()
    }

    fun writeNow() {
        if (writing) return
        writing = true
        note = ""
        scope.launch {
            val m = runCatching { FishMomentWriter.writeIfDue(context, force = true) }.getOrNull()
            note = when {
                m != null -> ""
                // 一天最多三条，第 4 次点会什么也不发生 —— 说清楚，别让人以为坏了
                container.momentStore.countOn(System.currentTimeMillis()) >= FishMoments.MAX_PER_DAY ->
                    "今天写得够多了，明天再来"

                else -> "这次没写出来，可能是网络或模型的问题"
            }
            reload()
            writing = false
        }
    }

    WatchScaffold(title = "她的动态", onBack = { nav.pop() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wearVerticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            if (loaded && moments.isEmpty()) {
                SettingCard {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = "她还没写过什么。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "等今天聊过几句，她会自己记一条。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            /* 按天分组。列表本身是倒序的，组内顺序保持"最新在前" */
            moments.groupBy { FishMoments.dayKey(it.at) }.forEach { (_, list) ->
                val day = list.first()
                Text(
                    text = dayLabel(day.at),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                )
                list.forEach { m -> MomentCard(m) }
            }

            SettingCard(modifier = Modifier.padding(top = 10.dp)) {
                SettingRow(
                    title = if (writing) "她正在写…" else "让她现在写一条",
                    subtitle = if (note.isNotBlank()) note else "把今天的事记下来，一天最多三条",
                    trailing = if (writing) "" else "写 ›",
                    onClick = { if (!writing) writeNow() },
                )
                SettingRow(
                    title = "她记得的事",
                    subtitle = "和这里的动态不是同一份",
                    trailing = "›",
                    onClick = { nav.push(Screen.MemoryDoc(card.id)) },
                )
            }
        }
    }
}

@Composable
private fun MomentCard(m: FishMoment) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = FishMoments.stamp(m.at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (m.mood.isNotBlank()) {
                Text(
                    text = m.mood,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        SettingCard(modifier = Modifier.padding(top = 2.dp)) {
            Text(
                text = m.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

/** 今天 / 昨天 / 11月20日 */
private fun dayLabel(at: Long): String {
    val now = System.currentTimeMillis()
    val key = FishMoments.dayKey(at)
    return when (key) {
        FishMoments.dayKey(now) -> "今天"
        FishMoments.dayKey(FishMoments.startOfDay(now) - 1) -> "昨天"
        else -> FishMoments.dayKey(at).split("-").let { parts ->
            if (parts.size == 3) "${parts[1].toInt()}月${parts[2].toInt()}日" else key
        }
    }
}
