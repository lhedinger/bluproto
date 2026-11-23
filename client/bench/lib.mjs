// Shared by the bench scripts: where the viewer is, and a Chromium to drive it.
//
// Headless Chromium renders WebGL on SwiftShader, a software rasteriser that
// pays per PIXEL. Absolute times here are pessimistic and not a phone's; the
// value is the relative signal between two runs on the same machine.

import { chromium } from 'playwright';

export const BASE = process.env.BENCH_URL || 'http://localhost:7070';

/** A browser with WebGL on SwiftShader (the client refuses software GL
 *  unless the page is opened with ?gl=1, which `open` adds). */
export async function launch() {
  const opts = { args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] };
  if (process.env.CHROMIUM) opts.executablePath = process.env.CHROMIUM;
  return chromium.launch(opts);
}

/** A page on the viewer, waited until the world has landed. */
export async function open(browser, { flags = '', hash = '', width = 900, height = 620 } = {}) {
  const page = await browser.newPage({ viewport: { width, height } });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  await page.goto(`${BASE}/?gl=1${flags ? '&' + flags : ''}${hash}`, { waitUntil: 'load' });
  await page.waitForFunction(() => window.__blu && window.__blu.state && window.__blu.state.tracks.size > 0,
    null, { timeout: 30000 });
  await page.waitForTimeout(1500);
  return { page, errors };
}

/** Puts the camera somewhere, not following anything. */
export function park(page, x, y, scale) {
  return page.evaluate(([x, y, s]) => {
    const c = window.__blu.cam; c.followId = null; c.cx = x; c.cy = y; c.scale = s;
  }, [x, y, scale]);
}

/** Frame intervals (ms) over `n` frames, the camera nudged by a hair each
 *  frame so the pacer draws every one. */
export function intervals(page, n) {
  return page.evaluate((n) => new Promise(res => {
    const c = window.__blu.cam, t = []; let s = 1;
    const step = (now) => {
      c.cx += 0.001 * s; s = -s; t.push(now);
      if (t.length <= n) requestAnimationFrame(step); else res(t.slice(1).map((x, i) => x - t[i]));
    };
    requestAnimationFrame(step);
  }), n);
}

export const quantile = (a, f) => {
  const s = [...a].sort((x, y) => x - y);
  return s.length ? s[Math.min(s.length - 1, Math.floor(f * s.length))] : NaN;
};
export const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
