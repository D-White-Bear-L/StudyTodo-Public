/**
 * 把 apps/web 的静态资源复制到 apps/webview/dist，作为 Capacitor 的 webDir。
 *
 * 目录约定：
 *   apps/web/           网页版源码（唯一真源）
 *   apps/webview/dist/  构建产物（gitignore）
 */
const fs = require('fs');
const path = require('path');

const SRC = path.resolve(__dirname, '../../web');
const OUT = path.resolve(__dirname, '../dist');

const FILES = [
  'index.html',
  'styles.css',
  'app.js',
  'manifest.webmanifest',
  'sw.js',
  'icon-192.png',
  'icon-512.png',
  'apple-touch-icon.png'
];

if (fs.existsSync(OUT)) fs.rmSync(OUT, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

let n = 0;
const missing = [];
for (const f of FILES) {
  const from = path.join(SRC, f);
  if (!fs.existsSync(from)) { missing.push(f); continue; }
  fs.copyFileSync(from, path.join(OUT, f));
  n++;
}

console.log(`[copy-web] ${n} 个文件 -> apps/webview/dist`);

if (missing.length) {
  console.warn(`[copy-web] 缺失: ${missing.join(', ')}`);
  process.exitCode = 1;
}
