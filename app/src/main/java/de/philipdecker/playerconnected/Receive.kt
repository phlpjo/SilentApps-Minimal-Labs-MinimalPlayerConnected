package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Empfang von Minimal Library (PC/Mac) über das WLAN – nur solange der Bildschirm „Empfangen“ offen ist.
 *
 * - Finden: Minimal Library schickt „MLDISCOVER1“ per UDP-Broadcast an Port 47820, die App antwortet mit Name und Port.
 * - Koppeln: einmal den 6-stelligen Code eingeben → dauerhafter Schlüssel (nur dieser Computer darf schreiben).
 * - Schreiben nur in den selbst gewählten Empfangsordner; halbe Dateien gibt es nicht (erst „.mlpart“, dann umbenennen).
 * - Nach dem Sync: Ordner neu einlesen, Playlisten anlegen/aktualisieren.
 */
object Receive {
    const val UDP_PORT = 47820
    const val TCP_PORT = 47821

    data class Status(
        val running: Boolean = false,
        val code: String = "",
        val client: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val current: String? = null,
        val finished: Boolean = false,
        val error: String? = null,
    )

    val status = MutableStateFlow(Status())

    private lateinit var app: Context
    private val random = SecureRandom()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "receive").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var mlock: WifiManager.MulticastLock? = null
    private var port = TCP_PORT
    private var fails = 0
    private val writeLock = Any()

    private fun sp(ctx: Context) = ctx.getSharedPreferences("receive", Context.MODE_PRIVATE)

    /** Empfangsordner (Baum-URI mit Schreibrecht) */
    fun folder(ctx: Context): String? = sp(ctx).getString("tree", null)

    fun setFolder(ctx: Context, uri: String) { sp(ctx).edit().putString("tree", uri).apply() }

    /** Feste Kennung dieses Handys (für Minimal Library) */
    private fun deviceId(ctx: Context): String {
        val p = sp(ctx)
        return p.getString("id", null) ?: hex(8).also { p.edit().putString("id", it).apply() }
    }

    fun deviceName(ctx: Context): String =
        (try { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    /** Gekoppelte Computer: Schlüssel → Name */
    fun paired(ctx: Context): Map<String, String> =
        (sp(ctx).getStringSet("tokens", emptySet()) ?: emptySet()).mapNotNull { e -> e.split('|', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    fun forgetAll(ctx: Context) { sp(ctx).edit().remove("tokens").apply() }

    private fun addToken(token: String, client: String) {
        val set = (sp(app).getStringSet("tokens", emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.endsWith("|$client") } // gleicher Computer neu gekoppelt
        set += "$token|${client.replace('|', ' ').take(60)}"
        sp(app).edit().putStringSet("tokens", set).apply()
    }

    private fun hex(n: Int) = ByteArray(n).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private fun newCode() = (100000 + random.nextInt(900000)).toString()

    // ───────── Start / Stopp (an den Bildschirm gebunden) ─────────

    @Synchronized
    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (server != null) return
        fails = 0
        status.value = Status(running = true, code = newCode())
        val s = try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(TCP_PORT)) } }
            catch (_: Exception) { ServerSocket(0) }
        server = s
        port = s.localPort
        pool.execute {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(sock) }
            }
        }
        // Manche Handys lassen Broadcasts nur mit dieser Sperre durch
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            mlock = wm.createMulticastLock("minimal-receive").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        val u = try { DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(UDP_PORT)) } } catch (_: Exception) { null }
        udp = u
        if (u != null) pool.execute {
            val buf = ByteArray(512)
            while (!u.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { u.receive(p) } catch (_: Exception) { break }
                val msg = String(p.data, 0, p.length)
                if (!msg.startsWith("MLDISCOVER1")) continue
                val reply = JSONObject()
                    .put("id", deviceId(app))
                    .put("name", deviceName(app))
                    .put("port", port)
                    .put("app", "Minimal Player Connected")
                    .put("ready", folder(app) != null)
                    .put("free", free())
                    .toString().toByteArray()
                try { u.send(DatagramPacket(reply, reply.size, p.socketAddress)) } catch (_: Exception) { }
            }
        }
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        try { udp?.close() } catch (_: Exception) { }
        try { mlock?.release() } catch (_: Exception) { }
        server = null; udp = null; mlock = null
        status.value = status.value.copy(running = false)
    }

    private fun free(): Long = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { -1L }

    // ───────── HTTP ─────────

    private class Req(val method: String, val path: String, val headers: Map<String, String>, val body: InputStream)

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 60_000
                s.tcpNoDelay = true
                val inp = BufferedInputStream(s.getInputStream(), 256 * 1024)
                val first = readLine(inp) ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(inp) ?: break
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                val body: InputStream = when {
                    headers["transfer-encoding"]?.contains("chunked", true) == true -> Chunked(inp)
                    else -> Limited(inp, headers["content-length"]?.toLongOrNull() ?: 0L)
                }
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val req = Req(parts[0].uppercase(), parts[1].substringBefore('?'), headers, body)
                try { route(req, out) } catch (e: Exception) { reply(out, "500 Internal Server Error", (e.message ?: "Fehler").take(300)) }
                out.flush()
            }
        } catch (_: Exception) { }
    }

    private fun route(r: Req, out: OutputStream) {
        if (r.method == "POST" && r.path == "/pair") return pair(r, out)
        val token = r.headers["x-token"] ?: ""
        val client = paired(app)[token] ?: return reply(out, "401 Unauthorized", "nicht gekoppelt")
        val tree = folder(app)?.let { Uri.parse(it) } ?: return reply(out, "409 Conflict", "Auf dem Handy ist noch kein Empfangsordner gewählt")
        if (status.value.client != client) status.value = status.value.copy(client = client, finished = false, error = null)
        when {
            r.method == "GET" && r.path == "/info" ->
                reply(out, "200 OK", JSONObject().put("name", deviceName(app)).put("free", free()).put("folder", Library.rootName(app, tree)).toString(), "application/json")
            r.method == "GET" && r.path == "/list" -> list(tree, out)
            r.method == "POST" && r.path == "/begin" -> {
                val n = String(r.body.readBytes()).trim().toIntOrNull() ?: 0
                status.value = status.value.copy(done = 0, total = n, current = null, finished = false, error = null)
                reply(out, "200 OK", "")
            }
            r.method == "PUT" && r.path.startsWith("/f/") -> put(tree, decode(r.path.removePrefix("/f/")), r, out)
            r.method == "DELETE" && r.path.startsWith("/f/") -> delete(tree, decode(r.path.removePrefix("/f/")), out)
            r.method == "POST" && r.path == "/done" -> {
                val json = String(r.body.readBytes())
                reply(out, "200 OK", "")
                status.value = status.value.copy(current = null, finished = true)
                scope.launch { finish(tree, json) }
            }
            else -> reply(out, "404 Not Found", "")
        }
    }

    private fun pair(r: Req, out: OutputStream) {
        val code = String(r.body.readBytes()).trim()
        val st = status.value
        if (!st.running || code != st.code) {
            // Raten erschweren: nach 5 Fehlversuchen neuer Code
            if (++fails >= 5) { fails = 0; status.value = st.copy(code = newCode()) }
            return reply(out, "403 Forbidden", "Code falsch")
        }
        val client = r.headers["x-client"]?.takeIf { it.isNotBlank() } ?: "Computer"
        val token = hex(24)
        addToken(token, client)
        status.value = st.copy(code = newCode(), client = client)
        reply(out, "200 OK", JSONObject().put("token", token).put("id", deviceId(app)).put("name", deviceName(app)).toString(), "application/json")
    }

    private fun decode(p: String): String =
        URLDecoder.decode(p.replace("+", "%2B"), "UTF-8").split('/').filter { it.isNotEmpty() && it != "." && it != ".." }.joinToString("/")

    private fun reply(out: OutputStream, status: String, body: String, type: String = "text/plain; charset=utf-8") {
        val b = body.toByteArray()
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(b)
        out.flush()
    }

    // ───────── Dateien im Empfangsordner ─────────

    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    private class Child(val id: String, val name: String, val dir: Boolean, val size: Long)

    private fun children(tree: Uri, docId: String): List<Child> {
        val out = ArrayList<Child>()
        app.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += Child(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return out
    }

    private fun rootId(tree: Uri) = DocumentsContract.getTreeDocumentId(tree)
    private fun doc(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Alle Dateien: „Größe<TAB>relativer Pfad“ je Zeile */
    private fun list(tree: Uri, out: OutputStream) {
        val sb = StringBuilder()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId(tree) to "")
        while (stack.isNotEmpty()) {
            val (id, rel) = stack.removeLast()
            for (c in children(tree, id)) {
                val r = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                if (c.dir) stack.add(c.id to r)
                else if (c.name.endsWith(".mlpart")) {
                    // Rest eines abgebrochenen Empfangs
                    try { DocumentsContract.deleteDocument(app.contentResolver, doc(tree, c.id)) } catch (_: Exception) { }
                } else sb.append(c.size).append('\t').append(r).append('\n')
            }
        }
        reply(out, "200 OK", sb.toString())
    }

    /** Ordnerkette anlegen bzw. finden (Groß-/Kleinschreibung egal) → Dokument-ID */
    private fun ensureDir(tree: Uri, parts: List<String>): String {
        var cur = rootId(tree)
        for (p in parts) {
            val hit = children(tree, cur).firstOrNull { it.dir && it.name.equals(p, true) }
            cur = hit?.id ?: run {
                val u = DocumentsContract.createDocument(app.contentResolver, doc(tree, cur), DocumentsContract.Document.MIME_TYPE_DIR, p)
                    ?: throw IllegalStateException("Ordner „$p“ lässt sich nicht anlegen")
                DocumentsContract.getDocumentId(u)
            }
        }
        return cur
    }

    private fun findFile(tree: Uri, rel: String): Pair<String, Child>? {
        val parts = rel.split('/')
        var cur = rootId(tree)
        for (p in parts.dropLast(1)) {
            cur = children(tree, cur).firstOrNull { it.dir && it.name.equals(p, true) }?.id ?: return null
        }
        val f = children(tree, cur).firstOrNull { !it.dir && it.name.equals(parts.last(), true) } ?: return null
        return cur to f
    }

    private fun put(tree: Uri, rel: String, r: Req, out: OutputStream) {
        if (rel.isEmpty()) return reply(out, "400 Bad Request", "")
        val cr = app.contentResolver
        val parts = rel.split('/')
        val name = parts.last()
        status.value = status.value.copy(current = name)
        val tmp: Uri
        synchronized(writeLock) {
            val parent = ensureDir(tree, parts.dropLast(1))
            // alte Fassung weg (SAF überschreibt nicht, sondern hängt „ (1)“ an)
            children(tree, parent).filter { !it.dir && (it.name.equals(name, true) || it.name.equals("$name.mlpart", true)) }
                .forEach { try { DocumentsContract.deleteDocument(cr, doc(tree, it.id)) } catch (_: Exception) { } }
            tmp = DocumentsContract.createDocument(cr, doc(tree, parent), "application/octet-stream", "$name.mlpart")
                ?: throw IllegalStateException("„$name“ lässt sich nicht anlegen")
        }
        try {
            (cr.openOutputStream(tmp, "w") ?: throw IllegalStateException("kein Schreibzugriff")).use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = r.body.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                }
            }
            val complete = when (val b = r.body) { is Limited -> b.complete; is Chunked -> b.complete; else -> true }
            if (!complete) throw IllegalStateException("Übertragung unvollständig")
            DocumentsContract.renameDocument(cr, tmp, name) ?: throw IllegalStateException("Umbenennen fehlgeschlagen")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, tmp) } catch (_: Exception) { }
            throw e
        }
        status.value = status.value.copy(done = status.value.done + 1)
        reply(out, "200 OK", "")
    }

    private fun delete(tree: Uri, rel: String, out: OutputStream) {
        val cr = app.contentResolver
        synchronized(writeLock) {
            val (parent, f) = findFile(tree, rel) ?: return reply(out, "404 Not Found", "")
            DocumentsContract.deleteDocument(cr, doc(tree, f.id))
            // leere Ordner darüber entfernen (nie den Empfangsordner selbst)
            var dirId = parent
            var dirs = rel.split('/').dropLast(1)
            val root = rootId(tree)
            while (dirs.isNotEmpty() && dirId != root && children(tree, dirId).isEmpty()) {
                try { DocumentsContract.deleteDocument(cr, doc(tree, dirId)) } catch (_: Exception) { break }
                dirs = dirs.dropLast(1)
                dirId = try { ensureDirLookup(tree, dirs) ?: break } catch (_: Exception) { break }
            }
        }
        reply(out, "200 OK", "")
    }

    private fun ensureDirLookup(tree: Uri, parts: List<String>): String? {
        var cur = rootId(tree)
        for (p in parts) cur = children(tree, cur).firstOrNull { it.dir && it.name.equals(p, true) }?.id ?: return null
        return cur
    }

    /** Datei in einen Ordnerbaum schreiben (für „offline verfügbar“) → Dokument-Adresse */
    fun saveFile(ctx: Context, tree: Uri, rel: String, input: InputStream): String {
        if (!::app.isInitialized) app = ctx.applicationContext
        val cr = app.contentResolver
        val parts = rel.split('/').filter { it.isNotEmpty() }
        val name = parts.last()
        val tmp: Uri
        synchronized(writeLock) {
            val parent = ensureDir(tree, parts.dropLast(1))
            children(tree, parent).filter { !it.dir && (it.name.equals(name, true) || it.name.equals("$name.mlpart", true)) }
                .forEach { try { DocumentsContract.deleteDocument(cr, doc(tree, it.id)) } catch (_: Exception) { } }
            tmp = DocumentsContract.createDocument(cr, doc(tree, parent), "application/octet-stream", "$name.mlpart")
                ?: throw IllegalStateException("„$name“ lässt sich nicht anlegen")
        }
        try {
            (cr.openOutputStream(tmp, "w") ?: throw IllegalStateException("kein Schreibzugriff")).use { o -> input.copyTo(o, 256 * 1024) }
            return (DocumentsContract.renameDocument(cr, tmp, name) ?: throw IllegalStateException("Umbenennen fehlgeschlagen")).toString()
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, tmp) } catch (_: Exception) { }
            throw e
        }
    }

    /** Größe einer vorhandenen Datei (null = gibt es nicht) */
    fun sizeOf(ctx: Context, tree: Uri, rel: String): Long? {
        if (!::app.isInitialized) app = ctx.applicationContext
        return try { findFile(tree, rel)?.second?.size } catch (_: Exception) { null }
    }

    /** Leere Unterordner unterhalb von `top` entfernen */
    fun removeEmptyDirs(ctx: Context, tree: Uri, top: String) {
        if (!::app.isInitialized) app = ctx.applicationContext
        val start = try { ensureDirLookup(tree, top.split('/').filter { it.isNotEmpty() }) } catch (_: Exception) { null } ?: return
        fun walk(id: String): Boolean {
            val kids = children(tree, id)
            var empty = true
            for (k in kids) {
                if (k.dir && walk(k.id)) {
                    try { DocumentsContract.deleteDocument(app.contentResolver, doc(tree, k.id)) } catch (_: Exception) { empty = false }
                } else empty = false
            }
            return empty
        }
        synchronized(writeLock) { try { walk(start) } catch (_: Exception) { } }
    }

    /** Nach dem Sync: Ordner einlesen, Playlisten aus Minimal Library übernehmen */
    private suspend fun finish(tree: Uri, json: String) {
        try {
            Library.receiveDone(app, tree.toString(), parsePlaylists(json))
        } catch (e: Exception) {
            status.value = status.value.copy(error = e.message)
        }
    }

    /** {"prefix":"ml…","playlists":[{"uid":"…","name":"…","files":["Ordner/Titel.mp3", …]}]} */
    private fun parsePlaylists(json: String): Library.Received {
        if (json.isBlank()) return Library.Received("", emptyList())
        val o = JSONObject(json)
        val arr = o.optJSONArray("playlists") ?: JSONArray()
        val list = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val f = p.optJSONArray("files") ?: JSONArray()
            Library.ReceivedPlaylist(p.getString("uid"), p.getString("name"), (0 until f.length()).map { f.getString(it) })
        }
        return Library.Received(o.optString("prefix", ""), list)
    }

    // ───────── Hilfen ─────────

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return String(sb.toString().toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8).trimEnd('\r')
            if (sb.length > 16384) return null
            sb.append(b.toChar())
        }
    }

    /** Body mit fester Länge */
    private class Limited(private val inp: InputStream, private var left: Long) : InputStream() {
        val complete get() = left == 0L
        override fun read(): Int {
            if (left <= 0) return -1
            val b = inp.read()
            if (b >= 0) left-- else left = -1
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) { left = -1; return -1 }
            left -= n
            return n
        }
    }

    /** Body in „chunked“-Kodierung */
    private class Chunked(private val inp: InputStream) : InputStream() {
        private var left = 0L
        private var end = false
        /** Abschluss-Block (Länge 0) gesehen → alles angekommen */
        var complete = false
        private fun next(): Boolean {
            if (end) return false
            if (left == 0L) {
                var line = readRaw() ?: return false.also { end = true }
                if (line.isEmpty()) line = readRaw() ?: return false.also { end = true }
                left = line.substringBefore(';').trim().toLongOrNull(16) ?: 0L
                if (left == 0L) { end = true; complete = true; return false }
            }
            return true
        }
        private fun readRaw(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = inp.read()
                if (b < 0) return null
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
        override fun read(): Int {
            if (!next()) return -1
            val b = inp.read()
            if (b >= 0) left--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!next()) return -1
            val n = inp.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }
}
