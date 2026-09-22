package net.hedinger.prototype.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.javalin.websocket.WsContext;

import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.sim.SimulationRunner;
import net.hedinger.prototype.sim.Worlds;
import net.hedinger.prototype.sim.WorldSnapshot;

/**
 * Hosts the process's one live world (engine globals allow exactly one — see
 * MODERNIZATION.md): the runner, the baked static layers, the set of connected
 * viewers, and the broadcast loop that fans state out to them.
 *
 * <p>Threading: the sim thread only publishes snapshots (the runner keeps the
 * latest); a single scheduled broadcaster thread reads it ~10x/s, computes the
 * delta against what it last sent, and pushes JSON to every open socket. A
 * viewer that joins mid-stream gets {@code hello}+{@code full} immediately and
 * converges via the cumulative-safe deltas. Viewers can never back-pressure
 * the simulation — a slow socket only hurts itself.
 */
final class WorldHost {

	/** Broadcast cadence: every 100 ms ≈ 10 Hz (the client interpolates). */
	private static final long BROADCAST_MS = 100;

	/** Ground is baked and served as fixed-size map chunks (google-maps style):
	 *  each level is rendered once, then sliced into CHUNK_TILES-square PNGs the
	 *  client streams on demand. Bounds per-request size and lets the client
	 *  fetch only the region in view. */
	private static final int CHUNK_TILES = 16;

	private final Object lock = new Object();
	private long seed;
	private SimulationRunner runner;
	private java.util.Map<String, byte[]> chunks; // key "z/cx_cy" -> PNG
	private int chunksX, chunksY; // chunk grid dimensions
	private WorldSnapshot lastSent;
	private boolean forceFull = false;

	private final ConcurrentHashMap.KeySetView<WsContext, Boolean> sessions = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService broadcaster =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "broadcast");
				t.setDaemon(true);
				return t;
			});

	private volatile long startedAt = System.currentTimeMillis();
	private final java.io.File recordDir; // durable recording dir, or null (off)

	private final int worldCols; // <=0 means use Worlds' built-in default size
	private final int worldRows;
	/** Which world this host runs: "demo" (the living ecosystem, the default)
	 *  or "blackmesa" (the authored, uninhabited facility). Fixed for the
	 *  host's lifetime — a reset rebuilds the same kind from a new seed. */
	private final String worldKind;

	WorldHost(long seed) {
		this(seed, 0, 0);
	}

	WorldHost(long seed, int cols, int rows) {
		this(seed, cols, rows, "demo");
	}

	/** Size-overridable ctor: {@code cols}/{@code rows} &gt; 0 build the world at
	 *  that size (so the deployed size can be tuned — or scaled down under a tight
	 *  heap — from config without a rebuild); otherwise the built-in default. */
	WorldHost(long seed, int cols, int rows, String kind) {
		this.worldCols = cols;
		this.worldRows = rows;
		this.worldKind = kind;
		String rd = System.getenv("RECORD_DIR");
		this.recordDir = (rd == null || rd.isBlank()) ? null : new java.io.File(rd);
		if (recordDir != null) {
			recordDir.mkdirs();
		}
		buildWorld(seed);
		broadcaster.scheduleAtFixedRate(this::broadcast, BROADCAST_MS, BROADCAST_MS, TimeUnit.MILLISECONDS);
		// Durability: periodically dump the session's recording (seed + command
		// log) so a crash or reboot never loses a viewer's spawns.
		broadcaster.scheduleAtFixedRate(this::dumpRecording, 15, 15, TimeUnit.SECONDS);
		// The headcount by role, for the population graph. Its own slow beat: a
		// series is about the shape of an hour, not about this tick.
		broadcaster.scheduleAtFixedRate(this::samplePopulation,
				POP_SAMPLE_SEC, POP_SAMPLE_SEC, TimeUnit.SECONDS);
		// The Sankey's stage snapshots. The first lands seconds after boot so a
		// fresh world has a first column immediately, then the slow beat.
		broadcaster.scheduleAtFixedRate(this::sampleStage, 5, STAGE_SEC, TimeUnit.SECONDS);
	}

	private void buildWorld(long newSeed) {
		seed = newSeed;
		startedAt = System.currentTimeMillis();
		popHistory = java.util.List.of(); // a new world starts its own series
		linHistory = java.util.List.of(); // both lenses: old lineages are not this world's
		stageHistory = java.util.List.of(); // and the flow stages with them
		boolean mesa = "blackmesa".equals(worldKind);
		boolean sized = !mesa && worldCols > 0 && worldRows > 0; // the campus is authored at its own size
		SimulationRunner r = new SimulationRunner(mesa
				? net.hedinger.prototype.sim.BlackMesa.build(newSeed)
				: sized ? Worlds.demo(newSeed, worldCols, worldRows) : Worlds.demo(newSeed));
		var terrain = mesa ? net.hedinger.prototype.sim.BlackMesa.build(newSeed)
				: sized ? Worlds.demoTerrain(newSeed, worldCols, worldRows)
				: Worlds.demoTerrain(newSeed); // entity-free twin, for the bake
		int cols = r.world().getColums(), rows = r.world().getRows();
		int cxN = (cols + CHUNK_TILES - 1) / CHUNK_TILES;
		int cyN = (rows + CHUNK_TILES - 1) / CHUNK_TILES;
		int ts = net.hedinger.prototype.engine.ResourceManager.tileSize;
		// Bake each level once into a single image (bounded by the shared tile-sprite
		// cache — see ProcTiles), then slice it into chunk PNGs and drop it before the
		// next level. One render pass per level (not one per chunk) keeps the bake
		// fast, and only one level image is ever live, so peak memory stays well
		// within the deploy heap even for a large map.
		net.hedinger.prototype.engine.LayerRenderer lr = LayerBaker.chunkRenderer(terrain);
		java.util.Map<String, byte[]> baked = new java.util.HashMap<String, byte[]>();
		for (int z = 0; z < r.world().getLevels(); z++) {
			java.awt.image.BufferedImage level = LayerBaker.bakeLevelImage(terrain, lr, z);
			for (int cy = 0; cy < cyN; cy++) {
				for (int cx = 0; cx < cxN; cx++) {
					int x0 = cx * CHUNK_TILES * ts, y0 = cy * CHUNK_TILES * ts;
					int cw = Math.min(CHUNK_TILES * ts, cols * ts - x0);
					int ch = Math.min(CHUNK_TILES * ts, rows * ts - y0);
					baked.put(z + "/" + cx + "_" + cy, LayerBaker.chunkPng(level, x0, y0, cw, ch));
				}
			}
			level = null; // free this level's image before baking the next
		}
		// The bake is free of LIVE state by construction — ground pixels read
		// only tile type and the static fertility potential (grassland's
		// sward), and the bake path draws no live tufts (LayerBaker) — so a
		// single bake serves as the ground under the client's vegetation
		// sprite layer. (The old design baked a second, force-grazed twin and
		// dithered between the two; five sprite states replaced all of that.)
		runner = r;
		chunks = baked;
		chunksX = cxN;
		chunksY = cyN;
		lastSent = r.snapshot();
		vegFeeds.clear(); // a fresh world starts a fresh state history
		r.start();
	}

	// ---- viewer lifecycle --------------------------------------------------

	/** The level each viewer is watching — the stream is filtered to it, so a
	 *  browser never parses the cave cohort while looking at the surface.
	 *  Defaults to the surface (top level) on connect. */
	private final java.util.concurrent.ConcurrentHashMap<WsContext, Integer> viewerLevel =
			new java.util.concurrent.ConcurrentHashMap<>();

	/** Viewers whose stream also carries the floor BELOW their level, so the
	 *  client can render bodies moving under its pit mouths. Opt-in per
	 *  viewer (the client's below toggle), because it roughly doubles the
	 *  cohort a browser parses. */
	private final java.util.Set<WsContext> viewerBelow =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** The levels a subscription watches: its own, plus the floor below when
	 *  the viewer asked for it and one exists. */
	private static java.util.Set<Integer> watchedOf(int z, boolean below) {
		return below && z - 1 >= 0 ? java.util.Set.of(z, z - 1) : java.util.Set.of(z);
	}

	/** Viewers that connected with {@code ?bin=1}: their full/delta frames are
	 *  {@link BinaryProtocol} bytes instead of JSON. The web client always opts
	 *  in (it is served by this process, so the builds are in lockstep); plain
	 *  JSON stays the default so scripts and tooling can read the stream. */
	private final java.util.Set<WsContext> binViewers =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	private boolean isBin(WsContext ctx) {
		return binViewers.contains(ctx);
	}

	private void sendFull(WsContext ctx, WorldSnapshot s, int z) {
		var watched = watchedOf(z, viewerBelow.contains(ctx));
		if (isBin(ctx)) {
			ctx.send(java.nio.ByteBuffer.wrap(
					BinaryProtocol.full(s.tick(), onLevels(s, watched), s.entities().size())));
		} else {
			ctx.send(Protocol.write(
					Protocol.Full.of(s.tick(), onLevels(s, watched), s.entities().size())));
		}
	}

	private int surfaceLevel() {
		return runner.world().getSurfaceZ();
	}

	/** Entities of the snapshot on the watched levels (z rounds, matching the
	 *  client): the viewer's own floor, plus the one below when subscribed. */
	private static java.util.List<net.hedinger.prototype.sim.EntityState> onLevels(
			WorldSnapshot s, java.util.Set<Integer> watched) {
		java.util.List<net.hedinger.prototype.sim.EntityState> out = new java.util.ArrayList<>();
		for (net.hedinger.prototype.sim.EntityState e : s.entities()) {
			if (watched.contains((int) Math.round(e.z()))) {
				out.add(e);
			}
		}
		return out;
	}

	/** New viewer: greet with world geometry, then its level's entity list. */
	void onConnect(WsContext ctx) {
		ctx.enableAutomaticPings(15, TimeUnit.SECONDS); // keep proxies from idling us out
		if ("1".equals(ctx.queryParam("bin"))) {
			binViewers.add(ctx);
		}
		synchronized (lock) {
			WorldSnapshot s = runner.snapshot();
			var w = runner.world();
			viewerLevel.put(ctx, surfaceLevel());
			ctx.send(Protocol.write(Protocol.Hello.of(seed, w.getColums(), w.getRows(), w.getLevels(),
					w.getSurfaceZ(),
					net.hedinger.prototype.engine.ResourceManager.tileSize, LayerBaker.CHUNK_PX,
					s.tick(), runner.isPaused(), runner.getSpeed(),
					java.util.List.of(), CHUNK_TILES, String.valueOf(startedAt))));
			sendFull(ctx, s, surfaceLevel());
			sessions.add(ctx);
		}
	}

	void onClose(WsContext ctx) {
		sessions.remove(ctx);
		viewerLevel.remove(ctx);
		viewerBelow.remove(ctx);
		binViewers.remove(ctx);
	}

	/** A viewer switched levels (or its below preference): refilter its stream
	 *  and resync it with a full snapshot (its old tracks are cleared by the
	 *  full). */
	void setViewerLevel(WsContext ctx, int z, boolean below) {
		var w = runner.world();
		int clamped = Math.max(0, Math.min(w.getLevels() - 1, z));
		viewerLevel.put(ctx, clamped);
		if (below) {
			viewerBelow.add(ctx);
		} else {
			viewerBelow.remove(ctx);
		}
		synchronized (lock) {
			sendFull(ctx, runner.snapshot(), clamped);
		}
	}

	/** One viewer's subscription as a map key: level packed with the below
	 *  flag, so two viewers on the same floor with different below settings
	 *  get different (correct) frames. */
	private int subKey(WsContext ctx) {
		return (viewerLevel.getOrDefault(ctx, surfaceLevel()) << 1)
				| (viewerBelow.contains(ctx) ? 1 : 0);
	}

	/** ~10 Hz: send what changed since the last broadcast to every viewer,
	 *  filtered to the levels each viewer watches. Messages are encoded once
	 *  per DISTINCT subscription in use, not per viewer. */
	private void broadcast() {
		try {
			// Encoded once per DISTINCT (level, below, format) in use, not per
			// viewer: the key packs the level with the below flag.
			java.util.Map<Integer, String> jsonByLevel = new java.util.HashMap<>();
			java.util.Map<Integer, byte[]> binByLevel = new java.util.HashMap<>();
			synchronized (lock) {
				WorldSnapshot now = runner.snapshot();
				if (now.tick() == lastSent.tick() && !forceFull) {
					return; // paused (or stalled): nothing new to say
				}
				int total = now.entities().size();
				java.util.Set<Integer> jsonLevels = new java.util.HashSet<>();
				java.util.Set<Integer> binLevels = new java.util.HashSet<>();
				for (WsContext ctx : sessions) {
					(isBin(ctx) ? binLevels : jsonLevels).add(subKey(ctx));
				}
				java.util.Set<Integer> levels = new java.util.HashSet<>(jsonLevels);
				levels.addAll(binLevels);
				// Previous level per id, to turn a ramp crossing into a
				// departure on the level left behind — and, for the binary
				// stream, to mark bodies NEW to a level (they need a birth
				// record, not just a pose).
				java.util.Map<Integer, Integer> prevZ = new java.util.HashMap<>();
				for (net.hedinger.prototype.sim.EntityState e : lastSent.entities()) {
					prevZ.put(e.id(), (int) Math.round(e.z()));
				}
				if (forceFull) {
					for (int key : levels) {
						var watched = watchedOf(key >> 1, (key & 1) != 0);
						if (jsonLevels.contains(key)) {
							jsonByLevel.put(key, Protocol.write(
									Protocol.Full.of(now.tick(), onLevels(now, watched), total)));
						}
						if (binLevels.contains(key)) {
							binByLevel.put(key, BinaryProtocol.full(now.tick(), onLevels(now, watched), total));
						}
					}
					forceFull = false;
				} else {
					DeltaEncoder.Delta d = DeltaEncoder.diff(lastSent, now);
					for (int key : levels) {
						var watched = watchedOf(key >> 1, (key & 1) != 0);
						java.util.List<net.hedinger.prototype.sim.EntityState> up = new java.util.ArrayList<>();
						// gone-if-unknown is a no-op client-side, so globally
						// vanished ids can go to every level unfiltered.
						java.util.List<Integer> gone = new java.util.ArrayList<>(d.gone());
						for (net.hedinger.prototype.sim.EntityState e : d.upsert()) {
							int ez = (int) Math.round(e.z());
							if (watched.contains(ez)) {
								up.add(e);
							} else {
								Integer was = prevZ.get(e.id());
								if (was != null && watched.contains(was)) {
									gone.add(e.id()); // walked a ramp out of the watched floors
								}
							}
						}
						if (jsonLevels.contains(key)) {
							jsonByLevel.put(key, Protocol.write(Protocol.Delta.of(now.tick(), up, gone, total)));
						}
						if (binLevels.contains(key)) {
							binByLevel.put(key, BinaryProtocol.delta(now.tick(), up, gone, total, prevZ, watched));
						}
					}
				}
				lastSent = now;
			}
			for (WsContext ctx : sessions) {
				if (!ctx.session.isOpen()) {
					continue;
				}
				int key = subKey(ctx);
				if (isBin(ctx)) {
					byte[] msg = binByLevel.get(key);
					if (msg != null) {
						ctx.send(java.nio.ByteBuffer.wrap(msg)); // wrap: fresh position per send
					}
				} else {
					String msg = jsonByLevel.get(key);
					if (msg != null) {
						ctx.send(msg);
					}
				}
			}
		} catch (Exception e) {
			// The broadcast loop must survive anything a socket throws at it.
			System.err.println("broadcast: " + e);
		}
	}

	/** Announces a control change (pause/speed) immediately, outside the tick flow. */
	void announceStatus() {
		String msg = Protocol.write(Protocol.Status.of(runner.snapshot().tick(),
				runner.isPaused(), runner.getSpeed()));
		for (WsContext ctx : sessions) {
			if (ctx.session.isOpen()) {
				ctx.send(msg);
			}
		}
	}

	// ---- control -----------------------------------------------------------

	/** Tears down the current world and starts a fresh one from the seed. */
	void reset(long newSeed) {
		synchronized (lock) {
			runner.stop();
			// A fresh recording is seed + log, and its log assumes the
			// code-level constants: any live tuning dies with the old world.
			net.hedinger.prototype.sim.Tuning.restoreDefaults();
			buildWorld(newSeed);
			forceFull = true; // every viewer resyncs on the next broadcast
		}
		announceStatus();
	}

	/**
	 * A detail record for one entity, for the tap-to-inspect panel: identity,
	 * live energy/health/state, and — for a genome-bearing creature — a readable
	 * summary of its heritable traits. Returns null if the id is gone. Reads live
	 * fields without locking the sim: a momentarily stale number in an info panel
	 * is harmless, and it never mutates anything.
	 */
	/** Ids of the living, genome-bearing creatures in this host's world — a test
	 *  seam, so the suite can ask what the wire says about a real creature without
	 *  reaching past the host into its world. */
	/** Ids of the living machines — the counterpart seam to
	 *  {@link #liveCreatureIds()}, so the suite can ask what the wire says
	 *  about plant as well as about animals. */
	java.util.List<Integer> liveMachineIds() {
		java.util.List<Integer> out = new java.util.ArrayList<>();
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e instanceof net.hedinger.prototype.entities.NPC n && !n.isRemoved()
					&& !n.isOrganic()) {
				out.add(n.getID());
			}
		}
		return out;
	}

	/** Kills one creature where it stands, so the suite can read back what the
	 *  wire says about a CARCASS. The same seam as {@link #liveCreatureIds()}:
	 *  a test asks the host, never the world behind it. */
	void killForTest(int id) {
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e.getID() == id && e instanceof net.hedinger.prototype.entities.NPC n) {
				n.kill();
				return;
			}
		}
	}

	/** Every entity in the world, for a suite that needs to walk them all rather
	 *  than one kind of them. The same seam as {@link #killForTest}. */
	Iterable<net.hedinger.prototype.engine.Entity> worldEntitiesForTest() {
		return runner.world().getEntities();
	}

	/** The world's warden, or null. The same seam as {@link #killForTest}: the
	 *  suite asks the host rather than reaching past it into the world, so the
	 *  bounds test can compare what {@link #cladeBounds()} put on the wire
	 *  against the object that enforces them. */
	net.hedinger.prototype.sim.WorldSteward stewardForTest() {
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e instanceof net.hedinger.prototype.sim.WorldSteward st) {
				return st;
			}
		}
		return null;
	}

	java.util.List<Integer> liveCreatureIds() {
		java.util.List<Integer> out = new java.util.ArrayList<>();
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e instanceof net.hedinger.prototype.simtest.TestNPC t
					&& !t.isDead() && !t.isRemoved() && t.getGenome() != null) {
				out.add(t.getID());
			}
		}
		return out;
	}

	/**
	 * The steward's drones, in a stable order, as {@code {id, x, y, z}}.
	 *
	 * <p>For the viewer's "next drone" button. The entity stream is filtered to
	 * the level a viewer is watching, so from the surface a client cannot see
	 * that the drones exist at all, let alone where they are — the same blindness
	 * that made a followed creature walking downstairs look like one that died.
	 * This endpoint is not level-filtered, which is the whole point of it.
	 *
	 * <p>Drones and no other machinery. {@link #liveMachineIds()} asks the
	 * broader question — anything inorganic — and answering it here would put the
	 * facility loader in a rank of stewards it is not part of. The rank is a
	 * cohort with one standing order between them; the loader hauls crates and
	 * has nothing to do with it.
	 *
	 * <p>Ordered by id, which is spawn order, which is the order the pads were
	 * cut. That makes the button's cycle the same every time round rather than
	 * whatever order the entity list happens to be in this tick.
	 */
	java.util.List<java.util.Map<String, Object>> droneRank() {
		java.util.List<net.hedinger.prototype.sim.StewardDrone> rank =
				new java.util.ArrayList<>();
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e instanceof net.hedinger.prototype.sim.StewardDrone d && !d.isRemoved()
					&& !d.isDead()) {
				rank.add(d);
			}
		}
		rank.sort(java.util.Comparator.comparingInt(
				net.hedinger.prototype.engine.Entity::getID));
		java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
		for (net.hedinger.prototype.sim.StewardDrone d : rank) {
			out.add(java.util.Map.of("id", d.getID(), "x", round(d.getX()),
					"y", round(d.getY()), "z", d.getLvl()));
		}
		return out;
	}

	/**
	 * One creature's family line, for the inspector's lineage tab: the chain of
	 * ancestors (following parentA, the primary line, noting the other parent
	 * on each link), and every remembered child. Read from the world's birth
	 * registry — the counts and flows can say what happened to species; only a
	 * record written at each birth can say what happened to THIS body's family.
	 */
	java.util.Map<String, Object> entityLineage(int id) {
		synchronized (runner) {
			var w = runner.world();
			java.util.List<java.util.Map<String, Object>> chain =
					new java.util.ArrayList<java.util.Map<String, Object>>();
			int cur = id;
			boolean faded = false;
			// A generous cap, not a lie: past it the chain says it was cut. The
			// registry itself bounds real chains long before this.
			for (int depth = 0; depth < 128; depth++) {
				chain.add(lineageNode(w, cur));
				net.hedinger.prototype.engine.World.Birth b = w.birthOf(cur);
				if (b == null) {
					break; // a root: world-seeded, reseeded, or faded from the registry
				}
				if (b.parentB() >= 0) {
					chain.get(chain.size() - 1).put("with", b.parentB());
				}
				if (b.parentA() < 0) {
					break;
				}
				cur = b.parentA();
				if (depth == 126) {
					faded = true;
				}
			}
			java.util.List<java.util.Map<String, Object>> children =
					new java.util.ArrayList<java.util.Map<String, Object>>();
			for (net.hedinger.prototype.engine.World.Birth b : w.childrenOf(id)) {
				java.util.Map<String, Object> c = lineageNode(w, b.child());
				c.put("tick", b.tick());
				children.add(c);
			}
			// The chain's far end is a root only if a record proved it -- no record
			// (world-seeded) or a parentless one (a founder the warden landed); a
			// chain that ended because the registry no longer remembers says so.
			net.hedinger.prototype.engine.World.Birth last = chain.isEmpty() ? null
					: w.birthOf((Integer) chain.get(chain.size() - 1).get("id"));
			boolean rooted = !chain.isEmpty() && !faded && (last == null || last.parentA() < 0);
			return java.util.Map.of("id", id, "chain", chain, "children", children,
					"rooted", rooted, "tick", runner.snapshot().tick());
		}
	}

	/** One link of a lineage: identity, species (as born, if recorded; as it
	 *  lives, if not), generation, when it was born, and whether it still is. */
	private java.util.Map<String, Object> lineageNode(net.hedinger.prototype.engine.World w, int id) {
		java.util.Map<String, Object> n = new java.util.LinkedHashMap<String, Object>();
		n.put("id", id);
		net.hedinger.prototype.engine.World.Birth b = w.birthOf(id);
		String species = b != null ? b.species() : null;
		int generation = b != null ? b.generation() : -1;
		String status = "gone"; // no longer in the world at all
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e == null || e.getID() != id || e.isRemoved()) {
				continue;
			}
			// Presence is a fact about ANY entity — the first thing clicked in
			// testing was a corpse item, matched by nothing narrower and so
			// reported "gone" while visibly on screen. Species and generation
			// are creature facts and stay behind the NPC check.
			status = e.isDead() ? "dead" : "alive";
			if (e instanceof net.hedinger.prototype.entities.NPC tn) {
				if (species == null && tn.getGenome() != null) {
					species = net.hedinger.prototype.entities.Species.of(tn.getGenome()).key();
				}
				if (generation < 0) {
					generation = tn.generation();
				}
			}
			break;
		}
		n.put("species", species == null ? "unknown" : species);
		n.put("rgb", species == null ? 0x888888
				: net.hedinger.prototype.entities.Species.rgbOf(species));
		n.put("generation", generation);
		n.put("born", b != null ? b.tick() : -1);
		n.put("status", status);
		return n;
	}

	java.util.Map<String, Object> entityDetail(int id) {
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e == null || e.getID() != id || e.isRemoved()) {
				continue;
			}
			// The warden is filtered out of every snapshot (WorldSnapshot), so no
			// viewer can see or select it. Describing it here would be the one way
			// to reach it anyway, and it would arrive as a bare "entity" with no
			// kind — the same shape a spent sound used to arrive in.
			if (e instanceof net.hedinger.prototype.sim.WorldSteward) {
				continue;
			}
			java.util.Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
			d.put("id", id);
			d.put("x", e.getX());
			d.put("y", e.getY());
			d.put("z", e.getLvl()); // level, for the debug inspector
			d.put("dir", round(e.getDirection()));
			d.put("age", e.getAge());
			d.put("dead", e.isDead());
			// A corpse explains itself -- but only a body has one. Entity.think
			// records "old age" whenever a lifespan runs out, and the ephemera
			// have lifespans too: a spent sound is a dead entity that died of old
			// age, and the inspector reported exactly that, for a noise. Guarded
			// on NPC rather than on organic, because a wrecked machine's cause
			// (shot, culled) is worth reading and a machine is not organic.
			if (e.isDead() && e.getDeathCause() != null
					&& e instanceof net.hedinger.prototype.entities.NPC) {
				d.put("diedOf", e.getDeathCause());
			}
			d.put("flying", e.isFlying());
			if (!e.isDead()) {
				d.put("health", e.getHealth()); // a corpse has failed this by definition
			}
			d.put("attachedTo", e.getAttachTarget() == null ? -1 : e.getAttachTarget().getID());
			if (e instanceof net.hedinger.prototype.entities.Item it) {
				d.put("kind", "item." + it.getKind().name().toLowerCase());
				d.put("subtype", it.getKind().name().toLowerCase());
				d.put("edible", it.isEdible());
				d.put("durability", it.getHealth());
			} else if (e instanceof net.hedinger.prototype.entities.Door dr) {
				// Furniture is inspectable too: a tapped door says what it is
				// made of, how wide a mouth it seals, and where its leaves are.
				d.put("kind", "door." + dr.flavorName());
				d.put("subtype", dr.flavorName());
				d.put("span", dr.getSpan());
				d.put("state", dr.isOpen() ? "open" : dr.isClosed() ? "closed" : "moving");
			} else if (e instanceof net.hedinger.prototype.entities.Switch sw) {
				boolean button = sw.getMode() == net.hedinger.prototype.entities.Switch.BUTTON;
				d.put("kind", button ? "switch.button" : "switch.plate");
				d.put("subtype", button ? "button" : "plate");
				d.put("pressed", sw.isPressed());
				d.put("wiredTo", sw.getDoor().getID()); // the door this switch drives
			} else if (e instanceof net.hedinger.prototype.entities.Nest nest) {
				// A brood site: how many births this ring of twigs has hosted.
				d.put("kind", "nest");
				d.put("broods", nest.getBroods());
			} else if (e instanceof net.hedinger.prototype.engine.PheromoneCloud p) {
				d.put("kind", "phero");
				d.put("strength", round(p.getStrength()));
			} else if (e instanceof net.hedinger.prototype.entities.Sound snd) {
				// A sound is not selectable in the viewer -- it is an event, and
				// the picker skips it the way the renderer does. It gets a branch
				// anyway so that anything which does reach one reads it as what it
				// is: without this it fell through to the generic tail and came
				// back as a bare "entity", which is how a noise came to be
				// inspectable as a corpse.
				d.put("kind", "sound");
				d.put("subtype", switch (snd.getCode()) {
				case net.hedinger.prototype.entities.Sound.KILL -> "a death";
				case net.hedinger.prototype.entities.Sound.FIGHT -> "a fight";
				default -> "a noise";
				});
				d.put("earshot", round(snd.getRadius()));
				d.put("heard", e.isDead()); // broadcast once, then spent
			} else if (e instanceof net.hedinger.prototype.entities.NPC n) {
				d.put("kind", "npc." + n.getNpcTypeName().toLowerCase());
				d.put("subtype", n.getNpcTypeName().toLowerCase());
				// The four books (VITALS.md): the needs, the action budget, and the
				// life gate — each its own number, so the inspector shows why a
				// creature is doing what it is doing.
				//
				// Three of the four are physiology, and physiology belongs to living
				// organisms. The facility's machines keep none of them: they are
				// not metabolic, so their hunger and thirst never move off zero
				// and their energy never moves off it either. Sent anyway, the
				// inspector faithfully drew a drone as fully fed, fully watered
				// and completely out of energy — three bars that mean nothing,
				// one of which actively reads as a machine about to drop. The
				// client already omits any book it is not given, so the fix is
				// not to give it one.
				//
				// A corpse is the same case. Death is where the four books stop: the
				// glycogen and the gut evaporate with it — that is exactly what the
				// birth ledger audits, a parent eating its own young recovers the
				// meat and nothing else — so the numbers still sitting in the fields
				// describe a body that no longer keeps them. Shown, they read as a
				// carcass with a brimming store that is merely a little peckish. What
				// a corpse is worth is its own four numbers, below.
				if (n.isOrganic() && !n.isDead()) {
					d.put("glycogen", round(n.getGlycogen()));
					// The store this body's glycogen is a fraction OF. It is size-scaled,
					// so no constant on the viewer's side can stand in for it: the bar
					// divided by a flat 4, which pinned every large body at full from a
					// quarter store and drew a small body's brimming store as
					// three-quarters. Hunger and thirst arrive normalised and health is
					// a percentage; glycogen was the one book shown against a number that
					// was not its maximum.
					d.put("glycogenCap", round(n.glycogenCapacity()));
					d.put("hunger", round(n.getHunger()));
					d.put("thirst", round(n.getThirst()));
				}
				if (!n.isDead()) {
					d.put("health", n.getHealth()); // the life gate, and it has been passed
					// The body's store: fat as a share of what the body can carry, and
					// its mass, so a fed life reads as a fat body and a lean one as bones-to-be.
					d.put("fat", round(n.fatLeft()));
					d.put("fatMass", round(n.fat()));
				}
				// What this body weighs to an eater: the body it HAS, not the one its
				// genome describes. A juvenile is worth its juvenile mass, and the
				// genome's size is the adult it is still climbing toward.
				d.put("mass", round(n.leanMass()));
				// A carcass is a resource, and the inspector said nothing about it.
				// These four are the whole of what decides whether one is worth
				// walking to -- the same numbers the scavenger's own scan reads --
				// and none of them could be worked out from what was on the wire:
				// the genome carries the ADULT size, so even the mass was missing.
				if (n.isDead()) {
					// What is left of it, not what it weighed: the pools shrink as they
					// are eaten and rot, and the mass with them. The whole is sent too so
					// the panel can draw each pool as a share of the body it came from.
					d.put("mass", round(n.remainingMass()));
					d.put("wholeMass", round(n.carcassMass()));
					d.put("decay", round(n.decayProgress()));
					d.put("meat", round(n.meatLeft())); // edible to anyone, as a share of the body
					d.put("fresh", round(n.freshLeft())); // the hunter's share of it; decay waits on this
					d.put("decayed", round(n.decayedLeft())); // the scavenger's, rotting on the clock
					d.put("bones", round(n.carcassMass() <= 0 ? 0 : n.bones() / n.carcassMass()));
					d.put("worth", round(n.carrionWorth()));
					// Ticks of decay left once it starts; the clock holds while fresh meat
					// remains, so a fresh body reads its whole span here.
					d.put("rotsIn", Math.max(0, n.getDeathspan() + n.getAge()));
				}
				d.put("carrying", n.getCarriedLoad() > 0); // hauling an ITEM (a crate)
				d.put("grabbed", n.isGrabbed());
				// Who has hold of whom. `attachedTo` above says which entity this one
				// is attached to but not which way round it is, so on its own a viewer
				// cannot tell a passenger from a captive: `grabbed` decides that, and
				// this names the creature on the other end when this body is the one
				// doing the holding.
				if (n.getGrabbing() != null) {
					d.put("hauling", n.getGrabbing().getID());
				}
				// Growth: a juvenile is still climbing toward its genome's body, so a
				// creature that looks too small for its species is simply young.
				if (n.isJuvenile()) {
					d.put("juvenile", true);
					d.put("grown", Math.round(n.maturity() * 100) + "%");
				}
				if (n instanceof net.hedinger.prototype.simtest.TestNPC tn) {
					d.put("minded", tn.isMinded());
					d.put("generation", tn.generation()); // 0 = world-spawned, +1 per birth
					// Every creature reports where it sits in the food chain. This used
					// to be ecoRole(), which is blank for the minded cohort by design --
					// so the majority of the world's creatures showed no role at all,
					// which is the one thing you want to know when you tap one.
					d.put("role", tn.ecoRole());
					// The species label: derived from the markers at read time, never
					// stored, so it costs the simulation nothing and cannot drift out
					// of step with the genome it describes.
					if (tn.getGenome() != null) {
						d.put("species",
								net.hedinger.prototype.entities.Species.of(tn.getGenome()).name());
					}
					if (!tn.currentAction().isEmpty()) {
						d.put("action", tn.currentAction());
					}
				}
				net.hedinger.prototype.entities.Genome g = n.getGenome();
				if (g != null) {
					java.util.Map<String, Object> gm = new java.util.LinkedHashMap<String, Object>();
					gm.put("size", round(g.size));
					gm.put("speed", round(g.speed));
					gm.put("turnRate", g.turnRate);
					gm.put("losRange", round(g.losRange));
					gm.put("losFov", round(g.losFov));
					gm.put("metabolism", round(g.metabolism));
					gm.put("maxAge", g.maxAge);
					gm.put("markers", new double[] { round(g.markers[0]), round(g.markers[1]),
							g.markers.length > 2 ? round(g.markers[2]) : 0.0 });
					gm.put("flying", g.flying);
					gm.put("predatory", round(g.predatory));
					gm.put("xenophobia", round(g.xenophobia));
					gm.put("gregariousness", round(g.gregariousness));
					gm.put("boldness", round(g.boldness));
					gm.put("greed", round(g.greed));
					gm.put("determination", round(g.determination));
					gm.put("patience", g.patience);
					// Breeding. Seven genes were missing from this map — sexuality,
					// greed, determination, the two r/K fractions, the mutation rate and
					// instinct — not by decision but because each was added to the
					// genome and nobody came back here. A "genome" that is a subset of
					// the genome is the kind of wrong a reader cannot see: it looks
					// complete. Everything heritable now goes on the wire, and
					// GenomeDetailIsTheWholeGenome fails the moment a gene is added
					// without one.
					gm.put("mateThreshold", round(g.mateThreshold));
					gm.put("sexuality", round(g.sexuality));
					// The derived answer travels with the gene. Whether 0.5 is the line
					// between budding and courting is the genome's rule
					// (Genome.isSexual), and a viewer that re-implemented the threshold
					// would be a second copy of it, free to drift.
					gm.put("sexual", g.isSexual());
					gm.put("reproFraction", round(g.reproFraction));
					gm.put("reproCostFraction", round(g.reproCostFraction));
					gm.put("birthSize", round(g.birthSize));
					gm.put("drainRate", round(g.drainRate));
					gm.put("mutationRate", round(g.mutationRate));
					gm.put("instinct", round(g.instinct));
					// How this lineage looks for what it cannot see: the length of a
					// search leg and how far the heading swings at the end of one.
					gm.put("searchLeg", g.searchLeg);
					gm.put("searchCast", round(g.searchCast));
					// WHICH mind this lineage inherited, not whether it has the one the
					// viewer happened to know about. There are two substrates behind one
					// seam — an LGP program and an MLP network — and this said
					// `hasBrain: g.brain != null`, so a network-minded creature reported
					// no mind at all and the panel offered it no mind tab. Naming the
					// substrate leaves nowhere for a third to hide.
					boolean net = g.mlp != null;
					gm.put("mind", net ? "network" : g.brain != null ? "program" : "none");
					gm.put("mindSize", net ? g.mlp.size() : g.brain != null ? g.brain.length() : 0);
					d.put("genome", gm);
				}
			}
			return d;
		}
		return null;
	}

	/**
	 * Tile detail for the debug tile-inspector: type, fertility, and live
	 * vegetation ("food"), so grazing depletion and regrowth
	 * are observable by tapping a tile. Reads live fields without locking — a
	 * momentarily stale number is harmless and it mutates nothing.
	 */
	java.util.Map<String, Object> tileDetail(int x, int y, int z) {
		var w = runner.world();
		if (z < 0 || z >= w.getLevels() || x < 0 || y < 0 || x >= w.getColums() || y >= w.getRows()) {
			return null;
		}
		net.hedinger.prototype.engine.Tile t = w.getTile(x, y, z);
		long tick = runner.snapshot().tick();
		java.util.Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
		d.put("x", x);
		d.put("y", y);
		d.put("z", z);
		// The human-readable label ("grassland", "steel bulkhead"), not the enum
		// constant: tile types multiply, and "floor"-grade names stop telling
		// them apart in the inspector.
		d.put("type", t.getType().label());
		d.put("walkable", t.isWalkable());
		d.put("water", t.isWater());
		d.put("open", t.getType().isOpen());
		// A wall is impassable (not walkable, not flyable). Say so explicitly:
		// otherwise a wall reads only "blocks sight", with its solidity implied
		// solely by the absence of the other badges.
		d.put("solid", t.isSolid());
		// Mud is walkable but drags (speedFactor < 1). Surface that, otherwise a
		// bog is indistinguishable from plain floor in the inspector.
		d.put("slow", t.speedFactor() < 1.0);
		// Cover (thicket) is walkable and open yet still blocks the sightline, so
		// surface it explicitly — otherwise the inspector gives no hint why a
		// creature standing in it can't be seen.
		d.put("blocksSight", t.blocksSight());
		d.put("fertility", round(t.getFertility()));
		// What the crop is drawn as, for a tile that grows one. The viewer's
		// tile card names it, because a fern bed and a meadow feed a grazer the
		// same and nothing else about the card would say why they look different.
		if (t.growsVegetation()) {
			d.put("flora", t.getType() == Tile.TileType.TYPE_FUNGUS ? "fungus"
					: new String[] { "grass", "fern", "wildflowers", "heather", "moss" }[t.getFlora()]);
		}
		d.put("food", round(t.getVegetation(tick)));
		d.put("foodCap", round(t.vegetationCap()));
		return d;
	}

	/**
	 * The evolvable mind behind one creature, for the mind inspector: the LGP
	 * program disassembled, the live register bank and program counter, and the
	 * sensor/actuator vectors it read and wrote last tick (named). {@code hasBrain}
	 * is false for a hardcoded or brain-less creature. Read-only: it snapshots live
	 * fields without locking and mutates nothing.
	 */
	java.util.Map<String, Object> mindDetail(int id) {
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e == null || e.getID() != id || e.isRemoved()) {
				continue;
			}
			java.util.Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
			d.put("id", id);
			// Either substrate is a mind. This asked only for the LGP program, so a
			// network-minded creature answered "no brain" — the panel then called it
			// scripted, which it is not: it senses and acts every tick like any other
			// evolved body. What differs is only how much of the thinking can be
			// LISTED. The program disassembles instruction by instruction; the
			// network describes itself in a line (MlpBrain.describe, written for this
			// inspector and never wired to it). The live channels are the same for
			// both, and they are most of what the tab is for.
			net.hedinger.prototype.simtest.TestNPC tn =
					e instanceof net.hedinger.prototype.simtest.TestNPC t ? t : null;
			net.hedinger.prototype.entities.Genome g = tn == null ? null : tn.getGenome();
			net.hedinger.prototype.entities.LgpMind lm = tn == null ? null : tn.lgpMind();
			boolean program = lm != null && lm.brain() != null;
			boolean network = g != null && g.mlp != null;
			if (!program && !network) {
				d.put("hasMind", false);
				return d;
			}
			d.put("hasMind", true);
			d.put("substrate", network ? "network" : "program");
			d.put("generation", tn.generation()); // lineage depth: 0 = world-seeded
			if (program) {
				net.hedinger.prototype.entities.Brain b = lm.brain();
				d.put("length", b.length());
				d.put("stepsPerTick", lm.budget());
				d.put("pc", b.pc());
				d.put("disasm", b.disassemble(net.hedinger.prototype.entities.AgentIO.SENSOR_NAMES,
						net.hedinger.prototype.entities.AgentIO.ACT_NAMES));
				double[] reg = b.registers();
				double[] regs = new double[reg.length];
				for (int i = 0; i < reg.length; i++) {
					regs[i] = round(reg[i]);
				}
				d.put("registers", regs);
			} else {
				d.put("length", g.mlp.size());
				d.put("disasm", g.mlp.describe());
			}
			d.put("sensors", named(net.hedinger.prototype.entities.AgentIO.SENSOR_NAMES, tn.sensorSnapshot()));
			d.put("actuators", named(net.hedinger.prototype.entities.AgentIO.ACT_NAMES, tn.actuatorSnapshot()));
			return d;
		}
		return null;
	}

	/**
	 * Exports one creature's whole genome (brain included) as a portable, single-
	 * line {@link net.hedinger.prototype.entities.GenomeCodec} string — a savefile
	 * you can back up and later re-inject as a seed. Read-only. {@code hasBrain} is
	 * false for a scripted or brain-less creature (nothing to seed from).
	 */
	java.util.Map<String, Object> genomeExport(int id) {
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e == null || e.getID() != id || e.isRemoved()) {
				continue;
			}
			java.util.Map<String, Object> d = new java.util.LinkedHashMap<String, Object>();
			d.put("id", id);
			net.hedinger.prototype.entities.Genome g =
					e instanceof net.hedinger.prototype.entities.NPC n ? n.getGenome() : null;
			if (g == null || g.brain == null) {
				d.put("hasBrain", false);
				return d;
			}
			d.put("hasBrain", true);
			d.put("minded", e instanceof net.hedinger.prototype.simtest.TestNPC tn && tn.isMinded());
			d.put("genome", net.hedinger.prototype.entities.GenomeCodec.encode(g));
			return d;
		}
		return null;
	}

	/**
	 * Injects a creature built from an exported genome at {@code (x,y,z)}, through
	 * the tick-boundary command queue (so it is logged and replays exactly). The
	 * caller has already checked the command token. Returns the tick it will apply
	 * at, or -1 if the genome string is malformed (so a bad payload is a 400, not a
	 * crash).
	 */
	long injectGenome(String encodedGenome, double x, double y, double z) {
		net.hedinger.prototype.sim.SpawnMindedCommand cmd =
				net.hedinger.prototype.sim.SpawnMindedCommand.parse(encodedGenome, x, y, z);
		if (cmd == null) {
			return -1;
		}
		return runner.enqueue(cmd);
	}

	/** Queues any world-mutating command; returns the tick it will land on. */
	long enqueue(net.hedinger.prototype.sim.SimCommand cmd) {
		return runner.enqueue(cmd);
	}

	/** Pairs each value with its channel name, for the mind inspector's I/O lists. */
	private java.util.List<java.util.Map<String, Object>> named(String[] names, double[] vals) {
		java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
		for (int i = 0; i < vals.length; i++) {
			java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
			m.put("name", i < names.length ? names[i] : "[" + i + "]");
			m.put("value", round(vals[i]));
			out.add(m);
		}
		return out;
	}

	/**
	 * Per-tile grass level for the live vegetation overlay: one byte per tile of
	 * level z — 0..100 = how much vegetation stands there on an ABSOLUTE scale
	 * (100 = the richest ground a world can hold, 0 = grazed bare), or 255 for a
	 * tile that grows nothing (rock / water / dead dirt — no overlay). Lets the
	 * client show grazing depletion and regrowth on top of the static baked
	 * ground.
	 *
	 * <p>Absolute, not per-tile: measured against its own capacity, half-fertile
	 * ground read "100 = full" and grew the same lush sprite as the richest
	 * meadow, which made fertility invisible in the overlay exactly as it once
	 * was in the bake. Against the world's maximum, a tile's ceiling IS its
	 * fertility — 0.5 fertility tops out mid-stage and never reaches the tall
	 * tufts — so the sprites agree with the sward baked underneath them.
	 */
	/** Quantised 5-state vegetation with deltas (see {@link VegFeed}) — what
	 *  the web client polls; the raw byte grid below stays for tooling. */
	private final java.util.concurrent.ConcurrentHashMap<Integer, VegFeed> vegFeeds =
			new java.util.concurrent.ConcurrentHashMap<>();

	java.util.Map<String, Object> vegetationSince(int z, long since) {
		byte[] raw = vegetation(z);
		if (raw == null) {
			return null;
		}
		var w = runner.world();
		VegFeed f = vegFeeds.computeIfAbsent(z, k -> new VegFeed());
		return f.respond(raw, vegKinds(z), since, System.currentTimeMillis(),
				w.getColums(), w.getRows());
	}

	/**
	 * What is standing on a tile, on the 0..100 scale the viewer quantises into
	 * sprite stages — or 255 where nothing stands at all.
	 *
	 * <p>For anything that grows, that is its vegetation against the world's
	 * maximum ({@link #grassLevel}). A cactus grows nothing: it is a fixture, not
	 * a crop, and no grazer can eat it. What it has instead is an AGE, and the
	 * sprite layer draws it at the moment of its life that age puts it in — so the
	 * age rides the same channel, because that channel's real subject is what a
	 * tile looks like right now, and the kind bits already say which of the two a
	 * reader is holding.
	 *
	 * <p>The age comes from {@link net.hedinger.prototype.engine.GroundTextures#cactusMaturity},
	 * which is where it lives so nothing computes a second copy of it. It is a
	 * function of position today; when vegetation grows, this is the line that
	 * starts reading a number the world keeps, and the viewer needs no change at
	 * all — which is the whole reason the plant moved off the static bake.
	 */
	static int levelOf(Tile t, int x, int y, long tick) {
		if (t != null && t.getType() == Tile.TileType.TYPE_CACTUS) {
			double age = net.hedinger.prototype.engine.GroundTextures
					.cactusMaturity(x + 0.5, y + 0.5);
			// Straight to a rung, then back out to the level that rung round-trips
			// from. Sending the age as a raw 0..100 put every cactus in the world on
			// rungs 2, 3 and 4: {@link VegFeed#stateOf} is shaped for a CROP, where
			// the ends mean stripped and fully grown, and it reaches them only at
			// the extremes of its input -- so an age that merely spans most of its
			// range comes out with both ends of the ladder shaved off. The mushroom
			// sprite lost its top two stages to exactly this and nobody noticed for
			// as long as it existed, so it is worth saying plainly: a quantiser
			// built for one quantity silently rescales another.
			int rung = (int) (Math.max(0, Math.min(0.999, age)) * VegFeed.STAGES);
			return rung * (100 / (VegFeed.STAGES - 1));
		}
		return grassLevel(t, tick);
	}

	/** One tile's grass level on the absolute scale (see {@link #vegetation}):
	 *  255 where nothing ever grows, else 0..100 of the world's maximum. */
	static int grassLevel(Tile t, long tick) {
		if (!t.growsVegetation() || t.vegetationCap() <= 1e-9) {
			return 255; // grows nothing: no overlay
		}
		// Against the world's maximum, so the tile's fertility is its ceiling:
		// poor ground can never report a full meadow.
		long frac = Math.round(t.getVegetation(tick) / Tile.VEG_MAX * 100);
		return (int) Math.max(0, Math.min(100, frac));
	}

	/**
	 * Which tiles of level {@code z} grow fungus rather than grass: 1 per fungus
	 * tile, 0 elsewhere. Static for the life of the world, so the client is told
	 * once in the full grid and never again.
	 *
	 * <p>Without this the viewer had no way to know, and drew grass everywhere —
	 * so the cave's fungus beds came up as meadow and the mushroom sprite, which
	 * has always been baked, was never once put on screen.
	 */
	byte[] vegKinds(int z) {
		var w = runner.world();
		if (z < 0 || z >= w.getLevels()) {
			return null;
		}
		int cols = w.getColums(), rows = w.getRows();
		byte[] k = new byte[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				Tile t = w.getTile(x, y, z);
				// A bed and a cactus are tile types; everything else that grows
				// says which plant it is on its own flora byte, and the wire index
				// IS that byte, shifted above the stage.
				k[y * cols + x] = (byte) (t == null ? 0
						: t.getType() == Tile.TileType.TYPE_FUNGUS ? VegFeed.KIND_FUNGUS
						: t.getType() == Tile.TileType.TYPE_CACTUS ? VegFeed.KIND_CACTUS
						: t.getFlora() << VegFeed.KIND_SHIFT);
			}
		}
		return k;
	}

	/** How many levels the world has, so a test can sweep all of them rather
	 *  than the two it happened to be written against. */
	int levelsForTest() {
		return runner.world().getLevels();
	}

	byte[] vegetation(int z) {
		var w = runner.world();
		if (z < 0 || z >= w.getLevels()) {
			return null;
		}
		long tick = runner.snapshot().tick();
		int cols = w.getColums(), rows = w.getRows();
		byte[] v = new byte[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				v[y * cols + x] = (byte) levelOf(w.getTile(x, y, z), x, y, tick);
			}
		}
		return v;
	}

	/**
	 * Per-tile cover mask for the concealment overlay: one byte per tile of
	 * level z — 1 where the tile is a {@code TYPE_COVER} thicket (drawn as a
	 * canopy veil), 2 where it is an enclosed crawl duct (drawn as a metal
	 * lid — a duct must not sprout shrubbery), 3 where it is a {@code
	 * TYPE_REEDS} bed (redrawn stalk-exact, so a body shows between the
	 * stalks), else 0. The values match the web client's veil kinds in
	 * render.ts. Static for the life of the world, so the client fetches it
	 * once per level and draws the overlay over entities standing in it —
	 * anything the sim hides, the viewer part-hides too.
	 */
	byte[] cover(int z) {
		var w = runner.world();
		if (z < 0 || z >= w.getLevels()) {
			return null;
		}
		int cols = w.getColums(), rows = w.getRows();
		byte[] c = new byte[cols * rows];
		for (int y = 0; y < rows; y++) {
			for (int x = 0; x < cols; x++) {
				var t = w.getTile(x, y, z);
				byte v = 0;
				if (t.blocksSight() && !t.isSolid()) {
					var type = t.getType();
					// Which veil the client stamps. Each cover has its own rule
					// and they are all different — 1 thicket (clustered blocks
					// at half coverage), 2 duct (slot-exact), 3 reeds
					// (stalk-exact), 4 tall grass (the lit tips only), 5 scrub
					// (the wood only) — so the kind has to travel, not just the
					// fact that the tile hides something.
					v = (byte) (type == net.hedinger.prototype.engine.Tile.TileType.TYPE_DUCT ? 2
							: type == net.hedinger.prototype.engine.Tile.TileType.TYPE_REEDS ? 3
							: type == net.hedinger.prototype.engine.Tile.TileType.TYPE_TALLGRASS ? 4
							: type == net.hedinger.prototype.engine.Tile.TileType.TYPE_SCRUB ? 5 : 1);
				}
				c[y * cols + x] = v;
			}
		}
		return c;
	}

	/** The standing intent's status as a word, for the mind inspector. */
	private static String intentLabel(double v) {
		if (v == net.hedinger.prototype.entities.AgentIO.INTENT_DONE) {
			return "done";
		}
		if (v == net.hedinger.prototype.entities.AgentIO.INTENT_PENDING) {
			return "pending";
		}
		if (v == net.hedinger.prototype.entities.AgentIO.INTENT_INVALID) {
			return "invalid";
		}
		return "idle";
	}

	private static double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	SimulationRunner runner() {
		return runner;
	}

	// ---- metrics + recording ----------------------------------------------

	/** Visitor counts for this uptime; addresses are hashed and never stored. */
	final VisitorLog visitors = new VisitorLog();

	// ---- population history ------------------------------------------------

	/**
	 * How often the headcount is sampled, in seconds, and how many samples are
	 * kept. Twelve an hour for a little over eight hours: long enough to watch a
	 * scavenger bloom eat its larder and starve back, short enough that the whole
	 * series is a few kilobytes on the wire and a rounding error in a 512 MB heap.
	 */
	static final int POP_SAMPLE_SEC = 5;
	static final int POP_SAMPLES = 6000;

	/** One reading of the world's trophic makeup. */
	record PopSample(long tick, int herbivore, int predator, int scavenger, int parasite) { }

	// Written only by the sampler task, read by request threads: an immutable
	// list swapped in wholesale, so a reader either sees the old series or the
	// new one and never a half-built ArrayDeque.
	private volatile java.util.List<PopSample> popHistory = java.util.List.of();

	/**
	 * Takes one reading of the headcount by role.
	 *
	 * <p>Under the runner's lock, because this walks live entities while the sim
	 * thread is free to add and remove them. The walk is over a few hundred bodies
	 * and runs once every {@link #POP_SAMPLE_SEC} seconds, so the pause is far
	 * below a tick and the sim never notices — worth paying to keep the series
	 * exact rather than racily off by one.
	 */
	private void samplePopulation() {
		PopSample s;
		LinSample l;
		// Both censuses under one lock: two beats would walk the entities twice
		// and could disagree about a body born between them, and the two graphs
		// are two lenses on the same population — they should never contradict.
		synchronized (runner) {
			long tick = runner.snapshot().tick();
			s = censusOf(runner.world(), tick);
			l = lineageOf(runner.world(), tick);
		}
		append(s);
		appendLineage(l);
	}

	/**
	 * Counts the living by trophic role. Pure — a world in, a reading out — so
	 * what the graph is actually measuring can be tested without standing up a
	 * server, a socket or a layer bake.
	 */
	static PopSample censusOf(net.hedinger.prototype.engine.World w, long tick) {
		int herb = 0, pred = 0, scav = 0, para = 0;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (!(e instanceof net.hedinger.prototype.simtest.TestNPC tn)
					|| tn.isDead() || tn.isRemoved()) {
				continue; // corpses are not population; nor are items or clouds
			}
			// One case per clade, and roleless bodies counted as nothing. This used
			// to fall through to `default: prey++`, which quietly enrolled anything
			// without a role into the herbivore series -- harmless while the only
			// roleless bodies were test fixtures, and a silent lie the moment
			// anything else grew one.
			switch (tn.ecoRole()) {
			case "herbivore":
				herb++;
				break;
			case "predator":
				pred++;
				break;
			case "scavenger":
				scav++;
				break;
			case "parasite":
				para++;
				break;
			default:
				break; // outside the ecosystem: not population
			}
		}
		return new PopSample(tick, herb, pred, scav, para);
	}

	// ---- lineage history ---------------------------------------------------
	//
	// The same idea one level finer: not "how many herbivores" but "how many of
	// each SPECIES" — the emergent labels Species derives from marker space.
	// This is the series that shows a lineage's whole story: the line sinking
	// to zero is a die-out, and a fresh line rising where one just ended is the
	// steward reseeding the niche with a new random lineage. The role graph
	// cannot show either; a reseed leaves its herbivore count almost unmoved.

	/**
	 * One reading of who exists, by species. Keys sorted, and parallel to
	 * counts — a compact pair rather than a map, because six thousand of these
	 * sit in the ring.
	 */
	record LinSample(long tick, String[] keys, int[] counts) { }

	private volatile java.util.List<LinSample> linHistory = java.util.List.of();

	/**
	 * Counts the living by species label. Pure, like {@link #censusOf}, and the
	 * same population: every genomed body the role census counts falls in some
	 * species, so the two series always sum to the same world.
	 */
	static LinSample lineageOf(net.hedinger.prototype.engine.World w, long tick) {
		java.util.TreeMap<String, int[]> tally = new java.util.TreeMap<String, int[]>();
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (!(e instanceof net.hedinger.prototype.simtest.TestNPC tn)
					|| tn.isDead() || tn.isRemoved() || tn.getGenome() == null) {
				continue; // species is a genome label: a body without one has none
			}
			String key = net.hedinger.prototype.entities.Species.of(tn.getGenome()).key();
			int[] n = tally.get(key);
			if (n == null) {
				tally.put(key, n = new int[1]);
			}
			n[0]++;
		}
		String[] keys = new String[tally.size()];
		int[] counts = new int[tally.size()];
		int i = 0;
		for (java.util.Map.Entry<String, int[]> en : tally.entrySet()) {
			keys[i] = en.getKey();
			counts[i] = en.getValue()[0];
			i++;
		}
		return new LinSample(tick, keys, counts);
	}

	private void appendLineage(LinSample sample) {
		java.util.List<LinSample> prev = linHistory;
		java.util.ArrayList<LinSample> next =
				new java.util.ArrayList<LinSample>(Math.min(prev.size() + 1, POP_SAMPLES));
		int drop = Math.max(0, prev.size() + 1 - POP_SAMPLES);
		next.addAll(prev.subList(drop, prev.size()));
		next.add(sample);
		linHistory = java.util.List.copyOf(next);
	}

	/**
	 * The lineage series for {@code /api/lineage}: one column per species that
	 * appears ANYWHERE in the ring, zero-filled where it is absent. The zeros
	 * are the point — a species that died out three hours ago keeps its column,
	 * flat on the floor, because "the ochre herbivores are gone" is exactly the
	 * reading this graph exists to give. Each column carries the species' own
	 * centroid tint so every client colours a lineage the same way.
	 */
	java.util.Map<String, Object> lineage() {
		java.util.List<LinSample> h = linHistory;
		long[] ticks = new long[h.size()];
		java.util.TreeMap<String, int[]> series = new java.util.TreeMap<String, int[]>();
		for (int i = 0; i < h.size(); i++) {
			LinSample s = h.get(i);
			ticks[i] = s.tick();
			for (int k = 0; k < s.keys().length; k++) {
				int[] col = series.get(s.keys()[k]);
				if (col == null) {
					series.put(s.keys()[k], col = new int[h.size()]);
				}
				col[i] = s.counts()[k];
			}
		}
		java.util.List<java.util.Map<String, Object>> out =
				new java.util.ArrayList<java.util.Map<String, Object>>(series.size());
		for (java.util.Map.Entry<String, int[]> en : series.entrySet()) {
			String key = en.getKey();
			int slash = key.indexOf('/');
			// The tint is the species centroid's, resolved from the key's name so
			// the label and the swatch can never disagree about who is who.
			out.add(java.util.Map.of("key", key,
					"clade", slash < 0 ? key : key.substring(0, slash),
					"name", slash < 0 ? key : key.substring(slash + 1),
					"rgb", net.hedinger.prototype.entities.Species.rgbOf(key),
					"counts", en.getValue()));
		}
		return java.util.Map.of("sampleSec", POP_SAMPLE_SEC, "tps",
				SimulationRunner.TICKS_PER_SECOND, "tick", ticks, "species", out);
	}

	// ---- lineage flows (the Sankey) ----------------------------------------
	//
	// The counts ring says how many; it cannot say where they WENT. A Sankey
	// needs flows — this species' heads continuing as themselves, drifting
	// across a label boundary into another species, dying, or arriving newborn
	// — and a flow is a fact about individuals, not about totals: two counts
	// can stay equal while every body underneath is replaced. So the stage ring
	// snapshots WHO exists (entity id -> species), and flows are exact diffs
	// between the two snapshots actually drawn, never sums of intermediate
	// steps.

	/** Seconds between stage snapshots, and how many the ring keeps: a stage
	 *  every five minutes for a little over eight hours — the Sankey is the
	 *  census's long-exposure photograph, not its live feed. */
	static final int STAGE_SEC = 300;
	static final int STAGE_KEEP = 100;

	/** Who existed, at one instant: ids sorted ascending, species parallel. */
	record StageSnap(long tick, int[] ids, String[] species) { }

	private volatile java.util.List<StageSnap> stageHistory = java.util.List.of();

	/** Pure, like {@link #censusOf}: the living genomed bodies, by id. */
	static StageSnap stageOf(net.hedinger.prototype.engine.World w, long tick) {
		java.util.TreeMap<Integer, String> m = new java.util.TreeMap<Integer, String>();
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.simtest.TestNPC tn
					&& !tn.isDead() && !tn.isRemoved() && tn.getGenome() != null) {
				m.put(e.getID(), net.hedinger.prototype.entities.Species.of(tn.getGenome()).key());
			}
		}
		int[] ids = new int[m.size()];
		String[] sp = new String[m.size()];
		int i = 0;
		for (java.util.Map.Entry<Integer, String> en : m.entrySet()) {
			ids[i] = en.getKey();
			sp[i] = en.getValue();
			i++;
		}
		return new StageSnap(tick, ids, sp);
	}

	private void sampleStage() {
		StageSnap s;
		synchronized (runner) {
			s = stageOf(runner.world(), runner.snapshot().tick());
		}
		java.util.List<StageSnap> prev = stageHistory;
		java.util.ArrayList<StageSnap> next =
				new java.util.ArrayList<StageSnap>(Math.min(prev.size() + 1, STAGE_KEEP));
		int drop = Math.max(0, prev.size() + 1 - STAGE_KEEP);
		next.addAll(prev.subList(drop, prev.size()));
		next.add(s);
		stageHistory = java.util.List.copyOf(next);
	}

	/** The kinds of passage a head can make between two stages. */
	static final String HELD = "held", BRED = "bred", RESEED = "reseed", UNKNOWN = "unknown",
			DIED = "died";

	/** One passage: how, from which species (or the pseudo-source {@code reseed}
	 *  / {@code unknown}), to which (or {@code died}). */
	record FlowKey(String kind, String from, String to) implements Comparable<FlowKey> {
		@Override
		public int compareTo(FlowKey o) {
			int c = from.compareTo(o.from);
			if (c == 0) {
				c = to.compareTo(o.to);
			}
			return c == 0 ? kind.compareTo(o.kind) : c;
		}
	}

	/**
	 * Exact flows between two stages, by DESCENT rather than by id alone.
	 *
	 * <p>The first version followed ids: present in both was a continuation,
	 * only in the second was born, only in the first died. On the live world
	 * that drew nothing but births and deaths -- drawn stages sit three quarters
	 * of an hour apart and a body rarely lives that long -- so every column was
	 * an island and the one question the diagram exists to answer, whether a
	 * line is breeding on or the warden keeps putting it back, was invisible.
	 *
	 * <p>So every head in the later stage is traced to ONE source. Still alive
	 * from the earlier stage: {@code held}. Otherwise its primary line
	 * (parentA, as the inspector walks it) is climbed until an ancestor that
	 * stood in the earlier stage: {@code bred}, from that ancestor's species
	 * then -- a child, a grandchild through a parent that came and went between
	 * the two, it makes no difference. A line that climbs to a parentless
	 * record is a founder the warden landed since: {@code reseed}. A line the
	 * registry cannot follow is {@code unknown}, said rather than guessed. And
	 * a head of the earlier stage that is neither held nor anyone's ancestor at
	 * the later one {@code died}: its line ended, which is what a death means
	 * here -- a body that died leaving descendants is carried by their ribbon.
	 *
	 * <p>Inflows still sum to the later count exactly. Outflows do not sum to
	 * the earlier one: a bred ribbon is as wide as the descendants, not the
	 * ancestors, which is the reading wanted, and the diagram's widths were
	 * never additive anyway.
	 */
	static java.util.Map<FlowKey, Integer> flowsBetween(StageSnap a, StageSnap b,
			java.util.function.IntFunction<net.hedinger.prototype.engine.World.Birth> birthOf) {
		java.util.HashMap<Integer, String> was = new java.util.HashMap<Integer, String>();
		for (int i = 0; i < a.ids().length; i++) {
			was.put(a.ids()[i], a.species()[i]);
		}
		java.util.HashSet<Integer> still = new java.util.HashSet<Integer>();
		java.util.HashSet<Integer> ancestors = new java.util.HashSet<Integer>();
		java.util.TreeMap<FlowKey, Integer> f = new java.util.TreeMap<FlowKey, Integer>();
		for (int j = 0; j < b.ids().length; j++) {
			int id = b.ids()[j];
			String to = b.species()[j];
			still.add(id);
			String at = was.get(id);
			if (at != null) {
				f.merge(new FlowKey(HELD, at, to), 1, Integer::sum);
				continue;
			}
			FlowKey k = null;
			int cur = id;
			for (int hop = 0; hop < 4096 && k == null; hop++) {
				net.hedinger.prototype.engine.World.Birth rec = birthOf.apply(cur);
				if (rec == null) {
					k = new FlowKey(UNKNOWN, UNKNOWN, to);
				} else if (rec.parentA() < 0) {
					k = new FlowKey(RESEED, RESEED, to);
				} else if (was.containsKey(rec.parentA())) {
					k = new FlowKey(BRED, was.get(rec.parentA()), to);
					ancestors.add(rec.parentA());
				} else {
					cur = rec.parentA();
				}
			}
			f.merge(k == null ? new FlowKey(UNKNOWN, UNKNOWN, to) : k, 1, Integer::sum);
		}
		for (int i = 0; i < a.ids().length; i++) {
			int id = a.ids()[i];
			if (!still.contains(id) && !ancestors.contains(id)) {
				f.merge(new FlowKey(DIED, a.species()[i], DIED), 1, Integer::sum);
			}
		}
		return f;
	}

	/**
	 * The Sankey series for {@code /api/lineage/flows}: up to {@code MAX_COLS}
	 * stages chosen evenly across the ring (the first and the latest always
	 * among them), each with its species headcounts, plus the exact flows
	 * between each drawn pair. Diffed between the CHOSEN stages, so drawing
	 * fewer columns never invents or loses a head.
	 */
	java.util.Map<String, Object> lineageFlows() {
		final int maxCols = 12;
		java.util.List<StageSnap> h = stageHistory;
		java.util.List<StageSnap> cols = new java.util.ArrayList<StageSnap>();
		int n = h.size();
		if (n <= maxCols) {
			cols.addAll(h);
		} else {
			for (int c = 0; c < maxCols; c++) {
				cols.add(h.get(Math.round(c * (n - 1) / (float) (maxCols - 1))));
			}
		}
		java.util.List<java.util.Map<String, Object>> stages =
				new java.util.ArrayList<java.util.Map<String, Object>>();
		for (StageSnap s : cols) {
			java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<String, Integer>();
			for (String sp : s.species()) {
				counts.merge(sp, 1, Integer::sum);
			}
			java.util.List<java.util.Map<String, Object>> species =
					new java.util.ArrayList<java.util.Map<String, Object>>();
			for (java.util.Map.Entry<String, Integer> en : counts.entrySet()) {
				species.add(java.util.Map.of("key", en.getKey(),
						"rgb", net.hedinger.prototype.entities.Species.rgbOf(en.getKey()),
						"count", en.getValue()));
			}
			stages.add(java.util.Map.of("tick", s.tick(), "species", species));
		}
		java.util.List<java.util.Map<String, Object>> flows =
				new java.util.ArrayList<java.util.Map<String, Object>>();
		var w = runner.world();
		for (int c = 0; c + 1 < cols.size(); c++) {
			for (java.util.Map.Entry<FlowKey, Integer> en
					: flowsBetween(cols.get(c), cols.get(c + 1), w::birthOf).entrySet()) {
				flows.add(java.util.Map.of("stage", c, "kind", en.getKey().kind(),
						"from", en.getKey().from(), "to", en.getKey().to(),
						"n", en.getValue()));
			}
		}
		return java.util.Map.of("tps", SimulationRunner.TICKS_PER_SECOND,
				"stageSec", STAGE_SEC, "stages", stages, "flows", flows);
	}

	/** Adds a reading, dropping the oldest once the ring is full. */
	private void append(PopSample sample) {
		java.util.List<PopSample> prev = popHistory;
		java.util.ArrayList<PopSample> next =
				new java.util.ArrayList<PopSample>(Math.min(prev.size() + 1, POP_SAMPLES));
		int drop = Math.max(0, prev.size() + 1 - POP_SAMPLES);
		next.addAll(prev.subList(drop, prev.size()));
		next.add(sample);
		popHistory = java.util.List.copyOf(next);
	}

	/**
	 * The population series for {@code /api/population}. Parallel arrays rather
	 * than a list of objects: the client plots four columns and this is a third of
	 * the bytes with none of the key repetition.
	 */
	java.util.Map<String, Object> population() {
		java.util.List<PopSample> h = popHistory;
		long[] ticks = new long[h.size()];
		int[] herb = new int[h.size()], pred = new int[h.size()], scav = new int[h.size()];
		int[] para = new int[h.size()];
		for (int i = 0; i < h.size(); i++) {
			PopSample p = h.get(i);
			ticks[i] = p.tick();
			herb[i] = p.herbivore();
			pred[i] = p.predator();
			scav[i] = p.scavenger();
			para[i] = p.parasite();
		}
		return java.util.Map.of("sampleSec", POP_SAMPLE_SEC, "tps",
				SimulationRunner.TICKS_PER_SECOND, "tick", ticks, "herbivore", herb,
				"predator", pred, "scavenger", scav, "parasite", para,
				"bounds", cladeBounds());
	}

	/**
	 * The steward's guardrails per clade, {@code {clade: {floor, ceiling}}} —
	 * the floor it restores a failing clade from and the ceiling it culls back
	 * to.
	 *
	 * <p>On the wire because the chart cannot derive them. A population line
	 * sitting flat says nothing about whether it is resting at a comfortable
	 * level or pinned against a cull, and a line climbing out of a trough looks
	 * the same whether it bred back or was reseeded — the guardrails are what
	 * separates those readings, and they live in {@link WorldSteward}, which
	 * scales them per world size.
	 *
	 * <p>Read off the warden itself rather than restated here, for the reason
	 * the scenario suite reads them that way too: a second copy of a bound is
	 * free to drift from the one actually being enforced, and a chart drawing
	 * the wrong line is worse than one drawing none. Empty for a world with no
	 * steward in it, which is every staged test world — the client omits what
	 * it is not given.
	 */
	java.util.Map<String, Object> cladeBounds() {
		var out = new java.util.LinkedHashMap<String, Object>();
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (!(e instanceof net.hedinger.prototype.sim.WorldSteward st)) {
				continue;
			}
			for (net.hedinger.prototype.entities.Genome.Clade c
					: net.hedinger.prototype.entities.Genome.Clade.values()) {
				out.put(c.wireName(), java.util.Map.of(
						"floor", st.floor(c), "ceiling", st.ceiling(c)));
			}
			break; // one warden to a world
		}
		return out;
	}

	/** Operational snapshot for {@code /api/metrics}: sim cost, size, viewers. */
	java.util.Map<String, Object> metrics() {
		Runtime rt = Runtime.getRuntime();
		long tick = runner.snapshot().tick();
		return new java.util.LinkedHashMap<String, Object>(java.util.Map.ofEntries(
				java.util.Map.entry("seed", seed),
				java.util.Map.entry("tick", tick),
				java.util.Map.entry("tickMs", Math.round(runner.avgTickMillis() * 1000) / 1000.0),
				java.util.Map.entry("targetTps", SimulationRunner.TICKS_PER_SECOND),
				java.util.Map.entry("entities", runner.snapshot().entities().size()),
				java.util.Map.entry("commands", runner.commandLog().size()),
				java.util.Map.entry("viewers", viewers()),
				java.util.Map.entry("paused", runner.isPaused()),
				java.util.Map.entry("speed", runner.getSpeed()),
				java.util.Map.entry("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000),
				java.util.Map.entry("heapMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)),
				// Who has been here, in the only sense worth keeping: how many and how
				// often. Addresses are hashed against a per-boot salt, so these count
				// visitors without being able to name one. `visitorsCapped` says whether
				// the distinct figure is exact or a floor -- see VisitorLog.
				java.util.Map.entry("visitors", visitors.distinct()),
				java.util.Map.entry("visitorsCapped", visitors.saturated()),
				java.util.Map.entry("httpRequests", visitors.requests())));
	}

	/** The current session as a downloadable, replayable recording. */
	net.hedinger.prototype.sim.Recording recording() {
		return net.hedinger.prototype.sim.Recording.of(seed, runner);
	}

	/**
	 * Reconstructs a recording to a given tick without disturbing the live world.
	 * The engine RNG is a single global, so this runs under the runner's lock
	 * (the live tick can't draw concurrently) with the live generator captured
	 * and restored around it — the reconstruction reseeds and draws freely on an
	 * isolated stream, then the live world resumes exactly where it was. Cheap
	 * enough to hold the lock for (sub-millisecond ticks), so the pause is
	 * imperceptible.
	 */
	net.hedinger.prototype.sim.WorldSnapshot replay(net.hedinger.prototype.sim.Recording rec, long tick) {
		synchronized (runner) {
			Object savedRng = net.hedinger.prototype.engine.Utils.captureRng();
			try {
				return net.hedinger.prototype.sim.Replays.reconstruct(rec, tick);
			} finally {
				net.hedinger.prototype.engine.Utils.restoreRng(savedRng);
			}
		}
	}

	/** Writes the recording to the durable dir (no-op if RECORD_DIR is unset or
	 *  the session has no commands worth persisting). */
	private void dumpRecording() {
		try {
			if (recordDir == null || runner.commandLog().size() == 0) {
				return;
			}
			byte[] json = Protocol.JSON.writeValueAsBytes(recording());
			java.nio.file.Files.write(new java.io.File(recordDir, "session.json").toPath(), json);
		} catch (Exception e) {
			System.err.println("recording dump: " + e);
		}
	}

	long seed() {
		return seed;
	}

	/** A baked ground chunk PNG at (level z, chunk cx, cy), or null if none. */
	byte[] chunk(int z, int cx, int cy) {
		return chunks.get(z + "/" + cx + "_" + cy);
	}

	int viewers() {
		return sessions.size();
	}
}
