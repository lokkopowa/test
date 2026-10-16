package com.walkietalkie.ptt.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelCryptoTest {

    @Test
    fun sameChannelAndPasswordCanTalk() {
        val a = ChannelCrypto("squadra", "segreta")
        val b = ChannelCrypto("squadra", "segreta")
        assertEquals(a.topic, b.topic)
        val msg = "ciao".toByteArray()
        assertArrayEquals(msg, b.decrypt(a.encrypt(msg)))
    }

    @Test
    fun wrongPasswordCannotRead() {
        val a = ChannelCrypto("squadra", "segreta")
        val other = ChannelCrypto("squadra", "sbagliata")
        assertNotEquals(a.topic, other.topic)
        assertNull(other.decrypt(a.encrypt("ciao".toByteArray())))
    }

    @Test
    fun topicDoesNotRevealChannel() {
        val topic = ChannelCrypto("squadra", "segreta").topic
        assertFalse(topic.contains("squadra"))
        assertFalse(topic.contains("segreta"))
    }

    @Test
    fun tamperedDataIsRejected() {
        val a = ChannelCrypto("c", "p")
        val data = a.encrypt("ciao".toByteArray())
        data[data.size - 1] = (data[data.size - 1].toInt() xor 1).toByte()
        assertNull(a.decrypt(data))
        assertNull(a.decrypt(byteArrayOf(1, 2, 3)))
    }
}
