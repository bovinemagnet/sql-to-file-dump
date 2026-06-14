/* Sluice — formatting utils + small shared defaults.
 * All views (Dashboard, Live Run, Schedules, Connections) are wired to the real daemon
 * and keep their live state in app.js / api.js; this file only holds helpers. */

/* ───────── format utils ───────── */
function fmtInt(n) { return Math.round(n).toLocaleString('en-US'); }
function fmtBytes(b) {
  if (b >= 1024 ** 3) return (b / 1024 ** 3).toFixed(2) + ' GB';
  if (b >= 1024 ** 2) return (b / 1024 ** 2).toFixed(1) + ' MB';
  if (b >= 1024) return (b / 1024).toFixed(1) + ' KB';
  return Math.round(b) + ' B';
}
function fmtDur(ms) {
  const s = ms / 1000;
  if (s < 60) return s.toFixed(s < 10 ? 1 : 0) + 's';
  const m = Math.floor(s / 60), r = Math.round(s % 60);
  if (m < 60) return m + 'm ' + String(r).padStart(2, '0') + 's';
  const h = Math.floor(m / 60);
  return h + 'h ' + String(m % 60).padStart(2, '0') + 'm';
}
function fmtClock(sec) {
  sec = Math.max(0, Math.round(sec));
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}
function fmtRate(n) {
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return Math.round(n).toString();
}
function fmtCountdown(sec) {
  sec = Math.max(0, Math.round(sec));
  if (sec >= 86400) { const d = Math.floor(sec / 86400), h = Math.round((sec % 86400) / 3600); return `in ${d}d ${String(h).padStart(2, '0')}h`; }
  if (sec >= 3600) { const h = Math.floor(sec / 3600), m = Math.round((sec % 3600) / 60); return `in ${h}h ${String(m).padStart(2, '0')}m`; }
  if (sec >= 60) { const m = Math.floor(sec / 60), s = sec % 60; return `in ${m}m ${String(s).padStart(2, '0')}s`; }
  return `in ${sec}s`;
}

/* default SQL shown in the schedule form */
const SQL_EVENTS =
`select o.id as order_id,
       o.customer_id as customer_id,
       o.total as total,
       o.created_at as created_at
from orders o
order by o.id`;

/* nav-rail footer values, refreshed live from /api/metrics */
const KPI_DASH = { uptime: '—', workers: 'virtual' };
