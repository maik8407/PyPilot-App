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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.eneriq.pilot.Link
import de.eneriq.pilot.Pypilot
import de.eneriq.pilot.normalize180
import kotlinx.coroutines.launch

internal val Bg = Color(0xFF121212)
internal val Teal = Color(0xFF76C7C0)
internal val Red = Color(0xFFD32F2F)
internal val Amber = Color(0xFFFFB300)
internal val Panel = Color(0xFF2A2A2A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        PilotService.start(this)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = quit()
        })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Teal, background = Bg, surface = Color(0xFF1E1E1E))) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Root(::quit) }
            }
        }
    }

    /** Beenden: Service (Verbindung, Benachrichtigung) stoppen und die App aus der Uebersicht nehmen. */
    private fun quit() {
        PilotService.stop(this)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) PilotService.stop(this)
    }
}

@Composable
private fun Root(onQuit: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val s by PilotHub.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val (linkText, linkColor) = if (s.link == Link.CONNECTED) "Verbunden" to Teal else "Keine Verbindung" to Amber
        Row(Modifier.fillMaxWidth().background(linkColor.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(start = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(linkText, color = linkColor, fontWeight = FontWeight.Bold)
            TextButton(onClick = { settings = true }) { Text("Einstellungen") }
        }
        TabRow(tab, containerColor = Bg) {
            listOf("Steuern", "Regler", "Karte").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> SteerScreen(s)
                1 -> TuneScreen(s)
                else -> MapScreen(s)
            }
        }
        // STOPP ist auf jedem Reiter sichtbar
        Button({ PilotHub.stop() }, Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White)) {
            Text("STOPP", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
    }

    if (settings) SettingsDialog(ctx, onQuit) { settings = false }
}

@Composable
private fun SteerScreen(s: PilotState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val windMode = s.mode == Mode.WIND
    fun show(v: Double?): String = when {
        v == null -> "--"
        windMode -> String.format("%+.0f°", normalize180(v) * Pypilot.WIND_SIGN)
        else -> String.format("%.0f°", v)
    }
    val shortcuts = PilotHub.shortcutsAvailable(s)

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(show(s.heading), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text("Soll ${show(s.command)}", fontSize = 26.sp, color = Teal)
            Text(if (s.message.isNotEmpty()) s.message else " ", color = Amber, fontSize = 14.sp)
        }

        // Bereitschaft <-> Automatik
        Button({ PilotHub.toggleAuto() }, Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (s.enabled) Teal else Panel, contentColor = if (s.enabled) Color.Black else Color.White)) {
            Text(if (s.enabled) "AUTOMATIK  (tippen: Bereitschaft)" else "BEREITSCHAFT  (tippen: Automatik)",
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { m ->
                Button({ PilotHub.setMode(m) }, Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (s.mode == m) Teal else Panel,
                        contentColor = if (s.mode == m) Color.Black else Color.White)) { Text(m.label) }
            }
        }

        // Winkel: links Backbord, rechts Steuerbord
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("◄ 10", Modifier.weight(1f)) { PilotHub.nudge(10, false) }
            StepButton("◄ 1", Modifier.weight(1f)) { PilotHub.nudge(1, false) }
            StepButton("1 ►", Modifier.weight(1f)) { PilotHub.nudge(1, true) }
            StepButton("10 ►", Modifier.weight(1f)) { PilotHub.nudge(10, true) }
        }
        Text("◄ Backbord        Steuerbord ►", Modifier.fillMaxWidth(), color = Color.Gray, fontSize = 12.sp, textAlign = TextAlign.Center)

        // Shortcuts nur in Automatik + Wind-Modus
        Text("Wind-Shortcuts" + if (shortcuts) "" else "  (nur Automatik + Wind)", color = Color.Gray, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutButton("Vorsegel\neinholen", shortcuts, Modifier.weight(1f)) { PilotHub.foresail(ctx) }
            ShortcutButton("Großsegel\neinholen", shortcuts, Modifier.weight(1f)) { PilotHub.mainsail(ctx) }
            ShortcutButton("Zurück auf\nalten Kurs", shortcuts, Modifier.weight(1f)) { PilotHub.backToOldCourse() }
        }
    }
}

@Composable
private fun StepButton(label: String, modifier: Modifier, onClick: () -> Unit) =
    Button(onClick, modifier.height(64.dp), colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White)) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }

@Composable
private fun ShortcutButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) =
    Button(onClick, modifier.height(64.dp), enabled = enabled, contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Color.White,
            disabledContainerColor = Color(0xFF1C1C1C), disabledContentColor = Color(0xFF555555))) {
        Text(label, fontSize = 14.sp, textAlign = TextAlign.Center)
    }

@Composable
private fun SettingsDialog(ctx: android.content.Context, onQuit: () -> Unit, onClose: () -> Unit) {
    var host by remember { mutableStateOf(PilotHub.host(ctx)) }
    var max by remember { mutableStateOf(PilotHub.maxAngle(ctx).toInt().toString()) }
    var fore by remember { mutableStateOf(PilotHub.foresailAngle(ctx).toInt().toString()) }
    var main by remember { mutableStateOf(PilotHub.mainsailAngle(ctx).toInt().toString()) }
    var status by remember { mutableStateOf("App-Version ${AppUpdater.installedName(ctx)}") }
    var info by remember { mutableStateOf<AppUpdater.Info?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Einstellungen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it }, label = { Text("pypilot-Adresse") }, singleLine = true)
                OutlinedTextField(max, { max = it.filter(Char::isDigit) }, label = { Text("Maximaler Windwinkel (°)") }, singleLine = true)
                OutlinedTextField(fore, { fore = it.filter(Char::isDigit) }, label = { Text("Vorsegel einholen: Winkel (°)") }, singleLine = true)
                OutlinedTextField(main, { main = it.filter(Char::isDigit) }, label = { Text("Großsegel einholen: Winkel (°)") }, singleLine = true)
                Text(status, fontSize = 14.sp)
                val i = info
                if (i == null) {
                    OutlinedButton({
                        status = "Suche ..."
                        scope.launch {
                            runCatching { AppUpdater.check(ctx) }
                                .onSuccess { info = it; status = if (it == null) "Aktuell (${AppUpdater.installedName(ctx)})" else "Neu: ${it.versionName}  ${it.notes}" }
                                .onFailure { status = "Fehler: ${it.message}" }
                        }
                    }) { Text("Nach Update suchen") }
                } else {
                    Button({
                        if (!ctx.packageManager.canRequestPackageInstalls()) {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:${ctx.packageName}")))
                            status = "Bitte \"Installieren erlauben\" einschalten, dann nochmal tippen"
                        } else scope.launch {
                            runCatching { AppUpdater.install(ctx, i) { p -> status = "Lade ... $p %" } }
                                .onFailure { status = "Fehler: ${it.message}" }
                        }
                    }) { Text("Installieren ${i.versionName}") }
                }
                OutlinedButton(onQuit) { Text("App beenden") }
            }
        },
        confirmButton = {
            TextButton({
                fun a(v: String, d: Int, lo: Int, hi: Int) = (v.toIntOrNull() ?: d).coerceIn(lo, hi).toDouble()
                val m = a(max, 170, 60, 179)
                PilotHub.saveSettings(ctx, host.trim(), m, a(fore, 110, 20, m.toInt()), a(main, 38, 20, m.toInt()))
                onClose()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClose) { Text("Abbrechen") } },
    )
}
