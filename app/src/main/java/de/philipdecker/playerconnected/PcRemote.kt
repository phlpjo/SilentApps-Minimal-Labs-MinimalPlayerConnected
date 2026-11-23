package de.philipdecker.playerconnected

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Was gerade am Computer (Minimal Library) läuft */
data class PcNow(
    val playing: Boolean,
    val trackId: Long?,
    val title: String,
    val artist: String,
    val posMs: Long,
    val durMs: Long,
    /** 0..1 */
    val volume: Float,
    val shuffle: Boolean,
    val outputId: String,
    val outputName: String,
    val outputs: List<Pair<String, String>>,
    val queue: List<Long>,
    val index: Int,
    val computer: String,
    /** Zeitpunkt der Meldung (für die laufende Position) */
    val at: Long = SystemClock.elapsedRealtime(),
) {
    fun position(): Long = if (playing) (posMs + SystemClock.elapsedRealtime() - at).coerceAtMost(if (durMs > 0) durMs else Long.MAX_VALUE) else posMs
}

/**
 * Fernsteuerung wie bei Spotify: zeigt, was am PC läuft, steuert ihn – und meldet dem PC,
 * was das Handy spielt (der PC kann dann hier eingreifen).
 */
object PcRemote {
    val now = MutableStateFlow<PcNow?>(null)

    /** Vom Wiedergabedienst gesetzt: aktueller Zustand des Handys und Ausführen von PC-Befehlen (Hauptthread) */
    @Volatile var phoneState: (() -> JSONObject?)? = null
    @Volatile var phoneExec: ((JSONObject) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0
    /** Steuert gerade den PC → öfter nachsehen */
    @Volatile var attached = false

    @Synchronized fun acquire() {
        users++
        if (job?.isActive == true) return
        job = scope.launch {
            var tick = 0
            while (isActive) {
                if (Pc.state.value.paired && Pc.state.value.online) {
                    readNow()
                    if (tick++ % 2 == 0) reportPhone()
                } else now.value = null
                delay(if (attached) 1000L else 1500L)
            }
        }
    }

    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { job?.cancel(); job = null }
    }

    private fun readNow() {
        val (st, body) = Pc.call("GET", "/now") ?: return
        if (st != 200) return
        try {
            val o = JSONObject(body)
            val outs = ArrayList<Pair<String, String>>()
            o.optJSONArray("outputs")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { outs += it.optString("id") to it.optString("name") } }
            val q = ArrayList<Long>()
            o.optJSONArray("queue")?.let { a -> for (i in 0 until a.length()) q += a.optLong(i) }
            val out = o.optJSONObject("output")
            now.value = PcNow(
                playing = o.optBoolean("playing"),
                trackId = if (o.isNull("trackId") || !o.has("trackId")) null else o.optLong("trackId"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                posMs = (o.optDouble("pos", 0.0) * 1000).toLong(),
                durMs = (o.optDouble("dur", 0.0) * 1000).toLong(),
                volume = o.optDouble("volume", 0.5).toFloat(),
                shuffle = o.optBoolean("shuffle"),
                outputId = out?.optString("id") ?: "local",
                outputName = out?.optString("name") ?: "",
                outputs = outs,
                queue = q,
                index = o.optInt("index", -1),
                computer = o.optString("computer"),
            )
        } catch (_: Exception) { }
    }

    /** Handy meldet seinen Zustand, Antwort = Befehle vom PC */
    private suspend fun reportPhone() {
        val state = withContext(Dispatchers.Main) { try { phoneState?.invoke() } catch (_: Exception) { null } } ?: return
        val (st, body) = Pc.call("POST", "/me", state.toString()) ?: return
        if (st != 200) return
        val cmds = try { JSONArray(body) } catch (_: Exception) { return }
        if (cmds.length() == 0) return
        withContext(Dispatchers.Main) {
            for (i in 0 until cmds.length()) cmds.optJSONObject(i)?.let { c -> try { phoneExec?.invoke(c) } catch (_: Exception) { } }
        }
    }

    /** Befehl an den PC: toggle, play, pause, next, prev, seek(pos s), volume(vol 0..1), output(id), queue(…) */
    fun ctl(cmd: JSONObject) {
        scope.launch { Pc.call("POST", "/ctl", cmd.toString(), timeout = 4000) }
    }

    fun ctl(cmd: String, vararg extra: Pair<String, Any?>) = ctl(JSONObject().apply { put("cmd", cmd); extra.forEach { (k, v) -> put(k, v) } })

    /** Ziel „Computer · Ausgabe“ für die Auswahl, wo die Musik läuft */
    fun target(outputId: String, outputName: String): Target {
        val n = now.value
        return Target(
            id = "pc:$outputId", name = outputName, sub = n?.computer ?: Pc.state.value.name,
            sonos = false, host = "", avt = Svc("", ""), rc = null, grc = null, pc = true, pcOutput = outputId,
        )
    }
}
