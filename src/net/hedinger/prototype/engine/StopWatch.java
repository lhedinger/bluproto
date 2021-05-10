package net.hedinger.prototype.engine;

public class StopWatch {

	public long startMs = 0;
	public long laps = 0;

	public long sum = 0;

	public void start() {
		startMs = System.currentTimeMillis();
		laps++;
	}

	public void reset() {
		laps = 0;
	}

	public void stop() {
		long delta = System.currentTimeMillis() - startMs;
		add(delta);
	}

	public synchronized void add(long delta) {
		sum += delta;
	}

	public void printReport() {
		System.out.println(sum + " " + laps + "   " + (float) Math.round(laps / (float) sum));
	}

}
