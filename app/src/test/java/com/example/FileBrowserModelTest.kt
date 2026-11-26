package com.example

import com.example.ui.files.FileBrowserModel
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileBrowserModelTest {
    @Test
    fun formatsSizes() {
        assertEquals("0 B", FileBrowserModel.formatSize(0))
        assertEquals("1023 B", FileBrowserModel.formatSize(1023))
        assertEquals("1.0 KB", FileBrowserModel.formatSize(1024))
        assertEquals("1.5 MB", FileBrowserModel.formatSize(1_572_864))
    }

    @Test
    fun quotesPathsForTheShell() {
        assertEquals("'/a b/c'", FileBrowserModel.shellQuote("/a b/c"))
        assertEquals("'it'\\''s'", FileBrowserModel.shellQuote("it's"))
        assertEquals("cd '/x y'", FileBrowserModel.cdCommand("/x y"))
    }

    @Test
    fun listsFoldersFirstThenFilesByName() {
        val dir = createTempDirectory("files").toFile()
        try {
            File(dir, "b.txt").writeText("hello")
            File(dir, "A.txt").writeText("")
            File(dir, "zdir").mkdir()
            File(dir, "adir").mkdir()
            val names = FileBrowserModel.list(dir)!!.map { it.name }
            assertEquals(listOf("adir", "zdir", "A.txt", "b.txt"), names)
            assertEquals(5L, FileBrowserModel.list(dir)!!.first { it.name == "b.txt" }.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun unreadableFolderIsNull() {
        assertNull(FileBrowserModel.list(File("/definitely/not/here")))
    }

    @Test
    fun parentStopsAtRoot() {
        val root = File("/r/home")
        assertNull(FileBrowserModel.parentWithin(File("/r/home"), root))
        assertEquals(File("/r/home"), FileBrowserModel.parentWithin(File("/r/home/x"), root))
    }
}
