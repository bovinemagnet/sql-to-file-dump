/* Sluice — Dashboard view, wired to the real daemon (submit + queue/history). */

const JOBS_GRID = 'grid-template-columns:58px minmax(0,1fr) 92px 118px 96px 150px 96px;';

/* ───────── job table row ───────── */
function jobRow(j) {
  const running = j.status === 'running';
  const rows = running
    ? `${fmtInt(j.rowCount)}<span>rows · live</span>`
    : (j.status === 'completed' ? `${fmtInt(j.rowCount)}<span>rows</span>` : `<span style="color:var(--faint)">—</span>`);
  const dur = running
    ? `${fmtClock(j.elapsedMillis / 1000)}<span>elapsed</span>`
    : (j.durationMillis != null ? `${fmtDur(j.durationMillis)}` : `<span style="color:var(--faint)">—</span>`);
  const act = running
    ? `<div class="iact iact--accent" data-view="live" title="Open live run">${icon('open_in_full')}</div>`
    : j.status === 'failed'
      ? `<div class="iact iact--danger" title="${escAttr(j.error || 'Failed')}">${icon('error')}</div>`
      : j.status === 'completed'
        ? `<div class="iact" title="Completed">${icon('check')}</div>`
        : `<div class="iact" title="Queued">${icon('schedule')}</div>`;
  return `<div class="orow ${running ? 'orow--run' : ''}" data-status="${j.status}" ${running ? 'data-view="live"' : ''} style="${JOBS_GRID}">
    <div class="orow__id">#${j.id}</div>
    <div class="orow__out">
      <b>${esc(j.output || '—')}</b>
      <span>${esc(j.driver)} · ${esc(j.user)} · ${j.submittedAt}</span>
    </div>
    <div>${j.format ? formatPill(j.format) : ''}</div>
    <div class="orow__num">${rows}</div>
    <div class="orow__num">${dur}</div>
    <div>${statusPill(j.status)}</div>
    <div class="orow__act">${act}</div>
  </div>`;
}

function jobsTableInner() {
  const jobs = STATE.jobs;
  const head = `<div class="otable__head" style="${JOBS_GRID}">
    <div>Job</div><div>Output · source</div><div>Format</div><div style="text-align:right">Rows</div><div style="text-align:right">Duration</div><div>Status</div><div style="text-align:right">Actions</div>
  </div>`;
  if (!jobs.length) {
    return head + `<div class="orow" style="grid-template-columns:1fr"><div style="color:var(--faint);text-align:center;padding:18px 0">No jobs yet — submit an export to get started.</div></div>`;
  }
  return head + jobs.map(jobRow).join('');
}

/* ───────── KPI strip ───────── */
function dashKpisInner() {
  const m = STATE.metrics;
  if (!m) return '';
  const successDen = m.completed + m.failed;
  const success = successDen ? Math.round(m.completed / successDen * 100) + '%' : '—';
  return `
    ${kpiTile({ ic: 'timer', k: 'Daemon uptime', v: fmtUptime(m.uptimeMillis) })}
    ${kpiTile({ ic: 'bolt', k: 'Jobs total', v: m.jobsTotal, sub: `${m.queued} queued · ${m.running} running` })}
    ${kpiTile({ ic: 'database', k: 'Rows exported', v: fmtCompact(m.rowsTotal) })}
    ${kpiTile({ ic: 'play_circle', k: 'Active runs', v: m.running, sub: 'virtual-thread pool' })}
    ${kpiTile({ ic: 'verified', k: 'Success', v: success, sub: `${m.failed} failed` })}`;
}

/* ───────── running mini-card + up-next ───────── */
function dashRunInner() {
  const run = STATE.jobs.find(j => j.status === 'running');
  const queued = STATE.jobs.filter(j => j.status === 'queued');

  const runCard = run ? `<div class="minirun">
    <div style="display:flex;align-items:center;gap:10px;margin-bottom:16px">
      <span class="st st--run"><i class="dt"></i>Running</span>
      <div class="dcard__sp"></div>
      <span class="orow__id">#${run.id}</span>
    </div>
    <div style="font:700 15px/1.3 'Space Grotesk';letter-spacing:-.01em;margin-bottom:4px">${esc(run.output)}</div>
    <div class="meta" style="margin-bottom:18px">${run.format ? formatPill(run.format) : ''} <span class="dot-sep"></span> ${esc(run.driver)} <span class="dot-sep"></span> ${esc(run.serverInfo || 'connecting…')}</div>
    <div style="display:flex;gap:22px;margin-bottom:16px">
      <div><div class="sl-d" style="margin-bottom:7px">Rows</div><div class="num" style="font:800 24px/1 'JetBrains Mono';letter-spacing:-.02em">${fmtInt(run.rowCount)}</div></div>
      <div><div class="sl-d" style="margin-bottom:7px">Throughput</div><div class="num" style="font:800 24px/1 'JetBrains Mono';letter-spacing:-.02em;color:var(--accent-line)">${fmtRate(run.throughput)}<span style="font-size:13px;color:var(--dim)"> /s</span></div></div>
      <div><div class="sl-d" style="margin-bottom:7px">Elapsed</div><div class="num" style="font:800 24px/1 'JetBrains Mono';letter-spacing:-.02em">${fmtClock(run.elapsedMillis / 1000)}</div></div>
    </div>
    <div class="barwrap" style="margin-bottom:18px">
      <div class="barwrap__top"><span class="barwrap__lab">Streaming</span><span class="barwrap__val">${fmtBytes(run.outputBytes)}</span></div>
      <div class="bar"><div class="bar__fill bar__fill--stripe" style="width:100%"></div></div>
    </div>
    <button class="dbtn dbtn--primary" style="width:100%;justify-content:center" data-view="live">${icon('monitoring')} Open live run</button>
  </div>` : `<div class="minirun" style="box-shadow:inset 0 0 0 1.5px var(--line)">
    <div style="display:flex;flex-direction:column;align-items:center;gap:12px;padding:24px 8px;text-align:center">
      <div class="iact" style="width:48px;height:48px;border-radius:14px">${icon('inbox')}</div>
      <div style="font:700 15px/1.3 'Space Grotesk'">No active export</div>
      <div class="meta">Submit a job to see it stream here.</div>
    </div>
  </div>`;

  const upNext = `<div class="dcard">
    <div class="dcard__h"><div class="dcard__t">Up next${icon('', '')}<div class="sub">${queued.length} job${queued.length === 1 ? '' : 's'} queued</div></div></div>
    <div class="stack-sm">
      ${queued.length ? queued.map(j => `<div class="flowrow" style="background:var(--s2);padding:13px 15px">
        <div class="flowrow__dot" style="background:var(--s3)">${icon('schedule', '')}</div>
        <div class="flowrow__n">${esc(j.output)}<span>${esc(j.driver)} · ${j.submittedAt}</span></div>
        ${j.format ? formatPill(j.format) : ''}
      </div>`).join('') : `<div class="meta" style="padding:6px 2px">Queue is empty.</div>`}
    </div>
  </div>`;

  return runCard + upNext;
}

/* ───────── submit form (static markup, wired in app.js) ───────── */
function dashFormHTML() {
  const fmtSeg = ['csv', 'tsv', 'json', 'ndjson', 'parquet']
    .map(f => `<div class="dseg__i ${f === 'parquet' ? 'dseg__i--on' : ''}" data-seg="format" data-val="${f}">${f}</div>`).join('');
  return `<div class="dcard">
    <div class="dcard__h">
      <div class="dcard__t">New export${icon('chevron_right', '')}<div class="sub">Submit a one-off job to the daemon queue</div></div>
      <div class="dcard__sp"></div>
      <span class="pillg pillg--ghost">${icon('lock')} read-only · SELECT / WITH</span>
    </div>
    <form class="oform" id="dashForm" onsubmit="return false" data-conn-id="">
      <div class="field">
        <label>${icon('database')} Use saved connection <span class="opt">— or enter details below</span></label>
        <select class="sel" id="connPicker"><option value="">— manual entry —</option></select>
      </div>
      <div class="oform__grid">
        <div class="field">
          <label>JDBC URL</label>
          <input class="inp" name="url" placeholder="jdbc:postgresql://host:5432/db">
        </div>
        <div class="field">
          <label>User</label>
          <input class="inp" name="user" placeholder="app_ro">
        </div>
      </div>
      <div class="oform__grid">
        <div class="field">
          <label>Password <span class="opt">— optional</span></label>
          <input class="inp" name="password" type="password" placeholder="••••••••" autocomplete="off">
        </div>
        <div class="field">
          <label>Password env <span class="opt">— preferred</span></label>
          <input class="inp" name="passwordEnv" placeholder="DB_PASSWORD">
        </div>
      </div>
      <div class="field">
        <label>SQL <span class="opt">— SELECT / WITH only · alias every column</span></label>
        <textarea class="ta" name="sql" spellcheck="false" placeholder="select b.booking_id as booking_id, b.attendees as attendees from booking b"></textarea>
      </div>
      <div class="oform__grid oform__grid--3">
        <div class="field">
          <label>Format</label>
          <div class="dseg" style="width:100%;flex-wrap:wrap">${fmtSeg}</div>
        </div>
        <div class="field">
          <label>Parquet codec</label>
          <select class="sel" name="compression"><option>SNAPPY</option><option>ZSTD</option><option>GZIP</option><option>UNCOMPRESSED</option></select>
        </div>
        <div class="field">
          <label>Output path</label>
          <input class="inp" name="output" placeholder="exports/bookings.parquet">
        </div>
      </div>
      <div class="oform__grid oform__grid--3">
        <div class="field" style="grid-column:1 / span 2">
          <label>Transforms <span class="opt">— optional, one per line · e.g. rename:old=new, mask:email, map:s=A&gt;Active</span></label>
          <textarea class="ta" name="transforms" spellcheck="false" placeholder="rename:room_code=room&#10;mask:email"></textarea>
        </div>
        <div class="field">
          <label>On transform error</label>
          <select class="sel" name="errorStrategy"><option value="fail">fail</option><option value="skipRow">skipRow</option><option value="keepOriginal">keepOriginal</option></select>
        </div>
      </div>
      <div class="actions">
        <label class="ocheck"><span class="sw sw--on" id="ovToggle" data-toggle></span> Overwrite if exists</label>
        <div class="actions__sp"></div>
        <button class="dbtn dbtn--ghost" type="button" id="describeBtn">${icon('table_chart')} Describe schema</button>
        <button class="dbtn dbtn--primary" type="button" id="submitBtn">${icon('play_arrow')} Run export</button>
      </div>
      <div id="dashFeedback"></div>
      <div id="dashDetail"></div>
    </form>
  </div>`;
}

function dashboardView() {
  const filters = ['All', 'Running', 'Queued', 'Completed', 'Failed'];
  const chips = filters.map((f, i) => `<span class="dchip ${i === 0 ? 'dchip--on' : ''}" data-filter="${f.toLowerCase()}">${f}</span>`).join('');

  return `<div class="kstrip" id="dashKpis">${dashKpisInner()}</div>` +
    `<div class="grid-2" style="margin-bottom:30px">
       ${dashFormHTML()}
       <div class="stack" id="dashRun">${dashRunInner()}</div>
     </div>` +
    `<div>
       <div class="filtrow">
         <p class="sectlabel" style="margin:0">Job queue &amp; history <span class="ct" id="jobCount">· ${STATE.jobs.length}</span></p>
         <div class="filtrow__sp"></div>
         ${chips}
       </div>
       <div class="otable" id="jobsTable">${jobsTableInner()}</div>
     </div>`;
}

function dashboardTop() {
  return topbar({
    ic: 'dashboard', title: 'Dashboard',
    sub: 'Submit exports, watch the queue, review history',
    actions: `<button class="dbtn dbtn--ghost" type="button" id="refreshBtn">${icon('refresh')} Refresh</button>`,
  });
}

/* ───────── live refresh (poll-driven) ───────── */
function updateDashboard() {
  const k = document.getElementById('dashKpis'); if (k) k.innerHTML = dashKpisInner();
  const r = document.getElementById('dashRun'); if (r) r.innerHTML = dashRunInner();
  const t = document.getElementById('jobsTable'); if (t) { t.innerHTML = jobsTableInner(); applyJobFilter(); }
  const c = document.getElementById('jobCount'); if (c) c.textContent = '· ' + STATE.jobs.length;
}

/* ───────── form wiring (called once per dashboard mount) ───────── */
function wireDashboard() {
  const submitBtn = document.getElementById('submitBtn');
  const describeBtn = document.getElementById('describeBtn');
  if (submitBtn) submitBtn.addEventListener('click', onSubmitExport);
  if (describeBtn) describeBtn.addEventListener('click', onDescribe);
  populateConnPicker();
}

function dashFormValues() {
  const root = document.getElementById('dashForm');
  const fmt = root.querySelector('[data-seg="format"].dseg__i--on');
  const ov = document.getElementById('ovToggle');
  return {
    url: root.querySelector('[name=url]').value.trim(),
    user: root.querySelector('[name=user]').value.trim(),
    password: root.querySelector('[name=password]').value,
    passwordEnv: root.querySelector('[name=passwordEnv]').value.trim(),
    sql: root.querySelector('[name=sql]').value,
    format: fmt ? fmt.dataset.val : 'csv',
    output: root.querySelector('[name=output]').value.trim(),
    compression: root.querySelector('[name=compression]').value,
    transforms: root.querySelector('[name=transforms]').value,
    errorStrategy: root.querySelector('[name=errorStrategy]').value,
    connectionId: root.dataset.connId || '',
    overwrite: ov && ov.classList.contains('sw--on') ? 'true' : 'false',
  };
}

async function onSubmitExport() {
  const fb = document.getElementById('dashFeedback');
  setBusy('submitBtn', true);
  try {
    const res = await API.submit(dashFormValues());
    if (res.ok) {
      fb.innerHTML = feedbackHTML(true, `Job #${res.data.id} submitted → ${esc(res.data.output)}`);
      await refreshData();
    } else {
      fb.innerHTML = feedbackHTML(false, res.data ? res.data.error : 'Submit failed (' + res.status + ')');
    }
  } catch (e) {
    fb.innerHTML = feedbackHTML(false, String(e));
  } finally {
    setBusy('submitBtn', false);
  }
}

async function onDescribe() {
  const detail = document.getElementById('dashDetail');
  const v = dashFormValues();
  setBusy('describeBtn', true);
  try {
    const res = await API.describe({ url: v.url, user: v.user, password: v.password, passwordEnv: v.passwordEnv, sql: v.sql });
    if (res.ok) {
      detail.innerHTML = columnsHTML(res.data);
    } else {
      detail.innerHTML = feedbackHTML(false, res.data ? res.data.error : 'Describe failed (' + res.status + ')');
    }
  } catch (e) {
    detail.innerHTML = feedbackHTML(false, String(e));
  } finally {
    setBusy('describeBtn', false);
  }
}

function feedbackHTML(ok, msg) {
  return `<div class="cronhint" style="margin-top:14px;color:${ok ? 'var(--accent-line)' : 'var(--danger)'}">
    ${icon(ok ? 'check_circle' : 'error')} <span>${esc(msg)}</span></div>`;
}

function columnsHTML(cols) {
  const grid = 'grid-template-columns:46px minmax(0,1fr) 120px 110px 90px;';
  const head = `<div class="otable__head" style="${grid}"><div>#</div><div>Label</div><div>Output name</div><div>JDBC type</div><div>Null</div></div>`;
  const rows = cols.map(c => `<div class="orow" style="${grid}">
    <div class="orow__id">${c.index}</div>
    <div class="orow__out"><b>${esc(c.label)}</b></div>
    <div class="orow__out"><b>${esc(c.outputName)}</b></div>
    <div class="meta">${esc(c.jdbcTypeName)}</div>
    <div class="meta">${c.nullable ? 'yes' : 'no'}</div>
  </div>`).join('');
  return `<div style="margin-top:16px"><p class="sectlabel" style="margin:0 0 12px">Schema <span class="ct">· ${cols.length} columns</span></p>
    <div class="otable">${head}${rows}</div></div>`;
}
