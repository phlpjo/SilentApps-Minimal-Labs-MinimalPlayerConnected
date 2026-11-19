package de.philipdecker.playerconnected

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Winziger HTTP-Server, über den der Lautsprecher die gerade gespielte Datei abholt.
 * Läuft nur, solange ein Gerät verbunden ist. Jede Datei bekommt eine zufällige, nicht erratbare Adresse;
 * es gibt keine Liste und kein Verzeichnis. Unterstützt Range-Anfragen (Spulen).
 */
object Serve {
    class Published(val url: String, val mime: String, val cover: String?, val durationMs: Long)

    private class Entry(val uri: Uri, val mime: String)

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "serve").apply { isDaemon = true } }
    private val files = ConcurrentHashMap<String, Entry>()
    private val covers = ConcurrentHashMap<String, File>()
    private val random = SecureRandom()
    @Volatile private var app: Context? = null

    @Synchronized
    private fun ensure(ctx: Context): Int {
        app = ctx.applicationContext
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0, 16)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val sock = try { s.accept() } catch (_: Exception) { break }
                pool.execute { handle(sock) }
            }
        }
        return s.localPort
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
        files.clear(); covers.clear()
    }

    /** Eigene Adresse im Netz des Lautsprechers (verschickt nichts, fragt nur die Route ab) */
    private fun localAddressFor(host: String): String =
        DatagramSocket().use { it.connect(InetAddress.getByName(host), 1900); it.localAddress.hostAddress ?: throw IllegalStateException("keine Adresse") }

    private fun token(): String = ByteArray(12).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Datei freigeben → Adresse für den Lautsprecher (läuft im Hintergrund-Thread) */
    fun publish(ctx: Context, host: String, item: MediaItem): Published {
        val port = ensure(ctx)
        val ip = localAddressFor(host)
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
        val name = displayName(ctx, uri) ?: uri.lastPathSegment ?: "audio"
        val ext = name.substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"
        val mime = mimeOf(ext, ctx.contentResolver.getType(uri))
        if (files.size > 400) files.clear()
        val t = token()
        files[t] = Entry(uri, mime)
        val coverUrl = try {
            val h = runBlocking { Library.dao.coverOf(item.mediaId) }
            h?.let { Covers.fileOf(ctx, it) }?.let { f ->
                val c = token()
                if (covers.size > 400) covers.clear()
                covers[c] = f
                "http://$ip:$port/c/$c.jpg"
            }
        } catch (_: Throwable) { null }
        return Published("http://$ip:$port/a/$t.$ext", mime, coverUrl, durationOf(ctx, item.mediaId, uri))
    }

    /** Länge der Datei (manche Lautsprecher melden keine) – aus der Bibliothek, sonst aus der Datei */
    private fun durationOf(ctx: Context, id: String, uri: Uri): Long {
        try { runBlocking { Library.dao.byUri(id) }?.duration?.takeIf { it > 0 }?.let { return it } } catch (_: Throwable) { }
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) { 0L } finally { try { r.release() } catch (_: Throwable) { } }
    }

    fun isOurs(url: String?): Boolean = url != null && url.contains("/a/") && files.keys.any { url.contains(it) }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun mimeOf(ext: String, given: String?): String = when (ext) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "m4b", "mp4", "aac", "alac" -> "audio/mp4"
        "ogg", "oga", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aif", "aiff" -> "audio/aiff"
        "wma" -> "audio/x-ms-wma"
        else -> given?.takeIf { it.startsWith("audio/") } ?: "audio/mpeg"
    }

    // ───────── HTTP ─────────

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val inp = BufferedInputStream(s.getInputStream())
                val first = readLine(inp) ?: return
                val parts = first.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                var range: String? = null
                while (true) {
                    val l = readLine(inp) ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("range:", true)) range = l.substringAfter(':').trim()
                }
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                if (method != "GET" && method != "HEAD") { status(out, "405 Method Not Allowed"); return }
                val head = method == "HEAD"
                val key = path.substringAfterLast('/').substringBefore('.')
                when {
                    path.startsWith("/a/") -> files[key]?.let { serveFile(out, it, range, head) } ?: status(out, "404 Not Found")
                    path.startsWith("/c/") -> covers[key]?.let { serveCover(out, it, head) } ?: status(out, "404 Not Found")
                    else -> status(out, "404 Not Found")
                }
                out.flush()
            }
        } catch (_: Exception) { }
    }

    private fun status(out: OutputStream, s: String) {
        out.write("HTTP/1.1 $s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
    }

    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private const val DLNA = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    private fun serveFile(out: OutputStream, e: Entry, range: String?, head: Boolean) {
        val ctx = app ?: return status(out, "503 Service Unavailable")
        val afd: AssetFileDescriptor = try { ctx.contentResolver.openAssetFileDescriptor(e.uri, "r") } catch (_: Exception) { null }
            ?: return status(out, "404 Not Found")
        afd.use {
            var len = afd.length
            if (len < 0) len = try { afd.parcelFileDescriptor.statSize } catch (_: Exception) { -1L }
            val fis = FileInputStream(afd.fileDescriptor)
            val common = "Content-Type: ${e.mime}\r\nAccept-Ranges: bytes\r\ntransferMode.dlna.org: Streaming\r\ncontentFeatures.dlna.org: $DLNA\r\nConnection: close\r\n"
            if (len < 0) {
                // Länge unbekannt: einfach durchreichen
                out.write("HTTP/1.1 200 OK\r\n$common\r\n".toByteArray())
                if (!head) fis.copyTo(out, 64 * 1024)
                return
            }
            var start = 0L
            var end = len - 1
            var partial = false
            val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
            if (m != null) {
                val a = m.groupValues[1]; val b = m.groupValues[2]
                when {
                    a.isEmpty() && b.isNotEmpty() -> { start = (len - b.toLong()).coerceAtLeast(0); partial = true }
                    a.isNotEmpty() -> { start = a.toLong(); if (b.isNotEmpty()) end = minOf(b.toLong(), len - 1); partial = true }
                }
                if (start >= len || start > end) {
                    out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$len\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return
                }
            }
            val count = end - start + 1
            val hdr = StringBuilder()
            hdr.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            hdr.append(common).append("Content-Length: ").append(count).append("\r\n")
            if (partial) hdr.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(len).append("\r\n")
            hdr.append("\r\n")
            out.write(hdr.toString().toByteArray())
            if (head) return
            val ch = fis.channel
            ch.position(afd.startOffset + start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun serveCover(out: OutputStream, f: File, head: Boolean) {
        val len = f.length()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: $len\r\nConnection: close\r\n\r\n".toByteArray())
        if (!head) f.inputStream().use { it.copyTo(out, 32 * 1024) }
    }
}
