package com.walkietalkie.ptt

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.walkietalkie.ptt.sound.SoundSettings
import com.walkietalkie.ptt.sound.ToneLibrary
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Suoneria + vibrazione quando arriva un SOS; si ferma da sola dopo qualche secondo.
 * La suoneria è quella scelta in Impostazioni → Suoni (sirena sintetizzata o suoneria del telefono).
 */
class AlarmPlayer(private val context: Context, private val sounds: SoundSettings) {

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var sirenActive: AtomicBoolean? = null

    fun start(durationMs: Long = 10_000) = handler.post {
        stopNow()
        val pcm = ToneLibrary.siren(sounds.current.sos)
        if (pcm != null) {
            playSiren(ToneLibrary.toBytes(pcm))
        } else {
            val uri = (if (sounds.current.sos == ToneLibrary.SosSound.CUSTOM) sounds.sosCustomUri else null)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            playRingtone(uri)
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 1200), 0))
        handler.postDelayed({ stopNow() }, durationMs)
    }

    fun stop() = handler.post { stopNow() }

    private fun playRingtone(uri: Uri) {
        ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            runCatching { play() }
        }
    }

    /** Sirena in loop sul canale "sveglia": si sente anche con il volume multimediale basso. */
    private fun playSiren(cycle: ByteArray) {
        val active = AtomicBoolean(true)
        sirenActive = active
        thread(name = "sos-siren") {
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(ToneLibrary.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(ToneLibrary.SAMPLE_RATE) // 0,5 s
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@thread
            try {
                track.play()
                while (active.get()) {
                    var pos = 0
                    while (pos < cycle.size && active.get()) {
                        val n = track.write(cycle, pos, minOf(3200, cycle.size - pos))
                        if (n <= 0) break
                        pos += n
                    }
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                track.release()
            }
        }
    }

    private fun stopNow() {
        handler.removeCallbacksAndMessages(null)
        runCatching { ringtone?.stop() }
        ringtone = null
        sirenActive?.set(false)
        sirenActive = null
        vibrator()?.cancel()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
}
