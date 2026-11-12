/* Kxko Brief v4 · plain JavaScript, no build step. Data stays in this browser. */
(() => {
  'use strict';

  const KM_TO_MI = 0.621371;
  const DAY = 86_400_000;
  const MAX_ACCURACY_M = 25, MIN_STEP_M = 2, MIN_CLIMB_M = 0.8;
  const STOP_SPEED_MPS = 0.8, STOP_AFTER_SEC = 8;
  const MAX_BOX = 5, QUIZ_SECONDS = 15;
  const K = { rides: 'kb4.rides', study: 'kb4.study', settings: 'kb4.settings' };

  const $ = (s) => document.querySelector(s);
  const $$ = (s) => [...document.querySelectorAll(s)];
  const read = (k, f) => { try { return JSON.parse(localStorage.getItem(k)) ?? f; } catch { return f; } };
  const write = (k, v) => localStorage.setItem(k, JSON.stringify(v));
  const pad = (n) => String(n).padStart(2, '0');
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

  const defaults = { goalKm: 100, units: 'km', autoPause: false, theme: 'system' };
  const settings = () => ({ ...defaults, ...read(K.settings, {}) });
  const saveSettings = (p) => write(K.settings, { ...settings(), ...p });
  const rides = () => read(K.rides, []).sort((a, b) => b.startMs - a.startMs);
  const saveRides = (list) => write(K.rides, list);
  const content = window.KB_CONTENT || { study: [], cycling: [] };

  const fmtTime = (sec) => { const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60; return h ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`; };
  const unit = () => settings().units;
  const toDisp = (m) => (unit() === 'mi' ? m / 1000 * KM_TO_MI : m / 1000);
  const fmtDist = (m, d = 1) => toDisp(m).toFixed(d);
  const fmtSpeed = (mps) => (mps * 3.6 * (unit() === 'mi' ? KM_TO_MI : 1)).toFixed(1);
  const speedUnit = () => (unit() === 'mi' ? 'mph' : 'km/h');
  const dayKey = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`; };
  const startOfDay = (ms) => { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };
  const haversine = (a, b) => {
    const R = 6_371_000, r = (d) => d * Math.PI / 180;
    const dLat = r(b.lat - a.lat), dLon = r(b.lon - a.lon);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  };

  // ---------- theme ----------
  function applyTheme() {
    const pref = settings().theme;
    const dark = pref === 'dark' || (pref === 'system' && matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.dataset.theme = dark ? 'dark' : 'light';
    document.querySelector('meta[name="theme-color"]').content = dark ? '#0b0d0c' : '#f4f6f3';
    $('#set-theme').value = pref;
  }
  $('#theme-btn').addEventListener('click', () => {
    const dark = document.documentElement.dataset.theme === 'dark';
    saveSettings({ theme: dark ? 'light' : 'dark' }); applyTheme();
  });
  $('#set-theme').addEventListener('change', (e) => { saveSettings({ theme: e.target.value }); applyTheme(); });
  matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', () => { if (settings().theme === 'system') applyTheme(); });

  // ---------- navigation ----------
  let currentTab = 'dashboard';
  function go(tab) {
    if (!$(`#view-${tab}`)) tab = 'dashboard';
    currentTab = tab;
    $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${tab}`));
    $$('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === tab));
    $('#page-title').textContent = $(`#view-${tab}`).dataset.title;
    history.replaceState(null, '', `#${tab}`);
    try { window.scrollTo(0, 0); } catch { /* ignore */ }
    if (tab === 'admin' && !isAdminUser()) tab = 'dashboard';
    if (tab === 'timetable' && !canSeeTimetable()) tab = 'dashboard';
    if (tab === 'today' && !canSeeToday()) tab = 'dashboard';
    currentTab = tab;
    const renders = { admin: () => window.KB_ADMIN?.refresh(), timetable: () => window.KB_TIMETABLE?.refresh(), today: () => window.KB_TODAY?.refresh(), dashboard: renderDashboard, study: renderStudy, quiz: () => (quiz.questions.length ? renderQuiz() : startQuiz()), rides: renderRides, settings: renderSettings, ride: renderRideIdle };
    try { renders[tab]?.(); } catch (err) { console.error('Kxko: could not draw', tab, err); }
    if (tab !== 'quiz') stopQuizTimer();
  }
  $$('.tab').forEach((b) => b.addEventListener('click', () => go(b.dataset.tab)));
  // Event delegation, so buttons drawn later (like the banner's Sign in) also work.
  document.addEventListener('click', (e) => { const g = e.target.closest('[data-goto]'); if (g) go(g.dataset.goto); });

  // ---------- dashboard ----------
  function streakDays(list) {
    const days = new Set(list.map((r) => dayKey(r.startMs)));
    let cursor = Date.now(), n = 0;
    if (!days.has(dayKey(cursor))) cursor -= DAY;
    while (days.has(dayKey(cursor))) { n++; cursor -= DAY; }
    return n;
  }

  function records(list) {
    if (!list.length) return [];
    const longest = list.reduce((a, r) => (r.distanceM > a.distanceM ? r : a));
    const climb = list.reduce((a, r) => (r.elevGainM > a.elevGainM ? r : a));
    const long = list.filter((r) => r.distanceM >= 5000);
    const fastest = long.length ? long.reduce((a, r) => (r.distanceM / r.durationSec > a.distanceM / a.durationSec ? r : a)) : null;
    const splitAll = list.flatMap((r) => (r.splits || []).map((s) => ({ ...s, ride: r })));
    const bestSplit = splitAll.length ? splitAll.reduce((a, s) => (s.sec < a.sec ? s : a)) : null;
    return [
      { label: 'Longest ride', value: `${fmtDist(longest.distanceM)} ${unit()}`, note: new Date(longest.startMs).toLocaleDateString() },
      { label: 'Most climb', value: `+${climb.elevGainM.toFixed(0)} m`, note: new Date(climb.startMs).toLocaleDateString() },
      { label: 'Fastest avg (5 km+)', value: fastest ? `${fmtSpeed(fastest.distanceM / fastest.durationSec)} ${speedUnit()}` : '—', note: fastest ? new Date(fastest.startMs).toLocaleDateString() : 'Ride 5 km to qualify' },
      { label: 'Best km split', value: bestSplit ? fmtTime(bestSplit.sec) : '—', note: bestSplit ? `km ${bestSplit.km}` : 'Complete a full km' }
    ];
  }

  function renderDashboard() {
    renderUpcoming();
    const s = settings(), list = rides(), now = Date.now(), d = new Date();
    $('#date-line').textContent = d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'short' });
    const h = d.getHours();
    $('#greeting').textContent = h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';

    const week = list.filter((r) => now - r.startMs < 7 * DAY);
    const weekM = week.reduce((a, r) => a + r.distanceM, 0);
    const goalM = s.goalKm * 1000;
    const pct = Math.min(100, Math.round(weekM / goalM * 100));
    $('#ring-arc').style.strokeDashoffset = String(365 * (1 - pct / 100));
    $('#ring-pct').textContent = `${pct}%`;
    const left = Math.max(0, goalM - weekM);
    $('#hero-line').textContent = weekM === 0 ? 'Let’s get rolling this week.'
      : pct >= 100 ? 'Goal reached. Everything from here is a bonus.'
      : `${fmtDist(left)} ${unit()} to go this week.`;
    const streak = streakDays(list);
    $('#streak').textContent = streak ? `${streak} day${streak > 1 ? 's' : ''} in a row` : 'Ride today to start a streak';

    $('#w-km').textContent = fmtDist(weekM);
    $('#w-unit').textContent = unit();
    $('#w-rides').textContent = week.length;
    $('#w-time').textContent = fmtTime(week.reduce((a, r) => a + r.durationSec, 0)).replace(/^00:/, '0:');
    $('#w-climb').textContent = week.reduce((a, r) => a + r.elevGainM, 0).toFixed(0);
    $('#bars-note').textContent = `${unit()} per day`;

    const days = [...Array(7)].map((_, i) => {
      const back = 6 - i, t = now - back * DAY, start = startOfDay(t);
      const m = list.filter((r) => r.startMs >= start && r.startMs < start + DAY).reduce((a, r) => a + r.distanceM, 0);
      return { v: toDisp(m), label: new Date(t).toLocaleDateString(undefined, { weekday: 'short' }), today: back === 0 };
    });
    const max = Math.max(1, ...days.map((x) => x.v));
    $('#bars').innerHTML = days.map((x) => `<div class="bar-col ${x.today ? 'today' : ''}"><div class="bar" data-h="${x.v > 0 ? Math.max(6, x.v / max * 100) : 3}"></div><div class="bar-label">${esc(x.label)}</div></div>`).join('');
    requestAnimationFrame(() => $$('.bar').forEach((b) => (b.style.height = `${b.dataset.h}%`)));

    $('#records').innerHTML = list.length ? records(list).map((r) => `<div class="record"><span>${esc(r.label)}</span><b>${esc(r.value)}</b><br><small>${esc(r.note)}</small></div>`).join('')
      : '<p class="muted small" style="margin:0">Your records appear after your first ride.</p>';

    const last = list[0];
    $('#last-ride').innerHTML = last
      ? `<div class="head"><b style="font:700 22px var(--head)">${esc(rideTitle(last))}</b><span class="muted small">${fmtDist(last.distanceM)} ${unit()}</span></div>
         <p class="muted small" style="margin:0">${fmtTime(last.durationSec)} · avg ${fmtSpeed(last.durationSec ? last.distanceM / last.durationSec : 0)} ${speedUnit()} · climb +${last.elevGainM.toFixed(0)} m</p>`
      : 'No rides yet. Start one from the Ride tab.';
  }

  // ---------- ride recording ----------
  const live = { active: false, paused: false, autoPaused: false, startMs: 0, elapsed: 0, movingSec: 0, pauseCount: 0, distanceM: 0, climbM: 0, speed: 0, maxSpeed: 0, points: [], splits: [], lastSplitSec: 0, stoppedFor: 0, watchId: null, timer: null, wake: null };

  function onPosition(pos) {
    if (!live.active) return;
    const c = pos.coords;
    if (c.accuracy && c.accuracy > MAX_ACCURACY_M) return;
    const p = {
      t: pos.timestamp || Date.now(),
      lat: c.latitude, lon: c.longitude,
      alt: typeof c.altitude === 'number' ? c.altitude : null,
      spd: typeof c.speed === 'number' && c.speed >= 0 ? c.speed : null,
      acc: typeof c.accuracy === 'number' ? Math.round(c.accuracy) : null,
      hdg: typeof c.heading === 'number' && !Number.isNaN(c.heading) ? Math.round(c.heading) : null,
      paused: live.paused,
    };
    const last = live.points[live.points.length - 1];
    if (last) {
      const step = haversine(last, p);
      if (step < MIN_STEP_M && !last.paused === !p.paused) return;   // ignore GPS jitter while standing still
      // Distance and climb only count while riding. The step from a paused point is never added.
      if (!live.paused && !last.paused) {
        live.distanceM += step;
        if (last.alt != null && p.alt != null && p.alt - last.alt > MIN_CLIMB_M) live.climbM += p.alt - last.alt;
      }
    }
    live.points.push(p);
    live.speed = p.spd ?? 0;
    if (!live.paused) live.maxSpeed = Math.max(live.maxSpeed, live.speed);
    while (live.distanceM >= (live.splits.length + 1) * 1000) {
      live.splits.push({ km: live.splits.length + 1, sec: live.elapsed - live.lastSplitSec });
      live.lastSplitSec = live.elapsed;
    }
    updateRideUI();
  }

  function tick() {
    if (!live.active) return;
    const s = settings();
    if (s.autoPause || live.autoPaused) {
      live.stoppedFor = live.speed < STOP_SPEED_MPS ? live.stoppedFor + 1 : 0;
      if (s.autoPause && !live.paused && live.stoppedFor >= STOP_AFTER_SEC) { live.paused = true; live.autoPaused = true; }
      if (live.autoPaused && live.paused && live.speed >= STOP_SPEED_MPS) { live.paused = false; live.autoPaused = false; }
    }
    if (!live.paused) {
      live.elapsed++;
      if (live.speed >= STOP_SPEED_MPS) live.movingSec++;
    }
    updateRideUI();
  }

  async function keepAwake(on) {
    try {
      if (on && 'wakeLock' in navigator) live.wake = await navigator.wakeLock.request('screen');
      else if (live.wake) { await live.wake.release(); live.wake = null; }
    } catch { /* optional */ }
  }

  function renderRideIdle() {
    if (live.active) { $('#ride-idle').classList.add('hidden'); $('#ride-active').classList.remove('hidden'); updateRideUI(); return; }
    $('#ride-idle').classList.remove('hidden'); $('#ride-active').classList.add('hidden');
    $('#opt-autopause').checked = settings().autoPause;
  }

  function startRide() {
    if (!('geolocation' in navigator)) { $('#ride-note').textContent = 'This browser cannot access GPS.'; return; }
    saveSettings({ autoPause: $('#opt-autopause').checked });
    Object.assign(live, { active: true, paused: false, autoPaused: false, startMs: Date.now(), elapsed: 0, movingSec: 0, pauseCount: 0, distanceM: 0, climbM: 0, speed: 0, maxSpeed: 0, points: [], splits: [], lastSplitSec: 0, stoppedFor: 0 });
    live.watchId = navigator.geolocation.watchPosition(onPosition, (err) => { $('#ride-note').textContent = `GPS: ${err.message}`; }, { enableHighAccuracy: true, maximumAge: 0, timeout: 15000 });
    live.timer = setInterval(tick, 1000);
    keepAwake(true);
    renderRideIdle();
  }

  function finishRide() {
    if (!live.active) return;
    navigator.geolocation.clearWatch(live.watchId);
    clearInterval(live.timer); keepAwake(false);
    live.active = false;
    if (live.points.length >= 2 && live.distanceM > 20) {
      const ride = { id: live.startMs, uid: newUid(), startMs: live.startMs, durationSec: live.elapsed, distanceM: live.distanceM, elevGainM: live.climbM, maxSpeedMps: live.maxSpeed, splits: live.splits, points: live.points, name: '', notes: '' };
      saveRides([ride, ...rides()]);
      pushRide(ride);
      $('#ride-note').textContent = 'Ride saved. Name it in the Rides tab.';
    } else {
      $('#ride-note').textContent = 'Too short to save.';
    }
    renderRideIdle();
  }

  function updateRideUI() {
    if (!live.active) return;
    $('#timer').textContent = fmtTime(live.elapsed);
    $('#speed').textContent = live.paused ? '0.0' : fmtSpeed(live.speed);
    $('#dist').textContent = fmtDist(live.distanceM, 2);
    $('#dist').parentElement.querySelector('.unit').textContent = unit();
    $('#avg').textContent = fmtSpeed(live.elapsed > 0 ? live.distanceM / live.elapsed : 0);
    $('#climb').textContent = live.climbM.toFixed(0);
    $('#moving').textContent = fmtTime(live.movingSec);
    $('#maxspd').textContent = fmtSpeed(live.maxSpeed);
    $('#pauses').textContent = live.pauseCount;
    $('#pause-btn').textContent = live.paused ? 'Resume' : 'Pause';
    $('#rec-label').textContent = live.autoPaused ? 'Auto-paused' : live.paused ? 'Paused' : 'Recording';
    $('#rec-dot').classList.toggle('paused', live.paused);
    $('#autopause-tag').textContent = settings().autoPause ? 'auto-pause on' : '';
    renderSplits(live.splits, $('#splits'));
    if ($('#live-route').offsetParent) drawRoute($('#live-route'), live.points);
  }

  function renderSplits(splits, box) {
    if (!splits.length) { box.innerHTML = 'Splits appear every kilometre.'; return; }
    const best = Math.min(...splits.map((s) => s.sec));
    box.innerHTML = splits.map((s) => `<div class="split ${s.sec === best ? 'best' : ''}"><span>${s.km} km</span><div class="track"><div class="fill" style="width:${Math.max(8, best / s.sec * 100)}%"></div></div><span>${fmtTime(s.sec)}</span></div>`).join('');
    requestAnimationFrame(() => box.querySelectorAll('.fill').forEach((f) => (f.style.width = f.style.width)));
  }

  $('#start-btn').addEventListener('click', startRide);
  $('#pause-btn').addEventListener('click', () => { if (!live.paused) live.pauseCount++; live.paused = !live.paused; live.autoPaused = false; updateRideUI(); });
  $('#finish-btn').addEventListener('click', finishRide);
  $('#opt-autopause').addEventListener('change', (e) => saveSettings({ autoPause: e.target.checked }));
  window.addEventListener('resize', () => { if (live.active) updateRideUI(); });
  window.addEventListener('pagehide', () => { if (live.active) finishRide(); });

  function drawRoute(canvas, pts, { empty = 'Route appears once GPS locks on' } = {}) {
    const dpr = window.devicePixelRatio || 1, w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h) return;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const ctx = canvas.getContext('2d'); ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h);
    if (pts.length < 2) { ctx.fillStyle = getComputedStyle(canvas).color; ctx.globalAlpha = .45; ctx.font = '14px Inter, sans-serif'; ctx.textAlign = 'center'; ctx.fillText(empty, w / 2, h / 2); ctx.globalAlpha = 1; return; }
    const pad = 24;
    const lats = pts.map((p) => p.lat), lons = pts.map((p) => p.lon);
    const minLat = Math.min(...lats), maxLat = Math.max(...lats), minLon = Math.min(...lons), maxLon = Math.max(...lons);
    const cos = Math.max(0.2, Math.cos((minLat + maxLat) / 2 * Math.PI / 180));
    const rx = Math.max((maxLon - minLon) * cos, 1e-6), ry = Math.max(maxLat - minLat, 1e-6);
    const sc = Math.min((w - 2 * pad) / rx, (h - 2 * pad) / ry), ox = (w - rx * sc) / 2, oy = (h - ry * sc) / 2;
    const P = (p) => [ox + (p.lon - minLon) * cos * sc, oy + (maxLat - p.lat) * sc];
    const lime = getComputedStyle(document.documentElement).getPropertyValue('--lime').trim() || '#c6f432';
    ctx.lineWidth = 4; ctx.lineJoin = 'round'; ctx.lineCap = 'round'; ctx.strokeStyle = lime;
    ctx.beginPath(); pts.forEach((p, i) => { const [x, y] = P(p); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); }); ctx.stroke();
    const [sx, sy] = P(pts[0]), [ex, ey] = P(pts[pts.length - 1]);
    ctx.fillStyle = '#7be0a4'; ctx.beginPath(); ctx.arc(sx, sy, 6, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = lime; ctx.beginPath(); ctx.arc(ex, ey, 6, 0, Math.PI * 2); ctx.fill();
  }

  // ---------- rides ----------
  const rideTitle = (r) => r.name || `Ride · ${new Date(r.startMs).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })}`;

  function gpxFor(r) {
    const pts = r.points.map((p) => `      <trkpt lat="${p.lat}" lon="${p.lon}">${p.alt != null ? `<ele>${p.alt.toFixed(1)}</ele>` : ''}</trkpt>`).join('\n');
    return `<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="Kxko Brief" xmlns="http://www.topografix.com/GPX/1/1">\n  <trk><name>${esc(rideTitle(r))}</name>\n    <trkseg>\n${pts}\n    </trkseg>\n  </trk>\n</gpx>`;
  }

  function download(name, text, type = 'application/octet-stream') {
    const url = URL.createObjectURL(new Blob([text], { type }));
    const a = Object.assign(document.createElement('a'), { href: url, download: name });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function renderRides() {
    const list = rides(), total = list.reduce((a, r) => a + r.distanceM, 0);
    $('#r-count').textContent = list.length;
    $('#r-km').textContent = fmtDist(total, 0);
    $('#r-unit').textContent = unit();
    $('#r-climb').textContent = list.reduce((a, r) => a + r.elevGainM, 0).toFixed(0);
    const box = $('#ride-list');
    if (!list.length) { box.innerHTML = '<div class="panel empty"><h2>No rides yet</h2><p class="muted">Finished rides show up here with their route, splits and notes.</p></div>'; return; }
    box.innerHTML = list.map((r) => `
      <div class="panel ride-card" data-id="${r.id}">
        <div class="top2"><b style="font:600 16px var(--head)">${esc(rideTitle(r))}</b><span class="km">${fmtDist(r.distanceM)} ${unit()}</span></div>
        <p class="muted small" style="margin:4px 0 0">${fmtTime(r.durationSec)} · avg ${fmtSpeed(r.durationSec ? r.distanceM / r.durationSec : 0)} ${speedUnit()} · +${r.elevGainM.toFixed(0)} m</p>
        <div class="details">
          <canvas class="route" data-route="${r.id}" style="height:200px;margin-top:12px"></canvas>
          <div class="splits" style="margin-top:14px" data-splits="${r.id}"></div>
          <label class="field" style="margin-top:14px">Name<input data-name="${r.id}" value="${esc(r.name || '')}" placeholder="${esc(rideTitle(r))}"></label>
          <label class="field">Notes<textarea data-notes="${r.id}" placeholder="How did it feel? Weather, bike, route…">${esc(r.notes || '')}</textarea></label>
          <div class="actions">
            <button class="btn primary" data-save="${r.id}">Save details</button>
            <button class="btn primary" data-open="${r.id}">Open map and share</button>
            <button class="btn" data-gpx="${r.id}">Download GPX</button>
            <button class="btn danger" data-del="${r.id}">Delete</button>
          </div>
        </div>
      </div>`).join('');
  }

  $('#ride-list').addEventListener('click', (e) => {
    const t = e.target;
    const save = t.closest('[data-save]');
    if (save) {
      e.stopPropagation();
      const id = save.dataset.save;
      const name = document.querySelector(`[data-name="${id}"]`).value.trim();
      const notes = document.querySelector(`[data-notes="${id}"]`).value.trim();
      const updated = rides().map((r) => (String(r.id) === id ? { ...r, name, notes } : r));
      saveRides(updated);
      const changedRide = updated.find((r) => String(r.id) === id);
      if (changedRide) pushRide(changedRide);
      save.textContent = 'Saved ✓'; setTimeout(() => { save.textContent = 'Save details'; }, 1200);
      return;
    }
    const open = t.closest('[data-open]');
    if (open) { e.stopPropagation(); const r = rides().find((x) => String(x.id) === open.dataset.open); if (r) window.KB_RIDES?.open(r); return; }
    const gpx = t.closest('[data-gpx]');
    if (gpx) { e.stopPropagation(); const r = rides().find((x) => String(x.id) === gpx.dataset.gpx); download(`ride-${dayKey(r.startMs)}.gpx`, gpxFor(r), 'application/gpx+xml'); return; }
    const del = t.closest('[data-del]');
    if (del) { e.stopPropagation(); if (confirm('Delete this ride?')) { const gone = rides().find((x) => String(x.id) === del.dataset.del); saveRides(rides().filter((x) => String(x.id) !== del.dataset.del)); if (gone?.uid) deleteRemoteRide(gone.uid); renderRides(); renderDashboard(); } return; }
    if (t.closest('input, textarea, label')) return;
    const card = t.closest('.ride-card');
    if (card) {
      card.classList.toggle('open');
      if (card.classList.contains('open')) {
        const r = rides().find((x) => String(x.id) === card.dataset.id);
        requestAnimationFrame(() => {
          drawRoute(card.querySelector('canvas'), r.points, { empty: 'No GPS track saved' });
          renderSplits(r.splits || [], card.querySelector('[data-splits]'));
        });
      }
    }
  });

  // ---------- study ----------
  let studyCard = null, studyTopic = 'All';
  const getBoxes = () => read(K.study, {});
  const boxOf = (b, id) => b[id] ?? 1;
  const topics = () => ['All', ...new Set(activeCards().map((c) => c.topic).filter(Boolean))];
  const deck = () => activeCards().filter((c) => studyTopic === 'All' || c.topic === studyTopic);

  function renderStudy() {
    studyBanner();
    const b = getBoxes(), cards = deck();
    const mastered = cards.filter((c) => boxOf(b, c.id) >= MAX_BOX).length;
    $('#study-progress-label').textContent = `${mastered} of ${cards.length} mastered`;
    $('#study-bar').style.width = `${cards.length ? mastered / cards.length * 100 : 0}%`;
    $('#study-topic').textContent = studyTopic === 'All' ? '' : studyTopic;
    $('#study-filters').innerHTML = topics().map((t) => `<button class="filter ${t === studyTopic ? 'on' : ''}" data-topic="${esc(t)}">${esc(t)}</button>`).join('');
    studyCard = cards.filter((c) => boxOf(b, c.id) < MAX_BOX).reduce((best, c) => (!best || boxOf(b, c.id) < boxOf(b, best.id) ? c : best), null);
    const done = !studyCard;
    $('#study-done').classList.toggle('hidden', !done);
    $('.flash-actions').classList.toggle('hidden', done);
    $('.flashwrap').classList.toggle('hidden', done);
    if (done) return;
    $('#flash-front').textContent = studyCard.front;
    $('#flash-back').textContent = studyCard.back;
    $('#flash').classList.remove('flipped');
    $('#show-btn').classList.remove('hidden');
    $('#grade-btns').classList.add('hidden');
  }

  function flip(force) {
    const on = $('#flash').classList.toggle('flipped', force ?? undefined);
    $('#show-btn').classList.toggle('hidden', on);
    $('#grade-btns').classList.toggle('hidden', !on);
  }
  function grade(gotIt) {
    const b = getBoxes();
    b[studyCard.id] = gotIt ? Math.min(MAX_BOX, boxOf(b, studyCard.id) + 1) : 1;
    write(K.study, b); renderStudy();
  }
  $('#study-filters').addEventListener('click', (e) => { const t = e.target.closest('[data-topic]'); if (t) { studyTopic = t.dataset.topic; renderStudy(); } });
  $('#flash').addEventListener('click', () => studyCard && flip());
  $('#flash').addEventListener('keydown', (e) => { if ((e.key === 'Enter' || e.key === ' ') && studyCard) { e.preventDefault(); flip(); } });
  $('#show-btn').addEventListener('click', () => flip(true));
  $('#gotit-btn').addEventListener('click', () => grade(true));
  $('#again-btn').addEventListener('click', () => grade(false));
  $('#reset-study').addEventListener('click', () => { const b = getBoxes(); deck().forEach((c) => delete b[c.id]); write(K.study, b); renderStudy(); });

  // ---------- quiz with timer ----------
  let quiz = { index: 0, score: 0, answered: false, picked: null, wrong: [], questions: [] };
  let qTimer = null, qLeft = 0;

  function startQuiz(questions = activeQuiz()) {
    quiz = { index: 0, score: 0, answered: false, picked: null, wrong: [], questions };
    renderQuiz();
  }

  function stopQuizTimer() { clearInterval(qTimer); qTimer = null; }
  function startQuestionTimer() {
    stopQuizTimer(); qLeft = QUIZ_SECONDS;
    updateQuizTimer();
    qTimer = setInterval(() => {
      qLeft -= 0.1;
      if (qLeft <= 0) { stopQuizTimer(); answer(-1); return; }
      updateQuizTimer();
    }, 100);
  }
  function updateQuizTimer() { $('#quiz-timer').style.width = `${Math.max(0, qLeft / QUIZ_SECONDS * 100)}%`; }

  function renderQuiz() {
    quizBanner();
    const qs = quiz.questions, total = qs.length, body = $('#quiz-body');
    $('#quiz-progress').textContent = quiz.index >= total ? 'Finished' : `Question ${quiz.index + 1} of ${total}`;
    $('#quiz-score').textContent = `Score ${quiz.score}`;
    $('#quiz-bar').style.width = `${total ? quiz.index / total * 100 : 0}%`;
    if (!total) { stopQuizTimer(); updateQuizTimer(); body.innerHTML = '<div class="panel empty"><h2>No questions</h2><p class="muted">Add questions in data/content.js.</p></div>'; return; }

    if (quiz.index >= total) {
      stopQuizTimer(); $('#quiz-timer').style.width = '0%';
      const pct = Math.round(quiz.score / total * 100);
      body.innerHTML = `<div class="panel done"><span class="label">Result</span><div class="score-big">${quiz.score}/${total}</div>
        <p class="muted">${pct >= 80 ? 'Strong round.' : pct >= 50 ? 'Solid. Review the misses.' : 'Keep going, repetition works.'}</p>
        <div class="row" style="justify-content:center;flex-wrap:wrap">
          ${quiz.wrong.length ? '<button class="btn primary" id="retry-wrong">Retry missed</button>' : ''}
          <button class="btn" id="restart-quiz">Start over</button></div></div>`;
      $('#restart-quiz').addEventListener('click', () => startQuiz());
      $('#retry-wrong')?.addEventListener('click', () => startQuiz(quiz.wrong.slice()));
      return;
    }

    const q = qs[quiz.index];
    body.innerHTML = `<div class="panel">
      <p class="q-text">${esc(q.q)}</p>
      <div>${q.options.map((o, i) => `<button class="opt" data-i="${i}"><span class="letter">${'ABCD'[i] ?? i + 1}</span><span>${esc(o)}</span></button>`).join('')}</div>
      <p class="feedback" id="feedback"></p>
      <button class="btn primary wide" id="next-q" ${quiz.answered ? '' : 'disabled'}>${quiz.index + 1 === total ? 'See result' : 'Next question'}</button>
    </div>`;
    $$('#quiz-body .opt').forEach((b) => b.addEventListener('click', () => answer(Number(b.dataset.i))));
    $('#next-q').addEventListener('click', () => { if (!quiz.answered) return; quiz.index++; quiz.answered = false; quiz.picked = null; renderQuiz(); startQuestionTimer(); });
    if (quiz.answered) markAnswer(quiz.picked); else startQuestionTimer();
  }

  function answer(i) {
    if (quiz.answered) return;
    stopQuizTimer();
    quiz.answered = true; quiz.picked = i;
    const q = quiz.questions[quiz.index];
    if (i === q.answer) quiz.score++; else quiz.wrong.push(q);
    reportAnswer(q.qid, i === q.answer);
    markAnswer(i);
    $('#next-q').disabled = false;
  }

  function markAnswer(i) {
    const q = quiz.questions[quiz.index];
    $$('#quiz-body .opt').forEach((b, idx) => {
      b.disabled = true;
      if (idx === q.answer) b.classList.add('right');
      if (idx === i && i !== q.answer) b.classList.add('wrong');
    });
    const fb = $('#feedback');
    fb.textContent = i === q.answer ? 'Correct.' : i === -1 ? `Time's up. The answer is ${'ABCD'[q.answer]}.` : `Not quite. The answer is ${'ABCD'[q.answer]}.`;
    fb.className = `feedback ${i === q.answer ? 'good' : 'bad'}`;
    $('#quiz-score').textContent = `Score ${quiz.score}`;
    updateQuizTimer();
  }

  // ---------- settings & backup ----------
  function renderOwnerSettings() {
    const panel = $('#owner-panel');
    if (!panel) return;
    panel.classList.toggle('hidden', !isOwner());
    if (!isOwner()) return;
    $('#module-toggles').innerHTML = CFG.modules.map((m) => `
      <label class="toggle"><input type="checkbox" data-module-toggle="${esc(m.key)}" ${moduleOn(m.key) ? 'checked' : ''}> ${esc(m.label)}</label>`).join('');
    const w = weatherPlace();
    $('#wx-name').value = w.name || ''; $('#wx-lat').value = w.lat ?? ''; $('#wx-lon').value = w.lon ?? '';
  }

  $('#module-toggles').addEventListener('change', (e) => {
    const key = e.target.dataset.moduleToggle;
    if (key) setModule(key, e.target.checked);
  });
  $('#wx-save').addEventListener('click', () => {
    const lat = Number($('#wx-lat').value), lon = Number($('#wx-lon').value);
    const msg = $('#wx-msg');
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
      msg.textContent = 'Enter a valid latitude (-90 to 90) and longitude (-180 to 180).'; msg.className = 'admin-msg bad'; return;
    }
    setWeatherPlace({ name: $('#wx-name').value.trim() || 'My place', lat, lon });
    msg.textContent = 'Saved.'; msg.className = 'admin-msg ok';
    window.KB_TODAY?.refresh();
  });
  $('#wx-use-gps').addEventListener('click', () => {
    const msg = $('#wx-msg');
    if (!navigator.geolocation) { msg.textContent = 'This browser cannot get your location.'; msg.className = 'admin-msg bad'; return; }
    navigator.geolocation.getCurrentPosition(
      (pos) => { $('#wx-lat').value = pos.coords.latitude.toFixed(2); $('#wx-lon').value = pos.coords.longitude.toFixed(2); msg.textContent = 'Location filled in. Name it, then save.'; msg.className = 'admin-msg ok'; },
      (err) => { msg.textContent = `Could not get location: ${err.message}`; msg.className = 'admin-msg bad'; }
    );
  });

  function renderSettings() {
    renderAppSettings();
    renderOwnerSettings();
    const s = settings();
    $('#set-goal').value = s.goalKm; $('#set-units').value = s.units; $('#set-autopause').checked = s.autoPause;
    $('#set-theme').value = s.theme;
    renderAccount();
  }
  $('#set-goal').addEventListener('change', (e) => { const v = Math.max(10, Number(e.target.value) || 100); e.target.value = v; saveSettings({ goalKm: v }); });
  $('#set-units').addEventListener('change', (e) => saveSettings({ units: e.target.value }));
  $('#set-autopause').addEventListener('change', (e) => saveSettings({ autoPause: e.target.checked }));

  $('#export-btn').addEventListener('click', () => {
    download(`kxko-brief-backup-${dayKey(Date.now())}.json`, JSON.stringify({ app: 'kxko-brief', version: 4, exportedAt: new Date().toISOString(), settings: settings(), rides: rides(), study: getBoxes() }, null, 2), 'application/json');
  });
  $('#import-file').addEventListener('change', async (e) => {
    const f = e.target.files[0]; if (!f) return;
    try {
      const data = JSON.parse(await f.text());
      if (data.app !== 'kxko-brief' || !Array.isArray(data.rides)) throw new Error('not a Kxko Brief backup');
      saveRides(data.rides); if (data.study) write(K.study, data.study); if (data.settings) write(K.settings, data.settings);
      applyTheme(); renderSettings(); alert(`Imported ${data.rides.length} rides.`);
    } catch (err) { alert(`Import failed: ${err.message}`); }
    e.target.value = '';
  });
  $('#wipe-btn').addEventListener('click', () => {
    if (!confirm('Erase all rides, study progress and settings on this device?')) return;
    [K.rides, K.study, K.settings].forEach((k) => localStorage.removeItem(k));
    applyTheme(); renderSettings(); renderDashboard();
  });

  // ---------- offline ----------
  if ('serviceWorker' in navigator && location.protocol.startsWith('http') && !navigator.userAgent.includes('KxkoApp/')) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }


  // ---------- Supabase (study tests, sign-in, answer logging) ----------
  // Uses the same tables and rules as the Android app. Access is enforced by Supabase row-level security.
  const SB = window.KB_SUPABASE || { url: '', key: '' };
  const SESSION_KEY = 'kb5.session';
  const sbSession = () => read(SESSION_KEY, null);
  const sbSignedIn = () => Boolean(sbSession()?.access_token);

  const remote = { status: 'signed-out', error: '', cards: [], quiz: [], exams: 0, examList: [] };
  let liveLoaded = false;

  const shuffle = (a) => { const b = a.slice(); for (let i = b.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [b[i], b[j]] = [b[j], b[i]]; } return b; };
  const tidy = (v) => (typeof v === 'string' ? v.trim() : '');

  function decodeJwt(token) {
    try { return JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))); } catch { return {}; }
  }

  async function sbFetch(path, { method = 'GET', body, token, prefer } = {}) {
    const headers = { apikey: SB.key, 'Content-Type': 'application/json', Authorization: `Bearer ${token || sbSession()?.access_token || SB.key}`, ...(prefer ? { Prefer: prefer } : {}) };
    const res = await fetch(`${SB.url}${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) { const err = new Error(data?.message || data?.error_description || data?.msg || `HTTP ${res.status}`); err.status = res.status; throw err; }
    return data;
  }

  async function refreshSession() {
    const s = sbSession();
    if (!s?.refresh_token) throw Object.assign(new Error('Session expired. Sign in again.'), { status: 401 });
    const data = await sbFetch('/auth/v1/token?grant_type=refresh_token', { method: 'POST', body: { refresh_token: s.refresh_token }, token: SB.key });
    saveSession(data);
    return data.access_token;
  }

  // Calls the API with the stored login; on an expired token it refreshes once and retries.
  async function sbCall(path, opts) {
    try { return await sbFetch(path, opts); }
    catch (err) {
      if (err.status === 401 && sbSession()?.refresh_token) { const token = await refreshSession(); return sbFetch(path, { ...opts, token }); }
      throw err;
    }
  }

  function saveSession(data, email) {
    const prev = sbSession() || {};
    const access = data.access_token;
    write(SESSION_KEY, {
      access_token: access,
      refresh_token: data.refresh_token || prev.refresh_token,
      expires_at: Date.now() + (data.expires_in || 3600) * 1000,
      email: email || prev.email || decodeJwt(access).email || '',
      role: decodeJwt(access).app_metadata?.role || ''
    });
  }

  async function signIn(email, password) {
    const data = await sbFetch('/auth/v1/token?grant_type=password', { method: 'POST', body: { email: email.trim(), password }, token: SB.key });
    if (!data?.access_token) throw new Error('Sign-in did not return a session.');
    saveSession(data, email.trim());
    refreshAdminTab();
  }

  function signOut() {
    localStorage.removeItem(SESSION_KEY);
    liveLoaded = false;
    remote.status = 'signed-out'; remote.cards = []; remote.quiz = [];
    renderSettings(); renderStudy(); renderQuiz();
  }

  async function loadLive(force = false) {
    if (!SB.url || !SB.key) { remote.status = 'not-configured'; return; }
    if (!sbSignedIn()) { remote.status = 'signed-out'; remote.cards = []; remote.quiz = []; return; }
    if (liveLoaded && !force) return;
    remote.status = 'loading';
    try {
      const today = dayKey(Date.now());
      const exams = await sbCall(`/rest/v1/exams?select=id,subject,exam_date,topics,round&exam_date=gte.${today}&order=exam_date.asc`);
      remote.exams = exams.length;
      const subjectOf = Object.fromEntries(exams.map((e) => [e.id, tidy(e.subject) || 'Test']));
      const rows = exams.length ? await sbCall(`/rest/v1/exam_questions?select=id,exam_id,kind,payload&exam_id=in.(${exams.map((e) => e.id).join(',')})`) : [];

      const cards = [], quiz = [];
      for (const row of rows) {
        const p = row.payload || {};
        const subject = subjectOf[row.exam_id] || 'Test';
        if (row.kind === 'cover') {
          const front = tidy(p.front), back = tidy(p.back);
          if (front && back) cards.push({ id: row.id, topic: subject, front, back, explain: tidy(p.explain) });
        } else if (row.kind === 'choice') {
          const prompt = tidy(p.question), raw = Array.isArray(p.options) ? p.options.map(String) : [];
          if (!prompt || !Number.isInteger(p.answer) || p.answer < 0 || p.answer >= raw.length || raw.length < 3) continue;
          const correct = raw[p.answer];
          const options = shuffle([...shuffle(raw.filter((o) => o !== correct)).slice(0, 2), correct]);
          quiz.push({ qid: row.id, subject, q: prompt, options, answer: options.indexOf(correct), explain: tidy(p.explain) });
        } else if (row.kind === 'match') {
          const pairs = Array.isArray(p.pairs) ? p.pairs : [];
          const matches = [...new Set(pairs.map((x) => tidy(x?.match)).filter(Boolean))];
          pairs.forEach((pair) => {
            const term = tidy(pair?.term), match = tidy(pair?.match);
            if (!term || !match) return;
            const others = matches.filter((m) => m !== match);
            if (others.length < 2) return;
            const options = shuffle([...shuffle(others).slice(0, 2), match]);
            quiz.push({ qid: row.id, subject, q: `Which explanation fits “${term}”?`, options, answer: options.indexOf(match), explain: tidy(pair.explain) });
          });
        }
      }
      remote.examList = exams.map((e) => ({
        subject: tidy(e.subject) || 'Test', date: e.exam_date, round: e.round, topics: tidy(e.topics),
        count: rows.filter((r) => r.exam_id === e.id && r.kind).length
      }));
      remote.cards = cards; remote.quiz = quiz;
      remote.status = exams.length ? (quiz.length || cards.length ? 'live' : 'no-questions') : 'no-exams';
      remote.error = '';
    } catch (err) {
      remote.status = 'error'; remote.error = err.message; remote.cards = []; remote.quiz = []; remote.examList = [];
    }
    liveLoaded = true;
    renderStudy(); renderQuiz(); renderSettings(); renderUpcoming();
  }

  function reportAnswer(qid, correct) {
    if (!qid || !sbSignedIn()) return;
    sbCall('/rest/v1/rpc/record_exam_answer', { method: 'POST', body: { q_id: qid, was_right: correct } }).catch(() => { /* best effort, like the app */ });
  }

  // Which content the Study and Quiz tabs use right now.
  function activeCards() { return remote.cards.length ? remote.cards : content.study; }
  function activeQuiz() { return remote.quiz.length ? remote.quiz : content.cycling; }

  function banner(id, kind, text, showSignIn = true) {
    const el = $(id);
    const button = showSignIn && kind !== 'live' ? '<button class="btn small-btn" data-goto="settings">Sign in</button>' : '';
    el.className = `banner ${kind === 'live' ? 'live' : kind === 'error' ? 'error' : 'demo'}`;
    el.innerHTML = `${esc(text)}${button}`;
  }

  function studyBanner() {
    const s = remote.status;
    if (s === 'live') return banner('#study-banner', 'live', `Your upcoming tests: ${remote.cards.length} cards from ${remote.exams} test${remote.exams === 1 ? '' : 's'}.`, false);
    if (s === 'loading') return banner('#study-banner', 'demo', 'Loading your tests…', false);
    if (s === 'no-exams') return banner('#study-banner', 'demo', 'No upcoming tests in your account. Showing demo cards.');
    if (s === 'no-questions') return banner('#study-banner', 'demo', 'Your upcoming tests have no flashcards yet. Showing demo cards.');
    if (s === 'error') return banner('#study-banner', 'error', `Could not load your tests: ${remote.error}. Showing demo cards.`);
    if (s === 'not-configured') return banner('#study-banner', 'demo', 'Supabase is not configured. Showing demo cards.', false);
    return banner('#study-banner', 'demo', 'Showing demo cards. Sign in to study your real tests.');
  }

  function quizBanner() {
    const s = remote.status;
    if (s === 'live') return banner('#quiz-banner', 'live', `Your upcoming tests: ${remote.quiz.length} questions.`, false);
    if (s === 'loading') return banner('#quiz-banner', 'demo', 'Loading your tests…', false);
    if (s === 'no-exams') return banner('#quiz-banner', 'demo', 'No upcoming tests in your account. Showing demo questions.');
    if (s === 'no-questions') return banner('#quiz-banner', 'demo', 'Your tests have no multiple-choice questions yet. Showing demo questions.');
    if (s === 'error') return banner('#quiz-banner', 'error', `Could not load your tests: ${remote.error}. Showing demo questions.`);
    if (s === 'not-configured') return banner('#quiz-banner', 'demo', 'Supabase is not configured. Showing demo questions.', false);
    return banner('#quiz-banner', 'demo', 'Showing demo questions. Sign in to practise your real tests.');
  }

  $('#signin-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('#si-submit'), err = $('#si-error');
    btn.disabled = true; err.textContent = ''; err.className = 'small';
    try {
      await signIn($('#si-email').value, $('#si-password').value);
      $('#si-password').value = '';
      await loadLive(true);
      await syncRides();
      renderSettings();
    } catch (ex) {
      err.textContent = ex.message; err.className = 'small error';
    } finally { btn.disabled = false; }
  });
  $('#signout-btn').addEventListener('click', signOut);

  function renderUpcoming() {
    const box = $('#upcoming-tests');
    if (!box) return;
    const note = $('#tests-note');
    if (!sbSignedIn()) { note.textContent = ''; box.textContent = 'Sign in under Settings to see your upcoming tests.'; return; }
    if (remote.status === 'loading') { note.textContent = ''; box.textContent = 'Loading…'; return; }
    if (remote.status === 'error') { note.textContent = ''; box.textContent = `Couldn't load your tests: ${remote.error}`; return; }
    if (!remote.examList.length) { note.textContent = ''; box.textContent = 'No upcoming tests in your account.'; return; }
    const withItems = remote.examList.filter((e) => e.count > 0).length;
    const today = dayKey(Date.now());
    const next = remote.examList.find((e) => e.date >= today);
    const days = next ? Math.round((new Date(next.date + 'T12:00:00') - new Date(today + 'T12:00:00')) / 86400000) : null;
    note.innerHTML = next
      ? `<span class="countdown">${days === 0 ? 'Today' : days === 1 ? 'Tomorrow' : `In ${days} days`}: ${esc(next.subject)}</span> · ${withItems} of ${remote.examList.length} with items`
      : `${withItems} of ${remote.examList.length} with items`;
    box.innerHTML = remote.examList.map((e) => `
      <div class="test-row" style="--subj:${esc(subjectColor(e.subject))}">
        <div class="test-date">${esc(new Date(e.date + 'T12:00:00').toLocaleDateString(undefined, { day: 'numeric', month: 'short' }))}</div>
        <div class="test-main"><b>${esc(e.subject)}</b>${e.round ? ` <span class="muted small">· round ${e.round}</span>` : ''}
          <div class="muted small">${esc(e.topics || 'No topics yet')}</div></div>
        <div class="test-count ${e.count ? '' : 'none'}">${e.count ? `${e.count} items` : 'no items yet'}</div>
      </div>`).join('');
  }

  // Owner access and module switches come from data/config.js and the Settings toggles (no hard-coded emails).
  const CFG = window.KB_CONFIG || { ownerEmail: '', weather: { name: '', lat: 0, lon: 0 }, modules: [] };
  const MODULES_KEY = 'kb5.modules';
  const WEATHER_KEY = 'kb5.weather';
  const isOwner = () => Boolean(CFG.ownerEmail) && (sbSession()?.email || '').toLowerCase() === CFG.ownerEmail.toLowerCase();
  const moduleOn = (key) => isOwner() && read(MODULES_KEY, {})[key] !== false;
  const setModule = (key, on) => { const m = read(MODULES_KEY, {}); m[key] = on; write(MODULES_KEY, m); refreshAdminTab(); };
  const weatherPlace = () => ({ ...CFG.weather, ...read(WEATHER_KEY, {}) });
  const setWeatherPlace = (p) => write(WEATHER_KEY, p);

  const isAdminUser = () => moduleOn('admin');
  const canSeeTimetable = () => moduleOn('timetable');
  const canSeeToday = () => moduleOn('today');

  function refreshAdminTab() {
    document.querySelector('[data-tab="admin"]')?.classList.toggle('hidden', !isAdminUser());
    document.querySelector('[data-tab="timetable"]')?.classList.toggle('hidden', !canSeeTimetable());
    document.querySelector('[data-tab="today"]')?.classList.toggle('hidden', !canSeeToday());
    if (!isAdminUser() && currentTab === 'admin') go('dashboard');
    if (!canSeeTimetable() && currentTab === 'timetable') go('dashboard');
    if (!canSeeToday() && currentTab === 'today') go('dashboard');
    applyTabPrefs();
  }

  // Shared API for the admin module (js/admin.js). Uses the same login and refresh logic.
  async function ensureFresh() {
    const s = sbSession();
    if (s?.refresh_token && s.expires_at - Date.now() < 60000) await refreshSession();
  }
  window.kbApi = { rides, call: sbCall, ensureFresh, session: sbSession, SB, esc, isOwner, moduleOn, setModule, weatherPlace, setWeatherPlace, modules: () => CFG.modules, ownerEmail: () => CFG.ownerEmail };

  function renderAccount() {
    refreshAdminTab();
    const s = sbSession();
    $('#set-role').textContent = s?.access_token
      ? `Signed in as ${s.email || 'your account'}. Your upcoming tests load into Study and Quiz.`
      : 'Not signed in. Study and Quiz show demo content until you sign in.';
    $('#signin-form').classList.toggle('hidden', Boolean(s?.access_token));
    $('#signout-btn').classList.toggle('hidden', !s?.access_token);
  }

  // ---------- ride sync (signed-in users only; rides are private by row-level security) ----------
  const newUid = () => (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`);

  const rideToRow = (r) => ({
    id: r.uid, start_ms: r.startMs, duration_sec: Math.round(r.durationSec || 0),
    distance_m: r.distanceM || 0, elev_gain_m: r.elevGainM || 0, max_speed_mps: r.maxSpeedMps || 0,
    name: r.name || '', notes: r.notes || '', splits: r.splits || [], points: r.points || []
  });

  const rideFromRow = (row) => ({
    id: row.start_ms, uid: row.id, startMs: row.start_ms, durationSec: row.duration_sec,
    distanceM: row.distance_m, elevGainM: row.elev_gain_m, maxSpeedMps: row.max_speed_mps,
    name: row.name || '', notes: row.notes || '', splits: row.splits || [], points: row.points || []
  });

  async function pushRide(ride) {
    if (!sbSignedIn() || !ride.uid) return;
    try {
      await sbCall('/rest/v1/cycling_rides?on_conflict=id', { method: 'POST', body: rideToRow(ride), prefer: 'resolution=merge-duplicates,return=minimal' });
    } catch (err) { console.warn('Kxko: ride not saved online yet', err.message); }
  }

  async function deleteRemoteRide(uid) {
    if (!sbSignedIn() || !uid) return;
    try { await sbCall(`/rest/v1/cycling_rides?id=eq.${encodeURIComponent(uid)}`, { method: 'DELETE' }); }
    catch (err) { console.warn('Kxko: delete not synced', err.message); }
  }

  // Two-way sync: gives every local ride a UUID, uploads rides the server lacks, downloads rides this device lacks.
  async function syncRides() {
    if (!sbSignedIn()) return;
    let local = rides();
    if (local.some((r) => !r.uid)) {
      local = local.map((r) => (r.uid ? r : { ...r, uid: newUid() }));
      saveRides(local);
    }
    try {
      const serverRows = await sbCall('/rest/v1/cycling_rides?select=*&order=start_ms.desc');
      const serverIds = new Set(serverRows.map((row) => row.id));
      const localIds = new Set(local.map((r) => r.uid));
      for (const r of local) if (!serverIds.has(r.uid)) await pushRide(r);
      const pulled = serverRows.filter((row) => !localIds.has(row.id)).map(rideFromRow);
      if (pulled.length) { saveRides([...local, ...pulled]); }
      renderRides(); renderDashboard();
    } catch (err) {
      console.warn('Kxko: ride sync failed', err.message);
    }
  }


  // Safety net: if a panel still says "Loading…" after 10 seconds, say what went wrong instead of spinning forever.
  setTimeout(() => {
    $$('#upcoming-tests, #today-weather, #today-briefing, #today-homework, #examples-list, #reviews-list, #questions-list, #requests-list, #tt-lessons, #tt-changes, #tt-homework')
      .forEach((el) => {
        if (/^Loading/.test(el.textContent.trim())) {
          el.textContent = 'Did not load. Check the internet connection, then reload the page (or clear the site data in Settings → Safari).';
        }
      });
  }, 10000);

  // ---------- App preferences (Settings → App) ----------
  const PREFS_KEY = 'kb5.prefs';
  const TAB_LABELS = { dashboard: 'Home', ride: 'Ride', study: 'Study', quiz: 'Quiz', rides: 'Rides', today: 'Today', timetable: 'School', admin: 'Admin', settings: 'Settings' };
  const DEFAULT_PREFS = { accent: '#c6f432', density: 'comfortable', defaultTab: 'dashboard', hiddenTabs: [], subjectColors: {} };
  const prefs = () => ({ ...DEFAULT_PREFS, ...read(PREFS_KEY, {}) });
  const savePrefs = (patch) => { write(PREFS_KEY, { ...prefs(), ...patch }); applyPrefs(); };

  function applyTabPrefs() {
    const hidden = new Set(prefs().hiddenTabs);
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('tab-off', hidden.has(t.dataset.tab) && t.dataset.tab !== 'dashboard'));
  }
  function applyPrefs() {
    const p = prefs();
    const root = document.documentElement;
    root.style.setProperty('--lime', p.accent);
    root.dataset.density = p.density;
    applyTabPrefs();
  }

  // Subject colour: Settings override, else the keyword table in data/config.js.
  function subjectColor(name) {
    const n = String(name || '').toLowerCase();
    const rules = window.KB_SUBJECT_COLORS || [];
    const overrides = prefs().subjectColors || {};
    const rule = rules.find((r) => r.match.some((term) => n.includes(term)));
    if (!rule) return '#94a3b8';
    return overrides[rule.label] || rule.color;
  }

  function renderAppSettings() {
    const p = prefs();
    $('#pref-accent').value = p.accent;
    $('#pref-density').value = p.density;
    $('#pref-default-tab').innerHTML = Object.keys(TAB_LABELS).map((k) => `<option value="${k}" ${p.defaultTab === k ? 'selected' : ''}>${TAB_LABELS[k]}</option>`).join('');
    $('#pref-tabs').innerHTML = Object.keys(TAB_LABELS).filter((k) => k !== 'dashboard' && k !== 'settings').map((k) => `
      <label class="pref-row"><span>${TAB_LABELS[k]}</span><input type="checkbox" data-tab-toggle="${k}" ${p.hiddenTabs.includes(k) ? '' : 'checked'}></label>`).join('');
    $('#pref-subjects').innerHTML = (window.KB_SUBJECT_COLORS || []).map((r) => `
      <label class="pref-row"><span>${esc(r.label)}</span><input type="color" data-subject="${esc(r.label)}" value="${subjectColor(r.match[0])}"></label>`).join('');
  }

  $('#pref-accent').addEventListener('input', (e) => savePrefs({ accent: e.target.value }));
  $('#pref-density').addEventListener('change', (e) => savePrefs({ density: e.target.value }));
  $('#pref-default-tab').addEventListener('change', (e) => savePrefs({ defaultTab: e.target.value }));
  $('#pref-tabs').addEventListener('change', (e) => {
    const key = e.target.dataset.tabToggle;
    if (!key) return;
    const hidden = new Set(prefs().hiddenTabs);
    e.target.checked ? hidden.delete(key) : hidden.add(key);
    savePrefs({ hiddenTabs: [...hidden] });
  });
  $('#pref-subjects').addEventListener('input', (e) => {
    const label = e.target.dataset.subject;
    if (!label) return;
    savePrefs({ subjectColors: { ...(prefs().subjectColors || {}), [label]: e.target.value } });
  });
  $('#pref-reset').addEventListener('click', () => {
    if (!confirm('Reset the app look and tab settings to the defaults?')) return;
    localStorage.removeItem(PREFS_KEY); applyPrefs(); renderAppSettings();
  });

  window.kbApi.subjectColor = subjectColor;

  // ---------- start ----------
  try { applyTheme(); applyPrefs(); } catch (err) { console.error('Kxko: theme', err); }
  loadLive();
  syncRides();
  refreshAdminTab();
  const initial = location.hash.replace('#', '');
  go(initial || prefs().defaultTab || 'dashboard');

  // Android shell hooks: keep the existing native refresh and back actions working
  // while the V2 interface is bundled locally in the APK.
  window.kxkoRefresh = async function () {
    try {
      await loadLive(true);
      await syncRides();
      refreshAdminTab();
      go(currentTab);
    } catch (err) {
      console.warn('Kxko: Android refresh failed', err);
    }
  };
  window.kxkoBack = function () {
    if (currentTab !== 'dashboard') {
      go('dashboard');
      return true;
    }
    return false;
  };
})();
