# 协作约定与项目地图

> 这份文件是给**接手的人（或 AI Agent）**看的。先读完再动代码。
> 发版节奏、密钥位置、踩过的坑都在这里；具体实现细节见 `docs/PROJECT-DETAILS.md`，
> 换环境的完整步骤见 `docs/HANDOFF.md`。

## 0. 一句话说清这是两个东西

| | 是什么 | 在哪 |
|---|---|---|
| **DSH 肥鱼** | 原生 Android 客户端（Kotlin + Compose），手表 / 手机同一份 APK | `/workspace/dsh-watch`，公开仓库 `dsdzm/DSH-Watch` |
| **酒馆社区服务端** | PHP + MySQL 站点，给 App 提供卡片投稿 / 浏览 / 评分 / 评论 | `/workspace/dshwatch-ugc`，线上 `dshwatch.vxni.ink` |

两边**没有共享代码**，只靠 HTTP JSON 接口耦合。改接口必须同时改两边 + 线上部署，
顺序是：先服务端（向后兼容）→ 再 App。

---

## 1. 目录布局

### 1.1 App 仓库 `/workspace/dsh-watch`

```
app/src/main/java/com/dshwatch/     362 个 kt，约 11 万行
├─ core/            AppContainer（手写 DI，没有 Hilt）、日志、JSON、ID
├─ domain/          ChatEngine（对话与 Agent 循环）、TokenEstimator、PerfProfile
│  ├─ agent/        ToolRegistry、tools/（52 个工具）、exec/、cloud/、mcp/、build/（静态校验）
│  ├─ community/    社区领域模型（角色卡、写作卡、导入导出）
│  ├─ plugin/       声明式插件、Cordis 转译、工具结果插件与 TOON 编码
│  ├─ update/       UpdateChecker（GitHub Releases）、AppChangelog
│  └─ model/        ChatMessage / Conversation / Provider / Media
├─ data/            SettingsRepository（DataStore）、ConversationStore（JSON）、SecureKeyStore
│  └─ community/    CommunityApi、CommunityCache（列表/详情本地缓存）
├─ net/             Http、SseReader、backend/（OpenAI 兼容 / DSH 服务器）、dshweb/、ssh/、remote/、qq/
├─ service/         流式期间的前台服务、无障碍设备操控、悬浮提示
└─ ui/              AppRoot、nav、theme、markdown、canvas、media
   ├─ screens/      手表 · 经典 + community/（社区各页）
   ├─ m3e/          手表 · M3E（Wear Material 3 Expressive）
   └─ phone/        手机（Material 3、底部导航、历史抽屉）

app/src/main/res/raw/sectigo_r46.crt  内置根证书，别删（见 §6.1）
app/src/main/res/xml/network_security_config.xml  信任锚 = 系统 + R46
app/src/test/java/com/dshwatch/       175 个测试文件，1697 条用例
tools/publish_update.py               发版脚本（只调 GitHub Release API）
.env / .env.example                   GITHUB_TOKEN（不进版本库）
dsh-watch.jks / keystore.properties   签名材料（不进版本库）
.github/workflows/                    ci.yml（PR 跑测）、dsh-build.yml（云端出 APK）、dsh-run.yml（通用云端执行）
```

**关键约定**：没有 Hilt、没有 Room、没有 navigation-compose、没有 WebView。
新增依赖前先想清楚会不会把 12 MB 的包体撑大 —— 这是本项目的硬约束，不是偏好。

### 1.2 服务端 `/workspace/dshwatch-ugc`

**这个目录不是 git 仓库**，靠面板 webftp 上传部署。改动要自己留副本。

```
certs/
  fullchain.pem   叶证书
  chain.pem       叶 + 中间证书（面板里贴的就是这个）
  privkey.pem     私钥，600
  ca.crt          中间证书原件（DER）
  ca_pem.crt      中间证书（PEM）
wwwroot/          站点根，对应线上 /wwwroot/
  config.php      数据库凭据 + 后台密码哈希（.htaccess 挡住，但**别提交到任何仓库**）
  install.php     首次安装：建表 + 写后台密码哈希 + 生成 .installed 锁
  api/index.php   全部 JSON 接口，单文件路由（1042 行）
  admin/index.php 审核后台（tab：pending / approved / rejected / users / comments / reports）
  index.php       社区首页（网页版列表）
  card.php        卡片详情（网页版，含评分与评论）
  assets/         site.css、admin.css
  .htaccess       DirectoryIndex、禁目录列表、禁 config.php 与 zip/sql 下载
  migrate.php     一次性迁移脚本（带 key，跑完自删）—— 历史遗留，线上已删
  migrate2.php    同上，第二批（email / parent_id / likes / notices）
  unpack.php      一次性解压脚本（历史遗留）
  unpack2.php     同上
README.md         部署、备份、HTTPS 续期、接口表
```

> 根目录下 `migrate*.php` / `unpack*.php` 都是**一次性脚本的本地副本**。
> 线上用完即删（脚本自己 `@unlink(__FILE__)`）。再传上去前先改 key。

---

## 2. 网络结构

### 2.1 客户端会连出去的所有地方

| 用途 | 地址 | 协议 / 鉴权 | 失效后果 |
|---|---|---|---|
| **社区 API** | `https://dshwatch.vxni.ink/api/` | HTTPS + JSON，`Authorization: Bearer <token>` | 社区整块不可用 |
| **检查更新** | `https://api.github.com/repos/dsdzm/DSH-Watch/releases[/latest]` | HTTPS，匿名（60 次/小时/IP） | 提示「GitHub 限流」 |
| **下载 APK** | `https://ghfast.top/<github-url>` → `https://gh-proxy.com/<github-url>` → 直链 | HTTPS，sha256 校验 | 自动降级到下一个源 |
| **模型调用** | 用户自配，任意 OpenAI 兼容（含 Ollama 局域网） | HTTPS + SSE，Bearer | — |
| **DeepSeek 账号** | `platform.deepseek.com` | PKCE + 回环回调 | 登录失败，不影响 API Key 方式 |
| **内置免费通道** | `api.agnes-ai.cn`、`open.bigmodel.cn` | Key 由构建期注入 `BuildConfig` | 源码自建包没有内置通道 |
| **DSH 服务器（SSH）** | 用户电脑 `:22` → 远端 `dsh web --port 0` → 本地端口转发 | SSH 密码 | — |
| **DSH Remote** | 用户自建 Relay | WSS + Noise IK 四层隧道 | — |
| **QQ** | 本机 OneBot（NapCat / SnowLuma），默认 `127.0.0.1:3001` | HTTP / WS + token | **当前暂停**，见 §7.3 |
| **B 站** | `api.bilibili.com`、`passport.bilibili.com` 等 | HTTPS + cookie | 16 个 bili 工具不可用 |

**更新链路是本项目最脆的一环**，改 `UpdateChecker` 前必读：

- 客户端只认资产名 `dshwatch-<versionCode>.apk`。名字错 ⇒ 永远显示「已是最新」，**且不报错**。
- GitHub API 不返回 versionCode，只能从文件名解析，所以这条命名是协议，不是习惯。
- 直链 `release-assets.githubusercontent.com` 在国内实测 9~25 KB/s，必须走反代；
  反代是第三方（TLS 在其处终结），所以 **sha256 校验不能省** —— 摘要取自 GitHub 资产 digest。

### 2.2 社区服务端

```
域名   dshwatch.vxni.ink
解析   starzv 香港多线，A 记录 → 149.104.21.22 ~ .26（多 IP 轮询）
主机   星辰云虚拟主机（Kangle），面板 https://aspanel.starzv.com/vhost/
站点根 /wwwroot/        数据库 localhost / MySQL / utf8mb4
PHP    7.2，单文件路由，PDO 预处理，无框架、无 Composer、无定时任务
配额   128 MB 空间 / **32 MB 数据库** / 24 G 月流量 / 4 Mbps
       —— 库很小，加字段可以，加表要想清楚；别存图片和附件
证书   ZeroSSL RSA DV SSL CA 2 签发，2026-11-28 ~ 2027-02-26
       面板读的是根目录 ssl.crt / ssl.key（不是 certs/ 里的文件名）
```

**数据表（10 张）**

| 表 | 作用 | 关键约束 |
|---|---|---|
| `users` | 账号 | `username` 唯一，`email` 必填 |
| `tokens` | 登录凭证 | 按 token 查用户；封号时连带删 |
| `cards` | 卡片（角色卡 / 写作卡） | `status`：pending / approved / rejected / **deleted（软删）** |
| `card_likes` | 卡片点赞 | 主键 `(card_id, user_id)`，重复点靠主键冲突挡住 |
| `audit` | 审核记录 | — |
| `comments` | 评论 | `parent_id` 一层回复，`likes` 冗余计数，`status` 可见/隐藏 |
| `comment_likes` | 评论点赞 | 主键 `(comment_id, user_id)` |
| `ratings` | 评分 | 主键 `(card_id, user_id)`，**一人一分，不可改** |
| `reports` | 举报 | 有卡片 / 评论两类目标，`status` 待处理/已处理 |
| `notices` | 站内消息 | 「有人赞了你的评论」，带 `read_at` |

**接口（18 个，全在 `api/index.php`）**

```
GET  ?a=ping ?a=stats ?a=list ?a=card&id= ?a=comments&id=&page= ?a=mine
POST ?a=register ?a=login ?a=logout ?a=me ?a=submit ?a=like ?a=comment
     ?a=clike ?a=rate ?a=delete ?a=report ?a=notices ?a=notice_read
```

**PHP 7.2 是硬约束**：不能用箭头函数（7.4）、`str_contains` / `match`（8.0）、
命名参数、`?->`。本地 `php -l` 只能查语法，跑不起 7.2 的运行时差异 —— 改完直接传线上验。

约定：
- 成功 `{"ok":true, ...}`，失败 `{"ok":false,"error":"中文人话"}` + 4xx；
  错误文案是给用户看的，别写英文栈。
- 幂等优先用主键冲突，不靠「先查再写」——并发下先查再写一定会漏。
- 删除一律**软删**（`status='deleted'`），硬删会留下孤儿评论和评分。

### 2.3 数据流

```
                    ┌─────────────────────────────┐
   用户手表/手机 ──▶ │  DSH 肥鱼 App（本地引擎）    │
                    │  ChatEngine + 52 tools      │
                    └───────┬─────────────┬───────┘
                            │             │
              模型 API（用户自配）      社区 API（HTTPS）
              HTTPS + SSE                 │
                            │             ▼
                            │      dshwatch.vxni.ink
                            │      PHP + MySQL（虚拟主机）
                            │             ▲
                            │             │ 浏览器
                            │       网页版 index / card / admin
                            │
                    检查更新 → api.github.com → Release 资产
                                （下载走 ghfast.top 反代）
```

---

## 3. 密钥与凭证

**本仓库是 public。任何明文密钥都不许进版本库，包括写在注释、文档、测试里的。**

### 3.1 清单（只记位置和用途，值见本地 `SECRETS.md`，不进仓库）

| 材料 | 位置 | 用途 | 丢了会怎样 |
|---|---|---|---|
| 签名密钥库 | `dsh-watch.jks`（项目根，已 gitignore） | Release 签名 | **同包名无法升级**，只能改包名重发 |
| 签名口令 | `keystore.properties`（已 gitignore） | 上面那个库的口令 | 同上 |
| GitHub PAT | `.env` 的 `GITHUB_TOKEN` | `publish_update.py` 发 Release，需 Contents: RW | 发不了版，需重新生成 |
| 内置通道 Key | `local.properties` 的 `agnes.apiKey` | 构建期注入 `BuildConfig` | 自建包没有 Agnes 通道 |
| 站点数据库 | 线上 `wwwroot/config.php` | PHP 连库 | 站点 500 |
| 后台密码 | `install.php` 生成哈希写回 `config.php` | `/admin/` 登录 | 重跑 install |
| 面板账号 | 星辰云虚拟主机面板 | webftp 部署、SSL、跳转开关 | 只能找主机商 |
| 证书私钥 | `dshwatch-ugc/certs/privkey.pem` | HTTPS | 重签证书 |

### 3.2 硬规则

1. **`.gitignore` 里这几条不许删**：`*.jks`、`*.keystore`、`/keystore.properties`、`signing/`、`.env`。
   `signing/` 那条是因为里面有明文密码的 `.txt`，光忽略 `*.jks` 挡不住。
2. 提交前自查：`git status --short` 里不许出现上面任何一类文件；
   `git diff --cached | grep -iE "password|secret|token|BEGIN .*PRIVATE"` 应为空。
3. 万一推上去了：**先轮换，再改历史**。历史重写挡不住已经被 clone / 被爬的副本。
4. `dshwatch-ugc/` 没有 git，`config.php` 是唯一副本 —— 备份时一起带走。

---

## 4. 构建环境

| 项 | 版本 | 备注 |
|---|---|---|
| JDK | 17+ | AGP 8.x 要求 |
| Android SDK | Platform 35 / Build-Tools 35.0.0 | `local.properties` 里 `sdk.dir` |
| AGP / Gradle / Kotlin | 8.7.3 / 8.11.1（wrapper 自带）/ 2.0.21 | |
| minSdk / targetSdk / compileSdk | **26** / 34 / 35 | minSdk 26 是 Wear Compose 1.5.6 的下限 |
| 包名 | `com.dshwatch`，debug 变体 `com.dshwatch.debug` | 两者可共存 |

```bash
./gradlew :app:testDebugUnitTest                 # 全量单测（1697 条，约 3 分钟）
./gradlew :app:assembleRelease                   # 出包
./gradlew :app:assembleRelease -Pabi=arm64-v8a   # 单 ABI
```

国内网络：`settings.gradle.kts` 已把阿里云镜像排在 `google()` / `mavenCentral()` 之前。
**Gradle 守护进程容易被 OOM 杀掉**（本机 8 GB 内存时尤其），表现是构建无输出中断 ——
`./gradlew --stop` 后单独跑测试、再单独打包，比串起来稳。

---

## 5. 发版

### 5.1 版本号

- `1.24.2` 里 `1.24` 是大版本，`.2` 是小版本。
- **beta**：只改两处 —— `versionCode + 1`，`versionName` 的 beta 序号 + 1。大小版本都不动。
- **正式版**：只在用户明确说「发正式版」时做，`versionCode + 1` 并去掉 `-beta.N`。
- 测试版**不替稳定版占号**：稳定版停在 `1.24.0`，beta 就只能在 `1.24.x` 里加。

### 5.2 推送节奏（硬约束）

GitHub 账号曾因推送过于频繁被封过。**源码每 5 次 beta 才 push 一次**，
beta Release 每版都发（`publish_update.py` 只调 Release API，不算推送）。

- 计数：`git log -1 origin/main -- app/build.gradle.kts` 取远端 versionCode，
  与本地当前值相减，**差到 5 才推**。
- 断点：`git log --oneline origin/main..HEAD` 看攒了几条。
- **当前基线：远端 174**（2026-11-28 推送，含全部社区相关提交）。计数 0 / 5，
  下一个该推的点是 `versionCode 179`。

### 5.3 beta 发布清单

1. `app/build.gradle.kts`：`versionCode + 1`，beta 序号 + 1
2. `AppChangelog.kt` 顶部加一条（只记新增能力，修复类不记）
3. `./gradlew :app:testDebugUnitTest` —— 全绿才继续，没有例外
4. `./gradlew :app:assembleRelease`
5. 校验产物（见 §5.5）
6. `python3 tools/publish_update.py <apk> --beta --notes "..."`（可多个 `--notes`）
7. 本地 commit；若到了第 5 次 → `git push`

### 5.4 正式版发布清单

同上，但去掉 `--beta`，`versionName` 去掉 `-beta.N`，末尾必须 `git push`。

### 5.5 发完必须验证的三件事

```bash
# ① 版本号真的进包了（资源会被混淆，所以查 dex 字符串）
unzip -p app-release.apk classes.dex | strings | grep "1.24.2-beta.24"
# ② 资产名对不对（错一个字符 = 永远不提示更新）
curl -s https://api.github.com/repos/dsdzm/DSH-Watch/releases/latest | grep browser_download_url
# ③ Release 的 tag 是 v<versionName>，beta 是 prerelease=true
```

---

## 6. 注意事项（都是踩过的坑，按重要性排）

### 6.1 HTTPS 证书链

- 面板必须贴**叶 + 中间**（`chain.pem`），只贴叶证书 → 安卓报 `Trust anchor`。
- 当前链锚在 **Sectigo Public Server Authentication Root R46**，这是 2021 年才进系统信任库的
  新根。老手表 / 老手机的系统 CA 库没有它，握手直接失败。
- 因此 **App 内置了 R46**（`res/raw/sectigo_r46.crt` + `network_security_config.xml`）。
  删这个文件 = 老设备立刻进不去社区。
- 换证书时**优先选锚在老根**（USERTrust RSA / ISRG Root X1）的链；如果又锚到新根，
  得把新根再打进 App 发一版。`crt.sh` 查中间证书有哪些签发版本。
- **证书 2027-02-26 到期，到期前必须续**，步骤见 `dshwatch-ugc/README.md`。

### 6.2 「HTTP 强制跳转 HTTPS」开关暂时是关的

旧版（≤ beta.23）走 http，开跳转会让它们的 POST 被 302 成 GET，投稿静默失败。
**要等所有人升到 beta.24+ 再开。** 这是当前唯一一个有意的「待办开关」。

### 6.3 面板 webftp 的两个坑

1. iframe 原始 src `?c=index&a=webftp` **打不开**，必须改成 `?c=webftp&a=index`。
2. `openDir()` 是**路径累加**（`dirOperating += p`），连点会进 `/wwwroot/wwwroot/wwwroot/`。
   上传前显式设绝对路径：`dirOperating='/wwwroot/'; loadDir('/wwwroot/');`
   —— 上一版就因为这条把补丁传进了 `/wwwroot/wwwroot/`，线上 404 查了半天。

传完在面板文件列表里核对文件名，再访问线上确认。别信「上传进度 100%」。

### 6.4 部署服务端的正确姿势

一次性脚本 + zip + 带 key 的 URL 是最省事的路子：

```
本地打包 patch.zip（只含改动的文件，路径相对 wwwroot）
→ webftp 传到 /wwwroot/
→ 传一个 unpack3.php（带 key，解压后 @unlink(__FILE__)）
→ curl "https://dshwatch.vxni.ink/unpack3.php?key=..." 
→ 立刻 curl 验一次线上，再确认脚本已 404
```

数据库变更用同样的 `migrateN.php`。**跑完必确认自删**（再请求一次应 404）。

### 6.5 `.gitignore` 里的 `build/`

只能写 `app/build/`，**不能写裸 `build/`** —— 那会把
`domain/agent/build/`（ProjectBuilder / LanguageValidators / SourceValidator）整个包忽略掉，
推上去的仓库缺文件，云端编译必 `Unresolved reference 'build'`。这个坑踩过一次。

### 6.6 社区缓存与状态

- `CommunityCache`：列表 5 分钟、详情 30 分钟、我的投稿 60 秒。
  **过期的也先显示再静默刷新** —— 缓存的意义是不转圈，不是数据绝对新。
- 缓存在构造里**同步**读磁盘一次，改成异步会让首帧读到 null 又去发请求，缓存白做。
- 写盘先写临时文件再 rename，否则进程被杀会留半截 JSON，下次启动全崩。
- 点赞 / 评分是**一次性**的：本地先生效，再发请求对账，失败也只是提示，不回滚 UI。
  服务端靠主键挡重复，不靠客户端。

### 6.7 圆屏与安全距离

- M3E 骨架给顶部时间文字让位的高度是 `32dp × fontScale`（封顶 1.6 倍）。
  写死 32dp 时，系统字号一放大标题就会被压住 / 被切 —— 真机上才看得出来。
- 详情页内容顶部留 8dp 呼吸位；长文折叠用 `hasVisualOverflow` 判断，不按字数猜。

### 6.8 单测是门槛，不是装饰

1697 条里有一批是**源码文本断言**（`CommunityWiringTest`、`WatchNavigatorTest`），
改路由名 / 删页面 / 改组件签名都会让它们红。这不是噪音 —— 它们挡的是
「编译过但界面白屏」这类问题。测试红了就改测试，别 `@Ignore`。

---

## 7. 当前状态与待办

### 7.1 基线

- 版本：`1.24.2-beta.24`（versionCode 174），测试通道已发
- 线上：`?a=stats` → 2 卡 / 4 用户 / 0 待处理举报
- 单测：1697 通过（4 skip）
- **推送计数：0 / 5**。远端基线 `versionCode 174` / `1.24.2-beta.24`，
  本地与远端已同步（`git rev-list --left-right --count origin/main...HEAD` → `0 0`）。
  上一个五轮（170→174）已随 2026-11-28 那次推送清零。

### 7.2 待办

| 事项 | 触发条件 | 说明 |
|---|---|---|
| 开「HTTP 强制跳转 HTTPS」 | 用户确认装了 beta.24+ | 见 §6.2 |
| 证书续期 | 2027-02-26 前 | 见 §6.1 |
| 源码推送 | 攒满 5 次 beta | 见 §5.2 |

### 7.3 暂停 / 未完成

- **QQ 双向聊天**：缺 `qq-sdk.jar` 与 `arm64-v8a` 的 so，通道代码在但跑不通。
  文档 `docs/qq-bridge.md` 描述的是 OneBot 方案，与 SDK 方案是两条路，别混。
- **`bili_*` 16 个工具**：能用，但依赖 B 站 cookie，过期就得重新登。
- `CHANGELOG.md` 落后于 `AppChangelog.kt`（后者才是 App 内展示的源），
  补文档时以 `AppChangelog.kt` 为准。

---

## 8. 文档索引

| 文件 | 内容 |
|---|---|
| `docs/HANDOFF.md` | **换环境交接**：从零重建、迁移清单、故障处理 |
| `docs/PROJECT-DETAILS.md` | 架构、对话引擎、工具循环、MCP、插件、构建链 |
| `docs/CLOUD.md` | 云端编译（GitHub Actions）配置 |
| `docs/qq-bridge.md` | QQ 通道（OneBot 方案） |
| `CHANGELOG.md` | 逐版本变更（可能滞后，见 §7.3） |
| `dshwatch-ugc/README.md` | 服务端部署、备份、HTTPS 续期、接口表 |
