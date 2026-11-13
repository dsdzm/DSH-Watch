# 参与贡献

感谢你愿意改进 DSH 肥鱼。这是一个跑在手表上的应用，有些约定和普通 Android 项目不太一样，
先花两分钟看完能省掉来回。

## 环境

| 项 | 版本 |
|---|---|
| JDK | 17 或 21 |
| Android SDK | Platform 35、Build-Tools 35.0.0 |
| Gradle | 用仓库自带的 wrapper（8.11.1） |

```bash
git clone https://github.com/dsdzm/DSH-Watch.git
cd DSH-Watch
./gradlew :app:testDebugUnitTest
```

首次同步会下载 200–400 MB 依赖。国内网络见 README 的「构建 → 国内网络」。

## 提交之前

三条硬性要求：

1. **全量单元测试必须通过**
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
   目前 951 个用例。修 bug 请同时补一个能复现该 bug 的用例 —— 这个仓库里几乎每个
   修复都带回归测试，缺了会在 Review 时被要求补上。

2. **构建通过**
   ```bash
   ./gradlew :app:assembleRelease
   ```

3. **不要提交敏感文件**
   `keystore.properties`、`*.jks`、`local.properties`、`dist/`、`.env` 都已在
   `.gitignore` 里。提交前可以用 `git status --short` 确认一遍。

## 代码约定

- 语言是 **Kotlin**，UI 是 **Compose**，不引入 Hilt / Room / navigation-compose / WebView。
  这不是口味问题：手表内存 256 MB–1 GB，每加一层依赖都是实打实的常驻内存。
  如果某个改动确实需要新依赖，请在 PR 里说明它换来了什么、代价是多少。
- 新增依赖前先看 `gradle/libs.versions.toml`，统一在那里管理版本。
- 面向用户的文案走 `ui/i18n/AppStrings.kt`，中英都要写 —— 它是接口实现，漏字段编译不过。
- 注释写「为什么」，不写「做了什么」。这个仓库里大量注释在记录踩过的坑，请保持这个风格。

## 提交信息

用 [Conventional Commits](https://www.conventionalcommits.org/) 前缀：

```
fix(canvas): 公式定界符 \Big 未翻译
feat(bili): 增加收藏夹列表工具
docs(readme): 重写项目主页
```

正文里说清楚**根因**，而不只是现象。例如：

```
fix(geom): 线段端点把 x 当成 y

py(a.first) 把点的 x 传进了 y 参数。A(0,0) 恰好不受影响，
于是三条边全落在 y=x 上，视觉上缩成一条斜线。
```

## Pull Request

- 一个 PR 只做一件事。顺手重构请单独开。
- 描述里写：改了什么、为什么、怎么验证的。
- **涉及 UI 或设备行为的改动，请附真机截图或录屏**。手表上的布局问题在模拟器里
  往往看不出来，这个仓库的历史里有过多次「本地全绿、装上去才发现」的事故。
- 我会在有设备的时候验证后再合并；如果长时间没有回应，可以在 Issue 里 ping。

## 发版节奏

**版本号是个稀缺动作，不是每次提交的结果。**

手表上的验证成本很高：装一次、看一眼、再改，一轮下来十几分钟，中途还经常要
重装（签名一致才能覆盖安装）。每修一个点就发一版，结果是版本号两位数地往上走、
Release 页面被碎片化的补丁塞满，用户也分不清哪一版才是「可以放心用」的那个。

所以：

- **平时不动版本号。**`versionCode` / `versionName` 保持不变，改动照常提交、照常跑测试。
- **要装到表上看效果**，用 debug 包或本地 `assembleRelease`，不要为此发版。
- **CHANGELOG 攒着写。**未发版的改动放在 `## [未发布]` 下，发版时把这一段
  改成 `## [x.y.z] — 日期（versionCode N）`，再补一条 `AppChangelog` 条目。
- **发版的门槛是「一批」不是「一个」**：手上攒了几处改动、且都稳定了，才动版本号。

有一点绕不开：**发版必须递增 versionCode**。应用内的更新检查就是拿它和
GitHub Releases 上的最新版比大小的，同号或更小的包推上去，老用户收不到，
新用户装了也会被判定为「已是最新」。所以「不急升级」的意思不是「永远不升」，
而是**不要为一个修复单独升**。

### 两条通道

「攒一批再发」之后，中间状态总得有人先试，所以给出一条测试通道：

| 命令 | 通道 | 谁能看到 |
|---|---|---|
| `python3 tools/publish_update.py` | 稳定版 | 所有人，`/releases/latest` 指向它 |
| `python3 tools/publish_update.py --beta` | 测试版 | 只在 App 里把「更新通道」切到「测试版」的用户 |

预发布**同样要递增 versionCode** —— 客户端的更新判断只认它。测试版的版本名建议
带后缀（`1.23.0-beta.1`），这样截图和反馈里一眼能看出是哪条线。

发现测试版有问题时，**修完重发**即可（`--force` 重建同名 release），不要让用户
降级回稳定版：系统会拒绝 versionCode 更小的包，表现为「点了安装没反应」。

## 文档

- 用户可见的变更写进 `CHANGELOG.md`，格式参考已有条目（`## [版本] — 日期（versionCode N）`）。
- 纯修复版本在 `AppChangelog.kt` 里留空条目，这是既有约定。
- 架构层面的改动同步更新 `docs/PROJECT-DETAILS.md`。

## 行为准则

技术上可以尖锐，对人请保持基本的尊重。人身攻击、歧视性言论、恶意刷屏会被直接移除。
