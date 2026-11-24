'use strict';
/* 내 저장함에 질문하기: 메모 목록을 짧게 묶어 Gemini 에 보내고, 답 + 근거 메모를 보여 줌 */
let askHist = [];      // {q, a, refs, error, pending}
let dockMode = 'save';
let askScope = null;   // 상세에서 물으면 그 메모 id

function setDockMode(m) {
  dockMode = m;
  document.querySelectorAll('#dockSeg button').forEach(b => b.classList.toggle('on', b.dataset.dm === m));
  const inp = $('#paste');
  inp.placeholder = m === 'ask' ? '저장한 것에 물어보기' : '링크 붙여넣기';
  inp.type = m === 'ask' ? 'text' : 'url';
  pasteState();
}

/** 질문에 맞는 메모를 먼저 골라 짧게 (최대 120개) */
function askContext(q) {
  if (askScope) return askOne(byId(askScope));
  const words = q.toLowerCase().split(/\s+/).filter(w => w.length >= 2);
  const done = items.filter(it => it.status === 'done');
  const scored = done.map(it => { const h = hayOf(it) + ' ' + (it.title || '').toLowerCase(); return { it, sc: words.reduce((a, w) => a + (h.includes(w) ? 1 : 0), 0) }; })
    .sort((a, b) => b.sc - a.sc || (b.it.createdAt || 0) - (a.it.createdAt || 0)).slice(0, 120);
  const cut = (s, n) => String(s || '').replace(/\s+/g, ' ').slice(0, n);
  return JSON.stringify(scored.map(({ it }) => {
    const ai = it.ai || {};
    const o = { id: it.id, t: cut(it.title, 80), f: fPath(it.folder), k: it.category || '' };
    if ((ai.places || []).length) o.p = ai.places.map(p => p.name + ': ' + plain(p.info)).join(' / ').slice(0, 400);
    if ((ai.summary || []).length) o.s = ai.summary.map(plain).join(' / ').slice(0, 300);
    if ((ai.tips || []).length) o.tip = ai.tips.map(x => x.text).join(' / ').slice(0, 300);
    if (ai.recipe && (ai.recipe.ingredients || []).length) o.r = ai.recipe.ingredients.join(', ').slice(0, 200);
    if (!o.p && !o.s) o.d = cut(it.desc || it.body, 300);
    if (it.memo) o.m = cut(it.memo, 150);
    if ((it.tags || []).length) o.tag = it.tags.join(' ');
    return o;
  }));
}

function openAsk(q) {
  if (!$('#askPage').classList.contains('open')) openPage('askPage');
  renderAsk();
  if (q) sendAsk(q);
}
function sendAsk(q) {
  q = String(q || '').trim(); if (!q) return;
  if (askHist.some(h => h.pending)) { toast('답을 기다리는 중이에요'); return; }
  const history = askHist.slice(-3).map(h => 'Q: ' + h.q + '\nA: ' + plain(h.a || '')).join('\n');
  askHist.push({ q, pending: true });
  $('#askQ').value = '';
  renderAsk();
  A.ask(q, askContext(q), history);
}
function refRow(id) {
  const it = byId(id); if (!it) return '';
  return `<button class="arow" data-id="${esc(id)}"><div class="at">${it.thumb ? `<img src="${fileUrl(id, it.thumb)}" alt="">` : platIcon(it.platform)}</div><div class="ab"><b>${esc(ttl(it))}</b><span>${esc(PLAT[it.platform] || '')} · ${fmtD(it.createdAt)}</span></div></button>`;
}
function renderAsk() {
  const el = $('#askLog');
  if (!askHist.length) {
    const ex = ['나트랑 맛집 뭐 저장했지?', '숙소 추천은?', '에어프라이어 레시피 재료'];
    el.innerHTML = `<div class="askex">${ex.map(x => `<button class="chip" data-askex="${esc(x)}">${esc(x)}</button>`).join('')}</div>`;
    return;
  }
  el.innerHTML = askHist.map((h, i) => `<div class="qb">${esc(h.q)}</div>
    <div class="ab2">${h.pending ? '<div class="watch"><i></i>찾는 중…</div>' : h.error ? `<div class="why">${esc(h.error)}</div>` : `<p class="p" data-ans="${i}">${rich(h.a || '')}</p>${(h.refs || []).map(refRow).join('')}`}</div>`).join('');
  el.scrollTop = el.scrollHeight;
}

/** 상세에서 묻기: 이 메모 하나만 자세히 보내기 (메모가 바뀌면 대화 새로) */
function askFor(id, q) {
  if (askScope !== id) askHist = [];
  askScope = id;
  openAsk(q);
}
function askOne(it) {
  if (!it) return '[]';
  const ai = it.ai || {}, cut = (x, n) => String(x || '').slice(0, n);
  return JSON.stringify([{ id: it.id, t: it.title, f: fPath(it.folder), url: it.url, ai: { summary: ai.summary, places: ai.places, facts: ai.facts, tips: ai.tips, recipe: ai.recipe, checklist: ai.checklist },
    desc: cut(it.desc, 3000), body: cut(it.body, 6000), memo: it.memo || '', ts: (ai.transcript || []).map(x => x.text).join(' ').slice(0, 6000),
    cmt: (it.comments || []).slice(0, 30).map(c => c.t).join(' / ').slice(0, 3000) }]);
}
