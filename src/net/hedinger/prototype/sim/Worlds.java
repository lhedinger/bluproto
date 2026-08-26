package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.Perf;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * Deterministic world factories for headless hosting: seed in, living world
 * out. The demo world the web server serves is a living, evolving ecosystem —
 * a fertile grassland grazed by breeding herbivores, hunted by predators, kept
 * inside sane population bounds by a {@link WorldSteward} so a public,
 * always-on world never dies out or swarms.
 *
 * <p>Uses {@code TestNPC} fixtures for the population — deliberately: the
 * roadmap retires the legacy bestiary in favour of genome-driven species, and
 * the fixtures are today's cleanest bodies. When real species graduate out of
 * the fixture package, this factory swaps them in.
 */
public final class Worlds {

	private Worlds() {
	}


	/** Herbivore "species": small, grazing prey — distinct marker barcodes drive
	 *  distinct procedural bodies/colours; all metabolic breeders (they evolve). */
	private static net.hedinger.prototype.entities.Genome[] herbivoreSpecies() {
		// Warm/cool hues, deliberately NOT green — herbivores should read clearly
		// against the green meadow, not camouflage into it.
		double[][] markers = {
				{ 0.90, 0.72, 0.40 }, // sand
				{ 0.55, 0.72, 0.92 }, // sky blue
				{ 0.74, 0.46, 0.86 }, // violet
				{ 0.95, 0.60, 0.35 }, // amber
		};
		double[] sizes = { 7, 8, 6, 9 };
		// Neutral metabolism efficiency (META_REF): the size-scaled energy model
		// does the work — reserve, resting burn and fasting endurance all follow
		// body size, so these small grazers hold a few minutes of reserve and the
		// bigger ones a little more. The herd stays food-limited: it booms where the
		// grass is rich and thins where grazing has stripped it.
		return species(markers, sizes, 0.018, 0.03, 0.02);
	}

	/** Predator "species": bigger, faster hunters — reddish barcodes so they read
	 *  as menacing against the green prey. Metabolic; they hunt, breed, starve. */
	private static net.hedinger.prototype.entities.Genome[] predSpecies() {
		double[][] markers = {
				{ 0.90, 0.20, 0.22 }, // red hunter
				{ 0.78, 0.28, 0.48 }, // crimson hunter
		};
		// Apex-sized, at the top of the band every genome is clamped to
		// (Genome.SIZE_MAX): paired with the "up to my own size" hunting rule this
		// makes a founder hunter able to take ANY creature in the world, including a
		// minded one that has drifted to the largest body a genome can express.
		double[] sizes = { 20, 18 };
		// Neutral metabolism efficiency (META_REF): the size-scaled model gives
		// these big hunters a large reserve and a long fasting endurance (bigger
		// body, bigger tank), so a predator drains gently between kills. Running
		// prey down is what costs it: movement is charged as mass * v^2, so a
		// full-speed pursuit burns far harder than its patrol and a long fruitless
		// chase still thins it.
		net.hedinger.prototype.entities.Genome[] out = species(markers, sizes, 0.045, 0.055, 0.02);
		for (net.hedinger.prototype.entities.Genome g : out) {
			// Say so in the genome. These have always hunted -- thinkPredator does the
			// work -- but the genome described a herbivore with no appetite for it,
			// which meant nothing downstream could tell a hunter from a grazer. The
			// body plan reads `diet`, and `predatory` is a disposition that ought to
			// match the animal carrying it; leaving it at zero on the world's actual
			// predators made the gene decorative. Behaviour is unaffected: a hunter
			// runs thinkPredator, not the react() weights this feeds.
			g.clade = net.hedinger.prototype.entities.Genome.Clade.PREDATOR;
			g.predatory = 0.9;
		}
		return out;
	}

	/** Minded "species": a small cohort whose behaviour comes from a fully-random
	 *  evolvable {@link net.hedinger.prototype.entities.Brain}, not a hardcoded rule.
	 *  Random bodies (so a role can emerge — a big one may learn to hunt, a small one
	 *  to graze) with a distinct greenish barcode, and a random brain each. They
	 *  compete inside the same world as the scripted species; most will flounder at
	 *  first (a random mind rarely feeds itself), which is the point of watching. */
	private static net.hedinger.prototype.entities.Genome[] mindedSpecies(int count) {
		net.hedinger.prototype.entities.Genome[] out =
				new net.hedinger.prototype.entities.Genome[count];
		for (int i = 0; i < count; i++) {
			out[i] = mindedGenome(i);
		}
		return out;
	}

	/** One founder minded genome: random dispositions, markers and body inside the
	 *  sane size band, and the hand-written {@link #starterBrain()} — a minimal
	 *  forager that mutation and survivor-seeding then refine. A fully-random brain
	 *  was tried first (Phase 3/4): it never stumbled onto feeding, so selection had
	 *  no gradient to climb. Seeding a viable-but-crude brain gives evolution a
	 *  foothold to improve from, while every other gene stays random. */
	static net.hedinger.prototype.entities.Genome mindedGenome() {
		return mindedGenome(0);
	}

	/**
	 * As {@link #mindedGenome()}, but picks the founder's starting brain by index
	 * so the cohort does not all begin with the same idea. Every third founder is a
	 * {@link #hitchhikerBrain() hitch-hiker} rather than a plain forager, so both
	 * strategies are in the world from the first tick and can be watched competing
	 * — which is the whole point of the minded cohort.
	 *
	 * <p>The index only chooses a (fixed, RNG-free) program, so the deterministic
	 * stream is identical to drawing every founder the old way.
	 */
	static net.hedinger.prototype.entities.Genome mindedGenome(int index) {
		net.hedinger.prototype.entities.Genome g = net.hedinger.prototype.entities.Genome.random();
		g.size = 5 + Utils.random() * 12; // 5..17: room for both grazer and hunter builds
		g.speed = 0.04 + Utils.random() * 0.03;
		g.metabolism = 0.02;
		g.brain = (index % 3 == 2) ? hitchhikerBrain() : starterBrain();
		return g;
	}

	/**
	 * A minimal hand-written forager brain — the warm seed the minded cohort starts
	 * from, so it survives long enough for selection to have something to work on.
	 *
	 * <p>It is written as <b>intents</b>, and that is what makes it short. The whole
	 * of its living is one actuator: {@code A_SEEK = forage} sends it looking for
	 * grass, walks it there at a cheap pace, and grazes when it arrives — steering,
	 * searching, throttle and eating, from one instruction. When something bigger
	 * comes close the same slot flips to {@code -threat} and the identical machinery
	 * runs it away flat out. It breeds when it can afford to.
	 *
	 * <p>Nine instructions where the motor-level version needed fourteen, and under
	 * one-instruction-per-tick that is not a tidiness win: it is a third off the
	 * lineage's reaction time. That is the trade intents exist to offer — see
	 * {@link net.hedinger.prototype.entities.AgentIO#A_SEEK}.
	 *
	 * <p>Still crude on purpose. Mutation and survivor-seeding are meant to sharpen
	 * it — a better threat threshold, hunting, using the waypoint it never marks —
	 * which is the whole experiment.
	 */
	static net.hedinger.prototype.entities.Brain starterBrain() {
		final int SET = net.hedinger.prototype.entities.Brain.SET;
		final int MOV = net.hedinger.prototype.entities.Brain.MOV;
		final int ADD = net.hedinger.prototype.entities.Brain.ADD;
		final int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
		final int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
		final int GT = net.hedinger.prototype.entities.Brain.GT;
		final int SKIPZ = net.hedinger.prototype.entities.Brain.SKIPZ;
		int[][] code = {
				{ SET, 1, 6, 0 }, // r1 = 0.1 (const[6]) -- the forage intent
				{ SET, 5, 7, 0 }, // r5 = 0.25 (const[7]) -- a cheap amble; movement costs v^2
				// The drink reflex: parched outranks foraging (a threat, below,
				// still outranks both). SEEK_WATER lives at magnitude >= 9, so the
				// intent value is composed as 4+4+2 from the const pool -- the warm
				// seed knows how to drink; evolution tunes or loses it from here.
				{ SENSE, 7, net.hedinger.prototype.entities.AgentIO.S_THIRST, 0 }, // r7 = how dry
				{ SET, 8, 8, 0 }, // r8 = 0.5 (const[8]) -- the dry line
				{ GT, 9, 7, 8 }, // r9 = parched?
				{ SET, 10, 11, 0 }, // r10 = 4 (const[11])
				{ ADD, 10, 10, 10 }, // r10 = 8
				{ SET, 11, 10, 0 }, // r11 = 2 (const[10])
				{ ADD, 10, 10, 11 }, // r10 = 10 -- names the water intent
				{ SKIPZ, 9, 0, 0 }, // sated -> keep foraging
				{ MOV, 1, 10, 0 }, // parched -> steer to water instead
				{ SENSE, 2, net.hedinger.prototype.entities.AgentIO.S_THREAT_PROX, 0 },
				{ SET, 3, 7, 0 }, // r3 = 0.25 (const[7]) threat threshold
				{ GT, 4, 2, 3 }, // r4 = something bigger is close?
				{ SKIPZ, 4, 0, 0 }, // nothing near -> keep foraging
				{ SET, 1, 1, 0 }, // r1 = -1 (const[1]) -- flee the threat
				{ SKIPZ, 4, 0, 0 }, // ...and only then
				{ SET, 5, 9, 0 }, // r5 = 1.0 (const[9]) -- run flat out
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_SEEK, 1, 0 }, // where, and what to do there
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_THROTTLE, 5, 0 }, // how hard: the mind's call
				{ SET, 6, 9, 0 }, // r6 = 1.0 (const[9])
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_MATE, 6, 0 }, // breed when well-fed
		};
		return new net.hedinger.prototype.entities.Brain(code);
	}

	/**
	 * A hitch-hiker: the forager's mirror image. Where the forager turns AWAY from
	 * anything bigger than itself, this one turns TOWARDS it and clings on — the
	 * same two sensors, read with the opposite sign.
	 *
	 * <p>Riding is a real strategy rather than a novelty. A voluntary passenger
	 * pays half metabolism, pays nothing at all for movement (the host covers the
	 * ground, and a load is billed to whoever is carrying it), and is not frozen —
	 * it keeps grazing and breeding while aboard. The catch is that "bigger than
	 * me" includes predators, so a hitch-hiker courts exactly the creatures most
	 * likely to eat it, and it feeds only from whatever tile its host happens to
	 * be standing on.
	 *
	 * <p>Boarding needs contact: {@code attachTo} refuses a host further away than
	 * the two bodies touching, which is why this steers toward its target instead
	 * of merely holding the actuator down and hoping.
	 *
	 * <p>Note that a juvenile is 35% of its adult size, so a young hitch-hiker's
	 * own parent counts as "bigger" — a lineage running this brain will be seen
	 * riding its mothers until it grows out of them.
	 */
	public static net.hedinger.prototype.entities.Brain hitchhikerBrain() {
		final int SET = net.hedinger.prototype.entities.Brain.SET;
		final int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
		final int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
		final int GT = net.hedinger.prototype.entities.Brain.GT;
		final int MOV = net.hedinger.prototype.entities.Brain.MOV;
		final int SKIPZ = net.hedinger.prototype.entities.Brain.SKIPZ;
		int[][] code = {
				{ SET, 1, 9, 0 }, // r1 = 1.0 (const[9]) -- seek the THREAT channel, i.e.
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_SEEK, 1, 0 }, // ...ride what's bigger
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_THROTTLE, 1, 0 }, // chase it down
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_EAT, 1, 0 }, // graze, aboard or not:
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_MATE, 1, 0 }, // a seek-threat doesn't feed you
				{ SENSE, 2, net.hedinger.prototype.entities.AgentIO.S_THREAT_PROX, 0 }, // r2 = how close
				{ SET, 3, 7, 0 }, // r3 = 0.25 (const[7]) boarding threshold
				{ GT, 4, 2, 3 }, // r4 = something bigger is within reach?
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_ATTACH, 4, 0 }, // cling; 0 lets go
		};
		return new net.hedinger.prototype.entities.Brain(code);
	}

	/**
	 * The genome for the steward's next minded reseed, under survivor-seeding: a
	 * mutated child of the longest-lived minded creature currently alive. Living
	 * longest <em>is</em> the fitness — a metabolic creature that can't feed itself
	 * starves, so the oldest one alive is the one coping best — so the cohort's
	 * reseeds descend from the current survival champion and inherit its (mutated)
	 * brain, rather than starting from scratch each death. Falls back to a fresh
	 * random genome only when the whole cohort has died out, so a total wipe can't
	 * stall on nothing to copy.
	 */
	public static net.hedinger.prototype.entities.Genome mindedReseedGenome(World w) {
		TestNPC best = null;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof TestNPC t && t.isMinded() && !t.isDead() && !t.isRemoved()
					&& t.getGenome() != null && (best == null || t.getAge() > best.getAge())) {
				best = t;
			}
		}
		if (best == null) {
			return mindedGenome(); // cohort wiped out: start a fresh random lineage
		}
		return net.hedinger.prototype.entities.Genome.child(best.getGenome(), 0.08); // inherit + mutate
	}

	private static net.hedinger.prototype.entities.Genome[] species(double[][] markers, double[] sizes,
			double speedLo, double speedHi, double metabolism) {
		net.hedinger.prototype.entities.Genome[] out =
				new net.hedinger.prototype.entities.Genome[markers.length];
		for (int i = 0; i < markers.length; i++) {
			net.hedinger.prototype.entities.Genome g = new net.hedinger.prototype.entities.Genome();
			g.markers = markers[i];
			g.size = sizes[i];
			g.speed = speedLo + (speedHi - speedLo) * (markers.length == 1 ? 0 : i / (double) (markers.length - 1));
			g.turnRate = 5;
			g.metabolism = metabolism;
			out[i] = g;
		}
		return out;
	}

	/**
	 * Default world size (tiles). Large enough for real biomes and a proper
	 * underground network; {@link WorldAudit} verifies the whole space stays
	 * connected and that the sim keeps far more than real-time headroom here. The
	 * one-time startup bake of the ground layers fits the deploy VPS's small
	 * ({@code -Xmx512m}) heap because the procedural tile sprites are shared/cached
	 * (see {@code ProcTiles}) and each level is rendered once into a single image
	 * then sliced (see {@code LayerBaker}) — so bake memory is bounded by distinct
	 * tile shapes, not map area. {@code WORLD_COLS}/{@code WORLD_ROWS} (see
	 * {@code ServerMain}) override this without a rebuild.
	 */
	static final int COLS = 144, ROWS = 88;

	/** Level indices. The engine treats a HIGHER index as physically UP (a HOLE
	 *  drops you to the level below, index-1; a RAMPUP climbs to index+1), so the
	 *  open-air surface must sit ABOVE the cave: surface is the higher index. */
	/**
	 * The three floors, bottom to top.
	 *
	 * <p>They are numbered rather than named in the engine, and the numbering
	 * only runs upward — {@code lvl + 1} is up, and there is no index below
	 * zero. So a floor UNDER the caves cannot be appended; it has to take index
	 * zero and push the other two up, which is why these constants changed
	 * together and why nothing else had to. Every reference in the world builder
	 * already went through these names, so the renumbering is the constants and
	 * the world's depth, and no coordinate anywhere needed touching.
	 */
	static final int DEEP_Z = 0, CAVE_Z = 1, SURFACE_Z = 2;

	/** How many drones the facility berths, and therefore how many charge pads
	 *  are cut into its deck. They share one standing order rather than dividing
	 *  the work: the steward recounts every tick and drops the order the moment
	 *  the target is met, so four machines converge on it four times as fast and
	 *  stop together. Splitting the cohorts between them would need a second
	 *  scoreboard, which is the one thing the order was designed not to have. */
	public static final int DRONE_RANK = 4;

	/**
	 * The demo world's terrain — same seed, same tiles, same fertility, no
	 * creatures: an exact twin of {@link #demo}'s ground, from which the
	 * server bakes the static layer images (one per level). The only entities
	 * are the buried installation's doors (structural furniture, part of the
	 * terrain's story rather than its population). Two levels:
	 *
	 * <ul>
	 *   <li><b>Level 0 — the surface:</b> a patchwork of biomes inside a rocky
	 *       rim — meadow, reed-fringed water, marsh, dry badlands with sand
	 *       pans, sight-blocking thickets, and stone-and-scree highlands — laid
	 *       out from coordinate noise.</li>
	 *   <li><b>Level 1 — underground:</b> solid rock with carved caverns,
	 *       subterranean pools, and bioluminescent fungus beds skirting
	 *       them.</li>
	 * </ul>
	 *
	 * The levels are linked by a few two-way ramps and a few open pits (holes).
	 * Layout is sampled from {@link Utils#noise2} (deterministic, draws no RNG),
	 * so the terrain is fully reproducible and does not perturb the entity RNG.
	 */
	public static World demoTerrain(long seed) {
		return demoTerrain(seed, COLS, ROWS);
	}

	/**
	 * The demo terrain at an arbitrary size. Biomes are sampled from the same
	 * coordinate noise (so a bigger map is more of the same world, not a
	 * different one), and the two levels are wired together by
	 * {@link #connectLevels} — which places underground links adaptively so the
	 * whole walkable space stays one connected region at any size (verified by
	 * {@link WorldAudit}).
	 */
	public static World demoTerrain(long seed, int cols, int rows) {
		Utils.seed(seed);
		Perf.stopwatch = new StopWatch();

		World w = new World(cols, rows, 3);

		// ---- the surface: biomes inside a rocky boundary ----
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				boolean border = x < 2 || y < 2 || x >= cols - 2 || y >= rows - 2;
				double elev = Utils.noise2(x, y, 0.055);
				double moist = Utils.noise2(x + 500, y + 300, 0.075);
				double detail = Utils.noise2(x + 950, y + 640, 0.16);
				Tile.TileType t;
				double fert;
				if (border || elev > 0.87) {
					t = Tile.TileType.TYPE_WALL;
					fert = 0; // rocky rim + occasional highland outcrops (the elevation
					// noise means ~0.67, so this high threshold keeps highlands a rare
					// accent instead of walling off half the map)
				} else if (elev > 0.845) {
					t = Tile.TileType.TYPE_RUBBLE;
					fert = 0; // scree collar hugging the outcrops
				} else if (elev > 0.82) {
					t = Tile.TileType.TYPE_STONE;
					fert = 0; // bare rock apron outside the scree
				} else if (elev > 0.74) {
					// The skirt where the highlands come apart into the meadow:
					// bedrock breaking the surface as slabs, thin grit between
					// them, and only what sward that grit can keep. Sparse by
					// nature — this is the poorest ground a grazer can still
					// make a living on, so it reads as a real frontier rather
					// than a texture swap.
					t = Tile.TileType.TYPE_ROCKY;
					fert = 0.10 + 0.30 * moist;
				} else if (moist > 0.70 && elev < 0.45) {
					t = Tile.TileType.TYPE_WATER;
					fert = 0; // lakes in the low, wet ground
				} else if (moist > 0.68 && elev < 0.46) {
					t = Tile.TileType.TYPE_REEDS;
					fert = 0; // reed beds fringing the water: slow, sight-blocking
				} else if (moist > 0.60 && elev < 0.52) {
					t = Tile.TileType.TYPE_MUD;
					fert = 0.30; // marshy shore, slows movement
				} else if (moist > 0.55 && detail > 0.62) {
					t = Tile.TileType.TYPE_COVER;
					fert = 0.90; // thickets: lush, and they block line of sight
				} else if (elev > 0.58 && moist < 0.30) {
					// The badlands' driest core: a sand pan, with treacherous
					// quicksand pockets where the detail noise peaks.
					t = detail > 0.78 ? Tile.TileType.TYPE_QUICKSAND : Tile.TileType.TYPE_SAND;
					fert = 0;
				} else if (elev > 0.58 && moist < 0.40) {
					t = Tile.TileType.TYPE_FLOOR;
					fert = 0.0; // dry badlands: bare dirt, no grass, no food
				} else {
					// Meadow, and its richness is the story the ground tells:
					// fertility runs the whole band from thin scrub on the dry
					// margins to prime pasture in the damp hollows, following
					// the same moisture that decides where water and thicket
					// go — so a walk toward the lakes is a walk into greener
					// ground. The band starts at the art's bare threshold
					// (GroundTextures.SWARD_BARE), so the poorest living
					// pasture is exactly where the baked sward begins to close
					// over the earth, and a little detail noise breaks the
					// gradient up so richness reads as patches, not as bands.
					// Kept mean-neutral against the old narrow band, so the
					// world holds as much food as before — just spread far more
					// unevenly, which is what makes a habitat worth choosing.
					t = Tile.TileType.TYPE_FLOOR;
					fert = 0.15 + 1.25 * moist + 0.08 * (detail - 0.5);
					fert = fert < 0 ? 0 : (fert > 1 ? 1 : fert);
				}
				w.setTile(x, y, SURFACE_Z, t);
				w.getTile(x, y, SURFACE_Z).setFertility(fert);
			}
		}

		// ---- rivers: droplet walks down the same elevation field the biomes
		// read, so water runs out of the highlands, through the meadows, and
		// into the lakes (WORLDGEN-RESEARCH.md #2) ----
		carveRivers(w, cols, rows);

		// ---- cave: underground caverns carved from solid rock ----
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				double cave = Utils.noise2(x + 210, y + 770, 0.11);
				double pool = Utils.noise2(x + 1300, y + 90, 0.13);
				Tile.TileType t;
				if (x < 1 || y < 1 || x >= cols - 1 || y >= rows - 1) {
					t = Tile.TileType.TYPE_WALL; // sealed edge
				} else if (cave > 0.38 && cave < 0.66) {
					// Pool cores are water; the damp ground skirting a pool grows
					// bioluminescent fungus beds (the caves' only food); the
					// driest stone sprouts crystal clusters and, in the odd
					// geothermal pocket, vent mouths; the rest is bare stone.
					double deep = Utils.noise2(x + 40, y + 1500, 0.3);
					if (pool > 0.72) {
						t = Tile.TileType.TYPE_WATER;
					} else if (pool > 0.62) {
						t = Tile.TileType.TYPE_FUNGUS;
					} else if (pool < 0.18) {
						// Crystal fields grade by density from the core out: a
						// blocking formation spine, a packed bed that slows a
						// wader, then loose shards on ordinary stone.
						t = pool < 0.07 ? Tile.TileType.TYPE_CRYSTAL
								: pool < 0.125 ? Tile.TileType.TYPE_CRYSTAL_BED
								: Tile.TileType.TYPE_CRYSTAL_SPARSE;
					} else if (deep > 0.85) {
						t = Tile.TileType.TYPE_VENT;
					} else if (deep < 0.08) {
						// The odd cavern floor gives way entirely: a natural
						// pit, and on the lowest level a pit is bottomless --
						// whatever falls in leaves the world. The corridor
						// carver paves straight through pit fields, so the
						// backbone always bridges them.
						t = Tile.TileType.TYPE_HOLE;
					} else {
						t = Tile.TileType.TYPE_STONE;
					}
				} else {
					t = Tile.TileType.TYPE_WALL; // solid rock
				}
				w.setTile(x, y, CAVE_Z, t);
				// Fungus beds hold moderate food; nothing else grows underground.
				w.getTile(x, y, CAVE_Z).setFertility(
						t == Tile.TileType.TYPE_FUNGUS ? 0.6 : 0);
			}
		}

		// ---- cellular-automata polish: a couple of rounds over the cave's
		// wall/floor boundary rounds cavern edges organically and merges
		// near-touching pockets, so less ends up sealed later. Only plain rock
		// and plain stone flip; pools, fungus, crystal, vents and pits keep
		// their ground truth (WORLDGEN-RESEARCH.md #3). ----
		smoothCave(w, cols, rows);

		// ---- wire the levels together so every area is reachable ----
		connectLevels(w, cols, rows);

		// ---- a buried installation: someone built down here, once ----
		buryInstallation(w, cols, rows);

		// ---- the ravine: a gorge torn through the surface, with the caves
		// showing through it -- carved last so it can keep clear of everything
		// the passes above placed ----
		carveRavine(w, cols, rows);

		// ---- shallows: every shore-touching water tile becomes a walkable,
		// wading fringe, so lakes have fords instead of hard edges ----
		for (int z = CAVE_Z; z <= SURFACE_Z; z++) {
			java.util.ArrayList<int[]> shore = new java.util.ArrayList<int[]>();
			for (int x = 1; x < cols - 1; x++) {
				for (int y = 1; y < rows - 1; y++) {
					if (w.getTile(x, y, z).getType() != Tile.TileType.TYPE_WATER) {
						continue;
					}
					for (int k = 0; k < 4; k++) {
						int nx = x + (k == 0 ? 1 : k == 1 ? -1 : 0);
						int ny = y + (k == 2 ? 1 : k == 3 ? -1 : 0);
						Tile.TileType n = w.getTile(nx, ny, z).getType();
						if (n != Tile.TileType.TYPE_WATER && n != Tile.TileType.TYPE_SHALLOWS
								&& w.getTile(nx, ny, z).isWalkable()) {
							shore.add(new int[] { x, y });
							break;
						}
					}
				}
			}
			for (int[] p : shore) {
				w.setTile(p[0], p[1], z, Tile.TileType.TYPE_SHALLOWS);
				w.getTile(p[0], p[1], z).setFertility(0);
			}
		}

		// Tune the surface grass's logistic recovery so a grazed-bare patch rests
		// (Tile.REGROW_DELAY, ~1 min) and then climbs back slowly over another
		// ~1.5 min, while a lightly-cropped patch springs back fast. Heavy grazing
		// thus leaves lasting bare patches, but the big map still sustains the herd
		// (unlike a small room). Non-grass tiles are unaffected.
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				w.getTile(x, y, SURFACE_Z).setRegrowRate(0.0025);
			}
		}
		return w;
	}

	/**
	 * Carve a buried installation into the cave level: a concrete-shelled
	 * facility hall with plate decking, a pipe run and air vents, a steel
	 * inner vault, and a crawl duct punched through the vault wall as the
	 * small-body shortcut -- the man-made counterweight to the caves' grown
	 * terrain, sunk into solid rock and reached through a paved entrance
	 * gallery tunnelled to the nearest cavern.
	 *
	 * <p>Runs after {@link #connectLevels}, so the gallery attaches to ground
	 * that is already part of the world's single connected region — the new
	 * rooms extend that region rather than gambling on surviving the seal.
	 * The doorway mouths are left open (no {@code Door} entities yet): doors
	 * are not shipped to the web client, and a door it cannot see would read
	 * as an invisible barrier there. When door state joins the client
	 * protocol, the blast door belongs in the 2-wide mouth.
	 *
	 * <p>Skipped quietly when no rock pocket fits the footprint (only a
	 * concern on very small maps); everything it carves is deterministic and
	 * draws no RNG.
	 */
	private static void buryInstallation(World w, int cols, int rows) {
		// The full three-band plan wants an 18x13 pocket. A cave too riddled
		// with caverns to host one (commonly the mid sizes) gets the compact
		// single-hall annex instead; only a truly tiny map gets nothing.
		int[] site = findRockPocket(w, cols, rows, DEEP_W, DEEP_H);
		if (site != null) {
			buildDeepStation(w, cols, rows, site[0], site[1]);
			return;
		}
		site = findRockPocket(w, cols, rows, 18, 13);
		if (site != null) {
			buildFullBase(w, cols, rows, site[0], site[1]);
			return;
		}
		site = findRockPocket(w, cols, rows, 15, 9);
		if (site != null) {
			buildCompactBase(w, cols, rows, site[0], site[1]);
		}
	}

	/** The expanded station's shell: the largest pocket the caves reliably
	 *  leave, measured rather than wished for. */
	static final int DEEP_W = 22, DEEP_H = 15;

	/**
	 * The expanded station: three bands on the cave level over a plant floor cut
	 * into the rock beneath, joined by a stairwell.
	 *
	 * <p>The old plan was three bands in an 18x13 shell and it had run out of
	 * room in the most literal way — a rank of four charge pads did not fit in a
	 * machine wing three rows tall, and putting them in anyway walked them
	 * through a partition wall and out into the spine. The building is the
	 * constraint the drones ran into, so the building is what changed.
	 *
	 * <pre>
	 *   rows  1..3   machine wing   plate, pipe run, vents, racks, drone rank
	 *   row   4      partition
	 *   rows  5..7   central spine  paved, blast mouth west, tram run, vault east
	 *   row   8      partition
	 *   rows  9..13  storage hall   plate, crates, the waste sump, the stairwell
	 * </pre>
	 *
	 * <p>Three bands and not four, and the shell is 22x15 rather than the 26x17
	 * this started as, because the caves do not leave room for more. Measured
	 * across seeds, a 26x17 rock pocket never appears and a 24x15 one appears
	 * about half the time — the site is riddled with caverns by the time this
	 * runs, which is the point of running it after the caves rather than before.
	 *
	 * <p>That constraint is what sent the expansion DOWNWARD, and the design is
	 * better for it. Rock under the station is virgin: there is no pocket to
	 * find, nothing to displace, and the plant floor can be laid out to suit
	 * itself. The rooms that would not fit up here are down there, and the
	 * building now has to be walked through in three dimensions to be seen.
	 *
	 */
	private static void buildDeepStation(World w, int cols, int rows, int x0, int y0) {
		final int W = DEEP_W, H = DEEP_H;

		for (int x = x0; x < x0 + W; x++) {
			for (int y = y0; y < y0 + H; y++) {
				boolean shell = x == x0 || y == y0 || x == x0 + W - 1 || y == y0 + H - 1;
				boolean partition = y == y0 + 4 || y == y0 + 8;
				setBare(w, x, y, CAVE_Z, shell || partition
						? Tile.TileType.TYPE_WALL_CONCRETE : Tile.TileType.TYPE_PLATE);
			}
		}

		// The spine, paved from the mouth to the vault's step.
		for (int x = x0 + 1; x < x0 + 15; x++) {
			for (int y = y0 + 5; y <= y0 + 7; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_PAVED);
			}
		}
		// Doorways through the partitions: two per wall, paved thresholds, and
		// crawl ducting through the west end of each so a small body can move
		// the whole height of the station inside the walls.
		for (int dy : new int[] { 4, 8 }) {
			setBare(w, x0 + 7, y0 + dy, CAVE_Z, Tile.TileType.TYPE_PAVED);
			setBare(w, x0 + 13, y0 + dy, CAVE_Z, Tile.TileType.TYPE_PAVED);
			for (int x = x0 + 2; x <= x0 + 4; x++) {
				setBare(w, x, y0 + dy, CAVE_Z, Tile.TileType.TYPE_DUCT);
			}
		}

		// The tram run, the length of the spine.
		for (int x = x0 + 1; x < x0 + 15; x++) {
			setBare(w, x, y0 + 6, CAVE_Z, Tile.TileType.TYPE_RAIL);
		}

		// ---- machine wing -------------------------------------------------
		for (int x = x0 + 2; x < x0 + W - 2; x++) {
			setBare(w, x, y0 + 1, CAVE_Z, Tile.TileType.TYPE_PIPES);
		}
		setBare(w, x0 + 5, y0 + 3, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		setBare(w, x0 + 11, y0 + 3, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		for (int y = y0 + 2; y <= y0 + 3; y++) {
			setBare(w, x0 + 8, y, CAVE_Z, Tile.TileType.TYPE_SERVER);
			setBare(w, x0 + 9, y, CAVE_Z, Tile.TileType.TYPE_SERVER);
		}
		for (int x = x0 + 2; x <= x0 + 6; x++) {
			setBare(w, x, y0 + 2, CAVE_Z, Tile.TileType.TYPE_COOLANT);
		}
		setBare(w, x0 + 12, y0 + 2, CAVE_Z, Tile.TileType.TYPE_EXCHANGER);
		setBare(w, x0 + 12, y0 + 3, CAVE_Z, Tile.TileType.TYPE_EXCHANGER);

		// The drone rank: four pads at the wing's east end, as two columns of
		// two rather than a line of four. A line was the first try and it does
		// not fit — the wing is three rows tall, and pads spaced along it walk
		// straight out through the partition and into the spine, which is how
		// three of the four quietly became doorways. Blocked up, they fit any
		// wing that fits the wing.
		for (int i = 0; i < DRONE_RANK; i++) {
			setBare(w, x0 + 15 + (i & 1) * 2, y0 + 2 + (i >> 1), CAVE_Z,
					Tile.TileType.TYPE_DOCK);
		}

		// ---- storage wing --------------------------------------------------
		setBare(w, x0 + 12, y0 + 9, CAVE_Z, Tile.TileType.TYPE_PIPES);
		setBare(w, x0 + 12, y0 + 10, CAVE_Z, Tile.TileType.TYPE_PIPES);
		setBare(w, x0 + 4, y0 + 12, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		setBare(w, x0 + 9, y0 + 9, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		for (int x = x0 + 1; x <= x0 + 2; x++) {
			for (int y = y0 + 10; y <= y0 + 12; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_SLUDGE);
			}
		}
		// Tread-plate down the hall's spine: the walk the stairwell is at the
		// end of, and the one piece of floor here that says people used it.
		for (int x = x0 + 4; x <= x0 + 16; x++) {
			setBare(w, x, y0 + 11, CAVE_Z, Tile.TileType.TYPE_TREADPLATE);
		}

		// ---- the steel vault, closing the spine's east end -------------------
		int vx = x0 + 15, vy = y0 + 4, vw = 6, vh = 5;
		for (int x = vx; x < vx + vw; x++) {
			for (int y = vy; y < vy + vh; y++) {
				boolean rim = x == vx || y == vy || x == vx + vw - 1 || y == vy + vh - 1;
				setBare(w, x, y, CAVE_Z, rim
						? Tile.TileType.TYPE_WALL_STEEL : Tile.TileType.TYPE_PLATE);
			}
		}
		setBare(w, vx, vy + vh / 2, CAVE_Z, Tile.TileType.TYPE_PLATE); // doorway
		// The duct through the vault's north wall opens at vx+3 and not vx+2,
		// which is the drone rank's east pad. The vault's grate answers only its
		// buttons, so this duct is the whole of the other way in -- and a berth
		// laid across its approach makes a parked machine the doorman.
		setBare(w, vx + 3, vy, CAVE_Z, Tile.TileType.TYPE_DUCT); // duct from the wing

		// ---- the way in, and only then the way down --------------------------
		// The mouth is cut on the spine (rows 5..7), not at the shell's
		// midpoint: with four bands the midpoint is a partition wall, and a
		// mouth there opens the storage wing straight onto the rock.
		//
		// The plant floor is sunk only if the station survives. A site with no
		// way out through the rock is un-carved back to wall, and a second
		// floor left hanging under a base that no longer exists is a hundred-odd
		// walkable tiles nothing can reach — which is exactly how this was
		// found, as a connectivity failure rather than as anything visible.
		if (!finishBase(w, cols, rows, x0, y0, W, H, vx, vy, vh, y0 + 6)) {
			return;
		}
		sinkPlantFloor(w, x0, y0, W, H);
		// Two lanes, at the east end of the storage hall's tread-plate walk.
		sinkStairwell(w, x0 + 15, y0 + 10, 2);

		// Furnishing: the stack the loader marshals to, the vault's cache.
		w.spawnEntity(Item.crate(x0 + 5.5, y0 + 9.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 6.5, y0 + 9.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 5.5, y0 + 12.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 8.5, y0 + 9.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 11.5, y0 + 6.5, CAVE_Z)); // one left on the tram run
		w.spawnEntity(Item.food(vx + 2.5, vy + 1.5, CAVE_Z));
		w.spawnEntity(Item.food(vx + 3.5, vy + 2.5, CAVE_Z));
		w.spawnEntity(Item.hazard(vx + 2.5, vy + 3.5, CAVE_Z));
	}

	/**
	 * The plant floor's plan, in the room's own interior coordinates: sixteen
	 * tiles wide by nine tall, with the shell added around it.
	 *
	 * <pre>
	 *   .  deck plate      C  coolant run    S  waste sump    P  pipe run
	 *   X  heat exchanger  w  catwalk        R  collapsed deck
	 *   T  loading deck    L  lit grating    B  shard bed     V  vent grille
	 *   s  steel bulkhead
	 * </pre>
	 *
	 * <p>Drawn rather than computed, and that is the whole of the change. The
	 * first version placed each feature by arithmetic off the room's centre —
	 * exchangers at cx+-2, coolant at cy+-3, a sump four in from the corner —
	 * and the features quietly wrote over one another in the order they
	 * happened to be listed. The loading walk erased the coolant loop's entire
	 * south leg, so the loop was three sides of a rectangle; the collapse ate
	 * its east end; the stairwell landed in the sump and cut it from three
	 * tiles to one. None of that is visible in the source, all of it is
	 * obvious in a render, and none of it could fail a test. ART-STYLE.md
	 * section 5 already says authored beats computed for discrete things, and
	 * a room is a discrete thing.
	 *
	 * <p>The room is one fixed size rather than the station's, because it is
	 * cut into virgin rock and nothing up there constrains it. Every plan that
	 * gets a floor beneath it is at least this big, so the plan is simply
	 * centred under whichever shell it is handed.
	 *
	 * <p>What is down here is the plant the rooms above keep referring to. The
	 * machine wing has a coolant run and an exchanger dumping heat, and until
	 * now they came from nowhere: the run started at a wall. Now it starts
	 * somewhere. A closed loop of coolant around a block of exchangers, a
	 * catwalk down its east face to walk the reactor from, a sump the floor
	 * drains into, and a bay behind a bulkhead whose only way in is over the
	 * fallen ceiling — which is also why the level reads as somewhere that
	 * stopped being maintained.
	 *
	 * <p>Every tile it uses already existed. The point of the new rooms is not
	 * new terrain but somewhere for the terrain to mean something: the crystal
	 * bed has been in the caves all along, and putting lit grating and a
	 * walkway around one says a facility was studying it.
	 */
	static final String[] PLANT_FLOOR = {
			"PV..CCCCCCCw.RRR",
			"P...CXXXXXCw.RRR",
			"P...CXXXXXCwsTTT",
			"P...CXXXXXCwsTTT",
			"P...CCCCCCCwsTTT",
			"PLLL.......w....",
			"PLBL.TTTTTTTT...",
			"PLLL...SSS......",
			"P..V...SSSV.....",
	};

	/** The plan's interior extent, and the shell around it. */
	static final int PLANT_W = 16 + 2, PLANT_H = 9 + 2;

	/** The plant floor's plan, for the scenarios: the drawing the deep level is
	 *  supposed to be, so a scenario can ask whether it still is one. Copied,
	 *  so nobody can edit the room in place. */
	public static String[] plantFloorPlan() {
		return PLANT_FLOOR.clone();
	}

	/** Cuts the plant floor into the rock under a station shell of W x H at
	 *  (x0, y0), centred. The stairwell is NOT cut here: only the builder above
	 *  knows which of its own rooms the stairs may come up in, and the two
	 *  station plans do not agree — one has a storage hall where the other has
	 *  a bottomless shaft. See {@link #sinkStairwell}. */
	private static void sinkPlantFloor(World w, int x0, int y0, int W, int H) {
		int px0 = x0 + (W - PLANT_W) / 2, py0 = y0 + (H - PLANT_H) / 2;
		for (int x = px0; x < px0 + PLANT_W; x++) {
			for (int y = py0; y < py0 + PLANT_H; y++) {
				boolean shell = x == px0 || y == py0
						|| x == px0 + PLANT_W - 1 || y == py0 + PLANT_H - 1;
				setBare(w, x, y, DEEP_Z, shell
						? Tile.TileType.TYPE_WALL_CONCRETE : Tile.TileType.TYPE_PLATE);
			}
		}
		for (int j = 0; j < PLANT_FLOOR.length; j++) {
			for (int i = 0; i < PLANT_FLOOR[j].length(); i++) {
				Tile.TileType t = plantTile(PLANT_FLOOR[j].charAt(i));
				if (t != null) {
					setBare(w, px0 + 1 + i, py0 + 1 + j, DEEP_Z, t);
				}
			}
		}
	}

	private static Tile.TileType plantTile(char ch) {
		switch (ch) {
		case 'P':
			return Tile.TileType.TYPE_PIPES;
		case 'C':
			return Tile.TileType.TYPE_COOLANT;
		case 'X':
			return Tile.TileType.TYPE_EXCHANGER;
		case 'w':
			return Tile.TileType.TYPE_CATWALK;
		case 's':
			return Tile.TileType.TYPE_WALL_STEEL;
		case 'T':
			return Tile.TileType.TYPE_TREADPLATE;
		case 'L':
			return Tile.TileType.TYPE_LIGHTGRATE;
		case 'B':
			return Tile.TileType.TYPE_CRYSTAL_BED;
		case 'S':
			return Tile.TileType.TYPE_SLUDGE;
		case 'R':
			return Tile.TileType.TYPE_COLLAPSE;
		case 'V':
			return Tile.TileType.TYPE_AIRVENT;
		case '.':
			return null; // the shell pass already laid deck plate
		default:
			throw new IllegalArgumentException("no such plant-floor tile: " + ch);
		}
	}

	/**
	 * The stairwell from the cave level down to the plant floor, {@code lanes}
	 * of it side by side running south from (hx, hy).
	 *
	 * <p>The link copies the surface-to-cave pattern exactly, one level down: a
	 * hole to fall through, a descending ramp beside it so the fall is not the
	 * only way, a landing below, and a climbing ramp back up. Written out
	 * rather than reusing {@code linkStation} because that carves cave and
	 * surface by name, and this joins the two floors underneath them — the
	 * geometry is the same, the levels are not.
	 *
	 * <p>Two lanes where the room above allows it, so a body coming down does
	 * not have to wait for one going up; one where it does not.
	 *
	 * <p>The hole beside each ramp is scenery for connectivity purposes: a pit
	 * is not walkable, so nothing routes through one and the flood never
	 * enters it.
	 */
	private static void sinkStairwell(World w, int hx, int hy, int lanes) {
		// u = 1 is east in Tile's direction order, so the slope climbs east and
		// a body steps off its west foot to come down.
		final int u = 1;
		int ax = Tile.dirDx(u), ay = Tile.dirDy(u);
		for (int r = 0; r < lanes; r++) {
			int bx = hx, by = hy + r;
			int dx1 = bx + ax, dy1 = by + ay;            // the descending ramp
			int ux = bx + 2 * ax, uy = by + 2 * ay;      // the climbing ramp
			int lx = bx + 3 * ax, ly = by + 3 * ay;      // the upper landing

			setBare(w, bx, by, CAVE_Z, Tile.TileType.TYPE_HOLE);
			setBare(w, dx1, dy1, CAVE_Z, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(dx1, dy1, CAVE_Z).setRampUphill(u);
			setBare(w, bx, by, DEEP_Z, Tile.TileType.TYPE_PLATE); // landing below

			setBare(w, ux, uy, DEEP_Z, Tile.TileType.TYPE_RAMPUP);
			w.getTile(ux, uy, DEEP_Z).setRampUphill(u);
			// Under the upper landing stands the mass the climb rises into.
			// This has flipped twice, so the reasoning in full: the first
			// version walled it as "rock under", the second removed it as "a
			// lone concrete block holding up a deck plate that did not need
			// holding up" — and bare, the ramp read as attached to nothing, a
			// bright band ending in open floor (an up ramp climbs into
			// something; that is the difference between a stair and a plank).
			// The block is not scenery under the landing, it is the stair's
			// own housing: in the deep station it merges into the east shell
			// it abuts, and mid-room it reads as the masonry core the stair
			// wraps, which is what real stairwells have. The engine never
			// walks it — the climb exits one level up, on the landing.
			setBare(w, lx, ly, DEEP_Z, Tile.TileType.TYPE_WALL_CONCRETE);
			setBare(w, lx, ly, CAVE_Z, Tile.TileType.TYPE_PLATE); // landing above
		}
	}

	/** The full station plan, in an 18x13 shell. */
	private static void buildFullBase(World w, int cols, int rows, int x0, int y0) {
		final int W = 18, H = 13;

		// The plan is three bands under one concrete shell, each floored in
		// its own material so the rooms read at a glance:
		//
		//   rows 1..3   machine wing  -- plate deck, pipe run, vents
		//   rows 5..7   central spine -- paved, fed by the 2-wide blast mouth
		//   rows 9..11  storage wing  -- plate deck, a pipe drop, vents
		//
		// Concrete partition walls at rows 4 and 8 separate the bands, each
		// pierced by two open doorways; the steel vault closes the spine's
		// east end, answering only its buttons (and the crawl duct through
		// its north wall, from the machine wing).
		for (int x = x0; x < x0 + W; x++) {
			for (int y = y0; y < y0 + H; y++) {
				boolean shell = x == x0 || y == y0 || x == x0 + W - 1 || y == y0 + H - 1;
				boolean partition = (y == y0 + 4 || y == y0 + 8);
				setBare(w, x, y, CAVE_Z, shell || partition
						? Tile.TileType.TYPE_WALL_CONCRETE : Tile.TileType.TYPE_PLATE);
			}
		}
		// The spine, paved from the mouth to the vault's step.
		for (int x = x0 + 1; x < x0 + 12; x++) {
			for (int y = y0 + 5; y <= y0 + 7; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_PAVED);
			}
		}
		// Doorways through the partitions: two per wall, paved thresholds.
		setBare(w, x0 + 5, y0 + 4, CAVE_Z, Tile.TileType.TYPE_PAVED);
		setBare(w, x0 + 11, y0 + 4, CAVE_Z, Tile.TileType.TYPE_PAVED);
		setBare(w, x0 + 5, y0 + 8, CAVE_Z, Tile.TileType.TYPE_PAVED);
		setBare(w, x0 + 11, y0 + 8, CAVE_Z, Tile.TileType.TYPE_PAVED);

		// The ventilation runs: crawl ducting laid through both partitions'
		// west sections, so a small body can move machine wing -> spine ->
		// storage wing entirely inside the walls, concealed -- the base's
		// second circulation system, parallel to the doorways.
		for (int x = x0 + 2; x <= x0 + 4; x++) {
			setBare(w, x, y0 + 4, CAVE_Z, Tile.TileType.TYPE_DUCT);
			setBare(w, x, y0 + 8, CAVE_Z, Tile.TileType.TYPE_DUCT);
		}

		// The tram run: track laid the length of the spine, from the blast
		// mouth to the vault's step. It is how the facility was supplied, and
		// it is ordinary ground to walk on -- the tile earns its place by
		// explaining the room rather than by changing anyone's speed, which
		// makes it the one piece of terrain here that is frankly scenery.
		for (int x = x0 + 1; x < x0 + 12; x++) {
			setBare(w, x, y0 + 6, CAVE_Z, Tile.TileType.TYPE_RAIL);
		}

		// Machine wing: a pipe run the room's whole width, vents in the deck.
		for (int x = x0 + 2; x < x0 + W - 2; x++) {
			setBare(w, x, y0 + 1, CAVE_Z, Tile.TileType.TYPE_PIPES);
		}
		setBare(w, x0 + 4, y0 + 2, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		setBare(w, x0 + 9, y0 + 3, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		// Two rack rows in the machine wing, aisled so a body can walk between
		// them. Solid, so they are cover; clear to the eye, so they are cover
		// that hides nothing -- a creature in the aisle can watch what it
		// cannot be reached by, which is a standoff no wall in this world
		// produces.
		for (int y = y0 + 2; y <= y0 + 3; y++) {
			setBare(w, x0 + 6, y, CAVE_Z, Tile.TileType.TYPE_SERVER);
			setBare(w, x0 + 7, y, CAVE_Z, Tile.TileType.TYPE_SERVER);
		}
		// The plant, and the reason the racks can be there at all: a lagged
		// coolant run comes down the wing to the cabinets, and a heat exchanger
		// on the far side dumps what it carried away. Neither does anything yet
		// -- they are the facility explaining, in the only language a tile has,
		// where its cold and its heat come from, so that when temperature is a
		// field rather than a picture it already has somewhere to start.
		for (int x = x0 + 2; x <= x0 + 5; x++) {
			setBare(w, x, y0 + 2, CAVE_Z, Tile.TileType.TYPE_COOLANT);
		}
		setBare(w, x0 + 9, y0 + 2, CAVE_Z, Tile.TileType.TYPE_EXCHANGER);
		setBare(w, x0 + 10, y0 + 2, CAVE_Z, Tile.TileType.TYPE_EXCHANGER);
		// The drone rank, in the machine wing among the plant it belongs to --
		// clear of the pipe run along row 1 and of both vents, and one tile in
		// from the partition doorway at x0+11 so no pad is the threshold
		// anything else has to cross.
		//
		// Four pads as two columns of two. A line of four does not fit a wing
		// three rows tall — spaced along it, three of them land in the
		// partition and the spine, which turns charge pads into doorways.
		for (int i = 0; i < DRONE_RANK; i++) {
			setBare(w, x0 + 13 + (i & 1) * 2, y0 + 2 + (i >> 1), CAVE_Z,
					Tile.TileType.TYPE_DOCK);
		}

		// Storage wing (west half): a vertical pipe drop and its own vents.
		setBare(w, x0 + 8, y0 + 9, CAVE_Z, Tile.TileType.TYPE_PIPES);
		setBare(w, x0 + 8, y0 + 10, CAVE_Z, Tile.TileType.TYPE_PIPES);
		setBare(w, x0 + 3, y0 + 10, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		setBare(w, x0 + 6, y0 + 9, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		// The waste sump: whatever the machine wing makes drains to the storage
		// wing's west end and stays there. Walkable, so it is a shortcut with a
		// price rather than a wall -- the only ground in the world that costs a
		// body health to cross, and the reason the storage wing is worth
		// crossing carefully instead of just crossing.
		for (int x = x0 + 1; x <= x0 + 2; x++) {
			for (int y = y0 + 9; y <= y0 + 11; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_SLUDGE);
			}
		}

		// The shaft bay, Black-Mesa style: the whole south-east quadrant has
		// given way to a bottomless pit, crossed by two catwalks meeting at
		// a junction over the void. The east-west walk runs from the storage
		// wing's pipe end out to a supply platform against the east wall;
		// the north-south walk drops from the spine's doorway down to a
		// second mouth in the south shell -- so one of the base's entrances
		// is a walk over the abyss.
		for (int x = x0 + 9; x <= x0 + 16; x++) {
			for (int y = y0 + 9; y <= y0 + 11; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_SHAFT);
			}
		}
		for (int x = x0 + 9; x <= x0 + 15; x++) {
			setBare(w, x, y0 + 10, CAVE_Z, Tile.TileType.TYPE_CATWALK); // east-west walk
		}
		for (int y = y0 + 9; y <= y0 + 11; y++) {
			setBare(w, x0 + 11, y, CAVE_Z, Tile.TileType.TYPE_CATWALK); // north-south walk
		}
		// The supply platform at the catwalk's end: chequerplate, worn by
		// whatever was landed on it, and lit grating on the approach so the
		// walk over the void has one stretch that is lit from below rather
		// than opening onto nothing.
		setBare(w, x0 + 16, y0 + 10, CAVE_Z, Tile.TileType.TYPE_TREADPLATE);
		setBare(w, x0 + 14, y0 + 10, CAVE_Z, Tile.TileType.TYPE_LIGHTGRATE);
		setBare(w, x0 + 15, y0 + 10, CAVE_Z, Tile.TileType.TYPE_LIGHTGRATE);
		w.spawnEntity(Item.food(x0 + 16.5, y0 + 10.5, CAVE_Z));

		// The south gate: the catwalk's south arm exits through the shell, a
		// self-cycling maintenance grate over the doorway and its own gallery
		// tunnelled to the nearest cavern -- the base's second ground
		// entrance. If the rock refuses a way out, the mouth seals back up
		// and the catwalk arm simply dead-ends over the void.
		setBare(w, x0 + 11, y0 + H - 1, CAVE_Z, Tile.TileType.TYPE_PAVED);
		if (carveGallery(w, cols, rows, x0 + 11, y0 + H,
				new int[][] { { x0 + 11, y0 + H - 1 } }) != null) {
			w.addDoor(new net.hedinger.prototype.entities.Door(x0 + 11, y0 + H - 1,
					CAVE_Z, 0, net.hedinger.prototype.entities.Door.GRATE));
		} else {
			setBare(w, x0 + 11, y0 + H - 1, CAVE_Z, Tile.TileType.TYPE_WALL_CONCRETE);
		}

		// The steel vault, closing the spine's east end: steel walls over the
		// partition rows, a grate doorway facing the spine, and the crawl
		// duct through its north wall into the machine wing.
		int vx = x0 + 12, vy = y0 + 4, vw = 5, vh = 5;
		for (int x = vx; x < vx + vw; x++) {
			for (int y = vy; y < vy + vh; y++) {
				boolean rim = x == vx || y == vy || x == vx + vw - 1 || y == vy + vh - 1;
				setBare(w, x, y, CAVE_Z, rim
						? Tile.TileType.TYPE_WALL_STEEL : Tile.TileType.TYPE_PLATE);
			}
		}
		setBare(w, vx, vy + vh / 2, CAVE_Z, Tile.TileType.TYPE_PLATE); // vault doorway
		setBare(w, vx + 2, vy, CAVE_Z, Tile.TileType.TYPE_DUCT); // duct to the machine wing

		// Furnishing: stacked crates in the storage wing (real items -- a
		// hauler can move them, and one parked on a plate holds a door), and
		// the vault's cache: the food worth locking behind buttons, plus a
		// hazard standing guard over it.
		// Where the ceiling came down: the storage wing's north-east corner,
		// between the crates and the shaft. It is the only ground in the base
		// that says something went wrong here.
		for (int x = x0 + 6; x <= x0 + 7; x++) {
			for (int y = y0 + 10; y <= y0 + 11; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_COLLAPSE);
			}
		}
		w.spawnEntity(Item.crate(x0 + 4.5, y0 + 9.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 5.5, y0 + 9.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 7.5, y0 + 9.5, CAVE_Z)); // clear of the stair head
		w.spawnEntity(Item.crate(x0 + 7.5, y0 + 11.5, CAVE_Z));
		w.spawnEntity(Item.food(vx + 1.5, vy + 1.5, CAVE_Z));
		w.spawnEntity(Item.food(vx + 3.5, vy + 1.5, CAVE_Z));
		w.spawnEntity(Item.hazard(vx + 2.5, vy + 2.5, CAVE_Z));

		// The ceiling: a ventilation shaft from the surface over the spine,
		// so gravity is the base's third entrance.
		dropShaft(w, x0 + 2, x0 + 10, y0 + 5, y0 + 7);

		// The rock below is virgin whatever shell the caves allowed up here, so a
		// smaller station is no reason for the building to stop at one storey.
		// The floor is sunk only if the station survives: a site with no way out
		// through the rock is un-carved back to wall, and a second floor left
		// hanging under a base that no longer exists is a hundred-odd walkable
		// tiles nothing in the world can reach.
		if (finishBase(w, cols, rows, x0, y0, W, H, vx, vy, vh)) {
			sinkPlantFloor(w, x0, y0, W, H);
			// One lane, and at the storage wing's WEST end: this plan's south-east
			// quadrant is the shaft bay, and a stairwell head over a bottomless pit
			// is a stairwell nothing can stand at the top of.
			sinkStairwell(w, x0 + 3, y0 + 10, 1);
		}
	}

	/**
	 * The compact annex, in a 15x9 shell: one hall with the pipe run, vents,
	 * and the steel vault at its east end -- the fallback plan for caves
	 * whose rock cannot host the full station.
	 */
	private static void buildCompactBase(World w, int cols, int rows, int x0, int y0) {
		final int W = 15, H = 9;
		for (int x = x0; x < x0 + W; x++) {
			for (int y = y0; y < y0 + H; y++) {
				boolean shell = x == x0 || y == y0 || x == x0 + W - 1 || y == y0 + H - 1;
				setBare(w, x, y, CAVE_Z, shell
						? Tile.TileType.TYPE_WALL_CONCRETE : Tile.TileType.TYPE_PLATE);
			}
		}
		for (int x = x0 + 2; x < x0 + W - 2; x++) {
			setBare(w, x, y0 + 1, CAVE_Z, Tile.TileType.TYPE_PIPES);
		}
		// The annex's stub of track, running the hall from mouth to vault.
		for (int x = x0 + 1; x < x0 + W - 6; x++) {
			setBare(w, x, y0 + 4, CAVE_Z, Tile.TileType.TYPE_RAIL);
		}
		setBare(w, x0 + 3, y0 + H - 3, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		setBare(w, x0 + 6, y0 + 3, CAVE_Z, Tile.TileType.TYPE_AIRVENT);
		// One rack pair in the annex, against the north deck.
		setBare(w, x0 + 8, y0 + 2, CAVE_Z, Tile.TileType.TYPE_SERVER);
		setBare(w, x0 + 8, y0 + 3, CAVE_Z, Tile.TileType.TYPE_SERVER);
		// The drone rank: the annex has one room, so the pads go against the
		// north deck under the pipe run, out of the walk from the mouth to the
		// vault. Same four as the full plan -- a smaller building is not a
		// reason to warden the world with a smaller crew.
		for (int i = 0; i < DRONE_RANK; i++) {
			setBare(w, x0 + 2 + (i & 1) * 2, y0 + 2 + (i >> 1), CAVE_Z,
					Tile.TileType.TYPE_DOCK);
		}
		// The annex has no storage wing to drain into, so its spill pools in
		// the hall itself -- squarely on the walk from the mouth to the vault,
		// which is the point: the shortest way across the room costs something.
		for (int x = x0 + 5; x <= x0 + 6; x++) {
			for (int y = y0 + 5; y <= y0 + 6; y++) {
				setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_SLUDGE);
			}
		}
		int vx = x0 + W - 6, vy = y0 + 2, vw = 4, vh = H - 4;
		for (int x = vx; x < vx + vw; x++) {
			for (int y = vy; y < vy + vh; y++) {
				boolean rim = x == vx || y == vy || x == vx + vw - 1 || y == vy + vh - 1;
				setBare(w, x, y, CAVE_Z, rim
						? Tile.TileType.TYPE_WALL_STEEL : Tile.TileType.TYPE_PLATE);
			}
		}
		setBare(w, vx, vy + vh / 2, CAVE_Z, Tile.TileType.TYPE_PLATE); // vault doorway
		setBare(w, vx + vw / 2, vy, CAVE_Z, Tile.TileType.TYPE_DUCT); // duct through the wall

		// Furnishing, annex-sized: a crate pair by the south wall, the
		// vault's small locked cache, and the ceiling shaft over the hall.
		w.spawnEntity(Item.crate(x0 + 2.5, y0 + H - 2.5, CAVE_Z));
		w.spawnEntity(Item.crate(x0 + 3.5, y0 + H - 2.5, CAVE_Z));
		w.spawnEntity(Item.food(vx + 1.5, vy + 1.5, CAVE_Z));
		dropShaft(w, x0 + 1, x0 + 7, y0 + 1, y0 + H - 2);

		finishBase(w, cols, rows, x0, y0, W, H, vx, vy, vh);
	}

	/**
	 * The shared finishing pass for either plan: the 2-wide blast mouth in
	 * the west shell, the paved gallery tunnelled out to the nearest
	 * walkable cavern (un-carving everything if no way out exists), the two
	 * doors, and their switches.
	 */
	private static boolean finishBase(World w, int cols, int rows, int x0, int y0,
			int W, int H, int vx, int vy, int vh) {
		return finishBase(w, cols, rows, x0, y0, W, H, vx, vy, vh, y0 + H / 2 - 1);
	}

	/**
	 * As above, with the mouth row given rather than assumed.
	 *
	 * <p>The assumed row is the shell's midpoint, which is the spine's middle
	 * in a plan whose spine is in the middle. It is not in every plan: put four
	 * bands under one shell and the midpoint lands on a partition wall, and the
	 * mouth is cut through the one row that was holding two rooms apart.
	 *
	 * <p>Returns whether the base survived. A site with no way out through the
	 * rock is un-carved back to wall here, and a caller that has built anything
	 * else — another floor, say — has to know to take it back down too.
	 */
	private static boolean finishBase(World w, int cols, int rows, int x0, int y0,
			int W, int H, int vx, int vy, int vh, int my) {
		setBare(w, x0, my, CAVE_Z, Tile.TileType.TYPE_PAVED);
		setBare(w, x0, my + 1, CAVE_Z, Tile.TileType.TYPE_PAVED);
		java.util.List<int[]> gallery = carveGallery(w, cols, rows, x0 - 1, my,
				new int[][] { { x0, my }, { x0, my + 1 } });
		if (gallery == null) {
			// No way out through the rock (a sealed map corner): un-carve, a
			// walled-off installation would fail the connectivity audit.
			for (int x = x0; x < x0 + W; x++) {
				for (int y = y0; y < y0 + H; y++) {
					setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_WALL);
				}
			}
			return false;
		}
		// The doors themselves: a two-tile blast door across the mouth and a
		// grate on the vault. Doors are ordinary non-living entities (they
		// ride the entity stream to the web client, which draws their sliding
		// leaves) -- with the vault duct as the constant small-body way in.
		net.hedinger.prototype.entities.Door blast = new net.hedinger.prototype.entities.Door(
				x0, my, CAVE_Z, 1, net.hedinger.prototype.entities.Door.BLAST, 2);
		net.hedinger.prototype.entities.Door grate = new net.hedinger.prototype.entities.Door(
				vx, vy + vh / 2, CAVE_Z, 1, net.hedinger.prototype.entities.Door.GRATE);
		w.addDoor(blast);
		w.addDoor(grate);

		// Switches on both sides of each door, wired to it, so a body is
		// never trapped on either side -- and deliberately NOT beside their
		// doors: the indicator trail from switch to door is the thing that
		// says what operates what, so give it distance to say it. The blast
		// door runs on weight-driven pressure plates -- anything crossing
		// them parts the leaves. The vault runs on intent-driven buttons: a
		// body must deliberately press (the A_INTERACT actuator), so only a mind
		// that has learned to use them opens the grate -- everything else
		// takes the crawl duct. Wiring also stops the doors' idle cycling.
		int[] outer = gallery.get(Math.min(3, gallery.size() - 1));
		wireSwitch(w, outer[0], outer[1], blast,
				net.hedinger.prototype.entities.Switch.PLATE); // down the gallery
		wireSwitch(w, x0 + 3, my, blast,
				net.hedinger.prototype.entities.Switch.PLATE); // out on the hall deck
		wireSwitch(w, vx - 3, vy + vh / 2, grate,
				net.hedinger.prototype.entities.Switch.BUTTON); // mid-hall, facing the vault
		wireSwitch(w, vx + 2, vy + vh - 2, grate,
				net.hedinger.prototype.entities.Switch.BUTTON); // the vault's far corner
		return true;
	}

	/**
	 * A ventilation shaft dropping from the surface into the base: the third
	 * way in -- one-way, by gravity, hazard-striped up top and open to the
	 * base's lights below. Scans the given window (base-interior tiles, in
	 * both levels' shared coordinates) for a surface tile that is plain open
	 * ground with all eight neighbours walkable -- removing such an interior
	 * tile cannot sever a surface path, and bodies can actually reach the
	 * lip -- and converts the first fit. Skips quietly when the surface
	 * overhead refuses (water, rock, or a link station's ramps).
	 */
	private static void dropShaft(World w, int lx0, int lx1, int ly0, int ly1) {
		for (int x = lx0; x <= lx1; x++) {
			for (int y = ly0; y <= ly1; y++) {
				Tile s = w.getTile(x, y, SURFACE_Z);
				if (!s.isWalkable() || s.getType() == Tile.TileType.TYPE_RAMPUP
						|| s.getType() == Tile.TileType.TYPE_RAMPDOWN) {
					continue;
				}
				boolean interior = true;
				for (int dx = -1; dx <= 1 && interior; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						if (!w.getTile(x + dx, y + dy, SURFACE_Z).isWalkable()) {
							interior = false;
							break;
						}
					}
				}
				if (interior) {
					w.setTile(x, y, SURFACE_Z, Tile.TileType.TYPE_SHAFT);
					w.getTile(x, y, SURFACE_Z).setFertility(0);
					return;
				}
			}
		}
	}

	/** One switch: the floor tile with the baked pedestal base, plus the
	 *  Switch entity that senses (and wires) its door. */
	private static void wireSwitch(World w, int x, int y,
			net.hedinger.prototype.entities.Door door, int mode) {
		setBare(w, x, y, CAVE_Z, Tile.TileType.TYPE_SWITCH);
		w.spawnEntity(new net.hedinger.prototype.entities.Switch(x, y, CAVE_Z, door, mode));
	}

	/**
	 * The all-rock rectangle of {@code pw x ph} nearest the map's centre on
	 * the cave level; null when none fits. All-rock is the whole safety
	 * argument: caverns, pools and link stations are all non-WALL tiles, so
	 * requiring solid rock means carving overwrites nothing that already
	 * works -- the shell may stand flush against a cavern wall, which only
	 * reads as the buried structure surfacing in it.
	 */
	private static int[] findRockPocket(World w, int cols, int rows, int pw, int ph) {
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int x0 = 2; x0 + pw < cols - 2; x0++) {
			scan: for (int y0 = 2; y0 + ph < rows - 2; y0++) {
				for (int x = x0; x < x0 + pw; x++) {
					for (int y = y0; y < y0 + ph; y++) {
						if (w.getTile(x, y, CAVE_Z).getType() != Tile.TileType.TYPE_WALL) {
							continue scan;
						}
					}
				}
				double d = Math.pow(x0 + pw * 0.5 - cols * 0.5, 2)
						+ Math.pow(y0 + ph * 0.5 - rows * 0.5, 2);
				if (d < bestD) {
					bestD = d;
					best = new int[] { x0, y0 };
				}
			}
		}
		return best;
	}

	/**
	 * Tunnel a 2-wide paved entrance gallery from an installation mouth to
	 * the nearest already-walkable cave tile, breadth-first through solid
	 * rock only -- so the gallery meets the world exactly once, at its far
	 * end, and cannot nick a pool or cavern on the way. {@code startX,startY}
	 * is the first rock tile beyond the mouth; {@code mouths} are the mouth
	 * tiles themselves (marked visited, so the search cannot turn around and
	 * call its own doorway daylight), with the first one also setting the
	 * preferred initial digging direction. Deterministic: derived neighbour
	 * order, no RNG.
	 */
	private static java.util.List<int[]> carveGallery(World w, int cols, int rows,
			int startX, int startY, int[][] mouths) {
		if (startX < 1 || startY < 1 || startX >= cols - 1 || startY >= rows - 1) {
			return null;
		}
		int[][] prev = new int[cols][rows];
		for (int[] c : prev) {
			java.util.Arrays.fill(c, -1);
		}
		java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
		prev[startX][startY] = startX * rows + startY; // start marks itself
		// The mouth tiles are walkable but they're where we CAME from -- mark
		// them visited so the search can't turn around and call them daylight.
		for (int[] m : mouths) {
			prev[m[0]][m[1]] = startX * rows + startY;
		}
		q.add(new int[] { startX, startY });
		// Prefer digging straight out from the mouth before wandering.
		int[] away = { Integer.signum(startX - mouths[0][0]),
				Integer.signum(startY - mouths[0][1]) };
		int[][] dirs = { away, { away[1], away[0] }, { -away[1], -away[0] },
				{ -away[0], -away[1] } };
		while (!q.isEmpty()) {
			int[] p = q.poll();
			for (int[] d : dirs) {
				int nx = p[0] + d[0], ny = p[1] + d[1];
				if (nx < 1 || ny < 1 || nx >= cols - 1 || ny >= rows - 1
						|| prev[nx][ny] != -1) {
					continue;
				}
				Tile t = w.getTile(nx, ny, CAVE_Z);
				if (t.isWalkable()) {
					// Found daylight: pave the path back to the mouth, and
					// hand the caller the mouth-first path (real paved tiles,
					// where a switch can safely stand).
					java.util.List<int[]> path = new java.util.ArrayList<int[]>();
					int cx = p[0], cy = p[1];
					while (!(cx == startX && cy == startY)) {
						paveGalleryTile(w, cx, cy, cols, rows);
						path.add(new int[] { cx, cy });
						int code = prev[cx][cy];
						cx = code / rows;
						cy = code % rows;
					}
					paveGalleryTile(w, startX, startY, cols, rows);
					path.add(new int[] { startX, startY });
					java.util.Collections.reverse(path);
					return path;
				}
				if (t.getType() == Tile.TileType.TYPE_WALL) {
					prev[nx][ny] = p[0] * rows + p[1];
					q.add(new int[] { nx, ny });
				}
			}
		}
		return null;
	}

	/** One gallery step: a 2x2 brush of paved floor through rock only (built
	 *  walls and open ground stand), clamped inside the rim -- so the gallery
	 *  runs body-wide like the cave backbone's corridors. */
	private static void paveGalleryTile(World w, int x, int y, int cols, int rows) {
		for (int dx = 0; dx <= 1; dx++) {
			for (int dy = 0; dy <= 1; dy++) {
				int cx = x + dx, cy = y + dy;
				if (cx < 1 || cy < 1 || cx >= cols - 1 || cy >= rows - 1) {
					continue;
				}
				if (w.getTile(cx, cy, CAVE_Z).getType() == Tile.TileType.TYPE_WALL) {
					setBare(w, cx, cy, CAVE_Z, Tile.TileType.TYPE_PAVED);
				}
			}
		}
	}

	/** Sets a tile with zero fertility: nothing grows on built ground. */
	private static void setBare(World w, int x, int y, int z, Tile.TileType t) {
		w.setTile(x, y, z, t);
		w.getTile(x, y, z).setFertility(0);
	}

	/**
	 * The ravine: one long gorge torn through the surface, dozens of hole
	 * tiles in a wandering band two to three wide, with the cave level
	 * reading through the pit veil down its whole length. The stations'
	 * one-tile pits prove there is a world below; the ravine is where that
	 * fact becomes geography — a thing you walk along, plan around, and see
	 * the caves slide beneath as you pan.
	 *
	 * <p>Two causeways of untouched ground cross it at the third points, so
	 * the banks stay one walkable surface: a gorge with no crossing would cut
	 * the world in half AFTER {@link #connectLevels} certified it whole, and
	 * whatever lived on the smaller side would starve against an audit that
	 * no longer runs. The hole art rims each causeway's flanks by itself —
	 * a pit rims every side it meets ground.
	 *
	 * <p>Sited like the rivers: probe candidate spans from the seeded RNG and
	 * take the first that fits. A span fits only on natural open ground —
	 * margin from the map rim, nothing man-made or already sunken within
	 * three tiles (station ramps, aprons' pits, the drop shaft), and no water
	 * within one (a gorge swallowing a river's middle would leave its lower
	 * half flowing from nowhere). A world whose surface never offers such a
	 * span simply goes without; nothing downstream depends on one existing.
	 */
	private static void carveRavine(World w, int cols, int rows) {
		for (int attempt = 0; attempt < 60; attempt++) {
			boolean horizontal = (attempt & 1) == 0;
			int len = 26 + Utils.random(8);
			int along = horizontal ? cols : rows;
			int across = horizontal ? rows : cols;
			if (along < len + 12 || across < 20) {
				return; // a map too small for a gorge
			}
			int s0 = 6 + Utils.random(along - len - 12);
			int c0 = 8 + Utils.random(across - 16);

			// The band, precomputed so fitting and carving see the same tiles.
			// The drift is a clamped random walk — one tile of sideways wander
			// per step at most — so the gorge meanders instead of staggering,
			// and the band stays 4-connected at every width: a jumpier drift
			// left runs touching only at corners, which reads as separate pits
			// rather than one cut.
			int[][] band = new int[len][2]; // {edge offset, width} per step
			int off = 0;
			for (int t = 0; t < len; t++) {
				double turn = Utils.noise2(s0 * 3 + t * 2, c0 * 5 + 900, 0.16);
				off += turn > 0.6 ? 1 : turn < 0.4 ? -1 : 0;
				off = Math.max(-4, Math.min(4, off));
				int width = Utils.noise2(s0 + t, c0 + 1700, 0.15) > 0.62 ? 3 : 2;
				band[t][0] = c0 + off;
				band[t][1] = width;
			}
			boolean fits = true;
			for (int t = 0; t < len && fits; t++) {
				for (int d = 0; d < band[t][1] && fits; d++) {
					int x = horizontal ? s0 + t : band[t][0] + d;
					int y = horizontal ? band[t][0] + d : s0 + t;
					fits = ravineCarvable(w, cols, rows, x, y);
				}
			}
			if (!fits) {
				continue;
			}
			int c1 = len / 3, c2 = 2 * len / 3; // the causeways, two tiles each
			for (int t = 0; t < len; t++) {
				if (t == c1 || t == c1 + 1 || t == c2 || t == c2 + 1) {
					continue; // the untouched ground the banks cross on
				}
				for (int d = 0; d < band[t][1]; d++) {
					int x = horizontal ? s0 + t : band[t][0] + d;
					int y = horizontal ? band[t][0] + d : s0 + t;
					setBare(w, x, y, SURFACE_Z, Tile.TileType.TYPE_HOLE);
				}
			}
			return;
		}
	}

	/** Whether the surface at (x, y) may become ravine: natural open ground,
	 *  well inside the rim, nothing man-made or already sunken within three
	 *  tiles, no water within one. */
	private static boolean ravineCarvable(World w, int cols, int rows, int x, int y) {
		if (x < 4 || y < 4 || x >= cols - 4 || y >= rows - 4) {
			return false;
		}
		switch (w.getTile(x, y, SURFACE_Z).getType()) {
		case TYPE_FLOOR:
		case TYPE_STONE:
		case TYPE_ROCKY:
		case TYPE_SAND:
		case TYPE_MUD:
		case TYPE_COVER:
		case TYPE_REEDS:
			break;
		default:
			return false;
		}
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				Tile.TileType n = w.getTile(x + dx, y + dy, SURFACE_Z).getType();
				boolean near = Math.abs(dx) <= 1 && Math.abs(dy) <= 1;
				if (n == Tile.TileType.TYPE_RAMPUP || n == Tile.TileType.TYPE_RAMPDOWN
						|| n == Tile.TileType.TYPE_HOLE || n == Tile.TileType.TYPE_SHAFT
						|| n == Tile.TileType.TYPE_PAVED || n == Tile.TileType.TYPE_PLATE
						|| n == Tile.TileType.TYPE_DOCK || n == Tile.TileType.TYPE_RAIL) {
					return false;
				}
				if (near && (n == Tile.TileType.TYPE_WATER
						|| n == Tile.TileType.TYPE_SHALLOWS)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Wire the surface and cave into a single connected space, at any size.
	 *
	 * <ol>
	 *   <li>Label the surface's walkable regions. Tiny walled-off nooks (fewer
	 *       than {@code MIN_REGION} tiles) are sealed to rock — they'd be
	 *       unreachable dead space otherwise.</li>
	 *   <li>Place a spread of underground link stations (grid-sampled, scaled to
	 *       the map's area), guaranteeing at least one inside every surviving
	 *       surface region — so no region is stranded on the surface.</li>
	 *   <li>Carve a cave backbone: connect the station landings with corridors,
	 *       so the underground is one traversable tunnel network linking the
	 *       regions.</li>
	 *   <li>Seal any cave pocket the backbone doesn't reach, so there are no
	 *       isolated underground areas.</li>
	 * </ol>
	 *
	 * The result is a world where any walkable tile can be reached from any other
	 * by land or by tunnel — which {@link WorldAudit#connectivity} verifies.
	 */
	private static void connectLevels(World w, int cols, int rows) {
		// 1. Label surface walkable regions (4-neighbour flood fill).
		int[][] label = new int[cols][rows];
		for (int[] c : label) {
			java.util.Arrays.fill(c, -1);
		}
		java.util.List<java.util.List<int[]>> regions = new java.util.ArrayList<java.util.List<int[]>>();
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				if (label[x][y] != -1 || !w.getTile(x, y, SURFACE_Z).isWalkable()) {
					continue;
				}
				java.util.List<int[]> members = new java.util.ArrayList<int[]>();
				int id = regions.size();
				java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
				label[x][y] = id;
				q.add(new int[] { x, y });
				while (!q.isEmpty()) {
					int[] p = q.poll();
					members.add(p);
					int[][] card = { { p[0] + 1, p[1] }, { p[0] - 1, p[1] },
							{ p[0], p[1] + 1 }, { p[0], p[1] - 1 } };
					for (int[] n : card) {
						if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows
								&& label[n[0]][n[1]] == -1 && w.getTile(n[0], n[1], SURFACE_Z).isWalkable()) {
							label[n[0]][n[1]] = id;
							q.add(n);
						}
					}
				}
				regions.add(members);
			}
		}

		// The largest surface region is the world's mainland: the connectivity
		// repair floods from here, so the main surface is never what gets sealed.
		int[] mainSeed = null;
		int mainSize = -1;
		for (java.util.List<int[]> members : regions) {
			if (members.size() > mainSize) {
				mainSize = members.size();
				mainSeed = members.get(0);
			}
		}

		// 2 + 3. For each region: seal the tiny nooks, and give every substantial
		// region at least one link station (a grid spread scaled to area gives big
		// regions several). A region that can host no station at all — too cramped,
		// or hard against a border — is itself isolated, so it is sealed too rather
		// than left stranded with no way in or out.
		final int MIN_REGION = 12;
		int target = Math.max(1, (int) Math.round(cols * (double) rows / 1600.0));
		int step = Math.max(16, (int) Math.round(Math.sqrt(cols * (double) rows / target)));
		java.util.List<int[]> sites = new java.util.ArrayList<int[]>();
		for (java.util.List<int[]> members : regions) {
			boolean seal = members.size() < MIN_REGION;
			if (!seal) {
				int before = sites.size();
				for (int gx = step / 2; gx < cols; gx += step) {
					for (int gy = step / 2; gy < rows; gy += step) {
						int[] s = nearestFittingSite(members, gx, gy, cols, rows, sites);
						if (s != null) {
							sites.add(s);
						}
					}
				}
				if (sites.size() == before) {
					int[] s = bestFittingSite(members, cols, rows, sites); // grid missed it
					if (s != null) {
						sites.add(s);
					} else {
						seal = true; // no station fits anywhere: this region is unlinkable
					}
				}
			}
			if (seal) {
				for (int[] p : members) {
					w.setTile(p[0], p[1], SURFACE_Z, Tile.TileType.TYPE_WALL);
					w.getTile(p[0], p[1], SURFACE_Z).setFertility(0);
				}
			}
		}
		if (sites.isEmpty()) {
			sites.add(new int[] { Math.min(cols - 5, Math.max(3, cols / 2)),
					Math.min(rows - 4, Math.max(2, rows / 2)) });
		}

		// 4. Carve a station at each site, then link the cave landings. Stations
		//    take their direction in turn rather than by lot: a world with four
		//    of them has one facing each way by construction. Left to a hash the
		//    four are only likely, and "likely" over the handful of stations a
		//    map holds means a world that quietly has no north ramp in it — the
		//    freedom would be in the engine and invisible in the world, which is
		//    the state this whole change existed to leave. The starting cardinal
		//    is hashed from the first site so the cycle does not always open the
		//    same way.
		int dirOffset = sites.isEmpty() ? 0
				: (int) (net.hedinger.prototype.engine.GroundTextures.hash01(
						sites.get(0)[0], sites.get(0)[1], 61) * 4) & 3;
		for (int i = 0; i < sites.size(); i++) {
			int[] s = sites.get(i);
			linkStation(w, s[0], s[1], (i + dirOffset) & 3);
		}
		for (int i = 1; i < sites.size(); i++) {
			carveCaveCorridor(w, sites.get(i - 1), sites.get(i), cols, rows);
		}

		// 5. Connectivity repair: flood the whole walkable space from the mainland
		//    (crossing levels exactly as a body does), and seal anything it can't
		//    reach — on either level. Whatever survives is, by construction, a
		//    single connected region: mainland + tunnel network + every surface
		//    region and cavern the tunnels tie in. Isolated pockets become rock.
		sealUnreachable(w, cols, rows, mainSeed != null ? mainSeed : sites.get(0));
	}

	/** The station-fitting region tile nearest {@code (gx,gy)} that isn't already
	 *  crowded by an existing site; null if the region has none near this point. */
	private static int[] nearestFittingSite(java.util.List<int[]> members, int gx, int gy,
			int cols, int rows, java.util.List<int[]> taken) {
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int[] p : members) {
			int sx = p[0], sy = p[1];
			// The station's footprint turns with the direction this site faces,
			// so the room it needs is asked for in its own frame rather than as
			// a fixed east-west box.
			if (!stationFits(sx, sy, cols, rows)) {
				continue;
			}
			double d = (sx - gx) * (double) (sx - gx) + (sy - gy) * (double) (sy - gy);
			if (d >= bestD || d > (double) (cols + rows)) {
				continue; // only snap a grid point to a site reasonably near it
			}
			boolean crowded = false;
			for (int[] t : taken) {
				if (Math.abs(t[0] - sx) < 6 && Math.abs(t[1] - sy) < 6) {
					crowded = true;
					break;
				}
			}
			if (!crowded) {
				bestD = d;
				best = new int[] { sx, sy };
			}
		}
		return best;
	}

	/** Any station-fitting tile in the region nearest its centroid (no distance
	 *  cap), avoiding crowding — the fallback when the grid snapped nothing. Null
	 *  only when the region can host no station at all. */
	private static int[] bestFittingSite(java.util.List<int[]> members, int cols, int rows,
			java.util.List<int[]> taken) {
		double cx = 0, cy = 0;
		for (int[] p : members) {
			cx += p[0];
			cy += p[1];
		}
		cx /= members.size();
		cy /= members.size();
		int[] best = null;
		double bestD = Double.MAX_VALUE;
		for (int[] p : members) {
			int sx = p[0], sy = p[1];
			if (sx < 3 || sx > cols - 5 || sy < 2 || sy > rows - 4) {
				continue;
			}
			boolean crowded = false;
			for (int[] t : taken) {
				if (Math.abs(t[0] - sx) < 6 && Math.abs(t[1] - sy) < 6) {
					crowded = true;
					break;
				}
			}
			if (crowded) {
				continue;
			}
			double d = (sx - cx) * (sx - cx) + (sy - cy) * (sy - cy);
			if (d < bestD) {
				bestD = d;
				best = new int[] { sx, sy };
			}
		}
		return best;
	}

	/**
	 * Rivers as seeded droplet walks (WORLDGEN-RESEARCH.md #2): each starts on
	 * high, damp ground and follows the elevation field downhill — the SAME
	 * noise the biome classifier reads, so rivers run where the terrain says
	 * they should — carving a channel that widens as it runs, until it reaches
	 * standing water, a basin with no way down (where it ponds), or the rim.
	 *
	 * <p>Crossable by construction: every few steps the whole cross-section is
	 * laid as SHALLOWS (a ford), and the later shore pass fringes the rest of
	 * the channel — so rivers structure the map without severing the walkable
	 * region (the audit's connectivity gate holds).
	 */
	private static void carveRivers(World w, int cols, int rows) {
		int n = Math.max(2, (int) Math.round(cols * (double) rows / 3000.0));
		for (int r = 0; r < n; r++) {
			// Source: the highest of a handful of probes on damp, open ground --
			// ridge springs, not desert trickles (and never inside the rock line).
			int sx = -1, sy = -1;
			double best = 0.56;
			for (int t = 0; t < 30; t++) {
				int px = 4 + Utils.random(cols - 8), py = 4 + Utils.random(rows - 8);
				double e = Utils.noise2(px, py, 0.055);
				double m = Utils.noise2(px + 500, py + 300, 0.075);
				if (e > best && e < 0.80 && m > 0.35) {
					best = e;
					sx = px;
					sy = py;
				}
			}
			if (sx >= 0) {
				runRiver(w, cols, rows, sx, sy);
			}
		}
	}

	/** One river: walk downhill with a light meander, carving as it goes. */
	private static void runRiver(World w, int cols, int rows, int x, int y) {
		int prevDx = 0, prevDy = 0, sinceFord = 0;
		for (int steps = 0; steps < cols + rows; steps++) {
			if (x < 3 || y < 3 || x >= cols - 3 || y >= rows - 3) {
				return; // reached the rim
			}
			if (steps > 4 && w.getTile(x, y, SURFACE_Z).getType() == Tile.TileType.TYPE_WATER) {
				return; // joined a lake (or an earlier river)
			}
			// The channel: a brook near the spring, two tiles wide lower down.
			boolean ford = ++sinceFord >= 9;
			int width = steps < 12 ? 1 : 2;
			for (int dx = 0; dx < width; dx++) {
				for (int dy = 0; dy < width; dy++) {
					int cx = x + dx, cy = y + dy;
					if (cx < 2 || cy < 2 || cx >= cols - 2 || cy >= rows - 2) {
						continue;
					}
					w.setTile(cx, cy, SURFACE_Z, ford
							? Tile.TileType.TYPE_SHALLOWS : Tile.TileType.TYPE_WATER);
					w.getTile(cx, cy, SURFACE_Z).setFertility(0);
				}
			}
			if (ford) {
				sinceFord = 0;
			}
			// Descend: the lowest of the four neighbours, with a whisper of
			// deterministic jitter so parallel rivers don't run identical rails,
			// and never straight back uphill the way we came.
			int bx = 0, by = 0;
			double bestE = Double.MAX_VALUE;
			for (int k = 0; k < 4; k++) {
				int dx = k == 0 ? 1 : k == 1 ? -1 : 0;
				int dy = k == 2 ? 1 : k == 3 ? -1 : 0;
				if (dx == -prevDx && dy == -prevDy && (dx != 0 || dy != 0)) {
					continue; // no immediate backtrack
				}
				double e = Utils.noise2(x + dx, y + dy, 0.055)
						+ 0.06 * GroundTextures.hash01(x + dx, y + dy, 71);
				if (e < bestE) {
					bestE = e;
					bx = dx;
					by = dy;
				}
			}
			double here = Utils.noise2(x, y, 0.055);
			if (bestE >= here + 0.045) {
				// A true basin with no lake: the river ponds and ends here.
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						int cx = x + dx, cy = y + dy;
						if (cx >= 2 && cy >= 2 && cx < cols - 2 && cy < rows - 2) {
							w.setTile(cx, cy, SURFACE_Z, Tile.TileType.TYPE_WATER);
							w.getTile(cx, cy, SURFACE_Z).setFertility(0);
						}
					}
				}
				return;
			}
			prevDx = bx;
			prevDy = by;
			x += bx;
			y += by;
		}
	}

	/**
	 * Cellular-automata smoothing over the cave's rock/floor boundary
	 * (WORLDGEN-RESEARCH.md #3): a wall spur with almost no rock around it
	 * opens, a floor sliver walled nearly all round closes. Special ground
	 * (pools, fungus, crystals, vents, pits) is fixed truth — it neither
	 * flips nor counts as more than what it is (crystal counts as rock, all
	 * else as open), so the pass only rounds edges, never rewrites features.
	 */
	private static void smoothCave(World w, int cols, int rows) {
		for (int round = 0; round < 2; round++) {
			Tile.TileType[][] snap = new Tile.TileType[cols][rows];
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					snap[x][y] = w.getTile(x, y, CAVE_Z).getType();
				}
			}
			for (int x = 1; x < cols - 1; x++) {
				for (int y = 1; y < rows - 1; y++) {
					Tile.TileType t = snap[x][y];
					if (t != Tile.TileType.TYPE_WALL && t != Tile.TileType.TYPE_STONE) {
						continue;
					}
					int rock = 0;
					for (int dx = -1; dx <= 1; dx++) {
						for (int dy = -1; dy <= 1; dy++) {
							if (dx == 0 && dy == 0) {
								continue;
							}
							Tile.TileType nt = snap[x + dx][y + dy];
							if (nt == Tile.TileType.TYPE_WALL
									|| nt == Tile.TileType.TYPE_CRYSTAL) {
								rock++;
							}
						}
					}
					if (t == Tile.TileType.TYPE_WALL && rock <= 3) {
						w.setTile(x, y, CAVE_Z, Tile.TileType.TYPE_STONE);
						w.getTile(x, y, CAVE_Z).setFertility(0);
					} else if (t == Tile.TileType.TYPE_STONE && rock >= 6) {
						w.setTile(x, y, CAVE_Z, Tile.TileType.TYPE_WALL);
						w.getTile(x, y, CAVE_Z).setFertility(0);
					}
				}
			}
		}
	}

	/**
	 * Carve a wandering 2-wide tunnel between two station landings: a directed
	 * random walker with heading persistence, so the backbone meanders like a
	 * worm-bore instead of an L of hallways (WORLDGEN-RESEARCH.md #4). The odd
	 * bulge opens a small chamber. The walk is biased toward the target and
	 * hard-capped; whatever distance remains when the cap hits is closed with
	 * the old straight carve, so the corridor contract — the two landings end
	 * up connected — is unconditional.
	 */
	private static void carveCaveCorridor(World w, int[] a, int[] b, int cols, int rows) {
		int x = a[0], y = a[1];
		int dx = Integer.signum(b[0] - a[0]), dy = 0;
		if (dx == 0) {
			dy = Integer.signum(b[1] - a[1]) == 0 ? 1 : Integer.signum(b[1] - a[1]);
		}
		int cap = 3 * (Math.abs(b[0] - a[0]) + Math.abs(b[1] - a[1])) + 40;
		for (int s = 0; s < cap && (x != b[0] || y != b[1]); s++) {
			carveCaveTile(w, x, y, cols, rows);
			if (Utils.random() < 0.05) {
				// A bulge: the tunnel balloons into a small chamber.
				carveCaveTile(w, x - 1, y - 1, cols, rows);
				carveCaveTile(w, x + 1, y + 1, cols, rows);
			}
			int tx = Integer.signum(b[0] - x), ty = Integer.signum(b[1] - y);
			boolean wayward = (dx != 0 && tx != 0 && dx != tx) || (dy != 0 && ty != 0 && dy != ty);
			if (wayward || Utils.random() < 0.35) {
				// Re-aim at the target: pick the axis weighted by remaining
				// distance, so long legs still meander but converge.
				int rx = Math.abs(b[0] - x), ry = Math.abs(b[1] - y);
				if (rx + ry > 0 && Utils.random(rx + ry) < rx) {
					dx = tx;
					dy = 0;
				} else {
					dx = 0;
					dy = ty;
				}
				if (dx == 0 && dy == 0) {
					dx = tx != 0 ? tx : 1; // degenerate: already on the target axis
				}
			}
			x = Math.max(1, Math.min(cols - 2, x + dx));
			y = Math.max(1, Math.min(rows - 2, y + dy));
		}
		// Close any remainder (the cap hit): the old deterministic straight carve.
		while (x != b[0]) {
			carveCaveTile(w, x, y, cols, rows);
			x += Integer.signum(b[0] - x);
		}
		while (y != b[1]) {
			carveCaveTile(w, x, y, cols, rows);
			y += Integer.signum(b[1] - y);
		}
		carveCaveTile(w, b[0], b[1], cols, rows);
	}

	/** A 2x2 brush of cave floor (clamped inside the sealed rim), so corridors are
	 *  wide enough for a body to pass. */
	private static void carveCaveTile(World w, int x, int y, int cols, int rows) {
		for (int dx = 0; dx <= 1; dx++) {
			for (int dy = 0; dy <= 1; dy++) {
				int cx = x + dx, cy = y + dy;
				if (cx < 1 || cy < 1 || cx >= cols - 1 || cy >= rows - 1) {
					continue;
				}
				Tile.TileType at = w.getTile(cx, cy, CAVE_Z).getType();
				if (at == Tile.TileType.TYPE_RAMPUP || at == Tile.TileType.TYPE_RAMPDOWN) {
					// A corridor that meets a ramp JOINS it — a ramp is walkable
					// floor already. Paving over it would leave the station's two
					// levels tied together by nothing but the pit beside it, and
					// silently: the corridor is stone either way, so the map looks
					// right and only the route up is gone. That stayed hidden
					// while every ramp pointed east and the corridors mostly ran
					// the other way; now that a station faces the next station,
					// the tunnel arrives along the slope every time.
					continue;
				}
				if (rampLanding(w, cx, cy)) {
					// Nor the rock at the head of the cut. An up ramp that runs
					// out into open floor climbs to nothing: the slope is a
					// staircase into a ceiling, and the surface tile it should
					// have arrived at is left resting on air. Measured before
					// this guard: three of eight stations had lost their landing
					// rock to a passing corridor.
					continue;
				}
				w.setTile(cx, cy, CAVE_Z, Tile.TileType.TYPE_STONE);
				w.getTile(cx, cy, CAVE_Z).setFertility(0);
			}
		}
	}

	/** Flood the whole walkable space from a surface seed, crossing levels the way
	 *  a land body does (walk onto a HOLE to fall; walk off a ramp's far edge to
	 *  change level), then seal every walkable tile the flood never reaches — on
	 *  either level. What remains is a single connected region. Mirrors
	 *  {@link WorldAudit#connectivity}'s traversal, so the audit agrees by
	 *  construction. */
	private static void sealUnreachable(World w, int cols, int rows, int[] surfaceSeed) {
		boolean[][][] seen = new boolean[w.getLevels()][cols][rows];
		java.util.Deque<int[]> q = new java.util.ArrayDeque<int[]>();
		if (w.getTile(surfaceSeed[0], surfaceSeed[1], SURFACE_Z).isWalkable()) {
			seen[SURFACE_Z][surfaceSeed[0]][surfaceSeed[1]] = true;
			q.add(new int[] { surfaceSeed[0], surfaceSeed[1], SURFACE_Z });
		}
		while (!q.isEmpty()) {
			int[] p = q.poll();
			int x = p[0], y = p[1], z = p[2];
			int[][] card = { { x + 1, y }, { x - 1, y }, { x, y + 1 }, { x, y - 1 } };
			for (int[] n : card) {
				floodVisit(w, seen, q, n[0], n[1], z, cols, rows); // land
				// Descend: a cardinal HOLE drops to the tile directly below it.
				if (n[0] >= 0 && n[1] >= 0 && n[0] < cols && n[1] < rows && z - 1 >= 0
						&& w.getTile(n[0], n[1], z).isDrop()) {
					floodVisit(w, seen, q, n[0], n[1], z - 1, cols, rows);
				}
			}
			// Ramps: stepping off the slope's own side lands a level up (RAMPUP)
			// or down (RAMPDOWN). Which side that is comes from the tile, so
			// this follows a ramp facing any cardinal — the same rule
			// Tile.isConnected enforces for a body actually walking it.
			Tile.TileType rt = w.getTile(x, y, z).getType();
			if (rt == Tile.TileType.TYPE_RAMPUP || rt == Tile.TileType.TYPE_RAMPDOWN) {
				int exit = w.getTile(x, y, z).rampExit();
				int nx = x + Tile.dirDx(exit), ny = y + Tile.dirDy(exit);
				int nz = rt == Tile.TileType.TYPE_RAMPUP ? z + 1 : z - 1;
				if (nz >= 0 && nz < w.getLevels() && nx >= 0 && ny >= 0 && nx < cols && ny < rows) {
					floodVisit(w, seen, q, nx, ny, nz, cols, rows);
				}
			}
		}
		for (int z = 0; z < w.getLevels(); z++) {
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, z).isWalkable() && !seen[z][x][y]) {
						w.setTile(x, y, z, Tile.TileType.TYPE_WALL);
						w.getTile(x, y, z).setFertility(0);
					}
				}
			}
		}
	}

	private static void floodVisit(World w, boolean[][][] seen, java.util.Deque<int[]> q,
			int x, int y, int z, int cols, int rows) {
		if (x >= 0 && y >= 0 && x < cols && y < rows && !seen[z][x][y]
				&& w.getTile(x, y, z).isWalkable()) {
			seen[z][x][y] = true;
			q.add(new int[] { x, y, z });
		}
	}

	/**
	 * Whether a station fits inside the map at {@code (sx, sy)}. A station's
	 * footprint reaches 3 tiles along its slope and 2 across, so whichever way
	 * it is turned it stays inside a 7x7 box centred on the site — ask for that
	 * box and the site is safe to build in any of the four directions, which is
	 * what lets the direction be chosen freely afterwards.
	 */
	private static boolean stationFits(int sx, int sy, int cols, int rows) {
		return sx >= 3 && sy >= 3 && sx < cols - 3 && sy < rows - 3;
	}

	/**
	 * A working two-way link between the surface and the cave, built to match the
	 * engine's movement rules: a ramp is floor spanning two levels, so stepping
	 * off a RAMPUP's high side lands a level up and off a RAMPDOWN's foot a level
	 * down. A HOLE is not a route at all, just a pit that drops whatever stands on
	 * it — kept here because a second way down costs nothing.
	 *
	 * <p>The whole station is laid out in the RAMP'S OWN FRAME: {@code t} runs
	 * up the slope and {@code r} across it, and both are mapped to the map's
	 * axes through the direction this site faces. So the same station is built
	 * whichever way it points, and there is one description of it rather than
	 * four. The run is two tiles wide ({@code r} of 0 and 1) so bodies pass each
	 * other on it instead of queueing on a one-tile thread. Per row:
	 * <ul>
	 *   <li><b>Down</b> — on the surface at {@code t=1}, a RAMPDOWN whose foot
	 *       faces back down-slope onto the cave floor carved at {@code t=0},
	 *       with a HOLE on that same tile that falls to the same landing.</li>
	 *   <li><b>Up</b> — in the cave at {@code t=2}, a RAMPUP whose top faces up
	 *       the slope onto the surface floor carved at {@code t=3}. The WALL
	 *       below that landing is only the rock the surface tile rests on.</li>
	 * </ul>
	 */
	private static void linkStation(World w, int sx, int sy, int u) {
		int ax = Tile.dirDx(u), ay = Tile.dirDy(u);          // up the slope
		int cx = Tile.dirDx((u + 1) & 3), cy = Tile.dirDy((u + 1) & 3); // across it

		// A small open patch on both levels so creatures can reach the link,
		// carved in the station's frame so it covers the run whichever way the
		// slope points.
		for (int t = -2; t <= 2; t++) {
			for (int r = -1; r <= 2; r++) {
				int x = sx + t * ax + r * cx, y = sy + t * ay + r * cy;
				carveTile(w, x, y, SURFACE_Z);
				carveTile(w, x, y, CAVE_Z);
			}
		}

		for (int r = 0; r <= 1; r++) {
			int bx = sx + r * cx, by = sy + r * cy;
			int x0 = bx, y0 = by;                       // t = 0: the cave landing
			int x1 = bx + ax, y1 = by + ay;             // t = 1: the descending ramp
			int x2 = bx + 2 * ax, y2 = by + 2 * ay;     // t = 2: the climbing ramp
			int x3 = bx + 3 * ax, y3 = by + 3 * ay;     // t = 3: the surface landing

			// Down: a pit, and beside it a ramp whose foot faces back down-slope;
			// the cave floor at t=0 is what both routes land on.
			w.setTile(x0, y0, SURFACE_Z, Tile.TileType.TYPE_HOLE);
			w.setTile(x1, y1, SURFACE_Z, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(x1, y1, SURFACE_Z).setRampUphill(u);
			w.setTile(x0, y0, CAVE_Z, Tile.TileType.TYPE_STONE); // landing
			w.getTile(x0, y0, CAVE_Z).setFertility(0);

			// Up: a ramp in the cave climbing onto the stone landing carved above.
			w.setTile(x2, y2, CAVE_Z, Tile.TileType.TYPE_RAMPUP);
			w.getTile(x2, y2, CAVE_Z).setRampUphill(u);
			w.setTile(x3, y3, CAVE_Z, Tile.TileType.TYPE_WALL); // rock under the landing
			w.setTile(x3, y3, SURFACE_Z, Tile.TileType.TYPE_STONE); // landing above
			w.getTile(x3, y3, SURFACE_Z).setFertility(0);
		}
	}

	/** Whether the cave tile at {@code (x, y)} is the rock an up ramp climbs
	 *  into — the head of the cut, which holds up the surface tile the climb
	 *  arrives at and which the wall art merges the ramp's top into. */
	private static boolean rampLanding(World w, int x, int y) {
		for (int d = 0; d < 4; d++) {
			int rx = x - Tile.dirDx(d), ry = y - Tile.dirDy(d);
			if (rx < 0 || ry < 0 || rx >= w.getColums() || ry >= w.getRows()) {
				continue;
			}
			Tile n = w.getTile(rx, ry, CAVE_Z);
			if (n.getType() == Tile.TileType.TYPE_RAMPUP && n.getRampUphill() == d) {
				return true;
			}
		}
		return false;
	}

	/** One tile of station apron — bare stone, on both levels.
	 *
	 *  <p>The surface apron used to be meadow, which put grass growing to the
	 *  very lip of a pit and up to the edge of a cut stone ramp. A station is
	 *  where the bedrock opens: the rock the ramp is cut into should reach the
	 *  surface around it, and the meadow should stop where the rock starts.
	 *  Stone outranks earth in the autotiling, so the apron laps out into the
	 *  grass with the same scalloped edge every other terrain boundary gets. */
	private static void carveTile(World w, int x, int y, int z) {
		if (x < 1 || y < 1 || x >= w.getColums() - 1 || y >= w.getRows() - 1) {
			return;
		}
		w.setTile(x, y, z, Tile.TileType.TYPE_STONE);
		w.getTile(x, y, z).setFertility(0);
	}

	/** A random open cave tile, for seeding the underground cohort onto stone or
	 *  fungus — never into rock, and never onto a pit or shaft (a drop on the
	 *  lowest level is bottomless, and a founder should not spawn into the void). */
	private static double[] caveSpot(World w) {
		for (int tries = 0; tries < 60; tries++) {
			double x = 2 + Utils.random() * (w.getColums() - 4);
			double y = 2 + Utils.random() * (w.getRows() - 4);
			Tile t = w.getTile(x, y, CAVE_Z);
			if (t.isWalkable() && !t.isDrop()) {
				return new double[] { x, y };
			}
		}
		return new double[] { w.getColums() / 2.0, w.getRows() / 2.0 };
	}

	/** A random open (walkable) surface tile, for scattering founders and items
	 *  onto meadow rather than into water or rock. */
	private static double[] openSpot(World w) {
		for (int tries = 0; tries < 60; tries++) {
			double x = 2 + Utils.random() * (w.getColums() - 4);
			double y = 2 + Utils.random() * (w.getRows() - 4);
			if (w.getTile(x, y, SURFACE_Z).isWalkable()) {
				return new double[] { x, y };
			}
		}
		return new double[] { w.getColums() / 2.0, w.getRows() / 2.0 };
	}

	/**
	 * A living, evolving arena, fully determined by the seed: patchy fertile
	 * grassland grazed by breeding herbivores, hunted by predators, with a
	 * scatter of items — and a {@link WorldSteward} that keeps both populations
	 * inside sane bounds so the public world never dies out or swarms. The
	 * herbivores graze/breed/starve and the predators hunt/breed/starve, so
	 * births, kills, deaths and evolution all play out on their own; the steward
	 * only catches the extremes. The returned world has ticked once, so the
	 * snapshot stream starts fully populated.
	 */
	public static World demo(long seed) {
		return demo(seed, COLS, ROWS);
	}

	/**
	 * The demo world at an arbitrary size. Populations and population bounds scale
	 * with the map's area, so a bigger world keeps roughly the same density (and
	 * the same feel) rather than becoming an empty plain — which also keeps the
	 * performance measurement honest.
	 */
	public static World demo(long seed, int cols, int rows) {
		World w = demoTerrain(seed, cols, rows);
		double scale = cols * (double) rows / (COLS * (double) ROWS);
		net.hedinger.prototype.entities.Genome[] herb = herbivoreSpecies();
		net.hedinger.prototype.entities.Genome[] pred = predSpecies();

		// Founder herbivores: metabolic grazers that breed and evolve, scattered
		// onto open meadow (never into water or rock).
		//
		// No nesters. A quarter of the founders used to home on their pheromone
		// peak to breed and leave a Nest fixture there, and measured over 40k ticks
		// that lineage drove the plain breeders extinct by tick 20k -- a decisive
		// outcome for a mechanic whose fixture does nothing at all (no shelter, no
		// safety, no bonus; the minded cohort can neither build one nor perceive
		// one). A strategy that wins that hard while meaning that little is shaping
		// the ecosystem for no reason anyone chose, so it is out of the seeded world
		// until it earns its place. The behaviour and the fixture both still exist
		// and stay covered by the scenario suite.
		for (int i = 0; i < sc(26, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.breeder(p[0], p[1], SURFACE_Z, herb[i % herb.length])
					.withHerding()); // corpse span comes from its body -- see configureGenomeBody
		}
		// Founder predators (few: predation should track the prey, not cap it).
		for (int i = 0; i < sc(4, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.predator(p[0], p[1], SURFACE_Z, pred[i % pred.length]));
		}
		// A small parallel cohort of minded creatures (fully-random brains) that
		// competes inside the same world as the scripted species — the A/B seam
		// where evolvable behaviour proves itself (or doesn't) against the hardcoded
		// baseline. The steward keeps this cohort topped up as it dies off.
		int nMinded = Math.max(5, sc(5, scale));
		net.hedinger.prototype.entities.Genome[] minded = mindedSpecies(nMinded);
		for (int i = 0; i < nMinded; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], SURFACE_Z, minded[i]));
		}
		// The underground gets its own minded seed group — separate founder
		// lineages, so cave life starts as its own experiment. Fungus beds feed
		// them, and the cave's fixtures (the buried base's plates and buttons)
		// are theirs to discover.
		int nCaveMinded = Math.max(3, sc(3, scale));
		net.hedinger.prototype.entities.Genome[] caveMinded = mindedSpecies(nCaveMinded);
		for (int i = 0; i < nCaveMinded; i++) {
			double[] p = caveSpot(w);
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], CAVE_Z, caveMinded[i]));
		}

		// Founder scavengers: minded, like the cohort above and running the same
		// brains, but eating carrion instead of grass. A third trophic level rather
		// than a third species -- nothing dies for them, they live on what the other
		// two leave behind, and by eating it they are the world's decomposition.
		// Their supply is mortality itself, which is finite and self-consuming, so
		// the cohort is small by nature: a handful is a niche and a crowd is a famine.
		// Seeded as TWO lineages of several individuals each, not one founder per
		// species. Three founders of three species is not a population: with diet a
		// reproductive barrier, a sexual scavenger among them has no compatible
		// partner anywhere in the world and its line ends with it, whatever it eats.
		// Kin it can actually breed with is the difference between a cohort and three
		// animals that happen to share a diet.
		int nScavLines = 2;
		int nScavPerLine = Math.max(3, sc(3, scale));
		net.hedinger.prototype.entities.Genome[] scavengers = mindedSpecies(nScavLines);
		for (int line = 0; line < nScavLines; line++) {
			for (int i = 0; i < nScavPerLine; i++) {
				double[] p = openSpot(w);
				// Siblings, not clones: enough drift for selection to have something to
				// work on, well inside the genome's own similarity threshold.
				net.hedinger.prototype.entities.Genome g =
						net.hedinger.prototype.entities.Genome.child(scavengers[line], 0.03);
				w.spawnEntity(TestNPC.mindedScavenger(p[0], p[1], SURFACE_Z, g));
			}
		}

		// Founder parasites: the fourth trophic level, and the strangest living —
		// they eat the herd without hunting it, a bite at a time from on top of
		// it. Small by nature (a parasite must be smaller than its host to latch,
		// and small is what clings too tight to buck off), ignored by predators,
		// unable to graze: their supply is the standing crop of big warm bodies,
		// which is abundant but fights back one buck at a time. Seeded as two
		// lineages of siblings for the same reason the scavengers are — diet is a
		// mate barrier, and a lone founder of a sexual line dies single.
		int nParaLines = 2;
		int nParaPerLine = Math.max(3, sc(3, scale));
		net.hedinger.prototype.entities.Genome[] parasites = mindedSpecies(nParaLines);
		for (int line = 0; line < nParaLines; line++) {
			for (int i = 0; i < nParaPerLine; i++) {
				double[] p = openSpot(w);
				net.hedinger.prototype.entities.Genome g =
						net.hedinger.prototype.entities.Genome.child(parasites[line], 0.03);
				w.spawnEntity(TestNPC.mindedParasite(p[0], p[1], SURFACE_Z, g));
			}
		}

		// A sprinkle of the inanimate world: food, crates, hazards.
		for (int i = 0; i < sc(10, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.food(p[0], p[1], SURFACE_Z));
		}
		for (int i = 0; i < sc(5, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.crate(p[0], p[1], SURFACE_Z));
		}
		for (int i = 0; i < sc(3, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.hazard(p[0], p[1], SURFACE_Z));
		}

		// The warden, with fixed {min,max} bounds that scale with the map's area.
		// These are guardrails, not the population control: grass, predation and
		// starvation decide the actual headcount, and the ceilings sit well above
		// where those forces settle so the steward rarely has to fire at all.
		//
		// That last sentence is not true of any of them, at any value tried.
		// Sampled every 2000 ticks of the settled world, every cohort sat ON its
		// backstop -- herbivores 449 against a ceiling of 410, predators 12-13
		// against 12, scavengers 63-66 against 60, parasites 31-33 against 30.
		// Raising three of them to 100 moved those three to 108-110 and left the
		// herd exactly where it was. So these numbers are the population control
		// rather than a guardrail around one, and a bigger number is a bigger
		// world rather than a freer one. Worth knowing before the next person
		// reads the paragraph above and believes it. The
		// minded cap in particular is generous — predators hunt minded creatures
		// like any other body their size or smaller, so that cohort is now held in
		// check ecologically rather than by deletion.
		WorldSteward steward = new WorldSteward(w, herb, pred, SURFACE_Z, CAVE_Z,
				// Every herbivore, scripted or minded, under one bound. The ceiling is
				// the sum of the two it replaces (160 plain + 250 minded), measured at
				// the settled world, so the merge changes WHO is counted rather than
				// how many the world carries.
				new int[] { sc(25, scale), sc(410, scale) }, // prey  [floor, ceiling]
				// Predators. Ceiling 12 -> 100. At 12 the cohort sat AT the line,
				// sampled 12 or 13 every time it was looked at, which meant the
				// warden was setting the predator population and grass, prey and
				// starvation were not.
				//
				// It still is. Run sixty thousand ticks and predators climb
				// 11 -> 26 -> 52 and then sit on 109 from tick 20000 on, which is
				// this ceiling's backstop and not an equilibrium. The same is true
				// of the two below. Nothing in this world limits any of these
				// cohorts below a hundred, so raising the number raises the
				// population one for one and the ceiling remains the control. That
				// is a fact about the ecology, and the honest place to record it is
				// beside the constant that is standing in for it.
				new int[] { Math.max(2, sc(3, scale)), sc(100, scale) },
				// Minded ceiling raised 80 -> 250. At 80 the cohort sat AT its cap for
				// long stretches, which meant the warden -- not grass, not predators --
				// was setting the population, and a ceiling that binds is a governor
				// rather than the backstop this is meant to be. 250 is well clear of
				// anything the ecosystem reaches unaided, so what the headcount settles
				// at is now a fact about the world instead of about this constant.
				// The real limit is the deploy's heap and the 10 Hz broadcast, neither
				// of which is measured yet; /api/health now reports tick cost so it can
				// be watched. (Sim CPU is not the binding constraint: the world audit
				// measures thousands of ticks/s against a 33 t/s requirement.)
				Math.max(6, sc(6, scale)), // the minded LINEAGE floor -- not a population bound
				// Scavengers. A floor so the niche is never simply empty, and a
				// ceiling well above it -- the binding control is meant to be the
				// carrion supply, which is finite and self-limiting in a way grass is
				// not: eating a body destroys it, so a scavenger bloom consumes its
				// own larder and starves back without the steward touching it.
				//
				// The floor matches the seeded cohort rather than sitting under it. At
				// three, a cohort held AT the floor was three animals scattered across
				// the map, which with diet as a mate barrier is not a breeding
				// population -- the warden was keeping the niche occupied and extinct
				// at the same time.
				new int[] { Math.max(6, sc(6, scale)), Math.max(100, sc(100, scale)) },
				// Parasites. A floor so the niche survives its own learning curve
				// (a mindless parasite that never latches starves), and a ceiling
				// raised 30 -> 100 for the same reason as the other two.
				//
				// This one gives up something real, and it is worth writing down
				// rather than discovering later. The old number was argued FOR
				// rather than defaulted to: their supply is the standing herd, and
				// a bloom bleeding every big body at once is a plague rather than
				// an ecosystem. At 30 the cohort sat on 31-33 every time it was
				// sampled, so that argument was being enforced by deletion --
				// which is the governor this whole set of bounds is not supposed
				// to be. If a plague is what the herd actually produces, the herd
				// should be what stops it; if nothing stops it, that is a finding
				// about the ecology rather than a reason to hide it behind a cap.
				new int[] { Math.max(6, sc(6, scale)), Math.max(100, sc(100, scale)) });
		w.spawnEntity(steward);

		// The warden's one machine, berthed in the buried base. It takes its
		// orders from the steward and does the killing the steward used to do
		// by deletion -- so a world with no base carved into it (a map too
		// small for either plan) simply has no drone, and the steward's own
		// backstop keeps the ceilings on its own, exactly as before.
		// One drone per pad, all reading the same standing order. They do not
		// divide the cohorts between them and do not need to: the steward keeps
		// the only scoreboard, recounts every tick and drops the order the
		// moment the target is met, so a rank of four converges on it faster
		// and stops together. The drone was always written not to count.
		for (int[] dock : findDocks(w)) {
			w.spawnEntity(new StewardDrone(dock[0] + 0.5, dock[1] + 0.5, CAVE_Z, steward));
		}

		// The building's other machine. It marshals loose crates back onto the
		// stack in the storage wing -- which is where the stack already is, so
		// the drop point locates itself the way the dock does: by looking at
		// what is on the map rather than by threading a coordinate out of
		// whichever base plan ran. A world with nothing stacked in it has
		// nothing to marshal and simply gets no loader.
		w.think(); // admit every spawn: the crates below must be findable

		// Sited after the first tick on purpose. A spawn is pending until the
		// world admits it, so a stack looked for before that tick is a stack of
		// nothing -- which is precisely what happened, and it reads as the
		// loader simply not existing rather than as an ordering bug.
		double[] stack = findStack(w);
		if (stack != null) {
			w.spawnEntity(new FacilityLoader(stack[0], stack[1], CAVE_Z,
					stack[0], stack[1], CAVE_Z));
		}

		w.think(); // and admit the loader
		return w;
	}

	/**
	 * The charge docks the world generator laid into the buried base, as
	 * {@code {col, row}} pairs on the cave level, in reading order. Empty if
	 * this map got no base.
	 *
	 * <p>Found by looking rather than remembered, because the two base plans put
	 * their rank in different places and coordinates threaded back out through
	 * {@code buryInstallation} would be one more thing for the plans to keep in
	 * step with each other. The dock tiles are the record: they are on the map,
	 * and anything that needs to know where the drones live asks the same
	 * question this does. One pass over one level at world creation.
	 *
	 * <p>Returns every pad rather than the first. A world that berthed one drone
	 * on the first pad it found and left three cut into the deck would be a
	 * world where the map says four and the population says one — and the map is
	 * the thing a viewer can see.
	 */
	public static java.util.List<int[]> findDocks(World w) {
		java.util.List<int[]> out = new java.util.ArrayList<int[]>();
		for (int y = 0; y < w.getRows(); y++) {
			for (int x = 0; x < w.getColums(); x++) {
				if (w.getTile(x, y, CAVE_Z).getType() == Tile.TileType.TYPE_DOCK) {
					out.add(new int[] { x, y });
				}
			}
		}
		return out;
	}

	/**
	 * Where the crates are stacked: the mean position of the crates already in
	 * the base, or null if there are none.
	 *
	 * <p>The loader's berth and its drop point are the same place, and that is
	 * the whole design rather than a shortcut. A machine that fetches strays
	 * back to the pile it is standing next to needs no depot, no schedule and
	 * no second coordinate; the pile is the instruction. Averaging rather than
	 * taking the first crate matters only for where it stands, and standing in
	 * the middle of the stack is what makes "bring it back here" legible.
	 */
	static double[] findStack(World w) {
		double sx = 0, sy = 0;
		int n = 0;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.entities.Item it && !it.isRemoved()
					&& it.getKind() == net.hedinger.prototype.entities.Item.Kind.CRATE
					&& it.getZ() == CAVE_Z) {
				sx += it.getX();
				sy += it.getY();
				n++;
			}
		}
		return n == 0 ? null : new double[] { sx / n, sy / n };
	}

	/** Scale a base count by the map's area ratio, never below 1. */
	private static int sc(int base, double scale) {
		return Math.max(1, (int) Math.round(base * scale));
	}
}
