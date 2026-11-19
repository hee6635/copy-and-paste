'use strict';
/* 링크메모 화면. 데이터는 앱(Android 브리지)이 저장하고, 화면은 그리기만 한다. */
(function () {
  const $ = (s, r = document) => r.querySelector(s);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const A = window.Android || devBridge();

  let items = [];
  let filter = '전체';
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

  function load() {
    try { items = JSON.parse(A.items()) || []; } catch (e) { items = []; }
    renderHome();
  }

  // ── 홈 ──
  function allTags() {
    const set = [];
    items.forEach(it => (it.tags || []).forEach(t => { if (!set.includes(t)) set.push(t); }));
    return set;
  }

  function matches(it) {
    if (filter !== '전체' && !(it.tags || []).includes(filter)) return false;
    if (!query) return true;
    const q = query.toLowerCase();
    const hay = [it.title, it.author, it.desc, it.body, it.memo, it.url, (it.tags || []).join(' '),
      (it.comments || []).map(c => c.a + ' ' + c.t).join(' '),
      (it.captures || []).map(c => c.text + ' ' + c.memo).join(' ')].join(' ').toLowerCase();
    return q.split(/\s+/).every(w => hay.includes(w));
  }

  function renderChips() {
    const tags = allTags();
    if (filter !== '전체' && !tags.includes(filter)) filter = '전체';
    const el = $('#chips');
    el.classList.toggle('hide', tags.length === 0);
    el.innerHTML = ['전체'].concat(tags).map(t =>
      `<button class="pill${t === filter ? ' on' : ''}" data-t="${esc(t)}">${esc(t)}</button>`).join('');
  }

  function jobHtml(it) {
    const err = it.status === 'error';
    const host = hostOf(it.url);
    const p = Math.max(0, Math.min(100, it.progress || 0));
    return `<div class="job${err ? ' err' : ''}" data-id="${esc(it.id)}">
      <div class="jt">${platIcon(it.platform)}</div>
      <div class="jb"><div class="jh"><b>${esc(err ? host : (it.stage || '저장 중'))}</b><span>${err ? esc(it.error || '실패') : p + '%'}</span></div>
      ${err ? '' : `<div class="bar"><i style="transform:scaleX(${p / 100})"></i></div>`}</div>
      ${err ? `<div class="jbtns"><button class="jbtn" data-errcopy="${esc(it.id)}">오류 복사</button><button class="jbtn" data-del="${esc(it.id)}">지우기</button><button class="jbtn dark" data-retry="${esc(it.id)}">다시</button></div>` : ''}
    </div>`;
  }

  function cardHtml(it) {
    const ratio = it.w && it.h ? Math.min(1.78, Math.max(.75, it.h / it.w)) : (it.platform === 'web' ? .62 : 1.35);
    const thumb = it.thumb ? `<div class="th" style="aspect-ratio:1/${ratio.toFixed(3)}"><img loading="lazy" src="${fileUrl(it.id, it.thumb)}" alt="">${it.kind && it.kind !== '웹' ? `<span class="badge">${esc(it.kind)}${it.duration ? ' ' + fmtT(it.duration) : ''}</span>` : ''}</div>` : '';
    const memo = it.memo ? `<div class="cm1">${esc(it.memo)}</div>` : '';
    const tags = (it.tags || []).slice(0, 2).map(t => `<span class="tag">${esc(t)}</span>`).join('');
    return `<button class="card" data-id="${esc(it.id)}">${thumb}<div class="cb"><div class="ct">${esc(it.title || it.url)}</div>
      <div class="cs">${esc(PLAT[it.platform] || '')}${it.author ? ' · ' + esc(it.author) : ''}</div>${memo}${tags}</div></button>`;
  }

  function renderHome() {
    renderChips();
    $('#jobs').innerHTML = items.filter(it => it.status === 'saving' || it.status === 'error').map(jobHtml).join('');
    const list = items.filter(it => it.status === 'done' && matches(it));
    // 두 줄에 높이가 비슷하게 나누기
    const cols = [[], []], h = [0, 0];
    list.forEach(it => {
      const est = (it.thumb ? (it.w && it.h ? Math.min(1.78, Math.max(.75, it.h / it.w)) : 1.2) * 170 : 0) + 90 + (it.memo ? 34 : 0);
      const k = h[0] <= h[1] ? 0 : 1; cols[k].push(it); h[k] += est;
    });
    const colsEl = $('#grid').children;
    colsEl[0].innerHTML = cols[0].map(cardHtml).join('');
    colsEl[1].innerHTML = cols[1].map(cardHtml).join('');
    $('#empty').classList.toggle('hide', items.length > 0 || !!query);
  }

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
    if (top.type === 'sheet') closeSheet(top.id); else closePage(top.id);
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
      sc.innerHTML = media + `<div class="meta"></div><div id="dCaps"></div><div id="dTabs"></div><div id="dBody"></div>
        <div class="sec"><label class="memo"><small>내 메모</small><textarea id="dMemo" rows="2"></textarea></label></div>`;
      $('#dMemo').value = it.memo || '';
      autoGrow($('#dMemo'));
    }
    sc.querySelector('.meta').innerHTML = `<div class="s">${esc(PLAT[it.platform] || '')}${it.author ? ' · ' + esc(it.author) : ''} · ${fmtD(it.createdAt)}</div>
      <h2>${esc(it.title || it.url)}</h2>
      <div class="tags">${(it.tags || []).map(t => `<button class="tg" data-tag="${esc(t)}">#${esc(t)}</button>`).join('')}
      <button class="add" id="dAddTag" aria-label="태그 추가"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button></div>`;
    renderCaps(it);
    renderTabs(it);
    $('#aSave').disabled = !it.video;
  }

  function renderCaps(it) {
    const caps = it.captures || [];
    const el = $('#dCaps');
    if (!it.video && caps.length === 0) { el.innerHTML = ''; return; }
    el.innerHTML = `<div class="sec"><div class="sh"><b>캡처 ${caps.length}</b></div><div class="shots">
      ${caps.map(c => `<button class="shot" data-cap="${esc(c.id)}"><img src="${fileUrl(it.id, c.file)}" alt=""><span class="tc">${fmtT(c.t)}</span></button>`).join('')}
      ${it.video ? '<button class="shot plus" id="dCapAdd" aria-label="지금 장면 캡처"><svg class="ic" viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button>' : ''}
    </div></div>`;
  }

  function renderTabs(it) {
    const tabs = it.platform === 'web'
      ? [['body', '본문']]
      : [['desc', '설명'], ['cmt', '댓글 ' + (it.comments || []).length]];
    if (!tabs.some(t => t[0] === curTab)) curTab = tabs[0][0];
    const idx = tabs.findIndex(t => t[0] === curTab);
    $('#dTabs').innerHTML = tabs.length < 2 ? '' : `<div class="tabs"><i style="width:calc((100% - 6px) / ${tabs.length});transform:translateX(${idx * 100}%)"></i>
      ${tabs.map(t => `<button data-tab="${t[0]}" class="${t[0] === curTab ? 'on' : ''}">${t[1]}</button>`).join('')}</div>`;
    let body = '';
    if (curTab === 'desc') {
      body = it.desc ? longText(it.desc) : '<div class="none">설명이 없어요</div>';
    } else if (curTab === 'body') {
      body = (it.desc ? `<p class="p" style="color:var(--sub);margin-bottom:12px">${esc(it.desc)}</p>` : '') + (it.body ? longText(it.body) : '<div class="none">본문을 읽지 못했어요</div>');
    } else {
      const cs = it.comments || [];
      body = cs.length ? cs.map((c, i) => `<div class="cmt${c.star ? ' star' : ''}"><div class="h"><b>${c.star ? '★ ' : ''}@${esc(c.a)}</b><span>♡ ${c.l || 0}</span></div><p>${esc(c.t)}</p>
        ${(c.r || []).map(r => `<div class="rep"><div class="h"><b>@${esc(r.a)}${r.own ? '<span class="own">작성자</span>' : ''}</b><span>♡ ${r.l || 0}</span></div><p>${esc(r.t)}</p></div>`).join('')}</div>`).join('')
        : '<div class="none">댓글이 없어요</div>';
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
    const r = A.capture(cur, v.currentTime, c.toDataURL('image/jpeg', .9));
    if (r) { replace(JSON.parse(r)); renderCaps(byId(cur)); }
    toast('캡처했어요 ' + fmtT(v.currentTime));
  }

  function itemText(it) {
    const L = [];
    L.push('📌 ' + (it.title || ''));
    L.push([PLAT[it.platform], it.author, new Date(it.createdAt).toLocaleDateString('ko-KR')].filter(Boolean).join(' · '));
    L.push('🔗 ' + it.url);
    if ((it.tags || []).length) L.push(it.tags.map(t => '#' + t).join(' '));
    if (it.memo) L.push('', '── 내 메모 ──', it.memo);
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
        <label class="memo" style="margin-top:8px"><small>메모</small><textarea id="cMemo" rows="1"></textarea></label></div>`;
      $('#cMemo').value = c.memo || ''; autoGrow($('#cMemo'));
    }
    $('#cOcr').innerHTML = `<small>화면 글자</small>${c.ocr === 'busy' ? '읽는 중…' : (c.text ? esc(c.text) : '글자가 없어요')}`;
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
    $('#sScroll').innerHTML = `
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
        <div class="row"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><path d="M12 11v5M12 8h.01"/></svg></span><span class="rt">앱 버전</span><span class="rv">${esc(s.app || '')}</span></div>
      </div>`;
    $('#sIg').onclick = () => {
      if (s.igLogin) confirmAsk('인스타에서 로그아웃할까요?', '로그아웃', () => A.igLogout());
      else A.igLogin();
    };
  }

  // ── 작은 창 ──
  let askCb = null;
  function ask(title, label, cb) {
    askCb = cb; $('#askTitle').textContent = title; $('#askLabel').textContent = label; $('#askInput').value = '';
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
    onResume() { load(); if ($('#settings').classList.contains('open')) renderSettings(); },
    insets(top, bottom) {
      document.documentElement.style.setProperty('--st', top + 'px');
      document.documentElement.style.setProperty('--sb', bottom + 'px');
    },
  };

  // ── 누르기 ──
  document.addEventListener('click', e => {
    const t = e.target;
    const card = t.closest('.card'); if (card) return openDetail(card.dataset.id);
    const pill = t.closest('.pill'); if (pill) { filter = pill.dataset.t; renderHome(); return; }
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
    const shot = t.closest('[data-cap]'); if (shot) return openCap(shot.dataset.cap);
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
    if (t.closest('#sTool')) { toast('확인하는 중…'); return A.updateTool(); }
  });

  $('#btnSettings').onclick = openSettings;
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
  $('#aCopy').onclick = () => { const it = byId(cur); if (it) { A.copy(itemText(it)); toast('복사했어요'); } };
  $('#aTxt').onclick = () => { const it = byId(cur); if (it) A.shareText(it.title || 'memo', itemText(it)); };
  $('#aDel').onclick = () => confirmAsk('이 메모를 지울까요?', '지우기', () => {
    const id = cur; closePage('detail'); A.remove(id); items = items.filter(x => x.id !== id); renderHome();
  });

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

  // ── 컴퓨터에서 화면만 볼 때 쓰는 가짜 브리지 ──
  function devBridge() {
    const now = Date.now();
    let data = [
      { id: 'a', url: 'https://www.instagram.com/reel/x', platform: 'instagram', kind: '릴스', status: 'done', title: '나트랑 시내 맛집 4곳 추천', author: 'yytable', desc: '나트랑 시내에서 현지인이 많이 가는 식당 4곳!\n1. Lai Seafood\n2. Ớt Hiểm Thai Cuisine', duration: 48, w: 720, h: 1280, memo: '12월 여행 때 저녁', tags: ['여행'], createdAt: now,
        comments: [{ a: 'traveler_kim', t: '저녁 6시 전에 가야 웨이팅 없어요', l: 312, r: [{ a: 'yytable', t: '맞아요! 6시 넘으면 30분 넘게 기다려요', l: 20, own: true }] }, { a: 'minji', t: '저장해둡니다', l: 54 }], captures: [] },
      { id: 'b', url: 'https://youtube.com/shorts/y', platform: 'youtube', kind: '쇼츠', status: 'done', title: '피그마 아이콘 파일 50개 이상 무료 배포', author: 'figma.create', desc: '댓글에 icon 남기면 보내드려요', duration: 35, w: 720, h: 1280, tags: ['디자인'], createdAt: now, comments: [], captures: [] },
      { id: 'c', url: 'https://brunch.co.kr/z', platform: 'web', kind: '웹', status: 'done', title: '홈카페 라떼아트 기초', author: 'brunch.co.kr', desc: '', body: '우유 온도는 60도가 좋아요.', tags: [], createdAt: now, comments: [], captures: [] },
      { id: 'd', url: 'https://www.instagram.com/reel/z', platform: 'instagram', status: 'saving', stage: '영상 받는 중', progress: 62, createdAt: now },
      { id: 'e', url: 'https://www.instagram.com/reel/q', platform: 'instagram', status: 'error', error: '인스타 로그인이 필요해요', errorDetail: 'ERROR: login required', createdAt: now },
    ];
    return {
      items: () => JSON.stringify(data),
      add: (url, mode, memo) => JSON.stringify({ id: 'n' + Math.random(), url, mode, memo, platform: 'web', status: 'saving', stage: '대기 중', progress: 0, createdAt: Date.now() }),
      update: () => '', remove: () => {}, retry: () => {}, capture: () => '', removeCapture: () => '',
      open: () => {}, copy: () => {}, shareText: () => {}, exportFile: () => {},
      settings: () => JSON.stringify({ quality: '720', igLogin: false, used: 1.2e9, tool: '2026.11.01', app: '0.1' }),
      setQuality: () => {}, igLogin: () => {}, igLogout: () => {}, deleteVideos: () => {}, updateTool: () => {}, toast: () => {},
    };
  }
})();
