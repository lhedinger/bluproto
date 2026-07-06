package net.hedinger.prototype.simtest;

import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

/**
 * Test-fixture NPC for scenario tests.
 *
 * <p>Scenarios should exercise <em>engine mechanics</em> (movement, collision,
 * perception, lifecycle, hearing) rather than the behaviour of any concrete
 * game species -- the bestiary (Zombie, Houndeye, ...) is expected to change or
 * disappear, and the tests must survive that. TestNPC provides the minimal
 * behaviours those mechanics need, with per-instance knobs instead of
 * hardcoded species constants:
 *
 * <ul>
 *   <li>{@link #inert} -- never moves; a stationary target or victim</li>
 *   <li>{@link #roamer} -- wanders randomly</li>
 *   <li>{@link #chaser} -- chases the closest NPC it can perceive</li>
 *   <li>{@link #listener} -- inert until it hears a {@code Sound}, then roams</li>
 * </ul>
 *
 * Configure lifecycle via the fluent setters, e.g.
 * {@code TestNPC.inert(x, y, z).withLifespan(50).withDeathspan(0)}.
 */
public class TestNPC extends NPC {

	private enum Behavior {
		INERT, ROAM, CHASE, LISTEN
	}

	private final Behavior behavior;
	private double speed = 0.04;
	private int turn = 5;
	private boolean heard = false;
	private TreeMap<Double, NPC> prey = null;

	private TestNPC(double x, double y, double z, Behavior behavior) {
		super(x, y, z);
		this.behavior = behavior;
		hostile = 1;
		size = 6;
		health = 100;
		deathspan = 1000;
		SEARCH_FREQ = 50;
		LOS_RANGE = 10;
		LOS_FOV = Math.PI * 0.5;
	}

	// ---- factories ---------------------------------------------------------

	/** Never moves. A stationary target, obstacle or damage victim. */
	public static TestNPC inert(double x, double y, double z) {
		return new TestNPC(x, y, z, Behavior.INERT);
	}

	/** Wanders randomly. */
	public static TestNPC roamer(double x, double y, double z) {
		return new TestNPC(x, y, z, Behavior.ROAM);
	}

	/** Chases the closest NPC it can perceive. */
	public static TestNPC chaser(double x, double y, double z) {
		TestNPC t = new TestNPC(x, y, z, Behavior.CHASE);
		t.speed = 0.03;
		return t;
	}

	/** Inert until it hears a Sound, then roams. Tests the hear() channel. */
	public static TestNPC listener(double x, double y, double z) {
		return new TestNPC(x, y, z, Behavior.LISTEN);
	}

	// ---- fluent lifecycle knobs ---------------------------------------------

	public TestNPC withHealth(int h) {
		health = h;
		return this;
	}

	public TestNPC withLifespan(int ticks) {
		lifespan = ticks;
		return this;
	}

	public TestNPC withDeathspan(int ticks) {
		deathspan = ticks;
		return this;
	}

	public TestNPC withSpeed(double s) {
		speed = s;
		return this;
	}

	/** True once this NPC has heard any Sound. */
	public boolean hasHeard() {
		return heard;
	}

	// ---- behaviour -----------------------------------------------------------

	@Override
	protected void think() {
		switch (behavior) {
		case INERT:
			return;
		case ROAM:
			roam(speed, turn);
			return;
		case CHASE:
			prey = getTargets(prey, "", false);
			lockTarget(getClosestNPC(prey));
			chase(speed, turn);
			return;
		case LISTEN:
			if (!heard && lastHeardSound != null) {
				heard = true;
			}
			if (heard) {
				roam(speed, turn);
			}
			return;
		}
	}

	@Override
	public String getNpcTypeName() {
		return "TestNPC";
	}
}
