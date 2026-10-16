package com.walkietalkie.ptt

/**
 * Informazioni sull'app, disclaimer e regole di buon senso: lo stesso testo su telefono e PC.
 */
object About {

    const val APP_NAME = "Walkie PTT"
    const val AUTHOR = "Gianluca Colotti"

    data class Section(val title: String, val paragraphs: List<String> = emptyList(), val bullets: List<String> = emptyList())

    val credits = "$APP_NAME è stata ideata e realizzata da $AUTHOR."

    val sections = listOf(
        Section(
            "Cos'è",
            paragraphs = listOf(
                "$APP_NAME trasforma telefoni e computer in ricetrasmittenti push-to-talk, con minichat, " +
                    "scambio di foto e pulsante SOS. Funziona con i dispositivi vicini (Bluetooth/Wi-Fi, " +
                    "senza internet) e via internet (rete cellulare o Wi-Fi).",
                "È un'app sperimentale, fornita gratuitamente \"così com'è\", senza garanzie di alcun tipo " +
                    "sul funzionamento, sulla disponibilità o sulla consegna dei messaggi.",
            ),
        ),
        Section(
            "⚠️ Non è un servizio di emergenza",
            paragraphs = listOf(
                "Il pulsante SOS è un aiuto tra le persone del tuo canale e NON sostituisce in nessun caso " +
                    "i servizi di emergenza. In caso di pericolo chiama subito il 112 (Numero Unico di Emergenza).",
                "Un SOS può non arrivare o arrivare in ritardo: rete assente, batteria scarica, app chiusa o " +
                    "sospesa dal telefono, server non raggiungibile, GPS impreciso o non disponibile. " +
                    "Non fare affidamento esclusivo su questa app per la tua sicurezza o quella di altri.",
            ),
        ),
        Section(
            "Rete, server e privacy",
            bullets = listOf(
                "Il collegamento internet usa un server MQTT pubblico di terze parti (predefinito: HiveMQ), " +
                    "non gestito dall'autore e senza garanzie di disponibilità.",
                "Voce, messaggi, foto e SOS che passano da internet sono cifrati end-to-end con una chiave " +
                    "ricavata da nome del canale e password. Senza password chiunque usi lo stesso nome di " +
                    "canale può ascoltare: per conversazioni private usa sempre una password.",
                "La posizione GPS viene inviata solo quando premi SOS, e solo ai dispositivi del canale.",
                "L'autore non raccoglie, non conserva e non vende dati. Messaggi e foto restano solo sui " +
                    "dispositivi e vengono cancellati quando esci dal canale.",
                "I suoni (selettive, sirene, bip) sono sintetizzati dall'app: non sono registrazioni di " +
                    "apparati reali e l'app non comunica con radio di servizio.",
            ),
        ),
        Section(
            "Uso consentito",
            bullets = listOf(
                "Usa l'app nel rispetto delle leggi e dei diritti altrui.",
                "Non registrare né diffondere conversazioni, foto o posizioni di altre persone senza il loro consenso.",
                "Non inviare contenuti offensivi, illeciti o che violano la privacy.",
                "Non usare l'app per spacciarti per forze dell'ordine o servizi di soccorso, né per fare scherzi con l'SOS.",
            ),
        ),
        Section(
            "📻 Regole di buon senso",
            bullets = listOf(
                "Mai usare il telefono mentre guidi: accosta in sicurezza prima di parlare o leggere.",
                "Usa l'SOS solo per vere emergenze: un falso allarme spaventa gli altri e fa perdere fiducia nel pulsante.",
                "Se ricevi un SOS: rispondi, cerca di contattare la persona e, se serve, chiama tu il 112 dando la posizione.",
                "Parla uno alla volta: aspetta che l'altro abbia finito (lo senti dal bip di fine) prima di premere il tasto.",
                "Premi, aspetta un istante, poi parla: messaggi brevi e chiari, e chiudi con \"passo\" o \"chiudo\".",
                "Controlla che il codice chiave 🔑 sia uguale su tutti i dispositivi del canale.",
                "Condividi la password del canale solo con chi deve farne parte.",
                "In luoghi pubblici abbassa il volume o usa gli auricolari, e rispetta chi ti sta intorno.",
                "Non inviare foto di persone senza il loro permesso.",
                "Con l'app attiva in background il telefono consuma più batteria e dati (circa 0,5 MB per minuto di voce via internet): tienilo carico.",
            ),
        ),
        Section(
            "Responsabilità",
            paragraphs = listOf(
                "Usando l'app accetti questi termini. Nei limiti consentiti dalla legge, l'autore non è " +
                    "responsabile per danni diretti o indiretti derivanti dall'uso, dal mancato funzionamento " +
                    "o dall'impossibilità di usare l'app, né per l'uso che ne fanno gli utenti.",
            ),
        ),
    )
}
