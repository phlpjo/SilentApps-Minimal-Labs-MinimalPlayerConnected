package de.philipdecker.playerconnected

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cover groß ansehen (wie bei Spotify): fliegt aus dem kleinen Bild in die Bildmitte,
 * neigt sich mit dem Finger, Antippen oder Zurück fliegt es zurück.
 */
object CoverZoom {
    class Req(val hash: String, val from: Rect)
    var req by mutableStateOf<Req?>(null)
}

/** Antippen öffnet das Cover groß (Position wird für den Flug gemerkt) */
fun Modifier.zoomable(hash: String): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    this.onGloballyPositioned { bounds = it.boundsInRoot() }
        .tap { if (hash.isNotEmpty() && bounds.width > 0f) CoverZoom.req = CoverZoom.Req(hash, bounds) }
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Ebene über allem (in der Activity ganz oben einhängen) */
@Composable
fun CoverZoomLayer() {
    val r = CoverZoom.req ?: return
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val img by produceState<ImageBitmap?>(null, r.hash) { value = Covers.large(ctx, r.hash) ?: Covers.thumb(ctx, r.hash) }
    val p = remember(r) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember(r) { mutableStateOf(false) }
    var wantX by remember(r) { mutableFloatStateOf(0f) }
    var wantY by remember(r) { mutableFloatStateOf(0f) }
    val tiltX by animateFloatAsState(wantX, spring(dampingRatio = 0.55f, stiffness = 120f), label = "tx")
    val tiltY by animateFloatAsState(wantY, spring(dampingRatio = 0.55f, stiffness = 120f), label = "ty")
    LaunchedEffect(r) { p.animateTo(1f, tween(480, easing = FastOutSlowInEasing)) }
    fun close() {
        if (closing) return
        closing = true
        wantX = 0f; wantY = 0f
        scope.launch {
            p.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            if (CoverZoom.req === r) CoverZoom.req = null
        }
    }
    BackHandler { close() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val side = min(w, h) * 0.86f
        val f = p.value
        val left = lerp(r.from.left, (w - side) / 2f, f)
        val top = lerp(r.from.top, (h - side) / 2f, f)
        val cw = lerp(r.from.width, side, f)
        val ch = lerp(r.from.height, side, f)
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.78f * f))
                .pointerInput(r) { detectTapGestures { close() } },
        )
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { cw.toDp() }, with(density) { ch.toDp() })
                .graphicsLayer {
                    rotationX = tiltX
                    rotationY = tiltY
                    cameraDistance = 14f * density.density
                    shadowElevation = 40f * f
                    shape = RoundedCornerShape(10f * f + 6f * (1 - f))
                    clip = true
                }
                .pointerInput(r) {
                    // Finger bewegen = Cover neigt sich zum Finger hin; loslassen = federt zurück
                    detectDragGestures(onDragEnd = { wantX = 0f; wantY = 0f }, onDragCancel = { wantX = 0f; wantY = 0f }) { change, _ ->
                        val x = (change.position.x / size.width).coerceIn(0f, 1f) - 0.5f
                        val y = (change.position.y / size.height).coerceIn(0f, 1f) - 0.5f
                        wantY = x * 30f
                        wantX = -y * 30f
                    }
                }
                .pointerInput(r) { detectTapGestures { close() } },
        ) {
            img?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            // Lichtreflex, wandert mit der Neigung
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.18f * f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(cw * (0.5f + tiltY / 40f), ch * (0.5f - tiltX / 40f)),
                        radius = cw * 0.7f,
                    ),
                ),
            )
        }
    }
}
