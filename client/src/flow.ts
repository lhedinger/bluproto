// Moving water: the rivers' flow drawn live over the still water the bake
// serves. ART-STYLE.md §4, "moving water".
//
// The bake draws every water still, because it is baked once and a texture
// cannot run on a clock. The flow grid (/api/world/flow/{z}) says, for each
// tile a river laid, which way the water goes and how fast -- STORED by the
// carver, never guessed from the water around a tile, for the reason a belt's
// run is stored: a channel's upstream end and downstream end look the same.
//
// Three things move, in the grammar the exploration sheet settled on (S5,
// with the rapids and cascade-step tiers):
//
//  - The drift. The bake's OWN water pixels, carried downstream. A layer that
//    repainted the water would be a second opinion about what it looks like
//    (§6: a live state re-composites the bake, never repaints it), so every
//    pixel this draws for the drift is a pixel the Java renderer put there,
//    sampled from upstream. A texture shifted by a growing offset has to jump
//    back somewhere; two copies half a cycle apart are mixed by ordered dither
//    (§3's grammar for coverage), each faded out by its own weight just as it
//    resets, so the loop never shows its seam -- and nothing is ever a new
//    colour, because each pixel is one copy or the other, never a blend.
//  - The dashes. Short runs of the water's own base and highlight riding
//    lanes downstream, fastest away from the banks (a channel drags at its
//    edges), outrunning the drift: the texture says "moving", the dashes say
//    which way and how fast.
//  - Whitewater, where the river runs fast (rapids) and where its bed steps
//    down sharply enough to break the surface across the channel (a cascade):
//    the shallows ramp, densest where the water is roughest.
//
// Only pixels that are water in the bake move: the bank's laps into a water
// tile (the autotiler's scallops) stay exactly where the bake drew them, and
// a source pixel that is bank, or a different water, is never dragged in.

import { ART, hash01 } from './artCore';
import { flagOff } from './flags';

const FLOW_OFF = flagOff('flow');

// The water and shallows ramps (GroundTextures.RAMP): what the marks are drawn in.
const WATER = [0x1a3a60, 0x24568c, 0x3172b0];
const SHALLOWS = [0x2b6a78, 0x4093a6, 0x63becd];
/** Whether a baked pixel is water, and so may move. By hue, not by palette: the
 *  bake tints its ramps (a shallows highlight is served as 61bbca as often as
 *  63becd), so an exact match missed most of a river. Water is the one ground
 *  whose blue stands well clear of its red and not far under its green; the
 *  soil, sand, grass, reeds and stone a bank laps in with all fail one or the
 *  other. */
function watery(rgb: number): boolean {
  const r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
  return b > 1.5 * r && b >= 0.75 * g;
}

/** World.FLOW_DX / FLOW_DY: the eight directions, clockwise from east. */
const FDX = [1, 1, 0, -1, -1, -1, 0, 1];
const FDY = [0, 1, 1, 1, 0, -1, -1, -1];
/** Art-pixels a tick, per speed tier (World.FLOW_SLOW, _STREAM, _RAPIDS). */
const SPEED = [0, 0.05, 0.12, 0.4];
/** Dash density per tier: a slow river's dashes are rare. */
const DASHES = [0, 0.08, 0.12, 0.14];
/** The drift's period, in art-pixels: one tile, so the two copies are half a tile apart. */
const CYCLE = ART;
/** Rebuild at most every this many ticks: the fastest mark moves a pixel in 2.5. */
const REBUILD_TICKS = 2;

const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];
const bayer = (x: number, y: number) => (BAYER[((y & 3) << 2) | (x & 3)] + 0.5) / 16;
const mod = (a: number, n: number) => ((a % n) + n) % n;
function vnoise(x: number, y: number, s: number): number {
  const xi = Math.floor(x), yi = Math.floor(y), xf = x - xi, yf = y - yi;
  const u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf);
  const a = hash01(xi, yi, s), b = hash01(xi + 1, yi, s), c = hash01(xi, yi + 1, s), d = hash01(xi + 1, yi + 1, s);
  return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
}

type ChunkSource = (cx: number, cy: number, z: number, fetch: boolean) => HTMLCanvasElement | null;

let grid: Uint8Array | null = null; // packed flow, World.getFlow's layout
let wet: Uint8Array | null = null;  // the water mask: 1 water, 2 shallows
let cols = 0, rows = 0, gridLevel = -1;
/** Chunk index -> the flowing tiles in it, so a view walks only those. */
let byChunk = new Map<number, number[]>();
let chunkTilesFor = 0;

/** Hands the layer a level's flow grid and water mask (both one byte a tile). */
export function setFlowGrid(flow: Uint8Array | null, water: Uint8Array | null,
    c: number, r: number, level: number): void {
  grid = flow;
  wet = water;
  cols = c;
  rows = r;
  gridLevel = level;
  byChunk = new Map();
  chunkTilesFor = 0;
  layers.clear(); // the old level's art
}

function index(chunkTiles: number): void {
  if (chunkTilesFor === chunkTiles || !grid) return;
  chunkTilesFor = chunkTiles;
  byChunk = new Map();
  const cxN = Math.ceil(cols / chunkTiles);
  for (let i = 0; i < grid.length; i++) {
    if (!grid[i]) continue;
    const x = i % cols, y = (i / cols) | 0;
    const k = Math.floor(y / chunkTiles) * cxN + Math.floor(x / chunkTiles);
    let list = byChunk.get(k);
    if (!list) byChunk.set(k, list = []);
    list.push(i);
  }
}

// The bake's pixels, read back once per chunk canvas.
const pixelsOf = new WeakMap<HTMLCanvasElement, Uint32Array>();
function pixels(cv: HTMLCanvasElement): Uint32Array {
  let p = pixelsOf.get(cv);
  if (!p) {
    const d = cv.getContext('2d', { willReadFrequently: true })!.getImageData(0, 0, cv.width, cv.height).data;
    p = new Uint32Array(d.buffer.slice(0));
    pixelsOf.set(cv, p);
  }
  return p;
}
/** The chunks one repaint reads, looked up once each: asking the source per
 *  pixel built a cache key string twice for every water pixel on screen. */
let paintChunks = new Map<number, { px: Uint32Array; w: number } | null>();
/** One baked art-pixel as 0xRRGGBB, or -1 where its chunk has not landed. */
function bakeAt(gx: number, gy: number, chunkTiles: number, level: number, getChunk: ChunkSource): number {
  const cp = chunkTiles * ART;
  const cx = Math.floor(gx / cp), cy = Math.floor(gy / cp);
  const k = cy * 4096 + cx;
  let c = paintChunks.get(k);
  if (c === undefined) {
    const cv = cx < 0 || cy < 0 ? null : getChunk(cx, cy, level, false);
    c = cv ? { px: pixels(cv), w: cv.width } : null;
    paintChunks.set(k, c);
  }
  if (!c) return -1;
  const v = c.px[(gy - cy * cp) * c.w + (gx - cx * cp)];
  return ((v & 0xff) << 16) | (v & 0xff00) | ((v >> 16) & 0xff); // RGBA little-endian -> RGB
}

type Layer = { cv: HTMLCanvasElement; img: ImageData; built: number; rev: number };
const layers = new Map<number, Layer>();

/** The moving water over the chunks in view, as art-resolution canvases a
 *  chunk each, transparent wherever nothing moves. `rev` changes whenever a
 *  canvas is redrawn (the GL path re-uploads on it). Empty at far zoom, where
 *  an art-pixel is under a device pixel and motion would be noise. */
export function flowLayers(scale: number, view: { x0: number; y0: number; x1: number; y1: number },
    chunkTiles: number, level: number, getChunk: ChunkSource, tick: number):
    Array<{ key: number; cv: HTMLCanvasElement; rev: number; wx: number; wy: number }> {
  const out: Array<{ key: number; cv: HTMLCanvasElement; rev: number; wx: number; wy: number }> = [];
  if (FLOW_OFF || !grid || !wet || level !== gridLevel || scale < ART || chunkTiles <= 0) return out;
  index(chunkTiles);
  const cxN = Math.ceil(cols / chunkTiles);
  const cx0 = Math.max(0, Math.floor(view.x0 / chunkTiles)), cx1 = Math.floor(view.x1 / chunkTiles);
  const cy0 = Math.max(0, Math.floor(view.y0 / chunkTiles)), cy1 = Math.floor(view.y1 / chunkTiles);
  for (let cy = cy0; cy <= cy1; cy++) {
    for (let cx = cx0; cx <= cx1; cx++) {
      const key = cy * cxN + cx;
      const tiles = byChunk.get(key);
      if (!tiles || !getChunk(cx, cy, level, false)) continue;
      let L = layers.get(key);
      if (!L) {
        const cv = document.createElement('canvas');
        cv.width = cv.height = chunkTiles * ART;
        L = { cv, img: cv.getContext('2d')!.createImageData(cv.width, cv.height), built: -1e9, rev: 0 };
        layers.set(key, L);
      }
      if (tick - L.built >= REBUILD_TICKS || tick < L.built) {
        paintChunk(L, tiles, cx * chunkTiles, cy * chunkTiles, chunkTiles, level, getChunk, tick);
        L.built = tick;
        L.rev++;
      }
      out.push({ key, cv: L.cv, rev: L.rev, wx: cx * chunkTiles, wy: cy * chunkTiles });
    }
  }
  return out;
}

function paintChunk(L: Layer, tiles: number[], wx0: number, wy0: number, chunkTiles: number,
    level: number, getChunk: ChunkSource, t: number): void {
  const d = new Uint32Array(L.img.data.buffer);
  d.fill(0);
  paintChunks = new Map();
  const W = chunkTiles * ART;
  for (const i of tiles) {
    const tx = i % cols, ty = (i / cols) | 0;
    const f = grid![i], kind = wet![i];
    if (kind !== 1 && kind !== 2) continue;
    const speed = f & 3, dir = (f >> 2) & 7, step = (f & 32) !== 0;
    const fx = FDX[dir], fy = FDY[dir];
    const ramp = kind === 2 ? SHALLOWS : WATER;
    // A channel drags at its banks: a tile with dry ground beside it runs slower.
    const banked = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([ox, oy]) => {
      const nx = tx + ox, ny = ty + oy;
      return nx < 0 || ny < 0 || nx >= cols || ny >= rows || !wet![ny * cols + nx];
    });
    const shear = banked ? 0.45 : 1;
    const spd = SPEED[speed];
    // The drift: two copies of the bake, CYCLE apart in phase, dithered by weight.
    const a = mod(spd * 0.6 * t, CYCLE);
    const wA = 1 - Math.abs(a / (CYCLE / 2) - 1);
    const oa = Math.floor(a), ob = Math.floor(mod(a + CYCLE / 2, CYCLE));
    const dashSp = spd * (0.35 + 0.65 * shear);
    const dashOff = Math.floor(dashSp * t);
    for (let aj = 0; aj < ART; aj++) {
      for (let ai = 0; ai < ART; ai++) {
        const gx = tx * ART + ai, gy = ty * ART + aj;
        const own = bakeAt(gx, gy, chunkTiles, level, getChunk);
        if (own < 0 || !watery(own)) continue; // bank laps stay the bake's
        const o = bayer(gx, gy) < wA ? oa : ob;
        let col = own;
        const sx = gx - fx * o, sy = gy - fy * o;
        const stx = Math.floor(sx / ART), sty = Math.floor(sy / ART);
        if (stx >= 0 && sty >= 0 && stx < cols && sty < rows && wet![sty * cols + stx] === kind) {
          const src = bakeAt(sx, sy, chunkTiles, level, getChunk);
          if (src >= 0 && watery(src)) col = src;
        }
        // Lanes run along the flow; u is the distance downstream, lane the one across.
        const u = gx * fx + gy * fy, lane = gy * fx - gx * fy;
        if (speed === 3) {
          // Rapids: whitewater where the bed breaks the surface, patches that
          // ride the drift, dashes thickening in them.
          const rough = vnoise((u - Math.floor(spd * 0.6 * t)) / 10, lane / 6, 55) > 0.55;
          const uu = u - dashOff + Math.floor(hash01(lane, 0, 51) * 97);
          const seg = Math.floor(uu / 7), k = mod(uu, 7);
          if (hash01(seg, lane, 53) < (rough ? 0.5 : DASHES[3]) && k < 3) col = k === 2 ? SHALLOWS[1] : SHALLOWS[2];
          else if (rough && hash01(seg, lane, 57) < 0.25 && k < 4) col = SHALLOWS[0];
        } else {
          const uu = u - dashOff + Math.floor(hash01(lane, 0, 31) * 97);
          const seg = Math.floor(uu / 11), k = mod(uu, 11);
          if (hash01(seg, lane, 33) < DASHES[speed] && k < 3) col = k === 0 ? ramp[1] : ramp[2];
        }
        if (step) {
          // A cascade: the bed drops, so it takes §4's grammar for a drop -- a
          // lit lip (whitewater flickering on the tick) and, just past it, the
          // sunk band of the water below the step, with foam churned into it.
          // The lip alone vanished on a shallows brook, which is already as
          // bright as whitewater, and a one-pixel band under it read as
          // texture; two of each, and a band a ramp deeper, is what reads.
          const diag = fx !== 0 && fy !== 0;
          const along = (fx * (ai - (ART - 1) / 2) + fy * (aj - (ART - 1) / 2)) / (diag ? 2 : 1); // -5.5 .. 5.5
          // Below the step the water is deeper: a shallows brook drops into the
          // water ramp, open water into its shadow.
          const deep = kind === 2 ? WATER[1] : WATER[0];
          if (along >= 3.5) col = hash01(lane >> 1, Math.floor(t / 3) + 9, 85) < 0.25 ? SHALLOWS[1] : deep;
          else if (along >= 1.5) col = hash01(lane >> 1, Math.floor(t / 3), 83) < 0.7 ? SHALLOWS[2] : SHALLOWS[1];
        }
        const lx = gx - wx0 * ART, ly = gy - wy0 * ART;
        d[ly * W + lx] = 0xff000000 | ((col & 0xff) << 16) | (col & 0xff00) | ((col >> 16) & 0xff);
      }
    }
  }
  L.cv.getContext('2d')!.putImageData(L.img, 0, 0);
}
