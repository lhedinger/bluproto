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
 * out. The demo world is the one the web server serves — a walled fertile
 * grassland roamed by a herd of grazers, with scattered items to interact with.
 *
 * <p>Uses {@code TestNPC} fixtures for the population — deliberately: the
 * roadmap retires the legacy bestiary in favour of genome-driven species, and
 * the fixtures are today's cleanest bodies. When real species graduate out of
 * the fixture package, this factory swaps them in.
 */
public final class Worlds {

	private Worlds() {
	}

	/**
	 * Six deterministic "species" for the herd: distinct marker barcodes (which
	 * drive the procedural body — form, legs, pattern, colour) and a couple of
	 * fliers, so the world reads as a small menagerie rather than clones. Built
	 * without RNG (fixed literals), so the demo world stays seed-determined.
	 */
	private static net.hedinger.prototype.entities.Genome[] herdSpecies() {
		double[][] markers = {
				{ 0.85, 0.55, 0.20 }, // warm, few legs
				{ 0.20, 0.70, 0.85 }, // cool, antennae
				{ 0.60, 0.25, 0.55 }, // magenta core
				{ 0.35, 0.85, 0.35 }, // green, leggy
				{ 0.90, 0.80, 0.30 }, // gold, tailed
				{ 0.15, 0.40, 0.75 }, // slate flyer
		};
		double[] sizes = { 7, 9, 6, 8, 11, 6 };
		boolean[] fly = { false, false, false, false, false, true };
		net.hedinger.prototype.entities.Genome[] out =
				new net.hedinger.prototype.entities.Genome[markers.length];
		for (int i = 0; i < markers.length; i++) {
			net.hedinger.prototype.entities.Genome g = new net.hedinger.prototype.entities.Genome();
			g.markers = markers[i];
			g.size = sizes[i];
			g.speed = 0.018 + 0.004 * (i % 3);
			g.turnRate = 5;
			g.flying = fly[i];
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
				w.getTile(x, y, 0).setFertility(0.4 + 0.6 * f);
			}
		}
		return w;
	}

	/**
	 * A self-contained living arena, fully determined by the seed: patchy
	 * fertile grassland, a wandering herd of grazers, and a scatter of items.
	 * The returned world has ticked once, so every spawn is admitted and the
	 * snapshot stream starts populated.
	 *
	 * <p>The herd is deliberately a stable, non-metabolic grazer population
	 * rather than breeding evolvers: a public, always-on world must never be
	 * found empty, and unbounded budders on regrowing grass are a chaotic
	 * boom-or-extinction per seed. A steady herd keeps the world reliably alive
	 * and in motion on every seed; the evolution sandbox stays the scenario
	 * suite's job, and a breeding demo can be a future opt-in.
	 */
	public static World demo(long seed) {
		World w = demoTerrain(seed);
		int cols = w.getColums(), rows = w.getRows();

		// A wandering herd drawn from a handful of species: each grazer renders as
		// its genome's procedural organism (distinct forms, colours, a flyer or
		// two) but keeps the stable graze-and-wander behaviour, so the herd drifts
		// across the meadows forever without breeding or starving.
		net.hedinger.prototype.entities.Genome[] species = herdSpecies();
		for (int i = 0; i < 16; i++) {
			double x = 3 + Utils.random() * (cols - 6);
			double y = 3 + Utils.random() * (rows - 6);
			w.spawnEntity(TestNPC.grazer(x, y, 0, species[i % species.length]));
		}

		// A sprinkle of the inanimate world: food to find, crates to break,
		// hazards to learn to avoid.
		for (int i = 0; i < 6; i++) {
			w.spawnEntity(Item.food(4 + Utils.random() * (cols - 8),
					4 + Utils.random() * (rows - 8), 0));
		}
		for (int i = 0; i < 3; i++) {
			w.spawnEntity(Item.crate(4 + Utils.random() * (cols - 8),
					4 + Utils.random() * (rows - 8), 0));
		}
		for (int i = 0; i < 2; i++) {
			w.spawnEntity(Item.hazard(4 + Utils.random() * (cols - 8),
					4 + Utils.random() * (rows - 8), 0));
		}

		w.think(); // admit every spawn: tick 1 is a fully populated world
		return w;
	}
}
