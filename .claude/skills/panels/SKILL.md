---
name: panels
description: Add or change anything the web client shows over the world — a panel, a chart, an inspector, a tab, a legend, a toolbar button. Use whenever the task is to surface new information in the viewer, to restyle or resize an existing panel, to add a control, or to fix something that reads badly on screen. Also use when reviewing a diff that touches client/index.html, the panel or inspector code in client/src/main.ts, or any new HTTP endpoint whose only consumer is the viewer.
---

# Panels in the web client

Everything the viewer shows over the world — the population chart, the debug
inspector, the constants editor, the sense overlays — follows a small set of
rules that are not enforced by anything. `./gradlew check` does not run the
client. `tsc` will happily compile a panel that opens on top of another panel,
in a size nobody chose, drawn by a library nobody else uses. Every rule below
exists because it was broken first and looked wrong on a screenshot.

## The rules

### Panels come in two sizes: half or full

Nothing else. **Half** is a right-hand column at half the viewport's height;
**full** fills the viewport under the toolbar. A ⛶/⊟ toggle in the panel's own
header swaps them, and the content reflows and redraws at whatever size it was
given — a canvas re-measures and repaints, a list re-wraps.

The population chart shipped as a 360×120 card. It was legible only as a
sparkline, and the thing it was meant to show — a lineage thinning out over
hours — could not be read at that size at all. A chart worth opening is worth
reading. If a panel's content only makes sense small, it is a caption on the
world (the plain entity card), not a panel.

Write the sizes in `client/index.html` next to the panel's own rule, and give
the panel exactly ONE positioning rule. Two rules that both set a panel's width
is the bug that shipped: a later `#inspect.dbg { width: min(340px, 84vw) }`
silently beat the full-screen rule added above it, and the panel stayed a card
while the code that opened it believed otherwise.

### One panel per subject, tabs inside it

A panel never opens another panel. When a subject has several faces, they are
**tabs of one panel**, not a chain of panels with "← back" between them.

The debug inspector is the worked example: `attributes | genome | lineage |
mind` for one creature. The mind used to be a second panel that the inspector
hid itself to open. That meant the viewer had to remember which panel they were
in, the tab bar stopped describing what was on screen, and closing the second
panel had to reconstruct the state the first was in. All of that disappears when
it is a tab.

Conditional tabs are fine and often right — the mind tab appears only for a body
that has a brain, because a tab that says "no brain" wastes the click. When the
selection changes to something without that tab, fall back to the first tab
rather than leaving the panel empty.

### A sticky header owns the padding it covers

A panel header that stays put while the body scrolls is `position: sticky; top:
0`, and it must be **opaque** — the panel's own translucent fill lets rows show
through as they pass under it.

Give it the panel's TOP padding (`#inspect { padding: 0 12px 10px }`, header
`padding: 10px 12px 6px`) and **no negative top margin**. Negative side margins
for full-bleed are fine; a negative *top* margin is the trap. A sticky offset
constrains the element's MARGIN box, so `margin-top: -10px` with `top: 0` parks
the header ten pixels lower than flush the moment it sticks, and the panel's top
padding becomes a window onto the text scrolling underneath. It looks correct at
rest — which is why it shipped, twice, in `#inspect` and `#tuning` — and fails
only while doing its job.

Measure it rather than squinting: with the body scrolled, the header's
`getBoundingClientRect().top` minus the panel's inner top edge must be **0**.
Anything positive is that window. Prove the measurement works by re-injecting
the old rule with `addStyleTag` and watching the number go to 10.

### The tab is state, and it survives

Selecting a different creature keeps the tab. A viewer walking a family line
through the lineage tab stays on lineage from body to body; that is what makes
the tree walkable. Store it in a module-level variable, not in the DOM.

### A tab that polls repaints only itself

The inspector re-renders on its 1 Hz detail poll. A tab that needs to be faster
than that — the mind's program counter is unreadable at 1 Hz — keeps its own
timer, but writes into **its own body element only** (`#mindBody`), never the
whole panel. Repainting the panel twice a second would throw away the reader's
scroll position in a long listing and re-run every handler.

Start and stop that timer from one function called wherever the tab, the
selection, or the panel's existence can change (`syncMindPoll`). A poll that
outlives its tab is a leak that fetches forever.

### Charts are drawn by hand, on canvas

The client has **no runtime dependencies** — `client/package.json` carries only
vite, typescript and playwright, all dev-only — and the bundle is around 19 KB
gzipped. The WebGL world renderer, the atlases, the population lines and the
lineage Sankey are all hand-rolled Canvas2D or GL.

Do not add a charting library for one panel. It would be the project's first
runtime dependency, arriving for the easiest rendering job in a codebase that
hand-rolls a far harder one. The bespoke part of even the Sankey is ~150 lines.

This is a decision, not a dogma. If a panel needs hover hit-testing, tooltips
and transitions, the calculus changes — and then reach for small utilities
(scales, shape generators) rather than a monolithic widget, so the drawing
stays under our control. Requirements like stable lane ordering and non-linear
widths are exactly what a general layout engine will fight.

### Non-linear scales say so, and print their numbers

The lineage Sankey's ribbon width is `1 + log₁₀(heads)`, because a linear scale
crushed every predator ribbon to a hairline beside the herbivores, and the thin
ribbons are the story. That is the right call when magnitudes span orders — and
it costs additivity: a bar no longer equals the sum of its ribbons.

So the chart states the scale in its corner (`width ≈ 1+log₁₀ heads`) and prints
**true counts** on the labelled columns. Where a width stops being readable as a
number, the number goes on the picture.

### A filter must not be able to lie

The Sankey filters to one clade at a time. That is safe for a specific reason
worth checking before copying it: **a clade is inherited and never mutated**, so
no flow ever crosses clades, and filtering can only omit whole ribbons — never
sever one. A filter that can cut a flow in half would make the diagram wrong
rather than smaller.

The same reasoning is why the role lens lets the legend hide a series and the
Sankey does not: hiding a line only rescales an axis, but hiding a species would
break the conservation that IS the diagram.

## Before you finish

`./gradlew check` does not look at any of this. What follows is the check.

1. **Open it in a real browser.** `./gradlew :server:run`, then drive it with
   Playwright from the scratchpad — `localhost` bypasses the agent proxy, and
   the bundled Chromium is at `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`.
   Click the actual controls; screenshot the actual panel
   (`(await p.$('#inspect')).screenshot(...)`, not a viewport crop).
2. **Check both sizes**, and both with content present and absent.
3. **Assert what you removed is gone**, not just that the new thing renders —
   count the old buttons and the old panel's elements in the DOM.
4. **Watch a polling tab across at least two polls**: the body updates, the tab
   bar and the panel survive, the scroll holds.
5. **`p.on('pageerror')`** on every run. A panel that throws still looks fine in
   a screenshot.

Two traps that cost real time here:

- **The tile inspector also wears `.dbg`.** Testing for that class does not tell
  you a creature is open; the presence of the tab bar does.
- **Clicking blindly hits ground, not bodies.** In debug mode a tap on open
  ground opens the tile inspector. To select a creature, compute its screen
  position — `Camera.fit` centres the world and scales it to the canvas, so
  `screen = canvas/2 + (world − centre) × scale`, divided by the DPR — and
  click exactly there.
