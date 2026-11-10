package org.abla.mobile.host

import org.junit.Assert.*
import org.junit.Test

// The color scheme is kept while what it is built from stays the same (694). These check the keys it is kept by, so a
// change that matters still rebuilds it: a scheme kept too long would leave a screen in the old colors.
class OneThemeKeysTest {
    @Test fun lightAndDarkRootSchemesDiffer() {
        assertNotEquals(oneRootScheme(false).background, oneRootScheme(true).background)
        assertNotEquals(oneRootScheme(false).onSurface, oneRootScheme(true).onSurface)
    }

    @Test fun aThemeNodesColorChangeChangesItsKey() {
        val before = mapOf("primary" to "#2F5BEA", "surface" to "#FFFFFF")
        val after = before + ("primary" to "#193C30")
        assertNotEquals(oneThemeNodeColors { before[it] ?: "" }, oneThemeNodeColors { after[it] ?: "" })
    }

    @Test fun eachThemeColorIsPartOfTheKey() {
        for (name in listOf("primary", "onPrimary", "surface", "onSurface", "muted", "outline")) {
            val changed = oneThemeNodeColors { if (it == name) "#000001" else "" }
            assertNotEquals(name, oneThemeNodeColors { "" }, changed)
        }
    }

    @Test fun theSameColorsGiveTheSameKey() {
        val colors = mapOf("primary" to "#2F5BEA")
        assertEquals(oneThemeNodeColors { colors[it] ?: "" }, oneThemeNodeColors { colors[it] ?: "" })
    }
}
