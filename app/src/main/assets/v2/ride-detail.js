/* Ride detail: stats from the recorded telemetry, a map with the route coloured by speed,
   speed and elevation charts, and a 1080x1920 story image you can share (Instagram Stories, etc.). */
(() => {
  'use strict';

  const STOP_MPS = 0.8;        // below this counts as standing still
  const GAP_SEC = 15;          // a longer gap between points counts as a pause
  const LEAFLET = { js: 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js', css: 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css' };
  const $ = (s) => document.querySelector(s);
  const esc = (v) => window.kbApi.esc(v);

  const haversine = (a, b) => {
    const R = 6_371_000, r = (d) => d * Math.PI / 180;
    const dLat = r(b.lat - a.lat), dLon = r(b.lon - a.lon);
    const h = Math.sin(dLat / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(dLon / 2) ** 2;
    return 2 * R * Math.asin(Math.sqrt(h));
  };
  const fmtTime = (sec) => { const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = Math.round(sec % 60); const p = (n) => String(n).padStart(2, '0'); return h ? `${h}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`; };
  const dateLabel = (ms) => new Date(ms).toLocaleString(undefined, { weekday: 'long', day: 'numeric', month: 'long', hour: '2-digit', minute: '2-digit' });

  // ---------- statistics from the points ----------
  function computeStats(ride) {
    const pts = (ride.points || []).filter((p) => typeof p.lat === 'number');
    if (pts.length < 2) return null;
    // smoothed altitude (5-point average) so GPS noise does not count as climbing
    const alt = pts.map((_, i) => {
      const w = pts.slice(Math.max(0, i - 2), i + 3).map((q) => q.alt).filter((a) => a != null);
      return w.length ? w.reduce((a, b) => a + b, 0) / w.length : null;
    });
    let dist = 0, moving = 0, stopped = 0, paused = 0, pauses = 0, maxSpd = 0, climb = 0, descent = 0;
    let wasPaused = false;
    for (let i = 1; i < pts.length; i++) {
      const a = pts[i - 1], b = pts[i];
      const dt = ((b.t || 0) - (a.t || 0)) / 1000;
      if (dt <= 0) continue;
      if (b.paused || a.paused || dt > GAP_SEC) {
        paused += dt;
        if (!wasPaused) pauses++;
        wasPaused = true;
        continue;
      }
      wasPaused = false;
      const step = haversine(a, b);
      const v = b.spd != null ? b.spd : step / dt;
      dist += step;
      maxSpd = Math.max(maxSpd, v);
      if (v >= STOP_MPS) moving += dt; else stopped += dt;
      if (alt[i] != null && alt[i - 1] != null) {
        const d = alt[i] - alt[i - 1];
        if (d > 0.5) climb += d; else if (d < -0.5) descent -= d;
      }
    }
    const altVals = pts.map((p) => p.alt).filter((a) => a != null);
    const accVals = pts.map((p) => p.acc).filter((a) => a != null);
    const elapsed = (pts[pts.length - 1].t - pts[0].t) / 1000;
    return {
      distanceM: dist,
      elapsedSec: elapsed,
      movingSec: moving,
      stoppedSec: stopped,
      pausedSec: paused,
      pauses,
      avgMovingMps: moving > 0 ? dist / moving : 0,
      avgMps: elapsed > 0 ? dist / elapsed : 0,
      maxMps: maxSpd,
      climbM: climb,
      descentM: descent,
      minAlt: altVals.length ? Math.min(...altVals) : null,
      maxAlt: altVals.length ? Math.max(...altVals) : null,
      avgAcc: accVals.length ? Math.round(accVals.reduce((a, b) => a + b, 0) / accVals.length) : null,
      kcal: Math.round(dist / 1000 * 32),
      points: pts,
      alt,
      hasTime: pts.every((p) => p.t),
    };
  }

  // ---------- overlay ----------
  function ensureOverlay() {
    if ($('#ride-overlay')) return;
    document.body.insertAdjacentHTML('beforeend', `
      <div id="ride-overlay" class="ov hidden" role="dialog" aria-modal="true" aria-label="Ride details">
        <div class="ov-card">
          <div class="ov-head"><span id="ov-title"></span><button class="btn small" id="ov-close">Close</button></div>
          <div id="ov-body"></div>
        </div>
      </div>`);
    $('#ov-close').addEventListener('click', close);
    $('#ride-overlay').addEventListener('click', (e) => { if (e.target.id === 'ride-overlay') close(); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
  }
  function close() { $('#ride-overlay')?.classList.add('hidden'); }

  // ---------- leaflet (loaded only when a ride is opened) ----------
  function loadLeaflet() {
    if (window.L) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const css = document.createElement('link'); css.rel = 'stylesheet'; css.href = LEAFLET.css; document.head.appendChild(css);
      const js = document.createElement('script'); js.src = LEAFLET.js;
      js.onload = resolve; js.onerror = () => reject(new Error('The map library could not load (check the internet connection).'));
      document.head.appendChild(js);
    });
  }

  // speed colour: slow green → fast red, relative to the ride's own top speed
  function speedColor(v, max) {
    const f = Math.max(0, Math.min(1, max > 0 ? v / max : 0));
    const hue = 130 - 130 * f; // 130 = green, 0 = red
    return `hsl(${hue.toFixed(0)} 85% 50%)`;
  }

  async function drawMap(el, st) {
    await loadLeaflet();
    const pts = st.points;
    const map = window.L.map(el, { zoomControl: true, attributionControl: true });
    window.L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19, attribution: '© OpenStreetMap',
    }).addTo(map);
    const latlngs = pts.map((p) => [p.lat, p.lon]);
    const bounds = window.L.latLngBounds(latlngs);
    const maxSpd = Math.max(...pts.map((p) => p.spd ?? 0), 0.1);
    for (let i = 1; i < pts.length; i++) {
      const v = pts[i].spd ?? 0;
      window.L.polyline([latlngs[i - 1], latlngs[i]], { color: speedColor(v, maxSpd), weight: 5, opacity: .9 }).addTo(map);
    }
    window.L.circleMarker(latlngs[0], { radius: 7, color: '#fff', weight: 2, fillColor: '#16a34a', fillOpacity: 1 }).bindTooltip('Start').addTo(map);
    window.L.circleMarker(latlngs[latlngs.length - 1], { radius: 7, color: '#fff', weight: 2, fillColor: '#e11d48', fillOpacity: 1 }).bindTooltip('Finish').addTo(map);
    map.fitBounds(bounds, { padding: [24, 24] });
    setTimeout(() => map.invalidateSize(), 50);
  }

  // ---------- charts ----------
  function drawChart(canvas, values, labels, color, unit) {
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (!w || !h || values.length < 2) return;
    canvas.width = w * dpr; canvas.height = h * dpr;
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    const pad = { l: 36, r: 8, t: 10, b: 22 };
    const min = Math.min(...values), max = Math.max(...values);
    const span = Math.max(max - min, 1e-6);
    const X = (i) => pad.l + (i / (values.length - 1)) * (w - pad.l - pad.r);
    const Y = (v) => pad.t + (1 - (v - min) / span) * (h - pad.t - pad.b);
    ctx.strokeStyle = 'rgba(128,128,128,.25)'; ctx.lineWidth = 1;
    ctx.fillStyle = getComputedStyle(canvas).color; ctx.font = '11px Inter, sans-serif';
    [min, (min + max) / 2, max].forEach((v) => {
      ctx.beginPath(); ctx.moveTo(pad.l, Y(v)); ctx.lineTo(w - pad.r, Y(v)); ctx.stroke();
      ctx.fillText(`${Math.round(v)}`, 4, Y(v) + 4);
    });
    ctx.fillText(labels[0], pad.l, h - 6);
    ctx.fillText(labels[1], w - pad.r - ctx.measureText(labels[1]).width, h - 6);
    ctx.beginPath();
    values.forEach((v, i) => (i ? ctx.lineTo(X(i), Y(v)) : ctx.moveTo(X(i), Y(v))));
    ctx.strokeStyle = color; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.stroke();
    ctx.lineTo(X(values.length - 1), h - pad.b); ctx.lineTo(X(0), h - pad.b); ctx.closePath();
    ctx.fillStyle = color.replace(')', ', .12)').replace('rgb', 'rgba').replace('#', '');
    ctx.globalAlpha = 0.18; ctx.fillStyle = color; ctx.fill(); ctx.globalAlpha = 1;
  }

  // thin the points so charts stay fast on long rides
  const thin = (arr, n = 400) => (arr.length <= n ? arr : arr.filter((_, i) => i % Math.ceil(arr.length / n) === 0));

  // ---------- rendering ----------
  function statsGrid(st, ride) {
    const kmh = (v) => (v * 3.6).toFixed(1);
    const items = [
      ['Distance', `${(st.distanceM / 1000).toFixed(2)} km`],
      ['Duration', fmtTime(st.elapsedSec)],
      ['Moving time', fmtTime(st.movingSec)],
      ['Paused', `${fmtTime(st.pausedSec)} · ${st.pauses}×`],
      ['Avg moving', `${kmh(st.avgMovingMps)} km/h`],
      ['Max speed', `${kmh(st.maxMps)} km/h`],
      ['Climb', `+${st.climbM.toFixed(0)} m`],
      ['Descent', `−${st.descentM.toFixed(0)} m`],
      ['Altitude', st.minAlt != null ? `${st.minAlt.toFixed(0)}–${st.maxAlt.toFixed(0)} m` : '—'],
      ['GPS accuracy', st.avgAcc != null ? `±${st.avgAcc} m` : '—'],
      ['Points', `${st.points.length}`],
      ['Energy (est.)', `≈${st.kcal} kcal`],
    ];
    return items.map(([k, v]) => `<div class="rd-stat"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('');
  }

  function splitsTable(ride, st) {
    const splits = ride.splits?.length ? ride.splits : [];
    if (!splits.length) return '<p class="muted small">Not enough distance for kilometre splits.</p>';
    const best = Math.min(...splits.map((s) => s.sec));
    const worst = Math.max(...splits.map((s) => s.sec));
    return splits.map((s) => `
      <div class="split ${s.sec === best ? 'best' : ''}"><span>${s.km} km</span>
        <div class="track"><div class="fill" style="width:${Math.max(8, best / s.sec * 100)}%"></div></div>
        <span>${fmtTime(s.sec)}</span></div>`).join('');
  }

  async function open(ride) {
    ensureOverlay();
    const st = computeStats(ride);
    $('#ov-title').textContent = ride.name || 'Ride';
    const body = $('#ov-body');
    if (!st) { body.innerHTML = '<p class="muted">This ride has too few GPS points to show.</p>'; $('#ride-overlay').classList.remove('hidden'); return; }
    body.innerHTML = `
      <div class="rd-date muted small">${esc(dateLabel(ride.startMs))}</div>
      ${st.hasTime ? '' : '<p class="admin-msg bad">This ride was recorded before timing was added, so moving time and speed are not available.</p>'}
      <div class="rd-hero"><b>${(st.distanceM / 1000).toFixed(1)}</b><span>km</span></div>
      <div class="rd-grid">${statsGrid(st, ride)}</div>

      <div class="rd-block"><h3>Route</h3>
        <div id="rd-map" class="rd-map"></div>
        <div class="rd-legend muted small"><span class="lg slow"></span> slow <span class="lg fast"></span> fast · route colour follows your speed</div>
      </div>

      <div class="rd-block"><h3>Speed</h3><canvas id="rd-speed" class="rd-chart"></canvas></div>
      <div class="rd-block"><h3>Elevation</h3><canvas id="rd-elev" class="rd-chart"></canvas></div>

      <div class="rd-block"><h3>Kilometre splits</h3><div class="splits">${splitsTable(ride, st)}</div></div>

      <div class="rd-block rd-share">
        <h3>Share</h3>
        <p class="muted small">Makes a 1080 × 1920 story image. On iPad and iPhone the share sheet lets you choose Instagram → Add to story.</p>
        <div class="row"><button class="btn primary grow" id="rd-share">Share story</button><button class="btn" id="rd-download">Save image</button></div>
        <div class="admin-msg" id="rd-share-msg"></div>
      </div>`;
    $('#ride-overlay').classList.remove('hidden');

    const mapEl = $('#rd-map');
    drawMap(mapEl, st).catch((err) => { mapEl.innerHTML = `<p class="muted small">${esc(err.message)}</p>`; });
    requestAnimationFrame(() => {
      const pts = thin(st.points);
      const speeds = pts.map((p) => (p.spd ?? 0) * 3.6);
      drawChart($('#rd-speed'), speeds, [fmtTime(0), fmtTime(st.elapsedSec)], '#2563eb');
      const alts = thin(st.points.map((p, i) => st.alt[i])).filter((a) => a != null);
      if (alts.length > 1) drawChart($('#rd-elev'), alts, ['0 km', `${(st.distanceM / 1000).toFixed(1)} km`], '#c6f432');
    });
    $('#rd-share').addEventListener('click', () => share(ride, st, 'share'));
    $('#rd-download').addEventListener('click', () => share(ride, st, 'download'));
  }

  // ---------- story image (1080 × 1920) ----------
  function drawStory(ride, st) {
    const W = 1080, H = 1920;
    const c = document.createElement('canvas');
    c.width = W; c.height = H;
    const g = c.getContext('2d');
    const bg = g.createLinearGradient(0, 0, 0, H);
    bg.addColorStop(0, '#0b0d0c'); bg.addColorStop(0.55, '#101a14'); bg.addColorStop(1, '#0b0d0c');
    g.fillStyle = bg; g.fillRect(0, 0, W, H);

    const lime = '#c6f432';
    g.fillStyle = lime; g.font = '600 40px Inter, Arial, sans-serif';
    g.fillText('KXKO BRIEF', 80, 120);
    g.fillStyle = '#9aa89e'; g.font = '400 36px Inter, Arial, sans-serif';
    g.fillText(new Date(ride.startMs).toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' }), 80, 180);

    g.fillStyle = '#f1f5f9'; g.font = '800 260px Inter, Arial, sans-serif';
    g.fillText((st.distanceM / 1000).toFixed(1), 70, 470);
    g.fillStyle = lime; g.font = '600 64px Inter, Arial, sans-serif';
    g.fillText('km', 80 + g.measureText((st.distanceM / 1000).toFixed(1)).width + 20, 470);

    // route box
    const bx = 80, by = 560, bw = 920, bh = 880;
    g.fillStyle = 'rgba(255,255,255,.04)'; g.fillRect(bx, by, bw, bh);
    const pts = st.points;
    const lats = pts.map((p) => p.lat), lons = pts.map((p) => p.lon);
    const minLat = Math.min(...lats), maxLat = Math.max(...lats), minLon = Math.min(...lons), maxLon = Math.max(...lons);
    const cos = Math.max(0.2, Math.cos((minLat + maxLat) / 2 * Math.PI / 180));
    const rx = Math.max((maxLon - minLon) * cos, 1e-6), ry = Math.max(maxLat - minLat, 1e-6);
    const pad = 70;
    const sc = Math.min((bw - 2 * pad) / rx, (bh - 2 * pad) / ry);
    const ox = bx + (bw - rx * sc) / 2, oy = by + (bh - ry * sc) / 2;
    const P = (p) => [ox + (p.lon - minLon) * cos * sc, oy + (maxLat - p.lat) * sc];
    const maxSpd = Math.max(...pts.map((p) => p.spd ?? 0), 0.1);
    g.lineWidth = 12; g.lineCap = 'round';
    for (let i = 1; i < pts.length; i++) {
      const [x1, y1] = P(pts[i - 1]), [x2, y2] = P(pts[i]);
      g.strokeStyle = speedColor(pts[i].spd ?? 0, maxSpd);
      g.beginPath(); g.moveTo(x1, y1); g.lineTo(x2, y2); g.stroke();
    }
    const [sx, sy] = P(pts[0]), [ex, ey] = P(pts[pts.length - 1]);
    g.fillStyle = '#16a34a'; g.beginPath(); g.arc(sx, sy, 20, 0, Math.PI * 2); g.fill();
    g.fillStyle = '#e11d48'; g.beginPath(); g.arc(ex, ey, 20, 0, Math.PI * 2); g.fill();

    // stat rows
    const row = (y, items) => {
      const cw = (W - 160) / items.length;
      items.forEach(([label, value], i) => {
        const x = 80 + i * cw;
        g.fillStyle = '#67768a'; g.font = '600 26px Inter, Arial, sans-serif';
        g.fillText(label.toUpperCase(), x, y);
        g.fillStyle = '#f1f5f9'; g.font = '700 56px Inter, Arial, sans-serif';
        g.fillText(value, x, y + 70);
      });
    };
    row(1560, [['Time', fmtTime(st.elapsedSec)], ['Moving', fmtTime(st.movingSec)], ['Avg', `${(st.avgMovingMps * 3.6).toFixed(1)}`]]);
    row(1760, [['Max', `${(st.maxMps * 3.6).toFixed(1)}`], ['Climb', `+${st.climbM.toFixed(0)} m`], ['Pauses', `${st.pauses}×`]]);

    g.fillStyle = '#67768a'; g.font = '400 26px Inter, Arial, sans-serif';
    g.fillText('Recorded with Kxko Brief', 80, 1860);
    return c;
  }

  async function share(ride, st, mode) {
    const msg = $('#rd-share-msg');
    msg.textContent = 'Making the image…'; msg.className = 'admin-msg ok';
    const canvas = drawStory(ride, st);
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
    const name = `kxko-ride-${new Date(ride.startMs).toISOString().slice(0, 10)}.png`;
    const file = new File([blob], name, { type: 'image/png' });
    if (mode === 'share' && navigator.canShare?.({ files: [file] })) {
      try {
        await navigator.share({ files: [file], title: 'My ride', text: `${(st.distanceM / 1000).toFixed(1)} km` });
        msg.textContent = 'Shared.'; return;
      } catch (err) {
        if (err.name === 'AbortError') { msg.textContent = ''; return; }
      }
    }
    const url = URL.createObjectURL(blob);
    const a = Object.assign(document.createElement('a'), { href: url, download: name });
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
    msg.textContent = 'Image saved. Open it and share it to Instagram Stories.';
  }

  window.KB_RIDES = { open, computeStats, close };
})();
