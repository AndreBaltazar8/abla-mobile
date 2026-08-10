package org.abla.mobile.host

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class GamePhase { Playing, GameOver, Victory }

internal data class GameEntity(
    var kind: String,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var size: Float = 0.06f,
    var phase: Float = 0f,
    var value: Int = 0,
    var health: Int = 1,
    var alive: Boolean = true,
)

internal data class GameParticle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maximumLife: Float,
    val size: Float,
    val palette: Int,
)

internal data class GamePointer(val x: Float, val y: Float, val pressed: Boolean)

internal data class GameHud(
    val score: Int,
    val combo: Int,
    val lives: Int,
    val progress: Int,
    val target: Int,
    val phase: GamePhase,
    val mission: String,
)

internal enum class ArcadeGame(
    val id: String,
    val label: String,
    val hint: String,
    val mission: String,
    val target: Int,
) {
    NeonDrift("neon_drift", "NEON DRIFT", "Drag to steer · dodge red gates", "CLEAR THE HYPERWAY", 24),
    SkyStack("sky_stack", "SKY STACK", "Tap to lock the moving floor", "BUILD 18 FLOORS", 18),
    SlimeSprint("slime_sprint", "SLIME SPRINT", "Tap to jump · grab stars", "ESCAPE THE CANDY LAB", 22),
    OrbitDash("orbit_dash", "ORBIT DASH", "Tap to reverse your orbit", "LINK 16 STAR BEACONS", 16),
    TempleHop("temple_hop", "TEMPLE HOP", "Tap left or right to change lanes", "CROSS 24 SUN STONES", 24),
    PixelPunch("pixel_punch", "PIXEL PUNCH", "Punch targets before they expire", "LAND 24 POWER HITS", 24),
    WaveRider("wave_rider", "WAVE RIDER", "Drag through pearls · avoid mines", "RIDE 20 PERFECT BREAKS", 20),
    MoonMiner("moon_miner", "MOON MINER", "Drill crystals · avoid volatile cores", "EXTRACT 10 MOON CORES", 10),
    ;

    companion object {
        fun fromId(id: String): ArcadeGame = entries.firstOrNull { it.id == id } ?: NeonDrift
    }
}

/**
 * Deterministic, renderer-independent arcade simulation. Every external frame
 * is broken into fixed 120 Hz steps so collision and game-over behavior do not
 * change with display refresh rate.
 */
internal class NativeArcadeEngine(
    val game: ArcadeGame,
    private val audio: GameAudio,
) {
    val entities = mutableListOf<GameEntity>()
    val particles = mutableListOf<GameParticle>()
    var score = 0
        private set
    var combo = 1
        private set
    var lives = 3
        private set
    var progress = 0
        private set
    var phase = GamePhase.Playing
        private set
    var elapsed = 0f
        private set
    var runElapsed = 0f
        private set
    var playerX = 0.5f
        private set
    var playerY = 0.78f
        private set
    var orbitAngle = -1.55f
        private set
    var targetAngle = 0.72f
        private set
    var direction = 1f
        private set
    var shake = 0f
        private set
    var flash = 0f
        private set
    var invulnerability = 0f
        private set

    private var accumulator = 0f
    private var spawnClock = 0f
    private var seed = game.id.hashCode().toLong() and 0xffffffffL
    private var playerVelocity = 0f
    private var templeLane = 1
    private var lastOrbitDistance = 10f
    private var stackDirection = 1f

    init {
        resetRun()
    }

    val hud: GameHud
        get() = GameHud(score, combo, lives, progress, game.target, phase, game.mission)

    fun update(deltaSeconds: Float) {
        accumulator += deltaSeconds.coerceIn(0f, 0.10f)
        val step = 1f / 120f
        var iterations = 0
        while (accumulator >= step && iterations < 12) {
            fixedUpdate(step)
            accumulator -= step
            iterations += 1
        }
        if (iterations == 12) accumulator = 0f
    }

    fun pointer(pointer: GamePointer) {
        if (pointer.pressed && phase != GamePhase.Playing) {
            resetRun()
            return
        }
        if (phase != GamePhase.Playing) return
        when (game) {
            ArcadeGame.NeonDrift -> playerX = pointer.x.coerceIn(0.15f, 0.85f)
            ArcadeGame.SkyStack -> if (pointer.pressed) placeBlock()
            ArcadeGame.SlimeSprint -> if (pointer.pressed && playerY >= 0.755f) {
                playerVelocity = -1.08f
                burst(0.23f, 0.81f, 7, 2)
                audio.action()
            }
            ArcadeGame.OrbitDash -> if (pointer.pressed) {
                direction = -direction
                burst(playerOrbitX(), playerOrbitY(), 8, 0)
                audio.action()
            }
            ArcadeGame.TempleHop -> if (pointer.pressed) {
                templeLane = if (pointer.x < 0.5f) max(0, templeLane - 1) else min(2, templeLane + 1)
                playerX = laneX(templeLane)
                burst(playerX, 0.8f, 5, 3)
                audio.action()
            }
            ArcadeGame.PixelPunch -> if (pointer.pressed) hitTarget(pointer.x, pointer.y)
            ArcadeGame.WaveRider -> {
                playerX = pointer.x.coerceIn(0.13f, 0.42f)
                playerY = pointer.y.coerceIn(0.2f, 0.82f)
            }
            ArcadeGame.MoonMiner -> if (pointer.pressed) mineCore(pointer.x, pointer.y)
        }
    }

    private fun fixedUpdate(dt: Float) {
        elapsed += dt
        shake = max(0f, shake - dt * 3.2f)
        flash = max(0f, flash - dt * 2.8f)
        invulnerability = max(0f, invulnerability - dt)
        updateParticles(dt)
        if (phase != GamePhase.Playing) return
        runElapsed += dt
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

    private fun resetRun() {
        entities.clear()
        particles.clear()
        score = 0
        combo = 1
        lives = 3
        progress = 0
        phase = GamePhase.Playing
        runElapsed = 0f
        spawnClock = 0f
        accumulator = 0f
        playerX = if (game == ArcadeGame.WaveRider) 0.22f else 0.5f
        playerY = 0.78f
        playerVelocity = 0f
        templeLane = 1
        orbitAngle = -1.55f
        targetAngle = 0.72f
        lastOrbitDistance = 10f
        direction = 1f
        stackDirection = 1f
        invulnerability = 0f
        when (game) {
            ArcadeGame.SkyStack -> {
                entities += GameEntity("block", 0.5f, 0.87f, size = 0.34f)
                spawnActiveBlock(0.79f)
            }
            ArcadeGame.PixelPunch -> repeat(4) { spawnTarget() }
            ArcadeGame.MoonMiner -> repeat(6) { spawnCore() }
            ArcadeGame.TempleHop -> repeat(6) { index ->
                spawnTempleStone(-0.1f + index * 0.18f)
            }
            else -> Unit
        }
    }

    private fun updateNeonDrift(dt: Float) {
        val interval = max(0.38f, 0.78f - progress * 0.012f)
        if (spawnClock >= interval) {
            spawnClock = 0f
            val lane = (nextFloat() * 3f).toInt().coerceIn(0, 2)
            val pickup = nextFloat() < 0.24f
            entities += GameEntity(
                if (pickup) "rivet" else "gate",
                laneX(lane), -0.1f,
                vy = 0.48f + progress * 0.008f,
                size = if (pickup) 0.04f else 0.08f,
            )
        }
        entities.forEach { entity ->
            entity.y += entity.vy * dt
            entity.phase += dt
            if (entity.y >= 0.79f && entity.value == 0) {
                entity.value = 1
                val aligned = abs(entity.x - playerX) < if (entity.kind == "rivet") 0.11f else 0.105f
                if (entity.kind == "rivet") {
                    if (aligned) reward(40, 0, entity.x, entity.y, 3)
                } else if (aligned) {
                    damage(entity.x, entity.y)
                } else {
                    reward(12, 1, entity.x, entity.y, 0)
                }
            }
            if (entity.y > 1.15f) entity.alive = false
        }
    }

    private fun updateSkyStack(dt: Float) {
        val active = entities.lastOrNull { it.kind == "active" } ?: return
        active.x += active.vx * dt
        if (active.x <= active.size / 2f || active.x >= 1f - active.size / 2f) {
            active.vx = -active.vx
            active.x = active.x.coerceIn(active.size / 2f, 1f - active.size / 2f)
        }
    }

    private fun placeBlock() {
        val active = entities.lastOrNull { it.kind == "active" } ?: return
        val previous = entities.lastOrNull { it.kind == "block" } ?: return
        val overlap = active.size - abs(active.x - previous.x)
        if (overlap <= 0.025f) {
            active.alive = false
            damage(active.x, active.y)
            if (phase == GamePhase.Playing) spawnActiveBlock(active.y)
            return
        }
        active.kind = "block"
        active.vx = 0f
        active.size = overlap
        active.x = (active.x + previous.x) * 0.5f
        reward((overlap * 180f).toInt(), 1, active.x, active.y, 3)
        if (phase != GamePhase.Playing) return
        val blocks = entities.count { it.kind == "block" }
        if (blocks >= 10) entities.filter { it.kind == "block" }.forEach { it.y += 0.075f }
        spawnActiveBlock((active.y - 0.075f).coerceAtLeast(0.15f))
    }

    private fun spawnActiveBlock(y: Float) {
        stackDirection = -stackDirection
        val width = entities.lastOrNull { it.kind == "block" }?.size ?: 0.34f
        entities += GameEntity(
            "active",
            if (stackDirection > 0f) width / 2f else 1f - width / 2f,
            y,
            vx = stackDirection * (0.46f + progress * 0.014f),
            size = width,
        )
    }

    private fun updateSlimeSprint(dt: Float) {
        playerVelocity += 2.75f * dt
        playerY += playerVelocity * dt
        if (playerY > 0.78f) {
            playerY = 0.78f
            playerVelocity = 0f
        }
        if (spawnClock >= max(0.68f, 1.02f - progress * 0.008f)) {
            spawnClock = 0f
            val star = nextFloat() < 0.28f
            entities += GameEntity(
                if (star) "star" else "mushroom",
                1.08f,
                if (star) randomBetween(0.54f, 0.68f) else 0.79f,
                vx = -(0.46f + progress * 0.008f),
                size = if (star) 0.038f else 0.062f,
            )
        }
        entities.forEach { entity ->
            entity.x += entity.vx * dt
            entity.phase += dt * 5f
            if (entity.x <= 0.29f && entity.value == 0) {
                entity.value = 1
                if (entity.kind == "star") {
                    if (distance(entity.x, entity.y, 0.23f, playerY) < 0.12f) {
                        entity.alive = false
                        reward(35, 0, entity.x, entity.y, 3)
                    }
                } else if (playerY > 0.69f) {
                    damage(entity.x, entity.y)
                } else {
                    reward(15, 1, entity.x, entity.y, 2)
                }
            }
            if (entity.x < -0.12f) entity.alive = false
        }
    }

    private fun updateOrbitDash(dt: Float) {
        orbitAngle += direction * (1.72f + progress * 0.035f) * dt
        val beaconDistance = angularDistance(orbitAngle, targetAngle)
        if (beaconDistance < 0.075f && lastOrbitDistance >= 0.075f) {
            reward(28, 1, playerOrbitX(), playerOrbitY(), 0)
            targetAngle += 1.35f + nextFloat() * 2.7f
        }
        lastOrbitDistance = beaconDistance
        if (spawnClock >= 2.4f) {
            spawnClock = 0f
            entities += GameEntity("meteor", 0f, 0f, phase = nextFloat() * TAU, size = 0.055f)
        }
        entities.forEach { meteor ->
            meteor.phase += 0.46f * dt
            meteor.value += 1
            if (meteor.value > 30 && angularDistance(orbitAngle, meteor.phase) < 0.09f) {
                meteor.alive = false
                damage(playerOrbitX(), playerOrbitY())
            }
            if (meteor.value > 540) meteor.alive = false
        }
    }

    private fun updateTempleHop(dt: Float) {
        var replacements = 0
        entities.forEach { stone ->
            stone.y += stone.vy * dt
            stone.phase += dt
            if (stone.y >= 0.78f && stone.health == 1) {
                stone.health = 0
                if (stone.value == templeLane) reward(18, 1, stone.x, stone.y, 3)
                else damage(laneX(stone.value), stone.y)
            }
            if (stone.y > 1.1f) {
                stone.alive = false
                replacements += 1
            }
        }
        repeat(replacements) { spawnTempleStone(-0.12f) }
    }

    private fun spawnTempleStone(y: Float) {
        val safeLane = (nextFloat() * 3f).toInt().coerceIn(0, 2)
        entities += GameEntity("stone", laneX(safeLane), y, vy = 0.25f + progress * 0.004f,
            size = 0.085f, value = safeLane)
    }

    private fun updatePixelPunch(dt: Float) {
        var replacements = 0
        entities.forEach { target ->
            target.phase -= dt
            if (target.phase <= 0f) {
                damage(target.x, target.y)
                target.alive = false
                if (phase == GamePhase.Playing) replacements += 1
            }
        }
        repeat(replacements) { spawnTarget() }
    }

    private fun hitTarget(x: Float, y: Float) {
        val hit = entities.filter { it.kind == "target" }.minByOrNull { distance(it.x, it.y, x, y) }
        if (hit != null && distance(hit.x, hit.y, x, y) < hit.size * 1.55f) {
            hit.alive = false
            reward(if (hit.value == 1) 60 else 30, 1, hit.x, hit.y, if (hit.value == 1) 3 else 0)
            if (phase == GamePhase.Playing) spawnTarget()
            audio.action()
        } else {
            combo = 1
            flash = max(flash, 0.14f)
            audio.miss()
        }
    }

    private fun spawnTarget() {
        val elite = nextFloat() < 0.18f
        entities += GameEntity(
            "target", randomBetween(0.14f, 0.86f), randomBetween(0.22f, 0.76f),
            size = if (elite) 0.052f else randomBetween(0.058f, 0.078f),
            phase = if (elite) 1.05f else randomBetween(1.35f, 2.2f), value = if (elite) 1 else 0,
        )
    }

    private fun updateWaveRider(dt: Float) {
        if (spawnClock >= max(0.42f, 0.72f - progress * 0.008f)) {
            spawnClock = 0f
            val mine = nextFloat() < 0.35f
            entities += GameEntity(
                if (mine) "mine" else "pearl", 1.08f, randomBetween(0.22f, 0.8f),
                vx = -(0.39f + progress * 0.009f), size = if (mine) 0.052f else 0.035f,
            )
        }
        entities.forEach { item ->
            item.x += item.vx * dt
            item.phase += dt * 5f
            if (item.value == 0 && distance(item.x, item.y, playerX, playerY) < item.size + 0.065f) {
                item.value = 1
                item.alive = false
                if (item.kind == "mine") damage(item.x, item.y)
                else reward(22, 1, item.x, item.y, 0)
            }
            if (item.x < -0.1f) item.alive = false
        }
        if ((runElapsed * 30f).toInt() % 2 == 0) {
            particles += GameParticle(playerX - 0.07f, playerY + 0.045f, -0.18f,
                randomBetween(-0.06f, 0.06f), 0.45f, 0.45f, randomBetween(0.006f, 0.018f), 2)
        }
    }

    private fun updateMoonMiner(dt: Float) {
        entities.forEach { core ->
            core.phase += dt
            core.y += sin(core.phase * 1.6f) * dt * 0.005f
        }
        if (entities.count { it.kind == "core" || it.kind == "volatile" } < 5) spawnCore()
    }

    private fun mineCore(x: Float, y: Float) {
        val core = entities.filter { it.kind == "core" || it.kind == "volatile" }
            .minByOrNull { distance(it.x, it.y, x, y) }
        if (core == null || distance(core.x, core.y, x, y) >= core.size * 1.5f) {
            combo = 1
            audio.miss()
            return
        }
        if (core.kind == "volatile") {
            core.alive = false
            damage(core.x, core.y)
            return
        }
        core.health -= 1
        reward(8, 0, core.x, core.y, 0)
        audio.action()
        if (core.health <= 0) {
            core.alive = false
            reward(45, 1, core.x, core.y, 3)
        }
    }

    private fun spawnCore() {
        val volatile = nextFloat() < 0.18f
        entities += GameEntity(
            if (volatile) "volatile" else "core",
            randomBetween(0.13f, 0.87f), randomBetween(0.2f, 0.72f),
            size = randomBetween(0.055f, 0.088f), phase = nextFloat() * 5f,
            health = if (volatile) 1 else 3,
        )
    }

    private fun reward(points: Int, objective: Int, x: Float, y: Float, palette: Int) {
        if (phase != GamePhase.Playing) return
        score += points * combo
        combo = min(combo + 1, 12)
        progress = min(game.target, progress + objective)
        flash = max(flash, 0.18f)
        burst(x, y, if (objective > 0) 14 else 8, palette)
        audio.reward(combo)
        if (progress >= game.target) {
            phase = GamePhase.Victory
            shake = 0.18f
            burst(0.5f, 0.48f, 72, 3)
            audio.victory()
        }
    }

    private fun damage(x: Float, y: Float) {
        if (phase != GamePhase.Playing || invulnerability > 0f) return
        lives = max(0, lives - 1)
        combo = 1
        invulnerability = 1.0f
        shake = 1f
        flash = 1f
        burst(x, y, 28, 1)
        if (lives == 0) {
            phase = GamePhase.GameOver
            audio.gameOver()
        } else {
            audio.impact()
        }
    }

    private fun burst(x: Float, y: Float, count: Int, palette: Int) {
        repeat(count) {
            val angle = nextFloat() * TAU
            val speed = randomBetween(0.08f, 0.48f)
            val life = randomBetween(0.28f, 0.8f)
            particles += GameParticle(
                x, y, kotlin.math.cos(angle) * speed, kotlin.math.sin(angle) * speed,
                life, life, randomBetween(0.004f, 0.018f), palette,
            )
        }
    }

    private fun updateParticles(dt: Float) {
        particles.forEach { particle ->
            particle.x += particle.vx * dt
            particle.y += particle.vy * dt
            particle.vy += 0.22f * dt
            particle.vx *= 1f - dt * 0.7f
            particle.life -= dt
        }
        particles.removeAll { it.life <= 0f }
        if (particles.size > 220) particles.subList(0, particles.size - 220).clear()
    }

    fun playerOrbitX(): Float = 0.5f + kotlin.math.cos(orbitAngle) * 0.31f
    fun playerOrbitY(): Float = 0.51f + kotlin.math.sin(orbitAngle) * 0.31f
    fun meteorX(entity: GameEntity): Float = 0.5f + kotlin.math.cos(entity.phase) * 0.31f
    fun meteorY(entity: GameEntity): Float = 0.51f + kotlin.math.sin(entity.phase) * 0.31f

    private fun nextFloat(): Float {
        seed = (seed * 1664525L + 1013904223L) and 0xffffffffL
        return ((seed ushr 8) and 0xffffffL).toFloat() / 0x1000000.toFloat()
    }

    private fun randomBetween(low: Float, high: Float): Float = low + (high - low) * nextFloat()
    private fun laneX(lane: Int): Float = 0.24f + lane.coerceIn(0, 2) * 0.26f

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun angularDistance(first: Float, second: Float): Float {
        val normalized = ((first - second) % TAU + TAU) % TAU
        return min(normalized, TAU - normalized)
    }

    private companion object {
        const val TAU = 6.2831855f
    }
}
