package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.GroundTextures;
import net.hedinger.prototype.engine.Utils;

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

	// Material palettes as plain RGB, drawn from the terrain ramps so doors
	// sit in the same world: mud browns (timber), built-wall masonry (stone),
	// pit darks + wall highlight (iron), thicket greens (hedge). The render
	// layer's DoorPainter wraps these for AWT; the mids double as the wire
	// colour a spriteless client falls back to.
	public static final int FRAME_RGB = 0x38291a;
	public static final int TIMBER_MID_RGB = 0x574024;
	public static final int TIMBER_HI_RGB = 0x775a38;
	public static final int MORTAR_RGB = 0x36342e;
	public static final int STONE_MID_RGB = 0x665e4c;
	public static final int STONE_HI_RGB = 0x8a8069;
	public static final int IRON_DARK_RGB = 0x14161f;
	public static final int IRON_HI_RGB = 0x7c828f;
	public static final int STEEL_MID_RGB = 0x515862;
	public static final int STEEL_HI_RGB = 0x707885;
	public static final int HEDGE_MID_RGB = 0x2b5422;
	public static final int HEDGE_HI_RGB = 0x456c36;

	private int status = DOOR_CLOSED;
	private int delay_counter = 0;
	private boolean triggered = false;
	private final int flavor;
	private final int span; // doorway width in tiles the door seals
	private final int open_delay, close_delay;

	/** A wired door is machinery, not weather: it stops the idle random
	 *  self-cycling and answers only its switches (and explicit triggers). */
	private boolean wired = false;
	/** Ticks of open-hold remaining; refreshed every tick a wired switch is
	 *  pressed, so the door closes only after every plate has gone quiet. */
	private int holdTimer = 0;
	/** How long the hold outlasts the last press (~3 s), so a body crossing
	 *  the plate gets through before the leaves come back. */
	private static final int HOLD = 100;

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
	 *  its next think -- the hook a sensor or a demo pulls. */
	public void trigger() {
		triggered = true;
	}

	/** Marks this door as switch-operated. Called by the switch that wires
	 *  itself to the door, so placement stays one line per switch. */
	public void setWired(boolean w) {
		wired = w;
	}

	/**
	 * A wired switch's per-tick request: keep (or get) this door open. Every
	 * press refreshes the same hold timer, so several plates wired to one
	 * door compose naturally -- it closes only when all have been quiet for
	 * the linger.
	 */
	public void holdOpen() {
		holdTimer = HOLD;
	}

	public boolean isOpen() {
		return status == DOOR_OPEN;
	}

	public boolean isClosed() {
		return status == DOOR_CLOSED;
	}

	/** Position-derived flavour for legacy call sites: the built materials
	 *  only (a hedge gate has no business in a generated bunker). */
	private static int flavorAt(int x, int y, int z) {
		return (int) (GroundTextures.hash01(x * 31 + z, y * 17, 58) * 2.999);
	}

	@Override
	protected void think() {
		int r = (int) (this.D % (Math.PI / 2));

		if (wired) {
			// Machinery: open while any switch holds it, close after the
			// linger runs out. Draws no RNG at all.
			if (holdTimer > 0) {
				holdTimer--;
				if (status == DOOR_CLOSED && delay_counter == 0) {
					triggered = true;
				}
			} else if (status == DOOR_OPEN && delay_counter == 0) {
				triggered = true;
			}
		} else if (Utils.random() * 600 < 1) {
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

	/** Doorway width in tiles this door seals. */
	public int getSpan() {
		return span;
	}

	/** Wire tag for the viewer ("door.<flavour>" selects the glyph). */
	public String flavorName() {
		switch (flavor) {
		case STONE:
			return "stone";
		case GRATE:
			return "grate";
		case HEDGE:
			return "hedge";
		case BLAST:
			return "blast";
		default:
			return "timber";
		}
	}

	/** The flavour's body tone for the wire, so a client that has no door
	 *  glyph yet still draws a plausible bar. */
	public int wireColor() {
		switch (flavor) {
		case STONE:
			return STONE_MID_RGB;
		case GRATE:
			return IRON_HI_RGB;
		case HEDGE:
			return HEDGE_MID_RGB;
		case BLAST:
			return STEEL_MID_RGB;
		default:
			return TIMBER_MID_RGB;
		}
	}

	/** Material flavour ({@link #TIMBER} .. {@link #BLAST}), for the painter. */
	public int getFlavor() {
		return flavor;
	}

	/**
	 * How far the leaves reach toward the middle: 1 sealed, ~0.15 open (the
	 * stubs by the posts), sliding smoothly through the transition -- the
	 * old sprite door blinked instead.
	 */
	public double extension() {
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
	public String getEntityTypeName() {
		return "Door";
	}
}
