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
 *  respawn), not motion, and must not widen the cull margin for everyone
 *  else. */
const TELEPORT_TILES = 2;
/** Updates the worst step is remembered over: enough that a quiet beat does
 *  not narrow the cull margin mid-chase, few enough that a sprint stops
 *  mattering soon after it ends. */
const STEP_MEMORY = 8;

export class WorldState {
  readonly tracks = new Map<number, Track>();
  tick = 0;
  /** The painter's-order array of every track, rebuilt lazily — see ordered(). */
  private sorted: Track[] = [];
  private sortedStale = true;
  /** Worst per-update step (tiles) of the last STEP_MEMORY updates, a ring. */
  private steps = new Float64Array(STEP_MEMORY);
  private stepAt = 0;

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
    let worst = 0;
    for (const e of msg.upsert) {
      const t = this.tracks.get(e.id);
      if (t) {
        const dx = e.x - t.curr.x, dy = e.y - t.curr.y;
        const d2 = dx * dx + dy * dy;
        if (d2 > worst && d2 <= TELEPORT_TILES * TELEPORT_TILES) worst = d2;
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
    this.steps[this.stepAt++ % STEP_MEMORY] = Math.sqrt(worst);
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
