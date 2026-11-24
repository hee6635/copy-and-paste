'use strict';
/* 공유: 메모 하나 또는 여러 개를 글로 — AI 에게 일정 부탁 / 사람에게 정리본. 장소는 합치고(같은 이름은 하나로) DAY 별로 묶음 */
function shareSheet(ids) {
  const its = ids.map(byId).filter(Boolean);
  if (!its.length) return;
  optSheet(its.length > 1 ? its.length + '개 공유' : '공유', [
    ['AI 에게 일정 부탁', () => A.shareMsg(shareText(its, true))],
    ['정리본 보내기', () => A.shareMsg(shareText(its, false))]
  ]);
}
// 카톡 · 메모에서 읽기 쉽게 한글 그대로 (+ 는 띄어쓰기)
const mapUrl = (name, area) => 'https://maps.google.com/?q=' + [name, area].filter(Boolean).join(' ').replace(/[&#?%]/g, '').replace(/\s+/g, '+');
function shareText(its, forAI) {
  const L = [], seen = new Set(), areas = new Set(), days = new Set();
  let nPlace = 0;
  its.forEach(it => {
    const ai = it.ai || {};
    if (ai.area) areas.add(ai.area);
    (ai.places || []).forEach(p => { if (p.day) days.add(p.day); });
  });
  const area = [...areas].join(' · ');
  if (forAI) {
    const hasPlace = its.some(it => !(it.ai || {}).listName && ((it.ai || {}).places || []).length);
    L.push(hasPlace
      ? `아래는 내가 저장해 둔 ${area || '여행'} 정보야. 이 장소들로 일정을 짜 줘.${days.size > 1 ? ' 원래 글의 DAY 구분은 참고만 하고' : ''} 동선 · 영업시간 · 이동 시간을 고려해서 하루씩 시간 순서로 정리하고, 아래에 없는 정보는 지어내지 말고 "확인 필요"로 표시해 줘.`
      : '아래는 내가 저장해 둔 메모야. 이 내용으로 바로 따라 할 수 있게 단계별 실행 계획으로 정리해 줘. 아래에 없는 정보는 지어내지 말고 "확인 필요"로 표시해 줘.', '');
  }
  its.forEach(it => {
    const ai = it.ai || {};
    L.push('📌 ' + ttl(it) + (it.author ? ' (' + (PLAT[it.platform] || '') + ' ' + it.author + ')' : ''));
    const ps = (ai.places || []).filter(p => p && p.name && !seen.has(p.name.trim()));
    ps.forEach(p => seen.add(p.name.trim()));
    let day = '';
    ps.forEach(p => {
      if ((p.day || '') !== day) { day = p.day || ''; if (day) L.push('[' + day + ']'); }
      nPlace++;
      L.push(nPlace + '. ' + p.name + (p.info ? ' — ' + plain(p.info) : '') + (forAI || p.map === false || ai.listName ? '' : '\n   지도 ' + mapUrl(p.name, ai.area)));
    });
    (ai.summary || []).forEach((x, i) => { const lb = (ai.sumLabel || [])[i]; L.push('💡 ' + (lb ? lb + ': ' : '') + plain(x)); });
    (ai.sections || []).forEach(x => { L.push('■ ' + x.title); (x.items || []).forEach((r, i) => L.push((x.kind === 'steps' ? (i + 1) + '. ' : '  ') + plain(String(r)))); });
    (ai.links || []).forEach(l => L.push('🔗 ' + (l.label ? l.label + ': ' : '') + l.value));
    if (it.memo) L.push('📝 ' + it.memo);
    L.push('원본 ' + it.url, '');
  });
  return L.join('\n').trim();
}
