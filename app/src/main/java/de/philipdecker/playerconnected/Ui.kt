package de.philipdecker.playerconnected

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.gestures.waitForUpOrCancellation

/**
 * Farben wie in Minimal: folgen dem System (hell/dunkel). Kontraste nach WCAG 2.2 gegen den Hintergrund:
 * Ink 21:1 · Dim ≥ 12:1 · Faint ≥ 7:1 (AAA) – auch der leiseste Text bleibt gut lesbar.
 */
object Palette { var dark by mutableStateOf(true) }
val Bg: Color get() = if (Palette.dark) Color.Black else Color.White
val Ink: Color get() = if (Palette.dark) Color.White else Color.Black
val Dim: Color get() = if (Palette.dark) Color(0xFFD2D2D8) else Color(0xFF38383F)
val Faint: Color get() = if (Palette.dark) Color(0xFFA8A8B1) else Color(0xFF595962)
val Panel: Color get() = if (Palette.dark) Color(0xFF101015) else Color(0xFFF3F3F6)
val Line: Color get() = if (Palette.dark) Color(0x47FFFFFF) else Color(0x47000000)

/** Text in der gewählten Schrift und Größe; glow = leichtes Leuchten (Überschriften, große Zahlen) */
@Composable
fun T(
    text: String,
    size: TextUnit = 18.sp,
    color: Color = Ink,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign = TextAlign.Start,
    glow: Boolean = false,
    spacing: TextUnit = 0.2.sp,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    modifier: Modifier = Modifier,
) = BasicText(
    text, modifier,
    style = TextStyle(
        color = color, fontSize = size * LocalScale.current, fontWeight = weight, textAlign = align,
        fontFamily = LocalFont.current, letterSpacing = spacing,
        shadow = if (glow) Shadow(color.copy(alpha = if (Palette.dark) 0.45f else 0.18f), Offset.Zero, 28f) else null,
    ),
    maxLines = maxLines,
    minLines = minLines,
    overflow = TextOverflow.Ellipsis,
)

/** Antippen ohne Welleneffekt */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tap(onLong: (() -> Unit)? = null, onClick: () -> Unit): Modifier = composed {
    combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onLongClick = onLong,
        onClick = onClick,
    )
}

/** Sanftes Einschweben (gestaffelt nach Index) */
fun Modifier.floatIn(index: Int): Modifier = composed {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(60L + index * 45L); a.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 28.dp.toPx(); scaleX = 0.96f + 0.04f * a.value; scaleY = scaleX }
}

/** Scrollen → Warp der Sterne */
@Composable
fun rememberWarpScroll(): NestedScrollConnection {
    val warp = LocalWarp.current
    return remember(warp) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                warp.floatValue = (warp.floatValue - consumed.y * 0.5f).coerceIn(-90f, 90f)
                return Offset.Zero
            }
        }
    }
}

/** 3D-Liste: Einträge kippen am Rand nach hinten weg und verblassen (wie eine Walze im All) */
fun Modifier.depth(state: LazyListState, key: Any): Modifier = graphicsLayer {
    val info = state.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return@graphicsLayer
    val half = (info.viewportEndOffset - info.viewportStartOffset) / 2f
    if (half <= 0f) return@graphicsLayer
    val center = info.viewportStartOffset + half
    val d = ((item.offset + item.size / 2f - center) / half).coerceIn(-1f, 1f)
    // auch ganz oben/unten bleibt der Eintrag gut lesbar (min. 65 % Deckkraft ≈ 7:1 Kontrast)
    alpha = (1f - 0.35f * d * d).coerceIn(0.65f, 1f)
    val s = 1f - 0.1f * abs(d)
    scaleX = s; scaleY = s
    rotationX = -d * 34f
    cameraDistance = 10f * density
    transformOrigin = TransformOrigin(0f, 0.5f)
    translationX = abs(d) * d * d * 18.dp.toPx()
}

/** Ganz oben in der Liste weiter nach unten ziehen → schließen (eine Ebene zurück) */
class PullClose(val connection: NestedScrollConnection, private val state: MutableFloatState, val closeAt: Float) {
    val pull: Float get() = state.floatValue
}

@Composable
fun rememberPullClose(onClose: () -> Unit): PullClose {
    val warpScroll = rememberWarpScroll()
    val haptic = LocalHapticFeedback.current
    val closeAt = with(LocalDensity.current) { 120.dp.toPx() }
    val pull = remember { mutableFloatStateOf(0f) }
    val close by rememberUpdatedState(onClose)
    return remember(warpScroll, closeAt) {
        PullClose(object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pull.floatValue > 0f && available.y < 0f) {
                    val c = maxOf(available.y, -pull.floatValue); pull.floatValue += c
                    return Offset(0f, c)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                warpScroll.onPostScroll(consumed, available, source)
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    val before = pull.floatValue
                    pull.floatValue += available.y * 0.55f
                    if (before < closeAt && pull.floatValue >= closeAt) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull.floatValue <= 0f) return Velocity.Zero
                if (pull.floatValue >= closeAt) { pull.floatValue = 0f; close() }
                else animate(pull.floatValue, 0f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> pull.floatValue = v }
                return available
            }
        }, pull, closeAt)
    }
}

fun Modifier.pullOffset(p: PullClose): Modifier = graphicsLayer {
    translationY = p.pull * 0.7f
    alpha = 1f - (p.pull / p.closeAt).coerceIn(0f, 1f) * 0.55f
}

/** Suchfeld mit leuchtender Linie, wie in Minimal */
@Composable
fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String, focus: FocusRequester, onGo: () -> Unit = {}) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 24.sp * LocalScale.current, fontFamily = LocalFont.current, fontWeight = FontWeight.Light),
        cursorBrush = SolidColor(Ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onGo() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        decorationBox = { field ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (query.isEmpty()) T(placeholder, size = 24.sp, color = Faint, weight = FontWeight.Light, maxLines = 1)
                        field()
                    }
                    if (query.isNotEmpty()) {
                        Box(Modifier.size(40.dp).tap { onChange(""); focus.requestFocus() }, contentAlignment = Alignment.Center) {
                            Canvas(Modifier.size(16.dp)) {
                                val w = 1.6.dp.toPx()
                                drawLine(Dim, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                                drawLine(Dim, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Ink.copy(alpha = 0.7f), Ink.copy(alpha = 0.08f), Color.Transparent))))
            }
        },
    )
}

@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .floatIn(0)
                .background(Panel, RoundedCornerShape(24.dp))
                .border(1.dp, Brush.verticalGradient(listOf(Ink.copy(alpha = 0.28f), Ink.copy(alpha = 0.04f))), RoundedCornerShape(24.dp))
                .padding(26.dp),
        ) { content() }
    }
}

@Composable
fun Option(text: String, color: Color = Ink, enabled: Boolean = true, onClick: () -> Unit) {
    T(
        text, size = 18.sp, color = if (enabled) color else Faint,
        modifier = Modifier.fillMaxWidth().tap { if (enabled) onClick() }.padding(vertical = 11.dp),
    )
}

@Composable
fun Section(title: String) {
    Spacer(Modifier.height(30.dp))
    T(title.uppercase(Locale.getDefault()), size = 11.sp, color = Faint, weight = FontWeight.Medium, spacing = 2.5.sp)
    Spacer(Modifier.height(8.dp))
}

/** Kleiner Abschnittstitel ohne großen Abstand (in Listen) */
@Composable
fun Caption(title: String, modifier: Modifier = Modifier) {
    T(title.uppercase(Locale.getDefault()), size = 11.sp, color = Faint, weight = FontWeight.Medium, spacing = 2.5.sp, modifier = modifier)
}

@Composable
fun Toggle(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().tap { onClick() }.padding(vertical = 11.dp)) {
        T(label, size = 18.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        T(value, size = 16.sp, color = Dim)
    }
}

/** Anfangsbuchstabe für die Leiste: A–Z (Umlaute zählen zum Grundbuchstaben), alles andere „#“ */
fun letterOf(label: String): Char {
    val c = fold(label.trim()).firstOrNull()?.uppercaseChar() ?: '#'
    return if (c in 'A'..'Z') c else '#'
}

/** Buchstabe → erster Listenindex (offset = Einträge davor). Fehlende Buchstaben springen zum nächsten vorhandenen. */
fun letterIndex(labels: List<String>, offset: Int = 0): List<Pair<Char, Int>> {
    val first = HashMap<Char, Int>()
    labels.forEachIndexed { i, l -> first.putIfAbsent(letterOf(l), i + offset) }
    val letters = ('A'..'Z').toList() + '#'
    return letters.map { l ->
        val i = first[l] ?: letters.drop(letters.indexOf(l)).firstNotNullOfOrNull { first[it] } ?: (labels.lastIndex + offset)
        l to i
    }
}

/** Buchstabenleiste rechts: antippen oder darüberziehen; großer Buchstabe zeigt, wo man ist */
@Composable
fun LetterBar(index: List<Pair<Char, Int>>, modifier: Modifier = Modifier, onJump: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var active by remember { mutableStateOf<Char?>(null) }
    val present = remember(index) { index.zipWithNext { a, b -> if (a.second != b.second) a.first else null }.filterNotNull().toSet() + index.last().first }
    Box(modifier.fillMaxHeight()) {
        active?.let { l ->
            T(l.toString(), size = 56.sp, weight = FontWeight.Light, modifier = Modifier.align(Alignment.Center).padding(end = 72.dp))
        }
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(0.86f)
                .width(28.dp)
                .pointerInput(index) {
                    fun pick(y: Float) {
                        val i = ((y / size.height) * index.size).toInt().coerceIn(0, index.lastIndex)
                        val (l, at) = index[i]
                        if (l != active) {
                            active = l
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onJump(at)
                        }
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume(); pick(down.position.y)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            c.consume(); pick(c.position.y)
                        }
                        active = null
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            index.forEach { (l, _) ->
                T(l.toString(), size = 11.sp, weight = FontWeight.Medium, color = if (l == active) Ink else if (l in present) Dim else Faint)
            }
        }
    }
}

/** Schieberegler im Minimal-Stil: Linie, Punkt, optionale Markierungen (0..1) */
@Composable
fun Slider(frac: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, ticks: List<Float> = emptyList(), center: Float? = null) {
    val change by rememberUpdatedState(onChange)
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { c, _ -> c.consume(); change((c.position.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) { detectTapGestures { o -> change((o.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            val x = size.width * frac.coerceIn(0f, 1f)
            val w = 1.5.dp.toPx()
            drawLine(Line, Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
            for (t in ticks) {
                val mx = size.width * t
                drawLine(Faint, Offset(mx, y - 5.dp.toPx()), Offset(mx, y + 5.dp.toPx()), 1.dp.toPx())
            }
            val from = size.width * (center ?: 0f)
            drawLine(Ink, Offset(from, y), Offset(x, y), w, StrokeCap.Round)
            drawCircle(Ink, 7.dp.toPx(), Offset(x, y))
        }
    }
}

/** Stern (nur Musik): Linie = aus, gefüllt = in der Playlist „Stern“ */
@Composable
fun Star(on: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(modifier.size(size)) {
        val p = androidx.compose.ui.graphics.Path()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f + this.size.height * 0.04f
        val outer = this.size.minDimension * 0.48f
        val inner = outer * 0.45f
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val r = if (i % 2 == 0) outer else inner
            val x = cx + (r * kotlin.math.cos(a)).toFloat()
            val y = cy + (r * kotlin.math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        if (on) drawPath(p, Ink)
        else drawPath(p, Ink.copy(alpha = 0.75f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Gesamtdauer lesbar: „1 Std. 23 Min.“ / „48 Min.“ (leer, wenn unbekannt) */
@Composable
fun totalTime(ms: Long): String {
    if (ms <= 0L) return ""
    val min = ((ms + 30_000L) / 60_000L).toInt()
    return if (min >= 60) androidx.compose.ui.res.stringResource(R.string.dur_h_m, min / 60, min % 60)
    else androidx.compose.ui.res.stringResource(R.string.dur_m, min.coerceAtLeast(1))
}

/** Kleines Cover (Liste). Reserviert den Platz sofort, damit nichts springt. */
@Composable
fun CoverThumb(hash: String, size: androidx.compose.ui.unit.Dp = 42.dp, modifier: Modifier = Modifier, zoom: Boolean = true) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, hash) { value = Covers.thumb(ctx, hash) }
    // Antippen: Cover fliegt groß in die Mitte
    Box(modifier.size(size).background(Panel, RoundedCornerShape(6.dp)).then(if (zoom) Modifier.zoomable(hash) else Modifier)) {
        img?.let {
            androidx.compose.foundation.Image(
                it, contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
            )
        }
    }
}

/** Lesezeichen-Symbol (Hörbücher): Linie; kurz gefüllt als Bestätigung */
@Composable
fun BookmarkIcon(on: Boolean, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.24f, h * 0.10f)
            lineTo(w * 0.76f, h * 0.10f)
            lineTo(w * 0.76f, h * 0.90f)
            lineTo(w * 0.50f, h * 0.68f)
            lineTo(w * 0.24f, h * 0.90f)
            close()
        }
        if (on) drawPath(p, Ink)
        else drawPath(p, Ink.copy(alpha = 0.75f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** Antippen = Lesezeichen ohne Notiz an der aktuellen Stelle; Symbol leuchtet kurz gefüllt */
@Composable
fun QuickBookmark(ui: Actions, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    var flash by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(flash) { if (flash) { delay(1200); flash = false } }
    BookmarkIcon(flash, modifier.tap {
        val c = ui.controller ?: return@tap
        val item = c.currentMediaItem ?: return@tap
        val pos = c.currentPosition
        flash = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { Library.quickBookmark(item, pos) }
    }, size)
}

/**
 * Schutz vor Fehlbedienung (wie Minimal GridCount): Aktion erst nach 1,2 s Gedrückthalten.
 * Ein Ring füllt sich; vorher loslassen → Ring läuft zurück, nichts passiert. Ersetzt jede „Wirklich …?“-Rückfrage.
 */
@Composable
fun HoldOption(text: String, hint: String = androidx.compose.ui.res.stringResource(R.string.hold_hint), millis: Int = 1200, onDone: () -> Unit) {
    val p = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    var done = false
                    val job = scope.launch {
                        p.animateTo(1f, androidx.compose.animation.core.tween(((1f - p.value) * millis).toInt().coerceAtLeast(1), easing = androidx.compose.animation.core.LinearEasing))
                        done = true
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onDone()
                    }
                    waitForUpOrCancellation()
                    if (!done) {
                        job.cancel()
                        scope.launch { p.animateTo(0f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
                    } else scope.launch { kotlinx.coroutines.delay(300); p.snapTo(0f) }
                }
            }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val w = 1.5.dp.toPx()
            drawCircle(Ink.copy(alpha = 0.18f), radius = size.minDimension / 2 - w, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            drawArc(Ink, -90f, 360f * p.value, false, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = androidx.compose.ui.geometry.Offset(w, w), size = androidx.compose.ui.geometry.Size(size.width - 2 * w, size.height - 2 * w))
        }
        Spacer(Modifier.width(14.dp))
        T(text, size = 18.sp, modifier = Modifier.weight(1f))
        T(hint, size = 13.sp, color = Faint)
    }
}
