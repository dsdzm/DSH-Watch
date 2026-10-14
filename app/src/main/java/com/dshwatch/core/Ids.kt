package com.dshwatch.core

import java.util.UUID

object Ids {
    fun new(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
