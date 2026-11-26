# QQ 手表内核组件（不随仓库分发）

这个目录**故意是空的**。它要放的是腾讯手表 QQ 的私有运行时，不是我们的代码。

## 需要放什么

```
app/libs/qqlite/
└── qq-sdk.jar              # 手表 QQ 9.0.7.2563 的完整提取（约 3.5 万个类）

app/src/main/jniLibs/armeabi-v7a/
├── libmsf.so               # MSF 网络层
├── libkernel.so            # NT 内核
└── ...                     # 其余 .so，一个都不能少
```

来源：QMME / QMCE 项目的完整工程包（这两个项目的 GitHub 仓库只放代码，
组件要在它们的 QQ 群里拿）。**不要**把组件提交进本仓库：

- 它们属于腾讯，QMME 自己的 README 写着"不可再分发，仅用于个人学习"；
- 进 git 历史就删不干净，仓库又是公开的。

`.gitignore` 已经挡住了 `*.jar` / `*.so`，别去改它。

## 放好之后

```bash
./gradlew :app:assembleDebug -Pabi=armeabi-v7a
```

构建脚本会自动：
1. 把 `libs/qqlite/*.jar` 加进 `implementation`；
2. 置 `BuildConfig.QQ_KERNEL = true`；
3. 把 ABI 钉死在 `armeabi-v7a`（内核只有 32 位，64 位包里它加载不了）。

没有这些文件时，上面三件事都不做，App 照常编译、照常运行，
只是「QQ 内核」这一项显示为未安装。

## 还缺什么

只有 jar 和 so 还不够。真正跑起来还差三块，见 `docs/qq-kernel.md`：

1. **签名 patch**：jar 里 `oicq/wlogin_sdk/tools/util` 的 `get_apk_id` /
   `get_apk_v` / `getPkgSigFromApkName`，以及
   `com/tencent/mobileqq/msf/core/auth/c` 的两个 `a(PackageManager, ...)`。
   不 patch，WtLogin 拿到的签名不是官方签名，登录会被拒。
2. **Application 接口**：内核要求 `Application` 实现它的
   `WatchApplicationDelegate`。我们主源码里的 `DshWatchApp` 不可能实现一个
   编译期不存在的接口，所以要靠 `src/qqKernel/java` 这份条件源码集。
3. **包名映射**：内核部分调用会检查包名是不是 `com.tencent.qqlite`。
