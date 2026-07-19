package net.hedinger.prototype.entities;

/**
 * A {@link Mind} backed by an LGP {@link Brain}: each tick it advances the brain
 * by a fixed instruction budget, so a longer program updates its actuators less
 * often (see {@link Brain}). This is the swappable LGP implementation of the
 * body/mind contract -- replace it with a different Mind to change the decision
 * method without touching how a body senses or acts.
 */
public final class LgpMind implements Mind {

	private final Brain brain;
	private final int budget;

	public LgpMind(Brain brain) {
		this(brain, Brain.DEFAULT_STEPS_PER_TICK);
	}

	public LgpMind(Brain brain, int budget) {
		this.brain = brain;
		this.budget = Math.max(1, budget);
	}

	/** The underlying evolvable program (for heredity and inspection). */
	public Brain brain() {
		return brain;
	}

	@Override
	public void think(double[] sensors, double[] actuators) {
		brain.step(sensors, actuators, budget);
	}

	@Override
	public String[] disassemble(String[] sensorNames, String[] actuatorNames) {
		return brain.disassemble(sensorNames, actuatorNames);
	}
}
