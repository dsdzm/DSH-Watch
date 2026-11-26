# AI 主动消息（陪伴模式）实现路径

> 目标：AI 依据**长期记忆**与**最近发生的事**，在用户没说话的时候主动发来一条消息。
> 默认角色「大肥鱼」，可切换。用户回复后无缝进入正常对话。

---

## 一、结论先给

| 判断 | 内容 |
|---|---|
| 最省事的落地方式 | **本地定时（AlarmManager）+ 本地生成 + 通知触达**，不需要服务器 |
| 可靠性上限 | 受 Android Doze 与厂商后台策略限制，**不能承诺分钟级准时**，只能承诺「大致在这个时段会来」 |
| 服务端要不要 | P2 阶段再上，且**只下发「该说话了」的触发信号**，内容仍在本地生成 |
| 为什么不做服务端生成 | 国内无 GMS，FCM 不可用；自建推送成本高；记忆出设备有隐私风险；模型调用成本转嫁给自己 |
| 默认角色 | 复用项目已有的「吃白饭的摸鱼大肥鱼」人设（`domain/bili/BiliTones.kt`），改写成陪伴语域 |
| 工期 | P0 链路验证 0.5–1 天；P1 可用的定时版 2–3 天；P2 记忆/事件决策 3–5 天 |

---

## 二、现有能力盘点

| 能力 | 代码位置 | 能否直接复用 |
|---|---|---|
| 精确闹钟 + 开机重排 | `domain/bili/BiliSchedule.kt`、`service/BiliDailyReceiver` | ✅ 照抄这套模式，注释里已论证「手表上 WorkManager 会被 Doze 推得没边」 |
| 高优先级通知 + 振动 | `service/AlarmReceiver.kt` | ✅ 渠道、图标、PendingIntent 全有，改文案即可 |
| 前台服务保活 | `service/StreamingService`、`service/QqService` | ✅ 生成期间挂一次前台服务，避免被冻结 |
| 进程级容器 | `DshWatchApp.container`（`lateinit var`，public） | ✅ 后台服务直接取 `container`，不用重新构造 |
| 长期记忆（markdown 分段） | `data/store/MemoryStore.kt`（`read` / `append` / `replace`） | ✅ 按 `## 时间戳` 切段，取最近 N 段进 prompt |
| 记忆自动压缩 | `domain/roleplay/MemoryCompressor.kt`（`maybeCompress` / `compress`） | ✅ 主动消息前后的对话同样会被压缩进记忆 |
| 会话与消息落盘 | `data/store/ConversationStore.kt` | ✅ JSON + 索引，支持 `listFor` / `latestFor` / `save` |
| 模型请求（脱离 UI） | `net/backend/BackendRegistry.streamChat(ChatRequest)` | ✅ 可独立调用，不污染 `ChatEngine._messages` |
| 外部消息灌进对话的范式 | `domain/qq/QqBridge.kt`（`ensureConversation` → `engine.send`） | ✅ 「把外部触发当成一轮对话」的完整参考 |
| 角色卡 | `domain/roleplay/RoleCard.kt`、`RoleCardStore` | ✅ 大肥鱼做成内置卡 |
| 事件源 | `AlarmStore`、`TodoBoard`、`UsageRepository`、B 站工具 | ✅ 已有数据，只差汇总成「情境卡片」 |

**缺口**

1. 没有调度器 / 状态机（什么时候该说、说过几次、用户回没回）
2. 没有「事件摘要」聚合
3. 通知点击进不了指定会话（`MainActivity` 不解析 intent extra）
4. `ChatMessage` 没有「这条是 AI 主动发的」标记
5. `AppSettings` 没有陪伴相关开关

---

## 三、三条路径对比

### 路径 A：纯本地定时（推荐 P0）

```
AlarmManager 到点 → BroadcastReceiver(goAsync)
  → 读记忆 + 最近对话 + 事件 → 组 prompt
  → BackendRegistry.streamChat（非流式收集）
  → 写入 ConversationStore（assistant, proactive=true）
  → 通知 + 振动 → 用户点开直达会话
```

- **优点**：零服务器、零运营成本、记忆不出设备、实现快
- **缺点**：Doze 下延迟（可能晚 15–60 分钟）；国产 ROM 杀后台后不触发；手表独立设备更不稳
- **缓解**：`setExactAndAllowWhileIdle` + 引导用户关闭电池优化 + 开机/联网/充电时补一次「错过补偿」

### 路径 B：服务端生成 + 推送

- 服务端保存记忆摘要与角色配置，定时生成，FCM/厂商推送下发
- **致命问题**：国内无 GMS，FCM 基本不可用；接小米/华为/OPPO/vivo 四家推送要四套 SDK 与资质
- **附带问题**：记忆上云涉及隐私承诺；模型费用由开发者承担
- **结论**：不作为主路径

### 路径 C：长连接触发 + 本地生成（推荐 P2）

```
自建轻量 WS 服务 ←→ App 保活连接（复用 QqBridge 的长连接范式）
服务端到点下发 {"type":"nudge","reason":"evening"}
  → App 本地执行与路径 A 完全相同的生成流程
```

- **优点**：触发可靠（不受 Doze 影响，连接本身由前台服务保活）；内容仍在本机生成，隐私与成本可控
- **缺点**：要一台服务器 + 域名 + 证书；连接保活耗电
- **适合**：用户量起来后作为「付费/高级」能力

### 推荐组合

**P0/P1 走 A，P2 叠加 C 作为可靠触发通道。B 不做。**

---

## 四、推荐架构

```
┌─────────────────────────────────────────────────────┐
│ 触发层                                               │
│  AlarmManager(精确) · 开机 · 充电 · 联网 · 长连接指令 │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│ 决策层  CompanionPlanner                             │
│  时间间隔 · 时段 · 今日次数 · 未回复次数 · 事件权重   │
│  输出：SEND / SKIP / RESCHEDULE(30~120min)           │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│ 素材层  CompanionContext                             │
│  记忆最近 3 段 · 最近 12 条消息 · 事件摘要 · 时间语境 │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│ 生成层  CompanionComposer + BackendRegistry          │
│  人设卡 + 情境卡 + 「只发一条 1~3 句」约束            │
│  非流式收集（后台不需要逐字）                         │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│ 落库层  ConversationStore                            │
│  assistant 消息，proactive=true，status=DONE          │
│  同步更新 index.updatedAt / messageCount              │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│ 触达层  CompanionNotifier                            │
│  通知（角色名 + 消息前 40 字）· 振动 · 点击直达会话    │
└──────────────────┬──────────────────────────────────┘
                   ▼
┌─────────────────────────────────────────────────────┐
│ 反馈层  CompanionState                               │
│  用户回复 → 清 pending → 重置冷却                     │
│  未回复   → 降频 / 冷却 / 换话题                      │
└─────────────────────────────────────────────────────┘
```

---

## 五、模块设计

### 5.1 调度器 `CompanionScheduler`

照 `BiliSchedule` 写，一个 `object` 即可：

```kotlin
object CompanionScheduler {
    const val ACTION_NUDGE = "com.dshwatch.action.COMPANION_NUDGE"
    private const val REQUEST = 0x8421

    fun sync(context: Context, settings: AppSettings)   // 开关/频率变化时调用
    fun scheduleNext(context: Context, after: Long)     // 每次触发后重排下一次
    fun cancel(context: Context)
}
```

要点（全是 `BiliSchedule` 踩过的坑）：

- **不用 `setRepeating`**：Doze 下会被批量推迟，且改时间后无法精确重排。每次触发时排下一次。
- **没有精确闹钟权限就退化**：`canScheduleExactAlarms()` 为 false 时用 `setAndAllowWhileIdle`，晚几分钟可以接受，崩不行。
- **`scheduledAt` 去重**：设置页每改一个字就重排会打乱节奏，用一个 `@Volatile var` 记住上次排的时间点。
- **开机补排**：`BiliDailyReceiver` 已经监听 `BOOT_COMPLETED`，把新 receiver 一起注册上。
- **错过补偿**：进程启动时比对 `lastNudgeAt`，若已超过应有间隔的 2 倍，随机在 1–10 分钟内补一次（避免「一开机就弹」的机械感）。

### 5.2 决策器 `CompanionPlanner`

核心不是「到点就发」，而是打分。**这是决定体验好坏的地方。**

| 维度 | 规则 | 权重 |
|---|---|---|
| 静默时长 | 距用户最后一条消息 < 90 分钟 → 直接 SKIP | 硬门槛 |
| 时段 | 23:30–07:30 静默；07:30–09:00、21:00–23:00 加权 | +2 / -3 |
| 今日已发 | ≥ 上限（默认 3）→ SKIP | 硬门槛 |
| 连续未回复 | 1 条 → -2；2 条 → 冷却 12h；3 条 → 冷却 24h | 递减 |
| 事件命中 | 闹钟刚响、待办完成、日程临近、B 站新动态 | +2 ~ +4 |
| 记忆命中 | 记忆里有「明天/下周」等未来指向 | +2 |
| 随机性 | ±1 抖动，避免每天同一分钟 | — |

输出三个动作：

- `SEND`：立即生成
- `SKIP`：不发，排下一次（默认 3–5 小时后）
- `RESCHEDULE(delay)`：条件不成熟但快了（如用户刚放下手机 10 分钟），30–120 分钟后重试

> 产品经验：**固定时间发 = 闹钟**，用户第三天就会烦。间隔要有 20% 左右的随机浮动。

### 5.3 素材层 `CompanionContext`

```kotlin
data class CompanionContext(
    val characterName: String,
    val memoryTail: String,        // MemoryStore.read(cardId) 最后 3 段
    val recentMessages: List<ChatMessage>, // 最后 12 条，只取 role + 前 120 字
    val events: List<String>,      // 已发生/即将发生的事，每条一句
    val now: Long,
    val silenceMinutes: Long,
    val timeOfDay: String,         // 清晨/上午/午后/傍晚/深夜
)
```

**记忆怎么取**：`MemoryStore` 的文件是按 `## 时间戳` 分段的，按 `\n## ` 切分后取最后 3 段，总长控制在 1200 字以内。不要整篇塞进去——记忆会越滚越长，早晚撑爆上下文。

**事件从哪来**（全部是现成数据）：

| 事件 | 来源 | 例子 |
|---|---|---|
| 闹钟刚响 | `AlarmStore` + `AlarmReceiver` 落一条 `lastFiredAt` | 「你设的 7:30 闹钟刚响过」 |
| 待办变化 | `TodoBoard` | 「今天的待办还剩 2 条没勾」 |
| 日程临近 | 日历（需 `READ_CALENDAR`，可选） | 「10 点有个会」 |
| 长时间使用 | `UsageRepository` | 「你今天已经用了 3 小时」 |
| B 站动态 | 现有 B 站工具 | 「你关注的 UP 主更新了」 |
| 设备状态 | `BatteryManager` / 充电监听 | 「电量只剩 15% 了」 |

### 5.4 生成层 `CompanionComposer`

**Prompt 模板（可直接用）**

```
你是「{角色名}」。{人设正文}

【你们的关系】
{关系设定，来自角色卡 persona 的第二段}

【你记得的事】
{memoryTail}

【最近聊过】
{recentMessages 摘要}

【眼下】
现在是 {时间}，距离你们上次说话过去了 {silence}。
{events 逐条}

【这次的任务】
你要主动发一条消息。规则：
1. 只发一条，1~3 句，不超过 60 字；
2. 必须具体——提到你记得的某件事，或眼下发生的某件事；
3. 不要问「在吗」「忙不忙」这种空话；
4. 不要复述上面的清单，要像真的想起来才说的；
5. 不要用括号写动作描写，不要加引号。
```

**调用方式**：`BackendRegistry.streamChat(ChatRequest)`，收集完整文本后一次落库。后台不需要流式（省电、省重连）。

**失败降级**：

- 无网络 / 无供应商 → 不发通知，只排下一次（不要弹「生成失败」，用户没主动要，弹了就是打扰）
- 超时（> 30s）→ 放弃本次
- 生成内容为空或过长（> 200 字）→ 截断或丢弃

### 5.5 落库

```kotlin
// ChatMessage 新增字段
val proactive: Boolean = false,
```

写入流程：

1. `conversationStore.latestFor(Scenes.TAVERN, cardId)`，没有就创建
2. 追加 `ChatMessage(role = ASSISTANT, proactive = true, status = DONE)`
3. 更新索引里的 `updatedAt` / `messageCount`
4. 不用 `ChatEngine`，避免后台生成顶掉用户正在看的页面

> 界面上对 `proactive` 消息可以加一个小标记（如左侧一条竖线），让用户知道「这是它自己找我的」。不加也不影响功能。

### 5.6 触达 `CompanionNotifier`

- 渠道：新建 `dsh_companion`，`IMPORTANCE_HIGH`（要能亮屏/振动），与闹钟渠道分开——用户想关掉陪伴消息时不该连闹钟一起关
- 标题：角色名；正文：消息前 40 字
- 点击：`PendingIntent` 带 `conversationId` + `cardId`
- 振动：短促两下（`longArrayOf(0, 120, 80, 120)`），不要用闹钟那种长振

**跳转需要改 `MainActivity`**：目前 `onCreate` 不读 intent extra。加：

```kotlin
val targetConv = intent?.getStringExtra("conversationId")
val targetCard = intent?.getStringExtra("cardId")
```

再交给 `AppRoot` / `WatchNavigator` 做首次路由。注意 `onNewIntent` 也要处理（App 在后台时点通知走这条）。

### 5.7 状态机 `CompanionState`

```
IDLE ──触发──> GENERATING ──成功──> WAITING_REPLY
                    │                    │
                    │失败                 │用户回复
                    ▼                    ▼
                 IDLE                 IDLE（重置计数）
```

持久化（`filesDir/companion/state.json`）：

```json
{
  "lastNudgeAt": 0,
  "todayCount": 0,
  "todayDate": "2026-11-22",
  "pendingSince": 0,
  "unanswered": 0,
  "cooldownUntil": 0
}
```

规则：

- 用户在任何会话里发消息 → `unanswered = 0`、`pendingSince = 0`
- 用户点开通知但没回复 → 不算回复，但 `unanswered` 不增加
- `unanswered >= 2` → 冷却 12h；`>= 3` → 24h 且当天不再发

---

## 六、数据模型改动

### `AppSettings` 新增（`domain/model/Provider.kt` 里那个 `AppSettings`）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `companionEnabled` | Boolean | false | 总开关，默认关（必须用户主动开） |
| `companionCardId` | String | "" | 当前角色卡，空则用内置大肥鱼 |
| `companionPerDay` | Int | 3 | 每日上限 |
| `companionMinGapMin` | Int | 180 | 两次最小间隔（分钟） |
| `companionQuietStart` | Int | 23 | 静默开始（小时） |
| `companionQuietEnd` | Int | 8 | 静默结束（小时） |
| `companionUseEvents` | Boolean | true | 是否允许用事件（闹钟/待办/电量） |
| `companionNotify` | Boolean | true | 是否发系统通知（关掉则只在应用内出现小红点） |

### `ChatMessage` 新增

| 字段 | 类型 | 默认 |
|---|---|---|
| `proactive` | Boolean | false |

老会话 JSON 反序列化不受影响（有默认值）。

---

## 七、默认角色「大肥鱼」

项目里已经有现成人设：`domain/bili/BiliTones.kt` 的 `fish` 预设——
「你是一条吃白饭的摸鱼的大肥鱼：日常躺平、蹭饭、动不动就说自己在摸鱼」。

做陪伴角色时**不要直接复用 B 站评论那套**（那是对外发言、带攻击性），要改写成「住在你手表里的一条鱼」：

```kotlin
RoleCard(
    id = "builtin_bigfatfish",
    kind = Scenes.TAVERN,
    name = "大肥鱼",
    tagline = "住在你手表里、吃白饭的摸鱼鱼",
    persona = """
        你是一条大肥鱼，圆滚滚的，住在用户的手表里。
        你懒，爱躺平，动不动就说自己在摸鱼；嘴上有时候欠，但心软。
        你不装可爱，不说「主人」这种称呼，像一个熟得太久的朋友。
        你说话短，一句到三句，口语，不用书面语，不加括号动作描写。
        你会记得用户之前说过的事，并且会拿它来搭话。
        用户难过的时候你不讲大道理，先陪着，再说别的。
    """.trimIndent(),
    opening = "……嗯？你终于看我了。",
    builtin = true,
)
```

首次开启陪伴时自动建卡并设为 `companionCardId`，用户可以在酒馆里编辑它（人物设定本来就是可编辑的）。

---

## 八、分阶段落地

### P0 · 链路验证（0.5–1 天）

**只做一件事：证明「能生成一条主动消息并落库」。**

1. `ChatMessage` 加 `proactive`
2. `CompanionRunner.runOnce(cardId)`：读记忆 → 组 prompt → `streamChat` → 落库
3. 设置页加一个「立即试一条」按钮（调试入口，正式版可隐藏）
4. 不做定时、不做通知

**验收**：点按钮后，酒馆会话里多出一条大肥鱼的话，内容引用了记忆里的真实事情。

### P1 · 定时 + 通知（2–3 天）

1. `CompanionScheduler` + `CompanionNudgeReceiver`（照 `BiliSchedule`）
2. `CompanionNotifier` + 通知渠道
3. `MainActivity` / `onNewIntent` 支持直达会话
4. 设置页：开关、每日上限、最小间隔、静默时段
5. 生成期间挂 `StreamingService` 保活

**验收**：开启后 24 小时内收到 2–3 条，点通知能进对应会话；深夜不发。

### P2 · 记忆与事件（3–5 天）

1. `CompanionContext` + 事件聚合
2. `CompanionPlanner` 打分与随机抖动
3. `CompanionState` 未回复冷却
4. 会话页对 `proactive` 消息做视觉区分
5. 增加「不想聊这个」的快捷反馈（点一下 → 记进记忆，降低该话题权重）

**验收**：连续 3 天，主动消息不重复、不空洞，用户回复率 > 30%。

### P3 · 可选增强

- 长连接触发（路径 C），解决 Doze 延迟
- 多角色轮换 / 角色主动发图（复用内置大肥鱼表情包）
- 主动消息引用图片（`MediaRef`）
- 「陪伴报告」：这周它想起你几次、你回了什么

---

## 九、难点与规避

| 难点 | 现实情况 | 规避 |
|---|---|---|
| **后台被杀** | 国产 ROM 默认限制后台，手表更狠 | 引导关闭电池优化（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 已声明）；开机/充电/联网补排；接受「可能不发」并降级到下次打开 App 时补 |
| **Doze 延迟** | `setExactAndAllowWhileIdle` 仍可能晚几分钟到几十分钟 | 产品文案不要说「每天 8:00 准时」；用「早上/下午/晚上」的模糊承诺 |
| **手表独立设备** | Wear OS 后台限制比手机更严 | 优先依赖手机端生成 + 通知镜像；手表端保留「打开时补一条」的兜底 |
| **模型成本** | 单次输入约 1200–1800 token（含记忆），输出约 80 token | 按 DeepSeek 价格约 0.002 元/次；一天 3 次 ≈ 0.2 元/月，可接受。记忆只取最后 3 段控制长度 |
| **打扰** | 用户第三天开始烦 | 默认每天 ≤3 条、随机间隔、深夜静默、未回复自动降频；通知渠道独立便于关闭 |
| **隐私** | 记忆是用户隐私 | 全程本地，不上传；设置页明确写「记忆只存在这台设备上」 |
| **内容重复** | 每天「今天过得怎么样」 | 决策层记录最近 7 天的话题标签，生成时禁用重复标签 |
| **失败无感知** | 后台生成失败用户不知道 | 静默失败，不弹错误；连续失败 3 次降级为「只在打开 App 时提示」 |

---

## 十、验收标准

| 指标 | 目标 |
|---|---|
| 主动消息触发率 | 开启后 7 天内，日均 ≥1 条且 ≤3 条 |
| 内容具体度 | 人工抽检 20 条，≥80% 提到记忆或事件中的具体信息 |
| 回复率 | ≥30% 的主动消息收到用户回复 |
| 关闭率 | 开启 7 天内关闭比例 <20% |
| 耗电 | 24 小时待机额外耗电 <2% |
| 崩溃 | 后台链路零崩溃（`CrashLog` 无新增） |

---

## 十一、待你定的三件事

1. **默认频率**：我建议每天 2–3 条、最小间隔 3 小时、23:00–08:00 静默。要不要更黏一点（每天 5 条）？
2. **要不要 P2 的长连接**：如果目标用户主要是手表独立设备，Doze 会让纯本地方案很不可靠，建议早点上。
3. **是否多角色**：先只做大肥鱼，还是开放「任意酒馆角色都能主动」？
