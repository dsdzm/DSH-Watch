# v1.7 界面重构：Compose for Wear OS + 两页横向结构

> 目标：对齐两张参考图 ——
> **图 1**：对话页（角色名在气泡上方，用户蓝灰气泡、助手深灰气泡）；
> **图 2**：设置页（居中标题 + 独立大卡片 + 底部两点指示）。
> 并且**打开就是对话**，左滑进设置、右滑回对话。

---

## 1. 结构：一屏两页，对话为主

```
┌─────────────────────────┐   ┌─────────────────────────┐
│                         │   │         设置            │
│  我                     │   │  ┌───────────────────┐  │
│  ┌───────────────────┐  │   │  │ ☁ 模型服务   ›    │  │
│  │ 调研三家竞品的…    │  │   │  │   当前：DeepSeek  │  │
│  └───────────────────┘  │   │  └───────────────────┘  │
│  DSH                    │   │  ┌───────────────────┐  │
│  ┌───────────────────┐  │   │  │ ✦ AI 能力    ›    │  │
│  │ 好的，我来处理…    │  │ ← │  │   5 个工具已启用   │  │
│  └───────────────────┘  │   │  └───────────────────┘  │
│                         │   │           ● ○           │
└─────────────────────────┘   └─────────────────────────┘
        对话（根）                      设置（横滑）
```

实现（`ui/MainPager.kt`）：

```kotlin
val pager = rememberPagerState(initialPage = if (nav.current == Settings) 1 else 0) { 2 }
HorizontalPager(state = pager) { page ->
    when (page) { 0 -> ChatScreen(...); 1 -> SettingsScreen(...) }
}
LaunchedEffect(pager.currentPage) {
    val target = if (pager.currentPage == 0) Screen.Chat else Screen.Settings
    if (nav.current != target) nav.replaceTop(target)
}
```

三个设计决定：

| 决定 | 原因 |
|---|---|
| 不设独立菜单首页 | 手表上多一层菜单 = 每次聊天多一次点击；设置是低频操作，横滑一步就到 |
| 两页共用同一个 Pager 容器 | 滑动时页面状态不重建；从二级页返回也停在原来那一页 |
| 滑动后 `replaceTop` 同步导航栈 | 在设置页点进二级页再返回，能回到设置页而不是对话页 |
| 指示点只在设置页出现 | 对话页底部是输入栏，叠一层点会打架；参考图里也只在列表页有 |

---

## 2. 对话气泡（`BubbleScaffold`）

参考图的关键不是「左右对齐」，而是**角色标签独立在气泡上方**：

```kotlin
Column {
    Row(padding(start = 4.dp, bottom = 5.dp)) {   // 标签行
        if (!isUser && showMascot) Image(mascot, 16.dp)
        Text(if (isUser) "我" else "DSH", 11sp, outline)
        // 步数 / 生成中 / 失败 / 已停止 等状态也在这行
    }
    Column(                                      // 气泡
        Modifier
            .fillMaxWidth(if (isUser) 0.94f else 1f)
            .clip(RoundedCornerShape(26.dp))
            .background(if (isUser) Color(0xFF4A5670) else surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { 工作块 / 正文 / 操作行 }
}
```

| 项 | 值 |
|---|---|
| 用户气泡 | `#4A5670`（低饱和蓝灰，对应参考图的蓝紫灰） |
| 助手气泡 | `#2A2A2A`（中性深灰） |
| 圆角 | 26dp（接近全圆，但保留一点矩形感） |
| 内边距 | 水平 14dp / 垂直 12dp |
| 用户气泡宽度 | 94%（右侧留一点呼吸） |
| 助手气泡宽度 | 100%（Markdown 表格/代码需要宽度） |
| 消息间距 | LazyColumn 10dp |
| 角色文字 | 11sp / `outline`（#7A7A7A） |

气泡内用 `CompositionLocalProvider(LocalContentColor)` 把默认文字色统一成气泡前景色，
这样 Markdown 渲染、操作按钮的文字颜色不需要逐个改。

---

## 3. 设置页（`WearCardRow`）

```
        设置                  ← 18sp SemiBold，居中
┌───────────────────────────┐
│ ☁   模型服务          ›   │  ← 24dp 圆角卡片
│     当前：DeepSeek        │
└───────────────────────────┘
```

- 卡片：`fillMaxWidth` + 24dp 圆角 + `surfaceContainerHigh` + 16/12dp 内边距；
- 左侧 20dp 线性图标（`outline` 色）；
- 中间标题 15sp Medium + 副标题 11sp（当前状态摘要，最多两行）；
- 右侧 `›`（`labelMedium`，`onSurfaceVariant`）；
- 列表间距 10dp，`contentPadding` 左右 14dp、底部 20dp（给指示点留位）；
- 九个分组各配一个线性图标：云（模型）、星芒（能力）、拼图（插件）、Hub（MCP）、
  对话（聊天）、调色板（外观）、文件夹（文件）、内存（运行）、WiFi（连接）。

---

## 4. 配色与形状

| 语义 | 值 | 用途 |
|---|---|---|
| `background` / `surface` | `#000000` | 纯黑底（OLED 省电） |
| `surfaceContainerHigh` | `#2A2A2A` | 气泡、卡片 |
| `surfaceContainerHighest` | `#343434` | 工作流程块、次级按钮 |
| `onSurface` | `#F2F2F2` | 正文 |
| `onSurfaceVariant` | `#C4C4C4` | 副标题、图标 |
| `outline` | `#7A7A7A` | 角色名、时间 |
| `primary` | `#D6E3FF` | 强调（低饱和浅蓝） |

形状：气泡 26dp、卡片 24dp、按钮/Chip 全圆角；顶栏去掉卡片背景，只留返回箭头 + 标题。

---

## 5. 依赖

```toml
composeBom  = "2025.06.01"
wearCompose = "1.5.6"
```

```kotlin
implementation(libs.androidx.wear.compose.material3)
implementation(libs.androidx.wear.compose.foundation)
implementation(libs.androidx.compose.material.icons.extended)
```

Wear M3 稳定版只有 1.5.x 起，全部依赖 Compose 1.8.x，因此 BOM 从 2024.10.01 升到
2025.06.01。Kotlin 2.0.21 / AGP 8.7.3 兼容，业务代码无需改动。

---

## 6. 验证

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` / `assembleRelease` 通过 |
| 单元测试 | 554 个，0 失败 |
| 双包 | lite 6.87MB / full 28.06MB |
| 版本 | `versionCode 15 / versionName 1.7`，签名 `70437a7f…bf31f94` |
| 真机验收项 | 横滑切换与指示点、气泡对比度、设置卡片点击热区、长标题截断 |

---

## 7. 已知限制

| 限制 | 说明 |
|---|---|
| 未使用 `ScalingLazyColumn` | 列表仍是普通 `LazyColumn`，没有 Wear 的边缘缩放效果；后续可在设置页试点 |
| 横滑与系统返回手势 | 部分表盘从边缘右滑会触发系统返回，可能与 Pager 冲突；真机如冲突可改为「仅从内容区滑动」 |
| 明色主题未重做 | 仍用旧浅色方案；手表以深色为主，暂不投入 |
| 设置页无返回按钮 | 它是与对话并列的一页，靠右滑返回；二级页仍有返回箭头 |
