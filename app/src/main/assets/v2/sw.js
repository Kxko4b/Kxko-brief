/* Offline support. Network first: a fresh copy is used whenever the phone is online,
   and the cache is only the fallback. A missing file never stops the service worker from installing. */
const CACHE = 'kb5-v13';
const SHELL = ['./', './index.html', './styles.css', './app.js', './data/config.js', './data/content.js',
  './admin.js', './timetable.js', './today.js', './briefing.js', './ride-detail.js', './manifest.json', './icon.svg'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) =>
    Promise.allSettled(SHELL.map((url) => c.add(url).catch(() => null)))
  ).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET' || new URL(e.request.url).origin !== location.origin) return;
  e.respondWith(
    fetch(e.request).then((res) => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(e.request, copy));
      }
      return res;
    }).catch(() => caches.match(e.request).then((hit) => hit || caches.match('./index.html')))
  );
});
