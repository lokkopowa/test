package com.walkietalkie.ptt.hw

import android.content.Context
import android.view.KeyEvent
import androidx.core.content.edit
import com.walkietalkie.ptt.WalkieApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tasto fisico usato come PTT (tasto laterale programmabile dei rugged phone, tasti volume…).
 * Gli eventi arrivano dalla schermata dell'app (MainActivity) e, con l'app ridotta o lo schermo
 * spento, dal servizio di accessibilità [PttKeyService].
 */
object HardwarePtt {

    data class Config(val keyCode: Int = KeyEvent.KEYCODE_UNKNOWN, val toggle: Boolean = false) {
        val assigned get() = keyCode != KeyEvent.KEYCODE_UNKNOWN
    }

    private const val PREFS = "hardware_ptt"
    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config.asStateFlow()
    private var loaded = false

    /** true mentre il servizio di accessibilità è attivo (tasto disponibile a schermo spento). */
    val accessibilityActive = MutableStateFlow(false)

    /** In attesa che l'utente prema il tasto da assegnare (dal menu Impostazioni). */
    @Volatile
    var learning: ((Int) -> Unit)? = null

    /** Tasti che non si possono assegnare: servono al sistema. */
    private val reserved = setOf(
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_UNKNOWN,
    )

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = Config(p.getInt("key", KeyEvent.KEYCODE_UNKNOWN), p.getBoolean("toggle", false))
    }

    fun update(context: Context, change: (Config) -> Config) {
        load(context)
        val next = change(_config.value)
        _config.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt("key", next.keyCode)
            putBoolean("toggle", next.toggle)
        }
    }

    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_UNKNOWN -> "nessuno"
        KeyEvent.KEYCODE_VOLUME_UP -> "Volume +"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume −"
        KeyEvent.KEYCODE_CAMERA -> "Fotocamera"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase()
            .replaceFirstChar { it.uppercase() } + " (codice $code)"
    }

    /** Gestisce un tasto; true se è stato usato (e non deve arrivare ad altre app). */
    fun onKey(context: Context, event: KeyEvent): Boolean {
        load(context)
        learning?.let { done ->
            if (event.keyCode in reserved) return false
            if (event.action == KeyEvent.ACTION_UP) {
                learning = null
                update(context) { it.copy(keyCode = event.keyCode) }
                done(event.keyCode)
            }
            return true
        }
        val cfg = _config.value
        if (!cfg.assigned || event.keyCode != cfg.keyCode) return false
        val session = (context.applicationContext as WalkieApp).session
        // Fuori da un canale il tasto torna a fare il suo lavoro normale (es. alzare il volume).
        if (!session.started.value) return false
        if (cfg.toggle) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) session.toggleTalking()
        } else {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> session.startTalking()
                event.action == KeyEvent.ACTION_UP -> session.stopTalking()
            }
        }
        return true
    }
}
