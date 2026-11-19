package de.philipdecker.playerconnected

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Cover aus den Metadaten: einmal beim Einlesen gespeichert, gleiche Bilder nur einmal (Hash).
 * Klein (Listen) und groß (Player). Liegt im App-Speicher, verlässt das Gerät nicht.
 */
object Covers {
    private const val THUMB = 192
    private const val LARGE = 1080
    private val thumbs = LruCache<String, ImageBitmap>(300)
    private var big: Pair<String, ImageBitmap>? = null

    private fun dir(ctx: Context) = File(ctx.filesDir, "covers").apply { mkdirs() }
    private fun thumbFile(ctx: Context, h: String) = File(dir(ctx), "$h.jpg")
    private fun largeFile(ctx: Context, h: String) = File(dir(ctx), "${h}_l.jpg")

    /** Eingebettetes Bild speichern → Hash (leer bei Fehler) */
    fun save(ctx: Context, bytes: ByteArray): String {
        if (bytes.size < 64) return ""
        val h = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }.take(20)
        if (thumbFile(ctx, h).exists() && largeFile(ctx, h).exists()) return h
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return ""
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= LARGE) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return ""
            fun scaled(max: Int): Bitmap {
                val m = maxOf(bmp.width, bmp.height)
                if (m <= max) return bmp
                val f = max.toFloat() / m
                return Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt().coerceAtLeast(1), (bmp.height * f).toInt().coerceAtLeast(1), true)
            }
            largeFile(ctx, h).outputStream().use { scaled(LARGE).compress(Bitmap.CompressFormat.JPEG, 88, it) }
            thumbFile(ctx, h).outputStream().use { scaled(THUMB).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            h
        } catch (_: Throwable) { "" }
    }

    /** Großes Cover als Datei (für Lautsprecher im Heimnetz, z. B. Sonos-Anzeige) */
    fun fileOf(ctx: Context, h: String): File? = if (h.isEmpty()) null else largeFile(ctx, h).takeIf { it.exists() }

    suspend fun thumb(ctx: Context, h: String): ImageBitmap? {
        if (h.isEmpty()) return null
        thumbs.get(h)?.let { return it }
        return withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(thumbFile(ctx, h).path)?.asImageBitmap() } catch (_: Throwable) { null }
        }?.also { thumbs.put(h, it) }
    }

    suspend fun large(ctx: Context, h: String): ImageBitmap? {
        if (h.isEmpty()) return null
        big?.let { if (it.first == h) return it.second }
        return withContext(Dispatchers.IO) {
            try { BitmapFactory.decodeFile(largeFile(ctx, h).path)?.asImageBitmap() } catch (_: Throwable) { null }
        }?.also { big = h to it }
    }
}
