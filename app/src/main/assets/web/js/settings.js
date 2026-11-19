'use strict';
/* 설정 · 검사 */
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
      <button class="row" id="sUsage"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/></svg></span><span class="rt">AI 사용량</span><span class="rv">자세히</span></button>
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
    </div>
    <div class="group">
      <button class="row" id="sBackup"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg></span><span class="rt">백업 파일 만들기</span><span class="rv">영상 빼고</span></button>
      <button class="row" id="sBackupV"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M12 4v11M7 10l5 5 5-5M5 20h14"/></svg></span><span class="rt">백업 파일 만들기</span><span class="rv">영상 포함</span></button>
      <button class="row" id="sRestore"><span class="icb"><svg class="ic" viewBox="0 0 24 24"><path d="M20 12a8 8 0 1 1-2.3-5.7M20 4v5h-5"/></svg></span><span class="rt">백업에서 되살리기</span></button>
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

/** AI 사용량: 오늘 · 이번 달(모델별) · 마지막으로 걸린 한도 · 다음 초기화 */
function openUsage() { renderUsage(); openPage('usagePage'); }
function renderUsage() {
  let u = {}; try { u = JSON.parse(A.usage()); } catch (e) {}
  const tbl = (o, title) => {
    const ks = Object.keys(o || {}); if (!ks.length) return `<div class="group"><div class="row"><span class="rt">${title}</span><span class="rv">0회</span></div></div>`;
    const tot = ks.reduce((a, k) => a + (o[k].n || 0), 0);
    return `<div class="sec"><div class="sh"><b>${title}</b><span class="rv">${tot}회</span></div><div class="ust"><span class="ush" style="text-align:left">모델</span><span class="ush">횟수</span><span class="ush">토큰</span>
      ${ks.map(k => `<b>${esc(mLabel(k))}</b><span>${o[k].n}</span><span>${(o[k].tok || 0).toLocaleString()}</span>`).join('')}</div></div>`;
  };
  const l = u.last429 || {};
  const lim = l.at ? `<div class="sec"><div class="sh"><b>마지막으로 걸린 한도</b><span class="rv">${new Date(l.at).toLocaleString('ko-KR')}</span></div>
    <div class="ust2"><b>모델</b><span>${esc(mLabel(l.model || ''))}</span>
    ${l.metric ? `<b>한도</b><span>${esc(String(l.metric).split('/').pop())}${l.value ? ' · ' + esc(l.value) + '번' : ''}</span>` : ''}
    ${l.retry ? `<b>다시 되는 때</b><span>${esc(l.retry)} 뒤</span>` : ''}</div>
    ${l.msg ? `<p class="p" style="font-size:13px;color:var(--sub);margin-top:8px">${esc(l.msg)}</p>` : ''}</div>` : '';
  // 남은 횟수(추정) · 예상
  const lims = u.limits || {}, today = u.today || {}, month = u.month || {};
  const remain = Object.keys(lims).map(m => ({ m, lim: lims[m], used: (today[m] || {}).n || 0 }));
  const monTot = Object.keys(month).reduce((a, k) => a + (month[k].n || 0), 0);
  const monTok = Object.keys(month).reduce((a, k) => a + (month[k].tok || 0), 0);
  const perMemo = u.memos ? monTot / u.memos : 1;
  const lite = remain.find(r => r.m === 'gemini-flash-lite-latest') || remain[0];
  const canMore = lite ? Math.max(0, Math.floor((lite.lim - lite.used) / Math.max(1, perMemo))) : 0;
  const proj = u.dom ? Math.round(monTot / u.dom * u.dim) : 0;
  const projTok = u.dom ? Math.round(monTok / u.dom * u.dim) : 0;
  const remainHtml = `<div class="sec"><div class="sh"><b>오늘 남은 횟수 (추정)</b></div><div class="ust">
      <span class="ush" style="text-align:left">모델</span><span class="ush">남음</span><span class="ush">한도</span>
      ${remain.map(r => `<b>${esc(mLabel(r.m))}</b><span>${Math.max(0, r.lim - r.used)}</span><button class="limb" data-lim="${esc(r.m)}" data-v="${r.lim}">${r.lim} ✎</button>`).join('')}</div></div>
    <div class="sec"><div class="sh"><b>예상</b></div><div class="ust2">
      <b>오늘 더 저장할 수 있는 메모</b><span>약 ${canMore}개</span>
      <b>메모 하나에 평균</b><span>${perMemo.toFixed(1)}번</span>
      <b>이번 달 예상</b><span>${proj.toLocaleString()}번 · ${projTok.toLocaleString()} 토큰</span></div></div>`;
  $('#usScroll').innerHTML = remainHtml + tbl(u.today, '오늘') + tbl(u.month, '이번 달') + lim +
    `<div class="group"><div class="row"><span class="rt">하루 한도 초기화</span><span class="rv">${esc(u.reset || '')}</span></div></div>`;
}
