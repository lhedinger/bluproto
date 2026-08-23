package net.hedinger.prototype.render;

import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
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
	 * <p>Eleven art-pixels across the plates against ten along, so the
	 * silhouette is still wider than it is long — the proportion that separates
	 * a sentinel from an aircraft, and the one a scenario pins.
	 *
	 *  <p>The rearmost rank is chassis iron rather than hull yellow, and that
	 *  dark cap is what tells front from back. The lamp alone could not: one
	 *  accent pixel on a body this size is legible as "there is a lamp" long
	 *  before it is legible as "and therefore that end is the front". A snout
	 *  was tried at the other end and dropped — one art-pixel of protrusion is
	 *  invisible at the size the drone is actually seen, and at map zoom it is
	 *  not drawn at all. The tail carries the read; the head needed nothing it
	 *  did not already have.
	 */
	public static final String[] CARDINAL = {
			".............",
			"....SSSSS....",
			"....SSSSS....",
			"......C......",
			"...########..",
			"..C#########.",
			"..C########A.",
			"..C#########.",
			"...########..",
			"......C......",
			"....SSSSS....",
			"....SSSSS....",
			".............",
	};

	/**
	 * Facing south-east. Authored rather than derived, because no lattice-exact
	 * transform reaches a diagonal from a cardinal.
	 *
	 * <p>Which means the two are only as consistent as someone made them, and
	 * the first version was not. Measured along its own axis, that hull ran
	 * thirteen art-pixels long by under four across, against the cardinal's ten
	 * by five: turning forty-five degrees made the machine thirty percent
	 * longer and a quarter thinner, so it appeared to stretch and slim as it
	 * came about. Drawing a diagonal as a staircase of fixed vertical thickness
	 * is what does it — vertical thickness T buys only T/root-2 across the
	 * body, and the error compounds with every step of length.
	 *
	 * <p>This one is laid out in the body's own axes instead: ten long and five
	 * across, matching the cardinal, with the plates at the same perpendicular
	 * offset. The tips are blunted because a rotated rectangle ends in single
	 * pixels on a square lattice, and a one-pixel point is the lumpy-math tell
	 * that section 5 warns about — pixel art ends flat.
	 *
	 * <p>The plates need no pylon here: at this offset they already sit one
	 * clear cell off the hull, which is all the pylon was ever buying.
	 *
	 * <p>The tail cap is cut to the same three cells the cardinal's is. Cutting
	 * it to the same DEPTH instead makes it half again as large, because a
	 * rank measured along a diagonal crosses a wider slice of the body, and it
	 * then reads as a bite taken out of the machine rather than as its back
	 * end. Match the cap by weight, not by depth.
	 */
	public static final String[] DIAGONAL = {
			".............",
			"........SS...",
			"...C##.SSSS..",
			"..C####.SSSS.",
			".C######.SSS.",
			"..#######....",
			"...#######...",
			"....#######..",
			".SSS.#######.",
			".SSSS.####A..",
			"..SSSS.###...",
			"...SS........",
			".............",
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

	/** The eight shaded stamps, resolved once: a pure function of the two
	 *  authored silhouettes, with no RNG and no clock in sight. */
	static final char[][][] FACING = MachineStamp.facings(CARDINAL, DIAGONAL, N);

	/** The shaded stamp for one heading bucket, for the conformance scenarios
	 *  and for {@code /sprites}. Copied, so nobody can edit the art in place. */
	public static char[][] facing(int bucket) {
		char[][] s = FACING[Math.floorMod(bucket, MachineStamp.DIRS)];
		char[][] o = new char[N][];
		for (int r = 0; r < N; r++) {
			o[r] = s[r].clone();
		}
		return o;
	}

	/** How many headings the drone is drawn for. */
	public static int dirs() {
		return MachineStamp.DIRS;
	}

	/** The palette, for the conformance scenarios: proof the machine is painted
	 *  in colours the world already owned rather than ones picked by eye. */
	public static int hullRgb() {
		return MachineStamp.YELLOW[1].getRGB() & 0xffffff;
	}

	public static int litRgb() {
		return MachineStamp.YELLOW[2].getRGB() & 0xffffff;
	}

	public static int checkerRgb() {
		return MachineStamp.HAZARD_DARK.getRGB() & 0xffffff;
	}

	public static int chassisRgb() {
		return MachineStamp.IRON.getRGB() & 0xffffff;
	}

	/** Which of the eight headings a direction falls in. */
	public static int bucket(double dir) {
		return MachineStamp.bucket(dir);
	}

	public static void draw(NPC n, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double z = n.getZ();
		// One art-pixel on screen. Below a pixel there is nothing to draw.
		if (v.pixelX(n.getX() + 1, z, 0) - v.pixelX(n.getX(), z, 0) <= 0) {
			return;
		}
		// Anchor on the world art-pixel lattice, exactly as the nest stamp
		// does: nothing positions itself off-lattice to look smoother.
		int cgx = (int) Math.round(n.getX() * MachineStamp.A);
		int cgy = (int) Math.round(n.getY() * MachineStamp.A);

		int sh = SHADOW_OVAL.length, sw = SHADOW_OVAL[0].length();
		MachineStamp.blit(g2, MachineStamp.grid(SHADOW_OVAL, sh, sw), cgx - sw / 2,
				cgy - sh / 2 + LIFT, z, v, MachineStamp.SHADOW);
		MachineStamp.blit(g2, FACING[MachineStamp.bucket(n.getDirection())], cgx - N / 2,
				cgy - N / 2, z, v, null);
	}

	private DronePainter() {
	}
}
