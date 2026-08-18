// Client-side world state with snapshot interpolation. The server broadcasts
// ~10 Hz; rendering runs at 60 fps. Each entity keeps its previous and current
// received states, and the renderer samples the world a fixed delay behind
// "now", lerping between the two — so motion is smooth even though updates are
// sparse, at the cost of ~150 ms of visual latency nobody can feel in a sim.

import type { DeltaMsg, EntityState, FullMsg } from './protocol';

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

export class WorldState {
  readonly tracks = new Map<number, Track>();
  tick = 0;

  applyFull(msg: FullMsg, now: number): void {
    this.tick = msg.tick;
    this.tracks.clear();
    for (const e of msg.entities) {
      this.tracks.set(e.id, { prev: e, tPrev: now, curr: e, tCurr: now });
    }
  }

  applyDelta(msg: DeltaMsg, now: number): void {
    this.tick = msg.tick;
    for (const e of msg.upsert) {
      const t = this.tracks.get(e.id);
      if (t) {
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
  }

  /** Interpolated pose for one entity at the (delayed) render time. */
  sample(t: Track, renderTime: number): { x: number; y: number; dir: number } {
    const span = t.tCurr - t.tPrev;
    if (span <= 0) {
      return { x: t.curr.x, y: t.curr.y, dir: t.curr.dir };
    }
    const a = Math.min(1, Math.max(0, (renderTime - t.tPrev) / span));
    return {
      x: t.prev.x + (t.curr.x - t.prev.x) * a,
      y: t.prev.y + (t.curr.y - t.prev.y) * a,
      dir: lerpAngle(t.prev.dir, t.curr.dir, a),
    };
  }
}

/** Shortest-arc angle interpolation (a creature turning 350°→10° sweeps 20°). */
function lerpAngle(a: number, b: number, t: number): number {
  let d = (b - a) % (Math.PI * 2);
  if (d > Math.PI) d -= Math.PI * 2;
  if (d < -Math.PI) d += Math.PI * 2;
  return a + d * t;
}
