package com.tdeletto.mp3bulk

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.tdeletto.mp3bulk.encoder.EncodeSettings
import com.tdeletto.mp3bulk.encoder.Outcome
import com.tdeletto.mp3bulk.encoder.SaveStatus
import com.tdeletto.mp3bulk.encoder.Transcoder
import com.tdeletto.mp3bulk.encoder.summary
import com.tdeletto.mp3bulk.files.Mp3File
import com.tdeletto.mp3bulk.files.Storage
import com.tdeletto.mp3bulk.files.formatBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FileResult(val file: Mp3File, val outcome: Outcome)

data class LogLine(val time: Long, val text: String, val isError: Boolean = false)

data class RunState(
    val total: Int,
    val replace: Boolean,
    val results: List<FileResult> = emptyList(),
    /** In-flight files and their 0..1 progress. */
    val active: Map<Uri, Pair<String, Float>> = emptyMap(),
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    /** Waiting for the user to approve moving originals to Trash. */
    val trashBusy: Boolean = false,
    val log: List<LogLine> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long = 0,
) {
    val completed get() = results.size
    val fraction: Float get() = if (total == 0) 1f else (completed + active.values.sumOf { it.second.toDouble() }.toFloat()) / total
    val done get() = results.mapNotNull { it.outcome as? Outcome.Done }
    val skipped get() = results.count { it.outcome is Outcome.Skipped }
    val failed get() = results.count { it.outcome is Outcome.Failed }
    val pendingTrash get() = done.mapNotNull { it.pending }
    val bytesSaved get() = done.sumOf { it.oldSize - it.newSize }
}

/** Runs a batch independently of any screen, so it survives rotation and the app going to the background. */
object BatchRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = _state.asStateFlow()
    private var job: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val isRunning get() = job?.isActive == true

    fun start(context: Context, files: List<Mp3File>, settings: EncodeSettings, replace: Boolean) {
        if (isRunning || files.isEmpty()) return
        val app = context.applicationContext
        val transcoder = Transcoder(app)
        _state.value = RunState(total = files.size, replace = replace)
        log("Started ${files.size} file(s) · ${if (replace) "replace originals" else "save “ - SHRUNK” copies"}")
        ContextCompat.startForegroundService(app, Intent(app, CompressService::class.java))

        val wakeLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mp3bulk:encode")
        wakeLock.acquire(12 * 60 * 60 * 1000L)

        job = scope.launch {
            try {
                val queue = Channel<Mp3File>(Channel.UNLIMITED)
                files.forEach { queue.trySend(it) }
                queue.close()
                val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
                coroutineScope {
                    repeat(workers) {
                        launch {
                            for (file in queue) {
                                setActive(file, 0f)
                                val outcome = transcoder.process(file, settings, replace) { p -> setActive(file, p) }
                                record(file, outcome)
                            }
                        }
                    }
                }
                finish(cancelled = false)
            } finally {
                if (!isActive) finish(cancelled = true)
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!isRunning && state.value?.trashBusy != true) _state.value = null
    }

    /** System prompt for originals that need the user's OK to go to Trash, or null if none. */
    fun trashRequest(context: Context): IntentSender? {
        val pending = state.value?.pendingTrash.orEmpty()
        if (pending.isEmpty()) return null
        return runCatching { Storage.trashRequest(context.contentResolver, pending.map { it.media }) }
            .onFailure { log("Couldn't open the Trash prompt: ${it.message}", error = true) }
            .getOrNull()
    }

    fun onTrashResult(context: Context, approved: Boolean) {
        val transcoder = Transcoder(context.applicationContext)
        _state.update { it?.copy(trashBusy = true) }
        scope.launch {
            val run = state.value ?: return@launch
            val updated = run.results.map { r ->
                val o = r.outcome as? Outcome.Done ?: return@map r
                val p = o.pending ?: return@map r
                val next = if (approved) transcoder.finishReplace(p, o) else transcoder.declineReplace(o)
                logResult(r.file, next)
                r.copy(outcome = next)
            }
            _state.update { it?.copy(results = updated, trashBusy = false) }
        }
    }

    private fun setActive(file: Mp3File, progress: Float) {
        _state.update { st -> st?.copy(active = st.active + (file.uri to (file.name to progress))) }
    }

    private fun record(file: Mp3File, outcome: Outcome) {
        _state.update { st -> st?.copy(results = st.results + FileResult(file, outcome), active = st.active - file.uri) }
        logResult(file, outcome)
    }

    private fun logResult(file: Mp3File, outcome: Outcome) {
        val path = if (file.relativeDir.isEmpty()) file.name else "${file.relativeDir}/${file.name}"
        when (outcome) {
            is Outcome.Done -> {
                val what = when (outcome.status) {
                    SaveStatus.COPY -> "saved as “${outcome.outputName}”"
                    SaveStatus.REPLACED -> "replaced; original in Trash"
                    SaveStatus.AWAITING_TRASH -> "verified; waiting for Trash approval"
                    SaveStatus.KEPT_BOTH -> "saved as copy"
                }
                log("✓ $path: ${formatBytes(outcome.oldSize)} → ${formatBytes(outcome.newSize)} (${outcome.plan.summary()}), $what" +
                    (outcome.note?.let { ". $it" } ?: ""))
            }
            is Outcome.Skipped -> log("– $path: skipped, ${outcome.reason}")
            is Outcome.Failed -> log("✗ $path: ${outcome.message}. Original untouched.", error = true)
        }
    }

    private fun log(text: String, error: Boolean = false) {
        _state.update { st -> st?.copy(log = (st.log + LogLine(System.currentTimeMillis(), text, error)).takeLast(1000)) }
    }

    fun formatTime(t: Long): String = synchronized(timeFormat) { timeFormat.format(Date(t)) }

    private fun finish(cancelled: Boolean) {
        val st = state.value ?: return
        if (st.finished) return
        _state.update { it?.copy(finished = true, cancelled = cancelled, active = emptyMap(), endedAt = System.currentTimeMillis()) }
        val s = state.value!!
        log((if (cancelled) "Stopped. " else "Batch complete. ") +
            "${s.done.size} compressed, ${s.skipped} skipped, ${s.failed} failed, ${formatBytes(s.bytesSaved)} saved")
    }
}
