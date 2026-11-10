package com.dshwatch.domain.agent

import com.dshwatch.domain.agent.tools.BuiltinTools
import org.junit.Test

class TmpGroup {
    @Test
    fun print() {
        BuiltinTools.all().sortedByDescending { it.description.length + it.parameters.toString().length }
            .forEach {
                println("${it.name}\t${it.description.length}\t${it.parameters.toString().length}")
            }
    }
}
