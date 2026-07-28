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

	private final Object lock = new Object();
	private long seed;
	private SimulationRunner runner;
	private List<byte[]> layers; // baked PNG per level
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
		List<byte[]> baked = new ArrayList<byte[]>();
		var terrain = Worlds.demoTerrain(newSeed); // entity-free twin, for the bake
		for (int z = 0; z < r.world().getLevels(); z++) {
			baked.add(LayerBaker.bake(terrain, z));
		}
		runner = r;
		layers = baked;
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
			List<String> urls = new ArrayList<String>();
			for (int z = 0; z < w.getLevels(); z++) {
				urls.add("/api/world/layers/" + z + ".png");
			}
			ctx.send(Protocol.write(Protocol.Hello.of(seed, w.getColums(), w.getRows(), w.getLevels(),
					net.hedinger.prototype.engine.ResourceManager.tileSize,
					s.tick(), runner.isPaused(), runner.getSpeed(), urls)));
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
		return new java.util.LinkedHashMap<String, Object>(java.util.Map.ofEntries(
				java.util.Map.entry("seed", seed),
				java.util.Map.entry("tick", runner.snapshot().tick()),
				java.util.Map.entry("tickMs", Math.round(runner.avgTickMillis() * 1000) / 1000.0),
				java.util.Map.entry("targetTps", SimulationRunner.TICKS_PER_SECOND),
				java.util.Map.entry("entities", runner.snapshot().entities().size()),
				java.util.Map.entry("commands", runner.commandLog().size()),
				java.util.Map.entry("viewers", viewers()),
				java.util.Map.entry("paused", runner.isPaused()),
				java.util.Map.entry("speed", runner.getSpeed()),
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

	byte[] layer(int z) {
		List<byte[]> l = layers;
		return z >= 0 && z < l.size() ? l.get(z) : null;
	}

	int viewers() {
		return sessions.size();
	}
}
