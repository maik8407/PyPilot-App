package de.eneriq.pypilot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selbst-Update wie in der EnerIQ-Handy-App: apps.json im oeffentlichen Repo maik8407/EnerIQ-firmware
 * nennt je App die neueste Version und die APK (Schluessel "pypilot"). Die APK wird geladen und ueber den
 * PackageInstaller installiert; Android fragt dabei nach (beim ersten Mal auch, ob die App Apps installieren darf).
 */
object AppUpdater {
    const val INDEX = "https://raw.githubusercontent.com/maik8407/EnerIQ-firmware/main/apps/apps.json"
    const val KEY = "pypilot"

    data class Info(val versionCode: Long, val versionName: String, val url: String, val notes: String)

    fun installedCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }

    fun installedName(ctx: Context): String = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.useCaches = false
        c.setRequestProperty("User-Agent", "PyPilot-App")
        return c
    }

    /** Neuere Version als die installierte, sonst null. */
    suspend fun check(ctx: Context): Info? = withContext(Dispatchers.IO) {
        val c = open(INDEX)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject(KEY)
            val info = Info(o.getLong("versionCode"), o.getString("versionName"), o.getString("apk"), o.optString("notes"))
            if (info.versionCode > installedCode(ctx)) info else null
        } finally { c.disconnect() }
    }

    /** APK laden und die Installation starten (Android zeigt danach "Aktualisieren?"). */
    suspend fun install(ctx: Context, info: Info, progress: (Int) -> Unit = {}) = withContext(Dispatchers.IO) {
        val f = File(ctx.cacheDir, "update.apk")
        val c = open(info.url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally { c.disconnect() }

        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, f.length()).use { out -> f.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        f.delete()
    }
}

/** Ergebnis der Installation: Rueckfrage an den Nutzer weiterreichen, Fehler anzeigen. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit   // App wird neu gestartet
            else -> Toast.makeText(ctx, "Update fehlgeschlagen: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "?"), Toast.LENGTH_LONG).show()
        }
    }
}
