// Entry point: wires net → state → camera → render, plus the toolbar.
// A tap follows the creature under the finger; with a spawn kind selected, a
// tap on open ground drops that item instead (through the token-gated command
// channel). Panning hands the camera back.

import { Camera } from './camera';
import { drawMinimap, minimapToWorld } from './minimap';
import { Net } from './net';
import type { HelloMsg, ServerMsg } from './protocol';
import { F_DEAD } from './protocol';
import { atlasCount } from './atlas';
import { GLRenderer } from './gl';
import { render, renderGL, type WorldMeta } from './render';
import { RENDER_DELAY_MS, WorldState } from './state';

const cv = document.getElementById('cv') as HTMLCanvasElement;
const fx = document.getElementById('fx') as HTMLCanvasElement;
// The world draws through WebGL where available — Canvas2D pays CPU per API
// call and walls out at herd scale on GPU browsers — with the vector overlay
// (rings, doors, badges) on the #fx canvas above it. Browsers without WebGL2
// fall back to the original Canvas2D path on #cv and never touch #fx.
// (alpha:false — an opaque backbuffer composites into the page without
// per-frame blending.)
// ?gl=1 forces WebGL even on a software rasteriser (how headless tests
// exercise this path); ?gl=0 forces the Canvas2D fallback. The probe runs on
// a THROWAWAY canvas: once a canvas has vended a webgl2 context it can never
// vend a 2d one, so testing on #cv would poison the fallback.
const glPref = new URLSearchParams(location.search).get('gl');
function glUsable(allowSoftware: boolean): boolean {
  try {
    const probe = document.createElement('canvas');
    const gl = probe.getContext('webgl2');
    if (!gl) return false;
    if (!allowSoftware) {
      const dbg = gl.getExtension('WEBGL_debug_renderer_info');
      const name = dbg ? String(gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL)) : '';
      if (/swiftshader|llvmpipe|software|basic render/i.test(name)) return false;
    }
    return true;
  } catch {
    return false;
  }
}
let glr: GLRenderer | null = null;
if (glPref !== '0' && glUsable(glPref === '1')) {
  try {
    glr = new GLRenderer(cv, glPref === '1');
  } catch {
    glr = null; // constructor failure: #cv may be poisoned, but this is rare
  }
}
const g = glr
  ? fx.getContext('2d')! // the overlay: transparent above the GL world
  : cv.getContext('2d', { alpha: false })!;
if (!glr) fx.style.display = 'none';
const statsEl = document.getElementById('stats')!;
const pauseBtn = document.getElementById('pause') as HTMLButtonElement;
const speedSel = document.getElementById('speed') as HTMLSelectElement;
const spawnSel = document.getElementById('spawn') as HTMLSelectElement;
const toastEl = document.getElementById('toast')!;
const inspectEl = document.getElementById('inspect') as HTMLElement;
const mindEl = document.getElementById('mind') as HTMLElement;
const mm = document.getElementById('minimap') as HTMLCanvasElement;
const levelBtn = document.getElementById('level') as HTMLButtonElement;
const injectBtn = document.getElementById('inject') as HTMLButtonElement;
const injectFile = document.getElementById('injectFile') as HTMLInputElement;

const state = new WorldState();
const cam = new Camera(cv);
let meta: WorldMeta | null = null;
let hello: HelloMsg | null = null;
let loadedBuild: string | null = null; // server build this tab loaded against; reload if it changes
let chunkTiles = 0;
let currentLevel = 0;
let paused = false;
// World population across every level: the stream itself only carries the
// entities of the level in view (the server filters per viewer), so the HUD
// count comes with the messages instead of from tracks.size.
let worldTotal = 0;

// Baked ground streams as map chunks, fetched lazily and cached by "z/cx_cy"
// (google-maps style). The render loop asks for whatever chunks are in view.
// Chunks live at a STABLE url but their content changes when the world is
// regenerated on a redeploy (e.g. the surface/cave level swap), and they carry
// a long CDN/browser cache — so the url is tagged with the server build id to
// bust that cache on every redeploy. Without this, a stale ground layer renders
// under live entities: they appear to walk through walls that no longer exist.
// Cached as CANVAS copies rather than the <img> elements themselves: blitting
// from an <img> can pay a pixel-format conversion on EVERY drawImage (brutal
// on software-rendered canvases), while a canvas source blits at memcpy speed.
// One copy per chunk at decode time buys back every frame thereafter.
const chunkCache = new Map<string, HTMLCanvasElement | null>(); // null = loading
function chunkImage(cx: number, cy: number, bare: boolean): HTMLCanvasElement | null {
  const v = hello ? hello.build : '0';
  const name = bare ? `${cx}_${cy}_bare` : `${cx}_${cy}`;
  const key = `${v}/${currentLevel}/${name}`;
  const hit = chunkCache.get(key);
  if (hit !== undefined) return hit;
  chunkCache.set(key, null); // mark in-flight so we fetch once
  const img = new Image();
  img.onload = () => {
    const copy = document.createElement('canvas');
    copy.width = img.naturalWidth;
    copy.height = img.naturalHeight;
    copy.getContext('2d')!.drawImage(img, 0, 0);
    chunkCache.set(key, copy);
  };
  // On error the entry stays null: the chunk simply never draws (same as the
  // old broken-<img> behaviour) rather than refetching every frame.
  img.src = `/api/world/layers/${currentLevel}/${name}.png?v=${v}`;
  return null;
}
function getChunk(cx: number, cy: number): HTMLCanvasElement | null {
  return chunkImage(cx, cy, false);
}
// The fully-grazed twin bake: what this chunk looks like with all vegetation
// stripped. The renderer dithers depleted tiles toward it.
function getBareChunk(cx: number, cy: number): HTMLCanvasElement | null {
  return chunkImage(cx, cy, true);
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
  // 1x CSS pixels, deliberately ignoring devicePixelRatio: a retina display
  // would otherwise quadruple every blit and fill for detail pixel art does
  // not have — the ground is 12 art-px per tile however many device pixels
  // show it. The browser upscales the canvas instead (image-rendering:
  // pixelated in the stylesheet keeps that upscale crisp), and the camera
  // derives its input scale from cv.width / cv.clientWidth, so pointer maths
  // are unaffected.
  cv.width = cv.clientWidth;
  cv.height = cv.clientHeight;
  fx.width = cv.width;
  fx.height = cv.height;
}
window.addEventListener('resize', resize);
resize();

const net = new Net(onMsg, s => {
  if (s !== 'open') statsEl.textContent = s === 'connecting' ? 'connecting…' : 'reconnecting…';
});

// Read-only viewers (no command token in the URL) can watch but not drive the
// world — hide every mutating control so the toolbar shows only what works.
if (net.readOnly) {
  for (const el of [pauseBtn, speedSel, spawnSel, injectBtn]) {
    el.style.display = 'none';
  }
}

// Genome injection (token holders only): "⤒ load genome" reads a .genome file
// exported from the mind inspector and REMEMBERS it (persisted, so it survives
// the auto-reload on redeploy), then arms the "tap: genome" spawn mode. With
// that selected, each tap on open ground drops a copy of the remembered genome
// there, through the token-gated POST /api/world/genome (the same command queue
// as spawn/reset).
let loadedGenome: string | null = null;
const GENOME_KEY = 'injectGenome';

function enableGenomeSpawn(): void {
  const opt = spawnSel.querySelector('option[value="genome"]') as HTMLOptionElement | null;
  if (opt) opt.disabled = false;
}

async function injectGenomeAt(x: number, y: number, z: number): Promise<void> {
  if (!loadedGenome) { toast('no genome loaded — tap ⤒ load genome'); return; }
  try {
    const r = await fetch('/api/world/genome', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Command-Token': net.commandToken },
      body: JSON.stringify({ genome: loadedGenome, x, y, z }),
    });
    const d = await r.json().catch(() => ({}));
    if (r.ok && d.ok) toast(`injected at ${x.toFixed(0)}, ${y.toFixed(0)}`);
    else if (r.status === 403) toast('inject refused: bad token');
    else toast(`inject failed: ${d.error ?? r.status}`);
  } catch {
    toast('inject failed');
  }
}

injectBtn.onclick = () => injectFile.click();
injectFile.onchange = async () => {
  const f = injectFile.files?.[0];
  injectFile.value = ''; // let the same file be picked again next time
  if (!f) return;
  const genome = (await f.text()).trim();
  if (!genome) { toast('empty genome file'); return; }
  loadedGenome = genome;
  try { localStorage.setItem(GENOME_KEY, genome); } catch { /* private mode: keep in memory */ }
  enableGenomeSpawn();
  spawnSel.value = 'genome'; // arm tap-to-place with the just-loaded genome
  toast(`loaded ${f.name} — tap to place`);
};

// Restore a remembered genome across reloads (the client self-heals/reloads on
// redeploy), so an armed genome and its "tap: genome" mode survive.
if (!net.readOnly) {
  try {
    const saved = localStorage.getItem(GENOME_KEY);
    if (saved) { loadedGenome = saved; enableGenomeSpawn(); }
  } catch { /* ignore */ }
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
      if (m.total !== undefined) worldTotal = m.total;
      break;
    case 'delta':
      state.applyDelta(m, receivedAt);
      if (m.total !== undefined) worldTotal = m.total;
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
    // Reach is the body OR a finger's width, whichever is larger -- not a multiple
    // of the body. Scaling by size padded small creatures into tappability but
    // inflated large furniture along with it: a blast door already fills its tile,
    // and 2.5x its radius swallowed everything standing near it.
    if (d < Math.max(fingerTiles, e.size) && d < bestD) {
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
  // instead of spawning — so grazing and regrowth are observable.
  if (debugOn && inBounds) {
    selectTile(tx, ty, currentLevel);
    return;
  }
  const kind = spawnSel.value;
  if (kind === 'genome' && inBounds) {
    injectGenomeAt(tap.x, tap.y, currentLevel); // drop the remembered genome here
  } else if (kind && inBounds) {
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
  const flags = [d.walkable ? 'walkable' : '', d.water ? 'water' : '', d.open ? 'open' : '',
    d.solid ? 'solid' : '', d.slow ? 'slow' : '', d.blocksSight ? 'blocks sight' : '']
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

// Non-debug context: what you actually want while following a creature — what
// it is doing right now, how fed it is, and how deep in its lineage it sits.
// Everything else stays behind debug mode.
function renderInspectSimple(d: Record<string, any>): void {
  const kind = String(d.role ?? d.kind ?? 'entity').replace('npc.', '').replace('item.', '');
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  const rows: string[] = [];
  // Where it sits in the food chain, and whether a mind is driving it. The
  // header shows the role as its title, but that alone cannot say "prey, and
  // evolvable" — which for the hybrid cohort is most of what you want to know.
  if (d.role) rows.push(row('role', d.minded ? `${d.role} · minded` : d.role));
  if (d.diedOf) rows.push(row('died of', d.diedOf));
  if (d.action) rows.push(row('doing', d.action));
  if (d.state) rows.push(row('state', d.state));
  if ('pressed' in d) rows.push(row('pressed', d.pressed ? 'yes' : 'no'));
  if ('wiredTo' in d) rows.push(row('wired to', `#${d.wiredTo}`));
  if ('energy' in d) rows.push(bar('energy', Number(d.energy).toFixed(2), Math.max(0, Math.min(1, d.energy / 4))));
  // gen 0 is a creature the world (or you) placed; every birth adds one.
  if ('generation' in d) rows.push(row('generation', `gen ${d.generation}`));
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
  if ('generation' in d) status.push(row('generation', `gen ${d.generation}`));
  status.push(row('age', d.age));
  status.push(row('health', d.health));
  if ('energy' in d) status.push(bar('energy', Number(d.energy).toFixed(2), Math.max(0, Math.min(1, d.energy / 4))));
  if ('hydration' in d) status.push(bar('water', Number(d.hydration).toFixed(2), Math.max(0, Math.min(1, d.hydration))));
  if (d.diedOf) status.push(row('died of', d.diedOf));
  const fl: string[] = [];
  if (d.dead) fl.push('dead');
  if (d.flying) fl.push('flying');
  if (d.carrying) fl.push('carrying');
  if (d.grabbed) fl.push('grabbed');
  if (d.attachedTo >= 0) fl.push(`attached→#${d.attachedTo}`);
  if (fl.length) status.push(row('flags', fl.join(', ')));
  if ('edible' in d) status.push(row('edible', d.edible ? 'yes' : 'no'));
  if ('durability' in d) status.push(row('durability', d.durability));
  if (d.state) status.push(row('state', d.state));
  if ('span' in d) status.push(row('span', d.span));
  if ('pressed' in d) status.push(row('pressed', d.pressed ? 'yes' : 'no'));
  if ('wiredTo' in d) status.push(row('wired to', `#${d.wiredTo}`));
  if ('strength' in d) status.push(row('strength', d.strength));
  if ('broods' in d) status.push(row('broods', d.broods));
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
  const gen = 'generation' in d ? ` <span class="mono">· gen ${d.generation}</span>` : '';
  const hd =
    `<h3><span class="sw" style="background:#${swatch.toString(16).padStart(6, '0')}"></span>` +
    `mind <span class="mono">#${d.id}</span>${gen}` +
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
  // Headline: decode the actuators into a plain-language "what is it doing".
  const doing = `<div class="doing">${doingNow(actuators)}</div>`;
  // Attention: a blanked channel and an empty one both read 0, and they are
  // opposite facts — "there is no grass" versus "there is grass and no room to
  // hold it". The server says which were crowded out, so they can be labelled.
  const dropped = new Set((d.trackDropped as string[]) ?? []);
  const slots = d.trackSlots as number | undefined;
  const attn = slots === undefined ? '' :
    `<div class="grp">attention — holds ${slots} target${slots === 1 ? '' : 's'} at once` +
    (dropped.size ? `, no room for ${[...dropped].join(', ')}` : '') + '</div>';
  const intent = d.intent === undefined ? '' :
    `<div class="grp">intent — ${esc(String(d.intent))}</div>`;
  const sIn = sensors.map(c => {
    const row = channelRow(c, SENSOR_KIND);
    return dropped.has(c.name) ? row.replace('</div>', ' <i class="crowded">no room</i></div>') : row;
  }).join('');
  const aOut = actuators.map(c => channelRow(c, ACT_KIND)).join('');
  const prog = disasm
    .map((ln, i) => `<div class="ins ${insClass(ln)}${i === d.pc ? ' pc' : ''}">${esc(ln)}</div>`)
    .join('');
  const regChips = regs
    .map((v, i) => `<span class="reg">R${i} <b>${v.toFixed(2)}</b></span>`)
    .join('');
  mindEl.innerHTML = hd + doing + intent + attn +
    '<div class="grp">sensors — what it perceives</div>' + sIn +
    '<div class="grp">actuators — what it drives</div>' + aOut +
    `<div class="grp">program · instruction ${d.pc + 1} of ${d.length} · ${d.stepsPerTick}/tick</div>` +
    `<div class="prog">${prog}</div>` +
    `<div class="grp">registers</div><div class="regs">${regChips}</div>` +
    '<div class="save" role="button">⤓ save genome</div>';
  showMind();
}

// Export this creature's whole genome (brain included) to a savefile you can
// back up and later re-inject as a seed (POST /api/world/genome, token-gated).
async function downloadGenome(id: number): Promise<void> {
  try {
    const r = await fetch(`/api/world/genome/${id}`);
    if (!r.ok) { toast('no genome'); return; }
    const d = await r.json();
    if (!d.hasBrain || !d.genome) { toast('no brain to save'); return; }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([d.genome], { type: 'text/plain' }));
    a.download = `creature-${id}.genome`;
    a.click();
    URL.revokeObjectURL(a.href);
    toast('genome saved');
  } catch {
    toast('save failed');
  }
}

// Each I/O channel has a natural shape; render it that way rather than as one
// generic bar. Bearings are angles, flags are on/off, some sensors are enums,
// and gated actuators only matter once they cross their firing threshold.
const SENSOR_KIND: Record<string, string> = {
  bias: 'const',
  energy: 'mag', food: 'mag', phero: 'mag', near_prox: 'mag', near_sim: 'mag',
  item_prox: 'mag', prey_prox: 'mag', threat_prox: 'mag', health: 'mag',
  near_bearing: 'brg', item_bearing: 'brg', prey_bearing: 'brg',
  threat_bearing: 'brg', kin_bearing: 'brg',
  near_sizeadv: 'bip', clock: 'bip',
  blocked: 'bool', whisker_l: 'bool', whisker_r: 'bool', hazard_ahead: 'bool',
  item_kind: 'item_kind', carried: 'carried',
};
const ACT_KIND: Record<string, string> = {
  turn: 'bip', throttle: 'mag', struggle: 'mag',
  eat: 'gate', deposit: 'gate', attack: 'gate', mate: 'gate',
  grab: 'gate', attach: 'gate', sprint: 'gate',
  vertical: 'vert',
};

// One channel row: name · a shape-appropriate visual · a readable value. Rows
// sitting at their inert value are dimmed so the live signals stand out; a
// firing actuator is highlighted.
function channelRow(c: { name: string; value: number }, kinds: Record<string, string>): string {
  const v = Number(c.value) || 0;
  const kind = kinds[c.name] ?? 'bip';
  let vis = '';
  let val = v.toFixed(3);
  let quiet = false;
  let fire = false;
  switch (kind) {
    case 'const':
      vis = magBar(v); val = v.toFixed(2); quiet = true; break;
    case 'mag':
      vis = magBar(clamp01(v)); val = v.toFixed(2); quiet = v < 0.02; break;
    case 'bip':
      vis = bipBar(v); val = v.toFixed(2); quiet = Math.abs(v) < 0.02; break;
    case 'brg': {
      const deg = Math.round(v * 180);
      vis = compass(v); val = `${deg > 0 ? '+' : ''}${deg}°`; quiet = Math.abs(v) < 0.02; break;
    }
    case 'bool': {
      const on = v >= 0.5;
      vis = `<span class="dot${on ? ' on' : ''}"></span>`; val = on ? 'yes' : '—'; quiet = !on; break;
    }
    case 'gate':
      fire = v > 0.5; vis = gateBar(v); val = (fire ? '▶ ' : '') + v.toFixed(2); quiet = !fire; break;
    case 'item_kind': {
      const t = v > 0.5 ? ['food', 'good'] : v < -0.5 ? ['hazard', 'bad'] : ['none', ''];
      vis = chip(t[0], t[1]); val = ''; quiet = !t[1]; break;
    }
    case 'carried': {
      const t = v > 0.5 ? ['captive', 'bad'] : v < -0.5 ? ['riding', 'good'] : ['free', ''];
      vis = chip(t[0], t[1]); val = ''; quiet = !t[1]; break;
    }
    case 'vert': {
      const t = v > 0.5 ? ['climb', 'good'] : v < -0.5 ? ['descend', 'good'] : ['hold', ''];
      vis = chip(t[0], t[1]); val = ''; quiet = !t[1]; break;
    }
  }
  const cls = 'ch' + (quiet ? ' dim' : '') + (fire ? ' fire' : '');
  return `<div class="${cls}"><span class="nm">${c.name}</span>` +
    `<span class="vis">${vis}</span><span class="val mono">${val}</span></div>`;
}

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v));
}
// A left-filling bar for a 0..1 magnitude.
function magBar(f: number): string {
  return `<span class="mag"><i style="width:${(f * 100).toFixed(0)}%"></i></span>`;
}
// The same, tinted, with the 0.5 firing threshold marked, for gated actuators.
function gateBar(v: number): string {
  return `<span class="mag gate"><i style="width:${(clamp01(v) * 100).toFixed(0)}%"></i></span>`;
}
// A centre-anchored bar for a signed -1..1 quantity (negative grows left).
function bipBar(v: number): string {
  const w = Math.min(50, Math.abs(v) * 50);
  const left = v >= 0 ? 50 : 50 - w;
  return `<span class="bip"><i style="left:${left}%;width:${w}%"></i></span>`;
}
// A compass needle for an egocentric bearing: 0 = straight ahead (up), positive
// to the right, negative to the left — value is -1..1 of PI, so ×180 = degrees.
function compass(v: number): string {
  return `<span class="brg" style="transform:rotate(${v * 180}deg)">↑</span>`;
}
function chip(label: string, tone: string): string {
  return `<span class="chip${tone ? ' ' + tone : ''}">${label}</span>`;
}

// Decode the actuator vector into a one-line plain-language read of behaviour.
function doingNow(acts: { name: string; value: number }[]): string {
  const m: Record<string, number> = {};
  for (const a of acts) m[a.name] = Number(a.value) || 0;
  const at = (k: string) => m[k] ?? 0;
  const words: string[] = [];
  const sprinting = at('sprint') > 0.5;
  if (at('throttle') > 0.05 || sprinting) words.push(sprinting ? 'sprinting' : 'moving');
  if (Math.abs(at('turn')) > 0.1) words.push(at('turn') > 0 ? 'turning right' : 'turning left');
  if (at('eat') > 0.5) words.push('eating');
  if (at('mate') > 0.5) words.push('mating');
  if (at('attack') > 0.5) words.push('attacking');
  if (at('grab') > 0.5) words.push('grabbing');
  if (at('attach') > 0.5) words.push('hitching');
  if (at('deposit') > 0.5) words.push('marking');
  if (at('struggle') > 0.5) words.push('struggling');
  if (at('vertical') > 0.5) words.push('climbing');
  else if (at('vertical') < -0.5) words.push('descending');
  return words.length ? words.join(' · ') : 'idle';
}

// Tint a disassembly line by role so the program's shape is scannable: reads
// from a sensor (input), writes to an actuator (output), or control flow.
function insClass(ln: string): string {
  if (/\bsense\b/.test(ln)) return 'in';
  if (/\bact\b/.test(ln)) return 'out';
  if (/\bskip\b/.test(ln)) return 'ctl';
  return '';
}

function showMind(): void {
  mindEl.style.display = 'block';
  mindEl.querySelector('.back')!.addEventListener('click', closeMind);
  mindEl.querySelector('.x')!.addEventListener('click', deselect);
  const save = mindEl.querySelector('.save');
  if (save && selectedId !== null) {
    const id = selectedId;
    save.addEventListener('click', () => downloadGenome(id));
  }
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
  // The entity stream is filtered server-side to the watched level; asking
  // for the new one resyncs us with a full snapshot of it.
  net.send({ cmd: 'level', z: currentLevel });
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

// ---- perf HUD ('h' key or ?hud=1): which clock is actually slipping? ------
const hudEl = document.getElementById('hud') as HTMLElement;
let hudOn = /[?&]hud\b/.test(location.search);
let hudGl = { drawCalls: 0, quads: 0, uploadMs: 0, textures: 0,
  secLayers: 0, secEnts: 0, secTail: 0 };
let hudUpMax = 0; // worst texture-upload frame since the HUD last redrew
let hudFrameEma = 16.7;
let hudLastFrame = 0;
let hudLastText = 0;
let hudTick = ''; // sim load, polled while the HUD is open
let hudTickAt = 0;
window.addEventListener('keydown', (ev) => {
  if (ev.key === 'h' && !(ev.target instanceof HTMLInputElement)) {
    hudOn = !hudOn;
    hudEl.style.display = hudOn ? 'block' : 'none';
  }
});
if (hudOn) hudEl.style.display = 'block';

function hudFrame(now: number): void {
  if (hudLastFrame > 0) hudFrameEma += (Math.min(200, now - hudLastFrame) - hudFrameEma) * 0.08;
  hudLastFrame = now;
  hudUpMax = Math.max(hudUpMax, hudGl.uploadMs);
  if (!hudOn || now - hudLastText < 250) return;
  hudLastText = now;
  const _resetUp = hudUpMax; hudUpMax = 0; void _resetUp;
  if (now - hudTickAt > 3000) {
    hudTickAt = now;
    fetch('/api/health').then(r => r.json()).then(h => {
      hudTick = `tick ${h.tickMillis}/${h.tickBudgetMillis}ms` + (h.keepingUp ? '' : ' LAGGING');
    }).catch(() => { hudTick = ''; });
  }
  const net2 = Net.streamStats;
  hudEl.textContent = `${glr ? 'webgl' : 'canvas2d'} · ${(1000 / hudFrameEma).toFixed(0)} fps`
    + ` (${hudFrameEma.toFixed(1)} ms)`
    + (glr ? `\n${hudGl.drawCalls} draw calls · ${hudGl.quads} quads · up ${hudUpMax.toFixed(1)}ms` : '')
    + (glr ? `\njs: layers ${hudGl.secLayers.toFixed(1)} · ents ${hudGl.secEnts.toFixed(1)}`
      + ` · tail ${hudGl.secTail.toFixed(1)}ms` : '')
    + `\nstream ${net2.msgMs.toFixed(1)}ms/${net2.msgKb.toFixed(0)}kb`
    + ` · tex ${hudGl.textures} · atlases ${atlasCount()}`
    + `\n${state.tracks.size} on level · ${worldTotal} world`
    + (hudTick ? `\n${hudTick}` : '');
}

let lastStats = 0;
let lastMini = 0;
let miniCx = NaN, miniCy = NaN, miniScale = NaN;
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

  const chunkPx = hello ? (hello.chunkPx ?? hello.tileSize) : 0;
  const sel = { id: selectedId, tile: selectedTile };
  if (glr) {
    hudGl = renderGL(glr, g, cam, state, meta, chunkTiles, chunkPx, getChunk, getBareChunk,
      vegGrid, coverGrid, renderTime, now, currentLevel, sel);
  } else {
    render(g, cam, state, meta, chunkTiles, chunkPx, getChunk, getBareChunk,
      vegGrid, coverGrid, renderTime, now, currentLevel, sel);
  }
  hudFrame(now);
  // The minimap is ambient, not mission-critical: entity drift redraws at
  // 4 Hz, but a camera move redraws at once so the viewport rectangle never
  // visibly lags a pan or zoom.
  if (meta && (now - lastMini > 250
      || cam.cx !== miniCx || cam.cy !== miniCy || cam.scale !== miniScale)) {
    lastMini = now;
    miniCx = cam.cx; miniCy = cam.cy; miniScale = cam.scale;
    drawMinimap(mm, cam, state, meta, cv, currentLevel);
  }

  if (net.status === 'open' && now - lastStats > 250) {
    lastStats = now;
    statsEl.textContent = `tick ${state.tick} · ${worldTotal || state.tracks.size} entities` +
        (hello ? ` · seed ${hello.seed}` : '');
  }
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
