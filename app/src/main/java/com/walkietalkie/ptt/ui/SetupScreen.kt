package com.walkietalkie.ptt.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
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
import com.walkietalkie.ptt.Settings as WalkieSettings

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
    }
    // Nearby verifica la posizione anche su Android 13+ (errore 8034 MISSING_PERMISSION_ACCESS_COARSE_LOCATION).
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

private fun hasAll(context: Context, useNearby: Boolean) = requiredPermissions(useNearby).all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Facoltativi: notifiche (avvisi con l'app ridotta) e posizione (coordinate nell'SOS).
 * Senza, l'app funziona lo stesso.
 */
private fun optionalPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
}.toTypedArray()

private fun missingOptional(context: Context) = optionalPermissions().filter {
    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
}

/**
 * Chiede (una volta) di escludere l'app dall'ottimizzazione batteria: altrimenti alcuni telefoni
 * (es. Samsung) sospendono la rete dell'app ridotta e si smette di ricevere.
 */
@SuppressLint("BatteryLife") // app distribuita fuori dal Play Store; serve per un walkie talkie
private fun askBatteryExemption(context: Context, prefs: android.content.SharedPreferences) {
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(context.packageName) || prefs.getBoolean("battery_asked", false)) return
    prefs.edit { putBoolean("battery_asked", true) }
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        )
    }
}

@Composable
fun SetupScreen(onStart: (WalkieSettings) -> Unit) {
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

    fun settings() = WalkieSettings(nick, channel, password, useNearby, useInternet, broker.ifBlank { DEFAULT_BROKER })

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
        askBatteryExemption(context, prefs)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = requiredPermissions(useNearby).all {
            result[it] ?: (ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED)
        }
        if (granted) go()
        else error = "Servono i permessi di microfono" +
            (if (useNearby) ", dispositivi nelle vicinanze e posizione" else "") + " per funzionare."
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
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
            Text(
                "Voce, chat, foto e SOS con chi è vicino o lontano",
                color = WalkieColors.Muted,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))

            Column(Modifier.fillMaxWidth().glass(24.dp).padding(18.dp)) {
                SectionLabel("CHI SEI")
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it.take(20) },
                    label = { Text("Il tuo nome") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                SectionLabel("CANALE")
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.take(30) },
                    label = { Text("Nome del canale") },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(64) },
                    label = { Text("Password (consigliata)") },
                    supportingText = { Text("Uguale su tutti i dispositivi: cifra ciò che passa da internet") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LinkCard(Icons.Filled.Bluetooth, "Vicino", "Bluetooth/Wi-Fi\nsenza internet", useNearby, Modifier.weight(1f)) { useNearby = !useNearby }
                LinkCard(Icons.Filled.Public, "Internet", "Rete cellulare\novunque", useInternet, Modifier.weight(1f)) { useInternet = !useInternet }
            }

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Nascondi opzioni avanzate" else "Opzioni avanzate", color = WalkieColors.Muted)
            }
            if (advanced) {
                OutlinedTextField(
                    value = broker,
                    onValueChange = { broker = it.trim() },
                    label = { Text("Server MQTT") },
                    supportingText = { Text("Tutti devono usare lo stesso server. Es. $DEFAULT_BROKER") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))
            val canStart = nick.isNotBlank() && channel.isNotBlank() && (useNearby || useInternet)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .shadow(if (canStart) 16.dp else 0.dp, RoundedCornerShape(18.dp), spotColor = WalkieColors.Orange)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (canStart) WalkieColors.accent else Brush.linearGradient(listOf(WalkieColors.Glass, WalkieColors.Glass)))
                    .clickable(enabled = canStart) {
                        val missing = requiredPermissions(useNearby).filter {
                            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                        }.plus(missingOptional(context)).distinct()
                        if (missing.isEmpty()) go() else launcher.launch(missing.toTypedArray())
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("ENTRA NEL CANALE", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = WalkieColors.Red, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = WalkieColors.Muted,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Scheda selezionabile per attivare un tipo di collegamento. */
@Composable
private fun LinkCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) WalkieColors.Orange.copy(alpha = 0.16f) else WalkieColors.Glass)
            .border(if (selected) 2.dp else 1.dp, if (selected) WalkieColors.Orange else WalkieColors.Stroke, shape)
            .clickable(onClick = onToggle)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (selected) WalkieColors.Orange else WalkieColors.Muted)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .background(if (selected) WalkieColors.Orange else Color.Transparent)
                    .border(2.dp, if (selected) WalkieColors.Orange else WalkieColors.Muted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = WalkieColors.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(subtitle, color = WalkieColors.Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
