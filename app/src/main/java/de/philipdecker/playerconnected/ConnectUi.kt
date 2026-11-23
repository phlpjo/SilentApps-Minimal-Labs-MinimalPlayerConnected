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
            Glyph(if (active?.pc == true) "laptop" else if (active != null) "speaker" else "cast", 18.dp, if (active != null) Ink else Dim)
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
    Glyph(if (active?.pc == true) "laptop" else "speaker", 18.dp, Ink, modifier.tap { open = true })
    if (open) OutputSheet(ui, now) { open = false }
}

/** Ein Ausgang in der Auswahl – jeder genau einmal, egal ob Handy oder PC ihn gefunden hat */
private class OutRow(val key: String, val name: String, val sub: String?, val icon: String, val select: () -> Unit, val on: Boolean, val busy: Session?)

/**
 * Wo läuft was – wie in Streaming-Apps, für den ganzen Haushalt:
 * oben alle anderen Wiedergaben (PC-Tabs, Handys) live und steuerbar, darunter jeder Ausgang einmal.
 * Belegte Ausgänge übernimmt man durch Gedrückthalten; die andere Wiedergabe pausiert.
 */
@Composable
fun OutputSheet(ui: Actions, now: NowPlaying, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val devices by Connect.devices.collectAsState()
    val searching by Connect.searching.collectAsState()
    val active by Connect.active.collectAsState()
    val wanted by Connect.wanted.collectAsState()
    val house by PcRemote.house.collectAsState()
    LaunchedEffect(Unit) { Connect.notice.value = null; Connect.search(ctx) }
    val list = rememberLazyListState()
    var arming by remember { mutableStateOf<String?>(null) }
    // Lautstärke beim Ziehen sofort zeigen, Gerät folgt
    var vol by remember { mutableFloatStateOf(-1f) }
    val shownVol = if (vol >= 0f) vol else now.volume / 100f
    val mine = active?.takeIf { it.pc }?.pcSession
    val busyFmt = stringResource(R.string.busy_fmt)
    val viaFmt = stringResource(R.string.via_fmt)

    fun takeOver(other: Session?, go: () -> Unit) {
        other?.let { PcRemote.ctl(it.id, org.json.JSONObject().put("cmd", "pause").put("takeover", true)) }
        go(); onDone()
    }

    // Lautsprecher: was das Handy findet, plus was nur der PC sieht (dann steuert der PC ihn)
    val h = house
    val mainBusy = h?.session(PcRemote.MAIN)?.takeIf { it.playing && mine != PcRemote.MAIN }
    val speakers = buildList {
        devices.forEach { d ->
            val k = outKey(d.id)
            add(OutRow(k, d.name, d.sub, "speaker", { Connect.select(d) },
                on = active?.let { a -> if (a.pc) outKey(a.pcOutput) == k else a.id == d.id } == true,
                busy = h?.busy(d.id, mine)))
        }
        val seen = devices.map { outKey(it.id) }.toSet()
        h?.outputs?.filter { it.kind != "pc" && outKey(it.id) !in seen }?.forEach { o ->
            val k = outKey(o.id)
            add(OutRow(k, o.name, viaFmt.format(h?.computer ?: ""), "speaker", { Connect.select(PcRemote.target(PcRemote.MAIN, o.id, o.name)) },
                on = active?.pc == true && outKey(active?.pcOutput ?: "") == k,
                busy = h?.busy(o.id, mine) ?: mainBusy))
        }
    }
    // Soundkarten des PCs (Lautsprecher, Kopfhörer am PC) – gespielt wird über die Haupt-Wiedergabe dort
    val pcOuts = h?.outputs?.filter { it.kind == "pc" }.orEmpty().map { o ->
        val k = outKey(o.id)
        OutRow(k, o.name, h?.computer, "laptop", { Connect.select(PcRemote.target(PcRemote.MAIN, o.id, o.name)) },
            on = active?.pc == true && outKey(active?.pcOutput ?: "") == k,
            busy = h?.busy(o.id, mine) ?: mainBusy)
    }
    val others = h?.sessions?.filter { !it.self && it.id != mine && it.playing }.orEmpty()

    var showOthers by remember { mutableStateOf(false) }
    Sheet(onDismiss = onDone) {
        // Einfach: eine Liste „Abspielen auf“ – jeder Ausgang einmal; was sonst noch läuft, eingeklappt darunter
        Caption(stringResource(R.string.play_on))
        Spacer(Modifier.height(10.dp))
        Device("smartphone", stringResource(R.string.this_phone), null, on = active == null, waiting = wanted == null && active != null) {
            Connect.select(null); onDone()
        }
        LazyColumn(Modifier.heightIn(max = 340.dp), state = list) {
            items(speakers + pcOuts, key = { it.icon + it.key }) { r ->
                val b = r.busy
                if (b != null && !r.on && arming == r.key) {
                    HoldOption(stringResource(R.string.take_over_fmt, r.name), stringResource(R.string.take_over_hint_fmt, b.who)) { takeOver(b, r.select) }
                } else {
                    Device(
                        r.icon, r.name, if (b != null && !r.on) busyFmt.format(b.who) else r.sub?.takeIf { r.icon == "laptop" },
                        on = r.on, waiting = false,
                        modifier = Modifier.depth(list, r.icon + r.key),
                    ) { if (b != null && !r.on) arming = r.key else { r.select(); onDone() } }
                }
            }
        }
        if (searching) T(stringResource(R.string.searching_devices), size = 14.sp, color = Faint, modifier = Modifier.padding(vertical = 8.dp))
        else if (speakers.isEmpty() && pcOuts.isEmpty()) T(stringResource(R.string.no_devices), size = 14.sp, color = Faint, modifier = Modifier.padding(vertical = 8.dp).tap { Connect.search(ctx) })

        if (others.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            T((if (showOthers) "▾  " else "▸  ") + stringResource(R.string.also_playing_fmt, others.size), size = 14.sp, color = Dim,
                modifier = Modifier.fillMaxWidth().tap { showOthers = !showOthers }.padding(vertical = 8.dp))
            if (showOthers) others.forEach { s ->
                SessionCard(s) {
                    // PC-Wiedergabe antippen = hier voll steuern (Player zeigt sie dann an)
                    if (s.isPc && s.controllable) { Connect.select(PcRemote.target(s.id, s.outputId, s.outputName)); onDone() }
                }
            }
        }

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
        Option(stringResource(R.string.close), color = Dim) { onDone() }
    }
}

/** Eine andere Wiedergabe im Haus: wer → wo, was läuft, Tasten (wenn erlaubt) */
@Composable
private fun SessionCard(s: Session, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().tap(onClick = onOpen).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (s.isPc) "laptop" else "phone", 18.dp, if (s.playing) Ink else Dim)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                T(s.who + if (s.outputName.isNotBlank()) " → " + s.outputName else "", size = 15.sp, color = if (s.playing) Ink else Dim, glow = s.playing, maxLines = 1)
                val what = listOf(s.artist, s.title).filter { it.isNotBlank() }.joinToString(" – ")
                T((if (s.playing) "" else stringResource(R.string.paused) + " · ") + what.ifBlank { "—" }, size = 12.sp, color = Faint, maxLines = 1)
            }
        }
        if (s.controllable) {
            Row(Modifier.padding(start = 30.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Key("⏮") { PcRemote.ctl(s.id, "prev") }
                Key(if (s.playing) "⏸" else "▶") { PcRemote.ctl(s.id, "toggle") }
                Key("⏭") { PcRemote.ctl(s.id, "next") }
                Key("−") { PcRemote.ctl(s.id, "volume", "vol" to (s.volume - 0.1f).coerceIn(0f, 1f).toDouble()) }
                Key("+") { PcRemote.ctl(s.id, "volume", "vol" to (s.volume + 0.1f).coerceIn(0f, 1f).toDouble()) }
            }
        } else T(stringResource(R.string.view_only), size = 12.sp, color = Faint, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
    }
}

@Composable
private fun Key(label: String, onClick: () -> Unit) {
    T(label, size = 18.sp, color = Ink, modifier = Modifier.tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp))
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
