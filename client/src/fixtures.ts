// Fixture and marker art: switches, doors, nests, items, rings, the carry
// link, the loading placeholder and the action glyphs. Split out of render.ts;
// render.ts re-exports it.

import { ART } from './artCore';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_CARRY, ACT_FLEE, ACT_GRAZE, ACT_HUNT, ACT_MATE,
  ACT_NEST, ACT_RIDE,
} from './protocol';
import type { EntityState } from './protocol';

// The pheromone puff's soft radial falloff, baked once and blitted scaled: a
// per-frame createRadialGradient allocates and rasterises a fresh gradient for
// every puff on screen. The haze must stay smooth (sanctioned translucency #3
// keeps the TINT soft), so the blit re-enables smoothing for just this draw.
let pheroSprite: HTMLCanvasElement | null = null;

export function pheroPuff(): HTMLCanvasElement {
  if (!pheroSprite) {
    pheroSprite = document.createElement('canvas');
    pheroSprite.width = 64;
    pheroSprite.height = 64;
    const pg = pheroSprite.getContext('2d')!;
    const grad = pg.createRadialGradient(32, 32, 0, 32, 32, 32);
    grad.addColorStop(0, 'rgba(230,40,190,0.20)');
    grad.addColorStop(1, 'rgba(230,40,190,0)');
    pg.fillStyle = grad;
    pg.fillRect(0, 0, 64, 64);
  }
  return pheroSprite;
}

/**
 * A switch, test-chamber style: an indicator trail of dotted lights from
 * the switch to its wired door (x-leg first, matching the Swing renderer)
 * — dim while idle, lit pale-blue while the circuit is closed (aux = 1) —
 * then the control itself over the baked pedestal base. 'switch.plate' is
 * the broad red floor button, sinking flush and dark while weighted;
 * 'switch.button' is a small domed red cap on a dark pedestal that only a
 * deliberate press operates. `door` is the wired door's track, looked up
 * by the id in `pheno`; without it (not yet streamed) only the control
 * draws.
 */
export function drawSwitch(
  g: CanvasRenderingContext2D, cam: Camera, e: EntityState, door?: EntityState,
): void {
  const sc = cam.scale;
  const cx = e.x + 0.5, cy = e.y + 0.5;
  const pressed = e.aux >= 0.5;
  if (door) {
    const lr = Math.abs(door.dir % (Math.PI / 2)) > 1e-6;
    const half = Math.max(1, door.size) / 2;
    const dx = lr ? door.x : door.x + half;
    const dy = lr ? door.y + half : door.y;
    const w = Math.max(2, sc * 0.09);
    const a = cam.worldToScreen(cx, cy);
    const k = cam.worldToScreen(dx, cy); // the L's corner: x-leg first
    const b = cam.worldToScreen(dx, dy);
    // Indicator lamps along both legs: a dark housing around a lens, dim
    // while idle, lit while the circuit is closed — readable on any floor.
    const legs: Array<[number, number, number, number]> = [[a.x, a.y, k.x, k.y], [k.x, k.y, b.x, b.y]];
    for (const [x0, y0, x1, y1] of legs) {
      const len = Math.hypot(x1 - x0, y1 - y0);
      const n = Math.floor(len / (sc * 0.34));
      for (let i = 1; i <= n; i++) {
        const t = i / (n + 1);
        const sx = x0 + (x1 - x0) * t, sy = y0 + (y1 - y0) * t;
        g.fillStyle = '#14161f';
        g.fillRect(sx - w, sy - w, w * 2, w * 2);
        g.fillStyle = pressed ? '#D0ECFF' : '#6a7280';
        g.fillRect(sx - w / 2, sy - w / 2, w, w);
      }
    }
    // A soft glow under the lit trail's endpoint at the door.
    if (pressed) {
      g.fillStyle = 'rgba(208,236,255,0.25)';
      g.beginPath(); g.arc(b.x, b.y, sc * 0.3, 0, 7); g.fill();
    }
  }
  const o = cam.worldToScreen(cx, cy);
  if (e.kind === 'switch.plate') {
    // The broad floor button: proud and bright when armed, flush and dark
    // while weighted.
    const r = Math.max(3, sc * 0.27);
    g.fillStyle = pressed ? '#7c2434' : '#E0455F';
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.fill();
    if (!pressed) {
      g.fillStyle = '#F0788C';
      g.beginPath(); g.arc(o.x - r * 0.2, o.y - r * 0.3, r * 0.45, 0, 7); g.fill();
    }
    g.strokeStyle = '#23262e';
    g.lineWidth = 1;
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.stroke();
  } else {
    // The pedestal button: a small domed cap a body must choose to press.
    const b = Math.max(3, sc * 0.2);
    g.fillStyle = '#2c3037';
    g.fillRect(o.x - b, o.y - b, b * 2, b * 2);
    const r = Math.max(2, sc * 0.12);
    g.fillStyle = pressed ? '#7c2434' : '#E0455F';
    g.beginPath(); g.arc(o.x, o.y, r, 0, 7); g.fill();
    if (!pressed) {
      g.fillStyle = '#F0788C';
      g.beginPath(); g.arc(o.x - r * 0.25, o.y - r * 0.35, r * 0.4, 0, 7); g.fill();
    } else {
      g.strokeStyle = '#D0ECFF';
      g.lineWidth = Math.max(1, sc * 0.03);
      g.strokeRect(o.x - b, o.y - b, b * 2, b * 2);
    }
  }
}

/**
 * A door: two leaves sliding along the doorway from its ends toward the
 * middle, mirroring the Swing renderer. The anchor tile is (x,y); a non-zero
 * `dir` means the bar runs north-south (sealing east-west passage), zero
 * east-west. `size` carries the doorway span in tiles and `aux` how far each
 * leaf reaches toward the middle (1 sealed .. 0.15 open stubs) — so the wire
 * updates animate the slide for free. Flavours: segmented steel with a
 * hazard-striped nose (blast), see-through bars (grate), planked timber,
 * coursed stone, woven hedge — all as chunky slabs with a drop shadow.
 */
export function drawDoor(g: CanvasRenderingContext2D, cam: Camera, e: EntityState): void {
  const lr = Math.abs(e.dir % (Math.PI / 2)) > 1e-6;
  const span = Math.max(1, e.size);
  const ext = Math.max(0.15, Math.min(1, e.aux));
  const reach = (span / 2) * ext; // leaf length from each end, tiles
  const blast = e.kind === 'door.blast';
  const grate = e.kind === 'door.grate';
  const th = (blast ? 5 : 3) / ART; // bar thickness, tiles
  const col = '#' + e.rgb.toString(16).padStart(6, '0');
  const sc = cam.scale;
  const seam = 'rgba(0,0,0,0.45)';
  const leaves: Array<[number, number, number]> = [
    [0, reach, +1], // [start, end, direction the leaf's nose faces]
    [span - reach, span, -1],
  ];
  for (const [a0, a1, nose] of leaves) {
    if (a1 - a0 <= 0.01) continue;
    const wx = lr ? e.x - th / 2 : e.x + a0;
    const wy = lr ? e.y + a0 : e.y - th / 2;
    const o = cam.worldToScreen(wx, wy);
    const pw = (lr ? th : a1 - a0) * sc;
    const ph = (lr ? a1 - a0 : th) * sc;
    // Drop shadow south, so the slab visibly sits on the floor.
    g.fillStyle = 'rgba(0,0,0,0.35)';
    g.fillRect(o.x + sc * 0.02, o.y + sc * 0.07, pw, ph);
    if (grate) {
      // Bars with honest gaps: the eye (like the sim) sees through a grate.
      g.fillStyle = col;
      const step = sc / 6;
      if (lr) {
        for (let y = o.y; y + step * 0.55 <= o.y + ph; y += step) {
          g.fillRect(o.x, y, pw, Math.max(1, step * 0.55));
        }
      } else {
        for (let x = o.x; x + step * 0.55 <= o.x + pw; x += step) {
          g.fillRect(x, o.y, Math.max(1, step * 0.55), ph);
        }
      }
      continue;
    }
    g.fillStyle = col;
    g.fillRect(o.x, o.y, pw, ph);
    g.strokeStyle = seam;
    g.lineWidth = Math.max(1, sc * 0.03);
    g.strokeRect(o.x, o.y, pw, ph);
    // Segment/plank seams across the leaf.
    const step = sc / 3;
    g.beginPath();
    if (lr) {
      for (let y = o.y + step; y < o.y + ph - 1; y += step) {
        g.moveTo(o.x, y); g.lineTo(o.x + pw, y);
      }
    } else {
      for (let x = o.x + step; x < o.x + pw - 1; x += step) {
        g.moveTo(x, o.y); g.lineTo(x, o.y + ph);
      }
    }
    g.stroke();
    if (blast) {
      // The hazard-striped crush edge where the leaves meet.
      const nb = Math.max(2, sc * 0.12);
      g.fillStyle = '#d8b028';
      if (lr) {
        const y = nose > 0 ? o.y + ph - nb : o.y;
        g.fillRect(o.x, y, pw, nb);
      } else {
        const x = nose > 0 ? o.x + pw - nb : o.x;
        g.fillRect(x, o.y, nb, ph);
      }
    }
  }
}

/** The nest stamp, 11x9 art-pixels — hand-authored pixel art, identical to
 *  the Java NestPainter's STAMP so /sprites shows the pipelines agreeing.
 *  H/M/D are the mud ramp's twig tones, S the rare straw accent, o the
 *  blocky translucent hollow. */
const NEST_STAMP = [
  '...HHSHH...',
  '..HHMMMHH..',
  '.MMoooooMM.',
  '.MSoooooMM.',
  '.MMoooooMD.',
  '.MMoooooDD.',
  '.DMoooooDD.',
  '..DDMMMDD..',
  '...DDDDD...',
];
const NEST_SHADES: Record<string, string> = {
  H: '#775a38', M: '#574024', D: '#38291a', S: '#8a6a3c', o: 'rgba(20,14,8,0.35)',
};

/** A nest: the authored stamp scaled to one art-pixel per cell — drawn
 *  sprite-style, no computed geometry, no smooth curves (ART-STYLE.md). */
export function drawNest(g: CanvasRenderingContext2D, x: number, y: number, sc: number): void {
  const p = Math.max(1, sc / ART); // one art-pixel on screen
  const x0 = x - (NEST_STAMP[0].length / 2) * p;
  const y0 = y - (NEST_STAMP.length / 2) * p;
  for (let row = 0; row < NEST_STAMP.length; row++) {
    for (let col = 0; col < NEST_STAMP[row].length; col++) {
      const c = NEST_SHADES[NEST_STAMP[row][col]];
      if (!c) continue;
      // Edge-exact blocks: the translucent hollow must tile without overlap,
      // or the double-tinted seams read as grid lines.
      const rx = Math.round(x0 + col * p), ry = Math.round(y0 + row * p);
      g.fillStyle = c;
      g.fillRect(rx, ry, Math.round(x0 + (col + 1) * p) - rx,
        Math.round(y0 + (row + 1) * p) - ry);
    }
  }
}

export function drawItem(g: CanvasRenderingContext2D, kind: string, x: number, y: number, r: number, col: string): void {
  g.fillStyle = col;
  if (kind === 'item.crate') {
    g.fillRect(x - r, y - r, r * 2, r * 2);
    g.strokeStyle = '#6E4824';
    g.lineWidth = Math.max(1, r * 0.22);
    g.strokeRect(x - r, y - r, r * 2, r * 2);
    if (r > 5) { // braces only when big enough to read
      g.beginPath();
      g.moveTo(x - r, y - r); g.lineTo(x + r, y + r);
      g.moveTo(x + r, y - r); g.lineTo(x - r, y + r);
      g.stroke();
    }
  } else if (kind === 'item.hazard') {
    g.beginPath();
    for (let i = 0; i < 16; i++) {
      const a = (Math.PI * i) / 8;
      const rr = (i % 2 ? 0.45 : 1) * r * 1.3;
      if (i === 0) g.moveTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
      else g.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr);
    }
    g.closePath();
    g.fill();
    g.strokeStyle = '#D83A4A';
    g.lineWidth = Math.max(1, r * 0.18);
    g.stroke();
  } else { // food: berry with a leaf
    g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
    g.fillStyle = '#4C8A33';
    g.beginPath(); g.arc(x + r * 0.4, y - r * 0.9, Math.max(1.5, r * 0.4), 0, 7); g.fill();
  }
}

/**
 * The viewer's status rings — grab (warm amber, being held), carry (cool cyan,
 * holding), follow (soft white pulse on the tracked creature), selected
 * (steady amber, the inspected one). Part of the overlay language, not world
 * art: smooth strokes are allowed here, and radius + colour together keep the
 * four meanings apart. `timeMs` drives the follow pulse only.
 */
export function drawRing(g: CanvasRenderingContext2D,
    kind: 'grabbed' | 'carrying' | 'follow' | 'selected',
    x: number, y: number, r: number, timeMs = 0): void {
  switch (kind) {
    case 'grabbed':
      g.strokeStyle = 'rgba(255,160,60,0.9)';
      g.lineWidth = Math.max(1, r * 0.2);
      g.beginPath(); g.arc(x, y, r * 1.4, 0, 7); g.stroke();
      return;
    case 'carrying':
      g.strokeStyle = 'rgba(0,229,255,0.6)';
      g.lineWidth = 1;
      g.beginPath(); g.arc(x, y, r * 1.3, 0, 7); g.stroke();
      return;
    case 'follow': {
      const pulse = 1.6 + 0.25 * Math.sin(timeMs / 180);
      g.strokeStyle = 'rgba(255,255,255,0.8)';
      g.lineWidth = 2;
      g.beginPath(); g.arc(x, y, r * pulse + 4, 0, 7); g.stroke();
      return;
    }
    case 'selected':
      g.strokeStyle = 'rgba(255,214,64,0.95)';
      g.lineWidth = Math.max(2, r * 0.18);
      g.beginPath(); g.arc(x, y, r * 1.7 + 3, 0, 7); g.stroke();
  }
}

/** The tether between a carrier and its cargo, drawn under both bodies. */
export function drawCarryLink(g: CanvasRenderingContext2D,
    x0: number, y0: number, x1: number, y1: number, scale: number): void {
  g.strokeStyle = 'rgba(0,229,255,0.55)';
  g.lineWidth = Math.max(1, scale * 0.02);
  g.beginPath(); g.moveTo(x0, y0); g.lineTo(x1, y1); g.stroke();
}

/** The pre-atlas placeholder: a coloured dot with a heading wedge, worn while
 *  a creature's sprite atlas is still in flight. */
export function drawPlaceholder(g: CanvasRenderingContext2D, x: number, y: number,
    r: number, dir: number, col: string): void {
  g.fillStyle = col;
  g.beginPath(); g.arc(x, y, r, 0, 7); g.fill();
  g.strokeStyle = 'rgba(0,0,0,0.45)';
  g.lineWidth = 1;
  g.stroke();
  g.fillStyle = 'rgba(255,255,255,0.85)';
  g.beginPath();
  g.moveTo(x + Math.cos(dir) * r * 1.35, y + Math.sin(dir) * r * 1.35);
  g.lineTo(x + Math.cos(dir + 2.5) * r * 0.55, y + Math.sin(dir + 2.5) * r * 0.55);
  g.lineTo(x + Math.cos(dir - 2.5) * r * 0.55, y + Math.sin(dir - 2.5) * r * 0.55);
  g.closePath(); g.fill();
}

/** Smallest on-screen body radius (device px) that earns an action badge. Below
 *  this the shape cannot be told apart from a coloured dot, so drawing it is
 *  noise; the map-overview zoom sits well under it. */
export const GLYPH_MIN_BODY_PX = 5;

/** Colour for each action badge — matched to the Java snapshot renderer's palette
 *  so a scenario PNG and the live viewer read the same way. */
const ACTION_COLOUR: Record<number, string> = {
  [ACT_ATTACK]: '#E63C3C',
  [ACT_FLEE]: '#F0BE3C',
  [ACT_MATE]: '#F05AB4',
  [ACT_AFFILIATE]: '#46C8DC',
  [ACT_GRAZE]: '#46C85A',
  [ACT_NEST]: '#DC3CC8',
  [ACT_CARRY]: '#F09632',
  // Cool against the carrier's warm orange: at a glance the pair reads as two
  // ends of one act rather than two creatures doing the same thing.
  [ACT_RIDE]: '#96BEFF',
  [ACT_HUNT]: '#FF7828',
};

/**
 * A small icon hovering over a creature saying what it is doing right now: `!`
 * attacking, heart mating, chevron fleeing, asterisk grazing, crosshair hunting,
 * hook carrying, house homing to a nest. Shapes mirror the Java snapshot
 * renderer's, so a test PNG and the live world are read the same way.
 *
 * Drawn on a dark disc so it stays legible over grass, water or a pale body.
 */
export function drawActionGlyph(g: CanvasRenderingContext2D, cx: number, cy: number,
                                u: number, action: number): void {
  if (!action) return; // nothing worth showing (the zoom gate is at the call site)
  const col = ACTION_COLOUR[action];
  if (!col) return;

  g.fillStyle = 'rgba(12,14,18,0.55)';
  g.beginPath(); g.arc(cx, cy, u * 1.15, 0, 7); g.fill();

  g.strokeStyle = col;
  g.fillStyle = col;
  g.lineWidth = Math.max(1, u * 0.28);
  g.lineCap = 'round';
  g.beginPath();
  switch (action) {
    case ACT_ATTACK: // exclamation
      g.moveTo(cx, cy - u * 0.6); g.lineTo(cx, cy + u * 0.15);
      g.stroke();
      g.beginPath(); g.arc(cx, cy + u * 0.55, Math.max(1, u * 0.16), 0, 7); g.fill();
      return;
    case ACT_FLEE: // chevron, pointing away
      g.moveTo(cx - u * 0.55, cy + u * 0.3);
      g.lineTo(cx, cy - u * 0.4);
      g.lineTo(cx + u * 0.55, cy + u * 0.3);
      g.stroke();
      return;
    case ACT_MATE: { // heart
      const t = u * 0.62;
      g.moveTo(cx, cy + t * 0.85);
      g.bezierCurveTo(cx - t * 1.7, cy - t * 0.35, cx - t * 0.55, cy - t * 1.3, cx, cy - t * 0.35);
      g.bezierCurveTo(cx + t * 0.55, cy - t * 1.3, cx + t * 1.7, cy - t * 0.35, cx, cy + t * 0.85);
      g.fill();
      return;
    }
    case ACT_AFFILIATE: // plus
      g.moveTo(cx - u * 0.5, cy); g.lineTo(cx + u * 0.5, cy);
      g.moveTo(cx, cy - u * 0.5); g.lineTo(cx, cy + u * 0.5);
      g.stroke();
      return;
    case ACT_GRAZE: // asterisk
      for (let i = 0; i < 3; i++) {
        const a = (i * Math.PI) / 3;
        const dx = Math.cos(a) * u * 0.55, dy = Math.sin(a) * u * 0.55;
        g.moveTo(cx - dx, cy - dy); g.lineTo(cx + dx, cy + dy);
      }
      g.stroke();
      return;
    case ACT_HUNT: // crosshair: locked on
      g.arc(cx, cy, u * 0.4, 0, 7);
      g.moveTo(cx - u * 0.65, cy); g.lineTo(cx - u * 0.4, cy);
      g.moveTo(cx + u * 0.4, cy); g.lineTo(cx + u * 0.65, cy);
      g.stroke();
      return;
    case ACT_CARRY: // hook: something is hanging off this body
      g.arc(cx, cy, u * 0.45, 0, 7);
      g.moveTo(cx, cy); g.lineTo(cx + u * 0.55, cy + u * 0.55);
      g.stroke();
      return;
    case ACT_RIDE: // a chevron riding above a rail: carried, not carrying
      g.moveTo(cx - u * 0.55, cy + u * 0.4); g.lineTo(cx + u * 0.55, cy + u * 0.4);
      g.moveTo(cx - u * 0.4, cy + u * 0.05);
      g.lineTo(cx, cy - u * 0.5);
      g.lineTo(cx + u * 0.4, cy + u * 0.05);
      g.stroke();
      return;
    case ACT_NEST: // house
      g.moveTo(cx - u * 0.45, cy + u * 0.5);
      g.lineTo(cx - u * 0.45, cy - u * 0.05);
      g.lineTo(cx, cy - u * 0.55);
      g.lineTo(cx + u * 0.45, cy - u * 0.05);
      g.lineTo(cx + u * 0.45, cy + u * 0.5);
      g.closePath();
      g.stroke();
      return;
  }
}
