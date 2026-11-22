package de.philipdecker.playerconnected

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.InputStream

/** Dateien in einem per Android-Speicherzugriff gewählten Ordner (z. B. USB-Stick) schreiben */
object Saf {
    private val lock = Any()

    fun deviceName(ctx: Context): String =
        (try { Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) } catch (_: Exception) { null })
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val cols = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
    )

    private class Child(val id: String, val name: String, val dir: Boolean, val size: Long)

    private fun children(ctx: Context, tree: Uri, docId: String): List<Child> {
        val out = ArrayList<Child>()
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += Child(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return out
    }

    private fun doc(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun ensureDir(ctx: Context, tree: Uri, parts: List<String>): String {
        var cur = DocumentsContract.getTreeDocumentId(tree)
        for (p in parts) {
            cur = children(ctx, tree, cur).firstOrNull { it.dir && it.name.equals(p, true) }?.id ?: run {
                val u = DocumentsContract.createDocument(ctx.contentResolver, doc(tree, cur), DocumentsContract.Document.MIME_TYPE_DIR, p)
                    ?: throw IllegalStateException("Ordner „$p“ lässt sich nicht anlegen")
                DocumentsContract.getDocumentId(u)
            }
        }
        return cur
    }

    data class Entry(val rel: String, val name: String, val uri: String, val dir: Boolean, val size: Long, val mime: String?)

    /** Alles unterhalb des Ordners (rekursiv, ohne versteckte) */
    fun list(ctx: Context, tree: Uri): List<Entry> {
        val out = ArrayList<Entry>()
        val cols2 = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(DocumentsContract.getTreeDocumentId(tree) to "")
        while (stack.isNotEmpty()) {
            val (id, rel) = stack.removeLast()
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols2, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val cid = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    val r = if (rel.isEmpty()) name else "$rel/$name"
                    val dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Entry(r, name, doc(tree, cid).toString(), dir, if (c.isNull(3)) 0L else c.getLong(3), mime)
                    if (dir) stack.add(cid to r)
                }
            }
        }
        return out
    }

    private fun findId(ctx: Context, tree: Uri, rel: String): Pair<String, String>? {
        val parts = rel.split('/').filter { it.isNotEmpty() }
        var cur = DocumentsContract.getTreeDocumentId(tree)
        var parent = cur
        for (p in parts) {
            parent = cur
            cur = children(ctx, tree, cur).firstOrNull { it.name.equals(p, true) }?.id ?: return null
        }
        return parent to cur
    }

    /** Text-Datei lesen (null = gibt es nicht) */
    fun read(ctx: Context, tree: Uri, rel: String): String? = try {
        findId(ctx, tree, rel)?.let { (_, id) -> ctx.contentResolver.openInputStream(doc(tree, id))?.use { String(it.readBytes()) } }
    } catch (_: Exception) { null }

    fun delete(ctx: Context, tree: Uri, rel: String) {
        try { findId(ctx, tree, rel)?.let { (_, id) -> DocumentsContract.deleteDocument(ctx.contentResolver, doc(tree, id)) } } catch (_: Exception) { }
    }

    /** Datei innerhalb des Baums verschieben/umbenennen → neue Dokument-Adresse */
    fun move(ctx: Context, tree: Uri, from: String, to: String): String {
        val cr = ctx.contentResolver
        val (parentId, id) = findId(ctx, tree, from) ?: throw IllegalStateException("fehlt: $from")
        val parts = to.split('/').filter { it.isNotEmpty() }
        val name = parts.last()
        synchronized(lock) {
            val target = ensureDir(ctx, tree, parts.dropLast(1))
            // Ziel belegt (z. B. alte Kopie) → weg
            children(ctx, tree, target).filter { !it.dir && it.name.equals(name, true) && it.id != id }
                .forEach { try { DocumentsContract.deleteDocument(cr, doc(tree, it.id)) } catch (_: Exception) { } }
            var u = doc(tree, id)
            if (target != parentId) {
                u = try { DocumentsContract.moveDocument(cr, u, doc(tree, parentId), doc(tree, target)) } catch (_: Exception) { null }
                    ?: run {
                        // Anbieter kann nicht verschieben → kopieren und löschen
                        val tmp = DocumentsContract.createDocument(cr, doc(tree, target), "application/octet-stream", "$name.part") ?: throw IllegalStateException("anlegen")
                        cr.openInputStream(doc(tree, id))!!.use { i -> cr.openOutputStream(tmp, "w")!!.use { o -> i.copyTo(o, 256 * 1024) } }
                        DocumentsContract.deleteDocument(cr, doc(tree, id))
                        tmp
                    }
            }
            val curName = cr.query(u, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            if (curName != name) u = DocumentsContract.renameDocument(cr, u, name) ?: u
            return u.toString()
        }
    }

    /** Leere Unterordner entfernen (nie den Hauptordner selbst) */
    fun removeEmptyDirs(ctx: Context, tree: Uri) {
        fun walk(id: String): Boolean {
            var empty = true
            for (k in children(ctx, tree, id)) {
                if (k.dir && walk(k.id)) {
                    try { DocumentsContract.deleteDocument(ctx.contentResolver, doc(tree, k.id)) } catch (_: Exception) { empty = false }
                } else empty = false
            }
            return empty
        }
        synchronized(lock) { try { walk(DocumentsContract.getTreeDocumentId(tree)) } catch (_: Exception) { } }
    }

    /** Größe einer vorhandenen Datei (null = gibt es nicht) */
    fun sizeOf(ctx: Context, tree: Uri, rel: String): Long? {
        return try {
            val parts = rel.split('/').filter { it.isNotEmpty() }
            var cur = DocumentsContract.getTreeDocumentId(tree)
            for (p in parts.dropLast(1)) cur = children(ctx, tree, cur).firstOrNull { it.dir && it.name.equals(p, true) }?.id ?: return null
            children(ctx, tree, cur).firstOrNull { !it.dir && it.name.equals(parts.last(), true) }?.size
        } catch (_: Exception) { null }
    }

    /** Datei schreiben (erst „.part“, dann umbenennen – nie halbe Dateien) → Dokument-Adresse */
    fun saveFile(ctx: Context, tree: Uri, rel: String, input: InputStream): String {
        val cr = ctx.contentResolver
        val parts = rel.split('/').filter { it.isNotEmpty() }
        val name = parts.last()
        val tmp: Uri
        synchronized(lock) {
            val parent = ensureDir(ctx, tree, parts.dropLast(1))
            children(ctx, tree, parent).filter { !it.dir && (it.name.equals(name, true) || it.name.equals("$name.part", true)) }
                .forEach { try { DocumentsContract.deleteDocument(cr, doc(tree, it.id)) } catch (_: Exception) { } }
            tmp = DocumentsContract.createDocument(cr, doc(tree, parent), "application/octet-stream", "$name.part")
                ?: throw IllegalStateException("„$name“ lässt sich nicht anlegen")
        }
        try {
            (cr.openOutputStream(tmp, "w") ?: throw IllegalStateException("kein Schreibzugriff")).use { o -> input.copyTo(o, 256 * 1024) }
            return (DocumentsContract.renameDocument(cr, tmp, name) ?: throw IllegalStateException("Umbenennen fehlgeschlagen")).toString()
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(cr, tmp) } catch (_: Exception) { }
            throw e
        }
    }
}
