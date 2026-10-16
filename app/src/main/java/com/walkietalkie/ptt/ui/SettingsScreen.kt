package com.walkietalkie.ptt.ui

import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkietalkie.ptt.sound.SoundSettings
import com.walkietalkie.ptt.sound.ToneLibrary
import com.walkietalkie.ptt.sound.ToneLibrary.EndTone
import com.walkietalkie.ptt.sound.ToneLibrary.PttTone
import com.walkietalkie.ptt.sound.ToneLibrary.SosSound
import com.walkietalkie.ptt.sound.ToneLibrary.StartTone
import com.walkietalkie.ptt.sound.TonePlayer

/** Impostazioni → Suoni: selettiva, fine trasmissione, conferma PTT e suoneria SOS. */
@Composable
fun SettingsScreen(settings: SoundSettings, onBack: () -> Unit) {
    val context = LocalContext.current
    val sounds by settings.sounds.collectAsStateWithLifecycle()
    var previewRingtone by remember { mutableStateOf<android.media.Ringtone?>(null) }

    fun stopPreviews() {
        TonePlayer.stop()
        runCatching { previewRingtone?.stop() }
        previewRingtone = null
    }
    DisposableEffect(Unit) { onDispose { stopPreviews() } }
    BackHandler(onBack = onBack)

    fun previewRingtone(uri: Uri?) {
        stopPreviews()
        val u = uri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        previewRingtone = runCatching { RingtoneManager.getRingtone(context, u) }.getOrNull()?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
            runCatching { play() }
        }
    }

    fun previewSos(sound: SosSound) {
        when (sound) {
            SosSound.SYSTEM -> previewRingtone(null)
            SosSound.CUSTOM -> previewRingtone(settings.sosCustomUri)
            else -> {
                stopPreviews()
                // Due cicli di sirena bastano per farsi un'idea (le sirene lunghe si accorciano).
                val cycle = ToneLibrary.siren(sound)!!
                val pcm = if (cycle.size < ToneLibrary.samples(1500)) ToneLibrary.concat(*Array(6) { cycle }) else cycle
                TonePlayer.play(ToneLibrary.toBytes(pcm), AudioAttributes.USAGE_ALARM)
            }
        }
    }

    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            ?: return@rememberLauncherForActivityResult
        val title = runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
        settings.update { it.copy(sos = SosSound.CUSTOM, sosCustomUri = uri.toString(), sosCustomTitle = title) }
    }

    fun pickRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Suoneria SOS")
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, settings.sosCustomUri)
        }
        runCatching { ringtonePicker.launch(intent) }
    }

    WalkieBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = WalkieColors.Text)
                }
                Column {
                    Text("IMPOSTAZIONI", color = WalkieColors.Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Text("Suoni", color = WalkieColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Black)
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SettingsCard(
                    "SELETTIVA IN ARRIVO",
                    "Suona quando qualcuno inizia a parlare, prima della sua voce",
                ) {
                    StartTone.entries.forEach { tone ->
                        OptionRow(
                            tone.label, tone.description, selected = sounds.start == tone,
                            onSelect = { settings.update { it.copy(start = tone) } },
                            onPreview = if (tone == StartTone.NONE) null else ({
                                stopPreviews()
                                TonePlayer.play(ToneLibrary.toBytes(ToneLibrary.start(tone, sounds.selectiveCode)))
                            }),
                        )
                    }
                    if (sounds.start == StartTone.ZVEI || sounds.start == StartTone.CCIR) {
                        var code by remember(sounds.selectiveCode) { mutableStateOf(sounds.selectiveCode) }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { v ->
                                code = v.filter(Char::isDigit).take(5)
                                if (code.length == 5) settings.update { it.copy(selectiveCode = code) }
                            },
                            label = { Text("Codice selettiva (5 cifre)") },
                            supportingText = { Text("Cambia la melodia dei 5 toni") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            colors = settingsFieldColors(),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                SettingsCard("FINE TRASMISSIONE", "Suona quando l'altro rilascia il tasto") {
                    EndTone.entries.forEach { tone ->
                        OptionRow(
                            tone.label, tone.description, selected = sounds.end == tone,
                            onSelect = { settings.update { it.copy(end = tone) } },
                            onPreview = if (tone == EndTone.NONE) null else ({
                                stopPreviews()
                                TonePlayer.play(ToneLibrary.toBytes(ToneLibrary.end(tone)))
                            }),
                        )
                    }
                }

                SettingsCard("QUANDO PREMI IL TASTO", "Conferma che puoi iniziare a parlare") {
                    PttTone.entries.forEach { tone ->
                        OptionRow(
                            tone.label, tone.description, selected = sounds.ptt == tone,
                            onSelect = { settings.update { it.copy(ptt = tone) } },
                            onPreview = if (tone == PttTone.NONE) null else ({
                                stopPreviews()
                                TonePlayer.play(ToneLibrary.toBytes(ToneLibrary.ptt(tone)))
                            }),
                        )
                    }
                }

                SettingsCard("SUONERIA SOS", "Suona (anche col telefono in silenzioso multimediale) quando arriva un SOS") {
                    SosSound.entries.forEach { sound ->
                        val description = if (sound == SosSound.CUSTOM && sounds.sosCustomTitle != null) {
                            "Scelta: ${sounds.sosCustomTitle} (tocca per cambiarla)"
                        } else {
                            sound.description
                        }
                        OptionRow(
                            sound.label, description, selected = sounds.sos == sound,
                            onSelect = {
                                if (sound == SosSound.CUSTOM) pickRingtone()
                                else settings.update { it.copy(sos = sound) }
                            },
                            onPreview = if (sound == SosSound.CUSTOM && sounds.sosCustomUri == null) null else ({ previewSos(sound) }),
                        )
                    }
                }
                Text(
                    "I suoni sono generati dall'app e imitano quelli delle radio professionali; " +
                        "ognuno sceglie i suoni che sente sul proprio telefono.",
                    color = WalkieColors.Muted, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(22.dp).padding(14.dp)) {
        Text(title, color = WalkieColors.Orange, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = WalkieColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onPreview: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) WalkieColors.Orange.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .border(2.dp, if (selected) WalkieColors.Orange else WalkieColors.Muted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(WalkieColors.Orange))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = WalkieColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (description.isNotEmpty()) Text(description, color = WalkieColors.Muted, fontSize = 12.sp)
        }
        if (onPreview != null) {
            Box(
                Modifier.size(38.dp).glass(50.dp, strong = true).clickable(onClick = onPreview),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Ascolta $title", tint = WalkieColors.Text)
            }
        }
    }
}

@Composable
private fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
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
