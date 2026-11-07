'use strict';

/* ================= CONFIGURAZIONE ================= */

// App "vergine": nessun impianto e nessun TAG predefinito. Tutto si configura dall'admin.
// TAG: { id, name, lat, lng, nearPhoto (foto del punto, scattata sul posto), photo (foto aerea, facoltativa) }
const TAG_COLUMNS = 6;         // colonne "Ora verifica" sulla scheda (= numero massimo di giri)
const FFOO_ROWS = 4;
const GATE_ROWS = 3, SHED_ROWS = 4;   // righe del modulo cartaceo quando l'elenco è vuoto
const DEFAULT_SITE = {
  name: '', address: '', idv: '', gpg: 2,
  shiftStart: '21:00', shiftEnd: '03:00',   // orario del turno
  firstRound: '21:00', every: 90, rounds: 4, // primo giro, cadenza in minuti, numero di giri
  gates: [], sheds: [],
};

/* ================= STORAGE ================= */

// Dati del telefono (elenco impianti, impianto attivo, bussola, blocco) e dati di ciascun impianto
// (TAG, orari, schede, mail...) sono separati: ogni impianto ha il proprio prefisso.
const mkStore = prefix => ({
  get(k, def) { try { const v = localStorage.getItem(prefix + k); return v ? JSON.parse(v) : def; } catch { return def; } },
  set(k, v) { try { localStorage.setItem(prefix + k, JSON.stringify(v)); return true; } catch { return false; } },
});
const gstore = mkStore('rondaU.');

// Elenco impianti [{ id, name }] e impianto attivo
let sites = gstore.get('sites', null);
if (!sites) {
  // primo avvio, o versione precedente con un solo impianto: i suoi dati diventano l'impianto "s1"
  sites = [{ id: 's1', name: gstore.get('site', {}).name || '' }];
  for (const k of ['site', 'shift', 'sheet', 'cars', 'carDefaults', 'targetIdx', 'nearRadius', 'reminders', 'mail']) {
    const v = localStorage.getItem('rondaU.' + k);
    if (v != null) { localStorage.setItem('rondaU.s1.' + k, v); localStorage.removeItem('rondaU.' + k); }
  }
  gstore.set('sites', sites); gstore.set('active', 's1');
}
let activeId = gstore.get('active', sites[0].id);
if (!sites.some(x => x.id === activeId)) activeId = sites[0].id;
const store = mkStore(`rondaU.${activeId}.`);

// I TAG con le loro foto stanno in IndexedDB: molto più spazio di localStorage
const idb = (() => {
  let dbp;
  const open = () => dbp ||= new Promise((ok, ko) => {
    const r = indexedDB.open('rondaU', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('kv');
    r.onsuccess = () => ok(r.result); r.onerror = () => ko(r.error);
  });
  const run = async (mode, fn) => {
    const db = await open();
    return new Promise((ok, ko) => { const t = db.transaction('kv', mode); const req = fn(t.objectStore('kv')); t.oncomplete = () => ok(req.result); t.onerror = () => ko(t.error); });
  };
  return { get: k => run('readonly', st => st.get(k)), set: (k, v) => run('readwrite', st => st.put(v, k)), del: k => run('readwrite', st => st.delete(k)) };
})();
const tagsKey = id => 'tags:' + id;

let site = { ...DEFAULT_SITE, ...store.get('site', {}) };
let tags = [];   // caricati all'avvio da IndexedDB
const saveTags = () => idb.set(tagsKey(activeId), tags).catch(() => alert('Memoria del telefono piena: elimina qualche foto.'));
let nearRadius = store.get('nearRadius', 30);
let targetIdx = 0;
let remindersOn = store.get('reminders', true);      // promemoria dei giri attivi/disattivati
let compassOffset = gstore.get('compassOffset', 0);  // correzione bussola in gradi (è del telefono)
let locked = gstore.get('locked', true);             // posizioni TAG bloccate

// Modalità admin (dura finché l'app resta aperta). Nel codice c'è solo l'hash SHA-256 della password.
const ADMIN_HASH = 'e42461f344370e918af4832c8292e68abbbc47196a108f5b05d53b2975e24c17';
let admin = sessionStorage.getItem('rondaU.admin') === '1';
async function sha256(txt) {
  const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(txt));
  return [...new Uint8Array(buf)].map(b => b.toString(16).padStart(2, '0')).join('');
}

/* ================= UTILITY ================= */

const $ = s => document.querySelector(s);

// App Android (Capacitor): usa notifiche, condivisione e file nativi
const NATIVE = !!window.Capacitor?.isNativePlatform?.();
const Plug = NATIVE ? window.Capacitor.Plugins : {};
const pad = n => String(n).padStart(2, '0');
const hhmm = d => pad(d.getHours()) + ':' + pad(d.getMinutes());
const ddmm = d => pad(d.getDate()) + '/' + pad(d.getMonth() + 1);

const toMin = hm => { const [h, m] = String(hm || '0:0').split(':').map(Number); return (h || 0) * 60 + (m || 0); };
const roundCount = () => Math.max(1, Math.min(TAG_COLUMNS, +site.rounds || 1));
const roundEvery = () => Math.max(5, +site.every || 90);
const shiftDur = () => ((toMin(site.shiftEnd) - toMin(site.shiftStart) + 1440) % 1440) || 1440;   // minuti

// Turno di riferimento: quello in corso, oppure il prossimo/ultimo a seconda di quale è più vicino
// (il cambio avviene a metà del tempo libero tra un turno e l'altro; per 21–03 è a mezzogiorno).
function shiftStart(now = new Date()) {
  const half = (1440 - shiftDur()) / 2 * 60000;
  const d = new Date(now); d.setHours(0, toMin(site.shiftStart), 0, 0);
  if (now < d.getTime() - half) d.setDate(d.getDate() - 1);
  else if (now >= d.getTime() + shiftDur() * 60000 + half) d.setDate(d.getDate() + 1);
  return d;
}
const shiftEndDate = (st = shiftStart()) => new Date(st.getTime() + shiftDur() * 60000);
// Orario previsto del giro i (senza posticipi) per il turno che inizia a `st`
function roundBase(i, st = shiftStart()) {
  const off = (toMin(site.firstRound) - toMin(site.shiftStart) + 1440) % 1440;
  return new Date(st.getTime() + (off + i * roundEvery()) * 60000);
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
const saveShift = () => { store.set('shift', shift); scheduleNative(); };

function roundDue(i) {
  return new Date(roundBase(i).getTime() + (shift.postpone[i] || 0) * 60000);
}

// Giro in corso: quello scelto a mano dall'agente, altrimenti il primo non concluso (in ordine di orario).
function currentRound() {
  if (shift.selected != null && !shift.done[shift.selected]) return shift.selected;
  for (let i = 0; i < roundCount(); i++) if (!shift.done[i]) return i;
  return roundCount() - 1;
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
  map = L.map('map', { layers: [sat] }).setView([42.5, 12.5], 6);   // Italia, finché non ci sono TAG o GPS
  L.control.layers({ 'Satellite': sat, 'Stradale': osm }).addTo(map);
  tagLayer = L.layerGroup().addTo(map);
  routeLine = L.polyline([], { color: '#ffcc00', weight: 5 }).addTo(map);
  drawTags();
  fitTags();
}

// Inquadra automaticamente i TAG (o, se non ce ne sono, la posizione dell'agente)
function fitTags() {
  if (tags.length === 1) map.setView([tags[0].lat, tags[0].lng], 18);
  else if (tags.length > 1) map.fitBounds(L.latLngBounds(tags.map(t => [t.lat, t.lng])).pad(0.3), { maxZoom: 19 });
  else if (pos) map.setView([pos.lat, pos.lng], 17);
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
    if (!userMarker) {
      userMarker = L.circleMarker([pos.lat, pos.lng], { radius: 8, color: '#fff', fillColor: '#1e90ff', fillOpacity: 1, weight: 3 }).addTo(map);
      if (!tags.length) fitTags();   // app ancora vuota: centra la mappa su dove ci si trova
    }
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
  $('#setupHint').classList.toggle('hidden', tags.length > 0);
  if (!t) { $('#targetName').textContent = 'Nessun TAG configurato'; $('#distance').textContent = '— m'; return; }
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
  $('#remState').textContent = remindersOn ? '🔔 Promemoria dei giri attivi' : '🔕 Promemoria dei giri disattivati (i giri si avviano a mano)';
  const cur = currentRound();
  $('#roundsList').innerHTML = Array.from({ length: roundCount() }, (_, i) => {
    const cls = shift.skipped[i] ? 'skipped' : shift.done[i] ? 'done' : i === cur ? 'next' : '';
    const pp = shift.postpone[i] ? ` (+${shift.postpone[i]}′)` : '';
    const st = shift.skipped[i] ? ' · saltato' : shift.done[i] ? ' ✔' : '';
    return `<button class="${cls}" data-round="${i}">Giro ${i + 1}: ${hhmm(roundDue(i))}${pp}${st}</button>`;
  }).join('');
  const allDone = Array.from({ length: roundCount() }, (_, i) => shift.done[i]).every(Boolean);
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
  for (let i = 0; i < roundCount(); i++) {
    const due = roundDue(i).getTime();
    if (remindersOn && !shift.done[i] && !shift.notified[i] && now >= due && now - due < 60 * 60000) {
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
  const title = `Giro ronda ${i + 1} di ${roundCount()}`;
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
  if (action === 'none') { shift.alarmRound = null; saveShift(); return; }
  if (action === 'start') {
    showView('giro'); selectRound(i);
  } else {
    const min = parseInt(action, 10) || 10;
    // posticipo a partire da ora (non dall'orario previsto)
    const base = roundBase(i).getTime();
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
async function requestWake() {
  try { if (NATIVE) await Plug.KeepAwake.keepAwake(); else wakeLock = await navigator.wakeLock?.request('screen'); } catch {}
}
$('#btnShift').addEventListener('click', async () => {
  shiftOn = !shiftOn;
  $('#btnShift').textContent = shiftOn ? 'Turno attivo ●' : 'Avvia turno';
  $('#btnShift').classList.toggle('on', shiftOn);
  if (shiftOn) {
    unlockAudio();
    await requestWake();
    if ('Notification' in window && Notification.permission === 'default') await Notification.requestPermission();
    enableCompass();
  } else { wakeLock?.release(); wakeLock = null; if (NATIVE) Plug.KeepAwake.allowSleep().catch(() => {}); }
});
document.addEventListener('visibilitychange', () => { if (shiftOn && document.visibilityState === 'visible') requestWake(); tick(); });

/* ================= IMPOSTAZIONI ================= */

$('#btnNotif').addEventListener('click', async () => {
  if (NATIVE) return nativePermissions(true);
  if (!('Notification' in window)) return alert('Notifiche non supportate da questo browser. Installa l\'app sulla schermata Home.');
  const p = await Notification.requestPermission();
  alert(p === 'granted' ? 'Notifiche abilitate' : 'Notifiche non consentite');
});
$('#btnTest').addEventListener('click', () => {
  unlockAudio(); fireAlarm(currentRound());
  if (NATIVE) Plug.LocalNotifications.schedule({ notifications: [{ id: 999, title: 'Prova avviso giro ronda', body: 'Se leggi questo, le notifiche funzionano anche ad app chiusa.',
    schedule: { at: new Date(Date.now() + 15000), allowWhileIdle: true }, channelId: 'ronda', smallIcon: 'ic_stat_ronda' }] })
    .then(() => toast('🔔 Tra 15 secondi arriva una notifica di prova: puoi anche chiudere l\'app'));
});

$('#btnIcs').addEventListener('click', () => {
  const fmt = d => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}T${pad(d.getHours())}${pad(d.getMinutes())}00`;
  const lines = ['BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//Ronda Vigilanza//IT', 'CALSCALE:GREGORIAN'];
  for (let i = 0; i < roundCount(); i++) {
    const d = roundBase(i);
    const e = new Date(d.getTime() + 30 * 60000);
    lines.push('BEGIN:VEVENT', `UID:ronda-vigilanza-${i}@ronda`, `DTSTAMP:${fmt(new Date())}`, `DTSTART:${fmt(d)}`, `DTEND:${fmt(e)}`,
      'RRULE:FREQ=DAILY', `SUMMARY:Giro ronda ${i + 1}${site.name ? ' - ' + site.name : ''}`,
      'BEGIN:VALARM', 'ACTION:DISPLAY', `DESCRIPTION:Giro ronda ${i + 1}`, 'TRIGGER:PT0M', 'END:VALARM', 'END:VEVENT');
  }
  lines.push('END:VCALENDAR');
  downloadFiles([new File([lines.join('\r\n')], 'giri-ronda.ics', { type: 'text/calendar' })]);
});

/* ================= AMMINISTRAZIONE TAG ================= */

const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

// Ogni modifica all'elenco TAG passa da qui: salva e aggiorna mappa, foto, scheda e pannello
function tagsChanged(fit = false) {
  saveTags();
  targetIdx = Math.max(0, Math.min(targetIdx, tags.length - 1));
  drawTags(); renderPhotos(); buildTagRows(); loadSheet(); renderAdmin(); updateGuide();
  if (fit) fitTags();
}

function saveTagPos(i, lat, lng) {
  if (!canEdit()) return;
  tags[i].lat = lat; tags[i].lng = lng;
  tagsChanged();
}

const validLat = v => isFinite(v) && Math.abs(v) <= 90;
const validLng = v => isFinite(v) && Math.abs(v) <= 180;
const num = s => parseFloat(String(s).replace(',', '.'));

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
        ${t.nearPhoto ? `<img class="thumb" src="${t.nearPhoto}" alt="">` : '<span class="nophoto">nessuna foto</span>'}
        <label class="file">📷 Foto del TAG <input type="file" accept="image/*" capture="environment" data-p="nearPhoto" ${dis}></label>
        <label class="file">🗺️ Foto aerea (facoltativa) <input type="file" accept="image/*" data-p="photo" ${dis}></label>
      </div>
    </div>`).join('');
}

$('#tagEdit').addEventListener('change', async e => {
  if (!canEdit()) return;
  const box = e.target.closest('[data-i]'); if (!box) return;
  const i = +box.dataset.i, f = e.target.dataset.e, p = e.target.dataset.p;
  let fit = false;
  if (f === 'name') { tags[i].name = e.target.value.trim() || tags[i].name; }
  else if (f === 'lat' || f === 'lng') {
    const v = num(e.target.value);
    if (!(f === 'lat' ? validLat(v) : validLng(v))) { alert('Coordinata non valida'); return renderAdmin(); }
    tags[i][f] = v; fit = true;
  } else if (p && e.target.files[0]) {
    try { tags[i][p] = await readPhoto(e.target.files[0]); } catch { return alert('Impossibile leggere la foto'); }
  } else return;
  tagsChanged(fit);
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
  tagsChanged(b.dataset.a !== 'up' && b.dataset.a !== 'down');
});

// ----- Nuovo TAG: nome + coordinate (GPS o scritte) + foto scattata sul posto -----
let ntPhoto = null;
$('#ntHere').addEventListener('click', () => {
  if (!canEdit()) return;
  if (!pos) return alert('Posizione GPS non ancora disponibile: attendi qualche secondo all\'aperto.');
  if (pos.acc > 25 && !confirm(`Precisione GPS bassa (±${Math.round(pos.acc)} m). Usare comunque questa posizione?`)) return;
  $('#ntLat').value = pos.lat.toFixed(6); $('#ntLng').value = pos.lng.toFixed(6);
});
$('#ntPhoto').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  try { ntPhoto = await readPhoto(f); } catch { return alert('Impossibile leggere la foto'); }
  $('#ntPreview').src = ntPhoto; $('#ntPreview').classList.remove('hidden');
});
$('#btnAddTag').addEventListener('click', () => {
  if (!canEdit()) return;
  const name = $('#ntName').value.trim(), lat = num($('#ntLat').value), lng = num($('#ntLng').value);
  if (!name) { $('#ntName').focus(); return alert('Scrivi il nome del TAG'); }
  if (!validLat(lat) || !validLng(lng)) return alert('Inserisci le coordinate (o premi "📍 Usa mia posizione" davanti al TAG)');
  if (!ntPhoto && !confirm('Nessuna foto del TAG. Salvare comunque? (la foto si può aggiungere dopo)')) return;
  tags.push({ id: 't' + Date.now().toString(36), name, photo: null, nearPhoto: ntPhoto, lat, lng });
  ntPhoto = null;
  ['#ntName', '#ntLat', '#ntLng'].forEach(s => $(s).value = '');
  $('#ntPreview').classList.add('hidden');
  tagsChanged(true);
  toast(`✅ ${name} salvato (${tags.length} TAG in tutto)`);
});

function toggleDrag(on) {
  dragMode = on && canEdit();
  tagMarkers.forEach(m => dragMode ? m.dragging.enable() : m.dragging.disable());
  $('#btnDrag').textContent = 'Sposta sulla mappa: ' + (dragMode ? 'ON' : 'OFF');
}
$('#btnDrag').addEventListener('click', () => { toggleDrag(!dragMode); if (dragMode) showView('giro'); });

$('#btnLock').addEventListener('click', () => {
  if (!admin) return;
  locked = !locked; gstore.set('locked', locked);
  renderAdmin();
});

$('#btnAdmin').addEventListener('click', async () => {
  const pw = $('#adminPw').value;
  $('#adminPw').value = '';
  let ok = false;
  try { ok = (await sha256(pw)) === ADMIN_HASH; } catch { alert('Accesso admin disponibile solo da connessione sicura (https)'); return; }
  if (!ok) return alert('Password errata');
  admin = true; sessionStorage.setItem('rondaU.admin', '1');
  renderAdmin(); renderSites();
});
$('#adminPw').addEventListener('keydown', e => { if (e.key === 'Enter') $('#btnAdmin').click(); });
$('#btnLogout').addEventListener('click', () => {
  admin = false; sessionStorage.removeItem('rondaU.admin');
  locked = true; gstore.set('locked', true);   // uscendo si ribloccano sempre le posizioni
  renderAdmin(); renderSites();
});

$('#nearRadius').addEventListener('change', e => {
  if (!admin) return;
  nearRadius = +e.target.value || 30; store.set('nearRadius', nearRadius); updateGuide();
});

// ----- Calibrazione bussola -----
function setOffset(v) {
  compassOffset = ((v + 540) % 360) - 180;   // tra -180 e +180
  gstore.set('compassOffset', compassOffset);
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
  const data = { versione: 2, site, tags, nearRadius, compassOffset };
  const nome = (site.name || 'impianto').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
  downloadFiles([new File([JSON.stringify(data, null, 1)], `ronda-config-${nome}.json`, { type: 'application/json' })]);
});
$('#importFile').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f || !canEdit()) return alert('Sblocca le posizioni per importare una configurazione');
  try {
    const data = JSON.parse(await f.text());
    if (!Array.isArray(data.tags) || !data.tags.every(t => t.id && t.name && isFinite(t.lat) && isFinite(t.lng))) throw 0;
    if (!confirm(`Sostituire la configurazione attuale con quella del file (${data.site?.name || 'impianto'}, ${data.tags.length} TAG)?`)) return;
    tags = data.tags;
    if (data.site) saveSite({ ...DEFAULT_SITE, ...data.site });
    if (isFinite(data.nearRadius)) { nearRadius = data.nearRadius; store.set('nearRadius', nearRadius); }
    if (isFinite(data.compassOffset)) setOffset(data.compassOffset);
    tagsChanged(true);
  } catch { alert('File di configurazione non valido'); }
});
$('#btnResetTags').addEventListener('click', () => {
  if (!canEdit() || !confirm('Eliminare TUTTI i TAG con le loro foto? Conviene prima fare "Esporta file".')) return;
  tags = [];
  tagsChanged();
});

/* ================= IMPIANTI (più siti nella stessa app) ================= */

const siteLabel = x => x.name || 'Impianto senza nome';
function renderSites() {
  $('#siteSelect').innerHTML = sites.map(x => `<option value="${x.id}" ${x.id === activeId ? 'selected' : ''}>${esc(siteLabel(x))}</option>`).join('');
  $('#siteAdmin').classList.toggle('hidden', !admin);
  $('#btnDelSite').disabled = sites.length < 2;
}
// Cambio impianto: si salva la scelta e si ricarica l'app con i dati di quell'impianto
function switchSite(id) {
  gstore.set('active', id);
  location.reload();
}
$('#siteSelect').addEventListener('change', e => {
  const x = sites.find(y => y.id === e.target.value);
  if (!confirm(`Passare all'impianto "${siteLabel(x)}"?`)) return renderSites();
  switchSite(x.id);
});
$('#btnNewSite').addEventListener('click', () => {
  if (!admin) return;
  const name = prompt('Nome del nuovo impianto (es. Torino Porta Susa):', '');
  if (!name || !name.trim()) return;
  const id = 's' + Date.now().toString(36);
  sites.push({ id, name: name.trim() });
  gstore.set('sites', sites);
  mkStore(`rondaU.${id}.`).set('site', { ...DEFAULT_SITE, name: name.trim() });
  alert(`Impianto "${name.trim()}" creato. Ora compila i dati dell'impianto e aggiungi i TAG.`);
  switchSite(id);
});
$('#btnDelSite').addEventListener('click', async () => {
  if (!admin || sites.length < 2) return;
  if (!confirm(`Eliminare l'impianto "${siteLabel(site)}" con tutti i suoi TAG, foto e schede? Conviene prima fare "Esporta file".`)) return;
  if (prompt('Per confermare scrivi ELIMINA') !== 'ELIMINA') return;
  Object.keys(localStorage).filter(k => k.startsWith(`rondaU.${activeId}.`)).forEach(k => localStorage.removeItem(k));
  try { await idb.del(tagsKey(activeId)); } catch {}
  sites = sites.filter(x => x.id !== activeId);
  gstore.set('sites', sites);
  switchSite(sites[0].id);
});
$('#topName').addEventListener('click', () => { showView('set'); $('#siteCard').scrollIntoView({ behavior: 'smooth' }); });

/* ================= DATI IMPIANTO E TURNO ================= */

// Mette i dati dell'impianto in intestazione, moduli e testi
function applySite() {
  document.title = site.name ? `Ronda ${site.name}` : 'Ronda Vigilanza';
  $('#topName').textContent = site.name ? `Ronda ${site.name}` : 'Ronda – impianto da configurare';
  $('#topAddr').textContent = site.address || '';
  $('#topSep').classList.toggle('hidden', !site.address);
  $('#rptSite').textContent = [site.name.toUpperCase(), site.address].filter(Boolean).join(' – ') || '\u00a0';
  $('#gpgImpianto').textContent = site.name.toUpperCase();
  $('#gpgIndirizzo').textContent = site.address;
  $('#gpgIdv').textContent = site.idv.toUpperCase();
  const n = +site.gpg || 0;
  $('#gpgPrev').textContent = n ? `${n}${NUMERI[n] ? ` (${NUMERI[n]})` : ''}` : '';
  $('#gpgOrario').textContent = `${site.shiftStart}/${site.shiftEnd}`;
  const times = Array.from({ length: roundCount() }, (_, i) => hhmm(roundBase(i))).join(', ');
  document.querySelectorAll('.schedText').forEach(el => el.textContent = `Giri alle ${times} (ogni ${roundEvery()} minuti).`);
}

function renderSiteForm() {
  const f = $('#siteForm');
  ['name', 'address', 'idv', 'gpg', 'shiftStart', 'shiftEnd', 'firstRound', 'every', 'rounds'].forEach(k => f.elements[k].value = site[k]);
  f.elements.gates.value = site.gates.join('\n');
  f.elements.sheds.value = site.sheds.join('\n');
}

// Valori di intestazione che la scheda prende dal turno (date e orari)
function autoSheetFields() {
  const st = shiftStart(), end = shiftEndDate(st);
  return { dalleOre: site.shiftStart, dalGiorno: ddmm(st), alleOre: site.shiftEnd, alGiorno: ddmm(end), gpgGiorno: `${ddmm(st)}/${st.getFullYear()}` };
}

function saveSite(next) {
  const before = autoSheetFields();
  site = next;
  store.set('site', site);
  sites = sites.map(x => x.id === activeId ? { ...x, name: site.name } : x); gstore.set('sites', sites); renderSites();
  // aggiorna date/orari della scheda in corso, ma solo quelli non modificati a mano
  const sh = store.get('sheet', null), after = autoSheetFields();
  if (sh) { for (const k in after) if (!sh[k] || sh[k] === before[k]) sh[k] = after[k]; store.set('sheet', sh); }
  if (shift.key !== shiftKey()) shift = loadShift();
  applySite(); buildSheetRows(); loadSheet(); renderRounds(); renderSiteForm(); scheduleNative();
}

$('#siteForm').addEventListener('submit', e => {
  e.preventDefault();
  if (!admin) return;
  const f = e.target.elements, lines = v => v.split('\n').map(x => x.trim()).filter(Boolean);
  const hm = /^\d{1,2}:\d{2}$/;
  if (![f.shiftStart.value, f.shiftEnd.value, f.firstRound.value].every(v => hm.test(v))) return alert('Orari non validi (formato HH:MM)');
  saveSite({
    ...site,
    name: f.name.value.trim(), address: f.address.value.trim(), idv: f.idv.value.trim(),
    gpg: Math.max(0, parseInt(f.gpg.value, 10) || 0),
    shiftStart: f.shiftStart.value, shiftEnd: f.shiftEnd.value, firstRound: f.firstRound.value,
    every: Math.max(5, parseInt(f.every.value, 10) || 90),
    rounds: Math.max(1, Math.min(TAG_COLUMNS, parseInt(f.rounds.value, 10) || 1)),
    gates: lines(f.gates.value), sheds: lines(f.sheds.value),
  });
  toast('✅ Dati impianto salvati');
});

// Promemoria dei giri: attivabili/disattivabili da chiunque
$('#remindersOn').checked = remindersOn;
$('#remindersOn').addEventListener('change', e => {
  remindersOn = e.target.checked;
  store.set('reminders', remindersOn);
  if (!remindersOn && shift.alarmRound != null) handleAlarmAction('none');
  renderRounds(); scheduleNative();
  toast(remindersOn ? '🔔 Promemoria dei giri attivati' : '🔕 Promemoria dei giri disattivati');
});

/* ================= SCHEDA ================= */

const yn = k => `<td class="yn" data-k="${k}" data-v="SI">SI</td><td class="yn" data-k="${k}" data-v="NO">NO</td>`;
const tcell = k => `<td class="t"><input data-k="${k}" inputmode="numeric" class="time"></td>`;
const dcell = k => `<td class="t"><input data-k="${k}" inputmode="numeric" class="date"></td>`;
const GPG_ROWS = 4;
const NUMERI = ['zero', 'uno', 'due', 'tre', 'quattro', 'cinque', 'sei', 'sette', 'otto', 'nove', 'dieci'];

// Elenco con le righe del modulo: quelle configurate, oppure righe vuote come sul cartaceo
const rowsOf = (list, min) => list.length ? list : Array(min).fill('');

function buildSheetRows() {
  $('#gates').innerHTML = rowsOf(site.gates, GATE_ROWS).map((g, i) =>
    `<tr><td class="name">${esc(g)}</td>${tcell(`gate_${i}_a_ora`)}${yn(`gate_${i}_a`)}<td class="name">${esc(g)}</td>${tcell(`gate_${i}_b_ora`)}${yn(`gate_${i}_b`)}</tr>`).join('');
  buildTagRows();
  $('#sheds').innerHTML = rowsOf(site.sheds, SHED_ROWS).map((s, i) =>
    `<tr><td>${esc(s)}</td><td style="width:70px">chiusura</td>${yn(`shed_${i}`)}<td><input data-k="shed_${i}_note" class="cell"></td></tr>`).join('');
  // riga capannoni: nome | chiusura | SI | NO | note
  document.querySelector('#sheds').closest('table').querySelector('th').colSpan = 5;
  $('#ffoo').innerHTML = Array.from({ length: FFOO_ROWS }, (_, i) =>
    `<tr><td><input data-k="ff_${i}_forza" class="cell"></td>${tcell(`ff_${i}_rich`)}${tcell(`ff_${i}_int`)}<td><input data-k="ff_${i}_motivo" class="cell"></td></tr>`).join('');

  $('#gpgRows').innerHTML = Array.from({ length: GPG_ROWS }, (_, i) =>
    `<tr><td class="name">GPG ${i < 2 ? i + 1 : '__'}</td><td><input data-k="gpg_${i}_nome" class="cell"></td>` +
    `${dcell(`gpg_${i}_d1`)}${tcell(`gpg_${i}_o1`)}${dcell(`gpg_${i}_d2`)}${tcell(`gpg_${i}_o2`)}</tr>`).join('');
}

function buildSheet() {
  buildSheetRows();
  // Report fine turno e Consuntivazione GPG condividono lo stesso salvataggio
  for (const sheetEl of [$('#sheet'), $('#gpgSheet')]) {
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
  // tocco su casella orario/data vuota = ora/data attuale
  sheetEl.addEventListener('focusin', e => {
    const t = e.target;
    if (t.value || !(t.classList.contains('time') || t.classList.contains('date'))) return;
    t.value = t.classList.contains('time') ? hhmm(new Date()) : ddmm(new Date());
    t.dispatchEvent(new Event('input', { bubbles: true }));
  });
  }
}

// Selettore Report fine turno / Consuntivazione GPG
document.querySelectorAll('[data-sheet]').forEach(b => b.addEventListener('click', () => {
  document.querySelectorAll('[data-sheet]').forEach(x => x.classList.toggle('active', x === b));
  ['sheet', 'gpgSheet'].forEach(id => $('#' + id).classList.toggle('hidden', id !== b.dataset.sheet));
}));

function buildTagRows() {
  $('#tagRows').innerHTML = rowsOf(tags, 4).map((t, i) => !t ? `<tr><td class="name">&nbsp;</td>${'<td class="t"></td>'.repeat(TAG_COLUMNS)}</tr>` :
    `<tr><td class="name"><b>${esc(t.name)}</b></td>${Array.from({ length: TAG_COLUMNS }, (_, c) => tcell(tagKey(i, c))).join('')}</tr>`).join('');
}

function loadSheet() {
  let s = store.get('sheet', null);
  if (!s) {
    s = autoSheetFields();
    store.set('sheet', s);
  }
  document.querySelectorAll('#sheet [data-k], #sheet [data-f], #gpgSheet [data-k]').forEach(el => {
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

/* ================= ESPORTAZIONE JPG + INVIO EMAIL ================= */

// Schede da esportare: [elemento, nome file]
const EXPORT_SHEETS = [
  ['#sheet', 'Report_fine_turno'],
  ['#carSheet', 'Verifica_carrozze'],
  ['#gpgSheet', 'Consuntivazione_GPG'],
];

let mail = store.get('mail', { to: '', cc: '', subject: 'Report servizio {impianto} - {data}' });
['to', 'cc', 'subject'].forEach(k => {
  const el = $('#mail' + k[0].toUpperCase() + k.slice(1));
  el.value = mail[k] || '';
  el.addEventListener('change', () => { mail[k] = el.value.trim(); store.set('mail', mail); });
});

function toast(msg, ms = 4000) {
  $('#toast').textContent = msg; $('#toast').classList.remove('hidden');
  clearTimeout(toast.t); toast.t = setTimeout(() => $('#toast').classList.add('hidden'), ms);
}

// Data del turno "26-27/10" per oggetto e nomi file
function shiftLabel() {
  const st = shiftStart(), end = shiftEndDate(st);
  return st.getDate() === end.getDate() ? `${ddmm(st)}/${st.getFullYear()}`
    : `${pad(st.getDate())}-${pad(end.getDate())}/${pad(end.getMonth() + 1)}/${end.getFullYear()}`;
}

// Fotografa una scheda: la copia (con i valori scritti) fuori schermo a larghezza fissa e la rende in JPG
async function sheetToJpg(sel) {
  if (!window.html2canvas) await loadScript('https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js');
  const src = $(sel);
  src.querySelectorAll('input, textarea').forEach(el => {
    if (el.tagName === 'TEXTAREA') el.textContent = el.value; else el.setAttribute('value', el.value);
  });
  const stage = document.createElement('div');
  stage.className = 'export-stage';
  const clone = src.cloneNode(true);
  clone.removeAttribute('id');
  clone.classList.remove('hidden');
  // i campi diventano testo semplice: in JPG si leggono meglio
  clone.querySelectorAll('textarea').forEach(t => { const d = document.createElement('div'); d.style.whiteSpace = 'pre-wrap'; d.style.minHeight = '200px'; d.style.color = '#1d3fa8'; d.textContent = t.value; t.replaceWith(d); });
  clone.querySelectorAll('input').forEach(i => { const sp = document.createElement('span'); sp.textContent = i.value; sp.style.color = '#1d3fa8'; sp.style.display = 'inline-block'; sp.style.minWidth = '40px';
    if (i.closest('td.t')) { sp.style.width = '100%'; sp.style.textAlign = 'center'; }
    i.replaceWith(sp); });
  stage.appendChild(clone);
  document.body.appendChild(stage);
  try {
    const canvas = await html2canvas(clone, { scale: 2, backgroundColor: '#ffffff', logging: false });
    return await new Promise(r => canvas.toBlob(r, 'image/jpeg', 0.85));
  } finally { stage.remove(); }
}

async function buildJpgs() {
  const tag = shiftLabel().replace(/\//g, '-');
  const files = [];
  for (const [sel, name] of EXPORT_SHEETS) {
    if (!$(sel)) continue;
    files.push(new File([await sheetToJpg(sel)], `${name}_${tag}.jpg`, { type: 'image/jpeg' }));
  }
  return files;
}

// Nell'app Android i file si scrivono nella cache e si aprono con la condivisione (salva su Drive, invia, ecc.)
function blobToBase64(blob) {
  return new Promise((ok, ko) => { const fr = new FileReader(); fr.onload = () => ok(fr.result.split(',')[1]); fr.onerror = ko; fr.readAsDataURL(blob); });
}
async function nativeWrite(files) {
  const uris = [];
  for (const f of files) uris.push((await Plug.Filesystem.writeFile({ path: f.name, data: await blobToBase64(f), directory: 'CACHE' })).uri);
  return uris;
}

function downloadFiles(files) {
  if (NATIVE) {
    nativeWrite(files).then(uris => Plug.Share.share({ title: files.map(f => f.name).join(', '), files: uris, dialogTitle: 'Salva o invia' }))
      .catch(e => { if (!/cancel/i.test(e?.message || '')) toast('❌ Salvataggio non riuscito'); });
    return;
  }
  files.forEach((f, i) => setTimeout(() => {
    const a = document.createElement('a'); a.href = URL.createObjectURL(f); a.download = f.name; a.click();
  }, i * 400));
}

const recipients = s => (s || '').split(/[,;\s]+/).filter(Boolean).join(',');
const siteFull = () => [site.name, site.address].filter(Boolean).join(' – ');
const mailSubject = () => (mail.subject || 'Report servizio').replace('{data}', shiftLabel()).replace('{impianto}', site.name || '').replace(/\s+-\s+-/, ' -').trim();
const mailBody = () => `Buongiorno,\nin allegato le schede del servizio di vigilanza${siteFull() ? ' ' + siteFull() : ''}, turno ${shiftLabel()}.\n\nCordiali saluti`;

async function sendSheets() {
  if (!recipients(mail.to)) { showView('set'); $('#mailTo').focus(); return toast('Inserisci prima i destinatari nelle Opzioni'); }
  toast('⏳ Preparo le schede in JPG…', 20000);
  let files;
  try { files = await buildJpgs(); } catch { return toast('❌ Impossibile creare le immagini (serve connessione la prima volta)'); }

  // App Android: Outlook si apre con destinatari, oggetto, testo e allegati già compilati
  if (NATIVE) {
    try {
      await nativeWrite(files);
      const list = s => (s || '').split(',').filter(Boolean);
      const r = await Plug.Mail.compose({ to: list(recipients(mail.to)), cc: list(recipients(mail.cc)), subject: mailSubject(), body: mailBody(), files: files.map(f => f.name) });
      toast(r.outlook ? '📧 Mail pronta in Outlook: controlla e premi Invia' : 'Outlook non trovato: scegli l\'app di posta', 6000);
    } catch (e) { toast('❌ ' + (e?.message || 'Impossibile aprire la mail')); }
    return;
  }

  // Telefono: si allegano i JPG tramite la condivisione → scegliere Outlook.
  // La condivisione non permette di precompilare i destinatari: li copio negli appunti da incollare nel campo "A".
  if (navigator.canShare && navigator.canShare({ files })) {
    try { await navigator.clipboard.writeText(recipients(mail.to).replace(/,/g, '; ')); } catch {}
    toast('📋 Destinatari copiati: in Outlook tieni premuto sul campo "A" e scegli Incolla', 8000);
    try {
      await navigator.share({ files, title: mailSubject(), text: mailBody() });
    } catch (e) { if (e.name !== 'AbortError') toast('❌ Condivisione non riuscita'); }
    return;
  }
  // Computer: scarico i JPG e apro una nuova mail con destinatari e oggetto già compilati, poi si allegano i file scaricati
  downloadFiles(files);
  const q = new URLSearchParams({ subject: mailSubject(), body: mailBody() + '\n\n(allegare i file JPG appena scaricati)' });
  if (recipients(mail.cc)) q.set('cc', recipients(mail.cc));
  location.href = `mailto:${recipients(mail.to)}?${q.toString().replace(/\+/g, '%20')}`;
  toast('📎 JPG scaricati: trascinali nella mail che si è aperta', 8000);
}

document.querySelectorAll('.btnSend').forEach(b => b.addEventListener('click', sendSheets));
$('#btnDownloadJpg').addEventListener('click', async () => {
  toast('⏳ Preparo le schede in JPG…', 20000);
  try { downloadFiles(await buildJpgs()); toast('✅ Schede scaricate'); } catch { toast('❌ Impossibile creare le immagini'); }
});

/* ================= NOTIFICHE NATIVE (app Android) ================= */

// Programma nel telefono gli avvisi dei giri: arrivano anche ad app chiusa o telefono in standby.
// Turno attuale (con posticipi, saltando i giri già fatti/avviati) + i 6 giorni successivi.
var scheduleTimer = null;   // var: usata da saveShift anche prima di questa riga
function scheduleNative() {
  if (!NATIVE) return;
  clearTimeout(scheduleTimer);
  scheduleTimer = setTimeout(async () => {
    try {
      const LN = Plug.LocalNotifications;
      const { notifications: pend } = await LN.getPending();
      const old = pend.filter(n => n.id < 900);
      if (old.length) await LN.cancel({ notifications: old.map(n => ({ id: n.id })) });
      if (!remindersOn) return;   // promemoria disattivati: nessun avviso programmato
      const now = Date.now(), list = [];
      for (let day = 0; day < 7; day++) {
        const base = shiftStart(); base.setDate(base.getDate() + day);
        for (let i = 0; i < roundCount(); i++) {
          let at = roundBase(i, base);
          if (day === 0) { if (shift.done[i] || shift.notified[i]) continue; at = roundDue(i); }
          if (at.getTime() <= now) continue;
          list.push({ id: day * 10 + i + 1, title: `Giro ronda ${i + 1} di ${roundCount()}${site.name ? ' – ' + site.name : ''}`, body: `Ore ${hhmm(at)} – inizia il giro ronda`,
            schedule: { at, allowWhileIdle: true }, actionTypeId: 'RONDA', extra: { round: i, day }, channelId: 'ronda', smallIcon: 'ic_stat_ronda' });
        }
      }
      if (list.length) await LN.schedule({ notifications: list });
    } catch (e) { console.warn('notifiche', e); }
  }, 500);
}

async function nativePermissions(explain) {
  const LN = Plug.LocalNotifications;
  const p = await LN.requestPermissions();
  if (p.display !== 'granted') return alert('Senza il permesso per le notifiche non arriveranno gli avvisi dei giri. Puoi attivarlo dalle impostazioni del telefono.');
  // Android 12+: le sveglie esatte vanno consentite a parte, altrimenti l'avviso può arrivare in ritardo
  try {
    const ex = await LN.checkExactNotificationSetting();
    if (ex.exact_alarm !== 'granted' && confirm('Per avere gli avvisi all\'ora esatta, consenti "Sveglie e promemoria" per Ronda Vigilanza nella schermata che si apre.')) await LN.changeExactNotificationSetting();
  } catch {}
  if (explain) alert('Notifiche attive: gli avvisi dei giri arrivano anche ad app chiusa.');
}

async function initNative() {
  document.querySelectorAll('.web-only').forEach(el => el.classList.add('hidden'));
  document.querySelectorAll('.native-only').forEach(el => el.classList.remove('hidden'));
  const LN = Plug.LocalNotifications;
  try {
    await LN.createChannel({ id: 'ronda', name: 'Giri ronda', description: 'Avvisi di inizio giro', importance: 5, visibility: 1, vibration: true, lights: true });
    await LN.registerActionTypes({ types: [{ id: 'RONDA', actions: [
      { id: 'start', title: 'Inizia giro', foreground: true },
      { id: '15', title: 'Posticipa 15 min', foreground: true },
    ] }] });
    LN.addListener('localNotificationActionPerformed', a => {
      const x = a.notification?.extra || {};
      if (x.round == null) return;
      if (shift.key !== shiftKey()) shift = loadShift();
      shift.alarmRound = x.round; shift.notified[x.round] = true;
      handleAlarmAction(a.actionId === 'tap' ? 'start' : a.actionId);
    });
    await nativePermissions(false);
  } catch (e) { console.warn(e); }
  scheduleNative();
}

/* ================= AVVIO ================= */

(async () => {
  try {
    tags = await idb.get(tagsKey(activeId));
    if (!tags && activeId === 's1') {   // TAG della versione precedente (un solo impianto)
      tags = await idb.get('tags');
      if (tags) { await idb.set(tagsKey('s1'), tags); await idb.del('tags'); }
    }
    tags ||= [];
  } catch { tags = []; }
  renderSites();
  targetIdx = Math.max(0, Math.min(store.get('targetIdx', 0), tags.length - 1));
  if (NATIVE) initNative();
  else if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
  applySite();
  renderSiteForm();
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
})();
