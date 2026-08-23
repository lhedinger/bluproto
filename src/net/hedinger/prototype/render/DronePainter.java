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
 * <p>The shape is a sentinel rather than a quadrotor: a solid hull with the eye
 * set into its face and two plates held off it on thin pylons, so the machine
 * reads as something built to a purpose rather than as a thing held up by
 * spinning parts. It has a FRONT — the eye leads and the plates flank — because
 * a body that visibly points where it is going tells you what it is about to
 * do.
 *
 * <p>Two silhouettes are authored and six are derived. A cardinal stamp and a
 * diagonal stamp are drawn by hand; the remaining headings are exact 90-degree
 * lattice rotations of those two, which are lossless on a square grid and so
 * stay perfectly on-lattice. A 45-degree rotation would not be, and rasterising
 * one produces exactly the lumpy math that "authored beats computed"
 * (section 5) exists to forbid — hence a second authored stamp instead of a
 * cleverer transform.
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

	/**
	 * Facing east. '#' is body, 'A' the eye, '.' nothing.
	 *
	 * <p>The hull is the mass and the plates are secondary, which took three
	 * tries to get right and is the whole difference between a sentinel and a
	 * pile of bars. Plates as long and as heavy as the hull read as a stack of
	 * planks. Plates joined to the hull by broad pylons invert the reading
	 * entirely — the vertical mass wins and the machine looks like it faces
	 * north whichever way the eye points. What works is a hull that dominates,
	 * small plates set well clear of it, and a pylon exactly one art-pixel
	 * wide: enough to say the plates are held rather than floating, too little
	 * to compete.
	 *
	 * <p>Eleven art-pixels across the plates against ten along the hull, so the
	 * silhouette is still wider than it is long — the proportion that separates
	 * a sentinel from an aircraft, and the one a scenario pins.
	 */
	public static final String[] CARDINAL = {
			".............",
			"....#####....",
			"....#####....",
			"......#......",
			"...#########.",
			"..##########.",
			"..#########A.",
			"..##########.",
			"...#########.",
			"......#......",
			"....#####....",
			"....#####....",
			".............",
	};

	/** Facing south-east: the same hull-and-plates arrangement drawn on the
	 *  diagonal, as staircases of two-pixel runs. Authored rather than derived,
	 *  because no lattice-exact transform reaches a diagonal from a cardinal. */
	public static final String[] DIAGONAL = {
			".............",
			".............",
			"..##.....##..",
			"..###....###.",
			"..####...###.",
			"..#####...##.",
			"...#####.....",
			"....#####....",
			".....#####...",
			"..##..#####..",
			"..###..####..",
			"...###..###..",
			"....##...#A..",
	};

	/**
	 * The ground shadow: sanctioned translucency 2 of 4, the blocky translucent
	 * oval — an ellipse rasterised into art-pixel steps, each block tinting the
	 * ground beneath.
	 *
	 * <p>Authored as its own small shape rather than stamped from the body's
	 * silhouette, which is what the first pass did and it failed twice over. A
	 * silhouette copy fills the gaps between hull and plates, destroying the
	 * separation those gaps exist to create; and offset far enough south to
	 * clear the glyph it stops reading as shadow at all and becomes a second
	 * dark plate parked underneath. An oval reads as ground because that is
	 * what a shadow on ground looks like from above, and it needs no rotation:
	 * the drone turns, the light does not.
	 */
	private static final String[] SHADOW_OVAL = {
			".#####.",
			"#######",
			".#####.",
	};

	/** How far south the shadow falls, in art-pixels. A body that stands casts
	 *  at one; this one casts at eight, clear of the glyph, because it is the
	 *  single thing in the world that genuinely does not touch the ground and
	 *  the gap between body and shadow is the only cue that says so. It still
	 *  casts: "nothing floats" (section 4) means nothing is drawn without a
	 *  shadow to sit against, not that nothing may fly. */
	private static final int LIFT = 8;

	/** Heading buckets, matching the creature atlases' eight. */
	private static final int DIRS = 8;

	/** The eight shaded stamps, resolved once: a pure function of the two
	 *  authored silhouettes, with no RNG and no clock in sight. */
	static final char[][][] FACING = new char[DIRS][][];

	static {
		for (int i = 0; i < DIRS; i++) {
			char[][] s = grid((i & 1) == 0 ? CARDINAL : DIAGONAL);
			for (int q = 0; q < i / 2; q++) {
				s = rot90(s);
			}
			FACING[i] = shade(s);
		}
	}

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

	private static final Color STEEL_MID = new Color(Door.STEEL_MID_RGB);
	private static final Color STEEL_HI = new Color(Door.STEEL_HI_RGB);
	/** The sunk south edge: the base shade at the x0.65 of section 4's raised
	 *  grammar, NOT the near-black iron a first pass used. Iron is a housing
	 *  and an outline colour; against the deck it reads as a hole punched in
	 *  the machine rather than as an edge turned away from the light. */
	private static final Color STEEL_LOW = darken(Door.STEEL_MID_RGB, 0.65);
	/** The one warm pixel on the machine, and deliberately the same hazard
	 *  amber as the charge dock's coil, so drone and berth read as a pair. */
	private static final Color EYE = new Color(0xd8b028);
	private static final Color SHADOW = new Color(0, 0, 0, 78);

	private static Color darken(int rgb, double f) {
		return new Color((int) Math.round(((rgb >> 16) & 0xff) * f),
				(int) Math.round(((rgb >> 8) & 0xff) * f),
				(int) Math.round((rgb & 0xff) * f));
	}

	public static void draw(NPC n, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double Z = n.getZ();
		// One art-pixel on screen. Below a pixel there is nothing to draw.
		if (v.pixelX(n.getX() + 1, Z, 0) - v.pixelX(n.getX(), Z, 0) <= 0) {
			return;
		}
		// Anchor on the world art-pixel lattice, exactly as the nest stamp
		// does: nothing positions itself off-lattice to look smoother.
		int cgx = (int) Math.round(n.getX() * A);
		int cgy = (int) Math.round(n.getY() * A);

		int sh = SHADOW_OVAL.length, sw = SHADOW_OVAL[0].length();
		blit(g2, grid(SHADOW_OVAL, sh, sw), cgx - sw / 2, cgy - sh / 2 + LIFT, Z, v, true);
		blit(g2, FACING[bucket(n.getDirection())], cgx - N / 2, cgy - N / 2, Z, v, false);
	}

	private static void blit(Graphics2D g2, char[][] rows, int gx0, int gy0, double Z, View v,
			boolean asShadow) {
		for (int row = 0; row < rows.length; row++) {
			for (int col = 0; col < rows[row].length; col++) {
				char ch = rows[row][col];
				if (ch == '.') {
					continue;
				}
				Color c = asShadow ? SHADOW : shadeOf(ch);
				if (c == null) {
					continue;
				}
				int gx = gx0 + col, gy = gy0 + row;
				// Edge-exact blocks: each block's extent comes from its
				// neighbour's rounded edge, never a rounded-up box, or the
				// translucent oval's overlaps double-tint into grid lines.
				int x0 = v.pixelX(gx / (double) A, Z, 0);
				int x1 = v.pixelX((gx + 1) / (double) A, Z, 0);
				int y0 = v.pixelY(gy / (double) A, Z, 0);
				int y1 = v.pixelY((gy + 1) / (double) A, Z, 0);
				g2.setColor(c);
				g2.fillRect(x0, y0, x1 - x0, y1 - y0);
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
			return STEEL_LOW;
		case 'A':
			return EYE;
		default:
			return null;
		}
	}

	private static char[][] grid(String[] rows) {
		return grid(rows, N, N);
	}

	private static char[][] grid(String[] rows, int h, int w) {
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
	private static char[][] rot90(char[][] s) {
		char[][] o = new char[N][N];
		for (int r = 0; r < N; r++) {
			for (int c = 0; c < N; c++) {
				o[r][c] = s[N - 1 - c][r];
			}
		}
		return o;
	}

	/**
	 * One sun, straight overhead-north: in every column, the north cell of a
	 * contiguous run of body is lit and its south cell is sunk. Because the
	 * plates stand clear of the hull, each gets its own pair.
	 *
	 * <p>A run one art-pixel tall is the exception, and it stays mid. Such a
	 * cell is simultaneously the north edge and the south edge of its plate — a
	 * sliver that thin has no two faces to light differently — and lighting it
	 * anyway speckles the tips of the diagonal staircases with stray bright
	 * pixels, which reads as noise rather than as shape. A scenario found that
	 * case; it was not foreseen.
	 */
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
						o[k][c] = r - r0 == 1 ? 'M' : k == r0 ? 'H' : k == r - 1 ? 'D' : 'M';
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
