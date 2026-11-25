package com.example.termux.vnc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal RFB 3.7/3.8 client: security "None" only (the desktop listens on localhost),
 * Raw + CopyRect + DesktopSize encodings, 32-bit true colour.
 */
class RfbClient(input: InputStream, output: OutputStream) {
    private val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
    private val dout = BufferedOutputStream(output, 4096)
    private val pixelLock = Any()
    private val outLock = Any()

    @Volatile var width: Int = 0
        private set
    @Volatile var height: Int = 0
        private set
    @Volatile var desktopName: String = ""
        private set

    private var pixels = IntArray(0)

    /** Called on the reader thread after a framebuffer update was applied. */
    var onFrame: (() -> Unit)? = null

    /** Called on the reader thread when the remote desktop size changed. */
    var onResize: ((Int, Int) -> Unit)? = null

    fun handshake() {
        val versionBytes = ByteArray(12)
        din.readFully(versionBytes)
        val version = String(versionBytes, Charsets.US_ASCII)
        if (!version.startsWith("RFB ") || version.length < 11) throw IOException("Not a VNC server")
        val minor = version.substring(8, 11).toIntOrNull() ?: 0
        if (minor < 7) throw IOException("VNC server too old (RFB $version)")
        sendRaw("RFB 003.008\n".toByteArray(Charsets.US_ASCII))

        val count = din.readUnsignedByte()
        if (count == 0) throw IOException("Server refused: " + readReason())
        val types = ByteArray(count)
        din.readFully(types)
        if (!types.contains(1.toByte())) throw IOException("Server requires authentication")
        sendRaw(byteArrayOf(1))
        if (minor >= 8) {
            val result = din.readInt()
            if (result != 0) throw IOException("Security failed: " + readReason())
        }
        sendRaw(byteArrayOf(1)) // ClientInit: shared

        val w = din.readUnsignedShort()
        val h = din.readUnsignedShort()
        skipFully(16) // server pixel format; we replace it below
        val nameLen = din.readInt()
        if (nameLen < 0 || nameLen > 4096) throw IOException("Bad desktop name")
        val nameBytes = ByteArray(nameLen)
        din.readFully(nameBytes)
        desktopName = String(nameBytes, Charsets.UTF_8)
        resizeTo(w, h)

        sendSetPixelFormat()
        sendSetEncodings()
        requestUpdate(incremental = false)
    }

    /** Reads and applies exactly one server message. */
    fun readMessage() {
        when (val type = din.readUnsignedByte()) {
            0 -> readFramebufferUpdate()
            1 -> { // SetColourMapEntries (unused with true colour)
                skipFully(1)
                din.readUnsignedShort()
                val n = din.readUnsignedShort()
                skipFully(n * 6)
            }
            2 -> Unit // Bell
            3 -> { // ServerCutText
                skipFully(3)
                val len = din.readInt()
                if (len < 0) throw IOException("Bad cut text length")
                skipFully(len)
            }
            else -> throw IOException("Unknown server message $type")
        }
    }

    /** Blocks, applying messages until the connection fails or is closed. */
    fun runLoop() {
        while (true) readMessage()
    }

    fun requestUpdate(incremental: Boolean) {
        val w = width
        val h = height
        val msg = byteArrayOf(
            3, if (incremental) 1 else 0,
            0, 0, 0, 0,
            (w shr 8).toByte(), w.toByte(),
            (h shr 8).toByte(), h.toByte()
        )
        sendRaw(msg)
    }

    fun sendPointer(mask: Int, x: Int, y: Int) {
        val cx = x.coerceIn(0, maxOf(0, width - 1))
        val cy = y.coerceIn(0, maxOf(0, height - 1))
        sendRaw(byteArrayOf(5, mask.toByte(), (cx shr 8).toByte(), cx.toByte(), (cy shr 8).toByte(), cy.toByte()))
    }

    fun sendKey(down: Boolean, keysym: Int) {
        sendRaw(
            byteArrayOf(
                4, if (down) 1 else 0, 0, 0,
                (keysym shr 24).toByte(), (keysym shr 16).toByte(), (keysym shr 8).toByte(), keysym.toByte()
            )
        )
    }

    /** Copies the framebuffer (ARGB) into [dst]; returns false when the size does not match. */
    fun copyPixels(dst: IntArray): Boolean = synchronized(pixelLock) {
        if (dst.size != pixels.size) return false
        System.arraycopy(pixels, 0, dst, 0, pixels.size)
        true
    }

    fun pixelAt(x: Int, y: Int): Int = synchronized(pixelLock) {
        if (x !in 0 until width || y !in 0 until height) 0 else pixels[y * width + x]
    }

    // ---- internals ----

    private fun sendSetPixelFormat() {
        val m = ByteArray(20)
        m[0] = 0
        m[4] = 32 // bits per pixel
        m[5] = 24 // depth
        m[6] = 0 // little endian
        m[7] = 1 // true colour
        m[8] = 0; m[9] = 255.toByte() // red max
        m[10] = 0; m[11] = 255.toByte() // green max
        m[12] = 0; m[13] = 255.toByte() // blue max
        m[14] = 16 // red shift
        m[15] = 8 // green shift
        m[16] = 0 // blue shift
        sendRaw(m)
    }

    private fun sendSetEncodings() {
        val encodings = intArrayOf(1, 0, -223) // CopyRect, Raw, DesktopSize
        val m = ByteArray(4 + encodings.size * 4)
        m[0] = 2
        m[2] = (encodings.size shr 8).toByte()
        m[3] = encodings.size.toByte()
        encodings.forEachIndexed { i, e ->
            val o = 4 + i * 4
            m[o] = (e shr 24).toByte(); m[o + 1] = (e shr 16).toByte()
            m[o + 2] = (e shr 8).toByte(); m[o + 3] = e.toByte()
        }
        sendRaw(m)
    }

    private fun readFramebufferUpdate() {
        skipFully(1)
        val rects = din.readUnsignedShort()
        repeat(rects) {
            val x = din.readUnsignedShort()
            val y = din.readUnsignedShort()
            val w = din.readUnsignedShort()
            val h = din.readUnsignedShort()
            when (val encoding = din.readInt()) {
                0 -> readRaw(x, y, w, h)
                1 -> readCopyRect(x, y, w, h)
                -223 -> {
                    resizeTo(w, h)
                    onResize?.invoke(w, h)
                }
                else -> throw IOException("Unsupported encoding $encoding")
            }
        }
        onFrame?.invoke()
        requestUpdate(incremental = true)
    }

    private fun readRaw(x: Int, y: Int, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val row = ByteArray(w * 4)
        for (r in 0 until h) {
            din.readFully(row)
            val py = y + r
            synchronized(pixelLock) {
                if (py < height) {
                    val base = py * width
                    var i = 0
                    for (c in 0 until w) {
                        val px = x + c
                        if (px < width) {
                            val b = row[i].toInt() and 0xFF
                            val g = row[i + 1].toInt() and 0xFF
                            val rr = row[i + 2].toInt() and 0xFF
                            pixels[base + px] = (0xFF shl 24) or (rr shl 16) or (g shl 8) or b
                        }
                        i += 4
                    }
                }
            }
        }
    }

    private fun readCopyRect(x: Int, y: Int, w: Int, h: Int) {
        val sx = din.readUnsignedShort()
        val sy = din.readUnsignedShort()
        synchronized(pixelLock) {
            val cw = minOf(w, width - x, width - sx)
            val ch = minOf(h, height - y, height - sy)
            if (cw <= 0 || ch <= 0) return
            val tmp = IntArray(cw * ch)
            for (r in 0 until ch) System.arraycopy(pixels, (sy + r) * width + sx, tmp, r * cw, cw)
            for (r in 0 until ch) System.arraycopy(tmp, r * cw, pixels, (y + r) * width + x, cw)
        }
    }

    private fun resizeTo(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) throw IOException("Bad desktop size ${w}x$h")
        synchronized(pixelLock) {
            pixels = IntArray(w * h) { 0xFF000000.toInt() }
            width = w
            height = h
        }
    }

    private fun readReason(): String {
        val len = din.readInt()
        if (len < 0 || len > 4096) return "?"
        val b = ByteArray(len)
        din.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun skipFully(count: Int) {
        var left = count
        val buf = ByteArray(minOf(maxOf(count, 1), 8192))
        while (left > 0) {
            val n = minOf(left, buf.size)
            din.readFully(buf, 0, n)
            left -= n
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        synchronized(outLock) {
            dout.write(bytes)
            dout.flush()
        }
    }
}
