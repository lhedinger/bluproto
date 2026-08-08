package net.hedinger.prototype.sim;

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

	/** How long an ecosystem corpse lingers before it clears (ticks ≈ 3 s), so a
	 *  predator's kills read as brief bodies rather than a growing grey field. */
	private static final int ECO_DEATHSPAN = 90;

	/** Herbivore "species": small, grazing prey — distinct marker barcodes drive
	 *  distinct procedural bodies/colours; all metabolic breeders (they evolve). */
	private static net.hedinger.prototype.entities.Genome[] preySpecies() {
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
		return species(markers, sizes, 0.045, 0.055, 0.02);
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
	 * It does four things, and nothing more: drive forward, graze continuously
	 * (harmless off grass, so it feeds whenever it crosses a meadow), try to breed
	 * (so a fed lineage grows), and steer — wandering by the clock, but turning away
	 * when a bigger creature is close. Crude on purpose: mutation plus survivor-
	 * seeding are meant to sharpen it (better wandering, real fleeing, hunting),
	 * which is the whole experiment. Runs one instruction per tick, so it is kept
	 * short; the actuator latches persist between writes.
	 */
	static net.hedinger.prototype.entities.Brain starterBrain() {
		final int SET = net.hedinger.prototype.entities.Brain.SET;
		final int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
		final int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
		final int NEG = net.hedinger.prototype.entities.Brain.NEG;
		final int GT = net.hedinger.prototype.entities.Brain.GT;
		final int MOV = net.hedinger.prototype.entities.Brain.MOV;
		final int SKIPZ = net.hedinger.prototype.entities.Brain.SKIPZ;
		int[][] code = {
				{ SET, 1, 9, 0 }, // r1 = 1.0 (const[9])
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_THROTTLE, 1, 0 }, // drive forward
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_EAT, 1, 0 }, // graze whenever on grass
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_MATE, 1, 0 }, // breed when well-fed
				{ SENSE, 2, net.hedinger.prototype.entities.AgentIO.S_CLOCK, 0 }, // r2 = clock (wander)
				{ SENSE, 3, net.hedinger.prototype.entities.AgentIO.S_THREAT_BEARING, 0 }, // r3 = threat bearing
				{ SENSE, 4, net.hedinger.prototype.entities.AgentIO.S_THREAT_PROX, 0 }, // r4 = threat prox
				{ NEG, 5, 3, 0 }, // r5 = -threat bearing (rough flee heading)
				{ SET, 6, 7, 0 }, // r6 = 0.25 (const[7]) threat threshold
				{ GT, 7, 4, 6, }, // r7 = threat is close?
				{ MOV, 8, 2, 0 }, // r8 = wander (default)
				{ SKIPZ, 7, 0, 0 }, // no threat near -> skip the flee override
				{ MOV, 8, 5, 0 }, // r8 = flee turn
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_TURN, 8, 0 }, // steer
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
				{ SET, 1, 9, 0 }, // r1 = 1.0 (const[9])
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_THROTTLE, 1, 0 }, // drive forward
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_EAT, 1, 0 }, // graze, aboard or not
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_MATE, 1, 0 }, // breed when well-fed
				{ SENSE, 2, net.hedinger.prototype.entities.AgentIO.S_CLOCK, 0 }, // r2 = clock (wander)
				{ SENSE, 3, net.hedinger.prototype.entities.AgentIO.S_THREAT_BEARING, 0 }, // r3 = bearing to it
				{ SENSE, 4, net.hedinger.prototype.entities.AgentIO.S_THREAT_PROX, 0 }, // r4 = how close
				{ SET, 6, 7, 0 }, // r6 = 0.25 (const[7]) closeness threshold
				{ GT, 7, 4, 6 }, // r7 = something bigger is close?
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_ATTACH, 7, 0 }, // cling; 0 lets go
				{ MOV, 8, 2, 0 }, // r8 = wander (default)
				{ SKIPZ, 7, 0, 0 }, // nothing bigger near -> keep wandering
				{ MOV, 8, 3, 0 }, // else steer TOWARD it, to get within boarding reach
				{ WRITE, net.hedinger.prototype.entities.AgentIO.A_TURN, 8, 0 }, // steer
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
	static final int CAVE_Z = 0, SURFACE_Z = 1;

	/**
	 * The demo world's terrain — same seed, same tiles, same fertility, zero
	 * entities: an exact twin of {@link #demo}'s ground, from which the server
	 * bakes the static layer images (one per level). Two levels:
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

		World w = new World(cols, rows, 2);

		// ---- level 0: surface biomes inside a rocky boundary ----
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
					t = Tile.TileType.TYPE_FLOOR;
					fert = 0.55 + 0.45 * moist; // meadow, lush where moist
				}
				w.setTile(x, y, SURFACE_Z, t);
				w.getTile(x, y, SURFACE_Z).setFertility(fert);
			}
		}

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
						t = Tile.TileType.TYPE_CRYSTAL;
					} else if (deep > 0.85) {
						t = Tile.TileType.TYPE_VENT;
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

		// ---- wire the levels together so every area is reachable ----
		connectLevels(w, cols, rows);

		// ---- shallows: every shore-touching water tile becomes a walkable,
		// wading fringe, so lakes have fords instead of hard edges ----
		for (int z = 0; z < 2; z++) {
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
					Math.min(rows - 3, Math.max(2, rows / 2)) });
		}

		// 4. Carve a station at each site, then link the cave landings.
		for (int[] s : sites) {
			linkStation(w, s[0], s[1]);
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
			// linkStation touches sx-2..sx+3 (apron + up-ramp wall) and sy-1..sy+1.
			if (sx < 3 || sx > cols - 5 || sy < 2 || sy > rows - 3) {
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
			if (sx < 3 || sx > cols - 5 || sy < 2 || sy > rows - 3) {
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

	/** Carve a 2-wide L-shaped cave corridor between two station landings, so the
	 *  underground forms one connected backbone. */
	private static void carveCaveCorridor(World w, int[] a, int[] b, int cols, int rows) {
		int x = a[0], y = a[1];
		while (x != b[0]) {
			carveCaveTile(w, x, y, cols, rows);
			x += b[0] > x ? 1 : -1;
		}
		while (y != b[1]) {
			carveCaveTile(w, x, y, cols, rows);
			y += b[1] > y ? 1 : -1;
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
				w.setTile(cx, cy, CAVE_Z, Tile.TileType.TYPE_STONE);
				w.getTile(cx, cy, CAVE_Z).setFertility(0);
			}
		}
	}

	/** Flood the whole walkable space from a surface seed, crossing levels the way
	 *  a land body does (walk onto a HOLE to drop; step a RAMPUP east into a WALL
	 *  to climb), then seal every walkable tile the flood never reaches — on
	 *  either level. What remains is a single connected region. Mirrors
	 *  {@link WorldAudit#connectivity}'s traversal, so the audit agrees by
	 *  construction. */
	private static void sealUnreachable(World w, int cols, int rows, int[] surfaceSeed) {
		boolean[][][] seen = new boolean[2][cols][rows];
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
						&& w.getTile(n[0], n[1], z).getType() == Tile.TileType.TYPE_HOLE) {
					floodVisit(w, seen, q, n[0], n[1], z - 1, cols, rows);
				}
			}
			// Climb: a RAMPUP with a WALL to its east lifts onto the tile above.
			if (z + 1 < 2 && x + 1 < cols
					&& w.getTile(x, y, z).getType() == Tile.TileType.TYPE_RAMPUP
					&& w.getTile(x + 1, y, z).getType() == Tile.TileType.TYPE_WALL) {
				floodVisit(w, seen, q, x + 1, y, z + 1, cols, rows);
			}
		}
		for (int z = 0; z < 2; z++) {
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
	 * A working two-way link between the surface and the cave, built to match the
	 * engine's movement rules (verified: a HOLE drops a walker one level; a RAMPUP
	 * lifts a walker one level only when it steps EAST into an adjacent WALL).
	 *
	 * <p>Laid out along one row at {@code (sx,sy)}:
	 * <ul>
	 *   <li><b>Down</b> — on the surface, a HOLE with a RAMPDOWN just east of it
	 *       (the ramp faces west, into the hole). A creature that walks onto the
	 *       hole falls to the cave floor carved directly below.</li>
	 *   <li><b>Up</b> — in the cave, a RAMPUP with a WALL just east of it (the ramp
	 *       faces east, into the wall). A creature on the ramp that steps east into
	 *       the wall pops up onto the surface floor carved directly above.</li>
	 * </ul>
	 */
	private static void linkStation(World w, int sx, int sy) {
		// A small open patch on both levels so creatures can reach the link.
		carveFloor(w, sx, sy, SURFACE_Z);
		carveFloor(w, sx, sy, CAVE_Z);

		// Down: hole on the surface, ramp beside it (east), cave floor to land on.
		w.setTile(sx, sy, SURFACE_Z, Tile.TileType.TYPE_HOLE);
		w.setTile(sx + 1, sy, SURFACE_Z, Tile.TileType.TYPE_RAMPDOWN);
		w.setTile(sx, sy, CAVE_Z, Tile.TileType.TYPE_STONE); // landing
		w.getTile(sx, sy, CAVE_Z).setFertility(0);

		// Up: ramp in the cave with a wall to its east; surface floor above the
		// wall is where the climber lands.
		int rx = sx + 2;
		w.setTile(rx, sy, CAVE_Z, Tile.TileType.TYPE_RAMPUP);
		w.setTile(rx + 1, sy, CAVE_Z, Tile.TileType.TYPE_WALL); // climb east into this
		w.setTile(rx + 1, sy, SURFACE_Z, Tile.TileType.TYPE_FLOOR); // landing above
		w.getTile(rx + 1, sy, SURFACE_Z).setFertility(0.7);
	}

	/** Clears a 5x3 patch of walkable floor around a tile (a link landing/apron),
	 *  lush on the surface, bare underground. */
	private static void carveFloor(World w, int cx, int cy, int z) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				int x = cx + dx, y = cy + dy;
				if (x < 1 || y < 1 || x >= w.getColums() - 1 || y >= w.getRows() - 1) {
					continue;
				}
				w.setTile(x, y, z, z == SURFACE_Z
						? Tile.TileType.TYPE_FLOOR : Tile.TileType.TYPE_STONE);
				w.getTile(x, y, z).setFertility(z == SURFACE_Z ? 0.7 : 0);
			}
		}
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
		net.hedinger.prototype.entities.Genome[] prey = preySpecies();
		net.hedinger.prototype.entities.Genome[] pred = predSpecies();

		// Founder herbivores: metabolic grazers that breed and evolve, scattered
		// onto open meadow (never into water or rock).
		for (int i = 0; i < sc(26, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.breeder(p[0], p[1], SURFACE_Z, prey[i % prey.length])
					.withHerding().withDeathspan(ECO_DEATHSPAN)); // born at its size-scaled reserve
		}
		// Founder predators (few: predation should track the prey, not cap it).
		for (int i = 0; i < sc(4, scale); i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.predator(p[0], p[1], SURFACE_Z, pred[i % pred.length]).withDeathspan(ECO_DEATHSPAN));
		}
		// A small parallel cohort of minded creatures (fully-random brains) that
		// competes inside the same world as the scripted species — the A/B seam
		// where evolvable behaviour proves itself (or doesn't) against the hardcoded
		// baseline. The steward keeps this cohort topped up as it dies off.
		int nMinded = Math.max(5, sc(5, scale));
		net.hedinger.prototype.entities.Genome[] minded = mindedSpecies(nMinded);
		for (int i = 0; i < nMinded; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], SURFACE_Z, minded[i]).withDeathspan(ECO_DEATHSPAN));
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
		// where those forces settle so the steward rarely has to fire at all. The
		// minded cap in particular is generous — predators hunt minded creatures
		// like any other body their size or smaller, so that cohort is now held in
		// check ecologically rather than by deletion.
		w.spawnEntity(new WorldSteward(w, prey, pred, SURFACE_Z,
				new int[] { sc(25, scale), sc(160, scale) }, // prey  [floor, ceiling]
				new int[] { Math.max(2, sc(3, scale)), sc(12, scale) }, // predators
				Math.max(6, sc(6, scale)), Math.max(80, sc(80, scale)))); // minded

		w.think(); // admit every spawn: tick 1 is a fully populated world
		return w;
	}

	/** Scale a base count by the map's area ratio, never below 1. */
	private static int sc(int base, double scale) {
		return Math.max(1, (int) Math.round(base * scale));
	}
}
