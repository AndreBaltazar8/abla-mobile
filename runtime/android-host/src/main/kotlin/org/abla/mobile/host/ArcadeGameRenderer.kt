package org.abla.mobile.host

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class ArcadeGameRenderer(
    private val engine: NativeArcadeEngine,
    private val background: Color,
    private val accent: Color,
    private val artwork: ImageBitmap?,
    private val hero: ImageBitmap?,
) {
    private val shader = if (Build.VERSION.SDK_INT >= 33) ArcadeBackdropShader.create() else null

    fun draw(scope: DrawScope) = with(scope) {
        drawShaderBackdrop()
        drawBackdropArtwork()
        val shakePixels = engine.shake * radius(0.012f)
        val shakeX = sin(engine.elapsed * 83f) * shakePixels
        val shakeY = cos(engine.elapsed * 67f) * shakePixels * 0.55f
        withTransform({ translate(shakeX, shakeY) }) {
            when (engine.game) {
                ArcadeGame.NeonDrift -> drawNeonDrift()
                ArcadeGame.SkyStack -> drawSkyStack()
                ArcadeGame.SlimeSprint -> drawSlimeSprint()
                ArcadeGame.OrbitDash -> drawOrbitDash()
                ArcadeGame.TempleHop -> drawTempleHop()
                ArcadeGame.PixelPunch -> drawPixelPunch()
                ArcadeGame.WaveRider -> drawWaveRider()
                ArcadeGame.MoonMiner -> drawMoonMiner()
            }
            drawParticles()
        }
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                center = Offset(size.width * 0.5f, size.height * 0.48f),
                radius = max(size.width, size.height) * 0.78f,
            ),
        )
        if (engine.flash > 0f) {
            drawRect(Color.White.copy(alpha = engine.flash.coerceAtMost(0.18f)))
        }
    }

    private fun DrawScope.drawShaderBackdrop() {
        if (shader != null && Build.VERSION.SDK_INT >= 33) {
            shader.draw(this, engine.elapsed, background, accent)
        } else {
            drawRect(
                Brush.verticalGradient(
                    listOf(background.mix(accent, 0.12f), background, Color.Black),
                ),
            )
        }
    }

    private fun DrawScope.drawBackdropArtwork() {
        val image = artwork ?: return
        val sourceAspect = image.width.toFloat() / image.height
        val destinationAspect = size.width / size.height
        val sourceWidth: Int
        val sourceHeight: Int
        val sourceX: Int
        val sourceY: Int
        if (destinationAspect > sourceAspect) {
            sourceWidth = image.width
            sourceHeight = (image.width / destinationAspect).toInt().coerceAtLeast(1)
            sourceX = 0
            sourceY = (image.height - sourceHeight) / 2
        } else {
            sourceHeight = image.height
            sourceWidth = (image.height * destinationAspect).toInt().coerceAtLeast(1)
            sourceX = (image.width - sourceWidth) / 2
            sourceY = 0
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(sourceX, sourceY),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = 0.13f,
        )
    }

    private fun DrawScope.drawNeonDrift() {
        val horizon = point(0.5f, 0.16f)
        repeat(28) { index ->
            val x = ((index * 0.137f + engine.elapsed * (0.12f + index % 3 * 0.03f)) % 1.2f) - 0.1f
            val y = 0.07f + (index * 0.091f % 0.72f)
            drawLine(
                if (index % 2 == 0) accent.copy(alpha = 0.22f) else Color(0xFFFF4D9D).copy(alpha = 0.18f),
                point(x, y), point(x - 0.035f, y + 0.06f), radius(0.004f), StrokeCap.Round,
            )
        }
        drawPath(Path().apply {
            moveTo(horizon.x - size.width * 0.07f, horizon.y)
            lineTo(size.width * 0.03f, size.height)
            lineTo(size.width * 0.97f, size.height)
            lineTo(horizon.x + size.width * 0.07f, horizon.y)
            close()
        }, Brush.verticalGradient(listOf(Color(0xFF15112D), Color(0xFF06040E))))
        repeat(16) { index ->
            val z = ((index / 16f + engine.elapsed * 0.72f) % 1f)
            val eased = z * z
            val spread = 0.045f + eased * 0.46f
            val y = 0.16f + eased * 0.84f
            drawGlowLine(point(0.5f - spread, y), point(0.5f + spread, y), accent, 0.0035f + eased * 0.005f)
        }
        repeat(4) { divider ->
            val targetX = 0.11f + divider * 0.26f
            drawGlowLine(horizon, point(targetX, 1f), accent.copy(alpha = 0.7f), 0.003f)
        }
        engine.entities.forEach { entity ->
            if (entity.kind == "gate") {
                val scale = 0.32f + entity.y.coerceIn(0f, 1f) * 0.75f
                val center = point(entity.x, entity.y)
                val halfWidth = radius(entity.size * scale)
                repeat(3) { layer ->
                    drawRoundRect(
                        Color(0xFFFF315F).copy(alpha = 0.12f + layer * 0.22f),
                        Offset(center.x - halfWidth, center.y - radius(0.018f + layer * 0.006f)),
                        Size(halfWidth * 2f, radius(0.036f + layer * 0.012f)),
                        CornerRadius(radius(0.012f)),
                    )
                }
                drawCircle(Color.White, radius(0.008f), Offset(center.x - halfWidth * 0.76f, center.y))
                drawCircle(Color.White, radius(0.008f), Offset(center.x + halfWidth * 0.76f, center.y))
            } else {
                drawRivet(point(entity.x, entity.y), entity.size, entity.phase)
            }
        }
        if (engine.invulnerability <= 0f || (engine.elapsed * 12f).toInt() % 2 == 0) {
            drawHero(point(engine.playerX, 0.82f), 0.34f)
        }
    }

    private fun DrawScope.drawSkyStack() {
        repeat(12) { index ->
            val x = ((index * 0.19f + sin(index * 2.1f) * 0.08f) % 1.1f)
            val y = 0.12f + (index % 4) * 0.13f
            drawCloud(point(x, y), 0.04f + index % 3 * 0.012f)
        }
        drawCircle(Color(0xFFFFE39A).copy(alpha = 0.18f), radius(0.19f), point(0.78f, 0.2f))
        engine.entities.forEachIndexed { index, block ->
            val topLeft = point(block.x - block.size / 2f, block.y - 0.032f)
            val blockSize = Size(block.size * size.width, 0.068f * size.height)
            val base = if (block.kind == "active") Color(0xFFFFD66D) else
                accent.mix(Color(0xFF71F0C2), (index % 6) / 6f)
            drawRoundRect(base.copy(alpha = 0.22f), topLeft - Offset(0f, radius(0.018f)),
                blockSize.copy(height = blockSize.height + radius(0.036f)), CornerRadius(radius(0.018f)))
            drawRoundRect(
                Brush.verticalGradient(listOf(base.mix(Color.White, 0.28f), base.mix(Color.Black, 0.2f))),
                topLeft, blockSize, CornerRadius(radius(0.014f)),
            )
            repeat(3) { flower ->
                drawCircle(Color.White.copy(alpha = 0.72f), radius(0.0045f),
                    Offset(topLeft.x + blockSize.width * (0.22f + flower * 0.27f), topLeft.y + radius(0.012f)))
            }
        }
        drawHero(point(0.17f, 0.78f), 0.36f)
    }

    private fun DrawScope.drawSlimeSprint() {
        repeat(7) { index ->
            val x = ((index * 0.22f - engine.elapsed * (0.08f + index % 2 * 0.035f)) % 1.3f + 1.3f) % 1.3f
            drawCandyTree(point(x, 0.7f), 0.04f + index % 3 * 0.012f)
        }
        drawPath(Path().apply {
            moveTo(0f, size.height * 0.82f)
            repeat(30) { step ->
                val x = step / 29f
                val y = 0.82f + sin(x * 15f) * 0.012f
                lineTo(x * size.width, y * size.height)
            }
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }, Brush.verticalGradient(listOf(Color(0xFF7342B7), Color(0xFF241144))))
        engine.entities.forEach { entity ->
            if (entity.kind == "star") drawStar(point(entity.x, entity.y), entity.size, Color(0xFFFFDE63), entity.phase)
            else drawMushroom(point(entity.x, entity.y), entity.size)
        }
        if (engine.invulnerability <= 0f || (engine.elapsed * 12f).toInt() % 2 == 0) {
            drawHero(point(0.23f, engine.playerY), 0.31f)
        }
    }

    private fun DrawScope.drawOrbitDash() {
        repeat(46) { index ->
            val x = ((index * 37) % 101) / 100f
            val y = ((index * 61) % 97) / 100f
            val pulse = 0.45f + sin(engine.elapsed * 2f + index) * 0.25f
            drawCircle(Color.White.copy(alpha = pulse), radius(0.0025f + index % 3 * 0.0015f), point(x, y))
        }
        val center = point(0.5f, 0.51f)
        repeat(5) { ring ->
            drawCircle(accent.copy(alpha = 0.06f + ring * 0.045f),
                radius(0.13f + ring * 0.055f), center, style = Stroke(radius(0.005f)))
        }
        repeat(4) { layer ->
            drawCircle(Color(0xFFFFC85A).copy(alpha = 0.08f + layer * 0.16f),
                radius(0.13f - layer * 0.018f), center)
        }
        val orbitRadius = radius(0.31f)
        val beacon = Offset(center.x + cos(engine.targetAngle) * orbitRadius,
            center.y + sin(engine.targetAngle) * orbitRadius)
        repeat(4) { layer ->
            drawCircle(Color(0xFFFF69BC).copy(alpha = 0.10f + layer * 0.12f),
                radius(0.075f - layer * 0.013f), beacon)
        }
        drawStar(beacon, 0.035f, Color.White, engine.elapsed * 2f)
        engine.entities.forEach { meteor -> drawMeteor(point(engine.meteorX(meteor), engine.meteorY(meteor)), meteor.size) }
        if (engine.invulnerability <= 0f || (engine.elapsed * 12f).toInt() % 2 == 0) {
            drawHero(point(engine.playerOrbitX(), engine.playerOrbitY()), 0.27f)
        }
    }

    private fun DrawScope.drawTempleHop() {
        drawPath(Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.08f)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }, Brush.verticalGradient(listOf(Color(0xFF203428), Color(0xFF10170F))))
        repeat(10) { index ->
            val x = if (index % 2 == 0) 0.04f else 0.92f
            val y = 0.1f + index * 0.09f
            drawCircle(Color(0xFF2B9A68).copy(alpha = 0.32f), radius(0.07f), point(x, y))
        }
        repeat(3) { lane ->
            drawLine(Color(0xFFFFD469).copy(alpha = 0.12f), point(laneX(lane), 0.12f),
                point(laneX(lane), 1f), radius(0.003f))
        }
        engine.entities.forEach { stone ->
            val p = point(stone.x, stone.y)
            repeat(3) { layer ->
                drawCircle(Color(0xFFFFCA62).copy(alpha = 0.08f + layer * 0.13f),
                    radius(stone.size * (1.3f - layer * 0.18f)), p)
            }
            drawCircle(Color(0xFF9A6535), radius(stone.size), p)
            drawPath(Path().apply {
                moveTo(p.x, p.y - radius(stone.size * 0.55f))
                lineTo(p.x + radius(stone.size * 0.48f), p.y + radius(stone.size * 0.35f))
                lineTo(p.x - radius(stone.size * 0.48f), p.y + radius(stone.size * 0.35f))
                close()
            }, Color(0xFFFFE29A))
        }
        if (engine.invulnerability <= 0f || (engine.elapsed * 12f).toInt() % 2 == 0) {
            drawHero(point(engine.playerX, 0.8f), 0.28f)
        }
    }

    private fun DrawScope.drawPixelPunch() {
        repeat(9) { index ->
            val x = 0.07f + (index % 3) * 0.31f
            val y = 0.15f + (index / 3) * 0.27f
            drawRoundRect(Color(0xFF4DE7FF).copy(alpha = 0.035f), point(x, y),
                Size(size.width * 0.25f, size.height * 0.19f), CornerRadius(radius(0.016f)),
                style = Stroke(radius(0.002f)))
        }
        repeat(13) { index ->
            val y = ((index / 13f + engine.elapsed * 0.22f) % 1f)
            drawLine(accent.copy(alpha = 0.06f), point(0f, y), point(1f, y), radius(0.002f))
        }
        engine.entities.forEach { target ->
            val p = point(target.x, target.y)
            val urgency = (1f - target.phase / 2.2f).coerceIn(0f, 1f)
            val color = if (target.value == 1) Color(0xFFFFD45E) else Color(0xFFFF4F9D)
            repeat(4) { layer ->
                drawCircle(color.copy(alpha = 0.08f + layer * 0.11f),
                    radius(target.size * (1.7f - layer * 0.18f) * (1f + urgency * 0.18f)), p)
            }
            drawCircle(Color(0xFF101324), radius(target.size * 0.78f), p)
            drawCircle(Color.White, radius(target.size * 0.18f), p)
            drawArc(color, -90f, 360f * (target.phase / if (target.value == 1) 1.05f else 2.2f).coerceIn(0f, 1f),
                false, Offset(p.x - radius(target.size), p.y - radius(target.size)),
                Size(radius(target.size * 2f), radius(target.size * 2f)), style = Stroke(radius(0.009f), cap = StrokeCap.Round))
        }
        drawHero(point(0.13f, 0.82f), 0.35f)
    }

    private fun DrawScope.drawWaveRider() {
        drawCircle(Color(0xFFFFBC7B).copy(alpha = 0.16f), radius(0.2f), point(0.82f, 0.18f))
        repeat(7) { wave ->
            val path = Path()
            val baseY = 0.18f + wave * 0.12f
            path.moveTo(0f, baseY * size.height)
            repeat(34) { step ->
                val x = step / 33f
                val amplitude = 0.022f + wave * 0.004f
                val y = baseY + sin(x * 10f + engine.elapsed * (1.4f + wave * 0.08f) + wave) * amplitude
                path.lineTo(x * size.width, y * size.height)
            }
            drawPath(path, Color(0xFF5BE7FF).copy(alpha = 0.09f + wave * 0.055f),
                style = Stroke(radius(0.006f + wave * 0.001f), cap = StrokeCap.Round))
        }
        engine.entities.forEach { item ->
            if (item.kind == "pearl") {
                repeat(3) { layer -> drawCircle(Color.White.copy(alpha = 0.12f + layer * 0.22f),
                    radius(item.size * (1.6f - layer * 0.25f)), point(item.x, item.y)) }
            } else {
                drawMine(point(item.x, item.y), item.size, item.phase)
            }
        }
        if (engine.invulnerability <= 0f || (engine.elapsed * 12f).toInt() % 2 == 0) {
            drawHero(point(engine.playerX, engine.playerY), 0.33f)
        }
    }

    private fun DrawScope.drawMoonMiner() {
        repeat(44) { index ->
            val x = ((index * 37) % 103) / 102f
            val y = ((index * 61) % 79) / 100f
            drawCircle(Color.White.copy(alpha = 0.2f + index % 4 * 0.14f),
                radius(0.0025f + index % 3 * 0.0015f), point(x, y))
        }
        drawPath(Path().apply {
            moveTo(0f, size.height * 0.8f)
            repeat(18) { step ->
                val x = step / 17f
                lineTo(x * size.width, (0.79f + sin(x * 17f) * 0.035f) * size.height)
            }
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }, Brush.verticalGradient(listOf(Color(0xFF332B55), Color(0xFF171329))))
        engine.entities.forEach { core ->
            val p = point(core.x, core.y)
            if (core.kind == "volatile") drawVolatileCore(p, core.size, core.phase)
            else drawCrystal(p, core.size, core.health)
        }
        drawHero(point(0.16f, 0.81f), 0.34f)
    }

    private fun DrawScope.drawParticles() {
        engine.particles.forEach { particle ->
            val color = when (particle.palette) {
                1 -> Color(0xFFFF315F)
                2 -> Color.White
                3 -> Color(0xFFFFD45E)
                else -> accent
            }
            val alpha = (particle.life / particle.maximumLife).coerceIn(0f, 1f)
            drawCircle(color.copy(alpha = alpha * 0.18f), radius(particle.size * 2.6f), point(particle.x, particle.y))
            drawCircle(color.copy(alpha = alpha), radius(particle.size), point(particle.x, particle.y))
        }
    }

    private fun DrawScope.drawHero(center: Offset, normalizedSize: Float) {
        val image = hero ?: return
        val side = radius(normalizedSize)
        val topLeft = IntOffset((center.x - side / 2f).toInt(), (center.y - side / 2f).toInt())
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = topLeft,
            dstSize = IntSize(side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1)),
            alpha = 1f,
        )
    }

    private fun DrawScope.drawGlowLine(start: Offset, end: Offset, color: Color, width: Float) {
        repeat(3) { layer -> drawLine(color.copy(alpha = 0.08f + layer * 0.18f), start, end,
            radius(width * (3.4f - layer)), StrokeCap.Round) }
    }

    private fun DrawScope.drawCloud(center: Offset, scale: Float) {
        val color = Color.White.copy(alpha = 0.08f)
        drawCircle(color, radius(scale), center)
        drawCircle(color, radius(scale * 0.75f), center + Offset(radius(scale * 0.8f), radius(scale * 0.16f)))
        drawCircle(color, radius(scale * 0.65f), center - Offset(radius(scale * 0.78f), -radius(scale * 0.2f)))
    }

    private fun DrawScope.drawCandyTree(base: Offset, scale: Float) {
        drawRoundRect(Color(0xFF73452C), Offset(base.x - radius(scale * 0.12f), base.y),
            Size(radius(scale * 0.24f), radius(scale * 1.6f)), CornerRadius(radius(scale * 0.1f)))
        drawCircle(Color(0xFFFF69B8).copy(alpha = 0.7f), radius(scale), base - Offset(0f, radius(scale * 0.48f)))
        drawCircle(Color(0xFF74E7FF).copy(alpha = 0.65f), radius(scale * 0.66f),
            base + Offset(radius(scale * 0.55f), -radius(scale * 0.38f)))
    }

    private fun DrawScope.drawMushroom(center: Offset, scale: Float) {
        drawRoundRect(Color(0xFFFFE6B0), Offset(center.x - radius(scale * 0.2f), center.y - radius(scale * 0.2f)),
            Size(radius(scale * 0.4f), radius(scale * 0.9f)), CornerRadius(radius(scale * 0.16f)))
        drawArc(Color(0xFFFF4F9D), 180f, 180f, true,
            Offset(center.x - radius(scale), center.y - radius(scale * 0.85f)),
            Size(radius(scale * 2f), radius(scale * 1.45f)))
        drawCircle(Color.White, radius(scale * 0.13f), center - Offset(radius(scale * 0.4f), radius(scale * 0.43f)))
        drawCircle(Color.White, radius(scale * 0.1f), center + Offset(radius(scale * 0.34f), -radius(scale * 0.34f)))
    }

    private fun DrawScope.drawStar(center: Offset, scale: Float, color: Color, phase: Float) {
        val outer = radius(scale * (1f + sin(phase * 3f) * 0.08f))
        val inner = outer * 0.44f
        val path = Path()
        repeat(10) { index ->
            val angle = -Math.PI / 2.0 + index * Math.PI / 5.0
            val r = if (index % 2 == 0) outer else inner
            val p = Offset(center.x + cos(angle).toFloat() * r, center.y + sin(angle).toFloat() * r)
            if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawCircle(color.copy(alpha = 0.18f), outer * 1.65f, center)
        drawPath(path, color)
    }

    private fun DrawScope.drawRivet(center: Offset, scale: Float, phase: Float) {
        val pulse = 1f + sin(phase * 4f) * 0.08f
        repeat(3) { layer -> drawCircle(accent.copy(alpha = 0.09f + layer * 0.12f),
            radius(scale * pulse * (1.7f - layer * 0.25f)), center) }
        drawCircle(Color(0xFF6D3CB0), radius(scale * 0.85f), center)
        drawStar(center, scale * 0.42f, Color(0xFF70F0FF), phase)
    }

    private fun DrawScope.drawMeteor(center: Offset, scale: Float) {
        drawCircle(Color(0xFFFF5D76).copy(alpha = 0.14f), radius(scale * 1.5f), center)
        drawCircle(Color(0xFF65506F), radius(scale), center)
        drawCircle(Color(0xFF31263D), radius(scale * 0.27f), center - Offset(radius(scale * 0.3f), radius(scale * 0.2f)))
        drawCircle(Color(0xFF31263D), radius(scale * 0.16f), center + Offset(radius(scale * 0.35f), radius(scale * 0.28f)))
    }

    private fun DrawScope.drawMine(center: Offset, scale: Float, phase: Float) {
        repeat(8) { index ->
            val angle = index * Math.PI.toFloat() / 4f + phase * 0.1f
            drawLine(Color(0xFFFF5A77), center,
                center + Offset(cos(angle) * radius(scale * 1.45f), sin(angle) * radius(scale * 1.45f)),
                radius(0.006f), StrokeCap.Round)
        }
        drawCircle(Color(0xFF20192C), radius(scale), center)
        drawCircle(Color(0xFFFF315F), radius(scale * 0.22f), center)
    }

    private fun DrawScope.drawCrystal(center: Offset, scale: Float, health: Int) {
        val color = when (health) { 3 -> Color(0xFF6EEBFF); 2 -> Color(0xFFA783FF); else -> Color(0xFFFF72C6) }
        drawCircle(color.copy(alpha = 0.16f), radius(scale * 1.55f), center)
        drawPath(Path().apply {
            moveTo(center.x, center.y - radius(scale))
            lineTo(center.x + radius(scale * 0.58f), center.y - radius(scale * 0.2f))
            lineTo(center.x + radius(scale * 0.38f), center.y + radius(scale))
            lineTo(center.x - radius(scale * 0.42f), center.y + radius(scale * 0.78f))
            lineTo(center.x - radius(scale * 0.62f), center.y - radius(scale * 0.22f))
            close()
        }, Brush.linearGradient(listOf(Color.White, color, color.mix(Color.Black, 0.35f))))
    }

    private fun DrawScope.drawVolatileCore(center: Offset, scale: Float, phase: Float) {
        val pulse = 1f + sin(phase * 7f) * 0.12f
        drawCircle(Color(0xFFFF315F).copy(alpha = 0.18f), radius(scale * 1.7f * pulse), center)
        drawCircle(Color(0xFF4B1728), radius(scale), center)
        drawLine(Color(0xFFFFD45E), center - Offset(radius(scale * 0.5f), radius(scale * 0.5f)),
            center + Offset(radius(scale * 0.5f), radius(scale * 0.5f)), radius(0.008f), StrokeCap.Round)
        drawLine(Color(0xFFFFD45E), center + Offset(radius(scale * 0.5f), -radius(scale * 0.5f)),
            center + Offset(-radius(scale * 0.5f), radius(scale * 0.5f)), radius(0.008f), StrokeCap.Round)
    }

    private fun DrawScope.point(x: Float, y: Float): Offset = Offset(x * size.width, y * size.height)
    private fun DrawScope.radius(value: Float): Float = value * min(size.width, size.height)
    private fun laneX(lane: Int): Float = 0.24f + lane.coerceIn(0, 2) * 0.26f
}

@RequiresApi(33)
private class ArcadeBackdropShader private constructor(
    private val shader: RuntimeShader,
    private val paint: Paint,
) {
    fun draw(scope: DrawScope, time: Float, background: Color, accent: Color) {
        shader.setFloatUniform("resolution", scope.size.width, scope.size.height)
        shader.setFloatUniform("time", time)
        shader.setFloatUniform("base", background.red, background.green, background.blue)
        shader.setFloatUniform("accent", accent.red, accent.green, accent.blue)
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
        }
    }

    companion object {
        fun create(): ArcadeBackdropShader? = try {
            val runtime = RuntimeShader(SOURCE)
            Log.i("AblaArcade", "AGSL backdrop enabled")
            ArcadeBackdropShader(runtime, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtime })
        } catch (failure: RuntimeException) {
            Log.w("AblaArcade", "AGSL backdrop unavailable; using gradient fallback", failure)
            null
        }

        private const val SOURCE = """
            uniform float2 resolution;
            uniform float time;
            uniform float3 base;
            uniform float3 accent;

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / resolution;
                float2 p = uv * 2.0 - 1.0;
                p.x *= resolution.x / resolution.y;
                float energy = 0.5 + 0.5 * sin(p.x * 4.0 - time * 0.55 + p.y * 2.2);
                float vignette = clamp(1.15 - dot(p, p) * 0.34, 0.0, 1.0);
                float horizon = clamp(1.0 - abs(uv.y - 0.42) * 5.0, 0.0, 1.0);
                float3 color = base * (0.78 + vignette * 0.28);
                color += accent * horizon * (0.045 + energy * 0.055);
                return half4(color, 1.0);
            }
        """
    }
}

internal fun Color.mix(other: Color, amount: Float): Color {
    val ratio = amount.coerceIn(0f, 1f)
    return Color(
        red = red + (other.red - red) * ratio,
        green = green + (other.green - green) * ratio,
        blue = blue + (other.blue - blue) * ratio,
        alpha = alpha + (other.alpha - alpha) * ratio,
    )
}
