const CACHE = 'todo-v2';
const ASSETS = [
  './', './index.html', './styles.css', './app.js',
  './manifest.webmanifest', './icon-192.png', './icon-512.png'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

/* 接口和安装包一律直连网络，不进缓存：
   接口被缓存会让同步一直读到旧数据，安装包体积大且每次覆盖，缓存也没意义 */
function bypass(url) {
  return url.pathname === '/sync'
    || url.pathname === '/health'
    || /\.apk$/i.test(url.pathname);
}

/* 页面、脚本、样式走「网络优先」：发版后马上生效，断网才回退缓存 */
function networkFirst(req) {
  return fetch(req)
    .then((res) => {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
      return res;
    })
    .catch(() => caches.match(req).then((hit) => hit || caches.match('./index.html')));
}

/* 图标一类走「缓存优先」，省流量 */
function cacheFirst(req) {
  return caches.match(req).then((hit) => hit || fetch(req).then((res) => {
    const copy = res.clone();
    caches.open(CACHE).then((c) => c.put(req, copy)).catch(() => {});
    return res;
  }));
}

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;

  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;
  if (bypass(url)) return;

  const netFirst = url.pathname === '/'
    || /\.(html|js|css|webmanifest)$/i.test(url.pathname);

  e.respondWith(netFirst ? networkFirst(e.request) : cacheFirst(e.request));
});
