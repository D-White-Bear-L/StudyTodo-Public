/**
 * 构建原生版 APK（Kotlin + Compose），并复制到 release/
 * 用法：node scripts/build-native.js
 */
const { execSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const PROJ = path.join(ROOT, 'apps', 'native');
const RELEASE = path.join(ROOT, 'release');

const ANDROID_HOME = process.env.ANDROID_HOME || 'C:\\Android\\Sdk';
const JAVA_HOME = process.env.JAVA_HOME || 'C:\\Program Files\\Java\\jdk-21';

const env = {
  ...process.env,
  ANDROID_HOME,
  ANDROID_SDK_ROOT: ANDROID_HOME,
  JAVA_HOME
};

const gradlew = process.platform === 'win32' ? 'gradlew.bat' : './gradlew';

console.log('[native] 开始编译…');
execSync(`${gradlew} assembleDebug --no-daemon`, {
  cwd: PROJ,
  stdio: 'inherit',
  env
});

/* build.gradle 会把 buildDir 挪到 F:/gradle-builds/todo-native（C 盘常年没空间），
   所以产物不一定在 app/build 下，两个位置都找一遍。 */
const candidates = [
  path.join(PROJ, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'),
  'F:/gradle-builds/todo-native/app/outputs/apk/debug/app-debug.apk'
];
const apk = candidates.find(p => fs.existsSync(p));
if (!apk) {
  console.error('[native] 未找到产物，找过这些位置:\n  ' + candidates.join('\n  '));
  process.exit(1);
}

fs.mkdirSync(RELEASE, { recursive: true });

const gradle = fs.readFileSync(path.join(PROJ, 'app', 'build.gradle'), 'utf8');
const version = (gradle.match(/versionName\s+"([^"]+)"/) || [, '1.0'])[1];

const out = path.join(RELEASE, `每日任务清单-native-v${version}.apk`);
fs.copyFileSync(apk, out);

const size = (fs.statSync(out).size / 1024 / 1024).toFixed(1);
console.log(`[native] 完成 -> release/${path.basename(out)} (${size} MB)`);
