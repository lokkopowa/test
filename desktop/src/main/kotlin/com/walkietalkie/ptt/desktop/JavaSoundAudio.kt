package com.walkietalkie.ptt.desktop

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Audio del PC con Java Sound (funziona su Windows, macOS e Linux senza librerie native). */
class JavaSoundAudio : AudioIO {

    private companion object {
        const val SAMPLE_RATE = 16_000f
        const val FRAME_BYTES = 640 // 20 ms
        const val PREBUFFER_BYTES = 8_000 // 250 ms: assorbe i ritardi della rete
        val FORMAT = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
        val END = ByteArray(0)
        val log: Logger = Logger.getLogger("JavaSoundAudio")
    }

    private var capture: AtomicBoolean? = null

    override fun startCapture(onPcm: (ByteArray, Int) -> Unit, onEnd: () -> Unit): Boolean {
        if (capture != null) return true
        val line: TargetDataLine = try {
            AudioSystem.getTargetDataLine(FORMAT).apply {
                open(FORMAT, FRAME_BYTES * 8)
                start()
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "Microfono non disponibile", e)
            return false
        }
        val active = AtomicBoolean(true)
        capture = active
        thread(name = "pc-capture", isDaemon = true) {
            val buf = ByteArray(FRAME_BYTES)
            try {
                while (active.get()) {
                    val n = line.read(buf, 0, buf.size)
                    if (n > 0) onPcm(buf, n)
                }
            } catch (e: Exception) {
                log.log(Level.WARNING, "Registrazione interrotta", e)
            } finally {
                runCatching { line.stop() }
                runCatching { line.close() }
                onEnd()
            }
        }
        return true
    }

    override fun stopCapture() {
        capture?.set(false)
        capture = null
    }

    /** Chiamato una volta se gli altoparlanti non si aprono (es. nessuna scheda audio). */
    var onSpeakerError: ((String) -> Unit)? = null

    override fun createPlayer(onFinished: () -> Unit): AudioIO.Player {
        val queue = LinkedBlockingQueue<ByteArray>()
        thread(name = "pc-play", isDaemon = true) {
            var line: SourceDataLine? = null
            try {
                // Pre-buffer: aspetta ~250 ms di audio (o la fine) prima di partire.
                val pending = ArrayList<ByteArray>()
                var buffered = 0
                var ended = false
                while (buffered < PREBUFFER_BYTES) {
                    val chunk = queue.poll(3, TimeUnit.SECONDS) ?: END
                    if (chunk === END) {
                        ended = true
                        break
                    }
                    pending += chunk
                    buffered += chunk.size
                }
                line = try {
                    AudioSystem.getSourceDataLine(FORMAT).apply {
                        open(FORMAT, 16_000)
                        start()
                    }
                } catch (e: Exception) {
                    // Senza altoparlanti si continua a "consumare" la trasmissione in silenzio,
                    // così l'indicatore "sta parlando" resta corretto.
                    log.log(Level.WARNING, "Altoparlanti non disponibili", e)
                    onSpeakerError?.invoke(e.message ?: "errore sconosciuto")
                    null
                }
                pending.forEach { line?.write(it, 0, it.size) }
                while (!ended) {
                    val chunk = queue.poll(3, TimeUnit.SECONDS) ?: break
                    if (chunk === END) break
                    line?.write(chunk, 0, chunk.size)
                }
                line?.drain()
            } catch (e: Exception) {
                log.log(Level.WARNING, "Riproduzione interrotta", e)
            } finally {
                runCatching { line?.close() }
                if (line != null) beep()
                onFinished()
            }
        }
        return object : AudioIO.Player {
            override fun write(pcm: ByteArray) {
                queue.put(pcm)
            }

            override fun end() {
                queue.put(END)
            }
        }
    }

    /** Sirena SOS: ~3 s di tono che sale e scende tra 650 e 1300 Hz. */
    override fun alarm() {
        thread(name = "pc-alarm", isDaemon = true) {
            runCatching {
                val seconds = 3.0
                val samples = (SAMPLE_RATE * seconds).toInt()
                val pcm = ByteArray(samples * 2)
                var phase = 0.0
                for (i in 0 until samples) {
                    val t = i / SAMPLE_RATE.toDouble()
                    val freq = 975 + 325 * sin(2 * PI * 1.2 * t)
                    phase += 2 * PI * freq / SAMPLE_RATE
                    val v = (9000 * sin(phase)).toInt()
                    pcm[2 * i] = v.toByte()
                    pcm[2 * i + 1] = (v shr 8).toByte()
                }
                AudioSystem.getSourceDataLine(FORMAT).use { line ->
                    line.open(FORMAT)
                    line.start()
                    line.write(pcm, 0, pcm.size)
                    line.drain()
                }
            }
        }
    }

    /** Il classico "bip" di fine trasmissione (120 ms a 880 Hz). */
    private fun beep() = runCatching {
        val samples = (SAMPLE_RATE * 0.12f).toInt()
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val fade = minOf(1.0, (samples - i) / 400.0)
            val v = (6000 * fade * sin(2 * PI * 880 * i / SAMPLE_RATE)).toInt()
            pcm[2 * i] = v.toByte()
            pcm[2 * i + 1] = (v shr 8).toByte()
        }
        AudioSystem.getSourceDataLine(FORMAT).use { line ->
            line.open(FORMAT)
            line.start()
            line.write(pcm, 0, pcm.size)
            line.drain()
        }
    }
}
