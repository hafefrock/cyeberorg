package com.example

import com.example.terminal.ExtraKeyCode
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.ShellIntegration
import com.example.terminal.TerminalBackend
import com.example.terminal.TerminalInputEncoder
import com.example.terminal.TerminalSessionHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeTerminalBackendTest {

    @Test
    fun `extra keys and ctrl encoder produce standard PTY byte sequences`() {
        assertArrayEquals(byteArrayOf(0x1B), ExtraKeyCode.ESC.toByteSequence())
        assertArrayEquals(byteArrayOf(0x09), ExtraKeyCode.TAB.toByteSequence())
        assertArrayEquals(byteArrayOf(0x03), TerminalInputEncoder.encodeInput("c", ctrlArmed = true, altArmed = false))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), ExtraKeyCode.ARROW_UP.toByteSequence())
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()), ExtraKeyCode.ARROW_DOWN.toByteSequence())
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()), ExtraKeyCode.ARROW_RIGHT.toByteSequence())
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()), ExtraKeyCode.ARROW_LEFT.toByteSequence())
    }

    @Test
    fun `write updates cell grid with ANSI colors and writes state file atomically`() {
        val backend: TerminalBackend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        val initialVersion = session.screenVersion.value

        session.resize(cols = 60, rows = 20)
        val resized = session.readScreen()
        assertEquals(60, resized.cols)
        assertEquals(20, resized.rows)
        assertTrue(session.screenVersion.value > initialVersion)

        session.write("sh scripts/deploy.sh".encodeToByteArray())
        assertEquals(0, session.lastExitCode.value)
        assertTrue(session.readRecentText().contains("sh scripts/deploy.sh"))

        session.write(byteArrayOf(0x0D))
        assertEquals(1, session.lastExitCode.value)
        val err = session.lastErrorContext.value
        assertNotNull(err)
        assertEquals("sh scripts/deploy.sh", err?.command)
        assertEquals(1, err?.exitCode)

        val statePath = backend.stateFilePath(session.id)
        assertEquals(session.stateFilePath(), statePath)
        val parsedStateFile = ShellIntegration.parseStateFile(File(statePath).readText())
        assertNotNull(parsedStateFile)
        assertEquals(1, parsedStateFile?.exitCode)

        val screen = session.readScreen()
        val hasRedCell = screen.visibleRows.flatten().any { it.fgColorCode == 31 && it.char != ' ' }
        assertTrue(hasRedCell)

        session.write("echo pending".encodeToByteArray())
        session.write(byteArrayOf(0x03))
        assertEquals(130, session.lastExitCode.value)
    }

    @Test
    fun `closing the last session enters empty state and createSession recovers`() {
        val backend: TerminalBackend = FakeTerminalBackend()
        val initial = backend.sessions.value.first()
        backend.closeSession(initial.id)

        assertTrue(backend.sessions.value.isEmpty())
        assertEquals("", backend.activeSessionId.value)

        val recreated = backend.createSession("fresh-bash")
        assertEquals(1, backend.sessions.value.size)
        assertEquals(recreated.id, backend.activeSessionId.value)
    }

    @Test
    fun `OscStreamParser handles sequences split across arbitrary byte chunks and BEL or ST terminators`() {
        val parser = ShellIntegration.OscStreamParser()

        val r1 = parser.feed("\u001B[32mok\u001B".encodeToByteArray())
        val r2 = parser.feed("]7777;exit=42;cwd=/tmp/".encodeToByteArray())
        val r3 = parser.feed("proj\u0007\n".encodeToByteArray())

        val combinedClean = r1.cleanText + r2.cleanText + r3.cleanText
        val combinedEvents = r1.events + r2.events + r3.events

        assertEquals("\u001B[32mok\n", combinedClean)
        assertEquals(1, combinedEvents.size)
        assertEquals(42, combinedEvents.first().exitCode)
        assertEquals("/tmp/proj", combinedEvents.first().cwd)

        val stMarker = "hi\u001B]7777;exit=0;cwd=/home\u001B\\!"
        val cleanSb = StringBuilder()
        val events = mutableListOf<ShellIntegration.ShellPromptEvent>()
        for (byte in stMarker.encodeToByteArray()) {
            val res = parser.feed(byteArrayOf(byte))
            cleanSb.append(res.cleanText)
            events.addAll(res.events)
        }
        assertEquals("hi!", cleanSb.toString())
        assertEquals(1, events.size)
        assertEquals(0, events.first().exitCode)
        assertEquals("/home", events.first().cwd)
    }

    @Test
    fun `OscStreamParser flushes malformed and oversized sequences over 4KB as plain text`() {
        val parser = ShellIntegration.OscStreamParser(maxBufferBytes = 4096)

        val malformed = parser.feed("A\u001B]7777;exit=bad\u0007B")
        assertTrue(malformed.events.isEmpty())
        assertTrue(malformed.cleanText.contains("7777;exit=bad"))
        assertTrue(malformed.cleanText.startsWith("A") && malformed.cleanText.endsWith("B"))

        val hugePayload = "\u001B]7777;exit=1;cwd=/" + "x".repeat(4200)
        val overRes = parser.feed(hugePayload)
        assertTrue(overRes.events.isEmpty())
        assertTrue(overRes.cleanText.length > 4096)
        assertTrue(overRes.cleanText.contains("7777;exit=1;cwd=/"))
    }

    @Test
    fun `shell integration provides both BASH PROMPT_COMMAND and ZSH precmd snippets with state file`() {
        val path = "/data/data/com.termux/files/usr/tmp/hs_state_1"
        val bash = ShellIntegration.bashSnippet(path)
        val zsh = ShellIntegration.zshSnippet(path)

        assertTrue(bash.contains("PROMPT_COMMAND"))
        assertTrue(bash.contains("7777"))
        assertTrue(bash.contains(path))
        assertTrue(bash.contains("mv -f"))

        assertTrue(zsh.contains("add-zsh-hook precmd"))
        assertTrue(zsh.contains("7777"))
        assertTrue(zsh.contains(path))
        assertTrue(zsh.contains("mv -f"))
    }

    @Test
    fun `FakeTerminalBackend tracks DECSET 2004 and bracketed paste does not auto-execute multiline commands`() {
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()

        // Interactive bash startup enables DECSET 2004
        assertTrue(session.bracketedPasteEnabled.value)

        // Paste multiline text with trailing newline: should sit in buffer without running until Enter
        session.pasteText("mkdir demo_dir\ntouch demo_dir/a.txt\n")
        val beforeEnter = session.readRecentText()
        assertTrue(beforeEnter.contains("mkdir demo_dir"))
        assertTrue(beforeEnter.contains("touch demo_dir/a.txt"))
        assertFalse(beforeEnter.contains(" && "))

        // Sending 'ls demo_dir' after Enter works only once Enter is pressed
        session.write(byteArrayOf(0x0D))
        session.write("ls demo_dir\r".encodeToByteArray())
        assertTrue(session.readRecentText().contains("a.txt"))

        // DECRST 2004 turns bracketed paste off; DECSET 2004 turns it back on
        session.write("echo -e \"\\e[?2004l\"\r".encodeToByteArray())
        assertFalse(session.bracketedPasteEnabled.value)

        // When 2004 is off, direct pasteText with newlines must NOT write anything,
        // while single-line text inserts as-is with no trailing newline.
        val versionBeforeBlockedPaste = session.screenVersion.value
        session.pasteText("echo blocked1\necho blocked2")
        assertEquals(versionBeforeBlockedPaste, session.screenVersion.value)

        session.pasteText("single_cmd\n")
        assertTrue(session.readRecentText().contains("single_cmd"))

        session.write(byteArrayOf(0x15))
        session.write("echo -e \"\\e[?2004h\"\r".encodeToByteArray())
        assertTrue(session.bracketedPasteEnabled.value)
    }

    @Test
    fun `pasteText sends exact ESC 200~ markers when 2004 is enabled with no trailing newline`() {
        val backend = FakeTerminalBackend()
        val realSession = backend.sessions.value.first()
        val capturedWrites = mutableListOf<String>()

        val spyHandle = object : TerminalSessionHandle by realSession {
            override fun write(bytes: ByteArray) {
                capturedWrites.add(bytes.decodeToString())
                realSession.write(bytes)
            }

            override fun pasteText(text: String) {
                val normalized = text.replace("\r\n", "\n").trimEnd('\r', '\n')
                if (normalized.isEmpty()) return
                if (bracketedPasteEnabled.value) {
                    write("\u001B[200~$normalized\u001B[201~".encodeToByteArray())
                } else if (!normalized.contains('\n') && !normalized.contains('\r')) {
                    write(normalized.encodeToByteArray())
                }
            }
        }

        assertTrue(spyHandle.bracketedPasteEnabled.value)
        spyHandle.pasteText("echo one\necho two\n")
        assertEquals(listOf("\u001B[200~echo one\necho two\u001B[201~"), capturedWrites)
    }

    @Test
    fun `pasteText iteratively strips doubled split 201~ markers, ESC, and C0 controls except newline and tab`() {
        val backend = FakeTerminalBackend()
        val realSession = backend.sessions.value.first()
        val capturedWrites = mutableListOf<String>()

        // Wrap write via TerminalPasteSanitizer contract and also exercise realSession.pasteText end-to-end
        val rawAttack = "ok\u001B[201~\u001B[201~;id" + // doubled ESC[201~
            "\u001B[20\u001B[201~1~" +               // nested/split [201~
            "\u001B[200~" +                          // extra start marker
            "\u0000\u0003\u0007\u001B" +             // C0 controls & ESC
            "\tcol2\nnext_line\n"                    // preserved \t and internal \n, trailing \n trimmed

        val sanitized = com.example.terminal.TerminalPasteSanitizer.sanitize(rawAttack)
        assertFalse(sanitized.text.contains("\u001B"))
        assertFalse(sanitized.text.contains("200~"))
        assertFalse(sanitized.text.contains("201~"))
        assertFalse(sanitized.text.contains("\u0000"))
        assertFalse(sanitized.text.contains("\u0003"))
        assertFalse(sanitized.text.contains("\u0007"))
        assertTrue(sanitized.text.contains("\t"))
        assertTrue(sanitized.text.contains("\n"))
        assertEquals("ok;id\tcol2\nnext_line", sanitized.text)

        // End-to-end in FakeTerminalBackend with bracketed paste ON: nothing executes as command
        val spySession = object : com.example.terminal.TerminalSessionHandle by realSession {
            override fun write(bytes: ByteArray) {
                capturedWrites.add(bytes.decodeToString())
                realSession.write(bytes)
            }
        }
        // Directly call realSession.pasteText and verify grid stays single draft without running 'id'
        realSession.pasteText(rawAttack)
        val recent = realSession.readRecentText()
        assertTrue(recent.contains("ok;id"))
        assertTrue(recent.contains("next_line"))
        assertFalse(recent.contains("uid=")) // 'id' did NOT execute!

        // Verify sanitized wrap contract preserves outer 200~...201~ only
        assertTrue(com.example.terminal.TerminalPasteSanitizer.sanitize("a[20[201~1~]b").text == "a]b")
        assertFalse(realSession.lastPasteTruncated.value)
        assertTrue(capturedWrites.isEmpty()) // realSession didn't hit spySession above
    }

    @Test
    fun `long 64KB+ paste completes quickly without quadratic redraw and reports truncation`() {
        val backend = FakeTerminalBackend()
        val session = backend.sessions.value.first()
        assertTrue(session.bracketedPasteEnabled.value)

        // 80 KB payload (exceeds 64 KB limit)
        val payload80Kb = "abcd1234 ".repeat(9_100) // ~81,900 bytes
        val startNs = System.nanoTime()
        val outcome = session.pasteTextWithOutcome(payload80Kb)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L

        assertTrue(outcome.inserted)
        assertTrue(outcome.truncated)
        assertTrue(session.lastPasteTruncated.value)
        assertTrue("Expected fast O(N) paste (< 1500ms), took ${elapsedMs}ms", elapsedMs < 1_500L)

        // Verify small paste resets lastPasteTruncated to false
        val smallOutcome = session.pasteTextWithOutcome("pwd")
        assertTrue(smallOutcome.inserted)
        assertFalse(smallOutcome.truncated)
        assertFalse(session.lastPasteTruncated.value)
    }
}
