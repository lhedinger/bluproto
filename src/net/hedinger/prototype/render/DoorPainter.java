package net.hedinger.prototype.render;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.entities.Door;

/**
 * Paints a door: two sliding leaves of flavour-patterned art-pixels (timber
 * planks, mortared stone, see-through grate bars, woven hedge wicker, or
 * segmented blast steel with a hazard-striped nose), each block dropped over
 * its own shadow and rimmed with the walls' raised-edge grammar. Verbatim port
 * of the old {@code Door.draw}; the material palette lives on {@link Door} as
 * plain RGB ints (they double as the wire colours), wrapped here for AWT.
 */
final class DoorPainter {

	private static final Color SHADOW = new Color(0, 0, 0, 110);
	private static final Color FRAME = new Color(Door.FRAME_RGB);
	private static final Color TIMBER_MID = new Color(Door.TIMBER_MID_RGB);
	private static final Color TIMBER_HI = new Color(Door.TIMBER_HI_RGB);
	private static final Color MORTAR = new Color(Door.MORTAR_RGB);
	private static final Color STONE_MID = new Color(Door.STONE_MID_RGB);
	private static final Color STONE_HI = new Color(Door.STONE_HI_RGB);
	private static final Color IRON_DARK = new Color(Door.IRON_DARK_RGB);
	private static final Color IRON_HI = new Color(Door.IRON_HI_RGB);
	private static final Color STEEL_MID = new Color(Door.STEEL_MID_RGB);
	private static final Color STEEL_HI = new Color(Door.STEEL_HI_RGB);
	private static final Color HEDGE_MID = new Color(Door.HEDGE_MID_RGB);
	private static final Color HEDGE_HI = new Color(Door.HEDGE_HI_RGB);

	static void draw(Door door, Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double X = door.getX(), Y = door.getY(), Z = door.getZ();
		boolean lr = ((int) (door.getDirection() % (Math.PI / 2))) != 0;
		double step = (v.pixelX(X + 1, Z, 0) - v.pixelX(X, Z, 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		int L = door.getSpan() * 12; // door length in art-px across its whole doorway
		int reach = (int) Math.round(L * 0.5 * door.extension()); // leaf length from each end
		int rows = door.getFlavor() == Door.BLAST ? 5 : 3; // a blast door is a mass, not a bar
		// Pass 1: the drop shadow, one art-pixel south of every door block --
		// the slab sits ON the floor, and the shadow slides with the leaves.
		g2.setColor(SHADOW);
		for (int i = 0; i < L; i++) {
			if (!drawnCell(i, L, reach)) {
				continue;
			}
			for (int j = 0; j < rows; j++) {
				double along = i / 12.0;
				double across = (j - rows * 0.5) / 12.0;
				double wx = lr ? X + across : X + along;
				double wy = (lr ? Y + along : Y + across) + 1.0 / 12.0;
				g2.fillRect((int) Math.round(v.pixelX(wx, Z, 0)),
						(int) Math.round(v.pixelY(wy, Z, 0)), box, box);
			}
		}
		// Pass 2: the door itself, its screen-north edge lit and its south
		// edge sunk -- the same raised grammar the walls use.
		for (int i = 0; i < L; i++) {
			if (!drawnCell(i, L, reach)) {
				continue; // the open middle
			}
			boolean post = i == 0 || i == L - 1;
			// The leading edge of each sliding leaf -- where they meet when
			// sealed, and the crush edges while they move.
			boolean nose = !post && (i == reach - 1 || i == L - reach);
			for (int j = 0; j < rows; j++) {
				Color c = pattern(door, i, L, j, post, nose);
				if (c == null) {
					continue; // a grate's see-through gap
				}
				boolean northEdge = lr ? !drawnCell(i - 1, L, reach) : j == 0;
				boolean southEdge = lr ? !drawnCell(i + 1, L, reach) : j == rows - 1;
				boolean sideEdge = lr ? (j == 0 || j == rows - 1)
						: (!drawnCell(i - 1, L, reach) || !drawnCell(i + 1, L, reach));
				if (northEdge) {
					c = shade(c, 1.3);
				} else if (southEdge) {
					c = shade(c, 0.65);
				} else if (sideEdge) {
					c = shade(c, 0.78); // the slab's long flanks, rimmed like a wall's
				}
				double along = i / 12.0; // world units along the doorway
				double across = (j - rows * 0.5) / 12.0;
				double wx = lr ? X + across : X + along;
				double wy = lr ? Y + along : Y + across;
				g2.setColor(c);
				g2.fillRect((int) Math.round(v.pixelX(wx, Z, 0)),
						(int) Math.round(v.pixelY(wy, Z, 0)), box, box);
			}
		}
	}

	/** Whether art-pixel {@code i} along the door is part of a leaf or post. */
	private static boolean drawnCell(int i, int L, int reach) {
		if (i < 0 || i >= L) {
			return false;
		}
		return i == 0 || i == L - 1 || i < reach || i >= L - reach;
	}

	private static Color shade(Color c, double f) {
		int r = (int) Math.min(255, c.getRed() * f);
		int g = (int) Math.min(255, c.getGreen() * f);
		int b = (int) Math.min(255, c.getBlue() * f);
		return new Color(r, g, b, c.getAlpha());
	}

	/** The material pattern for leaf art-pixel (i along a door of L art-px,
	 *  j across); {@code nose} marks a leaf's sliding leading edge. */
	private static Color pattern(Door door, int i, int L, int j, boolean post, boolean nose) {
		int X = (int) door.getX(), Y = (int) door.getY();
		switch (door.getFlavor()) {
		case Door.STONE:
			if (post || i % 4 == 0) {
				return MORTAR; // frame and joints
			}
			return GroundTextures.hash01(i / 4, j + (int) (door.getX() + door.getY()), 59) > 0.6
					? STONE_HI : STONE_MID;
		case Door.GRATE:
			if (post) {
				return IRON_DARK;
			}
			if (i % 2 == 1) {
				return null; // gap between bars: see straight through
			}
			return j == 0 ? IRON_HI : IRON_DARK; // lit bar tip, dark shaft
		case Door.BLAST:
			// Segmented steel with a hazard-striped nose where the leaves
			// meet: the Black-Mesa-grade door for facility mouths.
			if (post) {
				return IRON_DARK;
			}
			if (nose) {
				return new Color(GroundTextures.hazardStripe(i, j)); // striped crush edge
			}
			if (i % 4 == 0) {
				return IRON_DARK; // segment seam
			}
			if (j == 4) {
				return IRON_DARK; // shadowed trailing edge of the slab
			}
			return j <= 1 ? STEEL_HI : STEEL_MID; // broad lit face, steel body
		case Door.HEDGE: {
			// Woven wicker: a basket-weave of living green and timber withies
			// on dark posts, so the gate reads as BUILT brush -- otherwise a
			// closed hedge gate vanishes into the hedge it hangs in.
			if (post) {
				return FRAME;
			}
			if (GroundTextures.hash01(i + X * 7, j + Y * 5, 60) > 0.85) {
				return HEDGE_HI; // a live sprig poking from the weave
			}
			return ((i + j) & 1) == 0 ? HEDGE_MID : TIMBER_MID;
		}
		default: // TIMBER
			if (post || i % 3 == 0) {
				return FRAME; // posts and plank seams
			}
			return ((i / 3) & 1) == 0 ? TIMBER_MID : TIMBER_HI;
		}
	}

	private DoorPainter() {
	}
}
