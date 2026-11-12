/* Briefing diagnostics (Today tab, owner only). Pick a run to see what was checked, what was sent to the
   watch, what the AI reviewer said, and force a retry when something went wrong. */
(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const api = () => window.kbApi;
  const esc = (v) => api().esc(v);

  const STATUS = {
    healthy: { text: 'Healthy', cls: 'ok' },
    repaired: { text: 'Repaired', cls: 'ok' },
    degraded: { text: 'Needs attention', cls: 'warn' },
    failed: { text: 'Failed', cls: 'bad' },
  };
  const KIND = { check: 'Check', repair: 'Repair', ai: 'AI reviewer', escalation: 'Needs you' };
  const OUTCOME = { ok: 'OK', fixed: 'Fixed automatically', reported: 'Reported', stopped: 'Stopped' };

  let runs = [];
  const eventCache = {};
  let selectedId = null;

  const when = (iso) => new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  const badge = (status) => { const s = STATUS[status] || { text: status, cls: '' }; return `<span class="bl-badge ${s.cls}">${esc(s.text)}</span>`; };

  async function eventsFor(runId) {
    if (!eventCache[runId]) {
      eventCache[runId] = await api().call(`/rest/v1/briefing_events?select=id,created_at,kind,component,severity,outcome,found,action,details&run_id=eq.${runId}&order=created_at.asc`);
    }
    return eventCache[runId];
  }

  // Plain-language sentence: what went wrong, or that nothing did.
  function explain(run, events) {
    const bad = events.filter((e) => e.outcome === 'stopped' || e.outcome === 'reported');
    const fixed = events.filter((e) => e.outcome === 'fixed');
    if (!bad.length && !fixed.length) return { cls: 'ok', text: 'Everything worked. Nothing needed fixing or reporting.' };
    const parts = [];
    if (fixed.length) parts.push(`${fixed.length} problem${fixed.length > 1 ? 's were' : ' was'} fixed automatically`);
    bad.forEach((e) => parts.push(`${e.component}: ${e.found}`));
    return { cls: bad.length ? (run.status === 'failed' ? 'bad' : 'warn') : 'ok', text: parts.join('. ') + '.' };
  }

  function eventRow(e) {
    const kind = KIND[e.kind] || e.kind;
    return `
      <div class="bl-event ${esc(e.outcome)} ${e.kind === 'ai' ? 'ai' : ''}">
        <div class="bl-meta"><span class="bl-kind">${esc(kind)}</span>
          <span class="muted small"> · ${esc(e.component)} · ${esc(OUTCOME[e.outcome] || e.outcome)} · ${esc(e.severity)}</span></div>
        <div class="bl-found">${esc(e.found || '')}</div>
        ${e.action ? `<div class="bl-action">${esc(e.action)}</div>` : ''}
      </div>`;
  }

  function componentRow(c) {
    const ok = c.success;
    const retried = c.recovered ? ` · recovered on attempt ${c.attempts}` : (c.attempts > 1 ? ` · ${c.attempts} attempts` : '');
    return `
      <div class="bl-source ${ok ? 'ok' : 'bad'}">
        <span class="bl-dot"></span>
        <div class="bl-grow"><strong>${esc(c.name)}</strong><div class="muted small">${ok ? 'OK' : esc(c.error || 'failed')}${esc(retried)}</div></div>
      </div>`;
  }

  async function renderRun(run) {
    const box = $('#bl-body');
    box.innerHTML = '<div class="muted small">Loading this run…</div>';
    let events = [];
    try { events = await eventsFor(run.id); } catch (err) { box.innerHTML = `<p class="bad small">Could not load the log: ${esc(err.message)}</p>`; return; }

    const ex = explain(run, events);
    const fixed = events.filter((e) => e.outcome === 'fixed').length;
    const stopped = events.filter((e) => e.outcome === 'stopped').length;
    const reported = events.filter((e) => e.outcome === 'reported').length;
    const comps = Array.isArray(run.components) ? run.components : [];
    const needsRetry = run.status === 'degraded' || run.status === 'failed';
    const sentText = run.briefing || '';

    box.innerHTML = `
      <div class="bl-summary ${ex.cls}">${esc(ex.text)}</div>

      <div class="bl-tiles">
        <div class="bl-tile"><span>Fixed automatically</span><b>${fixed}</b></div>
        <div class="bl-tile"><span>Stopped for you</span><b>${stopped}</b></div>
        <div class="bl-tile"><span>Reported</span><b>${reported}</b></div>
      </div>

      <div class="bl-block">
        <div class="bl-title">Notification on your watch</div>
        <div class="bl-row muted small">
          <span>Title: Kxko Daily Briefing</span>
          <span>${run.push_sent ? 'Sent to the phone and watch' : 'Not sent'}</span>
        </div>
        <pre class="bl-text">${esc(sentText || '(empty)')}</pre>
        ${run.push_sent ? '' : '<p class="muted small">Nothing reached the watch for this run.</p>'}
      </div>

      <div class="bl-block">
        <div class="bl-title">Self-check and AI activity</div>
        ${events.length ? events.map(eventRow).join('') : '<p class="muted small">The self-check found nothing to fix or report for this run.</p>'}
      </div>

      <div class="bl-block">
        <div class="bl-title">Data sources</div>
        ${comps.length ? comps.map(componentRow).join('') : '<p class="muted small">No data source details stored for this run.</p>'}
      </div>

      ${needsRetry ? `
        <div class="bl-block bl-force">
          <div class="bl-title">Force a fix</div>
          <p class="muted small">Runs the whole briefing again with its safe repairs and the AI review. The same text is not sent to the watch twice.</p>
          <button class="btn primary" id="bl-retry">Force fix now</button>
          <div class="admin-msg" id="bl-retry-msg"></div>
        </div>` : ''}`;
  }

  function renderSelect() {
    const sel = $('#bl-select');
    sel.innerHTML = runs.map((r) => {
      const s = STATUS[r.status] || { text: r.status };
      return `<option value="${esc(r.id)}" ${r.id === selectedId ? 'selected' : ''}>${esc(when(r.run_at))} — ${esc(s.text)}</option>`;
    }).join('');
  }

  async function show(id) {
    selectedId = id;
    const run = runs.find((r) => String(r.id) === String(id));
    if (!run) return;
    renderSelect();
    $('#bl-head').innerHTML = `${badge(run.status)} <span class="muted small">${esc(when(run.run_at))} · ${run.push_sent ? 'notification sent' : 'no notification'}</span>`;
    await renderRun(run);
  }

  async function retry() {
    const btn = $('#bl-retry'), msg = $('#bl-retry-msg');
    btn.disabled = true;
    msg.textContent = 'Running the briefing again. This can take a minute…'; msg.className = 'admin-msg ok';
    try {
      await api().ensureFresh();
      const res = await api().call('/functions/v1/briefing-retry', { method: 'POST', body: {} });
      if (res?.success === false) throw new Error(res.error || 'The retry failed.');
      msg.textContent = `Done. New status: ${res.status || res.selfCheck?.status || 'checked'}.`; msg.className = 'admin-msg ok';
      Object.keys(eventCache).forEach((k) => delete eventCache[k]);
      await refresh(true);
    } catch (err) {
      msg.textContent = err.message; msg.className = 'admin-msg bad';
    } finally { btn.disabled = false; }
  }

  async function refresh(keepSelection = false) {
    if (!window.KB_TODAY?.canSee?.()) return;
    const box = $('#today-briefing');
    if (!keepSelection) box.textContent = 'Loading briefing…';
    try {
      runs = await api().call('/rest/v1/briefing_runs?select=id,run_at,date_key,status,summary,briefing,push_sent,components&order=run_at.desc&limit=30');
      if (!runs.length) { box.textContent = 'No briefing runs yet.'; return; }
      if (!keepSelection || !runs.some((r) => String(r.id) === String(selectedId))) selectedId = runs[0].id;
      box.innerHTML = `
        <div class="bl-pick">
          <label class="bl-label" for="bl-select">Run</label>
          <select id="bl-select"></select>
        </div>
        <div id="bl-head" class="bl-head"></div>
        <div id="bl-body"></div>`;
      await show(selectedId);
    } catch (err) {
      box.innerHTML = `<p class="bad small">Could not load the briefing: ${esc(err.message)}</p>`;
    }
  }

  document.addEventListener('change', (e) => { if (e.target.id === 'bl-select') show(e.target.value); });
  document.addEventListener('click', (e) => { if (e.target.closest('#bl-retry')) retry(); });

  window.KB_BRIEFING = { refresh };
})();
