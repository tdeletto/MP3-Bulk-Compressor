package com.tdeletto.mp3bulk

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tdeletto.mp3bulk.encoder.*
import com.tdeletto.mp3bulk.files.Mp3File
import com.tdeletto.mp3bulk.files.SHRUNK_SUFFIX
import com.tdeletto.mp3bulk.files.formatBytes
import com.tdeletto.mp3bulk.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent { AppTheme { App(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        if (BatchRunner.isRunning) return
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        // Only content:// URIs granted to us; never raw file paths from other apps.
        uri?.takeIf { it.scheme == "content" }?.let(vm::onFilePicked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val run by vm.run.collectAsStateWithLifecycle()
    val current = run

    LocalView.current.keepScreenOn = current != null && !current.finished

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MP3 Compressor", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                current == null -> settingsSummary(state.settings)
                                current.finished -> if (current.cancelled) "Stopped" else "Finished"
                                else -> "Compressing ${current.completed + current.active.size} of ${current.total}…"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (current != null) RunScreen(current, padding, vm)
        else SetupScreen(state, vm, padding)
    }
}

private fun settingsSummary(s: EncodeSettings): String {
    if (s == EncodeSettings()) return "All settings: keep original"
    val parts = listOf(
        s.mode?.label ?: "Mode: keep",
        if (s.kbps == KEEP) "Bitrate: keep" else if (s.kbps == V0_KBPS) "V0" else "${s.kbps} kbps",
        if (s.sampleRate == KEEP) "Rate: keep" else "%,d Hz".format(s.sampleRate),
        s.channels?.label ?: "Channels: keep",
    ) + listOfNotNull(if (s.highPass) "HP 80 Hz" else null, if (s.lowPass) "LP 15 kHz" else null)
    return parts.joinToString(" • ")
}

// ---------------------------------------------------------------- Setup

@Composable
private fun SetupScreen(state: UiState, vm: MainViewModel, padding: PaddingValues) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::onFolderPicked) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::onFilePicked) }
    val grantPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        if (it != null) vm.onFolderGrantedForFile(it) else vm.dismissFolderNeeded()
    }
    val allFilesSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onAllFilesResult() }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    var confirmReplace by remember { mutableStateOf(false) }

    val startRun = {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SourceCard(
                    state = state,
                    onPickFolder = { folderPicker.launch(null) },
                    onPickFile = { filePicker.launch(arrayOf("audio/mpeg", "audio/mp3")) },
                    onClear = vm::clearSource,
                    onSubfolders = vm::setIncludeSubfolders,
                    onDownload = vm::useDownloadFolder,
                )
            }
            item { PresetCard(state.settings, vm::updateSettings) }
            item { SettingsCard(state.settings, vm::updateSettings) }
            item { OutputCard(state.replaceOriginals, vm::setReplaceOriginals) }
            if (state.files.isNotEmpty()) fileList(state.files)
        }

        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
            val count = state.files.size
            Button(
                onClick = { if (state.replaceOriginals) confirmReplace = true else startRun() },
                enabled = count > 0 && !state.scanning,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Rounded.Compress, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        state.scanning -> "Finding MP3s…"
                        count == 0 -> "Select MP3s to start"
                        count == 1 -> "Compress 1 file"
                        else -> "Compress All ($count files)"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Replace originals?") },
            text = {
                Text(
                    "Each new file is fully checked first. Then its original moves to the system Trash (kept 30 days, " +
                        "restorable in Files by Google) and the new file takes the original name.\n\n" +
                        "If Android needs your OK to move them to Trash, it asks once at the end.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmReplace = false; startRun() }) { Text("Compress & replace") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Cancel") } },
        )
    }

    state.folderNeeded?.let { needed ->
        AlertDialog(
            onDismissRequest = vm::dismissFolderNeeded,
            icon = { Icon(Icons.Outlined.FolderOpen, null) },
            title = { Text("Allow access to its folder") },
            text = {
                Text(
                    "To save the compressed file next to “${needed.name}”, choose the folder that contains it, " +
                        "then tap “Use this folder” and “Allow”. You only need to do this once per folder.",
                )
            },
            confirmButton = { TextButton(onClick = { grantPicker.launch(needed.hint) }) { Text("Choose folder") } },
            dismissButton = { TextButton(onClick = vm::dismissFolderNeeded) { Text("Cancel") } },
        )
    }

    state.allFilesNeeded?.let { need ->
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = vm::dismissAllFilesNeeded,
            icon = { Icon(Icons.Outlined.AdminPanelSettings, null) },
            title = { Text("Allow All files access") },
            text = { Text(need.message) },
            confirmButton = {
                TextButton(onClick = {
                    val appSettings = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    runCatching { allFilesSettings.launch(appSettings) }
                        .onFailure { allFilesSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = vm::dismissAllFilesNeeded) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CardTitle(icon: ImageVector, text: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun SourceCard(
    state: UiState,
    onPickFolder: () -> Unit,
    onPickFile: () -> Unit,
    onClear: () -> Unit,
    onSubfolders: (Boolean) -> Unit,
    onDownload: () -> Unit,
) {
    val source = state.source
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(Icons.Outlined.LibraryMusic, "Source") {
                if (source != null) IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Clear selection") }
            }
            if (source == null) {
                Text(
                    "Choose a folder to find every MP3 inside it, or pick a single MP3.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (source is Source.Single) Icons.Outlined.AudioFile else Icons.Outlined.Folder, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(source.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                state.scanning -> "Scanning${if (state.includeSubfolders) " subfolders" else ""}… ${state.scanCount} found"
                                state.files.isEmpty() -> "No MP3 files found"
                                else -> "${state.files.size} MP3 file${if (state.files.size == 1) "" else "s"} · ${formatBytes(state.files.sumOf { it.size })}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            state.scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            if (source !is Source.Single) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip16()
                        .clickable(enabled = !state.scanning) { onSubfolders(!state.includeSubfolders) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AccountTree, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Include all subfolders", Modifier.weight(1f).padding(horizontal = 12.dp))
                    Switch(checked = state.includeSubfolders, onCheckedChange = onSubfolders, enabled = !state.scanning)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPickFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (source != null && source !is Source.Single) "Change Folder" else "Select Folder")
                }
                OutlinedButton(onClick = onPickFile, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.AudioFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Select File")
                }
            }
            TextButton(onClick = onDownload, enabled = !state.scanning, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use the Download folder")
            }
        }
    }
}

private fun Modifier.clip16() = this.then(Modifier.clip(RoundedCornerShape(16.dp)))

@Composable
private fun PresetCard(settings: EncodeSettings, onChange: (EncodeSettings) -> Unit) {
    val preset = Preset.matching(settings)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CardTitle(Icons.Rounded.AutoAwesome, "Presets") {
                if (preset == Preset.CUSTOM) AssistChip(onClick = {}, label = { Text("Custom") }, enabled = false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetTile(Icons.Outlined.Restore, "Keep original", "No changes", preset == Preset.ORIGINAL, Modifier.weight(1f)) {
                    onChange(Preset.ORIGINAL.settings!!)
                }
                PresetTile(Icons.Rounded.Podcasts, "Podcast", "VBR 64 · mono", preset == Preset.PODCAST, Modifier.weight(1f)) {
                    onChange(Preset.PODCAST.settings!!)
                }
                PresetTile(Icons.Rounded.MusicNote, "HQ Music", "V0 · stereo", preset == Preset.HQ_MUSIC, Modifier.weight(1f)) {
                    onChange(Preset.HQ_MUSIC.settings!!)
                }
            }
        }
    }
}

@Composable
private fun PresetTile(icon: ImageVector, title: String, subtitle: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) colors.primaryContainer else colors.surface),
        border = if (selected) BorderStroke(2.dp, colors.primary) else CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(12.dp).fillMaxWidth()) {
            Icon(icon, null, tint = if (selected) colors.onPrimaryContainer else colors.primary)
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
            }
        }
    }
}

@Composable
private fun SettingsCard(s: EncodeSettings, onChange: (EncodeSettings) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(Icons.Rounded.Tune, "Settings")

            ChipGroup("Bitrate mode", listOf(null) + EncodeMode.entries, s.mode, { it?.label ?: "Keep" }) { m ->
                // V0 only exists in VBR; fall back to the nearest CBR/ABR bitrate.
                onChange(s.copy(mode = m, kbps = if (m != EncodeMode.VBR && s.kbps == V0_KBPS) 256 else s.kbps))
            }
            val bitrates = listOf(KEEP) + (BITRATES + if (s.mode == EncodeMode.VBR) listOf(V0_KBPS) else emptyList()).sorted()
            ChipGroup(if (s.mode == EncodeMode.VBR) "Bitrate (kbps, VBR target)" else "Bitrate (kbps)", bitrates, s.kbps, ::bitrateLabel) {
                onChange(s.copy(kbps = it))
            }
            ChipGroup("Sample rate (Hz)", listOf(KEEP) + SAMPLE_RATES, s.sampleRate, ::sampleRateLabel) { onChange(s.copy(sampleRate = it)) }
            ChipGroup("High-pass filter", listOf(false, true), s.highPass, { if (it) "On · $HIGH_PASS_HZ Hz" else "Keep (off)" }) {
                onChange(s.copy(highPass = it))
            }
            ChipGroup("Low-pass filter", listOf(false, true), s.lowPass, { if (it) "On · 15,000 Hz" else "Keep (off)" }) {
                onChange(s.copy(lowPass = it))
            }
            ChipGroup("Channels", listOf(null, ChannelOut.STEREO, ChannelOut.JOINT, ChannelOut.MONO), s.channels, { it?.label ?: "Keep" }) {
                onChange(s.copy(channels = it))
            }

            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "“Keep” uses each file's current value. Files already below the chosen bitrate keep their bitrate. " +
                        "Nothing is upsampled, mono files stay mono, and tags and cover art are kept. " +
                        "Files that wouldn't get smaller are skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OutputCard(replace: Boolean, onChange: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Box(Modifier.padding(horizontal = 16.dp)) { CardTitle(Icons.Outlined.SaveAlt, "Output") }
            OutputOption(!replace, "Keep original, save a copy", "Saves “name$SHRUNK_SUFFIX.mp3” in the same folder", Icons.Outlined.FileCopy) { onChange(false) }
            OutputOption(replace, "Replace original", "After checking the new file, moves the original to Trash and uses its name", Icons.Outlined.SwapHoriz) { onChange(true) }
        }
    }
}

@Composable
private fun OutputOption(selected: Boolean, title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

private const val LIST_LIMIT = 300

private fun LazyListScope.fileList(files: List<Mp3File>) {
    item {
        Text("Files (${files.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
    }
    items(files.take(LIST_LIMIT), key = { it.uri.toString() }) { f ->
        FileRow(f, "Pending", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, formatBytes(f.size))
    }
    if (files.size > LIST_LIMIT) item {
        Text("+ ${files.size - LIST_LIMIT} more", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun FileRow(file: Mp3File, status: String, chipColor: Color, chipText: Color, detail: String, progress: Float? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val dir = if (file.relativeDir.isEmpty()) "" else "${file.relativeDir} · "
                    Text("$dir$detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Surface(color = chipColor, contentColor = chipText, shape = RoundedCornerShape(8.dp)) {
                    Text(status, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- Run

@Composable
private fun RunScreen(run: RunState, padding: PaddingValues, vm: MainViewModel) {
    BackHandler(enabled = run.finished && !run.trashBusy, onBack = vm::dismissRun)
    val progress by animateFloatAsState(run.fraction, label = "progress")
    val colors = MaterialTheme.colorScheme

    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onTrashResult(r.resultCode == Activity.RESULT_OK)
    }
    val pending = run.pendingTrash.size
    val askTrash = { vm.trashRequest()?.let { trashLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
    // Ask once automatically when the batch ends; the button stays available if the prompt is dismissed.
    var askedFor by rememberSaveable { mutableLongStateOf(0L) }
    LaunchedEffect(run.finished, pending) {
        if (run.finished && pending > 0 && !run.trashBusy && askedFor != run.startedAt) {
            askedFor = run.startedAt
            askTrash()
        }
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${run.completed} of ${run.total}", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape),
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Stat("Saved", formatBytes(run.bytesSaved), Modifier.weight(1f))
                            Stat("Compressed", "${run.done.size}", Modifier.weight(1f))
                            Stat("Skipped", "${run.skipped}", Modifier.weight(1f))
                            Stat("Failed", "${run.failed}", Modifier.weight(1f))
                        }
                        if (run.finished) {
                            Spacer(Modifier.height(12.dp))
                            val secs = (run.endedAt - run.startedAt) / 1000
                            Text(
                                (if (run.cancelled) "Stopped after " else "Finished in ") + "${secs / 60}m ${secs % 60}s",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (run.finished && (pending > 0 || run.trashBusy)) item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = colors.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Approve moving originals to Trash", style = MaterialTheme.typography.titleMedium, color = colors.onTertiaryContainer)
                        Text(
                            if (run.trashBusy) "Finishing up…"
                            else "$pending new file(s) are saved and checked. Until you approve, originals stay put and the new files keep the “$SHRUNK_SUFFIX” name.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onTertiaryContainer,
                        )
                        if (!run.trashBusy) Button(onClick = { askTrash() }) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Move $pending to Trash")
                        }
                    }
                }
            }

            items(run.active.entries.toList(), key = { "a" + it.key }) { (uri, v) ->
                val file = Mp3File(uri, v.first, 0, "", uri)
                FileRow(file, "Working", colors.primaryContainer, colors.onPrimaryContainer, "${(v.second * 100).toInt()}%", v.second)
            }
            items(run.results.asReversed(), key = { "r" + it.file.uri }) { r -> ResultRow(r) }
            item { LogCard(run.log) }
        }

        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
            val mod = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp)
            if (run.finished) {
                Button(onClick = vm::dismissRun, enabled = !run.trashBusy, modifier = mod, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text("Done", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                OutlinedButton(onClick = vm::cancel, modifier = mod, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultRow(r: FileResult) {
    val colors = MaterialTheme.colorScheme
    when (val o = r.outcome) {
        is Outcome.Done -> {
            val status = when (o.status) {
                SaveStatus.COPY -> "Done"
                SaveStatus.REPLACED -> "Replaced"
                SaveStatus.AWAITING_TRASH -> "Awaiting Trash"
                SaveStatus.KEPT_BOTH -> "Done"
            }
            val pct = if (o.oldSize > 0) (o.oldSize - o.newSize) * 100 / o.oldSize else 0
            val detail = "${formatBytes(o.oldSize)} → ${formatBytes(o.newSize)} (−$pct%) · ${o.plan.summary()}\n→ ${o.outputName}" +
                (o.note?.let { "\n$it" } ?: "")
            FileRow(r.file, status, colors.primary, colors.onPrimary, detail)
        }
        is Outcome.Skipped -> FileRow(r.file, "Skip", colors.secondaryContainer, colors.onSecondaryContainer, o.reason)
        is Outcome.Failed -> FileRow(r.file, "Failed", colors.errorContainer, colors.onErrorContainer, "${o.message}. Original untouched.")
    }
}

@Composable
private fun LogCard(log: List<LogLine>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize()) {
        Column(Modifier.padding(16.dp)) {
            CardTitle(Icons.AutoMirrored.Outlined.Article, "Log (${log.size})") {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) {
                log.takeLast(300).forEach { line ->
                    Text(
                        "[${BatchRunner.formatTime(line.time)}] ${line.text}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}
