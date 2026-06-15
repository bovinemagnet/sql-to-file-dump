/* Sluice — Transformations view. Shows the outbound transform pipeline applied to each export,
 * with per-step timings, row counts, drops and errors. Renders only metrics (never row values);
 * all DB-derived strings go through esc() to avoid HTML injection. Backed by /api/transformations. */

const TX_GRID = 'grid-template-columns:48px 1.4fr 0.9fr 90px 80px 110px 110px;';

function transformsTop() {
  return topbar({
    ic: 'transform', title: 'Transformations',
    sub: 'Outbound transform pipelines applied per export, with per-step timings',
    actions: `<button class="dbtn dbtn--ghost" type="button" id="refreshBtn">${icon('refresh')} Refresh</button>`,
  });
}

function transformsView() {
  const items = STATE.transformations || [];
  if (!items.length) {
    return `<div class="dcard">
      <div style="text-align:center;padding:40px 20px;color:var(--dim)">
        ${icon('transform')}
        <p style="margin:.6em 0 .2em;font-weight:600;color:var(--ink)">No transformed exports yet</p>
        <p style="margin:0">Submit an export with transforms on the Dashboard. Exports without transforms
        run as a fast pass-through and are not listed here.</p>
      </div>
    </div>`;
  }
  return `<div class="stack">${items.map(txCard).join('')}</div>`;
}

function txCard(t) {
  const total = t.totalDurationMs == null ? '—' : t.totalDurationMs + ' ms';
  const head = `<div class="otable__head" style="${TX_GRID}">
    <div>#</div><div>Transform</div><div>Type</div>
    <div style="text-align:right">Rows</div><div style="text-align:right">Errors</div>
    <div style="text-align:right">Duration</div><div style="text-align:right">Avg / row</div>
  </div>`;
  const rows = (t.steps || []).map((s, i) => `<div class="orow" style="${TX_GRID}">
    <div class="orow__id">${i + 1}</div>
    <div class="orow__out"><b>${esc(s.name)}</b>${s.slow ? ` <span class="pillg" style="color:#ff8c42">slow</span>` : ''}</div>
    <div>${esc(s.type)}</div>
    <div class="orow__num">${fmtInt(s.rows)}</div>
    <div class="orow__num" style="${s.errors > 0 ? 'color:#ff5f57' : ''}">${fmtInt(s.errors)}</div>
    <div class="orow__num">${fmtInt(s.durationMs)} ms</div>
    <div class="orow__num">${esc(s.avgPerRowMs)} ms</div>
  </div>`).join('');

  return `<div class="dcard">
    <div class="dcard__h">
      <div class="dcard__t">Job #${esc(t.jobId)}<div class="sub">${esc(t.output || '')}</div></div>
      <div class="dcard__sp"></div>
      <span class="pillg pillg--ghost">${esc(t.status)}</span>
      <span class="pillg pillg--ghost">${icon('rule')} on error: ${esc(t.errorStrategy)}</span>
    </div>
    <div class="kstrip" style="margin:12px 0">
      ${kpiTile({ ic: 'input', k: 'Rows in', v: fmtInt(t.rowsIn) })}
      ${kpiTile({ ic: 'output', k: 'Rows out', v: fmtInt(t.rowsOut) })}
      ${kpiTile({ ic: 'filter_alt', k: 'Dropped', v: fmtInt(t.rowsDropped) })}
      ${kpiTile({ ic: 'timer', k: 'Pipeline', v: total })}
      ${kpiTile({ ic: 'schedule', k: 'Last run', v: esc(t.submittedAt || '—') })}
    </div>
    <div class="otable">${head}${rows || ''}</div>
  </div>`;
}

async function loadTransformations() {
  try {
    STATE.transformations = await API.transformations();
  } catch (e) {
    return;
  }
  if (CURRENT === 'transforms') {
    const body = document.getElementById('bodyslot');
    if (body) body.innerHTML = transformsView();
  }
}
