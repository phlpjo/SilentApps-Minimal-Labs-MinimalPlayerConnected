package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Songtexte aus „Lied.lrc“ neben der Datei (legt Minimal Library an). Mit Zeitmarken läuft die Anzeige mit.
 */
object Lyrics {
    data class Line(val t: Long?, val text: String)

    private val cache = object : android.util.LruCache<String, List<Line>>(40) {}
    private val TS = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(text: String): List<Line> {
        val out = ArrayList<Line>()
        var offset = 0L
        for (raw in text.replace("\r\n", "\n").removePrefix("﻿").split('\n')) {
            Regex("""^\s*\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE).find(raw)?.let { offset = it.groupValues[1].toLong(); return@let }
            if (Regex("""^\s*\[[a-zA-Z]+:.*]\s*$""").matches(raw)) continue
            val ms = TS.findAll(raw).map { m ->
                val f = m.groupValues[3]
                val frac = if (f.isEmpty()) 0L else (f.toLong() * 1000 / Math.pow(10.0, f.length.toDouble())).toLong()
                m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac
            }.toList()
            val txt = raw.replace(TS, "").trim()
            if (ms.isNotEmpty()) ms.forEach { out += Line((it - offset).coerceAtLeast(0), txt) }
            else if (txt.isNotEmpty()) out += Line(null, txt)
        }
        if (out.any { it.t != null }) out.sortBy { it.t ?: 0L }
        return out
    }

    fun current(lines: List<Line>, pos: Long): Int {
        var idx = -1
        for ((i, l) in lines.withIndex()) {
            val t = l.t ?: continue
            if (t <= pos + 50) idx = i else break
        }
        return idx
    }

    suspend fun load(ctx: Context, uri: String): List<Line> = withContext(Dispatchers.IO) {
        cache.get(uri)?.let { return@withContext it }
        val text = try { read(ctx, uri) } catch (_: Exception) { null }
        val lines = text?.let { parse(it) } ?: emptyList()
        cache.put(uri, lines)
        lines
    }

    fun forget(uri: String) { cache.remove(uri) }

    private fun read(ctx: Context, uri: String): String? {
        if (Pc.isPc(uri)) return Pc.lyrics(ctx, uri)
        val u = Uri.parse(uri)
        if (u.scheme == "file") return java.io.File(u.path!!.substringBeforeLast('.') + ".lrc").takeIf { it.exists() }?.readText()
        // Gleicher Ordner, gleicher Name, Endung .lrc – erst direkt über die Dokument-ID (schnell) …
        try {
            val tree = DocumentsContract.getTreeDocumentId(u)
            val doc = DocumentsContract.getDocumentId(u)
            if (doc.contains('.')) {
                val lrc = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(uri.substringBefore("/document/")), doc.substringBeforeLast('.') + ".lrc")
                ctx.contentResolver.openInputStream(lrc)?.use { return String(it.readBytes()) }
            }
            if (tree.isEmpty()) return null
        } catch (_: Exception) { }
        // … sonst im Ordner nachsehen
        return try {
            val t = kotlinx.coroutines.runBlocking { Library.dao.byUri(uri) } ?: return null
            val tree = Uri.parse(t.root)
            var cur = DocumentsContract.getTreeDocumentId(tree)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            fun child(parent: String, name: String): String? =
                ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent), cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) if (c.getString(1).equals(name, true)) return@use c.getString(0)
                    null
                }
            for (p in t.dir.split('/').filter { it.isNotEmpty() }) cur = child(cur, p) ?: return null
            val id = child(cur, t.name.substringBeforeLast('.') + ".lrc") ?: return null
            ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { String(it.readBytes()) }
        } catch (_: Exception) { null }
    }
}

/** Mitlaufender Songtext: aktuelle Zeile leuchtet und steht in der Mitte; antippen springt dorthin */
@Composable
fun LyricsView(c: Player, uri: String, pos: Long, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val lines by produceState<List<Lyrics.Line>?>(null, uri) { value = Lyrics.load(ctx, uri) }
    val list = rememberLazyListState()
    val l = lines
    val cur = if (l != null) Lyrics.current(l, pos) else -1
    LaunchedEffect(cur, l) {
        if (cur >= 0) {
            val h = list.layoutInfo.viewportSize.height
            list.animateScrollToItem(cur, -(h / 3))
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        when {
            l == null -> Unit
            l.isEmpty() -> T(stringResource(R.string.no_lyrics), size = 15.sp, color = Faint)
            else -> LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 24.dp)) {
                itemsIndexed(l) { i, line ->
                    val on = i == cur
                    T(
                        line.text.ifEmpty { "♪" },
                        size = if (line.t == null) 17.sp else 21.sp, weight = FontWeight.Light,
                        color = if (on) Ink else if (line.t == null) Dim else Faint, glow = on,
                        modifier = Modifier.fillMaxWidth().tap { line.t?.let { c.seekTo(it) } }.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}
