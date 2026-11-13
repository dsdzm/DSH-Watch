# 声明式插件

手表上没有 Node。DSH 官方插件是 Cordis 的 npm 包，`apply(ctx)` 里能拿到 `ctx.fs`、
子进程和工具注册表 —— 这套东西在 Android 手表上跑不起来，硬塞一个 libnode 会把
APK 从 8 MB 推到 200 MB，还得逐个补 sqlite、sandbox、ripgrep 的移植。

所以这里的插件是**一份 JSON 声明**：它只能改模型看到什么，碰不到设备。

## 放在哪

```
工作空间/
└─ plugins/
   ├─ terse.json
   └─ purge-clean.json
```

设置 → 插件 → 重新扫描插件目录。扫描是覆盖式的：删掉文件再扫一次，插件就没了。
上限 12 个，单个文件 8 000 字符。

## 字段

```json
{
  "schema": 1,
  "id": "dsh-plugin-terse",
  "name": "极简回复",
  "description": "不复述、不预告、不总结",
  "version": "1.0.0",
  "prompt": {
    "identity": "你是谁",
    "append": "追加到系统提示末尾的规则",
    "when": { "model": "deepseek-*" }
  }
}
```

| 字段 | 说明 |
|---|---|
| `id` | 必填，`dsh-plugin-` 开头，3–48 字符，不能和内置插件重名 |
| `name` | 设置页显示的名字，最多 40 字 |
| `description` | 一句话说明它改了什么，最多 120 字 |
| `prompt.identity` | 前置的身份段。**不删除**内置身份，而是在它前面加一段并声明优先 |
| `prompt.append` | 尾部锚点。模型对末尾指令的服从度更高，长期规则放这里 |
| `prompt.when.model` | 只在模型名匹配时生效，支持 `*`，留空表示全部 |

变量：`{{model}}` `{{provider}}` `{{today}}` `{{weekday}}`。认不出的变量原样保留，
不会报错。

## 为什么没有审批规则

dsh-purge 那类项目会顺带放宽终端命令、文件操作的确认，减少任务中断。
这里**故意不做**，原因不是技术做不到，而是手表上这道确认门只有一道：

- 插件只能**声明它需要什么**，不能**声明什么可以放过**；
- `run_script:shell`、`adb_shell`、`cloud_build` 永远逐次确认，任何清单都改不了；
- 想少点弹窗，去「AI 能力 → 审批」里自己调，那是用户的选择，不是插件的。

dsh-purge 打补丁是因为宿主没给扩展点。我们的宿主是自己写的，把扩展点开在
正确的位置就行 —— 需要放宽的，让用户自己按，而不是让一份 JSON 替他按。

## dsh-purge 的哪些部分能对上

| dsh-purge | 这里 | 状态 |
|---|---|---|
| 系统提示词注入 / 替换 | `prompt.identity` | 支持 |
| 尾部锚点（1.1.84） | `prompt.append` | 支持 |
| 按模型切规则集 | `prompt.when.model` | 支持 |
| AGENTS.md / CLAUDE.md 管理 | 写进工作空间，用 `read_file` 读 | 不适用 |
| 审批规则调整 | —— | 有意不做 |
| 沙箱策略 | 手表无沙箱 | 不适用 |
| 二进制补丁 | 宿主是自己的，不需要 | 不适用 |

## 安装

最省事的方式是让助手自己写：

> 把 docs/plugins/terse.json 的内容写到工作空间 plugins/ 目录

或者用 adb：

```bash
adb push docs/plugins/terse.json /sdcard/Download/
# 再在 App 的文件页里移到 workspace/plugins/
```

写完后进设置 → 插件 → 重新扫描插件目录，开关出现在列表顶部。
声明式插件默认关闭 —— 它改的是模型的自我认知，这个决定应该由你按。
