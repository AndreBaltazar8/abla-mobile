package org.abla.mobile.host

import android.content.Context

// An app may hand its calls to the system's phone calls (Telecom, in a module of its own),
// so cars, headsets and watches see them. It's told as one starts and ends, and routes the
// audio.
interface AblaCallHandler {
    // True if it keeps the call going in the foreground itself.
    fun started(context: Context, title: String, caller: String): Boolean
    fun ended()
    // On the speaker or not: whether the phone is then at the ear; null if not handled.
    fun speaker(on: Boolean): Boolean?
}

// What such a module may do with the app's calls.
object AblaCalls {
    var handler: AblaCallHandler?
        get() = CallSession.handler
        set(value) { CallSession.handler = value }

    fun running(): Boolean = CallSession.running()

    // Hung up outside the app: a car, a headset, the call's notification.
    fun hangUp() = CallSession.hangUp()

    // A link for the app with no screen, as for a call answered from a car.
    fun openUrl(context: Context, url: String) = NativeBridge.openUrl(context, url)
}
