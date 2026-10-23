package de.eneriq.pypilot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import de.eneriq.pilot.Version
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Selbst-Update ueber GitHub-Releases: das neueste Release des eigenen Repos ([REPO]) nennt im tag_name die
 * Version (z. B. "v1.2") und haengt die APK als Asset an. Die APK wird geladen und ueber den PackageInstaller
 * installiert; Android fragt dabei nach (beim ersten Mal auch, ob die App Apps installieren darf).
 */
object AppUpdater {
    /** GitHub-Repo mit den Releases (Besitzer/Name). */
    const val REPO = "maik8407/PyPilot-App"
    const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    data class Info(val versionName: String, val url: String, val notes: String)

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
        val c = open(LATEST)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return@withContext null   // noch kein Release veroeffentlicht
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            if (!Version.isNewer(tag, installedName(ctx))) return@withContext null
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: throw IOException("Release $tag enthaelt keine APK")
            Info(tag.removePrefix("v"), apk.getString("browser_download_url"), o.optString("body"))
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
