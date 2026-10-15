# 本仓库的 workflow

这两个文件是**本项目自己用的副本**，同时也是 App 内置模板的来源：

- `dsh-run.yml` —— 执行任意命令并上传产物（手表端 `cloud_build` 会触发它）
- `dsh-build.yml` —— 构建本项目 APK

同一份内容在两个地方：

| 位置 | 用途 |
| --- | --- |
| `.github/workflows/` | GitHub 实际执行的 workflow（本仓库） |
| `app/src/main/assets/cloud/` | 打包进 APK 的模板，设置页「安装 workflow 模板」会把它写进**用户自己的**仓库 |

改任意一处都要同步另一处 —— 它们必须是同一份，否则会出现「照着 App 装的模板和文档描述不一致」。
