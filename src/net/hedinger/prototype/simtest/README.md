# Scenario tests

Deterministic mini-simulations that pin engine behaviour. Each test: **seed →
build a small world → place entities → tick N → assert on the outcome**
(positions, alive/removed, counts, tile state).

Reproducible from the seed, so they are **exact** — a failure means behaviour
actually changed. No test framework; plain `javac`/`java`, same as the app.

## Scope

**In scope — engine mechanics via fixtures:** movement, collision, perception,
line-of-sight, lifecycle (age/death/scavenging), hearing, grabbing/carrying,
doors/ramps/holes, and the environment substrate (vegetation, fertility).

**Out of scope — concrete game species.** The bestiary (Zombie, Houndeye, …) is
expected to change or disappear; tests must survive that. Use `TestNPC`
fixtures, not real species. Only use a game entity when the test *deliberately*
pins that species — and expect it to be deleted with the species.

One behaviour, a handful of entities, one clear assertion per test.

## Run

```bash
javac -d bin $(find src -name '*.java')
java -cp bin net.hedinger.prototype.simtest.SimTests                 # all
java -cp bin net.hedinger.prototype.simtest.SimTests ChaserClosesIn  # by name
java -Dsimtest.shots=out/shots -cp bin ...SimTests                   # + screenshots
```

Prints `PASS`/`FAIL`/`ERROR` per scenario; exits non-zero on any failure.

## Add a test

1. Add a `static class YourTest extends Scenario` with one `run()`.
2. Register it in the `all()` array in `SimTests.java`.
3. Follow **build → tick → assert**. Keep it single-purpose.

```java
static class ChaserClosesIn extends Scenario {
    @Override public void run() {
        seed(3);                                     // 1. deterministic RNG (call first)
        World w = room(12, 5);                       // 2. build the world
        TestNPC hunter = TestNPC.chaser(2.5, 2.5, 0);
        TestNPC prey   = TestNPC.inert(3.7, 2.5, 0); //    exact positions
        w.spawnEntity(hunter);
        w.spawnEntity(prey);
        w.think();                                   //    register spawns (see below)
        snapshot(w, "before");                       // 3. optional screenshot
        tick(w, 300);                                // 4. advance the sim
        snapshot(w, "after");
        double d = Math.hypot(hunter.getX() - prey.getX(), hunter.getY() - prey.getY());
        assertLess("chaser reached its prey", d, 0.5); // 5. assert the outcome
    }
}
```

`spawnEntity` **queues**; the entity enters on the next `think()`. Call
`w.think()` once before snapshotting/asserting an entity's initial state.

### `Scenario` helpers

| Helper | Purpose |
|---|---|
| `seed(long)` | Seed the RNG. **Call first.** |
| `room(cols, rows)` | Single-level open room (1-tile wall border forced). Consumes no RNG. |
| `room(cols, rows, lvls)` | Multi-level; carve holes/ramps after with `w.setTile(...)`. |
| `tick(w, n)` | Advance `n` ticks. |
| `snapshot(w, label)` | Labelled screenshot (no-op unless `-Dsimtest.shots` set). |
| `assertTrue / assertEquals / assertNear / assertLess / assertGreater` | Message printed on failure. |

### `TestNPC` fixtures

| Factory | Behaviour |
|---|---|
| `inert(x,y,z)` | Never moves — target/obstacle/victim |
| `roamer(x,y,z)` | Wanders |
| `chaser(x,y,z)` | Chases the nearest perceived NPC |
| `listener(x,y,z)` | Inert until it hears a `Sound`, then roams (`hasHeard()`) |
| `mover(x,y,z,heading)` | Straight line; halts at blockers — probes passability |
| `genomeDriven(x,y,z,genome)` | Reacts via a `Genome` (attack/mate/affiliate→chase, flee→flee, else roam); `lastAction()` |
| `grazer(x,y,z)` | Eats vegetation underfoot (`NPC.graze`); `totalIntake()` |
| `breeder(x,y,z,genome)` | Metabolic grazer: burns energy, starves at 0, buds a mutated child when fed (the evolutionary loop); `getEnergy()` |
| `nester(x,y,z,genome)` | A breeder that lays pheromone where it breeds and homes back to it — so an **emergent nest** (pheromone peak) forms and the lineage clusters into a colony |

Fluent knobs chain: `.withHealth(n)`, `.withLifespan(n)`, `.withDeathspan(n)`,
`.withSpeed(s)`, `.withSize(px)`, `.withFlying()`.

### Screenshots

`-Dsimtest.shots=<dir>` composes each scenario's `snapshot()`s into a
`<Scenario>.png` before/after strip (fires on failures too). The debug overlay
draws what's otherwise invisible: cyan heading arrows, per-NPC state labels,
magenta carry links, a vegetation wash (brown=barren → green=lush), red
closed-door bars, and every level side-by-side.

## Gotchas (account for these or tests lie)

- **Perception is myopic.** Scanning only gathers from the 3×3 tile box, so
  `LOS_RANGE` > ~1.5 tiles doesn't help acquisition — prey must start adjacent.
- **Acquisition is slow.** A chaser sweeps its FOV over several `SEARCH_FREQ`
  scans before locking on — budget hundreds of ticks.
- **Sizes are pixel radii.** `getSize() = size/64`; a size-6 NPC is ~0.09 tiles.
  Contact, shove and grab all act at ~0.1 tiles — entities must nearly overlap.
- **Corpses don't decay alone.** `kill()` pins `age=-1`; `deathspan` is a
  scavenging budget spent only by `eat()`, not a timer.
- **`Sound` broadcasts late** — reaches `hear()` at the end of its ~20-tick
  life, not on spawn. Tick past that.
- **Determinism wants one level.** `room(c,r)` draws no RNG; multi-level
  generation carves holes with the RNG.
- **Ramps = walking into walls.** A `RAMPUP` tile makes the move east onto a
  `WALL` legal; the in-wall check then pops the entity up a level. X-oriented.
- **Carrying ignores walls/bounds**, and out-of-world entities are killed — keep
  grab tests off the border.
- **Spawn truncates toward zero** — x/y in (-1,0) is accepted, then killed on
  tick 1 (`SpawnRejectsOutOfBounds` pins it).
- **Vegetation is lazy + clock-driven.** Only `TYPE_FLOOR` grows grass;
  `Tile.getVegetation(now)` is closed-form off `World.getTick()`, so it only
  changes when ticked. `fertility` (default 1) caps density;
  `World.generateFertility(freq)` paints patches without drawing RNG.
- **Terrain types have distinct rules.** `TYPE_WATER` is open (flyers pass) but
  not walkable — land entities halt at the shore. `TYPE_MUD` is walkable but
  `speedFactor()` drags a mover to 0.4×. `TYPE_COVER` is walkable but
  `blocksSight()` — an entity standing in it is invisible to perception (LOS is
  blocked into/through it). Place them with `w.setTile(x, y, z, type)`.
- **Energy is opt-in.** Only entities with `metabolic = true` (e.g. `breeder`)
  burn energy and starve; every other NPC ignores the economy entirely, so the
  bestiary and determinism are unaffected. `graze()` feeds energy; metabolism
  (the genome's rate) drains it in `run_extended`, before the think cycle.
- **Pheromone is lazy + diffusion-free.** `Tile.getPheromone(now)` decays in
  closed form (`stored · PHERO_DECAY^Δt`) off the world clock; `deposit()` adds
  to it. There is no spreading sweep — homing (`NPC.nestDirection`) samples a
  tile neighbourhood for the strongest cell, so nests are local peaks.
