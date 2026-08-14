package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Nest;

/**
 * Paints a nest per the design system (ART-STYLE.md): a HAND-AUTHORED pixel
 * stamp — symmetric runs, a clean stepped outline, lit top arc and sunk
 * bottom arc, one straw wisp — stamped onto the world-absolute art-pixel
 * lattice. Authored beats computed here: a distance-tested ring of blocks
 * reads as lumpy geometry, a drawn sprite reads as pixel art. The client's
 * drawNest stamps the SAME pattern, so /sprites shows the pipelines agreeing.
 */
final class NestPainter {

	/** The stamp, 11x9 art-pixels, centred on the nest. Letters are the mud
	 *  ramp's twig tones (H highlight, M mid, D dark shadow), S the rare straw
	 *  accent, o the hollow (blocky translucent tint), '.' nothing. */
	static final String[] STAMP = {
			"...HHSHH...",
			"..HHMMMHH..",
			".MMoooooMM.",
			".MSoooooMM.",
			".MMoooooMD.",
			".MMoooooDD.",
			".DMoooooDD.",
			"..DDMMMDD..",
			"...DDDDD...",
	};

	private static final Color TWIG_DARK = new Color(0x38291a);
	private static final Color TWIG_MID = new Color(0x574024);
	private static final Color TWIG_HI = new Color(0x775a38);
	private static final Color STRAW = new Color(0x8a6a3c);
	private static final Color HOLLOW = new Color(20, 14, 8, 90);

	static void draw(Nest nest, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double Z = nest.getZ();
		double step = (v.pixelX(nest.getX() + 1, Z, 0) - v.pixelX(nest.getX(), Z, 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		// Anchor the stamp on the world art-pixel lattice around the centre.
		int cgx = (int) Math.round(nest.getX() * 12) - STAMP[0].length() / 2;
		int cgy = (int) Math.round(nest.getY() * 12) - STAMP.length / 2;
		for (int row = 0; row < STAMP.length; row++) {
			for (int colIdx = 0; colIdx < STAMP[row].length(); colIdx++) {
				Color c = shade(STAMP[row].charAt(colIdx));
				if (c == null) {
					continue;
				}
				// Edge-exact blocks (no overlap): translucent hollow cells must
				// tile seamlessly, or their overlaps double-tint into grid lines.
				int gx = cgx + colIdx, gy = cgy + row;
				int x0 = (int) Math.round(v.pixelX(gx / 12.0, Z, 0));
				int x1 = (int) Math.round(v.pixelX((gx + 1) / 12.0, Z, 0));
				int y0 = (int) Math.round(v.pixelY(gy / 12.0, Z, 0));
				int y1 = (int) Math.round(v.pixelY((gy + 1) / 12.0, Z, 0));
				g2.setColor(c);
				g2.fillRect(x0, y0, x1 - x0, y1 - y0);
			}
		}
	}

	private static Color shade(char ch) {
		switch (ch) {
		case 'H':
			return TWIG_HI;
		case 'M':
			return TWIG_MID;
		case 'D':
			return TWIG_DARK;
		case 'S':
			return STRAW;
		case 'o':
			return HOLLOW;
		default:
			return null;
		}
	}

	private NestPainter() {
	}
}
