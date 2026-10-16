package com.walkietalkie.ptt.sound

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.walkietalkie.ptt.sound.ToneLibrary.EndTone
import com.walkietalkie.ptt.sound.ToneLibrary.PttTone
import com.walkietalkie.ptt.sound.ToneLibrary.SosSound
import com.walkietalkie.ptt.sound.ToneLibrary.StartTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Le scelte del menu Impostazioni → Suoni, salvate sul telefono. */
data class Sounds(
    val start: StartTone = StartTone.ZVEI,
    val selectiveCode: String = "12345",
    val end: EndTone = EndTone.SQUELCH,
    val ptt: PttTone = PttTone.PERMIT,
    val sos: SosSound = SosSound.BITONAL,
    val sosCustomUri: String? = null,
    val sosCustomTitle: String? = null,
)

class SoundSettings(context: Context) {

    private val prefs = context.getSharedPreferences("sounds", Context.MODE_PRIVATE)
    private val _sounds = MutableStateFlow(load())
    val sounds: StateFlow<Sounds> = _sounds.asStateFlow()

    val current get() = _sounds.value

    fun update(change: (Sounds) -> Sounds) {
        val next = change(_sounds.value).let { it.copy(selectiveCode = ToneLibrary.normalizeCode(it.selectiveCode)) }
        _sounds.value = next
        prefs.edit {
            putString("start", next.start.name)
            putString("code", next.selectiveCode)
            putString("end", next.end.name)
            putString("ptt", next.ptt.name)
            putString("sos", next.sos.name)
            putString("sos_uri", next.sosCustomUri)
            putString("sos_title", next.sosCustomTitle)
        }
    }

    fun startPcm(): ByteArray = ToneLibrary.toBytes(ToneLibrary.start(current.start, current.selectiveCode))
    fun endPcm(): ByteArray = ToneLibrary.toBytes(ToneLibrary.end(current.end))
    fun pttPcm(): ByteArray = ToneLibrary.toBytes(ToneLibrary.ptt(current.ptt))
    val sosCustomUri: Uri? get() = current.sosCustomUri?.let(Uri::parse)

    private fun load(): Sounds {
        val d = Sounds()
        return Sounds(
            start = enumOr(prefs.getString("start", null), d.start),
            selectiveCode = prefs.getString("code", null) ?: d.selectiveCode,
            end = enumOr(prefs.getString("end", null), d.end),
            ptt = enumOr(prefs.getString("ptt", null), d.ptt),
            sos = enumOr(prefs.getString("sos", null), d.sos),
            sosCustomUri = prefs.getString("sos_uri", null),
            sosCustomTitle = prefs.getString("sos_title", null),
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
