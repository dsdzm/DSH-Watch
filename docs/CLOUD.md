# 云端编译使用说明

> 适用版本：v1.4 起。这份文档解决一件事 —— **为什么我按直觉操作，云端总是失败。**

---

## 一、先搞清楚它到底是什么

`cloud_build` 做的事只有一件：**在你自己的 GitHub 仓库里触发一次 workflow，然后等它跑完、把产物取回来。**

所以有三件事必须先理解，否则后面每一步都会碰壁：

| 事实 | 后果 |
| --- | --- |
| 云端机器是一台**干净的 Ubuntu**，不是你手表的环境 | 没有 JDK、没有 Android SDK、没有 Python 包。需要什么得自己装（或换一个装好的模板） |
| 执行目录是**你仓库的一份全新 checkout** | 手表上 `write_file` 写的文件会**自动打包上传**，但仓库里原有的文件也在那儿 |
| 产物**不会自动回来** | 只有被 `upload-artifact` 上传的东西才会下载回手表。模板上传的是 `dsh-out/` 目录 |

最后一条是踩坑最多的地方，见第四节。

---

## 二、两个模板，分工完全不同

App 内置两个模板，装进你仓库后就是 `.github/workflows/` 下的两个文件：

| | `dsh-run.yml` | `dsh-build.yml` |
| --- | --- | --- |
| 干什么 | 跑**任意命令** | 构建**本项目（DSH-Watch）**的 APK |
| 参数 | `command`（必填） | `variant`（debug/release）、`with_python` |
| 环境 | 基础 Ubuntu | JDK 17 + Android SDK + Gradle 缓存 |
| 不接受 | — | **不接受 `command`**，传了直接 422 |

**判断口诀**：

- 要**编 Android** → `dsh-build.yml`
- 要**跑别的东西**（Python 脚本、C 程序、Go 编译）→ `dsh-run.yml`
- 不确定 → 先想"需不需要 JDK/Android SDK"，需要就用 `dsh-build.yml`

> ⚠️ `dsh-build.yml` 里的构建命令是 `./gradlew :app:assembleDebug`，
> **只对 DSH-Watch 这个仓库有效**。它不是一个通用的 Android 构建器 ——
> 想构建别的 Android 项目，得自己在仓库里写一个 workflow。

---

## 三、三个能直接抄的例子

### 3.1 跑一段 Python

```
1. write_file  path=main.py   content=<你的代码>
2. write_file  path=utils.py  content=<依赖>
3. cloud_build workflow=dsh-run.yml
              inputs={"command":"python3 main.py"}
```

工作目录里的 `main.py`、`utils.py` 会**自动打包上传**，解压到云端工作目录，然后执行那条命令。
跨文件 import、子目录（`models/user.py`）都能正常工作 —— 不需要手动把源码塞进命令里。

### 3.2 编一个 C 程序

```
1. write_file  path=main.c  content=<代码>
2. cloud_build workflow=dsh-run.yml
              inputs={"command":"gcc main.c -o main && ./main && cp main dsh-out/"}
```

注意最后那个 `cp main dsh-out/` —— 不加的话，程序编译出来了、也运行了，
但**你不会拿到那个二进制文件**（原因见第四节）。

### 3.3 构建本项目的 APK

```
cloud_build workflow=dsh-build.yml
            inputs={"variant":"debug"}
```

**不要传 `command`**。这个模板不接受它，传了会返回：

```
422  Unexpected inputs provided: ["command"]
```

想要含 Python 引擎的 full 包：`inputs={"variant":"debug","with_python":"true"}`。
产物是 `dsh-out/` 里的 APK，会自动下载到会话的 `artifacts/apk/` 下。

---

## 四、产物怎么才能回到手表

**只有被上传的东西才会回来。** 模板的做法是：

```
你的命令 → 产出文件 → 复制到 dsh-out/ → upload-artifact 上传 → 手表下载解压到 artifacts/
```

### 4.1 `dsh-run.yml` 会自动兜底

`dsh-out/` 为空时，模板会自动从这些目录收集 `apk / aab / jar / zip / tar.gz / so / exe / whl`：

```
app/build/outputs   build/outputs   build   dist   out   target/release   target/debug   bin
```

所以 `cargo build --release`、`go build`、`./gradlew assembleDebug` 这类产物在标准位置的，
通常不用手动 `cp`。但**目录不在上面这个列表里**的，还是要自己复制：

```bash
生成到 ./result/ 下 → 必须 cp result/* dsh-out/
```

### 4.2 "运行成功但没有可下载的产物"

这是最常见的一种"失败"—— 其实运行是成功的，只是没有东西上传。

App 现在会明确告诉你这句话，而不是留下一个空白的 `artifacts/`：

```
云端运行成功，但没有可下载的产物。
模板只上传 dsh-out/ 目录（为空时会自动收集 build/dist 等常见目录）。
```

**遇到它先检查两件事**：

1. 命令真的产出了文件吗？（`ls -la` 一下，日志里能看到）
2. 文件在 `dsh-out/` 或上面的自动收集目录里吗？

---

## 五、常见错误对照表

| 你看到的 | 真正的原因 | 怎么办 |
| --- | --- | --- |
| `422 Unexpected inputs provided: ["command"]` | 用了 `dsh-build.yml` 却传了 `command` | 换 `dsh-run.yml`，或去掉 `command` |
| `422 通常是分支名不存在` | 旧版 App 的兜底文案，真实原因同上 | 升级到 v1.4 修复版 |
| `运行成功但没有可下载的产物` | 产物不在 `dsh-out/`，也不在自动收集目录 | 命令末尾加 `cp <产物> dsh-out/` |
| `python: can't open file '.../main.py'` | 旧版没有自动上传工作目录 | 升级到 v1.4 |
| `./gradlew: not found` 或 `JAVA_HOME` 报错 | 用 `dsh-run.yml` 跑 gradle，那台机器没有 JDK | 改用 `dsh-build.yml` |
| `Could not find or load main class`（gradle 相关） | 同上 | 同上 |
| `401 / 403` | Token 无效或没有 Actions 权限 | 重新生成 PAT，勾 `repo` + `workflow` |
| `404 地址不对` | 仓库字段写成了 URL | 只写 `owner/repo` |
| 一直"排队中" | Actions 队列忙，或仓库的 Actions 被禁用 | 去仓库 Settings → Actions 确认已启用 |

---

## 六、想让它"从零造一个 App"？先看清楚成本

云端能编译 Android 项目，但从零造一个能装的 APK 需要三样东西同时具备：

| 需要 | 现状 |
| --- | --- |
| 完整项目骨架（gradle wrapper、`build.gradle`、`AndroidManifest.xml`、源码） | App 可以在工作空间里写出来，但**这些文件会被传到仓库根**，和你仓库原有内容混在一起 |
| JDK + Android SDK | 只有 `dsh-build.yml` 装了；`dsh-run.yml` 是干净 Ubuntu |
| 签名 | 不签名的 APK 只有 debug 签名，能装但**不能覆盖安装正式版** |

**`dsh-build.yml` 只构建 DSH-Watch 自身** —— 它跑的是仓库根那个 `./gradlew`。
构建别的项目得自己写 workflow，至少要有：

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: '17' }
- uses: android-actions/setup-android@v3
- run: ./gradlew assembleDebug
```

光装 Android SDK 就要 1–3 分钟，每次触发都付这个代价。

**更现实的路径**：把项目骨架（所有 `gradle` 文件 + 一个空 Activity）**一次性**提交进仓库，
之后每次只让 AI 改源码 —— 云端就是增量构建，几十秒出包。

> 换句话说：让它"改 App"很顺，让它"从零造一个 App 并交付可安装的包"，
> 需要你先花一次时间把骨架准备好。这不是工具的限制，是 Android 构建本身的成本。

---

## 七、限制

| 项 | 值 | 说明 |
| --- | --- | --- |
| 单次运行上限 | 20 分钟 | 模板里 `timeout-minutes: 20` |
| 内联载荷上限 | 20,000 字符（base64 后） | 工作目录太大时用 `files` 参数只挑必要的 |
| 产物保留 | 7 天（run）/ 14 天（build） | 过期后 `cloud_job(action=artifacts)` 拿不到 |
| 并发 | 一次一个会话 | 别重复触发，查进度用 `cloud_job` |
| 费用 | 公开仓库免费 | 私有仓库消耗 Actions 分钟数 |

---

## 八、装模板

设置 → 执行与编译 → 云端编译 → **安装 workflow 模板**。

它会往你的仓库写两个文件（`dsh-run.yml` / `dsh-build.yml`），提交后 GitHub 需要十几秒索引。

> 已经在 `dsdzm/DSH-Watch` 里的模板是随仓库维护的，**不需要重复安装**。
> 但如果你仓库里的模板是旧版（没有自动收集产物那一步），重新点一次「安装 workflow 模板」会覆盖成新版。
