// The client half of the sprite catalog (/sprites): every entry here is drawn
// by the SAME functions the live viewer uses — drawDoor/drawSwitch/drawItem
// from render.ts, the corpse/rim bakes from atlas.ts — driven by scripted
// state instead of the world stream. The server-baked (Java) counterpart of
// each entry sits beside it as an <img>, so the two render pipelines can be
// compared at a glance and any drift between them is immediately visible.

import { CELL, atlasFor, corpseFor, rimFor } from './atlas';
import type { Camera } from './camera';
import type { EntityState } from './protocol';
import { drawDoor, drawItem, drawNest, drawSwitch } from './render';

const root = document.getElementById('root')!;
const GRASS = '#3f7a38'; // flat stand-in for the baked ground the viewer has

// One page, two addresses: /sprites shows the java bake beside each client
// canvas (the comparison), /sprites/web hides the java halves — the catalog
// of what THIS browser's own drawing code produces, nothing else.
const webOnly = location.pathname.replace(/\/+$/, '').endsWith('/web');

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
    ? 'See also the <a href="/sprites">side-by-side comparison</a> '
      + 'or the <a href="/sprites/java">pure java bake</a>.'
    : 'Also: <a href="/sprites/web">web-client rendering only</a> · '
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
    // The viewer's haze: a soft radial magenta breathing out as strength decays
    // (the exact gradient render.ts draws for kind "phero").
    const decay = 1 - ((t % 8) / 8);
    const r = Math.max(2, (1.4 + 2.6 * decay) * S * 0.5);
    const grad = g.createRadialGradient(2 * S, 2 * S, 0, 2 * S, 2 * S, r);
    grad.addColorStop(0, 'rgba(230,40,190,0.20)');
    grad.addColorStop(1, 'rgba(230,40,190,0)');
    g.fillStyle = grad;
    g.beginPath();
    g.arc(2 * S, 2 * S, r, 0, 7);
    g.fill();
  });
}

// ---- web-only bakes: living body, corpse, minded rim --------------------

const bakes = section('Atlas bakes — web client only',
  'What the viewer itself makes of a creature\'s server-baked sprite atlas: the living '
  + 'stamp, the drained corpse bake (colour stripped, body kept), and the minded rim. '
  + 'The atlas comes from a phenotype alive in this world right now.');

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
  if (!pheno) return;
  // atlasFor starts the fetch and returns null until loaded; poll until ready.
  const atlas = await new Promise<HTMLCanvasElement>((resolve) => {
    const poll = () => { const a = atlasFor(pheno); a ? resolve(a) : setTimeout(poll, 100); };
    poll();
  });
  const D = 192;
  const stamp = (src: CanvasImageSource) => liveCanvas(D, D, (g, t) => {
    g.fillStyle = GRASS;
    g.fillRect(0, 0, D, D);
    const dir = Math.floor((t / 1.2) % 8);         // slow spin through the 8 facings
    const anim = Math.floor((t * 6) % 8);          // the idle gait
    g.imageSmoothingEnabled = false;
    g.drawImage(src, anim * CELL, dir * CELL, CELL, CELL, 0, 0, D, D);
  });
  figure(stamp(atlas), '<b>living stamp</b> (atlas cell)', bakes);
  figure(stamp(corpseFor(pheno, atlas)), '<b>corpse bake</b> (drained, transparent)', bakes);
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
})();

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
