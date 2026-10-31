# v1.8 刷 B站：把 dsh-bili-scroll 的流水线搬进手表

> 参考材料：开源插件 `dsh-bili-scroll`，以及基于它写的 Python 最小实现 `bili-digest`
> （WBI 签名调 B站官方接口 + 读字幕判断 + 写日报 + 可选评论）。
>
> 结论：**能做，但不是照搬**。参考实现是「DSH 插件 + Python MCP server + headless 会话」，
> 手表上没有常驻 Python，也不该要求用户在旁边开一台电脑 —— 所以接口层与落盘层全部用
> Kotlin 重写，模型侧只保留工具协议。

---

## 1. 为什么不用 MCP 通道

App 里本来就有 `McpClient`，理论上把 `bili-digest` 当 MCP server 连上就行。没这么做的原因：

| 方案 | 手表独立可用 | 额外依赖 | 每次确认 | 结论 |
|---|---|---|---|---|
| 连 Python MCP server | ✗ 必须有 PC/云 | Python 3.11 + requests | MCP 工具照常走审批 | 体验断在「还得开电脑」 |
| Chaquopy 在 App 内跑 Python | ✓ | +约 20MB、启动慢 | 每次都要开本地执行 | 为一个 HTTP 客户端背整个解释器 |
| **原生 Kotlin 工具** | ✓ | 0（复用 OkHttp） | 只有发评论确认 | 采用 |

原生方案的真正成本是 **WBI 签名**，但那是一段固定算法（见 §3），一次写对就再也不用碰。

---

## 2. 一轮「刷 B站」长什么样

```
用户：刷一轮B站
  │
  ├─ bili_status        → 今日 0/5、关键词、登录态、本轮流程
  ├─ bili_search        → 候选列表（BV号 / 标题 / UP / 播放 / 时长）
  ├─ bili_subtitle      → 字幕原文（判断依据）
  ├─ （模型自己判断：值得看 / 可以看 / 不值当）
  ├─ bili_report        → 写进 bili/2026-10-31.md，扣配额、判重
  └─ 配额满 → 停，把今天挑的列给用户
```

和参考实现的三处刻意差异：

1. **没有 `bili_judge`**。参考实现会再调一次 DeepSeek 打分；这里模型就在场，让它读字幕下判断，
   省一次 API 往返，也不会出现「两个模型互相看不懂对方结论」。
2. **判重不另存表**。`bili/2026-10-31.md` 里出现 `### BVxxx` 就是「今天记过」，
   文件本身就是判重表与配额计数器 —— 少一份状态，就少一处不一致。
3. **评论默认干跑**。见 §5。

---

## 3. WBI 签名（唯一必须写对的地方）

B站 2023 年后给搜索/播放器接口加了 `w_rid` + `wts`：

```kotlin
// 1) nav 接口拿 img_key / sub_key（文件名去扩展名）
// 2) 按固定 64 位置换表重排，取前 32 位 = mixinKey
private val MIXIN_KEY_ENC_TAB = intArrayOf(46, 47, 18, 2, 53, 8, ...)
val mixinKey = (imgKey + subKey).let { raw -> (0 until 32).map { raw[MIXIN_KEY_ENC_TAB[it]] }.joinToString("") }

// 3) 参数加 wts、按 key 排序、剔除 !'()*、拼 query
// 4) w_rid = md5(query + mixinKey)
```

两个踩过的坑：

- **签名必须覆盖「真正发出去的 query」**。手工编码与 OkHttp 的规范化只要差一个 `%20` / `+`，
  `w_rid` 就对不上，表现为「所有接口 -403」。所以签名取的是 `HttpUrl.encodedQuery`，
  而不是自己再拼一遍。
- **mixinKey 缓存 6 小时**。每次请求都去 `nav` 拿一次密钥，等于把请求数翻倍，也更容易被风控。

### 设备指纹

匿名请求必须带 `buvid3`（来自 `x/frontend/finger/spi`，不需要登录），否则 `view` / `search`
大概率是 **`code 0` + 空结果** —— 这是本项目调试时最费时间的一个坑，因为它看起来像「解析写错了」。
现在固定补齐 `buvid3` / `buvid4` / `b_nut` + `Origin` + `Accept-Language`，
并把 UA 从「手表 UA」换成桌面 Chrome：实测同一台机器、同一个 buvid，移动 UA 更容易被软限流。

---

## 4. 数据落在哪

```
workspace/
└── bili/
    ├── 2026-10-31.md     # 日报：### BV号 + 标题 / UP / 判断 / 理由 / 链接 / 时间
    └── replied.md        # 已评论过的 BV 号（一行一个，防重复打扰）
```

- 放在**工作空间根**而不是会话目录：配额与判重是「按天」的，跨会话必须共享
- 写日报会填 `ToolResult.artifact`，对话页直接给一张可点开/导出的产物卡片
- 配额口径在设置页与工具里是同一个函数：数 `### BV` 开头的行

---

## 5. 发评论：三道闸

```
设置「允许发评论」关着 ──→ 只返回预览，连请求都不发
        │开
        ▼
风格闸门（StyleGate）──→ 黑名单 / 长度 5–200 / 不许带链接 / 不许纯表情
        │过
        ▼
ApprovalPolicy.ALWAYS_CONFIRM ──→ 每次弹确认（和 cloud_build 同级）
        │确认
        ▼
POST /x/v2/reply/add（需要 SESSDATA + bili_jct）
```

- **风格闸门**不是审美洁癖：模型生成的套话评论（「家人们」「yyds」「干货满满」）在 B站
  极易被折叠，也最容易招来用户反感。黑名单只拦一眼可辨的模板，不做「像不像人」的复杂打分 ——
  那种闸门拦不住真的，只会拦住正常人。
- **CSRF**：`bili_jct` 就是表单里的 `csrf`。Cookie 里没有它时直接报错，不做「匿名试一下」这种事。
- Cookie 存 `SecureKeyStore`（Android Keystore 加密），界面只回显 `SESSDATA ✓ · bili_jct ✓ · N 字符`。

---

## 6. 工具面按设置现算

`AgentTool` 新增：

```kotlin
val enabled: (AppSettings) -> Boolean get() = { true }
```

`ToolRegistry.tools` 从「构造时快照」改成「每次读都按设置过滤」：

```kotlin
val tools: List<AgentTool> get() = allTools.filter { it.enabled(context.settings()) }
```

这样用户在设置里关掉「B站工具」，**下一次请求**就少七个 schema（约 800 token），
不需要等 MCP 重连、也不需要重启 App。顺手还带来一个安全性质：
关掉的工具即使模型幻觉出名字，`registry.get()` 也拿不到，执行不了。

---

## 7. 实测结论（2026-10-31）

| 能力 | 匿名 | 填 Cookie |
|---|---|---|
| 搜索 | ✓（需设备指纹） | ✓ |
| 视频元信息 | ✓ | ✓ |
| 热门评论 | ✓ | ✓ |
| 字幕原文 | ✗ 列表为空 | 通常 ✓ |
| 发评论 | ✗ | ✓ |

> **匿名拿不到 AI 字幕**是当前 B站的策略，不是实现问题。没有字幕时 `bili_subtitle`
> 会明确返回「没有可读字幕」，并提示模型改用「简介 + 评论区」判断，而不是拿标题硬猜。
> 想让判断准，建议填 Cookie（SESSDATA 即可，不需要 bili_jct；后者只有发评论才要）。

---

## 8. 风险与边界

- 高频搜索/评论有风控与封号风险 —— 每日配额、判重、干跑默认值都是为此设的
- 只读能力不碰账号数据；Cookie 只在本机加密存储，不进日志、不进导出、不给脚本
- 接口是网页端非公开 API，B站改版可能让某个工具失效；失效时工具会返回具体错误码与解释
  （`-352` 风控 / `-403` 签名 / `-412` 限流 / `12015` 需绑定手机号），而不是一句「失败了」
