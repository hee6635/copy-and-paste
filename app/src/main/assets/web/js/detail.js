'use strict';
/* 상세: 그리기 · 접기 · 탭 · 내 메모 · 복사용 글 */
// ── 상세 ──
function openDetail(id, from) {
  const it = byId(id); if (!it) return;
  cur = id; curTab = it.platform === 'web' ? 'body' : 'desc'; curPane = 'guide';
  renderDetail(true);
  $('#dScroll').scrollTop = 0;
  $('#detail .dtop').classList.remove('tt');
  if (from) zoomFrom(from);
  openPage('detail');
}

/** 카드를 누르면 그 카드 자리에서 상세가 커지며 열림 (transform · opacity 만) */
function zoomFrom(el) {
  const d = $('#detail'), r = el.getBoundingClientRect();
  d.style.transition = 'none'; d.style.transformOrigin = '0 0'; d.style.opacity = '0';
  d.style.transform = `translate(${r.left}px,${r.top}px) scale(${r.width / innerWidth},${r.height / innerHeight})`;
  d.offsetWidth;
  d.style.transition = 'transform .42s var(--ease),opacity .22s';
  d.style.transform = 'none'; d.style.opacity = '1';
  setTimeout(() => { ['transition', 'transformOrigin', 'opacity', 'transform'].forEach(k => d.style[k] = ''); }, 460);
}

// 탭: 가이드 · 캡처 · 원본 (한 화면 안에서 보이기만 바꿈)
let curPane = 'guide';
const PLAY = '<svg class="ic" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>';
function renderDetail(full) {
  const it = byId(cur); if (!it) return;
  $('#dTopTitle').textContent = it.title || '';
  const sc = $('#dScroll');
  if (full || !sc.querySelector('.meta')) {
    const media = '';  // 영상은 기본 플레이어로 봄 (앱 화면은 일부 소리 형식을 못 풀어서)
    accOpen = {};
    sc.innerHTML = `<div class="meta"></div>` + (media || '<div id="dMedia"></div>') + `<div id="dPanes"></div>
      <div data-pane="guide"><div id="dAi"></div><div id="dTips"></div>
        <div class="sec"><label class="memo"><small>내 메모</small><textarea id="dMemo" rows="2"></textarea></label></div><div id="dSim"></div></div>
      <div data-pane="caps"><div id="dCaps"></div></div>
      <div data-pane="orig"><div id="dRun"></div><div id="dTabs"></div><div id="dBody"></div></div>`;
    $('#dMemo').value = it.memo || '';
    autoGrow($('#dMemo'));
  }
  const th = it.thumb ? `<button class="hthumb" ${it.video ? 'data-vshow' : ''} aria-label="${it.video ? '영상 보기' : '대표 그림'}"><img src="${fileUrl(it.id, it.thumb)}" alt="">${it.video ? PLAY : ''}</button>`
    : it.video ? `<button class="hthumb n" data-vshow aria-label="영상 보기">${PLAY}</button>` : '';
  const busy = it.status === 'saving';
  const th2 = busy ? `<div class="hthumb n dl"><b>${it.progress || 0}%</b></div>` : !it.video && canGetVideo(it) && it.thumb ? th.replace('<button class="hthumb"', '<button class="hthumb" data-dlask') : th;
  const verr = !busy && it.videoError ? `<div class="vfail"><b>영상을 못 받았어요</b><span>${esc(it.videoError)}</span><button class="chip dark" data-dlvideo>다시 시도</button></div>` : '';
  sc.querySelector('.meta').innerHTML = `<div class="s">${esc(PLAT[it.platform] || '')}${it.author ? ' · ' + esc(it.author) : ''} · ${fmtD(it.createdAt)}</div>
    <div class="hrow"><h2>${esc(it.title || it.url)}</h2>${th2}</div>
    <div class="chiprow"><button class="fchip" id="dFolder"><svg class="ic" viewBox="0 0 24 24"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>${esc(fPath(it.folder) || '폴더 없음')}<svg class="ic chev" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button>
    <button class="fchip" id="dCat">${esc(it.category ? catLabel(it.category) : '키워드 없음')}<svg class="ic chev" viewBox="0 0 24 24"><path d="M7 10l5 5 5-5"/></svg></button>
    ${(it.tags || []).map(t => `<button class="tg" data-tag="${esc(t)}">#${esc(t)}</button>`).join('')}
    <button class="add" id="dAddTag" aria-label="태그 추가"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button></div>${verr}`;
  if (!it.video) renderMedia(it);
  renderAi(it);
  renderTips(it);
  renderCaps(it);
  renderTabs(it);
  renderSim(it);
  renderPanes(it);
  syncAcc();
  $('#aSave').disabled = !it.video;
}

function renderPanes(it) {
  const caps = (it.captures || []).length, nc = (it.comments || []).length;
  const ps = [['guide', '가이드']].concat(it.video || caps ? [['caps', '캡처' + (caps ? ' ' + caps : '')]] : [], [['orig', it.platform === 'web' ? '원본' : '원본 · 댓글' + (nc ? ' ' + nc : '')]]);
  if (!ps.some(p => p[0] === curPane)) curPane = 'guide';
  $('#dPanes').innerHTML = `<div class="mtabs">${ps.map(p => `<button data-pane-go="${p[0]}" class="${p[0] === curPane ? 'on' : ''}">${p[1]}</button>`).join('')}</div>`;
  document.querySelectorAll('#dScroll [data-pane]').forEach(el => el.classList.toggle('hide', el.dataset.pane !== curPane));
}

/** 비슷한 메모: 같은 장소 · 태그 · 폴더 · 갈래 (AI 없이 점수로) */
function renderSim(it) {
  const el = $('#dSim'); if (!el) return;
  const pl = new Set(((it.ai && it.ai.places) || []).map(p => p.name)), tg = new Set(it.tags || []);
  const sim = items.filter(x => x.id !== it.id && x.status === 'done').map(x => {
    let sc = ((x.ai && x.ai.places) || []).filter(p => pl.has(p.name)).length * 3 + (x.tags || []).filter(t => tg.has(t)).length * 2;
    if (it.folder && x.folder === it.folder) sc += 2;
    if (it.category && x.category === it.category) sc += 1;
    return { x, sc };
  }).filter(o => o.sc >= 2).sort((a, b) => b.sc - a.sc).slice(0, 6);
  el.innerHTML = sim.length ? `<div class="simh">비슷한 메모</div><div class="simg">${sim.map(({ x }) => `<button class="simc" data-id="${esc(x.id)}">${x.thumb ? `<img src="${fileUrl(x.id, x.thumb)}" alt="" loading="lazy">` : ''}<b>${esc(x.title || x.url)}</b></button>`).join('')}</div>` : '';
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
  el.innerHTML = accHead('tips', (it.photos || []).length && !it.video ? '사진 · 댓글 꿀팁' : it.ai.video ? '영상 · 댓글 꿀팁' : '꿀팁', tips.length) + `<div class="accb"><div class="sec">
    ${tips.map((t, i) => `<div class="tip"><span class="n">${i + 1}</span><div><p>${esc(t.text)}</p><small>${src(t)}</small></div></div>`).join('')}</div></div>`;
}

function renderCaps(it) {
  const caps = it.captures || [];
  const el = $('#dCaps');
  if (!it.video && caps.length === 0) { el.innerHTML = ''; return; }
  el.innerHTML = `<div class="sec"><div class="shots">
    ${caps.map(c => `<button class="shot" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span>${c.ai ? '<span class="aib">AI</span>' : ''}</button>`).join('')}
    ${it.video ? '<button class="shot plus" id="dCapAdd" aria-label="영상에서 캡처"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button>' : ''}
  </div></div>`;
}

function renderTabs(it) {
  const tabs = it.platform === 'web'
    ? [['body', '본문']]
    : [['desc', '설명']].concat((it.ai && (it.ai.transcript || []).length) ? [['ts', '받아쓰기']] : [], (it.photos || []).some(p => p.text) ? [['ph', '사진 글자']] : [], [['cmt', '댓글 ' + (it.comments || []).length]]);
  if (!tabs.some(t => t[0] === curTab)) curTab = tabs[0][0];
  const idx = tabs.findIndex(t => t[0] === curTab);
  $('#dTabs').innerHTML = tabs.length < 2 ? '' : `<div class="tabs"><i style="width:calc((100% - 6px) / ${tabs.length});transform:translateX(${idx * 100}%)"></i>
    ${tabs.map(t => `<button data-tab="${t[0]}" class="${t[0] === curTab ? 'on' : ''}">${t[1]}</button>`).join('')}</div>`;
  let body = '';
  if (curTab === 'desc') {
    const dk = it.ai && it.ai.desc_ko;
    body = it.desc ? (dk ? `<div class="kobox"><small>번역</small><p class="p">${esc(dk)}</p></div><details class="orig"><summary>원문 보기</summary>${longText(it.desc)}</details>` : longText(it.desc)) : '<div class="none">설명이 없어요</div>';
  } else if (curTab === 'ph') {
    body = it.photos.map((p, i) => p.text ? `<div class="phx"><b>${i + 1}</b><p class="p">${esc(p.text)}</p></div>` : '').join('');
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
    const ordered = cs.filter(c => c.star).concat(cs.filter(c => !c.star && !cmtJunk(c)), cs.filter(c => !c.star && cmtJunk(c)));
    const nTop = ordered.filter(c => c.star || !cmtJunk(c)).length;
    body = tools || ordered.map((c, i) => (i === nTop ? `<button class="more" data-cmtmore>나머지 ${ordered.length - nTop}개 보기</button><div class="hide" id="cmtRest">` : '') + `<div class="cmt${c.star ? ' star' : ''}"><div class="h"><b>${c.star ? '★ ' : ''}@${esc(c.a)}</b><span>♡ ${c.l || 0}</span></div><p>${esc(c.t)}</p>
      ${(c.r || []).map(r => `<div class="rep"><div class="h"><b>@${esc(r.a)}${r.own ? '<span class="own">작성자</span>' : ''}</b><span>♡ ${r.l || 0}</span></div><p>${esc(r.t)}</p></div>`).join('')}</div>`).join('') + (ordered.length > nTop ? '</div>' : '') + (ref ? '<button class="refetch" data-refetch>댓글 다시 가져오기</button>' : '');
  }
  $('#dBody').innerHTML = `<div class="sec">${body}</div>`;
  const vb = it.video ? '<button class="chip" data-dlvideo>영상 다시 받기</button>' : canGetVideo(it) ? '<button class="chip dark" data-dlvideo>영상 받기</button>' : '';
  $('#dRun').innerHTML = runInfoHtml(it) || vb ? `<div class="sec">${vb ? `<div class="bt" style="margin-bottom:${runInfoHtml(it) ? 10 : 0}px">${vb}${it.videoError ? `<span class="why">${esc(it.videoError)}</span>` : ''}</div>` : ''}${runInfoHtml(it)}</div>` : '';
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
  if (it.ai && (it.ai.places || []).length) L.push('', '── ' + (it.ai.listName || '장소') + ' ──', ...it.ai.places.map((p, i) => (i + 1) + '. ' + p.name + (+p.t >= 0 ? ' (' + fmtT(p.t) + ')' : '') + ' — ' + plain(p.info)));
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

// ── 영상이 없는 메모의 위쪽: 사진 넘겨 보기 · 대표 그림 · (인스타) 사진 가져오기 ──
function renderMedia(it) {
  const el = $('#dMedia'); if (!el) return;
  const ph = it.photos || [];
  const key = [it.id, ph.length, it.thumb, it.photoBusy, it.photoError].join('|');
  if (el.dataset.k === key) return;
  el.dataset.k = key;
  let h = '';
  if (ph.length) {
    h = `<div class="photos" id="dPhotos">${ph.map(p => `<img src="${fileUrl(it.id, p.file)}" alt="" loading="lazy">`).join('')}</div>` +
      (ph.length > 1 ? `<div class="pdots">${ph.map((_, i) => `<i class="${i ? '' : 'on'}"></i>`).join('')}</div>` : '');
  }
  if (!ph.length || ph.length === 1 && it.thumb === 'thumb.jpg') {
    if (it.platform === 'instagram' && !it.video && !it.duration && !/릴스|영상/.test(it.kind || '')) h += `<div class="sec getph">${it.photoBusy ? '<span class="dot"></span><b>사진 받는 중</b>' : `<b>${it.photoError ? '사진을 못 받았어요' : ph.length ? '사진이 1장만 있어요' : '사진이 아직 없어요'}</b><button class="chip dark" data-igphotos>${it.photoError ? '다시 받기' : '게시물 사진 전부 받기'}</button>`}</div>`;
  }
  el.innerHTML = h;
  const g = $('#dPhotos');
  if (g && ph.length > 1) g.addEventListener('scroll', () => {
    const i = Math.round(g.scrollLeft / g.clientWidth);
    el.querySelectorAll('.pdots i').forEach((d, k) => d.classList.toggle('on', k === i));
  }, { passive: true });
}
document.addEventListener('click', e => {
  if (e.target.closest('[data-igphotos]') && cur) { const it = byId(cur); it.photoBusy = true; renderMedia(it); A.igPhotos && A.igPhotos(cur); }
});

/** 쓸모없어 보이는 댓글: 정보 ★ · 작성자 답글이 없고, @태그 · 이모지 · ㅋㅋ 빼면 6자도 안 됨 (GeminiPrompt.kt 의 junkComment 와 같게) */
function cmtJunk(c) {
  if ((c.r || []).some(r => r.own)) return false;
  return String(c.t || '').replace(/@\S+/g, '').replace(/[\p{Extended_Pictographic}\s!?.,~ㅋㅎㅠㅜ♡❤️]/gu, '').length < 6;
}

/** 영상을 받을 수 있는 메모: 유튜브 · 인스타 영상 (사진 게시물 제외) */
const canGetVideo = it => (it.platform === 'youtube' || it.platform === 'instagram') && !(it.photos || []).length && !/사진|게시물/.test(it.kind || '');

/** 안드로이드 기본 플레이어로 재생 (갤러리와 같은 방식) */
function playNative(it, t) { A.playNative(it.id, it.video, Math.max(0, +t || 0)); }
