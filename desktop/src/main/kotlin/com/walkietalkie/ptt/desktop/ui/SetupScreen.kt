package com.walkietalkie.ptt.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.walkietalkie.ptt.desktop.DesktopSettings
import java.net.InetAddress
import java.util.prefs.Preferences

/** Stesso server predefinito dell'app Android: devono coincidere per parlarsi. */
const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"

@Composable
fun SetupScreen(onStart: (DesktopSettings) -> Unit) {
    val prefs = remember { Preferences.userRoot().node("walkieptt") }
    val defaultNick = remember { runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC" }

    var nick by remember { mutableStateOf(prefs.get("nick", defaultNick)) }
    var channel by remember { mutableStateOf(prefs.get("channel", "1")) }
    var password by remember { mutableStateOf(prefs.get("password", "")) }
    var broker by remember { mutableStateOf(prefs.get("broker", DEFAULT_BROKER)) }
    var advanced by remember { mutableStateOf(false) }

    fun go() {
        prefs.put("nick", nick)
        prefs.put("channel", channel)
        prefs.put("password", password)
        prefs.put("broker", broker)
        runCatching { prefs.flush() }
        onStart(DesktopSettings(nick, channel, password, broker.ifBlank { DEFAULT_BROKER }))
    }

    Surface(Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text("Walkie PTT per PC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Parla, scrivi e manda foto ai telefoni con l'app Walkie PTT (collegamento Internet attivo).",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it.take(20) },
                    label = { Text("Il tuo nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.take(30) },
                    label = { Text("Canale") },
                    supportingText = { Text("Lo stesso impostato sui telefoni") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(64) },
                    label = { Text("Password del canale") },
                    supportingText = { Text("La stessa dei telefoni (anche vuota)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Nascondi opzioni avanzate" else "Opzioni avanzate")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = broker,
                        onValueChange = { broker = it.trim() },
                        label = { Text("Server MQTT") },
                        supportingText = { Text("Lo stesso dei telefoni. Predefinito: $DEFAULT_BROKER") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = ::go,
                    enabled = nick.isNotBlank() && channel.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Entra nel canale") }
            }
        }
    }
}
