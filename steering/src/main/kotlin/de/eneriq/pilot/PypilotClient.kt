package de.eneriq.pilot

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Socket

enum class Link { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * TCP-Client fuer pypilot (Port 23322): Auto-Reconnect mit Backoff, Keepalive alle 2 s,
 * Totzeit-Erkennung (kein Datum in [staleMs] -> Verbindung gilt als tot).
 * socketFactory erlaubt auf Android die Bindung ans WLAN.
 *
 * send() darf von jedem Thread (auch dem Android-Hauptthread) aufgerufen werden: die Zeilen gehen in eine
 * Warteschlange und werden von einer eigenen IO-Coroutine geschrieben (Netzwerk auf dem Hauptthread ist auf Android verboten).
 * pypilot kennt keinen Heartbeat-Wert; eine leere Zeile wird vom Server still ignoriert und haelt die Verbindung wach.
 * Zeilen "error=..." vom Server werden als onValue("error", text) weitergegeben.
 */
class PypilotClient(
    private val scope: CoroutineScope,
    @Volatile var host: String,
    private val port: Int = Pypilot.PORT,
    private val watchLines: List<String>,
    private val socketFactory: () -> Socket = { Socket() },
    private val keepaliveMs: Long = 2000,
    private val staleMs: Int = 6000,
    private val connectTimeoutMs: Int = 3000,
    private val backoff: Backoff = Backoff(),
    private val onValue: (String, String) -> Unit,
) {
    private val _link = MutableStateFlow(Link.DISCONNECTED)
    val link: StateFlow<Link> = _link

    private var job: Job? = null
    @Volatile private var outbox = Channel<String>(Channel.UNLIMITED)
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

    /** Zeile zum Senden vormerken. false, wenn gerade keine Verbindung steht (dann wird nichts gesendet). */
    fun send(line: String): Boolean {
        if (_link.value != Link.CONNECTED) return false
        return outbox.trySend(line).isSuccess
    }

    /** Beobachtung weiterer Werte nachtraeglich anfordern (gilt nur fuer die laufende Verbindung). */
    fun watch(line: String) = send(line)

    private suspend fun run() {
        while (currentCoroutineContext().isActive) {
            _link.value = Link.CONNECTING
            val s = socketFactory()
            val box = Channel<String>(Channel.UNLIMITED)
            var writer: Job? = null
            var keepalive: Job? = null
            try {
                current = s
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                s.soTimeout = staleMs
                s.tcpNoDelay = true
                val w = s.getOutputStream().bufferedWriter()
                watchLines.forEach { box.trySend(it) }
                outbox = box
                writer = scope.launch(Dispatchers.IO) {
                    try {
                        for (l in box) { w.write(l + "\n"); w.flush() }
                    } catch (_: Exception) { runCatching { s.close() } }   // Schreibfehler -> Leser bricht ab -> Reconnect
                }
                _link.value = Link.CONNECTED
                backoff.reset()
                keepalive = scope.launch(Dispatchers.IO) {
                    while (isActive) { box.trySend(""); delay(keepaliveMs) }
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
                keepalive?.cancel()
                box.close()
                writer?.cancel()
                runCatching { s.close() }
                current = null
            }
            _link.value = Link.DISCONNECTED
            delay(backoff.next())
        }
    }
}
