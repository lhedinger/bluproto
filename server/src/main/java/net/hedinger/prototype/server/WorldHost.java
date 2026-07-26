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

	WorldHost(long seed) {
		buildWorld(seed);
		broadcaster.scheduleAtFixedRate(this::broadcast, BROADCAST_MS, BROADCAST_MS, TimeUnit.MILLISECONDS);
	}

	private void buildWorld(long newSeed) {
		seed = newSeed;
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

	SimulationRunner runner() {
		return runner;
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
