package com.walkietalkie.ptt.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Messaggi scambiati via internet (broker MQTT). Ogni pacchetto porta l'id del mittente
 * così i doppioni arrivati anche via Nearby possono essere scartati.
 */
sealed class Packet {
    abstract val uid: String
    abstract val nick: String

    /**
     * Presenza: "ci sono". Il nuovo arrivato manda [wantReply] = true e gli altri rispondono
     * subito, così si scoprono in un attimo; i battiti periodici hanno [wantReply] = false.
     */
    data class Hello(override val uid: String, override val nick: String, val wantReply: Boolean) : Packet()

    /** Uscita dal canale (inviato anche dal broker come "last will" se la connessione cade). */
    data class Bye(override val uid: String, override val nick: String) : Packet()

    data class Chat(override val uid: String, override val nick: String, val mid: String, val text: String) : Packet()

    class Photo(override val uid: String, override val nick: String, val mid: String, val jpeg: ByteArray) : Packet()

    /** Un pezzo (~100 ms) di una trasmissione vocale [sid]; [end] = tasto rilasciato. */
    class Audio(
        override val uid: String,
        override val nick: String,
        val sid: Long,
        val seq: Int,
        val end: Boolean,
        val ulaw: ByteArray,
    ) : Packet()

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeByte(VERSION)
            out.writeByte(
                when (this) {
                    is Hello -> T_HELLO
                    is Bye -> T_BYE
                    is Chat -> T_CHAT
                    is Photo -> T_PHOTO
                    is Audio -> T_AUDIO
                }
            )
            out.writeUTF(uid)
            out.writeUTF(nick)
            when (this) {
                is Hello -> out.writeBoolean(wantReply)
                is Bye -> Unit
                is Chat -> {
                    out.writeUTF(mid)
                    out.writeUTF(text)
                }
                is Photo -> {
                    out.writeUTF(mid)
                    out.writeInt(jpeg.size)
                    out.write(jpeg)
                }
                is Audio -> {
                    out.writeLong(sid)
                    out.writeInt(seq)
                    out.writeBoolean(end)
                    out.writeInt(ulaw.size)
                    out.write(ulaw)
                }
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val VERSION = 1
        private const val T_HELLO = 1
        private const val T_BYE = 2
        private const val T_CHAT = 3
        private const val T_PHOTO = 4
        private const val T_AUDIO = 5
        private const val MAX_BLOB = 8 * 1024 * 1024

        /** Restituisce null per pacchetti malformati o di una versione sconosciuta. */
        fun decode(data: ByteArray): Packet? = runCatching {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                if (input.readUnsignedByte() != VERSION) return null
                val type = input.readUnsignedByte()
                val uid = input.readUTF()
                val nick = input.readUTF()
                fun blob(): ByteArray {
                    val size = input.readInt()
                    require(size in 0..MAX_BLOB)
                    return ByteArray(size).also { input.readFully(it) }
                }
                when (type) {
                    T_HELLO -> Hello(uid, nick, input.readBoolean())
                    T_BYE -> Bye(uid, nick)
                    T_CHAT -> Chat(uid, nick, input.readUTF(), input.readUTF())
                    T_PHOTO -> Photo(uid, nick, input.readUTF(), blob())
                    T_AUDIO -> Audio(uid, nick, input.readLong(), input.readInt(), input.readBoolean(), blob())
                    else -> null
                }
            }
        }.getOrNull()
    }
}
