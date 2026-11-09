package org.abla.mobile.host

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Direct uploads (FILEPUT): part n of a document, the bytes from (n - 1) * partBytes,
// streamed with PUT to its signed URL as it is read. Each part is tried up to three
// times; the answer is each part's ETag, for the app to complete the upload.
internal object DirectParts {
    fun validUrl(url: String): Boolean {
        val uri = try { URI(url) } catch (_: Exception) { return false }
        return url.length <= 8192 && (uri.scheme == "https" || (uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")))
    }

    fun valid(first: Int, partBytes: Long, urls: List<String>): Boolean =
        first in 1..10000 && partBytes >= 1 && urls.size in 1..100 && urls.all { validUrl(it) }

    fun upload(first: Int, partBytes: Long, total: Long, urls: List<String>, open: () -> InputStream?): List<Pair<Int, String>> {
        require(valid(first, partBytes, urls)) { "invalid direct upload" }
        val sent = ArrayList<Pair<Int, String>>()
        for ((index, url) in urls.withIndex()) {
            val part = first + index
            val start = (part - 1) * partBytes
            val length = if (total >= 0) minOf(partBytes, total - start) else partBytes
            if (length <= 0) throw IllegalStateException("Direct upload failed: part $part is past the file")
            sent.add(part to send(url, start, length, open))
        }
        return sent
    }

    private fun send(url: String, start: Long, length: Long, open: () -> InputStream?): String {
        var attempt = 0
        while (true) {
            var connection: HttpURLConnection? = null
            var retry = false
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "PUT"
                connection.connectTimeout = 15_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(length)
                val input = open() ?: throw IllegalStateException("Direct file unavailable")
                input.use {
                    var skipped = 0L
                    while (skipped < start) {
                        val step = it.skip(start - skipped)
                        if (step <= 0) throw IllegalStateException("Direct file changed")
                        skipped += step
                    }
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var left = length
                        while (left > 0) {
                            val read = it.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) throw IllegalStateException("Direct file changed")
                            output.write(buffer, 0, read)
                            left -= read
                        }
                    }
                }
                val status = connection.responseCode
                (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
                val etag = connection.getHeaderField("ETag")
                if (status in 200..299 && !etag.isNullOrEmpty()) return etag
                if (status < 500 || attempt >= 2) throw IllegalStateException("Direct upload failed ($status)")
                retry = true
            } catch (failure: java.io.IOException) {
                if (attempt >= 2) throw IllegalStateException("Direct upload failed (${failure.javaClass.simpleName})")
                retry = true
            } finally {
                connection?.disconnect()
            }
            if (retry) {
                attempt += 1
                Thread.sleep(200L * attempt)
            }
        }
    }
}
