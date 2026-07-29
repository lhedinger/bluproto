// A corner overview: the whole world scaled into a small canvas, every entity a
// pixel, plus a rectangle for the current viewport. Tapping it recentres the
// camera. Cheap — the client already holds all entity positions.

import type { Camera } from './camera';
import { F_DEAD } from './protocol';
import type { WorldState } from './state';
import type { WorldMeta } from './render';

export function drawMinimap(
  mm: HTMLCanvasElement,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta,
  main: HTMLCanvasElement,
  level = 0,
): void {
  // Keep the minimap's aspect matched to the world.
  const targetH = Math.round((mm.width * meta.rows) / meta.cols);
  if (mm.height !== targetH) mm.height = targetH;
  const g = mm.getContext('2d')!;
  const sx = mm.width / meta.cols, sy = mm.height / meta.rows;
  g.clearRect(0, 0, mm.width, mm.height);
  g.fillStyle = '#1a3a1e';
  g.fillRect(0, 0, mm.width, mm.height);

  for (const t of state.tracks.values()) {
    const e = t.curr;
    if (e.kind === 'phero') continue;
    if (Math.round(e.z) !== level) continue; // mirror the main view's level
    g.fillStyle = (e.flags & F_DEAD) ? '#5a5f66'
      : e.kind.startsWith('item.') ? '#d0c090'
      : '#' + e.rgb.toString(16).padStart(6, '0');
    g.fillRect(e.x * sx - 1, e.y * sy - 1, 2, 2);
  }

  // Viewport rectangle: the world span currently on the main canvas.
  const tl = cam.screenToWorld(0, 0);
  const br = cam.screenToWorld(main.width, main.height);
  g.strokeStyle = '#ffffffcc';
  g.lineWidth = 1;
  g.strokeRect(tl.x * sx, tl.y * sy, (br.x - tl.x) * sx, (br.y - tl.y) * sy);
}

/** World coords for a click at (px,py) CSS-pixels within the minimap. */
export function minimapToWorld(mm: HTMLCanvasElement, meta: WorldMeta, px: number, py: number):
    { x: number; y: number } {
  const rect = mm.getBoundingClientRect();
  return {
    x: ((px - rect.left) / rect.width) * meta.cols,
    y: ((py - rect.top) / rect.height) * meta.rows,
  };
}
