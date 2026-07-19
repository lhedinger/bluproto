package net.hedinger.prototype.entities;

/**
 * A creature's decision method: it reads the {@link AgentIO} sensor vector and
 * writes the actuator vector, once per tick. That is the whole contract -- a
 * Mind never touches the world, so it is fully interchangeable.
 *
 * <p>Implementations may be <b>reactive</b> (compute outputs from inputs on the
 * spot -- a hand-written controller or a neural net) or <b>continuous/stateful</b>
 * (advance an internal process a slice at a time and latch outputs -- the LGP
 * {@link LgpMind}). The body treats them identically: fill sensors, call
 * {@link #think}, read actuators. Dummy entities just supply a trivial Mind.
 */
public interface Mind {

	/** Reads {@code sensors} and updates {@code actuators} for this tick. */
	void think(double[] sensors, double[] actuators);

	/** A human-readable listing of the policy, for the inspector; empty if the
	 * mind has nothing to show (e.g. a hard-coded controller). */
	default String[] disassemble(String[] sensorNames, String[] actuatorNames) {
		return new String[0];
	}
}
