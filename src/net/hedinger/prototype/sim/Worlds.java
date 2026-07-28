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
		return species(markers, sizes, 0.018, 0.03, 0.012);
	}

	/** Predator "species": bigger, faster hunters — reddish barcodes so they read
	 *  as menacing against the green prey. Metabolic; they hunt, breed, starve. */
	private static net.hedinger.prototype.entities.Genome[] predSpecies() {
		double[][] markers = {
				{ 0.90, 0.20, 0.22 }, // red hunter
				{ 0.78, 0.28, 0.48 }, // crimson hunter
		};
		double[] sizes = { 14, 13 };
		// Predators must keep hunting to live (moderate metabolism), so prey
		// scarcity thins them and the two populations track each other.
		return species(markers, sizes, 0.045, 0.055, 0.02);
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
	 * Only the demo world's terrain — same seed, same tiles, same fertility,
	 * zero entities. Terrain construction consumes no RNG (single level, all
	 * tiles set explicitly, fertility sampled from coordinate noise), so this
	 * is an exact twin of {@link #demo}'s ground: the server bakes its static
	 * layer images from it without an entity in sight.
	 */
	public static World demoTerrain(long seed) {
		Utils.seed(seed);
		Perf.stopwatch = new StopWatch();

		int cols = 48, rows = 28;
		World w = new World(cols, rows, 1);
		for (int x = 1; x < cols - 1; x++) {
			for (int y = 1; y < rows - 1; y++) {
				w.setTile(x, y, 0, Tile.TileType.TYPE_FLOOR);
			}
		}
		w.generateFertility(0.22); // patchy habitats: rich meadows, poor scrub
		// Lift the fertility floor so the ground reads lush (raw noise leaves
		// near-barren badlands); purely cosmetic for the grazer demo, which does
		// not depend on grass to survive.
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				double f = w.getTile(x, y, 0).getFertility();
				w.getTile(x, y, 0).setFertility(0.6 + 0.4 * f);
			}
		}
		return w;
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
		int cols = w.getColums(), rows = w.getRows();
		net.hedinger.prototype.entities.Genome[] prey = preySpecies();
		net.hedinger.prototype.entities.Genome[] pred = predSpecies();

		// Founder herbivores: metabolic grazers that breed and evolve.
		for (int i = 0; i < 16; i++) {
			double x = 3 + Utils.random() * (cols - 6);
			double y = 3 + Utils.random() * (rows - 6);
			w.spawnEntity(TestNPC.breeder(x, y, 0, prey[i % prey.length])
					.withHerding().withEnergy(2.0).withDeathspan(ECO_DEATHSPAN));
		}
		// Founder predators (few: predation should track the prey, not cap it).
		for (int i = 0; i < 2; i++) {
			double x = 3 + Utils.random() * (cols - 6);
			double y = 3 + Utils.random() * (rows - 6);
			w.spawnEntity(TestNPC.predator(x, y, 0, pred[i % pred.length]).withDeathspan(ECO_DEATHSPAN));
		}

		// A sprinkle of the inanimate world: food, crates, hazards.
		for (int i = 0; i < 6; i++) {
			w.spawnEntity(Item.food(4 + Utils.random() * (cols - 8), 4 + Utils.random() * (rows - 8), 0));
		}
		for (int i = 0; i < 3; i++) {
			w.spawnEntity(Item.crate(4 + Utils.random() * (cols - 8), 4 + Utils.random() * (rows - 8), 0));
		}
		for (int i = 0; i < 2; i++) {
			w.spawnEntity(Item.hazard(4 + Utils.random() * (cols - 8), 4 + Utils.random() * (rows - 8), 0));
		}

		// The warden, with seasonal bounds: winter holds a lean {min,max}, summer
		// a lush one, and the steward interpolates between them over the year —
		// so the population visibly booms in summer and thins in winter while
		// never emptying or swarming.
		w.spawnEntity(new WorldSteward(w, prey, pred,
				new int[] { 8, 18 }, new int[] { 22, 58 }, // prey: winter, summer
				new int[] { 1, 4 }, new int[] { 2, 10 })); // predators: winter, summer

		w.think(); // admit every spawn: tick 1 is a fully populated world
		return w;
	}
}
