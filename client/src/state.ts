// Client-side world state with snapshot interpolation. The server broadcasts
// ~10 Hz; rendering runs at 60 fps. Each entity keeps its previous and current
// received states, and the renderer samples the world a fixed delay behind
// "now", lerping between the two — so motion is smooth even though updates are
// sparse, at the cost of ~150 ms of visual latency nobody can feel in a sim.

import type { DeltaMsg, EntityState, FullMsg } from './protocol';
import { F_DEAD } from './protocol';

/** How far behind real time the renderer samples (≈1.5 broadcast intervals). */
export const RENDER_DELAY_MS = 150;

export interface Track {
  prev: EntityState;
  tPrev: number;
  curr: EntityState;
  tCurr: number;
  /** Last atlas heading column drawn, for the renderer's turn hysteresis —
   *  pose memory lives here because the Track object survives deltas. */
  col?: number;
}

/** An interpolated pose. The renderer samples thousands of these a frame, so
 *  `sample` writes into a caller-owned one instead of allocating — see there. */
export interface Pose { x: number; y: number; dir: number }

/**
 * Painter's rank of an entity: haze under fixtures under doors and corpses
 * under items under living bodies. Both render paths walk the population in
 * this order, with a secondary key on phenotype: a GPU batcher draws
 * consecutive quads from the SAME texture in one call, and each phenotype's
 * atlas is its own texture, so interleaved phenotypes break every batch.
 * Within a class the paint order is otherwise arbitrary, so grouping by atlas
 * is free.
 */
export function drawRank(e: EntityState): number {
  return e.kind === 'phero' ? 0
    : e.kind.startsWith('switch.') || e.kind === 'nest' ? 1
    : e.kind.startsWith('door.') ? 2
    : (e.flags & F_DEAD) ? 2 : e.kind.startsWith('item.') ? 3 : 4;
}

/** Steps longer than this between two poses are a teleport (a ramp, a
 *  respawn), not motion, and must not widen the cull margin or hurry the
 *  frame pacing for everyone else. */
const TELEPORT_TILES = 2;
/** Updates a step measure is remembered over: enough that a quiet beat does
 *  not drop the pace mid-chase, few enough that a sprint stops mattering soon
 *  after it ends. */
const STEP_MEMORY = 8;
/** Step histogram resolution for the pacer's percentile: 1/16-tile buckets up
 *  to the teleport bound. */
const STEP_BUCKET = 16;
const STEP_BUCKETS = TELEPORT_TILES * STEP_BUCKET;
/** The share of moving bodies the pacer keeps under its pixel budget. */
const PACE_QUANTILE = 0.9;

export class WorldState {
  readonly tracks = new Map<number, Track>();
  tick = 0;
  /** The painter's-order array of every track, rebuilt lazily — see ordered(). */
  private sorted: Track[] = [];
  private sortedStale = true;
  /** Worst per-update step (tiles) of the last STEP_MEMORY updates, a ring,
   *  and beside it the PACE_QUANTILE step of each — see maxStep / paceStep. */
  private steps = new Float64Array(STEP_MEMORY);
  private paceSteps = new Float64Array(STEP_MEMORY);
  private stepAt = 0;
  private hist = new Uint32Array(STEP_BUCKETS);

  applyFull(msg: FullMsg, now: number): void {
    this.tick = msg.tick;
    this.tracks.clear();
    for (const e of msg.entities) {
      this.tracks.set(e.id, { prev: e, tPrev: now, curr: e, tCurr: now });
    }
    this.sortedStale = true;
  }

  applyDelta(msg: DeltaMsg, now: number): void {
    this.tick = msg.tick;
    let worst = 0, moved = 0;
    const hist = this.hist;
    hist.fill(0);
    for (const e of msg.upsert) {
      const t = this.tracks.get(e.id);
      if (t) {
        const dx = e.x - t.curr.x, dy = e.y - t.curr.y;
        const d2 = dx * dx + dy * dy;
        if (d2 > 0 && d2 <= TELEPORT_TILES * TELEPORT_TILES) {
          if (d2 > worst) worst = d2;
          hist[Math.min(STEP_BUCKETS - 1, Math.floor(Math.sqrt(d2) * STEP_BUCKET))]++;
          moved++;
        }
        t.prev = t.curr;
        t.tPrev = t.tCurr;
        t.curr = e;
        t.tCurr = now;
      } else {
        this.tracks.set(e.id, { prev: e, tPrev: now, curr: e, tCurr: now }); // born: snap
      }
    }
    for (const id of msg.gone) {
      this.tracks.delete(id);
    }
    // The quantile step: the bucket the PACE_QUANTILE'th mover falls in, by
    // its upper edge.
    let pace = 0;
    if (moved > 0) {
      const want = PACE_QUANTILE * moved;
      let seen = 0;
      for (let b = 0; b < STEP_BUCKETS; b++) {
        seen += hist[b];
        if (seen >= want) { pace = (b + 1) / STEP_BUCKET; break; }
      }
    }
    const slot = this.stepAt++ % STEP_MEMORY;
    this.steps[slot] = Math.sqrt(worst);
    this.paceSteps[slot] = pace;
    // Any delta can change the order: a birth, a death (corpses rank under
    // the living), a body that walked a ramp in. Sorting once per delta is
    // the saving — the stream beats at 5-10 Hz, the frame at 60.
    this.sortedStale = true;
  }

  /**
   * Every track in painter's order (drawRank, then phenotype). The array is
   * rebuilt only after a message changed the population, so a frame walks it
   * for free instead of spreading and sorting the whole map — at a few
   * thousand bodies the sort was most of the frame's JS. Callers must not
   * hold it across a message.
   */
  ordered(): Track[] {
    if (this.sortedStale) {
      this.sorted = [...this.tracks.values()]
        .sort((a, b) => drawRank(a.curr) - drawRank(b.curr) || a.curr.pheno - b.curr.pheno);
      this.sortedStale = false;
    }
    return this.sorted;
  }

  /** The furthest any body moved between two recent updates, in tiles — the
   *  bound on how far an interpolated pose can sit from its received one, so
   *  a cull on the received pose stays exact with this much margin. */
  maxStep(): number {
    let m = 0;
    for (let i = 0; i < STEP_MEMORY; i++) if (this.steps[i] > m) m = this.steps[i];
    return m;
  }

  /** How far a typical fast body moves between updates, in tiles: the
   *  PACE_QUANTILE step over recent updates. The frame pacer budgets screen
   *  motion against THIS rather than maxStep: one sprinting hunter would
   *  otherwise hold the whole map view at full frame rate, and what it gives
   *  up is that the fastest tenth of the bodies may step a few pixels between
   *  frames at the far zoom instead of a fraction of one. */
  paceStep(): number {
    let m = 0;
    for (let i = 0; i < STEP_MEMORY; i++) if (this.paceSteps[i] > m) m = this.paceSteps[i];
    return m;
  }

  /** Interpolated pose for one entity at the (delayed) render time, written
   *  into `out` (a module scratch by default: take the fields before the next
   *  call, or pass your own when two poses must coexist). */
  sample(t: Track, renderTime: number, out: Pose = SCRATCH): Pose {
    const span = t.tCurr - t.tPrev;
    if (span <= 0) {
      out.x = t.curr.x; out.y = t.curr.y; out.dir = t.curr.dir;
      return out;
    }
    const a = Math.min(1, Math.max(0, (renderTime - t.tPrev) / span));
    out.x = t.prev.x + (t.curr.x - t.prev.x) * a;
    out.y = t.prev.y + (t.curr.y - t.prev.y) * a;
    out.dir = lerpAngle(t.prev.dir, t.curr.dir, a);
    return out;
  }
}

const SCRATCH: Pose = { x: 0, y: 0, dir: 0 };

/** Shortest-arc angle interpolation (a creature turning 350°→10° sweeps 20°). */
function lerpAngle(a: number, b: number, t: number): number {
  let d = (b - a) % (Math.PI * 2);
  if (d > Math.PI) d -= Math.PI * 2;
  if (d < -Math.PI) d += Math.PI * 2;
  return a + d * t;
}
