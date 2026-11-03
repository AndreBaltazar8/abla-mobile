package org.abla.mobile.host

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.json.JSONObject

@Composable
fun AblaMobileHost(
    modifier: Modifier = Modifier,
    assistantSurface: Boolean = false,
) {
    HandlePlatformEffects()
    val state = NativeBridge.state.collectAsState().value
    when (state) {
        HostState.Loading -> LoadingScreen(modifier)
        is HostState.Failed -> FailureScreen(state, modifier)
        is HostState.Ready -> {
            val tree = state.tree
            MaterialTheme(
                colorScheme = if (tree.darkTheme) darkColorScheme() else
                    lightColorScheme(),
            ) {
                TreeScreen(
                    tree,
                    if (assistantSurface) {
                        modifier.clip(
                            RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        )
                    } else modifier,
                )
            }
        }
    }
}

@Composable
private fun HandlePlatformEffects() {
    val context = LocalContext.current
    val view = LocalView.current
    LaunchedEffect(context, view) {
        NativeBridge.effects.collect { effect ->
            when (effect.kind) {
                1 -> Toast.makeText(
                    context,
                    effect.payload,
                    Toast.LENGTH_SHORT,
                ).show()
                2 -> {
                    val clipboard = context.getSystemService(
                        ClipboardManager::class.java,
                    )
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("Abla Mobile", effect.payload),
                    )
                }
                3 -> {
                    val uri = Uri.parse(effect.payload)
                    if (uri.scheme == "https" || uri.scheme == "http") {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        } catch (_: Exception) {
                            Toast.makeText(
                                context,
                                "No app can open this link",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            "Only http and https links are allowed",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                4 -> view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                5 -> try {
                    NativeBridge.playAudio(effect.payload)
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not play the spoken result", Toast.LENGTH_SHORT).show()
                }
                6 -> NativeBridge.stopAudio()
                8, 9 -> try {
                    NativeBridge.exportFile(effect.payload, effect.kind == 9)
                } catch (_: Exception) {
                    HostFileExports.failed(effect.payload)
                    Toast.makeText(context, "Could not export file", Toast.LENGTH_SHORT).show()
                }
                7 -> try {
                    NativeBridge.downloadArtifact(context, effect.payload)
                    Toast.makeText(context, "Downloading artifact", Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {
                    Toast.makeText(context, "Could not download artifact", Toast.LENGTH_SHORT).show()
                }
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
private fun FailureScreen(failure: HostState.Failed, modifier: Modifier) {
    val title = when (failure.kind) {
        HostFailureKind.Startup -> "Abla Mobile could not start"
        HostFailureKind.Protocol -> "Abla Mobile protocol failed"
        HostFailureKind.RuntimePanic -> "Abla app stopped"
        HostFailureKind.UnexpectedReturn -> "Abla app exited"
        HostFailureKind.Platform -> "Abla Mobile failed"
    }
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
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        failure.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TreeScreen(tree: UiTree, modifier: Modifier) {
    val background = mobileColor(tree.background, MaterialTheme.colorScheme.background)
    val drawn = remember(tree.revision) { booleanArrayOf(false) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = background,
        topBar = {
            if (tree.showTopBar) TopAppBar(title = { Text(tree.title) })
        },
    ) { contentPadding ->
        Box(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).drawWithContent {
            drawContent()
            if (!drawn[0]) {
                drawn[0] = true
                NativeBridge.traceLatency("draw")
            }
        }) {
            // An Abla One tree says which route entry it belongs to: state saved in it
            // (scroll positions) stays with the entry, so a new one opens at the top
            // and going back finds it as it was. While the app has entries to go back
            // to, the system's back button is the app's.
            val entry = tree.root.text("a.one.entry")
            BackHandler(enabled = tree.root.text("a.one.back") == "true") {
                NativeBridge.sendEvent(tree.revision, 2_147_483_647L, "{\"kind\":\"back\"}".toByteArray())
            }
            val saved = rememberSaveableStateHolder()
            saved.SaveableStateProvider(entry) {
                RenderNode(tree, tree.root, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun RenderNode(tree: UiTree, node: UiNode, modifier: Modifier = Modifier) {
    key(node.key) {
        val tagged = modifier.testTag(node.key)
        if (node.type.startsWith("one.")) RenderOne(tree, node, tagged)
        else when (node.type) {
            "input" -> RenderInput(tree,node,tagged)
            "appScaffold" -> RenderAppScaffold(tree,node,tagged)
            "theme" -> {
                val base = MaterialTheme.colorScheme
                MaterialTheme(colorScheme = base.copy(
                    primary = mobileColor(node.text("primary"), base.primary),
                    onPrimary = mobileColor(node.text("onPrimary"), base.onPrimary),
                    secondary = mobileColor(node.text("primary"), base.secondary),
                    surface = mobileColor(node.text("surface"), base.surface),
                    onSurface = mobileColor(node.text("onSurface"), base.onSurface),
                    onSurfaceVariant = mobileColor(node.text("muted"), base.onSurfaceVariant),
                    outline = mobileColor(node.text("outline"), base.outline),
                )) { node.children.firstOrNull()?.let { RenderNode(tree, it, tagged) } }
            }
            "column" -> RenderColumn(tree, node, tagged)
            "chatList" -> RenderChatList(tree,node,tagged)
            "richText" -> RenderRichText(node,tagged)
            "bottomScaffold" -> RenderBottomScaffold(tree, node, tagged)
            "row" -> RenderRow(tree, node, tagged)
            "text" -> RenderText(node, tagged)
            "button" -> RenderButton(tree, node, tagged)
            "textField" -> RenderTextField(tree, node, tagged)
            "dropdown" -> RenderDropdown(tree, node, tagged)
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
            "spacer" -> Spacer(tagged.height(node.int("height", 8).dp).then(if(node.int("width")>0) Modifier.width(node.int("width").dp) else Modifier))
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
            "image" -> RenderImage(node, tagged)
            "surface" -> RenderSurface(tree, node, tagged)
            "grid" -> RenderGrid(tree, node, tagged)
            "mediaCard" -> RenderMediaCard(tree, node, tagged)
            "actionGrid" -> RenderActionGrid(tree, node, tagged)
            "webView" -> RenderWebView(node, tagged)
            "nativeGame" -> RenderNativeGame(node, tagged)
            "pulseBadge" -> RenderPulseBadge(node, tagged)
            else -> Text(
                "Unsupported node: ${node.type}",
                modifier = tagged,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RenderBottomScaffold(tree: UiTree, node: UiNode, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = mobileColor(node.text("background"), MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            node.children.firstOrNull()?.let { content ->
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    RenderNode(tree, content, Modifier.fillMaxSize())
                }
            }
            node.children.getOrNull(1)?.let { bottomBar ->
                RenderNode(tree, bottomBar, Modifier.fillMaxWidth())
            }
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
        label = if (node.bool("placeholder")) null else ({ Text(node.text("label")) }),
        placeholder = if (node.bool("placeholder")) ({ Text(node.text("label")) }) else null,
        shape = RoundedCornerShape(node.int("radius", 4).dp),
        singleLine = !node.bool("multiline"),
        maxLines = if (node.bool("multiline")) 4 else 1,
        visualTransformation = if (node.bool("secure"))
            PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (node.bool("secure"))
            KeyboardOptions(keyboardType = KeyboardType.Password)
        else if (node.text("keyboard") == "number") KeyboardOptions(keyboardType = KeyboardType.Number)
        else if (node.text("keyboard") == "email") KeyboardOptions(keyboardType = KeyboardType.Email)
        else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth().onFocusChanged {
            focused = it.isFocused
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenderDropdown(tree: UiTree, node: UiNode, modifier: Modifier) {
    val options = node.text("options").split('|').filter { it.isNotEmpty() }
    val enabled = node.bool("enabled", true) && options.isNotEmpty()
    val selected = node.text("value").takeIf { it in options }
        ?: options.firstOrNull().orEmpty()
    var expanded by remember(node.key) { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = {
            if (enabled) expanded = !expanded
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(node.text("label")) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        NativeBridge.dispatch(tree, node, option)
                    },
                )
            }
        }
    }
}

@Composable
private fun RenderColumn(tree: UiTree, node: UiNode, modifier: Modifier) {
    var columnModifier = modifier
    if (node.bool("scroll")) {
        // A scrolling child must own a finite viewport. Without fillMaxSize a
        // column nested in a Surface can expand to its complete content and be
        // clipped by the screen, leaving verticalScroll nothing to scroll.
        columnModifier = columnModifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
    }
    columnModifier = columnModifier.padding(node.int("padding").dp)
    Column(
        modifier = columnModifier,
        verticalArrangement = Arrangement.spacedBy(node.int("spacing").dp),
    ) {
        node.children.forEach { child ->
            RenderNode(
                tree,
                child,
                if (node.bool("compact")) Modifier else Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RenderRow(tree: UiTree, node: UiNode, modifier: Modifier) {
    val arrangement = when (node.text("arrangement")) {
        "spaceBetween" -> Arrangement.SpaceBetween
        "center" -> Arrangement.Center
        "end" -> Arrangement.End
        else -> Arrangement.spacedBy(node.int("spacing").dp)
    }
    var rowModifier = modifier.padding(node.int("padding").dp)
    if (node.bool("fullWidth", true)) rowModifier = rowModifier.fillMaxWidth()
    Row(
        modifier = rowModifier,
        horizontalArrangement = arrangement,
        verticalAlignment = if(node.text("alignment")=="top") Alignment.Top else Alignment.CenterVertically,
    ) {
        node.children.forEach { child ->
            RenderNode(tree, child, if(child.int("layoutWeight") > 0)
                Modifier.weight(child.int("layoutWeight").toFloat(), fill=child.bool("layoutFill",true)) else Modifier)
        }
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
        color = mobileColor(node.text("color"), Color.Unspecified),
        fontSize = if (node.int("size") > 0) node.int("size").sp else
            androidx.compose.ui.unit.TextUnit.Unspecified,
        lineHeight = if (node.int("lineHeight") > 0) node.int("lineHeight").sp else
            androidx.compose.ui.unit.TextUnit.Unspecified,
        fontWeight = when (node.text("weight")) {
            "bold" -> FontWeight.Bold
            "semibold" -> FontWeight.SemiBold
            "medium" -> FontWeight.Medium
            else -> if (node.text("style") == "headline") FontWeight.Bold else null
        },
        textAlign = when (node.text("align")) {
            "center" -> TextAlign.Center
            "end" -> TextAlign.End
            else -> TextAlign.Start
        },
        maxLines = if (node.int("maxLines") > 0) node.int("maxLines") else Int.MAX_VALUE,
    )
}

@Composable
internal fun RenderImage(node: UiNode, modifier: Modifier) {
    val context = LocalContext.current
    val source = node.text("source")
    val authorization = node.text("authorization")
    val remote by produceState<android.graphics.Bitmap?>(if(source.startsWith("https://")) RemoteImageCache.peek(source,authorization) else null, source, authorization) {
        if(source.startsWith("https://")) {
            value=RemoteImageCache.peek(source,authorization)
            value=RemoteImageCache.get(source,authorization)
        }
    }
    val resource = context.resources.getIdentifier(
        if (source.startsWith("https://")) node.text("fallback") else source,
        "drawable",
        context.packageName,
    )
    var imageModifier = modifier
    if (node.int("height") > 0) imageModifier = imageModifier.height(node.int("height").dp)
    if (node.int("width") > 0) imageModifier = imageModifier.width(node.int("width").dp)
    if (node.int("radius") > 0) {
        imageModifier = imageModifier.clip(RoundedCornerShape(node.int("radius").dp))
    }
    if (remote != null) {
        Box(imageModifier, contentAlignment = Alignment.Center) {
            Image(bitmap = remote!!.asImageBitmap(), contentDescription = node.text("description").ifEmpty { null },
                modifier = Modifier.size(node.int("width").dp, node.int("height").dp).clip(RoundedCornerShape(node.int("radius").dp)), contentScale = if(node.text("scale")=="fit") ContentScale.Fit else ContentScale.Crop)
        }
    } else if (resource == 0 && (source.isEmpty() || source.startsWith("https://"))) {
        Box(imageModifier)
    } else if (resource == 0) {
        Box(
            imageModifier.background(Color(0xFF2A2344)),
            contentAlignment = Alignment.Center,
        ) { Text("?", color = Color.White) }
    } else {
        Image(
            painter = painterResource(resource),
            contentDescription = node.text("description").ifEmpty { null },
            modifier = imageModifier,
            contentScale = when (node.text("scale")) {
                "fit" -> ContentScale.Fit
                "fill" -> ContentScale.FillBounds
                else -> ContentScale.Crop
            },
        )
    }
}

@Composable
private fun RenderSurface(tree: UiTree, node: UiNode, modifier: Modifier) {
    var surfaceModifier = modifier
    if (node.bool("compact")) surfaceModifier = surfaceModifier.wrapContentWidth()
    if (node.int("width") > 0) surfaceModifier = surfaceModifier.width(node.int("width").dp)
    if (node.int("height") > 0) surfaceModifier = surfaceModifier.height(node.int("height").dp)
    if (node.long("event") > 0L) {
        surfaceModifier = surfaceModifier.clip(RoundedCornerShape(node.int("radius").dp)).clickable(enabled = node.bool("enabled", true)) { NativeBridge.dispatch(tree, node) }
    }
    Surface(
        modifier = surfaceModifier,
        color = mobileColor(node.text("background"), MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(node.int("radius").dp),
    ) {
        Column(
            Modifier.then(if(node.bool("centerContent")) Modifier.fillMaxSize() else Modifier).padding(node.int("padding").dp),
            verticalArrangement = if(node.bool("centerContent")) Arrangement.Center else Arrangement.spacedBy(node.int("spacing").dp),
            horizontalAlignment = if(node.bool("centerContent")) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            node.children.forEach { child ->
                RenderNode(
                    tree,
                    child,
                    if (node.bool("compact")) Modifier else Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun RenderGrid(tree: UiTree, node: UiNode, modifier: Modifier) {
    val columns = node.int("columns", 2).coerceIn(1, 6)
    val spacing = node.int("spacing", 12).dp
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        node.children.chunked(columns).forEach { rowChildren ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowChildren.forEach { child ->
                    RenderNode(tree, child, Modifier.weight(1f))
                }
                repeat(columns - rowChildren.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RenderMediaCard(tree: UiTree, node: UiNode, modifier: Modifier) {
    Surface(
        modifier = modifier.clickable { NativeBridge.dispatch(tree, node) },
        color = Color(0xFF19132B),
        shape = RoundedCornerShape(node.int("radius", 18).dp),
    ) {
        Box(Modifier.height(node.int("height", 190).dp)) {
            val imageNode = UiNode(
                type = "image",
                key = "${node.key}-image",
                properties = JSONObject().put("source", node.text("source"))
                    .put("description", node.text("title"))
                    .put("height", node.int("height", 190))
                    .put("radius", node.int("radius", 18)),
                children = emptyList(),
            )
            RenderImage(imageNode, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.35f to Color.Transparent,
                        1f to Color(0xF20D0718),
                    ),
                ),
            )
            Column(
                Modifier.align(Alignment.BottomStart).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    node.text("title"),
                    color = Color(0xFFFFF8FF),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                val subtitle = node.text("subtitle")
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, color = Color(0xFFD0C4E8), fontSize = 11.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RenderWebView(node: UiNode, modifier: Modifier) {
    val source = node.text("source")
    val safeSource = source.takeIf {
        it.matches(Regex("[a-zA-Z0-9_/-]+\\.html")) && !it.contains("..")
    }
    if (safeSource == null) {
        Box(
            modifier.height(node.int("height", 480).dp)
                .background(mobileColor(node.text("background"), Color(0xFF120D20))),
            contentAlignment = Alignment.Center,
        ) { Text("Game asset unavailable", color = Color(0xFFA99CC4)) }
        return
    }
    val assetUrl = "file:///android_asset/$safeSource"
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.rgb(18, 13, 32))
                    cornerRadius = node.int("radius", 22) *
                        context.resources.displayMetrics.density
                }
                clipToOutline = true
                setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isFocusable = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowContentAccess = false
                settings.allowFileAccess = true
                settings.offscreenPreRaster = true
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = true
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                settings.setSupportZoom(false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !request.url.toString().startsWith(
                        "file:///android_asset/games/",
                    )

                    override fun onPageFinished(view: WebView, url: String) {
                        view.postVisualStateCallback(
                            0L,
                            object : WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    view.invalidate()
                                }
                            },
                        )
                    }
                }
                tag = assetUrl
                loadUrl(assetUrl)
                onResume()
            }
        },
        update = { view ->
            if (view.tag != assetUrl) {
                view.tag = assetUrl
                view.loadUrl(assetUrl)
            }
        },
        modifier = modifier.height(node.int("height", 480).dp),
        onRelease = { it.destroy() },
    )
}

@Composable
private fun RenderPulseBadge(node: UiNode, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "${node.key}-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${node.key}-alpha",
    )
    Surface(
        modifier = modifier.wrapContentWidth(),
        color = mobileColor(node.text("background"), Color(0xFF2A1936)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(9.dp).height(9.dp)
                    .graphicsLayer { scaleX = pulse; scaleY = pulse }
                    .background(
                        mobileColor(node.text("accent"), Color(0xFFFF78AE))
                            .copy(alpha = pulse),
                        CircleShape,
                    ),
            )
            Text(
                node.text("text", "LIVE"),
                color = mobileColor(node.text("color"), Color(0xFFFF78AE)),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RenderActionGrid(tree: UiTree, node: UiNode, modifier: Modifier) {
    val labels = node.text("labels").split('|').filter { it.isNotEmpty() }
    val columns = node.int("columns", 3).coerceIn(1, 6)
    val spacing = node.int("spacing", 10).dp
    val palette = listOf(
        Color(0xFF4B2B83), Color(0xFF173B57), Color(0xFF5A2546), Color(0xFF5A3A21),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        labels.chunked(columns).forEachIndexed { rowIndex, rowLabels ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                rowLabels.forEachIndexed { columnIndex, label ->
                    val index = rowIndex * columns + columnIndex
                    Surface(
                        modifier = Modifier.weight(1f).height(node.int("height", 82).dp)
                            .clickable { NativeBridge.dispatch(tree, node, index.toString()) },
                        color = palette[index % palette.size],
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(label, color = Color(0xFFFFF8FF), fontSize = 25.sp,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
                repeat(columns - rowLabels.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun mobileColor(source: String, fallback: Color): Color {
    if (!source.startsWith("#")) return fallback
    return try {
        Color(android.graphics.Color.parseColor(source))
    } catch (_: IllegalArgumentException) {
        fallback
    }
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

@Composable
private fun RenderInput(tree: UiTree, node: UiNode, modifier: Modifier) {
    val model = node.text("value")
    var editing by remember(node.key) { mutableStateOf(model) }
    var focused by remember(node.key) { mutableStateOf(false) }
    if (!focused && editing != model) editing = model
    // Server acknowledgement can clear a draft while the keyboard remains open.
    LaunchedEffect(model) { if (model.isEmpty()) editing = "" }
    val controller = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val code = node.text("keyboard") == "number"
    val background = mobileColor(node.text("background"), Color.White)
    val shape = RoundedCornerShape(node.int("radius", 10).dp)
    androidx.compose.foundation.text.BasicTextField(
        value = editing,
        onValueChange = { raw ->
            val next = if (code) raw.filter { it in '0'..'9' }.take(node.int("maxLength",8)) else raw.take(node.int("maxLength",1500))
            editing = next
            NativeBridge.dispatch(tree,node,next)
        },
        enabled = node.bool("enabled",true),
        textStyle = androidx.compose.ui.text.TextStyle(
            color = Color(0xFF172D28), fontSize = node.int("size",16).sp,
            lineHeight = 24.sp,
            letterSpacing = if(code) 5.sp else 0.sp,
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF203E37)),
        singleLine = !node.bool("multiline"),
        maxLines = if(node.bool("multiline")) 4 else 1,
        visualTransformation = if(node.bool("secure")) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if(code) KeyboardType.Number else if(node.bool("secure")) KeyboardType.Password else if(node.text("keyboard")=="email") KeyboardType.Email else KeyboardType.Text,
            imeAction = if(node.long("submitEvent")>0) androidx.compose.ui.text.input.ImeAction.Done else androidx.compose.ui.text.input.ImeAction.Default,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
            if(node.long("submitEvent")>0) {
                controller?.hide()
                NativeBridge.sendEvent(tree.revision,node.long("submitEvent"),ByteArray(0))
            }
        }),
        modifier = modifier.fillMaxWidth()
            .heightIn(min=node.int("height",50).dp)
            .clip(shape).background(background)
            .then(if(node.bool("border",true)) Modifier.border(1.dp,if(focused) Color(0xFF41695A) else Color(0xFFDDE4DF),shape) else Modifier)
            .onFocusChanged { focused=it.isFocused }
            .semantics { contentDescription=node.text("label") },
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal=14.dp,vertical=node.int("paddingY",12).dp),contentAlignment=Alignment.CenterStart) {
                if(editing.isEmpty()) Text(node.text("label"),color=Color(0xFF84918B),fontSize=node.int("size",16).sp)
                inner()
            }
        },
    )
}

@Composable
private fun RenderAppScaffold(tree: UiTree,node: UiNode,modifier: Modifier) {
    Column(modifier.fillMaxSize().imePadding().background(mobileColor(node.text("background"),Color.White))) {
        node.children.getOrNull(0)?.let { RenderNode(tree,it,Modifier.fillMaxWidth()) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            node.children.getOrNull(1)?.let { RenderNode(tree,it,Modifier.fillMaxSize()) }
        }
        node.children.getOrNull(2)?.let { RenderNode(tree,it,Modifier.fillMaxWidth()) }
    }
}

// Stable message keys preserve the viewport through polling and edits. Read receipts
// come only from fully visible rows while the activity is resumed.
@Composable
private fun RenderChatList(tree: UiTree,node: UiNode,modifier: Modifier) {
    val state=androidx.compose.foundation.lazy.rememberLazyListState()
    var positioned by remember(node.key) { mutableStateOf(false) }
    var sentRead by remember(node.key) { mutableStateOf(0) }
    var previousCount by remember(node.key) { mutableStateOf(0) }
    val latestNode by androidx.compose.runtime.rememberUpdatedState(node)
    val latestTree by androidx.compose.runtime.rememberUpdatedState(tree)
    val lifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(node.key,node.children.size) {
        if(!positioned && node.children.any { it.int("messageId")>0 }) {
            val unread=node.int("lastRead")
            val index=node.children.indexOfFirst { it.int("messageId")>unread }
            state.scrollToItem(if(node.bool("unread") && index>=0) index else (node.children.size-1).coerceAtLeast(0))
            positioned=true
        } else if(positioned && node.children.size>previousCount && state.layoutInfo.visibleItemsInfo.lastOrNull()?.index==previousCount-1) {
            state.scrollToItem(node.children.lastIndex)
        }
        previousCount=node.children.size
    }
    LaunchedEffect(node.key,tree.revision) {
        kotlinx.coroutines.delay(220)
        if(positioned && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            val layout=state.layoutInfo
            val last=layout.visibleItemsInfo.filter { it.offset>=layout.viewportStartOffset && it.offset+it.size<=layout.viewportEndOffset }.maxOfOrNull { node.children.getOrNull(it.index)?.int("messageId")?:0 }?:0
            if(last>node.int("lastRead") && last>sentRead) {sentRead=last;NativeBridge.dispatch(tree,node,last.toString())}
        }
    }
    LaunchedEffect(node.key) {
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect {
            kotlinx.coroutines.delay(180)
            if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                val layout=state.layoutInfo
                val last=layout.visibleItemsInfo.filter { it.offset>=layout.viewportStartOffset && it.offset+it.size<=layout.viewportEndOffset }.maxOfOrNull { latestNode.children.getOrNull(it.index)?.int("messageId")?:0 }?:0
                if(last>latestNode.int("lastRead") && last>sentRead) {sentRead=last;NativeBridge.dispatch(latestTree,latestNode,last.toString())}
            }
        }
    }
    LazyColumn(modifier.fillMaxSize(),state=state,contentPadding=androidx.compose.foundation.layout.PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(node.children,key={it.key}) { RenderNode(tree,it,Modifier.fillMaxWidth()) }
    }
}
@Composable
private fun RenderRichText(node: UiNode,modifier: Modifier) {
    val parts=try { org.json.JSONArray(node.text("parts")) } catch (_: Exception) { org.json.JSONArray() }
    val inline=mutableMapOf<String,androidx.compose.foundation.text.InlineTextContent>()
    val text=androidx.compose.ui.text.buildAnnotatedString {
        for(i in 0 until parts.length()) {
            val part=parts.getJSONObject(i)
            if(part.optString("kind")=="emoji") {
                val id="emoji-$i"
                pushStringAnnotation("androidx.compose.foundation.text.inlineContent",id)
                append(part.optString("text"));pop()
                inline[id]=androidx.compose.foundation.text.InlineTextContent(androidx.compose.ui.text.Placeholder(22.sp,22.sp,androidx.compose.ui.text.PlaceholderVerticalAlign.Center)) {
                    RenderImage(UiNode("image",id,JSONObject().put("source",node.text("base")+part.optString("key")).put("authorization",node.text("authorization")).put("description",part.optString("text")).put("width",22).put("height",22),emptyList()),Modifier.size(22.dp))
                }
            } else {
                if(part.optString("kind")=="mention") pushStyle(androidx.compose.ui.text.SpanStyle(color=Color(0xFF286953),fontWeight=FontWeight.SemiBold))
                append(part.optString("text"))
                if(part.optString("kind")=="mention") pop()
            }
        }
    }
    Text(text,modifier,color=Color(0xFF172D28),fontSize=15.sp,lineHeight=21.sp,inlineContent=inline)
}
