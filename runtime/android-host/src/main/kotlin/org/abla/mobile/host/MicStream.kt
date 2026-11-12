package org.abla.mobile.host

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

// The microphone as a stream, for an app that sends what's heard somewhere as
// it's said (dictation): 16 kHz mono 16-bit PCM, kept in memory and handed out
// in the order it was heard by `take`. Nothing is written to disk.
internal object MicStream {
    private const val sampleRate = 16_000
    // Ten minutes at most, then it stops by itself.
    private const val maxBytes = sampleRate * 2 * 600
    private val recording = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private val pending = ByteArrayOutputStream()
    private var total = 0
    @Volatile private var level = 0.0

    @Synchronized
    fun start() {
        cancel()
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "microphone format is unavailable" }
        val size = maxOf(minimum, 3200)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2)
        check(audio.state == AudioRecord.STATE_INITIALIZED) { audio.release(); "microphone could not initialize" }
        synchronized(pending) { pending.reset() }
        total = 0
        level = 0.0
        recorder = audio
        recording.set(true)
        audio.startRecording()
        worker = Thread({ capture(audio, size) }, "abla-mobile-mic").apply { isDaemon = true; start() }
    }

    fun active(): Boolean = recording.get()

    fun level(): Double = level

    // What was heard since the last call.
    fun take(): ByteArray = synchronized(pending) {
        val bytes = pending.toByteArray()
        pending.reset()
        bytes
    }

    // Stops, and gives the rest of what was heard.
    @Synchronized
    fun stop(): ByteArray {
        halt()
        return take()
    }

    @Synchronized
    fun cancel() {
        halt()
        synchronized(pending) { pending.reset() }
    }

    private fun halt() {
        recording.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.join(2_000)
        recorder?.release()
        recorder = null
        worker = null
    }

    private fun capture(audio: AudioRecord, size: Int) {
        val buffer = ByteArray(size)
        while (recording.get() && total < maxBytes) {
            val read = audio.read(buffer, 0, buffer.size)
            if (read > 0) {
                var sum = 0.0
                var i = 0
                while (i + 1 < read) {
                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort() / 32768.0
                    sum += sample * sample
                    i += 2
                }
                level = Math.sqrt(sum / maxOf(1, read / 2))
                synchronized(pending) { pending.write(buffer, 0, read) }
                total += read
            } else if (read < 0) {
                recording.set(false)
            }
        }
    }
}
