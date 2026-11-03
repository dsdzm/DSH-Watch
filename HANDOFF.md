# DSH 肥鱼 · 交接说明

> 写给下一个对话用的。读完这份就能接着改，不需要翻聊天记录。
> 生成时间：2026-10-17 13:20 · 对应 commit `3cf1622`

---

## 一、一句话现状

v1.4 功能已完成、521+ 用例全绿、APK 已构建并在真机验收中；GitHub 云端构建的两个历史故障刚修完并**真实跑通一次**（run 39004747580，success）。当前处于「等作者真机验收，通过后才发 Release」的冻结期。

---

## 二、项目与设备

| 项 | 值 |
| --- | --- |
| 应用名 | DSH 肥鱼（包名 `com.dshwatch`） |
| 目标设备 | Android 9 手表，4+64GB，1200mAh，410×502 大圆角屏 |
| 技术栈 | Kotlin 1.9 + Jetpack Compose（Material 3），minSdk 28 / targetSdk 34 / compileSdk 35 |
| 版本 | versionName **1.4**，versionCode **12**（本轮修复没有升版本号） |
| 仓库 | https://github.com/dsdzm/DSH-Watch（main = `3cf1622`） |
| 云端构建仓库 | `dsdzm/dsh-`（私有，里面只有 `.github/workflows`） |
| 签名 | `dsh-watch.jks` + `keystore.properties`，证书 SHA-256 `70437a7f…bf31f94`，1.0 至今同一把 |

---

## 三、这次对话做完的事

### 1. v1.4 主体功能

- **MCP 客户端**：自研 JSON-RPC over OkHttp，复用 `SseReader`；先按 `2025-11-25` 握手，失败降级 `2026-07-28` 无状态；两步信任（先拉工具清单，用户确认后才启用）。
- **插件系统**：`ToolResultPlugin` / `PromptPlugin` / `RoutingPlugin` 三个 hook，全部 `runCatching` 包裹。内置 `dsh-token-saver`（TOON 编码压缩工具返回，原文落 `_raw/`，只有严格更小才采用）、`dsh-plugin-save-token`、`dsh-whale`、`dsh-tinyfish-search`、风神（首轮注入 persona + 收窄工具面，首次工具调用后恢复）。
- **去掉步数上限**：改为 `ProgressTracker` 进展指纹（成功调用 toolName+args+新增产物），连续 3 步无新指纹 / 连续 2 步全失败才停，兜底 200 步。
- **设置页二级化**：一级 8 个分组带摘要，点进二级页；`SettingsGroups` 常量共用，圆角加大。
- **对话页「移到底部」**：长会话快速回底。

### 2. 云端构建的三个坑（全部已修，且真实验证过）

| # | 现象 | 根因 | 修法 |
| --- | --- | --- | --- |
| 1 | job 在装 SDK 就失败 | `android-actions/setup-android@v3` 默认装 `tools` 包，该包已从 SDK 仓库下架 | 删掉该 action，直接用 runner 自带 SDK，只补 `platform-tools` / `platforms;android-35` / `build-tools;35.0.0`，已就绪则跳过安装 |
| 2 | `chmod: cannot access './gradlew'` | 目标仓库 `dsdzm/dsh-` 只放 workflow，没有源码，dispatch 不会带源码 | 模板新增 `source_repo`（默认 `dsdzm/DSH-Watch`），缺 `gradlew` 时浅克隆；`Build` / `Collect` 都跟着 `PROJECT_DIR` |
| 3 | 克隆下来编译报 `Unresolved reference 'build'` | `.gitignore` 里裸写 `build/`，把 `domain/agent/build/` 整个包忽略了，仓库一直缺 3 个文件 | `.gitignore` 改成只忽略 `app/build/` 和 `/build`，4 个文件补进版本控制 |

**验证方式**：把仓库快照下载到本地，先按模板流程构建（缺文件 → 编译失败），补文件后 `BUILD SUCCESSFUL`；再用 API 触发真实 run，9 个步骤全绿，产物 12.5 MB。

### 3. 交付方式变更

- **不再往产物面板堆卡片**，APK 一律上传资料库：`tools/upload_to_library.py <apk> --space-id nC2WofJtiVEQYUmFkami3r`。
- 命名固定 `DSH-Watch-v<版本>-<变体>-release.apk`；只传 lite/full 两个 release，debug 留本地。
- 下载直链用资料库 API 现取（2 小时有效），节点页的 APK 只能预览、下不了。

---

## 四、关键代码位置

```
app/src/main/java/com/dshwatch/
├── domain/agent/
│   ├── exec/            ShellRunner（文件重定向，避免孤儿进程阻塞）、JsRunner（Rhino，
│   │                    optimizationLevel=-1 + 指令观察者实现超时）、PythonBridge、ReflectionPythonEngine
│   ├── cloud/           GitHubApi、CloudTargets（仓库/分支/inputs 归一化）、CloudPayload（zip 内联）、
│   │                    CloudRunStore、CloudErrors
│   ├── mcp/             McpClient、McpToolAdapter、McpManager、McpModels
│   ├── build/           ProjectBuilder、SourceValidator、LanguageValidators（Python 续行四判据）
│   ├── tools/           CloudTools、BuildTools、ScriptTools、SearchTools、DeviceIntegrationTools、BuiltinTools
│   └── plugin/          Plugin、PluginRegistry、ToonEncoder、TokenSaverPlugin 等
├── ui/screens/settings/ SettingsScreen（一级）、SettingsGroupScreen、McpGroup、ExecSettingsScreen
└── ui/i18n/             AppStrings

app/src/main/assets/cloud/   dsh-run.yml、dsh-build.yml（App「安装模板」写进仓库的就是这两份）
app/src/main/python/         dsh_runner.py（独立线程 + PyThreadState_SetAsyncExc 中断）
tools/                       check_python_harness.py（11 项 Python 验收）、upload_to_library.py
.github/workflows/           与 assets 同源的两份模板
```

---

## 五、构建与测试

> **给作者的包一律是 release，不给 debug。**（2026-11-03 明确要求）
>
> 原因不只是体积：debug 包约 **74 MB**，release 约 **8 MB**，差 9 倍 ——
> 手表上装 74 MB 的包既占空间又慢。另外 debug 的 `applicationIdSuffix = ".debug"`
> 会让它变成**另一个 App**（`com.dshwatch.debug`），覆盖安装会失败、数据也不通。
>
> 自查一条：交付前 `ls -lh` 看体积，8 MB 量级才是对的；70 MB 一定是拿错包了。

> **v1.12 起没有 lite / full 两个包了。** 本地 Python 引擎（Chaquopy）整个移除，
> 安装包只剩一个、约 8 MB。原 `-PwithPython=true` 参数已不存在，传了会被忽略。
> 需要按架构裁剪时用 `-Pabi`（见下）。

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk

# 全量单测（664 用例）
./gradlew :app:testDebugUnitTest --rerun

# release（约 8 MB，通用包：v7a / v8a / x86 / x86_64 全含）
./gradlew :app:assembleRelease

# 只出 32 位（部分手表的安装器认这个）
./gradlew :app:assembleRelease -Pabi=armeabi-v7a

# 只出 64 位
./gradlew :app:assembleRelease -Pabi=arm64-v8a

# 产物在 app/build/outputs/apk/release/app-release.apk
# 校验签名/版本
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs <apk>
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging <apk> | head -1
```

输出路径只有一份，连续打不同 ABI 时**构建完立刻改名归档**，否则会被下一次覆盖。

### 版本号节奏（硬约定）

**不盲目升版本号。** 一轮迭代里改十次、打十个包，版本号都还是同一个 ——
只有「这一摊功能确实做稳了、要交给用户长期用」时才 `+1`。

原因很实际：

- 用户是覆盖安装（同证书），**versionCode 不变也能装**，升了只是把「同一个东西」
  在系统里记成两个版本，以后排查时反而要多问一句「你装的是哪一版」
- 版本号一旦升上去就回不来：用户装过 24，再给 23 只能卸载重装，数据丢了
- 「改一次升一次」会让 CHANGELOG 变成一堆 `1.15.1 / 1.15.2`，读起来没人分得清哪版好

所以现在的做法：**同版本号重发**，CHANGELOG 里在原版本条目下加一个
「崩溃已修（同版本号重发）」这样的子段落，写清楚这一轮修了什么。
等玻璃质感这摊稳定了，再统一升到 1.16。

判断「稳了」的标准：连续两轮用户反馈里没有「崩溃 / 明显不对」这一类，只有效果微调。

### 崩溃取证（v1.15 起）

release 包开 R8，系统崩溃对话框只给「屡次停止运行」，**拿不到堆栈**。
所以 v1.15 加了三个东西，排查崩溃时按顺序用：

| 位置 | 内容 |
|---|---|
| `app/build/outputs/mapping/release/mapping.txt` | R8 映射表，拿到混淆后的堆栈要先用它反混淆（`retrace`） |
| App 里「上次崩溃了」弹窗 | 崩溃后重新打开就能看到堆栈，一键复制 |
| 设置 → 诊断 → 底部 | 「上次崩溃的报告」+「最近的问题」（最近 8 条 warn/error） |

`mapping.txt` 每次 `assembleRelease` 都会重新生成，**它和 APK 是一对**：
换了包就得用同一轮的 mapping，旧的对不上。

实在拿不到报告时，退回二分法：把 `isMinifyEnabled` 临时改成 `false` 再打一版，
崩溃消失 → 是 R8 规则缺；仍崩 → 是代码逻辑。一次就能把问题面切一半。

### 反混淆怎么跑

```bash
/opt/android-sdk/cmdline-tools/latest/bin/retrace \
  app/build/outputs/mapping/release/mapping.txt crash.txt
```

v1.15 那次崩溃就是这么定位的：`CheckScrollableContainerConstraints` +
`AndroidDialog$DialogLayout` 两条，直接指向「弹窗里套了滚动容器」。

### 踩过的坑：GlassDialog 的 content 里不能再放滚动容器

`GlassDialog` 默认会在正文外包一层 `heightIn(max) + verticalScroll`。
滚动容器量高度时传下去的是**无限约束**，所以 content 里如果还有
`verticalScroll` / `wearVerticalScroll` / `LazyColumn`，里层就会抛：

```
IllegalStateException: Vertically scrollable component was measured with an
infinity maximum height constraints, which is disallowed.
```

**规则：content 自己滚，就传 `scrollable = false`，并给内容 `heightIn(max = ...)`。**

JVM 单测查不出这类问题（没有测量阶段），所以补了一条静态规则测试
`app/src/test/java/com/dshwatch/ui/components/GlassDialogScrollRuleTest.kt`：
扫所有 `GlassDialog(` 调用点，调用段内有滚动修饰符却没有 `scrollable = false` 就红。
加新弹窗前跑一遍全量单测即可。

---

## 六、已知限制 / 待办

1. **云端构建的是仓库代码，不是手表工作空间**。dispatch 装不下整个工程，只能靠 `source_repo` 克隆。想实现「手表改完直接出包」，需要在 App 里加「推送工作空间到仓库」（Git Trees API 一次提交，比逐文件 PUT 快得多）。
2. **云端出不了正式签名包**。模板默认 debug，`keystore.properties` 没有进仓库（正确做法：keystore base64 存 Secrets，构建时解码）。
3. **MCP 只支持 HTTP/SSE**，stdio 服务端不支持。
4. **资料库根目录有历史 APK**（WatchCloud、化学实验室），与本项目无关，没动。
5. **GitHub PAT 已用完，请撤销**。本轮用过一次写权限，只提交了代码，没有发 Release。

---

## 七、发布流程（作者定的规矩）

> 改代码 → 测试全绿 → 构建到 `dist/` 或上传资料库 → **停住等作者真机验收** → 作者确认后才 push + `gh release create`。
> 本地 commit 不受限制，限制的只有「推送代码」与「发 Release」。

例外：CI 配置修复（如本轮的 workflow）属于「不推就没法验证」，可以先推，但不发版、不动版本号。

---

## 八、签名与密钥

本压缩包内含：

- `signing/dsh-watch.jks` — 发布签名，密码在 `signing/keystore.properties`
- `signing/KEYSTORE-README.md` — 密钥来历与备份说明
- `signing/NEW-PASSWORD-未启用.txt` — 换密码方案，尚未启用，别误用

**丢了这把 key，已装用户就无法覆盖升级**（只能卸载重装，数据全丢）。请离线备份，不要放进公开仓库。

---

## 九、这次用到的资料库位置

- 空间：我的资料（`nC2WofJtiVEQYUmFkami3r`）
- v1.4 r3 包：`DSH-Watch-v1.4-lite-release-r3.apk` / `DSH-Watch-v1.4-full-release-r3.apk`
- 本地归档：`/root/dsh-watch-releases/`（v1.4、v1.4-r2、v1.4-r3）
