package net.hedinger.prototype.server;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import net.hedinger.prototype.sim.EntityState;
import net.hedinger.prototype.sim.SimulationRunner;
import net.hedinger.prototype.sim.Worlds;
import net.hedinger.prototype.sim.WorldSnapshot;

/**
 * Server-layer tests, same zero-framework convention as the engine's SimTests:
 * a plain main() that exits non-zero on failure, wired into `gradle check`.
 * Covers the pure, protocol-shaped pieces — delta encoding correctness and
 * JSON round-trips — while the sim-side invariants stay pinned in SimTests.
 */
public final class ServerTests {

	private static int failed = 0;

	public static void main(String[] args) throws Exception {
		deltaDiffFindsChangesAdditionsRemovals();
		deltaOfIdenticalSnapshotsIsEmpty();
		fullMessageJsonRoundTrips();
		deltaAppliedToFullMatchesNextSnapshot();
		System.out.println(failed == 0 ? "server tests: all passed" : "server tests: " + failed + " FAILED");
		if (failed > 0) {
			System.exit(1);
		}
	}

	/** diff() classifies every case: unchanged, moved, born, gone. */
	static void deltaDiffFindsChangesAdditionsRemovals() {
		EntityState a1 = probe(1, 5.0), a2 = probe(2, 6.0), a3 = probe(3, 7.0);
		WorldSnapshot prev = new WorldSnapshot(10, List.of(a1, a2, a3));
		EntityState b2 = probe(2, 6.5); // moved
		EntityState b4 = probe(4, 9.0); // born
		WorldSnapshot next = new WorldSnapshot(11, List.of(a1, b2, b4)); // 3 gone
		DeltaEncoder.Delta d = DeltaEncoder.diff(prev, next);
		check("upsert holds exactly the moved and the born", d.upsert().equals(List.of(b2, b4)));
		check("gone holds exactly the vanished id", d.gone().equals(List.of(3)));
	}

	static void deltaOfIdenticalSnapshotsIsEmpty() {
		WorldSnapshot s = new WorldSnapshot(5, List.of(probe(1, 1.0), probe(2, 2.0)));
		DeltaEncoder.Delta d = DeltaEncoder.diff(s, s);
		check("identical snapshots produce an empty delta", d.upsert().isEmpty() && d.gone().isEmpty());
	}

	/** The wire form carries every field a client draws from. */
	static void fullMessageJsonRoundTrips() throws Exception {
		WorldSnapshot s = WorldSnapshot.of(Worlds.demo(42));
		String json = Protocol.write(Protocol.Full.of(s.tick(), s.entities()));
		JsonNode n = Protocol.read(json);
		check("type tag", n.path("type").asText().equals("full"));
		check("tick carried", n.path("tick").asLong() == s.tick());
		check("all entities carried", n.path("entities").size() == s.entities().size());
		JsonNode e0 = n.path("entities").get(0);
		check("entity fields present", e0.has("id") && e0.has("kind") && e0.has("x")
				&& e0.has("y") && e0.has("dir") && e0.has("size") && e0.has("rgb")
				&& e0.has("flags") && e0.has("attachedTo") && e0.has("aux"));
	}

	/** A client that applies deltas to a full converges on the live state. */
	static void deltaAppliedToFullMatchesNextSnapshot() {
		SimulationRunner r = new SimulationRunner(Worlds.demo(7));
		r.advance(20);
		WorldSnapshot base = r.snapshot();
		r.advance(30);
		WorldSnapshot next = r.snapshot();
		DeltaEncoder.Delta d = DeltaEncoder.diff(base, next);

		java.util.TreeMap<Integer, EntityState> client = new java.util.TreeMap<Integer, EntityState>();
		for (EntityState e : base.entities()) {
			client.put(e.id(), e);
		}
		for (EntityState e : d.upsert()) {
			client.put(e.id(), e);
		}
		for (Integer id : d.gone()) {
			client.remove(id);
		}
		check("full + delta reproduces the next snapshot exactly",
				List.copyOf(client.values()).equals(next.entities()));
	}

	private static EntityState probe(int id, double x) {
		return new EntityState(id, "npc.testnpc", x, 2.0, 0, 0.5, 0.1f, 0xAABBCC, 0, -1, 1.0);
	}

	private static void check(String what, boolean ok) {
		System.out.println((ok ? "PASS  " : "FAIL  ") + what);
		if (!ok) {
			failed++;
		}
	}

	private ServerTests() {
	}
}
