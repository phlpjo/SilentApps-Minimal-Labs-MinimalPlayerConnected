package de.philipdecker.playerconnected

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Metadata
import androidx.media3.common.Tracks
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.CommandButton
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.json.JSONObject
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import android.os.Bundle
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.get(this)
        Library.init(this)
        Sync.start(this) // Ordner und Playlisten mit der anderen Player-App abgleichen
    }
}

/**
 * Wiedergabe im Hintergrund: ExoPlayer (lückenlos, Tempo mit Tonhöhenkorrektur) in einer MediaSession –
 * dadurch Steuerung auf dem Sperrbildschirm, in der Benachrichtigung und über Bluetooth-/Kopfhörer-Tasten.
 * Merkt sich bei Hörbüchern laufend die Stelle und führt den Schlaf-Timer (mit Ausblenden und Schütteln).
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private lateinit var exo: ExoPlayer
    /** Lautsprecher im Heimnetz (null = Wiedergabe auf dem Handy) */
    private var remote: Outlet? = null
    /** Der gerade aktive Player */
    private val player: Player get() = remote ?: exo
    private lateinit var localWrap: SessionPlayer
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuLock: PowerManager.WakeLock? = null
    /** Rückweg nach Verbindungsabbruch: auf dem Handy pausiert bleiben */
    private var pauseOnReturn = false
    private lateinit var prefs: Prefs
    private val scope = MainScope()
    private var sensors: SensorManager? = null
    private var shakeOn = false
    private var eq: Equalizer? = null
    private var boost: LoudnessEnhancer? = null
    /** Grundlautstärke (bis 100 %) und Ausblend-Faktor des Schlaf-Timers */
    private var base = 1f
    private var fadeFactor = 1f
    private var lastItem: MediaItem? = null
    /** ReplayGain des aktuellen Titels in dB (0 = keiner) */
    private var rgDb = 0f

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        Library.init(this)
        Pc.init(this)
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // Kopfhörer raus → Pause
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(listener)
        setupFx()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("player", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        localWrap = SessionPlayer(exo)
        session = MediaLibrarySession.Builder(this, localWrap, callback)
            .setSessionActivity(open)
            .build()

        // Stelle regelmäßig sichern (falls die App hart beendet wird)
        scope.launch {
            while (true) {
                delay(5_000)
                if (player.isPlaying) save()
            }
        }
        scope.launch { Sleep.endAt.collectLatest { runTimer(it) } }
        scope.launch { Sleep.endOfFile.collect { exo.pauseAtEndOfMediaItems = it; remote?.pauseAtEnd = it } }
        scope.launch { Connect.wanted.collect { switchTo(it) } }
        scope.launch { Fx.changed.collect { applyFx() } }
        // Fernsteuerung: dem PC melden, was hier läuft, und seine Befehle ausführen
        PcRemote.phoneState = state@{
            if (remote is PcPlayer) return@state null // steuert gerade den PC – nichts Eigenes zu melden
            val p = player
            val mi = p.currentMediaItem ?: return@state null
            val md = mi.mediaMetadata
            JSONObject().apply {
                put("playing", p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING))
                put("title", (md.title ?: md.displayTitle ?: "").toString())
                put("artist", (md.artist ?: "").toString())
                put("pos", p.currentPosition / 1000.0)
                put("dur", if (p.duration > 0) p.duration / 1000.0 else 0.0)
                put("volume", if (remote != null) p.deviceVolume / 100.0 else exo.volume.toDouble())
                put("output", Connect.active.value?.name ?: getString(R.string.this_phone))
                put("outputId", Connect.active.value?.id ?: "phone")
                // Geteilter Haushalt: dürfen andere dieses Handy steuern?
                put("remoteCtl", Prefs.get(this@PlaybackService).othersControl)
            }
        }
        PcRemote.phoneExec = exec@{ c ->
            if (!Prefs.get(this).othersControl) return@exec
            val p = player
            when (c.optString("cmd")) {
                "toggle" -> if (p.playWhenReady) p.pause() else p.play()
                "play" -> p.play()
                "pause" -> {
                    p.pause()
                    // Ausgang wurde von jemand anderem übernommen → kurz sagen, von wem
                    if (c.optBoolean("takeover") && c.optString("by").isNotBlank()) {
                        val where = Connect.active.value?.name ?: getString(R.string.this_phone)
                        android.widget.Toast.makeText(this, getString(R.string.taken_over_fmt, where, c.optString("by")), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                "seek" -> c.optDouble("pos", -1.0).takeIf { it >= 0 }?.let { p.seekTo((it * 1000).toLong()) }
                "next" -> p.seekToNext()
                "prev" -> p.seekToPrevious()
                "volume" -> {
                    val v = c.optDouble("vol", -1.0)
                    if (v >= 0) {
                        if (remote != null) p.setDeviceVolume((v * 100).toInt().coerceIn(0, 100), 0)
                        else exo.volume = v.toFloat().coerceIn(0f, 1f)
                    }
                }
            }
        }
        PcRemote.acquire()
        scope.launch { Library.stars.collect { applyButtons() } }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** App aus der Übersicht weggewischt: Wiedergabe sofort beenden, Benachrichtigung verschwindet */
    override fun onTaskRemoved(rootIntent: Intent?) {
        save()
        Sleep.cancel()
        dropRemote()
        exo.pause()
        exo.stop()
        session?.let { removeSession(it) }
        stopSelf()
    }

    override fun onDestroy() {
        PcRemote.phoneState = null
        PcRemote.phoneExec = null
        PcRemote.release()
        save()
        Sleep.cancel()
        stopShake()
        try { eq?.release() } catch (_: Exception) { }
        try { boost?.release() } catch (_: Exception) { }
        dropRemote()
        session?.run { exo.release(); release() }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ───────── Ereignisse ─────────

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Einzelne Hörbuch-Datei bis zum Ende gehört und automatisch weiter → als gehört markieren
            val prev = lastItem
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && prev != null) {
                val ex = prev.mediaMetadata.extras
                val k = ex?.getString("container")
                if (ex?.getInt("section", MUSIC) == BOOKS && k != null && k.startsWith("f|")) {
                    scope.launch { Library.markFinished(k, true); Library.dao.forget(Keys.progressOfFile(prev.mediaId)) }
                }
            }
            lastItem = mediaItem
            rgDb = 0f
            applySpeed()
            applyFx()
            applyButtons()
            save()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) Sleep.cancel()
            if (!playWhenReady) {
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) prefs.pausedAt = System.currentTimeMillis()
                save()
            } else autoRewind()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) finished()
            if (state == Player.STATE_READY) rememberDuration()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = updateLocks()

        override fun onTracksChanged(tracks: Tracks) {
            rgDb = replayGain(tracks)
            applyFx()
        }
    }

    /** Hörbücher mit dem gewählten Tempo, Musik immer mit 1,0 */
    private fun applySpeed() {
        if (remote != null) return // Lautsprecher spielen immer mit 1,0
        val section = player.currentMediaItem?.mediaMetadata?.extras?.getInt("section", MUSIC) ?: return
        val want = if (section == BOOKS) prefs.bookSpeed else 1f
        if (player.playbackParameters.speed != want) player.setPlaybackSpeed(want)
    }

    private fun isBook() = player.currentMediaItem?.mediaMetadata?.extras?.getInt("section", MUSIC) == BOOKS

    /** Hörbuch nach längerer Pause: ein paar Sekunden zurück, damit man den Faden wiederfindet */
    private fun autoRewind() {
        val at = prefs.pausedAt
        prefs.pausedAt = 0L
        if (at <= 0L || !prefs.autoRewind || !isBook()) return
        val away = System.currentTimeMillis() - at
        val back = when {
            away < 2 * 60_000L -> 0L
            away < 30 * 60_000L -> 5_000L
            away < 24 * 3600_000L -> 10_000L
            else -> 15_000L
        }
        if (back > 0) player.seekTo((player.currentPosition - back).coerceAtLeast(0L))
    }

    /** Dauer der Datei für die Prozentanzeige merken */
    private fun rememberDuration() {
        val item = player.currentMediaItem ?: return
        val d = player.duration
        if (d == C.TIME_UNSET || d <= 0) return
        val uri = item.mediaId
        scope.launch(Dispatchers.IO) { try { Library.dao.setDuration(uri, d) } catch (_: Exception) { } }
    }

    /** ReplayGain aus den Tags (ID3 TXXX, MP4 ----, Vorbis-Kommentar); nur Musik */
    private fun replayGain(tracks: Tracks): Float {
        if (isBook() || prefs.replayGain == "off") return 0f
        val found = HashMap<String, Float>()
        for (g in tracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                val md: Metadata = g.getTrackFormat(i).metadata ?: continue
                for (k in 0 until md.length()) {
                    val e = md.get(k)
                    val (key, value) = when (e) {
                        is TextInformationFrame -> (e.description ?: "") to (e.values.firstOrNull() ?: "")
                        is InternalFrame -> e.description to e.text
                        else -> e.toString().substringAfter(": ", "").let { it.substringBefore('=') to it.substringAfter('=', "") }
                    }
                    val name = key.trim().uppercase()
                    if (name == "REPLAYGAIN_TRACK_GAIN" || name == "REPLAYGAIN_ALBUM_GAIN") {
                        Regex("[-+]?\\d+(\\.\\d+)?").find(value.replace(',', '.'))?.value?.toFloatOrNull()?.let { found[name] = it }
                    }
                }
            }
        }
        val want = if (prefs.replayGain == "album") found["REPLAYGAIN_ALBUM_GAIN"] ?: found["REPLAYGAIN_TRACK_GAIN"]
        else found["REPLAYGAIN_TRACK_GAIN"] ?: found["REPLAYGAIN_ALBUM_GAIN"]
        return (want ?: 0f).coerceIn(-20f, 12f)
    }

    /** Benachrichtigung/Sperrbildschirm: bei Hörbüchern −15/+30 statt Vorheriger/Nächster */
    private fun applyButtons() {
        val book = isBook()
        val s = session ?: return
        if (!book) {
            // Musik: Stern (nur für Dateien aus der Bibliothek)
            val uri = player.currentMediaItem?.mediaId
            scope.launch {
                val starred = uri?.let { Library.isStarred(it) }
                if (player.currentMediaItem?.mediaId != uri || isBook()) return@launch
                s.setMediaButtonPreferences(
                    if (starred == null) emptyList()
                    else listOf(
                        CommandButton.Builder(if (starred) CommandButton.ICON_STAR_FILLED else CommandButton.ICON_STAR_UNFILLED)
                            .setSessionCommand(STAR)
                            .setDisplayName(getString(R.string.star_playlist))
                            .setSlots(CommandButton.SLOT_OVERFLOW)
                            .build(),
                    ),
                )
            }
            return
        }
        val backIcon = when (prefs.back) { 10 -> CommandButton.ICON_SKIP_BACK_10; 30 -> CommandButton.ICON_SKIP_BACK_30; else -> CommandButton.ICON_SKIP_BACK_15 }
        val fwdIcon = when (prefs.forward) { 15 -> CommandButton.ICON_SKIP_FORWARD_15; 30 -> CommandButton.ICON_SKIP_FORWARD_30; else -> CommandButton.ICON_SKIP_FORWARD }
        s.setMediaButtonPreferences(
            listOf(
                CommandButton.Builder(if (bookmarkFlash) CommandButton.ICON_BOOKMARK_FILLED else CommandButton.ICON_BOOKMARK_UNFILLED).setSessionCommand(BOOKMARK)
                    .setDisplayName(getString(if (bookmarkFlash) R.string.bookmark_set else R.string.bookmarks)).setSlots(CommandButton.SLOT_OVERFLOW).build(),
                CommandButton.Builder(backIcon).setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .setDisplayName("−${prefs.back} s").setSlots(CommandButton.SLOT_BACK).build(),
                CommandButton.Builder(fwdIcon).setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                    .setDisplayName("+${prefs.forward} s").setSlots(CommandButton.SLOT_FORWARD).build(),
            ),
        )
    }

    /**
     * Was nach außen (Benachrichtigung, Sperrbildschirm, Kopfhörer-/Bluetooth-Tasten) „Vorheriger/Nächster“ heißt,
     * springt bei Hörbüchern um Sekunden. Die App selbst nutzt dafür seekTo…MediaItem und ist nicht betroffen.
     */
    private inner class SessionPlayer(p: Player) : ForwardingPlayer(p) {
        // „Weiter/Zurück“ immer anbieten: bei Hörbüchern springen sie um Sekunden – auch in der letzten Datei,
        // wo der Player selbst „Nächster“ sonst abschaltet (deshalb ging +30 in der Benachrichtigung nicht)
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == Player.COMMAND_SEEK_TO_NEXT || command == Player.COMMAND_SEEK_TO_PREVIOUS || super.isCommandAvailable(command)

        override fun seekToNext() {
            if (isBook()) {
                val d = player.duration
                val t = player.currentPosition + prefs.forward * 1000L
                player.seekTo(if (d != C.TIME_UNSET && d > 0) t.coerceAtMost(d - 500L) else t)
            } else super.seekToNext()
        }

        override fun seekToPrevious() {
            if (isBook()) player.seekTo((player.currentPosition - prefs.back * 1000L).coerceAtLeast(0L))
            else super.seekToPrevious()
        }
    }

    /** Aktuelle Stelle eines Hörbuchs speichern: für den Ordner und für die Datei */
    private fun save() {
        if (!::exo.isInitialized) return
        val item = player.currentMediaItem ?: return
        val ex = item.mediaMetadata.extras ?: return
        if (ex.getInt("section", MUSIC) != BOOKS) return
        val key = ex.getString("container") ?: return
        val pos = player.currentPosition
        val idx = player.currentMediaItemIndex
        val count = player.mediaItemCount
        val name = ex.getString("name") ?: ""
        val title = item.mediaMetadata.title?.toString() ?: ""
        val uri = item.mediaId
        val now = System.currentTimeMillis()
        val dur = player.duration
        // Einzelne Hörbuch-Datei: sie ist das ganze Buch (1/1)
        val single = key.startsWith("f|")
        // Letzte Datei fast zu Ende (≥ 98 %) → gehört
        val done = (single || idx == count - 1) && dur != C.TIME_UNSET && dur > 0 && pos >= dur * 0.98
        scope.launch(Dispatchers.IO) {
            try {
                Library.dao.save(Progress(Keys.progressOf(key), uri, pos, if (single) 0 else idx, if (single) 1 else count, name, now, finished = done))
                Library.dao.save(Progress(Keys.progressOfFile(uri), uri, pos, idx, count, title, now))
            } catch (_: Exception) { }
        }
    }

    /** Hörbuch zu Ende: als gehört markieren; nächstes Abspielen beginnt vorn */
    private fun finished() {
        val item = player.currentMediaItem ?: return
        val ex = item.mediaMetadata.extras ?: return
        if (ex.getInt("section", MUSIC) != BOOKS) return
        val key = ex.getString("container") ?: return
        val uri = item.mediaId
        scope.launch(Dispatchers.IO) {
            try {
                Library.markFinished(key, true)
                Library.dao.forget(Keys.progressOfFile(uri))
            } catch (_: Exception) { }
        }
    }

    // ───────── Lautsprecher im Heimnetz ─────────

    /** Wiedergabe auf ein anderes Ziel legen: Warteschlange, Titel, Stelle, Zufall und Wiederholen wandern mit */
    private fun switchTo(t: Target?) {
        val s = session ?: return
        val cur = remote
        if (t?.id == cur?.target?.id) return
        save()
        val hand = cur?.handoff() ?: Handoff.of(exo)
        if (cur != null) {
            cur.removeListener(listener)
            s.player = localWrap
            remote = null
            cur.release()
        } else exo.pause()

        if (t == null) {
            Serve.stop()
            val play = hand.play && !pauseOnReturn
            pauseOnReturn = false
            if (hand.items.isNotEmpty()) {
                val orig = hand.original
                val cur0 = hand.items.getOrNull(hand.index)
                if (hand.shuffle && orig != null && cur0 != null) {
                    exo.setMediaItems(orig, orig.indexOf(cur0).coerceAtLeast(0), hand.position)
                } else exo.setMediaItems(hand.items, hand.index, hand.position)
                exo.shuffleModeEnabled = hand.shuffle
                exo.repeatMode = hand.repeat
                exo.prepare()
                exo.playWhenReady = play
            }
        } else {
            val r: Outlet = if (t.pc) PcPlayer(this, t) { reason -> lost(reason) } else RemotePlayer(this, t) { reason -> lost(reason) }
            r.pauseAtEnd = Sleep.endOfFile.value
            r.addListener(listener)
            remote = r
            s.player = SessionPlayer(r)
            r.start(hand)
            exo.stop()
        }
        Connect.active.value = t
        updateLocks()
        applyButtons()
    }

    /** Verbindung weg oder Lautsprecher anderweitig belegt: zurück aufs Handy, pausiert */
    private fun lost(reason: Int) {
        if (remote == null) return
        Connect.notice.value = reason
        pauseOnReturn = true
        if (Connect.wanted.value == null) switchTo(null) else Connect.wanted.value = null
    }

    /** Beim Beenden: Lautsprecher anhalten, Server zu */
    private fun dropRemote() {
        remote?.let { r ->
            r.removeListener(listener)
            try { session?.player = localWrap } catch (_: Exception) { }
            r.release()
        }
        remote = null
        Serve.stop()
        Connect.active.value = null
        Connect.wanted.value = null
        updateLocks()
    }

    /** Während auf dem Lautsprecher gespielt wird: WLAN und CPU wach halten (sonst stockt die Übertragung) */
    @Suppress("DEPRECATION")
    private fun updateLocks() {
        // Steuern des PCs braucht keine Daueraufwach-Sperre – nur echtes Streamen zum Lautsprecher
        val need = remote !is PcPlayer && remote?.isPlaying == true
        try {
            if (need) {
                if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "player:connect")?.apply { setReferenceCounted(false) }
                if (cpuLock == null) cpuLock = (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "player:connect")?.apply { setReferenceCounted(false) }
                wifiLock?.acquire()
                cpuLock?.acquire(6 * 3600_000L)
            } else {
                wifiLock?.takeIf { it.isHeld }?.release()
                cpuLock?.takeIf { it.isHeld }?.release()
            }
        } catch (_: Exception) { }
    }

    // ───────── Klang: Lautstärke bis 200 %, Equalizer je Bereich ─────────

    private fun setupFx() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val sid = am.generateAudioSessionId()
            exo.setAudioSessionId(sid)
            eq = try { Equalizer(0, sid) } catch (_: Throwable) { null }
            boost = try { LoudnessEnhancer(sid) } catch (_: Throwable) { null }
            eq?.let { e ->
                val n = e.numberOfBands.toInt()
                val range = e.bandLevelRange
                Fx.bands.value = EqInfo(List(n) { e.getCenterFreq(it.toShort()) / 1000 }, range[0].toInt(), range[1].toInt())
            }
        } catch (_: Throwable) { }
        applyFx()
    }

    private fun applyFx() {
        val section = player.currentMediaItem?.mediaMetadata?.extras?.getInt("section", MUSIC) ?: MUSIC
        // Gesamtfaktor: eingestellte Lautstärke × ReplayGain (nur Musik)
        val factor = (prefs.gain / 100.0) * Math.pow(10.0, (if (section == MUSIC) rgDb else 0f) / 20.0)
        base = factor.coerceAtMost(1.0).toFloat()
        try {
            boost?.let { b ->
                if (factor > 1.0) {
                    b.setTargetGain((2000.0 * kotlin.math.log10(factor)).toInt())
                    b.enabled = true
                } else b.enabled = false
            }
        } catch (_: Throwable) { }
        try {
            val e = eq; val info = Fx.bands.value
            if (e != null && info != null) {
                val on = prefs.eqOn(section)
                if (on) prefs.eqLevels(section, info).forEachIndexed { i, mb -> e.setBandLevel(i.toShort(), mb.coerceIn(info.min, info.max).toShort()) }
                e.enabled = on
            }
        } catch (_: Throwable) { }
        applyVolume()
    }

    private fun applyVolume() { exo.volume = base * fadeFactor }

    // ───────── Schlaf-Timer ─────────

    private suspend fun runTimer(end: Long?) {
        if (end == null) {
            fadeFactor = 1f; applyVolume()
            stopShake()
            return
        }
        if (prefs.shake) startShake() else stopShake()
        while (true) {
            val rem = end - System.currentTimeMillis()
            val fade = prefs.fade * 1000L
            fadeFactor = if (rem < fade) (rem.toFloat() / fade).coerceIn(0f, 1f) else 1f
            applyVolume()
            if (rem <= 0L) {
                player.pause()
                fadeFactor = 1f; applyVolume()
                Sleep.cancel()
                return
            }
            delay(if (rem < fade) 100L else 500L)
        }
    }

    private val shake = object : SensorEventListener {
        private var above = false
        private var peaks = 0
        private var lastPeak = 0L
        private var lastShake = 0L

        override fun onSensorChanged(e: SensorEvent) {
            val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
            val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            val now = SystemClock.elapsedRealtime()
            if (g > 2.2f && !above) {
                above = true
                if (now - lastPeak > 800L) peaks = 0
                peaks++; lastPeak = now
                if (peaks >= 2 && now - lastShake > 1500L) {
                    lastShake = now; peaks = 0
                    scope.launch { onShake() }
                }
            } else if (g < 1.6f) above = false
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun onShake() {
        if (Sleep.endAt.value == null) return
        Sleep.restart()
        fadeFactor = 1f; applyVolume()
        if (!player.isPlaying && player.mediaItemCount > 0) player.play()
        buzz()
    }

    private fun startShake() {
        if (shakeOn) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sensors = sm
        shakeOn = sm.registerListener(shake, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun stopShake() {
        if (!shakeOn) return
        sensors?.unregisterListener(shake)
        shakeOn = false
    }

    private fun buzz() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            v?.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    // ───────── Session ─────────

    /** Startet etwas im Dienst-Prozess und liefert das Ergebnis als Future (für die Session-Rückrufe) */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { try { f.set(block()) } catch (e: Throwable) { f.setException(e) } }
        return f
    }

    /** Abspielbaren Eintrag (Android Auto, Suche, Sprachbefehl) in die ganze Warteschlange auflösen */
    private suspend fun resolve(id: String): MediaSession.MediaItemsWithStartPosition? {
        val (raw, uri) = Browse.parsePlay(id) ?: return null
        val shuffle = raw.startsWith("s")
        val key = if (shuffle) raw.removePrefix("s") else raw
        val plan = Library.plan(key, uri) ?: return null
        player.shuffleModeEnabled = shuffle && plan.section == MUSIC
        if (plan.section == BOOKS) player.repeatMode = Player.REPEAT_MODE_OFF
        prefs.setLast(key)
        val start = if (shuffle) plan.items.indices.random() else plan.index
        return MediaSession.MediaItemsWithStartPosition(plan.items, start, if (shuffle) 0L else plan.position)
    }

    private suspend fun searchPlay(q: String): MediaSession.MediaItemsWithStartPosition? {
        if (q.isBlank()) {
            val key = prefs.last(prefs.lastSection) ?: return null
            return resolve(Browse.playId(key))
        }
        val hit = Browse.search(this, q).firstOrNull() ?: return null
        val id = hit.mediaId
        if (id.startsWith("p|")) return resolve(id)
        val p = id.split('|', limit = 4) // Ordner-Treffer: ganzen Ordner spielen
        if (p.size < 4) return null
        val s = p[1].toIntOrNull() ?: MUSIC
        return resolve(Browse.playId(Keys.tree(s, p[2], p[3])))
    }

    private val STAR = SessionCommand("de.philipdecker.playerconnected.STAR", Bundle.EMPTY)
    private val BOOKMARK = SessionCommand("de.philipdecker.playerconnected.BOOKMARK", Bundle.EMPTY)
    /** Nach dem Setzen kurz gefülltes Symbol in Benachrichtigung/Sperrbildschirm/Auto als Bestätigung */
    private var bookmarkFlash = false
    private var flashJob: kotlinx.coroutines.Job? = null

    private val callback = object : MediaLibrarySession.Callback {
        /** Stern-Befehl für Benachrichtigung und Android Auto freigeben */
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(STAR).add(BOOKMARK).build())
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == STAR.customAction) {
                val uri = player.currentMediaItem?.mediaId ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                return future { Library.toggleStar(uri); SessionResult(SessionResult.RESULT_SUCCESS) }
            }
            if (customCommand.customAction == BOOKMARK.customAction) {
                val item = player.currentMediaItem ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                val pos = player.currentPosition
                buzz()
                bookmarkFlash = true
                applyButtons()
                flashJob?.cancel()
                flashJob = scope.launch { delay(2500); bookmarkFlash = false; applyButtons() }
                return future { Library.quickBookmark(item, pos); SessionResult(SessionResult.RESULT_SUCCESS) }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        /** Titel aus der App kommen mit der Datei-Adresse (requestMetadata); Auto-Einträge (p|…) werden aufgelöst */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.flatMap { mi ->
                if (mi.mediaId.startsWith("p|")) resolve(mi.mediaId)?.mediaItems ?: emptyList()
                // Titel vom Computer (mlpc://…): Offline-Kopie oder Stream
                else listOf(mi.buildUpon().setUri(Pc.realUri(mi.requestMetadata.mediaUri ?: Uri.parse(mi.mediaId))).build())
            }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val one = mediaItems.singleOrNull()
            val query = one?.requestMetadata?.searchQuery
            if (one != null && (one.mediaId.startsWith("p|") || query != null)) {
                return future {
                    (if (one.mediaId.startsWith("p|")) resolve(one.mediaId) else searchPlay(query ?: ""))
                        ?: throw IllegalStateException("nichts gefunden")
                }
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        /** Play auf dem Kopfhörer/im Auto, obwohl die App beendet war: dort weitermachen, wo zuletzt gehört wurde */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val key = prefs.last(prefs.lastSection) ?: throw IllegalStateException("nichts gespielt")
            val plan = withContext(Dispatchers.IO) { Library.plan(key) } ?: throw IllegalStateException("leer")
            MediaSession.MediaItemsWithStartPosition(plan.items, plan.index, plan.position)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(Browse.root(this@PlaybackService), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = Browse.children(this@PlaybackService, parentId)
            val part = if (pageSize > 0) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(part, params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            if (mediaId == Browse.ROOT) LibraryResult.ofItem(Browse.root(this@PlaybackService), null)
            else {
                val first = resolve(mediaId)?.let { it.mediaItems.getOrNull(it.startIndex) }
                if (first != null) LibraryResult.ofItem(first, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            }
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = future {
            val n = Browse.search(this@PlaybackService, query).size
            session.notifySearchResultChanged(browser, query, n, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = Browse.search(this@PlaybackService, query)
            val part = if (pageSize > 0) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(part, params)
        }
    }
}
