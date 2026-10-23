package de.eneriq.pilot

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Socket

enum class Link { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * TCP-Client fuer pypilot (Port 23322): Auto-Reconnect mit Backoff, Heartbeat alle 2 s,
 * Totzeit-Erkennung (kein Datum in [staleMs] -> Verbindung gilt als tot).
 * socketFactory erlaubt auf Android die Bindung ans WLAN.
 */
class PypilotClient(
    private val scope: CoroutineScope,
    @Volatile var host: String,
    private val port: Int = Pypilot.PORT,
    private val watchNames: List<String>,
    private val socketFactory: () -> Socket = { Socket() },
    private val heartbeatMs: Long = 2000,
    private val staleMs: Int = 6000,
    private val connectTimeoutMs: Int = 3000,
    private val backoff: Backoff = Backoff(),
    private val onValue: (String, String) -> Unit,
) {
    private val _link = MutableStateFlow(Link.DISCONNECTED)
    val link: StateFlow<Link> = _link

    private var job: Job? = null
    @Volatile private var out: java.io.Writer? = null
    @Volatile private var current: Socket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel(); job = null
        runCatching { current?.close() }
        _link.value = Link.DISCONNECTED
    }

    /** Verbindung neu aufbauen (z. B. nach Hostwechsel). */
    fun reconnect() { runCatching { current?.close() } }

    fun send(line: String): Boolean = synchronized(this) {
        val w = out ?: return false
        try { w.write(line + "\n"); w.flush(); true } catch (e: Exception) { runCatching { current?.close() }; false }
    }

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            _link.value = Link.CONNECTING
            val s = socketFactory()
            var hb: Job? = null
            try {
                current = s
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                s.soTimeout = staleMs
                s.tcpNoDelay = true
                out = s.getOutputStream().bufferedWriter()
                send(watchLine(watchNames))
                _link.value = Link.CONNECTED
                backoff.reset()
                hb = scope.launch(Dispatchers.IO) {
                    var n = 0
                    while (isActive) { send(setLine("ap.heartbeat", (n++).toString())); delay(heartbeatMs) }
                }
                val r = s.getInputStream().bufferedReader()
                while (true) {
                    val line = r.readLine() ?: break
                    parseLine(line)?.let { onValue(it.name, it.value) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Verbindungsfehler oder Totzeit: unten neu verbinden
            } finally {
                hb?.cancel()
                out = null
                runCatching { s.close() }
                current = null
            }
            _link.value = Link.DISCONNECTED
            delay(backoff.next())
        }
    }
}
