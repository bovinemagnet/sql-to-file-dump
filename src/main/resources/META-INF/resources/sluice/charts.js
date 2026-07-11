/* Sluice — chart helpers. SVG strings, no deps. Adapted to the Kinetic Green tokens. */

function _smooth(pts, h, w) {
  const step = w / (pts.length - 1);
  let d = `M0,${(h - pts[0] * h).toFixed(1)}`;
  for (let i = 1; i < pts.length; i++) {
    const x = i * step, y = h - pts[i] * h, px = (i - 1) * step, py = h - pts[i - 1] * h, cx = (px + x) / 2;
    d += ` C${cx.toFixed(1)},${py.toFixed(1)} ${cx.toFixed(1)},${y.toFixed(1)} ${x.toFixed(1)},${y.toFixed(1)}`;
  }
  return { line: d, fill: d + ` L${w},${h} L0,${h} Z`, end: { x: w, y: h - pts[pts.length - 1] * h } };
}

/* map raw samples (e.g. rows/s) into 0..1 against the series max for the path
 * helpers; the floor is purely visual so a flat or slow series still draws */
function normaliseSeries(pts, floor) {
  const f = floor == null ? 0.02 : floor;
  const mx = Math.max(...pts);
  if (!(mx > 0)) return pts.map(() => f);
  return pts.map(v => Math.max(f, Math.min(1, v / mx)));
}

/* throughput-over-time area chart with gradient glow fill + live end dot */
function areaChart(id, pts, opts) {
  const o = Object.assign({ w: 720, h: 220, stroke: 'var(--accent)', ph: 220, grid: true }, opts || {});
  const a = _smooth(pts, o.h, o.w);
  const lines = o.grid
    ? [0.25, 0.5, 0.75].map(g => `<line x1="0" y1="${o.h * g}" x2="${o.w}" y2="${o.h * g}" stroke="var(--line)" stroke-width="1"/>`).join('')
    : '';
  return `<svg viewBox="0 0 ${o.w} ${o.h}" preserveAspectRatio="none" style="width:100%;height:${o.ph}px;display:block">
    <defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="${o.stroke}" stop-opacity="0.26"/>
      <stop offset="100%" stop-color="${o.stroke}" stop-opacity="0"/></linearGradient></defs>
    ${lines}
    <path d="${a.fill}" fill="url(#${id})"/>
    <path d="${a.line}" fill="none" stroke="${o.stroke}" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    <circle cx="${a.end.x.toFixed(1)}" cy="${a.end.y.toFixed(1)}" r="4.5" fill="${o.stroke}"/>
    <circle cx="${a.end.x.toFixed(1)}" cy="${a.end.y.toFixed(1)}" r="9" fill="none" stroke="${o.stroke}" stroke-width="2" opacity=".4"/>
  </svg>`;
}

function sparkline(pts, opts) {
  const o = Object.assign({ w: 160, h: 44, stroke: 'var(--accent)' }, opts || {});
  const a = _smooth(pts, o.h, o.w);
  return `<svg viewBox="0 0 ${o.w} ${o.h}" preserveAspectRatio="none" style="width:100%;height:${o.h}px;display:block">
    <path d="${a.line}" fill="none" stroke="${o.stroke}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>
    <circle cx="${a.end.x.toFixed(1)}" cy="${a.end.y.toFixed(1)}" r="3" fill="${o.stroke}"/></svg>`;
}

/* ring gauge — frac 0..1 */
function ringGauge(frac, opts) {
  const o = Object.assign({ size: 132, sw: 12, color: 'var(--accent)', track: 'var(--s3)' }, opts || {});
  const r = o.size / 2 - o.sw / 2 - 1, c = 2 * Math.PI * r;
  return `<svg viewBox="0 0 ${o.size} ${o.size}" style="width:${o.size}px;height:${o.size}px">
    <circle cx="${o.size / 2}" cy="${o.size / 2}" r="${r}" fill="none" stroke="${o.track}" stroke-width="${o.sw}"/>
    <circle cx="${o.size / 2}" cy="${o.size / 2}" r="${r}" fill="none" stroke="${o.color}" stroke-width="${o.sw}" stroke-linecap="round"
      stroke-dasharray="${c.toFixed(1)}" stroke-dashoffset="${(c * (1 - frac)).toFixed(1)}"
      transform="rotate(-90 ${o.size / 2} ${o.size / 2})" style="transition:stroke-dashoffset .6s ease"/></svg>`;
}

/* small batch bars */
function miniBars(data, opts) {
  const o = Object.assign({ w: 240, h: 60, ph: 60, color: 'var(--accent)', track: 'var(--s3)', active: -1 }, opts || {});
  const bw = o.w / data.length;
  const mx = Math.max(...data, 1);
  return `<svg viewBox="0 0 ${o.w} ${o.h}" preserveAspectRatio="none" style="width:100%;height:${o.ph}px;display:block">
    ${data.map((v, i) => {
      const bh = (v / mx) * o.h * 0.94;
      const col = i === o.active ? o.color : (i > o.active && o.active >= 0 ? o.track : 'color-mix(in srgb, ' + o.color + ' 55%, transparent)');
      return `<rect x="${(i * bw + bw * 0.18).toFixed(1)}" y="${(o.h - bh).toFixed(1)}" width="${(bw * 0.64).toFixed(1)}" height="${bh.toFixed(1)}" rx="2" fill="${col}"/>`;
    }).join('')}
  </svg>`;
}
