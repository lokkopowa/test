# Walkie PTT 📻

App Android semplice che trasforma il telefono in un **walkie talkie push‑to‑talk**, con
**minichat** e **scambio di foto istantanee**. Funziona in due modi, anche insieme:

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
| 🔒 **Canali con password** | Solo chi ha lo stesso **canale + password** si sente. La password cifra tutto ciò che passa da internet. |

## Come si usa

1. Installa l'app su almeno due telefoni Android (8.0 o superiore, con Google Play Services).
2. Apri l'app, scegli nome, **canale** e **password** (uguali su tutti i telefoni).
3. Scegli i collegamenti:
   - **Vicino**: attiva Bluetooth e Wi‑Fi (non serve essere connessi a una rete); su Android ≤ 12 anche la posizione.
   - **Internet**: servono i dati mobili (o un Wi‑Fi con internet).
4. Premi **Entra nel canale** e concedi i permessi. In alto vedi chi è connesso:
   📡 = vicino, 🌐 = via internet. Le icone a destra mostrano lo stato dei collegamenti.

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

**Riga di comando** (serve l'Android SDK, con `ANDROID_HOME` o `local.properties`):

```bash
./gradlew testDebugUnitTest assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Senza installare nulla:** ad ogni push GitHub Actions esegue i test e compila l'APK;
scaricalo dalla scheda *Actions* → ultima esecuzione → artefatto `walkie-ptt-debug-apk`.

## Come è fatta

```
app/src/main/java/com/walkietalkie/ptt/
├── MainActivity.kt        // avvio, schermo sempre acceso
├── WalkieViewModel.kt     // gestisce i due collegamenti, i telefoni connessi e i doppioni
├── AudioEngine.kt         // microfono -> PCM 16 kHz e riproduzione su altoparlante
├── PhotoUtils.kt          // ridimensiona, raddrizza (EXIF) e comprime le foto
├── ChatItem.kt            // modello dei messaggi
├── net/
│   ├── InternetLink.kt    // client MQTT (Eclipse Paho) con riconnessione automatica
│   ├── ChannelCrypto.kt   // chiave PBKDF2 da canale+password, AES-GCM, topic hashato
│   ├── Packet.kt          // formato binario dei messaggi via internet
│   └── VoiceCodec.kt      // PCM 16 kHz <-> G.711 µ-law 8 kHz
└── ui/                    // schermate Jetpack Compose (impostazioni e canale)
```

**Vicino (Nearby):** testo → `BYTES` (JSON), foto → `FILE`, voce → `STREAM` (PCM 16 kHz).

**Internet (MQTT):** tutti i telefoni del canale pubblicano e ascoltano lo stesso topic
`walkieptt/v1/<hash>`. La voce viaggia a pezzi da 100 ms con QoS 0 (meglio un piccolo vuoto
che un ritardo); chat, foto e presenza con QoS 1 (consegna garantita). La presenza usa
messaggi "ci sono" periodici e il *last will* di MQTT per accorgersi di chi sparisce.

Tecnologie: Kotlin, Jetpack Compose (Material 3), Play Services Nearby, Eclipse Paho MQTT,
minSdk 26, targetSdk 35. I test (`app/src/test`) verificano protocollo, cifratura, codec e lo
scambio via MQTT contro un broker locale (Moquette).

## Limiti noti (versione semplice)

- L'app deve restare **aperta in primo piano** (niente servizio in background).
- Via internet la voce ha qualche decimo di secondo di ritardo e qualità "telefono".
- Il broker pubblico predefinito è condiviso e può essere lento o non disponibile.
- La cronologia della chat non viene salvata.
