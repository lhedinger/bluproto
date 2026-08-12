# World generation & world building — research notes

**Question 1:** which 2D level-design algorithms could generate a better random
multi-level world than the current pipeline?
**Question 2:** what is the *world itself* missing — the world-building layer —
that would make the simulation richer?

Everything below is mapped against two hard constraints the project already
committed to: **determinism** (`seed + ordered RNG draws ⇒ same world`;
`Utils.noise2` deliberately draws no RNG, so field functions are free) and the
**tick budget** (33 t/s with headroom, measured by WorldAudit). Anything that
can be expressed as a pure function of the seed is cheap to adopt; anything
that wants global iteration must run at world-gen time, never during play.

## Where the generator stands

An honest inventory of `Worlds.demoTerrain`:

- **Surface** — independent value-noise fields thresholded per tile: one draw
  decides water, another mud, another sand, another thickets. It reads well
  locally, but the fields do not know about each other: a marsh can sit on a
  hilltop because there are no hills — there is no elevation, no moisture, no
  temperature, only per-feature noise.
- **Cave** — noise-threshold caverns, pools, graded crystal fields, natural
  pits; visually strong, but caverns are blobs of a threshold, not spaces with
  a shape history (no erosion, no worm tunnels), and corridors between
  stations are L-shaped carves that read rectilinear against organic caverns.
- **Levels** — the two z-levels are generated *independently* and then joined
  by force: link stations carve both levels flat wherever the siting grid
  lands, and `sealUnreachable` walls off whatever the flood cannot reach.
  Connectivity is repaired, not designed. Nothing above corresponds to
  anything below — no cavern under a valley, no shaft where the rock is thin.
- **The facility** — a single hand-authored plan (bands, vault, bay) fitted
  into a rock pocket. It is *good* — but it is one plan, not a space of plans.
- **The audit** — WorldAudit's flood/coverage gates are already a
  generate-and-test harness; today the only "retry" is sealing.

The through-line: everything is **local and independent**; the wins below all
come from letting parts of the world cause each other.

## Algorithms worth adopting

### 1. A coherent field stack (the Dwarf Fortress lesson)

Generate a few *base fields* first — elevation, then temperature (latitude +
elevation), then moisture (wind direction + rain-shadow against elevation),
then drainage — and classify biomes from the **joint** values instead of
independent thresholds: water collects where elevation is low and drainage
poor, reeds ring it, sand where dry, thickets where wet-and-warm. This is how
Dwarf Fortress gets terrain that feels *caused* rather than sprinkled, and it
is the single highest-leverage change available: pure functions of `noise2`,
zero RNG draws, no protocol or sim change — only `demoTerrain`'s
classification rewritten to read three fields instead of one.

### 2. Hydrology: droplet-carved rivers

With an elevation field, rivers fall out of a seeded droplet walk: start test
streams at high points, walk downhill, carve shallows/water along the path,
end in a pool or the map edge (Dwarf Fortress's "running rivers" erosion
stage, miniaturised). Rivers are the classic world-structure feature: they
connect regions, explain the shore/reed/mud bands the tile set already has,
and hand the simulation a *reason* for creature movement (see world-building
below). Deterministic: the walk draws from the seeded RNG at gen time.

### 3. Cellular-automata polish for the caves

The standard roguelike cave recipe (threshold noise, then ~3 rounds of
4-5-rule cellular automata) rounds cavern edges organically and merges
near-touching pockets. Its known weakness — isolated pockets — is already
solved here by the flood+seal pass; CA would simply leave *less* to seal.
Cheap, local, gen-time only.

### 4. Agent tunnels instead of L-corridors

Replace the L-shaped cave backbone with **directed random walkers** (drunkard
walk with directional persistence, 2-wide brush): tunnels that wander, fork,
and occasionally balloon into small chambers. Same connectivity contract
(walk from station A until station B's region is hit), organic result. This
is the cheapest visual upgrade the cave level can buy.

### 5. Vertical coherence: shared fields across levels

The multi-level-specific idea, and the one nothing off the shelf provides
directly: sample the **same** fields on both levels. Where surface elevation
is high, cave ceiling is thick (more rock); where a surface valley bottoms
out over a cave cavern, that is where a natural shaft or pit belongs; link
stations should *prefer* sites where both levels are already open instead of
carving both flat. Roguelike practice ("pick a floor tile whose tile below is
also floor, put the stairs there") is the degenerate version of this; the
field version makes the two levels feel like one geology. Ramp conventions
(east-west run, foot/top rules) stay exactly as they are — only *siting*
gets smarter.

### 6. Graph-first design for built places (Dormans / Unexplored)

The facility should be generated in two stages, the way Joris Dormans'
mission/space grammars and Unexplored's **cyclic dungeon generation** do it:
first a small *mission graph* — entrances, a guarded goal (the vault), locks
(doors) and keys (plates/buttons), and at least one **cycle** so every prize
has two routes touching it — then a geometric realisation of that graph into
the rock pocket. The current hand plan already embodies one such graph
(4 entrances, vault behind a button-guarded grate, catwalk loop); the win is
making the graph the *input*, so every seed gets a different-but-sound
facility and the door/switch wiring falls out as lock-and-key grammar output
instead of hand placement. This also scales to future ruins/outposts for free.

### 7. Room realisation: BSP now, WFC when variety matters

For turning a mission graph's rooms into floorplans, **binary space
partitioning** inside the pocket rectangle is the workhorse (rooms +
corridors, trivially deterministic). **Wave Function Collapse** (Gumin) is
the stronger tool for *interior texture* — furnishing rooms from a small
hand-drawn exemplar so pipes, crates and duct runs compose consistently —
but it wants a solvability fallback and offers no global structure, which is
exactly why the graph stage must own topology. The hybrid (graph plans,
WFC dresses) is the current state of the art for "designed-feeling"
generated levels.

### 8. Blue-noise placement everywhere things scatter

Poisson-disk (Bridson) sampling for stations, shrub stands, crystal clusters,
item drops: even coverage with no clumping artifacts, one seeded pass. The
station grid already approximates this; Poisson-disk does it without the
visible grid rhythm (the current stations sit at obvious rows — see the ramp
probe: y=20 and y=60 across the whole map).

### 9. Generate-audit-accept as the explicit loop

WorldAudit already scores worlds; the missing half is letting the generator
*use* the score: generate, audit, and if a gate fails (coverage below
threshold, region count wrong), reseal/retry with a derived sub-seed rather
than shipping whatever came out. Quality-diversity search is the academic
version; the pragmatic version is a bounded retry loop around what already
exists — determinism preserved by deriving retry seeds from the world seed.

## World building — what the simulation is missing

The terrain vocabulary is ahead of the *world dynamics*. Ranked by leverage:

1. **Water as a need.** Nothing drinks. With rivers/pools already on the map,
   thirst (a second, faster-draining reserve) would anchor territories,
   create contested ground, and give the intent layer a natural `SEEK_WATER`
   — the single biggest behavioural payoff per line of code.
2. **Nutrient closure.** Corpses are scavenged but return nothing to the
   ground. A corpse (and dung, if grazing ever excretes) bumping local tile
   fertility closes the loop: death feeds grass feeds grazers — kill sites
   become meadows. One tile write on corpse expiry.
3. **Nests as fixtures.** Pheromone nests exist as scent; a *physical* nest
   site (fixture-class, like switches) gives creatures a home to return to,
   brood to defend, and the fixture sense a second target class. Site
   fidelity is the precondition for territory, and territory for most
   interesting social behaviour.
4. **Seasons / day-night.** A slow sine over the tick count modulating
   regrowth rate (season) and LOS range (night) — pure functions, no RNG,
   deterministic — would produce migration pressure and nocturnal niches
   (the fungus glow is *already* the night asset waiting for this).
5. **Warmth at vents.** Geothermal vents exist as tiles; making them mild
   warmth sources (faster egg/brood development nearby, or the only
   comfortable cave spots in winter) turns decoration into ecology and gives
   the underground cohort its own geography of value.
6. **Weather.** Rain events raising pool levels and softening mud (speed
   factors already exist per tile) — cosmetic first, ecological later.
7. **Disease.** A third mortality force besides predation/starvation —
   density-triggered, marker-lineage-correlated — is the standard missing
   regulator in alife ecosystems (population booms currently end only in
   grass collapse or the steward's ceiling).
8. **Alarm calls.** The legacy `Sound` entity and the `A_ALARM` action pose
   already exist; wiring hearing back in for minded creatures would make the
   first *communication* channel almost free.

## Suggested order

1. Field stack (elevation/moisture/temperature classifier) — foundation.
2. Rivers + thirst — terrain and behaviour meet.
3. Vertical coherence for station/shaft siting — the multi-level payoff.
4. CA polish + walker tunnels — cave feel.
5. Facility as mission graph + BSP realisation — every seed its own base.
6. Nutrient closure + nests — the ecology flywheel.
7. WFC interiors, seasons, vents-as-warmth, disease — as appetite allows.

## Sources

- [Unexplored's Secret: Cyclic Dungeon Generation (Game Developer)](https://www.gamedeveloper.com/design/unexplored-s-secret-cyclic-dungeon-generation-)
- [Dungeon Generation in Unexplored (BorisTheBrave)](https://www.boristhebrave.com/2021/04/10/dungeon-generation-in-unexplored/)
- [Graph Rewriting for Procedural Level Generation (BorisTheBrave)](https://www.boristhebrave.com/2021/04/02/graph-rewriting/)
- [Dormans' mission/space grammar dungeon generator (GraphDungeonGenerator)](https://github.com/amidos2006/GraphDungeonGenerator)
- [Adventures in level design: generating missions and spaces (Dormans)](https://www.researchgate.net/publication/228994305_Adventures_in_level_design_Generating_missions_and_spaces_for_action_adventure_games)
- [Dwarf Fortress world generation (elevation/rainfall/temperature/drainage, erosion, rain shadow)](https://dwarffortresswiki.org/index.php/World_generation)
- [Cellular Automata Method for Generating Random Cave-Like Levels (RogueBasin)](https://www.roguebasin.com/index.php?title=Cellular_Automata_Method_for_Generating_Random_Cave-Like_Levels)
- [Combining constructive dungeon generation with WaveFunctionCollapse in top-down 2D games](https://www.researchgate.net/publication/348569324_Combining_Constructive_Procedural_Dungeon_Generation_Methods_with_WaveFunctionCollapse_in_Top-Down_2D_Games)
- [WFC + BSP for procedural dungeons (Shaan Khan)](https://shaankhan.dev/blog/wfc-and-bsp-for-procedural-dungeons-2021)
- [A Hybrid Cyclic-Graph & WFC Method for Designer-Guided Generation](https://blog.ptidej.net/content/files/2025/11/_ICSE_GAS_Laurent____Graph_WFC_Procedural_Gen-1_compressed.pdf)
- [Multi-level roguelike dungeons: z-levels and stair placement (Trystan)](http://trystans.blogspot.com/2011/09/roguelike-tutorial-07-z-levels-and.html)
- [Eco-Simulator: neural-agent ecosystem with resource requirements](https://github.com/BLayman/Artificial-Life-Simulator)
