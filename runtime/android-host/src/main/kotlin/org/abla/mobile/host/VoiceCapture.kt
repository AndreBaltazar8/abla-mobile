package org.abla.mobile.host

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

internal object VoiceCapture {
    private const val sampleRate = 16_000
    private const val bytesPerSecond = sampleRate * 2
    private val recording = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var output: File? = null

    @Synchronized
    fun start(context: Context, maxSeconds: Int) {
        check(!recording.get()) { "voice recording is already active" }
        require(maxSeconds in 10..1_800) { "voice limit must be 10 to 1800 seconds" }
        val minimum = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "microphone format is unavailable" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, 4096),
        )
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "microphone could not initialize" }
        val file = File.createTempFile("abla-voice-", ".wav", context.cacheDir)
        RandomAccessFile(file, "rw").use { it.write(ByteArray(44)) }
        recorder = audio
        output = file
        recording.set(true)
        audio.startRecording()
        val maxBytes = bytesPerSecond * maxSeconds
        worker = Thread({ capture(audio, file, maxOf(minimum, 4096), maxBytes) }, "abla-mobile-voice").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop(): File {
        val file = output ?: error("no voice recording is active")
        recording.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.join(2_000)
        recorder?.release()
        recorder = null
        worker = null
        output = null
        val pcmBytes = (file.length() - 44).coerceAtLeast(0).toInt()
        check(pcmBytes > 0) { file.delete(); "voice recording is empty" }
        RandomAccessFile(file, "rw").use { writeWavHeader(it, pcmBytes) }
        return file
    }

    @Synchronized
    fun cancel() {
        recording.set(false)
        try { recorder?.stop() } catch (_: Exception) {}
        worker?.join(2_000)
        recorder?.release()
        recorder = null
        worker = null
        output?.delete()
        output = null
    }

    private fun capture(audio: AudioRecord, file: File, bufferSize: Int, maxBytes: Int) {
        val buffer = ByteArray(bufferSize)
        var written = 0
        RandomAccessFile(file, "rw").use { target ->
            target.seek(44)
            while (recording.get() && written < maxBytes) {
                val read = audio.read(buffer, 0, minOf(buffer.size, maxBytes - written))
                if (read > 0) {
                    target.write(buffer, 0, read)
                    written += read
                } else if (read < 0) {
                    recording.set(false)
                }
            }
        }
        recording.set(false)
    }

    private fun writeWavHeader(file: RandomAccessFile, pcmBytes: Int) {
        fun little(value: Int, bytes: Int) = ByteArray(bytes) { index -> (value shr (8 * index)).toByte() }
        file.seek(0)
        file.writeBytes("RIFF")
        file.write(little(pcmBytes + 36, 4))
        file.writeBytes("WAVEfmt ")
        file.write(little(16, 4))
        file.write(little(1, 2))
        file.write(little(1, 2))
        file.write(little(sampleRate, 4))
        file.write(little(sampleRate * 2, 4))
        file.write(little(2, 2))
        file.write(little(16, 2))
        file.writeBytes("data")
        file.write(little(pcmBytes, 4))
    }
}
