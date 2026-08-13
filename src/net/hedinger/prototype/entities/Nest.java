package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.World;

/**
 * A nest: the physical fixture at a brood site (WORLDGEN-RESEARCH.md's "nests
 * as fixtures"). Nesters already home on their pheromone peak to breed; the
 * nest is that site made solid — a woven ring on the ground that persists,
 * counts the broods raised in it, and gives the viewer (and the inspector)
 * something to see where the scent map says "home".
 *
 * <p>Like doors and switches it is a non-living entity: it never thinks, has
 * no body size, is invisible to perception scans (which filter for creatures),
 * and draws no RNG. One nest serves a whole site: births within
 * {@link #CLAIM_RADIUS} of an existing nest reinforce it rather than founding
 * a new one, mirroring how pheromone deposits merge into one cloud.
 */
public class Nest extends Entity {

	/** Births within this many tiles of an existing nest reuse it — matches
	 *  the pheromone cloud's merge radius, so scent peak and fixture agree. */
	public static final double CLAIM_RADIUS = 2.5;

	private int broods = 0;

	private Nest(double x, double y, double z) {
		super(x, y, z, 0.0); // direction-taking ctor: no RNG draw
	}

	/**
	 * The nest for a birth at {@code (x, y, z)}: the nearest existing nest
	 * within {@link #CLAIM_RADIUS} on that level (its brood count grows), or a
	 * fresh one founded on the spot. This is the only way nests enter the
	 * world, so every nest marks real births.
	 */
	public static Nest claimAt(World w, double x, double y, double z) {
		Nest best = null;
		double bestD = CLAIM_RADIUS;
		for (Entity e : w.getEntities()) {
			if (e instanceof Nest n && !n.isRemoved() && (int) n.getZ() == (int) z) {
				double d = Math.hypot(n.getX() - x, n.getY() - y);
				if (d <= bestD) {
					bestD = d;
					best = n;
				}
			}
		}
		if (best == null) {
			best = new Nest(x, y, z);
			w.spawnEntity(best);
		}
		best.broods++;
		return best;
	}

	/** How many births this site has hosted. */
	public int getBroods() {
		return broods;
	}

	@Override
	protected void think() {
		// A nest does nothing. It is a place.
	}

	@Override
	public String getEntityTypeName() {
		return "Nest";
	}
}
