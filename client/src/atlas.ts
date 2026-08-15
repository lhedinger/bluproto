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

const cache = new Map<number, HTMLCanvasElement | null>(); // null = loading

/** The loaded atlas for a key, or null while it loads / on failure. Cached as
 *  a CANVAS copy, not the <img>: canvases blit orders of magnitude faster on
 *  software-rendered canvases (an <img> source can pay a format conversion on
 *  every draw), and creatures are stamped hundreds of times a frame. */
export function atlasFor(pheno: number): HTMLCanvasElement | null {
  if (pheno === 0) return null;
  const hit = cache.get(pheno);
  if (hit !== undefined) return hit;
  cache.set(pheno, null); // mark in-flight so we fetch once
  const img = new Image();
  img.onload = () => {
    const cv = document.createElement('canvas');
    cv.width = img.naturalWidth;
    cv.height = img.naturalHeight;
    cv.getContext('2d')!.drawImage(img, 0, 0);
    cache.set(pheno, cv);
  };
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

export function rimFor(pheno: number, img: HTMLCanvasElement): HTMLCanvasElement {
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

/**
 * Far-zoom mips: quarter-size copies of the atlas variants, smoothed ONCE at
 * bake time (like the depletion layer's mip). When the whole map is in view a
 * creature is ~a dozen pixels tall; nearest-sampling that out of a 96-px cell
 * every frame both wastes memory bandwidth and shimmers (it skips source
 * pixels), while a pre-smoothed small cell resolves to stable coverage.
 */
export const MIP = 4; // mip cells are CELL / MIP px

function downscale(src: HTMLCanvasElement): HTMLCanvasElement {
  const cv = document.createElement('canvas');
  cv.width = src.width / MIP;
  cv.height = src.height / MIP;
  const g = cv.getContext('2d')!;
  g.imageSmoothingEnabled = true; // resolve to coverage once, here, never per frame
  g.drawImage(src, 0, 0, cv.width, cv.height);
  return cv;
}

const mipCache = new Map<number, HTMLCanvasElement>();

export function atlasMipFor(pheno: number, atlas: HTMLCanvasElement): HTMLCanvasElement {
  let m = mipCache.get(pheno);
  if (!m) { m = downscale(atlas); mipCache.set(pheno, m); }
  return m;
}

const corpseMipCache = new Map<number, HTMLCanvasElement>();

export function corpseMipFor(pheno: number, atlas: HTMLCanvasElement): HTMLCanvasElement {
  let m = corpseMipCache.get(pheno);
  if (!m) { m = downscale(corpseFor(pheno, atlas)); corpseMipCache.set(pheno, m); }
  return m;
}

/**
 * The far-zoom minded variant: rim and body FUSED into one sprite, so a minded
 * creature at fit zoom costs one drawImage instead of five. Baked at mip
 * resolution — the rim is one mip-pixel wide, which at map-overview sizes is
 * exactly what the live four-offset path resolves to anyway. Near zoom keeps
 * the live path: there the rim must hug the body at one SCREEN pixel, which no
 * baked asset can promise at every scale.
 */
const mindedMipCache = new Map<number, HTMLCanvasElement>();

export function mindedMipFor(pheno: number, atlas: HTMLCanvasElement): HTMLCanvasElement {
  let m = mindedMipCache.get(pheno);
  if (m) return m;
  const body = atlasMipFor(pheno, atlas);
  const rim = document.createElement('canvas');
  rim.width = body.width;
  rim.height = body.height;
  const rg = rim.getContext('2d')!;
  rg.drawImage(body, 0, 0);
  rg.globalCompositeOperation = 'source-in'; // the mip's silhouette in rim violet
  rg.fillStyle = RIM_COLOUR;
  rg.fillRect(0, 0, rim.width, rim.height);
  m = document.createElement('canvas');
  m.width = body.width;
  m.height = body.height;
  const g = m.getContext('2d')!;
  for (const [dx, dy] of [[-1, 0], [1, 0], [0, -1], [0, 1]] as const) {
    g.drawImage(rim, dx, dy);
  }
  g.drawImage(body, 0, 0);
  mindedMipCache.set(pheno, m);
  return m;
}

/**
 * A drained, greyed copy of an atlas — what a corpse looks like.
 *
 * <p>The body keeps its shape, size and pose; only its colour goes. That matters
 * more here than a generic death marker would, because a corpse is not decoration:
 * it lingers, it can be scavenged, and it is worth its mass as meat, so "what died
 * and how big was it" is information worth keeping legible. The X-through-a-circle
 * this replaces threw all of it away and drew smooth geometry over pixel art.
 *
 * <p>Two passes: `saturation` against a flat grey strips the colour, then a
 * low-alpha black `source-atop` drains what is left so it reads as spent rather
 * than merely colourless. Baked once per phenotype, like the rim.
 */
const corpseCache = new Map<number, HTMLCanvasElement>();

export function corpseFor(pheno: number, img: HTMLCanvasElement): HTMLCanvasElement {
  const hit = corpseCache.get(pheno);
  if (hit) return hit;
  const cv = document.createElement('canvas');
  cv.width = img.width;
  cv.height = img.height;
  const g = cv.getContext('2d')!;
  g.imageSmoothingEnabled = false;
  g.drawImage(img, 0, 0);
  g.globalCompositeOperation = 'saturation'; // colour out, luminance kept
  g.fillStyle = '#808080';
  g.fillRect(0, 0, cv.width, cv.height);
  // 'saturation' is a blend mode, not a clip: it composites source-over, so the
  // grey fill lands opaque wherever the atlas was transparent. Re-clip to the
  // sprite's silhouette before darkening, or every corpse stamps a grey square.
  g.globalCompositeOperation = 'destination-in';
  g.drawImage(img, 0, 0);
  g.globalCompositeOperation = 'source-atop'; // ...and darken only the body
  g.fillStyle = 'rgba(10,12,15,0.45)';
  g.fillRect(0, 0, cv.width, cv.height);
  corpseCache.set(pheno, cv);
  return cv;
}
