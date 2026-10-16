package com.walkietalkie.ptt.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.walkietalkie.ptt.ChatItem
import com.walkietalkie.ptt.desktop.DesktopClient
import com.walkietalkie.ptt.desktop.Photos
import com.walkietalkie.ptt.desktop.openWithSystem
import com.walkietalkie.ptt.net.InternetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(client: DesktopClient, onTypingChange: (Boolean) -> Unit) {
    val peers by client.peers.collectAsState()
    val messages by client.messages.collectAsState()
    val speaking by client.speaking.collectAsState()
    val transmitting by client.transmitting.collectAsState()
    val linkState by client.linkState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Canale ${client.channelName}", fontWeight = FontWeight.Bold)
                        Text(
                            if (peers.isEmpty()) "Nessuno connesso"
                            else "${peers.size} connessi: ${peers.values.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    LinkStatus(linkState)
                    IconButton(onClick = client::stop) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Esci dal canale")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AnimatedVisibility(visible = speaking.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("${speaking.joinToString()} sta parlando…", fontWeight = FontWeight.Medium)
                }
            }
            MessageList(messages, Modifier.weight(1f))
            InputBar(client, onTypingChange)
            PttButton(
                transmitting = transmitting,
                enabled = peers.isNotEmpty(),
                onPress = client::startTalking,
                onRelease = client::stopTalking,
            )
        }
    }
}

@Composable
private fun LinkStatus(state: InternetLink.State?) {
    val (icon, tint, label) = when (state) {
        InternetLink.State.ONLINE -> Triple(Icons.Filled.Public, MaterialTheme.colorScheme.primary, "Collegato")
        InternetLink.State.CONNECTING, null ->
            Triple(Icons.Filled.Public, MaterialTheme.colorScheme.outline, "Collegamento…")
        else -> Triple(Icons.Filled.PublicOff, MaterialTheme.colorScheme.error, "Server non raggiungibile")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = tint)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
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
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.System -> Text(
                    item.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                is ChatItem.Text -> Bubble(item.from, item.mine, item.time) { Text(item.text) }
                is ChatItem.Photo -> Bubble(item.from, item.mine, item.time) {
                    Thumbnail(item.file)
                }
            }
        }
    }
}

@Composable
private fun Bubble(from: String, mine: Boolean, time: Long, content: @Composable () -> Unit) {
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!mine) {
                    Text(
                        from,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                content()
                Text(
                    timeFormat.format(Date(time)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(file: File) {
    var bitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                org.jetbrains.skia.Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(240.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable { openWithSystem(file) }, // si apre col visualizzatore del PC (da lì si salva)
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(it, contentDescription = "Foto (clic per aprire)", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: Text("…")
    }
}

@Composable
private fun InputBar(client: DesktopClient, onTypingChange: (Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    fun send() {
        client.sendText(text)
        text = ""
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
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
            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Invia foto")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Messaggio (Invio invia, Esc esce)") },
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
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
        IconButton(onClick = ::send, enabled = text.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Invia")
        }
    }
}

@Composable
private fun PttButton(transmitting: Boolean, enabled: Boolean, onPress: () -> Unit, onRelease: () -> Unit) {
    val color = when {
        transmitting -> MaterialTheme.colorScheme.tertiary
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(if (transmitting) 116.dp else 104.dp)
                .clip(CircleShape)
                .background(color)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onPress()
                        try {
                            tryAwaitRelease()
                        } finally {
                            onRelease()
                        }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "Premi per parlare", tint = Color.White, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                transmitting -> "IN TRASMISSIONE…"
                enabled -> "Tieni premuto qui o la barra SPAZIO per parlare"
                else -> "In attesa dei telefoni"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
