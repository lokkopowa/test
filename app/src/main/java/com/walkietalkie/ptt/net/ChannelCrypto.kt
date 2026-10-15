package com.walkietalkie.ptt.net

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifratura end-to-end per il canale internet: il broker MQTT pubblico vede solo byte cifrati
 * e un topic "hash" da cui non si ricava il nome del canale.
 * Chiave = PBKDF2(canale + password). Senza password chiunque conosca il nome del canale può
 * ascoltare: per conversazioni private usa una password.
 */
class ChannelCrypto(channel: String, password: String) {

    /** Topic MQTT su cui pubblicano tutti i telefoni dello stesso canale. */
    val topic: String = "walkieptt/v1/" + channelId(channel, password)

    private val key = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                PBEKeySpec(password.toCharArray(), "walkieptt|key|$channel".toByteArray(), 10_000, 256)
            ).encoded,
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** null se il messaggio non è stato cifrato con la stessa chiave (o è stato alterato). */
    fun decrypt(data: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE))
        cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
    }.getOrNull()

    companion object {
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128

        /** Identificativo del canale (nome + password) che non rivela né l'uno né l'altra. */
        fun channelId(channel: String, password: String): String =
            sha256Hex("channel|$channel|$password").take(32)

        private fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
