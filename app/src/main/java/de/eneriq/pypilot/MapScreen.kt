package de.eneriq.pypilot

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.eneriq.pilot.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor
import kotlin.math.pow

private const val TILE = 256

/** Kachel aus dem Cache oder von tile.openstreetmap.org (normales Netz, wie in der EnerIQ-Handy-Karte). */
private fun loadTile(cache: File, z: Int, x: Int, y: Int): Bitmap? {
    val f = File(cache, "tiles/$z/$x/$y.png")
    if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { return it }
    return try {
        val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "PyPilot-App")
        try {
            if (c.responseCode != 200) return null
            f.parentFile?.mkdirs()
            c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
        } finally { c.disconnect() }
        BitmapFactory.decodeFile(f.path)
    } catch (_: Exception) { null }
}

/** Karte zur Zielwahl: Tippen setzt das Ziel, "Anfahren" fuehrt den Kurs im GPS-Modus nach. */
@Composable
fun MapScreen(s: PilotState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val loc = remember { NavLocation.get(ctx) }
    var hasPerm by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { hasPerm = it }
    LaunchedEffect(hasPerm) { if (hasPerm) loc.start() else launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    var zoom by remember { mutableIntStateOf(12) }
    var clat by remember { mutableDoubleStateOf(54.3) }
    var clon by remember { mutableDoubleStateOf(10.15) }
    var centered by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    val tiles = remember { mutableStateMapOf<String, Bitmap>() }
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var pos by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    LaunchedEffect(Unit) { while (true) { pos = loc.last; if (!centered) pos?.let { clat = it.first; clon = it.second; centered = true }; delay(1000) } }

    // sichtbare Kacheln laden
    LaunchedEffect(zoom, clat, clon, size) {
        val x0 = Geo.pixelX(clon, zoom) - size.width / 2; val y0 = Geo.pixelY(clat, zoom) - size.height / 2
        val n = 2.0.pow(zoom).toInt()
        for (ty in floor(y0 / TILE).toInt()..floor((y0 + size.height) / TILE).toInt()) {
            for (tx in floor(x0 / TILE).toInt()..floor((x0 + size.width) / TILE).toInt()) {
                val wx = ((tx % n) + n) % n
                val key = "$zoom/$wx/$ty"
                if (ty < 0 || ty >= n || tiles.containsKey(key)) continue
                withContext(Dispatchers.IO) { loadTile(ctx.cacheDir, zoom, wx, ty) }?.let { tiles[key] = it }
            }
        }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(zoom) {
                    detectDragGestures { _, d ->
                        val px = Geo.pixelX(clon, zoom) - d.x; val py = Geo.pixelY(clat, zoom) - d.y
                        clon = Geo.lonOfPixel(px, zoom); clat = Geo.latOfPixel(py, zoom)
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
                size = IntSize(this.size.width.toInt(), this.size.height.toInt())
                val x0 = Geo.pixelX(clon, zoom) - this.size.width / 2; val y0 = Geo.pixelY(clat, zoom) - this.size.height / 2
                val n = 2.0.pow(zoom).toInt()
                for (ty in floor(y0 / TILE).toInt()..floor((y0 + this.size.height) / TILE).toInt()) {
                    for (tx in floor(x0 / TILE).toInt()..floor((x0 + this.size.width) / TILE).toInt()) {
                        val b = tiles["$zoom/${((tx % n) + n) % n}/$ty"] ?: continue
                        drawImage(b.asImageBitmap(), dstOffset = IntOffset((tx * TILE - x0).toInt(), (ty * TILE - y0).toInt()), dstSize = IntSize(TILE, TILE))
                    }
                }
                fun at(lat: Double, lon: Double) = Offset((Geo.pixelX(lon, zoom) - x0).toFloat(), (Geo.pixelY(lat, zoom) - y0).toFloat())
                pos?.let { drawCircle(Color(0xFF1E88E5), 10f, at(it.first, it.second)); drawCircle(Color.White, 10f, at(it.first, it.second), style = androidx.compose.ui.graphics.drawscope.Stroke(3f)) }
                s.target?.let { t -> val p = at(t.first, t.second); drawCircle(Red, 12f, p); drawCircle(Color.White, 12f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    pos?.let { drawLine(Red, at(it.first, it.second), p, 3f) } }
                pending?.let { t -> val p = at(t.first, t.second); drawCircle(Amber, 12f, p, style = androidx.compose.ui.graphics.drawscope.Stroke(4f)) }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                SmallFab("+") { if (zoom < 17) zoom++ }
                Spacer(Modifier.height(4.dp))
                SmallFab("−") { if (zoom > 3) zoom-- }
            }
            Text("© OpenStreetMap", Modifier.align(Alignment.BottomStart).padding(4.dp), fontSize = 10.sp, color = Color.DarkGray)
        }
        Text(
            when {
                s.navInfo.isNotEmpty() -> s.navInfo
                pending != null -> String.format(java.util.Locale.GERMANY, "Ziel: %.4f, %.4f", pending!!.first, pending!!.second)
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
