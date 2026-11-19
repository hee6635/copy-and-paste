'use strict';
/* 앱에서 오는 소식(App) · 누르기 · 버튼 연결 · 시작 (맨 마지막에 불러옴) */
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
  onOcr(id, o) {
    if (cur !== id || !live || !$('#lv')) return;
    if (o.p != null) { const b = $('#lvProg i'); if (b) b.style.transform = `scaleX(${Math.min(1, o.p)})`; }
    if (o.done) { const b = $('#lvProg'); if (b) b.classList.add('end'); return; }
    if (!o.text) return;
    const list = $('#lvTsList'); if (!list) return;
    const w = list.querySelector('.watch'); if (w) w.remove();
    const row = document.createElement('div'); row.className = 'tl ph'; row.dataset.t = o.t; row.dataset.k = String(o.text).slice(0, 6);
    row.innerHTML = `<b>${fmtT(o.t)}</b><span></span><small>폰</small>`;
    const after = [...list.querySelectorAll('.tl')].find(r => +r.dataset.t > +o.t);
    list.insertBefore(row, after || null);
    typeIn(row.querySelector('span'), '[화면] ' + o.text, 90);
  },
  onAsk(json) {
    const r = JSON.parse(json); const h = askHist.find(x => x.pending); if (!h) return;
    h.pending = false; if (r.error) h.error = r.error; else { h.a = r.answer || ''; h.refs = (r.refs || []).filter(id => byId(id)); }
    renderAsk();
  },
  onChapters(id, o) {
    if (cur !== id || !live || !$('#lv')) return;
    let box = $('#lvCh');
    if (!box) {
      collapse($('#lvDesc')); collapse($('#lvTs')); collapse($('#lvProg'));
      box = document.createElement('div'); box.id = 'lvCh'; box.className = 'sec lvpl';
      box.innerHTML = '<div class="parts"></div><div class="sh"><b>목차</b></div><div class="lvchl"></div>';
      $('#lv').appendChild(box); live.ch = 0;
    }
    box.querySelector('.parts').innerHTML = Array.from({ length: o.parts }, (_, i) => `<i class="${i < o.part ? 'd' : i === o.part ? 'r' : ''}"></i>`).join('');
    const list = box.querySelector('.lvchl');
    for (; live.ch < o.chapters.length; live.ch++) {
      const c = o.chapters[live.ch];
      typeQ = typeQ.then(() => new Promise(r => {
        const d = document.createElement('div'); d.className = 'pr ch pop';
        d.innerHTML = `<div class="pb"><b><span><span class="tm">${fmtT(c.t)}</span> ${esc(c.title)}</span></b><p>${rich(c.info || '')}</p></div>`;
        list.appendChild(d); setTimeout(r, 220);
      }));
    }
  },
  onPartial(id, txt) { if (cur === id && $('#detail').classList.contains('open')) liveUpdate(txt); },
  onDiag(json) {
    const o = JSON.parse(json);
    if (o.end) { diagRunning = false; $('#dgGo').classList.add('on'); if (o.error) toast('검사 중 오류'); const bad = diagSteps.filter(s => s.state === 'fail').length; toast(bad ? '✕ ' + bad + '곳에서 막혔어요' : '모두 통과했어요'); return; }
    const i = diagSteps.findIndex(s => s.i === o.i);
    if (i >= 0) diagSteps[i] = o; else diagSteps.push(o);
    renderDiag();
  },
  onResume() { load(); if ($('#settings').classList.contains('open')) renderSettings(); },
  insets(top, bottom) {
    document.documentElement.style.setProperty('--st', top + 'px');
    document.documentElement.style.setProperty('--sb', bottom + 'px');
  },
};

// ── 누르기 ──
document.addEventListener('click', e => {
  const t = e.target;
  if (lpFired) { lpFired = false; return; }
  const card = t.closest('.card'); if (card) return selecting ? toggleSel(card.dataset.id) : openDetail(card.dataset.id);
  const ct = t.closest('[data-cat]'); if (ct) { cat = cat === ct.dataset.cat ? '' : ct.dataset.cat; renderHome(); return; }
  const ft = t.closest('[data-ft]'); if (ft) { fTop = ft.dataset.ft; fSub = ''; renderHome(); return; }
  const fs = t.closest('[data-fs]'); if (fs) { fSub = fs.dataset.fs; renderHome(); return; }
  const mv = t.closest('[data-mv]'); if (mv) return moveTo(mv.dataset.mv);
  const fn = t.closest('[data-fnew]'); if (fn) return newFolder(fn.dataset.fnew);
  if (t.closest('#mvNew')) return newFolder(null);
  const op = t.closest('[data-opt]'); if (op) { const r = optRows[+op.dataset.opt]; closeSheet('optSheet'); setTimeout(() => r && r[1](), 250); return; }
  if (t.closest('#selX')) return endSelect();
  if (t.closest('#selAll')) { const v = visible(); if (sel.size === v.length) sel.clear(); else v.forEach(it => sel.add(it.id)); renderHome(); return; }
  if (t.closest('#sMove')) return openMove([...sel]);
  if (t.closest('#sOrder')) return openOrder();
  if (t.closest('#sDel')) return confirmAsk(sel.size + '개를 지울까요?', '지우기', () => {
    const ids = [...sel]; A.removeMany(JSON.stringify(ids)); items = items.filter(x => !ids.includes(x.id));
    meta.order = meta.order.filter(id => !ids.includes(id)); saveMeta(); endSelect(); toast(ids.length + '개를 지웠어요');
  });
  if (t.closest('#ordDone')) { saveOrder(); closePage('order'); if (selecting) endSelect(); toast('순서를 바꿨어요'); return; }
  if (t.closest('[data-dlvideo]')) { A.downloadVideo(cur); toast('영상을 받는 중이에요'); return; }
  const cs2 = t.closest('[data-chsub]'); if (cs2) { cs2.nextElementSibling.classList.toggle('hide'); cs2.textContent = cs2.nextElementSibling.classList.contains('hide') ? '이 부분 자막 ⌄' : '접기 ⌃'; return; }
  const lm = t.closest('[data-lim]'); if (lm) { const m = lm.dataset.lim; return ask(mLabel(m) + ' 하루 한도', '한도 (번)', v => { const n = parseInt(v, 10); if (n > 0) { A.setLimit(m, n); renderUsage(); } }, lm.dataset.v); }
  const dm = t.closest('[data-dm]'); if (dm) return setDockMode(dm.dataset.dm);
  const ex = t.closest('[data-askex]'); if (ex) return sendAsk(ex.dataset.askex);
  const arw = t.closest('.arow'); if (arw) return openDetail(arw.dataset.id);
  if (t.closest('[data-slides]')) return openSlides();
  if (t.closest('#sUsage')) return openUsage();
  if (t.closest('#sBackup')) return A.backup(false);
  if (t.closest('#sBackupV')) return A.backup(true);
  if (t.closest('#sRestore')) return confirmAsk('백업 파일로 되살릴까요? 지금 메모는 백업 내용으로 바뀌어요', '되살리기', () => A.restore());
  if (t.closest('#dFolder')) return openMove([cur]);
  if (t.closest('#dCat')) {
    const it = byId(cur);
    return optSheet('키워드', ALL_CATS.map(k => [catLabel(k) + (it.category === k ? '  ✓' : ''), () => { it.category = k; A.update(cur, JSON.stringify({ category: k })); renderDetail(false); renderHome(); }]));
  }
  if (t.closest('[data-sugg]')) { const it = byId(cur); const fid = ensureFolder(it.ai.folder); if (fid) { moveIds = [cur]; moveTo(fid); renderChips(); } return; }
  if (t.closest('[data-aistop]')) { A.aiCancel(); toast('중지하는 중…'); return; }
  const rl = t.closest('[data-runlog]'); if (rl) { rl.nextElementSibling.classList.toggle('hide'); rl.classList.toggle('open'); return; }
  if (t.closest('[data-airedo]')) { const it = byId(cur); it.aiBusy = 'AI 정리 준비 중'; it.aiBusyAt = Date.now(); it.aiError = ''; renderAi(it); A.aiRedo(cur); return; }
  const chk = t.closest('[data-chk]'); if (chk) {
    const it = byId(cur); const [k, i] = chk.dataset.chk.split(':'); const n = +i;
    it.checks = it.checks || {}; const a = it.checks[k] = it.checks[k] || [];
    const j = a.indexOf(n); if (j >= 0) a.splice(j, 1); else a.push(n);
    chk.classList.toggle('on', a.includes(n)); A.update(cur, JSON.stringify({ checks: it.checks })); return;
  }
  const sk = t.closest('[data-seek]'); if (sk) {
    const v = $('#dVideo'); const sec = Math.floor(+sk.dataset.seek); const it = byId(cur);
    if (v) { v.currentTime = sec; v.play(); $('#dScroll').scrollTo({ top: 0, behavior: 'smooth' }); }
    else if (it && it.platform === 'youtube') { const m = it.url.match(/(?:youtu\.be\/|\/shorts\/|[?&]v=)([A-Za-z0-9_-]{11})/); A.open(m ? 'https://www.youtube.com/watch?v=' + m[1] + '&t=' + sec + 's' : it.url); }
    else toast('영상을 저장하지 않아서 ' + fmtT(sec) + ' 장면은 원본에서 봐 주세요');
    return;
  }
  const sp = t.closest('[data-sp]'); if (sp) { A.setAiSpeed(sp.dataset.sp); const bs = [...$('#sSpeed').querySelectorAll('button')]; bs.forEach(b => b.classList.toggle('on', b === sp)); $('#sSpeed i').style.transform = `translateX(${bs.indexOf(sp) * 100}%)`; return; }
  if (t.closest('#sWatch')) { const on = !t.closest('#sWatch').querySelector('.sw').classList.contains('on'); A.setWatch(on); t.closest('#sWatch').querySelector('.sw').classList.toggle('on', on); return; }
  if (t.closest('#sBat')) { A.batteryUnrestrict(); return; }
  if (t.closest('#sAiOn')) { const on = !t.closest('#sAiOn').classList.contains('on'); A.setAiOn(on); renderSettings(); return; }
  if (t.closest('#sAiAll')) {
    const todo = items.filter(x => x.status === 'done' && (!x.ai || !x.category));
    if (!todo.length) { toast('정리할 메모가 없어요'); return; }
    return confirmAsk(todo.length + '개를 AI로 정리할까요?', '정리하기', () => { todo.forEach(x => { x.aiBusy = 'AI 대기 중'; A.aiRedo(x.id); }); toast('하나씩 정리할게요'); renderHome(); });
  }
  const dg = t.closest('[data-diag]'); if (dg) { const it = byId(dg.dataset.diag); return openDiag(it ? it.url : '', it, true); }
  if (t.closest('[data-refetch]')) { const it = byId(cur); if (it) { it.commentBusy = true; renderTabs(it); A.refetchComments(cur); } return; }
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
  const acc = t.closest('[data-acc]'); if (acc) { const k = acc.dataset.acc; accOpen[k] = !accOpen[k]; syncAcc(); return; }
  const cs = t.closest('[data-csel]'); if (cs) { const id = cs.dataset.csel; if (capSel.has(id)) capSel.delete(id); else capSel.add(id); renderCapSel(); return; }
  const shot = t.closest('[data-cap]'); if (shot) return openCap(shot.dataset.cap);
  const ar = t.closest('[data-att]'); if (ar) { attSel = ar.dataset.att; document.querySelectorAll('#attList .rad').forEach(b => b.classList.toggle('on', b === ar)); return; }
  if (t.closest('[data-capmove]')) { const it = byId(cur); const c = it.captures.find(x => x.id === curCap); return openAttach({ kind: 'move', ids: [curCap], t: c.t, img: fileUrl(it.id, c.file), info: fmtT(c.t) + ' 캡처', cur: c.at ? c.at.sec + ':' + c.at.i : '' }); }
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
  if (t.closest('#sDiag')) { const last = items.find(x => x.url); return openDiag(last ? last.url : '', null, false); }
  if (t.closest('#sTool')) { toast('확인하는 중…'); return A.updateTool(); }
});

$('#btnSettings').onclick = openSettings;
$('#btnTidy').onclick = openTidy;
$('#dgGo').onclick = runDiag;
$('#dgCopy').onclick = () => { A.copy(diagText()); toast('결과를 복사했어요'); };
$('#dim').onclick = () => { const top = stack[stack.length - 1]; if (top && top.type === 'sheet') closeSheet(top.id); };
$('#ssGo').onclick = doSave;
$('#ssMemo').addEventListener('keydown', e => { if (e.key === 'Enter') doSave(); });
$('#askGo').onclick = () => { const v = $('#askInput').value; closeSheet('askSheet'); askCb && askCb(v); };
$('#askInput').addEventListener('keydown', e => { if (e.key === 'Enter') $('#askGo').click(); });
$('#cfNo').onclick = () => closeSheet('confirmSheet');
$('#cfYes').onclick = () => { closeSheet('confirmSheet'); cfCb && cfCb(); };

$('#paste').addEventListener('input', pasteState);
$('#pasteGo').onclick = () => {
  if (dockMode === 'ask') { const q = $('#paste').value.trim(); if (!q) return; $('#paste').value = ''; pasteState(); return openAsk(q); }
  const u = findUrl($('#paste').value); if (u) openSave(u); else toast('링크를 붙여 넣어 주세요');
};
$('#askSend').onclick = () => sendAsk($('#askQ').value);
$('#askQ').addEventListener('keydown', e => { if (e.key === 'Enter') sendAsk($('#askQ').value); });
$('#askClear').onclick = () => { askHist = []; renderAsk(); };
$('#slTrack').addEventListener('scroll', () => { clearTimeout(window.__slT); window.__slT = setTimeout(slideActive, 120); });
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
$('#aCopy').onclick = () => {
  const it = byId(cur); if (!it) return;
  const done = (txt, what) => { A.copy(txt); toast(what + ' 복사했어요'); };
  const rows = [['전체', () => done(itemText(it), '전체를')]];
  if (it.ai && (it.ai.summary || []).length) rows.push(['요약', () => done(it.ai.summary.map(plain).join('\n'), '요약을')]);
  if (it.ai && (it.ai.tips || []).length) rows.push(['댓글 꿀팁', () => done(it.ai.tips.map((x, i) => (i + 1) + '. ' + x.text).join('\n'), '꿀팁을')]);
  if (it.ai && (it.ai.transcript || []).length) rows.push(['받아쓰기', () => done(it.ai.transcript.map(x => '[' + fmtT(x.t) + '] ' + x.text + (x.ko ? ' (' + x.ko + ')' : '')).join('\n'), '받아쓰기를')]);
  if (it.ai && it.ai.desc_ko) rows.push(['설명 번역', () => done(it.ai.desc_ko, '번역을')]);
  if (it.desc) rows.push(['설명', () => done(it.desc, '설명을')]);
  if (it.body) rows.push(['본문', () => done(it.body, '본문을')]);
  if ((it.comments || []).length) rows.push(['댓글', () => done(it.comments.map((c, i) => (i + 1) + '. @' + c.a + ' ' + c.t + (c.r || []).map(r => '\n   ↳ @' + r.a + (r.own ? ' [작성자]' : '') + ' ' + r.t).join('')).join('\n'), '댓글을')]);
  if (it.memo) rows.push(['내 메모', () => done(it.memo, '메모를')]);
  rows.push(['링크', () => done(it.url, '링크를')]);
  optSheet('복사', rows);
};
$('#aTxt').onclick = () => { const it = byId(cur); if (it) A.shareText(it.title || 'memo', itemText(it)); };
$('#aDel').onclick = () => confirmAsk('이 메모를 지울까요?', '지우기', () => {
  const id = cur; closePage('detail'); A.remove(id); items = items.filter(x => x.id !== id); meta.order = meta.order.filter(x => x !== id); saveMeta(); renderHome();
});

$('#cropFull').onclick = () => openAttach({ kind: 'new', data: cropResult(true), t: cropT, img: cropSrc, info: fmtT(cropT) + ' · 전체' });
$('#cropOk').onclick = () => { const d = cropResult(false); openAttach({ kind: 'new', data: d, t: cropT, img: d, info: fmtT(cropT) + ' · 잘라낸 부분' }); };
$('#attGo').onclick = doAttach;
$('#csAll').onclick = () => { const caps = byId(cur).captures || []; if (capSel.size === caps.length) capSel.clear(); else caps.forEach(c => capSel.add(c.id)); renderCapSel(); };
$('#csDel').onclick = () => confirmAsk(capSel.size + '개 캡처를 지울까요?', '지우기', () => {
  const r = A.removeCaptures(cur, JSON.stringify([...capSel])); if (r) replace(JSON.parse(r));
  closePage('capsel'); renderDetail(false); toast('지웠어요');
});
$('#csMove').onclick = () => { const it = byId(cur); const first = it.captures.find(c => capSel.has(c.id)); openAttach({ kind: 'move', ids: [...capSel], t: first ? first.t : -1, img: first ? fileUrl(it.id, first.file) : '', info: capSel.size + '개 캡처' }); };
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
if (!window.Android) window.__dev = { openCrop, liveUpdate, liveStart: (id) => { const it = byId(id); it.aiBusy = 'AI 받아쓰는 중'; it.aiBusyAt = Date.now(); renderDetail(false); } };
