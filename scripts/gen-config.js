'use strict';
/**
 * 从 .env 渲染部署用的配置文件到 build/（build/ 不入库）。
 * 用法：npm run config:gen
 */
const fs = require('fs');
const path = require('path');
const cfg = require('./config');

const ROOT = cfg.__root;

if (!cfg.__hasLocal) {
  console.warn('[config] 未找到 .env，当前使用 .env.example 的占位值。');
  console.warn('[config] 请先执行： cp .env.example .env  并填好自己的配置。');
}

function render(text) {
  return text.replace(/\{\{([A-Z0-9_]+)\}\}/g, (match, key) => {
    if (!Object.prototype.hasOwnProperty.call(cfg, key)) {
      throw new Error('配置缺少 ' + key + '，请检查 .env / .env.example');
    }
    return cfg[key];
  });
}

function generate(srcRel, outRel) {
  const src = path.join(ROOT, srcRel);
  const out = path.join(ROOT, outRel);
  if (!fs.existsSync(src)) throw new Error('模板不存在：' + srcRel);
  const text = render(fs.readFileSync(src, 'utf8'));
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, text, 'utf8');
  console.log('  ' + outRel);
}

function envSh() {
  const keys = Object.keys(cfg).filter((k) => !k.startsWith('__'));
  const lines = [
    '# 由 scripts/gen-config.js 生成，供部署脚本 source 使用（不入库）',
    '# shellcheck disable=SC1091',
    ...keys.map((k) => 'export ' + k + '=' + JSON.stringify(cfg[k])),
    ''
  ];
  const out = path.join(ROOT, 'build', 'deploy-env.sh');
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, lines.join('\n'), 'utf8');
  console.log('  build/deploy-env.sh');
}

console.log('[config] APP_BASE_URL = ' + cfg.APP_BASE_URL);
console.log('[config] 生成部署文件：');
generate('server/nginx-site.conf.example', 'build/nginx-site.conf');
generate('server/todo-sync.service', 'build/todo-sync.service');
envSh();
console.log('[config] 完成。产物在 build/，不会被提交。');
