package de.philipdecker.playerconnected

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Folder(val section: Int, val root: String, val path: String, val name: String) : Screen
    data object Player : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data class Export(val preselect: Long?) : Screen
    data object Playlists : Screen
    data class Playlist(val id: Long, val name: String) : Screen
}

/** Was gerade läuft – als Compose-Zustand, gespeist vom MediaController */
class NowPlaying {
    var item by mutableStateOf<MediaItem?>(null)
    var playing by mutableStateOf(false)
    var index by mutableIntStateOf(0)
    var count by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var speed by mutableFloatStateOf(1f)
    /** Läuft auf einem Lautsprecher im Heimnetz; dessen Lautstärke 0–100 */
    var remote by mutableStateOf(false)
    var volume by mutableIntStateOf(0)
    /** Zählt hoch bei Pause/Titelwechsel → Fortschrittsanzeigen laden neu */
    var tick by mutableIntStateOf(0)

    val section: Int get() = item?.mediaMetadata?.extras?.getInt("section", MUSIC) ?: MUSIC
    val container: String? get() = item?.mediaMetadata?.extras?.getString("container")

    fun update(p: Player) {
        val before = item?.mediaId to playing
        item = p.currentMediaItem
        playing = p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE
        index = p.currentMediaItemIndex
        count = p.mediaItemCount
        shuffle = p.shuffleModeEnabled
        repeat = p.repeatMode
        speed = p.playbackParameters.speed
        remote = p.deviceInfo.playbackType == androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE
        volume = if (remote) p.deviceVolume else 0
        if (before != (item?.mediaId to playing)) tick++
    }
}

/** Was die Bildschirme von der Activity brauchen */
interface Actions {
    val controller: MediaController?
    fun open(s: Screen)
    fun back()
    fun play(key: String, startUri: String? = null, shuffle: Boolean = false, fromStart: Boolean = false, startPos: Long? = null)
    fun toggle()
    fun pickFolder(section: Int, initial: String? = null)
    fun removeFolder(section: Int, uri: String)
    fun rescan()
    fun exportPlaylist(id: Long)
    /** Playlisten auf einen Stick: Ziel wählen lassen, dann exportieren */
    fun exportTo(ids: List<Long>, layout: String)
}

class MainActivity : ComponentActivity(), Actions {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    private val warp = mutableFloatStateOf(0f)
    private val now = NowPlaying()
    private var ctrl by mutableStateOf<MediaController?>(null)
    override val controller: MediaController? get() = ctrl
    private var future: ListenableFuture<MediaController>? = null
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private var pickSection = MUSIC
    /** Merkt Scroll-Position & Co. je Bildschirm, solange er im Verlauf ist */
    private var holder: SaveableStateHolder? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { Library.addRoot(this@MainActivity, pickSection, uri) }
    }

    /** Export-Ziel wählen (Ordner mit Schreibrecht), dann im Hintergrund kopieren */
    private var exportIds: List<Long> = emptyList()
    private var exportLayout = "numbered"
    private val exportPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && exportIds.isNotEmpty()) {
            val ids = exportIds
            val layout = exportLayout
            scope.launch { Library.exportMany(this@MainActivity, ids, layout, uri) }
        }
    }


    override fun exportPlaylist(id: Long) = open(Screen.Export(id))

    override fun exportTo(ids: List<Long>, layout: String) {
        exportIds = ids
        exportLayout = layout
        try { exportPicker.launch(null) } catch (_: Exception) { }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = now.update(player)
        /** Wiedergabe-Fehler sichtbar machen (statt still zu verschwinden) */
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            val name = now.item?.mediaMetadata?.title ?: ""
            android.widget.Toast.makeText(this@MainActivity, "$name: ${error.errorCodeName}" + (error.cause?.message?.let { " – $it" } ?: ""), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(resources.configuration)
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        Library.init(this)
        Pc.init(this)
        if (intent?.getBooleanExtra("player", false) == true) stack.add(Screen.Player)
        takeExternal(intent)

        // Zurück = eine Ebene zurück; ganz oben: App schließen (Wiedergabe läuft weiter)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        // Stiller Abgleich mit den Ordnern bei jedem Start (nicht öfter als einmal pro Minute)
        if (System.currentTimeMillis() - prefs.lastScan > 60_000L) scope.launch { Library.rescan(this@MainActivity) }

        setContent {
            CompositionLocalProvider(
                LocalFont provides fontFor(prefs.font),
                LocalScale provides sizeFactor(prefs.fontSize),
                LocalWarp provides warp,
            ) {
                val states = rememberSaveableStateHolder()
                holder = states
                Box(Modifier.fillMaxSize().background(Bg)) {
                    Starfield(prefs.stars, warp, warpFactor(prefs.warpLevel))
                    AnimatedContent(
                        targetState = stack.last(),
                        transitionSpec = {
                            // Tiefer hinein: Neues kommt aus der Nähe, Altes fliegt nach vorn weg
                            val deeper = depthOf(targetState) > depthOf(initialState)
                            val enter = fadeIn(tween(320, 40)) + scaleIn(tween(420, easing = FastOutSlowInEasing), initialScale = if (deeper) 0.9f else 1.08f)
                            val exit = fadeOut(tween(200)) + scaleOut(tween(360, easing = FastOutSlowInEasing), targetScale = if (deeper) 1.08f else 0.92f)
                            enter togetherWith exit
                        },
                        label = "screen",
                    ) { s ->
                        states.SaveableStateProvider(s.toString()) {
                            when (s) {
                                Screen.Home -> HomeScreen(this@MainActivity, prefs, now)
                                is Screen.Folder -> FolderScreen(this@MainActivity, prefs, now, s)
                                Screen.Player -> PlayerScreen(this@MainActivity, prefs, now)
                                Screen.Settings -> SettingsScreen(this@MainActivity, prefs)
                                Screen.Licenses -> LicensesScreen(this@MainActivity)
                                is Screen.Export -> ExportScreen(this@MainActivity, s)
                                Screen.Playlists -> PlaylistsScreen(this@MainActivity, now)
                                is Screen.Playlist -> PlaylistScreen(this@MainActivity, prefs, now, s)
                            }
                        }
                    }
                    // Fortschritt (Offline, Hochladen, Export) in jedem Bildschirm
                    TransferLine(Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars))
                }
            }
        }
    }

    private fun depthOf(s: Screen): Int = when (s) {
        Screen.Home -> 0
        is Screen.Folder -> 1 + s.path.count { it == '/' } + (if (s.path.isEmpty()) 0 else 1)
        Screen.Settings -> 50
        Screen.Licenses -> 51
        is Screen.Export -> 12
        Screen.Playlists -> 10
        is Screen.Playlist -> 11
        Screen.Player -> 100
    }

    /** Hell/Dunkel vom System übernehmen (wie Minimal): Farben und Symbolfarbe der Systemleisten */
    private fun applyTheme(c: android.content.res.Configuration) {
        val dark = (c.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_NO
        Palette.dark = dark
        val t = android.graphics.Color.TRANSPARENT
        val bar = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t)
        enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme(newConfig)
    }

    override fun onStart() {
        super.onStart()
        Sync.refresh()
        // Solange die App offen ist (jeder Bildschirm): Verbindung zu Minimal Library
        Pc.start(this)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try { f.get() } catch (_: Exception) { return@addListener }
            c.addListener(listener)
            ctrl = c
            now.update(c)
            val ext = pendingExternal
            if (ext != null) { pendingExternal = null; openExternal(ext) }
            else if (c.mediaItemCount == 0) restore(c)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        super.onStop()
        Pc.stop()
        ctrl?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        ctrl = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    /** „Öffnen mit“ aus Dateimanager oder anderer App */
    private var pendingExternal: android.net.Uri? = null

    private fun takeExternal(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val uri = i.data ?: return
        i.action = null // nicht erneut abspielen (z. B. nach Drehen)
        if (ctrl != null) openExternal(uri) else pendingExternal = uri
    }

    private fun openExternal(uri: android.net.Uri) {
        val name = uri.lastPathSegment?.lowercase() ?: ""
        val section = if (name.endsWith(".m4b")) BOOKS else MUSIC
        play(Keys.external(section, uri.toString()))
        if (stack.last() != Screen.Player) stack.add(Screen.Player)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeExternal(intent)
        if (intent.getBooleanExtra("player", false) && stack.last() != Screen.Player) stack.add(Screen.Player)
    }

    /** App frisch geöffnet: den zuletzt gehörten Titel an der gemerkten Stelle bereitlegen (ohne zu starten) */
    private fun restore(c: MediaController) {
        val key = prefs.last(prefs.lastSection) ?: return
        scope.launch {
            val plan = Library.plan(key) ?: return@launch
            if (c.mediaItemCount != 0) return@launch
            c.setMediaItems(plan.items, plan.index, plan.position)
            c.prepare()
        }
    }

    // ───────── Actions ─────────

    override fun open(s: Screen) { if (stack.last() != s) stack.add(s) }

    override fun back() {
        if (stack.size > 1) {
            val gone = stack.removeAt(stack.lastIndex)
            // Verlassener Ordner: beim nächsten Öffnen wieder oben beginnen (Player/Einstellungen egal)
            if ((gone is Screen.Folder || gone is Screen.Playlist) && gone !in stack) holder?.removeState(gone.toString())
        } else moveTaskToBack(true)
    }

    override fun play(key: String, startUri: String?, shuffle: Boolean, fromStart: Boolean, startPos: Long?) {
        val c = ctrl ?: return
        scope.launch {
            val plan = Library.plan(key, startUri, fromStart, startPos) ?: return@launch
            c.shuffleModeEnabled = shuffle && plan.section == MUSIC
            if (plan.section == BOOKS) c.repeatMode = Player.REPEAT_MODE_OFF
            c.setMediaItems(plan.items, if (shuffle && startUri == null) (plan.items.indices).random() else plan.index, plan.position)
            c.setPlaybackSpeed(if (plan.section == BOOKS) prefs.bookSpeed else 1f)
            c.prepare()
            c.play()
            if (!key.startsWith("x|")) prefs.setLast(key) // fremde Dateien: Zugriff gilt nur vorübergehend
        }
    }

    override fun toggle() {
        val c = ctrl ?: return
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) { c.pause(); return }
        if (c.mediaItemCount == 0) return
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0L)
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    override fun pickFolder(section: Int, initial: String?) {
        pickSection = section
        // Ordner aus der anderen Player-App: Auswahl gleich dort öffnen
        val start = initial?.let {
            try {
                val tree = android.net.Uri.parse(it)
                android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            } catch (_: Exception) { null }
        }
        try { picker.launch(start) } catch (_: Exception) { }
    }

    override fun removeFolder(section: Int, uri: String) {
        scope.launch { Library.removeRoot(this@MainActivity, section, uri) }
    }

    override fun rescan() {
        scope.launch { Library.rescan(this@MainActivity) }
    }
}
