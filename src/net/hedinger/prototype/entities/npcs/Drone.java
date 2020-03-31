package net.hedinger.prototype.entities.npcs;

import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Weapon;
import net.hedinger.prototype.entities.weapons.Gattlingun;

public class Drone extends NPC {
	private static final double DRONE_RANGE = 5; // los range (pixels)
	private static final double DRONE_FOV = Math.PI; // los range
	// (pixels)
	private static final double DRONE_SPEED = 0.07;
	private static final int DRONE_TURN = 10; // max turn speed
	private static final int DRONE_SF = 10;
	private Weapon weapon = null;
	private boolean malfunction = false;

	private String[] DRONE_IGNORE = { "Entity.NPC.Sentry", "Entity.NPC.Drone",
			"Entity.NPC.Soldier", "Entity.NPC.Human", "Entity.NPC.Spore" };

	private TreeMap<Double, NPC> enemies = null;
	private NPC enemy = null;

	public Drone(double x, double y, double z) {
		super(x, y, z);
		hostile = 0;
		size = 6;
		health = 100;
		SEARCH_FREQ = DRONE_SF;
		LOS_RANGE = DRONE_RANGE;
		LOS_FOV = DRONE_FOV;
		drawLOS = true;
		weapon = new Gattlingun(this);
	}

	@Override
	public void think() {
		if (weapon != null) {
			weapon.think();
		}

		if (health < 75) {
			hostile = 2;
			// malfunction = true;
		}

		// gets all targets within range and los
		// return true if any are found

		if (malfunction) {
			enemies = getTargets(enemies, getType(), false);
			detected = 2;
			selected = false;
			path = null;
		} else {
			if (selected) {
				say("[x]", 1);
			} else {
				say("[ ]", 1);
			}

			enemies = getTargets(enemies, DRONE_IGNORE, false);
		}

		if (!enemies.isEmpty()) {
			say("[!]", 200);
			enemy = enemies.firstEntry().getValue();
			drawPing = true;
		} else if (!malfunction) {
			drawPing = true;
		}

		if (pathFinding) {
			say(">>!", 20);
			if (!followPath2(DRONE_SPEED, DRONE_TURN)) {
				pathFinding = false;
			}
		} else if (seeTarget(enemy, getType(), false)) {
			lockTarget(enemy);
			if (!selected) {
				chase(0, DRONE_TURN);
			} else {
				chase(DRONE_SPEED, DRONE_TURN);
			}
			if (isInLOS(DRONE_RANGE, Math.PI * 0.1)) {
				say(">!<", 15);
				if (weapon != null) {
					weapon.use(distance());
				}
			}
		} else {
			if (malfunction) {
				roam(DRONE_SPEED, DRONE_TURN);
			} else {
				say("[?]", 10);
				if (!selected) {
					roam(DRONE_SPEED, DRONE_TURN);
				} else {
					idle();
				}
			}
		}
	}

	private void idle() {
		tX = X;
		tY = Y;
		tZ = Z;
	}

	public boolean selected() {
		return selected;
	}

	@Override
	public void select() {
		if (!malfunction) {
			selected = true;
		}
	}

	@Override
	public void unselect() {
		selected = false;
	}

	private boolean pathFinding = false;

	public void gotoDest(double x, double y) {
		if (!selected) {
			return;
		}

		if (!getWorld().isValid(x, y, Z)) {
			return;
		}

		generatePath(x, y, Z);
		pathFinding = true;
	}

	@Override
	public String NPCType() {
		return "Drone";
	}
}
