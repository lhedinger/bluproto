package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.World;

/**
 * Sets one tunable constant (see {@link Tuning}) — the only path by which a
 * constant changes on a live world. Riding the command queue buys the two
 * guarantees every world mutation gets: the change lands at a tick boundary,
 * never mid-tick, and it is recorded in the {@link CommandLog}, so a replay
 * of {@code seed + log} re-tunes itself at exactly the tick the live world
 * did and stays bit-identical.
 *
 * <p>Deterministic by construction: the effect depends only on the arguments.
 * The target field is validated at creation, so a bad key fails at the API
 * surface rather than on the sim thread.
 */
public final class TuneCommand implements SimCommand {

	private final String key;
	private final double value;

	public TuneCommand(String key, double value) {
		if (!Tuning.tunable(key)) {
			throw new IllegalArgumentException("not a tunable constant: " + key);
		}
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("not a finite value: " + value);
		}
		this.key = key;
		this.value = value;
	}

	@Override
	public void apply(World w) {
		Tuning.set(key, value);
	}

	@Override
	public String describe() {
		return "tune " + key + " " + value;
	}

	/** Rebuilds from the {@link #describe()} form, for replays. */
	public static TuneCommand parse(String key, double value) {
		return new TuneCommand(key, value);
	}
}
