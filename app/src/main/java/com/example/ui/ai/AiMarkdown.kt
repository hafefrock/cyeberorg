package com.example.ui.ai

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.StudioTheme

/** Small, dependency-free markdown subset for AI replies: bold, italic, inline code, headings, bullets. */
object AiMarkdown {

    private val MARKER = Regex("\\s*\u21B3\\s*")
    private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+(.*)$")
    private val BULLET = Regex("^(\\s*)[-*]\\s+(.*)$")

    /** Splits assistant text at the arrow markers that stand where command cards belong. */
    fun splitAtSuggestionMarkers(text: String): List<String> =
        text.split(MARKER).map { it.trim() }

    fun annotate(text: String, codeStyle: SpanStyle): AnnotatedString = buildAnnotatedString {
        text.lines().forEachIndexed { index, line ->
            if (index > 0) append('\n')
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            when {
                heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(heading.groupValues[1], codeStyle)
                }
                bullet != null -> {
                    append(bullet.groupValues[1])
                    append("\u2022 ")
                    appendInline(bullet.groupValues[2], codeStyle)
                }
                else -> appendInline(line, codeStyle)
            }
        }
    }

    private fun AnnotatedString.Builder.appendInline(s: String, codeStyle: SpanStyle) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '*' && s.startsWith("**", i)) {
                val j = s.indexOf("**", i + 2)
                if (j > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(s.substring(i + 2, j), codeStyle)
                    }
                    i = j + 2
                    continue
                }
            } else if (c == '`') {
                val j = s.indexOf('`', i + 1)
                if (j > i + 1) {
                    withStyle(codeStyle) { append(s.substring(i + 1, j)) }
                    i = j + 1
                    continue
                }
            } else if (c == '*' && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                val j = s.indexOf('*', i + 1)
                if (j > i + 1 && !s[j - 1].isWhitespace()) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInline(s.substring(i + 1, j), codeStyle)
                    }
                    i = j + 1
                    continue
                }
            }
            append(c)
            i++
        }
    }
}

@Composable
fun AiMarkdownText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    val tokens = StudioTheme.tokens
    val codeStyle = remember(tokens.accentMuted) {
        SpanStyle(fontFamily = JetBrainsMonoFamily, background = tokens.accentMuted)
    }
    val annotated = remember(text, codeStyle) { AiMarkdown.annotate(text, codeStyle) }
    Text(text = annotated, style = style, color = color, modifier = modifier)
}
