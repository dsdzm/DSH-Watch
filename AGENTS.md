# 协作约定

## 发版节奏（硬约束）

GitHub 账号曾因推送过于频繁被封。以下规则优先于任何「顺手提交」的冲动。

### 版本号怎么写

- `1.24.1` 里，`1.24` 是**大版本**，`1.24.1` 是**小版本**。
- **beta 迭代**：只动两处 —— `versionCode + 1`，`versionName` 里 beta 后面的数字 + 1。
  - 例：`121 / 1.24.1-beta.24` → `122 / 1.24.1-beta.25`
  - 大版本、小版本都不动。
- **正式版**：只在用户明确说「推送正式版」时才做。
  - 在最新 beta 的基础上 `versionCode + 1`，去掉 `-beta.N` 后缀。
  - 例：`126 / 1.24.1-beta.29` → `127 / 1.24.1`

### 推送节奏

- **源码每 5 次 beta 迭代才 push 一次**。
- 这 5 次之内：本地 commit 可以有，`git push` 一次都不许发。
- 计数方法：`git log -1 origin/main -- app/build.gradle.kts` 看上次推送时的 versionCode，
  与本地当前 versionCode 相差 5（即 126、131、136…）时才推送。
- **beta Release 每版都发**（测试通道要跟得上）。`tools/publish_update.py` 只调 Release API，
  不执行 `git push`，所以发 release 不占推送次数。

### beta 发布清单

1. `app/build.gradle.kts`：`versionCode + 1`，`versionName` 的 beta 序号 + 1
2. `AppChangelog.entries` 顶部加一条（只记新增能力，修复类不记）
3. `./gradlew :app:assembleRelease`
4. `python3 tools/publish_update.py --beta`
5. 若本次是第 5 次迭代 → 本地补 commit，然后一次性 `git push`

### 正式版发布清单

1. `versionCode + 1`，`versionName` 去掉 `-beta.N`
2. `AppChangelog.entries` 顶部加一条
3. `./gradlew :app:assembleRelease`
4. `python3 tools/publish_update.py`（不带 `--beta`）
5. commit + `git push`

## 当前计数

- 上次推送基线：`versionCode 164` / `1.24.2-beta.14`（已推源码，计数清零）
- 已攒 beta 次数：**1 / 5**（164 之后：内置文件浏览器成为全 App 总开关，
  面板接入玻璃材质与缩略图）
- 当前本地：`versionCode 165` / `1.24.2-beta.15`
- 下一个 beta：`versionCode 166` / `1.24.2-beta.16`
- 下次源码推送：`versionCode 169` 时
