const CACHE = 'ronda-v7';
const FILES = ['./', 'index.html', 'style.css', 'app.js', 'manifest.json',
  'img/tag1.jpg', 'img/tag1-vicino.jpg', 'img/tag2.jpg', 'img/tag2-vicino.jpg', 'img/tag3.jpg', 'img/tag3-vicino.jpg', 'img/tag4.jpg', 'img/tag4-vicino.jpg', 'img/icon-192.png', 'img/icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
// Rete prima, cache come riserva (funziona anche senza campo nei capannoni)
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(fetch(e.request).then(r => {
    if (r.ok && new URL(e.request.url).origin === location.origin) {
      const copy = r.clone(); caches.open(CACHE).then(c => c.put(e.request, copy));
    }
    return r;
  }).catch(() => caches.match(e.request)));
});

// Azioni della notifica: "Inizia giro" / "Posticipa"
self.addEventListener('notificationclick', e => {
  const action = e.action || 'start';
  e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    if (cs.length) { cs[0].postMessage({ alarmAction: action }); return cs[0].focus(); }
    return self.clients.openWindow('./?alarm=' + encodeURIComponent(action));
  }));
});
