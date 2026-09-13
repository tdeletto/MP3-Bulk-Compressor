package com.tdeletto.mp3bulk.encoder

import android.content.ContentResolver
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Byte layout and encoding of an MP3 file, read straight from its headers. */
class ProbeResult(
    val id3v2: ByteArray,
    val id3v1: ByteArray,
    val fileSize: Long,
    /** Null when no valid MPEG Layer III frames were found. */
    val mode: EncodeMode?,
    val headerKbps: Int,
    val sampleRate: Int,
    val channels: ChannelOut?,
    /** From a Xing/Info/VBRI header; 0 if the file has none. */
    val frameCount: Long,
    val samplesPerFrame: Int,
) {
    val audioBytes get() = (fileSize - id3v2.size - id3v1.size).coerceAtLeast(0)

    /** Exact duration when the file declares its frame count (or is CBR), otherwise 0. */
    val trustedDurationUs: Long
        get() = when {
            frameCount > 0 && sampleRate > 0 -> frameCount * samplesPerFrame * 1_000_000L / sampleRate
            mode == EncodeMode.CBR && headerKbps > 0 -> audioBytes * 8_000L / headerKbps
            else -> 0
        }
}

object Mp3Probe {
    private const val SCAN_BYTES = 512 * 1024

    private val RATES = arrayOf(intArrayOf(44100, 48000, 32000), intArrayOf(22050, 24000, 16000), intArrayOf(11025, 12000, 8000))
    private val KBPS_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val KBPS_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private class Header(val mpeg1: Boolean, val kbps: Int, val rate: Int, val channelMode: Int, val length: Int, val samples: Int)

    fun probe(resolver: ContentResolver, uri: Uri): ProbeResult {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: error("Can't open file")
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch -> return probe(ch) }
        }
    }

    internal fun probe(ch: FileChannel): ProbeResult {
        val size = ch.size()
        val id3v2 = readId3v2(ch, size)
        val id3v1 = readId3v1(ch, size, id3v2.size)
        val start = id3v2.size.toLong()
        val len = minOf(SCAN_BYTES.toLong(), size - start - id3v1.size).coerceAtLeast(0).toInt()
        val buf = ByteBuffer.allocate(len)
        readFully(ch, buf, start)
        return parse(buf.array(), id3v2, id3v1, size)
    }

    private fun parse(b: ByteArray, id3v2: ByteArray, id3v1: ByteArray, size: Long): ProbeResult {
        val none = ProbeResult(id3v2, id3v1, size, null, 0, 0, null, 0, 0)
        val first = firstFrame(b) ?: return none
        val h = header(b, first)!!

        var mode: EncodeMode? = null
        var frames = 0L
        var audioStart = first

        val sideInfo = if (h.mpeg1) (if (h.channelMode == 3) 17 else 32) else (if (h.channelMode == 3) 9 else 17)
        val x = first + 4 + sideInfo
        val tagId = ascii(b, x, 4)
        if (tagId == "Xing" || tagId == "Info") {
            mode = if (tagId == "Xing") EncodeMode.VBR else EncodeMode.CBR
            val flags = int32(b, x + 4)
            var p = x + 8
            if (flags and 1 != 0) { frames = int32(b, p).toLong() and 0xffffffffL; p += 4 }
            if (flags and 2 != 0) p += 4
            if (flags and 4 != 0) p += 100
            if (flags and 8 != 0) p += 4
            val encoder = ascii(b, p, 4)
            if (encoder == "LAME" || encoder == "Lavc" || encoder == "Lavf" || encoder.startsWith("L3.")) {
                when ((b.getOrNull(p + 9)?.toInt() ?: 0) and 0x0f) {
                    1, 8 -> mode = EncodeMode.CBR
                    2, 9 -> mode = EncodeMode.ABR
                    3, 4, 5, 6, 7 -> mode = EncodeMode.VBR
                }
            }
            audioStart = first + h.length
        } else if (ascii(b, first + 36, 4) == "VBRI") {
            mode = EncodeMode.VBR
            frames = int32(b, first + 36 + 14).toLong() and 0xffffffffL
            audioStart = first + h.length
        }

        val audio = header(b, audioStart) ?: h
        if (mode == null) {
            // No info tag: look at a run of frames to tell CBR from untagged VBR.
            val seen = HashSet<Int>()
            var i = audioStart
            var n = 0
            while (n < 300) {
                val f = header(b, i) ?: break
                seen += f.kbps
                i += f.length
                n++
            }
            mode = if (seen.size > 1) EncodeMode.VBR else EncodeMode.CBR
        }

        val channels = when (audio.channelMode) {
            3 -> ChannelOut.MONO
            1 -> ChannelOut.JOINT
            else -> ChannelOut.STEREO
        }
        return ProbeResult(id3v2, id3v1, size, mode, audio.kbps, audio.rate, channels, frames, audio.samples)
    }

    /** First offset where two consecutive, consistent frame headers appear. */
    private fun firstFrame(b: ByteArray): Int? {
        for (i in 0 until b.size - 4) {
            val h = header(b, i) ?: continue
            val next = i + h.length
            if (next + 4 > b.size) return i
            val n = header(b, next) ?: continue
            if (n.rate == h.rate && n.mpeg1 == h.mpeg1) return i
        }
        return null
    }

    private fun header(b: ByteArray, i: Int): Header? {
        if (i < 0 || i + 4 > b.size) return null
        val b0 = b[i].toInt() and 0xff
        val b1 = b[i + 1].toInt() and 0xff
        val b2 = b[i + 2].toInt() and 0xff
        val b3 = b[i + 3].toInt() and 0xff
        if (b0 != 0xff || (b1 and 0xe0) != 0xe0) return null
        val version = (b1 shr 3) and 3
        if (version == 1) return null
        if ((b1 shr 1) and 3 != 1) return null // Layer III only
        val bitrateIndex = b2 shr 4
        if (bitrateIndex == 0 || bitrateIndex == 15) return null
        val rateIndex = (b2 shr 2) and 3
        if (rateIndex == 3) return null
        val mpeg1 = version == 3
        val rate = RATES[if (mpeg1) 0 else if (version == 2) 1 else 2][rateIndex]
        val kbps = if (mpeg1) KBPS_V1[bitrateIndex] else KBPS_V2[bitrateIndex]
        val samples = if (mpeg1) 1152 else 576
        val length = samples / 8 * kbps * 1000 / rate + ((b2 shr 1) and 1)
        return Header(mpeg1, kbps, rate, b3 shr 6, length, samples)
    }

    private fun readId3v2(ch: FileChannel, size: Long): ByteArray {
        if (size < 10) return ByteArray(0)
        val header = ByteBuffer.allocate(10)
        readFully(ch, header, 0)
        val h = header.array()
        if (h[0] != 'I'.code.toByte() || h[1] != 'D'.code.toByte() || h[2] != '3'.code.toByte()) return ByteArray(0)
        val body = ((h[6].toInt() and 0x7f) shl 21) or ((h[7].toInt() and 0x7f) shl 14) or
            ((h[8].toInt() and 0x7f) shl 7) or (h[9].toInt() and 0x7f)
        val footer = if (h[5].toInt() and 0x10 != 0) 10 else 0
        val total = 10L + body + footer
        if (total > size || total > 64L * 1024 * 1024) return ByteArray(0)
        val bb = ByteBuffer.allocate(total.toInt())
        readFully(ch, bb, 0)
        return bb.array()
    }

    private fun readId3v1(ch: FileChannel, size: Long, v2Size: Int): ByteArray {
        if (size < 128L + v2Size) return ByteArray(0)
        val bb = ByteBuffer.allocate(128)
        readFully(ch, bb, size - 128)
        val a = bb.array()
        return if (a[0] == 'T'.code.toByte() && a[1] == 'A'.code.toByte() && a[2] == 'G'.code.toByte()) a else ByteArray(0)
    }

    private fun readFully(ch: FileChannel, bb: ByteBuffer, pos: Long) {
        while (bb.hasRemaining()) {
            if (ch.read(bb, pos + bb.position()) <= 0) break
        }
    }

    private fun ascii(b: ByteArray, i: Int, n: Int): String =
        if (i < 0 || i + n > b.size) "" else String(b, i, n, Charsets.ISO_8859_1)

    private fun int32(b: ByteArray, i: Int): Int =
        if (i < 0 || i + 4 > b.size) 0
        else ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or
            ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
}
