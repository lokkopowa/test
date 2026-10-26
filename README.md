# Ronda IMC Torino Smistamento – Via Chisola 28

Web app (PWA) per il servizio di vigilanza notturno.

- **Avvisi giro ronda** alle 21:00, 22:30, 00:00, 01:30 (ogni 90 min) con suono, vibrazione e notifica; pulsanti per posticipare (+10/+15/+30 min).
- **Guida al TAG**: mappa satellitare, freccia-bussola in tempo reale con distanza, percorso a piedi su Google Maps, foto del punto che compare quando si è vicini.
- **Scheda "Report di fine turno"** compilabile (tocca una casella orario = ora attuale), salvata sul telefono, stampabile/PDF.
- **Calibrazione TAG** da Impostazioni (salva la posizione GPS sul posto o trascina il segnaposto).

## Uso
Pubblicare la cartella su un hosting HTTPS (es. GitHub Pages) e aprire il link dal telefono, poi "Aggiungi a schermata Home".
Durante il turno premere **Avvia turno** (tiene lo schermo acceso e abilita suono/bussola).
Per avvisi garantiti anche ad app chiusa: Impostazioni → **Aggiungi sveglie al calendario (.ics)**.

## Foto ravvicinate dei TAG
Caricare le foto in `img/` (es. `img/tag1-vicino.jpg`) e impostare `nearPhoto` in `DEFAULT_TAGS` in `app.js`.

## Modalità admin
Impostazioni → password admin. Le posizioni dei TAG sono bloccate per tutti; l'admin può sbloccarle per:
aggiungere/eliminare/riordinare TAG, modificare nome e coordinate, caricare foto, calibrare la bussola,
esportare/importare la configurazione. Uscendo dalla modalità admin le posizioni si ribloccano.

## Schede e invio
- **Report di fine turno**, **Consuntivazione GPG** (scheda 📋) e **Verifica carrozze** (scheda 🚃, con lettura della matricola dalla foto).
- **📧 Invia schede**: crea un JPG per ogni scheda. Sul telefono si apre la condivisione → Outlook con i JPG allegati
  (i destinatari impostati in Opzioni vengono copiati negli appunti da incollare nel campo "A");
  sul computer i JPG vengono scaricati e si apre una nuova mail con destinatari e oggetto già compilati.

## App Android (APK)
L'APK pronto è in `apk/RondaChisola.apk`. Nell'app Android gli avvisi dei giri sono notifiche native
(arrivano anche ad app chiusa) e "Invia schede" apre Outlook con destinatari, oggetto e JPG già allegati.

Per ricompilarlo (serve Android SDK e JDK 21):
```
cd android-app && npm install && npm run apk
```
La firma usa `android-app/keystore.properties` + `ronda-release.jks` (non salvati su git):
senza la stessa chiave non si può aggiornare l'app già installata.
