package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Nest;

/**
 * Paints a nest per the design system (ART-STYLE.md): a woven ring of twig
 * art-pixels rasterised on the world-absolute 12-per-tile lattice, in the mud
 * ramp's timber tones with the rare straw wisp; north arc lit and south arc
 * sunk (the raised grammar — it is a built ring, not a stain); the hollow is
 * the sanctioned blocky translucent oval, each block tinting the ground.
 * Drawn under the bodies, so a brooding nester stands IN its nest.
 */
final class NestPainter {

	// The mud ramp's family (Door.TIMBER_* are the same values): shadow, base,
	// highlight — plus straw, the flora bloom-cream's earthy cousin, rare.
	private static final Color TWIG_DARK = new Color(0x38291a);
	private static final Color TWIG_MID = new Color(0x574024);
	private static final Color TWIG_HI = new Color(0x775a38);
	private static final Color STRAW = new Color(0x8a6a3c);
	private static final Color HOLLOW = new Color(20, 14, 8, 90);

	/** Ring geometry in art-pixels: the hollow inside, the woven band. */
	private static final double R_IN = 2.6, R_OUT = 4.6;

	static void draw(Nest nest, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double Z = nest.getZ();
		double step = (v.pixelX(nest.getX() + 1, Z, 0) - v.pixelX(nest.getX(), Z, 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		// Snap the centre to the world art-pixel lattice: every block below is
		// a real art-pixel, in phase with the ground it sits on.
		int cgx = (int) Math.round(nest.getX() * 12);
		int cgy = (int) Math.round(nest.getY() * 12);
		for (int dy = -5; dy <= 5; dy++) {
			for (int dx = -5; dx <= 5; dx++) {
				double rr = Math.hypot(dx, dy);
				if (rr > R_OUT) {
					continue;
				}
				int gx = cgx + dx, gy = cgy + dy;
				double wx = gx / 12.0, wy = gy / 12.0;
				int sx = (int) Math.round(v.pixelX(wx, Z, 0));
				int sy = (int) Math.round(v.pixelY(wy, Z, 0));
				if (rr <= R_IN) {
					// The hollow: a blocky translucent oval, tinting the ground.
					g2.setColor(HOLLOW);
					g2.fillRect(sx, sy, box, box);
					continue;
				}
				// The woven band: hash-varied twig shades, lit on the north arc
				// and sunk on the south one — raised, like everything built.
				double h = GroundTextures.hash01(gx, gy, 73);
				Color c = h > 0.93 ? STRAW : h > 0.5 ? TWIG_MID : TWIG_DARK;
				if (dy <= -3) {
					c = h > 0.93 ? STRAW : TWIG_HI;
				} else if (dy >= 3) {
					c = TWIG_DARK;
				}
				g2.setColor(c);
				g2.fillRect(sx, sy, box, box);
			}
		}
	}

	private NestPainter() {
	}
}
