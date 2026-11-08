package org.abla.mobile.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class UiNodeFingerprintTest {
    private fun leaf(key: String, vararg properties: Pair<String, Any?>) =
        uiNodeFingerprint("one.text", key, mapOf(*properties), emptyList())

    @Test fun theSameNodeHasTheSameFingerprint() {
        assertEquals(leaf("a", "text" to "Hello", "size" to 14), leaf("a", "size" to 14, "text" to "Hello"))
    }

    @Test fun aChangedHandlerIdIsNeverTheSame() {
        // A button whose press now sends another event must render again, though it looks the same.
        val before = uiNodeFingerprint("one.pressable", "save", mapOf("onPress" to 41, "label" to "Save"), emptyList())
        val after = uiNodeFingerprint("one.pressable", "save", mapOf("onPress" to 42, "label" to "Save"), emptyList())
        assertNotEquals(before, after)
    }

    @Test fun aValueOfAnotherTypeIsNotTheSame() {
        assertNotEquals(leaf("a", "value" to 1), leaf("a", "value" to "1"))
        assertNotEquals(leaf("a", "value" to true), leaf("a", "value" to "true"))
        assertNotEquals(leaf("a", "value" to null), leaf("a", "value" to "null"))
    }

    @Test fun aChangedLeafChangesOnlyItsPath() {
        val unchanged = leaf("left", "text" to "Same")
        val before = leaf("right", "text" to "Old")
        val after = leaf("right", "text" to "New")
        val parentBefore = uiNodeFingerprint("one.column", "root", emptyMap(), listOf(unchanged, before))
        val parentAfter = uiNodeFingerprint("one.column", "root", emptyMap(), listOf(unchanged, after))
        assertNotEquals(parentBefore, parentAfter)
        // The sibling's own fingerprint is untouched, so it is skipped.
        assertEquals(unchanged, leaf("left", "text" to "Same"))
    }

    @Test fun childOrderMatters() {
        val a = leaf("a", "text" to "A")
        val b = leaf("b", "text" to "B")
        assertNotEquals(uiNodeFingerprint("one.row", "r", emptyMap(), listOf(a, b)), uiNodeFingerprint("one.row", "r", emptyMap(), listOf(b, a)))
    }

    @Test fun boundariesBetweenFieldsCount() {
        // "ab"+"c" and "a"+"bc" must not collide.
        assertNotEquals(leaf("k", "ab" to "c"), leaf("k", "a" to "bc"))
        assertNotEquals(uiNodeFingerprint("ab", "c", emptyMap(), emptyList()), uiNodeFingerprint("a", "bc", emptyMap(), emptyList()))
    }
}
