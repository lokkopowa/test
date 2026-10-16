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
import com.walkietalkie.ptt.net.ChannelCrypto
import com.walkietalkie.ptt.net.InternetLink
import com.walkietalkie.ptt.net.Packet
import com.walkietalkie.ptt.net.VoiceCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/** Impostazioni scelte nella schermata iniziale. */
data class Settings(
    val nick: String,
    val channel: String,
    val password: String,
    val useNearby: Boolean,
    val useInternet: Boolean,
    val broker: String,
)

/** Un altro telefono del canale e come lo raggiungiamo. */
data class Peer(val uid: String, val nick: String, val nearby: Boolean, val internet: Boolean)

/**
 * Cuore dell'app. Usa due collegamenti, anche insieme:
 *  - **Vicino** (Google Nearby Connections: Bluetooth / Wi-Fi diretto, senza internet)
 *      BYTES -> chat (JSON), FILE -> foto, STREAM -> voce PCM 16 kHz
 *  - **Internet** (rete cellulare o Wi-Fi, tramite broker MQTT, contenuti cifrati)
 *      [Packet] con la voce compressa in µ-law 8 kHz
 * Se un telefono è raggiungibile in entrambi i modi, i doppioni vengono scartati.
 */
class WalkieViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "Walkie"
        private val STRATEGY = Strategy.P2P_CLUSTER // tutti con tutti

        private const val SRC_NEARBY = 0
        private const val SRC_INTERNET = 1
        private const val SESSION_ENDED = 2

        private const val HEARTBEAT_MS = 15_000L
        private const val INTERNET_PEER_TIMEOUT_MS = 45_000L
        private const val NET_AUDIO_TIMEOUT_MS = 2_500L
        private const val NET_AUDIO_PREBUFFER_MS = 250
        private const val NET_AUDIO_CHUNK = 800 // byte µ-law = 100 ms
    }

    private val nearby = Nearby.getConnectionsClient(app)
    private var internet: InternetLink? = null
    private val audio = AudioEngine()
    private val myUid = UUID.randomUUID().toString().replace("-", "").take(8)
    private val ids = AtomicLong()
    private var housekeeping: Job? = null

    private var settings = Settings("", "", "", useNearby = true, useInternet = true, broker = "")
    private var channelId = ""
    private val nickname get() = settings.nick

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /** uid -> telefono connesso. */
    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    /** Chi sta parlando in questo momento. */
    private val _speaking = MutableStateFlow<Set<String>>(emptySet())
    val speaking: StateFlow<Set<String>> = _speaking.asStateFlow()

    private val _transmitting = MutableStateFlow(false)
    val transmitting: StateFlow<Boolean> = _transmitting.asStateFlow()

    /** null = collegamento internet disattivato. */
    private val _internetState = MutableStateFlow<InternetLink.State?>(null)
    val internetState: StateFlow<InternetLink.State?> = _internetState.asStateFlow()

    private val _nearbyOn = MutableStateFlow(false)
    val nearbyOn: StateFlow<Boolean> = _nearbyOn.asStateFlow()

    val channelName get() = settings.channel

    // Nearby
    private val endpoints = ConcurrentHashMap<String, String>() // endpointId -> uid
    private val pending = ConcurrentHashMap<String, Remote>()
    private val incomingFiles = ConcurrentHashMap<Long, Pair<String, Payload>>()
    private val photoHeaders = ConcurrentHashMap<Long, String>() // payloadId -> mid

    // Internet
    private val netLastSeen = ConcurrentHashMap<String, Long>()
    private val netStreams = ConcurrentHashMap<Long, NetStream>()

    private class NetStream(val out: OutputStream, @Volatile var lastPacket: Long)

    /** Messaggi già visti (per scartare quelli arrivati da entrambi i collegamenti). */
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 1000
    }

    /** Trasmissione vocale (sid) -> collegamento da cui la stiamo ascoltando. */
    private val audioSessions = ConcurrentHashMap<Long, Int>()

    // ---------------------------------------------------------------- sessione

    fun start(s: Settings) {
        if (_started.value) return
        settings = s.copy(
            nick = s.nick.trim().replace("|", "").ifEmpty { "Anonimo" },
            channel = s.channel.trim().ifEmpty { "1" },
            broker = s.broker.trim(),
        )
        channelId = ChannelCrypto.channelId(settings.channel, settings.password)
        _started.value = true
        addSystem("Sei sul canale \"${settings.channel}\" come $nickname. In attesa di altri telefoni…")

        if (settings.useNearby) {
            _nearbyOn.value = true
            startAdvertising()
            startDiscovery()
        }
        if (settings.useInternet) startInternet()

        housekeeping = viewModelScope.launch(Dispatchers.Default) {
            var sinceHeartbeat = 0L
            while (isActive) {
                delay(1000)
                sinceHeartbeat += 1000
                if (sinceHeartbeat >= HEARTBEAT_MS) {
                    sinceHeartbeat = 0
                    internet?.publish(Packet.Hello(myUid, nickname, wantReply = false))
                }
                val now = now()
                netLastSeen.forEach { (uid, last) ->
                    if (now - last > INTERNET_PEER_TIMEOUT_MS) {
                        netLastSeen.remove(uid)
                        updatePeer(uid, null, internet = false)
                    }
                }
                netStreams.forEach { (sid, s) ->
                    if (now - s.lastPacket > NET_AUDIO_TIMEOUT_MS) closeNetStream(sid)
                }
            }
        }
    }

    fun stop() {
        stopTalking()
        housekeeping?.cancel()
        internet?.close(Packet.Bye(myUid, nickname))
        internet = null
        _internetState.value = null
        nearby.stopAdvertising()
        nearby.stopDiscovery()
        nearby.stopAllEndpoints()
        _nearbyOn.value = false
        netStreams.keys.forEach(::closeNetStream)
        endpoints.clear()
        pending.clear()
        incomingFiles.clear()
        photoHeaders.clear()
        netLastSeen.clear()
        audioSessions.clear()
        synchronized(seen) { seen.clear() }
        _peers.value = emptyMap()
        _speaking.value = emptySet()
        _messages.value = emptyList()
        // Le foto sono "istantanee": all'uscita dal canale vengono cancellate.
        PhotoUtils.photosDir(getApplication()).listFiles()?.forEach { it.delete() }
        _started.value = false
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    // ---------------------------------------------------------------- telefoni connessi

    private fun updatePeer(uid: String, nick: String?, nearby: Boolean? = null, internet: Boolean? = null) {
        var before: Peer? = null
        var after: Peer? = null
        _peers.update { map ->
            val old = map[uid]
            val peer = Peer(
                uid = uid,
                nick = nick ?: old?.nick ?: "?",
                nearby = nearby ?: old?.nearby ?: false,
                internet = internet ?: old?.internet ?: false,
            )
            before = old
            after = peer.takeIf { it.nearby || it.internet }
            if (after == null) map - uid else map + (uid to peer)
        }
        val b = before
        val a = after
        when {
            b == null && a != null -> addSystem("${a.nick} si è connesso ${via(a)}")
            b != null && a == null -> {
                addSystem("${b.nick} si è disconnesso")
                _speaking.update { it - b.nick }
            }
        }
    }

    private fun via(p: Peer) = when {
        p.nearby && p.internet -> "(vicino + internet)"
        p.nearby -> "(vicino)"
        else -> "(via internet)"
    }

    private fun nickOf(uid: String?) = _peers.value[uid]?.nick ?: "?"

    /** Serve mandare roba pesante (foto, voce) anche via internet? */
    private fun internetNeeded(): Boolean =
        internet?.isOnline == true && _peers.value.values.any { !it.nearby }

    // ---------------------------------------------------------------- Nearby

    private val serviceId get() = "${getApplication<Application>().packageName}.$channelId"
    private val endpointName get() = "${channelId.take(8)}|$myUid|$nickname"

    private data class Remote(val channel: String, val uid: String, val nick: String)

    private fun parseName(name: String): Remote? {
        val parts = name.split("|", limit = 3)
        return if (parts.size == 3) Remote(parts[0], parts[1], parts[2]) else null
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        nearby.startAdvertising(endpointName, serviceId, lifecycleCallback, options)
            .addOnFailureListener { addSystem("Errore Nearby (annuncio): ${it.message}") }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        nearby.startDiscovery(serviceId, discoveryCallback, options)
            .addOnFailureListener { addSystem("Errore Nearby (ricerca): ${it.message}") }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val remote = parseName(info.endpointName) ?: return
            if (remote.channel != channelId.take(8) || remote.uid == myUid) return
            if (endpoints.containsKey(endpointId) || endpoints.containsValue(remote.uid)) return
            // Per evitare richieste incrociate, chiama solo chi ha l'id "più piccolo".
            if (myUid < remote.uid) {
                nearby.requestConnection(endpointName, endpointId, lifecycleCallback)
                    .addOnFailureListener { Log.w(TAG, "requestConnection fallita", it) }
            }
        }

        override fun onEndpointLost(endpointId: String) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val remote = parseName(info.endpointName)
            if (remote == null || remote.channel != channelId.take(8)) {
                nearby.rejectConnection(endpointId)
                return
            }
            pending[endpointId] = remote
            nearby.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val remote = pending.remove(endpointId) ?: return
            if (result.status.isSuccess) {
                endpoints[endpointId] = remote.uid
                updatePeer(remote.uid, remote.nick, nearby = true)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val uid = endpoints.remove(endpointId) ?: return
            if (!endpoints.containsValue(uid)) updatePeer(uid, null, nearby = false)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val uid = endpoints[endpointId] ?: pending[endpointId]?.uid
            when (payload.type) {
                Payload.Type.BYTES -> handleNearbyBytes(uid, payload.asBytes() ?: return)
                Payload.Type.FILE -> incomingFiles[payload.id] = endpointId to payload
                Payload.Type.STREAM -> {
                    val input = payload.asStream()?.asInputStream() ?: return
                    thread(name = "nearby-audio") { receiveNearbyAudio(nickOf(uid), input) }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val entry = incomingFiles[update.payloadId] ?: return
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> {
                    incomingFiles.remove(update.payloadId)
                    viewModelScope.launch(Dispatchers.IO) { saveNearbyPhoto(entry.first, entry.second) }
                }
                PayloadTransferUpdate.Status.FAILURE,
                PayloadTransferUpdate.Status.CANCELED -> {
                    incomingFiles.remove(update.payloadId)
                    photoHeaders.remove(update.payloadId)
                }
            }
        }
    }

    private fun handleNearbyBytes(uid: String?, bytes: ByteArray) {
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() ?: return
        when (json.optString("t")) {
            "chat" -> receiveChat(nickOf(uid), json.optString("mid"), json.optString("text"))
            "photo" -> photoHeaders[json.optLong("pid")] = json.optString("mid")
        }
    }

    /** Il flusso Nearby inizia con l'id della trasmissione, poi c'è il PCM. */
    private fun receiveNearbyAudio(nick: String, input: InputStream) {
        val sid = runCatching { DataInputStream(input).readLong() }.getOrNull()
        if (sid == null || !claimAudio(sid, SRC_NEARBY)) {
            // La stessa trasmissione la stiamo già ascoltando via internet: scarta.
            runCatching { input.use { while (it.read(ByteArray(4096)) >= 0) Unit } }
            return
        }
        _speaking.update { it + nick }
        audio.play(input) {
            audioSessions[sid] = SESSION_ENDED
            _speaking.update { it - nick }
        }
    }

    @Suppress("DEPRECATION")
    private fun saveNearbyPhoto(endpointId: String, payload: Payload) {
        val ctx = getApplication<Application>()
        val nick = nickOf(endpoints[endpointId])
        val mid = photoHeaders.remove(payload.id)
        val file = payload.asFile() ?: return
        val dest = PhotoUtils.newPhotoFile(ctx, "recv")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = file.asUri() ?: return
                if (mid == null || markSeen(mid)) {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                }
                runCatching { ctx.contentResolver.delete(uri, null, null) }
            } else {
                val src = file.asJavaFile() ?: return
                if (mid == null || markSeen(mid)) src.copyTo(dest, overwrite = true)
                src.delete()
            }
            if (dest.exists()) addItem(ChatItem.Photo(nextId(), now(), nick, false, dest))
        } catch (e: Exception) {
            Log.w(TAG, "Salvataggio foto fallito", e)
            addSystem("Foto da $nick non ricevuta")
        }
    }

    // ---------------------------------------------------------------- Internet

    private fun startInternet() {
        _internetState.value = InternetLink.State.CONNECTING
        viewModelScope.launch(Dispatchers.IO) {
            val link = try {
                // PBKDF2 richiede qualche decina di ms: lontano dal thread principale.
                val crypto = ChannelCrypto(settings.channel, settings.password)
                InternetLink(settings.broker, crypto, "wptt-$myUid", internetListener)
            } catch (e: Exception) {
                Log.w(TAG, "Collegamento internet non avviato", e)
                _internetState.value = InternetLink.State.ERROR
                addSystem("Internet non avviato (${settings.broker}): ${e.javaClass.simpleName} ${e.message ?: ""}")
                return@launch
            }
            if (!_started.value) return@launch
            internet = link
            link.connect(will = Packet.Bye(myUid, nickname))
        }
    }

    private val internetListener = object : InternetLink.Listener {
        private var lastState: InternetLink.State? = null

        override fun onState(state: InternetLink.State, detail: String?) {
            if (state == InternetLink.State.OFF) return
            _internetState.value = state
            if (state != lastState) {
                when (state) {
                    InternetLink.State.ONLINE -> addSystem("Collegato via internet")
                    InternetLink.State.ERROR ->
                        if (lastState != InternetLink.State.ERROR) addSystem("Internet non raggiungibile, riprovo…")
                    else -> Unit
                }
            }
            lastState = state
        }

        override fun onConnected() {
            internet?.publish(Packet.Hello(myUid, nickname, wantReply = true))
        }

        override fun onPacket(packet: Packet) {
            if (packet.uid == myUid) return // MQTT ci rimanda anche i nostri messaggi
            if (packet is Packet.Bye) {
                netLastSeen.remove(packet.uid)
                updatePeer(packet.uid, null, internet = false)
                return
            }
            netLastSeen[packet.uid] = now()
            updatePeer(packet.uid, packet.nick, internet = true)
            when (packet) {
                is Packet.Hello -> if (packet.wantReply) {
                    internet?.publish(Packet.Hello(myUid, nickname, wantReply = false))
                }
                is Packet.Chat -> receiveChat(packet.nick, packet.mid, packet.text)
                is Packet.Photo -> receiveInternetPhoto(packet)
                is Packet.Audio -> receiveInternetAudio(packet)
                is Packet.Bye -> Unit
            }
        }
    }

    private fun receiveInternetPhoto(p: Packet.Photo) {
        if (!markSeen(p.mid)) return
        val dest = PhotoUtils.newPhotoFile(getApplication(), "recv")
        runCatching { dest.writeBytes(p.jpeg) }
            .onSuccess { addItem(ChatItem.Photo(nextId(), now(), p.nick, false, dest)) }
            .onFailure { addSystem("Foto da ${p.nick} non ricevuta") }
    }

    private fun receiveInternetAudio(p: Packet.Audio) {
        if (!claimAudio(p.sid, SRC_INTERNET)) return
        var stream = netStreams[p.sid]
        if (stream == null) {
            if (p.end && p.ulaw.isEmpty()) {
                audioSessions[p.sid] = SESSION_ENDED
                return
            }
            val pipe = ParcelFileDescriptor.createPipe()
            stream = NetStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]), now())
            netStreams[p.sid] = stream
            val nick = p.nick
            _speaking.update { it + nick }
            audio.play(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), NET_AUDIO_PREBUFFER_MS) {
                _speaking.update { it - nick }
            }
        }
        stream.lastPacket = now()
        if (p.ulaw.isNotEmpty()) {
            try {
                stream.out.write(VoiceCodec.decode(p.ulaw))
            } catch (e: Exception) {
                closeNetStream(p.sid)
                return
            }
        }
        if (p.end) closeNetStream(p.sid)
    }

    private fun closeNetStream(sid: Long) {
        audioSessions[sid] = SESSION_ENDED
        netStreams.remove(sid)?.let { runCatching { it.out.close() } }
    }

    /** La prima strada da cui arriva una trasmissione vince; l'altra copia viene ignorata. */
    private fun claimAudio(sid: Long, source: Int): Boolean {
        if (audioSessions.size > 500) audioSessions.entries.removeIf { it.value == SESSION_ENDED }
        val owner = audioSessions.putIfAbsent(sid, source) ?: source
        return owner == source
    }

    // ---------------------------------------------------------------- ricezione comune

    private fun receiveChat(nick: String, mid: String, text: String) {
        if (mid.isNotEmpty() && !markSeen(mid)) return
        addItem(ChatItem.Text(nextId(), now(), nick, false, text))
    }

    private fun markSeen(mid: String): Boolean = synchronized(seen) { seen.put(mid, true) == null }

    private fun newMid() = "$myUid-${ids.incrementAndGet()}"

    // ---------------------------------------------------------------- invio

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val mid = newMid()
        addItem(ChatItem.Text(nextId(), now(), nickname, true, clean))
        val targets = endpoints.keys.toList()
        if (targets.isNotEmpty()) {
            val json = JSONObject().put("t", "chat").put("mid", mid).put("text", clean).toString()
            nearby.sendPayload(targets, Payload.fromBytes(json.toByteArray(Charsets.UTF_8)))
        }
        // I messaggi sono minuscoli: via internet li mandiamo sempre (arrivano anche a chi entra ora).
        internet?.publish(Packet.Chat(myUid, nickname, mid, clean))
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
            if (_peers.value.isEmpty()) {
                addSystem("Nessuno connesso: foto non inviata")
                return@launch
            }
            val mid = newMid()
            val targets = endpoints.keys.toList()
            if (targets.isNotEmpty()) {
                val payload = Payload.fromFile(file)
                val header = JSONObject().put("t", "photo").put("mid", mid).put("pid", payload.id).toString()
                nearby.sendPayload(targets, Payload.fromBytes(header.toByteArray(Charsets.UTF_8)))
                nearby.sendPayload(targets, payload)
            }
            if (internetNeeded()) internet?.publish(Packet.Photo(myUid, nickname, mid, file.readBytes()))
        }
    }

    /** Tasto PTT premuto: la voce va a tutti, vicino e/o via internet. */
    fun startTalking() {
        if (_transmitting.value) return
        if (_peers.value.isEmpty()) {
            addSystem("Nessuno in ascolto sul canale")
            return
        }
        val sid = Random.nextLong()
        val targets = endpoints.keys.toList()
        val net = internet?.takeIf { internetNeeded() }

        var nearbyOut: OutputStream? = null
        if (targets.isNotEmpty()) {
            val pipe = ParcelFileDescriptor.createPipe()
            nearby.sendPayload(targets, Payload.fromStream(pipe[0]))
            nearbyOut = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]))
                .apply { writeLong(sid) }
        }

        val chunk = ByteArrayOutputStream()
        var seq = 0
        audio.startRecording(
            onFrame = { pcm, n ->
                nearbyOut?.let { out ->
                    try {
                        out.write(pcm, 0, n)
                        out.flush()
                    } catch (e: Exception) {
                        nearbyOut = null
                    }
                }
                if (net != null) {
                    chunk.write(VoiceCodec.encode(pcm, n))
                    if (chunk.size() >= NET_AUDIO_CHUNK) {
                        net.publish(Packet.Audio(myUid, nickname, sid, seq++, false, chunk.toByteArray()))
                        chunk.reset()
                    }
                }
            },
            onEnd = {
                runCatching { nearbyOut?.close() }
                net?.publish(Packet.Audio(myUid, nickname, sid, seq, true, chunk.toByteArray()))
            },
        )
        _transmitting.value = true
    }

    /** Tasto PTT rilasciato: chiude il flusso (il ricevente sente il "bip"). */
    fun stopTalking() {
        if (!_transmitting.value) return
        audio.stopRecording()
        _transmitting.value = false
    }

    // ---------------------------------------------------------------- utilità

    /** Messaggio di servizio; se è identico all'ultimo non viene ripetuto. */
    private fun addSystem(text: String) = _messages.update { list ->
        val last = list.lastOrNull()
        if (last is ChatItem.System && last.text == text) list else list + ChatItem.System(nextId(), now(), text)
    }

    private fun addItem(item: ChatItem) = _messages.update { it + item }

    private fun nextId() = ids.incrementAndGet()

    private fun now() = System.currentTimeMillis()
}
