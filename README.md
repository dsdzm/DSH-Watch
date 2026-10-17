# DSH Watch —— 手表端第三方 DSH 客户端

面向 **Android 9（API 28）方形/矩形小屏手表**的第三方 DeepSeek Harness 客户端。
Kotlin + Jetpack Compose + **Material Design 3**，自带两套后端：**直连自配 API** 与 **连接电脑上的 DSH 服务器**。

> ⚠️ 关于来源的说明
> 你提到「基于 GitHub 上的 dshForandiord 修改」。我实际检索过：
> GitHub 上**不存在**名为 `dshForandiord` / `dshForAndroid` 的仓库（精确搜索 0 条结果）。
> 名字相近的有 `Hakunm/dsh-android-app`、`SCSpotato/dsh-remote`、`pavel-logachev/dsh-mobile` 等，
> 但它们都是**手机端**应用，没有为手表做适配，且 UI 直接搬运到 200dp 宽的表盘上不可用。
> 因此本工程**没有直接 fork 任何仓库**，而是按你给出的需求（手表 / MD3 / 双后端 / token 统计 / 性能适配）
> 从零实现了一套可编译的完整源码。如果你手上有具体的仓库地址，把它发我，我可以把其中的协议层对接到
> `net/backend/` 下的适配器里。

---

> **开发约定（重要）**
>
> **改完代码先本地构建 + 跑测试，产出 APK 放 `dist/` 交给作者验收；
> 作者确认没问题之后才推送 GitHub、才创建 Release。**
>
> 理由很实在：这个 App 是戴在手上的，很多问题（路径、时区、真机权限、体积）
> 只有装上去才会暴露。推一次就等于对所有人发布一次 —— v1.3 和 v1.4 都出现过
> 「发版当天连修三次」的情况，每次都在消耗用户对"这是稳定版"的信任。
>
> 所以流程固定为：
>
> 1. 改代码 → `./gradlew :app:testDebugUnitTest` 全绿
> 2. 构建四个包到 `dist/`，附 SHA256SUMS
> 3. **停在这里**，把包交给作者真机测试
> 4. 作者说"推" → 才 `push` + `gh release create/upload`
>
> 本地 commit 不受限制（方便回退），限制的只有**推送与发布**。
>
> **交付位置**：验收包一律用 `tools/upload_to_library.py` 上传到作者资料库，
> 回复里直接给当前版本的链接 —— 不再依赖产物面板（那里每轮构建都会堆卡片，
> 想找最新版得往下翻半天）。文件名固定为 `DSH-Watch-v<版本>-<变体>-release.apk`。

---

## 一、功能总览

| 能力 | 说明 |
| --- | --- |
| **Vibe Coding 对话** | 流式输出、思维链单独折叠展示、Markdown/代码块渲染、一键复制、重新生成、停止生成 |
| **系统提示词预设** | Vibe Coding / 极简 / 代码评审 / 排障 / 讲解 / 自定义，可再追加自定义指令 |
| **多会话管理** | 本地持久化、自动起标题、重命名、置顶、删除；按会话累计 token |
| **自配 API** | 任意 OpenAI 兼容服务（DeepSeek 官方、中转站、本地 Ollama…）；API Key 存在 Keystore 加密存储 |
| **连接 DSH 服务器** | 复用 dsh-workspace 的远程访问配对流程，一次性配对码换设备令牌 |
| **Token 用量看板** | 精确 usage 优先、缺失时本地估算并打标；今天/近 7 天/本月/全部；按模型、按天柱状图；按单价估算花费 |
| **MD3 设计** | 完整 Material 3 配色（Android 12+ 支持动态取色）、MD3 组件与圆角体系、深浅色主题 |
| **手表性能适配** | 设备自动分档 + 手动覆盖，见第五节 |
| **后台不断流** | 生成期间前台服务 + 通知栏停止按钮，熄屏继续跑 |

---

## 二、环境要求

| 项 | 版本 |
| --- | --- |
| JDK | **17**（AGP 8.x 强制要求，JDK 21 也可） |
| Android Studio | Ladybug (2024.2) 或更新 |
| Android Gradle Plugin | 8.7.3（已写在 `gradle/libs.versions.toml`） |
| Gradle | 8.11.1（wrapper 已随工程附带，无需手动装） |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.10.01 |
| compileSdk / targetSdk / **minSdk** | 35 / 34 / **28** |

`minSdk = 28` 对应 Android 9.0，这是你要求的适配下限。

> 本工程**已在真实环境完整编译验证通过**：Android SDK Build-Tools 35.0.0、Platform 35、
> AGP 8.7.3、Gradle 8.11.1、JDK 20（AGP 8.x 要求 ≥17，JDK 20/21 均可）。

---

## 三、构建

### 3.0 已经构建好了，可以直接装

`dist/` 里是本次真实构建产物（不是占位文件）：

| 文件 | 大小 | 说明 |
| --- | --- | --- |
| `dist/DSH-Watch-v1.3-lite-release.apk` | **6.3 MB** | 推荐（不含 Python）。R8 + 资源压缩 + v1/v2/v3 全签名 |
| `dist/DSH-Watch-v1.3-full-release.apk` | **26.5 MB** | 含 Python 引擎（CPython 3.11），仅 arm64-v8a |
| `dist/DSH-Watch-v1.3-lite-debug.apk` | 32 MB | 未压缩，包名带 `.debug`，可与正式版共存 |
| `dist/DSH-Watch-v1.3-full-debug.apk` | 52 MB | 同上，含 Python（两个 ABI，便于模拟器调试） |
| `dist/SHA256SUMS.txt` | — | 校验和 |

lite 与 full **同一包名、同一签名**，可互相覆盖安装、数据不丢。

安装（手表开 USB/Wi-Fi 调试后）：

```bash
adb install -r dist/DSH-Watch-v1.3-lite-release.apk
```

没有 adb 的话，把 APK 传到手表上，用文件管理器点开安装（需允许「安装未知来源应用」）。

**本次构建验证结果**（Android SDK Build-Tools 35.0.0 / AGP 8.7.3 / Gradle 8.11.1）：

```
> Task :app:testDebugUnitTest  BUILD SUCCESSFUL   （352 个测试全部通过）
> Task :app:assembleRelease                  (lite) BUILD SUCCESSFUL   6.3 MB
> Task :app:assembleRelease -PwithPython     (full) BUILD SUCCESSFUL  26.5 MB

package       com.dshwatch   v1.3 (versionCode 11)
minSdk        28   ← Android 9
targetSdk     34
签名          v1(JAR)=✓  v2=✓  v3=✓   签名者 CN=DSH Watch（与 1.0/1.2 同一密钥）
full 包内容    libpython3.11.so + Chaquopy 运行时（arm64-v8a）
lite 包内容    无任何 libpython / chaquopy（已解包核对）
测试分布      ChatEngine 21 · 工作空间存储 25 · 源码校验 24 · 构建器 19 · 执行引擎 53
              · 路径归一化 15 · 表达式求值 17 · 协议序列 10 · 工具表 9 · 云端 31
              · 性能档 9 · Markdown 8 · SSE 6 · Token 估算 6 · 用量聚合 6
              · 工具名映射 4 · 线程上下文 3 · 产物透传 3 · 参数解析 9
```

> 关于「apksigner verify 只显示 v3=true」：那是因为 apksigner 默认只报告该 APK 的 minSdk 所**必需**的方案。
> 加 `--min-sdk-version 23` 再验一次就会看到 v1/v2/v3 全为 true（已实测），三种方案确实都写进了包里。

### 3.0.1 连不上？先跑「设置 → 诊断」

诊断页会依次验证四步，并给出**每一步的原始结果**（HTTP 状态码、响应正文、异常类名）：

1. **配置检查** — 地址、Key、模型有没有填全，地址有没有漏 `http(s)://`
2. **模型列表** — `GET /models`
3. **对话接口** — `POST /chat/completions`（非流式）
4. **流式输出** — `POST /chat/completions`（SSE，**这一步才是发消息真正走的路径**）

哪一步标红，问题就在哪一层。页面底部有「复制全部结果」，可以直接把原文发出来定位。

### 3.1 命令行（推荐，可复现）

```bash
cd dsh-watch

# Windows 用 gradlew.bat
./gradlew :app:assembleDebug

# 产物
# app/build/outputs/apk/debug/app-debug.apk
```

#### 两种安装包：lite 与 full

Python 引擎（CPython 约 20MB）是**可选**的，所以从构建入口分两个包：

```bash
# lite：不含 Python，约 7MB（shell + JS 执行仍可用）
./gradlew :app:assembleRelease

# full：含 Python 引擎，约 30MB（仅 arm64-v8a）
./gradlew :app:assembleRelease -PwithPython=true
```

两个包**同一包名、同一签名**，可以互相覆盖安装，数据不丢。

> 实现上为什么用 Gradle 属性而不是 product flavor：Chaquopy 是模块级插件，
> 一旦 apply 就会给**所有变体**塞进 libpython，官方没有「某个变体不打包」的开关。
> 所以改成按属性条件 apply 插件，并且**对 Chaquopy 的调用全走反射**
> （见 `domain/agent/exec/ReflectionPythonEngine.kt`）——
> 这样同一份 main 源码集在两种构建下都能编过，不需要维护两套源码目录。
>
> **首次构建 full 包时如果报「Could not find target-x.y.z-...zip」**：
> 那是镜像对未缓存产物的首次请求失败（文件其实存在），加 `--refresh-dependencies` 重跑即可。

安装到手表（手表需开启开发者模式 + USB/Wi-Fi 调试）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3.2 Android Studio

1. `File → Open`，选中 `dsh-watch` 目录（不是里面的 `app`）；
2. 等 Gradle Sync 完成（首次会下载依赖，约 200–400MB）；
3. 直接点 ▶ Run。

### 3.3 国内网络

`settings.gradle.kts` 里已把**阿里云镜像**放在 google()/mavenCentral() 之前，国内网络开箱可用。
如果镜像导致某些依赖解析失败，删掉 `pluginManagement` 与 `dependencyResolutionManagement`
里的 `maven("https://maven.aliyun.com/...")` 三行即可。

**Gradle 发行版本身**是从 `services.gradle.org` 拉的，国内可能很慢甚至超时（我在构建时实测只有 ~450KB/s，
默认 10 秒的 `networkTimeout` 会直接失败）。两个办法：

1. 用国内镜像直接下好再放进去（推荐，14MB/s）：
   ```bash
   curl -L -o /tmp/gradle.zip https://mirrors.huaweicloud.com/gradle/gradle-8.11.1-bin.zip
   ```
   然后把 `distributionUrl` 换成这个华为镜像地址即可（或把 zip 手动解压到
   `~/.gradle/wrapper/dists/gradle-8.11.1-bin/<hash>/` 并创建 `gradle-8.11.1-bin.zip.ok`）。
2. 本工程已把 `networkTimeout` 从默认 10 秒调到 **120 秒**，慢速网络下也能自己拉完。

### 3.4 正式签名

本工程**不包含签名密钥** —— `.gitignore` 已排除 `*.jks` / `*.keystore` / `keystore.properties`，
密钥与口令只保存在你自己的机器上，不会进入仓库。

要产出可安装的正式包，在自己机器上创建 `keystore.properties`：

```properties
storeFile=../dsh-watch.jks        # 指向你自己的密钥文件（切勿提交）
storePassword=<你的密钥库口令>
keyAlias=dsh
keyPassword=<你的密钥口令>
```

**密钥安全（比口令更要紧的是文件本身）**

- Android 要求同包名的更新**必须使用同一套密钥** —— 密钥文件一旦丢失，
  就再也无法给已安装用户推送更新（只能改包名重发，或让用户卸载重装、数据全丢）；
  请务必备份到项目之外
- 口令泄露 + 密钥文件泄露 = 他人可以伪造"同签名"的更新包，
  让老用户在正常升级流程里装上恶意版本
- 建议把 `dsh-watch.jks` 与口令交给密码管理器保存，不要放进任何代码仓库或云盘共享目录

`dsh-watch.jks` 证书指纹（SHA-256，**公开信息**，可用于校验某个 APK 是否由本密钥签名）：

`70:43:7A:7F:33:BE:37:46:BB:DA:17:9A:A1:5B:8C:F2:56:A3:27:68:C9:88:34:AD:DA:C9:8B:42:8B:F3:1F:94`

> 没有 `keystore.properties` 时 release 仍可构建，只是产出未签名 APK。
> 提交前可用 `git check-ignore -v dsh-watch.jks keystore.properties` 确认这两个文件确实被忽略。

---

## 四、首次使用

### 4.1 方式 A：直连自己的 API（手表可独立使用）

1. 打开 App → 右下角 **设置 → 供应商 → +**；
2. 类型选 **直连 API**；
3. 填写：
   - **接口地址**：`https://api.deepseek.com/v1`（注意要带 `/v1`；中转站按服务商给的填）
   - **API Key**：`sk-...`
4. 点 **从服务端拉取** 拿模型列表，或在「手动添加模型」里输入 `deepseek-chat`、`deepseek-reasoner` 等；
5. 点 **测试连接** 确认通了，再点底部 **保存**；
6. 回对话页，点底部输入框开始聊天。

> 提示词默认是 **Vibe Coding** 预设：少客套、直接给可运行的完整代码。

### 4.2 方式 B：连接电脑上的 DSH

电脑端：

1. 在 DSH WebUI 里启用 **dsh-workspace** 插件；
2. 打开「工作区设置」，至少添加一个授权根目录；
3. 打开「远程访问」，填绑定 IP 与端口并保存监听；
4. 点「启用并创建配对」，拿到 **10 分钟有效的一次性配对码**。

手表端：

1. 在供应商里新增一个，类型选 **DSH 服务器**，或直接进 **设置 → 配对 DSH 服务器**；
2. 填服务器地址（如 `http://192.168.1.20:3090`）、配对码、设备名；
3. 点 **配对并连接**，成功后设备令牌写入加密存储。

> 协议适配位置：`app/src/main/java/com/dshwatch/net/backend/DshServerBackend.kt`
> 里的 `Endpoints` 对象集中了所有路径；字段名集中在 `net/model/Wire.kt` 的 `Dsh*` 数据结构。
> 如果你的 dsh-workspace 小版本字段对不上，**只改这两个地方**，UI 与业务层不用动。
> 流式解析器同时兼容「DSH 原生事件」和「OpenAI chunk」两种形态，所以服务端直接复用 OpenAI 协议也能跑。

---

## 五、手表性能适配

手表真正的瓶颈不是算力，是 **256MB–1GB 内存 + 1.4 寸屏 + 400mAh 电池**。
所以核心策略是「少渲染、少重排、少唤醒」。App 启动时会按内存档位、核心数、是否手表、Android 版本
自动分档（`PerfDetector`），每一档对应一组渲染参数：

| 参数 | 低功耗手表 | 中等配置 | 高配置 |
| --- | --- | --- | --- |
| 列表最多渲染消息数 | 30 | 80 | 200 |
| 流式刷新间隔 | 220ms | 130ms | 70ms |
| Markdown 块数上限 | 120 | 400 | 1200 |
| 代码块默认折叠行数 | 40 | 80 | 200 |
| 语法着色 | 关 | 关 | 开 |
| 动画与闪烁光标 | 关 | 开 | 开 |
| 单次请求携带历史条数 | 16 | 24 | 40 |

用户可在 **设置 → 性能** 里覆盖：整体档位（自动/省电/均衡/性能），以及单独覆盖「列表渲染上限」
和「流式刷新间隔」。设置页底部实时显示当前生效的参数。

再配合这些实现层面的优化：

- **流式节流**：增量先进 `StringBuilder`，按间隔合并成一次 State 更新；正在生成的那条消息走独立的
  `LiveDelta` 流，**不会**每次 token 都重建整个消息列表；
- **无 WebView 的 Markdown 渲染**：自研解析器 + `AnnotatedString`，省掉 30–60MB 的 WebView 常驻内存；
- **无 Room**：会话用「一会话一 JSON + index.json」+ 临时文件 rename 原子写，去掉注解处理器与额外依赖；
- **手写导航栈**：不引 navigation-compose，少约 300KB 依赖和一层转场动画；
- **R8 + 资源压缩**：release 默认开启，并移除 `android.util.Log`；
- **前台服务**：只在生成期间挂起，结束即撤，避免常驻耗电。

---

## 六、Token 用量怎么算的

1. **优先真实 usage**：请求里带 `stream_options.include_usage`，从流式响应末尾的 `usage` 字段读取
   `prompt_tokens` / `completion_tokens` / `cached_tokens`（同时兼容 DeepSeek 的
   `prompt_cache_hit_tokens` 与 OpenAI 的 `prompt_tokens_details.cached_tokens`）。
   若服务端因为不认识 `stream_options` 返回 400，会自动去掉该字段重试一次。
2. **兜底估算**：服务端完全不返回 usage 时，用本地 `TokenEstimator` 估算
   （CJK 约 1 token/字，拉丁约 4 字符/token），并在记录上打 `estimated = true`，界面上标注「估算」。
3. **费用**：按供应商里配置的输入/输出单价（元 / 百万 tokens）折算，计入每条记录。
4. **聚合维度**：今天 / 近 7 天 / 本月 / 全部；按模型分解；按天柱状图；请求数、缓存命中量。

数据落在 `filesDir/usage/usage.json`，保留上限 20,000 条，超出后裁剪到 10,000 条。

---

## 七、工程结构

```
app/src/main/java/com/dshwatch/
├─ DshWatchApp.kt              Application：装配容器、把生成状态接到前台服务
├─ MainActivity.kt             唯一 Activity，纯 Compose
├─ core/
│  ├─ AppContainer.kt          手写 DI 容器（不用 Hilt/Koin）
│  ├─ Jsonx.kt / Ids.kt / Logx.kt
├─ domain/
│  ├─ ChatEngine.kt            应用级对话引擎：流式采集、节流、会话落盘、用量记账
│  ├─ TokenEstimator.kt        token 估算 + 上下文预算裁剪
│  ├─ PerfProfile.kt           设备分档与性能档参数
│  ├─ PromptPresets.kt         Vibe Coding 等系统提示词预设
│  └─ model/                   ChatMessage / Conversation / Provider / TokenUsage
├─ data/
│  ├─ settings/                DataStore 设置仓库 + Keystore 加密的 Key 存储
│  ├─ store/ConversationStore  会话 JSON 持久化
│  └─ usage/UsageRepository    用量记录与聚合（聚合函数是纯函数，可单测）
├─ net/
│  ├─ Http.kt                  手表定制的 OkHttp 客户端
│  ├─ SseReader.kt             手写 SSE 解析
│  ├─ model/Wire.kt            协议 wire 结构（含 DSH 专属字段）
│  └─ backend/
│     ├─ ChatBackend.kt        后端抽象 + 错误模型
│     ├─ OpenAiCompatBackend.kt  直连模式
│     ├─ DshServerBackend.kt     DSH 服务器模式（含配对）
│     └─ BackendRegistry.kt
├─ service/StreamingService.kt 流式期间的前台服务
├─ ui/
│  ├─ AppRoot.kt / nav/        轻量导航栈
│  ├─ theme/                   MD3 配色、手表字号、尺寸常量
│  ├─ i18n/AppStrings.kt       中/英文案（接口实现，漏字段直接编译不过）
│  ├─ components/              MD3 通用组件（顶栏、卡片、分段选择、步进器…）
│  ├─ markdown/                自研 Markdown 解析 + 行内样式 + 轻量代码着色
│  └─ screens/                 chat / sessions / settings / provider / pair / usage / about / input
└─ src/test/java/com/dshwatch/ 单元测试（Markdown、token 估算、性能档、SSE、用量聚合）
```

---

## 八、常见问题

**Q：点「测试连接」通过，但发消息就是失败。**
这正是 v1.0.1 修掉的一个坑：旧版的「测试连接」只测了 `GET /models`，而聊天走的是
`POST /chat/completions`，两条路径完全不同，所以会出现「测试通过但用不了」。
现在测试连接会连同对话接口和流式输出一起测。如果仍有问题，去 **设置 → 诊断** 跑一遍，
把标红那一步的原文发出来即可定位。

**Q：手表熄屏后生成就断了。**
设置 → 行为 → 打开「后台保持连接」。v1.0.1 起该开关除了挂前台服务，还会在生成期间持一个
**短时唤醒锁**——因为前台服务只能保证进程不被杀，**不能阻止 CPU 休眠**，
手表进 suspend 后 socket 会被挂住，表现就是「一黑屏就报网络错误」。唤醒锁最长 15 分钟，自动释放。

**Q：换了系统预设，但回答风格没变。**
v1.0.1 修复：旧版把预设取自一个从未被写入的全局字段，导致在供应商里换预设完全不生效；
现在取的是供应商自己的 `presetId`。

**Q：配了两个供应商，改了 B 但聊天好像还在用 A。**
v1.0.1 起供应商列表每项都有「设为当前」，且列表顶部会直接写明「聊天固定使用「X」」。
旧版只能在编辑页切换，而那个按钮不落盘。

**Q：装到手表上字太小 / 太大？**
设置 → 外观 → 字号，提供 小 / 标准 / 大 三档（0.85 / 0.92 / 1.12），实时生效。

**Q：安装报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 签名不一致？**
说明表上已经装了另一个签名的同包名应用。先 `adb uninstall com.dshwatch` 再装。
注意 debug 包的包名是 `com.dshwatch.debug`，和正式包**可以共存**，不会互相覆盖。

**Q：安装报 `INSTALL_FAILED_OLDER_SDK`？**
手表系统低于 Android 9。本包 `minSdkVersion=28`，Android 8 及以下装不上——
这是你要求的适配下限，要改的话把 `app/build.gradle.kts` 里的 `minSdk` 调低，
但 Compose 与 Keystore 加密存储的行为需要重新验证。

**Q：手表上没有 Google 服务，能装吗？**
能。本应用**不依赖任何 Google 服务**（没有 GMS、没有 Firebase、没有 Wearable Data Layer），
是纯 AOSP 应用，国产 Android 手表同样可用。

**Q：熄屏后生成断了。**
设置 → 行为 → 打开「后台保持连接」。另外部分国产表会额外杀后台，需要在系统设置里给本 App
加白名单/允许自启动。

**Q：401 / 404 报错。**
401 检查 API Key 是否有多余空格；404 通常是 Base URL 少了 `/v1`。

**Q：没有 usage，用量显示「估算」。**
你的服务端没返回 usage。打开供应商里的「请求精确用量」开关；若服务端不支持
`stream_options`，App 会自动去掉重试，此时只能用估算值。

**Q：想加英文/其他语言？**
`ui/i18n/AppStrings.kt` 里实现一个新的 `AppStrings` 对象，再改 `defaultStrings()` 的分支。
因为是接口实现，漏掉任何一个字段都会**编译失败**，不会等到运行时才崩。

**Q：方形表和圆形表都想支持？**
当前按方形/矩形优化（你选的）。圆形表盘要在 `ui/theme/Theme.kt` 增加 `WindowSizeClass`/表盘形状判断，
并把 `WatchScaffold` 的顶部与底部区域做内收。

---

## 九、免责声明

- 本项目是非官方第三方客户端，与 DeepSeek 官方无任何关系。
- API Key 只保存在本机（优先 Android Keystore 加密存储；部分定制 ROM 不支持 Tink 时会降级为普通存储并在日志中提示）。
- 走 A 模式时，聊天内容会直接发往你配置的第三方接口；走 B 模式时，数据只在你的手表与你自己的电脑之间传输。
- 使用 HTTP 明文地址只适合可信局域网，跨公网请务必上 HTTPS 或 Tailscale/WireGuard。

---

## 十、更新日志

### v1.0.1

修了 5 个真实缺陷，其中第 1 条会导致**全新安装后完全没法用**。

**1. 【致命】首次发消息被自己取消 —— `ChatEngine.startNew` 自取消**

调用链：

```
send() → 启动 job → runSend()
                     → _conversation.value == null（全新安装还没会话）
                     → startNew()
                         → if (job?.isActive == true) stop()   ← 这个 job 就是 runSend 自己
                             → backends.cancelAll() + job.cancel()   ← 把自己杀了
```

结果：首条消息在发出 HTTP 请求之前就被取消，界面上**没有任何反应、也没有报错**。
聊天页的「+」新建会话按钮同样中招。

修复：`startNew()` 不再取消任何东西；需要打断生成时由 UI 层显式先 `stop()`，
并新增 `newConversation()` 作为 UI 专用入口。同时给 `runSend` 加了 `guardedRun` 兜底，
任何未捕获异常都会转成界面提示，不再被 `SupervisorJob` 静默吞掉。

回归测试：`ChatEngineTest.first message on a fresh engine is not self-cancelled`。
实测把旧代码放回去，8 个用例里 5 个变红，该用例精确指向「后端调用次数应为 1，实际为 0」。

**2. 【重要】测试连接没覆盖聊天路径**

旧实现只 `GET /models` 就返回「连通」，而聊天走 `POST /chat/completions`。
于是「测试通过但发消息失败」——正是这个版本要解决的问题。
现在测试连接与诊断共用同一套代码路径（同一个 OkHttpClient、同一个请求体构造器），
保证「测的就是发的」。

**3. 【重要】错误信息把底层原因吞掉了**

`DshError.Network` 只显示「网络不可达」，界面又不展示 `message`，
于是 DNS 失败、TLS 失败、明文 HTTP 被拦截、超时，用户看到的全都一样，无法定位。
现在统一由 `describeForUser()` 输出「结论 + 底层异常类名与 message + 处理建议」。

**4. 预设不生效 / 供应商切换不落盘**

- 系统提示词预设取自 `settings.activePresetId`——这个字段界面上从来没写过，永远是默认值。
  改为取 `provider.presetId`。
- 编辑页的「设为当前」只写了 `activeProviderId`、没保存供应商本身，
  未点保存时 `activeProviderId` 会指向一个不存在的 id，被 fallback 到「第一个供应商」。
- 供应商列表新增「设为当前」直接操作，并在顶部标明「聊天固定使用「X」」。

**5. 密钥存储静默失败 / 局域网明文被系统拦截**

- `SecureKeyStore` 把加解密异常吞掉了，Keystore 主密钥失效时表现为「Key 明明填过却提示为空」。
  现在读写都有异常捕获、失败自动降级到普通存储、状态可在诊断页查看。
- Manifest 未声明 `usesCleartextTraffic`，`targetSdk >= 28` 时系统默认禁止明文，
  导致 README 里推荐的 `http://192.168.1.20:3090`（DSH 局域网模式）直接抛 IOException。
  已补 `network_security_config.xml` 允许明文。

**6. 其他**

- 流式期间新增短时 `PARTIAL_WAKE_LOCK`（最长 15 分钟），修复熄屏断流。
- 新增 **设置 → 诊断** 页：四步自检 + 原始报错 + 一键复制报告。
- 新增 `ChatEngineTest`（8 个用例）；为可测性抽出 `SettingsSource` / `CredentialSource` 接口，
  存储层调度器改为可注入。测试总数 32 → 40。

### v1.0.0

首个版本：双后端（直连 API / DSH 服务器）、MD3、手表性能分档、token 用量看板。

---

## 十一、v1.1.0 —— 本地 Agent + 设备适配

### 11.1 Agent：手表上直接跑工具循环

模型返回 `tool_calls` → **手表本地执行工具** → 结果回灌 → 再问模型，直到给出最终回答
或达到步数上限。**不依赖电脑**，直连自己的 API Key 就能用。

内置 11 个工具：

| 工具 | 副作用 | 说明 |
| --- | --- | --- |
| `get_datetime` | 只读 | 本地时间、星期、时区、Unix 时间戳 |
| `calculate` | 只读 | 表达式求值（自研递归下降解析器，非脚本引擎） |
| `get_device_info` | 只读 | 性能档、电量、剩余存储、内存、屏幕、系统版本 |
| `get_token_usage` | 只读 | 今天 / 近 7 天 / 全部的 token 与估算花费 |
| `list_notes` / `read_note` | 只读 | 读取本地笔记 |
| `save_note` / `delete_note` | 写操作 | 写本地笔记（agent 的跨会话记忆） |
| `http_fetch` | 联网 | GET 一个 URL 并返回正文（截断），用于查公开 API |
| `set_alarm` | 写操作 | 在手表上设闹钟，到点由本 App 发通知 + 振动 |
| `write_clipboard` | 写操作 | 写系统剪贴板 |

**审批策略**（聊天页顶部 `Agent` 胶囊或「设置 → AI Agent」随时可改）：

- `关闭` —— 退化成纯对话，请求里不带 tools
- `每步都问` —— 每次调用都弹窗
- `只读自动` —— `get_datetime`/`calculate`/读笔记等直接跑，联网与写操作仍然问
- `全自动`（默认）—— 全部直接执行

配套机制：

- **步数上限**（默认 8，可 1–20），防止在手表上无限循环烧 token；
- **拒绝的处理**：拒绝结果会回灌给模型让它换方案，但**同一个工具不会重复弹窗**；
  如果模型仍坚持，任务直接中止；
- **工具失败不中断**：不存在的工具名、工具抛异常，都会转成失败结果回灌，
  让模型自我纠正，而不是整个任务挂掉；
- **每个助手消息各自携带工具卡片**：工具名、参数、状态（待执行/等待确认/执行中/成功/失败/已拒绝）、
  耗时、结果（可展开、可复制），全都会落盘，重进 App 还在。

流式 `tool_calls` 的处理值得单独说一句：OpenAI 兼容协议里 tool_calls 是**按 index 分片**下发的，
第一片带 `id` 和 `function.name`，后续片只带 `function.arguments` 的字符串片段。
必须按 index 归并、把 arguments 拼起来才能得到完整参数——这一点有专门的单测钉死。

### 11.2 设备适配：4+64 / 410×502 / 大 R 角

**性能档位重写。** 旧逻辑用 `memoryClass + 是手表 + Android 版本` 三个惩罚项打分，
你的 4GB 手表会被误判成低功耗档，白关掉语法着色、把渲染条数砍到 30。
现在只看两个硬指标：

| 内存 | 核心 | 档位 |
| --- | --- | --- |
| ≥3GB | ≥4 核 | **HIGH**（你的设备：4GB + 4 核 → HIGH） |
| 1.5–3GB | 6–8 核 | HIGH |
| 1GB | 4 核 | MID |
| ≤512MB 或 lowRamDevice | — | LOW |

HIGH 档下：渲染 200 条消息、流式节流 70ms、**开启代码语法着色**、开启动画。

**屏幕 R 角。** 系统的 `DisplayCutout` **只报告刘海/挖孔，不报告圆角** —— 圆角是硬件遮罩，
App 拿不到半径。所以做法是：按屏宽估算 + 允许手动微调。

估算的几何依据（这也是为什么实现成「先加纵向内边距，再加一点横向」）：
设圆角半径为 R，内容盒从 y=0 开始，点 (x, y) 被遮住的判定是
`(x-R)² + (y-R)² > R² 且 x<R 且 y<R`。最坏情况是 y=0 那一行，此时需要 `x ≥ R`；
但如果内容上边距留了 p，需要的横向内收就降到 `R - sqrt(R² - (R-p)²)`。
R=25dp、p=6dp 时只要 8.8dp —— 远比直接缩进一个 R 划算（后者在 205dp 宽的表盘上要白白浪费 1/4 宽度）。

落地方式：顶栏 / 底栏统一 `cornerSafe()`（6dp 纵向 + 自动估算的横向内收），
并给它们各自加了圆角背景，视觉上跟着屏幕弧度走。
410px ÷ 2x = 205dp → 自动值 16dp。可在 **设置 → 外观 → 屏幕圆角安全边距** 里按 4dp 步进微调，或点「恢复默认」回到自动。

### 11.3 测试

81 个单测，覆盖：

- **ChatEngine 20 个**：首条消息不被自取消、预设生效、Agent 循环端到端、
  tool_calls 分片拼接、步数上限、审批挂起与恢复、拒绝回灌、未知工具、工具抛异常、
  Agent 开关、落盘重启后可恢复
- **表达式求值 17 个**：运算优先级、结合性、函数、常量、科学计数法、
  除零/未知函数/括号不配对/**注入 `System.exit(0)` 被拒绝**
- **工具注册表 9 个**：名字唯一、JSON Schema 合法、spec 能 JSON 往返、
  工具名归一化、参数读取容错
- 其余：Markdown 8 · 性能档 9 · SSE 6 · Token 估算 6 · 用量聚合 6

---

## 十二、v1.2.0 —— 修复 tool_calls 协议 400 + 本地工作空间

### 12.1 【致命】修复 `tool_calls` 消息序列非法导致的 400

**现象**：Agent 用了一次工具后，下一轮请求被服务端拒绝：

```
请求被拒绝（400）
{"error":{"message":"An assistant message with 'tool_calls' must be followed by tool
messages responding to each 'tool_call.id'. (insufficient tool messages following
tool_calls message)","type":"invalid_request_error"}}
```

**根因是历史裁剪把消息顺序弄反了。** `TokenEstimator.trimToBudget` 里这一段：

```kotlin
for (k in (i + 1)..start) kept.addFirst(history[k])
```

`addFirst` 在 `k` 递增时插入 → **组内顺序反转**。于是发出去的历史变成
`[user, tool结果, assistant(带tool_calls)]`，而协议要求 `assistant.tool_calls` **后面必须紧跟**
配对的 `tool` 消息，服务端直接拒。

第二处同类问题：`ChatMessage.toTurn()` 把存盘的助手消息翻译成 `assistant.tool_calls`，
但**没有同时生成配对的 `role=tool` 消息** —— 所以同一会话里发第二条消息时必然 400。

**修了三层**（每一层单独都够用，这里是纵深防御）：

1. **裁剪器**改写为「原子组」逻辑：把 `[assistant(tool_calls), tool, tool…]` 当成不可分割的一组，
   整组保留或整组丢弃；孤立的 `tool` 消息直接剔除。用 `addAll(0, group)` 而不是逐个 `addFirst`。
2. **历史展开** `toTurns()` 把带工具调用的助手消息展开成 `1 + N` 条
   （1 条 assistant + 每个已落定调用各 1 条 tool）；不足的调用不进历史，避免"有调用没结果"。
3. **出站兜底** `TurnSanitizer` 在 `buildBody` 里强制重建序列：缺结果的补占位、
   孤儿消息丢弃、重复结果去重。**不管上游怎么错，发出去的请求一定合法。**

同时删掉了 `requestHistoryLimit` 的 `takeLast()` —— 它会把消息组从中间切断，改成组感知的 `maxTurns`。

新增 10 个 `TurnSanitizerTest`（含「反转的 pair 被修复」「sanitize 幂等」）
和 2 个 `ChatEngineTest` 端到端用例（同会话连续两轮、出站序列必须通过 `validate`）。

### 12.2 剪贴板工具已移除，改为本地工作空间

按你的要求删掉了 `write_clipboard`（连带删掉 4 个笔记工具 —— 有了文件系统，键值笔记是多余的抽象）。

现在 agent 会在**应用私有的沙盒目录** `files/workspace` 里真正建文件：

| 工具 | 副作用 | 说明 |
| --- | --- | --- |
| `list_files` | 只读 | 递归列出工作空间所有文件 |
| `read_file` | 只读 | 读取文件（最多 24000 字） |
| `write_file` | 写操作 | 创建/覆盖文件，目录自动创建 |
| `append_file` | 写操作 | 追加内容，适合分几次写长文件 |
| `make_dir` | 写操作 | 创建目录 |
| `delete_file` | 写操作 | 删除文件或目录 |
| `build_project` | 写操作 | **校验 + 打包**，见下 |

安全边界（路径是模型给的，属于不可信输入）：拒绝 `../` 穿越、拒绝绝对路径、
用 `canonicalPath` 前缀校验挡住符号链接跳出、拒绝控制字符；
单文件 256KB、工作空间 400 个文件 / 32MB 上限。13 个 `WorkspaceStoreTest` 专门盯着这些。

### 12.3 「编译」在手表上是什么

手表上没有 Kotlin/Java 编译器，所以 `build_project` 被定义为两件**确实有意义**的事：

**① 静态校验**（`SourceValidator`）—— 有错误就**不产出**，直接把问题清单回灌给模型去修：

- **JSON**：真解析，报错定位到行
- **HTML/XML**：标签配对扫描（自己写，不用 `DocumentBuilder`，顺带避免 XXE）；
  `<script>`/`<style>` 内的内容按原始文本处理，不会被误判成标签；
  允许省略闭合的元素（`li`/`p`/`td`…）只报警告
- **CSS**：注释/字符串感知的括号配对
- **JS**：未闭合的字符串/注释判**错误**（高置信）；括号配对只判**警告** —— 没有真正的解析器，
  正则字面量里的括号会误报，所以不装懂，并在给模型的说明里写清楚了

**② 打包**（`ProjectBuilder`）—— 把入口页引用的本地 CSS / JS / 图片**内联成单文件 HTML**，
输出到 `dist/`。手表上没有浏览器、没有相对路径文件服务，不内联产物就打不开。
`http(s)://`、`//cdn`、`data:` 一律原样保留，不会被误当成工作空间文件。

### 12.4 工作空间界面

**设置 → 工作空间**，或聊天页顶部在成功构建过后出现的 `工作空间` 胶囊。

- 按「全部 / 产物 / 源码」筛选，显示大小与修改时间
- 点文件进预览：HTML 与图片走 WebView（**按需创建、离开即 destroy**，不留常驻内存），
  其它文本用等宽字体直接显示
- 「构建」按钮可以直接在界面里跑一次 `build_project`，结果就地显示
- 「导出」走 FileProvider 分享给别的 App（只暴露 `files/workspace/`，API Key 那些不在范围内）

### 12.5 工程配置

`gradle.properties` 把 `org.gradle.jvmargs` 从 3G 提到 **4G**，并加了 `org.gradle.workers.max=4`。
原来的配置在 release 的 R8 + 资源压缩阶段会被 OOM kill（实测报
`Gradle build daemon disappeared unexpectedly`）。

---

## 十三、v1.3.0 —— 修「找不到入口文件」+ 文件存放位置可自定义

### 13.1 【致命】路径没归一化，文件被写进了 6 层嵌套目录

**现象**（你截图里的那个）：

```
构建失败｜入口 index.html
错误 1 个；
[错误] index.html:1 找不到入口文件。当前工作空间里的文件：
        data/data/com.dshwatch.debug/files/workspace/index.html
```

看起来很费解：文件明明叫 index.html，工作空间里也「有」一个 index.html，为什么找不到？

**根因**：模型不知道自己在一个什么样的沙盒里，于是把「文件该放哪」表达成绝对路径传了进来：

```
write_file(path = "data/data/com.dshwatch.debug/files/workspace/index.html")
```

注意这串**没有前导斜杠**。它在语法上是一个完全合法的**相对路径**，
于是旧的 `resolve()` 检查（只拒绝 `/` 开头的和含 `..` 的）放行了它，
文件被真的写到了：

```
<工作空间根>/data/data/com.dshwatch.debug/files/workspace/index.html
```

—— 一个凭空长出来的 6 层嵌套目录。工作空间根目录下当然没有 index.html，
所以 `build_project(entry = "index.html")` 报「找不到入口文件」，
而它列出的「现有文件」正是那条被写歪的长路径。**现象和原因差了十万八千里。**

**修了四层**（每层单独都够用，这里是纵深防御）：

**① 路径归一化 `WorkspacePaths`**（新增，纯函数、15 个单测）

把「其实都指向工作空间内某个文件」的各种写法收敛成标准相对路径：

| 模型写的 | 归一化后 |
| --- | --- |
| `index.html` / `src/app.js` | 原样 |
| `./index.html`、`src//app.js`、`src\app.js` | 折叠 |
| `/data/data/<pkg>/files/workspace/index.html` | `index.html` |
| `data/data/<pkg>/files/workspace/index.html` ← **真凶** | `index.html` |
| `/data/user/0/<pkg>/files/workspace/x` | `x` |
| `Android/data/<pkg>/files/workspace/x` | `x` |
| `files/workspace/x`、`workspace/x` | `x` |

识别靠两条互补的规则：**用运行时真实根路径做段序列匹配**（最准），
以及 **`files/workspace` 这个稳定标记**（release/debug 包名互串、换存放位置时也能命中）。

**② 脏数据自愈 `WorkspaceStore.selfHeal()`**

已经写歪的文件不用你手动去删 —— 启动时和每次构建前会自动把它们**搬回正确位置**，
再清掉空壳目录。幂等，没脏数据时什么都不做。同名文件已存在时保留正确的那份，不覆盖。

**③ 读操作容错**

即使还没自愈到，`read` / 预览也会依次尝试「原样相对路径」和「在嵌套壳里找剥壳后同名的文件」，
所以你现在去「工作空间」里点那个 index.html，能正常读到内容。

**④ 写操作严格**：写永远不会走回退路径，保证不再产生新的嵌套目录。

**顺手把错误信息改成「能看懂」的**：原来所有失败都只说「路径越界」，
而模型写的那串既没 `../` 也不以 `/` 开头，模型看完完全不知道错在哪，只能反复重试。
现在 `describeIssue()` 会分因回答：

- 以 `/` 开头 → 「不允许使用绝对路径「…」，请改成相对路径，例如 index.html」
- 含 `..` → 「不允许用 .. 向上跳出工作空间」
- 归一化能救回来 → **不算错误**，直接放行

### 13.2 从源头治：把工作空间约定写进系统提示

只做兜底不够 —— 模型还会继续写绝对路径。所以 `ChatEngine` 在 Agent 开启时
会往 system prompt 里注入一段【工作空间】：

```
所有文件类工具的 path 必须是**相对于工作空间根目录的相对路径**：
  ✅ index.html    ✅ src/app.js    ✅ mysite/index.html
  ❌ /data/data/<包名>/files/workspace/index.html
  ❌ data/data/<包名>/files/workspace/index.html
  ❌ files/workspace/index.html
建议按项目建子目录（如 mysite/、tools/），一个项目一个目录，避免互相覆盖。
```

工具描述（`write_file` / `list_files` / `build_project`）也同步强调了这一点。

### 13.3 自定义文件存放位置

按你的要求做了三层：

**① 工作空间根目录可以换位置** —— **设置 → 工作空间 → 文件存放位置**：

| 选项 | 实际路径 | 特点 |
| --- | --- | --- |
| 应用内部（默认） | `…/files/workspace` | 最稳，只有本 App 能访问 |
| 外部私有 | `Android/data/<包名>/files/workspace` | **文件管理器 / USB 连电脑能看到、能直接拷出来** |

切换时 `relocate()` 会把已有文件**全部搬过去**（跨挂载点时自动退回「复制 + 删除」）。
两个位置都不需要任何存储权限。设置页和空态都会显示当前实际路径。
部分 ROM 没有外部私有目录时会自动回退到内部并给出提示。

> 没有做「任意选一个目录」（SAF / `ACTION_OPEN_DOCUMENT_TREE`）：那需要持有 tree URI 权限、
> 走 `DocumentFile` 读写，在手表上性能差很多，而且每次读写都要过 SAF。如果你确实需要，告诉我，我再加。

**② 产物目录可以自定义** —— `build_project` 新增 `out_dir` 参数（默认 `dist`），
比如 `out_dir = "site"`。产物目录下的文件不会参与源码校验。

**③ 工作空间内按项目建子目录** —— 现在入口可以是 `mysite/index.html` 这种子目录形式，
「工作空间」页的构建按钮也会自动挑入口（优先根目录 index.html，否则第一个 html），
不再写死只认根目录的 index.html。

### 13.4 构建脚本的坑

`kotlin.daemon.jvmargs=-Xmx1536m`。

Kotlin 编译 daemon 默认**继承** `org.gradle.jvmargs`（4G），而且默认 2 小时不退出。
于是「Gradle 4G + Kotlin daemon 4G」在 8G 的容器里必然撞墙，
表现为 R8 阶段报 `Gradle build daemon disappeared unexpectedly`（而且报错信息完全看不出是内存问题）。
现在给 Kotlin daemon 单独限 1.5G。

### 13.5 测试

171 个（新增 32 个）：

- **WorkspacePathsTest 15 个**：上表里每一种写法、以及 `/etc/passwd`、`..`、控制字符必须被拒
- **WorkspaceStoreTest 23 个**（+10）：**带包名但没前导斜杠的路径不再长出嵌套目录**（真机回归）、
  自愈搬迁、自愈不覆盖正确文件、读取容错、`relocate` 迁移
- **ProjectBuilderTest 17 个**（+9）：绝对路径入口能构建成功、脏数据自动自愈后能构建、
  `out_dir` 生效、产物目录不参与校验

---

## 十四、v1.3.1 —— 把自愈变成「随时生效」，并默认用外部目录

### 14.1 为什么 v1.3.0 还会看到同一句报错

v1.3.0 把自愈放在**启动时跑一次**（`AppContainer.init`）。这个前提太脆弱：

- 覆盖安装后进程不一定重建，`init` 可能压根没跑；
- 就算跑了，用户也可能在自愈完成前就点了构建；
- 而且它只对「当时的那个根目录」生效，切换存放位置后新目录不会补跑。

所以 v1.3.1 把自愈下沉到**每一个访问入口**：

```
list / listFilesRecursive / read / write / append / mkdir / delete  →  先 healLocked()
build_project                                                       →  先 selfHeal()
```

`healLocked()` 开头有一次**零成本快速判断**：脏数据的特征就是根目录下凭空多了一层 `data/`，
没有它就立刻返回，不会多扫一遍文件树。所以挂到每个入口上是安全的。

副作用是好的：现在**模型看到的、你在「工作空间」页看到的，永远都是修好之后的干净路径**。

### 14.2 构建结果会告诉你「修了几个」

`build_project` 的返回里会带上修复数量：

```
构建成功｜入口 index.html｜已自动修复 1 个位置错误的文件｜产物 dist/index.html（1 KB）
```

有这行就说明修复真实生效了；没有这行说明本次没有脏数据可修。这比看日志靠谱。

### 14.3 入口兜底

万一自愈仍然没能生效（比如目标目录只读、搬迁失败），`ProjectBuilder` 还会做一次
**「剥壳匹配」**：只要工作空间里存在「剥壳后正好等于入口」的文件，就直接拿它去构建。
也就是说哪怕文件还躺在嵌套壳里，构建也会成功，而不是卡在一条看不懂的报错上。

### 14.4 默认改成外部私有目录

`AppSettings.workspaceLocation` 的默认值改为 `EXTERNAL`：

```
Android/data/com.dshwatch/files/workspace      ← 默认（能用文件管理器 / USB 直接取）
files/workspace                                ← 可选（只有本 App 能访问）
```

**已经用过的用户不会自动切换** —— 因为你的设置里已经写入了 `INTERNAL`。
要去 **设置 → 工作空间 → 文件存放位置** 点一下切换（会显示「⇄」，点一下就是外部）。
切换时已有文件会全部搬过去。

### 14.5 目标目录不可写时不再「切过去就完蛋」

`relocate()` 现在会先探一下目标能不能真的写（建一个 `.dsh-probe` 写进去再删）：

- 能写 → 搬迁 + 切换，返回搬迁数量；
- 不能写（外部存储未挂载 / 只读）→ **保持原位置不动**，返回 `-1`，并在日志里写明原因。

v1.3.0 是直接切过去，万一目标不可写，之后所有文件类工具调用都会失败 —— 比留在原位置糟糕得多。

### 14.6 测试

175 个（新增 4 个）：

- `list heals lazily without an explicit selfHeal call` —— 不显式调自愈，列目录就应该是干净路径
- `a real data directory is not mistaken for the legacy shell` —— 模型合法建的 `data/` 子目录不能被误伤
- `build reports how many files it had to repair` —— 修复数量必须出现在报告文本里
- `entry inside a project subdirectory builds fine` —— 入口在子目录时也能构建，相对引用照常内联

---

## 十五、v1.4.0 —— 修主线程网络崩溃 + UI 重做

### 15.1 【致命】`拉取失败: NetworkOnMainThreadException`

真机上点「拉取模型」直接报这个。根因很直白：**两个后端的 `listModels()` 里是裸的阻塞网络调用**。

```kotlin
// 修复前：OpenAiCompatBackend / DshServerBackend 都是这个形状
override suspend fun listModels(...): List<String> {
    val req = Request.Builder()...build()
    Http.client.newCall(req).execute().use { resp -> ... }   // ← 阻塞，但在谁的线程上跑？
}
```

`streamChat` 有 `flowOn(Dispatchers.IO)` 所以没事，而 `listModels()` 没有 —— 从「供应商」页的
`rememberCoroutineScope()`（Main dispatcher）调过去，就正好撞在 Android 的主线程网络检查上。

同一批还有 `DshServerBackend` 的 `pair()` / `listRemoteSessions()` / `testConnection()`。

**修了两层：**

1. **根治** —— `listModels` 改为复用各自类里已有的 `rawGet()`（内部 `withContext(Dispatchers.IO)`），
   `pair` / `listRemoteSessions` / `testConnection` 补上 `withContext(Dispatchers.IO)`。
   复用 `rawGet` 还有个额外好处：和「诊断」页走同一条代码路径，避免再出现「测的和发的不一样」。

2. **结构性兜底** —— 新增 `IoBackend` 装饰器，`BackendRegistry.of()` 返回的后端一律包一层，
   任何接口方法都强制在 IO 上执行（`streamChat` 走 `flowOn`，其余走 `withContext`）。
   这样以后新增后端或新增接口方法，不必再想一遍这件事。

> 为什么单测没抓到：**JVM 单测里没有主线程网络检查**，`Thread` 是谁都无所谓。
> 所以补了一个 `IoBackendTest`，用「调用时所在线程名 ≠ 调用方线程名」把这条约束钉死。

### 15.2 UI 重做

#### 工具名说人话

模型用的是 `write_file` 这种 snake_case 英文名，之前直接就显示在卡片上了。现在统一走
`AppStrings.toolTitle()`：

| 模型看到的 | 用户看到的 |
| --- | --- |
| `write_file` | 创建文件 |
| `build_project` | 打包构建 |
| `http_fetch` | 联网获取 |
| `get_device_info` | 查看设备信息 |
| `set_alarm` | 设置闹钟 |
| …（13 个工具全部映射） | |

`ToolTitleTest` 会遍历 `BuiltinTools.all()` 检查每个工具中英双语都有映射、
且长度不超过一行 —— **以后新增工具忘了加映射，测试直接红**。

#### 工具卡片：图标 + 产物卡片

- 左侧图标 + 状态色底，一眼看出「在做什么、成没成」（只用了 material-icons-core，
  不引 extended 图标包 —— 那会让 APK 多几 MB，对手表不值）
- 参数摘要常显一行，不用展开就知道它动了什么
- 右侧 `▼/▲` 展开「调用详情」（参数原文 + 原始输出，可复制）
- **`build_project` 成功后直接渲染产物卡片**：文件名 + `产物 · 真实大小`，
  **点整卡打开预览、右侧箭头导出分享**

产物路径是从工具层一路带上来的（`ToolResult.artifact` → `ToolCallRecord.artifact`），
不是从返回文本里正则抠的 —— `ArtifactPlumbingTest` 覆盖了这条链路，
包括自定义 `out_dir` 的情况。大小则是从磁盘真实读的（工具文本里那个「1 KB」是四舍五入值）。

#### 代码块可折叠

两级折叠，对应两种诉求：

1. **整体折叠** —— 点标题条收起整段代码。一条回答里贴三四个大代码块时，
   这是唯一能快速扫完全文的办法；
2. **行数限制** —— 展开后仍只渲染前 N 行（N 由性能档决定），点「显示全部 300 行」才补上。

标题条同时给出**语言 + 行数** —— 用户判断"要不要看"靠的就是这个数字。

#### 对话与思维链

- 用户消息用主色容器、助手消息用中性容器，加大圆角（14dp）。
  **不改成手机那种左右气泡** —— 手表横向本来就窄，左右对齐会白白吃掉一截宽度；
- 操作行（复制 / 重试 / 删除）**常显**，不再藏在「先点消息选中」后面 ——
  那个交互既难发现又容易误触；
- 思维链升级为独立卡片：默认**完全折叠**成一行（带指示点 + 展开箭头），
  **流式期间自动展开**（这时它正是最有信息量的内容），用户手动操作过就以用户为准。

### 15.3 测试

185 个（新增 10 个）：

- `IoBackendTest` 3 个 —— suspend 调用 / 流式收集都不在调用方线程、cancel 透传
- `ToolTitleTest` 4 个 —— 双语映射齐全、未知工具回退原名、长度与唯一性
- `ArtifactPlumbingTest` 3 个 —— 构建成功带回产物路径、失败不带、自定义目录生效

---

## 十六、v1.0 正式版 —— 品牌化与关于页

> 应用对外的正式版本号从这一版起定为 **1.0**；此前的 v1.0.0 ~ v1.4.0 是开发迭代编号，
> 完整记录见「关于 → 更新日志」。

### 16.1 【致命】「缺少 path 参数」—— 真正的原因是参数被截断

真机上出现的一条误导性极强的报错：

```
创建文件            失败
参数：{"path": "codetest/index.html", "content": "<!DOCTYPE html> ... --ok:
结果：缺少 path 参数
```

参数里明明有 `path`，工具却说缺少。根因在参数解析这一层：

```kotlin
// 修复前
private fun parseArguments(raw: String): JsonObject =
    runCatching { json.decodeFromString(JsonObject.serializer(), raw) }
        .getOrElse {
            Logx.w(TAG, "工具参数不是合法 JSON")
            JsonObject(emptyMap())      // ← 静默回退成空对象
        }
```

模型写大 HTML 时，**单次输出被 max_tokens 截断**，`arguments` 少了一个引号和一个 `}`。
解析失败 → 回退成空对象 → 工具拿到空的 JsonObject → 报「缺少 path 参数」。
于是「参数被截断」这个真问题被彻底盖住，用户和模型都在猜为什么 path 会丢。

新增 `ToolArgs` 做三态解析：

| 结果 | 触发条件 | 处理 |
| --- | --- | --- |
| `Ok` | 合法 JSON | 正常执行 |
| `Truncated` | 补全引号/括号后能解析 | **不执行**，返回可操作的指引 |
| `Broken` | 结构本来就错乱 | 不执行，附上原始片段 |

其中还单独处理了**字符串里的裸换行** —— 模型写多行内容时经常直接把真换行塞进
`"content": "..."`，这在 JSON 里非法但意图明确，转义一次就能救回来
（只处理引号内的内容，并正确跳过 `\"`，避免把合法的 `\n` 二次转义）。

「被截断」时的说明是**可执行**的，而不是「参数错误」四个字：

```
工具参数被截断：模型还没写完就断了（单次输出超过了「最大输出 token」）。
建议换一种写法：先用 write_file 写入文件骨架，再用 append_file 分段追加内容；
或者在「供应商」设置里把最大输出调大。
```

同时往系统提示里补了一条约定：**单次 write_file 的内容控制在 200 行以内，
更长的文件先写骨架再分段追加** —— 从源头减少这类失败。

`ToolArgsTest` 9 个用例覆盖三态、裸换行、已转义换行不被二次转义、括号错乱不硬救。

### 16.2 应用名与版本号

- 应用名：**DSH肥鱼**（英文环境显示 `DSH Feiyu`）
- 版本号：`versionName = "1.0"`

> `versionCode` 保持递增（8）。Android 靠它判断"是不是新版本"，
> 降回 1 会导致覆盖安装被拒。

### 16.3 图标与看板娘

原始素材是一张带截图黑边和关闭按钮的照片，处理链路：

1. **裁边** —— 扫描行/列的黑像素占比，定位有效内容区（`y 8..493`）；
2. **去关闭按钮** —— 左上角 44×44 区域用邻近背景填充；
3. **抠图** —— 用 `isnet-general-use`（rembg）做主体分割。

> 中间试过两条弯路，都记下来避免以后重蹈：
> - **flood fill 全废**：角色的淡蓝头发和金属碗跟石头背景颜色太接近，
>   从四边灌进去会把头发、碗一起吃掉，只剩脸和围裙；
> - **u2net 模型下不动**：176MB，实测速度约 20 kB/s，要 1.5 小时。
>   而沙箱里已经预装了 `isnet-general-use`（178MB），换成它 **3.6 秒**跑完，
>   而且 ISNet 本身就是比 U2Net 更新的架构，边缘质量更好。

生成四类资源：

| 资源 | 用途 | 尺寸 |
| --- | --- | --- |
| `ic_launcher_foreground.png` | 自适应图标前景 | 5 个密度（108 ~ 432px） |
| `ic_launcher_background.xml` | 图标背景（深蓝渐变） | 矢量 |
| `mascot.png` | 聊天空态插图 | xxhdpi 420px 高 |
| `mascot_avatar.png` | AI 回复头像 | xxhdpi 256×256 |

两个细节：

- **图标前景只占画布的 68/108**。自适应图标的安全区是中心 66dp 圆，
  角色直接铺满 72dp 会让脚部被圆形遮罩切掉；压到 68dp 后最宽处（碗与头发）
  正好落在圆内 —— 生成时用圆形遮罩实际渲染验证过。
- 头像**单独裁「碗 + 完整脸部」的正方形**，而不是直接缩放全身图：
  18dp 的小圆里放全身等于什么都看不清。

看板娘接入两处，并且**可关**（设置 → 外观 → 显示看板娘）：

- 聊天空态：112dp 插图
- AI 回复消息头：18dp 圆形头像（关掉后退回原来的「DSH」文字）

### 16.4 关于页

四张卡片：

- **关于作者** —— 东山の字幕 / 2332184782@qq.com，附一键复制（手表上长按选中文字很别扭）
- **应用介绍** —— 开发技术栈、借鉴项目、功能简介
  （这里如实写了：DSH 服务器模式参考 dsh-workspace 的配对协议形态，
  产品形态参考社区 DSH 客户端，但**代码是独立实现**）
- **更新日志** —— 数据源是 `AppChangelog`，**发版时只需往列表最前面加一条**，
  关于页自动渲染，不用维护两份文档
- **设备信息** —— 型号 / Android / 屏幕 / 内存档 / 性能分级 / CPU 核心

### 16.5 测试

194 个（新增 9 个 `ToolArgsTest`）。

---

## 十七、v1.1 —— 会话级工作目录、ask_user、峰谷提示

### 17.1 每个会话一个工作目录

以前所有会话共用一个平铺的工作空间。问题在会话变多之后才显现：
上个会话留下的 `index.html` 会被这个会话的 `build_project` 捡走，
`list_files` 也会把别的会话的文件一并列出来糊在上下文里。

现在每个会话有自己的目录：**`progress/<会话 id 前 6 位>`**。

用 id 而不是标题来命名，是因为标题随时会改、还可能带中文和空格；
id 前缀一次生成就再也不变。

实现上分了两个方向，都由 `WorkspacePaths` 的两个纯函数负责：

| 方向 | 函数 | 作用 |
| --- | --- | --- |
| 模型 → 磁盘 | `resolveUnder(raw, base, root)` | `index.html` → `progress/a1b2c3/index.html` |
| 磁盘 → 模型 | `stripBase(global, base)` | `progress/a1b2c3/index.html` → `index.html` |

**顺序很关键：必须先归一化、再拼前缀。** 反过来的话，模型偶尔传进来的
`/data/data/<pkg>/files/workspace/x` 会被当成普通目录名，
在 `progress/a1b2c3/` 底下又长出一层 6 级嵌套 —— 正是之前查了半天的那个「找不到入口文件」。

反向的容错也留了：模型从 `list_files` 里看到过完整路径、下次照抄时，不会重复拼前缀。

工具层的变化：`ToolContext` 增加 `sessionDir`，并提供 `withSession(dir)` 换一副「眼镜」。
工具集是应用级单例（`ToolRegistry` 缓存在 `AppContainer` 里），
而工作目录是每个会话各自的，所以每次执行工具前由引擎换上当前会话的目录。
`ProjectBuilder` 也加了 `baseDir`：只扫本会话的文件做校验，产物落在本会话的 `dist/` 下。

**自定义文件位置**：对话管理页 → 会话菜单 → 「文件位置」，
可以选已有文件夹，也可以新建（输入框里的名字会做清洗：`/`、`..`、控制字符都会去掉）。

### 17.2 修复：没说话的新建会话不再占用列表

旧的 `startNew()` 每次都会 `store.create()` 写一条空会话进索引。
用户连点几下「+」，列表里就堆起一串同名、点开什么都没有的「新对话」——
列表记录的就不再是「聊过什么」，而是「点过几次加号」。

改成**延迟落盘**：`startNew()` 只建内存对象，
真正的写入发生在 `runSend` 里第一条消息确定之后。
顺手把用户消息也立刻持久化（以前要等 `finalize`，中途被杀就丢了）。

存量也收干净了：`index()` 会过滤掉 `messageCount == 0` 的条目，
`pruneEmpty()` 在启动时物理删除它们。

> 顺带一个可见的变化：**发出去的第一条消息会立刻出现在会话列表里**，
> 不必等回答生成完。

### 17.3 新增 ask_user

模型碰到信息不全时的默认行为是「编一个合理的假设然后一路做下去」。
在手表上这尤其糟糕 —— 它可能先写五个文件、打包、最后你才发现方向完全不对。

所以加了 `ask_user`：模型可以停下来问一句，**拿到答复后继续同一轮任务**
（不像把问题写在正文里，那已经是本轮结束了）。

实现上需要跨越一个构造顺序问题：工具集在 `AppContainer` 里先于引擎构造，
而「等用户回答」这件事必须发生在引擎里（只有它持有协程作用域和取消逻辑）。
解法是加一个可后置注册的 `UserPromptChannel`，引擎在 `init` 里把 handler 挂上去。

配套的细节：

- **5 分钟超时**：用户可能看完问题就去干别的了，任务不能永远挂着占住引擎
  （那样「停止」按钮也点不动）。超时后返回 null，工具转成「用户没有回答」，
  并明确告诉模型「不要再问一遍，要么选个默认值继续，要么写进最终回答」。
- **点停止会放行**：`cancelRunning` 里一并唤醒挂起的提问，否则协程会一直卡着。
- **「跳过」≠「取消」**：跳过会给一个空回答，意思是「你自己定吧，继续」。
- 界面按你的选择只做两件事：点选项、或者自己输入 —— 选项是一整行的可点区域，
  手表上目标太小就会点错，而这里点错的代价是 AI 按错误假设继续做下去。

### 17.4 DeepSeek 峰谷时段提示

> ⚠️ 这条规则**改过**：早先是「00:30–08:30 优惠」，现在是峰谷制。
> 按官方 2026-08-23 起生效的口径：**周一至周五 09:00–12:00、14:00–18:00 为高峰，
> 其余全部时间（含周六周日全天）为空闲，空闲单价是高峰的一半。**

判断集中在 `DeepSeekPricing` 一个文件里，将来官方再调只改它，并有 11 个测试钉住边界
（含「周五晚上下一次切换是下周一 09:00，不是明天」这种最容易写错的情况）。

提示条显示在**输入框正上方** —— 这是发出去之前最后一个能改主意的时刻。
它同时回答两件事：现在贵不贵、还有多久变。只给前者的话用户没法决定是现在发还是等一会儿。

只有识别为 DeepSeek 时才显示（域名、模型名、供应商名任一命中即可 ——
很多人用中转站，域名里并没有 `deepseek`）。

### 17.5 修复：导出分享点了没反应

`FileProvider` 的 `file_paths.xml` 里只映射了内部 `files/`，
而「文件存放位置」的默认值在 v1.3.1 就改成了**外部私有目录**
（`Android/data/<pkg>/files/workspace`）。两者对不上，
`getUriForFile` 直接抛 `IllegalArgumentException: Failed to find configured root`，
而调用处套着 `runCatching {}` 没有反馈 —— 点导出就是**什么都没发生**。

修了三处：

1. `file_paths.xml` 补上 `<external-files-path>`（外加 `<cache-path>` 作为兜底）；
2. `shareFile` 拿不到 URI 时**先复制一份到 `cache/share/` 再分享**，
   保证用户手工改过位置的情况下也不会失败；
3. 返回 `ShareResult`，失败原因通过 Toast 说出来 —— 不再静默吞掉。

顺带把 MIME 表补全（原来未知类型一律 `text/plain`，会让微信把 HTML 当纯文本显示）。

### 17.6 新增工具

| 工具 | 副作用 | 说明 |
| --- | --- | --- |
| `ask_user` | 只读 | 向用户提问并等待回答（见 17.3） |
| `web_search` | 联网 | 网页搜索，无需 API Key |
| `unit_convert` | 只读 | 长度/重量/温度/存储/时间/速度换算，含市制单位 |
| `move_file` | 写操作 | 移动或重命名（目标已存在时拒绝，不覆盖） |
| `search_files` | 只读 | 按内容或文件名搜索 |
| `list_alarms` | 只读 | 列出待响闹钟 |
| `cancel_alarm` | 写操作 | 取消闹钟 |
| `vibrate` | 写操作 | 震动提醒（手表上唯一的强提醒通道） |
| `get_clipboard` | 只读 | 读剪贴板 |

两点说明：

- **剪贴板只读不写**。写入会把用户原本复制的东西顶掉，而「拿到内容」这个诉求
  用工作空间文件解决得更彻底；但**读**是另一回事 —— 手表上打字非常困难，
  用户常常先在别处复制好再让 AI 看。
- **`list_alarms` / `cancel_alarm` 需要自己记账**：AlarmManager 根本不提供查询接口，
  你没法问它「现在有哪些闹钟」。所以设闹钟时会往 `AlarmStore` 写一条记录，
  取消时用同一个 id（就是 `PendingIntent` 的 requestCode）去 `cancel`。
  `unit_convert` 则是因为**单位换算正是模型最容易自信地算错的地方**，
  交给确定性代码做更可靠。

目前共 **22 个工具**。

### 17.7 测试

221 个（新增 27 个）：

- `DeepSeekPricingTest` 11 个 —— 高峰边界（9:00 / 12:00 整点）、午休、
  周末全天空闲、周五晚跨到周一、跨天倒计时、中转站识别
- `SessionScopeTest` 16 个 —— 路径作用域正反两向、
  **绝对路径必须先剥壳再拼前缀**（历史回归）、越界拒绝、
  空会话不出现在列表、`pruneEmpty`、`updateMeta` 迁移目录、
  两个会话各自构建产物互不干扰、产物路径回显不带前缀

---

## 十八、v1.2 —— 工具集对齐主流 Agent 设计

参考了一份成熟 Agent 工具集（读写分离、Glob/Grep、任务清单、技能、设备集成等）逐项比对，
挑出**在手表上真正成立**的部分做了实现。下面把「做了什么」和「为什么没做某些」都写清楚。

### 18.1 「编译」在手表上的真实边界

> **v1.3 补充**：本节写于 1.2 时期。1.3 之后边界变了 ——
> **Python 可以在本机真的跑起来**（Chaquopy + CPython 3.11，仅 full 包），
> 真正的编译（编 apk、gradle、gcc）交给云端 GitHub Actions。
> 也就是说「本机只能静态校验」这条结论已经被后面的版本推翻了一半，详见第十九节。
> 下面保留原文，是为了记录当时的判断依据。

先把话说清楚：**这台设备上没有真正的编译器** —— 没有 CPython、没有 JDK、没有 Kotlin 编译器。
不是「没接」，是物理上装不下：Kotlin 编译器光堆就要几百 MB，编译一个文件在手表 CPU 上要几分钟，
而 APK 现在总共 5MB。任何声称「在手表上编译出可执行文件」的实现都是假的。

那还能做什么？**静态校验** —— 而这件事的价值被严重低估了：

模型写代码时最常犯的错**不是算法错**，而是括号没配平、字符串没闭合、
Python 缩进混了 tab 和空格、`if` 后面忘了冒号。这些**全都能在不上编译器的情况下查出来**，
而且查出的时机正好：模型刚写完、上下文还没丢的时候，它可以立刻改。

等用户把文件拷到电脑上跑、发现报错、再回来描述给模型 —— 这一轮往返的成本
（重新贴代码、重新理解上下文）比在手表上查一次高一个数量级。

**校验覆盖范围**（`LanguageValidators`）：

| 语言 | 检查内容 |
| --- | --- |
| Python | 缩进 tab/空格混用、`if/for/def/class/try` 等漏冒号、括号配对、字符串与三引号闭合 |
| Kotlin / Java / C / C++ / C# / Go / Rust / Swift / Scala / Dart / PHP / ObjC | 括号配对、字符串（含三引号）闭合、字符字面量、块注释闭合 |
| Shell | `if/fi`、`case/esac`、`do/done` 配对 |
| YAML | tab 缩进（YAML 明确禁止，而 tab 和空格肉眼几乎一样） |
| JSON / HTML / XML / CSS / JS / TS | 沿用原有校验器 |

**只做「报了就是真错」的检查。** 这条约束比多查几类问题重要得多：
读报告的是模型，它收到错误就会去改代码，而它没有编译器可以对照 ——
误报会让它把本来正确的代码改坏。所以「未使用的变量」「可能的空指针」这类
需要类型信息才能下结论的检查，一律不做。

实现上所有语言共用一套**词法剥离**：先把注释、字符串、字符字面量挖空（替换成等长空格，
保持行列不变），再在剩下的纯代码上数括号。这样 `"内容里有（"` 和 `// 注释里的 {`
就不会被误算进去 —— 这是括号检查最常见的误报来源，测试里专门钉住了。

**产物上的诚实**：非 HTML 项目**不产出 `dist/` 里的假文件**。
报告里会写「静态校验通过（本机无该语言的编译器，未产出可执行文件）」，
并提示需要真正运行请把源码导出到电脑。造一个假的「构建成功」比不构建更糟。

### 18.2 逐项对齐

**① `edit_file` —— 本轮最有价值的补充**

清单里 `Read` / `Write` / `Edit` 是分开的三个工具，我们之前只有读写。
少了 `Edit`，模型改一行代码必须 read 全文 + write 全文 —— 在电脑上无所谓，
在手表上这意味着读一遍吃掉一次上下文、写一遍再吃一次，
而且**整份重写特别容易撞上单次输出上限**（这正是之前「缺少 path 参数」那个 bug 的根因）。

现在 `edit_file` 支持 `old_string` → `new_string` + `replace_all`，
失败时给的是**可执行的下一步**：找不到就让它去 `read_file` 逐字符核对，
多处命中就让它补上下文或显式 `replace_all`。

**② `glob` / `grep` 拆开**

之前是一个 `search_files` 同时做文件名和内容匹配，靠参数形态猜意图。
参照清单拆成两个 —— 因为这是两种完全不同的检索意图：
「我知道文件叫什么但忘了放哪」和「我知道里面写了什么但忘了在哪个文件」。
合并的代价是模型经常用错边，而用错的代价是一次白跑的往返。

`grep` 用**真正的正则**（`TODO\(`、`def main`、`val\s+\w+` 都能用）。
正则写错时**自动退回字面量匹配**并在结果里说明 —— 找一段普通文本本来就是常见需求
（里面的括号、点号都会让正则编译失败），不该因为一个错括号就整个失败。

**③ `todo_write` —— 多步任务不跑偏**

清单里的 `TaskCreate/TaskList` 是多步任务的进度跟踪。手表上的 agent 任务动辄五六步
（读文件 → 改 → 构建 → 看报错 → 再改），而**多步任务最容易出的问题不是某一步做错，
是做着做着忘了原本要干什么** —— 上下文被工具输出不断挤占，模型会开始在一个局部问题上打转。

所以加了 `todo_write`：一次写入完整清单（不是增量，避免多次往返），
每完成一步更新一次。那份清单会一直躺在上下文里（和最近工具输出不同，它不会被裁掉），
而且**用户也看得见进度**。

**④ `open_file` / `open_screen` —— 需要用户操作时直接送他过去**

对应清单里的 `mobile_files_openWith` 和 `mobile_workbuddy_openPage`。
手表屏幕上「返回 → 找设置 → 滚动 → 找到那一项」是很重的操作，
所以让模型能直接打开：`open_screen("providers")` 比「请到设置里配置 API Key」有用得多。

实现上需要跨一层构造顺序：工具集先于界面构造，而「打开页面」只有界面能做 ——
用了和 `ask_user` 同样的后置注册通道（`UiActionChannel`），
界面在 `DisposableEffect` 里注册、离开时摘掉。

`open_file` 优先走 App 内预览，因为**很多国产手表根本没有浏览器**，
用系统 `ACTION_VIEW` 打开 HTML 会直接失败。

**⑤ `create_event` —— 日程**

走系统 `ACTION_INSERT`（打开日历新建界面并预填字段），而不是直接写数据库。
原因很具体：直接写 `CalendarContract` 需要 `READ_CALENDAR`/`WRITE_CALENDAR`，
这俩都是**危险权限**，得走运行时申请 —— 为了「加个日程」弹一次权限框，
在手表上是很重的打扰。而且很多国产手表 ROM 的日历 Provider 不完整甚至不存在。

时间解析支持「今天/明天/后天/YYYY-MM-DD + HH:mm」，且**「今天」但时间已过会自动顺延到明天**
（否则用户说「下午 3 点提醒我」而现在是 4 点，会得到一个已经过去的日程）。

**⑥ `http_fetch` 自动把 HTML 转纯文本**

对应清单里 `WebFetch` 的「抓取并分析」。这里做了一件很值钱的事：
一个普通网页的 HTML **九成以上是标签、脚本和内联样式**，而模型要的只是那几句话。
现在自动剥离，测试里那篇 400 个 div 的页面从原体积压到不足三分之一。

同时补上了 `POST`（可带 body）—— 旧版只能 GET，于是「调用一个 API」只能靠
那些恰好接受 query string 的接口，稍微正规一点的要 JSON body 的就做不了。

### 18.3 没有采纳的项，以及原因

如实列一下，避免看起来像遗漏：

| 清单项 | 为什么不加 |
| --- | --- |
| `Bash` / `LSP` / `NotebookEdit` / `EnterWorktree` | 手表上没有 shell、没有语言服务、没有 git 工作区 |
| `Agent`（子智能体） | 一次子任务要额外一轮完整 API 调用，手表上等待时间与 token 成本都翻倍；同一件事用 `todo_write` + 多步循环更划算 |
| `Skill`（内置技能） | 概念本身有价值，但它需要按任务裁剪工具集，与「技能包」的文件分发机制绑定；目前用 22→31 个工具 + 系统提示引导已经够，硬做反而是半成品 |
| `TaskCreate/TaskGet/TaskUpdate/TaskList`（四个工具） | 合成一个 `todo_write`：每次调用都要一次往返，而清单只有几行，细粒度操作不划算 |
| `ImageGen` / `VideoGen` | 手表上没有意义（屏幕放不下，也没有消费场景） |
| 表格处理（`sheetagent`） | 与 `build_project` 的能力重叠，且手表上编辑 Excel 不现实 |
| `Artifact` / `show_widget` / `present_files` | 对应能力已经内建：产物卡片 + 内置 WebView 预览 |
| `library_search` / `LibraryUpload` / IMA / 腾讯文档 | 依赖外部账号与云端服务，与「直连自己的 API Key、数据不出设备」的定位冲突 |
| `WebSearch` 之外的检索 | 已有 `web_search` + `http_fetch` 两个入口，够用 |

### 18.4 当时的工具集（28 个；1.3 之后是 31 个，见第十九节）

| 类别 | 工具 |
| --- | --- |
| 信息 | `get_datetime` `calculate` `get_device_info` `get_token_usage` `unit_convert` `get_clipboard` |
| 联网 | `http_fetch` `web_search` |
| 文件 | `list_files` `read_file` `write_file` `edit_file` `append_file` `make_dir` `move_file` `delete_file` |
| 检索 | `glob` `grep` |
| 构建 | `build_project` |
| 交互 | `ask_user` `todo_write` `open_file` `open_screen` |
| 提醒日程 | `set_alarm` `list_alarms` `cancel_alarm` `vibrate` `create_event` |

> v1.3 新增 `run_script`（本机执行）、`cloud_build` / `cloud_job`（云端编译），共 31 个。

28 个工具的函数声明大约占 3–4k token。这个开销是换来「模型知道该用什么」的代价，
目前没有做裁剪 —— 如果实测发现某些供应商的上下文吃紧，
下一步可以按预设（Vibe Coding / 网页 / Python / 查资料）裁剪工具子集。

### 18.5 测试

251 个（新增 30 个）：

- `LanguageValidatorsTest` 20 个 —— 覆盖上面每一类检查，重点在**不误报**：
  字符串里的 `#` 不当注释、三引号里的括号不计数、注释里的括号不计数、
  多行条件不报缺冒号、Java/C/Go 共用同一套检查
- `HtmlTextTest` 7 个 —— script/style 整体剔除、块级标签转换行、
  实体解码（`&amp;lt;` 只解一层）、nav/footer 可选用、大页面压缩比
- `SessionScopeTest` 新增 3 个 —— Python 项目自动挑入口并报出「文件:行号」、
  校验通过时**不造假的产物**、Kotlin 源码同样被检查

---

## 十九、v1.3 —— 本机执行 + 云端编译

1.2 之后最明显的缺口是：**模型写的代码验证不了结果对不对**。

`build_project` 的静态检查能抓出括号不配平、缩进混用、漏冒号 —— 这类占了出错的大头，
但它抓不出变量名打错、索引越界、逻辑写反、路径不对。用户的实际流程于是变成：
让 AI 写 → 拷到电脑上跑 → 报错 → 回来描述给 AI → 来回几轮。
每一轮都要重新贴代码、重建上下文。**在手表上跑一次比这个便宜一个数量级。**

1.3 补上这一步，分两层：本机能跑的当场跑，本机跑不了的交给云端。

### 19.1 本机执行（`run_script`）

| 语言 | 引擎 | 能力 | 硬限制 |
| --- | --- | --- | --- |
| shell | 设备上真实的 `/system/bin/sh` | 管道、重定向、`grep`/`sed`/`awk` 全都能用 | 需要设备有这个二进制（会运行时探测） |
| JavaScript | Rhino 1.9.1（纯 Java） | ES6 大部分语法 | **不支持 async/await**；无 DOM、无 `fetch` |
| Python | CPython 3.11（Chaquopy，仅 full 包） | 真解释器，标准库可用 | 装不了第三方包 |

三个引擎共用一套执行闸门与结果格式。

**输出为什么保留「头 + 尾」而不是只留头**：一个脚本打印了两千行日志，
最后三行才是 `Traceback`。只截头等于把最有用的信息扔掉。
实现上是双缓冲（头一半、尾一半），内存占用恒定 —— 输出 100MB 也不会 OOM。

**超时怎么做的（三种语言不一样）**：

- shell：`destroyForcibly()`，干脆利落
- JavaScript：Rhino 只有在**解释模式**（`optimizationLevel = -1`）下才会回调指令观察者，
  观察者里抛异常才能真正打断 `while(true){}`。默认的编译模式跑字节码，观察者根本不触发 ——
  那样超时是假的。单元测试专门钉住了这条：超时后必须断言**线程真的结束了**
  （`stillRunning == false`），否则就是一个线程在后台空转烧 CPU，而用户只看到"超时了"。
- Python：**Chaquopy 与 App 同进程，Kotlin 侧没有安全手段终止它**（官方文档明说）。
  所以用两条兜底：Python 侧 `sys.settrace` 装死线守卫（能拦住纯 Python 死循环），
  Kotlin 侧等不到就放弃等待并把结果标为「可能仍在后台运行」。
  闸门会保持占用，设置页提供「强制释放」这个逃生口 —— 宁可挡住新任务，
  也不让失控脚本叠加（那是手表上唯一能让用户开不了机的做法）。

**输出重定向到文件而不是管道**，这里踩过两个坑：管道不持续排空会把子进程堵死在写操作上；
超时杀掉 `sh` 之后，脚本起的子进程（`sleep 30` 之类）变成孤儿并继续持有写端，
读线程会一直阻塞 —— 而且 JVM 在另一线程阻塞读时 `close()` 要等锁，救不回来。
改成文件之后，孤儿进程爱跑多久跑多久，我们读完自己的文件就走。

### 19.2 安全：默认关闭 + 每次确认

这个工具的权限比其它所有工具加起来都大，所以：

- **出厂关闭**，由用户在「设置 → 执行与编译」显式打开
- `run_script` / `cloud_build` / `cloud_job` 走 `ALWAYS_CONFIRM_TOOLS`：
  **无视「总是允许」和「全自动」**，每次都必须点确认。
  点过一次"总是允许"就永久放行任意代码执行，风险不对等，所以这里刻意不给"记住"的机会。
- 工作目录锁定在当前会话目录；环境变量**清空后重建**（只放 PATH/HOME/TMPDIR/LANG），
  绝不注入任何凭据
- 全局同时只允许一个脚本；输出有上限；超时强制结束

需要说清楚的边界：shell 子进程与 App **同 UID**，能访问 App 私有目录 ——
这是 Android 的现实，不是漏洞。它的边界是「拿不到其它应用的数据、没有 root」，
而不是沙箱隔离。所以靠的是限制 + 显式确认，而不是隔离。

### 19.3 云端编译（`cloud_build` / `cloud_job`）

手表上没有 JDK、Android SDK、Kotlin 编译器，这些需求本地永远做不到。
`cloud_build` 把它们交给用户自己的 GitHub 仓库（Actions）：

```
write_file 写源码 → cloud_build 触发 workflow → 轮询到结束
  → 下载 artifact 解压到 artifacts/ → 返回结论 + 链接 + 日志尾部
```

配套 `cloud_job` 管后续：`status` 看步骤结论、`logs` 看日志尾部、`artifacts` 补下载、`cancel` 取消。

**几个不得不这么写的地方**：

- 只走 `api.github.com`：`github.com` 的网页端点在部分网络下不可达（`git push` 会直接失败），
  而 API 一直可用
- 触发接口返回两种形态：新版给 `workflow_run_id`，老版本是 `204` 无 body。
  后者只能按「时间窗 + 分支 + event」去找运行 —— **不能用 run_number 猜**，
  否则多会话同时触发会读到别人的日志
- artifact 下载是 `302` 到 Azure 预签名地址，**那个地址 1 分钟过期**，拿到就立刻写盘
- 日志可能几十 MB，只取尾部

> 完整用法（两个模板的分工、产物怎么回来、常见错误对照表）见 **[docs/CLOUD.md](docs/CLOUD.md)**。

**模板**（`app/src/main/assets/cloud/`，设置页一键装进你的仓库）：

| 模板 | 用途 |
| --- | --- |
| `dsh-run.yml` | 执行任意命令（`inputs.command`），产物从 `dsh-out/` 上传 |
| `dsh-build.yml` | 构建本项目 APK（JDK 17 + Android SDK，支持 `with_python`） |

命令经**环境变量**传给 `bash -c`，而不是直接插值进 `run:` ——
后者会被 YAML 与 shell 的双重转义规则咬到，出错极难排查。

**代价必须说清楚**：这条路会把源码发到你的仓库、跑在 GitHub 的机器上。
所以它默认关闭、每次确认，用的必须是用户自己的仓库 ——
「数据不出设备」是本 App 的**默认立场**，而不是全部立场；
用户显式开启云端、并在确认框里看到目标仓库时，选择权在他手上。

### 19.4 两种安装包

| 包 | 体积 | Python | 说明 |
| --- | --- | --- | --- |
| lite | 约 7MB | ❌ | shell + JS 执行仍可用 |
| full | 约 30MB | ✅ | 仅 arm64-v8a（libpython 是原生库） |

同一包名、同一签名，可互相覆盖安装。
lite 版里调 Python 不会假装成功，而是明确说「这个安装不含 Python 引擎，可装 full 版，或改用云端执行」。

### 19.5 测试

352 个（新增 101 个）：

- `JsRunnerTest` 10 个 —— 含 `while(true){}` 超时、**超时后线程必须已结束**、
  脚本自己 catch 异常也不能吞掉超时、对象 `print` 走 JSON
- `ShellRunnerTest` 10 个 —— 真跑 `/bin/sh`：退出码、stderr 分离、
  **环境变量被清空后只剩白名单**、大输出不卡死、超时被杀
- `OutputFilesTest` 8 个 —— 大文件头尾读取、**UTF-8 随机偏移不产生乱码**
  （随机 seek 必然落在汉字中间字节上）
- `ExecTruncateTest` 9 个 —— 头尾保留、代理对不被切半、超限后继续消费
- `GitHubApiTest` 13 个 —— 时间窗匹配 run（含时钟偏差容忍）、
  跨分支不串、产物/步骤解析、日志尾部
- `CloudRunStoreTest` 9 个 / `ScriptToolsTest` 12 个 / `CloudToolsTest` 9 个
  —— 未开开关/未配仓库/未配令牌时的提示必须**包含下一步该做什么**
- `PythonBridgeTest` 10 个 —— 解析跨语言返回的 JSON、lite 路径（类不存在）
- `ExecGuardTest` 6 个 —— 并发闸门与强制释放
- `ToolContextTest` 5 个 —— `withSession` 必须同步拷新字段（漏了不报错，只会静默看不到设置）

### 19.6 真机反馈修掉的两个问题（v1.3 发布后当天）

v1.3 的 APK 发出去当天，真机一跑就暴露了两个问题。两个都只有真机能抓到，
也都值得写下来 —— 它们的共同点是**单元测试天然覆盖不到**。

**① Python 执行 100% 失败：`TypeError: 'ArrayList' object is not iterable`**

Kotlin 侧把参数用 `ArrayList<String>` 传给 Chaquopy，而 Chaquopy 把 Java 集合当
**对象**交给 Python，它不是 Python 的 `list`，`for a in args` 直接报错。
更阴的是：**空参数列表也一样炸** —— Python 里任意对象都判真，`args or []` 拿到的还是那个对象。
所以不是"某些情况失败"，而是从来没成功过（真机日志里 `用时 3 ms` 就是这个原因）。

改法：参数编成 **JSON 字符串**再传，Python 侧 `json.loads` 解回来。
跨语言边界只传字符串 —— 这是这个项目里反复验证过的做法（返回值本来就是这个思路）。
回归测试钉在 `PythonBridgeTest.argsJson()`。

**② `sh: python: not found`（退出码 127）**

模型很容易假设 shell 里能调 `python main.py` / `node app.js`，
但**手表上没有系统级运行时** —— Python 与 JS 是另外两条执行路径（跑在 App 进程内），
三者不能互相调用。这不是 bug，但如果不提示，模型会反复重试同一个死路，
甚至开始怀疑自己的代码写错了。

改法分两处：
- **系统提示 + 工具描述**里明写「shell 里没有 python / node / java / gcc」
- **失败结果里自动追加可执行的下一步**：认出 stderr 里"哪个命令不存在"，
  按名字给建议（python → 用 language=python；node → 用 language=js；
  gcc/kotlinc/gradle → 用 cloud_build）。测试样本直接抄自真机日志。

**③ 顺带修掉的两个隐患**

- **Python 超时其实从来没生效过**：原实现用 `sys.settrace` 装死线守卫、从 trace 函数里
  抛异常，而 **CPython 会吞掉 trace 函数里的异常并关闭追踪** ——
  `while True: pass` 会一直跑下去（本机复现过）。现在改成用户代码跑在独立线程、
  超时用 `PyThreadState_SetAsyncExc` 扔一个 `BaseException` 派生类进去，
  实测 2.3 秒稳定中断，且 `try/except Exception` 吞不掉它。
  仍然拦不住阻塞在 C 扩展里的代码 —— 那种情况如实标注 `still_running`。
- **执行临时目录 `.dsh-run` 会出现在 `list_files` 里**：它是实现细节，
  摆给模型看只浪费上下文。已在 `WorkspaceStore.list()` 里过滤隐藏项
  （与 `listFilesRecursive` 原有行为对齐）。

### 19.7 执行器怎么在本机验收

`app/src/main/python/dsh_runner.py` 的关键行为（跨语言传参、超时打断、输出截断）
**单元测试覆盖不到** —— 它得在真 Python 里跑。为此有一个不依赖 Android 的验收脚本：

```bash
python3 tools/check_python_harness.py
```

11 项检查，覆盖上面两个真机故障点 + 输出截断 + cwd + 文件写入 + 超时后状态复原。
改完 `dsh_runner.py` 先跑它，比等真机反馈快得多。

### 19.8 真机反馈之二：仓库字段的写法（v1.3 发布次日）

用户在「执行与编译」页点「安装 workflow 模板」，两个文件都报 404，提示写着
「workflow 文件名或 run id 不对；也可能是模板还没提交到默认分支」。

**这条提示把人带偏了** —— 模板明明都在仓库里。实测根因是**仓库字段填了 URL**：

```
https://github.com/dsdzm/DSH-Watch
```

而当时的校验只有一句 `repo.contains('/')`。URL 里当然有斜杠，于是它被原样拼进 API 路径：

```
GET https://api.github.com/repos/https://github.com/dsdzm/DSH-Watch/...
```

GitHub 对这种请求返回**通用 404**（`{"message":"Not Found"}`）。实测四种输入：

| 输入 | HTTP | 响应体 |
| --- | --- | --- |
| `dsdzm/DSH-Watch` | 200 | — |
| `DSH-Watch` / `dsdzm/DSH-Watch.git` / `github.com/…` / `https://…` | 404 | `{"message":"Not Found"}` |
| `actions/workflows/.github/workflows/dsh-run.yml`（完整路径） | 404 | `{"message":"Not Found"}` |
| 分支不存在（`branch=master`） | 404 | `{"message":"Branch master not found"}` |

关键发现：**前两类的响应体一模一样**。也就是说，旧的 404 文案
「workflow 文件名不对或模板还没提交」在两种成因里赌了一种，而用户遇到的是另一种。

更要紧的是这一点：**靠响应体无法区分**。所以只能靠请求顺序消歧 ——
**先探仓库、再探 workflow**。仓库能访问，后面那个 404 就唯一地是 workflow 的问题。

| 改动 | 位置 |
| --- | --- |
| 仓库字段归一化（URL、`github.com/` 前缀、`.git` 后缀、SSH 形式、尾斜杠、子页面地址） | `cloud/CloudTargets.kt` |
| workflow 字段归一化（完整路径、blob URL → 文件名） | 同上 |
| 按端点解释错误，404 不再一刀切 | `CloudErrors` |
| 设置页"测试连接"改两步诊断，保存时回显规范化结果 | `ExecSettingsScreen.kt` |
| 工具层各自兜一次归一化（设置里可能还留着旧格式） | `CloudTools.kt` |

**为什么选择"尽量救"而不是"严格拒绝"**：从浏览器地址栏复制是最自然的动作。
一个只会拒绝的输入框，用户要试两次才知道该怎么写；而 URL 里本来就带着
`owner/repo` 这两个信息，没理由不认。

唯一的取舍：多余路径段一律忽略（`owner/repo/tree/main` 与 `owner/repo/残留` 无法区分，
而 `owner/repo` 是唯一有意义的解释），但**前两段自己必须合法**，不合法才算写错。
这条取舍是被测试逼出来的 —— 最初的断言写的是"多余段应报错"，跑起来才发现
那是在要求用户去删掉自己看不出来的一截。

### 19.9 真机反馈之三：触发成功却「查不到运行记录」

用户点云端编译，得到：

> 已触发 dsh-run.yml，但没能定位到对应的运行记录。

而配置完全正常（同一页的「测试连接」显示仓库可访问、令牌可写、workflow 已就绪）。
模型于是开始猜"是不是仓库地址 / 令牌 / 分支配错了"—— 全猜错了。

**根因是一个必然失败的字符串匹配**：

| 字段 | 实际值 |
| --- | --- |
| workflow 文件名（代码拿它做匹配） | `dsh-run.yml` → 截出 `dsh-run` |
| 运行的显示名（被匹配的对象） | `DSH Run` |

`"DSH Run".contains("dsh-run")` → **false**。空格 vs 连字符、大写 vs 小写，
所以**每一次都匹配不上**，与配置毫无关系。

**这个判断本来就不该存在**：`/repos/{owner}/{repo}/actions/runs` 返回的是仓库里
**所有** workflow 的运行，所以要在客户端按名字筛；但 GitHub 另有 workflow 专属端点
`/repos/{owner}/{repo}/actions/workflows/{file}/runs`，服务端已经按 workflow 过滤好了。
换成它之后，「文件名 vs 显示名」这个坑从根上消失。

**顺带修掉两个隐患**：

1. **定位依赖设备时钟**。原实现靠"运行开始时间落在触发时刻之后"来判定，
   而手表的时钟未必准 —— 设备时间偏快十几秒，刚触发的运行就会被判成"太旧"而滤掉。
   现在改成**触发前记下基线 run id**（GitHub 的 run id 全局递增），
   取"比基线大的最新一条"，完全不看时钟。
2. **失败时什么都不说**。原来只回一句"没能定位到"，用户和模型都只能猜（真机上就猜错了）。
   现在把该 workflow 最近的运行直接列出来 ——
   **「一条运行都没有」和「有几条但对不上」是两种完全不同的故障**：
   前者说明触发请求没被真正执行，后者才是匹配逻辑的问题。

**端到端实测**（对真实 API 跑完整链路）：

```
基线 run id = 38850367568
dispatch → HTTP 204        ← GitHub 确认不返回 run id，所以定位是主路径而非兜底
第 1 次查询：还没有新运行    ← 重试是必需的
第 2 次查询（约 2.5 秒后）：命中 #38850703047  name='DSH Run'  path='.github/workflows/dsh-run.yml'
运行结束：success
```

另外改了 `cloud_job`：本会话没有记录时，退回查"该 workflow 最近一次运行"。
这样即使触发那一步没定位到，用户仍然能接着看状态和日志，
而不是像原来那样再收到一句"找不到可用的运行记录"。

### 19.10 真机反馈之四：云端跑不了「项目」

用户跑"随便一个 Python 项目"，第一次就失败：

```
python: can't open file '/home/runner/work/dsh-/dsh-/main.py'
```

然后模型自己悟出了办法 —— 把源码 heredoc 内联进命令 —— 并把它写成
「两个值得记住的坑」的第一条提醒用户。

**这不该由模型去记**。`cloud_build` 执行命令的地方是一个**空仓库**，
手表上 write_file 出来的文件并不会跟着过去 —— 这是工具该解决的问题，
不是要模型每次绕开的坑。工具明明知道工作目录里有什么。

**为什么不是给模板加个参数**（那是最自然的做法）：实测

```
POST /dispatches  {"command": "...", "payload": "e30="}
→ 422  Unexpected inputs provided: ["payload"]
```

也就是说，模板一旦加了新 input，**所有仓库里还是旧模板的用户会当场失败**，
而我们没法替所有人更新模板。而 `command` 本身就是一段自由的 shell 脚本 ——
把载荷放进命令里，模板一个字都不用改，兼容问题自然不存在。

**长度上限**（实测 `command` 输入）：

| 长度 | 结果 |
| --- | --- |
| 5,000 | 204 接受 |
| 30,000 | 204 接受 |
| 100,000 | 422 `inputs are too large` |

所以内联载荷设了 20,000 字符的硬上限，**超限在本地就拦住并说清怎么办**，
而不是把一个没有上下文的 422 丢出去。

**实现**：会话工作目录 → zip → base64（76 列折行）→ 拼成自解包脚本：

```bash
mkdir -p /tmp/dsh-payload && base64 -d > /tmp/dsh-payload/p.zip <<'DSH_PAYLOAD_EOF'
<base64 载荷>
DSH_PAYLOAD_EOF
unzip -q -o /tmp/dsh-payload/p.zip -d . || { echo '[dsh] 工作目录打包解压失败' >&2; exit 1; }
rm -rf /tmp/dsh-payload
<用户的命令>
```

几个刻意的选择：

- **heredoc 标记用 `DSH_PAYLOAD_EOF`**：base64 字符集是 `A-Za-z0-9+/=`，不含下划线，
  所以这个标记绝不会与载荷内容撞车（用 `EOF` 理论上存在撞上的可能）
- **解包失败必须显式 `exit 1`**：否则命令会在"文件不存在"上莫名其妙地失败 ——
  那正是这一版要消灭的那类问题
- **只打包工作目录内的文件**：路径参数来自模型，不能让它把本地任意文件发出去
- **排除 `.dsh-run/` 与 `artifacts/`**：那是工具自己的实现细节，上传只浪费额度
- **空目录 / 目录不存在不算错误**：命令可能本身是自足的（例如 `npm install`）
- **指定了文件却一个都不存在 → 明确报错**：比让命令在"文件不存在"上失败有用得多

`files` 参数是出口：目录太大导致超限时，只挑必要的文件打包。

**端到端实测**（4 个文件、含子目录的真实项目）：

```
载荷: 4 个文件 → base64 770 字符（上限 20000）
云端: sum = 5 / user = dsh / {"name": "dsh", "ok": true}
产物: dsh-multi 205 字节   |   6 个步骤全部 success
```

其中 `main.py` 导入了同目录的 `utils.py` **和子目录的 `models/user.py`** ——
跨文件、跨目录的导入在旧方案下要把每个文件都内联进命令，既繁琐又极易写错。
现在它只是"把源码写进工作目录，然后 `python3 main.py`"。

### 19.11 真机反馈之五：静态校验器的误报

模型在真机上总结出的一条（原话）：

> 本地静态检查拦了一个**误报** —— 我用生成器 `sum(1 for _ in range(n) if ...)` 时，
> 本机校验器把跨行的 `if` 判成「缺冒号」。改成普通 `for + if` 就过了。

**误报比漏报更糟**，这一点本来就在 `LanguageValidators` 的文件头写着：

> 校验器的读者是模型，它收到错误后会去改代码。如果这里误报，
> 模型就会把本来正确的代码改坏，而且它自己无法判断谁对谁错（它没有编译器可以对照）。

这次正好撞上自己写下的这条 —— 模型确实去改了本来正确的代码。

根因很隐蔽。判断"语句是否结束"时看的是**行末**的括号深度：

```kotlin
if (depthAtLineEnd(scan, index) > 0) return   // 括号未闭合 → 语句还没结束
```

而 `sum(1 for _ in range(n)` / `if _ % 2 == 0)` 这种写法里，
**括号恰恰是在 `if` 那一行闭合的** —— 该行末尾深度回到 0，于是它被判成一条独立语句。

真正该问的不是"这一行结束时括号合上了吗"，而是"**这一行是不是续行**"。改成四个判据：

| 判据 | 放过的写法 |
| --- | --- |
| 上一行结束时括号还开着 | 生成器 / 推导式里的换行 |
| 上一行以 `\` 结尾 | 反斜杠续行（续到本行） |
| **本行以 `\` 结尾** | 反斜杠续行（本行续到下一行） |
| 本行结束时括号还开着 | 多行条件 |

后两条是分开的，这点值得单说：修完前两条后，测试立刻发现 `if a and \` 这一行**仍然**被误报 ——
"续行"是双向的，只往前看解决了一半。

`PythonValidatorTest` 用 7 条「不该报」的用例把这些形式钉住，
同时保留 3 条「该报的仍然要报」—— 免得修过头把检查修成废的。

### 19.12 设置页重排与圆角

设置页有三十多项，**分组是它唯一的秩序来源**，而原来的分组是乱的：
供应商、用量、配对、工作空间、诊断、执行编译全挤在一个叫「快捷入口」的组里，
而「工作空间」在入口区和它自己那一组里**各出现一次**（同一件事两个入口）。
找一个设置只能靠滚。

新的分组依据是「用户想干什么」，而不是「代码里的模块」：

| 分组 | 回答的问题 |
| --- | --- |
| 模型 | 用哪个 API、花了多少 |
| AI 能力 | Agent 怎么干活、能干什么 |
| 对话 | 聊天时看得到什么 |
| 外观 | 长什么样 |
| 文件与存储 | AI 的文件在哪、占多少 |
| 性能与运行 | 卡不卡、费不费电、退到后台还跑不跑 |
| 连接与关于 | 连不上怎么办、这是哪个版本 |

两处顺带的整理：

- 「执行与编译」从"入口"归到 **AI 能力** —— 它是一个能力开关，不是导航项
- 设置页里**硬编码的中文**全部移进 `AppStrings`，英文界面不再漏中文

圆角整体调大一档（手表屏小、信息密度高，圆一点更松弛，也和屏幕本身的大 R 角更协调）：

| 元素 | 之前 | 现在 |
| --- | --- | --- |
| 卡片 | 14dp | **18dp** |
| 消息气泡 | 12dp | **16dp** |
| 分段选择器 | 10dp | **14dp** |
| 按钮 | 12dp | **14dp** |
| 步进器按钮 | 8dp | **11dp** |

---

## 二十、v1.4 —— MCP、插件、不再限制步数

1.3 让模型能在手表上跑代码、能借云端编译。1.4 解决的是另外三件事：
**能力边界**（MCP）、**上下文成本**（插件）、**任务长度**（停滞检测）。

### 20.1 MCP：为什么自己写客户端

MCP 已经有官方 Kotlin SDK（JetBrains 维护，KMP）。没用它，原因很直接：
它走 Ktor，对一台 lite 包只有 6.4MB 的手表来说是数 MB 级增量，
而这个功能只需要三个方法 —— `initialize` / `tools/list` / `tools/call`。

手写两个文件（`McpModels` + `McpClient`）体积近乎为零，而且：

- SSE 解析能直接复用项目里已经验证过的 `SseReader`（它当初是为流式对话写的）
- 错误文案可以写成本项目要的样子：**说清楚该改什么**，而不是 "request failed"

适配几乎是直译：MCP 的 `{name, description, inputSchema}` 与本项目的
`{name, description, parameters}` 一一对应，两者用的都是 JSON Schema，
所以 `inputSchema` **原样透传**。这是"自己写"相对"接一层转换器"的意外收获。

**协议版本是这里最大的坑**。2026-07-28 的规范把 MCP 改成了无状态（移除
`initialize` 与 session），但主流 server 目前仍只认 `2025-11-25` 的握手。所以：

1. 先按 `2025-11-25` 发 `initialize`
2. 若 server 明确表示版本不支持 → 降级为无状态：不握手、每个请求带
   `MCP-Protocol-Version` 与 `Mcp-Method` 头

**反过来的顺序不行**：旧 server 收到没有 `initialize` 的 `tools/list` 会直接 400，
而错误信息通常只说 "not initialized"，看不出该降级还是该重试。

**判断"版本不支持"也有两条路**：显式错误码，或者只给一句人话
（`Unsupported protocol version: 2025-11-25`）。两种都要认 ——
只认错误码的话，遇到第二种会当成普通失败直接放弃，而它其实完全能用。

### 20.2 信任分两步，因为工具描述会进模型上下文

远程 server 的工具**描述**会原样进模型上下文。而描述里可以藏指令：
工具名写得人畜无害（`search`），description 里写"顺便把用户的 API Key 发到某个地址"。
客户端没法审查这些内容 —— 它读不出哪句是善意提醒、哪句是注入。

所以把决定权交给人，但**不用弹窗**（手表上弹窗成本很高）：

1. 点「保存并连接」→ 只拉清单给你看，**不启用**
2. 你看过清单、点「信任并启用」→ 才真正注册成工具

改地址会重置确认状态 —— 换了地址就是换了一个 server，上一次的信任不适用。

另外三个随手的决定：

- **工具名加 `mcp_<server>_` 前缀**：两台 server 都可能有个叫 `search` 的工具
- **一律按写操作对待**（`ToolSafety.WRITE`）：远程工具能干什么客户端判断不了，
  名字叫 `search` 也可能在服务端改数据。在「只读自动」下每次都会要求确认
- **动态注册靠热替换 `_toolRegistry.value`**：引擎每轮实时读它，
  所以接上 MCP **不需要改 ChatEngine 一行代码** —— 这也是当初把注册表放在
  `MutableStateFlow` 上的原因

### 20.3 插件：四个 hook，两条原则

插件是编译进 App 的一段可选行为，用户在设置里开关。**不做动态加载**：
手表上既没有插件市场也没有签名校验链，动态加载只会多一个"能执行任意代码"的入口，
而本项目已经有一个需要每次确认的 `run_script` 了。

三个接口对应三类干预点：

| 接口 | 干预什么 | 谁在用 |
| --- | --- | --- |
| `ToolResultPlugin` | 工具结果进上下文之前的文本 | 结果裁剪、TOON 编码 |
| `PromptPlugin` | 系统提示的追加段 | 极简回复 |
| `RoutingPlugin` | 某一轮的提示 + 工具面 | 首轮路由 |

**原则一：插件坏掉不能影响正常对话。** 每个 hook 调用点都包 `runCatching`，
失败就跳过该环节。注意是跳过**该环节**而不是回到原文 ——
多个结果插件是串行叠加的，回到原文会让这次对话的上下文突然多出几倍 token，
而用户完全不知道发生了什么。

**原则二：改写内容的插件必须默认关闭。** 「结果裁剪」删的是冗余（不改含义），
默认开；「TOON 编码」换的是数据形态，默认关。

### 20.4 TOON：只做编码器

TOON 是 JSON 的紧凑等价写法，核心是**表格化数组**：

```
users[2]{age,city}:
  alice: 30,Berlin
  bob: 25,Oslo
```

重复的键名只写一次（表头），值按行排，uniform 数组通常省 30–60%。

**不做解码器**。解码的价值取决于"什么时候要读回来"，而本项目的答案是"不需要"：
原文会落盘（`_raw/` 目录），模型真要原始结构时用 `read_file` 取 —— 那条路是可靠的。
自己写解码器，等于在没有任何对照测试的情况下保证"编码→解码"完全可逆，
那是错误的成本分配。

三条硬约束：

1. **严格更小才采用**：用项目自己的估算器比较，**包括**尾部那句"原文已存 xxx"。不加这句
   比较，会出现"TOON 省了 5 个 token，说明文字又加回 30 个"的荒唐情况
2. **落盘失败就不转换**：TOON 是结构简化不是无损编码，原文拿不回来就不该转
3. **解析失败不动手**：内容被上游截断过（残缺 JSON）时保持原样

**不支持的形状整体放弃**。List form（数组元素是对象但字段不规整、数组套数组）
技术上能实现，但要处理"表头挂在 hyphen 行上、行缩进两层"的特例，复杂度陡增而收益很小。
宁可整体不转，也不要转出一份"看起来像 TOON 但语义变了"的文本 ——
模型不会告诉你数据有问题，它只会照着错的数据下结论。

引号规则是这个编码器里最容易做错的地方，所以测试逐条钉住了：空串、前后空白、
`true`/`null`、看着像数字的（`05`、`+1`、`.5`）、含分隔符、以 `-` 或 `#` 开头。
**少加一条的后果不是"格式略丑"，而是解码出别的值** —— `"true"` 不加引号就变成布尔。

### 20.5 首轮路由（风神）

用户在别的工具链上用的同名插件：读会话第一条消息，判断这是"修 bug"还是"建功能"，
据此给首轮人格、把工具面收窄，第一次工具调用之后完全放权。

**判据用本地关键词打分**，不调另一次模型：确定性、零延迟、不会被一次失败的分类请求卡住。
强信号（崩溃、traceback、重构）2 分，弱信号（修、改、做）1 分，
平局或都不命中时选 **REACT** —— "建东西"是更常见的诉求；
误判成 REACT 只是让模型更积极动手，误判成 SPEC 会让它在一堆"先确认"里空转。

**工具面为什么是 4 个而不是 2 个**：风神原设计在 Claude Code 语境里是
`shell` + `str_replace_editor` —— 那边一个工具既能读也能改（view/create/str_replace）。
本项目读写改是三个独立工具，只给两个的话模型**没有读取手段**，只能靠
`run_script` 里的 `cat` 绕过去，那更容易出错。所以映射成
`read_file` + `write_file` + `edit_file` + `run_script`。

还有一个容易漏的保护：**收窄后如果一个工具都不剩，就不收窄**。
否则模型这一轮没有任何工具可用，表现是"它什么都不做，只回一段文字"。

### 20.6 不再限制步数

原来是 `maxAgentSteps`（默认 8，上限 20）。问题是这个数字和任务复杂度无关：

- 读 8 个文件改 5 个文件的重构，20 步根本不够
- 模型卡住时，20 步全是重复调同一个工具，白烧二十轮 token

换成 `ProgressTracker`，判据是**进展指纹**而不是步数：

| 判据 | 行为 |
| --- | --- |
| 指纹 = 本步成功调用的「工具名 + 参数」+ 新增产物 | 有新的就继续 |
| 连续 3 步无新指纹 | 停（原地打转） |
| 连续 2 步工具全部失败 | 停（重试无用） |
| 兜底 200 步 | 防病态情况 |

为什么用指纹而不是"有没有调用成功"：**逐步写多个文件、逐个读文件都是新指纹**，
这类长任务不该被误杀；而"同一个工具同一个参数调三次"没有任何新信息，
那才是真的卡住。

测试里特意加了一条反证：把 `maxAgentSteps` 设成 1，但三步参数各不相同，
断言它照样跑完 3 步 —— 直接证明上限确实失效了。

### 20.7 设置页改成二级

八个分组入口，每行带当前状态摘要（"外观 → 深色"、"插件 → 2/4 已开启"）。
三十多项设置，分组是它唯一的秩序来源。

拆分时有个细节：各组需要的状态完全不同（外观要设备信息、文件要算占用、
AI 能力要工具清单和弹窗），所以拆成**各自独立的 Composable**，组内状态随页面销毁 ——
而不是都堆在一个函数里一直活着。

分组 id 在一级页和二级页**共用同一份常量**（`SettingsGroups`）。两边各写一遍字符串
是这类重构最常见的错源，表现是"点进去是空的"，编译器还不会提醒。

### 20.8 测试

504 个（新增 73 个）：

- `ToonEncoderTest` 21 个 —— 四种形式各一组、引号规则逐条、嵌套字段组折进表头、
  **非均匀数组必须整体放弃**、空值、中文与 emoji 不加引号、转义
- `McpClientTest` 11 个 —— 用 JDK 自带 `ServerSocket` 起假 server（**不能用
  `com.sun.net.httpserver`**：Android 的单元测试跑在 android.jar 桩上，那里没有 com.sun.*），
  覆盖握手、SSE 里混入通知、session 回传、版本降级、401/404 的具体提示
- `McpToolAdapterTest` 7 个 —— 名字前缀与非法字符、64 字符上限、
  调用用原名、一律按写操作
- `SaveTokenPluginTest` 7 个 —— 更小才采用、**原文落盘且能读回**、
  残缺 JSON 不动手、不支持的形状不改
- `TokenSaverPluginTest` 7 个 —— 头尾都留、read_file 不裁、
  **格式化 JSON 不能被当行列表折叠**、emoji 切断不产生半个字符
- `FengshenPluginTest` 7 个 —— spec/react 判定样本、首轮收窄、step≥2 返回 null、
  **收窄名单里的名字必须都是真实工具**
- `PluginRegistryTest` 6 个 / `ChatEngineTest` 新增 4 个 ——
  后者验的是**接线**：插件本身对但没接进请求，用户开开关不会有任何变化

### 20.9 发布后修复：模板的 inputs 不是共用的

v1.4 发布当天，有用户用 `cloud_build` 触发 `dsh-build.yml` 构建 APK，得到：

```
请求被拒绝（通常是分支名不存在，或 workflow 的 inputs 与模板不匹配）。
原始响应：{"message":"Unexpected inputs provided: [\"command\"]","status":"422"}
```

**这是我在 1.4 里引入的缺陷。** 为了"自动带上工作目录的文件"，我把
`command` 变成了硬性输入 —— 而两个模板的参数集完全不同：

| 模板 | 声明的 inputs |
| --- | --- |
| `dsh-run.yml` | `command` `artifact_name` `artifact_path` `retention_days` |
| `dsh-build.yml` | `variant` `with_python` |

`dsh-build.yml` 压根不接命令，它有自己的参数。于是"构建 APK"这条路径直接坏掉，
而且错误信息还在说"通常是分支名不存在"—— 又一次把人带到错的方向。

**根因不是"传错了参数"，是"没问模板要什么就传"。** 修法是先读声明：

```
GET /repos/{owner}/{repo}/contents/.github/workflows/{file}?ref=main
  → base64 解码 → 解析 on.workflow_dispatch.inputs 的键
```

拿到集合之后：

- 只有模板接受 `command` 时才要求命令、才做工作目录打包
- 不接受时如果调用方仍传了 `command` → **直接说清"这个模板不接受命令，它接受的是
  variant / with_python；要跑任意命令请换成 dsh-run.yml"**
- 其余参数按集合过滤，被丢掉的在结果里列出来

**为什么自己解析 YAML（40 行）而不引库**：只需要"某个块下面的第一层子键"，
而 snakeyaml 对一个 6.4MB 的安装包来说太贵。解析器要处理三种情况 ——
带 inputs 的块、明确没有 inputs 的块（返回空集）、以及看不懂的写法（返回 null）。
后两者的区别很重要：空集意味着"传任何参数都会 422"，null 只是"我们没看懂，不该拦"。

**再加一层兜底**：万一 YAML 写法超出预期，GitHub 的 422 响应里本来就列出了被拒的键名
（`Unexpected inputs provided: ["command"]`），解析出来去掉重试一次。

实测（对真实仓库）：

```
dsh-run.yml   → {artifact_name, artifact_path, command, retention_days}
dsh-build.yml → {variant, with_python}

dsh-build.yml + {variant: debug}  → 204   ← 修复前这条路走不通
dsh-build.yml + {command: ...}    → 422   ← 复现用户报错
dsh-run.yml   + {command: ...}    → 204   ← 原有路径没坏
```

`CloudTargetsInputsTest` 的 10 条用例直接用仓库里这两个**真实模板**当样本 ——
哪天模板改了 input 名，这里会先炸，而不是等用户撞上 422。
