package com.example.terminal

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.example.ui.theme.TerminalColorScheme
import java.io.File
import java.io.FileOutputStream

object TerminalOutputExporter {
    const val MAX_EXPORT_IMAGE_DIMENSION_PX: Int = 4096
    private const val EXPORT_SUBDIR = "terminal_exports"
    private const val EXPORT_PREFIX = "terminal_export_"
    private const val EXPORT_SUFFIX = ".png"
    private const val DEFAULT_CACHE_MAX_AGE_MS = 10L * 60L * 1000L

    fun buildExportText(
        buffer: TerminalScreenBuffer?,
        selection: TerminalGridSelection?
    ): String {
        if (buffer == null) return ""
        if (selection != null) {
            return selection.extractText(buffer)
        }
        val lines = buffer.visibleRows.map { row ->
            row.toCharArrayStringTrimmed()
        }.dropLastWhile { it.isEmpty() }
        return lines.joinToString("\n")
    }

    private fun List<TerminalCell>.toCharArrayStringTrimmed(): String {
        var lastNonSpace = -1
        for (i in indices) {
            if (!this[i].char.isWhitespace()) {
                lastNonSpace = i
            }
        }
        if (lastNonSpace < 0) return ""
        val sb = StringBuilder(lastNonSpace + 1)
        for (i in 0..lastNonSpace) {
            sb.append(this[i].char)
        }
        return sb.toString()
    }

    fun renderTerminalBufferToBitmap(
        buffer: TerminalScreenBuffer,
        colorScheme: TerminalColorScheme,
        typeface: Typeface,
        fontSizePx: Float
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rawMetrics = TerminalFontRegistry.computeCellMetrics(paint, fontSizePx, typeface)
        val cols = buffer.cols.coerceAtLeast(1)
        val rows = buffer.rows.coerceAtLeast(1)
        val paddingPx = (fontSizePx * 0.6f).coerceAtLeast(8f)

        val unscaledWidth = cols * rawMetrics.cellWidthPx + paddingPx * 2f
        val unscaledHeight = rows * rawMetrics.cellHeightPx + paddingPx * 2f
        val scale = minOf(
            1f,
            MAX_EXPORT_IMAGE_DIMENSION_PX / unscaledWidth.coerceAtLeast(1f),
            MAX_EXPORT_IMAGE_DIMENSION_PX / unscaledHeight.coerceAtLeast(1f)
        )

        val scaledFontPx = (fontSizePx * scale).coerceAtLeast(4f)
        val scaledPadding = paddingPx * scale
        val metrics = TerminalFontRegistry.computeCellMetrics(paint, scaledFontPx, typeface)

        val bmpWidth = (cols * metrics.cellWidthPx + scaledPadding * 2f)
            .toInt()
            .coerceIn(1, MAX_EXPORT_IMAGE_DIMENSION_PX)
        val bmpHeight = (rows * metrics.cellHeightPx + scaledPadding * 2f)
            .toInt()
            .coerceIn(1, MAX_EXPORT_IMAGE_DIMENSION_PX)

        val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colorScheme.background.toArgb())

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val charBuf = CharArray(cols)

        for (r in 0 until minOf(rows, buffer.visibleRows.size)) {
            val rowCells = buffer.visibleRows[r]
            val topY = scaledPadding + r * metrics.cellHeightPx
            val baselineY = topY + metrics.baselineOffsetPx
            val limit = minOf(cols, rowCells.size)
            for (c in 0 until limit) {
                val cell = rowCells[c]
                if (cell.bgColorCode != TerminalCell.DEFAULT_BG) {
                    bgPaint.color = colorScheme.resolveAnsiColor(cell.bgColorCode).toArgb()
                    val leftX = scaledPadding + c * metrics.cellWidthPx
                    canvas.drawRect(
                        leftX,
                        topY,
                        leftX + metrics.cellWidthPx + 0.5f,
                        topY + metrics.cellHeightPx + 0.5f,
                        bgPaint
                    )
                }
                charBuf[c] = cell.char
            }
            var c = 0
            while (c < limit) {
                if (charBuf[c] == ' ') {
                    c++
                    continue
                }
                val fg = rowCells[c].fgColorCode
                val bold = rowCells[c].bold
                var end = c + 1
                while (end < limit &&
                    rowCells[end].fgColorCode == fg &&
                    rowCells[end].bold == bold
                ) {
                    end++
                }
                paint.color = colorScheme.resolveAnsiColor(fg).toArgb()
                paint.isFakeBoldText = bold
                canvas.drawText(
                    charBuf,
                    c,
                    end - c,
                    scaledPadding + c * metrics.cellWidthPx,
                    baselineY,
                    paint
                )
                c = end
            }
        }
        return bitmap
    }

    fun writeBitmapToCachePng(
        cacheDir: File,
        bitmap: Bitmap,
        nowMillis: Long = System.currentTimeMillis()
    ): File {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        deleteAllExportPngs(cacheDir)
        val outFile = File(cacheDir, "$EXPORT_PREFIX$nowMillis$EXPORT_SUFFIX")
        FileOutputStream(outFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        outFile.setLastModified(nowMillis)
        return outFile
    }

    fun cleanupExportCache(
        cacheDir: File,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS
    ): Int {
        var deleted = cleanupDir(cacheDir, nowMillis, maxAgeMs)
        val subDir = File(cacheDir, EXPORT_SUBDIR)
        if (subDir.exists() && subDir.isDirectory) {
            deleted += cleanupDir(subDir, nowMillis, maxAgeMs)
        }
        return deleted
    }

    private fun deleteAllExportPngs(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(EXPORT_PREFIX) && file.name.endsWith(EXPORT_SUFFIX)) {
                file.delete()
            }
        }
    }

    private fun cleanupDir(dir: File, nowMillis: Long, maxAgeMs: Long): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(EXPORT_PREFIX) && file.name.endsWith(EXPORT_SUFFIX)) {
                val parsedTs = file.name
                    .removePrefix(EXPORT_PREFIX)
                    .removeSuffix(EXPORT_SUFFIX)
                    .toLongOrNull()
                val fileTs = parsedTs ?: file.lastModified()
                if (nowMillis - fileTs >= maxAgeMs) {
                    if (file.delete()) deleted++
                }
            }
        }
        return deleted
    }

    fun buildShareTextIntent(text: String, chooserTitle: String): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareTerminalBitmap(
        context: Context,
        bitmap: Bitmap,
        chooserTitle: String
    ): Result<Unit> = runCatching {
        val exportDir = File(context.cacheDir, EXPORT_SUBDIR).apply { mkdirs() }
        val file = writeBitmapToCachePng(exportDir, bitmap)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
    }
}
