# DSH-Watch v1.13 实施计划

## 范围拆解

| # | 用户需求 | 落地方式 |
|---|---|---|
| 0 | 删 Python 死代码 + 补 CHANGELOG | 删 `PythonBridge.kt` `ReflectionPythonEngine.kt`；CHANGELOG 补 1.12/1.13 |
| 1 | UI 加大、控件统一、修滑块 | 新增 `WatchButton`/`touchTarget`/`ChoiceRow`；字号改滑块且默认标准；看板娘改 SwitchRow |
| 2 | 酒馆（角色扮演） | `tavern/` 领域层 + 3 个屏 + `Conversation.tavernId/tavernTurn` |
| 2b | 长期记忆 | `MemoryCompressor`：发完一轮后异步摘要 + 清历史，记忆写 `memories/<id>.md` |
| 3 | 写作 | `writing/` 领域层（世界书/角色/插件）+ 3 个屏 |
| 4 | 引导页问场景 | 第 1 步加场景选择，自动设定 `chatLayout` + 根路由 |
| 5 | 场景化 UI | `ChatScreen` 按场景收敛顶栏/底栏/空态；`SceneDock` 场景切换 |
| 6 | 酒馆/写作隐藏思维链与工具 | `ChatScreen` 传 `showAgentUi`；两场景强制 `tools=[]`、`AgentMode.OFF` |

## 新增文件

```
domain/roleplay/CharacterCard.kt        角色卡 + 世界书 + 写作插件 + 场景枚举
data/store/RoleCardStore.kt             通用卡片库（一个卡一个 JSON）
data/store/MemoryStore.kt               长期记忆文档（md）
domain/roleplay/RolePromptBuilder.kt    开场系统提示 / 改写指令 / 记忆指令
domain/roleplay/MemoryCompressor.kt     摘要 + 记忆文档追加
ui/screens/scene/SceneDock.kt           场景切换入口
ui/screens/tavern/TavernScreens.kt      角色列表 + 角色编辑 + 酒馆对话
ui/screens/writing/WritingScreens.kt    写作项目列表 + 设定编辑 + 写作对话
ui/components/BigControls.kt            WatchButton / TouchTarget / ChoiceRow / SectionHeader
app/src/test/.../RolePromptTest.kt      提示组装与记忆压缩的单测
```

## 改动文件

- `theme/Theme.kt` `WatchDimens` 尺寸上调
- `theme/Type.kt` 只为 labelSmall 提一档，body 不动（bodyMedium 已被用于 15sp 卡片标题，整体放大需重排）
- `components/Common.kt`：WatchScaffold 顶栏高度、SegmentedRow 高度/字号、SwitchRow/SettingRow/StepperRow 触摸区
- `components/WearKit.kt`：PillItem、WearCardRow 触摸区
- `MainActivity.kt` `AppRoot.kt`：字号滑块接线、场景根路由
- `nav/WatchNavigator.kt`：新增 Screen
- `model/Provider.kt` `AppSettings`：`useScene` / `tavern*` / `writing*` 字段
- `domain/ChatEngine.kt`：`open(id, turn)` / `newConversation(settings, scene)` / 长期记忆钩子
- `model/Models.kt` `Conversation`：`tavernId` / `tavernTurn`
- `screens/chat/ChatScreen.kt`：场景化渲染、隐藏 agent UI
- `screens/onboarding/OnboardingScreen.kt`：4 步（新增场景页）
- `screens/settings/SettingsGroupScreen.kt`：字号滑块、看板娘 SwitchRow、新增角色扮演/写作分组
- `core/AppContainer.kt`：装配新 store

## 关键技术决定

1. **不改 `ChatEngine` 的 runSend 主流程**，只在 `runSend` 前后加钩子，降低回归风险。
2. 场景差异全部通过「传进来的 AppSettings 副本」实现（`agentMode=OFF` 让 `tools=[]`），
   不动 `ChatRequest` 结构。
3. 长期记忆**只改发给模型的历史，不改磁盘上的对话** —— 摘要后把 `conv.tavernTurn`
   推到当前消息下标，`buildHistory` 从该下标开始取。
4. 卡库用「独立 JSON 目录」而不是塞进 `AppSettings`：卡面内容可达几 KB，
   塞进 DataStore 会让每次改设置都整包重写。
5. 新文案先进 `AppStrings` 接口？**不** —— 新增 40+ 键会让中英两套实现都要动。
   本轮新功能的文案暂时直接内联中文字符串（与 B站 插件的既有做法一致），
   并在计划里标注后续 i18n 待办。
