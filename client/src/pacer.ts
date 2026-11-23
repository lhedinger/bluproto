// Frame pacing in whole display refreshes.
//
// The display shows a new picture only on a refresh, and repeats the old one
// when none is ready. A pacer that draws "when 40 ms have passed" therefore
// lands on 33 ms one frame and 50 the next, depending on where in a refresh
// the 40 falls: the 33, 50, 33, 50 cadence that reads as stutter, though the
// average rate is what was asked. The usual answer (Android's Frame Pacing
// library does the same) is to pace in refreshes rather than milliseconds:
// draw once N refreshes have passed, N a whole number, so every frame stands
// for exactly as long as the one before it. Refreshes passed are read from
// the rAF timestamps, which fall on refreshes, not by counting rAF calls: a
// heavy frame delays the next call, and a count would then drift.
//
// N is the larger of two numbers, each followed with hysteresis (a value
// that flickers between two rates would be the uneven cadence again, one
// level up):
//
// - what the motion on screen asks for (`standMs`, from main.ts). Faster
//   takes effect at once, because a body that starts moving must not step;
//   slower only after HOLD_MS.
// - what the device can draw (`floor`). A frame that costs 1.3 refreshes,
//   asked for on every refresh, lands on one refresh or two by chance. The
//   cost of a drawn frame is the gap from its rAF call to the next one, in
//   whole refreshes -- the browser cannot call again until the frame's work
//   is through -- and the floor follows its average both ways after
//   HOLD_MS. It is measured on every drawn frame whatever N is, so it comes
//   down as soon as the scene gets cheaper, with no probing.

/** How long a new rate must be wanted before the pacer slows down to it. */
const HOLD_MS = 500;
/** rAF intervals kept to measure the refresh period. */
const KEEP = 64;

export class Pacer {
  private gaps = new Float64Array(KEEP);
  private gapN = 0;
  private lastRaf = 0;
  private drewLast = false;
  private refresh = 1000 / 60;
  private lastDraw = 0;
  /** Draw once this many refreshes have passed. */
  every = 1;
  private slowerAt = 0; // when a slower rate was first wanted, 0 if it is not
  /** The fewest refreshes per frame the device keeps up with. */
  floor = 1;
  private cost = 1; // EMA of a drawn frame's cost, refreshes
  private floorAt = 0; // when the floor was first wrong, 0 if it is not

  /** The display's refresh period, measured: many phones run at 90 or 120 Hz.
   *  A low quantile of recent rAF intervals, because a drawn frame's interval
   *  is as long as its work; the refreshes the pacer skips show the period. */
  get refreshMs(): number { return this.refresh; }

  /**
   * Called once per animation frame. `standMs` is how long a drawn picture
   * may stand before motion would show; `force` draws as soon as the device
   * can (the camera moved: a pan or a pinch never waits on the motion rate).
   * Returns whether this refresh draws.
   */
  frame(now: number, standMs: number, force: boolean): boolean {
    if (this.lastRaf > 0) {
      const gap = now - this.lastRaf;
      if (gap > 0 && gap < 250) { // a backgrounded tab's gap teaches nothing
        this.gaps[this.gapN++ % KEEP] = gap;
        if (this.gapN >= 8) {
          const n = Math.min(this.gapN, KEEP);
          const s = Array.from(this.gaps.subarray(0, n)).sort((a, b) => a - b);
          this.refresh = s[Math.floor(n * 0.1)];
        }
        if (this.drewLast) this.cost += (Math.max(1, Math.round(gap / this.refresh)) - this.cost) * 0.2;
      }
    }
    this.lastRaf = now;
    this.followFloor(now);

    // Half a refresh of slack, so a stand of 33.0 ms on a 16.67 ms display
    // reads as two refreshes, not one.
    const want = Math.max(this.floor, Math.floor((standMs + this.refresh * 0.5) / this.refresh));
    if (want < this.every) {
      this.every = want;
      this.slowerAt = 0;
    } else if (want > this.every) {
      if (this.slowerAt === 0) this.slowerAt = now;
      else if (now - this.slowerAt >= HOLD_MS) { this.every = want; this.slowerAt = 0; }
    } else {
      this.slowerAt = 0;
    }

    const n = force ? this.floor : this.every;
    this.drewLast = this.lastDraw === 0 || now >= this.lastDraw + (n - 0.5) * this.refresh;
    if (this.drewLast) this.lastDraw = now;
    return this.drewLast;
  }

  /** The floor follows the measured cost after HOLD_MS, with a dead band:
   *  up once more than 20% of frames take a refresh past it, down only once
   *  fewer than 10% would at one refresh less. A cost sitting on a boundary
   *  would otherwise flip the floor back and forth. */
  private followFloor(now: number): void {
    const c = this.cost;
    const fit = c > this.floor + 0.2 ? Math.ceil(c - 0.2)
      : c < this.floor - 0.9 ? Math.max(1, Math.ceil(c - 0.1)) : this.floor;
    if (fit === this.floor) { this.floorAt = 0; return; }
    if (this.floorAt === 0) { this.floorAt = now; return; }
    if (now - this.floorAt < HOLD_MS) return;
    this.floor = fit;
    this.floorAt = 0;
    if (this.every < fit) this.every = fit;
  }
}
