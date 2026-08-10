package org.abla.mobile.host

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeGameEngineTest {
    @Test
    fun everyGameSurvivesAStressRunWithInputAndBoundedState() {
        ArcadeGame.entries.forEach { game ->
            val engine = NativeArcadeEngine(game, SilentGameAudio)
            repeat(3_600) { frame ->
                if (frame % 41 == 0) {
                    val x = 0.12f + (frame % 700) / 900f
                    val y = 0.24f + (frame % 400) / 700f
                    engine.pointer(GamePointer(x.coerceAtMost(0.88f), y.coerceAtMost(0.82f), true))
                }
                engine.update(1f / 120f)
                assertTrue("${game.id} entity list escaped its bound", engine.entities.size < 80)
                assertTrue("${game.id} particle list escaped its bound", engine.particles.size <= 220)
            }
        }
    }

    @Test
    fun gameOverLatchesUntilExplicitRetry() {
        val engine = NativeArcadeEngine(ArcadeGame.PixelPunch, SilentGameAudio)

        repeat(4_000) {
            if (engine.phase == GamePhase.Playing) engine.update(1f / 60f)
        }

        assertEquals(GamePhase.GameOver, engine.phase)
        assertEquals(0, engine.lives)
        val terminalScore = engine.score
        repeat(120) { engine.update(1f / 60f) }
        assertEquals(GamePhase.GameOver, engine.phase)
        assertEquals(terminalScore, engine.score)

        engine.pointer(GamePointer(0.5f, 0.5f, true))
        assertEquals(GamePhase.Playing, engine.phase)
        assertEquals(3, engine.lives)
        assertEquals(0, engine.score)
        assertEquals(0, engine.progress)
    }

    @Test
    fun skyStackCanCompleteItsMissionAndVictoryLatches() {
        val engine = NativeArcadeEngine(ArcadeGame.SkyStack, SilentGameAudio)

        repeat(ArcadeGame.SkyStack.target) {
            var guard = 0
            while (engine.phase == GamePhase.Playing) {
                val active = engine.entities.last { it.kind == "active" }
                val previous = engine.entities.last { it.kind == "block" }
                if (abs(active.x - previous.x) < 0.0035f) break
                engine.update(1f / 120f)
                guard += 1
                assertTrue("active block never reached the stack", guard < 2_000)
            }
            if (engine.phase == GamePhase.Playing) {
                engine.pointer(GamePointer(0.5f, 0.5f, true))
            }
        }

        assertEquals(GamePhase.Victory, engine.phase)
        assertEquals(ArcadeGame.SkyStack.target, engine.progress)
        val winningScore = engine.score
        repeat(120) { engine.update(1f / 60f) }
        assertEquals(GamePhase.Victory, engine.phase)
        assertEquals(winningScore, engine.score)
    }
}
