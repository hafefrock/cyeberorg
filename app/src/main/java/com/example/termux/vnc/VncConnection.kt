package com.example.termux.vnc

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** Connects to the local desktop (retrying while it starts), pumps updates, and sends input in order. */
class VncConnection(
    private val host: String = "127.0.0.1",
    private val port: Int = 5901
) {
    interface Listener {
        fun onConnected(client: RfbClient)
        fun onFrame()
        fun onClosed(error: String?)

        /** The desktop copied some text. Called on the reader thread. */
        fun onClipboard(text: String) {}
    }

    private companion object {
        const val RECONNECT_MS = 30_000L
    }

    @Volatile private var client: RfbClient? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "vnc-send").apply { isDaemon = true } }
    private var reader: Thread? = null

    fun start(listener: Listener, timeoutMs: Long = 180_000L) {
        reader = Thread({
            var deadline = System.currentTimeMillis() + timeoutMs
            var lastError: String? = null
            while (!closed) {
                var s: Socket? = null
                try {
                    s = Socket()
                    s.connect(InetSocketAddress(host, port), 2_000)
                    s.tcpNoDelay = true
                    socket = s
                    val c = RfbClient(s.getInputStream(), s.getOutputStream())
                    c.onFrame = { listener.onFrame() }
                    c.onServerCutText = { text -> listener.onClipboard(text) }
                    c.handshake()
                    client = c
                    listener.onConnected(c)
                    c.runLoop()
                    throw java.io.EOFException("connection closed")
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    runCatching { s?.close() }
                    if (closed) break
                    // A dropped session is retried for a while: the desktop is usually still running.
                    if (client != null) deadline = System.currentTimeMillis() + RECONNECT_MS
                    client = null
                    if (System.currentTimeMillis() >= deadline) {
                        listener.onClosed(lastError)
                        return@Thread
                    }
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
            if (closed) listener.onClosed(null)
        }, "vnc-read").apply {
            isDaemon = true
            start()
        }
    }

    fun pointer(mask: Int, x: Int, y: Int) = send { it.sendPointer(mask, x, y) }

    fun key(down: Boolean, keysym: Int) {
        if (keysym != 0) send { it.sendKey(down, keysym) }
    }

    fun tapKey(keysym: Int) {
        key(true, keysym)
        key(false, keysym)
    }

    /** Puts [text] on the desktop's clipboard (sent in order with later key presses). */
    fun clipboard(text: String) = send { it.sendClientCutText(text) }

    private fun send(block: (RfbClient) -> Unit) {
        val c = client ?: return
        if (closed) return
        try {
            sender.execute {
                try {
                    block(c)
                } catch (_: Exception) {
                    // the reader thread reports connection failures
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        reader?.interrupt()
        sender.shutdown()
    }
}
