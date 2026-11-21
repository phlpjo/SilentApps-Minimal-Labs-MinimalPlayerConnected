package de.philipdecker.playerconnected

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ordnerbaum für Android Auto (und andere Mediensteuerungen):
 *   /            → Musik, Hörbücher
 *   s|bereich    → (Hörbücher: Weiterhören) + gewählte Ordner
 *   b|bereich|root|pfad → „Abspielen“, Unterordner, Dateien
 *   p|container␟uri     → abspielbar (uri leer = Container von vorn bzw. gemerkte Stelle)
 */
object Browse {
    const val ROOT = "/"
    private const val SEP = '\u001F'

    fun playId(key: String, uri: String? = null) = "p|$key$SEP${uri ?: ""}"

    /** p|… → (Container, Start-Datei) */
    fun parsePlay(id: String): Pair<String, String?>? {
        if (!id.startsWith("p|")) return null
        val rest = id.removePrefix("p|")
        val key = rest.substringBefore(SEP)
        val uri = rest.substringAfter(SEP, "").ifEmpty { null }
        return key to uri
    }

    private fun folder(id: String, title: String, subtitle: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
                .setIsBrowsable(true).setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build(),
        ).build()

    private fun playable(id: String, title: String, subtitle: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtist(subtitle)
                .setIsBrowsable(false).setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build(),
        ).build()

    fun root(ctx: Context) = folder(ROOT, ctx.getString(R.string.app_name))

    suspend fun children(ctx: Context, parent: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(ctx)
        when {
            parent == ROOT -> listOf(
                folder("s|$MUSIC", ctx.getString(R.string.music)),
                folder("s|$BOOKS", ctx.getString(R.string.books)),
            )
            parent.startsWith("s|") -> {
                val s = parent.removePrefix("s|").toIntOrNull() ?: MUSIC
                val out = ArrayList<MediaItem>()
                if (s == BOOKS) {
                    Library.dao.recent(5).forEach { p ->
                        val key = p.id.removePrefix("c|")
                        out += playable(playId(key), p.title, ctx.getString(R.string.continue_listening) + " · " + clock(p.position))
                    }
                }
                if (s == MUSIC) out += folder("pl|", ctx.getString(R.string.playlists))
                val roots = prefs.roots(s)
                if (roots.size == 1) out += folderChildren(ctx, s, roots[0].uri, "")
                else roots.forEach { r -> out += folder("b|$s|${r.uri}|", r.name) }
                out
            }
            parent == "pl|" -> {
                val star = Library.starPlaylist(false)
                Library.dao.playlists().sortedBy { if (it.id == star) 0 else 1 }.filter { it.count > 0 }.map { p ->
                    playable(playId(Keys.playlist(p.id)), (if (p.id == star) "★  " else "") + p.name,
                        ctx.resources.getQuantityString(R.plurals.tracks_count, p.count, p.count))
                }
            }
            parent.startsWith("b|") -> {
                val p = parent.split('|', limit = 4)
                if (p.size < 4) emptyList() else folderChildren(ctx, p[1].toIntOrNull() ?: MUSIC, p[2], p[3])
            }
            else -> emptyList()
        }
    }

    private suspend fun folderChildren(ctx: Context, section: Int, root: String, path: String): List<MediaItem> {
        val entries = Library.entries(root, path)
        val out = ArrayList<MediaItem>()
        val files = entries.count { it is Entry.File }
        if (entries.isNotEmpty() && path.isNotEmpty()) {
            val key = Library.containerForFolder(section, root, path, files)
            val label = if (section == BOOKS && Library.dao.progress(Keys.progressOf(key))?.finished == false)
                ctx.getString(R.string.resume) else ctx.getString(R.string.play)
            out += playable(playId(key), "▶  $label", path.substringAfterLast('/'))
            if (section == MUSIC) out += playable(playId("s" + Keys.tree(MUSIC, root, path)), "⤨  " + ctx.getString(R.string.shuffle), path.substringAfterLast('/'))
        }
        for (e in entries) when (e) {
            is Entry.Folder -> out += folder("b|$section|${e.root}|${e.path}", e.name, ctx.resources.getQuantityString(R.plurals.files_count, e.total, e.total))
            is Entry.File -> out += playable(playId(Library.playKeyFor(section, e.track), e.track.uri), Library.label(e.track), e.track.dir.substringAfterLast('/').ifEmpty { null })
        }
        return out
    }

    /** Suche (auch Sprachbefehl „Spiele …“): Musik und Hörbücher */
    suspend fun search(ctx: Context, q: String): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        for (s in listOf(MUSIC, BOOKS)) for (e in Library.search(s, q)) when (e) {
            is Entry.Folder -> out += folder("b|$s|${e.root}|${e.path}", e.name, ctx.getString(if (s == BOOKS) R.string.books else R.string.music))
            is Entry.File -> out += playable(playId(Library.playKeyFor(s, e.track), e.track.uri), Library.label(e.track), e.track.dir.substringAfterLast('/').ifEmpty { null })
        }
        return out
    }
}
