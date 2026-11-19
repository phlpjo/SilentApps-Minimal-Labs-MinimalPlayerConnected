package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** Kapitelmarke innerhalb einer Datei */
data class Chapter(val start: Long, val title: String)

/**
 * Liest eingebettete Kapitel:
 * - M4B/M4A/MP4: QuickTime-Kapitelspur (tref/chap, iTunes/Audible-Stil) und Nero-„chpl“
 * - MP3: ID3v2-CHAP-Rahmen
 * Ergebnis pro Datei zwischengespeichert. Weniger als zwei Kapitel = keine Kapitel.
 */
object Chapters {
    private val cache = HashMap<String, List<Chapter>>()

    suspend fun of(ctx: Context, uri: String): List<Chapter> {
        synchronized(cache) { cache[uri]?.let { return it } }
        val list = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).channel.use { ch -> read(ch) }
                } ?: emptyList()
            } catch (_: Throwable) { emptyList() }
        }
        val clean = list.filter { it.start >= 0 }.sortedBy { it.start }.distinctBy { it.start }
            .mapIndexed { i, c -> if (c.title.isBlank()) c.copy(title = "${i + 1}") else c }
            .takeIf { it.size >= 2 } ?: emptyList()
        synchronized(cache) { cache[uri] = clean }
        return clean
    }

    /** Index des Kapitels an Position ms (oder -1) */
    fun indexAt(list: List<Chapter>, ms: Long): Int {
        var r = -1
        for (i in list.indices) if (list[i].start <= ms + 250) r = i else break
        return r
    }

    private fun read(ch: FileChannel): List<Chapter> {
        val head = bytes(ch, 0, 12) ?: return emptyList()
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) return id3(ch)
        if (String(head, 4, 4, Charsets.ISO_8859_1) in setOf("ftyp", "moov", "free", "wide", "mdat")) return mp4(ch)
        return emptyList()
    }

    // ───────── Hilfen ─────────

    private fun bytes(ch: FileChannel, pos: Long, n: Int): ByteArray? {
        if (n < 0 || n > 64 * 1024 * 1024 || pos < 0 || pos + n > ch.size()) return null
        val b = ByteBuffer.allocate(n)
        var p = pos
        while (b.hasRemaining()) { val r = ch.read(b, p); if (r <= 0) return null; p += r }
        return b.array()
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    // ───────── MP4 ─────────

    private data class Box(val type: String, val data: Long, val end: Long)

    private fun children(ch: FileChannel, start: Long, end: Long): List<Box> {
        val out = ArrayList<Box>()
        var p = start
        var guard = 0
        while (p + 8 <= end && guard++ < 10_000) {
            val h = bytes(ch, p, minOf(16L, end - p).toInt()) ?: break
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var hdr = 8
            if (size == 1L && h.size >= 16) { size = u64(h, 8); hdr = 16 }
            else if (size == 0L) size = end - p
            if (size < hdr) break
            out += Box(type, p + hdr, (p + size).coerceAtMost(end))
            p += size
        }
        return out
    }

    private fun child(ch: FileChannel, b: Box, type: String) = children(ch, b.data, b.end).firstOrNull { it.type == type }
    private fun body(ch: FileChannel, b: Box, max: Int = 32 * 1024 * 1024): ByteArray? =
        bytes(ch, b.data, (b.end - b.data).coerceAtMost(max.toLong()).toInt())

    private fun mp4(ch: FileChannel): List<Chapter> {
        val moov = children(ch, 0, ch.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        val boxes = children(ch, moov.data, moov.end)
        val traks = boxes.filter { it.type == "trak" }

        // 1) QuickTime-Kapitelspur
        val chapIds = HashSet<Long>()
        for (t in traks) {
            val tref = child(ch, t, "tref") ?: continue
            val chap = child(ch, tref, "chap") ?: continue
            val b = body(ch, chap) ?: continue
            for (i in 0 until b.size / 4) chapIds += u32(b, i * 4)
        }
        if (chapIds.isNotEmpty()) {
            for (t in traks) {
                val tkhd = child(ch, t, "tkhd")?.let { body(ch, it, 64) } ?: continue
                val id = if (tkhd[0].toInt() == 1) u32(tkhd, 20) else u32(tkhd, 12)
                if (id !in chapIds) continue
                textTrack(ch, t)?.let { if (it.size >= 2) return it }
            }
        }

        // 2) Nero „chpl“ in moov/udta
        boxes.firstOrNull { it.type == "udta" }?.let { udta -> child(ch, udta, "chpl")?.let { c -> body(ch, c)?.let { nero(it) } } }
            ?.let { if (it.size >= 2) return it }
        return emptyList()
    }

    private fun nero(b: ByteArray): List<Chapter> {
        var o = 4
        if (b[0].toInt() != 0) o += 4
        if (o >= b.size) return emptyList()
        val n = b[o].toInt() and 0xFF; o++
        val out = ArrayList<Chapter>()
        repeat(n) {
            if (o + 9 > b.size) return out
            val start = u64(b, o) / 10_000L; o += 8
            val len = b[o].toInt() and 0xFF; o++
            if (o + len > b.size) return out
            out += Chapter(start, String(b, o, len, Charsets.UTF_8).trim()); o += len
        }
        return out
    }

    private fun textTrack(ch: FileChannel, trak: Box): List<Chapter>? {
        val mdia = child(ch, trak, "mdia") ?: return null
        val mdhd = child(ch, mdia, "mdhd")?.let { body(ch, it, 64) } ?: return null
        val scale = if (mdhd[0].toInt() == 1) u32(mdhd, 20) else u32(mdhd, 12)
        if (scale <= 0) return null
        val stbl = child(ch, mdia, "minf")?.let { child(ch, it, "stbl") } ?: return null
        val st = children(ch, stbl.data, stbl.end).associateBy { it.type }
        val stts = st["stts"]?.let { body(ch, it) } ?: return null
        val stsz = st["stsz"]?.let { body(ch, it) } ?: return null
        val stsc = st["stsc"]?.let { body(ch, it) } ?: return null
        val co64 = st["co64"] != null
        val stco = (st["stco"] ?: st["co64"])?.let { body(ch, it) } ?: return null

        // Zeiten
        val times = ArrayList<Long>()
        var t = 0L
        val ne = u32(stts, 4).toInt()
        for (i in 0 until ne) {
            val o = 8 + i * 8; if (o + 8 > stts.size) break
            val cnt = u32(stts, o); val delta = u32(stts, o + 4)
            for (k in 0 until cnt.coerceAtMost(5000)) { times += t * 1000L / scale; t += delta }
            if (times.size > 5000) break
        }
        // Größen
        val fixed = u32(stsz, 4)
        val count = u32(stsz, 8).toInt().coerceAtMost(5000)
        val sizes = IntArray(count) { i -> if (fixed != 0L) fixed.toInt() else if (12 + i * 4 + 4 <= stsz.size) u32(stsz, 12 + i * 4).toInt() else 0 }
        // Chunk-Offsets
        val nChunks = u32(stco, 4).toInt().coerceAtMost(5000)
        val chunkOff = LongArray(nChunks) { i -> if (co64) u64(stco, 8 + i * 8) else u32(stco, 8 + i * 4) }
        // Samples je Chunk
        val nsc = u32(stsc, 4).toInt()
        val firsts = IntArray(nsc) { u32(stsc, 8 + it * 12).toInt() }
        val per = IntArray(nsc) { u32(stsc, 12 + it * 12).toInt() }
        val offsets = ArrayList<Long>()
        var e = 0
        for (c in 1..nChunks) {
            while (e + 1 < nsc && firsts[e + 1] <= c) e++
            var off = chunkOff[c - 1]
            repeat(if (nsc > 0) per[e] else 1) {
                if (offsets.size < count) { offsets += off; off += sizes[offsets.size - 1] }
            }
            if (offsets.size >= count) break
        }
        val out = ArrayList<Chapter>()
        for (i in 0 until minOf(count, offsets.size, times.size)) {
            val s = bytes(ch, offsets[i], sizes[i].coerceIn(2, 4096)) ?: continue
            val len = u16(s, 0).coerceAtMost(s.size - 2)
            val title = if (len >= 2 && (s[2].toInt() and 0xFF) == 0xFE && (s[3].toInt() and 0xFF) == 0xFF) String(s, 2, len, Charsets.UTF_16)
            else String(s, 2, len, Charsets.UTF_8)
            out += Chapter(times[i], title.trim())
        }
        return out
    }

    // ───────── MP3 / ID3v2 ─────────

    private fun syncsafe(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0x7F) shl 21) or ((b[o + 1].toInt() and 0x7F) shl 14) or ((b[o + 2].toInt() and 0x7F) shl 7) or (b[o + 3].toInt() and 0x7F)

    private fun id3(ch: FileChannel): List<Chapter> {
        val h = bytes(ch, 0, 10) ?: return emptyList()
        val ver = h[3].toInt()
        if (ver < 3) return emptyList()
        val size = syncsafe(h, 6)
        val tag = bytes(ch, 10, minOf(size.toLong(), ch.size() - 10).toInt()) ?: return emptyList()
        var p = 0
        if (h[5].toInt() and 0x40 != 0) p = if (ver == 4) syncsafe(tag, 0) else (u32(tag, 0).toInt() + 4)
        val out = ArrayList<Chapter>()
        while (p + 10 <= tag.size) {
            if (tag[p].toInt() == 0) break
            val id = String(tag, p, 4, Charsets.ISO_8859_1)
            val fs = if (ver == 4) syncsafe(tag, p + 4) else u32(tag, p + 4).toInt()
            if (fs <= 0 || p + 10 + fs > tag.size) break
            if (id == "CHAP") chap(tag, p + 10, fs, ver)?.let { out += it }
            p += 10 + fs
        }
        return out
    }

    private fun chap(b: ByteArray, start: Int, len: Int, ver: Int): Chapter? {
        val end = start + len
        var p = start
        while (p < end && b[p].toInt() != 0) p++
        p++ // Element-ID
        if (p + 16 > end) return null
        val t0 = u32(b, p); p += 16
        var title = ""
        while (p + 10 <= end) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            val fs = if (ver == 4) syncsafe(b, p + 4) else u32(b, p + 4).toInt()
            if (fs <= 0 || p + 10 + fs > end) break
            if (id == "TIT2") title = text(b, p + 10, fs)
            p += 10 + fs
        }
        return Chapter(t0, title)
    }

    private fun text(b: ByteArray, o: Int, n: Int): String {
        if (n < 1) return ""
        val cs: Charset = when (b[o].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(b, o + 1, n - 1, cs).trim('\u0000', ' ', '﻿')
    }
}
