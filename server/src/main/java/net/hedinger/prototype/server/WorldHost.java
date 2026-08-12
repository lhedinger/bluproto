package net.hedinger.prototype.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.javalin.websocket.WsContext;

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
	private java.util.Map<String, byte[]> bareChunks; // same chunks, fully grazed
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

	WorldHost(long seed) {
		this(seed, 0, 0);
	}

	/** Size-overridable ctor: {@code cols}/{@code rows} &gt; 0 build the world at
	 *  that size (so the deployed size can be tuned — or scaled down under a tight
	 *  heap — from config without a rebuild); otherwise the built-in default. */
	WorldHost(long seed, int cols, int rows) {
		this.worldCols = cols;
		this.worldRows = rows;
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
	}

	private void buildWorld(long newSeed) {
		seed = newSeed;
		startedAt = System.currentTimeMillis();
		boolean sized = worldCols > 0 && worldRows > 0;
		SimulationRunner r = new SimulationRunner(
				sized ? Worlds.demo(newSeed, worldCols, worldRows) : Worlds.demo(newSeed));
		var terrain = sized ? Worlds.demoTerrain(newSeed, worldCols, worldRows)
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
		// Phase 4 of the render split (MODERNIZATION.md): bake the same twin a
		// second time with every tile grazed bare. The client dithers between
		// the two bakes per art-pixel, so live depletion erases grass (and
		// fungus glow) down to the ground's true bare look — the Java renderer
		// stays the single source of the art, served, never ported.
		for (int z = 0; z < terrain.getLevels(); z++) {
			for (int y = 0; y < rows; y++) {
				for (int x = 0; x < cols; x++) {
					terrain.getTile(x, y, z).graze(0, net.hedinger.prototype.engine.Tile.VEG_MAX);
				}
			}
		}
		java.util.Map<String, byte[]> bareBaked = new java.util.HashMap<String, byte[]>();
		for (int z = 0; z < r.world().getLevels(); z++) {
			java.awt.image.BufferedImage level = LayerBaker.bakeLevelImage(terrain, lr, z);
			for (int cy = 0; cy < cyN; cy++) {
				for (int cx = 0; cx < cxN; cx++) {
					int x0 = cx * CHUNK_TILES * ts, y0 = cy * CHUNK_TILES * ts;
					int cw = Math.min(CHUNK_TILES * ts, cols * ts - x0);
					int ch = Math.min(CHUNK_TILES * ts, rows * ts - y0);
					bareBaked.put(z + "/" + cx + "_" + cy, LayerBaker.chunkPng(level, x0, y0, cw, ch));
				}
			}
			level = null; // free this level's image before baking the next
		}
		runner = r;
		chunks = baked;
		bareChunks = bareBaked;
		chunksX = cxN;
		chunksY = cyN;
		lastSent = r.snapshot();
		r.start();
	}

	// ---- viewer lifecycle --------------------------------------------------

	/** New viewer: greet with world geometry, then the complete entity list. */
	void onConnect(WsContext ctx) {
		ctx.enableAutomaticPings(15, TimeUnit.SECONDS); // keep proxies from idling us out
		synchronized (lock) {
			WorldSnapshot s = runner.snapshot();
			var w = runner.world();
			ctx.send(Protocol.write(Protocol.Hello.of(seed, w.getColums(), w.getRows(), w.getLevels(),
					net.hedinger.prototype.engine.ResourceManager.tileSize,
					s.tick(), runner.isPaused(), runner.getSpeed(),
					java.util.List.of(), CHUNK_TILES, String.valueOf(startedAt))));
			ctx.send(Protocol.write(Protocol.Full.of(s.tick(), s.entities())));
			sessions.add(ctx);
		}
	}

	void onClose(WsContext ctx) {
		sessions.remove(ctx);
	}

	/** ~10 Hz: send what changed since the last broadcast to every viewer. */
	private void broadcast() {
		try {
			String msg;
			synchronized (lock) {
				WorldSnapshot now = runner.snapshot();
				if (now.tick() == lastSent.tick() && !forceFull) {
					return; // paused (or stalled): nothing new to say
				}
				if (forceFull) {
					msg = Protocol.write(Protocol.Full.of(now.tick(), now.entities()));
					forceFull = false;
				} else {
					DeltaEncoder.Delta d = DeltaEncoder.diff(lastSent, now);
					msg = Protocol.write(Protocol.Delta.of(now.tick(), d.upsert(), d.gone()));
				}
				lastSent = now;
			}
			for (WsContext ctx : sessions) {
				if (ctx.session.isOpen()) {
					ctx.send(msg);
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
	java.util.Map<String, Object> entityDetail(int id) {
		for (net.hedinger.prototype.engine.Entity e : runner.world().getEntities()) {
			if (e == null || e.getID() != id || e.isRemoved()) {
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
			d.put("flying", e.isFlying());
			d.put("health", e.getHealth());
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
			} else if (e instanceof net.hedinger.prototype.engine.PheromoneCloud p) {
				d.put("kind", "phero");
				d.put("strength", round(p.getStrength()));
			} else if (e instanceof net.hedinger.prototype.entities.NPC n) {
				d.put("kind", "npc." + n.getNpcTypeName().toLowerCase());
				d.put("subtype", n.getNpcTypeName().toLowerCase());
				d.put("energy", round(n.getEnergy()));
				d.put("carrying", n.getCarriedLoad() > 0);
				d.put("grabbed", n.isGrabbed());
				// Growth: a juvenile is still climbing toward its genome's body, so a
				// creature that looks too small for its species is simply young.
				if (n.isJuvenile()) {
					d.put("juvenile", true);
					d.put("grown", Math.round(n.maturity() * 100) + "%");
				}
				if (n instanceof net.hedinger.prototype.simtest.TestNPC tn) {
					d.put("minded", tn.isMinded());
					d.put("generation", tn.generation()); // 0 = world-spawned, +1 per birth
					if (!tn.ecoRole().isEmpty()) {
						d.put("role", tn.ecoRole());
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
					gm.put("mateThreshold", round(g.mateThreshold));
					gm.put("hasBrain", g.brain != null);
					gm.put("brainLen", g.brain != null ? g.brain.length() : 0);
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
		d.put("type", t.getType().name().toLowerCase().replace("type_", ""));
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
			net.hedinger.prototype.entities.LgpMind lm =
					e instanceof net.hedinger.prototype.simtest.TestNPC tn ? tn.lgpMind() : null;
			if (lm == null || lm.brain() == null) {
				d.put("hasBrain", false);
				return d;
			}
			net.hedinger.prototype.simtest.TestNPC tn = (net.hedinger.prototype.simtest.TestNPC) e;
			net.hedinger.prototype.entities.Brain b = lm.brain();
			d.put("hasBrain", true);
			d.put("generation", tn.generation()); // lineage depth: 0 = world-seeded
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
	 * level z — 0..100 = fraction of this grass tile's current capacity that has
	 * vegetation (100 lush, 0 grazed bare), or 255 for a non-grass tile (bare
	 * dirt / rock / water — no overlay). Lets the client show grazing depletion
	 * and regrowth on top of the static baked ground.
	 */
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
				var t = w.getTile(x, y, z);
				double cap = t.vegetationCap();
				if (!t.growsVegetation() || cap <= 1e-9) {
					v[y * cols + x] = (byte) 255; // non-grass: no overlay
				} else {
					long frac = Math.round(t.getVegetation(tick) / cap * 100);
					v[y * cols + x] = (byte) Math.max(0, Math.min(100, frac));
				}
			}
		}
		return v;
	}

	/**
	 * Per-tile cover mask for the concealment overlay: one byte per tile of
	 * level z — 1 where the tile is walkable foliage cover (a {@code
	 * TYPE_COVER} thicket or a {@code TYPE_REEDS} bed, drawn as canopy), 2
	 * where it is an enclosed crawl duct (drawn as a metal lid — a duct must
	 * not sprout shrubbery), else 0. Static for the life of the world, so the
	 * client fetches it once per level and draws the overlay over entities
	 * standing in it — anything the sim hides, the viewer part-hides too.
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
					v = (byte) (t.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_DUCT
							? 2 : 1);
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

	/** The fully-grazed twin of {@link #chunk}: the same chunk with every tile's
	 *  vegetation stripped, for the client's live-depletion dither. */
	byte[] bareChunk(int z, int cx, int cy) {
		return bareChunks.get(z + "/" + cx + "_" + cy);
	}

	int viewers() {
		return sessions.size();
	}
}
