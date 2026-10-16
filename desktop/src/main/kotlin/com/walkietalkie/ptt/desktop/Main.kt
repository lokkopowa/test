package com.walkietalkie.ptt.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.walkietalkie.ptt.desktop.ui.ChatScreen
import com.walkietalkie.ptt.desktop.ui.SetupScreen
import com.walkietalkie.ptt.desktop.ui.WalkieTheme
import java.io.File
import java.nio.file.Files

fun main() = application {
    val client = remember {
        val audio = JavaSoundAudio()
        DesktopClient(audio, Files.createTempDirectory("walkieptt-photos").toFile()).also { c ->
            audio.onSpeakerError = c::audioProblem
        }
    }
    val started by client.started.collectAsState()
    // Il tasto Spazio fa da PTT, tranne quando si sta scrivendo un messaggio.
    var typing by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            client.stop()
            exitApplication()
        },
        title = "Walkie PTT",
        state = rememberWindowState(width = 460.dp, height = 760.dp),
        onPreviewKeyEvent = { event ->
            if (!started || typing || event.key != Key.Spacebar) return@Window false
            when (event.type) {
                KeyEventType.KeyDown -> client.startTalking()
                KeyEventType.KeyUp -> client.stopTalking()
            }
            true
        },
    ) {
        WalkieTheme {
            if (started) {
                ChatScreen(client, onTypingChange = { typing = it })
            } else {
                SetupScreen(onStart = client::start)
            }
        }
    }
}

/** Apre un file con il programma predefinito del sistema (es. il visualizzatore di foto). */
fun openWithSystem(file: File) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(file)
    }
}
