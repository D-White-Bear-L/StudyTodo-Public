/* ===========================================================
   每日任务清单 · 应用逻辑
   数据全部保存在本机 localStorage，无需登录、不联网
   =========================================================== */
(function () {
  'use strict';

  /* ---------------- 常量 ---------------- */
  var LS_KEY = 'todo_state_v1';

  var SUBJECTS = [
    { key: 'work',   name: '工作', color: '#4C8DFF' },
    { key: 'study',  name: '学习', color: '#1677FF' },
    { key: 'life',   name: '生活', color: '#FF9F2E' },
    { key: 'health', name: '健康', color: '#FF5B6E' },
    { key: 'other',  name: '其他', color: '#8A8F99' },
    /* 早期版本留下的「未分类」，保留成独立标签，不要粗暴并进「其他」，
       否则原来的分组就看不出来了 */
    { key: 'uncat',  name: '未分类', color: '#B0B6C0' }
  ];

  var THEMES = ['#4BC85C', '#7B3FE4', '#1677FF', '#F2453D', '#FF9F2E', '#52C41A', '#A855F7', '#FF5B6E', '#7EC8F5'];

  var WEEK = ['日', '一', '二', '三', '四', '五', '六'];
  var WEEK_FULL = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  /* ---------------- 工具 ---------------- */
  function $(sel, root) { return (root || document).querySelector(sel); }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function fmtDate(d) { return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()); }
  function parseDate(s) { var p = s.split('-'); return new Date(+p[0], +p[1] - 1, +p[2]); }
  function todayStr() { return fmtDate(new Date()); }
  function addDays(s, n) { var d = parseDate(s); d.setDate(d.getDate() + n); return fmtDate(d); }
  function uid() { return Date.now().toString(36) + Math.random().toString(36).slice(2, 7); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function subjectOf(key) {
    for (var i = 0; i < SUBJECTS.length; i++) if (SUBJECTS[i].key === key) return SUBJECTS[i];
    return SUBJECTS[SUBJECTS.length - 1];
  }
  function hexA(hex, a) {
    var h = hex.replace('#', '');
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h, 16);
    return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')';
  }
  function toast(msg) {
    var el = $('#toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.remove('show'); }, 1600);
  }

  /* 备份文件里的颜色可能是 '#4C8DFF'、'0xFF4C8DFF'，也可能是原生版写出的十进制数字。
     统一转成 CSS 能用的 #RRGGBB，省得导入后标签全变灰。 */
  function toHexColor(v, fallback) {
    try {
      if (typeof v === 'number') {
        var n = v < 0 ? v + 4294967296 : v;
        return '#' + ('000000' + (n & 0xFFFFFF).toString(16)).slice(-6);
      }
      if (typeof v === 'string' && v) {
        if (v.charAt(0) === '#') return v;
        if (v.slice(0, 2).toLowerCase() === '0x') {
          return '#' + ('000000' + (parseInt(v, 16) & 0xFFFFFF).toString(16)).slice(-6);
        }
      }
    } catch (e) { /* 落到下面的兜底色 */ }
    return fallback || '#8A8F99';
  }

  function normSubject(o) {
    if (!o || typeof o !== 'object') return null;
    var key = String(o.key || '').trim();
    var name = String(o.name || '').trim();
    if (!key || !name) return null;
    return { key: key, name: name, color: toHexColor(o.color, '#8A8F99') };
  }

  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () {
        toast('已复制到剪贴板');
      }, function () { fallbackCopy(text); });
    } else {
      fallbackCopy(text);
    }
  }
  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;opacity:0;left:0;top:0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); toast('已复制到剪贴板'); }
    catch (e) { toast('复制失败，请手动选择文本复制'); }
    ta.remove();
  }
  function shareText(text) {
    if (navigator.share) {
      navigator.share({ title: '每日任务清单', text: text }).catch(function () {});
    } else {
      copyText(text);
    }
  }

  /* ---------------- 触觉反馈 ---------------- */
  var HAPTIC_MAP = {
    toggle: 'light', tab: 'light', pickDay: 'light', pickCalDay: 'light',
    closeModal: 'light', back: 'light', exitSelect: 'light', sort: 'light',
    study: 'light', calendar: 'light', subject: 'light', status: 'light',
    theme: 'light', themeCustom: 'light', multi: 'light', calPrev: 'light',
    calNext: 'light', prevDay: 'light', nextDay: 'light', today: 'light',
    toggleCompletion: 'light', copyText: 'light', shareText: 'light',
    add: 'medium', save: 'medium', saveStudy: 'medium', checkin: 'medium',
    migrate: 'medium', delete: 'medium', selDelete: 'medium', selDone: 'medium',
    exportMenu: 'medium', carryOver: 'medium', selAll: 'light',
    exportSummary: 'medium', exportPlan: 'medium', exportCalImg: 'medium',
    export: 'medium'
  };

  function haptic(act) {
    var H = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Haptics;
    if (!H) return;
    var style = HAPTIC_MAP[act];
    if (!style) return;
    try {
      if (style === 'light') H.impact({ style: 'LIGHT' }).catch(function () {});
      else H.impact({ style: 'MEDIUM' }).catch(function () {});
    } catch (e) { /* 不支持则忽略 */ }
  }

  /* ---------------- 数据层 ---------------- */
  var DEFAULT_SETTINGS = { theme: '#4BC85C', showCompletion: true, sort: 'default' };

  function loadState() {
    var st = null;
    try {
      var raw = localStorage.getItem(LS_KEY);
      if (raw) st = JSON.parse(raw);
    } catch (e) { st = null; }
    if (!st || typeof st !== 'object') st = {};
    st.version = 1;
    st.tasks = st.tasks || {};
    st.study = st.study || {};
    st.checkins = st.checkins || {};
    st.settings = Object.assign({}, DEFAULT_SETTINGS, st.settings || {});
    /* 学科标签跟原生版一样存在本地：导入备份或以后加自定义标签时都能落盘 */
    if (Object.prototype.toString.call(st.subjects) === '[object Array]' && st.subjects.length) {
      var list = [];
      st.subjects.forEach(function (o) { var s = normSubject(o); if (s) list.push(s); });
      if (list.length) SUBJECTS = list;
    }
    return st;
  }

  var S = loadState();

  /* ===========================================================
     云端同步
     -----------------------------------------------------------
     本地 localStorage 永远是完整的一份，云端是权威副本。
     每次 save() 对比上一次的状态，算出「变了哪几条」进队列，联网时批量推上去。
     删除写墓碑（deleted=1），否则一端删掉的记录会被另一端的旧数据复活。
     冲突按 updatedAt 后写覆盖，不同记录之间完全互不影响。
     =========================================================== */

  var SYNC_KEY = 'todo_sync_cfg_v1';
  var PENDING_KEY = LS_KEY + '_pending';

  /* 页面本身就是这个服务托管的，所以默认「跟着你从哪个地址打开就走哪个地址」：
     用域名打开就同步到域名，代码里不写死任何主机。
     file:// 这种没法推断来源的情况，可由部署方注入 window.STUDYTODO_SYNC_FALLBACK，
     否则留空，用户在设置里手动填服务器地址。 */
  function defaultSyncUrl() {
    var o = String(location.origin || '');
    if (/^https?:\/\/[^/]+$/i.test(o)) return o;
    return String(window.STUDYTODO_SYNC_FALLBACK || '');
  }

  var DEFAULT_SYNC_URL = defaultSyncUrl();

  /* 安卓安装包的下载路径（和网页同一个服务托管） */
  var APK_PATH = '/todo.apk';
  var APK_VER = '1.43';   /* 只用来拼 ?v= 破缓存，发新包时同步改这里 */

  /* 全新设备启动时用的是这套默认标签。首次连云端时如果云端已有自己的一套，
     就直接用云端的替换掉，否则「工作/学习」和「数学/英语」会叠成十几个标签。 */
  var DEFAULT_SUBJECT_KEYS = ['work', 'study', 'life', 'health', 'other', 'uncat'];

  var syncBusy = false;
  var syncTimer = 0;
  var syncErrNotified = false;
  var baseFlat = null;

  function syncCfg() {
    var c = null;
    try { c = JSON.parse(localStorage.getItem(SYNC_KEY) || 'null'); } catch (e) { c = null; }
    if (!c || typeof c !== 'object') c = {};
    if (!c.baseUrl) c.baseUrl = DEFAULT_SYNC_URL;
    if (typeof c.enabled !== 'boolean') c.enabled = false;
    if (!c.token) c.token = '';
    if (!c.lastSeq) c.lastSeq = 0;
    if (!c.lastSyncAt) c.lastSyncAt = 0;
    if (c.bootDone !== true) c.bootDone = false;
    if (typeof c.lastError !== 'string') c.lastError = '';
    return c;
  }

  function saveSyncCfg(c) {
    try { localStorage.setItem(SYNC_KEY, JSON.stringify(c)); } catch (e) { /* 隐私模式下忽略 */ }
  }

  /* 老设备存的是「IP + 端口」直连地址。现在页面能通过域名打开了，就把地址跟着换过去；
     只有还停在IP直连的才动，用户自己填过的其它地址一律不碰。 */
  function migrateSyncUrl() {
    var raw = null;
    try { raw = JSON.parse(localStorage.getItem(SYNC_KEY) || 'null'); } catch (e) { return; }
    if (!raw || typeof raw !== 'object' || !raw.baseUrl) return;
    var host = '';
    try { host = new URL(raw.baseUrl).hostname; } catch (e) { return; }
    if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) return;      /* 不是 IP 就不用管 */
    if (!DEFAULT_SYNC_URL || DEFAULT_SYNC_URL === raw.baseUrl) return;
    raw.baseUrl = DEFAULT_SYNC_URL;
    saveSyncCfg(raw);
  }

  function syncUrl(cfg) {
    var u = String(cfg.baseUrl || '').trim().replace(/\/+$/, '');
    if (u && !/^https?:\/\//i.test(u)) u = 'http://' + u;
    return u;
  }

  /* 把整份状态摊平成「一条记录一项」，key 用于比对差异 */
  function flatState(st) {
    var m = {};

    var tasks = st.tasks || {};
    Object.keys(tasks).forEach(function (d) {
      (tasks[d] || []).forEach(function (t, ti) {
        if (!t || !t.id) return;
        m['task|' + d + '|' + t.id] = {
          kind: 'task', id: d + '|' + t.id,
          body: {
            date: d,
            order: ti,   /* 手动拖拽的顺序也要同步，否则另一端看到的是旧排列 */
            task: {
              id: t.id, title: t.title || '', subject: t.subject || 'other',
              note: t.note || '', done: !!t.done, createdAt: t.createdAt || 0
            }
          }
        };
      });
    });

    var study = st.study || {};
    Object.keys(study).forEach(function (d) {
      var s = study[d] || {};
      if (!s.hours && !s.start && !s.place && !s.review) return;
      m['study|' + d] = {
        kind: 'study', id: d,
        body: { date: d, study: { hours: s.hours || '', start: s.start || '', place: s.place || '', review: s.review || '' } }
      };
    });

    var cks = st.checkins || {};
    Object.keys(cks).forEach(function (d) {
      m['checkin|' + d] = { kind: 'checkin', id: d, body: { date: d, time: (cks[d] && cks[d].time) || '' } };
    });

    SUBJECTS.forEach(function (s, i) {
      m['subject|' + s.key] = {
        kind: 'subject', id: s.key,
        body: { key: s.key, name: s.name, color: s.color, order: i }
      };
    });

    m['setting|main'] = { kind: 'setting', id: 'main', body: st.settings || {} };
    return m;
  }

  function diffChanges(prev, cur, now) {
    var out = [];
    Object.keys(cur).forEach(function (k) {
      var a = prev[k], b = cur[k];
      if (!a || JSON.stringify(a.body) !== JSON.stringify(b.body)) {
        out.push({ kind: b.kind, id: b.id, body: b.body, updatedAt: now, deleted: 0 });
      }
    });
    Object.keys(prev).forEach(function (k) {
      if (!cur[k]) {
        var a = prev[k];
        out.push({ kind: a.kind, id: a.id, body: a.body, updatedAt: now, deleted: 1 });
      }
    });
    return out;
  }

  /* ---- 待推送队列（落盘，刷新不丢）---- */

  function readPending() {
    try {
      var v = JSON.parse(localStorage.getItem(PENDING_KEY) || '[]');
      return Object.prototype.toString.call(v) === '[object Array]' ? v : [];
    } catch (e) { return []; }
  }

  function writePending(list) {
    try { localStorage.setItem(PENDING_KEY, JSON.stringify(list)); } catch (e) { /* 存不下就只留在内存这一轮 */ }
  }

  function queuePending(list) {
    if (!list || !list.length) return;
    var map = {};
    readPending().forEach(function (c) { map[c.kind + '|' + c.id] = c; });
    list.forEach(function (c) { map[c.kind + '|' + c.id] = c; });   /* 同一条以最新一次为准 */
    var out = [];
    Object.keys(map).forEach(function (k) { out.push(map[k]); });
    writePending(out);
  }

  function takePending() {
    var l = readPending();
    writePending([]);
    return l;
  }

  function restorePending(list) {
    if (!list || !list.length) return;
    var map = {};
    readPending().forEach(function (c) { map[c.kind + '|' + c.id] = c; });   /* 期间新产生的优先 */
    list.forEach(function (c) {
      var k = c.kind + '|' + c.id;
      if (!map[k]) map[k] = c;
    });
    var out = [];
    Object.keys(map).forEach(function (k) { out.push(map[k]); });
    writePending(out);
  }

  /* ---- 写盘与重绘 ---- */

  function persistOnly() {
    S.subjects = SUBJECTS.map(function (s) { return { key: s.key, name: s.name, color: s.color }; });
    try { localStorage.setItem(LS_KEY, JSON.stringify(S)); }
    catch (e) { toast('存储空间不足，保存失败'); }
  }

  function rerenderAfterSync() {
    applyTheme();
    if (ui.page === 'list') renderList();
    else if (ui.page === 'calendar') renderCalendar();
    else if (ui.page === 'study') renderStudy();
  }

  /* ---- 把服务端变更套用到本地 ---- */

  function subjectsAreDefault() {
    if (SUBJECTS.length !== DEFAULT_SUBJECT_KEYS.length) return false;
    var mine = SUBJECTS.map(function (s) { return s.key; }).sort().join(',');
    return mine === DEFAULT_SUBJECT_KEYS.slice().sort().join(',');
  }

  function applyRemote(changes, opts) {
    if (!changes || !changes.length) return false;
    var touched = false, subjTouched = false;
    var orderMap = {};

    /* 首次连云端且本地还是默认标签：整套换成云端的 */
    if (opts && opts.replaceSubjects) {
      var hasSubj = false;
      for (var q = 0; q < changes.length; q++) {
        if (changes[q] && changes[q].kind === 'subject' && !changes[q].deleted) { hasSubj = true; break; }
      }
      if (hasSubj) SUBJECTS = [];
    }

    changes.forEach(function (c) {
      if (!c || !c.kind || !c.id) return;

      if (c.kind === 'task') {
        var parts = String(c.id).split('|');
        var date = parts[0], tid = parts.slice(1).join('|');
        if (!date || !tid) return;

        if (!S.tasks[date]) S.tasks[date] = [];
        var arr = S.tasks[date], idx = -1;
        for (var i = 0; i < arr.length; i++) { if (arr[i] && arr[i].id === tid) { idx = i; break; } }

        if (c.deleted) {
          if (idx >= 0) arr.splice(idx, 1);
          if (!arr.length) delete S.tasks[date];
        } else {
          var t = (c.body && c.body.task) || {};
          var rec = {
            id: tid, title: t.title || '', subject: t.subject || 'other',
            note: t.note || '', done: !!t.done, createdAt: t.createdAt || 0
          };
          if (idx >= 0) arr[idx] = rec; else arr.push(rec);
          if (c.body && typeof c.body.order === 'number') {
            if (!orderMap[date]) orderMap[date] = {};
            orderMap[date][tid] = c.body.order;
          }
        }
        touched = true;

      } else if (c.kind === 'study') {
        if (c.deleted) delete S.study[c.id];
        else {
          var sd = (c.body && c.body.study) || {};
          S.study[c.id] = {
            hours: sd.hours || '', start: sd.start || '',
            place: sd.place || '', review: sd.review || ''
          };
        }
        touched = true;

      } else if (c.kind === 'checkin') {
        if (c.deleted) delete S.checkins[c.id];
        else S.checkins[c.id] = { time: (c.body && c.body.time) || new Date().toISOString() };
        touched = true;

      } else if (c.kind === 'subject') {
        var b = c.body || {}, key = String(c.id), at = -1;
        for (var j = 0; j < SUBJECTS.length; j++) { if (SUBJECTS[j].key === key) { at = j; break; } }
        if (c.deleted) {
          if (at >= 0) SUBJECTS.splice(at, 1);
        } else if (at >= 0) {
          SUBJECTS[at] = { key: key, name: b.name || key, color: toHexColor(b.color, '#8A8F99'), _o: b.order || 0 };
        } else {
          SUBJECTS.push({ key: key, name: b.name || key, color: toHexColor(b.color, '#8A8F99'), _o: b.order || 0 });
        }
        subjTouched = true;
        touched = true;

      } else if (c.kind === 'setting') {
        if (!c.deleted && c.body) S.settings = Object.assign({}, S.settings, c.body);
        touched = true;
      }
    });

    /* 任务顺序按服端给的 order 重排；没有 order 的留在末尾 */
    Object.keys(orderMap).forEach(function (d) {
      var arr = S.tasks[d], om = orderMap[d];
      if (!arr || !arr.length) return;
      arr.sort(function (a, b) {
        var ao = om[a.id], bo = om[b.id];
        if (ao == null && bo == null) return 0;
        if (ao == null) return 1;
        if (bo == null) return -1;
        return ao - bo;
      });
    });

    if (subjTouched) {
      SUBJECTS.sort(function (a, b) { return (a._o || 0) - (b._o || 0); });
      SUBJECTS.forEach(function (s) { delete s._o; });
      if (!SUBJECTS.length) SUBJECTS = [{ key: 'other', name: '其他', color: '#8A8F99' }];
    }

    if (touched) {
      persistOnly();          /* 直接落盘，不走 save()，避免把远端变更当成新的本地变更推回去 */
      baseFlat = flatState(S);
    }
    return touched;
  }

  /* ---- 网络 ---- */

  function syncFetch(cfg, path, payload) {
    var opt = { method: payload ? 'POST' : 'GET', headers: { 'Authorization': 'Bearer ' + cfg.token } };
    if (payload) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(payload);
    }
    return fetch(syncUrl(cfg) + path, opt).then(function (r) {
      if (r.status === 401) throw new Error('同步码不正确');
      if (!r.ok) throw new Error('服务器返回 ' + r.status);
      return r.json();
    });
  }

  function noteSyncError(msg) {
    var c = syncCfg();
    c.lastError = msg || '网络不通';
    saveSyncCfg(c);
    if (!syncErrNotified) {
      syncErrNotified = true;
      toast('云端同步失败：' + c.lastError);
    }
  }

  function runSync() {
    var cfg = syncCfg();
    if (!cfg.enabled || !cfg.token || !cfg.bootDone) return Promise.resolve(false);
    if (syncBusy) return Promise.resolve(false);
    syncBusy = true;

    var batch = takePending();
    return syncFetch(cfg, '/sync', { since: cfg.lastSeq || 0, changes: batch }).then(function (res) {
      var c2 = syncCfg();
      var changed = applyRemote(res.changes || []);
      c2.lastSeq = res.seq || c2.lastSeq;
      c2.lastSyncAt = res.now || Date.now();
      c2.lastError = '';
      saveSyncCfg(c2);
      syncBusy = false;
      syncErrNotified = false;
      if (changed) rerenderAfterSync();
      if (res.hasMore) setTimeout(runSync, 150);   /* 一次拉不完就接着拉 */
      return true;
    }, function (err) {
      restorePending(batch);
      syncBusy = false;
      noteSyncError(err && err.message);
      return false;
    });
  }

  function scheduleSync(delay) {
    var cfg = syncCfg();
    if (!cfg.enabled || !cfg.token || !cfg.bootDone) return;
    clearTimeout(syncTimer);
    syncTimer = setTimeout(function () { runSync(); }, delay || 900);
  }

  /* 首次连上：先把云端拉下来合并，再把本地独有的以「最旧」时间戳补上去。
     历史记录没有 updatedAt，用 0 表示「很久以前」——这样云端已有的版本不会被旧数据冲掉。 */
  function bootstrapSync(cfg) {
    if (syncBusy) return Promise.reject(new Error('正在同步，稍后再试'));
    syncBusy = true;
    dropDemoSeed();                 /* 示例任务不能跟着上云 */
    return syncFetch(cfg, '/sync?since=0').then(function (res) {
      var firstPull = res.changes || [];
      applyRemote(firstPull, { replaceSubjects: subjectsAreDefault() });

      var cur = flatState(S), push = [];
      Object.keys(cur).forEach(function (k) {
        push.push({ kind: cur[k].kind, id: cur[k].id, body: cur[k].body, updatedAt: 0, deleted: 0 });
      });

      return syncFetch(cfg, '/sync', { since: 0, changes: push }).then(function (r2) {
        applyRemote(r2.changes || []);
        var c2 = syncCfg();
        c2.lastSeq = r2.seq || 0;
        c2.lastSyncAt = r2.now || Date.now();
        c2.bootDone = true;
        c2.enabled = true;
        c2.lastError = '';
        saveSyncCfg(c2);
        baseFlat = flatState(S);
        writePending([]);
        syncBusy = false;
        syncErrNotified = false;
        rerenderAfterSync();
        return true;
      });
    }).then(null, function (err) {
      syncBusy = false;
      noteSyncError(err && err.message);
      throw err;
    });
  }

  function save() {
    persistOnly();
    if (!baseFlat) baseFlat = flatState(S);
    var cur = flatState(S);
    var changes = diffChanges(baseFlat, cur, Date.now());
    baseFlat = cur;
    if (!changes.length) return;
    queuePending(changes);
    scheduleSync(1200);
  }

  function tasksOf(date) { return (S.tasks[date] || []).slice(); }
  function rawTasks(date) { if (!S.tasks[date]) S.tasks[date] = []; return S.tasks[date]; }
  function studyOf(date) {
    var s = S.study[date] || {};
    return { hours: s.hours || '', start: s.start || '', place: s.place || '', review: s.review || '' };
  }
  function progressOf(date) {
    var list = S.tasks[date] || [];
    var done = 0;
    for (var i = 0; i < list.length; i++) if (list[i].done) done++;
    return { total: list.length, done: done, rate: list.length ? done / list.length : 0 };
  }

  /* 打卡 */
  function isCheckedIn(date) { return !!(S.checkins && S.checkins[date]); }
  function checkin(date) {
    if (!S.checkins) S.checkins = {};
    S.checkins[date] = { time: new Date().toISOString() };
    save();
  }
  function uncheckin(date) {
    if (S.checkins && S.checkins[date]) { delete S.checkins[date]; save(); }
  }
  function streakCount() {
    var count = 0, d = parseDate(todayStr());
    while (S.checkins && S.checkins[fmtDate(d)]) { count++; d.setDate(d.getDate() - 1); }
    return count;
  }
  function totalCheckins() {
    return S.checkins ? Object.keys(S.checkins).length : 0;
  }

  function sortTasks(list) {
    var mode = S.settings.sort || 'default';
    var arr = list.slice();
    var byCreate = function (a, b) { return a.createdAt - b.createdAt; };
    if (mode === 'undone') {
      arr.sort(function (a, b) { return (a.done ? 1 : 0) - (b.done ? 1 : 0) || byCreate(a, b); });
    } else if (mode === 'subject') {
      arr.sort(function (a, b) {
        return SUBJECTS.findIndex(function (s) { return s.key === a.subject; }) -
               SUBJECTS.findIndex(function (s) { return s.key === b.subject; }) || byCreate(a, b);
      });
    } else {
      arr.sort(byCreate);
    }
    return arr;
  }

  /* ---------------- 视图状态 ---------------- */
  var ui = {
    page: 'list',
    date: todayStr(),
    tab: 'all',
    selectMode: false,
    sel: {},
    cal: { y: 0, m: 0 },
    editId: null,
    draft: null
  };
  (function initCal() {
    var d = parseDate(ui.date);
    ui.cal.y = d.getFullYear();
    ui.cal.m = d.getMonth();
  })();

  function applyTheme() {
    var c = S.settings.theme || '#4BC85C';
    document.documentElement.style.setProperty('--theme', c);
  }

  function goto(page, dir) {
    ui.page = page;
    var pages = document.querySelectorAll('.page');
    for (var i = 0; i < pages.length; i++) {
      pages[i].classList.remove('active', 'slide-fwd', 'slide-back', 'slide-fade');
    }
    var el = $('#page-' + page);
    el.classList.add('active');
    if (dir === 'back') el.classList.add('slide-back');
    else if (dir === 'fwd') el.classList.add('slide-fwd');
    else if (dir === 'fade') el.classList.add('slide-fade');
    window.scrollTo(0, 0);
  }

  /* ---------------- 清单页 ---------------- */
  function renderList() {
    var date = ui.date;
    var all = sortTasks(tasksOf(date));
    var total = all.length;
    var done = 0;
    for (var i = 0; i < all.length; i++) if (all[i].done) done++;
    var rate = total ? Math.round((done / total) * 100) : 0;
    var isToday = date === todayStr();
    var d = parseDate(date);

    var html = '';

    /* hero */
    html += '<div class="card hero rise">' +
      '<div class="hero-left">' +
        '<div class="hero-title">' + (isToday ? '今日任务' : (d.getMonth() + 1) + '月' + d.getDate() + '日任务') + '</div>' +
        '<div class="hero-sub">清晰记录每一项任务，完成进度一目了然</div>' +
      '</div>' +
      '<div class="hero-num"><b>' + (total - done) + '</b><span>未完成</span></div>' +
    '</div>';

    /* 统计 */
    html += '<div class="card stats">' +
      '<div class="stat"><b>' + done + '</b><span>已完成</span></div>' +
      '<div class="divider"></div>' +
      '<div class="stat"><b>' + rate + '%</b><span>完成率</span></div>' +
    '</div>';

    /* tabs */
    var tabs = [['all', '全部'], ['undone', '未完成'], ['done', '已完成']];
    html += '<div class="tabs">';
    tabs.forEach(function (t) {
      html += '<button data-act="tab" data-tab="' + t[0] + '" class="' + (ui.tab === t[0] ? 'on' : '') + '">' + t[1] + '</button>';
    });
    html += '</div>';

    /* 日期 */
    html += '<div class="card datecard">' +
      '<button class="round" data-act="prevDay"><svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg></button>' +
      '<div class="datecenter" data-act="calendar"><div class="dlabel">选择日期</div>' +
      '<div class="dval"><svg viewBox="0 0 24 24" style="width:14px;height:14px;fill:none;stroke:currentColor;stroke-width:2.2;vertical-align:-2px;margin-right:4px"><rect x="3" y="5" width="18" height="16" rx="3"/><path d="M8 3v4M16 3v4M3 10h18"/></svg>' + date + '</div></div>' +
      '<button class="round" data-act="nextDay"><svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg></button>' +
      '<button class="today-pill" data-act="today">↺ 回到今天</button>' +
    '</div>';

    /* 最近7天 */
    html += '<div class="card weekcard">' +
      '<div class="weeklabel">最近 7 天</div><div class="weekstrip">';
    for (var k = 6; k >= 0; k--) {
      var ds = addDays(todayStr(), -k);
      var dd = parseDate(ds);
      var label = ds === todayStr() ? '今天' : (ds === addDays(todayStr(), -1) ? '昨天' : WEEK_FULL[dd.getDay()]);
      html += '<button class="daychip ' + (ds === date ? 'on' : '') + '" data-act="pickDay" data-date="' + ds + '">' +
        '<span class="wd">' + label + '</span>' +
        '<span class="dd">' + (dd.getMonth() + 1) + '/' + dd.getDate() + '</span>' +
      '</button>';
    }
    html += '</div></div>';

    /* 学习详情 */
    var st = studyOf(date);
    html += '<div class="card study-preview" data-act="study">' +
      '<div class="card-head"><h3>每日学习详情</h3>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg></div>' +
      '<div class="study-grid">' +
        fieldHtml('学习总时长', st.hours ? st.hours + '小时 (h)' : '未填写') +
        fieldHtml('开始学习时间', st.start || '未填写') +
        fieldHtml('学习地点', st.place || '未填写') +
        fieldHtml('复盘时间', st.review || '未填写') +
      '</div>' +
    '</div>';

    /* 打卡 */
    var ck = isCheckedIn(date);
    var streak = streakCount();
    var total = totalCheckins();
    html += '<div class="card checkin-card ' + (ck ? 'on' : '') + '">' +
      '<div class="checkin-left">' +
        '<div class="checkin-icon ' + (ck ? 'on' : '') + '"></div>' +
        '<div><div class="checkin-title">' + (ck ? '今日已打卡' : '今日未打卡') + '</div>' +
        '<div class="checkin-sub">连续 ' + streak + ' 天 · 累计 ' + total + ' 次</div></div>' +
      '</div>' +
      '<button class="btn ' + (ck ? 'btn-ghost' : 'btn-primary') + ' checkin-btn" data-act="checkin">' +
        (ck ? '取消打卡' : '打卡') + '</button>' +
    '</div>';

    /* 任务列表 */
    var list = all.filter(function (t) {
      if (ui.tab === 'undone') return !t.done;
      if (ui.tab === 'done') return t.done;
      return true;
    });

    html += '<div class="tasklist">';
    if (!list.length) {
      html += '<div class="empty"><div class="emoji">🗒️</div><p>' +
        (total === 0 ? '这一天还没有任务<br>点下方「新增任务」开始记录' : '当前筛选下没有任务') +
        '</p></div>';
    } else {
      list.forEach(function (t) {
        var sub = subjectOf(t.subject);
        html += '<div class="task ' + (t.done ? 'done' : '') + '" data-act="open" data-id="' + t.id + '">' +
          '<button class="check ' + (t.done ? 'on' : '') + '" data-act="toggle" data-id="' + t.id + '"></button>' +
          '<div class="task-main">' +
            '<div class="task-title">' + esc(t.title) + '</div>' +
            (t.note ? '<div class="task-note">' + esc(t.note) + '</div>' : '') +
            '<span class="tag" style="background:' + hexA(sub.color, .12) + ';color:' + sub.color + '">' + sub.name + '</span>' +
          '</div>' +
          (ui.selectMode
            ? '<span class="selbox ' + (ui.sel[t.id] ? 'on' : '') + '"></span>'
            : '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>') +
        '</div>';
      });
    }
    html += '</div>';

    $('#listBody').innerHTML = html;
    var strip = $('#listBody .weekstrip');
    if (strip && strip.scrollWidth > strip.clientWidth) strip.scrollLeft = strip.scrollWidth;
    renderListBar();
  }

  function fieldHtml(label, value) {
    var empty = !value || value === '未填写';
    return '<div class="field"><span>' + label + '</span><b class="' + (empty ? 'empty' : '') + '">' + esc(value) + '</b></div>';
  }

  function renderListBar() {
    var bar = $('#listActionbar');
    if (ui.selectMode) {
      bar.innerHTML =
        '<button class="btn btn-ghost" data-act="selAll">全选</button>' +
        '<button class="btn btn-primary" data-act="selDone">标记完成</button>' +
        '<button class="btn btn-warning" data-act="selDelete">删除</button>' +
        '<button class="btn btn-icon" data-act="exitSelect"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>';
    } else {
      bar.innerHTML =
        '<button class="btn btn-primary" data-act="add">＋ 新增任务</button>' +
        '<button class="btn btn-warning" data-act="migrate">迁移未完成</button>' +
        '<button class="btn btn-green" data-act="exportMenu">导出</button>';
    }
  }

  /* ---------------- 编辑页 ---------------- */
  function openEdit(id) {
    var t = null;
    if (id) {
      var list = tasksOf(ui.date);
      for (var i = 0; i < list.length; i++) if (list[i].id === id) t = list[i];
    }
    ui.editId = id || null;
    ui.draft = t ? { title: t.title, subject: t.subject, note: t.note || '', done: !!t.done }
                 : { title: '', subject: 'english', note: '', done: false };
    renderEdit();
    goto('edit', 'fwd');
  }

  function syncDraft() {
    var ti = $('#fTitle'), ta = $('#fNote');
    if (ti) ui.draft.title = ti.value;
    if (ta) ui.draft.note = ta.value;
  }

  function renderEdit() {
    var d = ui.draft;
    $('#editTitle').textContent = ui.editId ? '编辑任务' : '新增任务';

    var html = '';
    html += '<div class="card form-card">';

    /* 任务内容 */
    html += '<div class="form-label">任务内容 <span class="req">*</span></div>' +
      '<input class="text-input" id="fTitle" maxlength="40" placeholder="例如：英语阅读 text2 复盘" value="' + esc(d.title) + '">' +
      '<div class="hint">简洁描述要完成的事项，便于快速勾选</div>';

    /* 学科标签 */
    html += '<div class="form-label" style="margin-top:18px">学科标签 <span class="opt">选填</span></div><div class="tagrow">';
    SUBJECTS.forEach(function (s) {
      var on = d.subject === s.key;
      html += '<button class="tagbtn" data-act="subject" data-key="' + s.key + '" style="' +
        (on ? 'background:' + s.color + ';color:#fff;box-shadow:0 4px 10px ' + hexA(s.color, .3)
            : 'background:' + hexA(s.color, .12) + ';color:' + s.color) + '">' + s.name + '</button>';
    });
    html += '</div>';

    /* 备注 */
    html += '<div class="form-label" style="margin-top:18px">备注 <span class="opt">选填</span></div>' +
      '<textarea class="textarea" id="fNote" maxlength="200" placeholder="添加备注说明...">' + esc(d.note) + '</textarea>' +
      '<div class="counter"><span id="fCount">' + d.note.length + '</span>/200 字</div>';

    /* 完成状态 */
    html += '<div class="form-label" style="margin-top:18px">完成状态</div>' +
      '<button class="status-row ' + (d.done ? 'on' : '') + '" data-act="status">' +
        '<span class="sc"></span><span>' + (d.done ? '已完成' : '未完成') + '</span>' +
      '</button>';

    html += '</div>';

    html += '<div class="infobox"><i class="dot">i</i><div><b>个人记录</b>' +
      '本应用仅用于个人任务与心得记录，所有数据只保存在你的设备本地，不会上传服务器。</div></div>';

    $('#editBody').innerHTML = html;

    var note = $('#fNote');
    note.addEventListener('input', function () { $('#fCount').textContent = note.value.length; });
    $('#fTitle').addEventListener('input', function () { ui.draft.title = this.value; });

    $('#editActionbar').innerHTML = (ui.editId
      ? '<button class="btn btn-danger-ghost" data-act="delete">删除</button>'
      : '<button class="btn btn-ghost" data-act="back" style="flex:1">取消</button>') +
      '<button class="btn btn-save-ghost" data-act="save">保存</button>';
  }

  function saveTask() {
    syncDraft();
    var title = (ui.draft.title || '').trim();
    if (!title) { toast('请填写任务内容'); $('#fTitle').focus(); return; }
    var arr = rawTasks(ui.date);
    if (ui.editId) {
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === ui.editId) {
          arr[i].title = title;
          arr[i].subject = ui.draft.subject;
          arr[i].note = ui.draft.note || '';
          arr[i].done = !!ui.draft.done;
          break;
        }
      }
    } else {
      arr.push({
        id: uid(), title: title, subject: ui.draft.subject,
        note: ui.draft.note || '', done: !!ui.draft.done, createdAt: Date.now()
      });
    }
    save();
    toast(ui.editId ? '已保存' : '已添加');
    goto('list', 'back');
    renderList();
  }

  function deleteTask() {
    if (!ui.editId) return;
    confirmSheet('删除任务', '删除后不可恢复，确定删除这条任务吗？', '删除', function () {
      var arr = rawTasks(ui.date);
      for (var i = 0; i < arr.length; i++) {
        if (arr[i].id === ui.editId) { arr.splice(i, 1); break; }
      }
      if (!arr.length) delete S.tasks[ui.date];
      save();
      toast('已删除');
      goto('list', 'back');
      renderList();
    });
  }

  /* ---------------- 日历页 ---------------- */
  function renderCalendar() {
    var y = ui.cal.y, m = ui.cal.m;
    var first = new Date(y, m, 1);
    var lead = first.getDay();
    var days = new Date(y, m + 1, 0).getDate();
    var today = todayStr();

    var html = '';
    html += '<div class="card rise">';
    html += '<div class="cal-head">' +
      '<button class="round" data-act="calPrev"><svg viewBox="0 0 24 24"><path d="M15 5l-7 7 7 7"/></svg></button>' +
      '<div class="cal-month">' + y + '年' + (m + 1) + '月</div>' +
      '<button class="round" data-act="calNext"><svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg></button>' +
    '</div>';

    html += '<div class="weekrow">';
    WEEK.forEach(function (w) { html += '<span>' + w + '</span>'; });
    html += '</div>';

    html += '<div class="togglerow"><label>完成度显示</label>' +
      '<button class="switch ' + (S.settings.showCompletion ? 'on' : '') + '" data-act="toggleCompletion"></button></div>';

    html += '<div class="calgrid">';
    for (var i = 0; i < lead; i++) html += '<div class="calday blank"></div>';
    for (var day = 1; day <= days; day++) {
      var ds = y + '-' + pad2(m + 1) + '-' + pad2(day);
      var p = progressOf(ds);
      var style = '';
      if (S.settings.showCompletion && p.total && ds !== ui.date) {
        var a = 0.14 + 0.76 * p.rate;
        style = 'background:' + hexA(S.settings.theme, a) + ';color:' + (p.rate >= 0.5 ? '#fff' : '#2C3036') + ';';
      }
      html += '<div class="calday ' + (ds === ui.date ? 'sel ' : '') + (ds === today ? 'today' : '') + '" data-act="pickCalDay" data-date="' + ds + '">' +
        '<span class="num" style="' + style + '">' + day + '</span></div>';
    }
    var tail = (7 - ((lead + days) % 7)) % 7;
    for (var j = 0; j < tail; j++) html += '<div class="calday blank"></div>';
    html += '</div></div>';

    /* 主题色 */
    html += '<div class="card"><div class="card-head"><h3>日历主题色</h3></div>' +
      '<div class="hint" style="margin:-6px 0 14px">选择颜色将应用于日历完成度渐变</div>' +
      '<div class="themerow">';
    THEMES.forEach(function (c) {
      var on = (S.settings.theme || '').toLowerCase() === c.toLowerCase();
      html += '<button class="swatch ' + (on ? 'on' : '') + '" data-act="theme" data-color="' + c + '" ' +
        'style="background:' + c + ';color:' + c + '"></button>';
    });
    html += '<button class="swatch-custom" data-act="themeCustom">' +
      '<svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg><span>自定义</span></button>';
    html += '</div></div>';

    $('#calBody').innerHTML = html;
    $('#calActionbar').innerHTML =
      '<button class="btn btn-green" data-act="export">' +
      '<svg viewBox="0 0 24 24" style="width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.9;stroke-linecap:round;stroke-linejoin:round">' +
      '<path d="M4 8h3l1.5-2h7L17 8h3v11H4z"/><circle cx="12" cy="13" r="3.4"/></svg>' +
      '导出日历图片</button>';
  }

  /* ---------------- 导出图片 ---------------- */
  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function buildCalendarCanvas() {
    var y = ui.cal.y, m = ui.cal.m;
    var theme = S.settings.theme || '#4BC85C';
    var FONT = '-apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif';

    var W = 780, padX = 46, headerH = 190, weekH = 58, cellH = 100, footerH = 186;
    var first = new Date(y, m, 1), lead = first.getDay();
    var days = new Date(y, m + 1, 0).getDate();
    var rows = Math.ceil((lead + days) / 7);
    var H = headerH + weekH + rows * cellH + footerH;
    var dpr = 2;

    var cv = document.createElement('canvas');
    cv.width = W * dpr; cv.height = H * dpr;
    var ctx = cv.getContext('2d');
    ctx.scale(dpr, dpr);

    /* 背景 */
    var bg = ctx.createLinearGradient(0, 0, 0, H);
    bg.addColorStop(0, '#FFFFFF');
    bg.addColorStop(1, '#F6F8FA');
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, W, H);

    /* 顶部色块 */
    var grad = ctx.createLinearGradient(padX, 0, W - padX, 0);
    grad.addColorStop(0, hexA(theme, .16));
    grad.addColorStop(1, hexA(theme, .04));
    ctx.fillStyle = grad;
    roundRect(ctx, padX, 44, W - padX * 2, 104, 26);
    ctx.fill();

    /* 标题 */
    ctx.fillStyle = '#17181A';
    ctx.font = '700 40px ' + FONT;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    ctx.fillText(y + ' 年 ' + (m + 1) + ' 月', padX + 30, 96);

    /* 月度统计 */
    var mTotal = 0, mDone = 0;
    for (var dd = 1; dd <= days; dd++) {
      var p = progressOf(y + '-' + pad2(m + 1) + '-' + pad2(dd));
      mTotal += p.total; mDone += p.done;
    }
    ctx.font = '600 20px ' + FONT;
    ctx.fillStyle = hexA(theme, .95);
    ctx.textAlign = 'right';
    ctx.fillText(mDone + ' / ' + mTotal + ' 项完成', W - padX - 30, 96);

    /* 星期 */
    ctx.textAlign = 'center';
    ctx.font = '600 20px ' + FONT;
    ctx.fillStyle = '#9AA0A8';
    var cellW = (W - padX * 2) / 7;
    for (var w = 0; w < 7; w++) {
      ctx.fillText(WEEK[w], padX + cellW * w + cellW / 2, headerH + 20);
    }

    /* 日期 */
    for (var i = 0; i < lead + days; i++) {
      var col = i % 7, row = Math.floor(i / 7);
      var cx = padX + col * cellW + cellW / 2;
      var cy = headerH + weekH + row * cellH + cellH / 2;
      var dayNum = i - lead + 1;
      var dateStr = y + '-' + pad2(m + 1) + '-' + pad2(dayNum);
      var pr = progressOf(dateStr);
      var has = pr.total > 0;

      ctx.beginPath();
      ctx.arc(cx, cy, 31, 0, Math.PI * 2);
      if (has) {
        ctx.fillStyle = hexA(theme, 0.14 + 0.76 * pr.rate);
      } else {
        ctx.fillStyle = '#F1F3F5';
      }
      ctx.fill();

      if (dateStr === ui.date) {
        ctx.strokeStyle = hexA(theme, .95);
        ctx.lineWidth = 3;
        ctx.beginPath();
        ctx.arc(cx, cy, 35, 0, Math.PI * 2);
        ctx.stroke();
      }

      ctx.fillStyle = has && pr.rate >= 0.5 ? '#FFFFFF' : '#2C3036';
      ctx.font = '700 26px ' + FONT;
      ctx.textBaseline = 'middle';
      ctx.fillText(String(dayNum), cx, cy + 1);
    }

    /* 页脚 */
    var gridEnd = headerH + weekH + rows * cellH;
    var fy = gridEnd + 96;

    ctx.strokeStyle = '#EDEFF2';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(padX, gridEnd + 34);
    ctx.lineTo(W - padX, gridEnd + 34);
    ctx.stroke();

    var barW = 190, barX = padX, barY = fy + 8, barH = 12;
    var lg = ctx.createLinearGradient(barX, 0, barX + barW, 0);
    lg.addColorStop(0, hexA(theme, .14));
    lg.addColorStop(1, hexA(theme, .95));
    ctx.fillStyle = lg;
    roundRect(ctx, barX, barY, barW, barH, 6);
    ctx.fill();

    ctx.fillStyle = '#8A8F99';
    ctx.font = '600 18px ' + FONT;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'alphabetic';
    ctx.fillText('完成度', barX, fy - 8);

    ctx.textAlign = 'right';
    ctx.fillStyle = '#B4B9C0';
    ctx.font = '500 17px ' + FONT;
    ctx.fillText('每日任务清单', W - padX, fy - 8);

    return cv;
  }

  function showExport() {
    var cv = buildCalendarCanvas();
    var url = cv.toDataURL('image/png');
    var name = '日历-' + ui.cal.y + '-' + pad2(ui.cal.m + 1) + '.png';

    var root = $('#modalRoot');
    root.innerHTML = '<div class="imgmodal">' +
      '<img src="' + url + '" alt="日历图片">' +
      '<div class="tip">长按图片可保存到相册<br>或点击下方按钮下载</div>' +
      '<div class="acts">' +
        '<a class="btn dl" href="' + url + '" download="' + name + '">下载图片</a>' +
        '<button class="btn close" data-act="closeModal">关闭</button>' +
      '</div></div>';
  }

  /* ---------------- 弹层 ---------------- */
  function closeSheet() {
    var root = $('#sheetRoot');
    var mask = $('.mask', root), sheet = $('.sheet', root);
    if (!sheet) return;
    if (mask) mask.classList.remove('show');
    sheet.classList.remove('show');
    setTimeout(function () { root.innerHTML = ''; }, 240);
  }

  function openSheet(title, sub, bodyHtml, actions) {
    var root = $('#sheetRoot');
    root.innerHTML = '<div class="mask"></div>' +
      '<div class="sheet"><div class="sheet-handle"></div>' +
      '<h3>' + esc(title) + '</h3>' +
      (sub ? '<div class="sheet-sub">' + esc(sub) + '</div>' : '') +
      '<div class="sheet-body">' + bodyHtml + '</div>' +
      '<div class="sheet-actions"></div></div>';

    var actWrap = $('.sheet-actions', root);
    /* 三个以上动作竖排，两个及以下保持并排 */
    if ((actions || []).length > 2) actWrap.classList.add('stack');
    (actions || []).forEach(function (a) {
      var b = document.createElement('button');
      b.className = 'btn ' + (a.cls || 'btn-cancel');
      b.textContent = a.label;
      b.addEventListener('click', function () { a.onClick && a.onClick(); });
      actWrap.appendChild(b);
    });

    $('.mask', root).addEventListener('click', closeSheet);
    requestAnimationFrame(function () {
      $('.mask', root).classList.add('show');
      $('.sheet', root).classList.add('show');
    });
  }

  function confirmSheet(title, sub, okLabel, onOk) {
    openSheet(title, sub, '', [
      { label: '取消', cls: 'btn-cancel', onClick: closeSheet },
      { label: okLabel, cls: 'btn-ok', onClick: function () { closeSheet(); onOk(); } }
    ]);
  }

  /* 排序 */
  function openSort() {
    var opts = [['default', '默认顺序'], ['undone', '未完成优先'], ['subject', '按学科分组']];
    var html = '<div class="optionlist">';
    opts.forEach(function (o) {
      html += '<button class="option ' + (S.settings.sort === o[0] ? 'on' : '') + '" data-sort="' + o[0] + '">' +
        '<span>' + o[1] + '</span><svg class="tick" viewBox="0 0 24 24"><path d="M5 13l4 4L19 7"/></svg></button>';
    });
    html += '</div>';
    openSheet('任务排序', '选择列表的排列方式', html, []);
    var root = $('#sheetRoot');
    root.querySelectorAll('[data-sort]').forEach(function (b) {
      b.addEventListener('click', function () {
        S.settings.sort = b.getAttribute('data-sort');
        save(); closeSheet(); renderList();
      });
    });
  }

  /* 学习详情 — 独立页面 */
  function renderStudy() {
    var st = studyOf(ui.date);
    $('#studyTitle') && ($('#studyTitle').textContent = ui.date);
    var html =
      '<div class="card glass form-card">' +
        studyRow('学习总时长', 'sHours', 'number', st.hours, '例如 10', {min:0,max:24,step:0.5}) +
        studyRow('开始学习时间', 'sStart', 'time', st.start, '', {}) +
        studyRow('学习地点', 'sPlace', 'text', st.place, '例如 图书馆', {maxlength:20}) +
        studyRow('复盘时间', 'sReview', 'time', st.review, '', {}) +
      '</div>';
    $('#studyBody').innerHTML = html;
    $('#studyActionbar').innerHTML =
      '<button class="btn btn-ghost" data-act="back" style="flex:1">取消</button>' +
      '<button class="btn btn-primary" data-act="saveStudy" style="flex:1.6">保存</button>';
  }

  function studyRow(label, id, type, value, placeholder, attrs) {
    var attrStr = '';
    Object.keys(attrs || {}).forEach(function (k) { attrStr += ' ' + k + '="' + attrs[k] + '"'; });
    return '<div class="study-row">' +
      '<div class="study-label">' + label + '<span class="opt">选填</span></div>' +
      '<input class="study-input" id="' + id + '" type="' + type + '" placeholder="' + esc(placeholder || '') + '" value="' + esc(value || '') + '"' + attrStr + '>' +
    '</div>';
  }

  function saveStudy() {
    var hours = $('#sHours').value.trim();
    S.study[ui.date] = {
      hours: hours,
      start: $('#sStart').value,
      place: $('#sPlace').value.trim(),
      review: $('#sReview').value
    };
    save();
    toast('已保存');
    goto('list', 'back');
    renderList();
  }

  /* 沿用上一日数据 */
  function carryOver() {
    var yesterday = addDays(ui.date, -1);
    var yStudy = studyOf(yesterday);
    var yTasks = tasksOf(yesterday).filter(function (t) { return !t.done; });

    var changed = false;
    if (yStudy && (yStudy.hours || yStudy.start || yStudy.place || yStudy.review)) {
      S.study[ui.date] = {
        hours: yStudy.hours,
        start: yStudy.start,
        place: yStudy.place,
        review: yStudy.review
      };
      changed = true;
    }
    if (yTasks.length) {
      var dest = rawTasks(ui.date);
      yTasks.forEach(function (t) {
        dest.push({
          id: uid(), title: t.title, subject: t.subject,
          note: t.note || '', done: false, createdAt: Date.now() + Math.random()
        });
      });
      changed = true;
    }
    if (!changed) { toast('昨天没有可沿用的数据'); return; }
    save();
    toast('已沿用昨日数据');
    renderStudy();
    renderList();
  }

  /* 迁移未完成 */
  function openMigrate() {
    var target = addDays(ui.date, 1);
    var html = '<div class="formrow"><div class="lab">把未完成的任务迁移到</div>' +
      '<div class="datepick">' +
        '<button data-target="' + addDays(ui.date, 1) + '" class="on">明天</button>' +
        '<button data-target="' + addDays(ui.date, 2) + '">后天</button>' +
        '<button data-target="' + addDays(ui.date, 7) + '">一周后</button>' +
      '</div>' +
      '<input class="native-date" id="migDate" type="date" value="' + target + '"></div>' +
      '<div class="hint">只会迁移「未完成」的任务，已完成的任务保留在原日期。</div>';

    openSheet('迁移未完成', ui.date + ' → 目标日期', html, [
      { label: '取消', cls: 'btn-cancel', onClick: closeSheet },
      {
        label: '确认迁移', cls: 'btn-ok', onClick: function () {
          var to = $('#migDate').value || target;
          if (to === ui.date) { toast('目标日期不能是当天'); return; }
          var src = rawTasks(ui.date);
          var moving = src.filter(function (t) { return !t.done; });
          if (!moving.length) { toast('没有未完成的任务'); closeSheet(); return; }
          var keep = src.filter(function (t) { return t.done; });
          S.tasks[ui.date] = keep;
          if (!keep.length) delete S.tasks[ui.date];
          var dest = rawTasks(to);
          moving.forEach(function (t) { t.createdAt = Date.now() + Math.random(); dest.push(t); });
          save(); closeSheet();
          toast('已迁移 ' + moving.length + ' 项到 ' + to);
          renderList();
        }
      }
    ]);

    var root = $('#sheetRoot');
    root.querySelectorAll('[data-target]').forEach(function (b) {
      b.addEventListener('click', function () {
        root.querySelectorAll('[data-target]').forEach(function (x) { x.classList.remove('on'); });
        b.classList.add('on');
        $('#migDate').value = b.getAttribute('data-target');
      });
    });
  }

  /* ---------------- 导出菜单 ---------------- */
  function openExportMenu() {
    var html = '<div class="export-menu">' +
      '<button class="export-item" data-act="exportSummary">' +
        '<div class="ei-icon" style="background:#EFFAF1;color:#2EA043">📋</div>' +
        '<div class="ei-text"><b>导出每日总结</b><span>按学科分组显示完成情况与完成率</span></div>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>' +
      '</button>' +
      '<button class="export-item" data-act="exportPlan">' +
        '<div class="ei-icon" style="background:#EAF2FF;color:#1677FF">📝</div>' +
        '<div class="ei-text"><b>导出每日计划</b><span>列出学习详情与各学科任务清单</span></div>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>' +
      '</button>' +
      '<button class="export-item" data-act="exportCalImg">' +
        '<div class="ei-icon" style="background:#FFF4E5;color:#FF9F2E">📅</div>' +
        '<div class="ei-text"><b>导出日历图片</b><span>月度完成度可视化图</span></div>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>' +
      '</button>' +
      '<button class="export-item" data-act="backupMenu">' +
        '<div class="ei-icon" style="background:#F2F0FF;color:#7B3FE4">💾</div>' +
        '<div class="ei-text"><b>数据备份</b><span>导出 / 导入全部记录，换手机不丢数据</span></div>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>' +
      '</button>' +
      '<button class="export-item" data-act="syncMenu">' +
        '<div class="ei-icon" style="background:#E6F4FF;color:#0B84D8">☁️</div>' +
        '<div class="ei-text"><b>云端同步</b><span>手机和电脑共用同一份数据</span></div>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>' +
      '</button>' +
      '<button class="export-item" data-act="downloadApk">' +
        '<div class="ei-icon" style="background:#E8F7EE;color:#12A05C">📱</div>' +
        '<div class="ei-text"><b>下载安卓版</b><span>装到手机上用，离线也不丢</span></div>' +
        '<svg class="chev" viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg>' +
      '</button>' +
      '<button class="sheet-link" data-act="showWelcome">回到首页</button>' +
    '</div>';
    openSheet('导出', ui.date, html, [
      { label: '关闭', cls: 'btn-cancel', onClick: closeSheet }
    ]);
  }

  /* 日期中文格式化 */
  function dateCN(s) {
    var d = parseDate(s);
    return (d.getMonth() + 1) + '月' + d.getDate() + '日';
  }

  /* 导出每日总结文本 */
  function buildSummaryText(date) {
    var tasks = sortTasks(tasksOf(date));
    var st = studyOf(date);
    var p = progressOf(date);
    var d = parseDate(date);

    var lines = [];
    lines.push('▤ 每日任务总结 - ' + date);
    lines.push('');
    lines.push('学习总时长：' + (st.hours ? st.hours + '小时' : '未填写'));
    lines.push('开始学习时间：' + (st.start || '未填写'));
    lines.push('学习地点：' + (st.place || '未填写'));
    lines.push('复盘时间：' + (st.review || '未填写'));
    lines.push('---------------');

    /* 按学科分组 */
    var groups = {};
    var order = [];
    SUBJECTS.forEach(function (s) { groups[s.key] = []; order.push(s.key); });
    tasks.forEach(function (t) {
      if (!groups[t.subject]) { groups[t.subject] = []; order.push(t.subject); }
      groups[t.subject].push(t);
    });

    order.forEach(function (key) {
      var list = groups[key];
      if (!list || !list.length) return;
      var sub = subjectOf(key);
      var done = 0;
      list.forEach(function (t) { if (t.done) done++; });
      lines.push('【' + sub.name + '】 ' + done + '/' + list.length);

      list.forEach(function (t) {
        lines.push((t.done ? '✅ ' : '❌ ') + t.title);
      });
      lines.push('───');
    });

    lines.push('━━ 总览 ━━');
    lines.push('完成率：' + Math.round(p.rate * 100) + '%（已完成 ' + p.done + ' 项 / 总 ' + p.total + ' 项）');

    return lines.join('\n');
  }

  /* 导出每日计划文本 */
  function buildPlanText(date) {
    var tasks = sortTasks(tasksOf(date));
    var st = studyOf(date);
    var d = parseDate(date);

    var lines = [];
    lines.push('【' + dateCN(date) + '学习任务】');
    lines.push('');
    lines.push('学习总时长：' + (st.hours ? st.hours + '小时' : '未填写'));
    lines.push('开始学习时间：' + (st.start || '未填写'));
    lines.push('学习地点：' + (st.place || '未填写'));
    lines.push('复盘时间：' + (st.review || '未填写'));
    lines.push('学习内容');

    /* 按学科分组 */
    var groups = {};
    var order = [];
    SUBJECTS.forEach(function (s) { groups[s.key] = []; order.push(s.key); });
    tasks.forEach(function (t) {
      if (!groups[t.subject]) { groups[t.subject] = []; order.push(t.subject); }
      groups[t.subject].push(t);
    });

    order.forEach(function (key) {
      var list = groups[key];
      if (!list || !list.length) return;
      var sub = subjectOf(key);
      lines.push('[' + sub.name + ']：');
      list.forEach(function (t, i) {
        lines.push((i + 1) + '. ' + t.title);
      });
    });

    return lines.join('\n');
  }

  /* 显示文本导出结果 */
  function showTextExport(title, text) {
    var root = $('#modalRoot');
    root.innerHTML = '<div class="textmodal">' +
      '<div class="textmodal-head">' +
        '<h3>' + esc(title) + '</h3>' +
        '<button class="textmodal-close" data-act="closeModal">✕</button>' +
      '</div>' +
      '<textarea class="textmodal-area" readonly>' + esc(text) + '</textarea>' +
      '<div class="textmodal-actions">' +
        '<button class="btn btn-primary" data-act="copyText" data-text="' + encodeURIComponent(text) + '">复制到剪贴板</button>' +
        '<button class="btn btn-ghost" data-act="shareText" data-text="' + encodeURIComponent(text) + '">分享</button>' +
      '</div>' +
    '</div>';
  }

  /* ---------------- 数据备份（JSON 导入 / 导出） ----------------
     和原生版共用同一份格式，互相能读：
     { app, schema, exportedAt, tasks, study, checkins, subjects, settings }
     —— 换手机、重装、更新版本之前导出这一份就够了。 */

  var BACKUP_APP = 'daily-task-list';

  function buildBackup() {
    var tasks = {};
    Object.keys(S.tasks).sort().forEach(function (d) { tasks[d] = S.tasks[d]; });

    var study = {};
    Object.keys(S.study).sort().forEach(function (d) {
      var s = S.study[d];
      if (s && (s.hours || s.start || s.place || s.review)) study[d] = s;
    });

    return {
      app: BACKUP_APP,
      schema: 1,
      exportedAt: new Date().toISOString(),
      tasks: tasks,
      study: study,
      checkins: Object.keys(S.checkins || {}).sort(),
      subjects: SUBJECTS.map(function (s) {
        return { key: s.key, name: s.name, color: s.color };
      }),
      settings: {
        theme: S.settings.theme || '#4BC85C',
        showCompletion: !!S.settings.showCompletion,
        sort: S.settings.sort || 'default'
      }
    };
  }

  function backupFileName() {
    return '每日任务清单备份-' + todayStr() + '.json';
  }

  function exportBackupFile() {
    var json = JSON.stringify(buildBackup(), null, 1);
    var blob = new Blob([json], { type: 'application/json' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.href = url;
    a.download = backupFileName();
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 5000);
    closeSheet();
    toast('备份已导出');
  }

  function copyBackupText() {
    copyText(JSON.stringify(buildBackup(), null, 1));
    closeSheet();
  }

  /* 备份文件一行的解析：格式不对就返回 error，不要把脏数据写进存储 */
  function parseBackup(text) {
    var data;
    try { data = JSON.parse(String(text).replace(/^\uFEFF/, '')); }
    catch (e) { return { error: 'JSON 解析失败，文件可能不完整' }; }
    if (!data || typeof data !== 'object') return { error: '这不是备份文件' };
    if (data.app && data.app !== BACKUP_APP) {
      return { error: '这是「' + data.app + '」的备份，不是本应用的' };
    }
    if (!data.tasks || typeof data.tasks !== 'object') {
      return { error: '备份里没有任务数据' };
    }

    var tasks = {}, total = 0, done = 0, days = 0, dates = [];
    Object.keys(data.tasks).forEach(function (d) {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return;
      var arr = data.tasks[d];
      if (!Array.isArray(arr)) return;
      var base = parseDate(d).getTime() + 8 * 3600 * 1000;
      var out = [];
      arr.forEach(function (t, i) {
        if (!t || typeof t !== 'object') return;
        var title = String(t.title == null ? '' : t.title).trim();
        if (!title) return;
        out.push({
          id: String(t.id || ('imp-' + d.replace(/-/g, '') + '-' + i)),
          title: title,
          subject: String(t.subject || 'other'),
          note: String(t.note == null ? '' : t.note),
          done: !!t.done,
          createdAt: typeof t.createdAt === 'number' && t.createdAt > 0 ? t.createdAt : base + i * 1000
        });
        if (t.done) done++;
      });
      if (out.length) { tasks[d] = out; total += out.length; days++; dates.push(d); }
    });
    if (!total) return { error: '备份里没有任何有效任务' };

    var study = {};
    if (data.study && typeof data.study === 'object') {
      Object.keys(data.study).forEach(function (d) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(d)) return;
        var s = data.study[d];
        if (!s || typeof s !== 'object') return;
        var o = {
          hours: String(s.hours == null ? '' : s.hours),
          start: String(s.start == null ? '' : s.start),
          place: String(s.place == null ? '' : s.place),
          review: String(s.review == null ? '' : s.review)
        };
        if (o.hours || o.start || o.place || o.review) study[d] = o;
      });
    }

    /* 打卡：原生版是数组，网页版自己是 { date: {time} } 的对象，两种都认 */
    var checkins = [];
    if (Array.isArray(data.checkins)) {
      data.checkins.forEach(function (d) {
        if (typeof d === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(d)) checkins.push(d);
      });
    } else if (data.checkins && typeof data.checkins === 'object') {
      Object.keys(data.checkins).forEach(function (d) {
        if (/^\d{4}-\d{2}-\d{2}$/.test(d)) checkins.push(d);
      });
    }

    var subjects = null;
    if (Array.isArray(data.subjects)) {
      var list = [];
      data.subjects.forEach(function (o) { var s = normSubject(o); if (s) list.push(s); });
      if (list.length) subjects = list;
    }

    var settings = data.settings && typeof data.settings === 'object' ? {
      theme: data.settings.theme ? toHexColor(data.settings.theme, '') || '' : '',
      sort: typeof data.settings.sort === 'string' ? data.settings.sort : ''
    } : null;

    dates.sort();
    return {
      data: { tasks: tasks, study: study, checkins: checkins, subjects: subjects, settings: settings },
      stats: {
        total: total, done: done, days: days,
        from: dates[0], to: dates[dates.length - 1]
      }
    };
  }

  function applyBackup(b, merge) {
    var added = 0;

    if (merge) {
      Object.keys(b.tasks).forEach(function (d) {
        var arr = rawTasks(d);
        var ids = {}, sigs = {};
        arr.forEach(function (t) { ids[t.id] = 1; sigs[t.title + '|' + t.subject] = 1; });
        b.tasks[d].forEach(function (t) {
          var sig = t.title + '|' + t.subject;
          if (!ids[t.id] && !sigs[sig]) {
            arr.push(t); ids[t.id] = 1; sigs[sig] = 1; added++;
          }
        });
        if (!arr.length) delete S.tasks[d];
      });
      Object.keys(b.study).forEach(function (d) {
        var cur = S.study[d];
        if (!cur || !(cur.hours || cur.start || cur.place || cur.review)) S.study[d] = b.study[d];
      });
      b.checkins.forEach(function (d) {
        if (!S.checkins[d]) S.checkins[d] = { time: '' };
      });
      if (b.subjects) {
        var keys = {}, names = {};
        SUBJECTS.forEach(function (s) { keys[s.key] = 1; names[s.name] = 1; });
        b.subjects.forEach(function (s) {
          if (!keys[s.key] && !names[s.name]) { SUBJECTS.push(s); keys[s.key] = 1; names[s.name] = 1; }
        });
      }
    } else {
      S.tasks = b.tasks;
      S.study = b.study;
      S.checkins = {};
      b.checkins.forEach(function (d) { S.checkins[d] = { time: '' }; });
      if (b.subjects) SUBJECTS = b.subjects;
      if (b.settings) {
        if (b.settings.theme) S.settings.theme = b.settings.theme;
        if (b.settings.sort) S.settings.sort = b.settings.sort;
      }
      added = Object.keys(b.tasks).reduce(function (n, d) { return n + b.tasks[d].length; }, 0);
    }

    /* 有真实数据了就别再塞示例任务，否则会混在历史记录里 */
    localStorage.setItem(LS_KEY + '_seeded', '1');
    save();
    applyTheme();

    var dates = Object.keys(S.tasks).sort();
    if (dates.length) {
      ui.date = dates[dates.length - 1];
      syncCalToDate();
    }
    ui.tab = 'all';
    ui.selectMode = false;
    ui.sel = {};
    renderList();
    return added;
  }

  function handleBackupText(text) {
    var r = parseBackup(text);
    if (r.error) { toast(r.error); return; }

    var s = r.stats;
    var body = '<div class="importstat">' + s.total + ' 条任务 · ' + s.days + ' 天 · 已完成 ' + s.done + ' 条</div>' +
      '<div class="hint" style="text-align:center;margin-top:2px">' + s.from + ' ~ ' + s.to + '</div>' +
      '<div class="hint" style="margin-top:14px">「合并」只补上 APP 里还没有的任务，不动现有记录；' +
      '「覆盖」会先清空当前全部数据再写入备份内容。</div>';

    closeSheet();
    setTimeout(function () {
      openSheet('导入备份', '选择「合并」或「覆盖」', body, [
        { label: '取消', cls: 'btn-cancel', onClick: closeSheet },
        {
          label: '合并导入（推荐）', cls: 'btn-primary', onClick: function () {
            var n = applyBackup(r.data, true);
            closeSheet();
            toast(n > 0 ? '已合并 ' + n + ' 条任务' : '没有新任务，记录都已存在');
          }
        },
        {
          label: '覆盖导入', cls: 'btn-warning', onClick: function () {
            var totalNow = Object.keys(S.tasks).reduce(function (n, d) { return n + S.tasks[d].length; }, 0);
            closeSheet();
            setTimeout(function () {
              confirmSheet('覆盖导入', '当前的 ' + totalNow + ' 条记录会被清空，只保留备份里的内容。确定吗？',
                '覆盖', function () {
                  var n = applyBackup(r.data, false);
                  toast('已写入 ' + n + ' 条任务');
                });
            }, 280);
          }
        }
      ]);
    }, 280);
  }

  function pickBackupFile() {
    var inp = document.createElement('input');
    inp.type = 'file';
    inp.accept = '.json,application/json,text/plain';
    inp.style.cssText = 'position:fixed;opacity:0;pointer-events:none;left:0;top:0';
    document.body.appendChild(inp);
    inp.addEventListener('change', function () {
      var f = inp.files && inp.files[0];
      if (!f) { inp.remove(); return; }
      var reader = new FileReader();
      reader.onload = function () {
        inp.remove();
        closeSheet();
        setTimeout(function () { handleBackupText(reader.result); }, 280);
      };
      reader.onerror = function () { inp.remove(); toast('读取文件失败'); };
      reader.readAsText(f, 'utf-8');
    });
    inp.click();
  }

  function openPasteBackup() {
    var body = '<div class="hint">把备份文件的内容整段粘贴进来，点击下面的「解析」。</div>' +
      '<textarea class="textarea" id="bkPaste" style="margin-top:10px;min-height:150px" placeholder="粘贴 JSON 内容…"></textarea>';
    openSheet('粘贴备份内容', '从其它设备复制的 JSON', body, [
      { label: '取消', cls: 'btn-cancel', onClick: closeSheet },
      {
        label: '解析', cls: 'btn-primary', onClick: function () {
          var v = ($('#bkPaste') || {}).value || '';
          if (!v.trim()) { toast('先粘贴备份内容'); return; }
          handleBackupText(v);
        }
      }
    ]);
  }

  function openBackupMenu() {
    var total = Object.keys(S.tasks).reduce(function (n, d) { return n + S.tasks[d].length; }, 0);
    var days = Object.keys(S.tasks).filter(function (d) { return S.tasks[d].length; }).length;
    var body = '<div class="importstat">' + total + ' 条任务 · ' + days + ' 天记录 · ' + totalCheckins() + ' 次打卡</div>' +
      '<div class="hint" style="margin-top:10px">导出的 JSON 可以直接给原生版 APK 导入，反过来也一样。' +
      '换手机、重装、更新版本之前，先导出一份存到网盘或发给自己。</div>' +
      '<div class="hint" style="margin-top:8px">如果这台设备的浏览器不支持下载文件，用「复制备份内容」再粘到备忘录里同样可以恢复。</div>';

    openSheet('数据备份', '导出 / 导入全部记录', body, [
      { label: '导出备份文件', cls: 'btn-primary', onClick: exportBackupFile },
      { label: '复制备份内容', cls: 'btn-ghost', onClick: copyBackupText },
      { label: '从文件恢复', cls: 'btn-ghost', onClick: pickBackupFile },
      { label: '粘贴内容恢复', cls: 'btn-ghost', onClick: openPasteBackup },
      { label: '关闭', cls: 'btn-cancel', onClick: closeSheet }
    ]);
  }

  /* ---------------- 云端同步面板 ---------------- */

  function fmtClock(ts) {
    if (!ts) return '';
    var d = new Date(ts), now = new Date();
    var hm = pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    if (d.toDateString() === now.toDateString()) return '今天 ' + hm;
    return (d.getMonth() + 1) + ' 月 ' + d.getDate() + ' 日 ' + hm;
  }

  function openSyncMenu() {
    var cfg = syncCfg();
    var statusText;
    if (cfg.lastError) statusText = '同步失败：' + cfg.lastError;
    else if (!cfg.token) statusText = '还没连接云端';
    else if (cfg.lastSyncAt) statusText = '上次同步 ' + fmtClock(cfg.lastSyncAt);
    else statusText = '已填同步码，点「连接」生效';

    var pending = readPending().length;

    var body =
      '<div class="importstat">' + esc(statusText) + '</div>' +
      (pending ? '<div class="hint" style="text-align:center;margin-top:6px">还有 ' + pending + ' 条改动等待上传</div>' : '') +
      '<div class="togglerow"><label>自动同步</label>' +
        '<button class="switch ' + (cfg.enabled ? 'on' : '') + '" data-act="syncToggle"></button>' +
      '</div>' +
      '<div class="study-row"><div class="study-label">服务器地址</div>' +
        '<input class="study-input" id="syncUrl" type="url" inputmode="url" autocomplete="off" placeholder="https://域名" value="' + esc(cfg.baseUrl) + '"></div>' +
      '<div class="study-row"><div class="study-label">同步码</div>' +
        '<input class="study-input" id="syncToken" type="password" autocomplete="off" placeholder="粘贴 64 位同步码" value="' + esc(cfg.token) + '"></div>' +
      '<div class="hint" style="margin-top:10px">多台设备填同一个同步码，就会看到同一份数据。连接时会把两边的记录合并，同一条以修改时间新的为准。</div>' +
      '<div class="hint" style="margin-top:8px">断网时照常记录，联网后自动补传，本地数据不会被云端清空。</div>';

    openSheet('云端同步', '手机、电脑共用一份数据', body, [
      { label: '连接 / 保存', cls: 'btn-primary', onClick: function () { applySyncForm(); } },
      { label: '立即同步', cls: 'btn-ghost', onClick: function () {
          var c = syncCfg();
          if (!c.bootDone) { toast('先填同步码并点「连接」'); return; }
          runSync().then(function (ok) { toast(ok ? '同步完成' : '同步失败'); });
        } },
      { label: '断开', cls: 'btn-ghost', onClick: function () {
          var c = syncCfg();
          c.enabled = false; c.bootDone = false; c.token = '';
          c.lastSeq = 0; c.lastSyncAt = 0; c.lastError = '';
          saveSyncCfg(c);
          closeSheet();
          toast('已断开，本地数据还在');
        } },
      { label: '关闭', cls: 'btn-cancel', onClick: closeSheet }
    ]);
  }

  function applySyncForm() {
    var urlEl = $('#syncUrl'), tokEl = $('#syncToken');
    var cfg = syncCfg();
    var url = urlEl ? urlEl.value.trim() : cfg.baseUrl;
    var token = tokEl ? tokEl.value.trim() : cfg.token;

    if (!url) { toast('请填服务器地址'); return; }
    if (!token) { toast('请填同步码'); return; }

    cfg.baseUrl = url;
    cfg.token = token;
    saveSyncCfg(cfg);

    if (!cfg.bootDone) {
      /* 第一次连接：先拉全量合并，再把本地独有的补上去 */
      toast('正在合并数据…');
      bootstrapSync(cfg).then(function () {
        closeSheet();
        toast('已连接，数据已合并');
      }, function (e) {
        toast(e && e.message ? e.message : '连接失败');
      });
    } else {
      cfg.enabled = true;
      saveSyncCfg(cfg);
      runSync().then(function (ok) {
        closeSheet();
        toast(ok ? '同步完成' : '同步失败');
      });
    }
  }

  /* ---------------- 事件 ---------------- */
  document.addEventListener('click', function (e) {
    var el = e.target.closest('[data-act]');
    if (!el) return;
    var act = el.getAttribute('data-act');
    haptic(act);

    switch (act) {
      /* ---- 通用 ---- */
      case 'back':
        goto('list', 'back'); renderList(); break;
      case 'closeModal':
        $('#modalRoot').innerHTML = ''; break;
      case 'enterWeb':
        hideWelcome(true); break;
      case 'showWelcome':
        closeSheet();
        setTimeout(showWelcome, 260); break;

      /* ---- 清单 ---- */
      case 'add':
        openEdit(null); break;
      case 'sort':
        openSort(); break;
      case 'multi':
        ui.selectMode = !ui.selectMode; ui.sel = {};
        renderList(); break;
      case 'exitSelect':
        ui.selectMode = false; ui.sel = {};
        renderList(); break;
      case 'tab':
        ui.tab = el.getAttribute('data-tab'); renderList(); break;
      case 'prevDay':
        ui.date = addDays(ui.date, -1); syncCalToDate(); renderList(); break;
      case 'nextDay':
        ui.date = addDays(ui.date, 1); syncCalToDate(); renderList(); break;
      case 'today':
        ui.date = todayStr(); syncCalToDate(); renderList(); break;
      case 'pickDay':
        ui.date = el.getAttribute('data-date'); syncCalToDate(); renderList(); break;
      case 'study':
        renderStudy(); goto('study', 'fwd'); break;
      case 'calendar': {
        var dd = parseDate(ui.date); ui.cal.y = dd.getFullYear(); ui.cal.m = dd.getMonth();
        renderCalendar(); goto('calendar', 'fwd'); break;
      }
      case 'checkin': {
        var date = ui.date;
        if (isCheckedIn(date)) {
          uncheckin(date);
          toast('已取消打卡');
        } else {
          checkin(date);
          toast('打卡成功！连续 ' + streakCount() + ' 天');
        }
        renderList();
        break;
      }
      case 'exportMenu':
        openExportMenu(); break;
      case 'backupMenu':
        closeSheet();
        setTimeout(openBackupMenu, 280); break;
      case 'syncMenu':
        closeSheet();
        setTimeout(openSyncMenu, 280); break;
      case 'downloadApk':
        /* 用当前页面的源拼出地址，换服务器不用改代码 */
        window.location.href = (syncCfg().baseUrl || DEFAULT_SYNC_URL).replace(/\/+$/, '') + APK_PATH + '?v=' + APK_VER;
        toast('开始下载安装包');
        break;
      case 'syncToggle': {
        var sc = syncCfg();
        if (!sc.bootDone) { toast('先填同步码并点「连接」'); break; }
        sc.enabled = !sc.enabled;
        saveSyncCfg(sc);
        if (el.classList) el.classList.toggle('on', sc.enabled);
        if (sc.enabled) scheduleSync(300);
        toast(sc.enabled ? '已开启自动同步' : '已关闭自动同步');
        break;
      }
      case 'exportSummary':
        closeSheet();
        showTextExport('每日总结', buildSummaryText(ui.date));
        break;
      case 'exportPlan':
        closeSheet();
        showTextExport('每日计划', buildPlanText(ui.date));
        break;
      case 'exportCalImg': {
        closeSheet();
        var dd2 = parseDate(ui.date); ui.cal.y = dd2.getFullYear(); ui.cal.m = dd2.getMonth();
        renderCalendar(); goto('calendar', 'fwd');
        showExport();
        break;
      }
      case 'copyText': {
        var txt = decodeURIComponent(el.getAttribute('data-text'));
        copyText(txt);
        break;
      }
      case 'shareText': {
        var txt2 = decodeURIComponent(el.getAttribute('data-text'));
        shareText(txt2);
        break;
      }
      case 'migrate':
        openMigrate(); break;
      case 'calendar':
        var dd = parseDate(ui.date); ui.cal.y = dd.getFullYear(); ui.cal.m = dd.getMonth();
        renderCalendar(); goto('calendar', 'fwd'); break;
      case 'toggle': {
        e.stopPropagation();
        var id = el.getAttribute('data-id');
        var arr = rawTasks(ui.date);
        for (var i = 0; i < arr.length; i++) {
          if (arr[i].id === id) { arr[i].done = !arr[i].done; break; }
        }
        save(); renderList(); break;
      }
      case 'open': {
        var tid = el.getAttribute('data-id');
        if (ui.selectMode) {
          ui.sel[tid] = !ui.sel[tid];
          renderList();
        } else {
          openEdit(tid);
        }
        break;
      }
      case 'selAll': {
        var all = tasksOf(ui.date);
        var allOn = all.length > 0 && all.every(function (t) { return ui.sel[t.id]; });
        ui.sel = {};
        if (!allOn) all.forEach(function (t) { ui.sel[t.id] = true; });
        renderList(); break;
      }
      case 'selDone': {
        var ids = Object.keys(ui.sel).filter(function (k) { return ui.sel[k]; });
        if (!ids.length) { toast('请先选择任务'); return; }
        var a2 = rawTasks(ui.date);
        a2.forEach(function (t) { if (ui.sel[t.id]) t.done = true; });
        save(); ui.selectMode = false; ui.sel = {}; renderList(); toast('已标记完成'); break;
      }
      case 'selDelete': {
        var ids2 = Object.keys(ui.sel).filter(function (k) { return ui.sel[k]; });
        if (!ids2.length) { toast('请先选择任务'); return; }
        confirmSheet('删除任务', '将删除选中的 ' + ids2.length + ' 项任务，不可恢复。', '删除', function () {
          S.tasks[ui.date] = rawTasks(ui.date).filter(function (t) { return !ui.sel[t.id]; });
          if (!S.tasks[ui.date].length) delete S.tasks[ui.date];
          save(); ui.selectMode = false; ui.sel = {}; renderList(); toast('已删除');
        });
        break;
      }

      case 'saveStudy':
        saveStudy(); break;
      case 'carryOver':
        carryOver(); break;

      /* ---- 编辑 ---- */
      case 'subject':
        syncDraft();
        ui.draft.subject = el.getAttribute('data-key');
        renderEdit(); break;
      case 'status':
        syncDraft();
        ui.draft.done = !ui.draft.done;
        renderEdit(); break;
      case 'save':
        saveTask(); break;
      case 'delete':
        deleteTask(); break;

      /* ---- 日历 ---- */
      case 'calPrev':
        ui.cal.m--; if (ui.cal.m < 0) { ui.cal.m = 11; ui.cal.y--; }
        renderCalendar(); break;
      case 'calNext':
        ui.cal.m++; if (ui.cal.m > 11) { ui.cal.m = 0; ui.cal.y++; }
        renderCalendar(); break;
      case 'pickCalDay':
        ui.date = el.getAttribute('data-date');
        ui.tab = 'all';
        renderList(); goto('list', 'back'); break;
      case 'toggleCompletion':
        S.settings.showCompletion = !S.settings.showCompletion;
        save(); renderCalendar(); break;
      case 'theme':
        S.settings.theme = el.getAttribute('data-color');
        save(); applyTheme(); renderCalendar(); break;
      case 'themeCustom':
        var inp = document.createElement('input');
        inp.type = 'color';
        inp.value = S.settings.theme || '#4BC85C';
        inp.style.cssText = 'position:fixed;opacity:0;pointer-events:none;left:50%;top:50%';
        document.body.appendChild(inp);
        inp.addEventListener('change', function () {
          S.settings.theme = inp.value; save(); applyTheme(); renderCalendar();
          setTimeout(function () { inp.remove(); }, 100);
        });
        inp.click();
        break;
      case 'export':
        showExport(); break;
    }
  });

  function syncCalToDate() {
    var d = parseDate(ui.date);
    ui.cal.y = d.getFullYear();
    ui.cal.m = d.getMonth();
  }

  /* ---------------- 首次使用示例数据 ---------------- */
  function seedIfEmpty() {
    var hasAny = Object.keys(S.tasks).length > 0 || localStorage.getItem(LS_KEY + '_seeded');
    if (hasAny) return;
    var t = todayStr();
    var now = Date.now();
    var ids = [uid(), uid(), uid()];
    S.tasks[t] = [
      { id: ids[0], title: '把今天最要紧的一件事挑出来', subject: 'work', note: '先做它', done: true, createdAt: now },
      { id: ids[1], title: '看书半小时', subject: 'study', note: '', done: false, createdAt: now + 1 },
      { id: ids[2], title: '出门走一走', subject: 'health', note: '', done: false, createdAt: now + 2 }
    ];
    S.study[t] = { hours: '2', start: '20:00', place: '', review: '' };
    /* 记住哪些是示例，第一次连云端时要把它们扔掉，不能混进真实记录 */
    try { localStorage.setItem(LS_KEY + '_demo', JSON.stringify({ ids: ids, date: t })); } catch (e) {}
    localStorage.setItem(LS_KEY + '_seeded', '1');
    save();
  }

  /* 首次连云端前调用：把没被动过的示例任务清掉 */
  function dropDemoSeed() {
    var meta = null;
    try { meta = JSON.parse(localStorage.getItem(LS_KEY + '_demo') || 'null'); } catch (e) { meta = null; }
    if (!meta || !meta.ids || !meta.ids.length) return false;

    var map = {};
    meta.ids.forEach(function (id) { map[id] = true; });

    var removed = false;
    Object.keys(S.tasks).forEach(function (d) {
      if (!S.tasks[d]) return;
      var n = S.tasks[d].length;
      S.tasks[d] = S.tasks[d].filter(function (t) { return !(t && map[t.id]); });
      if (S.tasks[d].length !== n) removed = true;
      if (!S.tasks[d].length) delete S.tasks[d];
    });

    if (meta.date && S.study[meta.date] && S.study[meta.date].hours === '2' && !S.study[meta.date].place) {
      delete S.study[meta.date];
      removed = true;
    }

    localStorage.removeItem(LS_KEY + '_demo');
    if (removed) {
      persistOnly();
      baseFlat = flatState(S);
    }
    return removed;
  }

  /* ---------------- 启动 ---------------- */
  var booted = false;
  /* ---------------- 首屏欢迎页 ----------------
     先让人看清「这是什么、有安卓版可以装」，再决定进网页版。
     看过一次就记住，老用户和装到主屏幕的不会再被拦一道。 */

  var WELCOME_KEY = 'todo_welcome_v1';

  function welcomeEl() { return $('#welcome'); }

  function showWelcome() {
    var w = welcomeEl();
    if (!w) return;
    w.hidden = false;
    w.scrollTop = 0;
  }

  function hideWelcome(remember) {
    var w = welcomeEl();
    if (w) w.hidden = true;
    if (remember) {
      try { localStorage.setItem(WELCOME_KEY, '1'); } catch (e) {}
    }
  }

  function initWelcome() {
    if (!welcomeEl()) return;

    var q = location.search;
    if (q.indexOf('welcome=1') !== -1) { showWelcome(); return; }
    if (q.indexOf('skip=1') !== -1) { hideWelcome(true); return; }

    /* 加过主屏幕（standalone）直接进应用，别每次开机都拦一下 */
    var standalone = false;
    try {
      standalone = window.matchMedia('(display-mode: standalone)').matches ||
        window.navigator.standalone === true;
    } catch (e) {}

    var seen = false;
    try { seen = localStorage.getItem(WELCOME_KEY) === '1'; } catch (e) {}

    if (standalone || seen) hideWelcome(false);
    else showWelcome();
  }

  function boot() {
    if (booted) return;
    booted = true;
    /* 不再播种示例任务：数据都从云端拉，空白设备就该显示空白，
       免得示例记录被当成真数据。 */
    migrateSyncUrl();
    applyTheme();
    /* 放在 applyTheme 之后：欢迎页主按钮用的是主题色，
       先定色再显示，否则会先绿一下再跳成用户选的颜色。 */
    initWelcome();
    renderList();
    goto('list', 'fade');

    /* 云端同步：开机补一次，之后每 30 秒对账；切回前台和网络恢复时也补一次。
       没开同步时 runSync 内部直接返回，不会有额外请求。 */
    baseFlat = flatState(S);
    setTimeout(function () { runSync(); }, 1200);
    setInterval(function () { runSync(); }, 30000);
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) runSync();
    });
    window.addEventListener('online', function () { runSync(); });

    if ('serviceWorker' in navigator && location.protocol.indexOf('http') === 0 && !window.Capacitor) {
      navigator.serviceWorker.register('sw.js').catch(function () {});
    }

    /* Android 物理返回键：子页面回列表，列表页退出 */
    var CapApp = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.App;
    if (CapApp) {
      CapApp.addListener('backButton', function () {
        if (ui.selectMode) {
          ui.selectMode = false; ui.sel = {}; renderList();
        } else if (ui.page !== 'list') {
          goto('list', 'back'); renderList();
        } else {
          CapApp.exitApp();
        }
      });
    }
    if (location.search.indexOf('debug=edit') !== -1) openEdit(null);
    if (location.search.indexOf('debug=study') !== -1) { renderStudy(); goto('study', 'fwd'); }
    if (location.search.indexOf('debug=export') !== -1) openExportMenu();
    if (location.search.indexOf('debug=backup') !== -1) openBackupMenu();
    if (location.search.indexOf('debug=summary') !== -1) showTextExport('每日总结', buildSummaryText(ui.date));
    if (location.search.indexOf('debug=plan') !== -1) showTextExport('每日计划', buildPlanText(ui.date));
  }

  /* 暴露少量 API 供测试 / 集成使用 */
  window.__TodoUI = ui;
  window.__TodoAPI = {
    todayStr: todayStr,
    addDays: addDays,
    tasksOf: tasksOf,
    studyOf: studyOf,
    progressOf: progressOf,
    isCheckedIn: isCheckedIn,
    totalCheckins: totalCheckins,
    streakCount: streakCount,
    checkin: checkin,
    uncheckin: uncheckin,
    save: save,
    buildSummaryText: buildSummaryText,
    buildPlanText: buildPlanText,
    buildBackup: buildBackup,
    parseBackup: parseBackup,
    applyBackup: applyBackup,
    handleBackupText: handleBackupText,
    openBackupMenu: openBackupMenu,
    subjects: function () { return SUBJECTS; },
    openEdit: openEdit,
    renderList: renderList,
    /* 云端同步（测试与排查用） */
    state: function () { return S; },
    syncCfg: syncCfg,
    runSync: runSync,
    bootstrapSync: bootstrapSync,
    flatState: function () { return flatState(S); },
    applyRemote: applyRemote,
    dropDemoSeed: dropDemoSeed,
    readPending: readPending,
    writePending: writePending,
    openSyncMenu: openSyncMenu
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
