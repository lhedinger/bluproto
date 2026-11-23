// Frame cadence: how evenly the pacer spaces the frames it draws, at zooms
// where it skips some. Judder is not a low rate but an uneven one -- 33, 50,
// 33, 50 ms -- so this counts how often two drawn intervals in a row differ
// by at least one refresh.
//
//   node bench/cadence.mjs [scales]      e.g. 4,6,8,12
//
// A drawn frame is one where __bluPerf.draws moved.

import { launch, open, park, quantile } from './lib.mjs';

const scales = (process.argv[2] || '4,6,8,12').split(',').map(Number);
const browser = await launch();
const { page } = await open(browser, { width: 480, height: 360 });
for (const s of scales) {
  await park(page, 120, 100, s);
  await page.waitForTimeout(2500);
  const r = await page.evaluate(() => new Promise(res => {
    const raf = [], drawn = []; let last = window.__bluPerf.draws;
    const step = (now) => {
      raf.push(now);
      const d = window.__bluPerf.draws;
      if (d !== last) { drawn.push(now); last = d; }
      if (raf.length < 400) requestAnimationFrame(step); else res({ raf, drawn, pace: window.__bluPerf.paceMs });
    };
    requestAnimationFrame(step);
  }));
  const refresh = quantile(r.raf.slice(1).map((t, i) => t - r.raf[i]), 0.25);
  const iv = r.drawn.slice(1).map((t, i) => t - r.drawn[i]);
  let changes = 0;
  for (let i = 1; i < iv.length; i++) if (Math.abs(iv[i] - iv[i - 1]) > refresh * 0.5) changes++;
  const steps = {};
  for (const v of iv) { const k = Math.round(v / refresh); steps[k] = (steps[k] || 0) + 1; }
  console.log(`scale ${String(s).padStart(3)}  pace ${r.pace.toFixed(0).padStart(3)} ms  drew ${iv.length + 1}/${r.raf.length}`
    + `  refreshes per draw ${JSON.stringify(steps)}  uneven ${(100 * changes / Math.max(1, iv.length - 1)).toFixed(0)}%`);
}
await browser.close();
