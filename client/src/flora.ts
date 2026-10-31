// Large flora: trees and big shrubs standing over the creatures. ART-STYLE §5,
// "living things".
//
// The world places them (Worlds.plantFlora) and serves them once per level as
// rows of [kind, gx, gy, layout, mirror, stage], the foot in world art-pixels;
// nothing about a plant changes, so each (kind, layout, mirror, stage) is baked
// once into a small canvas at one pixel an art-pixel and stamped from there.
//
// The art is authored, not computed (§5): leaf clumps, cone tiers, trap lobes and
// split leaves are hand-drawn string stamps, and each plant is a hand-placed
// arrangement of them. What the code adds is the light, applied in world space
// after any flip, by four rules that came out of the comparison sheets:
//
//  - each part is lit on its own north edge and shaded on its south one, with
//    light from a warm ramp and shade from a cool one (the plant borrows both,
//    §2), so the shading shifts hue rather than going grey;
//  - a part further back sits a step down that ladder, and where a nearer part
//    lies over it, it darkens along the edge -- the overlap's shadow -- so
//    overlapping leaves keep their own outlines instead of fusing;
//  - the outline is the outside's only, dark to the south and east and the
//    deepest shade to the north and west, so a hole through a leaf shows ground;
//  - a red stays red however far back: a dark trap reads as withered.
//
// Rejected on the way, so nobody walks it again: crowns without an outline (they
// vanish into meadow); a conifer as a disc (straight down -- this view is not)
// and as a tall Christmas tree (steeper than every wall); a flytrap as one trap
// stamp turned round a centre (a red cross, then a star, then lace) and as
// jaws with teeth (an animal); a monstera as eight rotated leaves (a square),
// with speckle (it buried the slits), and in one green (the leaves fused).

import { ART } from './artCore';

export const FLORA_KINDS = ['broadleaf', 'oak', 'conifer', 'acacia', 'shrub', 'berry', 'flytrap', 'monstera'] as const;
export type FloraKind = typeof FLORA_KINDS[number];

/** Flora.Kind's wire order: the server sends the ordinal. */
export type FloraRow = [number, number, number, number, number, number]; // kind, gx, gy, layout, mirror, stage

// ---- palette: ramps, verbatim, and shades derived off them -----------------------
const derive = (hex: string, f: number): string => {
  const v = parseInt(hex.slice(1), 16);
  const c = (n: number) => Math.min(255, Math.round(((v >> n) & 255) * f)).toString(16).padStart(2, '0');
  return '#' + c(16) + c(8) + c(0);
};
// GroundTextures.RAMP: grass, cover, reeds; soil for bark.
const GRASS_HI = '#5f9850', GRASS = '#3f7a38';
const COVER_HI = '#456c36', COVER = '#2b5422', COVER_LO = '#1b3a16';
const REEDS_HI = '#4f8752', REEDS = '#2c5a36', REEDS_LO = '#14301f';
const BARK = ['#40301f', '#63472e', '#866543'];
// Bloom red as a body colour, its shade derived at x0.65 -- the mushroom cap's pair.
const RED = '#e0455f', RED_LO = '#912c3d';
const OUTLINE = derive(REEDS_LO, 0.65);
/** §4's contact shadow weight. */
const SHADOW = 'rgba(0,0,0,0.42)';

/** A plant's light ladder: lit, base, shade, deep -- warm light over cool shade. */
const WARM = [GRASS_HI, GRASS, REEDS, REEDS_LO];
const DARK = [COVER_HI, COVER, COVER_LO, REEDS_LO];
const PINE = [REEDS_HI, REEDS, COVER_LO, REEDS_LO];

// ---- the stamps --------------------------------------------------------------------
// '#' tissue, 'm' a midrib, 's' a stalk, 'r' a trap's red face, 'R' its hinge,
// 'c' a cilium, 'B' a berry.
const C_SMALL = ['..###..', '.#####.', '#######', '#######', '.#####.', '..###..'];
const C_TINY = ['.###.', '#####', '#####', '.###.'];

const TRAP_OPEN = ['.....c.c.', '..c.c###c', '.c##rrrr#', 'c#rrRRRr#', '#rrRRrr#c', '#rrrr##c.', 'c###c.c..'];
const TRAP_SMALL = ['.c.c..', 'c#rr#c', '#rRRr#', 'c#rr#.', '.c..c.'];
const TRAP_SHUT = ['.###.', '#####', 'ccccc', '####.'];
const BLADE = ['..####.', 'ss######', '..####..'];

// The monstera leaf: slits cut in from the margin most of the way to a pale
// midrib, alternating sides, two holes beside the rib -- drawn so the light rule
// lights the cell below each slit and shades the one above.
const MLEAF = ['....###....', '..#######..', '.####m####.', '...##m#####', '#####m##...', '###.#m#####',
  '...##m#####', '#####m##...', '.####m#.##.', '..###m###..', '....#m#....', '.....m.....'];
const MLEAF_YOUNG = ['..###.', '.####.', '###m#.', '.##m..', '...m..']; // a new leaf, whole yet

type Part = { st: string[]; x: number; y: number; t?: string; tone?: number };
/** One growth stage's body: its box, how high the crown stands, its parts. */
type Body = { w: number; h: number; lift: number; faceH: number; trunk: number; shadow: [number, number]; layouts: Part[][] };
type Def = Body & { ladder: string[]; rib?: string };

/** Clumps north of the crown's middle are further back: a step down the ladder. */
const crown = (clumps: Array<[string[], number, number]>, mid: number): Part[] =>
  clumps.map(([st, x, y]) => ({ st, x, y, tone: y < mid ? 1 : 0 })).sort((a, b) => a.y - b.y);
const berries = (spots: Array<[number, number, number]>): Part[] => spots.map(([x, y, tone]) => ({ st: ['BB'], x, y, tone }));

const SHRUB_A: Array<[string[], number, number]> = [[C_TINY, 2, 0], [C_TINY, 7, 0], [C_TINY, 0, 3], [C_TINY, 4, 2], [C_TINY, 9, 3], [C_TINY, 3, 6], [C_TINY, 7, 6]];
const SHRUB_B: Array<[string[], number, number]> = [[C_TINY, 4, 0], [C_TINY, 0, 2], [C_TINY, 8, 1], [C_TINY, 3, 3], [C_TINY, 7, 4], [C_TINY, 1, 6], [C_TINY, 5, 6]];

export const FLORA: Record<FloraKind, Def> = {
  broadleaf: { ladder: WARM, w: 19, h: 15, lift: 4, faceH: 2, trunk: 2, shadow: [9, 3], layouts: [
    crown([[C_SMALL, 5, 0], [C_TINY, 1, 2], [C_SMALL, 11, 1], [C_SMALL, 0, 5], [C_SMALL, 7, 5], [C_SMALL, 12, 6], [C_SMALL, 4, 9]], 4),
    crown([[C_SMALL, 2, 0], [C_SMALL, 9, 0], [C_SMALL, 0, 4], [C_SMALL, 6, 3], [C_SMALL, 11, 4], [C_SMALL, 3, 8], [C_SMALL, 9, 8]], 3)] },
  oak: { ladder: DARK, w: 18, h: 14, lift: 4, faceH: 2, trunk: 2, shadow: [9, 3], layouts: [
    crown([[C_SMALL, 2, 0], [C_SMALL, 9, 0], [C_SMALL, 0, 4], [C_SMALL, 6, 3], [C_SMALL, 11, 4], [C_SMALL, 3, 8], [C_SMALL, 9, 8]], 3),
    crown([[C_SMALL, 5, 0], [C_TINY, 1, 2], [C_SMALL, 11, 1], [C_SMALL, 0, 5], [C_SMALL, 7, 5], [C_SMALL, 11, 6], [C_SMALL, 4, 8]], 4)] },
  // The tier below sits behind the one above: a cone seen a little from the south.
  conifer: { ladder: PINE, w: 13, h: 13, lift: 3, faceH: 1, trunk: 2, shadow: [6, 3], layouts: [[
    { st: ['..#########..', '.###########.', '#############', '#############', '#.##.###.##.#'], x: 0, y: 8, tone: 1 },
    { st: ['..#######..', '.#########.', '###########', '###########', '#.##.#.##.#'], x: 1, y: 4 },
    { st: ['...#...', '..###..', '.#####.', '#######', '#######', '#.#.#.#'], x: 3, y: 0 }], [
    { st: ['..#########..', '.###########.', '#############', '#############', '##.##.#.##.#'], x: 0, y: 8, tone: 1 },
    { st: ['..#######..', '.#########.', '###########', '###########', '#.#.##.#.##'], x: 1, y: 4 },
    { st: ['...#...', '..###..', '.#####.', '#######', '#######', '.#.#.#.'], x: 3, y: 0 }]] },
  acacia: { ladder: WARM, w: 23, h: 8, lift: 6, faceH: 1, trunk: 1, shadow: [10, 3], layouts: [
    crown([[C_TINY, 0, 2], [C_TINY, 4, 0], [C_TINY, 9, 0], [C_TINY, 14, 1], [C_TINY, 18, 3], [C_TINY, 6, 4], [C_TINY, 12, 4]], 2),
    crown([[C_TINY, 1, 3], [C_TINY, 5, 1], [C_TINY, 10, 0], [C_TINY, 15, 0], [C_TINY, 18, 2], [C_TINY, 8, 4], [C_TINY, 13, 4]], 2)] },
  shrub: { ladder: DARK, w: 12, h: 10, lift: 1, faceH: 1, trunk: 0, shadow: [6, 2], layouts: [crown(SHRUB_A, 2), crown(SHRUB_B, 2)] },
  berry: { ladder: DARK, w: 12, h: 10, lift: 1, faceH: 1, trunk: 0, shadow: [6, 2], layouts: [
    [...crown(SHRUB_A, 2), ...berries([[2, 4, 0], [8, 5, 0], [5, 8, 0], [9, 1, 1]])],
    [...crown(SHRUB_B, 2), ...berries([[1, 3, 0], [8, 6, 0], [4, 4, 0], [6, 1, 1]])]] },
  flytrap: { ladder: WARM, w: 18, h: 16, lift: 0, faceH: 0, trunk: 0, shadow: [8, 2], layouts: [[
    { st: BLADE, x: 9, y: 12, tone: 1 }, { st: BLADE, x: 0, y: 11, t: 'f', tone: 1 },
    { st: ['.##.', '####', '.ss.', '..s.'], x: 6, y: 12, tone: 1 },
    { st: ['s', 's', 's', 's', 's'], x: 9, y: 8 }, { st: ['s..', '.s.', '..s', '..s'], x: 4, y: 9 },
    { st: TRAP_SHUT, x: 12, y: 8, tone: 1 }, { st: TRAP_SMALL, x: 1, y: 5, tone: 1 }, { st: TRAP_OPEN, x: 7, y: 1 }], [
    { st: BLADE, x: 8, y: 11, tone: 1 }, { st: BLADE, x: 1, y: 12, t: 'f', tone: 1 },
    { st: ['s', 's', 's', 's'], x: 7, y: 8 }, { st: ['..s', '.s.', 's..'], x: 9, y: 9 },
    { st: TRAP_SMALL, x: 11, y: 4, t: 'f', tone: 1 }, { st: TRAP_SHUT, x: 12, y: 10, tone: 1 },
    { st: TRAP_OPEN, x: 0, y: 2, t: 'f' }]] },
  monstera: { ladder: WARM, rib: GRASS_HI, w: 18, h: 16, lift: 0, faceH: 0, trunk: 0, shadow: [8, 2], layouts: [[
    { st: MLEAF, x: 7, y: 0, t: 'r', tone: 1 }, { st: MLEAF, x: 0, y: 1, t: 'rrr', tone: 1 },
    { st: ['.s.', 's.s', 's.s', '.s.'], x: 7, y: 11 }, { st: MLEAF_YOUNG, x: 11, y: 9, t: 'r', tone: 1 },
    { st: MLEAF, x: 3, y: 5 }], [
    { st: MLEAF, x: 6, y: 0, tone: 1 }, { st: MLEAF, x: 0, y: 3, t: 'rrr', tone: 1 },
    { st: ['s..', '.s.', '.s.'], x: 8, y: 12 }, { st: MLEAF, x: 7, y: 5, t: 'r' },
    { st: MLEAF_YOUNG, x: 1, y: 11, t: 'f' }]] },
};

// ---- growing up ------------------------------------------------------------------------
// Four stages: seedling, sapling, young, mature (FLORA above). Each younger stage
// is its own small arrangement of the same stamps -- fewer parts, a shorter
// trunk, a smaller shadow -- not the mature plant scaled down: scaling breaks
// the lattice and thins every outline to nothing.
export const FLORA_STAGES = 4;
const SPROUT = ['##.##', '.#s#.', '..s..'];
const seed = (st: string[]): Body => ({ w: st[0].length, h: st.length, lift: 0, faceH: 0, trunk: 0, shadow: [2, 1], layouts: [[{ st, x: 0, y: 0 }]] });
const YOUNG_SHRUB: Array<[string[], number, number]> = [[C_TINY, 1, 0], [C_TINY, 5, 0], [C_TINY, 3, 2], [C_TINY, 0, 3], [C_TINY, 5, 3]];
const SAPLING_SHRUB: Array<[string[], number, number]> = [[C_TINY, 0, 1], [C_TINY, 3, 0]];

const YOUNGER: Record<FloraKind, [Body, Body, Body]> = {
  broadleaf: [seed(SPROUT),
    { w: 9, h: 7, lift: 5, faceH: 1, trunk: 1, shadow: [4, 2], layouts: [crown([[C_TINY, 0, 1], [C_TINY, 4, 0], [C_TINY, 2, 3]], 2)] },
    { w: 15, h: 11, lift: 3, faceH: 2, trunk: 2, shadow: [7, 3], layouts: [
      crown([[C_SMALL, 3, 0], [C_TINY, 0, 2], [C_SMALL, 8, 1], [C_SMALL, 1, 4], [C_SMALL, 7, 5]], 3)] }],
  oak: [seed(SPROUT),
    { w: 10, h: 8, lift: 4, faceH: 1, trunk: 1, shadow: [4, 2], layouts: [crown([[C_TINY, 0, 0], [C_TINY, 4, 1], [C_TINY, 1, 3], [C_TINY, 5, 4]], 2)] },
    { w: 14, h: 11, lift: 3, faceH: 2, trunk: 2, shadow: [7, 3], layouts: [
      crown([[C_SMALL, 1, 0], [C_SMALL, 7, 0], [C_SMALL, 0, 4], [C_SMALL, 7, 4], [C_TINY, 5, 7]], 3)] }],
  conifer: [seed(['..#..', '.###.', '#####', '..s..']),
    { w: 9, h: 8, lift: 2, faceH: 1, trunk: 1, shadow: [4, 2], layouts: [[
      { st: ['..#####..', '.#######.', '#########', '#.#.#.#.#'], x: 0, y: 4, tone: 1 },
      { st: ['..#..', '.###.', '#####', '#####', '#.#.#'], x: 2, y: 0 }]] },
    { w: 11, h: 11, lift: 3, faceH: 1, trunk: 2, shadow: [5, 3], layouts: [[
      { st: ['..#######..', '.#########.', '###########', '###########', '#.##.#.##.#'], x: 0, y: 6, tone: 1 },
      { st: ['..#####..', '.#######.', '#########', '#.#.#.#.#'], x: 1, y: 3 },
      { st: ['..#..', '.###.', '#####', '#.#.#'], x: 3, y: 0 }]] }],
  acacia: [seed(['##...##', '.##s##.', '...s...']),
    { w: 13, h: 6, lift: 6, faceH: 1, trunk: 1, shadow: [6, 2], layouts: [crown([[C_TINY, 0, 1], [C_TINY, 4, 0], [C_TINY, 8, 1], [C_TINY, 4, 2]], 1)] },
    { w: 18, h: 7, lift: 5, faceH: 1, trunk: 1, shadow: [8, 3], layouts: [
      crown([[C_TINY, 0, 2], [C_TINY, 4, 0], [C_TINY, 9, 0], [C_TINY, 13, 2], [C_TINY, 7, 3]], 2)] }],
  shrub: [seed(['.#.#.', '#####', '.###.']),
    { w: 8, h: 5, lift: 0, faceH: 1, trunk: 0, shadow: [4, 2], layouts: [crown(SAPLING_SHRUB, 1)] },
    { w: 10, h: 7, lift: 1, faceH: 1, trunk: 0, shadow: [5, 2], layouts: [crown(YOUNG_SHRUB, 2)] }],
  berry: [seed(['.#.#.', '#####', '.###.']),
    { w: 8, h: 5, lift: 0, faceH: 1, trunk: 0, shadow: [4, 2], layouts: [[...crown(SAPLING_SHRUB, 1), ...berries([[3, 2, 0]])]] },
    { w: 10, h: 7, lift: 1, faceH: 1, trunk: 0, shadow: [5, 2], layouts: [[...crown(YOUNG_SHRUB, 2), ...berries([[2, 4, 0], [7, 4, 0], [6, 1, 1]])]] }],
  flytrap: [seed(['.c.c.', 'c#r#c', '.###.', '..s..']),
    { w: 13, h: 9, lift: 0, faceH: 0, trunk: 0, shadow: [5, 2], layouts: [[
      { st: BLADE, x: 5, y: 6, tone: 1 }, { st: BLADE, x: 0, y: 5, t: 'f', tone: 1 },
      { st: ['s', 's'], x: 5, y: 5 }, { st: TRAP_SMALL, x: 3, y: 0 }]] },
    { w: 15, h: 12, lift: 0, faceH: 0, trunk: 0, shadow: [7, 2], layouts: [[
      { st: BLADE, x: 7, y: 9, tone: 1 }, { st: BLADE, x: 0, y: 9, t: 'f', tone: 1 },
      { st: ['s', 's'], x: 8, y: 8 }, { st: ['s.', '.s'], x: 3, y: 8 },
      { st: TRAP_SMALL, x: 0, y: 3, t: 'f', tone: 1 }, { st: TRAP_OPEN, x: 5, y: 1 }]] }],
  monstera: [seed(MLEAF_YOUNG),
    { w: 11, h: 7, lift: 0, faceH: 0, trunk: 0, shadow: [5, 2], layouts: [[
      { st: MLEAF_YOUNG, x: 0, y: 0, t: 'f', tone: 1 }, { st: ['s.', '.s'], x: 4, y: 5 }, { st: MLEAF_YOUNG, x: 5, y: 1 }]] },
    { w: 15, h: 13, lift: 0, faceH: 0, trunk: 0, shadow: [7, 2], layouts: [[
      { st: MLEAF, x: 3, y: 0, tone: 1 }, { st: MLEAF_YOUNG, x: 0, y: 7, t: 'f', tone: 1 },
      { st: ['.s.', 's.s'], x: 6, y: 11 }, { st: MLEAF_YOUNG, x: 9, y: 7 }]] }],
};

/** A kind at a growth stage, 0 a seedling to 3 mature: the stage's body in the kind's colours. */
export function floraDef(kind: FloraKind, stage: number): Def {
  const d = FLORA[kind];
  return stage >= FLORA_STAGES - 1 ? d : { ...YOUNGER[kind][Math.max(0, stage)], ladder: d.ladder, rib: d.rib };
}

// ---- composing a plant ---------------------------------------------------------------
// Quarter turns and flips are lossless on the lattice; the light goes on after.
const rot = (st: string[]): string[] => {
  const H = st.length, W = st[0].length, o: string[] = [];
  for (let r = 0; r < W; r++) { let row = ''; for (let c = 0; c < H; c++) row += st[H - 1 - c][r] ?? '.'; o.push(row); }
  return o;
};
const flip = (st: string[]): string[] => st.map(r => [...r].reverse().join(''));
function turned(st: string[], t = ''): string[] {
  let s = t.includes('f') ? flip(st) : st;
  for (let i = 0; i < (t.match(/r/g) || []).length; i++) s = rot(s);
  return s;
}

/** Cells of a plant, lit: "x,y" -> colour, in the crown's own frame. */
export function composeFlora(def: Def, layout: number, mirror: boolean): Map<string, string> {
  const parts = def.layouts[layout % def.layouts.length].map(p => {
    const st = turned(p.st, p.t);
    return mirror ? { ...p, st: flip(st), x: def.w - p.x - st[0].length } : { ...p, st };
  });
  const cells = new Map<string, string>(), part = new Map<string, number>();
  parts.forEach((p, i) => p.st.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch === '.') return;
    const k = (p.x + x) + ',' + (p.y + y);
    cells.set(k, ch); part.set(k, i);
  })));
  const L = def.ladder, m = new Map<string, string>();
  for (const [k, ch] of cells) {
    const [x, y] = k.split(',').map(Number), i = part.get(k)!;
    const tone = parts[i].tone ?? 0, at = (n: number) => L[Math.min(3, n + tone)];
    const up = cells.get(x + ',' + (y - 1));
    const upSame = up !== undefined && part.get(x + ',' + (y - 1)) === i;
    const dnSame = cells.has(x + ',' + (y + 1)) && part.get(x + ',' + (y + 1)) === i;
    let col: string;
    if (ch === '#') col = !upSame ? at(0) : !dnSame ? at(2) : at(1);
    else if (ch === 'm') col = tone ? at(1) : (def.rib ?? L[2]);
    else if (ch === 's') col = L[2];
    else if (ch === 'r') col = RED; // red stays red however far back: a dark trap reads as withered
    else if (ch === 'R') col = RED_LO;
    else if (ch === 'B') col = tone ? RED_LO : RED;
    else col = L[0]; // 'c', a cilium
    m.set(k, col);
  }
  // Where a nearer part lies over one behind it, the one behind darkens along
  // the edge: the overlap's shadow.
  for (const [k, i] of part) {
    const [x, y] = k.split(',').map(Number);
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const n = (x + dx) + ',' + (y + dy), j = part.get(n);
      if (j !== undefined && j < i && cells.get(n) !== 's') m.set(n, dy < 0 ? L[3] : OUTLINE);
    }
  }
  // The outline: the outside's only, selective.
  let x0 = 1e9, y0 = 1e9, x1 = -1e9, y1 = -1e9;
  for (const k of cells.keys()) { const [x, y] = k.split(',').map(Number); x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y); }
  const out = new Set([(x0 - 1) + ',' + (y0 - 1)]), q: Array<[number, number]> = [[x0 - 1, y0 - 1]];
  while (q.length) {
    const [x, y] = q.pop()!;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx, ny = y + dy, k = nx + ',' + ny;
      if (nx < x0 - 1 || ny < y0 - 1 || nx > x1 + 1 || ny > y1 + 1 || out.has(k) || cells.has(k)) continue;
      out.add(k); q.push([nx, ny]);
    }
  }
  for (const k of cells.keys()) {
    if (cells.get(k) === 'c') continue; // a cilium is its own edge
    const [x, y] = k.split(',').map(Number);
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const n = (x + dx) + ',' + (y + dy);
      if (out.has(n)) m.set(n, dy > 0 || dx > 0 ? OUTLINE : L[3]);
    }
  }
  return m;
}

/** A baked plant: its canvas, the foot's pixel in it, and the crown's box
 *  (canvas pixels) a body must be inside for the crown to veil it. */
export type FloraSprite = { cv: HTMLCanvasElement; fx: number; fy: number; box: [number, number, number, number] };
const sprites = new Map<string, FloraSprite>();

/** The plant drawn at the world's angle: the shadow on the ground at its foot,
 *  a trunk stub, the crown lifted by its height over a short band of shade
 *  under its south edge. One pixel an art-pixel. */
export function floraSprite(kind: FloraKind, layout: number, mirror: boolean, stage = FLORA_STAGES - 1): FloraSprite {
  const key = kind + ':' + layout + ':' + (mirror ? 1 : 0) + ':' + stage;
  const hit = sprites.get(key);
  if (hit) return hit;
  const d = floraDef(kind, stage);
  const m = composeFlora(d, layout, mirror);
  const pad = 2, W = Math.max(d.w, d.shadow[0] * 2 + 1) + pad * 2;
  const H = d.h + d.lift + d.faceH + d.shadow[1] + pad * 2 + 2;
  const fx = W >> 1, fy = H - pad - d.shadow[1] - 1;
  const ox = fx - (d.w >> 1), oy = fy - d.lift - d.h + 1;
  const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
  const g = cv.getContext('2d')!;
  g.fillStyle = SHADOW;
  const [rx, ry] = d.shadow;
  for (let y = -ry; y <= ry; y++) for (let x = -rx; x <= rx; x++) if (x * x / (rx * rx) + y * y / (ry * ry) <= 1) g.fillRect(fx + x, fy + y, 1, 1);
  if (d.trunk) {
    const h = d.lift + 1;
    for (let j = 0; j < h; j++) for (let i = 0; i < d.trunk; i++) {
      g.fillStyle = j >= h - 1 ? BARK[0] : i === 0 ? BARK[2] : BARK[1];
      g.fillRect(fx - (d.trunk >> 1) + i, fy - h + 1 + j, 1, 1);
    }
  }
  if (d.faceH) {
    const low = new Map<number, number>();
    for (const k of m.keys()) { const [x, y] = k.split(',').map(Number); low.set(x, Math.max(low.get(x) ?? -1e9, y)); }
    for (const [x, y] of low) for (let j = 1; j <= d.faceH; j++) {
      if (Math.abs(x - (d.w >> 1)) > (d.w >> 1) - 2 && j > 1) continue;
      g.fillStyle = j === d.faceH ? OUTLINE : d.ladder[3]; g.fillRect(ox + x, oy + y + j, 1, 1);
    }
  }
  let bx0 = 1e9, by0 = 1e9, bx1 = -1e9, by1 = -1e9;
  for (const [k, col] of m) {
    const [x, y] = k.split(',').map(Number);
    g.fillStyle = col; g.fillRect(ox + x, oy + y, 1, 1);
    bx0 = Math.min(bx0, ox + x); by0 = Math.min(by0, oy + y); bx1 = Math.max(bx1, ox + x + 1); by1 = Math.max(by1, oy + y + 1);
  }
  const s: FloraSprite = { cv, fx, fy, box: [bx0, by0, bx1, by1] };
  sprites.set(key, s);
  return s;
}

// ---- the level's plants ----------------------------------------------------------------
let rows: FloraRow[] = [];
let rowsLevel = -1;
/** Hands the layer a level's plants, sorted north to south so a plant nearer
 *  the viewer is drawn over one behind it. */
export function setFlora(level: number, plants: FloraRow[] | null): void {
  rows = (plants ?? []).filter(r => r[0] >= 0 && r[0] < FLORA_KINDS.length).slice().sort((a, b) => a[2] - b[2] || a[1] - b[1]);
  rowsLevel = level;
}

export type FloraDraw = { key: string; sprite: FloraSprite; x: number; y: number; w: number; h: number; alpha: number };

/**
 * The plants on screen, in draw order, each with where it lands and whether a
 * body is under its crown: an occupied crown draws at the concealment veil's
 * translucency (§4, sanctioned translucency 4), so a body beneath always
 * half-reads, exactly as in a thicket.
 */
export function floraInView(level: number, cam: { scale: number; screenX(x: number): number; screenY(y: number): number },
    cvW: number, cvH: number, bodies: Array<[number, number]>, veil: number): FloraDraw[] {
  const out: FloraDraw[] = [];
  if (level !== rowsLevel || rows.length === 0) return out;
  const px = cam.scale / ART; // screen px an art-pixel
  for (const [k, gx, gy, layout, mir, stage] of rows) {
    const kind = FLORA_KINDS[k], sp = floraSprite(kind, layout, mir === 1, stage ?? FLORA_STAGES - 1);
    const x = cam.screenX(gx / ART) - sp.fx * px, y = cam.screenY(gy / ART) - sp.fy * px;
    const w = sp.cv.width * px, h = sp.cv.height * px;
    if (x > cvW || y > cvH || x + w < 0 || y + h < 0) continue;
    // the crown's box in world art-pixels, a pixel of margin round it
    const bx0 = gx - sp.fx + sp.box[0] - 1, by0 = gy - sp.fy + sp.box[1] - 1;
    const bx1 = gx - sp.fx + sp.box[2] + 1, by1 = gy - sp.fy + sp.box[3] + 1;
    let alpha = 1;
    for (const [ax, ay] of bodies) if (ax >= bx0 && ax < bx1 && ay >= by0 && ay < by1) { alpha = veil; break; }
    out.push({ key: kind + ':' + layout + ':' + mir + ':' + stage, sprite: sp, x, y, w, h, alpha });
  }
  return out;
}
