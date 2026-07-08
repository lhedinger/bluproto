package net.hedinger.prototype.entities.npcs;

import java.awt.Color;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Scent;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Sound;

/**
 * The sim's grazing herbivore. Houndeyes live off tile flora, drink at
 * waterholes, herd together, breed from surplus energy, and panic-flee
 * predators — leaving fear scent that spooks the rest of the herd.
 */
public class Houndeye extends NPC {

	private static final int HOUNDEYE_SF = 40;
	private static final int HOUNDEYE_MAX_AGE = 12000;

	private static final String[] PREDATORS = { "Entity.NPC.Bullsquid", "Entity.NPC.Zombie",
			"Entity.NPC.Soldier", "Entity.NPC.Sentry", "Entity.NPC.Drone", "Entity.NPC.Elite" };

	private TreeMap<Double, NPC> threats = null;
	private TreeMap<Double, NPC> herd = null;
	private NPC threat = null;

	public Houndeye(double x, double y, double z) {
		this(x, y, z, new Genome(
				variation(0.035, 0.005), // speed
				variation(6, 1), // turn
				variation(7, 1), // losRange
				variation(0.04, 0.005), // metabolism
				variation(1.0, 0.1), // sizeScale
				variation(1100, 150), // gestation
				0.15)); // mostly diurnal
		// world-genesis animals start grown up and settled in
		age = matureAge + (int) (Math.random() * 1000);
		energy = 60 + Math.random() * 30;
		hydration = 60 + Math.random() * 30;
	}

	public Houndeye(double x, double y, double z, Genome g) {
		super(x, y, z);
		col = new Color(0, 180, 0);
		health = 100;
		lifespan = (int) variation(HOUNDEYE_MAX_AGE, 2000);
		deathspan = 2500; // carcass persists: scavenger food, then soil
		SEARCH_FREQ = HOUNDEYE_SF;
		LOS_FOV = Math.PI; // prey watches its back

		metabolic = true;
		susceptible = true;
		trailScent = Scent.TRAIL_HERBIVORE;
		matureAge = 1200;
		litterMax = 2;
		energy = 55;
		hydration = 70;

		applyGenome(g);
		size = 3;
	}

	@Override
	protected void think() {
		int turn = (int) Math.max(2, Math.round(genome.turn));
		double speed = genome.speed;

		// body grows toward genome size as the animal matures
		double grown = Math.min(1, (double) age / matureAge);
		size = (int) Math.round((3 + 3 * grown) * genome.sizeScale);

		// 1. visible predator: sprint away, sound the alarm
		threats = getTargets(threats, PREDATORS, true);
		if (!threats.isEmpty()) {
			threat = threats.firstEntry().getValue();
			status = NPC.STATUS_THREAT;
			if (Math.random() * 60 < 1) {
				say("!", 40);
				getWorld().spawnEntity(new Sound(getX(), getY(), getZ()));
			}
			flee(speed * 1.4, turn, threat, 3);
			return;
		}

		// 2. fear scent alone is enough to spook the herd
		if (senseScent(Scent.FEAR) > 1.2) {
			status = NPC.STATUS_ALERT;
			double danger = smell(Scent.FEAR);
			if (!Double.isNaN(danger)) {
				roam(speed * 1.15, turn, danger + Math.PI);
				return;
			}
		}

		status = NPC.STATUS_IDLE;

		// 3. needs, most pressing first
		if (hydration < 30) {
			seekWater(speed, turn);
			return;
		}
		if (energy < 75) {
			seekFlora(speed, turn, 0.06);
			return;
		}

		// 4. surplus: breed
		if (canMate() && seekMate(speed, turn)) {
			return;
		}

		// 5. diurnal: sleep through the night (threat checks still wake us)
		if (getWorld().isNight()) {
			rest();
			return;
		}

		// 6. otherwise keep loose herd cohesion
		herd = getTargets(herd, "Entity.NPC.Houndeye", true);
		NPC buddy = getClosestNPC(herd);
		if (buddy != null && distance(buddy) > 2.5) {
			follow(speed * 0.8, turn, buddy, 1.5);
		} else {
			roam(speed * 0.6, turn);
		}
	}

	@Override
	protected NPC createOffspring(double x, double y, double z, Genome g) {
		return new Houndeye(x, y, z, g);
	}

	@Override
	public String getNpcTypeName() {
		return "Houndeye";
	}
}
