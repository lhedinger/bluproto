package net.hedinger.prototype.sim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The simulation's constants, surveyed as a registry: every public static
 * primitive on the surveyed classes, each with its live value and whether it
 * can be tuned at runtime.
 *
 * <p>Two kinds of constant, and the distinction is load-bearing:
 *
 * <ul>
 *   <li><b>Tunable</b> — declared {@code public static} without {@code final}.
 *       Reads happen at the use site every time, so a change takes effect on
 *       the next tick. Writes go through {@link TuneCommand} only, so every
 *       change rides the command log and {@code seed + log} still reproduces
 *       the world bit for bit.</li>
 *   <li><b>Frozen</b> — still {@code final}. Either structural (anchors like
 *       {@code REF_SIZE}, wire codes, array dimensions) or copied out at
 *       build time (a {@link net.hedinger.prototype.engine.Tile} takes its
 *       regrow rate at construction), so a runtime edit would silently lie.
 *       The registry lists them read-only rather than pretending.</li>
 * </ul>
 *
 * <p>Because tunables are JVM-global statics and replays rebuild worlds in
 * the same process, anything that replays a log must run inside
 * {@link #snapshot()} / {@link #restore(Map)} so the live world's tuning
 * survives, and a world reset calls {@link #restoreDefaults()} so a fresh
 * recording starts from the code-level defaults its log assumes.
 */
public final class Tuning {

	/** The classes whose public static primitives make up the registry. */
	private static final Class<?>[] SURVEYED = {
			net.hedinger.prototype.engine.Tile.class,
			net.hedinger.prototype.engine.World.class,
			net.hedinger.prototype.entities.Genome.class,
			net.hedinger.prototype.entities.NPC.class,
			net.hedinger.prototype.simtest.TestNPC.class,
			StewardDrone.class,
			SimulationRunner.class,
	};

	/**
	 * Constants that are NAMES, not quantities — enum-like codes whose number
	 * is an arbitrary label (a direction is not "more" than another). The
	 * registry is for values where more and less mean something, so these are
	 * excluded from the survey rather than listed as frozen noise.
	 */
	private static final java.util.Set<String> CODES = java.util.Set.of(
			"Tile.DIR_N", "Tile.DIR_E", "Tile.DIR_S", "Tile.DIR_W",
			"NPC.STATUS_SLEEP", "NPC.STATUS_IDLE", "NPC.STATUS_ALERT",
			"NPC.STATUS_THREAT");

	/** Key -> field, in survey order. Built once; the class list is fixed. */
	private static final Map<String, Field> FIELDS = survey();

	/** The boot-time value of every surveyed field — the code-level defaults. */
	private static final Map<String, Double> DEFAULTS = capture();

	private static Map<String, Field> survey() {
		Map<String, Field> out = new LinkedHashMap<>();
		for (Class<?> c : SURVEYED) {
			for (Field f : c.getDeclaredFields()) {
				int m = f.getModifiers();
				if (!Modifier.isStatic(m) || !Modifier.isPublic(m)) {
					continue;
				}
				Class<?> t = f.getType();
				if (t != double.class && t != int.class && t != long.class
						&& t != boolean.class) {
					continue;
				}
				String key = c.getSimpleName() + "." + f.getName();
				if (CODES.contains(key)) {
					continue;
				}
				// A quantity without a unit is only half stated: refuse to
				// survey it, so a new constant cannot land unreadable.
				if (f.getAnnotation(net.hedinger.prototype.engine.Unit.class) == null) {
					throw new IllegalStateException(
							"constant lacks a @Unit annotation: " + key);
				}
				out.put(key, f);
			}
		}
		return out;
	}

	private static Map<String, Double> capture() {
		Map<String, Double> out = new LinkedHashMap<>();
		for (var e : FIELDS.entrySet()) {
			out.put(e.getKey(), read(e.getValue()));
		}
		return out;
	}

	private static double read(Field f) {
		try {
			Class<?> t = f.getType();
			if (t == double.class) {
				return f.getDouble(null);
			}
			if (t == int.class) {
				return f.getInt(null);
			}
			if (t == long.class) {
				return f.getLong(null);
			}
			return f.getBoolean(null) ? 1 : 0;
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("unreadable constant " + f, e);
		}
	}

	/** True for a key that exists and may be tuned at runtime. */
	public static boolean tunable(String key) {
		Field f = FIELDS.get(key);
		return f != null && !Modifier.isFinal(f.getModifiers());
	}

	/** True for any surveyed key, frozen or not. */
	public static boolean known(String key) {
		return FIELDS.containsKey(key);
	}

	/**
	 * Sets a tunable constant. {@link TuneCommand} is the only caller on a
	 * live world — direct calls are for the registry's own bookkeeping
	 * (defaults, snapshots) and the scenario suite.
	 */
	public static void set(String key, double value) {
		Field f = FIELDS.get(key);
		if (f == null || Modifier.isFinal(f.getModifiers())) {
			throw new IllegalArgumentException("not a tunable constant: " + key);
		}
		try {
			Class<?> t = f.getType();
			if (t == double.class) {
				f.setDouble(null, value);
			} else if (t == int.class) {
				f.setInt(null, (int) Math.round(value));
			} else if (t == long.class) {
				f.setLong(null, Math.round(value));
			} else {
				f.setBoolean(null, value != 0);
			}
		} catch (IllegalAccessException e) {
			throw new IllegalStateException("unwritable constant " + key, e);
		}
	}

	/**
	 * The registry as rows for the observation seam: key, live value, the
	 * boot default, the primitive type, and whether the row is frozen.
	 */
	public static List<Map<String, Object>> list() {
		List<Map<String, Object>> out = new ArrayList<>();
		for (var e : FIELDS.entrySet()) {
			Field f = e.getValue();
			out.add(Map.of(
					"key", e.getKey(),
					"value", read(f),
					"def", DEFAULTS.get(e.getKey()),
					"unit", f.getAnnotation(net.hedinger.prototype.engine.Unit.class).value(),
					"type", f.getType().getSimpleName(),
					"frozen", Modifier.isFinal(f.getModifiers())));
		}
		return out;
	}

	/** Live values of every tunable, for bracketing a replay. */
	public static Map<String, Double> snapshot() {
		Map<String, Double> out = new LinkedHashMap<>();
		for (var e : FIELDS.entrySet()) {
			if (!Modifier.isFinal(e.getValue().getModifiers())) {
				out.put(e.getKey(), read(e.getValue()));
			}
		}
		return out;
	}

	/** Restores a {@link #snapshot()}. */
	public static void restore(Map<String, Double> values) {
		for (var e : values.entrySet()) {
			set(e.getKey(), e.getValue());
		}
	}

	/** Back to the code-level defaults — what a fresh recording's log assumes. */
	public static void restoreDefaults() {
		for (var e : DEFAULTS.entrySet()) {
			if (!Modifier.isFinal(FIELDS.get(e.getKey()).getModifiers())) {
				set(e.getKey(), e.getValue());
			}
		}
	}

	private Tuning() {
	}
}
