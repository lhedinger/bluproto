// The body scan: a creature's cross-section as a lit scan on a cell grid,
// read by the inspector's body tab and the entity card. Every mark is a cell;
// "glow" is a bright outline cell and one dim halo cell beside it, never a
// blur. The drawing is a pure function of the body plan, the five live
// numbers it gauges, and nothing else -- there is no RNG and no wall clock in
// here, so the same creature in the same state is the same picture.
//
// What it shows, and why as organs rather than bars: the four plans differ in
// what they carry. A grazer is mostly gut and wears a fat band; a hunter has a
// small gut and the biggest glycogen tank, because a chase is paid from the
// store; a scavenger has long feelers and a wide gut; a parasite has a
// proboscis, hooks under the belly, and no water of its own -- it drinks its
// host's. Those are facts of the simulation (see Mechanics), and a cross
// section says them in one look where five bars say five numbers.
//
// Rejected on the way here, so nobody redraws them: dithered organ fills read
// as speckle at four pixels a cell; a checkered fat band was busier than the
// organ it framed; organs attached to the wall read as growths; a blurred
// vector scan pixelated afterwards came out muddy. What survived is the
// starving body's look -- outline, band, organs, nothing else -- with solid
// dim fills and bright edges.

/** The world's own colours. The skin and leaders are the crystal glint and the
 *  shallows ramp, the tank the crystal ramp, the gut the grass ramp or the
 *  bloom red the rest of the world's flesh wears, fat the sand ramp. Each pair
 *  is {bright, dim}: an edge and a fill. */
export const SCAN = {
  bg: '#0b0f17', grid: '#10192a', band: '#12303a',
  skin: ['#D0ECFF', '#2b6a78'] as const,
  hurt: ['#E0455F', '#7c2434'] as const,
  fat: ['#d6b16a', '#6e5f42'] as const,
  gutGrass: ['#5f9850', '#2a4d24'] as const,
  gutBlood: ['#E0455F', '#7c2434'] as const,
  tank: ['#7d96c8', '#38466e'] as const,
  water: ['#63becd', '#2b6a78'] as const,
  mind: '#ffffff', mouth: '#F0E8C6', sense: '#2b6a78', label: '#8b93a3',
};

export type Shade = readonly [string, string];

/** An organ: its centre and radii as fractions of the grid, so one plan draws
 *  at every cell size. */
export type Organ = [number, number, number, number];

export interface Plan {
  rx: number; ry: number; wob: number; seed: number;
  diet: 'grass' | 'meat' | 'blood';
  gut: Organ; tank: Organ; water: Organ | null;
  mind: [number, number];
  /** Authored cell runs, as grid fractions: feelers, mouth, hooks. */
  sense: [number, number][]; mouth: [number, number][]; grip: [number, number][];
}

export interface BodyState {
  hunger: number; thirst: number; fat: number; glycogen: number; health: number;
}

/** The sated reference state, for the catalog and for a body with no books. */
export const SATED: BodyState = { hunger: 0.1, thirst: 0.15, fat: 1, glycogen: 0.85, health: 1 };

/** One plan per trophic role. Organs sit toward the middle of the body with
 *  clear space to the wall; the gut is what differs most, so it is what the
 *  eye lands on. Fractions are of the grid, not the body, so a wider body
 *  carries its organs where a narrower one does. */
export const PLANS: Record<string, Plan> = {
  herbivore: { rx: 0.30, ry: 0.30, wob: 0.10, seed: 1, diet: 'grass',
    gut: [0.54, 0.47, 0.15, 0.15], tank: [0.70, 0.60, 0.035, 0.06], water: [0.38, 0.60, 0.03, 0.07],
    mind: [0.27, 0.42], sense: [[0.24, 0.36], [0.25, 0.38]],
    mouth: [[0.19, 0.52], [0.20, 0.52], [0.21, 0.52]], grip: [] },
  predator: { rx: 0.33, ry: 0.25, wob: 0.14, seed: 5, diet: 'meat',
    gut: [0.44, 0.48, 0.07, 0.10], tank: [0.62, 0.48, 0.08, 0.11], water: [0.34, 0.58, 0.025, 0.06],
    mind: [0.25, 0.44], sense: [[0.21, 0.38], [0.22, 0.40], [0.18, 0.48], [0.20, 0.47]],
    // two fangs, each a two-cell drop below the jaw line
    mouth: [[0.18, 0.56], [0.18, 0.60], [0.18, 0.64], [0.23, 0.56], [0.23, 0.60], [0.23, 0.64]], grip: [] },
  scavenger: { rx: 0.31, ry: 0.29, wob: 0.22, seed: 9, diet: 'meat',
    gut: [0.53, 0.46, 0.12, 0.12], tank: [0.70, 0.60, 0.03, 0.05], water: [0.38, 0.60, 0.03, 0.06],
    mind: [0.28, 0.44],
    // three feelers: authored diagonals from the brow, long enough to read as scent
    sense: [[0.25, 0.38], [0.23, 0.34], [0.21, 0.30], [0.19, 0.26], [0.17, 0.22],
            [0.24, 0.42], [0.21, 0.41], [0.18, 0.40], [0.15, 0.39], [0.12, 0.38],
            [0.25, 0.46], [0.22, 0.48], [0.19, 0.50], [0.16, 0.52]],
    mouth: [[0.19, 0.50], [0.17, 0.46], [0.15, 0.42], [0.19, 0.56], [0.17, 0.60], [0.15, 0.64]], grip: [] },
  parasite: { rx: 0.17, ry: 0.20, wob: 0.26, seed: 13, diet: 'blood',
    gut: [0.54, 0.48, 0.055, 0.075], tank: [0.62, 0.58, 0.02, 0.035], water: null,
    mind: [0.40, 0.46], sense: [[0.36, 0.38], [0.37, 0.40], [0.33, 0.46], [0.35, 0.47]],
    mouth: [[0.35, 0.52], [0.33, 0.52], [0.31, 0.52], [0.29, 0.52], [0.27, 0.52], [0.25, 0.52], [0.23, 0.52], [0.21, 0.52]],
    grip: [[0.44, 0.72], [0.44, 0.76], [0.50, 0.73], [0.50, 0.77], [0.56, 0.73], [0.56, 0.77], [0.62, 0.72], [0.62, 0.76]] },
};

/** The plan a body wears, by its trophic role; a grazer for anything else. */
export function planFor(role: string | undefined): Plan {
  return PLANS[role ?? ''] ?? PLANS.herbivore;
}

/** A blob on the cell grid: inside if the wobbled radius test passes. The
 *  wobble is a fixed function of the angle and the plan's seed, so each plan
 *  is its own blob and the same blob every time. */
function inside(x: number, y: number, cx: number, cy: number, rx: number, ry: number, wob: number, seed: number): boolean {
  const dx = (x - cx) / rx, dy = (y - cy) / ry;
  const a = Math.atan2(dy, dx);
  const w = 1 + wob * Math.sin(a * 3 + seed) * 0.6 + wob * Math.cos(a * 5 + seed * 2) * 0.4;
  return dx * dx + dy * dy <= w * w;
}

/** Ordered dither, the world's own Bayer 4x4, for the halo and the scan band. */
function bayer(x: number, y: number): number {
  const M = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]];
  return M[y & 3][x & 3] / 16;
}

const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

/**
 * Draws the scan into {@code g}: a grid of {@code W} by {@code H} cells at
 * {@code px} device pixels each. With {@code labels} the margins carry the
 * callouts (gut, fat, water, glycogen, hp) with leaders drawn as cell runs
 * from the skin outward, so nothing crosses an organ; without, it is the card
 * thumbnail, the body alone.
 */
export function drawBodyScan(g: CanvasRenderingContext2D, plan: Plan, s: BodyState,
    W: number, H: number, px: number, labels: boolean): void {
  const cell = (x: number, y: number, c: string) => { g.fillStyle = c; g.fillRect(x * px, y * px, px, px); };
  g.fillStyle = SCAN.bg;
  g.fillRect(0, 0, W * px, H * px);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if ((x % 8 === 0) || (y % 8 === 0)) cell(x, y, SCAN.grid);
  // the scan band: two dim dithered rows
  const sb = Math.floor(H * 0.62);
  for (let x = 0; x < W; x++) {
    if (bayer(x, sb) < 0.5) cell(x, sb, SCAN.band);
    if (bayer(x, sb + 1) < 0.25) cell(x, sb + 1, SCAN.band);
  }
  const cx = W * 0.52, cy = H * 0.5, rx = W * plan.rx, ry = H * plan.ry;
  const body = (x: number, y: number) => inside(x, y, cx, cy, rx, ry, plan.wob, plan.seed);
  const health = clamp01(s.health);
  const skin = health > 0.5 ? SCAN.skin : SCAN.hurt;
  // fat: one solid dim band just inside the skin, there while the store is
  if (clamp01(s.fat) > 0.05) {
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (inside(x, y, cx, cy, rx * 0.90, ry * 0.87, plan.wob, plan.seed)
          && !inside(x, y, cx, cy, rx * 0.86, ry * 0.83, plan.wob, plan.seed)) cell(x, y, SCAN.fat[1]);
    }
  }
  // organs: a gauge filled from the bottom, bright edge and dim fill; the
  // empty part is the dim outline alone
  const organ = (o: Organ, seed: number, col: Shade, level: number) => {
    const [ox, oy, orx, ory] = [W * o[0], H * o[1], W * o[2], H * o[3]];
    const in_ = (x: number, y: number) => inside(x, y, ox, oy, orx, ory, 0.2, seed);
    const top = oy + ory * 1.25 - 2 * ory * 1.25 * clamp01(level);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) if (in_(x, y)) {
      const edge = !(in_(x + 1, y) && in_(x - 1, y) && in_(x, y + 1) && in_(x, y - 1));
      if (y >= top) cell(x, y, edge ? col[0] : col[1]);
      else if (edge) cell(x, y, col[1]);
    }
  };
  organ(plan.gut, plan.seed + 1, plan.diet === 'grass' ? SCAN.gutGrass : SCAN.gutBlood, 1 - clamp01(s.hunger));
  organ(plan.tank, plan.seed + 2, SCAN.tank, s.glycogen);
  if (plan.water) organ(plan.water, plan.seed + 3, SCAN.water, 1 - clamp01(s.thirst));
  // skin: outline cells bright, one dithered ring outside dim -- the glow
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    if (body(x, y)) {
      if (!(body(x + 1, y) && body(x - 1, y) && body(x, y + 1) && body(x, y - 1))) cell(x, y, skin[0]);
    } else if (body(x + 1, y) || body(x - 1, y) || body(x, y + 1) || body(x, y - 1)) {
      if (bayer(x, y) < 0.6) cell(x, y, skin[1]);
    }
  }
  // the authored marks: feelers, mouth, hooks, and the mind
  for (const [x, y] of plan.sense) cell(Math.round(W * x), Math.round(H * y), SCAN.sense);
  for (const [x, y] of plan.mouth) cell(Math.round(W * x), Math.round(H * y), SCAN.mouth);
  for (const [x, y] of plan.grip) cell(Math.round(W * x), Math.round(H * y), SCAN.sense);
  cell(Math.round(W * plan.mind[0]), Math.round(H * plan.mind[1]), SCAN.mind);
  if (!labels) return;
  // callouts: a leader from the skin's far side to the margin, then the label
  const gutC = plan.diet === 'grass' ? SCAN.gutGrass : SCAN.gutBlood;
  const pct = (v: number) => `${Math.round(clamp01(v) * 100)}%`;
  const callouts: [number, number, number, number, Shade, string, string][] = [
    [0.70, 0.22, 0.79, 0.14, gutC, 'gut', pct(1 - s.hunger)],
    [0.74, 0.76, 0.79, 0.84, SCAN.tank, 'glycogen', pct(s.glycogen)],
    [0.30, 0.76, 0.10, 0.86, SCAN.water, 'water', plan.water ? pct(1 - s.thirst) : 'host'],
    [0.28, 0.24, 0.10, 0.14, SCAN.fat, 'fat', pct(s.fat)],
    [0.86, 0.46, 0.90, 0.46, skin, 'hp', `${Math.round(health * 100)}`],
  ];
  const font = Math.max(9, Math.round(px * 2.5));
  g.font = `${font}px ui-monospace, Menlo, Consolas, monospace`;
  g.textBaseline = 'top';
  for (const [x0, y0, x1, y1, col, k, v] of callouts) {
    let x = Math.round(W * x0), y = Math.round(H * y0);
    const tx = Math.round(W * x1), ty = Math.round(H * y1);
    while (y !== ty) { cell(x, y, col[1]); y += ty > y ? 1 : -1; }
    while (x !== tx) { cell(x, y, col[1]); x += tx > x ? 1 : -1; }
    cell(tx, ty, col[1]);
    const left = x1 > 0.5;
    g.textAlign = left ? 'left' : 'right';
    // the label hangs off the leader's end, outward: below-right or below-left
    // of it for the corner callouts, beside it for the one on the flank
    const flank = Math.abs(y1 - 0.5) < 0.1;
    const lx = left ? (tx + 1) * px : (tx - 1) * px;
    const ly = flank ? (ty - 1) * px - font : (ty - 3) * px;
    g.fillStyle = SCAN.label;
    g.fillText(k, lx, ly);
    g.fillStyle = col[0];
    g.fillText(v, lx, ly + font + 1);
  }
}

/** The body's books as the scan reads them, from an inspector detail payload.
 *  Hunger and thirst arrive normalised (0 sated .. 1 starving/parched), fat as
 *  a share of what the body can carry, glycogen against its capacity, health
 *  out of 100. A payload with no books (an item, a corpse) reads as sated. */
export function stateOf(d: Record<string, any>): BodyState {
  const num = (v: unknown, dflt: number) => typeof v === 'number' && Number.isFinite(v) ? v : dflt;
  const cap = num(d.glycogenCap, 0);
  return {
    hunger: num(d.hunger, SATED.hunger),
    thirst: num(d.thirst, SATED.thirst),
    fat: num(d.fat, SATED.fat),
    glycogen: cap > 0 ? num(d.glycogen, 0) / cap : SATED.glycogen,
    health: 'health' in d ? num(d.health, 100) / 100 : 1,
  };
}

/** A fresh canvas carrying the scan, sized to its cells. */
export function bodyScanCanvas(plan: Plan, s: BodyState, W: number, H: number, px: number, labels: boolean): HTMLCanvasElement {
  const cv = document.createElement('canvas');
  cv.width = W * px;
  cv.height = H * px;
  drawBodyScan(cv.getContext('2d')!, plan, s, W, H, px, labels);
  return cv;
}
