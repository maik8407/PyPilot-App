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
        val client = PypilotClient(scope, "127.0.0.1", server.localPort, listOf("ap.heading"),
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

    @Test fun clientConnectsWatchesHeartbeatsAndReconnects() = runBlocking {
        val server = ServerSocket(0)
        val received = CopyOnWriteArrayList<String>()
        val values = CopyOnWriteArrayList<String>()
        var accepts = 0
        val srv = launch(Dispatchers.IO) {
            while (isActive) {
                val c = try { server.accept() } catch (_: Exception) { break }
                accepts++
                val first = accepts == 1
                launch(Dispatchers.IO) {
                    val r = c.getInputStream().bufferedReader()
                    c.getOutputStream().write("ap.heading=90.0\n".toByteArray())
                    repeat(3) { r.readLine()?.let(received::add) }
                    if (first) c.close()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = PypilotClient(scope, "127.0.0.1", server.localPort, listOf("ap.heading"),
            heartbeatMs = 50, backoff = Backoff(50, 100)) { n, v -> values.add("$n=$v") }
        client.start()
        withTimeout(5000) { while (accepts < 2) delay(20) }
        withTimeout(2000) { while (received.none { it.startsWith("ap.heartbeat=") }) delay(20) }
        assertTrue(received.any { it.startsWith("watch=") })
        assertTrue(values.contains("ap.heading=90.0"))
        client.stop(); scope.cancel(); srv.cancel(); server.close()
    }
}
