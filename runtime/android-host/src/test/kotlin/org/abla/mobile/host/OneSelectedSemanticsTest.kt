package org.abla.mobile.host

import org.junit.Assert.*
import org.junit.Test

class OneSelectedSemanticsTest {
    @Test fun aChosenNodeIsSelectedAndAnUnchosenOneIsNot() {
        assertEquals(true, oneSelectedSemantics("true"))
        assertEquals(false, oneSelectedSemantics("false"))
    }

    @Test fun aNodeThatDoesNotSayExposesNoState() {
        assertNull(oneSelectedSemantics(""))
        assertNull(oneSelectedSemantics("maybe"))
        assertNull(oneSelectedSemantics("TRUE"))
    }
}
