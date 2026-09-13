package com.tdeletto.mp3bulk.encoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {

    private val stereo320 = SourceInfo(EncodeMode.CBR, 320, 44_100, ChannelOut.JOINT, 60_000_000)
    private val mono64 = SourceInfo(EncodeMode.CBR, 64, 44_100, ChannelOut.MONO, 60_000_000)
    private val lowMono = SourceInfo(EncodeMode.CBR, 32, 22_050, ChannelOut.MONO, 60_000_000)
    private val vbr190 = SourceInfo(EncodeMode.VBR, 190, 44_100, ChannelOut.JOINT, 60_000_000)

    @Test fun defaultsLeaveEveryFileUnchanged() {
        listOf(stereo320, mono64, lowMono, vbr190).forEach { assertNull(planFor(EncodeSettings(), it)) }
    }

    @Test fun podcastPresetOnStereo320() {
        val p = planFor(Preset.PODCAST.settings!!, stereo320)!!
        assertEquals(EncodeMode.VBR, p.mode)
        assertEquals(64, p.kbps)
        assertEquals(44_100, p.sampleRate)
        assertEquals(ChannelOut.MONO, p.channels)
        assertTrue(p.highPass && p.lowPass)
        assertFalse(p.keptSourceBitrate)
    }

    @Test fun hqMusicPresetOnStereo320() {
        val p = planFor(Preset.HQ_MUSIC.settings!!, stereo320)!!
        assertEquals(EncodeMode.VBR, p.mode)
        assertEquals(V0_KBPS, p.kbps)
        assertEquals(ChannelOut.STEREO, p.channels)
        assertFalse(p.highPass || p.lowPass)
    }

    @Test fun lowerBitrateSourceKeepsItsBitrate() {
        val p = planFor(EncodeSettings(kbps = 128, channels = ChannelOut.MONO), vbr190.copy(kbps = 96))!!
        assertEquals(96, p.kbps)
        assertTrue(p.keptSourceBitrate)
    }

    @Test fun bitrateIsNeverRaisedEvenWhenOtherSettingsChange() {
        val p = planFor(Preset.HQ_MUSIC.settings!!, vbr190)!! // mode same, channels change stereo
        assertEquals(190, p.kbps)
        assertTrue(p.keptSourceBitrate)
    }

    @Test fun onlyBitrateLowerWithNothingElseIsSkipped() {
        assertNull(planFor(EncodeSettings(kbps = 128), mono64))
    }

    @Test fun monoNeverBecomesStereo() {
        val p = planFor(EncodeSettings(kbps = 32, channels = ChannelOut.STEREO), mono64)!!
        assertEquals(ChannelOut.MONO, p.channels)
    }

    @Test fun neverUpsamples() {
        assertNull(planFor(EncodeSettings(sampleRate = 44_100), lowMono))
        val p = planFor(EncodeSettings(sampleRate = 48_000, kbps = 16), lowMono.copy(kbps = 64))
        assertEquals(22_050, p!!.sampleRate)
    }

    @Test fun keepModeUsesSourceMode() {
        val p = planFor(EncodeSettings(kbps = 128), vbr190)!!
        assertEquals(EncodeMode.VBR, p.mode)
        assertEquals(128, p.kbps)
    }

    @Test fun cbrSnapsToLegalBitrateForSampleRate() {
        // MPEG-2 (22.05 kHz) tops out at 160 kbps.
        val p = planFor(EncodeSettings(EncodeMode.CBR, 320, 22_050), stereo320)!!
        assertEquals(22_050, p.sampleRate)
        assertEquals(160, p.kbps)
        // MPEG-2.5 (8 kHz) tops out at 64 kbps.
        assertEquals(64, planFor(EncodeSettings(EncodeMode.CBR, 128, 8_000), stereo320)!!.kbps)
    }

    @Test fun filtersAloneCountAsAChange() {
        val p = planFor(EncodeSettings(highPass = true), mono64)!!
        assertEquals(64, p.kbps)
        assertEquals(EncodeMode.CBR, p.mode)
    }

    @Test fun presetMatching() {
        assertEquals(Preset.ORIGINAL, Preset.matching(EncodeSettings()))
        assertEquals(Preset.PODCAST, Preset.matching(Preset.PODCAST.settings!!))
        assertEquals(Preset.CUSTOM, Preset.matching(Preset.PODCAST.settings!!.copy(kbps = 96)))
    }
}
