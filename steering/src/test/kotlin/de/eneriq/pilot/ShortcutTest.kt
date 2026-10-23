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

    private fun abc(sign: Double) {
        val r = ShortcutRunner(s)
        val a = 52.0 * sign                       // Ausgangskurs A
        assertIs<Result.Ok>(r.start(a, a, 110.0))  // Vorsegel einholen -> B
        val b = run(r, a).last()
        assertEquals(110.0 * sign, b)
        assertIs<Result.Ok>(r.start(b, b, 38.0))   // Grosssegel einholen -> C (A darf nicht ueberschrieben werden)
        val c = run(r, b).last()
        assertEquals(38.0 * sign, c)
        assertEquals(a, r.saved)
        assertIs<Result.Ok>(r.back(c, c))          // zurueck muss A anfahren
        assertEquals(a, a.let { run(r, c).last() })
        assertNull(r.saved)                        // erst nach dem Erreichen geloescht
    }

    @Test fun foresailThenMainsailThenBackReturnsToOriginalStarboard() = abc(1.0)
    @Test fun foresailThenMainsailThenBackReturnsToOriginalPort() = abc(-1.0)

    @Test fun savedCourseSurvivesAbortedReturnAndIsClearedOnDisengage() {
        val r = ShortcutRunner(s)
        r.start(52.0, 52.0, 110.0); run(r, 52.0)
        r.back(110.0, 110.0)
        assertIs<Tick.Step>(r.tick(110.0, 110.0))
        assertIs<Tick.Aborted>(r.tick(-90.0, 100.0))    // Wind dreht auf andere Seite -> Abbruch
        assertEquals(52.0, r.saved)                     // bleibt erhalten
        r.forgetSaved()                                 // Automatik aus
        assertNull(r.saved)
    }

    @Test fun returnNeverCrossesToOtherSideOrThroughOneEighty() {
        val r = ShortcutRunner(s)
        r.start(45.0, 45.0, 170.0); val b = run(r, 45.0)
        assertTrue(b.all { it in 45.0..170.0 })
        r.back(170.0, 170.0)
        assertTrue(run(r, 170.0).all { it in 45.0..170.0 })
    }

    @Test fun backWithoutSavedIsRejected() = assertIs<Result.Rejected>(ShortcutRunner(s).back(40.0, 40.0))

    @Test fun respectsConfiguredMax() {
        s.maxAngle = 100.0
        assertIs<Result.Rejected>(ShortcutRunner(s).start(40.0, 40.0, 110.0))
    }
}
