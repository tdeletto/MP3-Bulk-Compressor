package com.tdeletto.mp3bulk.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * File operations. content:// documents go through the Storage Access Framework (folders the user picked);
 * file:// paths are used only with All files access, for places the picker can't grant (top of Download).
 */
object Storage {
    const val MIME = "audio/mpeg"

    private fun Uri.isFile() = scheme == ContentResolver.SCHEME_FILE
    private fun Uri.file() = File(path!!)

    /** Creates an empty file beside the original, adding " (1)" if the name is taken. */
    fun createSibling(resolver: ContentResolver, parentDir: Uri, displayName: String): Uri {
        if (parentDir.isFile()) {
            val f = uniqueChild(parentDir.file(), displayName)
            check(f.createNewFile()) { "Couldn't create ${f.name}" }
            return Uri.fromFile(f)
        }
        return DocumentsContract.createDocument(resolver, parentDir, MIME, displayName) ?: error("Couldn't create $displayName")
    }

    /** Copies [src] into [dest], syncs it to disk, then reads it back to confirm every byte landed. */
    fun writeVerified(resolver: ContentResolver, dest: Uri, src: File) {
        val expected = CRC32()
        val buf = ByteArray(1 shl 16)
        val pfd = resolver.openFileDescriptor(dest, "wt") ?: error("Can't write output file")
        pfd.use {
            FileOutputStream(it.fileDescriptor).use { out ->
                src.inputStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        expected.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
                out.flush()
                out.fd.sync()
            }
        }

        val actual = CRC32()
        var length = 0L
        val input = resolver.openInputStream(dest) ?: error("Can't read back output file")
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                actual.update(buf, 0, n)
                length += n
            }
        }
        check(length == src.length() && actual.value == expected.value) { "Saved file didn't match what was encoded" }
    }

    fun exists(resolver: ContentResolver, doc: Uri): Boolean = if (doc.isFile()) doc.file().exists() else runCatching {
        resolver.query(doc, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    fun displayName(resolver: ContentResolver, doc: Uri): String? = if (doc.isFile()) doc.file().name else runCatching {
        resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun delete(resolver: ContentResolver, doc: Uri) {
        if (doc.isFile()) doc.file().delete() else runCatching { DocumentsContract.deleteDocument(resolver, doc) }
    }

    /** Renames without ever overwriting: returns null if [name] is already taken. */
    fun rename(resolver: ContentResolver, doc: Uri, name: String): Uri? {
        if (doc.isFile()) {
            val src = doc.file()
            val dst = File(src.parentFile, name)
            return if (!dst.exists() && src.renameTo(dst)) Uri.fromFile(dst) else null
        }
        return runCatching { DocumentsContract.renameDocument(resolver, doc, name) }.getOrNull()
    }

    /** MediaStore item for a file, needed to use the system Trash. Null if it isn't (and can't be) indexed. */
    fun mediaUri(context: Context, doc: Uri): Uri? =
        if (doc.isFile()) mediaUriForPath(context, doc.file())
        else runCatching { MediaStore.getMediaUri(context, doc) }.getOrNull()

    private fun mediaUriForPath(context: Context, file: File): Uri? {
        val path = file.absolutePath
        fun lookup(): Uri? = runCatching {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME, MediaStore.Files.FileColumns.MEDIA_TYPE),
                "${MediaStore.MediaColumns.DATA}=?", arrayOf(path), null,
            )?.use { c ->
                when {
                    !c.moveToFirst() -> null
                    c.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.getContentUri(c.getString(1), c.getLong(0))
                    else -> MediaStore.Files.getContentUri(c.getString(1), c.getLong(0))
                }
            }
        }.getOrNull()
        return lookup() ?: run { scanBlocking(context, path); lookup() }
    }

    /** Lets the media library (and Files by Google) see files we created or renamed by path. */
    fun notifyMediaChanged(context: Context, vararg docs: Uri) {
        val paths = docs.filter { it.isFile() }.mapNotNull { it.path }.toTypedArray()
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(context, paths, null, null)
    }

    private fun scanBlocking(context: Context, path: String) {
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, _ -> latch.countDown() }
        latch.await(10, TimeUnit.SECONDS)
    }

    /** Moves an item to the system Trash (shown in Files by Google) when the OS allows it without asking. */
    fun trashDirect(resolver: ContentResolver, media: Uri): Boolean = try {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
        resolver.update(media, values, null, null) > 0
    } catch (e: SecurityException) {
        false
    } catch (e: UnsupportedOperationException) {
        false
    }

    /** System dialog asking the user to move these items to Trash. Items stay there 30 days. */
    fun trashRequest(resolver: ContentResolver, media: List<Uri>): IntentSender =
        MediaStore.createTrashRequest(resolver, media, true).intentSender
}

/** [name] in [dir], or "base (1).ext", "base (2).ext"… if taken. */
internal fun uniqueChild(dir: File, name: String): File {
    val base = name.substringBeforeLast('.', name)
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
    var f = File(dir, name)
    var n = 1
    while (f.exists()) f = File(dir, "$base (${n++})$ext")
    return f
}
