// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import {
  ART_RADIUS, CELL, DIRS, MIP, atlasFor, atlasMipFor, cell, corpseFor,
  corpseMipFor, decayStage, headingCol, tintedFor,
} from './atlas';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_CARRY, ACT_FLEE, ACT_GRAZE, ACT_HUNT, ACT_MATE,
  ACT_NEST, ACT_RIDE, actionOf, F_CARRYING, F_DEAD, F_GRABBED,
} from './protocol';
import type { EntityState } from './protocol';
import type { Track, WorldState } from './state';

export interface WorldMeta { cols: number; rows: number; }

// ---- the vegetation layer, chunked ----------------------------------------
//
// Vegetation renders as one-tile sprites stamped OVER the vegetation-free
// ground bake, held in PER-CHUNK canvases and textures (the ground's own
// chunk grid). Chunk granularity is what a mobile GPU wants: a repaint
// re-uploads one ~144px texture wholesale instead of patching a 7-megapixel
// monolith, whose per-frame mutation made tile drivers ghost-copy the whole
// thing. Updates are EVENTUALLY CONSISTENT by design: a vegetation poll only
// marks chunks dirty, and at most VEG_CHUNKS_PER_FRAME repaint per frame —
// the drawn field trails the sim by a few seconds, invisible on something
// that changes over minutes, and the frame never pays a burst.
let vegPainted: Uint8Array | null = null; // last state painted, per tile
let vegChunkRevs = new Map<number, number>();
let vegChunkCvs = new Map<number, HTMLCanvasElement>();
let vegDirty: number[] = []; // chunk keys owed a repaint, in discovery order
let vegDirtySet = new Set<number>();
let vegSrcRev = -1;
let vegLayerLevel = -1;
let vegLow: HTMLCanvasElement | null = null; // 3px/tile world mirror, far zoom
let vegLowRev = 0;
let vegLowDirty = false;
let vegLowAt = 0;
const VEG_CHUNKS_PER_FRAME = 2;
const VEG_LOW_MIN_MS = 1500;
const ART = 12;

function vegVariant(tx: number, ty: number): number {
  return Math.floor(hash01(tx, ty, 11) * VEG_VARIANTS);
}

/** Scans for moved states, repaints its budget of dirty chunks, and keeps the
 *  far-zoom mirror fresh; the draw passes read vegChunkCvs/vegLow directly. */
function vegLayerUpdate(meta: WorldMeta, chunkTiles: number,
    veg: Uint8Array, vegRev: number, level: number, nowMs: number): void {
  const cxN = Math.ceil(meta.cols / chunkTiles);
  if (level !== vegLayerLevel || !vegPainted || vegPainted.length !== meta.cols * meta.rows) {
    vegPainted = new Uint8Array(meta.cols * meta.rows);
    vegChunkRevs = new Map();
    vegChunkCvs = new Map();
    vegDirty = [];
    vegDirtySet = new Set();
    vegLayerLevel = level;
    vegSrcRev = -1;
    if (!vegLow || vegLow.width !== meta.cols * LAYER_LOW) {
      vegLow = document.createElement('canvas');
      vegLow.width = meta.cols * LAYER_LOW;
      vegLow.height = meta.rows * LAYER_LOW;
    }
    vegLow.getContext('2d')!.clearRect(0, 0, vegLow.width, vegLow.height);
    vegLowRev++;
  }
  if (vegRev !== vegSrcRev) {
    // Cheap pass: integer compares over the grid; painting is metered below.
    for (let i = 0; i < vegPainted.length; i++) {
      if (veg[i] !== vegPainted[i]) {
        const key = Math.floor((i % meta.cols) / chunkTiles)
          + Math.floor(i / meta.cols / chunkTiles) * cxN;
        if (!vegDirtySet.has(key)) {
          vegDirtySet.add(key);
          vegDirty.push(key);
        }
      }
    }
    vegSrcRev = vegRev;
  }
  for (let n = 0; n < VEG_CHUNKS_PER_FRAME && vegDirty.length > 0; n++) {
    const key = vegDirty.shift()!; // ≤ ~100 entries: shift is nothing
    vegDirtySet.delete(key);
    const cx = key % cxN, cy = Math.floor(key / cxN);
    const wTiles = Math.min(chunkTiles, meta.cols - cx * chunkTiles);
    const hTiles = Math.min(chunkTiles, meta.rows - cy * chunkTiles);
    let cvc = vegChunkCvs.get(key);
    if (!cvc) {
      cvc = document.createElement('canvas');
      cvc.width = chunkTiles * ART;
      cvc.height = chunkTiles * ART;
      vegChunkCvs.set(key, cvc);
    }
    const cg = cvc.getContext('2d')!;
    cg.clearRect(0, 0, cvc.width, cvc.height);
    cg.imageSmoothingEnabled = false;
    for (let ty = 0; ty < hTiles; ty++) {
      for (let tx = 0; tx < wTiles; tx++) {
        const gx = cx * chunkTiles + tx, gy = cy * chunkTiles + ty;
        const i = gy * meta.cols + gx;
        const s = veg[i];
        vegPainted[i] = s;
        // The high bit says which vegetation this tile grows; the low bits are
        // the growth stage. This used to be hardcoded to grass, so the cave's
        // fungus beds were drawn as meadow and the mushroom sprite — baked all
        // along, and visible in the catalog — never reached the world.
        const stage = s & VEG_STAGE_MASK;
        if (stage > 0) {
          const kind: VegKind = (s & VEG_KIND_MASK) !== 0 ? 'mushroom' : 'grass';
          cg.drawImage(vegetationTileFor(kind, stage, vegVariant(gx, gy)), tx * ART, ty * ART);
        }
      }
    }
    vegChunkRevs.set(key, (vegChunkRevs.get(key) ?? 0) + 1);
    const lg = vegLow!.getContext('2d')!;
    lg.imageSmoothingEnabled = true;
    lg.clearRect(cx * chunkTiles * LAYER_LOW, cy * chunkTiles * LAYER_LOW,
      wTiles * LAYER_LOW, hTiles * LAYER_LOW);
    lg.drawImage(cvc, 0, 0, wTiles * ART, hTiles * ART,
      cx * chunkTiles * LAYER_LOW, cy * chunkTiles * LAYER_LOW,
      wTiles * LAYER_LOW, hTiles * LAYER_LOW);
    vegLowDirty = true;
  }
  if (vegLowDirty && nowMs - vegLowAt >= VEG_LOW_MIN_MS) {
    vegLowRev++; // one small upload for the far-zoom texture, throttled
    vegLowDirty = false;
    vegLowAt = nowMs;
  }
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
/** The level whose layer textures are currently resident on the GPU, so the
 *  set for a floor the viewer has left can be handed back (GLRenderer
 *  .evictLevel). -1 until the first frame draws one. */
let glLayerLevel = -1;
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
    getChunk: (cx: number, cy: number, z: number) => HTMLCanvasElement | null,
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
        const chunk = getChunk(cx, cy, level);
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
      const chunk = getChunk(cx, cy, level);
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

// ---- the level below, seen through the holes -------------------------------
//
// A pit's open art-pixels reach the client TRANSPARENT: the bake clears to
// nothing and the ground pass declines to paint them (Grid.pitFloor), so what
// shows through a hole is a real second layer rather than a picture of one.
//
// That layer is drawn scaled about the SCREEN CENTRE by PARALLAX, which is the
// projection of a plane one storey further from the eye: pan by d and the top
// layer moves d while the floor below moves 0.90d, so the view down a hole
// slides as you travel. That slide is the whole point — it is what separates
// "there is a place down there" from "someone painted rock on the lid". The
// factor is a storey read at arm's length, not surveyed geometry: 0.90 made
// the slide easy to catch but read as too much — pit floors visibly swimming
// away from their mouths at the screen's edges — so this sits at the 0.94 a
// far camera over a one-storey drop actually projects: subtler on purpose,
// realism over showmanship.
//
// The dimming of what shows through is the bake's business, not this pass's:
// the pit interior arrives as a flat black veil at RenderFx.holeDepth opacity
// with real alpha beneath (Grid.veilPixel), so the floor reads through whole
// and dark, and nothing here invents a shade.
//
// Cost is proportional to the holes, not to the world. Only chunks actually IN
// VIEW are considered, and only those whose own art has any transparency ask
// for the chunk beneath them. A world with no pits therefore never fetches a
// second level at all. The pass runs at EVERY zoom: the world opens at its fit
// zoom (~9 px/tile on an ordinary window), and a below-layer that only existed
// past 12 px/tile was a feature nobody saw — at fit zoom the openings are
// small, but a hole that is dark blue nothing until the third wheel-click
// reads as a bug, not a hole.
export const PARALLAX = 0.94;
/** The factor actually applied: parallax=0 in the URL (or the ⚙ dialog) pins
 *  it to 1.0 — the floors below still show through every pit, they just stop
 *  sliding. A viewer preference as much as a perf experiment: the slide is a
 *  depth cue some eyes read as the world coming apart. */
const parallaxFactor = (): number =>
  PARALLAX_OFF ? 1.0 : PARALLAX;

// Whether a decoded chunk has any see-through art-pixel, memoised against the
// canvas itself: one getImageData per chunk for the life of the tab, and it
// dies with the chunk. Without the memo this would be a full readback of every
// visible chunk every frame.
const chunkHoles = new WeakMap<HTMLCanvasElement, boolean>();
function hasHoles(chunk: HTMLCanvasElement): boolean {
  const memo = chunkHoles.get(chunk);
  if (memo !== undefined) return memo;
  let holed = false;
  try {
    const d = chunk.getContext('2d', { willReadFrequently: true })!
      .getImageData(0, 0, chunk.width, chunk.height).data;
    for (let i = 3; i < d.length; i += 4) {
      if (d[i] < 255) { holed = true; break; }
    }
  } catch {
    holed = false; // a tainted or zero-sized canvas simply has no holes to show
  }
  chunkHoles.set(chunk, holed);
  return holed;
}

/** For every visible chunk whose art has holes, the chunks below it and the
 *  screen rects their parallax puts them in, deepest first: [key, z, canvas,
 *  x, y, w, h]. Where the level below is itself holed over the same chunk —
 *  the gorge cutting through two storeys — the level under THAT is drawn
 *  first at PARALLAX², a plane two storeys from the eye, so the stack reads
 *  as one deepening cut rather than a hole with a hole painted on its
 *  floor. */
function belowChunks(cam: Camera, cvW: number, cvH: number, meta: WorldMeta,
    chunkTiles: number, level: number,
    getChunk: (cx: number, cy: number, z: number) => HTMLCanvasElement | null,
): Array<[number, number, HTMLCanvasElement, number, number, number, number]> {
  const out: Array<[number, number, HTMLCanvasElement, number, number, number, number]> = [];
  if (level - 1 < 0 || chunkTiles <= 0) return out;
  const cxN = Math.ceil(meta.cols / chunkTiles), cyN = Math.ceil(meta.rows / chunkTiles);
  const tl = cam.screenToWorld(0, 0), br = cam.screenToWorld(cvW, cvH);
  const cx0 = Math.max(0, Math.floor(tl.x / chunkTiles));
  const cy0 = Math.max(0, Math.floor(tl.y / chunkTiles));
  const cx1 = Math.min(cxN - 1, Math.floor(br.x / chunkTiles));
  const cy1 = Math.min(cyN - 1, Math.floor(br.y / chunkTiles));
  const mx = cvW / 2, my = cvH / 2;
  const rect = (cx: number, cy: number, factor: number):
      [number, number, number, number] => {
    const wx = cx * chunkTiles, wy = cy * chunkTiles;
    const cw = Math.min(chunkTiles, meta.cols - wx);
    const ch = Math.min(chunkTiles, meta.rows - wy);
    const o = cam.worldToScreen(wx, wy);
    return [mx + (o.x - mx) * factor, my + (o.y - my) * factor,
      cw * cam.scale * factor, ch * cam.scale * factor];
  };
  for (let cy = cy0; cy <= cy1; cy++) {
    for (let cx = cx0; cx <= cx1; cx++) {
      const top = getChunk(cx, cy, level);
      if (!top || !hasHoles(top)) continue;
      const below = getChunk(cx, cy, level - 1);
      if (!below) continue;
      const key = cx + cy * cxN;
      if (level - 2 >= 0 && hasHoles(below)) {
        const below2 = getChunk(cx, cy, level - 2);
        if (below2) {
          out.push([key, level - 2, below2,
              ...rect(cx, cy, parallaxFactor() * parallaxFactor())]);
        }
      }
      out.push([key, level - 1, below, ...rect(cx, cy, parallaxFactor())]);
    }
  }
  return out;
}

/** Camera zoom (px per tile) below which EVERY creature draws as a flat
 *  colour block instead of a sprite stamp. One decision per frame, from the
 *  zoom alone: keying the tier on each body's own on-screen size mixed
 *  sprites and squares in the same view (adults as sprites, their young as
 *  blocks beside them), and the old adaptive frame-time threshold sat in a
 *  feedback loop on mid-range phones — degrading to dots made frames cheap
 *  enough to restore sprites, which made them slow enough to degrade again,
 *  so the whole herd blinked between the two tiers. Zoom is the one input
 *  that is stable frame to frame and identical for every body. */
export const DOT_LOD_SCALE = 8;

// Perf experiment switches (the ⚙ dialog in main.ts writes these): each
// disables one visual subsystem so its cost can be isolated on a device.
const flagOff = (k: string): boolean =>
  typeof location !== 'undefined' && new RegExp('[?&]' + k + '=0\\b').test(location.search);
const SPRITES_OFF = flagOff('sprites'); // creatures as dots only
const PHERO_OFF = flagOff('phero');     // pheromone clouds
const PARALLAX_OFF = flagOff('parallax'); // below-floor slide (floors still show)
const CANOPY_OFF = flagOff('canopy');   // foliage veils + duct lids
const OVERLAY_OFF = flagOff('overlay'); // action badges, rings, carry links

/** The map-view dot: a corpse is a spent grey block; a live body its colour. */
export function drawDot(g: CanvasRenderingContext2D, x: number, y: number,
    bodyPx: number, col: string, dead: boolean): void {
  const s = Math.max(4, bodyPx);
  g.fillStyle = dead ? '#5a5f66' : col;
  g.fillRect(x - s / 2, y - s / 2, s, s);
}

export function render(
  g: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  chunkTiles: number,
  tilePx: number,
  getChunk: (cx: number, cy: number, z: number) => HTMLCanvasElement | null,
  veg: Uint8Array | null,
  vegRev: number,
  cover: Uint8Array | null,
  renderTime: number,
  nowMs: number,
  level = 0,
  selection: { id: number | null; tile: { x: number; y: number; z: number } | null } =
    { id: null, tile: null },
): void {
  const cv = g.canvas;
  const dots = SPRITES_OFF || cam.scale < DOT_LOD_SCALE;
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
      for (const [, , img, bx, by, bw, bh] of
          belowChunks(cam, cv.width, cv.height, meta, chunkTiles, level, getChunk)) {
        g.drawImage(img, bx, by, bw, bh);
      }
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
    // The floor below goes down FIRST, under its own parallax, so the holes in
    // the chunks drawn next look onto it (see belowChunks).
    for (const [, , img, bx, by, bw, bh] of
        belowChunks(cam, cv.width, cv.height, meta, chunkTiles, level, getChunk)) {
      g.drawImage(img, bx, by, bw, bh);
    }
    for (let cy = cy0; cy <= cy1; cy++) {
      for (let cx = cx0; cx <= cx1; cx++) {
        const img = getChunk(cx, cy, level); // lazily fetches + caches this chunk
        if (!img) continue;
        const wx = cx * chunkTiles, wy = cy * chunkTiles;
        const cw = Math.min(chunkTiles, meta.cols - wx);
        const ch = Math.min(chunkTiles, meta.rows - wy);
        const o = cam.worldToScreen(wx, wy);
        g.drawImage(img, o.x, o.y, cw * cam.scale, ch * cam.scale);
      }
    }
  }

  // Vegetation: one-tile sprites over the bare ground, chunk-cached (see
  // vegLayerUpdate). Far zoom blits the small pre-smoothed mirror; near zoom
  // blits the visible chunks crisply.
  if (veg && chunkTiles > 0) {
    vegLayerUpdate(meta, chunkTiles, veg, vegRev, level, nowMs);
    const o = cam.worldToScreen(0, 0);
    if (cam.scale < ART && vegLow) {
      g.imageSmoothingEnabled = true;
      g.drawImage(vegLow, o.x, o.y, meta.cols * cam.scale, meta.rows * cam.scale);
      g.imageSmoothingEnabled = false;
    } else {
      const cxN = Math.ceil(meta.cols / chunkTiles);
      g.imageSmoothingEnabled = false;
      for (const [key, cvc] of vegChunkCvs) {
        const wx = (key % cxN) * chunkTiles, wy = Math.floor(key / cxN) * chunkTiles;
        const oc = cam.worldToScreen(wx, wy);
        const wpx = chunkTiles * cam.scale;
        if (oc.x > cv.width || oc.y > cv.height || oc.x + wpx < 0 || oc.y + wpx < 0) continue;
        g.drawImage(cvc, oc.x, oc.y, wpx, wpx);
      }
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
    if (e.kind === 'sound') continue; // an event, not a body: the sense overlay draws it

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

    // The steward's drone: machinery, drawn rather than grown. It has no
    // phenotype to build an atlas from, so without this it falls through to
    // the no-atlas dot and the one thing in the world that is not alive looks
    // exactly like everything that is.
    if (e.kind === 'npc.stewarddrone' || e.kind === 'npc.facilityloader') {
      const dc = headingCol(p.dir, t.col ?? -1);
      t.col = dc;
      if (e.kind === 'npc.facilityloader') drawLoader(g, s.x, s.y, cam.scale, dc);
      else drawSentinel(g, s.x, s.y, cam.scale, dc);
      continue;
    }

    // Creature. At map zoom (see DOT_LOD_SCALE) every body skips the sprite
    // pipeline: one or two fillRects instead of a drawImage, which is most of
    // the frame at that zoom with a large population.
    const bodyPx = e.size * cam.scale * 2;
    if (e.flags & F_DEAD) {
      if (dots) {
        drawDot(g, s.x, s.y, bodyPx, '', true);
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
    const atlas = dots ? null : atlasFor(e.pheno);
    if (dots) {
      drawDot(g, s.x, s.y, bodyPx, '#' + e.rgb.toString(16).padStart(6, '0'), false);
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
        // Far zoom: one stamp from the quarter-size mip.
        const c = CELL / MIP;
        const src = atlasMipFor(tk, tinted);
        g.drawImage(src, cc * c, rr * c, c, c, s.x - box / 2, s.y - box / 2, box, box);
      } else {
        const src = tinted;
        g.drawImage(src, cc * CELL, rr * CELL, CELL, CELL, s.x - box / 2, s.y - box / 2, box, box);
      }
    } else {
      // The colour string is built only here: the atlas path never needs it,
      // and building one per creature per frame is measurable at herd scale.
      drawPlaceholder(g, s.x, s.y, r, p.dir,
        '#' + e.rgb.toString(16).padStart(6, '0'));
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
      if (e.kind === 'phero' || e.kind === 'sound' || (e.flags & F_DEAD) || Math.round(e.z) !== level) continue;
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
// the whole WORLD — ground, vegetation, entity sprites, canopy, lids — as
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

/** The sentinel glyph baked once per colour as a strip of DIRS heading frames —
 *  same painter as the 2D path, at a reference radius, then scaled by the GPU.
 *  A sentinel has a front, so unlike an item it needs a column per heading; it
 *  reuses the creature atlases' bucket count and their sticky headingCol so the
 *  two never disagree about which way a body is pointing. */
/**
 * The steward's drone: a HAND-AUTHORED pixel stamp, the exact twin of the Java
 * renderer's DronePainter — same two silhouettes, same shading rule, same steel
 * ramp — so /sprites shows the two pipelines agreeing rather than diverging.
 *
 * The shape is a sentinel, not a quadrotor: a compact pod with two broad plates
 * held off to either side, so it reads as panels kept in formation around a
 * core rather than as something held up by spinning parts. It has a front — the
 * eye leads, the plates trail — because a body that visibly points where it is
 * going tells you what it is about to do.
 *
 * What this replaced was rotated polygons in three invented greys with an
 * anti-aliased arc for the eye: off-lattice, off-palette, unshaded, and drawn
 * with the smooth curves ART-STYLE.md section 4 forbids outright. It passed CI,
 * because none of that is mechanically checkable. The scenarios added alongside
 * this now check it.
 */
const SENTINEL_N = 13;

/** Facing east. '#' is body, 'A' the eye.
 *
 *  The hull is the mass and the plates are secondary, which is the whole
 *  difference between a sentinel and a pile of bars. Plates as heavy as the
 *  hull read as a stack of planks; plates joined by broad pylons invert the
 *  reading entirely, so the vertical mass wins and the machine looks like it
 *  faces north whichever way the eye points. A dominant hull, small plates set
 *  clear of it, and a pylon exactly one art-pixel wide is what works. Eleven
 *  across against ten along, so it stays wider than it is long.
 *
 *  The rearmost rank is chassis iron, and that dark cap is what tells front
 *  from back — one accent pixel reads as "there is a lamp" long before it
 *  reads as "and so that end is the front". A snout at the other end was
 *  tried and dropped: one art-pixel of protrusion is invisible at the size
 *  the drone is actually seen. The tail carries the read. */
const SENTINEL_CARDINAL = [
  '.............',
  '....SSSSS....',
  '....SSSSS....',
  '......C......',
  '...########..',
  '..C#########.',
  '..C########A.',
  '..C#########.',
  '...########..',
  '......C......',
  '....SSSSS....',
  '....SSSSS....',
  '.............',
];

/** Facing south-east. Authored rather than derived — no lattice-exact transform
 *  reaches a diagonal from a cardinal, which means the two are only as
 *  consistent as someone made them, and the first version was not: measured
 *  along its own axis that hull ran 13 art-pixels by under 4, against the
 *  cardinal's 10 by 5, so turning 45 degrees made the machine 30% longer and a
 *  quarter thinner. This one is laid out in the body's own axes — 10 long, 5
 *  across, plates at the same perpendicular offset — with blunted tips,
 *  because a rotated rectangle ends in single pixels on a square lattice and a
 *  one-pixel point is the lumpy-math tell. */
const SENTINEL_DIAGONAL = [
  '.............',
  '........SS...',
  '...C##.SSSS..',
  '..C####.SSSS.',
  '.C######.SSS.',
  '..#######....',
  '...#######...',
  '....#######..',
  '.SSS.#######.',
  '.SSSS.####A..',
  '..SSSS.###...',
  '...SS........',
  '.............',
];

/** The ground shadow: sanctioned translucency 2 of 4, the blocky translucent
 *  oval. Authored as its own shape rather than copied from the silhouette — a
 *  silhouette copy fills the gaps between hull and plates that the design
 *  depends on, and pushed clear of the glyph it reads as a second dark plate
 *  rather than as ground. It needs no rotation: the drone turns, the light
 *  does not. */
const SENTINEL_OVAL = [
  '.#####.',
  '#######',
  '.#####.',
];

/** The drone is machinery, so it is built from the door's steel — the world's
 *  existing ramp, not a new grey. The eye is the charge dock's hazard amber, so
 *  drone and berth read as a matched pair. The sunk south edge is the base
 *  shade at section 4's x0.65, not the near-black iron a first pass used —
 *  iron is a housing colour and against the deck it reads as a hole punched in
 *  the machine rather than an edge turned from the light. */
const HAZARD_RGB = 0xd8b028;
const scale = (rgb: number, f: number): string => {
  const c = (v: number) => Math.max(0, Math.min(255, Math.round(v)));
  return '#' + ((c(((rgb >> 16) & 0xff) * f) << 16) | (c(((rgb >> 8) & 0xff) * f) << 8)
    | c((rgb & 0xff) * f)).toString(16).padStart(6, '0');
};
/** The facility's own safety yellow, given the two shades it needs to be a body
 *  colour rather than a stripe. Nothing invented: 0xd8b028 is the hazard yellow
 *  already on the dock's keep-clear border and every other "machinery works
 *  here" marking underground, and the shadow and highlight come off it by
 *  section 4's own x0.65 and x1.18. Section 2 asks for exactly this promotion —
 *  an accent common enough to read as a texture IS a ramp colour now. */
const SENTINEL_SHADES: Record<string, string> = {
  H: scale(HAZARD_RGB, 1.18),   // hull, lit north edge
  M: '#d8b028',                 // hull, mid
  I: '#14161f',                 // hull, sunk south edge — iron, see below
  h: scale(HAZARD_RGB, 1.18),   // plate, lit
  m: '#d8b028',
  d: scale(HAZARD_RGB, 0.65),   // plate, sunk — yellow: a plate is thin
  K: '#17171a',                 // the hazard checker's black, a marking
  C: '#14161f',                 // pylon: chassis iron
  A: '#E0455F',                 // the warning lamp, the signal family's red
};

/** Sanctioned translucency 1 of 4, and how far south it falls. A standing body
 *  casts at one art-pixel; the drone casts at four, because it is the only
 *  thing here that genuinely does not touch the ground and the gap is the sole
 *  cue that says so. Seven art-pixels and not one or two: the stamp is
 *  thirteen tall with the plates at its extremes, so any smaller drop lands
 *  the pod's shadow on the south plate, where it reads as a black box welded
 *  under the machine rather than as ground. */
const SENTINEL_SHADOW = 'rgba(0,0,0,0.31)';
const SENTINEL_LIFT = 8;

function machineRot90(s: string[]): string[] {
  const n = s.length, o: string[] = [];
  for (let r = 0; r < n; r++) {
    let row = '';
    for (let c = 0; c < n; c++) row += s[n - 1 - c][r];
    o.push(row);
  }
  return o;
}

/** One sun, straight overhead-north, and it does not turn with the drone: the
 *  light is laid on AFTER rotation, so a heading never carries its highlight
 *  around and lights the machine from underneath. In each column the north cell
 *  of a contiguous run is lit and the south cell sunk; a run one art-pixel tall
 *  stays mid, being its own north and south edge at once. */
function machineShade(s: string[]): string[] {
  const n = s.length;
  const o: string[][] = [];
  for (let r = 0; r < n; r++) o.push(new Array(n).fill('.'));
  const body = (ch: string) => ch === '#' || ch === 'S' || ch === 'C';
  for (let c = 0; c < n; c++) {
    let r = 0;
    while (r < n) {
      if (body(s[r][c])) {
        const r0 = r;
        while (r < n && body(s[r][c])) r++;
        for (let k = r0; k < r; k++) {
          const pos = r - r0 === 1 ? 1 : k === r0 ? 2 : k === r - 1 ? 0 : 1;
          o[k][c] = machineMark(s[k][c], pos, k, c);
        }
      } else {
        if (s[r][c] === 'A') o[r][c] = 'A';
        r++;
      }
    }
  }
  return o.map((row) => row.join(''));
}

/** Material and lighting compose into one mark. The hazard checker is resolved
 *  here so the stamps stay pure data, and indexed body-locally rather than
 *  world-absolutely — world indexing is for ground, and a world-anchored
 *  pattern would crawl across the hull as the machine moved. A one-on-one-off
 *  checker is its own reflection under a quarter turn on an odd grid, so it
 *  survives the derived headings unchanged. */
function machineMark(material: string, pos: number, row: number, col: number): string {
  if (material === 'C') return 'C';
  if (material === 'S') {
    return ((row + col) & 1) === 0 ? (pos === 2 ? 'h' : pos === 0 ? 'd' : 'm') : 'K';
  }
  return pos === 2 ? 'H' : pos === 0 ? 'I' : 'M';
}

/** Two authored silhouettes into eight headings: six are exact 90-degree
 *  lattice rotations, and the light goes on afterwards so no facing carries its
 *  highlight around the compass. */
function machineFacings(cardinal: string[], diagonal: string[]): string[][] {
  const out: string[][] = [];
  for (let i = 0; i < DIRS; i++) {
    let s = (i & 1) === 0 ? cardinal : diagonal;
    for (let q = 0; q < i >> 1; q++) s = machineRot90(s);
    out.push(machineShade(s));
  }
  return out;
}

const SENTINEL_FACING: string[][] = machineFacings(SENTINEL_CARDINAL, SENTINEL_DIAGONAL);

/**
 * The facility loader: the same livery and the same rule as the drone, on a
 * body that stands on the floor instead of hanging over it. Seventeen
 * art-pixels to the drone's thirteen — a size-20 body against a size-16 one,
 * drawn at the same scale.
 *
 * The Java twin is LoaderPainter and holds these exact strings; a scenario
 * compares them, because the two files cannot share a literal and "somebody
 * remembered to edit both" is not a mechanism.
 */
const LOADER_CARDINAL = [
  '.................',
  '.................',
  '.................',
  '.................',
  '....SSS######....',
  '....SSS#########.',
  '....SSS#########.',
  '....SSS######....',
  '....SSS######A...',
  '....SSS######....',
  '....SSS#########.',
  '....SSS#########.',
  '....SSS######....',
  '.................',
  '.................',
  '.................',
  '.................',
];

/** Facing south-east. Its forks are one blade rather than two prongs: the gap
 *  between the cardinal's forks is two art-pixels and a diagonal staircase
 *  closes a gap that narrow, so two prongs here would be two prongs that touch
 *  — a smudge where the eye expects a shape. What the lattice can express at
 *  forty-five degrees is not what it can express square-on. */
const LOADER_DIAGONAL = [
  '.................',
  '.................',
  '.................',
  '.................',
  '.......SSS.......',
  '......SSS##......',
  '.....SSS####.....',
  '....SSS######....',
  '...SSS########...',
  '.....########....',
  '.....######A.##..',
  '......#####......',
  '.......###.......',
  '.........##......',
  '..........##.....',
  '.................',
  '.................',
];

const LOADER_FACING: string[][] = machineFacings(LOADER_CARDINAL, LOADER_DIAGONAL);

/** A standing body's contact shadow: its own silhouette one art-pixel south,
 *  and darker than a flyer's because it is cast from directly underneath
 *  rather than across a gap of air. The drone's oval would vanish entirely
 *  under a body this wide. */
const LOADER_CONTACT = 'rgba(0,0,0,0.42)';
const LOADER_DROP = 1;

/** Stamp the loader. `sc` is the tile size in screen pixels, so an art-pixel is
 *  sc/12 exactly as everywhere else. */
export function drawLoader(g: CanvasRenderingContext2D, x: number, y: number,
    sc: number, bucket: number): void {
  const p = Math.max(1, sc / 12);
  const f = LOADER_FACING[((bucket % DIRS) + DIRS) % DIRS];
  blit(g, f, x, y + LOADER_DROP * p, p, LOADER_CONTACT);
  blit(g, f, x, y, p, null);
}

export function drawSentinel(g: CanvasRenderingContext2D, x: number, y: number,
    sc: number, bucket: number): void {
  const p = Math.max(1, sc / 12); // one art-pixel on screen
  blit(g, SENTINEL_OVAL, x, y + SENTINEL_LIFT * p, p, SENTINEL_SHADOW);
  blit(g, SENTINEL_FACING[((bucket % DIRS) + DIRS) % DIRS], x, y, p, null);
}

/** Stamp art-pixel blocks, centred. Edge-exact: each block's extent comes from
 *  its neighbour's rounded edge, never a rounded-up box, or the translucent
 *  oval's overlaps double-tint into grid lines. */
function blit(g: CanvasRenderingContext2D, rows: string[], cx: number, cy: number,
    p: number, flat: string | null): void {
  const x0 = cx - (rows[0].length / 2) * p;
  const y0 = cy - (rows.length / 2) * p;
  for (let row = 0; row < rows.length; row++) {
    for (let col = 0; col < rows[row].length; col++) {
      const ch = rows[row][col];
      if (ch === '.') continue;
      const c = flat ?? SENTINEL_SHADES[ch];
      if (!c) continue;
      g.fillStyle = c;
      const rx = Math.round(x0 + col * p), ry = Math.round(y0 + row * p);
      g.fillRect(rx, ry, Math.round(x0 + (col + 1) * p) - rx,
        Math.round(y0 + (row + 1) * p) - ry);
    }
  }
}

const SENTINEL_BAKE_P = 8;                       // atlas pixels per art-pixel
const SENTINEL_CELL = SENTINEL_N * SENTINEL_BAKE_P; // a whole number of them
let droneStampCache: HTMLCanvasElement | null = null;
function droneStamp(): HTMLCanvasElement {
  if (!droneStampCache) droneStampCache = bakeMachine(SENTINEL_N, drawSentinel);
  return droneStampCache;
}

const LOADER_N = LOADER_CARDINAL.length;
const LOADER_CELL = LOADER_N * SENTINEL_BAKE_P;
let loaderStampCache: HTMLCanvasElement | null = null;
function loaderStamp(): HTMLCanvasElement {
  if (!loaderStampCache) loaderStampCache = bakeMachine(LOADER_N, drawLoader);
  return loaderStampCache;
}

/** Bake all eight headings into one strip. The palettes are fixed, so one bake
 *  serves every body of that kind, and the cell is a whole number of
 *  art-pixels so the bake itself is on-lattice before the GPU scales it. */
function bakeMachine(n: number,
    paint: (g: CanvasRenderingContext2D, x: number, y: number, sc: number,
      bucket: number) => void): HTMLCanvasElement {
  const cell = n * SENTINEL_BAKE_P;
  const cv = document.createElement('canvas');
  cv.width = cell * DIRS;
  cv.height = cell;
  const cx = cv.getContext('2d')!;
  for (let i = 0; i < DIRS; i++) {
    paint(cx, cell * i + cell / 2, cell / 2, SENTINEL_BAKE_P * 12, i);
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
  getChunk: (cx: number, cy: number, z: number) => HTMLCanvasElement | null,
  veg: Uint8Array | null,
  vegRev: number,
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
  const dots = SPRITES_OFF || cam.scale < DOT_LOD_SCALE;
  glr.begin(cv.width, cv.height, 0x14 / 255, 0x16 / 255, 0x1a / 255);
  og.clearRect(0, 0, cv.width, cv.height);
  if (!meta) return { ...glr.end(), ...sec };

  const o0 = cam.worldToScreen(0, 0);
  const worldW = meta.cols * cam.scale, worldH = meta.rows * cam.scale;

  // Ground and vegetation: art-resolution layers. Layer
  // textures carry NO mipmaps (they get patched), so at far zoom — where the
  // full 12px/tile texture would minify 4-8x and thrash a phone GPU's texture
  // cache — the quad samples the quarter-res mirror instead, exactly as the
  // Canvas2D path always has.
  const lowZoom = cam.scale < ART;
  // Layer textures are keyed by level, so leaving a floor strands its set on
  // the GPU. Hand them back the moment the viewer moves: a three-storey world
  // otherwise holds three worlds' worth of layers, and a phone has no room
  // for the two nobody is looking at.
  if (glLayerLevel !== level) {
    if (glLayerLevel >= 0) glr.evictLevel(glLayerLevel);
    glLayerLevel = level;
  }
  if (chunkTiles > 0 && tilePx > 0) {
    const ground = groundLayer(meta, chunkTiles, tilePx, getChunk, level, nowMs);
    // The floor below goes down FIRST, under its own parallax, so the ground
    // layer's holes look onto it (see belowChunks). Its chunks never change
    // once decoded, so each is a permanent texture at rev 0.
    for (const [key, z, img, bx, by, bw, bh] of
        belowChunks(cam, cv.width, cv.height, meta, chunkTiles, level, getChunk)) {
      glr.layer('below:' + z + ':' + key, img, 0, null, bx, by, bw, bh);
    }
    if (ground) {
      if (lowZoom && groundLowCv) {
        glr.layer('groundlo:' + level, groundLowCv, groundRev, null, o0.x, o0.y, worldW, worldH);
      } else {
        glr.layer('ground:' + level, ground, groundRev, groundPatchRects, o0.x, o0.y, worldW, worldH);
      }
    }
    if (veg) {
      vegLayerUpdate(meta, chunkTiles, veg, vegRev, level, nowMs);
      if (lowZoom && vegLow) {
        glr.layer('veglo:' + level, vegLow, vegLowRev, null, o0.x, o0.y, worldW, worldH);
      } else {
        // Visible chunks only: a handful of small quads/textures near zoom,
        // and a repainted chunk re-uploads its own ~80KB texture wholesale —
        // no giant-texture mutation for a mobile driver to ghost.
        const cxN = Math.ceil(meta.cols / chunkTiles);
        for (const [key, cvc] of vegChunkCvs) {
          const wx = (key % cxN) * chunkTiles, wy = Math.floor(key / cxN) * chunkTiles;
          const oc = cam.worldToScreen(wx, wy);
          const wpx = chunkTiles * cam.scale;
          if (oc.x > cv.width || oc.y > cv.height || oc.x + wpx < 0 || oc.y + wpx < 0) continue;
          glr.layer('veg:' + level + ':' + key, cvc, vegChunkRevs.get(key) ?? 0, null, oc.x, oc.y, wpx, wpx);
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
    if (e.kind === 'sound') continue; // an event, not a body: the sense overlay draws it

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

    if (e.kind === 'npc.stewarddrone' || e.kind === 'npc.facilityloader') {
      // Fixed palettes, so one bake serves every body of each kind. The
      // destination is the stamp's own art-pixels of the WORLD grid, matching
      // the 2D path and the Java stamp rather than the body's radius.
      const loader = e.kind === 'npc.facilityloader';
      const stamp = loader ? loaderStamp() : droneStamp();
      const n = loader ? LOADER_N : SENTINEL_N;
      const cell = loader ? LOADER_CELL : SENTINEL_CELL;
      const dst = n * (cam.scale / 12);
      const dc = headingCol(p.dir, t.col ?? -1);
      t.col = dc;
      glr.sprite(loader ? 'loader' : 'drone', stamp, 1, cell * dc, 0, cell, cell,
        s.x - dst / 2, s.y - dst / 2, dst, dst);
      continue;
    }

    const bodyPx = e.size * cam.scale * 2;
    if (e.flags & F_DEAD) {
      if (dots) {
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
    const atlas = dots ? null : atlasFor(e.pheno);
    if (dots || !atlas) {
      // The dot LOD (and the pre-atlas placeholder, simplified to the same
      // block while the sprite streams in): solid quads, batched together.
      const sq = Math.max(4, dots ? bodyPx : r * 1.4);
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
      const src = c === CELL ? atlas : atlasMipFor(e.pheno, atlas);
      const key = c === CELL ? 'atlas:' : 'atlasm:';
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
      glr.layer('canopylo:' + level, canopyLow, canopyRev, null, o0.x, o0.y, worldW, worldH, VEIL_ALPHA);
    } else {
      glr.layer('canopy:' + level, canopy, canopyRev, canopyPatchRects, o0.x, o0.y, worldW, worldH, VEIL_ALPHA);
    }
    const lids = new Set<number>();
    for (const t of tracks) {
      const e = t.curr;
      if (e.kind === 'phero' || e.kind === 'sound' || (e.flags & F_DEAD) || Math.round(e.z) !== level) continue;
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

// ---- vegetation sprites ----------------------------------------------------
//
// One-tile vegetation, drawn as transparent sprites OVER the floor: each kind
// grows through five stages — 1 is what depletion leaves behind (trampled
// remnants), 5 is fully grown — with four variants per stage so a field
// doesn't tile visibly. Painted at art resolution (12px/tile) with
// transparent ground, so the same sprite reads on any floor tile. The world
// integration (drawing these over the baked ground per tile, replacing the
// baked-in grass) comes later; the sprites and this painter are the record.

/** High bit of a vegetation byte: this tile grows fungus rather than grass.
 *  Set by the server in the full grid only (VegFeed.KIND_FUNGUS); deltas carry
 *  density alone, because terrain never changes underfoot. */
export const VEG_KIND_MASK = 0x80;
/** The low bits: growth stage, 0 = nothing grows here, 1..5 = trampled to lush. */
export const VEG_STAGE_MASK = 0x07;
export type VegKind = 'grass' | 'mushroom';
export const VEG_KINDS: VegKind[] = ['grass', 'mushroom'];
export const VEG_STAGES = 5;
export const VEG_VARIANTS = 4;

// Palettes in the ground bake's key: greens for living grass, dry olives for
// trampled remains; mushroom caps in the fungus family, pale stems.
const GRASS_BLADE = ['#2f5c2a', '#3f7a38', '#57944a'];
const GRASS_TIP = '#8fc46f';
const GRASS_DRY = ['#8a8a52', '#6f6b3f'];
const SHROOM_CAP = ['#8f4a3c', '#6e352b'];
const SHROOM_SPOT = '#e6dccb';
const SHROOM_STEM = '#cfc4b0';
const SHROOM_BUD = '#b9a98e';
const SHROOM_DEAD = '#7a6a55';

/**
 * Paints one vegetation tile at (x, y) with `px` screen pixels per art pixel.
 * Deterministic per (kind, stage, variant): the same triple always paints the
 * same sprite, so the world and the catalog can never drift apart.
 */
export function drawVegetationTile(g: CanvasRenderingContext2D, kind: VegKind,
    stage: number, variant: number, x: number, y: number, px = 1): void {
  const rnd = (n: number) => hash01(variant * 31 + n * 7 + stage * 13, n * 5 + variant,
    kind === 'grass' ? 41 : 43);
  const p = (ax: number, ay: number, col: string) => {
    if (ax < 0 || ay < 0 || ax > 11 || ay > 11) return;
    g.fillStyle = col;
    g.fillRect(x + ax * px, y + ay * px, px, px);
  };
  if (kind === 'grass') {
    if (stage <= 1) {
      // Trampled leftovers: flattened dry strokes lying sideways, a few crumbs.
      for (let i = 0; i < 4; i++) {
        const sx = 1 + Math.floor(rnd(i) * 8), sy = 2 + Math.floor(rnd(i + 10) * 8);
        const col = GRASS_DRY[i % 2];
        p(sx, sy, col); p(sx + 1, sy, col);
        if (rnd(i + 20) > 0.5) p(sx + 2, sy, col);
      }
      for (let i = 0; i < 3; i++) {
        p(1 + Math.floor(rnd(i + 30) * 10), 1 + Math.floor(rnd(i + 40) * 10), GRASS_DRY[1]);
      }
      return;
    }
    // Growing: upright tufts — more and taller with each stage.
    const tufts = [0, 0, 3, 4, 6, 7][stage];
    const maxH = [0, 0, 1, 2, 3, 3][stage];
    for (let i = 0; i < tufts; i++) {
      const bx = 1 + Math.floor(rnd(i) * 10);
      const by = 3 + Math.floor(rnd(i + 10) * 8);
      const h = 1 + Math.floor(rnd(i + 20) * maxH);
      const blade = GRASS_BLADE[Math.floor(rnd(i + 30) * 3)];
      for (let k = 0; k < h; k++) p(bx, by - k, blade);
      if (h >= 2 && stage >= 4) p(bx, by - h, GRASS_TIP); // lit tip on tall blades
      if (stage >= 3 && rnd(i + 40) > 0.55) p(bx + 1, by, GRASS_BLADE[0]); // base spread
    }
  } else {
    if (stage <= 1) {
      // Kicked-over remains: a toppled stem and a scatter of spores.
      const sx = 3 + Math.floor(rnd(0) * 5), sy = 5 + Math.floor(rnd(1) * 4);
      p(sx, sy, SHROOM_DEAD); p(sx + 1, sy, SHROOM_DEAD);
      for (let i = 0; i < 3; i++) {
        p(1 + Math.floor(rnd(i + 10) * 10), 2 + Math.floor(rnd(i + 20) * 9), SHROOM_DEAD);
      }
      return;
    }
    const shroom = (cx: number, cy: number, big: boolean) => {
      const cap = SHROOM_CAP[Math.floor(rnd(cx + cy) * 2)];
      if (big) {
        p(cx - 1, cy - 2, cap); p(cx, cy - 2, cap); p(cx + 1, cy - 2, cap);
        p(cx - 2, cy - 1, cap); p(cx - 1, cy - 1, cap); p(cx, cy - 1, cap);
        p(cx + 1, cy - 1, cap); p(cx + 2, cy - 1, cap);
        p(cx + (rnd(cx) > 0.5 ? 1 : -1), cy - 2, SHROOM_SPOT);
        p(cx, cy, SHROOM_STEM); p(cx, cy + 1, SHROOM_STEM);
      } else {
        p(cx - 1, cy - 1, cap); p(cx, cy - 1, cap); p(cx + 1, cy - 1, cap);
        p(cx, cy, SHROOM_STEM);
      }
    };
    const bud = (cx: number, cy: number) => { p(cx, cy, SHROOM_BUD); p(cx + 1, cy, SHROOM_BUD); };
    const bx = 3 + Math.floor(rnd(50) * 4), by = 4 + Math.floor(rnd(51) * 4);
    const ox = 2 + Math.floor(rnd(52) * 7), oy = 2 + Math.floor(rnd(53) * 8);
    if (stage === 2) { bud(bx, by); if (rnd(54) > 0.4) bud(ox, oy); return; }
    if (stage === 3) { shroom(bx, by, false); if (rnd(54) > 0.5) bud(ox, oy); return; }
    if (stage === 4) { shroom(bx, by, true); if (rnd(54) > 0.5) bud(ox, oy); return; }
    shroom(bx, by, true);
    shroom(Math.min(10, ox + 1), Math.min(10, oy + 1), false);
    if (rnd(55) > 0.4) bud((bx + ox) % 10 + 1, 9);
  }
}

/** The baked one-tile sprite for (kind, stage, variant) — 12px, transparent. */
const vegTileCache = new Map<string, HTMLCanvasElement>();
export function vegetationTileFor(kind: VegKind, stage: number, variant: number): HTMLCanvasElement {
  const key = `${kind}:${stage}:${variant}`;
  const hit = vegTileCache.get(key);
  if (hit) return hit;
  const cv = document.createElement('canvas');
  cv.width = ART;
  cv.height = ART;
  drawVegetationTile(cv.getContext('2d')!, kind, stage, variant, 0, 0, 1);
  vegTileCache.set(key, cv);
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
    getChunk: (cx: number, cy: number, z: number) => HTMLCanvasElement | null,
    cover: Uint8Array, level: number, nowMs: number): HTMLCanvasElement {
  const fresh = cover !== canopySrc || level !== canopyLevel || !canopyCv
    || canopyCv.width !== meta.cols * ART;
  if (!fresh && (canopyHoles.length === 0 || nowMs < canopyRetryAt)) return canopyCv!;
  const stamp = (ctx: CanvasRenderingContext2D, i: number): boolean => {
    const tx = i % meta.cols, ty = Math.floor(i / meta.cols);
    const v = cover[i];
    const ccx = Math.floor(tx / chunkTiles), ccy = Math.floor(ty / chunkTiles);
    const chunk = getChunk(ccx, ccy, level);
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
      // few hundred rects, ship the layer wholesale.
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
 *  a creature's sprite atlas is still in flight. */
export function drawPlaceholder(g: CanvasRenderingContext2D, x: number, y: number,
    r: number, dir: number, col: string): void {
  g.fillStyle = col;
  g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  g.strokeStyle = 'rgba(0,0,0,0.45)';
  g.lineWidth = 1;
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
  [ACT_CARRY]: '#F09632',
  // Cool against the carrier's warm orange: at a glance the pair reads as two
  // ends of one act rather than two creatures doing the same thing.
  [ACT_RIDE]: '#96BEFF',
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
    case ACT_CARRY: // hook: something is hanging off this body
      g.arc(cx, cy, u * 0.45, 0, 7);
      g.moveTo(cx, cy); g.lineTo(cx + u * 0.55, cy + u * 0.55);
      g.stroke();
      return;
    case ACT_RIDE: // a chevron riding above a rail: carried, not carrying
      g.moveTo(cx - u * 0.55, cy + u * 0.4); g.lineTo(cx + u * 0.55, cy + u * 0.4);
      g.moveTo(cx - u * 0.4, cy + u * 0.05);
      g.lineTo(cx, cy - u * 0.5);
      g.lineTo(cx + u * 0.4, cy + u * 0.05);
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

// ---- sense overlays ---------------------------------------------------------
// Two heatmaps a viewer can switch on over the live world: smell (pheromone
// clouds — a field that lingers) and sound (travelling events — a wavefront
// that blooms and is gone). Both read the same entity stream the rest of the
// renderer draws from; additive compositing makes overlapping sources read as
// heat. Sounds die within a second of being made, too fast to read as a map,
// so each one leaves a client-side echo that fades over a couple of seconds —
// presentation memory only, nothing the sim knows about.

const heatDots = new Map<string, HTMLCanvasElement>();
function heatDot(color: string): HTMLCanvasElement {
  let c = heatDots.get(color);
  if (!c) {
    c = document.createElement('canvas');
    c.width = c.height = 64;
    const ctx = c.getContext('2d')!;
    const grad = ctx.createRadialGradient(32, 32, 2, 32, 32, 32);
    grad.addColorStop(0, color);
    grad.addColorStop(0.55, color + '66');
    grad.addColorStop(1, color + '00');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, 64, 64);
    heatDots.set(color, c);
  }
  return c;
}

const SMELL_HEAT = '#e628be';
const SOUND_HEAT = '#f2b84b';
const SOUND_ECHO_MS = 2200;
const soundEchoes = new Map<number, { x: number; y: number; z: number; r: number; at: number }>();

export function drawSenseHeat(g: CanvasRenderingContext2D, cam: Camera,
    state: WorldState, renderTime: number, level: number,
    smell: boolean, sound: boolean, now: number): void {
  g.save();
  g.globalCompositeOperation = 'lighter';
  g.imageSmoothingEnabled = true;
  for (const t of state.tracks.values()) {
    const e = t.curr;
    if (Math.round(e.z) !== level) continue;
    if (smell && e.kind === 'phero') {
      const p = state.sample(t, renderTime);
      const s = cam.worldToScreen(p.x, p.y);
      // The cloud's wire size is its drawn radius; the heat blooms wider so
      // adjacent deposits merge into a field, and strength sets the glow.
      const r = Math.max(8, e.size * cam.scale * 1.8);
      if (s.x < -r || s.y < -r || s.x > g.canvas.width + r || s.y > g.canvas.height + r) continue;
      g.globalAlpha = Math.min(0.35, 0.12 + Math.log1p(e.aux) * 0.08);
      g.drawImage(heatDot(SMELL_HEAT), s.x - r, s.y - r, r * 2, r * 2);
    }
    if (sound && e.kind === 'sound' && !(e.flags & F_DEAD)) {
      const s = cam.worldToScreen(e.x, e.y); // sounds never move: no sampling
      const earshot = e.size * cam.scale;
      const frac = Math.max(0, Math.min(1, e.aux));
      const rr = earshot * (0.2 + 0.8 * frac); // the wavefront, spreading out
      if (s.x >= -earshot && s.y >= -earshot
          && s.x <= g.canvas.width + earshot && s.y <= g.canvas.height + earshot) {
        // Gentle per-source alphas: heat is additive, and a feeding frenzy
        // stacks many screams on one spot — hot should read hot, not white.
        g.globalAlpha = 0.16 - 0.08 * frac;
        g.drawImage(heatDot(SOUND_HEAT), s.x - rr, s.y - rr, rr * 2, rr * 2);
        g.globalAlpha = 0.3 - 0.15 * frac;
        g.strokeStyle = SOUND_HEAT;
        g.lineWidth = Math.max(1, cam.scale * 0.05);
        g.beginPath();
        g.arc(s.x, s.y, rr, 0, Math.PI * 2);
        g.stroke();
      }
      soundEchoes.set(e.id, { x: e.x, y: e.y, z: e.z, r: e.size, at: now });
    }
  }
  if (sound) {
    for (const [id, echo] of soundEchoes) {
      const age = now - echo.at;
      if (age > SOUND_ECHO_MS) {
        soundEchoes.delete(id);
        continue;
      }
      const live = state.tracks.get(id);
      if (live && !(live.curr.flags & F_DEAD)) continue; // still drawn above
      if (Math.round(echo.z) !== level) continue;
      const s = cam.worldToScreen(echo.x, echo.y);
      const r = echo.r * cam.scale;
      if (s.x < -r || s.y < -r || s.x > g.canvas.width + r || s.y > g.canvas.height + r) continue;
      g.globalAlpha = 0.1 * (1 - age / SOUND_ECHO_MS);
      g.drawImage(heatDot(SOUND_HEAT), s.x - r, s.y - r, r * 2, r * 2);
    }
  }
  g.restore();
}
