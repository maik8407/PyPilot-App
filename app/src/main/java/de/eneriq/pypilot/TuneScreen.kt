package de.eneriq.pypilot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
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
import kotlin.math.abs
import kotlin.math.max

private val PartColors = listOf(Teal, Amber, Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFF81C784), Color(0xFFE57373), Color(0xFFA1887F))
private fun colorOf(i: Int) = PartColors[i % PartColors.size]

/** Reglerseite: Profil, Gains, Live-Beitraege, Mitschnitt mit Vorschlaegen. Fehlende pypilot-Werte zeigen nur "--". */
@Composable
fun TuneScreen(s: PilotState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var suggestions by remember { mutableStateOf<List<de.eneriq.pilot.Suggestion>?>(null) }
    var saved by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Profil
        Text("Profil: ${s.profile ?: "--"}", fontSize = 16.sp)
        if (s.profiles.isEmpty()) Text("(keine Profilliste von pypilot erhalten)", color = Color.Gray, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.profiles.forEach { p ->
                FilterChip(s.profile == p, { PilotHub.setProfile(p) }, { Text(p, maxLines = 1) })
            }
        }

        // Gains: alle Regelparameter des aktiven Piloten, wie pypilot sie meldet
        Text("Regelparameter (${s.pilot ?: "--"})", fontSize = 16.sp)
        if (s.gainInfo.isEmpty()) Text("(noch keine Parameter von pypilot erhalten)", color = Color.Gray, fontSize = 12.sp)
        s.gainInfo.forEachIndexed { i, g ->
            val v = s.gains[g.name]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(g.name, Modifier.width(40.dp), color = colorOf(i))
                Text(v?.let { String.format("%.4f", it) } ?: "--", Modifier.weight(1f))
                OutlinedButton({ v?.let { PilotHub.setGain(g, it * 0.9) } }, enabled = v != null, contentPadding = PaddingValues(8.dp)) { Text("−10 %") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton({ v?.let { PilotHub.setGain(g, it * 1.1) } }, enabled = v != null, contentPadding = PaddingValues(8.dp)) { Text("+10 %") }
            }
        }

        // Live-Beitraege (von pypilot berechnet: ap.pilot.<pilot>.<Name>gain)
        Text("Wirkung der Regelanteile auf das Ruder", fontSize = 16.sp)
        val names = s.gainInfo.map { it.name }
        Contributions(names, s.terms)
        History(names, s.history)

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
private fun Contributions(names: List<String>, terms: Map<String, Double>) {
    if (names.isEmpty()) return
    val vals = names.map { terms[it] ?: 0.0 }
    val scale = max(0.01, vals.maxOf { abs(it) })
    Canvas(Modifier.fillMaxWidth().height((names.size * 22).dp)) {
        val h = size.height / names.size
        val mid = size.width / 2
        vals.forEachIndexed { i, v ->
            val w = (v / scale * mid).toFloat()
            drawRect(colorOf(i), Offset(if (w >= 0) mid else mid + w, i * h + 3f), Size(abs(w), h - 6f))
        }
        drawLine(Color.Gray, Offset(mid, 0f), Offset(mid, size.height))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        names.forEachIndexed { i, n -> Text(n + " " + String.format("%+.3f", vals[i]), color = colorOf(i), fontSize = 12.sp) }
    }
}

@Composable
private fun History(names: List<String>, h: List<Map<String, Double>>) {
    if (h.size < 2 || names.isEmpty()) return
    val scale = max(0.01, h.maxOf { m -> names.maxOf { abs(m[it] ?: 0.0) } })
    Canvas(Modifier.fillMaxWidth().height(100.dp)) {
        drawLine(Color.DarkGray, Offset(0f, size.height / 2), Offset(size.width, size.height / 2))
        names.forEachIndexed { k, n ->
            val p = Path()
            h.forEachIndexed { i, m ->
                val x = i * size.width / (h.size - 1)
                val y = size.height / 2 - ((m[n] ?: 0.0) / scale * size.height / 2).toFloat()
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, colorOf(k), style = Stroke(2f))
        }
    }
}
