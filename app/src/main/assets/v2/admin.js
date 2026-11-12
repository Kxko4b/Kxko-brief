/* Admin tab for the Kxko Brief web app. Owner only, switched on in Settings, and
   Supabase row-level security enforces access. Uses the signed-in session from app.js. */
(() => {
  'use strict';

  const BUCKET = 'diagram-images';
  const REQUEST_STATUSES = ['Pending', 'Accepted', 'In progress', 'Done', 'Cancelled'];
  const QUESTION_STATUSES = ['Pending', 'Answered'];
  const REQUEST_TYPES = ['Diagram', 'Model', 'Other'];

  const $ = (s) => document.querySelector(s);
  const api = () => window.kbApi;
  const esc = (v) => api().esc(v);
  const val = (v) => esc(v ?? '');
  const isAdmin = () => api().isOwner() && api().moduleOn('admin');
  const fmt = (iso) => (iso ? new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '');

  function setMsg(el, text, ok) { el.textContent = text; el.className = `admin-msg ${ok ? 'ok' : 'bad'}`; }

  // ---------- sub-tabs ----------
  let active = 'examples';
  document.querySelectorAll('[data-admin-tab]').forEach((btn) => btn.addEventListener('click', () => {
    const name = btn.dataset.adminTab;
    document.querySelectorAll('[data-admin-tab]').forEach((b) => b.classList.toggle('on', b === btn));
    ['examples', 'reviews', 'questions', 'requests'].forEach((t) => $(`#admin-${t}`).classList.toggle('hidden', t !== name));
    load(name);
  }));

  function load(name) {
    active = name;
    if (name === 'examples') loadExamples();
    if (name === 'reviews') loadReviews();
    if (name === 'questions') loadQuestions();
    if (name === 'requests') loadRequests();
  }

  function refresh() { if (isAdmin()) load(active); }

  // Edit blocks: a hidden form under each item, opened by its Edit button.
  const editBlock = (id, body) => `<div class="admin-edit hidden" data-edit-block="${esc(id)}">${body}</div>`;
  const toggleEdit = (id) => document.querySelector(`[data-edit-block="${id}"]`)?.classList.toggle('hidden');

  // ---------- examples ----------
  async function loadExamples() {
    const box = $('#examples-list');
    box.textContent = 'Loading…';
    try {
      const rows = await api().call('/rest/v1/examples?select=id,title,description,image_url,created_at&order=created_at.desc');
      if (!rows.length) { box.textContent = 'No examples yet.'; return; }
      box.innerHTML = rows.map((ex) => `
        <div class="admin-item">
          <div class="admin-row">
            <img class="admin-thumb" src="${esc(ex.image_url)}" alt="">
            <div class="admin-grow">
              <strong>${esc(ex.title)}</strong>
              <div class="muted small">${esc(ex.description || '')}</div>
              <div class="muted small">${fmt(ex.created_at)}</div>
            </div>
            <button class="btn small" data-edit-toggle="${esc(ex.id)}">Edit</button>
            <button class="btn small danger" data-del-example="${esc(ex.id)}">Delete</button>
          </div>
          ${editBlock(ex.id, `
            <label class="admin-label">Title<input id="ex-edit-title-${esc(ex.id)}" value="${val(ex.title)}" maxlength="120"></label>
            <label class="admin-label">Description<textarea id="ex-edit-desc-${esc(ex.id)}" maxlength="1000">${val(ex.description)}</textarea></label>
            <button class="btn small primary" data-save-example="${esc(ex.id)}">Save</button>`)}
        </div>`).join('');
    } catch (err) { box.textContent = `Could not load: ${err.message}`; }
  }

  $('#example-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const msg = $('#ex-msg'), btn = $('#ex-btn');
    const file = $('#ex-file').files[0];
    if (!file) return setMsg(msg, 'Choose an image first.', false);
    btn.disabled = true;
    setMsg(msg, 'Uploading…', true);
    try {
      await api().ensureFresh();
      const safe = file.name.replace(/[^a-zA-Z0-9._-]/g, '_');
      const path = `${Date.now()}-${safe}`;
      const res = await fetch(`${api().SB.url}/storage/v1/object/${BUCKET}/${encodeURIComponent(path)}`, {
        method: 'POST',
        headers: { apikey: api().SB.key, Authorization: `Bearer ${api().session().access_token}`, 'Content-Type': file.type || 'application/octet-stream' },
        body: file,
      });
      if (!res.ok) throw new Error(`upload failed (${res.status})`);
      const url = `${api().SB.url}/storage/v1/object/public/${BUCKET}/${encodeURIComponent(path)}`;
      await api().call('/rest/v1/examples', {
        method: 'POST',
        body: { title: $('#ex-title').value.trim(), description: $('#ex-desc').value.trim(), image_url: url },
        prefer: 'return=minimal',
      });
      $('#example-form').reset();
      setMsg(msg, 'Example added.', true);
      loadExamples();
    } catch (err) {
      setMsg(msg, `Upload failed: ${err.message}`, false);
    } finally { btn.disabled = false; }
  });

  // ---------- reviews ----------
  async function loadReviews() {
    const box = $('#reviews-list');
    box.textContent = 'Loading…';
    try {
      const rows = await api().call('/rest/v1/reviews?select=id,name,rating,message,created_at&order=created_at.desc');
      if (!rows.length) { box.textContent = 'No reviews yet.'; return; }
      box.innerHTML = rows.map((r) => `
        <div class="admin-item">
          <div class="admin-row">
            <div class="admin-grow">
              <strong>${esc(r.name || 'Anonymous')}</strong>
              <span class="muted small"> · ${'★'.repeat(Number(r.rating) || 0)} · ${fmt(r.created_at)}</span>
              <div>${esc(r.message || '')}</div>
            </div>
            <button class="btn small" data-edit-toggle="${esc(r.id)}">Edit</button>
            <button class="btn small danger" data-del-review="${esc(r.id)}">Delete</button>
          </div>
          ${editBlock(r.id, `
            <label class="admin-label">Name<input id="rv-name-${esc(r.id)}" value="${val(r.name)}" maxlength="80"></label>
            <label class="admin-label">Rating
              <select id="rv-rating-${esc(r.id)}">${[1, 2, 3, 4, 5].map((n) => `<option value="${n}" ${Number(r.rating) === n ? 'selected' : ''}>${n}</option>`).join('')}</select>
            </label>
            <label class="admin-label">Message<textarea id="rv-msg-${esc(r.id)}" maxlength="1000">${val(r.message)}</textarea></label>
            <button class="btn small primary" data-save-review="${esc(r.id)}">Save</button>`)}
        </div>`).join('');
    } catch (err) { box.textContent = `Could not load: ${err.message}`; }
  }

  // ---------- questions ----------
  async function loadQuestions() {
    const box = $('#questions-list');
    box.textContent = 'Loading…';
    try {
      const rows = await api().call('/rest/v1/questions?select=id,question,contact,status,answer,created_at&order=created_at.desc');
      if (!rows.length) { box.textContent = 'No questions yet.'; return; }
      box.innerHTML = rows.map((q) => {
        const statuses = [...new Set([q.status || 'Pending', ...QUESTION_STATUSES])];
        return `
        <div class="admin-item">
          <strong>${esc(q.question)}</strong>
          <div class="muted small">${esc(q.contact || 'no contact')} · ${fmt(q.created_at)}</div>
          <label class="admin-label">Answer<textarea data-answer="${esc(q.id)}">${val(q.answer)}</textarea></label>
          <div class="admin-row">
            <select data-q-status="${esc(q.id)}">${statuses.map((s) => `<option ${q.status === s ? 'selected' : ''}>${esc(s)}</option>`).join('')}</select>
            <button class="btn small primary" data-save-question="${esc(q.id)}">Save</button>
          </div>
        </div>`;
      }).join('');
    } catch (err) { box.textContent = `Could not load: ${err.message}`; }
  }

  // ---------- requests ----------
  async function loadRequests() {
    const box = $('#requests-list');
    box.textContent = 'Loading…';
    try {
      const rows = await api().call('/rest/v1/requests?select=id,name,email,type,size,description,status,price_offer,request_code,created_at&order=created_at.desc');
      if (!rows.length) { box.textContent = 'No requests yet.'; return; }
      const counts = {};
      rows.forEach((r) => { const k = r.status || 'Pending'; counts[k] = (counts[k] || 0) + 1; });
      const statTiles = [['All', rows.length], ...REQUEST_STATUSES.map((s) => [s, counts[s] || 0])]
        .map(([label, n]) => `<div class="biz-stat"><span>${esc(label)}</span><b>${n}</b></div>`).join('');
      box.innerHTML = `<div class="biz-stats">${statTiles}</div>` + rows.map((r) => {
        const statuses = [...new Set([r.status || 'Pending', ...REQUEST_STATUSES])];
        const types = [...new Set([r.type || 'Diagram', ...REQUEST_TYPES])];
        return `
        <div class="admin-item">
          <strong>${esc(r.name)}</strong>
          <span class="muted small"> · ${esc(r.request_code || '')} · ${fmt(r.created_at)}</span>
          <div class="muted small">${esc(r.email || 'no email')} · ${esc(r.type || '')} ${esc(r.size || '')}${r.price_offer ? ` · offer ${esc(r.price_offer)}` : ''}</div>
          <div>${esc(r.description || '')}</div>
          <div class="admin-row" style="margin-top:8px">
            <select data-req-status="${esc(r.id)}">${statuses.map((s) => `<option ${r.status === s ? 'selected' : ''}>${esc(s)}</option>`).join('')}</select>
            <button class="btn small primary" data-save-request-status="${esc(r.id)}">Save status</button>
            <button class="btn small" data-edit-toggle="${esc(r.id)}">Edit details</button>
          </div>
          ${editBlock(r.id, `
            <label class="admin-label">Name<input id="rq-name-${esc(r.id)}" value="${val(r.name)}" maxlength="80"></label>
            <label class="admin-label">Email<input id="rq-email-${esc(r.id)}" value="${val(r.email)}" maxlength="120"></label>
            <label class="admin-label">Type
              <select id="rq-type-${esc(r.id)}">${types.map((t) => `<option ${r.type === t ? 'selected' : ''}>${esc(t)}</option>`).join('')}</select>
            </label>
            <label class="admin-label">Size<input id="rq-size-${esc(r.id)}" value="${val(r.size)}" maxlength="40"></label>
            <label class="admin-label">Price offer<input id="rq-price-${esc(r.id)}" value="${val(r.price_offer)}" maxlength="40"></label>
            <label class="admin-label">Description<textarea id="rq-desc-${esc(r.id)}" maxlength="2000">${val(r.description)}</textarea></label>
            <button class="btn small primary" data-save-request="${esc(r.id)}">Save details</button>`)}
        </div>`;
      }).join('');
    } catch (err) { box.textContent = `Could not load: ${err.message}`; }
  }

  // ---------- one click handler for everything ----------
  document.addEventListener('click', async (e) => {
    const t = e.target;
    const id = (attr) => t.closest(`[${attr}]`)?.getAttribute(attr);
    const q = (sel) => document.querySelector(sel);

    const editId = id('data-edit-toggle');
    if (editId) return toggleEdit(editId);

    const delEx = id('data-del-example');
    if (delEx && confirm('Delete this example?')) {
      try { await api().call(`/rest/v1/examples?id=eq.${encodeURIComponent(delEx)}`, { method: 'DELETE' }); loadExamples(); } catch (err) { alert(err.message); }
      return;
    }
    const saveEx = id('data-save-example');
    if (saveEx) {
      try {
        await api().call(`/rest/v1/examples?id=eq.${encodeURIComponent(saveEx)}`, {
          method: 'PATCH', prefer: 'return=minimal',
          body: { title: q(`#ex-edit-title-${saveEx}`).value.trim(), description: q(`#ex-edit-desc-${saveEx}`).value.trim() },
        });
        loadExamples();
      } catch (err) { alert(err.message); }
      return;
    }

    const delRv = id('data-del-review');
    if (delRv && confirm('Delete this review?')) {
      try { await api().call(`/rest/v1/reviews?id=eq.${encodeURIComponent(delRv)}`, { method: 'DELETE' }); loadReviews(); } catch (err) { alert(err.message); }
      return;
    }
    const saveRv = id('data-save-review');
    if (saveRv) {
      try {
        await api().call(`/rest/v1/reviews?id=eq.${encodeURIComponent(saveRv)}`, {
          method: 'PATCH', prefer: 'return=minimal',
          body: { name: q(`#rv-name-${saveRv}`).value.trim(), rating: Number(q(`#rv-rating-${saveRv}`).value), message: q(`#rv-msg-${saveRv}`).value.trim() },
        });
        loadReviews();
      } catch (err) { alert(err.message); }
      return;
    }

    const saveQ = id('data-save-question');
    if (saveQ) {
      const answer = q(`[data-answer="${saveQ}"]`).value.trim();
      const status = q(`[data-q-status="${saveQ}"]`).value;
      try {
        await api().call(`/rest/v1/questions?id=eq.${encodeURIComponent(saveQ)}`, {
          method: 'PATCH', prefer: 'return=minimal',
          body: { answer, status, answered_at: status === 'Answered' ? new Date().toISOString() : null },
        });
        loadQuestions();
      } catch (err) { alert(err.message); }
      return;
    }

    const saveStatus = id('data-save-request-status');
    if (saveStatus) {
      try {
        await api().call(`/rest/v1/requests?id=eq.${encodeURIComponent(saveStatus)}`, {
          method: 'PATCH', prefer: 'return=minimal', body: { status: q(`[data-req-status="${saveStatus}"]`).value },
        });
        loadRequests();
      } catch (err) { alert(err.message); }
      return;
    }
    const saveReq = id('data-save-request');
    if (saveReq) {
      try {
        await api().call(`/rest/v1/requests?id=eq.${encodeURIComponent(saveReq)}`, {
          method: 'PATCH', prefer: 'return=minimal',
          body: {
            name: q(`#rq-name-${saveReq}`).value.trim(),
            email: q(`#rq-email-${saveReq}`).value.trim() || null,
            type: q(`#rq-type-${saveReq}`).value,
            size: q(`#rq-size-${saveReq}`).value.trim() || null,
            price_offer: q(`#rq-price-${saveReq}`).value.trim() || null,
            description: q(`#rq-desc-${saveReq}`).value.trim(),
          },
        });
        loadRequests();
      } catch (err) { alert(err.message); }
    }
  });

  window.KB_ADMIN = { refresh, isAdmin };
})();
