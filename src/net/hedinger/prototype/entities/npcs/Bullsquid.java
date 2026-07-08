package net.hedinger.prototype.entities.npcs;

import java.awt.Color;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Scent;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;

/**
 * The sim's apex predator: a nocturnal hunter that runs down Houndeyes,
 * tracks wounded prey by blood scent and herds by their trails, scavenges
 * carcasses, hauls surplus meat home to a nest cache, and breeds only when
 * hunting is good.
 */
public class Bullsquid extends NPC {

	private static final int BULLSQUID_SF = 25;
	private static final int BULLSQUID_MAX_AGE = 16000;
	private static final double CARRY_MAX = 30;

	private static final String[] PREY = { "Entity.NPC.Houndeye", "Entity.NPC.Headcrab",
			"Entity.NPC.Human" };

	private TreeMap<Double, NPC> prey = null;
	private NPC quarry = null;
	private int biteCooldown = 0;

	public Bullsquid(double x, double y, double z) {
		this(x, y, z, new Genome(
				variation(0.045, 0.006), // speed: faster than its prey
				variation(8, 1), // turn: but less agile
				variation(9, 1), // losRange
				variation(0.03, 0.004), // metabolism
				variation(1.3, 0.15), // sizeScale
				variation(1200, 150), // gestation
				0.8)); // night hunter
		age = matureAge + (int) (Math.random() * 1000);
		energy = 55 + Math.random() * 25;
		hydration = 60 + Math.random() * 30;
	}

	public Bullsquid(double x, double y, double z, Genome g) {
		super(x, y, z);
		col = new Color(200, 160, 40);
		health = 220;
		lifespan = (int) variation(BULLSQUID_MAX_AGE, 2500);
		deathspan = 3000;
		SEARCH_FREQ = BULLSQUID_SF;
		LOS_FOV = Math.PI; // all-round senses (sight + smell + hearing)

		metabolic = true;
		susceptible = false;
		trailScent = Scent.TRAIL_PREDATOR;
		matureAge = 1200;
		litterMax = 2;
		thirstRate = 0.008; // gets most moisture from meat
		energy = 55;
		hydration = 70;

		applyGenome(g);
		size = 5;
	}

	@Override
	protected void think() {
		int turn = (int) Math.max(2, Math.round(genome.turn));
		double speed = genome.speed;

		double grown = Math.min(1, (double) age / matureAge);
		size = (int) Math.round((4 + 4 * grown) * genome.sizeScale);

		if (biteCooldown > 0) {
			biteCooldown--;
		}

		prey = getTargets(prey, PREY, true);

		// 1. a carcass in reach is free food: eat, then load up meat to haul
		NPC corpse = findCorpse(Math.max(3, LOS_RANGE));
		if (corpse != null && (energy < energyMax - 5 || carriedFood < CARRY_MAX)) {
			if (distance(corpse) < 0.85) {
				tX = X;
				tY = Y;
				tZ = Z;
				move(0);
				scavenge(corpse, 2.0, CARRY_MAX);
			} else {
				navigate(speed, turn, corpse.getX(), corpse.getY());
			}
			return;
		}

		// 2. thirst before hunger: predators die dumb deaths too
		if (hydration < 25) {
			seekWater(speed, turn);
			return;
		}

		// 3. hungry: hunt
		if (energy < 65) {
			status = NPC.STATUS_THREAT;

			if (!prey.isEmpty()) {
				quarry = prey.firstEntry().getValue();
			}
			if (seeTarget(quarry, PREY, true)) {
				lockTarget(quarry);
				chase(speed * 1.5, turn); // sprint
				if (distance(quarry) < 0.5) {
					bite(quarry);
				}
				return;
			}
			quarry = null;

			// no prey in sight: nose to the ground
			if (followScent(Scent.BLOOD, speed, turn)) {
				return;
			}
			if (followScent(Scent.TRAIL_HERBIVORE, speed * 0.9, turn)) {
				return;
			}
			// last resort: raid the larder
			if (hasNest() && nest.getFood() > 2) {
				if (atNest()) {
					eatFromCache(2);
					move(0);
				} else {
					goHome(speed, turn);
				}
				return;
			}
			roam(speed * 0.6, turn); // conserve energy while searching
			return;
		}

		status = NPC.STATUS_IDLE;

		// 4. sated: bank the surplus
		if (carriedFood > 1 && hasNest()) {
			if (atNest()) {
				cacheFood();
				move(0);
			} else {
				goHome(speed, turn);
			}
			return;
		}
		if (age > matureAge && !hasNest() && energy > 50) {
			buildNest();
		}

		// 5. breed when hunting is good; solitary hunters find each other
		// by following the trails other bullsquids leave
		if (canMate()) {
			if (seekMate(speed, turn)) {
				return;
			}
			if (followScent(Scent.TRAIL_PREDATOR, speed * 0.8, turn)) {
				return;
			}
		}

		// 6. nocturnal: den up during the day
		if (!getWorld().isNight()) {
			if (hasNest() && !atNest()) {
				goHome(speed * 0.7, turn);
			} else {
				rest();
			}
			return;
		}

		// night patrol
		roam(speed * 0.5, turn);
	}

	private void bite(NPC target) {
		if (biteCooldown > 0 || target == null) {
			return;
		}
		biteCooldown = 15;
		target.damage((int) variation(55, 15));
		energy = Math.max(0, energy - 1);
	}

	@Override
	protected NPC createOffspring(double x, double y, double z, Genome g) {
		return new Bullsquid(x, y, z, g);
	}

	@Override
	public String getNpcTypeName() {
		return "Bullsquid";
	}
}
