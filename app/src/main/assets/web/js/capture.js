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
  $('#csGrid').innerHTML = caps.map(c => `<button class="shot${capSel.has(c.id) ? ' picked' : ''}" data-csel="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}<span class="ck2"></span></button>`).join('');
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
  const where = !c.at ? '캡처 칸에만' : c.at.sec === 'summary' ? '팁: ' + plain((ai.summary || [])[c.at.i] || '') : c.at.sec === 'places' ? '장소: ' + (((ai.places || [])[c.at.i] || {}).name || '') : c.at.sec === 'steps' ? '순서 ' + (+c.at.i + 1) : '정보: ' + (((ai.facts || [])[c.at.i] || {}).text || '');
  $('#cAt').innerHTML = `<span>붙인 곳 <b>${esc(where)}</b></span><button class="chip" data-capmove>바꾸기</button>`;
}
function saveCapMemo() {
  const it = byId(cur); const c = it && (it.captures || []).find(x => x.id === curCap); if (!c) return;
  const v = $('#cMemo').value; if (c.memo === v) return;
  c.memo = v; A.update(cur, JSON.stringify({ captures: it.captures }));
}
