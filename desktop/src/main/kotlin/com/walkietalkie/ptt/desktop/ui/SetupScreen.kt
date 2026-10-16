package com.walkietalkie.ptt.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkietalkie.ptt.About
import com.walkietalkie.ptt.desktop.DesktopSettings
import java.net.InetAddress
import java.util.prefs.Preferences

/** Stesso server predefinito dell'app Android: devono coincidere per parlarsi. */
const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"

@Composable
fun SetupScreen(version: String, onStart: (DesktopSettings) -> Unit) {
    val prefs = remember { Preferences.userRoot().node("walkieptt") }
    val defaultNick = remember { runCatching { InetAddress.getLocalHost().hostName }.getOrNull() ?: "PC" }

    var nick by remember { mutableStateOf(prefs.get("nick", defaultNick)) }
    var channel by remember { mutableStateOf(prefs.get("channel", "1")) }
    var password by remember { mutableStateOf(prefs.get("password", "")) }
    var broker by remember { mutableStateOf(prefs.get("broker", DEFAULT_BROKER)) }
    var advanced by remember { mutableStateOf(false) }
    // Il disclaimer compare da solo al primo avvio; poi dal pulsante "Informazioni".
    var aboutOpen by remember { mutableStateOf(!prefs.getBoolean("about_seen", false)) }

    fun go() {
        prefs.put("nick", nick)
        prefs.put("channel", channel)
        prefs.put("password", password)
        prefs.put("broker", broker)
        runCatching { prefs.flush() }
        onStart(DesktopSettings(nick, channel, password, broker.ifBlank { DEFAULT_BROKER }))
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = WalkieColors.Orange,
        unfocusedBorderColor = WalkieColors.Stroke,
        focusedLabelColor = WalkieColors.Orange,
        unfocusedLabelColor = WalkieColors.Muted,
        cursorColor = WalkieColors.Orange,
        focusedTextColor = WalkieColors.Text,
        unfocusedTextColor = WalkieColors.Text,
        focusedSupportingTextColor = WalkieColors.Muted,
        unfocusedSupportingTextColor = WalkieColors.Muted,
    )

    WalkieBackground {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 440.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { aboutOpen = true }, modifier = Modifier.glass(50.dp)) {
                        Icon(Icons.Filled.Info, contentDescription = "Informazioni e disclaimer", tint = WalkieColors.Text)
                    }
                }
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(132.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(WalkieColors.Orange.copy(alpha = 0.35f), Color.Transparent))),
                    )
                    Box(
                        Modifier.size(84.dp).shadow(20.dp, CircleShape, spotColor = WalkieColors.Orange)
                            .clip(CircleShape).background(WalkieColors.accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null, tint = Color.White, modifier = Modifier.size(46.dp))
                    }
                }
                Text("Walkie PTT", color = WalkieColors.Text, fontSize = 34.sp, fontWeight = FontWeight.Black)
                Text("per PC", color = WalkieColors.Orange, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(
                    "Voce, chat, foto e SOS con i telefoni che hanno il collegamento Internet attivo",
                    color = WalkieColors.Muted, textAlign = TextAlign.Center, fontSize = 14.sp,
                )
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth().glass(24.dp).padding(18.dp)) {
                    Label("CHI SEI")
                    OutlinedTextField(
                        value = nick, onValueChange = { nick = it.take(20) }, label = { Text("Il tuo nome") },
                        singleLine = true, colors = fieldColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Label("CANALE")
                    OutlinedTextField(
                        value = channel, onValueChange = { channel = it.take(30) }, label = { Text("Nome del canale") },
                        supportingText = { Text("Lo stesso impostato sui telefoni") },
                        singleLine = true, colors = fieldColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it.take(64) }, label = { Text("Password") },
                        supportingText = { Text("La stessa dei telefoni (anche vuota)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true, colors = fieldColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Nascondi opzioni avanzate" else "Opzioni avanzate", color = WalkieColors.Muted)
                }
                if (advanced) {
                    OutlinedTextField(
                        value = broker, onValueChange = { broker = it.trim() }, label = { Text("Server MQTT") },
                        supportingText = { Text("Lo stesso dei telefoni. Predefinito: $DEFAULT_BROKER") },
                        singleLine = true, colors = fieldColors, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                val canStart = nick.isNotBlank() && channel.isNotBlank()
                Box(
                    Modifier.fillMaxWidth().height(58.dp)
                        .shadow(if (canStart) 16.dp else 0.dp, RoundedCornerShape(18.dp), spotColor = WalkieColors.Orange)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (canStart) WalkieColors.accent else Brush.linearGradient(listOf(WalkieColors.Glass, WalkieColors.Glass)))
                        .clickable(enabled = canStart, onClick = ::go),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("ENTRA NEL CANALE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { aboutOpen = true }) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = WalkieColors.Muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Informazioni e disclaimer", color = WalkieColors.Muted)
                }
                Text("App creata da ${About.AUTHOR}", color = WalkieColors.Muted.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
        if (aboutOpen) {
            AboutOverlay(version) {
                aboutOpen = false
                prefs.putBoolean("about_seen", true)
                runCatching { prefs.flush() }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text, color = WalkieColors.Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}
