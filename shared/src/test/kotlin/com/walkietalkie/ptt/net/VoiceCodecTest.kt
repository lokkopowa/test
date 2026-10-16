package com.walkietalkie.ptt.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceCodecTest {

    @Test
    fun ulawRoundTripIsClose() {
        for (v in listOf(0, 1, 100, 1000, -1000, 8000, -8000, 32000, -32000)) {
            val back = VoiceCodec.ulawToLinear(VoiceCodec.linearToUlaw(v))
            // µ-law ha un errore relativo di qualche percento
            assertTrue("$v -> $back", abs(back - v) <= maxOf(16, abs(v) / 16))
        }
    }

    @Test
    fun encodeQuartersSizeAndDecodeRestoresIt() {
        // 20 ms di un tono a 440 Hz, 16 kHz, 16 bit LE
        val samples = 320
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (10_000 * sin(2 * PI * 440 * i / 16_000)).toInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
        val ulaw = VoiceCodec.encode(pcm)
        assertEquals(pcm.size / 4, ulaw.size)
        val back = VoiceCodec.decode(ulaw)
        assertEquals(pcm.size, back.size)

        var err = 0.0
        for (i in 0 until samples) {
            val a = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
            val b = ((back[2 * i + 1].toInt() shl 8) or (back[2 * i].toInt() and 0xFF)).toShort().toInt()
            err += abs(a - b)
        }
        assertTrue("errore medio troppo alto: ${err / samples}", err / samples < 800)
    }
}
