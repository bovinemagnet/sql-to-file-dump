/* Sluice — Schedules view, wired to the daemon's schedule store (/api/schedules).
 * Schedules reference a saved connection; the daemon fires them (cron / interval / once). */

const SCH_GRID = 'grid-template-columns:minmax(0,1.4fr) 210px 150px 158px 56px 110px;';

function triggerCell(s) {
  if (s.triggerType === 'cron') {
    return `<div><span class="cron">${esc(s.cron || '')}</span><div class="meta" style="margin-top:7px;font-size:11px">${icon('event_repeat')} ${esc(cronHuman(s.cron || ''))}</div></div>`;
  }
  if (s.triggerType === 'interval') {
    return `<div><span class="trigtype">${icon('autorenew')} interval</span><div class="meta" style="margin-top:7px;font-size:11px">every ${s.every || 1} ${esc(s.unit || 'hour')}</div></div>`;
  }
  return `<div><span class="trigtype">${icon('alarm')} one-off</span><div class="meta" style="margin-top:7px;font-size:11px">at ${esc(s.at || '')}</div></div>`;
}

function nextRunCell(s) {
  if (!s.enabled) return `<div class="nextrun" style="color:var(--faint)">paused<span>not scheduled</span></div>`;
  if (s.nextRunEpochMs == null) return `<div class="nextrun" style="color:var(--faint)">—<span>no upcoming run</span></div>`;
  const sec = (s.nextRunEpochMs - Date.now()) / 1000;
  return `<div class="nextrun">${fmtCountdown(sec).replace('in ', '')}<span>next fire</span></div>`;
}

function lastRunCell(s) {
  if (!s.lastStatus) return `<div class="orow__num"><span style="color:var(--faint)">never run</span></div>`;
  const ago = s.lastRunEpochMs ? schedAgo(s.lastRunEpochMs) : '';
  return `<div>${statusPill(s.lastStatus)}<div class="meta" style="margin-top:7px;font-size:11px">${ago}</div></div>`;
}

function schedAgo(ms) {
  const sec = Math.max(0, Math.floor((Date.now() - ms) / 1000));
  if (sec < 60) return 'just now';
  if (sec < 3600) return Math.floor(sec / 60) + 'm ago';
  if (sec < 86400) return Math.floor(sec / 3600) + 'h ago';
  return Math.floor(sec / 86400) + 'd ago';
}

function schedRowActions(s) {
  const pd = STATE.pendingDelete;
  if (pd && pd.kind === 'schedule' && pd.id === s.id && Date.now() < pd.until) {
    return `<div class="orow__act confirmdel">
      <span class="confirmdel__lab">delete?</span>
      <div class="iact iact--danger" title="Confirm delete" data-sched-action="confirm-delete" data-id="${escAttr(s.id)}">${icon('check')}</div>
      <div class="iact" title="Keep" data-sched-action="cancel-delete" data-id="${escAttr(s.id)}">${icon('close')}</div>
    </div>`;
  }
  return `<div class="orow__act">
      <div class="iact iact--accent" title="Run now" data-sched-action="run" data-id="${escAttr(s.id)}">${icon('play_arrow')}</div>
      <div class="iact" title="Edit" data-sched-action="edit" data-id="${escAttr(s.id)}">${icon('edit')}</div>
      <div class="iact iact--danger" title="Delete" data-sched-action="delete" data-id="${escAttr(s.id)}">${icon('delete')}</div>
    </div>`;
}

function schedRow(s) {
  return `<div class="orow" style="${SCH_GRID}">
    <div class="orow__out">
      <b>${esc(s.name)}</b>
      <span>${esc(connName(s.connectionId))} · ${esc(s.format)} · ${esc(s.outputPattern)}</span>
    </div>
    ${triggerCell(s)}
    ${nextRunCell(s)}
    ${lastRunCell(s)}
    <div><span class="sw ${s.enabled ? 'sw--on' : ''}" data-sched-action="toggle" data-id="${escAttr(s.id)}" title="${s.enabled ? 'Disable' : 'Enable'}"></span></div>
    ${schedRowActions(s)}
  </div>`;
}

function schedFormHTML() {
  const editing = STATE.editingSchedule ? STATE.schedules.find(s => s.id === STATE.editingSchedule) : null;
  const v = editing || { name: '', connectionId: '', sql: SQL_EVENTS, format: 'parquet', outputPattern: 'exports/orders_{date}.parquet', triggerType: 'cron', cron: '0 2 * * *', every: 1, unit: 'hour', at: '2026-06-15 06:00', enabled: true, overwrite: true };
  const conns = STATE.connections || [];
  /* the schedule's saved connection may have been deleted — never silently
   * rebind to the first option; force an explicit choice instead */
  const connMissing = !!(v.connectionId && !conns.some(c => c.id === v.connectionId));
  const missingOpt = connMissing
    ? `<option value="" selected disabled>connection missing (was ${escAttr(v.connectionId)}) — choose a connection</option>`
    : '';
  const connOpts = missingOpt + conns.map(c => `<option value="${escAttr(c.id)}" ${(!connMissing && c.id === v.connectionId) ? 'selected' : ''}>${esc(c.name)} — ${esc(c.url)}</option>`).join('');
  const fmtSeg = ['csv', 'tsv', 'json', 'ndjson', 'parquet']
    .map(f => `<div class="dseg__i ${f === v.format ? 'dseg__i--on' : ''}" data-seg="format" data-val="${f}">${f}</div>`).join('');
  const tt = v.triggerType || 'cron';
  const trigSeg = ['cron', 'interval', 'once'].map(t =>
    `<div class="dseg__i ${t === tt ? 'dseg__i--on' : ''}" data-seg="trigger" data-val="${t}" style="flex:1;text-align:center">${t === 'once' ? 'Once' : t.charAt(0).toUpperCase() + t.slice(1)}</div>`).join('');

  const connField = (conns.length || connMissing)
    ? `<select class="sel" name="connectionId">${connOpts}</select>` +
      (connMissing ? `<div class="cronhint" style="margin-top:9px;color:var(--warn)">${icon('warning')} <span>The saved connection this schedule used no longer exists — choose a replacement before saving.</span></div>` : '')
    : `<div class="cronhint" style="color:var(--warn)">${icon('warning')} <span>No saved connections yet — add one on the Connections tab first.</span></div>`;

  return `<div class="dcard" id="schedForm">
    <div class="dcard__h">
      <div class="dcard__t">${editing ? 'Edit schedule' : 'New schedule'}${icon('', '')}<div class="sub">Recurring or one-off export, fired by the daemon</div></div>
      <div class="dcard__sp"></div>
      ${editing ? `<span class="pillg pillg--accent">${icon('edit')} editing</span>` : `<span class="pillg pillg--ghost">${icon('history')} saved to schedule.json</span>`}
    </div>
    <form class="oform" onsubmit="return false">
      <div class="oform__grid">
        <div class="field"><label>Schedule name</label><input class="inp" name="name" value="${escAttr(v.name)}" placeholder="Nightly orders export"></div>
        <div class="field"><label>${icon('database')} Connection</label>${connField}</div>
      </div>
      <div class="field"><label>SQL <span class="opt">— SELECT / WITH</span></label><textarea class="ta" name="sql" style="min-height:96px" spellcheck="false">${esc(v.sql)}</textarea></div>
      <div class="oform__grid">
        <div class="field"><label>Format</label><div class="dseg" style="width:100%;flex-wrap:wrap">${fmtSeg}</div></div>
        <div class="field"><label>Output pattern <span class="opt">— {date} {time} {datetime} {ts}</span></label><input class="inp" name="outputPattern" value="${escAttr(v.outputPattern)}"></div>
      </div>

      <div class="field"><label>${icon('schedule')} Trigger</label><div class="dseg" style="width:100%">${trigSeg}</div></div>

      <div class="trigblock" data-trig="cron" ${tt === 'cron' ? '' : 'hidden'}>
        <div class="field"><label>Cron expression <span class="opt">— 5 fields, daemon time zone</span></label><input class="inp" id="cronInput" name="cron" value="${escAttr(v.cron || '0 2 * * *')}"></div>
        <div class="cronhint" style="margin-top:12px">${icon('check_circle')} <span id="cronHint">${esc(cronHuman(v.cron || '0 2 * * *'))}</span></div>
      </div>
      <div class="trigblock" data-trig="interval" ${tt === 'interval' ? '' : 'hidden'}>
        <div class="oform__grid--3 oform__grid">
          <div class="field"><label>Every</label><input class="inp" name="every" value="${escAttr(String(v.every || 1))}"></div>
          <div class="field"><label>Unit</label><select class="sel" name="unit">
            <option value="minute" ${v.unit === 'minute' ? 'selected' : ''}>minute</option>
            <option value="hour" ${(!v.unit || v.unit === 'hour') ? 'selected' : ''}>hour</option>
            <option value="day" ${v.unit === 'day' ? 'selected' : ''}>day</option>
          </select></div>
          <div class="field"><label>&nbsp;</label><div class="cronhint" style="padding-top:12px">${icon('autorenew')} repeats</div></div>
        </div>
      </div>
      <div class="trigblock" data-trig="once" ${tt === 'once' ? '' : 'hidden'}>
        <div class="field"><label>Run at <span class="opt">— yyyy-MM-dd HH:mm</span></label><input class="inp" name="at" value="${escAttr(v.at || '')}"></div>
      </div>

      <div class="actions">
        <label class="ocheck"><span class="sw ${v.enabled ? 'sw--on' : ''}" id="schedEnabled" data-toggle></span> Enabled</label>
        <label class="ocheck"><span class="sw ${v.overwrite ? 'sw--on' : ''}" id="schedOverwrite" data-toggle></span> Overwrite each run</label>
        <div class="actions__sp"></div>
        ${editing ? `<button class="dbtn dbtn--ghost" type="button" id="schedCancel">${icon('close')} Cancel</button>` : ''}
        <button class="dbtn dbtn--primary" type="button" id="schedSave">${icon('event_repeat')} ${editing ? 'Update' : 'Create'} schedule</button>
      </div>
      <div id="schedFormMsg"></div>
    </form>
  </div>`;
}

function schedKpisHTML() {
  const list = STATE.schedules || [];
  const enabled = list.filter(s => s.enabled);
  const failed = list.filter(s => s.lastStatus === 'failed').length;
  const upcoming = enabled.filter(s => s.nextRunEpochMs != null).sort((a, b) => a.nextRunEpochMs - b.nextRunEpochMs);
  const nextLabel = upcoming.length ? fmtCountdown((upcoming[0].nextRunEpochMs - Date.now()) / 1000).replace('in ', '') : '—';

  return `<div class="kstrip kstrip--4" id="schedKpis">
    ${kpiTile({ ic: 'event_available', k: 'Active schedules', v: enabled.length, unit: '/ ' + list.length })}
    ${kpiTile({ ic: 'timer', k: 'Next run', v: nextLabel, sub: upcoming.length ? upcoming[0].name : 'none scheduled' })}
    ${kpiTile({ ic: 'event_repeat', k: 'Total schedules', v: list.length })}
    ${kpiTile({ ic: 'error', k: 'Last-run failures', v: failed, sub: failed ? 'check the table' : 'none' })}
  </div>`;
}

function schedTimelineHTML() {
  const list = STATE.schedules || [];
  const upcoming = list.filter(s => s.enabled && s.nextRunEpochMs != null).sort((a, b) => a.nextRunEpochMs - b.nextRunEpochMs);
  return `<div class="dcard" id="schedTimeline">
    <div class="dcard__h"><div class="dcard__t">Upcoming runs${icon('', '')}<div class="sub">${upcoming.length} scheduled</div></div></div>
    <div class="tl">
      ${upcoming.length ? upcoming.slice(0, 6).map(s => `<div class="tl__i">
        <div class="tl__time">${fmtCountdown((s.nextRunEpochMs - Date.now()) / 1000).replace('in ', '')}<span>${esc(s.triggerType)}</span></div>
        <div class="tl__n"><b>${esc(s.name)}</b><span>${esc(connName(s.connectionId))} · ${esc(s.format)}</span></div>
        ${formatPill(s.format)}
      </div>`).join('') : `<div class="meta" style="padding:6px 2px">No enabled schedules with an upcoming run.</div>`}
    </div>
  </div>`;
}

function schedTableHTML() {
  const list = STATE.schedules || [];
  return `<div id="schedTable">
    <p class="sectlabel">All schedules <span class="ct">· ${list.length}</span></p>
    <div class="otable">
      <div class="otable__head" style="${SCH_GRID}">
        <div>Schedule</div><div>Trigger</div><div>Next run</div><div>Last run</div><div>On</div><div style="text-align:right">Actions</div>
      </div>
      ${list.length ? list.map(schedRow).join('') : `<div class="orow" style="grid-template-columns:1fr"><div style="color:var(--faint);text-align:center;padding:18px 0">No schedules yet — create one below.</div></div>`}
    </div>
  </div>`;
}

function schedulesInner() {
  return schedKpisHTML() +
    `<div class="grid-2" style="margin-bottom:30px">${schedFormHTML()}${schedTimelineHTML()}</div>` +
    schedTableHTML();
}

/* poll-driven partial refresh — countdowns, pills and 'ago' labels stay live
 * without touching the form (which the operator may be editing) */
function updateSchedulesLive() {
  const k = document.getElementById('schedKpis'); if (k) k.outerHTML = schedKpisHTML();
  const tl = document.getElementById('schedTimeline'); if (tl) tl.outerHTML = schedTimelineHTML();
  const tb = document.getElementById('schedTable'); if (tb) tb.outerHTML = schedTableHTML();
}

async function pollSchedules() {
  try {
    STATE.schedules = await API.schedules();
  } catch (e) { /* keep last known data; the header badge signals offline */ }
  if (CURRENT === 'schedules') updateSchedulesLive();
}

function schedulesView() {
  return `<div id="schedWrap"><div class="meta" style="padding:8px 2px">${icon('sync')} loading schedules…</div></div>`;
}

function schedulesTop() {
  return topbar({
    ic: 'schedule', title: 'Schedules',
    sub: 'Recurring and one-off exports · cron, interval and one-off triggers',
    actions: `<button class="dbtn dbtn--primary" type="button" id="schedAddBtn">${icon('add')} New schedule</button>`,
  });
}

/* ───────── load + render ───────── */
async function loadSchedules() {
  try {
    if (!STATE.connections || !STATE.connections.length) {
      STATE.connections = await API.connections();
    }
    STATE.schedules = await API.schedules();
  } catch (e) {
    STATE.schedules = STATE.schedules || [];
  }
  if (CURRENT === 'schedules') {
    const wrap = document.getElementById('schedWrap');
    if (wrap) wrap.innerHTML = schedulesInner();
  }
}

function schedFormValues() {
  const root = document.getElementById('schedForm');
  const fmt = root.querySelector('[data-seg="format"].dseg__i--on');
  const trig = root.querySelector('[data-seg="trigger"].dseg__i--on');
  const connSel = root.querySelector('[name=connectionId]');
  const en = document.getElementById('schedEnabled');
  const ov = document.getElementById('schedOverwrite');
  return {
    name: root.querySelector('[name=name]').value.trim(),
    connectionId: connSel ? connSel.value : '',
    sql: root.querySelector('[name=sql]').value,
    format: fmt ? fmt.dataset.val : 'csv',
    outputPattern: root.querySelector('[name=outputPattern]').value.trim(),
    triggerType: trig ? trig.dataset.val : 'cron',
    cron: root.querySelector('[name=cron]').value.trim(),
    every: root.querySelector('[name=every]').value.trim(),
    unit: root.querySelector('[name=unit]').value,
    at: root.querySelector('[name=at]').value.trim(),
    enabled: en && en.classList.contains('sw--on') ? 'true' : 'false',
    overwrite: ov && ov.classList.contains('sw--on') ? 'true' : 'false',
  };
}

function schedFormMsg(ok, msg) {
  const el = document.getElementById('schedFormMsg');
  if (el) el.innerHTML = `<div class="cronhint" style="margin-top:14px;color:${ok ? 'var(--accent-line)' : 'var(--danger)'}">${icon(ok ? 'check_circle' : 'error')} <span>${esc(msg)}</span></div>`;
}

async function saveSchedule() {
  const v = schedFormValues();
  const connSel = document.querySelector('#schedForm [name=connectionId]');
  if (connSel && !v.connectionId) {
    schedFormMsg(false, 'Choose a connection — the one this schedule was linked to no longer exists.');
    return;
  }
  setBusy('schedSave', true);
  try {
    const res = STATE.editingSchedule
      ? await API.updateSchedule(STATE.editingSchedule, v)
      : await API.createSchedule(v);
    if (res.ok) {
      STATE.editingSchedule = null;
      await loadSchedules();
    } else {
      schedFormMsg(false, res.data ? res.data.error : 'Save failed (' + res.status + ')');
    }
  } catch (e) {
    schedFormMsg(false, String(e));
  } finally {
    setBusy('schedSave', false);
  }
}

async function handleSchedAction(action, id) {
  if (action === 'edit') {
    STATE.editingSchedule = id;
    const wrap = document.getElementById('schedWrap');
    if (wrap) wrap.innerHTML = schedulesInner();
    const form = document.getElementById('schedForm');
    if (form) form.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return;
  }
  if (action === 'delete') {
    /* two-step inline confirm — never delete on a single (possibly stray) click */
    STATE.pendingDelete = { kind: 'schedule', id, until: Date.now() + 6000 };
    updateSchedulesLive();
    return;
  }
  if (action === 'cancel-delete') {
    STATE.pendingDelete = null;
    updateSchedulesLive();
    return;
  }
  if (action === 'confirm-delete') {
    STATE.pendingDelete = null;
    try {
      const res = await API.deleteSchedule(id);
      if (res.ok) {
        if (STATE.editingSchedule === id) STATE.editingSchedule = null;
        await loadSchedules();
      } else {
        toast('Delete failed · ' + (res.data && res.data.error ? res.data.error : 'HTTP ' + res.status), false);
        updateSchedulesLive();
      }
    } catch (e) {
      toast('Delete failed · ' + e, false);
      updateSchedulesLive();
    }
    return;
  }
  if (action === 'toggle') {
    await API.toggleSchedule(id);
    await loadSchedules();
    return;
  }
  if (action === 'run') {
    const res = await API.runSchedule(id);
    await loadSchedules();
    const r = res.data || {};
    schedFormMsg(res.ok, res.ok ? ('Run started · job #' + r.jobId) : (r.error || 'Run failed'));
  }
}

function cancelSchedEdit() {
  STATE.editingSchedule = null;
  const wrap = document.getElementById('schedWrap');
  if (wrap) wrap.innerHTML = schedulesInner();
}

function focusSchedForm() {
  const form = document.getElementById('schedForm');
  if (form) {
    form.scrollIntoView({ behavior: 'smooth', block: 'center' });
    const name = form.querySelector('[name=name]');
    if (name) name.focus();
  }
}
