package net.hedinger.prototype.entities;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.View;

/**
 * A pressure-plate switch wired to a door: a non-living entity (like the
 * doors it drives) standing on a {@code TYPE_SWITCH} floor tile. Any grounded
 * body on the plate -- creature, corpse, or a crate somebody parked there --
 * presses it, and every pressed tick refreshes the door's open-hold, so the
 * door parts while the plate is weighted and seals a linger after it clears.
 * Several switches wired to one door compose naturally through that timer.
 *
 * <p>Wiring a switch marks its door as machinery ({@link Door#setWired}),
 * which stops the door's idle random self-cycling. Perception never sees a
 * switch (every scan filters to NPCs), and its entity size stays zero so
 * nothing mistakes it for a body.
 *
 * <p>Drawn as a wire decal -- an L-run of dark conduit with staples from the
 * plate to the door's centre, so the connection is readable at a glance --
 * plus the button itself: a lit steel dome when armed, pressed flush with an
 * amber live-ring while a body stands on it. The plate's housing is baked
 * into the ground tile; only these live parts draw here.
 */
public class Switch extends Entity {

	private static final Color CABLE = new Color(0x14161f);
	private static final Color STAPLE = new Color(0x515862);
	private static final Color DOME = new Color(0x8a93a0);
	private static final Color DOME_LIT = new Color(0xc6cdd8);
	private static final Color WELL = new Color(0x23262e);
	private static final Color LIVE = new Color(0xd8b028);

	private final Door door;
	private boolean pressed = false;

	public Switch(int x, int y, int z, Door door) {
		super(x, y, z, 0);
		this.door = door;
		door.setWired(true);
	}

	/** Whether a grounded body is weighting the plate right now. */
	public boolean isPressed() {
		return pressed;
	}

	/** The door this switch is wired to. */
	public Door getDoor() {
		return door;
	}

	@Override
	protected void think() {
		pressed = false;
		for (Entity e : getWorld().getEntities()) {
			// Grounded NPCs only (items included -- a parked crate holds the
			// plate down); flyers pass over without weighting it.
			if (!(e instanceof NPC) || e.isRemoved() || e.isFlying()) {
				continue;
			}
			if ((int) e.getZ() != (int) getZ()) {
				continue;
			}
			if ((int) e.getX() == (int) getX() && (int) e.getY() == (int) getY()) {
				pressed = true;
				break;
			}
		}
		if (pressed) {
			door.holdOpen();
		}
	}

	/** The wired door's centre point, where the conduit runs to. */
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

		// The conduit: an art-pixel march, x-leg first then y-leg, dark cable
		// with a lit staple every few pixels -- the decal that shows what
		// this plate is wired to.
		int n = 0;
		double px = sx, py = sy;
		while (Math.abs(dc[0] - px) > 1.0 / 24) {
			px += Math.signum(dc[0] - px) / 12.0;
			wirePixel(g2, v, px, py, box, n++);
		}
		while (Math.abs(dc[1] - py) > 1.0 / 24) {
			py += Math.signum(dc[1] - py) / 12.0;
			wirePixel(g2, v, px, py, box, n++);
		}

		// The button: a domed cap riding proud of the baked well when armed,
		// pressed flush and ringed amber while a body weights it.
		int bx = (int) Math.round(v.pixelX(sx - 2.0 / 12, (int) getZ(), 0));
		int by = (int) Math.round(v.pixelY(sy - 2.0 / 12, (int) getZ(), 0));
		int d = box * 4;
		if (pressed) {
			g2.setColor(LIVE);
			g2.fillRect(bx - box / 2, by - box / 2, d + box, d + box);
			g2.setColor(WELL);
			g2.fillRect(bx, by, d, d);
		} else {
			g2.setColor(DOME);
			g2.fillRect(bx, by, d, d);
			g2.setColor(DOME_LIT);
			g2.fillRect(bx, by, d, box); // the dome's lit north edge
			g2.setColor(WELL);
			g2.fillRect(bx, by + d - box / 2, d, Math.max(1, box / 2));
		}
	}

	private void wirePixel(Graphics2D g2, View v, double wx, double wy, int box, int n) {
		g2.setColor(n % 5 == 0 ? STAPLE : CABLE);
		g2.fillRect((int) Math.round(v.pixelX(wx, (int) getZ(), 0)),
				(int) Math.round(v.pixelY(wy, (int) getZ(), 0)), box, box);
	}

	@Override
	public String getEntityTypeName() {
		return "Switch";
	}
}
