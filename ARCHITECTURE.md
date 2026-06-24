# Prototype_World — Architecture & Layer Documentation

> A Java/Swing top-down, multi-level (2.5D) agent simulator. The world is a
> procedurally generated grid of tiles spread across several elevation
> *levels*. Autonomous entities — soldiers, drones, civilians, alien
> lifeforms — perceive each other, navigate around walls and through doors,
> fight, flee, and reproduce. The application is a sandbox: there is no win
> condition, you watch (and poke at) an ecosystem run.

- **Entry point:** `net.hedinger.prototype.main.PrototypeWorld#main`
- **Build:** Eclipse project (`.classpath` / `.project`), plain Java, no external
  dependencies. Source under `src/`, sprite assets under `res/`.
- **Window title / version:** `Prototype_World 2.0`

---

## 1. The big picture

The codebase is small (~40 classes) but cleanly separates *what an entity
decides to do* from *whether the world physically allows it* and from *how any
of it is drawn*. That separation is exactly the layering the project is built
around. Reading the code, five layers fall out:

```
┌─────────────────────────────────────────────────────────────────────┐
│  APPLICATION / INPUT LAYER          net.hedinger.prototype.main       │
│  PrototypeWorld:  main(), JFrame, game loop, mouse + keyboard input,  │
│                   entity spawning, camera control                     │
└───────────────┬───────────────────────────────────┬──────────────────┘
                │ drives the loop                    │ feeds input
                ▼                                     ▼
┌───────────────────────────────┐   ┌──────────────────────────────────┐
│  PRESENTATION / RENDER LAYER   │   │  DOMAIN / "BUSINESS" LAYER        │
│  engine: View, LayerRenderer,  │   │  engine.Entity (base) +           │
│          Minimap               │   │  entities.* (NPC, Weapon, Bullet, │
│  engine.render: Gaussian/      │   │  Grenade, Explosion, Door, Sound) │
│          Convolve filters,     │   │  entities.npcs.* / .weapons.*     │
│          PixelUtils            │   │                                   │
│  → "how things look"           │   │  → "what entities decide to do":  │
│                                │   │     AI, perception, combat,       │
│                                │   │     reproduction, lifecycle       │
└───────────────┬────────────────┘   └───────────────┬───────────────────┘
                │ reads world state                   │ asks "can I move here?",
                │ to draw                             │ "what can I see?"
                ▼                                     ▼
┌───────────────────────────────────────────────────────────────────────┐
│  ENGINE / SIMULATION LAYER          net.hedinger.prototype.engine        │
│  World, Grid, Tile, Sector  +  WorldGenerator                            │
│  → the physical model & rules: tiles, walls, doors, ramps, levels,       │
│    collision (don't walk through walls), connectivity, pathfinding,      │
│    line-of-sight, spatial queries, the per-tick simulation clock         │
└───────────────────────────────────────┬─────────────────────────────────┘
                                         │ loads sprites / composited layers
                                         ▼
┌───────────────────────────────────────────────────────────────────────┐
│  RESOURCE / ASSET LAYER             engine.ResourceManager  +  res/       │
│  sprite sheets, tile compositing, NPC/door/overlay images                │
└───────────────────────────────────────────────────────────────────────┘

   CROSS-CUTTING:  engine.Utils (coordinate math, image resize, RNG, angles),
                   engine.StopWatch (profiling),  engine.MapLayer (cached layer image)
```

A useful mental model:

- The **Domain layer** answers *"what do I want to do?"* (chase that human,
  fire, flee, lay eggs).
- The **Engine layer** answers *"is that physically allowed?"* (no, a wall is
  there; yes, but the door is shut; you'd fall through that hole).
- The **Presentation layer** answers *"what does it look like on screen?"*
- The **Application layer** answers *"when does any of this happen, and what is
  the human watching/clicking?"*

---

## 2. Source map

```
src/net/hedinger/prototype/
├── main/
│   └── PrototypeWorld.java          Application: main(), window, game loop, input
├── engine/
│   ├── World.java                   Simulation root: entities, levels, queries, tick
│   ├── Grid.java                    One elevation level: tiles + doors + per-level queries
│   ├── Tile.java                    One cell: type, walls, doors, ramps, occupancy
│   ├── Sector.java                  Named connected region (navigation/generation hint)
│   ├── WorldGenerator.java          Procedural map generation
│   ├── Entity.java                  Domain base class (lives here for engine coupling)
│   ├── View.java                    Presentation: camera, viewport, projection, dispatch
│   ├── LayerRenderer.java           Presentation: composites tiles into per-level images
│   ├── Minimap.java                 Presentation: scaled overview + viewport frame
│   ├── MapLayer.java                Cached composited layer image (+ LOD downscales)
│   ├── ResourceManager.java         Asset loading & tile sprite compositing
│   ├── Utils.java                   Cross-cutting math/coordinate/image helpers
│   ├── StopWatch.java               Cross-cutting profiling
│   └── render/
│       ├── AbstractBufferedImageOp.java   Fast raster pixel get/set base
│       ├── ConvolveFilter.java            Separable convolution
│       ├── GaussianFilter.java            Gaussian blur (kernel gen)
│       └── PixelUtils.java                Pixel clamp / interpolate / blend modes
└── entities/                        Domain: concrete entity behaviours
    ├── NPC.java                     Abstract agent: AI, LOS, pathfinding, targeting
    ├── Bullet.java  Grenade.java  Explosion.java  Sound.java  Door.java
    ├── Weapon.java                  Abstract weapon
    ├── npcs/    Drone, DummyChaser, DummyRoamer, Elite, Headcrab,
    │            HeadcrabZombie, Houndeye, Human, Sentry, Soldier, Spore, Zombie
    └── weapons/ Gattlingun, MGL, Machinegun, Rifle, Shotgun

res/
├── tiles/      tilemap64.png, walls.png, floors.png, door sprites
├── npcs/       dot sprites (friendly / neutral / hostile) at 32 & 64 px
├── doors/      open / closed / offline door sprites
└── overlays/   status icons, scanline effects
```

> **Note on package vs. layer.** `Entity` physically lives in the `engine`
> package, not `entities`, because it is tightly coupled to `World`/`Tile` for
> movement. Conceptually it is the *root of the domain layer* — its concrete
> subclasses (the interesting behaviour) all live in `entities.*`. This is the
> one place where the package boundary and the layer boundary disagree.

---

## 3. Layer-by-layer

### 3.1 Application / Input Layer — `main.PrototypeWorld`

A single `JPanel`/`JFrame` class that owns the program lifecycle. It is the only
class that knows about Swing windows, the clock, and the human.

Responsibilities:

- **Bootstrap** (`main` → constructor → `initialize`): parse `cols=`/`rows=`/`lvls=`
  args, load resources, run the `WorldGenerator`, build the `LayerRenderer` and
  `View`, then spawn an initial population per level (`spawnASet` / `spawnEntities`
  / `spawnType`).
- **The game loop** lives in `paint(Graphics)`, which is self-perpetuating via
  `repaint()`. Each frame it:
  1. measures elapsed time (`gamma` accumulator),
  2. calls `view.think(...)` to apply pending camera moves and update the mouse's
     world coordinates,
  3. calls `view.render(g)` + `view.renderFPS(...)` to draw,
  4. **once enough wall-clock time has accrued (`gamma > 30` ms)** calls
     `world.think()` — i.e. the *simulation* steps on a time-gated cadence while
     *rendering* runs as fast as it can. Framerate is computed and, above 200 FPS,
     the loop sleeps 5 ms to avoid spinning.
- **Input** via inner `keyListener` / `mouseListener` / `mouseMotionListener`:
  - WASD / arrows → pan camera (`camDX/DY`); PageUp/PageDown → change level (`camDZ`).
  - `V` → `view.cycleViewMode()`; `R` → regenerate the world (`initialize`).
  - Number keys `0–9` → arm an "entity brush"; left-click then spawns that entity
    type at the moused tile via `spawnType(view.mouseRow, view.mouseCol, view.mouseZ, ...)`.

This layer is deliberately thin: it translates human intent and the passage of
time into calls on `World` and `View`.

### 3.2 Engine / Simulation Layer — `engine.{World, Grid, Tile, Sector, WorldGenerator}`

This is the physical model and rulebook. It knows nothing about *why* an entity
moves, only the geometry and legality of the world. **This is the layer that
keeps entities from walking through walls.**

**`World`** — the simulation root.
- Holds `Grid[] levels` (one per elevation), a `HashMap<Integer,Entity> entities`
  keyed by ID, and a spawn queue.
- `think()` is the **per-tick step**: runs every entity's `run()`, removes the
  dead, drains the spawn queue, and ticks each `Grid` (which ticks its doors).
- **Coordinate system & hashing:** `hashCode(c,r,l) = c + r*cols + l*rows*cols`
  gives every tile a unique integer — the currency of pathfinding and sectors.
- **Collision / movement legality:**
  - `isOpen(x,y,z)` — is the tile walkable (not solid)?
  - `isConnectedSpace(x,y,z, x2,y2,z2)` — may an entity cross *from* one tile *to*
    an adjacent one? Respects walls, **door state**, ramps, and diagonal squeeze
    rules. This is the call `Entity.isColliding()` makes every frame.
  - `findPath(...)` — A* over tile hashes, neighbours from `Tile.calcConnected()`,
    Euclidean heuristic; returns a `Stack<Integer>` of tile hashes.
- **Perception:** `hasLOS(...)` ray-traces between tiles with range + field-of-view
  culling; `searchEntity/searchNPC*` return distance-sorted `TreeMap`s of what an
  observer can see; `getRadialEntities(...)` does a radius query.

**`Grid`** — one elevation level: a `Tile[][]`, the level's `doors`, and named
`Sector`s. Ticks doors, renders its composited layer + entities at the right
depth, and delegates LOS ray-tracing (`hasLOS`/`isLosConnected`) and entity
searches scoped to the level. `setTile` forces the border to walls.

**`Tile`** — a single cell and the heart of the "no walking through walls" rule.
- `TileType ∈ {HOLE, FLOOR, WALL, RAMPUP, RAMPDOWN}` (each flagged open/solid).
- Per-direction doors `door_N/E/S/W` (closed = blocks).
- `isConnected(world, ..., diagonal, floorsOnly)` is the core legality check:
  same-level-or-valid-ramp, target not solid, the door facing the movement
  direction is open, and diagonals require *both* flanking cardinals to be
  walkable (no corner-cutting). `calcConnected(...)` enumerates legal neighbours
  for pathfinding. `tilecode` precomputes the neighbour-wall pattern so the
  renderer can pick the right sprite.

**`Sector`** — a named set of connected tile hashes with a cached center; produced
by generation, used as spatial/navigation regions.

**`WorldGenerator`** — procedural content. `run()` grows ~10 sectors per level by
recursive spreading, carves junctions to connect them, runs `cleanup()` (kills
isolated tiles/lone diagonals/holes-over-walls), places ramps to link levels, and
spawns `Door` entities at floor/wall boundaries. (Could be seen as its own
*content-generation* sub-layer; it depends on the engine model and produces it.)

### 3.3 Domain / "Business" Layer — `engine.Entity` + `entities.*`

Where the simulation's *behaviour* lives — how each kind of thing in the world
decides what to do. Every actor extends `Entity`; the autonomous ones extend `NPC`.

**`Entity`** (abstract base) defines the universal lifecycle and the contract with
the engine:
- State: position `X,Y,Z`, direction `D`, velocity `dX,dY,dZ`, `size`, `health`,
  `age`, `lifespan`, `deathspan`, attachment (one entity carried by another),
  `lastHeardSound`.
- **`run()` (final) is the per-tick template method:** age the entity, kill it if
  health ≤ 0 or it outlived its `lifespan`, then — only if the world position is
  valid — call the subclass `think()`, then `collisionCheck()`, then
  `executeMovement()`.
- **`executeMovement()` is where domain intent meets engine rules.** A subclass'
  `think()` only *sets desired velocity*; this method then asks the engine and
  corrects:
  - over a `HOLE` and not flying → fall (`dZ=-1`);
  - inside a wall → pop up a level (`dZ=1`);
  - `isColliding()` (i.e. `!world.isConnectedSpace(here, here+delta)`) → cancel the
    move. Then it updates per-tile occupancy and commits `X+=dX` etc.

  So the famous "don't walk through walls" guarantee is a *collaboration*: the
  domain proposes (`dX,dY` in `think`), the engine disposes
  (`World.isConnectedSpace` / `Tile.isConnected`), and `executeMovement` enforces
  the verdict.
- `abstract think()` / `abstract draw()` are the two extension points subclasses
  fill in.

**`NPC`** (abstract, in `entities`) is the AI brain shared by all agents:
- **State machine:** `STATUS_SLEEP / IDLE / ALERT / THREAT`; perception params
  `LOS_RANGE`, `LOS_FOV`, `SEARCH_FREQ`; faction `hostile ∈ {friendly, neutral,
  hostile}`; optional `flying`; distance-sorted `targets`/`focusTargets`.
- **Movement primitives** (set velocity for `executeMovement` to validate):
  `move`, `chase`, `roam`, `follow`, `flee`, `turn`, `backup`, plus
  `generatePath`/`followPath` for A* navigation.
- **Perception & targeting:** `scanTargets` (via `World.searchNPC3`), `getTargets`,
  `seeTarget`, `isInLOS`, `lockTarget`, `getClosestNPC`, `validTarget`,
  `killedTarget`, `lostTarget`. Type filtering is string-based
  (`"Entity.NPC.Type"` + include/blacklist arrays) so predator/prey and alliances
  stay loosely coupled.
- **Interaction:** `grab`/`drop` (carry smaller entities), `eat`, `say` (floating
  text), `mark`/`unmark` (friendly-detection for the fog rule),
  spring-repulsion `collisionCheck`.

**Concrete NPCs** (`entities.npcs.*`) differ only in their `think()` and tuning —
a compact catalogue of behaviours:

| NPC | Faction | Niche / behaviour |
|---|---|---|
| `Soldier` | hostile | squad combatant; chase/back-up to keep range, fires weapon |
| `Elite` | hostile | tougher soldier, wider LOS (180°), more aggressive |
| `Sentry` | hostile | immobile turret, scans + fires; can malfunction |
| `Drone` | hostile | flying, 360° LOS, patrols, abducts (`grab`) humans |
| `Human` | neutral/friendly | civilian; flees toward soldiers, panics, emits `Sound` |
| `Houndeye` | hostile | ecosystem omnivore: hunger/metabolism, eats `Spore`, mates |
| `Spore` | hostile | drifts, grows, bursts into more spores; Houndeye food |
| `Headcrab` | hostile | leaps onto victims → turns them into `HeadcrabZombie` |
| `HeadcrabZombie` | hostile | infests, then spawns a brood of `Headcrab`s; melee |
| `Zombie` | hostile | dormant until it hears a `Sound`; bites, can infect |
| `DummyChaser` | hostile | test dummy: always chase nearest |
| `DummyRoamer` | neutral | test dummy: always wander |

**Non-NPC domain entities** are short-lived effects/objects that also extend
`Entity`:
- `Weapon` (abstract) + `Rifle/Machinegun/Shotgun/Gattlingun/MGL`: a fire/clip/
  reload state machine; `fire()` spawns projectiles with per-weapon accuracy,
  spread, velocity, range.
- `Bullet` (hit → damage or accuracy-based instakill), `Grenade` (arcs, bounces,
  detonates → `Explosion`), `Explosion` (radius damage with falloff), `Sound`
  (broadcasts to nearby entities via `hear()` — this is how Zombies wake and
  Humans alert), and `Door` (animated open/close that flips the adjacent tile's
  passability — the one entity that writes back into the engine's collision model).

### 3.4 Presentation / Render Layer — `engine.{View, LayerRenderer, Minimap}` + `engine.render.*`

Turns world state into pixels. Reads the domain/engine; never mutates them.

- **`View`** — the camera and render orchestrator. Holds camera `camX/Y/Z`, window
  size, mouse-in-world coords, and a `ViewMode` enum (`NOHUD → BASIC → UNDERLAYS →
  OVERLAYS → ALL`, cycled with `V`). `think(...)` moves/clamps the camera and
  projects the mouse to a tile; `render(g)` clears, calls `world.render(this,
  layerRenderer)`, draws screen-space effects, then the minimap. `pixelX/pixelY`
  do the world→screen projection (depth-scaled via `Utils`), giving the slight 2.5D
  perspective between levels. `renderFPS` overlays the counter.
- **`LayerRenderer`** — performance core. At build time it composites every tile of
  every level into a single per-level `BufferedImage` (`MapLayer`), wall-over-floor,
  and pre-generates downscaled LOD copies. At run time a whole floor is one
  `drawImage` instead of thousands. (Gaussian-blur passes are wired in but
  commented out.)
- **`Minimap`** — a 5%-scale overview of the current level with a white viewport
  rectangle; clicking it recenters the camera. (Per-entity dots exist but are
  currently disabled.)
- **`engine.render.*`** — a self-contained image-filter toolkit:
  `AbstractBufferedImageOp` (fast raster access), `ConvolveFilter` (separable
  convolution), `GaussianFilter` (blur kernel generation), `PixelUtils` (clamp,
  interpolate, ~20 blend modes). Infrastructure for effects; mostly latent today.

### 3.5 Resource / Asset Layer — `engine.ResourceManager` + `res/`

Static asset pipeline, loaded once at startup. Reads the sprite sheets in `res/`
(`tilemap64`, `walls`, `floors`, door/NPC/overlay images) and, crucially,
**pre-composites tile sprites**: given a `Tile.tilecode` (the neighbour-wall
pattern) and a variant, it stitches four 32px sub-tiles into a finished 64px tile
(`getWallTile`, `getFloorTile`, `getHoleTile`, `getRamptile`, …) and produces
NPC/corpse/overlay sprites. The `LayerRenderer` consumes these to build its layer
images.

### 3.6 Cross-cutting — `engine.{Utils, StopWatch, MapLayer}`

- **`Utils`** — shared math used by every layer: world↔pixel coordinate transforms
  with depth scaling (`scaleZ`, `toPixel`, `toTile`), `resize` (bilinear image
  scaling), `random`, `normalizeAngle`, `parseInt`.
- **`StopWatch`** — accumulating profiler (`start`/`stop`/`printReport`); the loop
  prints a periodic report.
- **`MapLayer`** — plain holder for a level's composited image + its LOD downscales
  (data shared between `LayerRenderer` and `Minimap`).

---

## 4. One frame, end to end

```
PrototypeWorld.paint(g)                       [Application]
 ├─ accumulate elapsed time
 ├─ View.think(camera deltas, mouse)          [Presentation: move camera, project mouse]
 ├─ View.render(g)                            [Presentation]
 │    └─ World.render(view, layerRenderer)
 │         └─ per level: draw composited MapLayer, then doors & entities
 │              └─ Entity.render → draw()/draw_dead()  (uses View.pixelX/Y)
 ├─ View.renderFPS(g)
 └─ if (elapsed > 30ms)  World.think()         [Engine: advance the simulation]
        └─ for each Entity:  Entity.run()       [Domain template method]
             ├─ think()              ── decide intent, set dX/dY (AI / combat)   [Domain]
             ├─ collisionCheck()     ── soft repulsion                           [Domain]
             └─ executeMovement()    ── ask World.isConnectedSpace / Tile rules, [Domain→Engine]
                                        cancel illegal moves, commit position,
                                        update per-tile occupancy
        └─ remove dead, drain spawn queue, tick Grids (→ tick Doors)
 └─ repaint()   // loop again
```

The key inter-layer contract is the last block: **rendering reads the world every
frame; the simulation advances on a time gate; and within the simulation the
domain proposes movement while the engine vetoes anything that would cross a wall,
a shut door, or an illegal diagonal.**

---

## 5. Layer dependency rules (observed)

- **Application** depends on Presentation, Engine, and Domain (it wires them
  together) — top of the stack.
- **Presentation** depends on Engine (reads world/tiles) and Resource (sprites);
  it must not change simulation state.
- **Domain** depends on Engine (movement legality, perception, pathfinding,
  spawning) and is driven by it via `Entity.run()`. It implements `draw()` but
  otherwise shouldn't reach into Presentation.
- **Engine** depends only on the Resource layer (for rendering its tiles) and
  cross-cutting `Utils`; it is the stable core.
- **Resource** depends on nothing but the `res/` files and `Utils`.

The few rough edges worth knowing: `Entity` lives in the `engine` package despite
being the domain root (movement coupling); `Door` is a domain entity that writes
back into the engine's tile passability; and `Grid`/`World` contain `render()`
methods, so a slice of presentation logic sits inside the engine package rather
than purely in `View`.
