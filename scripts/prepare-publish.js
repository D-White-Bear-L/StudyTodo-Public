/**
 * 组装发布目录 build/publish：
 *   1. 复制 apps/web 的静态资源
 *   2. 收集 release/ 下的 APK
 *   3. 用 scripts/download-page.html 模板生成 download.html
 *
 * 用法：node scripts/prepare-publish.js
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const WEB = path.join(ROOT, 'apps', 'web');
const OUT = path.join(ROOT, 'build', 'publish');
const RELEASE = path.join(ROOT, 'release');

const WEB_FILES = [
  'index.html', 'styles.css', 'app.js', 'manifest.webmanifest', 'sw.js',
  'icon-192.png', 'icon-512.png', 'apple-touch-icon.png'
];

function kb(bytes) {
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}

/* ---- 1. 清空并复制网页 ---- */
if (fs.existsSync(OUT)) fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

for (const f of WEB_FILES) {
  const src = path.join(WEB, f);
  if (fs.existsSync(src)) fs.copyFileSync(src, path.join(OUT, f));
}

/* ---- 2. 收集 APK ---- */
const apks = fs.existsSync(RELEASE)
  ? fs.readdirSync(RELEASE).filter(f => f.toLowerCase().endsWith('.apk'))
  : [];

const found = { native: null, webview: null };
for (const name of apks) {
  const full = path.join(RELEASE, name);
  const st = fs.statSync(full);
  const entry = { name, size: st.size, mtime: st.mtimeMs, sizeText: kb(st.size) };
  if (/native/i.test(name)) {
    /* 同一种包可能有多个历史版本，取最新生成的那个，避免发布旧包 */
    if (!found.native || entry.mtime > found.native.mtime) found.native = entry;
  } else if (/webview|pwa/i.test(name)) {
    if (!found.webview || entry.mtime > found.webview.mtime) found.webview = entry;
  } else if (!found.webview) {
    found.webview = entry;
  }
}

/* ---- 3. 复制 APK 到发布目录 ---- */
const cards = [];
if (found.native) {
  const target = 'app-native.apk';
  fs.copyFileSync(path.join(RELEASE, found.native.name), path.join(OUT, target));
  cards.push({ kind: 'native', file: target, size: found.native.sizeText, label: '原生版', desc: 'Kotlin + Compose 原生渲染，动画与手势为系统级' });
}
if (found.webview) {
  const target = 'app-webview.apk';
  fs.copyFileSync(path.join(RELEASE, found.webview.name), path.join(OUT, target));
  cards.push({ kind: 'webview', file: target, size: found.webview.sizeText, label: '兼容版', desc: '网页技术打包，功能一致，兼容性更广' });
}

/* ---- 4. 生成下载页 ---- */
const tplPath = path.join(__dirname, 'download-page.html');
if (fs.existsSync(tplPath) && cards.length) {
  let html = fs.readFileSync(tplPath, 'utf8');

  const cardHtml = cards.map(c => `
    <a class="dl dl-${c.kind}" href="${c.file}" download>
      <div class="dl-main">
        <div class="dl-title">${c.label}</div>
        <div class="dl-desc">${c.desc}</div>
      </div>
      <div class="dl-side">
        <span class="dl-size">${c.size}</span>
        <svg viewBox="0 0 24 24"><path d="M12 3v12m0 0l-4-4m4 4l4-4M5 21h14"/></svg>
      </div>
    </a>`).join('');

  html = html.replace('{{DOWNLOAD_CARDS}}', cardHtml);
  fs.writeFileSync(path.join(OUT, 'download.html'), html);
  console.log('[publish] download.html 已生成，包含', cards.length, '个版本');
}

console.log('[publish] 输出目录:', path.relative(ROOT, OUT));
console.log('[publish] 文件:', fs.readdirSync(OUT).join(', '));
