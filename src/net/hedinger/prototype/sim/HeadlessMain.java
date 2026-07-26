package net.hedinger.prototype.sim;

import java.util.Locale;

/**
 * Console proof of the seam (MODERNIZATION.md Phase 1): runs a demo world with
 * no window at all — fast-forward or paced at the canonical real-time rate —
 * and prints tick statistics. This is the smallest possible embedding of the
 * engine behind {@link SimulationRunner}; the web server is the same shape
 * with a socket where the println is.
 *
 * <pre>
 *   java …sim.HeadlessMain seed=42 ticks=330            # fast-forward 330 ticks
 *   java …sim.HeadlessMain seed=42 ticks=330 realtime   # paced at 33 t/s
 * </pre>
 */
public final class HeadlessMain {

	public static void main(String[] args) throws Exception {
		long seed = 42;
		int ticks = 330;
		boolean realtime = false;
		for (String a : args) {
			if (a.startsWith("seed=")) {
				seed = Long.parseLong(a.substring(5));
			} else if (a.startsWith("ticks=")) {
				ticks = Integer.parseInt(a.substring(6));
			} else if (a.equals("realtime")) {
				realtime = true;
			}
		}

		SimulationRunner runner = new SimulationRunner(Worlds.demo(seed));
		System.out.printf(Locale.ROOT, "world seed=%d  entities=%d  mode=%s%n",
				seed, runner.snapshot().entities().size(), realtime ? "real-time (33 t/s)" : "fast-forward");

		long t0 = System.nanoTime();
		if (realtime) {
			runner.addListener(s -> {
				if (s.tick() % SimulationRunner.TICKS_PER_SECOND == 0) {
					report(s, t0);
				}
			});
			runner.start();
			while (runner.snapshot().tick() < ticks) {
				Thread.sleep(50);
			}
			runner.stop();
		} else {
			for (int done = 0; done < ticks; ) {
				int step = Math.min(SimulationRunner.TICKS_PER_SECOND, ticks - done);
				runner.advance(step);
				done += step;
				report(runner.snapshot(), t0);
			}
		}
		System.out.printf(Locale.ROOT, "done: tick=%d  checksum=%016x  commands=%d%n",
				runner.snapshot().tick(), runner.snapshot().checksum(), runner.commandLog().size());
	}

	private static void report(WorldSnapshot s, long t0) {
		System.out.printf(Locale.ROOT, "tick %5d  entities %3d  elapsed %.1fs%n",
				s.tick(), s.entities().size(), (System.nanoTime() - t0) / 1e9);
	}
}
