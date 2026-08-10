package net.hedinger.prototype.entities;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;

/**
 * A switch wired to a door: a non-living entity (like the doors it drives)
 * standing on a {@code TYPE_SWITCH} floor tile, in two modes:
 *
 * <ul>
 *   <li>{@link #PLATE} -- a weight-driven pressure plate, the test-chamber
 *       floor button: any grounded body on it presses it, creature, corpse,
 *       or a crate somebody parked there. Flyers pass over.</li>
 *   <li>{@link #BUTTON} -- an intent-driven push button: a body on or beside
 *       it must deliberately operate it (the {@code A_USE} actuator, or a
 *       fixture's standing order). Standing on it does nothing -- use is a
 *       choice, which is the entire point.</li>
 * </ul>
 *
 * Every pressed tick refreshes the door's open-hold, so the door parts while
 * the switch is held and seals a linger after it goes quiet; several switches
 * wired to one door compose through that one timer. Wiring a switch marks its
 * door as machinery ({@link Door#setWired}), stopping the idle self-cycling.
 * Perception never sees a switch, and its entity size stays zero.
 *
 * <p>Drawn test-chamber style: an indicator trail of dotted lights running
 * from the switch to the door's centre -- dim while idle, lit while the
 * circuit is closed -- and the control itself over the baked pedestal base: a
 * broad red disc for a plate (sinking flush when weighted), a small domed red
 * cap on a dark pedestal for a button.
 */
public class Switch extends Entity {

	public static final int PLATE = 0, BUTTON = 1;

	private static final Color LAMP_HOUSING = new Color(0x14161f);
	private static final Color TRAIL_DIM = new Color(0x6a7280);
	private static final Color TRAIL_LIT = new Color(0xD0ECFF);
	private static final Color BTN_RED = new Color(0xE0455F);
	private static final Color BTN_RED_DARK = new Color(0x7c2434);
	private static final Color BTN_RED_LIT = new Color(0xF0788C);
	private static final Color PEDESTAL = new Color(0x2c3037);

	private final Door door;
	private final int mode;
	private boolean pressed = false;

	public Switch(int x, int y, int z, Door door) {
		this(x, y, z, door, PLATE);
	}

	public Switch(int x, int y, int z, Door door, int mode) {
		super(x, y, z, 0);
		this.door = door;
		this.mode = mode;
		door.setWired(true);
	}

	/** Whether the switch is held right now (weight or deliberate use). */
	public boolean isPressed() {
		return pressed;
	}

	/** {@link #PLATE} or {@link #BUTTON}. */
	public int getMode() {
		return mode;
	}

	/** The door this switch is wired to. */
	public Door getDoor() {
		return door;
	}

	@Override
	protected void think() {
		pressed = false;
		for (Entity e : getWorld().getEntities()) {
			if (!(e instanceof NPC n) || e.isRemoved()) {
				continue;
			}
			if ((int) e.getZ() != (int) getZ()) {
				continue;
			}
			int dx = (int) e.getX() - (int) getX(), dy = (int) e.getY() - (int) getY();
			if (mode == PLATE) {
				// Weight: any grounded body on the plate (items included -- a
				// parked crate holds it down); flyers pass over unfelt.
				if (dx == 0 && dy == 0 && !e.isFlying()) {
					pressed = true;
					break;
				}
			} else {
				// Intent: a body at or beside the pedestal, deliberately
				// operating it. Weight alone does nothing.
				if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && n.wantsUse()) {
					pressed = true;
					break;
				}
			}
		}
		if (pressed) {
			door.holdOpen();
		}
	}

	/** The wired door's centre point, where the indicator trail runs to. */
	private double[] doorCentre() {
		boolean lr = ((int) (door.getDirection() % (Math.PI / 2))) != 0;
		double half = door.getSpan() * 0.5;
		return lr ? new double[] { door.getX(), door.getY() + half }
				: new double[] { door.getX() + half, door.getY() };
	}

	@Override
	protected void draw(Graphics g, View v) {
		Graphics2D g2 = (Graphics2D) g;
		double step = (v.pixelX((int) getX() + 1, (int) getZ(), 0)
				- v.pixelX((int) getX(), (int) getZ(), 0)) / 12.0;
		if (step <= 0) {
			return;
		}
		int box = (int) Math.ceil(step);
		double sx = getX() + 0.5, sy = getY() + 0.5;
		double[] dc = doorCentre();

		// The indicator trail: lamps every few art-pixels along the L-run
		// (x-leg first) from switch to door -- each a dark housing around a
		// lens, dim while idle, lit while the circuit is closed. The
		// test-chamber "what does this operate" line, readable on any floor.
		int n = 0;
		double px = sx, py = sy;
		while (Math.abs(dc[0] - px) > 1.0 / 24) {
			px += Math.signum(dc[0] - px) / 12.0;
			if (n++ % 4 == 0) {
				trailLamp(g2, v, px, py, box);
			}
		}
		while (Math.abs(dc[1] - py) > 1.0 / 24) {
			py += Math.signum(dc[1] - py) / 12.0;
			if (n++ % 4 == 0) {
				trailLamp(g2, v, px, py, box);
			}
		}

		int z = (int) getZ();
		if (mode == PLATE) {
			// The floor button: a broad red disc riding proud of its seat,
			// sinking flush (darker) while weighted.
			int bx = (int) Math.round(v.pixelX(sx - 3.0 / 12, z, 0));
			int by = (int) Math.round(v.pixelY(sy - 3.0 / 12, z, 0));
			int d = box * 6;
			g2.setColor(pressed ? BTN_RED_DARK : BTN_RED);
			g2.fillRect(bx, by + box / 2, d, d - box); // disc, squarish-round
			g2.fillRect(bx + box / 2, by, d - box, d);
			if (!pressed) {
				g2.setColor(BTN_RED_LIT);
				g2.fillRect(bx + box, by + box / 2, d - box * 2, box); // lit north arc
			}
		} else {
			// The pedestal button: a dark base with a small domed red cap --
			// pressed, the cap sinks dark and the pedestal rim lights.
			int bx = (int) Math.round(v.pixelX(sx - 2.0 / 12, z, 0));
			int by = (int) Math.round(v.pixelY(sy - 2.0 / 12, z, 0));
			int d = box * 4;
			g2.setColor(PEDESTAL);
			g2.fillRect(bx - box / 2, by - box / 2, d + box, d + box);
			g2.setColor(pressed ? BTN_RED_DARK : BTN_RED);
			g2.fillRect(bx + box / 2, by + box / 2, d - box, d - box);
			if (!pressed) {
				g2.setColor(BTN_RED_LIT);
				g2.fillRect(bx + box, by + box / 2, d - box * 2, box); // dome highlight
			} else {
				g2.setColor(TRAIL_LIT);
				g2.fillRect(bx - box / 2, by - box / 2, d + box, box / 2 + 1); // lit rim
			}
		}
	}

	/** One trail lamp: a dark housing with a centred lens. */
	private void trailLamp(Graphics2D g2, View v, double wx, double wy, int box) {
		int lx = (int) Math.round(v.pixelX(wx, (int) getZ(), 0));
		int ly = (int) Math.round(v.pixelY(wy, (int) getZ(), 0));
		g2.setColor(LAMP_HOUSING);
		g2.fillRect(lx - box / 2, ly - box / 2, box * 2, box * 2);
		g2.setColor(pressed ? TRAIL_LIT : TRAIL_DIM);
		g2.fillRect(lx, ly, box, box);
	}

	@Override
	public String getEntityTypeName() {
		return "Switch";
	}
}
