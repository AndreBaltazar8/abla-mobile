package org.abla.mobile.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilineFillTest {
    @Test fun aFieldSizedByItsTextDoesNotFill() {
        // A chat's composer: no height of its own (it grows across its row). Filling would take the screen from the feed.
        assertFalse(oneMultilineFillsHeight(""))
        assertFalse(oneMultilineFillsHeight("auto"))
        assertFalse(oneMultilineFillsHeight("0"))
    }

    @Test fun aFieldWithItsOwnHeightFillsIt() {
        assertTrue(oneMultilineFillsHeight("120"))
        assertTrue(oneMultilineFillsHeight("fill"))
    }
}
