// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import {
  ART_RADIUS, BAYER4, CELL, MIP, RIM_COLOUR, atlasFor, atlasMipFor, cell, corpseFor,
  corpseMipFor, decayStage, headingCol, mindedFor, mindedMipFor, tintedFor,
} from './atlas';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_FLEE, ACT_GRAB, ACT_GRAZE, ACT_HUNT, ACT_MATE,
  ACT_NEST, actionOf, F_CARRYING, F_DEAD, F_GRABBED, F_MINDED,
} from './protocol';
import type { EntityState } from './protocol';
import type { Track, WorldState } from './state';

export interface WorldMeta { cols: number; rows: number; }

/** Dither masks for the 16 depletion coverage levels: opaque where the Bayer
 *  threshold admits the bare bake, transparent where lush ground survives.
 *  Art-pixel resolution — a tile is 12 art-px a side and the pattern period
 *  is 4, so one 12x12 mask tiles every world tile in phase with the bake's
 *  own dithers. Built lazily, cached forever. */
const MASKS: HTMLCanvasElement[] = [];
function ditherMask(coverage16: number): HTMLCanvasElement {
  let m = MASKS[coverage16];
  if (!m) {
    m = document.createElement('canvas');
    m.width = 12;
    m.height = 12;
    const mg = m.getContext('2d')!;
    mg.fillStyle = '#fff';
    for (let y = 0; y < 12; y++) {
      for (let x = 0; x < 12; x++) {
        if (BAYER4[(y & 3) * 4 + (x & 3)] < coverage16) mg.fillRect(x, y, 1, 1);
      }
    }
    MASKS[coverage16] = m;
  }
  return m;
}

/** Scratch tile for compositing one bare-bake tile under a dither mask. */
const SCRATCH = document.createElement('canvas');

/**
 * Composite one tile of the bare (fully-grazed) bake through the depletion
 * dither mask into `ctx` — the ONE way grazing is allowed to show (a state is
 * another bake, dithered per art-pixel; ART-STYLE.md case law). Exported so
 * /sprites runs the identical compositing beside the Java depletion strip.
 */
export function ditherTile(ctx: CanvasRenderingContext2D, src: CanvasImageSource,
    sx: number, sy: number, tilePx: number,
    dx: number, dy: number, dw: number, dh: number, depl16: number): void {
  if (SCRATCH.width !== tilePx) { SCRATCH.width = tilePx; SCRATCH.height = tilePx; }
  const sg = SCRATCH.getContext('2d')!;
  sg.clearRect(0, 0, tilePx, tilePx);
  sg.drawImage(src, sx, sy, tilePx, tilePx, 0, 0, tilePx, tilePx);
  sg.globalCompositeOperation = 'destination-in';
  sg.imageSmoothingEnabled = false;
  sg.drawImage(ditherMask(depl16), 0, 0, tilePx, tilePx);
  sg.globalCompositeOperation = 'source-over';
  ctx.drawImage(SCRATCH, 0, 0, tilePx, tilePx, dx, dy, dw, dh);
}

// The depletion layer: the whole level's grazing overlay composited ONCE per
// vegetation update (the grid polls every ~1.5 s) into an offscreen canvas at
// art-pixel resolution (12 px per tile), then blitted in a single drawImage
// per frame. The naive per-frame path drew one scaled drawImage per depleted
// tile — with a thriving ecosystem keeping ~1000 tiles grazed, that was
// ~60k image draws a second and the fit view ran at one frame per second.
// At 12 px/tile, one layer pixel is exactly one art-pixel: upscaled crisply
// it matches the per-tile dither, downscaled with smoothing it resolves to
// the same coverage the old far-zoom alpha path approximated.
let deplLayer: HTMLCanvasElement | null = null;
// Far-zoom mip of the layer (3 px per tile), rebuilt with it: one smooth
// downscale per vegetation update instead of one per frame — a full-layer
// smooth blit every frame is exactly the kind of work that melts a software
// canvas (the desktop renderer keeps a downsized pyramid for the same reason).
let deplLayerLow: HTMLCanvasElement | null = null;
let deplSrc: Uint8Array | null = null; // the veg grid the layer was built from
let deplLevel = -1;
let deplMissing = 0; // bare chunks still streaming when last built
let deplRetryAt = 0;
// The depletion bucket (0..16) each tile is CURRENTLY painted at, so a veg
// update only repaints tiles whose bucket actually moved. Grazing shifts a
// handful of tiles per poll; repainting all ~1000 depleted tiles on every
// poll was a repeated whole-layer rebuild — a visible hitch every 1.5 s once
// the herds grew. -1 marks a tile that still needs paint (its chunk was not
// streamed in yet), which never equals a real bucket, so the retry pass
// naturally picks exactly those up.
let deplBuckets: Int16Array | null = null;
// The tile rects the last depletion pass repainted (null = full rebuild), so
// the GL path can patch its texture instead of re-uploading the whole layer.
let deplPatchRects: Array<[number, number, number, number]> | null = null;
// Tiles repainted since the GPU copy last reconciled. Normally each poll's
// handful ships as texSubImage2D patches, but a big herd can graze more tiles
// per poll than patching beats (>256), which forces a whole-layer re-upload —
// multi-millisecond on weak GPUs. Those bulk uploads are rate-limited: while
// the window is closed the repaints accumulate here (null = overflowed, a
// bulk is owed) and the GPU copy runs up to BULK_MIN_MS stale, which on a
// slow-moving vegetation field nobody can see. Level switches bypass this —
// they take the `full` path, which always ships immediately.
let deplPending: number[] | null = [];
let deplBulkAt = 0;
const DEPL_BULK_MIN_MS = 3000;
const ART = 12;

// The repaint QUEUE: tiles whose bucket moved, waiting for their ditherTile.
// A herd's grazing plus world-wide REGROWTH can move thousands of buckets per
// vegetation poll on an old world, and repainting them all in the poll's
// frame was a 60-130ms main-thread stall — every 1.5s, surface only (caves
// have no vegetation), which is exactly the "surface lags, underground is
// fine" signature. Repaints now drain at a frame budget; the dither runs a
// few frames behind the data, which on a field that changes over minutes is
// invisible. The budget rises while the queue is deep (a fresh level's whole
// depleted field) so the initial paint takes a moment, not a freeze.
let deplQueue: number[] = [];
let deplQHead = 0;
let deplQueued: Uint8Array | null = null;
const DEPL_TILES_PER_FRAME = 96;
// A queue this deep is a whole level's backlog (fresh join, level switch),
// not steady churn — drain faster so the field paints in a couple of
// seconds. Kept modest: a rush frame must still fit a phone's budget.
const DEPL_TILES_RUSH = 256;
const DEPL_RUSH_DEPTH = 8000;

function bucketOf(lvl: number): number {
  return (lvl >= 100 || lvl === 255) ? 0
    : Math.max(0, Math.min(16, Math.round((100 - lvl) * 16 / 100)));
}

function depletionLayer(meta: WorldMeta, chunkTiles: number, tilePx: number,
    getBareChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
    veg: Uint8Array, level: number, nowMs: number): HTMLCanvasElement | null {
  const full = !deplLayer || !deplBuckets || level !== deplLevel
    || deplLayer.width !== meta.cols * ART || deplLayer.height !== meta.rows * ART;
  // An owed bulk upload whose rate-limit window just opened re-enters even if
  // the vegetation itself brought nothing new this frame.
  const bulkDue = deplPending === null && nowMs - deplBulkAt >= DEPL_BULK_MIN_MS;
  const scanDue = full || veg !== deplSrc || (deplMissing > 0 && nowMs >= deplRetryAt);
  if (!scanDue && deplQHead >= deplQueue.length && !bulkDue) return deplLayer;
  if (full) {
    if (!deplLayer || deplLayer.width !== meta.cols * ART || deplLayer.height !== meta.rows * ART) {
      deplLayer = document.createElement('canvas');
      deplLayer.width = meta.cols * ART;
      deplLayer.height = meta.rows * ART;
    }
    deplLayer.getContext('2d')!.clearRect(0, 0, deplLayer.width, deplLayer.height);
    deplBuckets = new Int16Array(meta.cols * meta.rows); // all painted "lush"
    deplQueue = [];
    deplQHead = 0;
    deplQueued = new Uint8Array(meta.cols * meta.rows);
    deplLevel = level;
    deplRev++; // ship the cleared layer NOW; the dither drains in behind it
    deplPatchRects = null;
    deplPending = [];
    deplBulkAt = nowMs;
  }
  const layer = deplLayer!; // allocated above whenever it was missing or stale
  const ctx = layer.getContext('2d')!;
  ctx.imageSmoothingEnabled = false;
  const LOW = 3;
  if (!deplLayerLow || deplLayerLow.width !== meta.cols * LOW) {
    deplLayerLow = document.createElement('canvas');
    deplLayerLow.width = meta.cols * LOW;
    deplLayerLow.height = meta.rows * LOW;
    deplLayerLow.getContext('2d')!.clearRect(0, 0, deplLayerLow.width, deplLayerLow.height);
  }
  const lg = deplLayerLow.getContext('2d')!;
  lg.imageSmoothingEnabled = true;
  if (full) lg.clearRect(0, 0, deplLayerLow.width, deplLayerLow.height);
  if (scanDue) {
    // Cheap pass: FIND the moved buckets (integer compares over the grid);
    // the expensive painting is what the queue meters out.
    deplMissing = 0;
    for (let i = 0; i < deplBuckets!.length; i++) {
      if (bucketOf(veg[i]) !== deplBuckets![i] && !deplQueued![i]) {
        deplQueued![i] = 1;
        deplQueue.push(i);
      }
    }
    deplSrc = veg;
    deplRetryAt = nowMs + 1000; // if chunks were missing, look again shortly
  }
  const budget = deplQueue.length - deplQHead > DEPL_RUSH_DEPTH
    ? DEPL_TILES_RUSH : DEPL_TILES_PER_FRAME;
  const patched: number[] = []; // tiles repainted THIS frame
  let work = 0;
  while (work < budget && deplQHead < deplQueue.length) {
    const i = deplQueue[deplQHead++];
    deplQueued![i] = 0;
    const bucket = bucketOf(veg[i]); // re-read: the field moved on since enqueue
    if (bucket === deplBuckets![i]) continue;
    const tx = i % meta.cols, ty = Math.floor(i / meta.cols);
    ctx.clearRect(tx * ART, ty * ART, ART, ART);
    if (bucket > 0) {
      const ccx = Math.floor(tx / chunkTiles), ccy = Math.floor(ty / chunkTiles);
      const bare = getBareChunk(ccx, ccy);
      if (!bare) { deplMissing++; deplBuckets![i] = -1; work++; continue; }
      ditherTile(ctx, bare, (tx - ccx * chunkTiles) * tilePx, (ty - ccy * chunkTiles) * tilePx,
        tilePx, tx * ART, ty * ART, ART, ART, bucket);
    }
    deplBuckets![i] = bucket;
    lg.clearRect(tx * LOW, ty * LOW, LOW, LOW);
    lg.drawImage(layer, tx * ART, ty * ART, ART, ART, tx * LOW, ty * LOW, LOW, LOW);
    patched.push(i);
    work++;
  }
  if (deplQHead >= deplQueue.length) {
    deplQueue = [];
    deplQHead = 0;
  }
  if (patched.length > 0 || deplPending === null) {
    if (deplPending !== null) {
      for (const i of patched) deplPending.push(i);
      if (deplPending.length > 256) deplPending = null; // overflow: a bulk is owed
    }
    if (deplPending !== null) {
      deplRev++; // small delta: ships as texSubImage2D patches
      deplPatchRects = deplPending.map(i =>
        [(i % meta.cols) * ART, Math.floor(i / meta.cols) * ART, ART, ART]);
      deplPending = [];
    } else if (nowMs - deplBulkAt >= DEPL_BULK_MIN_MS) {
      deplRev++; // the owed whole-layer upload, at most once per window
      deplPatchRects = null;
      deplPending = [];
      deplBulkAt = nowMs;
    }
    // else: hold — the GPU copy stays a beat stale until the window opens.
  }
  return layer;
}

// The whole ground at art-pixel resolution (12 px/tile), assembled once per
// level from the chunk bakes with a nearest 4:1 downscale — the bake draws
// art-pixels as 4x4 blocks, so this is the EXACT art, just not oversampled.
// Far zoom blits this instead of every chunk: downscaling the 48 px/tile
// chunks each frame strides across ~16x more source memory than the art
// actually holds, which is what made the fit-zoom ground the single most
// expensive section on a software canvas.
let groundCv: HTMLCanvasElement | null = null;
// Quarter-res mirror (3 px/tile) the GL pass samples at far zoom: the full
// layer has NO mipmaps (it gets patched), and a phone GPU minifying a
// 7-megapixel mipless texture 4-8x every frame is bandwidth-bound — the
// classic mobile fill cost a desktop GPU never shows.
let groundLowCv: HTMLCanvasElement | null = null;
let groundLevel = -1;
// Chunks that had not streamed in when the layer was built. Retries fill
// ONLY these holes and ship them as texture patches: the old path recomposed
// the WHOLE layer and re-uploaded the whole texture once a second for as
// long as anything was missing — with a permanently failed chunk fetch that
// was a 60ms+ CPU stall every second for the life of the tab.
let groundHoles: Array<[number, number]> = [];
let groundRetryAt = 0;
let groundPatchRects: Array<[number, number, number, number]> | null = null;
const LAYER_LOW = 3; // px per tile of every low mirror

function refreshGroundLow(meta: WorldMeta): void {
  if (!groundLowCv || groundLowCv.width !== meta.cols * LAYER_LOW) {
    groundLowCv = document.createElement('canvas');
    groundLowCv.width = meta.cols * LAYER_LOW;
    groundLowCv.height = meta.rows * LAYER_LOW;
  }
  const lg = groundLowCv.getContext('2d')!;
  lg.imageSmoothingEnabled = true;
  lg.clearRect(0, 0, groundLowCv.width, groundLowCv.height);
  lg.drawImage(groundCv!, 0, 0, groundLowCv.width, groundLowCv.height);
}

function groundLayer(meta: WorldMeta, chunkTiles: number, tilePx: number,
    getChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
    level: number, nowMs: number): HTMLCanvasElement | null {
  const fresh = level !== groundLevel || !groundCv
    || groundCv.width !== meta.cols * ART || groundCv.height !== meta.rows * ART;
  if (!fresh && (groundHoles.length === 0 || nowMs < groundRetryAt)) return groundCv;
  const chunkRect = (cx: number, cy: number): [number, number, number, number] => {
    const wTiles = Math.min(chunkTiles, meta.cols - cx * chunkTiles);
    const hTiles = Math.min(chunkTiles, meta.rows - cy * chunkTiles);
    return [cx * chunkTiles * ART, cy * chunkTiles * ART, wTiles * ART, hTiles * ART];
  };
  if (fresh) {
    if (!groundCv || groundCv.width !== meta.cols * ART || groundCv.height !== meta.rows * ART) {
      groundCv = document.createElement('canvas');
      groundCv.width = meta.cols * ART;
      groundCv.height = meta.rows * ART;
    }
    const ctx = groundCv.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, groundCv.width, groundCv.height);
    groundHoles = [];
    const cxN = Math.ceil(meta.cols / chunkTiles), cyN = Math.ceil(meta.rows / chunkTiles);
    for (let cy = 0; cy < cyN; cy++) {
      for (let cx = 0; cx < cxN; cx++) {
        const chunk = getChunk(cx, cy);
        if (!chunk) { groundHoles.push([cx, cy]); continue; }
        const [dx, dy, dw, dh] = chunkRect(cx, cy);
        ctx.drawImage(chunk, 0, 0, dw / ART * tilePx, dh / ART * tilePx, dx, dy, dw, dh);
      }
    }
    groundLevel = level;
    groundRev++; // whole layer: the GL pass re-uploads its texture
    groundPatchRects = null;
    refreshGroundLow(meta);
  } else {
    // Retry pass: blit only the holes whose chunks have arrived, and ship
    // exactly those rects as texture patches. Nothing arrived = nothing
    // repainted, no rev bump, no upload.
    const ctx = groundCv!.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;
    const filled: Array<[number, number, number, number]> = [];
    groundHoles = groundHoles.filter(([cx, cy]) => {
      const chunk = getChunk(cx, cy);
      if (!chunk) return true;
      const [dx, dy, dw, dh] = chunkRect(cx, cy);
      ctx.drawImage(chunk, 0, 0, dw / ART * tilePx, dh / ART * tilePx, dx, dy, dw, dh);
      filled.push([dx, dy, dw, dh]);
      return false;
    });
    if (filled.length > 0) {
      groundRev++;
      groundPatchRects = filled;
      refreshGroundLow(meta);
    }
  }
  groundRetryAt = nowMs + 1000; // holes still open: look again shortly
  return groundCv;
}

/** True on-screen body diameter (px) below which a creature is drawn as a
 *  flat colour block instead of a sprite stamp. At that size the stamp reads
 *  as a couple of noisy pixels anyway, but still costs a drawImage — the
 *  block keeps the map view's herd colours readable for about a tenth of the
 *  cost. Bigger bodies keep their sprites slightly longer, so the megafauna
 *  stay recognisable on the map. */
export const DOT_LOD_PX = 8;

// Adaptive degradation: when frames run long for a sustained stretch, the dot
// threshold rises so more of the herd draws as blocks — trading sprite detail
// the eye can barely use for the frame rate it definitely can. An EMA with
// hysteresis keeps the mode from flapping; both bounds sit ABOVE the 16.7 ms
// a locked-60 machine settles at (an exit bound below it would trap the mode
// on forever — a machine holding exactly 60 fps could never leave), so a
// machine that can hold 60 fps always recovers full detail.
let frameEma = 16.7;
let lastFrameAt = 0;
let herdMode = false;

// ?lod=0 freezes the adaptive threshold at its base value — the benchmark
// harness (client/bench) uses it so a zoom band always exercises the draw
// tier it targets instead of whatever the load feedback picked that run.
const LOD_LOCKED = typeof location !== 'undefined' && /[?&]lod=0\b/.test(location.search);
// Perf experiment switches (the ⚙ dialog in main.ts writes these): each
// disables one visual subsystem so its cost can be isolated on a device.
const flagOff = (k: string): boolean =>
  typeof location !== 'undefined' && new RegExp('[?&]' + k + '=0\\b').test(location.search);
const SPRITES_OFF = flagOff('sprites'); // creatures as dots only
const PHERO_OFF = flagOff('phero');     // pheromone clouds
const CANOPY_OFF = flagOff('canopy');   // foliage veils + duct lids
const OVERLAY_OFF = flagOff('overlay'); // action badges, rings, carry links

function adaptiveDotLod(nowMs: number): number {
  if (LOD_LOCKED) return DOT_LOD_PX;
  if (lastFrameAt > 0) {
    const dt = Math.min(100, nowMs - lastFrameAt);
    frameEma += (dt - frameEma) * 0.1;
    if (!herdMode && frameEma > 26) herdMode = true;
    else if (herdMode && frameEma < 18.5) herdMode = false;
  }
  lastFrameAt = nowMs;
  return herdMode ? DOT_LOD_PX * 2.5 : DOT_LOD_PX;
}

/** The map-view dot: a corpse is a spent grey block; a minded body wears its
 *  rim violet as an underlying block, one pixel proud on every side. */
export function drawDot(g: CanvasRenderingContext2D, x: number, y: number,
    bodyPx: number, col: string, minded: boolean, dead: boolean): void {
  const s = Math.max(4, bodyPx);
  if (dead) {
    g.fillStyle = '#5a5f66';
    g.fillRect(x - s / 2, y - s / 2, s, s);
    return;
  }
  if (minded) {
    g.fillStyle = RIM_COLOUR;
    g.fillRect(x - s / 2 - 1, y - s / 2 - 1, s + 2, s + 2);
  }
  g.fillStyle = col;
  g.fillRect(x - s / 2, y - s / 2, s, s);
}

export function render(
  g: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  chunkTiles: number,
  tilePx: number,
  getChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
  getBareChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
  veg: Uint8Array | null,
  cover: Uint8Array | null,
  renderTime: number,
  nowMs: number,
  level = 0,
  selection: { id: number | null; tile: { x: number; y: number; z: number } | null } =
    { id: null, tile: null },
): void {
  const cv = g.canvas;
  const dotLod = SPRITES_OFF ? Infinity : adaptiveDotLod(nowMs);
  g.fillStyle = '#14161a';
  g.fillRect(0, 0, cv.width, cv.height);
  if (!meta) return;

  // Ground: far zoom blits the assembled art-resolution layer in one draw
  // (see groundLayer); near zoom draws the full-resolution chunks in view.
  // Both nearest-neighbour, so pixels stay fat and crisp either way.
  if (chunkTiles > 0 && cam.scale < ART) {
    const layer = groundLayer(meta, chunkTiles, tilePx, getChunk, level, nowMs);
    if (layer) {
      const o = cam.worldToScreen(0, 0);
      g.imageSmoothingEnabled = false;
      g.drawImage(layer, o.x, o.y, meta.cols * cam.scale, meta.rows * cam.scale);
    }
  } else if (chunkTiles > 0) {
    const cxN = Math.ceil(meta.cols / chunkTiles);
    const cyN = Math.ceil(meta.rows / chunkTiles);
    const tl = cam.screenToWorld(0, 0);
    const br = cam.screenToWorld(cv.width, cv.height);
    const cx0 = Math.max(0, Math.floor(tl.x / chunkTiles));
    const cy0 = Math.max(0, Math.floor(tl.y / chunkTiles));
    const cx1 = Math.min(cxN - 1, Math.floor(br.x / chunkTiles));
    const cy1 = Math.min(cyN - 1, Math.floor(br.y / chunkTiles));
    g.imageSmoothingEnabled = false;
    for (let cy = cy0; cy <= cy1; cy++) {
      for (let cx = cx0; cx <= cx1; cx++) {
        const img = getChunk(cx, cy); // lazily fetches + caches this chunk
        if (!img) continue;
        const wx = cx * chunkTiles, wy = cy * chunkTiles;
        const cw = Math.min(chunkTiles, meta.cols - wx);
        const ch = Math.min(chunkTiles, meta.rows - wy);
        const o = cam.worldToScreen(wx, wy);
        g.drawImage(img, o.x, o.y, cw * cam.scale, ch * cam.scale);
      }
    }
  }

  // Live grazing: the pre-composited depletion layer (see depletionLayer) —
  // one drawImage per frame, whatever the herds have eaten. Crisp art-pixels
  // when zoomed in; smoothing on the downscale resolves to coverage when the
  // whole map is in view.
  if (veg && chunkTiles > 0 && tilePx > 0) {
    const layer = depletionLayer(meta, chunkTiles, tilePx, getBareChunk, veg, level, nowMs);
    if (layer) {
      const o = cam.worldToScreen(0, 0);
      // Near zoom blits the art-pixel layer crisply; far zoom blits the small
      // mip (already smoothed once at build time), so no frame ever pays for
      // a full-resolution smooth downscale.
      const src2 = cam.scale < ART && deplLayerLow ? deplLayerLow : layer;
      g.imageSmoothingEnabled = cam.scale < ART;
      g.drawImage(src2, o.x, o.y, meta.cols * cam.scale, meta.rows * cam.scale);
      g.imageSmoothingEnabled = false;
    }
  }

  // Selection highlight — a bright ring on the inspected tile, so it's obvious
  // what the debug panel is describing. (The selected creature gets its own ring
  // in the entity loop, where its interpolated position is known.)
  if (selection.tile && selection.tile.z === level) {
    const o = cam.worldToScreen(selection.tile.x, selection.tile.y);
    const w = cam.scale;
    g.save();
    g.strokeStyle = 'rgba(255,214,64,0.95)';
    g.lineWidth = Math.max(2, w * 0.06);
    g.strokeRect(o.x + 1, o.y + 1, w - 2, w - 2);
    g.fillStyle = 'rgba(255,214,64,0.12)';
    g.fillRect(o.x + 1, o.y + 1, w - 2, w - 2);
    g.restore();
  }

  // Painter's order: haze, then dead, then items, then living creatures.
  const order = (t: Track): number =>
    t.curr.kind === 'phero' ? 0
      : t.curr.kind.startsWith('switch.') || t.curr.kind === 'nest' ? 1 // floor fixtures lowest
      : t.curr.kind.startsWith('door.') ? 2
      : (t.curr.flags & F_DEAD) ? 2 : t.curr.kind.startsWith('item.') ? 3 : 4;
  // Secondary sort by phenotype: a GPU canvas batches consecutive draws from
  // the SAME source texture, and each phenotype's atlas is its own texture —
  // interleaved phenotypes break every batch. Within a class the paint order
  // is otherwise arbitrary, so grouping by atlas is free.
  const tracks = [...state.tracks.values()]
    .sort((a, b) => order(a) - order(b) || a.curr.pheno - b.curr.pheno);

  for (const t of tracks) {
    const e = t.curr;
    if (Math.round(e.z) !== level) continue; // only entities on the shown level
    const p = state.sample(t, renderTime);
    const s = cam.worldToScreen(p.x, p.y);

    // Doors reach up to `span` tiles from their anchor, so they get their own
    // generous cull instead of the point cull below.
    if (e.kind.startsWith('door.')) {
      const m = (e.size + 1) * cam.scale + 60;
      if (s.x < -m || s.y < -m || s.x > cv.width + m || s.y > cv.height + m) continue;
      drawDoor(g, cam, e);
      continue;
    }
    // A switch's indicator trail reaches its wired door, so cull loosely too.
    if (e.kind.startsWith('switch.')) {
      const m = 8 * cam.scale + 60;
      if (s.x < -m || s.y < -m || s.x > cv.width + m || s.y > cv.height + m) continue;
      drawSwitch(g, cam, e, state.tracks.get(e.pheno)?.curr);
      continue;
    }

    if (s.x < -60 || s.y < -60 || s.x > cv.width + 60 || s.y > cv.height + 60) continue;

    if (e.kind === 'phero') {
      if (PHERO_OFF) continue;
      const r = Math.max(2, e.size * cam.scale);
      g.imageSmoothingEnabled = true; // the haze's tint stays soft by design
      g.drawImage(pheroPuff(), s.x - r, s.y - r, r * 2, r * 2);
      g.imageSmoothingEnabled = false;
      continue;
    }

    const r = Math.max(3.5, e.size * cam.scale);

    // Carry link under the bodies.
    const carrier = e.attachedTo >= 0 ? state.tracks.get(e.attachedTo) : undefined;
    if (carrier) {
      const cp = state.sample(carrier, renderTime);
      const cs = cam.worldToScreen(cp.x, cp.y);
      if (!OVERLAY_OFF) drawCarryLink(g, s.x, s.y, cs.x, cs.y, cam.scale);
    }

    if (e.kind === 'nest') {
      drawNest(g, s.x, s.y, cam.scale);
      continue;
    }

    if (e.kind.startsWith('item.')) {
      drawItem(g, e.kind, s.x, s.y, r, '#' + e.rgb.toString(16).padStart(6, '0'));
      continue;
    }

    // Creature. Dot-sized bodies (see DOT_LOD_PX; threshold rises under
    // sustained frame pressure) skip the sprite pipeline:
    // one or two fillRects instead of a drawImage, which is most of the frame
    // at map zoom with a large population.
    const bodyPx = e.size * cam.scale * 2;
    if (e.flags & F_DEAD) {
      if (bodyPx < dotLod) {
        drawDot(g, s.x, s.y, bodyPx, '', false, true);
        continue;
      }
      // A corpse keeps its body and loses its colour. It is not decoration: it
      // lingers for a span set by its mass, it can be scavenged, and it is worth
      // that mass as meat -- so what died, and how big it was, stays readable.
      const dead = atlasFor(e.pheno);
      if (dead) {
        const box = r * 2 * (CELL / (2 * ART_RADIUS));
        // Frozen on one frame, not cell(dir, nowMs): the atlas rows are gait
        // phases, so feeding a corpse the clock had it walking on the spot for
        // its whole decay. It keeps the heading it died on -- that still says
        // something -- but the legs stop. Row 0 is the frame nearest phase 0,
        // the closest thing the cycle has to a neutral stance.
        const dc = headingCol(p.dir, t.col ?? -1);
        t.col = dc;
        const dr = 0;
        g.imageSmoothingEnabled = false;
        // Far zoom stamps from the pre-smoothed quarter-size mip (see atlas.ts).
        // aux carries how far this body has rotted once F_DEAD is set (see
        // EntityState) -- energy means nothing to a corpse, so the slot is reused.
        const c = box <= CELL / MIP ? CELL / MIP : CELL;
        const src = c === CELL
          ? corpseFor(e.pheno, dead, e.aux)
          : corpseMipFor(e.pheno, dead, e.aux);
        g.drawImage(src, dc * c, dr * c, c, c,
          s.x - box / 2, s.y - box / 2, box, box);
      } else {
        g.fillStyle = '#555a63'; // no sprite yet: a spent dot, no marker over it
        g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
      }
      continue;
    }
    // The real procedural organism, if its atlas has loaded; else a dot with a
    // heading wedge so it still reads while the sprite is in flight. The dot
    // path does NOT touch atlasFor: prefetching every phenotype the map view
    // ever glimpsed is how an old world's 800+ atlases (~2MB of canvas each)
    // ended up resident at once — zooming in shows a dot for the beat the
    // fetch takes instead.
    const atlas = bodyPx < dotLod ? null : atlasFor(e.pheno);
    if (bodyPx < dotLod) {
      drawDot(g, s.x, s.y, bodyPx, '#' + e.rgb.toString(16).padStart(6, '0'),
        (e.flags & F_MINDED) !== 0, false);
    } else if (atlas) {
      const box = r * 2 * (CELL / (2 * ART_RADIUS)); // scale cell so body ≈ 2r
      // Sticky heading bucket (see atlas.headingCol): neighbouring columns are
      // very different silhouettes, so bucket noise while walking reads as
      // flicker rather than turning.
      const cc = headingCol(p.dir, t.col ?? -1);
      t.col = cc;
      const { row: rr } = cell(p.dir, nowMs, e.id);
      g.imageSmoothingEnabled = false;
      // The fetched atlas is colour-neutral (one per SHAPE); this path has no
      // tint shader, so it stamps from a re-tinted copy baked once per
      // (shape, colour) — see atlas.tintedFor.
      const tinted = tintedFor(e.pheno, e.rgb, atlas);
      const tk = e.pheno + ':' + e.rgb;
      if (box <= CELL / MIP) {
        // Far zoom: one stamp from the quarter-size mip — the minded variant has
        // its rim baked in, so the whole herd view costs one draw per creature.
        const c = CELL / MIP;
        const src = (e.flags & F_MINDED) ? mindedMipFor(tk, tinted) : atlasMipFor(tk, tinted);
        g.drawImage(src, cc * c, rr * c, c, c, s.x - box / 2, s.y - box / 2, box, box);
      } else {
        // Minded cohort: a violet rim hugging the body, so a creature driven by
        // an evolvable mind can be picked out of the scripted species at a
        // glance. Rim and body come pre-fused (see atlas.mindedFor) — one draw
        // per creature, not five, which is what a GPU canvas feels when a herd
        // of hundreds is on screen.
        const src = (e.flags & F_MINDED) ? mindedFor(tk, tinted) : tinted;
        g.drawImage(src, cc * CELL, rr * CELL, CELL, CELL, s.x - box / 2, s.y - box / 2, box, box);
      }
    } else {
      // The colour string is built only here: the atlas path never needs it,
      // and building one per creature per frame is measurable at herd scale.
      drawPlaceholder(g, s.x, s.y, r, p.dir,
        '#' + e.rgb.toString(16).padStart(6, '0'), (e.flags & F_MINDED) !== 0);
    }

    // What it is doing, as a small badge hovering over the body. Only notable
    // acts carry a code, so this stays sparse rather than tagging every creature
    // on screen. Gated on the TRUE on-screen body size rather than `r`, which is
    // floored so distant creatures stay visible as dots — testing `r` would keep
    // drawing unreadable specks at every zoom level, including the fully
    // zoomed-out map view. Badges therefore fade out as you pull back and appear
    // as you zoom in on what a creature is actually doing.
    if (e.size * cam.scale >= GLYPH_MIN_BODY_PX) {
      if (!OVERLAY_OFF) drawActionGlyph(g, s.x, s.y - r * 2.0, r * 0.95, actionOf(e.flags));
    }

    if (OVERLAY_OFF) { /* badges, rings and links are the experiment's cost */ }
    else if (e.flags & F_GRABBED) drawRing(g, 'grabbed', s.x, s.y, r);
    else if (e.flags & F_CARRYING) drawRing(g, 'carrying', s.x, s.y, r);

    // Follow highlight: a gentle pulsing ring around the tracked creature.
    if (cam.followId === e.id) drawRing(g, 'follow', s.x, s.y, r, renderTime);
    // Selection highlight: a steady amber ring on the inspected creature, so it's
    // obvious which one the panel describes (distinct from the follow pulse).
    if (selection.id === e.id) drawRing(g, 'selected', s.x, s.y, r);
  }

  // Concealment over the creatures: anything standing in a walkable
  // sight-blocker is part-hidden, matching the fact that cover blocks line of
  // sight. Foliage veils (thicket, reeds) blit from the cached layer — crisp
  // art-pixels near, the smoothed mip far.
  if (!CANOPY_OFF && cover && chunkTiles > 0 && tilePx > 0) {
    const layer = canopyLayer(meta, chunkTiles, tilePx, getChunk, cover, level, nowMs);
    const o = cam.worldToScreen(0, 0);
    const src = cam.scale < ART && canopyLow ? canopyLow : layer;
    g.imageSmoothingEnabled = cam.scale < ART;
    g.globalAlpha = VEIL_ALPHA;
    g.drawImage(src, o.x, o.y, meta.cols * cam.scale, meta.rows * cam.scale);
    g.globalAlpha = 1;
    g.imageSmoothingEnabled = false;

    // Duct lids are different: a lid is visibly not the duct's open floor, so
    // it cannot sit in the static layer — like the Java renderer, the lid
    // materialises only over ducts an entity is in or beside (the 3x3
    // neighbourhood Grid.renderConcealment veils), and an empty duct shows
    // its floor.
    const lids = new Set<number>();
    for (const t of tracks) {
      const e = t.curr;
      if (e.kind === 'phero' || (e.flags & F_DEAD) || Math.round(e.z) !== level) continue;
      const ex = Math.floor(e.x), ey = Math.floor(e.y);
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const tx = ex + dx, ty = ey + dy;
          if (tx < 0 || ty < 0 || tx >= meta.cols || ty >= meta.rows) continue;
          if (cover[ty * meta.cols + tx] === 2) lids.add(ty * meta.cols + tx);
        }
      }
    }
    g.globalAlpha = VEIL_ALPHA;
    for (const key of lids) {
      const tx = key % meta.cols, ty = Math.floor(key / meta.cols);
      const o2 = cam.worldToScreen(tx, ty);
      if (o2.x < -cam.scale || o2.y < -cam.scale || o2.x > cv.width || o2.y > cv.height) continue;
      const vert = (ty > 0 && cover[(ty - 1) * meta.cols + tx] === 2)
        || (ty < meta.rows - 1 && cover[(ty + 1) * meta.cols + tx] === 2);
      g.drawImage(ductLidTile(vert), o2.x, o2.y, cam.scale, cam.scale);
    }
    g.globalAlpha = 1;
  }
}

// ---- the WebGL world pass -------------------------------------------------
//
// Canvas2D pays CPU per API call, which is the wall a large population hits
// on real GPU browsers (the software rasteriser that runs our benchmarks pays
// per PIXEL instead, which is why it never showed this). The GL pass renders
// the whole WORLD — ground, depletion, entity sprites, canopy, lids — as
// batched textured quads through gl.ts: the layers are one quad each, and a
// thousand sorted creatures are a handful of draw calls. Everything vector —
// doors, switches, rings, badges, tethers, the selection — is collected
// during the same walk and drawn onto a thin 2D OVERLAY canvas afterwards,
// where a few dozen calls cost nothing. Canvas2D `render()` above remains the
// full fallback for browsers without WebGL2.
//
// One deliberate divergence: the overlay sits above the canopy, so rings,
// badges and door slabs are never veiled by foliage. For the viewer's overlay
// language that is arguably correct (a selection should not vanish into a
// thicket); for doors and switches it is invisible in practice (none stand in
// cover).

/** A per-frame bump for each composited layer, so the GL pass re-uploads a
 *  layer texture exactly when the canvas under it was repainted. */
let groundRev = 0;
let deplRev = 0;
let canopyRev = 0;

/** Bakes the nest stamp once at art resolution for the GL pass — painted by
 *  the same drawNest the 2D path and the catalog run. */
let nestStampCv: HTMLCanvasElement | null = null;
function nestStamp(): HTMLCanvasElement {
  if (!nestStampCv) {
    nestStampCv = document.createElement('canvas');
    nestStampCv.width = 11;
    nestStampCv.height = 9;
    drawNest(nestStampCv.getContext('2d')!, 5.5, 4.5, 12); // 1 px per art-pixel
  }
  return nestStampCv;
}

/** Item glyphs baked once per (kind, colour) — painted by the same drawItem
 *  the 2D path runs, at a reference radius, then scaled by the GPU. */
const ITEM_R = 24;
const itemStamps = new Map<string, HTMLCanvasElement>();
function itemStamp(kind: string, rgb: number): HTMLCanvasElement {
  const key = kind + ':' + rgb;
  let cv = itemStamps.get(key);
  if (!cv) {
    cv = document.createElement('canvas');
    cv.width = 96;
    cv.height = 96;
    drawItem(cv.getContext('2d')!, kind, 48, 48, ITEM_R, '#' + rgb.toString(16).padStart(6, '0'));
    itemStamps.set(key, cv);
  }
  return cv;
}

export function renderGL(
  glr: import('./gl').GLRenderer,
  og: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  chunkTiles: number,
  tilePx: number,
  getChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
  getBareChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
  veg: Uint8Array | null,
  cover: Uint8Array | null,
  renderTime: number,
  nowMs: number,
  level = 0,
  selection: { id: number | null; tile: { x: number; y: number; z: number } | null } =
    { id: null, tile: null },
): { drawCalls: number; quads: number; uploadMs: number; textures: number;
     secLayers: number; secEnts: number; secTail: number } {
  let t0 = performance.now();
  const sec = { secLayers: 0, secEnts: 0, secTail: 0 };
  const lap = (k: keyof typeof sec) => { const n = performance.now(); sec[k] += n - t0; t0 = n; };
  const cv = og.canvas;
  const dotLod = SPRITES_OFF ? Infinity : adaptiveDotLod(nowMs);
  glr.begin(cv.width, cv.height, 0x14 / 255, 0x16 / 255, 0x1a / 255);
  og.clearRect(0, 0, cv.width, cv.height);
  if (!meta) return { ...glr.end(), ...sec };

  const o0 = cam.worldToScreen(0, 0);
  const worldW = meta.cols * cam.scale, worldH = meta.rows * cam.scale;

  // Ground and depletion: the art-resolution layers, one quad each. Layer
  // textures carry NO mipmaps (they get patched), so at far zoom — where the
  // full 12px/tile texture would minify 4-8x and thrash a phone GPU's texture
  // cache — the quad samples the quarter-res mirror instead, exactly as the
  // Canvas2D path always has.
  const lowZoom = cam.scale < ART;
  if (chunkTiles > 0 && tilePx > 0) {
    const ground = groundLayer(meta, chunkTiles, tilePx, getChunk, level, nowMs);
    if (ground) {
      if (lowZoom && groundLowCv) {
        glr.layer('groundlo', groundLowCv, groundRev, null, o0.x, o0.y, worldW, worldH);
      } else {
        glr.layer('ground', ground, groundRev, groundPatchRects, o0.x, o0.y, worldW, worldH);
      }
    }
    if (veg) {
      const depl = depletionLayer(meta, chunkTiles, tilePx, getBareChunk, veg, level, nowMs);
      if (depl) {
        if (lowZoom && deplLayerLow) {
          // The low mirror's patch rects are the full-res rects at 1/4 scale
          // (ART=12 -> 3 px/tile), which divide exactly on tile boundaries.
          const loRects = deplPatchRects
            ? deplPatchRects.map(([x, y, w, h]) =>
              [x / 4, y / 4, w / 4, h / 4] as [number, number, number, number])
            : null;
          glr.layer('depllo', deplLayerLow, deplRev, loRects, o0.x, o0.y, worldW, worldH);
        } else {
          glr.layer('depl', depl, deplRev, deplPatchRects, o0.x, o0.y, worldW, worldH);
        }
      }
    }
  }

  lap('secLayers');
  // Vector work collected during the entity walk, drawn on the overlay after.
  const ovDoors: EntityState[] = [];
  const ovSwitches: Array<[EntityState, EntityState | undefined]> = [];
  const ovLinks: Array<[number, number, number, number]> = [];
  const ovGlyphs: Array<[number, number, number, number]> = [];
  const ovRings: Array<['grabbed' | 'carrying' | 'follow' | 'selected', number, number, number]> = [];

  const order = (t: Track): number =>
    t.curr.kind === 'phero' ? 0
      : t.curr.kind.startsWith('switch.') || t.curr.kind === 'nest' ? 1
      : t.curr.kind.startsWith('door.') ? 2
      : (t.curr.flags & F_DEAD) ? 2 : t.curr.kind.startsWith('item.') ? 3 : 4;
  const tracks = [...state.tracks.values()]
    .sort((a, b) => order(a) - order(b) || a.curr.pheno - b.curr.pheno);

  for (const t of tracks) {
    const e = t.curr;
    if (Math.round(e.z) !== level) continue;
    const p = state.sample(t, renderTime);
    const s = cam.worldToScreen(p.x, p.y);

    if (e.kind.startsWith('door.')) {
      const m = (e.size + 1) * cam.scale + 60;
      if (s.x < -m || s.y < -m || s.x > cv.width + m || s.y > cv.height + m) continue;
      ovDoors.push(e);
      continue;
    }
    if (e.kind.startsWith('switch.')) {
      const m = 8 * cam.scale + 60;
      if (s.x < -m || s.y < -m || s.x > cv.width + m || s.y > cv.height + m) continue;
      ovSwitches.push([e, state.tracks.get(e.pheno)?.curr]);
      continue;
    }

    if (s.x < -60 || s.y < -60 || s.x > cv.width + 60 || s.y > cv.height + 60) continue;

    if (e.kind === 'phero') {
      if (PHERO_OFF) continue;
      const r = Math.max(2, e.size * cam.scale);
      const puff = pheroPuff();
      glr.sprite('phero', puff, 1, 0, 0, puff.width, puff.height, s.x - r, s.y - r, r * 2, r * 2);
      continue;
    }

    const r = Math.max(3.5, e.size * cam.scale);

    const carrier = e.attachedTo >= 0 ? state.tracks.get(e.attachedTo) : undefined;
    if (carrier) {
      const cp = state.sample(carrier, renderTime);
      const cs = cam.worldToScreen(cp.x, cp.y);
      ovLinks.push([s.x, s.y, cs.x, cs.y]);
    }

    if (e.kind === 'nest') {
      const stamp = nestStamp();
      const px = cam.scale / 12;
      glr.sprite('nest', stamp, 1, 0, 0, 11, 9,
        s.x - 5.5 * px, s.y - 4.5 * px, 11 * px, 9 * px);
      continue;
    }

    if (e.kind.startsWith('item.')) {
      const stamp = itemStamp(e.kind, e.rgb);
      const k = r / ITEM_R;
      glr.sprite('item:' + e.kind + ':' + e.rgb, stamp, 1, 0, 0, 96, 96,
        s.x - 48 * k, s.y - 48 * k, 96 * k, 96 * k);
      continue;
    }

    const bodyPx = e.size * cam.scale * 2;
    if (e.flags & F_DEAD) {
      if (bodyPx < dotLod) {
        const sq = Math.max(4, bodyPx);
        glr.quad(s.x - sq / 2, s.y - sq / 2, sq, sq, 0x5a / 255, 0x5f / 255, 0x66 / 255, 1);
        continue;
      }
      const dead = atlasFor(e.pheno);
      if (dead) {
        const box = r * 2 * (CELL / (2 * ART_RADIUS));
        const dc = headingCol(p.dir, t.col ?? -1); // frozen gait: the legs stop (see render())
        t.col = dc;
        // Far zoom stamps from the quarter-size mip, same rule as the 2D path —
        // and the texture behind it is 16x smaller, which is what lets hundreds
        // of distinct phenotypes stay GPU-resident at once (see gl.ts caps).
        const c = box <= CELL / MIP ? CELL / MIP : CELL;
        const corpse = c === CELL
          ? corpseFor(e.pheno, dead, e.aux) : corpseMipFor(e.pheno, dead, e.aux);
        glr.sprite((c === CELL ? 'corpse:' : 'corpsem:') + e.pheno + ':' + decayStage(e.aux),
          corpse, 1, dc * c, 0, c, c, s.x - box / 2, s.y - box / 2, box, box);
      } else {
        glr.quad(s.x - r, s.y - r, r * 2, r * 2, 0x55 / 255, 0x5a / 255, 0x63 / 255, 1);
      }
      continue;
    }

    // No atlas fetch at dot size — see render() above for why prefetching
    // every glimpsed phenotype is a memory cliff on long-evolved worlds.
    const atlas = bodyPx < dotLod ? null : atlasFor(e.pheno);
    const minded = (e.flags & F_MINDED) !== 0;
    if (bodyPx < dotLod || !atlas) {
      // The dot LOD (and the pre-atlas placeholder, simplified to the same
      // block while the sprite streams in): solid quads, batched together.
      const sq = Math.max(4, bodyPx < dotLod ? bodyPx : r * 1.4);
      if (minded) {
        glr.quad(s.x - sq / 2 - 1, s.y - sq / 2 - 1, sq + 2, sq + 2,
          0xc6 / 255, 0x60 / 255, 0xff / 255, 1);
      }
      const rgb = e.rgb;
      glr.quad(s.x - sq / 2, s.y - sq / 2, sq, sq,
        ((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255, 1);
    } else {
      const box = r * 2 * (CELL / (2 * ART_RADIUS));
      // Sticky heading bucket (see atlas.headingCol): bucket noise while
      // walking would otherwise flip the pose at frame rate.
      const cc = headingCol(p.dir, t.col ?? -1);
      t.col = cc;
      const { row: rr } = cell(p.dir, nowMs, e.id);
      // Far zoom: quarter-size mip source, same threshold as the 2D path. This
      // is not just fewer pixels — the herd view is where EVERY phenotype is on
      // screen at once, and only the small mip textures can all stay resident.
      const c = box <= CELL / MIP ? CELL / MIP : CELL;
      const src = c === CELL
        ? (minded ? mindedFor(e.pheno, atlas) : atlas)
        : (minded ? mindedMipFor(e.pheno, atlas) : atlasMipFor(e.pheno, atlas));
      const key = c === CELL ? (minded ? 'minded:' : 'atlas:') : (minded ? 'mindedm:' : 'atlasm:');
      // The atlas is colour-neutral (one per SHAPE); the creature's rgb rides
      // the quad and the ramp shader re-tints it — see gl.ts mode 1.
      glr.sprite(key + e.pheno, src, 1,
        cc * c, rr * c, c, c, s.x - box / 2, s.y - box / 2, box, box, 1, e.rgb);
    }

    if (e.size * cam.scale >= GLYPH_MIN_BODY_PX) {
      const act = actionOf(e.flags);
      if (act) ovGlyphs.push([s.x, s.y - r * 2.0, r * 0.95, act]);
    }
    if (e.flags & F_GRABBED) ovRings.push(['grabbed', s.x, s.y, r]);
    else if (e.flags & F_CARRYING) ovRings.push(['carrying', s.x, s.y, r]);
    if (cam.followId === e.id) ovRings.push(['follow', s.x, s.y, r]);
    if (selection.id === e.id) ovRings.push(['selected', s.x, s.y, r]);
  }

  lap('secEnts');
  // Concealment over the creatures, then lids over occupants (see render()).
  if (!CANOPY_OFF && cover && chunkTiles > 0 && tilePx > 0) {
    const canopy = canopyLayer(meta, chunkTiles, tilePx, getChunk, cover, level, nowMs);
    if (lowZoom && canopyLow) {
      glr.layer('canopylo', canopyLow, canopyRev, null, o0.x, o0.y, worldW, worldH, VEIL_ALPHA);
    } else {
      glr.layer('canopy', canopy, canopyRev, canopyPatchRects, o0.x, o0.y, worldW, worldH, VEIL_ALPHA);
    }
    const lids = new Set<number>();
    for (const t of tracks) {
      const e = t.curr;
      if (e.kind === 'phero' || (e.flags & F_DEAD) || Math.round(e.z) !== level) continue;
      const ex = Math.floor(e.x), ey = Math.floor(e.y);
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const tx = ex + dx, ty = ey + dy;
          if (tx < 0 || ty < 0 || tx >= meta.cols || ty >= meta.rows) continue;
          if (cover[ty * meta.cols + tx] === 2) lids.add(ty * meta.cols + tx);
        }
      }
    }
    for (const key of lids) {
      const tx = key % meta.cols, ty = Math.floor(key / meta.cols);
      const o2 = cam.worldToScreen(tx, ty);
      if (o2.x < -cam.scale || o2.y < -cam.scale || o2.x > cv.width || o2.y > cv.height) continue;
      const vert = (ty > 0 && cover[(ty - 1) * meta.cols + tx] === 2)
        || (ty < meta.rows - 1 && cover[(ty + 1) * meta.cols + tx] === 2);
      const lid = ductLidTile(vert);
      glr.sprite('lid:' + (vert ? 'v' : 'h'), lid, 1, 0, 0, 12, 12,
        o2.x, o2.y, cam.scale, cam.scale, VEIL_ALPHA);
    }
  }

  const stats = glr.end();

  // The overlay: the viewer's vector language, cheap at a few dozen calls.
  if (selection.tile && selection.tile.z === level) {
    const o = cam.worldToScreen(selection.tile.x, selection.tile.y);
    const w = cam.scale;
    og.strokeStyle = 'rgba(255,214,64,0.95)';
    og.lineWidth = Math.max(2, w * 0.06);
    og.strokeRect(o.x + 1, o.y + 1, w - 2, w - 2);
    og.fillStyle = 'rgba(255,214,64,0.12)';
    og.fillRect(o.x + 1, o.y + 1, w - 2, w - 2);
  }
  for (const e of ovDoors) drawDoor(og, cam, e);
  for (const [e, door] of ovSwitches) drawSwitch(og, cam, e, door);
  if (!OVERLAY_OFF) {
    for (const [x0, y0, x1, y1] of ovLinks) drawCarryLink(og, x0, y0, x1, y1, cam.scale);
    for (const [x, y, u, act] of ovGlyphs) drawActionGlyph(og, x, y, u, act);
    for (const [kind, x, y, r] of ovRings) drawRing(og, kind, x, y, r, renderTime);
  }
  lap('secTail');
  return { ...stats, ...sec };
}

/** Ported verbatim from GroundTextures.hash01 (32-bit int arithmetic via
 *  Math.imul) — the concealment gap mask must agree with the Java renderer's
 *  bit for bit, or /sprites shows the pipelines disagreeing. */
export function hash01(x: number, y: number, s: number): number {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(s, 2246822519 | 0)) | 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) & 0x7fffffff) / 0x7fffffff;
}

// The concealment veil, mirroring Grid.renderConcealment: a body standing in
// a walkable sight-blocker is part-hidden by the tile's OWN pixels re-stamped
// over the entity layer. The client does not regenerate the texture functions
// — it re-stamps the baked chunk pixels, which the same functions produced —
// so every veil pixel is one the Java renderer authored, and where nothing is
// underneath the veil is invisible (identical pixels over identical pixels).
// This replaced a translucent green wash (see ART-STYLE.md case law).
//
// Thicket (cover 1): clustered 2x2 art-px blocks at ~half coverage — the gap
// mask is hash01(gx>>1, gy>>1, 61) > 0.55, identical to the Java pass.
// Reeds (cover 3): stalk-exact — every baked pixel EXCEPT the reed-bed's gap
// colour is re-stamped, so a body shows between the stalks.
const REED_GAP = 0x14301f; // GroundTextures.RAMP[CLS_REEDS][0]

/** The design system's cover translucency: every concealment veil — canopy,
 *  reed stalks, duct lids — draws at 25% translucency, so a veiled body
 *  always half-reads through its cover. One global constant, shared with the
 *  Java renderer (GroundTextures.VEIL_ALPHA) and documented in ART-STYLE.md
 *  §4. Applied where the veil meets the screen (the layer blit and the lid
 *  stamps), so the cached layer itself stays opaque and composable. */
export const VEIL_ALPHA = 0.75;

const VEIL_SCRATCH = document.createElement('canvas');
VEIL_SCRATCH.width = 12;
VEIL_SCRATCH.height = 12;

/** Stamp one cover tile's veil into an art-pixel-resolution (12 px/tile)
 *  context. (tx, ty) index the world tile (they seed the world-absolute gap
 *  hash); (dx, dy) is where the tile lands in `ctx`; (sx, sy, srcTilePx)
 *  locate the tile in the baked ground source. Kinds: 1 thicket, 3 reeds. */
export function veilTile(ctx: CanvasRenderingContext2D, kind: number,
    tx: number, ty: number, dx: number, dy: number,
    src: CanvasImageSource, sx: number, sy: number, srcTilePx: number): void {
  if (kind === 3) {
    const vg = VEIL_SCRATCH.getContext('2d', { willReadFrequently: true })!;
    vg.clearRect(0, 0, ART, ART);
    vg.imageSmoothingEnabled = false;
    vg.drawImage(src, sx, sy, srcTilePx, srcTilePx, 0, 0, ART, ART);
    const id = vg.getImageData(0, 0, ART, ART);
    const px = id.data;
    for (let i = 0; i < px.length; i += 4) {
      if ((px[i] << 16 | px[i + 1] << 8 | px[i + 2]) === REED_GAP) px[i + 3] = 0;
    }
    ctx.putImageData(id, dx, dy);
    return;
  }
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(src, sx, sy, srcTilePx, srcTilePx, dx, dy, ART, ART);
  for (let j = 0; j < 6; j++) {
    for (let i = 0; i < 6; i++) {
      if (hash01(tx * 6 + i, ty * 6 + j, 61) > 0.55) {
        ctx.clearRect(dx + i * 2, dy + j * 2, 2, 2);
      }
    }
  }
}

/**
 * The duct's ribbed lid tile (cover 2) — ported from GroundTextures.ductLid:
 * solid side walls, lit rib / rib flank / open slot on a 3-px cycle, so a
 * crawler shows through the slots. Two orientations, baked once at art-pixel
 * size. Unlike the foliage veils this is NOT in the static layer: a lid is
 * visibly different from the duct's open floor, and the Java renderer shows
 * an empty duct open — lids materialise only around occupants (see render()).
 */
const DUCT_RAMP = ['#3f454c', '#616974', '#88929e']; // GroundTextures.RAMP[CLS_DUCT]
const lidTiles: (HTMLCanvasElement | null)[] = [null, null];

export function ductLidTile(vertical: boolean): HTMLCanvasElement {
  let cv = lidTiles[vertical ? 1 : 0];
  if (cv) return cv;
  cv = document.createElement('canvas');
  cv.width = ART;
  cv.height = ART;
  const g = cv.getContext('2d')!;
  for (let aj = 0; aj < ART; aj++) {
    for (let ai = 0; ai < ART; ai++) {
      const along = vertical ? aj : ai, across = vertical ? ai : aj;
      let c: string | null;
      if (across === 0 || across === ART - 1) c = DUCT_RAMP[0]; // side walls stay solid
      else if (along % 3 === 0) c = DUCT_RAMP[2]; // lit rib
      else if (along % 3 === 1) c = DUCT_RAMP[1]; // rib flank
      else c = null; // the slot between ribs: the crawler shows through
      if (c) { g.fillStyle = c; g.fillRect(ai, aj, 1, 1); }
    }
  }
  lidTiles[vertical ? 1 : 0] = cv;
  return cv;
}

// The foliage veil layer, cached like the depletion layer: cover is static per
// level and the veil is a pure re-stamp of the (static) bake, so it is built
// once and blitted in a single drawImage per frame. Thicket and reed veils can
// safely cover EVERY cover tile — where no body is beneath, re-stamped pixels
// land on identical pixels and nothing changes.
let canopyCv: HTMLCanvasElement | null = null;
let canopyLow: HTMLCanvasElement | null = null;
let canopySrc: Uint8Array | null = null;
let canopyLevel = -1;
// Veil tiles whose chunk had not streamed in when the layer was built.
// Retries stamp ONLY these and ship them as texture patches — the old path
// re-stamped every cover tile in the world and re-uploaded the whole texture
// once a second for as long as any chunk was missing (see groundLayer).
let canopyHoles: number[] = [];
let canopyRetryAt = 0;
let canopyPatchRects: Array<[number, number, number, number]> | null = null;

function refreshCanopyLow(meta: WorldMeta): void {
  if (!canopyLow || canopyLow.width !== meta.cols * LAYER_LOW) {
    canopyLow = document.createElement('canvas');
    canopyLow.width = meta.cols * LAYER_LOW;
    canopyLow.height = meta.rows * LAYER_LOW;
  }
  const lg = canopyLow.getContext('2d')!;
  lg.clearRect(0, 0, canopyLow.width, canopyLow.height);
  lg.imageSmoothingEnabled = true;
  lg.drawImage(canopyCv!, 0, 0, canopyLow.width, canopyLow.height);
}

function canopyLayer(meta: WorldMeta, chunkTiles: number, tilePx: number,
    getChunk: (cx: number, cy: number) => HTMLCanvasElement | null,
    cover: Uint8Array, level: number, nowMs: number): HTMLCanvasElement {
  const fresh = cover !== canopySrc || level !== canopyLevel || !canopyCv
    || canopyCv.width !== meta.cols * ART;
  if (!fresh && (canopyHoles.length === 0 || nowMs < canopyRetryAt)) return canopyCv!;
  const stamp = (ctx: CanvasRenderingContext2D, i: number): boolean => {
    const tx = i % meta.cols, ty = Math.floor(i / meta.cols);
    const v = cover[i];
    const ccx = Math.floor(tx / chunkTiles), ccy = Math.floor(ty / chunkTiles);
    const chunk = getChunk(ccx, ccy);
    if (!chunk) return false;
    veilTile(ctx, v, tx, ty, tx * ART, ty * ART, chunk,
      (tx - ccx * chunkTiles) * tilePx, (ty - ccy * chunkTiles) * tilePx, tilePx);
    return true;
  };
  if (fresh) {
    if (!canopyCv || canopyCv.width !== meta.cols * ART || canopyCv.height !== meta.rows * ART) {
      canopyCv = document.createElement('canvas');
      canopyCv.width = meta.cols * ART;
      canopyCv.height = meta.rows * ART;
    }
    const ctx = canopyCv.getContext('2d')!;
    ctx.clearRect(0, 0, canopyCv.width, canopyCv.height);
    canopyHoles = [];
    for (let i = 0; i < cover.length; i++) {
      const v = cover[i];
      if (v !== 1 && v !== 3) continue;
      if (!stamp(ctx, i)) canopyHoles.push(i);
    }
    canopySrc = cover;
    canopyLevel = level;
    canopyRev++; // whole layer: the GL pass re-uploads its texture
    canopyPatchRects = null;
    refreshCanopyLow(meta);
  } else {
    const ctx = canopyCv!.getContext('2d')!;
    const filled: Array<[number, number, number, number]> = [];
    canopyHoles = canopyHoles.filter((i) => {
      if (!stamp(ctx, i)) return true;
      filled.push([(i % meta.cols) * ART, Math.floor(i / meta.cols) * ART, ART, ART]);
      return false;
    });
    if (filled.length > 0) {
      canopyRev++;
      // A whole chunk's worth of veil tiles beats per-rect patching: past a
      // few hundred rects, ship the layer wholesale (same rule as depletion).
      canopyPatchRects = filled.length > 256 ? null : filled;
      refreshCanopyLow(meta);
    }
  }
  canopyRetryAt = nowMs + 1000; // holes still open: look again shortly
  return canopyCv!;
}

// The pheromone puff's soft radial falloff, baked once and blitted scaled: a
// per-frame createRadialGradient allocates and rasterises a fresh gradient for
// every puff on screen. The haze must stay smooth (sanctioned translucency #3
// keeps the TINT soft), so the blit re-enables smoothing for just this draw.
let pheroSprite: HTMLCanvasElement | null = null;

export function pheroPuff(): HTMLCanvasElement {
  if (!pheroSprite) {
    pheroSprite = document.createElement('canvas');
    pheroSprite.width = 64;
    pheroSprite.height = 64;
    const pg = pheroSprite.getContext('2d')!;
    const grad = pg.createRadialGradient(32, 32, 0, 32, 32, 32);
    grad.addColorStop(0, 'rgba(230,40,190,0.20)');
    grad.addColorStop(1, 'rgba(230,40,190,0)');
    pg.fillStyle = grad;
    pg.fillRect(0, 0, 64, 64);
  }
  return pheroSprite;
}

/**
 * A switch, test-chamber style: an indicator trail of dotted lights from
 * the switch to its wired door (x-leg first, matching the Swing renderer)
 * — dim while idle, lit pale-blue while the circuit is closed (aux = 1) —
 * then the control itself over the baked pedestal base. 'switch.plate' is
 * the broad red floor button, sinking flush and dark while weighted;
 * 'switch.button' is a small domed red cap on a dark pedestal that only a
 * deliberate press operates. `door` is the wired door's track, looked up
 * by the id in `pheno`; without it (not yet streamed) only the control
 * draws.
 */
export function drawSwitch(
  g: CanvasRenderingContext2D, cam: Camera, e: EntityState, door?: EntityState,
): void {
  const sc = cam.scale;
  const cx = e.x + 0.5, cy = e.y + 0.5;
  const pressed = e.aux >= 0.5;
  if (door) {
    const lr = Math.abs(door.dir % (Math.PI / 2)) > 1e-6;
    const half = Math.max(1, door.size) / 2;
    const dx = lr ? door.x : door.x + half;
    const dy = lr ? door.y + half : door.y;
    const w = Math.max(2, sc * 0.09);
    const a = cam.worldToScreen(cx, cy);
    const k = cam.worldToScreen(dx, cy); // the L's corner: x-leg first
    const b = cam.worldToScreen(dx, dy);
    // Indicator lamps along both legs: a dark housing around a lens, dim
    // while idle, lit while the circuit is closed — readable on any floor.
    const legs: Array<[number, number, number, number]> = [[a.x, a.y, k.x, k.y], [k.x, k.y, b.x, b.y]];
    for (const [x0, y0, x1, y1] of legs) {
      const len = Math.hypot(x1 - x0, y1 - y0);
      const n = Math.floor(len / (sc * 0.34));
      for (let i = 1; i <= n; i++) {
        const t = i / (n + 1);
        const sx = x0 + (x1 - x0) * t, sy = y0 + (y1 - y0) * t;
        g.fillStyle = '#14161f';
        g.fillRect(sx - w, sy - w, w * 2, w * 2);
        g.fillStyle = pressed ? '#D0ECFF' : '#6a7280';
        g.fillRect(sx - w / 2, sy - w / 2, w, w);
      }
    }
    // A soft glow under the lit trail's endpoint at the door.
    if (pressed) {
      g.fillStyle = 'rgba(208,236,255,0.25)';
      g.beginPath(); g.arc(b.x, b.y, sc * 0.3, 0, 7); g.fill();
    }
  }
  const o = cam.worldToScreen(cx, cy);
  if (e.kind === 'switch.plate') {
    // The broad floor button: proud and bright when armed, flush and dark
    // while weighted.
    const r = Math.max(3, sc * 0.27);
    g.fillStyle = pressed ? '#7c2434' : '#E0455F';
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.fill();
    if (!pressed) {
      g.fillStyle = '#F0788C';
      g.beginPath(); g.arc(o.x - r * 0.2, o.y - r * 0.3, r * 0.45, 0, 7); g.fill();
    }
    g.strokeStyle = '#23262e';
    g.lineWidth = 1;
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.stroke();
  } else {
    // The pedestal button: a small domed cap a body must choose to press.
    const b = Math.max(3, sc * 0.2);
    g.fillStyle = '#2c3037';
    g.fillRect(o.x - b, o.y - b, b * 2, b * 2);
    const r = Math.max(2, sc * 0.12);
    g.fillStyle = pressed ? '#7c2434' : '#E0455F';
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.fill();
    if (!pressed) {
      g.fillStyle = '#F0788C';
      g.beginPath(); g.arc(o.x - r * 0.25, o.y - r * 0.35, r * 0.4, 0, 7); g.fill();
    } else {
      g.strokeStyle = '#D0ECFF';
      g.lineWidth = Math.max(1, sc * 0.03);
      g.strokeRect(o.x - b, o.y - b, b * 2, b * 2);
    }
  }
}

/**
 * A door: two leaves sliding along the doorway from its ends toward the
 * middle, mirroring the Swing renderer. The anchor tile is (x,y); a non-zero
 * `dir` means the bar runs north-south (sealing east-west passage), zero
 * east-west. `size` carries the doorway span in tiles and `aux` how far each
 * leaf reaches toward the middle (1 sealed .. 0.15 open stubs) — so the wire
 * updates animate the slide for free. Flavours: segmented steel with a
 * hazard-striped nose (blast), see-through bars (grate), planked timber,
 * coursed stone, woven hedge — all as chunky slabs with a drop shadow.
 */
export function drawDoor(g: CanvasRenderingContext2D, cam: Camera, e: EntityState): void {
  const lr = Math.abs(e.dir % (Math.PI / 2)) > 1e-6;
  const span = Math.max(1, e.size);
  const ext = Math.max(0.15, Math.min(1, e.aux));
  const reach = (span / 2) * ext; // leaf length from each end, tiles
  const blast = e.kind === 'door.blast';
  const grate = e.kind === 'door.grate';
  const th = (blast ? 5 : 3) / 12; // bar thickness, tiles
  const col = '#' + e.rgb.toString(16).padStart(6, '0');
  const sc = cam.scale;
  const seam = 'rgba(0,0,0,0.45)';
  const leaves: Array<[number, number, number]> = [
    [0, reach, +1], // [start, end, direction the leaf's nose faces]
    [span - reach, span, -1],
  ];
  for (const [a0, a1, nose] of leaves) {
    if (a1 - a0 <= 0.01) continue;
    const wx = lr ? e.x - th / 2 : e.x + a0;
    const wy = lr ? e.y + a0 : e.y - th / 2;
    const o = cam.worldToScreen(wx, wy);
    const pw = (lr ? th : a1 - a0) * sc;
    const ph = (lr ? a1 - a0 : th) * sc;
    // Drop shadow south, so the slab visibly sits on the floor.
    g.fillStyle = 'rgba(0,0,0,0.35)';
    g.fillRect(o.x + sc * 0.02, o.y + sc * 0.07, pw, ph);
    if (grate) {
      // Bars with honest gaps: the eye (like the sim) sees through a grate.
      g.fillStyle = col;
      const step = sc / 6;
      if (lr) {
        for (let y = o.y; y + step * 0.55 <= o.y + ph; y += step) {
          g.fillRect(o.x, y, pw, Math.max(1, step * 0.55));
        }
      } else {
        for (let x = o.x; x + step * 0.55 <= o.x + pw; x += step) {
          g.fillRect(x, o.y, Math.max(1, step * 0.55), ph);
        }
      }
      continue;
    }
    g.fillStyle = col;
    g.fillRect(o.x, o.y, pw, ph);
    g.strokeStyle = seam;
    g.lineWidth = Math.max(1, sc * 0.03);
    g.strokeRect(o.x, o.y, pw, ph);
    // Segment/plank seams across the leaf.
    const step = sc / 3;
    g.beginPath();
    if (lr) {
      for (let y = o.y + step; y < o.y + ph - 1; y += step) {
        g.moveTo(o.x, y); g.lineTo(o.x + pw, y);
      }
    } else {
      for (let x = o.x + step; x < o.x + pw - 1; x += step) {
        g.moveTo(x, o.y); g.lineTo(x, o.y + ph);
      }
    }
    g.stroke();
    if (blast) {
      // The hazard-striped crush edge where the leaves meet.
      const nb = Math.max(2, sc * 0.12);
      g.fillStyle = '#d8b028';
      if (lr) {
        const y = nose > 0 ? o.y + ph - nb : o.y;
        g.fillRect(o.x, y, pw, nb);
      } else {
        const x = nose > 0 ? o.x + pw - nb : o.x;
        g.fillRect(x, o.y, nb, ph);
      }
    }
  }
}

/** The nest stamp, 11x9 art-pixels — hand-authored pixel art, identical to
 *  the Java NestPainter's STAMP so /sprites shows the pipelines agreeing.
 *  H/M/D are the mud ramp's twig tones, S the rare straw accent, o the
 *  blocky translucent hollow. */
const NEST_STAMP = [
  '...HHSHH...',
  '..HHMMMHH..',
  '.MMoooooMM.',
  '.MSoooooMM.',
  '.MMoooooMD.',
  '.MMoooooDD.',
  '.DMoooooDD.',
  '..DDMMMDD..',
  '...DDDDD...',
];
const NEST_SHADES: Record<string, string> = {
  H: '#775a38', M: '#574024', D: '#38291a', S: '#8a6a3c', o: 'rgba(20,14,8,0.35)',
};

/** A nest: the authored stamp scaled to one art-pixel per cell — drawn
 *  sprite-style, no computed geometry, no smooth curves (ART-STYLE.md). */
export function drawNest(g: CanvasRenderingContext2D, x: number, y: number, sc: number): void {
  const p = Math.max(1, sc / 12); // one art-pixel on screen
  const x0 = x - (NEST_STAMP[0].length / 2) * p;
  const y0 = y - (NEST_STAMP.length / 2) * p;
  for (let row = 0; row < NEST_STAMP.length; row++) {
    for (let col = 0; col < NEST_STAMP[row].length; col++) {
      const c = NEST_SHADES[NEST_STAMP[row][col]];
      if (!c) continue;
      // Edge-exact blocks: the translucent hollow must tile without overlap,
      // or the double-tinted seams read as grid lines.
      const rx = Math.round(x0 + col * p), ry = Math.round(y0 + row * p);
      g.fillStyle = c;
      g.fillRect(rx, ry, Math.round(x0 + (col + 1) * p) - rx,
        Math.round(y0 + (row + 1) * p) - ry);
    }
  }
}

export function drawItem(g: CanvasRenderingContext2D, kind: string, x: number, y: number, r: number, col: string): void {
  g.fillStyle = col;
  if (kind === 'item.crate') {
    g.fillRect(x - r, y - r, r * 2, r * 2);
    g.strokeStyle = '#6E4824';
    g.lineWidth = Math.max(1, r * 0.22);
    g.strokeRect(x - r, y - r, r * 2, r * 2);
    if (r > 5) { // braces only when big enough to read
      g.beginPath();
      g.moveTo(x - r, y - r); g.lineTo(x + r, y + r);
      g.moveTo(x + r, y - r); g.lineTo(x - r, y + r);
      g.stroke();
    }
  } else if (kind === 'item.hazard') {
    g.beginPath();
    for (let i = 0; i < 16; i++) {
      const a = (Math.PI * i) / 8;
      const rr = (i % 2 ? 0.45 : 1) * r * 1.3;
      if (i === 0) g.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
      else g.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
    }
    g.closePath();
    g.fill();
    g.strokeStyle = '#D83A4A';
    g.lineWidth = Math.max(1, r * 0.18);
    g.stroke();
  } else { // food: berry with a leaf
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
    g.fillStyle = '#4C8A33';
    g.beginPath(); g.arc(x + r * 0.4, y - r * 0.9, Math.max(1.5, r * 0.4), 0, 7); g.fill();
  }
}

/**
 * The viewer's status rings — grab (warm amber, being held), carry (cool cyan,
 * holding), follow (soft white pulse on the tracked creature), selected
 * (steady amber, the inspected one). Part of the overlay language, not world
 * art: smooth strokes are allowed here, and radius + colour together keep the
 * four meanings apart. `timeMs` drives the follow pulse only.
 */
export function drawRing(g: CanvasRenderingContext2D,
    kind: 'grabbed' | 'carrying' | 'follow' | 'selected',
    x: number, y: number, r: number, timeMs = 0): void {
  switch (kind) {
    case 'grabbed':
      g.strokeStyle = 'rgba(255,160,60,0.9)';
      g.lineWidth = Math.max(1, r * 0.2);
      g.beginPath(); g.arc(x, y, r * 1.4, 0, 7); g.stroke();
      return;
    case 'carrying':
      g.strokeStyle = 'rgba(0,229,255,0.6)';
      g.lineWidth = 1;
      g.beginPath(); g.arc(x, y, r * 1.3, 0, 7); g.stroke();
      return;
    case 'follow': {
      const pulse = 1.6 + 0.25 * Math.sin(timeMs / 180);
      g.strokeStyle = 'rgba(255,255,255,0.8)';
      g.lineWidth = 2;
      g.beginPath(); g.arc(x, y, r * pulse + 4, 0, 7); g.stroke();
      return;
    }
    case 'selected':
      g.strokeStyle = 'rgba(255,214,64,0.95)';
      g.lineWidth = Math.max(2, r * 0.18);
      g.beginPath(); g.arc(x, y, r * 1.7 + 3, 0, 7); g.stroke();
  }
}

/** The tether between a carrier and its cargo, drawn under both bodies. */
export function drawCarryLink(g: CanvasRenderingContext2D,
    x0: number, y0: number, x1: number, y1: number, scale: number): void {
  g.strokeStyle = 'rgba(0,229,255,0.55)';
  g.lineWidth = Math.max(1, scale * 0.02);
  g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1); g.stroke();
}

/** The pre-atlas placeholder: a coloured dot with a heading wedge, worn while
 *  a creature's sprite atlas is still in flight. A minded creature wears the
 *  rim violet as its edge, since there is no sprite to hug yet. */
export function drawPlaceholder(g: CanvasRenderingContext2D, x: number, y: number,
    r: number, dir: number, col: string, minded: boolean): void {
  g.fillStyle = col;
  g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  g.strokeStyle = minded ? RIM_COLOUR : 'rgba(0,0,0,0.45)';
  g.lineWidth = minded ? 2 : 1;
  g.stroke();
  g.fillStyle = 'rgba(255,255,255,0.85)';
  g.beginPath();
  g.moveTo(x + Math.cos(dir) * r * 1.35, y + Math.sin(dir) * r * 1.35);
  g.lineTo(x + Math.cos(dir + 2.5) * r * 0.55, y + Math.sin(dir + 2.5) * r * 0.55);
  g.lineTo(x + Math.cos(dir - 2.5) * r * 0.55, y + Math.sin(dir - 2.5) * r * 0.55);
  g.closePath(); g.fill();
}

/** Smallest on-screen body radius (device px) that earns an action badge. Below
 *  this the shape cannot be told apart from a coloured dot, so drawing it is
 *  noise; the map-overview zoom sits well under it. */
const GLYPH_MIN_BODY_PX = 5;

/** Colour for each action badge — matched to the Java snapshot renderer's palette
 *  so a scenario PNG and the live viewer read the same way. */
const ACTION_COLOUR: Record<number, string> = {
  [ACT_ATTACK]: '#E63C3C',
  [ACT_FLEE]: '#F0BE3C',
  [ACT_MATE]: '#F05AB4',
  [ACT_AFFILIATE]: '#46C8DC',
  [ACT_GRAZE]: '#46C85A',
  [ACT_NEST]: '#DC3CC8',
  [ACT_GRAB]: '#F09632',
  [ACT_HUNT]: '#FF7828',
};

/**
 * A small icon hovering over a creature saying what it is doing right now: `!`
 * attacking, heart mating, chevron fleeing, asterisk grazing, crosshair hunting,
 * hook carrying, house homing to a nest. Shapes mirror the Java snapshot
 * renderer's, so a test PNG and the live world are read the same way.
 *
 * Drawn on a dark disc so it stays legible over grass, water or a pale body.
 */
export function drawActionGlyph(g: CanvasRenderingContext2D, cx: number, cy: number,
                                u: number, action: number): void {
  if (!action) return; // nothing worth showing (the zoom gate is at the call site)
  const col = ACTION_COLOUR[action];
  if (!col) return;

  g.fillStyle = 'rgba(12,14,18,0.55)';
  g.beginPath(); g.arc(cx, cy, u * 1.15, 0, 7); g.fill();

  g.strokeStyle = col;
  g.fillStyle = col;
  g.lineWidth = Math.max(1, u * 0.28);
  g.lineCap = 'round';
  g.beginPath();
  switch (action) {
    case ACT_ATTACK: // exclamation
      g.moveTo(cx, cy - u * 0.6); g.lineTo(cx, cy + u * 0.15);
      g.stroke();
      g.beginPath(); g.arc(cx, cy + u * 0.55, Math.max(1, u * 0.16), 0, 7); g.fill();
      return;
    case ACT_FLEE: // chevron, pointing away
      g.moveTo(cx - u * 0.55, cy + u * 0.3);
      g.lineTo(cx, cy - u * 0.4);
      g.lineTo(cx + u * 0.55, cy + u * 0.3);
      g.stroke();
      return;
    case ACT_MATE: { // heart
      const t = u * 0.62;
      g.moveTo(cx, cy + t * 0.85);
      g.bezierCurveTo(cx - t * 1.7, cy - t * 0.35, cx - t * 0.55, cy - t * 1.3, cx, cy - t * 0.35);
      g.bezierCurveTo(cx + t * 0.55, cy - t * 1.3, cx + t * 1.7, cy - t * 0.35, cx, cy + t * 0.85);
      g.fill();
      return;
    }
    case ACT_AFFILIATE: // plus
      g.moveTo(cx - u * 0.5, cy); g.lineTo(cx + u * 0.5, cy);
      g.moveTo(cx, cy - u * 0.5); g.lineTo(cx, cy + u * 0.5);
      g.stroke();
      return;
    case ACT_GRAZE: // asterisk
      for (let i = 0; i < 3; i++) {
        const a = (i * Math.PI) / 3;
        const dx = Math.cos(a) * u * 0.55, dy = Math.sin(a) * u * 0.55;
        g.moveTo(cx - dx, cy - dy); g.lineTo(cx + dx, cy + dy);
      }
      g.stroke();
      return;
    case ACT_HUNT: // crosshair: locked on
      g.arc(cx, cy, u * 0.4, 0, 7);
      g.moveTo(cx - u * 0.65, cy); g.lineTo(cx - u * 0.4, cy);
      g.moveTo(cx + u * 0.4, cy); g.lineTo(cx + u * 0.65, cy);
      g.stroke();
      return;
    case ACT_GRAB: // hook
      g.arc(cx, cy, u * 0.45, 0, 7);
      g.moveTo(cx, cy); g.lineTo(cx + u * 0.55, cy + u * 0.55);
      g.stroke();
      return;
    case ACT_NEST: // house
      g.moveTo(cx - u * 0.45, cy + u * 0.5);
      g.lineTo(cx - u * 0.45, cy - u * 0.05);
      g.lineTo(cx, cy - u * 0.55);
      g.lineTo(cx + u * 0.45, cy - u * 0.05);
      g.lineTo(cx + u * 0.45, cy + u * 0.5);
      g.closePath();
      g.stroke();
      return;
  }
}
