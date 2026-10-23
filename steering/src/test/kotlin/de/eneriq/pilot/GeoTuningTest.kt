package de.eneriq.pilot

import kotlin.math.sin
import kotlin.test.*

class GeoTuningTest {
    @Test fun bearingAndDistance() {
        assertEquals(0.0, Geo.bearing(54.0, 10.0, 55.0, 10.0), 0.01)
        assertEquals(90.0, Geo.bearing(0.0, 10.0, 0.0, 11.0), 0.01)
        assertEquals(180.0, Geo.bearing(55.0, 10.0, 54.0, 10.0), 0.01)
        assertEquals(60.0, Geo.distanceNm(54.0, 10.0, 55.0, 10.0), 0.2)
    }

    @Test fun mercatorRoundTrip() {
        val z = 12
        val lat = Geo.latOfPixel(Geo.pixelY(54.3, z), z)
        val lon = Geo.lonOfPixel(Geo.pixelX(10.1, z), z)
        assertEquals(54.3, lat, 1e-6); assertEquals(10.1, lon, 1e-6)
    }

    @Test fun gotoFollowsCourseWithHysteresisAndArrives() {
        val g = GoTo(55.0, 10.0)
        val a = g.update(54.0, 10.0) as GoTo.Out.Course
        assertTrue(a.changed); assertEquals(0.0, a.deg, 0.1)
        val b = g.update(54.0001, 10.0) as GoTo.Out.Course
        assertFalse(b.changed)
        val c = g.update(54.5, 10.5) as GoTo.Out.Course
        assertTrue(c.changed)
        assertEquals(GoTo.Out.Arrived, g.update(55.0, 10.0001))
    }

    private fun gen(n: Int, f: (Int) -> Pair<Double, Double>) =
        List(n) { k -> val (h, r) = f(k); Sample(k * 1000L, h, 100.0, r) }

    @Test fun oscillationSuggestsLowerP() {
        val s = gen(120) { k -> 100 + 10 * sin(k * 1.0) to 0.0 }
        val sug = Tuning.suggest(s)
        assertTrue(sug.any { it.gain == "P" && it.factor < 1 })
        assertTrue(sug.any { it.gain == "D" && it.factor > 1 })
    }

    @Test fun biasSuggestsHigherI() {
        val s = gen(120) { _ -> 106.0 to 0.0 }
        assertTrue(Tuning.suggest(s).any { it.gain == "I" && it.factor > 1 })
    }

    @Test fun sluggishSuggestsHigherP() {
        val s = gen(120) { k -> 100 + 12 * sin(k * 0.05) to 0.0 }
        assertTrue(Tuning.suggest(s).any { it.gain == "P" && it.factor > 1 })
    }

    @Test fun nervousRudderSuggestsLowerDD() {
        val s = gen(120) { k -> 100.0 + 0.5 * sin(k * 0.01) to if (k % 2 == 0) 3.0 else -3.0 }
        assertTrue(Tuning.suggest(s).any { it.gain == "DD" && it.factor < 1 })
    }

    @Test fun goodCourseAndTooLittleData() {
        assertEquals("-", Tuning.suggest(gen(120) { _ -> 100.2 to 0.0 }).single().gain)
        assertTrue(Tuning.suggest(gen(10) { _ -> 100.0 to 0.0 }).single().reason.contains("zu wenig"))
    }

    @Test fun windModeUsesWindError() {
        val s = List(120) { k -> Sample(k * 1000L, 0.0, 45.0, 0.0, wind = 52.0) }
        assertEquals(7.0, Tuning.stats(s, windMode = true)!!.mean, 1e-9)
    }

    @Test fun contributionIsLinear() {
        val c = contribution(Gains(p = 2.0, i = 0.5, d = 1.0, dd = 0.1), error = 5.0, errorInt = 10.0, rate = 2.0, accel = 3.0)
        assertEquals(10.0, c.p, 1e-9); assertEquals(5.0, c.i, 1e-9)
        assertEquals(2.0, c.d, 1e-9); assertEquals(0.3, c.dd, 1e-9)
        assertEquals(17.3, c.total, 1e-9)
    }

    @Test fun recorderCapsAndExportsCsv() {
        val r = Recorder(max = 3)
        repeat(5) { r.add(Sample(it.toLong(), 1.0, 2.0, 3.0)) }
        assertEquals(3, r.samples().size)
        assertTrue(r.csv().lines().first().startsWith("t_ms;"))
    }
}
