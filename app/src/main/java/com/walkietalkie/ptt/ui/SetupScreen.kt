package com.walkietalkie.ptt.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.walkietalkie.ptt.Settings

const val DEFAULT_BROKER = "ssl://broker.hivemq.com:8883"

/** Permessi necessari a runtime, in base alla versione di Android e ai collegamenti scelti. */
fun requiredPermissions(useNearby: Boolean): Array<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (!useNearby) return@buildList
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

private fun hasAll(context: Context, useNearby: Boolean) = requiredPermissions(useNearby).all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun SetupScreen(onStart: (Settings) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("setup", Context.MODE_PRIVATE) }

    var nick by rememberSaveable { mutableStateOf(prefs.getString("nick", null) ?: Build.MODEL ?: "") }
    var channel by rememberSaveable { mutableStateOf(prefs.getString("channel", "1")!!) }
    var password by rememberSaveable { mutableStateOf(prefs.getString("password", "")!!) }
    var useNearby by rememberSaveable { mutableStateOf(prefs.getBoolean("nearby", true)) }
    var useInternet by rememberSaveable { mutableStateOf(prefs.getBoolean("internet", true)) }
    var broker by rememberSaveable { mutableStateOf(prefs.getString("broker", DEFAULT_BROKER)!!) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun settings() = Settings(nick, channel, password, useNearby, useInternet, broker.ifBlank { DEFAULT_BROKER })

    fun go() {
        prefs.edit {
            putString("nick", nick)
            putString("channel", channel)
            putString("password", password)
            putBoolean("nearby", useNearby)
            putBoolean("internet", useInternet)
            putString("broker", broker)
        }
        error = null
        onStart(settings())
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) go()
        else error = "Servono i permessi di microfono" +
            (if (useNearby) " e dispositivi nelle vicinanze" else "") + " per funzionare."
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .imePadding()
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
            Text("Walkie PTT", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Parla, scrivi e scambia foto con chi è vicino o lontano.",
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
                supportingText = { Text("Solo chi è sullo stesso canale ti sente") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it.take(64) },
                label = { Text("Password del canale (consigliata)") },
                supportingText = { Text("Cifra tutto ciò che passa da internet") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LinkSwitch(
                Icons.Filled.Bluetooth, "Vicino (Bluetooth / Wi-Fi)", "Senza internet, fino a qualche decina di metri",
                useNearby,
            ) { useNearby = it }
            LinkSwitch(
                Icons.Filled.Public, "Internet (rete cellulare)", "Ovunque ci sia campo, usa i dati mobili",
                useInternet,
            ) { useInternet = it }

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Nascondi opzioni avanzate" else "Opzioni avanzate")
            }
            if (advanced) {
                OutlinedTextField(
                    value = broker,
                    onValueChange = { broker = it.trim() },
                    label = { Text("Server MQTT") },
                    supportingText = { Text("Tutti devono usare lo stesso server. Es. $DEFAULT_BROKER") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (hasAll(context, useNearby)) go() else launcher.launch(requiredPermissions(useNearby))
                },
                enabled = nick.isNotBlank() && channel.isNotBlank() && (useNearby || useInternet),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Entra nel canale") }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun LinkSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
