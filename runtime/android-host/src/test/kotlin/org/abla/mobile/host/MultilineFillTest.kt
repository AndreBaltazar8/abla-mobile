package org.abla.mobile.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilineFillTest {
    @Test fun aFieldSizedByItsTextDoesNotFill() {
        // A chat's composer: no height of its own. Filling would take the screen from the feed above it.
        assertFalse(oneMultilineFillsHeight("", 0))
        assertFalse(oneMultilineFillsHeight("auto", 0))
        assertFalse(oneMultilineFillsHeight("0", 0))
    }

    @Test fun aFieldWithItsOwnHeightFillsIt() {
        assertTrue(oneMultilineFillsHeight("120", 0))
        assertTrue(oneMultilineFillsHeight("fill", 0))
        assertTrue(oneMultilineFillsHeight("", 1))
    }
}
