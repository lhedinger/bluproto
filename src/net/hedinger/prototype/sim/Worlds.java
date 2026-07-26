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

	/** A warm palette so the herd reads as distinct individuals, not a swarm. */
	private static final java.awt.Color[] GRAZER_COLORS = {
			new java.awt.Color(0xE8A33D), new java.awt.Color(0xE86A5B), new java.awt.Color(0xD9C35A),
			new java.awt.Color(0xC98BE0), new java.awt.Color(0x6FB6E0), new java.awt.Color(0x8FD07A) };

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

		// A wandering herd: each grazer crops the tile underfoot and roams on
		// when a patch thins, so the herd drifts across the meadows forever.
		for (int i = 0; i < 16; i++) {
			double x = 3 + Utils.random() * (cols - 6);
			double y = 3 + Utils.random() * (rows - 6);
			w.spawnEntity(TestNPC.grazer(x, y, 0).withColor(GRAZER_COLORS[i % GRAZER_COLORS.length]));
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
