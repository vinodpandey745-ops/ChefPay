/**
 * Installable-shell-only service worker. This is deliberately NOT an offline-first data layer -
 * that's its own follow-on project (outbox pattern, idempotency, conflict UX - see the
 * competitor-analysis report's Phase 2 recommendation). All this does is let staff "install" the
 * PWA to a home screen/taskbar and re-open the last-loaded shell if the network blips while
 * already inside the app. It never touches /api/ or /ws - every data read/write always goes to
 * the network; a genuinely offline terminal is expected to show its normal loading/error states,
 * not silently serve stale data.
 */
// Bistrodesk Phase 12: bumped to v2 and SHELL_URL moved from '/app/' to '/' - the app now serves
// from the server root, so the old cache entry (keyed to a URL that no longer resolves to the
// shell) needs to be replaced, not reused; the version bump plus `activate`'s "delete every cache
// key that isn't CACHE_NAME" cleanup below makes that happen automatically on the next install.
const CACHE_NAME = 'bistrodesk-shell-v2'
const SHELL_URL = '/'

self.addEventListener('install', (event) => {
  self.skipWaiting()
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.add(SHELL_URL).catch(() => {})),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const { request } = event
  const url = new URL(request.url)

  // Never intercept API calls or the WebSocket handshake - those must always hit the network live.
  if (request.method !== 'GET' || url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) {
    return
  }

  // Only manage the app shell itself; let every other static asset (hashed JS/CSS bundles, fonts)
  // pass straight through to the network with the browser's normal HTTP cache.
  if (request.mode === 'navigate' || url.pathname === SHELL_URL) {
    event.respondWith(
      fetch(request)
        .then((response) => {
          const copy = response.clone()
          caches.open(CACHE_NAME).then((cache) => cache.put(SHELL_URL, copy))
          return response
        })
        .catch(() => caches.match(SHELL_URL)),
    )
  }
})
