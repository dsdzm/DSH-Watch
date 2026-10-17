import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    // Chaquopy 刻意不在这里 apply —— 见下方 withPython。
}

/**
 * 是否把 Python 引擎（Chaquopy）打进这个包。
 *
 * Chaquopy 是**模块级插件**：一旦 apply，所有变体都会带上 libpython（约 25MB）。
 * 它没有「某个变体不打包」的开关，所以只能从构建入口分：
 *
 *   ./gradlew :app:assembleRelease                 → lite（无 Python，约 7MB）
 *   ./gradlew :app:assembleRelease -PwithPython=true → full（含 Python，约 30MB）
 *
 * 两种构建共用同一套 main 源码集 —— 对 Chaquopy 的调用全走反射
 * （见 domain/agent/exec/ReflectionPythonEngine.kt），所以 lite 构建也能正常编译。
 */
val withPython: Boolean = providers.gradleProperty("withPython")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

if (withPython) {
    apply(plugin = "com.chaquo.python")
}

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

android {
    namespace = "com.dshwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dshwatch"
        // Android 9 = API 28，手表端要求
        minSdk = 28
        targetSdk = 34
        versionCode = 13
        versionName = "1.5"

        vectorDrawables { useSupportLibrary = true }
        // 只保留中英资源，减小安装包体积（手表存储紧张）
        resourceConfigurations += listOf("zh", "en")

        if (withPython) {
            // libpython 是原生库，必须指定 ABI。
            // 手表基本全是 arm64-v8a；armeabi-v7a 只支持 Python ≤3.11、体积多几 MB，收益为零，不做。
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // 三种签名方案全开：
                //  - v1(JAR) 兼容部分国产手表自带的安装器（只认老式 JAR 签名）；
                //  - v2 是 Android 7+ 的通用方案；
                //  - v3 是 Android 9+ 的方案，支持密钥轮换。
                // 代价只有几百 KB，换来"装不上"这类问题彻底消失。
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
            if (withPython) {
                // full 的 debug 包额外带上模拟器架构，方便在桌面模拟器里调 Python 路径
                ndk { abiFilters += "x86_64" }
            }
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

if (withPython) {
    /*
     * Chaquopy 配置。
     *
     * Python 版本显式钉死：默认值会随插件版本漂移，而「手表上跑的是哪个 Python」
     * 是用户能感知的事（语法错误信息、可用标准库），不该由升级构建插件悄悄改变。
     *
     * 构建机需要能执行同大版本的 python3（用于把源码编译成 .pyc）。
     * Chaquopy 会先找 python3.11、再找 python3；找不到时用 buildPython("/path/to/python3") 指定。
     */
    extensions.configure<com.chaquo.python.ChaquopyExtension>("chaquopy") {
        defaultConfig {
            version = "3.11"
        }
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

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
