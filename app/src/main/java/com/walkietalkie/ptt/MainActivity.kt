package com.walkietalkie.ptt

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkietalkie.ptt.ui.ChatScreen
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

        setContent {
            WalkieTheme {
                val started by vm.started.collectAsStateWithLifecycle()
                if (started) ChatScreen(vm) else SetupScreen(onStart = vm::start)
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
