package de.eneriq.pilot

import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fake-Server, der das Verhalten von pypilot/server.py und values.py nachbildet (Quelltext gelesen, kein echter
 * pypilot-Prozess: auf diesem PC ist kein Python vorhanden):
 * - Zeilen "name=json"; eine leere Zeile wird still ignoriert; unbekannte Namen -> "error=invalid unknown value: name"
 * - "watch={name: periode|true}" liefert den aktuellen Wert sofort und danach bei jeder Aenderung
 * - Wertpruefung wie in values.py: Boolean (not not value), EnumProperty (nur bekannte Namen), RangeProperty (min..max, sonst ignoriert)
 * - Antworten: Boolean "true"/"false", String in Anfuehrungszeichen, Bereichswerte mit "%.4f"
 */
class FakePypilot {
    sealed class V {
        class Bool(var v: Boolean) : V()
        class Enum(var v: String, val choices: List<String>) : V()
        class Range(var v: Double, val min: Double, val max: Double) : V()
        class Str(var v: String) : V()
        class Json(var v: String) : V()
        class Sensor(var v: Double) : V()
    }

    val values = ConcurrentHashMap<String, V>().apply {
        put("ap.enabled", V.Bool(false))
        put("ap.mode", V.Enum("compass", listOf("compass", "gps", "nav", "wind", "true wind")))
        put("ap.modes", V.Json("[\"compass\",\"gps\",\"wind\"]"))
        put("ap.heading", V.Sensor(123.4567))
        put("ap.heading_command", V.Range(120.0, -180.0, 360.0))
        put("ap.pilot", V.Enum("basic", listOf("basic", "simple")))
        put("ap.pilot.basic.P", V.Range(0.003, 0.0, 0.03))
        put("ap.pilot.basic.D", V.Range(0.09, 0.0, 0.24))
        put("ap.pilot.basic.Pgain", V.Sensor(0.0123))
        put("ap.pilot.basic.Dgain", V.Sensor(-0.004))
        put("rudder.angle", V.Sensor(-3.0))
        put("profile", V.Str("default"))
        put("profiles", V.Json("[\"default\",\"segeln\"]"))
    }

    val received = CopyOnWriteArrayList<String>()
    val errors = CopyOnWriteArrayList<String>()
    private val watchers = ConcurrentHashMap<String, MutableList<Socket>>()
    private val server = ServerSocket(0)
    val port get() = server.localPort
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            while (isActive) {
                val c = try { server.accept() } catch (_: Exception) { break }
                launch { serve(c) }
            }
        }
    }

    fun close() { scope.cancel(); server.close() }

    private fun msg(name: String): String? = when (val v = values[name]) {
        is V.Bool -> if (v.v) "true" else "false"
        is V.Enum -> "\"${v.v}\""
        is V.Range -> "%.4f".format(java.util.Locale.US, v.v)
        is V.Str -> "\"${v.v}\""
        is V.Json -> v.v
        is V.Sensor -> "%.4f".format(java.util.Locale.US, v.v)
        null -> null
    }

    private fun valuesList(): String =
        "values={" + values.entries.joinToString(",") { (k, v) ->
            val info = when {
                v is V.Range && k.startsWith("ap.pilot.") -> "{\"type\":\"RangeProperty\",\"min\":${v.min},\"max\":${v.max},\"persistent\":true,\"profiled\":true,\"AutopilotGain\":true}"
                v is V.Range -> "{\"type\":\"RangeProperty\",\"min\":${v.min},\"max\":${v.max}}"
                else -> "{\"type\":\"Value\"}"
            }
            "\"$k\":$info"
        } + "}"

    private fun write(c: Socket, line: String) { synchronized(c) { c.getOutputStream().write((line + "\n").toByteArray()); c.getOutputStream().flush() } }

    private fun serve(c: Socket) {
        val r = c.getInputStream().bufferedReader()
        try {
            while (true) {
                val line = r.readLine() ?: break
                received += line
                if (line.isEmpty()) continue   // "empty line used to poll connection"
                val i = line.indexOf('=')
                val name = line.substring(0, i); val data = line.substring(i + 1)
                if (name == "watch") {
                    Regex("\"([^\"]+)\"\\s*:\\s*([^,}]+)").findAll(data).forEach { m ->
                        val n = m.groupValues[1]
                        if (n == "values") { watchers.getOrPut(n) { CopyOnWriteArrayList() }.add(c); write(c, valuesList()); return@forEach }
                        if (!values.containsKey(n)) return@forEach        // Platzhalter wie im echten Server
                        watchers.getOrPut(n) { CopyOnWriteArrayList() }.add(c)
                        write(c, "$n=${msg(n)}")
                    }
                    continue
                }
                if (!values.containsKey(name)) { errors += name; write(c, "error=invalid unknown value: $name"); continue }
                set(name, data)
            }
        } catch (_: Exception) { } finally { watchers.values.forEach { it.remove(c) }; runCatching { c.close() } }
    }

    /** Wert setzen wie values.py; ungueltige Werte werden still ignoriert. */
    fun set(name: String, data: String) {
        val raw = data.trim()
        when (val v = values[name]) {
            is V.Bool -> v.v = raw == "true"
            is V.Enum -> { val s = raw.removeSurrounding("\""); if (s in v.choices) v.v = s else return }
            is V.Range -> { val d = raw.toDoubleOrNull() ?: return; if (d in v.min..v.max) v.v = d else return }
            is V.Str -> v.v = raw.removeSurrounding("\"")
            else -> return
        }
        watchers[name]?.forEach { runCatching { write(it, "$name=${msg(name)}") } }
    }
}
