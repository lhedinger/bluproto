// The /compare page: two genomes side by side, read like a code diff, with
// one number on top. The comparison itself is the server's (GenomeEditor
// .compare), so the score here is the same one the suite pins; this page only
// loads two lines and draws what comes back.
import './docs.css';
import './editor.css';
import { syncNavPad } from './mechdoc';

type Compared = {
  genes: { key: string; unit: string; a: number; b: number; distance: number }[];
  cladeA: string; cladeB: string; sameClade: boolean; markerSimilarity: number;
  mind: { kind: 'equal' | 'change' | 'insert' | 'delete'; a: string | null; b: string | null }[];
  mindLengthA: number; mindLengthB: number; mindCommon: number;
  bodySimilarity: number; mindSimilarity: number; similarity: number;
};

const root = document.getElementById('root')!;
const hash = new URLSearchParams(location.hash.slice(1));
const lines: Record<'a' | 'b', string | null> = { a: hash.get('a'), b: hash.get('b') };
const labels: Record<'a' | 'b', string> = { a: hash.get('la') ?? '', b: hash.get('lb') ?? '' };
let result: Compared | null = null;

const esc = (s: string): string => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
const pct = (v: number): string => (v * 100).toFixed(1) + '%';
const num = (v: number): string => Number.isInteger(v) ? String(v) : v.toPrecision(4);

function slot(side: 'a' | 'b'): string {
  const line = lines[side];
  return `<div class="cmp-slot" id="slot-${side}"><h3>${side.toUpperCase()}${labels[side] ? ' · ' + esc(labels[side]) : ''}</h3>
    <div class="ed-load"><label>creature id <input type="text" id="id-${side}" placeholder="from the inspector"></label>
      <button class="ed-btn" data-fetch="${side}">fetch</button>
      <input type="file" id="file-${side}" accept=".genome,text/plain"></div>
    <textarea id="paste-${side}" placeholder="or paste an encoded genome line">${line ? esc(line) : ''}</textarea>
    <div class="ed-row"><button class="ed-btn" data-load="${side}">use pasted line</button>
      <span class="ed-msg" id="msg-${side}">${line ? 'loaded' : 'empty'}</span></div>
  </div>`;
}

function slotsSection(): string {
  return `<div class="ed-section" id="slots"><h2>load</h2>
    <div class="cmp-slots">${slot('a')}${slot('b')}</div>
    <div class="ed-row"><button class="ed-btn" id="swap">⇄ swap sides</button><span class="ed-msg" id="msg"></span></div>
  </div>`;
}

function scoreSection(): string {
  if (!result) return `<div class="ed-section" id="score"><h2>score</h2><p class="note">load both sides to compare.</p></div>`;
  const r = result;
  return `<div class="ed-section" id="score"><h2>score</h2>
    <div class="cmp-score"><span class="big">${pct(r.similarity)}</span>
      <span class="part">body <b>${pct(r.bodySimilarity)}</b></span>
      <span class="part">mind <b>${pct(r.mindSimilarity)}</b> <span style="opacity:.7">(${r.mindCommon} of ${Math.max(r.mindLengthA, r.mindLengthB)} instructions shared)</span></span>
      <span class="part">markers <b>${pct(r.markerSimilarity)}</b></span>
      <span class="ed-badge">${r.sameClade ? 'both ' + esc(r.cladeA) : esc(r.cladeA) + ' vs ' + esc(r.cladeB)}</span></div>
  </div>`;
}

function bodySection(): string {
  if (!result) return '';
  const rows = result.genes.map(g => `<tr class="${g.distance > 0 ? 'diff' : ''}"><td>${esc(g.key)}</td>
    <td class="num">${num(g.a)}</td><td class="num">${num(g.b)}</td><td class="unit">${esc(g.unit)}</td>
    <td><span class="barwrap"><span class="bar" style="width:${(g.distance * 100).toFixed(0)}%"></span></span> <span style="opacity:.6;font-size:.78rem">${pct(g.distance)}</span></td></tr>`).join('');
  const differing = result.genes.filter(g => g.distance > 0).length;
  return `<div class="ed-section" id="body"><h2>body</h2>
    <p class="note">${differing} of ${result.genes.length} genes differ. The bar is each gene's distance across its own range.</p>
    <table class="facts cmp-genes"><thead><tr><th>gene</th><th>A</th><th>B</th><th>unit</th><th>distance</th></tr></thead><tbody>${rows}</tbody></table></div>`;
}

function mindSection(): string {
  if (!result) return '';
  let ia = 0, ib = 0;
  const rows = result.mind.map(r => {
    const na = r.a !== null ? String(ia++) : '', nb = r.b !== null ? String(ib++) : '';
    return `<tr class="${r.kind}"><td class="n">${na}</td><td class="side a">${r.a !== null ? esc(r.a) : ''}</td><td class="side b">${r.b !== null ? esc(r.b) : ''}</td><td class="n">${nb}</td></tr>`;
  }).join('');
  const counts = { change: 0, insert: 0, delete: 0 };
  for (const r of result.mind) if (r.kind !== 'equal') counts[r.kind]++;
  return `<div class="ed-section" id="mind"><h2>mind</h2>
    <p class="note">${result.mindLengthA} instructions on A, ${result.mindLengthB} on B: ${counts.change} changed, ${counts.insert} only on B, ${counts.delete} only on A.</p>
    <div class="cmp-legend"><span><i style="background:rgba(255,170,0,.4)"></i>changed</span><span><i style="background:rgba(230,70,70,.4)"></i>only on A</span><span><i style="background:rgba(60,180,90,.4)"></i>only on B</span></div>
    <div class="scroll"><table class="cmp-diff"><thead><tr><th></th><th>A</th><th>B</th><th></th></tr></thead><tbody>${rows}</tbody></table></div></div>`;
}

async function compare(): Promise<void> {
  const msg = document.getElementById('msg')!;
  if (!lines.a || !lines.b) { result = null; renderResult(); return; }
  try {
    const r = await fetch('/api/genome/compare', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ a: lines.a, b: lines.b }) });
    const d = await r.json();
    if (!r.ok) throw new Error(d?.error ?? String(r.status));
    result = d;
    msg.textContent = ''; msg.className = 'ed-msg';
  } catch (e) {
    result = null;
    msg.textContent = 'refused: ' + (e as Error).message; msg.className = 'ed-msg err';
  }
  renderResult();
}

function setLine(side: 'a' | 'b', line: string, label = ''): void {
  lines[side] = line.trim() || null;
  labels[side] = label;
  const m = document.getElementById(`msg-${side}`)!;
  m.textContent = lines[side] ? 'loaded' : 'empty'; m.className = 'ed-msg ok';
  (document.getElementById(`paste-${side}`) as HTMLTextAreaElement).value = lines[side] ?? '';
  const h = document.querySelector(`#slot-${side} h3`); if (h) h.textContent = side.toUpperCase() + (label ? ' · ' + label : '');
  void compare();
}

function wireSlots(): void {
  for (const side of ['a', 'b'] as const) {
    document.querySelector<HTMLButtonElement>(`button[data-load="${side}"]`)!.addEventListener('click', () =>
      setLine(side, (document.getElementById(`paste-${side}`) as HTMLTextAreaElement).value));
    (document.getElementById(`file-${side}`) as HTMLInputElement).addEventListener('change', async e => {
      const f = (e.target as HTMLInputElement).files?.[0]; if (f) setLine(side, await f.text(), f.name);
    });
    document.querySelector<HTMLButtonElement>(`button[data-fetch="${side}"]`)!.addEventListener('click', async () => {
      const id = (document.getElementById(`id-${side}`) as HTMLInputElement).value.trim();
      const m = document.getElementById(`msg-${side}`)!;
      if (!id) { m.textContent = 'enter a creature id'; m.className = 'ed-msg err'; return; }
      try {
        const r = await fetch(`/api/world/genome/${encodeURIComponent(id)}`);
        const d = r.ok ? await r.json() : null;
        if (!d?.genome) { m.textContent = 'no such creature, or no brain to read'; m.className = 'ed-msg err'; return; }
        setLine(side, d.genome, 'creature ' + id);
      } catch (e) { m.textContent = (e as Error).message; m.className = 'ed-msg err'; }
    });
  }
  document.getElementById('swap')!.addEventListener('click', () => {
    const la = lines.a, lb = lines.b, na = labels.a, nb = labels.b;
    setLine('a', lb ?? '', nb); setLine('b', la ?? '', na);
  });
}

function renderResult(): void {
  document.getElementById('result')!.innerHTML = scoreSection() + bodySection() + mindSection();
  syncNavPad();
}

root.innerHTML = slotsSection() + '<div id="result"></div>';
wireSlots();
void compare();
