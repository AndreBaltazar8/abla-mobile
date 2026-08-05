package org.abla.mobile.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal object NativeBridge {
    private val mutableState = MutableStateFlow<HostState>(HostState.Loading)
    val state: StateFlow<HostState> = mutableState.asStateFlow()

    init {
        System.loadLibrary("abla_mobile_bridge")
    }

    external fun start(): Boolean

    external fun sendEvent(
        revision: Long,
        identifier: Long,
        payload: ByteArray,
    ): Boolean

    @JvmStatic
    fun onTreeFromNative(bytes: ByteArray) {
        mutableState.value = try {
            val source = bytes.toString(Charsets.UTF_8)
            HostState.Ready(UiTree.fromJson(JSONObject(source)))
        } catch (failure: Exception) {
            HostState.Failed(
                "Invalid Abla UI tree: ${failure.message ?: failure.javaClass.simpleName}",
            )
        }
    }

    fun fail(message: String) {
        mutableState.value = HostState.Failed(message)
    }

    fun dispatch(tree: UiTree, node: UiNode, payload: String = "") {
        val event = node.long("event")
        if (event <= 0L) return
        if (!sendEvent(tree.revision, event, payload.toByteArray(Charsets.UTF_8))) {
            fail("The Abla event queue is full or unavailable")
        }
    }
}

internal sealed interface HostState {
    data object Loading : HostState
    data class Ready(val tree: UiTree) : HostState
    data class Failed(val message: String) : HostState
}

internal data class UiTree(
    val revision: Long,
    val title: String,
    val darkTheme: Boolean,
    val root: UiNode,
) {
    companion object {
        fun fromJson(source: JSONObject): UiTree {
            require(source.getInt("protocol") == 1) { "unsupported protocol" }
            return UiTree(
                revision = source.getLong("revision"),
                title = source.optString("title", "Abla Mobile"),
                darkTheme = source.optBoolean("darkTheme", false),
                root = UiNode.fromJson(source.getJSONObject("root")),
            )
        }
    }
}

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
        fun fromJson(source: JSONObject): UiNode {
            val children = source.getJSONArray("children")
            return UiNode(
                type = source.getString("type"),
                key = source.getString("key"),
                properties = source.getJSONObject("properties"),
                children = buildList(children.length()) {
                    repeat(children.length()) { index ->
                        add(fromJson(children.getJSONObject(index)))
                    }
                },
            )
        }
    }
}

