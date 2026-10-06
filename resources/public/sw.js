// Generated with a content fingerprint after the production bundle is ready.
const { version, assets } = {"version":"f9e432abde90e8eced28","assets":["/","/css/app.css","/js/app.js","/manifest.webmanifest","/corpora/en.edn","/corpora/nb.edn","/corpora/metadata.edn","/layouts/modified-engram.pretypist","/icons/mark.svg","/icons/app.svg","/icons/favicon-32.png","/icons/apple-touch-icon.png","/icons/icon-192.png","/icons/icon-512.png","/icons/icon-maskable-512.png"]};
const cacheName = `pretypist-offline-${version}`;
const assetPaths = new Set(assets);

self.addEventListener("install", event => {
  // Installation is atomic: a failed download leaves the previous app usable.
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(cacheName);
      await cache.addAll(assets.map(path => new Request(path, { cache: "reload" })));
    } catch (error) {
      await caches.delete(cacheName);
      throw error;
    }
  })());
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      if (name.startsWith("pretypist-offline-") && name !== cacheName) await caches.delete(name);
    }
    await self.clients.claim();
  })());
});

self.addEventListener("message", event => {
  if (event.data?.type === "PRETYPIST_APPLY_UPDATE") self.skipWaiting();
});

self.addEventListener("fetch", event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== "GET" || url.origin !== self.location.origin) return;
  const path = url.pathname === "/index.html" ? "/" : url.pathname;
  if (!assetPaths.has(path)) return;
  // Keep the shell, corpus and layout on the same release, online or offline.
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    return await cache.match(path) || fetch(request);
  })());
});
