// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import { ART_RADIUS, CELL, RIM_COLOUR, atlasFor, cell, corpseFor, rimFor } from './atlas';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_FLEE, ACT_GRAB, ACT_GRAZE, ACT_HUNT, ACT_MATE,
  ACT_NEST, actionOf, F_CARRYING, F_DEAD, F_GRABBED, F_MINDED,
} from './protocol';
import type { EntityState } from './protocol';
import type { Track, WorldState } from './state';

export interface WorldMeta { cols: number; rows: number; }

/** The classic 4x4 ordered-dither matrix (row-major), shared threshold table
 *  for pixel-art style partial coverage — matching the ground bake's dithers. */
const BAYER4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];

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

export function render(
  g: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  chunkTiles: number,
  tilePx: number,
  getChunk: (cx: number, cy: number) => HTMLImageElement,
  getBareChunk: (cx: number, cy: number) => HTMLImageElement,
  veg: Uint8Array | null,
  cover: Uint8Array | null,
  renderTime: number,
  nowMs: number,
  level = 0,
  selection: { id: number | null; tile: { x: number; y: number; z: number } | null } =
    { id: null, tile: null },
): void {
  const cv = g.canvas;
  g.fillStyle = '#14161a';
  g.fillRect(0, 0, cv.width, cv.height);
  if (!meta) return;

  // Ground: draw the baked map chunks covering the viewport, streamed on
  // demand (nearest-neighbour so zooming in shows fat crisp pixels, not blur).
  if (chunkTiles > 0) {
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
        if (!img.complete || !img.naturalWidth) continue;
        const wx = cx * chunkTiles, wy = cy * chunkTiles;
        const cw = Math.min(chunkTiles, meta.cols - wx);
        const ch = Math.min(chunkTiles, meta.rows - wy);
        const o = cam.worldToScreen(wx, wy);
        g.drawImage(img, o.x, o.y, cw * cam.scale, ch * cam.scale);
      }
    }
  }

  // Live grazing: a depleted tile dithers toward the fully-grazed twin bake
  // (255 = non-vegetated, no overlay; 100 = lush, none; 0 = grazed bare, the
  // bare bake shows whole). Per art-pixel, the Bayer mask decides which bake
  // wins — so depletion literally erases grass motifs (and fungus glow) down
  // to what the Java renderer draws for bare ground, never a tint the palette
  // doesn't contain. Regrowth runs the same dither in reverse.
  if (veg && chunkTiles > 0 && tilePx > 0) {
    const tl = cam.screenToWorld(0, 0);
    const br = cam.screenToWorld(cv.width, cv.height);
    const x0 = Math.max(0, Math.floor(tl.x)), y0 = Math.max(0, Math.floor(tl.y));
    const x1 = Math.min(meta.cols - 1, Math.ceil(br.x)), y1 = Math.min(meta.rows - 1, Math.ceil(br.y));
    if (SCRATCH.width !== tilePx) { SCRATCH.width = tilePx; SCRATCH.height = tilePx; }
    const sg = SCRATCH.getContext('2d')!;
    for (let ty = y0; ty <= y1; ty++) {
      for (let tx = x0; tx <= x1; tx++) {
        const lvl = veg[ty * meta.cols + tx];
        if (lvl >= 100 || lvl === 255) continue; // lush or non-vegetated: nothing
        const depl16 = Math.min(16, Math.round((100 - lvl) * 16 / 100));
        if (depl16 <= 0) continue;
        const ccx = Math.floor(tx / chunkTiles), ccy = Math.floor(ty / chunkTiles);
        const bare = getBareChunk(ccx, ccy);
        if (!bare.complete || !bare.naturalWidth) continue; // still streaming in
        const sx = (tx - ccx * chunkTiles) * tilePx, sy = (ty - ccy * chunkTiles) * tilePx;
        const o = cam.worldToScreen(tx, ty);
        if (cam.scale < 12) {
          // Art-pixels are sub-pixel here; coverage as alpha is exactly what
          // downscaling the dithered composite would resolve to.
          g.globalAlpha = depl16 / 16;
          g.drawImage(bare, sx, sy, tilePx, tilePx, o.x, o.y, cam.scale, cam.scale);
          g.globalAlpha = 1;
          continue;
        }
        sg.clearRect(0, 0, tilePx, tilePx);
        sg.drawImage(bare, sx, sy, tilePx, tilePx, 0, 0, tilePx, tilePx);
        sg.globalCompositeOperation = 'destination-in';
        sg.imageSmoothingEnabled = false; // mask cells stay crisp when upscaled
        sg.drawImage(ditherMask(depl16), 0, 0, tilePx, tilePx);
        sg.globalCompositeOperation = 'source-over';
        g.drawImage(SCRATCH, 0, 0, tilePx, tilePx, o.x, o.y, cam.scale, cam.scale);
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
  const tracks = [...state.tracks.values()].sort((a, b) => order(a) - order(b));

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
      const r = Math.max(2, e.size * cam.scale);
      const grad = g.createRadialGradient(s.x, s.y, 0, s.x, s.y, r);
      grad.addColorStop(0, 'rgba(230,40,190,0.20)');
      grad.addColorStop(1, 'rgba(230,40,190,0)');
      g.fillStyle = grad;
      g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
      continue;
    }

    const col = '#' + e.rgb.toString(16).padStart(6, '0');
    const r = Math.max(3.5, e.size * cam.scale);

    // Carry link under the bodies.
    const carrier = e.attachedTo >= 0 ? state.tracks.get(e.attachedTo) : undefined;
    if (carrier) {
      const cp = state.sample(carrier, renderTime);
      const cs = cam.worldToScreen(cp.x, cp.y);
      g.strokeStyle = 'rgba(0,229,255,0.55)';
      g.lineWidth = Math.max(1, cam.scale * 0.02);
      g.beginPath(); g.moveTo(s.x, s.y); g.lineTo(cs.x, cs.y); g.stroke();
    }

    if (e.kind === 'nest') {
      drawNest(g, s.x, s.y, cam.scale);
      continue;
    }

    if (e.kind.startsWith('item.')) {
      drawItem(g, e.kind, s.x, s.y, r, col);
      continue;
    }

    // Creature.
    if (e.flags & F_DEAD) {
      // A corpse keeps its body and loses its colour. It is not decoration: it
      // lingers for a span set by its mass, it can be scavenged, and it is worth
      // that mass as meat -- so what died, and how big it was, stays readable.
      const dead = atlasFor(e.pheno);
      if (dead) {
        const box = r * 2 * (CELL / (2 * ART_RADIUS));
        const { col: dc, row: dr } = cell(p.dir, nowMs);
        g.imageSmoothingEnabled = false;
        g.drawImage(corpseFor(e.pheno, dead), dc * CELL, dr * CELL, CELL, CELL,
          s.x - box / 2, s.y - box / 2, box, box);
      } else {
        g.fillStyle = '#555a63'; // no sprite yet: a spent dot, no marker over it
        g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
      }
      continue;
    }
    // The real procedural organism, if its atlas has loaded; else a dot with a
    // heading wedge so it still reads while the sprite is in flight.
    const atlas = atlasFor(e.pheno);
    if (atlas) {
      const box = r * 2 * (CELL / (2 * ART_RADIUS)); // scale cell so body ≈ 2r
      const { col: cc, row: rr } = cell(p.dir, nowMs);
      g.imageSmoothingEnabled = false;
      // Minded cohort: a violet rim hugging the body, so a creature driven by an
      // evolvable mind can be picked out of the scripted species at a glance. The
      // ring this replaces was the one smooth curve on a screen of pixel art, and
      // it also had to be told apart from the grab and carry rings by radius alone
      // — a shape that follows the sprite is legible at any zoom and cannot be
      // confused with them. Four offsets rather than eight: at one pixel the
      // diagonals add nothing but cost two more draws per creature.
      if (e.flags & F_MINDED) {
        const rim = rimFor(e.pheno, atlas);
        const d = Math.max(1, box / CELL); // one SPRITE pixel, never under one screen pixel
        for (const [dx, dy] of [[-d, 0], [d, 0], [0, -d], [0, d]] as const) {
          g.drawImage(rim, cc * CELL, rr * CELL, CELL, CELL,
            s.x - box / 2 + dx, s.y - box / 2 + dy, box, box);
        }
      }
      g.drawImage(atlas, cc * CELL, rr * CELL, CELL, CELL, s.x - box / 2, s.y - box / 2, box, box);
    } else {
      g.fillStyle = col;
      g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
      // No sprite to hug yet, so the placeholder wears the rim as its edge.
      g.strokeStyle = (e.flags & F_MINDED) ? RIM_COLOUR : 'rgba(0,0,0,0.45)';
      g.lineWidth = (e.flags & F_MINDED) ? 2 : 1;
      g.stroke();
      g.fillStyle = 'rgba(255,255,255,0.85)';
      g.beginPath();
      g.moveTo(s.x + Math.cos(p.dir) * r * 1.35, s.y + Math.sin(p.dir) * r * 1.35);
      g.lineTo(s.x + Math.cos(p.dir + 2.5) * r * 0.55, s.y + Math.sin(p.dir + 2.5) * r * 0.55);
      g.lineTo(s.x + Math.cos(p.dir - 2.5) * r * 0.55, s.y + Math.sin(p.dir - 2.5) * r * 0.55);
      g.closePath(); g.fill();
    }

    // What it is doing, as a small badge hovering over the body. Only notable
    // acts carry a code, so this stays sparse rather than tagging every creature
    // on screen. Gated on the TRUE on-screen body size rather than `r`, which is
    // floored so distant creatures stay visible as dots — testing `r` would keep
    // drawing unreadable specks at every zoom level, including the fully
    // zoomed-out map view. Badges therefore fade out as you pull back and appear
    // as you zoom in on what a creature is actually doing.
    if (e.size * cam.scale >= GLYPH_MIN_BODY_PX) {
      drawActionGlyph(g, s.x, s.y - r * 2.0, r * 0.95, actionOf(e.flags));
    }

    if (e.flags & F_GRABBED) {
      g.strokeStyle = 'rgba(255,160,60,0.9)';
      g.lineWidth = Math.max(1, r * 0.2);
      g.beginPath(); g.arc(s.x, s.y, r * 1.4, 0, 7); g.stroke();
    } else if (e.flags & F_CARRYING) {
      g.strokeStyle = 'rgba(0,229,255,0.6)';
      g.lineWidth = 1;
      g.beginPath(); g.arc(s.x, s.y, r * 1.3, 0, 7); g.stroke();
    }

    // Follow highlight: a gentle pulsing ring around the tracked creature.
    if (cam.followId === e.id) {
      const pulse = 1.6 + 0.25 * Math.sin(renderTime / 180);
      g.strokeStyle = 'rgba(255,255,255,0.8)';
      g.lineWidth = 2;
      g.beginPath(); g.arc(s.x, s.y, r * pulse + 4, 0, 7); g.stroke();
    }
    // Selection highlight: a steady amber ring on the inspected creature, so it's
    // obvious which one the panel describes (distinct from the follow pulse).
    if (selection.id === e.id) {
      g.strokeStyle = 'rgba(255,214,64,0.95)';
      g.lineWidth = Math.max(2, r * 0.18);
      g.beginPath(); g.arc(s.x, s.y, r * 1.7 + 3, 0, 7); g.stroke();
    }
  }

  // Shrub canopy: thickets (cover tiles) grow foliage that draws OVER the
  // creatures, so anything standing in cover is partly hidden — matching the
  // fact that cover blocks line of sight. The clumps are translucent and leave
  // gaps, so you can still make out what's underneath.
  if (cover) drawCanopy(g, cam, meta, cover);
}

// Stable per-tile pseudo-random in [0,1): same tile+index always yields the
// same value, so the foliage doesn't shimmer between frames.
function shrubRand(x: number, y: number, i: number): number {
  let h = (Math.imul(x, 374761393) + Math.imul(y, 668265263) + Math.imul(i, 2246822519)) >>> 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

// Draws a bush canopy over every visible cover tile: a deterministic cluster of
// translucent green blobs per tile, spilling slightly across tile edges so
// adjacent thickets merge into a continuous stand.
function drawCanopy(g: CanvasRenderingContext2D, cam: Camera, meta: WorldMeta, cover: Uint8Array): void {
  const cv = g.canvas;
  const tl = cam.screenToWorld(0, 0);
  const br = cam.screenToWorld(cv.width, cv.height);
  const x0 = Math.max(0, Math.floor(tl.x)), y0 = Math.max(0, Math.floor(tl.y));
  const x1 = Math.min(meta.cols - 1, Math.ceil(br.x)), y1 = Math.min(meta.rows - 1, Math.ceil(br.y));
  const s = cam.scale;
  // Translucent tones, kept light enough that a creature under the canopy still
  // reads through the gaps and the foliage itself.
  const tones = ['rgba(44,84,34,0.42)', 'rgba(32,63,25,0.46)', 'rgba(74,120,52,0.38)'];
  for (let ty = y0; ty <= y1; ty++) {
    for (let tx = x0; tx <= x1; tx++) {
      const cv2 = cover[ty * meta.cols + tx];
      if (cv2 === 2) {
        // A crawl duct: its concealment is a metal lid, not shrubbery —
        // translucent slats with a dark seam, so a crawler underneath still
        // half-reads through the gaps.
        const o = cam.worldToScreen(tx, ty);
        g.fillStyle = 'rgba(97,105,116,0.55)';
        for (let i = 0; i < 3; i++) {
          g.fillRect(o.x + s * 0.06, o.y + s * (0.10 + i * 0.30), s * 0.88, s * 0.20);
        }
        g.fillStyle = 'rgba(20,22,26,0.5)';
        for (let i = 0; i < 2; i++) {
          g.fillRect(o.x + s * 0.06, o.y + s * (0.30 + i * 0.30), s * 0.88, s * 0.04);
        }
        continue;
      }
      if (cv2 !== 1) continue;
      const o = cam.worldToScreen(tx, ty);
      for (let i = 0; i < 5; i++) {
        const bx = o.x + (0.15 + shrubRand(tx, ty, i * 3) * 0.70) * s;
        const by = o.y + (0.15 + shrubRand(tx, ty, i * 3 + 1) * 0.70) * s;
        const rad = (0.16 + shrubRand(tx, ty, i * 3 + 2) * 0.16) * s;
        g.fillStyle = tones[i % tones.length];
        g.beginPath(); g.arc(bx, by, rad, 0, 7); g.fill();
      }
    }
  }
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

/** A nest: a woven twig ring with a shaded hollow — a brood site made solid.
 *  Drawn under the bodies, so a brooding nester stands IN its nest. */
export function drawNest(g: CanvasRenderingContext2D, x: number, y: number, sc: number): void {
  const r = Math.max(4, sc * 0.3);
  g.fillStyle = 'rgba(20,14,8,0.35)';
  g.beginPath(); g.arc(x, y, r * 0.85, 0, 7); g.fill();
  g.strokeStyle = '#574024';
  g.lineWidth = Math.max(2, sc * 0.11);
  g.beginPath(); g.arc(x, y, r, 0, 7); g.stroke();
  g.strokeStyle = '#8a6a3c'; // straw wisps woven through the rim
  g.lineWidth = Math.max(1, sc * 0.045);
  g.setLineDash([sc * 0.09, sc * 0.07]);
  g.beginPath(); g.arc(x, y, r, 0, 7); g.stroke();
  g.setLineDash([]);
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
function drawActionGlyph(g: CanvasRenderingContext2D, cx: number, cy: number,
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
