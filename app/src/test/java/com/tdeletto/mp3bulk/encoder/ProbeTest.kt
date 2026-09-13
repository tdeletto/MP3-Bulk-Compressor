package com.tdeletto.mp3bulk.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/** Fixtures are made with the lame CLI and trimmed to their first 64 KB. */
class ProbeTest {

    private fun probe(name: String): ProbeResult {
        val url = javaClass.classLoader!!.getResource("probe/$name") ?: error("missing fixture $name")
        return FileInputStream(File(url.toURI())).channel.use { Mp3Probe.probe(it) }
    }

    @Test fun cbr320WithId3() {
        val r = probe("cbr320_stereo_id3.mp3")
        assertTrue(r.id3v2.isNotEmpty())
        assertEquals(EncodeMode.CBR, r.mode)
        assertEquals(320, r.headerKbps)
        assertEquals(44_100, r.sampleRate)
        assertEquals(ChannelOut.JOINT, r.channels) // lame's default mode
    }

    @Test fun cbr192FullStereo() {
        val r = probe("cbr192_full_stereo.mp3")
        assertEquals(EncodeMode.CBR, r.mode)
        assertEquals(192, r.headerKbps)
        assertEquals(ChannelOut.STEREO, r.channels)
    }

    @Test fun cbr128Joint() {
        val r = probe("cbr128_joint.mp3")
        assertEquals(EncodeMode.CBR, r.mode)
        assertEquals(128, r.headerKbps)
        assertEquals(ChannelOut.JOINT, r.channels)
    }

    @Test fun vbrHasFrameCount() {
        val r = probe("vbr_v2_stereo.mp3")
        assertEquals(EncodeMode.VBR, r.mode)
        assertTrue(r.frameCount > 0)
        // 30 s at 44.1 kHz ≈ 1149 frames.
        assertEquals(30.0, r.trustedDurationUs / 1e6, 0.2)
    }

    @Test fun abrDetectedFromLameTag() {
        val r = probe("abr160_48k.mp3")
        assertEquals(EncodeMode.ABR, r.mode)
        assertEquals(48_000, r.sampleRate)
    }

    @Test fun monoCbr() {
        val r = probe("cbr64_mono.mp3")
        assertEquals(EncodeMode.CBR, r.mode)
        assertEquals(64, r.headerKbps)
        assertEquals(ChannelOut.MONO, r.channels)
    }

    @Test fun mpeg2LowRate() {
        val r = probe("cbr32_mono_22k.mp3")
        assertEquals(22_050, r.sampleRate)
        assertEquals(32, r.headerKbps)
        assertEquals(576, r.samplesPerFrame)
        assertEquals(20.0, r.trustedDurationUs / 1e6, 0.2)
    }

    @Test fun garbageIsRejected() {
        assertNull(probe("garbage.mp3").mode)
    }
}
