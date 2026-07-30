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
const followEl = document.getElementById('follow')!;
const toastEl = document.getElementById('toast')!;
const inspectEl = document.getElementById('inspect') as HTMLElement;
const mm = document.getElementById('minimap') as HTMLCanvasElement;
const levelBtn = document.getElementById('level') as HTMLButtonElement;
const debugEl = document.getElementById('debug') as HTMLElement;

const state = new WorldState();
const cam = new Camera(cv);
let meta: WorldMeta | null = null;
let hello: HelloMsg | null = null;
let chunkTiles = 0;
let currentLevel = 0;
let paused = false;

// Baked ground streams as map chunks, fetched lazily and cached by "z/cx_cy"
// (google-maps style). The render loop asks for whatever chunks are in view.
const chunkCache = new Map<string, HTMLImageElement>();
function getChunk(cx: number, cy: number): HTMLImageElement {
  const key = `${currentLevel}/${cx}_${cy}`;
  let img = chunkCache.get(key);
  if (!img) {
    img = new Image();
    img.src = `/api/world/layers/${currentLevel}/${cx}_${cy}.png`;
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
    const r = await fetch(`/api/world/cover/${currentLevel}`);
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
// world — grey out every mutating control so it reads as unavailable.
if (net.readOnly) {
  for (const el of [pauseBtn, speedSel, spawnSel]) {
    el.disabled = true;
    el.title = 'read-only — open with a command token (#t=…) to control the world';
  }
}

function onMsg(m: ServerMsg, receivedAt: number): void {
  switch (m.type) {
    case 'hello': {
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
  clearInterval(detailTimer);
  inspectEl.style.display = 'none';
}

async function refreshDetail(): Promise<void> {
  if (selectedId === null) return;
  try {
    const r = await fetch(`/api/world/entity/${selectedId}`);
    if (!r.ok) { deselect(); return; }
    renderInspect(await r.json());
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

function renderTileInspect(d: Record<string, any>): void {
  const cap = Number(d.foodCap) || 0;
  const food = Number(d.food) || 0;
  const pct = cap > 0 ? Math.max(0, Math.min(1, food / cap)) : 0;
  const rows = [
    row('type', d.type),
    row('fertility', Number(d.fertility).toFixed(3)),
    row('food', `${food.toFixed(3)} / ${cap.toFixed(3)}`),
    `<tr><td colspan=2><div class="bar"><i style="width:${(pct * 100).toFixed(0)}%"></i></div></td></tr>`,
  ];
  inspectEl.innerHTML =
    `<h3>tile <span class="mono">${d.x},${d.y}</span> · L${d.z}<span class="x">✕</span></h3>` +
    `<table>${rows.join('')}</table>`;
  inspectEl.style.display = 'block';
  inspectEl.querySelector('.x')!.addEventListener('click', deselect);
}

function renderInspect(d: Record<string, any>): void {
  const rows: string[] = [];
  // Prefer the eco role (prey/predator) as the label; fall back to the kind.
  const kind = String(d.role ?? d.kind ?? 'entity').replace('npc.', '').replace('item.', '');
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  if (d.action) rows.push(row('doing', d.action));
  if ('energy' in d) {
    const pct = Math.max(0, Math.min(1, d.energy / 4));
    rows.push(row('energy', d.energy.toFixed(2)) +
      `<tr><td colspan=2><div class="bar"><i style="width:${(pct * 100).toFixed(0)}%"></i></div></td></tr>`);
  }
  if ('durability' in d) rows.push(row('durability', d.durability));
  if ('edible' in d) rows.push(row('edible', d.edible ? 'yes' : 'no'));
  if (d.flying) rows.push(row('locomotion', 'flying'));
  if (d.carrying) rows.push(row('state', 'carrying'));
  if (d.grabbed) rows.push(row('state', 'grabbed'));
  if (d.dead) rows.push(row('state', 'dead'));
  const gm = d.genome;
  if (gm) {
    rows.push(row('size', gm.size));
    rows.push(row('speed', gm.speed));
    rows.push(row('markers', (gm.markers as number[]).map(m => m.toFixed(2)).join(', ')));
    if (gm.predatory > 0) rows.push(row('predatory', gm.predatory));
    if (gm.gregariousness > 0) rows.push(row('gregarious', gm.gregariousness));
    if (gm.hasBrain) rows.push(row('brain', 'yes'));
  }
  inspectEl.innerHTML =
    `<h3><span class="sw" style="background:#${swatch.toString(16).padStart(6, '0')}"></span>` +
    `${kind} <span class="mono">#${d.id}</span><span class="x">✕</span></h3>` +
    `<table>${rows.join('')}</table>`;
  inspectEl.style.display = 'block';
  inspectEl.querySelector('.x')!.addEventListener('click', deselect);
}

function row(k: string, v: unknown): string {
  return `<tr><td class="k">${k}</td><td class="v">${v}</td></tr>`;
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
followEl.addEventListener('click', () => {
  cam.followId = null;
  reflect();
});

function reflect(): void {
  pauseBtn.textContent = paused ? 'resume' : 'pause';
  pauseBtn.classList.toggle('on', paused);
  if (cam.followId !== null) {
    const t = state.tracks.get(cam.followId);
    followEl.textContent = `following ${t ? t.curr.kind.replace('npc.', '') : '…'} ✕`;
    (followEl as HTMLElement).style.display = 'inline-block';
  } else {
    (followEl as HTMLElement).style.display = 'none';
  }
}

// Debug overlay: a live dump of /api/health on the page. Toggle it with the
// "d" key (desktop) or by tapping the status readout (mobile-friendly).
let debugOn = false;
let debugTimer = 0;
function toggleDebug(): void {
  debugOn = !debugOn;
  debugEl.style.display = debugOn ? 'block' : 'none';
  clearInterval(debugTimer);
  if (debugOn) {
    refreshDebug();
    debugTimer = window.setInterval(refreshDebug, 1000);
  }
}
window.addEventListener('keydown', ev => {
  const tag = (ev.target as HTMLElement).tagName;
  if (ev.key !== 'd' && ev.key !== 'D') return;
  if (tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return;
  toggleDebug();
});
statsEl.style.cursor = 'pointer';
statsEl.title = 'tap to toggle debug';
statsEl.addEventListener('click', toggleDebug);
async function refreshDebug(): Promise<void> {
  try {
    const r = await fetch('/api/health');
    const o = await r.json();
    // Show the deploy time in the viewer's local timezone, not UTC.
    if (o.deployedAt) o.deployedAt = new Date(o.deployedAt).toLocaleString();
    debugEl.textContent = JSON.stringify(o, null, 2);
  } catch {
    debugEl.textContent = '/api/health unreachable';
  }
}

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

  render(g, cam, state, meta, chunkTiles, getChunk, vegGrid, coverGrid, renderTime, now, currentLevel);
  if (meta) drawMinimap(mm, cam, state, meta, cv, currentLevel);

  if (net.status === 'open' && now - lastStats > 250) {
    lastStats = now;
    statsEl.textContent = `tick ${state.tick} · ${state.tracks.size} entities` +
        (hello ? ` · seed ${hello.seed}` : '');
  }
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
