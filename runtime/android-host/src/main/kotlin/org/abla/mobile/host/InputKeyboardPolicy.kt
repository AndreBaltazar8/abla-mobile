package org.abla.mobile.host

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization

// Password variation disables suggestions; explicitly turn off correction and
// capitalization too, rather than inheriting the Compose/keyboard defaults.
// Keep the caller's keyboard kind (including numeric secrets) and IME action.
internal fun KeyboardOptions.withSecureInputPolicy(secure: Boolean): KeyboardOptions =
    if (secure) copy(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
    else this
