package com.walkietalkie.ptt

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Cuore dell'app: gestisce la rete "mesh" tra i telefoni vicini con Google Nearby Connections
 * (Bluetooth / BLE / Wi-Fi, nessun internet necessario) e smista i tre tipi di contenuto:
 *  - BYTES  -> messaggi della chat (JSON)
 *  - FILE   -> foto
 *  - STREAM -> audio del push-to-talk
 */
class WalkieViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "Walkie"
        private val STRATEGY = Strategy.P2P_CLUSTER // tutti con tutti
    }

    private val client = Nearby.getConnectionsClient(app)
    private val audio = AudioEngine()
    private val myUid = UUID.randomUUID().toString().take(8)
    private val ids = AtomicLong()

    private var nickname = ""
    private var channel = ""

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /** endpointId -> nickname dei telefoni connessi. */
    private val _peers = MutableStateFlow<Map<String, String>>(emptyMap())
    val peers: StateFlow<Map<String, String>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    /** Chi sta parlando in questo momento. */
    private val _speaking = MutableStateFlow<Set<String>>(emptySet())
    val speaking: StateFlow<Set<String>> = _speaking.asStateFlow()

    private val _transmitting = MutableStateFlow(false)
    val transmitting: StateFlow<Boolean> = _transmitting.asStateFlow()

    val channelName get() = channel

    private val pendingNames = ConcurrentHashMap<String, String>()
    private val incomingFiles = ConcurrentHashMap<Long, Pair<String, Payload>>()

    // ---------------------------------------------------------------- sessione

    fun start(nick: String, chan: String) {
        if (_started.value) return
        nickname = nick.trim().replace("|", "").ifEmpty { "Anonimo" }
        channel = chan.trim().replace("|", "").ifEmpty { "1" }
        _started.value = true
        addSystem("Sei sul canale \"$channel\" come $nickname. In attesa di altri telefoni vicini…")
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        stopTalking()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        _peers.value = emptyMap()
        _speaking.value = emptySet()
        _messages.value = emptyList()
        pendingNames.clear()
        incomingFiles.clear()
        // Le foto sono "istantanee": all'uscita dal canale vengono cancellate.
        PhotoUtils.photosDir(getApplication()).listFiles()?.forEach { it.delete() }
        _started.value = false
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    /** Il service id include il canale: si "vedono" solo i telefoni sullo stesso canale. */
    private val serviceId get() = "${getApplication<Application>().packageName}.ch.$channel"
    private val endpointName get() = "$channel|$myUid|$nickname"

    private data class Remote(val channel: String, val uid: String, val nick: String)

    private fun parseName(name: String): Remote? {
        val parts = name.split("|", limit = 3)
        return if (parts.size == 3) Remote(parts[0], parts[1], parts[2]) else null
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        client.startAdvertising(endpointName, serviceId, lifecycleCallback, options)
            .addOnFailureListener { addSystem("Errore advertising: ${it.message}") }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        client.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { addSystem("Errore ricerca: ${it.message}") }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remote = parseName(info.endpointName) ?: return
            if (remote.channel != channel || remote.uid == myUid) return
            if (_peers.value.containsKey(endpointId)) return
            // Per evitare richieste incrociate, chiama solo chi ha l'id "più piccolo".
            if (myUid < remote.uid) {
                client.requestConnection(endpointName, endpointId, lifecycleCallback)
                    .addOnFailureListener { Log.w(TAG, "requestConnection fallita", it) }
            }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remote = parseName(info.endpointName)
            if (remote == null || remote.channel != channel) {
                client.rejectConnection(endpointId)
                return
            }
            pendingNames[endpointId] = remote.nick
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val nick = pendingNames.remove(endpointId) ?: return
            if (result.status.isSuccess) {
                _peers.update { it + (endpointId to nick) }
                addSystem("$nick si è connesso")
            }
        }

        override fun onDisconnected(endpointId: String) {
            val nick = _peers.value[endpointId] ?: return
            _peers.update { it - endpointId }
            _speaking.update { it - nick }
            addSystem("$nick si è disconnesso")
        }
    }

    // ---------------------------------------------------------------- ricezione

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val nick = _peers.value[endpointId] ?: pendingNames[endpointId] ?: "?"
            when (payload.type) {
                Payload.Type.BYTES -> handleBytes(nick, payload.asBytes() ?: return)
                Payload.Type.FILE -> incomingFiles[payload.id] = endpointId to payload
                Payload.Type.STREAM -> {
                    val input = payload.asStream()?.asInputStream() ?: return
                    _speaking.update { it + nick }
                    audio.play(input) { _speaking.update { it - nick } }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val entry = incomingFiles[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    incomingFiles.remove(update.payloadId)
                    viewModelScope.launch(Dispatchers.IO) { saveIncomingPhoto(entry.first, entry.second) }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> incomingFiles.remove(update.payloadId)
            }
        }
    }

    private fun handleBytes(nick: String, bytes: ByteArray) {
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        when (json.optString("t")) {
            "chat" -> addItem(ChatItem.Text(nextId(), now(), nick, false, json.optString("text")))
        }
    }

    @Suppress("DEPRECATION")
    private fun saveIncomingPhoto(endpointId: String, payload: Payload) {
        val ctx = getApplication<Application>()
        val nick = _peers.value[endpointId] ?: "?"
        val dest = PhotoUtils.newPhotoFile(ctx, "recv")
        val file = payload.asFile() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = file.asUri() ?: return
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
                runCatching { ctx.contentResolver.delete(uri, null, null) }
            } else {
                val src = file.asJavaFile() ?: return
                src.copyTo(dest, overwrite = true)
                src.delete()
            }
            addItem(ChatItem.Photo(nextId(), now(), nick, false, dest))
        } catch (e: Exception) {
            Log.w(TAG, "Salvataggio foto fallito", e)
            addSystem("Foto da $nick non ricevuta")
        }
    }

    // ---------------------------------------------------------------- invio

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        addItem(ChatItem.Text(nextId(), now(), nickname, true, clean))
        val targets = _peers.value.keys.toList()
        if (targets.isEmpty()) return
        val json = JSONObject().put("t", "chat").put("text", clean).toString()
        client.sendPayload(targets, Payload.fromBytes(json.toByteArray(Charsets.UTF_8)))
    }

    fun sendPhoto(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val file: File = try {
                PhotoUtils.compressForSending(getApplication(), uri)
            } catch (e: Exception) {
                Log.w(TAG, "Compressione fallita", e)
                null
            } ?: run {
                addSystem("Impossibile leggere la foto")
                return@launch
            }
            addItem(ChatItem.Photo(nextId(), now(), nickname, true, file))
            val targets = _peers.value.keys.toList()
            if (targets.isEmpty()) {
                addSystem("Nessuno connesso: foto non inviata")
                return@launch
            }
            client.sendPayload(targets, Payload.fromFile(file))
        }
    }

    /** Tasto PTT premuto: apre un flusso audio verso tutti i connessi. */
    fun startTalking() {
        if (_transmitting.value) return
        val targets = _peers.value.keys.toList()
        if (targets.isEmpty()) {
            addSystem("Nessuno in ascolto sul canale")
            return
        }
        val pipe = ParcelFileDescriptor.createPipe()
        client.sendPayload(targets, Payload.fromStream(pipe[0]))
        audio.startRecording(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
        _transmitting.value = true
    }

    /** Tasto PTT rilasciato: chiude il flusso (il ricevente sente il "bip"). */
    fun stopTalking() {
        if (!_transmitting.value) return
        audio.stopRecording()
        _transmitting.value = false
    }

    // ---------------------------------------------------------------- utilità

    private fun addSystem(text: String) = addItem(ChatItem.System(nextId(), now(), text))

    private fun addItem(item: ChatItem) = _messages.update { it + item }

    private fun nextId() = ids.incrementAndGet()

    private fun now() = System.currentTimeMillis()
}
