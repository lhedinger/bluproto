package net.hedinger.prototype.engine;

/**
 * Engine-owned profiling singletons. Historically the perception stopwatch was
 * a static on the Swing application class ({@code main.PrototypeWorld}), which
 * made the <em>engine</em> depend on the <em>app shell</em> — every headless
 * embedding (scenario suite, render harness, the web server) had to reach into
 * the GUI class to seed it before any NPC could think. Owning it here inverts
 * that: the engine is self-contained and shells merely read the report.
 */
public final class Perf {

	/** Perception-scan stopwatch, always present (never null). */
	public static StopWatch stopwatch = new StopWatch();

	private Perf() {
	}
}
