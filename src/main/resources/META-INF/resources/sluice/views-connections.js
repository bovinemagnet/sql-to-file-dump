/* Sluice — Connections view, wired to the daemon's saved-connection store (/api/connections).
 * Passwords are never stored — only a password-env reference. */

const DRV_COLOR = {
  postgres: 'var(--z-warm)', postgresql: 'var(--z-warm)',
  duckdb: 'var(--z-anaero)', oracle: 'var(--danger)', mysql: 'var(--z-aero)',
};
function driverLabel(d) {
  return { postgres: 'PostgreSQL', postgresql: 'PostgreSQL', duckdb: 'DuckDB', oracle: 'Oracle', mysql: 'MySQL' }[d] || d;
}
function fmtAgo(ms) {
  if (!ms) return 'never used';
  const s = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (s < 45) return 'used now';
  if (s < 3600) return 'used ' + Math.floor(s / 60) + 'm ago';
  if (s < 86400) return 'used ' + Math.floor(s / 3600) + 'h ago';
  return 'used ' + Math.floor(s / 86400) + 'd ago';
}

function connStatusPill(c) {
  if (c.status === 'reachable') return `<span class="st st--done">${icon('check')}reachable</span>`;
  if (c.status === 'failed') return `<span class="st st--failed"><i class="dt"></i>unreachable</span>`;
  return `<span class="st st--queued"><i class="dt"></i>untested</span>`;
}

function connCard(c) {
  return `<div class="conncard" data-conn-action="edit" data-id="${escAttr(c.id)}">
    <div class="conncard__h">
      <div class="conncard__ic" style="background:${DRV_COLOR[c.driver] || 'var(--s3)'}">${icon('database', 'icon--filled')}</div>
      <div class="conncard__t">
        <b>${esc(c.name)}</b>
        <span>${esc(driverLabel(c.driver))} · user ${esc(c.user)}${c.passwordEnv ? ' · $' + esc(c.passwordEnv) : ''}</span>
      </div>
      ${connStatusPill(c)}
    </div>
    <div class="conncard__url">${esc(c.url)}</div>
    <div class="conncard__foot">
      <span class="meta">${icon('schedule')} ${esc(fmtAgo(c.lastUsedEpochMs))}</span>
      <div class="orow__act">
        <div class="iact iact--accent" title="Test connection" data-conn-action="test" data-id="${escAttr(c.id)}">${icon('wifi_tethering')}</div>
        <div class="iact" title="Edit" data-conn-action="edit" data-id="${escAttr(c.id)}">${icon('edit')}</div>
        <div class="iact iact--danger" title="Remove" data-conn-action="delete" data-id="${escAttr(c.id)}">${icon('delete')}</div>
      </div>
    </div>
  </div>`;
}

function connFormHTML() {
  const editing = STATE.editingConn ? STATE.connections.find(c => c.id === STATE.editingConn) : null;
  const v = editing || { name: '', driver: '', url: '', user: '', passwordEnv: '' };
  const drivers = ['postgres', 'duckdb', 'oracle', 'mysql'];
  const driverOpts = drivers.map(d => `<option value="${d}" ${v.driver === d ? 'selected' : ''}>${driverLabel(d)}</option>`).join('');
  return `<div class="dcard" style="margin-top:30px" id="connForm">
    <div class="dcard__h">
      <div class="dcard__t">${editing ? 'Edit connection' : 'Register a connection'}${icon('', '')}<div class="sub">Passwords are referenced by env var only — never stored on disk</div></div>
      <div class="dcard__sp"></div>
      ${editing ? `<span class="pillg pillg--accent">${icon('edit')} editing ${esc(editing.name)}</span>` : ''}
    </div>
    <form class="oform" onsubmit="return false">
      <div class="oform__grid">
        <div class="field"><label>Name</label><input class="inp" name="name" value="${escAttr(v.name)}" placeholder="analytics-pg"></div>
        <div class="field"><label>Driver</label><select class="sel" name="driver">${driverOpts}</select></div>
      </div>
      <div class="field"><label>JDBC URL</label><input class="inp" name="url" value="${escAttr(v.url)}" placeholder="jdbc:postgresql://host:5432/db"></div>
      <div class="oform__grid oform__grid--3">
        <div class="field"><label>User</label><input class="inp" name="user" value="${escAttr(v.user)}" placeholder="app_ro"></div>
        <div class="field"><label>Password env <span class="opt">— stored</span></label><input class="inp" name="passwordEnv" value="${escAttr(v.passwordEnv || '')}" placeholder="DB_PASSWORD"></div>
        <div class="field"><label>Password <span class="opt">— test only, not saved</span></label><input class="inp" name="password" type="password" placeholder="••••••••" autocomplete="off"></div>
      </div>
      <div class="actions">
        <span class="meta">${icon('shield')} Prefer <b style="color:var(--accent-line)">password env</b> — inline secrets are never persisted</span>
        <div class="actions__sp"></div>
        ${editing ? `<button class="dbtn dbtn--ghost" type="button" id="connCancel">${icon('close')} Cancel</button>` : ''}
        <button class="dbtn dbtn--ghost" type="button" id="connTest">${icon('wifi_tethering')} Test</button>
        <button class="dbtn dbtn--primary" type="button" id="connSave">${icon('save')} ${editing ? 'Update' : 'Save'} connection</button>
      </div>
      <div id="connFormMsg"></div>
    </form>
  </div>`;
}

function connectionsInner() {
  const cs = STATE.connections;
  const reachable = cs.filter(c => c.status === 'reachable').length;
  const failed = cs.filter(c => c.status === 'failed').length;
  const drivers = [...new Set(cs.map(c => c.driver))];

  const kpis = `<div class="kstrip kstrip--4">
    ${kpiTile({ ic: 'database', k: 'Connections', v: cs.length })}
    ${kpiTile({ ic: 'check_circle', k: 'Reachable', v: reachable, unit: '/ ' + cs.length })}
    ${kpiTile({ ic: 'lan', k: 'Drivers', v: drivers.length, sub: drivers.map(driverLabel).join(' · ') || '—' })}
    ${kpiTile({ ic: 'warning', k: 'Unreachable', v: failed, sub: failed ? 'last test failed' : 'none' })}
  </div>`;

  const cards = `<div>
    <p class="sectlabel">Saved connections <span class="ct">· ${cs.length}</span></p>
    <div class="conngrid">
      ${cs.map(connCard).join('')}
      <div class="addcard" id="connAddCard"><div class="addcard__in">${icon('add')}<b>Add connection</b><span class="meta">jdbc url + credentials</span></div></div>
    </div>
  </div>`;

  return kpis + cards + connFormHTML();
}

function connectionsView() {
  return `<div id="connWrap"><div class="meta" style="padding:8px 2px">${icon('sync')} loading connections…</div></div>`;
}

function connectionsTop() {
  return topbar({
    ic: 'database', title: 'Connections',
    sub: 'Saved JDBC sources for exports — passwords by env var only',
    actions: `<button class="dbtn dbtn--primary" type="button" id="connAddBtn">${icon('add')} Add connection</button>`,
  });
}

/* ───────── load + render ───────── */
async function loadConnections() {
  try {
    STATE.connections = await API.connections();
  } catch (e) {
    STATE.connections = [];
  }
  if (CURRENT === 'connections') {
    const wrap = document.getElementById('connWrap');
    if (wrap) wrap.innerHTML = connectionsInner();
  }
  populateConnPicker();
}

function connFormValues() {
  const root = document.getElementById('connForm');
  return {
    name: root.querySelector('[name=name]').value.trim(),
    driver: root.querySelector('[name=driver]').value,
    url: root.querySelector('[name=url]').value.trim(),
    user: root.querySelector('[name=user]').value.trim(),
    passwordEnv: root.querySelector('[name=passwordEnv]').value.trim(),
    password: root.querySelector('[name=password]').value,
  };
}

function connFormMsg(ok, msg) {
  const el = document.getElementById('connFormMsg');
  if (el) el.innerHTML = `<div class="cronhint" style="margin-top:14px;color:${ok ? 'var(--accent-line)' : 'var(--danger)'}">${icon(ok ? 'check_circle' : 'error')} <span>${esc(msg)}</span></div>`;
}

async function saveConnection() {
  const v = connFormValues();
  setBusy('connSave', true);
  try {
    const res = STATE.editingConn
      ? await API.updateConnection(STATE.editingConn, v)
      : await API.createConnection(v);
    if (res.ok) {
      STATE.editingConn = null;
      await loadConnections();
    } else {
      connFormMsg(false, res.data ? res.data.error : 'Save failed (' + res.status + ')');
    }
  } catch (e) {
    connFormMsg(false, String(e));
  } finally {
    setBusy('connSave', false);
  }
}

async function testConnForm() {
  const v = connFormValues();
  setBusy('connTest', true);
  try {
    const res = await API.testAdHoc(v);
    const r = res.data || {};
    connFormMsg(r.ok, r.ok ? ('Reachable · ' + (r.serverInfo || 'connected')) : ('Failed · ' + (r.message || 'unreachable')));
  } catch (e) {
    connFormMsg(false, String(e));
  } finally {
    setBusy('connTest', false);
  }
}

async function handleConnAction(action, id) {
  if (action === 'edit') {
    STATE.editingConn = id;
    const wrap = document.getElementById('connWrap');
    if (wrap) wrap.innerHTML = connectionsInner();
    const form = document.getElementById('connForm');
    if (form) form.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return;
  }
  if (action === 'delete') {
    const res = await API.deleteConnection(id);
    if (res.ok) { if (STATE.editingConn === id) STATE.editingConn = null; await loadConnections(); }
    return;
  }
  if (action === 'test') {
    const res = await API.testConnection(id);
    await loadConnections();
    const r = res.data || {};
    connFormMsg(r.ok, r.ok ? ('Reachable · ' + (r.serverInfo || 'connected')) : ('Failed · ' + (r.message || 'unreachable')));
  }
}

function cancelConnEdit() {
  STATE.editingConn = null;
  const wrap = document.getElementById('connWrap');
  if (wrap) wrap.innerHTML = connectionsInner();
}

function focusConnForm() {
  const form = document.getElementById('connForm');
  if (form) {
    form.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const name = form.querySelector('[name=name]');
    if (name) name.focus();
  }
}
