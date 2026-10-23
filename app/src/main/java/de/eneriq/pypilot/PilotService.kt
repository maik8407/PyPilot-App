package de.eneriq.pypilot

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.os.PowerManager
import de.eneriq.pilot.Link
import de.eneriq.pilot.PypilotClient
import kotlinx.coroutines.*
import java.net.Socket

/** Haelt die pypilot-Verbindung im Vordergrund (WLAN-gebunden) und spiegelt den Verbindungsstatus. */
class PilotService : Service() {
    private var scope: CoroutineScope? = null
    private var wifi: Network? = null
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_QUIT) {
            shutdown()
            return START_NOT_STICKY
        }
        if (scope != null) return START_NOT_STICKY   // laeuft schon: nie eine zweite Verbindung aufbauen
        createChannels()
        startForeground(1, notification("Verbinde ..."))
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pypilot:service").apply { acquire() }
        PilotHub.steering.maxAngle = PilotHub.maxAngle(this)

        // WLAN-Bindung: Android wuerde ein WLAN ohne Internet sonst verlassen.
        val cm = getSystemService(ConnectivityManager::class.java)
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { wifi = n; PilotHub.client?.reconnect() }
            override fun onLost(n: Network) { if (wifi == n) wifi = null }
        }
        cb = c
        cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), c)

        val client = PypilotClient(
            sc, PilotHub.host(this), watchNames = PilotHub.WATCH,
            socketFactory = { wifi?.socketFactory?.createSocket() ?: Socket() },
            onValue = PilotHub::onValue,
        )
        PilotHub.client = client
        client.start()
        PilotHub.startSampler()
        sc.launch { monitor(client) }
        return START_NOT_STICKY
    }

    /** Verbindungsstatus in Oberflaeche und Benachrichtigung spiegeln (nur Anzeige, kein Ton). */
    private suspend fun monitor(client: PypilotClient) {
        client.link.collect { link ->
            PilotHub.update { it.copy(link = link) }
            getSystemService(NotificationManager::class.java).notify(
                1, notification(if (link == Link.CONNECTED) "Verbunden" else "Verbinde ..."))
        }
    }

    /** Wischen aus der Uebersicht: alles beenden. */
    override fun onTaskRemoved(rootIntent: Intent?) = shutdown()

    private fun shutdown() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pilot", "PyPilot", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification {
        val quit = PendingIntent.getService(
            this, 1, Intent(this, PilotService::class.java).setAction(ACTION_QUIT), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "pilot")
            .setSmallIcon(R.drawable.ic_pypilot)
            .setContentTitle("PyPilot")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Beenden", quit).build())
            .build()
    }

    override fun onDestroy() {
        PilotHub.shutdown(); NavLocation.instance?.stop()
        PilotHub.client?.stop(); PilotHub.client = null
        cb?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        cb = null
        wake?.let { if (it.isHeld) it.release() }
        wake = null
        scope?.cancel(); scope = null
        getSystemService(NotificationManager::class.java).cancel(1)
        PilotHub.update { it.copy(link = Link.DISCONNECTED) }
    }

    companion object {
        const val ACTION_QUIT = "de.eneriq.pypilot.QUIT"
        fun start(c: Context) = c.startForegroundService(Intent(c, PilotService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, PilotService::class.java)) }
    }
}
