package com.walkietalkie.ptt.sound

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreampTest {

    private fun pcmOf(vararg samples: Int) = ToneLibrary.toBytes(ShortArray(samples.size) { samples[it].toShort() })

    private fun samplesOf(pcm: ByteArray) = IntArray(pcm.size / 2) {
        ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF)).toShort().toInt()
    }

    @Test
    fun sixDecibelsDoublesQuietSpeech() {
        assertEquals(1.995f, Preamp.dbToGain(6f), 0.01f)
        val pcm = pcmOf(1000, -2000, 5000)
        Preamp.apply(pcm, pcm.size, Preamp.dbToGain(6f))
        val out = samplesOf(pcm)
        assertEquals(1995.0, out[0].toDouble(), 3.0)
        assertEquals(-3990.0, out[1].toDouble(), 5.0)
        assertEquals(9976.0, out[2].toDouble(), 10.0)
    }

    @Test
    fun zeroDecibelsLeavesAudioUntouched() {
        val pcm = pcmOf(1, -30000, 32767)
        val copy = pcm.copyOf()
        Preamp.apply(pcm, pcm.size, Preamp.dbToGain(0f))
        assertArrayEquals(copy, pcm)
    }

    @Test
    fun loudPeaksAreRoundedNotWrapped() {
        // Senza limitatore 20000 x 5,6 farebbe "giro" del 16 bit e diventerebbe un rumore fortissimo.
        val pcm = pcmOf(20000, -20000, 32767, -32768)
        Preamp.apply(pcm, pcm.size, Preamp.dbToGain(Preamp.MAX_DB))
        val out = samplesOf(pcm)
        assertTrue(out[0] in 24000..32767)
        assertTrue(out[1] in -32767..-24000)
        assertTrue(out[2] in 24000..32767)
        assertTrue(out[3] in -32767..-24000)
    }

    @Test
    fun limiterIsMonotonic() {
        var last = 0.0
        for (x in 0..200_000 step 500) {
            val y = Preamp.limit(x.toDouble())
            assertTrue(y >= last && y <= 32767.0)
            last = y
        }
    }

    @Test
    fun toneVolumeScales() {
        val tone = ToneLibrary.start(ToneLibrary.StartTone.ZVEI, "12345")
        val half = Preamp.scale(tone, 0.5f)
        val i = tone.indices.maxByOrNull { kotlin.math.abs(tone[it].toInt()) }!!
        assertEquals(tone[i] / 2.0, half[i].toDouble(), 1.0)
        assertTrue(Preamp.scale(tone, 0f).all { it.toInt() == 0 })
    }
}
