package de.philipdecker.playerconnected

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Bereiche: Musik und Hörbücher */
const val MUSIC = 0
const val BOOKS = 1

/** Ein vom Nutzer gewählter Ordner (Speicherzugriff über Android, Baum-URI) */
data class Root(val uri: String, val name: String)

fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Zeit als „1:02:03“ oder „4:05“ */
fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) / 1000L)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun speedLabel(v: Float): String {
    val t = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0')
    return (if (t.endsWith('.')) t + "0" else t) + "×"
}

/** Natürliche Sortierung wie am PC: „2 Titel“ vor „10 Titel“, Groß/Klein egal */
object Natural : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var si = i; while (si < a.length && a[si] == '0') si++
                var sj = j; while (sj < b.length && b[sj] == '0') sj++
                var ei = si; while (ei < a.length && a[ei].isDigit()) ei++
                var ej = sj; while (ej < b.length && b[ej].isDigit()) ej++
                val la = ei - si; val lb = ej - sj
                if (la != lb) return la - lb
                for (k in 0 until la) { val d = a[si + k] - b[sj + k]; if (d != 0) return d }
                i = ei; j = ej
            } else {
                val d = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (d != 0) return d
                i++; j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/**
 * Was gerade gespielt wird („Container“), als Schlüssel:
 * d|bereich|root|pfad  = Dateien direkt in einem Ordner
 * r|bereich|root|pfad  = Ordner mit allen Unterordnern (z. B. Hörbuch mit CD1/CD2)
 * f|bereich|root|uri   = eine einzelne Datei
 */
object Keys {
    data class K(val kind: Char, val section: Int, val root: String, val rest: String)

    fun dir(section: Int, root: String, path: String) = "d|$section|$root|$path"
    fun tree(section: Int, root: String, path: String) = "r|$section|$root|$path"
    fun file(section: Int, root: String, uri: String) = "f|$section|$root|$uri"
    /** Datei von außen („Öffnen mit“) */
    fun external(section: Int, uri: String) = "x|$section|-|$uri"
    /** Suchergebnis als Titelliste (nur Musik) */
    fun search(section: Int, q: String) = "q|$section|-|${q.trim()}"
    /** Playlist (nur Musik) */
    fun playlist(id: Long) = "l|$MUSIC|-|$id"

    fun parse(key: String): K? {
        val p = key.split('|', limit = 4)
        if (p.size < 4 || p[0].length != 1) return null
        return K(p[0][0], p[1].toIntOrNull() ?: return null, p[2], p[3])
    }

    fun section(key: String) = parse(key)?.section ?: MUSIC

    /** Fortschritt eines Containers / einer Datei in der Tabelle „progress“ */
    fun progressOf(container: String) = "c|$container"
    fun progressOfFile(uri: String) = "u|$uri"
}

/** Schlaf-Timer – gesteuert vom Wiedergabe-Dienst, angezeigt in der Oberfläche */
object Sleep {
    /** Ende als Systemzeit (ms) oder null = aus */
    val endAt = MutableStateFlow<Long?>(null)
    /** Am Ende der aktuellen Datei anhalten */
    val endOfFile = MutableStateFlow(false)
    private var minutes = 30

    fun start(min: Int) { minutes = min; endOfFile.value = false; endAt.value = System.currentTimeMillis() + min * 60_000L }
    fun startEndOfFile() { endAt.value = null; endOfFile.value = true }
    fun extend(min: Int) { endAt.value?.let { endAt.value = it + min * 60_000L } }
    /** Schütteln: von vorn mit der zuletzt gewählten Dauer */
    fun restart() { if (endAt.value != null) endAt.value = System.currentTimeMillis() + minutes * 60_000L }
    fun cancel() { endAt.value = null; endOfFile.value = false }
}

/** Klang: Equalizer-Bänder des Geräts (vom Dienst gemeldet) und Änderungszähler für Lautstärke/EQ */
data class EqInfo(val freqs: List<Int>, val min: Int, val max: Int)

object Fx {
    val bands = MutableStateFlow<EqInfo?>(null)
    val changed = MutableStateFlow(0)

    /** Voreinstellungen in Millibel je Band: neutral, Sprache (weniger Bass, etwas klarer), mehr Bass */
    fun preset(name: String, info: EqInfo): List<Int> = info.freqs.map { hz ->
        val v = when (name) {
            "speech" -> when { hz < 120 -> -500; hz < 400 -> -250; hz in 1500..5000 -> 200; else -> 0 }
            "bass" -> when { hz < 120 -> 500; hz < 400 -> 250; else -> 0 }
            else -> 0
        }
        v.coerceIn(info.min, info.max)
    }

    /** Standard: Musik neutral, Hörbücher mit etwas weniger Bass */
    fun defaultPreset(section: Int) = if (section == BOOKS) "speech" else "flat"
}

/** Einstellungen – als Compose-Zustand, damit sich die Oberfläche sofort aktualisiert. Eine Instanz pro Prozess. */
class Prefs private constructor(ctx: Context) {
    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }

    private val sp = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** Gerade gezeigter Bereich (Hauptumschalter) */
    var section by mutableIntStateOf(sp.getInt("section", MUSIC)); private set
    fun showSection(s: Int) { section = s; sp.edit().putInt("section", s).apply() }

    private val roots = listOf(mutableStateOf(readRoots("roots0")), mutableStateOf(readRoots("roots1")))
    fun roots(s: Int): List<Root> = roots[s].value
    fun addRoot(s: Int, r: Root) {
        if (roots(s).any { it.uri == r.uri }) return
        roots[s].value = (roots(s) + r).sortedWith(compareBy(Natural) { it.name }); writeRoots(s)
    }
    fun removeRoot(s: Int, uri: String) { roots[s].value = roots(s).filter { it.uri != uri }; writeRoots(s) }

    /** Ordner aus der anderen Player-App, für die hier noch kein Zugriff erteilt wurde */
    private val pending = listOf(mutableStateOf(readRoots("pending0")), mutableStateOf(readRoots("pending1")))
    fun pendingRoots(s: Int): List<Root> = pending[s].value
    fun setPendingRoots(s: Int, list: List<Root>) {
        pending[s].value = list
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        sp.edit().putString("pending$s", a.toString()).apply()
    }
    fun removePending(s: Int, uri: String) {
        if (pendingRoots(s).none { it.uri == uri }) return
        setPendingRoots(s, pendingRoots(s).filter { it.uri != uri }); Library.version.value++
    }

    /** Zurück-/Vorspringen in Sekunden */
    var back by mutableIntStateOf(sp.getInt("back", 15)); private set
    fun cycleBack() { back = next(listOf(10, 15, 30), back); sp.edit().putInt("back", back).apply() }
    var forward by mutableIntStateOf(sp.getInt("forward", 30)); private set
    fun cycleForward() { forward = next(listOf(15, 30, 60), forward); sp.edit().putInt("forward", forward).apply() }

    /** Schlaf-Timer: vorgeschlagene Dauer, Ausblendzeit, Schütteln */
    var sleepMinutes by mutableIntStateOf(sp.getInt("sleepMinutes", 30)); private set
    fun cycleSleep() { sleepMinutes = next(listOf(15, 30, 45, 60, 90), sleepMinutes); sp.edit().putInt("sleepMinutes", sleepMinutes).apply() }
    var fade by mutableIntStateOf(sp.getInt("fade", 30)); private set
    fun cycleFade() { fade = next(listOf(10, 30, 60), fade); sp.edit().putInt("fade", fade).apply() }
    var shake by mutableStateOf(sp.getBoolean("shake", true)); private set
    fun toggleShake() { shake = !shake; sp.edit().putBoolean("shake", shake).apply() }

    /** Hörbuch-Tempo (Musik läuft immer mit 1,0) */
    var bookSpeed by mutableFloatStateOf(sp.getFloat("bookSpeed", 1f)); private set
    fun changeBookSpeed(v: Float) { bookSpeed = v; sp.edit().putFloat("bookSpeed", v).apply() }

    /** Lautstärke für die ganze App in Prozent (50–200, 100 = normal; darüber verstärkt) */
    var gain by mutableIntStateOf(sp.getInt("gain", 100)); private set
    fun changeGain(v: Int) { gain = v.coerceIn(50, 200); sp.edit().putInt("gain", gain).apply(); Fx.changed.value++ }

    /** Equalizer je Bereich: an/aus und Pegel (leer = Voreinstellung des Bereichs) */
    private val eqOn = listOf(mutableStateOf(sp.getBoolean("eqOn0", false)), mutableStateOf(sp.getBoolean("eqOn1", true)))
    fun eqOn(s: Int) = eqOn[s].value
    fun toggleEq(s: Int) { eqOn[s].value = !eqOn[s].value; sp.edit().putBoolean("eqOn$s", eqOn[s].value).apply(); Fx.changed.value++ }
    private val eqLevels = listOf(mutableStateOf(readInts("eq0")), mutableStateOf(readInts("eq1")))
    fun eqLevels(s: Int, info: EqInfo): List<Int> =
        eqLevels[s].value.takeIf { it.size == info.freqs.size } ?: Fx.preset(Fx.defaultPreset(s), info)
    fun setEqLevels(s: Int, levels: List<Int>) {
        eqLevels[s].value = levels; sp.edit().putString("eq$s", levels.joinToString(",")).apply(); Fx.changed.value++
    }

    /** Hörbücher: nach längerer Pause ein paar Sekunden zurückspringen */
    var autoRewind by mutableStateOf(sp.getBoolean("autoRewind", true)); private set
    fun toggleAutoRewind() { autoRewind = !autoRewind; sp.edit().putBoolean("autoRewind", autoRewind).apply() }
    /** Zeitpunkt der letzten Pause (übersteht App-Neustart) */
    var pausedAt: Long
        get() = sp.getLong("pausedAt", 0L)
        set(v) { sp.edit().putLong("pausedAt", v).apply() }

    /** Lautstärkeausgleich für Musik aus ReplayGain-Tags: "off" | "track" | "album" */
    var replayGain by mutableStateOf(sp.getString("replayGain", "album") ?: "album"); private set
    fun cycleReplayGain() { replayGain = next(listOf("album", "track", "off"), replayGain); sp.edit().putString("replayGain", replayGain).apply(); Fx.changed.value++ }

    /** Musik: „Interpret – Titel“ aus den Tags statt Dateiname anzeigen */
    var tagTitles by mutableStateOf(sp.getBoolean("tagTitles", false)); private set
    fun toggleTagTitles() { tagTitles = !tagTitles; sp.edit().putBoolean("tagTitles", tagTitles).apply(); Library.version.value++ }

    /** Cover aus den Metadaten in Listen und im Player */
    var covers by mutableStateOf(sp.getBoolean("covers", true)); private set
    fun toggleCovers() { covers = !covers; sp.edit().putBoolean("covers", covers).apply() }

    /** Wellenform im Player: "library" (ganze Bibliothek vorab) | "queue" (aktuelle Titelliste) | "track" (beim Öffnen) | "off" */
    var waveMode by mutableStateOf(sp.getString("waveMode", "library") ?: "library"); private set
    fun cycleWaveMode() { waveMode = next(listOf("library", "queue", "track", "off"), waveMode); sp.edit().putString("waveMode", waveMode).apply() }

    var showIcons by mutableStateOf(sp.getBoolean("showIcons", false)); private set
    fun toggleIcons() { showIcons = !showIcons; sp.edit().putBoolean("showIcons", showIcons).apply() }

    /** Schrift: "space" | "system" | "mono"; Größe 0 = klein, 1 = normal, 2 = groß */
    var font by mutableStateOf(sp.getString("font", "space") ?: "space"); private set
    fun cycleFont() { font = next(listOf("space", "system", "mono"), font); sp.edit().putString("font", font).apply() }
    var fontSize by mutableIntStateOf(sp.getInt("fontSize", 1)); private set
    fun cycleFontSize() { fontSize = (fontSize + 1) % 3; sp.edit().putInt("fontSize", fontSize).apply() }
    /** Sternenhimmel: "move" | "still" | "off" */
    var stars by mutableStateOf(sp.getString("stars", "move") ?: "move"); private set
    fun cycleStars() { stars = next(listOf("move", "still", "off"), stars); sp.edit().putString("stars", stars).apply() }
    var warpLevel by mutableIntStateOf(sp.getInt("warpLevel", 1)); private set
    fun cycleWarp() { warpLevel = (warpLevel + 1) % 4; sp.edit().putInt("warpLevel", warpLevel).apply() }

    /** Zuletzt gespielter Container je Bereich und welcher Bereich zuletzt lief */
    fun last(s: Int): String? = sp.getString("last$s", null)
    var lastSection: Int
        get() = sp.getInt("lastSection", MUSIC)
        private set(v) { sp.edit().putInt("lastSection", v).apply() }
    fun setLast(key: String) { val s = Keys.section(key); sp.edit().putString("last$s", key).apply(); lastSection = s }

    /** Id der Playlist „Stern“ */
    var starId: Long
        get() = sp.getLong("starId", 0L)
        set(v) { sp.edit().putLong("starId", v).apply() }

    var lastScan: Long
        get() = sp.getLong("lastScan", 0L)
        set(v) { sp.edit().putLong("lastScan", v).apply() }

    private fun <X> next(list: List<X>, cur: X): X = list[(list.indexOf(cur) + 1) % list.size]

    private fun readInts(k: String): List<Int> =
        sp.getString(k, "")?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: emptyList()

    private fun readRoots(k: String): List<Root> = try {
        val a = JSONArray(sp.getString(k, "[]"))
        List(a.length()) { a.getJSONObject(it).let { o -> Root(o.getString("uri"), o.getString("name")) } }
    } catch (_: Exception) { emptyList() }

    private fun writeRoots(s: Int) {
        val a = JSONArray()
        roots(s).forEach { a.put(JSONObject().put("uri", it.uri).put("name", it.name)) }
        sp.edit().putString("roots$s", a.toString()).apply()
    }
}
