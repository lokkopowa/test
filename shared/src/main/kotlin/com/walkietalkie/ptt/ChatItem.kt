package com.walkietalkie.ptt

import java.io.File

/** Un elemento della minichat. */
sealed class ChatItem {
    abstract val id: Long
    abstract val time: Long

    data class Text(
        override val id: Long,
        override val time: Long,
        val from: String,
        val mine: Boolean,
        val text: String,
    ) : ChatItem()

    data class Photo(
        override val id: Long,
        override val time: Long,
        val from: String,
        val mine: Boolean,
        val file: File,
    ) : ChatItem()

    /** Messaggi di servizio: "Mario si è connesso", ecc. */
    data class System(
        override val id: Long,
        override val time: Long,
        val text: String,
    ) : ChatItem()
}
