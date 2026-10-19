# v1.6 改动与技术实现详解

> 记录 **DSH 肥鱼 v1.6（versionCode 14）** 相对 v1.5 的全部改动：根因、实现机制、取舍与验证。

| 项目 | 值 |
|---|---|
| 版本 | v1.6 / versionCode 14（v1.5 = 13） |
| 改动规模 | 11 个文件（2 个新增类 + 9 处修改），约 +700 / −50 行 |
| 测试 | 551 个单元测试，0 失败（新增 29 条） |
| 产物 | `v1.6-lite.apk`（6.4MB）、`v1.6-full.apk`（27MB） |
| 签名 | SHA-256 `70437a7f…bf31f94`（与 v1.5 同证书，可覆盖安装） |

本版解决四件互不相干、但都让人用不下去的事：**上下文被砍**、**确认弹窗太密**、**联网工具不能自定义请求头**、**Agent 干完活不留痕**。

---

## 1. 上下文截断：全文落盘 + 摘要回灌

### 1.1 问题

改动前，单个工具结果超过 4000 字会被 `TextClip.headTail()` 砍成「头 + 尾」，**中间直接丢弃**。真机上的表现：

```
模型：read_file(AdbFileTools.kt)  → 拿到头尾，中间没了
模型：再读一遍                    → 又是头尾，中间还是没了
用户：读了两次还没看到我要的那段，上下文还满了
```

模型拿不到中间内容，只能反复重读 —— 每次重读又往上下文里灌一大段，形成恶性循环。

### 1.2 做法

新增 `domain/agent/ToolResultCompactor.kt`：

1. 超过 `THRESHOLD`（4000 字）的结果，**整份写入** `notes/tool-output/<工具>-<内容指纹>.txt`；
2. 回灌给模型的文本 = 头 2000 字 + 尾 1000 字 + 路径 + 查法，约 3.2KB，与原始长度无关。

```kotlin
suspend fun compact(toolName: String, content: String, ctx: ToolContext): Outcome {
    if (content.length <= THRESHOLD) return Outcome(text = content, originalChars = content.length)
    val rel = "notes/tool-output/${safeName(toolName)}-${hashOf(content)}.txt"
    val global = ctx.scope(rel)
    val saved = global != null && runCatching { ctx.workspace.write(global, content) }.getOrNull() != null
    if (!saved) return Outcome(text = digest(content, relPath = null), originalChars = content.length)
    return Outcome(text = digest(content, rel), path = rel, originalChars = content.length)
}
```

几个决定性的细节：

| 决定 | 原因 |
|---|---|
| 文件名用**内容 hash** 而不是时间戳 | 同一个文件被反复读只会有**一个**副本；否则工作空间会被同一份内容的几十个文件堆满 |
| 落盘失败**不报错** | 结果本身是有效的，只把摘要降级成「中间已丢失」的文案。存不下就让工具调用失败，是拿次要故障毁掉主要功能 |
| 摘要必须写清「怎么取中间」 | 直接给出 `grep pattern=… path=…` 与 `read_file offset=…` 两条命令。只说「已省略」模型会回去重跑工具 |
| 落盘路径走 `ctx.scope()` | 写进**当前会话目录**，换会话不会互相污染 |

### 1.3 配套改动

- `read_file` 新增 `offset` 参数（字符偏移）。`WorkspaceStore.read` 只支持从头读 N 个字符，这里多读 `offset` 个再 `drop(offset)` —— 单文件上限 256KB，代价可接受。
- 系统提示同步改口径：原来写「输出会截断保留头尾，别指望它替你打印整个大文件」，现在写成「超长输出会整份落盘到 `notes/tool-output/`，要看中间用 grep 或 `read_file offset=…`，**不要重跑命令**」。

### 1.4 裁剪时保留最初的任务

`TokenEstimator.trimToBudget()` 是从后往前装历史的，所以**第一条用户消息总是最先被丢掉**。长任务里的后果不是「忘了刚才那步」，而是「忘了要干什么」。现在新增 `keepFirstUserTurn`（默认开）：裁剪后若首条用户消息不在保留列表里，且预算放得下，就插到最前面。第一条消息通常几十个字，成本可以忽略。

> 注意：裁剪必须把 `[assistant(tool_calls), tool, tool…]` 当作不可分割的原子组，这条规则没有变（详见 `TokenEstimator` 注释）。

---

## 2. 执行确认：按工具分级，而不是按名字一刀切

### 2.1 原来的问题

`ALWAYS_CONFIRM_TOOLS = setOf("run_script", "cloud_build", "cloud_job")` —— 这三个工具**无视「全自动」与「总是允许」**，每次都弹窗。结果：AI 跑一条 `ls` 也要点一次允许。

问题在于这三个工具的性质完全不同：

| 工具 | 风险 |
|---|---|
| `run_script` + shell | 一条命令就能改/删设备上的东西 |
| `run_script` + js / python | 跑在受控执行器里，有超时与参数边界 |
| `cloud_build` / `cloud_job` | 会把代码发到外部仓库、耗时长、不可逆 |

把「shell」和「js」塞在同一个 `run_script` 名下，就只能在「全都问」和「全都不问」之间二选一。

### 2.2 现在的规则

新增 `domain/agent/ApprovalPolicy.kt`，把策略从引擎里抽出来（安全边界必须能被单测直接钉住）：

```kotlin
fun needsApproval(settings, toolName, safety, args, sessionApproved): Boolean {
    // shell：永远拦，除非本会话已放行
    if (toolName == RUN_SCRIPT && languageOf(args) == "shell") return "run_script:shell" !in sessionApproved
    if (toolName in ALWAYS_CONFIRM) return true            // cloud_build / cloud_job
    if (toolName in settings.autoApprovedTools) return false
    return when (settings.agentMode) {
        OFF, AUTO_ALL -> false
        ASK_EVERY_STEP -> true
        AUTO_READONLY -> safety != ToolSafety.READ_ONLY
    }
}
```

| 工具 | 全自动 | 只读自动 | 每步都问 |
|---|---|---|---|
| shell | **确认**（可「本会话允许」） | 确认 | 确认 |
| js / python | 直接跑 | 确认 | 确认 |
| cloud_build / cloud_job | 确认 | 确认 | 确认 |
| 只读工具 | 直接跑 | 直接跑 | 确认 |

### 2.3 「本会话允许」

新增 `ApprovalDecision.ALLOW_SESSION`：

- 弹窗对 shell 显示**「本会话允许 / 允许一次 / 拒绝」**，不再显示「总是允许」；
- 放行状态存在引擎的内存集合 `sessionApproved` 里，**换新会话即失效**（`startNew()` 里 `clear()`）；
- 非 shell 工具仍是「总是允许 / 允许一次 / 拒绝」。

为什么不给 shell 永久放行：一条 `rm -rf` 就能清空工作空间，用户点一次「总是允许」换来的便利与风险不对等。本会话内放行足够解决「连着跑五条命令点五次」的烦躁。

---

## 3. 联网工具支持自定义请求头

### 3.1 需求来源

`web_search` / `http_fetch` 的请求头此前是写死的（固定 UA + Accept）。遇到需要 `Referer`、`Authorization`、特定 `User-Agent` 或 `Cookie` 的站点，只能返回 403/空白，模型无从下手。

用户提出「参考 DeepSeek 的搜索方式」——DeepSeek 现已提供 `POST /v1/tools/search`（字段含 `query` / `count` / `need_content` / `localization`，响应带 `rerank_score` / `summary` / `date` / `authority`，需 `Authorization: Bearer <key>`）与 `POST /v1/tools/fetch`。本次**不换搜索后端**（用户决定保留现有 DuckDuckGo），但把「自定义请求头」这个能力补齐 —— 它正是接上这类接口的前提。

### 3.2 实现

新增 `net/CustomHeaders.kt`，做**白名单式清洗**而不是照单全收：

```kotlin
object CustomHeaders {
    private val VALID_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
    private val FORBIDDEN = setOf("host", "content-length", "connection", "transfer-encoding", "upgrade", "te", "trailer")
    const val MAX_COUNT = 20
    const val MAX_VALUE_CHARS = 4_096

    fun sanitize(raw: Map<String, String>): Sanitized   // 返回可用头 + 被拒绝的头名
    fun apply(builder: Request.Builder, headers: Map<String, String>)  // removeHeader + header
}
```

拦住的四类：

| 被拦 | 为什么 |
|---|---|
| `Host` | 改了它请求会被发到别的主机（且证书对不上） |
| `Content-Length` / `Transfer-Encoding` / `Connection` / `TE` / `Trailer` | 由 OkHttp 自己算，手写只会制造协议错误 |
| 值里含 `\r` / `\n` | 请求头注入的经典手法 |
| 名称非 RFC 7230 token 字符、超过 20 个、值超 4KB | 防止畸形请求 |

被拒绝的头**不会让整次请求失败**：其余合法的照常生效，结果里附一句「已忽略不合法的请求头：…」并说明原因，模型据此能改正。

### 3.3 参数

```kotlin
"headers" to Schema.strMap("自定义请求头（键值对，值必须是字符串）…")
```

`Schema.obj()` 会把外层对象的 `additionalProperties` 置为 `false`，所以新增了 `Schema.strMap()` 单独声明 `additionalProperties: {"type":"string"}` —— 否则键名不固定这类参数根本传不进来。

`JsonObject.stringMapOf()` 同时兼容两种形态：真 JSON 对象，以及模型常写的「字符串里裹着 JSON」（与 `stringListOf` 的处理一致）。

`apply()` 用 `removeHeader` + `header` 而不是直接 `header()`：后者是追加语义，会出现两个 `User-Agent`，服务端取哪个不确定。

工具层面的应用顺序是「先设默认头，再应用自定义头」，所以默认 UA / Accept 可以被覆盖。

---

## 4. Agent 干完活要留文档

系统提示新增两段：

```
【干活要留文档】
只要这次是「做事」而不是一两句问答（写代码、改配置、成篇查资料、排查问题），
收尾时用 write_file 在工作目录写一份 md 说明，建议 `notes/<主题>.md`，写清五件事：
目标、结论、改了哪些文件、怎么验证、还没做什么。
预计超过 3 步的任务，从开始就维护 `notes/task.md`（目标 / 当前进度 / 下一步），
每完成一步更新它 —— 上下文会被裁剪，这份文档是你跨步骤唯一可靠的记忆。
纯聊天、单次查询、一句话能答完的事**不要**硬写文档，那是浪费用户的时间。
```

设计意图：**文档是跨步骤、跨会话的唯一可靠记忆**。上下文裁剪（§1.4）能保住任务目标，但保不住「已经试过什么、结论是什么」—— 那部分只能落到文件里。同时明确写了「纯聊天不要写」，避免变成形式主义。

---

## 5. 对话页「工作流程」块

### 5.1 原来的问题

Agent 干完一轮活，回答上方会堆成一摞：每个工具一张卡片（`ToolCallCard`），外加一块思维链面板（`ReasoningPanel`）。手表屏幕窄，用户真正想看的结论被这摞卡片推到很下面；而过程中想看「AI 现在干嘛」时，又要在多张卡之间找状态不是 SUCCESS 的那张。

### 5.2 现在的结构

思考与工具调用收进同一个可折叠容器 `AgentWorkBlock`（`ui/screens/chat/ChatScreen.kt`）：

```
┌───────────────────────────────────────┐
│ ● 工作流程              展开工作流程 ⌄ │  ← 标题栏，点击展开/收起
│   正在：写文件 · notes/task.md        │  ← 折叠态唯一的一行状态
├───────────────────────────────────────┤
│ [展开后才显示]                          │
│  思维链面板（若设置里开启）              │
│  工具卡片 × N（每张仍可各自展开看参数/结果）│
└───────────────────────────────────────┘
```

四条规则：

1. **默认折叠** —— 折叠态的一行状态实时回答「AI 现在在做什么」：有工具在跑时显示「正在：<工具中文名> <参数摘要>」，纯思考时显示「思考中」；
2. **完成后自动收回** —— 全部步骤落定（流式结束）时折叠成「N 步 · 已完成 / 有失败」摘要；流式期间用户手动展开盯过程，结束后自动回到摘要态（`LaunchedEffect(message.id, live == null)`）；
3. **展开 = 完整流程** —— 思维链 + 每步工具卡片，顺序即执行顺序；
4. **产物不藏折叠** —— 折叠态下，本轮成功的构建产物（`ToolCallRecord.artifact`）仍常显在块尾，点开即预览、右侧导出，不用先展开工作流。

### 5.3 状态与配色

| 状态 | 状态点颜色 | 状态行 |
|---|---|---|
| 流式中 · 有工具在跑 | 主色 | 正在：<工具> <摘要> |
| 流式中 · 纯思考 | 第三色 | 思考中 |
| 完成 · 全部成功 | 次色 | N 步 · 已完成 |
| 完成 · 有失败/拒绝 | 错误色 | N 步 · 有失败 |

### 5.4 交互细节

- 展开态按**消息 id** 记忆（`remember(message.id)`）：重新生成会新建消息，自然回到默认折叠，不会残留上一轮的展开状态；
- 无工具调用的消息（纯思维链回答）块标题退化为「思维链」，行为与旧版一致；
- 工具卡片自身的展开/收起（看参数与原始输出）保持不变，与工作块的两级折叠互不干扰；
- 新增文案集中在 `AppStrings`：`workflowTitle` / `workflowNow` / `workflowDone` / `workflowFailed` / `workflowExpand` / `workflowCollapse`，中英双语齐全。

---

## 6. 验证

| 项 | 结果 |
|---|---|
| 单元测试 | 551 个，0 失败（新增 29 条） |
| UI 改动 | `AgentWorkBlock` 为纯 Compose 渲染逻辑，无单测覆盖；编译通过、双包构建通过，交互效果需真机验收（默认折叠 / 状态行 / 完成后收回 / 产物常显） |
| 新增测试 | `ToolResultCompactorTest`（4）、`ToolResultCompactorIoTest`（4）、`ApprovalPolicyTest`（12）、`CustomHeadersTest`（7）、`TokenEstimatorTest` 追加（2） |
| 构建 | lite 与 full 均 `BUILD SUCCESSFUL` |
| 包校验 | 两包 `versionCode=14 / versionName=1.6`，签名 `70437a7f…bf31f94`；lite 内 `libpython` 命中 0、full 命中 26 |

其中 `ToolResultCompactorIoTest` 是端到端验证：真的写文件 → 用摘要里给的路径读回来 → 内容与原文完全一致；同一份内容重复落盘只有一个文件；文件落在**当前会话目录**下。

---

## 7. 已知限制与后续

| 限制 | 说明 |
|---|---|
| 搜索仍是 DuckDuckGo lite 页面解析 | 页面结构变了会解析不出结果；若要接 DeepSeek `/v1/tools/search` 或自建 SearXNG，现在的 `headers` 已能带 `Authorization`，缺的是后端可配置项 |
| 落盘文件没有清理机制 | `notes/tool-output/` 会随使用增长，目前靠工作空间容量上限（256KB/文件）兜底 |
| 「本会话允许」只按 `工具:语言` 粒度 | 不区分具体命令；想更细可以改成按命令前缀匹配 |
| 落盘阈值固定 4000 字 | 与上下文窗口大小无关；小窗口模型（如 16K）可能希望更激进 |

---

## 附录：改动索引

| 机制 | 位置 |
|---|---|
| 落盘与摘要 | `domain/agent/ToolResultCompactor.kt` |
| 引擎接入 | `ChatEngine.kt:executeTools`（原 `TextClip.headTail` 调用点） |
| 保留首条任务 | `domain/TokenEstimator.kt:trimToBudget(keepFirstUserTurn)` |
| 审批策略 | `domain/agent/ApprovalPolicy.kt` |
| 会话级放行 | `ChatEngine.kt`：`sessionApproved`、`ALLOW_SESSION`、`startNew()` 清空 |
| 弹窗按钮 | `ui/components/AgentUi.kt:ToolApprovalDialog`、`AppStrings.approveSession` |
| 表头清洗 | `net/CustomHeaders.kt` |
| 表头参数 | `Schema.strMap()`、`JsonObject.stringMapOf()` |
| 工具接入 | `BuiltinTools.HttpFetchTool`、`SystemTools.WebSearchTool` |
| `read_file` 偏移 | `domain/agent/tools/WorkspaceTools.kt:ReadFileTool` |
| 文档规则 | `ChatEngine.kt` 系统提示「【干活要留文档】」 |
| 工作流程块 | `ui/screens/chat/ChatScreen.kt:AgentWorkBlock`（含 `LaunchedEffect(message.id, live == null)` 完成后收回） |
| 工作流程文案 | `AppStrings.workflowTitle/Now/Done/Failed/Expand/Collapse` |
