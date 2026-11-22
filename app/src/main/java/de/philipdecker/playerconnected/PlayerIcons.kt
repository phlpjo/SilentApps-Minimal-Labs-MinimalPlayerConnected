package de.philipdecker.playerconnected

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Player-Symbole wie in Minimal Library (Desktop): feste Größe, damit nichts springt.
 * „f:“ = gefüllte Fläche, sonst Linie (Strich 2, runde Enden).
 */
object PlayerIcons {
    private val data = mapOf(
        "play" to listOf("f:M7 4.5v15a.8.8 0 0 0 1.2.7l12-7.5a.8.8 0 0 0 0-1.4l-12-7.5A.8.8 0 0 0 7 4.5z"),
        "pause" to listOf("f:M7 4.5h2a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z", "f:M15 4.5h2a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1z"),
        "next" to listOf("f:M5 5v14l10-7z", "M19 5v14"),
        "prev" to listOf("f:M19 5v14L9 12z", "M5 5v14"),
        "shuffle" to listOf("M3 7h3.5c2 0 3.2 1 4.3 2.6l2.4 4.8C14.3 16 15.5 17 17.5 17H21", "M3 17h3.5c1.2 0 2.1-.4 2.9-1.1", "M14.6 8.1c.8-.7 1.7-1.1 2.9-1.1H21", "m18 4 3 3-3 3", "m18 14 3 3-3 3"),
        "repeat" to listOf("m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3"),
        "repeat1" to listOf("m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3", "M11 10h1v4"),
        "timer" to listOf("M10 2h4", "M12 14l3-3", "M4 14a8 8 0 1 0 16 0a8 8 0 1 0 -16 0"),
        "list-plus" to listOf("M11 12H3", "M16 6H3", "M16 18H3", "M18 9v6", "M21 12h-6"),
        "queue" to listOf("M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13"),
        "back" to listOf("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5"),
        "forward" to listOf("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5"),
        "settings" to listOf("M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
    )
    private val cache = HashMap<String, ImageVector>()

    fun get(name: String): ImageVector? {
        cache[name]?.let { return it }
        val p = data[name] ?: return null
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d0 in p) {
            val fill = d0.startsWith("f:")
            val d = d0.removePrefix("f:")
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = if (fill) SolidColor(Color.White) else null,
                stroke = if (fill) null else SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build().also { cache[name] = it }
    }
}

@Composable
fun PIcon(name: String, size: Dp, color: Color = Ink, modifier: Modifier = Modifier) {
    val v = PlayerIcons.get(name) ?: return
    Image(v, contentDescription = name, modifier = modifier.size(size), colorFilter = ColorFilter.tint(color))
}
