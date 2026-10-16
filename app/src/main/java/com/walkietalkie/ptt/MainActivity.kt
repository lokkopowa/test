package com.walkietalkie.ptt

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkietalkie.ptt.ui.AboutDialog
import com.walkietalkie.ptt.ui.ChatScreen
import com.walkietalkie.ptt.ui.SettingsScreen
import com.walkietalkie.ptt.ui.SetupScreen
import com.walkietalkie.ptt.ui.WalkieTheme

class MainActivity : ComponentActivity() {

    private val app get() = application as WalkieApp
    private val vm get() = app.session

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Come un vero walkie talkie: lo schermo resta acceso mentre l'app è in primo piano.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("setup", MODE_PRIVATE)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

        setContent {
            WalkieTheme {
                val started by vm.started.collectAsStateWithLifecycle()
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                // Il disclaimer compare da solo al primo avvio; poi dal pulsante "Informazioni".
                var aboutOpen by rememberSaveable { mutableStateOf(!prefs.getBoolean("about_seen", false)) }

                when {
                    settingsOpen -> SettingsScreen(vm.sounds, onBack = { settingsOpen = false })
                    started -> ChatScreen(vm, onOpenSettings = { settingsOpen = true })
                    else -> SetupScreen(
                        onStart = vm::start,
                        onOpenSettings = { settingsOpen = true },
                        onOpenAbout = { aboutOpen = true },
                    )
                }
                if (aboutOpen) {
                    AboutDialog(version) {
                        aboutOpen = false
                        prefs.edit { putBoolean("about_seen", true) }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.uiVisible = true
    }

    // Con l'app ridotta la sessione continua (WalkieService): voce, messaggi e PTT dalla notifica.
    override fun onStop() {
        super.onStop()
        app.uiVisible = false
    }
}
