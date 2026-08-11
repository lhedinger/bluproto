// Lazy cache of server-baked creature sprite atlases (see server/AtlasBaker).
// Each phenotype key names one PNG laid out DIRS columns (heading) × ANIM rows
// (idle frames); the renderer picks a cell by heading and a free-running clock.
// A key is fetched once on first sighting; until it arrives the renderer falls
// back to a coloured dot, so nothing blocks on the network.

// Must match server/AtlasBaker: CELL px cells, DIRS×ANIM grid, art drawn at
// ART_RADIUS within each cell (so the client can scale a cell to make the
// on-screen body match the entity's true radius).
export const CELL = 96;
export const DIRS = 8;
export const ANIM = 8;
export const ART_RADIUS = 0.22 * CELL;

const cache = new Map<number, HTMLImageElement | null>(); // null = loading

/** The loaded atlas image for a key, or null while it loads / on failure. */
export function atlasFor(pheno: number): HTMLImageElement | null {
  if (pheno === 0) return null;
  const hit = cache.get(pheno);
  if (hit !== undefined) return hit;
  cache.set(pheno, null); // mark in-flight so we fetch once
  const img = new Image();
  img.onload = () => cache.set(pheno, img);
  img.onerror = () => cache.set(pheno, null);
  img.src = `/api/world/atlas/${pheno}.png`;
  return null;
}

/** Column (heading bucket) and row (animation frame) for the given pose. */
export function cell(dir: number, timeMs: number): { col: number; row: number } {
  const col = ((Math.round(dir / (Math.PI * 2 / DIRS)) % DIRS) + DIRS) % DIRS;
  const row = Math.floor((timeMs / 90) % ANIM); // ~90 ms/frame gentle shuffle
  return { col, row };
}

/**
 * A violet silhouette of an atlas, cached alongside it.
 *
 * <p>Drawing this four times at one-pixel offsets behind the real sprite gives a
 * rim that hugs the body's actual outline — the standard pixel-art dilation. It
 * has to be a silhouette rather than a tint, because what we want is the SHAPE
 * of the sprite in one flat colour, and `source-in` over a filled rectangle is
 * the cheapest way to get exactly that.
 *
 * <p>Baked once per phenotype and kept, since an atlas never changes: the cost is
 * one offscreen canvas per distinct creature design, not per creature or frame.
 */
const rimCache = new Map<number, HTMLCanvasElement>();

export function rimFor(pheno: number, img: HTMLImageElement): HTMLCanvasElement {
  const hit = rimCache.get(pheno);
  if (hit) return hit;
  const cv = document.createElement('canvas');
  cv.width = img.width;
  cv.height = img.height;
  const g = cv.getContext('2d')!;
  g.imageSmoothingEnabled = false;
  g.drawImage(img, 0, 0);
  g.globalCompositeOperation = 'source-in'; // keep the sprite's alpha, replace its colour
  g.fillStyle = RIM_COLOUR;
  g.fillRect(0, 0, cv.width, cv.height);
  rimCache.set(pheno, cv);
  return cv;
}

/** The minded rim's colour — the same violet the ring used, so nothing else in the
 *  palette has to move. */
export const RIM_COLOUR = '#c660ff';
