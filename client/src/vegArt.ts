// Vegetation art: the one-tile plant sprites stamped over the ground, and the
// vegetation byte's layout. Split out of render.ts; render.ts re-exports it.

import { ART, hash01 } from './artCore';

// ---- vegetation sprites ----------------------------------------------------
//
// One-tile vegetation, drawn as transparent sprites OVER the floor: each kind
// grows through five stages — 1 is what depletion leaves behind (trampled
// remnants), 5 is fully grown — with four variants per stage so a field
// doesn't tile visibly. Painted at art resolution (12px/tile) with
// transparent ground, so the same sprite reads on any floor tile. The world
// integration (drawing these over the baked ground per tile, replacing the
// baked-in grass) comes later; the sprites and this painter are the record.

/** The kind field of a vegetation byte: which plant a tile carries, as a
 *  five-bit index above the three stage bits (VegFeed.KIND_SHIFT). Set by the
 *  server in the full grid only; deltas carry density alone, because terrain
 *  never changes underfoot. The fungus bed (16) and the cactus (8) sit on the
 *  bytes they always sent -- 0x80 and 0x40 -- from when the field was two
 *  flags; the surface floras take the low indices, which are the tile's own
 *  FLORA_* numbering. */
const VEG_KIND_SHIFT = 3;
export const VEG_KIND_MASK = 0xF8;
/** The low bits: growth stage, 0 = nothing stands here, 1..5. For a crop that
 *  is how much has grown; for a cactus it is how old the plant is. */
export const VEG_STAGE_MASK = 0x07;
export type VegKind = 'grass' | 'mushroom' | 'cactus' | 'fern' | 'wildflowers' | 'heather' | 'moss';
export const VEG_KINDS: VegKind[] = ['grass', 'fern', 'wildflowers', 'heather', 'moss', 'mushroom', 'cactus'];
const VEG_KIND_BY_INDEX: Record<number, VegKind> = {
  0: 'grass', 1: 'fern', 2: 'wildflowers', 3: 'heather', 4: 'moss', 8: 'cactus', 16: 'mushroom',
};

/** Which plant a vegetation byte describes. An index no painter knows draws
 *  as grass rather than as nothing, so a new plant that has not landed here
 *  yet is at least visibly a crop. */
export function vegKindOf(b: number): VegKind {
  return VEG_KIND_BY_INDEX[(b & VEG_KIND_MASK) >> VEG_KIND_SHIFT] ?? 'grass';
}
export const VEG_STAGES = 5;
export const VEG_VARIANTS = 4;

// Palettes in the ground bake's key: greens for living grass, dry olives for
// trampled remains.
const GRASS_BLADE = ['#2f5c2a', '#3f7a38', '#57944a'];
const GRASS_TIP = '#8fc46f';
const GRASS_DRY = ['#8a8a52', '#6f6b3f'];

// The mushroom wore six colours that existed in no ramp anywhere — the comment
// above used to claim they were "in the fungus family" and they were not in any
// family at all. A cap is the cave's berry: the same thing on the same errand
// as a wildflower, a shrub berry and a thicket berry, which all share the bloom
// accents. So the cap is BLOOM_RED promoted to a body colour the sanctioned way
// (§2) — the shade it lacks derived off the accent itself at x0.65 — with the
// bloom cream, its own partner accent, as the fleck. Everything that is not the
// cap comes off the sand ramp, which is already what the world's bones wear.
const SHROOM_CAP = '#e0455f';      // bloom red, promoted
const SHROOM_CAP_DARK = '#912c3d'; // and its derived shadow (x0.65)
const SHROOM_SPOT = '#f0e8c6';     // bloom cream, the accent's own partner
const SHROOM_STEM = '#c0aa7e';     // sand highlight: a pale stalk
const SHROOM_BUD = '#98865c';      // sand base: an unopened button
const SHROOM_DEAD = '#6e5f42';     // sand shadow: what a grazed bed leaves
/** §4's contact shadow, at the same weight the loader's stands on. */
const SHADE = 'rgba(0,0,0,0.42)';

// The cactus: the flora family's green, verbatim from the ground ramp it used
// to be baked with (GroundTextures.RAMP[CLS_CACTUS]), plus the bloom red every
// other flowering thing in this world wears.
const CACTUS = ['#2a4d24', '#3f7a38', '#5f9850'];
const CACTUS_BLOOM = '#e0455f';

/**
 * Five authored stamps, ordered YOUNGEST TO OLDEST: a shoot, a bare column,
 * one arm, two arms, and a veteran carrying three.
 *
 * <p>These were baked into the ground until now, and the ground bake is static
 * — so a cactus could only ever be as old as its POSITION said, and a growing
 * one would have needed the whole chunk re-baked to show it. Here, on the
 * vegetation sprite layer beside the mushroom, the stage arrives per tile on
 * the veg feed and changes whenever the server says it does. Nothing about the
 * drawing had to change for that; only which layer it lives in.
 *
 * <p>The order is the point. A cactus does not have five looks, it has one life
 * with five moments in it, and the stage indexes them — so when vegetation
 * starts growing, a real age lands on a ladder that already climbs.
 *
 * <p>Marks: `h` a lit tip, `b` the body, `d` the sunk flank, `x` the contact
 * shadow on the sand, `.` nothing. Silhouettes double by mirroring, which is
 * lossless on a square grid and, unlike a rotation, keeps the sun where it is.
 */
const CACTUS_FORMS = [
  // a shoot: this year's growth, no arms yet
  ['............', '............', '............', '............',
   '............', '............', '.....hh.....', '.....bb.....',
   '.....bb.....', '.....dd.....', '.....xx.....', '............'],
  // a bare column, grown tall but still unbranched
  ['............', '............', '....hh......', '....bb......',
   '....bb......', '....bb......', '....bb......', '....bb......',
   '....bb......', '....dd......', '....xx......', '............'],
  // the first arm
  ['............', '....hh......', '....bb......', '....bb......',
   '....bb.hh...', '....bb.bb...', '....bbbbb...', '....bb.d....',
   '....bb......', '....dd......', '....xx......', '............'],
  // two arms
  ['............', '....hh......', '....bb......', '.hh.bb......',
   '.bb.bb.hh...', '.dbbbb.bb...', '....bbbbb...', '....bb.d....',
   '....bb......', '....dd......', '....xx......', '............'],
  // a veteran: three arms, the oldest thing standing in the pan
  ['....hh......', '....bb......', '.hh.bb.hh...', '.bb.bb.bb...',
   '.bbbbbbbb...', '.d..bb..d...', '....bb.hh...', '....bbbbb...',
   '....bb.d....', '....dd......', '....xx......', '............'],
];

/**
 * Paints one vegetation tile at (x, y) with `px` screen pixels per art pixel.
 * Deterministic per (kind, stage, variant): the same triple always paints the
 * same sprite, so the world and the catalog can never drift apart.
 */
export function drawVegetationTile(g: CanvasRenderingContext2D, kind: VegKind,
    stage: number, variant: number, x: number, y: number, px = 1): void {
  const rnd = (n: number) => hash01(variant * 31 + n * 7 + stage * 13, n * 5 + variant,
    kind === 'grass' ? 41 : 43);
  const p = (ax: number, ay: number, col: string) => {
    if (ax < 0 || ay < 0 || ax > 11 || ay > 11) return;
    g.fillStyle = col;
    g.fillRect(x + ax * px, y + ay * px, px, px);
  };
  if (kind === 'cactus') {
    drawCactus(g, stage, variant, x, y, px);
    return;
  }
  if (kind === 'fern' || kind === 'wildflowers' || kind === 'heather' || kind === 'moss') {
    drawFlora(kind, stage, variant, p);
    return;
  }
  if (kind === 'grass') {
    if (stage <= 1) {
      trampled(p, rnd);
      return;
    }
    // Growing: upright tufts — more and taller with each stage.
    const tufts = [0, 0, 3, 4, 6, 7][stage];
    const maxH = [0, 0, 1, 2, 3, 3][stage];
    for (let i = 0; i < tufts; i++) {
      const bx = 1 + Math.floor(rnd(i) * 10);
      const by = 3 + Math.floor(rnd(i + 10) * 8);
      const h = 1 + Math.floor(rnd(i + 20) * maxH);
      const blade = GRASS_BLADE[Math.floor(rnd(i + 30) * 3)];
      for (let k = 0; k < h; k++) p(bx, by - k, blade);
      if (h >= 2 && stage >= 4) p(bx, by - h, GRASS_TIP); // lit tip on tall blades
      if (stage >= 3 && rnd(i + 40) > 0.55) p(bx + 1, by, GRASS_BLADE[0]); // base spread
    }
  } else {
    if (stage <= 1) {
      // Kicked-over remains: a toppled stem and a scatter of spores.
      const sx = 3 + Math.floor(rnd(0) * 5), sy = 5 + Math.floor(rnd(1) * 4);
      p(sx, sy, SHROOM_DEAD); p(sx + 1, sy, SHROOM_DEAD);
      for (let i = 0; i < 3; i++) {
        p(1 + Math.floor(rnd(i + 10) * 10), 2 + Math.floor(rnd(i + 20) * 9), SHROOM_DEAD);
      }
      return;
    }
    // A cap is a raised thing, so it is lit north and sunk south (§4) — the
    // upper rank takes the bloom red, the rank under the dome its derived
    // shadow. This used to hash-pick ONE shade for the whole mushroom, which
    // made some of them uniformly dark and read as a second species rather
    // than as the same dome turned toward the same sun. A contact shadow one
    // art-pixel south of the stem sits the thing on the mat; nothing floats.
    const shroom = (cx: number, cy: number, big: boolean) => {
      if (big) {
        p(cx, cy + 2, SHADE); // contact shadow
        p(cx - 1, cy - 2, SHROOM_CAP); p(cx, cy - 2, SHROOM_CAP); p(cx + 1, cy - 2, SHROOM_CAP);
        p(cx - 2, cy - 1, SHROOM_CAP_DARK); p(cx - 1, cy - 1, SHROOM_CAP_DARK);
        p(cx, cy - 1, SHROOM_CAP_DARK); p(cx + 1, cy - 1, SHROOM_CAP_DARK);
        p(cx + 2, cy - 1, SHROOM_CAP_DARK);
        p(cx + (rnd(cx) > 0.5 ? 1 : -1), cy - 2, SHROOM_SPOT);
        p(cx, cy, SHROOM_STEM); p(cx, cy + 1, SHROOM_STEM);
      } else {
        p(cx, cy + 1, SHADE); // contact shadow
        p(cx - 1, cy - 1, SHROOM_CAP_DARK); p(cx, cy - 1, SHROOM_CAP);
        p(cx + 1, cy - 1, SHROOM_CAP_DARK);
        p(cx, cy, SHROOM_STEM);
      }
    };
    const bud = (cx: number, cy: number) => { p(cx, cy, SHROOM_BUD); p(cx + 1, cy, SHROOM_BUD); };
    const bx = 3 + Math.floor(rnd(50) * 4), by = 4 + Math.floor(rnd(51) * 4);
    const ox = 2 + Math.floor(rnd(52) * 7), oy = 2 + Math.floor(rnd(53) * 8);
    // The stages are spread over the range a fungus bed can actually REACH.
    // A tile's stage is measured against the world's maximum and its fertility
    // is the ceiling (vegetationCap = VEG_MAX * fertility); beds are seeded at
    // 0.6, so a bed that has fully regrown reports 60, which quantises to
    // stage 3 — and the live world serves nothing above it. The ramp used to
    // put its first big cap at 4 and its crowd at 5, which meant every bed in
    // the game, however full, was drawn as buds or one three-pixel cap. The
    // top of the reachable range has to look like the top of the range.
    //
    // 4 and 5 stay, and are not dead: nutrient closure drifts fertility up
    // over a world's life, so an old, well-fed bed grows into them.
    if (stage === 2) { shroom(bx, by, false); if (rnd(54) > 0.4) bud(ox, oy); return; }
    if (stage === 3) { shroom(bx, by, true); if (rnd(54) > 0.5) bud(ox, oy); return; }
    if (stage === 4) {
      shroom(bx, by, true);
      shroom(Math.min(10, ox + 1), Math.min(10, oy + 1), false);
      if (rnd(55) > 0.5) bud((bx + ox) % 10 + 1, 9);
      return;
    }
    // Stage 5 is a crowd: two open caps, a young one still small, and buds
    // coming. Anything running off the tile is clipped by p(), which is what
    // makes a bed read as continuous across its tiles rather than as a grid of
    // separate clumps.
    shroom(bx, by, true);
    shroom(bx <= 4 ? bx + 5 : bx - 4, by <= 5 ? by + 4 : by - 3, true);
    shroom(Math.min(10, ox + 1), Math.min(10, oy + 1), false);
    if (rnd(55) > 0.3) bud((bx + ox) % 10 + 1, 9);
  }
}

/**
 * One cactus, at the moment of its life `stage` puts it in.
 *
 * <p>Mirror and bloom are taken from the VARIANT rather than from a position,
 * because the sprite is baked once per (kind, stage, variant) and cached: a
 * mark that depended on anything else would be drawn once and then reused
 * everywhere, which is the same cache serving two different plants.
 */
function drawCactus(g: CanvasRenderingContext2D, stage: number, variant: number,
    x: number, y: number, px: number): void {
  const form = CACTUS_FORMS[Math.max(0, Math.min(CACTUS_FORMS.length - 1, stage - 1))];
  const mirror = (variant & 1) !== 0;
  const flowers = (variant & 2) !== 0 && stage >= 3; // only a grown plant blooms
  for (let ay = 0; ay < 12; ay++) {
    for (let ax = 0; ax < 12; ax++) {
      const c = form[ay][mirror ? 11 - ax : ax];
      if (c === '.') continue;
      g.fillStyle = c === 'h' ? (flowers && ay <= 2 ? CACTUS_BLOOM : CACTUS[2])
        : c === 'b' ? CACTUS[1]
        : c === 'd' ? CACTUS[0]
        : SHADE; // 'x', the contact shadow: it stands on the sand
      g.fillRect(x + ax * px, y + ay * px, px, px);
    }
  }
}

type Px = (ax: number, ay: number, col: string) => void;

/** Trampled leftovers, the same for every surface crop: flattened dry strokes
 *  lying sideways, a few crumbs. What a grazer leaves does not say what stood
 *  there, so stage 1 is shared and the plant only shows once it regrows. */
function trampled(p: Px, rnd: (n: number) => number): void {
  for (let i = 0; i < 4; i++) {
    const sx = 1 + Math.floor(rnd(i) * 8), sy = 2 + Math.floor(rnd(i + 10) * 8);
    const col = GRASS_DRY[i % 2];
    p(sx, sy, col); p(sx + 1, sy, col);
    if (rnd(i + 20) > 0.5) p(sx + 2, sy, col);
  }
  for (let i = 0; i < 3; i++) {
    p(1 + Math.floor(rnd(i + 30) * 10), 1 + Math.floor(rnd(i + 40) * 10), GRASS_DRY[1]);
  }
}

// The surface floras: four plants the meadow's one crop is drawn as, chosen
// per tile by worldgen (Tile.flora) and carried on the veg feed as the kind.
// Their ramps are all borrowed. Fern wears the reed bed's wet green -- the
// thicket's dark green was tried first and vanished into the sward, its
// highlight a hair off the meadow's own base. Heather and moss take that
// thicket green (GroundTextures.RAMP[CLS_COVER]) where it reads: heather as
// mounds against the pale margin, moss as cushions on rocky grit. Stems are
// soil, lichen flecks are the sand ramp's pale, and every bloom is the one
// bloom red and cream the rest of the world's flora already wears.
const FERN = ['#14301f', '#2c5a36', '#4f8752'];     // reeds ramp, verbatim
const THICKET = ['#1b3a16', '#2b5422', '#456c36'];  // cover ramp, verbatim
const STEM = '#63472e';      // soil base: a woody stalk, a fern's crown
const LICHEN = '#c0aa7e';    // sand highlight
const BLOOM = '#e0455f', BLOOM_CREAM = '#f0e8c6';

/**
 * The fern, seen from above: a crown `c` with fronds radiating from it.
 * Authored as ONE frond -- a rachis with pinnae alternating sides and
 * lengthening toward the base -- turned by lossless quarter-turns into the
 * others and lit afterwards in world space: north of the crown lit, level
 * with it mid, south of it sunk. The resolved stamps are frozen here, crown
 * at (5,5), so the scenario that pins them reads the same strings the
 * painter does; the painter moves a stamp about its crown, which keeps the
 * sun where it is.
 *
 * <p>A fern is PLACED the way §3's motif lattice places plants: one or two
 * small ferns per tile at hashed anchors, clipped by the tile edge so a bed
 * runs on across tiles. Rejected on the way: a generated five-frond rosette
 * with pinnae on both sides of every other cell (a cross-hatch); four fronds
 * of equal length (a crosshair); and a full-tile rosette with the crown
 * jittered by a pixel, which in the live world gridded into one crosshair
 * per tile over the sward. Small, offset and clipped is what reads as a bed.
 *
 * <p>Marks: `h` lit pinna, `b` frond, `d` sunk frond, `c` the crown, `x` the
 * contact shadow.
 */
const FERN_FIDDLEHEADS = [
  '............', '............', '............', '....hh......', '...h.b......', '.....b......',
  '.....b......', '.....d..hh..', '.....x.h.b..', '.........b..', '.........d..', '.........x..'];
const FERN_SMALL = [ // a young plant: two short fronds
  '............', '............', '...hhb......', '.....bhhh...', '...bbdb.b...', '.....cddd...',
  '.....x.b....', '.......b....', '............', '............', '............', '............'];
const FERN_MID = [ // three fronds
  '............', '.....bh.....', '...hhb......', '...h.bhhh...', '...bbdb.b...', '...ddcddd...',
  '....bx.b....', '....b..b....', '............', '............', '............', '............'];
const FERN_BIG = [ // a grown fern
  '....hb......', '.....bh.....', '...hhb......', '...h.bhhh...', '...bbdb.b...', '..dddcdddd..',
  '..b.bx.b.b..', '..b.b..b....', '............', '............', '............', '............'];

/** Stamps a fern with its crown at (ox, oy), mirrored or not. */
function stampFern(p: Px, form: string[], mirror: boolean, ox: number, oy: number): void {
  for (let ay = 0; ay < 12; ay++) {
    for (let ax = 0; ax < 12; ax++) {
      const c = form[ay][mirror ? 11 - ax : ax];
      if (c === '.') continue;
      p(ax + ox - 5, ay + oy - 5, c === 'h' ? FERN[2] : c === 'b' ? FERN[1] : c === 'd' ? FERN[0]
        : c === 'c' ? STEM : SHADE);
    }
  }
}

/** Moss cushions: three authored blobs, lit on top, sunk underneath. The first
 *  draft rasterised 3x3 squares and they read as cubes standing on the grit. */
const MOSS_BLOBS = [['hh', 'bd'], ['.hh.', 'hbbd', '.dd.'], ['hh.', 'bbh', '.db']];

function drawFlora(kind: VegKind, stage: number, variant: number, p: Px): void {
  const salt = kind === 'fern' ? 47 : kind === 'wildflowers' ? 53 : kind === 'heather' ? 61 : 59;
  const rnd = (n: number) => hash01(variant * 31 + n * 7 + stage * 13, n * 5 + variant, salt);
  if (stage <= 1) {
    if (kind === 'moss') {
      for (let i = 0; i < 3; i++) { // a dry crust
        const bx = 1 + Math.floor(rnd(i) * 9), by = 1 + Math.floor(rnd(i + 10) * 9);
        p(bx, by, GRASS_DRY[1]); p(bx + 1, by, GRASS_DRY[1]);
      }
    } else {
      trampled(p, rnd);
    }
    return;
  }
  if (kind === 'fern') {
    if (stage === 2) {
      stampFern(p, FERN_FIDDLEHEADS, (variant & 1) !== 0, 5, 5);
      return;
    }
    const ax = 2 + Math.floor(rnd(1) * 8), ay = 3 + Math.floor(rnd(2) * 7);
    stampFern(p, stage === 3 ? FERN_SMALL : stage === 4 ? FERN_MID : FERN_BIG, (variant & 1) !== 0, ax, ay);
    if (stage === 5) { // a second, younger plant beside the grown one
      stampFern(p, FERN_SMALL, (variant & 2) !== 0, 2 + Math.floor(rnd(3) * 8), 3 + Math.floor(rnd(4) * 7));
    }
    return;
  }
  if (kind === 'wildflowers') {
    // Trefoil leaves and a few blades, so it is still meadow; then the blooms
    // it puts up once grown -- a stalk with a two-pixel head, red or cream.
    const leaves = [0, 0, 2, 3, 3, 4][stage];
    for (let i = 0; i < leaves; i++) {
      const bx = 1 + Math.floor(rnd(i) * 9), by = 2 + Math.floor(rnd(i + 10) * 8);
      p(bx, by, GRASS_BLADE[2]); p(bx + 1, by, GRASS_BLADE[1]);
      p(bx, by + 1, GRASS_BLADE[1]); p(bx + 1, by + 1, GRASS_BLADE[0]);
    }
    const tufts = [0, 0, 2, 2, 3, 3][stage];
    for (let i = 0; i < tufts; i++) {
      const bx = 1 + Math.floor(rnd(i + 60) * 10), by = 3 + Math.floor(rnd(i + 70) * 8);
      p(bx, by, GRASS_BLADE[1]); p(bx, by - 1, GRASS_BLADE[1]);
    }
    // Blooms from stage 3: the mid meadow tops out at 4, so a ladder that
    // flowered only at the top would never flower.
    const heads = [0, 0, 0, 1, 2, 3][stage];
    for (let i = 0; i < heads; i++) {
      const bx = 2 + Math.floor(rnd(i + 20) * 8), by = 4 + Math.floor(rnd(i + 30) * 6);
      p(bx, by, GRASS_BLADE[0]); p(bx, by - 1, GRASS_BLADE[1]);
      const col = rnd(i + 40) > 0.6 ? BLOOM_CREAM : BLOOM;
      p(bx, by - 2, col); p(bx + 1, by - 2, col);
    }
    return;
  }
  if (kind === 'heather') {
    // Low woody mounds: three wide, two tall, lit on top and sunk below, on a
    // stem with its contact shadow; in flower once grown.
    // The dry margin's crop rarely climbs past stage 3, so that is where
    // heather has to be in flower, or the live world never sees it bloom.
    const n = [0, 0, 1, 2, 3, 3][stage];
    for (let i = 0; i < n; i++) {
      const bx = 1 + Math.floor(rnd(i) * 8), by = 2 + Math.floor(rnd(i + 10) * 7);
      p(bx, by, THICKET[2]); p(bx + 1, by, THICKET[2]); p(bx + 2, by, THICKET[1]);
      p(bx, by + 1, THICKET[1]); p(bx + 1, by + 1, THICKET[0]); p(bx + 2, by + 1, THICKET[0]);
      p(bx + 1, by + 2, STEM);
      p(bx + 1, by + 3, SHADE);
      if (stage >= 3 && (stage >= 4 || rnd(i + 20) > 0.4)) {
        p(bx + 1, by - 1, BLOOM);
        if (stage === 5) p(bx + 2, by - 1, BLOOM);
      }
    }
    return;
  }
  // moss: cushions by a per-stage plan, then a lichen fleck or two. Rocky
  // ground is poor, so most moss in the world sits at stage 2; that rung has
  // to carry the identity, cushion and fleck both.
  const plan = [[], [], [0, 2], [0, 2, 0], [1, 0, 2, 0], [1, 1, 2, 0, 0]][stage];
  plan.forEach((bi, i) => {
    const blob = MOSS_BLOBS[bi];
    const bx = 1 + Math.floor(rnd(i) * (10 - blob[0].length));
    const by = 1 + Math.floor(rnd(i + 10) * (10 - blob.length));
    for (let yy = 0; yy < blob.length; yy++) {
      for (let xx = 0; xx < blob[0].length; xx++) {
        const c = blob[yy][xx];
        if (c !== '.') p(bx + xx, by + yy, c === 'h' ? THICKET[2] : c === 'b' ? THICKET[1] : THICKET[0]);
      }
    }
  });
  for (let i = 0; i < stage - 1; i++) {
    p(1 + Math.floor(rnd(i + 30) * 10), 1 + Math.floor(rnd(i + 40) * 10), LICHEN);
  }
}

/** The baked one-tile sprite for (kind, stage, variant) — 12px, transparent. */
const vegTileCache = new Map<string, HTMLCanvasElement>();
export function vegetationTileFor(kind: VegKind, stage: number, variant: number): HTMLCanvasElement {
  const key = `${kind}:${stage}:${variant}`;
  const hit = vegTileCache.get(key);
  if (hit) return hit;
  const cv = document.createElement('canvas');
  cv.width = ART;
  cv.height = ART;
  drawVegetationTile(cv.getContext('2d')!, kind, stage, variant, 0, 0, 1);
  vegTileCache.set(key, cv);
  return cv;
}
