package com.walkietalkie.ptt.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkietalkie.ptt.ChatItem
import com.walkietalkie.ptt.PhotoUtils
import com.walkietalkie.ptt.WalkieViewModel
import com.walkietalkie.ptt.net.InternetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: WalkieViewModel) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val transmitting by vm.transmitting.collectAsStateWithLifecycle()
    val internetState by vm.internetState.collectAsStateWithLifecycle()
    val nearbyOn by vm.nearbyOn.collectAsStateWithLifecycle()
    val keyCode by vm.keyCode.collectAsStateWithLifecycle()
    var fullscreen by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Canale ${vm.channelName}", fontWeight = FontWeight.Bold)
                        Text(
                            (keyCode?.let { "Codice $it · " } ?: "") +
                                if (peers.isEmpty()) "Nessuno connesso"
                                else "${peers.size} connessi: " + peers.values.joinToString {
                                it.nick + when {
                                    it.nearby && it.internet -> " 📡🌐"
                                    it.nearby -> " 📡"
                                    else -> " 🌐"
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    LinkStatus(nearbyOn, internetState)
                    IconButton(onClick = vm::stop) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Esci dal canale")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
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

            MessageList(messages, onPhotoClick = { fullscreen = it }, modifier = Modifier.weight(1f))
            InputBar(onSendText = vm::sendText, onSendPhoto = vm::sendPhoto)
            PttButton(
                transmitting = transmitting,
                enabled = peers.isNotEmpty(),
                onPress = vm::startTalking,
                onRelease = vm::stopTalking,
            )
        }
    }

    fullscreen?.let { file -> PhotoDialog(file) { fullscreen = null } }
}

/** Icone di stato dei due collegamenti: vicino (Bluetooth/Wi-Fi) e internet. */
@Composable
private fun LinkStatus(nearbyOn: Boolean, internetState: InternetLink.State?) {
    if (nearbyOn) {
        Icon(
            Icons.Filled.Bluetooth,
            contentDescription = "Vicino attivo",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (internetState != null) {
        val (icon, tint, label) = when (internetState) {
            InternetLink.State.ONLINE -> Triple(Icons.Filled.Public, MaterialTheme.colorScheme.primary, "Internet collegato")
            InternetLink.State.CONNECTING -> Triple(Icons.Filled.Public, MaterialTheme.colorScheme.outline, "Internet: collegamento…")
            else -> Triple(Icons.Filled.PublicOff, MaterialTheme.colorScheme.error, "Internet non raggiungibile")
        }
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun MessageList(items: List<ChatItem>, onPhotoClick: (File) -> Unit, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) state.animateScrollToItem(items.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = state,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.System -> Text(
                    item.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                is ChatItem.Text -> Bubble(item.from, item.mine, item.time) {
                    Text(item.text)
                }
                is ChatItem.Photo -> Bubble(item.from, item.mine, item.time) {
                    Thumbnail(item.file, onClick = { onPhotoClick(item.file) })
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

@Composable
private fun Bubble(from: String, mine: Boolean, time: Long, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (mine) 16.dp else 4.dp,
                bottomEnd = if (mine) 4.dp else 16.dp,
            ),
            modifier = Modifier.widthIn(max = 280.dp),
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
private fun rememberPhoto(file: File, maxSide: Int): ImageBitmap? {
    var bitmap by remember(file, maxSide) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file, maxSide) {
        bitmap = withContext(Dispatchers.IO) { PhotoUtils.decodeSampled(file, maxSide)?.asImageBitmap() }
    }
    return bitmap
}

@Composable
private fun Thumbnail(file: File, onClick: () -> Unit) {
    val bitmap = rememberPhoto(file, 600)
    Box(
        Modifier
            .size(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("…")
        }
    }
}

@Composable
private fun PhotoDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            rememberPhoto(file, 2048)?.let {
                Image(it, contentDescription = "Foto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun InputBar(onSendText: (String) -> Unit, onSendPhoto: (Uri) -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) onSendPhoto(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSendPhoto(uri)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            val dir = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri
            takePicture.launch(uri)
        }) {
            Icon(Icons.Filled.CameraAlt, contentDescription = "Scatta e invia foto")
        }
        IconButton(onClick = {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }) {
            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Invia foto dalla galleria")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Messaggio") },
            maxLines = 3,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onSendText(text)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Invia")
        }
    }
}

@Composable
private fun PttButton(
    transmitting: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val color = when {
        transmitting -> MaterialTheme.colorScheme.tertiary
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 12.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(if (transmitting) 128.dp else 116.dp)
                .clip(CircleShape)
                .background(color)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
            Icon(Icons.Filled.Mic, contentDescription = "Premi per parlare", tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                transmitting -> "IN TRASMISSIONE…"
                enabled -> "Tieni premuto per parlare"
                else -> "In attesa di altri telefoni"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
