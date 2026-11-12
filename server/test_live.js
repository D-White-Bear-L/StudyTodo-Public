/*
 * 线上环境验证：模拟一台「全新设备」连真实服务器
 *
 *   SYNC_TOKEN=xxx node server/test_live.js
 *
 * 会从线上拉 index.html 和 app.js，跑真实的 bootstrap，
 * 再检查本地拿到的任务数是否和服务端一致。
 */

const { JSDOM } = require('jsdom');

const BASE = process.env.SYNC_BASE || 'http://127.0.0.1:18080';
const TOKEN = process.env.SYNC_TOKEN || '';

let passed = 0;
let failed = 0;

function ok(name, cond, detail) {
  if (cond) { passed++; console.log('  ok   ' + name); }
  else { failed++; console.log('  FAIL ' + name + '   ' + JSON.stringify(detail)); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  if (!TOKEN) {
    console.error('缺少 SYNC_TOKEN');
    process.exit(2);
  }

  console.log('[1] 拉取线上页面');
  const html = await (await fetch(BASE + '/')).text();
  const js = await (await fetch(BASE + '/app.js')).text();
  ok('首页可取', html.indexOf('<title>') !== -1, html.length);
  ok('app.js 可取', js.length > 10000, js.length);
  ok('页面无敏感词', !/kaoyan|考研|备考/.test(js), 'found');

  console.log('[2] 全新设备打开');
  const dom = new JSDOM(html.replace(/<script[^>]*><\/script>/g, ''), {
    url: BASE + '/',
    runScripts: 'outside-only',
    pretendToBeVisual: true
  });
  const w = dom.window;
  w.fetch = (...a) => globalThis.fetch(...a);
  w.eval(js);
  await sleep(300);

  ok('启动正常渲染', !!w.document.querySelector('.topbar-title'), null);

  console.log('[3] 把云端数据套到本地（只读，不往服务器写）');
  const res = await (await fetch(BASE + '/sync?since=0', {
    headers: { Authorization: 'Bearer ' + TOKEN }
  })).json();
  ok('服务端有数据', (res.changes || []).length > 0, (res.changes || []).length);

  const before = Object.keys(w.__TodoAPI.state().tasks).length;
  w.__TodoAPI.dropDemoSeed();
  w.__TodoAPI.applyRemote(res.changes, { replaceSubjects: true });
  await sleep(300);

  const st = w.__TodoAPI.state();
  let taskCount = 0;
  Object.keys(st.tasks).forEach((d) => { taskCount += st.tasks[d].length; });
  const dayCount = Object.keys(st.tasks).length;

  ok('拉到了任务', taskCount > 400, taskCount);
  ok('拉到了天数', dayCount > 60, dayCount);
  const wantSubj = res.changes.filter((c) => c.kind === 'subject' && !c.deleted).length;
  ok('拉到了标签', (w.__TodoAPI.subjects() || []).length === wantSubj,
    [(w.__TodoAPI.subjects() || []).length, wantSubj]);
  ok('拉到了打卡', Object.keys(st.checkins).length > 60, Object.keys(st.checkins).length);
  ok('本地确实新生成了数据', before !== dayCount, [before, dayCount]);

  console.log('    本地合计 ' + taskCount + ' 条任务 / ' + dayCount + ' 天');

  console.log('[4] 页面能正常渲染');
  w.__TodoAPI.renderList();
  await sleep(200);
  const rows = w.document.querySelectorAll('.task-row, [data-act="open"]');
  const hasEmpty = !!w.document.querySelector('.empty');
  /* 默认展示今天，云端数据截止到昨天，所以今天为空是正常的 */
  ok('今天要么有任务行、要么是空态', rows.length > 0 || hasEmpty, [rows.length, hasEmpty]);
  ok('顶栏在', !!w.document.querySelector('.topbar-title'), null);
  const lastDay = Object.keys(st.tasks).sort().pop();
  ok('最后一天有任务', (st.tasks[lastDay] || []).length > 0, [lastDay, (st.tasks[lastDay] || []).length]);
  ok('没有写入服务器（只读）', (await (await fetch(BASE + '/health')).json()).records === res.changes.length,
    res.changes.length);

  console.log('');
  console.log('通过 ' + passed + ' 项，失败 ' + failed + ' 项');
  dom.window.close();
  return failed === 0 ? 0 : 1;
}

main().then((c) => process.exit(c)).catch((e) => {
  console.error('验证失败:', e);
  process.exit(1);
});
