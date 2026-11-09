# DSH-Watch 1.10 · 刷 B站：让它自己按时干活，但知道分寸

> 版本 1.10 / versionCode 18 · 2026-10-31

这一版把「刷 B站」从**你让它刷**变成**它自己按时刷、刷完按你定的方式处理**。
功能听起来像是「加几个开关」，但真正花时间的是三件事：登录态下才存在的接口怎么验证、
自动发送的护栏放在哪、以及手表被 Doze 掐住时定时任务怎么活下来。

---

## 1. 刷完做什么：三选一

`AppSettings.biliMode` ∈ `summary` / `comment` / `dm`，默认 **summary**。

| 模式 | 允许 | 拒绝 |
|---|---|---|
| 只总结 | `bili_report` | `bili_reply`、`bili_dm` 直接返回失败并说明去哪改 |
| 发评论 | `bili_report`、`bili_reply`（含楼中楼） | `bili_dm` |
| 发私信 | `bili_report`、`bili_dm` | `bili_reply` |

**为什么不是三个独立开关**：这三件事在同一轮里互斥。三个开关能组合出
「又评论又私信」——用户从没想要、但模型一定会去做的状态。

**迁移**：1.9 及更早只有 `biliCommentEnabled` 一个布尔。读取时若 `biliMode` 为空，
按布尔推断（true → comment，false → summary）。用空串而不是默认 `summary`，
是为了区分「用户主动选了只总结」和「还没选过」。有单测钉住。

---

## 2. 评论里 @ 用户

设置项 `biliAtUid`（字符串，允许空）。发**顶层评论**时：

1. `x/web-interface/card?mid=<uid>` 查昵称（匿名可用，实测 `mid=2` → `code 0`）
2. 正文末尾追加 `@昵称`
3. 表单带 `at_name_to_mid={"昵称": uid}`

两个必须同时给：只写正文里的 @ 不会通知，只给映射正文里看不见。
查不到昵称就不 @，并在工具结果里说明 —— 发 `@12345678` 既不变色也不通知。

楼中楼回复**不**额外 @：`root` + `parent` 本身就会通知到对方，再 @ 一次等于喊两遍。

---

## 3. 每日定时

- 默认 `biliAutoRun = false`，时间 `03:30`（谷时）
- `AlarmManager.setExactAndAllowWhileIdle`；无精确闹钟权限时退化 `setAndAllowWhileIdle`
- 触发时**先排下一天**再启动前台服务与模型。顺序很重要：这一轮没网 / 没登录 /
  模型欠费都会抛异常，先排的话明天的闹钟还在，否则「设了定时但从没跑过」
- 开机与 App 升级后重排（`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`）
- 应用级 collector 只监听「开关 + 时间」三元组，改关键词不会重排闹钟

为什么不用 WorkManager：手表上 Doze 会把「03:30」推成「大概凌晨某个时候」，
而这个任务要跑十几分钟、要联网、要前台服务，WorkManager 的约束与重试反而更难控制。

---

## 4. 评论自动带图

| 选项 | 实现 |
|---|---|
| `none` | 纯文字 |
| `card` | `BiliImages.card()`：Canvas 画 720×720 黑底卡片，文案可自定义或按日期轮换 |
| `local` | `GetContent` 选图 → 缩放到最长边 1440 → `filesDir/bili/attached.jpg` |

上传：`POST x/dynamic/feed/draw/upload_bfs`，multipart，字段 `csrf` / `category=daily` /
`biz=new_dyn` / `file_up`。返回 `image_url` `image_width` `image_height` `img_size`，
直接拼进评论的 `pictures` 字段（JSON 数组字符串）。

**配图失败不阻断评论**：图挂了就发纯文字，失败原因回灌给模型，避免它反复重试。

**为什么自己画而不是内置图**：内置图有版权问题且撑 APK；评论配图本来就只需要
「一张写着话的卡片」，还能跟着用户的文案变。

---

## 5. 调皮模式

`TonePreset.NAUGHTY`，prompt 大意：彻底躺平、开始倒反天罡、视频说东你说西、
动不动宣布罢工、要求加餐。

底线是硬的，写在 prompt 里也写在禁词表里：

- 不脏、不骂具体的人（禁词含「傻逼 / 妈的 / 卧槽 / 草泥马 / 去死」）
- 不碰政治与地域、不造谣、不引战
- 「胡说」只允许发生在态度与立场上，不许编事实

理由不只是内容安全：B站 对骂人评论直接折叠，账号跟着降权。

---

## 6. 发评论不再确认

`ApprovalPolicy`：

```kotlin
val AUTO_ALLOW = setOf("bili_reply", "bili_dm")   // 永不确认
val ALWAYS_CONFIRM = setOf("cloud_build", "cloud_job")  // 仍然每次确认
```

护栏换成四层，而且比弹窗有效：

| 护栏 | 作用 |
|---|---|
| 模式开关 | 只有选了评论 / 私信才能发 |
| 每日发言上限（默认 3，评论 + 私信合计） | 超了直接拒绝，防刷屏封号 |
| 判重 `bvid|root|parent` | 同位置不重复打扰 |
| 风格闸门 | 套话、脏字、链接一律拦 |

`cloud_build` / `cloud_job` 没有一起放开 —— 那条是「代码发到外部仓库 + 几分钟构建」，
和「发一句评论」不是一个量级的不可逆。

---

## 7. 新增 `bili_dm`

| 参数 | 说明 |
|---|---|
| `message` | 10–150 字 |
| `bvid` | 可选，发给该视频 UP 主 |
| `to_uid` | 可选，优先级高于 `bvid` |

- 只有 `dm` 模式可用
- `msg[sender_uid]` 从 Cookie 的 `DedeUserID` 取，取不到直接报错（不猜）
- `msg[dev_id]` 复用 `buvid3`：同一设备反复换 dev_id 更像脚本
- **同一天同一个人只发一条**，记录在 `bili/sent/<日期>.md`

---

## 8. 验证与限制

### 已验证（匿名可测）

- `x/web-interface/card?mid=2` → `code 0`，昵称字段可读
- `upload_bfs` 与 `web_im/send_msg` 接口存在、参数被接受，匿名返回 `-101 账号未登录`
- 评论参数 `pictures` / `at_name_to_mid` / `plat` / `gaia_source` 与社区实现一致

### 需要在真机登录后验证

- 带图评论是否正常展示（图床返回 0 但评论被折叠 → 大概率是 `gaia_source` 变了）
- @ 是否真的通知到人（`at_name_to_mid` 的 key 必须与昵称完全一致，含特殊字符）
- 私信是否被要求 WBI 签名（若回 `-111`，说明该账号被风控要求签名）

### 已知限制

- 每日发言上限是**本地计数**，清 App 数据会重置
- 部分手表没有相册选择器，`GetContent` 返回 null → 自动退回摸鱼卡
- 定时任务在极端省电模式下可能晚几分钟（`setAndAllowWhileIdle` 的语义）
- 私信接口未做 WBI 签名，见上

---

## 9. 真机验收清单

- [ ] 设置 → 刷B站 → 刷完做什么，切到「只总结」，让模型发评论 → 应被拒绝并提示去改设置
- [ ] 切到「发评论」，填自己的 UID，让模型评论一条 → 评论区应能看到 `@你自己`
- [ ] 配图选「摸鱼卡」→ 评论应带一张黑底卡片；换成相册图 → 应带那张图
- [ ] 打开「每日自动刷一轮」，把时间改到 5 分钟后 → 到点应自动跑，通知栏出现进度
- [ ] 关屏放置 10 分钟 → 任务应仍在跑，跑完收到通知
- [ ] 重启手表 → 定时任务应仍在（`BOOT_COMPLETED` 重排）
- [ ] 连续让模型发 4 条评论 → 第 4 条应被「今日上限」拒绝
- [ ] 风格切「调皮模式」→ 评论应是抽象/罢工向，且不含脏字
