// ---- His house, in 3D: a cut-away house inside Jarvis's mind with a furnished room for every agent ----
// They keep their own hours (sleep, get up, sit at their desks, look things up). When one gets a real job it wakes,
// goes to its computer and shows the actual task on its screen; when the job is done it carries the result down to Jarvis.
(function(){
if (!window.THREE) return;
const $ = id => document.getElementById(id);
const T3 = THREE, PI = Math.PI;
// ---------- the plan ----------
const RW = 4, RH = 3, RD = 4, LEVELS = 6, STX = 12, SW = 2.4, LANE = 1.25, ATTIC_H = 2.4;
const floorY = L => L === 0 ? 5 * RH : (5 - L) * RH;            // level 0 is the attic (top), 5 is the ground floor
const AGS = [
  {id:'builder', room:'Workshop', f:0, c:0, wide:true, prop:'bench', wall:'#3a2f28', blanket:'#5a4634', awake:[[13,22],[1,5]],
    look:{skin:'#c48c66', hair:'#2a1c14', style:'cap', top:'#6e4428', pants:'#34312e'}, acts:['Running his night tests', 'Looking over his own code', 'Sketching ideas for upgrades']},
  {id:'research', room:'Library', f:1, c:0, prop:'shelf', wall:'#3b302b', blanket:'#5b4636', awake:[[7,24]],
    look:{skin:'#e0b896', hair:'#5a3a22', style:'long', top:'#6a5440', pants:'#2b2b2e', glasses:true}, acts:['Reading', 'Browsing the shelves', 'Taking notes']},
  {id:'planner', room:'Study', f:1, c:1, prop:'calendar', wall:'#3a2f33', blanket:'#6e4448', awake:[[5.5,22]],
    look:{skin:'#d9a982', hair:'#5b2a1e', style:'bun', top:'#7b5252', pants:'#26262b'}, acts:['Going over the week', 'Tidying the calendar', 'Checking what\'s next']},
  {id:'phone', room:'Signal room', f:1, c:2, prop:'screens', wall:'#2b3038', blanket:'#34405a', awake:[[6,24],[0,1]],
    look:{skin:'#8a5a3c', hair:'#140f0d', style:'curly', top:'#34405a', pants:'#1f1f24'}, acts:['Watching the line', 'Keeping the screens warm', 'Waiting on your texts']},
  {id:'money', room:'Vault', f:2, c:0, prop:'safe', wall:'#2e3429', blanket:'#3a4834', awake:[[7,23]],
    look:{skin:'#c99a7a', hair:'#1c1410', style:'short', top:'#465446', pants:'#2a2a30'}, acts:['Going over your numbers', 'Balancing the ledger', 'Checking the vault']},
  {id:'bills', room:'Office', f:2, c:2, prop:'paper', wall:'#37322a', blanket:'#544c3c', awake:[[8,20]],
    look:{skin:'#d2a07c', hair:'#8a8580', style:'bald', top:'#454856', pants:'#26262b'}, acts:['Sorting the paperwork', 'Filing receipts', 'Checking due dates']},
  {id:'meals', room:'Kitchen', f:3, c:0, prop:'kitchen', wall:'#3e352d', blanket:'#7a6850', awake:[[7,21]],
    look:{skin:'#e6c3a3', hair:'#b89a62', style:'short', top:'#ddd4c6', pants:'#2e2c2a'}, acts:['Flipping through recipes', 'Checking the fridge', 'Tidying the kitchen']},
  {id:'family', room:'Living room', f:3, c:1, prop:'frames', wall:'#3e2f2c', blanket:'#8a4444', awake:[[8,22]],
    look:{skin:'#9c6a4a', hair:'#120d0b', style:'long', top:'#8a4444', pants:'#28232a'}, acts:['Looking through photos', 'Straightening the frames', 'Remembering the little things']},
  {id:'memory', room:'Archive', f:3, c:2, prop:'files', wall:'#34302c', blanket:'#56504a', awake:[[9,19],[2,4]],
    look:{skin:'#b27b5a', hair:'#d8d2c8', style:'long', top:'#56504a', pants:'#2a2724'}, acts:['Filing what he\'s learned', 'Tidying old notes', 'Cross-checking the files']},
  {id:'coach', room:'Gym', f:4, c:0, prop:'gym', wall:'#2c3033', blanket:'#444a50', awake:[[5,21]],
    look:{skin:'#7a4e34', hair:'#120d0b', style:'short', top:'#9a3428', pants:'#1c1c20'}, acts:['Going over his lessons', 'Warming up', 'Studying how he did today']},
  {id:'pursuit', room:'Studio', f:4, c:1, prop:'studio', wall:'#302b2f', blanket:'#2e2e33', awake:[[10,24],[0,2]],
    look:{skin:'#c99070', hair:'#0e0b0a', style:'curly', top:'#1c1c20', pants:'#1c1c20'}, acts:['Sketching new pieces', 'Sorting the rack', 'Looking at the mood board']},
  {id:'appts', room:'Front desk', f:4, c:2, prop:'front', wall:'#33313a', blanket:'#544e62', awake:[[7,19]],
    look:{skin:'#e2b48e', hair:'#3a2418', style:'bun', top:'#544e62', pants:'#26262b'}, acts:['Checking the appointment book', 'Straightening the desk', 'Watching the clock']},
  {id:'packages', room:'Mailroom', f:5, c:0, prop:'boxes', wall:'#3a3229', blanket:'#66543e', awake:[[6,20]],
    look:{skin:'#b8845e', hair:'#2a1c14', style:'cap', top:'#7a5e3e', pants:'#2e2c2a'}, acts:['Sorting the shelves', 'Checking tracking numbers', 'Stacking boxes']},
  {id:'supplies', room:'Pantry', f:5, c:1, prop:'pantry', wall:'#36322b', blanket:'#566450', awake:[[7,21]],
    look:{skin:'#d8a882', hair:'#6a4026', style:'short', top:'#566450', pants:'#2a2a2e'}, acts:['Counting what\'s left', 'Restocking the shelves', 'Checking the diapers']},
];
const NAME = {builder:'Builder', research:'Research', planner:'Planner', phone:'Phone', money:'Money', bills:'Bills', meals:'Meals', family:'Family', memory:'Memory', coach:'Coach', pursuit:'Pursuit', appts:'Appointments', packages:'Packages', supplies:'Supplies'};
AGS.forEach(a => { a.name = NAME[a.id]; a.x0 = a.wide ? 0 : a.c * RW; });
const JR = {f: 2, c: 1}, GR = {f: 5, c: 2};
// where things are inside a room (x from the room's left edge, z from the back wall at -2 to the open front at +2)
const SPOT = {bed: [0.65, -0.95], edge: [1.25, -0.6], desk: [3.2, -0.95], prop: [2.05, -1.05]};
const spotOf = (a, what) => { if (a.wide) { const m = {bed: [0.65, -0.95], edge: [1.25, -0.6], desk: [3.2, -0.95], prop: [7.4, -1.0]}; return [a.x0 + m[what][0], m[what][1]]; } const s = SPOT[what]; return [a.x0 + s[0], s[1]]; };
const realNow = () => { const d = new Date(); return d.getHours() * 60 + d.getMinutes() + d.getSeconds() / 60; };
let simMin = realNow(), fast = false, focusI = 4, zoomed = false, open = false, coreFlash = 0, REAL = {}, T = 0;
const hourNow = () => (simMin / 60) % 24;
const scheduleAwake = (a, h = hourNow()) => a.awake.some(([s, e]) => h >= s && h < e);
const isOff = a => REAL[a.id] && REAL[a.id].on === false;
const awakeNow = a => !isOff(a) && (!!a.job || scheduleAwake(a));
const asleep = a => a.state === 'sleep' || (a.state === 'lieDown' && a.until < .8);
const clockTxt = () => { const m = Math.floor(simMin) % 1440, h = Math.floor(m / 60), mm = m % 60; return `${(h % 12) || 12}:${String(mm).padStart(2, '0')} ${h < 12 ? 'AM' : 'PM'}`; };
const fmtH = h => `${(Math.floor(h) % 12) || 12}${h % 1 ? ':30' : ''} ${h % 24 < 12 ? 'AM' : 'PM'}`;
function wakesAt(a) { const h = hourNow(); let best = 99, at = 0; a.awake.forEach(([s]) => { const d = (s - h + 24) % 24; if (d < best) { best = d; at = s; } }); return fmtH(at); }
const lastRun = id => { const r = REAL[id] && REAL[id].runs; return r && r.length ? r[r.length - 1] : null; };
const short = (s, n) => { s = String(s || '').replace(/\s+/g, ' ').trim(); return s.length > n ? s.slice(0, n - 1) + '…' : s; };
const STATE_TXT = {sleep: 'ASLEEP', getup: 'GETTING UP', toDesk: 'ON THE MOVE', work: 'AT THE DESK', toProp: 'ON THE MOVE', prop: 'LOOKING SOMETHING UP', deliver: 'ON ITS WAY TO JARVIS', give: 'WITH JARVIS', toBed: 'HEADING TO BED', lieDown: 'GOING TO SLEEP'};
function showFocus() {
  const a = AGS[focusI], lr = lastRun(a.id); if (!$('f-name')) return;
  $('f-name').textContent = a.name + ' · ' + a.room;
  $('f-state').textContent = isOff(a) ? 'SWITCHED OFF' : a.job ? 'ON YOUR JOB NOW' : STATE_TXT[a.state] || 'AWAKE';
  $('f-act').textContent = isOff(a) ? 'Switched off in his settings.' : a.job ? short(a.job, 110) : asleep(a) ? `Asleep. Wakes around ${wakesAt(a)}, or sooner if you need it.` :
    a.state === 'deliver' || a.state === 'give' ? 'Bringing Jarvis what it found' : a.state === 'toBed' || a.state === 'lieDown' ? 'Done for the day' : a.state === 'getup' ? 'Just woke up' : a.acts[a.act] + '…';
  $('f-done').textContent = lr ? 'Last job: ' + short(lr.result || lr.task, 120) : 'No jobs yet. It learns as it works.';
  const f = document.querySelector('#house .focus'); if (f) { f.classList.toggle('asleep', asleep(a) || isOff(a)); f.classList.toggle('live', !!a.job); }
}

// ---------- getting around: along the front of the rooms, through the doorways, and up and down the stairs ----------
const FL_X = STX + 1.75, WALK_X = STX + 0.5;
const zTop = k => k % 2 === 0 ? -1.5 : 1.5, zBot = k => -zTop(k);   // flight k joins level k (top) and level k+1 (bottom)
function route(a, f2, x2, z2) {
  const pts = []; let f = a.fl; const P = (x, z, fl, climb) => pts.push({x, z, f: fl, climb: !!climb});
  if (f !== f2) {
    P(a.x, LANE, f); P(WALK_X, LANE, f);
    while (f !== f2) { const down = f2 > f, k = down ? f : f - 1, zs = down ? zTop(k) : zBot(k), ze = down ? zBot(k) : zTop(k), nf = down ? f + 1 : f - 1;
      P(WALK_X, zs, f); P(FL_X, zs, f); P(FL_X, ze, nf, true); P(WALK_X, ze, nf); f = nf; }
    P(WALK_X, LANE, f); P(x2, LANE, f);
  } else if (Math.abs(a.x - x2) > .3) { P(a.x, LANE, f); P(x2, LANE, f); }
  P(x2, z2, f2); return pts;
}
function go(a, state, f2, where) { a.state = state; const [x, z] = Array.isArray(where) ? where : spotOf(a, where); a.path = route(a, f2, x, z); a.seg = null; if (AGS[focusI] === a) showFocus(); }
const deliverAt = a => { const jx = JR.c * RW + 2; return [a.f === 2 && a.c === 0 ? jx - 0.9 : jx + 0.9, 0.3]; };
function step(a, dt) {
  const awake = awakeNow(a);
  if (a.path.length) {
    const p = a.path[0], ty = floorY(p.f);
    if (!a.seg) a.seg = {x: a.x, y: a.y, z: a.z, len: Math.hypot(p.x - a.x, ty - a.y, p.z - a.z) || .001, d: 0};
    const sp = (p.climb ? .9 : 1.25) * (a.job ? 1.5 : 1); a.seg.d = Math.min(a.seg.len, a.seg.d + sp * dt); const k = a.seg.d / a.seg.len;
    const dx = p.x - a.seg.x, dz = p.z - a.seg.z; if (Math.abs(dx) + Math.abs(dz) > .01) a.face = Math.atan2(dx, dz);
    a.x = a.seg.x + dx * k; a.y = a.seg.y + (ty - a.seg.y) * k; a.z = a.seg.z + dz * k; a.walkP += sp * dt * 5.2;
    if (k >= 1) { a.fl = p.f; a.path.shift(); a.seg = null; if (!a.path.length) arrive(a); }
    return;
  }
  a.until -= dt;
  if (a.state === 'sleep') { if (awake) { a.state = 'getup'; a.until = a.job ? 1 : 2.4; const e = spotOf(a, 'edge'); a.x = e[0]; a.z = e[1]; a.face = PI / 2; if (AGS[focusI] === a) showFocus(); } return; }
  if (a.state === 'getup' && a.until <= 0) return go(a, 'toDesk', a.f, 'desk');
  if (a.state === 'lieDown' && a.until <= 0) { a.state = 'sleep'; if (AGS[focusI] === a) showFocus(); return; }
  if (a.state === 'give') { a.orb = Math.max(0, 1 - (1.4 - a.until) / 0.7); if (a.until <= 0) { a.orb = 0; go(a, 'toDesk', a.f, 'desk'); } return; }
  if (a.job) { if (a.state !== 'work' && a.state !== 'toDesk') go(a, 'toDesk', a.f, 'desk'); return; }
  if (a.deliverNext && (a.state === 'work' || a.state === 'prop')) { a.deliverNext = false; a.orb = 1; return go(a, 'deliver', JR.f, deliverAt(a)); }
  if ((a.state === 'work' || a.state === 'prop') && a.until <= 0) {
    if (!awake) return go(a, 'toBed', a.f, 'edge');
    if (a.state === 'work' && Math.random() < .4) go(a, 'toProp', a.f, 'prop');
    else if (a.state === 'prop') go(a, 'toDesk', a.f, 'desk');
    else { a.act = (a.act + 1) % a.acts.length; a.until = 6 + Math.random() * 7; if (AGS[focusI] === a) showFocus(); }
  }
}
function arrive(a) {
  if (a.state === 'toDesk') { a.state = 'work'; a.face = PI; a.until = 6 + Math.random() * 8; }
  else if (a.state === 'toProp') { a.state = 'prop'; a.face = PI; a.until = 3.5 + Math.random() * 2.5; }
  else if (a.state === 'deliver') { a.state = 'give'; a.face = a.x < JR.c * RW + 2 ? PI / 2 : -PI / 2; a.until = 1.4; setTimeout(() => { coreFlash = 1; try { window.brainPulse && window.brainPulse(a.id, 3); } catch (_) {} }, 700); }
  else if (a.state === 'toBed') { a.state = 'lieDown'; a.face = PI / 2; a.until = 1.6; }
  if (AGS[focusI] === a) showFocus();
}
AGS.forEach((a, i) => { a.ph = i * 1.7; a.act = i % 3; a.path = []; a.walkP = 0; a.orb = 0; a.job = null; a.fl = a.f; a.y = floorY(a.f); a.face = PI;
  if (scheduleAwake(a)) { a.state = 'work'; [a.x, a.z] = spotOf(a, 'desk'); a.until = 2 + Math.random() * 8; } else { a.state = 'sleep'; [a.x, a.z] = spotOf(a, 'bed'); } });
window.houseAgents = list => {
  for (let i = 0; i < list.length; i++) REAL[list[i].id] = list[i];
  AGS.forEach(a => { const r = REAL[a.id]; if (!r) return; const working = r.active && r.task;
    if (working && !a.job) { a.job = r.task; if (a.state === 'sleep' || a.state === 'lieDown') { a.state = 'getup'; a.until = .8; const e = spotOf(a, 'edge'); a.x = e[0]; a.z = e[1]; a.face = PI / 2; } }
    else if (working) a.job = r.task;
    else if (!working && a.job) { a.job = null; a.deliverNext = true; a.until = 0; } });
  if (open) showFocus();
};

// ================= the 3D world =================
const canvas = $('room'), H$ = $('house');
let R = null, scene, cam, built = false;
const mats = {}, geos = {};
const lam = (color, o = {}) => { const k = color + JSON.stringify(o); return mats[k] || (mats[k] = new T3.MeshLambertMaterial(Object.assign({color}, o))); };
const basic = (color, o = {}) => { const k = 'b' + color + JSON.stringify(o); return mats[k] || (mats[k] = new T3.MeshBasicMaterial(Object.assign({color}, o))); };
const boxG = (w, h, d) => { const k = `b${w},${h},${d}`; return geos[k] || (geos[k] = new T3.BoxGeometry(w, h, d)); };
const cylG = (rt, rb, h, s = 12) => { const k = `c${rt},${rb},${h},${s}`; return geos[k] || (geos[k] = new T3.CylinderGeometry(rt, rb, h, s)); };
const sphG = (r, w = 14, h = 10) => { const k = `s${r},${w},${h}`; return geos[k] || (geos[k] = new T3.SphereGeometry(r, w, h)); };
function box(p, w, h, d, m, x, y, z, ry = 0) { const o = new T3.Mesh(boxG(w, h, d), typeof m === 'string' ? lam(m) : m); o.position.set(x, y, z); o.rotation.y = ry; p.add(o); return o; }
function cyl(p, rt, rb, h, m, x, y, z, s) { const o = new T3.Mesh(cylG(rt, rb, h, s), typeof m === 'string' ? lam(m) : m); o.position.set(x, y, z); p.add(o); return o; }
function sph(p, r, m, x, y, z) { const o = new T3.Mesh(sphG(r), typeof m === 'string' ? lam(m) : m); o.position.set(x, y, z); p.add(o); return o; }
function plane(p, w, h, m, x, y, z, rx = 0, ry = 0, seg = 1) { const o = new T3.Mesh(new T3.PlaneGeometry(w, h, seg, seg), m); o.position.set(x, y, z); o.rotation.set(rx, ry, 0); p.add(o); return o; }
function canvasTex(w, h, draw) { const c = document.createElement('canvas'); c.width = w; c.height = h; const g = c.getContext('2d'); draw(g, w, h); const t = new T3.CanvasTexture(c); t.anisotropy = 4; return t; }
const glowTex = canvasTex(64, 64, (g, w) => { const r = g.createRadialGradient(32, 32, 0, 32, 32, 32); r.addColorStop(0, 'rgba(255,255,255,1)'); r.addColorStop(.3, 'rgba(255,255,255,.35)'); r.addColorStop(1, 'rgba(255,255,255,0)'); g.fillStyle = r; g.fillRect(0, 0, w, w); });
function glow(p, color, size, x, y, z, op = .8) { const s = new T3.Sprite(new T3.SpriteMaterial({map: glowTex, color, transparent: true, opacity: op, blending: T3.AdditiveBlending, depthWrite: false})); s.scale.set(size, size, 1); s.position.set(x, y, z); p.add(s); return s; }
const woodTex = canvasTex(256, 256, (g, w) => { for (let i = 0; i < 8; i++) { const l = 52 + (i * 37) % 22; g.fillStyle = `hsl(24,32%,${l / 2.4}%)`; g.fillRect(0, i * 32, w, 32); g.fillStyle = 'rgba(0,0,0,.35)'; g.fillRect(0, i * 32, w, 1.5); g.fillRect((i * 97) % w, i * 32, 1.5, 32);
  for (let k = 0; k < 14; k++) { g.fillStyle = `rgba(0,0,0,${.04 + (k % 3) * .02})`; g.fillRect(0, i * 32 + 3 + k * 2, w, .7); } } });
woodTex.wrapS = woodTex.wrapT = T3.RepeatWrapping; woodTex.repeat.set(2, 2);
const wallTex = canvasTex(128, 128, (g, w) => { g.fillStyle = '#fff'; g.fillRect(0, 0, w, w); for (let x = 0; x < w; x += 16) { g.fillStyle = 'rgba(0,0,0,.05)'; g.fillRect(x, 0, 2, w); } });
wallTex.wrapS = wallTex.wrapT = T3.RepeatWrapping; wallTex.repeat.set(4, 3);
const brickTex = canvasTex(128, 128, (g, w) => { g.fillStyle = '#4a2c24'; g.fillRect(0, 0, w, w); for (let r = 0; r < 8; r++) for (let c = 0; c < 4; c++) { g.fillStyle = `hsl(12,${30 + (r * 7 + c * 13) % 15}%,${20 + (r * 5 + c * 11) % 8}%)`; g.fillRect(c * 32 + (r % 2) * 16 + 1, r * 16 + 1, 30, 14); } });
brickTex.wrapS = brickTex.wrapT = T3.RepeatWrapping;
const skyMat = new T3.MeshBasicMaterial({color: '#223'});
const shared = {screens: []};

// ---------- a person ----------
function person(a) {
  const L = a.look, root = new T3.Group(), hips = new T3.Group(); root.add(hips); hips.position.y = .92;
  const top = lam(L.top), pants = lam(L.pants), skin = lam(L.skin), hair = lam(L.hair), shoe = lam('#1a1616');
  const torso = new T3.Mesh(cylG(.17, .145, .56, 12), top); torso.position.y = .29; hips.add(torso);
  const pelvis = new T3.Mesh(cylG(.15, .15, .14, 12), pants); pelvis.position.y = .02; hips.add(pelvis);
  const neck = cyl(hips, .05, .055, .1, skin, 0, .6, 0, 8);
  const head = new T3.Group(); head.position.y = .74; hips.add(head);
  const skull = new T3.Mesh(sphG(.115, 16, 12), skin); skull.scale.set(.95, 1.05, 1); head.add(skull);
  const nose = new T3.Mesh(boxG(.03, .05, .04), skin); nose.position.set(0, -.01, .115); head.add(nose);
  [-1, 1].forEach(s => { const e = new T3.Mesh(sphG(.014, 6, 6), lam('#1a1010')); e.position.set(.04 * s, .02, .105); head.add(e); const ear = new T3.Mesh(sphG(.03, 6, 6), skin); ear.position.set(.112 * s, 0, 0); ear.scale.set(.5, 1, .8); head.add(ear); });
  if (L.style !== 'bald') { const h = new T3.Mesh(new T3.SphereGeometry(.125, 16, 10, 0, PI * 2, 0, PI * .55), hair); h.position.set(0, .015, -.01); head.add(h);
    const back = new T3.Mesh(sphG(.12, 12, 8), hair); back.position.set(0, -.01, -.03); back.scale.set(1, .9, .85); head.add(back); }
  else { const h = new T3.Mesh(new T3.SphereGeometry(.118, 14, 8, 0, PI * 2, PI * .45, PI * .25), hair); head.add(h); }
  if (L.style === 'long') { const h = new T3.Mesh(boxG(.24, .28, .1), hair); h.position.set(0, -.15, -.07); head.add(h); }
  if (L.style === 'bun') { const b = new T3.Mesh(sphG(.06, 10, 8), hair); b.position.set(0, .1, -.1); head.add(b); }
  if (L.style === 'curly') for (let k = 0; k < 9; k++) { const b = new T3.Mesh(sphG(.045, 8, 6), hair); b.position.set(Math.cos(k * .7) * .08, .08 + (k % 3) * .015, Math.sin(k * .7) * .08 - .02); head.add(b); }
  if (L.style === 'cap') { const c = new T3.Mesh(new T3.SphereGeometry(.13, 14, 8, 0, PI * 2, 0, PI * .5), lam('#5a3a24')); c.position.y = .02; head.add(c); const brim = new T3.Mesh(boxG(.2, .015, .12), lam('#5a3a24')); brim.position.set(0, .03, .14); head.add(brim); }
  if (L.glasses) { [-1, 1].forEach(s => { const g = new T3.Mesh(new T3.TorusGeometry(.03, .006, 6, 12), lam('#1a1414')); g.position.set(.045 * s, .02, .115); head.add(g); }); }
  const limb = (parent, x, y, r1, r2, len, m) => { const piv = new T3.Group(); piv.position.set(x, y, 0); parent.add(piv); const seg = new T3.Mesh(cylG(r1, r2, len, 10), m); seg.position.y = -len / 2; piv.add(seg); const end = new T3.Group(); end.position.y = -len; piv.add(end); return [piv, end]; };
  const legs = [-1, 1].map(s => { const [thigh, knee] = limb(hips, .085 * s, 0, .075, .062, .44, pants); const [shin, ankle] = limb(knee, 0, 0, .06, .048, .44, pants);
    const f = new T3.Mesh(boxG(.1, .07, .24), shoe); f.position.set(0, -.02, .05); ankle.add(f); return {thigh, shin}; });
  const arms = [-1, 1].map(s => { const [up, elbow] = limb(hips, .215 * s, .52, .055, .047, .3, top); const [fore, wrist] = limb(elbow, 0, 0, .045, .038, .27, skin);
    const hand = new T3.Mesh(sphG(.045, 8, 6), skin); hand.position.y = -.03; wrist.add(hand); return {up, fore}; });
  const shadow = new T3.Mesh(new T3.CircleGeometry(.32, 16), new T3.MeshBasicMaterial({color: 0x000000, transparent: true, opacity: .35, depthWrite: false})); shadow.rotation.x = -PI / 2; shadow.position.y = .012; root.add(shadow);
  const orb = new T3.Group(); const os = new T3.Mesh(sphG(.07), basic('#fff0e8')); orb.add(os); orb.add(glow(orb, '#ff9a78', .9, 0, 0, 0, 1)); orb.position.set(0, 1.15, .42); orb.visible = false; root.add(orb);
  root.userData = {hips, head, legs, arms, shadow, orb, torso};
  return root;
}
function posePerson(a, t) {
  const m = a.mesh, u = m.userData, P = u; const moving = a.path.length > 0;
  const set = (l0, k0, l1, k1, a0, e0, a1, e1, hy = .92, lean = 0) => { u.legs[0].thigh.rotation.x = l0; u.legs[0].shin.rotation.x = k0; u.legs[1].thigh.rotation.x = l1; u.legs[1].shin.rotation.x = k1;
    u.arms[0].up.rotation.x = a0; u.arms[0].fore.rotation.x = e0; u.arms[1].up.rotation.x = a1; u.arms[1].fore.rotation.x = e1; u.hips.position.y = hy; u.hips.rotation.x = lean; };
  m.rotation.set(0, a.face, 0); m.position.set(a.x, a.y, a.z); u.arms.forEach(r => r.up.rotation.z = 0); u.shadow.visible = true; u.head.rotation.set(0, 0, 0);
  u.orb.visible = a.orb > 0 && (a.state === 'deliver' || a.state === 'give');
  if (asleep(a)) { m.visible = true; m.rotation.set(-PI / 2, 0, 0); const [bx, bz] = spotOf(a, 'bed'); m.position.set(bx, a.y + .6, bz + .95); set(0, 0, 0, 0, 0, 0, 0, 0); u.arms[0].up.rotation.z = .12; u.arms[1].up.rotation.z = -.12; u.shadow.visible = false; u.head.rotation.y = .3; return; }
  if (moving) { const p = a.walkP, sw = .55; set(-Math.sin(p) * sw, Math.max(0, Math.sin(p + 1.4)) * .7, Math.sin(p) * sw, Math.max(0, Math.sin(p + PI + 1.4)) * .7, Math.sin(p) * .45, -.25, -Math.sin(p) * .45, -.25, .92 + Math.abs(Math.cos(p)) * .025, .04);
    if (a.state === 'deliver') { u.arms[0].up.rotation.x = -.9; u.arms[1].up.rotation.x = -.9; u.arms[0].fore.rotation.x = -.5; u.arms[1].fore.rotation.x = -.5; } return; }
  if (a.state === 'work') { const ty = Math.sin(t * 14 + a.ph) * .06; set(-PI / 2, PI / 2, -PI / 2, PI / 2, -.55 + ty, -1.05, -.55 - ty, -1.05, .5, .05); u.head.rotation.x = .12 + Math.sin(t * .7 + a.ph) * .04; u.shadow.visible = false; return; }
  if (a.state === 'getup' || a.state === 'lieDown') { set(-PI / 2, PI / 2, -PI / 2, PI / 2, -.2, -.6, -.2, -.6, .52, -.08); u.shadow.visible = false; return; }
  if (a.state === 'prop') { const r = Math.sin(t * 1.6 + a.ph); set(0, 0, 0, 0, -2.4 - r * .25, -.4, -.25, -.3); u.head.rotation.x = -.25; return; }
  if (a.state === 'give') { set(0, 0, 0, 0, -1.1, -.4, -1.1, -.4); return; }
  set(0, 0, 0, 0, .05, -.1, .05, -.1);
}

// ---------- one room ----------
function screenTex(a) { const c = document.createElement('canvas'); c.width = 128; c.height = 80; const t = new T3.CanvasTexture(c); shared.screens.push({a, c, g: c.getContext('2d'), t, last: -1}); return t; }
function drawScreen(s, t) {
  const g = s.g, a = s.a, on = !asleep(a), working = a.state === 'work' && !a.path.length;
  const key = !on ? 'off' : working ? Math.floor(t * 5) : 'idle'; if (key === s.last) return; s.last = key;
  if (!on) { g.fillStyle = '#060506'; g.fillRect(0, 0, 128, 80); s.t.needsUpdate = true; return; }
  g.fillStyle = a.job ? '#fbe6dc' : '#f2ddd2'; g.fillRect(0, 0, 128, 80); g.fillStyle = '#c9665c'; g.fillRect(0, 0, 128, 9);
  if (!working) { g.fillStyle = 'rgba(80,30,26,.35)'; g.fillRect(40, 30, 48, 20); s.t.needsUpdate = true; return; }
  const n = Math.floor(t * 5);
  for (let k = 0; k < 9; k++) { const w = 20 + ((k * 31 + n * 7) % 80); g.fillStyle = k === (n % 9) ? 'rgba(201,102,92,.9)' : 'rgba(80,30,26,.55)'; g.fillRect(8, 14 + k * 7, Math.min(w, 112), 3); }
  if (a.job) { g.fillStyle = '#8a2c24'; g.font = 'bold 8px sans-serif'; g.fillText('ON YOUR JOB', 6, 7); }
  s.t.needsUpdate = true;
}
const rooms = [];
function buildRoom(a) {
  const G = new T3.Group(), y0 = floorY(a.f), w = a.wide ? RW * 3 : RW, h = a.wide ? ATTIC_H : RH; G.position.set(a.x0, y0, 0); scene.add(G);
  const wall = new T3.MeshLambertMaterial({color: a.wall, map: wallTex});
  plane(G, w, RD, new T3.MeshLambertMaterial({color: '#b08a6a', map: woodTex}), w / 2, .001, 0, -PI / 2, 0, 10);
  plane(G, w, h, wall, w / 2, h / 2, -RD / 2, 0, 0, 10);
  if (a.c === 0 || a.wide) plane(G, RD, h, wall, .001, h / 2, 0, 0, PI / 2, 8);
  box(G, w, .1, .03, '#1c1412', w / 2, .05, -RD / 2 + .02);
  // window and curtains
  plane(G, 1.15, 1.05, skyMat, 1.15, 1.75, -RD / 2 + .015);
  const fr = lam('#1a1210'); [[1.15, 2.3, 1.25, .07], [1.15, 1.2, 1.25, .07], [.55, 1.75, .07, 1.15], [1.75, 1.75, .07, 1.15], [1.15, 1.75, .04, 1.05], [1.15, 1.75, 1.05, .04]].forEach(([x, y, ww, hh]) => box(G, ww, hh, .06, fr, x, y, -RD / 2 + .04));
  box(G, 1.4, .05, .18, fr, 1.15, 1.17, -RD / 2 + .1);
  [.38, 1.92].forEach(x => box(G, .28, 1.5, .05, lam(a.blanket), x, 1.6, -RD / 2 + .12));
  box(G, 1.8, .03, .03, '#2a1c16', 1.15, 2.4, -RD / 2 + .14);
  // bed
  const wood = lam('#3a2418');
  box(G, 1.15, .32, 2.05, wood, .65, .2, -.95); box(G, 1.15, .95, .08, wood, .65, .48, -1.98); box(G, 1.15, .45, .06, wood, .65, .23, .07);
  box(G, 1.05, .2, 1.95, lam('#e8e0d4'), .65, .45, -.95);
  box(G, .55, .12, .32, lam('#f4eee4'), .65, .61, -1.68);
  const bl = box(G, 1.09, .07, 1.25, lam(a.blanket), .65, .58, -.32); const cover = box(G, 1.1, .22, 1.45, lam(a.blanket), .65, .64, -.55); cover.visible = false;
  // bedside table and lamp
  box(G, .42, .5, .4, wood, 1.5, .25, -1.7); box(G, .38, .02, .02, '#8a7a6a', 1.5, .38, -1.49);
  cyl(G, .06, .08, .06, '#2a2420', 1.5, .53, -1.7); cyl(G, .015, .015, .28, '#2a2420', 1.5, .7, -1.7);
  const shade = cyl(G, .1, .16, .2, new T3.MeshBasicMaterial({color: '#f3cfa0'}), 1.5, .88, -1.7, 16);
  const lampGlow = glow(G, '#ffbe82', 1.4, 1.5, .88, -1.7, .7);
  const light = new T3.PointLight('#ffc28c', 0, 7.5, 1.6); light.position.set(1.6, 1.9, -.9); G.add(light);
  // rug
  plane(G, 1.7, 1.2, lam(a.blanket), 2.25, .006, -.15, -PI / 2); plane(G, 1.5, 1.0, lam('#2c2420'), 2.25, .008, -.15, -PI / 2);
  // desk, computer, chair
  const dx = 3.2;
  box(G, 1.35, .05, .62, lam('#4a3020'), dx, .75, -1.62); [[-.62, -.28], [.62, -.28], [-.62, .26], [.62, .26]].forEach(([x, z]) => box(G, .05, .73, .05, wood, dx + x, .365, -1.62 + z));
  box(G, .4, .5, .55, wood, dx + .42, .45, -1.62); box(G, .3, .02, .02, '#8a7a6a', dx + .42, .52, -1.34);
  box(G, .08, .2, .08, '#1a1a1c', dx, .88, -1.78); box(G, .26, .02, .18, '#1a1a1c', dx, .785, -1.78);
  box(G, .82, .5, .04, '#141416', dx, 1.2, -1.8);
  const scr = plane(G, .76, .44, new T3.MeshBasicMaterial({map: screenTex(a)}), dx, 1.2, -1.775);
  const scrGlow = glow(G, '#ffd2c0', 1.6, dx, 1.2, -1.6, .35);
  box(G, .46, .02, .15, '#2a2a2e', dx, .785, -1.48); box(G, .06, .03, .1, '#2a2a2e', dx + .36, .785, -1.48);
  cyl(G, .04, .035, .1, '#d8cfc4', dx - .5, .82, -1.52, 10);
  const ch = lam('#2b2b30'); box(G, .5, .07, .48, ch, dx, .47, -.9); box(G, .48, .55, .06, ch, dx, .8, -.66); cyl(G, .03, .03, .38, '#151517', dx, .25, -.9, 8);
  [0, 1, 2, 3, 4].forEach(k => { const an = k / 5 * PI * 2; box(G, .28, .03, .04, '#151517', dx + Math.cos(an) * .13, .05, -.9 + Math.sin(an) * .13, -an); });
  // a plant by the front
  cyl(G, .14, .11, .3, '#5a3a2a', w - .35, .15, 1.45, 12); [0, 1, 2, 3, 4, 5].forEach(k => { const l = box(G, .06, .5, .02, '#3d5a36', w - .35 + Math.cos(k) * .05, .5, 1.45 + Math.sin(k) * .05); l.rotation.set(Math.cos(k * 2) * .4, k, Math.sin(k * 2) * .4); });
  props(G, a, w);
  rooms.push({a, G, light, lampGlow, shade, bl, cover, scrGlow, scr, nowOn: null});
}
function props(G, a, w) {
  const wood = lam('#3a2418'), dark = lam('#1c1412'), px = 2.05, bz = -1.85;
  const P = a.prop;
  if (P === 'shelf') { box(G, 1.1, 2.3, .38, wood, px, 1.15, bz + .05); for (let s = 0; s < 5; s++) { box(G, 1.02, .03, .34, dark, px, .25 + s * .45, bz + .07);
      let x = px - .48; let k = 0; while (x < px + .45) { const bw = .04 + ((s * 7 + k * 13) % 5) * .012, bh = .26 + ((s * 3 + k * 5) % 6) * .025; box(G, bw, bh, .24, lam(['#7a3a32', '#3d4a5a', '#6a5a2e', '#4a3a5e', '#8a7a58', '#2f4f3a', '#9a8a70'][(s * 3 + k) % 7]), x + bw / 2, .27 + s * .45 + bh / 2, bz + .1); x += bw + .008; k++; } }
    box(G, .3, .2, .22, lam('#e8dfd2'), 2.55 + .4, .87, -1.5); }
  else if (P === 'calendar') { box(G, .9, .75, .02, lam('#ece4d8'), px, 2.0, bz - .12); box(G, .9, .14, .025, lam('#8a2c24'), px, 2.32, bz - .11);
    for (let k = 0; k < 28; k++) box(G, .1, .09, .01, lam(k === 10 ? '#c9665c' : '#c8bfb2'), px - .36 + (k % 7) * .12, 2.15 - Math.floor(k / 7) * .13, bz - .1);
    box(G, .9, .9, .4, wood, px, .45, bz + .05); [0, 1].forEach(k => box(G, .82, .36, .01, lam('#4a3020'), px, .25 + k * .42, bz + .26)); }
  else if (P === 'screens') { for (let k = 0; k < 6; k++) { const x = px - .45 + (k % 3) * .45, y = 1.45 + Math.floor(k / 3) * .38; box(G, .42, .32, .04, '#121214', x, y, bz - .1); plane(G, .38, .28, basic('#f0c8bc'), x, y, bz - .075); }
    glow(G, '#ffc0b0', 2.2, px, 1.65, bz + .2, .25); box(G, 1.2, .7, .5, wood, px, .35, bz + .1); }
  else if (P === 'safe') { box(G, .8, 1.05, .7, lam('#3a3c40'), px, .53, bz + .2); box(G, .66, .9, .02, lam('#4a4c52'), px, .53, bz + .56); cyl(G, .09, .09, .04, '#a8987a', px, .6, bz + .58).rotation.x = PI / 2; box(G, .03, .25, .03, '#a8987a', px + .22, .53, bz + .58);
    box(G, .9, .62, .02, lam('#ece4d8'), px, 2.0, bz - .12); const pts = [[-.38, -.2], [-.2, -.1], [0, -.14], [.15, .05], [.38, .2]]; for (let k = 0; k < pts.length - 1; k++) { const [x1, y1] = pts[k], [x2, y2] = pts[k + 1]; const l = Math.hypot(x2 - x1, y2 - y1); const s = box(G, l, .025, .01, lam('#3d6a4a'), px + (x1 + x2) / 2, 2.0 + (y1 + y2) / 2, bz - .1); s.rotation.z = Math.atan2(y2 - y1, x2 - x1); } }
  else if (P === 'paper') { box(G, 1.0, .72, .5, wood, px, .36, bz + .15); for (let s = 0; s < 3; s++) for (let k = 0; k < 4 + s * 2; k++) box(G, .24, .018, .32, lam(k % 2 ? '#ece4d8' : '#d8cfc2'), px - .3 + s * .3, .73 + k * .02, bz + .15);
    [[-.25, 1.95], [.15, 2.05], [-.05, 1.65]].forEach(([x, y]) => box(G, .22, .28, .01, lam('#ece4d8'), px + x, y, bz - .12)); sph(G, .05, '#c9665c', px + .2, 2.17, bz - .1); }
  else if (P === 'kitchen') { box(G, 1.3, .88, .6, lam('#4a3a30'), px + .05, .44, bz + .12); box(G, 1.36, .05, .64, lam('#8a8278'), px + .05, .9, bz + .12);
    [[-.25, -.1], [.15, -.1]].forEach(([x, z]) => cyl(G, .1, .1, .01, '#1a1414', px + x, .93, bz + .2 + z, 16)); cyl(G, .13, .12, .2, '#5a5450', px - .25, 1.03, bz + .1, 16);
    box(G, 1.3, .7, .35, lam('#3a2c24'), px + .05, 2.05, bz - .02); [-.3, .32].forEach(x => box(G, .6, .64, .01, lam('#4a382e'), px + .05 + x, 2.05, bz + .16));
    const steam = []; for (let k = 0; k < 4; k++) steam.push(glow(G, '#ffffff', .25, px - .25, 1.2 + k * .15, bz + .1, .12)); G.userData.steam = steam;
    G.userData.burner = glow(G, '#ff6a30', .5, px - .25, .95, bz + .1, .6); }
  else if (P === 'frames') { [[-.3, 2.05, .4, .5, '#7a5a48'], [.18, 2.15, .44, .32, '#5a4a62'], [.12, 1.72, .56, .36, '#6a5040'], [-.36, 1.62, .26, .3, '#4a5a62']].forEach(([x, y, ww, hh, c]) => { box(G, ww, hh, .03, dark, px + x, y, bz - .11); box(G, ww - .06, hh - .06, .01, lam(c), px + x, y, bz - .09); sph(G, Math.min(ww, hh) * .16, '#2a1e1a', px + x, y + .02, bz - .08); });
    box(G, 1.4, .42, .75, lam('#5a2e2e'), px + .1, .3, -.05); box(G, 1.4, .5, .18, lam('#6a3636'), px + .1, .6, .3); [-1, 1].forEach(s => box(G, .16, .55, .75, lam('#6a3636'), px + .1 + s * .7, .38, -.05));
    box(G, .7, .04, .4, wood, px + .1, .4, -.85); }
  else if (P === 'files') { for (let c = 0; c < 2; c++) { box(G, .45, 1.4, .55, lam('#46423e'), px - .25 + c * .5, .7, bz + .15); for (let k = 0; k < 4; k++) { box(G, .4, .3, .02, lam('#56524e'), px - .25 + c * .5, .22 + k * .34, bz + .43); box(G, .12, .025, .02, '#a89a84', px - .25 + c * .5, .3 + k * .34, bz + .45); } }
    box(G, .3, .2, .4, lam('#a0784e'), px, 1.5, bz + .15); }
  else if (P === 'gym') { [-.5, .5].forEach(x => box(G, .07, 1.7, .07, '#2c2c30', px + x, .85, bz + .15)); cyl(G, .02, .02, 1.6, '#9a9aa0', px, 1.3, bz + .1, 8).rotation.z = PI / 2; [-.7, .7].forEach(x => { const p = cyl(G, .22, .22, .06, '#18181c', px + x, 1.3, bz + .1, 20); p.rotation.z = PI / 2; });
    box(G, .35, .1, 1.1, lam('#2c2c30'), px, .42, -.6); box(G, .06, .4, .06, '#151517', px, .2, -.95); box(G, .06, .4, .06, '#151517', px, .2, -.25);
    box(G, .8, 1.0, .02, lam('#8a2c24'), px + .1, 2.1, bz - .12); [0, 1, 2].forEach(k => box(G, .6 - k * .14, .06, .01, lam('#efe8dc'), px + .1, 2.35 - k * .14, bz - .1)); }
  else if (P === 'studio') { box(G, 1.3, .04, .04, '#7a7a80', px, 1.75, bz + .35); [-.65, .65].forEach(x => box(G, .04, 1.75, .04, '#7a7a80', px + x, .875, bz + .35));
    ['#18181b', '#e2dacd', '#18181b', '#8a2c24', '#18181b', '#3a3a40'].forEach((c, k) => { box(G, .16, .55, .3, lam(c), px - .5 + k * .2, 1.4, bz + .35); box(G, .02, .1, .02, '#7a7a80', px - .5 + k * .2, 1.72, bz + .35); });
    box(G, 1.0, .7, .02, lam('#ece4d8'), px, 2.25, bz - .12); [[-.3, .12, '#18181b'], [0, .18, '#8a2c24'], [.3, .1, '#3a3a40'], [-.15, -.15, '#8a7a6a'], [.2, -.15, '#18181b']].forEach(([x, y, c]) => box(G, .2, .2, .01, lam(c), px + x, 2.25 + y, bz - .1));
    const m = new T3.Group(); m.position.set(px - 1.0, 0, -.2); G.add(m); cyl(m, .02, .02, 1.1, '#2a2a2e', 0, .55, 0, 8); cyl(m, .17, .13, .55, lam('#18181b'), 0, 1.35, 0, 12); }
  else if (P === 'front') { const ck = new T3.Group(); ck.position.set(px, 2.15, bz - .1); G.add(ck); const face = cyl(ck, .28, .28, .04, '#ece4d8', 0, 0, 0, 24); face.rotation.x = PI / 2; const rim = new T3.Mesh(new T3.TorusGeometry(.28, .025, 8, 24), lam('#2a1a14')); ck.add(rim);
    const hh = box(ck, .03, .16, .01, '#1a1212', 0, .08, .03), mh = box(ck, .02, .23, .01, '#1a1212', 0, .11, .035); G.userData.clock = {hh, mh};
    box(G, 1.3, .95, .55, lam('#4a3628'), px, .48, bz + .45); box(G, 1.36, .05, .6, lam('#6a5240'), px, .97, bz + .45); box(G, .3, .04, .22, lam('#ece4d8'), px - .3, 1.01, bz + .45); cyl(G, .02, .02, .25, '#8a7a6a', px + .35, 1.1, bz + .45, 8); }
  else if (P === 'boxes' || P === 'pantry') { for (let s = 0; s < 4; s++) box(G, 1.2, .04, .5, dark, px, .02 + s * .6, bz + .15); [-.6, .6].forEach(x => box(G, .05, 1.85, .5, dark, px + x, .92, bz + .15));
    for (let s = 0; s < 3; s++) { let x = px - .55; let k = 0; while (x < px + .5) { const bw = P === 'boxes' ? .22 + ((s + k) % 3) * .08 : .14, bh = P === 'boxes' ? .2 + ((s * 2 + k) % 3) * .08 : .2 + (k % 2) * .08;
      const c = P === 'boxes' ? '#a87e52' : s === 0 ? ['#dfe6ee', '#cfdae6'][k % 2] : ['#c9a46a', '#8a5a3c', '#d8cfc2', '#6a8a5a', '#b05a4a'][(k + s) % 5];
      box(G, bw, bh, .36, lam(c), x + bw / 2, .04 + s * .6 + bh / 2, bz + .15); if (P === 'boxes') box(G, .03, bh + .002, .37, lam('#8a6640'), x + bw / 2, .04 + s * .6 + bh / 2, bz + .15); x += bw + .03; k++; } } }
  if (a.wide) { // the workshop: a long bench, a pegboard of tools, a server rack he tests himself on
    const bx = 7.6; box(G, 3.2, .06, .7, lam('#5a4030'), bx, .9, bz + .2); [-1.5, 1.5].forEach(x => box(G, .08, .88, .6, wood, bx + x, .44, bz + .2));
    box(G, 2.8, 1.0, .03, lam('#3a3028'), bx, 1.75, bz - .1); [[-1, 0], [-.6, 1], [-.2, 0], [.3, 2], [.7, 1], [1.1, 0]].forEach(([x, k]) => box(G, .04, .3 + k * .05, .03, '#9a8a7a', bx + x, 1.8, bz - .07));
    box(G, .7, .4, .4, lam('#1b2a3a'), bx - .6, 1.15, bz + .2); plane(G, .6, .32, basic('#bcd4e8'), bx - .6, 1.15, bz + .41);
    box(G, .8, 2.0, .7, lam('#141416'), 10.9, 1.0, bz + .2); const leds = []; for (let k = 0; k < 10; k++) { box(G, .7, .14, .02, '#1e1e22', 10.9, .3 + k * .17, bz + .56); leds.push(box(G, .03, .03, .01, basic('#ff9a80'), 11.15, .3 + k * .17, bz + .575)); } G.userData.leds = leds; }
}
function buildJarvis() {
  const G = new T3.Group(), y0 = floorY(JR.f); G.position.set(JR.c * RW, y0, 0); scene.add(G);
  plane(G, RW, RD, lam('#1a1012'), RW / 2, .001, 0, -PI / 2, 0, 8); plane(G, RW, RH, lam('#140c0e'), RW / 2, RH / 2, -RD / 2, 0, 0, 8);
  for (let k = 0; k < 10; k++) { const c = new T3.Mesh(new T3.TorusGeometry(1.1 + k * .06, .006, 4, 48, PI), basic('#c9665c', {transparent: true, opacity: .25})); c.position.set(RW / 2, 1.5, -1.95); c.rotation.z = k * .35; G.add(c); }
  cyl(G, .9, 1.0, .18, '#1e1416', RW / 2, .09, -.4, 32); cyl(G, .6, .7, .1, '#2a1a1c', RW / 2, .23, -.4, 32);
  const core = new T3.Group(); core.position.set(RW / 2, 1.55, -.4); G.add(core);
  const pts = []; for (let i = 0; i < 700; i++) { const u = Math.random() * PI * 2, v = Math.acos(2 * Math.random() - 1), r = .55 + (Math.random() < .2 ? Math.random() * .2 : 0); pts.push(Math.sin(v) * Math.cos(u) * r, Math.cos(v) * r * .85, Math.sin(v) * Math.sin(u) * r); }
  const pg = new T3.BufferGeometry(); pg.setAttribute('position', new T3.Float32BufferAttribute(pts, 3));
  const cloud = new T3.Points(pg, new T3.PointsMaterial({color: '#f0c3ba', size: .025, transparent: true, opacity: .85, blending: T3.AdditiveBlending, depthWrite: false})); core.add(cloud);
  const inner = new T3.Mesh(sphG(.26, 24, 16), basic('#151517')); core.add(inner);
  const ring = new T3.Mesh(new T3.TorusGeometry(.27, .018, 8, 48), basic('#c9665c')); core.add(ring);
  const arc = new T3.Mesh(new T3.TorusGeometry(.27, .03, 8, 24, 1.1), basic('#ffe0d6')); core.add(arc);
  const halo = glow(core, '#c9665c', 3.4, 0, 0, 0, .7);
  const beam = new T3.Mesh(new T3.CylinderGeometry(.25, .7, 1.3, 24, 1, true), new T3.MeshBasicMaterial({color: '#c9665c', transparent: true, opacity: .12, blending: T3.AdditiveBlending, depthWrite: false, side: T3.DoubleSide})); beam.position.set(RW / 2, .9, -.4); G.add(beam);
  const light = new T3.PointLight('#e2776a', 1.2, 7, 1.5); light.position.set(RW / 2, 1.6, .2); G.add(light);
  return {core, cloud, ring, arc, halo, light};
}
function buildGarage() {
  const G = new T3.Group(), y0 = floorY(GR.f); G.position.set(GR.c * RW, y0, 0); scene.add(G);
  plane(G, RW, RD, lam('#2a2628'), RW / 2, .001, 0, -PI / 2, 0, 8); plane(G, RW, RH, lam('#232022'), RW / 2, RH / 2, -RD / 2, 0, 0, 8);
  for (let k = 0; k < 8; k++) box(G, 3.6, .3, .04, '#2c2829', RW / 2, .3 + k * .34, -1.97);
  box(G, .1, .01, 3.8, lam('#c9a46a'), RW / 2 - 1.2, .005, 0); box(G, .1, .01, 3.8, lam('#c9a46a'), RW / 2 + 1.2, .005, 0);
  // a car under a fitted cover
  const shape = new T3.Shape(); shape.moveTo(-2.1, 0); shape.quadraticCurveTo(-2.15, .55, -1.7, .62); shape.quadraticCurveTo(-1.0, 1.25, .1, 1.28); shape.quadraticCurveTo(1.15, 1.25, 1.7, .72); shape.quadraticCurveTo(2.15, .62, 2.12, 0); shape.lineTo(-2.1, 0);
  const car = new T3.Mesh(new T3.ExtrudeGeometry(shape, {depth: 1.65, bevelEnabled: true, bevelSize: .12, bevelThickness: .12, bevelSegments: 4, curveSegments: 16}), lam('#4a4246'));
  car.rotation.y = PI / 2; car.scale.set(.82, .82, .82); car.position.set(RW / 2 - .68, .12, .4); G.add(car);
  [[-1.25, .75], [1.25, .75], [-1.25, -.75], [1.25, -.75]].forEach(([z, x]) => { const w = cyl(G, .3, .3, .2, '#121012', RW / 2 + x * 1.05, .3, z * .8 - .3, 18); w.rotation.z = PI / 2; });
  const sign = canvasTex(256, 128, () => {}); const sm = plane(G, 1.3, .65, new T3.MeshBasicMaterial({map: sign}), RW / 2, 2.25, -1.93); G.userData.sign = sign;
  const light = new T3.PointLight('#ffe6d6', .6, 6, 1.8); light.position.set(RW / 2, 2.6, .5); G.add(light);
  return G;
}
function drawSign(G) {
  let pct = 0; try { pct = parseFloat((document.querySelector('#t-goal .big') || {}).textContent) || window.MACAN_PCT || 0; } catch (_) {}
  const t = G.userData.sign, c = t.image, g = c.getContext('2d'); g.fillStyle = '#0f0d0e'; g.fillRect(0, 0, 256, 128); g.strokeStyle = 'rgba(201,102,92,.6)'; g.lineWidth = 3; g.strokeRect(2, 2, 252, 124);
  g.fillStyle = '#efe9e2'; g.font = '600 20px Inter, sans-serif'; g.fillText('MACAN FUND', 18, 36); g.font = '400 54px Georgia, serif'; g.fillText(Math.round(pct) + '%', 18, 96);
  g.fillStyle = '#2a2224'; g.fillRect(130, 76, 108, 10); g.fillStyle = '#c9665c'; g.fillRect(130, 76, 108 * Math.min(1, pct / 100), 10); t.needsUpdate = true;
}
function buildShell() {
  const top = floorY(0) + ATTIC_H, W = STX + SW;
  const slab = lam('#1a1312'), trim = lam('#2a1d18');
  for (let L = 1; L < LEVELS; L++) { const y = floorY(L - 1); box(scene, STX, .22, RD + .1, slab, STX / 2, y - .11, 0); box(scene, STX, .08, .1, trim, STX / 2, y - .11, RD / 2 + .05); }
  box(scene, W + .4, .3, RD + .4, slab, W / 2, -.15, 0);
  // inner walls between rooms: they stop short of the front, which is where everyone walks through
  for (let L = 1; L < LEVELS; L++) for (let c = 1; c <= 3; c++) { const x = c * RW, y = floorY(L); box(scene, .14, RH, RD - 1.45, lam('#231816'), x, y + RH / 2, -RD / 2 + (RD - 1.45) / 2); box(scene, .16, RH, .08, trim, x, y + RH / 2, -RD / 2 + RD - 1.45); }
  const bm = new T3.MeshLambertMaterial({color: '#7a5a50', map: brickTex}); brickTex.repeat.set(2, 8);
  box(scene, .3, top, RD + .3, bm, -.15, top / 2, 0); box(scene, .3, top - ATTIC_H, RD + .3, bm, W + .15, (top - ATTIC_H) / 2, 0);
  box(scene, W + .6, top, .25, bm, W / 2, top / 2, -RD / 2 - .14);
  // the stairwell: a walkway on each landing, and flights that switch back and forth
  const st = lam('#3a2618'), rail = lam('#1e1412');
  plane(scene, SW, top - ATTIC_H, lam('#1a1416'), STX + SW / 2, (top - ATTIC_H) / 2, -RD / 2 + .01, 0, 0, 6);
  for (let L = 0; L < LEVELS; L++) { const y = floorY(L); box(scene, 1.0, .2, RD, slab, STX + .5, y - .1, 0); }
  for (let k = 0; k < LEVELS - 1; k++) { const yT = floorY(k), yB = floorY(k + 1), zt = zTop(k), zb = zBot(k), n = 14;
    for (let i = 0; i < n; i++) { const u = (i + .5) / n, y = yB + (yT - yB) * u, z = zb + (zt - zb) * u; box(scene, 1.2, .08, Math.abs(zt - zb) / n + .02, st, FL_X, y - .04, z); box(scene, 1.2, (yT - yB) / n, .03, lam('#2a1a12'), FL_X, y - (yT - yB) / n / 2, z + Math.sign(zb - zt) * (Math.abs(zt - zb) / n / 2)); }
    const len = Math.hypot(yT - yB, zt - zb), r = box(scene, .04, .04, len, rail, FL_X + .62, (yT + yB) / 2 + .9, (zt + zb) / 2); r.rotation.x = Math.atan2(yT - yB, zt - zb) * (zt > zb ? -1 : 1) * -1; r.rotation.x = -Math.atan2(yT - yB, zt - zb); }
  // the roof over the attic
  const ry = floorY(0), rw = W + 1.2, ridge = 2.9;
  [-1, 1].forEach(s => { const len = Math.hypot(rw / 2, ridge), m = box(scene, len + .2, .18, RD + .8, lam('#2e1e1a'), W / 2 + s * rw / 4, ry + ATTIC_H + ridge / 2 - .3, 0); m.rotation.z = -s * Math.atan2(ridge, rw / 2); });
  const gable = new T3.Shape(); gable.moveTo(-rw / 2, 0); gable.lineTo(rw / 2, 0); gable.lineTo(0, ridge); gable.lineTo(-rw / 2, 0);
  const gm = new T3.Mesh(new T3.ShapeGeometry(gable), new T3.MeshLambertMaterial({color: '#6a4a42', map: brickTex})); gm.position.set(W / 2, ry + ATTIC_H - .3, -RD / 2 - .02); scene.add(gm);
  box(scene, .5, 1.2, .5, bm, W * .72, ry + ATTIC_H + ridge * .55, -.6);
  // the ground and a city far behind
  const ground = plane(scene, 220, 140, lam('#141011'), W / 2, -.3, -40, -PI / 2, 0, 1);
  const city = new T3.Group(); scene.add(city); const win = [];
  for (let i = 0; i < 46; i++) { const w = 3 + Math.random() * 5, h = 6 + Math.random() * 26, x = -50 + i * 2.6 + Math.random() * 2, z = -36 - Math.random() * 22; box(city, w, h, 3, lam('#141218'), x, h / 2 - .3, z);
    for (let k = 0; k < 6; k++) if (Math.random() < .5) win.push(box(city, .4, .5, .05, basic('#ffcf96'), x - w / 2 + .7 + (k % 2) * (w - 1.4), 2 + Math.random() * (h - 4), z + 1.53)); }
  city.userData.win = win; return city;
}
// the sky by the hour
const SKY = [[0, '#05060c', '#0c0e1a'], [5, '#070810', '#151326'], [6.5, '#1d1828', '#6a3424'], [8, '#3a4256', '#9a7a66'], [16, '#3c4458', '#a07a62'], [18.5, '#2a1a28', '#8a4228'], [20, '#0c0c16', '#21161f'], [24, '#05060c', '#0c0e1a']];
const mix = (a, b, k) => { const pa = [1, 3, 5].map(i => parseInt(a.slice(i, i + 2), 16)), pb = [1, 3, 5].map(i => parseInt(b.slice(i, i + 2), 16)); return '#' + pa.map((v, i) => Math.round(v + (pb[i] - v) * k).toString(16).padStart(2, '0')).join(''); };
function sky(h) { for (let i = 0; i < SKY.length - 1; i++) if (h >= SKY[i][0] && h < SKY[i + 1][0]) { const k = (h - SKY[i][0]) / (SKY[i + 1][0] - SKY[i][0]); return [mix(SKY[i][1], SKY[i + 1][1], k), mix(SKY[i][2], SKY[i + 1][2], k)]; } return [SKY[0][1], SKY[0][2]]; }
const daylight = h => h < 6 ? 0 : h < 8 ? (h - 6) / 2 : h < 18 ? 1 : h < 20 ? 1 - (h - 18) / 2 : 0;
let bgTex = null, bgKey = '', hemi, sun, moon, city, jv, garageG;
const STARS = Array.from({length: 160}, () => [Math.random(), Math.random() * .65, Math.random()]);
function drawSky(h) {
  const key = (h * 20 | 0); if (key === bgKey) return; bgKey = key; const [tp, bt] = sky(h), day = daylight(h), c = bgTex.image, g = c.getContext('2d'), W = c.width, H = c.height;
  const gr = g.createLinearGradient(0, 0, 0, H); gr.addColorStop(0, tp); gr.addColorStop(1, bt); g.fillStyle = gr; g.fillRect(0, 0, W, H);
  if (day < .5) for (const s of STARS) { g.fillStyle = `rgba(255,240,225,${(.5 - day) * (.3 + .7 * s[2])})`; g.fillRect(s[0] * W, s[1] * H, 1.5, 1.5); }
  if (h > 5.5 && h < 20.5) { const k = (h - 6) / 14, x = W * (.1 + .8 * k), y = H * (.5 - .38 * Math.sin(Math.max(0, Math.min(PI, k * PI)))); const r = g.createRadialGradient(x, y, 0, x, y, 90); r.addColorStop(0, 'rgba(255,214,170,.7)'); r.addColorStop(1, 'rgba(255,190,140,0)'); g.fillStyle = r; g.fillRect(0, 0, W, H); }
  else { const x = W * .8, y = H * .12; const r = g.createRadialGradient(x, y, 0, x, y, 60); r.addColorStop(0, 'rgba(230,225,240,.35)'); r.addColorStop(1, 'rgba(230,225,240,0)'); g.fillStyle = r; g.fillRect(0, 0, W, H); g.fillStyle = '#ebe5ee'; g.beginPath(); g.arc(x, y, 14, 0, 7); g.fill(); g.fillStyle = tp; g.beginPath(); g.arc(x + 6, y - 3, 12, 0, 7); g.fill(); }
  bgTex.needsUpdate = true; skyMat.color.set(mix(tp, bt, .5));
}
const plates = [];
function plate(text, sub) {
  const c = document.createElement('canvas'); c.width = 512; c.height = 96; const t = new T3.CanvasTexture(c);
  const s = new T3.Sprite(new T3.SpriteMaterial({map: t, transparent: true, depthTest: false})); s.renderOrder = 10; scene.add(s); const p = {c, t, s, text, sub, key: ''}; plates.push(p); return p;
}
function drawPlate(p, on, foc, busy) {
  const key = `${on}${foc}${busy}${zoomed}`; if (key === p.key) return; p.key = key; const g = p.c.getContext('2d'); g.clearRect(0, 0, 512, 96);
  const label = zoomed ? `${p.text.toUpperCase()}  ·  ${p.sub}` : p.text.toUpperCase(); g.font = '600 38px Inter, system-ui, sans-serif'; const tw = Math.min(440, g.measureText(label).width);
  g.fillStyle = foc ? 'rgba(70,26,22,.92)' : 'rgba(10,8,9,.78)'; const r = 30; g.beginPath(); g.moveTo(r, 12); g.arcTo(tw + 80, 12, tw + 80, 84, r); g.arcTo(tw + 80, 84, 0, 84, r); g.arcTo(4, 84, 4, 12, r); g.arcTo(4, 12, tw + 80, 12, r); g.fill();
  g.fillStyle = busy ? '#ffd2c2' : on ? '#e2774f' : '#5a5450'; g.beginPath(); g.arc(36, 48, 10, 0, 7); g.fill();
  g.fillStyle = on ? 'rgba(239,235,228,.97)' : 'rgba(170,165,157,.85)'; g.fillText(label, 58, 61, 440); p.t.needsUpdate = true; p.w = tw + 84;
}
function build() {
  R = new T3.WebGLRenderer({canvas, antialias: true, powerPreference: 'high-performance'}); R.setPixelRatio(Math.min(2, devicePixelRatio || 1));
  scene = new T3.Scene(); bgTex = canvasTex(256, 512, () => {}); scene.background = bgTex;
  cam = new T3.PerspectiveCamera(38, 1, .1, 400);
  hemi = new T3.HemisphereLight('#bcc4dc', '#2a1c18', .5); scene.add(hemi);
  sun = new T3.DirectionalLight('#ffe2c4', .6); sun.position.set(8, 30, 30); scene.add(sun);
  moon = new T3.DirectionalLight('#8a9ac8', .2); moon.position.set(-10, 20, 25); scene.add(moon);
  scene.add(new T3.AmbientLight('#ffffff', .12));
  city = buildShell(); AGS.forEach(buildRoom); jv = buildJarvis(); garageG = buildGarage(); drawSign(garageG);
  AGS.forEach(a => { a.mesh = person(a); scene.add(a.mesh); a.plate = plate(a.name, a.room); });
  plate('Jarvis', 'The core').fixed = {x: JR.c * RW, f: JR.f}; plate('Garage', 'The Macan fund').fixed = {x: GR.c * RW, f: GR.f};
  // invisible boxes to tap rooms
  AGS.forEach((a, i) => { const w = a.wide ? RW * 3 : RW, h = a.wide ? ATTIC_H : RH, m = new T3.Mesh(boxG(w, h, RD), new T3.MeshBasicMaterial({visible: false})); m.position.set(a.x0 + w / 2, floorY(a.f) + h / 2, 0); m.userData.i = i; scene.add(m); a.hit = m; });
  built = true; resize();
}
function resize() { if (!R) return; const r = canvas.getBoundingClientRect(); if (!r.width) return; R.setSize(r.width, r.height, false); cam.aspect = r.width / r.height; cam.updateProjectionMatrix(); }
new ResizeObserver(resize).observe(canvas);
// ---------- the camera: the whole house, or one room up close ----------
const HOUSE_C = new T3.Vector3((STX + SW) / 2, (floorY(0) + ATTIC_H + 2.4) / 2, 0);
const camS = {tx: HOUSE_C.x, ty: HOUSE_C.y, tz: 0, dist: 40, yaw: -.18, pitch: .1}, camT = Object.assign({}, camS);
let userYaw = 0, userPitch = 0;
function fitDist(w, h) { const v = cam.fov * PI / 180, hz = 2 * Math.atan(Math.tan(v / 2) * cam.aspect); return Math.max(h / 2 / Math.tan(v / 2), w / 2 / Math.tan(hz / 2)) + RD / 2; }
function aimCamera(dt) {
  if (zoomed) { const a = AGS[focusI], inRoom = a.fl === a.f && a.x > a.x0 - .1 && a.x < a.x0 + (a.wide ? RW * 3 : RW) + .1, w = a.wide ? RW * 3 : RW;
    if (inRoom) { camT.tx = a.wide ? Math.max(a.x0 + 2.3, Math.min(a.x0 + w - 2.3, a.x)) : a.x0 + w / 2; camT.ty = floorY(a.f) + .95; camT.dist = fitDist(Math.min(w, 4.6), 3.6) * .86; }
    else { camT.tx = a.x; camT.ty = a.y + 1.1; camT.dist = fitDist(4.6, 3) * .8; }
    camT.tz = -.2; camT.yaw = -.22 + userYaw; camT.pitch = .16 + userPitch; }
  else { camT.tx = HOUSE_C.x + .7; camT.ty = HOUSE_C.y - 2.6; camT.tz = 0; camT.dist = fitDist(STX + SW + 3, floorY(0) + ATTIC_H + 10) * 1.0; camT.yaw = -.12 + userYaw; camT.pitch = .06 + userPitch; }
  const k = 1 - Math.pow(.03, dt); for (const key in camT) camS[key] += (camT[key] - camS[key]) * k;
  const cp = Math.cos(camS.pitch); cam.position.set(camS.tx + Math.sin(camS.yaw) * cp * camS.dist, camS.ty + Math.sin(camS.pitch) * camS.dist, camS.tz + Math.cos(camS.yaw) * cp * camS.dist); cam.lookAt(camS.tx, camS.ty, camS.tz);
}
// ---------- each frame ----------
let last = 0, lastSign = 0;
function frame(ts) {
  requestAnimationFrame(frame);
  const tt = ts / 1000, dt = Math.min(.05, tt - last); last = tt;
  if (!open || !built || document.hidden) return;
  T += dt; if (fast) simMin += dt * 6; else simMin = realNow(); coreFlash = Math.max(0, coreFlash - dt * 1.2);
  AGS.forEach(a => step(a, dt));
  if ((T * 2 | 0) !== ((T - dt) * 2 | 0)) { $('h-clock').textContent = clockTxt(); const awake = AGS.filter(a => !asleep(a)).length, busy = AGS.filter(a => a.job).length; $('h-awake').textContent = busy ? `${busy} on a job · ${awake} awake` : `${awake} awake · ${AGS.length - awake} asleep`; }
  const h = hourNow(), day = daylight(h); drawSky(h);
  hemi.intensity = .18 + .42 * day; sun.intensity = .75 * day; moon.intensity = .22 * (1 - day);
  city.userData.win.forEach((w, i) => w.visible = day < .7 && (i * 7919 % 10) < 7);
  for (const r of rooms) { const a = r.a, on = !asleep(a) && !isOff(a), lamp = on ? 1 : 0;
    r.light.intensity += ((on ? 1.25 : 0) - r.light.intensity) * Math.min(1, dt * 3); r.lampGlow.material.opacity = .7 * lamp; r.shade.material.color.set(on ? '#f3cfa0' : '#3a3430');
    r.cover.visible = asleep(a); r.bl.visible = !asleep(a); if (r.cover.visible) r.cover.scale.y = 1 + Math.sin(T * 1.4 + a.ph) * .06;
    r.scrGlow.material.opacity = on ? (a.state === 'work' ? .45 : .15) : 0;
    if (r.G.userData.steam) r.G.userData.steam.forEach((s, k) => { s.visible = on; s.position.y = 1.15 + ((T * .35 + k / 4) % 1) * .7; s.material.opacity = .14 * (1 - ((T * .35 + k / 4) % 1)); s.position.x = 2.05 - .25 + Math.sin(T * 2 + k) * .05; });
    if (r.G.userData.burner) r.G.userData.burner.visible = on;
    if (r.G.userData.leds) r.G.userData.leds.forEach((l, k) => l.visible = Math.sin(T * (2 + k * .7) + k * 3) > (on ? -.2 : .6));
    if (r.G.userData.clock) { const d = new Date(), hr = (d.getHours() % 12 + d.getMinutes() / 60) / 12 * PI * 2, mn = d.getMinutes() / 60 * PI * 2; const {hh, mh} = r.G.userData.clock;
      hh.rotation.z = -hr; hh.position.set(Math.sin(hr) * .08, Math.cos(hr) * .08, .03); mh.rotation.z = -mn; mh.position.set(Math.sin(mn) * .11, Math.cos(mn) * .11, .035); } }
  shared.screens.forEach(s => drawScreen(s, T));
  AGS.forEach(a => { a.mesh.visible = true; posePerson(a, T); if (a.state === 'give') { const k = Math.max(0, (1.4 - a.until - .7) / .7); const o = a.mesh.userData.orb; o.visible = k < 1; const jx = JR.c * RW + 2 - a.x, jy = floorY(JR.f) + 1.55 - a.y; o.position.set(0, 1.15 + .4 * k, .42 + .55 * k); } else a.mesh.userData.orb.position.set(0, 1.15, .42); });
  // Jarvis
  jv.cloud.rotation.y += dt * .35; jv.ring.rotation.x = PI / 2 + Math.sin(T * .5) * .3; jv.arc.rotation.set(PI / 2, 0, T * 1.6); const pulse = 1 + .05 * Math.sin(T * 1.6) + coreFlash * .3;
  jv.core.scale.setScalar(pulse); jv.halo.material.opacity = .55 + coreFlash * .45; jv.light.intensity = 1.1 + coreFlash * 1.6 + .2 * Math.sin(T * 1.6);
  if (T - lastSign > 5) { lastSign = T; drawSign(garageG); }
  // name plates: bigger when you're looking at the whole house
  const ps = zoomed ? .3 : 1.25;
  plates.forEach((p, i) => { const a = AGS[i], fx = p.fixed; const on = fx ? true : !asleep(a) && !isOff(a); drawPlate(p, on, !fx && i === focusI, !fx && !!a.job);
    const x0 = fx ? fx.x : a.x0, y0 = floorY(fx ? fx.f : a.f) + (a && a.wide ? ATTIC_H : RH) - .3; const w = ps * (p.w || 300) / 96;
    p.s.scale.set(ps * 512 / 96, ps, 1); p.s.position.set(x0 + .25 + ps * 512 / 96 / 2, y0 - ps / 2 + .1, RD / 2 - .1); });
  aimCamera(dt); R.render(scene, cam);
}
requestAnimationFrame(frame);
// ---------- touch: drag to look around, tap a room to go in ----------
let drag = null; const ray = new T3.Raycaster(), v2 = new T3.Vector2();
canvas.addEventListener('pointerdown', e => { drag = {x: e.clientX, y: e.clientY, moved: 0}; try { canvas.setPointerCapture(e.pointerId); } catch (_) {} });
canvas.addEventListener('pointermove', e => { if (!drag) return; const dx = e.clientX - drag.x, dy = e.clientY - drag.y; drag.moved += Math.abs(dx) + Math.abs(dy); drag.x = e.clientX; drag.y = e.clientY;
  if (drag.moved > 8) { userYaw = Math.max(-.75, Math.min(.75, userYaw - dx * .005)); userPitch = Math.max(-.12, Math.min(.5, userPitch + dy * .004)); } });
canvas.addEventListener('pointerup', e => { const d = drag; drag = null; if (!d || d.moved > 8 || !built) return; const r = canvas.getBoundingClientRect(); v2.set((e.clientX - r.left) / r.width * 2 - 1, -(e.clientY - r.top) / r.height * 2 + 1); ray.setFromCamera(v2, cam);
  const people = ray.intersectObjects(AGS.map(a => a.mesh), true); if (people.length) { let o = people[0].object; while (o.parent && !AGS.some(a => a.mesh === o)) o = o.parent; const i = AGS.findIndex(a => a.mesh === o); if (i >= 0) { focusI = i; zoomed = true; userYaw = 0; userPitch = 0; showFocus(); return; } }
  const hit = ray.intersectObjects(AGS.map(a => a.hit)); if (hit.length) { focusI = hit[0].object.userData.i; zoomed = true; userYaw = 0; userPitch = 0; showFocus(); } });
// ---------- open, close, back ----------
window.openHouse = () => { if (!built) { try { build(); } catch (e) { try { J.jsError('house 3d: ' + e.message); } catch (_) {} const pl = $('peek-line'); if (pl) pl.textContent = 'His house needs 3D graphics this phone isn\'t giving the app right now.'; return; } } open = true; window.houseOpen = true; zoomed = false; userYaw = 0; userPitch = 0; simMin = realNow(); H$.classList.add('on'); H$.setAttribute('aria-hidden', 'false'); resize(); showFocus(); };
window.closeHouse = () => { open = false; window.houseOpen = false; H$.classList.remove('on'); H$.setAttribute('aria-hidden', 'true'); };
window.houseBack = () => { if (!open) return false; if (zoomed) { zoomed = false; userYaw = 0; userPitch = 0; } else window.closeHouse(); return true; };
window.houseSet = (k, v) => { if (k === 'hour') { fast = true; simMin = v * 60; } if (k === 'focus') { focusI = v; zoomed = true; showFocus(); } if (k === 'zoom') zoomed = v; if (k === 'state') AGS.forEach(a => { if (scheduleAwake(a)) { a.state = 'work'; [a.x, a.z] = spotOf(a, 'desk'); a.face = PI; } else { a.state = 'sleep'; [a.x, a.z] = spotOf(a, 'bed'); } a.path = []; a.fl = a.f; a.y = floorY(a.f); }); };
$('h-out').onclick = () => window.houseBack();
$('h-speed').onclick = () => { fast = !fast; $('h-speed').setAttribute('aria-pressed', String(fast)); $('h-speed').textContent = fast ? 'Fast day' : 'Real time'; if (!fast) simMin = realNow(); };
// ---------- the little window into the house on the home screen ----------
const pv = $('peek');
if (pv) { const pg = pv.getContext('2d'); let k = 0;
  const draw = ts => { requestAnimationFrame(draw); if (document.hidden || open) return; const t = ts / 1000, W = pv.width, H = pv.height; pg.clearRect(0, 0, W, H);
    const x0 = 132, y0 = 58, cw = 42, ch = 26; pg.fillStyle = '#1c1213'; pg.beginPath(); pg.moveTo(x0 - 12, y0); pg.lineTo(x0 + cw * 1.5, 18); pg.lineTo(x0 + cw * 3 + 12, y0); pg.fill();
    for (let r = 0; r < 5; r++) for (let c = 0; c < 3; c++) { const a = AGS.find(q => q.f === r + 1 && q.c === c), isJ = r === 1 && c === 1, isG = r === 4 && c === 2, flick = .85 + .15 * Math.sin(t * 3 + r * 3 + c);
      pg.fillStyle = isJ ? `rgba(201,102,92,${.55 + .25 * Math.sin(t * 2)})` : isG ? 'rgba(40,36,40,.7)' : a && a.job ? `rgba(255,214,196,${.6 + .3 * Math.sin(t * 6)})` : a && !asleep(a) ? `rgba(255,196,150,${.35 * flick})` : 'rgba(40,36,50,.6)';
      pg.fillRect(x0 + c * cw + 3, y0 + r * ch + 3, cw - 6, ch - 6); } };
  requestAnimationFrame(draw);
  // keep their routines going even while the house is closed
  setInterval(() => { if (open) return; if (!fast) simMin = realNow(); AGS.forEach(a => { if (a.path.length) { a.path = []; const s = asleep(a) ? 'bed' : 'desk'; [a.x, a.z] = spotOf(a, s); a.fl = a.f; a.y = floorY(a.f); } step(a, 2); }); }, 2000);
  setInterval(() => { if (document.hidden) return; const busy = AGS.filter(a => a.job); let a;
    if (busy.length) { a = busy[k++ % busy.length]; $('peek-line').textContent = `${a.name} is on it: ${short(a.job, 60)}`; return; }
    a = AGS[k++ % AGS.length]; $('peek-line').textContent = asleep(a) ? `${a.name} is asleep` : `${a.name}: ${a.acts[a.act].charAt(0).toLowerCase() + a.acts[a.act].slice(1)}`; }, 3000); }
})();
