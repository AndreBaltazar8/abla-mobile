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

// A voice call (abla:call) whose other side runs elsewhere, on the app's own computer: this
// is its microphone and speaker, here on the phone. One WebSocket to a relay (`relay`, with
// the app's `token`) carries 16 kHz mono 16-bit audio in binary frames both ways, and a
// little JSON in text frames ({"t":"note"} from here, {"t":"end"} from there). The
// microphone has the platform's echo cancelling and noise suppression. A dropped line is
// joined again from here, to the same stream, while the call goes on there.
internal object CallSession {
    private const val rate = 16_000
    private const val bytesPerMs = rate / 500
    private const val frameBytes = rate / 25 * 2
    // The relay's URL (wss://…/call-audio?stream=…) and the app's token.
    @Volatile private var relay = ""
    @Volatile private var relayToken = ""
    // How far behind the speaker may fall before quiet audio is skipped to catch up.
    private const val behindMs = 400L

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
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    // Frames handed to the speaker since the last flush.
    private var writtenFrames = 0L
    private val playLock = Any()
    // When the relay last sent anything; it sends audio all the time.
    @Volatile private var heardAt = 0L
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
        if (output != null) return write(JSONObject().put("t", "note").put("text", text).toString())
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
    fun start(context: Context, relayUrl: String, token: String, speaker: Boolean, title: String = "On a call", caller: String = "") {
        end(false)
        relay = relayUrl
        relayToken = token
        problem = ""
        hungUp = false
        hungUpWhy = ""
        state = "connecting"
        synchronized(events) { events.clear() }
        playQueue.clear()
        synchronized(playLock) { writtenFrames = 0 }
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
        Thread({ run() }, "abla-call").apply { isDaemon = true; start() }
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

    // Hangs up: at once, or once what the other side said has been heard.
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
    // the phone joins the relay's stream again; the call is still on at the computer.
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
            writtenFrames = 0
        }
        problem = "the connection dropped: ${why ?: "closed"}"
        if (droppedAt == 0L) droppedAt = System.currentTimeMillis()
        state = "reconnecting"
        note("$problem; reconnecting")
        rejoin()
    }

    // Joins the relay's stream again in a moment, as many times as it takes.
    private fun rejoin() {
        Thread({
            try { Thread.sleep(2_000) } catch (_: InterruptedException) {}
            if (running.get() && state == "reconnecting") {
                state = "connecting again"
                run(again = true)
            }
        }, "abla-call").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stop(final: String) {
        if (!running.get()) return
        // The computer hears it at once, not when the app next asks.
        if (output != null) write(JSONObject().put("t", "end").put("why", if (hungUpWhy != "") "André hung up ($hungUpWhy)" else if (final == "failed") problem else "André hung up").toString())
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

    private fun run(again: Boolean = false) {
        try {
            connect()
        } catch (failure: Exception) {
            if (!again) return fail("couldn't reach the relay: ${failure.message}")
            if (!running.get()) return
            problem = "couldn't reconnect: ${failure.message}"
            state = "reconnecting"
            note(problem)
            // It tries again by itself, until the guard gives up on it.
            rejoin()
            return
        }
        if (!running.get()) {
            writeLock.withLock { try { socket?.close() } catch (_: Exception) {} }
            return
        }
        if (again) {
            problem = ""
            note("reconnected")
        }
        droppedAt = 0L
        state = "live"
        if (!again) startAudio()
        watch(socket)
        try {
            read()
        } catch (failure: Exception) {
            if (running.get()) return dropped(failure.message)
        }
        // The relay closing the line isn't the call ending: only the computer says that ({"t":"end"}).
        if (running.get()) dropped("the relay closed the line")
    }

    // ---- The WebSocket ----

    private fun connect() {
        val target = java.net.URI(relay)
        val host = target.host
        val port = if (target.port > 0) target.port else 443
        val where = "${target.rawPath}${if (target.rawQuery != null) "?${target.rawQuery}" else ""}"
        val bearer = relayToken
        // The plain socket under TLS is kept, to cut a dead line without waiting on TLS.
        val plain = java.net.Socket()
        plain.connect(java.net.InetSocketAddress(host, port), 10_000)
        val opened = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
        opened.soTimeout = 15_000
        opened.startHandshake()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val request = "GET $where HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\n" +
            "Connection: Upgrade\r\nSec-WebSocket-Key: ${Base64.encodeToString(nonce, Base64.NO_WRAP)}\r\n" +
            "Sec-WebSocket-Version: 13\r\nAuthorization: Bearer $bearer\r\n\r\n"
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
        check(status.contains(" 101 ")) { "the relay refused the call: $status" }
        opened.soTimeout = 0
        writeLock.withLock {
            socket = opened
            line = plain
            output = opened.outputStream
        }
    }

    // A line that went dead without a word (out of signal) never errors on its own: the relay's
    // carries audio all the time, so a few seconds of nothing is a dead line, closed so the
    // call reconnects.
    private fun watch(watched: SSLSocket?) {
        heardAt = System.currentTimeMillis()
        val plain = line
        Thread({
            val patience = 6_000L
            while (running.get() && socket === watched && watched != null) {
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { return@Thread }
                if (!running.get() || socket !== watched) return@Thread
                if (System.currentTimeMillis() - heardAt > patience) {
                    note("no answer from the relay for ${patience / 1000} s")
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

    private fun write(text: String) = writeFrame(text.toByteArray(Charsets.UTF_8), 0x81)

    private fun writeFrame(payload: ByteArray, first: Int) {
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(first)
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
                // The computer's voice.
                2 -> playQueue.put(payload)
                0, 1 -> {
                    message.write(payload)
                    if (final) {
                        val text = message.toString("UTF-8")
                        message.reset()
                        val event = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                        if (event.optString("t") == "end") {
                            problem = event.optString("why")
                            stop("ended")
                            return
                        }
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

    // ---- The speaker ----

    private fun playingMs(): Long {
        val audio = track ?: return 0
        synchronized(playLock) {
            val left = writtenFrames - (audio.playbackHeadPosition.toLong() and 0xffffffffL)
            return if (left > 0) left * 1000 / rate else 0
        }
    }

    // ---- The microphone ----

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
            writeFrame(frame, 0x82)
        }
    }

    // How loud a stretch of 16-bit audio is: the largest sample.
    private fun peak(pcm: ByteArray): Int {
        var most = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val value = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 255)).toShort().toInt()
            val size = if (value < 0) -value else value
            if (size > most) most = size
            index += 2
        }
        return most
    }

    private fun play(speaker: AudioTrack) {
        while (running.get()) {
            val pcm = try { playQueue.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
            // The relay's line is a steady stream: when the network held some back and it comes
            // in a burst, the speaker falls behind for good unless it skips the quiet bits.
            if (playingMs() + playQueue.sumOf { it.size } / bytesPerMs > behindMs && peak(pcm) < 600) continue
            synchronized(playLock) { writtenFrames += pcm.size / 2 }
            var at = 0
            while (at < pcm.size && running.get()) {
                val done = speaker.write(pcm, at, pcm.size - at)
                if (done <= 0) break
                at += done
            }
        }
    }
}
