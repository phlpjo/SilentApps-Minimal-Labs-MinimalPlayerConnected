package de.philipdecker.playerconnected

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Name der aktuellen EQ-Einstellung eines Bereichs (für die Einstellungsliste) */
@Composable
fun eqLabel(prefs: Prefs, section: Int): String {
    val info by Fx.bands.collectAsState()
    if (!prefs.eqOn(section)) return stringResource(R.string.off)
    val i = info ?: return stringResource(R.string.on)
    val cur = prefs.eqLevels(section, i)
    return when (cur) {
        Fx.preset("flat", i) -> stringResource(R.string.eq_flat)
        Fx.preset("speech", i) -> stringResource(R.string.eq_speech)
        Fx.preset("bass", i) -> stringResource(R.string.eq_bass)
        else -> stringResource(R.string.eq_custom)
    }
}

/** Lautstärke 50–200 % für die ganze App */
@Composable
fun VolumeSheet(prefs: Prefs, onDone: () -> Unit) {
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.volume))
        Spacer(Modifier.height(8.dp))
        T("${prefs.gain}\u00A0%", size = 48.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        Slider(
            (prefs.gain - 50) / 150f,
            { f -> prefs.changeGain(((50 + f * 150) / 5f).roundToInt() * 5) },
            ticks = listOf(0f, 50 / 150f, 100 / 150f, 1f),
            center = 50 / 150f,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(50, 100, 150, 200).forEach { v ->
                T("$v\u00A0%", size = 15.sp, color = if (prefs.gain == v) Ink else Dim, modifier = Modifier.tap { prefs.changeGain(v) }.padding(vertical = 10.dp))
            }
        }
        T(stringResource(R.string.volume_hint), size = 13.sp, color = Faint)
        Spacer(Modifier.height(8.dp))
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

/** Equalizer eines Bereichs: an/aus, Voreinstellungen, je Band ein Regler */
@Composable
fun EqSheet(prefs: Prefs, section: Int, onDone: () -> Unit) {
    val info by Fx.bands.collectAsState()
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.equalizer) + "  ·  " + stringResource(if (section == BOOKS) R.string.books else R.string.music))
        Spacer(Modifier.height(6.dp))
        Toggle(stringResource(R.string.equalizer), stringResource(if (prefs.eqOn(section)) R.string.on else R.string.off)) { prefs.toggleEq(section) }
        val i = info
        if (i == null) {
            T(stringResource(R.string.eq_unavailable), size = 14.sp, color = Faint)
        } else {
            val levels = prefs.eqLevels(section, i)
            val on = prefs.eqOn(section)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("flat" to R.string.eq_flat, "speech" to R.string.eq_speech, "bass" to R.string.eq_bass).forEach { (k, res) ->
                    val p = Fx.preset(k, i)
                    T(stringResource(res), size = 14.sp, color = if (p == levels) Ink else Dim,
                        modifier = Modifier.tap { prefs.setEqLevels(section, p); if (!on) prefs.toggleEq(section) }.padding(vertical = 10.dp))
                }
            }
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                i.freqs.forEachIndexed { b, hz ->
                    val mb = levels.getOrElse(b) { 0 }
                    val span = (i.max - i.min).coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(if (hz >= 1000) "${hz / 1000}${if (hz % 1000 >= 100) "." + (hz % 1000) / 100 else ""}\u00A0kHz" else "$hz\u00A0Hz",
                            size = 13.sp, color = if (on) Dim else Faint, maxLines = 1, modifier = Modifier.width(64.dp))
                        Slider(
                            (mb - i.min).toFloat() / span,
                            { f ->
                                val v = ((i.min + f * span) / 50f).roundToInt() * 50
                                val snapped = if (abs(v) < 80) 0 else v
                                prefs.setEqLevels(section, levels.toMutableList().also { it[b] = snapped.coerceIn(i.min, i.max) })
                                if (!on) prefs.toggleEq(section)
                            },
                            modifier = Modifier.weight(1f),
                            ticks = listOf((0f - i.min) / span),
                            center = (0f - i.min) / span,
                        )
                        T((if (mb > 0) "+" else "") + "%.1f".format(mb / 100f), size = 13.sp, color = if (on) Dim else Faint,
                            maxLines = 1, modifier = Modifier.width(52.dp).padding(start = 8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}
