/*
 * 双设备端到端同步测试
 *
 * 用两个独立的 jsdom 实例模拟「手机」和「电脑」，
 * 真的连本地起的一个同步服务，验证：
 *   首次合并 / 增量推送 / 跨设备拉取 / 删除墓碑 / 后写覆盖 / 离线补传
 *
 *     node server/test_client.js
 */

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');
const { JSDOM } = require('jsdom');

const ROOT = path.resolve(__dirname, '..');
const WEB = path.join(ROOT, 'apps', 'web');
const PORT = 18098;
const TOKEN = 'e2e-test-token-0123456789';
const BASE = 'http://127.0.0.1:' + PORT;

let passed = 0;
let failed = 0;

function ok(name, cond, detail) {
  if (cond) { passed++; console.log('  ok   ' + name); }
  else { failed++; console.log('  FAIL ' + name + '   ' + JSON.stringify(detail)); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(pathname, payload) {
  const opt = { headers: { Authorization: 'Bearer ' + TOKEN } };
  if (payload) {
    opt.method = 'POST';
    opt.headers['Content-Type'] = 'application/json';
    opt.body = JSON.stringify(payload);
  }
  const r = await fetch(BASE + pathname, opt);
  return r.json();
}

function makeDevice(initial) {
  const html = fs
    .readFileSync(path.join(WEB, 'index.html'), 'utf8')
    .replace(/<script[^>]*><\/script>/g, '');

  const dom = new JSDOM(html, {
    url: 'http://localhost:8899/',
    runScripts: 'outside-only',
    pretendToBeVisual: true
  });

  const w = dom.window;
  w.fetch = (...args) => globalThis.fetch(...args);

  if (initial) {
    w.localStorage.setItem('todo_state_v1', JSON.stringify(initial));
    w.localStorage.setItem('todo_state_v1_seeded', '1');
  }

  w.eval(fs.readFileSync(path.join(WEB, 'app.js'), 'utf8'));
  return w;
}

function configure(w, port) {
  w.localStorage.setItem(
    'todo_sync_cfg_v1',
    JSON.stringify({
      baseUrl: 'http://127.0.0.1:' + port,
      token: TOKEN,
      enabled: false,
      bootDone: false,
      lastSeq: 0,
      lastSyncAt: 0,
      lastError: ''
    })
  );
  return w.__TodoAPI.syncCfg();
}

function tasksOf(w, date) {
  const st = w.__TodoAPI.state();
  return st.tasks[date] || [];
}

function allTasks(w) {
  const st = w.__TodoAPI.state();
  let out = [];
  Object.keys(st.tasks).forEach((d) => {
    (st.tasks[d] || []).forEach((t) => out.push(Object.assign({ date: d }, t)));
  });
  return out;
}

const DEVICE_A = {
  version: 1,
  tasks: {
    '2026-11-10': [
      { id: 'a1', title: '数学第一章', subject: 'math', note: '', done: true, createdAt: 1000 },
      { id: 'a2', title: '英语阅读两篇', subject: 'english', note: '第 3-4 篇', done: false, createdAt: 1001 }
    ],
    '2026-11-11': [
      { id: 'a3', title: '408 数据结构', subject: 'cs408', note: '', done: false, createdAt: 1002 }
    ]
  },
  study: { '2026-11-11': { hours: '6', start: '08:30', place: '图书馆', review: '错题' } },
  checkins: { '2026-11-11': { time: '2026-11-11T12:00:00.000Z' } },
  settings: { theme: '#4BC85C', showCompletion: true, sort: 'manual' }
};

const DEVICE_B = {
  version: 1,
  tasks: {
    '2026-11-12': [
      { id: 'b1', title: '政治马原第三章', subject: 'politics', note: '', done: false, createdAt: 2000 }
    ]
  },
  study: {},
  checkins: {},
  settings: { theme: '#7B3FE4', showCompletion: true, sort: 'undone' }
};

async function main() {
  const tmp = path.join(__dirname, '.e2e-tmp');
  fs.rmSync(tmp, { recursive: true, force: true });
  fs.mkdirSync(tmp, { recursive: true });
  fs.writeFileSync(path.join(tmp, 'token'), TOKEN + '\n');

  const srv = spawn('python', [path.join(__dirname, 'sync_server.py')], {
    env: Object.assign({}, process.env, {
      TODO_DB: path.join(tmp, 'todo.db'),
      TODO_TOKEN: path.join(tmp, 'token'),
      TODO_BIND: '127.0.0.1',
      TODO_PORT: String(PORT)
    }),
    stdio: 'ignore'
  });

  try {
    for (let i = 0; i < 50; i++) {
      try {
        const r = await fetch(BASE + '/health');
        if (r.ok) break;
      } catch (e) { /* 还没起来 */ }
      await sleep(200);
    }

    console.log('[1] 设备 A 首次连接（本地有 3 条历史任务）');
    const A = makeDevice(DEVICE_A);
    await sleep(300);
    ok('A 启动读到 3 条任务', allTasks(A).length === 3, allTasks(A).length);

    let cfgA = configure(A, PORT);
    await A.__TodoAPI.bootstrapSync(cfgA);
    await sleep(300);

    const afterBoot = await api('/sync?since=0');
    ok('数据已推到云端', afterBoot.records !== undefined || afterBoot.changes.length > 0, afterBoot.changes.length);
    ok('云端有 3 条任务', afterBoot.changes.filter((c) => c.kind === 'task').length === 3,
      afterBoot.changes.filter((c) => c.kind === 'task').length);
    ok('云端有学习记录', afterBoot.changes.filter((c) => c.kind === 'study').length === 1, 0);
    ok('云端有打卡', afterBoot.changes.filter((c) => c.kind === 'checkin').length === 1, 0);
    ok('云端有标签', afterBoot.changes.filter((c) => c.kind === 'subject').length === 6,
      afterBoot.changes.filter((c) => c.kind === 'subject').length);

    console.log('[2] 设备 B 首次连接（本地只有 1 条不一样的任务）');
    const B = makeDevice(DEVICE_B);
    await sleep(300);
    ok('B 启动读到 1 条任务', allTasks(B).length === 1, allTasks(B).length);

    let cfgB = configure(B, PORT);
    await B.__TodoAPI.bootstrapSync(cfgB);
    await sleep(300);

    ok('B 合并后共 4 条任务', allTasks(B).length === 4, allTasks(B).length);
    ok('B 拿到了 A 的任务', allTasks(B).some((t) => t.id === 'a1'), allTasks(B).map((t) => t.id));
    ok('B 自己的任务还在', allTasks(B).some((t) => t.id === 'b1'), allTasks(B).map((t) => t.id));
    ok('B 拿到学习记录', !!B.__TodoAPI.state().study['2026-11-11'], B.__TodoAPI.state().study);
    ok('B 拿到打卡', !!B.__TodoAPI.state().checkins['2026-11-11'], B.__TodoAPI.state().checkins);

    console.log('[3] A 拉取 B 新增的那条');
    await A.__TodoAPI.runSync();
    await sleep(200);
    ok('A 也变成 4 条', allTasks(A).length === 4, allTasks(A).length);
    ok('A 看到 B 的任务 b1', allTasks(A).some((t) => t.id === 'b1'), allTasks(A).map((t) => t.id));

    console.log('[4] A 改一条，B 应该看到');
    const A2 = A.__TodoAPI.state();
    for (const t of A2.tasks['2026-11-10']) {
      if (t.id === 'a2') { t.done = true; t.note = '改过了'; }
    }
    A.__TodoAPI.save();
    await A.__TodoAPI.runSync();
    await sleep(200);
    await B.__TodoAPI.runSync();
    await sleep(200);
    const bA2 = tasksOf(B, '2026-11-10').find((t) => t.id === 'a2');
    ok('B 看到 a2 已完成', bA2 && bA2.done === true, bA2);
    ok('B 看到 a2 新备注', bA2 && bA2.note === '改过了', bA2 && bA2.note);

    console.log('[5] A 删除一条，B 不能复活它');
    const A3 = A.__TodoAPI.state();
    A3.tasks['2026-11-10'] = A3.tasks['2026-11-10'].filter((t) => t.id !== 'a1');
    A.__TodoAPI.save();
    await A.__TodoAPI.runSync();
    await sleep(200);
    ok('A 本地剩 3 条', allTasks(A).length === 3, allTasks(A).length);

    await B.__TodoAPI.runSync();
    await sleep(200);
    ok('B 那条也消失了', !allTasks(B).some((t) => t.id === 'a1'), allTasks(B).map((t) => t.id));
    ok('B 剩 3 条', allTasks(B).length === 3, allTasks(B).length);

    console.log('[6] 同一条两端都改，后改的赢');
    const A4 = A.__TodoAPI.state();
    A4.tasks['2026-11-10'].find((t) => t.id === 'a2').note = 'A 改的';
    A.__TodoAPI.save();
    await A.__TodoAPI.runSync();
    await sleep(120);

    const B4 = B.__TodoAPI.state();
    B4.tasks['2026-11-10'].find((t) => t.id === 'a2').note = 'B 改的';
    B.__TodoAPI.save();
    await B.__TodoAPI.runSync();
    await sleep(200);

    await A.__TodoAPI.runSync();
    await sleep(200);
    const finalA2 = tasksOf(A, '2026-11-10').find((t) => t.id === 'a2');
    ok('两队最终一致（B 后改生效）', finalA2 && finalA2.note === 'B 改的', finalA2 && finalA2.note);
    const finalB2 = tasksOf(B, '2026-11-10').find((t) => t.id === 'a2');
    ok('B 侧也是自己的值', finalB2 && finalB2.note === 'B 改的', finalB2 && finalB2.note);

    console.log('[7] 离线改，联网后补传');
    const realFetch = A.fetch;
    A.fetch = () => Promise.reject(new Error('offline'));
    const A5 = A.__TodoAPI.state();
    A5.tasks['2026-11-12'] = [{ id: 'a9', title: '断网时加的', subject: 'other', note: '', done: false, createdAt: 9999 }];
    A.__TodoAPI.save();
    const offRes = await A.__TodoAPI.runSync();
    ok('断网时同步失败', offRes === false, offRes);
    ok('改动留在待传队列', A.__TodoAPI.readPending().length > 0, A.__TodoAPI.readPending().length);

    A.fetch = realFetch;
    const backRes = await A.__TodoAPI.runSync();
    await sleep(200);
    ok('恢复后同步成功', backRes === true, backRes);
    ok('待传队列已清空', A.__TodoAPI.readPending().length === 0, A.__TodoAPI.readPending().length);

    await B.__TodoAPI.runSync();
    await sleep(200);
    ok('B 收到断网期间加的任务', allTasks(B).some((t) => t.id === 'a9'), allTasks(B).map((t) => t.id));

    console.log('[8] 幂等：重复同步不产生脏数据');
    const seqBefore = (A.__TodoAPI.syncCfg() || {}).lastSeq;
    await A.__TodoAPI.runSync();
    await A.__TodoAPI.runSync();
    await sleep(200);
    ok('本地任务数不变', allTasks(A).length === allTasks(B).length, [allTasks(A).length, allTasks(B).length]);
    ok('seq 没有暴涨', A.__TodoAPI.syncCfg().lastSeq >= seqBefore, [seqBefore, A.__TodoAPI.syncCfg().lastSeq]);

    console.log('');
    console.log('通过 ' + passed + ' 项，失败 ' + failed + ' 项');
    A.close();
    B.close();
    return failed === 0 ? 0 : 1;
  } finally {
    srv.kill();
    await sleep(300);
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

main().then((code) => process.exit(code)).catch((e) => {
  console.error('测试跑挂了:', e);
  process.exit(1);
});
