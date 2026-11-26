package com.example.ui.desktop

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.termux.vnc.RfbClient
import com.example.termux.vnc.VncConnection
import com.example.termux.vnc.VncKeys
import com.example.ui.theme.StudioTheme
import kotlin.math.abs

private const val CURSOR_GAIN = 1.6f
private const val MOVE_SLOP_PX = 8f
private const val TAP_TRAVEL_PX = 24f
private const val TAP_MAX_MS = 300L
private const val SCROLL_STEP_PX = 30f

/** Full-screen viewer for the local Ubuntu desktop (VNC on localhost:5901), touchpad style. */
@Composable
fun DesktopViewerDialog(onDismiss: () -> Unit) {
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography

    var connected by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var frameTick by remember { mutableStateOf(0) }
    var fbSize by remember { mutableStateOf(IntSize.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(true) }
    var dragLock by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    val cursor = remember { floatArrayOf(-1f, -1f) }
    val bitmapHolder = remember { arrayOfNulls<Bitmap>(1) }
    val pixelHolder = remember { arrayOfNulls<IntArray>(1) }
    val clientHolder = remember { arrayOfNulls<RfbClient>(1) }
    val connection = remember { VncConnection() }
    val main = remember { Handler(Looper.getMainLooper()) }

    fun currentView(): ViewState = ViewTransform.view(
        fbSize.width, fbSize.height, boxSize.width, boxSize.height, zoom, panX, panY
    )

    fun ensureCursor() {
        if (cursor[0] < 0f && fbSize.width > 0) {
            cursor[0] = fbSize.width / 2f
            cursor[1] = fbSize.height / 2f
        }
    }

    fun sendCursor(mask: Int) {
        if (cursor[0] >= 0f) connection.pointer(mask, cursor[0].toInt(), cursor[1].toInt())
    }

    fun moveCursor(dx: Float, dy: Float) {
        if (fbSize.width <= 0) return
        ensureCursor()
        val s = currentView().scale.coerceAtLeast(0.1f)
        cursor[0] = (cursor[0] + dx / s * CURSOR_GAIN).coerceIn(0f, (fbSize.width - 1).toFloat())
        cursor[1] = (cursor[1] + dy / s * CURSOR_GAIN).coerceIn(0f, (fbSize.height - 1).toFloat())
        sendCursor(if (dragLock) VncKeys.BUTTON_LEFT else 0)
    }

    fun clickAtCursor(button: Int) {
        if (fbSize.width <= 0) return
        ensureCursor()
        sendCursor(0)
        sendCursor(button)
        sendCursor(if (dragLock) VncKeys.BUTTON_LEFT else 0)
    }

    fun wheelAtCursor(button: Int, times: Int = 1) {
        if (fbSize.width <= 0) return
        ensureCursor()
        repeat(times) {
            sendCursor(button)
            sendCursor(if (dragLock) VncKeys.BUTTON_LEFT else 0)
        }
    }

    DisposableEffect(Unit) {
        connection.start(object : VncConnection.Listener {
            override fun onConnected(client: RfbClient) {
                clientHolder[0] = client
                main.post { connected = true; failure = null }
            }

            override fun onFrame() {
                val client = clientHolder[0] ?: return
                val w = client.width
                val h = client.height
                if (w <= 0 || h <= 0) return
                var bmp = bitmapHolder[0]
                var buf = pixelHolder[0]
                if (bmp == null || buf == null || bmp.width != w || bmp.height != h) {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    buf = IntArray(w * h)
                    bitmapHolder[0] = bmp
                    pixelHolder[0] = buf
                }
                if (client.copyPixels(buf)) {
                    bmp.setPixels(buf, 0, w, 0, 0, w, h)
                    main.post { fbSize = IntSize(w, h); frameTick++ }
                }
            }

            override fun onClosed(reason: String?) {
                main.post {
                    connected = false
                    if (reason != null) failure = reason
                }
            }
        })
        onDispose { connection.close() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("desktop_viewer")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.desktop_viewer_title),
                    style = compactType.cardTitle,
                    color = tokens.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showKeys = !showKeys }, modifier = Modifier.testTag("desktop_toggle_keys")) {
                        Text("⌨", color = if (showKeys) tokens.accent else tokens.textSecondary)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("desktop_close")) {
                        Text(stringResource(R.string.desktop_close), color = tokens.accent)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var lastTime = down.uptimeMillis
                            var maxPointers = 1
                            var travel = 0f
                            var prevDist = 0f
                            var prevCx = 0f
                            var prevCy = 0f
                            var scrollAcc = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                lastTime = event.changes[0].uptimeMillis
                                if (pressed.size > maxPointers) maxPointers = pressed.size
                                if (pressed.size == 1 && maxPointers == 1) {
                                    val c = pressed[0]
                                    val d = c.position - c.previousPosition
                                    travel += abs(d.x) + abs(d.y)
                                    if (travel > MOVE_SLOP_PX) moveCursor(d.x, d.y)
                                    prevDist = 0f
                                } else if (pressed.size >= 2) {
                                    val a = pressed[0].position
                                    val b = pressed[1].position
                                    val dist = (a - b).getDistance()
                                    val cx = (a.x + b.x) / 2f
                                    val cy = (a.y + b.y) / 2f
                                    if (prevDist > 0f && fbSize.width > 0) {
                                        travel += abs(dist - prevDist) + abs(cx - prevCx) + abs(cy - prevCy)
                                        val ratio = dist / prevDist
                                        if (abs(ratio - 1f) > 0.004f) {
                                            val old = currentView()
                                            val newZoom = (zoom * ratio).coerceIn(ViewTransform.MIN_ZOOM, ViewTransform.MAX_ZOOM)
                                            val (px, py) = ViewTransform.panForZoom(
                                                fbSize.width, fbSize.height, boxSize.width, boxSize.height,
                                                old, newZoom, cx, cy
                                            )
                                            zoom = newZoom
                                            panX = px
                                            panY = py
                                        }
                                        if (zoom > 1.01f) {
                                            val (px, py) = ViewTransform.clampPan(
                                                fbSize.width, fbSize.height, boxSize.width, boxSize.height,
                                                zoom, panX + (cx - prevCx), panY + (cy - prevCy)
                                            )
                                            panX = px
                                            panY = py
                                        } else {
                                            scrollAcc += cy - prevCy
                                            while (abs(scrollAcc) > SCROLL_STEP_PX) {
                                                if (scrollAcc > 0f) {
                                                    wheelAtCursor(VncKeys.WHEEL_UP)
                                                    scrollAcc -= SCROLL_STEP_PX
                                                } else {
                                                    wheelAtCursor(VncKeys.WHEEL_DOWN)
                                                    scrollAcc += SCROLL_STEP_PX
                                                }
                                            }
                                        }
                                    }
                                    prevDist = dist
                                    prevCx = cx
                                    prevCy = cy
                                }
                                event.changes.forEach { it.consume() }
                            }
                            val duration = lastTime - down.uptimeMillis
                            if (duration < TAP_MAX_MS && travel < TAP_TRAVEL_PX) {
                                if (maxPointers == 1) clickAtCursor(VncKeys.BUTTON_LEFT)
                                else if (maxPointers == 2) clickAtCursor(VncKeys.BUTTON_RIGHT)
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_VARIABLE")
                    val tick = frameTick
                    val bmp = bitmapHolder[0]
                    if (bmp != null && fbSize.width > 0 && boxSize.width > 0) {
                        val v = currentView()
                        val dw = (fbSize.width * v.scale).toInt()
                        val dh = (fbSize.height * v.scale).toInt()
                        if (dw > 0 && dh > 0) {
                            drawImage(
                                image = bmp.asImageBitmap(),
                                dstOffset = IntOffset(v.ox.toInt(), v.oy.toInt()),
                                dstSize = IntSize(dw, dh)
                            )
                        }
                    }
                }
                if (!connected || fbSize.width == 0) {
                    val message = failure
                    Text(
                        text = if (message != null) {
                            stringResource(R.string.desktop_failed, message)
                        } else {
                            stringResource(R.string.desktop_connecting)
                        },
                        style = compactType.body,
                        color = if (message != null) tokens.error else tokens.textSecondary,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp)
                    )
                }
            }

            fun typeChar(codePoint: Int) {
                val ctrlSym = if (ctrlOn) VncKeys.ctrlLetter(codePoint) else 0
                val sym = if (ctrlSym != 0) ctrlSym else VncKeys.keysymForChar(codePoint)
                if (ctrlOn) connection.key(true, VncKeys.CONTROL_L)
                if (altOn) connection.key(true, VncKeys.ALT_L)
                connection.tapKey(sym)
                if (altOn) connection.key(false, VncKeys.ALT_L)
                if (ctrlOn) connection.key(false, VncKeys.CONTROL_L)
                ctrlOn = false
                altOn = false
            }

            if (showKeys) {
                BasicTextField(
                    value = "",
                    onValueChange = { text ->
                        var i = 0
                        while (i < text.length) {
                            val cp = text.codePointAt(i)
                            typeChar(cp)
                            i += Character.charCount(cp)
                        }
                    },
                    textStyle = compactType.body.copy(color = tokens.textPrimary),
                    cursorBrush = SolidColor(tokens.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .border(1.dp, tokens.accentMuted)
                        .padding(8.dp)
                        .testTag("desktop_input")
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val sym = when (event.key) {
                                Key.Backspace -> VncKeys.BACKSPACE
                                Key.Enter -> VncKeys.RETURN
                                Key.DirectionLeft -> VncKeys.LEFT
                                Key.DirectionRight -> VncKeys.RIGHT
                                Key.DirectionUp -> VncKeys.UP
                                Key.DirectionDown -> VncKeys.DOWN
                                else -> 0
                            }
                            if (sym == 0) {
                                false
                            } else {
                                connection.tapKey(sym)
                                true
                            }
                        },
                    decorationBox = { inner ->
                        Box {
                            Text(
                                text = stringResource(R.string.desktop_type_hint),
                                style = compactType.body,
                                color = tokens.textSecondary
                            )
                            inner()
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    KeyButton(stringResource(R.string.desktop_click)) { clickAtCursor(VncKeys.BUTTON_LEFT) }
                    KeyButton(stringResource(R.string.desktop_right_click)) { clickAtCursor(VncKeys.BUTTON_RIGHT) }
                    KeyButton(stringResource(R.string.desktop_drag_lock), selected = dragLock) {
                        dragLock = !dragLock
                        ensureCursor()
                        sendCursor(if (dragLock) VncKeys.BUTTON_LEFT else 0)
                    }
                    KeyButton("1×") { zoom = 1f; panX = 0f; panY = 0f }
                    KeyButton(stringResource(R.string.desktop_key_esc)) { connection.tapKey(VncKeys.ESCAPE) }
                    KeyButton(stringResource(R.string.desktop_key_tab)) { connection.tapKey(VncKeys.TAB) }
                    KeyButton(stringResource(R.string.desktop_key_ctrl), selected = ctrlOn) { ctrlOn = !ctrlOn }
                    KeyButton(stringResource(R.string.desktop_key_alt), selected = altOn) { altOn = !altOn }
                    KeyButton("⌫") { connection.tapKey(VncKeys.BACKSPACE) }
                    KeyButton("⏎") { connection.tapKey(VncKeys.RETURN) }
                    KeyButton("↑") { connection.tapKey(VncKeys.UP) }
                    KeyButton("↓") { connection.tapKey(VncKeys.DOWN) }
                    KeyButton(stringResource(R.string.desktop_scroll_up)) { wheelAtCursor(VncKeys.WHEEL_UP, 3) }
                    KeyButton(stringResource(R.string.desktop_scroll_down)) { wheelAtCursor(VncKeys.WHEEL_DOWN, 3) }
                }
                Text(
                    text = stringResource(R.string.desktop_hint),
                    style = compactType.body,
                    color = tokens.textSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun KeyButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val tokens = StudioTheme.tokens
    TextButton(onClick = onClick) {
        Text(label, color = if (selected) tokens.accent else tokens.textPrimary)
    }
}
