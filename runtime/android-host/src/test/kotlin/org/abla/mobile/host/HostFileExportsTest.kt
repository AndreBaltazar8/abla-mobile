package org.abla.mobile.host
import org.junit.Assert.*
import org.junit.Test
class HostFileExportsTest {
    private fun valid(name: String = "contabilidade.xml", mime: String = "application/xml", handle: String = "file-fixture", registered: Boolean = true) = HostFileExports.validMetadata(handle, name, mime, registered)
    @Test fun opaqueRegisteredFilesOnly() {
        assertTrue(valid())
        assertFalse(valid(registered = false))
        assertFalse(valid(handle = "/data/private.xml"))
        assertFalse(valid(handle = "content://other/private"))
        assertFalse(valid(handle = "https://example.test/private"))
    }
    @Test fun rejectPathAndControlNames() {
        for (name in listOf("", " ", ".", "..", "../private.xml", "a/b.xml", "a\\b.xml", "a\n.xml", "a\u0000.xml", "a\u007f.xml", "x".repeat(161))) assertFalse(name, valid(name = name))
        assertTrue(valid(name = "Reembolsos – outubro.xml"))
    }
    @Test fun rejectMimeInjection() {
        for (mime in listOf("", "xml", "application/*", "text/xml\r\nAuthorization: secret", "application/xml; token=secret", "a/")) assertFalse(mime, valid(mime = mime))
        assertTrue(valid(mime = "application/vnd.iso.20022+xml"))
    }
}
