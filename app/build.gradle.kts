import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * 可选的 ABI 过滤：-Pabi=armeabi-v7a 出 32 位包，-Pabi=arm64-v8a 出 64 位包。
 * 不传时保持通用包（不限制 ABI）。
 */
val targetAbi: String? = providers.gradleProperty("abi")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .orNull

/**
 * 发布签名：可选。
 * 在工程根目录放 keystore.properties：
 *   storeFile=/绝对路径/dsh-watch.jks
 *   storePassword=xxx
 *   keyAlias=dsh
 *   keyPassword=xxx
 * 没有该文件时 release 默认不签名（Android Studio 会提示，直接 build 会产出 unsigned apk）。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasKeystore = keystoreProps.getProperty("storeFile")?.let { file(it).exists() } == true

/**
 * 内置供应商（Agnes）的 API Key。
 *
 * ## 为什么不在源码里写死
 *
 * 本仓库是**公开**的。把 key 写进 .kt 文件，等于同时做两件事：
 * 让全世界拿到这把 key，并且让它永久留在 git 历史里（删掉文件也删不掉历史）。
 *
 * 读取顺序（先到先得）：
 *  1. 环境变量 `AGNES_API_KEY` —— CI / 命令行构建用；
 *  2. Gradle 属性 `-PagnesApiKey=...`；
 *  3. 工程根目录 `local.properties` 里的 `agnes.apiKey=...` —— 已被 .gitignore 排除，
 *     Android Studio 本地构建走这条。
 *
 * 三者都为空时编出来是空串：App 仍能启动，内置供应商会提示「需要先填 key」，
 * 其他供应商不受影响。**不会**因为缺 key 而编译失败。
 *
 * ⚠️ 即便这样，key 最终仍会以字符串常量进到 APK 里 —— 客户端内置密钥
 * 天生可被反编译提取。它的定位是「让用户开箱能用」，
 * 真正的防线是服务端的额度与限流，不是这层混淆。
 */
val builtinApiKey: String = run {
    val env = System.getenv("AGNES_API_KEY")?.trim().orEmpty()
    if (env.isNotEmpty()) return@run env
    val prop = providers.gradleProperty("agnesApiKey").orNull?.trim().orEmpty()
    if (prop.isNotEmpty()) return@run prop
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val p = Properties().apply { local.inputStream().use { load(it) } }
        p.getProperty("agnes.apiKey")?.trim().orEmpty()
    } else {
        ""
    }
}

/**
 * 内置供应商（智谱 GLM）的 API Key。
 *
 * 读取顺序与 Agnes 完全一致（环境变量 → Gradle 属性 → local.properties），
 * 单独做成函数是因为 1.17 加第二家内置通道时，把同一段逻辑抄两遍迟早会有一处漏改。
 *
 * ⚠️ 这把 key 只被授权给三个免费模型（glm-4.7-flash / glm-4.6v-flash / cogview-3-flash），
 * 名单硬编码在 [com.dshwatch.domain.model.BuiltinProviders.ZHIPU] 里。
 */
fun readBuiltinKey(env: String, gradleProp: String, localProp: String): String {
    val fromEnv = System.getenv(env)?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return fromEnv
    val fromGradle = providers.gradleProperty(gradleProp).orNull?.trim().orEmpty()
    if (fromGradle.isNotEmpty()) return fromGradle
    val local = rootProject.file("local.properties")
    if (!local.exists()) return ""
    val p = Properties().apply { local.inputStream().use { load(it) } }
    return p.getProperty(localProp)?.trim().orEmpty()
}

val zhipuApiKey: String = readBuiltinKey("ZHIPU_API_KEY", "zhipuApiKey", "zhipu.apiKey")

/** 转义成合法的 Java 字符串字面量，避免 key 里带引号/反斜杠把 buildConfigField 写坏 */
fun javaStringLiteral(raw: String): String = raw
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val builtinApiKeyLiteral: String = javaStringLiteral(builtinApiKey)
val zhipuApiKeyLiteral: String = javaStringLiteral(zhipuApiKey)


android {
    namespace = "com.dshwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dshwatch"
        // Android 9 = API 28，手表端要求
        minSdk = 28
        targetSdk = 34
        versionCode = 66
        versionName = "1.21.2"

        vectorDrawables { useSupportLibrary = true }
        // 只保留中英资源，减小安装包体积（手表存储紧张）
        resourceConfigurations += listOf("zh", "en")

        // 内置供应商的 key，来源见文件顶部 builtinApiKey 的注释
        buildConfigField("String", "BUILTIN_API_KEY", "\"$builtinApiKeyLiteral\"")
        buildConfigField("String", "ZHIPU_API_KEY", "\"$zhipuApiKeyLiteral\"")

        // androidx 自带少量 .so；需要单 ABI 时用 -Pabi 裁掉其余架构。
        if (targetAbi != null) {
            ndk { abiFilters += targetAbi }
        }
    }

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 三种签名方案都声明开启，但**实际产物只带 v3**（用 apksigner verify 实测）。
                //
                // 原因：AGP 8.x 会按 minSdk 优化签名方案 —— 本工程 minSdk 28，
                // 而 v3 正是 API 28 引入的，所有能装上这个 App 的设备都支持 v3，
                // 于是 v1/v2 被省略。这不是配置没生效，是构建链的合理裁剪。
                //
                // 之所以留着这三个 true：万一以后 minSdk 下探到 28 以下，
                // 或者要出给第三方安装器的包，开关已经在那儿了，不用回头改。
                //
                // 实测过的兼容性：v1.13、v1.13-fix、v1.14 三版都是 v3-only，
                // 证书指纹一致（70437a7f…bf31f94），覆盖安装正常。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // 压缩可压缩资源，进一步缩包
        noCompress += listOf("ttf", "otf", "woff")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // 线性图标集：截图风格的描边图标（R8 会裁掉未使用的部分）
    implementation(libs.androidx.compose.material.icons.extended)
    // Wear Material3：Chip / Card / ScalingLazyColumn / PositionIndicator 等手表组件
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    // 本地 JS 执行引擎（纯 Java，不需要 NDK/原生库，lite 包也带）
    implementation(libs.rhino)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // 扫码登录：只用 core（生成 QR 矩阵），不引 android 那一层
    implementation(libs.zxing.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
