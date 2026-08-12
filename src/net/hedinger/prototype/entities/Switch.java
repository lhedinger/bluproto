package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Entity;

/**
 * A switch wired to a door: a non-living entity (like the doors it drives)
 * standing on a {@code TYPE_SWITCH} floor tile, in two modes:
 *
 * <ul>
 *   <li>{@link #PLATE} -- a weight-driven pressure plate, the test-chamber
 *       floor button: any grounded body on it presses it, creature, corpse,
 *       or a crate somebody parked there. Flyers pass over.</li>
 *   <li>{@link #BUTTON} -- an intent-driven push button: a body on or beside
 *       it must deliberately operate it (the {@code A_INTERACT} actuator, or a
 *       fixture's standing order). Standing on it does nothing -- interaction is a
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

	/** Whether the switch is held right now (weight or deliberate interaction). */
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
				if (Math.abs(dx) <= 1 && Math.abs(dy) <= 1 && n.wantsInteract()) {
					pressed = true;
					break;
				}
			}
		}
		if (pressed) {
			door.holdOpen();
		}
	}

	@Override
	public String getEntityTypeName() {
		return "Switch";
	}
}
