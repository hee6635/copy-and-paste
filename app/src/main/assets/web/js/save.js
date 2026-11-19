'use strict';
/* 저장 창 (공유 · 붙여넣기) */
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

function pasteState() { const v = $('#paste').value; $('#pasteGo').classList.toggle('on', dockMode === 'ask' ? !!v.trim() : !!findUrl(v)); }
