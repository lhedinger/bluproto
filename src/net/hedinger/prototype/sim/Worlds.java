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
		// bigger ones a little more. The herd stays food-limited (booms when grass
		// is rich, thins when scarce), so seasons read as real boom/bust.
		return species(markers, sizes, 0.018, 0.03, 0.02);
	}

	/** Predator "species": bigger, faster hunters — reddish barcodes so they read
	 *  as menacing against the green prey. Metabolic; they hunt, breed, starve. */
	private static net.hedinger.prototype.entities.Genome[] predSpecies() {
		double[][] markers = {
				{ 0.90, 0.20, 0.22 }, // red hunter
				{ 0.78, 0.28, 0.48 }, // crimson hunter
		};
		double[] sizes = { 14, 13 };
		// Neutral metabolism efficiency (META_REF): the size-scaled model gives
		// these big hunters a large reserve and a long fasting endurance (bigger
		// body, bigger tank), so a predator drains gently between kills. Its only
		// heavy burn is the sprint surcharge paid while pursuing prey (see
		// TestNPC.PRED_SPRINT_FACTOR), so a long fruitless chase still thins it.
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
			out[i] = mindedGenome();
		}
		return out;
	}

	/** One founder/reseed minded genome: random dispositions and markers, a random
	 *  body inside the sane size band, and a fresh random brain. */
	static net.hedinger.prototype.entities.Genome mindedGenome() {
		net.hedinger.prototype.entities.Genome g = net.hedinger.prototype.entities.Genome.random();
		g.size = 5 + Utils.random() * 12; // 5..17: room for both grazer and hunter builds
		g.speed = 0.04 + Utils.random() * 0.03;
		g.metabolism = 0.02;
		g.brain = net.hedinger.prototype.entities.Brain.random(16); // a fully random mind
		return g;
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

	/** World size (tiles). Bigger than the old 48x28 box so biomes have room and
	 *  predator/prey aren't always on top of each other. */
	static final int COLS = 72, ROWS = 44;

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
	 *       rim — meadow, marsh and open water, dry badlands, sight-blocking
	 *       thickets, and rocky highlands — laid out from coordinate noise.</li>
	 *   <li><b>Level 1 — underground:</b> solid rock with carved caverns and the
	 *       odd subterranean pool.</li>
	 * </ul>
	 *
	 * The levels are linked by a few two-way ramps and a few open pits (holes).
	 * Layout is sampled from {@link Utils#noise2} (deterministic, draws no RNG),
	 * so the terrain is fully reproducible and does not perturb the entity RNG.
	 */
	public static World demoTerrain(long seed) {
		Utils.seed(seed);
		Perf.stopwatch = new StopWatch();

		World w = new World(COLS, ROWS, 2);

		// ---- level 0: surface biomes inside a rocky boundary ----
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				boolean border = x < 2 || y < 2 || x >= COLS - 2 || y >= ROWS - 2;
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
				} else if (moist > 0.70 && elev < 0.45) {
					t = Tile.TileType.TYPE_WATER;
					fert = 0; // lakes in the low, wet ground
				} else if (moist > 0.60 && elev < 0.52) {
					t = Tile.TileType.TYPE_MUD;
					fert = 0.30; // marshy shore, slows movement
				} else if (moist > 0.55 && detail > 0.62) {
					t = Tile.TileType.TYPE_COVER;
					fert = 0.90; // thickets: lush, and they block line of sight
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
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				double cave = Utils.noise2(x + 210, y + 770, 0.11);
				double pool = Utils.noise2(x + 1300, y + 90, 0.13);
				Tile.TileType t;
				if (x < 1 || y < 1 || x >= COLS - 1 || y >= ROWS - 1) {
					t = Tile.TileType.TYPE_WALL; // sealed edge
				} else if (cave > 0.38 && cave < 0.66) {
					t = pool > 0.72 ? Tile.TileType.TYPE_WATER : Tile.TileType.TYPE_FLOOR;
				} else {
					t = Tile.TileType.TYPE_WALL; // solid rock
				}
				w.setTile(x, y, CAVE_Z, t);
				w.getTile(x, y, CAVE_Z).setFertility(0); // no grass grows underground
			}
		}

		// ---- links between surface and cave (see linkStation for the mechanic) ----
		int[][] stations = { { 16, 12 }, { 52, 14 }, { 30, 34 }, { 58, 30 } };
		for (int[] s : stations) {
			linkStation(w, s[0], s[1]);
		}

		// Tune the surface grass's logistic recovery so a grazed-bare patch rests
		// (Tile.REGROW_DELAY, ~1 min) and then climbs back slowly over another
		// ~1.5 min, while a lightly-cropped patch springs back fast. Heavy grazing
		// thus leaves lasting bare patches, but the big map still sustains the herd
		// (unlike a small room). Non-grass tiles are unaffected.
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				w.getTile(x, y, SURFACE_Z).setRegrowRate(0.0025);
			}
		}
		return w;
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
		w.setTile(sx, sy, CAVE_Z, Tile.TileType.TYPE_FLOOR); // landing
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
				w.setTile(x, y, z, Tile.TileType.TYPE_FLOOR);
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
		World w = demoTerrain(seed);
		net.hedinger.prototype.entities.Genome[] prey = preySpecies();
		net.hedinger.prototype.entities.Genome[] pred = predSpecies();

		// Founder herbivores: metabolic grazers that breed and evolve, scattered
		// onto open meadow (never into water or rock).
		for (int i = 0; i < 26; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.breeder(p[0], p[1], SURFACE_Z, prey[i % prey.length])
					.withHerding().withDeathspan(ECO_DEATHSPAN)); // born at its size-scaled reserve
		}
		// Founder predators (few: predation should track the prey, not cap it).
		for (int i = 0; i < 4; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.predator(p[0], p[1], SURFACE_Z, pred[i % pred.length]).withDeathspan(ECO_DEATHSPAN));
		}
		// A small parallel cohort of minded creatures (fully-random brains) that
		// competes inside the same world as the scripted species — the A/B seam
		// where evolvable behaviour proves itself (or doesn't) against the hardcoded
		// baseline. The steward keeps this cohort topped up as it dies off.
		net.hedinger.prototype.entities.Genome[] minded = mindedSpecies(5);
		for (int i = 0; i < 5; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.mindedForager(p[0], p[1], SURFACE_Z, minded[i]).withDeathspan(ECO_DEATHSPAN));
		}

		// A sprinkle of the inanimate world: food, crates, hazards.
		for (int i = 0; i < 10; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.food(p[0], p[1], SURFACE_Z));
		}
		for (int i = 0; i < 5; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.crate(p[0], p[1], SURFACE_Z));
		}
		for (int i = 0; i < 3; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.hazard(p[0], p[1], SURFACE_Z));
		}

		// The warden, with seasonal bounds: winter holds a lean {min,max}, summer
		// a lush one, and the steward interpolates between them over the year —
		// so the population visibly booms in summer and thins in winter while
		// never emptying or swarming. Scaled up for the larger map.
		w.spawnEntity(new WorldSteward(w, prey, pred, SURFACE_Z,
				new int[] { 12, 30 }, new int[] { 34, 84 }, // prey: winter, summer
				new int[] { 1, 5 }, new int[] { 3, 14 }, // predators: winter, summer
				4)); // keep at least this many minded creatures alive (small cohort)

		w.think(); // admit every spawn: tick 1 is a fully populated world
		return w;
	}
}
