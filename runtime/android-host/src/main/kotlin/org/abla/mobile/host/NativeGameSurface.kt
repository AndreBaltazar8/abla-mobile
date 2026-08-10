package org.abla.mobile.host

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive

@Composable
internal fun RenderNativeGame(node: UiNode, modifier: Modifier) {
    val game = ArcadeGame.fromId(node.text("game"))
    val background = parseGameColor(node.text("background"), Color(0xFF120D20))
    val accent = parseGameColor(node.text("accent"), Color(0xFFA783FF))
    val context = LocalContext.current
    val artName = node.text("art").ifEmpty { game.id }
    val artResource = remember(context, artName) {
        context.resources.getIdentifier(artName, "drawable", context.packageName)
    }
    val heroResource = remember(context, game) {
        context.resources.getIdentifier("${game.id}_hero", "drawable", context.packageName)
    }
    val artwork = remember(context, artResource) {
        if (artResource == 0) null else ImageBitmap.imageResource(context.resources, artResource)
    }
    val hero = remember(context, heroResource) {
        if (heroResource == 0) null else ImageBitmap.imageResource(context.resources, heroResource)
    }
    val soundEnabled = node.bool("sound", true)
    val audio = remember(node.key, game, soundEnabled) {
        if (soundEnabled) AndroidGameAudio() else SilentGameAudio
    }
    DisposableEffect(audio) {
        onDispose { audio.close() }
    }
    RequestHighestRefreshRate()

    val engine = remember(node.key, game, audio) { NativeArcadeEngine(game, audio) }
    val renderer = remember(engine, background, accent, artwork, hero) {
        ArcadeGameRenderer(engine, background, accent, artwork, hero)
    }
    val frame = remember(engine) { mutableLongStateOf(0L) }
    var hud by remember(engine) { mutableStateOf(engine.hud) }
    LaunchedEffect(engine) {
        var previous = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            engine.update((now - previous) / 1_000_000_000f)
            previous = now
            frame.longValue += 1L
            val nextHud = engine.hud
            if (nextHud != hud) hud = nextHud
        }
    }

    val shape = RoundedCornerShape(node.int("radius", 22).dp)
    Box(
        modifier = modifier
            .height(node.int("height", 480).dp)
            .clip(shape)
            .background(background)
            .semantics {
                contentDescription = "${game.label} native game, ${hud.phase}, " +
                    "score ${hud.score}, progress ${hud.progress} of ${hud.target}"
            },
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(engine) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    engine.pointer(GamePointer(
                        down.position.x / size.width,
                        down.position.y / size.height,
                        true,
                    ))
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        engine.pointer(GamePointer(
                            change.position.x / size.width,
                            change.position.y / size.height,
                            false,
                        ))
                        change.consume()
                    } while (change.pressed)
                }
            },
        ) {
            frame.longValue
            renderer.draw(this)
        }
        GameHudOverlay(game, hud, accent)
        if (hud.phase != GamePhase.Playing) GameResultOverlay(hud, accent)
    }
}

@Composable
private fun GameHudOverlay(game: ArcadeGame, hud: GameHud, accent: Color) {
    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(game.label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                    Text(hud.mission, color = Color.White.copy(alpha = 0.62f), fontSize = 9.sp,
                        fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(hud.score.toString().padStart(6, '0'), color = Color.White,
                        fontSize = 15.sp, fontWeight = FontWeight.Black)
                    Text("COMBO ×${hud.combo}", color = accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    Modifier.width(150.dp).height(5.dp).clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Box(
                        Modifier.fillMaxWidth((hud.progress.toFloat() / hud.target).coerceIn(0f, 1f))
                            .height(5.dp).background(accent),
                    )
                }
                Text("${hud.progress}/${hud.target}", color = Color.White.copy(alpha = 0.72f),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(game.hint, color = Color.White.copy(alpha = 0.74f), fontSize = 10.sp)
            Text("♥".repeat(hud.lives), color = Color(0xFFFF628F), fontSize = 15.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GameResultOverlay(hud: GameHud, accent: Color) {
    val won = hud.phase == GamePhase.Victory
    Box(
        Modifier.fillMaxSize().background(Color(0xD910081C)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (won) "MISSION COMPLETE" else "RUN ENDED",
                color = if (won) Color(0xFFFFD763) else Color(0xFFFF628F),
                fontSize = 12.sp, fontWeight = FontWeight.Black)
            Text(if (won) "VICTORY" else "GAME OVER", color = Color.White,
                fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
            Text("${hud.score} POINTS  ·  ${hud.progress}/${hud.target}",
                color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.padding(top = 8.dp).clip(RoundedCornerShape(18.dp))
                    .background(accent).padding(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Text("TAP TO PLAY AGAIN", color = Color(0xFF100719), fontSize = 11.sp,
                    fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
private fun RequestHighestRefreshRate() {
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val display = hostView.display
        val currentMode = display?.mode
        val highestMode = display?.supportedModes
            ?.filter {
                currentMode == null ||
                    (it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight)
            }
            ?.maxByOrNull { it.refreshRate }
        val activity = hostView.context.findActivity()
        val oldMode = activity?.window?.attributes?.preferredDisplayModeId ?: 0
        val oldRate = activity?.window?.attributes?.preferredRefreshRate ?: 0f
        if (activity != null && highestMode != null) {
            activity.window.attributes = activity.window.attributes.apply {
                preferredDisplayModeId = highestMode.modeId
                preferredRefreshRate = highestMode.refreshRate
            }
        }
        if (Build.VERSION.SDK_INT >= 35) hostView.setRequestedFrameRate(highestMode?.refreshRate ?: 120f)
        onDispose {
            if (Build.VERSION.SDK_INT >= 35) hostView.setRequestedFrameRate(0f)
            if (activity != null) {
                activity.window.attributes = activity.window.attributes.apply {
                    preferredDisplayModeId = oldMode
                    preferredRefreshRate = oldRate
                }
            }
        }
    }
}

private fun parseGameColor(source: String, fallback: Color): Color {
    if (!source.startsWith("#")) return fallback
    return try {
        Color(android.graphics.Color.parseColor(source))
    } catch (_: IllegalArgumentException) {
        fallback
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
