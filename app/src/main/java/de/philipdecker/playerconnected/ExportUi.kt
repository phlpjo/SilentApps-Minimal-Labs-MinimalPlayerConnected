package de.philipdecker.playerconnected

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Playlisten auf einen USB-Stick – gleicher Aufbau wie „Playlisten exportieren“ in Minimal Library */
@Composable
fun ExportScreen(ui: Actions, s: Screen.Export) {
    val version by Library.version.collectAsState()
    val lists by produceState<List<PlaylistInfo>>(emptyList(), version) { value = withContext(Dispatchers.IO) { Library.dao.playlists() } }
    val chosen = remember { mutableStateListOf<Long>().apply { s.preselect?.let { add(it) } } }
    var layout by remember { mutableStateOf("numbered") }
    var hint by remember { mutableStateOf<String?>(null) }
    val running by Library.export.collectAsState()
    val scroll = rememberScrollState()
    val layouts = listOf(
        Triple("numbered", R.string.layout_numbered, R.string.layout_numbered_note),
        Triple("flat", R.string.layout_flat, R.string.layout_flat_note),
        Triple("structured", R.string.layout_structured, R.string.layout_structured_note),
    )

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(scroll)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        T(stringResource(R.string.export_usb), size = 30.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.floatIn(0))
        T(stringResource(R.string.export_hint), size = 14.sp, color = Dim, modifier = Modifier.padding(top = 8.dp))

        Section(stringResource(R.string.playlists))
        lists.forEach { p ->
            val on = p.id in chosen
            Row(Modifier.fillMaxWidth().tap { if (on) chosen.remove(p.id) else chosen.add(p.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                T(if (on) "✓" else "○", size = 18.sp, color = if (on) Ink else Faint, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    T(p.name, size = 18.sp, maxLines = 1)
                    T(pluralStringResource(R.plurals.tracks_count, p.count, p.count) + if (Pc.isPcPlaylist(p.uid)) "  ·  PC" else "", size = 12.sp, color = Faint, maxLines = 1)
                }
            }
        }
        if (lists.isEmpty()) T(stringResource(R.string.no_playlists), size = 15.sp, color = Dim)

        Section(stringResource(R.string.export_layout))
        layouts.forEach { (k, label, note) ->
            Column(Modifier.fillMaxWidth().tap { layout = k }.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(if (layout == k) "●" else "○", size = 16.sp, color = if (layout == k) Ink else Faint, modifier = Modifier.width(28.dp))
                    T(stringResource(label), size = 18.sp)
                }
                T(stringResource(note), size = 12.sp, color = Faint, modifier = Modifier.padding(start = 28.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        val busy = running?.finished == false
        val none = stringResource(R.string.export_none)
        Option(stringResource(R.string.export_choose), enabled = !busy) {
            if (chosen.isEmpty()) hint = none
            else { hint = null; ui.exportTo(chosen.toList(), layout) }
        }
        hint?.let { T(it, size = 14.sp, color = Ink) }
        ExportStatus()

        Spacer(Modifier.height(24.dp))
        T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
    }
}
