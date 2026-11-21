package de.philipdecker.playerconnected

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Abgleich zwischen „Minimal Player“ und „Minimal Player Connected“: gleiche Ordner und Playlisten in beiden Apps.
 *
 * - Jede App hält ihren vollständigen Stand selbst (Datenbank + sync.json). Wird eine App deinstalliert,
 *   bleibt in der anderen alles erhalten.
 * - Sind beide installiert, schickt die App mit der Änderung ihren Stand direkt an die andere
 *   (ContentProvider, nur auf dem Gerät, kein Netz). Neuerer Stand gewinnt.
 * - Ordnerzugriff vergibt Android pro App: Ordner aus der anderen App erscheinen als „Zugriff erlauben“.
 * - Playlist-Titel aus Ordnern ohne Zugriff bleiben erhalten (werden mitgeführt) und tauchen auf,
 *   sobald der Ordner freigegeben ist.
 *
 * Diese Datei ist in beiden Apps identisch (bis auf die package-Zeile).
 */
object Sync {
    private const val PLAYER = "de.philipdecker.player"
    private const val CONNECTED = "de.philipdecker.playerconnected"

    fun sibling(ctx: Context) = if (ctx.packageName == PLAYER) CONNECTED else PLAYER
    fun siblingName(ctx: Context) = if (ctx.packageName == PLAYER) "Minimal Player Connected" else "Minimal Player"

    data class PL(val uid: String, val name: String, val star: Boolean, val tracks: List<String>, val pending: Set<String> = emptySet())
    data class State(val stamp: Long, val roots: List<List<Root>>, val playlists: List<PL>) {
        fun sameContent(o: State) = roots == o.roots &&
            playlists.map { listOf(it.uid, it.name, it.star, it.tracks) } == o.playlists.map { listOf(it.uid, it.name, it.star, it.tracks) }
        val empty get() = roots.all { it.isEmpty() } && playlists.isEmpty()
    }

    private lateinit var app: Context
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var canon = State(0L, listOf(emptyList(), emptyList()), emptyList())
    @Volatile private var started = false

    private fun file() = File(app.filesDir, "sync.json")

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        canon = try { file().takeIf { it.exists() }?.readText()?.let { parse(it, withPending = true) } } catch (_: Exception) { null } ?: canon
    }

    /** Beim App-Start: Stand der anderen App holen, danach jede Änderung (entprellt) weitergeben */
    fun start(ctx: Context) {
        init(ctx)
        if (started) return
        started = true
        scope.launch {
            pull()
            Library.version.collectLatest { delay(1500); export() }
        }
    }

    /** Beim Öffnen der App: nachsehen, ob die andere App inzwischen Neueres hat */
    fun refresh() { if (::app.isInitialized) scope.launch { pull() } }

    // ───────── Austausch ─────────

    private fun call(method: String, json: String? = null): Bundle? = try {
        app.contentResolver.call(Uri.parse("content://${sibling(app)}.sync"), method, null, Bundle().apply { json?.let { putString("json", it) } })
    } catch (_: Exception) { null } // andere App nicht installiert

    private suspend fun pull() {
        val remote = call("get")?.getString("json")?.let { try { parse(it) } catch (_: Exception) { null } }
        lock.withLock {
            when {
                remote == null -> Unit
                canon.stamp == 0L && remote.stamp == 0L -> {
                    // Beide noch nie abgeglichen: zusammenführen statt überschreiben
                    val union = union(local(), remote)
                    applyState(union.copy(stamp = System.currentTimeMillis()))
                    call("put", toJson(canon))
                }
                remote.stamp > canon.stamp -> applyState(remote)
                canon.stamp > remote.stamp -> call("put", toJson(canon))
            }
        }
    }

    /** Lokale Änderung → neuen Stand bilden und an die andere App schicken */
    private suspend fun export() {
        val out = lock.withLock {
            val merged = merge(local(), canon)
            if (merged.sameContent(canon) && canon.stamp != 0L) {
                if (merged.playlists.map { it.pending } != canon.playlists.map { it.pending }) { canon = merged.copy(stamp = canon.stamp); save() }
                null
            } else {
                if (merged.empty && canon.stamp == 0L) return@withLock null
                canon = merged.copy(stamp = maxOf(System.currentTimeMillis(), canon.stamp + 1))
                save()
                toJson(canon)
            }
        }
        out?.let { call("put", it) }
    }

    /** Für die andere App: aktueller Stand (inkl. noch nicht gesendeter lokaler Daten) */
    fun current(): String = runBlocking(Dispatchers.IO) { lock.withLock { toJson(merge(local(), canon).copy(stamp = canon.stamp)) } }

    /** Von der andere App empfangen */
    fun receive(json: String) = runBlocking(Dispatchers.IO) {
        val remote = try { parse(json) } catch (_: Exception) { return@runBlocking }
        lock.withLock {
            if (canon.stamp == 0L && !local().empty) applyState(union(local(), remote).copy(stamp = maxOf(remote.stamp, System.currentTimeMillis())))
            else if (remote.stamp > canon.stamp) applyState(remote)
        }
    }

    // ───────── Stand bilden / zusammenführen ─────────

    private fun rootOf(uri: String) = uri.substringBefore("/document/")

    private suspend fun local(): State {
        val prefs = Prefs.get(app)
        val roots = listOf(MUSIC, BOOKS).map { s -> prefs.roots(s) + prefs.pendingRoots(s) }
        val star = prefs.starId
        val pls = Library.dao.allPlaylists().map { p ->
            PL(p.uid, p.name, p.id == star, Library.playlistTracks(p.id).map { it.track.uri })
        }
        return State(canon.stamp, roots, pls)
    }

    /** Lokal + bisheriger gemeinsamer Stand: Titel aus Ordnern ohne Zugriff bleiben erhalten, frei gewordene werden ergänzt */
    private suspend fun merge(local: State, c: State): State {
        val prefs = Prefs.get(app)
        val granted = (prefs.roots(MUSIC) + prefs.roots(BOOKS)).map { it.uri }.toSet()
        val pls = local.playlists.map { l ->
            val old = c.playlists.firstOrNull { it.uid == l.uid } ?: return@map l
            val have = l.tracks.toSet()
            // war vorher ohne Zugriff, jetzt in der Bibliothek → auch lokal in die Playlist
            val resolved = old.pending.filter { it !in have && Library.dao.byUri(it) != null }
            if (resolved.isNotEmpty()) {
                val id = Library.dao.playlistByUid(l.uid)?.id
                if (id != null) { Library.dao.addToPlaylist(id, resolved.mapNotNull { Library.dao.byUri(it)?.id }); Library.version.value++ }
            }
            // weiterhin ohne Zugriff → mitführen
            val still = old.tracks.filter { it !in have && it !in resolved && rootOf(it) !in granted && Library.dao.byUri(it) == null }
            l.copy(tracks = l.tracks + resolved + still, pending = still.toSet())
        }
        return State(c.stamp, local.roots, pls)
    }

    /** Erster Abgleich: alles aus beiden Apps übernehmen (gleichnamige Playlisten werden vereint) */
    private fun union(a: State, b: State): State {
        val roots = (0..1).map { s -> (b.roots.getOrElse(s) { emptyList() } + a.roots.getOrElse(s) { emptyList() }).distinctBy { it.uri } }
        val pls = ArrayList(b.playlists)
        for (p in a.playlists) {
            val i = pls.indexOfFirst { it.uid == p.uid || it.name.equals(p.name, ignoreCase = true) }
            if (i < 0) pls += p
            else pls[i] = pls[i].copy(star = pls[i].star || p.star, tracks = (pls[i].tracks + p.tracks).distinct())
        }
        return State(0L, roots, pls)
    }

    /** Fremden Stand übernehmen: Ordner (ohne Zugriff → „Zugriff erlauben“) und Playlisten */
    private suspend fun applyState(s: State) {
        val prefs = Prefs.get(app)
        val perms = app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        for (sec in listOf(MUSIC, BOOKS)) {
            val target = s.roots.getOrElse(sec) { emptyList() }
            val ids = target.map { it.uri }.toSet()
            prefs.roots(sec).filter { it.uri !in ids }.forEach { Library.removeRoot(app, sec, it.uri) }
            val pending = ArrayList<Root>()
            for (r in target) {
                when {
                    prefs.roots(sec).any { it.uri == r.uri } -> Unit
                    r.uri in perms -> Library.addRoot(app, sec, Uri.parse(r.uri))
                    else -> pending += r
                }
            }
            prefs.setPendingRoots(sec, pending)
        }
        val dao = Library.dao
        val keep = HashSet<String>()
        val out = ArrayList<PL>()
        for (p in s.playlists) {
            keep += p.uid
            val existing = dao.playlistByUid(p.uid)
            val id = existing?.id ?: dao.insertPlaylist(Playlist(name = p.name, created = System.currentTimeMillis(), uid = p.uid))
            if (existing != null && existing.name != p.name) dao.renamePlaylist(id, p.name)
            val found = p.tracks.mapNotNull { u -> dao.byUri(u)?.let { u to it.id } }
            dao.replaceItems(id, found.map { it.second })
            val foundUris = found.map { it.first }.toSet()
            if (p.star) prefs.starId = id
            out += p.copy(pending = p.tracks.filter { it !in foundUris }.toSet())
        }
        dao.allPlaylists().filter { it.uid !in keep }.forEach { dao.deletePlaylist(it.id) }
        canon = State(s.stamp, s.roots, out)
        save()
        Library.stars.value++
        Library.version.value++
    }

    // ───────── Datei / JSON ─────────

    private fun save() { try { file().writeText(toJson(canon, withPending = true)) } catch (_: Exception) { } }

    private fun toJson(s: State, withPending: Boolean = false): String = JSONObject().apply {
        put("stamp", s.stamp)
        put("roots", JSONArray().apply {
            s.roots.forEach { list -> put(JSONArray().apply { list.forEach { put(JSONObject().put("uri", it.uri).put("name", it.name)) } }) }
        })
        put("playlists", JSONArray().apply {
            s.playlists.forEach { p ->
                put(JSONObject().put("uid", p.uid).put("name", p.name).put("star", p.star)
                    .put("tracks", JSONArray(p.tracks))
                    .apply { if (withPending) put("pending", JSONArray(p.pending.toList())) })
            }
        })
    }.toString()

    private fun parse(json: String, withPending: Boolean = false): State {
        val o = JSONObject(json)
        val ra = o.optJSONArray("roots") ?: JSONArray()
        val roots = (0..1).map { i ->
            val a = ra.optJSONArray(i) ?: JSONArray()
            List(a.length()) { a.getJSONObject(it).let { r -> Root(r.getString("uri"), r.getString("name")) } }
        }
        val pa = o.optJSONArray("playlists") ?: JSONArray()
        val pls = List(pa.length()) { i ->
            val p = pa.getJSONObject(i)
            val t = p.optJSONArray("tracks") ?: JSONArray()
            val pend = if (withPending) p.optJSONArray("pending") else null
            PL(
                p.getString("uid"), p.getString("name"), p.optBoolean("star"),
                List(t.length()) { t.getString(it) },
                if (pend != null) List(pend.length()) { pend.getString(it) }.toSet() else emptySet(),
            )
        }
        return State(o.optLong("stamp"), roots, pls)
    }
}

/** Schnittstelle für die andere App – nur sie darf abfragen (Paketname wird geprüft) */
class SyncProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { Prefs.get(it); Library.init(it); Sync.init(it) }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (callingPackage != Sync.sibling(ctx)) return null
        return when (method) {
            "get" -> Bundle().apply { putString("json", Sync.current()) }
            "put" -> { extras?.getString("json")?.let { Sync.receive(it) }; Bundle() }
            else -> null
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}
