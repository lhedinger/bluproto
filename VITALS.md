# VITALS — the body's account books

**Status: implemented** (hard rollout, both cohorts at once). This document
rebalances the timing and energy parameters around eating, drinking and
reproduction into one coherent model; the [migration table](#what-changes-against-today)
names every mechanical change against the previous engine. The five §9
questions were resolved as proposed: worse-need satiation gate, a crawl
reserve at collapse, the linear mass proxy, hunger-as-level, and the single
hard rollout.

The premises, fixed up front:

1. **Mass and metabolic rate sit at the centre.** Every capacity and every rate
   derives from these two, and both stay what they already are: variable,
   heritable body attributes (`Genome.size`, `Genome.metabolism`).
2. **Every action takes time to reach its impact.** Nothing refills or lands
   instantly; eating, drinking, courting are rates held over ticks, and any of
   them can be interrupted, keeping whatever partial effect had accrued.
3. **Hunger, thirst, energy and health are four different things.** Thirst is a
   need that rises with time; hunger rises with what the body actually burns
   (see the §8 amendment); both influence energy and health.
   Energy drives what a body can *do*; health alone decides whether it lives.

---

## 1. The two attributes at the centre

| attribute | gene | role |
|---|---|---|
| **mass** `m` | `Genome.size` (normalised: reference body = 1.0) | scales every **capacity** — stomach, water reserve, energy tank, meal size, offspring cost — linearly |
| **metabolic rate** `R` | `Genome.metabolism` (neutral = 1.0) | scales every **rate** — need rise, intake speed, energy regeneration, healing — as `R · m^0.75` (Kleiber, as today) |

Because capacities grow with `m` but rates only with `m^0.75`, a big body's
needs rise **slower** per unit of reserve — large animals fast longer between
meals but need bigger meals — exactly the story the current energy tank tells,
now applied uniformly to all four books.

`R` is the **pace-of-life gene**. A hot metabolism runs everything faster:
needs return sooner, but it also eats, drinks, regenerates and heals faster.
A slow one coasts cheaply and recovers slowly. Selection prices the whole
bundle; there is no free knob.

## 2. The four vitals

| vital | range | rises / falls | what it is |
|---|---|---|---|
| **hunger** | 0 (sated) → 1 (starving) | rises as regeneration **drains the stomach** (no clock of its own — §8); falls only by **eating** | the need for food, and the fuel gauge |
| **thirst** | 0 (slaked) → 1 (parched) | same shape, **twice the rate** of hunger; falls only by **drinking** | the need for water |
| **energy** | 0 → capacity `∝ m` | spent by **actions**; **regenerates** from satiation | the action budget |
| **health** | 0 → 100 | worn by wounds and deprivation; regenerates under low needs | the life gate |

Invariants worth stating baldly:

- **Food and water never touch energy directly.** Eating fills the stomach
  (lowers hunger); regeneration then converts the stomach's contents into
  energy *over time*, draining the meal it is minted from, one for one — so
  **energy is food-backed**: a body can never bank more than it actually ate
  (§8). Today `graze()` deposits straight into the action budget — that
  shortcut is what this design removes.
- **Energy at zero is collapse, not death.** A drained body can barely move or
  act, and lies where it is while regeneration (if it is fed and watered) or
  deprivation (if it is not) decides what happens next. Rescue is possible.
- **Only health at zero kills.** Starvation and dehydration kill the way they
  do in life: by wearing health down while the need is pegged, so the corpse
  still names its cause. Predation stays a health matter as it already is.

## 3. The relationship graph

```
                        ┌────────────── GENOME ──────────────┐
                        │    mass m           metabolism R   │
                        └──────┬────────────────────┬────────┘
              capacities ∝ m   │                    │   rates ∝ R · m^0.75
      (stomach, water, energy  │                    │   (need rise, intake,
       tank, offspring cost)   ▼                    ▼    regen, healing)
             ┌───────────────────────────────────────────────┐
  eating ──▶ │            NEEDS  (rise with time)            │ ◀── drinking
  (timed,    │     HUNGER (period 2T)     THIRST (period T)  │     (timed,
  interrupt- └───────┬───────────┬───────────┬───────┬───────┘     interrupt-
  ible)              │           │           │       │             ible)
                     │   pegged need erodes  │       │
                     │   (deprivation)       │       │  satiation gates
                     ▼                       ▼       ▼  regeneration
             ┌──────────────┐    vigor    ┌─────────────────────┐
             │    HEALTH    │────────────▶│       ENERGY        │
             │  final gate: │  healthy =  │  the action budget: │
             │  0 = death   │  faster     │  move, grab, bite,  │
             └──────▲───────┘  regen      │  grow, breed        │
                    │                     └──────────┬──────────┘
              wounds (combat,                        │  0 = collapse,
              hazards) + deprivation                 │  never death
                                                     ▼
                                             ACTIONS (all timed)
```

In formulas, for review rather than for code:

```
hunger'  = +  minted energy / stomach                 (the drain; no clock — §8)
             resting-only body: = R · m^-0.25 / T_hunger  by the stomach identity
thirst'  = +  R · m^-0.25 / T_thirst                  (T_hunger = 2 · T_thirst)
eating   = −  intake · m / stomach     while the act runs (grazers also patch-limited)
drinking = −  intake · m / reserve     while at water

energy'  = +  REGEN · R · m^0.75 · satiation · vigor  −  action costs
             satiation = 1 − max(hunger, thirst)       (the worse need governs)
             vigor     = health / 100                  (healthy bodies regen faster)
action costs: movement m·v² per tick, grip, bite, growth, breeding — as priced today

health'  = −  wounds (bites, hazards)
           −  deprivation trickle while hunger ≥ ~0.95 or thirst ≥ ~0.95
           +  slow mend · R  only while hunger < 0.5 AND thirst < 0.5
```

Hunger and thirst thus influence energy (through the satiation gate on
regeneration) *and* health (through the deprivation trickle); health closes
the loop by scaling energy regeneration through vigor — an unhealthy body is
also a listless one, which is what makes wounds and deprivation compound
instead of being independent ledgers.

## 4. Every action takes time

An act is a **rate held over ticks**, never an event:

- **Drinking**: standing at a shore lowers thirst at a fixed rate; a full
  refill from parched is a deliberate stop measured in seconds. Walking off
  mid-drink keeps the partial refill. (Drinking already works this way.)
- **Eating**: same shape. A grazer's bites are additionally limited by what
  the patch under it holds (as today); a predator gorging at a kill and a
  scavenger at a carcass draw at their own intake rates. A full meal takes
  roughly **twice a full drink** — eating is the longer, riskier stop.
- **Breeding**: courtship/budding is a held act of several seconds during
  which the body stands committed (interruptible by fleeing); the offspring
  costs energy `∝ m` when the act completes, not when it starts.
- **Combat**: bites already land per-tick with reach checks; unchanged.

Interruption is the *point* of the durations: a drinking creature surprised by
a predator abandons the shore half-slaked; a gorging predator driven off a
kill got only what it ate. Time-to-impact is what makes those moments exist.

## 5. The timing ledger (reference body, neutral metabolism)

The one rhythm anchor, per the design brief: **a sated body's appetite returns
in twice the time its thirst does.** Everything else hangs off it.

| clock | period | notes |
|---|---|---|
| thirst, slaked → parched | ~4.5 min (9 000 ticks) | today's hydration period, kept |
| hunger, sated → starving | ~9 min (18 000 ticks) | 2 × thirst, the anchor — at rest; exertion shortens it (§8) |
| desire to drink returns (need ≥ 0.5) | ~2.2 min | seek thresholds at 0.5 |
| desire to eat returns (need ≥ 0.5) | ~4.5 min | twice the drink interval ✔ |
| full drink, uninterrupted | ~4 s | |
| full meal, uninterrupted | ~8 s | patch/carcass permitting |
| energy, empty → full (fed, watered, healthy) | ~1–2 min | the satiation gate scales this down |
| health, mend under low needs | ~several min for a bad wound | slow on purpose |
| deprivation, pegged need → death | ~2–3 min | via health, cause-tagged |
| breeding act | ~5 s | plus energy threshold and low-needs gate |

Scaling reminders: periods stretch with `m^0.25` (big bodies cycle slower) and
shrink with `R` (hot metabolisms cycle faster). All durations are stated at
33 ticks/s.

## 6. Reproduction under the new books

Reproduction stops being an energy checkout and becomes a **surplus signal**:

- **Eligibility**: energy above a threshold `∝ m`, **and** hunger < 0.5,
  **and** thirst < 0.5, **and** health above a floor. A parched, starving or
  badly wounded body does not court — which is what couples reproduction to
  the whole vitals loop instead of just the tank.
- **The act takes time** (§4) and pays its energy cost `∝ m` on completion.
- **Cooldown scales with mass**: today's flat 100 ticks (~3 s) becomes a
  fraction of the childhood the offspring itself will spend growing
  (`growthTicks`), so big slow bodies are also slow breeders — the same two
  constants that already set growth keep the whole life cycle in proportion.

## 7. What changes against today

| today | target |
|---|---|
| `graze()` feeds the energy tank directly | eating lowers **hunger**; energy regenerates from satiation over time |
| `energy <= 0` kills instantly (`"starvation"`) | energy 0 = collapse (no meaningful action); **only health 0 kills**, deprivation erodes health with its cause attached |
| `hydration` a bolt-on 0..1 with its own drain | **thirst**, a first-class need, sibling of hunger, twice hunger's rate |
| no hunger stat — appetite is "tank not full" | **hunger**, a first-class need; predators eat by appetite, not by tank headroom |
| health = 100, wounds only, never recovers | health mends slowly under low needs; erodes under pegged needs; scales energy regen (vigor) |
| repro: threshold 2.0, cost 1.0, cooldown 100 ticks | threshold/cost `∝ m`; timed act; low-needs + health gates; cooldown `∝ growthTicks` |
| `Genome.metabolism` scales the resting burn only | scales **every** rate: need rise, intake, regen, healing — one pace-of-life gene |
| minds sense `energy`, `thirst`, `health` | add `S_HUNGER`; seeded reflexes forage on hunger and drink on thirst, not on the tank |

Deliberately **unchanged**: Kleiber `m^0.75` scaling, movement priced at
`m·v²`, growth (`GROWTH_RATE`, birth fraction), the nutrient ledger (what is
eaten still comes from grass/carrion and returns to the ground), combat's
size-scaled bites, and determinism (all of this is closed-form per tick, no
new RNG).

## 8. Later: temperature and light

ROADMAP 2b (temperature/light fields + day/night clock) plugs into this model
at exactly one seam: **multipliers on the need clocks and the regeneration
rate**. Heat accelerates thirst; cold accelerates hunger (a body burning to
stay warm) and slows mending; darkness slows intake for sighted foragers.
None of them need new vitals — they modulate the periods in §5, which is why
the periods are named constants rather than folded into magic drain values.

## 9. Open questions — resolved

All five went as proposed, and are now what the code does:

1. **Satiation gate**: `1 − max(hunger, thirst)` — the worse need governs.
2. **Collapse**: a crawl reserve (5% of the tank, `CRAWL_RESERVE`): below it
   the body can only crawl at a quarter of its top speed — it cannot bite,
   grab, breed, or keep a grip (a collapsed captor's captive walks free) —
   but it can still reach food two tiles away and recover.
3. **Mass**: the linear radius-normalised `bodyMass()` stays.
4. **Stomach**: hunger-as-level, with a lifetime `swallowed` ledger for
   probes and the scenario suite; no digestion queue.
5. **Rollout**: both cohorts at once. Measured on seed 42 over 40k ticks: the
   ecology settles with every death cause live (starvation, thirst,
   predation, combat), a visible struggling minority (tens collapsed or
   deprived at any instant), scavengers contracting to their carrion-limited
   floor as designed — and the minded cohort now reaching its steward
   ceiling, since deprivation kills slower than the old energy-zero switch;
   whether to raise that ceiling is a question for the deployed world.

## 8. Amendment — energy is food-backed

The model above regenerated energy from the *state* of being fed: satiation
gated the mint, but nothing drained the stomach as energy was minted. The
constants made the exchange rate `REGEN · T_hunger / stomach = 12` — twelve
units of tank energy per unit of food actually eaten — and the herd found the
seam. Selection drove metabolism to triple the reference (net income scales
linearly with `R`), collapsed mate choice, drifted lineages toward budding,
and the population exploded on land it had visibly stripped, because a
breeding's worth of energy cost a third of a vegetation unit of real grass.

The repair is one mechanism and one identity:

- **The mint drains the meal.** Every unit of energy regeneration adds
  `minted / stomach` to hunger, so the tank can never bank more than the body
  ate. Conversion that would overflow a full tank does not run (a sated body
  does not burn its meal for nothing). Food, stomach and tank are now one
  conserved ledger; grass prices what it says.
- **Hunger needs no clock.** With the drain in place the resting burn alone
  empties a stomach, so the old timed rise became a redundant proxy and was
  removed. The stomach is sized to the anchor instead:
  `stomach = base burn · T_hunger` (9 units at reference mass), which makes a
  full stomach exactly one hunger period of resting fuel — the rhythm anchor
  (§5) holds *by construction* for a resting body, and exertion now buys
  appetite the clock could never price.

Consequences worth stating baldly: a parching body stops digesting (the
worse-need gate throttles the mint), so hunger stalls while thirst pegs; a
hot metabolism pays for its pace in appetite rather than out-breeding the
food supply; and reproduction is bounded by grazing income rather than by
the cooldown alone. Pinned by the `EnergyIsFoodBacked` scenario, which fails
against the satiation-state mint on both counts.
