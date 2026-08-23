// The viewport: a world-space centre + a zoom (device pixels per tile), with
// unified mouse/touch gestures via Pointer Events — drag to pan, wheel or
// two-finger pinch to zoom (anchored under the cursor/fingers), and a short
// still press counts as a tap. Panning breaks follow mode; the main loop
// re-centres the camera each frame while following.

export interface Tap { x: number; y: number } // world tiles

export class Camera {
  /** Longest a still press can last and still count as a tap. */
  static readonly TAP_MS = 400;
  /** How long a still press must last to become a hold. Fires at this moment
   *  rather than on release — a long press that waits for your finger to lift
   *  is indistinguishable from a slow tap while it is happening. */
  static readonly HOLD_MS = 500;

  cx = 0; // world centre, tiles
  cy = 0;
  scale = 32; // device px per tile
  followId: number | null = null;

  private minScale = 2;
  private maxScale = 256;

  constructor(private canvas: HTMLCanvasElement) {}

  /** Fits the whole world in view (initial framing). */
  fit(cols: number, rows: number): void {
    this.cx = cols / 2;
    this.cy = rows / 2;
    this.scale = Math.min(this.canvas.width / cols, this.canvas.height / rows);
    this.minScale = this.scale * 0.5;
  }

  worldToScreen(x: number, y: number): { x: number; y: number } {
    return {
      x: this.canvas.width / 2 + (x - this.cx) * this.scale,
      y: this.canvas.height / 2 + (y - this.cy) * this.scale,
    };
  }

  screenToWorld(px: number, py: number): { x: number; y: number } {
    return {
      x: this.cx + (px - this.canvas.width / 2) / this.scale,
      y: this.cy + (py - this.canvas.height / 2) / this.scale,
    };
  }

  private zoomAround(px: number, py: number, factor: number): void {
    const before = this.screenToWorld(px, py);
    this.scale = Math.min(this.maxScale, Math.max(this.minScale, this.scale * factor));
    const after = this.screenToWorld(px, py);
    this.cx += before.x - after.x; // keep the anchor point stationary on screen
    this.cy += before.y - after.y;
  }

  /**
   * Wires gestures.
   *
   * <p>{@code onTap} fires for short, still presses; {@code onHold} for a still
   * press that outlasts the tap window. The two are complementary — every still
   * press resolves to exactly one of them — so there is no length of press that
   * does nothing. A press held past {@link Camera.HOLD_MS} fires the hold at
   * that moment rather than on release, which is what makes it feel like a long
   * press instead of a slow tap; one released between the tap window and that
   * deadline fires it on release instead.
   *
   * <p>Both are cancelled by the same things: moving far enough to be a pan, or
   * a second finger arriving to pinch.
   */
  attach(onTap: (tap: Tap) => void, onHold?: (tap: Tap) => void): void {
    const cv = this.canvas;
    const dpr = () => cv.width / cv.clientWidth; // CSS px -> device px
    const pointers = new Map<number, { x: number; y: number }>();
    let tapStart: { x: number; y: number; t: number } | null = null;
    let pinchDist = 0;
    let lastTapT = 0, lastTapX = 0, lastTapY = 0; // for double-tap detection
    let holdTimer = 0;
    let held = false; // the hold already fired: the release must not tap as well
    const cancelHold = () => {
      if (holdTimer) {
        clearTimeout(holdTimer);
        holdTimer = 0;
      }
    };
    const fireHold = () => {
      cancelHold();
      if (!tapStart || held || !onHold) {
        return;
      }
      held = true;
      onHold(this.screenToWorld(tapStart.x, tapStart.y));
    };

    cv.addEventListener('pointerdown', ev => {
      cv.setPointerCapture(ev.pointerId);
      const p = { x: ev.clientX * dpr(), y: ev.clientY * dpr() };
      pointers.set(ev.pointerId, p);
      if (pointers.size === 1) {
        tapStart = { ...p, t: performance.now() };
        held = false;
        cancelHold();
        holdTimer = window.setTimeout(fireHold, Camera.HOLD_MS);
      } else {
        tapStart = null; // second finger: this is a pinch, not a tap
        cancelHold();
        const [a, b] = [...pointers.values()];
        pinchDist = Math.hypot(a.x - b.x, a.y - b.y);
      }
    });

    cv.addEventListener('pointermove', ev => {
      const p = pointers.get(ev.pointerId);
      if (!p) return;
      const np = { x: ev.clientX * dpr(), y: ev.clientY * dpr() };
      if (pointers.size === 1) {
        const dx = np.x - p.x, dy = np.y - p.y;
        if (tapStart && Math.hypot(np.x - tapStart.x, np.y - tapStart.y) > 10) {
          tapStart = null; // moved too far: a drag, not a tap
          cancelHold();
        }
        if (!tapStart) {
          this.cx -= dx / this.scale;
          this.cy -= dy / this.scale;
          this.followId = null; // panning takes the camera back
        }
      } else if (pointers.size === 2) {
        pointers.set(ev.pointerId, np);
        const [a, b] = [...pointers.values()];
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        if (pinchDist > 0) this.zoomAround(mid.x, mid.y, d / pinchDist);
        pinchDist = d;
        this.followId = null;
      }
      pointers.set(ev.pointerId, np);
    });

    const end = (ev: PointerEvent) => {
      pointers.delete(ev.pointerId);
      pinchDist = 0;
      cancelHold();
      if (held) {
        held = false; // the hold already answered for this gesture
        tapStart = null;
        return;
      }
      // Still held past the tap window but released before the hold deadline:
      // the user meant to hold, so honour it rather than dropping the gesture.
      if (tapStart && onHold && performance.now() - tapStart.t >= Camera.TAP_MS) {
        onHold(this.screenToWorld(tapStart.x, tapStart.y));
        tapStart = null;
        return;
      }
      if (tapStart && performance.now() - tapStart.t < Camera.TAP_MS) {
        const now = performance.now();
        const near = Math.hypot(tapStart.x - lastTapX, tapStart.y - lastTapY) < 40;
        if (now - lastTapT < 300 && near) {
          this.zoomAround(tapStart.x, tapStart.y, 1.8); // double-tap: zoom in on the point
          lastTapT = 0;
        } else {
          lastTapT = now;
          lastTapX = tapStart.x;
          lastTapY = tapStart.y;
          onTap(this.screenToWorld(tapStart.x, tapStart.y));
        }
      }
      tapStart = null;
    };
    // A long press on a touch screen otherwise raises the platform's own
    // context menu on top of the world. The gesture is the viewer's now.
    cv.addEventListener('contextmenu', ev => ev.preventDefault());
    cv.addEventListener('pointerup', end);
    cv.addEventListener('pointercancel', end);

    cv.addEventListener('wheel', ev => {
      ev.preventDefault();
      this.zoomAround(ev.clientX * dpr(), ev.clientY * dpr(), Math.pow(1.0015, -ev.deltaY));
    }, { passive: false });
  }
}
