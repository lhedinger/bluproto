# Scenario tests

Small, deterministic simulation experiments. Each scenario seeds the RNG,
builds a hardcoded world, places entities at exact positions, advances the
simulation a fixed number of ticks, then asserts on observable outcomes
(positions, alive/removed state, counts).

Because the simulation is fully reproducible from its seed, these tests are
**exact**: a failure means behaviour actually changed. They use no test
framework, so they compile and run with the plain JDK — the same `javac`/`java`
as the app.

## Running

From the repository root:

```bash
javac -d bin $(find src -name '*.java')

# all scenarios
java -cp bin net.hedinger.prototype.simtest.SimTests

# one scenario by name
java -cp bin net.hedinger.prototype.simtest.SimTests ChaserClosesIn

# with before/after screenshots written to a directory
java -Dsimtest.shots=out/shots -cp bin net.hedinger.prototype.simtest.SimTests
```

The runner prints `PASS`/`FAIL`/`ERROR` per scenario and exits non-zero if any
fail — so it drops straight into CI or a pre-commit hook.

## Writing a scenario

A scenario is a subclass of `Scenario` with one `run()` method. Add it to the
`all()` array in `SimTests`. The pattern is always **build → tick → assert**:

```java
static class ChaserClosesIn extends Scenario {
    @Override
    public void run() {
        seed(3);                                    // 1. deterministic RNG
        World w = room(12, 5);                      // 2. hardcoded world
        TestNPC hunter = TestNPC.chaser(2.5, 2.5, 0);
        TestNPC prey = TestNPC.inert(3.7, 2.5, 0);  //    exact positions
        w.spawnEntity(hunter);
        w.spawnEntity(prey);
        w.think();                                  //    register spawns
        snapshot(w, "before (tick 0)");             // 3. optional screenshot
        tick(w, 300);                               // 4. advance the sim
        snapshot(w, "after (tick 300)");
        double d = Math.hypot(hunter.getX() - prey.getX(),
                              hunter.getY() - prey.getY());
        assertLess("chaser reached its prey", d, 0.5);   // 5. assert outcome
    }
}
```

## Use test fixtures, not game species

Scenarios should exercise **engine mechanics** — movement, collision,
perception, lifecycle, hearing — not the behaviour of any concrete game
species. The bestiary (Zombie, Houndeye, Human, ...) is expected to change or
disappear as the project evolves, and this suite must survive that.

`TestNPC` is the fixture for this. It provides the minimal behaviours the
mechanics need, with per-instance knobs instead of species constants:

| Factory | Behaviour |
|---|---|
| `TestNPC.inert(x, y, z)` | Never moves — a stationary target, obstacle or victim |
| `TestNPC.roamer(x, y, z)` | Wanders randomly |
| `TestNPC.chaser(x, y, z)` | Chases the closest NPC it can perceive |
| `TestNPC.listener(x, y, z)` | Inert until it hears a `Sound`, then roams (`hasHeard()` to probe) |
| `TestNPC.mover(x, y, z, heading)` | Walks a straight line (radians, 0 = +x); halts where the engine blocks — ideal for probing passability (doors, walls, ramps) |
| `TestNPC.genomeDriven(x, y, z, genome)` | Behaviour driven entirely by a `Genome`: reacts to the most salient perceived neighbour (attack/mate/affiliate → chase, flee → flee, else roam). Sources its body stats from the genome and colours its dot by the genome markers; `lastAction()` exposes what it decided |

Lifecycle and body knobs chain fluently:

```java
TestNPC e = TestNPC.inert(4.5, 4.5, 0).withLifespan(50).withDeathspan(0);
TestNPC h = TestNPC.inert(4.5, 4.5, 0).withHealth(10).withDeathspan(100);
TestNPC f = TestNPC.inert(6.5, 6.5, 1).withFlying();      // hovers over holes
TestNPC g = TestNPC.roamer(5.5, 5.5, 0).withSize(6);      // pixel radius; gates grab
```

Only reach for a real game entity when the scenario is deliberately pinning
that species' behaviour — and expect such scenarios to be deleted with the
species.

### Helpers on `Scenario`

| Helper | Purpose |
|---|---|
| `seed(long)` | Seed the RNG and init the profiling stopwatch. **Call first.** |
| `room(cols, rows)` | Build a single-level world with an all-open interior (the engine forces a 1-tile wall border). Consumes no RNG. |
| `room(cols, rows, lvls)` | Multi-level variant, every interior carved open. Punch holes/ramps explicitly with `w.setTile(x, y, z, type)` afterwards. |
| `tick(w, n)` | Advance the simulation `n` ticks (`world.think()`). |
| `snapshot(w, label)` | Capture a labelled screenshot (no-op unless `-Dsimtest.shots` is set). |
| `assertTrue / assertEquals / assertNear / assertLess / assertGreater` | Assertions; the message is printed on failure. |

### Spawning

`w.spawnEntity(e)` queues the entity — it does not enter the world until the
next `world.think()`. If you want to snapshot or assert on an entity's initial
state before ticking the behaviour, call `w.think()` once first to register it.

## Screenshots (before/after, with debug overlay)

Call `snapshot(w, "<label>")` at the interesting moments. Capture is **off by
default** (so normal runs stay fast); enable it with `-Dsimtest.shots=<dir>`.
The runner composes each scenario's captures into a single labelled strip named
`<Scenario>.png` — e.g. `before (tick 0)` next to `after (tick 300)`. Capture
also fires on failing scenarios, so a red test leaves you a picture of what
went wrong.

On top of the normal game render, snapshots draw a **debug overlay** of state
that is otherwise invisible:

- a cyan **heading arrow** on every living NPC,
- a **state label** under each NPC — for fixtures this is `TestNPC.debugLabel()`
  (behaviour, `fly`, `heard!`, `grabbing`, `carried`, `hpN`, `dead`); colliding
  labels stack downward instead of overprinting,
- a magenta **carry link** between a carrier and its cargo (circle on the cargo),
- **closed door edges** as red bars on the tile border,
- **every world level, side by side**, so cross-level action (ramp climbs,
  hole falls) is visible.

Snapshots are integer-upscaled so even a 3×3 world is legible. The overlay
already earned its keep: it exposed that `drop()` left the carrier's `grabbing`
reference stale (now fixed and pinned by an assertion).

## Gotchas worth knowing

These are real properties of the current simulator that scenarios must account
for (writing the first tests surfaced them):

- **Perception is myopic.** Target scanning gathers candidates only from the
  3×3 tile box around an NPC, so an `LOS_RANGE` larger than ~1.5 tiles has no
  effect on target acquisition. Prey must start within an adjacent tile to be
  chased at all.
- **Corpses never decay on their own.** `kill()` pins `age` at `-1` and nothing
  decrements it; `deathspan` is a *scavenging budget*, not a timer. A body is
  removed only after `eat()` pushes its age past `-deathspan`.
- **Determinism needs a single level.** A one-level `room(...)` consumes no RNG
  during construction, so runs are bit-reproducible. Multi-level world
  generation carves holes with the RNG.
- **`Sound` broadcasts late.** A `Sound` reaches `hear()` on nearby entities at
  the end of its ~20-tick lifespan, not on spawn — tick past that before
  asserting a reaction.
- **NPC sizes are pixel radii.** `getSize()` converts to tiles as
  `size / tileSize` (÷64), so a size-6 NPC has a ~0.094-tile radius. Contact,
  collision-shove and grab reach all work at that ~0.1-tile scale — entities
  must practically overlap to interact physically.
- **Carrying ignores walls and bounds.** A grabbed entity is placed at the
  attachment offset with no collision or validity check, and `Entity.run()`
  kills anything positioned outside the world — keep grab scenarios away from
  the map border.
- **Ramps work by walking into walls.** Standing on a `RAMPUP` tile makes the
  move onto the next tile east legal even though it is `WALL` at this level;
  the in-wall check in `executeMovement` then pops the entity up a level
  (`dZ=+1`). Ramps are x-oriented (`RAMPUP` exits east, `RAMPDOWN` west).
- **Acquisition can take hundreds of ticks.** A chaser spawns facing a random
  direction and its FOV must sweep across the target over several perception
  scans (`SEARCH_FREQ` apart) before it locks on — give chase scenarios a
  generous tick budget.
- **Spawn validation truncates toward zero.** `spawnEntity` accepts x/y in
  (-1, 0) because `(int)` casts truncate toward zero (column -0.5 becomes 0);
  the runtime validity check then kills the entity on its first tick.
  `SpawnRejectsOutOfBounds` pins this seam.

Keep scenarios small and single-purpose: one behaviour, a handful of entities,
a clear assertion. That is what makes a failure point straight at the cause.
