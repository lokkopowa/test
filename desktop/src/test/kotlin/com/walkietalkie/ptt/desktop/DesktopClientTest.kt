package com.walkietalkie.ptt.desktop

import com.walkietalkie.ptt.ChatItem
import com.walkietalkie.ptt.net.ChannelCrypto
import com.walkietalkie.ptt.net.InternetLink
import com.walkietalkie.ptt.net.Packet
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Il PC contro un "telefono" simulato che usa il collegamento internet esattamente come
 * l'app Android (stesso InternetLink, stessi pacchetti), passando da un broker MQTT locale.
 */
class DesktopClientTest {

    private lateinit var broker: Server
    private var port = 0
    private lateinit var audio: FakeAudio
    private lateinit var pc: DesktopClient
    private lateinit var phone: InternetLink
    private val fromPc = CopyOnWriteArrayList<Packet>()
    private val phoneConnected = CountDownLatch(1)

    private class FakePlayer(private val onFinished: () -> Unit) : AudioIO.Player {
        val written = CopyOnWriteArrayList<ByteArray>()
        @Volatile var ended = false
        override fun write(pcm: ByteArray) {
            written += pcm
        }
        override fun end() {
            ended = true
            onFinished()
        }
    }

    private class FakeAudio : AudioIO {
        val players = CopyOnWriteArrayList<FakePlayer>()
        private var onEnd: (() -> Unit)? = null
        override fun startCapture(onPcm: (ByteArray, Int) -> Unit, onEnd: () -> Unit): Boolean {
            repeat(10) { onPcm(ByteArray(640) { i -> (i * 7).toByte() }, 640) } // 200 ms di "voce"
            this.onEnd = onEnd
            return true
        }
        override fun stopCapture() {
            onEnd?.invoke()
            onEnd = null
        }
        override fun createPlayer(onFinished: () -> Unit) = FakePlayer(onFinished).also { players += it }
    }

    @Before
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        val props = Properties().apply {
            setProperty("host", "127.0.0.1")
            setProperty("port", port.toString())
            setProperty("websocket_port", "disabled")
            setProperty("allow_anonymous", "true")
            setProperty("persistence_enabled", "false")
            setProperty("data_path", Files.createTempDirectory("moquette").toString())
            setProperty("netty.mqtt.message_size", (2 * 1024 * 1024).toString())
        }
        broker = Server()
        broker.startServer(MemoryConfig(props))
        audio = FakeAudio()
        pc = DesktopClient(audio, Files.createTempDirectory("pc-photos").toFile(), heartbeatMs = 1_000, peerTimeoutMs = 3_000)
        pc.start(DesktopSettings("Ufficio", "squadra", "segreta", "tcp://127.0.0.1:$port"))
        waitFor { pc.linkState.value == InternetLink.State.ONLINE }

        // Il telefono si collega come fa WalkieViewModel: Hello(wantReply = true) appena online.
        phone = InternetLink("tcp://127.0.0.1:$port", ChannelCrypto("squadra", "segreta"), "wptt-phone", object : InternetLink.Listener {
            override fun onState(state: InternetLink.State, detail: String?) = Unit
            override fun onConnected() {
                phone.publish(Packet.Hello("phone", "Mario", wantReply = true))
                phoneConnected.countDown()
            }
            override fun onPacket(packet: Packet) {
                if (packet.uid != "phone") fromPc += packet
            }
        })
        phone.connect(will = Packet.Bye("phone", "Mario"))
        assertTrue(phoneConnected.await(10, TimeUnit.SECONDS))
        waitFor { pc.peers.value.isNotEmpty() && fromPc.any { it is Packet.Hello } }
    }

    @After
    fun tearDown() {
        pc.stop()
        phone.close(null)
        broker.stopServer()
    }

    private fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("condizione non raggiunta in $timeoutMs ms", cond())
    }

    @Test
    fun pcAndPhoneSeeEachOther() {
        assertEquals(mapOf("phone" to "Mario"), pc.peers.value)
        val hello = fromPc.filterIsInstance<Packet.Hello>().first()
        assertEquals("Ufficio", hello.nick)
        assertEquals(pc.uid, hello.uid)
    }

    @Test
    fun chatBothWaysWithoutDuplicates() {
        pc.sendText("ciao dal PC")
        waitFor { fromPc.any { it is Packet.Chat } }
        assertEquals("ciao dal PC", fromPc.filterIsInstance<Packet.Chat>().single().text)

        // Lo stesso messaggio arrivato due volte (QoS 1 può duplicare) compare una volta sola.
        repeat(2) { phone.publish(Packet.Chat("phone", "Mario", "phone-1", "ciao dal telefono")) }
        waitFor { pc.messages.value.any { it is ChatItem.Text && !it.mine } }
        Thread.sleep(300)
        val received = pc.messages.value.filterIsInstance<ChatItem.Text>().filter { !it.mine }
        assertEquals(listOf("ciao dal telefono"), received.map { it.text })
        assertEquals("Mario", received.single().from)
    }

    @Test
    fun pcVoiceReachesPhoneInAndroidFormat() {
        pc.startTalking()
        assertTrue(pc.transmitting.value)
        pc.stopTalking()
        waitFor { fromPc.any { it is Packet.Audio && it.end } }
        val audio = fromPc.filterIsInstance<Packet.Audio>()
        // 10 frame da 20 ms = 200 ms = 1600 byte µ-law, in pezzi da 800 come l'app Android
        assertEquals(listOf(0, 1, 2), audio.map { it.seq })
        assertEquals(listOf(800, 800, 0), audio.map { it.ulaw.size })
        assertEquals(1, audio.map { it.sid }.distinct().size)
        assertTrue(audio.last().end)
    }

    @Test
    fun phoneVoiceIsPlayedOnPc() {
        repeat(3) { seq -> phone.publish(Packet.Audio("phone", "Mario", 7L, seq, false, ByteArray(800))) }
        waitFor { audio.players.size == 1 && audio.players[0].written.size == 3 }
        assertEquals(setOf("Mario"), pc.speaking.value)
        assertEquals(3200, audio.players[0].written[0].size) // 100 ms a 16 kHz / 16 bit

        phone.publish(Packet.Audio("phone", "Mario", 7L, 3, true, ByteArray(0)))
        waitFor { audio.players[0].ended }
        waitFor { pc.speaking.value.isEmpty() }
        // Pacchetti in ritardo della stessa trasmissione non riaprono l'audio.
        phone.publish(Packet.Audio("phone", "Mario", 7L, 4, false, ByteArray(800)))
        Thread.sleep(300)
        assertEquals(1, audio.players.size)
    }

    @Test
    fun photosAreResizedAndExchanged() {
        val big = File.createTempFile("foto", ".png").apply {
            ImageIO.write(BufferedImage(3000, 2000, BufferedImage.TYPE_INT_ARGB), "png", this)
        }
        pc.sendPhoto(big)
        waitFor(10_000) { fromPc.any { it is Packet.Photo } }
        val sent = ImageIO.read(ByteArrayInputStream(fromPc.filterIsInstance<Packet.Photo>().single().jpeg))
        assertEquals(1280, sent.width)
        assertEquals(853, sent.height)

        phone.publish(Packet.Photo("phone", "Mario", "phone-9", fromPc.filterIsInstance<Packet.Photo>().single().jpeg))
        waitFor { pc.messages.value.any { it is ChatItem.Photo && !it.mine } }
        val photo = pc.messages.value.filterIsInstance<ChatItem.Photo>().single { !it.mine }
        assertTrue(photo.file.exists())
        assertEquals("Mario", photo.from)
    }

    @Test
    fun phoneLeavingIsNoticed() {
        phone.publish(Packet.Bye("phone", "Mario"))
        waitFor { pc.peers.value.isEmpty() }
        assertTrue(pc.messages.value.any { it is ChatItem.System && it.text.contains("Mario si è disconnesso") })
    }

    @Test
    fun silentPhoneTimesOut() {
        phone.close(null) // niente Bye esplicito e niente più battiti
        waitFor(8_000) { pc.peers.value.isEmpty() }
    }
}
