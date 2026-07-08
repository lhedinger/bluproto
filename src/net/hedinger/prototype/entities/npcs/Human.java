package net.hedinger.prototype.entities.npcs;

import net.hedinger.prototype.engine.Utils;

import java.util.TreeMap;

import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Sound;

public class Human extends NPC {
	private static final int HUMAN_SF = 50;

	private static final String[] HUMAN_ENEMIES = { "Entity.NPC.Antlion", "Entity.NPC.Headcrab",
			"Entity.NPC.Houndeye", "Entity.NPC.Zombie" };

	private TreeMap<Double, NPC> soldiers = null;

	private NPC enemy = null;
	private NPC soldier = null;

	private int stamina = 100;

	public Human(double x, double y, double z) {
		super(x, y, z);
		applyGenome(Genome.phenotype(6, 0.04, 5, 10, Math.PI * 2, 3000));
		hostile = 1;
		health = 100;
		deathspan = 1000;
		SEARCH_FREQ = HUMAN_SF;
	}

	@Override
	protected void think() {
		focusTargets = getTargets(focusTargets, HUMAN_ENEMIES, true);

		if (stamina < 1000) {
			stamina = 100;
		}

		if (!focusTargets.isEmpty()) {
			enemy = focusTargets.firstEntry().getValue();
			status = NPC.STATUS_THREAT;

			if (Utils.random() * 1000 < 1) {
				say(panic_phrases[(int) (Utils.random() * panic_phrases.length)], 200);
				getWorld().spawnEntity(new Sound(getX(), getY(), getZ()));
			}
		} else {
			if (status >= NPC.STATUS_ALERT) {
				status = NPC.STATUS_ALERT;
			} else {
				status = NPC.STATUS_IDLE;
			}
		}

		if (status == NPC.STATUS_IDLE) {
			roam(speed * 0.5, turnRate);
		}
		if (status == NPC.STATUS_ALERT) {
			roam(speed, turnRate);
		}
		if (status == NPC.STATUS_THREAT) {
			soldiers = getTargets(soldiers, "Entity.NPC.Soldier", true);

			if (!soldiers.isEmpty()) {
				soldier = soldiers.firstEntry().getValue();
			}

			if (seeTarget(soldier, LOS_RANGE, Math.PI, "Entity.NPC.Soldier", true)) {
				follow(speed, turnRate, soldier, 0.25);
			} else {
				double fleeSpeed = speed;
				if (stamina > 0) {
					fleeSpeed = speed * 1.75;
				}
				stamina--;
				flee(fleeSpeed, turnRate, enemy, 0.25);
			}

		}

	}

	public static final String[] panic_phrases = { "They're everywhere!",
			"Game over man, game over!", "I hate my life!", "What the fuck man? What the fuck?",
			"Save us!", "I knew this was gonna happen!" };

	@Override
	public String getNpcTypeName() {
		return "Human";
	}

}
