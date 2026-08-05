package net.hedinger.prototype.simtest;

import java.util.TreeMap;

import net.hedinger.prototype.entities.AgentIO;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.LgpMind;
import net.hedinger.prototype.entities.Mind;
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
		INERT, ROAM, CHASE, LISTEN, MOVE, GENOME, GRAZE, BREEDER, NEST, MATER, MINDED, HAUL, PREDATOR
	}

	/** Damage a predator's bite does to prey of its own size or smaller (prey
	 *  health 100 -> a few-second kill). Scaled down against bigger quarry — see
	 *  {@link #biteDamage}. */
	private static final int PRED_DAMAGE = 20;
	/** How much larger than itself a hunter will take on, as a multiple of its own
	 *  body size. Above 1 so a smaller hunter can pick a fight it is not built to
	 *  win quickly: it lands weaker bites and needs far more of them, which is the
	 *  whole trade — a long, costly, interruptible kill instead of a clean one. */
	private static final double PRED_MAX_PREY_RATIO = 1.5;
	/** Floor on the bite-strength multiplier, so taking on the largest quarry a
	 *  hunter will engage stays a slow kill rather than an impossible one. */
	private static final double PRED_MIN_BITE_SCALE = 0.25;
	/** Energy a predator gains per bite (a kill is worth roughly a breeding's cost).
	 *  Deliberately NOT scaled by size: a bigger animal takes more bites to bring
	 *  down and so yields more energy in total, which is what makes the slow, risky
	 *  kill worth attempting at all. */
	private static final double PRED_BITE_ENERGY = 0.5;
	/** Fraction of top speed a predator patrols at while no prey is in sight — it
	 *  lopes around cheaply and saves the full sprint for an actual pursuit. */
	private static final double PRED_CRUISE = 0.6;
	/** Sprint surcharge as a multiple of resting burn: a pursuit costs ~2.5x the
	 *  resting rate (1.0 base + this), so cruising is cheap but a long fruitless
	 *  chase still bites. Scales with body size because the resting rate does. */
	private static final double PRED_SPRINT_FACTOR = 1.5;
	/** Top-speed multiplier while a mind engages the sprint actuator, paired with
	 *  the sprint energy surcharge: the burst is genuinely faster than the cruise it
	 *  costs more than, so the mind has a real gear to spend energy on. */
	private static final double SPRINT_SPEED_MULT = 1.5;
	/** A predator holding less than this fraction of its (size-scaled) tank is
	 *  starving — desperate enough to break the taboo and hunt its own kind. Born
	 *  at 0.6 and breeding at 0.75, a hunter only drops this low when it has been
	 *  failing to feed, so cannibalism stays a genuine last resort. */
	private static final double STARVE_FRACTION = 0.2;
	/** Window (ticks) over which a hunter's NET displacement is measured to spot a
	 *  pin. A trailing ring is sampled EVERY tick (not a free-running counter), so
	 *  a pin is caught within one window rather than up to two — the difference
	 *  between a barely-noticeable hitch and a hunter standing frozen for seconds.
	 *  Net displacement over a window also tells a genuine crawl (e.g. across
	 *  drag-heavy mud) apart from turning in place, which a per-tick check cannot. */
	private static final int PIN_WINDOW = 30;
	/** If a hunter covers less than this (tiles) over {@link #PIN_WINDOW}, it is
	 *  pinned. Sits well below even a slow straight mud crawl over the window
	 *  (~0.008 tiles/tick * 30 = 0.24), so only a near-standstill trips it. */
	private static final double PIN_MIN_MOVE = 0.1;
	/** After a pin, drive straight off clear ground for this many ticks (ignoring
	 *  prey) to break free. */
	private static final int HUNT_GIVEUP_TICKS = 45;

	/** Vegetation eaten per tick by a reference-size grazer (>> the tile's regrowth
	 *  rate). Bigger grazers crop faster in proportion to their size — see
	 *  {@link #grazeDemand()} — so a large body both burns and eats more. */
	private static final double GRAZE_DEMAND = 0.05;

	/** This grazer's per-tick appetite: {@link #GRAZE_DEMAND} scaled by body size,
	 *  so a bigger grazer takes bigger bites (and depletes a patch faster). */
	private double grazeDemand() {
		return GRAZE_DEMAND * bodyMass();
	}
	/** Eco herbivore: flees any predator within this radius (tiles). */
	private static final double THREAT_R = 6.0;
	/** Eco herbivore: regroups with kin within this radius when a patch thins. */
	private static final double HERD_R = 7.0;
	/** Pheromone laid at the nest at each birth; >> per-tick evaporation, so a
	 *  repeatedly-marked nest cloud builds a strong persistent peak. */
	private static final double NEST_DEPOSIT = 12.0;
	/** How far a nester can smell its nest when homing to breed. */
	private static final int NEST_SENSE_R = 8;
	/** How close (tiles, on top of touching) a mater must be to a partner to breed. */
	private static final double MATE_REACH = 0.5;

	/** Max steering per tick (radians) applied by the mind's turn actuator. */
	private static final double MAX_TURN = 0.35;
	/** Reach (tiles, beyond touching) of the mind's attack actuator. */
	private static final double ATTACK_REACH = 0.5;
	/** Health removed per tick from a neighbour the mind attacks. */
	private static final int ATTACK_DAMAGE = 4;
	/** Energy a successful bite feeds the attacker (predation payoff). */
	private static final double BITE_ENERGY = 0.03;

	private final Behavior behavior;
	private double speed = 0.04;
	private int turn = 5;
	private boolean heard = false;
	private boolean vigilant = false; // eco herbivore: flee predators, herd with kin
	private int generation = 0; // 0 = spawned by the world; a child is its parent's + 1
	private boolean handPlaced = false; // placed by a person, not by the steward
	private String ecoAction = ""; // what this creature did on its last tick (for inspect)
	private double totalIntake = 0;
	private double[] pinX, pinY; // trailing ring of recent positions (pin detection)
	private int pinCount = 0; // ring entries filled so far (caps at PIN_WINDOW)
	private int huntGiveUp = 0; // ticks left prowling after a pinned hunt
	private double escLastX, escLastY; // position last give-up tick (zeroed-step detection)
	private double lastThrottle = 0; // minded body's throttle last tick (gates the unstick reflex)
	private TreeMap<Double, NPC> prey = null;
	private TreeMap<Double, NPC> mates = null;
	private Mind mind = null;
	private final double[] sensors = new double[AgentIO.NUM_SENSORS];
	private final double[] actuators = new double[AgentIO.NUM_ACT];

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
	 * A courier: it walks to the nearest {@link Item} it can perceive, grabs it,
	 * then hauls it to a remembered drop-off coordinate and sets it down. Unlike
	 * the mind's reactive turn/throttle actuators, this uses the engine's
	 * move-to-target machinery -- it stores the destination in {@code (tX, tY)}
	 * and {@link #chase} steers toward it -- so it demonstrates remembered-goal
	 * navigation plus carrying.
	 */
	public static TestNPC hauler(double x, double y, double z,
			double pickX, double pickY, double destX, double destY) {
		TestNPC t = new TestNPC(x, y, z, Behavior.HAUL);
		t.haulPickX = pickX; // where to go to find the item to fetch
		t.haulPickY = pickY;
		t.haulDestX = destX;
		t.haulDestY = destY;
		t.haulHomeX = x; // where it started, and where it returns after dropping
		t.haulHomeY = y;
		t.size = 12; // big enough to grab a standard crate
		t.speed = 0.05;
		t.turn = 10; // gentle, wide turns
		t.LOS_FOV = Math.PI * 2;
		t.LOS_RANGE = 24;
		t.SEARCH_FREQ = 2;
		return t;
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
	 * A grazer that carries a {@link Genome} purely for its <em>body</em>: it
	 * renders as the genome's procedural organism (and colours/sizes/flies to
	 * match) but keeps the plain, non-metabolic graze-and-wander behaviour, so a
	 * herd of these stays alive indefinitely while looking like distinct species.
	 * The genome drives no decisions here — reproduction/energy stay off.
	 */
	public static TestNPC grazer(double x, double y, double z, Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.GRAZE);
		t.genome = g;
		t.size = (int) Math.round(g.size);
		t.speed = g.speed > 0 ? g.speed : 0.02;
		t.turn = g.turnRate;
		t.flying = g.flying;
		t.col = g.toColor();
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
	 * A metabolic predator that hunts, eats, evolves and starves: it chases the
	 * nearest smaller creature, bites it for energy, buds a mutated child when
	 * well-fed, and dies if it cannot catch enough. Bigger and faster than the
	 * prey (so it can catch and out-mass them), with wide, frequent perception.
	 */
	public static TestNPC predator(double x, double y, double z, net.hedinger.prototype.entities.Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.PREDATOR);
		configureGenomeBody(t, g); // size-scaled reserve, burn, and repro thresholds
		t.sprintFactor = PRED_SPRINT_FACTOR; // only the pursuit burst is costly
		t.LOS_FOV = Math.PI * 2;
		t.LOS_RANGE = Math.max(g.losRange, 12);
		t.SEARCH_FREQ = 3;
		t.turn = 8;
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

	/**
	 * A metabolic herbivore that breeds <em>sexually</em>: it grazes for energy
	 * like a breeder, but instead of budding it seeks a genome-compatible partner
	 * and produces a crossover child of the two. No partner (or only dissimilar
	 * ones) means no offspring -- the defining difference from the asexual
	 * {@link #breeder}. Perception is myopic, so partners must be close to pair.
	 */
	public static TestNPC mater(double x, double y, double z, net.hedinger.prototype.entities.Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.MATER);
		configureGenomeBody(t, g);
		// Omnidirectional, frequent perception so pairing is reliable -- this
		// isolates the reproduction mechanic from the facing/FOV perception gate
		// (the same move GenomePredatorHuntsPrey makes for its predator).
		t.LOS_FOV = Math.PI * 2;
		t.LOS_RANGE = Math.max(g.losRange, 3);
		t.SEARCH_FREQ = 5;
		return t;
	}

	/**
	 * A body driven by a pluggable {@link Mind}: each tick it fills the
	 * {@link AgentIO} sensor vector from what it perceives, lets the mind write the
	 * actuator vector, and applies that as movement/actions. The mind can be an
	 * LGP brain, a hand-written controller, or nothing -- the body is identical, so
	 * this is the seam where the decision method is swapped. Perception is
	 * omnidirectional here so the mechanic isn't masked by the facing gate.
	 */
	public static TestNPC minded(double x, double y, double z, Genome g, Mind mind) {
		TestNPC t = new TestNPC(x, y, z, Behavior.MINDED);
		t.genome = g;
		t.size = (int) Math.round(g.size);
		t.speed = g.speed;
		t.turn = g.turnRate;
		t.col = g.toColor();
		t.LOS_FOV = Math.PI * 2;
		t.LOS_RANGE = Math.max(g.losRange, 3);
		t.SEARCH_FREQ = 2;
		t.sprintFactor = PRED_SPRINT_FACTOR; // the sprint actuator's burst has a real cost
		t.mind = mind;
		return t;
	}

	/** A minded body whose mind is the genome's own evolvable {@link Brain} (an
	 * {@link LgpMind}), so it is inherited on reproduction; an inert mind if the
	 * genome carries no brain. */
	public static TestNPC minded(double x, double y, double z, Genome g) {
		return minded(x, y, z, g, mindOf(g));
	}

	/** A metabolic brained forager: runs its genome's brain, grazes and burns
	 * energy, and buds mutated offspring that inherit (a crossed/mutated copy of)
	 * the brain -- so the mind itself evolves. */
	public static TestNPC brainedBreeder(double x, double y, double z, Genome g) {
		TestNPC t = minded(x, y, z, g, mindOf(g));
		t.metabolic = true;
		t.energy = 1.0;
		return t;
	}

	/**
	 * A full ecosystem citizen driven by its genome's evolvable {@link Brain}: same
	 * size-scaled energy economy as the hardcoded breeders/predators (born fed,
	 * burns to live, breeds when full, starves when it can't feed), but every
	 * decision — where to go, what to eat, whom to hunt or flee or mate — comes from
	 * the mind reading the {@link AgentIO} vector, not a hardcoded rule. Its role is
	 * not fixed: a big-bodied one that learns to chase is a predator, a grazer is
	 * prey, and offspring inherit the mutated brain, so the behaviour evolves. Used
	 * to seed the parallel minded cohort that competes against the scripted species.
	 */
	public static TestNPC mindedForager(double x, double y, double z, Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.MINDED);
		configureGenomeBody(t, g); // size-scaled reserve, burn and repro thresholds
		t.LOS_FOV = Math.PI * 2; // omnidirectional, like the other genome bodies
		t.LOS_RANGE = Math.max(g.losRange, 3);
		t.SEARCH_FREQ = 2;
		t.sprintFactor = PRED_SPRINT_FACTOR; // the sprint actuator's burst has a real cost
		t.mind = mindOf(g);
		return t;
	}

	/** True if this body's decisions come from a pluggable {@link Mind} (the hybrid
	 *  minded cohort) rather than a hardcoded eco behaviour -- used to mark them
	 *  apart in the viewer and count them in the census. */
	public boolean isMinded() {
		return behavior == Behavior.MINDED;
	}

	/** This body's LGP mind, if it runs one (the minded cohort) -- for the mind
	 *  inspector; null for a hardcoded behaviour or an inert/brain-less mind. */
	public LgpMind lgpMind() {
		return mind instanceof LgpMind lm ? lm : null;
	}

	/** A copy of the last-tick sensor vector the mind saw (mind inspector). */
	public double[] sensorSnapshot() {
		return sensors.clone();
	}

	/** A copy of the last-tick actuator vector the mind wrote (mind inspector). */
	public double[] actuatorSnapshot() {
		return actuators.clone();
	}

	private static final Mind INERT_MIND = new Mind() {
		@Override
		public void think(double[] sensors, double[] actuators) {
		}
	};

	private static Mind mindOf(Genome g) {
		return g.brain != null ? new LgpMind(g.brain) : INERT_MIND;
	}

	private static void configureGenomeBody(TestNPC t, net.hedinger.prototype.entities.Genome g) {
		t.genome = g;
		// Born a juvenile and grow into the genome's body (see NPC.beginGrowth).
		t.beginGrowth(g.size);
		t.speed = g.speed;
		t.turn = g.turnRate;
		t.metabolic = true;
		// Energy scales are all derived from body size (see NPC's size-scaled model):
		// born comfortably fed, and reproduction gated on filling a big fraction of
		// the (size-scaled) tank so a bigger creature must eat more before it breeds.
		// energyCapacity() is anchored on the adult body, so these are unchanged by
		// the creature being born a juvenile — growth is physical, not economic.
		t.energy = 0.6 * t.energyCapacity();
		t.reproThreshold = 0.75 * t.energyCapacity();
		t.reproCost = 0.5 * t.energyCapacity();
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

	/** Tints the body (purely cosmetic; the snapshot carries it as the entity rgb). */
	public TestNPC withColor(java.awt.Color c) {
		col = c;
		return this;
	}

	/** Marks this NPC as flying: it hovers over holes instead of falling. */
	public TestNPC withFlying() {
		flying = true;
		return this;
	}

	/** Sets the starting energy (for metabolic fixtures like the breeder/mater). */
	public TestNPC withEnergy(double e) {
		energy = e;
		return this;
	}

	/** Generation depth: 0 for a creature the world (or steward) spawned, and one
	 *  more than its parent for any creature born in-world by reproduction — so a
	 *  lineage's age in births is visible at a glance (useful for debugging
	 *  evolution). */
	public int generation() {
		return generation;
	}

	/** Sets the generation (used when a birth stamps a child as parent + 1). */
	public TestNPC withGeneration(int g) {
		generation = g;
		return this;
	}

	/** True for a creature a person placed by hand (a genome injected from the
	 *  viewer) rather than one the {@link net.hedinger.prototype.sim.WorldSteward}
	 *  seeded. The steward's population ceiling leaves these alone -- see
	 *  {@link #withHandPlaced()}. */
	public boolean isHandPlaced() {
		return handPlaced;
	}

	/**
	 * Marks this creature as deliberately placed by a person, which exempts it
	 * from the steward's population cull.
	 *
	 * <p>The steward holds the minded cohort under a ceiling by silently deleting
	 * surplus members ({@code Entity.remove()} -- age -1 <em>and</em> removal in
	 * the same breath, so the body blinks out with no corpse). A hand-placed
	 * creature that landed in a world already at the ceiling was therefore liable
	 * to vanish within a second or two of being dropped, healthy and well-fed,
	 * which read as "I tap the ground and it disappears". Exempting it makes an
	 * injection <em>displace</em> one of the steward's own creatures instead of
	 * being displaced by them -- the cohort stays just as bounded, because the
	 * founder still counts toward the ceiling and its offspring are ordinary
	 * cullable citizens.
	 */
	public TestNPC withHandPlaced() {
		handPlaced = true;
		return this;
	}

	/** Holds off reproduction for this many ticks — lets a scenario keep a well-fed
	 *  predator sated for a whole window instead of letting it immediately breed the
	 *  surplus away and drop back to hungry. */
	public TestNPC withReproCooldown(int ticks) {
		reproCooldown = ticks;
		return this;
	}

	/** Makes this body metabolic (burns energy, can starve) — lets a scenario put a
	 *  hand-driven mind on an energy-bearing body to exercise the hunger/sprint
	 *  economy without the full breeder lifecycle. */
	public TestNPC withMetabolic() {
		metabolic = true;
		return this;
	}

	/**
	 * Eco-only: makes a herbivore predator-aware. Each tick it bolts from any
	 * predator inside {@link #THREAT_R} (overriding grazing), and when its patch
	 * thins it drifts toward nearby kin instead of wandering at random, so a herd
	 * loosely coheres and scatters. Off by default, so the plain {@link #breeder}
	 * the scenarios use — and the deterministic stream — are unchanged. Inherited
	 * by offspring, so a vigilant lineage stays vigilant.
	 */
	public TestNPC withHerding() {
		vigilant = true;
		return this;
	}

	/** True once this NPC has heard any Sound. */
	public boolean hasHeard() {
		return heard;
	}

	// ---- behaviour -----------------------------------------------------------

	@Override
	protected void think() {
		// If whoever was carrying us is dead or gone, we're free again -- a captive
		// isn't clamped to a corpse.
		if (getAttachTarget() != null && (getAttachTarget().isDead() || getAttachTarget().isRemoved())) {
			setGrabbed(false);
			detach();
		}
		// A grabbed captive can do only two things: struggle and communicate. A
		// voluntary rider (attached but not grabbed) is NOT frozen -- it keeps
		// grazing, attacking, breeding, and can let go, all while carried.
		if (isGrabbed()) {
			struggleWhileHeld();
			return;
		}
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
		case MATER:
			thinkMater();
			return;
		case MINDED:
			thinkMinded();
			return;
		case HAUL:
			thinkHaul();
			return;
		case PREDATOR:
			thinkPredator();
			return;
		}
	}

	/**
	 * A hunter whose drive tracks its hunger. Well-fed it merely patrols and takes
	 * prey that stumbles into reach; hungry it actively runs down the nearest
	 * smaller prey; starving it will even turn on smaller predators. It bites for a
	 * chunk of energy and buds a mutated child when well-fed, so a predator lineage
	 * evolves too. Metabolic: it starves if it cannot catch enough, which keeps
	 * predator numbers in check when prey are scarce. Spares its own kind unless
	 * starvation forces the issue.
	 */
	private void thinkPredator() {
		if (unstickIfPinned(speed * PRED_CRUISE, true, false)) {
			tryReproduce(); // shaking loose from a pin; still breed if well-fed
			return;
		}

		// Hunger dictates the hunt. A well-fed hunter (at/above its breeding
		// threshold) has no reason to chase: it patrols and takes only prey that
		// blunders into reach — less restrained than a grazer, which quits its
		// patch outright, but no longer the wanton killer that slaughtered prey it
		// couldn't use. Below that it hunts in earnest. Only genuine starvation
		// lifts the taboo on eating its own kind, so cannibalism is a last resort,
		// not routine.
		boolean sated = energy >= reproThreshold;
		boolean starving = energy < STARVE_FRACTION * energyCapacity();
		NPC prey = nearestPrey(LOS_RANGE, starving); // hunt as far as it can see
		double reach = prey == null ? 0
				: (getSize() + prey.getSize()) / 2.0 + ATTACK_REACH;
		if (prey != null && distance(prey.getX(), prey.getY(), prey.getZ()) <= reach) {
			lockTarget(prey);
			ecoAction = "attacking"; // in reach: bite whatever the hunger level
			sprinting = false; // in reach: bite, don't burn on the chase
			pinCount = 0; // biting in place is not a pin — hold off the give-up
			prey.damage(biteDamage(prey));
			energy += PRED_BITE_ENERGY; // predation feeds the hunter
		} else if (prey != null && !sated) {
			lockTarget(prey);
			ecoAction = starving ? "starving" : "hunting";
			sprinting = true; // burst pursuit at full speed — the costly gear
			chase(speed, turn);
		} else {
			// No prey, or well-fed and nothing in its mouth: patrol calmly. Label
			// it "sated" when it is deliberately letting visible prey be.
			ecoAction = (sated && prey != null) ? "sated" : "prowling";
			sprinting = false; // patrol calmly and cheaply — no sprint
			roam(speed * PRED_CRUISE, turn);
		}
		tryReproduce();
	}

	/**
	 * How hard this hunter's bite lands on a given quarry. Against anything its own
	 * size or smaller it is the full {@link #PRED_DAMAGE} — unchanged from a plain
	 * same-weight kill. Against something bigger the bite is scaled down by the
	 * size ratio, so a hunter punching above its weight needs proportionally more
	 * bites and the kill drags out: it has to stay latched on far longer, burning
	 * energy and giving the quarry (or a rival) time to break it up.
	 *
	 * <p>Only the damage is scaled, never {@link #PRED_BITE_ENERGY} — more bites at
	 * the same energy each means a bigger animal is a bigger meal overall, which is
	 * what makes the slow kill worth starting.
	 */
	private int biteDamage(NPC prey) {
		double ratio = getSize() / Math.max(1e-6, prey.getSize());
		double scale = Math.max(PRED_MIN_BITE_SCALE, Math.min(1.0, ratio));
		return Math.max(1, (int) Math.round(PRED_DAMAGE * scale));
	}

	/**
	 * Body survival reflex, shared by the hunter and any minded body: spot a pin —
	 * no net displacement over a window while the body is trying to move — and,
	 * once spotted, drive straight to open ground for a spell so nothing can jam
	 * the creature against an obstacle forever (a hunter turning in place against
	 * prey it can't reach, or a degenerate mind steering flat into a wall).
	 *
	 * <p>Detection uses a trailing ring sampled every tick: net displacement over
	 * {@link #PIN_WINDOW} ticks tells a real crawl (e.g. across drag-heavy mud)
	 * apart from a standstill, which a per-tick check cannot, and sampling every
	 * tick catches the pin within one window. While shaking loose it drives STRAIGHT
	 * along a cleared heading rather than handing back to roam/chase/the brain
	 * (which would re-aim into the same obstacle); if a step is fully zeroed it
	 * rotates by the golden angle so opposite headings can't cancel and re-pin.
	 *
	 * @param driveSpeed  speed to escape at while shaking loose
	 * @param trying      whether the body is currently trying to move -- a body
	 *                    deliberately holding still (a mind choosing not to move) is
	 *                    never force-marched
	 * @param terrainOnly only treat a pin as a jam when the obstacle ahead is
	 *                    terrain (a wall/water/hole), not another creature: pressing
	 *                    against a neighbour (a target, prey, mate) is benign and
	 *                    handled by collision separation, so a minded body at its
	 *                    goal is not shoved off it. The hunter passes false -- its
	 *                    turn-in-place pin on open ground has no terrain to gate on.
	 * @return true if it took over movement this tick (the caller must not also move)
	 */
	private boolean unstickIfPinned(double driveSpeed, boolean trying, boolean terrainOnly) {
		if (pinX == null) {
			pinX = new double[PIN_WINDOW];
			pinY = new double[PIN_WINDOW];
		}
		int pinSlot = (int) (getWorld().getTick() % PIN_WINDOW);
		if (pinCount >= PIN_WINDOW && huntGiveUp <= 0 && trying
				&& Math.hypot(X - pinX[pinSlot], Y - pinY[pinSlot]) < PIN_MIN_MOVE
				&& (!terrainOnly || terrainBlockedAhead())) {
			huntGiveUp = HUNT_GIVEUP_TICKS; // pinned: take over movement for a spell
			D = escapeHeading(); // aim at genuinely open ground the moment it is spotted
		}
		pinX[pinSlot] = X; // overwrite the PIN_WINDOW-ticks-ago sample with now
		pinY[pinSlot] = Y;
		if (pinCount < PIN_WINDOW) {
			pinCount++;
		}
		if (huntGiveUp > 0) {
			huntGiveUp--;
			if (Math.hypot(X - escLastX, Y - escLastY) < 1e-4) {
				D += 2.39996; // golden angle (~137.5 deg): sweep for a heading that moves
			}
			escLastX = X;
			escLastY = Y;
			ecoAction = "prowling";
			sprinting = false;
			move(driveSpeed, D);
			return true;
		}
		return false;
	}

	/** A heading whose next step is actually clear ground — used to shake a hunter
	 *  loose once it has pinned itself against water or a wall (roam on its own can
	 *  keep re-aiming into the same obstacle). Sweeps candidate directions, favouring
	 *  ones that turn away from the current (blocked) heading; falls back to a
	 *  U-turn if genuinely boxed in. Probes only a short step ahead: a longer reach
	 *  can diagonally clear a one-tile-wide water column that the hunter's actual
	 *  (much smaller) step falls straight into, so it would keep picking a heading
	 *  that stalls on the very next cell. */
	private static final double ESCAPE_PROBE = 0.25;

	private double escapeHeading() {
		double[] offs = { Math.PI, 3 * Math.PI / 4, -3 * Math.PI / 4, Math.PI / 2,
				-Math.PI / 2, Math.PI / 4, -Math.PI / 4 };
		for (double off : offs) {
			double a = D + off;
			double nx = X + Math.cos(a) * ESCAPE_PROBE, ny = Y + Math.sin(a) * ESCAPE_PROBE;
			if (getWorld().isConnectedSpace(X, Y, Z, nx, ny, Z)
					&& (isFlying() || !getWorld().getTile(nx, ny, Z).isWater())) {
				return a;
			}
		}
		return D + Math.PI;
	}

	/** A short label for what this creature did on its last tick (fleeing,
	 *  grazing, hunting, …), for the inspect panel. Empty if it has no eco role. */
	public String currentAction() {
		return isDead() ? "dead" : ecoAction;
	}

	/** Nearest strictly-smaller living creature within {@code radius} (its prey);
	 *  skips items and corpses. When {@code cannibal} is false it also skips other
	 *  predators — a hunter normally takes only actual prey and leaves its own kind
	 *  alone; only a starving hunter passes {@code cannibal} true to include smaller
	 *  rivals as food. Scans by proximity, NOT the facing-gated perception set: that
	 *  set is filled from a tile-local grid that only reaches ~1 tile, far short of
	 *  the range at which prey flee (THREAT_R), so a hunter relying on it could never
	 *  close on fleeing prey — it would lose sight of them the instant they bolted.
	 *  Sensing prey out to its full sight range (which out-ranges the flee radius)
	 *  lets a faster hunter actually run prey down, mirroring {@link #nearestThreat}. */
	private NPC nearestPrey(double radius, boolean cannibal) {
		NPC best = null;
		double bestD = radius;
		for (net.hedinger.prototype.engine.Entity e : getWorld().getEntities()) {
			// A hunter takes anything up to PRED_MAX_PREY_RATIO times its own size —
			// its own weight class, plus quarry somewhat above it. Body size is
			// clamped to Genome.SIZE_MAX for every creature alike, so a strictly-
			// smaller rule left the largest creatures permanently un-huntable with no
			// predator able to exist above them; reaching past its own size closes
			// that hole. Punching up is not free: the bite lands weaker the bigger the
			// quarry (see biteDamage), so the kill takes proportionally longer.
			if (!(e instanceof NPC n) || n == this || n.isDead() || n.isRemoved()
					|| n instanceof Item || n.getLvl() != getLvl()
					|| n.getSize() > getSize() * PRED_MAX_PREY_RATIO
					|| !isInLOS(n)) {
				// Same level only (can't reach a floor away), and only prey actually in
				// line of sight: a wall or a thicket (cover blocks sight) hides prey, and
				// the chase steering refuses to move toward a target it can't see — so
				// locking onto unseen prey would just freeze the hunter against the
				// obstacle. Requiring LOS also lets prey use cover as a real refuge.
				continue;
			}
			// Leave rival predators alone unless desperate: eating one's own kind is
			// a starvation measure, not everyday hunting.
			if (!cannibal && n instanceof TestNPC tn && tn.ecoRole().equals("predator")) {
				continue;
			}
			double d = distance(n.getX(), n.getY(), n.getZ());
			if (d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
	}

	private double haulPickX, haulPickY;
	private double haulDestX, haulDestY;
	private double haulHomeX, haulHomeY;
	private boolean haulDropped = false;
	private boolean haulReturned = false;

	/** A round trip: walk to the pickup spot, grab the item there, carry it to the
	 * remembered drop-off, set it down, then walk back home empty-handed.
	 * Perception is short-range (adjacent tiles only), so the courier navigates by
	 * remembered coordinates and grabs whatever item is in reach once it arrives. */
	private void thinkHaul() {
		// Phase 1 -- fetch: head to the pickup coordinate; grab the item on arrival.
		if (grabbing == null && !haulDropped) {
			Item crate = nearestItem();
			if (crate != null) {
				double reach = (getSize() + crate.getSize()) / 2.0;
				if (distance(crate) <= reach) {
					grab(crate);
					return;
				}
			}
			headFor(haulPickX, haulPickY); // walk to where the item is
			return;
		}
		// Phase 2 -- deliver: carry it to the drop-off coordinate and set it down.
		if (grabbing != null) {
			if (distance(haulDestX, haulDestY, Z) < 0.3) {
				drop(); // arrived: set the load down
				haulDropped = true;
			} else {
				headFor(haulDestX, haulDestY); // carry it to the drop-off
			}
			return;
		}
		// Phase 3 -- return: walk back home, now empty-handed.
		if (!haulReturned) {
			if (distance(haulHomeX, haulHomeY, Z) < 0.3) {
				haulReturned = true; // home again -- stop
			} else {
				headFor(haulHomeX, haulHomeY);
			}
		}
	}

	/**
	 * Heads for a remembered world coordinate using the engine's own move-to-target
	 * steering: it stores the goal in {@code (tX, tY)} and lets {@link #chase} ease
	 * the heading toward it (limited turn rate, so course changes are smooth arcs)
	 * and glide forward. Chase gates on line of sight to the target -- which now
	 * works on diagonals too -- so a courier can drive straight to an off-axis
	 * coordinate across open ground.
	 */
	private void headFor(double gx, double gy) {
		tX = gx;
		tY = gy;
		tZ = Z;
		chase(speed, turn);
	}

	/** The body/mind loop: sense the world into the vector, let the mind decide,
	 * then apply the actuator vector as intent. The mind never sees the world.
	 *
	 * <p>Survival reflex first: if the body has been trying to move (a real throttle
	 * last tick) but has made no headway, the body takes the wheel and drives to
	 * open ground for a spell, skipping the mind -- so a degenerate policy that
	 * steers flat into a wall can't pin the creature forever. A mind that is
	 * deliberately still (low throttle) is left alone, never force-marched. */
	private void thinkMinded() {
		if (mind == null) {
			return;
		}
		if (unstickIfPinned(speed * PRED_CRUISE, lastThrottle > 0.3, true)) {
			return; // body override: shaking loose, the mind sits this tick out
		}
		senseInto(sensors);
		mind.think(sensors, actuators);
		lastThrottle = clampUnit(actuators[AgentIO.A_THROTTLE]);
		actFrom(actuators);
	}

	/** Fills the egocentric, normalized {@link AgentIO} sensor vector. */
	private void senseInto(double[] s) {
		long now = getWorld().getTick();
		s[AgentIO.S_BIAS] = 1.0;
		double cap = energyCapacity();
		s[AgentIO.S_ENERGY] = cap > 0 ? clampUnit(getEnergy() / cap) : 0; // fraction of OWN tank
		s[AgentIO.S_FOOD] = getWorld().getTile(X, Y, Z).getVegetation(now)
				/ net.hedinger.prototype.engine.Tile.VEG_MAX;
		s[AgentIO.S_PHERO] = Math.tanh(sensePheromone());
		NPC near = nearestPerceived();
		if (near != null) {
			double dx = near.getX() - X, dy = near.getY() - Y;
			double dist = Math.hypot(dx, dy);
			s[AgentIO.S_NEAR_PROX] = 1.0 / (1.0 + dist);
			s[AgentIO.S_NEAR_BEARING] = wrap(Math.atan2(dy, dx) - D) / Math.PI;
			net.hedinger.prototype.entities.Genome og = near.getGenome();
			s[AgentIO.S_NEAR_SIM] = (genome != null && og != null) ? genome.similarityTo(og) : 0;
			s[AgentIO.S_NEAR_SIZEADV] = Math.tanh(getSize() / Math.max(1e-6f, near.getSize()) - 1);
		} else {
			s[AgentIO.S_NEAR_PROX] = 0;
			s[AgentIO.S_NEAR_BEARING] = 0;
			s[AgentIO.S_NEAR_SIM] = 0;
			s[AgentIO.S_NEAR_SIZEADV] = 0;
		}
		s[AgentIO.S_CLOCK] = Math.sin(now * 0.3 + getID());
		double ax = getX() + Math.cos(D), ay = getY() + Math.sin(D);
		s[AgentIO.S_BLOCKED] = getWorld().isConnectedSpace(getX(), getY(), getLvl(), ax, ay, getLvl())
				? 0.0 : 1.0; // wall/edge one tile ahead in the heading
		// Dedicated item sense: nearest inanimate object, its bearing and kind.
		Item item = nearestItem();
		if (item != null) {
			double dx = item.getX() - X, dy = item.getY() - Y;
			double dist = Math.hypot(dx, dy);
			s[AgentIO.S_ITEM_PROX] = 1.0 / (1.0 + dist);
			s[AgentIO.S_ITEM_BEARING] = wrap(Math.atan2(dy, dx) - D) / Math.PI;
			s[AgentIO.S_ITEM_KIND] = item.kindSignal();
		} else {
			s[AgentIO.S_ITEM_PROX] = 0;
			s[AgentIO.S_ITEM_BEARING] = 0;
			s[AgentIO.S_ITEM_KIND] = 0;
		}
		senseFieldAndBody(s); // wider hunt/flee/kin channels, body state, obstacle whiskers
	}

	/** Fills the wider-range hunt/flee/kin channels plus body-state and obstacle
	 *  whiskers -- the senses a hybrid mind needs that the short, facing-gated
	 *  nearest-neighbour channel cannot give. One pass over perceivable neighbours
	 *  feeds the prey, threat and kin gradients at once, at full sight range. */
	private void senseFieldAndBody(double[] s) {
		double preyD = Double.MAX_VALUE, threatD = Double.MAX_VALUE;
		double preyDx = 0, preyDy = 0, threatDx = 0, threatDy = 0;
		double kinX = 0, kinY = 0;
		for (net.hedinger.prototype.engine.Entity e : getWorld().getEntities()) {
			if (!(e instanceof NPC n) || n == this || n.isDead() || n.isRemoved()
					|| n instanceof Item || n.getLvl() != getLvl() || !isInLOS(n)) {
				continue; // same level, in line of sight (cover and walls hide neighbours)
			}
			double dx = n.getX() - X, dy = n.getY() - Y;
			double dist = Math.hypot(dx, dy);
			if (dist > LOS_RANGE) {
				continue;
			}
			if (n.getSize() < getSize() && dist < preyD) {
				preyD = dist;
				preyDx = dx;
				preyDy = dy;
			}
			if (n.getSize() > getSize() && dist < threatD) {
				threatD = dist;
				threatDx = dx;
				threatDy = dy;
			}
			net.hedinger.prototype.entities.Genome og = n.getGenome();
			if (genome != null && og != null) {
				double sim = genome.similarityTo(og);
				kinX += sim * dx; // similarity-weighted pull toward kin
				kinY += sim * dy;
			}
		}
		if (preyD < Double.MAX_VALUE) {
			s[AgentIO.S_PREY_PROX] = 1.0 / (1.0 + preyD);
			s[AgentIO.S_PREY_BEARING] = wrap(Math.atan2(preyDy, preyDx) - D) / Math.PI;
		} else {
			s[AgentIO.S_PREY_PROX] = 0;
			s[AgentIO.S_PREY_BEARING] = 0;
		}
		if (threatD < Double.MAX_VALUE) {
			s[AgentIO.S_THREAT_PROX] = 1.0 / (1.0 + threatD);
			s[AgentIO.S_THREAT_BEARING] = wrap(Math.atan2(threatDy, threatDx) - D) / Math.PI;
		} else {
			s[AgentIO.S_THREAT_PROX] = 0;
			s[AgentIO.S_THREAT_BEARING] = 0;
		}
		s[AgentIO.S_KIN_BEARING] = (kinX != 0 || kinY != 0)
				? wrap(Math.atan2(kinY, kinX) - D) / Math.PI
				: 0;

		s[AgentIO.S_HEALTH] = clampUnit(getHealth() / 100.0);
		s[AgentIO.S_CARRIED] = isGrabbed() ? 1.0 : (getAttachTarget() != null ? -1.0 : 0.0);

		// Obstacle whiskers 45 deg off each shoulder, and a drowning/falling hazard
		// dead ahead (non-flyer only): the sensed half of the survival reflex.
		s[AgentIO.S_WHISKER_L] = blockedAt(D - Math.PI / 4);
		s[AgentIO.S_WHISKER_R] = blockedAt(D + Math.PI / 4);
		double hx = X + Math.cos(D), hy = Y + Math.sin(D);
		net.hedinger.prototype.engine.Tile ahead = getWorld().getTile(hx, hy, Z);
		boolean hazard = !isFlying() && ahead != null
				&& (ahead.isWater()
						|| ahead.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
		s[AgentIO.S_HAZARD_AHEAD] = hazard ? 1.0 : 0.0;
	}

	/** 1 if a one-tile step along {@code heading} lands on impassable ground, else 0. */
	private double blockedAt(double heading) {
		double nx = X + Math.cos(heading), ny = Y + Math.sin(heading);
		return getWorld().isConnectedSpace(X, Y, Z, nx, ny, Z) ? 0.0 : 1.0;
	}

	/** True if the tile one step along the current heading is impassable terrain (a
	 *  wall, or -- for a non-flyer -- water or an open hole), as opposed to clear
	 *  ground where any standstill is a neighbour collision, not a terrain jam. */
	private boolean terrainBlockedAhead() {
		double nx = X + Math.cos(D), ny = Y + Math.sin(D);
		if (!getWorld().isConnectedSpace(X, Y, Z, nx, ny, Z)) {
			return true;
		}
		net.hedinger.prototype.engine.Tile t = getWorld().getTile(nx, ny, Z);
		return t != null && !isFlying()
				&& (t.isWater() || t.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
	}

	/** Applies the actuator vector as engine intent (movement + gated actions). */
	private void actFrom(double[] a) {
		double t = clamp(a[AgentIO.A_TURN], -1, 1);
		double throttle = clampUnit(a[AgentIO.A_THROTTLE]);
		D = wrap(D + t * MAX_TURN); // steer
		sprinting = a[AgentIO.A_SPRINT] > 0.5; // engage the costly burst gear
		double topSpeed = sprinting ? speed * SPRINT_SPEED_MULT : speed;
		if (throttle > 0.02) {
			move(throttle * topSpeed, D);
		}
		// Vertical intent: the body only drops through (or steps onto) a hole when
		// the mind actively wills the descent -- otherwise it treats an open hole as
		// a ledge and stops at the lip. Climbing a ramp stays automatic where one
		// exists (going up is not a survival hazard), so this gates only the fall.
		descendIntent = a[AgentIO.A_VERTICAL] < -0.5;
		double eaten = 0;
		if (a[AgentIO.A_EAT] > 0.5) {
			eaten = graze(grazeDemand());
			totalIntake += eaten;
			eatNearestItem(); // also devour a food (or bite a hazard) in reach
		}
		if (a[AgentIO.A_DEPOSIT] > 0.5) {
			depositPheromone(NEST_DEPOSIT * 0.25);
		}
		boolean bit = false;
		if (a[AgentIO.A_ATTACK] > 0.5) {
			bit = attackNearest();
			attackNearestItem(); // an object in reach can be smashed too
		}
		boolean bred = false;
		if (a[AgentIO.A_MATE] > 0.5) {
			bred = reproduce();
		}
		// A plain-language label for what this mind actually DID, so the viewer can
		// follow a minded creature and read its behaviour without opening the mind
		// inspector. Deliberately reports outcomes, not intent: a starter brain
		// holds eat and mate high permanently, so labelling the actuators would
		// read "mating" forever regardless of what the creature achieved.
		if (bit) {
			ecoAction = "attacking";
		} else if (bred) {
			ecoAction = "breeding";
		} else if (eaten > 0) {
			ecoAction = "grazing";
		} else if (throttle > 0.02) {
			ecoAction = sprinting ? "running" : "wandering";
		} else {
			ecoAction = "resting";
		}
		// Grab: seize and carry a smaller neighbour while the actuator is high,
		// release the moment it drops (or the captive is gone).
		if (grabbing != null && grabbing.isRemoved()) {
			drop();
		}
		if (a[AgentIO.A_GRAB] > 0.5) {
			grabNearestSmaller();
		} else {
			drop();
		}
		// Attach: latch onto and ride a larger host while the actuator is high, let
		// go when it drops. A rider self-releases; a captive stays held (release is
		// the captor's call, via drop()).
		if (a[AgentIO.A_ATTACH] > 0.5) {
			attachToLarger();
		} else if (getAttachTarget() != null && !isGrabbed()) {
			detach();
		}
		// Buck: the same struggle actuator, seen from the carrier's side. When we
		// are carrying riders (and not carried ourselves), struggling means trying
		// to shake them off -- which tires us and, per rider, builds up buck effort.
		if (getCarriedLoad() > 0 && getAttachTarget() == null && a[AgentIO.A_STRUGGLE] > 0) {
			buckRiders(clampUnit(a[AgentIO.A_STRUGGLE]));
		}
	}

	/** Shakes at riders clinging to this creature. Bucking costs energy every tick;
	 * for each voluntary rider it accumulates effort against that rider's grip
	 * (a smaller rider clings tighter, so it is far harder to throw). A rider whose
	 * grip is overcome is flung clear and cannot re-attach for a while. Its own
	 * grabbed captive (which it wants to keep) is never bucked. */
	private void buckRiders(double s) {
		energy -= s * BUCK_SELF_COST;
		if (energy < 0) {
			energy = 0;
		}
		for (net.hedinger.prototype.engine.Entity e : getWorld().getEntities()) {
			if (e.getAttachTarget() != this || e.isGrabbed() || e.isRemoved()) {
				continue; // not a voluntary rider of ours
			}
			e.addBuckPressure(s);
			double grip = BUCK_GRIP * (getSize() / e.getSize()); // smaller rider -> tighter grip
			if (e.getBuckPressure() >= grip) {
				e.detach(); // thrown clear (resets its buck pressure)
				if (e instanceof NPC) {
					((NPC) e).startAttachCooldown(BUCK_COOLDOWN);
				}
			}
		}
	}

	/** Grabs the nearest perceived smaller neighbour in reach (predatory seize). */
	private void grabNearestSmaller() {
		if (grabbing != null || getAttachTarget() != null) {
			return; // already carrying one, or being carried (can't do both)
		}
		for (NPC n : targets.values()) { // nearest-first (keyed by distance)
			if (n == this || n.isDead() || n.isRemoved()) {
				continue;
			}
			if (n.getSize() <= getSize() && grab(n)) {
				return;
			}
		}
	}

	/** Latches onto the nearest perceived larger neighbour in reach (hitch-hike). */
	private void attachToLarger() {
		if (getAttachTarget() != null) {
			return; // already riding something
		}
		for (NPC n : targets.values()) { // nearest-first
			if (n == this || n.isDead() || n.isRemoved()) {
				continue;
			}
			if (n.getSize() > getSize() && attachTo(n)) {
				return;
			}
		}
	}

	/** A grabbed captive's only outlet: it senses, then may struggle -- making
	 * itself costlier to haul while tiring itself -- and communicate (lay a
	 * distress pheromone). It cannot move, feed, fight, mate, or break free. */
	private void struggleWhileHeld() {
		if (mind == null) {
			return; // a mindless captive just goes limp
		}
		senseInto(sensors);
		mind.think(sensors, actuators);
		double s = clampUnit(actuators[AgentIO.A_STRUGGLE]);
		if (s > 0) {
			if (getAttachTarget() instanceof NPC) {
				// Fighting makes the captive heavier to hold for its captor...
				((NPC) getAttachTarget()).drainEnergy(getSize() * s * STRUGGLE_CARRIER_COST);
			}
			energy -= s * STRUGGLE_SELF_COST; // ...and exhausts the captive itself
			if (energy < 0) {
				energy = 0;
			}
		}
		// Communicate: even pinned, a captive can still lay pheromone -- a distress
		// marker other creatures could read.
		if (actuators[AgentIO.A_DEPOSIT] > 0.5) {
			depositPheromone(NEST_DEPOSIT * 0.25);
		}
	}

	/** Bites the nearest perceived neighbour if it is in reach: it takes damage
	 * (and dies once its health is gone) and the attacker gains a little energy.
	 * Returns true if a bite actually landed. */
	private boolean attackNearest() {
		NPC near = nearestPerceived();
		if (near == null || near == this || near.isDead()) {
			return false;
		}
		double reach = (getSize() + near.getSize()) / 2.0 + ATTACK_REACH;
		if (distance(near.getX(), near.getY(), near.getZ()) > reach) {
			return false;
		}
		near.damage(ATTACK_DAMAGE);
		energy += BITE_ENERGY; // predation feeds the attacker
		return true;
	}

	/** The reproduce actuator: mates with the nearest perceived compatible partner
	 * in reach (a crossover child inheriting both crossed minds); with no partner
	 * in reach it buds asexually instead. Reproduction is entirely brain-driven --
	 * nothing reproduces unless its mind fires this actuator. */
	private boolean reproduce() {
		if (!fertile()) {
			return false;
		}
		for (NPC n : targets.values()) {
			if (canMateWith(n)) {
				double reach = (getSize() + n.getSize()) / 2.0 + MATE_REACH;
				if (distance(n.getX(), n.getY(), n.getZ()) <= reach) {
					return reproduceWith(n); // sexual: crossover child
				}
			}
		}
		return tryReproduce(); // no compatible partner in reach -> bud asexually
	}

	/** Nearest living perceived neighbour (excluding self and inanimate items), or
	 * null. Items have their own dedicated sense/interaction path. */
	private NPC nearestPerceived() {
		NPC near = null;
		double best = Double.MAX_VALUE;
		for (NPC n : targets.values()) {
			if (n == this || n.isDead() || n instanceof Item) {
				continue;
			}
			double d = distance(n.getX(), n.getY(), n.getZ());
			if (d < best) {
				best = d;
				near = n;
			}
		}
		return near;
	}

	/** Nearest perceived inanimate {@link Item}, or null. */
	private Item nearestItem() {
		Item near = null;
		double best = Double.MAX_VALUE;
		for (NPC n : targets.values()) {
			if (!(n instanceof Item) || n.isRemoved()) {
				continue;
			}
			double d = distance(n.getX(), n.getY(), n.getZ());
			if (d < best) {
				best = d;
				near = (Item) n;
			}
		}
		return near;
	}

	/** Nearest item within interaction reach (touching + a small margin), or null. */
	private Item itemInReach() {
		Item item = nearestItem();
		if (item == null) {
			return null;
		}
		double reach = (getSize() + item.getSize()) / 2.0 + ATTACK_REACH;
		return distance(item.getX(), item.getY(), item.getZ()) <= reach ? item : null;
	}

	/** Eats the nearest food/hazard item in reach: food feeds, a hazard bites back. */
	private void eatNearestItem() {
		Item item = itemInReach();
		if (item != null && item.isEdible()) {
			item.beEatenBy(this);
		}
	}

	/** Strikes the nearest item in reach: whittles a crate down (spilling food when
	 * it breaks), or takes a wound from a hazard. */
	private void attackNearestItem() {
		Item item = itemInReach();
		if (item != null) {
			item.beAttackedBy(this, ATTACK_DAMAGE);
		}
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static double clampUnit(double v) {
		return v < 0 ? 0 : (v > 1 ? 1 : v);
	}

	private static double wrap(double a) {
		while (a > Math.PI) {
			a -= 2 * Math.PI;
		}
		while (a < -Math.PI) {
			a += 2 * Math.PI;
		}
		return a;
	}

	/** Forages for energy; when fertile, seeks a compatible partner and breeds
	 * sexually (a crossover child). Falls back to grazing/roaming otherwise. */
	private void thinkMater() {
		double intake = graze(grazeDemand());
		totalIntake += intake;
		if (fertile()) {
			NPC partner = findMate();
			if (partner != null) {
				double reach = (getSize() + partner.getSize()) / 2.0 + MATE_REACH;
				if (distance(partner) <= reach) {
					if (reproduceWith(partner)) {
						return;
					}
				} else {
					move(speed, Math.atan2(partner.getY() - Y, partner.getX() - X));
					return;
				}
			}
		}
		if (intake < grazeDemand() * 0.15) {
			roam(speed, turn); // patch thinning (or no partner) -> wander
		}
	}

	/** The nearest perceivable NPC this one can mate with, or null. */
	private NPC findMate() {
		mates = getTargets(mates, "", false);
		for (NPC other : mates.values()) {
			if (canMateWith(other)) {
				return other;
			}
		}
		return null;
	}

	/** True once a metabolic creature already has enough energy to reproduce — its
	 *  biological goal — so more grazing just strips the pasture for a surplus it
	 *  banks little of (it breeds the reserve away before ever topping out). Below
	 *  this it grazes to refill; at/above it, it stops cropping and moves on.
	 *  Always false for non-metabolic grazers (no energy tank). */
	private boolean sated() {
		return metabolic && energy >= reproThreshold;
	}

	/** Grazes for energy and buds a mutated child once well-fed. When
	 *  {@link #vigilant} (the eco herbivore), it flees predators and herds. */
	private void thinkBreeder() {
		if (vigilant) {
			NPC threat = nearestThreat(THREAT_R);
			if (threat != null) {
				// Bolt: pick a waypoint directly away from the predator and run.
				// Fleeing pre-empts grazing/breeding — survival first.
				ecoAction = "fleeing";
				roam(speed, turn, Math.atan2(Y - threat.getY(), X - threat.getX()));
				return;
			}
		}
		// A full creature stops cropping: grazing past the tank just wastes the
		// intake and needlessly holds the grass down, so it grazes only when it
		// has room to fill.
		double intake = sated() ? 0 : graze(grazeDemand());
		totalIntake += intake;
		if (tryReproduce()) {
			ecoAction = "breeding";
			return;
		}
		if (sated()) {
			// Fully fed: drift off the patch and let the grass recover.
			ecoAction = "sated";
			roam(speed, turn);
			return;
		}
		if (intake < grazeDemand() * 0.15) {
			double herd = vigilant ? herdDir(HERD_R) : Double.NaN;
			if (!Double.isNaN(herd)) {
				ecoAction = "herding";
				roam(speed, turn, herd); // regroup with kin
			} else {
				ecoAction = "foraging";
				roam(speed, turn); // patch thinning -> find fresh grass
			}
		} else {
			ecoAction = "grazing";
		}
	}

	/** Nearest living predator within {@code radius} (eco threat sense), or null.
	 *  Keys on {@link #ecoRole()} rather than raw size, so a herbivore flees
	 *  hunters — not merely larger herbivores. Scans proximity, not the
	 *  facing-gated perception set, so a predator can't sneak up from behind. */
	private NPC nearestThreat(double radius) {
		NPC best = null;
		double bestD = radius;
		for (net.hedinger.prototype.engine.Entity e : getWorld().getEntities()) {
			if (!(e instanceof TestNPC t) || t == this || t.isDead() || t.isRemoved()
					|| t.getLvl() != getLvl() || !t.ecoRole().equals("predator")) {
				continue; // same level only: no fleeing a hunter a floor away
			}
			double d = distance(t.getX(), t.getY(), t.getZ());
			if (d < bestD) {
				bestD = d;
				best = t;
			}
		}
		return best;
	}

	/** Heading toward the centroid of kin (same eco role) within {@code radius},
	 *  or NaN when alone — a gentle cohesion pull, so a herd loosely aggregates
	 *  without clumping into a single dot. */
	private double herdDir(double radius) {
		double sx = 0, sy = 0;
		int k = 0;
		for (net.hedinger.prototype.engine.Entity e : getWorld().getEntities()) {
			if (!(e instanceof TestNPC t) || t == this || t.isDead() || t.isRemoved()
					|| t.getLvl() != getLvl() || !t.ecoRole().equals("prey")) {
				continue; // same level only: herd with kin you can actually reach
			}
			double d = distance(t.getX(), t.getY(), t.getZ());
			if (d > radius || d < 1e-6) {
				continue;
			}
			sx += t.getX();
			sy += t.getY();
			k++;
		}
		return k == 0 ? Double.NaN : Math.atan2(sy / k - Y, sx / k - X);
	}

	private boolean homing = false;

	/** Forages; when ready to breed, commits to homing and births at the nest. */
	private void thinkNester() {
		double intake = graze(grazeDemand());
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
		if (intake < grazeDemand() * 0.15) {
			roam(speed, turn);
		}
	}

	@Override
	protected net.hedinger.prototype.entities.NPC spawnOffspring() {
		if (genome == null) {
			return null;
		}
		// Asexual: a mutated copy of this genome, born at the parent's spot. When the
		// genome carries a brain, Genome.child mutates the inherited program too.
		Genome childG = Genome.child(genome, 0.1);
		TestNPC child;
		if (behavior == Behavior.MINDED) {
			child = brainedBreeder(X, Y, Z, childG);
		} else if (behavior == Behavior.PREDATOR) {
			child = predator(X, Y, Z, childG).withDeathspan(deathspan); // lineage shares corpse lifespan
		} else {
			child = behavior == Behavior.NEST ? nester(X, Y, Z, childG) : breeder(X, Y, Z, childG);
			child.vigilant = vigilant; // a vigilant lineage stays predator-aware
			child.withDeathspan(deathspan);
		}
		return child.withGeneration(generation + 1);
	}

	@Override
	protected net.hedinger.prototype.entities.NPC spawnOffspring(net.hedinger.prototype.entities.NPC partner) {
		if (genome == null || partner.getGenome() == null) {
			return null;
		}
		// Sexual: a mutated crossover of both parents' genomes (including their
		// crossed minds, when both carry a brain), born at this spot.
		net.hedinger.prototype.entities.Genome childG =
				net.hedinger.prototype.entities.Genome.child(genome, partner.getGenome(), 0.1);
		TestNPC child = behavior == Behavior.MINDED ? brainedBreeder(X, Y, Z, childG) : mater(X, Y, Z, childG);
		// A crossover child is one deeper than the more-advanced parent's lineage.
		int parentGen = generation;
		if (partner instanceof TestNPC tp) {
			parentGen = Math.max(parentGen, tp.generation);
		}
		return child.withGeneration(parentGen + 1);
	}

	/** Eats the substrate underfoot; wanders on once a patch is grazed down. */
	private void thinkGraze() {
		double intake = graze(grazeDemand());
		totalIntake += intake;
		// Stay and crop the patch down; only move on when it is nearly bare, so
		// grazing bores a clear depleted spot before the herbivore wanders off.
		if (intake < grazeDemand() * 0.15) {
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

	/** Canonical action key for the hovering overlay glyph, or null if none. */
	public String actionKey() {
		if (isDead()) {
			return null;
		}
		if (grabbing != null || (getAttachTarget() != null && !isGrabbed())) {
			return "grab"; // carrying a captive, or riding a host
		}
		switch (behavior) {
		case GENOME:
			switch (lastAction) {
			case ATTACK:
				return "attack";
			case FLEE:
				return "flee";
			case MATE:
				return "mate";
			case AFFILIATE:
				return "affiliate";
			default:
				return null;
			}
		case GRAZE:
		case BREEDER:
		case MATER:
			return "graze";
		case NEST:
			return homing ? "nest" : "graze";
		default:
			return null;
		}
	}

	/** Ecosystem role, for the world steward's population census: {@code
	 *  "predator"}, {@code "prey"}, or {@code ""} for anything else. */
	public String ecoRole() {
		if (behavior == Behavior.PREDATOR) {
			return "predator";
		}
		return behavior == Behavior.BREEDER ? "prey" : "";
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
		if (behavior == Behavior.BREEDER || behavior == Behavior.NEST || behavior == Behavior.MATER) {
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
			s.append(isGrabbed() ? " carried" : " riding");
		}
		if (isDead()) {
			s.append(" dead");
		} else if (health < 100) {
			s.append(" hp").append(health);
		}
		return s.toString();
	}
}
