package net.hedinger.prototype.engine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Procedurally generated wall, hole and ramp tiles -- the structural terrain
 * counterpart to {@link GroundTextures} (which handles the open ground). Instead
 * of slicing sub-tiles out of {@code res/tiles/walls.png}, each tile is drawn
 * from its connectivity code so the shape autotiles: a wall/hole grows rounded
 * convex corners where it faces open ground, concave corners around a pocket,
 * and merges flush with its like neighbours -- no seams down a solid mass.
 *
 * <p>The connectivity code is the {@link Tile#getTileCode()} string of connected
 * neighbour directions (1=NW 2=N 3=NE 4=W 5=E 6=SW 7=S 8=SE). Images are the
 * padded tile size the layer compositor expects; the tile footprint sits in the
 * centre, with the surrounding pad free for drop shadows. Baked once per code,
 * deterministic (a dedicated RNG, never the sim RNG).
 */
public final class ProcTiles {

	private static final int TS = ResourceManager.tileSize;
	private static final int PAD = ResourceManager.tilePadding;
	private static final int SZ = TS + PAD * 2; // padded canvas
	private static final int X0 = PAD, Y0 = PAD; // tile footprint origin

	// Stone wall: cool grey body sitting on the ground. The base fills the tile
	// with the grass colour so the rounded convex corners and concave pockets
	// carved out of the body blend into the surrounding grass instead of showing
	// a dark notch (walls are baked separately from the procedural ground, so the
	// tile has to supply its own backdrop).
	private static final Color WALL_BODY = new Color(104, 108, 120);
	private static final Color WALL_BASE = GroundTextures.GRASS_GREEN;
	// Pit: near-black void framed by an earth lip (the hole-floor layer below).
	private static final Color HOLE_DARK = new Color(10, 11, 16);
	// Ramp: an earthy slope, light at the high end, with uphill chevrons.
	private static final Color RAMP_LO = new Color(86, 80, 64);
	private static final Color RAMP_HI = new Color(160, 152, 126);

	private ProcTiles() {
	}

	// ---- public tile builders (called by ResourceManager) ------------------

	/** A wall tile: a stone mass that merges with connected wall neighbours. */
	public static BufferedImage wall(String code, int variant) {
		boolean N = has(code, '2'), E = has(code, '5'), S = has(code, '7'), W = has(code, '4');
		boolean NW = has(code, '1'), NE = has(code, '3'), SE = has(code, '8'), SW = has(code, '6');
		BufferedImage img = new BufferedImage(SZ, SZ, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		int r = (int) (TS * 0.40);
		Area body = blob(N, E, S, W, NW, NE, SE, SW, 0, r);

		// Opaque dark base over the whole footprint: hides the floor sprite under
		// the wall and becomes the bevelled dark corner where the body rounds off.
		g.setColor(WALL_BASE);
		g.fillRect(X0, Y0, TS, TS);
		// Drop shadow down-right into the pad. Connected right/below neighbours are
		// composited later and paint over it, so it only shows on the open side.
		Area shadow = (Area) body.clone();
		shadow.transform(AffineTransform.getTranslateInstance(4, 5));
		g.setColor(new Color(0, 0, 0, 60));
		g.fill(shadow);
		// Body.
		g.setColor(WALL_BODY);
		g.fill(body);
		// Stone grain, clipped to the body.
		g.setClip(body);
		Random rng = new Random(seed(code, variant));
		for (int i = 0; i < 70; i++) {
			int px = X0 + rng.nextInt(TS), py = Y0 + rng.nextInt(TS), rr = 1 + rng.nextInt(3);
			int d = rng.nextInt(34) - 17;
			g.setColor(new Color(cl(WALL_BODY.getRed() + d), cl(WALL_BODY.getGreen() + d),
					cl(WALL_BODY.getBlue() + d), 110));
			g.fillOval(px, py, rr, rr);
		}
		g.setClip(null);
		g.dispose();
		return img;
	}

	/** The pit (top layer) of a hole: a dark void inset to leave an earth lip. */
	public static BufferedImage hole(String code, int variant) {
		boolean N = has(code, '2'), E = has(code, '5'), S = has(code, '7'), W = has(code, '4');
		boolean NW = has(code, '1'), NE = has(code, '3'), SE = has(code, '8'), SW = has(code, '6');
		BufferedImage img = new BufferedImage(SZ, SZ, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		int r = (int) (TS * 0.40);
		int lip = 7; // earth rim shown on open sides
		// No concave carving for pits: it fights the lip inset and leaves a hooked
		// nub at inner bends. Passing all diagonals "connected" keeps the convex
		// outer rounding but gives clean square inner corners, framed by the lip.
		Area pit = blob(N, E, S, W, true, true, true, true, lip, r);

		g.setColor(HOLE_DARK);
		g.fill(pit);
		g.dispose();
		return img;
	}

	/** The earth lip (bottom layer) under a hole; the pit sits on top of it. */
	public static BufferedImage holeFloor(String code, int variant) {
		return soilTile(seed(code, variant) ^ 0x9E3779B9L);
	}

	/**
	 * The floor bottom layer. It is always hidden under the procedural ground
	 * ({@link GroundTextures}) or a wall/pit, so it is just opaque soil -- the
	 * point is only that any hairline seam at a tile boundary reveals soil rather
	 * than the old blue floor sprite.
	 */
	public static BufferedImage floor() {
		return soilTile(0x50117L);
	}

	/** Opaque soil fill with a little grain, sized to the padded tile. */
	private static BufferedImage soilTile(long seed) {
		BufferedImage img = new BufferedImage(SZ, SZ, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		g.setColor(GroundTextures.SOIL);
		g.fillRect(X0, Y0, TS, TS);
		Random rng = new Random(seed);
		for (int i = 0; i < 40; i++) {
			int px = X0 + rng.nextInt(TS), py = Y0 + rng.nextInt(TS), rr = 1 + rng.nextInt(2);
			int d = rng.nextInt(24) - 12;
			g.setColor(new Color(cl(78 + d), cl(64 + d), cl(46 + d), 150));
			g.fillOval(px, py, rr, rr);
		}
		g.dispose();
		return img;
	}

	/** A ramp tile: an earthy slope, bright at the high end, with uphill chevrons. */
	public static BufferedImage ramp(String code, boolean up) {
		BufferedImage img = new BufferedImage(SZ, SZ, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = gfx(img);
		// up faces right (high on the right), down faces left.
		GradientPaint grad = up
				? new GradientPaint(X0, 0, RAMP_LO, X0 + TS, 0, RAMP_HI)
				: new GradientPaint(X0, 0, RAMP_HI, X0 + TS, 0, RAMP_LO);
		g.setPaint(grad);
		g.fillRect(X0, Y0, TS, TS);
		// Darker channel edges (top and bottom) so the ramp reads as a cut slope.
		g.setColor(new Color(0, 0, 0, 55));
		g.fillRect(X0, Y0, TS, 5);
		g.fillRect(X0, Y0 + TS - 5, TS, 5);
		// Chevrons pointing uphill (toward the high end).
		g.setColor(new Color(255, 255, 255, 150));
		g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int cy = Y0 + TS / 2, s = 10;
		for (int k = 0; k < 3; k++) {
			int cx = X0 + 16 + k * 16;
			if (up) {
				g.drawLine(cx - s, cy - s, cx + s, cy);
				g.drawLine(cx + s, cy, cx - s, cy + s);
			} else {
				g.drawLine(cx + s, cy - s, cx - s, cy);
				g.drawLine(cx - s, cy, cx + s, cy + s);
			}
		}
		g.dispose();
		return img;
	}

	// ---- geometry ----------------------------------------------------------

	/**
	 * The autotiled body shape for the tile footprint. {@code inset} shrinks the
	 * mass on each open (unconnected) side; {@code r} is the corner radius. Open
	 * corners round convex; corners whose two cardinals connect but whose diagonal
	 * does not round concave (wrapping an open pocket); everything else stays
	 * flush so like neighbours merge seamlessly.
	 */
	private static Area blob(boolean N, boolean E, boolean S, boolean W,
			boolean NW, boolean NE, boolean SE, boolean SW, int inset, int r) {
		// Connected sides extend 1px past the tile boundary so neighbouring masses
		// overlap and their anti-aliased edges don't leave a hairline seam.
		int ov = 1;
		int x = X0 + (W ? -ov : inset), y = Y0 + (N ? -ov : inset);
		int x2 = X0 + TS + (E ? ov : -inset), y2 = Y0 + TS + (S ? ov : -inset);
		Area a = new Area(new Rectangle(x, y, x2 - x, y2 - y));
		if (!N && !W) {
			a.subtract(convex(x, y, r, 0));
		}
		if (!N && !E) {
			a.subtract(convex(x2 - r, y, r, 1));
		}
		if (!S && !E) {
			a.subtract(convex(x2 - r, y2 - r, r, 2));
		}
		if (!S && !W) {
			a.subtract(convex(x, y2 - r, r, 3));
		}
		if (N && W && !NW) {
			a.subtract(concave(x, y, r));
		}
		if (N && E && !NE) {
			a.subtract(concave(x2, y, r));
		}
		if (S && E && !SE) {
			a.subtract(concave(x2, y2, r));
		}
		if (S && W && !SW) {
			a.subtract(concave(x, y2, r));
		}
		return a;
	}

	/** The corner sliver to remove for a convex round: the r-square minus its disc. */
	private static Area convex(int sx, int sy, int r, int corner) {
		Area sq = new Area(new Rectangle(sx, sy, r, r));
		double ex, ey; // disc centre = the square's inner corner
		switch (corner) {
		case 0: ex = sx + r; ey = sy + r; break; // TL
		case 1: ex = sx; ey = sy + r; break; // TR
		case 2: ex = sx; ey = sy; break; // BR
		default: ex = sx + r; ey = sy; break; // BL
		}
		sq.subtract(new Area(new Ellipse2D.Double(ex - r, ey - r, 2 * r, 2 * r)));
		return sq;
	}

	/** A quarter disc centred on a footprint corner, removed for a concave round. */
	private static Area concave(int px, int py, int r) {
		return new Area(new Ellipse2D.Double(px - r, py - r, 2 * r, 2 * r));
	}

	// ---- helpers -----------------------------------------------------------

	private static boolean has(String code, char c) {
		return code != null && code.indexOf(c) >= 0;
	}

	private static Graphics2D gfx(BufferedImage img) {
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		return g;
	}

	private static long seed(String code, int variant) {
		return ((long) (code == null ? 0 : code.hashCode())) * 2654435761L ^ (variant * 0x9E3779B1L);
	}

	private static int cl(int v) {
		return v < 0 ? 0 : (v > 255 ? 255 : v);
	}
}
