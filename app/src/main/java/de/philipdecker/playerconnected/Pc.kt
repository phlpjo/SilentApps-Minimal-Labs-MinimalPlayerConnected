package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * Bibliothek am Computer (Minimal Library): solange sie läuft, sieht die App alle Ordner und Playlisten 1:1,
 * spielt direkt ab (Streaming im Heimnetz), bearbeitet Playlisten und macht Ordner
 * oder Playlisten offline verfügbar (Dateien im eigenen Speicher der App, nur im Bereich PC). Musik aus den
 * Handy-Ordnern, die am PC fehlt, wird bei Verbindung hochgeladen; eigene Playlisten gehen mit.
 *
 * Titel vom PC stehen in der normalen Datenbank (Bereich Musik) unter eigenen Wurzeln „pc:<PC>:<Nr>“ mit
 * Adressen „mlpc://<PC>/<Titel-ID>.<Endung>“. Erst beim Abspielen wird daraus die echte Adresse
 * (offline gespeicherte Datei oder http://<PC>/a/<ID>?t=<Schlüssel>).
 */
object Pc {
    const val SCHEME = "mlpc"
    private const val UDP_PORT = 47831

    data class Found(val id: String, val name: String, val addr: String)

    data class State(
        val paired: Boolean = false,
        val name: String = "",
        val online: Boolean = false,
        val syncing: Boolean = false,
        val error: String? = null,
        val loaded: Boolean = false,
    )

    val state = MutableStateFlow(State())

    data class OfflineStatus(val done: Int, val total: Int, val current: String?, val finished: Boolean, val error: String? = null)
    val offline = MutableStateFlow<OfflineStatus?>(null)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poll: Job? = null
    private val mirrorLock = Mutex()
    private val offlineLock = Mutex()

    private fun sp() = app.getSharedPreferences("pc", Context.MODE_PRIVATE)

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        val p = sp()
        state.value = State(paired = p.getString("token", null) != null, name = p.getString("name", "") ?: "", loaded = p.getString("version", null) != null)
        loadOffline()
    }

    private val id get() = sp().getString("id", "") ?: ""
    private val token get() = sp().getString("token", "") ?: ""
    private val addr get() = sp().getString("addr", "") ?: ""

    // ───────── Adressen ─────────

    fun isPc(uri: String?) = uri != null && uri.startsWith("$SCHEME://")
    fun isPcRoot(root: String?) = root != null && root.startsWith("pc:")
    fun rootKey(pc: String, idx: Int) = "pc:$pc:$idx"
    private fun uriOf(pc: String, trackId: Long, file: String): String {
        val ext = file.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"
        return "$SCHEME://$pc/$trackId.$ext"
    }
    fun trackIdOf(uri: String): Long? = if (isPc(uri)) uri.substringAfterLast('/').substringBefore('.').toLongOrNull() else null

    /** Gesehene Wurzeln (Musikordner am PC) */
    fun roots(): List<Root> {
        if (!::app.isInitialized) return emptyList()
        val arr = try { JSONArray(sp().getString("roots", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).map { Root(rootKey(id, it), arr.getString(it)) }
    }

    /** Netz-Adresse eines Titels vom PC (ohne Offline-Kopie) */
    fun httpUrl(uri: String): String? {
        val tid = trackIdOf(uri) ?: return null
        if (addr.isEmpty()) return null
        return "http://$addr/a/$tid.${uri.substringAfterLast('.')}?t=$token"
    }

    fun coverUrl(uri: String): String? {
        val tid = trackIdOf(uri) ?: return null
        return if (addr.isEmpty()) null else "http://$addr/c/$tid?t=$token"
    }

    /** Für den Player: offline gespeicherte Datei bevorzugen, sonst vom PC streamen */
    fun realUri(uri: Uri): Uri {
        if (uri.scheme != SCHEME) return uri
        val tid = trackIdOf(uri.toString())
        if (tid != null) docs[tid]?.let { return Uri.parse(it) }
        return httpUrl(uri.toString())?.let { Uri.parse(it) } ?: uri
    }

    /** Media3: mlpc:// beim Öffnen in die echte Adresse umschreiben */
    fun resolve(spec: DataSpec): DataSpec = if (spec.uri.scheme == SCHEME) spec.withUri(realUri(spec.uri)) else spec

    // ───────── Finden & Koppeln ─────────

    suspend fun discover(waitMs: Long = 1600): List<Found> = withContext(Dispatchers.IO) {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try { wm?.createMulticastLock("minimal-pc")?.apply { setReferenceCounted(false); acquire() } } catch (_: Exception) { null }
        val out = LinkedHashMap<String, Found>()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                s.soTimeout = 200
                val msg = "MLPCDISCOVER1".toByteArray()
                val targets = listOf(InetAddress.getByName("255.255.255.255"))
                fun send() = targets.forEach { t -> try { s.send(DatagramPacket(msg, msg.size, t, UDP_PORT)) } catch (_: Exception) { } }
                send()
                val end = System.currentTimeMillis() + waitMs
                var resent = false
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < end) {
                    if (!resent && end - System.currentTimeMillis() < waitMs / 2) { resent = true; send() }
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: Exception) { continue }
                    try {
                        val o = JSONObject(String(p.data, 0, p.length))
                        val f = Found(o.getString("id"), o.optString("name", "Computer"), "${p.address.hostAddress}:${o.getInt("port")}")
                        out[f.id] = f
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { } finally { try { lock?.release() } catch (_: Exception) { } }
        out.values.toList()
    }

    /** Mit dem Code aus Minimal Library (Einstellungen) koppeln. null = ok, sonst Fehlertext */
    /** Von Hand eingegebene Adresse („192.168.1.20“, „nas.local:47830“, „http://…“) → „host:port“, sonst null */
    fun manualAddr(input: String): String? {
        var s = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (s.isEmpty() || s.contains(' ') || s.contains('/')) return null
        val hasPort = if (s.startsWith("[")) s.contains("]:") else s.count { it == ':' } == 1
        if (!hasPort) s = "$s:47830"
        return s
    }

    suspend fun pair(f: Found, code: String): String? = withContext(Dispatchers.IO) {
        try {
            val (st, body) = request("POST", "http://${f.addr}/pair", code.filter { it.isDigit() }, headers = mapOf("X-Client" to Saf.deviceName(app)))
            if (st == 403) return@withContext app.getString(R.string.pc_code_wrong)
            if (st == 503) return@withContext app.getString(R.string.pc_err_503)
            if (st != 200) return@withContext "$st"
            val o = JSONObject(body)
            val newId = o.optString("id", f.id)
            val name = o.optString("name", "").ifBlank { f.name }
            if (newId != id) clearMirror()
            sp().edit().putString("id", newId).putString("name", name).putString("token", o.getString("token"))
                .putString("addr", f.addr).remove("version").apply()
            state.value = State(paired = true, name = name, online = true)
            refresh()
            null
        } catch (e: Exception) { e.message ?: "Fehler" }
    }

    suspend fun unpair() {
        withContext(Dispatchers.IO) { clearMirror() }
        sp().edit().clear().apply()
        state.value = State()
        Library.version.value++
    }

    private suspend fun clearMirror() {
        for (r in Library.dao.knownRoots().filter { isPcRoot(it) }) Library.dao.drop(r)
        Library.dao.allPlaylists().filter { it.uid.startsWith("pc-") }.forEach { Library.dao.deletePlaylist(it.id) }
    }

    // ───────── Verbindung halten (solange die App offen ist) ─────────

    fun start(ctx: Context) {
        init(ctx)
        if (poll?.isActive == true) return
        poll = scope.launch {
            var misses = 0
            while (isActive) {
                if (token.isNotEmpty()) {
                    val ok = try {
                        val (st, body) = request("GET", "http://$addr/state", timeout = 3000)
                        if (st == 401) { state.value = state.value.copy(online = false, error = app.getString(R.string.pc_unpaired)); false }
                        else if (st == 200) {
                            val v = JSONObject(body).optString("version")
                            state.value = state.value.copy(online = true, error = null)
                            if (v != sp().getString("version", null) || dirty) refresh()
                            else syncHomeThrottled()
                            true
                        } else false
                    } catch (_: Exception) { false }
                    if (!ok) {
                        misses++
                        // neue Adresse? (DHCP) → im WLAN nach demselben PC suchen
                        val found = discover(1200).firstOrNull { it.id == id }
                        if (found != null) { sp().edit().putString("addr", found.addr).apply(); misses = 0 }
                        else state.value = state.value.copy(online = false)
                    } else misses = 0
                }
                delay(if (state.value.online) 4000L else 8000L)
            }
        }
    }

    fun stop() { poll?.cancel(); poll = null }

    private fun request(method: String, url: String, body: String? = null, timeout: Int = 8000, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeout
            c.readTimeout = maxOf(timeout, 30_000)
            c.useCaches = false
            if (token.isNotEmpty()) c.setRequestProperty("X-Token", token)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                val b = body.toByteArray()
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val st = c.responseCode
            val text = (if (st in 200..299) c.inputStream else c.errorStream)?.use { String(it.readBytes()) } ?: ""
            return st to text
        } finally { c.disconnect() }
    }

    /** Kurze Anfrage an den PC (Fernsteuerung); null = nicht gekoppelt/erreichbar */
    fun call(method: String, path: String, body: String? = null, timeout: Int = 3000): Pair<Int, String>? {
        if (token.isEmpty() || addr.isEmpty()) return null
        return try { request(method, "http://$addr$path", body, timeout) } catch (_: Exception) { null }
    }

    /** PC-Titel nach ihrer ID am PC (für die Übergabe der Warteschlange) */
    suspend fun tracksByTid(): Map<Long, Track> = withContext(Dispatchers.IO) {
        val m = HashMap<Long, Track>()
        for (r in roots()) for (t in Library.dao.tracksOfRoot(r.uri)) trackIdOf(t.uri)?.let { m[it] = t }
        m
    }

    private fun api(method: String, path: String, body: String? = null): String {
        val (st, text) = request(method, "http://$addr$path", body)
        if (st !in 200..299) throw IllegalStateException(text.ifBlank { "Fehler $st" })
        return text
    }

    // ───────── Bibliothek übernehmen ─────────

    /** Ganze Bibliothek vom PC holen und spiegeln */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (token.isEmpty()) return@withContext
        mirrorLock.withLock {
            state.value = state.value.copy(syncing = true)
            try {
                val o = JSONObject(api("GET", "/lib"))
                val pc = id
                val rootNames = o.getJSONArray("roots")
                val tracks = o.getJSONArray("tracks")
                val byRoot = HashMap<Int, ArrayList<Track>>()
                val uriById = HashMap<Long, String>()
                for (i in 0 until tracks.length()) {
                    val a = tracks.getJSONArray(i)
                    val tid = a.getLong(0)
                    val ri = a.getInt(1)
                    val dir = a.getString(2)
                    val file = a.getString(3)
                    val title = a.optString(4); val artist = a.optString(5)
                    val ms = a.optLong(7, 0L)
                    val size = a.optLong(8, 0L)
                    val uri = uriOf(pc, tid, file)
                    uriById[tid] = uri
                    // hat am PC ein eigenes Cover?
                    val hc = a.opt(9)
                    if (hc == true || hc == 1) withCover.add(tid) else withCover.remove(tid)
                    // „modified“ als Fingerabdruck der Angaben → Änderungen am PC werden übernommen
                    val stamp = (title + "\u0001" + artist + "\u0001" + ms + "\u0001" + size).hashCode().toLong()
                    byRoot.getOrPut(ri) { ArrayList() } += Track(
                        section = MUSIC, root = rootKey(pc, ri), dir = dir, name = file,
                        title = if (file.contains('.')) file.substringBeforeLast('.') else file,
                        uri = uri, size = size, modified = stamp, duration = if (ms > 0) ms else -1L,
                        tagTitle = title, tagArtist = artist, tagged = true,
                    )
                }
                val names = (0 until rootNames.length()).map { rootNames.getString(it) }
                // erst Umzüge zwischen Musikordnern übernehmen (IDs und Playlist-Plätze bleiben), dann je Ordner abgleichen
                Library.relocate(byRoot.values.flatten(), Library.dao.knownRoots().filter { isPcRoot(it) })
                for (i in names.indices) Library.mirrorRoot(rootKey(pc, i), byRoot[i] ?: emptyList())
                // weggefallene Musikordner am PC
                Library.dao.knownRoots().filter { isPcRoot(it) && (!it.startsWith("pc:$pc:") || (it.substringAfterLast(':').toIntOrNull() ?: 0) >= names.size) }
                    .forEach { Library.dao.drop(it) }
                // Playlisten und Stern: echte Zusammenführung (Handy ↔ PC)
                val smart = mergePlaylists(o.getJSONArray("playlists"), uriById, o.optLong("star", 0L))
                sp().edit()
                    .putString("roots", JSONArray(names).toString())
                    .putString("name", o.optString("name", state.value.name))
                    .putString("version", o.optString("version"))
                    .putStringSet("smart", smart)
                    .apply()
                state.value = state.value.copy(name = o.optString("name", state.value.name), loaded = true, online = true, error = null)
                Library.version.value++
                scope.launch { fetchCovers() }
                scope.launch { syncHome() }
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message)
            } finally {
                state.value = state.value.copy(syncing = false)
            }
        }
    }

    /** Titel, die am PC ein eingebettetes Cover haben (aus /lib) */
    private val withCover = java.util.Collections.synchronizedSet(HashSet<Long>())
    /** Titel, nach deren Cover in diesem Programmlauf schon gefragt wurde */
    private val askedCover = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Jeder Titel bekommt sein eigenes Cover (gleiche Bilder werden nur einmal gespeichert) */
    private suspend fun fetchCovers() = coroutineScope {
        if (!Prefs.get(app).covers) return@coroutineScope
        // früher bekam ein ganzer Ordner das Cover des ersten Titels → einmal zurücksetzen und je Titel neu holen
        if (!sp().getBoolean("coverPerTrack", false)) {
            for (r in roots()) Library.dao.tracksOfRoot(r.uri).filter { it.cover.isNotEmpty() }.map { it.id }.chunked(500).forEach { Library.dao.setCover(it, "") }
            sp().edit().putBoolean("coverPerTrack", true).apply()
        }
        val todo = ArrayList<Track>()
        for (r in roots()) for (t in Library.dao.tracksOfRoot(r.uri)) {
            val tid = trackIdOf(t.uri) ?: continue
            if (t.cover.isEmpty() && tid in withCover && askedCover.add(t.uri)) todo += t
        }
        if (todo.isEmpty()) return@coroutineScope
        val done = java.util.concurrent.atomic.AtomicInteger()
        val queue = java.util.concurrent.ConcurrentLinkedQueue(todo)
        // drei gleichzeitig – schnell, ohne den PC zu überlasten
        (1..3).map {
            launch(Dispatchers.IO) {
                while (true) {
                    val t = queue.poll() ?: break
                    val url = coverUrl(t.uri) ?: break
                    val bytes = try {
                        val c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 4000; c.readTimeout = 15000
                        try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
                    } catch (_: Exception) { null }
                    val h = bytes?.let { Covers.save(app, it) } ?: ""
                    if (h.isNotEmpty()) {
                        Library.dao.setCover(listOf(t.id), h)
                        if (done.incrementAndGet() % 40 == 0) Library.version.value++
                    }
                }
            }
        }.joinAll()
        if (done.get() > 0) Library.version.value++
    }

    // ───────── Playlisten & Stern: Zusammenführung Handy ↔ PC ─────────
    // Für jede Playlist merkt sich die App den zuletzt gemeinsamen Stand („Basis“). Beim Abgleich:
    // nur PC geändert → übernehmen · nur Handy geändert → an PC schicken · beide → zusammenführen
    // (Hinzugefügtes von beiden Seiten bleibt, Entferntes von beiden Seiten fällt weg, Reihenfolge vom PC).

    private data class Pl(val name: String, val items: List<Long>)

    private val base = HashMap<String, Pl>()
    private fun baseFile() = File(app.filesDir, "pc-base.json")
    @Volatile private var dirty = false

    private fun loadBase() {
        if (base.isNotEmpty()) return
        try {
            val o = JSONObject(baseFile().readText())
            o.keys().forEach { k ->
                val p = o.getJSONObject(k)
                val a = p.getJSONArray("items")
                base[k] = Pl(p.getString("name"), (0 until a.length()).map { a.getLong(it) })
            }
        } catch (_: Exception) { }
    }

    private fun saveBase() {
        try {
            val o = JSONObject()
            base.forEach { (k, v) -> o.put(k, JSONObject().put("name", v.name).put("items", JSONArray(v.items))) }
            baseFile().writeText(o.toString())
        } catch (_: Exception) { }
    }

    /** Drei-Wege-Zusammenführung einer Titelliste */
    private fun merge3(b: List<Long>, l: List<Long>, s: List<Long>): List<Long> {
        if (l == b) return s
        if (s == b) return l
        val removed = b.toSet() - l.toSet() + (b.toSet() - s.toSet())
        val result = s.filter { it !in removed }.toMutableList()
        val added = l.filter { it !in b.toSet() }
        for (x in added) {
            if (x in result) continue
            val i = l.indexOf(x)
            var prev = i - 1
            while (prev >= 0 && l[prev] !in result) prev--
            result.add(if (prev < 0) 0 else result.indexOf(l[prev]) + 1, x)
        }
        return result
    }

    private fun pcPlaylistId(uid: String) = if (uid.startsWith("pc-")) uid.substringAfterLast('-').toLongOrNull() else null

    /** Playlist der Bibliothek am PC (oder am Handy neu angelegt, noch nicht übertragen) */
    fun isPcPlaylist(uid: String?) = uid != null && (uid.startsWith("pc-") || uid.startsWith("pcnew-"))
    fun isSmart(uid: String?) = uid != null && ::app.isInitialized && (sp().getStringSet("smart", emptySet()) ?: emptySet()).contains(uid)

    /** Etwas an Playlisten/Stern geändert → beim nächsten Kontakt (sofort, wenn verbunden) abgleichen */
    fun changed() {
        dirty = true
        if (state.value.online) scope.launch { refresh() }
    }

    private suspend fun localItems(lid: Long): List<Long> = Library.playlistTracks(lid).mapNotNull { trackIdOf(it.track.uri) }

    private suspend fun setLocal(lid: Long, p: Pl, uriById: Map<Long, String>) {
        val ids = p.items.mapNotNull { uriById[it]?.let { u -> Library.dao.byUri(u)?.id } }
        val cur = Library.dao.playlist(lid)
        if (cur != null && cur.name != p.name) Library.dao.renamePlaylist(lid, p.name)
        Library.dao.replaceItems(lid, ids)
    }

    /** → Menge der smarten Playlisten (uids) */
    private suspend fun mergePlaylists(pls: JSONArray, uriById: Map<Long, String>, starId: Long): Set<String> {
        loadBase()
        dirty = false
        val pc = id
        val smart = HashSet<String>()
        val server = LinkedHashMap<String, Pl>()
        for (i in 0 until pls.length()) {
            val p = pls.getJSONObject(i)
            val uid = "pc-$pc-${p.getLong("id")}"
            if (p.optBoolean("smart")) smart += uid
            val a = p.getJSONArray("items")
            server[uid] = Pl(p.getString("name"), (0 until a.length()).map { a.getLong(it) })
        }
        val starUid = if (starId > 0) "pc-$pc-$starId" else null
        val locals = Library.dao.allPlaylists().filter { it.uid.startsWith("pc-$pc-") }.associateBy { it.uid }
        for ((uid, sv) in server) {
            val loc = locals[uid]
            val b = base[uid]
            if (loc == null) {
                if (b != null && sv == b && uid !in smart && uid != starUid) {
                    // am Handy gelöscht, am PC unverändert → auch am PC löschen
                    try { api("DELETE", "/pl/${pcPlaylistId(uid)}") } catch (_: Exception) { }
                    base.remove(uid)
                    continue
                }
                val lid = Library.dao.insertPlaylist(Playlist(name = sv.name, created = System.currentTimeMillis(), uid = uid))
                setLocal(lid, sv, uriById)
                base[uid] = sv
                continue
            }
            val lv = Pl(loc.name, localItems(loc.id))
            val bv = b ?: sv // noch nie abgeglichen: PC-Stand als Basis
            val merged = if (uid in smart) Pl(lv.name.takeIf { it != bv.name } ?: sv.name, sv.items)
            else Pl(if (lv.name != bv.name) lv.name else sv.name, merge3(bv.items, lv.items, sv.items))
            if (merged != sv) {
                try {
                    val body = JSONObject().put("name", merged.name).apply { if (uid !in smart) put("items", JSONArray(merged.items)) }
                    api("POST", "/pl/${pcPlaylistId(uid)}", body.toString())
                } catch (_: Exception) { }
            }
            if (merged != lv) setLocal(loc.id, merged, uriById)
            base[uid] = merged
        }
        // am PC gelöscht
        for ((uid, loc) in locals) {
            if (uid in server) continue
            val b = base.remove(uid)
            val lv = Pl(loc.name, localItems(loc.id))
            if (b != null && lv != b) {
                // am Handy inzwischen geändert → am PC neu anlegen statt verlieren
                try {
                    val o = JSONObject(api("POST", "/pl", JSONObject().put("name", lv.name).put("items", JSONArray(lv.items)).toString()))
                    val nuid = "pc-$pc-${o.getLong("id")}"
                    Library.dao.deletePlaylist(loc.id)
                    val lid = Library.dao.insertPlaylist(Playlist(name = lv.name, created = System.currentTimeMillis(), uid = nuid))
                    setLocal(lid, lv, uriById)
                    base[nuid] = lv
                } catch (_: Exception) { }
            } else Library.dao.deletePlaylist(loc.id)
        }
        // am Handy neu angelegt (auch ohne Verbindung)
        for (loc in Library.dao.allPlaylists().filter { it.uid.startsWith("pcnew-") }) {
            val lv = Pl(loc.name, localItems(loc.id))
            try {
                val o = JSONObject(api("POST", "/pl", JSONObject().put("name", lv.name).put("items", JSONArray(lv.items)).toString()))
                val nuid = "pc-$pc-${o.getLong("id")}"
                Library.dao.deletePlaylist(loc.id)
                val lid = Library.dao.insertPlaylist(Playlist(name = lv.name, created = System.currentTimeMillis(), uid = nuid))
                setLocal(lid, lv, uriById)
                base[nuid] = lv
            } catch (_: Exception) { }
        }
        saveBase()
        // Stern = Stern der Bibliothek
        if (starUid != null) Library.dao.playlistByUid(starUid)?.let { Prefs.get(app).starId = it.id; Library.stars.value++ }
        migrateLegacy(uriById)
        return smart
    }

    /** Einmalig: bisherige Handy-Playlisten und Sterne in die Bibliothek übernehmen */
    private suspend fun migrateLegacy(uriById: Map<Long, String>) {
        if (sp().getBoolean("migrated", false)) return
        val legacy = Library.dao.allPlaylists().filter { !isPcPlaylist(it.uid) && !it.uid.startsWith("off-") }
        val star = Prefs.get(app).starId
        val uriToPc = uriById.entries.associate { it.value to it.key }
        for (p in legacy) {
            val tracks = Library.playlistTracks(p.id).map { it.track }
            // PC-Titel direkt; Handy-Titel über Dateiname + Größe / Interpret + Titel suchen
            val ids = ArrayList<Long>()
            val unknown = tracks.filter { uriToPc[it.uri] == null }
            val found = HashMap<String, Long>()
            try {
                for (chunk in unknown.chunked(300)) {
                    val arr = JSONArray()
                    chunk.forEach { t -> arr.put(JSONObject().put("k", t.uri).put("name", t.name).put("size", t.size).put("title", t.tagTitle).put("artist", t.tagArtist).put("ms", t.duration.coerceAtLeast(0))) }
                    val o = JSONObject(api("POST", "/have", arr.toString()))
                    o.optJSONObject("found")?.let { f -> f.keys().forEach { k -> found[k] = f.getLong(k) } }
                }
            } catch (_: Exception) { return } // ohne Verbindung später erneut
            for (t in tracks) (uriToPc[t.uri] ?: found[t.uri])?.let { ids += it }
            if (p.id == star) {
                val s = Library.dao.allPlaylists().firstOrNull { it.uid.startsWith("pc-") && Prefs.get(app).starId == it.id }
                if (s != null) {
                    val cur = localItems(s.id)
                    setLocal(s.id, Pl(s.name, cur + ids.filter { it !in cur }), uriById)
                    dirty = true
                }
            } else {
                val lid = Library.dao.insertPlaylist(Playlist(name = p.name, created = System.currentTimeMillis(), uid = "pcnew-" + java.util.UUID.randomUUID().toString().take(8)))
                setLocal(lid, Pl(p.name, ids), uriById)
                dirty = true
            }
            Library.dao.deletePlaylist(p.id)
        }
        sp().edit().putBoolean("migrated", true).apply()
        Library.version.value++
    }

    // ───────── Hauptordner auf dem Handy ─────────
    // Alle Dateien am Handy liegen im Hauptordner, aufgebaut wie die Bibliothek: <Musikquelle>/<Ordner>/<Datei>.
    // Neu hineingelegte Musik wird hochgeladen; was am PC umsortiert wird, zieht am Handy mit;
    // „offline verfügbar“ lädt dorthin; am PC Gelöschtes verschwindet auch hier.

    private val files = HashMap<Long, String>()   // PC-Titel → Pfad im Hauptordner
    private val docs = HashMap<Long, String>()    // PC-Titel → Dokument-Adresse (zum Abspielen)
    private val own = HashSet<Long>()             // vom Handy gekommen → bleibt immer offline
    private fun homeFile() = File(app.filesDir, "pc-home.json")

    fun home(): String? = if (::app.isInitialized) sp().getString("home", null) else null

    fun setHome(uri: String) {
        sp().edit().putString("home", uri).apply()
        files.clear(); docs.clear(); saveHomeMap()
        state.value = state.value.copy()
        Library.version.value++
        scope.launch { syncHome(force = true) }
    }

    private fun loadOffline() {
        try {
            val o = JSONObject(homeFile().readText())
            o.optJSONObject("files")?.let { f -> f.keys().forEach { k -> k.toLongOrNull()?.let { files[it] = f.getJSONArray(k).getString(0); docs[it] = f.getJSONArray(k).getString(1) } } }
            o.optJSONArray("own")?.let { a -> for (i in 0 until a.length()) own += a.getLong(i) }
        } catch (_: Exception) { }
        // frühere Ablage im App-Speicher aufräumen
        try { File(app.getExternalFilesDir(null) ?: app.filesDir, "offline").deleteRecursively(); File(app.filesDir, "pc-offline.json").delete() } catch (_: Exception) { }
    }

    private fun saveHomeMap() {
        try {
            val f = JSONObject()
            files.forEach { (k, v) -> f.put(k.toString(), JSONArray().put(v).put(docs[k] ?: "")) }
            homeFile().writeText(JSONObject().put("files", f).put("own", JSONArray(own.toList())).toString())
        } catch (_: Exception) { }
    }

    /** „Wurzel|Ordner“ (inkl. übergeordneter Ordner), in denen Titel auf dem Handy liegen */
    suspend fun offlineDirs(): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        for (r in roots()) for (t in Library.dao.tracksOfRoot(r.uri)) {
            if (!isOffline(t.uri)) continue
            var d = t.dir
            while (true) { out += r.uri + "|" + d; if (d.isEmpty()) break; d = if (d.contains('/')) d.substringBeforeLast('/') else "" }
        }
        out
    }

    fun isOffline(uri: String) = trackIdOf(uri)?.let { docs.containsKey(it) } == true

    /** „Wurzel|Ordner“, in denen ALLE Titel (inkl. Unterordner) auf dem Handy liegen */
    suspend fun fullyOfflineDirs(rootUris: Collection<String>): Set<String> = withContext(Dispatchers.IO) {
        val total = HashMap<String, Int>()
        val off = HashMap<String, Int>()
        for (r in rootUris) for (t in Library.dao.tracksOfRoot(r)) {
            val o = isOffline(t.uri)
            var d = t.dir
            while (true) {
                val k = r + "|" + d
                total[k] = (total[k] ?: 0) + 1
                if (o) off[k] = (off[k] ?: 0) + 1
                if (d.isEmpty()) break
                d = if (d.contains('/')) d.substringBeforeLast('/') else ""
            }
        }
        total.filter { (k, n) -> n > 0 && off[k] == n }.keys
    }

    private fun pins(): Set<String> = sp().getStringSet("pins", emptySet()) ?: emptySet()
    fun isPinned(key: String) = key in pins()
    fun folderPin(root: String, path: String) = "d|$root|$path"
    fun playlistPin(uid: String) = "l|$uid"

    /** Von der Activity gesetzt: Ordnerauswahl für den Hauptordner öffnen */
    var pickHome: (() -> Unit)? = null

    fun setPinned(key: String, on: Boolean) {
        // Ohne Hauptordner gibt es keinen Platz für Offline-Kopien → direkt danach fragen
        if (on && home() == null) {
            offline.value = OfflineStatus(0, 0, null, true, app.getString(R.string.pc_home_needed))
            pickHome?.invoke()
        }
        val s = pins().toMutableSet()
        if (on) s += key else s -= key
        sp().edit().putStringSet("pins", s).apply()
        Library.version.value++
        scope.launch { syncHome(force = true) }
    }

    data class UpStatus(val done: Int, val total: Int, val finished: Boolean)
    val upload = MutableStateFlow<UpStatus?>(null)
    private var lastHome = 0L

    private suspend fun syncHomeThrottled() {
        if (System.currentTimeMillis() - lastHome > 60_000L) syncHome()
    }

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "_" }

    /** Wo ein PC-Titel im Hauptordner liegen soll */
    private fun expectedRel(t: Track, names: Map<String, String>): String =
        (listOf(clean(names[t.root] ?: "Musik")) + t.dir.split('/').filter { it.isNotEmpty() }.map(::clean) + clean(t.name)).joinToString("/")

    suspend fun syncHome(force: Boolean = false) = offlineLock.withLock {
        val homeUri = home()?.let { Uri.parse(it) } ?: return@withLock
        if (token.isEmpty() || (!force && !state.value.online)) return@withLock
        lastHome = System.currentTimeMillis()
        val ctx = app
        val names = roots().associate { it.uri to it.name }
        // PC-Titel nach ID
        val pcTracks = HashMap<Long, Track>()
        for (r in roots()) for (t in Library.dao.tracksOfRoot(r.uri)) trackIdOf(t.uri)?.let { pcTracks[it] = t }
        if (pcTracks.isEmpty() && !state.value.loaded) return@withLock
        val local = try { Saf.list(ctx, homeUri) } catch (_: Exception) { return@withLock }
        val audio = local.filter { !it.dir && Library.isAudio(it.name, it.mime) && !it.name.endsWith(".part") }
        val relToId = files.entries.associate { it.value.lowercase() to it.key }
        val present = HashSet<Long>()
        var changed = false
        // 1. Bekannte Dateien: am PC gelöscht → weg; umsortiert → mitziehen
        val unknown = ArrayList<Saf.Entry>()
        var toUpload: List<Saf.Entry> = emptyList()
        for (f in audio) {
            val tid = relToId[f.rel.lowercase()]
            if (tid == null) { unknown += f; continue }
            val t = pcTracks[tid]
            if (t == null) {
                if (state.value.online && state.value.loaded) {
                    Saf.delete(ctx, homeUri, f.rel); Saf.delete(ctx, homeUri, lrcOf(f.rel))
                    files.remove(tid); docs.remove(tid); own.remove(tid); changed = true
                }
                continue
            }
            present += tid
            val want = expectedRel(t, names)
            if (!want.equals(f.rel, ignoreCase = false)) {
                try {
                    docs[tid] = Saf.move(ctx, homeUri, f.rel, want)
                    files[tid] = want
                    try { Saf.move(ctx, homeUri, lrcOf(f.rel), lrcOf(want)) } catch (_: Exception) { }
                    changed = true
                } catch (_: Exception) { docs[tid] = f.uri }
            } else docs[tid] = f.uri
        }
        // 2. Neue Dateien: am PC vorhanden? sonst hochladen (→ eigene Musikquelle „<Gerät>“ am PC)
        if (unknown.isNotEmpty() && state.value.online) {
            val found = HashMap<String, Long>()
            val missing = ArrayList<Saf.Entry>()
            try {
                for (chunk in unknown.chunked(300)) {
                    val arr = JSONArray()
                    chunk.forEach { f -> arr.put(JSONObject().put("k", f.rel).put("name", f.name).put("size", f.size)) }
                    val o = JSONObject(api("POST", "/have", arr.toString()))
                    o.optJSONObject("found")?.let { m -> m.keys().forEach { k -> found[k] = m.getLong(k) } }
                    val m = o.optJSONArray("missing") ?: JSONArray()
                    for (i in 0 until m.length()) chunk.firstOrNull { it.rel == m.getString(i) }?.let { missing += it }
                }
            } catch (_: Exception) { }
            for (f in unknown) found[f.rel]?.let { tid -> files[tid] = f.rel; docs[tid] = f.uri; own += tid; present += tid; changed = true }
            toUpload = missing
        }
        // 3. Gewünscht: offline markierte Ordner/Playlisten + alles, was vom Handy kam
        val want = HashMap<Long, Track>()
        for (k in pins()) when {
            k.startsWith("d|") -> {
                val root = k.removePrefix("d|").substringBefore('|')
                val path = k.removePrefix("d|$root|")
                Library.dao.subtree(root, path).forEach { t -> trackIdOf(t.uri)?.let { want[it] = t } }
            }
            k.startsWith("l|") -> Library.dao.playlistByUid(k.removePrefix("l|"))?.let { p -> Library.playlistTracks(p.id).forEach { pt -> trackIdOf(pt.track.uri)?.let { want[it] = pt.track } } }
        }
        for (tid in own) pcTracks[tid]?.let { want[tid] = it }
        // 4. Fehlende herunterladen (vor dem Hochladen, damit „offline verfügbar“ nicht hinter langen Uploads wartet)
        val todo = want.filter { (tid, _) -> tid !in present }
        var done = 0
        var failed = 0
        var why: String? = null
        if (state.value.online) {
            offline.value = OfflineStatus(0, todo.size, null, todo.isEmpty())
            for ((tid, t) in todo) {
                val url = httpUrl(t.uri)
                if (url == null) { failed++; why = why ?: app.getString(R.string.pc_err_noaddr); done++; continue }
                offline.value = OfflineStatus(done, todo.size, Library.label(t), false)
                try {
                    val rel = expectedRel(t, names)
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 60_000
                    try {
                        val code = c.responseCode
                        if (code != 200) {
                            why = why ?: when (code) {
                                404 -> app.getString(R.string.pc_err_404)
                                401 -> app.getString(R.string.pc_err_401)
                                503 -> app.getString(R.string.pc_err_503)
                                else -> "HTTP $code"
                            }
                            throw IllegalStateException("$code")
                        }
                        docs[tid] = c.inputStream.use { Saf.saveFile(ctx, homeUri, rel, it) }
                        files[tid] = rel
                        present += tid
                        changed = true
                    } finally { c.disconnect() }
                    try {
                        val (st, body) = request("GET", "http://$addr/l/$tid", timeout = 4000)
                        if (st == 200 && body.isNotBlank()) Saf.saveFile(ctx, homeUri, lrcOf(rel), body.byteInputStream())
                    } catch (_: Exception) { }
                } catch (e: Exception) {
                    failed++
                    if (why == null) why = if (e is IllegalStateException) e.message else app.getString(R.string.pc_err_save, e.message ?: e.javaClass.simpleName)
                }
                done++
                if (done % 10 == 0) saveHomeMap()
            }
            offline.value = OfflineStatus(done, todo.size, null, true, if (failed > 0) app.getString(R.string.pc_errors_why_fmt, failed, why ?: "") else null)
            saveHomeMap()
        }
        // 4b. Neue Musik vom Handy an den PC schicken
        if (toUpload.isNotEmpty() && state.value.online) {
            val missing = toUpload
            run {
                upload.value = UpStatus(0, missing.size, false)
                var n = 0
                for (f in missing) {
                    if (!state.value.online) break
                    try {
                        uploadFile(f.uri, f.size, f.rel)?.let { tid -> files[tid] = f.rel; docs[tid] = f.uri; own += tid; present += tid; changed = true }
                    } catch (_: Exception) { }
                    n++
                    upload.value = UpStatus(n, missing.size, false)
                    if (n % 10 == 0) saveHomeMap()
                }
                upload.value = UpStatus(n, missing.size, true)
                dirty = true // neue Titel → beim nächsten Abgleich an ihren Platz ziehen
            }
        }
        // 5. Nicht mehr gewünschte Kopien entfernen (nur vom PC geholte; Eigenes bleibt)
        for (tid in files.keys.toList()) {
            if (tid in want || tid in own) continue
            files[tid]?.let { rel -> Saf.delete(ctx, homeUri, rel); Saf.delete(ctx, homeUri, lrcOf(rel)) }
            files.remove(tid); docs.remove(tid); changed = true
        }
        // Verwaiste Zuordnungen (Datei von Hand gelöscht)
        for (tid in files.keys.toList()) if (tid !in present && tid !in todo.keys) { files.remove(tid); docs.remove(tid); own.remove(tid); changed = true }
        if (changed) Saf.removeEmptyDirs(ctx, homeUri)
        saveHomeMap()
        if (!state.value.online && pins().isNotEmpty() && todo.isNotEmpty()) offline.value = OfflineStatus(0, todo.size, null, true, app.getString(R.string.pc_err_offline))
        if (changed) Library.version.value++
    }

    private fun lrcOf(rel: String) = rel.substringBeforeLast('.') + ".lrc"

    /** Eine Datei an Minimal Library schicken → Titel-ID am PC */
    private fun uploadFile(doc: String, size: Long, rel: String): Long? {
        val enc = rel.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val c = URL("http://$addr/up/$enc").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "PUT"
            c.connectTimeout = 5000
            c.readTimeout = 120_000
            c.setRequestProperty("X-Token", token)
            c.doOutput = true
            if (size > 0) c.setFixedLengthStreamingMode(size) else c.setChunkedStreamingMode(256 * 1024)
            app.contentResolver.openInputStream(Uri.parse(doc))?.use { inp -> c.outputStream.use { inp.copyTo(it, 256 * 1024) } } ?: return null
            if (c.responseCode != 200) return null
            return JSONObject(c.inputStream.use { String(it.readBytes()) }).optLong("id").takeIf { it > 0 }
        } finally { c.disconnect() }
    }

    /** Songtext eines PC-Titels: Offline-Kopie (.lrc daneben), sonst von Minimal Library */
    fun lyrics(ctx: Context, uri: String): String? {
        val tid = trackIdOf(uri) ?: return null
        val h = home()
        if (h != null) files[tid]?.let { rel -> Saf.read(ctx, Uri.parse(h), lrcOf(rel))?.let { return it } }
        if (addr.isEmpty()) return null
        return try {
            val (st, body) = request("GET", "http://$addr/l/$tid", timeout = 4000)
            if (st == 200) body else null
        } catch (_: Exception) { null }
    }

    /** Datei eines Titels lesen – lokal oder vom PC (für den Export) */
    fun open(ctx: Context, t: Track): InputStream? {
        if (!isPc(t.uri)) return ctx.contentResolver.openInputStream(Uri.parse(t.uri))
        val u = realUri(Uri.parse(t.uri))
        if (u.scheme == "content") return ctx.contentResolver.openInputStream(u)
        if (u.scheme == "file") return File(u.path ?: return null).inputStream()
        val c = URL(u.toString()).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 60_000
        if (c.responseCode != 200) { c.disconnect(); return null }
        return c.inputStream
    }
}
