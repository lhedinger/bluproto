package net.hedinger.prototype.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.World;

/**
 * An immutable, read-only capture of the world at one tick — the "snapshots
 * out" half of the observation seam (see MODERNIZATION.md). Everything a
 * viewer renders comes from here; nothing here can reach back into the world.
 *
 * <p>Entities are sorted by id so the same world state always yields the same
 * snapshot byte-for-byte — determinism of the <em>stream</em>, not just the
 * sim, is what the scenario suite pins.
 */
public record WorldSnapshot(long tick, List<EntityState> entities) {

	/** Captures the current world state. Never mutates {@code w}. */
	public static WorldSnapshot of(World w) {
		List<EntityState> out = new ArrayList<EntityState>();
		for (Entity e : w.getEntities()) {
			if (e == null || e.isRemoved()) {
				continue;
			}
			out.add(EntityState.of(e));
		}
		out.sort(Comparator.comparingInt(EntityState::id));
		return new WorldSnapshot(w.getTick(), List.copyOf(out));
	}

	/** Bit-exact fold over the whole snapshot, for determinism tests. */
	public long checksum() {
		long h = tick;
		for (EntityState e : entities) {
			h = h * 1000003L + e.checksum();
		}
		return h;
	}
}
