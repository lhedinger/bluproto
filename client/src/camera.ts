// The viewport: a world-space centre + a zoom (device pixels per tile), with
// unified mouse/touch gestures via Pointer Events — drag to pan, wheel or
// two-finger pinch to zoom (anchored under the cursor/fingers), and a short
// still press counts as a tap. The map gestures follow Google Maps: a double
// tap zooms in, a two-finger tap zooms out, and a double tap whose second
// press drags zooms continuously (down in, up out). Panning and pinching break
// follow mode; the main loop re-centres the camera each frame while following,
// so the discrete zooms keep the followed body in view instead.

export interface Tap { x: number; y: number } // world tiles

export class Camera {
  /** Longest a still press can last and still count as a tap. */
  static readonly TAP_MS = 400;
  /** How long a still press must last to become a hold. Fires at this moment
   *  rather than on release — a long press that waits for your finger to lift
   *  is indistinguishable from a slow tap while it is happening. */
  static readonly HOLD_MS = 500;
  /** How long a tap waits for a second one before it counts as a single tap.
   *  This is the latency every single tap pays so that a double tap never
   *  selects or deselects on its first half -- the price Google Maps pays too. */
  static readonly DOUBLE_MS = 280;
  /** How far apart (device px) the two taps of a double tap may land. */
  static readonly DOUBLE_SLOP = 40;
  /** Drag distance (CSS px) of a double-tap-drag that doubles or halves the zoom. */
  static readonly DRAG_ZOOM_PX = 120;

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

  /** worldToScreen, one axis at a time: no object per call, for the entity
   *  walk that projects every body on the level every frame. */
  screenX(x: number): number {
    return this.canvas.width / 2 + (x - this.cx) * this.scale;
  }

  screenY(y: number): number {
    return this.canvas.height / 2 + (y - this.cy) * this.scale;
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

  private anim = 0;
  /** A discrete zoom step, eased over a quarter second rather than jumped:
   *  a jump loses the reader's place, an eased zoom shows where it went. The
   *  anchor stays put on screen throughout, and any new gesture cancels it. */
  private animateZoom(px: number, py: number, factor: number, ms = 260): void {
    cancelAnimationFrame(this.anim);
    const from = this.scale;
    const to = Math.min(this.maxScale, Math.max(this.minScale, from * factor));
    if (to === from) return;
    const t0 = performance.now();
    const step = (now: number) => {
      const k = Math.min(1, (now - t0) / ms);
      const e = 1 - Math.pow(1 - k, 3); // ease out
      this.zoomAround(px, py, from * Math.pow(to / from, e) / this.scale);
      if (k < 1) this.anim = requestAnimationFrame(step);
    };
    this.anim = requestAnimationFrame(step);
  }

  /**
   * Wires gestures.
   *
   * <p>{@code onTap} fires for a short, still press that no second tap follows
   * within {@link Camera.DOUBLE_MS}; {@code onHold} for a still press that
   * outlasts the tap window. Every still press resolves to exactly one of tap,
   * hold or the first half of a double tap. A press held past
   * {@link Camera.HOLD_MS} fires the hold at that moment rather than on
   * release, which is what makes it feel like a long press instead of a slow
   * tap; one released between the tap window and that deadline fires it on
   * release instead. Moving far enough to be a pan, or a second finger
   * arriving to pinch, cancels both.
   *
   * <p>The map gestures, as Google Maps has them:
   * <ul>
   * <li>Double tap: zoom in 2x around the tap, eased.</li>
   * <li>Two-finger tap (both down and up quickly, neither moving): zoom out 2x
   *     around the midpoint.</li>
   * <li>Double tap and drag: the second press zooms instead of panning, around
   *     where it landed -- drag down to zoom in, up to zoom out.</li>
   * </ul>
   * A single tap's EFFECT waits out the double-tap window. Fired at once, the
   * first half of every double tap selected or deselected whatever was under
   * it, so zooming in on a followed creature dropped the follow. But what the
   * tap hit is decided when the finger lifts: {@code onTap} is called then,
   * with the world point as drawn at that moment, and returns the action to
   * run once the window passes -- or to drop, if a second tap makes it a
   * double tap. Resolved after the wait instead, a followed camera had moved
   * and a fast creature had walked out of finger reach.
   */
  attach(onTap: (tap: Tap) => (() => void) | void, onHold?: (tap: Tap) => void): void {
    const cv = this.canvas;
    const dpr = () => cv.width / cv.clientWidth; // CSS px -> device px
    const pointers = new Map<number, { x: number; y: number }>();
    let tapStart: { x: number; y: number; t: number } | null = null;
    let pinchDist = 0;
    let holdTimer = 0;
    let held = false; // the hold already fired: the release must not tap as well
    // A finished tap waiting to learn whether a second one follows, with the
    // action it resolved to when the finger lifted.
    let pending: { x: number; y: number; act: (() => void) | void; timer: number } | null = null;
    // The current press is the second of a double tap; once it drags, it zooms.
    let second = false;
    // Once the second press drags: the anchor it zooms around, and the height
    // the finger was last at, so the zoom tracks the finger from where it landed.
    let dragZoom: { x: number; y: number; lastY: number } | null = null;
    // A two-finger tap in progress: when the second finger landed, and where
    // each finger was then. Any real movement or a slow lift voids it.
    let twoTap: { t: number; at: Map<number, { x: number; y: number }> } | null = null;
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
    /** Fires a waiting single tap now (another gesture is starting elsewhere). */
    const flushPending = () => {
      if (!pending) return;
      clearTimeout(pending.timer);
      const act = pending.act;
      pending = null;
      act?.();
    };

    cv.addEventListener('pointerdown', ev => {
      // A pointer that is already gone (a synthetic or cancelled one) cannot
      // be captured; the gesture still works without capture.
      try { cv.setPointerCapture(ev.pointerId); } catch { /* ignore */ }
      cancelAnimationFrame(this.anim); // a new gesture takes over from an easing zoom
      const p = { x: ev.clientX * dpr(), y: ev.clientY * dpr() };
      pointers.set(ev.pointerId, p);
      if (pointers.size === 1) {
        tapStart = { ...p, t: ev.timeStamp };
        held = false;
        cancelHold();
        twoTap = null;
        if (pending && Math.hypot(p.x - pending.x, p.y - pending.y) < Camera.DOUBLE_SLOP) {
          // The second half of a double tap: the first is not a tap after all.
          clearTimeout(pending.timer);
          pending = null;
          second = true;
          dragZoom = null;
          return; // no hold: a double tap held still is just a slow double tap
        }
        flushPending(); // a tap somewhere else: the waiting one was single
        second = false;
        holdTimer = window.setTimeout(fireHold, Camera.HOLD_MS);
      } else {
        // A second finger: a pinch, or a two-finger tap if both stay put. It
        // can only be a tap if the first finger was itself still a tap.
        twoTap = pointers.size === 2 && tapStart && !second
          ? { t: ev.timeStamp, at: new Map([...pointers].map(([id, q]) => [id, { ...q }])) }
          : null;
        tapStart = null;
        second = false;
        dragZoom = null;
        cancelHold();
        flushPending();
        const [a, b] = [...pointers.values()];
        pinchDist = Math.hypot(a.x - b.x, a.y - b.y);
      }
    });

    cv.addEventListener('pointermove', ev => {
      const p = pointers.get(ev.pointerId);
      if (!p) return;
      const np = { x: ev.clientX * dpr(), y: ev.clientY * dpr() };
      if (twoTap) {
        const at = twoTap.at.get(ev.pointerId);
        if (!at || Math.hypot(np.x - at.x, np.y - at.y) > 10) twoTap = null;
      }
      if (pointers.size === 1) {
        const dx = np.x - p.x, dy = np.y - p.y;
        if (tapStart && Math.hypot(np.x - tapStart.x, np.y - tapStart.y) > 10) {
          if (second && !dragZoom) dragZoom = { x: tapStart.x, y: tapStart.y, lastY: tapStart.y };
          tapStart = null; // moved too far: a drag, not a tap
          cancelHold();
        }
        if (dragZoom) {
          // The second press of a double tap drags the zoom, not the map,
          // counted from where it landed: the slop that told a drag from a
          // tap is part of the drag.
          this.zoomAround(dragZoom.x, dragZoom.y, Math.pow(2, (np.y - dragZoom.lastY) / dpr() / Camera.DRAG_ZOOM_PX));
          dragZoom.lastY = np.y;
        } else if (!tapStart) {
          this.cx -= dx / this.scale;
          this.cy -= dy / this.scale;
          this.followId = null; // panning takes the camera back
        }
      } else if (pointers.size === 2) {
        pointers.set(ev.pointerId, np);
        const [a, b] = [...pointers.values()];
        const d = Math.hypot(a.x - b.x, a.y - b.y);
        const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        // A two-finger tap's jitter is not a pinch. The distance the fingers
        // landed at is kept until the tap is ruled out, so the pinch that
        // follows catches up on the spread it made meanwhile, not loses it.
        if (!twoTap) {
          if (pinchDist > 0) this.zoomAround(mid.x, mid.y, d / pinchDist);
          this.followId = null;
          pinchDist = d;
        }
      }
      pointers.set(ev.pointerId, np);
    });

    // Durations are measured between the events' own timestamps -- when the
    // finger actually went down and came up -- not when their handlers ran. A
    // frame that stalls on a texture upload right after a zoom delays the
    // handlers, and timed by the handlers a quick tap read as a slow one.
    const end = (ev: PointerEvent) => {
      pointers.delete(ev.pointerId);
      pinchDist = 0;
      cancelHold();
      if (ev.type === 'pointercancel') twoTap = null;
      if (twoTap) {
        if (pointers.size > 0) return; // wait for the other finger
        const t = twoTap;
        twoTap = null;
        tapStart = null;
        if (ev.timeStamp - t.t < Camera.TAP_MS) {
          const [a, b] = [...t.at.values()];
          this.animateZoom((a.x + b.x) / 2, (a.y + b.y) / 2, 0.5); // two-finger tap: zoom out
        }
        return;
      }
      if (second) {
        if (!dragZoom && tapStart && ev.timeStamp - tapStart.t < Camera.TAP_MS) {
          this.animateZoom(tapStart.x, tapStart.y, 2); // double tap: zoom in on the point
        }
        second = false;
        dragZoom = null;
        tapStart = null;
        return;
      }
      if (held) {
        held = false; // the hold already answered for this gesture
        tapStart = null;
        return;
      }
      // Still held past the tap window but released before the hold deadline:
      // the user meant to hold, so honour it rather than dropping the gesture.
      if (tapStart && onHold && ev.timeStamp - tapStart.t >= Camera.TAP_MS) {
        onHold(this.screenToWorld(tapStart.x, tapStart.y));
        tapStart = null;
        return;
      }
      if (tapStart && ev.timeStamp - tapStart.t < Camera.TAP_MS) {
        const { x, y } = tapStart;
        const act = onTap(this.screenToWorld(x, y)); // what was hit, decided now
        pending = {
          x, y, act,
          timer: window.setTimeout(() => { pending = null; act?.(); }, Camera.DOUBLE_MS),
        };
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
      cancelAnimationFrame(this.anim);
      this.zoomAround(ev.clientX * dpr(), ev.clientY * dpr(), Math.pow(1.0015, -ev.deltaY));
    }, { passive: false });
  }
}
