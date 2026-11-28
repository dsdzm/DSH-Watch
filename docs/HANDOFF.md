# 交接文档 · DSH 肥鱼 + 酒馆社区

> 写给**接手的人**。目标是：拿到这份文档 + 几个私密文件，就能在全新机器上
> 继续开发、发版、运维线上服务。
>
> 配套阅读：`AGENTS.md`（约定与坑）、`docs/PROJECT-DETAILS.md`（架构）、
> `dshwatch-ugc/README.md`（服务端部署细节）。
>
> 最后更新：2026-11-28，对应 `1.24.2-beta.24`（versionCode 174）。

---

## 1. 交接范围

| 资产 | 形态 | 现在的状态 |
|---|---|---|
| **DSH 肥鱼 App** | Kotlin/Compose 源码，约 11 万行 / 362 个文件 | 可构建、1697 条单测全绿 |
| **GitHub 仓库** | `dsdzm/DSH-Watch`，**public**，GPL-3.0 | 线上代码到 `versionCode 164`，本地领先 11 个提交 |
| **发版通道** | GitHub Releases（stable + beta 两条） | beta.24 已发 |
| **社区服务端** | PHP 单文件路由 + MySQL，虚拟主机 | 线上运行中，`?a=stats` 正常 |
| **证书** | ZeroSSL，`dshwatch.vxni.ink` | 有效至 **2027-02-26** |
| **域名 / 主机** | `vxni.ink` 子域 → 星辰云虚拟主机 | 解析与续费都在同一账户下 |

两套东西**互不依赖部署**：App 挂了服务端照跑，服务端挂了 App 只是社区不可用。

---

## 2. 换环境必须带走的东西

### 2.1 私密材料（**不能**放进 git，不能贴进任何聊天工具）

| # | 东西 | 现在在哪 | 校验方式 |
|---|---|---|---|
| 1 | **签名密钥库 `dsh-watch.jks`** | 项目根目录（已 gitignore） | `keytool -list -keystore dsh-watch.jks` |
| 2 | **签名口令** | 项目根 `keystore.properties` | 4 行：storeFile / storePassword / keyAlias / keyPassword |
| 3 | **GitHub PAT** | 项目根 `.env` 的 `GITHUB_TOKEN` | `curl -H "Authorization: Bearer $T" https://api.github.com/user` |
| 4 | **站点数据库凭据** | 线上 `wwwroot/config.php`（本地副本在 `dshwatch-ugc/wwwroot/config.php`） | 登录面板 phpMyAdmin |
| 5 | **主机面板账号** | 星辰云 `https://aspanel.starzv.com/vhost/` | 登录后能看到站点根 |
| 6 | **证书私钥** | `dshwatch-ugc/certs/privkey.pem` | `openssl rsa -in privkey.pem -check` |
| 7 | 内置通道 Key（可选） | 旧机 `local.properties` 的 `agnes.apiKey` | 不带走也能构建，只是没内置通道 |

**第 1、2 条最关键**：同包名升级必须用同一套签名。丢了不是「重新签名」，是
「改包名重新发一个 App，老用户全部丢更新链路」。至少备份三份，其中一份离线。

第 3 条建议**重新生成**而不是搬旧的（细粒度 token，只授权 DSH-Watch，Contents: Read and write）。

### 2.2 非私密但要对齐的

- `vxni.ink` 域名续费账户、星辰云主机到期时间
- GitHub 仓库的 Issues / Discussions 有没有待处理
- 当前版本基线：`versionCode 174` / `1.24.2-beta.24`

---

## 3. 新环境重建：App 侧

以 Ubuntu / macOS 为例。Windows 用 WSL2，别用原生 shell。

### 3.1 装工具链

```bash
# JDK 17（AGP 8.7 要求，17 或 21 都行）
# Android SDK：只需要 platform 35 + build-tools 35.0.0
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

不需要装 Android Studio，命令行够用。`local.properties` 写一行：

```properties
sdk.dir=/path/to/android-sdk
# 有内置通道 key 就加一行，没有可留空
agnes.apiKey=...
```

### 3.2 取代码 + 复原私密材料

```bash
git clone https://github.com/dsdzm/DSH-Watch.git
cd DSH-Watch

# 从私密渠道放回这三个（路径要和原来一致，脚本里有硬编码）
#   dsh-watch.jks
#   keystore.properties
#   .env
chmod 600 .env keystore.properties dsh-watch.jks

# 服务端源码另存一份（不在 git 里！）
#   dshwatch-ugc/  整个目录拷过来
```

> `keystore.properties` 里的 `storeFile` 是**绝对路径**。换机器要改成新路径，
> 否则 `assembleRelease` 会报找不到 keystore。这是换环境第一个会撞的坑。

### 3.3 验证环境（三条命令，全绿才算通）

```bash
./gradlew :app:testDebugUnitTest     # 期望 1697 tests, 0 failed, 4 skipped
./gradlew :app:assembleRelease       # 期望产出 app/build/outputs/apk/release/app-release.apk
python3 tools/publish_update.py --dry-run   # 期望打印"将要发布 v1.24.2-beta.24"，不实际发
```

`--dry-run` 能打印就说明 GITHUB_TOKEN 和仓库名都对。**别在这一步真发版**。

### 3.4 加本地 git 凭据

```bash
git config user.name  "dsdzm"
git config user.email "256729462+dsdzm@users.noreply.github.com"
# HTTPS 推送需要 PAT；凭证可以交给 credential helper 存
```

---

## 4. 新环境重建：服务端侧

服务端**没有版本控制**，全靠面板。所以第一件事是确认本地 `dshwatch-ugc/` 是最新的
（和线上 `api/index.php` 对一遍行数与关键函数）。

### 4.1 面板入口

```
https://aspanel.starzv.com/vhost/     账号见 §2.1
文件管理 → 目标目录必须是 /wwwroot/   （绝对路径，别用相对路径层层点进去）
数据库 → phpMyAdmin（同账号密码）
SSL → 贴 chain.pem + privkey.pem，保存后等 1~2 分钟生效
```

### 4.2 改代码 → 上线

```bash
# 1) 本地打包（只含改动文件，路径相对 wwwroot）
cd dshwatch-ugc/wwwroot
zip -q /tmp/patch.zip api/index.php card.php assets/site.css

# 2) 写一个一次性解压脚本（带随机 key，跑完自删）
#    unpackN.php 内容见 dshwatch-ugc/wwwroot/unpack2.php 的模板

# 3) 面板 webftp 上传 patch.zip 和 unpackN.php 到 /wwwroot/
#    ⚠ iframe src 若打不开，改成 ?c=webftp&a=index

# 4) 触发 + 验证
curl "https://dshwatch.vxni.ink/unpackN.php?key=<随机串>"
curl -s "https://dshwatch.vxni.ink/api/?a=stats"        # 站点活着
curl -s -o /dev/null -w "%{http_code}" ".../unpackN.php?key=..."   # 期望 404（已自删）
```

**数据库变更**用同样的路子（`migrateN.php`），必须满足：

- 脚本幂等（`CREATE TABLE IF NOT EXISTS`、`ALTER` 前先查 `information_schema`）
- 带 key，且 key 不是弱口令
- 末尾 `@unlink(__FILE__)`
- 跑完再请求一次确认 404

### 4.3 备份（接手第一周就该做一次）

```bash
# 数据库：面板 phpMyAdmin 导出 SQL
# 全站：面板打包下载 /wwwroot/，或 webftp 逐目录拉
# 建议节奏：每次改表前 + 每月一次
```

---

## 5. 验收：完整发一个 beta

能独立走完这一遍，才算真的接手了。

```bash
# ① 改版本号：app/build.gradle.kts → versionCode 175, versionName 1.24.2-beta.25
# ② AppChangelog.kt 顶部加一条（只写新增能力）
# ③ 测试
./gradlew :app:testDebugUnitTest
# ④ 打包
./gradlew :app:assembleRelease
# ⑤ 校验版本号进包（资源会混淆，查 dex）
unzip -p app/build/outputs/apk/release/app-release.apk classes.dex | strings | grep "1.24.2-beta.25"
# ⑥ 发 beta
python3 tools/publish_update.py <apk路径> --beta --notes "本条改动一句话"
# ⑦ 确认线上
curl -s https://api.github.com/repos/dsdzm/DSH-Watch/releases | \
  python3 -c "import json,sys; r=json.load(sys.stdin)[0]; print(r['tag_name'], r['prerelease'], [a['name'] for a in r['assets']])"
```

第 ⑦ 步要看到资产名是 `dshwatch-175.apk`。**名字错 = 所有用户永远收不到更新，且不报错。**

> 注意发版节奏：**beta Release 每版都发，源码每 5 次 beta 才 push 一次**（账号被封过）。
> 当前计数 4/5，下一个 beta 正好该 push。详见 `AGENTS.md` §5.2。

---

## 6. 故障速查

| 现象 | 最可能的原因 | 处理 |
|---|---|---|
| App 报 `Trust anchor` / 证书不受信 | 设备系统 CA 库没有 Sectigo R46 | 确认 APK 里 `res/raw/sectigo_r46.crt` 在、`network_security_config.xml` 引用了它 |
| 服务器 `openssl verify` 失败 | 面板只贴了叶证书 | 贴 `chain.pem`（叶+中间） |
| 站点 500 | `config.php` 被覆盖 / DB 密码错 | 面板看 PHP 错误日志，核对 DB 凭据 |
| 上传了但线上没变 | 传进了 `/wwwroot/wwwroot/` | 用绝对路径 `/wwwroot/` 重传 |
| 社区一直转圈 | 缓存被打断 / 接口超时 | 先看 `?a=ping`；缓存 TTL 见 `CommunityCache` |
| 「已是最新」但其实有新包 | 资产名不是 `dshwatch-<code>.apk`，或 tag 不匹配 | 改名重建 Release |
| 检查更新报「GitHub 限流」 | 匿名 API 60 次/小时/IP | 等一小时，或给 token（当前实现不带） |
| 旧版 App 注册失败 | 服务端已要求 email，旧版不传 | 让用户升级；或在服务端放宽 email 必填 |
| `assembleRelease` 找不到 keystore | `keystore.properties` 里是旧机器的绝对路径 | 改成新路径 |
| Gradle 构建中途无输出中断 | 守护进程被 OOM 杀 | `./gradlew --stop`，测试和打包分开跑 |
| 云端编译 `Unresolved reference 'build'` | `.gitignore` 写了裸 `build/` | 改成 `/build` 和 `app/build/` |
| 面板文件管理空白 | iframe src 不对 | 改成 `?c=webftp&a=index` |

---

## 7. 已知风险与待办

| 优先级 | 事项 | 说明 |
|---|---|---|
| 高 | **证书 2027-02-26 到期** | 提前一个月续。续完确认新链锚在哪条根，锚到新根就得再发一版 App 内置 |
| 高 | **HTTP 强制跳转 HTTPS 还没开** | 等所有活跃用户升到 beta.24+ 再开，否则旧版投稿会 POST→GET 静默失败 |
| 中 | 服务端无版本控制 | 建议接手后给 `dshwatch-ugc/` 建**私有**仓库（`config.php` 先移到仓库外） |
| 中 | 无自动备份 | 建议加个每月导出 SQL 的提醒 |
| 低 | QQ 通道未完成 | 缺 `qq-sdk.jar` 与 `arm64-v8a` so；OneBot 方案可用但未接完 |
| 低 | `CHANGELOG.md` 滞后 | App 内展示以 `AppChangelog.kt` 为准 |

---

## 8. 交接确认清单

接手方逐条打勾：

- [ ] `dsh-watch.jks` + `keystore.properties` 已拿到，`keytool -list` 能打开
- [ ] `.env` 里 token 能通过 `curl https://api.github.com/user`
- [ ] `./gradlew :app:testDebugUnitTest` 全绿
- [ ] `./gradlew :app:assembleRelease` 出包，且签名指纹与线上一致
- [ ] `publish_update.py --dry-run` 能打印版本信息
- [ ] 能登录星辰云面板，能看到 `/wwwroot/` 文件列表
- [ ] 能从 `config.php` 连上数据库（phpMyAdmin 能列表）
- [ ] `dshwatch-ugc/` 完整拷走（含 `certs/`）
- [ ] 知道当前版本基线 `174 / 1.24.2-beta.24`，知道推送计数 4/5
- [ ] 读完 `AGENTS.md` §6（注意事项）

---

## 9. 联系人 / 账号归属

都在原负责人手上：GitHub `dsdzm`、星辰云面板、`vxni.ink` 域名。
交接时应完成**账号层面**的转移（或至少共享），不只交接文件 ——
否则证书续不了、主机续不了费，前面全白做。
