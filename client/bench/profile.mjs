// Render profile: a scripted run of phases on a local world, each reporting
// frame intervals (p50/p95/max), JS per frame, texture upload time, long
// tasks and draw calls -- and GPU time per frame where the browser has timer
// queries (never on SwiftShader).
//
//   node bench/profile.mjs <label> [runs] [flags]
//
// Prints a table of medians over the runs and writes bench/profile-<label>.json
// (gitignored). See lib.mjs for what software GL numbers are worth.

import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launch, open, quantile } from './lib.mjs';

const label = process.argv[2] || 'run';
const runs = Number(process.argv[3] || 2);
const flags = process.argv[4] || '';
const PHASES = [
  // [name, camera, held keys (each ms), duration ms]
  ['near, still', [144, 88, 24], [], 4000],
  ['near, panning', [144, 88, 24], [['d', 2500], ['s', 2500]], 5000],
  ['far, panning', [144, 88, 6], [['a', 2000], ['w', 2000]], 4000],
];

const browser = await launch();
const results = {};
for (let run = 0; run < runs; run++) {
  const { page, errors } = await open(browser, { flags });
  // Per-frame recorder: rAF timestamps plus the renderer's own __bluPerf.
  await page.evaluate(() => {
    const r = window.__rec = { t: [], cb: [], up: [], calls: [], gpu: [], on: false, longN: 0, longMax: 0 };
    try {
      new PerformanceObserver(l => { for (const e of l.getEntries()) if (r.on) { r.longN++; r.longMax = Math.max(r.longMax, e.duration); } })
        .observe({ type: 'longtask' });
    } catch { /* no long-task timing in this browser */ }
    const step = (now) => {
      if (r.on) {
        const q = window.__bluPerf;
        r.t.push(now); r.cb.push(q.cbMs); r.up.push(q.uploadMs); r.calls.push(q.drawCalls);
        if (q.gpuMs >= 0) r.gpu.push(q.gpuMs);
      }
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });
  for (const [name, [x, y, s], keys, ms] of PHASES) {
    await page.evaluate(([x, y, s]) => { const c = window.__blu.cam; c.followId = null; c.cx = x; c.cy = y; c.scale = s; }, [x, y, s]);
    await page.waitForTimeout(800);
    await page.evaluate(() => { const r = window.__rec; r.t = []; r.cb = []; r.up = []; r.calls = []; r.gpu = []; r.longN = 0; r.longMax = 0; r.on = true; });
    const t0 = Date.now();
    for (const [k, hold] of keys) { await page.keyboard.down(k); await page.waitForTimeout(hold); await page.keyboard.up(k); }
    const left = ms - (Date.now() - t0);
    if (left > 0) await page.waitForTimeout(left);
    const r = await page.evaluate(() => { const r = window.__rec; r.on = false; return r; });
    const iv = r.t.slice(1).map((t, i) => t - r.t[i]);
    (results[name] ||= []).push({
      frames: r.t.length, p50: quantile(iv, 0.5), p95: quantile(iv, 0.95), max: Math.max(...iv),
      js: quantile(r.cb, 0.5), upload: quantile(r.up, 0.95), calls: quantile(r.calls, 0.5),
      gpu: r.gpu.length ? quantile(r.gpu, 0.5) : NaN, longN: r.longN, longMax: r.longMax,
    });
  }
  if (errors.length) console.log('page errors:', errors);
  await page.close();
}
await browser.close();

const med = (a, k) => quantile(a.map(o => o[k]), 0.5);
const summary = {};
console.log(`== ${label} ==  (median of ${runs} runs; ms)`);
console.log('phase            frames  p50   p95   max    js   gpu  upl95 calls longN longMax');
for (const [name, rs] of Object.entries(results)) {
  const o = summary[name] = Object.fromEntries(Object.keys(rs[0]).map(k => [k, med(rs, k)]));
  const f = (v, w, d = 1) => (Number.isNaN(v) ? '-' : v.toFixed(d)).padStart(w);
  console.log(`${name.padEnd(16)} ${f(o.frames, 5, 0)} ${f(o.p50, 5)} ${f(o.p95, 5)} ${f(o.max, 6)} ${f(o.js, 5)} ${f(o.gpu, 5)} ${f(o.upload, 5)} ${f(o.calls, 5, 0)} ${f(o.longN, 5, 0)} ${f(o.longMax, 6, 0)}`);
}
const out = join(dirname(fileURLToPath(import.meta.url)), `profile-${label}.json`);
writeFileSync(out, JSON.stringify({ label, runs, flags, summary, results }, null, 2));
