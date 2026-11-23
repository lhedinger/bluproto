// What each layer costs: the mean frame interval at near zoom with each flag
// switched off in turn (the flags are the ⚙ dialog's perf switches).
//
//   node bench/passes.mjs ["",veg=0,flow=0,...]
//
// The mean over ninety frames, not the median: on software GL a frame lands
// on whole vsync steps, and a median moves in 16.7 ms jumps that hide a pass
// costing less than one.

import { launch, open, park, intervals, mean } from './lib.mjs';

const sets = (process.argv[2] ?? ',veg=0,flow=0,canopy=0,glints=0,parallax=0,sprites=0,flora=0').split(',');
const browser = await launch();
for (const flags of sets) {
  const res = [];
  for (let r = 0; r < 2; r++) {
    const { page } = await open(browser, { flags });
    await park(page, 120, 100, 24);
    await page.waitForTimeout(2000);
    res.push(mean(await intervals(page, 90)));
    await page.close();
  }
  console.log(`${(flags || 'all on').padEnd(12)} frame mean ${mean(res).toFixed(1)} ms`);
}
await browser.close();
