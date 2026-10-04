package net.hedinger.prototype.engine;

/**
 * The two rates anything in the world looks around at.
 *
 * <p>A scan rate is the simulation's resolution, not a creature's trait, so it
 * is not chosen per scan. There are two, and every scan runs on one of them:
 * {@link #FAST} for what moves or can vanish -- other bodies, a carcass that
 * can be eaten out from under you -- and {@link #SLOW} for what stays put or
 * changes slowly -- a patch of grass that regrows over a minute, a machine's
 * rounds. Which clock a scan runs on says what it is looking at; there used to
 * be eight hand-picked periods (1, 2, 3, 5, 20, 30, 33 and 50 ticks) and none
 * of them said why.
 *
 * <p>A scan that has a reason to run NOW -- the question it answers changed,
 * or the thing it was holding is gone -- still runs at once. Those are events,
 * not rates, and waiting a clock period to notice them would be waiting for
 * nothing.
 */
public final class Scan {
	private Scan() {
	}

	/** Ticks between scans of what moves or can vanish: about 120 ms. */
	@Unit("ticks")
	public static final int FAST = 4;

	/** Ticks between scans of what stays put: about half a second. */
	@Unit("ticks")
	public static final int SLOW = 16;

	/**
	 * Whether a scan on a clock of {@code period} ticks is due at {@code clock},
	 * phased by {@code id} so a cohort that formed together does not all look
	 * on the same tick: exactly one in {@code period} of them scans each tick.
	 */
	public static boolean due(long clock, int id, int period) {
		return Math.floorMod(clock + id, Math.max(1, period)) == 0;
	}
}
