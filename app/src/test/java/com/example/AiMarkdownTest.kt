package com.example

import androidx.compose.ui.text.SpanStyle
import com.example.ui.ai.AiMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class AiMarkdownTest {

    @Test
    fun inlineMarkersAreRemovedAndStyled() {
        val r = AiMarkdown.annotate("**tidak ada** dan `ls` serta *miring*", SpanStyle())
        assertEquals("tidak ada dan ls serta miring", r.text)
        assertEquals(3, r.spanStyles.size)
    }

    @Test
    fun bulletsAndHeadings() {
        assertEquals("• a\n• b", AiMarkdown.annotate("- a\n* b", SpanStyle()).text)
        assertEquals("Judul", AiMarkdown.annotate("## Judul", SpanStyle()).text)
    }

    @Test
    fun globAsterisksStayLiteral() {
        assertEquals("ls *.txt dan *.md", AiMarkdown.annotate("ls *.txt dan *.md", SpanStyle()).text)
    }

    @Test
    fun unmatchedMarkersStayLiteral() {
        assertEquals("a ** b ` c", AiMarkdown.annotate("a ** b ` c", SpanStyle()).text)
    }

    @Test
    fun splitAtMarkers() {
        assertEquals(listOf("a", "b", ""), AiMarkdown.splitAtSuggestionMarkers("a\n↳\nb\n↳"))
        assertEquals(listOf("hello"), AiMarkdown.splitAtSuggestionMarkers("hello"))
    }
}
