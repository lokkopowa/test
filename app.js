'use strict';

/* ================= CONFIGURAZIONE ================= */

// Posizioni iniziali INDICATIVE: vanno calibrate sul posto da Impostazioni.
// photo     = foto aerea con la freccia
// nearPhoto = foto ravvicinata del punto esatto (quando disponibile, es. img/tag1-vicino.jpg)
const DEFAULT_TAGS = [
  { id: 'tornio',      name: 'TAG Capannone tornio',     photo: 'img/tag1.jpg', nearPhoto: null, lat: 45.04190, lng: 7.66700 },
  { id: 'dor',         name: 'TAG Palazzina uffici DOR', photo: 'img/tag2.jpg', nearPhoto: null, lat: 45.04120, lng: 7.66840 },
  { id: 'spogliatoio', name: 'TAG Spogliatoio',          photo: 'img/tag3.jpg', nearPhoto: null, lat: 45.04030, lng: 7.66760 },
  { id: 'diesel',      name: 'TAG Piazzale diesel',      photo: 'img/tag4.jpg', nearPhoto: null, lat: 45.03980, lng: 7.66640 },
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
  set(k, v) { try { localStorage.setItem('ronda.' + k, JSON.stringify(v)); } catch {} },
};

let tags = DEFAULT_TAGS.map(t => ({ ...t, ...(store.get('tagpos', {})[t.id] || {}) }));
let nearRadius = store.get('nearRadius', 30);
let targetIdx = store.get('targetIdx', 0);

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

function loadShift() {
  let s = store.get('shift', null);
  if (!s || s.key !== shiftKey()) s = { key: shiftKey(), postpone: {}, notified: {}, done: {}, alarmRound: null };
  return s;
}
let shift = loadShift();
const saveShift = () => store.set('shift', shift);

function roundDue(i) {
  const d = new Date(shiftStart().getTime() + i * ROUND_EVERY_MIN * 60000);
  return new Date(d.getTime() + (shift.postpone[i] || 0) * 60000);
}

// Giro in corso (già scattato e non concluso) oppure il prossimo.
function currentRound() {
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

let map, userMarker, routeLine, tagMarkers = [], dragMode = false;

function initMap() {
  const sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
    { maxZoom: 20, maxNativeZoom: 19, attribution: 'Esri World Imagery' });
  const osm = L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
    { maxZoom: 20, maxNativeZoom: 19, attribution: '© OpenStreetMap' });
  map = L.map('map', { layers: [sat] }).setView([tags[0].lat, tags[0].lng], 17);
  L.control.layers({ 'Satellite': sat, 'Stradale': osm }).addTo(map);

  L.polyline(tags.map(t => [t.lat, t.lng]), { color: '#fff', dashArray: '6 8', weight: 3 }).addTo(map);
  tags.forEach((t, i) => {
    const m = L.marker([t.lat, t.lng], {
      draggable: false,
      icon: L.divIcon({ className: '', html: `<div class="pin" style="background:#0b3d91;color:#fff;border:2px solid #fff;border-radius:50%;width:28px;height:28px;display:flex;align-items:center;justify-content:center;font-weight:700">${i + 1}</div>`, iconSize: [28, 28], iconAnchor: [14, 14] }),
    }).addTo(map).bindPopup(`<b>${t.name}</b><br><img src="${t.photo}" style="width:180px">`);
    m.on('click', () => setTarget(i));
    m.on('dragend', () => { const p = m.getLatLng(); saveTagPos(i, p.lat, p.lng); });
    tagMarkers.push(m);
  });
  routeLine = L.polyline([], { color: '#ffcc00', weight: 5 }).addTo(map);
  map.fitBounds(L.latLngBounds(tags.map(t => [t.lat, t.lng])).pad(0.4));
}

function refreshTagMarkers() {
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

let compassHeading = null;
function onOrientation(e) {
  let h = null;
  if (typeof e.webkitCompassHeading === 'number') h = e.webkitCompassHeading;          // iOS
  else if (e.absolute && typeof e.alpha === 'number') h = (360 - e.alpha) % 360;          // Android
  if (h == null) return;
  const so = (screen.orientation && screen.orientation.angle) || 0;
  compassHeading = heading = (h + so) % 360;
  updateArrow();
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
  if (!pos) return;
  const b = bearing(pos, tags[targetIdx]);
  const rel = heading == null ? b : b - heading;   // senza bussola: freccia rispetto al Nord (mappa)
  $('#arrow').style.transform = `rotate(${rel - 90}deg)`;
}

function updateGuide() {
  const t = tags[targetIdx];
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
  targetIdx = (i + tags.length) % tags.length;
  store.set('targetIdx', targetIdx);
  updateGuide.vibrated = false;
  updateGuide();
}
$('#prevTag').addEventListener('click', () => setTarget(targetIdx - 1));
$('#nextTag').addEventListener('click', () => setTarget(targetIdx + 1));

function renderPhotos() {
  $('#photos').innerHTML = tags.map((t, i) => `<figure data-i="${i}"><img src="${t.photo}" alt="${t.name}" loading="lazy"><figcaption>${i + 1}. ${t.name}</figcaption></figure>`).join('');
  document.querySelectorAll('.photos figure').forEach(f => f.addEventListener('click', () => setTarget(+f.dataset.i)));
}

/* ================= BOLLATURA ================= */

$('#btnBolla').addEventListener('click', () => {
  const r = Math.min(currentRound(), TAG_COLUMNS - 1);
  const key = `tag_${targetIdx}_${r}`;
  const sheet = store.get('sheet', {});
  if (sheet[key] && !confirm(`${tags[targetIdx].name} già bollato alle ${sheet[key]} per il giro ${r + 1}. Sovrascrivere?`)) return;
  sheet[key] = hhmm(new Date());
  store.set('sheet', sheet);
  loadSheet();
  navigator.vibrate?.([100, 50, 100]);
  // Giro concluso quando tutti i TAG del giro hanno un orario
  if (tags.every((_, i) => sheet[`tag_${i}_${r}`])) {
    shift.done[r] = true; saveShift();
    alert(`Giro ${r + 1} completato!`);
    setTarget(0);
  } else {
    // passa al prossimo TAG non ancora bollato in questo giro
    for (let k = 1; k <= tags.length; k++) {
      const j = (targetIdx + k) % tags.length;
      if (!sheet[`tag_${j}_${r}`]) { setTarget(j); break; }
    }
  }
  renderRounds();
});

/* ================= GIRI + AVVISI ================= */

function renderRounds() {
  const cur = currentRound();
  $('#roundsList').innerHTML = Array.from({ length: ROUND_COUNT }, (_, i) => {
    const cls = shift.done[i] ? 'done' : i === cur ? 'next' : '';
    const pp = shift.postpone[i] ? ` (+${shift.postpone[i]}′)` : '';
    return `<span class="${cls}">Giro ${i + 1}: ${hhmm(roundDue(i))}${pp}</span>`;
  }).join('');
  const allDone = Array.from({ length: ROUND_COUNT }, (_, i) => shift.done[i]).every(Boolean);
  if (allDone) { $('#nextRound').textContent = 'Tutti i giri completati'; return; }
  const due = roundDue(cur), mins = Math.round((due - Date.now()) / 60000);
  $('#nextRound').textContent = mins > 0
    ? `Giro ${cur + 1} alle ${hhmm(due)} (tra ${mins >= 60 ? Math.floor(mins / 60) + ' h ' : ''}${mins % 60} min)`
    : `Giro ${cur + 1} IN CORSO (dalle ${hhmm(due)})`;
}

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
  const body = `Ore ${hhmm(roundDue(i))} – inizia il giro. Primo punto: ${tags[0].name}`;
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
    showView('giro'); setTarget(0);
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

function saveTagPos(i, lat, lng) {
  tags[i].lat = lat; tags[i].lng = lng;
  const all = store.get('tagpos', {});
  all[tags[i].id] = { lat, lng };
  store.set('tagpos', all);
  renderCal(); updateGuide();
}
function renderCal() {
  $('#tagCal').innerHTML = tags.map((t, i) => `
    <div class="tagcal">
      <b>${i + 1}. ${t.name}</b>
      <div class="coords">${t.lat.toFixed(6)}, ${t.lng.toFixed(6)}</div>
      <div class="row"><button class="btn small" data-here="${i}">Salva qui la mia posizione</button></div>
    </div>`).join('');
  document.querySelectorAll('[data-here]').forEach(b => b.addEventListener('click', () => {
    if (!pos) return alert('Posizione GPS non ancora disponibile');
    if (pos.acc > 25 && !confirm(`Precisione GPS bassa (±${Math.round(pos.acc)} m). Salvare comunque?`)) return;
    saveTagPos(+b.dataset.here, pos.lat, pos.lng);
  }));
}
$('#btnDrag').addEventListener('click', () => {
  dragMode = !dragMode;
  tagMarkers.forEach(m => dragMode ? m.dragging.enable() : m.dragging.disable());
  $('#btnDrag').textContent = 'Sposta sulla mappa: ' + (dragMode ? 'ON' : 'OFF');
  if (dragMode) showView('giro');
});
$('#btnCopy').addEventListener('click', async () => {
  const txt = JSON.stringify(Object.fromEntries(tags.map(t => [t.id, { lat: +t.lat.toFixed(6), lng: +t.lng.toFixed(6) }])), null, 1);
  try { await navigator.clipboard.writeText(txt); alert('Coordinate copiate'); } catch { prompt('Copia le coordinate:', txt); }
});
$('#nearRadius').value = nearRadius;
$('#nearRadius').addEventListener('change', e => { nearRadius = +e.target.value || 30; store.set('nearRadius', nearRadius); updateGuide(); });

/* ================= SCHEDA ================= */

const yn = k => `<td class="yn" data-k="${k}" data-v="SI">SI</td><td class="yn" data-k="${k}" data-v="NO">NO</td>`;
const tcell = k => `<td class="t"><input data-k="${k}" inputmode="numeric" class="time"></td>`;

function buildSheet() {
  $('#gates').innerHTML = GATES.map((g, i) =>
    `<tr><td class="name">${g}</td>${tcell(`gate_${i}_a_ora`)}${yn(`gate_${i}_a`)}<td class="name">${g}</td>${tcell(`gate_${i}_b_ora`)}${yn(`gate_${i}_b`)}</tr>`).join('');
  $('#tagRows').innerHTML = tags.map((t, i) =>
    `<tr><td class="name"><b>${t.name}</b></td>${Array.from({ length: TAG_COLUMNS }, (_, c) => tcell(`tag_${i}_${c}`)).join('')}</tr>`).join('');
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
  shift = { key: shiftKey(), postpone: {}, notified: {}, done: {}, alarmRound: null }; saveShift();
  loadSheet(); renderRounds();
});

/* ================= AVVIO ================= */

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
initMap();
renderPhotos();
renderCal();
buildSheet();
loadSheet();
startGps();
updateGuide();
tick();
setInterval(tick, 10000);
if (qsAction) { history.replaceState(null, '', location.pathname); handleAlarmAction(qsAction); }
