// The /help/design page: the Bulkhead reference and its playground. Plain DOM,
// no dependencies, like the rest of the client.
/* eslint-disable */
// @ts-nocheck -- page glue over the system's markup; the system itself is CSS.

// ---- swatches
const SW = [
  ['--sh', 'shadow', 'bevel dark edge'], ['--s0', 'steel', 'panel surface'], ['--s1', 'steel lit', 'header, button'], ['--s2', 'steel high', 'bevel lit edge'],
  ['--lamp-h', 'well', 'fields, tracks, readouts'], ['--ink', 'ink', 'text'], ['--dim', 'dim ink', 'labels, hints'],
  ['--lamp-d', 'lamp off', 'switch off, placeholder'], ['--lamp-l', 'lamp lit', 'on, focus, values'],
  ['--red0', 'signal red dark', 'primary bevel'], ['--red1', 'signal red', 'primary action, radio'], ['--red2', 'signal red lit', 'primary hover, error text'],
  ['--amb0', 'amber dark', 'caution bevel'], ['--amb1', 'hazard yellow', 'caution action, warn'], ['--haz-k', 'hazard black', 'stripe ground'],
];
const cs = getComputedStyle(document.documentElement);
document.getElementById('swatches').innerHTML = SW.map(([v, n, r]) => {
  const hex = cs.getPropertyValue(v).trim();
  return `<div class="sw"><i style="background:var(${v})"></i><div><b>${n}</b><span>${hex} · ${r}</span><span><code>${v}</code></span></div></div>`;
}).join('');

// ---- generic behaviour, delegated so playground output works too
const fmtOut = r => { const o = document.querySelector(`output[for="${r.id}"]`); if (!o) return;
  const st = String(r.step || '1'); const d = st.includes('.') ? st.split('.')[1].length : 0; o.textContent = Number(r.value).toFixed(d); };
document.addEventListener('input', e => { if (e.target.matches('input[type=range]')) fmtOut(e.target); });
document.querySelectorAll('input[type=range]').forEach(fmtOut);
// A parent checkbox is never set to "partial" directly: partial is what it reads while some of its
// children are on. Pressing it sets every child to on, or every child to off once all are on.
const syncParent = g => { const kids = [...g.querySelectorAll('.bh-subgroup input[type=checkbox]:not(:disabled)')], par = g.querySelector('[data-parent]');
  const on = kids.filter(k => k.checked).length; par.checked = on === kids.length; par.indeterminate = on > 0 && on < kids.length; };
document.querySelectorAll('[data-tristate]').forEach(g => { syncParent(g);
  g.addEventListener('change', e => { const par = g.querySelector('[data-parent]');
    if (e.target === par) g.querySelectorAll('.bh-subgroup input[type=checkbox]:not(:disabled)').forEach(k => k.checked = par.checked);
    syncParent(g); }); });
document.addEventListener('click', e => {
  const t = e.target.closest('[role=tab]');
  if (t) t.parentElement.querySelectorAll('[role=tab]').forEach(x => x.setAttribute('aria-selected', String(x === t)));
  const r = e.target.closest('.bh-seg [role=radio]');
  if (r) r.parentElement.querySelectorAll('[role=radio]').forEach(x => x.setAttribute('aria-checked', String(x === r)));
  const tg = e.target.closest('[data-toggle]');
  if (tg) tg.setAttribute('aria-pressed', String(tg.getAttribute('aria-pressed') !== 'true'));
  const st = e.target.closest('[data-step]');
  if (st) { const i = st.parentElement.querySelector('input'); i.value = Math.max(0, (parseInt(i.value, 10) || 0) + Number(st.dataset.step)); }
  const op = e.target.closest('[data-open]');
  if (op) { const d = document.getElementById(op.dataset.open); d.hidden = false; d.querySelector('input,button')?.focus(); }
  const sc = e.target.closest('.bh-scrim');
  if (sc && (e.target === sc || e.target.closest('[data-close]'))) sc.hidden = true;
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') document.querySelectorAll('.bh-scrim').forEach(s => s.hidden = true); });

// ---- viewer mock
const device = document.getElementById('device'), vw = document.getElementById('vw'), vbar = document.getElementById('vbar');
const syncBar = () => vw.style.setProperty('--bar', vbar.offsetHeight + 'px');
new ResizeObserver(syncBar).observe(vbar);
document.getElementById('devW').addEventListener('click', e => { const b = e.target.closest('[data-w]'); if (b) device.dataset.w = b.dataset.w; });
document.getElementById('devA').addEventListener('click', e => { const b = e.target.closest('[data-a]'); if (!b) return;
  vw.querySelectorAll('.anchor').forEach(a => a.hidden = a.dataset.anchor !== b.dataset.a); });

// ---- playground
const esc = s => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const K = {
  text: (id, label, v) => `<div class="bh-field"><label class="bh-label" for="k-${id}">${label}</label><input class="bh-input" id="k-${id}" data-k="${id}" value="${esc(v)}"></div>`,
  num: (id, label, v, min, max, step) => `<div class="bh-slider"><label class="bh-label" for="k-${id}"><span>${label}</span></label><input type="range" id="k-${id}" data-k="${id}" min="${min}" max="${max}" step="${step}" value="${v}"><output class="bh-readout" for="k-${id}">${v}</output></div>`,
  sel: (id, label, opts, v) => `<div class="bh-field"><label class="bh-label" for="k-${id}">${label}</label><select class="bh-input" id="k-${id}" data-k="${id}">${opts.map(o => `<option ${o === v ? 'selected' : ''}>${o}</option>`).join('')}</select></div>`,
  bool: (id, label, v) => `<label class="bh-switch"><input type="checkbox" id="k-${id}" data-k="${id}" ${v ? 'checked' : ''}><span class="knob"></span><span>${label}</span></label>`,
};
const P = {
  button: { knobs: [K.text('label', 'Label', 'Spawn'), K.sel('variant', 'Variant', ['primary', 'secondary', 'ghost', 'caution'], 'primary'), K.sel('size', 'Size', ['sm', 'md', 'lg'], 'md'),
      K.bool('icon', 'Icon only (✕)', false), K.bool('pressed', 'Pressed', false), K.bool('busy', 'Busy', false), K.bool('block', 'Full width', false), K.bool('disabled', 'Disabled', false)],
    html: p => { const c = ['bh-btn']; if (p.variant !== 'secondary') c.push('bh-btn--' + p.variant); if (p.size !== 'md') c.push('bh-btn--' + p.size); if (p.icon) c.push('bh-btn--icon'); if (p.block) c.push('bh-btn--block');
      return `<button class="${c.join(' ')}"${p.icon ? ` aria-label="${esc(p.label)}"` : ''}${p.pressed ? ' aria-pressed="true"' : ''}${p.busy ? ' aria-busy="true"' : ''}${p.disabled ? ' disabled' : ''}>${p.icon ? '✕' : esc(p.label)}</button>`; } },
  switch: { knobs: [K.text('label', 'Label', 'Water glints'), K.bool('checked', 'On', true), K.bool('disabled', 'Disabled', false)],
    html: p => `<label class="bh-switch">\n  <input type="checkbox" id="pl-sw"${p.checked ? ' checked' : ''}${p.disabled ? ' disabled' : ''}>\n  <span class="knob"></span><span>${esc(p.label)}</span>\n</label>` },
  check: { knobs: [K.text('legend', 'Group label', 'Overlays'), K.text('items', 'Options (comma separated)', 'Smell, Sound, Glints'), K.sel('type', 'Type', ['checkbox', 'radio'], 'checkbox'), K.bool('inline', 'Inline', true), K.bool('disabled', 'Disabled', false)],
    html: p => { const it = p.items.split(',').map(s => s.trim()).filter(Boolean);
      return `<fieldset class="bh-fieldset">\n  <legend class="bh-label">${esc(p.legend)}</legend>\n  <div class="${p.inline ? 'bh-inline' : ''}" style="${p.inline ? '' : 'display:grid;gap:4px'}">\n${it.map((s, i) => `    <label class="bh-check"><input type="${p.type}" name="pl-g" id="pl-c${i}"${i === 0 ? ' checked' : ''}${p.disabled ? ' disabled' : ''}><span>${esc(s)}</span></label>`).join('\n')}\n  </div>\n</fieldset>`; } },
  slider: { knobs: [K.text('label', 'Label', 'Travel cost'), K.text('unit', 'Unit', '× per tile'), K.num('min', 'Min', 0, -10, 10, 1), K.num('max', 'Max', 2, 1, 100, 1), K.sel('step', 'Step', ['1', '0.1', '0.05', '0.01'], '0.05'), K.num('value', 'Value', 0.8, -10, 100, 0.05), K.bool('ticks', 'Ticks', true), K.bool('disabled', 'Disabled', false)],
    html: p => { const v = Math.min(Math.max(p.value, p.min), p.max), q = [0, 1, 2, 3].map(i => +(p.min + (p.max - p.min) * i / 3).toFixed(2));
      return `<div class="bh-slider" style="width:min(360px,100%)">\n  <label class="bh-label" for="pl-s"><span>${esc(p.label)}</span><span>${esc(p.unit)}</span></label>\n  <input type="range" id="pl-s" min="${p.min}" max="${p.max}" step="${p.step}" value="${v}"${p.disabled ? ' disabled' : ''}>\n  <output class="bh-readout" for="pl-s">${v}</output>${p.ticks ? `\n  <div class="bh-ticks" aria-hidden="true">${q.map(x => `<span>${x}</span>`).join('')}</div>` : ''}\n</div>`; } },
  field: { knobs: [K.text('label', 'Label', 'Seed'), K.text('value', 'Value', '42'), K.text('placeholder', 'Placeholder', 'A whole number'), K.text('hint', 'Hint', 'The same seed builds the same world.'), K.text('error', 'Error (blank = valid)', ''), K.bool('multiline', 'Multiline', false), K.bool('disabled', 'Disabled', false)],
    html: p => { const bad = p.error.trim() !== ''; const a = `id="pl-f" placeholder="${esc(p.placeholder)}"${bad ? ' aria-invalid="true" aria-describedby="pl-fe"' : p.hint ? ' aria-describedby="pl-fh"' : ''}${p.disabled ? ' disabled' : ''}`;
      return `<div class="bh-field" style="width:min(360px,100%)">\n  <label class="bh-label" for="pl-f">${esc(p.label)}</label>\n  ${p.multiline ? `<textarea class="bh-input" ${a}>${esc(p.value)}</textarea>` : `<input class="bh-input" ${a} value="${esc(p.value)}">`}\n  ${bad ? `<span class="bh-error" id="pl-fe">${esc(p.error)}</span>` : p.hint ? `<span class="bh-hint" id="pl-fh">${esc(p.hint)}</span>` : ''}\n</div>`; } },
  select: { knobs: [K.text('label', 'Label', 'Spawn on tap'), K.text('items', 'Options (comma separated)', 'Herbivore, Predator, Scavenger, Parasite'), K.bool('disabled', 'Disabled', false)],
    html: p => `<div class="bh-field" style="width:min(320px,100%)">\n  <label class="bh-label" for="pl-sel">${esc(p.label)}</label>\n  <select class="bh-input" id="pl-sel"${p.disabled ? ' disabled' : ''}>\n${p.items.split(',').map(s => s.trim()).filter(Boolean).map(s => `    <option>${esc(s)}</option>`).join('\n')}\n  </select>\n</div>` },
  tabs: { knobs: [K.text('items', 'Tabs (comma separated)', 'Attributes, Genome, Lineage, Mind, Body'), K.num('sel', 'Selected', 1, 1, 8, 1)],
    html: p => { const it = p.items.split(',').map(s => s.trim()).filter(Boolean);
      return `<div class="bh-tabs" role="tablist" style="width:min(520px,100%)">\n${it.map((s, i) => `  <button role="tab" aria-selected="${i + 1 === p.sel}">${esc(s)}</button>`).join('\n')}\n</div>`; } },
  seg: { knobs: [K.text('label', 'Label', 'Speed'), K.text('items', 'Options (comma separated)', 'Pause, 1×, 4×, 16×'), K.num('sel', 'Selected', 2, 1, 8, 1)],
    html: p => `<div class="bh-field">\n  <span class="bh-label" id="pl-segl">${esc(p.label)}</span>\n  <div class="bh-seg" role="radiogroup" aria-labelledby="pl-segl">\n${p.items.split(',').map(s => s.trim()).filter(Boolean).map((s, i) => `    <button role="radio" aria-checked="${i + 1 === p.sel}">${esc(s)}</button>`).join('\n')}\n  </div>\n</div>` },
  meter: { knobs: [K.text('label', 'Label', 'Energy'), K.num('value', 'Value', 64, 0, 100, 1), K.sel('tone', 'Tone', ['normal', 'warn', 'crit', 'busy'], 'normal')],
    html: p => `<div class="bh-meter${p.tone !== 'normal' ? ' bh-meter--' + p.tone : ''}" style="width:min(360px,100%)">\n  <span class="bh-label">${esc(p.label)}${p.tone === 'busy' ? '' : ` · ${p.value} of 100`}</span>\n  <div class="track" role="${p.tone === 'busy' ? 'progressbar' : 'meter'}"${p.tone === 'busy' ? '' : ` aria-valuenow="${p.value}" aria-valuemin="0" aria-valuemax="100"`} aria-label="${esc(p.label)}">\n    <div class="fill" style="width:${p.value}%"></div>\n  </div>\n</div>` },
  level: { knobs: [K.num('levels', 'Floors', 4, 2, 8, 1), K.num('at', 'Current floor (0 = deepest)', 1, 0, 7, 1), K.num('surface', 'Surface floor', 1, 0, 7, 1)],
    html: p => { const at = Math.min(p.at, p.levels - 1), d = at - p.surface, name = d > 0 ? `level +${d}` : `level ${d}`;
      return `<div class="bh-level" role="group" aria-label="level">\n  <div class="bh-updown">\n    <button class="bh-btn" aria-label="up a level"${at >= p.levels - 1 ? ' disabled' : ''}><i class="bh-arrow"></i></button>\n    <button class="bh-btn" aria-label="down a level"${at <= 0 ? ' disabled' : ''}><i class="bh-arrow bh-arrow--down"></i></button>\n  </div>\n  <span class="bh-level-name">${name}</span>\n</div>`; } },
  badge: { knobs: [K.text('label', 'Label', 'Live'), K.sel('tone', 'Tone', ['neutral', 'live', 'warn', 'crit'], 'live')],
    html: p => `<span class="bh-badge${p.tone !== 'neutral' ? ' bh-badge--' + p.tone : ''}">${esc(p.label)}</span>` },
  toast: { knobs: [K.text('msg', 'Message', 'Genome injected · 1 body spawned'), K.sel('tone', 'Tone', ['info', 'warn', 'crit'], 'info'), K.text('action', 'Action (blank = none)', '')],
    html: p => `<div class="bh-toast${p.tone !== 'info' ? ' bh-toast--' + p.tone : ''}" role="${p.tone === 'crit' ? 'alert' : 'status'}">${esc(p.msg)}${p.action.trim() ? ` <button class="bh-btn bh-btn--sm">${esc(p.action)}</button>` : ''}</div>` },
  dialog: { knobs: [K.text('title', 'Title', 'Reset world'), K.text('body', 'Body', 'This wipes all 1,164 bodies and builds a new world.'), K.text('confirm', 'Confirm label', 'Reset world'), K.bool('destructive', 'Destructive (hazard)', true)],
    html: p => `<div class="bh-panel bh-dialog" role="dialog" aria-modal="true" aria-labelledby="pl-dh">\n  <header><h4 id="pl-dh">${esc(p.title)}</h4><div class="bh-actions"><button class="bh-btn bh-btn--icon bh-btn--sm" aria-label="Close dialog">✕</button></div></header>${p.destructive ? '\n  <div class="bh-hazard"></div>' : ''}\n  <div class="bh-body"><p style="margin:0">${esc(p.body)}</p></div>\n  <footer>\n    <button class="bh-btn bh-btn--ghost">Cancel</button>\n    <button class="bh-btn bh-btn--${p.destructive ? 'caution' : 'primary'}">${esc(p.confirm)}</button>\n  </footer>\n</div>` },
};
const comp = document.getElementById('p-comp'), knobs = document.getElementById('p-knobs'), out = document.getElementById('pout'), code = document.getElementById('pcode'), stage = document.getElementById('pstage');
const read = () => { const p = {}; knobs.querySelectorAll('[data-k]').forEach(el => {
  p[el.dataset.k] = el.type === 'checkbox' ? el.checked : el.type === 'range' ? Number(el.value) : el.value; }); return p; };
const render = () => { const html = P[comp.value].html(read()); out.innerHTML = html; code.textContent = html;
  out.querySelectorAll('input[type=range]').forEach(fmtOut); };
const build = () => { knobs.innerHTML = P[comp.value].knobs.join(''); knobs.querySelectorAll('input[type=range]').forEach(fmtOut); render(); };
comp.addEventListener('change', build);
knobs.addEventListener('input', render); knobs.addEventListener('change', render);
document.getElementById('p-w').addEventListener('click', e => { const b = e.target.closest('[data-w]'); if (b) stage.dataset.w = b.dataset.w; });
document.getElementById('p-world').addEventListener('change', e => stage.classList.toggle('world', e.target.checked));
document.getElementById('pcopy').addEventListener('click', async e => {
  const btn = e.currentTarget;
  try { await navigator.clipboard.writeText(code.textContent); btn.textContent = 'Copied'; }
  catch { const r = document.createRange(); r.selectNodeContents(code); const s = getSelection(); s.removeAllRanges(); s.addRange(r); btn.textContent = 'Selected'; }
  setTimeout(() => btn.textContent = 'Copy', 1400);
});

// level stepper specimens: up over down, ends disable instead of wrapping
document.querySelectorAll('[data-stepper]').forEach(st => {
  const n = Number(st.dataset.levels), surf = Number(st.dataset.surface); let z = Number(st.dataset.at);
  const up = st.querySelector('[data-dz="1"]'), dn = st.querySelector('[data-dz="-1"]'), nm = st.querySelector('.bh-level-name');
  const sync = () => { const d = z - surf; nm.textContent = d > 0 ? `level +${d}` : `level ${d}`; up.disabled = z >= n - 1; dn.disabled = z <= 0; };
  st.addEventListener('click', e => { const b = e.target.closest('[data-dz]'); if (!b || b.disabled) return; z += Number(b.dataset.dz); sync(); });
  sync();
});

// floor rail specimens: one plate per floor, highest first; a press moves the lamp
document.querySelectorAll('[data-rail]').forEach(r => {
  const n = Number(r.dataset.levels), surf = Number(r.dataset.surface); let at = Number(r.dataset.at);
  for (let z = n - 1; z >= 0; z--) {
    const d = z - surf, b = document.createElement('button');
    b.className = 'bh-rail-floor' + (d === 0 ? ' is-surface' : ''); b.dataset.z = String(z);
    b.textContent = d > 0 ? `+${d}` : d < 0 ? `\u2212${-d}` : '0';
    b.setAttribute('aria-label', d > 0 ? `level +${d}` : `level ${d}`);
    r.append(b);
  }
  const sync = () => r.querySelectorAll('.bh-rail-floor').forEach(b => b.setAttribute('aria-current', String(Number(b.dataset.z) === at)));
  r.addEventListener('click', e => { const b = e.target.closest('.bh-rail-floor'); if (b) { at = Number(b.dataset.z); sync(); } });
  sync();
});
build();
