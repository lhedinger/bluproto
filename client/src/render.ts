// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import { ART_RADIUS, CELL, atlasFor, cell } from './atlas';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_FLEE, ACT_GRAB, ACT_GRAZE, ACT_HUNT, ACT_MATE,
  ACT_NEST, actionOf, F_CARRYING, F_DEAD, F_GRABBED, F_MINDED,
} from './protocol';
import type { EntityState } from './protocol';
import type { Track, WorldState } from './state';

export interface WorldMeta { cols: number; rows: number; }

export function render(
  g: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  chunkTiles: number,
  getChunk: (cx: number, cy: number) => HTMLImageElement,
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

  // Live grazing: darken depleted grass toward bare dirt (255 = non-grass, no
  // overlay; 100 = lush, none; 0 = grazed bare, full dirt). Regrowth fades it.
  if (veg) {
    const tl = cam.screenToWorld(0, 0);
    const br = cam.screenToWorld(cv.width, cv.height);
    const x0 = Math.max(0, Math.floor(tl.x)), y0 = Math.max(0, Math.floor(tl.y));
    const x1 = Math.min(meta.cols - 1, Math.ceil(br.x)), y1 = Math.min(meta.rows - 1, Math.ceil(br.y));
    for (let ty = y0; ty <= y1; ty++) {
      for (let tx = x0; tx <= x1; tx++) {
        const lvl = veg[ty * meta.cols + tx];
        if (lvl >= 100 || lvl === 255) continue; // lush grass or non-grass: nothing
        const o = cam.worldToScreen(tx, ty);
        g.fillStyle = `rgba(78,60,38,${(((100 - lvl) / 100) * 0.72).toFixed(3)})`;
        g.fillRect(o.x, o.y, cam.scale + 1, cam.scale + 1);
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
      : t.curr.kind === 'switch' ? 1 // wiring lowest: door leaves slide over it
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
    // A switch's conduit reaches its wired door, so cull loosely too.
    if (e.kind === 'switch') {
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

    if (e.kind.startsWith('item.')) {
      drawItem(g, e.kind, s.x, s.y, r, col);
      continue;
    }

    // Creature.
    if (e.flags & F_DEAD) {
      g.fillStyle = '#555a63';
      g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
      g.strokeStyle = '#14161a';
      g.lineWidth = Math.max(1, r * 0.25);
      g.beginPath();
      g.moveTo(s.x - r * 0.5, s.y - r * 0.5); g.lineTo(s.x + r * 0.5, s.y + r * 0.5);
      g.moveTo(s.x + r * 0.5, s.y - r * 0.5); g.lineTo(s.x - r * 0.5, s.y + r * 0.5);
      g.stroke();
      continue;
    }
    // The real procedural organism, if its atlas has loaded; else a dot with a
    // heading wedge so it still reads while the sprite is in flight.
    const atlas = atlasFor(e.pheno);
    if (atlas) {
      const box = r * 2 * (CELL / (2 * ART_RADIUS)); // scale cell so body ≈ 2r
      const { col: cc, row: rr } = cell(p.dir, nowMs);
      g.imageSmoothingEnabled = false;
      g.drawImage(atlas, cc * CELL, rr * CELL, CELL, CELL, s.x - box / 2, s.y - box / 2, box, box);
    } else {
      g.fillStyle = col;
      g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
      g.strokeStyle = 'rgba(0,0,0,0.45)';
      g.lineWidth = 1;
      g.stroke();
      g.fillStyle = 'rgba(255,255,255,0.85)';
      g.beginPath();
      g.moveTo(s.x + Math.cos(p.dir) * r * 1.35, s.y + Math.sin(p.dir) * r * 1.35);
      g.lineTo(s.x + Math.cos(p.dir + 2.5) * r * 0.55, s.y + Math.sin(p.dir + 2.5) * r * 0.55);
      g.lineTo(s.x + Math.cos(p.dir - 2.5) * r * 0.55, s.y + Math.sin(p.dir - 2.5) * r * 0.55);
      g.closePath(); g.fill();
    }
    // Minded cohort: a crisp violet ring marks a creature whose behaviour comes
    // from an evolvable mind, so it can be watched competing against the scripted
    // species. Distinct from the grab (orange) and carry (cyan) rings.
    if (e.flags & F_MINDED) {
      g.strokeStyle = 'rgba(198,96,255,0.95)';
      g.lineWidth = Math.max(1, r * 0.22);
      g.beginPath(); g.arc(s.x, s.y, r * 1.25, 0, 7); g.stroke();
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
      if (cover[ty * meta.cols + tx] !== 1) continue;
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
 * A pressure-plate switch: the conduit decal from the plate to its wired
 * door (an L-run of dark cable with lit staples, x-leg first — matching the
 * Swing renderer), then the button: a lit steel dome when armed, pressed
 * flush with an amber live-ring while a body weights it (aux = 1). The
 * plate's housing is baked into the ground tile; only the live parts draw
 * here. `door` is the wired door's track, looked up by the id the wire
 * carries in `pheno`; without it (not yet streamed) only the button draws.
 */
function drawSwitch(
  g: CanvasRenderingContext2D, cam: Camera, e: EntityState, door?: EntityState,
): void {
  const sc = cam.scale;
  const cx = e.x + 0.5, cy = e.y + 0.5;
  if (door) {
    const lr = Math.abs(door.dir % (Math.PI / 2)) > 1e-6;
    const half = Math.max(1, door.size) / 2;
    const dx = lr ? door.x : door.x + half;
    const dy = lr ? door.y + half : door.y;
    const w = Math.max(1, sc * 0.05);
    const a = cam.worldToScreen(cx, cy);
    const k = cam.worldToScreen(dx, cy); // the L's corner: x-leg first
    const b = cam.worldToScreen(dx, dy);
    g.strokeStyle = '#14161f';
    g.lineWidth = w;
    g.beginPath(); g.moveTo(a.x, a.y); g.lineTo(k.x, k.y); g.lineTo(b.x, b.y); g.stroke();
    // Staples along the run, so it reads as fixed conduit rather than string.
    g.fillStyle = '#515862';
    const legs: Array<[number, number, number, number]> = [[a.x, a.y, k.x, k.y], [k.x, k.y, b.x, b.y]];
    for (const [x0, y0, x1, y1] of legs) {
      const len = Math.hypot(x1 - x0, y1 - y0);
      const n = Math.floor(len / (sc * 0.45));
      for (let i = 1; i <= n; i++) {
        const t = i / (n + 1);
        const sx = x0 + (x1 - x0) * t, sy = y0 + (y1 - y0) * t;
        g.fillRect(sx - w, sy - w, w * 2, w * 2);
      }
    }
  }
  // The button.
  const o = cam.worldToScreen(cx, cy);
  const r = Math.max(2.5, sc * 0.16);
  if (e.aux >= 0.5) {
    g.strokeStyle = '#d8b028';
    g.lineWidth = Math.max(1, r * 0.4);
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.stroke();
    g.fillStyle = '#23262e';
    g.beginPath(); g.arc(o.x, o.y, r * 0.7, 0, 7); g.fill();
  } else {
    g.fillStyle = '#8a93a0';
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.fill();
    g.fillStyle = '#c6cdd8';
    g.beginPath(); g.arc(o.x - r * 0.25, o.y - r * 0.3, r * 0.4, 0, 7); g.fill();
    g.strokeStyle = '#23262e';
    g.lineWidth = 1;
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.stroke();
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
function drawDoor(g: CanvasRenderingContext2D, cam: Camera, e: EntityState): void {
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

function drawItem(g: CanvasRenderingContext2D, kind: string, x: number, y: number, r: number, col: string): void {
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
