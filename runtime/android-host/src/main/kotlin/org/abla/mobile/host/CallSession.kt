package org.abla.mobile.host

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.withLock
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// A voice call with an OpenAI Realtime model (abla:call): the connection, the microphone
// and the speaker, here on the phone, so what's said is heard at once. The app supplies a
// short-lived key, and holds the rest of the call (the instructions, the tools) elsewhere:
// it takes the events that matter with `events`, and sends what's needed with `send`.
//
// What's done here, for speed:
// - The microphone (24 kHz mono 16-bit, the platform's echo cancelling and noise
//   suppression) goes straight to the model.
// - The model's audio is played as it comes.
// - When the person speaks over it, the playing stops and the model is told how much was
//   heard (conversation.item.truncate).
// - When the person stops speaking, what they said goes with the event, as 16 kHz PCM,
//   for the app to have written down.
internal object CallSession {
    private const val rate = 24_000
    private const val bytesPerMs = 48
    private const val frameBytes = rate / 25 * 2
    // Ninety seconds of what the microphone sent, to cut what was said from.
    private const val ringBytes = rate * 2 * 90
    // What's said is sent with the event up to twenty seconds of it.
    private const val segmentBytes = rate * 2 * 20
    private val forwarded = setOf(
        "session.created", "error", "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped",
        "conversation.item.added", "conversation.item.created", "response.created", "response.done",
        "response.output_audio_transcript.done", "conversation.item.input_audio_transcription.completed",
    )

    private val running = AtomicBoolean(false)
    @Volatile private var state = "idle"
    @Volatile private var problem = ""
    private var socket: SSLSocket? = null
    @Volatile private var line: java.net.Socket? = null
    private var output: OutputStream? = null
    // Taken to write a frame; never waited on by the watch, as a dead line can hold it.
    private val writeLock = java.util.concurrent.locks.ReentrantLock()
    private val events = ArrayList<JSONObject>()
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<Pair<String, ByteArray>>()
    // What was handed to the speaker since the last flush, by item, in frames.
    private val played = ArrayList<Pair<String, Long>>()
    private var writtenFrames = 0L
    private val playLock = Any()
    private val ring = ByteArray(ringBytes)
    // Bytes the microphone sent since the call began; the model counts its milliseconds from these.
    @Volatile private var micBytes = 0L
    // When OpenAI last sent anything; a ping every few seconds makes it answer even in silence.
    @Volatile private var heardAt = 0L
    // The model's first response on each connection plays out whatever is heard over it (a
    // "hello?", the phone lifted to the ear): the other side opens its session the same way.
    @Volatile private var opening = true
    private var speechFrom = 0L
    private var audioManager: AudioManager? = null
    private var previousMode = AudioManager.MODE_NORMAL
    // The app looks at the call a few times a second (`take`); a call nobody follows any more
    // (the app's view gone, or stuck) ends, so the phone isn't left on a call for hours.
    @Volatile private var takenAt = 0L
    // When the line dropped, while it's being brought back.
    @Volatile private var droppedAt = 0L
    private var generation = 0
    private const val UNFOLLOWED_FOR = 120_000L
    private const val RECONNECT_FOR = 120_000L

    fun state(): String = state

    fun running(): Boolean = running.get()

    @Volatile var handler: AblaCallHandler? = null
    private var handled = false
    // Hung up by André outside the app (a car, a headset, the call's notification).
    @Volatile private var hungUp = false
    @Volatile private var hungUpWhy = ""

    fun hangUp(why: String = "") {
        if (!running.get()) return
        hungUpWhy = why
        hungUp = true
        stop("ended")
    }

    // Something worth knowing about the call on the phone, for the other side's log.
    fun note(text: String) {
        if (!running.get()) return
        synchronized(events) { events.add(JSONObject().put("type", "phone.note").put("text", text)) }
    }

    private fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "loudspeaker"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth call audio"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth media"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
            else -> "type ${device.type}"
        }
        return "$kind (${device.productName})"
    }

    private fun modeName(): String = when (audioManager?.mode) {
        AudioManager.MODE_IN_COMMUNICATION -> "in communication"
        AudioManager.MODE_IN_CALL -> "in call"
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        else -> "${audioManager?.mode}"
    }

    private var appContext: Context? = null

    @Synchronized
    fun start(context: Context, key: String, model: String, speaker: Boolean, title: String = "On a call", caller: String = "") {
        end(false)
        problem = ""
        hungUp = false
        hungUpWhy = ""
        state = "connecting"
        synchronized(events) { events.clear() }
        playQueue.clear()
        synchronized(playLock) { played.clear(); writtenFrames = 0 }
        micBytes = 0
        takenAt = System.currentTimeMillis()
        droppedAt = 0L
        running.set(true)
        guard(++generation)
        appContext = context.applicationContext
        handled = try { handler?.started(context.applicationContext, title, caller) == true } catch (_: Exception) { false }
        audioManager = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also {
            previousMode = it.mode
            // The system sets it for a call it knows about.
            if (!handled) it.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        speaker(speaker)
        Thread({ run(key, model) }, "abla-call").apply { isDaemon = true; start() }
        if (!handled) CallService.begin(context.applicationContext, title)
    }

    // On the loudspeaker, or held to the ear like a phone call: a headset or Bluetooth when
    // there is one, else the earpiece, with the screen off while it's against the face.
    @Synchronized
    fun speaker(on: Boolean) {
        val manager = audioManager ?: return
        if (handled) {
            val atEar = try { handler?.speaker(on) } catch (_: Exception) { null }
            if (atEar != null) return nearEar(atEar)
        }
        var held = !on
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = manager.availableCommunicationDevices
            val headset = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val wanted = if (on) devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            if (wanted != null) manager.setCommunicationDevice(wanted) else manager.clearCommunicationDevice()
            if (!on && headset != null) held = false
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = on
        }
        nearEar(held)
    }

    private var proximity: PowerManager.WakeLock? = null

    private fun nearEar(on: Boolean) {
        if (!on) {
            try { proximity?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            proximity = null
            return
        }
        if (proximity != null) return
        val power = appContext?.getSystemService(PowerManager::class.java) ?: return
        if (!power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = try {
            power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "abla:call").also { it.acquire(4 * 60 * 60 * 1000L) }
        } catch (_: Exception) {
            null
        }
    }

    // The events that came since the last look, and how the call is.
    fun take(): JSONObject {
        takenAt = System.currentTimeMillis()
        val list = JSONArray()
        synchronized(events) {
            for (event in events) list.put(event)
            events.clear()
        }
        return JSONObject().put("events", list).put("state", state).put("error", problem)
            .put("playing", playingMs() > 0).put("hungUp", hungUp).put("hungUpWhy", hungUpWhy)
    }

    fun send(list: JSONArray) {
        for (index in 0 until list.length()) {
            val event = list.optJSONObject(index) ?: continue
            write(event.toString())
        }
    }

    // Hangs up: at once, or once what the model said has been heard.
    fun end(drain: Boolean) {
        if (!running.get()) return
        if (drain) {
            Thread({
                val until = System.currentTimeMillis() + 15_000
                while ((playQueue.isNotEmpty() || playingMs() > 0) && System.currentTimeMillis() < until) Thread.sleep(100)
                stop("ended")
            }, "abla-call-end").apply { isDaemon = true; start() }
        } else stop("ended")
    }

    // The connection broke while the call was on: the call stays up (Telecom, the audio), and
    // waits for the app to bring a new key (`reconnect`), the conversation so far in its session.
    private fun dropped(why: String?) {
        try { line?.close() } catch (_: Exception) {}
        writeLock.withLock {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            line = null
            output = null
        }
        playQueue.clear()
        synchronized(playLock) {
            try { track?.pause(); track?.flush(); track?.play() } catch (_: Exception) {}
            played.clear()
            writtenFrames = 0
        }
        problem = "the connection dropped: ${why ?: "closed"}"
        if (droppedAt == 0L) droppedAt = System.currentTimeMillis()
        state = "reconnecting"
        note("$problem; reconnecting")
    }

    // A new connection for the call, with a key for a session that knows what was said.
    fun reconnect(key: String, model: String) {
        if (!running.get() || state != "reconnecting") return
        state = "connecting again"
        Thread({ run(key, model, again = true) }, "abla-call").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stop(final: String) {
        if (!running.getAndSet(false)) return
        if (state != "failed") state = final
        try { line?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        track?.release()
        track = null
        playQueue.clear()
        nearEar(false)
        audioManager?.let {
            if (!handled) {
                if (Build.VERSION.SDK_INT >= 31) it.clearCommunicationDevice()
                @Suppress("DEPRECATION")
                it.isSpeakerphoneOn = false
                it.mode = previousMode
            }
        }
        audioManager = null
        if (handled) try { handler?.ended() } catch (_: Exception) {}
        handled = false
        appContext?.let { CallService.finish(it) }
    }

    // Ends the call, whatever the app does, when nobody follows it any more or its line
    // doesn't come back.
    private fun guard(mine: Int) {
        Thread({
            while (running.get() && generation == mine) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
                if (!running.get() || generation != mine) return@Thread
                val now = System.currentTimeMillis()
                if (now - takenAt > UNFOLLOWED_FOR) return@Thread fail("the app stopped following the call for ${UNFOLLOWED_FOR / 1000} s")
                if (droppedAt != 0L && now - droppedAt > RECONNECT_FOR) return@Thread fail("the line didn't come back in ${RECONNECT_FOR / 1000} s")
            }
        }, "abla-call-guard").apply { isDaemon = true; start() }
    }

    private fun fail(message: String) {
        problem = message
        state = "failed"
        stop("failed")
    }

    private fun run(key: String, model: String, again: Boolean = false) {
        try {
            connect(key, model)
        } catch (failure: Exception) {
            if (!again) return fail("couldn't reach OpenAI: ${failure.message}")
            if (!running.get()) return
            problem = "couldn't reconnect: ${failure.message}"
            state = "reconnecting"
            return note(problem)
        }
        if (!running.get()) {
            writeLock.withLock { try { socket?.close() } catch (_: Exception) {} }
            return
        }
        // The new session counts its milliseconds from now.
        if (again) {
            micBytes = 0
            problem = ""
            note("reconnected")
        }
        opening = true
        droppedAt = 0L
        state = "live"
        if (!again) startAudio()
        watch(socket)
        try {
            read()
        } catch (failure: Exception) {
            if (running.get()) return dropped(failure.message)
        }
        if (running.get()) stop("ended")
    }

    // ---- The WebSocket ----

    private fun connect(key: String, model: String) {
        // The plain socket under TLS is kept, to cut a dead line without waiting on TLS.
        val plain = java.net.Socket()
        plain.connect(java.net.InetSocketAddress("api.openai.com", 443), 10_000)
        val opened = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, "api.openai.com", 443, true) as SSLSocket
        opened.soTimeout = 15_000
        opened.startHandshake()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val request = "GET /v1/realtime?model=$model HTTP/1.1\r\nHost: api.openai.com\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: ${Base64.encodeToString(nonce, Base64.NO_WRAP)}\r\n" +
            "Sec-WebSocket-Version: 13\r\nAuthorization: Bearer $key\r\n\r\n"
        opened.outputStream.write(request.toByteArray(Charsets.US_ASCII))
        opened.outputStream.flush()
        val input = opened.inputStream
        val head = ByteArrayOutputStream()
        while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0 && head.size() < 65536) { "no answer" }
            head.write(byte)
        }
        val status = head.toString("ISO-8859-1").lineSequence().first()
        check(status.contains(" 101 ")) { "OpenAI refused the call: $status" }
        opened.soTimeout = 0
        writeLock.withLock {
            socket = opened
            line = plain
            output = opened.outputStream
        }
    }

    // A line that went dead without a word (out of signal) never errors on its own: one that
    // doesn't answer a ping for this long is closed, and the call reconnects.
    private fun watch(watched: SSLSocket?) {
        heardAt = System.currentTimeMillis()
        val plain = line
        Thread({
            while (running.get() && socket === watched && watched != null) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
                if (!running.get() || socket !== watched) return@Thread
                if (System.currentTimeMillis() - heardAt > 15_000) {
                    note("no answer from OpenAI for 15 s")
                    try { plain?.close() } catch (_: Exception) {}
                    return@Thread
                }
                // Busy writing (or stuck on a dead line): no ping this time.
                if (!writeLock.tryLock()) continue
                try {
                    val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
                    output?.write(byteArrayOf(0x89.toByte(), 0x80.toByte()) + mask)
                    output?.flush()
                } catch (_: Exception) {
                } finally {
                    writeLock.unlock()
                }
            }
        }, "abla-call-watch").apply { isDaemon = true; start() }
    }

    private fun write(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x81)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size < 65536 -> { frame.write(0x80 or 126); frame.write(payload.size shr 8); frame.write(payload.size and 255) }
            else -> { frame.write(0x80 or 127); for (shift in 56 downTo 0 step 8) frame.write(((payload.size.toLong() shr shift) and 255).toInt()) }
        }
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        frame.write(mask)
        for (index in payload.indices) frame.write(payload[index].toInt() xor mask[index and 3].toInt())
        writeLock.withLock {
            try {
                output?.write(frame.toByteArray())
                output?.flush()
            } catch (_: Exception) {}
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var at = 0
        while (at < count) {
            val got = input.read(bytes, at, count - at)
            check(got > 0) { "closed" }
            at += got
        }
        return bytes
    }

    private fun read() {
        val input = socket!!.inputStream
        val message = ByteArrayOutputStream()
        while (running.get()) {
            val head = readFully(input, 2)
            heardAt = System.currentTimeMillis()
            val final = head[0].toInt() and 0x80 != 0
            val opcode = head[0].toInt() and 0x0f
            var size = (head[1].toInt() and 0x7f).toLong()
            if (size == 126L) { val more = readFully(input, 2); size = ((more[0].toLong() and 255) shl 8) or (more[1].toLong() and 255) }
            else if (size == 127L) { val more = readFully(input, 8); size = 0; for (byte in more) size = (size shl 8) or (byte.toLong() and 255) }
            check(size < 64L * 1024 * 1024) { "a message too big" }
            val payload = readFully(input, size.toInt())
            when (opcode) {
                0, 1 -> {
                    message.write(payload)
                    if (final) {
                        val text = message.toString("UTF-8")
                        message.reset()
                        received(JSONObject(text))
                    }
                }
                8 -> return
                9 -> writeLock.withLock {
                    // A pong, with the ping's payload.
                    val frame = ByteArrayOutputStream()
                    frame.write(0x8a); frame.write(0x80 or payload.size)
                    val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
                    frame.write(mask)
                    for (index in payload.indices) frame.write(payload[index].toInt() xor mask[index and 3].toInt())
                    try { output?.write(frame.toByteArray()); output?.flush() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun received(event: JSONObject) {
        when (event.optString("type")) {
            "response.output_audio.delta", "response.audio.delta" -> {
                playQueue.put(event.optString("item_id") to Base64.decode(event.optString("delta"), Base64.DEFAULT))
                return
            }
            "input_audio_buffer.speech_started" -> {
                speechFrom = event.optLong("audio_start_ms")
                if (!opening) interrupted()
            }
            "response.done" -> opening = false
            "input_audio_buffer.speech_stopped" -> said(event)
        }
        if (event.optString("type") in forwarded) synchronized(events) { events.add(event) }
    }

    // ---- The speaker ----

    private fun playingMs(): Long {
        val audio = track ?: return 0
        synchronized(playLock) {
            val left = writtenFrames - (audio.playbackHeadPosition.toLong() and 0xffffffffL)
            return if (left > 0) left * 1000 / rate else 0
        }
    }

    // The person spoke over the model: stop, and tell it where they cut it off.
    private fun interrupted() {
        val audio = track ?: return
        playQueue.clear()
        synchronized(playLock) {
            val heard = audio.playbackHeadPosition.toLong() and 0xffffffffL
            if (writtenFrames - heard > rate / 10) {
                audio.pause()
                audio.flush()
                var start = 0L
                for ((item, frames) in played) {
                    if (heard < start + frames) {
                        write(JSONObject().put("type", "conversation.item.truncate").put("item_id", item)
                            .put("content_index", 0).put("audio_end_ms", (heard - start) * 1000 / rate).toString())
                        break
                    }
                    start += frames
                }
                played.clear()
                writtenFrames = 0
                audio.play()
            }
        }
    }

    // ---- The microphone ----

    private fun said(event: JSONObject) {
        val end = event.optLong("audio_end_ms") * bytesPerMs
        var from = speechFrom * bytesPerMs
        if (end - from > segmentBytes) from = end - segmentBytes
        if (from < micBytes - ringBytes) from = micBytes - ringBytes
        if (end <= from || end > micBytes) return
        val segment = ByteArray((end - from).toInt() and 1.inv())
        for (index in segment.indices) segment[index] = ring[((from + index) % ringBytes).toInt()]
        event.put("pcm", Base64.encodeToString(downsample(segment), Base64.NO_WRAP)).put("rate", 16_000)
    }

    // 24 kHz to 16 kHz: two samples for every three.
    private fun downsample(pcm: ByteArray): ByteArray {
        val samples = pcm.size / 2
        fun at(index: Int): Int = ((pcm[index * 2 + 1].toInt() shl 8) or (pcm[index * 2].toInt() and 255)).toShort().toInt()
        val out = ByteArrayOutputStream(samples * 4 / 3 + 4)
        var index = 0
        while (index * 3 / 2 < samples - 1) {
            val position = index * 3 / 2
            val value = if ((index * 3) % 2 == 1) (at(position) + at(position + 1)) / 2 else at(position)
            out.write(value and 255)
            out.write((value shr 8) and 255)
            index += 1
        }
        return out.toByteArray()
    }

    private fun startAudio() {
        val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val mic = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, frameBytes * 4))
        } catch (_: Exception) { null }
        if (mic != null && mic.state == AudioRecord.STATE_INITIALIZED) {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(mic.audioSessionId)?.enabled = true
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(mic.audioSessionId)?.enabled = true
            recorder = mic
            mic.startRecording()
            note("mic: ${describe(mic.routedDevice)}, echo canceller ${if (AcousticEchoCanceler.isAvailable()) "on" else "unavailable"}")
            mic.addOnRoutingChangedListener({ routing: AudioRecord -> note("mic now: ${describe(routing.routedDevice)}") }, android.os.Handler(android.os.Looper.getMainLooper()))
            Thread({ capture(mic) }, "abla-call-mic").apply { isDaemon = true; start() }
        } else {
            mic?.release()
            problem = "the microphone is unavailable"
        }
        val speaker = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 5 * 2))
                .build()
        } catch (_: Exception) { null }
        if (speaker != null && speaker.state == AudioTrack.STATE_INITIALIZED) {
            track = speaker
            speaker.play()
            note("speaker: ${describe(speaker.routedDevice)}, audio mode ${modeName()}${if (handled) ", a Telecom call" else ""}")
            speaker.addOnRoutingChangedListener({ routing: AudioTrack -> note("speaker now: ${describe(routing.routedDevice)}, audio mode ${modeName()}") }, android.os.Handler(android.os.Looper.getMainLooper()))
            Thread({ play(speaker) }, "abla-call-speaker").apply { isDaemon = true; start() }
        } else {
            speaker?.release()
            problem = if (problem == "") "the speaker is unavailable" else "$problem, and the speaker too"
        }
    }

    private fun capture(mic: AudioRecord) {
        val frame = ByteArray(frameBytes)
        while (running.get()) {
            var at = 0
            while (at < frame.size && running.get()) {
                val got = mic.read(frame, at, frame.size - at)
                if (got < 0) return
                at += got
            }
            // Between connections, what's said goes nowhere.
            if (output == null) continue
            for (index in frame.indices) ring[((micBytes + index) % ringBytes).toInt()] = frame[index]
            micBytes += frame.size
            write(JSONObject().put("type", "input_audio_buffer.append")
                .put("audio", Base64.encodeToString(frame, Base64.NO_WRAP)).toString())
        }
    }

    private fun play(speaker: AudioTrack) {
        while (running.get()) {
            val (item, pcm) = try { playQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            synchronized(playLock) {
                if (played.isNotEmpty() && played.last().first == item) played[played.size - 1] = item to (played.last().second + pcm.size / 2)
                else played.add(item to (pcm.size / 2).toLong())
                writtenFrames += pcm.size / 2
            }
            var at = 0
            while (at < pcm.size && running.get()) {
                val done = speaker.write(pcm, at, pcm.size - at)
                if (done <= 0) break
                at += done
            }
        }
    }
}
