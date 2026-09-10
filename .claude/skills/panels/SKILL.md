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

### There is one panel system, and everything floating is in it

`.panel` in `client/index.html` owns the chrome — fill, border, radius, blur,
padding, the sticky header, `box-sizing: border-box`. A floating box gets that
class plus exactly ONE anchor class, and the anchor is the only thing that
differs between them:

| anchor | where | sizes |
|---|---|---|
| `.card` | bottom left, sized to content | none |
| `.menu` | under the toolbar at the right, sized to content | none |
| `.half` | right-hand column, 480px × 50vh | ⛶ → full |
| `.full` | everything under the toolbar | ⊟ → half |

**A panel is half or full and nothing between.** The population chart shipped
as a 360×120 card; it was legible only as a sparkline, and the lineage thinning
out over hours that it existed to show could not be read at that size at all. A
chart worth opening is worth reading.

**A card is not a small panel, it is a different thing** — a caption on the
world, like the plain entity card and the tile dump. Content that only makes
sense small is a card, and content that needs room is a panel. Get this wrong
in the other direction and you ship what the tile inspector shipped: four rows
of ground facts opening at full screen, with no sizer to shrink them.

Set the anchor through `openPanel(el, size)` in `main.ts`, which clears the
other three first. Never hand-write a second positioning rule for a panel: a
later `#inspect.dbg { width: min(340px, 84vw) }` silently beat the full-screen
rule added above it, and the panel stayed a card while the code that opened it
believed otherwise.

### Visibility is a class, never an inline style

`.open` shows a panel; removing it hides one. Three different mechanisms used
to coexist — an `.open` class driven by a module boolean, `style.display`
compared against the string `"block"`, and `style.display` plus a `className`
reset that also wiped the size — and no two panels agreed.

### One corner, one panel

`.menu`, `.half` and `.full` all anchor to the same top-right point, so opening
one closes the rest (`claimRightColumn`). A `.card` is exempt: it lives in the
opposite corner and coexists with whatever is up.

Register the close **function**, not the element. Putting a sibling away also
has to unlight its toolbar button and stop its polling; dropping the class
alone left a lit `population` button over a panel that was gone.

### Every panel can be dismissed, two ways

A ✕ in its own header, and Escape. Escape takes the corner panel first and the
card second. Two panels used to have no dismissal of their own at all — the
population chart could only be closed from the toolbar button that opened it —
and none of them answered the key every other window on the machine answers.

### A control's label is derived, never written down

The ⛶/⊟ glyph and its tooltip come from the panel's current anchor every time
it renders (`syncSizeBtn`), because a panel that re-renders will re-render the
glyph too. Written out at build time, the inspector's came straight back from
the 1 Hz detail poll offering "full screen" on a panel that was already half.

The same reasoning applies to any control that describes state: read the state,
do not remember what you last set it to.

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

Give it the panel's TOP padding (`.panel { padding: 0 12px 10px }`, header
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

Traps that cost real time here:

- **The inspector is three surfaces in one element.** `#inspect` is the entity
  card, the tile card and the creature panel, and only its anchor class says
  which. Testing that it is open tells you nothing about what is in it: the
  header text does (`tile …`), and the tab bar means a creature.
- **Clicking blindly hits ground, not bodies.** To select a creature, compute
  its screen position — `Camera.fit` centres the world and scales it to the
  canvas, so `screen = canvas/2 + (world − centre) × scale`, divided by the DPR
  — and click exactly there. A long press anywhere opens the tile card instead,
  in every mode.
- **`content-box` makes a width lie.** A panel declared 480px measured 506 once
  the 12px sides and the border were added. `.panel` sets `border-box` so the
  number in the CSS is the number on screen; anything you add inside it should
  not reintroduce the gap.
- **The toolbar wraps.** On a phone it is two rows, and it grows a row on any
  screen when debug reveals the constants button. Panels open below
  `--bar-h`, which a `ResizeObserver` measures from the toolbar itself; the
  fixed 52px that preceded it put every panel on top of its own buttons.
