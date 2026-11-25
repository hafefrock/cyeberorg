package com.example

import com.example.termux.vnc.RfbClient
import com.example.termux.vnc.VncKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbClientTest {
    private fun serverHello(w: Int, h: Int, out: DataOutputStream) {
        out.write("RFB 003.008\n".toByteArray())
        out.writeByte(1); out.writeByte(1) // one security type: None
        out.writeInt(0) // security ok
        out.writeShort(w); out.writeShort(h)
        out.write(ByteArray(16))
        val name = "test".toByteArray()
        out.writeInt(name.size); out.write(name)
    }

    @Test
    fun handshakeAndRawUpdate() {
        val server = ByteArrayOutputStream()
        val s = DataOutputStream(server)
        serverHello(2, 1, s)
        // FramebufferUpdate: 1 rect raw 2x1 (B,G,R,X per pixel)
        s.writeByte(0); s.writeByte(0); s.writeShort(1)
        s.writeShort(0); s.writeShort(0); s.writeShort(2); s.writeShort(1); s.writeInt(0)
        s.write(byteArrayOf(0x10, 0x20, 0x30, 0)) // r=0x30 g=0x20 b=0x10
        s.write(byteArrayOf(0, 0, 0xFF.toByte(), 0)) // red

        val sent = ByteArrayOutputStream()
        val client = RfbClient(ByteArrayInputStream(server.toByteArray()), sent)
        var frames = 0
        client.onFrame = { frames++ }
        client.handshake()
        assertEquals(2, client.width)
        assertEquals(1, client.height)
        assertEquals("test", client.desktopName)
        client.readMessage()
        assertEquals(1, frames)
        assertEquals(0xFF302010.toInt(), client.pixelAt(0, 0))
        assertEquals(0xFFFF0000.toInt(), client.pixelAt(1, 0))
        assertTrue(String(sent.toByteArray(), Charsets.ISO_8859_1).startsWith("RFB 003.008\n"))
    }

    @Test
    fun copyRectMovesPixels() {
        val server = ByteArrayOutputStream()
        val s = DataOutputStream(server)
        serverHello(2, 1, s)
        s.writeByte(0); s.writeByte(0); s.writeShort(2)
        s.writeShort(0); s.writeShort(0); s.writeShort(1); s.writeShort(1); s.writeInt(0)
        s.write(byteArrayOf(1, 2, 3, 0))
        s.writeShort(1); s.writeShort(0); s.writeShort(1); s.writeShort(1); s.writeInt(1)
        s.writeShort(0); s.writeShort(0)

        val client = RfbClient(ByteArrayInputStream(server.toByteArray()), ByteArrayOutputStream())
        client.handshake()
        client.readMessage()
        assertEquals(client.pixelAt(0, 0), client.pixelAt(1, 0))
    }

    @Test
    fun keysymMapping() {
        assertEquals(VncKeys.RETURN, VncKeys.keysymForChar('\n'.code))
        assertEquals('a'.code, VncKeys.keysymForChar('a'.code))
        assertEquals(0x01000000 + 0x20AC, VncKeys.keysymForChar(0x20AC))
        assertEquals('c'.code, VncKeys.ctrlLetter('C'.code))
        assertEquals(0, VncKeys.ctrlLetter('1'.code))
    }
}
