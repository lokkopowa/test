package com.walkietalkie.ptt.desktop

/** Microfono e altoparlanti, astratti per poter testare [DesktopClient] senza scheda audio. */
interface AudioIO {
    /**
     * Inizia a registrare PCM 16 kHz / 16 bit / mono (little endian), passandolo a [onPcm].
     * Restituisce false se il microfono non è disponibile.
     */
    fun startCapture(onPcm: (ByteArray, Int) -> Unit, onEnd: () -> Unit): Boolean

    fun stopCapture()

    /** Un "altoparlante" per una trasmissione in arrivo; [onFinished] quando ha finito di suonare. */
    fun createPlayer(onFinished: () -> Unit): Player

    /** Sirena di allarme per un SOS ricevuto. */
    fun alarm() = Unit

    interface Player {
        /** PCM 16 kHz / 16 bit / mono, little endian. */
        fun write(pcm: ByteArray)

        /** Fine trasmissione: suona quello che resta, poi il "bip". */
        fun end()
    }
}
