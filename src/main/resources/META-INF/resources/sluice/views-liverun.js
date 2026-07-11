/* Sluice — Live Run view, wired to the real daemon's running job + JVM metrics. */

function liverunView() {
  const run = STATE.jobs.find(j => j.status === 'running');
  if (!run) return liverunEmpty();

  const series = STATE.series[run.id] || [];
  const log = STATE.log[run.id] || [];
  const m = STATE.metrics;
  const batchesDone = run.fetchSize ? Math.floor(run.rowCount / run.fetchSize) : 0;
  const peak = series.length ? Math.max(...series) * 64000 : run.throughput;
  const avg = series.length ? series.reduce((a, b) => a + b, 0) / series.length * 64000 : run.throughput;

  const header = `<div class="dcard" style="margin-bottom:22px">
    <div style="display:flex;align-items:center;gap:18px;flex-wrap:wrap">
      <span class="st st--run"><i class="dt"></i>Running</span>
      <span class="orow__id" style="font-size:15px">#${run.id}</span>
      <div style="min-width:0;flex:1">
        <div style="font:700 18px/1.2 'Space Grotesk';letter-spacing:-.01em">${esc(run.output)}</div>
        <div class="meta" style="margin-top:8px">
          ${run.format ? formatPill(run.format) : ''} <span class="dot-sep"></span>
          ${driverBadge(run.driver)} <span class="dot-sep"></span>
          ${esc(run.serverInfo || 'connecting…')} <span class="dot-sep"></span> codec ${esc(run.compression)}
        </div>
      </div>
      <span class="meta">${icon('schedule')} auto-refresh <b style="color:var(--accent-line)">1.5s</b></span>
    </div>
  </div>`;

  const kpis = `<div class="kstrip" style="margin-bottom:22px">
    ${kpiTile({ ic: 'database', k: 'Rows streamed', v: fmtInt(run.rowCount) })}
    ${kpiTile({ ic: 'speed', k: 'Throughput', v: fmtRate(run.throughput), unit: 'rows/s' })}
    ${kpiTile({ ic: 'timer', k: 'Elapsed', v: fmtClock(run.elapsedMillis / 1000) })}
    ${kpiTile({ ic: 'save', k: 'Output size', v: fmtBytes(run.outputBytes) })}
    ${kpiTile({ ic: 'view_column', k: 'Columns', v: run.columnCount || '—' })}
  </div>`;

  const chartCard = `<div class="dcard">
    <div class="dcard__h">
      <div class="dcard__t">Throughput${icon('', '')}<div class="sub">rows / second · live</div></div>
      <div class="dcard__sp"></div>
      <div class="lvhero lvhero--accent"><b>${fmtRate(run.throughput)}</b><i>rows/s</i></div>
    </div>
    <div>${areaChart('lvgrad', series.length ? series : [0.2, 0.2], { ph: 230, stroke: 'var(--accent)' })}</div>
    <div style="display:flex;gap:28px;margin-top:16px">
      <div><span class="sl-d">Peak</span> <span class="num" style="font:700 15px/1 'JetBrains Mono';margin-left:8px">${fmtRate(peak)}/s</span></div>
      <div><span class="sl-d">Avg</span> <span class="num" style="font:700 15px/1 'JetBrains Mono';margin-left:8px">${fmtRate(avg)}/s</span></div>
      <div><span class="sl-d">Samples</span> <span class="num" style="font:700 15px/1 'JetBrains Mono';margin-left:8px">${series.length}</span></div>
    </div>
  </div>`;

  const fetchCard = `<div class="dcard">
    <div class="dcard__h">
      <div class="dcard__t">Fetch-batch progress${icon('', '')}<div class="sub">JDBC cursor · fetch-size ${run.fetchSize}</div></div>
      <div class="dcard__sp"></div>
      <span class="pillg pillg--accent">${icon('sync')} cursor mode</span>
    </div>
    <div class="barwrap" style="margin-bottom:18px">
      <div class="barwrap__top">
        <span class="barwrap__val">${fmtInt(batchesDone)} <em>batches flushed</em></span>
        <span class="barwrap__val">streaming</span>
      </div>
      <div class="bar bar--lg"><div class="bar__fill bar__fill--stripe" style="width:100%"></div></div>
    </div>
    <div class="sl-d" style="margin-bottom:10px">Recent throughput</div>
    <div>${miniBars(series.slice(-18).length ? series.slice(-18) : [0.4], { ph: 56, color: 'var(--accent)' })}</div>
  </div>`;

  const heapFrac = m && m.heapMaxBytes > 0 ? m.heapUsedBytes / m.heapMaxBytes : 0;
  const heapUsedGb = m ? (m.heapUsedBytes / 1024 ** 3) : 0;
  const heapMaxGb = m && m.heapMaxBytes > 0 ? (m.heapMaxBytes / 1024 ** 3) : 0;
  const heapCard = `<div class="dcard">
    <div class="dcard__h"><div class="dcard__t">Daemon heap${icon('', '')}<div class="sub">JVM · streaming, not buffered</div></div></div>
    <div class="gauge">
      <div class="gauge__c" style="width:132px;height:132px">
        <span>${ringGauge(heapFrac, { size: 132, sw: 12, color: 'var(--z-warm)' })}</span>
        <div class="lab"><b>${heapUsedGb.toFixed(2)}</b><span>GB used</span></div>
      </div>
      <div class="gauge__legend">
        <div class="railstat" style="padding:6px 0;border:0"><span class="k">Max heap</span><span class="v">${heapMaxGb ? heapMaxGb.toFixed(1) + ' GB' : '—'}</span></div>
        <div class="railstat" style="padding:6px 0;border:0"><span class="k">Active runs</span><span class="v" style="color:var(--accent-line)">${m ? m.running : '—'}</span></div>
        <div class="railstat" style="padding:6px 0;border:0"><span class="k">Workers</span><span class="v">virtual</span></div>
      </div>
    </div>
  </div>`;

  const stateCard = `<div class="dcard">
    <div class="dcard__h"><div class="dcard__t">Connection &amp; query state${icon('', '')}<div class="sub">single read-only connection</div></div></div>
    <div class="kv">
      <div class="kv__row"><span class="kv__k">${icon('cable')} Connection</span><span class="kv__v ok">established</span></div>
      <div class="kv__row"><span class="kv__k">${icon('dns')} Server</span><span class="kv__v">${esc(run.serverInfo || '—')}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('lan')} Driver</span><span class="kv__v">${esc(run.driver)}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('sync')} Cursor</span><span class="kv__v ok">forward-only · read-only · fetch ${run.fetchSize}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('view_column')} Columns</span><span class="kv__v">${run.columnCount || '—'}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('person')} User</span><span class="kv__v">${esc(run.user)}</span></div>
    </div>
  </div>`;

  const logCard = `<div class="dcard">
    <div class="dcard__h">
      <div class="dcard__t">Live log${icon('', '')}<div class="sub">daemon stream</div></div>
      <div class="dcard__sp"></div>
      <span class="meta">${icon('terminal')} tail -f</span>
    </div>
    <div class="logs">${log.slice().reverse().map(logLine).join('') || `<div class="meta">waiting for batches…</div>`}</div>
  </div>`;

  const sqlCard = `<div class="dcard">
    <div class="dcard__h">
      <div class="dcard__t">Query &amp; writer${icon('', '')}<div class="sub">the export contract</div></div>
      <div class="dcard__sp"></div>
      ${run.format ? formatPill(run.format) : ''}
    </div>
    ${sqlBlock(run.sql || '')}
    <div class="kv" style="margin-top:14px">
      <div class="kv__row"><span class="kv__k">${icon('description')} Output</span><span class="kv__v">${esc(run.output)}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('compress')} Compression</span><span class="kv__v">${esc(run.compression)}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('layers')} Writer</span><span class="kv__v">${esc(run.format)} · streaming</span></div>
    </div>
  </div>`;

  return header + kpis +
    `<div class="grid-2" style="margin-bottom:22px">
      <div class="stack">${chartCard}${fetchCard}</div>
      <div class="stack">${heapCard}${stateCard}</div>
    </div>` +
    `<div class="grid-2-even">${logCard}${sqlCard}</div>`;
}

function liverunEmpty() {
  const recent = STATE.jobs.find(j => j.status === 'completed' || j.status === 'failed');
  const recentCard = recent ? `<div class="dcard" style="margin-top:22px">
    <div class="dcard__h"><div class="dcard__t">Most recent run${icon('', '')}<div class="sub">#${recent.id}</div></div><div class="dcard__sp"></div>${statusPill(recent.status)}</div>
    <div class="kv">
      <div class="kv__row"><span class="kv__k">${icon('description')} Output</span><span class="kv__v">${esc(recent.output)}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('database')} Rows</span><span class="kv__v">${fmtInt(recent.rowCount)}</span></div>
      <div class="kv__row"><span class="kv__k">${icon('timer')} Duration</span><span class="kv__v">${recent.durationMillis != null ? fmtDur(recent.durationMillis) : '—'}</span></div>
      ${recent.error ? `<div class="kv__row"><span class="kv__k">${icon('error')} Error</span><span class="kv__v warn">${esc(recent.error)}</span></div>` : ''}
    </div>
  </div>` : '';
  return `<div class="dcard">
    <div style="display:flex;flex-direction:column;align-items:center;gap:14px;padding:48px 20px;text-align:center">
      <div class="iact" style="width:56px;height:56px;border-radius:16px">${icon('monitoring')}</div>
      <div style="font:700 18px/1.2 'Space Grotesk'">No export is running</div>
      <div class="meta">Submit a job from the Dashboard and its live metrics will appear here.</div>
      <button class="dbtn dbtn--primary" type="button" data-view="dashboard">${icon('add')} New export</button>
    </div>
  </div>` + recentCard;
}

function liverunTop() {
  const run = STATE.jobs.find(j => j.status === 'running');
  return topbar({
    ic: 'monitoring', title: 'Live Run',
    sub: run ? `Job #${run.id} · streaming to ${esc(run.format)} · ${fmtClock(run.elapsedMillis / 1000)} elapsed` : 'No active export',
    actions: `<span class="meta">${icon('schedule')} auto-refresh <b style="color:var(--accent-line)">1.5s</b></span>`,
  });
}
