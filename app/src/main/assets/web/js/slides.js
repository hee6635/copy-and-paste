'use strict';
/* 요약 슬라이드: 장소 · 순서 · 요약 줄마다 한 장. 사진은 캡처만 (영상 구간 재생은 앱 화면에서 오류가 나서 뺌) */
function slideData(it) {
  const ai = it.ai || {};
  const capOf = (sec, i) => (it.captures || []).find(c => c.at && c.at.sec === sec && +c.at.i === i);
  if ((ai.places || []).length) return ai.places.map((p, i) => ({ t: +p.t, title: (i + 1) + '. ' + p.name, text: p.info || '', cap: capOf('places', i) }));
  if (ai.recipe && (ai.recipe.steps || []).length) return ai.recipe.steps.map((x, i) => { const c = capOf('steps', i); return { t: c ? c.t : -1, title: '순서 ' + (i + 1), text: x, cap: c }; });
  return (ai.summary || []).map((x, i) => { const c = capOf('summary', i); return { t: c ? c.t : -1, title: '', text: x, cap: c }; });
}
function canSlide(it) { return slideData(it).some(s => s.cap); }
function openSlides() {
  const it = byId(cur); if (!it) return;
  const sl = slideData(it);
  $('#slTitle').textContent = it.title || '';
  $('#slTrack').innerHTML = sl.map((s, i) => {
    const media = s.cap ? `<img src="${fileUrl(it.id, s.cap.file)}" alt="">` : '<div class="slnone"></div>';
    return `<div class="slide" data-i="${i}"><div class="slm">${media}${s.t >= 0 ? `<span class="tc">${fmtT(s.t)}</span>` : ''}</div>
      <div class="sltx">${s.title ? `<b>${esc(s.title)}</b>` : ''}<p>${rich(s.text)}</p><small>${i + 1} / ${sl.length}</small></div></div>`;
  }).join('');
  $('#slDots').innerHTML = sl.map((_, i) => `<i class="${i ? '' : 'on'}"></i>`).join('');
  openPage('slides');
  $('#slTrack').scrollLeft = 0;
  setTimeout(slideActive, 450);
}
/** 지금 장 점 표시 */
function slideActive() {
  const tr = $('#slTrack'); if (!tr) return;
  const i = Math.round(tr.scrollLeft / Math.max(1, tr.clientWidth));
  document.querySelectorAll('#slDots i').forEach((d, k) => d.classList.toggle('on', k === i));
}
