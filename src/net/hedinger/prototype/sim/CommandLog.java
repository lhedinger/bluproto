package net.hedinger.prototype.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered record of every command applied to a world, stamped with the
 * tick it was applied at. Together with the world's seed this is a complete,
 * perfectly faithful recording: replaying the log against a fresh world of the
 * same seed reproduces the run bit-for-bit (pinned by scenario test). Far
 * smaller than any video, and the foundation of the Phase 5 replay feature.
 */
public final class CommandLog {

	/** One applied command: the tick it ran at, and the command itself. */
	public record Entry(long tick, SimCommand command) {
	}

	private final List<Entry> entries = new ArrayList<Entry>();

	void append(long tick, SimCommand command) {
		entries.add(new Entry(tick, command));
	}

	public List<Entry> entries() {
		return Collections.unmodifiableList(entries);
	}

	public int size() {
		return entries.size();
	}
}
