package org.abla.mobile.host
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
class DirectPartsTest {
    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    // A storage stand-in: answers each PUT with `answer(path, body)` as status and ETag.
    private fun storage(answer: (String, ByteArray) -> Pair<Int, String?>): ServerSocket {
        val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                client.use {
                    val input = DataInputStream(it.getInputStream())
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(input.readByte().toInt().toChar())
                    val lines = head.split("\r\n")
                    val length = lines.firstOrNull { line -> line.lowercase().startsWith("content-length:") }?.substringAfter(":")?.trim()?.toInt() ?: 0
                    val body = ByteArray(length)
                    input.readFully(body)
                    val (status, etag) = answer(lines[0].split(" ")[1], body)
                    it.getOutputStream().write("HTTP/1.1 $status X\r\n${if (etag != null) "ETag: $etag\r\n" else ""}Content-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }
        return socket
    }
    @Test fun partsGoToTheirUrlsWithARetry() {
        val stored = ConcurrentHashMap<Int, ByteArray>()
        val failures = AtomicInteger(1)
        val server = storage { path, body ->
            val part = path.substringAfter("partNumber=").toInt()
            if (part == 2 && failures.getAndDecrement() > 0) 503 to null
            else { stored[part] = body; 200 to "\"${md5(body)}\"" }
        }
        try {
            val bytes = ByteArray(2500) { (it % 251).toByte() }
            val base = "http://127.0.0.1:${server.localPort}/object"
            val sent = DirectParts.upload(1, 1000, bytes.size.toLong(), listOf("$base?partNumber=1", "$base?partNumber=2", "$base?partNumber=3")) { ByteArrayInputStream(bytes) }
            assertEquals(listOf(1, 2, 3), sent.map { it.first })
            assertArrayEquals(bytes, stored[1]!! + stored[2]!! + stored[3]!!)
            for ((part, etag) in sent) assertEquals("\"${md5(stored[part]!!)}\"", etag)
            assertTrue("part 2 was refused once and sent again", failures.get() <= 0)
            stored.clear()
            val later = DirectParts.upload(3, 1000, bytes.size.toLong(), listOf("$base?partNumber=3")) { ByteArrayInputStream(bytes) }
            assertEquals(3, later[0].first)
            assertArrayEquals(bytes.copyOfRange(2000, 2500), stored[3])
        } finally { server.close() }
    }
    @Test fun refusalsSayWhy() {
        val server = storage { _, _ -> 403 to null }
        try {
            val failure = assertThrows(IllegalStateException::class.java) {
                DirectParts.upload(1, 10, 5, listOf("http://127.0.0.1:${server.localPort}/x")) { ByteArrayInputStream(ByteArray(5)) }
            }
            assertEquals("Direct upload failed (403)", failure.message)
        } finally { server.close() }
    }
    @Test fun onlySignedHttpsOrLocalUrls() {
        assertTrue(DirectParts.valid(1, 1, listOf("https://bucket.example.test/k?partNumber=1")))
        assertTrue(DirectParts.valid(1, 1, listOf("http://127.0.0.1:9000/k")))
        for (url in listOf("http://bucket.example.test/k", "file:///data/x", "javascript:alert(1)", "")) assertFalse(url, DirectParts.valid(1, 1, listOf(url)))
        assertFalse(DirectParts.valid(0, 1, listOf("https://x.test/")))
        assertFalse(DirectParts.valid(1, 0, listOf("https://x.test/")))
        assertFalse(DirectParts.valid(1, 1, List(101) { "https://x.test/" }))
    }
}
