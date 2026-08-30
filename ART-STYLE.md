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

World-absolute indexing is for **ground**. A pattern belonging to a body that
*moves* — the hazard checker on the drone's plates — is indexed **body-locally**
instead, or the world lattice shows through it and the pattern crawls across the
hull as the machine flies. The lattice still decides where marks land; only what
the pattern is a function of changes. A one-on-one-off checker survives the
derived headings unchanged *because the stamp grid is odd*: a quarter turn maps
`(r, c)` to `(N-1-c, r)`, which preserves the parity of `r + c` when `N` is odd
and inverts it when `N` is even. On an even grid the same checker flips every
quarter turn. Patterns with a longer period survive neither, and want checking
per facing.

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

A terrain that is a **meeting of two others** borrows both rather than
inventing a third look: rocky grassland draws its slabs from the stone ramp
and its grass from the grass ramp, so a slab there is the same rock as the
rock floor and a blade there is the same blade as the meadow. Only the grit
between them gets a ramp of its own, and that one is built to sit *between*
its two neighbours in hue and value. A transitional terrain with a wholly new
palette reads as a third biome; one built this way reads as a frontier.

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

**Promote it by deriving, never by picking.** An accent that becomes a body
colour needs the two shades it does not have; take them off the accent itself
by scaling, the way §4's raised grammar and `chargeDock`'s contact ring already
do (the drone's hull uses ×0.65 down and ×1.18 up), so the hue stays the
world's. The drone's hull is the facility's own hazard yellow
`0xd8b028` given a shadow and a highlight, which is why a machine painted in it
still reads as belonging to the same building as the stripes on its floor.
Choosing a fresh yellow would have read as a machine from somewhere else.

**One accent to a body.** Two warm accents on the same small glyph do not add
up, they compete — see "the second eye" in §7.

### Signal colours
Interactive state gets its own reserved family: button/plate red
(`0xE0455F`, dark `0x7c2434`, lit `0xF0788C`), indicator lamps (housing
`0x14161f`, dim `0x6a7280`, lit `0xD0ECFF`), hazard striping
(`0xd8b028` on `0x17171a`) for anything that drops or crushes.

## 3. Pattern grammars

These are the approved ways to build texture. New art composes these before
inventing new ones.

- **Ordered dither (Bayer 4×4)** — for *coverage and gradients*: the classic
  threshold matrix indexed by world-absolute art-pixel (`bayer(px, py)`).
  Period 4 divides the 12-px tile, so every tile is in phase. Used for shade
  ramps, shore deepening, rim dropouts.
- **Hash scatter** — for *organic placement*: `hash01(x, y, salt)` gates
  grains, sparks, tufts. Grains come in 2-px clusters, **never lone pixels**
  (`quietGround`). A smell uses hash stipple, not Bayer — the ordered matrix
  reads as mechanical, wrong for organic haze.
- **Motif lattice** — for *stamped plants*: a coarse cell grid where each
  cell hash-decides whether it grows a motif (tuft, clump, sprout, cap) and
  jitters its anchor. The vegetation sprites are built this way: per-tile,
  hash-scattered motifs whose count and reach step with the growth stage.
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
- **A face needs a drop to front.** The band is drawn where a mass stands
  *above* what is beside it, so a tile that arrives at the mass's own height
  gets none: an up ramp's head is where the climb lands, level with the rock,
  and a cliff drawn across it walls off the very tile the slope exists to
  reach. Its flanks are a different question and keep their faces — there the
  wall really does stand over ground below it.
- **Sunken things** (pits, shafts): a lit, *crumbling* north lip — a broken
  run of dashes with per-column depth, not a solid band — and thin dim edges
  with dithered dropouts elsewhere. A natural pit's **mouth takes the water
  treatment**: it ranks below everything in the autotiler, so the surrounding
  terrain overhangs it in scalloped, corner-rounded laps and a gorge's
  outline is a drawn shape, not a staircase of tile edges — ground breaks
  off over a drop, and nothing pours out of one. Shafts and catwalk gaps
  stay square: machine-cut openings are.
- **A pit is a hole, not a picture of one.** Inside the lip, a hole with a
  level under it is one flat veil of **translucent black at
  `RenderFx.holeDepth` opacity** with real alpha beneath (`Grid.pitFloor`,
  `Grid.veilPixel`) — §4's own sanctioned shadow, doing what a pit does to
  the floor a storey down: showing all of it, darkly. At the default 0.7 the
  floor below reads through at 30% brightness, whole. This replaced a hashed
  scatter of fully-open pixels among void ones, which showed the floor bright
  but only in specks — and specks read as noise ON the pit, not a view
  through it. A window is better dim than perforated: continuity is what
  makes the parallax slide legible. The alpha channel of a served chunk means
  exactly "you can see down here", so every other pass must cover its own
  tile completely. A pit with nothing under it — the lowest level's — is
  opaque void shade all the way, and reads bottomless because it is. Grate
  gaps and drop-shafts are small pits and take the identical treatment.
- **What shows through a hole moves with its own depth.** The client draws the
  level below scaled about the screen centre by `PARALLAX` (0.94), which is the
  projection of a plane one storey further from the eye: pan by *d* and the top
  layer moves *d* while the floor below moves 0.94*d*. That slide is the whole
  point of the transparency — it is what separates "there is a place down
  there" from "someone painted rock on the lid", and no amount of texture in a
  painted stand-in can produce it. Nothing dims the lower floor on the way up;
  the darkness a pit needs is already carried by the void-shade scatter around
  the openings.
- **Drop shadows**: one art-pixel south of the body, translucent black
  (~`alpha 110`), art-pixel-aligned blocks. Entities that stand (doors,
  shrubs, creatures) sit ON the ground because of this; nothing floats.
  A body that genuinely flies still casts — "nothing floats" means nothing is
  drawn without a shadow to sit against, not that nothing may leave the
  ground. It casts *further south* (the drone at eight art-pixels, clear of
  its own glyph), and the gap between body and shadow is the only cue that
  says the thing is airborne. Use the blocky oval below rather than a copy of
  the silhouette: a silhouette shadow fills the gaps that make a compact glyph
  legible, and pushed far enough to clear the body it stops reading as shadow
  and becomes a second dark object parked underneath.
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

- **Machines** are authored stamps rather than procedural bodies, and where
  they have a heading they need one stamp per facing. Author a cardinal and a
  diagonal and derive the other six by **90-degree lattice rotations**, which
  are lossless on a square grid; a 45-degree rotation is not, and rasterising
  one is the computed-ring mistake again. Rotate the **silhouette only** and
  apply the light afterwards, in world space — the sun does not turn with the
  body, and a rotated copy of a pre-lit sprite is lit from underneath for half
  the compass. A run one art-pixel tall is its own north and south edge at
  once, so it stays mid rather than lit.

  Draw the diagonal **to the cardinal**, and check it in the *body's* axes
  rather than the screen's — length along the heading, width across it. Nothing
  else catches a mismatch: both stamps can be on the lattice, on the ramp, lit
  from the north and in the catalog while the machine visibly changes size as it
  turns, because "consistent with the other stamp" is not otherwise a rule. The
  trap is drawing a diagonal as a staircase of fixed vertical thickness, which
  is the obvious way and is wrong: `T` rows of thickness buys only `T/√2`
  measured across the body, and the shortfall compounds with every step of
  length. Features on a diagonal match by **weight, not depth**, for the same
  reason — a rank measured along a diagonal crosses a wider slice of the body,
  so a tail cap cut to the cardinal's depth comes out half again as large and
  reads as a bite taken out of the machine.
- **Material and light compose.** A stamp with more than one material says what
  each cell is *made of*; the run it sits in says which way that cell *faces the
  sun*. Resolve the two together when the stamp is baked, rather than laying
  materials down and shading over them afterwards. Markings are the exception
  that proves the rule: a hazard stripe is paint *on* a surface, not a surface,
  so it takes no shade — the ground painters do not shade theirs either.
- **Direction is carried by mass, not by an accent.** A lamp reads as "there is
  a lamp" long before it reads as "and therefore that end is the front". Put an
  asymmetry in the *silhouette* — the drone's rearmost rank is chassis iron —
  and let the accent confirm the reading rather than carry it. A one-art-pixel
  protrusion is not an asymmetry: it is invisible at the size the thing is
  actually seen, and gone entirely at map zoom.
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
colours over invented ones, dither over alpha washes. The ground bake is a
pure function of tile type — one look per meaning, no live state frozen in —
and live state rides above it in the client's own layers: vegetation as the
five-stage sprite stamps, concealment by **re-compositing the bake's own
pixels** (the cover tile's pixels through the ported gap mask), so every
ground pixel shown is one the Java renderer authored.

**`/sprites` is the catalog of record for the web view.** Everything the
client draws into the world must have an entry there, rendered by the *same
code path* the live view runs (the exported painters and compositors —
`drawDoor`, `veilTile`, `ditherTile`, the atlas bakes), with the Java bake
beside it wherever a Java twin exists. A client visual with no catalog entry
is a review failure; a divergence visible on the page is a bug report.

**The two renderers cannot share a literal**, so nothing holds them together
except a test that says so. Assert one against the other — the scenario guarding
the drone reads the client's stamp strings and compares them to the Java
painter's — because "somebody remembered to edit both" is not a mechanism, and
the drone is the entity that proves it: for weeks the client drew a sentinel the
Java renderer had never heard of, and nothing said a word. Where the shading
rule exists in only one language, compare the authored silhouettes; silhouette
drift is the failure that actually happens.

Note too that `SnapshotRenderer` draws its **own diagnostic glyphs** — heading
arrows, carry links — rather than going through the painters, so a scenario shot
is not a picture of entity art and never was. `EntityShot` is the tool that is:
it builds a real `World` and `View` and calls `renderWorld`, the same entry
point the live application uses, so it exercises the painter **dispatch** as
well as the painter — which matters, because the dispatch is where the drone was
missing, and a harness calling the painter directly would have drawn a perfect
drone while the renderer drew none.

```
EntityShot out.png --focus StewardDrone --sweep --ticks 3000 --span 12000
```

`--sweep` keeps one frame per heading the body is actually seen in. It reports
how many of the eight it got and never invents the rest: a drone that spends the
first few thousand ticks berthed returns a strip of one, which is the truth
about that run rather than a gap in the art.

## 7. Case law — how these rules were learned

Every rule above that reads like an opinion was paid for by a visual bug.
The precedents, so nobody pays twice:

- **The depletion wash** — grazing depletion was first drawn as a translucent
  brown alpha wash in 3×3-art-pixel blocks, Bayer-indexed per tile. Three
  violations in one: invented in-between colours, cells coarser than the art
  grid, and a dither out of phase with the bake's own. The lasting lesson:
  *a state is drawn art, not a tint* — the fix baked a fully-grazed twin and
  dithered between the two bakes per art-pixel; today the same principle is
  served by the five-stage vegetation sprites stamped over a ground bake that
  carries no vegetation at all. Either way, every pixel shown is one a
  renderer authored, never a wash.
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

- **The rotated sentinel** — the drone's first glyph was polygons rotated by
  `ctx.rotate()` in three invented greys, with an anti-aliased `arc` for its
  eye, no shading at all, sized from the body radius rather than the tile
  grid, no Java twin, and no catalog entry. Six checklist failures, and it
  passed CI and shipped, because not one of them is mechanically checkable.
  Two lessons, and the second is the load-bearing one. Scenarios asserting
  over the resolved stamp data — every cell a sanctioned mark, one accent, all
  headings lit from the north — put the checklist on the gate cheaply, and the
  first one written found a case nobody had considered. And the real cause was
  never the code: the guide simply was not read before the art was drawn, and
  a glyph iterated to "looks good" against screenshots will satisfy an eye
  while breaking every rule on this page.
- **Judging one draft at a time** — three sentinel silhouettes were drawn,
  screenshotted and rejected in sequence before six candidates on a single
  sheet settled the shape in one pass. Pixel art cannot be judged by reading
  it, and it can barely be judged in isolation: when a shape is not working,
  the next move is a comparison sheet, not another guess. Put every heading, a
  zoom ladder, and two different grounds on it — most failures are invisible
  at one size on one background.
- **The seam that took three tries** — a ramp is a cut in the rock, and made
  it look like one three separate times before it was. A wall is drawn by
  **two systems that must agree**: its MASS comes from the tile sprite chosen
  by the tilecode (`isConnectedStatic` → `ProcTiles.buildWall`), and its
  LIGHTING — face band, cornice, base shadow — is decided independently in the
  ground pixel pass (`isWallish` → `wallDepth`). Teaching only the first that
  a ramp is rock left the second still drawing a cliff across the ramp's head,
  and the screen did not move. The rules that fell out:
  *(a)* when two tiles are one mass, **both** must say so — a boundary drawn
  from either side is a boundary, and one drawn from both is a doubled seam;
  *(b)* a query like `isWallish(nx, ny)` that answers for a neighbour **alone**
  cannot express "this counts as rock *to you*" — anything gating on a
  neighbour's material has to know who is asking; and *(c)* the tests written
  along the way passed every time while the picture stayed wrong, because each
  pinned the path already thought of. **A render fix is not verified until the
  render is looked at** — bake the actual scene and compare it side by side.
  (An image diff will not do it either: the film grain moves a third of every
  tile in the map between any two renders, so the diff reports everything
  changed. Compare the picture, or assert on the data the art is chosen by.)
- **The stretching diagonal** — the drone's cardinal and diagonal stamps were
  each drawn until they looked right on their own, and never against each other.
  Measured in the body's own axes the cardinal hull ran 10 art-pixels by 5 and
  the diagonal 13.7 by 3.8, so turning forty-five degrees made the machine a
  third longer and a quarter thinner, then swelled it back on the next bucket.
  Every existing rule passed: both were on the lattice, drawn from the ramp, lit
  from the north, catalogued. What was missing was any rule saying the facings
  must agree with *each other*, and the fix was to measure — the numbers were
  obvious the moment anyone computed them, and invisible for as long as nobody
  did. Where a shape exists in more than one orientation, the comparison is the
  test.
- **The second eye** — the drone's tail was given a warm vent ember to sell it
  as running machinery. It was the best-sounding idea of that session and the
  worst on screen: against the red lamp at the head, an orange pixel at the tail
  is indistinguishable at a glance, so the body read as having two eyes and
  stopped saying which way it faced. An accent that duplicates another accent's
  job does not add detail, it destroys the reading the first one was carrying.
  Hence "one accent to a body" (§2) — and the general form, that a second cue
  for something already cued is a cue competing, not reinforcing.
- **The threshold that fought geometry** — a scenario written to pin the
  stretching diagonal asserted the two hulls were within ten lattice cells of
  each other. It failed on art that was correct, so it was loosened to a ratio;
  the ratio then failed on the *loader*, in the opposite direction, and the
  second failure is the instructive one. **Cell count is not a cross-orientation
  measure of shape at all.** Rasterised at forty-five degrees the same form can
  gain cells or lose them depending only on how its edges fall on the lattice —
  measured here, half-extents 5.0×1.5 come out at 1.12 of the axis-aligned
  count, 5.0×2.5 at 0.96, 4.0×4.0 at 0.75 and 6.0×1.0 at 0.64. Not even
  monotonic. The first fix reasoned from one shape and wrote the conclusion down
  as though it were general, which is the same mistake as the original threshold
  wearing a better disguise.
  What survives is: compare **extents**, in the body's own axes, and test
  hollowness *directly* — assert the hull encloses no empty cell — rather than
  inferring it from an area that the lattice is free to change. Three rules out
  of it. **A threshold you chose yourself is a hypothesis**: when correct art
  fails it, fix the premise, do not bend the art. **A fix derived from one
  example is also a hypothesis** — the loosened ratio was as invented as the
  number it replaced. And **run a new assertion against the OLD art before
  trusting it**: an assertion that has never failed has not been shown to be
  capable of failing.

- **The pits that were holes in the picture** — pit interiors were drawn by
  *skipping* the see-through art-pixels: leave them unpainted and the level
  below, composited underneath, shows through with its parallax. True in the
  desktop renderer. False in the one thing anyone looks at — the served chunks
  are **one opaque level each**, so every skipped pixel stayed the black the
  image was cleared to. Measured on the surface bake: 55% of every pit, plus a
  third of every catwalk grate, was pure `#000000`, a colour that is in no
  ramp. Two rules: *(a)* **a render trick that depends on what is underneath
  is a bet on the compositor**, and there are two compositors here (§6) — the
  bake can only draw what it can look up itself, so a pit reads the tile one
  level down and paints it; and *(b)* when the fix was first written as a
  Bayer threshold it laid a **regular halftone across the hole** that read as
  a wire mesh, and rewriting it as an alpha blend traded that for invented
  in-between colours (§2). The scatter is neither a gradient nor a wash — it
  is broken sight of something far away, and the grammar for that is a
  **hash** (§3). Reaching for the wrong one of the three is the whole bug.
  *Coda*: painting the floor below into those pixels was the right fix for a
  format that could not do better, and the wrong end state. The material was
  correct and the place was not — the same rock sat in the same spot however
  the camera moved, which is a texture of a pit rather than a view down one.
  Giving the chunks an alpha channel let the client put the **real** level
  under the hole and move it with its own parallax, and the whole illusion
  arrived with it. **When a stand-in is convincing but static, ask what it is
  standing in for**: the answer is usually cheaper than it looks, because the
  thing itself was already rendered somewhere.
- **The bake that was tested on the wrong renderer** — the test written to pin
  all of the above baked its levels through `renderLevelImage`, and passed. It
  was asserting on the DESKTOP renderer's layers, which composite every level
  into one picture, so a pit there is filled by the floor below and can never
  be see-through; what the server actually slices and serves is
  `bakeLevelImage`. The test only failed once the pits were genuinely
  transparent — because the path it watched had never had them. §6 says the
  Java renderer is the source of truth, and that is still true, but **"the Java
  renderer" is not one function**: check which entry point the server calls
  before asserting on it, or the test pins a picture nobody is shown. Exactly
  the bug it was written to catch, one level up.
- **Grass from the wrong level, the second time** — the caves wore the meadow's
  grass again, years of comments later, and nothing about the art was wrong:
  the server's grid for the deep level was 311 fungus tiles and no grass, the
  bake had none, and a fresh client drew none. It took **cycling** levels to
  show. The GPU layer cache re-uploads a texture only when its *revision*
  changes, but the vegetation layer rebuilds its chunk canvases and restarts
  their revisions on every level change — so chunk `veg:37` was revision 1 on
  the surface and revision 1 again underground, and the GPU kept showing the
  meadow. The rule: **a cache keyed on a number the caller manages is only as
  correct as the caller's discipline**, and discipline is invisible in a
  screenshot. The fix moved the invariant inside the cache — it now compares
  the source object's identity as well, so "a different canvas that happens to
  reuse the key" can never be mistaken for "same canvas, unchanged". Note also
  what made this findable: the sprite layer and the bake were separated by
  measurement (turning the layer off changed nothing), which ruled out three
  quarters of the code before any of it was read.
- **The belt that could only point two ways** — a conveyor's art asked whether
  the tiles north and south were also belts, and drew along whichever axis
  answered. That reads like the autotiling the rails and the pipe gallery use,
  and it is not: a **track is the same run travelled either way**, so its shape
  really is a fact about its neighbours, but a **belt has a near end and a far
  end**, and no arrangement of surrounding tiles can say which is which. The
  neighbour test could name two axes; a belt needs four directions. So every
  belt laid along a row pointed west and every belt down a column pointed
  north — not as a decision, but because that is the way the arithmetic fell
  out, and nobody could have turned one around. The rule: **autotile what the
  neighbours genuinely determine; store what they cannot.** A shape is
  neighbours' business, a heading is the builder's, and the tile carries it —
  the same reason `rampUphill` is stored rather than guessed. Two tells that a
  guess is standing in for a fact: the catalog can only show half the cases
  (`/tiles` listed "east-west" and "north-south" because those were the only
  two pictures that existed), and no caller anywhere can express the other
  half. Note the near miss in the diagnosis, too — reaching for the rail's
  four-bit mask because the belt "looked like" a rail. The mask would have
  bought elbows and tees for a machine whose actual defect was that it could
  not say which way it ran.
- **The sky that baked the world into itself** — a level of open air above the
  ground came out a near-black sheet. Nothing was wrong with the air: the tiles
  were `VOID`, and `VOID` is drawn by drawing nothing, which is exactly the
  distinction it carries. **A pit is an opening cut in a floor, so it has a rim
  and a shaded throat; open air was never a floor, so it has no edge of its
  own** — what bounds it is the spire standing in it. What was wrong was
  underneath. `World.render` composites the WHOLE stack, every floor drawn
  bottom-up with a 59% black scrim after each one below the camera, because
  that is what the desktop view is. The served chunks are not that: a chunk is
  one level, its alpha means "you can see down here" and nothing else, and the
  client draws the floor below at its own parallax. Baking the composite in
  handed the client a second, unparallaxed copy to draw its own copy over.
  That survived for as long as every level's own art was opaque — the stack was
  painted over, and showed only through pits, where the pit's own 70% veil hid
  it. Open air is what made it visible: with almost nothing painted over, three
  scrims came through at `1-(1-150/255)³` = **alpha 237**. The rule: **when a
  level is a layer in a composite somewhere else, bake it alone.** And note
  what let it ship — the bake test asked for `alpha < 255`, which 237 satisfies.
  A test for "see-through" must ask for *actually* see-through; "not fully
  opaque" is a different and much weaker claim, and a 93% black sheet meets it.

## 8. Conformance checklist

Before a new visual merges, ask:

1. Is every mark on the **art-pixel lattice** — indexed world-absolutely for
   ground, body-locally for a pattern that travels with a moving body (§1)?
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
8. If it touches how tiles meet, does **every** system that draws that
   boundary agree — the tile sprite's autotiling *and* the ground pass's
   lighting (§7, "the seam that took three tries")?
9. Has the **actual scene been baked and looked at**, before and after? A
   green suite is not evidence a render changed.
10. If it exists in more than one orientation or facing, do those agree with
   **each other** — same size, same proportions, measured in the body's axes
   (§5, §7 "the stretching diagonal")?
11. Does it **cover its own tile completely**? A served chunk's alpha means
    "you can see down here" and nothing else, so an unpainted pixel anywhere
    else is a window onto the wrong floor (§4, §7 "the pits that were holes").
12. If it is verified by a bake, is that the **entry point the server calls**?
    `bakeLevelImage` is served; `renderLevelImage` builds the desktop
    renderer's composited layers (§7, "the bake that was tested on the wrong
    renderer").
13. If it has a **facing**, does the tile *carry* that facing rather than the
    painter inferring it from the neighbours? Autotile what the neighbours
    genuinely determine — a run's shape — and store what they cannot: which
    way it runs (§7, "the belt that could only point two ways").
14. If it is an **absence**, is it drawn by drawing nothing — alpha 0, no rim,
    no veil? A pit is an opening cut in a floor and gets an edge; open air was
    never a floor and gets none. And nothing else may bake in behind it: a
    served chunk is one level (§7, "the sky that baked the world into itself").

If the answer to any of these is "no", either the art changes or this
document does — silently diverging is the only wrong move.
