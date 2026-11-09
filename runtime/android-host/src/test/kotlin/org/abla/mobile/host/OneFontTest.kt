package org.abla.mobile.host

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourceFont
import org.junit.Assert.*
import org.junit.Test

class OneFontTest {
    @Test fun interStackUsesBundledInterAtFourWeights() {
        val family = oneFont("Inter, sans-serif") as FontListFontFamily
        val fonts = family.fonts.map { it as ResourceFont }
        assertEquals(listOf(R.font.inter_400, R.font.inter_500, R.font.inter_600, R.font.inter_700), fonts.map { it.resId })
        assertEquals(listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold), fonts.map { it.weight })
        assertSame(family, oneFont("'inter'"))
    }

    @Test fun unknownNamesFallThroughToTheNextFamily() {
        assertSame(FontFamily.Serif, oneFont("Georgia, serif"))
        assertSame(FontFamily.Monospace, oneFont("\"JetBrains Mono\", monospace"))
        assertSame(FontFamily.SansSerif, oneFont("Helvetica"))
    }

    @Test fun genericNamesKeepTheirMeaning() {
        assertNull(oneFont(""))
        assertSame(FontFamily.Serif, oneFont("serif"))
        assertSame(FontFamily.Monospace, oneFont("mono"))
        assertSame(FontFamily.SansSerif, oneFont("sans-serif"))
    }
}
