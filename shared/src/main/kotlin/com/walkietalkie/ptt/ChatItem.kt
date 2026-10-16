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

    /** Allarme SOS (inviato o ricevuto), con la posizione se disponibile. */
    data class Sos(
        override val id: Long,
        override val time: Long,
        val from: String,
        val mine: Boolean,
        val lat: Double?,
        val lon: Double?,
        val accuracyM: Float?,
    ) : ChatItem() {
        val hasLocation get() = lat != null && lon != null

        /** Link che apre la posizione nelle mappe (Google Maps su telefono e browser). */
        val mapsUrl get() = if (hasLocation) "https://maps.google.com/?q=$lat,$lon" else null
    }

    /** Messaggi di servizio: "Mario si è connesso", ecc. */
    data class System(
        override val id: Long,
        override val time: Long,
        val text: String,
    ) : ChatItem()
}
