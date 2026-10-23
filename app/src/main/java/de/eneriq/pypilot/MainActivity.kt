package de.eneriq.pypilot

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.eneriq.pilot.Link
import de.eneriq.pilot.Pypilot
import de.eneriq.pilot.normalize180

private val Bg = Color(0xFF121212)
private val Teal = Color(0xFF76C7C0)
private val Red = Color(0xFFD32F2F)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        PilotService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Color(0xFF1E1E1E))) {
                Surface(Modifier.fillMaxSize(), color = Bg) { PilotScreen() }
            }
        }
    }
}

@Composable
private fun PilotScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s by PilotHub.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(16.dp).systemBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Verbindung
        val (linkText, linkColor) = when {
            s.alarm -> "VERBINDUNG VERLOREN" to Red
            s.link == Link.CONNECTED -> "Verbunden" to Teal
            else -> "Verbinde ..." to Color(0xFFFFB300)
        }
        Row(Modifier.fillMaxWidth().background(linkColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(linkText, color = linkColor, fontWeight = FontWeight.Bold)
            TextButton(onClick = { settings = true }) { Text("Einstellungen") }
        }

        // Werte
        val windMode = s.mode == Mode.WIND
        fun show(v: Double?): String = when {
            v == null -> "--"
            windMode -> String.format("%+.0f°", normalize180(v) * Pypilot.WIND_SIGN)
            else -> String.format("%.0f°", v)
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (s.enabled) "Pilot AN (${s.mode?.label ?: "?"})" else "Pilot AUS", fontSize = 18.sp,
                color = if (s.enabled) Teal else Color.Gray)
            Text(show(s.heading), fontSize = 72.sp, fontWeight = FontWeight.Bold)
            Text("Soll ${show(s.command)}", fontSize = 28.sp, color = Teal)
            if (s.message.isNotEmpty()) Text(s.message, color = Color(0xFFFFB300))
        }

        Spacer(Modifier.weight(1f))

        // Modi
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { m ->
                Button({ PilotHub.engage(m) }, Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (s.enabled && s.mode == m) Teal else Color(0xFF2A2A2A),
                        contentColor = if (s.enabled && s.mode == m) Color.Black else Color.White)) { Text(m.label) }
            }
        }

        // Winkel: links Backbord, rechts Steuerbord
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("◄ 10", Modifier.weight(1f)) { PilotHub.nudge(10, false) }
            StepButton("◄ 1", Modifier.weight(1f)) { PilotHub.nudge(1, false) }
            StepButton("1 ►", Modifier.weight(1f)) { PilotHub.nudge(1, true) }
            StepButton("10 ►", Modifier.weight(1f)) { PilotHub.nudge(10, true) }
        }
        Text("◄ Backbord        Steuerbord ►", Modifier.fillMaxWidth(), color = Color.Gray, fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Button({ PilotHub.stop() }, Modifier.fillMaxWidth().height(88.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) {
            Text("STOPP", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
    }

    if (settings) SettingsDialog(ctx) { settings = false }
}

@Composable
private fun StepButton(label: String, modifier: Modifier, onClick: () -> Unit) =
    Button(onClick, modifier.height(72.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Color.White)) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }

@Composable
private fun SettingsDialog(ctx: android.content.Context, onClose: () -> Unit) {
    var host by remember { mutableStateOf(PilotHub.host(ctx)) }
    var max by remember { mutableStateOf(PilotHub.maxAngle(ctx).toInt().toString()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Einstellungen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("pypilot-Adresse") }, singleLine = true)
                OutlinedTextField(max, { max = it.filter(Char::isDigit) }, label = { Text("Maximaler Windwinkel (°)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({
                PilotHub.saveSettings(ctx, host.trim(), (max.toIntOrNull() ?: 170).coerceIn(60, 179).toDouble())
                onClose()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClose) { Text("Abbrechen") } },
    )
}
