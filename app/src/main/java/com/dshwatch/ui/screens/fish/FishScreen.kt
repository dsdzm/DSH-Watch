package com.dshwatch.ui.screens.fish

import androidx.compose.runtime.Composable
import com.dshwatch.core.AppContainer
import com.dshwatch.domain.model.AppSettings
import com.dshwatch.ui.nav.WatchNavigator
import com.dshwatch.ui.phone.LocalPhoneUi

/**
 * 「养鱼」在手表上的入口。
 *
 * 手机上不再经过这里：`PhoneRoot` 直接把 `PhoneNavigator` 交给
 * [com.dshwatch.ui.phone.PhoneTavernScreen] / `FishPhoneScreen` 这些 MD3 页 ——
 * 它们要切底部 tab、要拉抽屉，拿的是手机那套导航，不是这条只压栈的。
 *
 * 留这一个转发函数是为了 `AppRoot` 那条分派不用改：那边手上只有
 * `WatchNavigator`，而它只会在手表界面上跑到。
 */
@Composable
fun FishScreen(
    container: AppContainer,
    settings: AppSettings,
    nav: WatchNavigator,
) {
    FishWatchScreen(container = container, settings = settings, nav = nav)
}
