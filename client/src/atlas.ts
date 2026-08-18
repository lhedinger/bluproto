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

/** The classic 4x4 ordered-dither matrix (row-major), shared threshold table
 *  for pixel-art style partial coverage — the ground bake's dithers, the
 *  depletion masks and the corpse dissolve all read from this one table so
 *  every partial coverage in the world erodes on the same grid. */
export const BAYER4 = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5];

const cache = new Map<number, HTMLCanvasElement | null>(); // null = loading

// Per-phenotype LRU over EVERY CPU-side variant cache below. A long-evolved
// world breeds a new phenotype with nearly every lineage; each one resident
// costs ~2.4MB for the atlas canvas alone (768px RGBA) and several times that
// once rim/minded/corpse variants bake. Left uncapped this grew to gigabytes
// of canvases on old worlds and the browser spent the frame budget in GC and
// paging stalls, not rendering. Eviction is by idle time with a floor, never
// touching a phenotype drawn in the last few seconds — the on-screen set must
// stay resident even when it exceeds the cap (see gl.ts for the same rule on
// GPU textures). A re-sighted evicted phenotype refetches through the
// browser's HTTP cache and wears the dot placeholder for a beat.
const touched = new Map<number, number>(); // pheno -> last sprite-path use, ms
const PHENO_CAP = 64;
const EVICT_IDLE_MS = 5000;

function dropPheno(p: number): void {
  cache.delete(p);
  touched.delete(p);
  mipCache.delete(p);
  mindedCache.delete(p);
  mindedMipCache.delete(p);
  for (let s = 0; s < DECAY_STEPS; s++) {
    corpseCache.delete(p * DECAY_STEPS + s);
    corpseMipCache.delete(p * DECAY_STEPS + s);
  }
}

function evictStale(now: number): void {
  if (cache.size <= PHENO_CAP) return;
  const order = [...touched.entries()].sort((a, b) => a[1] - b[1]);
  for (const [p, at] of order) {
    if (cache.size <= PHENO_CAP || now - at < EVICT_IDLE_MS) break;
    dropPheno(p);
  }
}

/** The loaded atlas for a key, or null while it loads / on failure. Cached as
 *  a CANVAS copy, not the <img>: canvases blit orders of magnitude faster on
 *  software-rendered canvases (an <img> source can pay a format conversion on
 *  every draw), and creatures are stamped hundreds of times a frame. */
export function atlasFor(pheno: number): HTMLCanvasElement | null {
  if (pheno === 0) return null;
  const now = performance.now();
  touched.set(pheno, now);
  const hit = cache.get(pheno);
  if (hit !== undefined) return hit;
  evictStale(now); // a new resident is what grows the pool past its cap
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

/** Distinct atlases this session has seen — on a long-evolved world this is
 *  the phenotype count, and each one is a 768px image in memory. */
export function atlasCount(): number {
  return cache.size;
}

/** Column (heading bucket) and row (animation frame) for the given pose.
 *  `phase` (any integer, e.g. the entity id) offsets the animation clock so
 *  a herd doesn't switch pose in lockstep — a synchronized 90 ms step across
 *  every creature on screen reads as a screen-wide flicker, not motion. */
export function cell(dir: number, timeMs: number, phase = 0): { col: number; row: number } {
  const col = ((Math.round(dir / (Math.PI * 2 / DIRS)) % DIRS) + DIRS) % DIRS;
  // Double-mod: entity ids (the phase) can be NEGATIVE, and a negative row
  // samples outside the atlas — an invisible body on 7 of 8 frames.
  const row = ((Math.floor(timeMs / 90 + phase) % ANIM) + ANIM) % ANIM;
  return { col, row };
}

/** How far past its own bucket's edge a heading must swing before the sprite
 *  turns to the neighbouring column (10°). Neighbouring heading cells are
 *  procedurally quite different silhouettes, so a walker whose heading noise
 *  straddles a bucket boundary would otherwise flip pose at frame rate —
 *  which reads as flicker, not turning. */
const HEADING_HYST = Math.PI / 18;

/** The heading column with hysteresis: keeps `prev` (pass -1 for none) until
 *  the heading has moved clearly INTO a neighbouring bucket. */
export function headingCol(dir: number, prev: number): number {
  const bucket = (Math.PI * 2) / DIRS;
  if (prev >= 0) {
    let d = (dir - prev * bucket) % (Math.PI * 2);
    if (d > Math.PI) d -= Math.PI * 2;
    if (d < -Math.PI) d += Math.PI * 2;
    if (Math.abs(d) <= bucket / 2 + HEADING_HYST) return prev;
  }
  return ((Math.round(dir / bucket) % DIRS) + DIRS) % DIRS;
}

/**
 * A violet silhouette of an atlas — the SHAPE of the sprite in one flat
 * colour, via `source-in` over a filled rectangle. Drawing it at one-pixel
 * offsets behind the real sprite gives a rim that hugs the body's actual
 * outline — the standard pixel-art dilation. Built transiently: its only
 * consumer is the fused minded bake below, so caching it was 2.4MB per
 * minded phenotype held for nothing.
 */
function rimOf(img: HTMLCanvasElement): HTMLCanvasElement {
  const cv = document.createElement('canvas');
  cv.width = img.width;
  cv.height = img.height;
  const g = cv.getContext('2d')!;
  g.imageSmoothingEnabled = false;
  g.drawImage(img, 0, 0);
  g.globalCompositeOperation = 'source-in'; // keep the sprite's alpha, replace its colour
  g.fillStyle = RIM_COLOUR;
  g.fillRect(0, 0, cv.width, cv.height);
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

// Keyed by (phenotype, decay stage) like the full-size corpse cache: a rotting
// body has to erode at far zoom too, or a corpse would decay while you watched
// it up close and sit there intact the moment you zoomed out.
const corpseMipCache = new Map<number, HTMLCanvasElement>();

export function corpseMipFor(pheno: number, atlas: HTMLCanvasElement, decay = 0): HTMLCanvasElement {
  const key = pheno * DECAY_STEPS + decayStage(decay);
  let m = corpseMipCache.get(key);
  if (!m) { m = downscale(corpseFor(pheno, atlas, decay)); corpseMipCache.set(key, m); }
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
 * The full-resolution minded variant: rim and body FUSED into one sprite, so
 * a minded creature costs one drawImage at sprite zoom instead of five (four
 * rim offsets plus the body). The live four-offset path drew the rim at one
 * SCREEN pixel; a baked rim is one ATLAS pixel, which nearest-downsamples to
 * dots below half scale — so the baked rim is dilated to TWO atlas pixels,
 * surviving down to the mip handoff (box = CELL/MIP) while reading at most a
 * couple of pixels thick near 1:1. Baked once per phenotype.
 */
const mindedCache = new Map<number, HTMLCanvasElement>();

export function mindedFor(pheno: number, atlas: HTMLCanvasElement): HTMLCanvasElement {
  let m = mindedCache.get(pheno);
  if (m) return m;
  const rim = rimOf(atlas);
  m = document.createElement('canvas');
  m.width = atlas.width;
  m.height = atlas.height;
  const g = m.getContext('2d')!;
  for (const [dx, dy] of [
    [-1, 0], [1, 0], [0, -1], [0, 1], [-1, -1], [1, -1], [-1, 1], [1, 1],
    [-2, 0], [2, 0], [0, -2], [0, 2],
  ] as const) {
    g.drawImage(rim, dx, dy);
  }
  g.drawImage(atlas, 0, 0);
  mindedCache.set(pheno, m);
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
 * than merely colourless.
 *
 * <p>Then it rots. A corpse now lasts as long as the animal took to grow up —
 * half a minute for a big one — so a single static image would have it lying
 * there unchanged and then blinking out. The body dissolves instead: pixels are
 * punched out on the same ordered-dither the ground depletion uses, so it erodes
 * in the art's own idiom rather than fading smoothly, which nothing else here
 * does. The silhouette survives to the end, because what is drawn should match
 * what exists — the body is solid meat to a scavenger right up until it is gone.
 *
 * <p>Quantised to {@link DECAY_STEPS} stages and baked once per (phenotype,
 * stage): the cost is a handful of offscreen canvases per creature design, not
 * per corpse or frame.
 */
const corpseCache = new Map<number, HTMLCanvasElement>();

/** Decay stages baked per phenotype. Enough that erosion reads as continuous
 *  over a half-minute rot, few enough to stay a cheap cache. */
export const DECAY_STEPS = 6;

/** Art-pixel size of the dither cell, in atlas pixels. The sprites are drawn
 *  chunky, so an erosion at the atlas's own resolution would be invisible —
 *  this matches the hole size to the art. */
const DITHER_PIX = 4;

/** An opaque tile of the Bayer cells that should be erased at `amount` (0..1),
 *  for use as a `destination-out` pattern. */
function dissolveMask(amount: number): HTMLCanvasElement {
  const cv = document.createElement('canvas');
  cv.width = 4 * DITHER_PIX;
  cv.height = 4 * DITHER_PIX;
  const g = cv.getContext('2d')!;
  g.fillStyle = '#000';
  for (let i = 0; i < 16; i++) {
    if (BAYER4[i] < amount * 16) {
      g.fillRect((i % 4) * DITHER_PIX, Math.floor(i / 4) * DITHER_PIX, DITHER_PIX, DITHER_PIX);
    }
  }
  return cv;
}

/** Which of the baked decay stages a progress of `decay` (0..1) falls in. */
export function decayStage(decay: number): number {
  return Math.max(0, Math.min(DECAY_STEPS - 1, Math.floor(decay * DECAY_STEPS)));
}

export function corpseFor(pheno: number, img: HTMLCanvasElement, decay = 0): HTMLCanvasElement {
  const stage = decayStage(decay);
  const key = pheno * DECAY_STEPS + stage;
  const hit = corpseCache.get(key);
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
  // Erode. Quadratic, matching the engine's own decay curve (ProcCreature's
  // A_DEATH): the body holds together early and goes quickly at the end.
  const t = (stage + 0.5) / DECAY_STEPS;
  if (t > 0) {
    g.globalCompositeOperation = 'destination-out';
    g.fillStyle = g.createPattern(dissolveMask(t * t), 'repeat')!;
    g.fillRect(0, 0, cv.width, cv.height);
  }
  g.globalCompositeOperation = 'source-over';
  corpseCache.set(key, cv);
  return cv;
}
