package com.walkietalkie.ptt.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Test
import java.security.Security

class ChannelCryptoTest {

    @Test
    fun emptyPasswordWorksWithAndroidCryptoProvider() {
        // Su Android SecretKeyFactory "PBKDF2WithHmacSHA256" viene da BouncyCastle, che rifiuta
        // le password vuote ("password empty"): era la causa di "Indirizzo del server non valido".
        // Si toglie SunJCE perché altrimenti Java ripiega su di lui in silenzio (Android non lo ha).
        val sunJce = Security.getProvider("SunJCE")
        Security.removeProvider("SunJCE")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        try {
            val a = ChannelCrypto("1", "")
            val b = ChannelCrypto("1", "")
            assertArrayEquals("ciao".toByteArray(), b.decrypt(a.encrypt("ciao".toByteArray())))
            // E deve restare compatibile con il PC (che usa SunJCE): stessa chiave, stesso topic.
            Security.insertProviderAt(sunJce, 2)
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            val pc = ChannelCrypto("1", "")
            assertEquals(a.topic, pc.topic)
            assertArrayEquals("ciao".toByteArray(), pc.decrypt(a.encrypt("ciao".toByteArray())))
        } finally {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            if (Security.getProvider("SunJCE") == null) Security.addProvider(sunJce)
        }
    }

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
