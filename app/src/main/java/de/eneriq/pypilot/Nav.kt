package de.eneriq.pypilot

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/** Position vom Handy-GPS (nur waehrend die Karte/Zielfahrt sie braucht). */
class NavLocation(private val ctx: Context) : LocationListener {
    @Volatile var last: Pair<Double, Double>? = null
    private val lm = ctx.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    fun start() {
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { last = it.latitude to it.longitude }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
    }

    fun stop() { runCatching { lm.removeUpdates(this) } }
    override fun onLocationChanged(l: Location) { last = l.latitude to l.longitude }

    companion object {
        /** Eine gemeinsame Instanz, damit die Zielfahrt auch ohne offene Karte weiterlaeuft. */
        @Volatile var instance: NavLocation? = null
        fun get(ctx: Context) = instance ?: NavLocation(ctx.applicationContext).also { instance = it }
    }
}
