package org.abla.mobile.host

import androidx.core.content.FileProvider

// A distinct provider name avoids merging with a consuming app's own FileProvider.
class AblaExportProvider : FileProvider()
