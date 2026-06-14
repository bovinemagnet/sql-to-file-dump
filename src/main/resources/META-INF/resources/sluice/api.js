/* Sluice — daemon API client. Talks to DashboardApiResource (/api/*).
 * State-changing calls carry the CSRF header that CsrfFilter requires. */

const API = {
  async jobs() {
    const r = await fetch('/api/jobs', { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error('GET /api/jobs → ' + r.status);
    return r.json();
  },

  async metrics() {
    const r = await fetch('/api/metrics', { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error('GET /api/metrics → ' + r.status);
    return r.json();
  },

  submit(fields) { return API._send('POST', '/api/jobs', fields); },
  describe(fields) { return API._send('POST', '/api/describe', fields); },

  async connections() {
    const r = await fetch('/api/connections', { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error('GET /api/connections → ' + r.status);
    return r.json();
  },
  createConnection(fields) { return API._send('POST', '/api/connections', fields); },
  updateConnection(id, fields) { return API._send('PUT', '/api/connections/' + encodeURIComponent(id), fields); },
  deleteConnection(id) { return API._send('DELETE', '/api/connections/' + encodeURIComponent(id)); },
  testConnection(id) { return API._send('POST', '/api/connections/' + encodeURIComponent(id) + '/test'); },
  testAdHoc(fields) { return API._send('POST', '/api/connections/test', fields); },

  async schedules() {
    const r = await fetch('/api/schedules', { headers: { Accept: 'application/json' } });
    if (!r.ok) throw new Error('GET /api/schedules → ' + r.status);
    return r.json();
  },
  createSchedule(fields) { return API._send('POST', '/api/schedules', fields); },
  updateSchedule(id, fields) { return API._send('PUT', '/api/schedules/' + encodeURIComponent(id), fields); },
  deleteSchedule(id) { return API._send('DELETE', '/api/schedules/' + encodeURIComponent(id)); },
  runSchedule(id) { return API._send('POST', '/api/schedules/' + encodeURIComponent(id) + '/run'); },
  toggleSchedule(id) { return API._send('POST', '/api/schedules/' + encodeURIComponent(id) + '/toggle'); },

  async _send(method, url, fields) {
    const init = {
      method,
      headers: { 'X-Requested-By': 'jdbc-export', Accept: 'application/json' },
    };
    if (fields) {
      init.headers['Content-Type'] = 'application/x-www-form-urlencoded';
      init.body = new URLSearchParams(fields);
    }
    const r = await fetch(url, init);
    let data = null;
    try { data = await r.json(); } catch (e) { data = null; }
    return { ok: r.ok, status: r.status, data };
  },
};
