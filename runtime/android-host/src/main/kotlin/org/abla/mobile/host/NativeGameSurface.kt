package org.abla.mobile.host

import android.media.AudioManager
import android.media.ToneGenerator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A normalized, renderer-neutral entity used by the bundled arcade scenes. */
internal data class GameEntity(
    var kind: String,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var size: Float = 0.06f,
    var phase: Float = 0f,
    var alive: Boolean = true,
)

internal data class GamePointer(
    val x: Float,
    val y: Float,
    val pressed: Boolean,
)

private data class GameHud(val score: Int, val streak: Int, val lives: Int)

internal enum class ArcadeGame(val id: String, val label: String, val hint: String) {
    NeonDrift("neon_drift", "NEON DRIFT", "Drag to change lanes"),
    SkyStack("sky_stack", "SKY STACK", "Tap to lock each block"),
    SlimeSprint("slime_sprint", "SLIME SPRINT", "Tap to jump"),
    OrbitDash("orbit_dash", "ORBIT DASH", "Tap to reverse orbit"),
    TempleHop("temple_hop", "TEMPLE HOP", "Tap left or right"),
    PixelPunch("pixel_punch", "PIXEL PUNCH", "Punch the glowing targets"),
    WaveRider("wave_rider", "WAVE RIDER", "Drag through the current"),
    MoonMiner("moon_miner", "MOON MINER", "Tap crystals to mine"),
    ;

    companion object {
        fun fromId(id: String): ArcadeGame = entries.firstOrNull { it.id == id }
            ?: NeonDrift
    }
}

internal interface GameAudio : AutoCloseable {
    fun collect()
    fun impact()
    override fun close()
}

private object SilentGameAudio : GameAudio {
    override fun collect() = Unit
    override fun impact() = Unit
    override fun close() = Unit
}

private class AndroidGameAudio : GameAudio {
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 18)

    override fun collect() {
        tones.startTone(ToneGenerator.TONE_PROP_ACK, 45)
    }

    override fun impact() {
        tones.startTone(ToneGenerator.TONE_PROP_NACK, 55)
    }

    override fun close() {
        tones.release()
    }
}

/**
 * Local real-time state. It owns no Abla value: only immutable scalar node
 * properties enter the constructor and the composition owns its lifecycle.
 */
internal class NativeArcadeEngine(
    val game: ArcadeGame,
    private val accent: Color,
    private val audio: GameAudio,
) {
    val entities = mutableListOf<GameEntity>()
    var score = 0
        private set
    var streak = 1
        private set
    var lives = 3
        private set
    private var width = 1f
    private var height = 1f
    private var elapsed = 0f
    private var spawnClock = 0f
    private var seed = game.id.hashCode().toLong() and 0xffffffffL
    private var playerX = 0.5f
    private var playerY = 0.78f
    private var playerVelocity = 0f
    private var direction = 1f
    private var orbitAngle = -1.55f
    private var targetAngle = 0.7f
    private var templeLane = 1
    private var lastOrbitDistance = 10f

    init {
        resetScene()
    }

    fun resize(width: Float, height: Float) {
        this.width = max(width, 1f)
        this.height = max(height, 1f)
    }

    fun update(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceIn(0f, 0.05f)
        elapsed += dt
        spawnClock += dt
        when (game) {
            ArcadeGame.NeonDrift -> updateNeonDrift(dt)
            ArcadeGame.SkyStack -> updateSkyStack(dt)
            ArcadeGame.SlimeSprint -> updateSlimeSprint(dt)
            ArcadeGame.OrbitDash -> updateOrbitDash(dt)
            ArcadeGame.TempleHop -> updateTempleHop(dt)
            ArcadeGame.PixelPunch -> updatePixelPunch(dt)
            ArcadeGame.WaveRider -> updateWaveRider(dt)
            ArcadeGame.MoonMiner -> updateMoonMiner(dt)
        }
        entities.removeAll { !it.alive }
    }

    fun pointer(pointer: GamePointer) {
        playerX = pointer.x.coerceIn(0.06f, 0.94f)
        when (game) {
            ArcadeGame.NeonDrift -> Unit
            ArcadeGame.SkyStack -> if (pointer.pressed) placeBlock()
            ArcadeGame.SlimeSprint -> if (pointer.pressed && playerY >= 0.76f) {
                playerVelocity = -1.05f
            }
            ArcadeGame.OrbitDash -> if (pointer.pressed) direction = -direction
            ArcadeGame.TempleHop -> if (pointer.pressed) {
                templeLane = if (pointer.x < 0.5f) max(0, templeLane - 1)
                    else min(2, templeLane + 1)
                playerX = laneX(templeLane)
            }
            ArcadeGame.PixelPunch -> if (pointer.pressed) hitTarget(pointer.x, pointer.y)
            ArcadeGame.WaveRider -> playerY = pointer.y.coerceIn(0.18f, 0.84f)
            ArcadeGame.MoonMiner -> if (pointer.pressed) mineRock(pointer.x, pointer.y)
        }
    }

    fun draw(scope: DrawScope, background: Color) = with(scope) {
        drawRect(
            Brush.verticalGradient(
                listOf(
                    background.copy(alpha = 0.82f),
                    background.mix(Color.Black, 0.32f).copy(alpha = 0.94f),
                ),
            ),
        )
        when (game) {
            ArcadeGame.NeonDrift -> drawNeonDrift()
            ArcadeGame.SkyStack -> drawSkyStack()
            ArcadeGame.SlimeSprint -> drawSlimeSprint()
            ArcadeGame.OrbitDash -> drawOrbitDash()
            ArcadeGame.TempleHop -> drawTempleHop()
            ArcadeGame.PixelPunch -> drawPixelPunch()
            ArcadeGame.WaveRider -> drawWaveRider()
            ArcadeGame.MoonMiner -> drawMoonMiner()
        }
    }

    private fun resetScene() {
        entities.clear()
        spawnClock = 0f
        playerX = 0.5f
        playerY = 0.78f
        playerVelocity = 0f
        when (game) {
            ArcadeGame.SkyStack -> {
                entities += GameEntity("block", 0.5f, 0.86f, size = 0.32f)
                spawnActiveBlock(0.78f)
            }
            ArcadeGame.PixelPunch -> repeat(4) { spawnTarget() }
            ArcadeGame.MoonMiner -> repeat(6) { spawnRock() }
            ArcadeGame.TempleHop -> repeat(7) { index ->
                entities += GameEntity(
                    "stone", laneX(index % 3), 0.12f + index * 0.13f,
                    vy = 0.17f, size = 0.095f,
                )
            }
            else -> Unit
        }
    }

    private fun updateNeonDrift(dt: Float) {
        if (spawnClock > 0.72f) {
            spawnClock = 0f
            val lane = (nextFloat() * 3f).toInt().coerceIn(0, 2)
            entities += GameEntity("gate", laneX(lane), -0.08f, vy = 0.48f,
                size = 0.075f)
        }
        entities.filter { it.kind == "gate" }.forEach { gate ->
            gate.y += gate.vy * dt
            if (gate.y > 0.9f && gate.phase == 0f) {
                gate.phase = 1f
                if (abs(gate.x - playerX) < 0.105f) crash() else collect(12)
            }
            if (gate.y > 1.12f) gate.alive = false
        }
    }

    private fun updateSkyStack(dt: Float) {
        val active = entities.lastOrNull { it.kind == "active" } ?: return
        active.x += active.vx * dt
        if (active.x < active.size / 2f || active.x > 1f - active.size / 2f) {
            active.vx = -active.vx
            active.x = active.x.coerceIn(active.size / 2f, 1f - active.size / 2f)
        }
    }

    private fun placeBlock() {
        val active = entities.lastOrNull { it.kind == "active" } ?: return
        val previous = entities.lastOrNull { it.kind == "block" } ?: return
        val overlap = active.size - abs(active.x - previous.x)
        if (overlap <= 0.025f) {
            crash()
            entities.clear()
            entities += GameEntity("block", 0.5f, 0.86f, size = 0.32f)
            spawnActiveBlock(0.78f)
            return
        }
        active.kind = "block"
        active.vx = 0f
        active.size = overlap
        active.x = (active.x + previous.x) * 0.5f
        collect((overlap * 100).toInt())
        val blocks = entities.count { it.kind == "block" }
        if (blocks >= 9) {
            entities.filter { it.kind == "block" }.forEach { it.y += 0.08f }
        }
        spawnActiveBlock((active.y - 0.08f).coerceAtLeast(0.13f))
    }

    private fun spawnActiveBlock(y: Float) {
        entities += GameEntity("active", 0.16f, y, vx = 0.48f + score * 0.001f,
            size = entities.lastOrNull { it.kind == "block" }?.size ?: 0.32f)
    }

    private fun updateSlimeSprint(dt: Float) {
        playerVelocity += 2.8f * dt
        playerY += playerVelocity * dt
        if (playerY > 0.78f) {
            playerY = 0.78f
            playerVelocity = 0f
        }
        if (spawnClock > 1.05f) {
            spawnClock = 0f
            entities += GameEntity("mushroom", 1.08f, 0.79f, vx = -0.48f,
                size = 0.065f)
        }
        entities.forEach { obstacle ->
            obstacle.x += obstacle.vx * dt
            if (obstacle.x < 0.3f && obstacle.phase == 0f) {
                obstacle.phase = 1f
                if (abs(playerY - obstacle.y) < 0.09f) crash() else collect(8)
            }
            if (obstacle.x < -0.1f) obstacle.alive = false
        }
    }

    private fun updateOrbitDash(dt: Float) {
        orbitAngle += direction * (1.75f + score * 0.002f) * dt
        val distance = angularDistance(orbitAngle, targetAngle)
        if (distance < 0.09f && lastOrbitDistance >= 0.09f) {
            collect(15)
            targetAngle += 1.65f + nextFloat() * 2.2f
        }
        lastOrbitDistance = distance
    }

    private fun updateTempleHop(dt: Float) {
        entities.forEach { stone ->
            stone.y += stone.vy * dt
            if (stone.y > 0.79f && stone.phase == 0f) {
                stone.phase = 1f
                if (abs(stone.x - playerX) < 0.08f) collect(10) else crash()
            }
            if (stone.y > 1.08f) {
                stone.y = -0.08f
                stone.x = laneX((nextFloat() * 3f).toInt().coerceIn(0, 2))
                stone.phase = 0f
            }
        }
    }

    private fun updatePixelPunch(dt: Float) {
        entities.forEach { target ->
            target.phase -= dt
            if (target.phase <= 0f) {
                target.x = randomBetween(0.14f, 0.86f)
                target.y = randomBetween(0.22f, 0.78f)
                target.phase = randomBetween(1.2f, 2.4f)
                target.size = randomBetween(0.055f, 0.085f)
            }
        }
    }

    private fun hitTarget(x: Float, y: Float) {
        val hit = entities.filter { it.kind == "target" }.minByOrNull {
            distance(it.x, it.y, x, y)
        }
        if (hit != null && distance(hit.x, hit.y, x, y) < hit.size * 1.45f) {
            collect(20)
            hit.phase = 0f
        } else {
            streak = 1
            audio.impact()
        }
    }

    private fun updateWaveRider(dt: Float) {
        if (spawnClock > 0.62f) {
            spawnClock = 0f
            entities += GameEntity("foam", 1.08f, randomBetween(0.2f, 0.82f),
                vx = -0.38f, size = randomBetween(0.035f, 0.07f))
        }
        entities.forEach { foam ->
            foam.x += foam.vx * dt
            foam.phase += dt * 4f
            if (foam.x < 0.27f && foam.phase > 0f) {
                if (distance(foam.x, foam.y, 0.22f, playerY) < 0.085f) {
                    foam.alive = false
                    collect(6)
                }
            }
            if (foam.x < -0.1f) foam.alive = false
        }
    }

    private fun updateMoonMiner(dt: Float) {
        entities.forEach { rock ->
            rock.phase += dt
            rock.y += sin(rock.phase * 1.4f) * dt * 0.006f
        }
        if (entities.count { it.kind == "rock" } < 5) spawnRock()
    }

    private fun mineRock(x: Float, y: Float) {
        val rock = entities.filter { it.kind == "rock" }.minByOrNull {
            distance(it.x, it.y, x, y)
        }
        if (rock != null && distance(rock.x, rock.y, x, y) < rock.size * 1.45f) {
            rock.size -= 0.018f
            collect(5)
            if (rock.size < 0.035f) {
                rock.alive = false
                collect(25)
            }
        } else {
            audio.impact()
        }
    }

    private fun spawnTarget() {
        entities += GameEntity(
            "target", randomBetween(0.14f, 0.86f), randomBetween(0.22f, 0.78f),
            size = randomBetween(0.055f, 0.085f), phase = randomBetween(1f, 2f),
        )
    }

    private fun spawnRock() {
        entities += GameEntity(
            "rock", randomBetween(0.13f, 0.87f), randomBetween(0.2f, 0.78f),
            size = randomBetween(0.055f, 0.095f), phase = nextFloat() * 5f,
        )
    }

    private fun collect(points: Int) {
        score += points * streak
        streak = min(streak + 1, 9)
        audio.collect()
    }

    private fun crash() {
        lives = max(0, lives - 1)
        streak = 1
        audio.impact()
        if (lives == 0) {
            lives = 3
            score = max(0, score - 40)
        }
    }

    private fun nextFloat(): Float {
        seed = (seed * 1664525L + 1013904223L) and 0xffffffffL
        return ((seed ushr 8) and 0xffffffL).toFloat() / 0x1000000.toFloat()
    }

    private fun randomBetween(low: Float, high: Float): Float =
        low + (high - low) * nextFloat()

    private fun laneX(lane: Int): Float = 0.24f + lane.coerceIn(0, 2) * 0.26f

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun angularDistance(first: Float, second: Float): Float {
        val tau = (Math.PI * 2.0).toFloat()
        val normalized = ((first - second) % tau + tau) % tau
        return min(normalized, tau - normalized)
    }

    private fun DrawScope.point(x: Float, y: Float): Offset =
        Offset(x * size.width, y * size.height)

    private fun DrawScope.radius(value: Float): Float =
        value * min(size.width, size.height)

    private fun DrawScope.drawNeonDrift() {
        val horizon = point(0.5f, 0.12f)
        drawPath(Path().apply {
            moveTo(horizon.x - size.width * 0.09f, horizon.y)
            lineTo(size.width * 0.05f, size.height)
            lineTo(size.width * 0.95f, size.height)
            lineTo(horizon.x + size.width * 0.09f, horizon.y)
            close()
        }, Color(0xFF17112B))
        repeat(12) { index ->
            val y = ((index / 12f + elapsed * 0.65f) % 1f)
            val spread = 0.04f + y * 0.43f
            drawLine(accent.copy(alpha = 0.38f), point(0.5f - spread, y),
                point(0.5f + spread, y), strokeWidth = 2f)
        }
        entities.forEach { gate ->
            drawRoundRect(
                color = Color(0xFFFF5FA2),
                topLeft = point(gate.x - gate.size, gate.y - 0.025f),
                size = Size(gate.size * 2f * size.width, 0.05f * size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f),
            )
        }
        val car = point(playerX, 0.82f)
        drawRoundRect(accent, Offset(car.x - radius(0.05f), car.y - radius(0.08f)),
            Size(radius(0.1f), radius(0.16f)),
            androidx.compose.ui.geometry.CornerRadius(radius(0.025f)))
        drawCircle(Color.White, radius(0.016f), Offset(car.x, car.y - radius(0.045f)))
    }

    private fun DrawScope.drawSkyStack() {
        repeat(8) { index ->
            drawCircle(accent.copy(alpha = 0.08f + index * 0.015f),
                radius(0.05f + index * 0.015f), point((index * 0.17f) % 1f, 0.18f))
        }
        entities.forEachIndexed { index, block ->
            val color = if (block.kind == "active") Color(0xFFFFD166)
                else accent.mix(Color(0xFF6DE8C5), (index % 5) / 5f)
            drawRoundRect(color, point(block.x - block.size / 2f, block.y - 0.03f),
                Size(block.size * size.width, 0.065f * size.height),
                androidx.compose.ui.geometry.CornerRadius(12f))
        }
    }

    private fun DrawScope.drawSlimeSprint() {
        drawRect(Color(0xFF203B35), point(0f, 0.84f),
            Size(size.width, size.height * 0.16f))
        repeat(6) { index ->
            val x = ((index * 0.21f - elapsed * 0.15f) % 1.2f + 1.2f) % 1.2f
            drawCircle(Color(0xFF6DE8C5).copy(alpha = 0.18f), radius(0.08f), point(x, 0.3f))
        }
        entities.forEach { obstacle ->
            drawCircle(Color(0xFFFFD166), radius(obstacle.size), point(obstacle.x, obstacle.y))
            drawCircle(Color(0xFFFF78AE), radius(obstacle.size * 0.45f),
                point(obstacle.x, obstacle.y - obstacle.size * 0.7f))
        }
        val slime = point(0.23f, playerY)
        drawOval(accent, Offset(slime.x - radius(0.07f), slime.y - radius(0.055f)),
            Size(radius(0.14f), radius(0.11f)))
        drawCircle(Color.White, radius(0.014f), point(0.205f, playerY - 0.025f))
        drawCircle(Color.White, radius(0.014f), point(0.255f, playerY - 0.025f))
    }

    private fun DrawScope.drawOrbitDash() {
        val center = point(0.5f, 0.52f)
        repeat(3) { ring ->
            drawCircle(accent.copy(alpha = 0.12f + ring * 0.1f),
                radius(0.16f + ring * 0.08f), center, style = Stroke(width = 3f))
        }
        drawCircle(Color(0xFFFFD166), radius(0.09f), center)
        val orbitRadius = radius(0.32f)
        val player = Offset(center.x + cos(orbitAngle) * orbitRadius,
            center.y + sin(orbitAngle) * orbitRadius)
        val target = Offset(center.x + cos(targetAngle) * orbitRadius,
            center.y + sin(targetAngle) * orbitRadius)
        drawCircle(Color(0xFFFF78AE).copy(alpha = 0.28f), radius(0.065f), target)
        drawCircle(Color(0xFFFF78AE), radius(0.027f), target)
        drawCircle(accent, radius(0.048f), player)
        drawCircle(Color.White, radius(0.015f), player)
    }

    private fun DrawScope.drawTempleHop() {
        drawPath(Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.05f)
            lineTo(size.width * 0.96f, size.height)
            lineTo(size.width * 0.04f, size.height)
            close()
        }, Color(0xFF38243A))
        repeat(3) { lane ->
            drawLine(Color(0xFFFFD166).copy(alpha = 0.18f), point(laneX(lane), 0.12f),
                point(laneX(lane), 1f), 3f)
        }
        entities.forEach { stone ->
            drawCircle(Color(0xFFCA8B55), radius(stone.size), point(stone.x, stone.y))
            drawCircle(Color(0xFFFFD166).copy(alpha = 0.32f), radius(stone.size * 0.65f),
                point(stone.x, stone.y))
        }
        drawPath(Path().apply {
            val p = point(playerX, 0.78f)
            moveTo(p.x, p.y - radius(0.07f))
            lineTo(p.x + radius(0.06f), p.y + radius(0.05f))
            lineTo(p.x - radius(0.06f), p.y + radius(0.05f))
            close()
        }, accent)
    }

    private fun DrawScope.drawPixelPunch() {
        repeat(9) { index ->
            val x = 0.08f + (index % 3) * 0.42f
            val y = 0.17f + (index / 3) * 0.3f
            drawRect(accent.copy(alpha = 0.045f), point(x, y),
                Size(size.width * 0.25f, size.height * 0.16f))
        }
        entities.forEach { target ->
            val pulse = 0.85f + sin(target.phase * 7f) * 0.15f
            drawCircle(Color(0xFFFF78AE).copy(alpha = 0.2f),
                radius(target.size * 1.45f * pulse), point(target.x, target.y))
            drawCircle(accent, radius(target.size * pulse), point(target.x, target.y))
            drawLine(Color.White, point(target.x - 0.025f, target.y),
                point(target.x + 0.025f, target.y), 4f, StrokeCap.Round)
            drawLine(Color.White, point(target.x, target.y - 0.025f),
                point(target.x, target.y + 0.025f), 4f, StrokeCap.Round)
        }
    }

    private fun DrawScope.drawWaveRider() {
        repeat(5) { wave ->
            val path = Path()
            val baseY = 0.2f + wave * 0.16f
            path.moveTo(0f, baseY * size.height)
            repeat(24) { step ->
                val x = step / 23f
                val y = baseY + sin(x * 9f + elapsed * 2f + wave) * 0.035f
                path.lineTo(x * size.width, y * size.height)
            }
            drawPath(path, accent.copy(alpha = 0.18f + wave * 0.07f),
                style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        entities.forEach { foam ->
            drawCircle(Color.White.copy(alpha = 0.72f), radius(foam.size),
                point(foam.x, foam.y))
        }
        val rider = point(0.22f, playerY)
        drawLine(Color(0xFFFFD166), Offset(rider.x - radius(0.075f), rider.y),
            Offset(rider.x + radius(0.075f), rider.y), 10f, StrokeCap.Round)
        drawCircle(Color(0xFFFF78AE), radius(0.038f),
            Offset(rider.x, rider.y - radius(0.045f)))
    }

    private fun DrawScope.drawMoonMiner() {
        drawCircle(Color(0xFF2E2A58), radius(0.33f), point(0.16f, 0.94f))
        repeat(24) { index ->
            val x = ((index * 37) % 101) / 100f
            val y = ((index * 61) % 83) / 100f
            drawCircle(Color.White.copy(alpha = 0.25f + (index % 4) * 0.15f),
                radius(0.004f + (index % 3) * 0.002f), point(x, y))
        }
        entities.forEach { rock ->
            val p = point(rock.x, rock.y)
            drawCircle(Color(0xFF514B70), radius(rock.size), p)
            drawPath(Path().apply {
                moveTo(p.x, p.y - radius(rock.size * 0.7f))
                lineTo(p.x + radius(rock.size * 0.45f), p.y)
                lineTo(p.x, p.y + radius(rock.size * 0.7f))
                lineTo(p.x - radius(rock.size * 0.45f), p.y)
                close()
            }, accent)
        }
    }
}

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
    val soundEnabled = node.bool("sound", true)
    val audio = remember(node.key, game, soundEnabled) {
        if (soundEnabled) AndroidGameAudio() else SilentGameAudio
    }
    DisposableEffect(audio) {
        onDispose { audio.close() }
    }
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val display = hostView.display
        val currentMode = display?.mode
        val highestMode = display?.supportedModes
            ?.filter {
                currentMode == null ||
                    (it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight)
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
        if (Build.VERSION.SDK_INT >= 35) {
            hostView.setRequestedFrameRate(highestMode?.refreshRate ?: 120f)
        }
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
    val engine = remember(node.key, game, accent, audio) {
        NativeArcadeEngine(game, accent, audio)
    }
    val frame = remember(engine) { mutableLongStateOf(0L) }
    var hud by remember(engine) {
        mutableStateOf(GameHud(engine.score, engine.streak, engine.lives))
    }
    LaunchedEffect(engine) {
        var previous = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            engine.update((now - previous) / 1_000_000_000f)
            previous = now
            frame.longValue += 1L
            val nextHud = GameHud(engine.score, engine.streak, engine.lives)
            if (nextHud != hud) hud = nextHud
        }
    }

    val shape = RoundedCornerShape(node.int("radius", 22).dp)
    Box(
        modifier = modifier
            .height(node.int("height", 480).dp)
            .clip(shape)
            .background(background)
            .semantics { contentDescription = "${game.label} native game" },
    ) {
        if (artResource != 0) {
            Image(
                painter = painterResource(artResource),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = 0.42f
                    scaleX = 1.04f
                    scaleY = 1.04f
                },
            )
        }
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
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: break
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
            engine.resize(size.width, size.height)
            engine.draw(this, background)
        }
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(game.label, color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("${hud.score.toString().padStart(5, '0')}  ×${hud.streak}",
                    color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(game.hint, color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp)
                Text("♥".repeat(hud.lives), color = Color(0xFFFF78AE), fontSize = 13.sp)
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

private fun Color.mix(other: Color, amount: Float): Color {
    val ratio = amount.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * ratio,
        green = green + (other.green - green) * ratio,
        blue = blue + (other.blue - blue) * ratio,
        alpha = alpha + (other.alpha - alpha) * ratio,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
