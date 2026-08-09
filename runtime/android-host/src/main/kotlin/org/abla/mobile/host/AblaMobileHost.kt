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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.json.JSONObject

@Composable
fun AblaMobileHost(modifier: Modifier = Modifier) {
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
                TreeScreen(tree, modifier)
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
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = background,
        topBar = {
            if (tree.showTopBar) TopAppBar(title = { Text(tree.title) })
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
            "image" -> RenderImage(node, tagged)
            "surface" -> RenderSurface(tree, node, tagged)
            "grid" -> RenderGrid(tree, node, tagged)
            "mediaCard" -> RenderMediaCard(tree, node, tagged)
            "actionGrid" -> RenderActionGrid(tree, node, tagged)
            "webView" -> RenderWebView(node, tagged)
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
        visualTransformation = if (node.bool("secure"))
            PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = if (node.bool("secure"))
            KeyboardOptions(keyboardType = KeyboardType.Password)
        else KeyboardOptions.Default,
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
        color = mobileColor(node.text("color"), Color.Unspecified),
        fontSize = if (node.int("size") > 0) node.int("size").sp else
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
private fun RenderImage(node: UiNode, modifier: Modifier) {
    val context = LocalContext.current
    val resource = context.resources.getIdentifier(
        node.text("source"),
        "drawable",
        context.packageName,
    )
    var imageModifier = modifier
    if (node.int("height") > 0) imageModifier = imageModifier.height(node.int("height").dp)
    if (node.int("width") > 0) imageModifier = imageModifier.width(node.int("width").dp)
    if (node.int("radius") > 0) {
        imageModifier = imageModifier.clip(RoundedCornerShape(node.int("radius").dp))
    }
    if (resource == 0) {
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
    if (node.int("height") > 0) surfaceModifier = surfaceModifier.height(node.int("height").dp)
    if (node.long("event") > 0L) {
        surfaceModifier = surfaceModifier.clickable { NativeBridge.dispatch(tree, node) }
    }
    Surface(
        modifier = surfaceModifier,
        color = mobileColor(node.text("background"), MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(node.int("radius").dp),
    ) {
        Column(
            Modifier.padding(node.int("padding").dp),
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
