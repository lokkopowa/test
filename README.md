# Walkie PTT 📻

*Creata da Gianluca Colotti.*

App Android semplice che trasforma il telefono in un **walkie talkie push‑to‑talk**, con
**minichat** e **scambio di foto istantanee**, più una **versione per PC** (Windows, macOS,
Linux) per parlare dal computer con tutti i telefoni. Il telefono funziona in due modi, anche insieme:

| Collegamento | Come | Portata |
|---|---|---|
| 📡 **Vicino** | [Google Nearby Connections](https://developers.google.com/nearby/connections/overview): Bluetooth, BLE e Wi‑Fi diretto. **Niente internet né SIM.** | qualche decina di metri |
| 🌐 **Internet** | **Rete cellulare** (o qualsiasi Wi‑Fi con internet) tramite un server MQTT. Contenuti **cifrati end‑to‑end**. | ovunque ci sia campo |

Con entrambi attivi l'app usa la strada diretta con chi è vicino e internet con chi è lontano.
Se un telefono è raggiungibile in tutti e due i modi, i doppioni vengono scartati
automaticamente. Inoltre foto e voce passano da internet solo se c'è qualcuno raggiungibile
**solo** via internet, così non consumano dati inutilmente.

## Funzionalità

| | |
|---|---|
| 🎙️ **PTT** | Tieni premuto il grande tasto per parlare, rilascia per ascoltare. Voce in tempo reale a tutto il canale, con il "bip" di fine trasmissione. |
| 💬 **Minichat** | Messaggi di testo a tutto il canale. |
| 📷 **Foto istantanee** | Scatta (o scegli dalla galleria): la foto viene ridimensionata, compressa e inviata subito. Tocca per vederla a schermo intero. All'uscita dal canale le foto vengono cancellate. |
| 🆘 **SOS** | Tocca **5 volte in 3 secondi** il pulsante rosso SOS: tutti i dispositivi del canale ricevono un allarme a tutto schermo con sirena, vibrazione, il tuo nome e la **posizione GPS** (subito l'ultima nota, poi una più precisa appena il GPS la trova) con il pulsante "Apri mappa". |
| 📴 **App ridotta** | Mentre sei in un canale una notifica fissa tiene l'app attiva anche ridotta o a schermo spento: ricevi voce, messaggi e foto (con notifica) e puoi **parlare dalla notifica** (pulsante "Parla"/"Stop", max 60 s). Al primo avvio l'app chiede di escluderla dal risparmio batteria: accetta, altrimenti alcuni telefoni (es. Samsung) la sospendono. |
| 🔊 **Suoni da radio** | Impostazioni (⚙️) → Suoni: **selettiva a 5 toni ZVEI/CCIR** (con codice a 5 cifre) o chirp/bip all'inizio di ogni trasmissione in arrivo, **fruscio di squelch**/roger beep alla fine, tono "talk permit" quando premi il tasto, e **suoneria SOS** (sirena bitonale, wail, yelp, sveglia del telefono o una suoneria a scelta). Ogni suono ha l'anteprima ▶. |
| ℹ️ **Informazioni e disclaimer** | Dalla schermata iniziale: autore, disclaimer completo e regole di buon senso (compare anche al primo avvio). |
| 🔒 **Canali con password** | Solo chi ha lo stesso **canale + password** si sente. La password cifra tutto ciò che passa da internet. |

## Come si usa

1. Installa l'app su almeno due telefoni Android (8.0 o superiore, con Google Play Services).
2. Apri l'app, scegli nome, **canale** e **password** (uguali su tutti i telefoni).
3. Scegli i collegamenti:
   - **Vicino**: attiva Bluetooth e Wi‑Fi (non serve essere connessi a una rete); su Android ≤ 12 anche la posizione.
   - **Internet**: servono i dati mobili (o un Wi‑Fi con internet).
4. Premi **Entra nel canale** e concedi i permessi. In alto vedi chi è connesso:
   📡 = vicino, 🌐 = via internet. Le icone a destra mostrano lo stato dei collegamenti.

## 💻 Versione PC

La versione PC parla con tutti i telefoni che hanno il collegamento **Internet** attivo sullo
stesso **canale + password** (il PC non ha il Bluetooth "Nearby" di Google, quindi usa solo internet).

| | |
|---|---|
| 🎙️ **PTT** | Tieni premuto il tasto rosso con il mouse **oppure la barra SPAZIO** (quando non stai scrivendo; premi **Esc** o clicca sulla chat per uscire dal campo messaggio). |
| 💬 **Chat** | Scrivi e premi **Invio**. |
| 🆘 **SOS** | Riceve gli SOS dei telefoni (allarme, sirena, "Apri mappa" nel browser) e può inviarli con 5 clic (senza posizione: il PC non ha il GPS). |
| 📷 **Foto** | Pulsante 🖼️ per scegliere una o più immagini (vengono ridimensionate come sul telefono). Clic su una foto ricevuta per aprirla col visualizzatore del PC, da cui puoi salvarla. |

**Installazione:** scarica l'installer dalla pagina *Releases* del repository (o dalla scheda
*Actions* → ultima esecuzione → artefatto `walkie-ptt-windows` / `-macos` / `-linux`):

- **Windows**: `WalkiePTT-1.0.0.msi` (o `.exe`)
- **macOS**: `WalkiePTT-1.0.0.dmg` (al primo avvio: tasto destro → Apri; consenti il microfono)
- **Linux**: `walkieptt_1.0.0_amd64.deb`, oppure il `.jar` (serve Java 17+: `java -jar WalkiePTT-linux-x64-1.0.0.jar`)

Gli installer includono già Java: non serve installare altro. Gli installer non sono firmati,
quindi Windows/macOS potrebbero mostrare un avviso di sicurezza la prima volta.

Da sorgente: `./gradlew :desktop:run`.

### Il server internet

Per non dover gestire un server, l'app usa di default il **broker MQTT pubblico gratuito di
HiveMQ** (`ssl://broker.hivemq.com:8883`). Il broker vede solo dati cifrati con AES‑256‑GCM e un
identificativo di canale "hashato", ma è un servizio condiviso, senza garanzie di
disponibilità. Per un uso serio puoi indicare il tuo server in **Opzioni avanzate → Server MQTT**
(es. [Mosquitto](https://mosquitto.org/) su un VPS, oppure un piano gratuito di HiveMQ Cloud /
EMQX Cloud). Tutti i telefoni devono usare lo stesso server.

**Consumo dati**: la voce via internet è compressa in G.711 µ‑law (64 kbit/s): circa
**0,5 MB per ogni minuto di parlato**. Una foto pesa circa 150–300 KB.

## Compilare

**Android Studio:** apri la cartella del progetto e premi ▶️ Run.

**Riga di comando** (per l'app serve l'Android SDK, con `ANDROID_HOME` o `local.properties`):

```bash
./gradlew :shared:test :app:assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :desktop:test :desktop:run          # versione PC
./gradlew :desktop:packageDistributionForCurrentOS   # installer per il sistema in uso
```

**Senza installare nulla:** ad ogni push GitHub Actions esegue i test e compila l'APK e gli
installer per Windows, macOS e Linux (scheda *Actions* → ultima esecuzione → artefatti).
Creando un tag `v1.0.0` (o simile) li pubblica anche in una *Release*.

## Come è fatta

```
shared/    codice comune (Kotlin/JVM), usato da telefono e PC: stesso protocollo garantito
└── com/walkietalkie/ptt/
    ├── About.kt               // autore, disclaimer e regole di buon senso (stesso testo ovunque)
    ├── ChatItem.kt            // modello dei messaggi
    └── net/
        ├── InternetLink.kt    // client MQTT (Eclipse Paho) con riconnessione automatica
        ├── ChannelCrypto.kt   // chiave PBKDF2 da canale+password, AES-GCM, topic hashato
        ├── Packet.kt          // formato binario dei messaggi via internet
        └── VoiceCodec.kt      // PCM 16 kHz <-> G.711 µ-law 8 kHz

app/       app Android
└── com/walkietalkie/ptt/
    ├── MainActivity.kt        // avvio, schermo sempre acceso
    ├── WalkieSession.kt       // gestisce i due collegamenti, i telefoni connessi, i doppioni e l'SOS
    ├── WalkieService.kt       // servizio in primo piano: app ridotta, notifiche, PTT dalla notifica
    ├── SosLocator.kt          // posizione GPS per l'SOS
    ├── AlarmPlayer.kt         // sirena e vibrazione per l'SOS ricevuto
    ├── sound/                 // suoni sintetizzati (selettive, squelch, sirene) e loro impostazioni
    ├── AudioEngine.kt         // microfono -> PCM 16 kHz e riproduzione su altoparlante
    ├── PhotoUtils.kt          // ridimensiona, raddrizza (EXIF) e comprime le foto
    └── ui/                    // schermate Jetpack Compose (impostazioni e canale)

desktop/   versione PC (Compose Multiplatform)
└── com/walkietalkie/ptt/desktop/
    ├── Main.kt                // finestra, PTT con la barra spaziatrice
    ├── DesktopClient.kt       // logica: presenza, chat, foto, voce (solo internet)
    ├── JavaSoundAudio.kt      // microfono e altoparlanti con Java Sound
    ├── Photos.kt              // ridimensiona e comprime le immagini
    └── ui/                    // schermate (impostazioni e canale)
```

**Vicino (Nearby):** testo → `BYTES` (JSON), foto → `FILE`, voce → `STREAM` (PCM 16 kHz).

**Internet (MQTT):** tutti i telefoni del canale pubblicano e ascoltano lo stesso topic
`walkieptt/v1/<hash>`. La voce viaggia a pezzi da 100 ms con QoS 0 (meglio un piccolo vuoto
che un ritardo); chat, foto e presenza con QoS 1 (consegna garantita). La presenza usa
messaggi "ci sono" periodici e il *last will* di MQTT per accorgersi di chi sparisce.

Tecnologie: Kotlin, Jetpack Compose / Compose Multiplatform (Material 3), Play Services
Nearby, Eclipse Paho MQTT, minSdk 26, targetSdk 35. I test (`shared/src/test`,
`desktop/src/test`) verificano protocollo, cifratura, codec, lo scambio via MQTT contro un
broker locale (Moquette) e il PC contro un telefono simulato che parla come l'app Android.

## Limiti noti (versione semplice)

- Chiudendo l'app dalle "app recenti" si esce dal canale (ridotta invece continua a funzionare).
- Il PC raggiunge solo i telefoni con il collegamento **Internet** attivo.
- Via internet la voce ha qualche decimo di secondo di ritardo e qualità "telefono".
- Il broker pubblico predefinito è condiviso e può essere lento o non disponibile.
- La cronologia della chat non viene salvata.
