package net.hedinger.prototype.simtest;

import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;

/**
 * Base class for simulation scenario tests.
 *
 * <p>A scenario is a deterministic, self-contained simulation experiment: it
 * seeds the RNG, builds a small hardcoded world, places entities at exact
 * positions, advances the simulation a fixed number of ticks, and asserts on
 * observable outcomes (positions, alive/removed state, counts). Because the
 * simulation is fully reproducible from its seed, scenarios are exact: a
 * failure means behaviour actually changed.
 *
 * <p>Scenarios are discovered and run by {@link SimTests}. They use no test
 * framework so they compile and run with the plain JDK, same as the app.
 */
public abstract class Scenario {

	private final java.util.List<String> shotLabels = new java.util.ArrayList<String>();
	private final java.util.List<java.awt.image.BufferedImage> shots = new java.util.ArrayList<java.awt.image.BufferedImage>();

	/** Short name shown in the runner output. */
	public String name() {
		return getClass().getSimpleName();
	}

	/** Runs the scenario; throws AssertionError on failure. */
	public abstract void run();

	// ---- snapshots ---------------------------------------------------------

	/** True when the runner asked for snapshots (-Dsimtest.shots=&lt;dir&gt;). */
	static boolean captureEnabled() {
		return System.getProperty("simtest.shots") != null;
	}

	/**
	 * Captures a labelled screenshot of the world's ground level. A no-op
	 * (zero cost) unless capture is enabled, so scenarios can call
	 * {@code snapshot(w, "before")} / {@code snapshot(w, "after")} freely and
	 * normal test runs stay fast. The runner composes the captures for a
	 * scenario into one before/after strip.
	 */
	protected void snapshot(World w, String label) {
		if (!captureEnabled()) {
			return;
		}
		shots.add(SnapshotRenderer.render(w, 0));
		shotLabels.add(label);
	}

	java.util.List<String> shotLabels() {
		return shotLabels;
	}

	java.util.List<java.awt.image.BufferedImage> shots() {
		return shots;
	}

	// ---- world building ---------------------------------------------------

	/**
	 * Seeds the RNG and prepares globals every scenario needs. Call first.
	 */
	protected void seed(long seed) {
		Utils.seed(seed);
		net.hedinger.prototype.main.PrototypeWorld.stopwatch = new StopWatch();
	}

	/**
	 * Builds a single-level world of the given size whose entire interior is
	 * open floor (the engine forces the 1-tile border to walls). No RNG is
	 * consumed: with one level, world construction is fully deterministic.
	 */
	protected World room(int cols, int rows) {
		World w = new World(cols, rows, 1);
		for (int x = 1; x < cols - 1; x++) {
			for (int y = 1; y < rows - 1; y++) {
				w.setTile(x, y, 0, Tile.TileType.TYPE_FLOOR);
			}
		}
		return w;
	}

	/** Advances the simulation n ticks. */
	protected void tick(World w, int n) {
		for (int i = 0; i < n; i++) {
			w.think();
		}
	}

	// ---- assertions --------------------------------------------------------

	protected void assertTrue(String what, boolean cond) {
		if (!cond) {
			throw new AssertionError(what);
		}
	}

	protected void assertEquals(String what, long expected, long actual) {
		if (expected != actual) {
			throw new AssertionError(what + ": expected " + expected + " but was " + actual);
		}
	}

	protected void assertNear(String what, double expected, double actual, double tolerance) {
		if (Math.abs(expected - actual) > tolerance) {
			throw new AssertionError(what + ": expected " + expected + " +/- " + tolerance + " but was " + actual);
		}
	}

	protected void assertLess(String what, double actual, double lessThan) {
		if (!(actual < lessThan)) {
			throw new AssertionError(what + ": expected < " + lessThan + " but was " + actual);
		}
	}

	protected void assertGreater(String what, double actual, double greaterThan) {
		if (!(actual > greaterThan)) {
			throw new AssertionError(what + ": expected > " + greaterThan + " but was " + actual);
		}
	}
}
