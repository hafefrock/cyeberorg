package com.example.ui.terminal

import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.TerminalFontConfig
import com.example.terminal.DetectedTerminalToken
import com.example.terminal.ExtraKeyCode
import com.example.terminal.FakeTerminalBackend
import com.example.terminal.TerminalCell
import com.example.terminal.TerminalErrorContext
import com.example.terminal.TerminalFontRegistry
import com.example.terminal.TerminalGridPoint
import com.example.terminal.TerminalGridSelection
import com.example.terminal.TerminalOutputExporter
import com.example.terminal.TerminalScreenBuffer
import com.example.terminal.TerminalTokenDetector
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioElevation
import com.example.ui.theme.StudioSpacing
import com.example.ui.theme.StudioTheme
import com.example.ui.theme.TerminalColorScheme
import com.example.ui.theme.TerminalCursorStyle
import com.example.ui.theme.chamferInset
import com.example.ui.theme.safeContentPadding
import com.example.ui.theme.studioTouchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private const val RESIZE_DEBOUNCE_MS = 100L
private const val ZOOM_HUD_HIDE_DELAY_MS = 800L

internal data class TerminalStyleRun(
    val startCol: Int,
    val length: Int,
    val fgColorCode: Int,
    val bgColorCode: Int,
    val bold: Boolean,
    val text: String
)

internal inline fun forEachTerminalStyleRun(
    rowCells: List<TerminalCell>,
    maxCols: Int,
    charBuffer: CharArray,
    onRun: (
        startCol: Int,
        length: Int,
        fgColorCode: Int,
        bgColorCode: Int,
        bold: Boolean,
        hasVisibleGlyph: Boolean
    ) -> Unit
) {
    val limit = minOf(rowCells.size, maxCols, charBuffer.size)
    if (limit <= 0) return

    var runStart = 0
    val firstCell = rowCells[0]
    var runFg = firstCell.fgColorCode
    var runBg = firstCell.bgColorCode
    var runBold = firstCell.bold
    var hasVisible = firstCell.char != ' '
    charBuffer[0] = firstCell.char

    for (col in 1 until limit) {
        val cell = rowCells[col]
        if (cell.fgColorCode == runFg && cell.bgColorCode == runBg && cell.bold == runBold) {
            charBuffer[col - runStart] = cell.char
            if (cell.char != ' ') hasVisible = true
        } else {
            onRun(runStart, col - runStart, runFg, runBg, runBold, hasVisible)
            runStart = col
            runFg = cell.fgColorCode
            runBg = cell.bgColorCode
            runBold = cell.bold
            hasVisible = cell.char != ' '
            charBuffer[0] = cell.char
        }
    }
    onRun(runStart, limit - runStart, runFg, runBg, runBold, hasVisible)
}

internal fun groupTerminalRowRuns(
    rowCells: List<TerminalCell>,
    maxCols: Int = rowCells.size
): List<TerminalStyleRun> {
    val limit = minOf(rowCells.size, maxCols)
    if (limit <= 0) return emptyList()
    val buffer = CharArray(limit)
    val runs = ArrayList<TerminalStyleRun>(4)
    forEachTerminalStyleRun(rowCells, limit, buffer) { startCol, length, fg, bg, bold, _ ->
        runs.add(
            TerminalStyleRun(
                startCol = startCol,
                length = length,
                fgColorCode = fg,
                bgColorCode = bg,
                bold = bold,
                text = String(buffer, 0, length)
            )
        )
    }
    return runs
}

/**
 * Cell-grid PTY terminal surface collecting an isolated [TerminalScreenSnapshot] [StateFlow]
 * so terminal screen updates never recompose outer workspace chrome.
 */
@Composable
fun TerminalConsoleView(
    sessionTitle: String,
    cwd: String,
    lastExitCode: Int?,
    lastError: TerminalErrorContext?,
    screenFlow: StateFlow<TerminalScreenSnapshot>,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    fontSizeSp: Float,
    colorScheme: TerminalColorScheme,
    aiEnabled: Boolean,
    terminalFontId: String = TerminalFontRegistry.DEFAULT_FONT_ID,
    isFocusedPane: Boolean = true,
    isAiPanelOpen: Boolean = false,
    onPaneFocused: () -> Unit = {},
    onResize: (cols: Int, rows: Int) -> Unit,
    onImeTextInput: (String) -> Unit,
    onImeBackspace: () -> Unit,
    onImeEnter: () -> Unit,
    onSendRawBytes: (ByteArray) -> Unit,
    onClearOutput: () -> Unit,
    onExplainLastError: () -> Unit,
    onFixLastError: () -> Unit = onExplainLastError,
    onCopySelection: (String) -> Unit = {},
    onAskAiWithSelection: (String) -> Unit = {},
    onExplainSelection: (String) -> Unit = {},
    onSummarizeSelection: (String) -> Unit = {},
    onFindRootCauseSelection: (String) -> Unit = {},
    onFontSizeChange: (Float) -> Unit = {},
    onInsertToken: (String) -> Unit = {},
    onShowBanner: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val screenSnapshot by screenFlow.collectAsStateWithLifecycle()
    TerminalConsoleView(
        sessionTitle = sessionTitle,
        cwd = cwd,
        lastExitCode = lastExitCode,
        lastError = lastError,
        screenBuffer = screenSnapshot.buffer,
        screenVersion = screenSnapshot.version,
        ctrlArmed = ctrlArmed,
        altArmed = altArmed,
        fontSizeSp = fontSizeSp,
        colorScheme = colorScheme,
        aiEnabled = aiEnabled,
        terminalFontId = terminalFontId,
        isFocusedPane = isFocusedPane,
        isAiPanelOpen = isAiPanelOpen,
        onPaneFocused = onPaneFocused,
        onResize = onResize,
        onImeTextInput = onImeTextInput,
        onImeBackspace = onImeBackspace,
        onImeEnter = onImeEnter,
        onSendRawBytes = onSendRawBytes,
        onClearOutput = onClearOutput,
        onExplainLastError = onExplainLastError,
        onFixLastError = onFixLastError,
        onCopySelection = onCopySelection,
        onAskAiWithSelection = onAskAiWithSelection,
        onExplainSelection = onExplainSelection,
        onSummarizeSelection = onSummarizeSelection,
        onFindRootCauseSelection = onFindRootCauseSelection,
        onFontSizeChange = onFontSizeChange,
        onInsertToken = onInsertToken,
        onShowBanner = onShowBanner,
        modifier = modifier
    )
}

/**
 * Cell-grid PTY terminal surface using selectable [TerminalColorScheme] palettes,
 * dismissible inline Fix-with-AI error chip, and long-press grid selection toolbar.
 */
@Composable
fun TerminalConsoleView(
    sessionTitle: String,
    cwd: String,
    lastExitCode: Int?,
    lastError: TerminalErrorContext?,
    screenBuffer: TerminalScreenBuffer?,
    screenVersion: Long,
    ctrlArmed: Boolean,
    altArmed: Boolean,
    fontSizeSp: Float,
    colorScheme: TerminalColorScheme,
    aiEnabled: Boolean,
    terminalFontId: String = TerminalFontRegistry.DEFAULT_FONT_ID,
    isFocusedPane: Boolean = true,
    isAiPanelOpen: Boolean = false,
    onPaneFocused: () -> Unit = {},
    onResize: (cols: Int, rows: Int) -> Unit,
    onImeTextInput: (String) -> Unit,
    onImeBackspace: () -> Unit,
    onImeEnter: () -> Unit,
    onSendRawBytes: (ByteArray) -> Unit,
    onClearOutput: () -> Unit,
    onExplainLastError: () -> Unit,
    onFixLastError: () -> Unit = onExplainLastError,
    onCopySelection: (String) -> Unit = {},
    onAskAiWithSelection: (String) -> Unit = {},
    onExplainSelection: (String) -> Unit = {},
    onSummarizeSelection: (String) -> Unit = {},
    onFindRootCauseSelection: (String) -> Unit = {},
    onFontSizeChange: (Float) -> Unit = {},
    onInsertToken: (String) -> Unit = {},
    onShowBanner: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    var terminalImeFocusAllowed by remember { mutableStateOf(false) }
    var pendingImeFocusSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(isAiPanelOpen) {
        if (isAiPanelOpen) {
            terminalImeFocusAllowed = false
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        } else {
            terminalImeFocusAllowed = false
        }
    }

    var scrollbackOffset by remember { mutableIntStateOf(0) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    var gridSelection by remember { mutableStateOf<TerminalGridSelection?>(null) }
    var dismissedErrorToken by remember { mutableStateOf<String?>(null) }
    var selectionStartedThisGesture by remember { mutableStateOf(false) }

    val persistedFontSizeSp = TerminalFontConfig.normalizeFontSizeSp(fontSizeSp)
    var activePreviewFontSizeSp by remember { mutableStateOf<Float?>(null) }
    var pendingCommitFontSizeSp by remember { mutableStateOf<Float?>(null) }
    var isPinchInProgress by remember { mutableStateOf(false) }
    var showZoomHud by remember { mutableStateOf(false) }
    var zoomHudInteractionTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(persistedFontSizeSp) {
        if (!isPinchInProgress) {
            activePreviewFontSizeSp = null
            pendingCommitFontSizeSp = null
        }
    }

    LaunchedEffect(zoomHudInteractionTick, isPinchInProgress) {
        if (showZoomHud && !isPinchInProgress) {
            delay(ZOOM_HUD_HIDE_DELAY_MS)
            showZoomHud = false
        }
    }

    val effectiveFontSizeSp = TerminalFontConfig.normalizeFontSizeSp(
        activePreviewFontSizeSp ?: pendingCommitFontSizeSp ?: persistedFontSizeSp
    )

    LaunchedEffect(screenVersion) {
        scrollbackOffset = 0
    }

    val errorToken = remember(lastError, lastExitCode) {
        if ((lastExitCode ?: 0) != 0 && lastError != null) {
            "${lastError.sessionId}:${lastError.exitCode}:${lastError.command}:${lastError.errorOutput.hashCode()}"
        } else {
            null
        }
    }

    var pressedToken by remember { mutableStateOf<DetectedTerminalToken?>(null) }
    var pressedTokenAbsRow by remember { mutableIntStateOf(-1) }
    var tappedToken by remember { mutableStateOf<DetectedTerminalToken?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }
    var shareSourceSelection by remember { mutableStateOf<TerminalGridSelection?>(null) }

    val monoTypeface = remember(context, terminalFontId) {
        TerminalFontRegistry.resolveTypeface(terminalFontId, context)
    }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG)
    }
    val fontSizePx = with(density) { effectiveFontSizeSp.sp.toPx() }
    val cellMetrics = remember(fontSizePx, monoTypeface) {
        TerminalFontRegistry.computeCellMetrics(
            textPaint = textPaint,
            fontSizePx = fontSizePx,
            typeface = monoTypeface
        )
    }
    val runCharBuffer = remember(screenBuffer?.cols ?: 256) {
        CharArray(maxOf(256, screenBuffer?.cols ?: 256))
    }

    val latestBuffer by rememberUpdatedState(screenBuffer)
    val latestCellMetrics by rememberUpdatedState(cellMetrics)
    val latestScrollbackOffset by rememberUpdatedState(scrollbackOffset)
    val latestOnPaneFocused by rememberUpdatedState(onPaneFocused)
    val latestPersistedFontSizeSp by rememberUpdatedState(persistedFontSizeSp)
    val latestEffectiveFontSizeSp by rememberUpdatedState(effectiveFontSizeSp)
    val latestOnFontSizeChange by rememberUpdatedState(onFontSizeChange)

    fun resolveTokenAtOffset(offset: Offset): Pair<Int, DetectedTerminalToken>? {
        val buf = latestBuffer ?: return null
        val totalRows = buf.totalRowCount
        if (totalRows <= 0) return null
        val metrics = latestCellMetrics
        val sbOffset = latestScrollbackOffset
        val endIdx = (totalRows - sbOffset).coerceIn(0, totalRows)
        val startIdx = if (sbOffset > 0) {
            (endIdx - buf.rows).coerceAtLeast(0)
        } else {
            buf.scrollbackRows.size
        }
        val visRow = (offset.y / metrics.cellHeightPx).toInt()
        val absRow = startIdx + visRow
        if (absRow !in startIdx until endIdx) return null
        val col = (offset.x / metrics.cellWidthPx).toInt()
        if (col !in 0 until buf.cols) return null
        val rowText = TerminalTokenDetector.rowToPlainText(buf.getRow(absRow))
        val token = TerminalTokenDetector.findTokenAt(rowText, col) ?: return null
        return absRow to token
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .padding(StudioSpacing.xs),
        color = colorScheme.background,
        shape = tokens.cardShape,
        border = if (isFocusedPane) {
            BorderStroke(tokens.borderWidth, tokens.focusRingColor.copy(alpha = 0.85f))
        } else {
            BorderStroke(tokens.borderWidth, tokens.thinBorderBrush)
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TerminalStatusStrip(
                cwd = cwd,
                cols = screenBuffer?.cols ?: 80,
                rows = screenBuffer?.rows ?: 24,
                onFocusKeyboard = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPaneFocused()
                    terminalImeFocusAllowed = true
                    pendingImeFocusSeq++
                },
                onShareOutput = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    shareSourceSelection = null
                    showShareSheet = true
                },
                onClearOutput = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    gridSelection = null
                    onClearOutput()
                }
            )

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = StudioSpacing.sm, vertical = StudioSpacing.sm)
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            var zoom = 1f
                            var pastTouchSlop = false
                            var gestureBaseFontSp = latestEffectiveFontSizeSp
                            var pinchStarted = false

                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial
                            )
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressedCount = event.changes.count { it.pressed }
                                val anyConsumed = event.changes.any { it.isConsumed }

                                if (pressedCount >= 2 && !anyConsumed) {
                                    if (!pinchStarted) {
                                        pinchStarted = true
                                        isPinchInProgress = true
                                        pressedToken = null
                                        pressedTokenAbsRow = -1
                                        dragAccumulator = 0f
                                        gestureBaseFontSp = activePreviewFontSizeSp ?: latestEffectiveFontSizeSp
                                    }
                                    val zoomChange = event.calculateZoom()
                                    val centroidSpan = event.calculateCentroidSize(useCurrent = true)
                                    val prevSpan = event.calculateCentroidSize(useCurrent = false)
                                    val spanDelta = kotlin.math.abs(centroidSpan - prevSpan)
                                    zoom *= zoomChange

                                    if (!pastTouchSlop) {
                                        val deltaSpanFromZoom = kotlin.math.abs(1f - zoom) * centroidSpan
                                        if (deltaSpanFromZoom > touchSlop ||
                                            spanDelta > touchSlop ||
                                            kotlin.math.abs(zoom - 1f) >= TerminalFontConfig.PINCH_SCALE_DEADBAND
                                        ) {
                                            pastTouchSlop = true
                                        }
                                    }

                                    if (pastTouchSlop && zoomChange != 1f) {
                                        val nextSp = TerminalFontConfig.calculatePinchFontSizeSp(
                                            currentSizeSp = gestureBaseFontSp,
                                            zoomScale = zoom
                                        )
                                        if (nextSp != (activePreviewFontSizeSp ?: gestureBaseFontSp)) {
                                            activePreviewFontSizeSp = nextSp
                                        }
                                        showZoomHud = true
                                        zoomHudInteractionTick++
                                    }

                                    event.changes.forEach { it.consume() }
                                } else if (pinchStarted) {
                                    if (pressedCount == 0) {
                                        break
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })

                            if (pinchStarted) {
                                isPinchInProgress = false
                                val committedSp = activePreviewFontSizeSp
                                if (committedSp != null &&
                                    kotlin.math.abs(committedSp - latestPersistedFontSizeSp) >= 0.01f
                                ) {
                                    pendingCommitFontSizeSp = committedSp
                                    latestOnFontSizeChange(committedSp)
                                }
                                if (showZoomHud) {
                                    zoomHudInteractionTick++
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { dragAccumulator = 0f },
                            onDragCancel = { dragAccumulator = 0f }
                        ) { change, dragAmount ->
                            if (isPinchInProgress) {
                                dragAccumulator = 0f
                                return@detectVerticalDragGestures
                            }
                            pressedToken = null
                            pressedTokenAbsRow = -1
                            change.consume()
                            val maxScroll = latestBuffer?.scrollbackRows?.size ?: 0
                            dragAccumulator += dragAmount
                            val rowHeight = latestCellMetrics.cellHeightPx
                            val deltaRows = (dragAccumulator / rowHeight).toInt()
                            if (deltaRows != 0) {
                                dragAccumulator -= deltaRows * rowHeight
                                scrollbackOffset = (scrollbackOffset + deltaRows).coerceIn(0, maxScroll)
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { offset ->
                                if (isPinchInProgress) {
                                    return@detectDragGesturesAfterLongPress
                                }
                                pressedToken = null
                                pressedTokenAbsRow = -1
                                val buf = latestBuffer ?: return@detectDragGesturesAfterLongPress
                                val totalRows = buf.totalRowCount
                                if (totalRows <= 0) return@detectDragGesturesAfterLongPress
                                val metrics = latestCellMetrics
                                val sbOffset = latestScrollbackOffset
                                val endIdx = (totalRows - sbOffset).coerceIn(0, totalRows)
                                val startIdx = if (sbOffset > 0) {
                                    (endIdx - buf.rows).coerceAtLeast(0)
                                } else {
                                    buf.scrollbackRows.size
                                }
                                val visRow = (offset.y / metrics.cellHeightPx).toInt().coerceIn(0, (buf.rows - 1).coerceAtLeast(0))
                                val absRow = (startIdx + visRow).coerceIn(0, totalRows - 1)
                                val col = (offset.x / metrics.cellWidthPx).toInt().coerceIn(0, (buf.cols - 1).coerceAtLeast(0))
                                val rowCells = buf.getRow(absRow)
                                val (wordStartCol, wordEndCol) = findWordBounds(rowCells, col)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                latestOnPaneFocused()
                                selectionStartedThisGesture = true
                                gridSelection = TerminalGridSelection(
                                    anchor = TerminalGridPoint(absRow, wordStartCol),
                                    focus = TerminalGridPoint(absRow, wordEndCol)
                                )
                            },
                            onDragEnd = { selectionStartedThisGesture = false },
                            onDragCancel = { selectionStartedThisGesture = false },
                            onDrag = { change, _ ->
                                if (isPinchInProgress) return@detectDragGesturesAfterLongPress
                                change.consume()
                                val buf = latestBuffer ?: return@detectDragGesturesAfterLongPress
                                val totalRows = buf.totalRowCount
                                if (totalRows <= 0) return@detectDragGesturesAfterLongPress
                                val metrics = latestCellMetrics
                                val sbOffset = latestScrollbackOffset
                                val endIdx = (totalRows - sbOffset).coerceIn(0, totalRows)
                                val startIdx = if (sbOffset > 0) {
                                    (endIdx - buf.rows).coerceAtLeast(0)
                                } else {
                                    buf.scrollbackRows.size
                                }
                                val visRow = (change.position.y / metrics.cellHeightPx).toInt().coerceIn(0, (buf.rows - 1).coerceAtLeast(0))
                                val absRow = (startIdx + visRow).coerceIn(0, totalRows - 1)
                                val col = (change.position.x / metrics.cellWidthPx).toInt().coerceIn(0, (buf.cols - 1).coerceAtLeast(0))
                                gridSelection = gridSelection?.copy(
                                    focus = TerminalGridPoint(absRow, col)
                                )
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                val hadSelectionAtDown = gridSelection != null
                                if (!isPinchInProgress && !hadSelectionAtDown) {
                                    val hit = resolveTokenAtOffset(offset)
                                    if (hit != null) {
                                        pressedTokenAbsRow = hit.first
                                        pressedToken = hit.second
                                    }
                                }
                                val released = try {
                                    tryAwaitRelease()
                                } finally {
                                    pressedToken = null
                                    pressedTokenAbsRow = -1
                                }
                                if (released &&
                                    !isPinchInProgress &&
                                    !hadSelectionAtDown &&
                                    !selectionStartedThisGesture &&
                                    resolveTokenAtOffset(offset) == null
                                ) {
                                    latestOnPaneFocused()
                                    terminalImeFocusAllowed = true
                                    pendingImeFocusSeq++
                                }
                            },
                            onDoubleTap = {
                                if (isPinchInProgress) return@detectTapGestures
                                pressedToken = null
                                pressedTokenAbsRow = -1
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val defaultSp = TerminalFontConfig.DEFAULT_FONT_SIZE_SP
                                activePreviewFontSizeSp = null
                                pendingCommitFontSizeSp = defaultSp
                                latestOnFontSizeChange(defaultSp)
                                showZoomHud = true
                                zoomHudInteractionTick++
                            },
                            onTap = { offset ->
                                if (isPinchInProgress) return@detectTapGestures
                                pressedToken = null
                                pressedTokenAbsRow = -1
                                if (selectionStartedThisGesture) {
                                    selectionStartedThisGesture = false
                                    return@detectTapGestures
                                }
                                if (gridSelection != null) {
                                    gridSelection = null
                                } else {
                                    val hit = resolveTokenAtOffset(offset)
                                    if (hit != null) {
                                        latestOnPaneFocused()
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        tappedToken = hit.second
                                    }
                                }
                            }
                        )
                    }
                    .semantics {
                        onClick {
                            if (gridSelection != null) {
                                gridSelection = null
                            } else {
                                latestOnPaneFocused()
                                terminalImeFocusAllowed = true
                                pendingImeFocusSeq++
                            }
                            true
                        }
                    }
                    .testTag("terminal_cell_grid")
            ) {
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val cellW = cellMetrics.cellWidthPx
                val cellH = cellMetrics.cellHeightPx
                val baselineOffset = cellMetrics.baselineOffsetPx

                val computedCols = (widthPx / cellW).toInt().coerceIn(20, 200)
                val computedRows = (heightPx / cellH).toInt().coerceIn(6, 100)

                val hasValidBounds = widthPx > cellW && heightPx > cellH
                var lastDispatchedCols by remember { mutableIntStateOf(-1) }
                var lastDispatchedRows by remember { mutableIntStateOf(-1) }
                LaunchedEffect(computedCols, computedRows, hasValidBounds, isPinchInProgress) {
                    if (!hasValidBounds) return@LaunchedEffect
                    if (computedCols == lastDispatchedCols && computedRows == lastDispatchedRows) {
                        return@LaunchedEffect
                    }
                    if (lastDispatchedCols >= 0 && isPinchInProgress) {
                        delay(RESIZE_DEBOUNCE_MS)
                    }
                    lastDispatchedCols = computedCols
                    lastDispatchedRows = computedRows
                    onResize(computedCols, computedRows)
                }

                var sentinel by remember {
                    mutableStateOf(TextFieldValue(text = SENTINEL, selection = TextRange(1)))
                }

                BasicTextField(
                    value = sentinel,
                    onValueChange = { next ->
                        val newText = next.text
                        when {
                            newText.isEmpty() -> onImeBackspace()
                            newText.length > 1 -> {
                                val typed = if (newText.startsWith(SENTINEL)) {
                                    newText.substring(1)
                                } else {
                                    newText.replace(SENTINEL, "")
                                }
                                if (typed.contains('\n')) {
                                    val parts = typed.split('\n')
                                    parts.forEachIndexed { idx, part ->
                                        if (part.isNotEmpty()) onImeTextInput(part)
                                        if (idx < parts.lastIndex) onImeEnter()
                                    }
                                } else if (typed.isNotEmpty()) {
                                    onImeTextInput(typed)
                                }
                            }
                        }
                        sentinel = TextFieldValue(text = SENTINEL, selection = TextRange(1))
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { onImeEnter() }),
                    modifier = Modifier
                        .size(dimens.hairlineWidth)
                        .alpha(0f)
                        .focusProperties { canFocus = !isAiPanelOpen && terminalImeFocusAllowed }
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                event.key == Key.Enter -> { onImeEnter(); true }
                                event.key == Key.Backspace -> { onImeBackspace(); true }
                                event.key == Key.Escape -> { ExtraKeyCode.ESC.toByteSequence()?.let(onSendRawBytes); true }
                                event.key == Key.Tab -> { ExtraKeyCode.TAB.toByteSequence()?.let(onSendRawBytes); true }
                                event.key == Key.DirectionUp -> { ExtraKeyCode.ARROW_UP.toByteSequence()?.let(onSendRawBytes); true }
                                event.key == Key.DirectionDown -> { ExtraKeyCode.ARROW_DOWN.toByteSequence()?.let(onSendRawBytes); true }
                                event.key == Key.DirectionLeft -> { ExtraKeyCode.ARROW_LEFT.toByteSequence()?.let(onSendRawBytes); true }
                                event.key == Key.DirectionRight -> { ExtraKeyCode.ARROW_RIGHT.toByteSequence()?.let(onSendRawBytes); true }
                                event.isCtrlPressed && event.key == Key.C -> { onSendRawBytes(byteArrayOf(0x03)); true }
                                event.isCtrlPressed && event.key == Key.L -> { onSendRawBytes(byteArrayOf(0x0C)); true }
                                else -> false
                            }
                        }
                        .testTag("terminal_ime_input")
                )

                LaunchedEffect(pendingImeFocusSeq) {
                    if (pendingImeFocusSeq > 0 && terminalImeFocusAllowed && !isAiPanelOpen) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                }

                val selectionHighlightColor = tokens.accent.copy(alpha = 0.36f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val buf = screenBuffer ?: return@Canvas
                    if (size.width <= 1f || size.height <= 1f) return@Canvas

                    val totalRows = buf.totalRowCount
                    if (totalRows <= 0) return@Canvas
                    val endIndex = (totalRows - scrollbackOffset).coerceIn(0, totalRows)
                    val startIndex = if (scrollbackOffset > 0) {
                        (endIndex - buf.rows).coerceAtLeast(0)
                    } else {
                        buf.scrollbackRows.size
                    }
                    if (startIndex >= endIndex) return@Canvas

                    val maxVisibleCols = minOf(
                        buf.cols,
                        runCharBuffer.size,
                        ((size.width / cellW).toInt() + 1).coerceAtLeast(1)
                    )
                    val sel = gridSelection
                    val selStartRow = sel?.start?.row ?: -1
                    val selEndRow = sel?.end?.row ?: -1
                    val selStartCol = sel?.start?.col ?: 0
                    val selEndCol = sel?.end?.col ?: 0

                    textPaint.typeface = monoTypeface
                    textPaint.textSize = fontSizePx

                    clipRect {
                        var visIdx = 0
                        for (absRowIdx in startIndex until endIndex) {
                            val topY = visIdx * cellH
                            if (topY >= size.height) break
                            val rowCells = buf.getRow(absRowIdx)
                            val rowCols = minOf(rowCells.size, maxVisibleCols)

                            // Pass 1: Draw cell backgrounds per style run + selection span in 1 rect per row
                            forEachTerminalStyleRun(rowCells, rowCols, runCharBuffer) { startCol, length, _, bgCode, _, _ ->
                                if (bgCode != TerminalCell.DEFAULT_BG) {
                                    val leftX = startCol * cellW
                                    if (leftX < size.width) {
                                        drawRect(
                                            color = colorScheme.resolveAnsiColor(bgCode),
                                            topLeft = Offset(leftX, topY),
                                            size = Size(length * cellW + 0.5f, cellH + 0.5f)
                                        )
                                    }
                                }
                            }

                            if (sel != null && absRowIdx in selStartRow..selEndRow && rowCols > 0) {
                                val sCol = if (absRowIdx == selStartRow) selStartCol.coerceIn(0, rowCols - 1) else 0
                                val eCol = if (absRowIdx == selEndRow) selEndCol.coerceIn(0, rowCols - 1) else rowCols - 1
                                if (sCol <= eCol) {
                                    drawRect(
                                        color = selectionHighlightColor,
                                        topLeft = Offset(sCol * cellW, topY),
                                        size = Size((eCol - sCol + 1) * cellW + 0.5f, cellH + 0.5f)
                                    )
                                }
                            }
                            visIdx++
                        }

                        // Pass 2: Draw foreground text runs once per style run using reused android.graphics.Paint
                        drawIntoCanvas { canvas ->
                            val nativeCanvas = canvas.nativeCanvas
                            var rowCursor = 0
                            for (absRowIdx in startIndex until endIndex) {
                                val topY = rowCursor * cellH
                                if (topY >= size.height) break
                                val baselineY = topY + baselineOffset
                                val rowCells = buf.getRow(absRowIdx)
                                val rowCols = minOf(rowCells.size, maxVisibleCols)

                                forEachTerminalStyleRun(rowCells, rowCols, runCharBuffer) { startCol, length, fgCode, _, bold, hasVisibleGlyph ->
                                    if (hasVisibleGlyph) {
                                        val leftX = startCol * cellW
                                        if (leftX < size.width) {
                                            val fgArgb = colorScheme.resolveAnsiColor(fgCode).toArgb()
                                            if (textPaint.color != fgArgb) {
                                                textPaint.color = fgArgb
                                            }
                                            if (textPaint.isFakeBoldText != bold) {
                                                textPaint.isFakeBoldText = bold
                                            }
                                            nativeCanvas.drawText(
                                                runCharBuffer,
                                                0,
                                                length,
                                                leftX,
                                                baselineY,
                                                textPaint
                                            )
                                        }
                                    }
                                }
                                rowCursor++
                            }
                        }

                        // Pass 3: Subtle underline drawn ONLY while a token is actively pressed
                        val activePressedToken = pressedToken
                        if (activePressedToken != null && pressedTokenAbsRow in startIndex until endIndex) {
                            val visRow = pressedTokenAbsRow - startIndex
                            val strokeW = dimens.hairlineWidth.toPx()
                            val underlineY = visRow * cellH + baselineOffset + strokeW * 2f
                            val startX = activePressedToken.startCol * cellW
                            val endX = activePressedToken.endColExclusive * cellW
                            if (startX < size.width && underlineY < size.height) {
                                drawLine(
                                    color = colorScheme.resolveAnsiColor(TerminalCell.DEFAULT_FG).copy(alpha = 0.85f),
                                    start = Offset(startX, underlineY),
                                    end = Offset(endX.coerceAtMost(size.width), underlineY),
                                    strokeWidth = strokeW
                                )
                            }
                        }
                    }
                }

                // Sibling lightweight cursor overlay so blinking/glow NEVER redraws the cell-grid Canvas
                if (scrollbackOffset == 0 && screenBuffer != null) {
                    TerminalCursorOverlay(
                        cursorRow = screenBuffer.cursorRow,
                        cursorCol = screenBuffer.cursorCol,
                        cellWidthPx = cellW,
                        cellHeightPx = cellH,
                        cursorColor = colorScheme.cursor,
                        cursorStyle = tokens.cursorStyle,
                        cursorGlowColor = tokens.cursorGlowColor,
                        reduceEffects = StudioTheme.reduceEffects,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Selected text floating toolbar (Copy, Share, Ask AI, Explain)
                val selectedText = remember(gridSelection, screenBuffer) {
                    val buf = screenBuffer ?: return@remember ""
                    val sel = gridSelection ?: return@remember ""
                    sel.extractText(buf)
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = selectedText.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = StudioSpacing.xs)
                ) {
                    TerminalSelectionToolbar(
                        aiEnabled = aiEnabled,
                        onCopy = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onCopySelection(selectedText)
                            gridSelection = null
                        },
                        onShare = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            shareSourceSelection = gridSelection
                            gridSelection = null
                            showShareSheet = true
                        },
                        onAskAi = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            terminalImeFocusAllowed = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onAskAiWithSelection(selectedText)
                            gridSelection = null
                        },
                        onExplain = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            terminalImeFocusAllowed = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onExplainSelection(selectedText)
                            gridSelection = null
                        },
                        onSummarize = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            terminalImeFocusAllowed = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onSummarizeSelection(selectedText)
                            gridSelection = null
                        },
                        onFindRootCause = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            terminalImeFocusAllowed = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onFindRootCauseSelection(selectedText)
                            gridSelection = null
                        },
                        onDismiss = { gridSelection = null }
                    )
                }

                // Dismissible floating "Explain / Fix with AI" chip on non-zero exit (TopEnd so prompt stays clear)
                val showErrorChip = errorToken != null &&
                    dismissedErrorToken != errorToken &&
                    aiEnabled &&
                    selectedText.isEmpty()

                androidx.compose.animation.AnimatedVisibility(
                    visible = showErrorChip,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = StudioSpacing.xs, end = StudioSpacing.xs)
                ) {
                    TerminalFixWithAiChip(
                        exitCode = lastExitCode ?: 1,
                        maxAvailableWidth = maxWidth,
                        onExplain = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            terminalImeFocusAllowed = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onExplainLastError()
                        },
                        onFixWithAi = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            terminalImeFocusAllowed = false
                            focusManager.clearFocus(force = true)
                            keyboardController?.hide()
                            onFixLastError()
                        },
                        onDismiss = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dismissedErrorToken = errorToken
                        }
                    )
                }

                if (scrollbackOffset > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(StudioSpacing.sm)
                            .clip(tokens.pillShape)
                            .clickable { scrollbackOffset = 0 },
                        color = tokens.floatingGlass,
                        shape = tokens.pillShape,
                        border = BorderStroke(tokens.borderWidth, tokens.accentBorderBrush)
                    ) {
                        Text(
                            text = stringResource(R.string.status_scrollback_jump, scrollbackOffset),
                            fontFamily = JetBrainsMonoFamily,
                            style = compactType.tabOrChip,
                            color = tokens.accent,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = StudioSpacing.md, vertical = StudioSpacing.sm)
                        )
                    }
                }

                val zoomLabelText = stringResource(
                    R.string.terminal_font_zoom_indicator,
                    TerminalFontConfig.formatFontSizeValue(effectiveFontSizeSp)
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = showZoomHud,
                    enter = fadeIn(animationSpec = tween(durationMillis = tokens.motionDurationMs)),
                    exit = fadeOut(animationSpec = tween(durationMillis = tokens.motionDurationMs)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Surface(
                        modifier = Modifier
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = zoomLabelText
                            }
                            .testTag("terminal_zoom_indicator"),
                        color = tokens.floatingGlass,
                        shape = tokens.pillShape,
                        border = BorderStroke(tokens.borderWidth, tokens.accentBorderBrush),
                        tonalElevation = StudioElevation.floating
                    ) {
                        Text(
                            text = zoomLabelText,
                            fontFamily = JetBrainsMonoFamily,
                            style = compactType.cardTitle,
                            color = tokens.accent,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(
                                safeContentPadding(tokens.pillShape, StudioSpacing.lg, StudioSpacing.sm)
                            )
                        )
                    }
                }
            }
        }
    }

    val activeTappedToken = tappedToken
    if (activeTappedToken != null) {
        TerminalTokenActionSheet(
            token = activeTappedToken,
            onDismiss = { tappedToken = null },
            onOpenUrl = { url ->
                val launched = runCatching {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }.isSuccess
                if (!launched) {
                    onShowBanner(R.string.token_open_url_failed)
                }
            },
            onCopy = onCopySelection,
            onInsertPath = onInsertToken
        )
    }

    if (showShareSheet) {
        val shareChooserTitle = stringResource(R.string.share_output_chooser_title)
        TerminalShareOutputSheet(
            hasSelection = shareSourceSelection != null,
            onDismiss = {
                showShareSheet = false
                shareSourceSelection = null
            },
            onShareText = {
                val exportedText = TerminalOutputExporter.buildExportText(
                    buffer = screenBuffer,
                    selection = shareSourceSelection
                )
                if (exportedText.isBlank()) {
                    onShowBanner(R.string.share_output_empty)
                } else {
                    val launched = runCatching {
                        context.startActivity(
                            TerminalOutputExporter.buildShareTextIntent(
                                text = exportedText,
                                chooserTitle = shareChooserTitle
                            )
                        )
                    }.isSuccess
                    if (!launched) {
                        onShowBanner(R.string.share_output_failed)
                    }
                }
            },
            onShareImage = {
                val buf = screenBuffer
                if (buf == null) {
                    onShowBanner(R.string.share_output_empty)
                } else {
                    val result = runCatching {
                        val bitmap = TerminalOutputExporter.renderTerminalBufferToBitmap(
                            buffer = buf,
                            colorScheme = colorScheme,
                            typeface = monoTypeface,
                            fontSizePx = fontSizePx
                        )
                        TerminalOutputExporter.shareTerminalBitmap(
                            context = context,
                            bitmap = bitmap,
                            chooserTitle = shareChooserTitle
                        ).getOrThrow()
                    }
                    if (result.isFailure) {
                        onShowBanner(R.string.share_output_failed)
                    }
                }
            }
        )
    }
}

private fun findWordBounds(rowCells: List<TerminalCell>, col: Int): Pair<Int, Int> {
    if (rowCells.isEmpty()) return 0 to 0
    val safeCol = col.coerceIn(0, rowCells.lastIndex)
    if (rowCells[safeCol].char == ' ') {
        return safeCol to safeCol
    }
    var start = safeCol
    while (start > 0 && rowCells[start - 1].char != ' ') {
        start--
    }
    var end = safeCol
    while (end < rowCells.lastIndex && rowCells[end + 1].char != ' ') {
        end++
    }
    return start to end
}

/**
 * Separate overlay Canvas for the terminal cursor so cursor blinking and glow animations
 * never invalidate or redraw the full terminal cell-grid Canvas.
 */
@Composable
private fun TerminalCursorOverlay(
    cursorRow: Int,
    cursorCol: Int,
    cellWidthPx: Float,
    cellHeightPx: Float,
    cursorColor: Color,
    cursorStyle: TerminalCursorStyle,
    cursorGlowColor: Color,
    reduceEffects: Boolean,
    modifier: Modifier = Modifier
) {
    val dimens = StudioTheme.dimens
    val cursorAlpha = if (!reduceEffects) {
        val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
        infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 550),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursorAlpha"
        ).value
    } else {
        0.88f
    }

    Canvas(modifier = modifier.testTag("terminal_cursor_overlay")) {
        if (size.width <= 1f || size.height <= 1f) return@Canvas
        val leftX = cursorCol * cellWidthPx
        val topY = cursorRow * cellHeightPx
        if (leftX >= size.width || topY >= size.height) return@Canvas

        when (cursorStyle) {
            TerminalCursorStyle.BLOCK -> {
                val h = (cellHeightPx - 2f).coerceAtLeast(1f)
                drawRect(
                    color = cursorColor.copy(alpha = cursorAlpha * 0.38f),
                    topLeft = Offset(leftX, topY + 1f),
                    size = Size(cellWidthPx, h)
                )
                drawRect(
                    color = cursorColor.copy(alpha = cursorAlpha),
                    topLeft = Offset(leftX, topY + 1f),
                    size = Size(cellWidthPx, h),
                    style = Stroke(width = dimens.hairlineWidth.toPx() * 1.5f)
                )
            }
            TerminalCursorStyle.UNDERLINE_GLOW -> {
                val underlineHeight = dimens.cursorUnderlineThickness.toPx()
                val underlineTop = (topY + cellHeightPx - underlineHeight).coerceAtLeast(topY)
                if (!reduceEffects && cursorGlowColor.alpha > 0.001f) {
                    val outerPad = StudioSpacing.xs.toPx()
                    drawRect(
                        color = cursorGlowColor.copy(alpha = cursorGlowColor.alpha * 0.45f * cursorAlpha),
                        topLeft = Offset((leftX - outerPad).coerceAtLeast(0f), underlineTop - outerPad * 0.5f),
                        size = Size(cellWidthPx + outerPad * 2f, underlineHeight + outerPad),
                        style = Stroke(width = dimens.cursorUnderlineThickness.toPx())
                    )
                    val innerPad = StudioSpacing.xxs.toPx()
                    drawRect(
                        color = cursorGlowColor.copy(alpha = cursorGlowColor.alpha * 0.75f * cursorAlpha),
                        topLeft = Offset((leftX - innerPad).coerceAtLeast(0f), underlineTop - innerPad * 0.4f),
                        size = Size(cellWidthPx + innerPad * 2f, underlineHeight + innerPad * 0.8f),
                        style = Stroke(width = dimens.hairlineWidth.toPx())
                    )
                }
                drawRect(
                    color = cursorColor.copy(alpha = cursorAlpha),
                    topLeft = Offset(leftX, underlineTop),
                    size = Size(cellWidthPx, underlineHeight)
                )
            }
        }
    }
}

@Composable
private fun TerminalFixWithAiChip(
    exitCode: Int,
    maxAvailableWidth: Dp,
    onExplain: () -> Unit,
    onFixWithAi: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val exitLabel = stringResource(R.string.status_exit_code, exitCode)
    val explainLabel = stringResource(R.string.terminal_chip_explain)
    val fixLabel = stringResource(R.string.terminal_chip_fix_ai)
    val dismissDesc = stringResource(R.string.cd_dismiss_chip)

    val labelStyle = compactType.tabOrChip.copy(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.SemiBold
    )
    val minAdvancePx = with(density) { labelStyle.fontSize.toPx() * 0.6f }
    val exitWidthPx = remember(exitLabel, labelStyle, density) {
        maxOf(
            textMeasurer.measure(exitLabel, style = labelStyle).size.width,
            (exitLabel.length * minAdvancePx).toInt()
        )
    }
    val explainWidthPx = remember(explainLabel, labelStyle, density) {
        maxOf(
            textMeasurer.measure(explainLabel, style = labelStyle).size.width,
            (explainLabel.length * minAdvancePx).toInt()
        )
    }
    val fixWidthPx = remember(fixLabel, labelStyle, density) {
        maxOf(
            textMeasurer.measure(fixLabel, style = labelStyle).size.width,
            (fixLabel.length * minAdvancePx).toInt()
        )
    }

    val showActionLabels = remember(
        maxAvailableWidth,
        exitWidthPx,
        explainWidthPx,
        fixWidthPx,
        tokens.pillShape,
        density
    ) {
        with(density) {
            val availPx = (maxAvailableWidth - StudioSpacing.sm).toPx()
            val padPx = maxOf(StudioSpacing.sm, tokens.pillShape.chamferInset()).toPx() * 2f
            val dotPx = dimens.statusDotSize.toPx()
            val btnChromePx = (StudioSpacing.sm.toPx() * 2f) + dimens.badgeIconSize.toPx() + StudioSpacing.xs.toPx()
            val dismissPx = dimens.controlHeightSmall.toPx()
            val gapsPx = StudioSpacing.xs.toPx() * 4f
            val neededPx = padPx + dotPx + exitWidthPx +
                btnChromePx + explainWidthPx +
                btnChromePx + fixWidthPx +
                dismissPx + gapsPx
            neededPx <= availPx
        }
    }

    Surface(
        modifier = Modifier
            .widthIn(max = maxAvailableWidth)
            .testTag("terminal_fix_with_ai_chip"),
        color = tokens.floatingGlass,
        shape = tokens.pillShape,
        border = BorderStroke(tokens.borderWidth, tokens.error.copy(alpha = 0.65f)),
        tonalElevation = StudioElevation.floating
    ) {
        Row(
            modifier = Modifier.padding(
                safeContentPadding(tokens.pillShape, StudioSpacing.sm, StudioSpacing.xs)
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            Box(
                modifier = Modifier
                    .size(dimens.statusDotSize)
                    .clip(CircleShape)
                    .background(tokens.error)
            )
            Text(
                text = exitLabel,
                fontFamily = JetBrainsMonoFamily,
                style = compactType.tabOrChip,
                color = tokens.error,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            ChipMiniAction(
                label = explainLabel,
                icon = Icons.Default.BugReport,
                color = tokens.textPrimary,
                showLabel = showActionLabels,
                onClick = onExplain,
                modifier = Modifier.testTag("terminal_chip_explain")
            )
            ChipMiniAction(
                label = fixLabel,
                icon = Icons.Default.Build,
                color = tokens.accent,
                showLabel = showActionLabels,
                onClick = onFixWithAi,
                modifier = Modifier.testTag("terminal_chip_fix_ai")
            )
            Box(
                modifier = Modifier
                    .size(dimens.controlHeightSmall)
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = onDismiss
                    )
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = dismissDesc }
                    .testTag("terminal_chip_dismiss"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = dismissDesc,
                    tint = tokens.textMuted,
                    modifier = Modifier.size(dimens.iconSizeSmall)
                )
            }
        }
    }
}

@Composable
private fun TerminalSelectionToolbar(
    aiEnabled: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onAskAi: () -> Unit,
    onExplain: () -> Unit,
    onSummarize: () -> Unit,
    onFindRootCause: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val dismissDesc = stringResource(R.string.cd_dismiss_chip)
    Surface(
        modifier = Modifier
            .padding(horizontal = StudioSpacing.sm)
            .testTag("terminal_selection_toolbar"),
        color = tokens.floatingGlass,
        shape = tokens.pillShape,
        border = BorderStroke(tokens.borderWidth, tokens.accentBorderBrush),
        tonalElevation = StudioElevation.floating
    ) {
        Row(
            modifier = Modifier.padding(
                safeContentPadding(tokens.pillShape, StudioSpacing.sm, StudioSpacing.xs)
            ),
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChipMiniAction(
                    label = stringResource(R.string.terminal_sel_copy),
                    icon = Icons.Default.ContentCopy,
                    color = tokens.textPrimary,
                    showLabel = true,
                    onClick = onCopy,
                    modifier = Modifier.testTag("selection_copy_btn")
                )
                ChipMiniAction(
                    label = stringResource(R.string.terminal_sel_share),
                    icon = Icons.Default.Share,
                    color = tokens.textPrimary,
                    showLabel = true,
                    onClick = onShare,
                    modifier = Modifier.testTag("selection_share_btn")
                )
                if (aiEnabled) {
                    ChipMiniAction(
                        label = stringResource(R.string.terminal_sel_ask_ai),
                        icon = Icons.Default.AutoAwesome,
                        color = tokens.accent,
                        showLabel = true,
                        onClick = onAskAi,
                        modifier = Modifier.testTag("selection_ask_ai_btn")
                    )
                    ChipMiniAction(
                        label = stringResource(R.string.terminal_sel_explain),
                        icon = Icons.Default.BugReport,
                        color = tokens.info,
                        showLabel = true,
                        onClick = onExplain,
                        modifier = Modifier.testTag("selection_explain_btn")
                    )
                    ChipMiniAction(
                        label = stringResource(R.string.terminal_sel_summarize),
                        icon = Icons.Default.Summarize,
                        color = tokens.info,
                        showLabel = true,
                        onClick = onSummarize,
                        modifier = Modifier.testTag("selection_summarize_btn")
                    )
                    ChipMiniAction(
                        label = stringResource(R.string.terminal_sel_root_cause),
                        icon = Icons.Default.ManageSearch,
                        color = tokens.warning,
                        showLabel = true,
                        onClick = onFindRootCause,
                        modifier = Modifier.testTag("selection_root_cause_btn")
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(dimens.controlHeightSmall)
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = onDismiss
                    )
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = dismissDesc },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = dismissDesc,
                    tint = tokens.textMuted,
                    modifier = Modifier.size(dimens.iconSizeSmall)
                )
            }
        }
    }
}

@Composable
private fun ChipMiniAction(
    label: String,
    icon: ImageVector,
    color: Color,
    showLabel: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    Surface(
        modifier = modifier
            .height(dimens.chipHeight)
            .studioTouchTarget(
                minSize = dimens.minTouchTarget,
                onOutOfBoundsTap = onClick
            )
            .clip(tokens.pillShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                if (!showLabel) {
                    contentDescription = label
                }
            },
        color = tokens.layer2,
        shape = tokens.pillShape,
        border = BorderStroke(tokens.borderWidth, color.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = StudioSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(StudioSpacing.xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = if (showLabel) null else label,
                tint = color,
                modifier = Modifier.size(dimens.badgeIconSize)
            )
            if (showLabel) {
                Text(
                    text = label,
                    fontFamily = JetBrainsMonoFamily,
                    style = compactType.tabOrChip,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TerminalStatusStrip(
    cwd: String,
    cols: Int,
    rows: Int,
    onFocusKeyboard: () -> Unit,
    onShareOutput: () -> Unit,
    onClearOutput: () -> Unit
) {
    val tokens = StudioTheme.tokens
    val dimens = StudioTheme.dimens
    val compactType = StudioTheme.compactTypography
    val shortCwd = when {
        cwd == FakeTerminalBackend.HOME_DIR -> "~"
        cwd.startsWith("${FakeTerminalBackend.HOME_DIR}/") -> "~/${cwd.removePrefix("${FakeTerminalBackend.HOME_DIR}/")}"
        cwd.isEmpty() -> "~"
        else -> cwd
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dimens.terminalHeaderHeight)
            .background(tokens.layer2.copy(alpha = 0.7f))
            .padding(horizontal = maxOf(StudioSpacing.md, tokens.cardShape.chamferInset()))
            .testTag("terminal_status_strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "$shortCwd ${cols}×${rows}",
            fontFamily = JetBrainsMonoFamily,
            style = compactType.tabOrChip,
            color = tokens.textSecondary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = onFocusKeyboard
                    )
                    .clip(tokens.smallShape)
                    .clickable(role = Role.Button, onClick = onFocusKeyboard)
                    .testTag("focus_keyboard_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = stringResource(R.string.cd_open_keyboard),
                    tint = tokens.textSecondary,
                    modifier = Modifier.size(dimens.iconSize)
                )
            }
            Box(
                modifier = Modifier
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = onShareOutput
                    )
                    .clip(tokens.smallShape)
                    .clickable(role = Role.Button, onClick = onShareOutput)
                    .testTag("share_output_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = stringResource(R.string.cd_share_output),
                    tint = tokens.textSecondary,
                    modifier = Modifier.size(dimens.iconSize)
                )
            }
            Box(
                modifier = Modifier
                    .studioTouchTarget(
                        minSize = dimens.minTouchTarget,
                        onOutOfBoundsTap = onClearOutput
                    )
                    .clip(tokens.smallShape)
                    .clickable(role = Role.Button, onClick = onClearOutput)
                    .testTag("clear_terminal_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.cd_clear_terminal),
                    tint = tokens.textMuted,
                    modifier = Modifier.size(dimens.iconSize)
                )
            }
        }
    }
}

private const val SENTINEL = " "
