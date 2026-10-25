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
import { VEG_KIND_MASK, advanceGait, drawClimateLayer, drawWaterGlints, drawSenseHeat, render, renderGL, setLowMapSource, type ClimateField, type ClimateRange, type WorldMeta } from './render';
import { RENDER_DELAY_MS, WorldState } from './state';
import { flagOff, flagOn } from './flags';
import {
  CLOSE_BTN, closePanel, isShortcut, openPanel, rightColumn, wirePanelChrome, type PanelSize,
} from './panels';
import { fmtSpan, popOn } from './population';
import { bodyScanCanvas, planFor, stateOf } from './bodyScan';

/** Every clock the viewer keeps, in ms, in one place. */
const MS = {
  vegPoll: 4000,    // vegetation deltas
  detailPoll: 1000, // a creature's card: glycogen and state tick live
  tilePoll: 600,    // a tile's food, fast enough to watch grazing happen
  mindPoll: 500,    // a program counter polled at 1 Hz reads as frozen
  fetchRetry: 8000, // a failed chunk or low map is asked for again after this
  lowMapGate: 2000, // how long the level's chunks wait for its low map
  toast: 2500,
  tripleTap: 600,   // the window for the stats readout's debug triple-tap
  hudText: 250,     // the perf HUD's text refresh
} as const;

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
// A lost context (driver reset, GPU memory pressure, a backgrounded phone) is
// a blank world until the page reloads unless the loss is claimed: the
// preventDefault is what makes the browser offer it back, and the renderer is
// then rebuilt on it from nothing, its textures re-uploaded as they are drawn.
let glLost = false;
cv.addEventListener('webglcontextlost', ev => { ev.preventDefault(); glLost = true; });
cv.addEventListener('webglcontextrestored', () => {
  try {
    glr = new GLRenderer(cv, glPref === '1');
    glLost = false;
  } catch {
    location.reload(); // the overlay is already a 2D context, so #cv cannot fall back
  }
});
const g = glr
  ? fx.getContext('2d')! // the overlay: transparent above the GL world
  : cv.getContext('2d', { alpha: false })!;
if (!glr) fx.hidden = true;
const statsEl = document.getElementById('stats')!;
const pauseBtn = document.getElementById('pause') as HTMLButtonElement;
const speedSel = document.getElementById('speed') as HTMLSelectElement;
const spawnSel = document.getElementById('spawn') as HTMLSelectElement;
const toastEl = document.getElementById('toast')!;
const inspectEl = document.getElementById('inspect') as HTMLElement;
const mm = document.getElementById('minimap') as HTMLCanvasElement;
// The floor stepper: up over down, beside the floor's name (Bulkhead's bh-level).
const levelCtl = document.getElementById('levelCtl') as HTMLElement;
const levelLabel = document.getElementById('level') as HTMLElement;
const levelUp = document.getElementById('levelUp') as HTMLButtonElement;
const levelDown = document.getElementById('levelDown') as HTMLButtonElement;
// The phone's level control: every floor in a column on the right edge (bh-rail).
const floorRail = document.getElementById('floorRail') as HTMLElement;
const droneBtn = document.getElementById('drone') as HTMLButtonElement;
const injectBtn = document.getElementById('inject') as HTMLButtonElement;
const injectFile = document.getElementById('injectFile') as HTMLInputElement;


/** Publishes the toolbar's real height, which every panel opens below. The
 *  toolbar wraps — on a phone it is two rows, and on a wide screen it grows a
 *  row when debug mode reveals the constants button — so the 52px the panels
 *  used to assume was one row's worth and a panel opened over its own buttons.
 *  Observed rather than recomputed at call sites: wrapping is the browser's
 *  decision, and it is the only thing that actually knows. */
function syncBarHeight(): void {
  const h = document.getElementById('bar')!.getBoundingClientRect().height;
  document.documentElement.style.setProperty('--bar-h', `${Math.ceil(h) + 4}px`);
}
new ResizeObserver(syncBarHeight).observe(document.getElementById('bar')!);
syncBarHeight();

// ---- the toolbar's phone fold -----------------------------------------------
// Under 600 px the tools fold behind one toggle (the CSS decides when; this only
// opens and shuts the sheet). A tool that was used puts the sheet away: what it
// opens is the thing the viewer wanted to look at, and a sheet left over it
// would be in the way. A select only does that once it has been changed.
const barEl = document.getElementById('bar')!;
const barToggle = document.getElementById('barToggle') as HTMLButtonElement;
const barTools = document.getElementById('barTools')!;
function setBarOpen(open: boolean): void {
  barEl.classList.toggle('open', open);
  barToggle.setAttribute('aria-expanded', String(open));
  barToggle.classList.toggle('on', open);
}
barToggle.onclick = () => setBarOpen(!barEl.classList.contains('open'));
barTools.addEventListener('click', ev => { if ((ev.target as HTMLElement).closest('button')) setBarOpen(false); });
barTools.addEventListener('change', () => setBarOpen(false));
/** A group whose controls are all hidden hides itself, label and divider with it:
 *  a read-only viewer has no simulation or placing controls, and an empty group
 *  would leave a lone divider on a wide screen and a bare label in the sheet.
 *  Driven by an observer, so it follows whichever code shows or hides a control. */
function syncBarGroups(): void {
  for (const g of Array.from(barTools.querySelectorAll<HTMLElement>('.bar-group'))) {
    const live = Array.from(g.children).some(c => !(c as HTMLElement).classList.contains('bar-glabel')
      && !(c as HTMLElement).hidden && c.tagName !== 'INPUT');
    // Only on a change: writing `hidden` re-fires the observer below even with the
    // same value, and an unconditional write looped it forever and hung the page.
    if (g.hidden !== !live) g.hidden = !live;
  }
}
new MutationObserver(syncBarGroups).observe(barTools, { subtree: true, childList: true, attributes: true, attributeFilter: ['style', 'hidden'] });
syncBarGroups();
// Escape puts away ONE thing, the topmost: the folded-out toolbar, then the
// settings dialog, then the corner panel, then the card. It was three listeners
// that each closed their own, so one press closed the dialog and the panel
// behind it together.
window.addEventListener('keydown', ev => {
  if (ev.key !== 'Escape') return;
  if (barEl.classList.contains('open')) { setBarOpen(false); return; }
  if (closePerfDlg()) return;
  const open = rightColumn.filter(p => p.el.classList.contains('open'));
  const corner = open.find(p => !p.el.classList.contains('card'));
  (corner ?? open[0])?.close();
});

const state = new WorldState();
const cam = new Camera(cv);
let meta: WorldMeta | null = null;
let hello: HelloMsg | null = null;
let loadedBuild: string | null = null; // server build this tab loaded against; reload if it changes
let chunkTiles = 0;
let currentLevel = 0;
/**
 * Bumped every time the viewer changes level.
 *
 * <p>The per-level grids (vegetation, cover) are fetched asynchronously, and a
 * request reads `currentLevel` before its await but assigns the shared grid
 * after it. Switch level while one is in flight and the OLD level's answer lands
 * on top of the NEW level's grid — grass from the wrong floor, and the sequence
 * number that comes with it then makes every later delta a delta against the
 * wrong history, so it stays wrong until the next full resync. The switch itself
 * fires a fresh poll immediately, which is exactly what makes the two overlap.
 *
 * <p>A response is therefore only applied if the epoch it started in is still
 * current. A counter rather than comparing the level, so a fast A -> B -> A
 * round trip does not let the first response through on the second A.
 */
let levelEpoch = 0;
/** Whether a `meta` has ever been seen. The first one places the viewer; every
 *  later one is a reconnect and must leave it where it is. */
let seenMeta = false;
let paused = false;
// World population across every level: the stream itself only carries the
// entities of the level in view (the server filters per viewer), so the HUD
// count comes with the messages instead of from tracks.size.
let worldTotal = 0;
let firstFullAt = 0; // when the first world snapshot landed (startup metric)

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
/** The renderer asks by level, not just by chunk: the level in view needs its
 *  own chunks, and the one below it needs the few its holes look down onto
 *  (render.belowChunks). Both live in the same cache, keyed by level.
 *  `request` false is a peek: what is decoded, and no fetch. */
function getChunk(cx: number, cy: number, z: number, request: boolean): HTMLCanvasElement | null {
  const v = hello ? hello.build : '0';
  // The bake carries no vegetation (server ground classes are type-only) —
  // vegetation renders as one-tile sprites stamped over these chunks by the
  // chunked vegetation layer (render.vegLayerUpdate).
  const name = `${cx}_${cy}`;
  const key = `${v}/${z}/${name}`;
  const hit = chunkCache.get(key);
  if (hit !== undefined) return hit;
  if (!request) return null; // a peek: what is decoded, and no request
  // The low map goes first. While the viewed level's is in flight no chunk is
  // requested at all: fetched side by side, one small picture queued behind
  // fifty-four bigger ones and landed after most of them, which made it
  // pointless. The gate has a deadline, so a lost low map costs a moment and
  // not the ground.
  if (lowMapCache.get(`${v}/${currentLevel}`) === null && performance.now() < lowMapGateUntil) {
    return null;
  }
  chunkCache.set(key, null); // mark in-flight so we fetch once
  const img = new Image();
  img.onload = () => {
    const copy = document.createElement('canvas');
    copy.width = img.naturalWidth;
    copy.height = img.naturalHeight;
    copy.getContext('2d')!.drawImage(img, 0, 0);
    chunkCache.set(key, copy);
  };
  // A failed fetch must not leave a permanent hole: on a phone one flaky
  // radio moment used to freeze a black chunk into the world for the tab's
  // whole life — AND kept the layer builders in their retry loops forever.
  // Forget the in-flight marker after a pause so the next frame that wants
  // this chunk simply refetches it.
  img.onerror = () => {
    setTimeout(() => {
      if (chunkCache.get(key) === null) chunkCache.delete(key);
    }, MS.fetchRetry);
  };
  img.src = `/api/world/layers/${z}/${name}.png?v=${v}`;
  return null;
}
/** The level's low map -- the whole floor in one small picture -- fetched and
 *  cached exactly like a chunk, under the same build tag. The renderer paints
 *  it into whatever chunks have not landed, and looks down through its holes
 *  (render.setLowMapSource). It arrives in two parts, the colour as a JPEG and
 *  the alpha as a greyscale PNG, because JPEG has no alpha and the colour as
 *  a PNG is too big to land first (LayerBaker.lowAlpha); they are put back
 *  together here, once. Without its alpha a sky level is a black sheet. */
const lowMapCache = new Map<string, HTMLCanvasElement | null>();
let lowMapGateUntil = 0; // chunk fetches wait for the low map until this
function lowMapImage(z: number): HTMLCanvasElement | null {
  const v = hello ? hello.build : '0';
  const key = `${v}/${z}`;
  const hit = lowMapCache.get(key);
  if (hit !== undefined) return hit;
  lowMapCache.set(key, null);
  if (z === currentLevel) lowMapGateUntil = performance.now() + MS.lowMapGate;
  const img = new Image(), alpha = new Image();
  let loaded = 0;
  const join = () => {
    if (++loaded < 2) return;
    const copy = document.createElement('canvas');
    copy.width = img.naturalWidth;
    copy.height = img.naturalHeight;
    const c = copy.getContext('2d', { willReadFrequently: true })!;
    c.drawImage(img, 0, 0);
    const colour = c.getImageData(0, 0, copy.width, copy.height);
    c.clearRect(0, 0, copy.width, copy.height);
    c.drawImage(alpha, 0, 0);
    const mask = c.getImageData(0, 0, copy.width, copy.height).data;
    for (let i = 3; i < mask.length; i += 4) colour.data[i] = mask[i - 3]; // grey is alpha
    c.putImageData(colour, 0, 0);
    lowMapCache.set(key, copy);
  };
  const retry = () => { setTimeout(() => { if (lowMapCache.get(key) === null) lowMapCache.delete(key); }, MS.fetchRetry); };
  img.onload = join;
  alpha.onload = join;
  img.onerror = retry;
  alpha.onerror = retry;
  img.src = `/api/world/layers/${z}/low.jpg?v=${v}`;
  alpha.src = `/api/world/layers/${z}/lowalpha.png?v=${v}`;
  return null;
}
setLowMapSource(lowMapImage);

/** Which index is the ground. The server says so; only a server too old to say
 *  makes us guess, and for that server the guess is right by construction. */
function surfaceZ(): number {
  return hello ? hello.surface ?? hello.levels - 1 : 0;
}

function levelName(z: number): string {
  // Floors are numbered FROM the ground, not by their raw index -- a bare
  // index reads "level 0" for the deepest floor of a 2-level world and
  // "level 0" again for the deepest of a 3-level one, the same label for two
  // different depths. And the ground is not simply the top of the array: a
  // world can carry open air above it, so the anchor is the surface the
  // server names, not the highest index. The surface itself reads "level 0"
  // rather than a word -- one scheme for every floor, above and below.
  const depth = z - surfaceZ();
  return depth > 0 ? `level +${depth}` : `level ${depth}`;
}

// ---- perf experiment toggles ----------------------------------------------
// Each visual subsystem can be switched off with `<flag>=0` in the URL, so
// its cost can be isolated on a real device by A/B-ing two loads. The ⚙
// button beside the perf HUD opens a dialog that flips these and reloads.
// veg=0 additionally stops the vegetation polling itself (no grid → the
// render paths skip the whole depletion machinery).
const PERF_FLAGS: Array<[string, string]> = [
  ['veg', 'vegetation & dither'],
  ['phero', 'pheromone clouds'],
  ['canopy', 'canopy veils & lids'],
  ['sprites', 'creature sprites (off = dots)'],
  ['overlay', 'badges, rings & links'],
  ['minimap', 'minimap'],
  ['glints', 'live glints on water'],
  ['parallax', 'below-floor parallax slide'],
  ['below', 'creatures on the floor below'],
  ['gl', 'webgl renderer (off = canvas2d)'],
];
/**
 * The other kind of flag. Perf flags are default-ON and switched off with
 * `<flag>=0`; a panel is default-OFF and switched on with `<flag>=1`, because
 * nobody wants a chart over the world until they ask for one. Both live in the
 * same dialog — from the viewer's side "what is switched on" is one question,
 * not two — and the apply step knows which sense each family reads in.
 */
const VIEW_FLAGS: Array<[string, string]> = [
  ['pop', 'population graph'],
  ['hud', 'performance HUD'],
];

const VEG_OFF = flagOff('veg');
// Whether the stream should carry the floor below's cohort too (the pit-view
// bodies). Sent with every level subscription; the server filters accordingly.
const BELOW_BODIES = !flagOff('below');
const MINIMAP_OFF = flagOff('minimap');
// The HUD's one-line record of what this load has switched off, so every
// screenshot documents its own experiment.
const perfOffStr = PERF_FLAGS.filter(([k]) => k !== 'gl' && flagOff(k)).map(([k]) => k).join(',');

// Live vegetation, polled as the server's QUANTISED 5-state grid (0 lush ..
// 4 bare — see server/VegFeed) with sequence-numbered deltas: the client
// sends the sequence it holds and receives only the tiles that moved, packed
// as (tile<<3|state) ints — a handful of numbers per poll instead of a 70KB
// base64 grid. States change ~4x less often than the old 17 dither buckets,
// so the poll also relaxes to 4s. vegVersion bumps on every applied change so
// the renderer can tell "same array, new content" without an identity swap.
/** High bit of a vegetation byte: this tile grows fungus, not grass. Set by the
 *  server in the full grid only — see VegFeed.KIND_FUNGUS. */

let vegGrid: Uint8Array | null = null;
let vegSeq = -1;
let vegVersion = 0;
let vegTimer = 0;
async function pollVeg(): Promise<void> {
  const epoch = levelEpoch;
  try {
    const r = await fetch(`/api/world/vegetation/${currentLevel}?since=${vegSeq}`);
    if (epoch !== levelEpoch) return; // level changed under us: this answer is stale
    if (!r.ok) { vegGrid = null; vegSeq = -1; return; }
    const j = await r.json();
    if (epoch !== levelEpoch) return;
    if (j.states !== undefined) {
      vegGrid = b64ToBytes(j.states);
      vegSeq = j.seq;
      vegVersion++;
    } else if (j.changes !== undefined && vegGrid) {
      // A change entry carries density only — three bits, no room for anything
      // else — so the kind bit already held for that tile is preserved. Terrain
      // never changes underfoot, so the kind cannot have gone stale.
      for (const p of j.changes as number[]) {
        vegGrid[p >> 3] = (vegGrid[p >> 3] & VEG_KIND_MASK) | (p & 7);
      }
      if (j.changes.length > 0) vegVersion++;
      vegSeq = j.seq;
    }
  } catch {
    /* transient; keep the last grid */
  }
}
function startVegPolling(): void {
  vegSeq = -1; // per-level history: resync with a full grid
  if (VEG_OFF) return;
  clearInterval(vegTimer);
  pollVeg();
  vegTimer = window.setInterval(() => { if (!document.hidden) void pollVeg(); }, MS.vegPoll);
}

// Static cover mask (one byte per tile, 1 = thicket) fetched once per level, so
// the renderer can draw a shrub canopy over the entities standing in cover.
function b64ToBytes(s: string): Uint8Array {
  const bin = atob(s);
  const a = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);
  return a;
}

/** A static per-level byte grid (cover, water, a climate field), fetched for
 *  the current level and handed back only if the level has not moved on in
 *  the meantime; `still` is the caller's own staleness test on top. */
async function fetchLevelGrid(path: string, still = () => true):
    Promise<{ bytes: Uint8Array; j: Record<string, any> } | null> {
  const epoch = levelEpoch;
  const r = await fetch(`${path}/${currentLevel}?v=${hello ? hello.build : '0'}`);
  if (epoch !== levelEpoch || !still() || !r.ok) return null;
  const j = await r.json();
  if (epoch !== levelEpoch || !still()) return null;
  return { bytes: b64ToBytes(j.data), j };
}

let coverGrid: Uint8Array | null = null;
async function fetchCover(): Promise<void> {
  coverGrid = null;
  try {
    const g = await fetchLevelGrid('/api/world/cover');
    if (g) coverGrid = g.bytes;
  } catch {
    /* transient; leave the canopy off until the next fetch */
  }
}

// Static water mask (one byte per tile: 1 water, 2 shallows, 3 pool, 4 mire)
// fetched once per level, so the renderer can twinkle each water its own way.
let waterGrid: Uint8Array | null = null;
async function fetchWater(): Promise<void> {
  waterGrid = null;
  try {
    const g = await fetchLevelGrid('/api/world/water');
    if (g) waterGrid = g.bytes;
  } catch {
    /* transient; the water lies still until the next fetch */
  }
}

// The chosen climate field for the current level, fetched once per level and
// per choice: the fields are static, so there is nothing to poll. The legend
// prints the field's own range, which only the server knows (temperature is
// in degrees and its span is the map's), so a wash of colour is never the
// only thing the viewer has to read it by.
let climGrid: Uint8Array | null = null;
let climRange: ClimateRange | null = null;
async function fetchClimate(): Promise<void> {
  const field = climChoice;
  climGrid = null;
  climRange = null;
  climLegend();
  if (!field) return;
  try {
    const g = await fetchLevelGrid(`/api/world/climate/${field}`, () => field === climChoice);
    if (!g) return; // stale: the level or the choice moved on
    climGrid = g.bytes;
    climRange = { min: g.j.min, max: g.j.max, mid: g.j.mid };
    climLegend();
  } catch {
    /* transient; the layer stays off until the next fetch */
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
    el.hidden = true;
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
      // A `meta` arrives on the FIRST connection and again on every reconnect —
      // and the server restarts on every deploy, so reconnects are routine.
      //
      // Only the first one places the viewer. A reconnect used to reset
      // currentLevel to the surface, which moved someone watching the cave to
      // another floor without being asked; worse, it never told the SERVER,
      // which kept streaming the level the client had last requested. Terrain
      // and vegetation followed the new level while the entities came from the
      // old one, and the label agreed with neither for as long as it took the
      // next poll to land. That is the "grass from the wrong level" this fixes:
      // it needed a restart to show, which is why it followed deploys.
      const firstMeta = !seenMeta;
      seenMeta = true;
      if (firstMeta) {
        // Open on the GROUND, which may not be the top of the array: a world
        // with sky above it would otherwise open on an empty level, and the
        // first thing a viewer saw would be a drop onto the world.
        // surfaceZ() reads the hello just assigned above; the min/max is a
        // bounds clamp, not a second opinion about where the ground is.
        currentLevel = Math.max(0, Math.min(m.levels - 1, surfaceZ()));
        cam.fit(m.cols, m.rows);
      } else {
        // Keep the viewer where it was, but never off the end of a world that
        // may have come back a different shape.
        currentLevel = Math.min(currentLevel, Math.max(0, m.levels - 1));
      }
      levelEpoch++;
      vegGrid = null;
      startVegPolling();
      fetchCover();
      fetchWater();
      fetchClimate();
      // Tell the server which level to stream. The client says this on every
      // connection now, not only when someone taps: after a reconnect the two
      // ends have no other way to agree, and silence meant the server carried on
      // with whatever the previous session had asked for.
      net.send({ cmd: 'level', z: currentLevel, below: BELOW_BODIES });
      // Only offer the level switch when the world actually has more than one.
      levelCtl.hidden = m.levels <= 1;
      buildFloorRail();
      syncLevelCtl();
      // A world with no base carves no charge pads and spawns no drones, and a
      // reset can hand back one that does or does not — so the button asks on
      // every hello and shows itself only if there is a rank to cycle.
      void fetchDroneRank();
      syncPauseBtn();
      break;
    }
    case 'full':
      state.applyFull(m, receivedAt);
      if (m.total !== undefined) worldTotal = m.total;
      if (firstFullAt === 0) firstFullAt = performance.now();
      break;
    case 'delta':
      state.applyDelta(m, receivedAt);
      if (m.total !== undefined) worldTotal = m.total;
      break;
    case 'status':
      paused = m.paused;
      speedSel.value = String(m.speed);
      syncPauseBtn();
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
    // You can select what you can SEE. Pheromone clouds and sounds are events,
    // not bodies — the renderer skips both in every body pass and says so in
    // those words, and only the sense overlay draws them. The picker missed
    // that memo and only excluded the clouds, so every sound was a tap target:
    // a spent one lingers as a dead entity, and they are 40% of everything the
    // viewer tracks, invisible and scattered exactly where the noise was made,
    // which is to say on top of the creatures. Tapping an animal often selected
    // the sound of it instead, and the inspector then reported an "entity" that
    // "died of old age" — which is what a lifespan expiry is called in a body,
    // and nonsense for a noise.
    if (e.kind === 'phero' || e.kind === 'sound') continue;
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
  // A tap does the same thing in every mode. Debug mode used to steal it for
  // tile inspection, so the gesture that spawns and deselects everywhere else
  // meant something different the moment the flag was on — and the long press
  // already inspects the ground, in every mode, which makes the tap version a
  // second way to do one thing rather than the only way to do it.
  const kind = spawnSel.value;
  if (kind === 'genome' && inBounds) {
    injectGenomeAt(tap.x, tap.y, currentLevel); // drop the remembered genome here
  } else if (kind && inBounds) {
    net.send({ cmd: 'spawnItem', kind, x: tap.x, y: tap.y, z: currentLevel });
  } else {
    deselect(); // tap on empty ground clears the selection
  }
}, hold => {
  // A long press inspects the ground, and it is the ONLY way to: one gesture,
  // the same in every mode, debug or not. Tile inspection used to be reachable
  // only with debug on, which put the one thing that explains WHY a patch is
  // bare — its fertility, its regrowth, what is standing on it — behind a flag
  // most people watching the world never turn on.
  //
  // Always the tile, never the creature standing on it: a tap already selects
  // creatures, and a gesture that returned one thing or the other depending on
  // what happened to be underfoot would be a gesture nobody could aim.
  if (!meta) {
    return;
  }
  const tx = Math.floor(hold.x), ty = Math.floor(hold.y);
  if (tx < 0 || ty < 0 || tx >= meta.cols || ty >= meta.rows) {
    return;
  }
  selectTile(tx, ty, currentLevel);
});

// ---- selection + inspect ---------------------------------------------------

let selectedId: number | null = null;
let selectedTile: { x: number; y: number; z: number } | null = null;
let detailTimer = 0;

/**
 * Selects an entity: opens its inspector, and follows it if it is a living body.
 *
 * <p>`sightUnseen` is for a caller that already knows what it picked and cannot
 * check. The entity stream is filtered to the watched level, so a drone parked
 * two floors down has no track here to read a kind off — and the ordinary
 * guard, which needs one, would decline to follow the very body the caller
 * asked for. The detail poll settles it within the second either way: it either
 * takes the viewer to that floor or deselects a body that is gone.
 */
function select(id: number, sightUnseen = false): void {
  selectedId = id;
  selectedTile = null;
  const t = state.tracks.get(id);
  if (sightUnseen || (t && t.curr.kind.startsWith('npc.') && !(t.curr.flags & F_DEAD))) {
    cam.followId = id; // follow living creatures; items stay put
  }
  refreshDetail();
  clearInterval(detailTimer);
  detailTimer = window.setInterval(() => { if (!document.hidden) void refreshDetail(); }, MS.detailPoll); // glycogen/state tick live
}

// Debug tile inspector: poll a tile's fertility/food so grazing is watchable.
function selectTile(x: number, y: number, z: number): void {
  selectedId = null;
  cam.followId = null;
  selectedTile = { x, y, z };
  syncMindPoll();
  refreshTileDetail();
  clearInterval(detailTimer);
  detailTimer = window.setInterval(() => { if (!document.hidden) void refreshTileDetail(); }, MS.tilePoll);
}

function deselect(): void {
  selectedId = null;
  selectedTile = null;
  cam.followId = null; // closing the inspector also stops following
  clearInterval(detailTimer);
  clearInterval(mindTimer);
  mindPollFor = null;
  inspectDetail = null;
  lineageFor = -1;
  closePanel(inspectEl);
}
// The inspector is in the corner group only while it is a panel; as a card it
// is exempt, and claimRightColumn skips it (see there).
rightColumn.push({ el: inspectEl, close: deselect });

async function refreshDetail(): Promise<void> {
  if (selectedId === null) return;
  // An answer is only applied to the body it was asked about: select A, then B
  // inside the second A's fetch takes, and A's reply landed on B's card — or a
  // 404 for a dead A deselected B.
  const id = selectedId;
  try {
    const r = await fetch(`/api/world/entity/${id}`);
    if (selectedId !== id) return;
    if (!r.ok) { deselect(); return; }
    const d = await r.json();
    if (selectedId !== id) return;
    // Follow it downstairs. The entity stream is filtered to the watched level,
    // so a creature that changes level simply stops arriving — from the stream
    // alone it is indistinguishable from one that died. This endpoint is not
    // level-filtered and carries the creature's z, which makes it the only thing
    // that can tell those two apart, so the follow is steered from here.
    if (cam.followId === selectedId && typeof d.z === 'number') {
      goToLevel(d.z);
    }
    // Two contexts: a compact card when just watching, the full dump in debug.
    if (debugOn) renderInspectDebug(d); else renderInspectSimple(d);
  } catch {
    /* transient; keep the last panel */
  }
}

async function refreshTileDetail(): Promise<void> {
  const asked = selectedTile;
  if (!asked) return;
  try {
    const { x, y, z } = asked;
    const r = await fetch(`/api/world/tile/${z}/${x}/${y}`);
    if (selectedTile !== asked) return;
    if (!r.ok) { deselect(); return; }
    const d = await r.json();
    if (selectedTile !== asked) return;
    renderTileInspect(d);
  } catch {
    /* transient; keep the last panel */
  }
}

// A tile's own facts. This is a CARD, not a panel: four rows of ground truth
// are a caption on the world, and the same thing the plain entity card is. It
// used to borrow the debug inspector's class and so opened at full screen,
// with no way to shrink it and taking the corner from whatever was there.
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
    ...(d.flora ? [row('flora', String(d.flora))] : []),
    bar('food', `${food.toFixed(3)} / ${cap.toFixed(3)}`, pct),
  ];
  inspectEl.innerHTML =
    `<h3>tile <span class="mono">${Number(d.x)},${Number(d.y)}</span> · L${Number(d.z)}` +
    `${CLOSE_BTN}</h3>` +
    `<table>${rows.join('')}</table>`;
  showInspect('card');
}

// Non-debug context: what you actually want while following a creature — what
// it is doing right now, how fed it is, and how deep in its lineage it sits.
// Everything else stays behind debug mode.
function renderInspectSimple(d: Record<string, any>): void {
  const kind = String(d.role ?? d.kind ?? 'entity').replace('npc.', '').replace('item.', '');
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  const rows: string[] = [];
  // Where it sits in the food chain, and whether a mind is driving it. The
  // header shows the role as its title, but that alone cannot say "herbivore, and
  // evolvable" — which for the hybrid cohort is most of what you want to know.
  if (d.role) rows.push(row('role', d.minded ? `${d.role} · minded` : d.role));
  if (d.diedOf) rows.push(row('died of', d.diedOf));
  if (d.action) rows.push(row('doing', d.action));
  if (d.state) rows.push(row('state', d.state));
  // Without these a creature slung over another's back read as one standing still.
  rows.push(...holdRows(d));
  if (d.species) rows.push(row('species', d.species));
  // A corpse's own facts sit under its identity: what it was comes first,
  // then what is left of it, which is the part anything still alive cares about.
  rows.push(...carcassRows(d));
  if (d.carrying) rows.push(row('carrying', 'cargo'));
  if ('pressed' in d) rows.push(row('pressed', d.pressed ? 'yes' : 'no'));
  if ('wiredTo' in d) rows.push(row('wired to', `#${d.wiredTo}`));
  // The books are for anyone following a creature, not a debug detail. Health
  // only for a creature: every living thing has a life gate, but on a crate it
  // is a number with nothing to say.
  rows.push(...vitalsRows(d, !!d.role));
  // gen 0 is a creature the world (or you) placed; every birth adds one.
  if ('generation' in d) rows.push(row('generation', `gen ${d.generation}`));
  if ('durability' in d) rows.push(row('durability', d.durability));
  inspectEl.innerHTML = header(swatch, kind, d.id)
      + (d.role ? '<div class="scan card"></div>' : '') + `<table>${rows.join('')}</table>`;
  // A creature's card opens on its body: the scan at 3 px a cell, no callouts,
  // the same books the bars below it count.
  const thumb = inspectEl.querySelector('.scan');
  if (thumb) thumb.appendChild(bodyScanCanvas(planFor(d.role), stateOf(d), 48, 24, 3, false));
  showInspect('card');
  syncMindPoll(); // leaving debug leaves the mind tab with nothing to show
}

// Debug context: the whole creature in ONE panel of tabs — attributes
// (identity + status), genome, lineage (its family line, from the world's
// birth registry), and mind (the evolvable program) for a creature that has a
// brain. The tab survives re-polls and re-selection, so a viewer walking a
// family tree stays on the lineage tab from body to body.
//
// The mind was a second panel that this one hid itself to open, with a "←
// back" to undo that. Two panels for one creature meant the viewer had to
// remember which one they were in, the tab bar stopped describing what was on
// screen, and closing the mind had to reconstruct the state the card was in.
// A tab is the same content under the rule the rest of the UI already follows.
// Order is the reading order: what it IS, what it inherited, what that
// inheritance thinks with, and where it came from. Genome and mind sit next to
// each other because the mind IS a gene — Genome.brain, copied and mutated with
// the rest — and lineage had been wedged between the two, which put a family
// tree in the middle of one creature's biology.
type InspectTab = 'attributes' | 'body' | 'genome' | 'mind' | 'lineage';
let inspectTab: InspectTab = 'attributes';
let inspectPanelSize: 'half' | 'full' = 'full'; // the debug panel's, as the viewer last set it
let inspectDetail: Record<string, any> | null = null;
let lineageData: Record<string, any> | null = null;
let lineageFor = -1; // which entity lineageData describes

/** The tabs this creature has. Mind appears only for a brained body: a tab
 *  that says "no brain" is a tab that wastes the reader's click. */
function inspectTabsFor(d: Record<string, any>): InspectTab[] {
  // Gated on there being a MIND, not on there being one particular substrate.
  // It asked `hasBrain`, which meant the LGP program, so a network-minded
  // creature was offered no mind tab and read as scripted.
  const minded = d.genome?.mind && d.genome.mind !== 'none';
  // The body scan is for a creature: a thing with a trophic role and the
  // four books. An item or a fixture has neither, and a tab of an empty
  // outline would be a tab that wastes the click.
  const body = d.role ? ['body' as InspectTab] : [];
  return minded
    ? ['attributes', ...body, 'genome', 'mind', 'lineage']
    : ['attributes', ...body, 'genome', 'lineage'];
}

function renderInspectDebug(d: Record<string, any>): void {
  inspectDetail = d;
  const swatch = state.tracks.get(selectedId!)?.curr.rgb ?? 0x888888;
  const name = String(d.role ?? d.kind ?? 'entity').replace('npc.', '').replace('item.', '');
  const avail = inspectTabsFor(d);
  // A body without a brain cannot show the mind tab; land on attributes rather
  // than an empty panel when the viewer walks from a minded creature to one
  // that is merely scripted.
  if (!avail.includes(inspectTab)) inspectTab = 'attributes';
  const tabs = avail.map(t =>
    `<button data-tab="${t}" class="${t === inspectTab ? 'on' : ''}">${t}</button>`).join('');
  let body = '';
  if (inspectTab === 'attributes') body = attributesTab(d);
  else if (inspectTab === 'body') body = '<div class="scan"></div>';
  else if (inspectTab === 'genome') body = genomeTab(d);
  else if (inspectTab === 'lineage') body = lineageTab(d);
  else body = mindTab();
  inspectEl.innerHTML = header(swatch, name, d.id, true)
      + `<div class="tabs">${tabs}</div>` + body;
  // A whole creature is what this panel is for, so it opens full screen — but
  // the size the viewer last chose survives the poll that rebuilds it, walking
  // from one creature to the next, and a spell as a card with debug off. Read
  // back from the class it did not: the card had replaced it.
  showInspect(inspectPanelSize);
  // The body tab is a canvas, not markup: the scan drawn at 4 px a cell with
  // its callouts, from the same books the attributes tab lists as bars.
  const scanHost = inspectEl.querySelector('.scan');
  if (scanHost) {
    scanHost.appendChild(bodyScanCanvas(planFor(d.role), stateOf(d), 96, 48, 4, true));
  }
  // The tab bar is chrome, so it stays put with the header rather than
  // scrolling away with the content — on a full genome dump or a mind listing
  // it used to leave the panel with no visible way out of the tab you were in.
  // It sticks BELOW the header, whose height is whatever the title wraps to, so
  // the offset is measured rather than guessed (the panel is open by now, so
  // offsetHeight is real).
  const head = inspectEl.querySelector(':scope > h3') as HTMLElement | null;
  if (head) inspectEl.style.setProperty('--inspect-head', `${head.offsetHeight}px`);
  inspectEl.querySelectorAll('.tabs button').forEach(b =>
    b.addEventListener('click', () => {
      inspectTab = (b as HTMLElement).dataset.tab as InspectTab;
      if (inspectDetail) renderInspectDebug(inspectDetail);
      syncMindPoll();
    }));
  const save = inspectEl.querySelector('.save');
  if (save && selectedId !== null) {
    const id = selectedId;
    save.addEventListener('click', () => downloadGenome(id));
  }
  syncMindPoll();
  // Any lineage node that is still in the world can be walked to: tapping it
  // selects that body, and the tab stays on lineage — the tree is a path.
  inspectEl.querySelectorAll('.lin .node.link').forEach(n =>
    n.addEventListener('click', () =>
      select(Number((n as HTMLElement).dataset.id), true)));
}

function attributesTab(d: Record<string, any>): string {
  const identity = [
    row('kind', d.kind),
    d.subtype ? row('subtype', d.subtype) : '',
    d.role ? row('role', d.role) : '',
    // Four things the compact card showed and the full dump did not: species,
    // how grown a juvenile is, which way round an attachment goes, and who is
    // being hauled. A debug panel that shows LESS than the caption it replaces
    // is the wrong way round, and each of these was simply never carried over.
    d.species ? row('species', d.species) : '',
    'minded' in d ? row('minded', d.minded ? 'yes' : 'no') : '',
  ];
  const status: string[] = [];
  if (d.action) status.push(row('action', d.action));
  if ('generation' in d) status.push(row('generation', `gen ${d.generation}`));
  status.push(row('age', d.age));
  if (d.juvenile) status.push(row('grown', d.grown));
  status.push(...vitalsRows(d, true));
  if (d.diedOf) status.push(row('died of', d.diedOf));
  status.push(...carcassRows(d));
  status.push(...holdRows(d));
  if ('mass' in d) status.push(row('mass', `${d.mass} × reference`));
  const fl: string[] = [];
  if (d.dead) fl.push('dead');
  if (d.flying) fl.push('flying');
  if (d.carrying) fl.push('carrying cargo');
  if (fl.length) status.push(row('flags', fl.join(', ')));
  if ('edible' in d) status.push(row('edible', d.edible ? 'yes' : 'no'));
  if ('durability' in d) status.push(row('durability', d.durability));
  if (d.state) status.push(row('state', d.state));
  if ('span' in d) status.push(row('span', d.span));
  if ('pressed' in d) status.push(row('pressed', d.pressed ? 'yes' : 'no'));
  if ('wiredTo' in d) status.push(row('wired to', `#${d.wiredTo}`));
  if ('strength' in d) status.push(row('strength', d.strength));
  if ('broods' in d) status.push(row('broods', d.broods));
  return group('identity', identity) + group('status', status);
}

/**
 * Everything heritable, grouped the way the genome itself is.
 *
 * This was one flat list of fourteen rows, and it had quietly stopped being the
 * genome: seven genes — the reproduction strategy, greed, determination, the
 * two r/K fractions, the mutation rate and the birth instinct — had been added
 * to `Genome` over time and never to this tab, so a reader looking for how a
 * lineage breeds found a mate threshold and nothing about whether it courts at
 * all. Groups rather than a longer flat list because twenty rows of bare labels
 * is a wall: these are the genome's own divisions, not invented ones.
 */
function genomeTab(d: Record<string, any>): string {
  const gm = d.genome;
  if (!gm) return '<div class="lin end">no genome — this body was built, not born</div>';
  // A gene the server did not send prints as an em dash rather than the word
  // "undefined": the two ship together, but a missing figure should read as
  // missing rather than as a value.
  const g = (v: unknown) => (v === undefined || v === null ? '—' : String(v));
  return group('identity', [
    // The recognition barcode. Species and body variant are both read off it,
    // which is why it sits here rather than among the body's measurements.
    row('markers', Array.isArray(gm.markers)
      ? (gm.markers as number[]).map(m => m.toFixed(2)).join(', ') : '—'),
  ]) + group('body', [
    row('size', g(gm.size)), row('speed', g(gm.speed)), row('turn rate', g(gm.turnRate)),
    row('sight', `${g(gm.losRange)} / ${(gm.losFov * 180 / Math.PI).toFixed(0)}°`),
    row('metabolism', g(gm.metabolism)), row('max age', g(gm.maxAge)),
    row('locomotion', gm.flying ? 'flying' : 'walking'),
  ]) + group('temperament', [
    row('predatory', g(gm.predatory)), row('xenophobia', g(gm.xenophobia)),
    row('gregarious', g(gm.gregariousness)), row('boldness', g(gm.boldness)),
    row('greed', g(gm.greed)), row('determination', g(gm.determination)),
    row('patience', g(gm.patience)), row('reserve', g(gm.reserve)),
  ]) + group('breeding', [
    // Sexual or asexual is the genome's own answer, arriving alongside the gene
    // it is read from. Whether 0.5 is the line is Genome.isSexual's business;
    // a threshold re-implemented here would be a second copy free to drift.
    row('reproduction', gm.sexual === undefined ? '—'
      : `${gm.sexual ? 'sexual' : 'asexual'} · ${gm.sexuality}`),
    row('mate thresh', g(gm.mateThreshold)),
    row('breeds at', g(gm.reproFraction)), row('spends', g(gm.reproCostFraction)),
    row('young born at', g(gm.birthSize)), row('milk', g(gm.milk)),
    row('drinks at', g(gm.drainRate)),
    row('mutation', g(gm.mutationRate)),
  ]) + group('mind', [
    // Instinct is in the genome and deliberation is in the mind, so the newborn
    // forage drive belongs beside the program it is overridden by.
    row('instinct', g(gm.instinct)),
    // How it hunts for what it cannot see: the cast, in degrees because radians
    // are not what anyone reads a path in. Narrow is a straight transect, wide
    // is combing the same ground over.
    row('search', gm.searchCast === undefined ? '—'
      : `cast ${(gm.searchCast * 180 / Math.PI).toFixed(0)}°`),
    // Which substrate, and how big. Two decision methods compete in this world
    // behind one seam; "brain: none" was what a network-minded body used to say.
    row('mind', gm.mind === undefined ? '—' : gm.mind === 'none' ? 'none'
      : `${gm.mind} · ${gm.mindSize} ${gm.mind === 'network' ? 'weights' : 'instr'}`),
  ]);
}

/** The body's store: fat as a share of what the body can carry, with the
 *  mass it amounts to. A fed life reads as a full bar; a starving body draws
 *  it down before its health goes. */
/**
 * The four books (VITALS.md) and the store, in one order for the card and the
 * debug panel alike — health the life gate, glycogen the action budget, fed and
 * watered the needs, all counting the SAME way up (see sated()), so a creature
 * in trouble is four draining bars. One function so the two panels cannot drift:
 * they had, once over which way the needs pointed and once over the labels.
 * A corpse is sent none of the books, because death is where they stop.
 */
function vitalsRows(d: Record<string, any>, health: boolean): string[] {
  const out: string[] = [];
  if (health && 'health' in d) {
    out.push(bar('health', d.health, Math.max(0, Math.min(1, d.health / 100))));
  }
  if ('glycogen' in d) out.push(glycogenRow(d));
  if ('hunger' in d) out.push(bar('fed', ...sated(d.hunger)));
  if ('thirst' in d) out.push(bar('watered', ...sated(d.thirst)));
  if ('fat' in d) out.push(fatRow(d));
  return out;
}

/** Who has hold of whom. `attachedTo` alone cannot tell a passenger from a
 *  captive — `grabbed` says which way round it is — and `hauling` names the
 *  body being dragged. */
function holdRows(d: Record<string, any>): string[] {
  const out: string[] = [];
  if (d.attachedTo >= 0) {
    out.push(d.grabbed ? row('held by', `#${d.attachedTo}`) : row('riding', `#${d.attachedTo}`));
  }
  if ('hauling' in d) out.push(row('hauling', `#${d.hauling}`));
  return out;
}

function fatRow(d: Record<string, any>): string {
  const f = Math.max(0, Math.min(1, Number(d.fat)));
  const mass = typeof d.fatMass === 'number' ? ` (${d.fatMass.toFixed(2)} mass)` : '';
  return bar('fat', `${Math.round(f * 100)}%${mass}`, f);
}

/**
 * The glycogen book as a fraction of THIS body's store.
 *
 * The store is size-scaled (`NPC.glycogenCapacity`), so no constant here can
 * stand in for it — and one did: a flat 4, which pinned every large body at full
 * from a quarter store and drew a small body's brimming store as three-quarters.
 * Both panels drew it, so both were wrong in the same way, which is what made it
 * look right. Shared now so they cannot disagree again, and when the cap is
 * missing the number is printed rather than a bar drawn against a guess.
 */
function glycogenRow(d: Record<string, any>): string {
  const e = Number(d.glycogen);
  const cap = Number(d.glycogenCap);
  return cap > 0
    ? bar('glycogen', `${e.toFixed(2)} / ${cap.toFixed(2)}`, Math.max(0, Math.min(1, e / cap)))
    : row('glycogen', e.toFixed(2));
}

/** The family line. Everything here is the world's birth registry speaking:
 *  ancestry exists only because it was written down at each birth, so a chain
 *  ends either at a true root (world-seeded, or the steward reseeding a
 *  wiped-out niche) or where the registry's memory fades. */
function lineageTab(d: Record<string, any>): string {
  if (lineageFor !== d.id) {
    lineageFor = d.id;
    lineageData = null;
    fetch(`/api/world/lineage/${d.id}`)
      .then(r => (r.ok ? r.json() : null))
      .then(j => {
        // A failed trace forgets it was asked, so the next poll asks again
        // instead of the tab reading "tracing…" for as long as it stays open.
        if (!j && lineageFor === d.id) lineageFor = -1;
        if (j && lineageFor === d.id) {
          lineageData = j;
          if (inspectTab === 'lineage' && inspectDetail?.id === d.id) {
            renderInspectDebug(inspectDetail!);
          }
        }
      })
      .catch(() => { if (lineageFor === d.id) lineageFor = -1; });
  }
  const L = lineageData;
  if (!L) return '<div class="lin end">tracing…</div>';
  const out: string[] = ['<div class="lin">'];
  const chain = L.chain as Array<Record<string, any>>;
  chain.forEach((n2, i) => {
    out.push(linNode(n2, i === 0 ? '●' : '↑', i === 0 ? 'this creature'
        : i === 1 ? 'parent' : 'grand'.repeat(i - 1) + 'parent', L.tick));
    if (n2.with !== undefined) {
      out.push(`<div class="lin end">— with mate #${n2.with}</div>`);
    }
  });
  out.push(`<div class="end">${L.rooted
    ? 'first of its line — world-seeded, or a niche reseeded'
    : 'records fade here'}</div>`);
  const kids = L.children as Array<Record<string, any>>;
  out.push(group('children' + (kids.length ? ` (${kids.length})` : ''), []));
  if (!kids.length) {
    out.push('<div class="end">none remembered</div>');
  }
  for (const k of kids) out.push(linNode(k, '↓', 'child', L.tick));
  out.push('</div>');
  return out.join('');
}

/** One lineage row: swatch, species, id, generation, age-at/born, status.
 *  Alive and dead-but-present bodies are tappable — the walkable tree. */
function linNode(n2: Record<string, any>, glyph: string, rel: string, nowTick: number): string {
  const alive = n2.status === 'alive';
  const present = n2.status !== 'gone';
  const sw = `<span class="sw" style="background:#${Number(n2.rgb).toString(16).padStart(6, '0')}"></span>`;
  const born = n2.born >= 0 && hello
    ? `born ${fmtSpan((nowTick - n2.born) / (hello.tps ?? 33))} ago` : '';
  const gen = n2.generation >= 0 ? `gen ${n2.generation}` : '';
  const meta = esc([gen, born, alive ? '' : String(n2.status)].filter(Boolean).join(' · '));
  return `<div class="node${present ? ' link' : ''}${alive ? '' : ' dead'}"`
    + (present ? ` data-id="${Number(n2.id)}"` : '')
    + `><span class="up">${glyph}</span>${sw}`
    + `<span class="who">${rel} — ${esc(String(n2.species).replace('/', ' · '))} `
    + `<span class="mono">#${Number(n2.id)}</span></span>`
    + `<span class="meta">${meta}</span></div>`;
}

function header(swatch: number, label: string, id: unknown, sizer = false): string {
  // The ⛶/⊟ appears only on the debug panel: a card has no sizes to swap. It
  // is left EMPTY here and filled by syncSizeBtn from the panel's own anchor —
  // written out at render time it came back stale on every poll. Its own class
  // too, not "x": the close wiring takes the first ".x" it finds, and a size
  // toggle that closed the panel would be a trap.
  const sz = sizer ? '<button type="button" class="szbtn"></button>' : '';
  return `<h3><span class="sw" style="background:#${swatch.toString(16).padStart(6, '0')}"></span>` +
    `${esc(label)} <span class="mono">#${esc(String(id))}</span>${sz}${CLOSE_BTN}</h3>`;
}
function group(title: string, rows: string[]): string {
  const body = rows.filter(Boolean).join('');
  return body ? `<div class="grp">${title}</div><table>${body}</table>` : '';
}
/**
 * A need (0 sated .. 1 desperate) as the satisfaction it leaves.
 *
 * <p>The engine stores hunger and thirst as needs, which is the right way round
 * for the simulation and the wrong way round for a progress bar: a bar is read
 * before it is understood, and a full one reads as "good" whatever the label
 * above it says. Shown raw, a starving creature had two brimming bars and a
 * well-fed one had two empty — the opposite of the truth, at a glance.
 *
 * <p>Returns the printed figure and the fill together, so the number and the bar
 * cannot drift into disagreeing about which way they count.
 */
function sated(need: unknown): [string, number] {
  const left = Math.max(0, Math.min(1, 1 - Number(need)));
  return [left.toFixed(2), left];
}

function bar(k: string, v: unknown, pct: number): string {
  return row(k, v) +
    `<tr><td colspan=2><div class="bar"><i style="width:${(pct * 100).toFixed(0)}%"></i></div></td></tr>`;
}
/** Shows the inspector at one of its three anchors, and rewires the header the
 *  render just replaced. */
function showInspect(size: PanelSize): void {
  openPanel(inspectEl, size);
  wirePanelChrome(inspectEl, deselect, () => {
    inspectPanelSize = inspectEl.classList.contains('full') ? 'full' : 'half';
  });
}

/** What a carcass still offers, for both inspector surfaces. A corpse is a
 *  resource, not a full stop: how much flesh is left on it and how far through
 *  rotting it is are what decide whether anything walks to it, and they are
 *  exactly what the panel used to omit. Shown the same way up as the living
 *  books -- a full bar is a good carcass -- so "meat" fills and "freshness"
 *  drains with rot rather than one of each. */
function carcassRows(d: Record<string, any>): string[] {
  const out: string[] = [];
  if (!('decay' in d)) return out;
  // A corpse is three pools, each drawn as its share of the body it came from,
  // so the three bars stack to what is left. Fresh meat is the hunters' and
  // holds the decay clock; decayed meat is the scavengers' and rots on it;
  // bones feed nobody and lie there until the clock runs out.
  const whole = typeof d.wholeMass === 'number' && d.wholeMass > 0 ? d.wholeMass : 0;
  const pool = (label: string, share: unknown) => {
    if (typeof share !== 'number') return;
    const f = Math.max(0, Math.min(1, share));
    const mass = whole ? ` (${(f * whole).toFixed(2)} mass)` : '';
    out.push(bar(label, `${Math.round(f * 100)}%${mass}`, f));
  };
  pool('fresh meat', d.fresh);
  pool('decayed meat', d.decayed);
  pool('bones', d.bones);
  out.push(bar('freshness', `${Math.round((1 - d.decay) * 100)}%`,
    Math.max(0, Math.min(1, 1 - d.decay))));
  if ('worth' in d) out.push(row('worth', `${d.worth} energy`));
  if ('rotsIn' in d) out.push(row('rots away in', `${d.rotsIn} ticks`));
  return out;
}

function row(k: string, v: unknown): string {
  return `<tr><td class="k">${esc(k)}</td><td class="v">${esc(String(v))}</td></tr>`;
}

// ---- mind tab ---------------------------------------------------------------
// The creature's evolvable LGP program: live sensor/actuator vectors, the
// disassembled instructions with the program counter marked, the registers.
//
// It is a TAB of the debug inspector, not a panel of its own. It keeps its own
// poll because a program counter at the card's 1 Hz reads as frozen — but the
// poll writes only into the tab's body, so the rest of the panel (and the
// reader's scroll position in a long listing) survives a refresh.
let mindTimer = 0;
let mindData: Record<string, any> | null = null;
let mindFor = -1; // which entity mindData describes
let mindPollFor: number | null = null; // which entity the running poll is for

/** Starts the mind poll while its tab is open and stops it otherwise — called
 *  wherever the tab, the selection, or the panel's existence can change. */
function syncMindPoll(): void {
  const want = debugOn && inspectTab === 'mind' && selectedId !== null
    && inspectEl.classList.contains('open');
  // Idempotent: the panel's 1 Hz rebuild calls this too, and restarting the
  // poll every time doubled its requests and reset its phase.
  if (want && mindPollFor === selectedId) return;
  clearInterval(mindTimer);
  mindPollFor = want ? selectedId : null;
  if (!want) return;
  if (mindFor !== selectedId) {
    mindData = null;
    mindFor = selectedId!;
  }
  void refreshMind();
  mindTimer = window.setInterval(() => { if (!document.hidden) void refreshMind(); }, MS.mindPoll);
}

async function refreshMind(): Promise<void> {
  if (selectedId === null || inspectTab !== 'mind') return;
  const id = selectedId;
  try {
    const r = await fetch(`/api/world/mind/${id}`);
    if (!r.ok) return; // a body that stopped answering is the card's business
    const d = await r.json();
    if (selectedId !== id || inspectTab !== 'mind') return; // moved on mid-flight
    mindData = d;
    mindFor = id;
    // Only the tab's body, so the panel does not flicker and the scroll holds.
    const host = inspectEl.querySelector('#mindBody');
    if (host) {
      host.innerHTML = mindBody(d);
      wireSave();
    }
  } catch {
    /* transient; keep what is on screen */
  }
}

/** The tab's shell. Empty until the first poll answers, then filled in place. */
function mindTab(): string {
  return `<div id="mindBody" class="mind">${
    mindData && mindFor === selectedId ? mindBody(mindData) : '<div class="grp">reading…</div>'
  }</div>`;
}

function mindBody(d: Record<string, any>): string {
  if (!d.hasMind) {
    return '<div class="grp">no mind</div>'
      + '<div class="mono">this creature is scripted, not minded.</div>';
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
  // How much of the thinking can be listed depends on the substrate. A program
  // disassembles instruction by instruction and holds registers between ticks;
  // a network describes itself in a line and holds no state to print. The live
  // channels above are identical for both, and they are most of what the tab is
  // for — what it senses and what it drives.
  const network = d.substrate === 'network';
  const head = network
    ? `<div class="grp">network · ${d.length} weights</div>`
    : `<div class="grp">program · instruction ${d.pc + 1} of ${d.length} · ${d.stepsPerTick}/tick</div>`;
  const regBlock = network ? ''
    : `<div class="grp">registers</div><div class="regs">${regChips}</div>`;
  return doing + intent + attn +
    '<div class="grp">sensors — what it perceives</div>' + sIn +
    '<div class="grp">actuators — what it drives</div>' + aOut +
    head + `<div class="prog">${prog}</div>` + regBlock +
    '<button type="button" class="save">⤓ save genome</button>' +
    '<button type="button" class="save edit">✎ edit a copy</button>' +
    `<button type="button" class="save cmp">${compareLabel()}</button>`;
}

// Two taps compare two creatures: the first tap remembers this one as A (so
// the label says so, derived from what is remembered rather than from what was
// last set), the second opens /compare with this one as B. Remembered in
// localStorage so it survives the inspector re-rendering between the taps.
const COMPARE_KEY = 'compareA';
function compareLabel(): string {
  let a: string | null = null;
  try { a = localStorage.getItem(COMPARE_KEY); } catch { /* private mode */ }
  return a ? `⇄ compare with ${a.split('\t')[0]} (A)` : '⇄ compare: set as A';
}

/** The save and edit buttons are rewritten by every mind poll, so their
 *  handlers are too. */
function wireSave(): void {
  const save = inspectEl.querySelector('.save:not(.edit):not(.cmp)');
  const edit = inspectEl.querySelector('.save.edit');
  const cmp = inspectEl.querySelector('.save.cmp');
  if (selectedId !== null) {
    const id = selectedId;
    save?.addEventListener('click', () => downloadGenome(id));
    edit?.addEventListener('click', () => openEditor(id));
    cmp?.addEventListener('click', () => compareStep(id));
  }
}

async function compareStep(id: number): Promise<void> {
  try {
    const r = await fetch(`/api/world/genome/${id}`);
    if (!r.ok) { toast('no genome'); return; }
    const d = await r.json();
    if (!d.hasBrain || !d.genome) { toast('no brain to compare'); return; }
    let a: string | null = null;
    try { a = localStorage.getItem(COMPARE_KEY); } catch { /* private mode */ }
    if (!a) {
      try { localStorage.setItem(COMPARE_KEY, `creature ${id}\t${d.genome}`); } catch { /* private mode */ }
      toast(`A is creature ${id}: tap another creature, then compare`);
      const el = inspectEl.querySelector('.save.cmp'); if (el) el.textContent = compareLabel();
      return;
    }
    const [la, ga] = a.split('\t');
    try { localStorage.removeItem(COMPARE_KEY); } catch { /* private mode */ }
    window.open('/compare#a=' + encodeURIComponent(ga) + '&la=' + encodeURIComponent(la)
      + '&b=' + encodeURIComponent(d.genome) + '&lb=' + encodeURIComponent('creature ' + id), 'bluproto-compare');
  } catch { toast('compare unavailable'); }
}

// Open the genome editor (/edit) on a copy of this creature's genome. The
// editor never touches the living creature: what it produces is a new founder,
// placed through the same injection path as a loaded file.
async function openEditor(id: number): Promise<void> {
  try {
    const r = await fetch(`/api/world/genome/${id}`);
    if (!r.ok) { toast('no genome'); return; }
    const d = await r.json();
    if (!d.hasBrain || !d.genome) { toast('no brain to edit'); return; }
    const t = net.commandToken ? '&t=' + encodeURIComponent(net.commandToken) : '';
    window.open('/edit#g=' + encodeURIComponent(d.genome) + t, 'bluproto-editor');
  } catch { toast('editor unavailable'); }
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
    document.body.append(a); // Firefox ignores a click on a detached anchor
    a.click();
    a.remove();
    // Revoked a beat later: revoking in the same task can cancel the download.
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
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
  glycogen: 'mag', food: 'mag', phero: 'mag', near_prox: 'mag', near_sim: 'mag',
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
  return `<div class="${cls}"><span class="nm">${esc(String(c.name))}</span>` +
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

/** Text for markup: everything the server names (a species, a role, a sensor)
 *  goes through here before it meets innerHTML. Quotes too, so it is safe in
 *  an attribute as well as between tags. */
function esc(s: string): string {
  return s.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]!));
}

// Minimap: tap to recentre the camera there (drops follow).
mm.addEventListener('click', ev => {
  if (!meta) return;
  const w = minimapToWorld(mm, meta, ev.clientX, ev.clientY);
  cam.cx = w.x;
  cam.cy = w.y;
  cam.followId = null;
});

pauseBtn.onclick = () => net.send({ cmd: paused ? 'resume' : 'pause' });
speedSel.onchange = () => net.send({ cmd: 'speed', value: parseFloat(speedSel.value) });
/**
 * Moves the viewer to a level: relabels, drops the per-level grids and asks the
 * server to stream that floor instead.
 *
 * <p>Its own function because two things move the viewer now — the button, and a
 * followed creature walking downstairs. Half a switch is worse than none: skip
 * the `level` command and the client draws one floor while the server streams
 * another; skip the epoch bump and a grid still in flight lands on the wrong
 * one.
 */
function goToLevel(z: number): void {
  if (!hello || z < 0 || z >= hello.levels || z === currentLevel) return;
  currentLevel = z;
  levelEpoch++; // any grid still in flight for the old level is now stale
  syncLevelCtl();
  vegGrid = null;
  startVegPolling(); // grass grid is per-level
  fetchCover(); // cover mask is per-level
  fetchWater(); // so is the water mask
  fetchClimate(); // and so is the climate field
  // The entity stream is filtered server-side to the watched level; asking
  // for the new one resyncs us with a full snapshot of it.
  net.send({ cmd: 'level', z: currentLevel, below: BELOW_BODIES });
}

/** The stepper's label and its two ends, read from the level every time: up is
 *  dead on the top floor and down on the bottom one, so the pair itself says
 *  where in the stack the viewer is. */
function syncLevelCtl(): void {
  levelLabel.textContent = levelName(currentLevel);
  const top = hello ? hello.levels - 1 : 0;
  levelUp.disabled = currentLevel >= top;
  levelDown.disabled = currentLevel <= 0;
  for (const b of Array.from(floorRail.querySelectorAll<HTMLButtonElement>('.bh-rail-floor'))) {
    b.setAttribute('aria-current', String(Number(b.dataset.z) === currentLevel));
  }
}

/** One plate per floor, highest at the top, labelled from the surface (+2, 0, −1)
 *  the way levelName counts. Rebuilt on every hello, because a reset can hand back
 *  a world with a different stack. A world of one floor shows no rail at all. */
function buildFloorRail(): void {
  for (const old of Array.from(floorRail.querySelectorAll('.bh-rail-floor'))) old.remove();
  const n = hello ? hello.levels : 1;
  floorRail.hidden = n <= 1;
  for (let z = n - 1; z >= 0; z--) {
    const d = z - surfaceZ();
    const b = document.createElement('button');
    b.className = 'bh-rail-floor' + (d === 0 ? ' is-surface' : '');
    b.dataset.z = String(z);
    b.textContent = d > 0 ? `+${d}` : d < 0 ? `−${-d}` : '0';
    b.setAttribute('aria-label', levelName(z));
    b.onclick = () => stepLevel(z - currentLevel);
    floorRail.append(b);
  }
}

/**
 * One floor up or down. It used to be a single button that stepped down and
 * wrapped back to the top, which made the floor above you a full lap away; a
 * pair says which way you are going and stops at the ends instead of wrapping.
 */
function stepLevel(dz: number): void {
  if (!hello || hello.levels <= 1 || dz === 0) return;
  // Changing level by hand ends the follow. Otherwise the two fight: the button
  // moves the viewer, the followed creature's next poll drags it straight back,
  // and the button looks broken. Asking for a floor outranks being glued to
  // something on a different one. The selection stays — only the camera lets go.
  cam.followId = null;
  goToLevel(currentLevel + dz);
}
levelUp.onclick = () => stepLevel(1);
levelDown.onclick = () => stepLevel(-1);
// ---- the drone button ------------------------------------------------------
// Four machines parked on charge pads in a base buried somewhere in the rock,
// on a floor the viewer is probably not watching. Nothing on screen said they
// existed: the entity stream carries only the level being watched, so from the
// surface the client cannot see a drone, and the minimap — which draws what the
// client has — cannot show one either. Finding one meant cycling levels by hand
// and hunting a two-pixel dot that looks exactly like the loader and the crates
// beside it. This is the button that does that for you.

interface DroneMark { id: number; x: number; y: number; z: number }
let droneRank: DroneMark[] = [];

/** The rank, newest answer wins. Cheap — four rows — and asked again on every
 *  press rather than cached, because a drone can be culled and the world can be
 *  reset underneath a stale list, and a button that walks you to a body that is
 *  no longer there is worse than no button. */
async function fetchDroneRank(): Promise<void> {
  try {
    const r = await fetch('/api/world/drones');
    droneRank = r.ok ? await r.json() : [];
  } catch {
    /* transient: keep whatever we had */
  }
  droneBtn.hidden = !droneRank.length;
  droneLabel();
}

/** Where the currently followed body sits in the rank, or -1. Asking the camera
 *  rather than keeping a counter is what makes the cycle behave: tap a drone by
 *  hand, press the button, and it goes to the NEXT one — a private index would
 *  send you back to wherever the button had got to on its own. */
function droneIndex(): number {
  return cam.followId === null ? -1 : droneRank.findIndex(d => d.id === cam.followId);
}

/** The label carries which of the rank is being followed, so the cycle says
 *  where it has got to. Driven from the frame loop rather than from the press:
 *  the follow ends in several places that have no reason to know about this
 *  button — a pan, a death, a tap on open ground — and a counter still naming a
 *  drone the camera let go of is a button lying about what it will do next.
 *  Writes only on change, so it is a string compare per frame and no DOM work. */
function droneLabel(): void {
  const i = droneIndex();
  const want = i < 0 ? 'drone' : `drone ${i + 1}/${droneRank.length}`;
  if (droneBtn.textContent !== want) droneBtn.textContent = want;
}

droneBtn.onclick = async () => {
  await fetchDroneRank();
  if (!droneRank.length) return;
  const next = droneRank[(droneIndex() + 1) % droneRank.length];
  goToLevel(next.z); // it is almost never on the floor you are watching
  cam.cx = next.x;
  cam.cy = next.y;
  // Only ever zoom IN. Opening on a fitted whole world puts the camera at a few
  // pixels per tile, where a drone is one speck among the base's other specks
  // and arriving is indistinguishable from not arriving; but someone who zoomed
  // in deliberately should keep the zoom they chose.
  if (cam.scale < 32) cam.scale = 32;
  select(next.id, true);
  droneLabel();
};

function syncPauseBtn(): void {
  pauseBtn.textContent = paused ? 'resume' : 'pause';
  pauseBtn.classList.toggle('on', paused);
}

// ---- sense overlays (open to everyone) --------------------------------------
// Two heatmaps over the live world — smell (pheromone clouds) and sound
// (events in earshot) — behind one toolbar button. Viewing only: they read
// the entity stream every client already receives, so there is nothing to
// gate. The choices stick in localStorage like any other viewer convenience.
const ovlBtn = document.getElementById('ovlBtn') as HTMLButtonElement;
const ovlEl = document.getElementById('ovl')!;
const ovlSmellBox = document.getElementById('ovlSmell') as HTMLInputElement;
const ovlSoundBox = document.getElementById('ovlSound') as HTMLInputElement;
let ovlSmell = false;
let ovlSound = false;
try {
  ovlSmell = localStorage.getItem('ovlSmell') === '1';
  ovlSound = localStorage.getItem('ovlSound') === '1';
} catch { /* storage may be unavailable; the toggles still work per-session */ }
ovlSmellBox.checked = ovlSmell;
ovlSoundBox.checked = ovlSound;
// The climate field, one at a time: three full-tile washes over each other
// would be unreadable, so it is a choice with an off, not three toggles.
// The choice sticks like the toggles do.
const climBoxes = Array.from(document.querySelectorAll<HTMLInputElement>('input[name="clim"]'));
const climLegendEl = document.getElementById('climLegend')!;
let climChoice: ClimateField | null = null;
try {
  const saved = localStorage.getItem('climField');
  if (saved === 'light' || saved === 'temperature' || saved === 'humidity') climChoice = saved;
} catch { /* per-session only, then */ }
for (const b of climBoxes) b.checked = (b.value || null) === climChoice;
function climLegend(): void {
  if (!climChoice || !climRange) { climLegendEl.textContent = ''; return; }
  const { min, max } = climRange;
  climLegendEl.textContent = climChoice === 'temperature'
    ? `${min.toFixed(1)} – ${max.toFixed(1)} °C`
    : `${min.toFixed(2)} – ${max.toFixed(2)}`;
}
function climApply(): void {
  const on = climBoxes.find(b => b.checked);
  const v = on ? on.value : '';
  climChoice = v === 'light' || v === 'temperature' || v === 'humidity' ? v : null;
  ovlBtn.classList.toggle('lit', !!(ovlSmell || ovlSound || climChoice));
  try { localStorage.setItem('climField', climChoice ?? ''); } catch { /* per-session only */ }
  fetchClimate();
}
for (const b of climBoxes) b.onchange = climApply;
function ovlApply(): void {
  ovlSmell = ovlSmellBox.checked;
  ovlSound = ovlSoundBox.checked;
  ovlBtn.classList.toggle('lit', !!(ovlSmell || ovlSound || climChoice));
  try {
    localStorage.setItem('ovlSmell', ovlSmell ? '1' : '0');
    localStorage.setItem('ovlSound', ovlSound ? '1' : '0');
  } catch { /* per-session only, then */ }
}
ovlSmellBox.onchange = ovlApply;
ovlSoundBox.onchange = ovlApply;
ovlApply();
// A menu, not a panel: two checkboxes, sized by what they are. The button
// lights while the menu is open (as every toolbar button does) and wears its
// pilot lamp while an overlay is actually painting, which outlives the menu.
function closeOvl(): void {
  closePanel(ovlEl);
  ovlBtn.classList.remove('on');
}
ovlBtn.onclick = () => {
  if (ovlEl.classList.contains('open')) {
    closeOvl();
  } else {
    openPanel(ovlEl);
    ovlBtn.classList.add('on');
  }
};
wirePanelChrome(ovlEl, closeOvl);
rightColumn.push({ el: ovlEl, close: closeOvl });

// ---- the constants panel (debug mode) --------------------------------------
// Every simulation constant, live from GET /api/tuning: frozen ones (structural
// anchors, values copied out at world build) render as read-only sliders; the
// tunable ones become editable when the viewer holds the command token (the
// same admin gate as spawn/reset/inject). "save & close" diffs the panel
// against the values it loaded and POSTs only the changes — each lands as its
// own logged tune command at a tick boundary, so replays re-tune themselves.
const tuneBtn = document.getElementById('tuneBtn') as HTMLButtonElement;
const tuningEl = document.getElementById('tuning')!;
const tuneBody = document.getElementById('tuneBody')!;
const tuneFoot = document.getElementById('tuneFoot')!;

type TuneRow = { key: string; loaded: number; input: HTMLInputElement; row: HTMLElement };
let tuneRows: TuneRow[] = [];

let tuneEpoch = 0; // bumped per open and close: a slow load lands only on its own panel
function closeTuning(): void {
  tuneEpoch++; // a load still in flight now has no panel to fill
  closePanel(tuningEl);
  tuneBtn.classList.remove('on');
  tuneRows = [];
}
wirePanelChrome(tuningEl, closeTuning);
rightColumn.push({ el: tuningEl, close: closeTuning });

/** Slider bounds around the boot default: room to quadruple, floor at zero. */
function tuneMax(def: number, value: number): number {
  return Math.max(Math.abs(def) * 4, Math.abs(value) * 1.25, 1e-6);
}

async function openTuning(): Promise<void> {
  const epoch = ++tuneEpoch;
  openPanel(tuningEl);
  tuneBtn.classList.add('on');
  tuneBody.textContent = 'loading…';
  tuneFoot.textContent = '';
  tuneRows = [];
  let constants: { key: string; value: number; def: number; unit: string; type: string; frozen: boolean }[];
  try {
    const r = await fetch('/api/tuning');
    const d = await r.json();
    if (epoch !== tuneEpoch) return; // closed and reopened while this was in flight
    if (!r.ok || !d.ok) throw new Error();
    constants = d.constants;
  } catch {
    if (epoch === tuneEpoch) tuneBody.textContent = 'failed to load /api/tuning';
    return;
  }
  const editable = !net.readOnly;
  tuneBody.textContent = '';
  let group = '';
  for (const c of constants) {
    const cls = c.key.split('.')[0];
    if (cls !== group) {
      group = cls;
      const g = document.createElement('div');
      g.className = 'grp';
      g.textContent = cls;
      tuneBody.append(g);
    }
    const row = document.createElement('div');
    row.className = 'trow';
    const name = document.createElement('span');
    name.className = 'tname';
    name.textContent = c.key.split('.')[1];
    name.title = `${c.key} — default ${c.def} ${c.unit}`;
    row.append(name);

    const canEdit = editable && !c.frozen;
    const num = document.createElement('input');
    num.setAttribute('aria-label', c.key); // the name cell is a span, not a <label>
    if (c.type === 'boolean') {
      num.type = 'checkbox';
      num.className = 'bh-box';
      num.checked = c.value !== 0;
      num.disabled = !canEdit;
      row.append(num);
    } else {
      // The pair: a slider for the hand, a number for precision — same value.
      const slider = document.createElement('input');
      slider.type = 'range';
      slider.className = 'bh-range';
      // Symmetric about zero for a constant that is, or defaults, negative: a
      // floor of 0 put its own value off the end of the track.
      slider.min = c.def < 0 || c.value < 0 ? String(-tuneMax(c.def, c.value)) : '0';
      slider.max = String(tuneMax(c.def, c.value));
      slider.step = String(tuneMax(c.def, c.value) / 400);
      slider.value = String(c.value);
      slider.disabled = !canEdit;
      slider.setAttribute('aria-label', `${c.key} slider`);
      num.type = 'number';
      num.className = 'bh-input bh-input--sm';
      num.step = 'any';
      num.value = String(c.value);
      num.disabled = !canEdit;
      const mark = () => row.classList.toggle('changed', Number(num.value) !== c.value);
      slider.oninput = () => { num.value = slider.value; mark(); };
      num.oninput = () => { slider.value = num.value; mark(); };
      row.append(slider, num);
    }
    const unit = document.createElement('span');
    unit.className = 'tunit';
    unit.textContent = c.unit;
    unit.title = c.unit;
    row.append(unit);
    const lock = document.createElement('span');
    lock.className = 'lock';
    lock.textContent = c.frozen ? '🔒' : '';
    lock.title = c.frozen ? 'structural — fixed at build, read-only' : '';
    row.append(lock);
    tuneBody.append(row);
    if (canEdit) tuneRows.push({ key: c.key, loaded: c.value, input: num, row });
  }
  if (editable) {
    const note = document.createElement('span');
    note.className = 'note';
    note.textContent = '🔒 = structural, read-only';
    const save = document.createElement('button');
    save.className = 'bh-btn bh-btn--primary bh-btn--sm';
    save.textContent = 'save & close';
    save.onclick = saveTuning;
    tuneFoot.append(note, save);
  } else {
    const note = document.createElement('span');
    note.className = 'note';
    note.textContent = 'read-only — a command token (#t=…) unlocks editing';
    tuneFoot.append(note);
  }
}

async function saveTuning(): Promise<void> {
  const changes: Record<string, number> = {};
  for (const r of tuneRows) {
    const v = r.input.type === 'checkbox' ? (r.input.checked ? 1 : 0) : Number(r.input.value);
    if (Number.isFinite(v) && v !== r.loaded) changes[r.key] = v;
  }
  if (Object.keys(changes).length === 0) { closeTuning(); toast('no changes'); return; }
  try {
    const r = await fetch('/api/tuning', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Command-Token': net.commandToken },
      body: JSON.stringify(changes),
    });
    const d = await r.json().catch(() => ({}));
    if (r.ok && d.ok) { closeTuning(); toast(`tuned ${d.applied} constant${d.applied === 1 ? '' : 's'}`); }
    else if (r.status === 403) toast('tuning refused: bad token');
    else toast(`tuning failed: ${d.error ?? r.status}`);
  } catch {
    toast('tuning failed');
  }
}

tuneBtn.onclick = () => {
  if (tuningEl.classList.contains('open')) closeTuning(); else void openTuning();
};

// Debug mode is a CLIENT-ONLY view: it changes what's rendered and how much a
// selection reveals, never how the server behaves. (The constants panel it
// reveals can change the world — but only through the token gate, which is the
// admin boundary; debug alone still only looks.) Enable it by tapping the tick
// readout three times, pressing "d" (desktop), or opening with ?debug=true. When
// on, the inspector opens as the full debug panel for whatever is selected
// rather than the plain card. It does NOT change what the gestures mean: a tap
// selects and a long press inspects the ground, in either mode.
let debugOn = new URLSearchParams(location.search).get('debug') === 'true';
function applyDebug(): void {
  // Re-render whatever is open in the new context (simple card <-> full dump).
  if (selectedId !== null) refreshDetail();
  statsEl.title = debugOn ? 'debug on — tap 3× to turn off' : 'tap 3× for debug';
  tuneBtn.hidden = !debugOn;
  if (!debugOn) closeTuning();
}
function setDebug(on: boolean): void {
  if (debugOn === on) return;
  debugOn = on;
  // Tile inspection is no longer debug-only — a long press reaches it in any
  // mode — so leaving debug must not tear down a selection the user made.
  applyDebug();
  syncMindPoll();
  toast(`debug mode ${debugOn ? 'on' : 'off'}`);
}
// Triple-tap the tick readout toggles debug — deliberate, so a stray tap on the
// status line never flips it. The "d" key does the same on desktop.
let tickTaps = 0;
let tickTapTimer = 0;
statsEl.title = 'tap 3× for debug';
statsEl.addEventListener('click', () => {
  clearTimeout(tickTapTimer);
  if (++tickTaps >= 3) {
    tickTaps = 0;
    setDebug(!debugOn);
    return;
  }
  tickTapTimer = window.setTimeout(() => (tickTaps = 0), MS.tripleTap);
});
window.addEventListener('keydown', ev => {
  if ((ev.key === 'd' || ev.key === 'D') && isShortcut(ev)) setDebug(!debugOn);
});
applyDebug(); // reflect ?debug=true on load

let toastTimer = 0;
function toast(msg: string): void {
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toastEl.classList.remove('show'), MS.toast);
}

// ---- frame loop ------------------------------------------------------------

// Test/debug handle: lets an automated browser (or a curious dev console)
// read live state and camera without any UI coupling.
(window as unknown as Record<string, unknown>).__blu = { cam, state, select }; // select: a harness's way to open an inspector

// ---- perf HUD ('h' key or ?hud=1): which clock is actually slipping? ------
const hudEl = document.getElementById('hud') as HTMLElement;
let hudOn = flagOn('hud');
let hudGl = { drawCalls: 0, quads: 0, uploadMs: 0, textures: 0,
  secLayers: 0, secEnts: 0, secTail: 0 };
let hudUpMax = 0; // worst texture-upload frame since the HUD last redrew
let hudFrameEma = 16.7;
let hudCbEma = 0; // JS spent INSIDE the rAF callback — the part of the frame that is ours
let hudLastFrame = 0;
let hudLastText = 0;
let hudTick = ''; // sim load, polled while the HUD is open
let hudTickAt = 0;
// Main-thread stalls >50ms from ANY source — GC, stream parsing, layout — via
// the Long Tasks API. The section timers only see inside the render call;
// this is the net that catches everything else the frame budget bleeds into.
let hudLongN = 0;
let hudLongMax = 0;
try {
  new PerformanceObserver((list) => {
    for (const e of list.getEntries()) {
      hudLongN++;
      hudLongMax = Math.max(hudLongMax, e.duration);
    }
  }).observe({ type: 'longtask' });
} catch { /* API missing (e.g. Safari): the HUD line just stays 0 */ }
window.addEventListener('keydown', (ev) => {
  if (ev.key === 'h' && isShortcut(ev)) {
    hudOn = !hudOn;
    hudEl.hidden = !hudOn;
  }
});
hudEl.hidden = !hudOn;

// ---- the ⚙ settings dialog -------------------------------------------------
// Checkboxes for the visual subsystems and the optional panels, applied by
// reloading with the matching URL params (each perf flag is a load-time
// constant, so a reload keeps the render paths branch-free).
//
// Always in the bar. It used to appear only once the perf HUD was open, which
// made every setting in here reachable only by someone who already knew to
// press 'h' or to hand-edit the query string — including the two toggles that
// are not about performance at all. A settings button you have to know a
// keystroke to find is not a settings button.
if (MINIMAP_OFF) mm.hidden = true;
/** Whether a panel is showing right now. Live state, since both can be toggled
 *  by key; falls back to the query string for anything not wired to a variable. */
function viewFlagOn(k: string): boolean {
  if (k === 'pop') return popOn;
  if (k === 'hud') return hudOn;
  return flagOn(k);
}

const perfBtn = document.createElement('button');
perfBtn.className = 'bh-btn bh-btn--sm bh-btn--icon';
perfBtn.textContent = '⚙';
perfBtn.title = 'settings (reloads with url flags)';
perfBtn.setAttribute('aria-label', 'settings');
document.getElementById('barSettings')!.append(perfBtn); // its own group, last
/** The settings dialog, as a Bulkhead panel: a header with its close, two groups
 *  of rivet checkboxes, and the commit in the footer. */
function closePerfDlg(): boolean {
  const old = document.getElementById('perfdlg');
  if (!old) return false;
  old.remove();
  perfBtn.classList.remove('on');
  return true;
}
perfBtn.onclick = () => {
  if (closePerfDlg()) return;
  const dlg = document.createElement('div');
  dlg.id = 'perfdlg';
  dlg.className = 'bh-panel';
  dlg.setAttribute('role', 'dialog');
  dlg.setAttribute('aria-label', 'settings');
  const head = document.createElement('header');
  const h = document.createElement('h4');
  h.textContent = 'settings';
  const x = document.createElement('button');
  x.className = 'bh-btn bh-btn--icon bh-btn--sm';
  x.textContent = '✕';
  x.setAttribute('aria-label', 'close');
  x.onclick = () => { closePerfDlg(); };
  const acts = document.createElement('div');
  acts.className = 'bh-actions';
  acts.append(x);
  head.append(h, acts);
  const body = document.createElement('div');
  body.className = 'bh-body';
  const boxes = new Map<string, HTMLInputElement>();
  const groupOf = (title: string, flags: ReadonlyArray<readonly [string, string]>, on: (k: string) => boolean) => {
    const fs = document.createElement('fieldset');
    fs.className = 'bh-fieldset';
    const lg = document.createElement('legend');
    lg.className = 'bh-label';
    lg.textContent = title;
    fs.append(lg);
    for (const [k, label] of flags) {
      const row = document.createElement('label');
      row.className = 'bh-check';
      const cb = document.createElement('input');
      cb.type = 'checkbox';
      cb.checked = on(k);
      boxes.set(k, cb);
      const span = document.createElement('span');
      span.textContent = label;
      row.append(cb, span);
      fs.append(row);
    }
    body.append(fs);
  };
  groupOf('visual subsystems', PERF_FLAGS, k => !flagOff(k));
  // From LIVE state, not the URL: both panels can be toggled by key without a
  // reload, so reading the query string here would show a stale box and then
  // silently undo the toggle on apply.
  groupOf('panels', VIEW_FLAGS, k => viewFlagOn(k));
  const foot = document.createElement('footer');
  const cancel = document.createElement('button');
  cancel.className = 'bh-btn bh-btn--ghost';
  cancel.textContent = 'cancel';
  cancel.onclick = () => { closePerfDlg(); };
  const apply = document.createElement('button');
  apply.className = 'bh-btn bh-btn--primary';
  apply.textContent = 'apply & reload';
  apply.onclick = () => {
    const sp = new URLSearchParams(location.search);
    for (const [k] of PERF_FLAGS) {
      if (boxes.get(k)!.checked) sp.delete(k); // default-on: absent = enabled
      else sp.set(k, '0');
    }
    for (const [k] of VIEW_FLAGS) {
      if (boxes.get(k)!.checked) sp.set(k, '1'); // default-off: present = enabled
      else sp.delete(k);
    }
    location.search = sp.toString();
  };
  foot.append(cancel, apply);
  dlg.append(head, body, foot);
  document.body.append(dlg);
  perfBtn.classList.add('on');
  apply.focus();
};

function hudFrame(now: number): void {
  if (hudLastFrame > 0) hudFrameEma += (Math.min(200, now - hudLastFrame) - hudFrameEma) * 0.08;
  hudLastFrame = now;
  hudUpMax = Math.max(hudUpMax, hudGl.uploadMs);
  // Structured mirror of the HUD for the automated benchmark (client/bench):
  // numbers, not display text, so the harness never parses a string. Published
  // every frame whether or not the HUD is visible.
  (window as unknown as Record<string, unknown>).__bluPerf = {
    renderer: glr ? 'webgl' : 'canvas2d',
    frameMs: hudFrameEma,
    drawCalls: hudGl.drawCalls, quads: hudGl.quads, uploadMs: hudGl.uploadMs,
    secLayers: hudGl.secLayers, secEnts: hudGl.secEnts, secTail: hudGl.secTail,
    streamMs: Net.streamStats.msgMs, streamKb: Net.streamStats.msgKb, msgs: Net.streamStats.count,
    tex: hudGl.textures, atlases: atlasCount(),
    onLevel: state.tracks.size, world: worldTotal,
    cbMs: hudCbEma, longN: hudLongN, longMax: hudLongMax,
    scale: cam.scale, firstFullMs: firstFullAt, now,
  };
  if (!hudOn || now - hudLastText < MS.hudText) return;
  hudLastText = now;
  // Capture-and-reset the since-last-redraw maxima BEFORE building the text —
  // and print the captured values. (The first version printed the variable it
  // had just zeroed, so the HUD swore uploads were 0.0ms no matter what.)
  const upMax = hudUpMax; hudUpMax = 0;
  const longN = hudLongN, longMax = hudLongMax; hudLongN = 0; hudLongMax = 0;
  if (now - hudTickAt > 3000) {
    hudTickAt = now;
    fetch('/api/health').then(r => r.json()).then(h => {
      hudTick = `tick ${h.tickMillis}/${h.tickBudgetMillis}ms` + (h.keepingUp ? '' : ' LAGGING');
    }).catch(() => { hudTick = ''; });
  }
  const net2 = Net.streamStats;
  hudEl.textContent = `${glr ? 'webgl' : 'canvas2d'}${perfOffStr ? ` · OFF: ${perfOffStr}` : ''}`
    + ` · ${(1000 / hudFrameEma).toFixed(0)} fps`
    + ` (${hudFrameEma.toFixed(1)} ms · cb ${hudCbEma.toFixed(1)})`
    + (glr ? `\n${hudGl.drawCalls} draw calls · ${hudGl.quads} quads · up ${upMax.toFixed(1)}ms` : '')
    + (glr ? `\njs: layers ${hudGl.secLayers.toFixed(1)} · ents ${hudGl.secEnts.toFixed(1)}`
      + ` · tail ${hudGl.secTail.toFixed(1)}ms` : '')
    + `\nstream ${net2.msgMs.toFixed(1)}ms/${net2.msgKb.toFixed(0)}kb`
    + ` · tex ${hudGl.textures} · atlases ${atlasCount()}`
    + `\nlong ${longN}×/${longMax.toFixed(0)}ms`
    + ` · ${state.tracks.size} on level · ${worldTotal} world`
    + (hudTick ? `\n${hudTick}` : '');
}

let lastStats = 0;
let lastMini = 0;
let miniCx = NaN, miniCy = NaN, miniScale = NaN;
let lastFrameAt = 0;
function frame(now: number): void {
  const cb0 = performance.now();
  advanceGait(lastFrameAt ? now - lastFrameAt : 0, paused ? 0 : (parseFloat(speedSel.value) || 1));
  lastFrameAt = now;
  // A fixed fraction behind the interval the stream is actually sending at:
  // the server halves its rate for a big world, and a delay tuned to 10 Hz
  // would run out of interpolation and hold every body still for the second
  // half of each 200 ms gap.
  const renderTime = now - Math.max(RENDER_DELAY_MS, 1.5 * Net.streamStats.intervalMs);

  // Follow: glue the camera to the tracked creature's interpolated position.
  if (cam.followId !== null) {
    const t = state.tracks.get(cam.followId);
    if (t) {
      const p = state.sample(t, renderTime);
      cam.cx = p.x;
      cam.cy = p.y;
    } else if (cam.followId !== selectedId) {
      // Nothing is polling this one, so there is no way to learn where it went.
      cam.followId = null;
    }
    // Otherwise hold the camera and keep following. A track can vanish because
    // the creature died OR because it walked to another level and fell out of a
    // stream filtered to this one; this loop cannot tell which, and it used to
    // guess "died" and drop the follow. The detail poll knows, and answers
    // within a second — it either takes us to the new level or deselects.
  }

  const chunkPx = hello ? (hello.chunkPx ?? hello.tileSize) : 0;
  const sel = { id: selectedId, tile: selectedTile };
  if (glr && glLost) {
    g.clearRect(0, 0, g.canvas.width, g.canvas.height); // the world waits for its context
  } else if (glr) {
    hudGl = renderGL(glr, g, cam, state, meta, chunkTiles, chunkPx, getChunk,
      vegGrid, vegVersion, coverGrid, renderTime, now, currentLevel, sel);
  } else {
    render(g, cam, state, meta, chunkTiles, chunkPx, getChunk,
      vegGrid, vegVersion, coverGrid, renderTime, now, currentLevel, sel);
  }
  if (waterGrid && meta) {
    drawWaterGlints(g, cam, waterGrid, meta.cols, meta.rows, state.tick);
  }
  if (climChoice && climGrid && climRange && meta) {
    drawClimateLayer(g, cam, climChoice, climGrid, climRange, meta.cols, meta.rows);
  }
  if (ovlSmell || ovlSound) {
    drawSenseHeat(g, cam, state, renderTime, currentLevel, ovlSmell, ovlSound, now);
  }
  hudFrame(now);
  // The minimap is ambient, not mission-critical: entity drift redraws at
  // 4 Hz, but a camera move redraws at once so the viewport rectangle never
  // visibly lags a pan or zoom.
  if (!MINIMAP_OFF && meta && (now - lastMini > 250
      || cam.cx !== miniCx || cam.cy !== miniCy || cam.scale !== miniScale)) {
    lastMini = now;
    miniCx = cam.cx; miniCy = cam.cy; miniScale = cam.scale;
    drawMinimap(mm, cam, state, meta, cv, currentLevel);
  }

  if (net.status === 'open' && now - lastStats > 250) {
    lastStats = now;
    // The seed is its own span so a phone can drop it (index.html): beside the
    // minimap there is room for the tick and the count, not for all three.
    statsEl.innerHTML = `tick ${state.tick} · ${worldTotal || state.tracks.size} entities` +
        (hello ? `<span class="stat-seed"> · seed ${Number(hello.seed)}</span>` : '');
    droneLabel();
  }
  // Our whole share of the frame: everything above, GL submission included.
  // A big gap between this and the frame interval is time spent OUTSIDE this
  // callback — compositing, GPU, GC, or the browser pacing rAF down.
  hudCbEma += (performance.now() - cb0 - hudCbEma) * 0.08;
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// The polls skip a hidden tab: a phone in a pocket fetched vegetation, the
// inspector and the chart for nobody. Coming back catches the ground up at
// once rather than on the next beat.
document.addEventListener('visibilitychange', () => { if (!document.hidden && meta) void pollVeg(); });
