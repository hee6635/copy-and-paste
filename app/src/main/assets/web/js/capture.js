'use strict';
/* 캡처: 찍기 · 잘라쓰기 · 붙일 곳 · 여러 개 고르기 · 캡처 보기 */
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
  if ((ai.places || []).length) h = '<div class="agrp">장소</div>' + ai.places.map((p, i) => row('places:' + i, (i + 1) + '. ' + p.name)).join('') + h;
  if (ai.recipe && (ai.recipe.steps || []).length) h += '<div class="agrp">순서</div>' + ai.recipe.steps.map((x, i) => row('steps:' + i, (i + 1) + '. ' + plain(x))).join('');
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
  $('#csGrid').innerHTML = caps.map(c => `<button class="shot${capSel.has(c.id) ? ' picked' : ''}" data-csel="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" style="object-position:50% ${c.focusY != null ? c.focusY : 50}%" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}<span class="ck2"></span></button>`).join('');
  $('#csCount').textContent = capSel.size + '개 선택';
  $('#csMove').disabled = $('#csDel').disabled = capSel.size === 0;
}

// ── 캡처 보기 ──
function openCap(capId) {
  const it = byId(cur); if (!it) return;
  curCap = capId;
  renderCap();
  openPage('capView');
}
function stepCap(dir) {
  const it = byId(cur); const caps = (it && it.captures) || [];
  const i = caps.findIndex(x => x.id === curCap);
  const ni = i + dir;
  if (i < 0 || ni < 0 || ni >= caps.length) return;
  saveCapMemo();
  curCap = caps[ni].id;
  renderCap();
  const pf = $('#photoFull img');
  if (pf) pf.src = fileUrl(it.id, caps[ni].file);
}
function renderCap() {
  const it = byId(cur); const caps = (it && it.captures) || [];
  const idx = caps.findIndex(x => x.id === curCap);
  const c = idx >= 0 ? caps[idx] : null; if (!c) return;
  $('#cTitle').textContent = '캡처 ' + fmtT(c.t) + (caps.length > 1 ? ` (${idx + 1}/${caps.length})` : '');
  const sc = $('#cScroll');
  if (!sc.querySelector('#cMemo') || sc.dataset.cap !== c.id) {
    sc.dataset.cap = c.id;
    const src = fileUrl(it.id, c.file);
    sc.innerHTML = `<div class="capimg"><img src="${src}" alt=""></div>
      <div class="sec"><div class="ocr" id="cOcr"></div>
      <label class="memo" style="margin-top:8px"><small>메모</small><textarea id="cMemo" rows="1"></textarea></label>
      <div class="capat" id="cAt"></div></div>`;
    $('#cMemo').value = c.memo || ''; autoGrow($('#cMemo'));
    zoomable(sc.querySelector('.capimg'), () => openPhotoFull(src), false, stepCap);
  }
  const ls = (c.text || '').split('\n').map(x => x.trim()).filter(Boolean);
  $('#cOcr').innerHTML = `<div class="sh"><b>화면 글자</b>${ls.length > 1 ? `<button class="chip dark" data-copytext="${esc(c.text)}">전체 복사</button>` : ''}</div>` +
    (c.ocr === 'busy' ? '읽는 중…' : ls.length ? ls.map(x => `<div class="ocrl"><span>${esc(x)}</span><button class="chip" data-copytext="${esc(x)}">복사</button></div>`).join('') : '글자가 없어요');
  const ai = it.ai || {};
  const where = !c.at ? '캡처 칸에만' : c.at.sec === 'summary' ? '팁: ' + plain((ai.summary || [])[c.at.i] || '') : c.at.sec === 'places' ? '장소: ' + (((ai.places || [])[c.at.i] || {}).name || '') : c.at.sec === 'steps' ? '순서 ' + (+c.at.i + 1) : '정보: ' + (((ai.facts || [])[c.at.i] || {}).text || '');
  $('#cAt').innerHTML = `<span>붙인 곳 <b>${esc(where)}</b></span><button class="chip" data-capmove>바꾸기</button>`;
}
function saveCapMemo() {
  const it = byId(cur); const c = it && (it.captures || []).find(x => x.id === curCap); if (!c) return;
  const v = $('#cMemo').value; if (c.memo === v) return;
  c.memo = v; A.update(cur, JSON.stringify({ captures: it.captures }));
}

/** 순수 사진 전체화면 (UI 없이 사진만 · 확대/축소 · 좌우 스와이프 · 1배율에서 한 번 탭하면 닫힘) */
function openPhotoFull(src) {
  const old = $('#photoFull'); if (old) old.remove();
  const el = document.createElement('div');
  el.id = 'photoFull';
  el.style.cssText = 'position:fixed;inset:0;z-index:9999;background:#000;display:flex;align-items:center;justify-content:center;overflow:hidden;touch-action:none;';
  el.innerHTML = `<img src="${src}" alt="" style="width:100%;height:100%;object-fit:contain;user-select:none;-webkit-user-drag:none;">`;
  document.body.appendChild(el);
  zoomable(el, () => el.remove(), true, stepCap);
}

/** 사진 확대: 두 손가락 벌리기 · 두 번 톡(2배 ↔ 원래) · 한 번 톡(onTap) · 1배율 좌우 스와이프(onSwipe) */
function zoomable(box, onTap, fullMode, onSwipe) {
  if (!box) return;
  const img = box.querySelector('img'); if (!img) return;
  img.draggable = false;  // 사진 끌기(파일 끌기)가 손가락 이동을 끊지 않게
  let k = 1, x = 0, y = 0, pts = new Map(), start = null, lastTap = 0, tapTimer = null, moved = false, justDbl = false, swiping = false;
  const clamp = () => { const mx = box.clientWidth * (k - 1) / 2, my = box.clientHeight * (k - 1) / 2; x = Math.max(-mx, Math.min(mx, x)); y = Math.max(-my, Math.min(my, y)); };
  const apply = anim => {
    img.style.transition = anim ? 'transform .25s var(--ease)' : 'none';
    img.style.transform = `translate(${x}px,${y}px) scale(${k})`;
    box.style.touchAction = (fullMode || k > 1) ? 'none' : 'pan-y';
  };
  const dist = () => { const [a, b] = [...pts.values()]; return Math.hypot(a.x - b.x, a.y - b.y); };
  box.addEventListener('pointerdown', e => {
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY }); box.setPointerCapture(e.pointerId);
    start = { k, x, y, d: pts.size === 2 ? dist() : 0, px: e.clientX, py: e.clientY };
    swiping = false;
    if (pts.size > 1) { moved = true; if (tapTimer) { clearTimeout(tapTimer); tapTimer = null; } }
    else {
      moved = false; justDbl = false;
      const now = Date.now();
      if (now - lastTap < 280) {
        if (tapTimer) { clearTimeout(tapTimer); tapTimer = null; }
        k = k > 1 ? 1 : 2; x = y = 0; apply(true); lastTap = 0; justDbl = true;
      } else lastTap = now;
    }
  });
  box.addEventListener('pointermove', e => {
    if (!pts.has(e.pointerId)) return;
    pts.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const dx = e.clientX - start.px, dy = e.clientY - start.py;
    if (Math.hypot(dx, dy) > 8) moved = true;
    if (pts.size === 2 && start.d) { swiping = false; k = Math.max(1, Math.min(4, start.k * dist() / start.d)); clamp(); apply(false); }
    else if (pts.size === 1 && k > 1) { x = start.x + dx; y = start.y + dy; clamp(); apply(false); }
    else if (pts.size === 1 && k <= 1.02 && onSwipe) {
      if (!swiping && Math.abs(dx) > 14 && Math.abs(dx) > Math.abs(dy) * 1.3) swiping = true;
      if (swiping) { img.style.transition = 'none'; img.style.transform = `translate(${dx * 0.35}px,0)`; }
    }
  });
  const up = e => {
    const dx = start ? e.clientX - start.px : 0;
    pts.delete(e.pointerId);
    if (swiping) {
      swiping = false;
      img.style.transition = 'transform .2s var(--ease)';
      img.style.transform = 'translate(0,0) scale(1)';
      if (Math.abs(dx) > 55 && onSwipe) onSwipe(dx < 0 ? 1 : -1);
      start = { k, x, y, d: 0, px: e.clientX, py: e.clientY };
      return;
    }
    start = { k, x, y, d: 0, px: e.clientX, py: e.clientY };
    if (k <= 1.02) { k = 1; x = y = 0; apply(true); }
    if (!justDbl && !moved && pts.size === 0 && onTap && k <= 1.02) {
      tapTimer = setTimeout(() => { tapTimer = null; onTap(); }, 280);
    }
  };
  box.addEventListener('pointerup', up); box.addEventListener('pointercancel', up);
}
