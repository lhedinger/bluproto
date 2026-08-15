// The client half of the sprite catalog (/sprites): every entry here is drawn
// by the SAME functions the live viewer uses — drawDoor/drawSwitch/drawItem,
// the veil and dither compositors, the overlay language from render.ts, the
// corpse/rim/mip bakes from atlas.ts — driven by scripted state instead of
// the world stream. The server-baked (Java) counterpart of each entry sits
// beside it as an <img>, so the two render pipelines can be compared at a
// glance and any drift between them is immediately visible.
//
// The catalog is NORMATIVE for the web view (ART-STYLE.md §6): everything the
// client draws into the world must have an entry on this page rendered by the
// same code path. A visual with no catalog entry is a review failure; a
// reimplementation that merely imitates one is a bug waiting to drift.

import {
  ART_RADIUS, CELL, DECAY_STEPS, MIP, atlasFor, atlasMipFor, corpseFor, corpseMipFor,
  mindedMipFor, rimFor,
} from './atlas';
import type { Camera } from './camera';
import {
  ACT_AFFILIATE, ACT_ATTACK, ACT_FLEE, ACT_GRAB, ACT_GRAZE, ACT_HUNT, ACT_MATE, ACT_NEST,
} from './protocol';
import type { EntityState } from './protocol';
import {
  DOT_LOD_PX, VEIL_ALPHA, ditherTile, drawActionGlyph, drawCarryLink, drawDoor, drawDot,
  drawItem, drawNest, drawPlaceholder, drawRing, drawSwitch, ductLidTile, pheroPuff, veilTile,
} from './render';

const root = document.getElementById('root')!;
const GRASS = '#3f7a38'; // flat stand-in for the baked ground the viewer has

// One page, two addresses. /sprites is the WEB view: what this browser's own
// drawing code produces, which is what the live world actually shows anyone —
// so it is the default, and the question "what does the art look like" is
// answered without asking a second one. /sprites/compare puts the java bake
// beside each client canvas, for the narrower question of whether the two
// pipelines have drifted. /sprites/web stays as an alias so old links keep
// working.
const compare = location.pathname.replace(/\/+$/, '').endsWith('/compare');
const webOnly = !compare;

{
  if (webOnly) {
    const intro = document.getElementById('intro');
    if (intro) {
      intro.innerHTML = 'The art system as the <b>web client alone</b> renders it: every '
        + 'canvas below is painted live by the viewer\'s own drawing code, driven by '
        + 'scripted state — the pixels this very browser puts on the live world.';
    }
  }
  const nav = document.createElement('p');
  nav.className = 'note';
  nav.innerHTML = webOnly
    ? 'See also the <a href="/sprites/compare">side-by-side comparison</a> '
      + 'or the <a href="/sprites/java">pure java bake</a>.'
    : 'Also: <a href="/sprites">web-client rendering only</a> · '
      + '<a href="/sprites/java">pure java bake</a>.';
  root.append(nav);
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
  root.append(h, p, grid);
  return grid;
}

function figure(el: HTMLElement, caption: string, parent: HTMLElement): void {
  const f = document.createElement('figure');
  const c = document.createElement('figcaption');
  c.innerHTML = caption;
  f.append(el, c);
  parent.append(f);
}

/** A java-bake <img> and a live client canvas boxed together for comparison. */
function pair(parent: HTMLElement, javaSrc: string, javaW: number, label: string,
    w: number, h: number, paint: (g: CanvasRenderingContext2D, t: number) => void): void {
  const outer = document.createElement('figure');
  const box = document.createElement('div');
  box.className = 'pair';
  if (!webOnly) {
    const img = document.createElement('img');
    img.src = javaSrc;
    img.style.width = `${javaW}px`;
    img.loading = 'lazy';
    figure(img, '<b>java bake</b>', box);
  }
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

const furniture = webOnly
  ? section('Furniture & items — web client',
    'The exact drawing code the live viewer runs, driven by scripted state over a flat '
    + 'ground tone. Doors cycle closed → open → closed; the switches press and release.')
  : section('Furniture & items — java bake vs web client',
    'Left of each box: the Java renderer (staged world, entity painters). Right: the exact '
    + 'drawing code the live viewer runs, driven by scripted state over a flat ground tone. '
    + 'Doors cycle closed → open → closed; the switches press and release; the corpse and '
    + 'rim are the client-side atlas bakes.');

for (const flavour of ['timber', 'stone', 'grate', 'hedge', 'blast']) {
  const S = 64;
  pair(furniture, `/sprites/door_${flavour}.gif`, 224, `${flavour} door`, 4 * S, 3 * S, (g, t) => {
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
  pair(furniture, `/sprites/switch_${mode}.gif`, 288,
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
  pair(furniture, '/sprites/items.png', 288, 'food · crate · hazard', 6 * S, 2 * S, (g) => {
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
  pair(furniture, '/sprites/nest.png', 160, 'nest (brood site)', 3 * S, 3 * S, (g) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 3 * S, 3 * S);
    drawNest(g, 1.5 * S, 1.5 * S, S);
  });
}

{
  const S = 56;
  pair(furniture, '/sprites/pheromone.gif', 224, 'pheromone cloud, evaporating', 4 * S, 4 * S, (g, t) => {
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

const conceal = webOnly
  ? section('Concealment — web client',
    'A body in cover is part-hidden by the tile\'s own re-stamped bake pixels — the '
    + 'clustered canopy mask, stalk-exact reeds, the duct\'s ribbed lid — built by the '
    + 'same veilTile/ductLidTile code the live view runs, over the server\'s ground bake.')
  : section('Concealment — java bake vs web client',
    'Left: the Java renderer\'s concealment pass over a staged scene. Right: the client\'s '
    + 'veil code (the SAME hash-gated mask, ported bit for bit) re-stamping the identical '
    + 'ground image over its own creature. The veils should agree pixel for pixel; the '
    + 'bodies underneath differ by design (each pipeline stamps its own sample creature).');

// ---- grazing depletion: the client's dither between the two bakes -------

const depl = webOnly
  ? section('Grazing depletion — web client',
    'The live dither: the fully-grazed bake admitted through the Bayer mask, per '
    + 'art-pixel, sweeping lush to bare — the exact compositing the world view runs.')
  : section('Grazing depletion — java bake vs web client',
    'Left: the Java bake grazed down frame by frame. Right: the client\'s own dither '
    + 'compositing (ditherTile) sweeping between the served lush and bare endpoint bakes '
    + '— the same code that draws live grazing.');

(async () => {
  const lush = await loadCanvas('/sprites/depletion_lush.png');
  const bare = await loadCanvas('/sprites/depletion_bare.png');
  if (!lush || !bare) return;
  const ts = lush.width / 4; // the staged strip is 4x4 tiles
  const D = 224, T = D / 4;
  pair(depl, '/sprites/ground_grass_depletion.gif', 128, 'grass, grazed bare', D, D, (g, t) => {
    g.imageSmoothingEnabled = false;
    g.drawImage(lush, 0, 0, D, D);
    const depl16 = Math.min(16, Math.floor(((t % 6) / 6) * 17));
    if (depl16 <= 0) return;
    for (let ty = 0; ty < 4; ty++) {
      for (let tx = 0; tx < 4; tx++) {
        ditherTile(g, bare, tx * ts, ty * ts, ts, tx * T, ty * T, T, T, depl16);
      }
    }
  });
})();

// ---- web-only bakes: living body, corpse, minded rim --------------------

const bakes = section('Atlas bakes — web client only',
  'What the viewer itself makes of a creature\'s server-baked sprite atlas: the living '
  + 'stamp, the drained corpse bake (colour stripped, body kept), and the minded rim. '
  + 'The atlas comes from a phenotype alive in this world right now.');

const expressions = section('Expressions — what each badge says',
  'The action badge hovering over a creature, exactly as the live view draws it, with '
  + 'the behaviour it flags. Only notable acts earn a badge — wandering, resting and '
  + 'herding show nothing — and badges fade out as you zoom away, so the map view '
  + 'stays clean. Shapes and colours mirror the Java scenario renderer\'s.');

(async () => {
  // A live phenotype from the stream, so the atlas endpoint has it baked.
  const pheno = await new Promise<number>((resolve) => {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/api/world/stream`);
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      const n = (m.entities || []).find((e: EntityState) => e.kind.startsWith('npc.') && e.pheno);
      if (m.type === 'full' && n) { resolve(n.pheno); ws.close(); }
    };
    setTimeout(() => resolve(0), 20000);
  });
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
  if (atlas) {
  figure(stamp(atlas), '<b>living stamp</b> (atlas cell)', bakes);
  figure(stamp(corpseFor(pheno, atlas)), '<b>corpse bake</b> (drained, transparent)', bakes);
  // The rot, stage by stage. A corpse lasts as long as the animal took to grow
  // up -- most of a minute for a big one -- so decay is a thing you watch, not a
  // single image, and the catalog of record should show all of it.
  for (let i = 0; i < DECAY_STEPS; i++) {
    const t = (i + 0.5) / DECAY_STEPS;
    figure(stamp(corpseFor(pheno, atlas, t)),
      `<b>rot ${i + 1}/${DECAY_STEPS}</b> (${Math.round(t * 100)}%)`, bakes);
  }
  figure(liveCanvas(D, D, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, D, D);
    const dir = Math.floor((t / 1.2) % 8);
    const anim = Math.floor((t * 6) % 8);
    g.imageSmoothingEnabled = false;
    // The viewer's halo: four one-sprite-pixel offset rim stamps, body on top.
    const rim = rimFor(pheno, atlas);
    const d = Math.max(1, D / CELL);
    for (const [dx, dy] of [[-d, 0], [d, 0], [0, -d], [0, d]] as const) {
      g.drawImage(rim, anim * CELL, dir * CELL, CELL, CELL, dx, dy, D, D);
    }
    g.drawImage(atlas, anim * CELL, dir * CELL, CELL, CELL, 0, 0, D, D);
  }), '<b>minded rim</b> (violet halo)', bakes);

  // The far-zoom mips: what the world view actually stamps when the whole map
  // is in view — quarter-size cells, smoothed once at bake time, the minded
  // variant with its rim fused in (one draw per creature).
  const M = CELL / MIP;
  const mipStamp = (src: HTMLCanvasElement) => liveCanvas(D, D, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, D, D);
    const dir = Math.floor((t / 1.2) % 8);
    const anim = Math.floor((t * 6) % 8);
    g.imageSmoothingEnabled = false;
    g.drawImage(src, anim * M, dir * M, M, M, 0, 0, D, D);
  });
  figure(mipStamp(atlasMipFor(pheno, atlas)), '<b>far-zoom mip</b> (quarter cell)', bakes);
  figure(mipStamp(mindedMipFor(pheno, atlas)), '<b>minded mip</b> (rim fused)', bakes);
  figure(mipStamp(corpseMipFor(pheno, atlas)), '<b>corpse mip</b>', bakes);
  } // end of the entries that need a live atlas

  // The expressions gallery: each badge over a living body, composed exactly
  // as the live loop composes it (glyph at two body-radii above, sized to the
  // body), so the icon is seen the way the world shows it.
  const EXPR: Array<[number, string, string]> = [
    [ACT_ATTACK, 'attack', 'striking at a target'],
    [ACT_HUNT, 'hunt', 'locked onto prey'],
    [ACT_FLEE, 'flee', 'running from a threat'],
    [ACT_GRAZE, 'graze', 'foraging vegetation'],
    [ACT_MATE, 'mate', 'courting a partner'],
    [ACT_AFFILIATE, 'affiliate', 'bonding with a packmate'],
    [ACT_GRAB, 'grab', 'carrying a captive, or riding a host'],
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
      // The badge is the subject here, not the body under it, so this gallery
      // renders with or without a live phenotype -- falling back to the same
      // placeholder the live viewer draws while an atlas is still in flight.
      if (atlas) {
        g.drawImage(atlas, anim * CELL, dir * CELL, CELL, CELL,
          bx - box / 2, by - box / 2, box, box);
      } else {
        drawPlaceholder(g, bx, by, rb, (dir / 8) * Math.PI * 2, '#8b9bb4', false);
      }
      drawActionGlyph(g, bx, by - rb * 2.0, rb * 0.95, act);
    }), `<b>${name}</b> — ${meaning}`, expressions);
  }

  // Concealment pairs: the client's veil code over the SAME staged ground the
  // Java scene was baked from. The Java pass veils the 3x3 tiles around the
  // occupant (tile 3,3 of the staged 7x7 world; the crop starts at tile 1,1),
  // so the demo veils exactly those — the live view veils every foliage tile,
  // which is equivalent because re-stamped pixels are invisible over the
  // identical ground.
  for (const [name, kind] of [['canopy', 1], ['reeds', 3], ['duct', 2]] as const) {
    if (!atlas) break; // the whole point is a body under the veil
    const ground = await loadCanvas(`/sprites/${name}_ground.png`);
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
    pair(conceal, `/sprites/${name}.png`, 160, `a body veiled in ${name}`, DC, DC, (g, t) => {
      g.imageSmoothingEnabled = false;
      g.drawImage(ground, 0, 0, DC, DC);
      const dir = Math.floor((t / 1.2) % 8);
      const anim = Math.floor((t * 6) % 8);
      g.drawImage(atlas, anim * CELL, dir * CELL, CELL, CELL,
        (DC - box) / 2, (DC - box) / 2, box, box);
      g.globalAlpha = VEIL_ALPHA;
      g.drawImage(veil, 0, 0, DC, DC);
      g.globalAlpha = 1;
    });
  }
})();

// ---- the viewer's overlay language --------------------------------------

{
  const overlay = section('Viewer overlay language — web client only',
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
    drawPlaceholder(g, S, y, S * 0.2, t, '#5a8ab9', false);
    drawPlaceholder(g, 3 * S, y, S * 0.2, -t, '#b95a8a', true);
  }), 'pre-atlas placeholders (plain · minded) with a carry tether', overlay);
  figure(liveCanvas(6 * S, S * 1.5, (g) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, 6 * S, S * 1.5);
    // Actual map-view size on the left, the same dots at 6x beside them.
    const y = S * 0.75, d = DOT_LOD_PX - 2;
    drawDot(g, S * 0.5, y, d, '#5a8ab9', false, false);
    drawDot(g, S * 0.85, y, d, '#b95a8a', true, false);
    drawDot(g, S * 1.2, y, d, '', false, true);
    drawDot(g, S * 2.6, y, d * 6, '#5a8ab9', false, false);
    drawDot(g, S * 3.9, y, d * 6, '#b95a8a', true, false);
    drawDot(g, S * 5.2, y, d * 6, '', false, true);
  }), `map-view dots — bodies under ${DOT_LOD_PX} px draw as blocks `
    + '(plain · minded · corpse; actual size, then 6×)', overlay);
}

// ---- pointers to the java-only sections ---------------------------------

const rest = section('Ground & creatures — java bake',
  'Ground chunks and creature bodies genuinely come from the server on the web too '
  + '(baked chunk PNGs; ProcCreature sprite atlases), so those sections have a single '
  + 'source of truth and live on the server-rendered reference page.');
{
  const p = document.createElement('p');
  p.className = 'note';
  p.innerHTML = 'See the <a href="/sprites/java">server-rendered catalog</a> for all 28 '
    + 'ground swatches, the depletion strip, the six creature samples and the action envelopes.';
  rest.append(p);
}
