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
