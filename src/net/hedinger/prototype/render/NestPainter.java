package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Nest;

/**
 * Paints a nest: a woven ring of twig-brown art-pixels with a shaded hollow —
 * built ground, in the timber tones, on the 12-per-tile art grid like every
 * other fixture. The ring sits under the bodies (the grid draws fixtures
 * before creatures), so a brooding nester stands IN its nest.
 */
final class NestPainter {

	private static final Color TWIG_DARK = new Color(0x38291a);
	private static final Color TWIG_MID = new Color(0x574024);
	private static final Color STRAW = new Color(0x8a6a3c);
	private static final Color HOLLOW = new Color(20, 14, 8, 90);

	static void draw(Nest nest, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double X = nest.getX(), Y = nest.getY(), Z = nest.getZ();
		double step = (v.pixelX(X + 1, Z, 0) - v.pixelX(X, Z, 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		// The shaded hollow first, then the woven ring over its rim.
		int hr = (int) Math.round(step * 3);
		g2.setColor(HOLLOW);
		g2.fillOval((int) Math.round(v.pixelX(X, Z, 0)) - hr,
				(int) Math.round(v.pixelY(Y, Z, 0)) - hr, hr * 2, hr * 2);
		int n = 14;
		for (int i = 0; i < n; i++) {
			double a = Math.PI * 2 * i / n;
			double wx = X + Math.cos(a) * 4.0 / 12;
			double wy = Y + Math.sin(a) * 4.0 / 12;
			// Alternate twig shades around the weave, with the odd straw wisp.
			double h = GroundTextures.hash01(i, (int) (X * 12 + Y), 73);
			g2.setColor(h > 0.8 ? STRAW : (i & 1) == 0 ? TWIG_MID : TWIG_DARK);
			g2.fillRect((int) Math.round(v.pixelX(wx, Z, 0)) - box / 2,
					(int) Math.round(v.pixelY(wy, Z, 0)) - box / 2, box, box);
		}
	}

	private NestPainter() {
	}
}
