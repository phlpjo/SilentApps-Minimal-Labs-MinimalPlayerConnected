package de.philipdecker.playerconnected

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import kotlin.math.roundToInt

@Composable
fun PlayerScreen(ui: Actions, prefs: Prefs, now: NowPlaying) {
    val ctx = LocalContext.current
    val c = ui.controller
    val item = now.item
    val section = now.section
    var speedSheet by remember { mutableStateOf(false) }
    var sleepSheet by remember { mutableStateOf(false) }
    var chapterSheet by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var markSheet by remember { mutableStateOf(false) }
    var playlistSheet by remember { mutableStateOf(false) }
    var marksTick by remember { mutableIntStateOf(0) }
    val sleepEnd by Sleep.endAt.collectAsState()
    val endOfFile by Sleep.endOfFile.collectAsState()
    val clockNow by produceState(System.currentTimeMillis(), sleepEnd) {
        while (sleepEnd != null) { value = System.currentTimeMillis(); delay(1000) }
    }
    var drag by remember { mutableFloatStateOf(0f) }
    val id = item?.mediaId
    val pos by produceState(c?.currentPosition ?: 0L, c, id) {
        while (c != null) { value = c.currentPosition; delay(250) }
    }
    val dur by produceState(0L, c, id) {
        while (c != null) { val d = c.duration; value = if (d == C.TIME_UNSET || d < 0) 0L else d; delay(500) }
    }
    val chapters by produceState(emptyList<Chapter>(), id) { value = if (id != null) Chapters.of(ctx, id) else emptyList() }
    val ci = Chapters.indexAt(chapters, pos)
    val starsTick by Library.stars.collectAsState()
    val starred by produceState<Boolean?>(null, id, starsTick) { value = if (id != null) Library.isStarred(id) else null }
    val scope = rememberCoroutineScope()
    val marks by produceState(emptyList<Bookmark>(), id, marksTick, starsTick) {
        value = if (id != null) withContext(Dispatchers.IO) { Library.dao.bookmarksOf(id) } else emptyList()
    }

    val coverHash by produceState<String?>(null, id) { value = if (id != null) withContext(Dispatchers.IO) { Library.dao.coverOf(id) } else null }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            // Nach unten wischen schließt den Player
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag > 140.dp.toPx()) ui.back(); drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, d -> drag += d }
            }
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        if (item == null || c == null) {
            Spacer(Modifier.weight(1f))
            T(stringResource(R.string.nothing_playing), size = 18.sp, color = Dim, modifier = Modifier.floatIn(0))
            Spacer(Modifier.weight(1f))
            T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
            return@Column
        }
        val md = item.mediaMetadata
        // Cover füllt den freien Raum oben und ist vor „Musik · 10 / 20“ schon ganz ausgeblendet
        if (prefs.covers && !coverHash.isNullOrEmpty()) CoverArea(coverHash!!, Modifier.weight(2.2f).fillMaxWidth())
        else Spacer(Modifier.weight(1f))
        Caption(
            stringResource(if (section == BOOKS) R.string.books else R.string.music) +
                (if (now.count > 1) "   ·   " + stringResource(R.string.of_fmt, now.index + 1, now.count) else ""),
            Modifier.floatIn(0),
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().floatIn(1), verticalAlignment = Alignment.Top) {
            // Immer drei Zeilen Platz: kurze und lange Titel verschieben das Layout nicht; länger → „…“
            T(md.title?.toString() ?: "", size = 30.sp, weight = FontWeight.Light, glow = true, maxLines = 3, minLines = 3, modifier = Modifier.weight(1f))
            if (section == MUSIC && starred != null) {
                Star(starred == true, Modifier.tap { scope.launch { Library.toggleStar(item.mediaId) } }.padding(start = 14.dp, top = 8.dp), 26.dp)
            }
            if (section == BOOKS) QuickBookmark(ui, 26.dp, Modifier.padding(start = 14.dp, top = 8.dp))
        }
        Spacer(Modifier.height(6.dp))
        if (ci >= 0) {
            T(chapters[ci].title, size = 18.sp, maxLines = 1, modifier = Modifier.floatIn(2).tap { chapterSheet = true })
            T(stringResource(R.string.chapter_fmt, ci + 1, chapters.size), size = 12.sp, color = Faint, spacing = 1.sp, modifier = Modifier.floatIn(2))
            Spacer(Modifier.height(4.dp))
        }
        val where = listOfNotNull(md.albumTitle?.toString(), md.artist?.toString()).distinct().filter { it.isNotBlank() && it != md.title?.toString() }.joinToString("  ·  ")
        T(where, size = 15.sp, color = Dim, maxLines = 1, minLines = 1, modifier = Modifier.floatIn(2))
        Spacer(Modifier.height(36.dp))

        SeekBar(c, pos, dur, if (dur > 0) chapters.drop(1).map { it.start.toFloat() / dur } else emptyList())

        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth().floatIn(4), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T("−${prefs.back}", size = 22.sp, weight = FontWeight.Light,
                modifier = Modifier.tap { c.seekTo((c.currentPosition - prefs.back * 1000L).coerceAtLeast(0L)) }.padding(vertical = 12.dp, horizontal = 4.dp))
            T(stringResource(if (now.playing) R.string.pause else R.string.play), size = 34.sp, weight = FontWeight.Light, glow = true,
                align = TextAlign.Center, modifier = Modifier.tap { ui.toggle() }.padding(12.dp))
            T("+${prefs.forward}", size = 22.sp, weight = FontWeight.Light,
                modifier = Modifier.tap {
                    val d = c.duration
                    val target = c.currentPosition + prefs.forward * 1000L
                    c.seekTo(if (d != C.TIME_UNSET && d > 0) target.coerceAtMost(d - 500L) else target)
                }.padding(vertical = 12.dp, horizontal = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        // Zurück/Weiter: bei Kapiteln kapitelweise, sonst dateiweise
        Row(Modifier.fillMaxWidth().floatIn(5), horizontalArrangement = Arrangement.SpaceBetween) {
            T(stringResource(R.string.previous), size = 16.sp, color = Dim, modifier = Modifier.tap {
                val p = c.currentPosition
                val i = Chapters.indexAt(chapters, p)
                when {
                    i >= 0 && p - chapters[i].start > 3000L -> c.seekTo(chapters[i].start)
                    i > 0 -> c.seekTo(chapters[i - 1].start)
                    p > 3000L || !c.hasPreviousMediaItem() -> c.seekTo(0L)
                    else -> c.seekToPreviousMediaItem()
                }
            }.padding(vertical = 10.dp))
            val hasNext = (ci >= 0 && ci < chapters.lastIndex) || c.hasNextMediaItem()
            T(stringResource(R.string.next), size = 16.sp, color = if (hasNext) Dim else Faint, modifier = Modifier.tap {
                val i = Chapters.indexAt(chapters, c.currentPosition)
                when {
                    i >= 0 && i < chapters.lastIndex -> c.seekTo(chapters[i + 1].start)
                    c.hasNextMediaItem() -> c.seekToNextMediaItem()
                }
            }.padding(vertical = 10.dp))
        }

        Spacer(Modifier.weight(0.7f))
        // Kompakte Zeilen: Bezeichnung klein, Wert darunter – antippen ändert/öffnet. Je Zeile drei feste Spalten.
        val sleepValue = when {
            sleepEnd != null -> clock((sleepEnd!! - clockNow).coerceAtLeast(0L))
            endOfFile -> stringResource(R.string.chip_eof)
            else -> stringResource(R.string.off)
        }
        val queueValue = if (now.count > 0) stringResource(R.string.of_fmt, now.index + 1, now.count) else "–"
        val chapterValue = "${(ci + 1).coerceAtLeast(1)}\u00A0/\u00A0${chapters.size}"
        if (section == BOOKS) {
            Row(Modifier.fillMaxWidth().floatIn(6)) {
                Chip(stringResource(R.string.chip_speed), speedLabel(now.speed), Modifier.weight(1f)) { speedSheet = true }
                Chip(stringResource(R.string.chip_sleep), sleepValue, Modifier.weight(1f)) { sleepSheet = true }
                Chip(stringResource(R.string.chip_marks), if (marks.isEmpty()) "+" else "${marks.size}", Modifier.weight(1f)) { markSheet = true }
            }
            Row(Modifier.fillMaxWidth().floatIn(7)) {
                if (chapters.isNotEmpty()) Chip(stringResource(R.string.chip_chapters), chapterValue, Modifier.weight(1f)) { chapterSheet = true }
                if (now.count > 1) Chip(stringResource(R.string.chip_files), queueValue, Modifier.weight(1f)) { queueSheet = true }
                Spacer(Modifier.weight(if (chapters.isNotEmpty() && now.count > 1) 1f else if (chapters.isNotEmpty() || now.count > 1) 2f else 3f))
            }
        } else {
            Row(Modifier.fillMaxWidth().floatIn(6)) {
                Chip(stringResource(R.string.chip_shuffle), stringResource(if (now.shuffle) R.string.on else R.string.off), Modifier.weight(1f)) { c.shuffleModeEnabled = !c.shuffleModeEnabled }
                Chip(
                    stringResource(R.string.chip_repeat),
                    stringResource(when (now.repeat) { Player.REPEAT_MODE_ALL -> R.string.repeat_all; Player.REPEAT_MODE_ONE -> R.string.repeat_one; else -> R.string.off }),
                    Modifier.weight(1f),
                ) {
                    c.repeatMode = when (c.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }
                }
                Chip(stringResource(R.string.chip_sleep), sleepValue, Modifier.weight(1f)) { sleepSheet = true }
            }
            Row(Modifier.fillMaxWidth().floatIn(7)) {
                Chip(stringResource(R.string.chip_queue), queueValue, Modifier.weight(1f)) { queueSheet = true }
                Chip(stringResource(R.string.chip_playlist), "+", Modifier.weight(1f)) { playlistSheet = true }
                if (chapters.isNotEmpty()) Chip(stringResource(R.string.chip_chapters), chapterValue, Modifier.weight(1f)) { chapterSheet = true }
                else Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        // Wo die Musik läuft: Handy oder Lautsprecher im Heimnetz
        OutputLine(ui, now, Modifier.align(Alignment.CenterHorizontally))
        T(stringResource(R.string.back), size = 15.sp, color = Faint, modifier = Modifier.align(Alignment.CenterHorizontally).tap { ui.back() }.padding(12.dp))
    }
    }

    if (speedSheet) SpeedSheet(ui, prefs, now) { speedSheet = false }
    if (sleepSheet) SleepSheet(prefs, sleepEnd, endOfFile, clockNow) { sleepSheet = false }
    if (playlistSheet && id != null) AddToPlaylistSheet({ listOfNotNull(withContext(Dispatchers.IO) { Library.dao.byUri(id) }?.id) }) { playlistSheet = false }
    if (queueSheet && c != null) QueueSheet(c, now) { queueSheet = false }
    if (markSheet && section == BOOKS && c != null && item != null) BookmarkSheet(c, item, pos, marks, { marksTick++ }) { markSheet = false }
    if (chapterSheet && c != null) ChapterSheet(chapters, ci, { c.seekTo(it.start); if (!c.playWhenReady) ui.toggle() }) { chapterSheet = false }
}

/** Kapitelliste einer Datei, aktuelles Kapitel hervorgehoben */
@Composable
private fun ChapterSheet(chapters: List<Chapter>, current: Int, onPick: (Chapter) -> Unit, onDone: () -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.chapters) + "  ·  ${chapters.size}")
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp), state = list) {
            itemsIndexed(chapters, key = { i, _ -> i }) { i, ch ->
                Row(Modifier.fillMaxWidth().depth(list, i).tap { onPick(ch); onDone() }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    T(ch.title, size = 17.sp, color = if (i == current) Ink else Dim, glow = i == current, maxLines = 2, modifier = Modifier.weight(1f))
                    T(clock(ch.start), size = 13.sp, color = Faint, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

/** Fortschrittslinie: antippen oder ziehen zum Springen; Kapitel als Striche; Zeiten darunter */
@Composable
private fun SeekBar(c: Player, pos: Long, dur: Long, marks: List<Float>) {
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    val frac = dragFrac ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val shown = dragFrac?.let { (it * dur).toLong() } ?: pos

    Column(Modifier.fillMaxWidth().floatIn(3)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragFrac?.let { if (dur > 0) c.seekTo((it * dur).toLong()) }; dragFrac = null },
                        onDragCancel = { dragFrac = null },
                    ) { change, _ -> change.consume(); dragFrac = (change.position.x / size.width).coerceIn(0f, 1f) }
                }
                .pointerInput(dur) {
                    detectTapGestures { o -> if (dur > 0) c.seekTo(((o.x / size.width).coerceIn(0f, 1f) * dur).toLong()) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2f
                val x = size.width * frac
                val w = 1.5.dp.toPx()
                drawLine(Line, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                for (m in marks) {
                    val mx = size.width * m
                    drawLine(Faint, Offset(mx, y - 4.dp.toPx()), Offset(mx, y + 4.dp.toPx()), 1.dp.toPx())
                }
                drawLine(Ink.copy(alpha = 0.9f), Offset(0f, y), Offset(x, y), w, StrokeCap.Round)
                val r = if (dragFrac != null) 7.dp.toPx() else 4.5.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(Ink.copy(alpha = 0.22f), Color.Transparent), Offset(x, y), r * 3.2f), r * 3.2f, Offset(x, y))
                drawCircle(Ink, r, Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            T(clock(shown), size = 13.sp, color = Dim, spacing = 0.8.sp)
            Spacer(Modifier.weight(1f))
            if (dur > 0) T("−" + clock(dur - shown), size = 13.sp, color = Faint, spacing = 0.8.sp)
        }
    }
}

/** Tempo stufenlos 1,0× bis 2,5× (Tonhöhe bleibt natürlich) */
@Composable
private fun SpeedSheet(ui: Actions, prefs: Prefs, now: NowPlaying, onDone: () -> Unit) {
    fun set(v: Float) {
        val s = ((v * 20f).roundToInt() / 20f).coerceIn(1f, 2.5f)
        prefs.changeBookSpeed(s)
        ui.controller?.setPlaybackSpeed(s)
    }
    val value = if (now.section == BOOKS) now.speed else prefs.bookSpeed
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.speed))
        Spacer(Modifier.height(8.dp))
        T(speedLabel(value), size = 48.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ -> change.consume(); set(1f + 1.5f * (change.position.x / size.width).coerceIn(0f, 1f)) }
                }
                .pointerInput(Unit) { detectTapGestures { o -> set(1f + 1.5f * (o.x / size.width).coerceIn(0f, 1f)) } },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height / 2f
                val f = ((value - 1f) / 1.5f).coerceIn(0f, 1f)
                val x = size.width * f
                drawLine(Line, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), StrokeCap.Round)
                drawLine(Ink, Offset(0f, y), Offset(x, y), 1.5.dp.toPx(), StrokeCap.Round)
                // Markierungen bei 1,0 / 1,5 / 2,0 / 2,5
                for (i in 0..3) {
                    val mx = size.width * i / 3f
                    drawLine(Faint, Offset(mx, y - 5.dp.toPx()), Offset(mx, y + 5.dp.toPx()), 1.dp.toPx())
                }
                drawCircle(Ink, 7.dp.toPx(), Offset(x, y))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { v ->
                T(speedLabel(v).removeSuffix("×"), size = 15.sp, color = if (kotlin.math.abs(v - value) < 0.01f) Ink else Dim,
                    modifier = Modifier.tap { set(v) }.padding(vertical = 10.dp, horizontal = 2.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        T(stringResource(R.string.speed_hint), size = 13.sp, color = Faint)
        Spacer(Modifier.height(8.dp))
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

@Composable
private fun SleepSheet(prefs: Prefs, end: Long?, endOfFile: Boolean, nowMs: Long, onDone: () -> Unit) {
    val def = stringResource(R.string.default_mark)
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.sleep))
        Spacer(Modifier.height(8.dp))
        if (end != null) {
            T(clock((end - nowMs).coerceAtLeast(0L)), size = 48.sp, weight = FontWeight.Light, glow = true, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(10.dp))
            Option(stringResource(R.string.add_5)) { Sleep.extend(5) }
            Option(stringResource(R.string.turn_off)) { Sleep.cancel(); onDone() }
        } else {
            if (endOfFile) Option(stringResource(R.string.turn_off)) { Sleep.cancel(); onDone() }
            listOf(15, 30, 45, 60, 90).forEach { m ->
                val label = stringResource(R.string.minutes_fmt, m) + if (m == prefs.sleepMinutes) "  ·  $def" else ""
                Option(label, color = if (m == prefs.sleepMinutes) Ink else Dim) { Sleep.start(m); onDone() }
            }
            Option(stringResource(R.string.end_of_file), color = if (endOfFile) Ink else Dim) { Sleep.startEndOfFile(); onDone() }
        }
        Toggle(stringResource(R.string.shake_restart), stringResource(if (prefs.shake) R.string.on else R.string.off)) { prefs.toggleShake() }
        T(stringResource(R.string.sleep_hint), size = 13.sp, color = Faint)
        Spacer(Modifier.height(8.dp))
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

/** Kleine Einstellung im Player: Bezeichnung oben, Wert darunter */
@Composable
private fun Chip(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.tap(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp)) {
        T(label.uppercase(java.util.Locale.getDefault()), size = 10.sp, color = Faint, weight = FontWeight.Medium, spacing = 1.4.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        T(value, size = 16.sp, maxLines = 1)
    }
}

/** Warteschlange: was als Nächstes kommt (berücksichtigt Zufall) */
@Composable
private fun QueueSheet(c: Player, now: NowPlaying, onDone: () -> Unit) {
    val order = remember(now.index, now.count, now.shuffle, now.repeat) {
        val tl = c.currentTimeline
        val out = ArrayList<Int>()
        if (!tl.isEmpty) {
            // Ab dem nächsten Titel – der laufende steht nicht in der Liste
            var i = tl.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && out.size < 500) {
                out += i
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        out
    }
    val list = rememberLazyListState()
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.up_next) + if (now.shuffle) "  ·  " + stringResource(R.string.shuffle) else "")
        Spacer(Modifier.height(8.dp))
        if (order.isEmpty()) T(stringResource(R.string.nothing_next), size = 15.sp, color = Faint, modifier = Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 440.dp), state = list) {
            items(order, key = { it }) { idx ->
                val mi: MediaItem? = try { c.getMediaItemAt(idx) } catch (_: Exception) { null }
                val cur = false
                Column(Modifier.fillMaxWidth().depth(list, idx).tap { c.seekTo(idx, 0L); if (!c.playWhenReady) c.play() }.padding(vertical = 9.dp)) {
                    T(mi?.mediaMetadata?.title?.toString() ?: "", size = 17.sp, color = if (cur) Ink else Dim, glow = cur, maxLines = 1)
                    val sub = mi?.mediaMetadata?.artist?.toString()
                    if (!sub.isNullOrBlank()) T(sub, size = 12.sp, color = Faint, maxLines = 1)
                }
            }
        }
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

/** Lesezeichen der aktuellen Datei: hinzufügen (mit Notiz), anspringen, löschen */
@Composable
private fun BookmarkSheet(c: Player, item: MediaItem, pos: Long, marks: List<Bookmark>, onChanged: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf<Long?>(null) }
    var note by remember { mutableStateOf("") }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var editNote by remember { mutableStateOf("") }
    editing?.let { b ->
        Sheet(onDismiss = { editing = null }) {
            Caption(stringResource(R.string.bookmarks) + "  ·  " + clock(b.position))
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = editNote, onValueChange = { editNote = it.take(200) },
                textStyle = TextStyle(color = Ink, fontSize = 18.sp * LocalScale.current, fontFamily = LocalFont.current),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { f -> Box { if (editNote.isEmpty()) T(stringResource(R.string.note_hint), size = 18.sp, color = Faint); f() } },
            )
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Line))
            Spacer(Modifier.height(10.dp))
            Option(stringResource(R.string.save)) {
                val n = editNote.trim()
                Library.bg.launch { withContext(Dispatchers.IO) { Library.dao.setBookmarkNote(b.id, n) }; onChanged() }
                editing = null
            }
            HoldOption(stringResource(R.string.delete)) {
                Library.bg.launch { withContext(Dispatchers.IO) { Library.dao.deleteBookmark(b.id) }; onChanged() }
                editing = null
            }
            Option(stringResource(R.string.cancel), color = Dim) { editing = null }
        }
        return
    }
    Sheet(onDismiss = onDone) {
        Caption(stringResource(R.string.bookmarks))
        Spacer(Modifier.height(6.dp))
        val at = adding
        if (at != null) {
            T(clock(at), size = 28.sp, weight = FontWeight.Light, glow = true)
            Spacer(Modifier.height(8.dp))
            BasicTextField(
                value = note, onValueChange = { note = it.take(200) },
                textStyle = TextStyle(color = Ink, fontSize = 18.sp * LocalScale.current, fontFamily = LocalFont.current),
                cursorBrush = SolidColor(Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { f -> Box { if (note.isEmpty()) T(stringResource(R.string.note_hint), size = 18.sp, color = Faint); f() } },
            )
            androidx.compose.runtime.LaunchedEffect(Unit) { delay(100); focus.requestFocus() }
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(1.dp).background(Line))
            Spacer(Modifier.height(10.dp))
            Option(stringResource(R.string.save)) {
                val ex = item.mediaMetadata.extras
                val b = Bookmark(
                    uri = item.mediaId, container = ex?.getString("container") ?: "",
                    title = item.mediaMetadata.title?.toString() ?: "", position = at, note = note.trim(), created = System.currentTimeMillis(),
                )
                scope.launch { withContext(Dispatchers.IO) { Library.dao.addBookmark(b) }; onChanged() }
                adding = null; note = ""
            }
            Option(stringResource(R.string.cancel), color = Dim) { adding = null; note = "" }
        } else {
            Option(stringResource(R.string.add_bookmark_fmt, clock(pos))) { adding = c.currentPosition }
            if (marks.isEmpty()) T(stringResource(R.string.no_bookmarks), size = 14.sp, color = Faint, modifier = Modifier.padding(vertical = 6.dp))
            else T(stringResource(R.string.bookmark_hint), size = 12.sp, color = Faint, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(marks, key = { it.id }) { b ->
                    // Tippen = Sprung, lange drücken = Notiz bearbeiten / löschen
                    Row(Modifier.fillMaxWidth().tap(onLong = { editing = b; editNote = b.note }) {
                        c.seekTo(b.position); if (!c.playWhenReady) c.play()
                    }.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        T(clock(b.position), size = 16.sp, maxLines = 1, modifier = Modifier.width(78.dp))
                        T(b.note.ifEmpty { "—" }, size = 15.sp, color = Dim, maxLines = 2, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

/**
 * Cover im freien Raum über dem Titel: randlos (über den seitlichen Rand und die Statusleiste hinaus),
 * unten vollständig nach Schwarz ausgeblendet – überlagert nie Text. Beim Titelwechsel weich überblendet.
 */
@Composable
private fun CoverArea(hash: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val top = WindowInsets.systemBars.getTop(density) + with(density) { 24.dp.roundToPx() }
    val side = with(density) { 32.dp.roundToPx() }
    val img by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, hash) { value = Covers.large(ctx, hash) }
    Box(
        modifier.layout { m, c ->
            // nach links/rechts/oben über das Polster hinaus zeichnen
            val w = c.maxWidth + 2 * side
            val h = c.maxHeight + top
            val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h.coerceAtLeast(0)))
            layout(c.maxWidth, c.maxHeight) { p.place(-side, -top) }
        },
    ) {
        androidx.compose.animation.Crossfade(targetState = img, animationSpec = androidx.compose.animation.core.tween(600), label = "cover") { b ->
            if (b != null) Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.Image(
                    b, contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Bg.copy(alpha = 0.35f),
                            0.15f to Color.Transparent,
                            0.45f to Color.Transparent,
                            0.85f to Bg.copy(alpha = 0.9f),
                            1f to Bg,
                        ),
                    ),
                )
            }
        }
    }
}
