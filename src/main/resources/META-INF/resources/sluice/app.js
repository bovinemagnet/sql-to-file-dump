/* Sluice — app shell, router, theme, and the live poll loop against /api. */

const VIEWS = {
  dashboard:   { top: dashboardTop,   body: dashboardView },
  live:        { top: liverunTop,     body: liverunView },
  transforms:  { top: transformsTop,  body: transformsView },
  schedules:   { top: schedulesTop,   body: schedulesView },
  connections: { top: connectionsTop, body: connectionsView },
};

const LS = { theme: 'sluice.theme', view: 'sluice.view' };
let CURRENT = 'dashboard';
let JOB_FILTER = 'all';

/* live state, hydrated from the daemon */
const STATE = {
  jobs: [],
  metrics: null,
  connections: [],   // saved JDBC connections from /api/connections
  editingConn: null, // id of the connection currently being edited, or null
  schedules: [],     // schedules from /api/schedules
  editingSchedule: null, // id of the schedule currently being edited, or null
  transformations: [], // per-job transform summaries from /api/transformations
  series: {},        // jobId → raw instantaneous rows/s samples (normalised only at draw time)
  log: {},           // jobId → synthesised batch log lines
  lastRows: {},      // jobId → { rows, t } for instantaneous throughput
  pendingDelete: null, // { kind: 'connection'|'schedule', id, until } — inline two-step confirm
};

/* ───────── small helpers ───────── */
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
/* attribute-context escape — esc() plus single quotes, so values are inert in
 * both double- and single-quoted HTML attributes */
function escAttr(s) { return esc(s).replace(/'/g, '&#39;'); }

function fmtUptime(ms) {
  const s = Math.floor((ms || 0) / 1000);
  const d = Math.floor(s / 86400), h = Math.floor((s % 86400) / 3600), m = Math.floor((s % 3600) / 60);
  if (d > 0) return `${d}d ${String(h).padStart(2, '0')}h`;
  if (h > 0) return `${h}h ${String(m).padStart(2, '0')}m`;
  return `${m}m`;
}
function fmtCompact(n) {
  if (n >= 1e9) return (n / 1e9).toFixed(1) + 'B';
  if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M';
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'k';
  return String(Math.round(n || 0));
}
function clockNow() {
  const d = new Date();
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`;
}
function setBusy(id, busy) {
  const el = document.getElementById(id);
  if (!el) return;
  el.style.opacity = busy ? '.6' : '';
  el.style.pointerEvents = busy ? 'none' : '';
}
function applyJobFilter() {
  const t = document.getElementById('jobsTable');
  if (!t) return;
  t.querySelectorAll('.orow').forEach(r => {
    const st = r.dataset.status;
    r.style.display = (JOB_FILTER === 'all' || !st || st === JOB_FILTER) ? '' : 'none';
  });
}

/* ───────── theme ───────── */
function getTheme() {
  try { return localStorage.getItem(LS.theme) || 'dark'; } catch (e) { return 'dark'; }
}
function setTheme(t) {
  const f = document.querySelector('.ops-shell');
  if (f) f.setAttribute('data-theme', t);
  document.querySelectorAll('#themeToggle button').forEach(b => b.classList.toggle('on', b.dataset.t === t));
  try { localStorage.setItem(LS.theme, t); } catch (e) {}
}

/* ───────── router ───────── */
function setView(key) {
  if (!VIEWS[key]) key = 'dashboard';
  CURRENT = key;
  document.querySelectorAll('.dnav__item').forEach(i => i.classList.toggle('dnav__item--on', i.dataset.view === key));
  document.getElementById('topslot').innerHTML = VIEWS[key].top();
  const body = document.getElementById('bodyslot');
  body.innerHTML = VIEWS[key].body();
  body.scrollTop = 0;
  if (key === 'dashboard') { wireDashboard(); applyJobFilter(); }
  if (key === 'connections') loadConnections();
  if (key === 'schedules') loadSchedules();
  if (key === 'transforms') loadTransformations();
  try { localStorage.setItem(LS.view, key); } catch (e) {}
}

function mount() {
  const theme = getTheme();
  const app = document.getElementById('app');
  app.innerHTML = `<div class="dframe ops-shell" data-theme="${theme}">
    ${chrome(theme)}
    <div class="dapp">
      ${sideNav('dashboard')}
      <div class="dmain">
        <div id="topslot"></div>
        <div class="dbody" id="bodyslot"></div>
      </div>
    </div>
  </div>`;

  let start = 'dashboard';
  try { start = localStorage.getItem(LS.view) || 'dashboard'; } catch (e) {}
  setView(start);
  wire(app);
  refreshData();
  loadConnections();
  setInterval(refreshData, 1500);
}

/* ───────── delegated interactions ───────── */
function wire(root) {
  root.addEventListener('click', (e) => {
    const themeBtn = e.target.closest('#themeToggle button');
    if (themeBtn) { setTheme(themeBtn.dataset.t); return; }

    const seg = e.target.closest('[data-seg]');
    if (seg) {
      const grp = seg.closest('.dseg');
      grp.querySelectorAll('[data-seg]').forEach(s => s.classList.remove('dseg__i--on'));
      seg.classList.add('dseg__i--on');
      if (seg.dataset.seg === 'trigger') {
        document.querySelectorAll('.trigblock').forEach(b => { b.hidden = (b.dataset.trig !== seg.dataset.val); });
      }
      return;
    }

    const sw = e.target.closest('[data-toggle]');
    if (sw) { sw.classList.toggle('sw--on'); return; }

    const chip = e.target.closest('.dchip[data-filter]');
    if (chip) {
      chip.parentElement.querySelectorAll('.dchip').forEach(c => c.classList.remove('dchip--on'));
      chip.classList.add('dchip--on');
      JOB_FILTER = chip.dataset.filter;
      applyJobFilter();
      return;
    }

    const refresh = e.target.closest('#refreshBtn');
    if (refresh) { refreshData(); return; }

    // connection management (Connections view)
    const connAct = e.target.closest('[data-conn-action]');
    if (connAct) { e.stopPropagation(); handleConnAction(connAct.dataset.connAction, connAct.dataset.id); return; }
    if (e.target.closest('#connSave')) { saveConnection(); return; }
    if (e.target.closest('#connTest')) { testConnForm(); return; }
    if (e.target.closest('#connCancel')) { cancelConnEdit(); return; }
    if (e.target.closest('#connAddCard') || e.target.closest('#connAddBtn')) { focusConnForm(); return; }

    // schedule management (Schedules view)
    const schedAct = e.target.closest('[data-sched-action]');
    if (schedAct) { e.stopPropagation(); handleSchedAction(schedAct.dataset.schedAction, schedAct.dataset.id); return; }
    if (e.target.closest('#schedSave')) { saveSchedule(); return; }
    if (e.target.closest('#schedCancel')) { cancelSchedEdit(); return; }
    if (e.target.closest('#schedAddBtn')) { focusSchedForm(); return; }

    const nav = e.target.closest('[data-view]');
    if (nav) { setView(nav.dataset.view); return; }
  });

  root.addEventListener('input', (e) => {
    if (e.target.id === 'cronInput') {
      const h = document.getElementById('cronHint');
      if (h) h.textContent = cronHuman(e.target.value);
    }
  });

  root.addEventListener('change', (e) => {
    if (e.target.id === 'connPicker') applyConnPicker(e.target.value);
  });
}

/* fill the Dashboard form's url/user/password-env from a saved connection */
function populateConnPicker() {
  const sel = document.getElementById('connPicker');
  if (!sel) return;
  const current = sel.value;
  sel.innerHTML = `<option value="">— manual entry —</option>` +
    STATE.connections.map(c => `<option value="${escAttr(c.id)}">${esc(c.name)} — ${esc(c.url)}</option>`).join('');
  if (STATE.connections.some(c => c.id === current)) sel.value = current;
}

function applyConnPicker(id) {
  const form = document.getElementById('dashForm');
  if (!form) return;
  form.dataset.connId = id || '';
  const c = STATE.connections.find(x => x.id === id);
  if (!c) return;
  form.querySelector('[name=url]').value = c.url || '';
  form.querySelector('[name=user]').value = c.user || '';
  form.querySelector('[name=passwordEnv]').value = c.passwordEnv || '';
}

function cronHuman(expr) {
  const map = {
    '0 2 * * *': 'At 02:00, every day',
    '0 3 * * 1': 'At 03:00, only on Monday',
    '*/15 * * * *': 'Every 15 minutes',
    '0 * * * *': 'Every hour, on the hour',
    '0 0 * * *': 'At midnight, every day',
    '30 6 * * 1-5': 'At 06:30, Monday through Friday',
  };
  const e = String(expr == null ? '' : expr).trim();
  if (map[e]) return map[e];
  const p = e.split(/\s+/);
  if (p.length !== 5) return 'Enter 5 fields: min hour day-of-month month day-of-week';
  const num = f => (/^\d+$/.test(f) ? parseInt(f, 10) : null);
  const step = f => { const m = /^\*\/(\d+)$/.exec(f); return m ? parseInt(m[1], 10) : null; };
  const allStar = fields => fields.every(f => f === '*');
  const pad = n => String(n).padStart(2, '0');

  if (allStar(p)) return 'Every minute';
  const minStep = step(p[0]);
  if (minStep != null && allStar(p.slice(1))) {
    return minStep === 1 ? 'Every minute' : `Every ${minStep} minutes`;
  }
  const hourStep = step(p[1]);
  if (num(p[0]) != null && hourStep != null && allStar(p.slice(2))) {
    return hourStep === 1
      ? `Every hour, at ${pad(num(p[0]))} past`
      : `Every ${hourStep} hours, at ${pad(num(p[0]))} past`;
  }
  if (num(p[0]) != null && num(p[1]) != null && allStar(p.slice(2))) {
    return `At ${pad(num(p[1]))}:${pad(num(p[0]))}, every day`;
  }
  /* not confidently describable — show the raw expression rather than guess */
  return e;
}

/* ───────── live data poll ───────── */
let POLL_IN_FLIGHT = false; // one poll at a time — a slow response must never overwrite newer state

function setOnline(online) {
  const b = document.getElementById('daemonBadge');
  if (!b) return;
  b.classList.toggle('daemon--off', !online);
  const t = b.querySelector('span');
  if (t) t.textContent = online ? 'Online' : 'Offline';
}

async function refreshData() {
  if (POLL_IN_FLIGHT) return; // previous tick still outstanding — skip, next tick retries
  POLL_IN_FLIGHT = true;
  try {
    /* independent fetches — one failing must not discard the other's data */
    const [jobsRes, metricsRes] = await Promise.allSettled([API.jobs(), API.metrics()]);
    setOnline(jobsRes.status === 'fulfilled' || metricsRes.status === 'fulfilled');
    if (jobsRes.status === 'fulfilled') {
      updateSeries(jobsRes.value);
      STATE.jobs = jobsRes.value;
    }
    if (metricsRes.status === 'fulfilled') STATE.metrics = metricsRes.value;
    updateNavFoot();
    if (CURRENT === 'dashboard') {
      updateDashboard();
    } else if (CURRENT === 'live') {
      document.getElementById('topslot').innerHTML = liverunTop();
      document.getElementById('bodyslot').innerHTML = liverunView();
    } else if (CURRENT === 'transforms') {
      await loadTransformations();
    } else if (CURRENT === 'schedules') {
      await pollSchedules();
    } else if (CURRENT === 'connections') {
      await pollConnections();
    }
  } finally {
    POLL_IN_FLIGHT = false;
  }
}

/* transient notice — bottom-right toast, used to surface action failures */
function toast(msg, ok) {
  const shell = document.querySelector('.ops-shell') || document.body;
  let host = document.getElementById('toastHost');
  if (!host) {
    host = document.createElement('div');
    host.id = 'toastHost';
    host.className = 'toasthost';
    shell.appendChild(host);
  }
  const t = document.createElement('div');
  t.className = 'toast ' + (ok ? 'toast--ok' : 'toast--err');
  t.textContent = msg;
  host.appendChild(t);
  setTimeout(() => { t.classList.add('toast--out'); setTimeout(() => t.remove(), 400); }, 4500);
}

function updateSeries(jobs) {
  const now = Date.now();
  jobs.forEach(j => {
    if (j.status !== 'running') return;
    if (!STATE.series[j.id]) STATE.series[j.id] = [];
    if (!STATE.log[j.id]) STATE.log[j.id] = [];

    const prev = STATE.lastRows[j.id];
    let instantaneous = j.throughput;
    if (prev) {
      const dt = (now - prev.t) / 1000;
      if (dt > 0) instantaneous = (j.rowCount - prev.rows) / dt;
    }
    if (!Number.isFinite(instantaneous) || instantaneous < 0) instantaneous = 0;
    STATE.series[j.id].push(instantaneous);
    if (STATE.series[j.id].length > 44) STATE.series[j.id].shift();

    if (!prev || j.rowCount > prev.rows) {
      const batch = j.fetchSize ? Math.floor(j.rowCount / j.fetchSize) : 0;
      STATE.log[j.id].push({ t: clockNow(), l: 'info', m: `batch ${fmtInt(batch)} · <b>${fmtInt(j.rowCount)} rows</b> · ${fmtBytes(j.outputBytes)}` });
      if (STATE.log[j.id].length > 40) STATE.log[j.id].shift();
    }
    STATE.lastRows[j.id] = { rows: j.rowCount, t: now };
  });
}

function updateNavFoot() {
  const m = STATE.metrics;
  if (!m) return;
  const up = document.getElementById('footUptime');
  if (up) up.textContent = fmtUptime(m.uptimeMillis);
  const q = document.getElementById('navQueued');
  if (q) { q.hidden = m.queued === 0; q.textContent = m.queued; }
  const live = document.getElementById('navLiveDot');
  if (live) live.hidden = m.running === 0;
}

document.addEventListener('DOMContentLoaded', mount);
