package de.eneriq.pilot

import kotlin.test.*

class ShortcutTest {
    private val s = Steering()

    /** Simuliert ein Boot, das dem Sollwert sofort folgt; liefert alle gesendeten Sollwerte. */
    private fun run(r: ShortcutRunner, start: Double): List<Double> {
        var c = start
        val sent = mutableListOf<Double>()
        repeat(40) {
            when (val t = r.tick(c, c)) {
                is Tick.Step -> { c = t.command; sent += c }
                is Tick.Done -> { sent += t.command; return sent }
                else -> return sent
            }
        }
        return sent
    }

    @Test fun foresailFromStarboardBearsAwayInSteps() {
        val r = ShortcutRunner(s)
        assertIs<Result.Ok>(r.start(40.0, 40.0, 110.0))
        val sent = run(r, 40.0)
        assertEquals(listOf(50.0, 60.0, 70.0, 80.0, 90.0, 100.0, 110.0), sent)
        assertFalse(r.active)
    }

    @Test fun mainsailFromPortLuffsAndStaysOnPortSide() {
        val r = ShortcutRunner(s)
        assertIs<Result.Ok>(r.start(-110.0, -110.0, 38.0))
        val sent = run(r, -110.0)
        assertEquals(-38.0, sent.last())
        assertTrue(sent.all { it < 0 && it >= -110.0 })
    }

    @Test fun portSideForesail() {
        val r = ShortcutRunner(s)
        r.start(-40.0, -40.0, 110.0)
        assertEquals(-110.0, run(r, -40.0).last())
    }

    @Test fun neverBeyondMaxAngle() {
        val r = ShortcutRunner(s)
        assertIs<Result.Rejected>(r.start(40.0, 40.0, 175.0))
        assertFalse(r.active)
    }

    @Test fun windSideChangeAborts() {
        val r = ShortcutRunner(s)
        r.start(40.0, 40.0, 110.0)
        assertIs<Tick.Step>(r.tick(40.0, 40.0))
        val t = r.tick(-30.0, 50.0)
        assertEquals(Tick.Aborted("Windseite gewechselt"), t)
        assertFalse(r.active)
        assertEquals(Tick.Idle, r.tick(-30.0, 50.0))
    }

    @Test fun boatNotFollowingAborts() {
        val r = ShortcutRunner(s)
        r.start(40.0, 40.0, 110.0)
        assertEquals(Tick.Aborted("Boot folgt nicht"), r.tick(40.0, 100.0))
    }

    @Test fun cancelStops() {
        val r = ShortcutRunner(s)
        r.start(40.0, 40.0, 110.0)
        r.cancel()
        assertEquals(Tick.Idle, r.tick(40.0, 40.0))
    }

    @Test fun backRestoresOldAngleAndSurvivesChainedShortcuts() {
        val r = ShortcutRunner(s)
        r.start(45.0, 45.0, 110.0); run(r, 45.0)
        r.start(110.0, 110.0, 38.0)              // zweiter Shortcut: gespeichert bleibt 45
        assertEquals(45.0, r.saved)
        r.cancel()
        assertIs<Result.Ok>(r.back(38.0, 38.0))
        assertEquals(45.0, run(r, 38.0).last())
        assertNull(r.saved)
    }

    @Test fun backUsesCurrentWindSide() {
        val r = ShortcutRunner(s)
        r.start(45.0, 45.0, 110.0); run(r, 45.0)
        r.back(-110.0, -110.0)                    // Wind ist inzwischen von Backbord
        assertEquals(-45.0, run(r, -110.0).last())
    }

    @Test fun backWithoutSavedIsRejected() = assertIs<Result.Rejected>(ShortcutRunner(s).back(40.0, 40.0))

    @Test fun respectsConfiguredMax() {
        s.maxAngle = 100.0
        assertIs<Result.Rejected>(ShortcutRunner(s).start(40.0, 40.0, 110.0))
    }
}
