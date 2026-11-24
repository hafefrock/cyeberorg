package com.example

import com.example.data.CommandSnippet
import com.example.data.PromptHistoryItem
import com.example.data.PromptHistoryReducer
import com.example.data.SnippetJsonCodec
import com.example.terminal.TerminalCell
import com.example.terminal.TerminalGridPoint
import com.example.terminal.TerminalGridSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetAndSelectionTest {

    @Test
    fun `snippet export and import JSON round-trips cleanly`() {
        val original = listOf(
            CommandSnippet(
                id = "s1",
                title = "Disk Check",
                command = "du -sh *",
                tags = listOf("disk", "storage"),
                updatedAtMillis = 123456L
            ),
            CommandSnippet(
                id = "s2",
                title = "Git Log",
                command = "git log -n 10 --oneline",
                tags = listOf("git"),
                updatedAtMillis = 123457L
            )
        )

        val json = SnippetJsonCodec.exportToJson(original, exportedAtMillis = 999L)
        assertTrue(json.contains("hackerstudio_snippets"))

        val imported = SnippetJsonCodec.parseImportJson(json)
        assertTrue(imported.isSuccess)
        val items = imported.getOrThrow()
        assertEquals(2, items.size)
        assertEquals("Disk Check", items[0].title)
        assertEquals("du -sh *", items[0].command)
        assertEquals(listOf("disk", "storage"), items[0].tags)
        assertEquals("Git Log", items[1].title)
    }

    @Test
    fun `snippet import handles raw JSON array and rejects invalid or empty JSON`() {
        val rawArray = """
            [
              {"id":"a1","title":"Check Ports","command":"ss -tulnp","tags":["NET","Debug"]},
              {"id":"empty","title":"Ignored","command":"   ","tags":[]}
            ]
        """.trimIndent()
        val arrayRes = SnippetJsonCodec.parseImportJson(rawArray)
        assertTrue(arrayRes.isSuccess)
        val parsed = arrayRes.getOrThrow()
        assertEquals(1, parsed.size)
        assertEquals(listOf("net", "debug"), parsed.first().tags)

        assertTrue(SnippetJsonCodec.parseImportJson("").isFailure)
        assertTrue(SnippetJsonCodec.parseImportJson("{not valid json").isFailure)
    }

    @Test
    fun `prompt history caps at 30 items and deduplicates newest first`() {
        var list = emptyList<PromptHistoryItem>()
        for (i in 1..35) {
            list = PromptHistoryReducer.appendPrompt(
                current = list,
                rawPrompt = "prompt-$i",
                nowMillis = i.toLong(),
                idFactory = { "id-$i" }
            )
        }
        assertEquals(30, list.size)
        assertEquals("prompt-35", list.first().prompt)
        assertEquals("prompt-6", list.last().prompt)

        // Re-sending an older prompt moves it to the top without duplicating
        list = PromptHistoryReducer.appendPrompt(
            current = list,
            rawPrompt = "prompt-10",
            nowMillis = 999L,
            idFactory = { "id-reused" }
        )
        assertEquals(30, list.size)
        assertEquals("prompt-10", list.first().prompt)
        assertEquals(1, list.count { it.prompt == "prompt-10" })
    }

    @Test
    fun `terminal grid selection extracts single-line and multi-line text accurately`() {
        fun row(text: String, width: Int = 20): List<TerminalCell> =
            List(width) { col -> TerminalCell(char = if (col < text.length) text[col] else ' ') }

        val grid = listOf(
            row("u0@termux:~$ ls"),
            row("error: file missing"),
            row("exit code 2")
        )

        val singleWord = TerminalGridSelection(
            anchor = TerminalGridPoint(1, 7),
            focus = TerminalGridPoint(1, 18)
        )
        assertEquals("file missing", singleWord.extractText(grid))

        val reverseMultiLine = TerminalGridSelection(
            anchor = TerminalGridPoint(2, 8),
            focus = TerminalGridPoint(1, 7)
        )
        assertEquals("file missing\nexit code", reverseMultiLine.extractText(grid))
    }

    @Test
    fun `snippet JSON import rejects entire payload when exceeding file size, count, title, command, or tag limits`() {
        // 1. File size > 1 MB (both raw JSON string and bounded InputStream)
        val oversizedJson = "[\"${"x".repeat(SnippetJsonCodec.MAX_IMPORT_FILE_BYTES + 64)}\"]"
        assertTrue(SnippetJsonCodec.parseImportJson(oversizedJson).isFailure)
        val streamTooLarge = java.io.ByteArrayInputStream(ByteArray(SnippetJsonCodec.MAX_IMPORT_FILE_BYTES + 1) { ' '.code.toByte() })
        assertTrue(SnippetJsonCodec.readUtf8Bounded(streamTooLarge).isFailure)

        // 2. Snippet count > 500
        val tooManySnippets = (1..(SnippetJsonCodec.MAX_IMPORT_SNIPPETS + 1)).map { i ->
            CommandSnippet(id = "s_$i", title = "Cmd $i", command = "echo $i")
        }
        val tooManyJson = SnippetJsonCodec.exportToJson(tooManySnippets)
        assertTrue(SnippetJsonCodec.parseImportJson(tooManyJson).isFailure)

        // 3. Title > 120 chars (atomic failure even when snippet #1 is valid)
        val longTitleList = listOf(
            CommandSnippet(id = "ok", title = "Valid", command = "ls"),
            CommandSnippet(id = "bad_title", title = "T".repeat(121), command = "pwd")
        )
        assertTrue(SnippetJsonCodec.parseImportJson(SnippetJsonCodec.exportToJson(longTitleList)).isFailure)

        // 4. Command > 4000 chars
        val longCmdList = listOf(
            CommandSnippet(id = "ok", title = "Valid", command = "ls"),
            CommandSnippet(id = "bad_cmd", title = "Big Cmd", command = "a".repeat(4001))
        )
        assertTrue(SnippetJsonCodec.parseImportJson(SnippetJsonCodec.exportToJson(longCmdList)).isFailure)

        // 5. Tags > 20
        val tooManyTagsList = listOf(
            CommandSnippet(
                id = "bad_tags",
                title = "Tagged",
                command = "echo ok",
                tags = (1..21).map { "tag$it" }
            )
        )
        assertTrue(SnippetJsonCodec.parseImportJson(SnippetJsonCodec.exportToJson(tooManyTagsList)).isFailure)
    }
}
