'use strict';
/* 상세: 그리기 · 접기 · 탭 · 내 메모 · 복사용 글 */
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
    <button class="fchip" id="dCat">${esc(it.category ? catLabel(it.category) : '키워드 없음')}<svg class="ic chev" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button>
    <div class="tags">${(it.tags || []).map(t => `<button class="tg" data-tag="${esc(t)}">#${esc(t)}</button>`).join('')}
    <button class="add" id="dAddTag" aria-label="태그 추가"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button></div>`;
  renderAi(it);
  renderTips(it);
  renderCaps(it);
  renderTabs(it);
  syncAcc();
  $('#aSave').disabled = !it.video;
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
  const src = t => t.from === 'video' && +t.t >= 0 ? `<button data-seek="${+t.t}">영상 ${fmtT(t.t)}</button>` : t.from === 'video' ? '영상' : t.from === 'author' ? '작성자 답글' : t.from === 'body' || /^author$/i.test(t.who || '') ? '본문' : t.who ? '@' + esc(String(t.who).replace(/^@/, '')) : '댓글';
  el.innerHTML = accHead('tips', it.ai.video ? '영상 · 댓글 꿀팁' : '꿀팁', tips.length) + `<div class="accb"><div class="sec">
    ${tips.map((t, i) => `<div class="tip"><span class="n">${i + 1}</span><div><p>${esc(t.text)}</p><small>${src(t)}</small></div></div>`).join('')}</div></div>`;
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
    const dk = it.ai && it.ai.desc_ko;
    body = it.desc ? (dk ? `<div class="kobox"><small>번역</small><p class="p">${esc(dk)}</p></div><details class="orig"><summary>원문 보기</summary>${longText(it.desc)}</details>` : longText(it.desc)) : '<div class="none">설명이 없어요</div>';
  } else if (curTab === 'ts') {
    body = it.ai.transcript.map(x => `<button class="ts" data-seek="${+x.t || 0}"><b>${fmtT(x.t)}</b><span>${esc(x.text)}${x.ko ? `<em class="ko">${esc(x.ko)}</em>` : ''}</span></button>`).join('');
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

function itemText(it) {
  const L = [];
  L.push('📌 ' + (it.title || ''));
  L.push([PLAT[it.platform], it.author, new Date(it.createdAt).toLocaleDateString('ko-KR')].filter(Boolean).join(' · '));
  L.push('🔗 ' + it.url);
  if ((it.tags || []).length) L.push(it.tags.map(t => '#' + t).join(' '));
  if (it.memo) L.push('', '── 내 메모 ──', it.memo);
  if (it.ai && (it.ai.places || []).length) L.push('', '── ' + (it.ai.listName || '장소') + ' ──', ...it.ai.places.map((p, i) => (i + 1) + '. ' + p.name + (+p.t >= 0 ? ' (' + fmtT(p.t) + ')' : '') + ' — ' + plain(p.info) + (p.need === 'must' ? ' [필수]' : p.need === 'skip' ? ' [필요 없음]' : '')));
  else if (it.ai && (it.ai.course || []).length) L.push('', '── 코스 ──', it.ai.course.join(' → '));
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
