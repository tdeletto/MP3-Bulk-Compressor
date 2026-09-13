package com.tdeletto.mp3bulk

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tdeletto.mp3bulk.encoder.ChannelOut
import com.tdeletto.mp3bulk.encoder.EncodeMode
import com.tdeletto.mp3bulk.encoder.EncodeSettings
import com.tdeletto.mp3bulk.encoder.KEEP
import com.tdeletto.mp3bulk.files.Mp3File
import com.tdeletto.mp3bulk.files.fileEntry
import com.tdeletto.mp3bulk.files.findInTree
import com.tdeletto.mp3bulk.files.isGrantableFolder
import com.tdeletto.mp3bulk.files.openableInfo
import com.tdeletto.mp3bulk.files.parentFolderHint
import com.tdeletto.mp3bulk.files.resolveFile
import com.tdeletto.mp3bulk.files.scanDirectory
import com.tdeletto.mp3bulk.files.scanTree
import com.tdeletto.mp3bulk.files.treeDisplayName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Source {
    val name: String
    data class Folder(val uri: Uri, override val name: String) : Source
    /** A folder reached by path, for places the picker can't grant (e.g. Download itself). Needs All files access. */
    data class PathFolder(val dir: File, override val name: String) : Source
    data class Single(override val name: String) : Source
}

/** A picked file whose folder we still need access to, so output can be saved beside it. */
data class FolderNeeded(val name: String, val size: Long, val hint: Uri?)

/** Why All files access is being asked for, and what to retry once it's on. */
data class AllFilesNeeded(val message: String, val retryFile: Uri? = null, val downloadFolder: Boolean = false)

data class UiState(
    val source: Source? = null,
    val includeSubfolders: Boolean = true,
    val files: List<Mp3File> = emptyList(),
    val scanning: Boolean = false,
    val scanCount: Int = 0,
    val scanError: String? = null,
    val settings: EncodeSettings = EncodeSettings(),
    val replaceOriginals: Boolean = false,
    val folderNeeded: FolderNeeded? = null,
    val allFilesNeeded: AllFilesNeeded? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val resolver get() = getApplication<Application>().contentResolver
    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    val run: StateFlow<RunState?> = BatchRunner.state

    private var scanJob: Job? = null

    private fun hasAllFilesAccess() = Environment.isExternalStorageManager()

    private fun loadState(): UiState {
        val settings = EncodeSettings(
            mode = prefs.getString("mode", null)?.let { n -> EncodeMode.entries.firstOrNull { it.name == n } },
            kbps = prefs.getInt("kbps", KEEP),
            sampleRate = prefs.getInt("rate", KEEP),
            highPass = prefs.getBoolean("highPass", false),
            lowPass = prefs.getBoolean("lowPass", false),
            channels = prefs.getString("channels", null)?.let { n -> ChannelOut.entries.firstOrNull { it.name == n } },
        )
        return UiState(
            includeSubfolders = prefs.getBoolean("subfolders", true),
            replaceOriginals = prefs.getBoolean("replace", false),
            settings = settings,
        )
    }

    fun updateSettings(s: EncodeSettings) {
        _state.update { it.copy(settings = s) }
        prefs.edit {
            putString("mode", s.mode?.name)
            putInt("kbps", s.kbps)
            putInt("rate", s.sampleRate)
            putBoolean("highPass", s.highPass)
            putBoolean("lowPass", s.lowPass)
            putString("channels", s.channels?.name)
        }
    }

    fun setReplaceOriginals(v: Boolean) {
        _state.update { it.copy(replaceOriginals = v) }
        prefs.edit { putBoolean("replace", v) }
    }

    fun setIncludeSubfolders(v: Boolean) {
        _state.update { it.copy(includeSubfolders = v) }
        prefs.edit { putBoolean("subfolders", v) }
        when (val s = state.value.source) {
            is Source.Folder -> rescan(s)
            is Source.PathFolder -> rescanPath(s)
            else -> Unit
        }
    }

    private fun persist(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun onFolderPicked(uri: Uri) {
        persist(uri)
        rescan(Source.Folder(uri, treeDisplayName(uri)))
    }

    private sealed interface Pick {
        data class Ready(val file: Mp3File) : Pick
        data class NeedFolder(val needed: FolderNeeded) : Pick
        data class NeedAllFiles(val name: String) : Pick
        data object NotFound : Pick
    }

    fun onFilePicked(uri: Uri) {
        scanJob?.cancel()
        _state.update { it.copy(scanError = null) }
        scanJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { resolvePick(uri) } }
            result.onSuccess { pick ->
                when (pick) {
                    is Pick.Ready -> setSingle(pick.file)
                    is Pick.NeedFolder -> _state.update { it.copy(folderNeeded = pick.needed) }
                    is Pick.NeedAllFiles -> _state.update {
                        it.copy(
                            allFilesNeeded = AllFilesNeeded(
                                "Android's folder picker can't give access to the top of Download or of internal storage, " +
                                    "so “${pick.name}” can't be compressed where it is yet.\n\n" +
                                    "Turn on All files access for MP3 Compressor on the next screen, then come back.",
                                retryFile = uri,
                            ),
                        )
                    }
                    Pick.NotFound -> _state.update { it.copy(scanError = "Couldn't find that file on this device's storage") }
                }
            }.onFailure {
                _state.update { it.copy(scanError = "Couldn't open that file") }
            }
        }
    }

    /** Folder the user already granted → that; All files access on → by path; otherwise ask for the least access that works. */
    private fun resolvePick(uri: Uri): Pick {
        val (name, size) = openableInfo(resolver, uri)
        val hint = parentFolderHint(uri)
        existingGrantFor(hint)?.let { tree -> findInTree(resolver, tree, name, size)?.let { return Pick.Ready(it) } }
        if (hasAllFilesAccess()) {
            val file = resolveFile(getApplication(), uri) ?: return Pick.NotFound
            return Pick.Ready(fileEntry(file))
        }
        return if (hint != null && isGrantableFolder(hint)) Pick.NeedFolder(FolderNeeded(name, size, hint)) else Pick.NeedAllFiles(name)
    }

    /** A folder the user already granted that is exactly the file's parent. */
    private fun existingGrantFor(hint: Uri?): Uri? {
        val parentId = hint?.let { runCatching { DocumentsContract.getDocumentId(it) }.getOrNull() } ?: return null
        return resolver.persistedUriPermissions.firstOrNull { p ->
            p.isWritePermission && DocumentsContract.isTreeUri(p.uri) &&
                runCatching { DocumentsContract.getTreeDocumentId(p.uri) == parentId }.getOrDefault(false)
        }?.uri
    }

    fun onFolderGrantedForFile(tree: Uri) {
        val needed = state.value.folderNeeded ?: return
        persist(tree)
        _state.update { it.copy(folderNeeded = null) }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { findInTree(resolver, tree, needed.name, needed.size) }.getOrNull() }
            if (file != null) setSingle(file)
            else _state.update { it.copy(scanError = "“${needed.name}” isn't in that folder. Choose the folder that contains it.") }
        }
    }

    fun dismissFolderNeeded() {
        _state.update { it.copy(folderNeeded = null) }
    }

    /** Download itself can't be picked as a folder; with All files access it's scanned by path. */
    fun useDownloadFolder() {
        if (!hasAllFilesAccess()) {
            _state.update {
                it.copy(
                    allFilesNeeded = AllFilesNeeded(
                        "Android's folder picker can't give access to the Download folder itself.\n\n" +
                            "To compress the MP3s saved there, turn on All files access for MP3 Compressor on the next screen, then come back.",
                        downloadFolder = true,
                    ),
                )
            }
            return
        }
        rescanPath(Source.PathFolder(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Download"))
    }

    /** Called when the user returns from the All files access settings screen. */
    fun onAllFilesResult() {
        val need = state.value.allFilesNeeded ?: return
        _state.update { it.copy(allFilesNeeded = null) }
        if (!hasAllFilesAccess()) {
            _state.update { it.copy(scanError = "All files access is still off, so that location can't be used.") }
            return
        }
        when {
            need.downloadFolder -> useDownloadFolder()
            need.retryFile != null -> onFilePicked(need.retryFile)
        }
    }

    fun dismissAllFilesNeeded() {
        _state.update { it.copy(allFilesNeeded = null) }
    }

    private fun setSingle(file: Mp3File) {
        _state.update { it.copy(source = Source.Single(file.name), files = listOf(file), scanning = false, scanError = null) }
    }

    fun rescan() {
        when (val s = state.value.source) {
            is Source.Folder -> rescan(s)
            is Source.PathFolder -> rescanPath(s)
            is Source.Single -> {
                val f = state.value.files.firstOrNull() ?: return
                viewModelScope.launch {
                    val fresh = withContext(Dispatchers.IO) {
                        runCatching {
                            if (f.uri.scheme == ContentResolver.SCHEME_FILE) {
                                File(f.uri.path!!).takeIf { it.isFile }?.let(::fileEntry)
                            } else {
                                val tree = DocumentsContract.buildTreeDocumentUri(f.parentDir.authority, DocumentsContract.getTreeDocumentId(f.parentDir))
                                findInTree(resolver, tree, f.name, 0)
                            }
                        }.getOrNull()
                    }
                    if (fresh != null) setSingle(fresh) else clearSource()
                }
            }
            null -> Unit
        }
    }

    private fun rescan(source: Source.Folder) = scan(source) { cancelled, found ->
        scanTree(resolver, source.uri, state.value.includeSubfolders, cancelled, found)
    }

    private fun rescanPath(source: Source.PathFolder) = scan(source) { cancelled, found ->
        scanDirectory(source.dir, state.value.includeSubfolders, cancelled, found)
    }

    private fun scan(source: Source, lister: (isCancelled: () -> Boolean, onFound: (Int) -> Unit) -> List<Mp3File>) {
        scanJob?.cancel()
        _state.update { it.copy(source = source, files = emptyList(), scanning = true, scanCount = 0, scanError = null) }
        scanJob = viewModelScope.launch {
            val job = coroutineContext[Job]!!
            val result = withContext(Dispatchers.IO) {
                runCatching { lister({ !job.isActive }) { n -> _state.update { it.copy(scanCount = n) } } }
            }
            result.onSuccess { files -> _state.update { it.copy(files = files, scanning = false) } }
                .onFailure { _state.update { it.copy(scanning = false, scanError = "Couldn't read that folder. Try selecting it again.") } }
        }
    }

    fun clearSource() {
        scanJob?.cancel()
        _state.update { it.copy(source = null, files = emptyList(), scanning = false, scanError = null) }
    }

    fun start() {
        val s = state.value
        if (s.scanning) return
        BatchRunner.start(getApplication(), s.files, s.settings, s.replaceOriginals)
    }

    fun cancel() = BatchRunner.cancel()

    /** Back to setup after a run; rescans so the list reflects the new files. */
    fun dismissRun() {
        BatchRunner.dismiss()
        rescan()
    }

    fun trashRequest(): IntentSender? = BatchRunner.trashRequest(getApplication())

    fun onTrashResult(approved: Boolean) = BatchRunner.onTrashResult(getApplication(), approved)
}
