/**
 * 构建 Capacitor 套壳版 APK，并复制到 release/
 * 用法：node scripts/build-webview.js
 */
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const PROJ = path.join(ROOT, 'apps', 'webview');
const RELEASE = path.join(ROOT, 'release');

const ANDROID_HOME = process.env.ANDROID_HOME || 'C:\\Android\\Sdk';
const JAVA_HOME = process.env.JAVA_HOME || 'C:\\Program Files\\Java\\jdk-21';

const env = {
  ...process.env,
  ANDROID_HOME,
  ANDROID_SDK_ROOT: ANDROID_HOME,
  JAVA_HOME
};

console.log('[webview] 复制网页资源…');
execSync('node scripts/copy-web.js', { cwd: PROJ, stdio: 'inherit', env });

console.log('[webview] 同步 Capacitor…');
execSync('npx cap sync android', { cwd: PROJ, stdio: 'inherit', env });

console.log('[webview] 编译…');
const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';
execSync(`${gradlew} assembleDebug --no-daemon`, {
  cwd: path.join(PROJ, 'android'),
  stdio: 'inherit',
  env
});

const apk = path.join(PROJ, 'android', 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
if (!fs.existsSync(apk)) {
  console.error('[webview] 未找到产物:', apk);
  process.exit(1);
}

fs.mkdirSync(RELEASE, { recursive: true });

const gradle = fs.readFileSync(path.join(PROJ, 'android', 'app', 'build.gradle'), 'utf8');
const version = (gradle.match(/versionName\s+"([^"]+)"/) || [, '1.0'])[1];

const out = path.join(RELEASE, `每日任务清单-webview-v${version}.apk`);
fs.copyFileSync(apk, out);

const size = (fs.statSync(out).size / 1024 / 1024).toFixed(1);
console.log(`[webview] 完成 -> release/${path.basename(out)} (${size} MB)`);
