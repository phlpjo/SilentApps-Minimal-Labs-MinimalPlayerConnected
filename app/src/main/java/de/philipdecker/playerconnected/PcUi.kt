package de.philipdecker.playerconnected

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bereich „PC“: Bibliothek aus Minimal Library – koppeln, dann Ordner, Playlisten und Suche wie unter Musik */
@Composable
fun PcArea(ui: Actions, prefs: Prefs, now: NowPlaying, query: String, version: Int) {
    val st by Pc.state.collectAsState()
    val roots = remember(version, st.loaded) { Pc.roots() }
    if (query.isNotBlank()) { SearchResults(ui, prefs, now, MUSIC, query, version, pc = true); return }
    val single = roots.singleOrNull()
    val entries by produceState<List<Entry>?>(null, roots, version) {
        value = if (single != null) Library.entries(single.uri, "")
        else roots.map { r -> Entry.Folder(r.uri, "", r.name, 0, Library.stats(r.uri, "").n) }
    }
    val playlistCount by produceState(0, version) { value = withContext(Dispatchers.IO) { Library.dao.playlists().count { Pc.isPcPlaylist(it.uid) } } }
    EntryList(
        ui, prefs, now, MUSIC, entries ?: emptyList(), onClose = null,
        header = {
            item(key = "h:pcstate") { PcStatusLine(st) }
            item(key = "h:pcplaylists") {
                Row(Modifier.fillMaxWidth().tap { ui.open(Screen.Playlists) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Labeled(stringResource(R.string.playlists), if (prefs.showIcons) "list-todo" else null, size = 24.sp, weight = FontWeight.Light)
                    Spacer(Modifier.weight(1f))
                    if (playlistCount > 0) T("$playlistCount", size = 14.sp, color = Faint, maxLines = 1)
                }
            }
            item(key = "h:pcfolders") { Spacer(Modifier.height(14.dp)); Caption(stringResource(R.string.folders), Modifier.padding(bottom = 6.dp)) }
        },
        emptyText = if (st.syncing || !st.loaded) stringResource(R.string.pc_loading) else stringResource(R.string.empty_folder),
    )
}

@Composable
private fun PcStatusLine(st: Pc.State) {
    val off by Pc.offline.collectAsState()
    Column(Modifier.padding(bottom = 14.dp)) {
        T(
            st.name + "  ·  " + stringResource(
                when {
                    st.syncing -> R.string.pc_syncing
                    st.online -> R.string.pc_online
                    else -> R.string.pc_offline
                },
            ),
            size = 13.sp, color = if (st.online) Dim else Faint, spacing = 0.8.sp, maxLines = 1,
        )
        if (!st.online && !st.syncing) T(stringResource(R.string.pc_offline_hint), size = 12.sp, color = Faint, maxLines = 2)
        st.error?.let { T(it, size = 12.sp, color = Faint, maxLines = 2) }
        if (Pc.home() == null) T(stringResource(R.string.pc_home_missing), size = 12.sp, color = Dim, maxLines = 2)
        off?.takeIf { !it.finished || it.error != null }?.let { o ->
            T(o.error ?: stringResource(R.string.pc_offline_progress_fmt, o.done, o.total), size = 12.sp, color = Dim, maxLines = 1)
        }
    }
}

/** Noch nicht gekoppelt: Computer im WLAN suchen, Code aus Minimal Library eingeben */
@Composable
fun PcPairing() {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<Pc.Found>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<Pc.Found?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val search: () -> Unit = {
        searching = true
        scope.launch { found = Pc.discover(); searching = false }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { search() }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        item { T(stringResource(R.string.pc_intro), size = 16.sp, color = Dim); Spacer(Modifier.height(16.dp)) }
        items(found ?: emptyList(), key = { it.id }) { f ->
            Column(Modifier.fillMaxWidth().tap { chosen = f; error = null }.padding(vertical = 8.dp)) {
                T(f.name, size = 26.sp, weight = FontWeight.Light, glow = true, maxLines = 1)
                T(stringResource(R.string.pc_tap_to_pair), size = 13.sp, color = Faint, maxLines = 1)
            }
        }
        item {
            if (found?.isEmpty() == true && !searching) T(stringResource(R.string.pc_none_found), size = 15.sp, color = Dim)
            error?.let { T(it, size = 15.sp, color = Ink, modifier = Modifier.padding(top = 8.dp)) }
            Option(if (searching) "…" else stringResource(R.string.pc_search_again), color = Dim) { if (!searching) search() }
        }
    }
    chosen?.let { f ->
        NameInput(stringResource(R.string.pc_code_title, f.name), "", onCancel = { chosen = null }) { code ->
            chosen = null
            scope.launch { error = Pc.pair(f, code) }
        }
    }
}

/** Zusätzliche Punkte im Ordner-Menü für Ordner vom PC */
@Composable
fun PcFolderOptions(f: Entry.Folder, onDone: () -> Unit) {
    val key = Pc.folderPin(f.root, f.path)
    val pinned = remember(f) { Pc.isPinned(key) }
    Option(stringResource(if (pinned) R.string.pc_offline_off else R.string.pc_offline_on)) { Pc.setPinned(key, !pinned); onDone() }
}

/** Ganz oben, in jedem Bildschirm: Offline-Download, Hochladen zum PC, Export */
@Composable
fun TransferLine(modifier: Modifier = Modifier) {
    val u by Pc.upload.collectAsState()
    val o by Pc.offline.collectAsState()
    val e by Library.export.collectAsState()
    val text = when {
        u != null && u?.finished == false -> stringResource(R.string.pc_upload_fmt, u!!.done, u!!.total)
        o != null && o?.finished == false -> stringResource(R.string.pc_offline_progress_fmt, o!!.done, o!!.total)
        e != null && e?.finished == false -> stringResource(R.string.export_progress_fmt, e!!.done, e!!.total)
        else -> null
    } ?: return
    T(text, size = 12.sp, color = Dim, spacing = 0.8.sp, maxLines = 1, align = androidx.compose.ui.text.style.TextAlign.Center, modifier = modifier.fillMaxWidth())
}

/** Einstellungen: Minimal Library (Computer) – verbinden, Hauptordner, entkoppeln */
@Composable
fun PcSettings(ui: Actions) {
    val st by Pc.state.collectAsState()
    val version by Library.version.collectAsState()
    Section(stringResource(R.string.pc_section))
    if (!st.paired) {
        T(stringResource(R.string.pc_settings_none), size = 15.sp, color = Dim)
        Option(stringResource(R.string.pc_connect)) { ui.open(Screen.Pair) }
        return
    }
    T(st.name, size = 18.sp, maxLines = 1)
    T(stringResource(if (st.online) R.string.pc_online else R.string.pc_offline), size = 13.sp, color = Faint)
    val homeName = remember(version, st) {
        Pc.home()?.let { h ->
            try { android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(h)).substringAfterLast(':').ifEmpty { "/" } } catch (_: Exception) { h }
        }
    }
    Toggle(stringResource(R.string.pc_home), homeName ?: stringResource(R.string.pc_home_none)) { ui.pickHome() }
    T(stringResource(R.string.pc_home_note), size = 12.sp, color = Faint)
    Option(stringResource(R.string.pc_refresh), color = Dim) { Library.bg.launch { Pc.refresh() } }
    HoldOption(stringResource(R.string.pc_unpair)) { Library.bg.launch { Pc.unpair() } }
}

/** Eigener Bildschirm zum Koppeln */
@Composable
fun PairScreen(ui: Actions) {
    val st by Pc.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(st.paired) { if (st.paired) ui.back() }
    Column(
        Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.systemBars).padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        T(stringResource(R.string.pc_connect), size = 30.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.floatIn(0))
        Box(Modifier.weight(1f)) { PcPairing() }
        T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
    }
}
