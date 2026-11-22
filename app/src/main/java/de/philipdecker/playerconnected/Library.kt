package de.philipdecker.playerconnected

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// ───────────────────────── Datenbank (Index der Dateien, Stellen, Lesezeichen) ─────────────────────────

/** Eine Audiodatei. dir = Pfad relativ zum gewählten Ordner („Artist/Album“, "" = direkt im Ordner) */
@Entity(tableName = "track", indices = [Index(value = ["root", "dir"]), Index(value = ["uri"])])
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val section: Int,
    val root: String,
    val dir: String,
    val name: String,
    val title: String,
    val uri: String,
    @ColumnInfo(defaultValue = "0") val size: Long = 0,
    @ColumnInfo(defaultValue = "0") val modified: Long = 0,
    /** Dauer in ms (0 = noch unbekannt; wird für Hörbücher im Hintergrund ermittelt) */
    @ColumnInfo(defaultValue = "0") val duration: Long = 0,
    /** Titel/Interpret aus den Tags (nur für die umschaltbare Anzeige; Navigation bleibt nach Ordnern) */
    val tagTitle: String = "",
    val tagArtist: String = "",
    /** Tags/Dauer schon gelesen */
    val tagged: Boolean = false,
    /** Cover aus den Tags (Hash, leer = keins) */
    val cover: String = "",
)

data class DirCover(val dir: String, val minC: String, val maxC: String)

data class Stats(val n: Int, val ms: Long)

/** Volltextsuche über Titel und Ordnerpfad (Umlaute/Akzente egal) */
@Fts4(contentEntity = Track::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "track_fts")
data class TrackFts(val title: String, val dir: String)

/** Ein Unterordner mit Anzahl der Dateien direkt darin (files) und insgesamt (total) */
@Entity(tableName = "dir", primaryKeys = ["root", "path"], indices = [Index(value = ["root", "parent"])])
data class Dir(
    val root: String,
    val path: String,
    val section: Int,
    val parent: String,
    val name: String,
    val files: Int,
    val total: Int,
)

/** Gespeicherte Stelle: id = „c|container“ (Ordner/Hörbuch) oder „u|uri“ (einzelne Datei) */
@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val id: String,
    val uri: String,
    val position: Long,
    val idx: Int,
    val count: Int,
    val title: String,
    val updated: Long,
    @ColumnInfo(defaultValue = "0") val finished: Boolean = false,
)

/** Lesezeichen mit optionaler Notiz */
@Entity(tableName = "bookmark", indices = [Index(value = ["uri"]), Index(value = ["container"])])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val container: String,
    val title: String,
    val position: Long,
    val note: String,
    val created: Long,
)

/** Playlist (nur Musik) */
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val created: Long,
    /** Feste Kennung für den Abgleich mit der anderen Player-App */
    val uid: String = java.util.UUID.randomUUID().toString().replace("-", ""),
)

/**
 * Eintrag einer Playlist. Verweist per Fremdschlüssel auf track.id: Verschwindet eine Datei beim Abgleich
 * (gelöscht/verschoben) oder wird ihr Ordner entfernt, löscht SQLite den Eintrag automatisch (CASCADE).
 */
@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(entity = Playlist::class, parentColumns = ["id"], childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Track::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playlistId"]), Index(value = ["trackId"])],
)
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val trackId: Long,
    val pos: Int,
)

data class PlaylistInfo(val id: Long, val name: String, val count: Int, val duration: Long, val uid: String)

/** Summe der bekannten Dauern je Ordnerpfad */
data class DirDuration(val dir: String, val ms: Long)

/** Titel einer Playlist mit ihrem Eintrag (für Verschieben/Entfernen) */
data class PlaylistTrack(val itemId: Long, val pos: Int, val track: Track)

@Dao
abstract class LibDao {
    @Query("SELECT * FROM dir WHERE root = :root AND parent = :parent")
    abstract suspend fun dirs(root: String, parent: String): List<Dir>

    @Query("SELECT * FROM dir WHERE root = :root")
    abstract suspend fun dirsOfRoot(root: String): List<Dir>

    @Query("SELECT * FROM dir WHERE root = :root AND path = :path LIMIT 1")
    abstract suspend fun dir(root: String, path: String): Dir?

    @Query("SELECT * FROM track WHERE root = :root AND dir = :dir")
    abstract suspend fun files(root: String, dir: String): List<Track>

    @Query("SELECT * FROM track WHERE root = :root")
    abstract suspend fun tracksOfRoot(root: String): List<Track>

    @Query("SELECT * FROM track WHERE root = :root AND (:path = '' OR dir = :path OR substr(dir, 1, length(:path) + 1) = :path || '/')")
    abstract suspend fun subtree(root: String, path: String): List<Track>

    @Query("SELECT track.* FROM track JOIN track_fts ON track.id = track_fts.rowid WHERE track_fts MATCH :q AND track.section = :section LIMIT 300")
    abstract suspend fun search(q: String, section: Int): List<Track>

    @Query("SELECT * FROM dir WHERE section = :section AND name LIKE '%' || :q || '%' LIMIT 60")
    abstract suspend fun searchDirs(q: String, section: Int): List<Dir>

    @Query("SELECT COUNT(*) FROM track WHERE section = :section")
    abstract suspend fun count(section: Int): Int

    @Query("SELECT uri FROM track WHERE section = :section ORDER BY root, dir, name")
    abstract suspend fun urisOf(section: Int): List<String>

    @Query("SELECT * FROM track WHERE uri = :uri LIMIT 1")
    abstract suspend fun byUri(uri: String): Track?

    @Query("SELECT * FROM track WHERE section = :section AND (duration = 0 OR tagged = 0) LIMIT :n")
    abstract suspend fun withoutDetails(section: Int, n: Int): List<Track>

    @Query("UPDATE track SET duration = :ms, tagTitle = :title, tagArtist = :artist, cover = :cover, tagged = 1 WHERE id = :id")
    abstract suspend fun setDetails(id: Long, ms: Long, title: String, artist: String, cover: String)

    @Query("SELECT dir AS dir, MIN(cover) AS minC, MAX(cover) AS maxC FROM track WHERE root = :root GROUP BY dir")
    abstract suspend fun dirCovers(root: String): List<DirCover>

    @Query("UPDATE track SET cover = :cover WHERE id IN (:ids)")
    abstract suspend fun setCover(ids: List<Long>, cover: String)

    @Query("SELECT cover FROM track WHERE uri = :uri LIMIT 1")
    abstract suspend fun coverOf(uri: String): String?

    @Query(
        "SELECT COUNT(*) AS n, COALESCE(SUM(CASE WHEN duration > 0 THEN duration ELSE 0 END), 0) AS ms FROM track " +
            "WHERE root = :root AND (:path = '' OR dir = :path OR substr(dir, 1, length(:path) + 1) = :path || '/')",
    )
    abstract suspend fun stats(root: String, path: String): Stats

    @Query("SELECT * FROM track WHERE section = :section AND (tagTitle LIKE '%' || :q || '%' OR tagArtist LIKE '%' || :q || '%') LIMIT 200")
    abstract suspend fun searchTags(q: String, section: Int): List<Track>

    @Query("UPDATE track SET duration = :ms WHERE uri = :uri")
    abstract suspend fun setDuration(uri: String, ms: Long)

    @Query("DELETE FROM track WHERE root = :root")
    abstract suspend fun deleteTracks(root: String)

    @Query("DELETE FROM track WHERE id IN (:ids)")
    abstract suspend fun deleteTrackIds(ids: List<Long>)

    @Query("DELETE FROM dir WHERE root = :root")
    abstract suspend fun deleteDirs(root: String)

    @Query("SELECT DISTINCT root FROM track")
    abstract suspend fun knownRoots(): List<String>

    @Insert
    abstract suspend fun insertTracks(t: List<Track>)

    @Update
    abstract suspend fun updateTracks(t: List<Track>)

    @Insert
    abstract suspend fun insertDirs(d: List<Dir>)

    /** Abgleich eines Ordners: neu aufnehmen, Geändertes aktualisieren, Fehlendes löschen – in einem Rutsch */
    @Transaction
    open suspend fun sync(root: String, insert: List<Track>, update: List<Track>, delete: List<Long>, dirs: List<Dir>?) {
        delete.chunked(500).forEach { deleteTrackIds(it) }
        update.chunked(500).forEach { updateTracks(it) }
        insert.chunked(500).forEach { insertTracks(it) }
        if (dirs != null) { deleteDirs(root); dirs.chunked(500).forEach { insertDirs(it) } }
        cleanPlaylists()
    }

    /** Sicherheitsnetz zusätzlich zu CASCADE: Einträge ohne Datei entfernen */
    @Query("DELETE FROM playlist_item WHERE trackId NOT IN (SELECT id FROM track)")
    abstract suspend fun cleanPlaylists()

    // ───── Playlisten ─────

    @Query(
        "SELECT p.id AS id, p.name AS name, p.uid AS uid, " +
            "(SELECT COUNT(*) FROM playlist_item i WHERE i.playlistId = p.id) AS count, " +
            "(SELECT COALESCE(SUM(CASE WHEN t.duration > 0 THEN t.duration ELSE 0 END), 0) FROM playlist_item i JOIN track t ON t.id = i.trackId WHERE i.playlistId = p.id) AS duration " +
            "FROM playlist p ORDER BY p.name COLLATE NOCASE",
    )
    abstract suspend fun playlists(): List<PlaylistInfo>

    @Query("SELECT dir AS dir, SUM(CASE WHEN duration > 0 THEN duration ELSE 0 END) AS ms FROM track WHERE root = :root GROUP BY dir")
    abstract suspend fun dirDurations(root: String): List<DirDuration>

    @Query("SELECT COUNT(*) FROM playlist_item WHERE playlistId = :pl AND trackId = :track")
    abstract suspend fun inPlaylist(pl: Long, track: Long): Int

    @Query("SELECT DISTINCT trackId FROM playlist_item WHERE playlistId = :pl AND trackId IN (:ids)")
    abstract suspend fun presentIn(pl: Long, ids: List<Long>): List<Long>

    @Query("DELETE FROM playlist_item WHERE playlistId = :pl AND trackId IN (:ids)")
    abstract suspend fun removeTracks(pl: Long, ids: List<Long>)

    @Query("DELETE FROM playlist_item WHERE playlistId = :pl AND trackId = :track")
    abstract suspend fun removeTrack(pl: Long, track: Long)

    @Query("SELECT * FROM playlist WHERE id = :id")
    abstract suspend fun playlist(id: Long): Playlist?

    @Insert
    abstract suspend fun insertPlaylist(p: Playlist): Long

    @Query("SELECT * FROM playlist")
    abstract suspend fun allPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlist WHERE uid = :uid LIMIT 1")
    abstract suspend fun playlistByUid(uid: String): Playlist?

    @Query("DELETE FROM playlist_item WHERE playlistId = :id")
    abstract suspend fun clearItems(id: Long)

    /** Inhalt einer Playlist komplett ersetzen (Abgleich) */
    @Transaction
    open suspend fun replaceItems(id: Long, trackIds: List<Long>) { clearItems(id); addToPlaylist(id, trackIds) }

    @Query("UPDATE playlist SET name = :name WHERE id = :id")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlist WHERE id = :id")
    abstract suspend fun deletePlaylist(id: Long)

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY pos")
    abstract suspend fun playlistItems(id: Long): List<PlaylistItem>

    @Query("SELECT * FROM track WHERE id IN (:ids)")
    abstract suspend fun tracksById(ids: List<Long>): List<Track>

    @Query("SELECT COALESCE(MAX(pos), -1) FROM playlist_item WHERE playlistId = :id")
    abstract suspend fun maxPos(id: Long): Int

    @Insert
    abstract suspend fun insertItems(items: List<PlaylistItem>)

    @Query("DELETE FROM playlist_item WHERE id = :itemId")
    abstract suspend fun removeItem(itemId: Long)

    @Query("UPDATE playlist_item SET pos = :pos WHERE id = :itemId")
    abstract suspend fun setPos(itemId: Long, pos: Int)

    @Transaction
    open suspend fun addToPlaylist(id: Long, trackIds: List<Long>) {
        var p = maxPos(id) + 1
        trackIds.chunked(500).forEach { chunk -> insertItems(chunk.map { PlaylistItem(playlistId = id, trackId = it, pos = p++) }) }
    }

    @Transaction
    open suspend fun swap(a: PlaylistItem, b: PlaylistItem) { setPos(a.id, b.pos); setPos(b.id, a.pos) }

    @Transaction
    open suspend fun drop(root: String) { deleteTracks(root); deleteDirs(root); cleanPlaylists() }

    @Query("SELECT * FROM progress WHERE id = :id")
    abstract suspend fun progress(id: String): Progress?

    @Query("SELECT * FROM progress WHERE id IN (:ids)")
    abstract suspend fun progressFor(ids: List<String>): List<Progress>

    @Query("SELECT * FROM progress WHERE id LIKE 'c|%' AND finished = 0 ORDER BY updated DESC LIMIT :n")
    abstract suspend fun recent(n: Int): List<Progress>

    @Upsert
    abstract suspend fun save(p: Progress)

    @Query("DELETE FROM progress WHERE id = :id")
    abstract suspend fun forget(id: String)

    @Insert
    abstract suspend fun addBookmark(b: Bookmark)

    @Query("UPDATE bookmark SET note = :note WHERE id = :id")
    abstract suspend fun setBookmarkNote(id: Long, note: String)

    @Query("DELETE FROM bookmark WHERE id = :id")
    abstract suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM bookmark WHERE uri = :uri ORDER BY position")
    abstract suspend fun bookmarksOf(uri: String): List<Bookmark>

    @Query("SELECT * FROM bookmark WHERE container = :container OR substr(container, 1, length(:container) + 1) = :container || '/' ORDER BY title, position")
    abstract suspend fun bookmarksIn(container: String): List<Bookmark>
}

@Database(
    entities = [Track::class, TrackFts::class, Dir::class, Progress::class, Bookmark::class, Playlist::class, PlaylistItem::class],
    version = 6, exportSchema = false,
)
abstract class LibDb : RoomDatabase() {
    abstract fun dao(): LibDao
}

/** Version 1 → 2: Größe/Änderungsdatum/Dauer, „gehört“, Lesezeichen. Gemerkte Stellen bleiben erhalten. */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `track` ADD COLUMN `size` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `track` ADD COLUMN `modified` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `track` ADD COLUMN `duration` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `progress` ADD COLUMN `finished` INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, " +
                "`container` TEXT NOT NULL, `title` TEXT NOT NULL, `position` INTEGER NOT NULL, `note` TEXT NOT NULL, `created` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_uri` ON `bookmark` (`uri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_container` ON `bookmark` (`container`)")
    }
}

/** Version 2 → 3: Playlisten (Einträge hängen per Fremdschlüssel an den Dateien) */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlist` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `created` INTEGER NOT NULL)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `playlist_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, " +
                "`trackId` INTEGER NOT NULL, `pos` INTEGER NOT NULL, " +
                "FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`trackId`) REFERENCES `track`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_item_playlistId` ON `playlist_item` (`playlistId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_item_trackId` ON `playlist_item` (`trackId`)")
    }
}

/** Version 3 → 4: Titel/Interpret aus Tags */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `track` ADD COLUMN `tagTitle` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `track` ADD COLUMN `tagArtist` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `track` ADD COLUMN `tagged` INTEGER NOT NULL DEFAULT 0")
    }
}

/** Version 4 → 5: Cover; alle Dateien einmal neu auslesen */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `track` ADD COLUMN `cover` TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE `track` SET `tagged` = 0")
    }
}

/** Version 5 → 6: feste Kennung je Playlist (Abgleich zwischen den beiden Player-Apps) */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `playlist` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE `playlist` SET `uid` = lower(hex(randomblob(16)))")
    }
}

/** Eintrag in einer Ordneransicht */
sealed interface Entry {
    val id: String
    val label: String

    data class Folder(val root: String, val path: String, val name: String, val files: Int, val total: Int) : Entry {
        override val id get() = "d:$root|$path"
        override val label get() = name
    }

    data class File(val track: Track) : Entry {
        override val id get() = "f:${track.uri}"
        override val label get() = Library.label(track)
    }
}

/** Stand eines Hörbuchs für die Anzeige */
data class BookState(val progress: Progress?, val percent: Int?) {
    val finished get() = progress?.finished == true
}

// ───────────────────────── Bibliothek ─────────────────────────

object Library {
    private lateinit var db: LibDb
    private lateinit var app: Context
    val dao: LibDao get() = db.dao()

    /** Für Änderungen aus Menüs, die sich sofort schließen (ihr eigener Scope würde mit abgebrochen) */
    val bg = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

    /** Läuft gerade ein Einlesen? / Zähler, damit Listen nach Änderungen neu laden */
    val scanning = MutableStateFlow(false)
    val version = MutableStateFlow(0)
    private val lock = Mutex()

    private val AUDIO = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "wave", "mka", "amr", "awb", "3gp", "weba", "aif", "aiff",
    )

    @Synchronized
    fun init(ctx: Context) {
        if (::db.isInitialized) return
        app = ctx.applicationContext
        db = Room.databaseBuilder(app, LibDb::class.java, "library.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .fallbackToDestructiveMigration(true)
            .build()
    }

    fun isAudio(name: String, mime: String?): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in AUDIO) return true
        return mime != null && mime.startsWith("audio/") && ext != "m3u" && ext != "pls" && ext != "cue"
    }

    /** Anzeigename eines gewählten Ordners */
    fun rootName(ctx: Context, tree: Uri): String {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return try {
            ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: tree.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "?"
    }

    /** Ordner hinzufügen: Lesezugriff dauerhaft behalten, sofort einlesen */
    suspend fun addRoot(ctx: Context, section: Int, tree: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val prefs = Prefs.get(ctx)
        val name = withContext(Dispatchers.IO) { rootName(ctx, tree) }
        prefs.addRoot(section, Root(tree.toString(), name))
        prefs.removePending(section, tree.toString())
        lock.withLock {
            scanning.value = true
            try { withContext(Dispatchers.IO) { scanRoot(ctx, section, tree.toString()) } } finally { scanning.value = false; version.value++ }
        }
        withContext(Dispatchers.IO) { fillDurations(ctx) }
        Waves.prepareLibrary(ctx)
    }

    suspend fun removeRoot(ctx: Context, section: Int, uri: String) {
        val prefs = Prefs.get(ctx)
        prefs.removeRoot(section, uri)
        lock.withLock {
            withContext(Dispatchers.IO) {
                dao.drop(uri)
                if (prefs.roots(1 - section).none { it.uri == uri }) {
                    try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                }
            }
            version.value++
        }
    }

    /**
     * Stiller Abgleich aller Ordner im Hintergrund: Neues aufnehmen, Geändertes aktualisieren, Fehlendes löschen.
     * Listen laden nur neu, wenn sich wirklich etwas geändert hat.
     */
    suspend fun rescan(ctx: Context) {
        if (scanning.value) return
        val prefs = Prefs.get(ctx)
        lock.withLock {
            scanning.value = true
            try {
                withContext(Dispatchers.IO) {
                    val wanted = HashSet<String>()
                    var changed = false
                    for (s in listOf(MUSIC, BOOKS)) for (r in prefs.roots(s)) {
                        wanted += r.uri
                        if (scanRoot(ctx, s, r.uri)) changed = true
                    }
                    // Wurzeln vom Computer (Minimal Library) verwaltet Pc selbst
                    dao.knownRoots().filter { it !in wanted && !Pc.isPcRoot(it) }.forEach { dao.drop(it); changed = true }
                    if (changed) version.value++
                }
                prefs.lastScan = System.currentTimeMillis()
            } finally { scanning.value = false }
        }
        withContext(Dispatchers.IO) { fillDurations(ctx) }
        Waves.prepareLibrary(ctx) // Wellenformen der Musik vorab im Hintergrund
    }

    /** Dauer der Hörbuch-Dateien nachtragen (für Prozentanzeige), in kleinen Portionen */
    private suspend fun fillDurations(ctx: Context) {
        var any = false
        var n = 0
        for (section in listOf(BOOKS, MUSIC)) while (true) {
            val batch = dao.withoutDetails(section, 40)
            if (batch.isEmpty()) break
            n += batch.size
            if (n % 400 == 0) version.value++
            for (t in batch) {
                var ms: Long? = null; var title = ""; var artist = ""; var cover = ""
                try {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(ctx, Uri.parse(t.uri))
                            ms = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                            title = extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim() ?: ""
                            artist = (extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                ?: extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))?.trim() ?: ""
                            cover = embeddedPicture?.let { Covers.save(ctx, it) } ?: ""
                        } finally { release() }
                    }
                } catch (_: Throwable) { }
                // -1 = Dauer nicht lesbar, nicht erneut versuchen
                dao.setDetails(t.id, (if (t.duration > 0) t.duration else ms?.takeIf { it > 0 }) ?: -1L, title, artist, cover)
                any = true
            }
        }
        if (any) version.value++
    }

    /** Einen Ordnerbaum über den Android-Speicherzugriff durchgehen und mit der Datenbank abgleichen. true = etwas geändert */
    private suspend fun scanRoot(ctx: Context, section: Int, rootUri: String): Boolean {
        val tree = Uri.parse(rootUri)
        val cr = ctx.contentResolver
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (_: Exception) { return false }
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val fresh = ArrayList<Track>()
        val direct = HashMap<String, Int>()
        val allDirs = ArrayList<String>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId to "")
        var ok = false
        while (stack.isNotEmpty()) {
            val (docId, path) = stack.removeLast()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            try {
                cr.query(children, cols, null, null, null)?.use { c ->
                    ok = true
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        if (name.startsWith(".")) continue
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val sub = if (path.isEmpty()) name else "$path/$name"
                            allDirs += sub
                            stack.add(id to sub)
                        } else if (isAudio(name, mime)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                            val title = if (name.contains('.')) name.substringBeforeLast('.') else name
                            val size = if (c.isNull(3)) 0L else c.getLong(3)
                            val mod = if (c.isNull(4)) 0L else c.getLong(4)
                            fresh += Track(section = section, root = rootUri, dir = path, name = name, title = title, uri = uri, size = size, modified = mod)
                            direct[path] = (direct[path] ?: 0) + 1
                        }
                    }
                }
            } catch (_: Exception) { /* einzelner Ordner nicht lesbar → überspringen */ }
        }
        if (!ok) return false // Zugriff verloren: alten Stand behalten
        return applyFresh(rootUri, section, fresh, direct, allDirs)
    }

    /** Titel vom Computer (Minimal Library) als eigene Wurzel übernehmen. true = etwas geändert */
    suspend fun mirrorRoot(rootUri: String, fresh: List<Track>): Boolean = lock.withLock {
        val direct = HashMap<String, Int>()
        val dirs = LinkedHashSet<String>()
        for (t in fresh) {
            direct[t.dir] = (direct[t.dir] ?: 0) + 1
            var d = t.dir
            while (d.isNotEmpty()) { dirs += d; d = if (d.contains('/')) d.substringBeforeLast('/') else "" }
        }
        withContext(Dispatchers.IO) { applyFresh(rootUri, MUSIC, fresh, direct, dirs.toList()) }
    }

    /** Abgleich einer Wurzel mit frisch gelesenen Titeln (gemeinsam für Handy-Ordner und Computer) */
    private suspend fun applyFresh(rootUri: String, section: Int, fresh: List<Track>, direct: Map<String, Int>, allDirs: List<String>): Boolean {
        // Abgleich mit dem bisherigen Stand
        val old = dao.tracksOfRoot(rootUri).associateBy { it.uri }
        val freshUris = HashSet<String>(fresh.size)
        val insert = ArrayList<Track>()
        val update = ArrayList<Track>()
        for (f in fresh) {
            freshUris += f.uri
            val o = old[f.uri]
            if (o == null) { insert += f; continue }
            val same = o.size == f.size && o.modified == f.modified
            if (!same || o.dir != f.dir || o.name != f.name || o.section != f.section) {
                update += if (same) f.copy(id = o.id, duration = o.duration, tagTitle = o.tagTitle, tagArtist = o.tagArtist, tagged = o.tagged, cover = o.cover)
                else f.copy(id = o.id)
            }
        }
        val delete = old.values.filter { it.uri !in freshUris }.map { it.id }

        // Ordner-Zählung; leere Ordner fallen weg
        val total = HashMap<String, Int>()
        for ((p, n) in direct) {
            var cur = p
            while (cur.isNotEmpty()) {
                total[cur] = (total[cur] ?: 0) + n
                cur = if (cur.contains('/')) cur.substringBeforeLast('/') else ""
            }
        }
        val dirs = allDirs.filter { (total[it] ?: 0) > 0 }.map { p ->
            Dir(
                root = rootUri, path = p, section = section,
                parent = if (p.contains('/')) p.substringBeforeLast('/') else "",
                name = p.substringAfterLast('/'), files = direct[p] ?: 0, total = total[p] ?: 0,
            )
        }
        val oldDirs = dao.dirsOfRoot(rootUri).toSet()
        val dirsChanged = oldDirs != dirs.toSet()
        if (insert.isEmpty() && update.isEmpty() && delete.isEmpty() && !dirsChanged) return false
        dao.sync(rootUri, insert, update, delete, if (dirsChanged) dirs else null)
        return true
    }

    // ───────── Ansichten ─────────

    suspend fun entries(root: String, path: String): List<Entry> = withContext(Dispatchers.IO) {
        val d = dao.dirs(root, path).sortedWith(compareBy(Natural) { it.name })
            .map { Entry.Folder(it.root, it.path, it.name, it.files, it.total) }
        val f = dao.files(root, path).sortedWith(fileOrder).map { Entry.File(it) }
        d + f
    }

    /** Suchbegriff → FTS-Abfrage: jedes Wort als Präfix („beat“ findet „Beatles“) */
    private fun ftsQuery(q: String): String? {
        val words = q.trim().split(Regex("\\s+")).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"$it*\"" }
    }

    suspend fun search(section: Int, q: String, pc: Boolean = false): List<Entry> = withContext(Dispatchers.IO) {
        val fq = ftsQuery(q) ?: return@withContext emptyList()
        val dirs = dao.searchDirs(q.trim(), section).filter { Pc.isPcRoot(it.root) == pc }.sortedWith(compareBy(Natural) { it.name })
            .map { Entry.Folder(it.root, it.path, it.name, it.files, it.total) }
        val tracks = ((try { dao.search(fq, section) } catch (_: Exception) { emptyList() }) +
            (if ((pc || Prefs.get(app).tagTitles) && q.trim().length >= 2) dao.searchTags(q.trim(), section) else emptyList())).distinctBy { it.id }
            .filter { Pc.isPcRoot(it.root) == pc }
        dirs + tracks.sortedWith(compareBy(Natural) { label(it) }).map { Entry.File(it) }
    }

    /** Container für eine Datei in der Ordneransicht: Musik = der Ordner, Hörbuch = der Ordner mit Unterordnern */
    fun containerFor(section: Int, t: Track): String =
        if (section == MUSIC) Keys.dir(MUSIC, t.root, t.dir)
        else if (t.dir.isEmpty()) Keys.file(BOOKS, t.root, t.uri)
        else Keys.tree(BOOKS, t.root, t.dir)

    /** Was beim Antippen einer Datei geladen wird: immer die ganze Liste, in der sie steht */
    fun playKeyFor(section: Int, t: Track): String =
        if (section == BOOKS && t.dir.isEmpty()) Keys.group(BOOKS, t.root, t.dir) else containerFor(section, t)

    /** Container für „Ordner abspielen“ */
    fun containerForFolder(section: Int, root: String, path: String, files: Int): String =
        Keys.tree(section, root, path) // immer mit Unterordnern

    /** Anzeigename eines Containers */
    suspend fun nameOf(key: String, tracks: List<Track>): String {
        val k = Keys.parse(key) ?: return ""
        return when (k.kind) {
            'f', 'x' -> tracks.firstOrNull()?.title ?: ""
            'l' -> dao.playlist(k.rest.toLongOrNull() ?: -1)?.name ?: ""
            'q' -> "„${k.rest}“"
            else -> k.rest.substringAfterLast('/').ifEmpty {
                (Prefs.get(app).roots(k.section) + Pc.roots()).firstOrNull { it.uri == k.root }?.name ?: ""
            }
        }
    }

    /** Datei von außen („Öffnen mit“) – nicht in der Bibliothek */
    private fun external(section: Int, uri: String): Track {
        val name = try {
            app.contentResolver.query(Uri.parse(uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null } ?: Uri.parse(uri).lastPathSegment?.substringAfterLast('/') ?: "Audio"
        return Track(section = section, root = "", dir = "", name = name, title = name.substringBeforeLast('.'), uri = uri)
    }

    /** Alle Titel eines Containers in Abspielreihenfolge */
    suspend fun queue(key: String): List<Track> = queueAll(key).let { list ->
        // ohne Verbindung zum PC: nur offline gespeicherte Titel vom PC
        if (Pc.state.value.online) list else list.filter { !Pc.isPc(it.uri) || Pc.isOffline(it.uri) }
    }

    private suspend fun queueAll(key: String): List<Track> = withContext(Dispatchers.IO) {
        val k = Keys.parse(key) ?: return@withContext emptyList()
        when (k.kind) {
            'd' -> dao.files(k.root, k.rest).sortedWith(fileOrder)
            'r' -> dao.subtree(k.root, k.rest).sortedWith { a, b ->
                val d = comparePath(a.dir, b.dir)
                if (d != 0) d else fileOrder.compare(a, b)
            }
            'f' -> listOfNotNull(dao.byUri(k.rest))
            'x' -> listOf(dao.byUri(k.rest) ?: external(k.section, k.rest))
            'l' -> playlistTracks(k.rest.toLongOrNull() ?: -1).map { it.track }
            'q' -> search(k.section, k.rest, pc = k.root == "pc").filterIsInstance<Entry.File>().map { it.track }
            'g' -> dao.files(k.root, k.rest).sortedWith(compareBy(Natural) { it.name })
            else -> emptyList()
        }
    }

    // ───────── Playlisten ─────────

    /** Ändert sich, wenn ein Stern gesetzt/entfernt wird (Player, Mini-Player, Benachrichtigung, Auto) */
    val stars = MutableStateFlow(0)

    /** Die Playlist „Stern“ (nur Musik); wird beim ersten Stern angelegt, nach Löschen neu */
    suspend fun starPlaylist(create: Boolean): Long? = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(app)
        prefs.starId.takeIf { it > 0 && dao.playlist(it) != null }?.let { return@withContext it }
        if (!create) return@withContext null
        val id = dao.insertPlaylist(Playlist(name = app.getString(R.string.star_playlist), created = System.currentTimeMillis()))
        prefs.starId = id
        version.value++
        id
    }

    /** null = Datei nicht in der Bibliothek (kann keinen Stern bekommen) */
    suspend fun isStarred(uri: String): Boolean? = withContext(Dispatchers.IO) {
        val t = dao.byUri(uri) ?: return@withContext null
        if (t.section != MUSIC || Pc.isPc(t.uri)) return@withContext null
        val pl = starPlaylist(false) ?: return@withContext false
        dao.inPlaylist(pl, t.id) > 0
    }

    suspend fun toggleStar(uri: String): Boolean? {
        val r = withContext(Dispatchers.IO) {
            val t = dao.byUri(uri) ?: return@withContext null
            if (t.section != MUSIC || Pc.isPc(t.uri)) return@withContext null
            val pl = starPlaylist(true)!!
            if (dao.inPlaylist(pl, t.id) > 0) { dao.removeTrack(pl, t.id); false }
            else { dao.addToPlaylist(pl, listOf(t.id)); true }
        }
        stars.value++
        version.value++
        return r
    }

    /** Ordner-Cover: nur wenn alle Titel darin (inkl. Unterordner) dasselbe Cover haben */
    suspend fun folderCovers(entries: List<Entry>): Map<String, String> = withContext(Dispatchers.IO) {
        val folders = entries.filterIsInstance<Entry.Folder>()
        if (folders.isEmpty()) return@withContext emptyMap()
        val out = HashMap<String, String>()
        for ((root, fs) in folders.groupBy { it.root }) {
            val rows = dao.dirCovers(root)
            for (f in fs) {
                val sub = rows.filter { f.path.isEmpty() || it.dir == f.path || it.dir.startsWith(f.path + "/") }
                val all = sub.flatMap { listOf(it.minC, it.maxC) }.toSet()
                if (all.size == 1 && all.first().isNotEmpty()) out[f.id] = all.first()
            }
        }
        out
    }

    /** Schnelles Lesezeichen ohne Notiz an der aktuellen Stelle (Mini-Player, Benachrichtigung, Auto) */
    suspend fun quickBookmark(item: MediaItem, position: Long) {
        val ex = item.mediaMetadata.extras
        withContext(Dispatchers.IO) {
            dao.addBookmark(
                Bookmark(
                    uri = item.mediaId, container = ex?.getString("container") ?: "",
                    title = item.mediaMetadata.title?.toString() ?: "", position = position, note = "", created = System.currentTimeMillis(),
                ),
            )
        }
        stars.value++ // Anzeigen (Zähler im Player) aktualisieren
    }

    /** Gesamtdauer je Ordner (inkl. Unterordner) für eine Liste von Einträgen */
    suspend fun folderDurations(entries: List<Entry>): Map<String, Long> = withContext(Dispatchers.IO) {
        val folders = entries.filterIsInstance<Entry.Folder>()
        if (folders.isEmpty()) return@withContext emptyMap()
        val out = HashMap<String, Long>()
        for ((root, fs) in folders.groupBy { it.root }) {
            val sums = dao.dirDurations(root)
            for (f in fs) {
                out[f.id] = sums.sumOf { d ->
                    if (f.path.isEmpty() || d.dir == f.path || d.dir.startsWith(f.path + "/")) d.ms else 0L
                }
            }
        }
        out
    }

    suspend fun playlistTracks(id: Long): List<PlaylistTrack> = withContext(Dispatchers.IO) {
        val items = dao.playlistItems(id)
        val byId = items.map { it.trackId }.distinct().chunked(500).flatMap { dao.tracksById(it) }.associateBy { it.id }
        items.mapNotNull { i -> byId[i.trackId]?.let { PlaylistTrack(i.id, i.pos, it) } }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        dao.insertPlaylist(Playlist(name = name.trim(), created = System.currentTimeMillis()))
    }.also { version.value++ }

    suspend fun addToPlaylist(id: Long, trackIds: List<Long>) {
        withContext(Dispatchers.IO) { dao.addToPlaylist(id, trackIds) }
        version.value++
    }

    /** Wie viele der Titel stecken schon in jeder Playlist? (für die Häkchen) */
    suspend fun membership(ids: List<Long>): Map<Long, Int> = withContext(Dispatchers.IO) {
        dao.playlists().associate { p -> p.id to ids.distinct().chunked(500).sumOf { dao.presentIn(p.id, it).size } }
    }

    /** Häkchen setzen: fehlende Titel anhängen / Häkchen entfernen: Titel aus der Playlist nehmen */
    suspend fun setInPlaylist(id: Long, ids: List<Long>, inside: Boolean) {
        withContext(Dispatchers.IO) {
            val distinct = ids.distinct()
            if (inside) {
                val have = distinct.chunked(500).flatMap { dao.presentIn(id, it) }.toSet()
                dao.addToPlaylist(id, distinct.filter { it !in have })
            } else distinct.chunked(500).forEach { dao.removeTracks(id, it) }
        }
        if (id == Prefs.get(app).starId) stars.value++
        version.value++
        pushIfPc(id)
    }

    /** Playlist vom Computer geändert → an Minimal Library schicken */
    suspend fun pushIfPc(id: Long) {
        val uid = withContext(Dispatchers.IO) { dao.playlist(id)?.uid }
        if (Pc.isPcPlaylist(uid)) Pc.push(id)
    }

    /** Titel um eine Stelle verschieben (dir = −1 nach oben, +1 nach unten) */
    suspend fun movePlaylistItem(id: Long, itemId: Long, dir: Int) {
        withContext(Dispatchers.IO) {
            val items = dao.playlistItems(id)
            val i = items.indexOfFirst { it.id == itemId }
            val j = i + dir
            if (i >= 0 && j in items.indices) dao.swap(items[i], items[j])
        }
        version.value++
        pushIfPc(id)
    }

    /** Titel für „Zu Playlist hinzufügen“ eines Ordners (mit Unterordnern, Abspielreihenfolge) */
    suspend fun folderTrackIds(root: String, path: String): List<Long> = queue(Keys.tree(MUSIC, root, path)).map { it.id }

    /** Export: Status für die Oberfläche (null = nichts läuft) */
    data class Export(val done: Int, val total: Int, val finished: Boolean = false, val failed: Boolean = false)
    val export = MutableStateFlow<Export?>(null)

    /**
     * Kopiert die Dateien einer Playlist in den gewählten Ordner – plus eine .m3u8 mit der Reihenfolge.
     * Gleichnamige Dateien bekommen „ (2)“ angehängt. Originale bleiben unverändert.
     */
    suspend fun exportPlaylist(ctx: Context, id: Long, target: Uri) = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val tracks = playlistTracks(id).map { it.track }
        val name = dao.playlist(id)?.name ?: "Playlist"
        export.value = Export(0, tracks.size)
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(target, DocumentsContract.getTreeDocumentId(target))
            val existing = HashSet<String>()
            try {
                cr.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(target, DocumentsContract.getTreeDocumentId(target)),
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
                )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { existing += it.lowercase() } }
            } catch (_: Exception) { }
            fun unique(n: String): String {
                if (n.lowercase() !in existing) { existing += n.lowercase(); return n }
                val base = n.substringBeforeLast('.', n); val ext = n.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                var k = 2
                while ("$base ($k)$ext".lowercase() in existing) k++
                return "$base ($k)$ext".also { existing += it.lowercase() }
            }
            val names = ArrayList<String>()
            tracks.forEachIndexed { i, t ->
                val fileName = unique(t.name)
                val mime = cr.getType(Uri.parse(t.uri)) ?: "audio/*"
                val dest = DocumentsContract.createDocument(cr, parent, mime, fileName) ?: throw IllegalStateException("anlegen")
                cr.openInputStream(Uri.parse(t.uri))?.use { inp -> cr.openOutputStream(dest)?.use { out -> inp.copyTo(out, 256 * 1024) } }
                // Manche Anbieter benennen selbst um → tatsächlichen Namen für die .m3u8 merken
                val real = try {
                    cr.query(dest, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                } catch (_: Exception) { null } ?: fileName
                names += real
                export.value = Export(i + 1, tracks.size)
            }
            val m3u = DocumentsContract.createDocument(cr, parent, "audio/x-mpegurl", unique(name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".m3u8"))
            if (m3u != null) cr.openOutputStream(m3u)?.use { out ->
                out.write(("#EXTM3U\n" + names.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            }
            export.value = Export(tracks.size, tracks.size, finished = true)
        } catch (_: Throwable) {
            export.value = Export(export.value?.done ?: 0, tracks.size, finished = true, failed = true)
        }
    }

    /**
     * Mehrere Playlisten auf einen Stick (per Android-Speicherzugriff gewählt) – Aufbau wie in Minimal Library:
     * "numbered" = Ordner je Playlist, nummeriert · "flat" = Ordner je Playlist · "structured" = gemeinsamer Ordner „Musik“.
     * Titel vom PC werden übers WLAN geholt (oder aus der Offline-Kopie). Vorhandene gleich große Dateien bleiben.
     */
    suspend fun exportMany(ctx: Context, ids: List<Long>, layout: String, target: Uri) = withContext(Dispatchers.IO) {
        fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').take(120).ifEmpty { "_" }
        fun labelOf(t: Track) = if (t.tagTitle.isNotBlank()) (if (t.tagArtist.isNotBlank()) "${t.tagArtist} - ${t.tagTitle}" else t.tagTitle) else t.title
        fun extOf(t: Track) = t.name.substringAfterLast('.', "mp3")
        val lists = ids.mapNotNull { id -> dao.playlist(id)?.let { it to playlistTracks(id).map { pt -> pt.track } } }
        // Plan: (Titel, Zielpfad) + Playlist-Dateien
        val files = LinkedHashMap<String, Track>()
        val byUri = HashMap<String, String>()
        val m3us = ArrayList<Pair<String, String>>()
        val used = HashSet<String>()
        fun uniq(path: String): String {
            if (used.add(path.lowercase())) return path
            val base = path.substringBeforeLast('.'); val ext = path.substringAfterLast('.', "")
            var k = 2
            while (!used.add("$base ($k).$ext".lowercase())) k++
            return "$base ($k).$ext"
        }
        for ((p, tracks) in lists) {
            val pl = clean(p.name)
            val digits = maxOf(2, tracks.size.toString().length)
            val lines = StringBuilder("#EXTM3U\n")
            tracks.forEachIndexed { i, t ->
                val rel = when (layout) {
                    "structured" -> byUri.getOrPut(t.uri) {
                        uniq("Musik/" + (t.dir.split('/').filter { it.isNotEmpty() }.map(::clean) + clean(t.name)).joinToString("/")).also { files[it] = t }
                    }
                    "flat" -> uniq("$pl/${clean(labelOf(t))}.${extOf(t)}").also { files[it] = t }
                    else -> uniq("$pl/${String.format("%0${digits}d", i + 1)} - ${clean(labelOf(t))}.${extOf(t)}").also { files[it] = t }
                }
                val secs = if (t.duration > 0) t.duration / 1000 else -1
                lines.append("#EXTINF:").append(secs).append(',').append(labelOf(t)).append('\n')
                lines.append(if (layout == "structured") rel else rel.substringAfter('/')).append('\n')
            }
            m3us += (if (layout == "structured") "$pl.m3u8" else "$pl/$pl.m3u8") to lines.toString()
        }
        export.value = Export(0, files.size)
        var done = 0
        var failed = 0
        for ((rel, t) in files) {
            try {
                val have = Saf.sizeOf(ctx, target, rel)
                if (have == null || (t.size > 0 && have != t.size)) {
                    (Pc.open(ctx, t) ?: throw IllegalStateException("nicht lesbar")).use { Saf.saveFile(ctx, target, rel, it) }
                }
            } catch (_: Exception) { failed++ }
            done++
            export.value = Export(done, files.size)
        }
        for ((rel, text) in m3us) {
            try { Saf.saveFile(ctx, target, rel, text.toByteArray(Charsets.UTF_8).inputStream()) } catch (_: Exception) { failed++ }
        }
        export.value = Export(done, files.size, finished = true, failed = failed > 0)
    }

    /** Ordnerpfade wie am PC: Dateien eines Ordners vor seinen Unterordnern, sonst natürlich sortiert */
    private fun comparePath(a: String, b: String): Int {
        if (a == b) return 0
        val pa = if (a.isEmpty()) emptyList() else a.split('/')
        val pb = if (b.isEmpty()) emptyList() else b.split('/')
        for (i in 0 until minOf(pa.size, pb.size)) {
            val d = Natural.compare(pa[i], pb[i]); if (d != 0) return d
        }
        return pa.size - pb.size
    }

    /** Stand eines Hörbuchs: gemerkte Stelle + Prozent (wenn alle Dauern bekannt) */
    suspend fun bookState(key: String): BookState = withContext(Dispatchers.IO) {
        val p = dao.progress(Keys.progressOf(key))
        if (p == null) return@withContext BookState(null, null)
        if (p.finished) return@withContext BookState(p, 100)
        val tracks = queue(key)
        if (tracks.isEmpty() || tracks.any { it.duration <= 0 }) return@withContext BookState(p, null)
        val total = tracks.sumOf { it.duration }
        val i = tracks.indexOfFirst { it.uri == p.uri }.takeIf { it >= 0 } ?: return@withContext BookState(p, null)
        val done = tracks.take(i).sumOf { it.duration } + p.position
        BookState(p, ((done * 100) / total).toInt().coerceIn(0, 100))
    }

    /** Hörbuch als gehört / ungehört markieren */
    suspend fun markFinished(key: String, finished: Boolean) = withContext(Dispatchers.IO) {
        val id = Keys.progressOf(key)
        if (finished) {
            val tracks = queue(key)
            val p = dao.progress(id)
            dao.save(
                (p ?: Progress(id, tracks.firstOrNull()?.uri ?: "", 0L, 0, tracks.size, nameOf(key, tracks), 0L))
                    .copy(finished = true, updated = System.currentTimeMillis()),
            )
        } else dao.forget(id)
        version.value++
    }

    /** Reihenfolge der Dateien: nach Dateiname – bei „Titel aus Tags“ (Musik) nach dem angezeigten Namen, passend zur Buchstabenleiste */
    val fileOrder: Comparator<Track> = Comparator { a, b -> Natural.compare(sortKey(a), sortKey(b)) }
    private fun sortKey(t: Track): String = if (t.section == MUSIC && Prefs.get(app).tagTitles) label(t) else t.name

    /** Anzeigename eines Titels: Dateiname oder – wenn eingeschaltet (Musik) – „Interpret – Titel“ aus den Tags */
    fun label(t: Track): String {
        if (t.section != MUSIC || t.tagTitle.isBlank() || !Prefs.get(app).tagTitles) return t.title
        return if (t.tagArtist.isNotBlank()) "${t.tagArtist} – ${t.tagTitle}" else t.tagTitle
    }

    /** Anzahl Dateien und Spielzeit eines Ordners (inkl. Unterordner; path "" = ganzer gewählter Ordner) */
    suspend fun stats(root: String, path: String): Stats = withContext(Dispatchers.IO) { dao.stats(root, path) }

    fun item(t: Track, key0: String, section: Int, name0: String): MediaItem {
        // In einer Gruppe einzelner Hörbuch-Dateien ist jede Datei ihr eigenes Hörbuch
        val single = key0.startsWith("g|")
        val key = if (single) Keys.file(section, t.root, t.uri) else key0
        val name = if (single) t.title else name0
        // Titel vom Computer: gleich die echte Adresse (Offline-Kopie oder Stream)
        val uri = Pc.realUri(Uri.parse(t.uri))
        val folder = t.dir.substringAfterLast('/').ifEmpty { name }
        return MediaItem.Builder()
            .setMediaId(t.uri)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(label(t))
                    .setDisplayTitle(label(t))
                    .setArtist(folder)
                    .setAlbumTitle(name)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(Bundle().apply {
                        putString("container", key)
                        putInt("section", section)
                        putString("name", name)
                        putString("dir", t.dir)
                    })
                    .build(),
            )
            .build()
    }

    /** Was abgespielt werden soll – inkl. gespeicherter Stelle bei Hörbüchern */
    data class Plan(val items: List<MediaItem>, val index: Int, val position: Long, val section: Int)

    suspend fun plan(key: String, startUri: String? = null, fromStart: Boolean = false, startPos: Long? = null): Plan? {
        val tracks = queue(key)
        if (tracks.isEmpty()) return null
        val section = Keys.section(key)
        val name = nameOf(key, tracks)
        var index = startUri?.let { u -> tracks.indexOfFirst { it.uri == u } }?.takeIf { it >= 0 } ?: 0
        var pos = startPos ?: 0L
        if (section == BOOKS && !fromStart && startPos == null) {
            withContext(Dispatchers.IO) {
                if (startUri != null) {
                    pos = dao.progress(Keys.progressOfFile(startUri))?.position ?: 0L
                } else if (key.startsWith("g|")) {
                    // Liste einzelner Hörbuch-Dateien: bei der zuletzt gehörten weitermachen
                    val last = tracks.map { it.uri }.chunked(500).flatMap { c -> dao.progressFor(c.map { Keys.progressOfFile(it) }) }.maxByOrNull { it.updated }
                    if (last != null) { val i = tracks.indexOfFirst { it.uri == last.uri }; if (i >= 0) { index = i; pos = last.position } }
                } else {
                    dao.progress(Keys.progressOf(key))?.takeIf { !it.finished }?.let { p ->
                        val i = tracks.indexOfFirst { it.uri == p.uri }
                        if (i >= 0) { index = i; pos = p.position }
                    }
                }
            }
        }
        Waves.prepareList(app, tracks.map { it.uri }) // nur im Modus „aktuelle Titelliste“
        return Plan(tracks.map { item(it, key, section, name) }, index, pos, section)
    }
}
