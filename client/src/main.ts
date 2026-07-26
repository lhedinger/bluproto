// Entry point: wires net → state → camera → render, plus the toolbar.
// A tap follows the creature under the finger; with a spawn kind selected, a
// tap on open ground drops that item instead (through the token-gated command
// channel). Panning hands the camera back.

import { Camera } from './camera';
import { Net } from './net';
import type { HelloMsg, ServerMsg } from './protocol';
import { F_DEAD } from './protocol';
import { render, type WorldMeta } from './render';
import { RENDER_DELAY_MS, WorldState } from './state';

const cv = document.getElementById('cv') as HTMLCanvasElement;
const g = cv.getContext('2d')!;
const statsEl = document.getElementById('stats')!;
const pauseBtn = document.getElementById('pause') as HTMLButtonElement;
const speedSel = document.getElementById('speed') as HTMLSelectElement;
const spawnSel = document.getElementById('spawn') as HTMLSelectElement;
const followEl = document.getElementById('follow')!;
const toastEl = document.getElementById('toast')!;

const state = new WorldState();
const cam = new Camera(cv);
let meta: WorldMeta | null = null;
let hello: HelloMsg | null = null;
let layer: HTMLImageElement | null = null;
let paused = false;

function resize(): void {
  const dpr = window.devicePixelRatio || 1;
  cv.width = Math.round(cv.clientWidth * dpr);
  cv.height = Math.round(cv.clientHeight * dpr);
}
window.addEventListener('resize', resize);
resize();

const net = new Net(onMsg, s => {
  if (s !== 'open') statsEl.textContent = s === 'connecting' ? 'connecting…' : 'reconnecting…';
});

function onMsg(m: ServerMsg, receivedAt: number): void {
  switch (m.type) {
    case 'hello': {
      hello = m;
      meta = { cols: m.cols, rows: m.rows };
      paused = m.paused;
      speedSel.value = String(m.speed);
      layer = new Image();
      layer.src = m.layers[0];
      cam.fit(m.cols, m.rows);
      reflect();
      break;
    }
    case 'full':
      state.applyFull(m, receivedAt);
      break;
    case 'delta':
      state.applyDelta(m, receivedAt);
      break;
    case 'status':
      paused = m.paused;
      speedSel.value = String(m.speed);
      reflect();
      break;
    case 'error':
      toast(m.message);
      break;
    case 'ack':
      break;
  }
}
net.connect();

// ---- interaction -----------------------------------------------------------

cam.attach(tap => {
  // Prefer following: pick the nearest living creature within a finger-sized
  // radius of the tap.
  const fingerTiles = Math.max(0.35, 14 / cam.scale);
  let best: number | null = null;
  let bestD = Infinity;
  for (const [id, t] of state.tracks) {
    const e = t.curr;
    if (e.kind === 'phero' || e.flags & F_DEAD) continue;
    const d = Math.hypot(e.x - tap.x, e.y - tap.y);
    if (d < Math.max(fingerTiles, e.size * 2) && d < bestD) {
      best = id;
      bestD = d;
    }
  }
  if (best !== null) {
    cam.followId = best;
    reflect();
    return;
  }
  const kind = spawnSel.value;
  if (kind && meta && tap.x > 0 && tap.y > 0 && tap.x < meta.cols && tap.y < meta.rows) {
    net.send({ cmd: 'spawnItem', kind, x: tap.x, y: tap.y, z: 0 });
  }
});

pauseBtn.onclick = () => net.send({ cmd: paused ? 'resume' : 'pause' });
speedSel.onchange = () => net.send({ cmd: 'speed', value: parseFloat(speedSel.value) });
followEl.addEventListener('click', () => {
  cam.followId = null;
  reflect();
});

function reflect(): void {
  pauseBtn.textContent = paused ? 'resume' : 'pause';
  pauseBtn.classList.toggle('on', paused);
  if (cam.followId !== null) {
    const t = state.tracks.get(cam.followId);
    followEl.textContent = `following ${t ? t.curr.kind.replace('npc.', '') : '…'} ✕`;
    (followEl as HTMLElement).style.display = 'inline-block';
  } else {
    (followEl as HTMLElement).style.display = 'none';
  }
}

let toastTimer = 0;
function toast(msg: string): void {
  toastEl.textContent = msg;
  (toastEl as HTMLElement).style.opacity = '1';
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => ((toastEl as HTMLElement).style.opacity = '0'), 2500);
}

// ---- frame loop ------------------------------------------------------------

// Test/debug handle: lets an automated browser (or a curious dev console)
// read live state and camera without any UI coupling.
(window as unknown as Record<string, unknown>).__blu = { cam, state };

let lastStats = 0;
function frame(now: number): void {
  const renderTime = now - RENDER_DELAY_MS;

  // Follow: glue the camera to the tracked creature's interpolated position.
  if (cam.followId !== null) {
    const t = state.tracks.get(cam.followId);
    if (t) {
      const p = state.sample(t, renderTime);
      cam.cx = p.x;
      cam.cy = p.y;
    } else {
      cam.followId = null; // it died away or despawned
      reflect();
    }
  }

  render(g, cam, state, meta, layer, renderTime);

  if (net.status === 'open' && now - lastStats > 250) {
    lastStats = now;
    statsEl.textContent = `tick ${state.tick} · ${state.tracks.size} entities` +
        (hello ? ` · seed ${hello.seed}` : '');
  }
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
