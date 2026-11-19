'use strict';
/* 요약 슬라이드: 장소 · 순서 · 요약 줄마다 한 장. 영상을 저장했으면 그 구간(4초)을 반복, 아니면 캡처 사진 */
function slideData(it) {
  const ai = it.ai || {};
  const capOf = (sec, i) => (it.captures || []).find(c => c.at && c.at.sec === sec && +c.at.i === i);
  if ((ai.places || []).length) return ai.places.map((p, i) => ({ t: +p.t, title: (i + 1) + '. ' + p.name, text: p.info || '', cap: capOf('places', i) }));
  if (ai.recipe && (ai.recipe.steps || []).length) return ai.recipe.steps.map((x, i) => { const c = capOf('steps', i); return { t: c ? c.t : -1, title: '순서 ' + (i + 1), text: x, cap: c }; });
  return (ai.summary || []).map((x, i) => { const c = capOf('summary', i); return { t: c ? c.t : -1, title: '', text: x, cap: c }; });
}
function canSlide(it) { return slideData(it).some(s => s.cap || (it.video && s.t >= 0)); }
function openSlides() {
  const it = byId(cur); if (!it) return;
  const sl = slideData(it);
  $('#slTitle').textContent = it.title || '';
  $('#slTrack').innerHTML = sl.map((s, i) => {
    const media = it.video && s.t >= 0
      ? `<video data-st="${Math.max(0, s.t - 0.3)}" src="${fileUrl(it.id, it.video)}#t=${Math.max(0, s.t - 0.3)}" muted playsinline preload="metadata"></video>`
      : s.cap ? `<img src="${fileUrl(it.id, s.cap.file)}" alt="">` : '<div class="slnone"></div>';
    return `<div class="slide" data-i="${i}"><div class="slm">${media}${s.t >= 0 ? `<span class="tc">${fmtT(s.t)}</span>` : ''}</div>
      <div class="sltx">${s.title ? `<b>${esc(s.title)}</b>` : ''}<p>${rich(s.text)}</p><small>${i + 1} / ${sl.length}</small></div></div>`;
  }).join('');
  $('#slDots').innerHTML = sl.map((_, i) => `<i class="${i ? '' : 'on'}"></i>`).join('');
  openPage('slides');
  $('#slTrack').scrollLeft = 0;
  setTimeout(slideActive, 450);
}
/** 보이는 장만 재생 (4초 구간 반복) */
function slideActive() {
  const tr = $('#slTrack'); if (!tr) return;
  const i = Math.round(tr.scrollLeft / Math.max(1, tr.clientWidth));
  document.querySelectorAll('#slDots i').forEach((d, k) => d.classList.toggle('on', k === i));
  document.querySelectorAll('#slTrack .slide').forEach((s, k) => {
    const v = s.querySelector('video'); if (!v) return;
    if (k === i && $('#slides').classList.contains('open')) { const st = +v.dataset.st; if (v.currentTime < st || v.currentTime > st + 4.2) v.currentTime = st; v.play().catch(() => {}); }
    else v.pause();
  });
}
document.addEventListener('timeupdate', e => { const v = e.target; if (v.dataset && v.dataset.st != null && v.currentTime > +v.dataset.st + 4) v.currentTime = +v.dataset.st; }, true);
