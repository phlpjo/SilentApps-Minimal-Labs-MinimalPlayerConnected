package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wiedergabe am Computer (Minimal Library) als Media3-Player – wie Spotify Connect:
 * die Warteschlange liegt am PC; das Handy zeigt an, was läuft, und steuert (Abspielen, Weiter, Spulen, Lautstärke, Ausgabe).
 */
class PcPlayer(
    private val ctx: Context,
    override val target: Target,
    private val onLost: (Int) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()), Outlet {

    override var pauseAtEnd = false
    /** gesteuerte Wiedergabe am PC */
    private val sid = target.pcSession
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var last: Session? = null
    private var lastSeen = SystemClock.elapsedRealtime()
    /** eigener Befehl: bis der PC ihn meldet, so anzeigen */
    private var wantPlay: Boolean? = null
    private var wantUntil = 0L
    private var volume = 50
    /** PC-Titel-ID → Eintrag für das Handy (für die Rückgabe der Warteschlange) */
    private var items: Map<Long, MediaItem> = emptyMap()
    private var released = false

    private val poll = object : Runnable {
        override fun run() {
            if (released) return
            val n = PcRemote.session(sid)
            val t = SystemClock.elapsedRealtime()
            if (n != null && n !== last) {
                last = n
                lastSeen = t
                volume = (n.volume * 100).toInt().coerceIn(0, 100)
                invalidateState()
            } else if (t - lastSeen > 12_000) {
                onLost(R.string.pc_lost)
                return
            }
            main.postDelayed(this, 400)
        }
    }

    override fun start(h: Handoff) {
        PcRemote.attached = true
        val tids = h.items.map { Pc.trackIdOf(it.mediaId) ?: Pc.trackIdOf(it.requestMetadata.mediaUri?.toString() ?: "") }
        val cur = tids.getOrNull(h.index)
        val list = tids.filterNotNull()
        // Warteschlange nur an die Haupt-Wiedergabe (deren Titel-Kennungen kennt das Handy)
        if (h.play && list.isNotEmpty() && sid == PcRemote.MAIN) {
            // Spielt das Handy gerade: Warteschlange, Titel und Stelle wandern zum PC
            val idx = if (cur != null) list.indexOf(cur).coerceAtLeast(0) else 0
            PcRemote.ctl(sid, JSONObject().apply {
                put("cmd", "queue"); put("ids", JSONArray(list)); put("index", idx)
                put("pos", h.position / 1000.0); put("play", true); put("output", target.pcOutput)
            })
        } else PcRemote.ctl(sid, "output", "id" to target.pcOutput)
        // Einträge für den Rückweg vorbereiten
        scope.launch {
            val byTid = Pc.tracksByTid()
            val name = Pc.state.value.name
            items = byTid.mapValues { (_, t) -> Library.item(t, Keys.dir(MUSIC, t.root, t.dir), MUSIC, name) }
        }
        main.post(poll)
    }

    override fun handoff(): Handoff {
        val n = last ?: PcRemote.session(sid)
        // Nur die Haupt-Wiedergabe kommt aufs Handy (wie Spotify, PC hält an). Andere Tabs laufen einfach weiter –
        // das Handy hört nur auf, sie zu steuern.
        if (sid != PcRemote.MAIN) return Handoff(emptyList(), 0, 0, false, false, Player.REPEAT_MODE_OFF, null)
        PcRemote.ctl(sid, "pause")
        if (n == null || n.queue.isEmpty()) return Handoff(emptyList(), 0, 0, false, false, Player.REPEAT_MODE_OFF, null)
        val mapped = n.queue.mapNotNull { id -> items[id]?.let { id to it } }
        val idx = mapped.indexOfFirst { it.first == n.trackId }.coerceAtLeast(0)
        return Handoff(mapped.map { it.second }, idx, n.position(), n.playing, false, Player.REPEAT_MODE_OFF, null)
    }

    private fun itemFor(n: Session): MediaItem {
        val id = n.trackId ?: 0L
        items[id]?.let { mi ->
            return mi.buildUpon().setMediaMetadata(mi.mediaMetadata.buildUpon().setTitle(n.title).setDisplayTitle(n.title).setArtist(n.artist.ifEmpty { n.outputName }).build()).build()
        }
        val uri = "${Pc.SCHEME}://pc/$id.x"
        return MediaItem.Builder()
            .setMediaId(uri)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(n.title).setDisplayTitle(n.title).setArtist(n.artist)
                    .apply { Pc.coverUrl(uri)?.let { setArtworkUri(Uri.parse(it)) } }
                    .setIsPlayable(true).setIsBrowsable(false).build(),
            ).build()
    }

    override fun getState(): State {
        val cmds = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_RELEASE,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE, Player.COMMAND_PREPARE,
            Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH, Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
        ).build()
        val n = last
        val t = SystemClock.elapsedRealtime()
        val playing = if (t < wantUntil) wantPlay ?: (n?.playing == true) else n?.playing == true
        val b = State.Builder()
            .setAvailableCommands(cmds)
            .setPlayWhenReady(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setShuffleModeEnabled(n?.shuffle == true)
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(0).setMaxVolume(100).build())
            .setDeviceVolume(volume)
        if (n?.trackId == null) return b.setPlaybackState(Player.STATE_IDLE).build()
        val data = MediaItemData.Builder(n.trackId)
            .setMediaItem(itemFor(n))
            .setIsSeekable(true)
            .setDurationUs(if (n.durMs > 0) n.durMs * 1000 else C.TIME_UNSET)
            .build()
        // Platzhalter davor/danach: so sind „Zurück“ und „Weiter“ am Handy (und in der Benachrichtigung) bedienbar.
        // Die echte Warteschlange liegt am PC.
        val hasNext = n.queue.isEmpty() || n.index < 0 || n.index < n.queue.size - 1
        val list = buildList {
            add(placeholder("prev:${n.trackId}"))
            add(data)
            if (hasNext) add(placeholder("next:${n.trackId}"))
        }
        return b.setPlaylist(list)
            .setCurrentMediaItemIndex(1)
            .setPlaybackState(Player.STATE_READY)
            .setContentPositionMs(PositionSupplier { n.position() })
            .build()
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun placeholder(uid: String) = MediaItemData.Builder(uid)
        .setMediaItem(MediaItem.Builder().setMediaId(uid).setMediaMetadata(MediaMetadata.Builder().setTitle("").setIsPlayable(true).setIsBrowsable(false).build()).build())
        .build()

    /** Titel am Handy angetippt (während der PC gesteuert wird): diese Warteschlange spielt jetzt am PC */
    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        val tids = mediaItems.map { Pc.trackIdOf(it.mediaId) ?: Pc.trackIdOf(it.localConfiguration?.uri?.toString() ?: "") ?: Pc.trackIdOf(it.requestMetadata.mediaUri?.toString() ?: "") }
        val start = if (startIndex in tids.indices) startIndex else 0
        val list = tids.filterNotNull()
        if (list.isEmpty() || tids[start] == null) {
            // Titel liegt nur auf dem Handy – den kann der PC nicht abspielen
            main.post { android.widget.Toast.makeText(ctx, ctx.getString(R.string.pc_only_pc_tracks), android.widget.Toast.LENGTH_SHORT).show() }
            return done()
        }
        if (sid != PcRemote.MAIN) {
            main.post { android.widget.Toast.makeText(ctx, ctx.getString(R.string.pc_only_main), android.widget.Toast.LENGTH_SHORT).show() }
            return done()
        }
        val idx = list.indexOf(tids[start]!!).coerceAtLeast(0)
        wantPlay = true
        wantUntil = SystemClock.elapsedRealtime() + 3000
        PcRemote.ctl(sid, JSONObject().apply {
            put("cmd", "queue"); put("ids", JSONArray(list)); put("index", idx)
            put("pos", (if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs) / 1000.0); put("play", true)
        })
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> = done()
    override fun handleSetPlaybackParameters(playbackParameters: androidx.media3.common.PlaybackParameters): ListenableFuture<*> = done()
    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = done()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantPlay = playWhenReady
        wantUntil = SystemClock.elapsedRealtime() + 3000
        PcRemote.ctl(sid, if (playWhenReady) "play" else "pause")
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || mediaItemIndex >= 2 -> PcRemote.ctl(sid, "next")
            seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM || mediaItemIndex == 0 -> PcRemote.ctl(sid, "prev")
            else -> PcRemote.ctl(sid, "seek", "pos" to (positionMs.coerceAtLeast(0) / 1000.0))
        }
        return done()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        volume = deviceVolume.coerceIn(0, 100)
        PcRemote.ctl(sid, "volume", "vol" to volume / 100.0)
        return done()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume + 5, flags)
    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> = handleSetDeviceVolume(volume - 5, flags)

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> {
        PcRemote.ctl(sid, "shuffle", "on" to shuffleModeEnabled)
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        PcRemote.ctl(sid, "pause")
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        PcRemote.attached = false
        main.removeCallbacks(poll)
        scope.cancel()
        return done()
    }
}
