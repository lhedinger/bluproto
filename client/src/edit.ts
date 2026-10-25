// The /edit page: a genome taken apart for a form and put back together. The
// form is built from /api/genome/schema and nothing else, so a gene or a
// channel added to the simulation appears here without a change. Values are
// assembled by the server (GenomeEditor), which clamps them the way a birth
// does and writes the line in the simulation's own encoding; this page never
// serialises a genome itself.
import './docs.css';
import './editor.css';
import { syncNavPad } from './mechdoc';

type Gene = { key: string; unit: string; lo: number; hi: number | null; drift: string; integral: boolean };
type Op = { op: number; name: string; operands: string[] };
type Schema = {
  genes: Gene[]; clades: { code: number; name: string }[]; ops: Op[];
  registers: number; consts: number[]; sensors: string[]; actuators: string[]; maxLen: number;
};
type Described = {
  genes: Record<string, number>; clade: number; cladeName: string; sexual: boolean;
  code: number[][]; disasm: string[]; network: boolean; genome: string; clamped?: string[];
};

const root = document.getElementById('root')!;
const hash = new URLSearchParams(location.hash.slice(1));
const token = hash.get('t') ?? '';

let schema: Schema;
let base: string | null = null;           // the line the draft started from
let draft: { genes: Record<string, number>; clade: number; code: number[][] } | null = null;
let raw = false;
let last: Described | null = null;        // the server's view of the current draft
let assembleTimer: number | null = null;
let assembling = 0;

const mod = (v: number, n: number): number => ((v % n) + n) % n;
const esc = (s: string): string => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));

async function postJson(url: string, body: unknown): Promise<any> {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const d = await r.json();
  if (!r.ok) throw new Error(d?.error ?? `${r.status}`);
  return d;
}

// ---- load ------------------------------------------------------------------

async function load(line: string): Promise<void> {
  line = line.trim();
  if (!line) return;
  try {
    const d: Described = await postJson('/api/genome/describe', { genome: line });
    base = d.genome;
    draft = { genes: { ...d.genes }, clade: d.clade, code: d.code.map(r => [...r]) };
    last = d;
    renderAll();
    setMsg('loaded ' + d.cladeName + (d.network ? ' (network mind: carried through, not editable here)' : '') + ', ' + d.code.length + ' instructions', 'ok');
    location.hash = '';
  } catch (e) {
    setMsg('not a genome: ' + (e as Error).message, 'err');
  }
}

function loadSection(): string {
  return `<div class="ed-section" id="load"><h2>load</h2>
    <div class="ed-load">
      <input type="file" id="file" accept=".genome,text/plain">
      <span class="drop" id="drop">or drop a .genome file here</span>
      <button class="ed-btn" id="blank">start blank</button>
    </div>
    <textarea id="paste" placeholder="or paste an encoded genome line (g6;size=…;brain=…) and press load"></textarea>
    <div class="ed-row"><button class="ed-btn" id="pasteBtn">load pasted line</button><span class="ed-msg" id="msg"></span></div>
  </div>`;
}

function wireLoad(): void {
  const file = document.getElementById('file') as HTMLInputElement;
  file.addEventListener('change', async () => { const f = file.files?.[0]; if (f) await load(await f.text()); });
  const drop = document.getElementById('drop')!;
  for (const ev of ['dragenter', 'dragover']) drop.addEventListener(ev, e => { e.preventDefault(); });
  drop.addEventListener('drop', async e => {
    e.preventDefault();
    const f = e.dataTransfer?.files?.[0];
    if (f) await load(await f.text());
  });
  document.getElementById('pasteBtn')!.addEventListener('click', () =>
    load((document.getElementById('paste') as HTMLTextAreaElement).value));
  document.getElementById('blank')!.addEventListener('click', async () => {
    try {
      const d: Described = await postJson('/api/genome/assemble', { base: null, genes: {}, clade: 0, code: [[0, 0, 0, 0]], raw: false });
      base = null;
      draft = { genes: { ...d.genes }, clade: d.clade, code: d.code.map(r => [...r]) };
      last = d;
      renderAll();
      setMsg('a blank genome: the schema defaults and a one-instruction mind', 'ok');
    } catch (e) { setMsg((e as Error).message, 'err'); }
  });
}

function setMsg(text: string, kind: 'ok' | 'err'): void {
  const m = document.getElementById('msg');
  if (m) { m.textContent = text; m.className = 'ed-msg ' + kind; }
}

// ---- body ------------------------------------------------------------------

function stepFor(g: Gene): string {
  if (g.integral) return '1';
  const span = g.hi === null ? Math.max(1, Math.abs(g.lo)) : g.hi - g.lo;
  return span > 100 ? '1' : span > 10 ? '0.1' : span > 1 ? '0.01' : '0.001';
}

function bodySection(): string {
  if (!draft) return `<div class="ed-section" id="body"><h2>body</h2><p class="note">load a genome first.</p></div>`;
  const clamped = new Set(last?.clamped ?? []);
  const rows = schema.genes.map(g => {
    const v = draft!.genes[g.key] ?? 0;
    const range = g.hi === null ? '' :
      `<input type="range" aria-label="${esc(g.key)} slider" data-gene="${esc(g.key)}" min="${g.lo}" max="${g.hi}" step="${stepFor(g)}" value="${v}">`;
    const bounds = g.hi === null ? `≥ ${g.lo}` : `${g.lo} – ${g.hi}`;
    return `<tr data-row="${esc(g.key)}" class="${clamped.has(g.key) ? 'clamped' : ''}"><td>${esc(g.key)}</td>
      <td><input type="number" aria-label="${esc(g.key)}" data-gene="${esc(g.key)}" step="${stepFor(g)}" value="${v}"></td>
      <td>${range}</td><td class="unit">${esc(g.unit)}</td>
      <td class="bounds">${bounds} · ${g.drift.toLowerCase()}</td></tr>`;
  }).join('');
  const clades = schema.clades.map(c => `<option value="${c.code}" ${c.code === draft!.clade ? 'selected' : ''}>${esc(c.name)}</option>`).join('');
  return `<div class="ed-section" id="body"><h2>body</h2>
    <div class="ed-row"><label>clade <select id="clade">${clades}</select></label>
      <span class="ed-badge" id="sexual">${last?.sexual ? 'courts' : 'buds'}</span>
      <label class="ed-raw"><input type="checkbox" id="raw" ${raw ? 'checked' : ''}> raw values (no clamp: an experiment, and it says so)</label></div>
    <div class="scroll"><table class="facts genes"><thead><tr><th>gene</th><th>value</th><th></th><th>unit</th><th>bounds · drift</th></tr></thead><tbody>${rows}</tbody></table></div>
  </div>`;
}

function wireBody(): void {
  if (!draft) return;
  for (const el of Array.from(document.querySelectorAll<HTMLInputElement>('table.genes input[data-gene]'))) {
    el.addEventListener('input', () => {
      const key = el.dataset.gene!;
      const v = Number(el.value);
      if (!Number.isFinite(v)) return;
      draft!.genes[key] = v;
      // keep the number and the slider of the same gene in step
      for (const twin of Array.from(document.querySelectorAll<HTMLInputElement>(`table.genes input[data-gene="${key}"]`))) {
        if (twin !== el) twin.value = String(v);
      }
      scheduleAssemble();
    });
  }
  (document.getElementById('clade') as HTMLSelectElement).addEventListener('change', e => {
    draft!.clade = Number((e.target as HTMLSelectElement).value);
    scheduleAssemble();
  });
  (document.getElementById('raw') as HTMLInputElement).addEventListener('change', e => {
    raw = (e.target as HTMLInputElement).checked;
    scheduleAssemble();
  });
}

// ---- mind ------------------------------------------------------------------

function operandCell(kind: string, r: number, c: number, v: number): string {
  const sel = (opts: string[], n: number) => {
    const cur = mod(v, n);
    return `<select data-r="${r}" data-c="${c}">${opts.map((o, i) =>
      `<option value="${i}" ${i === cur ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select>`;
  };
  switch (kind) {
    case 'reg': return sel(Array.from({ length: schema.registers }, (_, i) => 'R' + i), schema.registers);
    case 'const': return sel(schema.consts.map(String), schema.consts.length);
    case 'sensor': return sel(schema.sensors, schema.sensors.length);
    case 'act': return sel(schema.actuators, schema.actuators.length);
    default: return '<span style="opacity:.3">·</span>';
  }
}

function mindSection(): string {
  if (!draft) return `<div class="ed-section" id="mind"><h2>mind</h2><p class="note">load a genome first.</p></div>`;
  const dis = last?.disasm ?? [];
  const rows = draft.code.map((row, r) => {
    const op = schema.ops[mod(row[0], schema.ops.length)];
    const ops = schema.ops.map(o => `<option value="${o.op}" ${o.op === op.op ? 'selected' : ''}>${o.name}</option>`).join('');
    const cells = op.operands.map((k, c) => `<td>${operandCell(k, r, c + 1, row[c + 1])}</td>`).join('');
    const text = (dis[r] ?? '').replace(/^[> ]*\d+\s*/, '');
    return `<tr><td class="n">${r}</td><td><select data-r="${r}" data-c="0">${ops}</select></td>${cells}
      <td class="dis">${esc(text)}</td>
      <td class="ctl"><button data-act="up" data-r="${r}" title="move up">↑</button><button data-act="down" data-r="${r}" title="move down">↓</button><button data-act="ins" data-r="${r}" title="insert after">+</button><button data-act="del" data-r="${r}" title="delete">✕</button></td></tr>`;
  }).join('');
  return `<div class="ed-section" id="mind"><h2>mind</h2>
    <p class="note">One instruction a row: the opcode, then what each operand names — a register, a sensor
    to read, an actuator to write, or an immediate from the constant pool. The text column is the
    simulation's own disassembly of the row, read back from the line. ${draft.code.length} of ${schema.maxLen} instructions.</p>
    <div class="scroll"><table class="facts prog"><thead><tr><th>#</th><th>op</th><th>x</th><th>y</th><th>z</th><th>reads as</th><th></th></tr></thead><tbody>${rows}</tbody></table></div>
    <div class="ed-row"><button class="ed-btn" id="addRow">+ instruction</button></div>
  </div>`;
}

function wireMind(): void {
  if (!draft) return;
  for (const el of Array.from(document.querySelectorAll<HTMLSelectElement>('table.prog select'))) {
    el.addEventListener('change', () => {
      const r = Number(el.dataset.r), c = Number(el.dataset.c);
      draft!.code[r][c] = Number(el.value);
      if (c === 0) renderMind(); // a new opcode has new operand kinds
      scheduleAssemble();
    });
  }
  for (const el of Array.from(document.querySelectorAll<HTMLButtonElement>('table.prog button[data-act]'))) {
    el.addEventListener('click', () => {
      const r = Number(el.dataset.r); const code = draft!.code;
      switch (el.dataset.act) {
        case 'up': if (r > 0) { [code[r - 1], code[r]] = [code[r], code[r - 1]]; } break;
        case 'down': if (r < code.length - 1) { [code[r + 1], code[r]] = [code[r], code[r + 1]]; } break;
        case 'ins': if (code.length < schema.maxLen) code.splice(r + 1, 0, [0, 0, 0, 0]); break;
        case 'del': if (code.length > 1) code.splice(r, 1); break;
      }
      renderMind(); scheduleAssemble();
    });
  }
  document.getElementById('addRow')?.addEventListener('click', () => {
    if (draft!.code.length < schema.maxLen) { draft!.code.push([0, 0, 0, 0]); renderMind(); scheduleAssemble(); }
  });
}

// ---- the line --------------------------------------------------------------

function outSection(): string {
  const line = last?.genome ?? '';
  const place = token
    ? `<button class="ed-btn" id="placeBtn">place at the surface's centre</button>`
    : `<span class="ed-msg ok">placing needs the world's command token (open this page from the world with one).</span>`;
  return `<div class="ed-section ed-out" id="out"><h2>line</h2>
    <textarea id="line" readonly>${esc(line)}</textarea>
    <div class="ed-row"><button class="ed-btn" id="saveBtn" ${line ? '' : 'disabled'}>⤓ save .genome</button>
      <button class="ed-btn" id="handBtn" ${line ? '' : 'disabled'}>hand to the world (tap to place)</button>${line ? place : ''}
      <span class="ed-msg" id="outMsg"></span></div>
  </div>`;
}

function wireOut(): void {
  document.getElementById('saveBtn')?.addEventListener('click', () => {
    if (!last) return;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([last.genome], { type: 'text/plain' }));
    a.download = `edited-${last.cladeName}.genome`;
    a.click();
    URL.revokeObjectURL(a.href);
  });
  document.getElementById('handBtn')?.addEventListener('click', () => {
    if (!last) return;
    // The world page remembers one genome under this key and arms "tap: genome"
    // with it; see main.ts. Same key, so the editor's output is what a tap drops.
    try { localStorage.setItem('injectGenome', last.genome); } catch { /* private mode */ }
    const m = document.getElementById('outMsg')!;
    m.textContent = 'handed over: in the world, choose "tap: genome" and tap open ground';
    m.className = 'ed-msg ok';
    window.open('/' + (token ? '#t=' + encodeURIComponent(token) : ''), 'bluproto-world');
  });
  document.getElementById('placeBtn')?.addEventListener('click', async () => {
    if (!last) return;
    const m = document.getElementById('outMsg')!;
    try {
      const r = await fetch('/api/world/genome', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Command-Token': token },
        body: JSON.stringify({ genome: last.genome }),
      });
      const d = await r.json();
      m.textContent = r.ok ? `placed: lands at tick ${d.tick}` : 'refused: ' + (d.error ?? r.status);
      m.className = 'ed-msg ' + (r.ok ? 'ok' : 'err');
    } catch (e) { m.textContent = (e as Error).message; m.className = 'ed-msg err'; }
  });
}

// ---- assemble --------------------------------------------------------------

function scheduleAssemble(): void {
  if (assembleTimer !== null) window.clearTimeout(assembleTimer);
  assembleTimer = window.setTimeout(() => { assembleTimer = null; void assemble(); }, 150);
}

async function assemble(): Promise<void> {
  if (!draft) return;
  const my = ++assembling;
  try {
    const d: Described = await postJson('/api/genome/assemble',
      { base, genes: draft.genes, clade: draft.clade, code: draft.code, raw });
    if (my !== assembling) return; // a later edit is in flight
    last = d;
    if (!raw) { // the server may have clamped: show what it kept
      for (const [k, v] of Object.entries(d.genes)) {
        if (draft.genes[k] !== v) {
          draft.genes[k] = v;
          for (const el of Array.from(document.querySelectorAll<HTMLInputElement>(`table.genes input[data-gene="${k}"]`))) el.value = String(v);
        }
      }
    }
    const clamped = new Set(d.clamped ?? []);
    for (const tr of Array.from(document.querySelectorAll<HTMLTableRowElement>('table.genes tr[data-row]'))) {
      tr.classList.toggle('clamped', clamped.has(tr.dataset.row!));
    }
    const sx = document.getElementById('sexual'); if (sx) sx.textContent = d.sexual ? 'courts' : 'buds';
    for (const [r, td] of Array.from(document.querySelectorAll<HTMLTableCellElement>('table.prog td.dis')).entries()) {
      td.textContent = (d.disasm[r] ?? '').replace(/^[> ]*\d+\s*/, '');
    }
    renderOut();
    setMsg('', 'ok');
  } catch (e) {
    setMsg('refused: ' + (e as Error).message, 'err');
  }
}

// ---- render ----------------------------------------------------------------

function renderMind(): void {
  const el = document.getElementById('mind');
  if (!el) return;
  el.outerHTML = mindSection();
  wireMind();
}

function renderOut(): void {
  const el = document.getElementById('out');
  if (!el) return;
  el.outerHTML = outSection();
  wireOut();
}

function renderAll(): void {
  root.innerHTML = loadSection() + bodySection() + mindSection() + outSection();
  wireLoad(); wireBody(); wireMind(); wireOut();
  syncNavPad();
}

void (async () => {
  schema = await (await fetch('/api/genome/schema')).json();
  renderAll();
  const g = hash.get('g');
  if (g) await load(g);
})();
