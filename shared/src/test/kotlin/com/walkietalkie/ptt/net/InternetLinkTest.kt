package com.walkietalkie.ptt.net

import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Prova end-to-end del collegamento internet contro un broker MQTT locale. */
class InternetLinkTest {

    private lateinit var broker: Server
    private var port = 0
    private val links = mutableListOf<InternetLink>()

    private class Recorder : InternetLink.Listener {
        val packets = CopyOnWriteArrayList<Packet>()
        val connected = CountDownLatch(1)
        override fun onState(state: InternetLink.State, detail: String?) = Unit
        override fun onConnected() = connected.countDown()
        override fun onPacket(packet: Packet) {
            packets += packet
        }
    }

    @Before
    fun startBroker() {
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
    }

    @After
    fun stopBroker() {
        links.forEach { it.close(null) }
        broker.stopServer()
    }

    private fun link(id: String, channel: String, password: String, rec: Recorder): InternetLink =
        InternetLink("tcp://127.0.0.1:$port", ChannelCrypto(channel, password), id, rec).also {
            links += it
            it.connect(will = Packet.Bye(id, id))
            assertTrue("$id non si è collegato", rec.connected.await(10, TimeUnit.SECONDS))
        }

    private fun waitFor(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
    }

    @Test
    fun phonesOnSameChannelExchangeChatPhotoAndVoice() {
        val recA = Recorder()
        val recB = Recorder()
        val recIntruder = Recorder()
        val a = link("a", "squadra", "segreta", recA)
        link("b", "squadra", "segreta", recB)
        link("x", "squadra", "altra", recIntruder)

        repeat(10) { seq -> a.publish(Packet.Audio("a", "Anna", 99L, seq, seq == 9, ByteArray(800))) }
        waitFor { recB.packets.size >= 10 }
        val audio = recB.packets.filterIsInstance<Packet.Audio>()
        assertEquals((0 until 10).toList(), audio.map { it.seq }) // in ordine
        assertTrue(audio.last().end)

        a.publish(Packet.Chat("a", "Anna", "a-1", "ciao"))
        a.publish(Packet.Photo("a", "Anna", "a-2", ByteArray(300_000) { 7 }))
        waitFor { recB.packets.size >= 12 }
        assertEquals("ciao", recB.packets.filterIsInstance<Packet.Chat>().single().text)
        assertEquals(300_000, recB.packets.filterIsInstance<Packet.Photo>().single().jpeg.size)

        // Il mittente riceve anche i propri pacchetti (il ViewModel li scarta per uid)
        assertTrue(recA.packets.all { it.uid == "a" })
        // Con un'altra password non arriva nulla
        Thread.sleep(300)
        assertTrue(recIntruder.packets.isEmpty())
    }

    @Test
    fun chatIsNotLostBehindABigPhoto() {
        val recA = Recorder()
        val recB = Recorder()
        val a = link("a", "c", "p", recA)
        link("b", "c", "p", recB)
        repeat(3) { i ->
            a.publish(Packet.Photo("a", "Anna", "p$i", ByteArray(400_000)))
            repeat(20) { j -> a.publish(Packet.Chat("a", "Anna", "c$i-$j", "msg $j")) }
        }
        waitFor(10_000) { recB.packets.size >= 63 }
        assertEquals(3, recB.packets.filterIsInstance<Packet.Photo>().map { it.mid }.distinct().size)
        assertEquals(60, recB.packets.filterIsInstance<Packet.Chat>().map { it.mid }.distinct().size)
    }

    @Test
    fun brokerDeliversByeWhenPhoneDisappears() {
        val recA = Recorder()
        val recB = Recorder()
        link("a", "c", "p", recA)
        val b = link("b", "c", "p", recB)
        // Chiusura brusca: il broker deve pubblicare il "last will" (Bye) di b.
        val client = InternetLink::class.java.getDeclaredField("client").apply { isAccessible = true }
            .get(b) as org.eclipse.paho.client.mqttv3.MqttAsyncClient
        links.remove(b)
        client.disconnectForcibly(0, 0, false)
        waitFor { recA.packets.any { it is Packet.Bye && it.uid == "b" } }
        assertTrue(recA.packets.any { it is Packet.Bye && it.uid == "b" })
    }
}
