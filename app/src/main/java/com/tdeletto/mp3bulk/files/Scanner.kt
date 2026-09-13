package com.tdeletto.mp3bulk.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File

data class Mp3File(
    /** Tree-based document URI (picked folder) or file:// URI (All files access). */
    val uri: Uri,
    val name: String,
    val size: Long,
    /** Folder path relative to the chosen root, for display ("" for root). */
    val relativeDir: String,
    /** Parent directory, so output can be created beside the original. */
    val parentDir: Uri,
)

const val SHRUNK_SUFFIX = " - SHRUNK"

private val MP3_MIME = setOf("audio/mpeg", "audio/mp3", "audio/x-mp3", "audio/mpeg3", "audio/x-mpeg")

private fun isMp3(name: String, mime: String?) =
    name.endsWith(".mp3", ignoreCase = true) || (mime != null && mime.lowercase() in MP3_MIME)

/** Our own outputs and hidden/trashed files are never picked up again. */
private fun isCandidate(name: String) =
    !name.startsWith(".") && !name.substringBeforeLast('.').endsWith(SHRUNK_SUFFIX, ignoreCase = true)

private val PROJECTION = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)

private fun MutableList<Mp3File>.sortForDisplay() = sortWith(compareBy({ it.relativeDir.lowercase() }, { it.name.lowercase() }))

/** Directory listing straight from DocumentsContract: much faster than DocumentFile.listFiles(). */
fun scanTree(
    resolver: ContentResolver,
    treeUri: Uri,
    recursive: Boolean,
    isCancelled: () -> Boolean = { false },
    onFound: (Int) -> Unit = {},
): List<Mp3File> {
    val result = ArrayList<Mp3File>()
    val queue = ArrayDeque<Pair<String, String>>() // documentId to relative path
    queue.add(DocumentsContract.getTreeDocumentId(treeUri) to "")

    while (queue.isNotEmpty() && !isCancelled()) {
        val (dirId, dirPath) = queue.removeFirst()
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirId)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        resolver.query(children, PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == Document.MIME_TYPE_DIR) {
                    if (recursive && !name.startsWith(".")) queue.add(id to if (dirPath.isEmpty()) name else "$dirPath/$name")
                } else if (isMp3(name, mime) && isCandidate(name)) {
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    result += Mp3File(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, size, dirPath, dirUri)
                }
            }
        }
        onFound(result.size)
    }
    result.sortForDisplay()
    return result
}

/** Same as [scanTree] but by path; needs All files access. */
fun scanDirectory(
    root: File,
    recursive: Boolean,
    isCancelled: () -> Boolean = { false },
    onFound: (Int) -> Unit = {},
): List<Mp3File> {
    val result = ArrayList<Mp3File>()
    val queue = ArrayDeque<Pair<File, String>>()
    queue.add(root to "")
    while (queue.isNotEmpty() && !isCancelled()) {
        val (dir, dirPath) = queue.removeFirst()
        val children = dir.listFiles() ?: continue
        for (f in children) {
            val name = f.name
            if (f.isDirectory) {
                if (recursive && !name.startsWith(".")) queue.add(f to if (dirPath.isEmpty()) name else "$dirPath/$name")
            } else if (isMp3(name, null) && isCandidate(name)) {
                result += Mp3File(Uri.fromFile(f), name, f.length(), dirPath, Uri.fromFile(dir))
            }
        }
        onFound(result.size)
    }
    result.sortForDisplay()
    return result
}

fun fileEntry(f: File): Mp3File = Mp3File(Uri.fromFile(f), f.name, f.length(), "", Uri.fromFile(f.absoluteFile.parentFile))

/** Finds a file by name (and size, when known) directly inside the root of [treeUri]. */
fun findInTree(resolver: ContentResolver, treeUri: Uri, name: String, size: Long): Mp3File? {
    val rootId = DocumentsContract.getTreeDocumentId(treeUri)
    val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId), PROJECTION, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val fileSize = if (c.isNull(3)) 0L else c.getLong(3)
            if (c.getString(1) == name && (size <= 0 || fileSize == size)) {
                return Mp3File(DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)), name, fileSize, "", dirUri)
            }
        }
    }
    return null
}

/** Name and size of a file handed to us by the system picker or another app. */
fun openableInfo(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio.mp3"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return name to size
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"
private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

/** The folder containing a picked file, used to open the folder picker in the right place. */
fun parentFolderHint(uri: Uri): Uri? {
    if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
    val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
    val parent = if ('/' in id) id.substringBeforeLast('/') else id.substringBefore(':') + ":"
    return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, parent)
}

/**
 * Folders Android's picker refuses to grant (Android 11+): the top of each storage volume,
 * Download itself, and Android/data and Android/obb. Subfolders of Download are fine.
 */
fun isSafBlockedPath(relativePath: String): Boolean {
    val p = relativePath.trim('/')
    return p.isEmpty() ||
        p.equals("Download", ignoreCase = true) ||
        p.equals("Android/data", ignoreCase = true) || p.startsWith("Android/data/", ignoreCase = true) ||
        p.equals("Android/obb", ignoreCase = true) || p.startsWith("Android/obb/", ignoreCase = true)
}

fun isGrantableFolder(folderDoc: Uri): Boolean {
    val id = runCatching { DocumentsContract.getDocumentId(folderDoc) }.getOrNull() ?: return false
    return !isSafBlockedPath(id.substringAfter(':', ""))
}

/** The real file behind a picked document. Only readable with All files access. */
fun resolveFile(context: Context, uri: Uri): File? {
    val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
    if (id != null) {
        when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> {
                val volume = id.substringBefore(':')
                val root = if (volume.equals("primary", ignoreCase = true)) Environment.getExternalStorageDirectory() else File("/storage/$volume")
                return File(root, id.substringAfter(':', "")).takeIf { it.isFile }
            }
            DOWNLOADS_AUTHORITY -> if (id.startsWith("raw:")) return File(id.removePrefix("raw:")).takeIf { it.isFile }
        }
    }
    val media: Uri? = when {
        uri.authority == MediaStore.AUTHORITY -> uri
        id != null && uri.authority == DOWNLOADS_AUTHORITY && id.startsWith("msf:") ->
            id.removePrefix("msf:").toLongOrNull()?.let { MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, it) }
        id != null && uri.authority == MEDIA_DOCUMENTS_AUTHORITY ->
            id.substringAfter(':').toLongOrNull()?.let { MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL, it) }
        else -> null
    } ?: runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
    val path = media?.let { m ->
        runCatching {
            context.contentResolver.query(m, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }
    return path?.let(::File)?.takeIf { it.isFile }
}

/** Human-readable name for a tree URI, e.g. "Music/Podcasts". */
fun treeDisplayName(treeUri: Uri): String {
    val id = DocumentsContract.getTreeDocumentId(treeUri)
    val path = id.substringAfter(':', id)
    return path.ifEmpty { if (id.startsWith("primary")) "Internal storage" else id.substringBefore(':') }
}

fun formatBytes(bytes: Long): String {
    val abs = kotlin.math.abs(bytes)
    return when {
        abs >= 1L shl 30 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        abs >= 1L shl 20 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        abs >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
