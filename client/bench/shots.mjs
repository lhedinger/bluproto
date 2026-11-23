// Visual regression: fixed views of a PAUSED world, for comparing two builds.
//
//   node bench/shots.mjs pause            # pause the world (admin control)
//   node bench/shots.mjs take <dir>       # screenshot every view into <dir>
//   node bench/shots.mjs diff <a> <b>     # count differing pixels per view
//   node bench/shots.mjs resume
//
// A paused world draws the same frame for the same camera: the playhead, the
// gait and the flow clock all stop. So take, rebuild, take again and diff:
// a refactor that should change nothing must come out at zero.

import { mkdirSync, readdirSync, readFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import { launch, open, park } from './lib.mjs';

const [cmd, a, b] = process.argv.slice(2);
const FLAGS = process.env.SHOT_FLAGS || '';
// Spots on the default seed-7 demo world, each at the zoom ladder.
const VIEWS = [['forest', 120, 100], ['spring', 94, 13], ['fall', 85.5, 139.5], ['lake', 140, 60]];
const ZOOMS = [4, 9, 12, 18, 24, 48];

const browser = await launch();
if (cmd === 'pause' || cmd === 'resume') {
  const { page } = await open(browser, { hash: '#t=local', width: 480, height: 360 });
  const label = await page.evaluate(() => document.getElementById('pause').textContent);
  if (label === cmd) await page.evaluate(() => document.getElementById('pause').click());
  await page.waitForTimeout(1500);
  console.log('world', cmd === 'pause' ? 'paused' : 'running');
} else if (cmd === 'take') {
  mkdirSync(a, { recursive: true });
  const { page, errors } = await open(browser, { flags: FLAGS, width: 480, height: 360 });
  for (const [name, x, y] of VIEWS) {
    for (const s of ZOOMS) {
      await park(page, x, y, s);
      await page.waitForTimeout(1800);
      await page.screenshot({ path: join(a, `${name}-${s}.png`) });
    }
  }
  console.log(`${VIEWS.length * ZOOMS.length} views in ${a}`, errors.length ? errors : '');
} else if (cmd === 'diff') {
  const page = await browser.newPage();
  for (const f of readdirSync(a).filter(f => f.endsWith('.png')).sort()) {
    if (!existsSync(join(b, f))) continue;
    const pa = readFileSync(join(a, f)).toString('base64'), pb = readFileSync(join(b, f)).toString('base64');
    const n = await page.evaluate(async ([pa, pb]) => {
      const img = async (d) => { const i = new Image(); i.src = 'data:image/png;base64,' + d; await i.decode(); return i; };
      const [A, B] = await Promise.all([img(pa), img(pb)]);
      const px = (i) => { const c = new OffscreenCanvas(i.width, i.height), g = c.getContext('2d');
        g.drawImage(i, 0, 0); return g.getImageData(0, 0, i.width, i.height).data; };
      const da = px(A), db = px(B); let n = 0;
      for (let k = 0; k < da.length; k += 4) {
        if (Math.abs(da[k] - db[k]) + Math.abs(da[k + 1] - db[k + 1]) + Math.abs(da[k + 2] - db[k + 2]) > 24) n++;
      }
      return [n, A.width * A.height];
    }, [pa, pb]);
    console.log(`${f.padEnd(16)} ${String(n[0]).padStart(7)} px differ (${(100 * n[0] / n[1]).toFixed(2)}%)`);
  }
} else {
  console.log('usage: shots.mjs pause | resume | take <dir> | diff <a> <b>');
}
await browser.close();
