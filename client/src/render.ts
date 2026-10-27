// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import {
  ART_RADIUS, CELL, DIRS, MIP, atlasFor, atlasMipFor, cell, corpseFor,
  corpseMipFor, decayStage, headingCol, tintedFor,
} from './atlas';
import type { Camera } from './camera';
import { flagOff } from './flags';
import { actionOf, F_CARRYING, F_DEAD, F_GRABBED } from './protocol';
import { ART, CORPSE_RGB, PENDING_RGB, VOID_RGB, hash01, hex, rgbF } from './artCore';
import {
  GLYPH_MIN_BODY_PX, drawActionGlyph, drawCarryLink, drawDoor, drawItem, drawNest,
  drawPlaceholder, drawRing, drawSwitch, pheroPuff,
} from './fixtures';
import { VEG_STAGE_MASK, VEG_VARIANTS, vegKindOf, vegetationTileFor, type VegKind } from './vegArt';
import { flowLayers } from './flow';

// The split-out drawing modules are part of this one's public face: callers
// import from './render' and need not know where a painter lives.
export { ART, CORPSE_RGB, PENDING_RGB, VOID_RGB, hash01, hex, rgbF } from './artCore';
export * from './fixtures';
export * from './vegArt';
export * from './fx';
export { setFlowGrid } from './flow';

/** The moving water in view (flow.ts), as chunk canvases with their world
 *  origin: drawn over the ground and under everything standing on it, so a
 *  swimmer is never painted over. */
function flowInView(cam: Camera, w: number, h: number, chunkTiles: number, level: number,
    getChunk: ChunkSource, tick: number) {
  const tl = cam.screenToWorld(0, 0), br = cam.screenToWorld(w, h);
  return flowLayers(cam.scale, { x0: tl.x, y0: tl.y, x1: br.x, y1: br.y }, chunkTiles, level, getChunk, tick);
}
import type { EntityState } from './protocol';
import type { Pose, WorldState } from './state';

export interface WorldMeta { cols: number; rows: number; }

/** How far past the screen edge a body is still drawn: its own reach plus a ring. */
const CULL_PX = 60;
/** Bodies on the floor below sit under the veil's darkness, not competing with it. */
const BELOW_ALPHA = 0.7;
const CORPSE_F = rgbF(CORPSE_RGB), PENDING_F = rgbF(PENDING_RGB), VOID_F = rgbF(VOID_RGB);

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
          const kind: VegKind = vegKindOf(s);
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

// The level's low map (LayerBaker.lowJpeg): one small JPEG of the whole floor,
// painted into every hole the chunks have not filled yet, so the map is on
// screen after ONE request instead of after fifty-four. It used to be black
// until the chunks landed, and on a phone behind the entity stream that was
// ten seconds and more of dots on nothing. Where it stands in for a chunk it
// is blocky at near zoom -- it is the far-zoom mirror's resolution -- and the
// chunk replaces it the moment it decodes.
let getLowMap: (z: number) => HTMLCanvasElement | null = () => null;
/** A chunk lookup. `fetch` false is a peek: the chunk if it has been decoded,
 *  and no request otherwise. Chunks are requested only for the viewport at
 *  near zoom; everything else is the low map's job. */
type ChunkSource = (cx: number, cy: number, z: number, fetch: boolean) => HTMLCanvasElement | null;
/** The chunk range a view wants, inclusive, one chunk of margin for panning --
 *  or null at far zoom, where the low map is the ground and no chunk is
 *  fetched at all. The doubled world is 198 chunks and 3.7 MB a level; at fit
 *  zoom on a phone that is 25 seconds of downloading for a picture the 34 KB
 *  low map already shows at 2 px a tile. */
function wantedChunks(cam: Camera, cvW: number, cvH: number, meta: WorldMeta,
    chunkTiles: number): [number, number, number, number] | null {
  if (cam.scale < ART || chunkTiles <= 0) return null;
  const cxN = Math.ceil(meta.cols / chunkTiles), cyN = Math.ceil(meta.rows / chunkTiles);
  const tl = cam.screenToWorld(0, 0), br = cam.screenToWorld(cvW, cvH);
  return [Math.max(0, Math.floor(tl.x / chunkTiles) - 1), Math.max(0, Math.floor(tl.y / chunkTiles) - 1),
    Math.min(cxN - 1, Math.floor(br.x / chunkTiles) + 1), Math.min(cyN - 1, Math.floor(br.y / chunkTiles) + 1)];
}
let lowMapLevel = -1; // the level the placeholder has been painted for, or -1
/** The host wires up the low map fetch; drawing never fetches by itself. */
export function setLowMapSource(fn: (z: number) => HTMLCanvasElement | null): void {
  getLowMap = fn;
}

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
    getChunk: ChunkSource, level: number, nowMs: number,
    want: [number, number, number, number] | null): HTMLCanvasElement | null {
  const fresh = level !== groundLevel || !groundCv
    || groundCv.width !== meta.cols * ART || groundCv.height !== meta.rows * ART;
  if (!fresh && (groundHoles.length === 0 || nowMs < groundRetryAt)) return groundCv;
  const cxN = Math.ceil(meta.cols / chunkTiles), cyN = Math.ceil(meta.rows / chunkTiles);
  const chunkRect = (cx: number, cy: number): [number, number, number, number] => {
    const wTiles = Math.min(chunkTiles, meta.cols - cx * chunkTiles);
    const hTiles = Math.min(chunkTiles, meta.rows - cy * chunkTiles);
    return [cx * chunkTiles * ART, cy * chunkTiles * ART, wTiles * ART, hTiles * ART];
  };
  // The low map, scaled up into one chunk's rect: its pixels are whatever
  // the JPEG says per tile, read off its own width so the two never disagree.
  const lowInto = (ctx: CanvasRenderingContext2D, low: HTMLCanvasElement,
      [dx, dy, dw, dh]: [number, number, number, number]) => {
    const sc = low.width / groundCv!.width; // low px per art px
    ctx.drawImage(low, dx * sc, dy * sc, dw * sc, dh * sc, dx, dy, dw, dh);
  };
  const wanted = (cx: number, cy: number) =>
    want !== null && cx >= want[0] && cx <= want[2] && cy >= want[1] && cy <= want[3];
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
    lowMapLevel = -1;
    const low = getLowMap(level);
    if (low) lowMapLevel = level;
    for (let cy = 0; cy < cyN; cy++) {
      for (let cx = 0; cx < cxN; cx++) {
        // Only a chunk the view wants is requested; one already decoded is
        // used wherever it is, since it costs nothing now.
        const chunk = getChunk(cx, cy, level, wanted(cx, cy));
        if (!chunk) {
          groundHoles.push([cx, cy]);
          if (low) lowInto(ctx, low, chunkRect(cx, cy));
          continue;
        }
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
    // repainted, no rev bump, no upload. A low map that has arrived since
    // the layer was built fills every hole still open, once.
    const ctx = groundCv!.getContext('2d')!;
    ctx.imageSmoothingEnabled = false;
    const filled: Array<[number, number, number, number]> = [];
    const low = lowMapLevel === level ? null : getLowMap(level);
    if (low) lowMapLevel = level;
    groundHoles = groundHoles.filter(([cx, cy]) => {
      const chunk = getChunk(cx, cy, level, wanted(cx, cy));
      const r = chunkRect(cx, cy);
      if (!chunk) {
        if (low) { lowInto(ctx, low, r); filled.push(r); }
        return true;
      }
      const [dx, dy, dw, dh] = r;
      // The slot holds the low map's placeholder, and a chunk is not opaque:
      // its pit veils (and on a sky level, nearly all of it) are translucent
      // black. Drawn straight over the placeholder's own veil, the two
      // stacked to a window three times darker than either -- a landed
      // chunk read as a black hole in the map while its unlanded neighbours
      // kept the single veil. The placeholder goes before the chunk lands on it.
      ctx.clearRect(dx, dy, dw, dh);
      ctx.drawImage(chunk, 0, 0, dw / ART * tilePx, dh / ART * tilePx, dx, dy, dw, dh);
      filled.push(r);
      return false;
    });
    if (filled.length > 0) {
      groundRev++;
      groundPatchRects = filled;
      refreshGroundLow(meta);
    }
  }
  // Holes still open: look again soon. A quarter second, not the second it
  // was -- the pass is a handful of cache lookups when nothing has arrived,
  // and a chunk that has landed should not wait most of a second to show.
  groundRetryAt = nowMs + 250;
  return groundCv;
}

// ---- the level below, seen through the holes -------------------------------
//
// A pit's open art-pixels reach the client TRANSPARENT: the bake clears to
// nothing and the ground pass declines to paint them (Grid.pitFloor), so what
// shows through a hole is a real second layer rather than a picture of one.
//
// That layer is drawn scaled about the SCREEN CENTRE by a parallax factor,
// which is the projection of a plane one storey further from the eye: pan by
// d and the top layer moves d while the floor below moves a little less, so
// the view down a hole slides as you travel. That slide is the whole point —
// it is what separates "there is a place down there" from "someone painted
// rock on the lid".
//
// The factor follows the camera. Zooming is the eye moving: zoomed in it is
// close over the ground and a storey is a real fraction of its height, zoomed
// out it is far and a storey is nothing. A fixed factor had the opposite feel
// — at fit zoom, where the whole world is on screen, every floor below slid
// by the same 6% of the screen, a world-sized layer swimming under a lid, and
// the parallax-off switch existed largely because of that. The projection of
// a plane one storey down from a camera at height H is H / (H + 1); with
// zoom as the inverse of height that is 1 / (1 + scale / PARALLAX_ZOOM), and
// PARALLAX_ZOOM is the one figure read by eye: the zoom at which a storey
// slides by half, set so that at 32 px/tile — a creature filling a fingertip
// — the slide is the 0.94 that read right at that zoom before. At the fit
// zoom of an ordinary window (~3 px/tile) it is 0.994, and at the far end of
// the wheel (256) it is 0.66, a real drop under the feet.
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
const PARALLAX_ZOOM = 500;
/** The factor one storey down at this zoom (device px per tile): parallax=0
 *  in the URL (or the ⚙ dialog) pins it to 1.0 — the floors below still show
 *  through every pit, they just stop sliding. A viewer preference as much as
 *  a perf experiment: the slide is a depth cue some eyes read as the world
 *  coming apart. */
const parallaxFactor = (scale: number): number =>
  PARALLAX_OFF ? 1.0 : 1 / (1 + scale / PARALLAX_ZOOM);

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
 *  x, y, w, h], ordered deepest FLOOR first. Where the level below is itself holed over the same chunk —
 *  the gorge cutting through two storeys — the level under THAT is drawn
 *  first at the factor squared, a plane two storeys from the eye, so the stack reads
 *  as one deepening cut rather than a hole with a hole painted on its
 *  floor. */
function belowChunks(cam: Camera, cvW: number, cvH: number, meta: WorldMeta,
    chunkTiles: number, level: number,
    getChunk: ChunkSource,
): Array<[number, number, HTMLCanvasElement, number, number, number, number]> {
  const out: Array<[number, number, HTMLCanvasElement, number, number, number, number]> = [];
  if (level - 1 < 0 || chunkTiles <= 0) return out;
  const cxN = Math.ceil(meta.cols / chunkTiles), cyN = Math.ceil(meta.rows / chunkTiles);
  const mx = cvW / 2, my = cvH / 2;
  // The chunks the DEEPEST floor needs to fill the screen: every floor down
  // shrinks toward the centre, so the screen's edge reaches further into the
  // world on it than on the top. Ranged off the top floor's view alone, a
  // floor below ran out of chunks short of the edge, and there its low map
  // showed through with its own veil on top of the chunks' -- a dark band
  // down the side of the screen, doubly veiled.
  const deep = parallaxFactor(cam.scale) ** level;
  const tl = cam.screenToWorld(mx - mx / deep, my - my / deep);
  const br = cam.screenToWorld(mx + (cvW - mx) / deep, my + (cvH - my) / deep);
  const cx0 = Math.max(0, Math.floor(tl.x / chunkTiles));
  const cy0 = Math.max(0, Math.floor(tl.y / chunkTiles));
  const cx1 = Math.min(cxN - 1, Math.floor(br.x / chunkTiles));
  const cy1 = Math.min(cyN - 1, Math.floor(br.y / chunkTiles));
  // Every chunk edge is computed as a SHARED grid line and rounded once, so
  // two neighbours land on the identical device pixel. The obvious per-chunk
  // rect (origin + width, both scaled) is continuous in float math but lets
  // each drawImage rasterise its own edges — and adjacent edges at .28px
  // round apart, leaving one-pixel background seams. Through a pit mouth
  // nobody saw them; through a sky level the whole world is the below layer
  // and the seams draw a grid across the map.
  const edgeX = (wtx: number, factor: number): number =>
    Math.round(mx + (cam.worldToScreen(wtx, 0).x - mx) * factor);
  const edgeY = (wty: number, factor: number): number =>
    Math.round(my + (cam.worldToScreen(0, wty).y - my) * factor);
  const rect = (cx: number, cy: number, factor: number):
      [number, number, number, number] => {
    const wx = cx * chunkTiles, wy = cy * chunkTiles;
    const cw = Math.min(chunkTiles, meta.cols - wx);
    const ch = Math.min(chunkTiles, meta.rows - wy);
    const x0 = edgeX(wx, factor), y0 = edgeY(wy, factor);
    return [x0, y0, edgeX(wx + cw, factor) - x0, edgeY(wy + ch, factor) - y0];
  };
  for (let cy = cy0; cy <= cy1; cy++) {
    for (let cx = cx0; cx <= cx1; cx++) {
      // Requested, not peeked: the ground pass fetches the top chunks for its
      // own view, and the floors below reach past it (see `deep`), so a top
      // chunk out there would never land and nothing would be looked down
      // through it. Only a decoded chunk knows its holes; the chunk beneath
      // is requested only for a holed top, and the
      // one beneath THAT only for a holed chunk beneath — so the view down
      // goes as deep as the openings do and no deeper. Three storeys of sky
      // over the ground made this a walk rather than a pair: from the top
      // storey the ground is three floors down, and each floor's own
      // parallax compounds on the way.
      const key = cx + cy * cxN;
      const stack: Array<[number, number, HTMLCanvasElement, number, number, number, number]> = [];
      let above = getChunk(cx, cy, level, true);
      let factor = 1;
      for (let z = level - 1; z >= 0 && above && hasHoles(above); z--) {
        const below = getChunk(cx, cy, z, true);
        if (!below) break;
        factor *= parallaxFactor(cam.scale);
        stack.push([key, z, below, ...rect(cx, cy, factor)]);
        above = below;
      }
      out.push(...stack);
    }
  }
  // Deepest FLOOR first across every chunk, not deepest first chunk by chunk.
  // A floor further down shrinks further toward the screen centre, so a
  // chunk's deep floors reach a few pixels past the seam with its neighbour;
  // drawn as part of their own chunk's stack, they landed after the
  // neighbour's upper floors and painted over those floors' veils there --
  // a bright line down every seam where the stacks met (3 px at 12 px/tile
  // from the top storey). Floor by floor, each level covers the one under
  // it whatever chunk either came from. Stable, so a floor keeps chunk order.
  out.sort((a, b) => a[1] - b[1]);
  return out;
}

/** The floors below as WHOLE levels, from their low maps: the same parallax
 *  as belowChunks, one image a floor, deepest first: [z, canvas, x, y, w, h].
 *
 *  belowChunks can only look under a chunk that has landed, and chunks are
 *  fetched at near zoom alone -- so at the zoom a world opens at, the view
 *  down went nowhere, and the sky, nearly all open air, was a black sheet with the
 *  plateaus on it. The low maps are fetched at every zoom and carry their
 *  alpha, so this is the view down wherever no chunk is there to give a
 *  sharper one; belowChunks draws over it where one is. Nothing is drawn for
 *  a level whose own low map is opaque: there is no hole to see it through. */
function belowLows(cam: Camera, cvW: number, cvH: number, meta: WorldMeta,
    level: number): Array<[number, HTMLCanvasElement, number, number, number, number]> {
  const out: Array<[number, HTMLCanvasElement, number, number, number, number]> = [];
  const mx = cvW / 2, my = cvH / 2;
  const rect = (factor: number): [number, number, number, number] => {
    const a = cam.worldToScreen(0, 0), b = cam.worldToScreen(meta.cols, meta.rows);
    const x0 = Math.round(mx + (a.x - mx) * factor), y0 = Math.round(my + (a.y - my) * factor);
    return [x0, y0, Math.round(mx + (b.x - mx) * factor) - x0, Math.round(my + (b.y - my) * factor) - y0];
  };
  // Down floor by floor while each floor's map has holes to look through,
  // every floor a storey further from the eye (see belowChunks).
  const stack: Array<[number, HTMLCanvasElement, number]> = [];
  let above = getLowMap(level);
  let factor = 1;
  for (let z = level - 1; z >= 0 && above && hasHoles(above); z--) {
    const low = getLowMap(z);
    if (!low) break;
    factor *= parallaxFactor(cam.scale);
    stack.push([z, low, factor]);
    above = low;
  }
  for (let i = stack.length - 1; i >= 0; i--) {
    const [z, low, f] = stack[i];
    out.push([z, low, ...rect(f)]); // deepest first
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
 *  that is stable frame to frame and identical for every body.
 *
 *  It is the SAME breakpoint the ground uses: below one screen pixel per
 *  art-pixel the ground, vegetation and canopy layers all drop to their 3
 *  px/tile mirrors (see `lowZoom`), and the bodies drop to dots with them.
 *  The two used to differ (8 for bodies, 12 for tiles), which put a band of
 *  zoom where the world had already gone to its far-view art while the
 *  creatures on it were still stamped sprites at a couple of pixels across —
 *  two levels of detail in one picture. One number, one far view. */
export const DOT_LOD_SCALE = ART;

// There is deliberately NO minimum on-screen body size, in either tier: a
// body's drawn radius is its true radius times the zoom, at every zoom, so a
// creature scales exactly as the tiles under it do. There used to be a floor
// (3.5 px radius, 4 px dot) so a distant body never vanished into the ground,
// and it bound the SMALLEST bodies first: a 5 px parasite was pinned below 45
// px/tile and a 12 px grazer below 19, while a fitted world sits at ~9 — so
// across the band the world is actually watched at every creature was one
// size, only the tiles scaled, and a parasite read as growing while its host
// shrank under it. Sizes are the one thing the bodies are meant to show at a
// glance. What a 5 px body is at 9 px/tile is under a pixel, and that is the
// picture (see ART-STYLE.md §7, "the floor that made every body one size").

// Perf experiment switches (the ⚙ dialog in main.ts writes these): each
// disables one visual subsystem so its cost can be isolated on a device.

const SPRITES_OFF = flagOff('sprites'); // creatures as dots only
const PHERO_OFF = flagOff('phero');     // pheromone clouds
const PARALLAX_OFF = flagOff('parallax'); // below-floor slide (floors still show)
const BELOW_OFF = flagOff('below');       // bodies on the floor below the pits
const CANOPY_OFF = flagOff('canopy');   // foliage veils + duct lids
const OVERLAY_OFF = flagOff('overlay'); // action badges, rings, carry links

/** The map-view dot: a corpse is a spent grey block; a live body its colour. */
export function drawDot(g: CanvasRenderingContext2D, x: number, y: number,
    bodyPx: number, col: string, dead: boolean): void {
  const s = bodyPx;
  g.fillStyle = dead ? hex(CORPSE_RGB) : col;
  g.fillRect(x - s / 2, y - s / 2, s, s);
}

/**
 * Whether a body received at screen (rx, ry) can touch the canvas. `reach` is
 * everything that can carry its drawing away from that point: its radius on
 * screen, and one stream step — an interpolated pose lies between the last
 * two received ones, so it is within a step of the latest, and a cull on the
 * received pose with that step in the margin is exact. CULL_PX stays for the
 * badges and rings that hang off a body.
 *
 * Culling BEFORE interpolating is the point: at near zoom nearly every body on
 * the level is off screen, and each used to pay a lerp, an angle lerp and two
 * projections to find that out.
 */
function onScreen(rx: number, ry: number, reach: number, w: number, h: number): boolean {
  const m = CULL_PX + reach * SPRITE_REACH;
  return rx >= -m && ry >= -m && rx <= w + m && ry <= h + m;
}
/** A sprite's drawn box over its body radius (the atlas cell's padding —
 *  horns, halos — see ART_RADIUS), rounded up. */
const SPRITE_REACH = Math.ceil(CELL / (2 * ART_RADIUS));

/** A second pose scratch, for the one place two live at once: a carried body
 *  and its carrier, whose tether needs both ends. */
const CARRIER: Pose = { x: 0, y: 0, dir: 0 };

/** The GL texture keys of a phenotype's atlas and its mip, built once: a
 *  string per body per frame was measurable garbage at herd scale. */
const atlasKeys = new Map<number, string>();
const atlasMipKeys = new Map<number, string>();
function atlasKey(pheno: number, mip: boolean): string {
  const m = mip ? atlasMipKeys : atlasKeys;
  let k = m.get(pheno);
  if (k === undefined) { k = (mip ? 'atlasm:' : 'atlas:') + pheno; m.set(pheno, k); }
  return k;
}

/** The walk cycle's clock: wall time scaled by the sim's speed, held while it
 *  is paused. Fed the wall clock, a paused world walked on the spot and a
 *  world at 4× strode at the pace of 1×. */
let gaitMs = 0;
export function advanceGait(dtMs: number, simSpeed: number): void {
  gaitMs += Math.min(dtMs, 250) * simSpeed;
}

export function render(
  g: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  chunkTiles: number,
  tilePx: number,
  getChunk: ChunkSource,
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
  g.fillStyle = hex(VOID_RGB);
  g.fillRect(0, 0, cv.width, cv.height);
  if (!meta) return;

  // Ground: far zoom blits the assembled art-resolution layer in one draw
  // (see groundLayer); near zoom draws the full-resolution chunks in view.
  // Both nearest-neighbour, so pixels stay fat and crisp either way.
  if (chunkTiles > 0 && cam.scale < ART) {
    const layer = groundLayer(meta, chunkTiles, tilePx, getChunk, level, nowMs, null);
    if (layer) {
      const o = cam.worldToScreen(0, 0);
      g.imageSmoothingEnabled = false;
      // The view down from the low maps ALONE at far zoom, as the ground
      // itself is: a chunk that landed while zoomed in used to be drawn here
      // too, minified, as a crisp striped rectangle on the smooth map, and
      // its straight edges slid against the ground under parallax.
      for (const [, img, bx, by, bw, bh] of belowLows(cam, cv.width, cv.height, meta, level)) {
        g.drawImage(img, bx, by, bw, bh);
      }
      drawBelowBodies2D(g, cam, state, renderTime, level); // occluded by the ground next
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
    // the chunks drawn next look onto it (see belowLows, belowChunks).
    for (const [, img, bx, by, bw, bh] of belowLows(cam, cv.width, cv.height, meta, level)) {
      g.drawImage(img, bx, by, bw, bh);
    }
    for (const [, , img, bx, by, bw, bh] of
        belowChunks(cam, cv.width, cv.height, meta, chunkTiles, level, getChunk)) {
      g.drawImage(img, bx, by, bw, bh);
    }
    drawBelowBodies2D(g, cam, state, renderTime, level); // occluded by the chunks next
    // A chunk not yet landed shows its patch of the low map, at the JPEG's
    // resolution, rather than the background. Its patch ONLY: the low map
    // carries the pit veil too, and laid under the whole view it veiled every
    // hole twice -- once in the map, once in the chunk drawn over it.
    const low = getLowMap(level);
    for (let cy = cy0; cy <= cy1; cy++) {
      for (let cx = cx0; cx <= cx1; cx++) {
        const img = getChunk(cx, cy, level, true); // lazily fetches + caches this chunk
        const wx = cx * chunkTiles, wy = cy * chunkTiles;
        const cw = Math.min(chunkTiles, meta.cols - wx);
        const ch = Math.min(chunkTiles, meta.rows - wy);
        const o = cam.worldToScreen(wx, wy);
        if (img) {
          g.drawImage(img, o.x, o.y, cw * cam.scale, ch * cam.scale);
        } else if (low) {
          const sx = low.width / meta.cols, sy = low.height / meta.rows; // low px per tile
          g.drawImage(low, wx * sx, wy * sy, cw * sx, ch * sy, o.x, o.y, cw * cam.scale, ch * cam.scale);
        }
      }
    }
  }

  // Moving water over the still bake, under the plants and the bodies.
  if (chunkTiles > 0) {
    g.imageSmoothingEnabled = false;
    for (const f of flowInView(cam, cv.width, cv.height, chunkTiles, level, getChunk, state.tick)) {
      const o = cam.worldToScreen(f.wx, f.wy);
      g.drawImage(f.cv, o.x, o.y, chunkTiles * cam.scale, chunkTiles * cam.scale);
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

  // Painter's order (state.drawRank): haze, then dead, then items, then
  // living creatures, grouped by phenotype — sorted once per stream message,
  // not once per frame.
  const tracks = state.ordered();
  const stepPx = state.maxStep() * cam.scale;
  const cvW = cv.width, cvH = cv.height;

  for (const t of tracks) {
    const e = t.curr;
    if (Math.round(e.z) !== level) continue; // only entities on the shown level
    // Cull on the RECEIVED pose, before interpolating (see onScreen): the
    // thousands of bodies off screen at near zoom cost one compare each.
    const rx = cam.screenX(e.x), ry = cam.screenY(e.y);

    // Doors reach up to `span` tiles from their anchor, so they get their own
    // generous cull instead of the point cull below.
    if (e.kind.startsWith('door.')) {
      const m = (e.size + 1) * cam.scale + CULL_PX;
      if (rx < -m || ry < -m || rx > cvW + m || ry > cvH + m) continue;
      drawDoor(g, cam, e);
      continue;
    }
    // A switch's indicator trail reaches its wired door, so cull loosely too.
    if (e.kind.startsWith('switch.')) {
      const m = 8 * cam.scale + CULL_PX;
      if (rx < -m || ry < -m || rx > cvW + m || ry > cvH + m) continue;
      drawSwitch(g, cam, e, state.tracks.get(e.pheno)?.curr);
      continue;
    }

    if (!onScreen(rx, ry, e.size * cam.scale + stepPx, cvW, cvH)) continue;
    const p = state.sample(t, renderTime);
    const sx = cam.screenX(p.x), sy = cam.screenY(p.y);

    if (e.kind === 'phero') {
      if (PHERO_OFF) continue;
      const r = Math.max(2, e.size * cam.scale);
      g.imageSmoothingEnabled = true; // the haze's tint stays soft by design
      g.drawImage(pheroPuff(), sx - r, sy - r, r * 2, r * 2);
      g.imageSmoothingEnabled = false;
      continue;
    }
    if (e.kind === 'sound') continue; // an event, not a body: the sense overlay draws it

    const r = e.size * cam.scale;

    // Carry link under the bodies.
    const carrier = e.attachedTo >= 0 ? state.tracks.get(e.attachedTo) : undefined;
    if (carrier) {
      const cp = state.sample(carrier, renderTime, CARRIER);
      if (!OVERLAY_OFF) drawCarryLink(g, sx, sy, cam.screenX(cp.x), cam.screenY(cp.y), cam.scale);
    }

    if (e.kind === 'nest') {
      drawNest(g, sx, sy, cam.scale);
      continue;
    }

    if (e.kind.startsWith('item.')) {
      drawItem(g, e.kind, sx, sy, r, '#' + e.rgb.toString(16).padStart(6, '0'));
      continue;
    }

    // The steward's drone: machinery, drawn rather than grown. It has no
    // phenotype to build an atlas from, so without this it falls through to
    // the no-atlas dot and the one thing in the world that is not alive looks
    // exactly like everything that is.
    if (e.kind === 'npc.stewarddrone' || e.kind === 'npc.facilityloader') {
      const dc = headingCol(p.dir, t.col ?? -1);
      t.col = dc;
      if (e.kind === 'npc.facilityloader') drawLoader(g, sx, sy, cam.scale, dc);
      else drawSentinel(g, sx, sy, cam.scale, dc);
      continue;
    }

    // Creature. At map zoom (see DOT_LOD_SCALE) every body skips the sprite
    // pipeline: one or two fillRects instead of a drawImage, which is most of
    // the frame at that zoom with a large population.
    const bodyPx = e.size * cam.scale * 2;
    if (e.flags & F_DEAD) {
      if (dots) {
        drawDot(g, sx, sy, bodyPx, '', true);
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
          sx - box / 2, sy - box / 2, box, box);
      } else {
        g.fillStyle = hex(PENDING_RGB); // no sprite yet: a spent dot, no marker over it
        g.beginPath(); g.arc(sx, sy, r, 0, 7); g.fill();
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
      drawDot(g, sx, sy, bodyPx, '#' + e.rgb.toString(16).padStart(6, '0'), false);
    } else if (atlas) {
      const box = r * 2 * (CELL / (2 * ART_RADIUS)); // scale cell so body ≈ 2r
      // Sticky heading bucket (see atlas.headingCol): neighbouring columns are
      // very different silhouettes, so bucket noise while walking reads as
      // flicker rather than turning.
      const cc = headingCol(p.dir, t.col ?? -1);
      t.col = cc;
      const { row: rr } = cell(p.dir, gaitMs, e.id);
      g.imageSmoothingEnabled = false;
      // The fetched atlas is colour-neutral (one per SHAPE); this path has no
      // tint shader, so it stamps from a re-tinted copy baked once per
      // (shape, colour) — see atlas.tintedFor.
      if (box <= CELL / MIP) {
        // Far zoom: one stamp from the quarter-size mip, tinted at mip size.
        const c = CELL / MIP;
        const src = tintedFor(e.pheno, e.rgb, atlasMipFor(e.pheno, atlas), true);
        g.drawImage(src, cc * c, rr * c, c, c, sx - box / 2, sy - box / 2, box, box);
      } else {
        const src = tintedFor(e.pheno, e.rgb, atlas);
        g.drawImage(src, cc * CELL, rr * CELL, CELL, CELL, sx - box / 2, sy - box / 2, box, box);
      }
    } else {
      // The colour string is built only here: the atlas path never needs it,
      // and building one per creature per frame is measurable at herd scale.
      drawPlaceholder(g, sx, sy, r, p.dir,
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
      if (!OVERLAY_OFF) drawActionGlyph(g, sx, sy - r * 2.0, r * 0.95, actionOf(e.flags));
    }

    if (OVERLAY_OFF) { /* badges, rings and links are the experiment's cost */ }
    else if (e.flags & F_GRABBED) drawRing(g, 'grabbed', sx, sy, r);
    else if (e.flags & F_CARRYING) drawRing(g, 'carrying', sx, sy, r);

    // Follow highlight: a gentle pulsing ring around the tracked creature.
    if (cam.followId === e.id) drawRing(g, 'follow', sx, sy, r, renderTime);
    // Selection highlight: a steady amber ring on the inspected creature, so it's
    // obvious which one the panel describes (distinct from the follow pulse).
    if (selection.id === e.id) drawRing(g, 'selected', sx, sy, r);
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
    const lidPad = 2 * cam.scale; // a lid is at most one tile from its occupant
    for (const t of tracks) {
      const e = t.curr;
      if (e.kind === 'phero' || e.kind === 'sound' || (e.flags & F_DEAD) || Math.round(e.z) !== level) continue;
      const rx = cam.screenX(e.x), ry = cam.screenY(e.y);
      if (rx < -lidPad || ry < -lidPad || rx > cvW + lidPad || ry > cvH + lidPad) continue;
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
const ITEM_STAMP = ITEM_R * 4; // the stamp's side: a reference body with room for its reach
const itemStamps = new Map<string, HTMLCanvasElement>();
function itemStamp(kind: string, rgb: number): HTMLCanvasElement {
  const key = kind + ':' + rgb;
  let cv = itemStamps.get(key);
  if (!cv) {
    cv = document.createElement('canvas');
    cv.width = ITEM_STAMP;
    cv.height = ITEM_STAMP;
    drawItem(cv.getContext('2d')!, kind, ITEM_STAMP / 2, ITEM_STAMP / 2, ITEM_R, hex(rgb));
    itemStamps.set(key, cv);
  }
  return cv;
}

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
/** `rgb` with each channel multiplied by `f`, as a CSS hex: a ramp step. */
const shadeHex = (rgb: number, f: number): string => {
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
  H: shadeHex(HAZARD_RGB, 1.18),   // hull, lit north edge
  M: '#d8b028',                 // hull, mid
  I: '#14161f',                 // hull, sunk south edge — iron, see below
  h: shadeHex(HAZARD_RGB, 1.18),   // plate, lit
  m: '#d8b028',
  d: shadeHex(HAZARD_RGB, 0.65),   // plate, sunk — yellow: a plate is thin
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
  const p = Math.max(1, sc / ART);
  const f = LOADER_FACING[((bucket % DIRS) + DIRS) % DIRS];
  blit(g, f, x, y + LOADER_DROP * p, p, LOADER_CONTACT);
  blit(g, f, x, y, p, null);
}

export function drawSentinel(g: CanvasRenderingContext2D, x: number, y: number,
    sc: number, bucket: number): void {
  const p = Math.max(1, sc / ART); // one art-pixel on screen
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
  getChunk: ChunkSource,
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
  glr.begin(cv.width, cv.height, VOID_F[0], VOID_F[1], VOID_F[2]);
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
    const ground = groundLayer(meta, chunkTiles, tilePx, getChunk, level, nowMs,
      wantedChunks(cam, cv.width, cv.height, meta, chunkTiles));
    // The floor below goes down FIRST, under its own parallax, so the ground
    // layer's holes look onto it (see belowLows, belowChunks). Neither its
    // low maps nor its chunks change once decoded, so each is a permanent
    // texture at rev 0.
    for (const [z, img, bx, by, bw, bh] of belowLows(cam, cv.width, cv.height, meta, level)) {
      glr.layer('belowlo:' + z, img, 0, null, bx, by, bw, bh);
    }
    // Chunks sharpen the view down at near zoom only. Below ART the ground
    // samples its low mirror, and the floors under it must do the same:
    // these textures carry no mipmaps, and a chunk that had landed while
    // zoomed in was drawn 2-4x minified — a crisp, moiré-striped rectangle
    // on the smooth low map, its straight edges sliding against the ground
    // under parallax as the viewer panned.
    if (!lowZoom) {
      for (const [key, z, img, bx, by, bw, bh] of
          belowChunks(cam, cv.width, cv.height, meta, chunkTiles, level, getChunk)) {
        glr.layer('below:' + z + ':' + key, img, 0, null, bx, by, bw, bh);
      }
    }
    // Bodies on the floor below ride between the two layers: the batcher
    // draws in submission order, so the ground quad occludes them next.
    drawBelowBodiesGL(glr, cam, state, renderTime, level, cv.width, cv.height);
    if (ground) {
      if (lowZoom && groundLowCv) {
        glr.layer('groundlo:' + level, groundLowCv, groundRev, null, o0.x, o0.y, worldW, worldH);
      } else {
        glr.layer('ground:' + level, ground, groundRev, groundPatchRects, o0.x, o0.y, worldW, worldH);
      }
    }
    // Moving water over the still bake, under the plants and the bodies.
    for (const f of flowInView(cam, cv.width, cv.height, chunkTiles, level, getChunk, state.tick)) {
      const o = cam.worldToScreen(f.wx, f.wy);
      const side = chunkTiles * cam.scale;
      glr.layer('flow:' + level + ':' + f.key, f.cv, f.rev, null, o.x, o.y, side, side);
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

  // Painter's order (state.drawRank), sorted once per stream message.
  const tracks = state.ordered();
  const stepPx = state.maxStep() * cam.scale;
  const cvW = cv.width, cvH = cv.height;

  for (const t of tracks) {
    const e = t.curr;
    if (Math.round(e.z) !== level) continue;
    // Cull on the RECEIVED pose, before interpolating (see onScreen).
    const rx = cam.screenX(e.x), ry = cam.screenY(e.y);

    if (e.kind.startsWith('door.')) {
      const m = (e.size + 1) * cam.scale + CULL_PX;
      if (rx < -m || ry < -m || rx > cvW + m || ry > cvH + m) continue;
      ovDoors.push(e);
      continue;
    }
    if (e.kind.startsWith('switch.')) {
      const m = 8 * cam.scale + CULL_PX;
      if (rx < -m || ry < -m || rx > cvW + m || ry > cvH + m) continue;
      ovSwitches.push([e, state.tracks.get(e.pheno)?.curr]);
      continue;
    }

    if (!onScreen(rx, ry, e.size * cam.scale + stepPx, cvW, cvH)) continue;
    const p = state.sample(t, renderTime);
    const sx = cam.screenX(p.x), sy = cam.screenY(p.y);

    if (e.kind === 'phero') {
      if (PHERO_OFF) continue;
      const r = Math.max(2, e.size * cam.scale);
      const puff = pheroPuff();
      glr.sprite('phero', puff, 1, 0, 0, puff.width, puff.height, sx - r, sy - r, r * 2, r * 2);
      continue;
    }
    if (e.kind === 'sound') continue; // an event, not a body: the sense overlay draws it

    const r = e.size * cam.scale;

    const carrier = e.attachedTo >= 0 ? state.tracks.get(e.attachedTo) : undefined;
    if (carrier) {
      const cp = state.sample(carrier, renderTime, CARRIER);
      ovLinks.push([sx, sy, cam.screenX(cp.x), cam.screenY(cp.y)]);
    }

    if (e.kind === 'nest') {
      const stamp = nestStamp();
      const px = cam.scale / ART;
      glr.sprite('nest', stamp, 1, 0, 0, 11, 9,
        sx - 5.5 * px, sy - 4.5 * px, 11 * px, 9 * px);
      continue;
    }

    if (e.kind.startsWith('item.')) {
      const stamp = itemStamp(e.kind, e.rgb);
      const k = r / ITEM_R;
      const side = ITEM_STAMP * k;
      glr.sprite('item:' + e.kind + ':' + e.rgb, stamp, 1, 0, 0, ITEM_STAMP, ITEM_STAMP,
        sx - side / 2, sy - side / 2, side, side);
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
      const dst = n * (cam.scale / ART);
      const dc = headingCol(p.dir, t.col ?? -1);
      t.col = dc;
      glr.sprite(loader ? 'loader' : 'drone', stamp, 1, cell * dc, 0, cell, cell,
        sx - dst / 2, sy - dst / 2, dst, dst);
      continue;
    }

    const bodyPx = e.size * cam.scale * 2;
    if (e.flags & F_DEAD) {
      if (dots) {
        const sq = bodyPx;
        glr.quad(sx - sq / 2, sy - sq / 2, sq, sq, CORPSE_F[0], CORPSE_F[1], CORPSE_F[2], 1);
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
          corpse, 1, dc * c, 0, c, c, sx - box / 2, sy - box / 2, box, box);
      } else {
        glr.quad(sx - r, sy - r, r * 2, r * 2, PENDING_F[0], PENDING_F[1], PENDING_F[2], 1);
      }
      continue;
    }

    // No atlas fetch at dot size — see render() above for why prefetching
    // every glimpsed phenotype is a memory cliff on long-evolved worlds.
    const atlas = dots ? null : atlasFor(e.pheno);
    if (dots || !atlas) {
      // The dot LOD (and the pre-atlas placeholder, simplified to the same
      // block while the sprite streams in): solid quads, batched together.
      const sq = dots ? bodyPx : r * 1.4;
      const rgb = e.rgb;
      glr.quad(sx - sq / 2, sy - sq / 2, sq, sq,
        ((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255, 1);
    } else {
      const box = r * 2 * (CELL / (2 * ART_RADIUS));
      // Sticky heading bucket (see atlas.headingCol): bucket noise while
      // walking would otherwise flip the pose at frame rate.
      const cc = headingCol(p.dir, t.col ?? -1);
      t.col = cc;
      const { row: rr } = cell(p.dir, gaitMs, e.id);
      // Far zoom: quarter-size mip source, same threshold as the 2D path. This
      // is not just fewer pixels — the herd view is where EVERY phenotype is on
      // screen at once, and only the small mip textures can all stay resident.
      const c = box <= CELL / MIP ? CELL / MIP : CELL;
      const src = c === CELL ? atlas : atlasMipFor(e.pheno, atlas);
      // The atlas is colour-neutral (one per SHAPE); the creature's rgb rides
      // the quad and the ramp shader re-tints it — see gl.ts mode 1.
      glr.sprite(atlasKey(e.pheno, c !== CELL), src, 1,
        cc * c, rr * c, c, c, sx - box / 2, sy - box / 2, box, box, 1, e.rgb);
    }

    if (e.size * cam.scale >= GLYPH_MIN_BODY_PX) {
      const act = actionOf(e.flags);
      if (act) ovGlyphs.push([sx, sy - r * 2.0, r * 0.95, act]);
    }
    if (e.flags & F_GRABBED) ovRings.push(['grabbed', sx, sy, r]);
    else if (e.flags & F_CARRYING) ovRings.push(['carrying', sx, sy, r]);
    if (cam.followId === e.id) ovRings.push(['follow', sx, sy, r]);
    if (selection.id === e.id) ovRings.push(['selected', sx, sy, r]);
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
    const lidPad = 2 * cam.scale; // a lid is at most one tile from its occupant
    for (const t of tracks) {
      const e = t.curr;
      if (e.kind === 'phero' || e.kind === 'sound' || (e.flags & F_DEAD) || Math.round(e.z) !== level) continue;
      const rx = cam.screenX(e.x), ry = cam.screenY(e.y);
      if (rx < -lidPad || ry < -lidPad || rx > cvW + lidPad || ry > cvH + lidPad) continue;
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
// Tall grass (cover 4): the lit TIPS only, which is the loosest veil of the
// four — tall grass is cover you are partly visible in, and that ordering
// (thicket hides most, then reeds, then grass, then scrub) is the reason a
// third and fourth cover were worth drawing at all.
// Scrub (cover 5): the wood only. A stand of thorn is mostly sand and hides
// about as much as it looks like it does.
const REED_GAP = 0x14301f;   // GroundTextures.RAMP[CLS_REEDS][0]
const BLADE_TIP = 0x5f9850;  // RAMP[CLS_GRASS][2]
const SCRUB_WOOD = 0x3f7a38; // RAMP[CLS_CACTUS][1]
const SCRUB_THORN = 0x2a4d24; // RAMP[CLS_CACTUS][0]
// Cotton grass (cover 6): the tufts and their seed heads — reed stem, reed
// tip and the bloom cream — and nothing of the peat between them.
const SEDGE_STEM = 0x2c5a36;  // RAMP[CLS_REEDS][1]
const SEDGE_TIP = 0x4f8752;   // RAMP[CLS_REEDS][2]
const SEDGE_HEAD = 0xf0e8c6;  // GroundTextures.BLOOM_CREAM

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
  // The three colour-keyed veils. Each reads the tile's own baked pixels back
  // and keeps a set of them, so the client never re-implements a texture — it
  // only decides which of the Java renderer's own pixels come back over a body.
  // The Java pass makes the same decision from the same functions (see
  // Grid.renderConcealment), so the two agree with no shared literal but these.
  const keep = kind === 3 ? (c: number) => c !== REED_GAP
    : kind === 4 ? (c: number) => c === BLADE_TIP
    : kind === 5 ? (c: number) => c === SCRUB_WOOD || c === SCRUB_THORN
    : kind === 6 ? (c: number) => c === SEDGE_STEM || c === SEDGE_TIP || c === SEDGE_HEAD
    : null;
  if (keep) {
    const vg = VEIL_SCRATCH.getContext('2d', { willReadFrequently: true })!;
    vg.clearRect(0, 0, ART, ART);
    vg.imageSmoothingEnabled = false;
    vg.drawImage(src, sx, sy, srcTilePx, srcTilePx, 0, 0, ART, ART);
    const id = vg.getImageData(0, 0, ART, ART);
    const px = id.data;
    for (let i = 0; i < px.length; i += 4) {
      if (!keep(px[i] << 16 | px[i + 1] << 8 | px[i + 2])) px[i + 3] = 0;
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
    getChunk: ChunkSource,
    cover: Uint8Array, level: number, nowMs: number): HTMLCanvasElement {
  const fresh = cover !== canopySrc || level !== canopyLevel || !canopyCv
    || canopyCv.width !== meta.cols * ART;
  if (!fresh && (canopyHoles.length === 0 || nowMs < canopyRetryAt)) return canopyCv!;
  const stamp = (ctx: CanvasRenderingContext2D, i: number): boolean => {
    const tx = i % meta.cols, ty = Math.floor(i / meta.cols);
    const v = cover[i];
    const ccx = Math.floor(tx / chunkTiles), ccy = Math.floor(ty / chunkTiles);
    // A peek: the veil re-stamps a chunk's own pixels, so it exists only where
    // a chunk has been decoded -- the viewport at near zoom -- and fills in as
    // chunks arrive. At far zoom a hidden body is a dot the size of a tile's
    // cover anyway.
    const chunk = getChunk(ccx, ccy, level, false);
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
      if (v === 0 || v === 2) continue; // 2 is the duct, which lids itself
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

// ---- bodies on the floor below ----------------------------------------------
// The pit view is a real second level, and with the below cohort subscribed
// (below=1, the default) the stream carries its bodies too. They are drawn
// BETWEEN the two ground layers — after the floor below, before the level's
// own ground — so the upper level's opaque pixels occlude them for free and
// they show only through genuine openings. Drawn as dots, not sprites: through
// a pit mouth a moving dot IS the fact worth showing, and the dot takes the
// same centre-scaling the below ground does, so it sits on its floor as the
// view slides. Dimmed to sit under the pit veil's darkness.

type BelowBody = { x: number; y: number; px: number; col: string; rgb: number; dead: boolean };

function belowBodies(cam: Camera, state: WorldState, renderTime: number,
    level: number, w: number, h: number): BelowBody[] {
  const out: BelowBody[] = [];
  if (BELOW_OFF || level - 1 < 0) return out;
  const f = parallaxFactor(cam.scale); // the same factor the ground below slid by
  const cx = w / 2, cy = h / 2;
  const stepPx = state.maxStep() * cam.scale * f;
  for (const t of state.tracks.values()) {
    const e = t.curr;
    if (Math.round(e.z) !== level - 1) continue;
    const k = e.kind;
    if (!k.startsWith('npc.') && !k.startsWith('item.')) continue;
    // Cull on the received pose before interpolating (see onScreen); the
    // parallax shrinks the step along with everything else.
    const rx = cx + (cam.screenX(e.x) - cx) * f, ry = cy + (cam.screenY(e.y) - cy) * f;
    if (!onScreen(rx, ry, stepPx, w, h)) continue;
    const p = state.sample(t, renderTime);
    const sx = cx + (cam.screenX(p.x) - cx) * f, sy = cy + (cam.screenY(p.y) - cy) * f;
    out.push({
      x: sx, y: sy,
      px: Math.max(3, e.size * cam.scale * f * 1.6),
      col: '#' + e.rgb.toString(16).padStart(6, '0'),
      rgb: e.rgb,
      dead: (e.flags & F_DEAD) !== 0,
    });
  }
  return out;
}

function drawBelowBodies2D(g: CanvasRenderingContext2D, cam: Camera,
    state: WorldState, renderTime: number, level: number): void {
  const bodies = belowBodies(cam, state, renderTime, level, g.canvas.width, g.canvas.height);
  if (bodies.length === 0) return;
  g.save();
  g.globalAlpha = BELOW_ALPHA;
  for (const b of bodies) {
    drawDot(g, b.x, b.y, b.px, b.col, b.dead);
  }
  g.restore();
}

/** GL variant: the batcher draws in submission order, so these quads land
 *  between the below layer and the ground layer exactly like the 2D path.
 *  Solid quads with the colour on the vertex, so every dot shares the one
 *  white texture and the whole cohort is a single draw call. They used to
 *  be one 8x8 texture per body colour: through a pit mouth that is a
 *  handful, but a sky level's floor below is the surface, and the surface
 *  is the population -- eleven hundred bodies of alternating colour broke
 *  the batch on nearly every one, a thousand draw calls and as many
 *  textures a frame, none of them ever evicted. */
function drawBelowBodiesGL(glr: import('./gl').GLRenderer, cam: Camera,
    state: WorldState, renderTime: number, level: number,
    w: number, h: number): void {
  for (const b of belowBodies(cam, state, renderTime, level, w, h)) {
    const rgb = b.dead ? CORPSE_RGB : b.rgb;
    glr.quad(b.x - b.px / 2, b.y - b.px / 2, b.px, b.px,
        ((rgb >> 16) & 255) / 255, ((rgb >> 8) & 255) / 255, (rgb & 255) / 255, BELOW_ALPHA);
  }
}
