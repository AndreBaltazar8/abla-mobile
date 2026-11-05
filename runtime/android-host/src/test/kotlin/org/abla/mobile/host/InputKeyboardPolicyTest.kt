package org.abla.mobile.host

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.*
import org.junit.Test

class InputKeyboardPolicyTest {
    @Test fun secureInputDisablesInheritedCorrectionAndCapitalization() {
        val options = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
            keyboardType = KeyboardType.Password,
        ).withSecureInputPolicy(true)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertEquals(false, options.autoCorrectEnabled)
    }

    @Test fun secureInputDoesNotInheritUnspecifiedCorrection() {
        val options = KeyboardOptions(keyboardType = KeyboardType.Password)
            .withSecureInputPolicy(true)
        assertEquals(false, options.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
    }

    @Test fun secureInputKeepsKeyboardAndSubmitAction() {
        val options = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done)
            .withSecureInputPolicy(true)
        assertEquals(KeyboardType.Password, options.keyboardType)
        assertEquals(ImeAction.Done, options.imeAction)
    }

    @Test fun numericSecretsKeepTheirExistingKeyboard() {
        val options = KeyboardOptions(keyboardType = KeyboardType.Number)
            .withSecureInputPolicy(true)
        assertEquals(KeyboardType.Number, options.keyboardType)
    }

    @Test fun ordinaryEditingPolicyIsUnchanged() {
        val original = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrectEnabled = true,
            keyboardType = KeyboardType.Email,
        )
        assertSame(original, original.withSecureInputPolicy(false))
        assertSame(KeyboardOptions.Default, KeyboardOptions.Default.withSecureInputPolicy(false))
    }
}
