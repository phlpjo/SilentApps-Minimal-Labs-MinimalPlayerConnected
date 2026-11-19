package de.philipdecker.playerconnected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

// ───────────────────────── Schrift ─────────────────────────

val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_300, FontWeight.Light),
    Font(R.font.space_grotesk_400, FontWeight.Normal),
    Font(R.font.space_grotesk_500, FontWeight.Medium),
)
val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_300, FontWeight.Light),
    Font(R.font.jetbrains_mono_400, FontWeight.Normal),
    Font(R.font.jetbrains_mono_500, FontWeight.Medium),
)

fun fontFor(key: String): FontFamily = when (key) {
    "system" -> FontFamily.Default
    "mono" -> JetBrainsMono
    else -> SpaceGrotesk
}
fun fontLabel(key: String) = when (key) { "system" -> "System"; "mono" -> "Mono"; else -> "Space Grotesk" }
fun sizeFactor(i: Int) = when (i) { 0 -> 0.88f; 2 -> 1.16f; else -> 1f }
fun sizeLabel(i: Int) = when (i) { 0 -> R.string.size_small; 2 -> R.string.size_large; else -> R.string.size_normal }
/** Stern-Mitbewegung beim Scrollen: 0 aus, 1 sehr langsam, 2 langsam, 3 normal */
fun warpFactor(level: Int) = when (level) { 0 -> 0f; 1 -> 0.15f; 2 -> 0.4f; else -> 1f }
fun warpLabel(level: Int) = when (level) { 0 -> R.string.warp_off; 1 -> R.string.warp_very_slow; 2 -> R.string.warp_slow; else -> R.string.warp_normal }

val LocalFont = compositionLocalOf { SpaceGrotesk }
val LocalScale = compositionLocalOf { 1f }

/** Scroll-/Wisch-Schwung (px/Frame, mit Vorzeichen) – lässt die Sterne zu Lichtstreifen werden */
val LocalWarp = compositionLocalOf<MutableFloatState> { mutableFloatStateOf(0f) }

// ───────────────────────── Sternenfeld ─────────────────────────

private class Star(var x: Float, var y: Float, val z: Float, val phase: Float, val size: Float)

/**
 * Sterne auf reinem Schwarz (OLED-schonend: wenige, kleine, gedimmte Punkte).
 * - langsames Driften und Funkeln (Modus „move“), ruhig stehend („still“) oder aus
 * - Parallaxe: nahe Sterne (z groß) bewegen sich schneller
 * - Warp: beim Scrollen/Wischen ziehen die Sterne Streifen in Bewegungsrichtung
 */
@Composable
fun Starfield(mode: String, warp: MutableFloatState, speed: Float, modifier: Modifier = Modifier) {
    if (mode == "off") return
    val stars = remember {
        val r = Random(7)
        List(140) { Star(r.nextFloat(), r.nextFloat(), 0.25f + r.nextFloat() * r.nextFloat() * 0.75f, r.nextFloat() * 6.28f, 0.6f + r.nextFloat() * 1.1f) }
    }
    var time by remember { mutableLongStateOf(0L) }
    var streak by remember { mutableFloatStateOf(0f) }
    val moving = mode == "move"

    LaunchedEffect(moving, speed) {
        var last = 0L
        while (true) {
            if (speed == 0f) { warp.floatValue = 0f; streak = 0f }   // Mitbewegung aus
            // Ruhender Himmel ohne Schwung: keine Frames, bis wieder gescrollt/gewischt wird (spart Akku)
            if (!moving && abs(warp.floatValue) < 0.05f && abs(streak) < 0.05f) {
                streak = 0f
                snapshotFlow { warp.floatValue }.first { abs(it) >= 0.05f }
                last = 0L
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1e9f)
                last = now
                val w = warp.floatValue
                // Schwung abklingen lassen (≈ 0,25 s), Streifenlänge folgt weich
                warp.floatValue = if (abs(w) < 0.05f) 0f else w * 0.86f
                streak += (w - streak) * 0.35f
                for (s in stars) {
                    // Warp: vertikal entgegen der Scrollrichtung; Driften: ganz langsam nach oben
                    s.y -= (streak * 0.0016f * speed + if (moving) 0.0035f * dt else 0f) * s.z
                    if (s.y < -0.02f) s.y += 1.04f
                    if (s.y > 1.02f) s.y -= 1.04f
                }
                if (moving || abs(w) > 0.05f || abs(streak) > 0.05f) time = now
            }
        }
    }

    Canvas(modifier.fillMaxSize()) {
        val t = time / 1e9f
        val len = streak * 5f * speed
        for (s in stars) {
            val twinkle = if (moving) 0.75f + 0.25f * sin(t * (0.6f + s.z) + s.phase) else 1f
            val a = (0.18f + 0.55f * s.z) * twinkle
            val p = Offset(s.x * size.width, s.y * size.height)
            val c = Ink.copy(alpha = (if (Palette.dark) a else a * 0.8f).coerceIn(0f, 1f))
            if (abs(len) > 1.5f) {
                // Lichtstreifen: Länge mit Tiefe, Ende ausgeblendet
                drawLine(c, p, Offset(p.x, p.y + len * s.z * s.z * 2.2f), strokeWidth = s.size * s.z * 2f, cap = StrokeCap.Round)
            } else {
                drawCircle(c, radius = s.size * s.z * 1.6f, center = p)
            }
        }
    }
}
