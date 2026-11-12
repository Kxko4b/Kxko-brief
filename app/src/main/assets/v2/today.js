/* Today tab: weather, the latest daily briefing and your manual homework.
   Owner only, switched on in Settings. Database rules (briefing_runs, manual_homework) enforce access. */
(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const api = () => window.kbApi;
  const esc = (v) => api().esc(v);
  const canSee = () => api().isOwner() && api().moduleOn('today');
  const place = () => api().weatherPlace();

  // WMO weather codes (used by Open-Meteo)
  function wmo(code) {
    if (code === 0) return ['☀️', 'Clear'];
    if (code <= 2) return ['⛅', 'Partly cloudy'];
    if (code === 3) return ['☁️', 'Overcast'];
    if (code === 45 || code === 48) return ['🌫️', 'Fog'];
    if (code <= 57) return ['🌦️', 'Drizzle'];
    if (code <= 67) return ['🌧️', 'Rain'];
    if (code <= 77) return ['🌨️', 'Snow'];
    if (code <= 82) return ['🌦️', 'Showers'];
    if (code <= 86) return ['🌨️', 'Snow showers'];
    if (code >= 95) return ['⛈️', 'Thunderstorm'];
    return ['❔', 'Unknown'];
  }

  const weekday = (iso) => new Date(iso + 'T12:00:00').toLocaleDateString(undefined, { weekday: 'short' });

  // ---------- weather ----------
  async function loadWeather() {
    const box = $('#today-weather');
    box.textContent = 'Loading weather…';
    $('#wx-title').textContent = `Weather · ${place().name}`;
    try {
      const url = 'https://api.open-meteo.com/v1/forecast?' + new URLSearchParams({
        latitude: place().lat, longitude: place().lon,
        current: 'temperature_2m,weather_code,wind_speed_10m,precipitation',
        daily: 'weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max',
        timezone: 'Europe/Berlin', forecast_days: 7,
      });
      const res = await fetch(url);
      if (!res.ok) throw new Error(`weather ${res.status}`);
      const d = await res.json();
      const [icon, text] = wmo(d.current.weather_code);
      const days = d.daily.time.map((t, i) => {
        const [di] = wmo(d.daily.weather_code[i]);
        return `<div class="wx-day"><span>${esc(weekday(t))}</span><span class="wx-icon">${di}</span>
          <span>${Math.round(d.daily.temperature_2m_min[i])}° / ${Math.round(d.daily.temperature_2m_max[i])}°</span>
          <span class="muted small">${d.daily.precipitation_probability_max[i] ?? 0}%</span></div>`;
      }).join('');
      box.innerHTML = `
        <div class="wx-now">
          <div class="wx-big">${icon}</div>
          <div><div class="wx-temp">${Math.round(d.current.temperature_2m)}°C</div>
          <div class="muted">${esc(text)} · ${esc(place().name)}</div>
          <div class="muted small">Wind ${Math.round(d.current.wind_speed_10m)} km/h · rain ${d.current.precipitation} mm</div></div>
        </div>
        <div class="wx-days">${days}</div>
        <div class="muted small">Rain % is the chance of rain for each day.</div>`;
    } catch (err) {
      box.innerHTML = `<p class="bad small">Could not load weather: ${esc(err.message)}</p>`;
    }
  }

  // ---------- manual homework ----------
  async function loadHomework() {
    const box = $('#today-homework');
    box.textContent = 'Loading…';
    try {
      const rows = await api().call('/rest/v1/manual_homework?select=id,subject,description,due_date,completed&order=completed.asc,due_date.asc.nullslast');
      if (!rows.length) { box.textContent = 'No homework added yet.'; return; }
      box.innerHTML = rows.map((h) => `
        <div class="hw-item ${h.completed ? 'done' : ''}">
          <input type="checkbox" data-hw-toggle="${esc(h.id)}" ${h.completed ? 'checked' : ''} aria-label="Done">
          <div class="hw-main"><strong>${esc(h.subject)}</strong>${h.due_date ? ` <span class="muted small">· due ${esc(h.due_date)}</span>` : ''}
            <div>${esc(h.description)}</div></div>
          <button class="btn small danger" data-hw-del="${esc(h.id)}">Delete</button>
        </div>`).join('');
    } catch (err) { box.innerHTML = `<p class="bad small">Could not load homework: ${esc(err.message)}</p>`; }
  }

  $('#hw-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('#hw-msg');
    const subject = $('#hw-subject').value.trim();
    const description = $('#hw-desc').value.trim();
    if (!subject || !description) { msg.textContent = 'Subject and description are needed.'; msg.className = 'admin-msg bad'; return; }
    try {
      await api().call('/rest/v1/manual_homework', {
        method: 'POST',
        body: { subject, description, due_date: $('#hw-due').value || null },
        prefer: 'return=minimal',
      });
      $('#hw-form').reset();
      msg.textContent = 'Added.'; msg.className = 'admin-msg ok';
      loadHomework();
    } catch (err) { msg.textContent = err.message; msg.className = 'admin-msg bad'; }
  });

  $('#today-homework').addEventListener('change', async (e) => {
    const id = e.target.dataset.hwToggle;
    if (!id) return;
    try {
      await api().call(`/rest/v1/manual_homework?id=eq.${encodeURIComponent(id)}`, {
        method: 'PATCH', body: { completed: e.target.checked, updated_at: new Date().toISOString() }, prefer: 'return=minimal',
      });
      loadHomework();
    } catch (err) { alert(err.message); }
  });

  $('#today-homework').addEventListener('click', async (e) => {
    const id = e.target.closest('[data-hw-del]')?.dataset.hwDel;
    if (!id || !confirm('Delete this homework?')) return;
    try { await api().call(`/rest/v1/manual_homework?id=eq.${encodeURIComponent(id)}`, { method: 'DELETE' }); loadHomework(); }
    catch (err) { alert(err.message); }
  });

  function refresh() {
    if (!canSee()) return;
    loadWeather(); loadHomework(); window.KB_BRIEFING?.refresh();
  }

  window.KB_TODAY = { refresh, canSee };
})();
