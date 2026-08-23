package net.hedinger.prototype.render;

import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.NPC;

/**
 * Paints the facility loader: the same livery and the same stamp machinery as
 * the drone, on a body that stands on the floor instead of hanging over it.
 *
 * <p>Seventeen art-pixels to the drone's thirteen, which is not a preference.
 * The drone is a size-16 body drawn at 13, so a size-20 body wants about 17 to
 * be drawn at the same scale — and the two are meant to be comparable on
 * screen, because "bigger" is the thing the pathfinder is already acting on
 * when it declines to send this one down the crawl duct.
 *
 * <p>The grid also had to grow for the art to work at all. At fifteen the forks
 * would not survive the diagonal: two prongs with a two-pixel gap between them
 * rasterise into one blade at forty-five degrees, and the machine came out a
 * blob. The right size for the body turned out to be the size the silhouette
 * needed, which is a pleasant accident rather than a principle.
 */
public final class LoaderPainter {

	/** The stamp grid, in art-pixels. Odd, so the body has a true centre to
	 *  rotate about — and so the hazard checker survives a quarter turn, which
	 *  holds only on an odd grid. */
	public static final int N = 17;

	/**
	 * Facing east. '#' is hull, 'S' the hazard-striped cab, 'C' chassis iron,
	 * 'A' the lamp.
	 *
	 * <p>Two features and no more, which took three rounds to accept. A
	 * forklift wants a hull, side treads AND forward forks, and at this scale
	 * three separable features cannot all survive being drawn at forty-five
	 * degrees — the treads and forks merged into the hull and every diagonal
	 * came out an amorphous lump. Dropping the treads is what fixed it, and the
	 * loss is smaller than it sounds: the striped cab does the same job of
	 * saying "plant", and a MATERIAL rotates for free where a silhouette
	 * feature has to be redrawn.
	 *
	 * <p>Direction is carried by mass at both ends, per section 5 — forks
	 * ahead, striped cab behind — with the lamp only confirming it.
	 *
	 * <p>The forks are hull yellow, not chassis iron, which was the first
	 * choice and wrong for the reason the guide already records about sunk
	 * edges: near-black against dark rock is not a dark shape, it is no shape.
	 * Underground the loader spends most of its life on exactly that ground,
	 * and the forks simply vanished. What makes them read as forks is that they
	 * stand clear of the hull, not what colour they are — direction is carried
	 * by mass (section 5), and mass has to be visible to carry anything.
	 *
	 * <p>The lamp sits one cell PROUD of the hull rather than inside it, and
	 * that is a lighting constraint rather than a stylistic one. The shading
	 * rule walks each column and lights the north end of every run: a lamp set
	 * into the hull's face splits its column into two runs, and the second one
	 * gets its own bright top edge halfway down the machine. On the drone the
	 * split column happened to be one cell tall either side, which stays mid
	 * and hides it; here it was four, and the seam was plainly visible.
	 */
	public static final String[] CARDINAL = {
			".................",
			".................",
			".................",
			".................",
			"....SSS######....",
			"....SSS#########.",
			"....SSS#########.",
			"....SSS######....",
			"....SSS######A...",
			"....SSS######....",
			"....SSS#########.",
			"....SSS#########.",
			"....SSS######....",
			".................",
			".................",
			".................",
			".................",
	};

	/**
	 * Facing south-east, authored to the cardinal rather than to itself.
	 *
	 * <p>Its forks are one blade rather than two prongs, and that is a decision
	 * rather than a defect. The gap between the cardinal's forks is two
	 * art-pixels; a diagonal staircase closes a gap that narrow, so drawing two
	 * prongs here yields two prongs that touch — which reads worse than one
	 * honest blade, because a viewer sees a smudge where they expect a shape.
	 * What the lattice can express at forty-five degrees is not what it can
	 * express square-on, and pretending otherwise is how the earlier drafts
	 * turned to mush. The proportions still match, which is the part a scenario
	 * can check.
	 */
	public static final String[] DIAGONAL = {
			".................",
			".................",
			".................",
			".................",
			".......SSS.......",
			"......SSS##......",
			".....SSS####.....",
			"....SSS######....",
			"...SSS########...",
			".....########....",
			".....######A.##..",
			"......#####......",
			".......###.......",
			".........##......",
			"..........##.....",
			".................",
			".................",
	};

	/** How far south the contact shadow falls, in art-pixels. One, because this
	 *  body stands on the floor — the flyer's eight is the exception in this
	 *  world, not the rule, and the two shadows side by side are the clearest
	 *  statement of which machine touches the ground. */
	private static final int DROP = 1;

	/** The eight shaded stamps, resolved once. */
	static final char[][][] FACING = MachineStamp.facings(CARDINAL, DIAGONAL, N);

	/** The shaded stamp for one heading, for the scenarios and {@code /sprites}.
	 *  Copied, so nobody can edit the art in place. */
	public static char[][] facing(int bucket) {
		char[][] s = FACING[Math.floorMod(bucket, MachineStamp.DIRS)];
		char[][] o = new char[N][];
		for (int r = 0; r < N; r++) {
			o[r] = s[r].clone();
		}
		return o;
	}

	public static int dirs() {
		return MachineStamp.DIRS;
	}

	public static void draw(NPC n, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double z = n.getZ();
		if (v.pixelX(n.getX() + 1, z, 0) - v.pixelX(n.getX(), z, 0) <= 0) {
			return;
		}
		int cgx = (int) Math.round(n.getX() * MachineStamp.A);
		int cgy = (int) Math.round(n.getY() * MachineStamp.A);

		// A standing body casts its own silhouette one art-pixel south, not the
		// flyer's oval: an oval small enough to read as a shadow disappears
		// entirely under a body this wide, and one wide enough to show is a
		// second object on the floor. What shows here is a hard dark edge along
		// the bottom of the machine, which is exactly what contact looks like.
		MachineStamp.blit(g2, FACING[MachineStamp.bucket(n.getDirection())], cgx - N / 2,
				cgy - N / 2 + DROP, z, v, MachineStamp.CONTACT);
		MachineStamp.blit(g2, FACING[MachineStamp.bucket(n.getDirection())], cgx - N / 2,
				cgy - N / 2, z, v, null);
	}

	private LoaderPainter() {
	}
}
