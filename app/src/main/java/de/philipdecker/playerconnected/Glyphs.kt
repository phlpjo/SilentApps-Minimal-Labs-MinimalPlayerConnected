package de.philipdecker.playerconnected

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Weiße Linien-Symbole, die man Apps und Ordnern zuordnen kann */
object Glyphs {
    val names: List<String> = GlyphData.all.map { it.first }
    private val paths = GlyphData.all.toMap()
    private val cache = HashMap<String, ImageVector>()

    fun get(name: String?): ImageVector? {
        if (name == null) return null
        cache[name]?.let { return it }
        val p = paths[name] ?: return null
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in p) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build().also { cache[name] = it }
    }
}

@Composable
fun Glyph(name: String?, size: Dp, color: Color = Ink, modifier: Modifier = Modifier) {
    val v = Glyphs.get(name) ?: return
    Image(v, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** Text mit optionalem Symbol davor (Symbolgröße folgt der Schrift) */
@Composable
fun Labeled(
    text: String, icon: String?, size: TextUnit, color: Color = Ink, weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (icon == null || Glyphs.get(icon) == null) { T(text, size = size, color = color, weight = weight, maxLines = maxLines, modifier = modifier); return }
    val s = (size.value * LocalScale.current * 0.8f).dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, s, color.copy(alpha = color.alpha * 0.9f))
        Spacer(Modifier.width((s.value * 0.6f).dp))
        T(text, size = size, color = color, weight = weight, maxLines = maxLines)
    }
}
