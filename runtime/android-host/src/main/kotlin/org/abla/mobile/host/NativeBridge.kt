package org.abla.mobile.host

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Environment
import android.service.voice.VoiceInteractionService
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object NativeBridge {
    @Volatile private var pendingImageRequest = 0L
    private const val HTTP_COMPLETION_EVENT = -2L
    private const val HTTP_COMPLETION_LIMIT = 512 * 1024
    // Generic app responses can contain bounded server-owned histories and
    // artifact metadata. Keep the bridge bounded, but large enough for those
    // legitimate JSON snapshots.
    private const val HTTP_RESPONSE_LIMIT = 384 * 1024
    private val requestExecutor = ThreadPoolExecutor(
        4,
        4,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { task -> Thread(task, "abla-mobile-http").apply { isDaemon = true } },
    )
    private val timerExecutor = ScheduledThreadPoolExecutor(
        1,
        { task -> Thread(task, "abla-mobile-timer").apply { isDaemon = true } },
    ).apply { removeOnCancelPolicy = true }
    private val mutableState = MutableStateFlow<HostState>(HostState.Loading)
    @Volatile private var applicationContext: Context? = null
    @Volatile private var activity: WeakReference<AblaMobileActivity>? = null
    @Volatile private var pendingVoicePermissionRequest = 0L
    @Volatile private var pendingVoiceMaxSeconds = 180
    @Volatile private var pendingAssistantRoleRequest = 0L
    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var downloadReceiverRegistered = false
    private val artifactMimeTypes = ConcurrentHashMap<Long, String>()
    private val artifactDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val mimeType = artifactMimeTypes.remove(id) ?: return
            if (mimeType != "application/vnd.android.package-archive") return
            openDownloadedApk(context, id)
        }
    }
    val state: StateFlow<HostState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<HostEffect>(
        extraBufferCapacity = 32,
    )
    val effects: SharedFlow<HostEffect> = mutableEffects.asSharedFlow()

    init {
        System.loadLibrary("abla_mobile_bridge")
    }

    fun attachContext(context: Context) {
        val appContext = context.applicationContext
        applicationContext = appContext
        registerDownloadReceiver(appContext)
    }

    fun attachActivity(value: AblaMobileActivity) {
        activity = WeakReference(value)
        attachContext(value.applicationContext)
    }

    external fun start(): Boolean

    external fun sendEvent(
        revision: Long,
        identifier: Long,
        payload: ByteArray,
    ): Boolean

    @JvmStatic
    fun onTreeFromNative(bytes: ByteArray) {
        traceLatency("tree")
        mutableState.value = try {
            val source = bytes.toString(Charsets.UTF_8)
            HostState.Ready(UiTree.fromJson(JSONObject(source)))
        } catch (failure: Exception) {
            HostState.Failed(
                HostFailureKind.Protocol,
                "Invalid Abla UI tree: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    @JvmStatic
    fun onEffectFromNative(kind: Int, bytes: ByteArray) {
        if (bytes.size > 4096 || kind !in 1..7) {
            fail("Invalid Abla platform effect")
            return
        }
        if (!mutableEffects.tryEmit(HostEffect(kind, bytes.toString(Charsets.UTF_8)))) {
            fail("The Abla platform effect queue is full")
        }
    }

    fun downloadArtifact(context: Context, payload: String) {
        val parts = payload.split('\n', limit = 3)
        require(parts.size == 3) { "invalid artifact download" }
        val (url, name, mimeType) = parts
        val uri = URI(url)
        require(uri.scheme == "https" || uri.scheme == "http") { "invalid artifact URL" }
        require(name.isNotEmpty() && name.length <= 160 &&
            name.none { it == '/' || it == '\\' || it == '\n' || it == '\r' }) {
            "invalid artifact name"
        }
        require(mimeType.isNotEmpty() && mimeType.length <= 128) { "invalid artifact type" }
        val request = DownloadManager.Request(android.net.Uri.parse(url))
            .setTitle(name)
            .setDescription("Coder Auto artifact")
            .setMimeType(mimeType)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, name)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        artifactMimeTypes[id] = mimeType
    }

    @Synchronized
    private fun registerDownloadReceiver(context: Context) {
        if (downloadReceiverRegistered) return
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                artifactDownloadReceiver,
                filter,
                // DownloadManager runs in the DownloadsProvider package, not
                // under the system UID, so its completion broadcast requires
                // an exported runtime receiver. Unknown ids are ignored.
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(artifactDownloadReceiver, filter)
        }
        downloadReceiverRegistered = true
    }

    private fun openDownloadedApk(context: Context, id: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val manager = context.getSystemService(DownloadManager::class.java)
        val downloaded = manager.getUriForDownloadedFile(id) ?: return
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(downloaded, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    @JvmStatic
    fun onHttpRequestFromNative(
        requestId: Long,
        methodBytes: ByteArray,
        urlBytes: ByteArray,
        bodyBytes: ByteArray,
    ) {
        traceLatency("request")
        val method = methodBytes.toString(Charsets.UTF_8)
        val url = urlBytes.toString(Charsets.UTF_8)
        val secureMethod = method == "STOREGET" || method == "STORESET" ||
            method == "STOREDEL"
        val voiceMethod = method == "VOICEON" || method == "VOICEOFF" ||
            method == "VOICEUP" ||
            method == "ASSIST" || method == "TIMER" || method == "PICKIMG" || method == "PICKEMJ" || method == "IMGCLEAR"
        if (requestId <= 0L ||
            (method != "GET" && method != "POST" && !secureMethod && !voiceMethod) ||
            urlBytes.size > 2048 || bodyBytes.size > 16384
        ) {
            completeHttp(requestId, 0, "", "invalid HTTP request")
            return
        }
        if (voiceMethod) {
            when (method) {
                "VOICEON" -> startVoiceCapture(requestId, url)
                "VOICEOFF" -> cancelVoiceCapture(requestId)
                "VOICEUP" -> submitVoiceCapture(requestId, url, bodyBytes)
                "ASSIST" -> requestAssistantRole(requestId)
                "PICKIMG" -> pickProfileImage(requestId)
                "PICKEMJ" -> pickProfileImage(requestId, true)
                "IMGCLEAR" -> { RemoteImageCache.clear();completeHttp(requestId,204,"","") }
                else -> scheduleTimer(requestId, url)
            }
            return
        }
        if (secureMethod) {
            val context = applicationContext
                ?: return completeHttp(requestId, 0, "", "secure storage is unavailable")
            try {
                requestExecutor.execute {
                    try {
                        when (method) {
                            "STOREGET" -> {
                                val value = SecureStore.get(context, url)
                                if (value == null) completeHttp(requestId, 404, "", "")
                                else completeHttp(requestId, 200, value, "")
                            }
                            "STORESET" -> {
                                SecureStore.set(context, url, bodyBytes.toString(Charsets.UTF_8))
                                completeHttp(requestId, 204, "", "")
                            }
                            else -> {
                                SecureStore.delete(context, url)
                                completeHttp(requestId, 204, "", "")
                            }
                        }
                    } catch (failure: Exception) {
                        completeHttp(
                            requestId,
                            0,
                            "",
                            failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                }
            } catch (_: RejectedExecutionException) {
                completeHttp(requestId, 0, "", "platform worker queue is full")
            }
            return
        }
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            completeHttp(requestId, 0, "", "invalid URL")
            return
        }
        if (uri.scheme != "https" && uri.scheme != "http") {
            completeHttp(requestId, 0, "", "only http and https are allowed")
            return
        }

        try {
            requestExecutor.execute {
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = method
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept", "text/plain")
                    if (method == "POST") {
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bodyBytes.size)
                        connection.setRequestProperty(
                            "Content-Type",
                            "text/plain; charset=utf-8",
                        )
                        connection.outputStream.use { it.write(bodyBytes) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    val response = stream?.use {
                        it.readLimited(HTTP_RESPONSE_LIMIT)
                    } ?: ByteArray(0)
                    if (response.size > HTTP_RESPONSE_LIMIT) {
                        completeHttp(requestId, status, "", "response is too large")
                    } else {
                        completeHttp(
                            requestId,
                            status,
                            response.toString(Charsets.UTF_8),
                            "",
                        )
                    }
                } catch (failure: Exception) {
                    completeHttp(
                        requestId,
                        0,
                        "",
                        failure.message ?: failure.javaClass.simpleName,
                    )
                } finally {
                    connection?.disconnect()
                }
            }
        } catch (_: RejectedExecutionException) {
            completeHttp(requestId, 0, "", "HTTP worker queue is full")
        }
    }

    private var pendingEmoji = false
    private fun pickProfileImage(requestId: Long, emoji: Boolean = false) {
        val owner = activity?.get() ?: return completeHttp(requestId, 0, "", "picker unavailable")
        if (pendingImageRequest != 0L) return completeHttp(requestId, 409, "", "picker already open")
        pendingEmoji = emoji
        pendingImageRequest = requestId
        owner.runOnUiThread {
            try {
                owner.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    AblaMobileActivity.IMAGE_PICK_REQUEST,
                )
            } catch (_: Exception) {
                pendingImageRequest = 0L
                completeHttp(requestId, 0, "", "picker unavailable")
            }
        }
    }

    fun onImagePicked(uri: Uri?) {
        val emoji = pendingEmoji
        val requestId = pendingImageRequest
        pendingImageRequest = 0L
        if (requestId == 0L) return
        if (uri == null) return completeHttp(requestId, 204, "", "")
        val context = applicationContext ?: return completeHttp(requestId, 0, "", "picker unavailable")
        try { requestExecutor.execute {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(20 * 1024 * 1024) }
                    ?: error("unreadable image")
                require(bytes.size <= 20 * 1024 * 1024)
                val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                        require(info.size.width > 0 && info.size.height > 0 && info.size.width.toLong() * info.size.height <= 50_000_000)
                        val factor = maxOf(info.size.width, info.size.height) / 512.0
                        if (factor > 1) decoder.setTargetSize(maxOf(1, (info.size.width / factor).toInt()), maxOf(1, (info.size.height / factor).toInt()))
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 50_000_000)
                    bounds.inJustDecodeBounds = false
                    bounds.inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) ?: error("invalid image")
                }
                var result = byteArrayOf()
                if (emoji) {
                    for (size in listOf(128, 96, 64, 48)) {
                        val square = android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
                        square.setHasAlpha(true)
                        val scale = minOf(size.toFloat()/bitmap.width,size.toFloat()/bitmap.height)
                        val w=bitmap.width*scale; val h=bitmap.height*scale
                        android.graphics.Canvas(square).drawBitmap(bitmap,null,android.graphics.RectF((size-w)/2,(size-h)/2,(size+w)/2,(size+h)/2),android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        val out=ByteArrayOutputStream(); square.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out)
                        result=out.toByteArray();square.recycle()
                        if(result.size<=10000) break
                    }
                    bitmap.recycle()
                } else {
                val square = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(square)
                canvas.drawColor(android.graphics.Color.WHITE)
                val edge = minOf(bitmap.width, bitmap.height)
                val left = (bitmap.width - edge) / 2; val top = (bitmap.height - edge) / 2
                canvas.drawBitmap(bitmap, android.graphics.Rect(left, top, left + edge, top + edge), android.graphics.Rect(0, 0, 256, 256), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                bitmap.recycle()
                result = byteArrayOf()
                for (quality in listOf(85, 70, 55, 40, 25, 10)) {
                    val out = ByteArrayOutputStream()
                    square.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)
                    result = out.toByteArray()
                    if (result.size <= 10000) break
                }
                square.recycle()
                }
                require(result.size <= 10000)
                completeHttp(requestId, 200, android.util.Base64.encodeToString(result, android.util.Base64.NO_WRAP), "")
            } catch (_: Exception) { completeHttp(requestId, 422, "", "invalid image") }
        } } catch (_: RejectedExecutionException) { completeHttp(requestId, 0, "", "worker unavailable") }
    }

    private fun scheduleTimer(requestId: Long, value: String) {
        val milliseconds = value.toLongOrNull()
        if (milliseconds == null || milliseconds !in 100L..60_000L) {
            return completeHttp(requestId, 0, "", "timer must be 100 to 60000 milliseconds")
        }
        try {
            timerExecutor.schedule(
                { completeHttp(requestId, 200, "timer", "") },
                milliseconds,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: RejectedExecutionException) {
            completeHttp(requestId, 0, "", "timer queue is unavailable")
        }
    }

    private fun startVoiceCapture(requestId: Long, limitValue: String) {
        val context = applicationContext
            ?: return completeHttp(requestId, 0, "", "voice capture is unavailable")
        val maxSeconds = limitValue.toIntOrNull()
        if (maxSeconds == null || maxSeconds !in 10..1_800) {
            return completeHttp(requestId, 0, "", "voice limit must be 10 to 1800 seconds")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            try {
                VoiceCapture.start(context, maxSeconds)
                completeHttp(requestId, 200, "recording", "")
            } catch (failure: Exception) {
                completeHttp(requestId, 0, "", failure.message ?: "could not start recording")
            }
            return
        }
        val owner = activity?.get()
            ?: return completeHttp(requestId, 0, "", "the activity is unavailable")
        if (pendingVoicePermissionRequest != 0L) {
            return completeHttp(requestId, 0, "", "a microphone permission request is active")
        }
        pendingVoicePermissionRequest = requestId
        pendingVoiceMaxSeconds = maxSeconds
        owner.runOnUiThread {
            ActivityCompat.requestPermissions(
                owner,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AblaMobileActivity.RECORD_AUDIO_REQUEST,
            )
        }
    }

    fun onRecordAudioPermissionResult(granted: Boolean) {
        val requestId = pendingVoicePermissionRequest
        val maxSeconds = pendingVoiceMaxSeconds
        pendingVoicePermissionRequest = 0L
        pendingVoiceMaxSeconds = 180
        if (requestId == 0L) return
        if (!granted) return completeHttp(requestId, 403, "", "microphone permission denied")
        startVoiceCapture(requestId, maxSeconds.toString())
    }

    private fun cancelVoiceCapture(requestId: Long) {
        VoiceCapture.cancel()
        completeHttp(requestId, 204, "", "")
    }

    private fun submitVoiceCapture(requestId: Long, url: String, bodyBytes: ByteArray) {
        val context = applicationContext
            ?: return completeHttp(requestId, 0, "", "voice capture is unavailable")
        val settings = try {
            JSONObject(bodyBytes.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            return completeHttp(requestId, 0, "", "invalid voice upload settings")
        }
        val repository = settings.optString("repository")
        val token = settings.optString("token")
        if (repository.isEmpty() || repository.length > 128 || token.length !in 16..512) {
            return completeHttp(requestId, 0, "", "invalid voice upload settings")
        }
        val audio = try {
            VoiceCapture.stop()
        } catch (failure: Exception) {
            return completeHttp(requestId, 0, "", failure.message ?: "could not stop recording")
        }
        try {
            requestExecutor.execute {
                var connection: HttpURLConnection? = null
                try {
                    val uri = URI(url)
                    require(uri.scheme == "https" || uri.scheme == "http")
                    val boundary = "abla-${UUID.randomUUID()}"
                    connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = false
                    connection.doOutput = true
                    connection.setChunkedStreamingMode(32 * 1024)
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    connection.outputStream.buffered().use { output ->
                        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"repository\"\r\n\r\n$repository\r\n".toByteArray())
                        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"prompt.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
                        audio.inputStream().use { it.copyTo(output, 32 * 1024) }
                        output.write("\r\n--$boundary--\r\n".toByteArray())
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val response = stream?.use { it.readLimited(HTTP_RESPONSE_LIMIT) } ?: ByteArray(0)
                    if (response.size > HTTP_RESPONSE_LIMIT) {
                        completeHttp(requestId, status, "", "response is too large")
                    } else {
                        completeHttp(requestId, status, response.toString(Charsets.UTF_8), "")
                    }
                } catch (failure: Exception) {
                    completeHttp(requestId, 0, "", failure.message ?: failure.javaClass.simpleName)
                } finally {
                    connection?.disconnect()
                    audio.delete()
                }
            }
        } catch (_: RejectedExecutionException) {
            audio.delete()
            completeHttp(requestId, 0, "", "platform worker queue is full")
        }
    }

    private fun requestAssistantRole(requestId: Long) {
        val owner = activity?.get()
            ?: return completeHttp(requestId, 0, "", "the activity is unavailable")
        val manager = owner.getSystemService(RoleManager::class.java)
        if (!manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            return completeHttp(requestId, 404, "", "assistant role is unavailable")
        }
        if (manager.isRoleHeld(RoleManager.ROLE_ASSISTANT) && assistantServiceActive(owner)) {
            return completeHttp(requestId, 200, "assistant", "")
        }
        if (manager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            return openAssistantSettings(owner, requestId)
        }
        if (pendingAssistantRoleRequest != 0L) {
            return completeHttp(requestId, 0, "", "an assistant role request is active")
        }
        pendingAssistantRoleRequest = requestId
        owner.runOnUiThread {
            owner.startActivityForResult(
                manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT),
                AblaMobileActivity.ASSISTANT_ROLE_REQUEST,
            )
        }
    }

    fun onAssistantRoleResult(granted: Boolean) {
        val requestId = pendingAssistantRoleRequest
        pendingAssistantRoleRequest = 0L
        if (requestId == 0L) return
        val owner = activity?.get()
            ?: return completeHttp(requestId, 0, "", "the activity is unavailable")
        if (granted && assistantServiceActive(owner)) {
            return completeHttp(requestId, 200, "assistant", "")
        }
        openAssistantSettings(owner, requestId)
    }

    private fun assistantServiceActive(context: Context): Boolean =
        VoiceInteractionService.isActiveService(
            context,
            ComponentName(context, AblaVoiceInteractionService::class.java),
        )

    private fun openAssistantSettings(owner: Activity, requestId: Long) {
        try {
            owner.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            completeHttp(requestId, 202, "settings", "")
        } catch (failure: Exception) {
            completeHttp(requestId, 403, "", failure.message ?: "open assistant settings manually")
        }
    }

    fun playAudio(url: String) {
        val uri = URI(url)
        require(uri.scheme == "https" || uri.scheme == "http")
        stopAudio()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnCompletionListener { player ->
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
            }
            setOnErrorListener { player, _, _ ->
                player.release()
                if (mediaPlayer === player) mediaPlayer = null
                true
            }
            prepareAsync()
        }
    }

    fun stopAudio() {
        val player = mediaPlayer
        mediaPlayer = null
        player?.setOnPreparedListener(null)
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        player?.release()
    }

    private fun completeHttp(
        requestId: Long,
        status: Int,
        body: String,
        error: String,
    ) {
        val completion = try {
            JSONObject()
                .put("requestId", requestId)
                .put("status", status)
                .put("body", body)
                .put("error", error.take(512))
                .toString()
                .toByteArray(Charsets.UTF_8)
        } catch (_: JSONException) {
            return fail("Could not encode an HTTP completion")
        }
        if (completion.size > HTTP_COMPLETION_LIMIT) {
            return completeHttp(requestId, 0, "", "response is too large")
        }
        // Platform completions are revision-independent serialized events.
        // Revision one is valid after the initial tree has been published.
        if (!sendEvent(1, HTTP_COMPLETION_EVENT, completion)) {
            fail("The Abla HTTP completion queue is full or unavailable")
        }
    }

    private fun InputStream.readLimited(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 1024))
        val buffer = ByteArray(1024)
        var total = 0
        while (total <= limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit + 1 - total))
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    @JvmStatic
    fun onFailureFromNative(kind: Int, bytes: ByteArray) {
        val detail = bytes.toString(Charsets.UTF_8).take(1024)
        fail(
            HostFailureKind.fromNative(kind),
            if (detail.isEmpty()) "The native Abla program failed" else detail,
        )
    }

    fun fail(message: String) {
        fail(HostFailureKind.Platform, message)
    }

    fun fail(kind: HostFailureKind, message: String) {
        mutableState.value = HostState.Failed(kind, message.take(1024))
    }

    internal fun traceLatency(stage: String) {
        if ((applicationContext?.applicationInfo?.flags ?: 0) and 2 != 0)
            android.util.Log.d("AblaLatency", stage)
    }

    fun dispatch(tree: UiTree, node: UiNode, payload: String = "") {
        traceLatency("dispatch ${node.type} ${node.long("event")}")
        val event = node.long("event")
        if (event <= 0L) return
        if (!sendEvent(tree.revision, event, payload.toByteArray(Charsets.UTF_8))) {
            fail("The Abla event queue is full or unavailable")
        }
    }
}

internal data class HostEffect(val kind: Int, val payload: String)

internal enum class HostFailureKind {
    Startup,
    Protocol,
    RuntimePanic,
    UnexpectedReturn,
    Platform;

    companion object {
        fun fromNative(value: Int): HostFailureKind = when (value) {
            1 -> Startup
            2 -> Protocol
            3 -> RuntimePanic
            4 -> UnexpectedReturn
            else -> Platform
        }
    }
}

internal sealed interface HostState {
    data object Loading : HostState
    data class Ready(val tree: UiTree) : HostState
    data class Failed(
        val kind: HostFailureKind,
        val message: String,
    ) : HostState
}

internal data class UiTree(
    val revision: Long,
    val title: String,
    val darkTheme: Boolean,
    val showTopBar: Boolean,
    val background: String,
    val root: UiNode,
) {
    companion object {
        fun fromJson(source: JSONObject): UiTree {
            require(source.getInt("protocol") == 1) { "unsupported protocol" }
            val revision = source.getLong("revision")
            val title = source.optString("title", "Abla Mobile")
            require(revision > 0L) { "revision must be positive" }
            require(title.length <= 256) { "title is too long" }
            return UiTree(
                revision = revision,
                title = title,
                darkTheme = source.getBoolean("darkTheme"),
                showTopBar = source.optBoolean("showTopBar", true),
                background = source.optString("background", ""),
                root = UiNode.fromJson(
                    source.getJSONObject("root"),
                    TreeBudget(),
                    0,
                ),
            )
        }
    }
}

internal class TreeBudget(var remainingNodes: Int = 4096)

internal data class UiNode(
    val type: String,
    val key: String,
    val properties: JSONObject,
    val children: List<UiNode>,
) {
    fun text(name: String, fallback: String = ""): String =
        properties.optString(name, fallback)

    fun long(name: String, fallback: Long = 0L): Long =
        properties.optLong(name, fallback)

    fun int(name: String, fallback: Int = 0): Int =
        properties.optInt(name, fallback)

    fun bool(name: String, fallback: Boolean = false): Boolean =
        properties.optBoolean(name, fallback)

    companion object {
        private val supportedTypes = setOf(
            "theme",
            "input",
            "appScaffold",
            "chatList",
            "richText",
            "column",
            "bottomScaffold",
            "row",
            "text",
            "button",
            "textField",
            "dropdown",
            "switch",
            "checkbox",
            "slider",
            "progress",
            "divider",
            "spacer",
            "card",
            "chip",
            "list",
            "image",
            "surface",
            "grid",
            "mediaCard",
            "actionGrid",
            "webView",
            "nativeGame",
            "pulseBadge",
        )

        fun fromJson(
            source: JSONObject,
            budget: TreeBudget,
            depth: Int,
        ): UiNode {
            require(depth <= 64) { "tree nesting exceeds 64 levels" }
            require(budget.remainingNodes-- > 0) { "tree exceeds 4096 nodes" }
            val type = source.getString("type")
            val key = source.getString("key")
            require(type in supportedTypes) { "unsupported node type: $type" }
            require(key.isNotEmpty() && key.length <= 128) {
                "node key must contain 1..128 characters"
            }
            val properties = source.getJSONObject("properties")
            require(properties.length() <= 32) { "node has too many properties" }
            properties.keys().forEach { name ->
                require(name.length <= 64) { "property name is too long" }
                val value = properties.get(name)
                require(value !is JSONObject && value !is org.json.JSONArray) {
                    "node properties must be scalar"
                }
                if (value is String) {
                    require(value.length <= if(type=="richText" && name=="parts") 65536 else 16384) { "property text is too long" }
                }
            }
            val children = source.getJSONArray("children")
            require(children.length() <= 1024) { "node has too many children" }
            val childKeys = mutableSetOf<String>()
            val parsedChildren = buildList(children.length()) {
                repeat(children.length()) { index ->
                    val child = fromJson(
                        children.getJSONObject(index),
                        budget,
                        depth + 1,
                    )
                    require(childKeys.add(child.key)) {
                        "duplicate sibling key: ${child.key}"
                    }
                    add(child)
                }
            }
            return UiNode(
                type = type,
                key = key,
                properties = properties,
                children = parsedChildren,
            )
        }
    }
}
