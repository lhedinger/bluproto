package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.engine.Utils;

import java.awt.Color;
import java.util.TreeMap;

import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Sound;

public class Zombie extends NPC {
	private static final Color ZOMBIE_COLOR = new Color(0, 150, 0);
	private static final int ZOMBIE_SF = 50;
	private static final int ZOMBIE_MAX_AGE = 10000;

	private static final String[] ZOMBIE_ENEMIES = { "Entity.NPC.Sentry", "Entity.NPC.Drone", "Entity.NPC.Soldier",
			"Entity.NPC.Human", "Entity.NPC.Elite" };

	private static final String[] ZOMBIE_ALLIES = { "Entity.NPC.Zombie",
	"Entity.NPC.Human_Infected" };

	private TreeMap<Double, NPC> enemies = null;
	private TreeMap<Double, NPC> allies = null;
	private NPC enemy = null;
	private NPC ally = null;

	private boolean dormant = true;

	private int status_alert = 0;

	public Zombie(double x, double y, double z) {
		super(x, y, z);
		applyGenome(Genome.phenotype(6, 0.04, 3, 4, Math.PI, ZOMBIE_MAX_AGE));
		col = ZOMBIE_COLOR;
		health = 200;
		lifespan = ZOMBIE_MAX_AGE;
		deathspan = (int) (ZOMBIE_MAX_AGE * 0.5);
		age = (int) (ZOMBIE_MAX_AGE * 0.5);
		SEARCH_FREQ = ZOMBIE_SF;
	}

	public Zombie(double x, double y, double z, double d) {
		super(x, y, z, d);
		applyGenome(Genome.phenotype(6, 0.04, 3, 4, Math.PI, ZOMBIE_MAX_AGE));
		col = ZOMBIE_COLOR;
		health = 200;
		lifespan = ZOMBIE_MAX_AGE;
		deathspan = (int) (ZOMBIE_MAX_AGE * 0.5);
		age = (int) (ZOMBIE_MAX_AGE * 0.5);
		SEARCH_FREQ = ZOMBIE_SF;
	}

	@Override
	protected void think() {

		if (dormant) {
			if (lastHeardSound != null) {
				lastHeardSound = null;
				dormant = false;
				status_alert = 1000;
			}
			return;
		}

		status_alert--;

		if (status_alert < 0) {
			dormant = true;
			return;
		}

		if (age < ZOMBIE_MAX_AGE * 0.1) {
			if (age > ZOMBIE_MAX_AGE * 0.07) {
				move(0);
			} else {
				double ratio = 1 - ratio(age, ZOMBIE_MAX_AGE * 0.07);

				roam(speed * ratio * 2, turnRate);
			}

			return;
		} else if (age > ZOMBIE_MAX_AGE) {
			// kill();
		}

		if (allies != null) {
			if (allies.size() < 2
					&& ZOMBIE_MAX_AGE * 0.5 + Utils.random() * ZOMBIE_MAX_AGE * 0.5 < age) {
				move(0);
			}
		}

		enemies = getTargets(enemies, ZOMBIE_ENEMIES, true);

		if (!enemies.isEmpty()) {
			enemy = enemies.firstEntry().getValue();
			status = NPC.STATUS_THREAT;
			status_alert = 1000;
			if (Utils.random() * 1000 < 1) {
				say("roar!", 100);
				getWorld().spawnEntity(new Sound(getX(), getY(), getZ()));
			}
		} else {
			if (status >= NPC.STATUS_ALERT) {
				status = NPC.STATUS_ALERT;
			} else {
				status = NPC.STATUS_IDLE;
			}
		}

		if (status == NPC.STATUS_ALERT) {
			allies = getTargets(25, allies, 3.0, Math.PI, ZOMBIE_ALLIES, true);
			ally = getClosestNPC(allies, NPC.STATUS_THREAT);
		}
		if (status == NPC.STATUS_IDLE) {
			allies = getTargets(25, allies, 3.0, Math.PI, ZOMBIE_ALLIES, true);
			ally = getClosestNPC(allies, NPC.STATUS_ALERT);
			if (ally != null) {
				status = NPC.STATUS_ALERT;
			}
		}
		if (seeTarget(enemy, ZOMBIE_ENEMIES, true)) {
			lockTarget(enemy);
			chase(speed, turnRate);
			if (seeTarget(enemy, 0.1, Math.PI * 0.1, ZOMBIE_ENEMIES, true)) {
				bite();
			} else {
				enemy = null;
			}
		} else if (seeTarget(ally, 5.0, Math.PI, ZOMBIE_ALLIES, true) && status <= NPC.STATUS_THREAT) {
			follow(speed, turnRate, ally, 1);
		} else {
			roam(speed * 0.5, turnRate);
		}
	}

	public void bite() {
		if (enemy.getType().equalsIgnoreCase("Entity.NPC.Soldier")
				|| enemy.getType().equalsIgnoreCase("Entity.NPC.Human")) {

			if (Utils.random() * 100 < 1) {
				enemy.remove();
				getWorld().spawnEntity(
						new Zombie(enemy.getX(), enemy.getY(), enemy.getZ(), enemy.getDirection()));
			} else {
				enemy.damage(1);
			}
		} else {
			enemy.damage(30);
		}

	}

	private boolean isStillHuman() {
		if (age < ZOMBIE_MAX_AGE * 0.09) {
			return true;
		}
		return false;
	}

	@Override
	public String getNpcTypeName() {
		if (isStillHuman()) {
			return "Human_Infected";
		}
		return "Zombie";
	}

}
