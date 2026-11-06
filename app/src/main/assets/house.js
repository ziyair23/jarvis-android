// ---- His house: a cut-away house inside Jarvis's mind, a room for every agent ----
// They keep their own hours (sleep, get up, sit at their desks, look things up). When one gets a real job it wakes,
// goes to its computer and shows the actual task; when the job is done it walks the result down to Jarvis.
(function(){
const $ = id => document.getElementById(id);
const FLOORS = [380, 680, 980, 1280, 1580, 1880];   // floor line of each storey (0 is the attic)
const LAST = FLOORS.length - 1, FH = 300, X0 = 60, RW = 380, STX = X0 + RW * 3, SW = 140, S = 1.6;
const AGS = [
  {id:'builder', room:'Workshop', f:0, c:0, wide:true, prop:'bench', wall:'#2a211c', blanket:'#4a3b2e', awake:[[13,22],[1,5]],
    look:{skin:'#c48c66', hair:'#2a1c14', style:'cap', top:'#6e4428', pants:'#34312e'}, acts:['Running his night tests', 'Looking over his own code', 'Sketching ideas for upgrades']},
  {id:'research', room:'Library', f:1, c:0, prop:'shelf', wall:'#2d2421', blanket:'#5b4636', awake:[[7,24]],
    look:{skin:'#e0b896', hair:'#5a3a22', style:'long', top:'#5a4636', pants:'#2b2b2e', glasses:true}, acts:['Reading', 'Browsing the shelves', 'Taking notes']},
  {id:'planner', room:'Study', f:1, c:1, prop:'calendar', wall:'#2b2427', blanket:'#5e3a3e', awake:[[5.5,22]],
    look:{skin:'#d9a982', hair:'#5b2a1e', style:'bun', top:'#6b4a4a', pants:'#26262b'}, acts:['Going over the week', 'Tidying the calendar', 'Checking what\'s next']},
  {id:'phone', room:'Signal room', f:1, c:2, prop:'screens', wall:'#202328', blanket:'#2f3a4f', awake:[[6,24],[0,1]],
    look:{skin:'#8a5a3c', hair:'#140f0d', style:'curly', top:'#2f3a4f', pants:'#1f1f24'}, acts:['Watching the line', 'Keeping the screens warm', 'Waiting on your texts']},
  {id:'money', room:'Vault', f:2, c:0, prop:'safe', wall:'#22261f', blanket:'#33402f', awake:[[7,23]],
    look:{skin:'#c99a7a', hair:'#1c1410', style:'short', top:'#3d4a3f', pants:'#2a2a30'}, acts:['Going over your numbers', 'Balancing the ledger', 'Checking the vault']},
  {id:'bills', room:'Office', f:2, c:2, prop:'paper', wall:'#27241f', blanket:'#4a4436', awake:[[8,20]],
    look:{skin:'#d2a07c', hair:'#8a8580', style:'bald', top:'#3d3f4a', pants:'#26262b'}, acts:['Sorting the paperwork', 'Filing receipts', 'Checking due dates']},
  {id:'meals', room:'Kitchen', f:3, c:0, prop:'kitchen', wall:'#2e2722', blanket:'#6a5a48', awake:[[7,21]],
    look:{skin:'#e6c3a3', hair:'#b89a62', style:'short', top:'#d4cbbd', pants:'#2e2c2a'}, acts:['Flipping through recipes', 'Checking the fridge', 'Tidying the kitchen']},
  {id:'family', room:'Living room', f:3, c:1, prop:'frames', wall:'#2e2321', blanket:'#7a3b3b', awake:[[8,22]],
    look:{skin:'#9c6a4a', hair:'#120d0b', style:'long', top:'#7a3b3b', pants:'#28232a'}, acts:['Looking through photos', 'Straightening the frames', 'Remembering the little things']},
  {id:'memory', room:'Archive', f:3, c:2, prop:'files', wall:'#25221f', blanket:'#4a4440', awake:[[9,19],[2,4]],
    look:{skin:'#b27b5a', hair:'#d8d2c8', style:'long', top:'#4a4440', pants:'#2a2724'}, acts:['Filing what he\'s learned', 'Tidying old notes', 'Cross-checking the files']},
  {id:'coach', room:'Gym', f:4, c:0, prop:'gym', wall:'#1f2224', blanket:'#3a3f44', awake:[[5,21]],
    look:{skin:'#7a4e34', hair:'#120d0b', style:'short', top:'#8a2c24', pants:'#1c1c20'}, acts:['Going over his lessons', 'Warming up', 'Studying how he did today']},
  {id:'pursuit', room:'Studio', f:4, c:1, prop:'studio', wall:'#231f22', blanket:'#2a2a2e', awake:[[10,24],[0,2]],
    look:{skin:'#c99070', hair:'#0e0b0a', style:'curly', top:'#18181b', pants:'#18181b'}, acts:['Sketching new pieces', 'Sorting the rack', 'Looking at the mood board']},
  {id:'appts', room:'Front desk', f:4, c:2, prop:'front', wall:'#26242a', blanket:'#4a4456', awake:[[7,19]],
    look:{skin:'#e2b48e', hair:'#3a2418', style:'bun', top:'#4a4456', pants:'#26262b'}, acts:['Checking the appointment book', 'Straightening the desk', 'Watching the clock']},
  {id:'packages', room:'Mailroom', f:5, c:0, prop:'boxes', wall:'#2a241e', blanket:'#5a4a36', awake:[[6,20]],
    look:{skin:'#b8845e', hair:'#2a1c14', style:'cap', top:'#6a5236', pants:'#2e2c2a'}, acts:['Sorting the shelves', 'Checking tracking numbers', 'Stacking boxes']},
  {id:'supplies', room:'Pantry', f:5, c:1, prop:'pantry', wall:'#28241f', blanket:'#4e5a48', awake:[[7,21]],
    look:{skin:'#d8a882', hair:'#6a4026', style:'short', top:'#4e5a48', pants:'#2a2a2e'}, acts:['Counting what\'s left', 'Restocking the shelves', 'Checking the diapers']},
];
const NAME = {builder:'Builder', research:'Research', planner:'Planner', phone:'Phone', money:'Money', bills:'Bills', meals:'Meals', family:'Family', memory:'Memory', coach:'Coach', pursuit:'Pursuit', appts:'Appointments', packages:'Packages', supplies:'Supplies'};
AGS.forEach(a => a.name = NAME[a.id]);
const roomOf = a => a.wide ? {x: X0, y: FLOORS[0] - 230, w: RW * 3, h: 230} : {x: X0 + a.c * RW, y: FLOORS[a.f] - FH, w: RW, h: FH};
const jRoom = {x: X0 + RW, y: FLOORS[2] - FH, w: RW, h: FH}, gRoom = {x: X0 + RW * 2, y: FLOORS[5] - FH, w: RW, h: FH};
const spot = (a, what) => { const r = roomOf(a); return what === 'bed' ? r.x + 96 : what === 'desk' ? r.x + 245 : a.wide ? r.x + 560 : r.x + 196; };
const realNow = () => { const d = new Date(); return d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60; };
let simMin = realNow(), fast = false, focusI = 4, zoomed = false, open = false, coreFlash = 0, REAL = {};
const hourNow = () => (simMin / 60) % 24;
const scheduleAwake = (a, h = hourNow()) => a.awake.some(([s, e]) => h >= s && h < e);
const isOff = a => REAL[a.id] && REAL[a.id].on === false;
const awakeNow = a => !isOff(a) && (!!a.job || scheduleAwake(a));
const asleep = a => a.state === 'sleep' || (a.state === 'lieDown' && a.until < .8);
const clockTxt = () => { const m = Math.floor(simMin) % 1440, h = Math.floor(m / 60), mm = m % 60; return `${(h % 12) || 12}:${String(mm).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };
const fmtH = h => `${(Math.floor(h) % 12) || 12}${h % 1 ? ':30' : ''} ${h % 24 < 12 ? 'AM' : 'PM'}`;
function wakesAt(a) { const h = hourNow(); let best = 99, at = 0; a.awake.forEach(([s]) => { const d = (s - h + 24) % 24; if (d < best) { best = d; at = s; } }); return fmtH(at); }
function lastRun(id) { const r = REAL[id] && REAL[id].runs; if (!r || !r.length) return null; return r[r.length - 1]; }
AGS.forEach((a, i) => { a.ph = i * 1.7; a.act = i % 3; a.path = []; a.dir = 1; a.walkP = 0; a.orb = 0; a.job = null;
  if (scheduleAwake(a)) { a.state = 'work'; a.x = spot(a, 'desk'); a.until = 2 + Math.random() * 8; } else { a.state = 'sleep'; a.x = spot(a, 'bed'); }
  a.fl = a.f; a.y = FLOORS[a.f]; });
const STATE_TXT = {sleep: 'ASLEEP', getup: 'GETTING UP', toDesk: 'ON THE MOVE', work: 'AT THE DESK', toProp: 'ON THE MOVE', prop: 'LOOKING SOMETHING UP', deliver: 'ON ITS WAY TO JARVIS', give: 'WITH JARVIS', toBed: 'HEADING TO BED', lieDown: 'GOING TO SLEEP'};
const short = (s, n) => { s = String(s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
function showFocus() {
  const a = AGS[focusI], lr = lastRun(a.id); if (!$('f-name')) return;
  $('f-name').textContent = a.name + ' · ' + a.room;
  $('f-state').textContent = isOff(a) ? 'SWITCHED OFF' : a.job ? 'ON YOUR JOB NOW' : STATE_TXT[a.state] || 'AWAKE';
  $('f-act').textContent = isOff(a) ? 'Switched off in his settings.' : a.job ? short(a.job, 110) : asleep(a) ? `Asleep. Wakes around ${wakesAt(a)}, or sooner if you need it.` :
    a.state === 'deliver' || a.state === 'give' ? 'Bringing Jarvis what it found' : a.state === 'toBed' || a.state === 'lieDown' ? 'Done for the day' : a.state === 'getup' ? 'Just woke up' : a.acts[a.act] + '…';
  $('f-done').textContent = lr ? 'Last job: ' + short(lr.result || lr.task, 120) : 'No jobs yet. It learns as it works.';
  const f = document.querySelector('#house .focus'); if (f) { f.classList.toggle('asleep', asleep(a) || isOff(a)); f.classList.toggle('live', !!a.job); }
}
// a path through the house: along the floor, through the doorways, up or down the stairwell
function route(a, f2, x2) { const pts = []; let f = a.fl;
  while (f !== f2) { const down = f2 > f, up = down ? f : f - 1, ux = up % 2 === 0 ? STX + 22 : STX + SW - 22, lx = up % 2 === 0 ? STX + SW - 22 : STX + 22;
    pts.push({x: down ? ux : lx, f, m: 'walk'}); const nf = down ? f + 1 : f - 1; pts.push({x: down ? lx : ux, f: nf, m: 'climb'}); f = nf; }
  pts.push({x: x2, f: f2, m: 'walk'}); return pts; }
function go(a, state, f2, x2) { a.state = state; a.path = route(a, f2, x2); a.seg = null; if (AGS[focusI] === a) showFocus(); }
const deliverX = a => { const jx = X0 + RW + 190; return a.f === 2 && a.c === 0 ? jx - 62 : jx + 62; };
function step(a, dt) {
  const awake = awakeNow(a);
  if (a.path.length) {
    const p = a.path[0];
    if (!a.seg) a.seg = {x: a.x, y: a.y, tx: p.x, ty: FLOORS[p.f], d: 0, len: Math.hypot(p.x - a.x, FLOORS[p.f] - a.y) || 1};
    const sp = (p.m === 'climb' ? 62 : 78) * (a.job ? 1.5 : 1); a.seg.d = Math.min(a.seg.len, a.seg.d + sp * dt); const k = a.seg.d / a.seg.len;
    a.dir = p.x >= a.seg.x ? 1 : -1; a.x = a.seg.x + (a.seg.tx - a.seg.x) * k; a.y = a.seg.y + (a.seg.ty - a.seg.y) * k; a.walkP += sp * dt * 0.075;
    if (k >= 1) { a.fl = p.f; a.path.shift(); a.seg = null; if (!a.path.length) arrive(a); }
    return;
  }
  a.until -= dt;
  if (a.state === 'sleep') { if (awake) { a.state = 'getup'; a.until = a.job ? 1 : 2.4; if (AGS[focusI] === a) showFocus(); } return; }
  if (a.state === 'getup' && a.until <= 0) return go(a, 'toDesk', a.f, spot(a, 'desk'));
  if (a.state === 'lieDown' && a.until <= 0) { a.state = 'sleep'; if (AGS[focusI] === a) showFocus(); return; }
  if (a.state === 'give') { a.orb = Math.max(0, 1 - (1.4 - a.until) / 0.7); if (a.until <= 0) { a.orb = 0; go(a, 'toDesk', a.f, spot(a, 'desk')); } return; }
  if (a.job) { if (a.state !== 'work' && a.state !== 'toDesk') go(a, 'toDesk', a.f, spot(a, 'desk')); return; }   // on a real job: stays at the desk
  if (a.deliverNext && (a.state === 'work' || a.state === 'prop')) { a.deliverNext = false; a.orb = 1; return go(a, 'deliver', 2, deliverX(a)); }
  if ((a.state === 'work' || a.state === 'prop') && a.until <= 0) {
    if (!awake) return go(a, 'toBed', a.f, spot(a, 'bed'));
    if (a.state === 'work' && Math.random() < .4) go(a, 'toProp', a.f, spot(a, 'prop'));
    else if (a.state === 'prop') go(a, 'toDesk', a.f, spot(a, 'desk'));
    else { a.act = (a.act + 1) % a.acts.length; a.until = 6 + Math.random() * 7; if (AGS[focusI] === a) showFocus(); }
  }
}
function arrive(a) {
  if (a.state === 'toDesk') { a.state = 'work'; a.dir = 1; a.until = 6 + Math.random() * 8; }
  else if (a.state === 'toProp') { a.state = 'prop'; a.dir = 1; a.until = 3.5 + Math.random() * 2.5; }
  else if (a.state === 'deliver') { a.state = 'give'; a.dir = a.x < X0 + RW + 190 ? 1 : -1; a.until = 1.4; setTimeout(() => { coreFlash = 1; try { window.brainPulse && window.brainPulse(a.id, 3); } catch (_) {} }, 700); }
  else if (a.state === 'toBed') { a.state = 'lieDown'; a.until = 1.6; }
  if (AGS[focusI] === a) showFocus();
}
// the real agents: a job wakes them and sends them to their desk; a finished job gets walked down to Jarvis
window.houseAgents = list => {
  for (let i = 0; i < list.length; i++) { const r = list[i]; REAL[r.id] = r; }
  AGS.forEach(a => { const r = REAL[a.id]; if (!r) return;
    const working = r.active && r.task;
    if (working && !a.job) { a.job = r.task; if (a.state === 'sleep' || a.state === 'lieDown') { a.state = 'getup'; a.until = .8; } }
    else if (working) a.job = r.task;
    else if (!working && a.job) { a.job = null; a.deliverNext = true; a.until = 0; }
  });
  showFocus();
};

  const cv = $('room'), g = cv.getContext('2d'); let W = 0, H = 0, dpr = 1;
  function size() { const r = cv.getBoundingClientRect(); dpr = Math.min(2, devicePixelRatio || 1); W = r.width; H = r.height; cv.width = W * dpr; cv.height = H * dpr; }
  new ResizeObserver(size).observe(cv); size();
  let seed = 5; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const STARS = Array.from({length: 120}, () => [rnd(), rnd() * .6, rnd()]);
  const CITY = Array.from({length: 46}, (_, i) => ({x: i * 34 - 60, w: 22 + rnd() * 30, h: 40 + rnd() * 150, lit: Array.from({length: 8}, () => rnd() > .6)}));
  const BOOKS = Array.from({length: 60}, () => [4 + rnd() * 5, 18 + rnd() * 14, ['#6b3a32', '#3d4a52', '#5a4a2e', '#4a3a4e', '#7a6a50', '#2f3f34'][Math.floor(rnd() * 6)]]);
  const CORE = Array.from({length: 260}, () => { const u = rnd() * 6.283, v = Math.acos(2 * rnd() - 1); return [Math.sin(v) * Math.cos(u), Math.cos(v) * .8, Math.sin(v) * Math.sin(u)]; });
  const cam = {x: 700, y: 1100, z: .25}, camT = {x: 700, y: 1100, z: .25};
  let pan = null, T = 0, last = 0;
  // ---------- the sky, by the hour ----------
  const SKY = [[0, '#04050b', '#0c0e1a'], [5, '#06070f', '#141224'], [6.5, '#1b1626', '#5a2c22'], [8, '#2c2f3c', '#7a5a4c'], [16, '#2e3240', '#86665a'], [18.5, '#251824', '#7a3a2a'], [20, '#0a0a14', '#1d1420'], [24, '#04050b', '#0c0e1a']];
  const mix = (a, b, k) => { const pa = [1, 3, 5].map(i => parseInt(a.slice(i, i + 2), 16)), pb = [1, 3, 5].map(i => parseInt(b.slice(i, i + 2), 16)); return `rgb(${pa.map((v, i) => Math.round(v + (pb[i] - v) * k)).join(',')})`; };
  function sky(h) { for (let i = 0; i < SKY.length - 1; i++) if (h >= SKY[i][0] && h < SKY[i + 1][0]) { const k = (h - SKY[i][0]) / (SKY[i + 1][0] - SKY[i][0]); return [mix(SKY[i][1], SKY[i + 1][1], k), mix(SKY[i][2], SKY[i + 1][2], k)]; } return [SKY[0][1], SKY[0][2]]; }
  const daylight = h => h < 6 ? 0 : h < 8 ? (h - 6) / 2 : h < 18 ? 1 : h < 20 ? 1 - (h - 18) / 2 : 0;
  function rr(x, y, w, h, r) { g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r); g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath(); }
  function glow(x, y, r, col, a) { const gr = g.createRadialGradient(x, y, 0, x, y, r); gr.addColorStop(0, col.replace('A', a)); gr.addColorStop(1, col.replace('A', 0)); g.fillStyle = gr; g.beginPath(); g.arc(x, y, r, 0, 7); g.fill(); }
  // ---------- a person, seen from the side ----------
  function pose(a) {
    const st = a.state, t = T, p = a.walkP;
    const moving = a.path.length > 0;
    if (st === 'sleep' || st === 'lieDown' && a.until < 0.8) return {lie: true};
    if (st === 'getup' || st === 'lieDown') { const hip = [0, -31]; return {hip, sh: [6, -57], head: [10, -68], legs: [[[17, -32], [20, -2]], [[15, -32], [18, -2]]], arms: [[[10, -45], [16, -33]], [[8, -45], [14, -33]]]}; }
    if (!moving && st === 'work') { const sh = [3, -48]; return {hip: [0, -22], sh, head: [6, -60], legs: [[[19, -23], [21, -3]], [[17, -23], [19, -3]]],
      arms: [0, 1].map(i => { const e = [sh[0] + 6, sh[1] + 11]; return [e, [e[0] + 15, sh[1] + 8 + Math.sin(t * 14 + i * 2 + a.ph) * 1.1]]; })}; }
    if (!moving && st === 'prop') { const hip = [0, -37], sh = [2, -64], al = 2.3 + .3 * Math.sin(t * 1.6 + a.ph);
      const e = [sh[0] + Math.sin(al) * 14, sh[1] + Math.cos(al) * 14]; return {hip, sh, head: [5, -76], legs: [[[1, -18], [2, 0]], [[-2, -18], [-3, 0]]],
      arms: [[e, [e[0] + Math.sin(al + .25) * 13, e[1] + Math.cos(al + .25) * 13]], [[sh[0] + 2, sh[1] + 14], [sh[0] + 6, sh[1] + 26]]]}; }
    if (!moving && st === 'give') { const hip = [0, -37], sh = [3, -64]; return {hip, sh, head: [6, -76], legs: [[[1, -18], [2, 0]], [[-2, -18], [-3, 0]]], arms: [[[sh[0] + 8, sh[1] + 10], [sh[0] + 21, sh[1] + 4]], [[sh[0] + 7, sh[1] + 11], [sh[0] + 19, sh[1] + 6]]], carry: true}; }
    if (!moving) return {hip: [0, -37], sh: [2, -64], head: [5, -76], legs: [[[1, -18], [2, 0]], [[-2, -18], [-3, 0]]], arms: [[[3, -50], [5, -38]], [[1, -50], [2, -38]]]};
    const hip = [0, -37 - Math.abs(Math.sin(p)) * 1.6], sh = [hip[0] + 3, hip[1] - 27], head = [sh[0] + 3, sh[1] - 12];
    const legs = [1, -1].map(s => { const th = s * Math.sin(p) * .45, bend = Math.max(0, s * Math.cos(p)) * .75; const kn = [hip[0] + Math.sin(th) * 19, hip[1] + Math.cos(th) * 19], ph = th - bend; return [kn, [kn[0] + Math.sin(ph) * 19, kn[1] + Math.cos(ph) * 19]]; });
    const carry = st === 'deliver';
    const arms = [-1, 1].map(s => { if (carry) return [[sh[0] + 7, sh[1] + 11], [sh[0] + 19, sh[1] + 7]]; const al = s * Math.sin(p) * .5, e = [sh[0] + Math.sin(al) * 14, sh[1] + Math.cos(al) * 14], b = al + .35; return [e, [e[0] + Math.sin(b) * 13, e[1] + Math.cos(b) * 13]]; });
    return {hip, sh, head, legs, arms, carry};
  }
  function hair(L, hd, r) {
    g.fillStyle = L.hair;
    if (L.style === 'cap') { g.beginPath(); g.arc(hd[0], hd[1] - 1, r * 1.05, Math.PI, 0); g.fill(); g.fillRect(hd[0], hd[1] - 2, r * 1.6, 2.2); g.fillStyle = '#5a3a24'; g.beginPath(); g.arc(hd[0], hd[1] - 1.5, r * 1.07, Math.PI * 1.05, Math.PI * 1.95); g.fill(); g.fillRect(hd[0] + r * .3, hd[1] - 2.2, r * 1.4, 2); return; }
    if (L.style === 'bald') { g.beginPath(); g.arc(hd[0] - 1, hd[1] + 1, r * 1.02, Math.PI * .75, Math.PI * 1.25); g.lineTo(hd[0] - 1, hd[1]); g.fill(); return; }
    g.beginPath(); g.arc(hd[0], hd[1], r * 1.08, Math.PI * .95, Math.PI * 2.05); g.lineTo(hd[0] + r * .5, hd[1] - r * .35); g.lineTo(hd[0] - r * .4, hd[1] - r * .2); g.lineTo(hd[0] - r * .7, hd[1] + r * .5); g.closePath(); g.fill();
    if (L.style === 'long') { g.beginPath(); g.moveTo(hd[0] - r * 1.05, hd[1] - r * .1); g.quadraticCurveTo(hd[0] - r * 1.4, hd[1] + r * 1.4, hd[0] - r * .8, hd[1] + r * 2.2); g.lineTo(hd[0] - r * .1, hd[1] + r * 1.2); g.lineTo(hd[0] - r * .3, hd[1]); g.fill(); }
    if (L.style === 'bun') { g.beginPath(); g.arc(hd[0] - r * .9, hd[1] - r * .8, r * .5, 0, 7); g.fill(); }
    if (L.style === 'curly') for (let k = 0; k < 6; k++) { g.beginPath(); g.arc(hd[0] - r * .8 + k * r * .32, hd[1] - r * .75 - Math.sin(k) * r * .15, r * .38, 0, 7); g.fill(); }
  }
  function person(a, x, y) {
    const L = a.look, P = pose(a);
    g.save(); g.translate(x, y);
    if (P.lie) { g.restore(); return; }
    g.scale(a.dir * S, S); g.lineCap = 'round'; g.lineJoin = 'round';
    g.fillStyle = 'rgba(0,0,0,.35)'; g.beginPath(); g.ellipse(4, 0, 14, 2.2, 0, 0, 7); g.fill();
    const limb = (pts, w, c) => { g.strokeStyle = c; g.lineWidth = w; g.beginPath(); g.moveTo(pts[0][0], pts[0][1]); for (let i = 1; i < pts.length; i++) g.lineTo(pts[i][0], pts[i][1]); g.stroke(); };
    const shade = c => mixHex(c, '#000000', .35);
    // back leg and arm (a touch darker), body, then front leg and arm, then the head
    limb([P.hip, P.legs[1][0], P.legs[1][1]], 7, shade(L.pants)); g.fillStyle = '#151111'; g.beginPath(); g.ellipse(P.legs[1][1][0] + 2.5, P.legs[1][1][1] - 1, 4.4, 2, 0, 0, 7); g.fill();
    limb([P.sh, P.arms[1][0], P.arms[1][1]], 5, shade(L.top));
    limb([P.hip, P.sh], 13, L.top);
    if (a.prop === 'kitchen') { g.strokeStyle = '#eee6da'; g.lineWidth = 9; g.beginPath(); g.moveTo(P.hip[0] + 4, P.hip[1] + 4); g.lineTo(P.sh[0] + 4, P.sh[1] + 10); g.stroke(); }
    limb([P.hip, P.legs[0][0], P.legs[0][1]], 7.5, L.pants); g.fillStyle = '#1b1515'; g.beginPath(); g.ellipse(P.legs[0][1][0] + 2.5, P.legs[0][1][1] - 1, 4.6, 2.1, 0, 0, 7); g.fill();
    limb([[P.sh[0], P.sh[1] - 1], [P.head[0] - 1, P.head[1] + 4]], 4.5, L.skin);
    limb([P.sh, P.arms[0][0], P.arms[0][1]], 5.5, L.top);
    g.fillStyle = L.skin; g.beginPath(); g.arc(P.arms[0][1][0], P.arms[0][1][1], 2.3, 0, 7); g.fill();
    const hd = P.head, r = 7.2;
    g.fillStyle = L.skin; g.beginPath(); g.ellipse(hd[0], hd[1], r * .95, r, 0, 0, 7); g.fill();
    g.beginPath(); g.moveTo(hd[0] + r * .85, hd[1] - 1); g.lineTo(hd[0] + r * 1.15, hd[1] + 1.5); g.lineTo(hd[0] + r * .8, hd[1] + 2.2); g.fill();
    hair(L, hd, r);
    g.fillStyle = '#1a1010'; g.beginPath(); g.arc(hd[0] + r * .55, hd[1] - .8, .95, 0, 7); g.fill();
    if (L.glasses) { g.strokeStyle = 'rgba(30,20,18,.9)'; g.lineWidth = .8; g.strokeRect(hd[0] + r * .3, hd[1] - 2.6, 4.2, 3); g.beginPath(); g.moveTo(hd[0] + r * .3, hd[1] - 1.5); g.lineTo(hd[0] - r * .4, hd[1] - 1.8); g.stroke(); }
    g.restore();
  }
  function mixHex(a, b, k) { return mix(a, b, k); }
  function sleeper(a, r) {   // in bed: head on the pillow, the blanket rising and falling
    const bx = r.x + 18, fy = FLOORS[a.f], top = fy - 50, br = Math.sin(T * 1.4 + a.ph) * 1.6;
    const hx = bx + 30, hy = top - 12, L = a.look;
    g.save(); g.fillStyle = L.skin; g.beginPath(); g.ellipse(hx, hy, 11, 11.5, 0, 0, 7); g.fill();
    g.save(); g.translate(hx, hy); g.rotate(-Math.PI / 2); g.scale(1.6, 1.6); hair(L, [0, 0], 7.2); g.restore();
    g.fillStyle = a.blanket; g.beginPath(); g.moveTo(bx + 40, top + 2); g.quadraticCurveTo(bx + 46, top - 20 - br, bx + 80, top - 18 - br); g.quadraticCurveTo(bx + 120, top - 16 - br * .6, bx + 132, top - 8); g.lineTo(bx + 134, top + 14); g.lineTo(bx + 38, top + 14); g.closePath(); g.fill();
    g.strokeStyle = 'rgba(255,255,255,.06)'; g.lineWidth = 1; g.beginPath(); g.moveTo(bx + 48, top - 10 - br); g.quadraticCurveTo(bx + 84, top - 16 - br, bx + 126, top - 8); g.stroke(); g.restore();
  }
  // ---------- one room ----------
  function room(a, h, day) {
    const r = roomOf(a), fy = FLOORS[a.f], [sTop, sBot] = sky(h);
    const wg = g.createLinearGradient(0, r.y, 0, fy); wg.addColorStop(0, mix(a.wall, '#000000', .25)); wg.addColorStop(1, a.wall); g.fillStyle = wg; g.fillRect(r.x, r.y, r.w, r.h);
    g.fillStyle = 'rgba(255,255,255,.018)'; for (let x = r.x + 10; x < r.x + r.w; x += 18) g.fillRect(x, r.y, 1, r.h);
    g.fillStyle = mix(a.wall, '#000000', .45); g.fillRect(r.x, fy - 9, r.w, 9);
    // window over the bed
    const wx = r.x + 40, wy = fy - 230, ww = 92, wh = 84;
    const sg = g.createLinearGradient(0, wy, 0, wy + wh); sg.addColorStop(0, sTop); sg.addColorStop(1, sBot); g.fillStyle = sg; g.fillRect(wx, wy, ww, wh);
    if (day < .3) { g.fillStyle = `rgba(255,240,220,${.6 * (1 - day / .3)})`; for (let k = 0; k < 5; k++) g.fillRect(wx + ((k * 37 + a.c * 13) % ww), wy + ((k * 23 + a.f * 17) % (wh - 10)), 1.4, 1.4); }
    g.strokeStyle = '#120c0b'; g.lineWidth = 5; g.strokeRect(wx, wy, ww, wh); g.lineWidth = 2.5; g.beginPath(); g.moveTo(wx + ww / 2, wy); g.lineTo(wx + ww / 2, wy + wh); g.moveTo(wx, wy + wh / 2); g.lineTo(wx + ww, wy + wh / 2); g.stroke();
    g.fillStyle = mix(a.blanket, '#000000', .3); g.fillRect(wx - 12, wy - 8, 14, wh + 26); g.fillRect(wx + ww - 2, wy - 8, 14, wh + 26); g.fillStyle = '#100a09'; g.fillRect(wx - 16, wy - 11, ww + 32, 4);
    // bed
    const bx = r.x + 14; g.fillStyle = '#2a1a14'; rr(bx, fy - 92, 10, 92, 3); g.fill(); g.fillRect(bx, fy - 40, 136, 30); g.fillRect(bx + 128, fy - 58, 8, 58);
    g.fillStyle = '#d9d0c4'; rr(bx + 8, fy - 52, 124, 14, 5); g.fill();
    g.fillStyle = '#efe8dc'; rr(bx + 12, fy - 64, 34, 13, 6); g.fill();
    if (a.state !== 'sleep' && !(a.state === 'lieDown' && a.until < .8)) { g.fillStyle = a.blanket; rr(bx + 52, fy - 54, 82, 18, 4); g.fill(); g.fillStyle = 'rgba(255,255,255,.07)'; g.fillRect(bx + 52, fy - 54, 82, 3); }
    // floor lamp by the bed
    g.strokeStyle = '#18100e'; g.lineWidth = 3; g.beginPath(); g.moveTo(r.x + 160, fy); g.lineTo(r.x + 160, fy - 120); g.stroke(); g.fillStyle = '#5a463a'; g.beginPath(); g.moveTo(r.x + 148, fy - 120); g.lineTo(r.x + 172, fy - 120); g.lineTo(r.x + 166, fy - 140); g.lineTo(r.x + 154, fy - 140); g.closePath(); g.fill();
    // desk, chair, computer
    const dx = r.x + 262; g.fillStyle = '#3a2820'; g.fillRect(dx, fy - 66, 108, 6); g.fillStyle = '#2a1c16'; g.fillRect(dx + 4, fy - 60, 5, 60); g.fillRect(dx + 99, fy - 60, 5, 60); g.fillRect(dx + 60, fy - 60, 42, 30);
    g.fillStyle = '#121012'; rr(dx + 34, fy - 128, 62, 42, 3); g.fill(); g.fillRect(dx + 62, fy - 86, 6, 18); g.fillRect(dx + 52, fy - 70, 26, 4);
    g.fillStyle = '#1a1414'; g.fillRect(dx + 20, fy - 69, 30, 3);
    const cx = r.x + 245; g.fillStyle = '#2b2b30'; g.fillRect(cx - 18, fy - 38, 34, 6); g.fillRect(cx - 20, fy - 100, 6, 62); g.fillRect(cx - 2, fy - 32, 4, 26); g.fillRect(cx - 14, fy - 7, 28, 4);
    propDraw(a, r, fy);
  }
  function propDraw(a, r, fy) {
    const px = r.x + 168;
    if (a.prop === 'shelf') { g.fillStyle = '#2a1a14'; g.fillRect(px, fy - 200, 66, 200); g.fillStyle = '#1a100d'; for (let s = 0; s < 5; s++) g.fillRect(px + 4, fy - 196 + s * 39, 58, 35);
      let k = 0; for (let s = 0; s < 5; s++) { let x = px + 6; while (x < px + 58 && k < BOOKS.length) { const b = BOOKS[k++]; g.fillStyle = b[2]; g.fillRect(x, fy - 196 + s * 39 + 35 - b[1], b[0], b[1]); x += b[0] + 1; } } }
    else if (a.prop === 'calendar') { g.fillStyle = '#e8dfd2'; g.fillRect(px + 2, fy - 220, 62, 70); g.fillStyle = '#8a2c24'; g.fillRect(px + 2, fy - 220, 62, 12); g.fillStyle = 'rgba(40,30,28,.55)';
      for (let k = 0; k < 28; k++) g.fillRect(px + 5 + (k % 7) * 8.4, fy - 204 + Math.floor(k / 7) * 13, 6.6, 10); g.fillStyle = '#c9665c'; g.fillRect(px + 5 + 3 * 8.4, fy - 204 + 13, 6.6, 10);
      g.fillStyle = '#2a1a14'; g.fillRect(px + 4, fy - 56, 58, 56); g.fillStyle = '#1a100d'; g.fillRect(px + 8, fy - 50, 50, 20); g.fillRect(px + 8, fy - 26, 50, 20); }
    else if (a.prop === 'screens') { g.fillStyle = '#121214'; for (let k = 0; k < 6; k++) g.fillRect(px + (k % 2) * 34, fy - 228 + Math.floor(k / 2) * 34, 30, 28); g.fillStyle = '#1c1616'; g.fillRect(px + 6, fy - 50, 54, 50); }
    else if (a.prop === 'safe') { g.fillStyle = '#2c2e30'; rr(px + 4, fy - 74, 58, 74, 4); g.fill(); g.strokeStyle = '#4a4c50'; g.lineWidth = 2; g.strokeRect(px + 10, fy - 68, 46, 62); g.fillStyle = '#9a8a70'; g.beginPath(); g.arc(px + 33, fy - 37, 7, 0, 7); g.fill();
      g.fillStyle = '#e8dfd2'; g.fillRect(px + 4, fy - 210, 60, 46); g.strokeStyle = '#3d4a3f'; g.lineWidth = 2; g.beginPath(); [[0, 36], [10, 30], [20, 32], [30, 22], [40, 24], [52, 10]].forEach(([x, y], i) => i ? g.lineTo(px + 8 + x, fy - 210 + y) : g.moveTo(px + 8 + x, fy - 210 + y)); g.stroke(); }
    else if (a.prop === 'paper') { g.fillStyle = '#2a1a14'; g.fillRect(px, fy - 52, 66, 6); g.fillRect(px + 4, fy - 46, 5, 46); g.fillRect(px + 57, fy - 46, 5, 46);
      for (let s = 0; s < 3; s++) for (let k = 0; k < 6 + s * 2; k++) { g.fillStyle = k % 2 ? '#e8e0d4' : '#d8cfc2'; g.fillRect(px + 4 + s * 20, fy - 55 - k * 2.4, 17, 2.2); }
      g.fillStyle = '#e8dfd2'; g.fillRect(px + 10, fy - 200, 18, 24); g.fillRect(px + 34, fy - 190, 18, 24); g.fillStyle = '#c9665c'; g.beginPath(); g.arc(px + 43, fy - 176, 5, 0, 7); g.fill(); }
    else if (a.prop === 'kitchen') { g.fillStyle = '#3a2a22'; g.fillRect(px - 6, fy - 66, 78, 66); g.fillStyle = '#5a4a40'; g.fillRect(px - 8, fy - 70, 82, 6); g.fillStyle = '#1a1414'; g.fillRect(px + 4, fy - 74, 30, 4); g.fillStyle = '#4a4440'; rr(px + 8, fy - 92, 22, 18, 3); g.fill();
      g.fillStyle = '#2a201c'; g.fillRect(px - 2, fy - 220, 70, 50); g.strokeStyle = '#1a1210'; g.lineWidth = 1.5; g.strokeRect(px - 2, fy - 220, 35, 50); g.strokeRect(px + 33, fy - 220, 35, 50); }
    else if (a.prop === 'frames') { [[0, -210, 26, 32], [32, -200, 30, 24], [8, -166, 40, 30]].forEach(([x, y, w, h], i) => { g.fillStyle = '#2a1a14'; g.fillRect(px + x, fy + y, w, h); g.fillStyle = ['#7a5a48', '#5a4a52', '#6a5040'][i]; g.fillRect(px + x + 3, fy + y + 3, w - 6, h - 6); g.fillStyle = 'rgba(30,20,18,.6)'; g.beginPath(); g.arc(px + x + w / 2, fy + y + h * .45, h * .16, 0, 7); g.fill(); });
      g.fillStyle = '#3a2a24'; rr(px - 4, fy - 46, 74, 34, 8); g.fill(); g.fillStyle = '#7a3b3b'; rr(px - 2, fy - 58, 70, 22, 8); g.fill(); g.fillStyle = '#2a1a14'; g.fillRect(px, fy - 12, 4, 12); g.fillRect(px + 62, fy - 12, 4, 12); }
    else if (a.prop === 'files') { g.fillStyle = '#3a3634'; g.fillRect(px + 6, fy - 150, 54, 150); g.fillStyle = '#2a2624'; for (let k = 0; k < 4; k++) { g.fillRect(px + 10, fy - 146 + k * 37, 46, 33); } g.fillStyle = '#8a8070'; for (let k = 0; k < 4; k++) g.fillRect(px + 27, fy - 136 + k * 37, 12, 3); }
    else if (a.prop === 'gym') { g.fillStyle = '#2a2a2e'; g.fillRect(px + 2, fy - 120, 6, 120); g.fillRect(px + 58, fy - 120, 6, 120); g.fillStyle = '#4a4a50'; g.fillRect(px - 8, fy - 92, 82, 4);
      g.fillStyle = '#18181c'; g.beginPath(); g.arc(px - 6, fy - 90, 14, 0, 7); g.fill(); g.beginPath(); g.arc(px + 72, fy - 90, 14, 0, 7); g.fill();
      g.fillStyle = '#2c2c30'; g.fillRect(px - 4, fy - 34, 74, 8); g.fillRect(px + 4, fy - 26, 5, 26); g.fillRect(px + 57, fy - 26, 5, 26);
      g.fillStyle = '#8a2c24'; g.fillRect(px + 4, fy - 220, 58, 74); g.fillStyle = 'rgba(239,235,228,.75)'; for (let k = 0; k < 3; k++) g.fillRect(px + 12, fy - 206 + k * 12, 42 - k * 8, 5); }
    else if (a.prop === 'studio') { g.strokeStyle = '#6a6a70'; g.lineWidth = 3; g.beginPath(); g.moveTo(px - 6, fy); g.lineTo(px - 6, fy - 132); g.lineTo(px + 72, fy - 132); g.lineTo(px + 72, fy); g.stroke();
      ['#151517', '#d8d0c4', '#151517', '#8a2c24', '#151517'].forEach((c, k) => { const x = px - 2 + k * 15; g.fillStyle = c; g.beginPath(); g.moveTo(x + 2, fy - 128); g.lineTo(x + 12, fy - 128); g.lineTo(x + 14, fy - 78); g.lineTo(x, fy - 78); g.closePath(); g.fill(); g.strokeStyle = 'rgba(255,255,255,.08)'; g.lineWidth = 1; g.stroke(); });
      g.fillStyle = '#e8e0d4'; g.fillRect(px - 2, fy - 236, 70, 52); [[4, 6, '#151517'], [26, 4, '#8a2c24'], [46, 10, '#3a3a40'], [10, 30, '#8a7a6a'], [36, 28, '#151517']].forEach(([x, y, c]) => { g.fillStyle = c; g.fillRect(px - 2 + x, fy - 236 + y, 16, 16); }); }
    else if (a.prop === 'front') { const cx0 = px + 33, cy0 = fy - 196, d = new Date(), hr = (d.getHours() % 12 + d.getMinutes() / 60) / 12 * 6.283, mn = d.getMinutes() / 60 * 6.283;
      g.fillStyle = '#e8e0d4'; g.beginPath(); g.arc(cx0, cy0, 22, 0, 7); g.fill(); g.strokeStyle = '#2a1a14'; g.lineWidth = 3; g.stroke();
      g.lineWidth = 2.5; g.beginPath(); g.moveTo(cx0, cy0); g.lineTo(cx0 + Math.sin(hr) * 11, cy0 - Math.cos(hr) * 11); g.stroke(); g.lineWidth = 1.5; g.beginPath(); g.moveTo(cx0, cy0); g.lineTo(cx0 + Math.sin(mn) * 17, cy0 - Math.cos(mn) * 17); g.stroke();
      g.fillStyle = '#3a2a22'; g.fillRect(px - 6, fy - 60, 78, 60); g.fillStyle = '#5a4436'; g.fillRect(px - 8, fy - 64, 82, 6); g.fillStyle = '#e8e0d4'; g.fillRect(px + 6, fy - 72, 22, 8); g.fillStyle = '#8a7a6a'; g.fillRect(px + 40, fy - 76, 6, 12); }
    else if (a.prop === 'boxes') { g.fillStyle = '#2a1a14'; for (let s = 0; s < 4; s++) g.fillRect(px - 6, fy - 4 - s * 54, 80, 5); g.fillRect(px - 6, fy - 166, 5, 166); g.fillRect(px + 69, fy - 166, 5, 166);
      [[0, 0, 30, 26], [32, 0, 34, 34], [2, 1, 40, 30], [44, 1, 24, 20], [0, 2, 22, 22], [24, 2, 30, 40]].forEach(([x, s, w, h]) => { const y = fy - 4 - s * 54 - h; g.fillStyle = '#a0784e'; g.fillRect(px + x, y, w, h); g.fillStyle = 'rgba(0,0,0,.18)'; g.fillRect(px + x + w / 2 - 2, y, 4, h); g.fillStyle = 'rgba(255,255,255,.5)'; g.fillRect(px + x + 4, y + h - 9, 9, 5); }); }
    else if (a.prop === 'pantry') { g.fillStyle = '#2a1a14'; for (let s = 0; s < 4; s++) g.fillRect(px - 6, fy - 4 - s * 54, 80, 5); g.fillRect(px - 6, fy - 166, 5, 166); g.fillRect(px + 69, fy - 166, 5, 166);
      for (let s = 0; s < 3; s++) for (let k = 0; k < 5; k++) { const x = px + 1 + k * 13, y = fy - 4 - s * 54; if (s === 0) { g.fillStyle = k % 2 ? '#d8e2ea' : '#c6d4e0'; g.fillRect(x, y - 30, 12, 30); g.fillStyle = '#7aa0c0'; g.fillRect(x + 2, y - 22, 8, 6); }
        else { g.fillStyle = ['#c9a46a', '#8a5a3c', '#d8cfc2', '#6a8a5a', '#b05a4a'][(k + s) % 5]; g.fillRect(x + 1, y - 18 - (k % 2) * 6, 10, 18 + (k % 2) * 6); g.fillStyle = 'rgba(255,255,255,.15)'; g.fillRect(x + 1, y - 18 - (k % 2) * 6, 10, 3); } } }
    if (a.wide) { const bx = r.x + 440;   // the workshop: a long bench, a pegboard of tools, and the server rack he tests himself on
      g.fillStyle = '#3a2a1e'; g.fillRect(bx, fy - 70, 300, 8); g.fillStyle = '#2a1c14'; g.fillRect(bx + 6, fy - 62, 8, 62); g.fillRect(bx + 286, fy - 62, 8, 62); g.fillRect(bx + 6, fy - 22, 288, 5);
      g.fillStyle = '#2e2620'; g.fillRect(bx + 20, fy - 200, 250, 110); g.fillStyle = 'rgba(0,0,0,.35)'; for (let x = 0; x < 25; x++) for (let y = 0; y < 10; y++) g.fillRect(bx + 26 + x * 10, fy - 196 + y * 10, 1.5, 1.5);
      g.strokeStyle = '#8a7a6a'; g.lineWidth = 3; g.lineCap = 'round'; [[50, 0], [80, 1], [110, 0], [150, 2], [190, 1], [230, 0]].forEach(([x, k]) => { g.beginPath(); g.moveTo(bx + x, fy - 185); g.lineTo(bx + x + (k === 1 ? 6 : 0), fy - 135 + k * 8); g.stroke(); });
      g.fillStyle = '#1b2a3a'; g.fillRect(bx + 60, fy - 112, 90, 40); g.strokeStyle = 'rgba(200,220,240,.35)'; g.lineWidth = 1; g.strokeRect(bx + 66, fy - 106, 40, 26); g.beginPath(); g.moveTo(bx + 110, fy - 100); g.lineTo(bx + 140, fy - 84); g.stroke();
      g.fillStyle = '#141416'; g.fillRect(r.x + 800, fy - 190, 70, 190); g.fillStyle = '#1e1e22'; for (let k = 0; k < 8; k++) g.fillRect(r.x + 804, fy - 186 + k * 23, 62, 19);
      g.fillStyle = '#3a2a22'; g.beginPath(); g.moveTo(r.x, r.y); g.lineTo(r.x + 120, r.y); g.lineTo(r.x, r.y + 60); g.fill(); g.beginPath(); g.moveTo(r.x + r.w, r.y); g.lineTo(r.x + r.w - 120, r.y); g.lineTo(r.x + r.w, r.y + 60); g.fill(); }
  }
  // what glows: screens that are on, lamps, the stove, the server lights
  function emissive(a, h, lightOn) {
    const r = roomOf(a), fy = FLOORS[a.f], dx = r.x + 262, working = a.state === 'work' && !a.path.length, t = T;
    if (lightOn) { glow(r.x + 160, fy - 128, 120, 'rgba(255,196,140,A)', .2); g.fillStyle = 'rgba(255,214,170,.85)'; g.fillRect(r.x + 154, fy - 122, 12, 3); }
    if (working) { const sg = g.createLinearGradient(0, fy - 125, 0, fy - 89); sg.addColorStop(0, '#f6dfd4'); sg.addColorStop(1, '#e8b8a6'); g.fillStyle = sg; g.fillRect(dx + 37, fy - 125, 56, 36);
      g.fillStyle = 'rgba(80,30,26,.55)'; for (let k = 0; k < 6; k++) { const w = 12 + ((k * 13 + Math.floor(t * 3)) % 30); g.fillRect(dx + 41, fy - 121 + k * 5.4, w, 2); }
      glow(dx + 50, fy - 105, 90, 'rgba(255,200,180,A)', .22); }
    else if (a.state !== 'sleep') { g.fillStyle = '#2a1e22'; g.fillRect(dx + 37, fy - 125, 56, 36); }
    if (a.state !== 'sleep' || a.wide) { g.fillStyle = 'rgba(255,170,140,.85)'; g.fillRect(dx + 88, fy - 92, 2, 2); }
    const px = r.x + 168;
    if (a.prop === 'screens' && a.state !== 'sleep') for (let k = 0; k < 6; k++) { const x = px + (k % 2) * 34 + 3, y = fy - 228 + Math.floor(k / 2) * 34 + 3; g.fillStyle = 'rgba(255,214,200,.75)'; g.fillRect(x, y, 24, 22);
      const off = (t * 8 + k * 5) % 22; g.fillStyle = 'rgba(160,60,50,.7)'; for (let m = 0; m < 3; m++) { const yy = y + 20 - ((off + m * 8) % 22); if (yy > y + 1) g.fillRect(x + (m % 2 ? 8 : 2), yy, 14, 3); } glow(x + 12, y + 11, 30, 'rgba(255,190,170,A)', .12); }
    if (a.prop === 'kitchen' && a.state !== 'sleep') { glow(r.x + 187, fy - 74, 20, 'rgba(255,120,60,A)', .6); for (let k = 0; k < 3; k++) { g.strokeStyle = `rgba(255,240,230,${.18 - k * .04})`; g.lineWidth = 2; g.beginPath(); for (let u = 0; u <= 1; u += .1) { const y = fy - 94 - u * 60, x = r.x + 185 + k * 6 + Math.sin(u * 8 + t * 2.4 + k) * 4; u ? g.lineTo(x, y) : g.moveTo(x, y); } g.stroke(); } }
    if (a.wide) for (let k = 0; k < 8; k++) { const on = Math.sin(t * (2 + k * .7) + k * 3) > (a.state === 'sleep' ? .6 : -.2); g.fillStyle = on ? (k % 3 ? 'rgba(255,170,140,.95)' : 'rgba(160,230,180,.9)') : 'rgba(80,40,36,.6)'; g.fillRect(r.x + 856, fy - 180 + k * 23, 4, 3); g.fillRect(r.x + 848, fy - 180 + k * 23, 4, 3); }
    if (a.prop === 'files' && a.state === 'prop') glow(px + 33, fy - 100, 60, 'rgba(255,190,150,A)', .25);
    if (a.prop === 'paper' && a.state === 'prop') { const d = Math.max(0, Math.sin(t * 5)); if (d > .9) glow(px + 24, fy - 60, 30, 'rgba(201,102,92,A)', .6); }
  }
  // Jarvis's room: the core of the house
  function jarvisRoom(h) {
    const r = jRoom, fy = FLOORS[2], cx = r.x + r.w / 2, cy = fy - 140;
    const wg = g.createLinearGradient(0, r.y, 0, fy); wg.addColorStop(0, '#0e0809'); wg.addColorStop(1, '#1e0f10'); g.fillStyle = wg; g.fillRect(r.x, r.y, r.w, r.h);
    g.strokeStyle = 'rgba(201,102,92,.18)'; g.lineWidth = 1.5;
    for (let k = 0; k < 10; k++) { const side = k % 2 ? r.x : r.x + r.w, yy = r.y + 30 + k * 24; g.beginPath(); g.moveTo(side, yy); g.bezierCurveTo((side + cx) / 2, yy, cx + (side < cx ? -60 : 60), cy, cx, cy); g.stroke(); }
    g.fillStyle = '#1a1214'; g.beginPath(); g.ellipse(cx, fy - 8, 70, 12, 0, 0, 7); g.fill(); g.fillStyle = '#251618'; g.fillRect(cx - 50, fy - 30, 100, 22); g.fillStyle = '#1a1012'; g.beginPath(); g.ellipse(cx, fy - 30, 50, 8, 0, 0, 7); g.fill();
  }
  function jarvisGlow() {
    const r = jRoom, fy = FLOORS[2], cx = r.x + r.w / 2, cy = fy - 140, t = T, R = 34 + 3 * Math.sin(t * 1.4) + coreFlash * 8;
    const bm = g.createLinearGradient(0, fy - 30, 0, cy); bm.addColorStop(0, `rgba(201,102,92,${.25 + coreFlash * .3})`); bm.addColorStop(1, 'rgba(201,102,92,0)'); g.fillStyle = bm; g.beginPath(); g.moveTo(cx - 44, fy - 30); g.lineTo(cx + 44, fy - 30); g.lineTo(cx + 20, cy); g.lineTo(cx - 20, cy); g.fill();
    glow(cx, cy, R * 4, 'rgba(176,82,74,A)', .45 + coreFlash * .35);
    const yaw = t * .4; g.fillStyle = 'rgba(239,220,210,.7)';
    for (const p of CORE) { const x = p[0] * Math.cos(yaw) + p[2] * Math.sin(yaw), z = -p[0] * Math.sin(yaw) + p[2] * Math.cos(yaw), s = 1.6 * (z + 1.6) / 2.6; g.globalAlpha = .25 + .5 * (z + 1) / 2; g.fillRect(cx + x * R * 2.1 - s / 2, cy + p[1] * R * 2.1 - s / 2, s, s); }
    g.globalAlpha = 1; g.fillStyle = '#151517'; g.beginPath(); g.arc(cx, cy, R * .6, 0, 7); g.fill(); g.strokeStyle = `rgba(201,102,92,${.8 + coreFlash * .2})`; g.lineWidth = 3; g.stroke();
    g.strokeStyle = 'rgba(255,222,212,.85)'; g.lineWidth = 3.5; g.lineCap = 'round'; g.beginPath(); g.arc(cx, cy, R * .6, t * 1.2, t * 1.2 + 1); g.stroke();
  }
  function garage(day) {
    const r = gRoom, fy = FLOORS[LAST]; const wg = g.createLinearGradient(0, r.y, 0, fy); wg.addColorStop(0, '#141213'); wg.addColorStop(1, '#201c1d'); g.fillStyle = wg; g.fillRect(r.x, r.y, r.w, r.h);
    g.fillStyle = '#2a2628'; for (let k = 0; k < 7; k++) g.fillRect(r.x + 250, r.y + 40 + k * 34, 120, 30);
    g.fillStyle = '#181617'; g.fillRect(r.x, fy - 9, r.w, 9);
    // a car under a fitted cover
    const cx = r.x + 30; g.fillStyle = '#3a3436'; g.beginPath(); g.moveTo(cx, fy - 14); g.quadraticCurveTo(cx - 2, fy - 50, cx + 30, fy - 56); g.quadraticCurveTo(cx + 70, fy - 96, cx + 130, fy - 98); g.quadraticCurveTo(cx + 190, fy - 96, cx + 220, fy - 62); g.quadraticCurveTo(cx + 250, fy - 56, cx + 252, fy - 30); g.lineTo(cx + 250, fy - 14); g.closePath(); g.fill();
    g.strokeStyle = 'rgba(255,255,255,.07)'; g.lineWidth = 2; g.beginPath(); g.moveTo(cx + 40, fy - 58); g.quadraticCurveTo(cx + 120, fy - 90, cx + 210, fy - 62); g.stroke();
    g.fillStyle = '#0e0c0c'; [cx + 50, cx + 200].forEach(x => { g.beginPath(); g.arc(x, fy - 14, 15, Math.PI, 0); g.fill(); });
    // the fund on the wall
    let pct = 0; try { pct = parseFloat((document.querySelector('#t-goal .big') || {}).textContent) || 0; } catch (_) {}
    g.fillStyle = '#0f0d0e'; rr(r.x + 40, fy - 250, 170, 74, 8); g.fill(); g.strokeStyle = 'rgba(201,102,92,.4)'; g.lineWidth = 1.5; g.stroke();
    g.fillStyle = 'rgba(239,235,228,.85)'; g.font = '600 12px Inter, system-ui, sans-serif'; g.textAlign = 'left'; g.fillText('MACAN FUND', r.x + 54, fy - 228); g.font = '400 26px Fraunces, Georgia, serif'; g.fillText(Math.round(pct) + '%', r.x + 54, fy - 196);
    g.fillStyle = '#2a2224'; g.fillRect(r.x + 112, fy - 206, 84, 5); g.fillStyle = '#c9665c'; g.fillRect(r.x + 112, fy - 206, 84 * Math.min(1, pct / 100), 5);
  }
  function garageGlow() { const r = gRoom, fy = FLOORS[LAST]; glow(r.x + 125, fy - 214, 110, 'rgba(201,102,92,A)', .18); glow(r.x + 150, fy - 60, 160, 'rgba(255,220,200,A)', .05); }
  function orb(x, y, k) { glow(x, y, 26 * k, 'rgba(255,190,160,A)', .9); g.fillStyle = `rgba(255,238,230,${k})`; g.beginPath(); g.arc(x, y, 3.5 * k, 0, 7); g.fill(); }
  // ---------- the house itself ----------
  function structure() {
    g.fillStyle = '#0e0a0a';
    for (let f = 0; f < FLOORS.length; f++) g.fillRect(X0 - 10, FLOORS[f], RW * 3 + SW + 20, 16);
    g.fillRect(X0 - 12, FLOORS[0] - 236, 12, FLOORS[LAST] - FLOORS[0] + 252); g.fillRect(STX + SW, FLOORS[0] - 236, 12, FLOORS[LAST] - FLOORS[0] + 252);
    for (let f = 1; f < FLOORS.length; f++) for (let c = 1; c <= 3; c++) { const x = X0 + RW * c - 5; g.fillRect(x, FLOORS[f] - FH, 10, FH - 150); }
    g.fillRect(STX - 5, FLOORS[0] - 230, 10, 80);
    // roof
    const rg = g.createLinearGradient(0, 20, 0, FLOORS[0] - 230); rg.addColorStop(0, '#2a1a18'); rg.addColorStop(1, '#170f0e'); g.fillStyle = rg;
    g.beginPath(); g.moveTo(X0 - 50, FLOORS[0] - 222); g.lineTo((X0 + STX + SW) / 2, 30); g.lineTo(STX + SW + 50, FLOORS[0] - 222); g.closePath(); g.fill();
    g.strokeStyle = 'rgba(0,0,0,.35)'; g.lineWidth = 2; for (let k = 1; k < 8; k++) { const y = 30 + k * ((FLOORS[0] - 252) / 8); const half = (y - 30) / (FLOORS[0] - 252) * ((STX + SW + 50) - (X0 + STX + SW) / 2); g.beginPath(); g.moveTo((X0 + STX + SW) / 2 - half, y); g.lineTo((X0 + STX + SW) / 2 + half, y); g.stroke(); }
    g.fillStyle = '#120c0b'; g.fillRect(STX - 120, 60, 40, 80);
  }
  function stairs(day) {
    const x0 = STX, w = SW; g.fillStyle = '#161112'; g.fillRect(x0, FLOORS[0] - 230, w, FLOORS[LAST] - FLOORS[0] + 230);
    for (let f = 0; f < LAST; f++) { const upX = f % 2 === 0 ? x0 + 22 : x0 + w - 22, loX = f % 2 === 0 ? x0 + w - 22 : x0 + 22, yU = FLOORS[f], yL = FLOORS[f + 1], n = 14;
      g.fillStyle = '#2a1c16'; for (let k = 0; k < n; k++) { const u = k / n, x = upX + (loX - upX) * u, y = yU + (yL - yU) * u; g.fillRect(Math.min(x, x + (loX - upX) / n), y, Math.abs(loX - upX) / n + 1, (yL - yU) / n + 2); }
      g.strokeStyle = '#3a2820'; g.lineWidth = 3; g.beginPath(); g.moveTo(upX, yU - 46); g.lineTo(loX, yL - 46); g.stroke(); }
    g.fillStyle = `rgba(4,5,12,${.55 * (1 - day)})`; g.fillRect(x0, FLOORS[0] - 230, w, FLOORS[LAST] - FLOORS[0] + 230);
  }
  function frame(ts) {
    requestAnimationFrame(frame);
    const tt = ts / 1000, dt = Math.min(.05, tt - last); last = tt;
    if (!open) return;
    T += dt; if (fast) simMin += dt * 6; else simMin = realNow(); coreFlash = Math.max(0, coreFlash - dt * 1.2);
    AGS.forEach(a => step(a, dt));
    if ((T * 2 | 0) !== ((T - dt) * 2 | 0)) { $('h-clock').textContent = clockTxt(); const awake = AGS.filter(a => !asleep(a)).length, busy = AGS.filter(a => a.job).length; $('h-awake').textContent = busy ? `${busy} on a job · ${awake} awake` : `${awake} awake · ${AGS.length - awake} asleep`; }
    const h = hourNow(), day = daylight(h), [sTop, sBot] = sky(h);
    // camera: whole house, or one agent's room (following them if they walk out)
    const cy0 = (H - 150) * .5 + 40;
    if (zoomed && !pan) { const a = AGS[focusI], rm = roomOf(a), inRoom = a.fl === a.f && a.x > rm.x && a.x < rm.x + rm.w; camT.z = W / (RW + 30);
      camT.x = a.wide || !inRoom ? Math.max(X0 + W / camT.z / 2 - 20, Math.min(STX + SW - W / camT.z / 2 + 20, a.x)) : rm.x + RW / 2; camT.y = inRoom ? FLOORS[a.f] - FH / 2 + 10 : a.y - 120; }
    else if (!pan) { camT.z = Math.min(W / 1460, (H - 230) / 1760); camT.x = 700; camT.y = (FLOORS[0] - 236 + FLOORS[LAST]) / 2 + 40; }
    const kk = 1 - Math.pow(.02, dt); cam.x += (camT.x - cam.x) * kk; cam.y += (camT.y - cam.y) * kk; cam.z += (camT.z - cam.z) * kk;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const bg = g.createLinearGradient(0, 0, 0, H); bg.addColorStop(0, sTop); bg.addColorStop(1, sBot); g.fillStyle = bg; g.fillRect(0, 0, W, H);
    if (day < .5) for (const s of STARS) { g.fillStyle = `rgba(255,240,225,${(.5 - day) * (.3 + .7 * s[2]) * (.7 + .3 * Math.sin(T * 2 + s[0] * 40))})`; g.fillRect(s[0] * W, s[1] * H, 1.2, 1.2); }
    const sunA = ((h - 6) / 14) * Math.PI; if (h > 5.5 && h < 20.5) glow(W * (.1 + .8 * (h - 6) / 14), H * (.42 - .3 * Math.sin(Math.max(0, Math.min(Math.PI, sunA)))), 60, 'rgba(255,190,140,A)', .35 * Math.min(1, day + .3));
    else { const mx = W * .78, my = H * .12; glow(mx, my, 40, 'rgba(230,225,240,A)', .25); g.fillStyle = '#e8e2ea'; g.beginPath(); g.arc(mx, my, 9, 0, 7); g.fill(); g.fillStyle = sTop; g.beginPath(); g.arc(mx + 4, my - 2, 8, 0, 7); g.fill(); }
    g.setTransform(dpr * cam.z, 0, 0, dpr * cam.z, dpr * (W / 2 - cam.x * cam.z), dpr * (cy0 - cam.y * cam.z));
    // the city behind, and the ground
    for (const b of CITY) { g.fillStyle = mix('#0c0a0e', sBot, .25); g.fillRect(b.x * 1.1 - 200, FLOORS[LAST] + 16 - b.h, b.w, b.h); if (day < .6) b.lit.forEach((on, k) => { if (on) { g.fillStyle = 'rgba(255,200,150,.5)'; g.fillRect(b.x * 1.1 - 196 + (k % 2) * 10, FLOORS[LAST] + 6 - b.h + 10 + Math.floor(k / 2) * 16, 4, 6); } }); }
    const gg = g.createLinearGradient(0, FLOORS[LAST], 0, FLOORS[LAST] + 200); gg.addColorStop(0, '#15100e'); gg.addColorStop(1, '#070506'); g.fillStyle = gg; g.fillRect(-2000, FLOORS[LAST] + 10, 6000, 600);
    stairs(day);
    AGS.forEach(a => room(a, h, day)); jarvisRoom(h); garage(day);
    // the people, then each room's own light (lamps off and moonlight when they sleep)
    AGS.forEach(a => { if (asleep(a)) sleeper(a, roomOf(a)); });
    AGS.slice().sort((p, q) => (p.path.length ? 1 : 0) - (q.path.length ? 1 : 0)).forEach(a => { if (!asleep(a)) person(a, a.x, a.y); });
    AGS.forEach(a => { const r = roomOf(a), on = !asleep(a); const dark = on ? .12 * (1 - day) : .78 * (1 - day) + .1;
      g.fillStyle = `rgba(4,6,16,${dark})`; g.fillRect(r.x, r.y, r.w, r.h);
      if (!on && day < .4) { const wx = r.x + 40; const mg = g.createLinearGradient(wx, FLOORS[a.f] - 230, wx + 120, FLOORS[a.f]); mg.addColorStop(0, 'rgba(170,180,220,.12)'); mg.addColorStop(1, 'rgba(170,180,220,0)'); g.fillStyle = mg; g.beginPath(); g.moveTo(wx, FLOORS[a.f] - 230); g.lineTo(wx + 92, FLOORS[a.f] - 230); g.lineTo(wx + 170, FLOORS[a.f]); g.lineTo(wx + 40, FLOORS[a.f]); g.fill(); } });
    g.fillStyle = `rgba(4,6,16,${.1 * (1 - day)})`; g.fillRect(jRoom.x, jRoom.y, jRoom.w, jRoom.h);
    structure();
    g.save(); g.globalCompositeOperation = 'lighter';
    AGS.forEach(a => emissive(a, h, !asleep(a))); jarvisGlow(); garageGlow();
    AGS.forEach(a => { if (a.orb > 0) { const dirn = a.dir; const hx = a.x + dirn * 26 * S, hy = a.y - 60 * S;
      if (a.state === 'give') { const k = 1 - a.orb, jx = jRoom.x + RW / 2, jy = FLOORS[2] - 140; orb(hx + (jx - hx) * Math.max(0, (1.4 - a.until - .7) / .7), hy + (jy - hy) * Math.max(0, (1.4 - a.until - .7) / .7), Math.max(.2, a.orb)); } else orb(hx, hy, 1); } });
    g.restore();
    // a lit window at night is the only sign of who's up
    // name plates, in screen space so they stay readable
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const toS = (x, y) => [W / 2 + (x - cam.x) * cam.z, cy0 + (y - cam.y) * cam.z];
    g.textAlign = 'left';
    [...AGS, {name: 'Jarvis', room: 'The core', jarvis: true, R: jRoom}, {name: 'Garage', room: 'The Macan fund', jarvis: true, R: gRoom}].forEach((a, i) => { const r = a.R || roomOf(a), [sx, sy] = toS(r.x + 12, r.y + 16), on = a.jarvis || !asleep(a), foc = !a.jarvis && AGS[focusI] === a;
      const big = zoomed; g.font = `600 ${big ? 11 : 8.5}px Inter, system-ui, sans-serif`; const label = big ? `${a.name.toUpperCase()}  ·  ${a.room}` : a.name.toUpperCase(); const tw = g.measureText(label).width;
      g.fillStyle = foc ? 'rgba(60,24,22,.85)' : 'rgba(10,8,9,.7)'; rr(sx - 4, sy - (big ? 11 : 9), tw + (big ? 26 : 20), big ? 18 : 14, 7); g.fill();
      g.fillStyle = a.job ? '#ffd2c2' : on ? '#e2774f' : '#5a5450'; g.beginPath(); g.arc(sx + 3, sy - (big ? 2 : 2), big ? 3 : 2.4, 0, 7); g.fill();
      g.fillStyle = on ? 'rgba(239,235,228,.95)' : 'rgba(162,157,149,.75)'; g.fillText(label, sx + (big ? 11 : 9), sy + (big ? 2 : 1)); });
  }
  requestAnimationFrame(frame);
  // tap a room to go in; drag to look around
  const toWorld = (sx, sy) => { const cy0 = (H - 150) * .5 + 40; return [cam.x + (sx - W / 2) / cam.z, cam.y + (sy - cy0) / cam.z]; };
  cv.addEventListener('pointerdown', e => { pan = {x: e.clientX, y: e.clientY, moved: 0}; try { cv.setPointerCapture(e.pointerId); } catch (_) {} });
  cv.addEventListener('pointermove', e => { if (!pan) return; const dx = e.clientX - pan.x, dy = e.clientY - pan.y; pan.moved += Math.abs(dx) + Math.abs(dy); pan.x = e.clientX; pan.y = e.clientY;
    if (pan.moved > 8) { camT.x = cam.x -= dx / cam.z; camT.y = cam.y -= dy / cam.z; } });
  cv.addEventListener('pointerup', e => { const p = pan; pan = null; if (!p || p.moved > 8) { if (p) setTimeout(() => {}, 0); return; }
    const b = cv.getBoundingClientRect(), [wx, wy] = toWorld(e.clientX - b.left, e.clientY - b.top);
    const i = AGS.findIndex(a => { const r = roomOf(a); return wx > r.x && wx < r.x + r.w && wy > r.y && wy < FLOORS[a.f]; });
    const walker = AGS.findIndex(a => Math.abs(a.x - wx) < 30 && wy < a.y && wy > a.y - 130);
    const k = walker >= 0 ? walker : i; if (k >= 0) { focusI = k; zoomed = true; showFocus(); } });
  // ---------- open, close, back ----------
  const H$ = $('house');
  window.openHouse = () => { open = true; window.houseOpen = true; zoomed = false; simMin = realNow(); H$.classList.add('on'); H$.setAttribute('aria-hidden', 'false'); showFocus(); last = performance.now() / 1000; };
  window.closeHouse = () => { open = false; window.houseOpen = false; H$.classList.remove('on'); H$.setAttribute('aria-hidden', 'true'); };
  window.houseBack = () => { if (!open) return false; if (zoomed) zoomed = false; else window.closeHouse(); return true; };
  $('h-out').onclick = () => window.houseBack();
  $('h-speed').onclick = () => { fast = !fast; $('h-speed').setAttribute('aria-pressed', String(fast)); $('h-speed').textContent = fast ? 'Fast day' : 'Real time'; if (!fast) simMin = realNow(); };
  // the little window into the house on the home screen
  const pv = $('peek');
  if (pv) { const pg = pv.getContext('2d'); let k = 0;
    const draw = ts => { requestAnimationFrame(draw); if (document.hidden || open) return; const t = ts / 1000, W = pv.width, H = pv.height; pg.clearRect(0, 0, W, H);
      const x0 = 132, y0 = 58, cw = 42, ch = 26; pg.fillStyle = '#1c1213'; pg.beginPath(); pg.moveTo(x0 - 12, y0); pg.lineTo(x0 + cw * 1.5, 18); pg.lineTo(x0 + cw * 3 + 12, y0); pg.fill();
      for (let r = 0; r < 5; r++) for (let c = 0; c < 3; c++) { const a = AGS.find(q => q.f === r + 1 && q.c === c), isJ = r === 1 && c === 1, isG = r === 4 && c === 2, flick = .85 + .15 * Math.sin(t * 3 + r * 3 + c);
        pg.fillStyle = isJ ? `rgba(201,102,92,${.55 + .25 * Math.sin(t * 2)})` : isG ? 'rgba(40,36,40,.7)' : a && a.job ? `rgba(255,214,196,${.6 + .3 * Math.sin(t * 6)})` : a && !asleep(a) ? `rgba(255,196,150,${.35 * flick})` : 'rgba(40,36,50,.6)';
        pg.fillRect(x0 + c * cw + 3, y0 + r * ch + 3, cw - 6, ch - 6); } };
    requestAnimationFrame(draw);
    setInterval(() => { if (document.hidden) return; const busy = AGS.filter(a => a.job); let a;
      if (busy.length) { a = busy[k++ % busy.length]; $('peek-line').textContent = `${a.name} is on it: ${short(a.job, 60)}`; return; }
      a = AGS[k++ % AGS.length]; $('peek-line').textContent = asleep(a) ? `${a.name} is asleep` : `${a.name}: ${a.acts[a.act].charAt(0).toLowerCase() + a.acts[a.act].slice(1)}`; }, 3000); }
})();
