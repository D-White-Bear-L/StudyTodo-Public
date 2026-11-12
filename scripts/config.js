'use strict';
/**
 * 统一读取本机配置：.env.example 提供默认值，.env 覆盖（.env 不入库）。
 * 其它脚本只 require('./config')，不要再各自硬编码域名/路径。
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

function parse(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  fs.readFileSync(file, 'utf8')
    .split(/\r?\n/)
    .forEach((line) => {
      const s = line.trim();
      if (!s || s.startsWith('#')) return;
      const i = s.indexOf('=');
      if (i < 0) return;
      const key = s.slice(0, i).trim();
      let val = s.slice(i + 1).trim();
      if ((val.startsWith('"') && val.endsWith('"')) ||
          (val.startsWith("'") && val.endsWith("'"))) {
        val = val.slice(1, -1);
      }
      out[key] = val;
    });
  return out;
}

const example = parse(path.join(ROOT, '.env.example'));
const local = parse(path.join(ROOT, '.env'));
const cfg = Object.assign({}, example, local);

cfg.__root = ROOT;
cfg.__hasLocal = fs.existsSync(path.join(ROOT, '.env'));

module.exports = cfg;
