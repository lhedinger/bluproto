// The population panel: headcount by trophic role over time, or the lineage
// flows between species, polled while it is open.

import { flagOn } from './flags';
import { closePanel, isShortcut, openPanel, rightColumn, wirePanelChrome } from './panels';

// ---- population graph ('p' key, the bar button, or ?pop=1) -----------------
//
// Headcount by trophic role over time. The series lives on the SERVER, in a
// ring it fills every POP_SAMPLE_SEC seconds — so opening this panel on a world
// that has been running for hours shows those hours, rather than starting a
// fresh chart from whenever this browser happened to connect. All this end does
// is fetch it and draw.

const popEl = document.getElementById('pop') as HTMLElement;
const popBtn = document.getElementById('popBtn') as HTMLButtonElement;
const popCv = document.getElementById('popCv') as HTMLCanvasElement;
const popKey = document.getElementById('popKey') as HTMLElement;
const popWin = document.getElementById('popWin') as HTMLElement;
/** The lineage diagram's scrollbar, ABOVE the chart: a Bulkhead rail and its
 *  thumb, drawn rather than native because a phone hides a native scrollbar
 *  until you are already scrolling. The canvas stays the size of the panel and
 *  redraws at the offset, so a hundred stages never ask for a canvas wider
 *  than a browser will allocate. */
const popScroll = document.getElementById('popScroll') as HTMLElement;
const popThumb = popScroll.firstElementChild as HTMLElement;

/** Series colours: herbivore green, predator red, scavenger amber, parasite
 *  violet. Distinct in luminance as well as hue, so the lines stay separable
 *  to a colour-blind viewer and in a screenshot that has lost its colour. */
const POP_SERIES = [
  { key: 'herbivore' as const, label: 'herbivore', rgb: '#6fbf5e' },
  { key: 'predator' as const, label: 'predators', rgb: '#e0574f' },
  { key: 'scavenger' as const, label: 'scavengers', rgb: '#e2a63b' },
  { key: 'parasite' as const, label: 'parasites', rgb: '#b07fe0' },
];

/** The steward's guardrails for one clade: the headcount it restores a failing
 *  clade from, and the one it culls back under. */
type CladeBounds = { floor: number; ceiling: number };

type PopData = {
  sampleSec: number; tps: number; tick: number[];
  herbivore: number[]; predator: number[]; scavenger: number[]; parasite: number[];
  /** Absent on a world with no steward in it — then no guides are drawn. */
  bounds?: Partial<Record<string, CladeBounds>>;
};

/** The Sankey's feed: WHO existed at each stage and exactly where every head
 *  went between one stage and the next — continued as itself, drifted across a
 *  species boundary, died, or arrived newborn. Flows are per-individual diffs
 *  between the two stages drawn, so the ribbons never invent or lose a head. */
type FlowData = {
  tps: number; stageSec: number;
  stages: Array<{ tick: number; species: Array<{ key: string; rgb: number; count: number }> }>;
  /** Each head of the later stage traced to ONE source: `held` (the same body,
   *  still alive), `bred` (descended from a head of the earlier stage, through
   *  however many generations came and went between), `reseed` (a founder the
   *  warden landed since), `unknown` (a line the registry cannot follow); and
   *  `died` for a line of the earlier stage that ended -- a body that died
   *  leaving descendants is carried by their ribbon, not reported dead. */
  flows: Array<{ stage: number; kind: FlowKind; from: string; to: string; n: number }>;
};
type FlowKind = 'held' | 'bred' | 'reseed' | 'unknown' | 'died';
/** The warden's own colour on the diagram: the facility's hazard yellow, which
 *  is what its drones wear. A reseed ribbon in a species tint would read as
 *  that species breeding, which is exactly the lie the kinds exist to stop. */
const WARDEN = '#d8b028';
const UNKNOWN_TINT = '#8b93a3';

export let popOn = flagOn('pop');
/** Which lens the panel shows: the four trophic roles over time, or the
 *  lineage Sankey — species as node columns with ribbons carrying each head to
 *  where it actually went, which is the view that shows a lineage dying out
 *  (its ribbon ends) and the niche being reseeded (a new one begins). */
let popMode: 'roles' | 'lineages' = 'roles';
/** Which clade the Sankey shows. Always one: a lineage never crosses clades
 *  (a clade is inherited and never mutated), so a diagram of all of them is
 *  four unrelated diagrams interleaved, and the cut is free of charge -- no
 *  ribbon can ever be severed by it. */
const LIN_CLADES = ['herbivore', 'predator', 'scavenger', 'parasite'] as const;
let linClade: (typeof LIN_CLADES)[number] = 'herbivore';
function linCladeMatch(key: string): boolean {
  return key.startsWith(linClade + '/');
}
/** Which ROLE series are drawn. Prey outnumber predators by an order of
 *  magnitude on one shared axis, so the legend lets a viewer drop the big
 *  series and give the rest the height. Roles only: the Sankey accounts for
 *  every head by construction, and hiding a species would break exactly that. */
const popHidden = new Set<string>();
let popData: PopData | null = null;
let linData: FlowData | null = null;
let popFetching = false;
let popLastFetch = 0;

/** A species tint, floored to chart-legible. The umbral centroid is honestly
 *  near-black — its creatures wear that — but a near-black bar on this panel
 *  is a species the chart cannot show. Lifted toward white just enough to
 *  draw with; display only — the world paints bodies in their true colours. */
function liftForDark(rgb: number): string {
  let r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
  const luma = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  const floor = 0.3;
  if (luma < floor) {
    const t = (floor - luma) / (1 - luma); // exactly enough lift, no more
    r = Math.round(r + (255 - r) * t);
    g = Math.round(g + (255 - g) * t);
    b = Math.round(b + (255 - b) * t);
  }
  return '#' + ((r << 16) | (g << 8) | b).toString(16).padStart(6, '0');
}

/** "ochre herbivore", from "herbivore/ochre". */
function speciesLabel(key: string): string {
  const slash = key.indexOf('/');
  return slash < 0 ? key : key.slice(slash + 1) + ' ' + key.slice(0, slash);
}

function popApply(): void {
  if (popOn) openPanel(popEl); else closePanel(popEl);
  popBtn.classList.toggle('on', popOn);
  if (popOn) {
    popPoll(true);
    popDraw(); // the panel may have been resized while closed
  }
}
function closePop(): void {
  popOn = false;
  popApply();
}

/** Fetches the current lens's data, at most once per its own cadence: the
 *  role series changes every server sample, the Sankey only when a new stage
 *  lands, minutes apart — polling it faster would re-draw the same ribbons. */
function popPoll(force = false): void {
  const now = performance.now();
  const everyMs = popMode === 'roles' ? (popData ? popData.sampleSec : 5) * 1000 : 30_000;
  if (popFetching || (!force && now - popLastFetch < everyMs)) return;
  popFetching = true;
  popLastFetch = now;
  const mode = popMode;
  fetch(mode === 'roles' ? '/api/population' : `/api/lineage/flows?cols=${LIN_MAX_COLS}`)
    .then(r => (r.ok ? r.json() : null))
    .then((d: (PopData & FlowData) | null) => {
      if (!d) return;
      if (mode === 'roles' && Array.isArray(d.tick)) popData = d;
      else if (mode === 'lineages' && Array.isArray(d.stages)) linData = d;
      else return;
      popDraw();
    })
    .catch(() => { /* a missing sample is not worth a toast */ })
    .finally(() => {
      popFetching = false;
      // The lens switched while this was in flight, and the switch's own forced
      // fetch was turned away by the in-flight guard: ask for the new lens now,
      // not at its next beat (half a minute away for lineages).
      if (mode !== popMode && popOn) popPoll(true);
    });
}

function popDraw(): void {
  const ctx = popCv.getContext('2d');
  if (!ctx) return;
  // Draw at device resolution and let CSS scale it back down, so hairlines and
  // text stay crisp on a phone.
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const w = Math.max(1, Math.round(popCv.clientWidth * dpr));
  const h = Math.max(1, Math.round(popCv.clientHeight * dpr));
  if (popCv.width !== w || popCv.height !== h) { popCv.width = w; popCv.height = h; }
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, w, h);
  if (popMode === 'lineages') {
    sankeyDraw(ctx, w, h, dpr);
  } else {
    syncLinScroll(0);
    rolesDraw(ctx, w, h, dpr);
  }
  popKeyDraw();
}

function rolesDraw(ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number): void {
  const d = popData;
  const padL = 30 * dpr, padR = 4 * dpr, padT = 4 * dpr, padB = 12 * dpr;
  const gw = w - padL - padR, gh = h - padT - padB;
  const n = d ? d.tick.length : 0;
  if (!d || n < 2 || gw <= 0 || gh <= 0) {
    gathering(ctx, dpr, padL, padT + gh / 2);
    return;
  }
  // One shared vertical scale: every line is the same kind of quantity, and
  // separate axes would hide that predators are a tenth of the herbivores.
  let max = 1;
  for (const s of POP_SERIES) {
    if (popHidden.has(s.key)) continue;
    for (const v of d[s.key]) if (v > max) max = v;
    // A visible clade's ceiling is part of the picture, so it has to fit in
    // it: the whole reason to draw the line is to see how much headroom is
    // left, and a guide scrolled off the top of the plot answers nothing. It
    // costs some height when a cohort sits far under its cap — which is itself
    // the reading, and the legend still drops whichever clade is squashing the
    // rest. Hidden clades are not consulted, so dropping the herbivores drops
    // their ceiling out of the axis with them.
    const c = d.bounds?.[s.key]?.ceiling;
    if (c !== undefined && c > max) max = c;
  }
  max = niceCeil(max);
  const xAt = (i: number) => padL + (gw * i) / (n - 1);
  const yAt = (v: number) => padT + gh - (gh * v) / max;

  // Axis: a floor and a ceiling is all a shape needs.
  ctx.strokeStyle = '#ffffff1a';
  ctx.lineWidth = 1 * dpr;
  ctx.beginPath();
  for (const v of [0, max]) {
    ctx.moveTo(padL, Math.round(yAt(v)) + 0.5);
    ctx.lineTo(padL + gw, Math.round(yAt(v)) + 0.5);
  }
  ctx.stroke();
  ctx.fillStyle = '#9aa2b1';
  ctx.font = `${10 * dpr}px ui-monospace, monospace`;
  ctx.textAlign = 'right';
  ctx.fillText(String(max), padL - 4 * dpr, yAt(max) + 8 * dpr);
  ctx.fillText('0', padL - 4 * dpr, yAt(0));
  ctx.textAlign = 'left';

  // The steward's guardrails, under the series so a population line always
  // wins where they cross. Dashed and faint on purpose: these are not measured
  // quantities but the rails the measured one runs between, and drawn solid at
  // full strength they read as four more populations. Each takes its clade's
  // own colour, because a legend with four entries cannot also explain eight
  // lines — the colour says whose rail it is and the dash says it is a rail.
  // Clades routinely SHARE a bound — three of the four sit at the same floor
  // and the same ceiling in the standard world — so drawn in phase they land
  // on exactly the same pixels and the last one painted is the only one you
  // can see, which reads as a rail belonging to one clade rather than to
  // three. Each series therefore gets its own quarter of the dash period and
  // draws only in that slot: coincident rails interleave into one line that
  // alternates colours, distinct rails just look dashed. The value is never
  // nudged to make room — a guide that is off by a pixel to be legible is a
  // guide that lies about the number it exists to show.
  const slot = 4 * dpr, period = slot * POP_SERIES.length;
  ctx.save();
  ctx.setLineDash([slot, period - slot]);
  ctx.lineWidth = 1 * dpr;
  ctx.globalAlpha = 0.5;
  POP_SERIES.forEach((s, i) => {
    // Tied to the series, not to the data: hiding a clade takes its rails with
    // it. A guide for a line that is not on the chart is an orphan the legend
    // gives the viewer no way to explain or get rid of.
    if (popHidden.has(s.key)) return;
    const b = d.bounds?.[s.key];
    if (!b) return;
    ctx.lineDashOffset = i * slot;
    ctx.strokeStyle = s.rgb;
    ctx.beginPath();
    for (const v of [b.floor, b.ceiling]) {
      if (v > max) continue; // only ever the floor, on an axis a ceiling lifted
      const y = Math.round(yAt(v)) + 0.5;
      ctx.moveTo(padL, y);
      ctx.lineTo(padL + gw, y);
    }
    ctx.stroke();
  });
  ctx.restore();

  for (const s of POP_SERIES) {
    if (popHidden.has(s.key)) continue;
    ctx.strokeStyle = s.rgb;
    ctx.lineWidth = 1.5 * dpr;
    ctx.lineJoin = 'round';
    ctx.beginPath();
    for (let i = 0; i < n; i++) {
      const x = xAt(i), y = yAt(d[s.key][i]);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    }
    ctx.stroke();
  }
  const spanSec = (n - 1) * d.sampleSec;
  // Say what the dashes are. Four extra lines appeared on this chart with
  // nothing on screen to name them, and a dashed line among solid ones reads
  // as another measurement until something says otherwise.
  const rails = POP_SERIES.some(s => !popHidden.has(s.key) && d.bounds?.[s.key]);
  popWin.textContent = `${fmtSpan(spanSec)} · ${n} samples`
    + (rails ? ' · dashed: steward floor/ceiling' : '');
}

/** One node's box, for ribbon anchoring: outCur/inCur walk down its right and
 *  left edges as flows claim their spans. */
type SankeyNode = { x: number; yTop: number; h: number; rgb: string; outCur: number; inCur: number };

/** Every stage the server's ring holds (WorldHost.STAGE_KEEP). */
const LIN_MAX_COLS = 100;
/** Column pitch in CSS px once the stages outgrow the panel: room for a
 *  ribbon to be followed across and a time label under every column. */
const LIN_PITCH = 72, LIN_PITCH_PHONE = 56;
/** Pinned to the latest stage: true until the reader scrolls back, so a new
 *  stage landing keeps "now" in view instead of pushing it off the edge. */
let linFollow = true;
/** The bars as last drawn, in device px, for the pointer readout. */
let linHits: Array<{ x: number; y: number; w: number; h: number; key: string; count: number; tick: number }> = [];
let linSummary = '';
let linPointing = false;
let linLastTick = 0, linTps = 33;

/** The diagram's horizontal offset and extent, in CSS px. */
let linScroll = 0, linContent = 0, linView = 0;
const linMax = () => Math.max(0, linContent - linView);

/** Sizes the rail to the diagram (content 0: nothing to scroll, rail hidden),
 *  keeps the view on now while following, and places the thumb. */
function syncLinScroll(contentCss: number, viewCss = 0): void {
  const on = contentCss > 0;
  if (popScroll.hidden === on) {
    popScroll.hidden = !on;
    // The rail takes height from the chart under it: draw again at the new size.
    requestAnimationFrame(popDraw);
  }
  linContent = contentCss;
  linView = viewCss;
  if (!on) return;
  linScroll = linFollow ? linMax() : Math.min(linScroll, linMax());
  const track = popScroll.clientWidth;
  const tw = Math.max(28, track * Math.min(1, linView / linContent));
  popThumb.style.width = `${tw}px`;
  popThumb.style.transform = `translateX(${linMax() > 0 ? (linScroll / linMax()) * (track - tw) : 0}px)`;
  popScroll.setAttribute('aria-valuenow', String(Math.round(linMax() > 0 ? (100 * linScroll) / linMax() : 100)));
}

let linRaf = 0;
function setLinScroll(x: number): void {
  linScroll = Math.max(0, Math.min(linMax(), x));
  // Following stops the moment the reader leaves the right edge, and resumes
  // when they come back to it.
  linFollow = linScroll >= linMax() - 2;
  if (!linRaf) linRaf = requestAnimationFrame(() => { linRaf = 0; popDraw(); });
}

// The rail: drag the thumb, or press anywhere on the rail to bring the thumb
// there. Arrow keys step a stage, Page keys a screen, Home and End the ends.
let thumbFrom: { x: number; scroll: number } | null = null;
popScroll.addEventListener('pointerdown', ev => {
  const r = popScroll.getBoundingClientRect();
  const tw = popThumb.offsetWidth;
  const perPx = linMax() / Math.max(1, r.width - tw);
  if (ev.target !== popThumb) setLinScroll((ev.clientX - r.left - tw / 2) * perPx);
  thumbFrom = { x: ev.clientX, scroll: linScroll };
  popScroll.setPointerCapture(ev.pointerId);
  ev.preventDefault();
});
popScroll.addEventListener('pointermove', ev => {
  if (!thumbFrom) return;
  const r = popScroll.getBoundingClientRect();
  const perPx = linMax() / Math.max(1, r.width - popThumb.offsetWidth);
  setLinScroll(thumbFrom.scroll + (ev.clientX - thumbFrom.x) * perPx);
});
const endThumb = () => { thumbFrom = null; };
popScroll.addEventListener('pointerup', endThumb);
popScroll.addEventListener('pointercancel', endThumb);
popScroll.addEventListener('keydown', ev => {
  const step = (linView < 600 ? LIN_PITCH_PHONE : LIN_PITCH);
  const by: Record<string, number> = { ArrowLeft: -step, ArrowRight: step, PageUp: -linView, PageDown: linView };
  if (ev.key in by) setLinScroll(linScroll + by[ev.key]);
  else if (ev.key === 'Home') setLinScroll(0);
  else if (ev.key === 'End') setLinScroll(linMax());
  else return;
  ev.preventDefault();
});

// The chart itself scrolls too: a horizontal drag (a finger, or a mouse held
// down), and a wheel, vertical or sideways. The track is the visible
// affordance; nobody should have to aim for it.
let dragX: number | null = null;
popCv.addEventListener('pointerdown', ev => {
  if (popMode !== 'lineages' || popScroll.hidden) return;
  dragX = ev.clientX;
  popCv.setPointerCapture(ev.pointerId);
});
popCv.addEventListener('pointermove', ev => {
  if (dragX !== null) {
    setLinScroll(linScroll - (ev.clientX - dragX));
    dragX = ev.clientX;
  }
  linReadout(ev);
});
const endDrag = () => { dragX = null; };
popCv.addEventListener('pointerup', endDrag);
popCv.addEventListener('pointercancel', endDrag);
popCv.addEventListener('pointerleave', () => {
  linPointing = false;
  if (popMode === 'lineages') popWin.textContent = linSummary;
});
popCv.addEventListener('wheel', ev => {
  if (popMode !== 'lineages' || popScroll.hidden) return;
  ev.preventDefault();
  setLinScroll(linScroll + (Math.abs(ev.deltaX) > Math.abs(ev.deltaY) ? ev.deltaX : ev.deltaY));
}, { passive: false });

/** Names the bar under the pointer in the readout line: which species, how
 *  many heads, and when. The middle columns carry no labels, and a log-width
 *  bar cannot be read as a number, so this is how a middle column is read. */
function linReadout(ev: PointerEvent): void {
  if (popMode !== 'lineages') return;
  const r = popCv.getBoundingClientRect();
  const dpr = popCv.width / Math.max(1, r.width);
  const x = (ev.clientX - r.left) * dpr, y = (ev.clientY - r.top) * dpr;
  const slack = 6 * dpr; // a bar is 9 px wide; a finger is not
  const hit = linHits.find(b => x >= b.x - slack && x <= b.x + b.w + slack && y >= b.y && y <= b.y + b.h);
  linPointing = !!hit;
  if (!hit) { popWin.textContent = linSummary; return; }
  const ago = (linLastTick - hit.tick) / linTps;
  popWin.textContent = `${speciesLabel(hit.key)} · ${hit.count} head${hit.count === 1 ? '' : 's'} · `
    + (ago <= 0 ? 'now' : `${fmtSpan(ago)} ago`);
}

/**
 * The lineage Sankey. Each stage is a column of species bars; ribbons carry
 * each head to where it actually went — a species into itself, into another
 * species where its members drifted across a label boundary, in from a birth,
 * out to a death.
 *
 * <p>Widths are PSEUDO-LOGARITHMIC: a band of n heads is 1+log10(n) units
 * wide, so one head is a visible sliver, ten are twice that, a hundred three
 * times — the standard answer when magnitudes span orders and the small flows
 * are the story (a lone predator's death matters as much as thirty grazing
 * herbivores). The price is stated rather than hidden: log widths are not
 * additive, so a bar no longer equals the sum of its ribbons, the diagram
 * says so in its corner, and the outer columns print their true counts —
 * where the scale stops being linear, the numbers go on the picture.
 *
 * <p>Filtered to one clade at a time when asked (the header's cycle button).
 * The cut can never sever a ribbon: a clade is inherited and never mutated,
 * so every flow — birth, death, continuation, drift — stays inside its clade.
 */
function sankeyDraw(ctx: CanvasRenderingContext2D, w: number, h: number, dpr: number): void {
  const d = linData;
  const padX = 6 * dpr, padT = 6 * dpr, padB = 16 * dpr;
  const gw = w - 2 * padX, gh = h - padT - padB;
  if (!d || d.stages.length < 2 || gw <= 0 || gh <= 0) {
    gathering(ctx, dpr, padX + 4 * dpr, padT + gh / 2);
    if (d && d.stages.length === 1) {
      ctx.fillText(`first stage taken — the next lands in ~${fmtSpan(d.stageSec)}`,
          padX + 4 * dpr, padT + gh / 2 + 14 * dpr);
    }
    return;
  }
  const K = d.stages.length;
  const nodeW = 9 * dpr, gap = 4 * dpr;
  // Every stage at a pitch a ribbon can be followed across; past the panel's
  // width the diagram scrolls (the track above it) rather than squeezing.
  const minPitch = (w / dpr < 600 ? LIN_PITCH_PHONE : LIN_PITCH) * dpr;
  const pitch = Math.max(minPitch, (gw - nodeW) / (K - 1));
  const contentW = (K - 1) * pitch + nodeW + 2 * padX;
  const scrolls = contentW > w + 1;
  syncLinScroll(scrolls ? contentW / dpr : 0, w / dpr);
  const scrollX = scrolls ? linScroll * dpr : 0;
  const real = (k: string) => k !== 'reseed' && k !== 'unknown' && k !== 'died';

  // The clade filter, applied to columns and flows alike.
  const specs = d.stages.map(st => st.species.filter(sp => linCladeMatch(sp.key)));
  const flowsAt: Array<FlowData['flows']> = [];
  for (let c = 0; c + 1 < K; c++) {
    flowsAt.push(d.flows.filter(f => f.stage === c
        && (real(f.from) ? linCladeMatch(f.from)
            : real(f.to) ? linCladeMatch(f.to) : false)));
  }

  // Width units: 1 head → 1 unit, 10 → 2, 100 → 3.
  const uOf = (n: number) => (n <= 0 ? 0 : 1 + Math.log10(n));

  // A node's height is the larger of what arrives and what leaves, in units —
  // log widths are subadditive, so the two sides genuinely differ and the
  // smaller side is centred on the bar rather than left ragged at the top.
  const unitsIn = specs.map(() => new Map<string, number>());
  const unitsOut = specs.map(() => new Map<string, number>());
  for (let c = 0; c + 1 < K; c++) {
    for (const f of flowsAt[c]) {
      if (real(f.from)) unitsOut[c].set(f.from, (unitsOut[c].get(f.from) ?? 0) + uOf(f.n));
      if (real(f.to)) unitsIn[c + 1].set(f.to, (unitsIn[c + 1].get(f.to) ?? 0) + uOf(f.n));
    }
  }
  const nodeUnits = (c: number, key: string, count: number) =>
    Math.max(unitsIn[c].get(key) ?? 0, unitsOut[c].get(key) ?? 0) || uOf(count);

  // One units-to-pixels scale for the whole diagram, set by the crowdest
  // column — and capped, so a filter that leaves one species does not balloon
  // it into a slab.
  let perU = Infinity;
  let anything = false;
  for (let c = 0; c < K; c++) {
    let tot = 0;
    for (const sp of specs[c]) tot += nodeUnits(c, sp.key, sp.count);
    if (tot > 0) {
      anything = true;
      perU = Math.min(perU, (gh - (specs[c].length - 1) * gap) / tot);
    }
  }
  if (!anything) {
    gathering(ctx, dpr, padX + 4 * dpr, padT + gh / 2);
    ctx.fillText(`no ${linClade}s in this window`,
        padX + 4 * dpr, padT + gh / 2 + 14 * dpr);
    return;
  }
  perU = Math.min(perU, 32 * dpr);

  const colX = (c: number) => padX + c * pitch - scrollX;
  // Columns on screen, with one either side so a ribbon entering from off the
  // edge is drawn; and the first and last WHOLE ones, which carry the names.
  const cMin = Math.max(0, Math.floor((scrollX - padX) / pitch) - 1);
  const cMax = Math.min(K - 1, Math.ceil((scrollX + w) / pitch) + 1);
  let cLeft = cMin, cRight = cMax;
  while (cLeft < cMax && colX(cLeft) < padX - 1) cLeft++;
  while (cRight > cLeft && colX(cRight) + nodeW > w - padX + 1) cRight--;
  const cols: Array<Map<string, SankeyNode>> = [];
  for (let c = 0; c < K; c++) {
    const m = new Map<string, SankeyNode>();
    // Columns centre on the panel's midline, first key lowest, same order
    // every column. A filtered view of two species otherwise sat capped at
    // the bottom of an empty chart, reading as a footer rather than a flow.
    let colH = -gap;
    for (const sp of specs[c]) colH += nodeUnits(c, sp.key, sp.count) * perU + gap;
    let y = padT + gh - Math.max(0, (gh - colH) / 2);
    for (const sp of specs[c]) {
      const uIn = unitsIn[c].get(sp.key) ?? 0, uOut = unitsOut[c].get(sp.key) ?? 0;
      const bh = nodeUnits(c, sp.key, sp.count) * perU;
      y -= bh;
      m.set(sp.key, { x: colX(c), yTop: y, h: bh, rgb: liftForDark(sp.rgb),
        inCur: y + (bh - uIn * perU) / 2, outCur: y + (bh - uOut * perU) / 2 });
      y -= gap;
    }
    cols.push(m);
  }

  // Ribbons first, bars over them. Flows sorted by their source's vertical
  // position then their destination's, so spans claim node edges top-down and
  // ribbons cross as little as this data allows.
  for (let c = cMin; c + 1 <= cMax; c++) {
    const from = cols[c], to = cols[c + 1];
    const order = (m: Map<string, SankeyNode>, k: string) =>
      m.has(k) ? m.get(k)!.yTop : Number.MAX_VALUE;
    const flows = flowsAt[c].slice().sort((a, b) =>
      (order(from, a.from) - order(from, b.from)) || (order(to, a.to) - order(to, b.to)));
    const x0 = colX(c) + nodeW, x1 = colX(c + 1), mx = (x0 + x1) / 2;
    for (const f of flows) {
      const hpx = uOf(f.n) * perU;
      const src = from.get(f.from), dst = to.get(f.to);
      if (src && dst) {
        const sy = src.outCur, dy = dst.inCur;
        src.outCur += hpx;
        dst.inCur += hpx;
        ctx.beginPath();
        ctx.moveTo(x0, sy);
        ctx.bezierCurveTo(mx, sy, mx, dy, x1, dy);
        ctx.lineTo(x1, dy + hpx);
        ctx.bezierCurveTo(mx, dy + hpx, mx, sy + hpx, x0, sy + hpx);
        ctx.closePath();
        // A ribbon keeps its SOURCE tint, so a lineage crossing into another
        // label is seen leaving. Bred is drawn solid and held faint: a column
        // that is mostly faint is the same bodies still standing there, and one
        // that is mostly solid is a line making generations.
        ctx.fillStyle = src.rgb;
        ctx.globalAlpha = f.kind === 'bred' ? 0.7 : 0.28;
        ctx.fill();
        ctx.globalAlpha = 1;
      } else if (dst) {
        // From nothing, opening out of the gap: a reseed in the warden's
        // yellow, a line the registry lost in grey. Never the species' own
        // tint -- that would read as the species breeding.
        const dy = dst.inCur;
        dst.inCur += hpx;
        const xb = x0 + (x1 - x0) * 0.45, ym = dy + hpx / 2;
        ctx.beginPath();
        ctx.moveTo(xb, ym);
        ctx.bezierCurveTo(mx, dy, mx, dy, x1, dy);
        ctx.lineTo(x1, dy + hpx);
        ctx.bezierCurveTo(mx, dy + hpx, mx, dy + hpx, xb, ym);
        ctx.closePath();
        ctx.fillStyle = f.kind === 'reseed' ? WARDEN : UNKNOWN_TINT;
        ctx.globalAlpha = f.kind === 'reseed' ? 0.75 : 0.4;
        ctx.fill();
        ctx.globalAlpha = 1;
      } else if (src) {
        // Ended: the line closes to nothing before the next stage.
        const sy = src.outCur;
        src.outCur += hpx;
        const xd = x0 + (x1 - x0) * 0.55, ym = sy + hpx / 2;
        ctx.beginPath();
        ctx.moveTo(x0, sy);
        ctx.bezierCurveTo(mx, sy, mx, sy, xd, ym);
        ctx.bezierCurveTo(mx, sy + hpx, mx, sy + hpx, x0, sy + hpx);
        ctx.closePath();
        ctx.fillStyle = src.rgb;
        ctx.globalAlpha = 0.35;
        ctx.fill();
        ctx.globalAlpha = 1;
      }
    }
  }

  linHits = [];
  for (let c = cMin; c <= cMax; c++) {
    for (const [key, node] of cols[c]) {
      ctx.fillStyle = node.rgb;
      ctx.fillRect(node.x, node.yTop, nodeW, Math.max(node.h, 1));
      const sp = specs[c].find(x => x.key === key)!;
      linHits.push({ x: node.x, y: node.yTop, w: nodeW, h: Math.max(node.h, 4 * dpr),
        key, count: sp.count, tick: d.stages[c].tick });
    }
  }

  // Names and TRUE counts beside both outer columns. On a log scale the width
  // cannot be read as a number, so the numbers are written where the eye
  // lands; middle columns are read by tint and by following a ribbon home.
  ctx.font = `${9.5 * dpr}px ui-monospace, monospace`;
  // Beside the first and last columns ON SCREEN, so a scrolled diagram still
  // names what it shows.
  for (const [c, align] of [[cLeft, 'left'], [cRight, 'right']] as Array<[number, CanvasTextAlign]>) {
    ctx.textAlign = align;
    for (const sp of specs[c]) {
      const node = cols[c].get(sp.key)!;
      if (node.h < 9 * dpr) continue; // thin bars lean on the legend
      // On a plate: at full pitch a name runs across the ribbons beside it, and
      // bare text over a bright ribbon could not be read.
      const label = `${speciesLabel(sp.key)} ${sp.count}`;
      const tx = align === 'left' ? node.x + nodeW + 4 * dpr : node.x - 4 * dpr;
      const ty = node.yTop + node.h / 2 + 3.5 * dpr;
      const tw = ctx.measureText(label).width;
      ctx.fillStyle = 'rgba(20, 22, 31, 0.82)'; // --lamp-h, the readout plate
      ctx.fillRect(align === 'left' ? tx - 3 * dpr : tx - tw - 3 * dpr, ty - 10 * dpr, tw + 6 * dpr, 13 * dpr);
      ctx.fillStyle = '#c8cdd5';
      ctx.fillText(label, tx, ty);
    }
  }
  ctx.textAlign = 'left';

  // When each stage was, as time before now, under every column that has the
  // room; a cramped chart names only its ends. The scale note sits in the
  // same band: a non-linear width must say it is one.
  ctx.fillStyle = '#9aa2b1';
  ctx.font = `${8.5 * dpr}px ui-monospace, monospace`;
  const lastTick = d.stages[K - 1].tick;
  ctx.fillText('width ≈ 1+log₁₀ heads', padX, padT + gh + 11 * dpr);
  ctx.textAlign = 'center';
  const roomy = pitch >= 46 * dpr;
  const noteEnd = padX + ctx.measureText('width ≈ 1+log₁₀ heads').width + 6 * dpr;
  for (let c = cMin; c <= cMax; c++) {
    if (!roomy && c !== 0 && c !== K - 1) continue;
    if (c === 0 && !scrolls) continue; // its slot holds the scale note
    const ago = (lastTick - d.stages[c].tick) / d.tps;
    const label = c === K - 1 ? 'now' : `-${fmtAgo(ago)}`;
    const x = colX(c) + nodeW / 2;
    if (x - ctx.measureText(label).width / 2 < noteEnd) continue; // under the note
    ctx.fillText(label, x, padT + gh + 11 * dpr);
  }
  ctx.textAlign = 'left';
  linSummary = `${fmtSpan((lastTick - d.stages[0].tick) / d.tps)} · ${K} stages`
      + (scrolls ? ' · drag or scroll for earlier' : '');
  linLastTick = lastTick;
  linTps = d.tps;
  if (!linPointing) popWin.textContent = linSummary;
}

function gathering(ctx: CanvasRenderingContext2D, dpr: number, x: number, y: number): void {
  ctx.fillStyle = '#9aa2b1';
  ctx.font = `${11 * dpr}px ui-monospace, monospace`;
  ctx.fillText('gathering…', x, y);
}

/** The legend. On the role lens it doubles as the control — tap a series to
 *  drop it from the chart and give the rest the axis. On the lineage lens it
 *  is a readout only, of the LATEST stage: the Sankey accounts for every head
 *  on both sides of every boundary, and hiding a species would break exactly
 *  the conservation the diagram is. */
function popKeyDraw(): void {
  popKey.innerHTML = '';
  if (popMode === 'roles') {
    const d = popData;
    for (const s of POP_SERIES) {
      const vals = d ? d[s.key] : [];
      const last = vals.length ? vals[vals.length - 1] : 0;
      const off = popHidden.has(s.key);
      const el = document.createElement('span');
      el.className = off ? 'off' : '';
      el.title = off ? `show ${s.label}` : `hide ${s.label}`;
      el.appendChild(keySwatch(off ? 'transparent' : s.rgb, s.rgb));
      el.appendChild(document.createTextNode(`${s.label} ${last}`));
      el.onclick = () => {
        // Never hide the last one standing: an empty chart is not a view.
        if (!off && popHidden.size >= POP_SERIES.length - 1) return;
        if (off) popHidden.delete(s.key); else popHidden.add(s.key);
        popDraw();
      };
      popKey.appendChild(el);
    }
    return;
  }
  const st = linData && linData.stages.length
      ? linData.stages[linData.stages.length - 1] : null;
  // What the ribbons mean, first: the reading is in the kinds.
  for (const [fill, label, title] of [
    ['#c8cdd580', 'bred', 'descended from a head of the previous stage'],
    ['#c8cdd530', 'held', 'the same body, still alive'],
    [WARDEN, 'reseeded', 'a founder the warden landed since the previous stage'],
  ] as const) {
    const el = document.createElement('span');
    el.className = 'static'; // a caption, not a toggle
    el.title = title;
    el.appendChild(keySwatch(fill, '#c8cdd560'));
    el.appendChild(document.createTextNode(label));
    popKey.appendChild(el);
  }
  // Then each species of the latest stage with its count, and over the whole
  // window how many of its heads were bred against how many the warden put
  // back -- the diagram's question, answered in numbers where the widths are
  // not additive enough to be read as any.
  const bred = new Map<string, number>(), reseed = new Map<string, number>();
  for (const f of linData ? linData.flows : []) {
    if (f.kind === 'bred') bred.set(f.to, (bred.get(f.to) ?? 0) + f.n);
    if (f.kind === 'reseed') reseed.set(f.to, (reseed.get(f.to) ?? 0) + f.n);
  }
  for (const sp of (st ? st.species : []).filter(x => linCladeMatch(x.key))) {
    const el = document.createElement('span');
    el.className = 'static'; // a caption, not a toggle
    el.title = `${sp.key}: over the window, ${bred.get(sp.key) ?? 0} heads bred, `
      + `${reseed.get(sp.key) ?? 0} reseeded by the warden`;
    const rgb = liftForDark(sp.rgb);
    el.appendChild(keySwatch(rgb, rgb));
    el.appendChild(document.createTextNode(
      `${speciesLabel(sp.key)} ${sp.count} · bred ${bred.get(sp.key) ?? 0} · reseeded ${reseed.get(sp.key) ?? 0}`));
    popKey.appendChild(el);
  }
}

/** A legend swatch. The faint outer ring is what keeps a dark one a swatch:
 *  without it a near-black species reads as a gap on this near-black panel. */
function keySwatch(fill: string, ring: string): HTMLElement {
  const dot = document.createElement('i');
  dot.style.setProperty('--fill', fill);
  dot.style.setProperty('--ring', ring);
  return dot;
}

/** Rounds an axis top up to something a person would have chosen.
 *
 *  The ladder is deliberately fine. A coarse one (1, 2, 5, 10) sends a peak of
 *  54 all the way to 100, which throws away half the chart's height — and the
 *  half it throws away is exactly where predators and scavengers live, since
 *  they share the axis with a herbivore count ten times theirs. */
function niceCeil(v: number): number {
  const mag = Math.pow(10, Math.floor(Math.log10(Math.max(1, v))));
  for (const m of [1, 1.2, 1.5, 2, 2.5, 3, 4, 5, 6, 8, 10]) {
    if (v <= m * mag) return m * mag;
  }
  return 10 * mag;
}

/** An axis offset: like fmtSpan, but past the hour as hours AND minutes, so
 *  stages five minutes apart never print the same label twice ("1.8h" did). */
function fmtAgo(sec: number): string {
  if (sec < 3600) return fmtSpan(Math.min(sec, 3599));
  const m = Math.round(sec / 60);
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}`;
}

export function fmtSpan(sec: number): string {
  if (sec < 90) return `${Math.round(sec)}s`;
  if (sec < 5400) return `${Math.round(sec / 60)}m`;
  return `${(sec / 3600).toFixed(1)}h`;
}

popBtn.onclick = () => { popOn = !popOn; popApply(); };
// The lens tabs. Switching draws whatever that lens already holds and forces a
// fresh fetch, so the panel never shows one lens's numbers under the other's
// heading while the request is in flight.
const popModeRoles = document.getElementById('popModeRoles') as HTMLButtonElement;
const popModeLin = document.getElementById('popModeLin') as HTMLButtonElement;
/** The size the roles chart had before the lineage lens took the screen. */
let rolesSize: 'half' | 'full' = 'half';
function popSetMode(m: 'roles' | 'lineages'): void {
  if (popMode === m) return;
  popMode = m;
  popModeRoles.classList.toggle('on', m === 'roles');
  popModeLin.classList.toggle('on', m === 'lineages');
  popCladeBtn.hidden = m !== 'lineages';
  // The lineage diagram is read at full screen: a column per stage needs the
  // width, and a ribbon one head wide needs the height. Going back to roles
  // gives the chart the size it had.
  if (m === 'lineages') {
    rolesSize = popEl.classList.contains('full') ? 'full' : 'half';
    openPanel(popEl, 'full');
  } else {
    openPanel(popEl, rolesSize);
  }
  linFollow = true; // a lens opened fresh opens on now
  popDraw();
  popPoll(true);
}
popModeRoles.onclick = () => popSetMode('roles');
popModeLin.onclick = () => popSetMode('lineages');
const popCladeBtn = document.getElementById('popClade') as HTMLButtonElement;
popCladeBtn.onclick = () => {
  linClade = LIN_CLADES[(LIN_CLADES.indexOf(linClade) + 1) % LIN_CLADES.length];
  popCladeBtn.textContent = linClade;
  popDraw();
};
// The chart is the one panel that has to repaint when it changes size: its
// canvas is sized in device pixels, so reflowing alone would stretch the last
// frame rather than draw a new one.
wirePanelChrome(popEl, closePop, popDraw);
rightColumn.push({ el: popEl, close: closePop });
window.addEventListener('keydown', ev => {
  if ((ev.key !== 'p' && ev.key !== 'P') || !isShortcut(ev)) return;
  popOn = !popOn;
  popApply();
});
// Redraw on resize so the chart tracks a rotated phone or a dragged window.
window.addEventListener('resize', () => { if (popOn) popDraw(); });
// The poll beat. Only runs while the panel is open — a closed graph costs the
// server nothing.
setInterval(() => { if (popOn && !document.hidden) popPoll(); }, 1000);

popApply();
