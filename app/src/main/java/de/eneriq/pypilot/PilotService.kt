package de.eneriq.pypilot

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
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

/** Haelt die pypilot-Verbindung im Vordergrund (WLAN-gebunden) und loest den Verbindungsalarm aus. */
class PilotService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wifi: Network? = null
    private var cb: ConnectivityManager.NetworkCallback? = null
    private var wake: PowerManager.WakeLock? = null
    private var ringtone: android.media.Ringtone? = null
    private var downSince = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (PilotHub.client != null) return START_STICKY
        createChannels()
        startForeground(1, notification("Verbinde ..."))
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
            scope, PilotHub.host(this), watchNames = PilotHub.WATCH,
            socketFactory = { wifi?.socketFactory?.createSocket() ?: Socket() },
            onValue = PilotHub::onValue,
        )
        PilotHub.client = client
        client.start()
        scope.launch { monitor(client) }
        return START_STICKY
    }

    /** Alle 500 ms Zustand pruefen: Alarm, wenn die Verbindung >4 s weg ist und der Pilot lief oder laeuft. */
    private suspend fun monitor(client: PypilotClient) {
        while (true) {
            val link = client.link.value
            val now = System.currentTimeMillis()
            if (link == Link.CONNECTED) downSince = 0 else if (downSince == 0L) downSince = now
            val alarm = downSince != 0L && now - downSince > 4000
            val s = PilotHub.state.value
            if (s.link != link || s.alarm != alarm) {
                PilotHub.update { it.copy(link = link, alarm = alarm) }
                getSystemService(NotificationManager::class.java).notify(
                    1, notification(if (alarm) "VERBINDUNG VERLOREN" else if (link == Link.CONNECTED) "Verbunden" else "Verbinde ..."))
                if (alarm) startAlarm() else stopAlarm()
            }
            delay(500)
        }
    }

    private fun startAlarm() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            isLooping = true
            play()
        }
    }

    private fun stopAlarm() { ringtone?.stop(); ringtone = null }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pilot", "PyPilot", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, "pilot")
            .setSmallIcon(R.drawable.ic_pypilot)
            .setContentTitle("PyPilot")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    override fun onDestroy() {
        stopAlarm()
        PilotHub.client?.stop(); PilotHub.client = null
        cb?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wake?.release()
        scope.cancel()
        PilotHub.update { it.copy(link = Link.DISCONNECTED, alarm = false) }
    }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, PilotService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, PilotService::class.java)) }
    }
}
