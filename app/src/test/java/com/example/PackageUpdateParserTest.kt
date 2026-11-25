package com.example

import com.example.termux.PackageUpdateParser
import org.junit.Assert.assertEquals
import org.junit.Test

class PackageUpdateParserTest {
    @Test
    fun countsUpgradableLines() {
        val out = """
            WARNING: apt does not have a stable CLI interface.
            Listing...
            curl/stable 8.5.0 aarch64 [upgradable from: 8.4.0]
            git/stable 2.43 aarch64 [upgradable from: 2.42]
        """.trimIndent()
        assertEquals(2, PackageUpdateParser.countUpgradable(out))
    }

    @Test
    fun noUpdatesIsZero() {
        assertEquals(0, PackageUpdateParser.countUpgradable("Listing...\n"))
        assertEquals(0, PackageUpdateParser.countUpgradable(""))
    }
}
