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
		replayReconstructsLiveSessionExactly();
		visitorLogCountsWithoutIdentifying();
		visitorLogReadsTheClientThroughTheProxy();
		populationCensusCountsEveryLivingRole();
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
		String json = Protocol.write(Protocol.Full.of(s.tick(), s.entities(), s.entities().size()));
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

	/** A recorded session replays to a bit-identical world at any tick, and
	 * survives a JSON round-trip (the download/replay feature's core promise). */
	static void replayReconstructsLiveSessionExactly() throws Exception {
		SimulationRunner live = new SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(21));
		live.advance(20);
		live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("food", 10.5, 10.5, 0));
		live.advance(25);
		live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("crate", 20.5, 12.5, 0));
		live.advance(30);
		long liveTick = live.snapshot().tick();
		long liveSum = live.snapshot().checksum();

		net.hedinger.prototype.sim.Recording rec = net.hedinger.prototype.sim.Recording.of(21, live);
		check("replay reconstructs the live snapshot exactly",
				net.hedinger.prototype.sim.Replays.reconstruct(rec, liveTick).checksum() == liveSum);

		String json = Protocol.write(rec);
		net.hedinger.prototype.sim.Recording rec2 =
				Protocol.JSON.readValue(json, net.hedinger.prototype.sim.Recording.class);
		check("a serialized recording still reconstructs exactly",
				net.hedinger.prototype.sim.Replays.reconstruct(rec2, liveTick).checksum() == liveSum);

		WorldSnapshot mid = net.hedinger.prototype.sim.Replays.reconstruct(rec, 40);
		check("scrub-to-tick lands on the requested tick", mid.tick() == 40);
	}

	/**
	 * The visitor log answers "how many" and "how often" and nothing else. Note
	 * what it does NOT expose: there is no accessor that returns an address, a
	 * hash, or the set itself, so "cannot name a visitor" is a property of the
	 * class shape rather than something a test could catch after the fact.
	 */
	static void visitorLogCountsWithoutIdentifying() {
		VisitorLog log = new VisitorLog();
		log.record("1.2.3.4");
		log.record("1.2.3.4");
		log.record("5.6.7.8");
		check("repeat contacts are one visitor", log.distinct() == 2);
		check("every contact is a request", log.requests() == 3);

		log.record(null);
		log.record("   ");
		check("an unidentifiable contact cannot invent a visitor", log.distinct() == 2);
		check("an unidentifiable contact still counts as traffic", log.requests() == 5);
		check("an uncapped count is exact", !log.saturated());

		VisitorLog full = new VisitorLog();
		for (int i = 0; i < 20_050; i++) {
			full.record("10.0." + (i / 256) + "." + (i % 256));
		}
		check("the distinct set stops growing at its cap", full.distinct() == 20_000);
		check("a capped count says it is a floor", full.saturated());
		check("traffic keeps counting past the cap", full.requests() == 20_050);
	}

	/** Behind Caddy the direct peer is the proxy, so the client comes from the header. */
	static void visitorLogReadsTheClientThroughTheProxy() {
		check("no header means the peer is the client",
				"9.9.9.9".equals(VisitorLog.clientAddress("9.9.9.9", null)));
		check("a blank header falls back to the peer",
				"9.9.9.9".equals(VisitorLog.clientAddress("9.9.9.9", "  ")));
		check("the first hop is the client, the rest are proxies",
				"1.1.1.1".equals(VisitorLog.clientAddress("10.0.0.1", "1.1.1.1, 10.0.0.1")));
		check("surrounding whitespace is not part of the address",
				"1.1.1.1".equals(VisitorLog.clientAddress("10.0.0.1", " 1.1.1.1 ")));

		VisitorLog log = new VisitorLog();
		log.record(VisitorLog.clientAddress("10.0.0.1", "1.1.1.1, 10.0.0.1"));
		log.record(VisitorLog.clientAddress("10.0.0.1", "2.2.2.2, 10.0.0.1"));
		check("two clients behind one proxy are two visitors", log.distinct() == 2);
	}

	/**
	 * The role census counts the living, splits them by trophic role, and
	 * accounts for every one of them.
	 *
	 * <p>The last part is the one worth pinning: {@code trophicRole()} has three
	 * answers today and the census switches on them, so a fourth added later
	 * would silently land in the {@code default} arm and be counted as prey. Here
	 * the three columns are checked against an independent headcount of the live
	 * creatures, which fails the moment they stop adding up.
	 */
	static void populationCensusCountsEveryLivingRole() {
		net.hedinger.prototype.engine.Utils.seed(7);
		net.hedinger.prototype.engine.World w = net.hedinger.prototype.sim.Worlds.demo(7, 64, 44);
		WorldHost.PopSample s = WorldHost.censusOf(w, 123);

		int alive = 0;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.simtest.TestNPC t
					&& !t.isDead() && !t.isRemoved()) {
				alive++;
			}
		}
		check("the census finds creatures", alive > 0);
		check("every living creature lands in exactly one role",
				s.prey() + s.predator() + s.scavenger() == alive);
		check("a seeded world has all three roles",
				s.prey() > 0 && s.predator() > 0 && s.scavenger() > 0);
		check("the reading is stamped with the tick it was taken at", s.tick() == 123);

		// Corpses are not population: killing one must move the count down by one.
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.simtest.TestNPC t
					&& !t.isDead() && !t.isRemoved()) {
				t.kill();
				break;
			}
		}
		WorldHost.PopSample after = WorldHost.censusOf(w, 124);
		check("a corpse is not counted as population",
				after.prey() + after.predator() + after.scavenger() == alive - 1);
	}

	private static EntityState probe(int id, double x) {
		return new EntityState(id, "npc.testnpc", x, 2.0, 0, 0.5, 0.1f, 0f, 0xAABBCC, 0, -1, 1.0, 0);
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
