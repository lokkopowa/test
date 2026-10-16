package com.walkietalkie.ptt.sound

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/** Riproduce un suono breve "una tantum" (conferma PTT, anteprime nel menu). */
object TonePlayer {

    @Volatile
    private var current: AudioTrack? = null

    fun play(pcm: ByteArray, usage: Int = AudioAttributes.USAGE_MEDIA) {
        if (pcm.isEmpty()) return
        thread(name = "tone") {
            stop()
            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(usage)
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
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
            }.getOrNull() ?: return@thread
            current = track
            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(pcm.size / 2 * 1000L / ToneLibrary.SAMPLE_RATE + 50)
            if (current === track) current = null
            runCatching { track.stop() }
            track.release()
        }
    }

    fun stop() {
        current?.let { runCatching { it.stop() } }
        current = null
    }
}
