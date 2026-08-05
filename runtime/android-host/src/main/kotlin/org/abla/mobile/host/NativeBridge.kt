package org.abla.mobile.host

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal object NativeBridge {
    private val mutableState = MutableStateFlow<HostState>(HostState.Loading)
    val state: StateFlow<HostState> = mutableState.asStateFlow()
    private val mutableEffects = MutableSharedFlow<HostEffect>(
        extraBufferCapacity = 32,
    )
    val effects: SharedFlow<HostEffect> = mutableEffects.asSharedFlow()

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

    @JvmStatic
    fun onEffectFromNative(kind: Int, bytes: ByteArray) {
        if (bytes.size > 4096 || kind !in 1..4) {
            fail("Invalid Abla platform effect")
            return
        }
        if (!mutableEffects.tryEmit(HostEffect(kind, bytes.toString(Charsets.UTF_8)))) {
            fail("The Abla platform effect queue is full")
        }
    }

    @JvmStatic
    fun onFailureFromNative(bytes: ByteArray) {
        val detail = bytes.toString(Charsets.UTF_8).take(1024)
        fail(if (detail.isEmpty()) "The native Abla program failed" else detail)
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

internal data class HostEffect(val kind: Int, val payload: String)

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
            val revision = source.getLong("revision")
            val title = source.optString("title", "Abla Mobile")
            require(revision > 0L) { "revision must be positive" }
            require(title.length <= 256) { "title is too long" }
            return UiTree(
                revision = revision,
                title = title,
                darkTheme = source.getBoolean("darkTheme"),
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
            "column",
            "row",
            "text",
            "button",
            "textField",
            "switch",
            "checkbox",
            "slider",
            "progress",
            "divider",
            "spacer",
            "card",
            "chip",
            "list",
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
                    require(value.length <= 16384) { "property text is too long" }
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
