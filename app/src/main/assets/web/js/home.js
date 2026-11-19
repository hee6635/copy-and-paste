'use strict';
/* 홈: 폴더 · 큰 키워드 · 카드 · 검색 · 여러 개 고르기 · 폴더 옮기기 · 순서 바꾸기 */
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
    (ai.summary || []).map(x => String(x).replace(/\[\[|\]\]/g, '')).join(' '), (ai.course || []).join(' '), (ai.places || []).map(p => p.name + ' ' + plain(p.info)).join(' '), (ai.tips || []).map(x => x.text).join(' '), (ai.facts || []).map(x => x.text).join(' '), (ai.transcript || []).map(x => x.text + ' ' + (x.ko || '')).join(' '), ai.desc_ko || '',
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
