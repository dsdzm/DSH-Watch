package com.dshwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.DeviceProfile
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.components.PageDots
import com.dshwatch.ui.nav.Screen
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.screens.chat.ChatScreen
import com.dshwatch.ui.screens.settings.SettingsScreen

/**
 * 主界面：两页横向分页 —— **对话页（根）** ⇄ **设置页**。
 *
 * 交互对齐参考产品：
 *  - 打开就是对话，不需要先在菜单里点一下；
 *  - 向左滑进设置，向右滑回对话，底部圆点指示当前位置；
 *  - 从设置页点进二级页后返回，仍停在设置页（由导航栈保证）。
 *
 * 为什么不用「菜单首页 + push 设置」：手表上多一层菜单等于每次聊天多一次点击，
 * 而设置是低频操作，横滑一步就到更合理。
 */
@Composable
fun MainPager(
    container: AppContainer,
    settings: AppSettings,
    profile: DeviceProfile,
    nav: WatchNavigator,
) {
    val initialPage = if (nav.current == Screen.Settings) 1 else 0
    val pager = rememberPagerState(initialPage = initialPage) { 2 }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ChatScreen(container = container, settings = settings, nav = nav)
                else -> SettingsScreen(
                    container = container,
                    settings = settings,
                    profile = profile,
                    nav = nav,
                )
            }
        }

        // 指示点只在设置页显示：对话页底部是输入栏，再叠一层点会打架
        if (pager.currentPage == 1) {
            PageDots(
                count = 2,
                current = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
            )
        }
    }

    // 滑动后把导航栈同步成当前页，保证「从二级页返回」回到看得见的那一页
    LaunchedEffect(pager.currentPage) {
        val target = if (pager.currentPage == 0) Screen.Chat else Screen.Settings
        if (nav.current != target) nav.replaceTop(target)
    }
}
