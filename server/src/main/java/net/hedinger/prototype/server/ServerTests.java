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
		fertilityCapsTheGrassSpriteStage();
		vegetationFeedCarriesTheKind();
		theBakeIsOpaqueExceptWhereYouCanSeeDown();
		machineryIsNotInspectedForFoodAndWater();
		theDroneRankIsDronesAndOnlyDrones();
		System.out.println(failed == 0 ? "server tests: all passed" : "server tests: " + failed + " FAILED");
		if (failed > 0) {
			System.exit(1);
		}
	}

	/**
	 * The inspector's four books are physiology, and the facility's machines
	 * keep none of them: not metabolic, so hunger and thirst never leave zero
	 * and energy never leaves it either. Sent regardless, the panel drew a
	 * drone as fully fed, fully watered and completely out of energy — two bars
	 * that mean nothing and one that reads as a machine about to drop.
	 *
	 * <p>Tested at the wire rather than at the panel because that is where the
	 * decision now lives: the client omits any book it is not given, so what
	 * matters is that a machine is not given one.
	 */
	static void machineryIsNotInspectedForFoodAndWater() {
		WorldHost host = new WorldHost(11);

		int machines = 0, creatures = 0;
		for (int id : host.liveMachineIds()) {
			java.util.Map<String, Object> d = host.entityDetail(id);
			if (d == null) {
				continue;
			}
			machines++;
			check("a machine is not reported fed: " + d.get("subtype"),
					!d.containsKey("hunger"));
			check("nor watered: " + d.get("subtype"), !d.containsKey("thirst"));
			check("nor out of energy: " + d.get("subtype"), !d.containsKey("energy"));
			check("but it still has a health reading: " + d.get("subtype"),
					d.containsKey("health"));
		}
		for (int id : host.liveCreatureIds()) {
			java.util.Map<String, Object> d = host.entityDetail(id);
			if (d == null) {
				continue;
			}
			creatures++;
			check("a creature still keeps its books", d.containsKey("hunger")
					&& d.containsKey("thirst") && d.containsKey("energy"));
		}
		check("the world actually contained machinery", machines > 0);
		check("and creatures to contrast it with", creatures > 0);
	}

	/**
	 * The viewer's "next drone" button is offered the steward's drones and no
	 * other machinery.
	 *
	 * <p>The narrowing is the whole assertion. {@link WorldHost#liveMachineIds()}
	 * answers the broader question — anything inorganic — and it was sitting
	 * right there when this endpoint was written; using it would have quietly put
	 * the facility loader into a rank of stewards it is not part of, and the
	 * button would have cycled through a crate-hauler with no way for anyone to
	 * tell that was a mistake rather than the design. So this checks both halves:
	 * every id offered is a drone, and the world it was asked about really does
	 * contain machinery that was left out.
	 *
	 * <p>Also that each entry carries a level. The entity stream is filtered to
	 * the floor a viewer is watching, so a client that is not already on the
	 * drones' floor cannot see them at all — the z is the only thing that lets
	 * the button go and get one.
	 */
	static void theDroneRankIsDronesAndOnlyDrones() {
		WorldHost host = new WorldHost(11);

		java.util.List<java.util.Map<String, Object>> rank = host.droneRank();
		check("the rank is the world's full complement of drones",
				rank.size() == net.hedinger.prototype.sim.Worlds.DRONE_RANK);

		java.util.Set<Integer> offered = new java.util.HashSet<>();
		for (java.util.Map<String, Object> d : rank) {
			int id = ((Number) d.get("id")).intValue();
			offered.add(id);
			check("every entry says where it is, floor included",
					d.get("x") != null && d.get("y") != null && d.get("z") != null);
			java.util.Map<String, Object> detail = host.entityDetail(id);
			check("id " + id + " is a steward drone",
					detail != null && "stewarddrone".equals(detail.get("subtype")));
		}
		check("no drone is offered twice", offered.size() == rank.size());

		// The other half: machinery deliberately left out. Without this the test
		// would still pass on an endpoint that returned every machine in a world
		// that happened to contain only drones.
		int machines = 0, omitted = 0;
		for (int id : host.liveMachineIds()) {
			machines++;
			if (!offered.contains(id)) {
				omitted++;
			}
		}
		check("the world has more machinery than drones", machines > rank.size());
		check("and the rank leaves it out", omitted == machines - rank.size());
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
	 * <p>The last part is the one worth pinning: {@code ecoRole()} has one answer
	 * per clade and the census switches on them, so a clade added later would land
	 * in the {@code default} arm and be counted as nothing at all. Here the columns
	 * are checked against an independent headcount of the live creatures, which
	 * fails the moment they stop adding up.
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
		check("every living creature lands in exactly one clade",
				s.herbivore() + s.predator() + s.scavenger() + s.parasite() == alive);
		check("a seeded world has all four clades",
				s.herbivore() > 0 && s.predator() > 0 && s.scavenger() > 0 && s.parasite() > 0);
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
				after.herbivore() + after.predator() + after.scavenger() + after.parasite()
						== alive - 1);

		// The wire carries the clade AND the species label a viewer reads. The
		// species is derived at read time from the markers -- nothing stores it --
		// so this is the only place the plumbing gets checked.
		// Its own world rather than the one above: WorldHost builds and owns one.
		WorldHost host = new WorldHost(7);
		java.util.Map<String, Object> detail = null;
		for (int id : host.liveCreatureIds()) {
			detail = host.entityDetail(id);
			if (detail != null && detail.get("role") != null && detail.get("species") != null) {
				break;
			}
		}
		check("a creature's detail reaches the wire", detail != null);
		String role = String.valueOf(detail.get("role"));
		check("the role on the wire is a clade name (" + role + ")",
				role.equals("herbivore") || role.equals("predator")
						|| role.equals("scavenger") || role.equals("parasite"));
		check("the species label rides along", detail.get("species") != null);
		check("and it is one of the named species",
				java.util.List.of("umbral", "vermil", "verdant", "cobalt", "ochre", "dusken")
						.contains(String.valueOf(detail.get("species"))));
	}

	/**
	 * A tile's fertility is the ceiling on the grass sprite it can ever show:
	 * poor ground, however long it regrows, never reaches the tall-tuft stage
	 * a rich meadow wears.
	 *
	 * <p>Worth pinning because the obvious implementation is the wrong one.
	 * Reporting each tile as a fraction of ITS OWN capacity makes every fully
	 * grown tile read 100 — half-fertile ground grows the same lush sprite as
	 * the richest meadow, and fertility vanishes from the picture entirely.
	 * The scale has to be absolute for the ceiling to mean anything.
	 */
	static void fertilityCapsTheGrassSpriteStage() {
		long t = 10_000_000; // long enough that every tile has regrown to its cap
		check("dead ground draws no grass at all",
				VegFeed.stateOf(WorldHost.grassLevel(grassland(0.0), t)) == 0);
		check("rich ground grows the full meadow",
				VegFeed.stateOf(WorldHost.grassLevel(grassland(1.0), t)) == 5);
		int half = VegFeed.stateOf(WorldHost.grassLevel(grassland(0.5), t));
		check("half-fertile ground never reaches the full meadow", half < 5);
		check("half-fertile ground still grows real grass", half > 1);
		check("poor ground keeps to the sparsest growth",
				VegFeed.stateOf(WorldHost.grassLevel(grassland(0.15), t)) <= 2);

		// The ceiling rises with fertility, so richness stays readable as a
		// gradient rather than collapsing into two buckets.
		int prev = -1;
		for (double f : new double[] { 0.15, 0.35, 0.55, 0.75, 1.0 }) {
			int stage = VegFeed.stateOf(WorldHost.grassLevel(grassland(f), t));
			check("fertility " + f + " does not grow less than poorer ground", stage >= prev);
			prev = stage;
		}

		// Grazing still reads on top of the ceiling: the overlay must show
		// depletion, not just potential.
		net.hedinger.prototype.engine.Tile grazed = grassland(1.0);
		grazed.graze(t, 1.0);
		check("a stripped tile falls to the trampled remnants",
				VegFeed.stateOf(WorldHost.grassLevel(grazed, t)) == 1);
	}

	/**
	 * The alpha channel of a served chunk means exactly one thing: you can see
	 * down here. Nothing else in the bake is allowed to be see-through, and
	 * nothing that should be see-through is allowed to be painted shut.
	 *
	 * <p>Both halves have shipped broken. Pit interiors were once drawn by
	 * SKIPPING their open pixels and letting the level below show through from
	 * underneath — which the desktop renderer composites and the chunk bake does
	 * not, so 55% of every pit shipped as the colour the image was cleared to.
	 * The bake now clears to nothing instead, which fixes that but hands the
	 * alpha channel a second job: if any pass quietly failed to cover its own
	 * tile, the gap would be a window onto the wrong floor rather than a black
	 * square, and would look plausible. So this pins the whole channel — every
	 * non-opaque art-pixel must sit in a tile that can actually be seen down.
	 *
	 * <p>This bakes every level of the demo world and roughly doubles the time
	 * {@code gradle check} takes. It earns it: a bake that declines to paint is
	 * invisible to every other test in the suite — the one that shipped was
	 * caught by eye, months late — and there is no cheaper way to ask.
	 */
	static void theBakeIsOpaqueExceptWhereYouCanSeeDown() {
		net.hedinger.prototype.engine.World terrain = Worlds.demoTerrain(42);
		// bakeLevelImage through chunkRenderer, because that is the pair WorldHost
		// serves from. renderLevelImage builds the DESKTOP renderer's layers, which
		// composite every level into one picture — a pit there is filled in by the
		// floor below and could never be see-through. Asserting on it would be the
		// same mistake the pits themselves were: testing the path nobody looks at.
		net.hedinger.prototype.engine.LayerRenderer lr = LayerBaker.chunkRenderer(terrain);
		int deep = 0, bottom = -1;
		for (int z = 0; z < terrain.getLevels(); z++) {
			// One render per level: baking a whole level is the slow part here.
			java.awt.image.BufferedImage img = LayerBaker.bakeLevelImage(terrain, lr, z);
			check("level " + z + " leaves no art-pixel unpainted", opaqueBlack(img) == 0);
			String stray = strayOpenTile(terrain, img, z);
			check("level " + z + " is see-through only where a pit is (" + stray + ")",
					stray == null);

			int[] p = findPit(terrain, z);
			if (p == null) {
				continue;
			}
			if (z == 0) {
				// The bottom level's pits have nothing under them to look down onto.
				bottom = z;
				check("a pit over nothing is solid to the eye (" + p[0] + "," + p[1] + " "
						+ terrain.getTile(p[0], p[1], z).getType() + ")",
						openPixels(img, p) == 0);
			} else {
				deep = z;
				check("a pit over a floor opens onto it", openPixels(img, p) > 0);
			}
		}
		check("the demo world has a pit over another level", deep > 0);
		check("the demo world has a pit over nothing", bottom == 0);
	}

	/**
	 * The first see-through tile on a level that is not on the map edge, as
	 * {x, y}: a pit for preference, and a grate if the level has no pit.
	 *
	 * <p>The grate fallback is what keeps the bottom-level half of this test
	 * alive. It used to look for pits alone, which was enough while the caves
	 * were the bottom level and had plenty; a floor added underneath them left
	 * the bottom level with no pit at all, and the "nothing below" branch simply
	 * stopped running. A catwalk's gaps take a pit's treatment for exactly this
	 * reason (see Grid.pitFloor), so they answer the same question.
	 */
	private static int[] findPit(net.hedinger.prototype.engine.World w, int z) {
		if (z < 0) {
			return null;
		}
		int[] grate = null;
		for (int x = 1; x < w.getColums() - 1; x++) {
			for (int y = 1; y < w.getRows() - 1; y++) {
				net.hedinger.prototype.engine.Tile.TileType t = w.getTile(x, y, z).getType();
				if (t == net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE) {
					return new int[] { x, y };
				}
				if (grate == null
						&& t == net.hedinger.prototype.engine.Tile.TileType.TYPE_CATWALK) {
					grate = new int[] { x, y };
				}
			}
		}
		return grate;
	}

	/** Whether a tile is one you could ever see through: a pit, a drop-shaft,
	 *  or a grated catwalk. Anything else must cover itself completely. */
	private static boolean canSeeDown(net.hedinger.prototype.engine.Tile t) {
		net.hedinger.prototype.engine.Tile.TileType ty = t.getType();
		return ty == net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE
				|| ty == net.hedinger.prototype.engine.Tile.TileType.TYPE_SHAFT
				|| ty == net.hedinger.prototype.engine.Tile.TileType.TYPE_CATWALK;
	}

	/** The first tile that is see-through without being a pit, or null. */
	private static String strayOpenTile(net.hedinger.prototype.engine.World w,
			java.awt.image.BufferedImage img, int z) {
		int a = LayerBaker.CHUNK_PX;
		for (int x = 0; x < w.getColums(); x++) {
			for (int y = 0; y < w.getRows(); y++) {
				if (canSeeDown(w.getTile(x, y, z))) {
					continue;
				}
				for (int aj = 0; aj < a; aj++) {
					for (int ai = 0; ai < a; ai++) {
						if ((artPixel(img, x, y, ai, aj) >>> 24) < 255) {
							return "(" + x + "," + y + ") " + w.getTile(x, y, z).getType();
						}
					}
				}
			}
		}
		return null;
	}

	/** Art-pixels inside the pit at {@code p} that are not fully opaque. */
	private static int openPixels(java.awt.image.BufferedImage img, int[] p) {
		int n = 0;
		for (int aj = 3; aj < 9; aj++) { // inside the rim on every side
			for (int ai = 3; ai < 9; ai++) {
				if ((artPixel(img, p[0], p[1], ai, aj) >>> 24) < 255) {
					n++;
				}
			}
		}
		return n;
	}

	/** Art-pixel (ai, aj) of tile (x, y), sampled where the encoder samples it. */
	private static int artPixel(java.awt.image.BufferedImage img, int x, int y, int ai, int aj) {
		int ts = net.hedinger.prototype.engine.ResourceManager.tileSize, a = LayerBaker.CHUNK_PX;
		return img.getRGB(x * ts + ai * ts / a, y * ts + aj * ts / a);
	}

	/** Art-pixels that are opaque black — a colour in no ramp, and the mark the
	 *  old skip-the-pixel bug left behind. */
	private static int opaqueBlack(java.awt.image.BufferedImage img) {
		int ts = net.hedinger.prototype.engine.ResourceManager.tileSize, a = LayerBaker.CHUNK_PX;
		int n = 0;
		for (int y = 0; y + ts <= img.getHeight(); y += ts) {
			for (int x = 0; x + ts <= img.getWidth(); x += ts) {
				for (int aj = 0; aj < a; aj++) {
					for (int ai = 0; ai < a; ai++) {
						if (img.getRGB(x + ai * ts / a, y + aj * ts / a) == 0xff000000) {
							n++;
						}
					}
				}
			}
		}
		return n;
	}

	private static net.hedinger.prototype.engine.Tile grassland(double fertility) {
		var t = new net.hedinger.prototype.engine.Tile(0, 0, 0,
				net.hedinger.prototype.engine.Tile.TileType.TYPE_FLOOR);
		t.setFertility(fertility);
		return t;
	}

	private static EntityState probe(int id, double x) {
		return new EntityState(id, "npc.testnpc", x, 2.0, 0, 0.5, 0.1f, 0f, 0xAABBCC, 0, -1, 1.0, 0);
	}

	/**
	 * The vegetation feed says WHICH vegetation a tile grows, not just how much.
	 *
	 * <p>The viewer stamps one sprite per tile and had no way to tell a fungus bed
	 * from a meadow, so it drew grass on both and the mushroom sprite — baked all
	 * along — was never once put on screen. The kind rides as the high bit of the
	 * full grid and deliberately NOT in the deltas: a change entry packs
	 * {@code tile << 3 | state} with three bits for the state, and terrain does not
	 * change at runtime, so a delta has neither room for the kind nor any need of
	 * it. This pins both halves.
	 */
	static void vegetationFeedCarriesTheKind() {
		net.hedinger.prototype.engine.Utils.seed(42);
		WorldHost host = new WorldHost(42);
		int fungusLevels = 0;
		for (int z = 0; z < 2; z++) {
			byte[] kinds = host.vegKinds(z);
			if (kinds == null) {
				continue;
			}
			int fungus = 0;
			for (byte k : kinds) {
				if (k != 0) {
					fungus++;
				}
			}
			if (fungus == 0) {
				continue;
			}
			fungusLevels++;
			@SuppressWarnings("unchecked")
			java.util.Map<String, Object> full =
					(java.util.Map<String, Object>) host.vegetationSince(z, -1);
			check("a full grid comes back for level " + z, full.get("states") != null);
			byte[] grid = java.util.Base64.getDecoder()
					.decode((String) full.get("states"));
			check("the grid covers the level", grid.length == kinds.length);

			int marked = 0, markedButBare = 0;
			for (int i = 0; i < grid.length; i++) {
				boolean bit = (grid[i] & VegFeed.KIND_FUNGUS) != 0;
				check("the kind bit is set exactly on the fungus tiles",
						bit == (kinds[i] != 0));
				if (bit) {
					marked++;
					if ((grid[i] & 0x07) == 0) {
						markedButBare++;
					}
				}
			}
			check("the fungus beds are marked (" + marked + ")", marked == fungus);
			// The stage must survive alongside the kind, or every bed would draw as
			// "nothing grows here" and the fix would swap one blank for another.
			check("and they still carry a growth stage", markedButBare < marked);
		}
		check("the world has a level that grows fungus", fungusLevels > 0);
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
