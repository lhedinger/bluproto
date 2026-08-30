package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Unit;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import net.hedinger.prototype.engine.World;

/**
 * Drives a {@link World} at the canonical fixed timestep (33 ticks/s — the
 * cadence the whole project's "real time" is defined against) without any
 * window or wall-clock coupling inside the engine itself. This is the
 * headless replacement for the Swing paint-loop's time gate, and the single
 * place where the two halves of the observation seam meet:
 *
 * <ul>
 *   <li><b>Commands in</b> — anything may {@link #enqueue} a {@link SimCommand}
 *       from any thread; the sim thread drains the queue at the next tick
 *       boundary, applies in FIFO order, and records each in the
 *       {@link CommandLog}.</li>
 *   <li><b>Snapshots out</b> — after every tick an immutable
 *       {@link WorldSnapshot} is captured and handed to listeners; the latest
 *       is always available from {@link #snapshot()}. Listeners run on the sim
 *       thread and must be quick; slow consumers must hand off.</li>
 * </ul>
 *
 * <p>Pause and speed shape only <em>when</em> ticks happen in wall-clock time,
 * never how the world evolves per tick — so they need no place in the command
 * log and cannot affect determinism.
 *
 * <p>Tests and replays drive the runner synchronously ({@link #advance});
 * the live server runs {@link #start} on a dedicated thread.
 */
public final class SimulationRunner {

	/** The project's canonical simulation rate (see Scenario.REALTIME_FPS). */
	@Unit("ticks/s")
	public static final int TICKS_PER_SECOND = 33;
	private static final long TICK_NANOS = 1_000_000_000L / TICKS_PER_SECOND;

	/** Receives every tick's snapshot, on the sim thread. */
	public interface SnapshotListener {
		void onTick(WorldSnapshot snapshot);
	}

	private final World world;
	private final CommandLog log = new CommandLog();
	private final Queue<SimCommand> pending = new ConcurrentLinkedQueue<SimCommand>();
	private final List<SnapshotListener> listeners = new CopyOnWriteArrayList<SnapshotListener>();

	private volatile WorldSnapshot latest;
	private volatile boolean paused = false;
	private volatile double speed = 1.0;
	private volatile boolean running = false;
	private Thread loop;

	public SimulationRunner(World world) {
		this.world = world;
		this.latest = WorldSnapshot.of(world);
	}

	public World world() {
		return world;
	}

	// ---- the tick (the seam itself) ---------------------------------------

	/** One tick: drain+apply+log queued commands, step the world, snapshot. */
	public synchronized void tickOnce() {
		SimCommand c;
		while ((c = pending.poll()) != null) {
			c.apply(world);
			log.append(world.getTick(), c);
		}
		long t0 = System.nanoTime();
		world.think();
		WorldSnapshot s = WorldSnapshot.of(world);
		recordTickTime(System.nanoTime() - t0);
		latest = s;
		for (SnapshotListener l : listeners) {
			l.onTick(s);
		}
	}

	// ---- metrics (rolling per-tick cost) ----------------------------------

	private static final int TICK_WINDOW = 64;
	private final long[] tickNanos = new long[TICK_WINDOW];
	private int tickIdx = 0;
	private long ticksTimed = 0;

	private void recordTickTime(long nanos) {
		tickNanos[tickIdx] = nanos;
		tickIdx = (tickIdx + 1) % TICK_WINDOW;
		ticksTimed++;
	}

	/** Mean wall time of the last window of ticks, in milliseconds. */
	public double avgTickMillis() {
		int n = (int) Math.min(ticksTimed, TICK_WINDOW);
		if (n == 0) {
			return 0;
		}
		long sum = 0;
		for (int i = 0; i < n; i++) {
			sum += tickNanos[i];
		}
		return sum / (double) n / 1_000_000.0;
	}

	/** Advances n ticks synchronously (tests, replays, fast-forward). */
	public void advance(int n) {
		for (int i = 0; i < n; i++) {
			tickOnce();
		}
	}

	// ---- observation ------------------------------------------------------

	public WorldSnapshot snapshot() {
		return latest;
	}

	public void addListener(SnapshotListener l) {
		listeners.add(l);
	}

	public void removeListener(SnapshotListener l) {
		listeners.remove(l);
	}

	public CommandLog commandLog() {
		return log;
	}

	// ---- command intake ---------------------------------------------------

	/**
	 * Queues a command for the next tick boundary (thread-safe). Returns the
	 * tick it will apply at, assuming no pause — an ack for the client.
	 */
	public long enqueue(SimCommand command) {
		pending.add(command);
		return world.getTick();
	}

	// ---- live pacing ------------------------------------------------------

	public void pause() {
		paused = true;
	}

	public void resume() {
		paused = false;
	}

	public boolean isPaused() {
		return paused;
	}

	/** Wall-clock multiplier (0.25 .. 8 sensible); 1.0 = real time. */
	public void setSpeed(double s) {
		speed = Math.max(0.05, Math.min(16.0, s));
	}

	public double getSpeed() {
		return speed;
	}

	/** Starts the real-time loop on its own thread (idempotent). */
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		loop = new Thread(this::runLoop, "sim-loop");
		loop.setDaemon(true);
		loop.start();
	}

	/** Stops the loop and waits for it to exit. */
	public void stop() {
		running = false;
		Thread t = loop;
		if (t != null) {
			try {
				t.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public boolean isRunning() {
		return running;
	}

	/**
	 * Fixed-timestep accumulator: wall-clock elapsed (scaled by speed) is
	 * banked and spent in whole ticks, so the tick rate stays honest even when
	 * a tick occasionally runs long. The bank is capped to avoid a spiral of
	 * death after a stall (better to slip than to freeze catching up).
	 */
	private void runLoop() {
		long prev = System.nanoTime();
		double bank = 0;
		while (running) {
			long now = System.nanoTime();
			if (!paused) {
				bank += (now - prev) * speed;
				long cap = TICK_NANOS * TICKS_PER_SECOND; // at most 1 s of catch-up
				if (bank > cap) {
					bank = cap;
				}
				while (bank >= TICK_NANOS && running) {
					tickOnce();
					bank -= TICK_NANOS;
				}
			}
			prev = now;
			try {
				Thread.sleep(2);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	// ---- replay -----------------------------------------------------------

	/**
	 * Re-runs a recorded session against a fresh world: each logged command is
	 * applied at exactly the tick it originally ran at, interleaved with the
	 * same number of ticks. Given a world built from the same seed by the same
	 * factory, the end state is bit-identical to the original run — the
	 * scenario suite pins this.
	 */
	public static void replay(World fresh, CommandLog log, long ticks) {
		// Tunables are JVM-global: replay from defaults, hand the caller's
		// tuning back afterwards (see Tuning).
		var live = Tuning.snapshot();
		Tuning.restoreDefaults();
		try {
			List<CommandLog.Entry> entries = log.entries();
			int i = 0;
			for (long t = 0; t < ticks; t++) {
				while (i < entries.size() && entries.get(i).tick() == fresh.getTick()) {
					entries.get(i).command().apply(fresh);
					i++;
				}
				fresh.think();
			}
		} finally {
			Tuning.restore(live);
		}
	}
}
