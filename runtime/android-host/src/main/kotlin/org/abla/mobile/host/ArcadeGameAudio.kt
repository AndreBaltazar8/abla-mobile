package org.abla.mobile.host

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin

internal interface GameAudio : AutoCloseable {
    fun reward(combo: Int)
    fun action()
    fun miss()
    fun impact()
    fun victory()
    fun gameOver()
    override fun close()
}

internal object SilentGameAudio : GameAudio {
    override fun reward(combo: Int) = Unit
    override fun action() = Unit
    override fun miss() = Unit
    override fun impact() = Unit
    override fun victory() = Unit
    override fun gameOver() = Unit
    override fun close() = Unit
}

/** Lightweight procedural SFX: no generic system beeps and no bundled audio files. */
internal class AndroidGameAudio : GameAudio {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "abla-arcade-audio").apply { isDaemon = true }
    }
    @Volatile private var closed = false

    override fun reward(combo: Int) {
        val lift = combo.coerceIn(1, 12) * 18.0
        play(0.09, 520.0 + lift, 880.0 + lift, 0.22, harmonic = 1.5)
    }

    override fun action() = play(0.075, 240.0, 620.0, 0.16, harmonic = 2.0)
    override fun miss() = play(0.07, 170.0, 115.0, 0.10, noise = 0.08)
    override fun impact() = play(0.18, 145.0, 42.0, 0.28, noise = 0.42, harmonic = 0.5)

    override fun victory() {
        playSequence(doubleArrayOf(523.25, 659.25, 783.99, 1046.5), 0.11, 0.24)
    }

    override fun gameOver() {
        playSequence(doubleArrayOf(293.66, 246.94, 196.0, 130.81), 0.15, 0.22)
    }

    private fun playSequence(notes: DoubleArray, noteLength: Double, volume: Double) {
        val segment = (SAMPLE_RATE * noteLength).toInt()
        val samples = ShortArray(segment * notes.size)
        notes.forEachIndexed { noteIndex, frequency ->
            repeat(segment) { index ->
                val t = index.toDouble() / SAMPLE_RATE
                val envelope = envelope(index, segment)
                val wave = sin(2.0 * PI * frequency * t) * 0.72 +
                    sin(2.0 * PI * frequency * 2.0 * t) * 0.18
                samples[noteIndex * segment + index] =
                    (wave * envelope * volume * Short.MAX_VALUE).toInt().toShort()
            }
        }
        playSamples(samples)
    }

    private fun play(
        seconds: Double,
        startFrequency: Double,
        endFrequency: Double,
        volume: Double,
        noise: Double = 0.0,
        harmonic: Double = 1.0,
    ) {
        val count = (SAMPLE_RATE * seconds).toInt().coerceAtLeast(1)
        val samples = ShortArray(count)
        var noiseSeed = 0x12345
        repeat(count) { index ->
            val amount = index.toDouble() / count
            val frequency = startFrequency + (endFrequency - startFrequency) * amount
            val t = index.toDouble() / SAMPLE_RATE
            noiseSeed = noiseSeed * 1103515245 + 12345
            val random = (((noiseSeed ushr 16) and 0x7fff) / 16384.0) - 1.0
            val tonal = sin(2.0 * PI * frequency * t) * 0.72 +
                sin(2.0 * PI * frequency * harmonic * t) * 0.2
            val mixed = tonal * (1.0 - noise) + random * noise
            samples[index] = (mixed * envelope(index, count) * volume * Short.MAX_VALUE)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        playSamples(samples)
    }

    private fun envelope(index: Int, count: Int): Double {
        val position = index.toDouble() / count
        val attack = (position / 0.08).coerceAtMost(1.0)
        val release = ((1.0 - position) / 0.3).coerceAtMost(1.0)
        return attack * release
    }

    private fun playSamples(samples: ShortArray) {
        if (closed || samples.isEmpty()) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return
            }
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val durationMs = samples.size * 1000L / SAMPLE_RATE + 80L
            scheduler.schedule({
                try {
                    track.stop()
                } catch (_: IllegalStateException) {
                    // The platform can stop a short static track before this callback.
                }
                track.release()
            }, durationMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Audio failure never compromises the game loop.
        }
    }

    override fun close() {
        closed = true
        scheduler.shutdownNow()
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
    }
}
