package de.philipdecker.playerconnected

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.media3.common.C
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ───────────────────────── Start: Umschalter Musik / Hörbücher, Suche, Ordner ─────────────────────────

@Composable
fun HomeScreen(ui: Actions, prefs: Prefs, now: NowPlaying) {
    val section = prefs.section
    var query by rememberSaveable(section) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val roots = prefs.roots(section)
    val version by Library.version.collectAsState()
    val scanning by Library.scanning.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 32.dp),
    ) {
        // Einstellungen klein oben rechts, darunter der Hauptumschalter (eigene Zeilen → nie gequetscht)
        Row(Modifier.fillMaxWidth().floatIn(0)) {
            Spacer(Modifier.weight(1f))
            T(stringResource(R.string.settings), size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1,
                modifier = Modifier.tap { ui.open(Screen.Settings) }.padding(start = 12.dp, top = 10.dp, bottom = 4.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().floatIn(0)) {
            Tab(stringResource(R.string.music), section == MUSIC) { prefs.showSection(MUSIC) }
            Spacer(Modifier.width(22.dp))
            Tab(stringResource(R.string.books), section == BOOKS) { prefs.showSection(BOOKS) }
        }
        Spacer(Modifier.height(22.dp))
        if (roots.isNotEmpty()) {
            Box(Modifier.floatIn(1)) {
                SearchField(
                    query, { query = it },
                    stringResource(if (section == MUSIC) R.string.search_music else R.string.search_books),
                    focus, onGo = { keyboard?.hide() },
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { key(section) {
            when {
                roots.isEmpty() -> Column(Modifier.align(Alignment.CenterStart).floatIn(2)) {
                    val pending = prefs.pendingRoots(section)
                    if (pending.isNotEmpty()) {
                        // Ordner aus der anderen Player-App: hier nur noch Zugriff erlauben
                        T(stringResource(R.string.pending_hint, Sync.siblingName(LocalContext.current)), size = 17.sp, color = Dim)
                        Spacer(Modifier.height(10.dp))
                        pending.forEach { r ->
                            T(r.name, size = 26.sp, weight = FontWeight.Light, glow = true, maxLines = 1,
                                modifier = Modifier.tap { ui.pickFolder(section, r.uri) }.padding(vertical = 6.dp))
                        }
                        T(stringResource(R.string.pending_allow), size = 14.sp, color = Faint, maxLines = 1)
                    } else {
                        T(stringResource(if (section == MUSIC) R.string.no_folders_music else R.string.no_folders_books), size = 17.sp, color = Dim)
                        Spacer(Modifier.height(16.dp))
                        T(stringResource(R.string.choose_folder), size = 26.sp, weight = FontWeight.Light, glow = true,
                            modifier = Modifier.tap { ui.pickFolder(section) }.padding(vertical = 8.dp))
                    }
                }
                query.isNotBlank() -> SearchResults(ui, prefs, now, section, query, version)
                else -> {
                    val single = roots.singleOrNull()
                    val entries by produceState<List<Entry>?>(null, section, roots, version) {
                        value = if (single != null) Library.entries(single.uri, "")
                        else roots.map { r -> Entry.Folder(r.uri, "", r.name, 0, Library.stats(r.uri, "").n) }
                    }
                    val recent by produceState<List<Progress>>(emptyList(), section, version, now.tick) {
                        value = if (section == BOOKS) withContext(Dispatchers.IO) {
                            val uris = roots.map { it.uri }.toSet()
                            Library.dao.recent(12).filter { p -> Keys.parse(p.id.removePrefix("c|"))?.let { it.root in uris } == true }.take(3)
                        } else emptyList()
                    }
                    val playlistCount by produceState(0, version) { value = withContext(Dispatchers.IO) { Library.dao.playlists().size } }
                    val rootStats by produceState<Stats?>(null, single, version) { value = single?.let { Library.stats(it.uri, "") } }
                    entries?.let { list ->
                        EntryList(
                            ui, prefs, now, section, list, onClose = null,
                            header = {
                                val rs = rootStats
                                if (single != null && rs != null && rs.n > 0) item(key = "h:root") {
                                    T(
                                        single.name + "  ·  " + pluralStringResource(R.plurals.files_count, rs.n, rs.n) + totalTime(rs.ms).let { if (it.isEmpty()) "" else "  ·  $it" },
                                        size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1, modifier = Modifier.padding(bottom = 14.dp),
                                    )
                                }
                                if (section == MUSIC) {
                                    item(key = "h:playlists") {
                                        Row(Modifier.fillMaxWidth().tap { ui.open(Screen.Playlists) }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Labeled(stringResource(R.string.playlists), if (prefs.showIcons) "list-todo" else null, size = 24.sp, weight = FontWeight.Light)
                                            Spacer(Modifier.weight(1f))
                                            if (playlistCount > 0) T("$playlistCount", size = 14.sp, color = Faint, maxLines = 1)
                                        }
                                    }
                                    item(key = "h:music") { Spacer(Modifier.height(14.dp)); Caption(stringResource(R.string.folders), Modifier.padding(bottom = 6.dp)) }
                                }
                                if (recent.isNotEmpty()) {
                                    item(key = "h:recent") { Caption(stringResource(R.string.continue_listening), Modifier.padding(bottom = 6.dp)) }
                                    items(recent, key = { "p:" + it.id }) { p ->
                                        val key = p.id.removePrefix("c|")
                                        Column(Modifier.fillMaxWidth().tap { ui.play(key) }.padding(vertical = 9.dp)) {
                                            T(p.title, size = 24.sp, weight = FontWeight.Light, glow = now.container == key, maxLines = 2)
                                            T(stringResource(R.string.track_pos_fmt, p.idx + 1, p.count, clock(p.position)), size = 13.sp, color = Dim, spacing = 0.8.sp)
                                        }
                                    }
                                    item(key = "h:all") { Spacer(Modifier.height(18.dp)); Caption(stringResource(R.string.folders), Modifier.padding(bottom = 6.dp)) }
                                }
                            },
                            emptyText = if (scanning) stringResource(R.string.scanning) else stringResource(R.string.empty_folder),
                        )
                    }
                }
            }
            }
        }
        MiniPlayer(ui, now)
    }
}

@Composable
private fun Tab(text: String, active: Boolean, onClick: () -> Unit) {
    T(text, size = if (active) 28.sp else 22.sp, weight = FontWeight.Light, color = if (active) Ink else Faint, glow = active, maxLines = 1,
        modifier = Modifier.tap(onClick = onClick).padding(vertical = 4.dp))
}

@Composable
private fun SearchResults(ui: Actions, prefs: Prefs, now: NowPlaying, section: Int, query: String, version: Int) {
    val results by produceState<List<Entry>?>(null, section, query, version) {
        delay(120)
        value = Library.search(section, query)
    }
    results?.let { list ->
        val key = if (section == MUSIC) Keys.search(MUSIC, query) else null
        val hasFiles = list.any { it is Entry.File }
        EntryList(
            ui, prefs, now, section, list, onClose = null, fromSearch = true, searchKey = key,
            header = {
                if (key != null && hasFiles) item(key = "h:searchplay") {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(stringResource(R.string.play), size = 18.sp, maxLines = 1, modifier = Modifier.tap { ui.play(key) }.padding(vertical = 10.dp).padding(end = 22.dp))
                        T(stringResource(R.string.shuffle), size = 18.sp, color = Dim, maxLines = 1, modifier = Modifier.tap { ui.play(key, shuffle = true) }.padding(vertical = 10.dp))
                    }
                }
            },
            emptyText = stringResource(R.string.nothing_found),
        )
    }
}

// ───────────────────────── Ordneransicht ─────────────────────────

@Composable
fun FolderScreen(ui: Actions, prefs: Prefs, now: NowPlaying, s: Screen.Folder) {
    val version by Library.version.collectAsState()
    val entries by produceState<List<Entry>?>(null, s, version) { value = Library.entries(s.root, s.path) }
    val info by produceState<Dir?>(null, s, version) { value = withContext(Dispatchers.IO) { Library.dao.dir(s.root, s.path) } }
    val directFiles = info?.files ?: entries?.count { it is Entry.File } ?: 0
    val playKey = Library.containerForFolder(s.section, s.root, s.path, directFiles)
    val state by produceState<BookState?>(null, playKey, now.tick, version) {
        value = if (s.section == BOOKS) Library.bookState(playKey) else null
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 32.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        T(s.name, size = 30.sp, weight = FontWeight.Light, glow = true, maxLines = 2, modifier = Modifier.floatIn(0))
        val sub = s.path.substringBeforeLast('/', "").replace("/", "  ›  ")
        if (sub.isNotEmpty()) T(sub, size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1, modifier = Modifier.floatIn(1))
        val stats by produceState<Stats?>(null, s, version) { value = Library.stats(s.root, s.path) }
        stats?.takeIf { it.n > 0 }?.let { st ->
            T(pluralStringResource(R.plurals.files_count, st.n, st.n) + totalTime(st.ms).let { if (it.isEmpty()) "" else "  ·  $it" },
                size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1, modifier = Modifier.floatIn(1))
        }
        Spacer(Modifier.height(14.dp))
        // Abspielen-Zeile
        Row(Modifier.fillMaxWidth().floatIn(2), verticalAlignment = Alignment.CenterVertically) {
            if (s.section == BOOKS) {
                val st = state
                val p = st?.progress
                if (st?.finished == true) {
                    T(stringResource(R.string.play_again), size = 18.sp, maxLines = 1, modifier = Modifier.tap { ui.play(playKey, fromStart = true) }.padding(vertical = 10.dp).padding(end = 18.dp))
                    T("✓\u00A0" + stringResource(R.string.heard), size = 15.sp, color = Dim, maxLines = 1, modifier = Modifier.padding(vertical = 10.dp))
                } else {
                    T(
                        if (p != null) stringResource(R.string.resume) + (st?.percent?.let { "  ·  $it\u00A0%" } ?: "")
                        else stringResource(R.string.play),
                        size = 18.sp, maxLines = 1, modifier = Modifier.tap { ui.play(playKey) }.padding(vertical = 10.dp).padding(end = 18.dp),
                    )
                    if (p != null) T(stringResource(R.string.from_start), size = 15.sp, color = Dim, maxLines = 1,
                        modifier = Modifier.tap { ui.play(playKey, fromStart = true) }.padding(vertical = 10.dp))
                }
            } else {
                T(stringResource(R.string.play), size = 18.sp, modifier = Modifier.tap { ui.play(playKey) }.padding(vertical = 10.dp).padding(end = 22.dp))
                T(stringResource(R.string.shuffle), size = 18.sp, color = Dim,
                    modifier = Modifier.tap { ui.play(Keys.tree(MUSIC, s.root, s.path), shuffle = true) }.padding(vertical = 10.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            entries?.let { list ->
                EntryList(ui, prefs, now, s.section, list, onClose = { ui.back() }, emptyText = stringResource(R.string.empty_folder))
            }
        }
        MiniPlayer(ui, now)
    }
}

/**
 * Liste aus Ordnern und Dateien: 3D-Walze, beginnt in der Bildschirmmitte, Buchstabenleiste bei langen Listen,
 * ganz oben nach unten ziehen schließt. Ordner antippen = öffnen, lange drücken = Menü. Datei antippen = abspielen.
 */
@Composable
fun EntryList(
    ui: Actions, prefs: Prefs, now: NowPlaying, section: Int, entries: List<Entry>,
    onClose: (() -> Unit)?,
    header: LazyListScope.() -> Unit = {},
    fromSearch: Boolean = false,
    /** Suche (Musik): Antippen spielt die ganze Ergebnisliste als Titelliste */
    searchKey: String? = null,
    emptyText: String,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val pull = rememberPullClose { onClose?.invoke() }
    val warpScroll = rememberWarpScroll()
    var menu by remember { mutableStateOf<Entry.Folder?>(null) }
    var addTrack by remember { mutableStateOf<Track?>(null) }
    val current = now.item?.mediaId

    // Gemerkte Stellen (nur Hörbücher)
    val progress by produceState<Map<String, Progress>>(emptyMap(), entries, now.tick) {
        if (section != BOOKS) { value = emptyMap(); return@produceState }
        val ids = entries.flatMap {
            when (it) {
                is Entry.Folder -> listOf(Keys.progressOf(Keys.tree(BOOKS, it.root, it.path)))
                // Einzelne Datei als eigenes Hörbuch (direkt im gewählten Ordner): auch deren „gehört“-Stand
                is Entry.File -> listOf(Keys.progressOfFile(it.track.uri), Keys.progressOf(Library.containerFor(BOOKS, it.track)))
            }
        }.distinct()
        value = withContext(Dispatchers.IO) { ids.chunked(500).flatMap { Library.dao.progressFor(it) }.associateBy { it.id } }
    }
    val version by Library.version.collectAsState()
    // Ordner-Cover (alle Titel gleiches Cover)
    val folderCovers by produceState<Map<String, String>>(emptyMap(), entries, version, prefs.covers) { value = if (prefs.covers) Library.folderCovers(entries) else emptyMap() }
    // Spielzeit je Ordner (inkl. Unterordner)
    val durations by produceState<Map<String, Long>>(emptyMap(), entries, version) { value = Library.folderDurations(entries) }
    // Prozent nur für Ordner mit gemerkter Stelle
    val percents by produceState<Map<String, Int?>>(emptyMap(), progress) {
        value = progress.keys.filter { it.startsWith("c|") }.associateWith { Library.bookState(it.removePrefix("c|")).percent }
    }

    // Hörbücher: bereits zu Ende gehörte nach ganz unten („Bereits gehört“). Dateien innerhalb eines Buchs bleiben in Reihenfolge.
    fun heard(e: Entry): Boolean = when (e) {
        is Entry.Folder -> progress[Keys.progressOf(Keys.tree(BOOKS, e.root, e.path))]?.finished == true
        is Entry.File -> e.track.dir.isEmpty() && progress[Keys.progressOf(Library.containerFor(BOOKS, e.track))]?.finished == true
    }
    val (open, done) = remember(entries, progress, section, fromSearch) {
        if (section == BOOKS && !fromSearch) entries.partition { !heard(it) } else entries to emptyList()
    }

    // Header-Einträge zählen, damit die Buchstabenleiste richtig springt
    var headerCount by remember { mutableStateOf(0) }
    LaunchedEffect(list, entries.size, done.isEmpty()) {
        snapshotFlow { list.layoutInfo.totalItemsCount }.collect { total ->
            headerCount = (total - entries.size - (if (entries.isEmpty()) 1 else 0) - (if (done.isNotEmpty()) 1 else 0)).coerceAtLeast(0)
        }
    }
    val showBar = !fromSearch && open.size > 24

    BoxWithConstraints(Modifier.fillMaxSize().then(if (onClose != null) Modifier.pullOffset(pull) else Modifier)) {
        val half = maxHeight / 2
        val index = remember(open, headerCount) { letterIndex(open.map { it.label }, headerCount) }
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(end = if (showBar) 28.dp else 0.dp)
                .nestedScroll(if (onClose != null) pull.connection else warpScroll),
            state = list,
            contentPadding = PaddingValues(top = if (fromSearch) 14.dp else half - 40.dp, bottom = half),
        ) {
            header()
            val row: @Composable (Entry) -> Unit = { e ->
                Box(Modifier.fillMaxWidth().depth(list, e.id)) {
                    when (e) {
                        is Entry.Folder -> FolderRow(prefs, section, e, progress[Keys.progressOf(Keys.tree(BOOKS, e.root, e.path))],
                            percents[Keys.progressOf(Keys.tree(BOOKS, e.root, e.path))], durations[e.id] ?: 0L, folderCovers[e.id], fromSearch,
                            onOpen = { ui.open(Screen.Folder(section, e.root, e.path, e.name)) },
                            onLong = { menu = e })
                        is Entry.File -> FileRow(prefs, section, e.track, e.track.uri == current, progress[Keys.progressOfFile(e.track.uri)], fromSearch,
                            onLong = if (section == MUSIC) ({ addTrack = e.track }) else null) {
                            ui.play(searchKey ?: Library.playKeyFor(section, e.track), e.track.uri)
                        }
                    }
                }
            }
            items(open, key = { it.id }) { row(it) }
            if (done.isNotEmpty()) {
                item(key = "h:heard") { Caption(stringResource(R.string.already_heard), Modifier.padding(top = 22.dp, bottom = 6.dp)) }
                items(done, key = { it.id }) { row(it) }
            }
            if (entries.isEmpty()) item(key = "empty") { T(emptyText, size = 16.sp, color = Dim, modifier = Modifier.padding(vertical = 10.dp)) }
        }
        if (showBar) {
            LetterBar(index, Modifier.align(Alignment.CenterEnd)) { i -> scope.launch { list.scrollToItem(i) } }
        }
    }

    menu?.let { f -> FolderMenu(ui, section, f) { menu = null } }
    addTrack?.let { t -> AddToPlaylistSheet({ listOf(t.id) }) { addTrack = null } }
}

@Composable
private fun FolderRow(prefs: Prefs, section: Int, e: Entry.Folder, p: Progress?, percent: Int?, duration: Long, cover: String?, fromSearch: Boolean, onOpen: () -> Unit, onLong: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap(onLong = onLong, onClick = onOpen).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
    if (cover != null) CoverThumb(cover, 46.dp, Modifier.padding(end = 14.dp))
    Column(Modifier.weight(1f)) {
        Labeled(e.name, if (prefs.showIcons && cover == null) (if (section == BOOKS) "book-open" else "folder") else null, size = 24.sp, weight = FontWeight.Light, maxLines = 2)
        val detail = when {
            p != null && p.finished -> "✓\u00A0" + stringResource(R.string.heard)
            p != null -> (percent?.let { "$it\u00A0%  ·  " } ?: "") + stringResource(R.string.track_pos_fmt, p.idx + 1, p.count, clock(p.position))
            fromSearch && e.path.contains('/') -> e.path.substringBeforeLast('/').replace("/", "  ›  ")
            e.total > 0 -> pluralStringResource(R.plurals.files_count, e.total, e.total) + totalTime(duration).let { if (it.isEmpty()) "" else "  ·  $it" }
            else -> null
        }
        if (detail != null) T(detail, size = 13.sp, color = if (p != null) Dim else Faint, spacing = 0.8.sp, maxLines = 1)
    }
    }
}

@Composable
private fun FileRow(prefs: Prefs, section: Int, t: Track, playing: Boolean, p: Progress?, fromSearch: Boolean, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val cover = t.cover.takeIf { prefs.covers && it.isNotEmpty() }
    Row(Modifier.fillMaxWidth().tap(onLong = onLong, onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
    if (cover != null) CoverThumb(cover, 40.dp, Modifier.padding(end = 14.dp))
    Column(Modifier.weight(1f)) {
        Labeled(Library.label(t), if (prefs.showIcons && cover == null) "eighth-notes" else null, size = 20.sp, color = if (playing) Ink else Ink.copy(alpha = 0.88f), maxLines = 2)
        val len = if (t.duration > 0) clock(t.duration) else null
        val left = if (section == BOOKS && p != null && p.position > 0 && t.duration > 0)
            stringResource(R.string.left_fmt, clock((t.duration - p.position).coerceAtLeast(0L))) else null
        val detail = when {
            fromSearch -> listOfNotNull(t.dir.replace("/", "  ›  ").ifEmpty { null }, len).joinToString("  ·  ").ifEmpty { null }
            left != null -> "$len  ·  $left"
            p != null && p.position > 0 -> clock(p.position)
            else -> len
        }
        if (playing) T(stringResource(R.string.now_playing), size = 12.sp, color = Dim, spacing = 1.2.sp)
        else if (detail != null) T(detail, size = 13.sp, color = Faint, spacing = 0.8.sp, maxLines = 1)
    }
    }
}

@Composable
private fun FolderMenu(ui: Actions, section: Int, f: Entry.Folder, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bookKey = Keys.tree(BOOKS, f.root, f.path)
    val saved by produceState<Progress?>(null, f) {
        value = if (section == BOOKS) withContext(Dispatchers.IO) { Library.dao.progress(Keys.progressOf(bookKey)) } else null
    }
    val marks by produceState(emptyList<Bookmark>(), f) {
        value = withContext(Dispatchers.IO) { Library.dao.bookmarksIn(Keys.tree(section, f.root, f.path)) }
    }
    var showMarks by remember { mutableStateOf(false) }
    var addAll by remember { mutableStateOf(false) }
    if (addAll) { AddToPlaylistSheet({ Library.folderTrackIds(f.root, f.path) }, onDone); return }
    if (showMarks) {
        Sheet(onDismiss = onDone) {
            Caption(stringResource(R.string.bookmarks) + "  ·  " + f.name)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(marks, key = { it.id }) { b ->
                    Column(Modifier.fillMaxWidth().tap { onDone(); ui.play(b.container, b.uri, startPos = b.position) }.padding(vertical = 9.dp)) {
                        T(b.title + "  ·  " + clock(b.position), size = 16.sp, maxLines = 1)
                        if (b.note.isNotEmpty()) T(b.note, size = 14.sp, color = Dim, maxLines = 2)
                    }
                }
            }
            Option(stringResource(R.string.close), color = Dim) { onDone() }
        }
        return
    }
    Sheet(onDismiss = onDone) {
        T(f.name, size = 24.sp, weight = FontWeight.Light, glow = true, maxLines = 2)
        if (f.total > 0) T(pluralStringResource(R.plurals.files_count, f.total, f.total), size = 13.sp, color = Faint, spacing = 1.sp)
        Spacer(Modifier.height(14.dp))
        if (section == MUSIC) {
            if (f.files > 0) Option(stringResource(R.string.play)) { onDone(); ui.play(Keys.dir(MUSIC, f.root, f.path)) }
            Option(stringResource(R.string.play_sub)) { onDone(); ui.play(Keys.tree(MUSIC, f.root, f.path)) }
            Option(stringResource(R.string.shuffle_sub)) { onDone(); ui.play(Keys.tree(MUSIC, f.root, f.path), shuffle = true) }
            Option(stringResource(R.string.add_to_playlist)) { addAll = true }
        } else {
            Option(stringResource(if (saved != null && saved?.finished != true) R.string.resume else R.string.play)) { onDone(); ui.play(bookKey, fromStart = saved?.finished == true) }
            if (saved != null && saved?.finished != true) {
                Option(stringResource(R.string.from_start)) { onDone(); ui.play(bookKey, fromStart = true) }
                Option(stringResource(R.string.reset_progress), color = Dim) {
                    Library.bg.launch { withContext(Dispatchers.IO) { Library.dao.forget(Keys.progressOf(bookKey)) }; Library.version.value++ }
                    onDone()
                }
            }
        }
        if (section == BOOKS) {
            val heard = saved?.finished == true
            Option(stringResource(if (heard) R.string.mark_unheard else R.string.mark_heard), color = Dim) {
                Library.bg.launch { Library.markFinished(bookKey, !heard) }
                onDone()
            }
        }
        if (marks.isNotEmpty()) Option(stringResource(R.string.bookmarks) + "  (${marks.size})") { showMarks = true }
        Option(stringResource(R.string.open)) { onDone(); ui.open(Screen.Folder(section, f.root, f.path, f.name)) }
        Option(stringResource(R.string.cancel), color = Dim) { onDone() }
    }
}

// ───────────────────────── Mini-Player unten ─────────────────────────

/** Unten: Zeitlinie zum Ziehen (Spulen), Titel, Zeit, Play/Pause. Titel antippen öffnet den Player. */
@Composable
fun MiniPlayer(ui: Actions, now: NowPlaying) {
    val item = now.item ?: return
    val c = ui.controller ?: return
    val pos by produceState(c.currentPosition, c, item) {
        while (true) { value = c.currentPosition; delay(250) }
    }
    val dur by produceState(0L, c, item) {
        while (true) { val d = c.duration; value = if (d == C.TIME_UNSET || d < 0) 0L else d; delay(500) }
    }
    var drag by remember { mutableStateOf<Float?>(null) }
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val shown = drag?.let { (it * dur).toLong() } ?: pos
    val starsTick by Library.stars.collectAsState()
    val starred by produceState<Boolean?>(null, item.mediaId, starsTick) { value = Library.isStarred(item.mediaId) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { drag?.let { if (dur > 0) c.seekTo((it * dur).toLong()) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { ch, _ -> ch.consume(); drag = (ch.position.x / size.width).coerceIn(0f, 1f) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2f
                val x = size.width * frac
                drawLine(Line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                drawLine(Ink.copy(alpha = 0.8f), Offset(0f, y), Offset(x, y), 1.dp.toPx(), StrokeCap.Round)
                val r = if (drag != null) 6.dp.toPx() else 2.5.dp.toPx()
                if (drag != null) drawCircle(Brush.radialGradient(listOf(Ink.copy(alpha = 0.25f), Color.Transparent), Offset(x, y), r * 3f), r * 3f, Offset(x, y))
                drawCircle(Ink, r, Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).tap { ui.open(Screen.Player) }.padding(vertical = 4.dp)) {
                T(item.mediaMetadata.title?.toString() ?: "", size = 17.sp, maxLines = 1, glow = drag != null)
                T(
                    clock(shown) + (if (dur > 0) "  /  " + clock(dur) else "") +
                        (item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.let { "   ·   $it" } ?: ""),
                    size = 12.sp, color = if (drag != null) Ink else Faint, spacing = 0.8.sp, maxLines = 1,
                )
            }
            if (now.section == MUSIC && starred != null) {
                Star(starred == true, Modifier.tap { scope.launch { Library.toggleStar(item.mediaId) } }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp), 18.dp)
            }
            if (now.section == BOOKS) QuickBookmark(ui, 18.dp, Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 2.dp))
            OutputMini(ui, now, Modifier.padding(start = 16.dp))
            T(stringResource(if (now.playing) R.string.pause else R.string.play), size = 16.sp, color = if (now.playing) Ink else Dim, maxLines = 1,
                modifier = Modifier.tap { ui.toggle() }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp))
        }
    }
}

// ───────────────────────── Einstellungen ─────────────────────────

@Composable
fun SettingsScreen(ui: Actions, prefs: Prefs) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()
    val warp = LocalWarp.current
    val scanning by Library.scanning.collectAsState()
    val version by Library.version.collectAsState()
    val counts by produceState(0 to 0, version) {
        value = withContext(Dispatchers.IO) { Library.dao.count(MUSIC) to Library.dao.count(BOOKS) }
    }
    LaunchedEffect(scroll) {
        var last = scroll.value
        snapshotFlow { scroll.value }.collect { v -> warp.floatValue = (warp.floatValue - (v - last) * 0.5f).coerceIn(-90f, 90f); last = v }
    }
    val appVersion = remember {
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    val on = stringResource(R.string.on); val off = stringResource(R.string.off)
    var sheet by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<Pair<Int, Root>?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(scroll)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        T(stringResource(R.string.settings), size = 32.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.floatIn(0))

        for (s in listOf(MUSIC, BOOKS)) {
            Section(stringResource(if (s == MUSIC) R.string.music_folders else R.string.book_folders))
            prefs.roots(s).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(r.name, size = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    T("✕", size = 18.sp, color = Dim, modifier = Modifier.tap { removing = s to r }.padding(start = 14.dp))
                }
            }
            // Ordner aus der anderen Player-App ohne Zugriff in dieser App
            prefs.pendingRoots(s).forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).tap { ui.pickFolder(s, r.uri) }) {
                        T(r.name, size = 18.sp, color = Dim, maxLines = 1)
                        T(stringResource(R.string.pending_allow), size = 12.sp, color = Faint, maxLines = 1)
                    }
                    T("✕", size = 18.sp, color = Dim, modifier = Modifier.tap { prefs.removePending(s, r.uri) }.padding(start = 14.dp))
                }
            }
            Option(stringResource(R.string.add_folder), color = Dim) { ui.pickFolder(s) }
        }
        T(stringResource(R.string.sync_note, Sync.siblingName(LocalContext.current)), size = 12.sp, color = Faint, modifier = Modifier.padding(top = 6.dp))

        Section(stringResource(R.string.library))
        Toggle(stringResource(R.string.rescan), if (scanning) "…" else "") { ui.rescan() }
        T(stringResource(R.string.library_count, counts.first, counts.second), size = 14.sp, color = Faint)

        Section(stringResource(R.string.receive_section))
        Toggle(stringResource(R.string.receive_entry), "") { ui.open(Screen.Receive) }

        Section(stringResource(R.string.playback))
        Toggle(stringResource(R.string.skip_back), stringResource(R.string.seconds_fmt, prefs.back)) { prefs.cycleBack() }
        Toggle(stringResource(R.string.skip_forward), stringResource(R.string.seconds_fmt, prefs.forward)) { prefs.cycleForward() }
        Toggle(stringResource(R.string.sleep_default), stringResource(R.string.minutes_fmt, prefs.sleepMinutes)) { prefs.cycleSleep() }
        Toggle(stringResource(R.string.fade), stringResource(R.string.seconds_fmt, prefs.fade)) { prefs.cycleFade() }
        Toggle(stringResource(R.string.shake_restart), if (prefs.shake) on else off) { prefs.toggleShake() }
        Toggle(stringResource(R.string.auto_rewind), if (prefs.autoRewind) on else off) { prefs.toggleAutoRewind() }

        Section(stringResource(R.string.sound))
        Toggle(stringResource(R.string.volume), "${prefs.gain}\u00A0%") { sheet = "vol" }
        Toggle(stringResource(R.string.eq_music), eqLabel(prefs, MUSIC)) { sheet = "eq0" }
        Toggle(stringResource(R.string.eq_books), eqLabel(prefs, BOOKS)) { sheet = "eq1" }
        Toggle(stringResource(R.string.replay_gain), when (prefs.replayGain) { "track" -> stringResource(R.string.rg_track); "off" -> off; else -> stringResource(R.string.rg_album) }) { prefs.cycleReplayGain() }

        Section(stringResource(R.string.appearance))
        Toggle(stringResource(R.string.font), fontLabel(prefs.font)) { prefs.cycleFont() }
        Toggle(stringResource(R.string.font_size), stringResource(sizeLabel(prefs.fontSize))) { prefs.cycleFontSize() }
        Toggle(stringResource(R.string.stars), when (prefs.stars) { "still" -> stringResource(R.string.stars_still); "off" -> off; else -> stringResource(R.string.stars_move) }) { prefs.cycleStars() }
        Toggle(stringResource(R.string.warp), stringResource(warpLabel(prefs.warpLevel))) { prefs.cycleWarp() }
        Toggle(stringResource(R.string.covers), if (prefs.covers) on else off) { prefs.toggleCovers() }
        Toggle(
            stringResource(R.string.wave_mode),
            stringResource(when (prefs.waveMode) { "queue" -> R.string.wave_queue; "track" -> R.string.wave_track; "off" -> R.string.wave_off; else -> R.string.wave_library }),
        ) { prefs.cycleWaveMode(); Waves.modeChanged(ctx) }
        Toggle(stringResource(R.string.symbols), if (prefs.showIcons) on else off) { prefs.toggleIcons() }
        Toggle(stringResource(R.string.title_display), stringResource(if (prefs.tagTitles) R.string.title_tags else R.string.title_file)) { prefs.toggleTagTitles() }

        Section(stringResource(R.string.about))
        T(stringResource(R.string.privacy_note), size = 15.sp, color = Dim)
        Spacer(Modifier.height(6.dp))
        Toggle(stringResource(R.string.licenses), "") { ui.open(Screen.Licenses) }
        T("Minimal Player Connected $appVersion", size = 13.sp, color = Faint, spacing = 1.sp)
        val labsCtx = androidx.compose.ui.platform.LocalContext.current
        val labsUrl = stringResource(R.string.labs_url)
        Option(stringResource(R.string.more_apps)) { openLabs(labsCtx, labsUrl) }
        Option(stringResource(R.string.feedback)) { mailLabs(labsCtx, "Minimal Player Connected") }

        Spacer(Modifier.height(24.dp))
        T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
    }

    removing?.let { (sec, r) ->
        Sheet(onDismiss = { removing = null }) {
            Caption(stringResource(if (sec == MUSIC) R.string.music_folders else R.string.book_folders))
            Spacer(Modifier.height(6.dp))
            T(r.name, size = 24.sp, weight = FontWeight.Light, glow = true, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            T(stringResource(R.string.remove_folder_hint), size = 14.sp, color = Dim)
            Spacer(Modifier.height(10.dp))
            HoldOption(stringResource(R.string.remove_folder_confirm)) { ui.removeFolder(sec, r.uri); removing = null }
            Option(stringResource(R.string.cancel), color = Dim) { removing = null }
        }
    }
    when (sheet) {
        "vol" -> VolumeSheet(prefs) { sheet = null }
        "eq0" -> EqSheet(prefs, MUSIC) { sheet = null }
        "eq1" -> EqSheet(prefs, BOOKS) { sheet = null }
    }
}

@Composable
fun LicensesScreen(ui: Actions) {
    val ctx = LocalContext.current
    val texts = remember {
        listOf(
            "Space Grotesk" to R.raw.ofl_spacegrotesk,
            "JetBrains Mono" to R.raw.ofl_jetbrainsmono,
            "Lucide" to R.raw.lucide,
        ).map { (n, id) -> n to (try { ctx.resources.openRawResource(id).bufferedReader().use { it.readText() } } catch (_: Exception) { "" }) }
    }
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(scroll)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        T(stringResource(R.string.licenses), size = 32.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.floatIn(0))
        Section("AndroidX · Jetpack Compose · Media3 · Room")
        T("Copyright The Android Open Source Project.\nLicensed under the Apache License, Version 2.0 (www.apache.org/licenses/LICENSE-2.0).", size = 12.sp, color = Dim)
        Section("Guava · Kotlin")
        T("Copyright Google LLC / JetBrains s.r.o.\nLicensed under the Apache License, Version 2.0.", size = 12.sp, color = Dim)
        texts.forEach { (n, t) ->
            Section(n)
            T(t.trim(), size = 11.sp, color = Dim, spacing = 0.sp)
        }
        Spacer(Modifier.height(24.dp))
        T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
    }
}

/** „Weitere Apps“: öffnet die Website von SilentApps Minimal Labs im Browser (keine Internet-Berechtigung nötig) */
fun openLabs(ctx: android.content.Context, url: String = "https://silentapps-minimal-labs.com/de/") {
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}

/** „Fehler melden & Vorschläge“: öffnet die Mail-App mit Empfänger, Betreff und Geräteinfos */
fun mailLabs(ctx: android.content.Context, app: String) {
    val version = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    val body = "\n\n—\n$app $version · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
    val uri = android.net.Uri.parse("mailto:apps@silentapps-minimal-labs.com?subject=" + android.net.Uri.encode("$app – Feedback") + "&body=" + android.net.Uri.encode(body))
    try {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}
