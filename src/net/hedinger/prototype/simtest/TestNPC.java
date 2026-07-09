package net.hedinger.prototype.simtest;

import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

/**
 * Test-fixture NPC for scenario tests.
 *
 * <p>Scenarios should exercise <em>engine mechanics</em> (movement, collision,
 * perception, lifecycle, hearing) rather than the behaviour of any concrete
 * game species -- the bestiary (Zombie, Houndeye, ...) is expected to change or
 * disappear, and the tests must survive that. TestNPC provides the minimal
 * behaviours those mechanics need, with per-instance knobs instead of
 * hardcoded species constants:
 *
 * <ul>
 *   <li>{@link #inert} -- never moves; a stationary target or victim</li>
 *   <li>{@link #roamer} -- wanders randomly</li>
 *   <li>{@link #chaser} -- chases the closest NPC it can perceive</li>
 *   <li>{@link #listener} -- inert until it hears a {@code Sound}, then roams</li>
 * </ul>
 *
 * Configure lifecycle via the fluent setters, e.g.
 * {@code TestNPC.inert(x, y, z).withLifespan(50).withDeathspan(0)}.
 */
public class TestNPC extends NPC {

	private enum Behavior {
		INERT, ROAM, CHASE, LISTEN, MOVE, GENOME, GRAZE, BREEDER, NEST
	}

	/** Vegetation eaten per tick while grazing (>> the tile's regrowth rate). */
	private static final double GRAZE_DEMAND = 0.05;
	/** Pheromone laid on the nest tile at each birth; >> per-tick evaporation. */
	private static final double NEST_DEPOSIT = 8.0;
	/** How far a nester can smell its nest when homing to breed. */
	private static final int NEST_SENSE_R = 8;

	private final Behavior behavior;
	private double speed = 0.04;
	private int turn = 5;
	private boolean heard = false;
	private double totalIntake = 0;
	private TreeMap<Double, NPC> prey = null;

	private TestNPC(double x, double y, double z, Behavior behavior) {
		super(x, y, z);
		this.behavior = behavior;
		hostile = 1;
		size = 6;
		health = 100;
		deathspan = 1000;
		SEARCH_FREQ = 50;
		LOS_RANGE = 10;
		LOS_FOV = Math.PI * 0.5;
	}

	// ---- factories ---------------------------------------------------------

	/** Never moves. A stationary target, obstacle or damage victim. */
	public static TestNPC inert(double x, double y, double z) {
		return new TestNPC(x, y, z, Behavior.INERT);
	}

	/** Wanders randomly. */
	public static TestNPC roamer(double x, double y, double z) {
		return new TestNPC(x, y, z, Behavior.ROAM);
	}

	/** Chases the closest NPC it can perceive. */
	public static TestNPC chaser(double x, double y, double z) {
		TestNPC t = new TestNPC(x, y, z, Behavior.CHASE);
		t.speed = 0.03;
		return t;
	}

	/** Inert until it hears a Sound, then roams. Tests the hear() channel. */
	public static TestNPC listener(double x, double y, double z) {
		return new TestNPC(x, y, z, Behavior.LISTEN);
	}

	/**
	 * Walks in a straight line along the given heading (radians; 0 = +x).
	 * Blocked moves are cancelled by the engine, so a mover halts at walls,
	 * closed doors, etc. -- ideal for probing passability.
	 */
	public static TestNPC mover(double x, double y, double z, double heading) {
		TestNPC t = new TestNPC(x, y, z, Behavior.MOVE);
		t.D = heading;
		return t;
	}

	/**
	 * A herbivore: each tick it eats vegetation from the tile underfoot, and
	 * wanders on once that patch thins -- so grazing pressure spreads and bare
	 * patches appear. {@link #totalIntake()} reports how much it has eaten.
	 */
	public static TestNPC grazer(double x, double y, double z) {
		TestNPC t = new TestNPC(x, y, z, Behavior.GRAZE);
		t.speed = 0.02;
		return t;
	}

	/**
	 * A metabolic herbivore that evolves: it grazes for energy, burns it each
	 * tick, starves at zero, and buds a mutated child once well-fed. Offspring
	 * inherit a mutated copy of its {@link Genome}, so a fed population grows and
	 * drifts. The whole energy/reproduction loop in one fixture.
	 */
	public static TestNPC breeder(double x, double y, double z, net.hedinger.prototype.entities.Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.BREEDER);
		configureGenomeBody(t, g);
		return t;
	}

	/**
	 * A breeder that nests: as it forages it is like any breeder, but when it is
	 * ready to reproduce it homes up the pheromone gradient to its nest, lays a
	 * strong pheromone blob, and births the child there. The nest is not an
	 * object -- it is the emergent pheromone peak the lineage keeps reinforcing,
	 * so descendants cluster into a colony.
	 */
	public static TestNPC nester(double x, double y, double z, net.hedinger.prototype.entities.Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.NEST);
		configureGenomeBody(t, g);
		t.reproThreshold = 3.0; // bank a buffer to cover the trip home
		return t;
	}

	private static void configureGenomeBody(TestNPC t, net.hedinger.prototype.entities.Genome g) {
		t.genome = g;
		t.size = (int) Math.round(g.size);
		t.speed = g.speed;
		t.turn = g.turnRate;
		t.metabolic = true;
		t.energy = 1.0;
		t.col = g.toColor();
	}

	/**
	 * Behaviour driven entirely by its {@link Genome}: each tick it reacts to
	 * the most salient perceived neighbour (attack/mate/affiliate -> chase,
	 * flee -> flee, nothing -> roam). Sources its body stats from the genome
	 * and colours its dot by the genome's markers, so similarity is visible.
	 */
	public static TestNPC genomeDriven(double x, double y, double z, net.hedinger.prototype.entities.Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.GENOME);
		t.genome = g;
		t.size = (int) Math.round(g.size);
		t.speed = g.speed;
		t.turn = g.turnRate;
		t.LOS_RANGE = g.losRange;
		t.LOS_FOV = g.losFov;
		t.col = g.toColor();
		return t;
	}

	// ---- fluent lifecycle knobs ---------------------------------------------

	public TestNPC withHealth(int h) {
		health = h;
		return this;
	}

	public TestNPC withLifespan(int ticks) {
		lifespan = ticks;
		return this;
	}

	public TestNPC withDeathspan(int ticks) {
		deathspan = ticks;
		return this;
	}

	public TestNPC withSpeed(double s) {
		speed = s;
		return this;
	}

	/** Sets the body radius; gates grabbing and the carry offset. */
	public TestNPC withSize(int s) {
		size = s;
		return this;
	}

	/** Marks this NPC as flying: it hovers over holes instead of falling. */
	public TestNPC withFlying() {
		flying = true;
		return this;
	}

	/** True once this NPC has heard any Sound. */
	public boolean hasHeard() {
		return heard;
	}

	// ---- behaviour -----------------------------------------------------------

	@Override
	protected void think() {
		switch (behavior) {
		case INERT:
			return;
		case ROAM:
			roam(speed, turn);
			return;
		case CHASE:
			prey = getTargets(prey, "", false);
			lockTarget(getClosestNPC(prey));
			chase(speed, turn);
			return;
		case LISTEN:
			if (!heard && lastHeardSound != null) {
				heard = true;
			}
			if (heard) {
				roam(speed, turn);
			}
			return;
		case MOVE:
			move(speed);
			return;
		case GENOME:
			thinkGenome();
			return;
		case GRAZE:
			thinkGraze();
			return;
		case BREEDER:
			thinkBreeder();
			return;
		case NEST:
			thinkNester();
			return;
		}
	}

	/** Grazes for energy and buds a mutated child once well-fed. */
	private void thinkBreeder() {
		double intake = graze(GRAZE_DEMAND); // feeds energy
		totalIntake += intake;
		if (tryReproduce()) {
			return;
		}
		if (intake < GRAZE_DEMAND * 0.15) {
			roam(speed, turn); // patch thinning -> find fresh grass
		}
	}

	private boolean homing = false;

	/** Forages; when ready to breed, commits to homing and births at the nest. */
	private void thinkNester() {
		double intake = graze(GRAZE_DEMAND);
		totalIntake += intake;
		if (!homing && energy >= reproThreshold && reproCooldown == 0) {
			homing = true; // commit -- don't flip back to foraging mid-trip
		}
		if (homing) {
			double home = nestDirection(NEST_SENSE_R);
			if (Double.isNaN(home) || sensePheromone() > 1.0) {
				// At (or in) the nest: reinforce the mark and breed here.
				depositPheromone(NEST_DEPOSIT);
				tryReproduce();
				homing = false;
			} else {
				move(speed, home); // walk home up the pheromone gradient
			}
			return;
		}
		if (intake < GRAZE_DEMAND * 0.15) {
			roam(speed, turn);
		}
	}

	@Override
	protected net.hedinger.prototype.entities.NPC spawnOffspring() {
		if (genome == null) {
			return null;
		}
		// Asexual: a mutated copy of this genome, born at the parent's spot.
		net.hedinger.prototype.entities.Genome childG =
				net.hedinger.prototype.entities.Genome.child(genome, 0.1);
		return behavior == Behavior.NEST ? nester(X, Y, Z, childG) : breeder(X, Y, Z, childG);
	}

	/** Eats the substrate underfoot; wanders on once a patch is grazed down. */
	private void thinkGraze() {
		double intake = graze(GRAZE_DEMAND);
		totalIntake += intake;
		// Stay and crop the patch down; only move on when it is nearly bare, so
		// grazing bores a clear depleted spot before the herbivore wanders off.
		if (intake < GRAZE_DEMAND * 0.15) {
			roam(speed, turn);
		}
	}

	/** Total vegetation this grazer has eaten (for assertions/overlay). */
	public double totalIntake() {
		return totalIntake;
	}

	/** Reacts to the single most salient perceived neighbour via the genome. */
	private void thinkGenome() {
		net.hedinger.prototype.entities.Genome.Action act = net.hedinger.prototype.entities.Genome.Action.IGNORE;
		NPC subject = null;
		double best = 0;
		for (NPC n : targets.values()) {
			net.hedinger.prototype.entities.Genome og = n.getGenome();
			if (og == null || n == this) {
				continue;
			}
			double sizeAdv = getSize() / Math.max(1e-6f, n.getSize());
			net.hedinger.prototype.entities.Genome.Relation r = genome.react(og, sizeAdv);
			if (r.strength() > best) {
				best = r.strength();
				act = r.action;
				subject = n;
			}
		}
		lastAction = act;
		if (subject == null) {
			roam(speed, turn);
			return;
		}
		switch (act) {
		case ATTACK:
		case AFFILIATE:
		case MATE:
			lockTarget(subject);
			chase(speed, turn);
			return;
		case FLEE:
			flee(speed, turn, subject, 0.25);
			return;
		default:
			roam(speed, turn);
		}
	}

	private net.hedinger.prototype.entities.Genome.Action lastAction =
			net.hedinger.prototype.entities.Genome.Action.IGNORE;

	/** The action this genome-driven NPC took on its last think (for tests/overlay). */
	public net.hedinger.prototype.entities.Genome.Action lastAction() {
		return lastAction;
	}

	@Override
	public String getNpcTypeName() {
		return "TestNPC";
	}

	/** One-line state summary for the snapshot debug overlay. */
	public String debugLabel() {
		if (behavior == Behavior.GENOME) {
			StringBuilder s = new StringBuilder(lastAction.name().toLowerCase());
			if (getAttachTarget() != null) {
				s.append(" carried");
			}
			if (isDead()) {
				s.append(" dead");
			}
			return s.toString();
		}
		StringBuilder s = new StringBuilder(behavior.name().toLowerCase());
		if (behavior == Behavior.GRAZE) {
			s.append(" ate ").append(String.format("%.2f", totalIntake));
		}
		if (behavior == Behavior.BREEDER || behavior == Behavior.NEST) {
			s.append(String.format(" e%.1f", getEnergy()));
		}
		if (flying) {
			s.append(" fly");
		}
		if (heard) {
			s.append(" heard!");
		}
		if (grabbing != null) {
			s.append(" grabbing");
		}
		if (getAttachTarget() != null) {
			s.append(" carried");
		}
		if (isDead()) {
			s.append(" dead");
		} else if (health < 100) {
			s.append(" hp").append(health);
		}
		return s.toString();
	}
}
