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
 *  bloom red the rest of the world's flesh wears, fat the sand ramp, the coat
 *  a pale fur a step lighter than the fat it wraps, so the two bands never
 *  read as one. Each pair is {bright, dim}: an edge and a fill. */
const SCAN = {
  bg: '#0b0f17', grid: '#10192a', band: '#12303a',
  skin: ['#D0ECFF', '#2b6a78'] as const,
  hurt: ['#E0455F', '#7c2434'] as const,
  fat: ['#d6b16a', '#6e5f42'] as const,
  gutGrass: ['#5f9850', '#2a4d24'] as const,
  gutBlood: ['#E0455F', '#7c2434'] as const,
  tank: ['#7d96c8', '#38466e'] as const,
  water: ['#63becd', '#2b6a78'] as const,
  coat: ['#c0aa7e', '#98865c'] as const,
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

/** How a body stands the air, when it has a lineage that says: its comfort
 *  band in degrees, how much of its warmth it makes, how much coat it wears,
 *  and the air it is in right now -- the tile's temperature, its comfort
 *  (-1 at the cold edge of the band .. +1 at the hot edge) and the pace the
 *  air leaves it (1 for a warm-blooded body, less for a cold-blooded one
 *  outside its band). */
export interface Thermal {
  optimum: number; breadth: number; thermogenesis: number; insulation: number;
  temperature: number; comfort: number; pace: number;
}

export interface BodyState {
  hunger: number; thirst: number; fat: number; glycogen: number; health: number;
  /** Absent for a body with no lineage (an item, a corpse). */
  thermal?: Thermal;
}

/** The thermometer's reach, in degrees: the span a lineage's optimum may
 *  draw from, which also covers the coldest coolant run and the mouth of a
 *  vent. A reading past either end sits at that end. */
export const THERMO_MIN = -5, THERMO_MAX = 45;

/** Rows the thermometer strip adds under the labelled scan. */
export const THERMO_ROWS = 13;

/** The word for a comfort reading: at home inside the band, cool or warm in
 *  its outer halves, cold or hot past its edges. One copy, shared with the
 *  inspector's climate line. */
export function comfortWord(c: number): string {
  return c <= -1 ? 'cold' : c >= 1 ? 'hot' : c < -0.5 ? 'cool' : c > 0.5 ? 'warm' : 'at home';
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
function drawBodyScan(g: CanvasRenderingContext2D, plan: Plan, s: BodyState,
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
  // the coat: a dithered ring of fur two cells out from the skin, as dense as
  // the insulation gene -- a bare body draws nothing, a full coat a thick pelt
  // that still never reads as a second skin
  const coat = s.thermal ? clamp01(s.thermal.insulation) : 0;
  if (coat > 0.02) {
    const near = (x: number, y: number) => body(x + 1, y) || body(x - 1, y) || body(x, y + 1) || body(x, y - 1);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (body(x, y) || near(x, y)) continue;
      if ((near(x + 1, y) || near(x - 1, y) || near(x, y + 1) || near(x, y - 1)) && bayer(x, y) < coat * 0.75) cell(x, y, SCAN.coat[1]);
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
  if (s.thermal) drawThermometer(g, s.thermal, W, H, px, font);
}

/**
 * The thermometer strip under the labelled scan: a rail from
 * {@link THERMO_MIN} to {@link THERMO_MAX} degrees, the lineage's comfort band
 * as a bright segment on it with its optimum marked, and the air the body is
 * in as a tick above -- skin-coloured inside the band, water-blue below it,
 * hurt-red above. Two lines of text say the rest: the comfort word, the
 * degrees, and the pace the air leaves the body; then the two genes the
 * picture cannot carry as shape, how warm-blooded it is and how much coat.
 */
function drawThermometer(g: CanvasRenderingContext2D, t: Thermal, W: number, H: number, px: number, font: number): void {
  const cell = (x: number, y: number, c: string) => { g.fillStyle = c; g.fillRect(x * px, y * px, px, px); };
  const x0 = 4, x1 = W - 5;                       // the rail's cells
  const col = (deg: number) => Math.round(x0 + (x1 - x0) * clamp01((deg - THERMO_MIN) / (THERMO_MAX - THERMO_MIN)));
  const rail = H + 3;
  for (let x = x0; x <= x1; x++) cell(x, rail, SCAN.grid);
  for (let deg = 0; deg <= 40; deg += 10) { cell(col(deg), rail - 1, SCAN.band); cell(col(deg), rail + 1, SCAN.band); }
  // the band: bright edges, dim fill, the optimum a bright cell in the middle
  const lo = col(t.optimum - t.breadth), hi = col(t.optimum + t.breadth);
  for (let x = lo; x <= hi; x++) cell(x, rail, x === lo || x === hi ? SCAN.fat[0] : SCAN.fat[1]);
  cell(col(t.optimum), rail, SCAN.fat[0]);
  // the air: a three-cell tick standing on the rail
  const tx = col(t.temperature);
  const tc = t.comfort <= -1 ? SCAN.water[0] : t.comfort >= 1 ? SCAN.hurt[0] : SCAN.skin[0];
  for (let y = rail - 3; y < rail; y++) cell(tx, y, tc);
  g.textBaseline = 'top';
  g.textAlign = 'left';
  const lx = x0 * px, ly = (rail + 3) * px;
  const d = (v: number) => `${Math.round(v)}`;
  const pct = (v: number) => `${Math.round(clamp01(v) * 100)}%`;
  g.fillStyle = SCAN.label;
  g.fillText('warmth', lx, ly);
  g.fillStyle = tc;
  const pace = t.pace < 0.995 ? ` · pace ${pct(t.pace)}` : '';
  g.fillText(`${comfortWord(t.comfort)} · ${d(t.temperature)} °C in a ${d(t.optimum - t.breadth)}–${d(t.optimum + t.breadth)} band${pace}`,
      lx + font * 4.5, ly);
  g.fillStyle = SCAN.label;
  g.fillText(`warm-blooded ${pct(t.thermogenesis)} · coat ${pct(t.insulation)}`, lx + font * 4.5, ly + font + 2);
}

/** The body's books as the scan reads them, from an inspector detail payload.
 *  Hunger and thirst arrive normalised (0 sated .. 1 starving/parched), fat as
 *  a share of what the body can carry, glycogen against its capacity, health
 *  out of 100. A payload with no books (an item, a corpse) reads as sated. */
export function stateOf(d: Record<string, any>): BodyState {
  const num = (v: unknown, dflt: number) => typeof v === 'number' && Number.isFinite(v) ? v : dflt;
  const cap = num(d.glycogenCap, 0);
  const gm = d.genome;
  const thermal: Thermal | undefined = gm && typeof gm.thermalOptimum === 'number' && typeof d.comfort === 'number' ? {
    optimum: gm.thermalOptimum, breadth: num(gm.thermalBreadth, 1),
    thermogenesis: num(gm.thermogenesis, 1), insulation: num(gm.insulation, 0),
    temperature: num(d.temperature, gm.thermalOptimum), comfort: d.comfort, pace: num(d.pace, 1),
  } : undefined;
  return {
    hunger: num(d.hunger, SATED.hunger),
    thirst: num(d.thirst, SATED.thirst),
    fat: num(d.fat, SATED.fat),
    glycogen: cap > 0 ? num(d.glycogen, 0) / cap : SATED.glycogen,
    health: 'health' in d ? num(d.health, 100) / 100 : 1,
    thermal,
  };
}

/** A fresh canvas carrying the scan, sized to its cells. */
export function bodyScanCanvas(plan: Plan, s: BodyState, W: number, H: number, px: number, labels: boolean): HTMLCanvasElement {
  const cv = document.createElement('canvas');
  cv.width = W * px;
  cv.height = (H + (labels && s.thermal ? THERMO_ROWS : 0)) * px;
  drawBodyScan(cv.getContext('2d')!, plan, s, W, H, px, labels);
  return cv;
}
