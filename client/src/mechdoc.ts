// The mechanics-reference renderer, shared by every documentation page (/help
// and its /help/genome and /help/body subpages). The server hands each page a
// JSON list of sections — every figure in them read off the running
// simulation's own constants — and this module turns one section into DOM.
// One renderer for three pages, so the reference cannot drift apart visually
// and a new kind of block (a worked table, a channel list) reaches all of
// them at once.

export interface MechRow { label: string; value: string; unit: string; note: string; }
export interface MechTable { caption: string; headers: string[]; rows: string[][]; }
export interface MechItem { name: string; detail: string; idx?: string; }
export interface MechGroup { title: string; items: MechItem[]; }
export interface MechSection {
  id: string; title: string; intro: string; rows: MechRow[];
  table?: MechTable; groups?: MechGroup[];
}

/** The nav wraps to however many rows its links need, so the anchor offset
 *  can't be a constant — measure the real height and keep the scroll root's
 *  padding in step with it. Every documentation page has the one sticky
 *  #pagenav, so the measurement lives with the renderer they share. */
export function syncNavPad(): void {
  const nav = document.getElementById('pagenav');
  if (nav) document.documentElement.style.scrollPaddingTop = `${nav.offsetHeight + 8}px`;
}
if (typeof window !== 'undefined') window.addEventListener('resize', syncNavPad);

/** Text into a fresh element, escaped by the DOM rather than by us. */
export function el<K extends keyof HTMLElementTagNameMap>(
    tag: K, text?: string, cls?: string): HTMLElementTagNameMap[K] {
  const e = document.createElement(tag);
  if (text !== undefined) e.textContent = text;
  if (cls) e.className = cls;
  return e;
}

export function mechSection(m: MechSection, into: HTMLElement): void {
  const h = el('h2', m.title);
  h.id = m.id;
  into.append(h);
  // Blank-line-separated paragraphs, so the server can write more than one
  // without smuggling markup through the wire.
  for (const para of m.intro.split('\n\n')) into.append(el('p', para, 'note'));

  const t = el('table', undefined, 'facts');
  const tb = el('tbody');
  for (const r of m.rows) {
    const tr = el('tr');
    tr.append(el('th', r.label));
    const v = el('td', r.value, 'v');
    if (r.unit) {
      v.append(document.createTextNode(' '));
      v.append(el('span', r.unit, 'unit'));
    }
    tr.append(v, el('td', r.note, 'why'));
    tb.append(tr);
  }
  t.append(tb);
  into.append(wide(t));

  if (m.table) into.append(worked(m.table));
  if (m.groups) for (const g of m.groups) into.append(channels(g));
}

/** A named group of channels — a sensor bank, a set of acts, a clade's built-in
 *  reflexes. These describe a SURFACE rather than a quantity, so they read as a
 *  list of names with what each one means, not as a table of figures. Where the
 *  name is a wire name the engine itself uses, the list is checkable. */
export function channels(g: MechGroup): HTMLElement {
  const box = el('div', undefined, 'chan');
  box.append(el('h3', g.title));
  const dl = el('dl');
  for (const i of g.items) {
    dl.append(el('dt', i.name), el('dd', i.detail));
  }
  box.append(dl);
  return box;
}

/** A worked table: the constants above, applied across the range of bodies or
 *  paces the world can actually produce. This is where the model stops being a
 *  formula and starts being a claim about what living here is like. */
export function worked(w: MechTable): HTMLElement {
  const t = el('table', undefined, 'worked');
  const head = el('tr');
  for (const h of w.headers) head.append(el('th', h));
  const th = el('thead');
  th.append(head);
  const tb = el('tbody');
  for (const row of w.rows) {
    const tr = el('tr');
    for (const cell of row) tr.append(el('td', cell));
    tb.append(tr);
  }
  t.append(th, tb);
  const cap = el('figcaption', w.caption, 'tcap');
  const box = el('div', undefined, 'tblock');
  box.append(wide(t), cap);
  return box;
}

/** Wraps a table so a narrow phone scrolls the TABLE sideways rather than the
 *  page — the site is watched on a phone more often than not. */
export function wide(t: HTMLElement): HTMLElement {
  const d = el('div', undefined, 'scroll');
  d.append(t);
  return d;
}

/**
 * Fetches one documentation page's sections and renders them into `mount`,
 * filing a link per section into the nav span named by `navGroup`. The whole
 * of a subpage's body, and the mechanics half of /help.
 */
export async function renderMechPage(url: string, mount: HTMLElement,
    navGroup?: string): Promise<MechSection[]> {
  let secs: MechSection[] = [];
  try {
    const r = await fetch(url);
    if (r.ok) secs = await r.json();
  } catch {
    /* offline: better a page with no rules on it than a page of stale ones */
  }
  for (const m of secs) {
    if (navGroup) {
      const host = document.getElementById(navGroup);
      if (host) {
        const a = document.createElement('a');
        a.href = `#${m.id}`;
        a.textContent = m.title;
        host.append(a);
      }
    }
  }
  for (const m of secs) mechSection(m, mount);
  return secs;
}
