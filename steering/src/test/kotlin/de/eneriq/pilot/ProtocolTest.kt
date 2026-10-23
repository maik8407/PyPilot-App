package de.eneriq.pilot

import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ProtocolTest {
    @Test fun parsesLines() {
        assertEquals(Msg("ap.heading", "123.4"), parseLine("ap.heading=123.4"))
        assertEquals(Msg("ap.mode", "\"wind\""), parseLine("ap.mode=\"wind\""))
        assertNull(parseLine("kaputt"))
        assertEquals("wind", parseString("\"wind\""))
        assertEquals(12.5, parseNumber("12.5"))
    }

    @Test fun buildsLines() {
        assertEquals("watch={\"ap.heading\":0.5,\"ap.mode\":0.5}", watchLine(listOf("ap.heading", "ap.mode")))
        assertEquals("ap.mode=\"gps\"", setString("ap.mode", "gps"))
        assertEquals("ap.enabled=false", setLine("ap.enabled", "false"))
    }

    @Test fun backoffDoublesAndResets() {
        val b = Backoff(1000, 4000)
        assertEquals(listOf(1000L, 2000L, 4000L, 4000L), List(4) { b.next() })
        b.reset(); assertEquals(1000L, b.next())
    }

    private fun waitFor(ms: Long = 3000, c: () -> Boolean) = runBlocking { withTimeout(ms) { while (!c()) delay(10) } }

    @Test fun clientTalksRealPypilotFormat() {
        val srv = FakePypilot()
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = PypilotClient(scope, "127.0.0.1", srv.port,
            listOf("watch={\"values\":true}", watchLine(listOf("ap.enabled", "ap.mode", "ap.heading", "ap.heading_command", "profile", "profiles", "gibt.es.nicht"), 0.5)),
            keepaliveMs = 50) { n, v -> values[n] = v }
        client.start()
        waitFor { client.link.value == Link.CONNECTED && values.containsKey("profiles") && values.containsKey("values") }

        // Anfangswerte im echten Format
        assertEquals("false", values["ap.enabled"]); assertEquals("\"compass\"", values["ap.mode"])
        assertEquals(123.4567, parseNumber(values["ap.heading"]!!)); assertEquals("default", parseString(values["profile"]!!))
        assertTrue(values["values"]!!.contains("\"ap.pilot.basic.P\":{\"type\":\"RangeProperty\""))

        // Befehle aus dem Hauptthread-aehnlichen Aufruf: Modus, Sollwert, Automatik, Profil, Gain
        assertTrue(client.send(setString("ap.mode", "wind")))
        assertTrue(client.send(setLine("ap.heading_command", "45.5")))
        assertTrue(client.send(setLine("ap.enabled", "true")))
        assertTrue(client.send(setString("profile", "segeln")))
        assertTrue(client.send(setLine("ap.pilot.basic.P", "0.0040")))
        waitFor { values["ap.enabled"] == "true" && values["profile"] == "\"segeln\"" && values["ap.mode"] == "\"wind\"" }
        assertEquals(45.5, parseNumber(values["ap.heading_command"]!!))
        assertEquals(0.004, (srv.values["ap.pilot.basic.P"] as FakePypilot.V.Range).v, 1e-9)

        // pypilot ignoriert ungueltige Werte: unbekannter Modus, Gain ausserhalb des Bereichs
        client.send(setString("ap.mode", "ufo")); client.send(setLine("ap.pilot.basic.P", "9.0"))
        waitFor { srv.received.any { it == "ap.pilot.basic.P=9.0" } }
        Thread.sleep(150)
        assertEquals("wind", (srv.values["ap.mode"] as FakePypilot.V.Enum).v)
        assertEquals(0.004, (srv.values["ap.pilot.basic.P"] as FakePypilot.V.Range).v, 1e-9)

        // der alte Fehler: ein Heartbeat-Wert "ap.heartbeat" existiert in pypilot nicht -> Fehlerantwort; Leerzeile ist dagegen erlaubt
        client.send("ap.heartbeat=1")
        waitFor { values.containsKey("error") }
        assertTrue(values["error"]!!.contains("ap.heartbeat"))
        assertTrue(srv.received.contains(""))        // Keepalive als Leerzeile
        assertTrue(srv.errors.all { it == "ap.heartbeat" })

        client.stop(); scope.cancel(); srv.close()
    }

    @Test fun sendWhileDisconnectedReturnsFalse() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = PypilotClient(scope, "127.0.0.1", 1, listOf("watch={}")) { _, _ -> }
        assertFalse(client.send("ap.enabled=true"))
        scope.cancel()
    }

    @Test fun clientStopsReconnectingAfterStop() = runBlocking {
        val server = ServerSocket(0)
        var accepts = 0
        val srv = launch(Dispatchers.IO) {
            while (isActive) {
                val c = try { server.accept() } catch (_: Exception) { break }
                accepts++
                c.close()   // sofort trennen -> Client versucht es immer wieder
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = PypilotClient(scope, "127.0.0.1", server.localPort, listOf("watch={}"),
            backoff = Backoff(30, 30)) { _, _ -> }
        client.start()
        withTimeout(5000) { while (accepts < 3) delay(10) }
        client.stop()
        delay(100)
        val n = accepts
        delay(400)
        assertEquals(n, accepts, "nach stop() darf nicht mehr verbunden werden")
        assertEquals(Link.DISCONNECTED, client.link.value)
        scope.cancel(); srv.cancel(); server.close()
    }

    @Test fun clientReconnectsAfterServerDrop() = runBlocking {
        val server = ServerSocket(0)
        val received = CopyOnWriteArrayList<String>()
        var accepts = 0
        val srv = launch(Dispatchers.IO) {
            while (isActive) {
                val c = try { server.accept() } catch (_: Exception) { break }
                accepts++
                val first = accepts == 1
                launch(Dispatchers.IO) {
                    val r = c.getInputStream().bufferedReader()
                    repeat(2) { r.readLine()?.let(received::add) }
                    if (first) c.close()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = PypilotClient(scope, "127.0.0.1", server.localPort, listOf("watch={\"ap.heading\":0.5}"),
            keepaliveMs = 50, backoff = Backoff(50, 100)) { _, _ -> }
        client.start()
        withTimeout(5000) { while (accepts < 2) delay(20) }
        assertTrue(received.count { it.startsWith("watch=") } >= 2, "Watch muss nach dem Reconnect erneut gesendet werden")
        client.stop(); scope.cancel(); srv.cancel(); server.close()
    }
}
