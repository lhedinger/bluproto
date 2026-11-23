// Draw calls over the densest herd on the surface, at a ladder of zooms:
// what sprite batching is worth. The camera is parked on the 20 x 14 tile
// cell holding the most creatures.
//
//   node bench/calls.mjs [scales] [flags]      e.g. 12,24,48 sprites=0

import { launch, open, intervals, quantile } from './lib.mjs';

const scales = (process.argv[2] || '12,24,48').split(',').map(Number);
const flags = process.argv[3] || '';
const browser = await launch();
const { page } = await open(browser, { flags });
for (const scale of scales) {
  await page.evaluate((scale) => {
    const b = window.__blu, cell = new Map();
    let best = null, most = 0;
    for (const [, t] of b.state.tracks) {
      const e = t.curr;
      if (!String(e.kind).startsWith('npc.')) continue;
      const k = Math.floor(e.x / 20) + ',' + Math.floor(e.y / 14);
      const n = (cell.get(k) || 0) + 1;
      cell.set(k, n);
      if (n > most) { most = n; best = [e.x, e.y]; }
    }
    const c = b.cam; c.followId = null; c.cx = best[0]; c.cy = best[1]; c.scale = scale;
  }, scale);
  await page.waitForTimeout(2500);
  const calls = [];
  for (let i = 0; i < 20; i++) {
    await intervals(page, 1);
    calls.push(await page.evaluate(() => window.__bluPerf.drawCalls));
  }
  console.log(`${(flags || 'all on').padEnd(10)} scale ${String(scale).padStart(3)}  draw calls ${quantile(calls, 0.5)}`);
}
await browser.close();
