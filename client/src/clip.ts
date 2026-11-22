// Where a full-screen pass matters. A GPU pass that draws only over part of
// the world (the water, the canopy) still costs a whole screen of fragments
// if it is drawn full-screen and discards the rest; on a phone, and on any
// software GL, that is the frame. These give the pass the screen rects of the
// chunks that hold anything, for GLRenderer.fullscreen's scissor clip.

/** Chunk edge, in tiles: small enough that a river's boxes hug it, large
 *  enough that a lake is a handful of rects rather than hundreds. */
export const CLIP_CHUNK = 16;

const cache = new WeakMap<Uint8Array, { chunk: number; grow: number; boxes: number[] }>();

/**
 * Tile boxes [x0, y0, x1, y1), one per chunk where `grid` (one byte per tile)
 * is non-zero, grown `grow` tiles each way. Cached by the grid's identity:
 * callers replace a grid when it changes rather than write into it.
 */
export function chunkBoxes(grid: Uint8Array, cols: number, rows: number,
    chunk = CLIP_CHUNK, grow = 1): number[] {
  const hit = cache.get(grid);
  if (hit && hit.chunk === chunk && hit.grow === grow) return hit.boxes;
  const boxes: number[] = [];
  for (let cy = 0; cy < rows; cy += chunk) {
    for (let cx = 0; cx < cols; cx += chunk) {
      let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1;
      const ex = Math.min(cols, cx + chunk), ey = Math.min(rows, cy + chunk);
      for (let y = cy; y < ey; y++) {
        const row = y * cols;
        for (let x = cx; x < ex; x++) {
          if (!grid[row + x]) continue;
          if (x < x0) x0 = x;
          if (x > x1) x1 = x;
          if (y < y0) y0 = y;
          y1 = y;
        }
      }
      if (x1 < 0) continue;
      boxes.push(Math.max(0, x0 - grow), Math.max(0, y0 - grow),
        Math.min(cols, x1 + 1 + grow), Math.min(rows, y1 + 1 + grow));
    }
  }
  cache.set(grid, { chunk, grow, boxes });
  return boxes;
}

/**
 * The boxes on screen as rects (x, y, w, h, top-left origin) for a world
 * drawn from origin (ox, oy) at `scale` px per tile, into `out` (cleared
 * first). Off-screen boxes are left out, so an empty result means the pass
 * has nothing to draw. Past `max` rects (zoomed out, every chunk is on
 * screen) each is a draw call for little saved, so they become the one rect
 * around them all.
 */
export function screenRects(boxes: number[], ox: number, oy: number, scale: number,
    vw: number, vh: number, out: number[], max = 12): number[] {
  out.length = 0;
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (let i = 0; i < boxes.length; i += 4) {
    const x = ox + boxes[i] * scale, y = oy + boxes[i + 1] * scale;
    const w = (boxes[i + 2] - boxes[i]) * scale, h = (boxes[i + 3] - boxes[i + 1]) * scale;
    if (x > vw || y > vh || x + w < 0 || y + h < 0) continue;
    out.push(x, y, w, h);
    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x + w); y1 = Math.max(y1, y + h);
  }
  if (out.length > max * 4) { out.length = 0; out.push(x0, y0, x1 - x0, y1 - y0); }
  return out;
}
