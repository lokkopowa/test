package com.walkietalkie.ptt.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketTest {

    private fun roundTrip(p: Packet): Packet = Packet.decode(p.encode())!!

    @Test
    fun helloAndChatRoundTrip() {
        assertEquals(Packet.Hello("u1", "Mario", true), roundTrip(Packet.Hello("u1", "Mario", true)))
        assertEquals(Packet.Bye("u1", "Mario"), roundTrip(Packet.Bye("u1", "Mario")))
        assertEquals(
            Packet.Chat("u1", "Mario", "u1-7", "Ciao à tutti 👋"),
            roundTrip(Packet.Chat("u1", "Mario", "u1-7", "Ciao à tutti 👋")),
        )
    }

    @Test
    fun photoAndAudioRoundTrip() {
        val jpeg = ByteArray(200_000) { it.toByte() }
        val photo = roundTrip(Packet.Photo("u2", "Luca", "u2-1", jpeg)) as Packet.Photo
        assertEquals("u2-1", photo.mid)
        assertArrayEquals(jpeg, photo.jpeg)

        val audio = roundTrip(Packet.Audio("u2", "Luca", -42L, 3, true, byteArrayOf(1, 2, 3))) as Packet.Audio
        assertEquals(-42L, audio.sid)
        assertEquals(3, audio.seq)
        assertTrue(audio.end)
        assertArrayEquals(byteArrayOf(1, 2, 3), audio.ulaw)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(Packet.decode(byteArrayOf()))
        assertNull(Packet.decode(byteArrayOf(9, 9, 9)))
        assertNull(Packet.decode(Packet.Chat("u", "n", "m", "t").encode().copyOf(6)))
    }
}
