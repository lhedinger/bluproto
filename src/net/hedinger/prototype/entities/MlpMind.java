package net.hedinger.prototype.entities;

/**
 * A {@link Mind} backed by an {@link MlpBrain}: each tick it runs one forward
 * pass over the whole sensor vector and writes the whole actuator vector. The
 * dense-continuous counterpart to {@link LgpMind}, interchangeable with it
 * behind the {@link AgentIO} contract — the body senses and acts identically
 * whichever substrate is deciding, which is what makes the two a fair A/B.
 */
public final class MlpMind implements Mind {

	private final MlpBrain net;

	public MlpMind(MlpBrain net) {
		this.net = net;
	}

	/** The underlying evolvable network (for heredity and inspection). */
	public MlpBrain net() {
		return net;
	}

	@Override
	public void think(double[] sensors, double[] actuators) {
		net.forward(sensors, actuators);
	}

	@Override
	public String[] disassemble(String[] sensorNames, String[] actuatorNames) {
		return net.describe();
	}
}
