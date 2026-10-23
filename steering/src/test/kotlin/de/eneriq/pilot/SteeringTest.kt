package de.eneriq.pilot

import kotlin.test.*

class SteeringTest {
    private val s = Steering()
    private fun ok(r: Result) = (r as Result.Ok).target

    @Test fun starboardArrowWindFromStarboardReducesAngle() = assertEquals(35.0, ok(s.nudge(45.0, 10.0, true)))
    @Test fun portArrowWindFromStarboardIncreasesAngle() = assertEquals(55.0, ok(s.nudge(45.0, 10.0, false)))
    @Test fun starboardArrowWindFromPortIncreasesMagnitude() = assertEquals(-55.0, ok(s.nudge(-45.0, 10.0, true)))
    @Test fun portArrowWindFromPortReducesMagnitude() = assertEquals(-35.0, ok(s.nudge(-45.0, 10.0, false)))
    @Test fun neverCrossesToOtherSide() {
        assertIs<Result.Rejected>(s.nudge(25.0, 10.0, true))
        assertIs<Result.Rejected>(s.nudge(-25.0, 10.0, false))
    }
    @Test fun neverBeyondMax() {
        assertIs<Result.Rejected>(s.nudge(165.0, 10.0, false))
        assertIs<Result.Rejected>(s.nudge(-165.0, 10.0, true))
        assertEquals(170.0, ok(s.nudge(169.0, 1.0, false)))
    }
    @Test fun shortcutKeepsSide() {
        assertEquals(110.0, ok(s.goTo(40.0, 110.0)))
        assertEquals(-110.0, ok(s.goTo(-40.0, 110.0)))
        assertEquals(-38.0, ok(s.goTo(-120.0, 38.0)))
    }
    @Test fun shortcutRejectsTooLarge() = assertIs<Result.Rejected>(s.goTo(40.0, 175.0))
    @Test fun shortcutRespectsConfiguredMax() {
        s.maxAngle = 150.0
        assertIs<Result.Rejected>(s.goTo(40.0, 160.0))
    }
    @Test fun stepsStayOnSideAndReachTarget() {
        var c = 40.0
        repeat(20) { c = s.nextStep(c, 110.0, 10.0); assertTrue(c in 40.0..110.0) }
        assertEquals(110.0, c)
        var p = -40.0
        repeat(20) { p = s.nextStep(p, -110.0, 10.0); assertTrue(p in -110.0..-40.0) }
        assertEquals(-110.0, p)
    }
    @Test fun sideChangeDuringShortcutRejected() {
        assertFailsWith<IllegalArgumentException> { s.nextStep(-10.0, 110.0, 10.0) }
    }
    @Test fun normalizeWraps() {
        assertEquals(-179.0, normalize180(181.0)); assertEquals(1.0, normalize180(361.0))
    }
}
