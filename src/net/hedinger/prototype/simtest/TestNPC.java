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

	/**
	 * What a body can turn into energy. This is the ONE thing that makes a
	 * scavenger a scavenger — it is a property of the body, not of the mind, so a
	 * minded scavenger runs the same brain and the same forage intent as any other
	 * minded creature and simply finds carrion where a grazer finds grass.
	 *
	 * <p>Deliberately not a gene. Diet decides what a lineage can eat at all, and a
	 * mutation that flipped it would strand a creature in a world with none of its
	 * food; the genome already carries everything that should drift.
	 */
	public enum Diet {
		/** Vegetation from the tile underfoot. */
		HERBIVORE,
		/** Living bodies, taken by force. Predation is its own hardcoded loop. */
		CARNIVORE,
		/** Carrion. Eating a corpse both feeds the eater and hastens the corpse's
		 *  return to the world -- {@link net.hedinger.prototype.entities.NPC#eat}
		 *  ages a dead body toward removal, so a fed scavenger IS decomposition. */
		SCAVENGER,
		/** A bigger living body, eaten slowly from on top of it. A parasite
		 *  latches onto a host it could never bring down in a fight and drains
		 *  it bite by bite while it rides; its mouth works on nothing else — it
		 *  cannot graze — and predators leave it alone (too small and too foul
		 *  to be worth a bite), so its checks are the host's bucking, the
		 *  drained host dying under it, and its own four books. */
		PARASITE,
	}

	private Diet diet = Diet.HERBIVORE;

	/** Damage a predator's bite does to prey of its own size or smaller (prey
	 *  health 100 -> a few-second kill). Scaled down against bigger quarry — see
	 *  {@link #biteDamage}. */
	private static final int PRED_DAMAGE = 20;
	/** How much larger than itself a hunter will take on, as a multiple of its own
	 *  body size. Above 1 so a smaller hunter can pick a fight it is not built to
	 *  win quickly: it lands weaker bites and needs far more of them, which is the
	 *  whole trade — a long, costly, interruptible kill instead of a clean one. */
	private static final double PRED_MAX_PREY_RATIO = 1.5;
	/** Full health, and so the whole of a body: a bite that removes this much of a
	 *  creature has consumed all of it. Health is flat across every body size, which
	 *  is why the <i>meal</i> has to carry the size instead — see {@link #MEAT_ENERGY}. */
	private static final int FULL_BODY_HEALTH = 100;
	/**
	 * Energy in a whole carcass, per unit of body mass ({@code REF_SIZE} = 1). A
	 * body is worth what it weighs, so the meal tracks the quarry rather than the
	 * effort: an animal that takes twice as many bites to bring down is not twice
	 * as nutritious, it is just slower to eat.
	 *
	 * <p>This replaces a flat per-bite payout, under which a mouse and an animal the
	 * hunter's own size were worth exactly the same (measured: 2.49 either way). That
	 * pointed selection at the smallest, easiest quarry and left no niche for a large
	 * hunter — the one corner of the economy where mass did not appear, while
	 * metabolism, movement and tank capacity all scale with it.
	 */
	public static final double MEAT_ENERGY = 2.5;
	/**
	 * Energy in a carcass, per unit of its mass. Meat is meat: a body is worth the
	 * same whether the eater killed it or found it, so this is {@link #MEAT_ENERGY}
	 * rather than a number of its own.
	 *
	 * <p>What separates the two trophic levels is not the price of the meat. A
	 * hunter pays for its meal in the chase and the risk of taking on something
	 * that fights back; a scavenger pays in search — measured across five seeds it
	 * had a carcass within biting distance on 0.87 per cent of its ticks and spent
	 * the rest walking between bodies. Those are the real costs, and they are
	 * already charged, in energy and in time, everywhere else in the loop.
	 */
	public static final double CARRION_ENERGY = MEAT_ENERGY;
	/**
	 * How much of a carcass a scavenger can process in one tick, as a fraction of
	 * the whole body. A carcass is a meal taken over time, not a pickup: at this
	 * rate a body takes ~2 seconds to strip, long enough that two scavengers on one
	 * corpse genuinely compete and short enough that it does not pin them in place.
	 */
	public static final double CARRION_BITE = 0.015;
	/** How far (tiles, beyond touching) a scavenger can reach a carcass. */
	private static final double CARRION_REACH = 0.6;
	/**
	 * How far a scavenger can locate a carcass — smell, not sight, so it reaches
	 * well past {@code LOS_RANGE} and through the dark.
	 *
	 * <p>A grazer stands in its food: measured, one is in view on 98% of its ticks
	 * and averages 1.6 tiles away. A scavenger's food is wherever something happened
	 * to die, and at plain sight range it had a target on only 38% of ticks — the
	 * other 62% it wandered at random. Smell is the sense the niche actually runs
	 * on, so it is not gated on the eye: a body is found at this range whatever the
	 * light and whichever way the scavenger happens to be facing.
	 */
	/**
	 * How long a sound stays audible after it arrives, in ticks.
	 *
	 * <p>A sound is an event, not a place: nothing keeps making it, so the channel
	 * has to fall silent by itself or a creature would steer forever toward a
	 * scream from five minutes ago. Short enough that acting on it is acting on
	 * news, long enough that a body has time to turn and commit. Recency is NOT
	 * folded into the proximity — that would conflate "close" with "just now" and
	 * leave a mind unable to tell a distant shriek from an old one — so the
	 * channel reads at full strength for this long and then goes to zero.
	 */
	public static final int EARSHOT_MEMORY = 33;
	/**
	 * How far a kill can be heard, in tiles, per unit of the dying body's mass.
	 *
	 * <p>Scaled by the victim rather than the killer, because what makes the noise
	 * is the thing being killed. A big animal going down is heard across a
	 * neighbourhood; something small is barely a rustle. That is what makes the
	 * channel worth reading: the loudest events are also the ones worth walking
	 * toward, since a big kill leaves a big carcass.
	 */
	public static final double KILL_LOUDNESS = 6.0;

	/** The most recent sound to reach this body, and when — the sensed half of
	 *  {@link AgentIO#S_SOUND_PROX}. Held here rather than on Entity because only
	 *  a body with a mind has anything to do with it. */
	private double heardX, heardY;
	private long heardAt = Long.MIN_VALUE;

	public static final double CARRION_SCENT_R = 10.0;
	/**
	 * How much faster a scavenger's top speed is than the body it was built from —
	 * the ranging adaptation that pays for the scent.
	 *
	 * <p>Measured, this is the constraint the niche actually failed on. A carcass
	 * lasts about as long as a child takes to grow (roughly 800-1600 ticks), and a
	 * scavenger that smells one 20 tiles off closes at ~0.012 tiles per tick — it
	 * arrives, when it arrives at all, to bare ground. On seed 11 the cohort had a
	 * carcass in view on 58 per cent of its ticks and fed on 0.28 of them. Smelling
	 * food you cannot reach in time is not a living, so the sense is worth nothing
	 * without the legs.
	 *
	 * <p>Movement costs the square of speed, so this is a trade rather than a gift:
	 * a scavenger burns more travel energy per carcass and eats the ones a slower
	 * body never reaches at all.
	 */
	public static final double SCAVENGER_STRIDE = 2.5;
	/**
	 * What a scavenger pays to cover ground, as a share of an ordinary body's bill.
	 * Ranging cheaply is the other half of the same adaptation — a vulture's living
	 * is made by covering far more ground per calorie than the animals it eats, not
	 * by sprinting — and it is what makes {@link #SCAVENGER_STRIDE} affordable at
	 * all, since travel is billed on the square of speed. Together they come to
	 * roughly 2.5x the distance for the energy an ordinary body spends.
	 */
	public static final double SCAVENGER_TRAVEL = 0.16;
	/** Fraction of top speed a predator patrols at while no prey is in sight — it
	 *  lopes around cheaply and opens up to full speed only for a real pursuit. */
	private static final double PRED_CRUISE = 0.6;
	/** Hunger at which a hunter starts hunting in earnest (VITALS.md: appetite,
	 *  not tank headroom, is what sends a predator after prey). Sits at the
	 *  NEED_LOW seek line, so appetite returns in twice the time thirst does. */
	public static final double PRED_HUNT_HUNGER = 0.5;
	/** Hunger at or above which a predator is starving — desperate enough to
	 *  break the taboo and hunt its own kind. A hunter only pegs this high when
	 *  it has been failing to feed, so cannibalism stays a last resort. */
	public static final double STARVE_HUNGER = 0.9;
	/** Below this hunger a hunter stops killing altogether: the meal would not
	 *  fit its stomach, so the prey would die for nothing. */
	public static final double PRED_FULL_HUNGER = 0.05;
	/** Fraction of its (adult-sized) tank a creature is born holding. Comfortably
	 *  fed, but below the breeding line, so a new body has to make its own living
	 *  before it can make another one. */
	public static final double BORN_FRACTION = 0.6;
	/** Fraction of the tank that has to be full before a creature will breed. A
	 *  big body's tank is bigger, so a big creature must eat more, not merely as
	 *  much, before it reproduces. */
	public static final double REPRO_FRACTION = 0.75;
	/** Fraction of the tank each parent spends on an offspring. Below
	 *  {@link #REPRO_FRACTION}, so breeding leaves a parent alive and fed rather
	 *  than emptied. */
	public static final double REPRO_COST_FRACTION = 0.5;
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
	public static final int HUNT_GIVEUP_TICKS = 45;

	/**
	 * Vegetation cropped per tick by a reference-size grazer, still well above the
	 * tile's regrowth rate so a patch does run down. Bigger grazers crop faster in
	 * proportion to their mass — see {@link #grazeDemand()}.
	 *
	 * <p>Sized so stripping a tile is real work: the largest body in the world takes
	 * about four seconds of standing on one patch and eating, a reference-size body
	 * about ten. The original rate cleared a tile in 20 ticks — well under a second —
	 * which meant a herd erased its pasture faster than it could spread out over it,
	 * and the substrate behaved like a switch rather than a resource.
	 *
	 * <p>This and {@link NPC#GRASS_ENERGY} multiply into a herbivore's income per
	 * tick, so slowing the crop without raising the energy per unit starves the herd
	 * — measured, not assumed. See that constant for the calibration.
	 */
	private static final double GRAZE_DEMAND = 0.003;

	/** This grazer's per-tick appetite: {@link #GRAZE_DEMAND} scaled by body size,
	 *  so a bigger grazer takes bigger bites (and depletes a patch faster). A
	 *  sated body has no appetite at all — graze() additionally bounds every
	 *  bite by the stomach room left, so nothing strips ground it can't digest. */
	private double grazeDemand() {
		return hunger <= 0 ? 0 : GRAZE_DEMAND * bodyMass();
	}
	/** Eco herbivore: flees any predator within this radius (tiles). */
	private static final double THREAT_R = 6.0;
	/** Eco herbivore: regroups with kin within this radius when a patch thins. */
	private static final double HERD_R = 7.0;
	/** Pheromone laid at the nest at each birth; >> per-tick evaporation, so a
	 *  repeatedly-marked nest cloud builds a strong persistent peak. */
	public static final double NEST_DEPOSIT = 12.0;
	/** How far a nester can smell its nest when homing to breed. */
	public static final int NEST_SENSE_R = 8;
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
	private boolean alwaysInteract = false; // fixture: a standing order to press buttons
	private int generation = 0; // 0 = spawned by the world; a child is its parent's + 1
	private boolean handPlaced = false; // placed by a person, not by the steward
	private String ecoAction = ""; // what this creature did on its last tick (for inspect)
	/**
	 * Ticks a momentary act stays on display after the instant it happened. A bite
	 * or a birth occupies a single tick, and the viewer only samples ~10 times a
	 * second, so without a hold those events flash past between frames and are
	 * effectively invisible — you would never see a heart. Continuous states
	 * (grazing, hunting, fleeing) need no hold: they persist on their own.
	 */
	private static final int ACTION_HOLD = 25; // ~0.75 s at 33 t/s
	private int actionHold = 0;

	/**
	 * Records what this creature is doing. A {@code momentary} act latches for
	 * {@link #ACTION_HOLD} ticks so it survives long enough to be seen; ordinary
	 * states cannot overwrite a latched one until it lapses.
	 */
	private void setAction(String action, boolean momentary) {
		if (actionHold > 0 && !momentary) {
			return; // a latched event still has the floor
		}
		ecoAction = action;
		if (momentary) {
			actionHold = ACTION_HOLD;
		}
	}
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

	/** The one place this body remembers, in world tiles; {@code wpLvl < 0} means
	 *  nothing is marked. Spatial memory lives here rather than in the mind's
	 *  registers because a coordinate is only useful if something can do geometry
	 *  with it, and the instruction set has neither divide nor atan2. */
	private double wpX = 0, wpY = 0;
	private int wpLvl = -1;

	/** The forage patch this body is currently steering by, as tile coordinates;
	 *  {@code forageCol < 0} means none was found on the last scan. Cached because
	 *  the scan is O(r^2) tile reads and the answer changes slowly, while the
	 *  <i>bearing</i> to it changes every tick as the body moves — so the sensor
	 *  stays live between scans without rescanning. */
	/** How the standing intent went last tick, as an {@link AgentIO} INTENT_* value.
	 *  Reported back to the mind through S_INTENT, and to the viewer. */
	private double intentStatus = AgentIO.INTENT_IDLE;
	/**
	 * How long a pair must stay together before a child arrives (~3s at 33 t/s).
	 * Mating is an exchange, not an instant: the pair has to hold station, which
	 * costs them both the time and leaves them stationary and conspicuous while it
	 * happens. That is what makes breeding a decision with a price rather than a
	 * collision, and it gives the pending state something real to describe.
	 */
	public static final int MATING_TICKS = 100;
	/** The partner this body is currently exchanging with; null when not mating. */
	private NPC matingWith = null;
	/** Ticks spent in the current exchange. */
	private int matingTicks = 0;
	/** Relative bearing to a partner being approached, NaN when not approaching. */
	private double mateSteer = Double.NaN;
	/** Which tracked channels attention had no room for last tick, by index into
	 *  {@link #TRACKED_CHANNELS}. Kept so the inspector can tell "nothing there"
	 *  apart from "no room for it" -- a bare zero means both, and they are opposite
	 *  facts about the creature. */
	private final java.util.Set<Integer> attentionDropped = new java.util.TreeSet<Integer>();

	/** The tile property this mind is currently looking for (an AgentIO TILE_*),
	 *  and the one the cached result was found under. */
	private int tileWanted = AgentIO.TILE_FOOD;
	private int tileScanned = AgentIO.TILE_FOOD;

	private int forageCol = -1, forageRow = -1;
	private long forageScanAt = Long.MIN_VALUE;

	/** How often a body re-scans for a forage patch. Grass regrows over ~a minute
	 *  (Tile.REGROW_DELAY), so a target a second old is still a good target, and
	 *  rescanning every tick would buy nothing for 33x the cost. */
	private static final int FORAGE_SCAN_PERIOD = 33;

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

	/** Wears the given genome's body, so a staged scene (the sprite catalog's
	 *  reference occupants) shows a real procedural creature rather than the
	 *  genome-less plain dot. */
	public TestNPC withGenome(net.hedinger.prototype.entities.Genome g) {
		applyGenome(g);
		return this;
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
		adoptDiet(t, g);
		t.size = (int) Math.round(g.size);
		t.speed = g.speed;
		t.turn = g.turnRate;
		t.col = g.toColor();
		t.LOS_FOV = Math.PI * 2;
		t.LOS_RANGE = Math.max(g.losRange, 3);
		t.SEARCH_FREQ = 2;
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
	/**
	 * A minded scavenger: the same brain, body and forage intent as any other
	 * minded creature, with a diet that makes carrion its food. Nothing about its
	 * mind is scavenger-specific — it forages, and forage means carcasses. Whether
	 * a lineage actually makes a living that way is left to selection, which is the
	 * same bargain the rest of the minded cohort is on.
	 */
	public static TestNPC mindedScavenger(double x, double y, double z, Genome g) {
		// Own the genome before writing a diet into it. Founder pools are shared
		// arrays and callers hand the same instance to several bodies, so mutating
		// what was passed in would quietly re-flavour creatures nobody was building.
		// Copied BEFORE the mind is bound, so the two never point at different ones.
		TestNPC t = mindedForager(x, y, z, g.copy());
		t.withDiet(Diet.SCAVENGER); // writes through to the genome the body is drawn from
		t.speed *= SCAVENGER_STRIDE;
		return t;
	}

	/**
	 * A minded parasite: the same brains and forage intent as the rest of the
	 * cohort, with a diet that makes a bigger living body its food. Its forage
	 * channel points at the nearest host, the shared attach machinery latches it
	 * on when it arrives (the starter mind holds {@code A_ATTACH} through the
	 * forage walk), and the drain itself is a body reflex — riding while hungry
	 * IS eating. Kept small: a parasite must be smaller than its host to latch
	 * at all, and small is what lets it cling too tight to buck off easily.
	 */
	public static TestNPC mindedParasite(double x, double y, double z, Genome g) {
		Genome own = g.copy();
		own.size = Math.min(own.size, PARASITE_MAX_SIZE_PX); // small by nature
		TestNPC t = mindedForager(x, y, z, own);
		t.withDiet(Diet.PARASITE); // writes through to the genome the body is drawn from
		return t;
	}

	/**
	 * The biggest body a parasite lineage can grow, <em>in pixels</em> — the
	 * unit of the genome's size and of {@link #getPixelSize()}, NOT of
	 * {@link #getSize()}, which is that radius in tiles (a 48th of it). Staying
	 * well under every plausible host is what makes the latch (host must be
	 * bigger) and the tight grip (smaller rider clings harder) reliably true of
	 * the niche.
	 *
	 * <p>The name says PIXELS because the two units are a genuine trap: read as
	 * tiles, this constant is larger than any body in the world, and everything
	 * gated on it silently answers "no" forever. That is exactly how the
	 * steward's reseeding floor died — see
	 * {@code WorldSteward.hostPresent()}.
	 */
	public static final int PARASITE_MAX_SIZE_PX = 5;

	@Override
	protected void run_extended() {
		super.run_extended(); // the four books first (needs, regen, health)
		if (diet == Diet.PARASITE && !isDead()) {
			parasiteFeed();
		}
	}

	/**
	 * The parasite's living, as a body reflex rather than a decision: riding a
	 * host while hungry IS eating. Every {@link #PARA_BITE_PERIOD} ticks it
	 * takes {@link #PARA_BITE} health off the host and digests the share of the
	 * body that health represented — the same meat arithmetic as a hunter's
	 * bite, so a bigger host is a richer ride. The drain stops on its own when
	 * the stomach is full or the body is collapsed, and a host that dies under
	 * it is let go of: a parasite drinks lives, not corpses (the carrion niche
	 * is the scavenger's).
	 */
	private void parasiteFeed() {
		net.hedinger.prototype.engine.Entity mount = getAttachTarget();
		if (!(mount instanceof NPC h) || isGrabbed()) {
			return; // not riding (or held captive, which is not riding)
		}
		if (h.isDead() || h.isRemoved()) {
			detach();
			return;
		}
		if (hunger <= 0 || !canExert() || age % PARA_BITE_PERIOD != 0) {
			return;
		}
		int consumed = Math.max(0, Math.min(PARA_BITE, h.getHealth()));
		h.damage(PARA_BITE, "parasites");
		feed(MEAT_ENERGY * h.bodyMass() * (consumed / (double) FULL_BODY_HEALTH));
		setAction("eating", true);
	}

	public static TestNPC mindedForager(double x, double y, double z, Genome g) {
		TestNPC t = new TestNPC(x, y, z, Behavior.MINDED);
		configureGenomeBody(t, g); // size-scaled reserve, burn and repro thresholds
		t.LOS_FOV = Math.PI * 2; // omnidirectional, like the other genome bodies
		t.LOS_RANGE = Math.max(g.losRange, 3);
		t.SEARCH_FREQ = 2;
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
		adoptDiet(t, g); // heredity carries diet in the genome; the body follows it
		// Born a juvenile and grow into the genome's body (see NPC.beginGrowth).
		t.beginGrowth(g.size);
		// A body takes as long to rot away as it took to build: the corpse span IS
		// the childhood, read off the same two growth constants rather than a
		// separate figure that could drift out of step. Both are linear in adult
		// size and so linear in mass, which is the scaling that matters -- a big
		// animal leaves a big body, lying there for a big scavenger's window.
		// Measured from the body it will grow INTO, so a creature that dies young
		// still leaves the corpse its species leaves.
		t.deathspan = growthTicks(g.size);
		t.speed = g.speed;
		t.turn = g.turnRate;
		t.metabolic = true;
		// Energy scales are all derived from body size (see NPC's size-scaled model):
		// born comfortably fed, and reproduction gated on filling a big fraction of
		// the (size-scaled) tank so a bigger creature must eat more before it breeds.
		// energyCapacity() is anchored on the adult body, so these are unchanged by
		// the creature being born a juvenile — growth is physical, not economic.
		t.energy = BORN_FRACTION * t.energyCapacity();
		t.reproThreshold = REPRO_FRACTION * t.energyCapacity();
		t.reproCost = REPRO_COST_FRACTION * t.energyCapacity();
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
		adoptDiet(t, g);
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

	/** Points the body along a heading in radians (0 = east). The {@code mover}
	 *  fixture takes one up front; this lets the other fixtures — a minded body in
	 *  particular — start out facing a chosen way without a mind having to turn. */
	public TestNPC withHeading(double radians) {
		D = radians;
		return this;
	}

	/** Sets what this body can turn into energy — see {@link Diet}. */
	// --- parasitism ---------------------------------------------------------
	/** Ticks between a riding parasite's bites of its host. */
	public static final int PARA_BITE_PERIOD = 30;
	/** Health a parasite's bite takes off the host — a slow drain, not an
	 *  attack: minutes to matter, so the host has every chance to buck it off
	 *  or simply outlive it. The meal is the same meat arithmetic as a
	 *  hunter's bite, so a bigger host is a richer ride. */
	public static final int PARA_BITE = 1;
	/** How far (tiles) a parasite senses warm bodies to ride — scent-like,
	 *  through cover, the way carrion is smelled rather than seen. */
	public static final double HOST_SENSE_R = 12.0;

	public TestNPC withDiet(Diet d) {
		diet = d;
		if (genome != null) {
			genome.diet = d == Diet.SCAVENGER ? net.hedinger.prototype.entities.Genome.DIET_SCAVENGER
					: d == Diet.CARNIVORE ? net.hedinger.prototype.entities.Genome.DIET_CARNIVORE
							: d == Diet.PARASITE ? net.hedinger.prototype.entities.Genome.DIET_PARASITE
									: net.hedinger.prototype.entities.Genome.DIET_HERBIVORE;
		}
		return this;
	}

	/** Reads the diet the genome carries onto the body. The genome is the source:
	 *  it is what heredity copies and what the body plan is drawn from. */
	private static void adoptDiet(TestNPC t, net.hedinger.prototype.entities.Genome g) {
		t.diet = g.diet == net.hedinger.prototype.entities.Genome.DIET_SCAVENGER ? Diet.SCAVENGER
				: g.diet == net.hedinger.prototype.entities.Genome.DIET_CARNIVORE ? Diet.CARNIVORE
						: g.diet == net.hedinger.prototype.entities.Genome.DIET_PARASITE ? Diet.PARASITE
								: Diet.HERBIVORE;
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

	/** Starts this body at the given hydration — the complement of the thirst
	 *  need (tests: make it thirsty). */
	public TestNPC withHydration(double h) {
		thirst = 1.0 - Math.max(0, Math.min(1, h));
		return this;
	}

	/** Starts this body at the given hunger, 0 sated .. 1 starving (tests). */
	public TestNPC withHunger(double h) {
		hunger = Math.max(0, Math.min(1, h));
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
	 *  hand-driven mind on an energy-bearing body to exercise the hunger/movement
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

	/** Gives this fixture a standing order to press buttons: it carries the
	 *  deliberate interact intent every tick, so an intent-driven switch answers
	 *  it -- the scripted stand-in for a mind writing {@code A_INTERACT}. */
	public TestNPC withInteract() {
		alwaysInteract = true;
		return this;
	}

	/** True once this NPC has heard any Sound. */
	public boolean hasHeard() {
		return heard;
	}

	// ---- behaviour -----------------------------------------------------------

	@Override
	protected void think() {
		if (actionHold > 0) {
			actionHold--; // a latched act lapses; think() runs exactly once per tick
		}
		// Scripted bodies press buttons only under a standing order; a minded
		// body overwrites this from its A_INTERACT actuator in actFrom.
		interactIntent = alwaysInteract;
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
			if (thirstOverride()) {
				return;
			}
			thinkGraze();
			return;
		case BREEDER:
			if (thirstOverride()) {
				return;
			}
			thinkBreeder();
			return;
		case NEST:
			if (thirstOverride()) {
				return;
			}
			thinkNester();
			return;
		case MATER:
			if (thirstOverride()) {
				return;
			}
			thinkMater();
			return;
		case MINDED:
			thinkMinded(); // a minded body drinks by its own choices, not a script
			return;
		case HAUL:
			thinkHaul();
			return;
		case PREDATOR:
			if (thirstOverride()) {
				return;
			}
			thinkPredator();
			return;
		}
	}

	/** Thirst at which finding water outranks everything for an eco creature. */
	private static final double THIRST_DRIVE = 0.65;
	/** How far (tiles) a creature can know about a shore. Deliberately wider
	 *  than sight: animals remember water, they don't have to see it. */
	private static final int WATER_SENSE_R = 20;

	/**
	 * The scripted species' water drive: when thirst runs high, walking to the
	 * nearest shore in sense range outranks grazing, breeding and hunting — a
	 * genuinely parched body has no other business. At the water it simply
	 * stands and sips (drinking is a timed act by adjacency — see NPC's drink
	 * rate); the override releases once thirst falls back under the drive
	 * line. Out of sense range of any water it roams, which doubles as
	 * searching. Returns true when it ran this tick.
	 */
	private boolean thirstOverride() {
		if (!metabolic || thirst < THIRST_DRIVE) {
			return false;
		}
		setAction("thirst", false);
		if (nearWater()) {
			settleOnPatch(); // stay put and drink; drift off tile intersections
			return true;
		}
		double dir = waterDirection(WATER_SENSE_R);
		if (Double.isNaN(dir)) {
			roam(speed, turn); // no shore in known range: search
		} else {
			move(speed, dir);
		}
		return true;
	}

	/**
	 * How far the nearest reachable water is, in tiles WALKED rather than tiles
	 * crossed as the crow flies, or MAX_VALUE when none is within {@code r}.
	 *
	 * <p>Path length is the honest number for a body that has to walk there, and
	 * it is what stops the water sense lying about a lake on the other side of a
	 * ridge: straight-line it was two tiles away and the creature pressed the rock
	 * for as long as it stayed thirsty. O(1) — the world's water field did the
	 * walking once, at build time.
	 */
	private double waterDistance(int r) {
		int steps = getWorld().waterStepDistance(X, Y, Z, isFlying());
		return steps >= 0 && steps <= r ? steps : Double.MAX_VALUE;
	}

	/**
	 * Which way to go for water, or NaN when none is reachable within {@code r}.
	 *
	 * <p>Always one step along the world's flow field, never a bearing aimed at the
	 * water itself. Aiming at the water was tried and is worse on every count: even
	 * with an unobstructed tile route the straight line cuts corners, and clipping
	 * the corner of a rock is exactly the failure this is here to end. Measured on
	 * seed 42, steering at the water left creatures stuck on 8.2% of their thirsty
	 * ticks against 5.9% following the field, and bone dry four times as often.
	 */
	private double waterDirection(int r) {
		int steps = getWorld().waterStepDistance(X, Y, Z, isFlying());
		if (steps < 0 || steps > r) {
			return Double.NaN;
		}
		return getWorld().waterFlowDirection(X, Y, Z, isFlying());
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

		// Appetite dictates the hunt (VITALS.md): hunger is the need for food,
		// distinct from the energy budget, so a hunter chases when its stomach
		// asks — not when its tank has headroom. A sated hunter patrols and
		// takes only prey that blunders into reach; hungry it hunts in earnest;
		// only genuine starvation lifts the taboo on eating its own kind.
		boolean sated = hunger < PRED_HUNT_HUNGER;
		boolean starving = hunger >= STARVE_HUNGER;
		NPC prey = nearestPrey(LOS_RANGE, starving); // hunt as far as it can see
		double reach = prey == null ? 0
				: (getSize() + prey.getSize()) / 2.0 + ATTACK_REACH;
		// So nearly sated a kill would be thrown away: the stomach has no room.
		boolean full = hunger <= PRED_FULL_HUNGER;
		if (prey != null && !full && distance(prey.getX(), prey.getY(), prey.getZ()) <= reach) {
			lockTarget(prey);
			setAction("attacking", true); // in reach: bite at any hunger short of full
			pinCount = 0; // biting in place is not a pin — hold off the give-up
			feed(biteFeeds(prey));
		} else if (prey != null && !sated) {
			lockTarget(prey);
			setAction(starving ? "starving" : "hunting", false);
			chase(speed, turn);
		} else {
			// No prey, or well-fed and nothing in its mouth: patrol calmly. Label
			// it "sated" when it is deliberately letting visible prey be.
			setAction((sated && prey != null) ? "sated" : "prowling", false);
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
	/**
	 * A sound arrives. Keeps where it came from and when, for the two hearing
	 * channels to report until {@link #EARSHOT_MEMORY} runs out.
	 *
	 * <p>Last one wins rather than loudest: the sound has already been filtered by
	 * earshot on the way here — anything that reached this body was loud enough to
	 * matter — and "the most recent thing that happened" is both the more useful
	 * fact and the one a creature could actually have.
	 */
	@Override
	public void hear(net.hedinger.prototype.entities.Sound sound) {
		super.hear(sound);
		if (sound == null) {
			return;
		}
		heardX = sound.getX();
		heardY = sound.getY();
		heardAt = getWorld() == null ? 0 : getWorld().getTick();
	}

	/** Whether a sound is still ringing, for the hearing channels and for tests. */
	public boolean hearsSomething() {
		if (heardAt == Long.MIN_VALUE || getWorld() == null) {
			return false;
		}
		return getWorld().getTick() - heardAt < EARSHOT_MEMORY;
	}

	private int biteDamage(NPC prey) {
		double ratio = getSize() / Math.max(1e-6, prey.getSize());
		double scale = Math.min(1.0, ratio);
		return Math.max(1, (int) Math.round(PRED_DAMAGE * scale));
	}

	/**
	 * Takes one bite out of {@code prey} and returns what it fed the hunter.
	 *
	 * <p>The bite removes health; the meal is the share of the body that health
	 * represented, {@code MEAT_ENERGY * preyMass * (damage / FULL_BODY_HEALTH)}. So
	 * the whole carcass is worth {@code MEAT_ENERGY * preyMass} no matter how many
	 * bites it took, and a hunter that arrives at an animal something else has
	 * already chewed on gets only what is left of it. Damage past death feeds
	 * nobody — you cannot eat more of an animal than there was.
	 */
	private double biteFeeds(NPC prey) {
		int bite = biteDamage(prey);
		int consumed = Math.max(0, Math.min(bite, prey.getHealth()));
		prey.damage(bite, "predation");
		// Violence is audible. The scream comes from the quarry, not the hunter,
		// and carries in proportion to how big the quarry is -- so a hunt tells the
		// neighbourhood something is happening here, and roughly how big a
		// something. This is the only event in the eco simulation that makes a
		// noise, which is what gives the hearing channels anything to hear.
		if (getWorld() != null) {
			getWorld().spawnEntity(new net.hedinger.prototype.entities.Sound(
					prey.getX(), prey.getY(), prey.getLvl(),
					KILL_LOUDNESS * prey.bodyMass()));
		}
		return MEAT_ENERGY * prey.bodyMass() * (consumed / (double) FULL_BODY_HEALTH);
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
			setAction("prowling", false);
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
	/** The nearest body a parasite could ride: bigger than itself, alive, and
	 *  not itself a parasite (they do not stack), within {@link #HOST_SENSE_R}.
	 *  The host it is already riding counts — and is trivially nearest — so the
	 *  forage channel keeps reading "here" for as long as the meal lasts. */
	private NPC nearestHost() {
		net.hedinger.prototype.engine.Entity mount = getAttachTarget();
		if (mount instanceof NPC h && !h.isDead() && !h.isRemoved()) {
			return h;
		}
		NPC best = null;
		double bestD = HOST_SENSE_R;
		for (NPC n : getWorld().census().creatures(getLvl())) {
			if (n == this || n.isDead() || n.isRemoved() || n.getSize() <= getSize()
					|| !n.isOrganic() // no blood in a machine: nothing to ride and nothing to drink
					|| (n instanceof TestNPC tn && tn.diet == Diet.PARASITE)) {
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

	private NPC nearestPrey(double radius, boolean cannibal) {
		NPC best = null;
		double bestD = radius;
		for (NPC n : getWorld().census().creatures(getLvl())) {
			// A hunter takes anything up to PRED_MAX_PREY_RATIO times its own size —
			// its own weight class, plus quarry somewhat above it. Body size is
			// clamped to Genome.SIZE_MAX for every creature alike, so a strictly-
			// smaller rule left the largest creatures permanently un-huntable with no
			// predator able to exist above them; reaching past its own size closes
			// that hole. Punching up is not free: the bite lands weaker the bigger the
			// quarry (see biteDamage), so the kill takes proportionally longer.
			// (Census walk: live same-level non-item bodies only.)
			if (n == this || n.isDead() || n.isRemoved()
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
			// Parasites are ignored outright, at any hunger: too small and too
			// foul to be worth a bite. Nothing preys on them — their checks are
			// the host's bucking and their own four books.
			if (n instanceof TestNPC tp && tp.diet == Diet.PARASITE) {
				continue;
			}
			// Nor is a machine ever quarry. The steward's drone is the size of a
			// grown animal and moves like one, so without this a hungry hunter
			// would spend its life closing on a body it cannot bite and cannot
			// digest. Inedibility is a property of the drone (isOrganic), not a
			// rule about drones, so anything mechanical added later is covered.
			if (!n.isOrganic()) {
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
	 * <p>Survival reflex first: if the body has been trying to move (any throttle
	 * that engages movement at all — the same 0.02 threshold {@code actFrom}
	 * moves by) but has made no headway, the body takes the wheel and drives to
	 * open ground for a spell, skipping the mind -- so a degenerate policy that
	 * steers flat into a wall can't pin the creature forever. Only a mind that is
	 * deliberately still (throttle at zero) is left alone, never force-marched.
	 * This gate once sat at 0.3, which quietly disarmed the reflex for the whole
	 * starter lineage: its amble throttle is 0.25, so every wanderer that aimed
	 * at a wall shoved it for good — measured on seed 42, 4.2% of all thirsty
	 * minded ticks were spent pinned like that, walls with water just beyond. */
	private void thinkMinded() {
		if (mind == null) {
			return;
		}
		if (unstickIfPinned(speed * PRED_CRUISE, lastThrottle > 0.02, true)) {
			return; // body override: shaking loose, the mind sits this tick out
		}
		senseInto(sensors);
		mind.think(sensors, actuators);
		lastThrottle = clampUnit(actuators[AgentIO.A_THROTTLE]);
		actFrom(actuators);
	}

	/** Fills the egocentric, normalized {@link AgentIO} sensor vector. */
	/** Package-visible so the suite can read the bank a mind actually sees,
	 *  rather than testing the bookkeeping behind it. */
	void senseInto(double[] s) {
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
		// Hearing: neither facing-gated nor stopped by terrain, and it reports an
		// event rather than a thing -- so unlike every other distance channel it
		// falls silent on its own once the sound stops ringing.
		if (hearsSomething()) {
			double hdx = heardX - X, hdy = heardY - Y;
			s[AgentIO.S_SOUND_PROX] = 1.0 / (1.0 + Math.hypot(hdx, hdy));
			s[AgentIO.S_SOUND_BEARING] = wrap(Math.atan2(hdy, hdx) - D) / Math.PI;
		} else {
			s[AgentIO.S_SOUND_PROX] = 0;
			s[AgentIO.S_SOUND_BEARING] = 0;
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
		// Dedicated fixture sense: the nearest interactable fixture (a switch's
		// pedestal). Fixtures are furniture, not fauna -- they never enter the
		// perceived-creature set -- so this scans the world directly; a
		// fixture's indicator lamps make it readable at sight range from any
		// facing, which is exactly what a control panel is for.
		net.hedinger.prototype.entities.Switch fx = nearestFixture();
		if (fx != null) {
			double dx = fx.getX() + 0.5 - X, dy = fx.getY() + 0.5 - Y;
			double dist = Math.hypot(dx, dy);
			s[AgentIO.S_FIXTURE_PROX] = 1.0 / (1.0 + dist);
			s[AgentIO.S_FIXTURE_BEARING] = wrap(Math.atan2(dy, dx) - D) / Math.PI;
		} else {
			s[AgentIO.S_FIXTURE_PROX] = 0;
			s[AgentIO.S_FIXTURE_BEARING] = 0;
		}

		// The needs (VITALS.md): hunger and thirst as the mind's own hollow
		// feelings, distinct from S_ENERGY (what the body can DO). With the
		// water channel below, "when dry, steer to water" stays a
		// two-instruction reflex away.
		s[AgentIO.S_HUNGER] = hunger;
		s[AgentIO.S_THIRST] = thirst;
		double wDir = waterDirection(WATER_SENSE_R);
		if (!Double.isNaN(wDir)) {
			s[AgentIO.S_WATER_PROX] = 1.0 / (1.0 + waterDistance(WATER_SENSE_R));
			s[AgentIO.S_WATER_BEARING] = wrap(wDir - D) / Math.PI;
		} else {
			s[AgentIO.S_WATER_PROX] = 0;
			s[AgentIO.S_WATER_BEARING] = 0;
		}
		s[AgentIO.S_INTENT] = intentStatus; // how last tick's intent went
		// What kind of ground to look for is the mind's standing choice, read before
		// the scan so the channel answers the question actually being asked.
		tileWanted = AgentIO.tileWanted(actuators[AgentIO.A_TILE]);
		senseFieldAndBody(s); // wider hunt/flee/kin channels, body state, obstacle whiskers
		attentionDropped.clear();
		limitAttention(s); // ...of which only as many as this mind can hold survive
	}

	/** Fills the wider-range hunt/flee/kin channels plus body-state and obstacle
	 *  whiskers -- the senses a hybrid mind needs that the short, facing-gated
	 *  nearest-neighbour channel cannot give. One pass over perceivable neighbours
	 *  feeds the prey, threat and kin gradients at once, at full sight range. */
	private void senseFieldAndBody(double[] s) {
		double preyD = Double.MAX_VALUE, threatD = Double.MAX_VALUE;
		double preyDx = 0, preyDy = 0, threatDx = 0, threatDy = 0;
		double kinX = 0, kinY = 0, kinWeight = 0;
		// Census walk: live same-level bodies, then line of sight (cover and
		// walls hide neighbours; hasLOS range-gates before it raycasts).
		for (NPC n : getWorld().census().creatures(getLvl())) {
			if (n == this || n.isDead() || n.isRemoved() || !isInLOS(n)) {
				continue;
			}
			double dx = n.getX() - X, dy = n.getY() - Y;
			double dist = Math.hypot(dx, dy);
			if (dist > LOS_RANGE) {
				continue;
			}
			// Parasites never enter the prey channel: predators ignore them (see
			// nearestPrey), and the minded hunt sense agrees so evolution cannot
			// quietly relearn a taste the scripted hunters are denied.
			if (n.getSize() < getSize() && dist < preyD && n.isOrganic()
					&& !(n instanceof TestNPC tp && tp.diet == Diet.PARASITE)) {
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
				kinWeight += sim;
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
		// The kin centroid's distance, which the bearing alone never carried. The
		// weights are a positive scalar, so dividing them out moves the point but not
		// the direction -- S_KIN_BEARING is unchanged by this.
		if (kinWeight > 0 && (kinX != 0 || kinY != 0)) {
			s[AgentIO.S_KIN_PROX] = 1.0 / (1.0 + Math.hypot(kinX / kinWeight, kinY / kinWeight));
		} else {
			s[AgentIO.S_KIN_PROX] = 0;
		}

		senseForage(s);
		senseWaypoint(s);

		s[AgentIO.S_HEALTH] = clampUnit(getHealth() / 100.0);
		s[AgentIO.S_CARRIED] = isGrabbed() ? 1.0 : (getAttachTarget() != null ? -1.0 : 0.0);

		// Obstacle whiskers 45 deg off each shoulder, and a drowning/falling hazard
		// dead ahead (non-flyer only): the sensed half of the survival reflex.
		s[AgentIO.S_WHISKER_L] = blockedAt(D - Math.PI / 4);
		s[AgentIO.S_WHISKER_R] = blockedAt(D + Math.PI / 4);
		double hx = X + Math.cos(D), hy = Y + Math.sin(D);
		net.hedinger.prototype.engine.Tile ahead = getWorld().getTile(hx, hy, Z);
		// Water and pits are hazards a body cannot survive entering; corrosive
		// ground is the third and the interesting one, because a body CAN enter
		// it and merely pays for the crossing. The channel says "ahead of you
		// costs something", and what that something is is the terrain's business.
		boolean hazard = !isFlying() && ahead != null
				&& (ahead.isWater() || ahead.isCorrosive()
						|| ahead.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
		s[AgentIO.S_HAZARD_AHEAD] = hazard ? 1.0 : 0.0;
	}

	/**
	 * Fills the forage channel: where the best patch of ground in sight is, as a
	 * bearing and a proximity.
	 *
	 * <p>The patch is re-chosen only every {@link #FORAGE_SCAN_PERIOD} ticks, but the
	 * bearing to it is recomputed every tick from the remembered tile — so the body
	 * keeps a steady target it can actually walk to, instead of a direction that
	 * jitters as ties change hands. That distinction is the whole point of naming a
	 * <i>place</i> rather than a gradient: a creature can commit to it.
	 */
	private void senseForage(double[] s) {
		long now = getWorld().getTick();
		// A scavenger's food is a body, not a patch of ground, so its forage channel
		// points at the best carcass in sight instead of the best vegetation. Same
		// channel, same units, same intent: what changes is only what counts as food,
		// which is exactly what a diet is. Rescanned every tick because a carcass can
		// be eaten out from under it by another scavenger, unlike a tile of grass.
		if (diet == Diet.SCAVENGER) {
			scanCarrion();
			if (forageCol < 0) {
				s[AgentIO.S_FORAGE_PROX] = 0;
				s[AgentIO.S_FORAGE_BEARING] = 0;
				return;
			}
			double cdx = forageCol + 0.5 - X, cdy = forageRow + 0.5 - Y;
			s[AgentIO.S_FORAGE_PROX] = 1.0 / (1.0 + Math.hypot(cdx, cdy));
			s[AgentIO.S_FORAGE_BEARING] = wrap(Math.atan2(cdy, cdx) - D) / Math.PI;
			return;
		}
		// A parasite's food is a bigger living body, so its forage channel points
		// at the nearest host — sensed like scent (through cover, no facing),
		// the way a scavenger smells carrion. Riding one, the channel reads "you
		// are on it", so the forage intent holds it in place instead of marching
		// it off its own meal.
		if (diet == Diet.PARASITE) {
			NPC host = nearestHost();
			if (host == null) {
				s[AgentIO.S_FORAGE_PROX] = 0;
				s[AgentIO.S_FORAGE_BEARING] = 0;
				return;
			}
			double hdx = host.getX() - X, hdy = host.getY() - Y;
			s[AgentIO.S_FORAGE_PROX] = 1.0 / (1.0 + Math.hypot(hdx, hdy));
			s[AgentIO.S_FORAGE_BEARING] = wrap(Math.atan2(hdy, hdx) - D) / Math.PI;
			return;
		}
		boolean due = forageScanAt == Long.MIN_VALUE // never scanned: answer this tick
				|| tileWanted != tileScanned // the mind asked for different ground
				|| (now + getID()) % FORAGE_SCAN_PERIOD == 0 // staggered by id across the cohort
				|| (forageCol >= 0 && tileScoreAt(forageCol, forageRow, now) <= 0); // no longer matches
		tileScanned = tileWanted;
		if (due) {
			scanForage(now);
		}
		if (forageCol < 0) {
			s[AgentIO.S_FORAGE_PROX] = 0;
			s[AgentIO.S_FORAGE_BEARING] = 0;
			return;
		}
		double dx = forageCol + 0.5 - X, dy = forageRow + 0.5 - Y;
		s[AgentIO.S_FORAGE_PROX] = 1.0 / (1.0 + Math.hypot(dx, dy));
		s[AgentIO.S_FORAGE_BEARING] = wrap(Math.atan2(dy, dx) - D) / Math.PI;
	}

	/**
	 * Picks the best forage tile within sight and remembers it. Scored as
	 * {@code vegetation / (1 + distance)}, so a rich patch across the map loses to a
	 * fair one underfoot — the creature is choosing where to walk, and walking costs
	 * energy. The sweep runs in a fixed row-major order and keeps the first strict
	 * maximum, so ties break identically on every replay.
	 */
	/** How well a tile answers the property this body is currently looking for,
	 *  0 when it does not. Vegetation is graded (a richer patch scores higher); the
	 *  rest are yes-or-no, so any matching tile is judged purely on being close. */
	private double tileScore(net.hedinger.prototype.engine.Tile t, long now) {
		switch (tileWanted) {
		case AgentIO.TILE_COVER:
			return t.blocksSight() && t.isWalkable() ? 1 : 0;
		case AgentIO.TILE_SLOW:
			return t.isWalkable() && t.speedFactor() < 1.0 ? 1 : 0;
		case AgentIO.TILE_WATER:
			return t.isWater() ? 1 : 0;
		case AgentIO.TILE_SOLID:
			return t.isSolid() ? 1 : 0;
		case AgentIO.TILE_HAZARD:
			return t.isWater()
					|| t.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE ? 1 : 0;
		default:
			return t.isWalkable() ? t.getVegetation(now) : 0;
		}
	}

	/**
	 * Points the forage channel at the best carcass in sight, scored the same way
	 * vegetation is: value over distance, so a fat body across the map loses to a
	 * fair one underfoot. Value is mass times freshness — a big fresh kill is the
	 * prize, a nearly-rotted mouse barely worth the walk.
	 *
	 * <p>Sweeps in id order and keeps the first strict maximum, so ties break
	 * identically on every replay.
	 */
	private void scanCarrion() {
		forageCol = -1;
		forageRow = -1;
		if (getWorld() == null) {
			return;
		}
		// A body already chosen keeps the channel unless something is a great deal
		// better -- the bar it must clear is the held body's own score times
		// CARRION_SWITCH_GAIN. With nothing held the bar is zero and the best
		// carcass in range simply wins, which is the same choice as before.
		NPC held = heldCarrion();
		NPC best = null;
		double bestScore = held == null ? 0 : carrionScore(held) * CARRION_SWITCH_GAIN;
		// Census walk: this level's corpses only.
		for (NPC n : getWorld().census().corpses(getLvl())) {
			if (n == this || !n.isDead() || n.isRemoved()) {
				continue;
			}
			if (distance(n.getX(), n.getY(), n.getZ()) > CARRION_SCENT_R) {
				continue;
			}
			double score = carrionScore(n);
			// Same reachability rule as the grazer's forage: a carcass on the far
			// side of rock is not a meal, it is a wall to press against.
			if (score > bestScore && walkableLineTo((int) n.getX(), (int) n.getY())) {
				bestScore = score;
				best = n;
			}
		}
		if (best != null) {
			carrionTarget = best; // nothing held, or something worth crossing to
		}
		NPC target = carrionTarget;
		if (target != null) {
			forageCol = (int) target.getX();
			forageRow = (int) target.getY();
		}
	}

	/**
	 * What a carcass is worth from here: the energy still in it over the walk to
	 * reach it. Since a bite is paid at full rate and rot is charged once, in how
	 * much carcass is LEFT, {@code mass * freshness} is exactly the energy
	 * remaining — so this really is expected payoff per unit of travel rather than
	 * a stand-in for it.
	 */
	private double carrionScore(NPC n) {
		return n.bodyMass() * (1.0 - n.decayProgress())
				/ (1.0 + distance(n.getX(), n.getY(), n.getZ()));
	}

	/**
	 * How much better a rival carcass must be before a committed scavenger will
	 * cross to it.
	 *
	 * <p>Committing at all was the fix that made the niche viable: re-running the
	 * argmax every tick handed the lead back and forth between bodies whose scores
	 * sat within noise of each other, so the creature turned toward a new one every
	 * few steps and reached none — a carcass within biting distance on 0.87 per cent
	 * of its ticks. But committing outright is not free either: measured, a quarter
	 * of held ticks had a body in range worth more than half again as much, and only
	 * a third of commitments ended in a meal at all.
	 *
	 * <p>A wide band buys both. Near-ties cannot steal the target, so the thrashing
	 * stays fixed; a body worth twice the walk can. It is also self-damping — the
	 * score rises as the walk shortens, so whatever is being approached gets harder
	 * to displace the closer it gets, and a switch cannot immediately switch back.
	 */
	public static final double CARRION_SWITCH_GAIN = 2.0;

	/**
	 * The carcass this body is already walking to, if that choice is still worth
	 * keeping; null when it should look again.
	 *
	 * <p>Commitment is the whole of it. Scoring every carcass in scent range afresh
	 * each tick sounds like keeping up to date, but with dozens of bodies in range
	 * and scores as close as {@code mass * freshness / (1 + dist)} makes them, the
	 * winner changes hands constantly and the creature turns toward a new one every
	 * few steps. Measured, that left a scavenger with a target in view on 37% of
	 * its ticks and actually within biting distance on 0.87% — it spent its life
	 * walking between carcasses without reaching any. The grazer's forage has always
	 * committed to a chosen patch for exactly this reason; the carrion path opted out
	 * on the grounds that a body can be eaten out from under it, which is true and is
	 * handled here by dropping a target that has gone rather than by re-deciding
	 * every tick.
	 */
	private NPC heldCarrion() {
		NPC t = carrionTarget;
		if (t == null) {
			return null;
		}
		if (t.isRemoved() || !t.isDead() || t.getLvl() != getLvl()) {
			carrionTarget = null; // eaten, rotted away, or on the other level now
			return null;
		}
		return t;
	}

	/** The carcass this body has committed to walking to; see {@link #heldCarrion}. */
	private NPC carrionTarget = null;

	/**
	 * Whether this body could actually walk to the centre of tile
	 * {@code (tx, ty)} in a straight line from where it stands.
	 *
	 * <p>A straight line is exactly the right question, because a straight line is
	 * exactly what the body will do: {@code A_SEEK} steers at a bearing and walks
	 * it, with no pathfinding anywhere in the loop. So a target chosen without this
	 * check is a target the creature will walk INTO A WALL trying to reach --
	 * measured underground, a minded forager picking a patch six tiles away through
	 * rock, then pressing against the stone at quarter throttle until it starved.
	 * The engine has a real {@code findPath}, but asking it here would answer a
	 * question the body cannot act on: it has no way to follow a path around a
	 * corner, so "reachable by some route" would still strand it.
	 *
	 * <p>Walks the segment in half-tile steps -- short enough that no step skips
	 * over a wall, since {@link net.hedinger.prototype.engine.Entity#MAX_STEP} is
	 * itself half a tile -- and requires every one of them to be a legal move from
	 * the last. Pure tile reads, no allocation.
	 */
	private boolean walkableLineTo(int tx, int ty) {
		double gx = tx + 0.5, gy = ty + 0.5;
		double dx = gx - X, dy = gy - Y;
		double dist = Math.hypot(dx, dy);
		if (dist <= 0.5) {
			return true; // already there, or as good as
		}
		int steps = (int) Math.ceil(dist / 0.5);
		double px = X, py = Y;
		for (int i = 1; i <= steps; i++) {
			double f = i / (double) steps;
			double nx = X + dx * f, ny = Y + dy * f;
			if (!getWorld().isConnectedSpace(px, py, Z, nx, ny, Z)) {
				return false;
			}
			px = nx;
			py = ny;
		}
		return true;
	}

	private void scanForage(long now) {
		forageScanAt = now;
		forageCol = -1;
		forageRow = -1;
		int r = (int) Math.ceil(LOS_RANGE);
		int cx = (int) X, cy = (int) Y, lvl = getLvl();
		double best = 0;
		for (int ty = cy - r; ty <= cy + r; ty++) {
			for (int tx = cx - r; tx <= cx + r; tx++) {
				double dist = Math.hypot(tx + 0.5 - X, ty + 0.5 - Y);
				if (dist > LOS_RANGE) {
					continue; // the square's corners fall outside the sight circle
				}
				// Prune before touching the tile: vegetation tops out at VEG_MAX, so
				// nothing this far away can beat what we already hold no matter what
				// grows on it. Since the winner is decided by a strict >, skipping a
				// tile that can only tie or lose leaves the choice bit-for-bit
				// identical -- this is a speed-up, not an approximation.
				if (net.hedinger.prototype.engine.Tile.VEG_MAX / (1.0 + dist) <= best) {
					continue;
				}
				net.hedinger.prototype.engine.Tile t = getWorld().isValid(tx, ty, getLvl())
						? getWorld().getTile(tx, ty, getLvl()) : null;
				double q = t == null ? 0 : tileScore(t, now);
				if (q <= 0) {
					continue;
				}
				double score = q / (1.0 + dist);
				// Reachability is checked ONLY for a tile that would take the lead,
				// not for every candidate: the ray is the expensive part and the
				// running maximum improves a handful of times per scan.
				if (score > best && walkableLineTo(tx, ty)) {
					best = score;
					forageCol = tx;
					forageRow = ty;
				}
			}
		}
		if (forageCol >= 0 && !getWorld().isValid(forageCol, forageRow, lvl)) {
			forageCol = -1; // paranoia: never hand the mind a tile off the map
		}
	}

	/** True when this body is standing on ground of the kind it asked for -- which is
	 *  what arrival means for a tile that is not food: there is nothing to do on it
	 *  beyond being there. */
	private boolean onWantedTile() {
		return tileScoreAt((int) X, (int) Y, getWorld().getTick()) > 0;
	}

	/** {@link #tileScore} at a coordinate, or 0 off the map. */
	private double tileScoreAt(int col, int row, long now) {
		if (!getWorld().isValid(col, row, getLvl())) {
			return 0;
		}
		net.hedinger.prototype.engine.Tile t = getWorld().getTile(col, row, getLvl());
		return t == null ? 0 : tileScore(t, now);
	}

	/** Vegetation on a tile, or 0 if it is off the map or not walkable ground. */
	private double vegetationAt(int col, int row, long now) {
		if (!getWorld().isValid(col, row, getLvl())) {
			return 0;
		}
		net.hedinger.prototype.engine.Tile t = getWorld().getTile(col, row, getLvl());
		return (t == null || !t.isWalkable()) ? 0 : t.getVegetation(now);
	}

	/** The channels a body can hold at once, as {prox, bearing} sensor pairs. Each
	 *  one is a thing being kept track of; the waypoint is deliberately absent —
	 *  see {@link #limitAttention}. */
	private static final int[][] TRACKED_CHANNELS = {
			{ AgentIO.S_FORAGE_PROX, AgentIO.S_FORAGE_BEARING },
			{ AgentIO.S_PREY_PROX, AgentIO.S_PREY_BEARING },
			{ AgentIO.S_THREAT_PROX, AgentIO.S_THREAT_BEARING },
			{ AgentIO.S_ITEM_PROX, AgentIO.S_ITEM_BEARING },
			{ AgentIO.S_FIXTURE_PROX, AgentIO.S_FIXTURE_BEARING },
	};

	/** Smallest and largest number of things any mind can keep track of. */
	private static final int TRACK_MIN = 1, TRACK_MAX = 5;
	/** Instructions of brain per extra thing tracked, past the first. */
	private static final int TRACK_PER_INSTR = 12;

	/**
	 * How many targets this mind can hold at once, from the size of the brain
	 * driving it. A body with no mind tracks everything, since the limit is a fact
	 * about minds rather than about eyes.
	 *
	 * <p>Deriving it from program length rather than a gene of its own is the point:
	 * length already sets reaction time under one-instruction-per-tick, so a longer
	 * brain now buys a wider attention span and pays for it in reflexes. Selection
	 * prices both ends of the same trade instead of being handed a free parameter.
	 */
	private int trackingSlots() {
		if (genome == null || genome.brain == null) {
			return TRACKED_CHANNELS.length;
		}
		int n = 1 + genome.brain.length() / TRACK_PER_INSTR;
		return Math.max(TRACK_MIN, Math.min(TRACK_MAX, n));
	}

	/**
	 * Blanks every tracked channel past what this mind can hold, nearest kept first.
	 *
	 * <p>Perception is not the scarce thing here — attention is. Everything in range
	 * was already computed; what a small brain lacks is somewhere to put it, so it
	 * ends up single-minded: a creature watching the grass under its nose may simply
	 * not have the room to also be watching the predator behind it. That is the cost
	 * of a short program, and the reason a long one is worth its slower reflexes.
	 *
	 * <p>Nearest-first is what a crowded mind keeps: deterministic, and it needs no
	 * policy a mind must evolve before it can see at all. Note what is NOT in the
	 * tracked set — kin. Including it collapsed the cohort outright (measured: 80
	 * down to the steward's floor by 45k ticks and never back), because in a herd
	 * the nearest thing is almost always a harmless neighbour, so kin crowded out
	 * both the grass and the predator and left creatures unable to feed or flee. The
	 * kin channel is a flocking gradient rather than a thing being tracked, and it
	 * costs nothing to carry, so it is exempt.
	 *
	 * <p>The waypoint is exempt. A mark is a <i>place</i>, not a target being kept
	 * track of — it does not move, so remembering it is not the same claim as
	 * watching something — and homing would be impossible if it expired the moment
	 * a creature walked out of range of it.
	 */
	private void limitAttention(double[] s) {
		int slots = trackingSlots();
		if (slots >= TRACKED_CHANNELS.length) {
			return;
		}
		// Rank by proximity: prox is 1/(1+dist), so larger is nearer, and 0 is
		// "nothing there" and never worth a slot. Ties break on channel order, which
		// is fixed, so the whole thing replays identically.
		int kept = 0;
		boolean[] keep = new boolean[TRACKED_CHANNELS.length];
		for (int pass = 0; pass < slots; pass++) {
			int best = -1;
			double bestProx = 0;
			for (int c = 0; c < TRACKED_CHANNELS.length; c++) {
				if (!keep[c] && s[TRACKED_CHANNELS[c][0]] > bestProx) {
					bestProx = s[TRACKED_CHANNELS[c][0]];
					best = c;
				}
			}
			if (best < 0) {
				break; // fewer things in range than slots to hold them
			}
			keep[best] = true;
			kept++;
		}
		if (kept == 0) {
			return;
		}
		for (int c = 0; c < TRACKED_CHANNELS.length; c++) {
			if (!keep[c]) {
				// Only a channel that HAD something counts as dropped: a blank one was
				// empty anyway, and reporting it as crowded out would be a lie.
				if (s[TRACKED_CHANNELS[c][0]] > 0) {
					attentionDropped.add(c);
				}
				s[TRACKED_CHANNELS[c][0]] = 0;
				s[TRACKED_CHANNELS[c][1]] = 0;
			}
		}
	}

	/** Names of the tracked channels attention had no room for last tick. */
	public java.util.List<String> attentionDropped() {
		java.util.List<String> out = new java.util.ArrayList<String>();
		for (int c : attentionDropped) {
			out.add(AgentIO.SENSOR_NAMES[TRACKED_CHANNELS[c][0]]);
		}
		return out;
	}

	/** How many targets this mind can hold at once (see {@link #trackingSlots}). */
	public int trackingCapacity() {
		return trackingSlots();
	}

	/** The standing intent's status, as an {@link AgentIO} INTENT_* value. */
	public double intentStatus() {
		return intentStatus;
	}

	/** True while this body is in the middle of an exchange with a partner. */
	public boolean isMating() {
		return matingWith != null;
	}

	/** Abandons any exchange under way. */
	private void breakOffMating() {
		matingWith = null;
		matingTicks = 0;
	}

	/**
	 * The nearest compatible partner in sight.
	 *
	 * <p>Scans the world directly rather than reading {@code targets}, which is the
	 * short, tile-local perception set and is not filled for a minded body at all --
	 * that is precisely why the old A_MATE could only ever fire at contact range,
	 * and why breeding was something creatures blundered into. Same guards as the
	 * prey and threat channels: this level, in line of sight, inside LOS_RANGE.
	 */
	private NPC nearestMate() {
		NPC best = null;
		double bestD = LOS_RANGE;
		// Census walk: live same-level bodies (see World.Census).
		for (NPC n : getWorld().census().creatures(getLvl())) {
			if (n == this || n.isDead() || n.isRemoved() || !canMateWith(n)
					|| !isInLOS(n)) {
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

	/**
	 * The mate intent: find a partner, walk to it, hold station together, and a
	 * child follows. {@code A_MATE} is the whole behaviour rather than a gate that
	 * only fires if a partner happens to already be in reach -- which is what made
	 * breeding accidental, since nothing ever went looking.
	 *
	 * <p>Flocking is deliberately NOT this. Seeking kin gathers a creature into the
	 * herd and does nothing else; a mind that wants to breed says so.
	 *
	 * <p>Returns the {@link AgentIO} INTENT_* status. Note that this is the one
	 * intent whose guards include the creature's OWN state -- too hungry or too
	 * recently bred both read as invalid, which tells a mind to go and eat instead.
	 * And its {@code done} is a true completion rather than the per-tick pulse
	 * grazing gives: a birth is discrete, and the cooldown it starts means the very
	 * next tick reads invalid, so a mind sees a done->invalid edge.
	 */
	private double mateIntent() {
		mateSteer = Double.NaN;
		if (genome != null && !genome.isSexual()) {
			// An asexual body never courts. Reproduction is still what A_MATE asks
			// for; the body just answers it alone. Reported invalid so a mind reads
			// "this intent is not going to close" and can go and do something else --
			// the budding itself is handled below, outside the courtship path.
			breakOffMating();
			return AgentIO.INTENT_INVALID;
		}
		if (!fertile()) {
			breakOffMating(); // too hungry, too soon, or not built to breed
			return AgentIO.INTENT_INVALID;
		}
		if (matingWith != null) {
			double reach = (getSize() + matingWith.getSize()) / 2.0 + MATE_REACH;
			boolean together = !matingWith.isDead() && !matingWith.isRemoved()
					&& distance(matingWith.getX(), matingWith.getY(), matingWith.getZ()) <= reach
					&& canMateWith(matingWith);
			if (together) {
				matingTicks++;
				if (matingTicks >= MATING_TICKS) {
					NPC partner = matingWith;
					breakOffMating();
					return reproduceWith(partner) ? AgentIO.INTENT_DONE : AgentIO.INTENT_INVALID;
				}
				return AgentIO.INTENT_PENDING; // the exchange, in progress
			}
			breakOffMating(); // partner left, died, or stopped being willing
		}
		NPC p = nearestMate();
		if (p == null) {
			return AgentIO.INTENT_INVALID; // nobody here to breed with
		}
		double reach = (getSize() + p.getSize()) / 2.0 + MATE_REACH;
		if (distance(p.getX(), p.getY(), p.getZ()) <= reach) {
			matingWith = p; // in reach: the exchange starts next tick
			matingTicks = 0;
		} else {
			mateSteer = wrap(Math.atan2(p.getY() - Y, p.getX() - X) - D);
		}
		return AgentIO.INTENT_PENDING;
	}

	/** Fills the waypoint channel; zeros when nothing is marked or the mark is on
	 *  another level, since a bearing across levels would point at nothing walkable. */
	private void senseWaypoint(double[] s) {
		if (wpLvl != getLvl()) {
			s[AgentIO.S_WAYPOINT_PROX] = 0;
			s[AgentIO.S_WAYPOINT_BEARING] = 0;
			return;
		}
		double dx = wpX - X, dy = wpY - Y;
		s[AgentIO.S_WAYPOINT_PROX] = 1.0 / (1.0 + Math.hypot(dx, dy));
		s[AgentIO.S_WAYPOINT_BEARING] = wrap(Math.atan2(dy, dx) - D) / Math.PI;
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

	/**
	 * The relative bearing (radians) to a named seek target, or {@code NaN} when the
	 * body cannot see one. Read straight off the sensor vector the mind was just
	 * shown, so the body steers by exactly what it told the mind — there is no
	 * second, privileged view of the world hiding behind the intent commands.
	 */
	private double seekBearing(int seekClass) {
		int prox;
		int bearing;
		switch (seekClass) {
		case AgentIO.SEEK_FORAGE:
			prox = AgentIO.S_FORAGE_PROX;
			bearing = AgentIO.S_FORAGE_BEARING;
			break;
		case AgentIO.SEEK_KIN:
			prox = AgentIO.S_KIN_PROX;
			bearing = AgentIO.S_KIN_BEARING;
			break;
		case AgentIO.SEEK_PREY:
			prox = AgentIO.S_PREY_PROX;
			bearing = AgentIO.S_PREY_BEARING;
			break;
		case AgentIO.SEEK_THREAT:
			prox = AgentIO.S_THREAT_PROX;
			bearing = AgentIO.S_THREAT_BEARING;
			break;
		case AgentIO.SEEK_ITEM:
			prox = AgentIO.S_ITEM_PROX;
			bearing = AgentIO.S_ITEM_BEARING;
			break;
		case AgentIO.SEEK_WAYPOINT:
			prox = AgentIO.S_WAYPOINT_PROX;
			bearing = AgentIO.S_WAYPOINT_BEARING;
			break;
		case AgentIO.SEEK_FIXTURE:
			prox = AgentIO.S_FIXTURE_PROX;
			bearing = AgentIO.S_FIXTURE_BEARING;
			break;
		case AgentIO.SEEK_WATER:
			prox = AgentIO.S_WATER_PROX;
			bearing = AgentIO.S_WATER_BEARING;
			break;
		default:
			return Double.NaN;
		}
		return sensors[prox] > 0 ? sensors[bearing] * Math.PI : Double.NaN;
	}

	/** Applies the actuator vector as engine intent (movement + gated actions). */
	private void actFrom(double[] a) {
		double t = clamp(a[AgentIO.A_TURN], -1, 1);
		double throttle = clampUnit(a[AgentIO.A_THROTTLE]);
		// Intent steering. A_SEEK names a kind of thing rather than a turn rate; when
		// the body can see one, it works out the turn and A_TURN is ignored. A
		// negative value means the same target repels instead of attracting, so
		// running from a threat and running at prey are the same instruction with a
		// different sign. Naming something absent changes nothing, and A_TURN stands.
		double seekWish = a[AgentIO.A_SEEK];
		int seekClass = AgentIO.seekTarget(seekWish);
		boolean away = seekWish < 0;
		boolean pursuing = false; // the goal is named AND the body can see one
		boolean searching = false; // the goal is named and the body cannot see one yet
		if (seekClass != AgentIO.SEEK_NONE) {
			double bearing = seekBearing(seekClass);
			if (!Double.isNaN(bearing)) {
				pursuing = true;
				if (away) {
					bearing = wrap(bearing + Math.PI); // the far side of the same target
				}
				t = clamp(bearing / MAX_TURN, -1, 1); // as far around as one tick allows
			} else {
				// Wanting something you cannot see is a reason to go looking, not a
				// reason to stand still. The body drifts on the same oscillator the
				// mind reads as S_CLOCK, so the search is a deterministic wander
				// rather than a freeze -- "forage" means find food, not merely walk at
				// food already in view.
				searching = true;
				t = clamp(sensors[AgentIO.S_CLOCK], -1, 1);
			}
		}
		// Speed is deliberately NOT the intent's business. An intent says where to go
		// and, on arrival, what to do there -- but how hard to push is the one part of
		// locomotion worth leaving to selection, because movement costs the square of
		// speed and so the throttle is where a lineage spends or saves its living. A
		// body given a goal and no throttle wants something and stays put, which is a
		// policy a mind is free to hold.
		// Mating is a commitment, so while it has a partner it takes the wheel: a
		// creature walking to a mate is not also foraging, and one mid-exchange is
		// standing still. A_SEEK resumes the moment the intent goes invalid.
		boolean wantsMate = a[AgentIO.A_MATE] > 0.5;
		double mateStatus = wantsMate ? mateIntent() : Double.NaN;
		boolean mating = wantsMate && mateStatus == AgentIO.INTENT_PENDING;
		if (mating && !Double.isNaN(mateSteer)) {
			t = clamp(mateSteer / MAX_TURN, -1, 1); // walk to the partner
		}
		if (matingWith != null) {
			throttle = 0; // hold station: the exchange happens in place
		}
		if (a[AgentIO.A_MARK] > 0.5) {
			wpX = X; // remember here
			wpY = Y;
			wpLvl = getLvl();
		} else if (a[AgentIO.A_MARK] < -0.5) {
			wpLvl = -1; // forget it
		}
		interactIntent = a[AgentIO.A_INTERACT] > 0.5; // deliberately operate a fixture
		D = wrap(D + t * MAX_TURN); // steer
		// Throttle IS the desired speed: 0 is standing still, 1 is flat out at the
		// genome's top speed. There is no separate gear to engage — the movement
		// cost is quadratic in speed, so easing off is exactly how a mind saves
		// energy, and pushing hard is exactly what it pays for.
		if (throttle > 0.02) {
			move(throttle * speed, D);
		}
		// Nothing here steers vertically. Changing level is the ground's business:
		// a ramp is floor that spans two levels and carries whatever walks across it,
		// so a mind reaches the cave the same way it reaches anywhere else -- by
		// walking there. A hole is a pit, and falling in one is a mistake, not a move.
		// Carrying the intent through to the act. A goal with one unambiguous thing to
		// do on arrival does it, so "forage" is a behaviour rather than half of one --
		// which is the whole reason an intent is worth an instruction. Only the
		// approach sense acts: fleeing a thing is not a reason to bite it.
		boolean chasing = pursuing && !away;
		// Arriving only grazes when the ground being sought is food. A body that asked
		// for cover and reached it has arrived, full stop -- there is nothing to eat
		// in a thicket, and the terminal act has to follow what was actually wanted.
		// A scavenger's forage is a body, not ground, so the tile-property gate does
		// not apply to it: there is no "wanted tile" underneath a carcass, and
		// requiring one meant a scavenger could steer to its food and then refuse to
		// eat it. What the gate is really for is not acting on ground that was asked
		// for as cover or as water, and that reasoning only concerns a grazer.
		boolean intentGraze = chasing && seekClass == AgentIO.SEEK_FORAGE
				&& (diet == Diet.SCAVENGER || tileWanted == AgentIO.TILE_FOOD);
		boolean intentTake = chasing && seekClass == AgentIO.SEEK_ITEM;
		boolean intentBite = chasing && seekClass == AgentIO.SEEK_PREY;
		// Seeking a fixture and reaching it presses it: arriving IS the act,
		// so the intent carries through without the mind also having to hold
		// A_INTERACT high. In-reach is read off the same sensed proximity the
		// mind was shown (0.45 ~ just over a tile, the switch's own reach).
		boolean intentPress = chasing && seekClass == AgentIO.SEEK_FIXTURE
				&& sensors[AgentIO.S_FIXTURE_PROX] >= 0.45;
		if (intentPress) {
			interactIntent = true;
		}
		boolean eats = a[AgentIO.A_EAT] > 0.5;

		double eaten = 0;
		if (eats || intentGraze) {
			// "Forage" means the food THIS body eats. For a grazer that is the
			// vegetation underfoot; for a scavenger it is the carcass it walked to.
			// Routing both through the same intent is what lets a scavenger inherit
			// the forage behaviour every starter brain already has, instead of
			// needing a sensor and a policy of its own.
			// A parasite's mouth works on nothing here: it cannot graze and it
			// does not scavenge — its whole living is the host drain reflex
			// (parasiteFeed), which runs while it rides, hungry.
			eaten = diet == Diet.SCAVENGER ? scavenge()
					: diet == Diet.PARASITE ? 0 : graze(grazeDemand());
			totalIntake += eaten;
		}
		boolean ateItem = false;
		if (eats || intentTake) {
			ateItem = eatNearestItem(); // devour a food (or bite a hazard) in reach
		}
		if (a[AgentIO.A_DEPOSIT] > 0.5) {
			depositPheromone(NEST_DEPOSIT * 0.25);
		}
		boolean bit = false;
		if (a[AgentIO.A_ATTACK] > 0.5 || intentBite) {
			bit = attackNearest();
			if (a[AgentIO.A_ATTACK] > 0.5) {
				attackNearestItem(); // smashing crates is a deliberate act, not a side
			}                        // effect of running something down
		}
		boolean bred = mateStatus == AgentIO.INTENT_DONE;
		boolean asexual = genome == null || !genome.isSexual();
		if (wantsMate && asexual) {
			// The body's whole answer for an asexual lineage: bud, alone, whenever it
			// can afford to. It will not court and does not care who is nearby.
			bred = tryReproduce();
		}
		// A sexual body has no fallback: with nobody to court it simply waits. That is
		// what makes the two strategies genuinely different, rather than one being the
		// other plus a bonus.
		// How the intent went, for the mind to read next tick. DONE is the terminal
		// act actually firing -- grass eaten, a bite landed, an item taken -- not
		// merely arriving; PENDING is a goal in sight and not yet reached; INVALID is
		// the guards failing, which covers both "there is no such thing here" and "the
		// thing I was chasing is gone". Nothing reports a plain failure, because a
		// latched intent never finishes losing: see AgentIO.S_INTENT.
		if (wantsMate && mateStatus != AgentIO.INTENT_INVALID) {
			intentStatus = mateStatus; // a commitment outranks a preference
		} else if (wantsMate && seekClass == AgentIO.SEEK_NONE) {
			intentStatus = mateStatus; // wanted to breed, and could not
		} else if (seekClass == AgentIO.SEEK_NONE) {
			intentStatus = AgentIO.INTENT_IDLE;
		} else if (searching) {
			intentStatus = AgentIO.INTENT_INVALID;
		} else if ((intentGraze && eaten > 0) || (intentBite && bit) || (intentTake && ateItem)
				|| intentPress
				|| (chasing && seekClass == AgentIO.SEEK_FORAGE
						&& tileWanted != AgentIO.TILE_FOOD && onWantedTile())) {
			intentStatus = AgentIO.INTENT_DONE;
		} else {
			intentStatus = AgentIO.INTENT_PENDING;
		}
		// A plain-language label for what this mind actually DID, so the viewer can
		// follow a minded creature and read its behaviour without opening the mind
		// inspector. Deliberately reports outcomes, not intent: a starter brain
		// holds eat and mate high permanently, so labelling the actuators would
		// read "mating" forever regardless of what the creature achieved.
		if (bit) {
			setAction("attacking", true); // an instant: latch it so it can be seen
		} else if (matingWith != null) {
			setAction("courting", false); // the exchange, visibly taking its time
		} else if (bred) {
			setAction("breeding", true);
		} else if (eaten > 0) {
			setAction("grazing", false);
		} else if (throttle > 0.02) {
			setAction(throttle > 0.7 ? "running" : "wandering", false);
		} else {
			setAction("resting", false);
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
		} else if (chasing && seekClass == AgentIO.SEEK_FORAGE && diet == Diet.PARASITE) {
			// A parasite that reaches the host its forage intent names latches on:
			// arriving IS the act, exactly as pressing is for a fixture, so the
			// starter mind that merely forages can make this living at all. The
			// grip holds for as long as the intent does — a mind that switches to
			// water or to flight climbs off, which is how a riding parasite still
			// gets to a shore before its own thirst kills it.
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
		if (grabbing != null || getAttachTarget() != null || !canExert()) {
			return; // already engaged either way — or collapsed (VITALS.md)
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
			// Parasites do not stack: a parasite never rides another parasite
			// (a chain of drains would bleed the bottom host through the pile).
			if (diet == Diet.PARASITE && n instanceof TestNPC tn && tn.diet == Diet.PARASITE) {
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
		if (near == null || near == this || near.isDead() || !canExert()) {
			return false; // a collapsed body can crawl, not fight (VITALS.md)
		}
		double reach = (getSize() + near.getSize()) / 2.0 + ATTACK_REACH;
		if (distance(near.getX(), near.getY(), near.getZ()) > reach) {
			return false;
		}
		near.damage(ATTACK_DAMAGE, "combat");
		feed(BITE_ENERGY); // predation feeds the attacker — into the stomach
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

	/** Nearest interactable fixture (switch) in sight range on this level, or
	 *  null. Scans the world's entities rather than the perceived-creature
	 *  set, which is NPC-only by design -- furniture must never read as a
	 *  body -- and fixtures are few, so the sweep stays cheap. */
	private net.hedinger.prototype.entities.Switch nearestFixture() {
		net.hedinger.prototype.entities.Switch near = null;
		double best = LOS_RANGE;
		// Census walk: this level's switches — a handful, not the whole world.
		for (net.hedinger.prototype.entities.Switch sw : getWorld().census().switches(getLvl())) {
			if (sw.isRemoved()) {
				continue;
			}
			net.hedinger.prototype.engine.Entity e = sw;
			double d = distance(e.getX() + 0.5, e.getY() + 0.5, getZ());
			if (d < best) {
				best = d;
				near = sw;
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

	/** Eats the nearest food/hazard item in reach: food feeds, a hazard bites back.
	 *  Reports whether anything was actually taken, so an intent can say whether it
	 *  got what it went for. */
	private boolean eatNearestItem() {
		Item item = itemInReach();
		if (item != null && item.isEdible()) {
			item.beEatenBy(this);
			return true;
		}
		return false;
	}

	/**
	 * Takes one bite out of the nearest carcass in reach, if any. Returns the mass
	 * consumed, on the same scale {@link #graze} reports, so both feed the same
	 * intake counter.
	 *
	 * <p>The bite does two things at once, and they are the same thing: it converts
	 * carcass mass into the eater's energy, and it ages the corpse toward removal
	 * ({@link net.hedinger.prototype.entities.NPC#eat}). A scavenger does not
	 * "trigger" decomposition — feeding IS the decomposition, which is why this
	 * needs no separate decay hook.
	 *
	 * <p>A bite is paid at full rate and freshness is not charged again on top of
	 * it, because rot is already priced -- in how much carcass is LEFT. Eating
	 * advances the same clock decay does, so a body found half rotted has half its
	 * bites remaining and yields half as much; one found nearly gone yields nearly
	 * nothing. Multiplying the per-bite rate by freshness as well discounted the
	 * same rot twice and made a whole fresh carcass worth half a kill of the same
	 * mass. The incentive to reach bodies early survives intact -- it just comes
	 * from the size of what is left rather than from a second penalty.
	 */
	private double scavenge() {
		NPC carrion = carrionInReach();
		if (carrion == null) {
			return 0;
		}
		double mass = carrion.bodyMass() * CARRION_BITE;
		feed(mass * CARRION_ENERGY); // meat -> stomach; satiation powers the body
		// Aged in ticks of its own remaining span: a bite takes the same FRACTION
		// out of a mouse as out of an apex body, so a big carcass is genuinely more
		// meals rather than merely a bigger number.
		carrion.eat(Math.max(1, (int) Math.round(carrion.getDeathspan() * CARRION_BITE)));
		setAction("eating", true);
		return mass;
	}

	/**
	 * Diet is a reproductive barrier on top of the genome's own similarity test: a
	 * grazer and a scavenger are not the same animal, whatever their markers say.
	 *
	 * <p>Without this a scavenger spends its scarce fertile ticks courting the far
	 * more numerous herbivores around it, and because the child is built by whichever
	 * parent closes the exchange, such a pairing yields a grazer either way --
	 * measured on seed 7, the one fertile scavenger in 40k ticks held a 99-tick
	 * courtship with a minded herbivore and produced nothing of its own kind. A
	 * niche that cannot breed true is not a niche.
	 */
	@Override
	protected double travelEfficiency() {
		return diet == Diet.SCAVENGER ? SCAVENGER_TRAVEL : 1.0;
	}

	@Override
	public boolean canMateWith(NPC other) {
		if (other instanceof TestNPC t && t.diet != diet) {
			return false;
		}
		return super.canMateWith(other);
	}

	/** The nearest carcass this body could bite right now, or null. Corpses only --
	 *  a scavenger has no way to kill, so a living body is not food to it. */
	private NPC carrionInReach() {
		if (getWorld() == null) {
			return null;
		}
		NPC best = null;
		double bestD = Double.MAX_VALUE;
		// Census walk: this level's corpses only.
		for (NPC n : getWorld().census().corpses(getLvl())) {
			if (n == this || !n.isDead() || n.isRemoved()) {
				continue;
			}
			double reach = (getSize() + n.getSize()) / 2.0 + CARRION_REACH;
			double d = distance(n.getX(), n.getY(), n.getZ());
			if (d <= reach && d < bestD) {
				bestD = d;
				best = n;
			}
		}
		return best;
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
				setAction("fleeing", false);
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
			setAction("breeding", true);
			return;
		}
		if (sated()) {
			// Fully fed: drift off the patch and let the grass recover.
			setAction("sated", false);
			roam(speed, turn);
			return;
		}
		if (intake < grazeDemand() * 0.15) {
			double herd = vigilant ? herdDir(HERD_R) : Double.NaN;
			if (!Double.isNaN(herd)) {
				setAction("herding", false);
				roam(speed, turn, herd); // regroup with kin
			} else {
				setAction("foraging", false);
				roam(speed, turn); // patch thinning -> find fresh grass
			}
		} else {
			setAction("grazing", false);
			settleOnPatch(); // stand over the patch being eaten, not on its seam
		}
	}

	/** Nearest living predator within {@code radius} (eco threat sense), or null.
	 *  Keys on {@link #ecoRole()} rather than raw size, so a herbivore flees
	 *  hunters — not merely larger herbivores. Scans proximity, not the
	 *  facing-gated perception set, so a predator can't sneak up from behind. */
	private NPC nearestThreat(double radius) {
		NPC best = null;
		double bestD = radius;
		// Census walk (see World.Census): the predators of this level only —
		// same level only, no fleeing a hunter a floor away.
		for (NPC t : getWorld().census().predators(getLvl())) {
			if (t == this || t.isDead() || t.isRemoved()) {
				continue;
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
		// Census walk: this level's prey — herd with kin you can actually reach.
		for (NPC t : getWorld().census().prey(getLvl())) {
			if (t == this || t.isDead() || t.isRemoved()) {
				continue;
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
				net.hedinger.prototype.entities.Nest.claimAt(getWorld(), X, Y, Z);
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

	/**
	 * Hands a newborn the body traits its parent carries that the genome does not.
	 *
	 * <p>Most of what a body is comes from the genome and is inherited by being
	 * mutated and copied — size, speed, colour, senses, whether it flies. These are
	 * the rest: flags set on the body after it was built, by the world that seeded
	 * it or the warden that replaced it. They were being dropped, and silently,
	 * because each factory branch remembered a different subset of them. A minded
	 * child inherited none at all.
	 *
	 * <p>Diet is the one that mattered: a scavenger's child was born a grazer, so
	 * the niche could not grow no matter how well its parents ate. Corpse lifespan
	 * and predator-vigilance were dropped the same way for minded lineages. They
	 * are copied together here so that adding a body trait means adding it in one
	 * place rather than in four, and forgetting is not the default.
	 */
	private void passBodyTraitsTo(TestNPC child) {
		// Diet is NOT copied here: it rides in the genome now, so the child was born
		// with it. Copying it a second time would be a second source of truth, and
		// the one that silently wins whenever the two disagree.
		child.vigilant = vigilant; // a vigilant lineage stays predator-aware
		child.alwaysInteract = alwaysInteract;
		child.withDeathspan(deathspan); // the lineage shares how long its dead lie about
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
			child = predator(X, Y, Z, childG);
		} else {
			child = behavior == Behavior.NEST ? nester(X, Y, Z, childG) : breeder(X, Y, Z, childG);
		}
		passBodyTraitsTo(child);
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
		passBodyTraitsTo(child); // a pair breeds within its diet, so either parent's will do
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
		} else {
			settleOnPatch();
		}
	}

	/**
	 * Eases a cropping body off the tile boundary toward the centre of the
	 * patch it is actually eating. A grazer halts wherever the roam happened
	 * to leave it -- uniformly across the tile, so half the time within a
	 * step of a grid line -- and grazing eats the tile under {@code floor(X,
	 * Y)}, which visually put bodies on the seam between their own depleted
	 * square and the neighbour's fresh one. The drift is slow (a stroll, not
	 * a step) and stops inside a small radius, so a settled body genuinely
	 * rests and the RNG stream quiets with it.
	 */
	private void settleOnPatch() {
		double dx = Math.floor(X) + 0.5 - X, dy = Math.floor(Y) + 0.5 - Y;
		if (Math.hypot(dx, dy) > 0.12) {
			move(speed * 0.3, Math.atan2(dy, dx));
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
		// Two opposite things used to share one badge, and the comment saying so sat
		// right here: hauling a captive and being carried are not the same act, and
		// the one that tells you what you are looking at is which end of it a
		// creature is on.
		if (grabbing != null) {
			return "carry"; // this body is doing the carrying
		}
		if (getAttachTarget() != null && !isGrabbed()) {
			return "ride"; // aboard a host by its own choice, paying no fare
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
		case MATER:
			return "graze"; // no per-tick action label: these always forage
		case NEST:
			return homing ? "nest" : "graze";
		default:
			// Breeders, predators and the minded cohort all report what they
			// actually did last tick, so the glyph follows real behaviour rather
			// than the species. Predators and minded bodies previously fell through
			// here and showed nothing at all.
			return glyphFor(ecoAction);
		}
	}

	/** Maps a last-tick action label to a glyph key, or null for the unremarkable
	 *  ones (wandering, resting, prowling, sated, herding) — a badge over every
	 *  creature all the time would be noise rather than information. */
	private static String glyphFor(String action) {
		switch (action) {
		case "attacking":
			return "attack";
		case "breeding":
			return "mate";
		case "fleeing":
			return "flee";
		case "grazing":
		case "foraging":
			return "graze";
		case "hunting":
		case "starving":
			return "hunt";
		default:
			return null;
		}
	}

	/** Ecosystem role, for the world steward's population census: {@code
	 *  "predator"}, {@code "prey"}, or {@code ""} for anything else. */
	/**
	 * Where this body sits in the food chain, for the viewer: {@code "prey"},
	 * {@code "predator"} or {@code "scavenger"}. Every creature has one.
	 *
	 * <p>Deliberately NOT {@link #ecoRole()}, which returns {@code ""} for the
	 * minded cohort because its role is meant to emerge rather than be assigned.
	 * That blank is right for the steward -- which counts minded creatures as their
	 * own category -- and useless to someone looking at a creature and asking what
	 * it eats. The two must stay separate: {@code ecoRole} is also the key the
	 * population trims and the flee/herd scans match on, so widening it to cover
	 * minded bodies would quietly enrol them in the prey ceiling and the herds.
	 */
	public String trophicRole() {
		if (diet == Diet.SCAVENGER) {
			return "scavenger";
		}
		if (diet == Diet.PARASITE) {
			return "parasite";
		}
		if (diet == Diet.CARNIVORE || behavior == Behavior.PREDATOR) {
			return "predator";
		}
		return "prey"; // grazes, and is hunted by anything its size or larger
	}

	public String ecoRole() {
		// Diet decides this before behaviour does: a scavenger is a scavenger
		// whatever loop drives it, and the census has to see it as its own trophic
		// level rather than folding it in with the herbivores it is sized like.
		if (diet == Diet.SCAVENGER) {
			return "scavenger";
		}
		if (diet == Diet.PARASITE) {
			return "parasite"; // its own census bucket, like the scavengers
		}
		if (behavior == Behavior.PREDATOR) {
			return "predator";
		}
		// Nesters are prey like any other herbivore: hunted by predators,
		// counted and trimmed by the steward. Leaving them roleless made them
		// invisible to every population check — an unhunted, uncapped lineage
		// that exploded exponentially the moment the demo seeded some.
		return behavior == Behavior.BREEDER || behavior == Behavior.NEST ? "prey" : "";
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
