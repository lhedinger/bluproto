// The panel system the viewer's floating boxes share: anchors, the corner
// they contend for, their header chrome, and the one-letter shortcut filter.

// ---- panels ----------------------------------------------------------------
// Every box that floats over the world obeys the same three rules (the CSS side
// of them is the .panel block in index.html):
//
//   1. It is shown and hidden by the `open` class, never by an inline display.
//   2. Its anchor is exactly ONE class — card, menu, half or full — and setting
//      one clears the others, so two geometries can never both apply. They used
//      to: a later `#inspect.dbg { width: … }` quietly beat the full-screen rule
//      above it and the panel stayed a card while the code believed otherwise.
//   3. The menu and both panel sizes share the top-right corner, so opening one
//      closes the rest. Each registers its OWN close, because putting a sibling
//      away also has to unlight its toolbar button and stop its polling —
//      dropping the class alone left a lit button over a panel that was gone.
//
// A card is exempt from rule 3. It is a caption on the world in the opposite
// corner, and it coexists with whatever is open.

export type PanelSize = 'card' | 'menu' | 'half' | 'full';
const PANEL_SIZES: PanelSize[] = ['card', 'menu', 'half', 'full'];

/** The panels that claim the top-right corner, each with how it puts itself
 *  away. Also the order Escape considers them in. */
export const rightColumn: Array<{ el: HTMLElement; close: () => void }> = [];

export function claimRightColumn(mine: HTMLElement): void {
  for (const p of rightColumn) {
    if (p.el === mine || p.el.classList.contains('card')) continue;
    if (p.el.classList.contains('open')) p.close();
  }
}

/** Opens a panel, optionally re-anchoring it. Omit `size` to reopen at
 *  whatever size it was last given — the anchor class survives closing, so a
 *  panel the viewer made full screen comes back full screen. */
export function openPanel(el: HTMLElement, size?: PanelSize): void {
  if (size) {
    el.classList.remove(...PANEL_SIZES);
    el.classList.add(size);
  }
  el.classList.add('open');
  syncSizeBtn(el);
  if (!el.classList.contains('card')) claimRightColumn(el);
}

export function closePanel(el: HTMLElement): void {
  el.classList.remove('open');
}

/** The ⛶/⊟ describes what the next click will DO, derived from the panel's
 *  current anchor every time rather than written once. The inspector rebuilds
 *  its header on each 1 Hz poll, and a hardcoded glyph came straight back
 *  offering "full screen" on a panel that was already half. */
/** A panel's close: a real button, so it is reachable by Tab and answers Enter. */
export const CLOSE_BTN = '<button type="button" class="x" title="close" aria-label="close">✕</button>';

export function syncSizeBtn(el: HTMLElement): void {
  const b = el.querySelector(':scope > h3 .szbtn') as HTMLElement | null;
  if (!b) return;
  const full = el.classList.contains('full');
  b.textContent = full ? '⊟' : '⛶';
  b.title = full ? 'half screen' : 'full screen';
  b.setAttribute('aria-label', b.title);
}

/**
 * Wires a panel's header: the ⛶/⊟ swaps half and full, the ✕ closes. Call it
 * once for a panel whose header is static markup, and after every render for
 * one that rebuilds its own — the handlers are assigned, not added, so a
 * re-render cannot stack a second copy.
 *
 * `onResize` is for a panel that has to repaint at the new size (a chart on a
 * canvas). Text reflows on its own and needs nothing.
 */
export function wirePanelChrome(el: HTMLElement, close: () => void, onResize?: () => void): void {
  const h = el.querySelector(':scope > h3');
  if (!h) return;
  const sz = h.querySelector('.szbtn') as HTMLElement | null;
  if (sz) {
    syncSizeBtn(el);
    sz.onclick = () => {
      const full = el.classList.contains('full');
      el.classList.remove('half', 'full');
      el.classList.add(full ? 'half' : 'full');
      syncSizeBtn(el);
      onResize?.();
    };
  }
  const x = h.querySelector('.x') as HTMLElement | null;
  if (x) x.onclick = close;
}

/** A bare key press outside any field: what a one-letter shortcut answers to.
 *  Not with a modifier (ctrl+d is the browser's bookmark, cmd+p its print), not
 *  auto-repeat (a held key flickered debug on and off), not while typing. */
export function isShortcut(ev: KeyboardEvent): boolean {
  if (ev.ctrlKey || ev.metaKey || ev.altKey || ev.repeat) return false;
  const t = ev.target as HTMLElement;
  return !(t.isContentEditable || /^(INPUT|SELECT|TEXTAREA)$/.test(t.tagName));
}
