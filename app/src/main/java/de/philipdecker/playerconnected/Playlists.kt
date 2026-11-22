package de.philipdecker.playerconnected

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────── Übersicht ─────────────────────────

@Composable
fun PlaylistsScreen(ui: Actions, now: NowPlaying) {
    val version by Library.version.collectAsState()
    // „Stern“ immer zuerst
    // Bereich PC: nur Playlisten aus Minimal Library, sonst nur die eigenen
    val pcArea = Pc.state.collectAsState().value.paired
    val lists by produceState<List<PlaylistInfo>?>(null, version, pcArea) {
        val star = Library.starPlaylist(false)
        value = withContext(Dispatchers.IO) { Library.dao.playlists() }.filter { Pc.isPcPlaylist(it.uid) == pcArea }.sortedBy { if (it.id == star) 0 else 1 }
    }
    var naming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<PlaylistInfo?>(null) }
    val list = rememberLazyListState()
    val pull = rememberPullClose { ui.back() }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 32.dp),
    ) {
        SettingsLink(ui)
        T(stringResource(R.string.playlists), size = 30.sp, weight = FontWeight.Light, glow = true, maxLines = 1, modifier = Modifier.floatIn(0))
        Row(Modifier.floatIn(1)) {
            T(stringResource(R.string.new_playlist), size = 18.sp, color = Dim, maxLines = 1,
                modifier = Modifier.tap { naming = true }.padding(vertical = 12.dp).padding(end = 22.dp))
            T(stringResource(R.string.export_usb), size = 18.sp, color = Dim, maxLines = 1,
                modifier = Modifier.tap { ui.open(Screen.Export(null)) }.padding(vertical = 12.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().pullOffset(pull)) {
            val half = maxHeight / 2
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(pull.connection),
                state = list,
                contentPadding = PaddingValues(top = half - 60.dp, bottom = half),
            ) {
                items(lists ?: emptyList(), key = { it.id }) { p ->
                    Column(Modifier.fillMaxWidth().depth(list, p.id).tap(onLong = { menu = p }) {
                        ui.open(Screen.Playlist(p.id, p.name))
                    }.padding(vertical = 9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.id == Prefs.get(androidx.compose.ui.platform.LocalContext.current).starId) {
                                Star(true, Modifier.padding(end = 10.dp), 18.dp)
                            }
                            T(p.name, size = 24.sp, weight = FontWeight.Light, maxLines = 2)
                        }
                        T(pluralStringResource(R.plurals.tracks_count, p.count, p.count) + totalTime(p.duration).let { if (it.isEmpty()) "" else "  ·  $it" } +
                            (if (Pc.isPcPlaylist(p.uid) && Pc.isPinned(Pc.playlistPin(p.uid))) "  ·  " + stringResource(R.string.pc_offline_mark) else ""),
                            size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1)
                    }
                }
                if (lists?.isEmpty() == true) item(key = "empty") {
                    T(stringResource(R.string.no_playlists), size = 16.sp, color = Dim, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
        ExportStatus()
        MiniPlayer(ui, now)
    }

    val scope = rememberCoroutineScope()
    if (naming) NameInput(stringResource(R.string.new_playlist).removePrefix("+ "), "", onCancel = { naming = false }) { name ->
        naming = false
        scope.launch {
            val id: Long? = Library.createPlaylist(name, pc = pcArea)
            if (id != null) ui.open(Screen.Playlist(id, name))
        }
    }
    menu?.let { p -> PlaylistMenu(ui, p.id, p.name) { menu = null } }
}

/** Menü einer Playlist: abspielen, zufällig, umbenennen, exportieren, löschen */
@Composable
fun PlaylistMenu(ui: Actions, id: Long, name: String, onDeleted: () -> Unit = {}, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    if (renaming) {
        NameInput(stringResource(R.string.rename), name, onCancel = onDone) { n ->
            Library.bg.launch { withContext(Dispatchers.IO) { Library.dao.renamePlaylist(id, n.trim()) }; Library.version.value++; Library.pushIfPc(id) }
            onDone()
        }
        return
    }
    Sheet(onDismiss = onDone) {
        T(name, size = 24.sp, weight = FontWeight.Light, glow = true, maxLines = 2)
        Spacer(Modifier.height(14.dp))
        Option(stringResource(R.string.play)) { onDone(); ui.play(Keys.playlist(id)) }
        Option(stringResource(R.string.shuffle)) { onDone(); ui.play(Keys.playlist(id), shuffle = true) }
        Option(stringResource(R.string.rename)) { renaming = true }
        Option(stringResource(R.string.export)) { onDone(); ui.open(Screen.Export(id)) }
        val uid by produceState<String?>(null, id) { value = withContext(Dispatchers.IO) { Library.dao.playlist(id)?.uid } }
        if (Pc.isPcPlaylist(uid)) {
            val pin = Pc.playlistPin(uid!!)
            val pinned = Pc.isPinned(pin)
            Option(stringResource(if (pinned) R.string.pc_offline_off else R.string.pc_offline_on)) { Pc.setPinned(pin, !pinned); onDone() }
        }
        HoldOption(stringResource(R.string.delete)) {
            Library.bg.launch { val pc = Pc.isPcPlaylist(withContext(Dispatchers.IO) { Library.dao.playlist(id)?.uid }); withContext(Dispatchers.IO) { Library.dao.deletePlaylist(id) }; Library.stars.value++; Library.version.value++; if (pc) Pc.changed() }
            onDone(); onDeleted()
        }
        Option(stringResource(R.string.cancel), color = Dim) { onDone() }
    }
}

// ───────────────────────── Eine Playlist ─────────────────────────

@Composable
fun PlaylistScreen(ui: Actions, prefs: Prefs, now: NowPlaying, s: Screen.Playlist) {
    val version by Library.version.collectAsState()
    val tracks by produceState<List<PlaylistTrack>?>(null, s.id, version) { value = Library.playlistTracks(s.id) }
    val name by produceState(s.name, s.id, version) { value = withContext(Dispatchers.IO) { Library.dao.playlist(s.id)?.name } ?: s.name }
    val scope = rememberCoroutineScope()
    var itemMenu by remember { mutableStateOf<PlaylistTrack?>(null) }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val pull = rememberPullClose { ui.back() }
    val key = Keys.playlist(s.id)
    val current = now.item?.mediaId
    val pcState by Pc.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 32.dp),
    ) {
        SettingsLink(ui)
        T(name, size = 30.sp, weight = FontWeight.Light, glow = true, maxLines = 2, modifier = Modifier.floatIn(0).tap(onLong = { menu = true }) {})
        tracks?.let { list ->
            val ms = list.sumOf { t -> t.track.duration.coerceAtLeast(0L) }
            T(pluralStringResource(R.plurals.tracks_count, list.size, list.size) + totalTime(ms).let { if (it.isEmpty()) "" else "  ·  $it" }, size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1, modifier = Modifier.floatIn(1)) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().floatIn(2), verticalAlignment = Alignment.CenterVertically) {
            T(stringResource(R.string.play), size = 18.sp, maxLines = 1, modifier = Modifier.tap { ui.play(key) }.padding(vertical = 10.dp).padding(end = 22.dp))
            T(stringResource(R.string.shuffle), size = 18.sp, color = Dim, maxLines = 1, modifier = Modifier.tap { ui.play(key, shuffle = true) }.padding(vertical = 10.dp).padding(end = 22.dp))
            Spacer(Modifier.weight(1f))
            T("···", size = 18.sp, color = Dim, maxLines = 1, modifier = Modifier.tap { menu = true }.padding(vertical = 10.dp, horizontal = 4.dp))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().pullOffset(pull)) {
            val half = maxHeight / 2
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(pull.connection),
                state = list,
                contentPadding = PaddingValues(top = half - 60.dp, bottom = half),
            ) {
                items(tracks ?: emptyList(), key = { it.itemId }) { t ->
                    val playing = t.track.uri == current && now.container == key
                    // Titel vom PC ohne Verbindung und ohne Offline-Kopie: ausgegraut, nicht antippbar
                    val avail = !Pc.isPc(t.track.uri) || pcState.online || Pc.isOffline(t.track.uri)
                    Row(Modifier.fillMaxWidth().depth(list, t.itemId).graphicsLayer { alpha = if (avail) 1f else 0.35f }
                        .tap(onLong = { itemMenu = t }) { if (avail) ui.play(key, t.track.uri) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cover = t.track.cover.takeIf { prefs.covers && it.isNotEmpty() }
                    if (cover != null) CoverThumb(cover, 40.dp, Modifier.padding(end = 14.dp))
                    Column(Modifier.weight(1f)) {
                        Labeled(Library.label(t.track), if (prefs.showIcons && cover == null) "eighth-notes" else null, size = 20.sp, color = if (playing) Ink else Ink.copy(alpha = 0.88f))
                        val sub = listOf(t.track.dir.substringAfterLast('/'), if (t.track.duration > 0) clock(t.track.duration) else "",
                            if (Pc.isOffline(t.track.uri)) stringResource(R.string.pc_offline_mark) else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
                        if (playing) T(stringResource(R.string.now_playing), size = 12.sp, color = Dim, spacing = 1.2.sp, maxLines = 1)
                        else if (sub.isNotEmpty()) T(sub, size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1)
                    }
                    }
                }
                if (tracks?.isEmpty() == true) item(key = "empty") {
                    T(stringResource(R.string.empty_playlist), size = 16.sp, color = Dim, modifier = Modifier.padding(vertical = 10.dp))
                }
            }
        }
        ExportStatus()
        MiniPlayer(ui, now)
    }

    itemMenu?.let { t ->
        val all = tracks ?: emptyList()
        val i = all.indexOfFirst { it.itemId == t.itemId }
        Sheet(onDismiss = { itemMenu = null }) {
            T(t.track.title, size = 22.sp, weight = FontWeight.Light, glow = true, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Option(stringResource(R.string.move_up), enabled = i > 0) { Library.bg.launch { Library.movePlaylistItem(s.id, t.itemId, -1) }; itemMenu = null }
            Option(stringResource(R.string.move_down), enabled = i in 0 until all.lastIndex) { Library.bg.launch { Library.movePlaylistItem(s.id, t.itemId, 1) }; itemMenu = null }
            Option(stringResource(R.string.remove_from_playlist)) {
                Library.bg.launch { withContext(Dispatchers.IO) { Library.dao.removeItem(t.itemId) }; Library.stars.value++; Library.version.value++; Library.pushIfPc(s.id) }
                itemMenu = null
            }
            Option(stringResource(R.string.cancel), color = Dim) { itemMenu = null }
        }
    }
    if (menu) PlaylistMenu(ui, s.id, name, onDeleted = { ui.back() }) { menu = false }
}

// ───────────────────────── Hinzufügen ─────────────────────────

/**
 * „Zu Playlist hinzufügen“ mit Häkchen: mehrere Playlisten an-/abwählen, Sheet bleibt offen.
 * Schließen über „Fertig“ oder Antippen daneben. Bei Ordnern gilt das Häkchen, wenn alle Titel drin sind.
 */
@Composable
fun AddToPlaylistSheet(load: suspend () -> List<Long>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val version by Library.version.collectAsState()
    val ids by produceState<List<Long>?>(null) { value = load() }
    // Titel vom PC nur in PC-Playlisten, eigene Titel nur in eigene
    val pcTracks by produceState<Boolean?>(null) {
        value = withContext(Dispatchers.IO) { ids?.firstOrNull()?.let { Library.dao.tracksById(listOf(it)).firstOrNull()?.uri } }?.let { Pc.isPc(it) }
            ?: withContext(Dispatchers.IO) { load().firstOrNull()?.let { Library.dao.tracksById(listOf(it)).firstOrNull()?.uri } }?.let { Pc.isPc(it) } ?: false
    }
    val lists by produceState<List<PlaylistInfo>?>(null, version, pcTracks) {
        val star = Library.starPlaylist(false)
        val pc = pcTracks ?: return@produceState
        value = withContext(Dispatchers.IO) { Library.dao.playlists() }.filter { Pc.isPcPlaylist(it.uid) == pc && !(pc && Pc.isSmart(it.uid)) }.sortedBy { if (it.id == star) 0 else 1 }
    }
    val inside by produceState<Map<Long, Int>>(emptyMap(), ids, version) { value = ids?.let { Library.membership(it) } ?: emptyMap() }
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameInput(stringResource(R.string.new_playlist).removePrefix("+ "), "", onCancel = { naming = false }) { name ->
            naming = false
            scope.launch {
                val id: Long? = Library.createPlaylist(name, pc = pcTracks == true)
                if (id != null) ids?.let { Library.setInPlaylist(id, it, true) }
            }
        }
        return
    }
    val n = ids?.distinct()?.size ?: 0
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.add_to_playlist) + if (n > 1) "  ·  " + pluralStringResource(R.plurals.tracks_count, n, n) else "")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            items(lists ?: emptyList(), key = { it.id }) { p ->
                val have = inside[p.id] ?: 0
                val all = n > 0 && have >= n
                val some = have in 1 until n
                Row(
                    Modifier.fillMaxWidth().tap {
                        val list = ids ?: return@tap
                        scope.launch { Library.setInPlaylist(p.id, list, !all) }
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(p.name, size = 18.sp, color = if (all) Ink else Dim, maxLines = 1, modifier = Modifier.weight(1f))
                    if (some) T("$have/$n", size = 12.sp, color = Faint, maxLines = 1, modifier = Modifier.padding(horizontal = 10.dp))
                    Check(all, partly = some)
                }
            }
        }
        Option(stringResource(R.string.new_playlist), color = Dim) { naming = true }
        Option(stringResource(R.string.done)) { onDone() }
    }
}

/** Häkchen im Minimal-Stil: Rahmen = aus, weiß gefüllt mit schwarzem Haken = an, Strich = teilweise */
@Composable
fun Check(on: Boolean, partly: Boolean = false, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.padding(start = 6.dp).size(20.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
        val w = 1.5.dp.toPx()
        if (on) {
            drawRoundRect(Ink, cornerRadius = r)
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.26f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.70f)
                lineTo(size.width * 0.76f, size.height * 0.32f)
            }
            drawPath(p, Bg, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        } else {
            drawRoundRect(Ink.copy(alpha = 0.6f), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            if (partly) drawLine(Ink.copy(alpha = 0.8f), androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width * 0.7f, size.height / 2), 2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

/** Namen eingeben (Playlist anlegen/umbenennen) */
@Composable
fun NameInput(title: String, initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
    Sheet(onDismiss = onCancel) {
        Caption(title)
        Spacer(Modifier.height(12.dp))
        BasicTextField(
            value = name, onValueChange = { name = it.take(60) }, singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 24.sp * LocalScale.current, fontFamily = LocalFont.current, fontWeight = FontWeight.Light),
            cursorBrush = SolidColor(Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onSave(name) }),
            modifier = Modifier.fillMaxWidth().imePadding().focusRequester(focus),
        )
        Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Line))
        Spacer(Modifier.height(14.dp))
        Option(stringResource(R.string.save), enabled = name.isNotBlank()) { onSave(name) }
        Option(stringResource(R.string.cancel), color = Dim) { onCancel() }
    }
}

/** Fortschritt eines laufenden Exports (unten, dezent) */
@Composable
fun ExportStatus() {
    val e by Library.export.collectAsState()
    val x = e ?: return
    LaunchedEffect(x.finished) { if (x.finished) { delay(4000); Library.export.value = null } }
    T(
        when {
            x.failed -> stringResource(R.string.export_failed)
            x.finished -> stringResource(R.string.export_done_fmt, x.done)
            else -> stringResource(R.string.export_progress_fmt, x.done, x.total)
        },
        size = 13.sp, color = if (x.failed) Ink else Dim, spacing = 0.8.sp, maxLines = 1,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
