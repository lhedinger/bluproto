# Genome

The heritable core of the ecosystem. An entity's body **and** its behaviour are
encoded in a `Genome` (`src/net/hedinger/prototype/entities/Genome.java`) that is
passed to offspring with mutation — so relationships are *learned by the lineage*
rather than hardcoded, and species emerge as clusters rather than Java classes.

Relationships are **emergent, not authored**: how one entity reacts to another
falls out of *how similar they are* + *their relative size* + *this genome's
dispositions*. No "A hates B" table.

---

## The model — three layers

| Layer | Fields | Role |
|---|---|---|
| **Phenotype** (the body) | `size`, `speed`, `turnRate`, `losRange`, `losFov`, `metabolism`, `maxAge` | Drives physics & perception |
| **Markers** (recognition) | `markers[3]` ∈ [0,1] | A neutral "barcode" — no physical effect. Two entities are *similar* when markers are close. Mapped to RGB (`toColor()`) so similarity is visible |
| **Dispositions** (behaviour) | `predatory`, `xenophobia`, `gregariousness`, `boldness`, `mateThreshold` | Response weights that turn a perceived neighbour into a drive |

Keeping markers (who I recognise) separate from dispositions (how I feel about
the recognised) lets recognition and behaviour evolve independently.

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

## Current status

- ✅ **Phenotype flows from the genome** — all 12 species source their body stats
  from a founder `Genome` via `NPC.applyGenome` (byte-for-byte behaviour-preserving).
- ✅ **The model is validated** — `react()` and `child()` are exercised by the
  `genomeDriven` test fixture and three scenarios (below).
- ⚠️ **Behaviourally inert on real species** — founder genomes leave `markers` and
  every disposition at their defaults (all `0`), so `react()` between two real
  animals returns `IGNORE`. Targeting still runs on the old string arrays
  (`HOUNDEYE_ENEMIES`, …).
- ❌ **Inheritance wired to zero spawn points** — `child()` exists but nothing calls
  it, so nothing evolves yet.

---

## Plan — make the genome actually drive & evolve

### Migration (behaviour → genome)

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

- **Energy / metabolism** — grass → energy → survival. The `metabolism` gene
  exists but is unused; add an `energy` pool, per-tick drain, and `starve()`.
- **Reproduction** — `canMate(other)` (mate drive + energy threshold + cooldown)
  and `reproduce()` → `child(mom, dad, rate)`. **Assortative mating** (breed with
  the similar) is what holds lineages together.
- **Sensing** — so genome-driven foraging can steer toward food, not stumble onto it.

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
