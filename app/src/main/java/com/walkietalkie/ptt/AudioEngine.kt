package com.walkietalkie.ptt

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Cattura e riproduzione audio PCM 16 bit mono a 16 kHz.
 * Il flusso viaggia "grezzo" dentro un payload STREAM di Nearby (~32 KB/s).
 */
class AudioEngine {

    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 16_000
        private const val FRAME_BYTES = SAMPLE_RATE / 50 * 2 // 20 ms
    }

    /** Flag della sessione di registrazione corrente (una nuova per ogni pressione del PTT). */
    private var session: AtomicBoolean? = null

    /**
     * Inizia a registrare dal microfono: ogni 20 ms passa il PCM a [onFrame] finché non si chiama
     * [stopRecording]; alla fine chiama [onEnd]. Le callback girano sul thread di registrazione.
     */
    @SuppressLint("MissingPermission") // il permesso viene chiesto prima di avviare la sessione
    fun startRecording(onFrame: (ByteArray, Int) -> Unit, onEnd: () -> Unit) {
        if (session != null) return
        val active = AtomicBoolean(true)
        session = active
        thread(name = "ptt-record") {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_BYTES * 4),
            )
            val audioSession = recorder.audioSessionId
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(audioSession)?.enabled = true
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(audioSession)?.enabled = true

            val buf = ByteArray(FRAME_BYTES)
            try {
                recorder.startRecording()
                while (active.get()) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n > 0) onFrame(buf, n)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Registrazione interrotta", e)
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                onEnd()
            }
        }
    }

    fun stopRecording() {
        session?.set(false)
        session = null
    }

    /**
     * Riproduce un flusso PCM in arrivo finché il mittente non rilascia il tasto.
     * [prebufferMs] accumula un po' di audio prima di partire, per assorbire i ritardi della rete.
     */
    fun play(
        input: InputStream,
        prebufferMs: Int = 0,
        startTone: ByteArray = ByteArray(0),
        endTone: ByteArray = ByteArray(0),
        onFinished: () -> Unit,
    ) {
        thread(name = "ptt-play") {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA) // altoparlante, come un walkie talkie
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val buf = ByteArray(FRAME_BYTES)
            try {
                val prebuffer = ByteArray(SAMPLE_RATE * 2 * prebufferMs / 1000)
                var filled = 0
                while (filled < prebuffer.size) {
                    val n = input.read(prebuffer, filled, prebuffer.size - filled)
                    if (n < 0) break
                    filled += n
                }
                track.play()
                var written = 0L
                // La selettiva suona prima della voce, nella stessa traccia: l'ordine è garantito.
                if (startTone.isNotEmpty()) written += track.write(startTone, 0, startTone.size)
                if (filled > 0) written += track.write(prebuffer, 0, filled)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) written += track.write(buf, 0, n)
                }
                if (endTone.isNotEmpty()) written += track.write(endTone, 0, endTone.size)
                // Aspetta che tutto sia uscito dall'altoparlante prima di chiudere la traccia.
                val frames = written / 2
                val deadline = System.currentTimeMillis() + 3_000
                while (track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Riproduzione interrotta", e)
            } finally {
                runCatching { track.stop() }
                track.release()
                runCatching { input.close() }
                onFinished()
            }
        }
    }
}
