package com.walkietalkie.ptt.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.walkietalkie.ptt.ChatItem
import com.walkietalkie.ptt.PhotoUtils
import com.walkietalkie.ptt.WalkieSession
import com.walkietalkie.ptt.net.InternetLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(vm: WalkieSession) {
    val peers by vm.peers.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val transmitting by vm.transmitting.collectAsStateWithLifecycle()
    val internetState by vm.internetState.collectAsStateWithLifecycle()
    val nearbyOn by vm.nearbyOn.collectAsStateWithLifecycle()
    val keyCode by vm.keyCode.collectAsStateWithLifecycle()
    val sosAlert by vm.sosAlert.collectAsStateWithLifecycle()
    var fullscreen by remember { mutableStateOf<File?>(null) }

    WalkieBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Header(vm.channelName, peers.values.toList(), speaking, nearbyOn, internetState, keyCode, onExit = vm::stop)

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

            MessageList(messages, onPhotoClick = { fullscreen = it }, modifier = Modifier.weight(1f))
            BottomPanel(vm, peers.isNotEmpty(), transmitting, speaking.isNotEmpty())
        }
    }

    fullscreen?.let { file -> PhotoDialog(file) { fullscreen = null } }
    sosAlert?.let { sos -> SosAlertDialog(sos, onDismiss = vm::dismissSos) }
}

// ------------------------------------------------------------------- intestazione

@Composable
private fun Header(
    channel: String,
    peers: List<com.walkietalkie.ptt.Peer>,
    speaking: Set<String>,
    nearbyOn: Boolean,
    internetState: InternetLink.State?,
    keyCode: String?,
    onExit: () -> Unit,
) {
    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
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
            if (nearbyOn) StatusChip("Vicino", WalkieColors.Teal)
            if (internetState != null) {
                val (label, color) = when (internetState) {
                    InternetLink.State.ONLINE -> "Internet" to WalkieColors.Teal
                    InternetLink.State.CONNECTING -> "Internet…" to WalkieColors.Amber
                    else -> "Internet offline" to WalkieColors.Red
                }
                StatusChip(label, color)
            }
            keyCode?.let { StatusChip("🔑 $it", WalkieColors.Muted) }
        }
        Spacer(Modifier.height(12.dp))
        if (peers.isEmpty()) {
            Text("In attesa di altri dispositivi sul canale…", color = WalkieColors.Muted, fontSize = 13.sp)
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(peers, key = { it.uid }) { p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
                        Avatar(
                            p.nick, size = 44.dp, speaking = p.nick in speaking,
                            badge = when {
                                p.nearby && p.internet -> "📡"
                                p.nearby -> "📡"
                                else -> "🌐"
                            },
                        )
                        Text(p.nick, color = WalkieColors.Text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------- messaggi

@Composable
private fun MessageList(items: List<ChatItem>, onPhotoClick: (File) -> Unit, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) state.animateScrollToItem(items.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = state,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.System -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        item.text,
                        color = WalkieColors.Muted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.glass(50.dp).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
                is ChatItem.Text -> Bubble(item.from, item.mine, item.time) {
                    Text(item.text, color = if (item.mine) Color.White else WalkieColors.Text, fontSize = 15.sp)
                }
                is ChatItem.Photo -> Bubble(item.from, item.mine, item.time) {
                    Thumbnail(item.file, onClick = { onPhotoClick(item.file) })
                }
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
            Modifier
                .widthIn(max = 280.dp)
                .clip(shape)
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

@Composable
private fun SosCard(sos: ChatItem.Sos) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(WalkieColors.sos).padding(14.dp),
    ) {
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
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)),
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Apri mappa", color = Color.White)
            }
        }
    }
}

private fun locationText(sos: ChatItem.Sos): String =
    if (sos.hasLocation) {
        "📍 %.5f, %.5f".format(Locale.US, sos.lat, sos.lon) +
            (sos.accuracyM?.let { " (±${it.toInt()} m)" } ?: "")
    } else {
        "Posizione non disponibile"
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
        Modifier.padding(vertical = 4.dp).size(220.dp).clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.25f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("…", color = WalkieColors.Muted)
        }
    }
}

@Composable
private fun PhotoDialog(file: File, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            rememberPhoto(file, 2048)?.let {
                Image(it, contentDescription = "Foto", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Allarme a tutto schermo quando arriva un SOS. */
@Composable
private fun SosAlertDialog(sos: ChatItem.Sos, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF7F0A1C), WalkieColors.Red, Color(0xFF7F0A1C)))),
            contentAlignment = Alignment.Center,
        ) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(120.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))
                        .border(3.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text("SOS", color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
                Text(sos.from, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("ha chiesto aiuto alle ${timeFormat.format(Date(sos.time))}", color = Color.White.copy(alpha = 0.85f))
                Spacer(Modifier.height(16.dp))
                Text(locationText(sos), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                sos.mapsUrl?.let { url ->
                    Button(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = WalkieColors.RedDark),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Apri mappa", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedButton(
                    onClick = onDismiss,
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    Text("Ho visto", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }
    }
}

// ------------------------------------------------------------------- comandi in basso

@Composable
private fun BottomPanel(vm: WalkieSession, enabled: Boolean, transmitting: Boolean, receiving: Boolean) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var pendingCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) vm.sendPhoto(uri)
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.sendPhoto(uri)
    }
    fun shoot() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        takePicture.launch(uri)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Color(0xCC111527))
            .border(1.dp, WalkieColors.Stroke, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .navigationBarsPadding()
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp).fillMaxWidth().glass(28.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = "Invia foto dalla galleria", tint = WalkieColors.Muted)
            }
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Scrivi un messaggio…", color = WalkieColors.Muted) },
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
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape)
                    .background(if (text.isNotBlank()) WalkieColors.accent else Brush.linearGradient(listOf(WalkieColors.Glass, WalkieColors.Glass)))
                    .clickable(enabled = text.isNotBlank()) {
                        vm.sendText(text)
                        text = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Invia", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SosButton(onTrigger = vm::sendSos, modifier = Modifier.width(76.dp))
            PttButton(
                state = when {
                    transmitting -> PttState.TRANSMITTING
                    receiving -> PttState.RECEIVING
                    enabled -> PttState.IDLE
                    else -> PttState.DISABLED
                },
                onPress = vm::startTalking,
                onRelease = vm::stopTalking,
            )
            Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(64.dp).glass(50.dp, strong = true).clickable { shoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Scatta e invia foto", tint = WalkieColors.Text, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Foto", color = WalkieColors.Muted, fontSize = 11.sp)
                Spacer(Modifier.height(15.dp))
            }
        }
        Text(
            when {
                transmitting -> "IN TRASMISSIONE"
                receiving -> "IN ASCOLTO"
                enabled -> "TIENI PREMUTO PER PARLARE"
                else -> "IN ATTESA DI ALTRI DISPOSITIVI"
            },
            color = when {
                transmitting -> WalkieColors.Red
                receiving -> WalkieColors.Teal
                else -> WalkieColors.Muted
            },
            fontSize = 12.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
