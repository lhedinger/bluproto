package net.hedinger.prototype.entities;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.View;

/**
 * A gate spanning the boundary between two tiles: closed it seals the edge
 * (the tile door flags feed collision), open it retracts to stub posts, and
 * it slides smoothly between the two. Drawn procedurally on the render's
 * 12-per-tile art-pixel grid -- no sprites -- in four material flavours:
 *
 * <ul>
 *   <li>{@link #TIMBER}: planked wood in the mud-ramp browns, seamed and
 *       framed -- the door of a built ruin.</li>
 *   <li>{@link #STONE}: a coursed masonry slab in the built-wall tones.</li>
 *   <li>{@link #GRATE}: iron bars in the pit-dark shades with lit tips --
 *       and honest gaps, so the eye (like the sim) sees through it.</li>
 *   <li>{@link #HEDGE}: a living brush gate in the thicket greens.</li>
 * </ul>
 *
 * A flavour can be given explicitly; the legacy constructor derives one from
 * the door's position, so old world-gen call sites scatter variety for free.
 */
public class Door extends Entity {

	public static final int TIMBER = 0, STONE = 1, GRATE = 2, HEDGE = 3, BLAST = 4;

	private static final int DOOR_OPEN = 0;
	private static final int DOOR_MOVING = 1;
	private static final int DOOR_CLOSED = 2;

	// Material palettes, drawn from the terrain ramps so doors sit in the
	// same world: mud browns (timber), built-wall masonry (stone), pit darks
	// + wall highlight (iron), thicket greens (hedge).
	private static final Color FRAME = new Color(0x38291a);
	private static final Color TIMBER_MID = new Color(0x574024);
	private static final Color TIMBER_HI = new Color(0x775a38);
	private static final Color MORTAR = new Color(0x36342e);
	private static final Color STONE_MID = new Color(0x665e4c);
	private static final Color STONE_HI = new Color(0x8a8069);
	private static final Color IRON_DARK = new Color(0x14161f);
	private static final Color IRON_HI = new Color(0x7c828f);
	private static final Color STEEL_MID = new Color(0x515862);
	private static final Color STEEL_HI = new Color(0x707885);
	private static final Color HEDGE_MID = new Color(0x2b5422);
	private static final Color HEDGE_HI = new Color(0x456c36);

	private int status = DOOR_CLOSED;
	private int delay_counter = 0;
	private boolean triggered = false;
	private final int flavor;
	private final int span; // doorway width in tiles the door seals
	private final int open_delay, close_delay;

	public Door(double x, double y, double z, int d) {
		this(x, y, z, d, flavorAt((int) x, (int) y, (int) z));
	}

	public Door(double x, double y, double z, int d, int flavor) {
		this(x, y, z, d, flavor, 1);
	}

	/**
	 * A door sealing a doorway {@code span} tiles wide: (X,Y) is the first
	 * doorway tile and the door extends toward +x (ud) or +y (lr). The two
	 * leaves slide apart from the centre into the flanking walls -- a wide
	 * blast door parts like a bay door, it never swings.
	 */
	public Door(double x, double y, double z, int d, int flavor, int span) {
		super((int) x, (int) y, (int) z, d % 2);
		this.span = Math.max(1, span);
		this.size_diameter = 64 * this.span;
		this.flavor = flavor;
		// A blast door is tons of steel: it takes its time.
		this.open_delay = flavor == BLAST ? -90 : -40;
		this.close_delay = flavor == BLAST ? 90 : 40;
	}

	/** Asks the door to start opening (if closed) or closing (if open) on
	 *  its next think -- the hook a switch, a sensor, or a demo pulls. */
	public void trigger() {
		triggered = true;
	}

	/** Position-derived flavour for legacy call sites: the built materials
	 *  only (a hedge gate has no business in a generated bunker). */
	private static int flavorAt(int x, int y, int z) {
		return (int) (GroundTextures.hash01(x * 31 + z, y * 17, 58) * 2.999);
	}

	@Override
	protected void think() {
		int r = (int) (this.D % (Math.PI / 2));

		if (Utils.random() * 600 < 1) {
			triggered = true;
		}

		if (delay_counter == 0) {
			if (triggered) {
				triggered = false;
				if (status == DOOR_CLOSED) {
					delay_counter = open_delay;
				} else if (status == DOOR_OPEN) {
					delay_counter = close_delay;
				}
			}
		} else {
			status = DOOR_MOVING;
			if (delay_counter < 0) {
				delay_counter++; // opening
				if (delay_counter == 0) {
					status = DOOR_OPEN;
				}
			} else {
				delay_counter--; // closing
				if (delay_counter == 0) {
					status = DOOR_CLOSED;
				}
			}
		}

		if (status == DOOR_CLOSED || status == DOOR_OPEN) {
			applyFlags(status == DOOR_OPEN);
		}
	}

	/** Applies the open/closed collision flags to every tile pair the door
	 *  spans (a wide door seals its whole doorway). */
	private void applyFlags(boolean open) {
		int r = (int) (this.D % (Math.PI / 2));
		for (int s = 0; s < span; s++) {
			if (r == 0) {
				if (open) {
					getWorld().getTile(X + s, Y, Z).openDoor(0);
					getWorld().getTile(X + s, Y - 1, Z).openDoor(2);
				} else {
					getWorld().getTile(X + s, Y, Z).closeDoor(0);
					getWorld().getTile(X + s, Y - 1, Z).closeDoor(2);
				}
			} else {
				if (open) {
					getWorld().getTile(X - 1, Y + s, Z).openDoor(1);
					getWorld().getTile(X, Y + s, Z).openDoor(3);
				} else {
					getWorld().getTile(X - 1, Y + s, Z).closeDoor(1);
					getWorld().getTile(X, Y + s, Z).closeDoor(3);
				}
			}
		}
	}

	/** Snaps the door fully open or closed with no transit -- placement-time
	 *  setup, so a ruin can stand with its gate ajar from the first tick.
	 *  The tile collision flags follow on the door's next think. */
	public void snap(boolean open) {
		status = open ? DOOR_OPEN : DOOR_CLOSED;
		delay_counter = 0;
		triggered = false;
	}

	/**
	 * How far the leaves reach toward the middle: 1 sealed, ~0.15 open (the
	 * stubs by the posts), sliding smoothly through the transition -- the
	 * old sprite door blinked instead.
	 */
	private double extension() {
		if (status == DOOR_CLOSED) {
			return 1;
		}
		if (status == DOOR_OPEN) {
			return 0.15;
		}
		if (delay_counter < 0) {
			return Math.max(0.15, delay_counter / (double) open_delay); // opening: 1 -> 0
		}
		return Math.max(0.15, 1 - delay_counter / (double) close_delay); // closing: 0 -> 1
	}

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		boolean lr = ((int) (D % (Math.PI / 2))) != 0;
		double step = (v.pixelX(X + 1, Z, 0) - v.pixelX(X, Z, 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		int L = span * 12; // door length in art-px across its whole doorway
		int reach = (int) Math.round(L * 0.5 * extension()); // leaf length from each end
		int rows = flavor == BLAST ? 5 : 3; // a blast door is a mass, not a bar
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
				Color c = pattern(i, L, j, post, nose);
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

	private static final Color SHADOW = new Color(0, 0, 0, 110);

	private static Color shade(Color c, double f) {
		int r = (int) Math.min(255, c.getRed() * f);
		int g = (int) Math.min(255, c.getGreen() * f);
		int b = (int) Math.min(255, c.getBlue() * f);
		return new Color(r, g, b, c.getAlpha());
	}

	/** The material pattern for leaf art-pixel (i along a door of L art-px,
	 *  j across); {@code nose} marks a leaf's sliding leading edge. */
	private Color pattern(int i, int L, int j, boolean post, boolean nose) {
		switch (flavor) {
		case STONE:
			if (post || i % 4 == 0) {
				return MORTAR; // frame and joints
			}
			return GroundTextures.hash01(i / 4, j + (int) (X + Y), 59) > 0.6 ? STONE_HI : STONE_MID;
		case GRATE:
			if (post) {
				return IRON_DARK;
			}
			if (i % 2 == 1) {
				return null; // gap between bars: see straight through
			}
			return j == 0 ? IRON_HI : IRON_DARK; // lit bar tip, dark shaft
		case BLAST:
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
		case HEDGE: {
			// Woven wicker: a basket-weave of living green and timber withies
			// on dark posts, so the gate reads as BUILT brush -- otherwise a
			// closed hedge gate vanishes into the hedge it hangs in.
			if (post) {
				return FRAME;
			}
			if (GroundTextures.hash01(i + (int) X * 7, j + (int) Y * 5, 60) > 0.85) {
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

	@Override
	public String getEntityTypeName() {
		return "Door";
	}
}
