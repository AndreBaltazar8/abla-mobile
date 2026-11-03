package org.abla.mobile.host

import org.junit.Assert.*
import org.junit.Test

class OneInputEchoesTest {
    @Test fun lateEchoAfterIdleDoesNotDeleteNewCharacters() {
        val field = OneInputEchoes("")
        field.changed("a")
        val firstTimeout = field.generation
        assertNull(field.acceptModel("", focused = true, expiredGeneration = firstTimeout))
        field.changed("an")
        assertNull(field.acceptModel("a", focused = true, expiredGeneration = firstTimeout))
        assertTrue(field.hasPending)
        field.changed("androidqa@example.test")
        assertNull(field.acceptModel("an", focused = true, expiredGeneration = firstTimeout))
        assertNull(field.acceptModel("androidqa@example.test", focused = true, expiredGeneration = firstTimeout))
        assertFalse(field.hasPending)
    }

    @Test fun olderExpiryCannotRejectANewerUnfocusedEdit() {
        val field = OneInputEchoes("")
        field.changed("a")
        val oldTimeout = field.generation
        field.changed("an")
        assertNull(field.acceptModel("", focused = false, expiredGeneration = oldTimeout))
        assertTrue(field.hasPending)
    }

    @Test fun ignoredEditReturnsToModelAfterBlurAndExpiry() {
        val field = OneInputEchoes("original")
        field.changed("local edit")
        assertNull(field.acceptModel("original", focused = true, expiredGeneration = field.generation))
        assertTrue(field.hasPending)
        assertEquals("original", field.acceptModel("original", focused = false, expiredGeneration = field.generation))
        assertFalse(field.hasPending)
    }

    @Test fun blurBeforeExpiryRetainsPendingEdit() {
        val field = OneInputEchoes("original")
        field.changed("local edit")
        assertNull(field.acceptModel("original", focused = false, expiredGeneration = -1))
        assertTrue(field.hasPending)
    }

    @Test fun applicationReplacementStillWinsWhileFocused() {
        val field = OneInputEchoes("original")
        field.changed("local edit")
        assertEquals("replacement", field.acceptModel("replacement", focused = true, expiredGeneration = -1))
        assertFalse(field.hasPending)
    }

    @Test fun applicationCanClearAnAcknowledgedField() {
        val field = OneInputEchoes("")
        field.changed("a")
        assertNull(field.acceptModel("a", focused = true, expiredGeneration = -1))
        field.changed("an")
        assertEquals("", field.acceptModel("", focused = true, expiredGeneration = -1))
        assertFalse(field.hasPending)
    }
}
