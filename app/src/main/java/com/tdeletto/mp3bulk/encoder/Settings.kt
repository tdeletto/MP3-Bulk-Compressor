package com.tdeletto.mp3bulk.encoder

import kotlin.math.abs

/** Ordinals must match MODE_* in lame_jni.c. */
enum class EncodeMode(val label: String, val description: String) {
    CBR("CBR", "Constant bitrate: predictable size, most compatible"),
    VBR("VBR", "Variable bitrate: best quality for the size"),
    ABR("ABR", "Average bitrate: size close to target, adaptive"),
}

/** Ordinals must match CH_* in lame_jni.c. */
enum class ChannelOut(val label: String) {
    MONO("Mono"),
    STEREO("Full stereo"),
    JOINT("Joint stereo"),
}

/** "Keep original" for the numeric settings. */
const val KEEP = 0

/** LAME -V0 averages about 245 kbps; only offered in VBR mode. */
const val V0_KBPS = 245

const val HIGH_PASS_HZ = 80
const val LOW_PASS_HZ = 15_000

/** Null / [KEEP] / false means "leave that property as the file has it". */
data class EncodeSettings(
    val mode: EncodeMode? = null,
    val kbps: Int = KEEP,
    val sampleRate: Int = KEEP,
    val highPass: Boolean = false,
    val lowPass: Boolean = false,
    val channels: ChannelOut? = null,
)

enum class Preset(val label: String, val settings: EncodeSettings?) {
    ORIGINAL("Keep original", EncodeSettings()),
    PODCAST("Podcast", EncodeSettings(EncodeMode.VBR, 64, 44_100, highPass = true, lowPass = true, channels = ChannelOut.MONO)),
    HQ_MUSIC("HQ Music", EncodeSettings(EncodeMode.VBR, V0_KBPS, 44_100, highPass = false, lowPass = false, channels = ChannelOut.STEREO)),
    CUSTOM("Custom", null);

    companion object {
        fun matching(s: EncodeSettings): Preset = entries.firstOrNull { it.settings == s } ?: CUSTOM
    }
}

val BITRATES = listOf(32, 48, 64, 96, 128, 160, 192, 224, 256, 320)
val SAMPLE_RATES = listOf(8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000)

fun bitrateLabel(kbps: Int) = when (kbps) {
    KEEP -> "Keep"
    V0_KBPS -> "V0 (~245)"
    else -> "$kbps"
}

fun sampleRateLabel(hz: Int) = if (hz == KEEP) "Keep" else "%,d".format(hz)

/** What the source file currently is, from [Mp3Probe]. */
data class SourceInfo(
    val mode: EncodeMode,
    /** Average bitrate in kbps. */
    val kbps: Int,
    val sampleRate: Int,
    val channels: ChannelOut,
    val durationUs: Long,
)

/** The concrete encode for one file. */
data class FilePlan(
    val mode: EncodeMode,
    val kbps: Int,
    val sampleRate: Int,
    val channels: ChannelOut,
    val highPass: Boolean,
    val lowPass: Boolean,
    val keptSourceBitrate: Boolean,
)

private val MPEG1_CBR = listOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
private val MPEG2_CBR = listOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

/** Legal bitrate range for an output sample rate (MPEG-1 / MPEG-2 / MPEG-2.5). */
private fun bitrateRange(rate: Int) = when {
    rate >= 32_000 -> 32..320
    rate >= 16_000 -> 8..160
    else -> 8..64
}

private fun snapCbr(kbps: Int, rate: Int): Int {
    val table = if (rate >= 32_000) MPEG1_CBR else MPEG2_CBR.filter { it in bitrateRange(rate) }
    return table.lastOrNull { it <= kbps } ?: table.first()
}

/** True when [a] and [b] are the same bitrate for practical purposes (VBR averages wobble). */
private fun sameBitrate(a: Int, b: Int) = abs(a - b) <= maxOf(3, b / 20)

/**
 * Resolves settings against one file. Rules:
 * - "Keep" takes the file's own value.
 * - The bitrate is never raised: a source at or below the chosen bitrate keeps its bitrate.
 * - Nothing is upsampled, and a mono file is never made stereo.
 * Returns null when the result would be the same as the source.
 */
fun planFor(settings: EncodeSettings, src: SourceInfo): FilePlan? {
    val rate = if (settings.sampleRate == KEEP || settings.sampleRate >= src.sampleRate) src.sampleRate else settings.sampleRate
    val channels = when {
        settings.channels == null -> src.channels
        src.channels == ChannelOut.MONO -> ChannelOut.MONO
        else -> settings.channels
    }
    val mode = settings.mode ?: src.mode

    val sourceNotHigher = src.kbps > 0 && (src.kbps < settings.kbps || sameBitrate(src.kbps, settings.kbps))
    val keptSource = settings.kbps == KEEP || sourceNotHigher
    var kbps = if (keptSource) src.kbps else settings.kbps
    if (kbps <= 0) kbps = 128
    val range = bitrateRange(rate)
    kbps = kbps.coerceIn(range)
    if (mode == EncodeMode.CBR) kbps = snapCbr(kbps + if (keptSource) 2 else 0, rate)

    val unchanged = rate == src.sampleRate &&
        channels == src.channels &&
        mode == src.mode &&
        !settings.highPass && !settings.lowPass &&
        (keptSource || sameBitrate(kbps, src.kbps))
    if (unchanged) return null

    return FilePlan(mode, kbps, rate, channels, settings.highPass, settings.lowPass, keptSource && settings.kbps != KEEP)
}

/** 44100 -> "44.1 kHz", 22050 -> "22.05 kHz", 8000 -> "8 kHz". */
fun kHz(hz: Int): String = (hz / 1000.0).toString().removeSuffix(".0") + " kHz"

fun FilePlan.summary(): String = buildString {
    append(if (mode == EncodeMode.VBR && kbps >= V0_KBPS) "VBR V0" else "${mode.label} $kbps kbps")
    if (keptSourceBitrate) append(" (kept)")
    append(" · ").append(kHz(sampleRate))
    append(" · ").append(channels.label.lowercase())
    if (highPass) append(" · HP")
    if (lowPass) append(" · LP")
}

fun SourceInfo.summary(): String = "${mode.label} $kbps kbps · ${kHz(sampleRate)} · ${channels.label.lowercase()}"
