// UI performance benchmark: drives the live viewer like a user and samples the
// renderer's own structured metrics (window.__bluPerf, published every frame
// by main.ts) across a ladder of zoom levels, on both render paths.
//
// Usage:
//   npm run bench                 # against http://localhost:7070
//   BENCH_URL=... npm run bench   # against any deployment (assumes low latency)
//   BENCH_PATHS=gl npm run bench  # gl | 2d | both (default both)
//
// Results print as a table and land in client/bench/out.json (gitignored).
//
// Caveat that belongs in every reading of the numbers: headless Chromium here
// renders GL on SwiftShader, a software rasteriser that pays per PIXEL, so
// absolute webgl frame times are pessimistic and NOT comparable to a real
// GPU's. The value of this harness is the relative signal — a change that
// doubles frame time, draw calls, upload cost or stream cost shows up loudly
// in a before/after run on the same machine. The report records the renderer
// string so a reading is never mistaken for hardware truth.

import { chromium } from 'playwright';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const BASE = process.env.BENCH_URL || 'http://localhost:7070';
const PATHS = (process.env.BENCH_PATHS || 'both').toLowerCase();
const WARMUP_MS = 3000;    // let caches build and the frame EMA settle
const SAMPLE_MS = 5000;    // measurement window per scenario
const SAMPLE_EVERY = 250;

// The zoom ladder, by TARGET BODY SIZE in on-screen px — each band pins one
// draw tier. Tiers are zoom-keyed (render.ts): every body is a dot below
// DOT_LOD_SCALE = 8 px/tile of camera zoom, a sprite above; sprite stamps use
// the quarter-res mip while the draw box stays ≤ 24 px (body ≤ ~10.5 via the
// atlas cell's padding factor CELL / (2*ART_RADIUS) ≈ 2.27), full-res above.
// With a ~0.5-tile median adult, bodyPx 6 parks the camera at ~6 px/tile
// (dots), 9 at ~9 px/tile (mip sprites), 40 well into full-res. The camera is
// parked over the densest creature cluster first, so every band actually
// draws a crowd instead of whatever terrain sits at the screen centre.
const SCENARIOS = [
  { name: 'fit', bodyPx: 0 },   // the whole-map view exactly as it loads
  { name: 'dots', bodyPx: 6 },
  { name: 'mip', bodyPx: 9 },
  { name: 'full', bodyPx: 40 },
];

/** Runs in the page: centre on the densest creature cluster and, for a body
 *  target, set the camera scale that renders the median creature at that
 *  size. Returns how many creatures the chosen cluster holds. */
function stageScenario([bodyPx, fit]) {
  const { cam, state } = window.__blu;
  // No z filter: the entity stream is already filtered to the viewed level.
  const cs = [...state.tracks.values()].map(t => t.curr)
    .filter(e => e.pheno && e.kind.startsWith('npc.'));
  if (!cs.length) return { n: 0 };
  const B = 12, buckets = new Map();
  for (const e of cs) {
    const k = Math.floor(e.x / B) * 4096 + Math.floor(e.y / B);
    buckets.set(k, (buckets.get(k) || 0) + 1);
  }
  let bk = 0, bn = 0;
  for (const [k, n] of buckets) if (n > bn) { bn = n; bk = k; }
  if (fit) {
    cam.cx = fit.cx; cam.cy = fit.cy; cam.scale = fit.scale;
    return { n: cs.length };
  }
  cam.cx = (Math.floor(bk / 4096) + 0.5) * B;
  cam.cy = ((bk % 4096) + 0.5) * B;
  const sizes = cs.map(e => e.size).sort((a, b) => a - b);
  const s = sizes[Math.floor(sizes.length / 2)] || 0.5;
  cam.scale = Math.min(cam.maxScale || 1e9, bodyPx / (2 * s));
  return { n: bn };
}

const q = (v, f) => { // quantile over a sorted copy
  const s = [...v].sort((a, b) => a - b);
  return s.length ? s[Math.min(s.length - 1, Math.floor(f * s.length))] : 0;
};
const med = v => q(v, 0.5);

async function health() {
  try {
    const r = await fetch(BASE + '/api/health');
    return r.ok ? await r.json() : null;
  } catch { return null; }
}

async function runPath(browser, glParam) {
  const page = await browser.newPage({ viewport: { width: 900, height: 620 } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e.message)));
  const navStart = Date.now();
  await page.goto(`${BASE}/?hud=1&gl=${glParam}`, { waitUntil: 'load' });

  // Startup: how long until the first world snapshot has landed.
  await page.waitForFunction(() => {
    const p = window.__bluPerf;
    return p && p.firstFullMs > 0;
  }, null, { timeout: 30000 });
  const firstFull = await page.evaluate(() => window.__bluPerf.firstFullMs);
  const fit = await page.evaluate(() => {
    const { cam } = window.__blu;
    return { cx: cam.cx, cy: cam.cy, scale: cam.scale };
  });

  const scenarios = [];
  for (const sc of SCENARIOS) {
    const staged = await page.evaluate(stageScenario, [sc.bodyPx, sc.bodyPx ? null : fit]);
    await page.waitForTimeout(WARMUP_MS);
    const t0 = Date.now();
    const samples = [];
    while (Date.now() - t0 < SAMPLE_MS) {
      samples.push(await page.evaluate(() => window.__bluPerf));
      await page.waitForTimeout(SAMPLE_EVERY);
    }
    const pick = k => samples.map(s => s[k] ?? 0);
    const last = samples[samples.length - 1];
    scenarios.push({
      name: sc.name,
      renderer: last.renderer,
      cluster: staged.n,
      scale: +last.scale.toFixed(2),
      frameMsMedian: +med(pick('frameMs')).toFixed(1),
      frameMsP90: +q(pick('frameMs'), 0.9).toFixed(1),
      fpsMedian: +(1000 / med(pick('frameMs'))).toFixed(1),
      drawCalls: Math.round(med(pick('drawCalls'))),
      quads: Math.round(med(pick('quads'))),
      uploadMsP90: +q(pick('uploadMs'), 0.9).toFixed(2),
      secLayersMed: +med(pick('secLayers')).toFixed(2),
      secEntsMed: +med(pick('secEnts')).toFixed(2),
      secTailMed: +med(pick('secTail')).toFixed(2),
      streamMs: +med(pick('streamMs')).toFixed(2),
      streamKb: Math.round(med(pick('streamKb'))),
      tex: Math.round(med(pick('tex'))),
      atlases: Math.round(med(pick('atlases'))),
      onLevel: Math.round(med(pick('onLevel'))),
      world: Math.round(med(pick('world'))),
    });
  }
  await page.close();
  return { glParam, firstFullMs: Math.round(firstFull), navMs: Date.now() - navStart, errors, scenarios };
}

const h = await health();
if (!h) {
  console.error(`No server at ${BASE} (GET /api/health failed). Start one: ./gradlew :server:run`);
  process.exit(1);
}
console.log(`bench → ${BASE} · tick ${h.tick} · ${h.entities} entities · sim ${h.tickMillis}/${h.tickBudgetMillis}ms`);

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM || '/opt/pw-browsers/chromium',
  args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
});

const runs = [];
if (PATHS === 'both' || PATHS === 'gl') runs.push(await runPath(browser, '1'));
if (PATHS === 'both' || PATHS === '2d') runs.push(await runPath(browser, '0'));
await browser.close();

for (const run of runs) {
  const label = run.scenarios[0]?.renderer || `gl=${run.glParam}`;
  console.log(`\n=== ${label} · first snapshot ${run.firstFullMs}ms after page start ===`);
  console.table(run.scenarios.map(({ name, cluster, frameMsMedian, frameMsP90, fpsMedian, drawCalls,
    quads, uploadMsP90, secLayersMed, secEntsMed, secTailMed, streamMs, streamKb, tex, atlases, onLevel }) => ({
    zoom: name, herd: cluster, 'ms(med)': frameMsMedian, 'ms(p90)': frameMsP90, fps: fpsMedian,
    calls: drawCalls, quads, 'up(p90)': uploadMsP90,
    layers: secLayersMed, ents: secEntsMed, tail: secTailMed,
    'stream ms': streamMs, kb: streamKb, tex, atlases, onLevel,
  })));
  if (run.errors.length) console.log('page errors:', run.errors.join(' | '));
}

const out = {
  at: new Date().toISOString(),
  base: BASE,
  sim: { tick: h.tick, entities: h.entities, tickMillis: h.tickMillis, budget: h.tickBudgetMillis },
  note: 'headless GL is SwiftShader (software): compare runs, do not read as hardware truth',
  runs,
};
const outPath = join(dirname(fileURLToPath(import.meta.url)), 'out.json');
mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(out, null, 2));
console.log(`\nwrote ${outPath}`);
