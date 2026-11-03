package org.abla.mobile.host

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.UUID

// Only process-local HostFiles handles cross this boundary: never URLs, paths or credentials.
internal object HostFileExports {
    const val SAVE_REQUEST = 4705
    private data class Export(val handle: String, val name: String, val mime: String)
    private var pending: Export? = null

    internal fun validMetadata(handle: String, name: String, mime: String, registered: Boolean): Boolean =
        registered && handle.startsWith("file-") && handle.length <= 128 &&
            name.isNotBlank() && name.length <= 160 && name != "." && name != ".." &&
            name.none { it <= '\u001f' || it == '\u007f' || it == '/' || it == '\\' } &&
            mime.length <= 128 && mime.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+"))

    private fun parse(payload: String): Export {
        require(payload.length <= 2048)
        val json = JSONObject(payload)
        val handle = json.getString("handle")
        val name = json.getString("name")
        val mime = json.getString("mime")
        require(validMetadata(handle, name, mime, HostFiles.exists(handle)))
        return Export(handle, name, mime)
    }

    fun launch(activity: Activity, payload: String, share: Boolean) {
        val export = parse(payload)
        if (share) {
            // Copy large responses off the UI thread. Only the chooser launch returns to it.
            Thread({
            // Export a copy in the narrowly scoped provider directory, never the private HTTP cache.
            val directory = File(activity.cacheDir, "abla-exports")
            directory.mkdirs()
            // Remove stale copies only; active receiving apps have a day to open their grant.
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86400000L }?.forEach { it.deleteRecursively() }
            val file = File(File(directory, UUID.randomUUID().toString()).apply { mkdirs() }, export.name)
            try {
                HostFiles.open(activity, export.handle)?.use { source -> file.outputStream().use { source.copyTo(it) } }
                    ?: error("missing file")
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.abla.files", file)
                val intent = Intent(Intent.ACTION_SEND).setType(export.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.clipData = ClipData.newUri(activity.contentResolver, export.name, uri)
                activity.runOnUiThread {
                    try {
                        activity.startActivity(Intent.createChooser(intent, null))
                        completed("shared", export.handle)
                    } catch (_: Exception) { file.delete(); completed("failed", export.handle) }
                }
            } catch (_: Exception) {
                file.delete()
                completed("failed", export.handle)
            }
            }, "abla-file-share").start()
        } else {
            synchronized(this) { check(pending == null); pending = export }
            try {
                activity.startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType(export.mime)
                    .putExtra(Intent.EXTRA_TITLE, export.name), SAVE_REQUEST)
            } catch (failure: Exception) {
                synchronized(this) { pending = null }
                throw failure
            }
        }
    }

    fun finish(activity: Activity, resultCode: Int, uri: Uri?) {
        val export = synchronized(this) { pending.also { pending = null } } ?: return
        if (resultCode != Activity.RESULT_OK || uri == null) { completed("cancelled", export.handle); return }
        Thread({
            try {
                HostFiles.open(activity, export.handle)?.use { source ->
                    activity.contentResolver.openOutputStream(uri, "wt")?.use { source.copyTo(it) }
                        ?: error("unwritable destination")
                } ?: error("missing file")
                completed("saved", export.handle)
            } catch (_: Exception) { completed("failed", export.handle) }
        }, "abla-file-export").start()
    }

    fun failed(payload: String) {
        val handle = try { JSONObject(payload).optString("handle") } catch (_: Exception) { "" }
        completed("failed", handle)
    }

    private fun completed(status: String, handle: String) {
        NativeBridge.sendEvent(1, 2147483647L, JSONObject().put("kind", "fileExport").put("status", status).put("handle", handle).toString().toByteArray(Charsets.UTF_8))
    }
}
