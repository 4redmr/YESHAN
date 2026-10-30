// Bump VERSION whenever any cached file changes so installed copies pick up the update.
const VERSION = 'v23';
const FILES = ['./', './index.html', './manifest.webmanifest',
  './icons/icon-192.png', './icons/icon-512.png', './icons/apple-touch-icon.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== VERSION).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Network first so updates show up when online; fall back to the cache offline.
self.addEventListener('fetch', e => {
  if (e.request.method !== 'GET') return;
  e.respondWith(fetch(e.request)
    .then(res => {
      const copy = res.clone();
      caches.open(VERSION).then(c => c.put(e.request, copy));
      return res;
    })
    .catch(() => caches.match(e.request, { ignoreSearch: true })));
});

// The timer's status notification: "멈추기" tells the open app to stop (or opens it with
// ?stop=1); tapping the notification itself just brings the app forward and keeps it shown.
self.addEventListener('notificationclick', e => {
  const timerNote = e.notification.tag === 'ft-timer';
  if (!timerNote || e.action === 'stop') e.notification.close();
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
    const open = list.find(c => 'focus' in c);
    if (timerNote && e.action === 'stop') {
      if (open) { open.postMessage({ type: 'stop-timer' }); return open.focus(); }
      return self.clients.openWindow('./?stop=1');
    }
    return open ? open.focus() : self.clients.openWindow('./');
  }));
});
