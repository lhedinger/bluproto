package net.hedinger.prototype.engine;

/**
 * A pheromone deposit as a first-class entity: a soft cloud with a centre, a
 * {@link #getStrength() strength} that evaporates every tick, and a radius that
 * grows with strength. It replaces the old per-tile scalar field -- instead of
 * marking whole tiles, a deposit reinforces the nearest cloud (or spawns a new
 * one), and the world sums nearby clouds when something senses pheromone.
 *
 * <p>Graphically it renders as a chunky stipple haze -- translucent
 * art-pixel blocks whose scattered density falls off with radius -- rather
 * than a coloured tile, so trails and nests read as organic smells drifting
 * over the ground while staying inside the render's pixel-art grammar.
 *
 * <p>It draws no RNG (it is built through the direction-taking {@link Entity}
 * constructor) and never moves or collides, so scattering clouds through a
 * simulation does not perturb the deterministic actor stream.
 */
public class PheromoneCloud extends Entity {

	/** Fraction of strength retained per tick (matches the old field decay). */
	public static final double DECAY = 0.99;
	/** Below this strength the cloud has effectively evaporated and is removed. */
	public static final double MIN_STRENGTH = 0.05;
	/** Deposits within this many tiles of a cloud reinforce it instead of
	 *  spawning a new one -- keeps a nest one coherent cloud (roughly a cloud
	 *  radius wide), not a scattered ring of them. */
	public static final double MERGE_RADIUS = 2.5;

	public static final double BASE_RADIUS = 1.4; // tiles, at negligible strength
	public static final double MAX_RADIUS = 4.0;  // tiles

	private double strength;

	public PheromoneCloud(double x, double y, double z, double amount) {
		super(x, y, z, 0.0); // direction-taking ctor: no RNG draw
		this.strength = amount;
		updateRadius();
	}

	public double getStrength() {
		return strength;
	}

	/** Adds to this cloud (a repeated deposit at the same spot builds a peak). */
	public void reinforce(double amount) {
		strength += amount;
		updateRadius();
	}

	private void updateRadius() {
		size = (float) Math.min(MAX_RADIUS, BASE_RADIUS + Math.log1p(strength) * 0.7);
	}

	/**
	 * Concentration this cloud contributes at a world point: its strength with a
	 * smooth radial falloff to zero at the edge (so the centre reads full
	 * strength, matching what the old tile field returned underfoot).
	 */
	public double concentrationAt(double px, double py) {
		double dx = px - X, dy = py - Y;
		double d = Math.sqrt(dx * dx + dy * dy);
		double r = size;
		if (r <= 0 || d >= r) {
			return 0;
		}
		double f = 1.0 - d / r;
		return strength * f * f;
	}

	@Override
	protected void think() {
		strength *= DECAY;
		if (strength <= MIN_STRENGTH) {
			remove();
			return;
		}
		updateRadius();
	}

	@Override
	public String getEntityTypeName() {
		return "pheromone";
	}
}
