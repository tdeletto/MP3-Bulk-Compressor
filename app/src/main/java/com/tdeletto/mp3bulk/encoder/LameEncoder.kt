package com.tdeletto.mp3bulk.encoder

import java.io.Closeable

/** Thin wrapper over a native LAME encoder instance. Not thread-safe; use one per file. */
class LameEncoder(inSampleRate: Int, val channels: Int, plan: FilePlan) : Closeable {

    private var handle: Long = nativeInit(
        inSampleRate, channels, plan.sampleRate, plan.mode.ordinal, plan.kbps,
        plan.channels.ordinal, plan.highPass, plan.lowPass,
    )

    init {
        check(handle != 0L) { "Encoder rejected settings (${plan.summary()})" }
    }

    val outSampleRate: Int get() = nativeOutSampleRate(handle)

    /** @param pcm interleaved 16-bit samples, [frames] samples per channel. */
    fun encode(pcm: ShortArray, frames: Int, out: ByteArray): Int {
        val n = nativeEncode(handle, pcm, frames, out)
        check(n >= 0) { "LAME encode error $n" }
        return n
    }

    fun flush(out: ByteArray): Int = nativeFlush(handle, out).also { check(it >= 0) { "LAME flush error $it" } }

    /** Xing/LAME info frame for VBR/ABR streams; 0 bytes for CBR. */
    fun tagFrame(out: ByteArray): Int = nativeTagFrame(handle, out)

    override fun close() {
        nativeClose(handle)
        handle = 0
    }

    companion object {
        init {
            System.loadLibrary("lamejni")
        }

        /** Worst-case MP3 output buffer size for [frames] input frames. */
        fun outBufferSize(frames: Int) = (1.25 * frames + 7200).toInt()

        @JvmStatic private external fun nativeInit(
            inRate: Int, channels: Int, outRate: Int, mode: Int, kbps: Int,
            channelMode: Int, highpass: Boolean, lowpass: Boolean,
        ): Long
        @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, out: ByteArray): Int
        @JvmStatic private external fun nativeFlush(handle: Long, out: ByteArray): Int
        @JvmStatic private external fun nativeTagFrame(handle: Long, out: ByteArray): Int
        @JvmStatic private external fun nativeOutSampleRate(handle: Long): Int
        @JvmStatic private external fun nativeClose(handle: Long)
    }
}
