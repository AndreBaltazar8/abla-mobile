package org.abla.mobile.host

// A delayed acknowledgement is not an application-authored replacement for
// newer text. Keep the unacknowledged values while the user is still editing.
internal class OneInputEchoes(initial: String) {
    private var seen = initial
    private val pending = mutableListOf<String>()
    var generation = 0
        private set
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun changed(value: String) {
        pending.add(value)
        generation += 1
    }

    // A non-null result replaces the local edit. An expired old generation
    // cannot reject a newer change, and expiry never discards a focused edit's
    // history: its acknowledgement may still be on the way.
    fun acceptModel(value: String, focused: Boolean, expiredGeneration: Int): String? {
        var replacement: String? = null
        if (value != seen) {
            seen = value
            val echo = pending.indexOf(value)
            if (echo >= 0) pending.subList(0, echo + 1).clear()
            else {
                pending.clear()
                replacement = value
            }
        }
        if (!focused && expiredGeneration == generation) pending.clear()
        if (pending.isEmpty() && !focused) replacement = value
        return replacement
    }
}
