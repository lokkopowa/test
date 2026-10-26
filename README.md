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
