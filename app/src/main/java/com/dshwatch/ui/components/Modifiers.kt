package com.dshwatch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier

/** 统一点击扩展，方便以后统一加防抖/触感 */
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(enabled = enabled, onClick = onClick)
