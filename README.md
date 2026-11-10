<p align="center">
  <img src="docs/mascot-cutout.png" width="120" alt="DSH 肥鱼">
</p>

<h1 align="center">DSH 肥鱼 · DSH Watch</h1>

<p align="center"><b>把 AI 编程助手装进手表。</b><br>不经过电脑，不依赖手机中转 —— 模型走云端 API，工具循环跑在表盘上。</p>

<p align="center">
  <a href="https://github.com/dsdzm/DSH-Watch/actions/workflows/ci.yml"><img src="https://github.com/dsdzm/DSH-Watch/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/dsdzm/DSH-Watch/releases"><img src="https://img.shields.io/github/v/release/dsdzm/DSH-Watch?display_name=tag" alt="Release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License"></a>
  <img src="https://img.shields.io/badge/minSdk-28-3ddc84" alt="minSdk 28">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-7f52ff" alt="Kotlin">
  <img src="https://img.shields.io/badge/APK-8.2%20MB-lightgrey" alt="APK 8.2 MB">
</p>

---

## 这是什么

DSH 肥鱼（`com.dshwatch`）是面向 **Android 9+（API 28）方形手表**的第三方 AI 助手客户端。
Kotlin + Jetpack Compose + Material 3，单 Activity，一份约 8 MB 的 APK。

它与「手表遥控电脑上的 Agent」那一类项目是两种东西：

| | 本项目 | 遥控类项目 |
|---|---|---|
| 引擎位置 | **手表本地** | 电脑 |
| 工具循环 | 手表执行 `tool_calls` | 电脑执行，手表只转发/审批 |
| 是否必须配电脑 | **否** | 是 |
| 断网/离桌 | 有网即可用 | 电脑必须开着 |

代价是它不跑真正的 DSH 运行时：没有 Node、没有本机 JDK。它用 OpenAI 兼容协议 +
一套为小屏重写的工具集，复刻「多步 Agent」的体验，并把本机做不了的事交给云端。

## 功能

### 对话

- 流式输出，思维链单独折叠，Markdown / 代码块 / 表格渲染
- 会话本地持久化、自动起标题、置顶、重命名
- 停止生成、重新生成、一键复制
- 附件上传：`txt` `md` `json` `csv` / 代码 / `docx` / `pdf` / 图片，正文提取后随消息进入上下文
- 内置文件浏览器：不依赖系统文件管理器，直接翻目录选文件（见下）
- 图片上传前压到长边 512 / JPEG 80（按路径与修改时间缓存），手表截图通常 20–60KB
- 对话内直接显示模型产出或引用的图片、视频（支持多模态的模型可读图）
- 系统提示词预设：Vibe Coding / 极简 / 代码评审 / 排障 / 讲解 / 自定义

### Agent 与工具

模型返回 `tool_calls` → 手表本地执行 → 结果回灌 → 再问模型，直到收敛。
步数不设硬上限，改用停滞检测；长任务用 `todo_write` 维护清单，进度实时可见。

**51 个内置工具**：

| 分组 | 工具 |
|---|---|
| 信息与计算 5 | `get_datetime` `calculate` `unit_convert` `get_device_info` `get_token_usage` |
| 联网 2 | `http_fetch` `web_search` |
| 工作空间文件 8 | `list_files` `read_file` `write_file` `edit_file` `append_file` `make_dir` `move_file` `delete_file` |
| 检索 2 | `glob` `grep` |
| 构建与云端 3 | `build_project` `cloud_build` `cloud_job` |
| 本机执行 1 | `run_script` |
| 交互与提醒 7 | `ask_user` `todo_write` `set_alarm` `list_alarms` `cancel_alarm` `vibrate` `create_event` |
| 设备联动 3 | `open_file` `open_screen` `get_clipboard` |
| 设备操控 4 | `device_screen` `device_act` `device_open` `device_handoff` |

表格里是全部 52 个工具，但**不是每次都全发给模型**：常驻的只有 13 个
（`tool_search` 与读写改搜跑、提醒自己进度的那几个），其余按「文件 / 构建 / 网络 /
提醒 / B站 / 设备」分组，只给一行目录。模型判断需要某类时调 `tool_search`，
取到的工具从下一轮起带完整参数 —— 每轮的工具表因此从 18365 字降到约 5 千字。
| B 站 16 | `bili_status` `bili_search` `bili_video` `bili_subtitle` `bili_comments` `bili_inbox` `bili_thread` `bili_report` `bili_reply` `bili_dm` `bili_like` `bili_coin` `bili_fav` `bili_fav_folders` `bili_fav_list` `bili_dyn` |

另外支持：

- **设备操控（默认关）**：通过系统无障碍服务读屏并按编号点击、输入、翻页，
  也能打开其他应用。翻页是**滑动**（一次划半屏），不是找滚动条 —— 手表上的列表
  基本都是 Compose 自绘，系统滚动动作在那些界面上不生效。
  操作期间 App 退到后台，屏幕底部一条悬浮提示写着当前动作，右边是停止按钮；
  通知栏同步显示「AI 正在操作设备 · 当前动作」。
  密码、验证码、支付确认框不代填；读到就交还用户。
  操控期间强制占用前台服务（不受「后台保持连接」开关影响），
  并可在设置页申请「不受电池优化限制」白名单 —— 手表的内存回收很激进，
  这是不 root 的加固手段里最有效的一条。
  **系统设置页可以操作**（开勿扰、调亮度、连蓝牙）；权限管理、应用安装、
  系统界面仍然只读 —— 那几页点错不可逆。
  开关与系统授权分两层，都开才生效 —— 关着时这四个工具的 schema 不进请求。
  **操控期间也只开放这四个工具**：工具表是每轮重发的固定成本，三十步的任务里，
  四十多个工具的 schema 就是几十万 token 的纯浪费。任务结束（交还 / 停止）后其余工具自动回来。
  历史里的旧屏幕同样按步折叠，只保留最近三屏 —— 旧编号指向的东西早就变了，
  留着不但费 token，还会把模型带偏。

- **内置文件浏览器（默认开）**：一部分手表 ROM 没有文件管理器，系统 `ACTION_OPEN_DOCUMENT`
  拿不到结果 —— 点「＋文件」什么都不发生。内置浏览器不依赖第三方 App，直接翻
  工作空间 / 下载 / 文档 / 图片 / 相机 / 音乐 / 存储根目录。列表是鱼眼变换的，
  圆屏上滚到两端时条目缩小内收，长文件名不会被圆周切掉。
  公共目录的读取门槛按系统版本不同：**API 28–29** 是 `READ_EXTERNAL_STORAGE`，
  **API 30+** 只能是「所有文件访问」(MANAGE_EXTERNAL_STORAGE) —— 后者要在系统设置里
  单独打开，不授权也能用，只是只看得到应用自己的目录。设置 → 文件里可切回系统选择器。

- **MCP 客户端**：手写实现，不引官方 Kotlin SDK（Ktor 对 8 MB 的包太重）。HTTP / SSE 传输，工具自动转成 `AgentTool` 注册进工具表。stdio 传输在手表上没有可用的进程沙箱，未支持。
- **工具结果插件**：可插拔的后处理钩子，内置 JSON→TOON 压缩（重复键名只写一次，常压掉 30–60%）等。
- **审批策略**：读写分离，`run_script` 出厂关闭、每次强制确认且无视「总是允许」，工作目录锁在会话目录。

### 画布

模型直接输出围栏代码块，App 原生渲染（无 WebView）：

- **数学公式**（`math` 围栏）：LaTeX 子集，分式、根号、上下标、求和积分、定界符、希腊字母、常用运算符
- **思维导图**（`mindmap` 围栏）：缩进列表 → 分层导图，贝塞尔连线
- **几何图**（`geom` 围栏）：JSON DSL → 坐标系、点线圆、多边形、角度弧、标注

三块都支持全屏与手势缩放。语法说明会随系统提示一起发给模型，所以它知道这些能力存在。

### 模型接入

两种后端，互不冲突：

| 模式 | 说明 |
|---|---|
| **直连 API** | 任意 OpenAI 兼容服务：官方接口、中转站、本地 Ollama。Key 存 Android Keystore |
| **DSH 服务器** | 复用 `dsh-workspace` 配对流程，10 分钟一次性配对码换设备令牌 |

另外内置两家免费通道（Agnes、智谱 GLM）。内置项的地址与 Key 在界面中不可见、不可改，
模型名单锁定为免费档，避免误用计费模型。

> 仓库**不包含**内置 Key —— 它由构建时的 `local.properties` / 环境变量注入 `BuildConfig`。
> 从源码自行构建的 APK 没有内置通道，需要在设置里填自己的 Key。正式 Release 包已注入。

### 用量

优先读服务端 `usage`（`stream_options.include_usage`，兼容 DeepSeek 与 OpenAI 两种缓存字段）；
服务端不返回时本地估算并标记「估算」。按天/模型聚合，按单价折算花费。

## 安装

到 [Releases](https://github.com/dsdzm/DSH-Watch/releases) 下载最新 APK：

```bash
adb install -r DSH-Watch-v1.20.0.apk
```

没有 adb 就把 APK 传进手表，用文件管理器点开安装（需允许「安装未知来源应用」）。
包名 `com.dshwatch`，debug 变体是 `com.dshwatch.debug`，两者可共存。

**签名指纹**（SHA-256，可用于校验安装包来源）：

```
70:43:7A:7F:33:BE:37:46:BB:DA:17:9A:A1:5B:8C:F2:56:A3:27:68:C9:88:34:AD:DA:C9:8B:42:8B:F3:1F:94
```

## 首次使用

### 方式 A：直连自己的 API

1. 设置 → 供应商 → `+`
2. 类型选 **直连 API**，填接口地址（要带 `/v1`）与 Key
3. 拉取模型列表，或手动添加（如 `deepseek-chat`、`deepseek-reasoner`）
4. 点 **测试连接** —— 它会依次验证配置、`/models`、`/chat/completions` 和 SSE 流式，哪一步红就是哪一层的问题
5. 保存，回对话页开始

### 方式 B：连接电脑上的 DSH

电脑端在 DSH WebUI 里启用 `dsh-workspace` 插件 → 添加授权根目录 → 打开远程访问 →
创建配对，拿到 10 分钟有效的一次性配对码。手表端进 **设置 → 配对 DSH 服务器**，
填地址、配对码、设备名即可。

> 协议适配集中在 `net/backend/DshServerBackend.kt` 的 `Endpoints` 与 `net/model/Wire.kt`，
> 版本字段对不上时只改这两处。

首次启动会走一遍 6 页新手引导：什么是 API Key、上下文窗口怎么设、温度影响什么。

## 构建

| 项 | 版本 |
|---|---|
| JDK | 17+（AGP 8.x 要求） |
| Android SDK | Platform 35 / Build-Tools 35.0.0 |
| AGP | 8.7.3 |
| Gradle | 8.11.1（wrapper 已附带） |
| Kotlin | 2.0.21 |
| minSdk / targetSdk / compileSdk | 28 / 34 / 35 |

```bash
# 调试包
./gradlew :app:assembleDebug

# 正式包（未配置签名时产出未签名 APK）
./gradlew :app:assembleRelease

# 只出单个 ABI，减小体积
./gradlew :app:assembleRelease -Pabi=arm64-v8a

# 全量单元测试
./gradlew :app:testDebugUnitTest
```

国内网络：`settings.gradle.kts` 已把阿里云镜像放在 `google()` / `mavenCentral()` 之前；
Gradle 发行版本身建议从华为镜像下载，wrapper 的 `networkTimeout` 已调到 120 秒。

**正式签名**：仓库不含密钥，`.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`。
在自己的机器上创建 `keystore.properties` 指向本地密钥即可。密钥文件务必备份到项目之外 ——
同包名更新必须使用同一套密钥，丢了就只能改包名重发。

## 工程结构

```
app/src/main/java/com/dshwatch/
├─ core/        AppContainer（手写 DI）、日志、JSON、ID
├─ domain/      ChatEngine（对话与 Agent 循环）、TokenEstimator、PerfProfile
│  ├─ agent/    ToolRegistry、tools/（51 个工具）、exec/、cloud/、mcp/、build/（静态校验）
│  ├─ plugin/   工具结果插件与 TOON 编码
│  └─ model/    ChatMessage / Conversation / Provider / Media
├─ data/        SettingsRepository（DataStore）、ConversationStore（JSON）、SecureKeyStore
├─ net/         Http、SseReader、backend/（OpenAI 兼容 / DSH 服务器）、media/
├─ service/     流式期间的前台服务
└─ ui/          AppRoot、nav、theme、markdown、canvas、media、screens/
```

没有 Hilt、没有 Room、没有 navigation-compose、没有 WebView。理由都在
[docs/PROJECT-DETAILS.md](docs/PROJECT-DETAILS.md) 里，一句话：手表内存小、启动要快，
每去掉一层依赖都换来实打实的常驻内存。

## 能力边界

写清楚比藏起来好：

- **没有本机编译器**。`build_project` 只做静态校验 + 打包，不是真编译。校验器覆盖 JSON、HTML/XML、CSS、JS/TS、Python、Shell、YAML，原则是「只报确定是错的」——读报告的是模型，误报会让它把正确代码改坏。真要编 APK / Gradle 工程，走 `cloud_build`（GitHub Actions）。
- **`run_script` 不是沙箱**。子进程与 App 同 UID，能读应用私有目录。缓解手段是默认关闭、每次确认、工作目录限制，而不是隔离。
- **内置 Key 可被反编译提取**。真正的防线是服务端限额，不是客户端的隐藏。
- **圆形表盘未适配**。当前按方形/矩形优化，圆形表需要改 `ui/theme` 的尺寸策略。

## 安全与隐私

- API Key 优先存 Android Keystore（Tink）；部分定制 ROM 不支持时降级为普通存储，并在界面明确提示
- 直连模式下聊天内容发往你配置的第三方接口；DSH 服务器模式数据只在手表与你的电脑之间
- HTTP 明文仅适合可信局域网，跨公网请用 HTTPS 或 Tailscale / WireGuard
- 不依赖任何 Google 服务（无 GMS、无 Firebase、无 Wearable Data Layer）
- 存储权限只在用内置文件浏览器时申请，**只读不写**；API 30+ 的「所有文件访问」是
  系统级重权限，不申请也能正常用，只是看不到公共目录

漏洞与安全问题请按 [SECURITY.md](SECURITY.md) 的方式反馈，不要直接开公开 Issue。

## 文档

| 文件 | 内容 |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | 逐版本变更，与 App 内「关于 → 更新日志」同源 |
| [docs/PROJECT-DETAILS.md](docs/PROJECT-DETAILS.md) | 架构、对话引擎、工具循环、MCP、插件、构建链 |
| [docs/CLOUD.md](docs/CLOUD.md) | 云端编译（GitHub Actions）配置 |
| [docs/releases/](docs/releases/) | 各版本发布说明与根因分析 |
| [docs/HISTORY.md](docs/HISTORY.md) | 早期开发日志存档（v1.0–v1.4） |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 构建、测试、提交与 PR 约定 |

## 参与贡献

欢迎 Issue 与 PR。请注意这是**手表应用**：很多问题（路径、时区、真机权限、体积）
只有装到设备上才会暴露，所以合并前需要真机验证。具体流程见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

[GPL-3.0](LICENSE)。衍生作品需以同样许可开源。

## 免责声明

- 本项目是非官方第三方客户端，与 DeepSeek 官方无任何关系。
- 本项目不含任何模型、密钥或服务端资源，使用产生的 API 费用由使用者自行承担。
- 按现状提供，不对因使用造成的任何数据或设备损失负责。
