package net.hedinger.prototype.engine;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * A pheromone deposit as a first-class entity: a soft cloud with a centre, a
 * {@link #getStrength() strength} that evaporates every tick, and a radius that
 * grows with strength. It replaces the old per-tile scalar field -- instead of
 * marking whole tiles, a deposit reinforces the nearest cloud (or spawns a new
 * one), and the world sums nearby clouds when something senses pheromone.
 *
 * <p>Graphically it renders as a translucent radial haze (concentric fills,
 * denser in the middle) rather than a coloured tile, so trails and nests read as
 * organic smells drifting over the ground.
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

	private static final double BASE_RADIUS = 1.4; // tiles, at negligible strength
	private static final double MAX_RADIUS = 4.0;  // tiles
	private static final int RINGS = 5;            // concentric fills for the haze

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
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		int rpx = toPixel(v, size);
		if (rpx < 1) {
			return;
		}
		// Opacity scales with strength but saturates, so a nest reads as a dense
		// haze and a faint trail as a whisper. Concentric fills drawn outer-first
		// with the outer rings faintest give a soft radial gradient that melts to
		// nothing at the edge -- no per-pixel shader, no hard grey halo band.
		double s = Math.min(1.0, strength / 8.0);
		for (int i = RINGS; i >= 1; i--) {
			int rr = rpx * i / RINGS;
			int a = (int) (s * 40.0 * (RINGS - i + 1) / RINGS); // inner rings denser
			if (a < 3) {
				continue;
			}
			g2.setColor(new Color(230, 40, 190, a));
			g2.fillOval(pixelX(v, rr), pixelY(v, rr), rr * 2, rr * 2);
		}
	}

	@Override
	public String getEntityTypeName() {
		return "pheromone";
	}
}
