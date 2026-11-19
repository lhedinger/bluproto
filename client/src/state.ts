// Client-side world state with snapshot interpolation. The server broadcasts
// at 5-10 Hz; rendering runs at 60 fps. Each body keeps a short history of the
// poses it was sent, stamped with the SIM TICK each was true at, and the
// renderer samples that history at a playhead running a fixed number of
// updates behind the newest -- so there are always two poses to move between.
//
// It used to keep two poses stamped with when their messages ARRIVED, and
// sampled 1.5 intervals in the past. Two poses cannot cover 1.5 intervals: for
// part of every interval the sample fell before the older pose and the body
// stood still, then jumped half a step when the next message landed, and
// network jitter went straight into the motion. Measured on 300 moving bodies,
// 37% of frames showed one standing still while it travelled (50% with
// 0-80 ms of jitter); stamped by tick and played from a buffer, 1.2% (2.7%).

import type { DeltaMsg, EntityState, FullMsg } from './protocol';
import { F_DEAD } from './protocol';

/** How many updates the playhead runs behind the newest one. Two keeps a
 *  pose ahead of it through one late or bunched message, at the cost of two
 *  intervals of latency (0.4 s at 5 Hz), which nobody feels watching a sim. */
export const PLAY_BEHIND = 2;
/** Poses remembered per body: the playhead needs the two around it, and two
 *  updates behind plus one late message is at most four. */
const HIST = 4;
/** How gently the playhead is steered back to its place, in ms: slow enough
 *  that jitter in when messages land never shows as a change of pace. */
const STEER_MS = 1000;

export interface Track {
  curr: EntityState;
  /** Up to HIST poses, oldest first, each `[tick, x, y, dir]`, laid out flat. */
  hist: Float64Array;
  /** How many poses `hist` holds. */
  n: number;
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
/** The engine's ceiling on one tick's movement, in tiles (half a tile: a body
 *  never steps past the tile beside it). */
const MAX_STEP = 0.5;
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

  /** The playhead, in sim ticks (fractional), and what steers it: the newest
   *  tick received, the tick rate and the ticks between messages, both
   *  measured from the stream. */
  private play = 0;
  private playAt = 0;
  private newest = 0;
  private rate = 33 / 1000; // ticks per ms, until measured
  private perMsg = 3;       // ticks between messages, until measured
  private lastMsgAt = 0;
  private msgMs = 100;
  /** Times the playhead jumped to its place (see playhead), published as
   *  __bluPerf.playSnaps: a steady stream should never make it count. */
  snaps = 0;

  applyFull(msg: FullMsg, now: number): void {
    this.tick = msg.tick;
    this.tracks.clear();
    for (const e of msg.entities) this.tracks.set(e.id, fresh(e, msg.tick));
    this.sortedStale = true;
    // A full snapshot starts the clock over: everything is where it was sent.
    this.newest = msg.tick;
    this.play = msg.tick;
    this.playAt = now;
    this.lastMsgAt = now;
  }

  applyDelta(msg: DeltaMsg, now: number): void {
    const before = this.tick; // the tick of the message before this one
    this.observe(msg.tick, now);
    this.tick = msg.tick;
    let worst = 0, moved = 0;
    const hist = this.hist;
    hist.fill(0);
    // Farther than a body can walk in the ticks this message spans is a
    // teleport. Not the pacer's fixed TELEPORT_TILES: at 4x one message spans
    // twenty ticks, a running body covers six tiles of them, and snapping it
    // there read as a body blinking across the map every update.
    const reach = Math.max(TELEPORT_TILES, MAX_STEP * (msg.tick - before) + 1);
    for (const e of msg.upsert) {
      const t = this.tracks.get(e.id);
      if (t) {
        const dx = e.x - t.curr.x, dy = e.y - t.curr.y;
        const d2 = dx * dx + dy * dy;
        if (d2 > reach * reach) {
          // A ramp or a respawn: arrive, do not glide across the map.
          t.n = 0;
        } else {
          if (d2 > 0 && d2 <= TELEPORT_TILES * TELEPORT_TILES) {
            if (d2 > worst) worst = d2;
            hist[Math.min(STEP_BUCKETS - 1, Math.floor(Math.sqrt(d2) * STEP_BUCKET))]++;
            moved++;
          }
          // A body absent from the last messages sat where it was until the
          // one before this: without that pose it would glide from wherever
          // it stopped, seconds ago, instead of starting to move now.
          if (t.n > 0 && t.hist[(t.n - 1) * 4] < before) push(t, before, t.curr);
        }
        push(t, msg.tick, e);
        t.curr = e;
      } else {
        this.tracks.set(e.id, fresh(e, msg.tick)); // born: snap
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

  /** Learns the stream's pace from one message: how many ticks it advanced
   *  and how long it took to come. A gap far longer than usual is a pause or
   *  a stall, not a pace, and teaches nothing. */
  private observe(tick: number, now: number): void {
    const dt = now - this.lastMsgAt, dk = tick - this.newest;
    this.lastMsgAt = now;
    if (dk > 0 && dt > 0 && dt < this.msgMs * 4 + 250) {
      this.rate += (dk / dt - this.rate) * 0.15;
      this.perMsg += (dk - this.perMsg) * 0.15;
      this.msgMs += (dt - this.msgMs) * 0.15;
    }
    if (tick > this.newest) this.newest = tick;
  }

  /**
   * The playhead for this frame, in sim ticks: where every body is sampled.
   * It runs at the measured tick rate and is steered gently toward its place,
   * PLAY_BEHIND updates behind the newest, so a message landing early or late
   * changes nothing on screen. It never runs past the newest pose (no guessing
   * ahead: a paused world simply stops there), never runs backwards, and jumps
   * to its place only when it is far out -- a new world, a long stall.
   */
  playhead(now: number): number {
    const dt = Math.max(0, Math.min(250, now - this.playAt));
    this.playAt = now;
    const want = this.newest - PLAY_BEHIND * this.perMsg;
    let p = this.play + dt * this.rate;
    const off = want - p;
    if (Math.abs(off) > 3 * this.perMsg + 10) { p = want; this.snaps++; }
    else p += off * (1 - Math.exp(-dt / STEER_MS));
    if (p < this.play && Math.abs(off) <= 3 * this.perMsg + 10) p = this.play; // never backwards
    if (p > this.newest) p = this.newest;
    this.play = p;
    return p;
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

  /** Interpolated pose for one body at the playhead `at` (a tick, from
   *  playhead()), written into `out` (a module scratch by default: take the
   *  fields before the next call, or pass your own when two poses must
   *  coexist). Before its oldest pose a body is where it was first seen;
   *  past its newest it holds there -- a body the stream stopped mentioning
   *  is a body that did not move. */
  sample(t: Track, at: number, out: Pose = SCRATCH): Pose {
    const h = t.hist, n = t.n;
    if (n === 0 || at >= h[(n - 1) * 4]) {
      out.x = t.curr.x; out.y = t.curr.y; out.dir = t.curr.dir;
      return out;
    }
    if (at <= h[0]) {
      out.x = h[1]; out.y = h[2]; out.dir = h[3];
      return out;
    }
    let i = n - 2;
    while (i > 0 && h[i * 4] > at) i--;
    const o = i * 4, a = (at - h[o]) / (h[o + 4] - h[o]);
    out.x = h[o + 1] + (h[o + 5] - h[o + 1]) * a;
    out.y = h[o + 2] + (h[o + 6] - h[o + 2]) * a;
    out.dir = lerpAngle(h[o + 3], h[o + 7], a);
    return out;
  }
}

/** A track for a body first seen at `tick`: one pose, so it is drawn where it
 *  is until a second arrives. */
function fresh(e: EntityState, tick: number): Track {
  const t: Track = { curr: e, hist: new Float64Array(HIST * 4), n: 0 };
  push(t, tick, e);
  return t;
}

/** Appends a pose, dropping the oldest once HIST are held. A pose for the
 *  same tick as the newest replaces it. */
function push(t: Track, tick: number, e: EntityState): void {
  const h = t.hist;
  if (t.n > 0 && h[(t.n - 1) * 4] >= tick) t.n--;
  if (t.n === HIST) { h.copyWithin(0, 4); t.n--; }
  const o = t.n * 4;
  h[o] = tick; h[o + 1] = e.x; h[o + 2] = e.y; h[o + 3] = e.dir;
  t.n++;
}

const SCRATCH: Pose = { x: 0, y: 0, dir: 0 };

/** Shortest-arc angle interpolation (a creature turning 350°→10° sweeps 20°). */
function lerpAngle(a: number, b: number, t: number): number {
  let d = (b - a) % (Math.PI * 2);
  if (d > Math.PI) d -= Math.PI * 2;
  if (d < -Math.PI) d += Math.PI * 2;
  return a + d * t;
}
