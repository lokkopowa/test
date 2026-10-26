'use strict';

/* ================= CONFIGURAZIONE ================= */

// Posizioni iniziali INDICATIVE: vanno calibrate sul posto da Impostazioni.
// photo     = foto aerea con la freccia
// nearPhoto = foto ravvicinata del punto esatto (quando disponibile, es. img/tag1-vicino.jpg)
const DEFAULT_TAGS = [
  { id: 'tornio',      name: 'TAG Capannone tornio',     photo: 'img/tag1.jpg', nearPhoto: 'img/tag1-vicino.jpg', lat: 45.04190, lng: 7.66700 },
  { id: 'dor',         name: 'TAG Palazzina uffici DOR', photo: 'img/tag2.jpg', nearPhoto: 'img/tag2-vicino.jpg', lat: 45.04120, lng: 7.66840 },
  { id: 'spogliatoio', name: 'TAG Spogliatoio',          photo: 'img/tag3.jpg', nearPhoto: 'img/tag3-vicino.jpg', lat: 45.04030, lng: 7.66760 },
  { id: 'diesel',      name: 'TAG Piazzale diesel',      photo: 'img/tag4.jpg', nearPhoto: 'img/tag4-vicino.jpg', lat: 45.03980, lng: 7.66640 },
];
const ROUND_START_HOUR = 21;   // primo giro alle 21:00
const ROUND_EVERY_MIN = 90;    // cadenza
const ROUND_COUNT = 4;         // 21:00, 22:30, 00:00, 01:30
const TAG_COLUMNS = 6;         // colonne "Ora verifica" sulla scheda
const GATES = ['Cancello portineria', 'Cancello Otto Gallery', 'Cancello torre idrica'];
const SHEDS = ['Officine trazione elettrica', 'Officine trazione diesel', 'Magazzini', 'Palazzina uffici'];
const FFOO_ROWS = 4;

/* ================= STORAGE ================= */

const store = {
  get(k, def) { try { const v = localStorage.getItem('ronda.' + k); return v ? JSON.parse(v) : def; } catch { return def; } },
  set(k, v) { try { localStorage.setItem('ronda.' + k, JSON.stringify(v)); return true; } catch { return false; } },
};

// Elenco TAG: quello salvato dall'admin, altrimenti i predefiniti (+ eventuali posizioni calibrate con la versione precedente)
let tags = store.get('tags', null) || DEFAULT_TAGS.map(t => ({ ...t, ...(store.get('tagpos', {})[t.id] || {}) }));
const saveTags = () => store.set('tags', tags) || alert('Memoria del telefono piena: usa foto più piccole o elimina qualche foto.');
let nearRadius = store.get('nearRadius', 30);
let targetIdx = Math.min(store.get('targetIdx', 0), tags.length - 1);
let compassOffset = store.get('compassOffset', 0);   // correzione bussola in gradi
let locked = store.get('locked', true);              // posizioni TAG bloccate

// Modalità admin (dura finché l'app resta aperta). Nel codice c'è solo l'hash SHA-256 della password.
const ADMIN_HASH = 'e42461f344370e918af4832c8292e68abbbc47196a108f5b05d53b2975e24c17';
let admin = sessionStorage.getItem('ronda.admin') === '1';
async function sha256(txt) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(txt));
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}

/* ================= UTILITY ================= */

const $ = s => document.querySelector(s);
const pad = n => String(n).padStart(2, '0');
const hhmm = d => pad(d.getHours()) + ':' + pad(d.getMinutes());
const ddmm = d => pad(d.getDate()) + '/' + pad(d.getMonth() + 1);

// Il turno "appartiene" alla sera in cui inizia: prima di mezzogiorno è ancora il turno di ieri.
function shiftStart(now = new Date()) {
  const d = new Date(now);
  if (d.getHours() < 12) d.setDate(d.getDate() - 1);
  d.setHours(ROUND_START_HOUR, 0, 0, 0);
  return d;
}
const shiftKey = () => shiftStart().toISOString().slice(0, 10);

const newShift = () => ({ key: shiftKey(), postpone: {}, notified: {}, done: {}, skipped: {}, selected: null, alarmRound: null });

function loadShift() {
  let s = store.get('shift', null);
  if (!s || s.key !== shiftKey()) s = newShift();
  s.skipped ||= {};
  if (s.selected === undefined) s.selected = null;
  return s;
}
let shift = loadShift();
const saveShift = () => store.set('shift', shift);

function roundDue(i) {
  const d = new Date(shiftStart().getTime() + i * ROUND_EVERY_MIN * 60000);
  return new Date(d.getTime() + (shift.postpone[i] || 0) * 60000);
}

// Giro in corso: quello scelto a mano dall'agente, altrimenti il primo non concluso (in ordine di orario).
function currentRound() {
  if (shift.selected != null && !shift.done[shift.selected]) return shift.selected;
  for (let i = 0; i < ROUND_COUNT; i++) if (!shift.done[i]) return i;
  return ROUND_COUNT - 1;
}

function distance(a, b) {
  const R = 6371000, toR = Math.PI / 180;
  const dLat = (b.lat - a.lat) * toR, dLng = (b.lng - a.lng) * toR;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * toR) * Math.cos(b.lat * toR) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(x));
}
function bearing(a, b) {
  const toR = Math.PI / 180;
  const y = Math.sin((b.lng - a.lng) * toR) * Math.cos(b.lat * toR);
  const x = Math.cos(a.lat * toR) * Math.sin(b.lat * toR) - Math.sin(a.lat * toR) * Math.cos(b.lat * toR) * Math.cos((b.lng - a.lng) * toR);
  return (Math.atan2(y, x) / toR + 360) % 360;
}

/* ================= TABS ================= */

document.querySelectorAll('.tabs button').forEach(b => b.addEventListener('click', () => showView(b.dataset.view)));
function showView(v) {
  document.querySelectorAll('.tabs button').forEach(b => b.classList.toggle('active', b.dataset.view === v));
  document.querySelectorAll('.view').forEach(s => s.classList.toggle('active', s.id === 'view-' + v));
  if (v === 'giro' && map) setTimeout(() => map.invalidateSize(), 50);
}

/* ================= MAPPA ================= */

let map, userMarker, routeLine, tagLayer, tourLine, tagMarkers = [], dragMode = false;
const canEdit = () => admin && !locked;

function initMap() {
  const sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    { maxZoom: 20, maxNativeZoom: 19, attribution: 'Esri World Imagery' });
  const osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    { maxZoom: 20, maxNativeZoom: 19, attribution: '© OpenStreetMap' });
  map = L.map('map', { layers: [sat] }).setView([45.0410, 7.6679], 17);
  L.control.layers({ 'Satellite': sat, 'Stradale': osm }).addTo(map);
  tagLayer = L.layerGroup().addTo(map);
  routeLine = L.polyline([], { color: '#ffcc00', weight: 5 }).addTo(map);
  drawTags();
  if (tags.length) map.fitBounds(L.latLngBounds(tags.map(t => [t.lat, t.lng])).pad(0.4));
}

// (Ri)disegna segnaposto e percorso del giro: chiamata a ogni modifica dell'elenco TAG
function drawTags() {
  tagLayer.clearLayers();
  tourLine = L.polyline(tags.map(t => [t.lat, t.lng]), { color: '#fff', dashArray: '6 8', weight: 3 }).addTo(tagLayer);
  tagMarkers = tags.map((t, i) => {
    const m = L.marker([t.lat, t.lng], {
      draggable: false,
      icon: L.divIcon({ className: '', html: `<div class="pin" style="background:#0b3d91;color:#fff;border:2px solid #fff;border-radius:50%;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-weight:700">${i + 1}</div>`, iconSize: [28, 28], iconAnchor: [14, 14] }),
    }).addTo(tagLayer).bindPopup(`<b>${t.name}</b>${t.photo ? `<br><img src="${t.photo}" style="width:180px">` : ''}`);
    m.on('click', () => setTarget(i));
    m.on('dragend', () => { const p = m.getLatLng(); saveTagPos(i, p.lat, p.lng); });
    if (dragMode && canEdit()) m.dragging.enable();
    return m;
  });
}

function refreshTagMarkers() {
  tourLine?.setLatLngs(tags.map(t => [t.lat, t.lng]));
  tagMarkers.forEach((m, i) => {
    m.setLatLng([tags[i].lat, tags[i].lng]);
    m.getElement()?.querySelector('.pin')?.style.setProperty('background', i === targetIdx ? '#c0392b' : '#0b3d91');
  });
}

/* ================= GPS + BUSSOLA ================= */

let pos = null, heading = null;

function startGps() {
  if (!navigator.geolocation) { $('#gpsInfo').textContent = 'GPS non disponibile'; return; }
  navigator.geolocation.watchPosition(p => {
    pos = { lat: p.coords.latitude, lng: p.coords.longitude, acc: p.coords.accuracy };
    // Senza bussola uso la direzione di marcia del GPS
    if (compassHeading == null && p.coords.heading != null && !isNaN(p.coords.heading) && p.coords.speed > 0.5) heading = p.coords.heading;
    $('#gpsInfo').textContent = `GPS ±${Math.round(pos.acc)} m`;
    if (!userMarker) userMarker = L.circleMarker([pos.lat, pos.lng], { radius: 8, color: '#fff', fillColor: '#1e90ff', fillOpacity: 1, weight: 3 }).addTo(map);
    else userMarker.setLatLng([pos.lat, pos.lng]);
    updateGuide();
  }, e => { $('#gpsInfo').textContent = 'GPS: ' + e.message; }, { enableHighAccuracy: true, maximumAge: 2000 });
}

let compassHeading = null, rawHeading = null;
function onOrientation(e) {
  let h = null;
  if (typeof e.webkitCompassHeading === 'number') h = e.webkitCompassHeading;          // iOS
  else if (e.absolute && typeof e.alpha === 'number') h = (360 - e.alpha) % 360;          // Android
  if (h == null) return;
  const so = (screen.orientation && screen.orientation.angle) || 0;
  rawHeading = (h + so) % 360;
  compassHeading = heading = (rawHeading + compassOffset + 360) % 360;
  updateArrow();
  const hd = $('#headingNow');
  if (hd) hd.textContent = `Direzione attuale: ${Math.round(heading)}°`;
}
async function enableCompass() {
  try {
    if (typeof DeviceOrientationEvent !== 'undefined' && DeviceOrientationEvent.requestPermission) {
      if (await DeviceOrientationEvent.requestPermission() !== 'granted') return;
      window.addEventListener('deviceorientation', onOrientation);
    } else if ('ondeviceorientationabsolute' in window) {
      window.addEventListener('deviceorientationabsolute', onOrientation);
    } else {
      window.addEventListener('deviceorientation', onOrientation);
    }
    $('#btnCompass').textContent = 'Bussola attiva';
  } catch { $('#btnCompass').textContent = 'Bussola non disponibile'; }
}
$('#btnCompass').addEventListener('click', enableCompass);

function updateArrow() {
  if (!pos || !tags.length) return;
  const b = bearing(pos, tags[targetIdx]);
  const rel = heading == null ? b : b - heading;   // senza bussola: freccia rispetto al Nord (mappa)
  $('#arrow').style.transform = `rotate(${rel - 90}deg)`;
}

function updateGuide() {
  const t = tags[targetIdx];
  if (!t) { $('#targetName').textContent = 'Nessun TAG configurato'; return; }
  $('#targetName').textContent = `${targetIdx + 1}. ${t.name}`;
  $('#btnGmaps').href = `https://www.google.com/maps/dir/?api=1&destination=${t.lat},${t.lng}&travelmode=walking`;
  document.querySelectorAll('.photos figure').forEach((f, i) => f.classList.toggle('current', i === targetIdx));
  refreshTagMarkers();
  if (!pos) return;
  const d = distance(pos, t);
  $('#distance').textContent = d < 1000 ? `${Math.round(d)} m` : `${(d / 1000).toFixed(1)} km`;
  routeLine.setLatLngs([[pos.lat, pos.lng], [t.lat, t.lng]]);
  const near = d <= nearRadius;
  $('#nearBox').classList.toggle('hidden', !near);
  if (near) $('#nearPhoto').src = t.nearPhoto || t.photo;
  if (near && !updateGuide.vibrated) { navigator.vibrate?.(300); updateGuide.vibrated = true; }
  if (!near) updateGuide.vibrated = false;
  updateArrow();
}

function setTarget(i) {
  if (!tags.length) return;
  targetIdx = (i + tags.length) % tags.length;
  store.set('targetIdx', targetIdx);
  updateGuide.vibrated = false;
  updateGuide();
}
$('#prevTag').addEventListener('click', () => setTarget(targetIdx - 1));
$('#nextTag').addEventListener('click', () => setTarget(targetIdx + 1));

function renderPhotos() {
  $('#photos').innerHTML = tags.map((t, i) => `<figure data-i="${i}"><img src="${t.photo || t.nearPhoto || 'img/icon-192.png'}" alt="${t.name}" loading="lazy"><figcaption>${i + 1}. ${t.name}</figcaption></figure>`).join('');
  document.querySelectorAll('.photos figure').forEach(f => f.addEventListener('click', () => setTarget(+f.dataset.i)));
}

/* ================= BOLLATURA ================= */

// Le caselle orario sono legate all'id del TAG, così aggiungere/eliminare TAG non sposta gli orari
const tagKey = (i, r) => `tag_${tags[i].id}_${r}`;

$('#btnBolla').addEventListener('click', () => {
  if (!tags.length) return;
  const r = Math.min(currentRound(), TAG_COLUMNS - 1);
  const key = tagKey(targetIdx, r);
  const sheet = store.get('sheet', {});
  if (sheet[key] && !confirm(`${tags[targetIdx].name} già bollato alle ${sheet[key]} per il giro ${r + 1}. Sovrascrivere?`)) return;
  sheet[key] = hhmm(new Date());
  store.set('sheet', sheet);
  loadSheet();
  navigator.vibrate?.([100, 50, 100]);
  // Giro concluso quando tutti i TAG del giro hanno un orario
  if (tags.every((_, i) => sheet[tagKey(i, r)])) {
    shift.done[r] = true; shift.selected = null; saveShift();
    alert(`Giro ${r + 1} completato!`);
    setTarget(0);
  } else {
    // passa al prossimo TAG non ancora bollato in questo giro
    for (let k = 1; k <= tags.length; k++) {
      const j = (targetIdx + k) % tags.length;
      if (!sheet[tagKey(j, r)]) { setTarget(j); break; }
    }
  }
  renderRounds();
});

/* ================= GIRI + AVVISI ================= */

function renderRounds() {
  const cur = currentRound();
  $('#roundsList').innerHTML = Array.from({ length: ROUND_COUNT }, (_, i) => {
    const cls = shift.skipped[i] ? 'skipped' : shift.done[i] ? 'done' : i === cur ? 'next' : '';
    const pp = shift.postpone[i] ? ` (+${shift.postpone[i]}′)` : '';
    const st = shift.skipped[i] ? ' · saltato' : shift.done[i] ? ' ✔' : '';
    return `<button class="${cls}" data-round="${i}">Giro ${i + 1}: ${hhmm(roundDue(i))}${pp}${st}</button>`;
  }).join('');
  const allDone = Array.from({ length: ROUND_COUNT }, (_, i) => shift.done[i]).every(Boolean);
  $('#btnSkip').disabled = allDone;
  if (allDone) { $('#nextRound').textContent = 'Tutti i giri completati'; return; }
  const due = roundDue(cur), mins = Math.round((due - Date.now()) / 60000);
  if (shift.selected === cur) { $('#nextRound').textContent = `Giro ${cur + 1} IN CORSO (scelto a mano)`; return; }
  $('#nextRound').textContent = mins > 0
    ? `Giro ${cur + 1} alle ${hhmm(due)} (tra ${mins >= 60 ? Math.floor(mins / 60) + ' h ' : ''}${mins % 60} min)`
    : `Giro ${cur + 1} IN CORSO (dalle ${hhmm(due)})`;
}

// Tocco su un giro: lo si effettua subito, a prescindere dall'orario (se già fatto/saltato, lo si riapre)
$('#roundsList').addEventListener('click', e => {
  const b = e.target.closest('[data-round]'); if (!b) return;
  const i = +b.dataset.round;
  if (shift.done[i]) {
    if (!confirm(`Il giro ${i + 1} è già ${shift.skipped[i] ? 'stato saltato' : 'completato'}. Riaprirlo per effettuarlo ora?`)) return;
    shift.done[i] = false; shift.skipped[i] = false;
  } else if (i === currentRound()) return;
  else if (!confirm(`Effettuare ora il giro ${i + 1} (previsto alle ${hhmm(roundDue(i))})?`)) return;
  selectRound(i);
});

function selectRound(i) {
  shift.selected = i;
  shift.notified[i] = true;   // niente avviso per un giro già iniziato a mano
  saveShift();
  focusRound(i);
}

// Punta al primo TAG non ancora bollato del giro
function focusRound(i) {
  const sheet = store.get('sheet', {}), c = Math.min(i, TAG_COLUMNS - 1);
  const j = tags.findIndex((_, k) => !sheet[tagKey(k, c)]);
  setTarget(j < 0 ? 0 : j);
  renderRounds();
}

$('#btnSkip').addEventListener('click', () => {
  const i = currentRound();
  if (shift.done[i] || !confirm(`Saltare il giro ${i + 1}? Verrà segnato come "saltato".`)) return;
  shift.done[i] = true; shift.skipped[i] = true; shift.notified[i] = true;
  if (shift.selected === i) shift.selected = null;
  saveShift();
  focusRound(currentRound());
});

function tick() {
  if (shift.key !== shiftKey()) shift = loadShift();
  $('#clock').textContent = hhmm(new Date());
  const now = Date.now();
  for (let i = 0; i < ROUND_COUNT; i++) {
    const due = roundDue(i).getTime();
    if (!shift.done[i] && !shift.notified[i] && now >= due && now - due < 60 * 60000) {
      shift.notified[i] = true; saveShift();
      fireAlarm(i);
      break;
    }
  }
  renderRounds();
}

let beepTimer = null, audioCtx = null;
function unlockAudio() {
  if (!audioCtx) try { audioCtx = new (window.AudioContext || window.webkitAudioContext)(); } catch {}
  audioCtx?.resume?.();
}
function beep() {
  if (!audioCtx) return;
  const o = audioCtx.createOscillator(), g = audioCtx.createGain();
  o.frequency.value = 880; o.connect(g); g.connect(audioCtx.destination);
  g.gain.setValueAtTime(0.4, audioCtx.currentTime);
  o.start(); o.stop(audioCtx.currentTime + 0.4);
}

async function fireAlarm(i) {
  shift.alarmRound = i; saveShift();
  const title = `Giro ronda ${i + 1} di ${ROUND_COUNT}`;
  const body = `Ore ${hhmm(roundDue(i))} – inizia il giro.` + (tags[0] ? ` Primo punto: ${tags[0].name}` : '');
  $('#alarmTitle').textContent = title;
  $('#alarmText').textContent = body;
  $('#alarm').classList.remove('hidden');
  beep(); clearInterval(beepTimer); beepTimer = setInterval(beep, 1500);
  navigator.vibrate?.([500, 200, 500, 200, 500]);
  if ('Notification' in window && Notification.permission === 'granted') {
    const opts = {
      body, tag: 'ronda', renotify: true, requireInteraction: true, icon: 'img/icon-192.png', vibrate: [500, 200, 500],
      actions: [{ action: 'start', title: 'Inizia giro' }, { action: '15', title: 'Posticipa 15 min' }],
    };
    try { (await navigator.serviceWorker?.ready)?.showNotification(title, opts); }
    catch { try { new Notification(title, opts); } catch {} }
  }
}

function handleAlarmAction(action) {
  const i = shift.alarmRound;
  $('#alarm').classList.add('hidden');
  clearInterval(beepTimer);
  navigator.serviceWorker?.ready.then(r => r.getNotifications({ tag: 'ronda' })).then(ns => ns?.forEach(n => n.close())).catch(() => {});
  if (i == null) return;
  if (action === 'start') {
    showView('giro'); selectRound(i);
  } else {
    const min = parseInt(action, 10) || 10;
    // posticipo a partire da ora (non dall'orario previsto)
    const base = shiftStart().getTime() + i * ROUND_EVERY_MIN * 60000;
    shift.postpone[i] = Math.round((Date.now() + min * 60000 - base) / 60000);
    shift.notified[i] = false;
  }
  shift.alarmRound = null; saveShift();
  renderRounds();
}
document.querySelectorAll('[data-alarm]').forEach(b => b.addEventListener('click', () => handleAlarmAction(b.dataset.alarm)));
navigator.serviceWorker?.addEventListener('message', e => { if (e.data?.alarmAction) handleAlarmAction(e.data.alarmAction); });
// Apertura dall'azione della notifica (app era chiusa)
const qsAction = new URLSearchParams(location.search).get('alarm');

/* ================= TURNO / WAKE LOCK ================= */

let wakeLock = null, shiftOn = false;
async function requestWake() { try { wakeLock = await navigator.wakeLock?.request('screen'); } catch {} }
$('#btnShift').addEventListener('click', async () => {
  shiftOn = !shiftOn;
  $('#btnShift').textContent = shiftOn ? 'Turno attivo ●' : 'Avvia turno';
  $('#btnShift').classList.toggle('on', shiftOn);
  if (shiftOn) {
    unlockAudio();
    await requestWake();
    if ('Notification' in window && Notification.permission === 'default') await Notification.requestPermission();
    enableCompass();
  } else { wakeLock?.release(); wakeLock = null; }
});
document.addEventListener('visibilitychange', () => { if (shiftOn && document.visibilityState === 'visible') requestWake(); tick(); });

/* ================= IMPOSTAZIONI ================= */

$('#btnNotif').addEventListener('click', async () => {
  if (!('Notification' in window)) return alert('Notifiche non supportate da questo browser. Installa l\'app sulla schermata Home.');
  const p = await Notification.requestPermission();
  alert(p === 'granted' ? 'Notifiche abilitate' : 'Notifiche non consentite');
});
$('#btnTest').addEventListener('click', () => { unlockAudio(); fireAlarm(currentRound()); });

$('#btnIcs').addEventListener('click', () => {
  const start = shiftStart();
  const fmt = d => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}T${pad(d.getHours())}${pad(d.getMinutes())}00`;
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Ronda Chisola//IT', 'CALSCALE:GREGORIAN'];
  for (let i = 0; i < ROUND_COUNT; i++) {
    const d = new Date(start.getTime() + i * ROUND_EVERY_MIN * 60000);
    const e = new Date(d.getTime() + 30 * 60000);
    lines.push('BEGIN:VEVENT', `UID:ronda-chisola-${i}@ronda`, `DTSTAMP:${fmt(new Date())}`, `DTSTART:${fmt(d)}`, `DTEND:${fmt(e)}`,
      'RRULE:FREQ=DAILY', `SUMMARY:Giro ronda ${i + 1} - Via Chisola 28`,
      'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:Giro ronda ${i + 1}`, 'TRIGGER:PT0M', 'END:VALARM', 'END:VEVENT');
  }
  lines.push('END:VCALENDAR');
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([lines.join('\r\n')], { type: 'text/calendar' }));
  a.download = 'giri-ronda-chisola.ics';
  a.click();
});

/* ================= AMMINISTRAZIONE TAG ================= */

const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// Ogni modifica all'elenco TAG passa da qui: salva e aggiorna mappa, foto, scheda e pannello
function tagsChanged() {
  saveTags();
  targetIdx = Math.max(0, Math.min(targetIdx, tags.length - 1));
  drawTags(); renderPhotos(); buildTagRows(); loadSheet(); renderAdmin(); updateGuide();
}

function saveTagPos(i, lat, lng) {
  if (!canEdit()) return;
  tags[i].lat = lat; tags[i].lng = lng;
  tagsChanged();
}

// Riduce la foto scelta (max 800 px) per non riempire la memoria del telefono
function readPhoto(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const k = Math.min(1, 800 / Math.max(img.width, img.height));
      const c = document.createElement('canvas');
      c.width = img.width * k; c.height = img.height * k;
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(img.src);
      resolve(c.toDataURL('image/jpeg', 0.75));
    };
    img.onerror = reject;
    img.src = URL.createObjectURL(file);
  });
}

function renderAdmin() {
  $('#adminLogin').classList.toggle('hidden', admin);
  $('#adminPanel').classList.toggle('hidden', !admin);
  $('#lockState').textContent = locked ? '🔒 Posizioni TAG bloccate' : '🔓 Posizioni TAG sbloccate (modificabili)';
  $('#lockState').className = locked ? 'lock on' : 'lock off';
  $('#btnLock').textContent = locked ? 'Sblocca per modificare' : 'Blocca posizioni';
  $('#nearRadius').value = nearRadius;
  $('#offsetNow').textContent = `Correzione attuale: ${compassOffset > 0 ? '+' : ''}${Math.round(compassOffset)}°`;
  if (!canEdit() && dragMode) toggleDrag(false);

  // elenco in sola lettura per tutti
  $('#tagList').innerHTML = tags.map((t, i) =>
    `<div class="tagcal"><b>${i + 1}. ${esc(t.name)}</b><div class="coords">${t.lat.toFixed(6)}, ${t.lng.toFixed(6)}</div></div>`).join('')
    || '<p class="muted">Nessun TAG</p>';

  const dis = canEdit() ? '' : 'disabled';
  $('#tagEdit').innerHTML = tags.map((t, i) => `
    <div class="tagcal" data-i="${i}">
      <div class="row tight">
        <b>${i + 1}.</b><input class="field" data-e="name" value="${esc(t.name)}" ${dis}>
      </div>
      <div class="row tight">
        <label>Lat <input class="field" data-e="lat" inputmode="decimal" value="${t.lat.toFixed(6)}" ${dis}></label>
        <label>Lng <input class="field" data-e="lng" inputmode="decimal" value="${t.lng.toFixed(6)}" ${dis}></label>
      </div>
      <div class="row tight">
        <button class="btn small" data-a="here" ${dis}>📍 Usa mia posizione</button>
        <button class="btn small" data-a="up" ${dis || (i === 0 ? 'disabled' : '')}>▲</button>
        <button class="btn small" data-a="down" ${dis || (i === tags.length - 1 ? 'disabled' : '')}>▼</button>
        <button class="btn small danger" data-a="del" ${dis}>Elimina</button>
      </div>
      <div class="row tight small-text">
        <label class="file">Foto aerea <input type="file" accept="image/*" data-p="photo" ${dis}></label>
        <label class="file">Foto ravvicinata <input type="file" accept="image/*" data-p="nearPhoto" ${dis}></label>
      </div>
    </div>`).join('');
}

$('#tagEdit').addEventListener('change', async e => {
  if (!canEdit()) return;
  const box = e.target.closest('[data-i]'); if (!box) return;
  const i = +box.dataset.i, f = e.target.dataset.e, p = e.target.dataset.p;
  if (f === 'name') { tags[i].name = e.target.value.trim() || tags[i].name; }
  else if (f === 'lat' || f === 'lng') {
    const v = parseFloat(e.target.value.replace(',', '.'));
    const ok = f === 'lat' ? Math.abs(v) <= 90 : Math.abs(v) <= 180;
    if (isNaN(v) || !ok) { alert('Coordinata non valida'); return renderAdmin(); }
    tags[i][f] = v;
  } else if (p && e.target.files[0]) {
    try { tags[i][p] = await readPhoto(e.target.files[0]); } catch { return alert('Impossibile leggere la foto'); }
  } else return;
  tagsChanged();
});

$('#tagEdit').addEventListener('click', e => {
  const b = e.target.closest('[data-a]');
  if (!b || !canEdit()) return;
  const i = +b.closest('[data-i]').dataset.i;
  if (b.dataset.a === 'here') {
    if (!pos) return alert('Posizione GPS non ancora disponibile');
    if (pos.acc > 25 && !confirm(`Precisione GPS bassa (±${Math.round(pos.acc)} m). Salvare comunque?`)) return;
    tags[i].lat = pos.lat; tags[i].lng = pos.lng;
  } else if (b.dataset.a === 'del') {
    if (!confirm(`Eliminare "${tags[i].name}"?`)) return;
    tags.splice(i, 1);
  } else {
    const j = b.dataset.a === 'up' ? i - 1 : i + 1;
    [tags[i], tags[j]] = [tags[j], tags[i]];
  }
  tagsChanged();
});

$('#btnAddTag').addEventListener('click', () => {
  if (!canEdit()) return;
  const name = prompt('Nome del nuovo TAG:', 'TAG ');
  if (!name || !name.trim()) return;
  // posizione: GPS se disponibile, altrimenti centro della mappa (poi si sposta trascinandolo)
  const c = pos || map.getCenter();
  tags.push({ id: 't' + Date.now().toString(36), name: name.trim(), photo: null, nearPhoto: null, lat: c.lat, lng: c.lng });
  tagsChanged();
  alert(pos ? 'TAG aggiunto nella tua posizione attuale.' : 'TAG aggiunto al centro della mappa: attiva "Sposta sulla mappa" per posizionarlo.');
});

function toggleDrag(on) {
  dragMode = on && canEdit();
  tagMarkers.forEach(m => dragMode ? m.dragging.enable() : m.dragging.disable());
  $('#btnDrag').textContent = 'Sposta sulla mappa: ' + (dragMode ? 'ON' : 'OFF');
}
$('#btnDrag').addEventListener('click', () => { toggleDrag(!dragMode); if (dragMode) showView('giro'); });

$('#btnLock').addEventListener('click', () => {
  if (!admin) return;
  locked = !locked; store.set('locked', locked);
  renderAdmin();
});

$('#btnAdmin').addEventListener('click', async () => {
  const pw = $('#adminPw').value;
  $('#adminPw').value = '';
  let ok = false;
  try { ok = (await sha256(pw)) === ADMIN_HASH; } catch { alert('Accesso admin disponibile solo da connessione sicura (https)'); return; }
  if (!ok) return alert('Password errata');
  admin = true; sessionStorage.setItem('ronda.admin', '1');
  renderAdmin();
});
$('#adminPw').addEventListener('keydown', e => { if (e.key === 'Enter') $('#btnAdmin').click(); });
$('#btnLogout').addEventListener('click', () => {
  admin = false; sessionStorage.removeItem('ronda.admin');
  locked = true; store.set('locked', true);   // uscendo si ribloccano sempre le posizioni
  renderAdmin();
});

$('#nearRadius').addEventListener('change', e => {
  if (!admin) return;
  nearRadius = +e.target.value || 30; store.set('nearRadius', nearRadius); updateGuide();
});

// ----- Calibrazione bussola -----
function setOffset(v) {
  compassOffset = ((v + 540) % 360) - 180;   // tra -180 e +180
  store.set('compassOffset', compassOffset);
  if (rawHeading != null) heading = compassHeading = (rawHeading + compassOffset + 360) % 360;
  renderAdmin(); updateArrow();
}
$('#btnCalTag').addEventListener('click', async () => {
  if (!admin) return;
  if (rawHeading == null) { await enableCompass(); return alert('Attiva la bussola e riprova (muovi il telefono a 8 per qualche secondo).'); }
  if (!pos || !tags[targetIdx]) return alert('Serve la posizione GPS e un TAG selezionato nella schermata Giro.');
  if (!confirm(`Punta la parte alta del telefono esattamente verso "${tags[targetIdx].name}" e premi OK.`)) return;
  setOffset(bearing(pos, tags[targetIdx]) - rawHeading);
  alert('Bussola calibrata');
});
$('#btnCalMinus').addEventListener('click', () => admin && setOffset(compassOffset - 5));
$('#btnCalPlus').addEventListener('click', () => admin && setOffset(compassOffset + 5));
$('#btnCalReset').addEventListener('click', () => admin && setOffset(0));

// ----- Esporta / importa configurazione (per non perderla e passarla ad altri telefoni) -----
$('#btnExport').addEventListener('click', () => {
  const data = { versione: 1, tags, nearRadius, compassOffset };
  const a = document.createElement('a');
  a.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' }));
  a.download = 'ronda-chisola-config.json';
  a.click();
});
$('#importFile').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f || !canEdit()) return alert('Sblocca le posizioni per importare una configurazione');
  try {
    const data = JSON.parse(await f.text());
    if (!Array.isArray(data.tags) || !data.tags.every(t => t.id && t.name && isFinite(t.lat) && isFinite(t.lng))) throw 0;
    if (!confirm(`Sostituire i TAG attuali con i ${data.tags.length} del file?`)) return;
    tags = data.tags;
    if (isFinite(data.nearRadius)) { nearRadius = data.nearRadius; store.set('nearRadius', nearRadius); }
    if (isFinite(data.compassOffset)) setOffset(data.compassOffset);
    tagsChanged();
  } catch { alert('File di configurazione non valido'); }
});
$('#btnResetTags').addEventListener('click', () => {
  if (!canEdit() || !confirm('Ripristinare i 4 TAG predefiniti dell\'app? Le modifiche fatte su questo telefono andranno perse.')) return;
  tags = DEFAULT_TAGS.map(t => ({ ...t }));
  tagsChanged();
});

/* ================= SCHEDA ================= */

const yn = k => `<td class="yn" data-k="${k}" data-v="SI">SI</td><td class="yn" data-k="${k}" data-v="NO">NO</td>`;
const tcell = k => `<td class="t"><input data-k="${k}" inputmode="numeric" class="time"></td>`;

function buildSheet() {
  $('#gates').innerHTML = GATES.map((g, i) =>
    `<tr><td class="name">${g}</td>${tcell(`gate_${i}_a_ora`)}${yn(`gate_${i}_a`)}<td class="name">${g}</td>${tcell(`gate_${i}_b_ora`)}${yn(`gate_${i}_b`)}</tr>`).join('');
  buildTagRows();
  $('#sheds').innerHTML = SHEDS.map((s, i) =>
    `<tr><td>${s}</td><td style="width:70px">chiusura</td>${yn(`shed_${i}`)}<td><input data-k="shed_${i}_note" class="cell"></td></tr>`).join('');
  // riga capannoni: nome | chiusura | SI | NO | note
  document.querySelector('#sheds').closest('table').querySelector('th').colSpan = 5;
  $('#ffoo').innerHTML = Array.from({ length: FFOO_ROWS }, (_, i) =>
    `<tr><td><input data-k="ff_${i}_forza" class="cell"></td>${tcell(`ff_${i}_rich`)}${tcell(`ff_${i}_int`)}<td><input data-k="ff_${i}_motivo" class="cell"></td></tr>`).join('');

  const sheetEl = $('#sheet');
  sheetEl.addEventListener('input', e => {
    const k = e.target.dataset.k || e.target.dataset.f;
    if (!k) return;
    const s = store.get('sheet', {}); s[k] = e.target.value; store.set('sheet', s);
  });
  sheetEl.addEventListener('click', e => {
    const td = e.target.closest('.yn');
    if (!td) return;
    const s = store.get('sheet', {});
    s[td.dataset.k] = s[td.dataset.k] === td.dataset.v ? '' : td.dataset.v;
    store.set('sheet', s); loadSheet();
  });
  // tocco su casella orario vuota = ora attuale
  sheetEl.addEventListener('focusin', e => {
    if (e.target.classList.contains('time') && !e.target.value) {
      e.target.value = hhmm(new Date());
      e.target.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
}

function buildTagRows() {
  $('#tagRows').innerHTML = tags.map((t, i) =>
    `<tr><td class="name"><b>${t.name}</b></td>${Array.from({ length: TAG_COLUMNS }, (_, c) => tcell(tagKey(i, c))).join('')}</tr>`).join('');
}

function loadSheet() {
  let s = store.get('sheet', null);
  if (!s) {
    const st = shiftStart(), end = new Date(st.getTime() + 6 * 3600000);
    s = { dalleOre: '21:00', dalGiorno: ddmm(st), alleOre: '03:00', alGiorno: ddmm(end) };
    store.set('sheet', s);
  }
  document.querySelectorAll('#sheet [data-k], #sheet [data-f]').forEach(el => {
    if (el.classList.contains('yn')) el.classList.toggle('sel', s[el.dataset.k] === el.dataset.v);
    else el.value = s[el.dataset.k || el.dataset.f] || '';
  });
}

$('#btnPrint').addEventListener('click', () => window.print());
$('#btnNewSheet').addEventListener('click', () => {
  if (!confirm('Cancellare la scheda attuale e iniziarne una nuova?')) return;
  store.set('sheet', null);
  shift = newShift(); saveShift();
  loadSheet(); renderRounds();
});

/* ================= VERIFICA CARROZZE ================= */

const CAR_ROWS = 45;                                   // righe del modulo cartaceo
const CYCLE = { luogo: ['', 'OMV', 'OML'], yn: ['', 'SI', 'NO'] };
let cars = store.get('cars', []);
const saveCars = () => store.set('cars', cars);

function renderCars() {
  const rows = Math.max(CAR_ROWS, cars.length + 1);
  const cyc = (i, f) => `<td class="lu" data-c="${f}" data-r="${i}">${esc(cars[i]?.[f] || '')}</td>`;
  const inp = (i, f, mode) => `<td><input data-c="${f}" data-r="${i}" inputmode="${mode}" value="${esc(cars[i]?.[f] || '')}"></td>`;
  $('#carRows').innerHTML = Array.from({ length: rows }, (_, i) =>
    `<tr>${cyc(i, 'luogo')}${inp(i, 'binario', 'numeric')}${inp(i, 'num', 'numeric')}${cyc(i, 'aperta')}${cyc(i, 'graffiti')}${cyc(i, 'danneggiata')}${inp(i, 'persone', 'numeric')}</tr>`).join('');
  renderCarTotals();
}
function renderCarTotals() {
  const used = cars.filter(c => c && c.num);
  $('#totCtrl').textContent = used.length || '';
  $('#totAperte').textContent = used.filter(c => c.aperta === 'SI').length || '';
  $('#totGraf').textContent = used.filter(c => c.graffiti === 'SI').length || '';
  $('#totPers').textContent = cars.reduce((n, c) => n + (parseInt(c?.persone, 10) || 0), 0) || '';
}
function setCar(i, f, v) {
  while (cars.length <= i) cars.push({});
  cars[i][f] = v;
  saveCars();
}
$('#carRows').addEventListener('input', e => {
  const t = e.target; if (!t.dataset.c) return;
  setCar(+t.dataset.r, t.dataset.c, t.value); renderCarTotals();
});
// tocco su Luogo / SI-NO: cicla tra i valori (vuoto → SI → NO → vuoto)
$('#carRows').addEventListener('click', e => {
  const td = e.target.closest('td.lu'); if (!td) return;
  const i = +td.dataset.r, f = td.dataset.c, list = f === 'luogo' ? CYCLE.luogo : CYCLE.yn;
  const v = list[(list.indexOf(cars[i]?.[f] || '') + 1) % list.length];
  setCar(i, f, v); td.textContent = v; renderCarTotals();
});

// ----- Lettura matricola dalla foto (OCR nel telefono con Tesseract.js) -----
let ocrWorker = null;
function loadScript(src) {
  return new Promise((ok, ko) => { const s = document.createElement('script'); s.src = src; s.onload = ok; s.onerror = ko; document.head.appendChild(s); });
}
async function getOcr() {
  if (ocrWorker) return ocrWorker;
  if (!window.Tesseract) await loadScript('https://cdn.jsdelivr.net/npm/tesseract.js@5.1.1/dist/tesseract.min.js');
  ocrWorker = await Tesseract.createWorker('eng');
  await ocrWorker.setParameters({ tessedit_char_whitelist: '0123456789- ', tessedit_pageseg_mode: '11' });
  return ocrWorker;
}

// Foto → scala di grigi con contrasto aumentato, per aiutare la lettura
function prepareImage(img) {
  const k = Math.min(1, 1600 / Math.max(img.width, img.height));
  const c = document.createElement('canvas');
  c.width = img.width * k; c.height = img.height * k;
  const g = c.getContext('2d'); g.drawImage(img, 0, 0, c.width, c.height);
  const d = g.getImageData(0, 0, c.width, c.height), px = d.data;
  let lo = 255, hi = 0;
  for (let i = 0; i < px.length; i += 4) { const y = (px[i] * 299 + px[i + 1] * 587 + px[i + 2] * 114) / 1000; px[i] = y; if (y < lo) lo = y; if (y > hi) hi = y; }
  const span = Math.max(1, hi - lo);
  for (let i = 0; i < px.length; i += 4) px[i] = px[i + 1] = px[i + 2] = (px[i] - lo) * 255 / span;
  g.putImageData(d, 0, 0);
  return c;
}

// Matricola UIC a 12 cifre: l'ultima è la cifra di controllo
function uicValid(d) {
  if (!/^\d{12}$/.test(d)) return false;
  let sum = 0;
  for (let i = 0; i < 11; i++) { const p = +d[i] * (i % 2 ? 1 : 2); sum += p > 9 ? p - 9 : p; }
  return (10 - sum % 10) % 10 === +d[11];
}
const uicFormat = d => `${d.slice(0, 2)} ${d.slice(2, 4)} ${d.slice(4, 6)}-${d.slice(6, 8)} ${d.slice(8, 11)}-${d[11]}`;

// Dal testo letto ricava i numeri candidati, prima le matricole UIC valide, poi i più lunghi
function carCandidates(text) {
  const groups = (text.match(/\d[\d \-]*\d|\d/g) || []).map(g => g.replace(/\D/g, '')).filter(g => g.length >= 3);
  const all = text.replace(/\D/g, '');
  const out = new Set();
  for (const src of [all, ...groups]) for (let i = 0; i + 12 <= src.length; i++) if (uicValid(src.substr(i, 12))) out.add(uicFormat(src.substr(i, 12)));
  groups.sort((a, b) => b.length - a.length).forEach(g => out.add(g));
  return [...out].slice(0, 5);
}

$('#cPhoto').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  $('#ocrBox').classList.remove('hidden');
  $('#ocrImg').src = URL.createObjectURL(f);
  $('#cNumero').value = ''; $('#ocrAlt').innerHTML = '';
  $('#ocrStatus').textContent = '⏳ Lettura dei numeri in corso… (la prima volta serve connessione)';
  try {
    const img = await new Promise((ok, ko) => { const i = new Image(); i.onload = () => ok(i); i.onerror = ko; i.src = $('#ocrImg').src; });
    const w = await getOcr();
    const { data } = await w.recognize(prepareImage(img));
    const cand = carCandidates(data.text);
    if (!cand.length) { $('#ocrStatus').textContent = '❌ Nessun numero trovato: rifai la foto più da vicino e dritta, oppure scrivilo a mano.'; return; }
    $('#cNumero').value = cand[0];
    $('#ocrStatus').textContent = cand[0].includes('-') ? '✅ Matricola letta e verificata (cifra di controllo corretta)' : '⚠️ Numero letto: controllalo prima di inserirlo';
    $('#ocrAlt').innerHTML = cand.length > 1 ? cand.slice(1).map(c => `<button class="alt" data-alt="${c}">${c}</button>`).join('') : '';
  } catch (err) {
    $('#ocrStatus').textContent = '❌ Lettura non riuscita (serve connessione la prima volta). Scrivi il numero a mano.';
  }
});
$('#ocrAlt').addEventListener('click', e => { const b = e.target.closest('[data-alt]'); if (b) $('#cNumero').value = b.dataset.alt; });

$('#btnAddCar').addEventListener('click', () => {
  const num = $('#cNumero').value.trim();
  if (!num) { $('#cNumero').focus(); return; }
  if (cars.some(c => c?.num === num) && !confirm(`La carrozza ${num} è già nel modulo. Inserirla di nuovo?`)) return;
  let i = cars.findIndex(c => !c?.num);
  if (i < 0) i = cars.length;
  setCar(i, 'num', num);
  setCar(i, 'luogo', $('#cLuogo').value);
  setCar(i, 'binario', $('#cBinario').value.trim());
  store.set('carDefaults', { luogo: $('#cLuogo').value, binario: $('#cBinario').value.trim() });
  renderCars();
  $('#cNumero').value = ''; $('#ocrBox').classList.add('hidden');
  navigator.vibrate?.(100);
  document.querySelector(`#carRows tr:nth-child(${i + 1})`)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
});

$('#btnPrintCar').addEventListener('click', () => window.print());
$('#btnNewCar').addEventListener('click', () => {
  if (!confirm('Cancellare il modulo carrozze attuale e iniziarne uno nuovo?')) return;
  cars = []; saveCars(); renderCars();
});
{ const d = store.get('carDefaults', {}); if (d.luogo) $('#cLuogo').value = d.luogo; $('#cBinario').value = d.binario || ''; }

/* ================= AVVIO ================= */

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
initMap();
renderPhotos();
renderAdmin();
renderCars();
buildSheet();
loadSheet();
startGps();
updateGuide();
tick();
setInterval(tick, 10000);
if (qsAction) { history.replaceState(null, '', location.pathname); handleAlarmAction(qsAction); }
