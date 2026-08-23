package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.NPC;

/**
 * Paints the steward's drone per the design system (ART-STYLE.md): a
 * HAND-AUTHORED pixel stamp — symmetric runs, a deliberate silhouette, one
 * accent pixel — stamped onto the world-absolute art-pixel lattice.
 *
 * <p>The shape is a sentinel rather than a quadrotor: a compact pod with two
 * broad plates held off to either side, so the machine reads as panels kept in
 * formation around a core rather than as something held up by spinning parts.
 * It has a FRONT — the eye leads and the plates trail — because a body that
 * visibly points where it is going tells you what it is about to do.
 *
 * <p>Two silhouettes are authored and six are derived, which is the only part
 * of this worth explaining. A cardinal stamp and a diagonal stamp are drawn by
 * hand; the remaining headings are exact 90-degree lattice rotations of those
 * two, which are lossless on a square grid and so stay perfectly on-lattice. A
 * 45-degree rotation would not be, and rasterising one would produce exactly
 * the lumpy math that "authored beats computed" (ART-STYLE.md section 5) exists
 * to forbid — hence a second authored stamp instead of a cleverer transform.
 *
 * <p>Shading is applied AFTER the rotation, never carried through it. There is
 * one sun and it is fixed straight overhead-north in world space, so a rotated
 * copy of a lit stamp would carry its highlight around with the heading and
 * light the machine from underneath half the time. Instead the stamps store
 * only the silhouette, and {@link #shade} walks each column at the end: the
 * north cell of every contiguous run is lit, the south cell is sunk, the rest
 * is mid. That is the raised-thing grammar of section 4 applied per plate, and
 * it comes out right for all eight headings without eight hand-shaded sprites.
 *
 * <p>The web client's {@code drawSentinel} stamps the SAME two silhouettes
 * through the same shading rule, so {@code /sprites} shows the pipelines
 * agreeing.
 */
public final class DronePainter {

	/** Art-pixels per tile — the lattice everything here lands on. */
	private static final int A = 12;

	/** The stamp grid, in art-pixels. Odd, so the body has a true centre to
	 *  rotate about. */
	public static final int N = 13;

	/** Facing east. '#' is body, 'A' the eye, '.' nothing. Eleven art-pixels
	 *  across the plates and nine along the pod: wider than it is long, which
	 *  is what separates a sentinel from an aircraft. The plates stand clear of
	 *  the pod rather than joining it — the gap is what makes them read as held
	 *  in formation, and it also gives every column its own lit and sunk edge. */
	public static final String[] CARDINAL = {
			".............",
			"....#####....",
			"....#####....",
			"....#####....",
			".............",
			"...########..",
			"...########A.",
			"...########..",
			".............",
			"....#####....",
			"....#####....",
			"....#####....",
			".............",
	};

	/** Facing south-east: the same three-plate arrangement drawn on the
	 *  diagonal, as staircases of two-pixel runs. Authored separately because
	 *  no lattice-exact transform gets here from the cardinal. */
	public static final String[] DIAGONAL = {
			".............",
			".............",
			"..##....##...",
			"..###...###..",
			"..####...###.",
			"...####...##.",
			"....####.....",
			".....####....",
			"..##..####...",
			"..###..####..",
			"...###..##A..",
			"....##...##..",
			".............",
	};

	/** Heading buckets, matching the creature atlases' eight. */
	private static final int DIRS = 8;

	/** The eight shaded stamps, resolved once: a pure function of the two
	 *  authored silhouettes, with no RNG and no clock in sight. */
	static final char[][][] FACING = new char[DIRS][][];

	/** The shaded stamp for one heading bucket, for the conformance scenarios
	 *  and for {@code /sprites}. Copied, so nobody can edit the art in place. */
	public static char[][] facing(int bucket) {
		char[][] s = FACING[Math.floorMod(bucket, DIRS)];
		char[][] o = new char[N][];
		for (int r = 0; r < N; r++) {
			o[r] = s[r].clone();
		}
		return o;
	}

	/** How many headings the drone is drawn for. */
	public static int dirs() {
		return DIRS;
	}

	static {
		for (int i = 0; i < DIRS; i++) {
			char[][] s = grid((i & 1) == 0 ? CARDINAL : DIAGONAL);
			for (int q = 0; q < i / 2; q++) {
				s = rot90(s);
			}
			FACING[i] = shade(s);
		}
	}

	private static final Color IRON_DARK = new Color(Door.IRON_DARK_RGB);
	private static final Color STEEL_MID = new Color(Door.STEEL_MID_RGB);
	private static final Color STEEL_HI = new Color(Door.STEEL_HI_RGB);
	/** The one warm pixel on the machine, and deliberately the same hazard
	 *  amber as the charge dock's coil, so drone and berth read as a pair. */
	private static final Color EYE = new Color(0xd8b028);
	/** Sanctioned translucency 1 of 4: the contact shadow. */
	private static final Color SHADOW = new Color(0, 0, 0, 110);

	/** How far south the shadow falls, in art-pixels. A body that stands casts
	 *  at one; this one casts at four because it is the single thing in the
	 *  world that genuinely does not touch the ground, and the gap between body
	 *  and shadow is the only cue that says so. It still casts: "nothing
	 *  floats" (section 4) means nothing is drawn without a shadow to sit
	 *  against, not that nothing may fly. */
	private static final int LIFT = 4;

	public static void draw(NPC n, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double Z = n.getZ();
		// One art-pixel on screen. Below a pixel there is nothing to draw.
		double step = (v.pixelX(n.getX() + 1, Z, 0) - v.pixelX(n.getX(), Z, 0)) / (double) A;
		if (step <= 0) {
			return;
		}
		char[][] stamp = FACING[bucket(n.getDirection())];

		// Anchor on the world art-pixel lattice, exactly as the nest stamp
		// does: nothing positions itself off-lattice to look smoother.
		int cgx = (int) Math.round(n.getX() * A) - N / 2;
		int cgy = (int) Math.round(n.getY() * A) - N / 2;

		for (int pass = 0; pass < 2; pass++) {
			int drop = pass == 0 ? LIFT : 0;
			for (int row = 0; row < N; row++) {
				for (int col = 0; col < N; col++) {
					char ch = stamp[row][col];
					if (ch == '.') {
						continue;
					}
					Color c = pass == 0 ? SHADOW : shadeOf(ch);
					int gx = cgx + col, gy = cgy + row + drop;
					// Edge-exact blocks: each block's extent comes from its
					// neighbour's rounded edge, never a rounded-up box, or the
					// translucent shadow's overlaps double-tint into grid lines.
					int x0 = v.pixelX(gx / (double) A, Z, 0);
					int x1 = v.pixelX((gx + 1) / (double) A, Z, 0);
					int y0 = v.pixelY(gy / (double) A, Z, 0);
					int y1 = v.pixelY((gy + 1) / (double) A, Z, 0);
					g2.setColor(c);
					g2.fillRect(x0, y0, x1 - x0, y1 - y0);
				}
			}
		}
	}

	/** Which of the eight headings a direction falls in. */
	public static int bucket(double dir) {
		return Math.floorMod((int) Math.round(dir / (Math.PI * 2 / DIRS)), DIRS);
	}

	private static Color shadeOf(char ch) {
		switch (ch) {
		case 'H':
			return STEEL_HI;
		case 'M':
			return STEEL_MID;
		case 'D':
			return IRON_DARK;
		case 'A':
			return EYE;
		default:
			return null;
		}
	}

	private static char[][] grid(String[] rows) {
		char[][] g = new char[N][N];
		for (int r = 0; r < N; r++) {
			for (int c = 0; c < N; c++) {
				g[r][c] = rows[r].charAt(c);
			}
		}
		return g;
	}

	/** Clockwise quarter turn. Lossless on a square lattice, which is the whole
	 *  reason the derived headings stay as on-grid as the authored ones. */
	private static char[][] rot90(char[][] s) {
		char[][] o = new char[N][N];
		for (int r = 0; r < N; r++) {
			for (int c = 0; c < N; c++) {
				o[r][c] = s[N - 1 - c][r];
			}
		}
		return o;
	}

	/** One sun, straight overhead-north: in every column, the north cell of a
	 *  contiguous run of body is lit and its south cell is sunk. Because the
	 *  plates stand clear of the pod, each gets its own pair.
	 *
	 *  <p>A run one art-pixel tall is the exception, and it stays mid. Such a
	 *  cell is simultaneously the north edge and the south edge of its plate —
	 *  a sliver that thin has no two faces to light differently — and lighting
	 *  it anyway speckles the tips of the diagonal staircases with stray bright
	 *  pixels, which reads as noise rather than as shape. */
	static char[][] shade(char[][] s) {
		char[][] o = new char[N][N];
		for (char[] row : o) {
			java.util.Arrays.fill(row, '.');
		}
		for (int c = 0; c < N; c++) {
			int r = 0;
			while (r < N) {
				if (s[r][c] == '#') {
					int r0 = r;
					while (r < N && s[r][c] == '#') {
						r++;
					}
					for (int k = r0; k < r; k++) {
						o[k][c] = r - r0 == 1 ? 'M'
								: k == r0 ? 'H'
								: k == r - 1 ? 'D' : 'M';
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

	private DronePainter() {
	}
}
