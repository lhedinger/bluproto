package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Door;

/**
 * The shared machinery behind the facility's machines: one livery, one shading
 * rule, one way of turning two authored silhouettes into eight headings on the
 * art-pixel lattice.
 *
 * <p>Extracted when the loader arrived, because the alternative was two
 * copies of it, and a rule that exists twice is a rule that will shortly exist
 * in two versions. Everything general lives here; a painter keeps only what is
 * actually its own — its stamps, and how it meets the ground.
 *
 * <p>The palette is shared on purpose and not merely to save a constant. Both
 * bodies are plant belonging to the same buried building, and they are painted
 * in the safety yellow already on that building's floor markings; a second
 * yellow, however close, would say they came from different places. See
 * ART-STYLE.md section 2 on promoting an accent to a ramp.
 */
final class MachineStamp {

	/** Art-pixels per tile — the lattice everything lands on. */
	static final int A = 12;

	/** Heading buckets, matching the creature atlases' eight. */
	static final int DIRS = 8;

	/**
	 * The facility's own safety yellow, given the two shades it needs to be a
	 * body colour rather than a stripe.
	 *
	 * <p>Nothing here is invented. {@code 0xd8b028} is the hazard yellow already
	 * painted on the charge dock's keep-clear border, the vent gratings and
	 * every other "machinery works here" marking underground; the shadow and
	 * highlight come off it by section 4's own x0.65 and x1.18, the same
	 * operation {@code chargeDock} uses on its contact ring.
	 */
	static final int HAZARD_RGB = 0xd8b028;

	static final Color[] YELLOW = {
			scale(HAZARD_RGB, 0.65), new Color(HAZARD_RGB), scale(HAZARD_RGB, 1.18),
	};

	/** The black half of the hazard checker, as the ground painters use it. */
	static final Color HAZARD_DARK = new Color(0x17171a);

	/** Sunk south edges and chassis. Iron rather than a darker yellow: against
	 *  pale ground a yellow-on-yellow edge disappears, and a machine needs a
	 *  hard bottom line to sit against anything. */
	static final Color IRON = new Color(Door.IRON_DARK_RGB);

	/** The warning lamp: the signal family's red, the one colour that survives
	 *  against a yellow body. */
	static final Color LAMP = new Color(0xE0455F);

	static final Color SHADOW = new Color(0, 0, 0, 78);

	/** A standing body's contact shadow is darker than a flyer's, because it is
	 *  cast from directly under the body rather than across a gap of air. */
	static final Color CONTACT = new Color(0, 0, 0, 108);

	static Color scale(int rgb, double f) {
		return new Color(clamp(((rgb >> 16) & 0xff) * f), clamp(((rgb >> 8) & 0xff) * f),
				clamp((rgb & 0xff) * f));
	}

	private static int clamp(double v) {
		return (int) Math.max(0, Math.min(255, Math.round(v)));
	}

	/**
	 * The eight shaded stamps: two authored silhouettes and six exact 90-degree
	 * lattice rotations of them, shaded afterwards.
	 *
	 * <p>Shading is applied AFTER the rotation, never carried through it. There
	 * is one sun and it is fixed straight overhead-north in world space, so a
	 * rotated copy of a lit stamp would carry its highlight around with the
	 * heading and light the machine from underneath half the time.
	 */
	static char[][][] facings(String[] cardinal, String[] diagonal, int n) {
		char[][][] out = new char[DIRS][][];
		for (int i = 0; i < DIRS; i++) {
			char[][] s = grid((i & 1) == 0 ? cardinal : diagonal, n, n);
			for (int q = 0; q < i / 2; q++) {
				s = rot90(s, n);
			}
			out[i] = shade(s, n);
		}
		return out;
	}

	/** Which of the eight headings a direction falls in. */
	static int bucket(double dir) {
		return Math.floorMod((int) Math.round(dir / (Math.PI * 2 / DIRS)), DIRS);
	}

	static char[][] grid(String[] rows, int h, int w) {
		char[][] g = new char[h][w];
		for (int r = 0; r < h; r++) {
			for (int c = 0; c < w; c++) {
				g[r][c] = rows[r].charAt(c);
			}
		}
		return g;
	}

	/** Clockwise quarter turn. Lossless on a square lattice, which is the whole
	 *  reason the derived headings stay as on-grid as the authored ones. */
	static char[][] rot90(char[][] s, int n) {
		char[][] o = new char[n][n];
		for (int r = 0; r < n; r++) {
			for (int c = 0; c < n; c++) {
				o[r][c] = s[n - 1 - c][r];
			}
		}
		return o;
	}

	/**
	 * One sun, straight overhead-north: in every column, the north cell of a
	 * contiguous run of body is lit and its south cell is sunk.
	 *
	 * <p>A run one art-pixel tall stays mid. Such a cell is simultaneously the
	 * north edge and the south edge of its part — a sliver that thin has no two
	 * faces to light differently — and lighting it anyway speckles the tips of
	 * diagonal staircases with stray bright pixels.
	 */
	static char[][] shade(char[][] s, int n) {
		char[][] o = new char[n][n];
		for (char[] row : o) {
			java.util.Arrays.fill(row, '.');
		}
		for (int c = 0; c < n; c++) {
			int r = 0;
			while (r < n) {
				if (isBody(s[r][c])) {
					int r0 = r;
					while (r < n && isBody(s[r][c])) {
						r++;
					}
					for (int k = r0; k < r; k++) {
						int pos = r - r0 == 1 ? 1 : k == r0 ? 2 : k == r - 1 ? 0 : 1;
						o[k][c] = mark(s[k][c], pos, k, c);
					}
				} else {
					if (s[r][c] == 'A') {
						o[r][c] = 'A';
					}
					r++;
				}
			}
		}
		return o;
	}

	/** Hull, panel and chassis are all body — they light as one run where they
	 *  touch, which is what makes a pylon read as holding a plate rather than
	 *  as a separate speck. */
	private static boolean isBody(char ch) {
		return ch == '#' || ch == 'S' || ch == 'C';
	}

	/**
	 * Material and lighting compose into one mark: the stamp says what a cell is
	 * made of, the run position says which way it faces the sun.
	 *
	 * <p>The hazard checker is resolved here rather than at paint time so the
	 * stamps stay pure data. It is indexed body-locally, not world-absolutely —
	 * world indexing is for ground, and a world-anchored pattern would crawl
	 * across the hull as the machine moved. A one-on-one-off checker is its own
	 * reflection under a quarter turn on an odd grid, so it survives the derived
	 * headings unchanged.
	 */
	private static char mark(char material, int pos, int row, int col) {
		switch (material) {
		case 'C':
			return 'C'; // chassis: flat iron
		case 'S':
			// The checker's black is a marking, like the dock's keep-clear
			// border, and markings are not shaded.
			return ((row + col) & 1) == 0 ? (pos == 2 ? 'h' : pos == 0 ? 'd' : 'm') : 'K';
		default:
			return pos == 2 ? 'H' : pos == 0 ? 'I' : 'M';
		}
	}

	static Color shadeOf(char ch) {
		switch (ch) {
		case 'H':
			return YELLOW[2]; // hull, lit north edge
		case 'M':
			return YELLOW[1]; // hull, mid
		case 'I':
			return IRON;      // hull, sunk south edge
		case 'h':
			return YELLOW[2]; // panel, lit
		case 'm':
			return YELLOW[1];
		case 'd':
			return YELLOW[0]; // panel, sunk — yellow: a panel is thin
		case 'K':
			return HAZARD_DARK;
		case 'C':
			return IRON;
		case 'A':
			return LAMP;
		default:
			return null;
		}
	}

	/**
	 * Stamps art-pixel blocks onto the world lattice.
	 *
	 * <p>Edge-exact: each block's extent comes from its neighbour's rounded
	 * edge, never a rounded-up box, or a translucent pass's overlaps
	 * double-tint into grid lines.
	 */
	static void blit(Graphics2D g2, char[][] rows, int gx0, int gy0, double z, View v,
			Color flat) {
		for (int row = 0; row < rows.length; row++) {
			for (int col = 0; col < rows[row].length; col++) {
				char ch = rows[row][col];
				if (ch == '.') {
					continue;
				}
				Color c = flat != null ? flat : shadeOf(ch);
				if (c == null) {
					continue;
				}
				int gx = gx0 + col, gy = gy0 + row;
				int x0 = v.pixelX(gx / (double) A, z, 0);
				int x1 = v.pixelX((gx + 1) / (double) A, z, 0);
				int y0 = v.pixelY(gy / (double) A, z, 0);
				int y1 = v.pixelY((gy + 1) / (double) A, z, 0);
				g2.setColor(c);
				g2.fillRect(x0, y0, x1 - x0, y1 - y0);
			}
		}
	}

	private MachineStamp() {
	}
}
