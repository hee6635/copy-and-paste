'use strict';
/* AI 정리 결과 · 쓰는 중 주르륵 · 기록 */
// ── AI 정리 결과 ──
function renderAi(it) {
  const el = $('#dAi'); if (!el) return;
  const ai = it.ai;
  if (it.aiBusy) { liveRender(it); return; }
  if (live) typeTok++;
  live = null;
  if (!ai) {
    if (manualWait[it.id]) { el.innerHTML = manualHtml(); return; }
    if (!it.aiError) { el.innerHTML = it.status === 'done' ? `<div class="sec"><div class="sh"><b>AI 정리</b></div><div class="bt"><button class="chip dark" data-manual>제미나이 앱으로 정리</button></div></div>` : ''; return; }
    if (it.aiError && it.aiRetryAt > Date.now()) { el.innerHTML = `<div class="sec"><div class="cmtx"><b>${hm(it.aiRetryAt)}쯤 다시 정리해요</b>
      <div class="bt"><button class="chip dark" data-airedo>지금 다시</button><button class="chip" data-manual>제미나이 앱으로</button><button class="chip" data-diag="${esc(it.id)}">원인 검사</button></div></div>${logHtml(it.aiLog)}</div>`; return; }
    el.innerHTML = it.aiError ? `<div class="sec"><div class="cmtx"><b>AI 정리를 못 했어요</b><div class="why pre">${esc(String(it.aiError))}</div>
      <div class="bt"><button class="chip dark" data-airedo>다시 정리</button><button class="chip" data-manual>제미나이 앱으로</button><button class="chip" data-diag="${esc(it.id)}">원인 검사</button></div></div>${logHtml(it.aiLog, true)}</div>` : '';
    return;
  }
  let h = '';
  const sum = ai.summary || [];
  const places = (ai.places || []).filter(p => p && p.name);
  const sugg = ai.folder && ai.folder.trim() && fPath(it.folder) !== ai.folder.trim() ? ai.folder.trim() : '';
  const ck = it.checks || {};
  const rec = ai.kind === 'recipe' && ai.recipe && ((ai.recipe.ingredients || []).length || (ai.recipe.steps || []).length) ? ai.recipe : null;
  const runinfo = ai.log ? `<button class="runinfo" data-runlog>${fmtMs(ai.log.total)} · ${esc(mLabel(ai.model))}${(ai.log.why || []).length ? ' · 오래 걸린 이유 ' + ai.log.why.length : ''}<svg class="ic" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button><div class="runlog hide">${logHtml(ai.log)}</div>` : '';
  const redo = (canSlide(it) ? '<button class="slbtn" data-slides>▶ 슬라이드</button>' : '') + '<button class="ai" data-airedo aria-label="다시 정리">AI ↻</button>';
  const suggHtml = sugg ? `<div class="sugg"><div class="t">폴더 추천 <b>${esc(sugg)}</b></div><button data-sugg>옮기기</button></div>` : '';
  const tipsTitle = places.length || rec ? '꼭 알아둘 팁' : '요약';
  const longNote = it.longSkipped && !it.video ? `<div class="sec longnote"><span>긴 영상이라 링크만 저장했어요</span><button class="chip dark" data-dlvideo>영상 받기</button></div>` : '';
  // 긴 영상: 꼭 알아둘 팁 → 목차 → 장소
  const chs = (ai.chapters || []).filter(c => c && c.title);
  if (chs.length) {
    h += longNote + `<div class="sec"><div class="sh"><b>꼭 알아둘 팁</b>${redo}</div>${runinfo}
      ${sum.map(x => `<div class="tip3"><b>·</b><span>${rich(x)}</span></div>`).join('')}${suggHtml}</div>`;
    h += `<div class="sec"><div class="sh"><b>목차 ${chs.length}</b>${(it.ytChapters || []).length ? '<span class="badge2">유튜버 목차</span>' : ''}</div>${chs.map((c, i) => chapterRow(it, chs, i)).join('')}</div>`;
    if (places.length) h += `<div class="sec"><div class="sh"><b>장소 ${places.length}</b></div>${places.map((p, i) => placeRow(it, p, i)).join('')}</div>`;
    if (it.aiError) h += `<div class="sec"><div class="cmtx"><div class="why">다시 정리 실패: ${esc(String(it.aiError).split('\n')[0])}</div></div></div>`;
    el.innerHTML = h;
    return;
  }
  h += longNote;
  if (places.length) {
    h += `<div class="sec"><div class="sh"><b>장소 ${places.length}</b>${redo}</div>${runinfo}${places.map((p, i) => placeRow(it, p, i)).join('')}</div>`;
  } else if (rec) {
    const f = srvF(it);
    h += `<div class="sec"><div class="sh"><b>재료 ${(rec.ingredients || []).length}</b><span class="shr">${srvHtml(it, rec)}${redo}</span></div>${runinfo}
      <div class="ing">${(rec.ingredients || []).map((x, i) => `<button class="chk${(ck.i || []).includes(i) ? ' on' : ''}" data-chk="i:${i}"><i></i><span>${esc(scaleIng(x, f))}</span></button>`).join('')}</div></div>`;
    if ((rec.steps || []).length) h += `<div class="sec"><div class="sh"><b>순서 ${rec.steps.length}</b></div>${rec.steps.map((x, i) => stepRow(it, x, i)).join('')}</div>`;
  }
  if (sum.length || sugg) {
    const isTop = !places.length && !rec;
    h += `<div class="sec"><div class="sh"><b>${tipsTitle}</b>${isTop ? redo : ''}</div>${isTop ? runinfo : ''}
      ${isTop ? sum.map((x, i) => sumCard(it, x, i)).join('') : sum.map((x, i) => `<div class="tip3"><b>·</b><span>${rich(x)}</span></div>${attHtml(it, 'summary', i)}`).join('')}
      ${suggHtml}</div>`;
  }
  const facts = (ai.facts || []).filter(f => f && f.text);
  if (facts.length && !places.length) h += `<div class="sec"><div class="sh"><b>${ai.video ? '영상에서 뽑은 정보' : '핵심 정보'}</b><span class="ai">AI</span></div>
    ${(ai.facts || []).map((f, i) => !f || !f.text ? '' : `<button class="ts" ${+f.t >= 0 ? `data-seek="${+f.t}"` : ''}>${+f.t >= 0 ? `<b>${fmtT(f.t)}</b>` : '<b>·</b>'}<span>${esc(f.text)}</span></button>${attHtml(it, 'facts', i)}`).join('')}</div>`;
  if (ai.kind === 'checklist' && (ai.checklist || []).length) h += `<div class="sec"><div class="sh"><b>체크리스트</b><span class="ai">AI</span></div>
    ${ai.checklist.map((x, i) => `<button class="chk${(ck.c || []).includes(i) ? ' on' : ''}" data-chk="c:${i}"><i></i><span>${esc(x)}</span></button>`).join('')}</div>`;
  h += eventsHtml(it, ai);
  if (it.aiError) h += `<div class="sec"><div class="cmtx"><div class="why">다시 정리 실패: ${esc(String(it.aiError).split('\n')[0])}</div></div></div>`;
  el.innerHTML = h;
}

const hm = ms => { const d = new Date(ms); return (d.getHours() < 12 ? '오전 ' : '오후 ') + ((d.getHours() + 11) % 12 + 1) + ':' + String(d.getMinutes()).padStart(2, '0'); };

// ── 인분 조절: 재료 속 숫자에 배수를 곱함 (분수 1/2 도) ──
const srvMul = {};
const MULS = [0.5, 1, 1.5, 2, 3, 4];
const srvF = it => srvMul[it.id] || 1;
function srvHtml(it, rec) {
  const f = srvF(it), b = +rec.servings > 0 ? +rec.servings : 0;
  const lab = b ? fmtN(b * f) + '인분' : '×' + fmtN(f);
  return `<span class="srv"><button data-srv="-1" aria-label="줄이기"${f <= MULS[0] ? ' disabled' : ''}>−</button><b>${lab}</b><button data-srv="1" aria-label="늘리기"${f >= MULS[MULS.length - 1] ? ' disabled' : ''}>+</button></span>`;
}
const fmtN = n => String(Math.round(n * 10) / 10);
function scaleIng(x, f) {
  if (f === 1) return x;
  return String(x).replace(/(\d+(?:\.\d+)?)(?:\s*\/\s*(\d+))?/g, (m, a, b) => fmtN((b ? +a / +b : +a) * f));
}

// ── 일정 → 캘린더 ──
const evList = ai => (ai.events || []).filter(e => e && e.title && /^\d{4}-\d{2}-\d{2}$/.test(e.date));
function eventsHtml(it, ai) {
  const evs = evList(ai); if (!evs.length) return '';
  const W = '일월화수목금토';
  return `<div class="sec"><div class="sh"><b>일정 ${evs.length}</b></div>${evs.map((e, i) => {
    const d = new Date(e.date + 'T00:00:00');
    const sub = [e.time, e.end && e.end !== e.date ? '~ ' + (+e.end.slice(5, 7)) + '.' + (+e.end.slice(8)) : '', e.place].filter(Boolean).join(' · ');
    return `<div class="evr"><div class="evd"><b>${d.getMonth() + 1}.${d.getDate()}</b><span>${W[d.getDay()]}</span></div><div class="evb"><b>${esc(e.title)}</b>${sub ? `<span>${esc(sub)}</span>` : ''}</div><button class="chip" data-cal="${i}">+ 캘린더</button></div>`;
  }).join('')}</div>`;
}
document.addEventListener('click', e => {
  const sv = e.target.closest('[data-srv]');
  if (sv && cur) { const it = byId(cur); const i = MULS.indexOf(srvF(it)); const j = Math.max(0, Math.min(MULS.length - 1, i + +sv.dataset.srv)); srvMul[cur] = MULS[j]; renderAi(it); return; }
  const cb = e.target.closest('[data-cal]');
  if (cb && cur) { const it = byId(cur); const ev = evList(it.ai || {})[+cb.dataset.cal]; if (ev && A.addCalendar) A.addCalendar(JSON.stringify(Object.assign({ note: (it.title || '') + '\n' + it.url }, ev))); }
});

/** 긴 영상 목차 한 줄: 시간 · 제목 · 핵심 · (자막이 있으면) 이 부분 자막 펼치기 */
function chapterRow(it, chs, i) {
  const c = chs[i], end = i + 1 < chs.length ? +chs[i + 1].t : 1e9;
  const subs = (it.subs || []).filter(x => +x.t >= +c.t - 1 && +x.t < end);
  return `<div class="pr ch"><div class="pb"><b><span><button class="tm" data-seek="${+c.t}">${fmtT(c.t)}</button> ${esc(c.title)}</span></b><p>${rich(c.info || '')}</p>
    ${subs.length ? `<button class="more2" data-chsub="${i}">이 부분 자막 ⌄</button><div class="chsub hide">${subs.map(x => `<div><b>${fmtT(x.t)}</b> ${esc(x.text)}</div>`).join('')}</div>` : ''}</div></div>`;
}
/** [[핵심]] → 숫자 · 시간이면 노란 칩, 아니면 굵게만 */
const CHIPY = /[0-9０-９]|오전|오후|아침|저녁|새벽|점심|밤|분|시간|원|동|달러|성급|박|일차/;
const rich = s => esc(s).replace(/\[\[(.+?)\]\]/g, (m, x) => CHIPY.test(x) ? `<span class="chip2">${x}</span>` : `<b>${x}</b>`);
/** 장소 한 줄: 사진(없으면 번호) · 이름 · 시각 · 핵심 정보 */
function placeRow(it, p, i) {
  const c = (it.captures || []).find(c => c.at && c.at.sec === 'places' && +c.at.i === i);
  const th = c ? `<button class="th2" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><i>${i + 1}</i></button>` : `<div class="th2 n"><i>${i + 1}</i></div>`;
  return `<div class="pr">${th}<div class="pb"><b><span>${esc(p.name)}</span>${+p.t >= 0 ? `<button class="tm" data-seek="${+p.t}">${fmtT(p.t)}</button>` : ''}</b><p>${rich(p.info || '')}</p></div></div>`;
}
function stepRow(it, x, i) {
  const c = (it.captures || []).find(c => c.at && c.at.sec === 'steps' && +c.at.i === i);
  const th = c ? `<button class="th2 s" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><i>${i + 1}</i></button>` : `<div class="th2 s n"><i>${i + 1}</i></div>`;
  return `<div class="pr st">${th}<div class="pb"><p>${rich(x)}</p></div></div>`;
}
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
  const keys = ['transcript', 'places', 'facts', 'summary', 'course', 'tips', 'keyshots', 'recipe', 'checklist', 'tags', 'folder', 'kind', 'category', 'star'];
  let end = txt.length;
  keys.forEach(k => { if (k === key) return; const j = txt.indexOf('"' + k + '"', i + 1); if (j > i && j < end) end = j; });
  return txt.slice(i, end);
}
const unq = s => { try { return JSON.parse('"' + s + '"'); } catch (e) { return s.replace(/\\"/g, '"'); } };
function parsePartial(txt) {
  const out = { ts: [], facts: [], sum: [], course: [] };
  const objs = sl => sl ? [...sl.matchAll(/\{\s*"t"\s*:\s*(-?[\d.]+)\s*,\s*"text"\s*:\s*"((?:[^"\\]|\\.)*)"\s*\}/g)].map(m => ({ t: +m[1], text: unq(m[2]) })) : [];
  const strs = sl => { if (!sl) return []; const b = sl.indexOf('['); if (b < 0) return []; return [...sl.slice(b).matchAll(/"((?:[^"\\]|\\.)*)"/g)].map(m => unq(m[1])); };
  const anyObjs = sl => { if (!sl) return []; const r = []; for (const m of sl.matchAll(/\{[^{}]*\}/g)) { try { r.push(JSON.parse(m[0])); } catch (e) {} } return r; };
  out.places = anyObjs(sliceKey(txt, 'places')).filter(p => p && p.name);
  out.ts = anyObjs(sliceKey(txt, 'transcript')).filter(x => x && x.text != null);
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
    <div class="lvprog" id="lvProg"><i></i></div>
    <div class="lvbox" id="lvTs"><h4>받아쓰기</h4><div id="lvTsList"><div class="watch"><i></i>영상 보는 중…</div></div></div>
    <div class="sec lvpl hide" id="lvPlaces"><div class="sh"><b>장소</b></div></div>
    <div class="sec lvpl hide" id="lvTips"><div class="sh"><b>꼭 알아둘 팁</b></div></div>
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
    // 폰이 먼저 읽은 같은 글자 줄은 AI 줄로 바꿈
    const key = String(x.text).replace(/^\[화면\]\s*/, '').slice(0, 6);
    list.querySelectorAll('.tl.ph').forEach(r => { if (key && r.dataset.k && (r.dataset.k.includes(key) || key.includes(r.dataset.k))) r.remove(); });
    const row = document.createElement('div'); row.className = 'tl'; row.dataset.t = x.t;
    row.innerHTML = `<b>${fmtT(x.t)}</b><span></span><small>AI</small>`;
    const after = [...list.querySelectorAll('.tl')].find(r => +r.dataset.t > +x.t);
    list.insertBefore(row, after || null);
    typeIn(row.querySelector('span'), x.text + (x.ko ? '  → ' + x.ko : ''), 70);
  }
  // 정보가 나오면: 받아쓰기에서 그 시각 줄을 반짝
  if (p.facts.length > live.facts) {
    live.facts = p.facts.length;
    typeQ = typeQ.then(() => {
      const rows = [...document.querySelectorAll('#lvTsList .tl')];
      rows.forEach(r => { const hit = p.facts.some(f => f.t >= 0 && Math.abs(f.t - +r.dataset.t) < 2); r.classList.toggle('hi', hit); r.classList.toggle('lo', !hit); });
    });
  }
  // 장소가 나오면: 설명 · 받아쓰기를 접고 장소 표로
  if ((p.places.length || p.sum.length) && live.phase === 'raw') {
    live.phase = 'sum';
    typeQ = typeQ.then(() => new Promise(r => setTimeout(r, 700))).then(() => { collapse($('#lvDesc')); collapse($('#lvTs')); collapse($('#lvProg')); });
  }
  const pdone = p.places.length - (/\}\s*\]/.test(sliceKey(txt, 'places') || '') ? 0 : 1);
  for (live.pl = live.pl || 0; live.pl < Math.max(0, pdone); live.pl++) {
    const pl = p.places[live.pl], i = live.pl;
    typeQ = typeQ.then(() => new Promise(r => {
      const box = $('#lvPlaces'); if (!box) return r();
      box.classList.remove('hide'); box.querySelector('.sh b').textContent = '장소 ' + (i + 1);
      const d = document.createElement('div'); d.innerHTML = placeRow({ id: '', captures: [] }, pl, i); const row = d.firstElementChild; row.classList.add('pop');
      box.appendChild(row); setTimeout(r, 260);
    }));
  }
  const done = p.sum.length - (/"\s*\]/.test(sliceKey(txt, 'summary') || '') ? 0 : 1);  // 마지막 줄은 끝날 때까지 기다림
  for (; live.sum < Math.max(0, done); live.sum++) {
    const x = p.sum[live.sum];
    typeQ = typeQ.then(() => {
      const usePl = live && live.pl > 0;
      const c = document.createElement('div'); c.className = usePl ? 'tip3 pop' : 'scard pop'; c.innerHTML = usePl ? '<b>·</b><span class="stx"></span>' : '<div class="stx"></div>';
      if (usePl) { const tb = $('#lvTips'); if (tb) { tb.classList.remove('hide'); tb.appendChild(c); } } else if ($('#lvCards')) $('#lvCards').appendChild(c);
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

// ── 반자동: 제미나이 앱에 부탁 → 답 복사 → 붙여넣기 ──
const manualWait = {};
const manualHtml = () => `<div class="sec"><div class="cmtx"><b>제미나이 답을 복사한 뒤 눌러 주세요</b>
  <div class="bt"><button class="chip dark" data-mpaste>답 붙여넣기</button><button class="chip" data-manual>제미나이 다시 열기</button><button class="chip" data-mcancel>그만두기</button></div></div></div>`;
document.addEventListener('click', e => {
  if (!cur) return;
  if (e.target.closest('[data-manual]')) { A.manualAsk && A.manualAsk(cur); manualWait[cur] = 1; renderAi(byId(cur)); return; }
  if (e.target.closest('[data-mcancel]')) { delete manualWait[cur]; renderAi(byId(cur)); return; }
  if (e.target.closest('[data-mpaste]')) {
    const r = A.manualPaste ? A.manualPaste(cur) : '';
    if (!r) { toast('복사한 답에서 정리를 못 찾았어요'); return; }
    delete manualWait[cur]; replace(JSON.parse(r)); renderDetail(false); renderHome(); toast('정리를 넣었어요');
  }
});
