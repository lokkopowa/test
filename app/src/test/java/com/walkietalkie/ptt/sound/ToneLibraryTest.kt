package com.walkietalkie.ptt.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ToneLibraryTest {

    /** Frequenza stimata contando gli attraversamenti dello zero. */
    private fun frequency(pcm: ShortArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if ((pcm[i - 1] < 0) != (pcm[i] < 0)) crossings++
        return crossings / 2.0 / ((to - from).toDouble() / ToneLibrary.SAMPLE_RATE)
    }

    private fun ms(n: Int) = n * 1000 / ToneLibrary.SAMPLE_RATE

    @Test
    fun zveiSelectiveHasFiveTonesWithStandardFrequencies() {
        val pcm = ToneLibrary.start(ToneLibrary.StartTone.ZVEI, "12345")
        assertEquals(30 + 5 * 70 + 80, ms(pcm.size))
        val start = ToneLibrary.samples(30)
        val per = ToneLibrary.samples(70)
        val expected = listOf(1060.0, 1160.0, 1270.0, 1400.0, 1530.0)
        expected.forEachIndexed { i, f ->
            // Si misura la parte centrale di ogni tono.
            val measured = frequency(pcm, start + i * per + per / 5, start + (i + 1) * per - per / 5)
            assertTrue("tono $i: atteso $f, misurato $measured", abs(measured - f) < f * 0.06)
        }
    }

    @Test
    fun repeatedDigitUsesRepeatTone() {
        val pcm = ToneLibrary.start(ToneLibrary.StartTone.CCIR, "11234")
        val start = ToneLibrary.samples(30)
        val per = ToneLibrary.samples(100)
        val second = frequency(pcm, start + per + per / 5, start + 2 * per - per / 5)
        assertTrue("misurato $second", abs(second - ToneLibrary.CCIR_REPEAT) < 120)
    }

    @Test
    fun codeIsNormalizedToFiveDigits() {
        assertEquals("12000", ToneLibrary.normalizeCode("12"))
        assertEquals("98765", ToneLibrary.normalizeCode("9a8-7 65 43"))
    }

    @Test
    fun everySoundIsShortAndNotClipped() {
        val all = ToneLibrary.StartTone.entries.map { ToneLibrary.start(it, "12345") } +
            ToneLibrary.EndTone.entries.map(ToneLibrary::end) +
            ToneLibrary.PttTone.entries.map(ToneLibrary::ptt)
        all.forEach { pcm ->
            assertTrue(ms(pcm.size) < 800) // non deve ritardare troppo la voce
            assertTrue(pcm.none { it == Short.MIN_VALUE })
        }
        assertEquals(0, ToneLibrary.start(ToneLibrary.StartTone.NONE, "12345").size)
    }

    @Test
    fun sirensLoopAndSystemSoundsAreNotSynthesized() {
        assertNotNull(ToneLibrary.siren(ToneLibrary.SosSound.BITONAL))
        assertNotNull(ToneLibrary.siren(ToneLibrary.SosSound.WAIL))
        assertNotNull(ToneLibrary.siren(ToneLibrary.SosSound.YELP))
        assertNull(ToneLibrary.siren(ToneLibrary.SosSound.SYSTEM))
        val bitonal = ToneLibrary.siren(ToneLibrary.SosSound.BITONAL)!!
        val half = bitonal.size / 2
        assertTrue(abs(frequency(bitonal, 0, half) - 660) < 60)
        assertTrue(abs(frequency(bitonal, half, bitonal.size) - 880) < 80)
    }
}
