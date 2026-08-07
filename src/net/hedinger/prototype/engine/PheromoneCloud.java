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
 * <p>Graphically it renders as a screen-door haze -- opaque stipple whose
 * dithered density falls off with radius -- rather than a coloured tile, so
 * trails and nests read as organic smells drifting over the ground while
 * staying inside the render's pixel-art grammar.
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

	/** Haze colours: the scent's magenta, with a slightly brighter core tone. */
	private static final Color HAZE = new Color(0xC0, 0x2C, 0xA2);
	private static final Color HAZE_CORE = new Color(0xE6, 0x50, 0xC4);

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		if (toPixel(v, size) < 1) {
			return;
		}
		// Screen-door haze: opaque art-pixels whose Bayer-dithered density
		// falls off with radius -- pixel-art translucency, matching the rest
		// of the render instead of an alpha gradient. The stipple is indexed
		// by world-absolute art-pixel, so it holds still as the cloud decays.
		double s = Math.min(1.0, strength / 8.0);
		int A = 12; // art-pixels per tile, matching the ground grid
		double step = (v.pixelX(1, Z, 0) - v.pixelX(0, Z, 0)) / (double) A;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		int gx0 = (int) Math.floor((X - size) * A), gx1 = (int) Math.ceil((X + size) * A);
		int gy0 = (int) Math.floor((Y - size) * A), gy1 = (int) Math.ceil((Y + size) * A);
		for (int gy = gy0; gy <= gy1; gy++) {
			for (int gx = gx0; gx <= gx1; gx++) {
				double wx = (gx + 0.5) / A, wy = (gy + 0.5) / A;
				double f = 1.0 - Math.hypot(wx - X, wy - Y) / size;
				if (f <= 0) {
					continue;
				}
				double cov = s * f * f * 0.55; // peak ~half coverage at a nest core
				// Hash stipple, not Bayer: an organic scatter suits a smell,
				// where the ordered matrix reads as a mechanical grid.
				if (GroundTextures.hash01(gx, gy, 55) >= cov) {
					continue;
				}
				g2.setColor(f > 0.6 ? HAZE_CORE : HAZE);
				g2.fillRect((int) Math.round(v.pixelX(wx, Z, 0) - step * 0.5),
						(int) Math.round(v.pixelY(wy, Z, 0) - step * 0.5), box, box);
			}
		}
	}

	@Override
	public String getEntityTypeName() {
		return "pheromone";
	}
}
