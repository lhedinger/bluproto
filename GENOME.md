# Genome

The heritable core of the ecosystem. An entity's body **and** its behaviour are
encoded in a `Genome` (`src/net/hedinger/prototype/entities/Genome.java`) that is
passed to offspring with mutation — so relationships are *learned by the lineage*
rather than hardcoded, and species emerge as clusters rather than Java classes.

Relationships are **emergent, not authored**: how one entity reacts to another
falls out of *how similar they are* + *their relative size* + *this genome's
dispositions*. No "A hates B" table.

---

## The model — four layers

| Layer | Fields | Role |
|---|---|---|
| **Phenotype** (the body) | `size`, `speed`, `turnRate`, `losRange`, `losFov`, `metabolism`, `maxAge`, `flying` | Drives physics, perception and the energy economy |
| **Markers** (recognition) | `markers[3]` ∈ [0,1] | A neutral "barcode" — no physical effect. Two entities are *similar* when markers are close. Mapped to RGB (`toColor()`) so similarity is visible |
| **Dispositions** (reflex behaviour) | `predatory`, `xenophobia`, `gregariousness`, `boldness`, `mateThreshold` | Response weights that turn a perceived neighbour into a drive (`react()`) |
| **Mind** (learned behaviour) | `brain` — a `Brain`, or null | An evolvable program. Where dispositions give a fixed reflex, the brain *decides*, and both the wiring and its length are heritable |

Keeping markers (who I recognise) separate from dispositions (how I feel about
the recognised) lets recognition and behaviour evolve independently. The brain is
separate again: a genome can carry a body with no mind (the scripted species) or
a body driven entirely by one (the minded cohort).

## The mind — `Brain` + `AgentIO`

A creature with a `brain` is driven by a **linear genetic program** rather than a
hardcoded rule. Its body fills a sensor vector, the program runs, and the body
applies the actuator vector as intent. The mind never touches the world directly.

- **Registers** — 12 scalars that persist across ticks, so a brain has memory.
- **Ops** — `NOP SET MOV ADD SUB MUL MIN MAX NEG TANH GT SKIPZ SKIPNZ SENSE WRITE`,
  over a fixed 12-value constant pool.
- **I/O** — 28 sensors (`S_*`) in, 13 actuator slots (`A_*`) out, of which 11 are
  live: `A_SPRINT` and `A_VERTICAL` are retired in place. Slots are never deleted,
  because instructions store raw actuator indices and renumbering would silently
  rewrite every saved genome. Actuator values **latch** between writes, so a
  program only spends instructions on what changes.
- **Heredity** — point mutation plus insertion/deletion of whole instructions, and
  variable-length crossover, so wiring *and* length evolve. Capped at `MAX_LEN`.

### Resizing the I/O vectors is a breaking change

`Brain` masks operand indices **modulo the live array length** (`s[imod(y,
s.length)]`), which is what makes every random mutation a legal program. The
consequence is that *growing* a vector is as destructive as deleting a slot: a
genome encoded against 23 sensors reads different sensors once there are 28. Its
wiring is not extended, it is rewritten.

So `GenomeCodec` carries a version tag, and it is bumped whenever either vector
changes size — `g1` was 23/11, `g2` is 28/13. Old tokens are **rejected**, not
migrated: a stale token names a creature that can no longer be reconstructed, and
silently loading a different animal under its name is worse than refusing.

### Intent commands — naming a target instead of steering to it

`A_SEEK` names a *kind of thing to head for* rather than a turn rate. While it is
set and the body can see one, the body supplies the heading and `A_TURN` is
ignored; the sign flips attraction to avoidance, so chasing and fleeing are one
instruction apart. Throttle stays the mind's, and so do walls — the body gives a
direction, not a route.

The magnitude selects the target on bands centred on the constant pool, so one
`SET`+`WRITE` can name any of them, and a small mutation usually preserves the
intent:

| `A_SEEK` | Target | | `A_SEEK` | Target |
|---|---|---|---|---|
| `±0.1` | forage patch | | `±1` | threat |
| `±0.25` | kin | | `±2` | item |
| `±0.5` | prey | | `±4` | waypoint |

Naming something absent does nothing at all and leaves `A_TURN` in charge, so a
seek can never freeze a creature pointing at what isn't there.

`A_MARK` plus the waypoint sensors are the whole of spatial memory: latch where
you are, wander off, come back. **The coordinate lives in the body**, not in the
mind's registers — necessarily so, because the instruction set has no divide and
no `atan2`, so a mind holding two numbers could never turn them into a heading.

Note what this does to the economy above: an intent command packs far more
capability into one instruction than `A_TURN` does. Under one instruction per
tick, a lineage that seeks is both more capable *and* faster to react than one
that steers by hand. That is a deliberate widening of what selection can reach,
and it means seed brains can be shorter still.

### One instruction per tick — a deliberate invariant

A brain executes **one instruction per tick**, so program length *is* the length
of one thought cycle: a 14-instruction brain completes a cycle every 14 ticks
(≈0.42 s at 33 t/s). This is a specification, not a limitation to engineer away.
It makes capability and reflex speed a real trade-off that selection must price:
a mind that senses more reacts more slowly, and a lean brain that reacts fast
gives something up to do it. Since length is heritable, lineages settle this for
themselves. **Seed brains are therefore kept short**, and new ones should be.

### Starter brains

Fully-random brains were tried first and never stumbled onto feeding, so
selection had no gradient to climb. Founders instead get a crude, viable seed
that mutation sharpens:

| Brain | Strategy |
|---|---|
| `Worlds.starterBrain()` | Forager — drive, graze, breed, wander by the clock, turn **away** from anything bigger |
| `Worlds.hitchhikerBrain()` | Hitch-hiker — the same sensors read with the opposite sign: turn **toward** anything bigger and ride it |

Every third minded founder is a hitch-hiker, so both strategies compete from tick
one; survivor-seeding then propagates whichever is coping.

## Reaction — `react(other, sizeAdv)`

Given a perceived neighbour's genome and the size ratio `sizeAdv = my size /
their size`, it returns the dominant drive (survival-first on ties):

```
s        = similarityTo(other)      // 1 = identical markers … 0 = maximally distant
dissim   = 1 - s
attack   = predatory     * dissim * max(0, sizeAdv - 1)      // hunt smaller & dissimilar
flee     = xenophobia    * dissim * max(0, 1/sizeAdv - 1) - boldness   // flee bigger & dissimilar
affiliate= gregariousness* s                                 // flock with the similar
mate     = s >= mateThreshold ? s : 0                        // breed with the very similar
```

→ `Action ∈ {IGNORE, ATTACK, FLEE, AFFILIATE, MATE}`. Predation and flight key
off the **size ratio**, which is what makes relationships **asymmetric** (big
eats small; small flees big) from a single symmetric similarity.

## Inheritance

| Call | Meaning |
|---|---|
| `Genome.child(parent, rate)` | Asexual: a mutated copy |
| `Genome.child(a, b, rate)` | Sexual: per-gene crossover of two parents, then mutation |
| `mutate(rate)` | Jitter every gene by ±rate (relative for magnitudes) |
| `random()` / `phenotype(...)` | Found a population / build a body-only founder |

Mutation draws from the **seeded RNG**, so evolution is fully reproducible.

---

## The body's economy

Every genome-driven creature that opts into `metabolic` runs the same energy
model. Everything in it derives from the body the genome asks for, so the
phenotype genes have real consequences rather than being cosmetic.

### The per-tick bill

```
energy -= base + grip + travel

base   = BASE_METABOLISM · mass^0.75 · (metabolism / META_REF)   // staying alive
grip   = GRIP_ENERGY · held_mass          // restraining an unwilling captive
travel = MOVE_ENERGY · (mass + carried_mass) · v²                // going somewhere
```

`mass = size / REF_SIZE`, and `v` is the ground **actually covered** this tick —
a step cancelled by a collision moved nothing and costs nothing, so travel prices
movement rather than intent.

### The laws that fall out of it

- **Resting scales sublinearly with mass** (`mass^0.75`, Kleiber) while the tank
  scales linearly, so fasting endurance goes as **mass^0.25**: a bigger body
  idles longer between meals, but needs bigger meals to refill.
- **Movement is kinetic.** Cost per tick rises with v², so cost per *tile* rises
  linearly with v — twice as fast is four times as expensive per tick and twice as
  expensive per tile. Covering ground is cheapest slowly, so speed has to buy
  something real (escaping, catching) to be worth its price.
- **Travel is mass-neutral in tank terms.** `travel / capacity` reduces to
  `distance / 600` with the mass cancelling: crossing the map costs *every*
  creature the same fraction of its reserve. Being big buys endurance at rest and
  nothing at all for covering ground.
- **A load is simply extra mass.** Carrying has no separate toll; whatever a
  creature hauls makes it heavier and is billed through movement. Standing still
  under a load is nearly free; walking off with it costs in proportion. Flight
  counts a load several times heavier — lifting is harder than dragging.
- **The grip is the exception**, and the only thing separating a ferry from a
  captor: restraining something that does not want to be held costs whether or not
  you move, so captivity is an effort rather than a free permanent state.
- **There is no sprint gear.** A creature asks for the speed it wants (throttle,
  for a mind) and the quadratic law prices that choice continuously at every
  speed, rather than only above a threshold.

### The income side — what food is worth

Spending is mass-based everywhere above, so income is too.

```
meat  = MEAT_ENERGY · prey_mass · (damage / FULL_BODY_HEALTH)   // per bite
grass = GRASS_ENERGY · vegetation_cropped                       // per tick grazing
```

- **A carcass is worth what it weighs.** Health is a flat 100 on every body, so
  the *meal* has to carry the size instead. Since each bite pays for the share of
  the body it removed, a whole carcass comes to `MEAT_ENERGY · prey_mass` however
  many bites it took — an animal that is hard to bring down is slower to eat, not
  more nutritious, and a hunter arriving at something already chewed on gets only
  what is left.
- **This was the one corner where mass did not appear.** A flat per-bite payout
  made a mouse and an animal the hunter's own size worth exactly the same
  (measured: 2.49 either way), which pointed selection at the smallest, easiest
  quarry and left no niche for a large hunter.
- **Grass is bulk food.** Stripping one tile bare takes about **4 seconds** for the
  largest body in the world and **11 seconds** for a reference-size one, and yields
  ~0.64 energy against the 2.5 a same-mass carcass is worth. So grazing is a
  full-time occupation and predation is an event — a hunter banks its meal in a
  handful of ticks, a grazer works for minutes. That is why a herd is spread thin
  across the map while hunters are few. Bite size scales with mass like everything
  else, so a big grazer both eats and burns more, and clears a patch sooner.
- **Crop rate and energy density are one knob, not two.** They multiply into a
  herbivore's income per tick, so grass cannot be made both slower to eat and
  poorer without starving the herd. Measured over 60k ticks at the current crop
  rate: below `GRASS_ENERGY` 0.75 predators fall to their floor on empty tanks, and
  at 0.25 the herd stops breeding and only the steward keeps it alive. The shipped
  value is the poorest grass the food chain will carry.
- **A predator will not kill on a full tank.** The opportunistic bite is gated on
  having room for the meal; without that, a full hunter killed prey whose energy
  the tank cap then discarded.

### Growth

A creature is born at `BIRTH_SIZE_FRACTION` of its adult body and grows in at a
fixed `GROWTH_RATE`. Because the *rate* is fixed and the *distance* is not,
childhood length scales with adult size — the largest body a genome can express
takes about a minute, the longest childhood the world produces.

Growth is deliberately **physical, not economic**. Everything derived from the
body a creature has *right now* follows it down — resting burn, movement cost,
collision reach, and what a hunter may take — so a juvenile is cheap to run but
genuinely small and easy prey. The energy **tank** is anchored on the *adult*
body, which keeps the whole reproduction economy (born-fed level, breeding
threshold and cost, a hunter's "sated" line) identical to a world without growth.
Anchoring the tank on the juvenile body instead was tried and silently re-gated
breeding on maturity while leaving young hunters unable ever to count as sated.

### Engine limits, not balance knobs

- `Entity.MAX_STEP` (0.5 tiles) caps any single step. Passability is decided one
  tile at a time, so a longer step would tunnel through terrain — and past a full
  tile the collision test rejects it outright, freezing the creature rather than
  speeding it up. Direction is preserved; only magnitude is clamped.
- `Genome.SIZE_MAX` / `SPEED_MAX` bound the two genes that would otherwise
  random-walk without limit. With movement priced as v², evolved speed settles
  around 0.08–0.10 tiles/tick on its own, well inside the clamp.

---

## Current status

- ✅ **Phenotype flows from the genome** — all 12 species source their body stats
  from a founder `Genome` via `NPC.applyGenome` (byte-for-byte behaviour-preserving).
- ✅ **The model is validated** — `react()` and `child()` are exercised by the
  `genomeDriven` test fixture and three scenarios (below).
- ⚠️ **Behaviourally inert on real species** — founder genomes leave `markers` and
  every disposition at their defaults (all `0`), so `react()` between two real
  animals returns `IGNORE`. Targeting still runs on the old string arrays
  (`HOUNDEYE_ENEMIES`, …).
- ✅ **Asexual inheritance runs** — the energy economy (`NPC.metabolic`) plus
  `tryReproduce()` → `spawnOffspring()` → `Genome.child(parent)` gives a working
  evolutionary loop for the `breeder` fixture: fed populations grow and drift
  (`PopulationGrowsWithFood`).
- ✅ **Sexual inheritance runs** — `canMateWith()` + `reproduceWith()` bear a
  crossover child; both parents pay and go on cooldown.
- ✅ **The mind evolves** — brains are inherited, mutated and crossed over
  alongside the body, and the deployed world runs a standing cohort of minded
  creatures competing beside the scripted species.
- ✅ **The economy has teeth** — size, speed and metabolism all feed the per-tick
  bill above, so the phenotype genes are under real selection: too fast starves,
  too big is expensive to move, too small is prey.
- ✅ **Genomes are portable** — `GenomeCodec` round-trips a whole creature (brain
  included) through a single whitespace-free line, so one can be exported from the
  viewer, kept as a savefile, and injected back into a live world.

---

## Plan — make the genome actually drive & evolve

> **Superseded, kept for context.** The migration below assumed the legacy
> bestiary (Zombie, Headcrab, …) would be converted species by species. The
> project went the other way: the deployed world is built entirely from
> genome-driven `TestNPC` bodies, and the bestiary is now dead weight awaiting
> deletion rather than migration. Steps 2–5 describe a path not taken; the
> *cross-cutting APIs* section below did happen, and is the part that mattered.

### Migration (behaviour → genome) — not taken

- **Step 2 · Author founder genes.** Give each species distinct `markers` and
  `dispositions`. ⚠️ This is a **deliberate behaviour change toward emergence**,
  not a preserving refactor: the old string arrays are an *arbitrary* graph;
  similarity+size `react()` cannot (and shouldn't) reproduce it exactly. We tune
  so the *interesting* dynamics survive (predators hunt smaller dissimilar prey;
  kin flock) and accept that hand-authored quirks disappear.
- **Step 3 · Rewire targeting to `react()`.** Replace `getTargets(ENEMY_STRINGS)`
  + `getClosestNPC` with: scan neighbours → `react()` each → act on the strongest
  drive. This is exactly what `TestNPC.thinkGenome()` already does — promote it
  into `NPC` as the default brain.
- **Step 4 · Wire inheritance.** At the ~5 spawn points (`Spore.explode`,
  `HeadcrabZombie.infest`/`kill`, `Headcrab.leap`, `Zombie.bite`) spawn offspring
  with `Genome.child(parent, rate)`. **This is the moment evolution turns on.**
- **Step 5 · Collapse & delete.** Remove the string arrays; optionally fold the 12
  bespoke `think()` methods into one genome-driven brain. A "species" becomes a
  cluster in marker-space.

### The loop that gives it stakes (cross-cutting APIs)

Heritable behaviour means nothing until something is selected for:

- ✅ **Energy / metabolism** — shipped, and since grown into the full economy
  documented above.
- ✅ **Reproduction** — `canMateWith(other)` + `reproduceWith()`; assortative
  mating holds lineages together.
- ⚠️ **Sensing** — *still the open one.* A creature can feel whether it is standing
  on grass (`S_FOOD`) but has **no directional food sense at all**, so foraging
  cannot steer — it can only stumble. This is why the minded cohort's dominant
  reported action is *wandering*. See the roadmap: it is the next thing to build.

### The payoff — speciation

Inheritance + assortative mating + energy-based selection → marker-space clusters
drift apart, cross-cluster mating fails (dissimilar ⇒ no mate drive), reproductive
isolation emerges, and **species form on their own** — visible directly as the
population sorting into colour clusters (`toColor()`) in snapshots.

### Suggested sequencing

Step 2+3 (a real animal thinks with its genome) → energy + asexual inheritance
(Step 4, evolution runs) → reproduction API + sexual mating → Step 5 cleanup.
Each lands as its own validated, committed slice.

## Open design decisions

1. **Marker/disposition authoring** — placing founders in marker-space so the
   starting ecosystem is interesting (not all-fight or all-ignore). A short tuning
   loop against snapshots.
2. **Asexual first, sexual later** — Step 4's spawn points are asexual; true
   speciation wants sexual mating (needs the reproduction gate).
3. **Mutation rate** — too high ⇒ no stable lineages; too low ⇒ no adaptation.
4. **Balancing** — grazing income vs metabolism cost vs breeding threshold sets
   carrying capacity and boom/crash vs stability.

---

## Tested

Genome behaviour is pinned by the scenario suite (`simtest/`, run with
`java -cp bin net.hedinger.prototype.simtest.SimTests`):

| Scenario | What it pins |
|---|---|
| `GenomeReactModel` | `react()` classifies neighbours correctly: predator attacks smaller dissimilar prey; kin are not hostile (mate/affiliate) |
| `GenomePredatorHuntsPrey` | A genome-driven predator perceives, closes on, and reaches dissimilar prey |
| `GenomeInheritance` | `mutate()` perturbs genes and `child(a,b)` crossover draws each gene from a parent |

**Last recorded run — 23 passed, 0 failed:**

```
PASS  GenomeReactModel
PASS  GenomePredatorHuntsPrey
PASS  GenomeInheritance
… (+ 20 engine/environment scenarios)
23 passed, 0 failed
```
