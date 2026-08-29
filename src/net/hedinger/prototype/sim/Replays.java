package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.World;

/**
 * Replays a {@link Recording} headlessly to reconstruct the world at any past
 * moment. Because the demo world is fully determined by its seed and the
 * commands are applied at exactly the ticks they originally ran, the
 * reconstructed snapshot at tick N is bit-identical to what the live world
 * showed at tick N — the {@code seed + command log = the run} guarantee the
 * scenario suite pins, turned into a scrub-to-any-tick feature.
 */
public final class Replays {

	/**
	 * Rebuilds the world of {@code rec.seed()} and drives it to {@code
	 * throughTick} (clamped to the recording's extent), applying each logged
	 * command at its tick, and returns the snapshot there.
	 */
	public static WorldSnapshot reconstruct(Recording rec, long throughTick) {
		// A replayed log may carry tune commands, and tunables are JVM-global
		// statics: bracket the whole reconstruction so the recording starts
		// from the defaults its log assumes and the live world's tuning is
		// back untouched afterwards.
		var live = Tuning.snapshot();
		Tuning.restoreDefaults();
		try {
			World w = Worlds.demo(rec.seed()); // fresh, deterministic; already at tick 1
			long target = Math.min(Math.max(throughTick, w.getTick()), rec.throughTick());
			int i = 0;
			var cmds = rec.commands();
			while (w.getTick() < target) {
				// Commands recorded at this tick are applied before the tick advances,
				// exactly as the live runner did.
				while (i < cmds.size() && cmds.get(i).tick() == w.getTick()) {
					SimCommand c = SimCommands.fromDescribe(cmds.get(i).cmd());
					if (c != null) {
						c.apply(w);
					}
					i++;
				}
				w.think();
			}
			return WorldSnapshot.of(w);
		} finally {
			Tuning.restore(live);
		}
	}

	private Replays() {
	}
}
