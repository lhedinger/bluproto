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
/** Shallows (teal) rather than open water (blue): green close to blue. The
 *  shoreline curves between the two through the middle of tiles, so a source
 *  pixel is taken only from the same water as the pixel it replaces -- or the
 *  drift would drag the deep pool across its own outline, back onto the tile
 *  grid. */
function teal(rgb: number): boolean {
  return ((rgb >> 8) & 255) > 0.77 * (rgb & 255);
}

/** World.FLOW_FALL / FLOW_PLUNGE: the lip of a fall, a plunge pool. */
const FALL = 1 << 6, PLUNGE = 1 << 7;
/** World.FLOW_DX / FLOW_DY: the eight directions, clockwise from east. */
const FDX = [1, 1, 0, -1, -1, -1, 0, 1];
const FDY = [0, 1, 1, 1, 0, -1, -1, -1];
/** Art-pixels a tick, per speed tier (World.FLOW_SLOW, _STREAM, _RAPIDS). */
const SPEED = [0, 0.05, 0.12, 0.4];
/** Dash density per tier: a slow river's dashes are rare. */
const DASHES = [0, 0.08, 0.12, 0.14];
/** The drift's period, in art-pixels: one tile, so the two copies are half a tile apart. */
const CYCLE = ART;
/** Art-px over which the motion fades out against still water. */
const FADE = 6;
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
    const falls = (f & FALL) !== 0, plunge = (f & PLUNGE) !== 0;
    const speed = f & 3, dir = (f >> 2) & 7, step = (f & 32) !== 0 && !plunge; // on a pool the bit is its core
    const fx = FDX[dir], fy = FDY[dir];
    const ramp = kind === 2 ? SHALLOWS : WATER;
    // A channel drags at its banks: a tile with dry ground beside it runs slower.
    const banked = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([ox, oy]) => {
      const nx = tx + ox, ny = ty + oy;
      return nx < 0 || ny < 0 || nx >= cols || ny >= rows || !wet![ny * cols + nx];
    });
    const shear = banked ? 0.45 : 1;
    // Still water beside this tile (a pond the river runs into): the motion
    // fades out toward it over FADE art-px by ordered dither, or the drifted
    // texture would stop dead at the tile line and draw the tile as a box.
    const stillAt = (ox: number, oy: number) => {
      const nx = tx + ox, ny = ty + oy;
      if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) return false;
      const j = ny * cols + nx;
      return !!wet![j] && !grid![j];
    };
    const stillN = stillAt(0, -1), stillS = stillAt(0, 1), stillW = stillAt(-1, 0), stillE = stillAt(1, 0);
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
        let edge = FADE;
        if (stillN) edge = Math.min(edge, aj);
        if (stillS) edge = Math.min(edge, ART - 1 - aj);
        if (stillW) edge = Math.min(edge, ai);
        if (stillE) edge = Math.min(edge, ART - 1 - ai);
        if (edge < FADE && bayer(gx, gy) >= (edge + 0.5) / FADE) continue; // still, as the pond beside it
        const o = bayer(gx, gy) < wA ? oa : ob;
        let col = own;
        const sx = gx - fx * o, sy = gy - fy * o;
        const stx = Math.floor(sx / ART), sty = Math.floor(sy / ART);
        if (stx >= 0 && sty >= 0 && stx < cols && sty < rows && wet![sty * cols + stx] === kind) {
          const src = bakeAt(sx, sy, chunkTiles, level, getChunk);
          if (src >= 0 && watery(src) && teal(src) === teal(own)) col = src;
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
        if (falls) {
          // The lip of a fall: the river breaks white along the edge it pours
          // over -- the last two art-px before the drop -- and streaks just
          // behind it as the run quickens. The sheet itself is drawn into the
          // drop ahead (paintFall).
          const along = fx * (ai - (ART - 1) / 2) + fy * (aj - (ART - 1) / 2); // -5.5 .. 5.5
          if (along >= 3.5) col = hash01(lane >> 1, Math.floor(t / 2), 87) < 0.75 ? SHALLOWS[2] : SHALLOWS[1];
          else if (along >= 0.5 && hash01(lane, Math.floor((u - Math.floor(0.6 * t)) / 3), 89) < 0.3) col = SHALLOWS[1];
        } else if (plunge) {
          col = plungeAt(col, tx, ty, gx, gy, t);
        } else if (step) {
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
    if (falls) paintFall(d, W, tx, ty, fx, fy, wx0, wy0, chunkTiles, level, getChunk, t);
  }
  L.cv.getContext('2d')!.putImageData(L.img, 0, 0);
}

/** How far a falling sheet reads into the drop, in art-px, before the dark
 *  under the veil takes it. */
const FALL_REACH = 12;

/**
 * The sheet of a fall, drawn into the drop past its lip (the hole tile the
 * lip flows into), as ART-STYLE §4 has it: streaks running down fast in the
 * water ramp with the shallows highlight on their leading ends, thinning by
 * ordered dither with every art-px of fall until the pit veil's dark takes
 * them. A lane falls only where water reaches the lip in it, so the sheet is
 * as wide as the river at its edge and the bank's laps make no water.
 */
function paintFall(d: Uint32Array, W: number, tx: number, ty: number, fx: number, fy: number,
    wx0: number, wy0: number, chunkTiles: number, level: number, getChunk: ChunkSource, t: number): void {
  const hx = tx + fx, hy = ty + fy;
  if (hx < wx0 || hy < wy0 || hx >= wx0 + chunkTiles || hy >= wy0 + chunkTiles) return; // the drop is in the next chunk
  for (let aj = 0; aj < ART; aj++) {
    for (let ai = 0; ai < ART; ai++) {
      const gx = hx * ART + ai, gy = hy * ART + aj;
      // how far below the lip, and the lip pixel in the same lane
      const fall = fx > 0 ? ai : fx < 0 ? ART - 1 - ai : fy > 0 ? aj : ART - 1 - aj;
      const lipX = fx !== 0 ? (fx > 0 ? tx * ART + ART - 1 : tx * ART) : gx;
      const lipY = fy !== 0 ? (fy > 0 ? ty * ART + ART - 1 : ty * ART) : gy;
      const lip = bakeAt(lipX, lipY, chunkTiles, level, getChunk);
      if (lip < 0 || !watery(lip)) continue;
      if (bayer(gx, gy) >= 1 - fall / FALL_REACH) continue; // the dark takes it
      const lane = gy * fx - gx * fy;
      const u = fall - Math.floor(0.9 * t) + Math.floor(hash01(lane, 1, 67) * 97);
      const seg = Math.floor(u / 6), k = mod(u, 6);
      const col = hash01(seg, lane, 69) < 0.55 && k < 2 ? (k === 0 ? SHALLOWS[1] : WATER[2])
        : fall < 3 ? WATER[1] : WATER[0];
      const lx = gx - wx0 * ART, ly = gy - wy0 * ART;
      d[ly * W + lx] = 0xff000000 | ((col & 0xff) << 16) | (col & 0xff00) | ((col >> 16) & 0xff);
    }
  }
}

// Ripple rings, authored (ART-STYLE §5: discrete shapes are drawn stamps, not
// distance tests), radius 4, 7 and 10, centred on the pool's core.
const RINGS: string[][] = [
  ['...###...', '.##...##.', '.#.....#.', '#.......#', '#.......#', '#.......#', '.#.....#.', '.##...##.', '...###...'],
  ['.....#####.....', '...##.....##...', '..#.........#..', '.#...........#.', '.#...........#.',
    '#.............#', '#.............#', '#.............#', '#.............#', '#.............#',
    '.#...........#.', '.#...........#.', '..#.........#..', '...##.....##...', '.....#####.....'],
  ['.......#######.......', '.....##.......##.....', '....#...........#....', '...#.............#...',
    '..#...............#..', '.#.................#.', '.#.................#.', '#...................#',
    '#...................#', '#...................#', '#...................#', '#...................#',
    '#...................#', '#...................#', '.#.................#.', '.#.................#.',
    '..#...............#..', '...#.............#...', '....#...........#....', '.....##.......##.....',
    '.......#######.......'],
];
/** Ticks each ring is shown before the next, larger one. */
const RING_TICKS = 8;

/**
 * A plunge pool, where a fall lands a storey down: the core (the tile under
 * the lip) churns in the shallows ramp, re-hashed every two ticks, and rings
 * leave it -- one at a time, each a little wider, the newest in the water
 * highlight and the one before it in the base.
 */
function plungeAt(col: number, tx: number, ty: number, gx: number, gy: number, t: number): number {
  let cx = -1, cy = -1;
  for (let oy = -1; oy <= 1 && cx < 0; oy++) {
    for (let ox = -1; ox <= 1; ox++) {
      const nx = tx + ox, ny = ty + oy;
      if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue;
      const g = grid![ny * cols + nx];
      if ((g & PLUNGE) && (g & 32)) { cx = nx; cy = ny; break; }
    }
  }
  if (cx < 0) return col;
  const dx = gx - (cx * ART + 6), dy = gy - (cy * ART + 6);
  if (Math.abs(dx) <= 3 && Math.abs(dy) <= 3) {
    return hash01(gx, gy + Math.floor(t / 2) * 13, 93) < 0.55 ? SHALLOWS[2] : SHALLOWS[1];
  }
  const phase = Math.floor(t / RING_TICKS) % RINGS.length;
  const on = (r: number) => {
    const st = RINGS[r], n = st.length >> 1;
    const row = st[dy + n];
    return row !== undefined && row[dx + n] === '#';
  };
  if (on(phase)) return WATER[2];
  if (phase > 0 && on(phase - 1)) return WATER[1];
  return col;
}
