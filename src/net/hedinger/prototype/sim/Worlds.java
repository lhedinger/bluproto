package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Perf;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * Deterministic world factories for headless hosting: seed in, living world
 * out. The demo world is the one the web server serves — a walled fertile
 * grassland with a breeding herbivore population (so there is visible life,
 * grazing pressure and population dynamics to watch) plus scattered items to
 * interact with.
 *
 * <p>Uses {@code TestNPC} genome fixtures for the population — deliberately:
 * the roadmap retires the legacy bestiary in favour of genome-driven species,
 * and the fixtures are today's only genome-complete bodies. When real species
 * graduate out of the fixture package, this factory swaps them in.
 */
public final class Worlds {

	private Worlds() {
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
		return w;
	}

	/**
	 * A self-contained living arena, fully determined by the seed: patchy
	 * fertile grassland, a founder population of grazing breeders, and a
	 * scatter of items. The returned world has ticked once, so every spawn is
	 * admitted and the snapshot stream starts populated.
	 */
	public static World demo(long seed) {
		World w = demoTerrain(seed);
		int cols = w.getColums(), rows = w.getRows();

		// Founder herbivores: metabolic breeders that graze, starve, and bud —
		// the population finds its own level against the grass.
		for (int i = 0; i < 14; i++) {
			Genome g = new Genome();
			g.markers = new double[] { 0.2, 0.6, 0.9 };
			double x = 3 + Utils.random() * (cols - 6);
			double y = 3 + Utils.random() * (rows - 6);
			w.spawnEntity(TestNPC.breeder(x, y, 0, g));
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
