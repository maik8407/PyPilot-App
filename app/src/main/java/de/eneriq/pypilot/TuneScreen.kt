package de.eneriq.pypilot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.eneriq.pilot.Contribution
import de.eneriq.pilot.Gains
import kotlin.math.abs
import kotlin.math.max

private val PartColors = listOf(Teal, Color(0xFFFFB300), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFF81C784))

/** Reglerseite: Profil, Gains, Live-Beitraege, Mitschnitt mit Vorschlaegen. */
@Composable
fun TuneScreen(s: PilotState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var suggestions by remember { mutableStateOf<List<de.eneriq.pilot.Suggestion>?>(null) }
    var saved by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Profil
        Text("Profil: ${s.profile ?: "--"}", fontSize = 16.sp)
        if (s.profiles.isEmpty()) Text("(keine Profilliste von pypilot erhalten)", color = Color.Gray, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState(), enabled = false), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.profiles.take(4).forEach { p ->
                FilterChip(s.profile == p, { PilotHub.setProfile(p) }, { Text(p, maxLines = 1) })
            }
        }

        // Gains
        Text("Regelparameter (${s.pilot})", fontSize = 16.sp)
        Gains.NAMES.forEachIndexed { i, n ->
            val v = s.gains[n]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(n, Modifier.width(36.dp), color = PartColors[i])
                Text(v?.let { String.format("%.4g", it) } ?: "--", Modifier.weight(1f))
                OutlinedButton({ v?.let { PilotHub.setGain(n, it * 0.9) } }, enabled = v != null, contentPadding = PaddingValues(8.dp)) { Text("−10 %") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ v?.let { PilotHub.setGain(n, it * 1.1) } }, enabled = v != null, contentPadding = PaddingValues(8.dp)) { Text("+10 %") }
            }
        }

        // Live-Beitraege
        Text("Wirkung der Regelanteile (Näherung aus den Gains)", fontSize = 16.sp)
        Contributions(s.contribution)
        History(s.history)

        // Mitschnitt
        Text("Mitschnitt: ${s.samples} Messpunkte", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ PilotHub.setRecording(!s.recording) }) { Text(if (s.recording) "Stopp" else "Aufnehmen") }
            OutlinedButton({ suggestions = PilotHub.suggestions() }, enabled = s.samples > 1) { Text("Auswerten") }
            OutlinedButton({ saved = "Gespeichert: " + PilotHub.saveRecording(ctx).name }, enabled = s.samples > 1) { Text("CSV") }
        }
        if (saved.isNotEmpty()) Text(saved, color = Color.Gray, fontSize = 12.sp)
        suggestions?.let { list ->
            Text("Vorschläge (werden nie automatisch angewendet):", color = Teal, fontSize = 14.sp)
            list.forEach { Text("• $it", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun Contributions(c: Contribution?) {
    val vals = listOf(c?.p, c?.i, c?.d, c?.dd, c?.ff).map { it ?: 0.0 }
    val scale = max(1.0, vals.maxOf { abs(it) })
    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
        val h = size.height / 5
        val mid = size.width / 2
        vals.forEachIndexed { i, v ->
            val w = (v / scale * mid).toFloat()
            drawRect(PartColors[i], Offset(if (w >= 0) mid else mid + w, i * h + 4f), Size(abs(w), h - 8f))
        }
        drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, size.height))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Gains.NAMES.forEachIndexed { i, n -> Text(n + " " + String.format("%+.1f", vals[i]), color = PartColors[i], fontSize = 12.sp) }
    }
}

@Composable
private fun History(h: List<Contribution>) {
    if (h.size < 2) return
    val parts: List<(Contribution) -> Double> = listOf({ it.p }, { it.i }, { it.d }, { it.dd }, { it.ff })
    val scale = max(1.0, h.maxOf { c -> parts.maxOf { abs(it(c)) } })
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        drawLine(Color.DarkGray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        parts.forEachIndexed { k, f ->
            val p = Path()
            h.forEachIndexed { i, c ->
                val x = i * size.width / (h.size - 1)
                val y = size.height / 2 - (f(c) / scale * size.height / 2).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, PartColors[k], style = Stroke(2f))
        }
    }
}
