package com.tdeletto.mp3bulk.encoder

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.tdeletto.mp3bulk.files.Mp3File
import com.tdeletto.mp3bulk.files.SHRUNK_SUFFIX
import com.tdeletto.mp3bulk.files.Storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

private const val TAG = "Mp3Bulk"

enum class SaveStatus { COPY, REPLACED, AWAITING_TRASH, KEPT_BOTH }

/** A verified new file waiting for its original to go to Trash before taking the original's name. */
data class PendingReplace(val original: Uri, val originalName: String, val media: Uri, val staged: Uri)

sealed interface Outcome {
    data class Done(
        val oldSize: Long,
        val newSize: Long,
        val source: SourceInfo,
        val plan: FilePlan,
        val outputName: String,
        val status: SaveStatus,
        val note: String? = null,
        val pending: PendingReplace? = null,
    ) : Outcome
    data class Skipped(val reason: String) : Outcome
    data class Failed(val message: String) : Outcome
}

class Transcoder(private val context: Context) {

    private val resolver = context.contentResolver

    private class EncodeResult(val inFrames: Long, val inRate: Int, val outRate: Int, val channels: Int)

    /**
     * Encode to a private temp file, decode it back to prove it's complete, copy it beside the original
     * and re-read that copy. Only then is the original touched (moved to Trash, never overwritten).
     */
    suspend fun process(file: Mp3File, settings: EncodeSettings, replace: Boolean, onProgress: (Float) -> Unit): Outcome {
        val temp = File.createTempFile("enc", ".mp3", context.cacheDir)
        var unfinished: Uri? = null
        try {
            val probe = timed("probe", file.name) { Mp3Probe.probe(resolver, file.uri) }
            if (probe.mode == null) return Outcome.Failed("Not a valid MP3 (no audio frames found)")

            val source: SourceInfo
            val plan: FilePlan
            val encoded: EncodeResult
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, file.uri, null)
                val track = audioTrack(extractor) ?: return Outcome.Failed("No audio track")
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                source = sourceInfo(probe, format)
                plan = planFor(settings, source)
                    ?: return Outcome.Skipped(if (settings == EncodeSettings()) "All settings are “Keep”" else "Already matches settings")
                encoded = timed("encode", file.name) { encode(extractor, format, plan, source.durationUs, probe, temp) { onProgress(it * 0.8f) } }
            } finally {
                extractor.release()
            }

            val decodedUs = encoded.inFrames * 1_000_000L / encoded.inRate
            val expectedUs = probe.trustedDurationUs
            if (expectedUs > 0 && decodedUs < expectedUs * 0.98 - 500_000) {
                return Outcome.Failed("Source is damaged: only %.1fs of %.1fs could be decoded".format(decodedUs / 1e6, expectedUs / 1e6))
            }

            timed("verify", file.name) { verify(temp, encoded) { onProgress(0.8f + it * 0.15f) } }

            val newSize = temp.length()
            if (newSize >= probe.fileSize) {
                return Outcome.Skipped("Wouldn't be smaller (source is ${source.summary()})")
            }

            val base = file.name.substringBeforeLast('.', file.name)
            val shrunkName = "$base$SHRUNK_SUFFIX.mp3"
            val staged = Storage.createSibling(resolver, file.parentDir, shrunkName)
            unfinished = staged
            timed("write", file.name) { Storage.writeVerified(resolver, staged, temp) }
            Storage.notifyMediaChanged(context, staged)
            onProgress(0.98f)
            val done = Outcome.Done(
                oldSize = probe.fileSize,
                newSize = newSize,
                source = source,
                plan = plan.copy(sampleRate = encoded.outRate),
                outputName = Storage.displayName(resolver, staged) ?: shrunkName,
                status = SaveStatus.COPY,
            )
            unfinished = null
            return if (replace) replaceOriginal(file, staged, done) else done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Outcome.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            temp.delete()
            // Only a partially written output of ours is removed; originals are never deleted here.
            unfinished?.let { Storage.delete(resolver, it) }
        }
    }

    private inline fun <T> timed(step: String, name: String, block: () -> T): T {
        val t0 = SystemClock.elapsedRealtime()
        return block().also { Log.i(TAG, "$step took ${SystemClock.elapsedRealtime() - t0} ms: $name") }
    }

    private fun replaceOriginal(file: Mp3File, staged: Uri, done: Outcome.Done): Outcome.Done {
        val media = timed("mediaUri", file.name) { Storage.mediaUri(context, file.uri) }
            ?: return done.copy(status = SaveStatus.KEPT_BOTH, note = "Original kept: it isn't in the media library, so it can't go to Trash")
        val pending = PendingReplace(file.uri, file.name, media, staged)
        return if (timed("trashDirect", file.name) { Storage.trashDirect(resolver, media) }) finishReplace(pending, done)
        else done.copy(status = SaveStatus.AWAITING_TRASH, pending = pending)
    }

    /** Once the original is in Trash, the new file takes its name. */
    fun finishReplace(p: PendingReplace, done: Outcome.Done): Outcome.Done {
        if (timed("exists", p.originalName) { Storage.exists(resolver, p.original) }) {
            return done.copy(status = SaveStatus.KEPT_BOTH, pending = null, note = "Original wasn't moved to Trash, so both files were kept")
        }
        val renamed = timed("rename", p.originalName) { Storage.rename(resolver, p.staged, p.originalName) }
            ?: return done.copy(status = SaveStatus.REPLACED, pending = null, note = "Original is in Trash, but the new file couldn't be renamed")
        Storage.notifyMediaChanged(context, p.staged, renamed)
        val name = timed("displayName", p.originalName) { Storage.displayName(resolver, renamed) }
        return done.copy(status = SaveStatus.REPLACED, pending = null, outputName = name ?: p.originalName)
    }

    fun declineReplace(done: Outcome.Done): Outcome.Done =
        done.copy(status = SaveStatus.KEPT_BOTH, pending = null, note = "Trash not approved, so the original was kept")

    private fun sourceInfo(probe: ProbeResult, format: MediaFormat): SourceInfo {
        val extractorUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val durationUs = probe.trustedDurationUs.takeIf { it > 0 } ?: extractorUs
        val mode = probe.mode ?: EncodeMode.CBR
        val kbps = when {
            mode == EncodeMode.CBR && probe.headerKbps > 0 -> probe.headerKbps
            durationUs > 1_000_000 -> (probe.audioBytes * 8_000L / durationUs).toInt()
            else -> probe.headerKbps
        }
        return SourceInfo(mode, kbps, probe.sampleRate, probe.channels ?: ChannelOut.STEREO, durationUs)
    }

    private suspend fun encode(
        extractor: MediaExtractor,
        format: MediaFormat,
        plan: FilePlan,
        durationUs: Long,
        probe: ProbeResult,
        out: File,
        onProgress: (Float) -> Unit,
    ): EncodeResult {
        var encoder: LameEncoder? = null
        var inFrames = 0L
        var inRate = 0
        var pcm = ShortArray(0)
        var mp3 = ByteArray(0)
        var lastPct = -1
        try {
            BufferedOutputStream(FileOutputStream(out), 1 shl 16).use { stream ->
                stream.write(probe.id3v2)
                decode(extractor, format) { buf, fmt, ptsUs ->
                    val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val enc = encoder ?: run {
                        inRate = rate
                        val outCh = if (plan.channels == ChannelOut.MONO || inCh == 1) 1 else 2
                        LameEncoder(rate, outCh, plan.copy(sampleRate = minOf(plan.sampleRate, rate))).also { encoder = it }
                    }
                    val frames = readPcm(buf, isFloat(fmt), inCh, enc.channels) { size ->
                        if (pcm.size < size) pcm = ShortArray(size)
                        pcm
                    }
                    inFrames += frames
                    val need = LameEncoder.outBufferSize(frames)
                    if (mp3.size < need) mp3 = ByteArray(need)
                    stream.write(mp3, 0, enc.encode(pcm, frames, mp3))
                    if (durationUs > 0) {
                        val pct = (ptsUs * 100 / durationUs).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                    }
                }
                val enc = encoder ?: error("Decoder produced no audio")
                if (mp3.size < 7200) mp3 = ByteArray(7200)
                stream.write(mp3, 0, enc.flush(mp3))
                stream.write(probe.id3v1)
            }

            // VBR/ABR: LAME reserved the first frame; patch in the real Xing/LAME header so players show the right duration.
            val enc = encoder!!
            val tag = ByteArray(8192)
            val tagLen = enc.tagFrame(tag)
            if (tagLen > 0) {
                RandomAccessFile(out, "rw").use { raf ->
                    raf.seek(probe.id3v2.size.toLong())
                    raf.write(tag, 0, tagLen)
                    raf.fd.sync()
                }
            }
            onProgress(1f)
            return EncodeResult(inFrames, inRate, enc.outSampleRate, enc.channels)
        } finally {
            encoder?.close()
        }
    }

    /** Decodes the finished file end to end and checks format and length against what went in. */
    private suspend fun verify(file: File, encoded: EncodeResult, onProgress: (Float) -> Unit) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val track = audioTrack(extractor) ?: error("Check failed: output has no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            check(format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_MPEG) { "Check failed: output isn't MP3" }
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            var frames = 0L
            var rate = 0
            var channels = 0
            var lastPct = -1
            decode(extractor, format) { buf, fmt, ptsUs ->
                rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                frames += buf.remaining() / ((if (isFloat(fmt)) 4 else 2) * channels)
                if (durationUs > 0) {
                    val pct = (ptsUs * 100 / durationUs).toInt().coerceIn(0, 100)
                    if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                }
            }
            check(rate == encoded.outRate) { "Check failed: output is $rate Hz, expected ${encoded.outRate} Hz" }
            check(channels == encoded.channels) { "Check failed: output has $channels channels, expected ${encoded.channels}" }
            val inSec = encoded.inFrames.toDouble() / encoded.inRate
            val outSec = frames.toDouble() / rate
            check(inSec > 0 && abs(outSec - inSec) <= 0.25 + inSec * 0.005) {
                "Check failed: output is %.2fs long, source is %.2fs".format(outSec, inSec)
            }
        } finally {
            extractor.release()
        }
    }

    private fun audioTrack(extractor: MediaExtractor): Int? = (0 until extractor.trackCount).firstOrNull {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
    }

    private fun isFloat(f: MediaFormat) =
        f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT

    /** Runs [extractor]'s selected track through a decoder, handing each PCM buffer to [onPcm]. */
    private suspend fun decode(extractor: MediaExtractor, format: MediaFormat, onPcm: (ByteBuffer, MediaFormat, Long) -> Unit) {
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idleMs = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                // Fill every free input slot without waiting; a blocking dequeue here caps decoding at about real time.
                var fed = false
                while (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(0)
                    if (inIndex < 0) break
                    val buf = codec.getInputBuffer(inIndex)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    fed = true
                }
                // Only pause (briefly) when nothing new was queued and no output is ready yet.
                val outIndex = codec.dequeueOutputBuffer(info, if (fed) 0L else 2_000L)
                if (outIndex >= 0) {
                    idleMs = 0
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIndex)!!
                        buf.position(info.offset).limit(info.offset + info.size)
                        onPcm(buf.order(ByteOrder.nativeOrder()), codec.getOutputFormat(outIndex), info.presentationTimeUs)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (!fed) {
                    idleMs += 2
                    if (idleMs > 15_000) error("Decoder stopped responding")
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** Converts one decoder buffer into interleaved 16-bit PCM with [outCh] channels. Returns frames. */
    private inline fun readPcm(buf: ByteBuffer, isFloat: Boolean, inCh: Int, outCh: Int, target: (Int) -> ShortArray): Int {
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = buf.remaining() / (bytesPerSample * inCh)
        val dst = target(frames * outCh)
        if (!isFloat && inCh == outCh) {
            buf.asShortBuffer().get(dst, 0, frames * outCh)
            return frames
        }
        for (i in 0 until frames) {
            var sum = 0f
            var left = 0f
            var right = 0f
            for (c in 0 until inCh) {
                val s = if (isFloat) buf.getFloat() else buf.getShort() / 32768f
                sum += s
                if (c == 0) left = s
                if (c == 1) right = s
            }
            if (outCh == 1) {
                dst[i] = toShort(sum / inCh)
            } else {
                dst[i * 2] = toShort(left)
                dst[i * 2 + 1] = toShort(if (inCh > 1) right else left)
            }
        }
        return frames
    }

    private fun toShort(v: Float): Short = (v * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
}
