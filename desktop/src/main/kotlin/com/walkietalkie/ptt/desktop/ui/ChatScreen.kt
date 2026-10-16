package com.walkietalkie.ptt.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.walkietalkie.ptt.ChatItem
import com.walkietalkie.ptt.desktop.DesktopClient
import com.walkietalkie.ptt.desktop.Photos
import com.walkietalkie.ptt.desktop.openWithSystem
import com.walkietalkie.ptt.net.InternetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(client: DesktopClient, onTypingChange: (Boolean) -> Unit) {
    val peers by client.peers.collectAsState()
    val messages by client.messages.collectAsState()
    val speaking by client.speaking.collectAsState()
    val transmitting by client.transmitting.collectAsState()
    val linkState by client.linkState.collectAsState()
    val keyCode by client.keyCode.collectAsState()
    val sosAlert by client.sosAlert.collectAsState()

    WalkieBackground {
        Column(Modifier.fillMaxSize()) {
            Header(client.channelName, peers.values.toList(), speaking, linkState, keyCode, onExit = client::stop)
            AnimatedVisibility(
                visible = speaking.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().glass(16.dp)
                        .background(WalkieColors.Teal.copy(alpha = 0.12f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    EqualizerBars()
                    Spacer(Modifier.width(12.dp))
                    Text("${speaking.joinToString()} sta parlando…", color = WalkieColors.Text, fontWeight = FontWeight.SemiBold)
                }
            }
            MessageList(messages, Modifier.weight(1f))
            BottomPanel(client, peers.isNotEmpty(), transmitting, speaking.isNotEmpty(), onTypingChange)
        }
        sosAlert?.let { SosAlertOverlay(it, onDismiss = client::dismissSos) }
    }
}

@Composable
private fun Header(
    channel: String,
    peers: List<String>,
    speaking: Set<String>,
    linkState: InternetLink.State?,
    keyCode: String?,
    onExit: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("CANALE", color = WalkieColors.Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Text(channel, color = WalkieColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onExit, modifier = Modifier.glass(50.dp)) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Esci dal canale", tint = WalkieColors.Text)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (label, color) = when (linkState) {
                InternetLink.State.ONLINE -> "Internet" to WalkieColors.Teal
                InternetLink.State.CONNECTING, null -> "Internet…" to WalkieColors.Amber
                else -> "Internet offline" to WalkieColors.Red
            }
            StatusChip(label, color)
            keyCode?.let { StatusChip("🔑 $it", WalkieColors.Muted) }
        }
        Spacer(Modifier.height(12.dp))
        if (peers.isEmpty()) {
            Text("In attesa dei telefoni sul canale…", color = WalkieColors.Muted, fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(peers) { nick ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        Avatar(nick, size = 44.dp, speaking = nick in speaking)
                        Text(nick, color = WalkieColors.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageList(items: List<ChatItem>, modifier: Modifier) {
    val state = rememberLazyListState()
    val focus = LocalFocusManager.current
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) state.animateScrollToItem(items.lastIndex)
    }
    LazyColumn(
        // Un clic sulla chat toglie il cursore dal campo messaggio: così SPAZIO torna a fare da PTT.
        modifier = modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        state = state,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.System -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        item.text, color = WalkieColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.glass(50.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                is ChatItem.Text -> Bubble(item.from, item.mine, item.time) {
                    Text(item.text, color = if (item.mine) Color.White else WalkieColors.Text, fontSize = 15.sp)
                }
                is ChatItem.Photo -> Bubble(item.from, item.mine, item.time) { Thumbnail(item.file) }
                is ChatItem.Sos -> SosCard(item)
            }
        }
    }
}

private val timeFormat get() = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun Bubble(from: String, mine: Boolean, time: Long, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp,
        bottomStart = if (mine) 20.dp else 6.dp,
        bottomEnd = if (mine) 6.dp else 20.dp,
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) {
            Avatar(from, size = 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .then(
                    if (mine) Modifier.background(WalkieColors.accent)
                    else Modifier.background(WalkieColors.Glass).border(1.dp, WalkieColors.Stroke, shape)
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            if (!mine) Text(from, color = WalkieColors.forName(from), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            content()
            Text(
                timeFormat.format(Date(time)),
                color = if (mine) Color.White.copy(alpha = 0.75f) else WalkieColors.Muted,
                fontSize = 10.sp,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

private fun openUrl(url: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
}

private fun locationText(sos: ChatItem.Sos): String =
    if (sos.hasLocation) {
        "📍 %.5f, %.5f".format(Locale.US, sos.lat, sos.lon) + (sos.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
    } else {
        "Posizione non disponibile"
    }

@Composable
private fun SosCard(sos: ChatItem.Sos) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(WalkieColors.sos).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(
                if (sos.mine) "Hai inviato un SOS" else "SOS da ${sos.from}",
                color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp, modifier = Modifier.weight(1f),
            )
            Text(timeFormat.format(Date(sos.time)), color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(locationText(sos), color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
        sos.mapsUrl?.let { url ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { openUrl(url) }, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Apri mappa", color = Color.White)
            }
        }
    }
}

/** Allarme rosso sopra tutta la finestra quando arriva un SOS. */
@Composable
private fun SosAlertOverlay(sos: ChatItem.Sos, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xF27F0A1C), Color(0xF2FF3B4E), Color(0xF27F0A1C))))
            .pointerInput(Unit) { detectTapGestures { } }, // blocca i clic sotto
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 380.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(110.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)).border(3.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("SOS", color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
            Text(sos.from, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("ha chiesto aiuto alle ${timeFormat.format(Date(sos.time))}", color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(14.dp))
            Text(locationText(sos), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            sos.mapsUrl?.let { url ->
                Button(
                    onClick = { openUrl(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = WalkieColors.RedDark),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Filled.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apri mappa", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedButton(onClick = onDismiss, border = BorderStroke(2.dp, Color.White), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Ho visto", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier.padding(vertical = 4.dp).size(240.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable { openWithSystem(file) }, // si apre col visualizzatore del PC (da lì si salva)
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = "Foto (clic per aprire)", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: Text("…", color = WalkieColors.Muted)
    }
}

@Composable
private fun BottomPanel(
    client: DesktopClient,
    enabled: Boolean,
    transmitting: Boolean,
    receiving: Boolean,
    onTypingChange: (Boolean) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun send() {
        client.sendText(text)
        text = ""
    }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xCC111527))
            .border(1.dp, WalkieColors.Stroke, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(28.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                val dialog = FileDialog(null as Frame?, "Scegli una foto da inviare", FileDialog.LOAD).apply {
                    setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in Photos.EXTENSIONS }
                    isMultipleMode = true
                    isVisible = true
                }
                dialog.files.forEach(client::sendPhoto)
            }) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Invia foto", tint = WalkieColors.Muted)
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Messaggio (Invio invia, Esc esce)", color = WalkieColors.Muted) },
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = WalkieColors.Orange,
                    focusedTextColor = WalkieColors.Text,
                    unfocusedTextColor = WalkieColors.Text,
                ),
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { onTypingChange(it.isFocused) }
                    .onPreviewKeyEvent { e ->
                        when {
                            e.type != KeyEventType.KeyDown -> false
                            e.key == Key.Enter && !e.isShiftPressed -> {
                                send()
                                true
                            }
                            e.key == Key.Escape -> {
                                focus.clearFocus()
                                true
                            }
                            else -> false
                        }
                    },
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (text.isNotBlank()) WalkieColors.accent else Brush.linearGradient(listOf(WalkieColors.Glass, WalkieColors.Glass)))
                    .clickable(enabled = text.isNotBlank(), onClick = ::send),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Invia", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SosButton(onTrigger = client::sendSos, modifier = Modifier.width(76.dp))
            PttButton(
                state = when {
                    transmitting -> PttState.TRANSMITTING
                    receiving -> PttState.RECEIVING
                    enabled -> PttState.IDLE
                    else -> PttState.DISABLED
                },
                onPress = client::startTalking,
                onRelease = client::stopTalking,
            )
            Spacer(Modifier.width(76.dp))
        }
        Text(
            when {
                transmitting -> "IN TRASMISSIONE"
                receiving -> "IN ASCOLTO"
                enabled -> "TIENI PREMUTO IL TASTO O LA BARRA SPAZIO"
                else -> "IN ATTESA DEI TELEFONI"
            },
            color = when {
                transmitting -> WalkieColors.Red
                receiving -> WalkieColors.Teal
                else -> WalkieColors.Muted
            },
            fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}
