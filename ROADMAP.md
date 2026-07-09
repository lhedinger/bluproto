# Roadmap

**Goal:** grow this agent simulator into a *digital ecosystem with evolving
entities* — a world where behaviour and bodies are heritable, the environment
exerts selection pressure, and species emerge rather than being hardcoded.

The path has two intertwined tracks: **environment** (make the world a habitat)
and **entities** (make them evolve, sense, and survive). Both are built in
small, deterministic, test-backed slices.

## Principles

- **Deterministic.** All randomness routes through the seeded RNG. Environment
  fields are sampled from coordinates (`Utils.noise2`) so they draw no RNG
  state and never perturb world-generation reproducibility. Behaviour-preserving
  refactors are proven with a state checksum.
- **Lazy, O(entities) not O(map).** Per-tile state (e.g. vegetation) is computed
  in closed form on access, never swept every tick.
- **Fixtures, not species.** Mechanics are pinned with `TestNPC` fixtures in the
  scenario suite; the bestiary is expected to be replaced by genome-driven
  entities.
- **Visible.** Every new system renders (live view + scenario snapshots) so it
  can be seen, not just asserted.

---

## Done

**Foundation — performance & correctness**
- Dead-code purge; collapsed search variants; dropped A/B perf toggles.
- Primitive int-keyed entity store (`IntObjectMap`); fixed latent nondeterminism.
- Reduced boxed-`TreeMap` churn in perception; tighter LOS/bullet tile gates.

**Scenario-test harness** — `src/net/hedinger/prototype/simtest/`
- Zero-dependency `build → tick → assert` mini-simulations, decoupled from the
  bestiary via `TestNPC` fixtures; before/after debug-overlay screenshots.

**Genome relationship model** — `entities/Genome.java` (full plan in [GENOME.md](GENOME.md))
- Heritable trait vector: phenotype · neutral markers · dispositions.
- `react(other, sizeAdv)` → similarity-based drive (attack / flee / affiliate /
  mate). Inheritance via `child()` (asexual + crossover) and `mutate()`.
- **Phenotype migration:** all 12 species source body stats from a founder
  `Genome` (`NPC.applyGenome`) — proven byte-for-byte behaviour-preserving.

**Environment — Tier 1 & 2a**
- *Living substrate:* per-tile regrowing vegetation (lazy closed-form off a new
  `World.getTick()` clock); `NPC.graze()`; grazer fixture.
- *Fertility field:* `Tile.fertility` gates the grass cap;
  `World.generateFertility()` paints coherent patchy habitats.

---

## Next — environment tiers

| Tier | Adds | Value | Status |
|---|---|---|---|
| 1 · Living substrate | Regrowing vegetation | Base of the food chain | ✅ done |
| 2a · Fertility field | Patchy grass capacity | Spatial niches | ✅ done |
| **2b · Living pressure** | Temperature/light fields + day/night clock that drain energy and can starve/kill | **Real selection pressure — habitat-driven survival** | ▶ next (keystone, higher risk: touches survival + determinism) |
| 3 · Terrain variety | `WATER` (blocks land / passes flyers), `MUD` (slows), `COVER` (blocks LOS) | Behavioural richness | planned (cheap, safe) |
| 4 · Scent / stigmergy | Deposit + decay field | Emergent trails, foraging | planned |

---

## Next — cross-cutting entity APIs (the missing half)

The environment has a *write* side (grass grows, fertility varies) but entities
lack the *read* side and the *economy* that give it stakes. These ship
alongside the tiers:

- **Environment sensing.** Entities can't perceive fields — `graze()` eats
  blindly. Add `senseVegetation()` / `senseFertility()` / `senseTemperature()` /
  `senseScent(dir)` so behaviour can *steer toward* food, warmth, trails. Every
  environment tier needs a matching sense API or entities can't respond to it.
- **Energy / metabolism.** No cost → no selection. Add an `energy` pool, a
  per-tick `metabolism` drain (the gene already exists in `Genome`, unused), and
  `starve()`. Closes the loop: grass → energy → survival.
- **Reproduction.** `Genome.child()` is built but **wired to zero spawn points**.
  Add `reproduce(mate)` / `spawnOffspring(genome)` and a working `canMate(other)`
  (mate drive + cooldown), so heritable variation actually flows to offspring.
  *This is the keystone that turns "genome" into "evolution."*

---

## Sequencing note

Recommended order once the above are scoped: **Tier 3 (quick, safe win)** →
**energy + reproduction** (unlocks evolution) → **Tier 2b living pressure**
(the selection engine) → **Tier 4 scent**. Each lands as its own validated,
committed slice.
