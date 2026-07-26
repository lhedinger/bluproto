package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.World;

/**
 * The "commands in" half of the observation seam: the only way anything
 * outside the simulation (a web viewer, a replay) may mutate the world. A
 * command is validated up front, queued, and applied by the sim thread at a
 * tick boundary — never mid-tick — and every applied command is recorded in
 * the {@link CommandLog}, so {@code seed + log} reproduces the world exactly.
 *
 * <p>Implementations must be deterministic: same world state + same command
 * arguments must always produce the same effect. In particular they must not
 * draw fresh randomness outside the world's seeded stream.
 */
public interface SimCommand {

	/** Applies this command to the world. Runs on the sim thread only. */
	void apply(World w);

	/** Stable, human-readable form (also the replay-log serialization seed). */
	String describe();
}
