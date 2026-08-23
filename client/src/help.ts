// The help page (/help). One rule holds the whole thing together: every entry
// is drawn by the SAME code the live viewer draws with, reading the same
// server-baked atlases. The web rendering is the source of truth, because it is
// the rendering anyone actually looks at — a picture produced down a second path
// is a picture of what the art ought to be, and the two drift silently.
//
// This began as a sprite catalog and is growing into the place the world
// explains itself; /sprites still redirects here. Sections that document
// mechanics rather than art belong here too.

import {
  ART_RADIUS, CELL, DECAY_STEPS, MIP, atlasFor, atlasMipFor, corpseFor, corpseMipFor,
  tintedFor,
} from './atlas';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_CARRY, ACT_FLEE, ACT_GRAZE, ACT_HUNT, ACT_MATE, ACT_NEST,
  ACT_RIDE,
} from './protocol';
import type { EntityState } from './protocol';
import {
  DOT_LOD_SCALE, VEG_KINDS, VEG_STAGES, VEG_VARIANTS, VEIL_ALPHA,
  drawActionGlyph, drawCarryLink, drawDoor, drawDot, drawItem, drawNest, drawPlaceholder,
  drawRing, drawSentinel, drawSwitch, ductLidTile, pheroPuff, veilTile, vegetationTileFor,
} from './render';

const root = document.getElementById('root')!;
// The page has two halves. Mechanics come first — a viewer who wants to know why
// a creature just starved is better served by the energy model than by a sprite
// sheet — so its host div is parked in the DOM before any art section appends
// itself to `root`, and filled in once the server has answered.
const mechRoot = document.createElement('div');
root.append(mechRoot);
const artRoot = document.createElement('div');
root.append(artRoot);

const GRASS = '#3f7a38'; // flat stand-in for the baked ground the viewer has
const DECK = '#4a5058'; // ditto, for the underground base's steel plate

{
  const intro = document.getElementById('intro');
  if (intro) {
    intro.innerHTML = 'The art system as the <b>web client renders it</b>: every canvas '
      + 'below is painted live by the viewer\'s own drawing code, driven by scripted '
      + 'state — the pixels this very browser puts on the live world.';
  }
}

/** A fixed pseudo-camera: `tiles * scale` px, origin at the canvas corner. */
function fixedCam(scale: number): Camera {
  return {
    scale,
    worldToScreen: (x: number, y: number) => ({ x: x * scale, y: y * scale }),
  } as unknown as Camera;
}

function ent(partial: Partial<EntityState>): EntityState {
  return {
    id: 0, kind: '', x: 0, y: 0, z: 0, dir: 0, size: 0, rgb: 0xffffff,
    flags: 0, attachedTo: -1, aux: 0, pheno: 0, ...partial,
  };
}

function section(title: string, note: string): HTMLDivElement {
  const h = document.createElement('h2');
  h.textContent = title;
  const p = document.createElement('p');
  p.className = 'note';
  p.textContent = note;
  const grid = document.createElement('div');
  grid.className = 'grid';
  artRoot.append(h, p, grid);
  return grid;
}

function figure(el: HTMLElement, caption: string, parent: HTMLElement): void {
  const f = document.createElement('figure');
  const c = document.createElement('figcaption');
  c.innerHTML = caption;
  f.append(el, c);
  parent.append(f);
}

/** A captioned live canvas. Kept as its own helper (rather than folded into
 *  `figure`) because these entries carry a label under a boxed canvas, and
 *  because it used to place a java bake beside each one -- the comparison the
 *  three-page catalog existed for. */
function pair(parent: HTMLElement, label: string,
    w: number, h: number, paint: (g: CanvasRenderingContext2D, t: number) => void): void {
  const outer = document.createElement('figure');
  const box = document.createElement('div');
  box.className = 'pair';
  figure(liveCanvas(w, h, paint), '<b>web client</b>', box);
  const cap = document.createElement('figcaption');
  cap.textContent = label;
  outer.append(box, cap);
  parent.append(outer);
}

/** A canvas repainted every frame with the elapsed seconds. */
function liveCanvas(w: number, h: number,
    paint: (g: CanvasRenderingContext2D, t: number) => void): HTMLCanvasElement {
  const cv = document.createElement('canvas');
  cv.width = w;
  cv.height = h;
  const g = cv.getContext('2d')!;
  const t0 = performance.now();
  const tick = () => {
    paint(g, (performance.now() - t0) / 1000);
    requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
  return cv;
}

/** The door slide the wire animates: sealed, open with a hold, sealed again. */
function extAt(t: number): number {
  const period = 6, p = (t % period) / period; // 0..1
  if (p < 0.2) return 1;                        // sealed
  if (p < 0.4) return 1 - ((p - 0.2) / 0.2) * 0.85; // opening
  if (p < 0.7) return 0.15;                     // held open
  if (p < 0.9) return 0.15 + ((p - 0.7) / 0.2) * 0.85; // closing
  return 1;
}

// ---- furniture & items --------------------------------------------------

const DOOR_RGB: Record<string, number> = {
  timber: 0x574024, stone: 0x665e4c, grate: 0x7c828f, hedge: 0x2b5422, blast: 0x515862,
};

const furniture = section('Furniture & items',
  'The exact drawing code the live viewer runs, driven by scripted state over a flat '
  + 'ground tone. Doors cycle closed → open → closed; the switches press and release.');

for (const flavour of ['timber', 'stone', 'grate', 'hedge', 'blast']) {
  const S = 64;
  pair(furniture, `${flavour} door`, 4 * S, 3 * S, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 4 * S, 3 * S);
    drawDoor(g, fixedCam(S), ent({
      kind: `door.${flavour}`, x: 1, y: 1.5, dir: 0, size: 2,
      rgb: DOOR_RGB[flavour], aux: extAt(t),
    }));
  });
}

for (const mode of ['plate', 'button']) {
  const S = 48;
  pair(furniture,
      mode === 'plate' ? 'pressure plate (weight)' : 'button (intent)', 7 * S, 4 * S, (g, t) => {
    const pressed = (t % 6) >= 3; // held three seconds, released three
    // The wired door follows the press with the slide the wire would carry.
    const p = (t % 6) / 3; // 0..1 within the active half-cycle
    const slide = Math.max(0.15, Math.min(1, pressed ? 1 - p % 1 * 3 : 0.15 + (p % 1) * 3));
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 7 * S, 4 * S);
    const door = ent({ kind: 'door.grate', x: 5, y: 1, dir: 0, size: 1,
      rgb: DOOR_RGB.grate, aux: slide });
    drawSwitch(g, fixedCam(S), ent({
      kind: `switch.${mode}`, x: 1, y: 2, aux: pressed ? 1 : 0,
    }), door);
    drawDoor(g, fixedCam(S), door);
  });
}

{
  const S = 56;
  pair(furniture, 'food · crate · hazard', 6 * S, 2 * S, (g) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 6 * S, 2 * S);
    const r = S * 0.14;
    drawItem(g, 'item.food', 1 * S, 1 * S, r, '#C8402E');
    drawItem(g, 'item.crate', 3 * S, 1 * S, r * 1.6, '#9C6B3C');
    drawItem(g, 'item.hazard', 5 * S, 1 * S, r * 1.3, '#7A2E8A');
  });
}

{
  const S = 56;
  pair(furniture, 'nest (brood site)', 3 * S, 3 * S, (g) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 3 * S, 3 * S);
    drawNest(g, 1.5 * S, 1.5 * S, S);
  });
}

{
  const S = 56;
  pair(furniture, 'pheromone cloud, evaporating', 4 * S, 4 * S, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 4 * S, 4 * S);
    // The viewer's haze, breathing out as strength decays — the same baked
    // puff sprite render.ts blits for kind "phero".
    const decay = 1 - ((t % 8) / 8);
    const r = Math.max(2, (1.4 + 2.6 * decay) * S * 0.5);
    g.imageSmoothingEnabled = true;
    g.drawImage(pheroPuff(), 2 * S - r, 2 * S - r, r * 2, r * 2);
    g.imageSmoothingEnabled = false;
  });
}

// ---- concealment: the veil over bodies in cover -------------------------

/** Fetch an image into a canvas (null on failure), for catalog inputs. */
function loadCanvas(src: string): Promise<HTMLCanvasElement | null> {
  return new Promise((resolve) => {
    const img = new Image();
    img.onload = () => {
      const cv = document.createElement('canvas');
      cv.width = img.naturalWidth;
      cv.height = img.naturalHeight;
      cv.getContext('2d')!.drawImage(img, 0, 0);
      resolve(cv);
    };
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

// ---- the steward's drone -------------------------------------------------

const droneSec = section("The steward's drone",
  'The one body in the world that is machinery rather than an organism, so it is an '
  + 'authored pixel stamp instead of a procedural creature \u2014 and it is painted like plant '
  + 'equipment: the facility\u2019s own safety yellow for the hull, the hazard checker its '
  + 'ground markings use for the plates, chassis iron for the pylons and the hull\u2019s sunk '
  + 'edge, and one red warning lamp. Every one of those colours was already in the world. '
  + 'Java\u2019s DronePainter stamps the same two silhouettes through the same rule \u2014 a '
  + 'cardinal and a diagonal drawn by hand, the other six headings exact 90\u00b0 lattice '
  + 'rotations of them. Material and light compose: the stamp says what a cell is made of, '
  + 'the run says which way it faces the sun, and the light goes on after the rotation so no '
  + 'heading carries its highlight around the compass.');

{
  const S = 96;
  pair(droneSec, 'eight headings \u2014 E, SE, S, SW, W, NW, N, NE', 8 * S, S * 1.5, (g) => {
    g.fillStyle = DECK;
    g.fillRect(0, 0, 8 * S, S * 1.5);
    for (let i = 0; i < 8; i++) {
      drawSentinel(g, S * (i + 0.5), S * 0.62, S, i);
    }
  });
}

{
  // The zoom ladder is the entry that earns its place: the stamp is sized by
  // the tile grid, not by the body radius, so this is what the drone actually
  // looks like as the viewer scrolls out — and where it stops being legible.
  const W = 900;
  pair(droneSec, 'across the zoom range (tile size 16\u2026120px)', W, 150, (g) => {
    g.fillStyle = DECK;
    g.fillRect(0, 0, W, 150);
    let x = 60;
    for (const sc of [16, 24, 32, 48, 64, 80, 96, 120]) {
      drawSentinel(g, x, 60, sc, 0);
      x += 110;
    }
  });
}

{
  // Turning on the spot, at the eight buckets the sticky headingCol snaps to.
  const S = 128;
  pair(droneSec, 'turning on the spot', S * 2, S * 2, (g, t) => {
    g.fillStyle = DECK;
    g.fillRect(0, 0, S * 2, S * 2);
    drawSentinel(g, S, S * 0.85, S, Math.floor(t * 1.5) % 8);
  });
}

const conceal = section('Concealment',
  'A body in cover is part-hidden by the tile\'s own re-stamped bake pixels — the '
  + 'clustered canopy mask, stalk-exact reeds, the duct\'s ribbed lid — built by the '
  + 'same veilTile/ductLidTile code the live view runs, over the server\'s ground bake.');

// ---- vegetation sprites ---------------------------------------------------

const vegSec = section('Vegetation sprites',
  'One-tile vegetation drawn OVER the floor: five growth stages per kind — stage 1 is '
  + 'what depletion leaves (trampled remnants), stage 5 fully grown — four variants '
  + 'each, transparent so the same sprite reads on any ground. Painted at art '
  + 'resolution by the exported drawVegetationTile the world will stamp with. '
  + 'The stage is measured on an absolute scale, so a tile’s fertility is a '
  + 'ceiling on it: half-fertile ground fully regrown stops around stage 3 and '
  + 'never wears the tall tufts, matching the sward baked underneath it.');

for (const kind of VEG_KINDS) {
  const S = 44, PAD = 6;
  const W = VEG_STAGES * (S + PAD) + PAD, H = VEG_VARIANTS * (S + PAD) + PAD;
  figure(liveCanvas(W, H, (g) => {
    // Checkerboard under the sprites: transparency is the point.
    for (let cy = 0; cy * 8 < H; cy++) {
      for (let cx = 0; cx * 8 < W; cx++) {
        g.fillStyle = (cx + cy) % 2 ? '#2e2a24' : '#3a352c';
        g.fillRect(cx * 8, cy * 8, 8, 8);
      }
    }
    g.imageSmoothingEnabled = false;
    for (let v = 0; v < VEG_VARIANTS; v++) {
      for (let st = 1; st <= VEG_STAGES; st++) {
        g.drawImage(vegetationTileFor(kind, st, v),
          PAD + (st - 1) * (S + PAD), PAD + v * (S + PAD), S, S);
      }
    }
  }), `<b>${kind}</b> — stages 1..${VEG_STAGES} × ${VEG_VARIANTS} variants`, vegSec);
  // The same sprites over two different grounds, cycling through growth:
  // the transparency promise, demonstrated.
  const D = 176, T = D / 4;
  pair(vegSec, `${kind} growing on two grounds`, D * 2 + 12, D, (g, t) => {
    const stage = 1 + Math.floor((t % 7.5) / 1.5);
    for (const [gx, tone] of [[0, '#3f7a38'], [D + 12, '#584430']] as const) {
      g.fillStyle = tone;
      g.fillRect(gx, 0, D, D);
      g.imageSmoothingEnabled = false;
      for (let ty = 0; ty < 4; ty++) {
        for (let tx = 0; tx < 4; tx++) {
          g.drawImage(vegetationTileFor(kind, stage, (tx * 7 + ty * 5) % VEG_VARIANTS),
            gx + tx * T, ty * T, T, T);
        }
      }
    }
  });
}

// ---- web-only bakes: living body, corpse ---------------------------------

const bakes = section('Atlas bakes',
  'What the viewer itself makes of a creature\'s server-baked sprite atlas: the living '
  + 'stamp and the drained corpse bake (colour stripped, body kept). '
  + 'The atlas comes from a phenotype alive in this world right now.');

/** The live sprite, once the stream and the atlas endpoint have produced one.
 *  Null until then; every canvas that can do without it simply does. */
let liveAtlas: HTMLCanvasElement | null = null;

const expressions = section('Expressions — what each badge says',
  'The action badge hovering over a creature, exactly as the live view draws it, with '
  + 'the behaviour it flags. Only notable acts earn a badge — wandering, resting and '
  + 'herding show nothing — and badges fade out as you zoom away, so the map view '
  + 'stays clean. Shapes and colours mirror the Java scenario renderer\'s.');

// The expressions gallery: each badge over a body, composed exactly as the live
// loop composes it (glyph at two body-radii above, sized to the body), so the
// icon is seen the way the world shows it.
//
// Rendered HERE, at module load, and deliberately NOT inside the async block
// below. A badge needs no phenotype, no atlas and no world: it is a glyph. Built
// down there it inherited that block's whole waiting list -- a WebSocket
// handshake, a full world snapshot, then an atlas PNG -- and on a real
// connection the section sat empty under its own heading for seconds, which
// reads exactly like a gallery that is missing. It now draws immediately over
// the placeholder body the live viewer itself uses, and quietly upgrades to the
// real sprite the moment `liveAtlas` arrives, because liveCanvas repaints every
// frame anyway.
const EXPR: Array<[number, string, string]> = [
  [ACT_ATTACK, 'attack', 'striking at a target'],
  [ACT_HUNT, 'hunt', 'locked onto prey'],
  [ACT_FLEE, 'flee', 'running from a threat'],
  [ACT_GRAZE, 'graze', 'foraging vegetation'],
  [ACT_MATE, 'mate', 'courting a partner'],
  [ACT_AFFILIATE, 'affiliate', 'bonding with a packmate'],
  [ACT_CARRY, 'carry', 'hauling another creature — a captive, or cargo'],
  [ACT_RIDE, 'ride', 'aboard a host by its own choice: carried, not carrying'],
  [ACT_NEST, 'nest', 'homing to its nest'],
];
for (const [act, name, meaning] of EXPR) {
  const W = 150, H = 195, bx = W / 2, by = 125;
  const rb = (W * 0.75) * (ART_RADIUS / CELL); // the stamped body's on-screen radius
  figure(liveCanvas(W, H, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, W, H);
    const dir = Math.floor((t / 1.2) % 8);
    const anim = Math.floor((t * 6) % 8);
    const box = W * 0.75;
    g.imageSmoothingEnabled = false;
    if (liveAtlas) {
      g.drawImage(liveAtlas, anim * CELL, dir * CELL, CELL, CELL,
        bx - box / 2, by - box / 2, box, box);
    } else {
      drawPlaceholder(g, bx, by, rb, (dir / 8) * Math.PI * 2, '#8b9bb4');
    }
    drawActionGlyph(g, bx, by - rb * 2.0, rb * 0.95, act);
  }), `<b>${name}</b> — ${meaning}`, expressions);
}

(async () => {
  // A live phenotype from the stream, so the atlas endpoint has it baked. The
  // atlas is keyed by SHAPE and baked colour-neutral; the creature's rgb rides
  // the same stream entity, and the page re-tints exactly as the live view does.
  const found = await new Promise<{ pheno: number; rgb: number }>((resolve) => {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/api/world/stream`);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      const n = (m.entities || []).find((e: EntityState) => e.kind.startsWith('npc.') && e.pheno);
      if (m.type === 'full' && n) { resolve({ pheno: n.pheno, rgb: n.rgb }); ws.close(); }
    };
    setTimeout(() => resolve({ pheno: 0, rgb: 0 }), 20000);
  });
  const pheno = found.pheno;
  // No live phenotype (the stream never delivered one inside the timeout) is
  // survivable for most of this page, and it used to end it: everything below
  // sat after an early return, so the headings rendered and their contents
  // silently did not -- the badges gallery in particular, which does not need a
  // real body at all. Only the sections that genuinely stamp an atlas bow out.
  const atlas = pheno
    ? await new Promise<HTMLCanvasElement>((resolve) => {
      const poll = () => { const a = atlasFor(pheno); a ? resolve(a) : setTimeout(poll, 100); };
      poll();
    })
    : null;
  // The re-tinted living body — one bake per (shape, colour), the 2D path's
  // equivalent of the GL ramp shader. The galleries stamp this; the corpse
  // bakes stay on the neutral sheet, exactly as the live view derives them.
  const tinted = atlas ? tintedFor(pheno, found.rgb, atlas) : null;
  const tk = pheno + ':' + found.rgb;
  // Hand it to the galleries that started without one; they repaint every frame,
  // so they pick the real body up on the next tick with no rebuild.
  liveAtlas = tinted;
  if (!atlas) {
    const note = document.createElement('p');
    note.className = 'note';
    note.textContent = 'No creature phenotype arrived from the world stream, so the '
      + 'atlas-stamped entries below are unavailable. Everything drawn without one '
      + 'still renders.';
    bakes.append(note);
  }
  const D = 192;
  const stamp = (src: CanvasImageSource) => liveCanvas(D, D, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, D, D);
    const dir = Math.floor((t / 1.2) % 8);         // slow spin through the 8 facings
    const anim = Math.floor((t * 6) % 8);          // the idle gait
    g.imageSmoothingEnabled = false;
    g.drawImage(src, anim * CELL, dir * CELL, CELL, CELL, 0, 0, D, D);
  });
  if (atlas && tinted) {
  figure(stamp(atlas), '<b>neutral shape bake</b> (mid-grey ramp, one sheet per shape)', bakes);
  figure(stamp(tinted), '<b>living stamp</b> (re-tinted with the creature\'s rgb)', bakes);
  figure(stamp(corpseFor(pheno, atlas)), '<b>corpse bake</b> (drained, transparent)', bakes);
  // The rot, stage by stage. A corpse lasts as long as the animal took to grow
  // up -- most of a minute for a big one -- so decay is a thing you watch, not a
  // single image, and the catalog of record should show all of it.
  for (let i = 0; i < DECAY_STEPS; i++) {
    const t = (i + 0.5) / DECAY_STEPS;
    figure(stamp(corpseFor(pheno, atlas, t)),
      `<b>rot ${i + 1}/${DECAY_STEPS}</b> (${Math.round(t * 100)}%)`, bakes);
  }
  // The far-zoom mips: what the world view actually stamps when the whole map
  // is in view — quarter-size cells, smoothed once at bake time.
  const M = CELL / MIP;
  const mipStamp = (src: HTMLCanvasElement) => liveCanvas(D, D, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, D, D);
    const dir = Math.floor((t / 1.2) % 8);
    const anim = Math.floor((t * 6) % 8);
    g.imageSmoothingEnabled = false;
    g.drawImage(src, anim * M, dir * M, M, M, 0, 0, D, D);
  });
  figure(mipStamp(atlasMipFor(tk, tinted)), '<b>far-zoom mip</b> (quarter cell)', bakes);
  figure(mipStamp(corpseMipFor(pheno, atlas)), '<b>corpse mip</b>', bakes);
  } // end of the entries that need a live atlas


  // Concealment pairs: the client's veil code over the SAME staged ground the
  // Java scene was baked from. The Java pass veils the 3x3 tiles around the
  // occupant (tile 3,3 of the staged 7x7 world; the crop starts at tile 1,1),
  // so the demo veils exactly those — the live view veils every foliage tile,
  // which is equivalent because re-stamped pixels are invisible over the
  // identical ground.
  for (const [name, kind] of [['canopy', 1], ['reeds', 3], ['duct', 2]] as const) {
    if (!atlas) break; // the whole point is a body under the veil
    const ground = await loadCanvas(`/help/${name}_ground.png`);
    if (!ground) continue;
    const ts = ground.width / 5;
    const veil = document.createElement('canvas');
    veil.width = veil.height = 5 * 12;
    const vg = veil.getContext('2d')!;
    for (let ty = 2; ty <= 4; ty++) {
      for (let tx = 2; tx <= 4; tx++) {
        if (kind === 2) {
          vg.imageSmoothingEnabled = false;
          vg.drawImage(ductLidTile(true), (tx - 1) * 12, (ty - 1) * 12);
        } else {
          veilTile(vg, kind, tx, ty, (tx - 1) * 12, (ty - 1) * 12,
            ground, (tx - 1) * ts, (ty - 1) * ts, ts);
        }
      }
    }
    // The stamped body matches the Java scene's occupant scale: a size-12
    // reference body is 12/32 tiles in radius; the live formula maps body
    // radius to an atlas-cell box (cells carry padding around the art).
    const DC = 280, scale = DC / 5;
    const box = (12 / 32) * scale * 2 * (CELL / (2 * ART_RADIUS));
    pair(conceal, `a body veiled in ${name}`, DC, DC, (g, t) => {
      g.imageSmoothingEnabled = false;
      g.drawImage(ground, 0, 0, DC, DC);
      const dir = Math.floor((t / 1.2) % 8);
      const anim = Math.floor((t * 6) % 8);
      g.drawImage(tinted!, anim * CELL, dir * CELL, CELL, CELL,
        (DC - box) / 2, (DC - box) / 2, box, box);
      g.globalAlpha = VEIL_ALPHA;
      g.drawImage(veil, 0, 0, DC, DC);
      g.globalAlpha = 1;
    });
  }
})();

// ---- the viewer's overlay language --------------------------------------

{
  const overlay = section('Viewer overlay language',
    'Not world art: the rings and markers the viewer floats over creatures to say how '
    + 'the camera and inspector relate to them (the action badges have their own '
    + 'expressions gallery above). Smooth strokes are allowed here by design — '
    + 'ART-STYLE.md keeps the overlay language apart from the world\'s pixel grammar.');
  const S = 56;
  const RINGS = ['grabbed', 'carrying', 'follow', 'selected'] as const;
  figure(liveCanvas(RINGS.length * S * 1.5, S * 1.5, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, RINGS.length * S * 1.5, S * 1.5);
    RINGS.forEach((kind, i) => {
      const x = i * S * 1.5 + S * 0.75, y = S * 0.75;
      g.fillStyle = '#b98a5a';
      g.beginPath(); g.arc(x, y, S * 0.18, 0, 7); g.fill();
      drawRing(g, kind, x, y, S * 0.18, t * 1000);
    });
  }), 'status rings: ' + RINGS.join(' · '), overlay);
  figure(liveCanvas(4 * S, S * 1.5, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 4 * S, S * 1.5);
    const y = S * 0.75;
    drawCarryLink(g, S, y, 3 * S, y, S);
    drawPlaceholder(g, S, y, S * 0.2, t, '#5a8ab9');
    drawPlaceholder(g, 3 * S, y, S * 0.2, -t, '#b95a8a');
  }), 'pre-atlas placeholders with a carry tether', overlay);
  figure(liveCanvas(6 * S, S * 1.5, (g) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 6 * S, S * 1.5);
    // Actual map-view size on the left, the same dots at 6x beside them.
    const y = S * 0.75, d = 6;
    drawDot(g, S * 0.5, y, d, '#5a8ab9', false);
    drawDot(g, S * 0.85, y, d, '#b95a8a', false);
    drawDot(g, S * 1.2, y, d, '', true);
    drawDot(g, S * 2.6, y, d * 6, '#5a8ab9', false);
    drawDot(g, S * 3.9, y, d * 6, '#b95a8a', false);
    drawDot(g, S * 5.2, y, d * 6, '', true);
  }), `map-view dots — below ${DOT_LOD_SCALE} px/tile zoom every body draws as `
    + 'a block (live · live · corpse; actual size, then 6×)', overlay);
}

// ---- what the server bakes, and where to see it --------------------------

// ---- the bodies, drawn the way the world draws them --------------------------
// Every plan the organism renderer can express, and the named samples. The
// server hands over shape KEYS; the atlas comes down the same endpoint the live
// view uses and is stamped by the same code, so what is on this page is what a
// viewer sees rather than a second rendering that merely resembles it. These
// used to be baked GIFs, which is a picture of what the art ought to be.
interface RefBody { group: string; label: string; pheno: number; worn: boolean; rgb: number; }

const plans = section('Body plans',
  'Every outline the organism renderer can express, drawn from one genome with the '
  + 'trophic markings stripped off, so the only thing differing between them is the '
  + 'body. A creature\'s plan follows what its genome eats and there are four trophic '
  + 'levels, so two of these are worn by nothing — they are here because a reference '
  + 'to the space should show the whole space, not a census of what happens to be alive.');
const samples = section('Creatures',
  'Hand-picked points in the genome space, wearing their trophic markings: a hunter '
  + 'strides on the long pair and carries a tail, a scavenger is the segmented body with '
  + 'the feelers it finds its food by, a parasite is the round ciliate that rides a '
  + 'bigger body and eats it slowly, anything airborne keeps a single pair of limbs. '
  + 'Fixed points rather than live phenotypes, so this stays a reference while the world '
  + 'evolves away from it.');

/** One body, spun through its facings with the idle gait — the live view's own
 *  stamp, waiting on the same atlas fetch the world waits on. */
function refBody(b: RefBody, into: HTMLElement): void {
  const W = 150, H = 150, box = W * 0.72;
  figure(liveCanvas(W, H, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, W, H);
    const dir = Math.floor((t / 1.2) % 8);
    const anim = Math.floor((t * 6) % 8);
    g.imageSmoothingEnabled = false;
    const sheet = atlasFor(b.pheno);
    const tint = sheet ? tintedFor(b.pheno, b.rgb, sheet) : null;
    if (tint) {
      g.drawImage(tint, anim * CELL, dir * CELL, CELL, CELL,
        (W - box) / 2, (H - box) / 2, box, box);
    } else {
      // The same placeholder the live viewer shows while an atlas is in flight.
      drawPlaceholder(g, W / 2, H / 2, (W * 0.72) * (ART_RADIUS / CELL),
        (dir / 8) * Math.PI * 2, '#8b9bb4');
    }
  }), b.worn ? `<b>${b.label}</b>` : `${b.label} <i>(unworn)</i>`, into);
}

void (async () => {
  let bodies: RefBody[] = [];
  try {
    const r = await fetch('/help/bodies.json');
    if (r.ok) bodies = await r.json();
  } catch {
    /* offline: the sections below simply stay empty rather than lying */
  }
  for (const b of bodies) refBody(b, b.group === 'plan' ? plans : samples);
})();

const rest = section('Server-baked art',
  'Ground chunks and creature bodies are baked on the server for the web view too — '
  + 'chunk PNGs and ProcCreature atlases — so they have a single source of truth rather '
  + 'than a second implementation here.');
{
  const p = document.createElement('p');
  p.className = 'note';
  p.innerHTML = 'The full set of ground swatches is served under <code>/help/'
    + 'ground_*.png</code>; the creature atlases under <code>/api/world/atlas/&lt;pheno&gt;'
    + '.png</code>. Both are fetched straight into the entries above.';
  rest.append(p);
}

// ---- how the world works -------------------------------------------------
// The other half of the page, and the reason it stopped being called a sprite
// catalog. Same principle as the art above, applied to the rules: none of these
// numbers are written here. The server reads them off the running constants —
// or computes them with the arithmetic the simulation itself uses — and hands
// them over, so a tuning change moves this page with it instead of quietly
// leaving it behind. Only the prose is authored, and it is deliberately written
// about relationships rather than values for exactly that reason.

interface MechRow { label: string; value: string; unit: string; note: string; }
interface MechTable { caption: string; headers: string[]; rows: string[][]; }
interface MechItem { name: string; detail: string; idx?: string; }
interface MechGroup { title: string; items: MechItem[]; }
interface MechSection {
  id: string; title: string; intro: string; rows: MechRow[];
  table?: MechTable; groups?: MechGroup[];
}

/** Text into a fresh element, escaped by the DOM rather than by us. */
function el<K extends keyof HTMLElementTagNameMap>(
    tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

function mechSection(m: MechSection, into: HTMLElement): void {
  const h = el('h2', m.title);
  h.id = m.id;
  into.append(h);
  // Blank-line-separated paragraphs, so the server can write more than one
  // without smuggling markup through the wire.
  for (const para of m.intro.split('\n\n')) into.append(el('p', para, 'note'));

  const t = el('table', undefined, 'facts');
  const tb = el('tbody');
  for (const r of m.rows) {
    const tr = el('tr');
    tr.append(el('th', r.label));
    const v = el('td', r.value, 'v');
    if (r.unit) {
      v.append(document.createTextNode(' '));
      v.append(el('span', r.unit, 'unit'));
    }
    tr.append(v, el('td', r.note, 'why'));
    tb.append(tr);
  }
  t.append(tb);
  into.append(wide(t));

  if (m.table) into.append(worked(m.table));
  if (m.groups) for (const g of m.groups) into.append(channels(g));
}

/** A named group of channels — a sensor bank, a set of acts. These describe a
 *  SURFACE rather than a quantity, so they read as a list of names with what
 *  each one means, not as a table of figures. The name is the engine's own wire
 *  name for the channel, which is what makes the list checkable. */
function channels(g: MechGroup): HTMLElement {
  const box = el('div', undefined, 'chan');
  box.append(el('h3', g.title));
  const dl = el('dl');
  for (const i of g.items) {
    dl.append(el('dt', i.name), el('dd', i.detail));
  }
  box.append(dl);
  return box;
}

/** A worked table: the constants above, applied across the range of bodies or
 *  paces the world can actually produce. This is where the model stops being a
 *  formula and starts being a claim about what living here is like. */
function worked(w: MechTable): HTMLElement {
  const t = el('table', undefined, 'worked');
  const head = el('tr');
  for (const h of w.headers) head.append(el('th', h));
  const th = el('thead');
  th.append(head);
  const tb = el('tbody');
  for (const row of w.rows) {
    const tr = el('tr');
    for (const cell of row) tr.append(el('td', cell));
    tb.append(tr);
  }
  t.append(th, tb);
  const cap = el('figcaption', w.caption, 'tcap');
  const box = el('div', undefined, 'tblock');
  box.append(wide(t), cap);
  return box;
}

/** Wraps a table so a narrow phone scrolls the TABLE sideways rather than the
 *  page — the site is watched on a phone more often than not. */
function wide(t: HTMLElement): HTMLElement {
  const d = el('div', undefined, 'scroll');
  d.append(t);
  return d;
}

void (async () => {
  let secs: MechSection[] = [];
  try {
    const r = await fetch('/help/mechanics.json');
    if (r.ok) secs = await r.json();
  } catch {
    /* offline: better a page with no rules on it than a page of stale ones */
  }
  if (!secs.length) return;

  mechRoot.append(el('h2', 'How the world works', 'part'));
  const lead = el('p', undefined, 'note');
  lead.innerHTML = 'Every figure below is read off the <b>running simulation\'s own '
    + 'constants</b>, or worked out from them by the same arithmetic the simulation '
    + 'uses — nothing on this page is transcribed. A page that divides the tank by '
    + 'the burn rate cannot be wrong about how long a creature lasts; a page that '
    + 'states the answer can, and would never say so.';
  mechRoot.append(lead);

  const nav = el('p', undefined, 'nav');
  for (const m of secs) {
    const a = el('a', m.title);
    a.href = `#${m.id}`;
    nav.append(a);
  }
  mechRoot.append(nav);
  for (const m of secs) mechSection(m, mechRoot);

  // The art half gets its own banner, now that it is no longer the whole page.
  const banner = el('h2', 'How the world looks', 'part');
  artRoot.prepend(banner);
})();
