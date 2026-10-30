package org.abla.mobile.host

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import org.json.JSONObject
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlin.math.roundToInt

open class AblaMobileActivity : ComponentActivity() {
    companion object {
        const val RECORD_AUDIO_REQUEST = 4701
        const val ASSISTANT_ROLE_REQUEST = 4702
        const val IMAGE_PICK_REQUEST = 4703
        private const val ASSISTANT_EVENT = 2_147_483_647L
    }

    protected open val assistantSurface = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (assistantSurface) configureAssistantWindow() else {
            enableEdgeToEdge()
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        NativeBridge.attachActivity(this)
        if (!NativeBridge.start()) {
            NativeBridge.fail(
                HostFailureKind.Startup,
                "Unable to start libabla_app.so",
            )
        }
        setContent {
            AblaMobileHost(assistantSurface = assistantSurface)
        }
        dispatchAssistantIntent(intent)
        dispatchViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        NativeBridge.attachActivity(this)
        dispatchVisibility(true)
    }

    override fun onPause() {
        dispatchVisibility(false)
        super.onPause()
    }

    private fun dispatchVisibility(active: Boolean) {
        val ready=NativeBridge.state.value as? HostState.Ready ?: return
        val payload=JSONObject().put("kind","visibility").put("active",if(active) "yes" else "no").toString().toByteArray(Charsets.UTF_8)
        NativeBridge.sendEvent(ready.tree.revision,ASSISTANT_EVENT,payload)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchAssistantIntent(intent)
        dispatchViewIntent(intent)
    }

    // Deliver incoming links as copied data. The application validates the URI
    // and decides whether any action should follow; the host never redeems it.
    private fun dispatchViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.dataString ?: return
        if (uri.length > 4096) return
        val payload = JSONObject().put("kind", "openUrl").put("url", uri)
            .toString().toByteArray(Charsets.UTF_8)
        Thread {
            repeat(100) {
                val ready = NativeBridge.state.value as? HostState.Ready
                if (ready != null) {
                    Thread.sleep(500)
                    NativeBridge.sendEvent(ready.tree.revision, ASSISTANT_EVENT, payload)
                    return@Thread
                }
                Thread.sleep(50)
            }
        }.start()
    }

    private fun dispatchAssistantIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_ASSIST && intent?.action != Intent.ACTION_VOICE_COMMAND) return
        Thread {
            repeat(100) {
                if (NativeBridge.state.value is HostState.Ready) {
                    // The native runtime reaches Ready just before the Abla screen
                    // installs its event callback. Give that hand-off one frame.
                    Thread.sleep(500)
                    NativeBridge.sendEvent(1, ASSISTANT_EVENT, "{\"kind\":\"assistant\"}".toByteArray())
                    return@Thread
                }
                Thread.sleep(50)
            }
        }.start()
    }

    protected fun dispatchAssistantDismissed() {
        NativeBridge.sendEvent(
            1,
            ASSISTANT_EVENT,
            "{\"kind\":\"assistantDismissed\"}".toByteArray(),
        )
    }

    private fun configureAssistantWindow() {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            gravity = Gravity.BOTTOM
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = (480 * resources.displayMetrics.density).roundToInt()
            dimAmount = 0.28f
        }
        setFinishOnTouchOutside(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_REQUEST) {
            NativeBridge.onRecordAudioPermissionResult(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    @Deprecated("Android activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMAGE_PICK_REQUEST) {
            NativeBridge.onImagePicked(if (resultCode == Activity.RESULT_OK) data?.data else null)
        }
        if (requestCode == ASSISTANT_ROLE_REQUEST) {
            NativeBridge.onAssistantRoleResult(resultCode == Activity.RESULT_OK)
        }
    }
}

class AblaMobileAssistantActivity : AblaMobileActivity() {
    override val assistantSurface = true

    override fun onDestroy() {
        if (isFinishing) dispatchAssistantDismissed()
        super.onDestroy()
    }
}
