package com.walkietalkie.ptt.desktop

import com.walkietalkie.ptt.ChatItem
import com.walkietalkie.ptt.net.ChannelCrypto
import com.walkietalkie.ptt.net.InternetLink
import com.walkietalkie.ptt.net.Packet
import com.walkietalkie.ptt.net.VoiceCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

data class DesktopSettings(
    val nick: String,
    val channel: String,
    val password: String,
    val broker: String,
)

/**
 * Il "telefono" del PC: parla con l'app Android tramite il collegamento internet (broker MQTT),
 * con lo stesso protocollo, la stessa cifratura e lo stesso codec voce (modulo :shared).
 */
class DesktopClient(
    private val audio: AudioIO,
    private val photoDir: File,
    private val heartbeatMs: Long = 15_000,
    private val peerTimeoutMs: Long = 45_000,
) {
    private companion object {
        const val AUDIO_TIMEOUT_MS = 2_500L
        const val AUDIO_CHUNK = 800 // byte µ-law = 100 ms, come l'app Android
    }

    val uid: String = UUID.randomUUID().toString().replace("-", "").take(8)
    private val ids = AtomicLong()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var housekeeping: Job? = null
    private var link: InternetLink? = null
    private var settings = DesktopSettings("", "", "", "")
    private val nick get() = settings.nick

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()

    /** uid -> nome dei telefoni (e PC) connessi. */
    private val _peers = MutableStateFlow<Map<String, String>>(emptyMap())
    val peers: StateFlow<Map<String, String>> = _peers.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatItem>>(emptyList())
    val messages: StateFlow<List<ChatItem>> = _messages.asStateFlow()

    private val _speaking = MutableStateFlow<Set<String>>(emptySet())
    val speaking: StateFlow<Set<String>> = _speaking.asStateFlow()

    private val _transmitting = MutableStateFlow(false)
    val transmitting: StateFlow<Boolean> = _transmitting.asStateFlow()

    private val _linkState = MutableStateFlow<InternetLink.State?>(null)
    val linkState: StateFlow<InternetLink.State?> = _linkState.asStateFlow()

    val channelName get() = settings.channel

    private val lastSeen = ConcurrentHashMap<String, Long>()
    private val seen = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 1000
    }

    private class Session(val player: AudioIO.Player, @Volatile var last: Long)

    private val sessions = ConcurrentHashMap<Long, Session>()
    private val endedSessions = object : LinkedHashMap<Long, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) = size > 500
    }

    // ---------------------------------------------------------------- sessione

    fun start(s: DesktopSettings) {
        if (_started.value) return
        settings = s.copy(
            nick = s.nick.trim().replace("|", "").ifEmpty { "PC" },
            channel = s.channel.trim().ifEmpty { "1" },
            broker = s.broker.trim(),
        )
        photoDir.mkdirs()
        _started.value = true
        addSystem("Sei sul canale \"${settings.channel}\" come $nick. In attesa dei telefoni…")
        _linkState.value = InternetLink.State.CONNECTING

        val newLink = try {
            InternetLink(settings.broker, ChannelCrypto(settings.channel, settings.password), "wptt-$uid", listener)
        } catch (e: Exception) {
            _linkState.value = InternetLink.State.ERROR
            addSystem("Indirizzo del server non valido: ${settings.broker}")
            return
        }
        link = newLink
        newLink.connect(will = Packet.Bye(uid, nick))

        housekeeping = scope.launch {
            var sinceHeartbeat = 0L
            while (isActive) {
                delay(500)
                sinceHeartbeat += 500
                if (sinceHeartbeat >= heartbeatMs) {
                    sinceHeartbeat = 0
                    link?.publish(Packet.Hello(uid, nick, wantReply = false))
                }
                val now = now()
                lastSeen.forEach { (peer, last) -> if (now - last > peerTimeoutMs) removePeer(peer) }
                sessions.forEach { (sid, s) -> if (now - s.last > AUDIO_TIMEOUT_MS) endSession(sid) }
            }
        }
    }

    fun stop() {
        if (!_started.value) return
        stopTalking()
        housekeeping?.cancel()
        link?.close(Packet.Bye(uid, nick))
        link = null
        sessions.keys.forEach(::endSession)
        lastSeen.clear()
        synchronized(seen) { seen.clear() }
        _peers.value = emptyMap()
        _speaking.value = emptySet()
        _messages.value = emptyList()
        _linkState.value = null
        photoDir.listFiles()?.forEach { it.delete() }
        _started.value = false
    }

    // ---------------------------------------------------------------- ricezione

    private val listener = object : InternetLink.Listener {
        private var lastState: InternetLink.State? = null

        override fun onState(state: InternetLink.State, detail: String?) {
            if (state == InternetLink.State.OFF) return
            _linkState.value = state
            if (state != lastState) {
                when (state) {
                    InternetLink.State.ONLINE -> addSystem("Collegato via internet")
                    InternetLink.State.ERROR ->
                        if (lastState != InternetLink.State.ERROR) addSystem("Server non raggiungibile, riprovo… ${detail ?: ""}")
                    else -> Unit
                }
            }
            lastState = state
        }

        override fun onConnected() {
            link?.publish(Packet.Hello(uid, nick, wantReply = true))
        }

        override fun onPacket(packet: Packet) {
            if (packet.uid == uid) return
            if (packet is Packet.Bye) {
                removePeer(packet.uid)
                return
            }
            lastSeen[packet.uid] = now()
            addPeer(packet.uid, packet.nick)
            when (packet) {
                is Packet.Hello -> if (packet.wantReply) link?.publish(Packet.Hello(uid, nick, wantReply = false))
                is Packet.Chat -> if (markSeen(packet.mid)) {
                    addItem(ChatItem.Text(ids.incrementAndGet(), now(), packet.nick, false, packet.text))
                }
                is Packet.Photo -> if (markSeen(packet.mid)) {
                    val file = File(photoDir, "recv_${System.currentTimeMillis()}_${packet.mid}.jpg")
                    runCatching { file.writeBytes(packet.jpeg) }
                        .onSuccess { addItem(ChatItem.Photo(ids.incrementAndGet(), now(), packet.nick, false, file)) }
                }
                is Packet.Audio -> receiveAudio(packet)
                is Packet.Bye -> Unit
            }
        }
    }

    private fun addPeer(peer: String, name: String) {
        var added = false
        _peers.update { map ->
            added = map[peer] == null
            if (map[peer] == name) map else map + (peer to name)
        }
        if (added) addSystem("$name si è connesso")
    }

    private fun removePeer(peer: String) {
        lastSeen.remove(peer)
        var name: String? = null
        _peers.update { map ->
            name = map[peer]
            map - peer
        }
        name?.let { n ->
            addSystem("$n si è disconnesso")
            _speaking.update { it - n }
        }
    }

    private fun receiveAudio(p: Packet.Audio) {
        if (synchronized(endedSessions) { endedSessions.containsKey(p.sid) }) return
        var session = sessions[p.sid]
        if (session == null) {
            if (p.end && p.ulaw.isEmpty()) return
            val speaker = p.nick
            _speaking.update { it + speaker }
            session = Session(audio.createPlayer { _speaking.update { it - speaker } }, now())
            sessions[p.sid] = session
        }
        session.last = now()
        if (p.ulaw.isNotEmpty()) session.player.write(VoiceCodec.decode(p.ulaw))
        if (p.end) endSession(p.sid)
    }

    private fun endSession(sid: Long) {
        synchronized(endedSessions) { endedSessions[sid] = true }
        sessions.remove(sid)?.player?.end()
    }

    // ---------------------------------------------------------------- invio

    fun sendText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val mid = newMid()
        addItem(ChatItem.Text(ids.incrementAndGet(), now(), nick, true, clean))
        if (link?.publish(Packet.Chat(uid, nick, mid, clean)) != true) addSystem("Non collegato: messaggio non inviato")
    }

    fun sendPhoto(file: File) {
        scope.launch(Dispatchers.IO) {
            val jpeg = Photos.compressForSending(file) ?: run {
                addSystem("Impossibile leggere l'immagine ${file.name}")
                return@launch
            }
            val copy = File(photoDir, "sent_${System.currentTimeMillis()}.jpg").apply { writeBytes(jpeg) }
            addItem(ChatItem.Photo(ids.incrementAndGet(), now(), nick, true, copy))
            when {
                _peers.value.isEmpty() -> addSystem("Nessuno connesso: foto non inviata")
                link?.publish(Packet.Photo(uid, nick, newMid(), jpeg)) != true -> addSystem("Non collegato: foto non inviata")
            }
        }
    }

    /** Tasto PTT premuto: la voce va a tutti i telefoni del canale. */
    fun startTalking() {
        if (_transmitting.value) return
        val net = link?.takeIf { it.isOnline }
        if (net == null || _peers.value.isEmpty()) {
            addSystem("Nessuno in ascolto sul canale")
            return
        }
        val sid = Random.nextLong()
        val chunk = ByteArrayOutputStream()
        var seq = 0
        val ok = audio.startCapture(
            onPcm = { pcm, n ->
                chunk.write(VoiceCodec.encode(pcm, n))
                if (chunk.size() >= AUDIO_CHUNK) {
                    net.publish(Packet.Audio(uid, nick, sid, seq++, false, chunk.toByteArray()))
                    chunk.reset()
                }
            },
            onEnd = { net.publish(Packet.Audio(uid, nick, sid, seq, true, chunk.toByteArray())) },
        )
        if (ok) _transmitting.value = true else addSystem("Microfono non disponibile")
    }

    /** Avviso una tantum dall'audio (es. altoparlanti mancanti). */
    private var audioWarned = false

    @Suppress("UNUSED_PARAMETER") // il dettaglio tecnico finisce già nel log
    fun audioProblem(message: String) {
        if (audioWarned) return
        audioWarned = true
        addSystem("Altoparlanti non disponibili: la voce in arrivo non si sente")
    }

    fun stopTalking() {
        if (!_transmitting.value) return
        audio.stopCapture()
        _transmitting.value = false
    }

    // ---------------------------------------------------------------- utilità

    private fun markSeen(mid: String): Boolean = synchronized(seen) { seen.put(mid, true) == null }

    private fun newMid() = "$uid-${ids.incrementAndGet()}"

    private fun addSystem(text: String) = addItem(ChatItem.System(ids.incrementAndGet(), now(), text))

    private fun addItem(item: ChatItem) = _messages.update { it + item }

    private fun now() = System.currentTimeMillis()
}
