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
		// Low metabolism so grazers bank a strong energy surplus in lush months
		// and breed freely — the herd is food-limited (booms when grass is rich,
		// thins when it is scarce), not pinned to the steward's floor by
		// predation. Seasons then read as real boom/bust.
		return species(markers, sizes, 0.018, 0.03, 0.007);
	}

	/** Predator "species": bigger, faster hunters — reddish barcodes so they read
	 *  as menacing against the green prey. Metabolic; they hunt, breed, starve. */
	private static net.hedinger.prototype.entities.Genome[] predSpecies() {
		double[][] markers = {
				{ 0.90, 0.20, 0.22 }, // red hunter
				{ 0.78, 0.28, 0.48 }, // crimson hunter
		};
		double[] sizes = { 14, 13 };
		// Low RESTING metabolism: a predator idling/prowling burns little, so it can
		// go a long time between kills. The real cost is the sprint surcharge it
		// pays only while pursuing prey (see TestNPC.PRED_SPRINT_COST), so a long
		// fruitless chase still drains it and prey scarcity still thins the hunters.
		return species(markers, sizes, 0.045, 0.055, 0.007);
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
				if (border || elev > 0.74) {
					t = Tile.TileType.TYPE_WALL;
					fert = 0; // rocky rim + highland outcrops (impassable boundaries)
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
				w.setTile(x, y, 0, t);
				w.getTile(x, y, 0).setFertility(fert);
			}
		}

		// ---- level 1: underground caverns carved from solid rock ----
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
				w.setTile(x, y, 1, t);
				w.getTile(x, y, 1).setFertility(0); // no grass grows underground
			}
		}

		// ---- links: two-way ramps and open pits between surface and cave ----
		int[][] ramps = { { 18, 12 }, { 54, 14 }, { 30, 34 }, { 60, 32 } };
		for (int[] r : ramps) {
			carveFloor(w, r[0], r[1], 0);
			carveFloor(w, r[0], r[1], 1);
			w.setTile(r[0], r[1], 0, Tile.TileType.TYPE_RAMPDOWN);
			w.setTile(r[0], r[1], 1, Tile.TileType.TYPE_RAMPUP);
		}
		int[][] holes = { { 24, 20 }, { 44, 26 }, { 38, 9 } };
		for (int[] h : holes) {
			carveFloor(w, h[0], h[1], 1); // a chamber under the pit to fall into
			w.setTile(h[0], h[1], 0, Tile.TileType.TYPE_HOLE);
		}

		// Slow the surface grass's regrowth so heavy grazing leaves lasting bare
		// patches that take ~1-2 min to recover (the big map still sustains the
		// herd, unlike a small room). Non-grass tiles are unaffected.
		for (int x = 0; x < COLS; x++) {
			for (int y = 0; y < ROWS; y++) {
				w.getTile(x, y, 0).setRegrowRate(0.00025);
			}
		}
		return w;
	}

	/** Clears a 3x3 patch of walkable floor around a tile (a ramp/pit landing),
	 *  lush on the surface, bare underground. */
	private static void carveFloor(World w, int cx, int cy, int z) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				int x = cx + dx, y = cy + dy;
				if (x < 1 || y < 1 || x >= w.getColums() - 1 || y >= w.getRows() - 1) {
					continue;
				}
				w.setTile(x, y, z, Tile.TileType.TYPE_FLOOR);
				w.getTile(x, y, z).setFertility(z == 0 ? 0.7 : 0);
			}
		}
	}

	/** A random open (walkable) surface tile, for scattering founders and items
	 *  onto meadow rather than into water or rock. */
	private static double[] openSpot(World w) {
		for (int tries = 0; tries < 60; tries++) {
			double x = 2 + Utils.random() * (w.getColums() - 4);
			double y = 2 + Utils.random() * (w.getRows() - 4);
			if (w.getTile(x, y, 0).isWalkable()) {
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
			w.spawnEntity(TestNPC.breeder(p[0], p[1], 0, prey[i % prey.length])
					.withHerding().withEnergy(3.0).withDeathspan(ECO_DEATHSPAN));
		}
		// Founder predators (few: predation should track the prey, not cap it).
		for (int i = 0; i < 4; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(TestNPC.predator(p[0], p[1], 0, pred[i % pred.length]).withDeathspan(ECO_DEATHSPAN));
		}

		// A sprinkle of the inanimate world: food, crates, hazards.
		for (int i = 0; i < 10; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.food(p[0], p[1], 0));
		}
		for (int i = 0; i < 5; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.crate(p[0], p[1], 0));
		}
		for (int i = 0; i < 3; i++) {
			double[] p = openSpot(w);
			w.spawnEntity(Item.hazard(p[0], p[1], 0));
		}

		// The warden, with seasonal bounds: winter holds a lean {min,max}, summer
		// a lush one, and the steward interpolates between them over the year —
		// so the population visibly booms in summer and thins in winter while
		// never emptying or swarming. Scaled up for the larger map.
		w.spawnEntity(new WorldSteward(w, prey, pred,
				new int[] { 12, 30 }, new int[] { 34, 84 }, // prey: winter, summer
				new int[] { 1, 5 }, new int[] { 3, 14 })); // predators: winter, summer

		w.think(); // admit every spawn: tick 1 is a fully populated world
		return w;
	}
}
