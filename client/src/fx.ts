// Viewer overlays drawn over the world: the sense heatmaps, the climate wash
// and the water glints. Split out of render.ts; render.ts re-exports it.

import { ART, hash01 } from './artCore';
import type { Camera } from './camera';
import { flagOff } from './flags';
import { F_DEAD } from './protocol';
import type { WorldState } from './state';

const GLINTS_OFF = flagOff('glints'); // live glints on water

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


// --- climate overlays ---------------------------------------------------------
// One of three static per-tile fields -- light, temperature, humidity -- drawn
// as a tinted raster under the sense heatmaps. The server hands each over once
// per level as a byte a tile on the field's own range; the colouring happens
// here, once per grid, into an offscreen canvas the size of the map in tiles,
// and the frame scales that up with smoothing off so every tile stays a
// square. The three ramps say different things on purpose: light darkens what
// the sky cannot reach (a lit tile is left alone), temperature is a diverging
// cool-to-warm wash, humidity a single blue that deepens with moisture.
export type ClimateField = 'light' | 'temperature' | 'humidity';
let climCanvas: HTMLCanvasElement | null = null;
let climFor: Uint8Array | null = null;
let climField: ClimateField | null = null;
let climRangeFor: ClimateRange | null = null;

export type ClimateRange = { min: number; max: number; mid?: number };

function climatePixels(field: ClimateField, grid: Uint8Array, range: ClimateRange,
    px: Uint8ClampedArray): void {
  // Temperature diverges around the ground's own temperature (the server's
  // `mid`), not the midpoint of the range: a cave at the region's mean is
  // neither cool nor warm, however hot the vent that stretched the range.
  const mid = range.mid ?? (range.min + range.max) / 2;
  const half = Math.max(range.max - mid, mid - range.min, 1e-9);
  for (let i = 0; i < grid.length; i++) {
    const u = grid[i] / 255;
    const o = i * 4;
    if (field === 'light') {
      px[o] = 10; px[o + 1] = 10; px[o + 2] = 30;
      px[o + 3] = Math.round((1 - u) * 190); // the veil thickens as the light goes
    } else if (field === 'temperature') {
      const v = range.min + u * (range.max - range.min);
      const t = Math.max(-1, Math.min(1, (v - mid) / half)); // -1 cool .. +1 warm
      if (t < 0) { px[o] = 59; px[o + 1] = 111; px[o + 2] = 214; }
      else { px[o] = 232; px[o + 1] = 68; px[o + 2] = 42; }
      px[o + 3] = Math.round(Math.abs(t) * 150 + 20);
    } else {
      px[o] = 47; px[o + 1] = 143; px[o + 2] = 214;
      px[o + 3] = Math.round(u * 170);
    }
  }
}

// ---- water glints -----------------------------------------------------------
//
// The one thing a bake cannot carry: light moving on a surface. The ground
// bake scatters a few fixed 2-px glints in each water's highlight; this layer
// adds the ones that come and go. Each water kind sparkles its own way — the
// wading shallows densest and quickest, open water sparser, a bog pool rare
// and slow with a sky reflection on black water, mire rarer still — so the
// kinds tell apart by motion as well as by pattern.
//
// Everything about a glint is hashed from its world position: where it sits
// on the art-pixel lattice, its period, its phase. Nothing consumes RNG or the
// wall clock; the phase runs on the simulation tick (ART-STYLE §1), so two
// viewers see the same water twinkle the same way, and a paused world holds
// still. The glint set for a level is built once from the water mask and read
// every frame, so the per-frame cost is a cull over a few thousand entries,
// not a hash per tile.
//
// Colours are the water ramps' own highlights, pinned by a scenario
// (TheWaterGlintsAreTheRampsHighlights) because Java cannot read this file.
export const GLINT_WATER = 0x3172b0;    // GroundTextures.RAMP[CLS_WATER][2]
export const GLINT_SHALLOWS = 0x63becd; // RAMP[CLS_SHALLOWS][2]
/** Lattice pitch in art-px: nine cells a tile, at most one glint each. */
const GLINT_C = 4;
/** Per water kind (index = the mask byte): colour, expected glints per tile,
 *  blink period range in ticks, and the lit share of each period. Tuned by
 *  eye for how a whole surface reads, not one glint: what registers as pace
 *  is the rate of events across the water, so a calm surface wants few
 *  glints each lit long (half its period, swelling and dimming over seconds)
 *  rather than many each winking briefly. */
export const GLINT_KINDS: ReadonlyArray<{ col: number; per: number; pmin: number; pmax: number; lit: number } | null> = [
  null,
  { col: GLINT_WATER, per: 0.5, pmin: 150, pmax: 360, lit: 0.5 },    // 1 open water
  { col: GLINT_SHALLOWS, per: 1.2, pmin: 90, pmax: 210, lit: 0.5 },  // 2 shallows
  { col: GLINT_WATER, per: 0.3, pmin: 240, pmax: 480, lit: 0.5 },    // 3 tannin pool
  { col: GLINT_WATER, per: 0.15, pmin: 270, pmax: 540, lit: 0.5 },   // 4 mire
];
export interface Glint { x: number; y: number; kind: number; period: number; phase: number }

/** Every glint a level will ever show, from its water mask: world-absolute
 *  art-pixel positions, each with its own period and phase. */
export function glintsFor(water: Uint8Array, cols: number, rows: number): Glint[] {
  const out: Glint[] = [];
  const cells = ART / GLINT_C; // per tile side
  for (let ty = 0; ty < rows; ty++) {
    for (let tx = 0; tx < cols; tx++) {
      const kind = water[ty * cols + tx];
      const k = GLINT_KINDS[kind];
      if (!k) continue;
      for (let j = 0; j < cells; j++) {
        for (let i = 0; i < cells; i++) {
          const cx = tx * cells + i, cy = ty * cells + j;
          if (hash01(cx, cy, 81) >= k.per / (cells * cells)) continue;
          const period = k.pmin + Math.floor(hash01(cx, cy, 82) * (k.pmax - k.pmin));
          out.push({
            x: cx * GLINT_C + 1 + Math.floor(hash01(cx, cy, 84) * 2),
            y: cy * GLINT_C + 1 + Math.floor(hash01(cx, cy, 85) * 2),
            kind, period, phase: Math.floor(hash01(cx, cy, 83) * period),
          });
        }
      }
    }
  }
  return out;
}

/** Where a glint is in its blink at `tick`: -1 while dark, else 0..1 across
 *  the lit window. */
export function glintLit(gl: Glint, tick: number): number {
  const k = GLINT_KINDS[gl.kind]!;
  const u = (((tick + gl.phase) % gl.period) + gl.period) % gl.period / gl.period;
  return u > k.lit ? -1 : u / k.lit;
}

/** Stamp the lit glints of `glints` into `g`, with art-pixel (0,0) at device
 *  (ox, oy) and `px` device pixels per art-pixel; only glints inside the
 *  art-pixel box [x0,x1)×[y0,y1) are considered. A glint is a 2-px dash in
 *  its water's highlight, and a 4-px spark through the middle of its blink.
 *  It fades in and out over its lit window — translucency on whole art-pixel
 *  blocks, the pheromone haze's idiom: the structure stays pixelated and
 *  only the tint is soft. */
export function drawGlints(g: CanvasRenderingContext2D, glints: Glint[], tick: number,
    ox: number, oy: number, px: number, x0: number, y0: number, x1: number, y1: number): void {
  const d = Math.ceil(px);
  let last = -1;
  for (const gl of glints) {
    if (gl.x < x0 - 1 || gl.x >= x1 || gl.y < y0 - 1 || gl.y >= y1 + 1) continue;
    const s = glintLit(gl, tick);
    if (s < 0) continue;
    const col = GLINT_KINDS[gl.kind]!.col;
    if (col !== last) {
      g.fillStyle = '#' + col.toString(16).padStart(6, '0');
      last = col;
    }
    g.globalAlpha = s < 0.5 ? s * 2 : (1 - s) * 2; // in over the first half, out over the second
    const sx = Math.round(ox + gl.x * px), sy = Math.round(oy + gl.y * px);
    g.fillRect(sx, sy, d, d);
    g.fillRect(Math.round(ox + (gl.x + 1) * px), sy, d, d);
    if (s > 0.3 && s < 0.7) {
      g.fillRect(sx, Math.round(oy + (gl.y - 1) * px), d, d);
      g.fillRect(Math.round(ox + (gl.x + 1) * px), Math.round(oy + (gl.y + 1) * px), d, d);
    }
  }
  g.globalAlpha = 1;
}

let glintSrc: Uint8Array | null = null;
let glintSet: Glint[] = [];

/** The live glint layer over the viewport's water. Drawn only from 12 px a
 *  tile up: below that an art-pixel is less than a device pixel and a glint
 *  is a flicker of noise, which the far view does not need. */
export function drawWaterGlints(g: CanvasRenderingContext2D, cam: Camera,
    water: Uint8Array, cols: number, rows: number, tick: number): void {
  if (GLINTS_OFF || cam.scale < ART) return;
  if (water !== glintSrc) {
    glintSet = glintsFor(water, cols, rows);
    glintSrc = water;
  }
  const o = cam.worldToScreen(0, 0);
  const px = cam.scale / ART;
  const x0 = Math.floor(-o.x / px), y0 = Math.floor(-o.y / px);
  const x1 = Math.ceil((g.canvas.width - o.x) / px), y1 = Math.ceil((g.canvas.height - o.y) / px);
  drawGlints(g, glintSet, tick, o.x, o.y, px, x0, y0, x1, y1);
}

export function drawClimateLayer(g: CanvasRenderingContext2D, cam: Camera,
    field: ClimateField, grid: Uint8Array, range: ClimateRange, cols: number, rows: number): void {
  if (climCanvas === null || climFor !== grid || climField !== field || climRangeFor !== range) {
    climCanvas = document.createElement('canvas');
    climCanvas.width = cols;
    climCanvas.height = rows;
    const cg = climCanvas.getContext('2d')!;
    const img = cg.createImageData(cols, rows);
    climatePixels(field, grid, range, img.data);
    cg.putImageData(img, 0, 0);
    climFor = grid;
    climField = field;
    climRangeFor = range;
  }
  const o = cam.worldToScreen(0, 0);
  g.save();
  g.imageSmoothingEnabled = false;
  g.drawImage(climCanvas, o.x, o.y, cols * cam.scale, rows * cam.scale);
  g.restore();
}
