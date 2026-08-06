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

**Evolvable minds** — `entities/Brain.java`, `entities/AgentIO.java`
- Linear genetic program with persistent registers; 23 sensors in, 11 actuator
  slots out (9 live); wiring *and* length heritable. One instruction per tick, so
  brain size is reaction time — see
  [GENOME.md](GENOME.md#one-instruction-per-tick--a-deliberate-invariant).
- A standing minded cohort competes beside the scripted species in the live world,
  seeded from two hand-written starter brains (forager, hitch-hiker) and kept
  topped up by survivor-seeding from the longest-lived survivor.
- `GenomeCodec` exports a whole creature, brain included, as one line — savefiles
  and token-gated injection into the running world.

**The body's economy** — full spec in [GENOME.md](GENOME.md#the-bodys-economy)
- Resting burn `mass^0.75` (Kleiber) against a linear tank, so endurance goes as
  `mass^0.25`. Movement is kinetic (`mass · v²`), with no separate sprint gear.
- A carried load is simply extra mass on its carrier; the grip on an unwilling
  captive is the one cost paid standing still.
- Creatures are born small and grow into their adult body; growth is physical, so
  a juvenile is cheap to run but easy prey.
- Engine step cap (`MAX_STEP`) and gene clamps (`SIZE_MAX`, `SPEED_MAX`) bound
  what the physics can express, distinct from the balance knobs above.

**A public, always-on world**
- `WorldSteward` holds each population inside fixed floors and ceilings — a
  backstop, not the control: predation and starvation do the work. Creatures a
  person placed by hand are exempt from the cull.
- Seasons were tried and removed: a global fertility cycle drove the whole world
  in lockstep, which read as noise rather than as habitat.

---

## Next — environment tiers

| Tier | Adds | Value | Status |
|---|---|---|---|
| 1 · Living substrate | Regrowing vegetation | Base of the food chain | ✅ done |
| 2a · Fertility field | Patchy grass capacity | Spatial niches | ✅ done |
| **2b · Living pressure** | Temperature/light fields + day/night clock that drain energy and can starve/kill | **Real selection pressure — habitat-driven survival** | keystone; **do it after sensing** — a pressure creatures cannot perceive only kills them at random |
| 3 · Terrain variety | `WATER` (blocks land / passes flyers), `MUD` (slows), `COVER` (blocks LOS) | Behavioural richness | ✅ done |
| 3b · Ramps as floor | A ramp spans two levels, so walking across one changes level | Vertical space costs a mind nothing to use | ✅ done |
| 4 · Scent / stigmergy | Per-tile pheromone (lazy decay) + deposit/sense/home | Emergent **nests**: a marked peak the lineage clusters around | ✅ done (nesting) |

---

## Next — cross-cutting entity APIs (the missing half)

The environment has a *write* side (grass grows, fertility varies) but entities
lack the *read* side and the *economy* that give it stakes. These ship
alongside the tiers:

- ✅ **Energy / metabolism.** Opt-in energy pool on `NPC` (`metabolic`): `graze()`
  feeds it, the genome's `metabolism` drains it each tick, zero = starve. Real
  species leave it off, so they're unaffected.
- ✅ **Reproduction (asexual).** `tryReproduce()` + `spawnOffspring()` bud a
  mutated `Genome.child(parent)` when a metabolic entity is well-fed and off
  cooldown. The `breeder` fixture runs the full loop; a fed population grows and
  drifts (`PopulationGrowsWithFood`). *Evolution is on.*
- ✅ **Sexual reproduction.** `canMateWith(other)` (mutual, marker-based mate
  choice + energy + cooldown) and `reproduceWith(partner)` bear a crossover
  `Genome.child(mom, dad)`; both parents pay and go on cooldown. The `mater`
  fixture runs it, and `SexualReproductionNeedsPartner` pins the three defining
  facts: a partner is required, dissimilar maters refuse, offspring recombine
  both parents. The assortative-mating path to true speciation is open.
- **Environment sensing.** Entities can't perceive fields — `graze()` eats
  blindly. Add `senseVegetation()` / `senseFertility()` / `senseTemperature()` /
  `senseScent(dir)` so behaviour can *steer toward* food, warmth, trails.

---

## The gap that matters most

The apparatus is built and barely used. Of **23 sensors the starter brains read
three** (`S_CLOCK`, `S_THREAT_PROX`, `S_THREAT_BEARING`); of 9 live actuators
they write five. Never touched by any seed: `A_DEPOSIT`, `A_ATTACK`, `A_GRAB`,
`A_STRUGGLE`, and the sensors for food, scent, prey, kin, health, items,
whiskers and hazards.

So the cheapest new behaviour is mostly behaviour already paid for:

1. **Food-gradient sensing, and a forager that climbs it.** `S_FOOD` reports only
   *"am I standing on grass"* — there is no directional food sensor anywhere in
   the engine, so a creature cannot tell where food *is*. Measured consequence:
   the minded cohort reports **wandering 71, grazing 4** out of 80. They are not
   failing to forage, they are searching blind. Add a bearing sensor and a brain
   that climbs it, and foraging becomes a skill selection can sharpen.
2. **A hunter brain.** `A_ATTACK` + `S_PREY_*` are fully wired and unused — all
   predation is scripted. Roughly the shape of the hitch-hiker brain, and it lets
   the cohort grow its own predators instead of the role being hardcoded.
3. **A nester brain.** Tier 4 is done, but only the *scripted* nester nests.
   `World.pheromoneDirection()` already exists — minds simply have no sensor for
   it. Exposing it gives colonies that emerge from evolved minds.
4. **The underground.** Multi-level tunnels exist that no mind has entered. This
   one no longer needs an actuator: a ramp is floor that spans two levels, so a
   mind reaches the cave by walking there. What it needs is a *reason* to go —
   which is Tier 2b, since shelter is only worth having once the surface hurts.

Note the standing constraint when adding any of these: one instruction per tick
means every extra sensor costs a tick of reaction latency, so seed brains stay
lean and lineages trade capability against reflexes themselves.

## Sequencing note

**Sensing (1) → a hunter brain (2) → Tier 2b living pressure.** Sensing comes
first because it is the roadmap's own open item, because it is what the current
population visibly lacks, and because Tier 2b's whole point is a pressure
creatures can *respond* to. Each lands as its own validated, committed slice.
