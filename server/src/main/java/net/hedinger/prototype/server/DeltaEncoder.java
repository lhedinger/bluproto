package net.hedinger.prototype.server;

import java.util.ArrayList;
import java.util.List;

import net.hedinger.prototype.sim.EntityState;
import net.hedinger.prototype.sim.WorldSnapshot;

/**
 * Computes the change between two snapshots: which entities to upsert and
 * which ids are gone. Deltas are <em>cumulative-safe</em> — upsert overwrites
 * and removing an unknown id is a no-op — so a client that joined mid-stream
 * (its full may be newer than the delta base) still converges. Both inputs are
 * id-sorted (a {@link WorldSnapshot} invariant), so this is a single merge
 * pass. Pure and stateless: the unit-testable core of the broadcaster.
 */
final class DeltaEncoder {

	record Delta(List<EntityState> upsert, List<Integer> gone) {
	}

	static Delta diff(WorldSnapshot prev, WorldSnapshot next) {
		List<EntityState> upsert = new ArrayList<EntityState>();
		List<Integer> gone = new ArrayList<Integer>();
		List<EntityState> a = prev.entities(), b = next.entities();
		int i = 0, j = 0;
		while (i < a.size() || j < b.size()) {
			if (i >= a.size()) {
				upsert.add(b.get(j++)); // new entity
			} else if (j >= b.size()) {
				gone.add(a.get(i++).id()); // vanished
			} else {
				EntityState pa = a.get(i), pb = b.get(j);
				if (pa.id() == pb.id()) {
					if (!pa.equals(pb)) {
						upsert.add(pb); // changed
					}
					i++;
					j++;
				} else if (pa.id() < pb.id()) {
					gone.add(pa.id());
					i++;
				} else {
					upsert.add(pb);
					j++;
				}
			}
		}
		return new Delta(upsert, gone);
	}

	private DeltaEncoder() {
	}
}
