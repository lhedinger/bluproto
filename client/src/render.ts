// Draws the world: the baked ground layer under interpolated entities. True
// body radii (fractions of a tile) rule when zoomed in; a small readable floor
// keeps distant creatures visible when zoomed out. Pixel-art stays crisp via
// nearest-neighbour scaling of the layer.

import type { Camera } from './camera';
import { F_CARRYING, F_DEAD, F_GRABBED } from './protocol';
import type { Track, WorldState } from './state';

export interface WorldMeta { cols: number; rows: number; }

export function render(
  g: CanvasRenderingContext2D,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta | null,
  layer: HTMLImageElement | null,
  renderTime: number,
): void {
  const cv = g.canvas;
  g.fillStyle = '#14161a';
  g.fillRect(0, 0, cv.width, cv.height);
  if (!meta) return;

  // Ground: one drawImage of the baked layer, nearest-neighbour so zooming in
  // shows fat crisp pixels instead of blur.
  if (layer && layer.complete && layer.naturalWidth) {
    const o = cam.worldToScreen(0, 0);
    g.imageSmoothingEnabled = false;
    g.drawImage(layer, o.x, o.y, meta.cols * cam.scale, meta.rows * cam.scale);
  }

  // Painter's order: haze, then dead, then items, then living creatures.
  const order = (t: Track): number =>
    t.curr.kind === 'phero' ? 0 : (t.curr.flags & F_DEAD) ? 1 : t.curr.kind.startsWith('item.') ? 2 : 3;
  const tracks = [...state.tracks.values()].sort((a, b) => order(a) - order(b));

  for (const t of tracks) {
    const e = t.curr;
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
    g.fillStyle = col;
    g.beginPath(); g.arc(s.x, s.y, r, 0, 7); g.fill();
    g.strokeStyle = 'rgba(0,0,0,0.45)';
    g.lineWidth = 1;
    g.stroke();
    // Heading wedge: a nose so orientation reads at any zoom.
    g.fillStyle = 'rgba(255,255,255,0.85)';
    g.beginPath();
    g.moveTo(s.x + Math.cos(p.dir) * r * 1.35, s.y + Math.sin(p.dir) * r * 1.35);
    g.lineTo(s.x + Math.cos(p.dir + 2.5) * r * 0.55, s.y + Math.sin(p.dir + 2.5) * r * 0.55);
    g.lineTo(s.x + Math.cos(p.dir - 2.5) * r * 0.55, s.y + Math.sin(p.dir - 2.5) * r * 0.55);
    g.closePath(); g.fill();
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
