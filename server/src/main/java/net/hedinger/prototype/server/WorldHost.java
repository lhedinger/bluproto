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

	WorldHost(long seed) {
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
		SimulationRunner r = new SimulationRunner(Worlds.demo(newSeed));
		var terrain = Worlds.demoTerrain(newSeed); // entity-free twin, for the bake
		int ts = net.hedinger.prototype.engine.ResourceManager.tileSize;
		int chunkPx = CHUNK_TILES * ts;
		int cols = r.world().getColums(), rows = r.world().getRows();
		int imgW = cols * ts, imgH = rows * ts;
		int cxN = (cols + CHUNK_TILES - 1) / CHUNK_TILES;
		int cyN = (rows + CHUNK_TILES - 1) / CHUNK_TILES;
		java.util.Map<String, byte[]> baked = new java.util.HashMap<String, byte[]>();
		for (int z = 0; z < r.world().getLevels(); z++) {
			java.awt.image.BufferedImage full = LayerBaker.renderLevelImage(terrain, z);
			for (int cy = 0; cy < cyN; cy++) {
				for (int cx = 0; cx < cxN; cx++) {
					int x0 = cx * chunkPx, y0 = cy * chunkPx;
					int w = Math.min(chunkPx, imgW - x0), h = Math.min(chunkPx, imgH - y0);
					baked.put(z + "/" + cx + "_" + cy, LayerBaker.chunkPng(full, x0, y0, w, h));
				}
			}
			full = null; // free the whole-level image before rendering the next
		}
		runner = r;
		chunks = baked;
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
					java.util.List.of(), CHUNK_TILES)));
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
			d.put("dead", e.isDead());
			d.put("flying", e.isFlying());
			d.put("health", e.getHealth());
			if (e instanceof net.hedinger.prototype.entities.Item it) {
				d.put("kind", "item." + it.getKind().name().toLowerCase());
				d.put("edible", it.isEdible());
				d.put("durability", it.getHealth());
			} else if (e instanceof net.hedinger.prototype.entities.NPC n) {
				d.put("kind", "npc." + n.getNpcTypeName().toLowerCase());
				d.put("energy", round(n.getEnergy()));
				d.put("carrying", n.getCarriedLoad() > 0);
				d.put("grabbed", n.isGrabbed());
				if (n instanceof net.hedinger.prototype.simtest.TestNPC tn) {
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
					gm.put("markers", new double[] { round(g.markers[0]), round(g.markers[1]),
							g.markers.length > 2 ? round(g.markers[2]) : 0.0 });
					gm.put("flying", g.flying);
					gm.put("predatory", round(g.predatory));
					gm.put("gregariousness", round(g.gregariousness));
					gm.put("hasBrain", g.brain != null);
					d.put("genome", gm);
				}
			}
			return d;
		}
		return null;
	}

	/**
	 * Tile detail for the debug tile-inspector: type, fertility, and live
	 * vegetation ("food"), so grazing depletion and the seasonal fertility swing
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
		d.put("fertility", round(t.getFertility()));
		d.put("food", round(t.getVegetation(tick)));
		d.put("foodCap", round(t.vegetationCap()));
		return d;
	}

	private static double round(double v) {
		return Math.round(v * 1000.0) / 1000.0;
	}

	SimulationRunner runner() {
		return runner;
	}

	// ---- metrics + recording ----------------------------------------------

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
				java.util.Map.entry("season", net.hedinger.prototype.sim.WorldSteward.seasonLabel(tick)),
				java.util.Map.entry("seasonPhase",
						Math.round(net.hedinger.prototype.sim.WorldSteward.seasonPhase(tick) * 100) / 100.0),
				java.util.Map.entry("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000),
				java.util.Map.entry("heapMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024))));
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
