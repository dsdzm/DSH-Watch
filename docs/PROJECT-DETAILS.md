# DSH 肥鱼 · 技术详解

> 记录「怎么做的」和「为什么这么做」。给接手的人看，也给三个月后的自己看。
> 对应代码：`46af80f`（v1.4 / versionCode 12）· 2026-10-17

---

## 0. 速览

| 项 | 值 |
| --- | --- |
| 形态 | 单 Activity + Compose，手表端 AI 编程助手 |
| 包名 | `com.dshwatch`（debug 变体加 `.debug` 后缀，可与正式版共存） |
| 版本 | 1.4 / 12 |
| SDK | minSdk 28 / targetSdk 34 / compileSdk 35 |
| 工具链 | AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.11.1 |
| 主要依赖 | OkHttp 4.12、Compose BOM 2024.10、kotlinx.serialization、DataStore、Rhino 1.9.1、（可选）Chaquopy 17.1.0 |
| 规模 | 113 个 Kotlin 文件 / 24.5k 行；44 个测试文件 / 522 个用例 |
| 体积 | lite 6.4 MB（无 Python）· full 27 MB（含 CPython 3.11，仅 arm64-v8a） |

**两个包的区别只在 Python**。`-PwithPython=true` 时才 apply Chaquopy，其余代码完全一致；
签名相同，lite 与 full 可互相覆盖安装。

---

## 1. 架构总览

```
ui/           Compose 界面（chat / sessions / settings / workspace / usage / provider / pair / diagnostics）
   │  StateFlow + ViewModel
domain/       ChatEngine（对话与 Agent 循环）、agent/tools（31 个工具）、
   │          agent/exec（本机执行）、agent/cloud（GitHub Actions）、agent/mcp、agent/build（代码解析/校验）、plugin
data/         SettingsRepository（DataStore）、ConversationStore（JSON）、WorkspaceStore、SecureKeyStore、UsageStore
net/          ChatBackend 抽象 + OpenAiCompatBackend / DshServerBackend / IoBackend、SseReader、TurnSanitizer
service/      前台服务（生成期间防断流 + 通知栏停止）
core/         AppContainer（手写 DI）、Logx、Jsonx、Ids
```

没有 Hilt、没有 Room。理由很实际：手表内存小、启动要快，手写 `AppContainer` 的依赖关系
一眼能看完，也不需要在 KSP 上多花几十秒。持久化全走文件（JSON）与 DataStore。

**数据流**：输入框 → `ChatEngine.send` → 组装 messages（系统提示 + 会话历史 + 工具结果）
→ `ChatBackend.stream()` → 逐 token 更新 UI → 若有 `tool_calls` 则进入工具循环
→ 结果回灌 → 再请求，直到模型给出纯文本回答或判定停滞。

---

## 2. 对话引擎（ChatEngine）

### 2.1 请求与流式解析

`OpenAiCompatBackend` 用 OkHttp 发 `POST /chat/completions`，`stream=true`。
`SseReader` 只做一件事：按 SSE 规范切 `data:` 行，遇到 `[DONE]` 结束。
每个 chunk 解析出四类增量：

- `choices[0].delta.content` → 正文
- `delta.reasoning_content`（DeepSeek）或 `reasoning` → 思维链，单独折叠展示
- `usage`（流末尾，部分服务商在中间也给）→ token 统计
- `delta.tool_calls` → 按 `index` 聚合，`arguments` 是**分片字符串**，必须拼完再解析

坑：有的中转站把 `tool_calls.function.arguments` 写成对象而不是字符串，
所以解析前先判类型，对象就直接 `toString()`。

### 2.2 UI 节流

手表上每个 token 都触发重组会掉帧。`streamThrottleOverrideMs` 默认按性能档位给值
（通常 40–60ms），在协程里合并更新，停止生成时强制 flush 一次。

### 2.3 停止与保活

- 停止：取消协程 + 关闭 SSE 连接；已收到的内容保留，标记为「已停止」。
- 保活：`foregroundService` 开着时，生成期间起前台服务，通知栏带「停止」按钮，熄屏继续跑。

### 2.4 历史与上下文

`TurnSanitizer` 在每次请求前清理历史：丢掉孤立的 `tool` 消息（没有对应 `tool_call_id`）、
合并连续同角色消息、把超长工具结果截断到 `execMaxOutputChars`。
这是为了让「本地模型 / 严格实现」也能接受这段历史 —— 很多服务端会直接 400。

### 2.5 Token 统计

优先用服务端返回的 `usage`；缺失时按字符估算并打 `~` 标记。
按 provider / model / 日期聚合，配合单价算花费（`priceInPerMillion` / `priceOutPerMillion`）。

---

## 3. Agent 循环

### 3.1 为什么不用固定步数

早期实现是「最多 20 步」。问题很明显：一次要读 8 个文件、改 5 个文件的重构，20 步不够；
而模型卡住时，20 步全是重复调用，白烧 20 轮 token。

现在改成 **进展指纹**（`domain/agent/ProgressTracker.kt`）：

- 每步给每次**成功**调用算指纹：`工具名 + 规范化参数`，有产物再加 `artifact:路径`
- 指纹进一个 `HashSet`，只有新指纹才算「有进展」
- **连续 3 步无新指纹** → 判定停滞
- **连续 2 步全部失败** → 判定停滞（失败不进指纹，但同样卡住）
- 兜底 `HARD_STEP_CEILING = 200`，只防病态死循环

这样「逐步写 5 个不同文件」不会被误杀，而「同一个 grep 参数反复跑」三步就停。

### 3.2 工具审批

`AgentMode` 四档：

| 模式 | 行为 |
| --- | --- |
| `OFF` | 不给模型任何工具，退化成普通对话 |
| `ASK_EVERY_STEP` | 每次调用都弹确认 |
| `AUTO_READONLY` | 只读工具直接跑，写操作与联网询问 |
| `AUTO_ALL` | 全自动（`autoApprovedTools` 可单独放行） |

工具的 `safety` 字段（`ToolSafety.READ_ONLY / WRITE / NETWORK / DEVICE`）决定档位判断。
被拒的调用会回灌给模型一次（让它知道「用户不同意，换个办法」），不算失败步。

### 3.3 系统提示

每轮按**当前实际可用**的工具面生成清单。这里踩过坑：风神插件首轮会收窄工具面，
但系统提示还在列全部工具，模型就会去调不存在的工具，然后说「没有 cloud_build」。
现在收窄时明确写「下一轮放开」，且清单只列本轮真实可用的。

---

## 4. 工具系统（31 个）

`AgentTool` 接口：`name` / `description` / `safety` / `parameters`（JSON Schema）/ `execute()`。
`ToolRegistry` 负责注册、按名字分发、统一异常包装（工具内部抛异常 → `ToolResult.fail`，不炸整个循环）。

| 分类 | 工具 |
| --- | --- |
| 文件（8） | `list_files` `read_file` `write_file` `append_file` `edit_file` `make_dir` `move_file` `delete_file` |
| 检索（2） | `glob` `grep` |
| 执行（1） | `run_script`（shell / js / python 三选一） |
| 云端（2） | `cloud_build` `cloud_job` |
| 构建（1） | `build_project`（本地写多文件项目 + 静态校验 + 生成 HTML/单页） |
| 网络（2） | `http_fetch` `web_search` |
| 设备（8） | `get_datetime` `get_device_info` `get_token_usage` `set_alarm` `list_alarms` `cancel_alarm` `create_event` `vibrate` |
| 交互（3） | `ask_user` `todo_write` `open_file` |
| 其他（4） | `calculate` `unit_convert` `get_clipboard` `open_screen` |

设计原则：

- **文件工具全是相对路径**，基准是会话工作目录，`..` 越界直接拒绝。
- `write_file` 单次建议 ≤200 行（系统提示里写了），长文件分多次 `append_file`。
  手表内存有限，一次写几 MB 容易 OOM。
- `run_script` 的 `language` 由文件后缀或显式参数决定；输出走「头尾双缓冲」，
  中间省略并标注省略了多少行 —— 手表屏幕装不下几千行日志。

---

## 5. 本机执行（domain/agent/exec）

三个引擎，共同约束：超时可配（默认 20s）、输出上限 20k 字符、工作目录锁定在会话目录。

### 5.1 ShellRunner

`ProcessBuilder("/system/bin/sh", "-c", cmd)`，但**不用 `redirectErrorStream` + 管道读**，
而是把 stdout/stderr 重定向到临时文件，结束后读文件。

原因：读管道时如果孙进程还活着，`close()` 会等锁，JVM 线程直接卡死；
`destroyForcibly()` 杀不掉进程组。改成文件重定向后，孤儿进程继续写它的文件，
我们只读已写入的部分，超时立刻返回。

超时后 `destroy()` + `destroyForcibly()`，并在结果里注明「已终止（超时）」。
退出码在超时情况下是 `-1`，和「命令返回 -1」区分开。

### 5.2 JsRunner（Rhino）

- `Context` 关掉 `ClassShutter` 之外的一切 Java 访问（默认禁 `Packages`、`java.*`）
- `optimizationLevel = -1`（解释模式）。这是**超时能生效的前提**：
  编译模式下执行的是生成字节码，指令观察器收不到逐条回调，`observeInstructionCount` 永远不会触发
- 用 `ContextFactory` 的指令观察者按「每 N 条指令」检查一次 deadline，超时抛 `Error`
- 测试里断言「超时后线程确实已结束」，避免只是返回了错误、线程还在后台跑

### 5.3 Python（可选）

Chaquopy 17.1.0（MIT）。`-PwithPython=true` 才 apply，插件在 `app/build.gradle.kts` 顶部条件加载。
好处：lite 包不用背 20 MB 的 CPython。

调用链：`PythonBridge` → 反射找 `com.chaquo.python.Python`（不存在就明确告知「此版本不含 Python」）
→ `ReflectionPythonEngine` 调 `app/src/main/python/dsh_runner.py`。

`dsh_runner.py` 的要点：

- 在**独立线程**里执行用户代码，主线程负责超时
- 超时用 `ctypes.pythonapi.PyThreadState_SetAsyncExc(tid, PyExc_SystemExit)` 异步注入异常
  —— 这是唯一能打断 `while True: pass` 的手段
- 返回结构化 JSON：`{ok, stdout, stderr, error, duration_ms}`

坑一：**不要给 Chaquopy 传 `ArrayList`**。它的 JNI 桥接只认 `String[]` / `List<String>` 的特定形态，
传 `ArrayList` 空参也会炸。现在统一传 **JSON 字符串**，Python 侧 `json.loads`。

坑二：`sys.settrace` 抛异常会被 CPython 在 C 层吞掉，超时从未生效。所以最终选了 `SetAsyncExc`。

---

## 6. 云端编译（重点）

手表编不了 Android 工程：没有 JDK、没有 SDK、内存也不够。云端编译是把这个能力外包给
GitHub Actions，App 只负责「触发、等待、取回产物」。

### 6.1 参与者

| 角色 | 说明 |
| --- | --- |
| App | 触发 workflow、轮询状态、下载 artifact |
| 目标仓库 | 用户在设置里填的 `owner/repo`，workflow 必须在这里 |
| workflow | `dsh-run.yml`（跑任意命令）或 `dsh-build.yml`（编 APK） |
| 源码 | 目标仓库里要有；没有就从 `source_repo` 克隆（v1.4 新增） |

### 6.2 完整链路（CloudTools.execute）

```
1. 配置校验
   cloudBuildEnabled → cloudRepo 归一化 → token 是否存在 → workflow 文件名归一化

2. 读 workflow 声明
   GET /repos/{repo}/contents/.github/workflows/{file}?ref={ref}
   → base64 解码 → 解析 on.workflow_dispatch.inputs 的键集合
   解析器只有 40 行，不引 snakeyaml（6.4MB 的包不值得为这点事加依赖）
   三种结果：有 inputs 的集合 / 明确没有 inputs（空集）/ 看不懂（null，不拦）

3. 按模板分流
   ├─ 接受 command（dsh-run.yml）
   │    工作目录打包：zip → base64，内联进 command，上限 MAX_BASE64_CHARS = 20_000
   │    超限时报「多少个文件、需要多少字符」，并建议用 files 只挑必要的
   └─ 不接受 command（dsh-build.yml）
        只传 variant / with_python / source_repo，多传会被 GitHub 422 拒绝

4. 三步探测
   probeRepo（仓库存在且有权限）
   probeWorkflow（workflow 存在且启用）
   latestRunId（记基线，用来定位刚触发的那次）

5. dispatch
   POST /repos/{repo}/actions/workflows/{file}/dispatches
   body: {ref, inputs}
   返回 204 且没有 body —— 不给 run id，这是 GitHub 的设计

6. 定位 run
   优先用 workflow 专属端点 /actions/workflows/{file}/runs 拿最新一条，
   与基线 id 比较；最多 7 次、间隔 2.5s
   不用时间窗比较：手表时钟可能不准

7. 等待（v1.4 起默认不等）
   wait=false（默认）：立刻返回 run 链接
   wait=true：每 8s 查一次，直到 settled 或 timeout（默认 900s；可被 timeout_sec 覆盖）

8. 取产物
   GET /actions/runs/{id}/artifacts → 逐个下载 zip → 解压到
   工作空间 sessions/<id>/artifacts/<name>/
   同时把最大的 APK 作为「界面产物卡片」返回

9. 后续查询（cloud_job）
   action=status / logs / artifacts / cancel
   优先用本会话记录，其次 run_id，最后回落到该 workflow 最新一次
```

### 6.3 两个模板

**dsh-run.yml**（跑任意命令）

- 输入：`command`、`artifact_name`、`artifact_path`、`retention_days`
- 只有基础 Ubuntu，没有 JDK / Android SDK
- 产物约定写到 `dsh-out/`；为空时自动从 `app/build/outputs`、`build`、`dist`、
  `out`、`target/release` 等目录收集 apk/aab/jar/zip/so/exe/whl
- 为什么要有自动收集：99% 的人不会记得约定，构建成功却拿不到包是最挫败的体验

**dsh-build.yml**（编 APK）

- 输入：`variant`（debug/release）、`with_python`（布尔）、`source_repo`（owner/repo）
- 步骤：checkout → **Locate project** → JDK 17 →（可选）Python 3.11 → **Set up Android SDK**
  → Cache Gradle → Build → Collect APKs → Upload artifacts
- `Locate project`：当前目录有 `gradlew` 就直接用；没有就浅克隆 `source_repo`，
  写 `PROJECT_DIR` 到 `$GITHUB_ENV`，后续 Build / Collect 都跟着它走
- `Set up Android SDK`：**不再用 `android-actions/setup-android@v3`**，原因见 6.4

### 6.4 三个真实的坑（都已修）

**坑 1：`setup-android` 装已下架的 `tools`**

```
Warning: Failed to find package 'tools'
.../setup-android/v3/dist/index.js:1823  error = new Error(...)
```

该 action 无参时默认 `sdkmanager tools platform-tools`，而 `tools` 包早已下架。
`sdkmanager` 返回非零 → action 抛错 → job 在装 SDK 就结束，Gradle 一行没跑。

修法：删掉 action，直接用 runner 自带的 `/usr/local/lib/android/sdk`，
只装真实存在的 `platform-tools`、`platforms;android-35`、`build-tools;35.0.0`；
目录已存在则跳过安装（避免 runner 镜像波动或源站抖动）。

`platforms;android-35` 必须与 `compileSdk` 对齐，这条已写成测试，
以后升 compileSdk 忘改模板会先炸在单测里。

**坑 2：目标仓库没有 `gradlew`**

```
chmod: cannot access './gradlew': No such file or directory
```

用户把 workflow 装进了 `dsdzm/dsh-`（私有仓库，里面只有 `.github`），
源码在 `dsdzm/DSH-Watch`。`workflow_dispatch` 的 inputs 装不下整个工程，
所以 checkout 出来是空壳。

修法：`source_repo` + `Locate project`（见 6.3）。仓库自带源码时行为不变。

**坑 3：`.gitignore` 吞掉源码包**

```gitignore
build/        # ← 裸写，匹配任意层级
```

它把 `app/src/main/java/com/dshwatch/domain/agent/build/` 整个包忽略了。
`git status` 永远干净，所以一直没人发现；但推上去的仓库缺
`ProjectBuilder.kt` / `LanguageValidators.kt` / `SourceValidator.kt`，
克隆下来编译必然：

```
e: BuildTools.kt:8:34 Unresolved reference 'build'
```

修法：改成只忽略 `app/build/` 与 `/build`，用 `git add -f` 把 4 个文件补进版本控制。

验证方式不是「看日志猜」，而是把仓库快照拉到本地、按模板流程构建：
补文件前失败、补文件后 `BUILD SUCCESSFUL`；再用 API 真实触发一次，
9 个步骤全绿、产物 12.5 MB。

### 6.5 云端的边界

- **构建的是仓库里的代码**，不是手表工作空间。要「手表改完直接出包」，
  还差一步「把工作空间推到仓库」（Git Trees API 一次提交，比逐文件 PUT 快一个数量级）。
- **出不了正式签名包**。模板默认 debug；正式签名要把 keystore base64 放进
  Actions Secrets，构建时解码 —— 现在没做，也不建议把 keystore 放进仓库。
- artifact 保留 14 天（模板里可改），过期后 `cloud_job(artifacts)` 会明确说「已过期」。

---

## 7. MCP（domain/agent/mcp）

自研 JSON-RPC over OkHttp，没有引官方 SDK（Kotlin SDK 依赖较重，且我们要复用已有的 SSE 读取）。

### 7.1 握手与降级

```
① POST initialize {protocolVersion: "2025-11-25", capabilities, clientInfo}
   成功 → notifications/initialized → tools/list
② 失败（多数是 400/404，服务端只认新版）→ 无状态模式
   protocolVersion = "2026-07-28"，跳过 initialize，直接 tools/list
```

`McpClient` 会记住 `stateless`，后续 `tools/call` 走同一套（无状态模式不带 `Mcp-Session-Id`）。

### 7.2 传输

- HTTP：`POST` JSON-RPC，响应可能是 `application/json`，也可能是 `text/event-stream`
- 统一用 `SseReader` 解，`Content-Type` 只用于选择解析入口，不做硬判断
- 超时 20s；工具调用结果里的 `content[]` 支持 `text` / `image`，目前只取文本并截断

### 7.3 工具适配与信任

`McpToolAdapter` 把 `tools/list` 的结果包成 `AgentTool`：

- 名字统一前缀 `mcp__<server>__<tool>`，避免与内置工具冲突
- `inputSchema` 原样透传（模型看得懂 JSON Schema）
- `safety = NETWORK`

**两步信任**：拉到清单后先展示给用户，用户勾选启用哪些，才真正注册进 `ToolRegistry`。
不做「连上就全量放行」—— MCP server 是第三方代码，工具描述本身就能诱导模型。

---

## 8. 插件系统（domain/plugin）

三个 hook，全部 `runCatching` 包裹，插件抛异常只记日志，不影响主流程：

```kotlin
interface ToolResultPlugin : Plugin {          // 工具返回后改写
    suspend fun onToolResult(call: ToolCallRecord, result: ToolResult): ToolResult
}
interface PromptPlugin : Plugin {              // 每轮请求前注入/改写系统提示
    suspend fun onSystemPrompt(ctx: PromptContext, prompt: String): String
}
interface RoutingPlugin : Plugin {             // 每轮决定工具面
    suspend fun onTools(ctx: PromptContext, tools: List<AgentTool>): List<AgentTool>
}
```

`PluginRegistry` 按 `enabledPlugins` 过滤，`pluginsCustomized=false` 时用默认集合。

| 插件 | 类型 | 做什么 |
| --- | --- | --- |
| `dsh-token-saver` | ToolResult | 把工具结果编码成 TOON（比 JSON 省 token），原文落 `_raw/`；**只有严格更小才采用**（含尾注成本） |
| `dsh-plugin-save-token` | Prompt | 在系统提示里加「少解释、少复述」的约束 |
| `dsh-whale` | Prompt | 语气与排版风格 |
| `dsh-tinyfish-search` | ToolResult | 搜索结果去重、压成短列表 |
| 风神（`FengshenPlugin`） | Routing + Prompt | 首条消息判断 spec / react；首轮注入 persona 并**收窄工具面**，首次工具调用后恢复全量 |

风神的收窄逻辑有个必须遵守的约定：**每轮按本轮实际工具面生成清单**，收窄时注明
「下一轮放开」，否则模型会以为工具不存在。

### TOON 编码器

只做编码、不做解码。规则：数组统一列式表达、去掉重复键名、数字不加引号。
压缩收益在 15%–40%，对长表格特别明显；短结果可能更大，所以带「严格更小才采用」的闸门。

---

## 9. 工作空间与文件

```
<workspace root>/                     # 设置里可选 EXTERNAL（推荐）或 INTERNAL
└── sessions/<sessionId>/
    ├── files/                        # 文件工具的根，`..` 越界拒绝
    ├── artifacts/<name>/             # 云端产物解压到这里
    ├── dist/                         # build_project 的输出
    ├── _raw/                         # TOON 压缩前的原文
    └── cloud-runs.json               # 本会话触发过的 run 记录（最多 20 条）
```

`WorkspaceStore.rootDir()` 是唯一入口。外部存储用 `getExternalFilesDir`，
卸载即清理，不需要 `MANAGE_EXTERNAL_STORAGE`（手表上申请这个权限基本等于自杀）。

`build_project`（本地写项目）：接收文件列表 → 静态校验（HTML/CSS/JS/Python 语法）
→ 写盘 → 返回入口文件。校验器在 `domain/agent/build/`：

- `LanguageValidators`：Python 用「续行四判据」判断缩进块的冒号是否真的缺失
  （前一行/本行反斜杠、前一行/本行括号未闭合），避免 `if x: \` 这类写法被误报
- `SourceValidator`：HTML 标签闭合、CSS 括号、JS 基础语法
- `ProjectBuilder`：入口推断（index.html / main.py…）、相对路径修复、产物落 `dist/`

---

## 10. 存储与安全

| 内容 | 位置 | 形式 |
| --- | --- | --- |
| 设置 | DataStore Preferences | JSON 序列化的 `AppSettings` |
| 会话 | 应用私有目录 | 每会话一个 JSON（消息、标题、置顶、token 累计） |
| API Key / GitHub Token | Android Keystore | AES-GCM 加密后存 SharedPreferences，明文不落盘 |
| 云端 run 记录 | 会话目录 | JSON，最多 20 条，超出按时间淘汰 |

`SecureKeyStore` 的 key 存在 Android Keystore 里（`setUserAuthenticationRequired(false)`，
手表上没有稳定的生物识别）。导出设置时**不包含**密钥，只导出 provider 的非敏感字段。

---

## 11. UI 与手表适配

- **Material 3**，Android 12+ 支持动态取色；深浅色跟随系统
- **大圆角**：`Shapes` 统一 20–28dp；屏幕四角有物理 R 角，内容区用 `cornerInsetDp` 让出安全区
- **字号**：默认 `fontScale = 0.92`，可在设置里 0.8–1.2 调
- **性能分档**：`PerfMode.AUTO` 按设备内存/核心数选档，决定消息渲染上限与流式节流间隔
- **设置二级页**：一级 8 个分组（模型、对话、Agent、执行与编译、插件、MCP、外观、关于），
  每项带一行摘要；点进二级页才能改。手表一屏放不下 40 个开关，分组是唯一可行的组织方式
- **对话页**：长列表 + 「移到底部」悬浮按钮（滚动超过一屏才出现）；思维链默认折叠

---

## 12. 网络层

`ChatBackend` 抽象三种后端：

| 实现 | 用途 |
| --- | --- |
| `OpenAiCompatBackend` | 任意 OpenAI 兼容服务（DeepSeek 官方、中转站、Ollama…） |
| `DshServerBackend` | 连接 DSH 服务器（配对码换设备令牌，复用 dsh-workspace 的远程访问） |
| `IoBackend` | 输入输出直通，用于本地调试 |

超时策略：连接 15s、读 120s（流式）；重试只针对「连接失败」与 5xx，
4xx 直接抛给上层（多半是 key 或模型名错，重试没意义）。
错误信息做了一层翻译：401 → 「令牌无效或过期」，404 → 「模型名可能不存在」，
429 → 「被限流，稍后再试」。

---

## 13. 构建、签名、测试

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk

./gradlew :app:testDebugUnitTest --rerun --offline     # 522 用例
./gradlew :app:assembleRelease --offline               # lite 6.4 MB
./gradlew :app:assembleRelease -PwithPython=true --offline   # full 27 MB
```

- **R8** 开 `isMinifyEnabled` + `isShrinkResources`，规则在 `app/proguard-rules.pro`
- **签名** v1 + v2 + v3 全开：v1 兼容部分国产手表自带安装器（只认 JAR 签名），
  v3 给 Android 9+，代价几百 KB
- **keystore**：`dsh-watch.jks` + `keystore.properties`（`storeFile` 是绝对路径，
  换机器要改）。证书 SHA-256 `70437a7f…bf31f94`，1.0 至今未换
- 测试重点：`CloudTargetsInputsTest`（真实模板当样本）、`CloudWorkflowTemplateTest`、
  `PythonValidatorTest`、`ChatEngineTest`、`ScriptToolsTest`

---

## 14. 已知问题与路线

1. **云端只认仓库代码**。手表工作空间 → 仓库的推送还没做。方案：Git Trees API
   一次提交（blob 可内联 content），3 个请求搞定，比逐文件 PUT 快得多。
2. **云端 release 无签名**。需要 Secrets 里的 keystore；如果做，务必用
   `secrets.KEYSTORE_BASE64` 而不是把文件提交进仓库。
3. **MCP 只有 HTTP/SSE**。stdio server 需要本地进程，手表上没有合适沙箱，暂不支持。
4. **cloud_build 默认不等待**（v1.4 起）。构建 8 分钟时，同步等待会让界面一直转圈；
   现在触发即返回链接，进度靠 `cloud_job`。需要同步结果时显式 `wait=true`。
5. **产物面板会堆卡片**。交付统一走资料库（`tools/upload_to_library.py`），
   本地构建产物及时归档到 `/root/dsh-watch-releases/`，不留在工作空间。
6. **全量构建约 1–2 分钟**（有缓存）；首次冷构建 5–10 分钟，主要是下 Gradle 与依赖。

---

## 附：关键常量

| 常量 | 值 | 位置 |
| --- | --- | --- |
| `HARD_STEP_CEILING` | 200 | ChatEngine |
| 停滞阈值 | 3 步无新指纹 | ProgressTracker |
| 全失败阈值 | 2 步 | ProgressTracker |
| 云端轮询间隔 | 8s | CloudTools |
| 云端默认等待 | 否（v1.4 起） | CloudTools |
| 云端字符上限 | 20,000（base64） | CloudPayload |
| 执行超时 | 20s（可配） | AppSettings |
| 输出上限 | 20,000 字符 | AppSettings |
| 会话 run 记录 | 20 条 | CloudRunStore |
| 工作空间位置 | EXTERNAL（默认） | AppSettings |
