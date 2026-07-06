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
        seed(3);                                   // 1. deterministic RNG
        World w = room(12, 5);                      // 2. hardcoded world
        DummyChaser hunter = new DummyChaser(2.5, 2.5, 0);
        Zombie prey = new Zombie(3.7, 2.5, 0);      //    exact positions
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

### Helpers on `Scenario`

| Helper | Purpose |
|---|---|
| `seed(long)` | Seed the RNG and init the profiling stopwatch. **Call first.** |
| `room(cols, rows)` | Build a single-level world with an all-open interior (the engine forces a 1-tile wall border). Consumes no RNG. |
| `tick(w, n)` | Advance the simulation `n` ticks (`world.think()`). |
| `snapshot(w, label)` | Capture a labelled screenshot (no-op unless `-Dsimtest.shots` is set). |
| `assertTrue / assertEquals / assertNear / assertLess / assertGreater` | Assertions; the message is printed on failure. |

### Spawning

`w.spawnEntity(e)` queues the entity — it does not enter the world until the
next `world.think()`. If you want to snapshot or assert on an entity's initial
state before ticking the behaviour, call `w.think()` once first to register it.

## Screenshots (before/after)

Call `snapshot(w, "<label>")` at the interesting moments. Capture is **off by
default** (so normal runs stay fast); enable it with `-Dsimtest.shots=<dir>`.
The runner composes each scenario's captures into a single labelled strip named
`<Scenario>.png` — e.g. `before (tick 0)` next to `after (tick 300)`, with the
entity-count overlay baked in. Capture also fires on failing scenarios, so a red
test leaves you a picture of what went wrong.

Snapshots render the whole of ground level, integer-upscaled so even a 3×3 world
is legible.

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
- **Zombies spawn dormant** and don't move until they hear a `Sound` — handy for
  a stationary target, but call it out if you expect motion.

Keep scenarios small and single-purpose: one behaviour, a handful of entities,
a clear assertion. That is what makes a failure point straight at the cause.
