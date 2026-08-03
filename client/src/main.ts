// Entry point: wires net → state → camera → render, plus the toolbar.
// A tap follows the creature under the finger; with a spawn kind selected, a
// tap on open ground drops that item instead (through the token-gated command
// channel). Panning hands the camera back.

import { Camera } from './camera';
import { drawMinimap, minimapToWorld } from './minimap';
import { Net } from './net';
import type { HelloMsg, ServerMsg } from './protocol';
import { F_DEAD } from './protocol';
import { render, type WorldMeta } from './render';
import { RENDER_DELAY_MS, WorldState } from './state';

const cv = document.getElementById('cv') as HTMLCanvasElement;
const g = cv.getContext('2d')!;
const statsEl = document.getElementById('stats')!;
const pauseBtn = document.getElementById('pause') as HTMLButtonElement;
const speedSel = document.getElementById('speed') as HTMLSelectElement;
const spawnSel = document.getElementById('spawn') as HTMLSelectElement;
const toastEl = document.getElementById('toast')!;
const inspectEl = document.getElementById('inspect') as HTMLElement;
const mindEl = document.getElementById('mind') as HTMLElement;
const mm = document.getElementById('minimap') as HTMLCanvasElement;
const levelBtn = document.getElementById('level') as HTMLButtonElement;

const state = new WorldState();
const cam = new Camera(cv);
let meta: WorldMeta | null = null;
let hello: HelloMsg | null = null;
let loadedBuild: string | null = null; // server build this tab loaded against; reload if it changes
let chunkTiles = 0;
let currentLevel = 0;
let paused = false;

// Baked ground streams as map chunks, fetched lazily and cached by "z/cx_cy"
// (google-maps style). The render loop asks for whatever chunks are in view.
// Chunks live at a STABLE url but their content changes when the world is
// regenerated on a redeploy (e.g. the surface/cave level swap), and they carry
// a long CDN/browser cache — so the url is tagged with the server build id to
// bust that cache on every redeploy. Without this, a stale ground layer renders
// under live entities: they appear to walk through walls that no longer exist.
const chunkCache = new Map<string, HTMLImageElement>();
function getChunk(cx: number, cy: number): HTMLImageElement {
  const v = hello ? hello.build : '0';
  const key = `${v}/${currentLevel}/${cx}_${cy}`;
  let img = chunkCache.get(key);
  if (!img) {
    img = new Image();
    img.src = `/api/world/layers/${currentLevel}/${cx}_${cy}.png?v=${v}`;
    chunkCache.set(key, img);
  }
  return img;
}

function levelName(z: number): string {
  // The surface is the top level (highest index); the cave sits below it.
  const top = hello ? hello.levels - 1 : 1;
  return z === top ? 'surface' : z === 0 ? 'underground' : `level ${z}`;
}

// Live grass levels (one byte per tile) polled from the server, so grazing
// depletion and regrowth show as dirt over the static baked grass.
let vegGrid: Uint8Array | null = null;
let vegTimer = 0;
async function pollVeg(): Promise<void> {
  try {
    const r = await fetch(`/api/world/vegetation/${currentLevel}`);
    if (!r.ok) { vegGrid = null; return; }
    const j = await r.json();
    const bin = atob(j.data);
    const a = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    vegGrid = a;
  } catch {
    /* transient; keep the last grid */
  }
}
function startVegPolling(): void {
  clearInterval(vegTimer);
  pollVeg();
  vegTimer = window.setInterval(pollVeg, 1500);
}

// Static cover mask (one byte per tile, 1 = thicket) fetched once per level, so
// the renderer can draw a shrub canopy over the entities standing in cover.
let coverGrid: Uint8Array | null = null;
async function fetchCover(): Promise<void> {
  coverGrid = null;
  try {
    const r = await fetch(`/api/world/cover/${currentLevel}?v=${hello ? hello.build : '0'}`);
    if (!r.ok) return;
    const j = await r.json();
    const bin = atob(j.data);
    const a = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
    coverGrid = a;
  } catch {
    /* transient; leave the canopy off until the next fetch */
  }
}

function resize(): void {
  const dpr = window.devicePixelRatio || 1;
  cv.width = Math.round(cv.clientWidth * dpr);
  cv.height = Math.round(cv.clientHeight * dpr);
}
window.addEventListener('resize', resize);
resize();

const net = new Net(onMsg, s => {
  if (s !== 'open') statsEl.textContent = s === 'connecting' ? 'connecting…' : 'reconnecting…';
});

// Read-only viewers (no command token in the URL) can watch but not drive the
// world — hide every mutating control so the toolbar shows only what works.
if (net.readOnly) {
  for (const el of [pauseBtn, speedSel, spawnSel]) {
    el.style.display = 'none';
  }
}

function onMsg(m: ServerMsg, receivedAt: number): void {
  switch (m.type) {
    case 'hello': {
      // Self-heal stale tabs: if this tab first connected to a different server
      // process (a redeploy/restart happened while it was open), its bundled
      // client may not match the new server — reload to fetch the current one.
      if (loadedBuild !== null && m.build !== loadedBuild) {
        location.reload();
        return;
      }
      loadedBuild = m.build;
      hello = m;
      meta = { cols: m.cols, rows: m.rows };
      paused = m.paused;
      speedSel.value = String(m.speed);
      chunkTiles = m.chunkTiles;
      chunkCache.clear();
      currentLevel = Math.max(0, m.levels - 1); // open on the surface (top level)
      vegGrid = null;
      startVegPolling();
      fetchCover();
      // Only offer the level switch when the world actually has more than one.
      levelBtn.style.display = m.levels > 1 ? 'inline-block' : 'none';
      levelBtn.textContent = levelName(currentLevel);
      cam.fit(m.cols, m.rows);
      reflect();
      break;
    }
    case 'full':
      state.applyFull(m, receivedAt);
      break;
    case 'delta':
      state.applyDelta(m, receivedAt);
      break;
    case 'status':
      paused = m.paused;
      speedSel.value = String(m.speed);
      reflect();
      break;
    case 'error':
      toast(m.message);
      break;
    case 'ack':
      break;
  }
}
net.connect();

// ---- interaction -----------------------------------------------------------

cam.attach(tap => {
  // Prefer selecting an entity under the finger: follow it and open its
  // inspector. Both creatures and items are selectable to inspect.
  const fingerTiles = Math.max(0.35, 14 / cam.scale);
  let best: number | null = null;
  let bestD = Infinity;
  for (const [id, t] of state.tracks) {
    const e = t.curr;
    if (e.kind === 'phero') continue;
    if (Math.round(e.z) !== currentLevel) continue; // only the visible level is selectable
    const d = Math.hypot(e.x - tap.x, e.y - tap.y);
    if (d < Math.max(fingerTiles, e.size * 2.5) && d < bestD) {
      best = id;
      bestD = d;
    }
  }
  if (best !== null) {
    select(best);
    return;
  }
  const tx = Math.floor(tap.x), ty = Math.floor(tap.y);
  const inBounds = meta && tx >= 0 && ty >= 0 && tx < meta.cols && ty < meta.rows;
  // In debug mode, tapping open ground inspects that tile (fertility + food)
  // instead of spawning — so grazing and seasons are observable.
  if (debugOn && inBounds) {
    selectTile(tx, ty, currentLevel);
    return;
  }
  const kind = spawnSel.value;
  if (kind && inBounds) {
    net.send({ cmd: 'spawnItem', kind, x: tap.x, y: tap.y, z: currentLevel });
  } else {
    deselect(); // tap on empty ground clears the selection
  }
});

// ---- selection + inspect ---------------------------------------------------

let selectedId: number | null = null;
let selectedTile: { x: number; y: number; z: number } | null = null;
let detailTimer = 0;

function select(id: number): void {
  selectedId = id;
  selectedTile = null;
  const t = state.tracks.get(id);
  if (t && t.curr.kind.startsWith('npc.') && !(t.curr.flags & F_DEAD)) {
    cam.followId = id; // follow living creatures; items stay put
  }
  refreshDetail();
  clearInterval(detailTimer);
  detailTimer = window.setInterval(refreshDetail, 1000); // energy/state tick live
  reflect();
}

// Debug tile inspector: poll a tile's fertility/food so grazing is watchable.
function selectTile(x: number, y: number, z: number): void {
  selectedId = null;
  cam.followId = null;
  selectedTile = { x, y, z };
  refreshTileDetail();
  clearInterval(detailTimer);
  detailTimer = window.setInterval(refreshTileDetail, 600);
  reflect();
}

function deselect(): void {
  selectedId = null;
  selectedTile = null;
  cam.followId = null; // closing the inspector also stops following
  clearInterval(detailTimer);
  clearInterval(mindTimer);
  inspectEl.style.display = 'none';
  inspectEl.className = '';
  mindEl.style.display = 'none';
  reflect();
}

async function refreshDetail(): Promise<void> {
  if (selectedId === null) return;
  try {
    const r = await fetch(`/api/world/entity/${selectedId}`);
    if (!r.ok) { deselect(); return; }
    const d = await r.json();
    // Two contexts: a compact card when just watching, the full dump in debug.
    if (debugOn) renderInspectDebug(d); else renderInspectSimple(d);
  } catch {
    /* transient; keep the last panel */
  }
}

async function refreshTileDetail(): Promise<void> {
  if (!selectedTile) return;
  try {
    const { x, y, z } = selectedTile;
    const r = await fetch(`/api/world/tile/${z}/${x}/${y}`);
    if (!r.ok) { deselect(); return; }
    renderTileInspect(await r.json());
  } catch {
    /* transient; keep the last panel */
  }
}

// Tile inspection is a debug-only action, so it always shows the full tile dump.
function renderTileInspect(d: Record<string, any>): void {
  const cap = Number(d.foodCap) || 0;
  const food = Number(d.food) || 0;
  const pct = cap > 0 ? Math.max(0, Math.min(1, food / cap)) : 0;
  const flags = [d.walkable ? 'walkable' : '', d.water ? 'water' : '', d.open ? 'open' : '']
    .filter(Boolean).join(', ') || '—';
  const rows = [
    row('type', d.type),
    row('flags', flags),
    row('fertility', Number(d.fertility).toFixed(3)),
    bar('food', `${food.toFixed(3)} / ${cap.toFixed(3)}`, pct),
  ];
  inspectEl.className = 'dbg';
  inspectEl.innerHTML =
    `<h3>tile <span class="mono">${d.x},${d.y}</span> · L${d.z}<span class="x">✕</span></h3>` +
    `<table>${rows.join('')}</table>`;
  showInspect();
}

// Non-debug context: just the essentials — name and, for a creature, its energy.
function renderInspectSimple(d: Record<string, any>): void {
  const kind = String(d.role ?? d.kind ?? 'entity').replace('npc.', '').replace('item.', '');
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  const rows: string[] = [];
  if ('energy' in d) rows.push(bar('energy', Number(d.energy).toFixed(2), Math.max(0, Math.min(1, d.energy / 4))));
  if ('durability' in d) rows.push(row('durability', d.durability));
  inspectEl.className = '';
  inspectEl.innerHTML = header(swatch, kind, d.id) + `<table>${rows.join('')}</table>`;
  showInspect();
}

// Debug context: everything the server will tell us, grouped and scrollable.
function renderInspectDebug(d: Record<string, any>): void {
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  const name = String(d.role ?? d.kind ?? 'entity').replace('npc.', '').replace('item.', '');
  const identity = [
    row('kind', d.kind),
    d.subtype ? row('subtype', d.subtype) : '',
    d.role ? row('role', d.role) : '',
    'minded' in d ? row('minded', d.minded ? 'yes' : 'no') : '',
  ];
  const status: string[] = [];
  if (d.action) status.push(row('action', d.action));
  status.push(row('age', d.age));
  status.push(row('health', d.health));
  if ('energy' in d) status.push(bar('energy', Number(d.energy).toFixed(2), Math.max(0, Math.min(1, d.energy / 4))));
  const fl: string[] = [];
  if (d.dead) fl.push('dead');
  if (d.flying) fl.push('flying');
  if (d.carrying) fl.push('carrying');
  if (d.grabbed) fl.push('grabbed');
  if (d.attachedTo >= 0) fl.push(`attached→#${d.attachedTo}`);
  if (fl.length) status.push(row('flags', fl.join(', ')));
  if ('edible' in d) status.push(row('edible', d.edible ? 'yes' : 'no'));
  if ('durability' in d) status.push(row('durability', d.durability));
  const sections = [group('identity', identity), group('status', status)];
  const gm = d.genome;
  if (gm) {
    sections.push(group('genome', [
      row('size', gm.size), row('speed', gm.speed), row('turnRate', gm.turnRate),
      row('los', `${gm.losRange} / ${(gm.losFov * 180 / Math.PI).toFixed(0)}°`),
      row('metabolism', gm.metabolism), row('maxAge', gm.maxAge),
      row('markers', (gm.markers as number[]).map(m => m.toFixed(2)).join(', ')),
      row('predatory', gm.predatory), row('xenophobia', gm.xenophobia),
      row('gregarious', gm.gregariousness), row('boldness', gm.boldness),
      row('mateThresh', gm.mateThreshold),
      row('brain', gm.hasBrain ? `${gm.brainLen} instr` : 'none'),
    ]));
  }
  // A doorway to the second inspector: the creature's evolvable program itself.
  const mindlink = gm?.hasBrain
    ? '<div class="mindlink" role="button">🧠 inspect mind →</div>'
    : '';
  inspectEl.className = 'dbg';
  inspectEl.innerHTML = header(swatch, name, d.id) + sections.join('') + mindlink;
  showInspect();
  const ml = inspectEl.querySelector('.mindlink');
  if (ml) ml.addEventListener('click', openMind);
}

function header(swatch: number, label: string, id: unknown): string {
  return `<h3><span class="sw" style="background:#${swatch.toString(16).padStart(6, '0')}"></span>` +
    `${label} <span class="mono">#${id}</span><span class="x">✕</span></h3>`;
}
function group(title: string, rows: string[]): string {
  const body = rows.filter(Boolean).join('');
  return body ? `<div class="grp">${title}</div><table>${body}</table>` : '';
}
function bar(k: string, v: unknown, pct: number): string {
  return row(k, v) +
    `<tr><td colspan=2><div class="bar"><i style="width:${(pct * 100).toFixed(0)}%"></i></div></td></tr>`;
}
function showInspect(): void {
  inspectEl.style.display = 'block';
  inspectEl.querySelector('.x')!.addEventListener('click', deselect);
}

function row(k: string, v: unknown): string {
  return `<tr><td class="k">${k}</td><td class="v">${v}</td></tr>`;
}

// ---- mind inspector --------------------------------------------------------
// A second panel focused on the creature's evolvable LGP program: its live
// sensor/actuator vectors, the disassembled instructions with the program
// counter marked, and the registers. One panel at a time — opening the mind
// hides the entity card and stops its poll; "← back" reopens the card, "✕"
// deselects entirely. Polls faster than the card so the PC and I/O feel live.
let mindTimer = 0;

function openMind(): void {
  if (selectedId === null) return;
  clearInterval(detailTimer); // the entity card yields the poll to the mind
  inspectEl.style.display = 'none';
  refreshMind();
  clearInterval(mindTimer);
  mindTimer = window.setInterval(refreshMind, 500);
}

function closeMind(): void {
  clearInterval(mindTimer);
  mindEl.style.display = 'none';
  if (selectedId === null) return;
  refreshDetail(); // reopen the entity card and resume its slower poll
  clearInterval(detailTimer);
  detailTimer = window.setInterval(refreshDetail, 1000);
}

async function refreshMind(): Promise<void> {
  if (selectedId === null) return;
  try {
    const r = await fetch(`/api/world/mind/${selectedId}`);
    if (!r.ok) { deselect(); return; }
    renderMind(await r.json());
  } catch {
    /* transient; keep the last panel */
  }
}

function renderMind(d: Record<string, any>): void {
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  const hd =
    `<h3><span class="sw" style="background:#${swatch.toString(16).padStart(6, '0')}"></span>` +
    `mind <span class="mono">#${d.id}</span>` +
    `<span class="back" role="button">← back</span><span class="x">✕</span></h3>`;
  if (!d.hasBrain) {
    mindEl.innerHTML = hd + '<div class="grp">no brain</div>' +
      '<div class="mono">this creature is scripted, not minded.</div>';
    showMind();
    return;
  }
  const sensors = (d.sensors as any[]) ?? [];
  const actuators = (d.actuators as any[]) ?? [];
  const regs = (d.registers as number[]) ?? [];
  const disasm = (d.disasm as string[]) ?? [];
  const metaRows = [
    row('length', `${d.length} instr`),
    row('steps/tick', d.stepsPerTick),
    row('pc', d.pc),
  ];
  const prog = disasm
    .map((ln, i) => `<div class="ins${i === d.pc ? ' pc' : ''}">${esc(ln)}</div>`)
    .join('');
  mindEl.innerHTML = hd +
    group('meta', metaRows) +
    `<div class="grp">sensors (in)</div><table>${sensors.map(ioRow).join('')}</table>` +
    `<div class="grp">actuators (out)</div><table>${actuators.map(ioRow).join('')}</table>` +
    `<div class="grp">program</div><div class="prog">${prog}</div>` +
    `<div class="grp">registers</div><table>${regs.map((v, i) => row(`R${i}`, v.toFixed(3))).join('')}</table>`;
  showMind();
}

// One I/O channel: name, signed value, and a centre-anchored bar (negative
// grows left of centre, positive right) so the sign is readable at a glance.
function ioRow(c: { name: string; value: number }): string {
  const v = Number(c.value) || 0;
  const w = Math.min(50, Math.abs(v) * 50);
  const left = v >= 0 ? 50 : 50 - w;
  return `<tr><td class="k">${c.name}</td><td class="v mono">${v.toFixed(3)}</td></tr>` +
    `<tr><td colspan=2><div class="io"><i style="left:${left}%;width:${w}%"></i></div></td></tr>`;
}

function showMind(): void {
  mindEl.style.display = 'block';
  mindEl.querySelector('.back')!.addEventListener('click', closeMind);
  mindEl.querySelector('.x')!.addEventListener('click', deselect);
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// Minimap: tap to recentre the camera there (drops follow).
mm.addEventListener('click', ev => {
  if (!meta) return;
  const w = minimapToWorld(mm, meta, ev.clientX, ev.clientY);
  cam.cx = w.x;
  cam.cy = w.y;
  cam.followId = null;
  reflect();
});

pauseBtn.onclick = () => net.send({ cmd: paused ? 'resume' : 'pause' });
speedSel.onchange = () => net.send({ cmd: 'speed', value: parseFloat(speedSel.value) });
levelBtn.onclick = () => {
  if (!hello || hello.levels <= 1) return;
  currentLevel = (currentLevel + 1) % hello.levels; // cycle surface -> underground -> …
  levelBtn.textContent = levelName(currentLevel);
  vegGrid = null;
  startVegPolling(); // grass grid is per-level
  fetchCover(); // cover mask is per-level
};
function reflect(): void {
  pauseBtn.textContent = paused ? 'resume' : 'pause';
  pauseBtn.classList.toggle('on', paused);
}

// Debug mode is a CLIENT-ONLY view: it changes what's rendered and how much a
// selection reveals, never how the server behaves. Enable it by tapping the tick
// readout three times, pressing "d" (desktop), or opening with ?debug=true. When
// on: tapping a tile inspects it, and the inspect panel shows the full debug dump
// for whatever is selected.
let debugOn = new URLSearchParams(location.search).get('debug') === 'true';
function applyDebug(): void {
  // Re-render whatever is open in the new context (simple card <-> full dump).
  if (selectedId !== null) refreshDetail();
  statsEl.title = debugOn ? 'debug on — tap 3× to turn off' : 'tap 3× for debug';
}
function setDebug(on: boolean): void {
  if (debugOn === on) return;
  debugOn = on;
  if (!debugOn && selectedTile) deselect(); // tile inspection is a debug-only tool
  applyDebug();
  toast(`debug mode ${debugOn ? 'on' : 'off'}`);
}
// Triple-tap the tick readout toggles debug — deliberate, so a stray tap on the
// status line never flips it. The "d" key does the same on desktop.
let tickTaps = 0;
let tickTapTimer = 0;
statsEl.style.cursor = 'pointer';
statsEl.title = 'tap 3× for debug';
statsEl.addEventListener('click', () => {
  clearTimeout(tickTapTimer);
  if (++tickTaps >= 3) {
    tickTaps = 0;
    setDebug(!debugOn);
    return;
  }
  tickTapTimer = window.setTimeout(() => (tickTaps = 0), 600);
});
window.addEventListener('keydown', ev => {
  const tag = (ev.target as HTMLElement).tagName;
  if (ev.key !== 'd' && ev.key !== 'D') return;
  if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
  setDebug(!debugOn);
});
applyDebug(); // reflect ?debug=true on load

let toastTimer = 0;
function toast(msg: string): void {
  toastEl.textContent = msg;
  (toastEl as HTMLElement).style.opacity = '1';
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => ((toastEl as HTMLElement).style.opacity = '0'), 2500);
}

// ---- frame loop ------------------------------------------------------------

// Test/debug handle: lets an automated browser (or a curious dev console)
// read live state and camera without any UI coupling.
(window as unknown as Record<string, unknown>).__blu = { cam, state };

let lastStats = 0;
function frame(now: number): void {
  const renderTime = now - RENDER_DELAY_MS;

  // Follow: glue the camera to the tracked creature's interpolated position.
  if (cam.followId !== null) {
    const t = state.tracks.get(cam.followId);
    if (t) {
      const p = state.sample(t, renderTime);
      cam.cx = p.x;
      cam.cy = p.y;
    } else {
      cam.followId = null; // it died away or despawned
      reflect();
    }
  }

  render(g, cam, state, meta, chunkTiles, getChunk, vegGrid, coverGrid, renderTime, now, currentLevel,
    { id: selectedId, tile: selectedTile });
  if (meta) drawMinimap(mm, cam, state, meta, cv, currentLevel);

  if (net.status === 'open' && now - lastStats > 250) {
    lastStats = now;
    statsEl.textContent = `tick ${state.tick} · ${state.tracks.size} entities` +
        (hello ? ` · seed ${hello.seed}` : '');
  }
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
