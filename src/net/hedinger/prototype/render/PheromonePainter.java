package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.PheromoneCloud;
import net.hedinger.prototype.engine.View;

/**
 * Paints a pheromone cloud as a chunky stipple haze: translucent art-pixel
 * blocks whose scattered density falls off with radius — pixelated structure,
 * soft tint. Verbatim port of the old {@code PheromoneCloud.draw}.
 */
final class PheromonePainter {

	/** Haze colours: the scent's magenta, with a slightly brighter core tone.
	 *  Translucent: the stipple stays chunky art-pixels, but each block tints
	 *  the ground under it rather than covering it. */
	private static final Color HAZE = new Color(0xC0, 0x2C, 0xA2, 110);
	private static final Color HAZE_CORE = new Color(0xE6, 0x50, 0xC4, 150);

	static void draw(PheromoneCloud cloud, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double X = cloud.getX(), Y = cloud.getY(), Z = cloud.getZ();
		double size = cloud.getSize();
		if (EntityPainters.toPixel(cloud, v, size) < 1) {
			return;
		}
		// The stipple is indexed by world-absolute art-pixel, so it holds
		// still as the cloud decays.
		double s = Math.min(1.0, cloud.getStrength() / 8.0);
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
				double cov = s * f * f * 0.7; // peak coverage at a nest core
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

	private PheromonePainter() {
	}
}
