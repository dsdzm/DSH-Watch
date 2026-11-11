package com.dshwatch.domain.shell

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Shizuku 的接入声明。
 *
 * 这三条各自都能独立造成「装了 Shizuku，App 说没装」：
 * 包名写成权限前缀、清单没有包可见性、provider 没注册。
 * 真机上排查过一次就得钉住，不然改注释、搬文件时很容易再丢。
 */
class ShizukuManifestTest {

    private val manifest: String by lazy {
        // 单测的工作目录是模块根（app/）
        File("src/main/AndroidManifest.xml").readText()
    }

    @Test
    fun `包名是应用包名，不是权限前缀`() {
        assertThat(ShizukuShell.PACKAGE).isEqualTo("moe.shizuku.privileged.api")
        // 权限名长这样：moe.shizuku.manager.permission.API_V23
        assertThat(ShizukuShell.PACKAGE).doesNotContain("permission")
        assertThat(ShizukuShell.PACKAGE).doesNotContain("moe.shizuku.manager")
    }

    @Test
    fun `清单声明了 Shizuku 的包可见性`() {
        // Android 11+ 不声明这一段，getPackageInfo 直接抛 NameNotFoundException
        assertThat(manifest).contains("""<package android:name="moe.shizuku.privileged.api" />""")
    }

    @Test
    fun `清单注册了 ShizukuProvider`() {
        assertThat(manifest).contains("rikka.shizuku.ShizukuProvider")
        assertThat(manifest).contains("\${applicationId}.shizuku")
        assertThat(manifest).contains("moe.shizuku.manager.permission.API_V23")
    }
}
