// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import { ART_RADIUS, CELL, atlasFor, cell } from './atlas';
import type { Camera } from './camera';
import { F_CARRYING, F_DEAD, F_GRABBED } from './protocol';
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

  // Painter's order: haze, then dead, then items, then living creatures.
  const order = (t: Track): number =>
    t.curr.kind === 'phero' ? 0 : (t.curr.flags & F_DEAD) ? 1 : t.curr.kind.startsWith('item.') ? 2 : 3;
  const tracks = [...state.tracks.values()].sort((a, b) => order(a) - order(b));

  for (const t of tracks) {
    const e = t.curr;
    if (Math.round(e.z) !== level) continue; // only entities on the shown level
    const p = state.sample(t, renderTime);
    const s = cam.worldToScreen(p.x, p.y);
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
