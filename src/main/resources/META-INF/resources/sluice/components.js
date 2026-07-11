/* Sluice — shared render components. Plain functions returning HTML strings.
 * Convention: components escape their own inputs (esc()/escAttr() from app.js),
 * so callers pass RAW values — do not pre-escape arguments. */

function icon(name, cls) { return `<span class="icon ${cls || ''}">${name}</span>`; }

/* status pill */
const ST_META = {
  running:   { c: 'run',     ic: null,        lab: 'Running',  dot: true },
  queued:    { c: 'queued',  ic: null,        lab: 'Queued',   dot: true },
  completed: { c: 'done',    ic: 'check',      lab: 'Completed' },
  failed:    { c: 'failed',  ic: 'error',      lab: 'Failed',   dot: true },
  scheduled: { c: 'scheduled', ic: 'schedule', lab: 'Scheduled' },
  paused:    { c: 'paused',  ic: 'pause',      lab: 'Paused' },
  canceled:  { c: 'canceled', ic: 'block',     lab: 'Canceled' },
};
function statusPill(status) {
  const m = ST_META[status] || ST_META.queued;
  const inner = m.dot ? `<i class="dt"></i>` : icon(m.ic);
  return `<span class="st st--${m.c}">${inner}${m.lab}</span>`;
}

function formatPill(fmt) { return `<span class="fmt fmt--${escAttr(fmt)}">${esc(fmt)}</span>`; }

function driverBadge(driver) {
  return `<span class="drv drv--${escAttr(driver)}"><i class="d"></i>${esc(driver)}</span>`;
}

/* KPI tile (reuses rf-desktop .kpi) */
function kpiTile(o) {
  const delta = o.delta
    ? `<div class="kpi__d ${o.deltaDir || 'up'}">${icon(o.deltaDir === 'down' ? 'trending_down' : 'trending_up')}${esc(o.delta)}</div>`
    : (o.sub ? `<div class="kpi__d up" style="color:var(--dim)">${esc(o.sub)}</div>` : '');
  return `<div class="kpi">
    <div class="kpi__k">${icon(o.ic)}${esc(o.k)}</div>
    <div class="kpi__v"><b class="num" ${o.id ? `id="${escAttr(o.id)}"` : ''}>${esc(o.v)}</b>${o.unit ? `<i>${esc(o.unit)}</i>` : ''}</div>
    ${delta}
  </div>`;
}

/* progress bar */
function progressBar(pct, o) {
  o = o || {};
  return `<div class="bar ${o.lg ? 'bar--lg' : ''} ${o.cls || ''}">
    <div class="bar__fill ${o.stripe ? 'bar__fill--stripe' : ''}" style="width:${Math.max(0, Math.min(100, pct)).toFixed(1)}%" ${o.fillId ? `id="${o.fillId}"` : ''}></div>
  </div>`;
}

/* naive SQL highlighter (display only) */
function sqlHL(sql) {
  const esc = sql.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const kw = /\b(select|from|where|join|inner|left|right|outer|on|order|by|group|having|as|and|or|not|in|is|null|date|with|limit|offset|distinct|desc|asc|union|case|when|then|else|end)\b/gi;
  let out = esc
    .replace(/('(?:[^'\\]|\\.)*')/g, '<span class="str">$1</span>')
    .replace(/\b(\d+)\b/g, '<span class="n">$1</span>')
    .replace(kw, (m) => `<span class="kw">${m}</span>`);
  return out;
}
function sqlBlock(sql) { return `<pre class="sqlb">${sqlHL(sql)}</pre>`; }

/* log line */
function logLine(e) {
  const lab = { info: 'INFO', ok: 'OK', warn: 'WARN', err: 'ERR' }[e.l] || 'INFO';
  return `<div class="logline">
    <span class="logline__t">${e.t}</span>
    <span class="logline__l ${e.l}">${lab}</span>
    <span class="logline__m">${e.m}</span>
  </div>`;
}

/* ───────── app shell ───────── */
const NAV = [
  ['Operate', [
    ['dashboard', 'Dashboard', 'dashboard'],
    ['monitoring', 'Live Run', 'live'],
    ['transform', 'Transformations', 'transforms'],
  ]],
  ['Automate', [
    ['schedule', 'Schedules', 'schedules'],
  ]],
  ['System', [
    ['database', 'Connections', 'connections'],
  ]],
];

function sideNav(active) {
  const groups = NAV.map(([label, items]) => `
    <div class="dnav__group">${label}</div>
    ${items.map(([ic, name, key]) => {
      const on = key === active;
      const trail = key === 'live'
        ? `<span id="navLiveDot" hidden><i class="livedot"></i></span>`
        : (key === 'dashboard' ? `<span class="tag" id="navQueued" hidden>0</span>` : '');
      return `<div class="dnav__item ${on ? 'dnav__item--on' : ''}" data-view="${key}">
        ${icon(ic)}<span>${name}</span>${trail}</div>`;
    }).join('')}
  `).join('');

  return `<div class="dnav">
    <div class="dnav__brand">
      <div class="dnav__logo">${icon('database', 'icon--filled')}</div>
      <div class="dnav__name">Slu<span>ice</span></div>
    </div>
    ${groups}
    <div class="dnav__spacer"></div>
    <div class="dnav__foot">
      <div class="row"><span class="k">Daemon</span><span class="v accent">running</span></div>
      <div class="row"><span class="k">Uptime</span><span class="v" id="footUptime">${KPI_DASH.uptime}</span></div>
      <div class="row"><span class="k">Workers</span><span class="v" id="footWorkers">virtual</span></div>
    </div>
  </div>`;
}

function chrome(theme) {
  return `<div class="ochrome">
    <div class="ochrome__lights"><i style="background:#ff5f57"></i><i style="background:#febc2e"></i><i style="background:#28c840"></i></div>
    <div class="ochrome__url">${icon('lock')}localhost:8080 · jdbc-export daemon</div>
    <div class="ochrome__right">
      <span class="daemon"><i></i>Online</span>
      <div class="othemes" id="themeToggle">
        <button data-t="dark" class="${theme === 'dark' ? 'on' : ''}">Dark</button>
        <button data-t="light" class="${theme === 'light' ? 'on' : ''}">Light</button>
      </div>
    </div>
  </div>`;
}

/* topbar — title, subtitle, right-side actions slot */
function topbar(o) {
  return `<div class="dtop">
    <div>
      <div class="dtop__title">${o.ic ? icon(o.ic) : ''}${o.title}</div>
      <div class="dtop__sub">${o.sub}</div>
    </div>
    <div class="dtop__spacer"></div>
    ${o.actions || ''}
  </div>`;
}

function connName(id) {
  const live = (typeof STATE !== 'undefined' && STATE.connections) ? STATE.connections.find(x => x.id === id) : null;
  return live ? live.name : (id || '—');
}
