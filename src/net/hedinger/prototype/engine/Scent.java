package net.hedinger.prototype.engine;

/**
 * Typed stimulus deposits that entities leave on tiles as they act. Each type
 * fades at its own rate every simulation tick, so a trail is both a direction
 * (follow the gradient) and a clock (strength = how fresh).
 */
public enum Scent {
	/** dropped continuously by herbivores as they walk */
	TRAIL_HERBIVORE(0.990f),
	/** dropped continuously by predators as they walk */
	TRAIL_PREDATOR(0.990f),
	/** burst-dropped by fleeing animals; spooks others of their kind */
	FEAR(0.960f),
	/** dropped where an animal was wounded; draws predators */
	BLOOD(0.985f),
	/** marks a rich feeding site (heavy flora, cached corpse) */
	FOOD(0.980f);

	private final float decay;

	private Scent(float decay) {
		this.decay = decay;
	}

	public float getDecay() {
		return decay;
	}
}
