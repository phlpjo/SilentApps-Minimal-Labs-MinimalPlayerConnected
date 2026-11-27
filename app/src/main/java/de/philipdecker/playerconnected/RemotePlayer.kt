package de.philipdecker.playerconnected

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Wiedergabeziel außerhalb des Handys (Lautsprecher im Heimnetz oder Computer) */
interface Outlet : Player {
    val target: Target
    var pauseAtEnd: Boolean
    fun start(h: Handoff)
    fun handoff(): Handoff
}

/** Was beim Umschalten zwischen Handy und Lautsprecher mitgenommen wird */
class Handoff(
    /** Reihenfolge, in der gespielt wird (bei Zufall schon gemischt) */
    val items: List<MediaItem>,
    val index: Int,
    val position: Long,
    val play: Boolean,
    val shuffle: Boolean,
    val repeat: Int,
    /** Ursprüngliche Reihenfolge vor dem Mischen */
    val original: List<MediaItem>?,
) {
    companion object {
        /** Vom ExoPlayer: bei Zufall die gemischte Reihenfolge übernehmen, damit es auf dem Lautsprecher genauso weitergeht */
        fun of(p: Player): Handoff {
            val all = (0 until p.mediaItemCount).map { p.getMediaItemAt(it) }
            val idx = p.currentMediaItemIndex.coerceAtLeast(0)
            val pos = p.currentPosition.coerceAtLeast(0L)
            val play = p.playWhenReady && p.playbackState != Player.STATE_ENDED
            if (!p.shuffleModeEnabled || all.size < 2) return Handoff(all, idx, pos, play, p.shuffleModeEnabled, p.repeatMode, null)
            val tl = p.currentTimeline
            val order = ArrayList<Int>()
            var i = tl.getFirstWindowIndex(true)
            while (i != C.INDEX_UNSET && order.size < all.size) { order += i; i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true) }
            if (order.size != all.size) return Handoff(all, idx, pos, play, true, p.repeatMode, null)
            return Handoff(order.map { all[it] }, order.indexOf(idx).coerceAtLeast(0), pos, play, true, p.repeatMode, all)
        }
    }
}

/**
 * Wiedergabe auf einem Lautsprecher im Heimnetz (Sonos oder UPnP/DLNA-Renderer) als Media3-Player.
 * Die Warteschlange bleibt im Handy; der Lautsprecher bekommt jeweils den aktuellen Titel über [Serve].
 * Zustand wird jede Sekunde abgefragt; Pause/Play/Lautstärke am Gerät selbst werden übernommen.
 */
class RemotePlayer(
    private val ctx: Context,
    override val target: Target,
    /** Verbindung weg oder Lautsprecher spielt etwas anderes → zurück aufs Handy (String-Ressource als Grund) */
    private val onLost: (Int) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()), Outlet {

    private class Entry(val uid: Long, val item: MediaItem)

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "remote").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var nextUid = 1L

    private var entries: List<Entry> = emptyList()
    private var original: List<Entry>? = null
    private var index = 0
    private var pwr = false
    private var state = Player.STATE_IDLE
    private var posMs = 0L
    private var posAt = SystemClock.elapsedRealtime()
    private var durMs = C.TIME_UNSET
    private var repeat = Player.REPEAT_MODE_OFF
    private var shuffle = false
    private var volume = 25
    private var muted = false
    /** Bei „Ende der Datei“ (Schlaf-Timer) nach dem Titel anhalten */
    override var pauseAtEnd = false

    /** Adresse, die der Lautsprecher gerade spielt (null = noch nichts geladen) */
    private var loaded: String? = null
    /** Steigt bei jedem Laden – veraltete Antworten werden verworfen */
    private var gen = 0
    private var pendingSeek: Long? = null
    /** Nach eigenen Befehlen kurz keine „am Gerät geändert“-Erkennung (Lautsprecher braucht etwas) */
    private var quietUntil = 0L
    private var sawPlaying = false
    private var lastPlayingPos = 0L
    private var fails = 0
    private var busy = false
    private var ticks = 0
    private var released = false
    private var loadedAt = 0L
    /** Länge laut Datei (Ersatz, falls der Lautsprecher keine meldet) */
    private var fileDur = 0L

    private val moving get() = state == Player.STATE_READY && pwr

    // ───────── Start ─────────

    override fun start(h: Handoff) {
        entries = h.items.map { Entry(nextUid++, it) }
        original = h.original?.let { orig ->
            // Gleiche Einträge (gleiche Objekte) wie in entries, damit das Zurückmischen funktioniert
            orig.map { mi -> entries.firstOrNull { it.item === mi } ?: Entry(nextUid++, mi) }
        }
        index = h.index.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        shuffle = h.shuffle
        repeat = h.repeat
        pwr = h.play
        posMs = h.position; posAt = SystemClock.elapsedRealtime()
        if (target.sonos && target.group > 1 && target.grc != null) io.execute { try { Upnp.soap(target.grc, "SnapshotGroupVolume", listOf("InstanceID" to "0")) } catch (_: Exception) { } }
        readVolume()
        if (entries.isNotEmpty()) load(index, h.position, h.play) else invalidateState()
        main.postDelayed(poll, 1000)
    }

    /** Für den Rückweg aufs Handy */
    override fun handoff(): Handoff {
        val items = entries.map { it.item }
        val orig = original?.map { it.item }
        return Handoff(items, index, currentPos(), pwr && state != Player.STATE_ENDED, shuffle, repeat, orig)
    }

    private fun currentPos(): Long {
        val p = if (moving) posMs + (SystemClock.elapsedRealtime() - posAt) else posMs
        return if (durMs > 0) p.coerceAtMost(durMs) else p
    }

    // ───────── Zustand für Media3 ─────────

    override fun getState(): SimpleBasePlayer.State {
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_PREPARE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()
        val b = SimpleBasePlayer.State.Builder()
            .setAvailableCommands(cmds)
            .setPlayWhenReady(pwr, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setRepeatMode(repeat)
            .setShuffleModeEnabled(shuffle)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build())
            .setDeviceVolume(volume.coerceIn(0, 100))
            .setIsDeviceMuted(muted)
        if (entries.isEmpty()) {
            return b.setPlaybackState(Player.STATE_IDLE).build()
        }
        val cur = entries.getOrNull(index)
        val list = entries.map { e ->
            SimpleBasePlayer.MediaItemData.Builder(e.uid)
                .setMediaItem(e.item)
                .setIsSeekable(true)
                .setDurationUs(if (e === cur && durMs > 0) durMs * 1000 else C.TIME_UNSET)
                .build()
        }
        return b.setPlaylist(list)
            .setCurrentMediaItemIndex(index.coerceIn(0, entries.size - 1))
            .setPlaybackState(state)
            .setContentPositionMs(SimpleBasePlayer.PositionSupplier { currentPos() })
            .build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun quiet() { quietUntil = SystemClock.elapsedRealtime() + 3500 }

    // ───────── Befehle ─────────

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) { posMs = currentPos(); posAt = SystemClock.elapsedRealtime() }
        pwr = playWhenReady
        quiet()
        if (entries.isEmpty()) return done()
        if (loaded == null || state == Player.STATE_ENDED) {
            // Lädt gerade: das Ergebnis von load() richtet sich nach dem dann gültigen pwr
            if (state != Player.STATE_ENDED && state != Player.STATE_BUFFERING) load(index, posMs, playWhenReady)
            return done()
        }
        val my = gen
        val seek = pendingSeek
        if (playWhenReady) pendingSeek = null
        posAt = SystemClock.elapsedRealtime()
        io.execute {
            try {
                if (playWhenReady) {
                    avt("Play", "Speed" to "1")
                    if (seek != null && seek > 1500) seekRemote(seek)
                } else avt("Pause")
            } catch (_: Exception) {
                if (!playWhenReady) try { avt("Stop") } catch (_: Exception) { }
                main.post { if (my == gen) failed() }
            }
        }
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (entries.isNotEmpty() && loaded == null) load(index, posMs, pwr)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        gen++
        loaded = null
        state = Player.STATE_IDLE
        posMs = currentPos(); posAt = SystemClock.elapsedRealtime()
        io.execute { try { avt("Stop") } catch (_: Exception) { } }
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        gen++
        main.removeCallbacks(poll)
        // Lautsprecher anhalten (wie beim Wechsel in anderen Apps); danach Thread beenden
        io.execute { try { avt("Stop") } catch (_: Exception) { } }
        io.shutdown()
        return done()
    }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return done() }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        if (shuffleModeEnabled == shuffle) return done()
        val cur = entries.getOrNull(index)
        if (shuffleModeEnabled) {
            original = entries
            if (cur != null) { entries = listOf(cur) + (entries - cur).shuffled(); index = 0 }
        } else {
            val orig = original ?: entries
            entries = orig
            index = if (cur != null) orig.indexOf(cur).coerceAtLeast(0) else 0
            original = null
        }
        shuffle = shuffleModeEnabled
        return done()
    }

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        var list = mediaItems.map { Entry(nextUid++, it) }
        var start = if (startIndex == C.INDEX_UNSET) 0 else startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        original = null
        if (shuffle && list.size > 1) {
            original = list
            val first = list[start]
            list = listOf(first) + (list - first).shuffled()
            start = 0
        }
        entries = list
        index = start
        val pos = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs
        posMs = pos; posAt = SystemClock.elapsedRealtime()
        if (entries.isEmpty()) { gen++; loaded = null; state = Player.STATE_IDLE } else load(index, pos, pwr)
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: MutableList<MediaItem>): ListenableFuture<*> {
        val add = mediaItems.map { Entry(nextUid++, it) }
        val at = index.coerceIn(0, entries.size)
        entries = entries.take(at) + add + entries.drop(at)
        if (at <= this.index && entries.size > add.size) this.index += add.size
        original = original?.let { it + add }
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val gone = entries.subList(fromIndex, toIndex).toSet()
        val cur = entries.getOrNull(index)
        entries = entries.filterNot { it in gone }
        original = original?.filterNot { it in gone }
        if (cur != null && cur in gone) {
            index = fromIndex.coerceAtMost((entries.size - 1).coerceAtLeast(0))
            if (entries.isEmpty()) { gen++; loaded = null; state = Player.STATE_ENDED } else load(index, 0L, pwr)
        } else if (cur != null) index = entries.indexOf(cur)
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val cur = entries.getOrNull(index)
        val moved = entries.subList(fromIndex, toIndex).toList()
        val rest = entries.take(fromIndex) + entries.drop(toIndex)
        val at = newIndex.coerceIn(0, rest.size)
        entries = rest.take(at) + moved + rest.drop(at)
        if (cur != null) index = entries.indexOf(cur)
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (entries.isEmpty()) return done()
        val i = if (mediaItemIndex == C.INDEX_UNSET) index else mediaItemIndex.coerceIn(0, entries.size - 1)
        val p = if (positionMs == C.TIME_UNSET) 0L else positionMs.coerceAtLeast(0L)
        if (i == index && loaded != null && state != Player.STATE_ENDED) {
            posMs = p; posAt = SystemClock.elapsedRealtime()
            quiet()
            if (!pwr || state != Player.STATE_READY) { pendingSeek = p; io.execute { try { seekRemote(p) } catch (_: Exception) { } } }
            else io.execute { try { seekRemote(p) } catch (_: Exception) { } }
        } else load(i, p, pwr)
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volume = deviceVolume.coerceIn(0, 100)
        pushVolume()
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume + 3, flags)
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume - 3, flags)

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        this.muted = muted
        val rc = target.rc ?: return done()
        io.execute { try { Upnp.soap(rc, "SetMute", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0")) } catch (_: Exception) { } }
        return done()
    }

    // ───────── Laden & Steuern ─────────

    /** Sonos: aktuell steuernde Box der Gruppe (kann wechseln) */
    @Volatile private var liveAvt: Svc? = null

    private fun avt(action: String, vararg args: Pair<String, String>): String =
        Upnp.soap(liveAvt ?: target.avt, action, listOf("InstanceID" to "0") + args)

    private fun seekRemote(ms: Long) {
        // Lautsprecher nimmt Spulen erst an, wenn der Titel geladen ist → ein paar Versuche
        repeat(6) {
            try { avt("Seek", "Unit" to "REL_TIME", "Target" to Upnp.time(ms)); return } catch (_: Exception) { Thread.sleep(400) }
        }
    }

    private fun load(i: Int, startMs: Long, play: Boolean) {
        val e = entries.getOrNull(i) ?: return
        index = i
        posMs = startMs.coerceAtLeast(0); posAt = SystemClock.elapsedRealtime()
        durMs = C.TIME_UNSET
        fileDur = 0L
        state = Player.STATE_BUFFERING
        loaded = null
        sawPlaying = false
        pendingSeek = null
        quiet()
        val my = ++gen
        invalidateLater()
        io.execute {
            try {
                val pub = Serve.publish(ctx, target.host, e.item)
                val meta = didl(e.item, pub)
                if (target.sonos) sonosCoordinatorAvt(target)?.let { liveAvt = it }
                avt("SetAVTransportURI", "CurrentURI" to pub.url, "CurrentURIMetaData" to meta)
                var seekLater: Long? = null
                if (play) {
                    avt("Play", "Speed" to "1")
                    if (startMs > 1500) seekRemote(startMs)
                } else if (startMs > 1500) seekLater = startMs
                main.post {
                    if (my != gen || released) return@post
                    loaded = pub.url
                    loadedAt = SystemClock.elapsedRealtime()
                    if (pub.durationMs > 0) fileDur = pub.durationMs
                    if (durMs <= 0 && fileDur > 0) durMs = fileDur
                    pendingSeek = seekLater
                    if (!play) state = Player.STATE_READY
                    // Während des Ladens wurde Play/Pause gedrückt
                    if (pwr != play) {
                        val seek = pendingSeek
                        if (pwr) pendingSeek = null
                        val want = pwr
                        io.execute {
                            try {
                                if (want) { avt("Play", "Speed" to "1"); if (seek != null) seekRemote(seek) } else avt("Pause")
                            } catch (_: Exception) { }
                        }
                    }
                    quiet()
                    invalidateState()
                }
            } catch (_: Exception) {
                main.post { if (my == gen && !released) failed() }
            }
        }
    }

    /** Datei lässt sich nicht abspielen bzw. Befehl abgelehnt: anhalten und Hinweis */
    private fun failed() {
        pwr = false
        state = Player.STATE_READY
        loaded = null
        Connect.notice.value = R.string.connect_failed
        invalidateState()
    }

    private fun didl(item: MediaItem, pub: Serve.Published): String {
        val md = item.mediaMetadata
        val title = Upnp.esc(md.title?.toString()?.takeIf { it.isNotBlank() } ?: "Audio")
        val artist = md.artist?.toString()?.takeIf { it.isNotBlank() }?.let { Upnp.esc(it) }
        val album = md.albumTitle?.toString()?.takeIf { it.isNotBlank() }?.let { Upnp.esc(it) }
        val proto = if (target.sonos) "http-get:*:${pub.mime}:*"
        else "http-get:*:${pub.mime}:DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"1\" parentID=\"0\" restricted=\"1\">")
            append("<dc:title>").append(title).append("</dc:title>")
            if (artist != null) append("<dc:creator>").append(artist).append("</dc:creator><upnp:artist>").append(artist).append("</upnp:artist>")
            if (album != null) append("<upnp:album>").append(album).append("</upnp:album>")
            if (pub.cover != null) append("<upnp:albumArtURI>").append(Upnp.esc(pub.cover)).append("</upnp:albumArtURI>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res protocolInfo=\"").append(Upnp.esc(proto)).append('"')
            if (pub.durationMs > 0) append(" duration=\"").append(Upnp.time(pub.durationMs)).append(".000\"")
            append('>').append(Upnp.esc(pub.url)).append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    // ───────── Lautstärke ─────────

    private val volBusy = AtomicBoolean(false)
    @Volatile private var volWanted = -1

    private fun pushVolume() {
        volWanted = volume
        if (!volBusy.compareAndSet(false, true)) return
        io.execute {
            try {
                // Nur der jeweils letzte Wert wird geschickt (Schieberegler erzeugt viele)
                var sent = -1
                while (volWanted != sent) {
                    val v = volWanted
                    try {
                        if (target.group > 1 && target.grc != null) Upnp.soap(target.grc, "SetGroupVolume", listOf("InstanceID" to "0", "DesiredVolume" to "$v"))
                        else target.rc?.let { Upnp.soap(it, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "$v")) }
                    } catch (_: Exception) { }
                    sent = v
                }
            } finally { volBusy.set(false) }
        }
    }

    private fun readVolume() {
        if (volBusy.get()) return
        io.execute {
            val v = try {
                if (target.group > 1 && target.grc != null) Upnp.value(Upnp.soap(target.grc, "GetGroupVolume", listOf("InstanceID" to "0")), "CurrentVolume")
                else target.rc?.let { Upnp.value(Upnp.soap(it, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")), "CurrentVolume") }
            } catch (_: Exception) { null }?.toIntOrNull()
            if (v != null) main.post { if (!released && !volBusy.get() && v != volume) { volume = v; invalidateState() } }
        }
    }

    // ───────── Abfrage ─────────

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            tick()
            main.postDelayed(this, 1000)
        }
    }

    private fun tick() {
        if (busy || loaded == null) return
        busy = true
        val my = gen
        if (++ticks % 5 == 0) readVolume()
        io.execute {
            try {
                val t = Upnp.value(avt("GetTransportInfo"), "CurrentTransportState") ?: ""
                val p = avt("GetPositionInfo")
                val rel = Upnp.parseTime(Upnp.value(p, "RelTime"))
                val dur = Upnp.parseTime(Upnp.value(p, "TrackDuration"))
                val uri = Upnp.value(p, "TrackURI")
                main.post {
                    busy = false
                    fails = 0
                    if (my == gen && !released) update(t, rel, dur, uri)
                }
            } catch (_: Exception) {
                main.post {
                    busy = false
                    if (my == gen && !released && ++fails >= 4) onLost(R.string.connect_lost)
                }
            }
        }
    }

    private fun update(transport: String, rel: Long, dur: Long, uri: String?) {
        val mine = loaded ?: return
        val now = SystemClock.elapsedRealtime()
        val calm = now > quietUntil
        // Jemand hat am Lautsprecher etwas anderes gestartet (andere App, Radio …)
        if (calm && !uri.isNullOrEmpty() && uri != mine && !Serve.isOurs(uri) && transport != "STOPPED" && transport != "NO_MEDIA_PRESENT") {
            onLost(R.string.connect_taken)
            return
        }
        // Länge: vom Lautsprecher, sonst aus der Datei
        if (dur > 0) durMs = dur else if (fileDur > 0) durMs = fileDur
        when (transport) {
            "PLAYING" -> {
                sawPlaying = true
                if (rel >= 0) { posMs = rel; posAt = now; lastPlayingPos = rel }
                state = Player.STATE_READY
                if (!pwr && calm) pwr = true
            }
            "PAUSED_PLAYBACK" -> {
                if (rel >= 0) { posMs = rel; posAt = now }
                state = Player.STATE_READY
                if (pwr && calm && sawPlaying) pwr = false
            }
            "TRANSITIONING" -> if (state != Player.STATE_READY || !sawPlaying) state = Player.STATE_BUFFERING
            "STOPPED", "NO_MEDIA_PRESENT" -> {
                if (sawPlaying && calm) {
                    sawPlaying = false
                    val nearEnd = durMs <= 0 || lastPlayingPos >= durMs - 4000
                    if (nearEnd && pwr) { trackEnded(); return }
                    // Am Gerät gestoppt
                    posMs = lastPlayingPos; posAt = now
                    pwr = false
                    loaded = null
                    state = Player.STATE_READY
                } else if (!pwr) state = Player.STATE_READY
                else if (!sawPlaying && now - loadedAt > 15_000) { failed(); return }
            }
        }
        invalidateState()
    }

    private fun trackEnded() {
        val next = when {
            repeat == Player.REPEAT_MODE_ONE -> index
            index + 1 < entries.size -> index + 1
            repeat == Player.REPEAT_MODE_ALL && entries.isNotEmpty() -> 0
            else -> -1
        }
        if (next < 0) {
            posMs = durMs.coerceAtLeast(0); posAt = SystemClock.elapsedRealtime()
            loaded = null
            state = Player.STATE_ENDED
            invalidateState()
            return
        }
        if (pauseAtEnd) pwr = false
        load(next, 0L, pwr)
    }

    private fun invalidateLater() = main.post { if (!released) invalidateState() }
}
