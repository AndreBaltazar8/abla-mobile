package org.abla.mobile.host

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun AblaMobileHost(modifier: Modifier = Modifier) {
    val state = NativeBridge.state.collectAsState().value
    when (state) {
        HostState.Loading -> LoadingScreen(modifier)
        is HostState.Failed -> FailureScreen(state.message, modifier)
        is HostState.Ready -> {
            val tree = state.tree
            MaterialTheme(
                colorScheme = if (tree.darkTheme) darkColorScheme() else
                    lightColorScheme(),
            ) {
                TreeScreen(tree, modifier)
            }
        }
    }
}

@Composable
private fun LoadingScreen(modifier: Modifier) {
    MaterialTheme {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Starting Abla…")
            }
        }
    }
}

@Composable
private fun FailureScreen(message: String, modifier: Modifier) {
    MaterialTheme {
        Box(
            modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Abla Mobile could not start",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TreeScreen(tree: UiTree, modifier: Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(tree.title) })
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding)) {
            RenderNode(tree, tree.root, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun RenderNode(tree: UiTree, node: UiNode, modifier: Modifier = Modifier) {
    key(node.key) {
        val tagged = modifier.testTag(node.key)
        when (node.type) {
            "column" -> RenderColumn(tree, node, tagged)
            "row" -> RenderRow(tree, node, tagged)
            "text" -> RenderText(node, tagged)
            "button" -> RenderButton(tree, node, tagged)
            "textField" -> RenderTextField(tree, node, tagged)
            "switch" -> ToggleRow(
                node = node,
                modifier = tagged,
                control = {
                    Switch(
                        checked = node.bool("checked"),
                        onCheckedChange = {
                            NativeBridge.dispatch(tree, node, it.toString())
                        },
                    )
                },
                onToggle = {
                    NativeBridge.dispatch(
                        tree,
                        node,
                        (!node.bool("checked")).toString(),
                    )
                },
            )
            "checkbox" -> ToggleRow(
                node = node,
                modifier = tagged,
                control = {
                    Checkbox(
                        checked = node.bool("checked"),
                        onCheckedChange = {
                            NativeBridge.dispatch(tree, node, it.toString())
                        },
                    )
                },
                onToggle = {
                    NativeBridge.dispatch(
                        tree,
                        node,
                        (!node.bool("checked")).toString(),
                    )
                },
            )
            "slider" -> RenderSlider(tree, node, tagged)
            "progress" -> RenderProgress(node, tagged)
            "divider" -> HorizontalDivider(tagged)
            "spacer" -> Spacer(tagged.height(node.int("height", 8).dp))
            "card" -> RenderCard(tree, node, tagged)
            "chip" -> FilterChip(
                selected = node.bool("selected"),
                onClick = {
                    NativeBridge.dispatch(tree, node, node.text("payload"))
                },
                label = { Text(node.text("text")) },
                modifier = tagged,
            )
            "list" -> LazyColumn(
                modifier = tagged.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(node.children, key = { it.key }) { child ->
                    RenderNode(tree, child, Modifier.fillMaxWidth())
                }
            }
            else -> Text(
                "Unsupported node: ${node.type}",
                modifier = tagged,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RenderTextField(tree: UiTree, node: UiNode, modifier: Modifier) {
    val modelValue = node.text("value")
    var editingValue by remember(node.key) { mutableStateOf(modelValue) }
    var focused by remember(node.key) { mutableStateOf(false) }
    if (!focused && editingValue != modelValue) editingValue = modelValue

    OutlinedTextField(
        value = editingValue,
        onValueChange = {
            // Keep the IME composition responsive while every complete value
            // is sent to, and ultimately owned by, the Abla event loop.
            editingValue = it
            NativeBridge.dispatch(tree, node, it)
        },
        label = { Text(node.text("label")) },
        singleLine = true,
        modifier = modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
        },
    )
}

@Composable
private fun RenderColumn(tree: UiTree, node: UiNode, modifier: Modifier) {
    var columnModifier = modifier.padding(node.int("padding").dp)
    if (node.bool("scroll")) {
        columnModifier = columnModifier.verticalScroll(rememberScrollState())
    }
    Column(
        modifier = columnModifier,
        verticalArrangement = Arrangement.spacedBy(node.int("spacing").dp),
    ) {
        node.children.forEach { child ->
            RenderNode(tree, child, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RenderRow(tree: UiTree, node: UiNode, modifier: Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(node.int("spacing").dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        node.children.forEach { child -> RenderNode(tree, child) }
    }
}

@Composable
private fun RenderText(node: UiNode, modifier: Modifier) {
    val style = when (node.text("style")) {
        "display" -> MaterialTheme.typography.displayMedium
        "headline" -> MaterialTheme.typography.headlineMedium
        "title" -> MaterialTheme.typography.titleLarge
        "label" -> MaterialTheme.typography.labelLarge
        else -> MaterialTheme.typography.bodyLarge
    }
    Text(
        text = node.text("text"),
        modifier = modifier,
        style = style,
        fontWeight = if (node.text("style") == "headline") FontWeight.Bold else null,
    )
}

@Composable
private fun RenderButton(tree: UiTree, node: UiNode, modifier: Modifier) {
    val content: @Composable RowScope.() -> Unit = { Text(node.text("text")) }
    val onClick = { NativeBridge.dispatch(tree, node) }
    when (node.text("appearance", "filled")) {
        "outlined" -> OutlinedButton(
            onClick = onClick,
            enabled = node.bool("enabled", true),
            modifier = modifier,
            content = content,
        )
        "text" -> TextButton(
            onClick = onClick,
            enabled = node.bool("enabled", true),
            modifier = modifier,
            content = content,
        )
        else -> Button(
            onClick = onClick,
            enabled = node.bool("enabled", true),
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun ToggleRow(
    node: UiNode,
    modifier: Modifier,
    control: @Composable () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = node.bool("checked"),
                role = Role.Switch,
                onValueChange = { onToggle() },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(node.text("label"), modifier = Modifier.weight(1f))
        control()
    }
}

@Composable
private fun RenderSlider(tree: UiTree, node: UiNode, modifier: Modifier) {
    val minimum = node.int("minimum").toFloat()
    val maximum = node.int("maximum", 100).toFloat()
    Column(modifier) {
        Text(node.text("label"), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = node.int("value").toFloat().coerceIn(minimum, maximum),
            onValueChange = {
                NativeBridge.dispatch(tree, node, it.roundToInt().toString())
            },
            valueRange = minimum..maximum,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RenderProgress(node: UiNode, modifier: Modifier) {
    val maximum = node.int("maximum", 100).coerceAtLeast(1)
    val progress = (node.int("value").toFloat() / maximum).coerceIn(0f, 1f)
    if (node.bool("circular")) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LinearProgressIndicator(
            progress = { progress },
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RenderCard(tree: UiTree, node: UiNode, modifier: Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val title = node.text("title")
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            node.children.forEach { child ->
                RenderNode(tree, child, Modifier.fillMaxWidth())
            }
        }
    }
}
