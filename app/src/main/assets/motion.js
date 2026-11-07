// ---- Motion for the home screen: talking view, agents' job cards, living tiles, the brain docking when a page opens ----
(function(){
const $ = id => document.getElementById(id);
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const ease = k => 1 - Math.pow(1 - k, 3);
const short = (s, n) => { s = String(s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const escH = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
const sheetOpen = () => typeof openId !== 'undefined' && !!openId;

// ================= the home screen comes in piece by piece =================
if (!reduce) document.querySelectorAll('header, #hero, #agbar, #brief, .tiles > *, .foot').forEach((el, i) =>
  el.animate([{opacity: 0, transform: 'translateY(16px)'}, {opacity: 1, transform: 'none'}], {duration: 650, delay: 60 + i * 60, easing: 'cubic-bezier(.2,.8,.2,1)', fill: 'backwards'}));

// numbers count up to their new value; text he writes types itself in
const num = s => { const m = String(s).replace(/,/g, '').match(/-?\d+(\.\d+)?/); return m ? parseFloat(m[0]) : null; };
function countUp(el, from, to, ms = 1200) {
  const txt = el.textContent, dec = (txt.match(/\d\.(\d+)/) || ['', ''])[1].length, first = txt.search(/\d/), lastD = txt.search(/\d[^\d]*$/), pre = txt.slice(0, first), post = txt.slice(lastD + 1);
  const t0 = performance.now(); el._counting = true;
  (function stepC(now) { const k = Math.min(1, (now - t0) / ms), v = from + (to - from) * ease(k);
    el.textContent = pre + v.toLocaleString('en-US', {minimumFractionDigits: dec, maximumFractionDigits: dec}) + post;
    if (k < 1) requestAnimationFrame(stepC); else { el.textContent = txt; el._counting = false; } })(t0);
}
function watchNumber(getEl, host) {
  let prev = null;
  const check = () => { const el = getEl(); if (!el || el._counting) return; const v = num(el.textContent); if (v === null) return;
    if (prev !== v && !reduce) countUp(el, prev === null ? 0 : prev, v, prev === null ? 1400 : 900); prev = v; };
  new MutationObserver(check).observe(host, {childList: true, subtree: true, characterData: true}); check();
}
watchNumber(() => $('t-earn'), $('t-earn'));
watchNumber(() => document.querySelector('#t-goal .big'), $('t-goal'));
// the goal bar fills from where it was
{ let w = '0%'; const fill = () => { const i = document.querySelector('#t-goal .bar > i'); if (!i || i._done) return; i._done = true; const to = i.style.width; i.style.width = w; requestAnimationFrame(() => requestAnimationFrame(() => { i.style.width = to; })); w = to; };
  new MutationObserver(fill).observe($('t-goal'), {childList: true}); fill(); }
// the week's bars grow up
{ let last = ''; const grow = () => { const h = $('t-mini').innerHTML; if (h === last) return; const first = !last; last = h; if (reduce) return;
    [...$('t-mini').children].forEach((b, i) => b.animate([{transform: 'scaleY(0)'}, {transform: 'none'}], {duration: first ? 700 : 400, delay: (first ? 500 : 0) + i * 60, easing: 'cubic-bezier(.2,.8,.2,1)', fill: 'backwards'})); };
  new MutationObserver(grow).observe($('t-mini'), {childList: true}); grow(); }
function typeIn(el, ms = 18) {
  if (el._typing || reduce) return; const html = el.innerHTML, text = el.textContent; if (!text.trim() || el._typed === text) return; el._typed = text; el._typing = true;
  let i = 0; const go = () => { i = Math.min(text.length, i + 2); el.textContent = text.slice(0, i); el.insertAdjacentHTML('beforeend', '<span class="caret"></span>');
    if (i < text.length) setTimeout(go, ms); else setTimeout(() => { el.innerHTML = html; el._typing = false; }, 500); };
  go();
}
['brief', 't-mind'].forEach(id => { const el = $(id); const obs = new MutationObserver(() => { if (!el._typing) typeIn(el); }); obs.observe(el, {childList: true, characterData: true, subtree: true}); setTimeout(() => typeIn(el), 500); });
// now and then a tile catches the light, like he just looked at it
setInterval(() => { if (document.hidden || sheetOpen() || OS || window.houseOpen || reduce) return; const ts = [...document.querySelectorAll('.tile')]; const el = ts[Math.floor(Math.random() * ts.length)];
  el.classList.remove('shine'); void el.offsetWidth; el.classList.add('shine'); }, 5200);
// the next feed counts down, to the second
setInterval(() => { const at = window.nextFeedAt || 0, el = $('t-baby'); if (!el || !at) return; const s = Math.round((at - Date.now()) / 1000); if (s <= 0) return;
  let sp = $('t-feedin'); if (!sp) { el.insertAdjacentHTML('beforeend', ' · <span id="t-feedin"></span>'); sp = $('t-feedin'); }
  sp.textContent = `in ${Math.floor(s / 3600)}h ${String(Math.floor(s / 60) % 60).padStart(2, '0')}m ${String(s % 60).padStart(2, '0')}s`; }, 1000);
// a shift running: the Today tile shows the clock, to the second
setInterval(() => { const si = window.shiftInfo, el = $('t-week'); if (!si || !el) return;
  const ms = si.on ? si.done + (Date.now() - si.at) : si.done; if (ms < 60000) { const old = $('t-shift'); if (old) old.remove(); return; }
  const h = Math.floor(ms / 3600000), m = Math.floor(ms / 60000) % 60, sec = Math.floor(ms / 1000) % 60;
  let sp = $('t-shift'); if (!sp) { el.insertAdjacentHTML('afterend', '<div class="sub" id="t-shift"></div>'); sp = $('t-shift'); }
  sp.textContent = (si.on ? 'On shift ' : 'Worked ') + h + 'h ' + String(m).padStart(2, '0') + 'm' + (si.on ? ' ' + String(sec).padStart(2, '0') + 's' : '') + ' today';
  sp.style.color = si.on ? 'var(--accent-soft)' : ''; }, 1000);
// ticking something off the list: the box fills, the line strikes through, then it moves
window.tickItem = (li, at) => { if (li.classList.contains('ticking')) return; li.classList.add('ticking'); setTimeout(() => J.listToggle('list', at), reduce ? 0 : 520); };

// ================= when a page opens, the brain flies up into the corner and keeps going =================
window.dockBrain = (on, id) => {
  const hero = $('hero'); if (!hero) return;
  if (on) {
    if (hero.classList.contains('docked')) return;
    hero.style.transform = ''; const r = hero.getBoundingClientRect(), s = 96 / r.height;
    const tx = (innerWidth - 50) - (r.left + r.width / 2), ty = 54 - (r.top + r.height / 2);
    hero.classList.add('docked'); hero.style.transform = `translate(${tx}px,${ty}px) scale(${s})`;
  } else { hero.style.transform = ''; hero.classList.remove('docked'); }
  if (on && id === 'money' && !reduce) setTimeout(() => {
    [...$('m-bars').children].forEach((b, i) => b.animate([{transform: 'scaleY(0)'}, {transform: 'none'}], {duration: 650, delay: i * 40, easing: 'cubic-bezier(.2,.8,.2,1)', fill: 'backwards'}));
    const s = $('m-saved'), v = num(s.textContent); if (v) countUp(s, 0, v, 1300); }, 250);
};

// ================= talking: the screen dims, the brain comes forward, the words arrive as they're said =================
const tv = $('talkv'), you = $('tv-you'), him = $('tv-him'), wave = $('tv-wave'), wg = wave.getContext('2d');
let on = false, offAt = 0, turnAt = -1, stale = null, words = [], shown = 0, nextAt = 0, lit = null;
function show(v) {
  if (on === v) return; on = v; document.body.classList.toggle('talking', v); tv.setAttribute('aria-hidden', String(!v));
  if (v) { if (scrollY > 0) scrollTo({top: 0, behavior: reduce ? 'auto' : 'smooth'}); place(); }
}
function place() { const r = $('hero').getBoundingClientRect(); $('tv-words').style.top = Math.max(120, r.bottom - 14) + 'px'; }
function setYou(text) {
  const ws = text.split(/\s+/).filter(Boolean), cur = [...you.children].map(e => e.textContent);
  const same = cur.length <= ws.length && cur.every((w, i) => w === ws[i]);
  if (!same) { you.innerHTML = ''; }
  for (let i = same ? cur.length : 0; i < ws.length; i++) { const s = document.createElement('span'); s.className = 'w'; s.textContent = ws[i]; you.appendChild(s); requestAnimationFrame(() => s.classList.add('on')); }
}
setInterval(() => {
  let v; try { v = JSON.parse(J.words()); } catch (_) { return; }
  const want = OS !== 0 && !sheetOpen() && !window.houseOpen && !document.hidden;
  if (want) { offAt = 0; if (!on) { stale = v.heard; show(true); you.innerHTML = ''; him.innerHTML = ''; words = []; shown = 0; turnAt = v.at; } }
  else if (on) { if (!offAt) offAt = Date.now(); if (shown < words.length) { while (shown < words.length) him.children[shown++].classList.add('on'); }
    if (lit) { lit.classList.remove('lit'); lit = null; } if (Date.now() - offAt > 2200) show(false); }
  if (!on) return;
  place();
  if (v.at !== turnAt) { turnAt = v.at; stale = null; him.innerHTML = ''; words = []; shown = 0; }
  const heard = v.heard && v.heard !== stale ? v.heard : '';
  if (heard) setYou('“' + heard + '”'); else if (!you.children.length) you.innerHTML = '<span class="faint">Listening…</span>';
  const ws = (v.said || '').split(/\s+/).filter(Boolean);
  if (ws.length > words.length && ws.slice(0, words.length).join(' ') === words.join(' ')) {
    for (let i = words.length; i < ws.length; i++) { const s = document.createElement('span'); s.className = 'w'; s.textContent = ws[i]; him.appendChild(s); } words = ws;
  } else if (ws.length && ws.join(' ') !== words.join(' ')) { him.innerHTML = ''; words = ws; shown = 0; ws.forEach(w => { const s = document.createElement('span'); s.className = 'w'; s.textContent = w; him.appendChild(s); }); }
  // reveal at about the pace he speaks; when he's not speaking yet, wait
  if (OS === 3 && shown < words.length && Date.now() >= nextAt) {
    const el = him.children[shown++]; if (lit) lit.classList.remove('lit'); el.classList.add('on', 'lit'); lit = el;
    nextAt = Date.now() + 150 + el.textContent.length * 24 + (/[.,!?]$/.test(el.textContent) ? 220 : 0);
    if (him.scrollHeight > him.clientHeight) him.scrollTop = him.scrollHeight;
  }
}, 90);
(function wv(ts) { requestAnimationFrame(wv); if (!on) return; const t = ts / 1000, W = wave.width, H = wave.height; wg.clearRect(0, 0, W, H);
  const amp = OS === 2 ? 3 + 2 * Math.sin(t * 3) : 4 + 30 * (OL > 0 ? OL : 0);
  for (let L = 0; L < 3; L++) { wg.beginPath(); for (let x = 0; x <= W; x += 4) { const env = Math.sin(Math.PI * x / W); const y = H / 2 + Math.sin(x * (0.025 + L * 0.01) + t * (4 + L * 1.7)) * amp * env * (1 - L * 0.28); x ? wg.lineTo(x, y) : wg.moveTo(x, y); }
    wg.strokeStyle = L ? `rgba(201,102,92,${0.5 - L * 0.15})` : 'rgba(255,226,217,.9)'; wg.lineWidth = L ? 1.5 : 2.2; wg.stroke(); } })(0);
$('tv-stop').onclick = () => { try { J.hush(); } catch (_) {} };

// ================= an agent gets a job: its card flies out of the brain, fills in, and flies back to Jarvis =================
const jobs = {};
const STEP = s => { s = String(s).replace(/_/g, ' '); return s.charAt(0).toUpperCase() + s.slice(1); };
function flyFrom(el, id, back) {
  const at = window.agentAt ? window.agentAt(id) : [innerWidth / 2, 200], r = el.getBoundingClientRect(), dx = at[0] - (r.left + r.width / 2), dy = at[1] - (r.top + r.height / 2);
  const a = [{transform: `translate(${dx}px,${dy}px) scale(.06)`, opacity: 0, borderRadius: '50%'}, {transform: 'none', opacity: 1, borderRadius: '18px'}];
  return el.animate(back ? a.slice().reverse() : a, {duration: back ? 520 : 650, easing: back ? 'cubic-bezier(.6,0,.8,.4)' : 'cubic-bezier(.2,.9,.25,1.1)', fill: 'forwards'}).finished;
}
function jobOpen(a) {
  const el = document.createElement('div'); el.className = 'job';
  el.innerHTML = `<div class="jk"><i></i><span>${escH(a.name.toUpperCase())} IS ON IT</span></div><div class="jt">${escH(short(a.task, 120))}</div><ul></ul><div class="shim"></div>`;
  $('jobs').appendChild(el); const j = jobs[a.id] = {el, n: 0, closing: false};
  if (!reduce) flyFrom(el, a.id, false);
  return j;
}
function jobSteps(j, steps) {
  const ul = j.el.querySelector('ul');
  for (let i = j.n; i < steps.length && i < 5; i++) { const prev = ul.lastElementChild; if (prev) prev.classList.add('ok');
    const li = document.createElement('li'); li.innerHTML = `<b></b>${escH(STEP(steps[i]))}`; ul.appendChild(li); requestAnimationFrame(() => li.classList.add('on')); }
  j.n = Math.max(j.n, Math.min(5, steps.length));
}
async function jobClose(j, a) {
  j.closing = true; const el = j.el; el.querySelectorAll('li').forEach(li => li.classList.add('ok'));
  const run = a.runs && a.runs.length ? a.runs[a.runs.length - 1] : null;
  el.querySelector('.jk').innerHTML = `<i class="ok"></i><span>${escH(a.name.toUpperCase())} · DONE</span>`;
  if (run && run.result) el.querySelector('.jt').textContent = short(run.result, 140);
  const sh = el.querySelector('.shim'); if (sh) sh.remove();
  await sleep(1800);
  if (!reduce) await flyFrom(el, 'jarvis', true);
  el.remove(); delete jobs[a.id];
  try { window.brainPulse && window.brainPulse(a.id, 2); } catch (_) {}
}
function onJobs(A) {
  const quiet = document.hidden || window.houseOpen || sheetOpen();
  for (let i = 0; i < A.length; i++) { const a = A[i]; if (a.id === 'jarvis') continue; const j = jobs[a.id];
    if (a.active && a.task) { if (!j && !quiet) jobSteps(jobOpen(a), a.steps || []); else if (j && !j.closing) { j.el.querySelector('.jt').textContent = short(a.task, 120); jobSteps(j, a.steps || []); } }
    else if (j && !j.closing) jobClose(j, a);
  }
}
window.onAgents = A => { try { onJobs(A); } catch (e) { try { J.jsError('jobs: ' + e.message); } catch (_) {} } try { window.houseAgents && window.houseAgents(A); } catch (e) { try { J.jsError('house: ' + e.message); } catch (_) {} } };
})();
