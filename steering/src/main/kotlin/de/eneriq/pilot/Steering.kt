package de.eneriq.pilot

import kotlin.math.abs

enum class WindSide { STARBOARD, PORT }

/** Windwinkel mit Vorzeichen: + = Wind von Steuerbord, - = von Backbord. Bereich -180..180. */
fun normalize180(a: Double): Double {
    var x = (a + 180.0) % 360.0
    if (x < 0) x += 360.0
    return x - 180.0
}

fun sideOf(signedWind: Double) = if (signedWind >= 0) WindSide.STARBOARD else WindSide.PORT

sealed class Result {
    data class Ok(val target: Double) : Result()
    data class Rejected(val reason: String) : Result()
}

/** Lenklogik im Wind-Modus. Ziel nie ueber maxAngle (Vorgabe 170) und nie auf die andere Windseite (keine Wende/Halse). */
class Steering(var maxAngle: Double = 170.0, var minAngle: Double = 20.0) {

    /** Steuerbord-Drehung: bei Wind von Stb wird der Winkel kleiner, bei Wind von Bb groesser. */
    fun nudge(currentSigned: Double, steps: Double, toStarboard: Boolean): Result {
        val side = sideOf(currentSigned)
        val mag = abs(currentSigned)
        val sign = if (toStarboard) -1.0 else 1.0
        val delta = sign * abs(steps) * (if (side == WindSide.STARBOARD) 1.0 else -1.0)
        return clamp(side, mag + delta)
    }

    fun goTo(currentSigned: Double, targetMag: Double): Result {
        if (targetMag > maxAngle) return Result.Rejected("Ziel ${targetMag.toInt()}° ueber Maximum ${maxAngle.toInt()}°")
        if (targetMag < minAngle) return Result.Rejected("Ziel ${targetMag.toInt()}° unter Minimum ${minAngle.toInt()}°")
        val side = sideOf(currentSigned)
        return Result.Ok(if (side == WindSide.STARBOARD) targetMag else -targetMag)
    }

    private fun clamp(side: WindSide, mag: Double): Result {
        if (mag > maxAngle) return Result.Rejected("Maximum ${maxAngle.toInt()}° erreicht")
        if (mag < minAngle) return Result.Rejected("Minimum ${minAngle.toInt()}° erreicht")
        return Result.Ok(if (side == WindSide.STARBOARD) mag else -mag)
    }

    fun nextStep(currentSigned: Double, targetSigned: Double, stepDeg: Double): Double {
        require(sideOf(currentSigned) == sideOf(targetSigned) || abs(currentSigned) < 1e-9) { "Seitenwechsel" }
        val d = targetSigned - currentSigned
        return if (abs(d) <= stepDeg) targetSigned else currentSigned + stepDeg * (if (d > 0) 1 else -1)
    }
}
