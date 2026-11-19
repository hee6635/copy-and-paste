'use strict';
/* 링크메모 화면. 데이터는 앱(Android 브리지)이 저장하고, 화면은 그리기만 한다. */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const A = window.Android || devBridge();

  let items = [];
    let query = '';
  let cur = null;          // 열린 메모 id
  let curTab = 'desc';
  let curCap = null;       // 열린 캡처 id
  let saveMode = 'video';
  let pendingUrl = '';
  const stack = [];        // 열린 화면 순서 (뒤로가기는 맨 위부터)

  const PLAT = { instagram: 'instagram', youtube: 'youtube', web: '웹' };
  const fileUrl = (id, f) => '/files/' + encodeURIComponent(id) + '/' + encodeURIComponent(f);
  const fmtT = t => { t = Math.max(0, Math.floor(t || 0)); return Math.floor(t / 60) + ':' + String(t % 60).padStart(2, '0'); };
  const fmtD = ms => { const d = new Date(ms); return (d.getMonth() + 1) + '.' + d.getDate(); };
  const fmtB = b => b > 1e9 ? (b / 1e9).toFixed(1) + 'GB' : Math.max(0, Math.round(b / 1e6)) + 'MB';
  const byId = id => items.find(x => x.id === id);

  // 폴더(2단계) · 홈 순서: meta = { folders:[{id,name,parent}], order:[id…] }
  let meta = { folders: [], order: [] };
  let fTop = '';           // 고른 큰 폴더 id ('' = 전체)
  let fSub = '';           // 고른 작은 폴더 id ('' = 큰 폴더 전체)
  let selecting = false;
  let cat = '';            // 고른 큰 키워드(갈래)
  const CAT_IC = {
    '여행': '<path d="M3 13l18-7-7 18-2-8z"/>',
    '맛집': '<path d="M7 3v8a2 2 0 0 0 4 0V3M9 11v10M16 3c-2 0-3 3-3 6s1 4 3 4v8"/>',
    '요리': '<path d="M4 11h16v2a7 7 0 0 1-14 0zM8 7c0-2 2-2 2-4M13 7c0-2 2-2 2-4"/>',
    '쇼핑': '<path d="M6 8h12l-1 12H7zM9 8a3 3 0 0 1 6 0"/>',
    '디자인': '<path d="M12 3a9 9 0 1 0 0 18c1 0 1.5-1 1-2s0-2 1.5-2H17a4 4 0 0 0 4-4c0-5-4-10-9-10z"/><circle cx="8" cy="11" r="1"/><circle cx="12" cy="7.5" r="1"/>',
    '개발·IT': '<path d="M8 8l-4 4 4 4M16 8l4 4-4 4M14 5l-4 14"/>',
    '공부': '<path d="M3 7l9-4 9 4-9 4zM7 9v5c3 2 7 2 10 0V9"/>',
    '운동·건강': '<path d="M4 9v6M20 9v6M7 7v10M17 7v10M7 12h10"/>',
    '영화·책': '<path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z"/>',
    '게임': '<rect x="3" y="8" width="18" height="10" rx="4"/><path d="M8 11v4M6 13h4M16 12h.01M18 14h.01"/>',
    '음악': '<path d="M9 18V5l11-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="17" cy="16" r="3"/>',
    '생활팁': '<path d="M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z"/>',
    '기타': '<circle cx="6" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="18" cy="12" r="1.5"/>',
  };
  function renderCats() {
    const n = {};
    items.forEach(it => { if (it.status === 'done' && it.category && inFilter(it)) n[it.category] = (n[it.category] || 0) + 1; });
    let list = Object.keys(n).sort((a, b) => n[b] - n[a]).slice(0, 8);
    if (cat && !list.includes(cat)) list = list.slice(0, 7).concat(cat);
    const el = $('#cats');
    el.classList.toggle('hide', list.length === 0);
    el.innerHTML = list.map(c => `<button class="kt${c === cat ? ' on' : ''}" data-cat="${esc(c)}"><svg class="ic" viewBox="0 0 24 24">${CAT_IC[c] || CAT_IC['기타']}</svg><b>${esc(c)}</b><small>${n[c] || 0}</small></button>`).join('');
  }
  const sel = new Set();

  function load() {
    try { items = JSON.parse(A.items()) || []; } catch (e) { items = []; }
    try { meta = Object.assign({ folders: [], order: [] }, JSON.parse(A.meta ? A.meta() : '{}')); } catch (e) {}
    renderHome();
  }
  function saveMeta() { A.setMeta && A.setMeta(JSON.stringify(meta)); }

  const fById = id => meta.folders.find(f => f.id === id);
  const tops = () => meta.folders.filter(f => !f.parent);
  const kids = id => meta.folders.filter(f => f.parent === id);
  function fPath(id) { const f = fById(id); if (!f) return ''; const p = f.parent && fById(f.parent); return p ? p.name + ' › ' + f.name : f.name; }

  function inFilter(it) {
    if (!fTop) return true;
    if (fSub) return it.folder === fSub;
    const f = fById(it.folder);
    return it.folder === fTop || (f && f.parent === fTop);
  }

  function matches(it) {
    if (!inFilter(it)) return false;
    if (cat && it.category !== cat) return false;
    if (!query) return true;
    const q = query.toLowerCase();
    const ai = it.ai || {};
    const hay = [it.title, it.author, it.desc, it.body, it.memo, it.url, fPath(it.folder),
      (ai.summary || []).map(x => String(x).replace(/\[\[|\]\]/g, '')).join(' '), (ai.course || []).join(' '), (ai.tips || []).map(x => x.text).join(' '), (ai.facts || []).map(x => x.text).join(' '), (ai.transcript || []).map(x => x.text).join(' '),
      ai.recipe ? (ai.recipe.ingredients || []).concat(ai.recipe.steps || []).join(' ') : '', (ai.checklist || []).join(' '), (it.tags || []).join(' '),
      (it.comments || []).map(c => c.a + ' ' + c.t + ' ' + (c.r || []).map(r => r.t).join(' ')).join(' '),
      (it.captures || []).map(c => c.text + ' ' + c.memo).join(' ')].join(' ').toLowerCase();
    return q.split(/\s+/).every(w => hay.includes(w));
  }

  /** 홈 순서: meta.order 순, 목록에 없는 새 메모는 맨 앞(최근 순) */
  function sorted(list) {
    const pos = new Map(meta.order.map((id, i) => [id, i]));
    const fresh = list.filter(x => !pos.has(x.id)).sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    const old = list.filter(x => pos.has(x.id)).sort((a, b) => pos.get(a.id) - pos.get(b.id));
    return fresh.concat(old);
  }

  function renderChips() {
    if (fTop && !fById(fTop)) { fTop = ''; fSub = ''; }
    if (fSub && !fById(fSub)) fSub = '';
    const el = $('#chips');
    const t = tops();
    el.classList.toggle('hide', t.length === 0);
    el.innerHTML = `<button class="pill${!fTop ? ' on' : ''}" data-ft="">전체</button>` +
      t.map(f => `<button class="pill${f.id === fTop ? ' on' : ''}" data-ft="${esc(f.id)}">${esc(f.name)}</button>`).join('');
    const k = fTop ? kids(fTop) : [];
    const el2 = $('#chips2');
    el2.classList.toggle('hide', k.length === 0);
    el2.innerHTML = k.length ? `<button class="pill sm${!fSub ? ' on' : ''}" data-fs="">모두</button>` +
      k.map(f => `<button class="pill sm${f.id === fSub ? ' on' : ''}" data-fs="${esc(f.id)}">${esc(f.name)}</button>`).join('') : '';
  }

  function jobHtml(it) {
    const err = it.status === 'error';
    const host = hostOf(it.url);
    const p = Math.max(0, Math.min(100, it.progress || 0));
    return `<div class="job${err ? ' err' : ''}" data-id="${esc(it.id)}">
      <div class="jt">${platIcon(it.platform)}</div>
      <div class="jb"><div class="jh"><b>${esc(err ? host : (it.stage || '저장 중'))}</b><span>${err ? esc(it.error || '실패') : p + '%'}</span></div>
      ${err ? '' : `<div class="bar"><i style="transform:scaleX(${p / 100})"></i></div>`}</div>
      ${err ? `<div class="jbtns"><button class="jbtn" data-diag="${esc(it.id)}">원인 검사</button><button class="jbtn" data-del="${esc(it.id)}">지우기</button><button class="jbtn dark" data-retry="${esc(it.id)}">다시</button></div>` : ''}
    </div>`;
  }

  function cardHtml(it) {
    const ratio = it.w && it.h ? Math.min(1.78, Math.max(.75, it.h / it.w)) : (it.platform === 'web' ? .62 : 1.35);
    const thumb = it.thumb ? `<div class="th" style="aspect-ratio:1/${ratio.toFixed(3)}"><img loading="lazy" src="${fileUrl(it.id, it.thumb)}" alt="">${it.kind && it.kind !== '웹' ? `<span class="badge">${esc(it.kind)}${it.duration ? ' ' + fmtT(it.duration) : ''}</span>` : ''}</div>` : '';
    const memo = it.memo ? `<div class="cm1">${esc(it.memo)}</div>` : '';
    const folder = it.folder && fById(it.folder) && !fSub ? `<span class="tag">${esc(fById(it.folder).name)}</span>` : '';
    return `<button class="card${sel.has(it.id) ? ' picked' : ''}" data-id="${esc(it.id)}"><span class="ck" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M6 12.5l4 4 8-9"/></svg></span>${thumb}<div class="cb"><div class="ct">${esc(it.title || it.url)}</div>
      <div class="cs">${esc(PLAT[it.platform] || '')}${it.author ? ' · ' + esc(it.author) : ''}</div>${memo}${folder}${it.aiBusy ? `<span class="tag busy">AI <span data-since="${it.aiBusyAt || Date.now()}">${since(it.aiBusyAt)}</span></span>` : ''}</div></button>`;
  }

  function visible() { return sorted(items.filter(it => it.status === 'done' && matches(it))); }

  function renderHome() {
    renderChips();
    renderCats();
    $('#jobs').innerHTML = selecting ? '' : items.filter(it => it.status === 'saving' || it.status === 'error').map(jobHtml).join('');
    const list = visible();
    // 두 줄에 높이가 비슷하게 나누기 (순서대로 왼쪽 · 오른쪽 중 짧은 쪽)
    const cols = [[], []], h = [0, 0];
    list.forEach(it => {
      const est = (it.thumb ? (it.w && it.h ? Math.min(1.78, Math.max(.75, it.h / it.w)) : 1.2) * 170 : 0) + 90 + (it.memo ? 34 : 0);
      const k = h[0] <= h[1] ? 0 : 1; cols[k].push(it); h[k] += est;
    });
    const colsEl = $('#grid').children;
    colsEl[0].innerHTML = cols[0].map(cardHtml).join('');
    colsEl[1].innerHTML = cols[1].map(cardHtml).join('');
    $('#empty').classList.toggle('hide', items.length > 0 || !!query);
    document.body.classList.toggle('selecting', selecting);
    $('#selCount').textContent = sel.size + '개 선택';
    $('#selBar').querySelectorAll('button').forEach(b => { if (b.id !== 'sOrder') b.disabled = sel.size === 0; });
  }

  // ── 여러 개 고르기 ──
  function startSelect(id) {
    if (selecting) return;
    selecting = true; sel.clear(); if (id) sel.add(id);
    stack.push({ type: 'mode', id: 'select' });
    renderHome();
  }
  function endSelect() {
    selecting = false; sel.clear();
    const i = stack.findIndex(s => s.type === 'mode'); if (i >= 0) stack.splice(i, 1);
    renderHome();
  }
  function toggleSel(id) {
    if (sel.has(id)) sel.delete(id); else sel.add(id);
    const c = document.querySelector(`.card[data-id="${CSS.escape(id)}"]`); if (c) c.classList.toggle('picked', sel.has(id));
    $('#selCount').textContent = sel.size + '개 선택';
    $('#selBar').querySelectorAll('button').forEach(b => { if (b.id !== 'sOrder') b.disabled = sel.size === 0; });
  }

  // 꾹 누르기 (0.45초)
  let lpT = 0, lpX = 0, lpY = 0, lpFired = false;
  document.addEventListener('pointerdown', e => {
    const c = e.target.closest('.card'); const ch = e.target.closest('[data-ft],[data-fs]');
    const cp = e.target.closest('#dScroll [data-cap]');
    lpFired = false;
    if (cp) { lpX = e.clientX; lpY = e.clientY; clearTimeout(lpT); lpT = setTimeout(() => { lpFired = true; openCapSel(cp.dataset.cap); }, 450); return; }
    if (!c && !(ch && (ch.dataset.ft || ch.dataset.fs))) return;
    lpX = e.clientX; lpY = e.clientY;
    clearTimeout(lpT);
    lpT = setTimeout(() => {
      lpFired = true;
      if (c) { if (!selecting) startSelect(c.dataset.id); else toggleSel(c.dataset.id); }
      else folderOptions(ch.dataset.ft || ch.dataset.fs);
    }, 450);
  });
  document.addEventListener('pointermove', e => { if (Math.abs(e.clientX - lpX) + Math.abs(e.clientY - lpY) > 12) clearTimeout(lpT); });
  document.addEventListener('pointerup', () => clearTimeout(lpT));
  document.addEventListener('pointercancel', () => clearTimeout(lpT));
  document.addEventListener('contextmenu', e => { if (e.target.closest('.card,.pill')) e.preventDefault(); });

  // ── 폴더 고르기 창 (옮기기) ──
  let moveIds = [];
  function openMove(ids) {
    moveIds = ids;
    renderMove();
    openSheet('moveSheet');
  }
  function renderMove() {
    const cur1 = moveIds.length === 1 ? (byId(moveIds[0]) || {}).folder : null;
    const row = (f, sub) => `<div class="frow${sub ? ' sub' : ''}"><button class="fpick${cur1 === f.id ? ' on' : ''}" data-mv="${esc(f.id)}"><span class="ficon">${sub ? '' : '<svg class="ic" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>'}</span>${esc(f.name)}</button>${sub ? '' : `<button class="ib fadd" data-fnew="${esc(f.id)}" aria-label="${esc(f.name)} 안에 폴더 만들기"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button>`}</div>`;
    $('#mvList').innerHTML = `<div class="frow"><button class="fpick${cur1 === '' || cur1 === undefined && moveIds.length === 1 ? ' on' : ''}" data-mv=""><span class="ficon"><svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M7 17L17 7"/></svg></span>폴더 없음</button></div>` +
      tops().map(f => row(f, false) + kids(f.id).map(k => row(k, true)).join('')).join('');
    $('#mvTitle').textContent = moveIds.length > 1 ? moveIds.length + '개를 옮길 폴더' : '폴더';
  }
  function moveTo(fid) {
    const ids = moveIds.slice();
    ids.forEach(id => { const it = byId(id); if (it) it.folder = fid; });
    A.updateMany ? A.updateMany(JSON.stringify(ids), JSON.stringify({ folder: fid })) : ids.forEach(id => A.update(id, JSON.stringify({ folder: fid })));
    closeSheet('moveSheet');
    toast(fid ? fPath(fid) + '(으)로 옮겼어요' : '폴더에서 뺐어요');
    if (selecting) endSelect(); else renderHome();
    if (cur && $('#detail').classList.contains('open')) renderDetail(false);
  }
  function newFolder(parent) {
    ask(parent ? fById(parent).name + ' 안에 새 폴더' : '새 폴더', '폴더 이름', v => {
      v = v.trim(); if (!v) return;
      const f = { id: 'f' + Date.now().toString(36), name: v.slice(0, 20), parent: parent || null };
      meta.folders.push(f); saveMeta();
      renderChips();
      if (moveIds.length) setTimeout(() => moveTo(f.id), 50);
    });
  }
  function folderOptions(fid) {
    const f = fById(fid); if (!f) return;
    optSheet(fPath(fid), [
      ['이름 바꾸기', () => ask('폴더 이름 바꾸기', '폴더 이름', v => { v = v.trim(); if (v) { f.name = v.slice(0, 20); saveMeta(); renderHome(); } }, f.name)],
      ...(!f.parent ? [['안에 폴더 만들기', () => { moveIds = []; newFolder(f.id); }]] : []),
      ['앞으로', () => { const i = meta.folders.indexOf(f); const prev = meta.folders.slice(0, i).reverse().find(x => (x.parent || null) === (f.parent || null)); if (prev) { meta.folders.splice(i, 1); meta.folders.splice(meta.folders.indexOf(prev), 0, f); saveMeta(); renderChips(); } }],
      ['폴더 지우기', () => confirmAsk(f.name + ' 폴더를 지울까요? (안의 메모는 남아요)', '지우기', () => {
        const gone = [f.id].concat(kids(f.id).map(k => k.id));
        const to = f.parent || '';
        const ids = items.filter(it => gone.includes(it.folder)).map(it => it.id);
        ids.forEach(id => byId(id).folder = to);
        if (ids.length) A.updateMany(JSON.stringify(ids), JSON.stringify({ folder: to }));
        meta.folders = meta.folders.filter(x => !gone.includes(x.id)); saveMeta();
        if (gone.includes(fTop)) { fTop = ''; fSub = ''; } if (gone.includes(fSub)) fSub = '';
        renderHome();
      }), 'danger'],
    ]);
  }

  // 고르는 창 (여러 줄)
  function optSheet(title, rows) {
    $('#optTitle').textContent = title;
    $('#optList').innerHTML = rows.map((r, i) => `<button class="orow${r[2] ? ' ' + r[2] : ''}" data-opt="${i}">${esc(r[0])}</button>`).join('');
    optRows = rows;
    openSheet('optSheet');
  }
  let optRows = [];

  // ── 순서 바꾸기 ──
  let ordList = [];
  function openOrder() {
    ordList = visible().map(it => it.id);
    renderOrder();
    openPage('order');
  }
  function renderOrder() {
    $('#ordList').innerHTML = ordList.map(id => { const it = byId(id); return `<div class="orow2" data-oid="${esc(id)}">
      <div class="ot">${it.thumb ? `<img src="${fileUrl(id, it.thumb)}" alt="">` : platIcon(it.platform)}</div>
      <div class="ob"><b>${esc(it.title || it.url)}</b><span>${esc(fPath(it.folder) || PLAT[it.platform] || '')}</span></div>
      <div class="grip" aria-label="끌어서 옮기기"><svg class="ic" viewBox="0 0 24 24"><path d="M5 9h14M5 15h14"/></svg></div></div>`; }).join('');
  }
  function saveOrder() {
    // 지금 보이는 것들의 자리(전체 순서 안)는 그대로 두고, 그 자리들에 새 순서로 채움
    const full = sorted(items.filter(it => it.status === 'done')).map(it => it.id);
    const setV = new Set(ordList);
    const slots = []; full.forEach((id, i) => { if (setV.has(id)) slots.push(i); });
    slots.forEach((slot, k) => { full[slot] = ordList[k]; });
    meta.order = full; saveMeta();
    renderHome();
  }
  (function dragSetup() {
    let drag = null;
    document.addEventListener('pointerdown', e => {
      const g = e.target.closest('.grip'); if (!g) return;
      const row = g.closest('.orow2'); const list = $('#ordList');
      const rows = [...list.children]; const h = row.offsetHeight + 8;
      drag = { row, rows, h, from: rows.indexOf(row), to: rows.indexOf(row), y0: e.clientY };
      row.classList.add('drag'); g.setPointerCapture(e.pointerId); e.preventDefault();
    });
    document.addEventListener('pointermove', e => {
      if (!drag) return;
      const dy = e.clientY - drag.y0;
      drag.row.style.transform = `translateY(${dy}px) scale(1.02)`;
      const to = Math.max(0, Math.min(drag.rows.length - 1, drag.from + Math.round(dy / drag.h)));
      if (to !== drag.to) {
        drag.to = to;
        drag.rows.forEach((r, i) => {
          if (r === drag.row) return;
          let shift = 0;
          if (drag.from < to && i > drag.from && i <= to) shift = -drag.h;
          if (drag.from > to && i < drag.from && i >= to) shift = drag.h;
          r.style.transform = shift ? `translateY(${shift}px)` : '';
        });
      }
    });
    const end = () => {
      if (!drag) return;
      const d = drag; drag = null;
      const id = ordList.splice(d.from, 1)[0]; ordList.splice(d.to, 0, id);
      d.row.style.transform = `translateY(${(d.to - d.from) * d.h}px)`;
      setTimeout(() => { renderOrder(); }, 180);
    };
    document.addEventListener('pointerup', end);
    document.addEventListener('pointercancel', end);
  })();

  function hostOf(u) { try { return new URL(u).hostname.replace(/^www\./, ''); } catch (e) { return u; } }
  function platIcon(p) {
    if (p === 'youtube') return '<svg class="ic" viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="12" rx="4"/><path d="M10.5 9.5v5l4-2.5z"/></svg>';
    if (p === 'instagram') return '<svg class="ic" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="5"/><circle cx="12" cy="12" r="3.5"/></svg>';
    return '<svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M3.5 12h17M12 3.5c2.5 2.6 2.5 14.4 0 17M12 3.5c-2.5 2.6-2.5 14.4 0 17"/></svg>';
  }

  // ── 화면 열기 / 닫기 ──
  function openPage(id) {
    const el = $('#' + id);
    el.classList.add('open'); el.setAttribute('aria-hidden', 'false');
    if (stack.filter(s => s.type === 'page').length === 0) $('#home').classList.add('behind');
    stack.push({ type: 'page', id });
  }
  function closePage(id) {
    const el = $('#' + id);
    el.classList.remove('open'); el.setAttribute('aria-hidden', 'true');
    const i = stack.findIndex(s => s.id === id); if (i >= 0) stack.splice(i, 1);
    if (stack.filter(s => s.type === 'page').length === 0) $('#home').classList.remove('behind');
    if (id === 'detail') { const v = $('#dScroll video'); if (v) v.pause(); setTimeout(() => { if (!$('#detail').classList.contains('open')) { $('#dScroll').innerHTML = ''; cur = null; } }, 400); }
    if (id === 'capView') curCap = null;
  }
  function openSheet(id) {
    $('#' + id).classList.add('open'); $('#' + id).setAttribute('aria-hidden', 'false');
    $('#dim').classList.add('on');
    stack.push({ type: 'sheet', id });
  }
  function closeSheet(id) {
    $('#' + id).classList.remove('open'); $('#' + id).setAttribute('aria-hidden', 'true');
    const i = stack.findIndex(s => s.id === id); if (i >= 0) stack.splice(i, 1);
    if (!stack.some(s => s.type === 'sheet')) $('#dim').classList.remove('on');
    document.activeElement && document.activeElement.blur && document.activeElement.blur();
  }
  function closeTop() {
    const top = stack[stack.length - 1];
    if (!top) return false;
    if (top.type === 'sheet') closeSheet(top.id); else if (top.type === 'mode') endSelect(); else closePage(top.id);
    return true;
  }

  let toastT = 0;
  function toast(msg) {
    const t = $('#toast'); t.textContent = msg; t.classList.add('on');
    clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2000);
  }

  // ── 저장 창 ──
  function findUrl(text) { const m = String(text || '').match(/https?:\/\/[^\s"'<>]+/); return m ? m[0].replace(/[).,]+$/, '') : ''; }

  function openSave(url) {
    pendingUrl = url;
    const host = hostOf(url);
    const plat = /youtube\.com|youtu\.be/.test(host) ? 'youtube' : /instagram\.com/.test(host) ? 'instagram' : 'web';
    $('#ssIcon').innerHTML = platIcon(plat);
    $('#ssHost').textContent = PLAT[plat] === '웹' ? host : PLAT[plat];
    $('#ssUrl').textContent = url;
    $('#ssModeWrap').classList.toggle('hide', plat === 'web');
    setMode(plat === 'web' ? 'link' : 'video');
    $('#ssMemo').value = '';
    openSheet('saveSheet');
  }
  function setMode(m) {
    saveMode = m;
    const bs = $('#ssMode').querySelectorAll('button');
    bs.forEach(b => b.classList.toggle('on', b.dataset.v === m));
    $('#ssMode i').style.transform = m === 'video' ? 'none' : 'translateX(calc(100% + 8px))';
  }
  function doSave() {
    if (!pendingUrl) return;
    const r = JSON.parse(A.add(pendingUrl, saveMode, $('#ssMemo').value.trim()));
    closeSheet('saveSheet');
    $('#paste').value = ''; pasteState();
    if (r.dup) { toast('이미 저장한 링크예요'); const it = byId(r.dup); if (it && it.status === 'done') openDetail(r.dup); return; }
    items.unshift(r); renderHome();
    $('#home').scrollTo({ top: 0, behavior: 'smooth' });
  }

  function pasteState() { $('#pasteGo').classList.toggle('on', !!findUrl($('#paste').value)); }

  // ── 상세 ──
  function openDetail(id) {
    const it = byId(id); if (!it) return;
    cur = id; curTab = it.platform === 'web' ? 'body' : 'desc';
    renderDetail(true);
    $('#dScroll').scrollTop = 0;
    openPage('detail');
  }

  function renderDetail(full) {
    const it = byId(cur); if (!it) return;
    $('#dTopTitle').textContent = '';
    const sc = $('#dScroll');
    if (full || !sc.querySelector('.video, .meta')) {
      let media = '';
      if (it.video) {
        media = `<div class="video"><video id="dVideo" src="${fileUrl(it.id, it.video)}" ${it.thumb ? `poster="${fileUrl(it.id, it.thumb)}"` : ''} controls playsinline preload="metadata"></video>
          <button class="capbtn" id="dCap"><svg class="ic" viewBox="0 0 24 24"><path d="M4 8a1 1 0 0 1 1-1h2.5l1.5-2h6l1.5 2H19a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z"/><circle cx="12" cy="13" r="3.5"/></svg>캡처</button><div class="flash" id="dFlash"></div></div>`;
      } else if (it.thumb) {
        media = `<div class="video"><img src="${fileUrl(it.id, it.thumb)}" alt=""></div>`;
      }
      accOpen = {};
      sc.innerHTML = media + `<div class="meta"></div><div id="dAi"></div><div id="dTips"></div><div id="dCaps"></div>
        <button class="acc" data-acc="more">받아쓰기 · 설명 · 댓글 <span><svg class="ic" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></span></button>
        <div class="accb"><div id="dTabs"></div><div id="dBody"></div></div>
        <div class="sec"><label class="memo"><small>내 메모</small><textarea id="dMemo" rows="2"></textarea></label></div>`;
      $('#dMemo').value = it.memo || '';
      autoGrow($('#dMemo'));
    }
    sc.querySelector('.meta').innerHTML = `<div class="s">${esc(PLAT[it.platform] || '')}${it.author ? ' · ' + esc(it.author) : ''} · ${fmtD(it.createdAt)}</div>
      <h2>${esc(it.title || it.url)}</h2>
      <button class="fchip" id="dFolder"><svg class="ic" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>${esc(fPath(it.folder) || '폴더 없음')}<svg class="ic chev" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button>
      <div class="tags">${(it.tags || []).map(t => `<button class="tg" data-tag="${esc(t)}">#${esc(t)}</button>`).join('')}
      <button class="add" id="dAddTag" aria-label="태그 추가"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button></div>`;
    renderAi(it);
    renderTips(it);
    renderCaps(it);
    renderTabs(it);
    syncAcc();
    $('#aSave').disabled = !it.video;
  }

  // ── AI 정리 결과 ──
  function renderAi(it) {
    const el = $('#dAi'); if (!el) return;
    const ai = it.ai;
    if (it.aiBusy) { liveRender(it); return; }
    if (live) typeTok++;
    live = null;
    if (!ai) {
      el.innerHTML = it.aiError ? `<div class="sec"><div class="cmtx"><b>AI 정리를 못 했어요</b><div class="why pre">${esc(String(it.aiError))}</div>
        <div class="bt"><button class="chip dark" data-airedo>다시 정리</button><button class="chip" data-diag="${esc(it.id)}">원인 검사</button></div></div>${logHtml(it.aiLog, true)}</div>` : '';
      return;
    }
    let h = '';
    const sum = ai.summary || [];
    const sugg = ai.folder && ai.folder.trim() && fPath(it.folder) !== ai.folder.trim() ? ai.folder.trim() : '';
    if (sum.length || sugg) h += `<div class="sec"><div class="sh"><b>요약</b><button class="ai" data-airedo aria-label="다시 정리">AI ↻</button></div>
      ${ai.log ? `<button class="runinfo" data-runlog>${fmtMs(ai.log.total)} · ${esc(mLabel(ai.model))}${(ai.log.why || []).length ? ' · 오래 걸린 이유 ' + ai.log.why.length : ''}<svg class="ic" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button><div class="runlog hide">${logHtml(ai.log)}</div>` : ''}
      ${(ai.course || []).length ? `<div class="course">${ai.course.map((c, i) => (i ? '<em>→</em>' : '') + `<span>${i + 1} ${esc(c)}</span>`).join('')}</div>` : ''}
      ${sum.map((x, i) => sumCard(it, x, i)).join('')}
      ${sugg ? `<div class="sugg"><div class="t">폴더 추천 <b>${esc(sugg)}</b></div><button data-sugg>옮기기</button></div>` : ''}</div>`;
    const facts = (ai.facts || []).filter(f => f && f.text);
    if (facts.length) h += `<div class="sec"><div class="sh"><b>${ai.video ? '영상에서 뽑은 정보' : '핵심 정보'}</b><span class="ai">AI</span></div>
      ${(ai.facts || []).map((f, i) => !f || !f.text ? '' : `<button class="ts" ${+f.t >= 0 ? `data-seek="${+f.t}"` : ''}>${+f.t >= 0 ? `<b>${fmtT(f.t)}</b>` : '<b>·</b>'}<span>${esc(f.text)}</span></button>${attHtml(it, 'facts', i)}`).join('')}</div>`;
    const ck = it.checks || {};
    const rec = ai.kind === 'recipe' && ai.recipe ? ai.recipe : null;
    if (rec && (rec.ingredients || []).length) h += `<div class="sec"><div class="sh"><b>재료</b><span class="ai">AI</span></div>
      ${rec.ingredients.map((x, i) => `<button class="chk${(ck.i || []).includes(i) ? ' on' : ''}" data-chk="i:${i}"><i></i><span>${esc(x)}</span></button>`).join('')}</div>`;
    if (rec && (rec.steps || []).length) h += `<div class="sec"><div class="sh"><b>순서</b></div>${rec.steps.map((x, i) => `<div class="stepn"><b>${i + 1}</b><span>${esc(x)}</span></div>`).join('')}</div>`;
    if (ai.kind === 'checklist' && (ai.checklist || []).length) h += `<div class="sec"><div class="sh"><b>체크리스트</b><span class="ai">AI</span></div>
      ${ai.checklist.map((x, i) => `<button class="chk${(ck.c || []).includes(i) ? ' on' : ''}" data-chk="c:${i}"><i></i><span>${esc(x)}</span></button>`).join('')}</div>`;
    if (it.aiError) h += `<div class="sec"><div class="cmtx"><div class="why">다시 정리 실패: ${esc(String(it.aiError).split('\n')[0])}</div></div></div>`;
    el.innerHTML = h;
  }

  /** [[핵심]] → 노란 칩 */
  const rich = s => esc(s).replace(/\[\[(.+?)\]\]/g, '<span class="chip2">$1</span>');
  const plain = s => String(s || '').replace(/\[\[(.+?)\]\]/g, '$1');
  /** 요약 한 줄 = 카드 (붙은 첫 사진은 크게, 나머지는 작게) */
  function sumCard(it, x, i) {
    const cs = (it.captures || []).filter(c => c.at && c.at.sec === 'summary' && +c.at.i === i);
    const big = cs[0], rest = cs.slice(1);
    return `<div class="scard">${big ? `<button class="sbig" data-cap="${esc(big.id)}"><img src="${fileUrl(it.id, big.file)}" alt=""><span class="tc">${fmtT(big.t)}</span>${big.ai ? '<span class="aib">AI</span>' : ''}</button>` : ''}
      <div class="stx">${rich(x)}</div>${rest.length ? `<div class="att in">${rest.map(c => `<button class="shot" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}</button>`).join('')}</div>` : ''}</div>`;
  }

  // ── AI 가 쓰는 중: 설명 → 받아쓰기 → 중요한 줄 → 요약 카드 (주르륵) ──
  let live = null;
  function sliceKey(txt, key) {
    const i = txt.indexOf('"' + key + '"'); if (i < 0) return null;
    const keys = ['transcript', 'facts', 'summary', 'course', 'tips', 'keyshots', 'recipe', 'checklist', 'tags', 'folder', 'kind', 'category', 'star'];
    let end = txt.length;
    keys.forEach(k => { if (k === key) return; const j = txt.indexOf('"' + k + '"', i + 1); if (j > i && j < end) end = j; });
    return txt.slice(i, end);
  }
  const unq = s => { try { return JSON.parse('"' + s + '"'); } catch (e) { return s.replace(/\\"/g, '"'); } };
  function parsePartial(txt) {
    const out = { ts: [], facts: [], sum: [], course: [] };
    const objs = sl => sl ? [...sl.matchAll(/\{\s*"t"\s*:\s*(-?[\d.]+)\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}/g)].map(m => ({ t: +m[1], text: unq(m[2]) })) : [];
    const strs = sl => { if (!sl) return []; const b = sl.indexOf('['); if (b < 0) return []; return [...sl.slice(b).matchAll(/"((?:[^"\\]|\\.)*)"/g)].map(m => unq(m[1])); };
    out.ts = objs(sliceKey(txt, 'transcript'));
    out.facts = objs(sliceKey(txt, 'facts'));
    out.sum = strs(sliceKey(txt, 'summary'));
    out.course = strs(sliceKey(txt, 'course'));
    return out;
  }
  let typeQ = Promise.resolve(), typeTok = 0;
  function typeIn(el, text, cps) {
    const tok = typeTok;
    typeQ = typeQ.then(async () => {
      if (tok !== typeTok || !el.isConnected) { el.textContent = text; return; }
      el.classList.add('cursor');
      const step = Math.max(1, Math.round(cps / 30));
      for (let i = step; i < text.length; i += step) { if (tok !== typeTok || !el.isConnected) break; el.textContent = text.slice(0, i); await new Promise(r => setTimeout(r, 1000 / 30)); }
      el.textContent = text; el.classList.remove('cursor');
    });
    return typeQ;
  }
  function liveRender(it) {
    const el = $('#dAi');
    if (live && live.id === it.id && el.querySelector('#lv')) { $('#lvSt').textContent = it.aiBusy; return; }
    typeTok++; typeQ = Promise.resolve();
    live = { id: it.id, ts: 0, sum: 0, course: 0, phase: 'raw', facts: 0 };
    el.innerHTML = `<div id="lv"><div class="lvst"><span class="dot"></span><span id="lvSt">${esc(it.aiBusy)}</span><span class="elapsed" data-since="${it.aiBusyAt || Date.now()}">${since(it.aiBusyAt)}</span><button class="chip" data-aistop>중지</button></div>
      ${it.desc || it.body ? '<div class="lvbox" id="lvDesc"><h4>설명</h4><div class="desc"></div></div>' : ''}
      <div class="lvbox" id="lvTs"><h4>받아쓰기</h4><div id="lvTsList"><div class="watch"><i></i>영상 보는 중…</div></div></div>
      <div class="course" id="lvCourse"></div><div id="lvCards"></div></div>`;
    const d = el.querySelector('#lvDesc .desc'); if (d) typeIn(d, (it.desc || it.body || '').slice(0, 320), 400);
  }
  function collapse(box) { if (!box || box.classList.contains('gone')) return; box.style.maxHeight = box.scrollHeight + 'px'; box.offsetHeight; box.classList.add('gone'); }
  function liveUpdate(txt) {
    if (!live || !$('#lv')) return;
    const p = parsePartial(txt);
    const list = $('#lvTsList');
    if (p.ts.length && list.querySelector('.watch')) list.innerHTML = '';
    for (; live.ts < p.ts.length; live.ts++) {
      const x = p.ts[live.ts];
      const row = document.createElement('div'); row.className = 'tl'; row.dataset.t = x.t;
      row.innerHTML = `<b>${fmtT(x.t)}</b><span></span>`; list.appendChild(row);
      typeIn(row.querySelector('span'), x.text, 70);
    }
    // 정보가 나오면: 받아쓰기에서 그 시각 줄을 반짝
    if (p.facts.length > live.facts) {
      live.facts = p.facts.length;
      typeQ = typeQ.then(() => {
        const rows = [...document.querySelectorAll('#lvTsList .tl')];
        rows.forEach(r => { const hit = p.facts.some(f => f.t >= 0 && Math.abs(f.t - +r.dataset.t) < 2); r.classList.toggle('hi', hit); r.classList.toggle('lo', !hit); });
      });
    }
    // 요약이 나오기 시작하면: 설명 · 받아쓰기를 접고 카드로
    if (p.sum.length && live.phase === 'raw') {
      live.phase = 'sum';
      typeQ = typeQ.then(() => new Promise(r => setTimeout(r, 700))).then(() => { collapse($('#lvDesc')); collapse($('#lvTs')); });
    }
    const done = p.sum.length - (/"\s*\]/.test(sliceKey(txt, 'summary') || '') ? 0 : 1);  // 마지막 줄은 끝날 때까지 기다림
    for (; live.sum < Math.max(0, done); live.sum++) {
      const x = p.sum[live.sum];
      typeQ = typeQ.then(() => {
        const c = document.createElement('div'); c.className = 'scard pop'; c.innerHTML = '<div class="stx"></div>';
        $('#lvCards') && $('#lvCards').appendChild(c);
        const tx = c.querySelector('.stx'); tx.classList.add('cursor');
        return new Promise(async r => { const t = plain(x); for (let i = 2; i < t.length; i += 2) { tx.textContent = t.slice(0, i); await new Promise(z => setTimeout(z, 33)); } tx.innerHTML = rich(x); tx.classList.remove('cursor'); r(); });
      });
    }
    const cd = p.course.length - (/"\s*\]/.test(sliceKey(txt, 'course') || '') ? 0 : 1);
    for (; live.course < Math.max(0, cd); live.course++) {
      const i = live.course, name = p.course[i];
      typeQ = typeQ.then(() => new Promise(r => {
        const box = $('#lvCourse'); if (!box) return r();
        if (i) { const e = document.createElement('em'); e.textContent = '→'; box.appendChild(e); }
        const s2 = document.createElement('span'); s2.textContent = (i + 1) + ' ' + name; box.appendChild(s2);
        requestAnimationFrame(() => s2.classList.add('on')); setTimeout(r, 160);
      }));
    }
  }

  // 접기 · 펼치기 (상세를 열 때마다 접힌 상태로 시작)
  let accOpen = {};
  function syncAcc() { document.querySelectorAll('#dScroll [data-acc]').forEach(b => b.classList.toggle('open', !!accOpen[b.dataset.acc])); }
  function accHead(key, title, count) {
    return `<button class="acc" data-acc="${key}">${esc(title)} <span>${count != null ? count : ''}<svg class="ic" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></span></button>`;
  }
  /** 이 줄에 붙은 캡처들 */
  function attHtml(it, sec, i) {
    const cs = (it.captures || []).filter(c => c.at && c.at.sec === sec && +c.at.i === i);
    if (!cs.length) return '';
    return `<div class="att">${cs.map(c => `<button class="shot" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}</button>`).join('')}</div>`;
  }
  function renderTips(it) {
    const el = $('#dTips'); if (!el) return;
    const tips = (it.ai && it.ai.tips) || [];
    if (!tips.length) { el.innerHTML = ''; return; }
    const src = t => t.from === 'video' && +t.t >= 0 ? `<button data-seek="${+t.t}">영상 ${fmtT(t.t)}</button>` : t.from === 'video' ? '영상' : t.from === 'author' ? '작성자 답글' : t.who ? '@' + esc(String(t.who).replace(/^@/, '')) : '댓글';
    el.innerHTML = accHead('tips', '영상 · 댓글 꿀팁', tips.length) + `<div class="accb"><div class="sec">
      ${tips.map((t, i) => `<div class="tip"><span class="n">${i + 1}</span><div><p>${esc(t.text)}</p><small>${src(t)}</small></div></div>`).join('')}</div></div>`;
  }

  const mLabel = m => ({ 'gemini-flash-latest': '최신 flash', 'gemini-2.5-flash': '2.5 flash', 'gemini-flash-lite-latest': 'flash-lite' }[m] || m || '');
  function fmtMs(ms) { const s = Math.round((ms || 0) / 1000); return s >= 60 ? Math.floor(s / 60) + '분 ' + (s % 60) + '초' : s + '초'; }
  function since(at) { return fmtMs(Date.now() - (at || Date.now())) + '째'; }
  /** 정리 기록: 단계별 시간 · 토큰 · 오래 걸린 이유 */
  function logHtml(log, open) {
    if (!log) return '';
    const u = log.usage || {};
    const rows = (log.rows || []).map(r => `<div class="lr${r.ok ? '' : ' bad'}"><span>${esc(r.step)}</span><b>${fmtMs(r.ms)}</b><em>${esc(r.note || '')}</em></div>`).join('');
    const tok = u.totalTokenCount ? `<div class="lr"><span>토큰</span><b>${u.totalTokenCount}</b><em>입력 ${u.promptTokenCount || 0}${u.videoTokens ? ' (영상 ' + u.videoTokens + ')' : ''}${u.audioTokens ? ' (소리 ' + u.audioTokens + ')' : ''} · 생각 ${u.thoughtsTokenCount || 0} · 답 ${u.candidatesTokenCount || 0}</em></div>` : '';
    const why = (log.why || []).length ? `<div class="whys"><b>오래 걸린 이유</b>${log.why.map(w => `<div>· ${esc(w)}</div>`).join('')}</div>` : (log.total > 30000 ? '' : '<div class="whys"><div>· 특별히 늦은 곳 없음</div></div>');
    return `<div class="logbox${open ? ' open' : ''}"><div class="lr total"><span>전체</span><b>${fmtMs(log.total)}</b><em>${log.modelVersion ? '실제 모델 ' + esc(log.modelVersion) : ''}</em></div>${rows}${tok}${why}</div>`;
  }
  setInterval(() => document.querySelectorAll('[data-since]').forEach(e => { e.textContent = since(+e.dataset.since); }), 1000);

  /** "여행 › 나트랑" 경로의 폴더 id (없으면 만들기) */
  function ensureFolder(path) {
    const [a, b] = path.split(/\s*›\s*|\s*>\s*/).map(x => x.trim()).filter(Boolean);
    if (!a) return '';
    let top = tops().find(f => f.name === a);
    if (!top) { top = { id: 'f' + Date.now().toString(36), name: a.slice(0, 20), parent: null }; meta.folders.push(top); }
    if (!b) { saveMeta(); return top.id; }
    let k = kids(top.id).find(f => f.name === b);
    if (!k) { k = { id: 'f' + (Date.now() + 1).toString(36), name: b.slice(0, 20), parent: top.id }; meta.folders.push(k); }
    saveMeta(); return k.id;
  }

  function renderCaps(it) {
    const caps = it.captures || [];
    const el = $('#dCaps');
    if (!it.video && caps.length === 0) { el.innerHTML = ''; return; }
    el.innerHTML = accHead('caps', '캡처', caps.length) + `<div class="accb"><div class="sec"><div class="shots">
      ${caps.map(c => `<button class="shot" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}</button>`).join('')}
      ${it.video ? '<button class="shot plus" id="dCapAdd" aria-label="지금 장면 캡처"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button>' : ''}
    </div></div></div>`;
    syncAcc();
  }

  function renderTabs(it) {
    const tabs = it.platform === 'web'
      ? [['body', '본문']]
      : [['desc', '설명']].concat((it.ai && (it.ai.transcript || []).length) ? [['ts', '받아쓰기']] : [], [['cmt', '댓글 ' + (it.comments || []).length]]);
    if (!tabs.some(t => t[0] === curTab)) curTab = tabs[0][0];
    const idx = tabs.findIndex(t => t[0] === curTab);
    $('#dTabs').innerHTML = tabs.length < 2 ? '' : `<div class="tabs"><i style="width:calc((100% - 6px) / ${tabs.length});transform:translateX(${idx * 100}%)"></i>
      ${tabs.map(t => `<button data-tab="${t[0]}" class="${t[0] === curTab ? 'on' : ''}">${t[1]}</button>`).join('')}</div>`;
    let body = '';
    if (curTab === 'desc') {
      body = it.desc ? longText(it.desc) : '<div class="none">설명이 없어요</div>';
    } else if (curTab === 'ts') {
      body = it.ai.transcript.map(x => `<button class="ts" data-seek="${+x.t || 0}"><b>${fmtT(x.t)}</b><span>${esc(x.text)}</span></button>`).join('');
    } else if (curTab === 'body') {
      body = (it.desc ? `<p class="p" style="color:var(--sub);margin-bottom:12px">${esc(it.desc)}</p>` : '') + (it.body ? longText(it.body) : '<div class="none">본문을 읽지 못했어요</div>');
    } else {
      const cs = it.comments || [];
      const ref = it.platform === 'instagram' || it.platform === 'youtube';
      const tools = it.commentBusy ? '<div class="none">댓글 가져오는 중…</div>'
        : !cs.length && it.commentError ? `<div class="cmtx"><b>댓글을 못 가져왔어요</b><div class="why">${esc(String(it.commentError).split('\n')[0])}</div><div class="bt"><button class="chip dark" data-refetch>다시 가져오기</button><button class="chip" data-diag="${esc(it.id)}">원인 검사</button></div></div>`
        : !cs.length ? `<div class="cmtx"><div class="none">댓글이 없어요</div>${ref ? '<button class="chip" data-refetch>다시 가져오기</button>' : ''}</div>` : '';
      const ordered = cs.filter(c => c.star).concat(cs.filter(c => !c.star));
      body = tools || ordered.map((c, i) => `<div class="cmt${c.star ? ' star' : ''}"><div class="h"><b>${c.star ? '★ ' : ''}@${esc(c.a)}</b><span>♡ ${c.l || 0}</span></div><p>${esc(c.t)}</p>
        ${(c.r || []).map(r => `<div class="rep"><div class="h"><b>@${esc(r.a)}${r.own ? '<span class="own">작성자</span>' : ''}</b><span>♡ ${r.l || 0}</span></div><p>${esc(r.t)}</p></div>`).join('')}</div>`).join('') + (ref ? '<button class="refetch" data-refetch>댓글 다시 가져오기</button>' : '');
    }
    $('#dBody').innerHTML = `<div class="sec">${body}</div>`;
  }

  function longText(s) {
    if (s.length <= 600) return `<p class="p">${esc(s)}</p>`;
    return `<p class="p" data-long>${esc(s.slice(0, 600))}…</p><button class="more" data-more>더 보기</button>`;
  }

  function autoGrow(t) { t.style.height = 'auto'; t.style.height = t.scrollHeight + 'px'; }

  let memoT = 0;
  function saveMemo() {
    if (!cur) return;
    const v = $('#dMemo').value;
    const it = byId(cur); if (!it || it.memo === v) return;
    it.memo = v; A.update(cur, JSON.stringify({ memo: v }));
  }

  function capture() {
    const v = $('#dVideo'); if (!v || !v.videoWidth) { toast('영상을 먼저 재생해 주세요'); return; }
    const scale = Math.min(1, 1280 / Math.max(v.videoWidth, v.videoHeight));
    const c = document.createElement('canvas');
    c.width = Math.round(v.videoWidth * scale); c.height = Math.round(v.videoHeight * scale);
    c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
    const f = $('#dFlash'); f.classList.remove('go'); void f.offsetWidth; f.classList.add('go');
    v.pause();
    openCrop(c.toDataURL('image/jpeg', .92), v.currentTime);
  }

  // ── 잘라쓰기 ──
  let cropT = 0, cropSrc = '', cb = null;
  function openCrop(data, t) {
    cropT = t; cropSrc = data;
    $('#cropTitle').textContent = fmtT(t) + ' 잘라쓰기';
    const img = $('#cropImg');
    img.onload = () => requestAnimationFrame(() => {
      const r = img.getBoundingClientRect(), w = $('#cropWrap').getBoundingClientRect();
      cb = { x: r.left - w.left, y: r.top - w.top, w: r.width, h: r.height, ix: r.left - w.left, iy: r.top - w.top, iw: r.width, ih: r.height };
      const m = Math.min(r.width, r.height) * .12; cb.x += m; cb.y += m; cb.w -= 2 * m; cb.h -= 2 * m;
      drawBox();
    });
    img.src = data;
    openPage('crop');
  }
  function drawBox() { const b = $('#cropBox').style; b.left = cb.x + 'px'; b.top = cb.y + 'px'; b.width = cb.w + 'px'; b.height = cb.h + 'px'; }
  (function cropDrag() {
    let d = null;
    $('#cropWrap').addEventListener('pointerdown', e => {
      if (!cb) return;
      const hd = e.target.closest('[data-h]'); const inBox = e.target.closest('#cropBox');
      if (!hd && !inBox) return;
      d = { h: hd ? hd.dataset.h : 'move', x0: e.clientX, y0: e.clientY, b: Object.assign({}, cb) };
      e.target.setPointerCapture(e.pointerId); e.preventDefault();
    });
    $('#cropWrap').addEventListener('pointermove', e => {
      if (!d) return;
      const dx = e.clientX - d.x0, dy = e.clientY - d.y0, b = d.b, MIN = 40;
      let { x, y, w, h } = b;
      if (d.h === 'move') { x += dx; y += dy; }
      if (d.h.includes('l')) { x = Math.min(b.x + dx, b.x + b.w - MIN); w = b.x + b.w - x; }
      if (d.h.includes('r')) { w = Math.max(MIN, b.w + dx); }
      if (d.h.includes('t')) { y = Math.min(b.y + dy, b.y + b.h - MIN); h = b.y + b.h - y; }
      if (d.h.includes('b') && d.h !== 'move') { h = Math.max(MIN, b.h + dy); }
      // 그림 밖으로 못 나가게
      x = Math.max(cb.ix, Math.min(x, cb.ix + cb.iw - w)); y = Math.max(cb.iy, Math.min(y, cb.iy + cb.ih - h));
      w = Math.min(w, cb.ix + cb.iw - x); h = Math.min(h, cb.iy + cb.ih - y);
      Object.assign(cb, { x, y, w, h }); drawBox();
    });
    const end = () => { d = null; };
    $('#cropWrap').addEventListener('pointerup', end); $('#cropWrap').addEventListener('pointercancel', end);
  })();
  function cropResult(full) {
    const img = $('#cropImg');
    if (full || !cb) return cropSrc;
    const sx = img.naturalWidth / cb.iw, sy = img.naturalHeight / cb.ih;
    const c = document.createElement('canvas');
    c.width = Math.round(cb.w * sx); c.height = Math.round(cb.h * sy);
    c.getContext('2d').drawImage(img, (cb.x - cb.ix) * sx, (cb.y - cb.iy) * sy, c.width, c.height, 0, 0, c.width, c.height);
    return c.toDataURL('image/jpeg', .92);
  }

  // ── 붙일 곳 고르기 (새 캡처 · 옮기기 같이 씀) ──
  let attMode = null, attSel = '';
  function openAttach(mode) {
    attMode = mode;
    const it = byId(cur); const ai = it.ai || {};
    const t = mode.t != null ? mode.t : -1;
    // 시간이 가장 가까운 정보 줄을 미리 고름 (10초 안)
    let best = '', bd = 11;
    (ai.facts || []).forEach((f, i) => { if (+f.t >= 0 && t >= 0 && Math.abs(f.t - t) < bd) { bd = Math.abs(f.t - t); best = 'facts:' + i; } });
    attSel = mode.cur != null ? mode.cur : best;
    const row = (k, label) => `<button class="rad${attSel === k ? ' on' : ''}" data-att="${k}"><i></i><span>${esc(label)}</span></button>`;
    let h = '';
    if ((ai.summary || []).length) h += '<div class="agrp">요약</div>' + ai.summary.map((x, i) => row('summary:' + i, plain(x))).join('');
    if ((ai.facts || []).length) h += `<div class="agrp">${ai.video ? '영상에서 뽑은 정보' : '핵심 정보'}</div>` + ai.facts.map((f, i) => row('facts:' + i, (+f.t >= 0 ? fmtT(f.t) + ' ' : '') + f.text)).join('');
    h += '<div class="agrp">그 밖</div>' + row('', '캡처 칸에만 두기');
    $('#attList').innerHTML = h;
    $('#attImg').src = mode.img || '';
    $('#attInfo').textContent = mode.info || '';
    openSheet('attSheet');
  }
  function attValue(k) { if (!k) return ''; const [sec, i] = k.split(':'); return JSON.stringify({ sec, i: +i }); }
  function doAttach() {
    const m = attMode; if (!m) return;
    closeSheet('attSheet');
    const at = attValue(attSel);
    if (m.kind === 'new') {
      const r = A.capture(cur, m.t, m.data, at);
      if (r) { replace(JSON.parse(r)); }
      closePage('crop');
      toast(at ? '요약에 붙였어요' : '캡처 칸에 넣었어요');
    } else {
      const it = byId(cur);
      (it.captures || []).forEach(c => { if (m.ids.includes(c.id)) { if (at) c.at = JSON.parse(at); else delete c.at; } });
      A.update(cur, JSON.stringify({ captures: it.captures }));
      toast(m.ids.length + '개를 옮겼어요');
      if ($('#capsel').classList.contains('open')) closePage('capsel');
    }
    renderDetail(false);
    if (curCap) renderCap();
  }

  // ── 캡처 고르기 ──
  const capSel = new Set();
  function openCapSel(firstId) {
    capSel.clear(); if (firstId) capSel.add(firstId);
    renderCapSel(); openPage('capsel');
  }
  function renderCapSel() {
    const it = byId(cur); const caps = it.captures || [];
    $('#csGrid').innerHTML = caps.map(c => `<button class="shot${capSel.has(c.id) ? ' picked' : ''}" data-csel="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}<span class="ck2"></span></button>`).join('');
    $('#csCount').textContent = capSel.size + '개 선택';
    $('#csMove').disabled = $('#csDel').disabled = capSel.size === 0;
  }

  function itemText(it) {
    const L = [];
    L.push('📌 ' + (it.title || ''));
    L.push([PLAT[it.platform], it.author, new Date(it.createdAt).toLocaleDateString('ko-KR')].filter(Boolean).join(' · '));
    L.push('🔗 ' + it.url);
    if ((it.tags || []).length) L.push(it.tags.map(t => '#' + t).join(' '));
    if (it.memo) L.push('', '── 내 메모 ──', it.memo);
    if (it.ai && (it.ai.course || []).length) L.push('', '── 코스 ──', it.ai.course.join(' → '));
    if (it.ai && (it.ai.summary || []).length) L.push('', '── 요약 ──', ...it.ai.summary.map(x => '· ' + plain(x)));
    if (it.ai && (it.ai.facts || []).length) L.push('', '── 핵심 정보 ──', ...it.ai.facts.map(x => (+x.t >= 0 ? '[' + fmtT(x.t) + '] ' : '· ') + x.text));
    if (it.ai && (it.ai.tips || []).length) L.push('', '── 영상 · 댓글 꿀팁 ──', ...it.ai.tips.map((x, i) => (i + 1) + '. ' + x.text + (x.from === 'video' && +x.t >= 0 ? ' (영상 ' + fmtT(x.t) + ')' : x.from === 'author' ? ' (작성자)' : '')));
    if (it.ai && it.ai.recipe && (it.ai.recipe.ingredients || []).length) L.push('', '── 재료 ──', ...it.ai.recipe.ingredients.map(x => '· ' + x), '', '── 순서 ──', ...(it.ai.recipe.steps || []).map((x, i) => (i + 1) + '. ' + x));
    if (it.ai && (it.ai.checklist || []).length) L.push('', '── 체크리스트 ──', ...it.ai.checklist.map(x => '□ ' + x));
    if (it.desc) L.push('', '── 설명 ──', it.desc);
    if (it.body) L.push('', '── 본문 ──', it.body);
    const caps = (it.captures || []).filter(c => c.text || c.memo);
    if (caps.length) { L.push('', '── 캡처 ──'); caps.forEach(c => L.push('[' + fmtT(c.t) + '] ' + [c.text, c.memo].filter(Boolean).join(' / '))); }
    const cs = it.comments || [];
    if (cs.length) { L.push('', '── 댓글 ──'); cs.forEach((c, i) => { L.push((i + 1) + '. @' + c.a + ' (♡' + (c.l || 0) + ') ' + c.t); (c.r || []).forEach(r => L.push('   ↳ @' + r.a + (r.own ? ' [작성자]' : '') + ' ' + r.t)); }); }
    return L.join('\n');
  }

  // ── 캡처 보기 ──
  function openCap(capId) {
    const it = byId(cur); if (!it) return;
    curCap = capId;
    renderCap();
    openPage('capView');
  }
  function renderCap() {
    const it = byId(cur); const c = it && (it.captures || []).find(x => x.id === curCap); if (!c) return;
    $('#cTitle').textContent = '캡처 ' + fmtT(c.t);
    const sc = $('#cScroll');
    if (!sc.querySelector('#cMemo') || sc.dataset.cap !== c.id) {
      sc.dataset.cap = c.id;
      sc.innerHTML = `<div class="capimg"><img src="${fileUrl(it.id, c.file)}" alt=""></div>
        <div class="sec"><div class="ocr" id="cOcr"></div>
        <label class="memo" style="margin-top:8px"><small>메모</small><textarea id="cMemo" rows="1"></textarea></label>
        <div class="capat" id="cAt"></div></div>`;
      $('#cMemo').value = c.memo || ''; autoGrow($('#cMemo'));
    }
    $('#cOcr').innerHTML = `<small>화면 글자</small>${c.ocr === 'busy' ? '읽는 중…' : (c.text ? esc(c.text) : '글자가 없어요')}`;
    const ai = it.ai || {};
    const where = c.at ? (c.at.sec === 'summary' ? '요약: ' + plain((ai.summary || [])[c.at.i] || '') : '정보: ' + (((ai.facts || [])[c.at.i] || {}).text || '')) : '캡처 칸에만';
    $('#cAt').innerHTML = `<span>붙인 곳 <b>${esc(where)}</b></span><button class="chip" data-capmove>바꾸기</button>`;
  }
  function saveCapMemo() {
    const it = byId(cur); const c = it && (it.captures || []).find(x => x.id === curCap); if (!c) return;
    const v = $('#cMemo').value; if (c.memo === v) return;
    c.memo = v; A.update(cur, JSON.stringify({ captures: it.captures }));
  }

  // ── 설정 ──
  function openSettings() { renderSettings(); openPage('settings'); }
  function renderSettings() {
    let s = {}; try { s = JSON.parse(A.settings()); } catch (e) {}
    const qs = [['480', '480p'], ['720', '720p'], ['1080', '1080p'], ['best', '최고']];
    const qi = Math.max(0, qs.findIndex(q => q[0] === s.quality));
    const aiN = items.filter(x => x.status === 'done' && (!x.ai || !x.category)).length;
    $('#sScroll').innerHTML = `
      <div class="group">
        <button class="row" id="sAiOn" ${s.hasKey ? '' : 'disabled'}><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M12 3l1.8 4.7L18.5 9.5l-4.7 1.8L12 16l-1.8-4.7L5.5 9.5l4.7-1.8z"/></svg></span><span class="rt">AI 정리</span><span class="sw${s.aiOn ? ' on' : ''}"></span></button>
        <label class="field keyf"><input id="sKey" type="password" placeholder=" " autocomplete="off"><span>${s.hasKey ? 'Gemini API 키 (••••' + esc(s.keyTail) + ')' : 'Gemini API 키 붙여넣기'}</span></label>
        <button class="row" id="sWatch" ${s.hasKey ? '' : 'disabled'}><span class="icb"><svg class="ic" viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="12" rx="3"/><path d="M10.5 9.5v5l4-2.5z"/></svg></span><span class="rt">링크만 저장해도 AI가 영상 보기</span><span class="sw${s.watch ? ' on' : ''}"></span></button>
        <div class="row stack"><div class="rhead"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M13 3L4 14h7l-1 7 9-11h-7z"/></svg></span><span class="rt">AI 모델</span></div>
          <div class="seg" id="sSpeed"><i style="width:calc((100% - 6px) / 2);transform:translateX(${s.aiSpeed === 'smart' ? 100 : 0}%)"></i><button data-sp="fast" class="${s.aiSpeed !== 'smart' ? 'on' : ''}">빠르게</button><button data-sp="smart" class="${s.aiSpeed === 'smart' ? 'on' : ''}">꼼꼼하게</button></div></div>
        <div class="row"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M3 12h4l3-8 4 16 3-8h4"/></svg></span><span class="rt">이번 달 사용</span><span class="rv">${s.aiUsed || 0}회</span></div>
        ${s.batteryOpt ? `<button class="row" id="sBat"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><rect x="3" y="7" width="16" height="10" rx="2"/><path d="M21 11v2M7 10v4"/></svg></span><span class="rt">배터리 최적화 끄기</span><span class="rv">뒤에서도 빠르게</span></button>` : ''}
        <button class="row" id="sAiAll"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/></svg></span><span class="rt">저장한 것 AI 정리</span><span class="rv">${aiN}개</span></button>
      </div>
      <div class="group">
        <button class="row" id="sIg"><span class="icb">${platIcon('instagram')}</span><span class="rt">인스타 로그인</span><span class="rv">${s.igLogin ? '로그인됨' : '안 됨'}</span></button>
      </div>
      <div class="group">
        <div class="row stack"><div class="rhead"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="12" rx="3"/><path d="M10.5 9.5v5l4-2.5z"/></svg></span><span class="rt">영상 화질</span></div>
          <div class="seg" id="sQ"><i style="width:calc((100% - 6px) / 4);transform:translateX(${qi * 100}%)"></i>${qs.map((q, i) => `<button data-q="${q[0]}" class="${i === qi ? 'on' : ''}">${q[1]}</button>`).join('')}</div></div>
      </div>
      <div class="group">
        <div class="row"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M4 14h16"/></svg></span><span class="rt">저장 공간</span><span class="rv">${fmtB(s.used || 0)}</span></div>
        <button class="row" id="sDelVid"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg></span><span class="rt">영상만 지우기</span></button>
      </div>
      <div class="group">
        <button class="row" id="sTool"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/></svg></span><span class="rt">영상 도구 업데이트</span><span class="rv">${esc(s.tool || '')}</span></button>
        <button class="row" id="sDiag"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M9 11l2 2 4-4"/><rect x="4" y="4" width="16" height="16" rx="4"/></svg></span><span class="rt">검사</span></button>
        <div class="row"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/></svg></span><span class="rt">앱 버전</span><span class="rv">${esc(s.app || '')}</span></div>
      </div>`;
    $('#sKey').onchange = e => { const v = e.target.value.trim(); if (!v) return; A.setGeminiKey(v); A.setAiOn(true); e.target.value = ''; toast('키를 저장했어요'); renderSettings(); };
    $('#sIg').onclick = () => {
      if (s.igLogin) confirmAsk('인스타에서 로그아웃할까요?', '로그아웃', () => A.igLogout());
      else A.igLogin();
    };
  }

  // ── 검사 ──
  let diagSteps = [], diagRunning = false, diagItem = null;
  function openDiag(url, it, auto) {
    diagItem = it || null;
    $('#dgUrl').value = url || '';
    diagSteps = []; renderDiag();
    openPage('diag');
    if (auto && url) setTimeout(runDiag, 400);
  }
  function runDiag() {
    const u = findUrl($('#dgUrl').value);
    if (!u) { toast('링크를 넣어 주세요'); return; }
    if (diagRunning) return;
    diagRunning = true; diagSteps = []; renderDiag();
    $('#dgGo').classList.remove('on');
    A.diag(u);
  }
  function renderDiag() {
    $('#dgSteps').classList.toggle('hide', !diagSteps.length);
    $('#dgSteps').innerHTML = diagSteps.map(s => `<div class="step ${s.state}"><div class="dot">${s.state === 'ok' ? '✓' : s.state === 'fail' ? '✕' : s.state === 'skip' ? '–' : s.state === 'warn' ? '!' : ''}</div>
      <div class="sb"><b>${esc(s.name)}</b><div class="sm">${esc(s.msg || (s.state === 'run' ? '확인 중…' : ''))}</div>${s.state === 'fail' && s.detail ? `<pre>${esc(s.detail)}</pre>` : ''}</div></div>`).join('');
  }
  function diagText() {
    const L = ['[링크메모 검사] ' + $('#dgUrl').value];
    diagSteps.forEach(s => { L.push((s.state === 'ok' ? '✓ ' : s.state === 'fail' ? '✕ ' : s.state === 'warn' ? '! ' : '– ') + s.name + ' : ' + (s.msg || '')); if ((s.state === 'fail' || s.state === 'ok') && s.detail && s.detail.includes('\n')) L.push('   ' + s.detail.replace(/\n/g, '\n   ')); });
    if (diagItem && diagItem.errorDetail) L.push('', '[저장 오류]', diagItem.errorDetail);
    if (diagItem && diagItem.commentError) L.push('', '[댓글 오류]', diagItem.commentError);
    return L.join('\n');
  }

  // ── 작은 창 ──
  let askCb = null;
  function ask(title, label, cb, val) {
    askCb = cb; $('#askTitle').textContent = title; $('#askLabel').textContent = label; $('#askInput').value = val || '';
    openSheet('askSheet'); setTimeout(() => $('#askInput').focus(), 350);
  }
  let cfCb = null;
  function confirmAsk(title, yes, cb) {
    cfCb = cb; $('#cfTitle').textContent = title; $('#cfYes').textContent = yes || '지우기';
    openSheet('confirmSheet');
  }

  function replace(o) {
    const i = items.findIndex(x => x.id === o.id);
    if (i >= 0) items[i] = o; else items.unshift(o);
  }

  // ── 앱에서 오는 소식 ──
  window.App = {
    onItem(json) {
      const o = JSON.parse(json);
      const before = byId(o.id);
      // 화면에서 쓰는 중인 메모는 덮어쓰지 않음
      if (cur === o.id && document.activeElement && document.activeElement.id === 'dMemo') o.memo = $('#dMemo').value;
      replace(o);
      if (before && before.status === 'saving' && o.status === 'done') toast('저장했어요');
      renderHome();
      if (cur === o.id && $('#detail').classList.contains('open')) {
        renderCaps(o);
        if (!document.activeElement || document.activeElement.id !== 'dMemo') renderDetail(false);
        if (curCap) renderCap();
      }
    },
    onShare(text) {
      const url = findUrl(text);
      if (!url) { toast('링크를 찾지 못했어요'); return; }
      while (stack.length && stack[stack.length - 1].type === 'sheet') closeTop();
      openSave(url);
    },
    onBack() { return closeTop(); },
    onPartial(id, txt) { if (cur === id && $('#detail').classList.contains('open')) liveUpdate(txt); },
    onDiag(json) {
      const o = JSON.parse(json);
      if (o.end) { diagRunning = false; $('#dgGo').classList.add('on'); if (o.error) toast('검사 중 오류'); const bad = diagSteps.filter(s => s.state === 'fail').length; toast(bad ? '✕ ' + bad + '곳에서 막혔어요' : '모두 통과했어요'); return; }
      const i = diagSteps.findIndex(s => s.i === o.i);
      if (i >= 0) diagSteps[i] = o; else diagSteps.push(o);
      renderDiag();
    },
    onResume() { load(); if ($('#settings').classList.contains('open')) renderSettings(); },
    insets(top, bottom) {
      document.documentElement.style.setProperty('--st', top + 'px');
      document.documentElement.style.setProperty('--sb', bottom + 'px');
    },
  };

  // ── 누르기 ──
  document.addEventListener('click', e => {
    const t = e.target;
    if (lpFired) { lpFired = false; return; }
    const card = t.closest('.card'); if (card) return selecting ? toggleSel(card.dataset.id) : openDetail(card.dataset.id);
    const ct = t.closest('[data-cat]'); if (ct) { cat = cat === ct.dataset.cat ? '' : ct.dataset.cat; renderHome(); return; }
    const ft = t.closest('[data-ft]'); if (ft) { fTop = ft.dataset.ft; fSub = ''; renderHome(); return; }
    const fs = t.closest('[data-fs]'); if (fs) { fSub = fs.dataset.fs; renderHome(); return; }
    const mv = t.closest('[data-mv]'); if (mv) return moveTo(mv.dataset.mv);
    const fn = t.closest('[data-fnew]'); if (fn) return newFolder(fn.dataset.fnew);
    if (t.closest('#mvNew')) return newFolder(null);
    const op = t.closest('[data-opt]'); if (op) { const r = optRows[+op.dataset.opt]; closeSheet('optSheet'); setTimeout(() => r && r[1](), 250); return; }
    if (t.closest('#selX')) return endSelect();
    if (t.closest('#selAll')) { const v = visible(); if (sel.size === v.length) sel.clear(); else v.forEach(it => sel.add(it.id)); renderHome(); return; }
    if (t.closest('#sMove')) return openMove([...sel]);
    if (t.closest('#sOrder')) return openOrder();
    if (t.closest('#sDel')) return confirmAsk(sel.size + '개를 지울까요?', '지우기', () => {
      const ids = [...sel]; A.removeMany(JSON.stringify(ids)); items = items.filter(x => !ids.includes(x.id));
      meta.order = meta.order.filter(id => !ids.includes(id)); saveMeta(); endSelect(); toast(ids.length + '개를 지웠어요');
    });
    if (t.closest('#ordDone')) { saveOrder(); closePage('order'); if (selecting) endSelect(); toast('순서를 바꿨어요'); return; }
    if (t.closest('#dFolder')) return openMove([cur]);
    if (t.closest('[data-sugg]')) { const it = byId(cur); const fid = ensureFolder(it.ai.folder); if (fid) { moveIds = [cur]; moveTo(fid); renderChips(); } return; }
    if (t.closest('[data-aistop]')) { A.aiCancel(); toast('중지하는 중…'); return; }
    const rl = t.closest('[data-runlog]'); if (rl) { rl.nextElementSibling.classList.toggle('hide'); rl.classList.toggle('open'); return; }
    if (t.closest('[data-airedo]')) { const it = byId(cur); it.aiBusy = 'AI 정리 준비 중'; it.aiBusyAt = Date.now(); it.aiError = ''; renderAi(it); A.aiRedo(cur); return; }
    const chk = t.closest('[data-chk]'); if (chk) {
      const it = byId(cur); const [k, i] = chk.dataset.chk.split(':'); const n = +i;
      it.checks = it.checks || {}; const a = it.checks[k] = it.checks[k] || [];
      const j = a.indexOf(n); if (j >= 0) a.splice(j, 1); else a.push(n);
      chk.classList.toggle('on', a.includes(n)); A.update(cur, JSON.stringify({ checks: it.checks })); return;
    }
    const sk = t.closest('[data-seek]'); if (sk) {
      const v = $('#dVideo'); const sec = Math.floor(+sk.dataset.seek); const it = byId(cur);
      if (v) { v.currentTime = sec; v.play(); $('#dScroll').scrollTo({ top: 0, behavior: 'smooth' }); }
      else if (it && it.platform === 'youtube') { const m = it.url.match(/(?:youtu\.be\/|\/shorts\/|[?&]v=)([A-Za-z0-9_-]{11})/); A.open(m ? 'https://www.youtube.com/watch?v=' + m[1] + '&t=' + sec + 's' : it.url); }
      else toast('영상을 저장하지 않아서 ' + fmtT(sec) + ' 장면은 원본에서 봐 주세요');
      return;
    }
    const sp = t.closest('[data-sp]'); if (sp) { A.setAiSpeed(sp.dataset.sp); const bs = [...$('#sSpeed').querySelectorAll('button')]; bs.forEach(b => b.classList.toggle('on', b === sp)); $('#sSpeed i').style.transform = `translateX(${bs.indexOf(sp) * 100}%)`; return; }
    if (t.closest('#sWatch')) { const on = !t.closest('#sWatch').querySelector('.sw').classList.contains('on'); A.setWatch(on); t.closest('#sWatch').querySelector('.sw').classList.toggle('on', on); return; }
    if (t.closest('#sBat')) { A.batteryUnrestrict(); return; }
    if (t.closest('#sAiOn')) { const on = !t.closest('#sAiOn').classList.contains('on'); A.setAiOn(on); renderSettings(); return; }
    if (t.closest('#sAiAll')) {
      const todo = items.filter(x => x.status === 'done' && (!x.ai || !x.category));
      if (!todo.length) { toast('정리할 메모가 없어요'); return; }
      return confirmAsk(todo.length + '개를 AI로 정리할까요?', '정리하기', () => { todo.forEach(x => { x.aiBusy = 'AI 대기 중'; A.aiRedo(x.id); }); toast('하나씩 정리할게요'); renderHome(); });
    }
    const dg = t.closest('[data-diag]'); if (dg) { const it = byId(dg.dataset.diag); return openDiag(it ? it.url : '', it, true); }
    if (t.closest('[data-refetch]')) { const it = byId(cur); if (it) { it.commentBusy = true; renderTabs(it); A.refetchComments(cur); } return; }
    const ec = t.closest('[data-errcopy]'); if (ec) { const it = byId(ec.dataset.errcopy); A.copy((it.url || '') + '\n' + (it.errorDetail || it.error || '')); toast('오류 내용을 복사했어요'); return; }
    const rt = t.closest('[data-retry]'); if (rt) return A.retry(rt.dataset.retry);
    const dl = t.closest('[data-del]'); if (dl) { A.remove(dl.dataset.del); items = items.filter(x => x.id !== dl.dataset.del); renderHome(); return; }
    if (t.closest('[data-back]')) return closeTop();
    const tab = t.closest('[data-tab]'); if (tab) { curTab = tab.dataset.tab; renderTabs(byId(cur)); return; }
    const more = t.closest('[data-more]'); if (more) {
      const it = byId(cur); const full = curTab === 'body' ? it.body : it.desc;
      more.previousElementSibling.textContent = full; more.remove(); return;
    }
    if (t.closest('#dCap') || t.closest('#dCapAdd')) return capture();
    const acc = t.closest('[data-acc]'); if (acc) { const k = acc.dataset.acc; accOpen[k] = !accOpen[k]; syncAcc(); return; }
    const cs = t.closest('[data-csel]'); if (cs) { const id = cs.dataset.csel; if (capSel.has(id)) capSel.delete(id); else capSel.add(id); renderCapSel(); return; }
    const shot = t.closest('[data-cap]'); if (shot) return openCap(shot.dataset.cap);
    const ar = t.closest('[data-att]'); if (ar) { attSel = ar.dataset.att; document.querySelectorAll('#attList .rad').forEach(b => b.classList.toggle('on', b === ar)); return; }
    if (t.closest('[data-capmove]')) { const it = byId(cur); const c = it.captures.find(x => x.id === curCap); return openAttach({ kind: 'move', ids: [curCap], t: c.t, img: fileUrl(it.id, c.file), info: fmtT(c.t) + ' 캡처', cur: c.at ? c.at.sec + ':' + c.at.i : '' }); }
    if (t.closest('#dAddTag')) return ask('태그 추가', '태그', v => {
      const it = byId(cur); v = v.replace(/^#/, '').trim(); if (!v) return;
      it.tags = it.tags || []; if (!it.tags.includes(v)) it.tags.push(v);
      A.update(cur, JSON.stringify({ tags: it.tags })); renderDetail(false); renderHome();
    });
    const tg = t.closest('[data-tag]'); if (tg) {
      const v = tg.dataset.tag;
      return confirmAsk('#' + v + ' 태그를 지울까요?', '지우기', () => {
        const it = byId(cur); it.tags = (it.tags || []).filter(x => x !== v);
        A.update(cur, JSON.stringify({ tags: it.tags })); renderDetail(false); renderHome();
      });
    }
    const sm = t.closest('#ssMode button'); if (sm) return setMode(sm.dataset.v);
    const q = t.closest('#sQ button'); if (q) {
      A.setQuality(q.dataset.q);
      const bs = [...$('#sQ').querySelectorAll('button')]; const i = bs.indexOf(q);
      bs.forEach(b => b.classList.toggle('on', b === q));
      $('#sQ i').style.transform = `translateX(${i * 100}%)`; return;
    }
    if (t.closest('#sDelVid')) return confirmAsk('저장한 영상을 모두 지울까요? (메모 · 캡처는 남아요)', '지우기', () => { A.deleteVideos(); load(); renderSettings(); });
    if (t.closest('#sDiag')) { const last = items.find(x => x.url); return openDiag(last ? last.url : '', null, false); }
    if (t.closest('#sTool')) { toast('확인하는 중…'); return A.updateTool(); }
  });

  $('#btnSettings').onclick = openSettings;
  $('#dgGo').onclick = runDiag;
  $('#dgCopy').onclick = () => { A.copy(diagText()); toast('결과를 복사했어요'); };
  $('#dim').onclick = () => { const top = stack[stack.length - 1]; if (top && top.type === 'sheet') closeSheet(top.id); };
  $('#ssGo').onclick = doSave;
  $('#ssMemo').addEventListener('keydown', e => { if (e.key === 'Enter') doSave(); });
  $('#askGo').onclick = () => { const v = $('#askInput').value; closeSheet('askSheet'); askCb && askCb(v); };
  $('#askInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('#askGo').click(); });
  $('#cfNo').onclick = () => closeSheet('confirmSheet');
  $('#cfYes').onclick = () => { closeSheet('confirmSheet'); cfCb && cfCb(); };

  $('#paste').addEventListener('input', pasteState);
  $('#pasteGo').onclick = () => { const u = findUrl($('#paste').value); if (u) openSave(u); else toast('링크를 붙여 넣어 주세요'); };
  $('#paste').addEventListener('keydown', e => { if (e.key === 'Enter') $('#pasteGo').click(); });

  $('#q').addEventListener('input', e => { query = e.target.value.trim(); $('#qClear').classList.toggle('hide', !query); renderHome(); });
  $('#qClear').onclick = e => { e.preventDefault(); $('#q').value = ''; query = ''; $('#qClear').classList.add('hide'); renderHome(); };

  document.addEventListener('input', e => {
    if (e.target.id === 'dMemo') { autoGrow(e.target); clearTimeout(memoT); memoT = setTimeout(saveMemo, 600); }
    if (e.target.id === 'cMemo') { autoGrow(e.target); clearTimeout(memoT); memoT = setTimeout(saveCapMemo, 600); }
  });
  document.addEventListener('focusout', e => { if (e.target.id === 'dMemo') saveMemo(); if (e.target.id === 'cMemo') saveCapMemo(); });

  $('#aOpen').onclick = () => { const it = byId(cur); if (it) A.open(it.url); };
  $('#aSave').onclick = () => { const it = byId(cur); if (it && it.video) { A.exportFile(it.id, it.video); toast('갤러리에 저장하는 중…'); } };
  $('#aCopy').onclick = () => {
    const it = byId(cur); if (!it) return;
    const done = (txt, what) => { A.copy(txt); toast(what + ' 복사했어요'); };
    const rows = [['전체', () => done(itemText(it), '전체를')]];
    if (it.ai && (it.ai.summary || []).length) rows.push(['요약', () => done(it.ai.summary.map(plain).join('\n'), '요약을')]);
    if (it.ai && (it.ai.tips || []).length) rows.push(['댓글 꿀팁', () => done(it.ai.tips.map((x, i) => (i + 1) + '. ' + x.text).join('\n'), '꿀팁을')]);
    if (it.ai && (it.ai.transcript || []).length) rows.push(['받아쓰기', () => done(it.ai.transcript.map(x => '[' + fmtT(x.t) + '] ' + x.text).join('\n'), '받아쓰기를')]);
    if (it.desc) rows.push(['설명', () => done(it.desc, '설명을')]);
    if (it.body) rows.push(['본문', () => done(it.body, '본문을')]);
    if ((it.comments || []).length) rows.push(['댓글', () => done(it.comments.map((c, i) => (i + 1) + '. @' + c.a + ' ' + c.t + (c.r || []).map(r => '\n   ↳ @' + r.a + (r.own ? ' [작성자]' : '') + ' ' + r.t).join('')).join('\n'), '댓글을')]);
    if (it.memo) rows.push(['내 메모', () => done(it.memo, '메모를')]);
    rows.push(['링크', () => done(it.url, '링크를')]);
    optSheet('복사', rows);
  };
  $('#aTxt').onclick = () => { const it = byId(cur); if (it) A.shareText(it.title || 'memo', itemText(it)); };
  $('#aDel').onclick = () => confirmAsk('이 메모를 지울까요?', '지우기', () => {
    const id = cur; closePage('detail'); A.remove(id); items = items.filter(x => x.id !== id); meta.order = meta.order.filter(x => x !== id); saveMeta(); renderHome();
  });

  $('#cropFull').onclick = () => openAttach({ kind: 'new', data: cropResult(true), t: cropT, img: cropSrc, info: fmtT(cropT) + ' · 전체' });
  $('#cropOk').onclick = () => { const d = cropResult(false); openAttach({ kind: 'new', data: d, t: cropT, img: d, info: fmtT(cropT) + ' · 잘라낸 부분' }); };
  $('#attGo').onclick = doAttach;
  $('#csAll').onclick = () => { const caps = byId(cur).captures || []; if (capSel.size === caps.length) capSel.clear(); else caps.forEach(c => capSel.add(c.id)); renderCapSel(); };
  $('#csDel').onclick = () => confirmAsk(capSel.size + '개 캡처를 지울까요?', '지우기', () => {
    const r = A.removeCaptures(cur, JSON.stringify([...capSel])); if (r) replace(JSON.parse(r));
    closePage('capsel'); renderDetail(false); toast('지웠어요');
  });
  $('#csMove').onclick = () => { const it = byId(cur); const first = it.captures.find(c => capSel.has(c.id)); openAttach({ kind: 'move', ids: [...capSel], t: first ? first.t : -1, img: first ? fileUrl(it.id, first.file) : '', info: capSel.size + '개 캡처' }); };
  $('#cGallery').onclick = () => { const it = byId(cur); const c = it && it.captures.find(x => x.id === curCap); if (c) A.exportFile(it.id, c.file); };
  $('#cPlay').onclick = () => {
    const it = byId(cur); const c = it && it.captures.find(x => x.id === curCap); if (!c) return;
    closePage('capView');
    const v = $('#dVideo'); if (v) { v.currentTime = c.t; v.play(); $('#dScroll').scrollTo({ top: 0, behavior: 'smooth' }); }
  };
  $('#cDel').onclick = () => confirmAsk('이 캡처를 지울까요?', '지우기', () => {
    const id = curCap; closePage('capView');
    const r = A.removeCapture(cur, id); if (r) { replace(JSON.parse(r)); renderCaps(byId(cur)); }
  });

  load();
  if (!window.Android) window.__dev = { openCrop, liveUpdate, liveStart: (id) => { const it = byId(id); it.aiBusy = 'AI 받아쓰는 중'; it.aiBusyAt = Date.now(); renderDetail(false); } };

  // ── 컴퓨터에서 화면만 볼 때 쓰는 가짜 브리지 ──
  function devBridge() {
    const now = Date.now();
    let data = [
      { id: 'a', url: 'https://www.instagram.com/reel/x', platform: 'instagram', kind: '릴스', status: 'done', category: '여행',
        ai: { summary: ['청안딤섬 딤섬 한 판 [[약 3천 원]], 북경오리 추천', '포나가르 사원은 더우니 [[오전]]에'], course: ['콩카페', '포나가르 사원', '청안딤섬', '제시프루츠'], folder: '여행 › 나트랑', kind: 'recipe', recipe: { ingredients: ['감자 2개', '올리브유 1큰술'], steps: ['찬물에 10분', '200도 10분'] },
          tips: [{ text: '6시 전에 가야 웨이팅 없음', from: 'author', who: 'yytable' }], video: true, facts: [{ t: 5, text: 'Lai Seafood 랍스터 1kg 120만동' }, { t: -1, text: '화요일 휴무: Ớt Hiểm' }], model: 'gemini-2.5-flash',
          log: { total: 112000, rows: [{ step: '영상 올리기', ms: 8200, note: '12.3MB', ok: true }, { step: '영상 처리 대기', ms: 14000, note: '확인 7번', ok: true }, { step: '최신 flash #1', ms: 1200, note: '503 구글 서버가 바빠요(모델 혼잡) → 잠시 뒤 또는 다른 모델', ok: false }, { step: '2.5 flash #1', ms: 88000, note: '성공', ok: true }],
            usage: { totalTokenCount: 21000, promptTokenCount: 18000, videoTokens: 15000, thoughtsTokenCount: 2400, candidatesTokenCount: 600 }, why: ['실패 1번 → 다시 시도 · 다른 모델로 넘어감', 'AI가 답하기 전에 오래 생각함 (생각 2400 · 답 600 토큰)', '영상이 길어서 볼 양이 많음 (영상 15000 토큰)'] }, transcript: [{ t: 0, text: '오늘은 나트랑 3일차' }, { t: 12, text: '콩카페 먼저 가요' }] }, title: '나트랑 시내 맛집 4곳 추천', author: 'yytable', desc: '나트랑 시내에서 현지인이 많이 가는 식당 4곳!\n1. Lai Seafood\n2. Ớt Hiểm Thai Cuisine', duration: 48, w: 720, h: 1280, memo: '12월 여행 때 저녁', tags: ['여행'], createdAt: now,
        captures: [{ id: 'k1', t: 5, file: 'x.jpg', text: '랍스터 1kg', memo: '가격표', ai: true, at: { sec: 'summary', i: 0 } }, { id: 'k2', t: 12, file: 'y.jpg', text: '', memo: '', at: { sec: 'facts', i: 0 } }, { id: 'k3', t: 20, file: 'z.jpg', text: '', memo: '' }], comments: [{ a: 'traveler_kim', t: '저녁 6시 전에 가야 웨이팅 없어요', l: 312, r: [{ a: 'yytable', t: '맞아요! 6시 넘으면 30분 넘게 기다려요', l: 20, own: true }] }, { a: 'minji', t: '저장해둡니다', l: 54 }] },
      { id: 'b', url: 'https://youtube.com/shorts/y', platform: 'youtube', kind: '쇼츠', status: 'done', category: '디자인', title: '피그마 아이콘 파일 50개 이상 무료 배포', author: 'figma.create', desc: '댓글에 icon 남기면 보내드려요', duration: 35, w: 720, h: 1280, tags: ['디자인'], createdAt: now, comments: [], commentError: '[화면] HTTP 403 login_required\n[직접] HTTP 403', captures: [] },
      { id: 'c', url: 'https://brunch.co.kr/z', platform: 'web', kind: '웹', status: 'done', category: '요리', title: '홈카페 라떼아트 기초', author: 'brunch.co.kr', desc: '', body: '우유 온도는 60도가 좋아요.', tags: [], createdAt: now, comments: [], captures: [] },
      { id: 'd', url: 'https://www.instagram.com/reel/z', platform: 'instagram', status: 'saving', stage: '영상 받는 중', progress: 62, createdAt: now },
      { id: 'e', url: 'https://www.instagram.com/reel/q', platform: 'instagram', status: 'error', error: '인스타 로그인이 필요해요', errorDetail: 'ERROR: login required', createdAt: now },
    ];
    return {
      items: () => JSON.stringify(data),
      add: (url, mode, memo) => JSON.stringify({ id: 'n' + Math.random(), url, mode, memo, platform: 'web', status: 'saving', stage: '대기 중', progress: 0, createdAt: Date.now() }),
      update: () => '', remove: () => {}, retry: () => {}, capture: () => '', removeCapture: () => '',
      open: () => {}, copy: () => {}, shareText: () => {}, exportFile: () => {},
      settings: () => JSON.stringify({ quality: '720', igLogin: false, used: 1.2e9, tool: '2026.11.01', app: '0.1' }),
      setQuality: () => {}, refetchComments: () => {}, setGeminiKey: () => {}, setAiOn: () => {}, aiRedo: () => {}, aiCancel: () => {}, batteryUnrestrict: () => {}, setAiSpeed: () => {}, setWatch: () => {}, removeMany: () => {}, updateMany: () => {}, removeCaptures: () => '',
      meta: () => localStorage.meta || JSON.stringify({ folders: [{ id: 'f1', name: '여행', parent: null }, { id: 'f2', name: '나트랑', parent: 'f1' }, { id: 'f3', name: '디자인', parent: null }], order: [] }),
      setMeta: (j) => { localStorage.meta = j; },
      diag: (u) => { const st = [['링크 종류', 'ok', '인스타 · 게시물 abc'], ['인터넷 연결', 'ok', '응답 200'], ['인스타 로그인', 'ok', '로그인됨'], ['내용 읽기 (yt-dlp)', 'ok', '제목 ✓ · 설명 320자'], ['댓글 가져오기', 'fail', '[화면] HTTP 403']];
        st.forEach((x, i) => setTimeout(() => App.onDiag(JSON.stringify({ i, name: x[0], state: x[1], msg: x[2], detail: x[1] === 'fail' ? '[화면] HTTP 403 {"message":"login_required"}' : '' })), 150 * i));
        setTimeout(() => App.onDiag('{"end":true}'), 900); }, igLogin: () => {}, igLogout: () => {}, deleteVideos: () => {}, updateTool: () => {}, toast: () => {},
    };
  }
})();
