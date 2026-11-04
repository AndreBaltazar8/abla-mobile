package org.abla.mobile.host

// Push messages belong to the app: the host has no push service of its own. An app
// that has one (Firebase, say, in a module of its own) sets `tokenProvider` when its
// process starts, and the app's code asks for the token with a request to
// `abla:push-token`, which asks for permission to notify first. Without a provider
// that request answers 501.
object AblaPush {
    // Calls back once with the device's token, or null and why not.
    @Volatile var tokenProvider: ((callback: (token: String?, error: String?) -> Unit) -> Unit)? = null
}
