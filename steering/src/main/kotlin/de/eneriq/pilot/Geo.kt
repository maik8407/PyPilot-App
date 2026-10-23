package de.eneriq.pilot

import kotlin.math.*

object Geo {
    private const val R_NM = 3440.065   // Erdradius in Seemeilen

    /** Anfangskurs von (lat1,lon1) nach (lat2,lon2), 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Entfernung in Seemeilen (Haversine). */
    fun distanceNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R_NM * asin(min(1.0, sqrt(a)))
    }

    // Web-Mercator, Pixel auf Zoomstufe z (Kachelgroesse 256) -- wie in der EnerIQ-Handy-Karte
    fun pixelX(lon: Double, z: Int) = (lon + 180) / 360 * 256 * 2.0.pow(z)
    fun pixelY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat)
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * 256 * 2.0.pow(z)
    }
    fun lonOfPixel(x: Double, z: Int) = x / (256 * 2.0.pow(z)) * 360 - 180
    fun latOfPixel(y: Double, z: Int): Double {
        val n = PI - 2 * PI * y / (256 * 2.0.pow(z))
        return Math.toDegrees(atan(sinh(n)))
    }
}

/**
 * Zielanfahrt: aus Position und Ziel den Kurs ableiten. Der Sollkurs wird nur nachgefuehrt,
 * wenn er sich um mehr als [hysteresis] Grad geaendert hat (kein staendiges Nachstellen).
 */
class GoTo(val lat: Double, val lon: Double, private val arriveNm: Double = 0.05, private val hysteresis: Double = 2.0) {
    sealed class Out {
        object Arrived : Out()
        data class Course(val deg: Double, val distNm: Double, val changed: Boolean) : Out()
    }

    private var last: Double? = null

    fun update(posLat: Double, posLon: Double): Out {
        val d = Geo.distanceNm(posLat, posLon, lat, lon)
        if (d <= arriveNm) return Out.Arrived
        val b = Geo.bearing(posLat, posLon, lat, lon)
        val l = last
        val changed = l == null || abs(normalize180(b - l)) > hysteresis
        if (changed) last = b
        return Out.Course(b, d, changed)
    }
}
