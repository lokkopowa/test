package com.walkietalkie.ptt.net

/**
 * Compressione della voce per la rete cellulare: PCM 16 kHz/16 bit (256 kbit/s)
 * -> G.711 µ-law a 8 kHz (64 kbit/s, qualità "telefono"). Semplice e senza ritardi.
 */
object VoiceCodec {

    private const val BIAS = 0x84
    private const val CLIP = 32635

    /** PCM 16 bit little-endian a 16 kHz -> µ-law a 8 kHz (1 byte ogni 4 in ingresso). */
    fun encode(pcm: ByteArray, length: Int = pcm.size): ByteArray {
        val samples = length / 2
        val out = ByteArray(samples / 2)
        for (i in out.indices) {
            val a = sample(pcm, 2 * i)
            val b = sample(pcm, 2 * i + 1)
            out[i] = linearToUlaw((a + b) / 2) // media di 2 campioni = filtro passa basso + dimezzamento
        }
        return out
    }

    /** µ-law a 8 kHz -> PCM 16 bit little-endian a 16 kHz (interpolazione lineare). */
    fun decode(ulaw: ByteArray): ByteArray {
        val out = ByteArray(ulaw.size * 4)
        for (i in ulaw.indices) {
            val cur = ulawToLinear(ulaw[i])
            val next = if (i + 1 < ulaw.size) ulawToLinear(ulaw[i + 1]) else cur
            put(out, 2 * i, cur)
            put(out, 2 * i + 1, (cur + next) / 2)
        }
        return out
    }

    private fun sample(pcm: ByteArray, index: Int): Int =
        ((pcm[2 * index + 1].toInt() shl 8) or (pcm[2 * index].toInt() and 0xFF)).toShort().toInt()

    private fun put(pcm: ByteArray, index: Int, value: Int) {
        pcm[2 * index] = value.toByte()
        pcm[2 * index + 1] = (value shr 8).toByte()
    }

    fun linearToUlaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun ulawToLinear(code: Byte): Int {
        val u = code.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return if (sign != 0) -magnitude else magnitude
    }
}
