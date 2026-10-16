package com.walkietalkie.ptt.sound

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Suoni "da radio" generati al volo (PCM 16 bit mono 16 kHz, little endian): nessun file audio,
 * frequenze e tempi come quelli degli standard reali (selettive ZVEI/CCIR, roger beep, squelch).
 */
object ToneLibrary {

    const val SAMPLE_RATE = 16_000

    // ---------------------------------------------------------------- suoni selezionabili

    /** Suono all'inizio di una trasmissione in arrivo. */
    enum class StartTone(val label: String, val description: String) {
        ZVEI("Selettiva 5 toni ZVEI", "La classica \"bidibidibip\" delle radio di polizia e 118"),
        CCIR("Selettiva 5 toni CCIR", "Selettiva europea, toni un po' più lunghi"),
        MOTOROLA("Chirp stile Motorola", "Tre note veloci ascendenti"),
        TETRA("Doppio bip stile TETRA", "Due bip brevi, come le radio digitali"),
        NONE("Nessuno", "La voce parte subito"),
    }

    /** Suono alla fine di una trasmissione in arrivo. */
    enum class EndTone(val label: String, val description: String) {
        SQUELCH("Fruscio di squelch", "Il \"kssh\" delle radio quando l'altro molla il tasto"),
        BEEP("Bip classico", "Roger beep a 1000 Hz"),
        ROGER("Roger a due toni", "Due note ascendenti, stile radioamatori"),
        NONE("Nessuno", ""),
    }

    /** Suono di conferma quando si preme il PTT (il "talk permit" delle radio). */
    enum class PttTone(val label: String, val description: String) {
        PERMIT("Talk permit", "Tre bip rapidi: puoi parlare"),
        CLICK("Clic", "Un clic breve e discreto"),
        NONE("Nessuno", ""),
    }

    /** Suoneria dell'SOS ricevuto. */
    enum class SosSound(val label: String, val description: String) {
        BITONAL("Sirena bitonale", "Due toni alternati, stile sirena italiana"),
        WAIL("Sirena ululato (wail)", "Sale e scende lentamente"),
        YELP("Sirena yelp", "Rapida e insistente"),
        SYSTEM("Allarme del telefono", "La suoneria di sveglia impostata nel telefono"),
        CUSTOM("Suoneria a scelta…", "Scegli una suoneria tra quelle del telefono"),
    }

    fun start(tone: StartTone, code: String): ShortArray = when (tone) {
        StartTone.ZVEI -> selective(code, ZVEI, ZVEI_REPEAT, toneMs = 70)
        StartTone.CCIR -> selective(code, CCIR, CCIR_REPEAT, toneMs = 100)
        StartTone.MOTOROLA -> concat(beep(1200.0, 35), silence(15), beep(1450.0, 35), silence(15), beep(1700.0, 60), silence(60))
        StartTone.TETRA -> concat(beep(1150.0, 60), silence(50), beep(1150.0, 60), silence(60))
        StartTone.NONE -> ShortArray(0)
    }

    fun end(tone: EndTone): ShortArray = when (tone) {
        EndTone.SQUELCH -> concat(silence(30), squelchTail(220))
        EndTone.BEEP -> concat(silence(40), beep(1000.0, 150))
        EndTone.ROGER -> concat(silence(40), beep(800.0, 70), beep(1250.0, 110))
        EndTone.NONE -> ShortArray(0)
    }

    fun ptt(tone: PttTone): ShortArray = when (tone) {
        PttTone.PERMIT -> concat(beep(1400.0, 30), silence(25), beep(1400.0, 30), silence(25), beep(1400.0, 30))
        PttTone.CLICK -> concat(beep(2000.0, 12, amplitude = 0.35))
        PttTone.NONE -> ShortArray(0)
    }

    /** Un ciclo di sirena (da ripetere in loop); null per le suonerie di sistema. */
    fun siren(sound: SosSound): ShortArray? = when (sound) {
        SosSound.BITONAL -> concat(sirenTone(660.0, 550), sirenTone(880.0, 550))
        SosSound.WAIL -> sweep(650.0, 1350.0, 2000) + sweep(1350.0, 650.0, 2000)
        SosSound.YELP -> sweep(700.0, 1500.0, 280)
        SosSound.SYSTEM, SosSound.CUSTOM -> null
    }

    // ---------------------------------------------------------------- selettive a 5 toni

    /** Frequenze delle cifre 0-9 nello standard ZVEI-1 (70 ms per tono). */
    val ZVEI = doubleArrayOf(2400.0, 1060.0, 1160.0, 1270.0, 1400.0, 1530.0, 1670.0, 1830.0, 2000.0, 2200.0)
    const val ZVEI_REPEAT = 2600.0

    /** Frequenze delle cifre 0-9 nello standard CCIR (100 ms per tono). */
    val CCIR = doubleArrayOf(1981.0, 1124.0, 1197.0, 1275.0, 1358.0, 1446.0, 1540.0, 1640.0, 1747.0, 1860.0)
    const val CCIR_REPEAT = 2110.0

    /** Codice selettiva valido: 5 cifre (quelle mancanti diventano 0). */
    fun normalizeCode(code: String): String = code.filter(Char::isDigit).take(5).padEnd(5, '0')

    /**
     * Sequenza a 5 toni. Come negli standard reali, una cifra uguale alla precedente viene
     * sostituita dal "tono di ripetizione", altrimenti i due toni si fonderebbero in uno.
     */
    fun selective(code: String, table: DoubleArray, repeat: Double, toneMs: Int): ShortArray {
        val digits = normalizeCode(code).map { it - '0' }
        val freqs = digits.mapIndexed { i, d -> if (i > 0 && d == digits[i - 1]) repeat else table[d] }
        // Toni contigui senza pause e con fase continua, come un vero encoder.
        return concat(silence(30), continuousTones(freqs, toneMs), silence(80))
    }

    // ---------------------------------------------------------------- mattoncini

    fun silence(ms: Int) = ShortArray(samples(ms))

    fun beep(freq: Double, ms: Int, amplitude: Double = 0.55): ShortArray {
        val n = samples(ms)
        val fade = samples(4)
        return ShortArray(n) { i ->
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (amplitude * env * Short.MAX_VALUE * sin(2 * PI * freq * i / SAMPLE_RATE)).toInt().toShort()
        }
    }

    private fun continuousTones(freqs: List<Double>, toneMs: Int, amplitude: Double = 0.5): ShortArray {
        val per = samples(toneMs)
        val n = per * freqs.size
        val fade = samples(3)
        var phase = 0.0
        return ShortArray(n) { i ->
            phase += 2 * PI * freqs[i / per] / SAMPLE_RATE
            val env = min(1.0, min(i, n - 1 - i).toDouble() / fade)
            (amplitude * env * Short.MAX_VALUE * sin(phase)).toInt().toShort()
        }
    }

    /** Fruscio bianco che si spegne: la "coda di squelch" delle radio FM. */
    private fun squelchTail(ms: Int): ShortArray {
        val n = samples(ms)
        val rnd = Random(42)
        var last = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / n
            val env = if (t < 0.05) t / 0.05 else exp(-4.0 * (t - 0.05))
            last = 0.6 * last + 0.4 * (rnd.nextDouble() * 2 - 1) // leggermente filtrato, meno stridente
            (0.5 * env * Short.MAX_VALUE * last).toInt().toShort()
        }
    }

    /** Tono di sirena con un po' di armoniche (timbro da tromba, non da sinusoide). */
    private fun sirenTone(freq: Double, ms: Int): ShortArray {
        val n = samples(ms)
        return ShortArray(n) { i ->
            val x = 2 * PI * freq * i / SAMPLE_RATE
            val v = sin(x) + 0.35 * sin(3 * x) + 0.15 * sin(5 * x)
            (0.4 * Short.MAX_VALUE * v).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun sweep(from: Double, to: Double, ms: Int): ShortArray {
        val n = samples(ms)
        var phase = 0.0
        return ShortArray(n) { i ->
            val f = from + (to - from) * i / n
            phase += 2 * PI * f / SAMPLE_RATE
            val v = sin(phase) + 0.35 * sin(3 * phase) + 0.15 * sin(5 * phase)
            (0.4 * Short.MAX_VALUE * v).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    fun samples(ms: Int) = SAMPLE_RATE * ms / 1000

    /** PCM 16 bit little endian, il formato che scrive l'AudioEngine. */
    fun toBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            out[2 * i] = pcm[i].toInt().toByte()
            out[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        return out
    }
}
