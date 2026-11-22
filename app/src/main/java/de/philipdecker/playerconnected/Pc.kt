package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.DocumentsContract
import androidx.media3.datasource.DataSpec
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
 * oder Playlisten offline verfügbar (echte Dateien im Empfangsordner – erscheinen dann auch unter „Musik“).
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
        if (tid != null) local[tid]?.let { return Uri.parse(it) }
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
    suspend fun pair(f: Found, code: String): String? = withContext(Dispatchers.IO) {
        try {
            val (st, body) = request("POST", "http://${f.addr}/pair", code.filter { it.isDigit() }, headers = mapOf("X-Client" to Receive.deviceName(app)))
            if (st == 403) return@withContext app.getString(R.string.pc_code_wrong)
            if (st != 200) return@withContext "$st"
            val o = JSONObject(body)
            if (f.id != id) clearMirror()
            sp().edit().putString("id", o.optString("id", f.id)).putString("name", f.name).putString("token", o.getString("token"))
                .putString("addr", f.addr).remove("version").apply()
            state.value = State(paired = true, name = f.name, online = true)
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
                            if (v != sp().getString("version", null)) refresh()
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
                for (i in names.indices) Library.mirrorRoot(rootKey(pc, i), byRoot[i] ?: emptyList())
                // weggefallene Musikordner am PC
                Library.dao.knownRoots().filter { isPcRoot(it) && (!it.startsWith("pc:$pc:") || (it.substringAfterLast(':').toIntOrNull() ?: 0) >= names.size) }
                    .forEach { Library.dao.drop(it) }
                // Playlisten
                val pls = o.getJSONArray("playlists")
                val keep = HashSet<String>()
                val smart = HashSet<String>()
                for (i in 0 until pls.length()) {
                    val p = pls.getJSONObject(i)
                    val uid = "pc-$pc-${p.getLong("id")}"
                    keep += uid
                    if (p.optBoolean("smart")) smart += uid
                    val items = p.getJSONArray("items")
                    val ids = (0 until items.length()).mapNotNull { k -> uriById[items.getLong(k)]?.let { Library.dao.byUri(it)?.id } }
                    val existing = Library.dao.playlistByUid(uid)
                    val name = p.getString("name")
                    val lid = existing?.id ?: Library.dao.insertPlaylist(Playlist(name = name, created = System.currentTimeMillis(), uid = uid))
                    if (existing != null && existing.name != name) Library.dao.renamePlaylist(lid, name)
                    Library.dao.replaceItems(lid, ids)
                }
                Library.dao.allPlaylists().filter { it.uid.startsWith("pc-") && it.uid !in keep }.forEach { Library.dao.deletePlaylist(it.id) }
                sp().edit()
                    .putString("roots", JSONArray(names).toString())
                    .putString("name", o.optString("name", state.value.name))
                    .putString("version", o.optString("version"))
                    .putStringSet("smart", smart)
                    .apply()
                state.value = state.value.copy(name = o.optString("name", state.value.name), loaded = true, online = true, error = null)
                Library.version.value++
                scope.launch { fetchCovers() }
                scope.launch { syncOffline() }
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message)
            } finally {
                state.value = state.value.copy(syncing = false)
            }
        }
    }

    /** Ordner, für die in diesem Programmlauf schon nach einem Cover gefragt wurde */
    private val askedCover = HashSet<String>()

    /** Cover je Ordner einmal holen (spart Datenvolumen; Alben liegen meist in eigenen Ordnern) */
    private suspend fun fetchCovers() {
        if (!Prefs.get(app).covers) return
        var any = false
        for (r in roots()) {
            val missing = Library.dao.tracksOfRoot(r.uri).filter { it.cover.isEmpty() }
            for ((dir, group) in missing.groupBy { it.dir }) {
                if (!askedCover.add(r.uri + "|" + dir)) continue
                val url = coverUrl(group.first().uri) ?: return
                val bytes = try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 4000; c.readTimeout = 15000
                    try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
                } catch (_: Exception) { null }
                val h = bytes?.let { Covers.save(app, it) } ?: ""
                if (h.isNotEmpty()) { group.map { it.id }.chunked(500).forEach { Library.dao.setCover(it, h) }; any = true }
            }
        }
        if (any) Library.version.value++
    }

    // ───────── Änderungen zurück an den PC ─────────

    private fun pcPlaylistId(uid: String) = if (uid.startsWith("pc-")) uid.substringAfterLast('-').toLongOrNull() else null

    fun isPcPlaylist(uid: String?) = uid?.startsWith("pc-") == true
    fun isSmart(uid: String?) = uid != null && ::app.isInitialized && (sp().getStringSet("smart", emptySet()) ?: emptySet()).contains(uid)

    /** Name und Inhalt einer PC-Playlist an Minimal Library schicken (nach jeder Änderung am Handy) */
    fun push(localId: Long) {
        scope.launch {
            val p = Library.dao.playlist(localId) ?: return@launch
            val pid = pcPlaylistId(p.uid) ?: return@launch
            try {
                val ids = Library.playlistTracks(localId).mapNotNull { trackIdOf(it.track.uri) }
                val body = JSONObject().put("name", p.name).apply { if (!isSmart(p.uid)) put("items", JSONArray(ids)) }
                api("POST", "/pl/$pid", body.toString())
                sp().edit().remove("version").apply() // beim nächsten Abgleich frisch laden
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message)
                refresh() // Stand vom PC wiederherstellen
            }
        }
    }

    /** PC-Playlist löschen (vor dem lokalen Löschen aufrufen) */
    suspend fun deleteOnPc(localId: Long) = withContext(Dispatchers.IO) {
        val p = Library.dao.playlist(localId) ?: return@withContext
        val pid = pcPlaylistId(p.uid) ?: return@withContext
        try { api("DELETE", "/pl/$pid") } catch (e: Exception) { state.value = state.value.copy(error = e.message) }
    }

    /** Neue Playlist am PC anlegen → lokale ID */
    suspend fun createPlaylist(name: String): Long? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(api("POST", "/pl", JSONObject().put("name", name.trim()).toString()))
            val uid = "pc-$id-${o.getLong("id")}"
            val lid = Library.dao.insertPlaylist(Playlist(name = name.trim(), created = System.currentTimeMillis(), uid = uid))
            Library.version.value++
            lid
        } catch (e: Exception) { state.value = state.value.copy(error = e.message); null }
    }

    // ───────── Offline verfügbar ─────────
    // Gemerkte Auswahl („d|<Wurzel>|<Pfad>“ für Ordner, „l|<uid>“ für Playlisten) und welche PC-Titel als Datei vorliegen.

    private val local = HashMap<Long, String>()
    private fun offlineFile() = File(app.filesDir, "pc-offline.json")

    private fun loadOffline() {
        try {
            val o = JSONObject(offlineFile().readText())
            o.keys().forEach { k -> k.toLongOrNull()?.let { local[it] = o.getString(k) } }
        } catch (_: Exception) { }
    }

    private fun saveOffline() {
        try { offlineFile().writeText(JSONObject(local.mapKeys { it.key.toString() } as Map<*, *>).toString()) } catch (_: Exception) { }
    }

    /** „Wurzel|Ordner“ (inkl. übergeordneter Ordner), in denen offline gespeicherte Titel liegen */
    suspend fun offlineDirs(): Set<String> = withContext(Dispatchers.IO) {
        val out = HashSet<String>()
        for (r in roots()) for (t in Library.dao.tracksOfRoot(r.uri)) {
            if (!isOffline(t.uri)) continue
            var d = t.dir
            while (true) { out += r.uri + "|" + d; if (d.isEmpty()) break; d = if (d.contains('/')) d.substringBeforeLast('/') else "" }
        }
        out
    }

    fun isOffline(uri: String) = trackIdOf(uri)?.let { local.containsKey(it) } == true

    private fun pins(): Set<String> = sp().getStringSet("pins", emptySet()) ?: emptySet()
    fun isPinned(key: String) = key in pins()
    fun folderPin(root: String, path: String) = "d|$root|$path"
    fun playlistPin(uid: String) = "l|$uid"

    fun setPinned(key: String, on: Boolean) {
        val s = pins().toMutableSet()
        if (on) s += key else s -= key
        sp().edit().putStringSet("pins", s).apply()
        Library.version.value++
        scope.launch { syncOffline() }
    }

    /** Ausgewählte Ordner/Playlisten herunterladen, nicht mehr benötigte Kopien löschen */
    suspend fun syncOffline() = offlineLock.withLock {
        val tree = Receive.folder(app)?.let { Uri.parse(it) }
        val pinned = pins()
        if (pinned.isEmpty() && local.isEmpty()) return@withLock
        if (tree == null) {
            if (pinned.isNotEmpty()) offline.value = OfflineStatus(0, 0, null, true, app.getString(R.string.pc_need_folder))
            return@withLock
        }
        val names = roots().associate { it.uri to it.name }
        // gewünschte Titel
        val want = LinkedHashMap<Long, Track>()
        val lists = ArrayList<Library.ReceivedPlaylist>()
        for (k in pinned) {
            when {
                k.startsWith("d|") -> {
                    val root = k.removePrefix("d|").substringBefore('|')
                    val path = k.removePrefix("d|$root|")
                    Library.dao.subtree(root, path).forEach { t -> trackIdOf(t.uri)?.let { want[it] = t } }
                }
                k.startsWith("l|") -> {
                    val uid = k.removePrefix("l|")
                    val p = Library.dao.playlistByUid(uid) ?: continue
                    val tr = Library.playlistTracks(p.id).map { it.track }
                    tr.forEach { t -> trackIdOf(t.uri)?.let { want[it] = t } }
                    lists += Library.ReceivedPlaylist("off-$uid", p.name, tr.map { relFor(it, names) })
                }
            }
        }
        val todo = want.filter { (tid, _) -> !local.containsKey(tid) || !exists(Uri.parse(local[tid])) }
        var done = 0
        var failed = 0
        var changed = false
        offline.value = OfflineStatus(0, todo.size, null, todo.isEmpty())
        for ((tid, t) in todo) {
            val url = httpUrl(t.uri)
            if (url == null || !state.value.online) { failed++; continue }
            offline.value = OfflineStatus(done, todo.size, Library.label(t), false)
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 60_000
                try {
                    if (c.responseCode != 200) throw IllegalStateException("${c.responseCode}")
                    val doc = c.inputStream.use { Receive.saveFile(app, tree, relFor(t, names), it) }
                    local[tid] = doc
                    changed = true
                } finally { c.disconnect() }
            } catch (_: Exception) { failed++ }
            done++
            if (done % 10 == 0) saveOffline()
        }
        // nicht mehr gewünschte Kopien entfernen
        val drop = local.keys.filter { it !in want.keys }
        for (tid in drop) {
            local.remove(tid)?.let { u -> try { DocumentsContract.deleteDocument(app.contentResolver, Uri.parse(u)) } catch (_: Exception) { } }
            changed = true
        }
        if (drop.isNotEmpty()) Receive.removeEmptyDirs(app, tree, "PC")
        saveOffline()
        if (changed || lists.isNotEmpty()) {
            try { Library.receiveDone(app, tree.toString(), Library.Received("off-pc-", lists)) } catch (_: Exception) { }
        }
        offline.value = OfflineStatus(done, todo.size, null, true, if (failed > 0) app.getString(R.string.pc_errors_fmt, failed) else null)
    }

    /** Ablage der Offline-Kopie im Empfangsordner: „PC/<Musikordner>/<Unterordner>/<Datei>“ */
    private fun relFor(t: Track, names: Map<String, String>): String {
        val root = (names[t.root] ?: "Musik").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return listOf("PC", root, t.dir, t.name).filter { it.isNotEmpty() }.joinToString("/")
    }

    private fun exists(u: Uri): Boolean = try {
        app.contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    } catch (_: Exception) { false }

    /** Datei eines Titels lesen – lokal oder vom PC (für den Export) */
    fun open(ctx: Context, t: Track): InputStream? {
        if (!isPc(t.uri)) return ctx.contentResolver.openInputStream(Uri.parse(t.uri))
        val u = realUri(Uri.parse(t.uri))
        if (u.scheme == "content") return ctx.contentResolver.openInputStream(u)
        val c = URL(u.toString()).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 60_000
        if (c.responseCode != 200) { c.disconnect(); return null }
        return c.inputStream
    }
}
