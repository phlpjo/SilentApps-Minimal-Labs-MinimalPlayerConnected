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

/** Eine Wiedergabe im Haus: ein Tab von Minimal Library ("pc:<tab>") oder ein Handy ("phone:<name>") */
data class Session(
    val id: String,
    val kind: String,
    val device: String,
    val tabName: String,
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
    val controllable: Boolean,
    /** das bin ich (dieses Handy) */
    val self: Boolean,
    val queue: List<Long>,
    val index: Int,
    /** Zeitpunkt der Meldung (für die laufende Position) */
    val at: Long = SystemClock.elapsedRealtime(),
) {
    fun position(): Long = if (playing) (posMs + SystemClock.elapsedRealtime() - at).coerceAtMost(if (durMs > 0) durMs else Long.MAX_VALUE) else posMs
    val isPc get() = kind == "pc"
    /** „Computer · Tab“ bzw. Name des Handys */
    val who get() = if (isPc && tabName.isNotBlank()) "$device · $tabName" else device
}

/** Ein Ausgang, den der PC kennt: seine Soundkarten ("local:<id>", kind "pc") und die Lautsprecher im Heimnetz */
data class HouseOut(val id: String, val name: String, val kind: String)

data class House(val computer: String, val sessions: List<Session>, val outputs: List<HouseOut>) {
    fun session(id: String) = sessions.firstOrNull { it.id == id }
    /** Wer spielt gerade auf diesem Ausgang – außer `except`? */
    fun busy(outputId: String, except: String? = null): Session? {
        val k = outKey(outputId)
        return sessions.firstOrNull { it.id != except && !it.self && it.playing && outKey(it.outputId) == k }
    }
}

/** Gleicher Ausgang? Sonos-Kennungen kommen mal mit, mal ohne „uuid:“ */
fun outKey(id: String) = id.removePrefix("uuid:").substringBefore("::").lowercase()

/**
 * Fernsteuerung wie bei Spotify: zeigt, was am PC läuft, steuert ihn – und meldet dem PC,
 * was das Handy spielt (der PC kann dann hier eingreifen).
 */
object PcRemote {
    val house = MutableStateFlow<House?>(null)
    /** Haupt-Wiedergabe des PCs (Tab „main“) – deren Titel kennt das Handy */
    const val MAIN = "pc:main"
    fun session(id: String): Session? = house.value?.session(id)

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
                    readHouse()
                    if (tick++ % 2 == 0) reportPhone()
                } else house.value = null
                delay(if (attached) 1000L else 1500L)
            }
        }
    }

    @Synchronized fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { job?.cancel(); job = null }
    }

    private fun readHouse() {
        val (st, body) = Pc.call("GET", "/house") ?: return
        if (st == 404) return readOld()
        if (st != 200) return
        try {
            val o = JSONObject(body)
            val sessions = ArrayList<Session>()
            o.optJSONArray("sessions")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { sessions += parse(it) } }
            val outs = ArrayList<HouseOut>()
            o.optJSONArray("outputs")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { outs += HouseOut(it.optString("id"), it.optString("name"), it.optString("kind", "pc")) }
            }
            house.value = House(o.optString("computer"), sessions, outs)
        } catch (_: Exception) { }
    }

    private fun parse(o: JSONObject, id0: String? = null, computer: String = ""): Session {
        val q = ArrayList<Long>()
        o.optJSONArray("queue")?.let { a -> for (i in 0 until a.length()) q += a.optLong(i) }
        val out = o.optJSONObject("output")
        return Session(
            id = id0 ?: o.optString("id"),
            kind = o.optString("kind", "pc"),
            device = o.optString("device", computer),
            tabName = o.optString("tabName"),
            playing = o.optBoolean("playing"),
            trackId = if (o.isNull("trackId") || !o.has("trackId")) null else o.optLong("trackId"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            posMs = (o.optDouble("pos", 0.0) * 1000).toLong(),
            durMs = (o.optDouble("dur", 0.0) * 1000).toLong(),
            volume = o.optDouble("volume", 0.5).toFloat(),
            shuffle = o.optBoolean("shuffle"),
            outputId = out?.optString("id") ?: o.optString("outputId"),
            outputName = out?.optString("name") ?: o.optString("output"),
            controllable = o.optBoolean("controllable", true),
            self = o.optBoolean("self"),
            queue = q,
            index = o.optInt("index", -1),
        )
    }

    /** Ältere Minimal Library ohne /house: nur die eine PC-Wiedergabe */
    private fun readOld() {
        val (st, body) = Pc.call("GET", "/now") ?: return
        if (st != 200) return
        try {
            val o = JSONObject(body)
            val computer = o.optString("computer")
            val outs = ArrayList<HouseOut>()
            o.optJSONArray("outputs")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                    val id = it.optString("id")
                    outs += HouseOut(if (id == "local") "local:default" else id, it.optString("name"), if (id == "local") "pc" else "speaker")
                }
            }
            house.value = House(computer, listOf(parse(o, MAIN, computer)), outs)
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

    /** Befehl an eine Wiedergabe (über den PC, auch an andere Handys): toggle, play, pause, next, prev, seek(pos s), volume(vol 0..1), output(id), queue(…) */
    fun ctl(session: String, cmd: JSONObject) {
        cmd.put("session", session)
        scope.launch { Pc.call("POST", "/ctl", cmd.toString(), timeout = 4000) }
    }

    fun ctl(session: String, cmd: String, vararg extra: Pair<String, Any?>) =
        ctl(session, JSONObject().apply { put("cmd", cmd); extra.forEach { (k, v) -> put(k, v) } })

    /** Ziel „Wiedergabe am PC · Ausgang“: das Handy steuert dann diese Wiedergabe */
    fun target(session: String, outputId: String, outputName: String): Target {
        val h = house.value
        val s = h?.session(session)
        return Target(
            id = "pc:$session|${outKey(outputId)}", name = outputName.ifBlank { s?.outputName ?: "" },
            sub = s?.who ?: h?.computer ?: Pc.state.value.name,
            sonos = false, host = "", avt = Svc("", ""), rc = null, grc = null, pc = true, pcOutput = outputId, pcSession = session,
        )
    }
}
