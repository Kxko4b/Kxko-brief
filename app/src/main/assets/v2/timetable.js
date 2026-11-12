/* Timetable tab (WebUntis via the untis-timetable function). Owner only, switched on in Settings;
   the function checks the login on the server. */
(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const api = () => window.kbApi;
  const esc = (v) => api().esc(v);

  let offset = 0; // days from today

  const canSee = () => api().isOwner() && api().moduleOn('timetable');

  function dateFor(off) {
    const d = new Date();
    d.setDate(d.getDate() + off);
    return d;
  }
  const yyyymmdd = (d) => `${d.getFullYear()}${String(d.getMonth() + 1).padStart(2, '0')}${String(d.getDate()).padStart(2, '0')}`;
  const label = (d) => d.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long' });

  const CHANGE_STYLE = {
    ENTFALL: { text: 'Cancelled', cls: 'bad' },
    VERTRETUNG: { text: 'Substitute', cls: 'warn' },
    WIEDER_NORMAL: { text: 'Back to normal', cls: 'ok' },
  };

  async function load() {
    if (!canSee()) return;
    const d = dateFor(offset);
    $('#tt-date').textContent = label(d);
    $('#tt-status').textContent = 'Loading timetable…';
    $('#tt-lessons').innerHTML = '';
    $('#tt-changes').innerHTML = '';
    $('#tt-homework').innerHTML = '';
    try {
      await api().ensureFresh();
      const data = await api().call(`/functions/v1/untis-timetable?date=${yyyymmdd(d)}`);
      if (!data?.success) throw new Error(data?.error || 'Unknown error');
      $('#tt-status').textContent = `${data.lessonsChecked ?? data.timetable.length} lessons from WebUntis`;
      renderChanges(data.changes || [], data.timetable || []);
      renderLessons(data.timetable || []);
      renderHomework(data.homework || []);
    } catch (err) {
      $('#tt-status').textContent = '';
      $('#tt-lessons').innerHTML = `<p class="bad small">Could not load the timetable: ${esc(err.message)}</p>`;
    }
  }

  // Changes are built from the lesson rows themselves, so they show even when the briefing already
  // stored the latest snapshot. Server changes are merged in too, without duplicates.
  function renderChanges(serverChanges, lessons) {
    const fromLessons = [];
    lessons.forEach((l) => {
      if (l.cancelled) fromLessons.push({ type: 'ENTFALL', time: l.time, subject: l.subject, details: `${l.subject} is cancelled.` });
      else if (l.changed) fromLessons.push({ type: 'VERTRETUNG', time: l.time, subject: l.subject,
        details: `${l.expected || l.subject}: ${l.expectedTeacher || 'normal teacher'} → ${l.substituteTeacher || l.teacher || 'substitute'}` });
    });
    const seen = new Set(fromLessons.map((c) => `${c.type}|${c.time}|${c.subject}`));
    const extra = serverChanges.filter((c) => !seen.has(`${c.type}|${c.time}|${c.subject}`));
    const all = [...fromLessons, ...extra];
    const box = $('#tt-changes');
    if (!all.length) { box.innerHTML = '<p class="muted small">No changes today.</p>'; return; }
    box.innerHTML = all.map((c) => {
      const s = CHANGE_STYLE[c.type] || { text: c.title || c.type, cls: '' };
      return `<div class="tt-change ${s.cls}"><strong>${esc(s.text)} · ${esc(c.time)}</strong><div class="small">${esc(c.details || c.subject || '')}</div></div>`;
    }).join('');
  }

  // Subject colour: from the Settings override first, then the keyword table in data/config.js.
  const subjectOf = (name) => api().subjectColor(name);

  function renderLessons(lessons) {
    const box = $('#tt-lessons');
    if (!lessons.length) { box.innerHTML = '<p class="muted small">No lessons on this day.</p>'; return; }
    box.innerHTML = lessons.map((l) => {
      const colour = subjectOf(l.expected || l.subject);
      const subject = l.changed && l.expected !== l.subject
        ? `${esc(l.expected)} → ${esc(l.subject)}`
        : esc(l.subject);
      const teacher = l.changed && l.substituteTeacher
        ? `<span class="warn">${esc(l.substituteTeacher)}</span> <span class="muted">(for ${esc(l.expectedTeacher || l.teacher || '?')})</span>`
        : esc(l.teacher || '');
      const badge = l.cancelled ? '<span class="tt-badge bad">Cancelled</span>'
        : l.changed ? '<span class="tt-badge warn">Substitute</span>' : '';
      const cross = l.cancelled ? '<svg class="tt-x" viewBox="0 0 100 100" preserveAspectRatio="none" aria-hidden="true"><line x1="0" y1="0" x2="100" y2="100"/><line x1="100" y1="0" x2="0" y2="100"/></svg>' : '';
      return `
        <div class="tt-lesson ${l.cancelled ? 'cancelled' : ''}" style="--subj:${esc(colour)}">
          <div class="tt-time">${esc(l.time)}</div>
          <div class="tt-main"><strong>${subject}</strong> ${badge}<div class="muted small">${teacher}${l.room ? ` · ${esc(l.room)}` : ''}</div></div>
          ${cross}
        </div>`;
    }).join('');
  }

  function renderHomework(items) {
    const box = $('#tt-homework');
    if (!items.length) { box.innerHTML = '<p class="muted small">No homework in the next days.</p>'; return; }
    box.innerHTML = items.map((h) => `
      <div class="tt-hw" style="--subj:${esc(subjectOf(h.subject))}">
        <strong>${esc(h.subject)}</strong> <span class="muted small">· due ${esc(h.dueDate || '')}</span>
        <div>${esc(h.text)}</div>
      </div>`).join('');
  }

  $('#tt-prev').addEventListener('click', () => { offset -= 1; load(); });
  $('#tt-next').addEventListener('click', () => { offset += 1; load(); });
  $('#tt-today').addEventListener('click', () => { offset = 0; load(); });

  window.KB_TIMETABLE = { refresh: () => { if (canSee()) load(); } };
})();
