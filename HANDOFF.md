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

```bash
export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk

# 全量单测（约 5 秒，521+ 用例）
./gradlew :app:testDebugUnitTest --rerun --offline

# lite release（6.4 MB）
./gradlew :app:assembleRelease --offline

# full release（27 MB，含 CPython 3.11，仅 arm64-v8a）
./gradlew :app:assembleRelease -PwithPython=true --offline

# 产物在 app/build/outputs/apk/release/app-release.apk
# 校验签名/版本
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs <apk>
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging <apk> | head -1
```

注意：lite 与 full 共用同一个输出路径，**构建完立刻改名归档**，否则会被下一次构建覆盖。

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
