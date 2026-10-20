package com.walkietalkie.ptt.sound

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Preamplificazione della voce in arrivo (PCM 16 bit little endian) con limitatore morbido:
 * sotto la soglia il segnale viene solo moltiplicato, sopra viene "arrotondato" verso il massimo
 * invece di essere tagliato di netto (niente gracchiare quando si alza molto).
 */
object Preamp {

    const val MAX_DB = 15f
    private const val KNEE = 24_000.0
    private const val MAX = 32_767.0

    fun dbToGain(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    /** Applica [gain] ai primi [length] byte di [pcm], sul posto. */
    fun apply(pcm: ByteArray, length: Int, gain: Float) {
        if (gain == 1f) return
        var i = 0
        while (i + 1 < length) {
            val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            val v = limit(s * gain.toDouble()).toInt()
            pcm[i] = v.toByte()
            pcm[i + 1] = (v shr 8).toByte()
            i += 2
        }
    }

    fun limit(x: Double): Double {
        val a = abs(x)
        if (a <= KNEE) return x
        val soft = KNEE + (MAX - KNEE) * tanh((a - KNEE) / (MAX - KNEE))
        return if (x < 0) -soft else soft
    }

    /** Scala un suono (selettive, bip) al volume scelto, 0..1. */
    fun scale(pcm: ShortArray, volume: Float): ShortArray {
        val v = volume.coerceIn(0f, 1f)
        if (v == 1f) return pcm
        return ShortArray(pcm.size) { (pcm[it] * v).toInt().toShort() }
    }
}
