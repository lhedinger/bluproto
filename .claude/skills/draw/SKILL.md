---
name: draw
description: Author or change anything drawn into this world — a tile texture, an entity glyph, a furniture sprite, a state overlay, a shadow — in either renderer. Use whenever the task is to draw, redraw, restyle, or "make X look like Y", to add a new tile or creature or item art, to fix something that looks wrong on screen, or to check whether a visual conforms to the design system. Also use when reviewing a diff that touches GroundTextures, Grid, the render package, or the client's render.ts drawing functions.
---

# Drawing in bluproto

This world has a written design system, `ART-STYLE.md`, and it is enforced
socially rather than mechanically. Nothing here is on `./gradlew check`. A glyph
can be off-lattice, off-palette, unshaded, drawn with anti-aliased curves, and
absent from the catalog, and CI will pass it without a word. That has happened,
more than once, and it is the reason this skill exists.

## Read the guide before you open the file

`ART-STYLE.md` first, every time, **before** looking at the code you are about
to change and before writing a line of art. Not after, not "if it looks
complicated".

This is the whole skill in one instruction, and it is here because the failure
mode is so ordinary: you picture the thing, you write a `drawX` function, you
screenshot it, you iterate until it looks good, and you ship a glyph that
satisfies your eye and violates six written rules. Looking right is not the
standard. The guide is the standard, and it is 226 lines — reading it costs
less than one wrong iteration.

The guide's own closing line is the escape hatch and it is a real one: *either
the art changes or this document does — silently diverging is the only wrong
move.* If a rule genuinely does not fit what you are drawing, say so and amend
the document in the same branch. What you may not do is quietly not follow it.

## The rules that get broken

The full checklist is §8 of the guide. These are the ones that have actually
been violated in this repo, with what it costs:

**Marks land on the art-pixel lattice.** 12 per tile, indexed world-absolute.
`ctx.rotate()` and float polygon coordinates put every mark off it. So does
sizing a glyph from an entity's radius instead of the tile grid — do that and
it drifts off-lattice at every zoom but one. This is the *smooth nest ring* and
it has now been paid for twice.

**Colours come from an existing ramp.** Before you write a hex literal, grep for
it:

```bash
grep -ric "515862" --include=*.java --include=*.ts . | grep -v node_modules | grep -v ':0'
```

One hit means you invented it. Machinery borrows `Door`'s steel and iron;
ground borrows `GroundTextures.RAMP`; a terrain between two others borrows both.
For a sunk south edge use the base at ×0.65, not the darkest colour you can
find — near-black against a mid-tone deck reads as a hole punched in the object
rather than an edge turned away from the light.

**No smooth curves, ever.** `arc`, `fillOval`, anti-aliased strokes are named in
§4 as never. A round thing is a drawn stamp of blocks.

**Authored beats computed.** Discrete objects are hand-drawn string-array
stamps. Procedural belongs to *fields* — ground, textures, patterns. A ring
rasterised by a distance test is on-lattice, ramp-coloured, and still reads as
lumpy math. Both nest attempts failed here.

**Raised things are lit north and sunk south**, and standing things cast a
shadow. See "Shadows" below for the part that is not obvious.

**Translucency is limited to the four sanctioned uses**, and the second of them
— the blocky translucent oval — is the right tool far more often than people
reach for it.

## Both renderers, and the catalog

The Java renderer is the visual source of truth. The client repaints some things
live in a simpler idiom, but the two must agree.

- If the thing appears in the world, it needs a **Java painter**. Check the
  dispatch actually reaches it: `NpcPainter` branches on `getGenome() != null`,
  so anything without a genome silently falls through to the legacy sprite. The
  drone sat in that hole for weeks while the client drew a sentinel Java had
  never heard of.
- The client twin stamps the **same authored data through the same rule**. Share
  the silhouette strings verbatim between the two files; if they are transcribed
  rather than copied they will drift.
- It needs an entry in **`/sprites`** (`client/src/help.ts`), drawn by the live
  code path. §6 makes this a merge gate — *"a client visual with no catalog
  entry is a review failure"* — and the wash canopy hid for months precisely
  because it had none. Prefer entries that show something the source cannot: a
  zoom ladder, all headings at once, a state cycling.

## Look at it before you commit

You cannot judge pixel art by reading it.

For anything the **Java** renderer draws, use `EntityShot` — it builds a real
world and calls `renderWorld`, the same entry point the live app uses, so it
shows the art through the real dispatch rather than through the painter you
hoped was being called:

```bash
./gradlew compileJava -q
java -cp engine/build/classes/java/main net.hedinger.prototype.simtest.EntityShot \
  out.png --focus StewardDrone --sweep --ticks 3000 --span 12000 --cell 3
```

`--sweep` collects one frame per heading the body is actually seen in and tells
you how many of the eight it managed. A short strip is information, not a bug:
an entity that stays parked is only ever drawn one way.

For client-side glyphs, extract the painter into a scratch page and screenshot
it:

```bash
# strip the TS types out of the drawing functions into a plain <script>,
# paint a sheet, then:
/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell \
  --headless --disable-gpu --no-sandbox --hide-scrollbars \
  --screenshot=out.png --window-size=1200,320 file:///abs/path/sheet.html
```

Then `Read` the PNG. Watch the console line in the output — a stray `:` from a
type annotation gives you a blank canvas and a silent lie.

Put on every sheet: **all headings or states**, a **zoom ladder** (16→120px),
and the glyph over **at least two different grounds**. Most failures are
invisible at one size on one background.

**Compare variants side by side, do not iterate one at a time.** Six candidates
in one screenshot settles in a single pass what six sequential attempts will
not settle at all. Three drafts of the drone were rejected one at a time before
a six-way sheet made the answer obvious in seconds. When a shape is not working,
your next move is a comparison sheet, not another guess.

## Pin what CI cannot see

Add scenarios to `SimTests` asserting the properties you just spent effort
getting right. They are cheap, they run on the gate, and they are the only
mechanical check this art will ever have. Assert over the *resolved stamp data*,
not over pixels:

- every cell is one of the sanctioned marks — nothing continuous crept in
- accents appear exactly as often as the guide allows (rare, or it is a ramp
  colour now)
- every state/heading is lit from the north
- the proportion that defines the shape (the drone's "wider across than long")
- distinct states are actually distinct

Write these *before* you are confident. `EveryHeadingIsLitFromTheNorth` failed
on its first run and found a real case — a one-art-pixel run is its own north
and south edge at once — that had not been considered.

## Two traps worth naming

**Rotation carries the light around with it.** If you bake a lit sprite and
rotate it per heading, the sun rotates too and half the compass is lit from
underneath. Store the **silhouette only**, rotate that, and apply the light at
the end in world space. Rotations must be exact 90° lattice turns, which are
lossless; a 45° rotation is not, and rasterising one is the lumpy math §5
forbids — author a second diagonal stamp instead.

**A shadow is not a copy of the body.** For a compact glyph a silhouette-shaped
shadow fails two ways: offset slightly it fills the gaps that make the parts
read as separate, and offset far enough to clear the glyph it stops looking like
shadow and becomes a second dark object parked underneath. Use the blocky
translucent oval (sanctioned translucency 2). It reads as ground because that is
what a shadow on ground looks like from above, and it needs no rotation — the
body turns, the light does not.

## Committing

Follow the `ship` skill for landing. One thing specific to art: **presentation
is its own concern**. How a thing is drawn and what it does are separate
commits, and the Java painter, the client twin, and the catalog entry are
separate again — each is independently revertible and each stands alone.

Record what you *rejected* in the commit body and in the code comment above the
stamp. "The plates were as long as the hull and it read as a stack of planks" is
the single most useful sentence for whoever redraws it next, and it is unknowable
from the final art.
