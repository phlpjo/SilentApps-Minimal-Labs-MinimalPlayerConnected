package de.philipdecker.playerconnected

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

    /** Verlauf 0..1 je Abschnitt, null wenn die Datei nicht gelesen werden kann */
    suspend fun of(ctx: Context, uri: String): FloatArray? {
        val k = key(uri)
        mem.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                mem.get(k)?.let { return@withLock it }
                val f = file(ctx, k)
                val cached = try {
                    if (f.exists() && f.length() == N * 4L) {
                        val bb = ByteBuffer.wrap(f.readBytes())
                        FloatArray(N) { bb.float }
                    } else null
                } catch (_: Exception) { null }
                val w = cached ?: analyze(ctx, uri)?.also { a ->
                    try {
                        val bb = ByteBuffer.allocate(N * 4)
                        a.forEach { bb.putFloat(it) }
                        f.writeBytes(bb.array())
                    } catch (_: Exception) { }
                }
                w?.also { mem.put(k, it) }
            }
        }
    }

    private suspend fun analyze(ctx: Context, uri: String): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, Uri.parse(uri), null)
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
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
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
                while (guard++ < 400) {
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
                    val ob = c.dequeueOutputBuffer(info, 5_000)
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
                    } else if (inputDone && ob == MediaCodec.INFO_TRY_AGAIN_LATER && guard > 300) break
                }
                // Mischung aus Effektivwert und Spitze: wirkt wie die Lautheit, die man hört
                val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
                out[i] = rms * 0.7f + peak * 0.3f
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
