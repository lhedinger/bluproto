package net.hedinger.prototype.sim;

import java.util.ArrayList;
import java.util.List;

/**
 * A complete, faithful capture of a session: the world's {@code seed} and the
 * ordered log of commands applied to it, each stamped with the tick it ran at.
 * Because the world is deterministic, {@code seed + commands} reproduces the run
 * exactly — so a Recording is a perfect, tiny "replay file" (far smaller than a
 * video, and re-playable to any moment). See {@link Replays}.
 */
public record Recording(long seed, long throughTick, List<Entry> commands) {

	/** One applied command: the tick it ran at and its stable text form. */
	public record Entry(long tick, String cmd) {
	}

	/** Captures the current state of a runner's command log. */
	public static Recording of(long seed, SimulationRunner runner) {
		List<Entry> out = new ArrayList<Entry>();
		for (CommandLog.Entry e : runner.commandLog().entries()) {
			out.add(new Entry(e.tick(), e.command().describe()));
		}
		return new Recording(seed, runner.snapshot().tick(), out);
	}
}
