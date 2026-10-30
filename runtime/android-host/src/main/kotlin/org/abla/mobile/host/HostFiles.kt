package org.abla.mobile.host

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

// Files the Abla program can refer to by handle without holding their bytes:
// documents the user picked (content URIs, read through the content resolver)
// and responses saved by SEND (files in the app's cache directory). Handles are
// opaque random strings valid for the life of the process; the program never
// sees paths or URIs.
internal object HostFiles {
    private class Entry(val uri: Uri?, val file: File?, val name: String, val size: Long, val mime: String)

    private val entries = HashMap<String, Entry>()

    private fun newHandle(): String = "file-" + UUID.randomUUID().toString()

    @Synchronized
    fun registerPicked(context: Context, uri: Uri): JSONObject {
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val handle = newHandle()
        entries[handle] = Entry(uri, null, name, size, mime)
        return describe(handle)
    }

    @Synchronized
    fun registerSaved(file: File, name: String, mime: String): JSONObject {
        val handle = newHandle()
        entries[handle] = Entry(null, file, name, file.length(), mime)
        return describe(handle)
    }

    @Synchronized
    fun describe(handle: String): JSONObject {
        val entry = entries[handle] ?: return JSONObject()
        return JSONObject().put("handle", handle).put("name", entry.name).put("size", entry.size).put("mime", entry.mime)
    }

    @Synchronized
    fun exists(handle: String): Boolean = entries.containsKey(handle)

    @Synchronized
    fun mime(handle: String): String = entries[handle]?.mime ?: "application/octet-stream"

    @Synchronized
    fun name(handle: String): String = entries[handle]?.name ?: "file"

    @Synchronized
    fun size(handle: String): Long = entries[handle]?.size ?: -1L

    fun open(context: Context, handle: String): InputStream? {
        val entry = synchronized(this) { entries[handle] } ?: return null
        return entry.file?.inputStream() ?: entry.uri?.let { context.contentResolver.openInputStream(it) }
    }

    // Reads up to `length` bytes starting at `offset`.
    fun read(context: Context, handle: String, offset: Long, length: Int): ByteArray? {
        val stream = open(context, handle) ?: return null
        return stream.use {
            var skipped = 0L
            while (skipped < offset) {
                val step = it.skip(offset - skipped)
                if (step <= 0) break
                skipped += step
            }
            val buffer = ByteArray(length)
            var total = 0
            while (total < length) {
                val read = it.read(buffer, total, length - total)
                if (read < 0) break
                total += read
            }
            buffer.copyOf(total)
        }
    }

    // Forgets a handle; files this store created are deleted. Picked documents
    // belong to the user and are never deleted.
    @Synchronized
    fun delete(handle: String): Boolean {
        val entry = entries.remove(handle) ?: return false
        entry.file?.delete()
        return true
    }

    fun cacheFile(context: Context): File {
        val directory = File(context.cacheDir, "abla-files")
        directory.mkdirs()
        return File(directory, UUID.randomUUID().toString())
    }
}
