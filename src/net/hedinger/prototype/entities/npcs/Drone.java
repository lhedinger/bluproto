package net.hedinger.prototype.entities.npcs;

import java.util.TreeMap;

import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Weapon;
import net.hedinger.prototype.entities.weapons.Gattlingun;

public class Drone extends NPC {
	private static final double DRONE_RANGE = 5; // los range (pixels)
	private static final double DRONE_FOV = 2 * Math.PI; // los range
	// (pixels)
	private static final double DRONE_SPEED = 0.07;
	private static final int DRONE_TURN = 10; // max turn speed
	private static final int DRONE_SF = 30;
	private static final int PATROL_COOLDOWN = 1000;
	private Weapon weapon = null;

	private int patrol = PATROL_COOLDOWN;

	private String[] DRONE_IGNORE = { "Entity.NPC.Sentry", "Entity.NPC.Drone",
			"Entity.NPC.Soldier", "Entity.NPC.Human", "Entity.NPC.Spore" };

	private String[] ABDUCT = { "Entity.NPC.Human" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> humans = null;
	private NPC enemy = null;
	private NPC human = null;

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
		flying = true;
	}

	@Override
	public void think() {
		if (weapon != null) {
			weapon.think();
		}

		// gets all targets within range and los
		// return true if any are found

		if (selected) {
			say("[x]", 1);
		} else {
			say("[ ]", 1);
		}

		if (patrol < 0 && !pathFinding) {

			int x = Utils.random(getWorld().getColums());
			int y = Utils.random(getWorld().getRows());

			gotoDest(x, y);
		}

		enemies = getTargets(enemies, DRONE_IGNORE, false);
		humans = getTargets(humans, ABDUCT, true);

		if (!enemies.isEmpty()) {
			say("[!]", 200);
			enemy = enemies.firstEntry().getValue();
			drawPing = true;
		}

		if (pathFinding) {
			say(">>!", 20);
			if (!followPath2(DRONE_SPEED, DRONE_TURN)) {
				pathFinding = false;
				patrol = PATROL_COOLDOWN;
				drop();
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
		} else if (!humans.isEmpty()) {
			say("[@]", 10);
			human = humans.firstEntry().getValue();
			lockTarget(human);
			chase(DRONE_SPEED * 0.5f, DRONE_TURN);

			if (canTouch(human)) {
				say("[<3]", 100);
				grab(human);
				patrol = -1;
			}

		} else {
			say("[?]", 10);
			if (!selected) {
				patrol--;
				roam(DRONE_SPEED, DRONE_TURN);
			} else {
				idle();
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
		selected = true;
	}

	@Override
	public void unselect() {
		selected = false;
	}

	private boolean pathFinding = false;

	public void gotoDest(double x, double y) {

		if (!getWorld().isValid(x, y, Z)) {
			return;
		}

		generatePath(x, y, Z);
		pathFinding = true;
	}

	@Override
	public String getNpcTypeName() {
		return "Drone";
	}
}
