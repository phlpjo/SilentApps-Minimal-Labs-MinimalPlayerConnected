package de.philipdecker.playerconnected

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

/**
 * Lautstärkeverlauf einer Datei für die Wellenform im Player (wie bei SoundCloud).
 * Es wird nicht die ganze Datei dekodiert: an [N] gleichmäßig verteilten Stellen je ein kurzes Stück –
 * dadurch auch bei stundenlangen Hörbüchern in wenigen Sekunden fertig. Ergebnis liegt im App-Speicher.
 */
object Waves {
    const val N = 160
    private const val WINDOW_US = 350_000L
    private val mem = LruCache<String, FloatArray>(24)
    private val lock = Mutex()

    private fun key(uri: String) = MessageDigest.getInstance("MD5").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    private fun file(ctx: Context, k: String) = File(File(ctx.filesDir, "waves").apply { mkdirs() }, "$k.w")
    /** Fehlversuche je Datei; erst nach [MAX_TRIES] gilt sie als nicht auswertbar (ein Fehlschlag kann Zufall sein) */
    private const val MAX_TRIES = 3
    private fun failFile(ctx: Context, k: String) = File(File(ctx.filesDir, "waves").apply { mkdirs() }, "$k.x")
    private fun tries(ctx: Context, k: String): Int = try {
        failFile(ctx, k).takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: if (failFile(ctx, k).exists()) MAX_TRIES else 0
    } catch (_: Exception) { 0 }
    private fun failed(ctx: Context, k: String) = tries(ctx, k) >= MAX_TRIES

    private fun has(ctx: Context, k: String) = file(ctx, k).let { it.exists() && it.length() == N * 4L } || failed(ctx, k)

    /** Alte Markierungen (früher: ein Fehlschlag = für immer) einmalig löschen */
    @Volatile private var cleaned = false
    private fun cleanOld(ctx: Context) {
        if (cleaned) return
        cleaned = true
        val sp = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)
        if (sp.getInt("wavesFix", 0) >= 1) return
        try { File(ctx.filesDir, "waves").listFiles { f -> f.name.endsWith(".x") }?.forEach { it.delete() } } catch (_: Exception) { }
        sp.edit().putInt("wavesFix", 1).apply()
    }

    private fun write(ctx: Context, k: String, a: FloatArray?) {
        try {
            if (a == null) { failFile(ctx, k).writeText("${tries(ctx, k) + 1}"); return }
            failFile(ctx, k).delete()
            val bb = ByteBuffer.allocate(N * 4)
            a.forEach { bb.putFloat(it) }
            file(ctx, k).writeBytes(bb.array())
        } catch (_: Exception) { }
    }

    /** Eigener Hintergrund-Thread mit niedriger Priorität: Wiedergabe und Oberfläche haben immer Vorrang */
    private val slow = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "waves").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }.asCoroutineDispatcher()
    private var job: kotlinx.coroutines.Job? = null

    /**
     * Ganze Musikbibliothek im Hintergrund vorberechnen (Album für Album), damit die Wellenform beim
     * Öffnen eines Titels sofort da ist. Bereits berechnete Dateien werden übersprungen.
     */
    fun prepareLibrary(ctx: Context) {
        if (Prefs.get(ctx).waveMode != "library") return
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + slow).launch {
            cleanOld(app)
            // Titel vom Computer nicht vorab (würde die ganze Bibliothek übers WLAN laden)
            val uris = try { Library.dao.urisOf(MUSIC).filter { !Pc.isPc(it) } } catch (_: Exception) { emptyList() }
            for (u in uris) {
                ensureActive()
                val k = key(u)
                if (has(app, k)) continue
                lock.withLock { if (!has(app, k)) write(app, k, analyze(app, u, null)) }
                delay(30) // dem Gerät zwischendurch Luft lassen
            }
        }
    }

    private var listJob: kotlinx.coroutines.Job? = null

    /** Modus „aktuelle Titelliste“: beim Laden einer Liste deren Titel im Hintergrund vorberechnen */
    private var lastList: List<String>? = null

    fun prepareList(ctx: Context, uris: List<String>) {
        if (Prefs.get(ctx).waveMode != "queue") return
        lastList = uris
        val app = ctx.applicationContext
        listJob?.cancel()
        listJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + slow).launch {
            for (u in uris.take(500)) {
                ensureActive()
                val k = key(u)
                if (has(app, k)) continue
                lock.withLock { if (!has(app, k)) write(app, k, analyze(app, u, null)) }
                delay(30)
            }
        }
    }

    /** Einstellung geändert: Hintergrundarbeit passend starten oder anhalten */
    fun modeChanged(ctx: Context) {
        when (Prefs.get(ctx).waveMode) {
            "library" -> { listJob?.cancel(); prepareLibrary(ctx) }
            else -> { job?.cancel(); job = null; if (Prefs.get(ctx).waveMode != "queue") listJob?.cancel() }
        }
    }

    /**
     * Verlauf 0..1 je Abschnitt, null wenn die Datei nicht gelesen werden kann.
     * onPartial meldet während der Berechnung den bisherigen Stand (Rohwerte, Anzahl fertiger Abschnitte).
     */
    suspend fun of(ctx: Context, uri: String, onPartial: ((FloatArray, Int) -> Unit)? = null): FloatArray? {
        val k = key(uri)
        mem.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            // Schon berechnet → sofort von der Platte, ohne auf die Hintergrund-Berechnung zu warten
            cleanOld(ctx)
            read(ctx, k)?.let { mem.put(k, it); return@withContext it }
            if (failed(ctx, k)) return@withContext null
            // Noch nicht berechnet: der angezeigte Titel hat Vorrang – Hintergrundarbeit anhalten
            val wasLibrary = job?.isActive == true
            val wasList = listJob?.isActive == true
            job?.cancel(); listJob?.cancel()
            try {
                lock.withLock {
                    mem.get(k)?.let { return@withLock it }
                    val w = read(ctx, k) ?: analyze(ctx, uri, onPartial).also { a -> write(ctx, k, a) }
                    w?.also { mem.put(k, it) }
                }
            } finally {
                // danach im Hintergrund dort weitermachen
                if (wasLibrary) { job = null; prepareLibrary(ctx) }
                if (wasList) lastList?.let { listJob = null; prepareList(ctx, it) }
            }
        }
    }

    private fun read(ctx: Context, k: String): FloatArray? = try {
        val f = file(ctx, k)
        if (f.exists() && f.length() == N * 4L) {
            val bb = ByteBuffer.wrap(f.readBytes())
            FloatArray(N) { bb.float }
        } else null
    } catch (_: Exception) { null }

    private suspend fun analyze(ctx: Context, uri: String, onPartial: ((FloatArray, Int) -> Unit)?): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Pc.realUri(Uri.parse(uri)), null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            if (dur <= 0) return null
            ex.selectTrack(track)
            // Software-Decoder bevorzugen: Hardware-Decoder sind knapp und werden für die Wiedergabe gebraucht
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val soft = try {
                android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
                    !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                        (ci.name.startsWith("c2.android.") || ci.name.startsWith("OMX.google."))
                }?.name
            } catch (_: Throwable) { null }
            val c = try { soft?.let { MediaCodec.createByCodecName(it) } } catch (_: Throwable) { null } ?: MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            val out = FloatArray(N)
            var floatPcm = false
            for (i in 0 until N) {
                coroutineContext.ensureActive()
                val t = dur * (2 * i + 1) / (2L * N)
                ex.seekTo(t, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                c.flush()
                var sum = 0.0
                var count = 0L
                var peak = 0f
                var inputDone = false
                var guard = 0
                val until = maxOf(ex.sampleTime, 0L) + WINDOW_US
                while (guard++ < 800) {
                    if (!inputDone) {
                        val ib = c.dequeueInputBuffer(5_000)
                        if (ib >= 0) {
                            val buf = c.getInputBuffer(ib)!!
                            val n = ex.readSampleData(buf, 0)
                            val st = ex.sampleTime
                            if (n < 0 || st > until) {
                                c.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(ib, 0, n, st, 0)
                                ex.advance()
                            }
                        }
                    }
                    val ob = c.dequeueOutputBuffer(info, 10_000)
                    if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = c.outputFormat
                        floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    } else if (ob >= 0) {
                        val buf = c.getOutputBuffer(ob)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val b = buf.slice().order(ByteOrder.nativeOrder())
                            if (floatPcm) {
                                val fb = b.asFloatBuffer()
                                while (fb.hasRemaining()) { val v = fb.get(); sum += v * v; count++; if (v > peak) peak = v else if (-v > peak) peak = -v }
                            } else {
                                val sb = b.asShortBuffer()
                                while (sb.hasRemaining()) { val v = sb.get() / 32768f; sum += v * v; count++; if (v > peak) peak = v else if (-v > peak) peak = -v }
                            }
                        }
                        c.releaseOutputBuffer(ob, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (inputDone && ob == MediaCodec.INFO_TRY_AGAIN_LATER && guard > 600) break
                }
                // Mischung aus Effektivwert und Spitze: wirkt wie die Lautheit, die man hört
                val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
                out[i] = rms * 0.7f + peak * 0.3f
                onPartial?.invoke(out, i + 1)
            }
            val max = out.maxOrNull() ?: 0f
            if (max <= 0.0001f) return null
            // Normieren; leise Stellen etwas anheben, damit die Form sichtbar bleibt
            for (i in out.indices) out[i] = sqrt((out[i] / max).coerceIn(0f, 1f))
            return out
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) { }
            try { codec?.release() } catch (_: Throwable) { }
            try { ex.release() } catch (_: Throwable) { }
        }
    }
}
