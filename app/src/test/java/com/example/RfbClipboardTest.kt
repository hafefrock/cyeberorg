package com.example

import com.example.termux.vnc.RfbClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbClipboardTest {
    private fun hello(s: DataOutputStream) {
        s.write("RFB 003.008\n".toByteArray())
        s.writeByte(1); s.writeByte(1)
        s.writeInt(0)
        s.writeShort(1); s.writeShort(1)
        s.write(ByteArray(16))
        s.writeInt(1); s.write('x'.code)
    }

    @Test
    fun serverCutTextIsDelivered() {
        val server = ByteArrayOutputStream()
        val s = DataOutputStream(server)
        hello(s)
        s.writeByte(3); s.write(ByteArray(3))
        val text = "café".toByteArray(Charsets.ISO_8859_1)
        s.writeInt(text.size); s.write(text)

        val client = RfbClient(ByteArrayInputStream(server.toByteArray()), ByteArrayOutputStream())
        var received: String? = null
        client.onServerCutText = { received = it }
        client.handshake()
        client.readMessage()
        assertEquals("café", received)
    }

    @Test
    fun clientCutTextHasTheRightLayout() {
        val server = ByteArrayOutputStream()
        hello(DataOutputStream(server))
        val sent = ByteArrayOutputStream()
        val client = RfbClient(ByteArrayInputStream(server.toByteArray()), sent)
        client.handshake()
        val before = sent.size()
        client.sendClientCutText("hi€")
        val msg = sent.toByteArray().copyOfRange(before, sent.size())
        assertEquals(6, msg[0].toInt())
        assertEquals(3, msg[7].toInt())
        assertEquals('h'.code, msg[8].toInt())
        assertEquals('?'.code, msg[10].toInt())
        assertTrue(msg.size == 11)
    }
}
