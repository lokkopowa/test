package com.walkietalkie.ptt

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Sirena + vibrazione quando arriva un SOS; si ferma da sola dopo qualche secondo. */
class AlarmPlayer(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null

    fun start(durationMs: Long = 10_000) = handler.post {
        stopNow()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull()?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            runCatching { play() }
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300, 600, 300, 1200), 0))
        handler.postDelayed({ stopNow() }, durationMs)
    }

    fun stop() = handler.post { stopNow() }

    private fun stopNow() {
        handler.removeCallbacksAndMessages(null)
        runCatching { ringtone?.stop() }
        ringtone = null
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
