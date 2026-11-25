package com.example.ui.desktop

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
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

/** Full-screen viewer for the local Ubuntu desktop (VNC on localhost:5901). */
@Composable
fun DesktopViewerDialog(onDismiss: () -> Unit) {
    val tokens = StudioTheme.tokens
    val compactType = StudioTheme.compactTypography

    var connected by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var frameTick by remember { mutableStateOf(0) }
    var fbSize by remember { mutableStateOf(IntSize.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var ctrlOn by remember { mutableStateOf(false) }
    var altOn by remember { mutableStateOf(false) }
    val bitmapHolder = remember { arrayOfNulls<Bitmap>(1) }
    val pixelHolder = remember { arrayOfNulls<IntArray>(1) }
    val connection = remember { VncConnection() }
    val main = remember { Handler(Looper.getMainLooper()) }

    DisposableEffect(Unit) {
        connection.start(object : VncConnection.Listener {
            override fun onConnected(client: RfbClient) {
                main.post { connected = true; error = null }
            }

            override fun onFrame() {
                val client = lastClient ?: return
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
                    bmp!!.setPixels(buf, 0, w, 0, 0, w, h)
                    main.post { fbSize = IntSize(w, h); frameTick++ }
                }
            }

            override fun onClosed(error: String?) {
                if (error != null) main.post { connected = false; this@DisposableEffect.run { } }
                if (error != null) main.post { errorHolder(error) }
            }

            private var lastClient: RfbClient? = null
            private fun errorHolder(message: String) {
                // set from the main thread
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
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("desktop_close")) {
                    Text(stringResource(R.string.desktop_close), color = tokens.accent)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { p -> click(connection, fbSize, boxSize, p, VncKeys.BUTTON_LEFT) },
                            onLongPress = { p -> click(connection, fbSize, boxSize, p, VncKeys.BUTTON_RIGHT) }
                        )
                    }
                    .pointerInput(Unit) {
                        var last = Offset.Zero
                        detectDragGestures(
                            onDragStart = { p ->
                                last = p
                                val (x, y) = toFramebuffer(fbSize, boxSize, p)
                                connection.pointer(0, x, y)
                                connection.pointer(VncKeys.BUTTON_LEFT, x, y)
                            },
                            onDrag = { change, _ ->
                                last = change.position
                                val (x, y) = toFramebuffer(fbSize, boxSize, change.position)
                                connection.pointer(VncKeys.BUTTON_LEFT, x, y)
                            },
                            onDragEnd = {
                                val (x, y) = toFramebuffer(fbSize, boxSize, last)
                                connection.pointer(0, x, y)
                            },
                            onDragCancel = {
                                val (x, y) = toFramebuffer(fbSize, boxSize, last)
                                connection.pointer(0, x, y)
                            }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_VARIABLE")
                    val tick = frameTick
                    val bmp = bitmapHolder[0]
                    if (bmp != null && fbSize.width > 0) {
                        val scale = minOf(size.width / fbSize.width, size.height / fbSize.height)
                        val dw = (fbSize.width * scale).toInt()
                        val dh = (fbSize.height * scale).toInt()
                        drawImage(
                            image = bmp.asImageBitmap(),
                            dstOffset = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                            dstSize = IntSize(dw, dh)
                        )
                    }
                }
                if (!connected || fbSize.width == 0) {
                    Text(
                        text = error?.let { stringResource(R.string.desktop_failed, it) }
                            ?: stringResource(R.string.desktop_connecting),
                        style = compactType.body,
                        color = if (error != null) tokens.error else tokens.textSecondary,
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
                        if (sym == 0) false else { connection.tapKey(sym); true }
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
                KeyButton(stringResource(R.string.desktop_key_esc)) { connection.tapKey(VncKeys.ESCAPE) }
                KeyButton(stringResource(R.string.desktop_key_tab)) { connection.tapKey(VncKeys.TAB) }
                KeyButton(stringResource(R.string.desktop_key_ctrl), selected = ctrlOn) { ctrlOn = !ctrlOn }
                KeyButton(stringResource(R.string.desktop_key_alt), selected = altOn) { altOn = !altOn }
                KeyButton("⌫") { connection.tapKey(VncKeys.BACKSPACE) }
                KeyButton("⏎") { connection.tapKey(VncKeys.RETURN) }
                KeyButton("↑") { connection.tapKey(VncKeys.UP) }
                KeyButton("↓") { connection.tapKey(VncKeys.DOWN) }
                KeyButton(stringResource(R.string.desktop_scroll_up)) { wheel(connection, fbSize, VncKeys.WHEEL_UP) }
                KeyButton(stringResource(R.string.desktop_scroll_down)) { wheel(connection, fbSize, VncKeys.WHEEL_DOWN) }
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

@Composable
private fun KeyButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val tokens = StudioTheme.tokens
    TextButton(onClick = onClick) {
        Text(label, color = if (selected) tokens.accent else tokens.textPrimary)
    }
}

private fun toFramebuffer(fb: IntSize, box: IntSize, p: Offset): Pair<Int, Int> {
    if (fb.width <= 0 || fb.height <= 0 || box.width <= 0 || box.height <= 0) return 0 to 0
    val scale = minOf(box.width.toFloat() / fb.width, box.height.toFloat() / fb.height)
    val ox = (box.width - fb.width * scale) / 2f
    val oy = (box.height - fb.height * scale) / 2f
    val x = ((p.x - ox) / scale).toInt().coerceIn(0, fb.width - 1)
    val y = ((p.y - oy) / scale).toInt().coerceIn(0, fb.height - 1)
    return x to y
}

private fun click(connection: VncConnection, fb: IntSize, box: IntSize, p: Offset, button: Int) {
    val (x, y) = toFramebuffer(fb, box, p)
    connection.pointer(0, x, y)
    connection.pointer(button, x, y)
    connection.pointer(0, x, y)
}

private fun wheel(connection: VncConnection, fb: IntSize, button: Int) {
    val x = fb.width / 2
    val y = fb.height / 2
    repeat(3) {
        connection.pointer(button, x, y)
        connection.pointer(0, x, y)
    }
}
