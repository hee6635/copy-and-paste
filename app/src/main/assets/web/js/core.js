'use strict';
/* 공통: 브리지(A) · 상태 · 작은 도구 · 화면 열고 닫기 · 토스트 · 작은 창 */
const $ = (s, r = document) => r.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const A = window.Android || devBridge();

let items = [];
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
  if (top.type === 'sheet') closeSheet(top.id); else if (top.type === 'mode') endSelect(); else closePage(top.id);
  return true;
}

let toastT = 0;
function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.classList.add('on');
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2000);
}

// ── 작은 창 ──
let askCb = null;
function ask(title, label, cb, val) {
  askCb = cb; $('#askTitle').textContent = title; $('#askLabel').textContent = label; $('#askInput').value = val || '';
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
