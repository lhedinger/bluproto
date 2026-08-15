# The pixel-art design system

How bluproto draws its world, written down as the system it already is. The
rules here are extracted from the renderer (`GroundTextures`, `Grid`,
`ProcCreature`, the `render` painter package) — when code and this document
disagree, one of them has a bug worth a commit. The style is enforced socially,
not mechanically: every new visual should walk the checklist at the bottom.

## 1. Foundations

### The art-pixel grid
The atomic unit is the **art-pixel**: every tile is **12×12 art-pixels**
(`A = 12`), whatever the on-screen zoom. Everything painted on the ground —
textures, dithers, door slabs, switch lamps — lands on this grid, indexed by
**world-absolute** art-pixel coordinates (`gx = tileX * 12 + ai`), so patterns
are stable frame to frame and continuous across tile seams. Screen size per
art-pixel is derived (`tileSize / 12`, fractionally rounded); nothing may
position itself off-lattice to "look smoother".

### Texture is identity, scalars are magnitude
Terrains are told apart by **pattern**, not by flat colour: grass stipples,
mud speckles, water ripples, steel rivets. A live scalar (vegetation density,
crystal density, depletion) rides **on top of** the identity as a modulation —
motif density, dither coverage — never as a palette change.

### Determinism
All texture functions are **pure functions of world position and seed**
(`Utils.noise2` and `hash01` draw no RNG). A texture may never consume sim RNG
or wall-clock time; animation phase comes from the tick.

## 2. Colour

### Ramps: three shades per class
Every terrain class owns a **ramp of exactly three colours** —
`{shadow, base, highlight}` (`GroundTextures.RAMP`). All ground art is built
from ramp entries; materials elsewhere (door timber, switch steel) borrow
existing ramp families so built things sit in the same world as grown things.

### No in-between colours
Gradients render as **ordered-dither mixes of the same three ramp colours,
never as new in-between colours** (`ditherRamp`). Alpha-blending a wash over
art to approximate a state is a violation — the depletion overlay bug and the
corpse grey-square bug were both exactly this. The sanctioned translucency is
listed in §4 (shadows and haze); everything else is opaque ramp paint.

### Accents: rare, small, deliberate
A handful of colours are allowed to sit **above** their ramp, and each is
disciplined: the fungus spark (`0x9df5c6`), crystal glint (`0xD0ECFF`), vent
ember (`0xD8622C`), bloom red/cream (`0xE0455F`/`0xF0E8C6` — shared by
wildflowers, shrub berries and thicket berries so all flora reads as one
family). Accents are single art-pixels or 2-px motifs, at low hash-gated
frequency. If an accent is common enough to read as a texture, it is a ramp
colour now — pick one of the three.

### Signal colours
Interactive state gets its own reserved family: button/plate red
(`0xE0455F`, dark `0x7c2434`, lit `0xF0788C`), indicator lamps (housing
`0x14161f`, dim `0x6a7280`, lit `0xD0ECFF`), hazard striping
(`0xd8b028` on `0x17171a`) for anything that drops or crushes. The minded rim
violet (`#c660ff`) belongs to the viewer's overlay language, not to the world.

## 3. Pattern grammars

These are the approved ways to build texture. New art composes these before
inventing new ones.

- **Ordered dither (Bayer 4×4)** — for *coverage and gradients*: the classic
  threshold matrix indexed by world-absolute art-pixel (`bayer(px, py)`).
  Period 4 divides the 12-px tile, so every tile is in phase. Used for shade
  ramps, depletion, shore deepening, rim dropouts.
- **Hash scatter** — for *organic placement*: `hash01(x, y, salt)` gates
  grains, sparks, tufts. Grains come in 2-px clusters, **never lone pixels**
  (`quietGround`). A smell uses hash stipple, not Bayer — the ordered matrix
  reads as mechanical, wrong for organic haze.
- **Motif lattice** — for *stamped plants*: a coarse cell grid (grass uses
  3-px pitch) where each cell hash-decides whether it grows a motif (tuft,
  clump, rosette, wildflower) and jitters its anchor. Motif **density** is the
  live-scalar hook (grazed lawns go quiet before they go bare).
- **Autotiled edges** — boundaries are *drawn shapes, not noise*: the
  higher-ranked terrain laps 1–3 px into its neighbour in scalloped runs with
  quarter-round corners (`resolveEdge`), deterministic and tile-anchored.
- **Crack networks** — seams for baked ground (badland clay, flagstones):
  the crack colour is the ramp's own shadow shade — "soft creases, never hard
  black lines".

## 4. Light, depth and shadow

One sun, straight overhead-north. The grammar:

- **Raised things**: screen-**north edge lit** (ramp highlight, or ×1.3
  shade), screen-**south edge sunk** (×0.65), long flanks rimmed slightly dark
  (×0.78). Walls add a face band where mass fronts open ground to the south,
  and a cornice/base-shadow read (`wallDepth`). Doors, crates and slabs reuse
  exactly this.
- **Sunken things** (pits, shafts): a lit, *crumbling* north lip — a broken
  run of dashes with per-column depth, not a solid band — and thin dim edges
  with dithered dropouts elsewhere.
- **Drop shadows**: one art-pixel south of the body, translucent black
  (~`alpha 110`), art-pixel-aligned blocks. Entities that stand (doors,
  shrubs, creatures) sit ON the ground because of this; nothing floats.
- **Sanctioned translucency** — exactly four: (1) drop/contact shadows as
  above; (2) the **blocky translucent oval** — an ellipse rasterised into
  art-pixel steps, each block *tinting* the ground (shrub shadows, hollows);
  (3) pheromone haze blocks (chunky art-pixels at low alpha — structure stays
  pixelated, only the tint is soft); (4) the **concealment veil** — every
  cover re-stamp (thicket canopy, reed stalks, duct lids) draws at a single
  global **25% translucency** (`VEIL_ALPHA = 0.75`, defined once in
  `GroundTextures` and mirrored in the web client's `render.ts`), so a veiled
  body always half-reads through its cover. Smooth `fillOval`/`arc`/
  anti-aliased curves are **never** drawn into world art.

## 5. Entities

- **Creatures** are `ProcCreature` organisms: procedural bodies on their own
  small art-pixel grid (radius `ph.r` art-px), palette derived from genome
  markers, 8 facings × 8 gait frames, plus shared **action envelopes**
  (squash/stretch/offset/tint/dissolve — lunge, hurt, eat, court, alarm,
  spawn, death). State changes are envelopes over the same body, never
  separate sprites.
- **Corpses** keep the body and lose the colour (saturation stripped by
  luminance, then a dark drain) — what died and how big stays readable.
- **Furniture** (doors, switches, nests) is built from tile-ramp materials on
  the 12-px grid, obeys the raised grammar, and its *state* is drawn state:
  leaves slide, plates sink, lamps light. Machinery signals with the signal
  colours; wiring is shown (lamp trails), not implied.
- **Items** are compact glyphs with a shadow, a body, and one readable
  identity feature (leaf, braces, spikes).
- **Authored beats computed.** Small fixtures and glyphs are hand-drawn
  stamps (a string-array sprite in the painter), not geometry rasterised by
  distance tests — a computed ring of blocks is on-lattice and ramp-coloured
  and *still* reads as lumpy math, because pixel art is symmetric runs and a
  deliberate outline. Procedural texture is for *fields* (ground, patterns);
  discrete objects get drawn.

## 6. The two renderers

The **Java renderer is the visual source of truth**. It bakes the ground
layers and creature atlases the web serves, and draws scenario captures.
The web client repaints what it must live (furniture glyphs, overlays,
corpse/rim bakes) in a deliberately simpler idiom — but that idiom follows
the same rules where it counts: art-pixel blocks over smooth curves, ramp
colours over invented ones, dither over alpha washes. Where the client
overlays state onto the bake — grazing, concealment — it does not paint its
own idea of the state: it **re-composites the bake's own pixels** (the bare
twin through the dither mask; the cover tile's pixels through the ported gap
mask), so every pixel shown is one the Java renderer authored.

**`/sprites` is the catalog of record for the web view.** Everything the
client draws into the world must have an entry there, rendered by the *same
code path* the live view runs (the exported painters and compositors —
`drawDoor`, `veilTile`, `ditherTile`, the atlas bakes), with the Java bake
beside it wherever a Java twin exists. A client visual with no catalog entry
is a review failure; a divergence visible on the page is a bug report.

## 7. Case law — how these rules were learned

Every rule above that reads like an opinion was paid for by a visual bug.
The precedents, so nobody pays twice:

- **The depletion wash** — grazing depletion was first drawn as a translucent
  brown alpha wash in 3×3-art-pixel blocks, Bayer-indexed per tile. Three
  violations in one: invented in-between colours, cells coarser than the art
  grid, and a dither out of phase with the bake's own. The lasting lesson went
  further than fixing the overlay: *a state is another bake, not a tint* — the
  server now bakes a fully-grazed twin and the client dithers between the two
  bakes per art-pixel, so every pixel shown is one the renderer authored.
- **The corpse grey square** — the client's corpse bake filled the whole atlas
  cell with grey in `'saturation'` mode, assuming the blend would clip to the
  sprite. Blend modes composite source-over: everywhere the cell was
  transparent turned opaque grey, and every corpse stamped a grey tile.
  Rule: blend modes are not clips — re-clip to the silhouette
  (`destination-in`) after any whole-canvas pass. Corollary: `/sprites` exists
  so a bug like this is on display the day it ships.
- **The smooth nest ring** — the first client nest was stroked, dashed arcs;
  the Java hollow a `fillOval`; the ring blocks art-pixel-*sized* but placed
  off-lattice by continuous angles; the raised ring unshaded. Four checklist
  failures in one fixture — the checklist works, walk it.
- **The computed nest ring** — the second attempt rasterised a distance-tested
  ring of lattice blocks: on-grid, ramp-coloured, and still lumpy math.
  That is where "authored beats computed" (§5) comes from: procedural
  belongs to fields, discrete objects are drawn stamps.
- **The overlap seams** — the stamp's translucent hollow cells were sized
  `ceil(step)` on fractional spacing, so neighbours overlapped and the
  double-tinted overlaps read as grid lines. Rule: translucent cells tile
  **edge-exact** — each block's extent computed from its neighbour's rounded
  edge, never a rounded-up box.
- **The straw at one-in-five** — the first nest ring hash-gated its straw
  accent at ~20% of ring pixels, and the accent started reading as texture.
  Accents are rare or they are ramp colours (§2); the stamp keeps exactly two.
- **The wash canopy** — the web client's thicket concealment was a translucent
  green wash: three invented alpha tones blobbed over every cover tile, while
  the Java pass re-stamps the tile's *own baked pixels* through a clustered
  gap mask (invisible where nothing is beneath). Nobody noticed for months
  because the canopy had no catalog entry. Two rules came out of it: a state
  overlay re-composites the bake, never repaints it (§6) — and `/sprites` is
  the catalog of record, so a client visual without an entry drawn by its own
  live code path does not merge.

## 8. Conformance checklist

Before a new visual merges, ask:

1. Is every mark on the **art-pixel lattice** (world-absolute indexing)?
2. Do all colours come from an existing **ramp** (or a disciplined accent /
   signal colour)? No new in-betweens, no alpha washes?
3. Are gradients **dithered**, organic scatters **hashed**, plants on a
   **motif lattice**?
4. Does anything raised follow **north-lit/south-sunk**, and anything
   standing cast its **south drop shadow**?
5. Is translucency limited to the four sanctioned uses (§4)?
6. Is it a **pure function** of position/seed/state — no RNG, no wall-clock?
7. Does the web client's version (if any) agree with the Java bake on
   `/sprites`?

If the answer to any of these is "no", either the art changes or this
document does — silently diverging is the only wrong move.
