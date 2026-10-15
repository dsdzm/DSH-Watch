plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Python 引擎（Chaquopy）只在这里声明、不应用 ——
    // 真正 apply 由 app 模块按 `-PwithPython=true` 决定，详见 app/build.gradle.kts。
    alias(libs.plugins.chaquopy.python) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
