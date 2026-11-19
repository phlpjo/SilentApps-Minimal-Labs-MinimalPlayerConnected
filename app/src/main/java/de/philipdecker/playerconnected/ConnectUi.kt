package de.philipdecker.playerconnected

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Im Player: Symbol + wo die Musik gerade läuft („Dieses Handy“ oder Lautsprecher). Antippen → Auswahl.
 */
@Composable
fun OutputLine(ui: Actions, now: NowPlaying, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val active by Connect.active.collectAsState()
    val notice by Connect.notice.collectAsState()
    val name = active?.name ?: stringResource(R.string.this_phone)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.tap { open = true }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(if (active != null) "speaker" else "cast", 18.dp, if (active != null) Ink else Dim)
            Spacer(Modifier.width(10.dp))
            T(name, size = 15.sp, color = if (active != null) Ink else Dim, glow = active != null, maxLines = 1)
        }
        notice?.let { T(stringResource(it), size = 12.sp, color = Faint, maxLines = 2, modifier = Modifier.tap { Connect.notice.value = null }.padding(horizontal = 12.dp)) }
    }
    if (open) OutputSheet(ui, now) { open = false }
}

/** Kleines Symbol für den Mini-Player: nur sichtbar, wenn ein Lautsprecher spielt */
@Composable
fun OutputMini(ui: Actions, now: NowPlaying, modifier: Modifier = Modifier) {
    val active by Connect.active.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (active == null) return
    Glyph("speaker", 18.dp, Ink, modifier.tap { open = true })
    if (open) OutputSheet(ui, now) { open = false }
}

/** Auswahl, wo die Musik läuft – wie in Streaming-Apps: antippen, fertig */
@Composable
fun OutputSheet(ui: Actions, now: NowPlaying, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val devices by Connect.devices.collectAsState()
    val searching by Connect.searching.collectAsState()
    val active by Connect.active.collectAsState()
    val wanted by Connect.wanted.collectAsState()
    LaunchedEffect(Unit) { Connect.notice.value = null; Connect.search(ctx) }
    val list = rememberLazyListState()
    // Lautstärke beim Ziehen sofort zeigen, Gerät folgt
    var vol by remember { mutableFloatStateOf(-1f) }
    val shownVol = if (vol >= 0f) vol else now.volume / 100f

    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.output_title))
        Spacer(Modifier.height(10.dp))
        Device("cast", stringResource(R.string.this_phone), null, on = active == null, waiting = wanted == null && active != null) {
            Connect.select(null); onDone()
        }
        LazyColumn(Modifier.heightIn(max = 340.dp), state = list) {
            items(devices, key = { it.id }) { d ->
                Device(
                    "speaker", d.name, d.sub,
                    on = active?.id == d.id,
                    waiting = wanted?.id == d.id && active?.id != d.id,
                    modifier = Modifier.depth(list, d.id),
                ) { Connect.select(d); onDone() }
            }
        }
        if (searching) T(stringResource(R.string.searching_devices), size = 14.sp, color = Faint, modifier = Modifier.padding(vertical = 8.dp))
        else if (devices.isEmpty()) T(stringResource(R.string.no_devices), size = 14.sp, color = Faint, modifier = Modifier.padding(vertical = 8.dp))

        if (active != null && now.remote) {
            Spacer(Modifier.height(14.dp))
            Caption(stringResource(R.string.device_volume_fmt, (shownVol * 100).roundToInt()))
            Slider(shownVol, { f ->
                vol = f
                ui.controller?.setDeviceVolume((f * 100).roundToInt(), 0)
            })
            T(stringResource(R.string.remote_hint), size = 12.sp, color = Faint)
        }
        Spacer(Modifier.height(8.dp))
        Option(stringResource(R.string.search_again), color = Dim, enabled = !searching) { Connect.search(ctx) }
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

@Composable
private fun Device(icon: String, name: String, sub: String?, on: Boolean, waiting: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().tap(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 20.dp, if (on) Ink else Dim)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            T(name, size = 18.sp, color = if (on) Ink else Dim, glow = on, weight = if (on) FontWeight.Medium else FontWeight.Normal, maxLines = 1)
            if (!sub.isNullOrBlank()) T(sub, size = 12.sp, color = Faint, maxLines = 1)
        }
        if (on || waiting) T(if (on) "●" else "…", size = 12.sp, color = Ink, modifier = Modifier.padding(start = 10.dp))
    }
}
