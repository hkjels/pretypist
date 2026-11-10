// Generated with a content fingerprint after the production bundle is ready.
const { version, assets } = __PRETYPIST_OFFLINE_BUILD__;
const scope = new URL(self.registration.scope);
const cachePrefix = `pretypist-offline:${encodeURIComponent(scope.pathname)}:`;
const cacheName = `${cachePrefix}${version}`;
const assetURLs = new Set(assets.map(path => new URL(path, scope).href));
const indexPath = new URL("index.html", scope).pathname;

self.addEventListener("install", event => {
  // Installation is atomic: a failed download leaves the previous app usable.
  event.waitUntil((async () => {
    try {
      const cache = await caches.open(cacheName);
      await cache.addAll(Array.from(assetURLs, url => new Request(url, { cache: "reload" })));
    } catch (error) {
      await caches.delete(cacheName);
      throw error;
    }
  })());
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    for (const name of await caches.keys()) {
      const oldRelease = name.startsWith(cachePrefix) && name !== cacheName;
      // The first releases used an unscoped cache name and ran at the origin root.
      const legacyRootRelease = scope.pathname === "/" && /^pretypist-offline-[0-9a-f]{20}$/.test(name);
      if (oldRelease || legacyRootRelease) await caches.delete(name);
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
  if (request.method !== "GET" || url.origin !== scope.origin) return;
  const assetURL = url.pathname === indexPath ? scope.href : url.origin + url.pathname;
  if (!assetURLs.has(assetURL)) return;
  // Keep the shell, corpus and layout on the same release, online or offline.
  event.respondWith((async () => {
    const cache = await caches.open(cacheName);
    return await cache.match(assetURL) || fetch(request);
  })());
});
