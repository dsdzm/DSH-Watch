# v1.7 界面重构：Compose for Wear OS + Material 3

> 目标：把界面从「手机 M3 挤进手表」改成**表盘原生**的观感 ——
> 纯黑底、大圆角胶囊条目、线性图标、极简顶栏，对齐参考图
> （深灰胶囊菜单 + 底部页面指示点）。

---

## 1. 为什么换 UI 框架

改前的界面用的是**手机版** `androidx.compose.material3`，所有手表适配都是手搓的：
固定尺寸常量、`cornerSafe()` 自己算 R 角内收、按钮高度按手指估。这带来两个问题：

1. **组件不是为圆屏设计的**：卡片、按钮、对话框都是矩形语义，边角在圆屏上要么被裁、
   要么得靠 padding 硬躲；
2. **缺少手表专属的交互件**：分页指示、滚动缩放、边缘水波纹、旋转表冠支持都没有。

Wear Compose（`androidx.wear.compose:compose-material3`）正好补这两块：组件尺寸、
点击热区、Typography（Wear 的 body1 是 16sp 起）、圆屏内收都是按表盘调的。

> 注：这一版**没有**全量替换成 Wear 的 `Chip`/`Card`/`ScalingLazyColumn`。
> 现有页面的信息密度（Markdown、工具卡片、多行设置）用 Wear 组件反而更挤；
> 采取的是「Wear 主题 + 自绘胶囊组件」的混合方案，见 §3。

---

## 2. 依赖与版本

```toml
composeBom  = "2025.06.01"      # 2024.10.01 → 2025.06.01（Compose 1.7.4 → 1.8.x）
wearCompose = "1.5.6"           # Wear Material 3（依赖 Compose 1.8.2）
```

```kotlin
implementation(libs.androidx.wear.compose.material3)
implementation(libs.androidx.wear.compose.foundation)
implementation(libs.androidx.compose.material.icons.extended)
```

三个决定：

| 决定 | 原因 |
|---|---|
| 升 BOM 而不是压 Wear 版本 | Wear M3 稳定版只有 1.5.x 起，全部依赖 Compose 1.8.x；压不下来 |
| 引 `icons-extended` | 参考图的图标是线性风格（History / CloudDownload / Settings / Info），核心图标集里没有；R8 会裁掉未引用图标，lite 包实测只涨 170KB |
| 不替换现有页面组件 | 12 个页面的信息结构差异大，全量换 Wear 组件的回归风险高于收益；先统一视觉语言，后续按页面逐步迁移 |

编译验证：Compose 1.8 与 Kotlin 2.0.21 / AGP 8.7.3 兼容，现有代码零改动通过编译
（仅新增 `LocalClipboardManager` 等 deprecation 警告）。

---

## 3. 新组件（`ui/components/WearKit.kt`）

### 3.1 `PillItem` —— 胶囊条目

参考图的核心元素。尺寸取自截图（410×502 ≈ 2x 表盘）：

| 项 | 值 | 说明 |
|---|---|---|
| 高度 | ≥48dp | 手指热区，不追截图里的 44dp |
| 圆角 | 全圆（`CircleShape`） | 截图是高胶囊，短文字时接近圆 |
| 左右内边距 | 20dp | 图标离边远一点，和参考图一致 |
| 图标 | 18dp，`onSurfaceVariant` | 线性图标，不用主色（截图里图标是灰的） |
| 图标-文字间距 | 14dp | |
| 文字 | 14sp / Medium / `onSurface` | |
| 宽度 | **随内容**（`widthIn(min = 132.dp)`） | 长短不一的胶囊是这种菜单的呼吸感来源，不做满宽 |
| 禁用态 | `alpha 0.42` | 「继续对话」没有历史会话时 |

### 3.2 `PageDots` —— 底部页面指示点

当前页 7dp 白色实心，其余 5dp `#4A4A4A`。与参考图一致（当前点更大更亮）。

### 3.3 `PillMenuPage` —— 一页菜单容器

内容垂直居中、条目左对齐、间距 12dp。左对齐 + 宽度随内容，是参考图最显眼的结构特征。

### 3.4 `SettingPill` —— 独立胶囊设置项

设置主页原来是「一张 `SettingCard` 装 8 个分组行 + 分割线」。改成每项一个 24dp 圆角胶囊，
副标题保留（显示该项当前状态），右侧 `›`。

---

## 4. 新主页（`ui/screens/home/HomeScreen.kt`）

### 4.1 结构

```
┌────────────────────────────┐
│                            │
│   ( 新对话              )   │  ← 胶囊宽度随文字
│   ( 继续对话            )   │
│   ( 历史记录            )   │
│                            │
│           ● ○ ○            │  ← 底部指示点
└────────────────────────────┘
```

- `HorizontalPager` 三页，左右滑动；
- 每页 3 项，按使用频率分组：

| 页 | 内容 | 分组逻辑 |
|---|---|---|
| 1 | 新对话 / 继续对话 / 历史记录 | 每天都会点的 |
| 2 | 工作空间 / 供应商 / 用量 | 配置与成本 |
| 3 | 设置 / 关于 / 连接诊断 | 低频 |

- **继续对话**：`LaunchedEffect` 读 `conversationStore.index().firstOrNull()`，
  有历史时直接 `engine.open(id)` 进对话页；没有历史时置灰。

### 4.2 导航变化

| 项 | 改前 | 改后 |
|---|---|---|
| 根页面 | `Chat` | `Home` |
| 对话页 | 根，返回即退出 | 二级，返回回主页 |
| `toChat()` | `popToRoot()` | `popToRoot()` 后确保栈是 `[Home, Chat]` |
| 启动 | 直接进对话 | 进主页 |

好处：聊天时左上角返回不会误退出应用；坏处：多一步才能继续聊天 —— 用「继续对话」第一项抵消。

---

## 5. 配色与形状

深色方案改为参考图的灰阶（`ui/theme/Color.kt`）：

| 语义 | 改前 | 改后 |
|---|---|---|
| `background` / `surface` | `#000000` / `#0B0F14` | `#000000` / `#000000`（纯黑，OLED 省电） |
| `surfaceContainerHigh`（胶囊底） | `#262E3A`（偏蓝） | `#2A2A2A`（中性灰，对齐截图） |
| `onSurface` | `#E2E6EF`（冷白） | `#F2F2F2`（中性白） |
| `onSurfaceVariant` | `#B9C2CF`（偏蓝） | `#C4C4C4`（图标/次要文字） |
| `primary` | `#B0C2FF`（饱和蓝） | `#D6E3FF`（低饱和浅蓝，仅用于强调） |

形状：主按钮 / 幽灵按钮 / Chip 全部改 `CircleShape`；`WatchTopBar` 去掉卡片背景，
只剩返回箭头 + 标题（标题 `titleSmall`，`onSurface`）。

---

## 6. 页面改造清单

| 页面 | 改动 |
|---|---|
| `HomeScreen`（新增） | 三页胶囊菜单 + 指示点 |
| `ChatScreen` | 顶栏去掉设置入口（返回 + 历史 + 新建）；消息区沿用原有结构，卡片圆角随主题 |
| `SessionsScreen` | 会话条目 12dp → 26dp 圆角，左右内边距加大，条目间距 4 → 8dp |
| `SettingsScreen` | 分组入口改为 `SettingPill` 独立胶囊 |
| `AboutScreen` / `WorkspaceScreen` / `UsageScreen` / `ProvidersScreen` 等 | 随主题与 `SettingCard` 自动获得新视觉（卡片圆角不变、底色变深灰） |
| `WatchTopBar` | 去背景、去卡片、内边距收敛 |
| `WatchScaffold` | 背景色跟随纯黑主题 |

---

## 7. 验证

| 项 | 结果 |
|---|---|
| 编译 | `compileDebugKotlin` / `assembleRelease` 通过 |
| 单元测试 | 554 个，0 失败 |
| 双包 | lite 6.87MB（+170KB）/ full 28.06MB |
| 版本 | `versionCode 15 / versionName 1.7`，签名 `70437a7f…bf31f94` |
| 真机验收项 | 主页滑动与指示点、胶囊点击热区、返回路径、深色可读性 |

---

## 8. 已知限制

| 限制 | 说明 |
|---|---|
| 未使用 `ScalingLazyColumn` | 列表页仍是普通 `LazyColumn`，没有边缘缩放/透明度效果；后续可在会话列表试点 |
| 主页不显示标题 | 对齐参考图；代价是首屏没有「你在哪」的文字提示，靠指示点与内容区分 |
| 中文标题在胶囊里换行 | `maxLines = 1` + 省略号；极长标题（如会话名）会截断，靠副标题补信息 |
| 明色主题未重做 | 仍用旧浅色方案；手表场景以深色为主，暂不投入 |
