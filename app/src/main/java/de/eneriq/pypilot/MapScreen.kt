package de.eneriq.pypilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.eneriq.pilot.Geo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.pow

private const val TILE = 256

/**
 * Kacheln: Arbeitsspeicher-Cache, darunter dauerhafter Datei-Cache (filesDir/tiles, wird nie von selbst geleert),
 * erst dann tile.openstreetmap.org (normales Netz, wie in der EnerIQ-Handy-Karte). Einmal geladene Kacheln
 * werden nie erneut geholt.
 */
private object TileStore {
    private val mem = LruCache<String, Bitmap>(200)
    private val gate = Semaphore(6)

    fun cached(z: Int, x: Int, y: Int): Bitmap? = mem.get("$z/$x/$y")

    suspend fun load(ctx: Context, z: Int, x: Int, y: Int): Bitmap? = gate.withPermit {
        cached(z, x, y)?.let { return it }
        withContext(Dispatchers.IO) {
            val f = File(ctx.filesDir, "tiles/$z/$x/$y.png")
            var b = if (f.exists()) BitmapFactory.decodeFile(f.path) else null
            if (b == null) {
                try {
                    val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                    c.connectTimeout = 5000; c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "PyPilot-App")
                    try {
                        if (c.responseCode == 200) {
                            f.parentFile?.mkdirs()
                            val tmp = File(f.path + ".tmp")
                            c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                            tmp.renameTo(f)
                            b = BitmapFactory.decodeFile(f.path)
                        }
                    } finally { c.disconnect() }
                } catch (_: Exception) { }
            }
            b?.also { mem.put("$z/$x/$y", it) }
        }
    }
}

/** Karte zur Zielwahl (nur im GPS-Modus sichtbar): Tippen setzt das Ziel, "Anfahren" fuehrt den Kurs im GPS-Modus nach. */
@Composable
fun MapScreen(s: PilotState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loc = remember { NavLocation.get(ctx) }
    var hasPerm by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { hasPerm = it }
    LaunchedEffect(hasPerm) { if (hasPerm) loc.start() else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    // zuletzt gezeigter Ausschnitt wird gespeichert und beim naechsten Oeffnen wieder verwendet
    val prefs = remember { PilotHub.prefs(ctx) }
    val hasSaved = remember { prefs.contains("map_zoom") }
    var zoom by remember { mutableIntStateOf(prefs.getInt("map_zoom", 12)) }
    var clat by remember { mutableDoubleStateOf(prefs.getString("map_lat", null)?.toDoubleOrNull() ?: 54.3) }
    var clon by remember { mutableDoubleStateOf(prefs.getString("map_lon", null)?.toDoubleOrNull() ?: 10.15) }
    var centered by remember { mutableStateOf(hasSaved) }
    var pending by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var tileTick by remember { mutableIntStateOf(0) }   // zwingt Neuzeichnen, wenn Kacheln eintreffen

    LaunchedEffect(Unit) { while (true) { pos = loc.last; if (!centered) pos?.let { clat = it.first; clon = it.second; centered = true }; delay(1000) } }

    // Ausschnitt nach Zoom/Verschieben speichern (kurz entprellt)
    LaunchedEffect(zoom, clat, clon) {
        delay(600)
        prefs.edit().putInt("map_zoom", zoom).putString("map_lat", clat.toString()).putString("map_lon", clon.toString()).apply()
    }

    // sichtbare Kacheln parallel laden; neu gestartet wird nur, wenn sich der Kachelbereich aendert
    val x0 = Geo.pixelX(clon, zoom) - size.width / 2; val y0 = Geo.pixelY(clat, zoom) - size.height / 2
    val tx0 = floor(x0 / TILE).toInt(); val tx1 = floor((x0 + size.width) / TILE).toInt()
    val ty0 = floor(y0 / TILE).toInt(); val ty1 = floor((y0 + size.height) / TILE).toInt()
    LaunchedEffect(zoom, tx0, tx1, ty0, ty1) {
        val n = 2.0.pow(zoom).toInt()
        coroutineScope {
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val wx = ((tx % n) + n) % n
                if (ty < 0 || ty >= n || TileStore.cached(zoom, wx, ty) != null) continue
                launch { if (TileStore.load(ctx, zoom, wx, ty) != null) tileTick++ }
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = it }
                .pointerInput(Unit) {
                    // Verschieben und Zwei-Finger-Zoom; pro Faktor 1,6 eine Zoomstufe
                    var acc = 1f
                    detectTransformGestures { _, pan, gz, _ ->
                        val px = Geo.pixelX(clon, zoom) - pan.x; val py = Geo.pixelY(clat, zoom) - pan.y
                        clon = Geo.lonOfPixel(px, zoom); clat = Geo.latOfPixel(py, zoom)
                        acc *= gz
                        if (acc > 1.6f && zoom < 17) { zoom++; acc = 1f } else if (acc < 1f / 1.6f && zoom > 3) { zoom--; acc = 1f }
                    }
                }
                .pointerInput(zoom, clat, clon) {
                    detectTapGestures { o ->
                        val px = Geo.pixelX(clon, zoom) - size.width / 2 + o.x
                        val py = Geo.pixelY(clat, zoom) - size.height / 2 + o.y
                        pending = Geo.latOfPixel(py, zoom) to Geo.lonOfPixel(px, zoom)
                    }
                }
            ) {
                tileTick.let { }   // Lesezugriff: bei neuen Kacheln neu zeichnen
                val ox = Geo.pixelX(clon, zoom) - this.size.width / 2; val oy = Geo.pixelY(clat, zoom) - this.size.height / 2
                val n = 2.0.pow(zoom).toInt()
                for (ty in floor(oy / TILE).toInt()..floor((oy + this.size.height) / TILE).toInt()) {
                    for (tx in floor(ox / TILE).toInt()..floor((ox + this.size.width) / TILE).toInt()) {
                        val b = TileStore.cached(zoom, ((tx % n) + n) % n, ty) ?: continue
                        drawImage(b.asImageBitmap(), dstOffset = IntOffset((tx * TILE - ox).toInt(), (ty * TILE - oy).toInt()), dstSize = IntSize(TILE, TILE))
                    }
                }
                fun at(lat: Double, lon: Double) = Offset((Geo.pixelX(lon, zoom) - ox).toFloat(), (Geo.pixelY(lat, zoom) - oy).toFloat())
                pos?.let { drawCircle(Color(0xFF1E88E5), 10f, at(it.first, it.second)); drawCircle(Color.White, 10f, at(it.first, it.second), style = androidx.compose.ui.graphics.drawscope.Stroke(3f)) }
                s.target?.let { t -> val p = at(t.first, t.second); drawCircle(Red, 12f, p); drawCircle(Color.White, 12f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    pos?.let { drawLine(Red, at(it.first, it.second), p, 3f) } }
                pending?.let { t -> val p = at(t.first, t.second); drawCircle(Amber, 12f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                SmallFab("+") { if (zoom < 17) zoom++ }
                Spacer(Modifier.height(4.dp))
                SmallFab("−") { if (zoom > 3) zoom-- }
                Spacer(Modifier.height(4.dp))
                // Home: eigene Position mittig
                HomeFab { pos?.let { clat = it.first; clon = it.second } }
            }
            Text("© OpenStreetMap", Modifier.align(Alignment.BottomStart).padding(4.dp), fontSize = 10.sp, color = Color.DarkGray)
        }
        Text(
            when {
                s.navInfo.isNotEmpty() -> s.navInfo
                pending != null -> String.format(java.util.Locale.GERMANY, "Ziel: %.4f, %.4f", pending!!.first, pending!!.second)
                pos == null -> "Warte auf Handy-GPS ..."
                else -> "Auf die Karte tippen, um ein Ziel zu wählen"
            }, fontSize = 14.sp, color = if (s.navInfo.isNotEmpty()) Teal else Color.Gray)
        if (!hasPerm) Text("Für die Zielfahrt wird der Handy-GPS gebraucht (Standort erlauben).", color = Amber, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ pending?.let { PilotHub.startNav(it.first, it.second) { loc.last }; pending = null } }, enabled = pending != null && hasPerm) { Text("Anfahren") }
            OutlinedButton({ PilotHub.cancelNav() }, enabled = s.target != null) { Text("Ziel löschen") }
        }
    }
}

@Composable
private fun SmallFab(label: String, onClick: () -> Unit) =
    FilledTonalButton(onClick, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text(label, fontSize = 20.sp) }

/** Home-Taste: Fadenkreuz-Symbol, zentriert die Karte auf die eigene Position. */
@Composable
private fun HomeFab(onClick: () -> Unit) =
    FilledTonalButton(onClick, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
        Canvas(Modifier.size(22.dp)) {
            val c = Offset(size.width / 2, size.height / 2); val r = size.width / 3
            drawCircle(Color.White, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            drawLine(Color.White, Offset(c.x, 0f), Offset(c.x, size.height), 3f)
            drawLine(Color.White, Offset(0f, c.y), Offset(size.width, c.y), 3f)
        }
    }
