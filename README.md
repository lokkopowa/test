# Walkie PTT 📻

App Android semplice che trasforma il telefono in un **walkie talkie push‑to‑talk**, con
**minichat** e **scambio di foto istantanee** tra telefoni vicini.

Funziona **senza internet e senza SIM**: i telefoni si trovano e si collegano da soli tramite
[Google Nearby Connections](https://developers.google.com/nearby/connections/overview)
(Bluetooth, BLE e Wi‑Fi diretto). Portata tipica: qualche decina di metri.

## Funzionalità

| | |
|---|---|
| 🎙️ **PTT** | Tieni premuto il grande tasto rosso per parlare, rilascia per ascoltare. L'audio arriva in tempo reale a tutti i telefoni del canale, con il classico "bip" di fine trasmissione. |
| 💬 **Minichat** | Messaggi di testo a tutto il canale. |
| 📷 **Foto istantanee** | Scatta una foto (o scegline una dalla galleria): viene ridimensionata, compressa e inviata subito. Tocca una foto per vederla a schermo intero. All'uscita dal canale le foto vengono cancellate. |
| 📡 **Canali** | Solo chi sceglie lo stesso nome di canale si sente e si vede. Più di due telefoni possono stare sullo stesso canale (rete a maglia). |

## Come si usa

1. Installa l'app su almeno due telefoni Android (8.0 o superiore, con Google Play Services).
2. Attiva **Bluetooth** e **Wi‑Fi** (non serve essere connessi a una rete) e, su Android ≤ 12, la **posizione**.
3. Apri l'app, scegli nome e canale (es. `1`) e premi **Entra nel canale**; concedi i permessi.
4. Dopo qualche secondo in alto compare "1 connessi: …". Ora puoi parlare, scrivere e mandare foto.

## Compilare

**Android Studio:** apri la cartella del progetto e premi ▶️ Run.

**Riga di comando** (serve l'Android SDK, con `ANDROID_HOME` o `local.properties`):

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

**Senza installare nulla:** ad ogni push GitHub Actions compila l'APK; scaricalo dalla scheda
*Actions* → ultima esecuzione → artefatto `walkie-ptt-debug-apk`.

## Come è fatta

```
app/src/main/java/com/walkietalkie/ptt/
├── MainActivity.kt      // avvio, schermo sempre acceso
├── WalkieViewModel.kt   // connessioni Nearby + smistamento di chat / foto / audio
├── AudioEngine.kt       // microfono -> PCM 16 kHz e riproduzione su altoparlante
├── PhotoUtils.kt        // ridimensiona, raddrizza (EXIF) e comprime le foto
├── ChatItem.kt          // modello dei messaggi
└── ui/                  // schermate Jetpack Compose (setup e canale)
```

Ogni contenuto usa un tipo di payload Nearby diverso:

- **testo** → `BYTES` (JSON)
- **foto** → `FILE` (JPEG max 1280 px)
- **voce** → `STREAM` (PCM 16 bit mono 16 kHz, ~32 KB/s), un flusso per ogni pressione del tasto

Tecnologie: Kotlin, Jetpack Compose (Material 3), Play Services Nearby, minSdk 26, targetSdk 35.

## Limiti noti (versione semplice)

- L'app deve restare **aperta in primo piano** (niente servizio in background).
- La voce viaggia non compressa: perfetta via Wi‑Fi, può avere un po' di ritardo se il collegamento è solo Bluetooth.
- La cronologia della chat non viene salvata.
