package com.example

import com.example.termux.TermuxPaths
import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxPathsTest {
    @Test
    fun rewriteUser0_usesDataDataForm() {
        assertEquals(
            "/data/data/com.termux/files",
            TermuxPaths.rewriteUser0("/data/user/0/com.termux/files"),
        )
        assertEquals("/tmp/x", TermuxPaths.rewriteUser0("/tmp/x"))
    }
}
