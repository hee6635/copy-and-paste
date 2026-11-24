'use strict';
/* 한 장씩 정리: 왼쪽으로 넘기면 지우기 · 오른쪽(또는 큰 버튼)은 AI 추천 폴더로 / 두기 · ↶ 되돌리기
   지우기는 정리 화면을 닫을 때 한 번에 지움 (그 전엔 되돌릴 수 있음). 정리한 것은 tidied 시각을 남겨 다시 안 나옴 */
let tq = [];            // 이번에 볼 id 순서
let ti = 0;             // 지금 보는 자리
let tHist = [];         // 되돌리기용 [{id, type:'del'|'keep'|'move', prev}]
let tDel = [];          // 닫을 때 지울 id
let tPick = '';         // 고른 칩: 'sugg' | 'keep'
let tCount = { del: 0, move: 0, keep: 0 };

function tidyList() { return items.filter(it => it.status === 'done' && !it.tidied && !tDel.includes(it.id)); }
function openTidy() {
  tDel = []; tHist = []; ti = 0; tCount = { del: 0, move: 0, keep: 0 };
  tq = tidyList().sort((a, b) => (a.createdAt || 0) - (b.createdAt || 0)).map(it => it.id);
  openPage('tidy');
  renderTidy();
}
function tidyClosed() {
  if (tDel.length) {
    const ids = tDel.slice(); tDel = [];
    A.removeMany ? A.removeMany(JSON.stringify(ids)) : ids.forEach(id => A.remove(id));
    items = items.filter(x => !ids.includes(x.id)); meta.order = meta.order.filter(x => !ids.includes(x)); saveMeta();
  }
  renderHome();
}
const tSugg = it => { const ai = it.ai || {}; const s = (ai.folder || '').trim(); return s && fPath(it.folder) !== s ? s : ''; };
function ago(ms) {
  const d = Math.floor((Date.now() - (ms || Date.now())) / 864e5);
  return d < 1 ? '오늘' : d < 30 ? d + '일 전' : d < 365 ? Math.floor(d / 30) + '달 전' : Math.floor(d / 365) + '년 전';
}
function tCardHtml(it, back) {
  const ai = it.ai || {};
  const thumb = it.thumb ? `<div class="tth"><img src="${fileUrl(it.id, it.thumb)}" alt=""><span class="tsrc">${esc(PLAT[it.platform] || '')}</span>${it.duration ? `<span class="badge">${fmtT(it.duration)}</span>` : ''}</div>` : '';
  if (back) return `<div class="tcard back">${thumb}<div class="tcb"><h3>${esc(ttl(it))}</h3></div></div>`;
  const sum = (ai.summary || []).slice(0, 3).map(x => `<li>${rich(x)}</li>`).join('');
  const s = tSugg(it);
  const cur = it.folder && fById(it.folder) ? fPath(it.folder) : '';
  const chips = (s ? `<button class="tchip${tPick === 'sugg' ? ' on' : ''}" data-tpick="sugg">${esc(s)}</button>` : '')
    + (s ? `<button class="tchip${tPick === 'keep' ? ' on' : ''}" data-tpick="keep">${cur ? '그대로 · ' + esc(cur) : '폴더 없이'}</button>` : '')
    + `<button class="tchip" data-tother>다른 폴더</button>`;
  return `<div class="tcard" id="tTop">${thumb}<div class="tstamp del">지우기</div><div class="tstamp ok">${s && tPick === 'sugg' ? '옮기기' : '두기'}</div>
    <div class="tcb"><h3>${esc(ttl(it))}</h3><div class="tmeta">${ago(it.createdAt)} 저장${cur ? ' · ' + esc(cur) : ''}</div>
    ${sum ? `<ul class="tsum">${sum}</ul>` : ''}${it.memo ? `<div class="tmemo">${esc(it.memo)}</div>` : ''}
    <div class="tai"><div class="tah">${s ? '어디로 둘까요<span>AI</span>' : '폴더'}</div><div class="tchips">${chips}</div></div></div></div>`;
}
function renderTidy() {
  const left = tq.length - ti;
  $('#tdN').textContent = left > 0 ? (ti + 1) + ' / ' + tq.length : '';
  $('#tdUndo').classList.toggle('off', !tHist.length);
  $('#tdBar').style.transform = `scaleX(${tq.length ? ti / tq.length : 1})`;
  const it = byId(tq[ti]);
  if (!it) {
    $('#tdStack').innerHTML = `<div class="tdone"><b>${tq.length ? '다 정리했어요' : '정리할 게 없어요'}</b>${tq.length ? `<div>지움 ${tCount.del} · 옮김 ${tCount.move} · 둠 ${tCount.keep}</div>` : ''}<button class="chip dark" data-back>닫기</button></div>`;
    $('#tdBtns').classList.add('hide');
    return;
  }
  tPick = tSugg(it) ? 'sugg' : 'keep';
  const nx = byId(tq[ti + 1]);
  $('#tdStack').innerHTML = (nx ? tCardHtml(nx, true) : '') + tCardHtml(it);
  $('#tdBtns').classList.remove('hide');
  tLabel(it);
  tDrag($('#tTop'));
}
/** 받침 보고 '으로' / '로' */
function ro(w) { const c = (w || '').trim().slice(-1).charCodeAt(0) - 0xac00; if (c < 0 || c > 11171) return w + '(으)로'; const j = c % 28; return w + (j === 0 || j === 8 ? '로' : '으로'); }
function tLabel(it) {
  const s = tSugg(it);
  $('#tdGo').textContent = s && tPick === 'sugg' ? ro(s) : '두기';
  const ok = $('#tTop .tstamp.ok'); if (ok) ok.textContent = s && tPick === 'sugg' ? '옮기기' : '두기';
}
/** 맨 위 카드를 날려 보내고 다음 장 */
function tFly(dir, done) {
  const c = $('#tTop'); const b = $('#tdStack .tcard.back');
  if (b) b.classList.add('up');
  if (!c) return done();
  c.style.transition = 'transform .32s var(--ease),opacity .32s';
  c.style.transform = `translateX(${dir * 130}%) rotate(${dir * 14}deg)`; c.style.opacity = '0';
  setTimeout(done, 280);
}
function tDo(type, fid) {
  const it = byId(tq[ti]); if (!it) return;
  const prev = it.folder || '';
  if (type === 'del') { tDel.push(it.id); tCount.del++; }
  else {
    const f = { tidied: Date.now() };
    if (type === 'move') { f.folder = fid; it.folder = fid; tCount.move++; } else tCount.keep++;
    it.tidied = f.tidied; A.update(it.id, JSON.stringify(f));
  }
  tHist.push({ id: it.id, type, prev });
  tFly(type === 'del' ? -1 : 1, () => { ti++; renderTidy(); });
}
function tGo() {
  const it = byId(tq[ti]); if (!it) return;
  const s = tSugg(it);
  if (s && tPick === 'sugg') { const fid = ensureFolder(s); if (fid) return tDo('move', fid); }
  tDo('keep');
}
function tUndo() {
  const h = tHist.pop(); if (!h) return;
  const it = byId(h.id);
  if (h.type === 'del') { tDel = tDel.filter(x => x !== h.id); tCount.del--; }
  else if (it) {
    it.tidied = 0; const f = { tidied: 0 };
    if (h.type === 'move') { it.folder = h.prev; f.folder = h.prev; tCount.move--; } else tCount.keep--;
    A.update(it.id, JSON.stringify(f));
  }
  ti = Math.max(0, tq.indexOf(h.id)); renderTidy();
}
/** 다른 폴더: 폴더 고르기 창을 쓰고, 옮기면 다음 장 (home.js moveTo 에서 부름) */
let tMoving = false, tPrev = '';
function tidyMoved(fid) {
  if (!tMoving) return false;
  tMoving = false;
  const it = byId(tq[ti]); if (!it) return true;
  if (moveIds[0] === it.id) { it.folder = fid; tHist.push({ id: it.id, type: 'move', prev: tPrev }); tCount.move++; it.tidied = Date.now(); A.update(it.id, JSON.stringify({ tidied: it.tidied })); tFly(1, () => { ti++; renderTidy(); }); }
  return true;
}
// 끌기: 왼쪽 = 지우기, 오른쪽 = 큰 버튼과 같음
function tDrag(c) {
  if (!c) return;
  let x0 = 0, y0 = 0, dx = 0, on = false, horiz = null;
  const del = c.querySelector('.tstamp.del'), ok = c.querySelector('.tstamp.ok');
  c.addEventListener('pointerdown', e => { if (e.target.closest('button')) return; on = true; horiz = null; x0 = e.clientX; y0 = e.clientY; dx = 0; c.style.transition = 'none'; });
  c.addEventListener('pointermove', e => {
    if (!on) return;
    dx = e.clientX - x0; const dy = e.clientY - y0;
    if (horiz === null && Math.abs(dx) + Math.abs(dy) > 8) { horiz = Math.abs(dx) > Math.abs(dy); if (horiz) c.setPointerCapture(e.pointerId); }
    if (!horiz) return;
    c.style.transform = `translateX(${dx}px) rotate(${dx / 22}deg)`;
    del.style.opacity = Math.min(1, Math.max(0, -dx / 90)); ok.style.opacity = Math.min(1, Math.max(0, dx / 90));
  });
  const end = () => {
    if (!on) return; on = false;
    if (horiz && dx < -100) return tDo('del');
    if (horiz && dx > 100) return tGo();
    c.style.transition = 'transform .45s var(--spring)'; c.style.transform = ''; del.style.opacity = 0; ok.style.opacity = 0;
  };
  c.addEventListener('pointerup', end); c.addEventListener('pointercancel', end);
}
document.addEventListener('click', e => {
  const t = e.target;
  if (!t.closest('#tidy')) return;
  const pk = t.closest('[data-tpick]'); if (pk) { tPick = pk.dataset.tpick; document.querySelectorAll('#tidy [data-tpick]').forEach(b => b.classList.toggle('on', b === pk)); tLabel(byId(tq[ti])); return; }
  if (t.closest('[data-tother]')) { tMoving = true; tPrev = (byId(tq[ti]) || {}).folder || ''; openMove([tq[ti]]); return; }
  if (t.closest('#tdDel')) return tDo('del');
  if (t.closest('#tdGo')) return tGo();
  if (t.closest('#tdUndo')) return tUndo();
});
