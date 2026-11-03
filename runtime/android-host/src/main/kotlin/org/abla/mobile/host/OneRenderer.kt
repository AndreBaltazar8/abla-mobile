package org.abla.mobile.host

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import org.json.JSONArray
import org.json.JSONObject

// Generic renderer for Abla One nodes (`one.*`). Properties are the resolved
// style for the android target ("gap", "pressed:background", ...) plus node
// attributes prefixed with "a." ("a.text", "a.onPress", ...). Nothing here is
// application specific; see abla-one/SPEC.md §6-§8.

private class OneProps(val node: UiNode) {
    fun s(name: String): String = node.text(name)
    fun has(name: String): Boolean = node.properties.has(name)
    fun i(name: String, fallback: Int = 0): Int = s(name).toIntOrNull() ?: fallback
    fun a(name: String): String = node.text("a.$name")
    fun ai(name: String, fallback: Int = 0): Int = a(name).toIntOrNull() ?: fallback
    fun color(name: String): Color? = oneColor(s(name))
    fun stateColor(name: String, pressed: Boolean, focused: Boolean, disabled: Boolean): Color? =
        (if (disabled) color("disabled:$name") else null)
            ?: (if (pressed) color("pressed:$name") else null)
            ?: (if (focused) color("focus:$name") else null)
            ?: (if (a("selected") == "true") color("selected:$name") else null)
            ?: color(name)
    fun pad(side: String): Dp = i("padding$side", i("padding")).dp
}

internal fun oneColor(source: String): Color? {
    if (!source.startsWith("#")) return null
    return try {
        Color(android.graphics.Color.parseColor(source))
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun oneWeight(value: String): FontWeight? = when (value) {
    "bold" -> FontWeight.Bold
    "semibold" -> FontWeight.SemiBold
    "medium" -> FontWeight.Medium
    "regular", "normal" -> FontWeight.Normal
    else -> value.toIntOrNull()?.let { FontWeight(it) }
}

private fun oneFont(value: String): FontFamily? = when (value) {
    "" -> null
    "serif" -> FontFamily.Serif
    "mono", "monospace" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

private fun oneHorizontal(value: String): Alignment.Horizontal = when (value) {
    "center" -> Alignment.CenterHorizontally
    "end" -> Alignment.End
    else -> Alignment.Start
}

private fun oneVertical(value: String, fallback: Alignment.Vertical): Alignment.Vertical = when (value) {
    "center" -> Alignment.CenterVertically
    "end" -> Alignment.Bottom
    "start" -> Alignment.Top
    else -> fallback
}

private fun oneArrangementV(justify: String, gap: Dp): Arrangement.Vertical = when (justify) {
    "between" -> Arrangement.SpaceBetween
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterVertically)
    "end" -> Arrangement.spacedBy(gap, Alignment.Bottom)
    else -> Arrangement.spacedBy(gap)
}

private fun oneArrangementH(justify: String, gap: Dp): Arrangement.Horizontal = when (justify) {
    "between" -> Arrangement.SpaceBetween
    "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    "end" -> Arrangement.spacedBy(gap, Alignment.End)
    else -> Arrangement.spacedBy(gap)
}

private fun oneSend(tree: UiTree, event: Int, payload: String = "") {
    if (event <= 0) return
    if (!NativeBridge.sendEvent(tree.revision, event.toLong(), payload.toByteArray(Charsets.UTF_8))) {
        NativeBridge.fail("The Abla event queue is full or unavailable")
    }
}

// A field's change carries its whole value, so one still queued for the same
// tree can be replaced: fast typing can't fill the queue or outrun the app.
private fun oneSendChange(tree: UiTree, event: Int, value: String) {
    if (event <= 0) return
    if (!NativeBridge.sendChange(tree.revision, event.toLong(), value.toByteArray(Charsets.UTF_8))) {
        NativeBridge.fail("The Abla event queue is full or unavailable")
    }
}

// Digits and the first decimal separator, which may be either one.
private fun oneDecimal(raw: String): String {
    var separator = false
    return raw.filter { c ->
        if (c == ',' || c == '.') !separator.also { separator = true } else c in '0'..'9'
    }
}

// Size modifiers that don't depend on the parent's scope.
private fun Modifier.oneSize(p: OneProps): Modifier {
    var m = this
    p.s("width").toIntOrNull()?.let { m = m.width(it.dp) }
    p.s("height").toIntOrNull()?.let { m = m.height(it.dp) }
    if (p.has("minWidth") || p.has("maxWidth")) m = m.widthIn(
        min = if (p.has("minWidth")) p.i("minWidth").dp else Dp.Unspecified,
        max = if (p.has("maxWidth")) p.i("maxWidth").dp else Dp.Unspecified,
    )
    if (p.has("minHeight") || p.has("maxHeight")) m = m.heightIn(
        min = if (p.has("minHeight")) p.i("minHeight").dp else Dp.Unspecified,
        max = if (p.has("maxHeight")) p.i("maxHeight").dp else Dp.Unspecified,
    )
    return m
}

// Box decoration in CSS order: shadow, clip, background, border, then padding
// is applied by the caller after the click target.
private fun Modifier.oneBox(p: OneProps, pressed: Boolean = false, focused: Boolean = false): Modifier {
    var m = this
    val radius = p.i("radius")
    val shape = RoundedCornerShape(radius.dp)
    if (p.has("opacity")) m = m.alpha(p.i("opacity", 100) / 100f)
    if (p.i("elevation") > 0) m = m.shadow(p.i("elevation").dp, shape, clip = false)
    if (radius > 0 || p.s("clip") == "true") m = m.clip(shape)
    val disabled = p.a("enabled") == "false"
    p.stateColor("background", pressed, focused, disabled)?.let { m = m.background(it, shape) }
    val borderColor = p.stateColor("borderColor", pressed, focused, disabled) ?: Color.Transparent
    if (p.i("border") > 0) m = m.border(p.i("border").dp, borderColor, shape)
    if (p.i("borderBottom") > 0) m = m.oneEdge(p.i("borderBottom").dp, borderColor, "bottom")
    if (p.i("borderTop") > 0) m = m.oneEdge(p.i("borderTop").dp, borderColor, "top")
    if (p.i("borderLeft") > 0) m = m.oneEdge(p.i("borderLeft").dp, borderColor, "left")
    if (p.i("borderRight") > 0) m = m.oneEdge(p.i("borderRight").dp, borderColor, "right")
    return m
}

private fun Modifier.oneEdge(width: Dp, color: Color, side: String): Modifier = this.drawBehind {
    val stroke = width.toPx()
    when (side) {
        "top" -> drawRect(color, topLeft = Offset(0f, 0f), size = Size(size.width, stroke))
        "bottom" -> drawRect(color, topLeft = Offset(0f, size.height - stroke), size = Size(size.width, stroke))
        "left" -> drawRect(color, topLeft = Offset(0f, 0f), size = Size(stroke, size.height))
        else -> drawRect(color, topLeft = Offset(size.width - stroke, 0f), size = Size(stroke, size.height))
    }
}

private fun Modifier.onePadding(p: OneProps): Modifier =
    this.padding(PaddingValues(start = p.pad("Left"), top = p.pad("Top"), end = p.pad("Right"), bottom = p.pad("Bottom")))

// Whether the node being rendered has a definite width. CSS `stretch` fills a
// definite container but never widens a hug-width one; Compose's fillMaxWidth
// would, so children only stretch inside containers whose width is definite.
private val LocalOneDefinite = androidx.compose.runtime.compositionLocalOf { true }

// A child's own alignSelf comes first, as CSS's align-self over align-items: one aligned to the start, centre or end keeps its
// own width, as in a browser, rather than fill the column with its content placed in the middle.
private fun oneStretches(child: UiNode): Boolean {
    val p = OneProps(child)
    val self = p.s("alignSelf")
    if (self.isNotEmpty() && self != "stretch") return false
    return p.s("width").isEmpty() && child.type != "one.icon" && child.type != "one.image" && child.type != "one.bitmap" && child.type != "one.spacer"
}

private fun oneDefiniteInColumn(child: UiNode, parentDefinite: Boolean, stretch: Boolean): Boolean {
    val width = OneProps(child).s("width")
    return width.toIntOrNull() != null || (parentDefinite && (width == "fill" || (stretch && oneStretches(child))))
}

private fun oneDefiniteInRow(child: UiNode, parentDefinite: Boolean): Boolean {
    val p = OneProps(child)
    return p.s("width").toIntOrNull() != null || (parentDefinite && (p.i("grow") > 0 || p.s("width") == "fill"))
}

// Child modifiers that need the parent's scope (fill, grow, alignSelf).
private fun ColumnScope.oneChild(child: UiNode, stretch: Boolean, definite: Boolean): Modifier {
    val p = OneProps(child)
    var m: Modifier = Modifier
    val grow = p.i("grow")
    if (grow > 0 || p.s("height") == "fill") m = m.weight(if (grow > 0) grow.toFloat() else 1f, fill = true)
    if (definite && (p.s("width") == "fill" || (stretch && oneStretches(child)))) m = m.fillMaxWidth()
    when (p.s("alignSelf")) {
        "center" -> m = m.align(Alignment.CenterHorizontally)
        "end" -> m = m.align(Alignment.End)
        "start" -> m = m.align(Alignment.Start)
        "stretch" -> m = m.fillMaxWidth()
    }
    return m
}

private fun RowScope.oneChild(child: UiNode): Modifier {
    val p = OneProps(child)
    var m: Modifier = Modifier
    val grow = p.i("grow")
    if (grow > 0 || p.s("width") == "fill") m = m.weight(if (grow > 0) grow.toFloat() else 1f, fill = true)
    if (p.s("height") == "fill") m = m.fillMaxHeight()
    when (p.s("alignSelf")) {
        "center" -> m = m.align(Alignment.CenterVertically)
        "end" -> m = m.align(Alignment.Bottom)
        "start" -> m = m.align(Alignment.Top)
    }
    return m
}

@Composable
internal fun RenderOne(tree: UiTree, node: UiNode, modifier: Modifier) {
    val p = OneProps(node)
    when (node.type) {
        "one.column", "one.row", "one.scrollRow", "one.stack", "one.scroll", "one.pressable", "one.link" -> OneContainer(tree, node, p, modifier)
        "one.text" -> OneText(node, p, modifier)
        "one.rich" -> OneRich(node, p, modifier)
        "one.icon" -> OneIcon(p, modifier)
        "one.image" -> OneImage(node, p, modifier)
        "one.bitmap" -> OneBitmap(p, modifier)
        "one.popover" -> OnePopover(tree, node, p, modifier)
        "one.input" -> OneInput(tree, node, p, modifier)
        "one.divider" -> Box(modifier.oneSize(p).fillMaxWidth().height(p.i("height", 1).dp).background(p.color("background") ?: Color(0x1F000000)))
        "one.spacer" -> Spacer(
            if (p.has("width") || p.has("height") || p.has("grow")) modifier.oneSize(p)
            else modifier.size(p.ai("size").dp)
        )
        "one.feed" -> OneFeed(tree, node, p, modifier)
        "one.native" -> OneNative(tree, node, p, modifier)
        else -> Text("Unsupported node: ${node.type}", modifier, color = Color.Red)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OneContainer(tree: UiTree, node: UiNode, p: OneProps, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press = p.ai("onPress")
    val enabled = p.a("enabled") != "false"
    var m = modifier.oneSize(p)
    if (p.s("safeBottom") == "true") m = m.imePadding()
    // Links to the web open in the browser; in-page anchors have no meaning here.
    val href = p.a("href")
    val opens = node.type == "one.link" && (href.startsWith("https://") || href.startsWith("http://"))
    val uri = LocalUriHandler.current
    m = m.oneBox(p, pressed = pressed && (press > 0 || opens))
    if (press > 0 || opens) {
        m = m.clickable(
            interactionSource = interaction,
            indication = if (p.s("ripple") == "none" || p.has("pressed:background")) null else LocalIndication.current,
            enabled = enabled,
        ) {
            oneSend(tree, press)
            if (opens) runCatching { uri.openUri(href) }
        }
    }
    if (p.a("label").isNotEmpty()) m = m.semantics { contentDescription = p.a("label") }
    if (node.type == "one.scroll") m = m.verticalScroll(rememberScrollState())
    if (node.type == "one.scrollRow") m = m.horizontalScroll(rememberScrollState())
    m = m.onePadding(p)
    val gap = p.i("gap").dp
    val align = p.s("align")
    val definite = LocalOneDefinite.current
    when (node.type) {
        "one.scrollRow" -> Row(m, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            node.children.forEach { child -> androidx.compose.runtime.key(child.key) { RenderOne(tree, child, Modifier) } }
        }
        "one.row" -> if (p.s("wrap") == "true") {
            FlowRow(m, horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                node.children.forEach { child -> androidx.compose.runtime.key(child.key) { RenderOne(tree, child, Modifier) } }
            }
        } else Row(m, horizontalArrangement = oneArrangementH(p.s("justify"), gap), verticalAlignment = oneVertical(align, Alignment.CenterVertically)) {
            node.children.forEach { child ->
                androidx.compose.runtime.key(child.key) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalOneDefinite provides oneDefiniteInRow(child, definite)) {
                        RenderOne(tree, child, oneChild(child))
                    }
                }
            }
        }
        "one.stack" -> Box(m, contentAlignment = when (align) {
            "center" -> Alignment.Center
            "end" -> Alignment.BottomEnd
            "topEnd" -> Alignment.TopEnd
            "bottomStart" -> Alignment.BottomStart
            else -> Alignment.TopStart
        }) {
            node.children.forEach { child -> androidx.compose.runtime.key(child.key) { RenderOne(tree, child, Modifier) } }
        }
        else -> Column(m, verticalArrangement = oneArrangementV(p.s("justify"), gap), horizontalAlignment = oneHorizontal(align)) {
            val stretch = align.isEmpty() || align == "stretch"
            node.children.forEach { child ->
                androidx.compose.runtime.key(child.key) {
                    androidx.compose.runtime.CompositionLocalProvider(LocalOneDefinite provides oneDefiniteInColumn(child, definite, stretch)) {
                        RenderOne(tree, child, oneChild(child, stretch, definite))
                    }
                }
            }
        }
    }
}

private fun oneTextStyle(p: OneProps, fallbackSize: Int = 15): TextStyle {
    val size = p.i("size", fallbackSize)
    return TextStyle(
        color = p.color("color") ?: Color(0xFF111111),
        fontSize = size.sp,
        fontWeight = oneWeight(p.s("weight")),
        fontFamily = oneFont(p.s("font")),
        fontStyle = if (p.s("italic") == "true") FontStyle.Italic else null,
        lineHeight = if (p.has("lineHeight")) p.i("lineHeight").sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        letterSpacing = if (p.has("tracking")) (p.i("tracking") / 100f).sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        textAlign = when (p.s("textAlign")) {
            "center" -> TextAlign.Center
            "end", "right" -> TextAlign.End
            else -> TextAlign.Start
        },
    )
}

@Composable
private fun OneText(node: UiNode, p: OneProps, modifier: Modifier) {
    val lines = p.i("maxLines", Int.MAX_VALUE)
    Text(
        text = p.a("text"),
        modifier = modifier.oneSize(p).oneBox(p).onePadding(p),
        style = oneTextStyle(p),
        maxLines = lines,
        overflow = if (lines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

@Composable
private fun OneRich(node: UiNode, p: OneProps, modifier: Modifier) {
    val runs = try { JSONArray(p.a("runs")) } catch (_: Exception) { JSONArray() }
    val inline = mutableMapOf<String, InlineTextContent>()
    val mention = p.color("mentionColor") ?: p.color("color") ?: Color(0xFF111111)
    val emojiSize = p.i("emojiSize", 20)
    val text: AnnotatedString = buildAnnotatedString {
        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            when (run.optString("kind")) {
                "emoji" -> if (run.optString("src").isNotEmpty()) {
                    val id = "emoji-$i"
                    pushStringAnnotation("androidx.compose.foundation.text.inlineContent", id)
                    append(run.optString("text"))
                    pop()
                    inline[id] = InlineTextContent(Placeholder(emojiSize.sp, emojiSize.sp, PlaceholderVerticalAlign.Center)) {
                        OneRemoteImage(run.optString("src"), run.optString("authorization", p.a("authorization")), "", run.optString("text"), emojiSize, emojiSize, 0, "fit")
                    }
                } else append(run.optString("text"))
                "mention", "link" -> {
                    pushStyle(SpanStyle(color = mention, fontWeight = oneWeight(p.s("mentionWeight")) ?: FontWeight.SemiBold))
                    append(run.optString("text"))
                    pop()
                }
                else -> append(run.optString("text"))
            }
        }
    }
    Text(text, modifier.oneSize(p).oneBox(p).onePadding(p), style = oneTextStyle(p), inlineContent = inline,
        maxLines = p.i("maxLines", Int.MAX_VALUE), overflow = if (p.has("maxLines")) TextOverflow.Ellipsis else TextOverflow.Clip)
}

@Composable
private fun OneIcon(p: OneProps, modifier: Modifier) {
    val context = LocalContext.current
    val resource = context.resources.getIdentifier(p.a("name"), "drawable", context.packageName)
    val size = p.i("size", 24)
    var m = modifier.size(p.i("width", size).dp, p.i("height", size).dp).oneBox(p)
    if (resource == 0) {
        Box(m)
        return
    }
    val tint = p.color("tint") ?: p.color("color")
    Image(
        painter = painterResource(resource),
        contentDescription = p.a("label").ifEmpty { null },
        modifier = m.onePadding(p),
        colorFilter = tint?.let { ColorFilter.tint(it) },
    )
}

@Composable
private fun OneImage(node: UiNode, p: OneProps, modifier: Modifier) {
    val size = p.i("size", 40)
    val width = p.i("width", size)
    val height = p.i("height", size)
    Box(modifier.oneBox(p)) {
        OneRemoteImage(p.a("src"), p.a("authorization"), p.a("fallback"), p.a("label"), width, height, p.i("radius"), p.s("fit"))
    }
}

// Sharp cells: each the same whole number of pixels, as many as fit the box, centered in it.
@Composable
private fun OneBitmap(p: OneProps, modifier: Modifier) {
    val cells = p.a("cells")
    val columns = p.ai("columns")
    val size = p.i("size", 40)
    val box = modifier.oneSize(p).then(if (p.has("width") || p.has("height")) Modifier else Modifier.size(size.dp))
    Box(box.semantics { contentDescription = p.a("label") }.drawBehind {
        val rows = if (columns > 0) cells.length / columns else 0
        if (rows > 0) {
            val cell = minOf(this.size.width.toInt() / columns, this.size.height.toInt() / rows).coerceAtLeast(1)
            val left = ((this.size.width.toInt() - cell * columns) / 2).toFloat()
            val top = ((this.size.height.toInt() - cell * rows) / 2).toFloat()
            // Without antialiasing: an edge is one color or the other.
            val paint = android.graphics.Paint().apply { isAntiAlias = false }
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                paint.color = android.graphics.Color.WHITE
                native.drawRect(left, top, left + cell * columns, top + cell * rows, paint)
                paint.color = android.graphics.Color.BLACK
                for (row in 0 until rows) for (column in 0 until columns) {
                    if (cells[row * columns + column] == '1') native.drawRect(left + column * cell, top + row * cell, left + (column + 1) * cell, top + (row + 1) * cell, paint)
                }
            }
        }
    })
}

// Below the anchor, or above when only that fits, lined up with the side it asks for, inside the window.
private class OnePopoverPosition(val end: Boolean, val gap: Int, val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val wanted = if (end) anchorBounds.right - popupContentSize.width else anchorBounds.left
        val x = wanted.coerceAtMost(windowSize.width - margin - popupContentSize.width).coerceAtLeast(margin)
        val below = anchorBounds.bottom + gap
        val above = anchorBounds.top - gap - popupContentSize.height
        return IntOffset(x, if (below + popupContentSize.height > windowSize.height - margin && above >= margin) above else below)
    }
}

// A press outside and the back button close it, through the app.
@Composable
private fun OnePopover(tree: UiTree, node: UiNode, p: OneProps, modifier: Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val latest by rememberUpdatedState(tree)
    val dismiss by rememberUpdatedState(p.ai("onDismiss"))
    Box(modifier.oneSize(p)) {
        node.children.firstOrNull()?.let { RenderOne(tree, it, Modifier) }
        node.children.getOrNull(1)?.let { panel ->
            val end = p.a("side") == "end"
            Popup(
                popupPositionProvider = remember(end, density) { OnePopoverPosition(end, (4 * density).toInt(), (8 * density).toInt()) },
                onDismissRequest = { oneSend(latest, dismiss) },
                properties = PopupProperties(focusable = true),
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalOneDefinite provides true) {
                    RenderOne(tree, panel, Modifier.width(IntrinsicSize.Max))
                }
            }
        }
    }
}

// Delegates to the host's image renderer, which owns remote fetching and caching.
@Composable
internal fun OneRemoteImage(source: String, authorization: String, fallback: String, label: String, width: Int, height: Int, radius: Int, fit: String) {
    RenderImage(
        UiNode("image", "img", JSONObject()
            .put("source", source).put("authorization", authorization).put("fallback", fallback)
            .put("description", label).put("width", width).put("height", height).put("radius", radius)
            .put("scale", if (fit == "fit") "fit" else "crop"), emptyList()),
        Modifier,
    )
}

@Composable
private fun OneInput(tree: UiTree, node: UiNode, p: OneProps, modifier: Modifier) {
    val model = p.a("value")
    var editing by remember(node.key) { mutableStateOf(model) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Values sent to the app that it hasn't shown back yet. The app answers from
    // behind the field, so until it catches up its value is an older one of ours,
    // not a change. Any other value is the app's own, and replaces the text.
    val echoes = remember(node.key) { OneInputEchoes(model) }
    var sends by remember(node.key) { mutableStateOf(0) }
    var expiredGeneration by remember(node.key) { mutableStateOf(-1) }
    echoes.acceptModel(model, focused, expiredGeneration)?.let { if (editing != it) editing = it }
    // An app may keep its value and ignore ours; then the field goes back to it.
    val generation = sends
    LaunchedEffect(generation) {
        if (echoes.hasPending) {
            kotlinx.coroutines.delay(5000)
            expiredGeneration = generation
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val number = p.a("keyboard") == "number"
    val decimal = p.a("keyboard") == "decimal"
    val multiline = p.a("multiline") == "true"
    val submit = p.ai("onSubmit")
    val style = oneTextStyle(p, 16)
    BasicTextField(
        value = editing,
        onValueChange = { raw ->
            val limit = p.ai("maxLength", if (number) 8 else if (decimal) 32 else 4000)
            val next = (if (number) raw.filter { it in '0'..'9' } else if (decimal) oneDecimal(raw) else raw).take(limit)
            if (next != editing) {
                editing = next
                if (p.ai("onChange") > 0) {
                    echoes.changed(next)
                    sends = echoes.generation
                }
                oneSendChange(tree, p.ai("onChange"), next)
            }
        },
        enabled = p.a("enabled") != "false",
        textStyle = style.copy(letterSpacing = if (number && !p.has("tracking")) 5.sp else style.letterSpacing),
        cursorBrush = SolidColor(p.color("caretColor") ?: style.color),
        singleLine = !multiline,
        maxLines = if (multiline) p.i("maxLines", 5) else 1,
        visualTransformation = if (p.a("secure") == "true") PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else if (decimal) KeyboardType.Decimal else if (p.a("secure") == "true") KeyboardType.Password
                else if (p.a("keyboard") == "email") KeyboardType.Email else KeyboardType.Text,
            imeAction = if (submit > 0) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = {
            if (submit > 0) {
                keyboard?.hide()
                oneSend(tree, submit)
            }
        }),
        interactionSource = interaction,
        modifier = modifier.oneSize(p).oneBox(p, focused = focused)
            .semantics { contentDescription = p.a("placeholder") },
        decorationBox = { inner ->
            Box(Modifier.onePadding(p), contentAlignment = Alignment.CenterStart) {
                if (editing.isEmpty()) Text(p.a("placeholder"), style = style.copy(color = p.color("placeholderColor") ?: style.color.copy(alpha = .5f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

// A lazy keyed list. Opens at the bottom, the top, or the first item after
// `a.anchorId`, follows new items when already at the end, and reports the
// largest fully visible `a.item` to `a.onVisible` while the activity is resumed.
@Composable
private fun OneFeed(tree: UiTree, node: UiNode, p: OneProps, modifier: Modifier) {
    val state = rememberLazyListState()
    var positioned by remember(node.key) { mutableStateOf(false) }
    var sent by remember(node.key) { mutableStateOf(0) }
    var previousCount by remember(node.key) { mutableStateOf(0) }
    val latest by rememberUpdatedState(node)
    val latestTree by rememberUpdatedState(tree)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun item(n: UiNode?): Int = n?.text("a.item")?.toIntOrNull() ?: 0
    LaunchedEffect(node.key, node.children.size) {
        if (!positioned && node.children.isNotEmpty()) {
            val anchor = p.a("anchor")
            val index = when (anchor) {
                "top" -> 0
                "id" -> node.children.indexOfFirst { item(it) > p.ai("anchorId") }.let { if (it >= 0) it else node.children.lastIndex }
                else -> node.children.lastIndex
            }
            state.scrollToItem(index.coerceAtLeast(0))
            positioned = true
        } else if (positioned && node.children.size > previousCount && state.layoutInfo.visibleItemsInfo.lastOrNull()?.index == previousCount - 1) {
            state.scrollToItem(node.children.lastIndex)
        }
        previousCount = node.children.size
    }
    LaunchedEffect(node.key) {
        snapshotFlow { state.firstVisibleItemIndex to state.layoutInfo.totalItemsCount }.collect {
            kotlinx.coroutines.delay(200)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
            val layout = state.layoutInfo
            val last = layout.visibleItemsInfo
                .filter { it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset }
                .maxOfOrNull { item(latest.children.getOrNull(it.index)) } ?: 0
            val event = OneProps(latest).ai("onVisible")
            if (event > 0 && last > sent) {
                sent = last
                oneSend(latestTree, event, last.toString())
            }
        }
    }
    LazyColumn(
        modifier.oneSize(p).oneBox(p).fillMaxSize(),
        state = state,
        contentPadding = PaddingValues(start = p.pad("Left"), top = p.pad("Top"), end = p.pad("Right"), bottom = p.pad("Bottom")),
        verticalArrangement = Arrangement.spacedBy(p.i("gap").dp),
    ) {
        items(node.children, key = { it.key }) { RenderOne(tree, it, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun OneNative(tree: UiTree, node: UiNode, p: OneProps, modifier: Modifier) {
    val target = p.a("target")
    val fallback = node.children.lastOrNull()
    if (target == "android" || target == "mobile") {
        val props = try { JSONObject(p.a("props")) } catch (_: Exception) { JSONObject() }
        RenderNode(tree, UiNode(p.a("nativeKind"), node.key, props, node.children.dropLast(1)), modifier)
    } else if (fallback != null) {
        RenderOne(tree, fallback, modifier)
    }
}
