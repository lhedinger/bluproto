# Prototype_World — Architecture

A Java/Swing top-down, multi-level (2.5D) **agent simulator**. A procedurally
generated grid of tiles, spread across several elevation *levels*, is populated
by autonomous entities — soldiers, drones, civilians, alien lifeforms — that
perceive each other, navigate around walls and through doors, fight, flee, and
reproduce. There is no win condition; it is a sandbox you watch and poke at.

This document describes the *layers* the code is organised into and the
contracts between them. It deliberately avoids restating file lists, class
members, or diagrams — those are better read from the source.

## The layers

The design separates three concerns that are easy to conflate in a simulation:
*what an actor wants to do*, *whether the world physically permits it*, and
*how any of it appears on screen*. Reading the code, five layers fall out.

**Application / Input.** Owns the program lifecycle: the window, the game loop,
and the human. It bootstraps the world, translates keyboard/mouse and the
passage of time into calls on the layers below, and lets the user spawn entities
and move the camera. It holds no simulation rules of its own — it only decides
*when* things happen and relays *what the human asked for*.

**Presentation / Render.** Turns world state into pixels: camera and viewport,
world-to-screen projection (the slight 2.5D perspective between levels),
per-level tile compositing, the minimap, and an image-filter toolkit. It *reads*
the world every frame and must never mutate it.

**Domain.** The "business logic" — *what entities decide to do*. Every actor
derives from a common entity base; autonomous ones share an NPC brain (a
perception/targeting/movement/AI state machine), and concrete types differ
mainly in their decision logic and tuning. Projectiles, explosions, sounds,
weapons, and doors live here too. This layer expresses *intent*.

**Engine / Simulation.** The physical model and rulebook — *whether intent is
permitted*. It owns the world structure (levels, tiles, walls, doors, ramps),
the per-tick clock, collision and connectivity, pathfinding, and line-of-sight.
It knows nothing about *why* an entity acts, only the geometry and legality of
the world. This is the stable core.

**Resource / Asset.** Loads sprite sheets once at startup and pre-composites
tile sprites from neighbour-wall patterns, feeding finished images to the render
layer.

A small set of **cross-cutting helpers** (coordinate/angle math, image resizing,
RNG, profiling) is shared by all of the above.

## The contract that matters

The simulator's defining guarantee — entities don't walk through walls — is not
owned by any single layer. It emerges from a **domain↔engine collaboration**:

- The **domain proposes.** On each tick, an actor's decision step only sets a
  *desired* velocity. It expresses where it would like to go.
- The **engine disposes.** Before the move commits, the entity base asks the
  engine whether the destination is reachable — not solid, no shut door in the
  way, no illegal diagonal squeeze, the right handling for holes and ramps.
  Illegal moves are cancelled; legal ones are committed and tile occupancy is
  updated.

So "don't walk through walls" is a *protocol*, not a method: intent flows down,
a verdict comes back, and only the engine has final say over position. The same
shape governs perception (the domain asks "what can I see?"; the engine answers
with range- and field-of-view-limited line-of-sight) and navigation (the domain
asks for a path; the engine computes one over the tile graph).

## How a frame runs

Rendering and simulation run at different cadences. The presentation layer draws
as fast as it can, reading current world state each pass. The simulation
advances on a time gate — only after enough wall-clock time has elapsed does the
engine step every entity: decide, then resolve movement against the rules, then
retire the dead and admit anything newly spawned. Decoupling the two keeps the
display smooth while the simulation ticks at a steady rate.

## Levels, depth, and holes

The world is a stack of elevation *levels*, and the camera sits on exactly one
of them. That level is the integer `camZ` — there is no fractional camera
height, so **changing level is a hard cut, not a smooth zoom**: the camera
descends or climbs one whole level at a time and the view switches in a single
frame. (This is intended; smoothly interpolating between levels would require a
fractional-level projection the renderer deliberately doesn't have.)

Each frame, the camera's own level is drawn full, and levels *below* it are
composited underneath first (deeper = smaller index), each drawn through the
shared per-level projection: `Utils.scaleZ` scales a level's world-to-screen
mapping by its distance from the camera, so a lower level is slightly smaller
and shifts against the surface as the camera pans — the engine's built-in,
gentle **inter-level parallax**. Every level below the camera is also given a
translucent black **depth-fog** wash, so the deeper you look the dimmer it gets.

**Holes reuse this directly.** A hole tile bakes to nothing — it is a
see-through cut-out in its level — so the level below (already composited and
projected) simply shows through it and parallaxes for free via `scaleZ`, dimmed
by the same depth-fog. The pixel-ground pass only adds a light translucent pit
shade and a lit lip around the rim; it invents no separate substrate or parallax
of its own. On the bottom level a hole reveals nothing and reads as a dark pit.

## Dependency direction

Dependencies point downward and inward. Application sits on top and wires the
others together. Presentation reads the engine and consumes assets but never
changes simulation state. The domain is driven by the engine each tick and
depends on it for movement, perception, and pathfinding. The engine depends only
on assets (to render its tiles) and the shared helpers; it is the layer
everything else rests on.

A few seams are worth knowing, because the package layout doesn't perfectly
mirror the layers:

- The entity base class lives in the *engine* package even though it is the root
  of the domain — it is coupled tightly enough to movement resolution that it
  sits next to the model it queries.
- Doors are domain entities that write *back* into the engine's tile passability;
  they are the one actor that mutates the physical model.
- Some render logic lives inside engine classes rather than purely in the
  presentation layer, so the engine/presentation boundary is not airtight.
