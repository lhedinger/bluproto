// A corner overview: the whole world scaled into a small canvas, every entity a
// pixel, plus a rectangle for the current viewport. Tapping it recentres the
// camera. Cheap — the client already holds all entity positions.

import type { Camera } from './camera';
import { F_DEAD } from './protocol';
import type { WorldState } from './state';
import { CORPSE_RGB, hex } from './artCore';
import type { WorldMeta } from './render';

/** The minimap's world -- the ground and a dot per body -- as last drawn, so a
 *  camera move redraws only the viewport rectangle. During a pan every frame
 *  is a camera move, and every frame used to repaint all ~1,300 dots, each
 *  with a freshly built colour string, for a picture that moves at 4 Hz. */
let dotsCv: HTMLCanvasElement | null = null;

export function drawMinimap(
  mm: HTMLCanvasElement,
  cam: Camera,
  state: WorldState,
  meta: WorldMeta,
  main: HTMLCanvasElement,
  level = 0,
  redrawDots = true,
): void {
  // Keep the minimap's aspect matched to the world.
  const targetH = Math.round((mm.width * meta.rows) / meta.cols);
  if (mm.height !== targetH) mm.height = targetH;
  const sx = mm.width / meta.cols, sy = mm.height / meta.rows;
  if (!dotsCv || dotsCv.width !== mm.width || dotsCv.height !== mm.height) {
    dotsCv = document.createElement('canvas');
    dotsCv.width = mm.width;
    dotsCv.height = mm.height;
    redrawDots = true;
  }
  if (redrawDots) {
    const d = dotsCv.getContext('2d')!;
    d.fillStyle = '#1a3a1e';
    d.fillRect(0, 0, dotsCv.width, dotsCv.height);
    for (const t of state.tracks.values()) {
      const e = t.curr;
      if (e.kind === 'phero') continue;
      if (Math.round(e.z) !== level) continue; // mirror the main view's level
      d.fillStyle = (e.flags & F_DEAD) ? hex(CORPSE_RGB)
        : e.kind.startsWith('item.') ? '#d0c090'
        : '#' + e.rgb.toString(16).padStart(6, '0');
      d.fillRect(e.x * sx - 1, e.y * sy - 1, 2, 2);
    }
  }
  const g = mm.getContext('2d')!;
  g.drawImage(dotsCv, 0, 0);

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
