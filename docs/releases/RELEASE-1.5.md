# v1.5 改动与技术实现详解

> 本文档记录 **DSH 肥鱼 v1.5（versionCode 13）** 相对 v1.4 的全部代码改动，以及每处改动背后的实现机制、设计取舍和验证方式。
> 面向后续维护者：读完应能独立复现、修改、验证这些改动，而不需要重新踩一遍坑。

| 项目 | 值 |
|---|---|
| 版本 | v1.5 / versionCode 13 |
| 基线 | v1.4 / versionCode 12（提交 `1ff137d` 之前的 v1.4 发布点） |
| 本版提交 | `12a18d2`（release: v1.5） |
| 改动规模 | 9 个文件，+106 / −16 行 |
| 测试 | 522 个单元测试，全绿 |
| 产物 | `DSH-Watch-v1.5-lite-release.apk`（6.7MB）、`DSH-Watch-v1.5-full-release.apk`（27.9MB） |
| 签名 | SHA-256 `70437a7f33be3746bbda179aa15b8cf256a32768c98834addac98b428bf31f94`（与 v1.4 同证书，可覆盖安装） |

---

## 0. 一句话总结

v1.5 只做了一件事：**让「云端编译」这条路从「看起来能用」变成「真的能用」**，并顺手把 lite 版里那些「摆着但跑不起来」的入口全部收掉。

它不是一个加功能的版本，而是一个**修可信度**的版本。改动分两条线：

- **云端线**（用户感知最强）：修掉三个各自独立、但都会让整条链路断掉的硬伤；把阻塞式等待改成异步触发；轮询降频；UI 上标出预计耗时。
- **本机线**：能力清单从「写死的文案」改成「按本安装实际有的引擎生成」，lite 版不再暗示自己能跑 Python、也不再暗示自己能本地编译。

---

## 1. 变更全景

### 1.1 提交图谱

v1.5 的改动不是一次提交完成的，而是分散在 v1.4 发布后的一系列修复里，最后由 `12a18d2` 统一收口版本号。理解这个顺序有助于定位代码：

| 提交 | 内容 | 归属 |
|---|---|---|
| `fce5c38` | fix(ci): 云端构建不再装已下架的 tools 包 | 云端硬伤 ① |
| `f2626ef` | fix: 云端构建补齐两个根因 —— 空仓库没有 gradlew、源码包被 gitignore 吞掉 | 云端硬伤 ②③ |
| `d1d7e1b` | docs: 交接说明 | 文档 |
| `46af80f` | fix: cloud_build 默认不阻塞等待 | 异步化（代码层） |
| `1ff137d` | docs: 技术详解 | 文档 |
| `12a18d2` | release: v1.5 —— 异步化收尾、轮询 60s、ETA 提示、lite 能力收敛、版本号 | 全部 |

> 注：`fce5c38`/`f2626ef` 在早期因为远端与本地历史分叉，被重建为 `3cf1622` 这条线上的等价提交后推送。内容一致，只是 sha 不同。

### 1.2 文件清单

| 文件 | 改动 | 类别 |
|---|---|---|
| `app/src/main/assets/cloud/dsh-build.yml` | 构建模板重写：删 setup-android、加 source_repo + Locate project、PROJECT_DIR 传播 | 云端链路 |
| `.github/workflows/dsh-build.yml` | 与上者同源（`cp` 同步），仓库里的工作副本 | 云端链路 |
| `app/src/main/java/com/dshwatch/domain/agent/tools/CloudTools.kt` | `POLL_INTERVAL_MS` 8s→60s；`wait` 默认 true→false；描述改写 | 云端链路 |
| `app/src/main/java/com/dshwatch/domain/ChatEngine.kt` | 系统提示按引擎生成 Python 能力行；补「本地校验不产出可执行文件」 | lite 收敛 |
| `app/src/main/java/com/dshwatch/domain/agent/tools/ScriptTools.kt` | `run_script` 描述与 `language` enum 按引擎收敛 | lite 收敛 |
| `app/src/main/java/com/dshwatch/ui/screens/settings/ExecSettingsScreen.kt` | Python 开关 → 有引擎才显示开关，无引擎显示说明文字 | lite 收敛 |
| `app/src/main/java/com/dshwatch/ui/components/AgentUi.kt` | 工具卡片新增云端构建预计耗时行 | UI |
| `app/src/main/java/com/dshwatch/ui/i18n/AppStrings.kt` | 新增 `cloudBuildEta`（接口 + 中文 + 英文三处） | UI/i18n |
| `app/build.gradle.kts` | `versionCode 12→13`、`versionName "1.4"→"1.5"` | 版本 |
| `app/src/main/java/com/dshwatch/domain/AppChangelog.kt` | 新增 1.5 条目（5 条用户可感知变化） | 版本 |
| `CHANGELOG.md` | 新增 `[1.5]` 段（修复/改进） | 版本 |

---

## 2. 云端编译的三处硬伤（核心）

v1.4 的云端编译在纸面上是通的：手表打包工作目录 → 触发 `workflow_dispatch` → 云端 gradle → 回传 artifact。但真机上连续失败两次，每次失败都停在不同位置，且日志都指向「看起来不相关」的原因。

三处故障**互相独立**：修好①不会让②通过，修好②不会让③通过。这是这一版最值得记录的部分。

### 2.1 硬伤①：装 SDK 时找到一个不存在的包

**现象**（run `39002138855`）：

```
Warning: Failed to find package 'tools'
Error: Process completed with exit code 1.
  at setup-android/dist/index.js:1823
```

**根因**：模板用了 `android-actions/setup-android@v3`。这个 action 的默认行为里包含 `sdkmanager tools` —— 而 `tools` 这个包早已从 Google 的 SDK 仓库下架。`sdkmanager` 打印 warning 后返回非零，action 直接把异常抛出去，**整个 job 在「装 SDK」这一步就结束，连 Gradle 都没跑到**。

难点在于：报错文本是 `Failed to find package 'tools'`，看起来像是「模板缺了什么配置」，实际上是「这个 action 依赖了一个已消失的包」。这类失败不能靠改参数绕过去，只能不用它。

**修复**：删掉该 action，改用 **runner 镜像自带的 SDK**（`/usr/local/lib/android/sdk`），只补当前编译真正需要的三个包。完整实现（`app/src/main/assets/cloud/dsh-build.yml`，第 87–117 行）：

```yaml
- name: Set up Android SDK
  shell: bash
  run: |
    set -euo pipefail
    SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
    if [ ! -d "$SDK_ROOT" ]; then
      echo "::error::找不到 Android SDK：$SDK_ROOT"
      exit 1
    fi
    echo "ANDROID_HOME=$SDK_ROOT" >> "$GITHUB_ENV"
    echo "ANDROID_SDK_ROOT=$SDK_ROOT" >> "$GITHUB_ENV"

    # cmdline-tools 的目录名随镜像版本变化（latest / 16.0 …），找而不是写死
    SDKMANAGER="$(find "$SDK_ROOT/cmdline-tools" -maxdepth 3 -type f -name sdkmanager 2>/dev/null | head -1 || true)"
    if [ -z "$SDKMANAGER" ]; then
      echo "::error::找不到 sdkmanager"
      exit 1
    fi

    # 镜像里已经有了就别再联网装：runner 换镜像也不会因为源站波动而失败
    if [ -d "$SDK_ROOT/platforms/android-35" ] && [ -d "$SDK_ROOT/build-tools/35.0.0" ]; then
      echo "SDK 已就绪，跳过安装"
    else
      yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses > /dev/null 2>&1 || true
      "$SDKMANAGER" --sdk_root="$SDK_ROOT" \
        "platform-tools" "platforms;android-35" "build-tools;35.0.0"
    fi
```

三个设计点，每个都对应一个曾经踩过的坑：

1. **不写死 sdkmanager 路径**。runner 镜像升级时 `cmdline-tools/latest` 会变成 `cmdline-tools/16.0` 之类，写死路径会在半年后突然失效。用 `find` 找第一个可执行文件。
2. **已就绪则跳过安装**。这不是优化，是**稳定性**：能少一次联网就少一个失败源（Google 源站偶发 5xx）。判断依据是「目标 platform 目录 + 目标 build-tools 目录都在」。
3. **`yes | ... --licenses || true`**。`yes` 会被 SIGPIPE 打断，退出码非零是正常现象，不能让它把 `set -e` 触发掉。

**为什么不 pin 一个旧版本的 setup-android**：旧版本能装成功是因为它不带 `tools`，但它同样会在未来的镜像升级里失效；而 runner 自带 SDK 是 GitHub 的稳定契约，依赖它比依赖某个 action 的旧版本更可靠。

### 2.2 硬伤②：仓库里没有 gradlew

**现象**（run `39003529450`）：

```
chmod: cannot access './gradlew': No such file or directory
Error: Process completed with exit code 1.
```

**根因**：云端编译的目标仓库（`dsdzm/dsh-`，私有）**只用来放 workflow**，里面没有源码。而 `workflow_dispatch` 的 `inputs` 只能装字符串（手表端把工作目录打成一个 base64 命令内联进去），装不下整个 Android 工程 —— 所以「源码跟着 dispatch 过来」这条路本来就走不通。

真实结构是：

```
dsdzm/dsh-          ← 只有 .github/workflows/dsh-build.yml，没有 gradlew
dsdzm/DSH-Watch     ← 完整工程（公开）
```

**修复**：模板新增 input `source_repo`，并增加一步 `Locate project` 决定构建目录：

```yaml
- name: Locate project
  shell: bash
  env:
    SOURCE_REPO: ${{ inputs.source_repo }}
  run: |
    set -euo pipefail
    if [ -f gradlew ]; then
      echo "PROJECT_DIR=." >> "$GITHUB_ENV"
      echo "当前仓库自带源码，直接构建"
      exit 0
    fi
    if [ -z "${SOURCE_REPO:-}" ]; then
      echo "::error::当前仓库没有 gradlew，且没有填 source_repo"
      exit 1
    fi
    echo "当前仓库没有 gradlew，从 $SOURCE_REPO 取源码"
    git clone --depth 1 "https://github.com/$SOURCE_REPO.git" src || {
      echo "::error::克隆 $SOURCE_REPO 失败 —— 仓库要公开，名字写成 owner/repo"
      exit 1
    }
    echo "PROJECT_DIR=src" >> "$GITHUB_ENV"
    ls src | head -20
```

配套改动两点：

```yaml
# Build 步骤先切到项目目录
- name: Build
  run: |
    set -o pipefail
    cd "$PROJECT_DIR"
    ...
    chmod +x ./gradlew
    ./gradlew $ARGS --no-daemon

# Collect APKs 也跟着目录走
- name: Collect APKs
  if: always()
  run: |
    mkdir -p dsh-out
    find "${PROJECT_DIR:-.}/app/build/outputs/apk" -name "*.apk" -exec cp {} dsh-out/ \; || true
```

`PROJECT_DIR` 通过 `$GITHUB_ENV` 在步骤间传递 —— 这是 GitHub Actions 跨 step 传值的标准做法（每个 step 是独立 shell 进程，普通变量不会活到下一步）。

**为什么用「探测 + 二选一」而不是「一律克隆」**：同一个模板要同时服务两种仓库布局 —— 有的用户把源码和 workflow 放在同一个仓库（此时克隆是多余的，而且私有源码根本克隆不了），有的只放 workflow。用 `[ -f gradlew ]` 探测让两种情况都走通，且**不依赖用户配置正确**。

**三种场景都做了本地模拟验证**（把每个 step 的 `run` 块抽出来在临时目录执行）：

| 场景 | 仓库内容 | 期望 | 实测 |
|---|---|---|---|
| A | 自带 `gradlew` | `PROJECT_DIR=.`，直接构建 | ✅ |
| B | 只有 workflow | 浅克隆成功，`PROJECT_DIR=src` | ✅ |
| C | 只有 workflow 且 `source_repo` 留空 | `::error::` 明确报错退出 | ✅ |

场景 C 的报错文案是特意写的：如果只写「找不到 gradlew」，用户会以为模板坏了；写成「没有 gradlew，且没有填 source_repo」才能让他知道该去填哪个输入。

### 2.3 硬伤③：源码包被 `.gitignore` 吞掉

**现象**：把源码推到 GitHub 后再克隆下来构建，编译失败：

```
e: .../ChatEngine.kt: Unresolved reference 'build'
```

本地 `git status` 永远是干净的，看起来「代码都推上去了」。

**根因**：`.gitignore` 里写了一条**裸的** `build/`：

```gitignore
build/        # ← 这一条
```

gitignore 的 `build/` 不带前导斜杠时，匹配**任意层级**下名为 `build` 的目录。而本项目的包结构里恰好有一个：

```
app/src/main/java/com/dshwatch/domain/agent/build/
├── ProjectBuilder.kt
├── LanguageValidators.kt
└── SourceValidator.kt
```

于是这三个源文件从未被提交。`git status` 干净是因为它们对 git 来说「不存在」，本地编译又直接读文件系统，所以本地一切正常 —— 只有「克隆一份干净仓库再编译」才会暴露。这也是为什么这个 bug 能活过好几轮发布。

**修复**：

```gitignore
/build          # 仅仓库根的构建输出
app/build/      # 模块的构建输出
# 只忽略构建输出目录，不能写裸 `build/` —— 那会把
# app/src/main/java/com/dshwatch/domain/agent/build/ 整个包一起忽略掉，
# 推上去的仓库就缺 ProjectBuilder / LanguageValidators / SourceValidator，
# 云端编译必然 Unresolved reference 'build'。
```

并用 `git add -f` 强制补提交了这 4 个文件（3 个源文件 + 1 个测试）。

**验证方式**（这一步很关键，不能只看本地能编）：

1. 取一份「修复前」的仓库快照，按云端流程克隆 → `./gradlew assembleDebug` → **失败**，报错与线上一致；
2. 在同一份快照里打上 `.gitignore` 修复 + 补文件 → 再跑 → `BUILD SUCCESSFUL`。

即：先复现，再证明修复真的解决了它，而不是「改完本地能编就认为好了」。

**教训**（写进 `.gitignore` 注释里了）：gitignore 里凡是**名字可能也是包名**的目录（`build`、`dist`、`out`、`target`），必须带路径限定。裸写一个词，等于在未来的某一天静默丢掉一个包。

### 2.4 用测试把这三处修复钉住

`app/src/test/java/com/dshwatch/domain/agent/cloud/CloudWorkflowTemplateTest.kt`（4 条测试，读模板文件做断言，不需要网络）：

| 测试 | 断言内容 | 防的是 |
|---|---|---|
| 不装已下架的包 | 模板不含 `uses: android-actions/setup-android`，不含 `sdkmanager tools` | 硬伤① 回归 |
| 安装的包是现存的 | 含 `platform-tools` / `platforms;android-${compileSdk()}` / `build-tools;35.0.0` | 包名写错、升级 compileSdk 漏改 |
| 导出 SDK 环境变量 | 含 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 写入 `$GITHUB_ENV` | gradle 找不到 SDK |
| 缺源码时能自取 | 含 `source_repo` / `PROJECT_DIR` / `git clone` / `cd "$PROJECT_DIR"` | 硬伤② 回归 |

其中 `compileSdk()` 是从 `app/build.gradle.kts` 里正则抓出来的，**不是写死的 35** —— 这样将来把 `compileSdk` 升到 36 时，模板没跟着改会被测试直接拦下。

> 这组测试的注释里明确记录了它来自 run `39002138855` 的真实失败。写测试时留下「为什么会有这条测试」，比测试本身更有价值。

---

## 3. `cloud_build` 异步化

### 3.1 旧行为的问题

v1.4 的 `cloud_build` 默认 `wait=true`，`timeoutSec=900`。用户点一次「编个 APK」，工具调用会**阻塞**：

- 手表端一次工具调用挂起 5–15 分钟，界面上只有转圈；
- 期间模型无法做别的事，用户也无法追问；
- 超时后拿到一句「等待 900 秒仍未结束」，但构建其实还在云端跑 —— 信息是半成品；
- 真机上一次这样卡了十几分钟，用户直接问「是不是死了」。

### 3.2 新行为

```kotlin
// CloudTools.kt:134
val wait = args.boolOf("wait", default = false)
```

默认只做「触发 + 定位 run」，立刻返回：

```kotlin
// CloudTools.kt:232-241
if (!wait) {
    return ToolResult.ok(
        "已在云端触发 `$workflow`（分支 $ref），**没有阻塞等待**。\n" +
            formatRun(located) +
            "\n运行页：https://github.com/$repo/actions/runs/${located.runId}\n" +
            "首次构建要下 Gradle 依赖，通常 5～10 分钟。稍后用 " +
            "cloud_job(action=status) 查进度，action=logs 看日志，" +
            "action=artifacts 下载产物。"
    )
}
```

时序对比：

```
v1.4（阻塞）：
  dispatch ──► 轮询 ──► 轮询 ──► … ──► 结束 ──► 返回（用户等了 5–15 分钟）
                ↑ 这期间界面只有转圈，模型无法响应

v1.5（异步）：
  dispatch ──► 定位 run ──► 立即返回「运行页链接」
                              │
                              ├─ 用户/模型随时 cloud_job(status) 查
                              ├─ cloud_job(logs) 看日志末尾
                              └─ cloud_job(artifacts) 下载产物
```

工具描述也同步改写，把「默认不阻塞」写进模型能看到的地方（否则模型会以为它还在等，然后自己空转）：

```
**默认触发后立刻返回**（不阻塞等待），拿到的 run 链接可以直接给用户；
查进度用 `cloud_job(action=status)`，看日志用 `action=logs`，下产物用 `action=artifacts`。
只有确实需要同步结果时才传 `wait=true`（此时最多等到 timeout_sec，默认 300 秒）。
```

### 3.3 一个必须保留的机制：dispatch 不返回 run id

异步化没有改变「定位 run」这件事的难度，而它是整条链路里最不显然的一环：

> GitHub 的 `workflow_dispatch` 成功时返回 **204 No Content，没有 body** —— 它不告诉你触发了哪个 run。

所以「把刚触发的那次找回来」是**主路径**，不是兜底。实现（`CloudTools.kt:211-228`）：

```kotlin
// 触发前先记下基线 run id。
// 用 id 比较而不是时间窗 —— 手表的时钟未必准，而且 Actions 的
// created_at 有分钟级抖动。
val baselineId = runCatching { api.latestRunId(repo, workflow) }.getOrNull()

val triggeredAt = System.currentTimeMillis()
val runId = api.dispatch(repo, workflow, ref, finalInputs)

val located = if (runId != null) {
    runCatching { api.getRun(repo, runId) }.getOrNull()
} else {
    locateRun(api, repo, ref, workflow, baselineId, triggeredAt)
}
```

两个设计点：

1. **用 `id > baselineId` 而不是时间窗**。手表时钟可能偏差几分钟，Actions 的 `created_at` 也有抖动，用时间窗会抓错 run（抓到上一次的），而「id 严格大于基线」在单仓库单 workflow 下几乎是确定的。
2. **定位成功后立刻 `ctx.cloudRuns?.put(...)`** 存进会话记录。这样后续 `cloud_job` 不带 `run_id` 也能找到它 —— 即使定位逻辑在某个边缘场景下失败，`cloud_job` 还有第三条退路（直接查该 workflow 最近一次运行）。

---

## 4. 轮询降频：8 秒 → 60 秒

```kotlin
// CloudTools.kt:54-56
/** 轮询间隔：Actions 起步就要十几秒，8 秒一次既及时又不会刷爆速率限制 */
// 一分钟一次：云端构建本来就要 5–10 分钟，8 秒一查纯属浪费额度与电量
private const val POLL_INTERVAL_MS = 60_000L
```

只作用于 `pollUntilSettled`（即 `wait=true` 路径），也就是模型显式要求同步等的场景：

```kotlin
// CloudTools.kt:482-495
private suspend fun pollUntilSettled(...): CloudRunRecord {
    var record = initial
    val deadline = System.currentTimeMillis() + timeoutSec * 1000L
    while (!record.settled && System.currentTimeMillis() < deadline) {
        delay(POLL_INTERVAL_MS)
        record = runCatching { api.getRun(repo, record.runId) }.getOrElse { record }
    }
    return record
}
```

为什么值得改，算一笔账：一次 8 分钟的构建，8 秒一次是 **60 次 API 调用**，60 秒一次是 **8 次** —— 少 87%。手表上每次请求都走蓝牙/WiFi 再唤醒 CPU，这部分电量消耗与信息量完全不成比例：构建本身的状态变化粒度就是分钟级。

`delay()` 用的是协程挂起而不是 `Thread.sleep`，所以等待期间不占线程，UI 也不会卡。

---

## 5. 前端预计耗时提示

### 5.1 为什么放在折叠外

云端构建的「慢」是**预期行为**，不是异常。但用户在手表上只看到一个转圈的工具卡片，没有任何时间预期，就会判定「卡住了」。

所以提示必须放在**不用展开就能看到**的位置 —— 折叠区域里的说明等于没写。实现（`AgentUi.kt:165-175`）：

```kotlin
// 云端构建要几分钟，卡片上先把预期写清楚 —— 否则用户只会看到"一直在跑"
if (call.name == "cloud_build") {
    Text(
        text = s.cloudBuildEta,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 28.dp, top = 1.dp),
    )
}
```

插入位置在**标题行之后、参数摘要之前**：

```
┌──────────────────────────────────────┐
│ ▸  ☁ 云端编译         12.3s   完成  │  ← 标题行（图标+标题+耗时+状态）
│    云端构建通常 3–8 分钟，首次更久   │  ← 新增：ETA 行（弱化色）
│    variant=release · dsdzm/DSH-Watch │  ← 参数摘要（原有）
└──────────────────────────────────────┘
```

布局细节都是为手表小屏服务的：

| 属性 | 值 | 原因 |
|---|---|---|
| `start = 28.dp` | 与图标列对齐 | 标题行是「图标 14dp + 6dp 间距 + 文字」，`28dp` 让 ETA 与标题文字左对齐，视觉上属于同一块 |
| `labelSmall` + `outline` 色 | 比正文弱一档 | 它是预期说明，不是结果；不能抢「已完成/失败」的视觉权重 |
| `maxLines = 1` + `Ellipsis` | 强制单行 | 手表宽度只有 300–400dp，两行会把参数摘要挤出屏幕 |
| `top = 1.dp` | 极小的上间距 | 保持紧凑，避免卡片变高后一屏放不下两个工具调用 |

### 5.2 文案走 i18n 三处

`AppStrings.kt` 的结构是「接口 + 中文实现 + 英文实现」，新增字符串必须三处都改，**少一处编译不过**（接口成员未实现）—— 这是有意的：如果只在中文里加，英文用户会看到 key 或空白。

```kotlin
// 接口
val cloudBuildEta: String

// 中文
override val cloudBuildEta = "云端构建通常 3–8 分钟，首次要下依赖会更久"

// 英文
override val cloudBuildEta = "Cloud builds usually take 3–8 min; the first run is slower"
```

文案里的「首次要下依赖会更久」是有依据的：首次运行要下 Gradle distribution + 全部依赖，实测 10 分钟以上；命中 `actions/cache@v4` 之后稳定在 3–5 分钟。分开说，用户才不会在第一次构建时以为坏了。

---

## 6. lite 版能力收敛

### 6.1 探测机制：类存在性

`run_script` 是否真的能跑 Python，取决于安装包里有没有 Chaquopy 引擎。判定方式（`PythonBridge.kt:53,68`）：

```kotlin
const val PYTHON_CLASS = "com.chaquo.python.Python"

fun classPresent(): Boolean = runCatching { Class.forName(PYTHON_CLASS) }.isSuccess
```

为什么用 `Class.forName` 而不是别的：

| 方案 | 问题 |
|---|---|
| `BuildConfig.WITH_PYTHON` | 需要把 `-PwithPython` 一路传到 buildConfigField；`--offline` 复用旧 build 目录时会读到陈旧值 |
| `PackageManager` 查 native lib | 需要 Context，且 `PythonBridge` 是纯工具类，拿不到 Context 时无法判断 |
| 尝试初始化 Chaquopy | 会真的加载 native 库，成本高、可能抛 UnsatisfiedLinkError，不适合做「探测」 |
| **`Class.forName`** | 零副作用、无 Context 依赖、结果就是「包装里有没有这个类」，与真实可用性一一对应 |

`runCatching` 是必须的：lite 包里 `Class.forName` 会抛 `ClassNotFoundException`，不接住就是崩溃。

### 6.2 四个联动点

能力信息会通过**四条独立渠道**到达模型/用户，只改一条不够：

#### ① 系统提示（`ChatEngine.kt`）

```kotlin
// 能力清单必须按**本安装实际有的引擎**生成：lite 版没有 CPython，
// 若还告诉模型"可以跑 Python"，它就会去调、失败、再解释一遍 —— 用户看到的是
// "明明说支持却跑不了"。这里直接不给这个选项。
val pythonReady = PythonBridge.classPresent()
val pythonLine = if (pythonReady) {
    "- language=python：真 CPython（仅标准库）。"
} else {
    "- language=python：**本次安装不含 Python 引擎（lite 版），不要调用**；" +
        "需要 Python 就交给 cloud_build 在云端跑。"
}

// 在 raw string 里插值
- language=python：真 CPython（仅标准库；lite 版会明确告知不可用）。
$pythonLine
```

注意这里用的是 Kotlin raw string 的 `$pythonLine` 插值。原来的写法是「一句话兼容两版」（`lite 版会明确告知不可用`），信息量太低 —— 模型读到的仍是「有 python 这个选项」。改成条件生成后，lite 版的提示是**明确的禁止 + 替代方案**。

#### ② 工具描述（`ScriptTools.kt`）

```kotlin
private val pythonReady = PythonBridge.classPresent()

private val pythonNote = if (pythonReady) {
    "- language=python：真 CPython，仅标准库（full 版才有）。" +
        "**重要：shell 里没有 python / node / java / gcc 这些命令** —— …"
} else {
    "**重要：shell 里没有 python / node / java / gcc 这些命令** —— 手表上没有系统级运行时。" +
        "本安装不含 Python 引擎，**不要用 language=python**；需要 Python 就交给 cloud_build。"
}

override val description =
    "在当前工作目录里**真的执行**一段脚本，拿到真实的输出与报错。" +
        (if (pythonReady) "三种语言：" else "两种语言（lite 版没有 Python）：") +
        "- language=shell：…" +
        "- language=js：…" +
        pythonNote +
        "什么时候用：…"
```

实现上有一个坑值得记：`pythonReady` 与 `pythonNote` 必须**先定义成属性、再被 `description` 引用**，不能把 `if` 表达式直接塞进 `+` 链里 —— Kotlin 的 `if` 作为表达式与字符串 `+` 结合时，`else` 分支的优先级容易读错，写成一坨后维护者很难判断哪个分支包含了哪几行。拆成命名属性后，两版各自完整、也能被单元测试单独断言。

#### ③ 参数 enum（`ScriptTools.kt`）

```kotlin
"language" to Schema.str(
    "执行语言",
    enum = if (pythonReady) listOf("shell", "js", "python") else listOf("shell", "js"),
),
```

这一条最容易被忽略，但可能是**约束力最强**的一条：JSON Schema 的 `enum` 会进入 function calling 的参数约束，部分模型/后端会据此**拒绝生成** `"language": "python"`。也就是说，前面两条是「劝说」，这条是「硬拦」。

#### ④ 设置页（`ExecSettingsScreen.kt`）

```kotlin
val pythonReady = remember { PythonBridge.classPresent() }

if (pythonReady) {
    SwitchRow(
        title = "Python",
        subtitle = s.execPythonReady,
        checked = settings.allowPython,
        onCheckedChange = { on -> persist { it.copy(allowPython = on) } },
    )
} else {
    // lite 版没有解释器：摆一个永远跑不起来的开关只会让人以为能用
    Text(
        text = s.execPythonMissing,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
```

`remember { }` 是必要的：`classPresent()` 会走一次类加载，不该在每次重组时重复执行。放在 `remember` 里只算一次，而结果在进程生命周期内不会变（装了哪个包是固定的）。

### 6.3 为什么必须四处都改

模型对「自己有什么能力」的判断来自多个来源，任一来源给出相反信息，就会产生**试探性调用**：

```
系统提示说「可以跑 Python」 ─┐
工具描述说「三种语言」      ─┼─► 模型尝试 language=python
参数 enum 允许 "python"     ─┤      │
设置页摆着 Python 开关      ─┘      ▼
                              执行失败 → 模型解释「这个版本不支持」
                              → 用户看到：明明说支持却跑不了
```

只有四处**同时**收敛，模型才拿不到任何「可以试」的暗示。本质上这是**单一事实来源**问题：能力由 `PythonBridge.classPresent()` 唯一决定，所有对外表述都从它派生。

### 6.4 顺带修掉「本地编译」的暗示

lite 版还有一类误导：模型会把「本机静态校验」说成「我在本地编译」，用户就会以为手表上有编译器。修复是在系统提示里补一句：

```
写完代码后调一次 build_project。它会按语言做静态检查（括号与字符串闭合、
Python 缩进与冒号、HTML/XML 标签配对、JSON/YAML 语法等）并给出「文件:行号」。
这是你**在本机验证代码最快的手段**，不要写完就直接交付。
⚠️ 它只做静态校验，**不产出可执行文件** —— 手表上没有编译器，别说"我在本地编译"。
有报错就改完再调一次，直到通过。
```

这句话的作用不是限制模型，而是**校准它对用户的表述**：`build_project` 通过 ≠ 有产物。真需要产物就必须走 `cloud_build`。

---

## 7. 版本号与更新日志

三处同步，缺一就会出现「应用内显示 1.5、安装包是 1.4」这类对不上的情况：

| 位置 | 内容 |
|---|---|
| `app/build.gradle.kts` | `versionCode = 13`、`versionName = "1.5"` |
| `AppChangelog.kt` | 新增 1.5 条目（5 条 highlights，应用内「更新日志」页读取） |
| `CHANGELOG.md` | 新增 `## [1.5] — 2026-10-17（versionCode 13）` |

`AppChangelog` 的文案是**用户视角**（「提交后不再卡住」），`CHANGELOG.md` 是**维护者视角**（「`wait` 默认 false」）。两份都写，因为读者不同。

---

## 8. 验证记录

### 8.1 静态验证

| 项 | 结果 |
|---|---|
| 单元测试 | 522 个，全绿（含 `CloudWorkflowTemplateTest` 4 条） |
| 编译 | lite（`assembleRelease`）与 full（`-PwithPython=true`）均 `BUILD SUCCESSFUL` |
| 模板与仓库副本一致性 | `app/src/main/assets/cloud/dsh-build.yml` 与 `.github/workflows/dsh-build.yml` 用 `cp` 同步，避免两份漂移 |

### 8.2 包校验

```
lite: versionCode='13' versionName='1.5'  签名 70437a7f…bf31f94  无 libpython
full: versionCode='13' versionName='1.5'  签名 70437a7f…bf31f94  26 项 libpython/chaquopy
```

lite 包内 `libpython`/`chaquopy` 命中数为 **0**，full 为 **26** —— 这是「lite 真的没有引擎」的实证，也是 6.1 节探测机制能工作的前提。

### 8.3 真实云端验证

修复后用真实 API 触发了一次 `dsh-build.yml`（run `39004747580`）：**success**，产物 12.5MB。这是端到端验证 —— 不是「本地模拟通过」，而是 GitHub 的机器真的编出了 APK。

在此之前还做了三次本地模拟（对应 2.2 节的 A/B/C 场景），因为真实 run 一次要几分钟，先用本地模拟把明显错误排掉。

---

## 9. 已知限制与后续

| 限制 | 说明 | 可能的下一步 |
|---|---|---|
| Release 未含 debug 包 | v1.4 的 Release 有 4 个包（含 debug），v1.5 只发 2 个 release 包 | 需要时补构建并追加资产 |
| 签名密钥不入仓库 | `keystore.properties`、`*.jks` 被 ignore，云端只能出 debug 签名的包 | 用 GitHub Secrets 存 base64 keystore，在 workflow 里解码 |
| Actions 产物 14 天过期 | `retention-days: 14` | 需要长期留存就得下载到本地归档 |
| 首次构建慢 | 要下 Gradle + 依赖，10 分钟以上 | 已加 `actions/cache@v4`，第二次起 3–5 分钟 |
| `cloud_build` 的 `wait=true` 仍是同步的 | 保留给确实需要结果再继续的场景 | 目前够用，暂不改 |
| lite/full 能力差异靠运行时探测 | 没有编译期开关，四处的 `if (pythonReady)` 是运行时判断 | 若要进一步收敛，可用 Gradle 变体在编译期裁剪工具 |

---

## 附录 A：改动索引（文件:行）

| 机制 | 位置 |
|---|---|
| 轮询间隔常量 | `CloudTools.kt:54-56` |
| `wait` 默认值 | `CloudTools.kt:134` |
| 异步返回分支 | `CloudTools.kt:232-241` |
| run 定位（baseline id） | `CloudTools.kt:211-228` |
| `pollUntilSettled` | `CloudTools.kt:482-495` |
| `cloud_job` 四种动作 | `CloudTools.kt:312-325` |
| 模板：source_repo 输入 | `dsh-build.yml:28-32` |
| 模板：Locate project | `dsh-build.yml:48-69` |
| 模板：SDK 安装 | `dsh-build.yml:87-117` |
| 模板：Build（cd PROJECT_DIR） | `dsh-build.yml:127-137` |
| 模板：Collect APKs | `dsh-build.yml:139-145` |
| 系统提示 Python 行 | `ChatEngine.kt:1096-1102` |
| 静态校验说明补充 | `ChatEngine.kt:1125` |
| `run_script` 描述与 enum | `ScriptTools.kt:77-106` |
| 引擎探测 | `PythonBridge.kt:53,68` |
| 设置页开关分支 | `ExecSettingsScreen.kt:66,125-140` |
| ETA 提示 | `AgentUi.kt:165-175` |
| ETA 文案 | `AppStrings.kt:246,740,1147` |
| 版本号 | `app/build.gradle.kts:55-56` |

## 附录 B：故障对照表

遇到云端编译失败时，按这张表先定位阶段，再查对应小节：

| 报错关键词 | 失败阶段 | 根因 | 见 |
|---|---|---|---|
| `Failed to find package 'tools'` | 装 SDK | setup-android 装已下架包 | 2.1 |
| `chmod: cannot access './gradlew'` | 构建前 | 仓库没有源码 | 2.2 |
| `Unresolved reference 'build'` | 编译 | 源码包被 gitignore 吞掉 | 2.3 |
| `sdkmanager: command not found` | 装 SDK | cmdline-tools 路径变了 | 2.1（用 `find`） |
| `Unexpected inputs provided: ["command"]` | dispatch | 模板不接受该参数 | `CloudTools` 先查 `workflowInputs` |
| `Requested entity was not found` | dispatch | 分支名/仓库名不对 | `probeRepo` / `probeWorkflow` 两步探 |
| 触发成功但找不到 run | 定位 | dispatch 不返回 run id | 3.3 |
| 成功但没有产物 | 收产物 | 模板只传 `dsh-out/` | `dsh-run.yml` 约定 |

---

*文档对应提交 `12a18d2`。改动代码时请同步更新本文件与 `CHANGELOG.md`。*
