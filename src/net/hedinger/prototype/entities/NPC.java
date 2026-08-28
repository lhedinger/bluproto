package net.hedinger.prototype.entities;

import java.awt.Color;
import java.util.Stack;
import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.ResourceManager;
import net.hedinger.prototype.engine.Utils;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.engine.Perf;

public abstract class NPC extends Entity {
	// misc. variables
	protected boolean drawLine;
	protected boolean drawTrace;
	protected boolean drawPing;
	protected boolean drawLOS;

	// targeting variables
	protected double tX, tY, tZ;
	protected int SEARCH_FREQ;
	protected double LOS_RANGE; // max distance entity can see (tiles)
	protected double LOS_FOV; // max field of view (radians)
	protected int status;
	protected int size;
	/** Deliberate interact intent this tick (the A_INTERACT actuator, or a fixture's
	 *  standing order): what an intent-driven switch listens for. */
	protected boolean interactIntent = false;
	protected double speed = 0.04; // tiles/tick; sourced from the genome
	protected int turnRate = 5; // steering divisor; sourced from the genome
	protected int maxAge = 3000; // ticks before old age; sourced from the genome
	protected Color col = Color.ORANGE;

	// --- energy economy (opt-in: only entities that set `metabolic` take part) ---
	// Energy is in vegetation units: graze() feeds it, metabolism burns it each
	// tick, and an entity that hits zero starves. Real species leave `metabolic`
	// false, so their behaviour and determinism are untouched.
	protected boolean metabolic = false;
	protected double energy = 1.0;
	// Movement has no separate "gear": a creature simply chooses how fast to go
	// (its throttle, or a behaviour's chosen speed) and the movement cost below
	// prices that choice continuously. There is no sprint flag and no surcharge.

	// --- size-scaled energy model --------------------------------------------
	// Everything scales off a body-size factor, normalised so a reference-size
	// creature is 1.0. The reserve (the "fully fed" tank) grows in proportion to
	// size, while the resting burn grows only with size^0.75 (Kleiber: a big body
	// burns more in absolute terms but less per unit mass). So a big creature's
	// bigger tank outlasts a small one's — large animals fast longer between meals
	// — yet still need bigger meals to top up. Anchored so a reference creature
	// lasts a few minutes on a full tank.
	/** Body size the energy model is anchored on (size factor 1.0 here). */
	public static final double REF_SIZE = 8.0;
	/** Full energy reserve of a reference-size creature ("fully fed"). */
	public static final double BASE_CAPACITY = 6.0;
	/** Resting energy/tick a reference-size creature burns. At 33 t/s a full
	 *  reference tank (6.0 / 0.0005 = 12000 ticks) lasts ~6 minutes unfed, so even
	 *  a half-empty creature has a few minutes of reserve before it starves. */
	public static final double BASE_METABOLISM = 0.0005;
	/** The neutral {@link Genome#metabolism}; a genome at this value is an
	 *  average burner, and mutations above/below it scale efficiency. */
	public static final double META_REF = 0.02;

	// --- growth: born small, grow into the genome's body ----------------------
	/** Fraction of its adult body a creature is born at. */
	public static final double BIRTH_SIZE_FRACTION = 0.35;
	/**
	 * Growth in body radius per tick. A FIXED rate, so the distance to travel —
	 * and therefore the length of childhood — scales with how big the adult body
	 * is: a small grazer is grown in seconds, the largest possible body takes the
	 * longest. At {@link Genome#SIZE_MAX} (20) the climb from birth size is 13
	 * units, or ~1970 ticks — about one minute at 33 ticks/s, the longest
	 * childhood the world can produce.
	 */
	public static final double GROWTH_RATE = 0.0066;

	/** Adult body this creature is growing toward; 0 for a body that does not grow. */
	protected double adultSize = 0;
	/** Continuous size while growing — {@link #size} is this, rounded, because the
	 *  body radius itself is an integer. */
	protected double grownSize = 0;

	/**
	 * Starts this body as a juvenile that will grow into {@code adult}. The
	 * size-derived economy (tank, burn, collision reach, what can eat it) follows
	 * the CURRENT body, so a juvenile is genuinely small: cheaper to run, but a
	 * smaller reserve and easy prey.
	 */
	protected void beginGrowth(double adult) {
		adultSize = adult;
		grownSize = Math.max(1, adult * BIRTH_SIZE_FRACTION);
		size = (int) Math.round(grownSize);
	}

	/**
	 * Ticks a body of adult radius {@code adult} spends growing up: the climb from
	 * birth size to full size at the fixed {@link #GROWTH_RATE}. Linear in adult
	 * size, and therefore linear in mass, since {@link #bodyMass()} is just size
	 * normalised to {@link #REF_SIZE}.
	 *
	 * <p>Exposed because rotting is pinned to it — a body takes as long to return
	 * to the world as it took to build. Deriving both from these two constants
	 * keeps that relationship true when either is tuned, instead of leaving a
	 * second magic number to drift out of step.
	 */
	public static int growthTicks(double adult) {
		return (int) Math.round((1 - BIRTH_SIZE_FRACTION) * adult / GROWTH_RATE);
	}

	/** The adult body this creature is still growing toward, or 0 when it is
	 *  not growing — in TILES, matching {@link #getSize()}'s wire convention
	 *  (the raw fields are in pixels). Growth is deterministic
	 *  ({@link #GROWTH_RATE} pixels per tick, clamped at the adult body), so
	 *  the observation seam can hand a viewer the target and let it
	 *  extrapolate the size itself. */
	public double getGrowthTarget() {
		return adultSize > 0 && grownSize < adultSize
				? adultSize / ResourceManager.tileSize : 0;
	}

	/**
	 * How far this corpse has rotted, 0 (just died) to 1 (gone). A living body
	 * reports 0. Age counts down from 0 to {@code -deathspan} once dead, so this
	 * is simply how far through that it has fallen.
	 */
	public double decayProgress() {
		if (age >= 0 || deathspan <= 0) {
			return 0;
		}
		return Math.min(1.0, -age / (double) deathspan);
	}

	/** True while this body is still growing into its adult size. */
	public boolean isJuvenile() {
		return adultSize > 0 && grownSize < adultSize;
	}

	/** How grown this body is, 0..1; 1 for anything fully grown or that never grows. */
	public double maturity() {
		return adultSize <= 0 ? 1.0 : Math.min(1.0, grownSize / adultSize);
	}
	/**
	 * Movement cost coefficient: a creature pays
	 * {@code MOVE_ENERGY * mass * v^2} every tick, where {@code v} is the ground
	 * it actually covered that tick — kinetic energy, so speed is charged as a
	 * square rather than a flat toll per tile.
	 *
	 * <p>The square is what makes speed genuinely expensive. Cost per TICK rises
	 * with v², so cost per TILE rises linearly with v: going twice as fast costs
	 * four times as much per tick and twice as much per tile. A creature that
	 * merely wants to cover ground is therefore better off going slowly, and speed
	 * has to buy something real — escaping, or catching — to be worth its price.
	 * There is deliberately no separate sprint gear: a creature just chooses how
	 * fast to move and this prices the choice continuously, at every speed, rather
	 * than only above a threshold.
	 *
	 * <p>Anchored so a reference-size creature moving at about the founder speed
	 * (0.05 tiles/tick) pays roughly its own resting rate again — the same
	 * break-even the earlier flat model had — while a genuinely fast one now pays
	 * several times over rather than merely proportionally.
	 */
	public static final double MOVE_ENERGY = 0.2;

	/** This creature's clade ("herbivore", "predator", ...), or "" for
	 *  species outside the eco simulation. Virtual so the engine's per-tick
	 *  census can bucket bodies without knowing their concrete classes. */
	/** A body is bound by terrain: it cannot end a tick inside a wall, whatever
	 *  put it there. See {@link net.hedinger.prototype.engine.Entity#boundToTerrain}. */
	@Override
	protected boolean boundToTerrain() {
		return true;
	}

	public String ecoRole() {
		return "";
	}

	/**
	 * Whether this body is made of meat — the one question every appetite in
	 * the world is really asking. Living things answer yes; a machine answers
	 * no, and by answering no drops out of the prey channel, the host search,
	 * the carrion scan and the mating test all at once.
	 *
	 * <p>Asked as a property of the body rather than tested with an
	 * {@code instanceof} at each appetite, because "is that food?" is a fact
	 * about the thing being looked at, not about the looker: a machine has to
	 * be inedible to every appetite there is, including the ones a future diet
	 * invents. A hunter that could learn to bite the drone is a hunter that
	 * starves chewing on steel.
	 */
	public boolean isOrganic() {
		return true;
	}

	/** Body-size factor, 1.0 at {@link #REF_SIZE}; drives every energy scale.
	 *  Falls back to the reference when no size is set.
	 *
	 *  <p>Public because a body's mass is a fact about it that other creatures act
	 *  on — a predator has to weigh its quarry to know what the meal is worth. */
	public double bodyMass() {
		double s = size > 0 ? size : REF_SIZE;
		return s / REF_SIZE;
	}

	/**
	 * Everything this body is hauling, in the same units as {@link #bodyMass()} so
	 * the two simply add up.
	 *
	 * <p>A load is not a separate bill — it is just extra mass. Whatever a carrier
	 * is holding makes it heavier, and being heavier is already expensive through
	 * the one channel that prices mass: movement. So hauling costs exactly what it
	 * should, when it should. Standing still holding something is nearly free
	 * (only the grip, if the thing is an unwilling captive), and walking off with
	 * it costs in proportion to how much of it there is and how fast you go.
	 *
	 * <p>{@code carriedLoad} accumulates {@code getSize()}, which is in tiles,
	 * while {@code bodyMass()} is normalised to {@link #REF_SIZE} — hence the
	 * conversion. Flying counts a load heavier: holding a body up in the air is
	 * harder than dragging it along the ground.
	 */
	protected double carriedMass() {
		double load = getCarriedLoad() * ResourceManager.tileSize / REF_SIZE;
		return isFlying() ? load * FLIER_CARRY_MULTIPLIER : load;
	}

	/** Size factor of the body this creature is growing INTO, 1.0 at
	 *  {@link #REF_SIZE}. Falls back to the current body for anything that does not
	 *  grow. */
	protected double adultMass() {
		double s = adultSize > 0 ? adultSize : (size > 0 ? size : REF_SIZE);
		return s / REF_SIZE;
	}

	/**
	 * "Fully fed" energy ceiling: the tank grows with size, so a big body banks
	 * more and can go longer between meals (but needs more food to top up).
	 *
	 * <p>Anchored on the ADULT body, deliberately. Growth is a physical change —
	 * how much a body burns, how far it reaches, what can eat it — and pinning the
	 * tank to the grown body keeps the whole reproduction economy (born-fed level,
	 * breeding threshold, breeding cost, and a hunter's "sated" line, all of which
	 * are fractions of this) identical to what it was before creatures grew. A
	 * juvenile-sized tank would instead have silently re-gated breeding on maturity
	 * and left young hunters unable ever to count as sated.
	 */
	public double energyCapacity() {
		return BASE_CAPACITY * adultMass();
	}

	protected double reproThreshold = 2.0; // energy needed to bud an offspring
	protected double reproCost = 1.0; // energy spent per offspring
	protected int reproCooldown = 0; // ticks until able to reproduce again
	public static final int REPRO_COOLDOWN = 100;
	/**
	 * Energy per tick per unit of held body weight, for keeping a grip on a
	 * <em>grabbed</em> captive — the cost of restraint itself, separate from the
	 * weight, which is priced by {@link #carriedMass}.
	 *
	 * <p>A voluntary rider costs its carrier nothing beyond the weight — it clings
	 * on by its own effort — so this is the whole difference between a passenger and
	 * a prisoner. It is charged whether or not the captor moves, so holding somebody
	 * is an effort that has to keep being paid for rather than a free permanent
	 * state: a captor must eventually either eat its captive or let go.
	 *
	 * <p>Sits below {@link #STRUGGLE_CARRIER_COST} so a captive that actively fights
	 * still costs its captor more than one hanging limp.
	 */
	public static final double GRIP_ENERGY = 0.10;
	/** Fraction of normal metabolism a voluntary rider pays while carried (its
	 *  bonus for hitching a ride instead of walking). */
	public static final double RIDER_METABOLISM = 0.5;
	/** How far beyond touching a creature can reach to climb aboard a host, in
	 *  tiles — the same margin biting, mating and grabbing already allow. */
	protected static final double ATTACH_REACH = 0.5;
	/** Extra energy a captor burns per tick per unit of (weight x struggle) -- the
	 *  surcharge for hauling an unwilling captive over a consenting passenger. */
	public static final double STRUGGLE_CARRIER_COST = 0.35;
	/** Energy a struggling captive burns itself per tick per unit of struggle --
	 *  fighting is exhausting, so consenting conserves the captive's reserves. */
	protected static final double STRUGGLE_SELF_COST = 0.02;
	/** How much heavier a load counts while the carrier is flying: holding a body
	 *  up through the air is far harder work than dragging it over the ground. */
	public static final double FLIER_CARRY_MULTIPLIER = 5.0;
	/** Energy a carrier burns per tick per unit of buck effort (shaking riders
	 *  off is exhausting, just like a captive's struggle). */
	protected static final double BUCK_SELF_COST = 0.02;
	/** Buck effort needed to throw a same-size rider; scaled by host/rider size,
	 *  so a much smaller rider (tighter relative grip) is far harder to dislodge. */
	protected static final double BUCK_GRIP = 8.0;
	/** Ticks a just-bucked rider cannot re-attach, so it is actually thrown clear. */
	protected static final int BUCK_COOLDOWN = 200;

	/** Ticks remaining before this creature may latch onto a host again (set when
	 *  it is bucked off). */
	protected int attachCooldown = 0;

	/** Bars this creature from re-attaching for a while (used when it is bucked off). */
	public void startAttachCooldown(int ticks) {
		attachCooldown = ticks;
	}

	/** Removes energy (never below zero); used when another entity imposes a cost,
	 *  e.g. a struggling captive draining its captor. */
	public void drainEnergy(double amount) {
		energy -= amount;
		if (energy < 0) {
			energy = 0;
		}
	}

	/** Adds energy; used when an external source feeds this creature, e.g. eating
	 *  a food {@link Item}. */
	public void addEnergy(double amount) {
		energy += amount;
	}

	public double getEnergy() {
		return energy;
	}

	/**
	 * Multiplier on the cost of covering ground, 1.0 for an ordinary body. The hook
	 * exists so a lineage whose living is made by travelling can be cheap over
	 * distance without being fast — the two are different adaptations and only the
	 * first is affordable, since movement is priced on the square of speed.
	 */
	protected double travelEfficiency() {
		return 1.0;
	}

	private double metabolismRate() {
		// Resting burn scales with mass^0.75 (Kleiber). The genome's metabolism is
		// a heritable efficiency multiplier normalised to META_REF, so an average
		// genome burns exactly the size-based rate and mutations nudge it.
		double eff = genome != null ? genome.metabolism / META_REF : 1.0;
		return BASE_METABOLISM * Math.pow(bodyMass(), 0.75) * eff;
	}

	/** Heritable trait vector; null for species that do not use one (yet). */
	protected Genome genome = null;

	public Genome getGenome() {
		return genome;
	}

	/**
	 * Sources this NPC's body stats from a founder {@link Genome} and keeps the
	 * reference. The genome becomes the single source of truth for the phenotype
	 * (size, speed, turn rate, perception, lifespan), so offspring can later
	 * inherit a mutated copy. Anything the genome does not carry -- health,
	 * SEARCH_FREQ, colour -- stays set by the species directly.
	 */
	protected void applyGenome(Genome g) {
		this.genome = g;
		this.size = (int) Math.round(g.size);
		this.speed = g.speed;
		this.turnRate = g.turnRate;
		this.LOS_RANGE = g.losRange;
		this.LOS_FOV = g.losFov;
		this.maxAge = g.maxAge;
	}

	private int blink_random = 0;
	private int blink_on = 50;
	private int blink_off = 5;
	private float ping = 0;

	private String message = "";
	private int message_fade = 0;
	private int mesage_fade_max = 0;

	// pathfinding variabels
	protected Stack<Integer> path;
	protected int path_next;
	protected int path_goal;

	protected int hostile = 2;

	protected int detected = 0;

	protected Entity grabbing = null;

	/** The creature this one has hold of, or null. The captive's own view of the
	 *  same fact is {@code isGrabbed()} plus {@code getAttachTarget()}; this is the
	 *  captor's, which nothing outside the engine could see before. */
	public Entity getGrabbing() {
		return grabbing;
	}

	protected boolean flying = false;

	protected TreeMap<Double, NPC> targets = new TreeMap<Double, NPC>();
	protected TreeMap<Double, NPC> focusTargets = new TreeMap<Double, NPC>();

	// Topological neighbourhood: like real flocks (starlings track ~7 nearest
	// neighbours regardless of crowding), each NPC only tracks its nearest
	// MAX_NEIGHBORS. This bounds the per-tick neighbour loops at O(K) instead of
	// O(local density), so a dense pile-up costs the same per entity as a light
	// crowd.
	protected int MAX_NEIGHBORS = Integer.getInteger("blu.k", 7);

	// Staggered-update period multiplier (1 = each NPC re-scans every
	// SEARCH_FREQ ticks). Tunable via -Dblu.stagger=N for benchmarking the
	// freshness/speed trade-off.
	public static int STAGGER = Integer.getInteger("blu.stagger", 1);

	public NPC(double x, double y, double z) {
		super(x, y, z);
		initialize();
	}

	protected NPC(double x, double y, double z, double d) {
		super(x, y, z, d); // honour the given heading: draws no RNG (items, clouds)
		initialize();
	}

	private void initialize() {
		tX = X;
		tY = Y;
		tZ = Z;

		size = 6;
		// col = new Color(150, 150, 150);
		drawLine = false;
		drawTrace = false;
		drawPing = false;
		drawLOS = false;

		path = null;
		path_next = -1;
		path_goal = -1;
		SEARCH_FREQ = 50;
		LOS_RANGE = 5;
		LOS_FOV = Math.PI; // entity can see 180 degrees left and right

		status = NPC.STATUS_IDLE;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		blink_random = (int) (Utils.random() * 0);
	}

	@Override
	protected void run_extended() {
		// A corpse holds no prisoners: if we died while carrying a captive, let it
		// go so it isn't clamped to a dead body (the captive also frees itself when
		// it sees its carrier is dead -- this clears our stale grip either way).
		if (isDead() && grabbing != null) {
			drop();
		}
		if (attachCooldown > 0) {
			attachCooldown--;
		}

		Perf.stopwatch.start();
		targets = scanTargets(targets);
		Perf.stopwatch.stop();

		if (status < 0 || status > 3) {
			status = NPC.STATUS_IDLE;
		}

		if (path != null) {
			if (path.size() == 0) {
				path = null;
			}
		}

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		// Growth: a juvenile creeps toward its adult body at a fixed rate, so the
		// bigger the adult the longer the childhood. Everything size-derived — tank,
		// resting burn, transport cost, collision reach, and whether a hunter can
		// take it — follows the body it has right now, not the one it will have.
		if (age >= 0 && adultSize > 0 && grownSize < adultSize) {
			grownSize = Math.min(adultSize, grownSize + GROWTH_RATE);
			size = (int) Math.round(grownSize);
		}

		// Corrosive ground: the facility's waste channels burn what wades them.
		// Outside the metabolic block on purpose -- this is chemistry, not
		// physiology, so it costs a body its health whether or not that body
		// keeps the four books, and the same erosion rate as a pegged need
		// keeps a spill comparable to a hunger a creature already understands.
		// Flyers skim over untouched, exactly as they do over water.
		if (age >= 0 && !isFlying() && age % DEPRIVATION_PERIOD == DEPRIVATION_PERIOD / 4
				&& getWorld() != null && getWorld().getTile(X, Y, Z).isCorrosive()) {
			damage(1, "toxic");
		}

		// The four books (VITALS.md): hunger and thirst rise with time, both gate
		// energy regeneration through satiation and erode health when pegged;
		// energy is spent by the acts and regenerated from satiation scaled by
		// vigor; health alone decides life and death (Entity.run reaps at 0).
		if (metabolic && age >= 0) {
			if (reproCooldown > 0) {
				reproCooldown--;
			}
			double cap = energyCapacity();
			double eff = metaEfficiency();
			// The thirst clock: capacity grows with m, the burn only with m^0.75,
			// so a big body's needs rise slower per unit of reserve (Kleiber's
			// fast). Hunger has no clock of its own any more — appetite arrives
			// through the regeneration drain below, so it tracks what the body
			// actually burns; the resting rhythm anchor (appetite returns in
			// twice the time thirst does) survives as the STOMACH identity.
			double pace = Math.pow(bodyMass(), -0.25) * eff;
			thirst = Math.min(1.0, thirst + pace / THIRST_PERIOD);
			// Drinking: a rate held over ticks, never a refill — a body beside
			// water sips as it goes about its business, and walking off mid-drink
			// keeps whatever partial refill had accrued.
			if (thirst > 0 && nearWater()) {
				thirst = Math.max(0, thirst - 1.0 / DRINK_TICKS);
			}
			double base = metabolismRate();
			// Rider bonus: a creature voluntarily riding a host spends less energy
			// (it is carried, not walking). A grabbed captive gets no such break.
			if (getAttachTarget() != null && !isGrabbed()) {
				base *= RIDER_METABOLISM;
			}
			// Grip: restraining an unwilling captive is work in its own right, and
			// the only thing a captor pays that a ferry carrying a willing passenger
			// of the same weight does not. The weight itself is not billed here --
			// see below.
			double grip = grabbing != null ? GRIP_ENERGY * grabbing.getSize() : 0.0;
			// Movement: kinetic, so a fast body pays the square of its speed, and it
			// pays for everything it is hauling because a load is simply extra mass.
			// Charged on the ground actually covered -- a step cancelled by a
			// collision moved nothing and costs nothing, so this prices travel rather
			// than intent, and standing still under a load is nearly free.
			double travel = MOVE_ENERGY * travelEfficiency() * (bodyMass() + carriedMass()) * lastStep * lastStep;
			// Regeneration: the body converts the stomach's contents into energy
			// over time — food never becomes energy directly (feed() fills the
			// stomach), and the mint drains the meal it is minted from, 1:1 in
			// energy units, so a body can never bank more than it actually ate.
			// The worse need still governs the rate, and vigor makes health
			// compound with the rest: an unhealthy body is also a listless one.
			double satiation = 1.0 - Math.max(hunger, thirst);
			double vigor = Math.max(0, health) / 100.0;
			double regen = REGEN_RATE * Math.pow(bodyMass(), 0.75) * eff * satiation * vigor;
			double out = base + grip + travel;
			// Only conversion that lands in the books draws down the stomach: at
			// a full tank the mint stops instead of burning the meal for nothing.
			double minted = Math.min(regen, Math.max(0, cap - energy + out));
			hunger = Math.min(1.0, hunger + minted / (STOMACH * adultMass()));
			energy = Math.min(cap, energy + regen - out);
			if (energy < 0) {
				energy = 0; // collapse, never death — health is the only gate
			}
			// A collapsed captor cannot hold: restraint is exertion, and below the
			// crawl reserve there is none to spend — the grip opens and the captive
			// walks free of a captor that is still alive.
			if (!canExert() && grabbing != null) {
				drop();
			}
			// Health: pegged needs erode it with their cause attached, so the
			// corpse still says what killed it; mending happens only under low
			// needs, at the pace-of-life rate (fast burners heal faster and pay
			// for it in appetite). Wounds themselves come from combat, as ever.
			if (hunger >= DEPRIVED && age % DEPRIVATION_PERIOD == 0) {
				damage(1, "starvation");
			}
			if (thirst >= DEPRIVED && age % DEPRIVATION_PERIOD == DEPRIVATION_PERIOD / 2) {
				damage(1, "thirst"); // offset phase: two pegged needs erode faster
			}
			if (health < 100 && hunger < NEED_LOW && thirst < NEED_LOW
					&& age % Math.max(1, (int) Math.round(MEND_PERIOD / eff)) == 0) {
				health++;
			}
		}
	}

	// --- the four books (VITALS.md) ------------------------------------------
	/** Ticks for thirst to rise slaked -> parched at the reference body
	 *  (~4.5 min at 33 t/s). The faster of the two need clocks. */
	public static final double THIRST_PERIOD = 9000;
	/** Ticks for hunger to rise sated -> starving in a RESTING reference body:
	 *  twice {@link #THIRST_PERIOD}, so appetite returns in twice the time
	 *  thirst does — the design's one rhythm anchor. No longer a clock of its
	 *  own: hunger rises only as regeneration drains the stomach, and this
	 *  period holds because the stomach is sized to it (see {@link #STOMACH}).
	 *  Exertion adds appetite on top, which the old clock could not price. */
	public static final double HUNGER_PERIOD = 18000;
	/** Ticks of standing at water for a full drink (~4 s): drinking is an act
	 *  with a duration, interruptible by simply walking away. */
	public static final double DRINK_TICKS = 132;
	/** Stomach of a reference body, in vegetation-energy units: eating
	 *  {@code STOMACH * adultMass()} worth of food takes hunger from starving
	 *  to sated. Not a free knob: it equals {@code BASE_METABOLISM *
	 *  HUNGER_PERIOD} (0.0005 * 18000), which is what makes the resting burn
	 *  drain a full stomach in exactly {@link #HUNGER_PERIOD} ticks — the
	 *  rhythm anchor, preserved by construction now that hunger has no clock
	 *  of its own. Change either factor and this must follow. */
	public static final double STOMACH = 9.0;
	/** Energy regenerated per tick by a fed, watered, healthy reference body —
	 *  before the resting burn nets it down. Anchored so an idle ideal body
	 *  refills an empty tank in roughly a minute and a half. */
	public static final double REGEN_RATE = 0.002;
	/** Fraction of the tank kept as a crawl reserve: below it the body is
	 *  collapsed — it can only crawl (see {@link #move}), not act. Collapse is
	 *  recoverable; death is health's decision alone. */
	public static final double CRAWL_RESERVE = 0.05;
	/** Fraction of the genome's top speed a collapsed body can still make. */
	public static final double CRAWL_SPEED = 0.25;
	/** A need at or above this is pegged, and starts eroding health. */
	public static final double DEPRIVED = 0.95;
	/** Needs below this count as low: mending and breeding both require it. */
	public static final double NEED_LOW = 0.5;
	/** Ticks between deprivation damage points: a pegged need kills through
	 *  health in ~2.5 min, slow enough that rescue by a meal or a shore is a
	 *  real possibility. */
	public static final int DEPRIVATION_PERIOD = 50;
	/** Ticks per mended health point (divided by metabolic efficiency): a bad
	 *  wound takes minutes of fed, watered living to close. */
	public static final int MEND_PERIOD = 160;
	/** Ticks a budding (asexual) birth must be held for before it completes —
	 *  ~5 s of sustained commitment; breaking off resets the act. */
	public static final int BREED_HOLD_TICKS = 165;

	/** The hunger need, 0 (sated) .. 1 (starving). Metabolic bodies only. */
	protected double hunger = 0;
	/** The thirst need, 0 (slaked) .. 1 (parched). Metabolic bodies only. */
	protected double thirst = 0;

	public double getHunger() {
		return hunger;
	}

	public double getThirst() {
		return thirst;
	}

	/** The genome's pace-of-life multiplier, 1.0 for an average burner (or a
	 *  body without a genome). Scales every rate: need rise, regeneration,
	 *  mending — and the resting burn via {@link #metabolismRate}. */
	protected double metaEfficiency() {
		return genome != null ? genome.metabolism / META_REF : 1.0;
	}

	/**
	 * Digests food worth {@code amount} (vegetation-energy units): lowers
	 * hunger by its share of the mass-scaled stomach. Deliberately never
	 * touches energy — satiation regenerates energy over time (VITALS.md), so
	 * a meal's power arrives gradually and an interrupted meal keeps exactly
	 * what was eaten.
	 */
	public void feed(double amount) {
		if (amount > 0) {
			double stomach = STOMACH * adultMass();
			swallowed += Math.min(amount, hunger * stomach); // only what fit counts
			hunger = Math.max(0, hunger - amount / stomach);
		}
	}

	/** Food units this body has digested over its lifetime — what actually fit
	 *  in the stomach, not what was merely bitten. The measurable end of
	 *  {@link #feed}, for probes and the scenario suite. */
	private double swallowed = 0;

	public double totalSwallowed() {
		return swallowed;
	}

	/** Room left in the stomach, in the same vegetation-energy units
	 *  {@link #feed} consumes — what a sated body can still swallow (0). */
	protected double stomachRoom() {
		return hunger * STOMACH * adultMass();
	}

	/** Whether the body has the reserve to act (bite, grab, breed, press):
	 *  below the crawl reserve it is collapsed and can only crawl. */
	public boolean canExert() {
		return !metabolic || energy > CRAWL_RESERVE * energyCapacity();
	}

	/** Legacy read: hydration is the complement of the thirst need. */
	public double getHydration() {
		return 1.0 - thirst;
	}

	/** Whether this body is standing at (or right beside) drinkable water:
	 *  any water or shallows tile in the 3x3 around its feet. */
	public boolean nearWater() {
		if (getWorld() == null) {
			return false;
		}
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (!getWorld().isValid(X + dx, Y + dy, Z)) {
					continue;
				}
				var t = getWorld().getTile(X + dx, Y + dy, Z).getType();
				if (t == net.hedinger.prototype.engine.Tile.TileType.TYPE_WATER
						|| t == net.hedinger.prototype.engine.Tile.TileType.TYPE_SHALLOWS) {
					return true;
				}
			}
		}
		return false;
	}

	/** Fertility a reference-size corpse returns to the tile it rotted on
	 *  (neighbours get a fraction). Sized so a kill site visibly greens up
	 *  over a few generations without one death making a jungle. */
	private static final double ROT_FERTILITY = 0.10;

	@Override
	protected void onCorpseExpired() {
		// Nutrient closure (WORLDGEN-RESEARCH.md): a body that rots where it
		// fell feeds the ground. The bump scales with body mass and bleeds
		// into the four neighbouring tiles, so death sites become meadows —
		// grass feeds grazers feed predators feed grass. Items are inanimate
		// and return nothing.
		if (this instanceof Item) {
			return;
		}
		double bump = ROT_FERTILITY * bodyMass();
		enrich(X, Y, Z, bump);
		enrich(X + 1, Y, Z, bump * 0.4);
		enrich(X - 1, Y, Z, bump * 0.4);
		enrich(X, Y + 1, Z, bump * 0.4);
		enrich(X, Y - 1, Z, bump * 0.4);
	}

	private void enrich(double x, double y, double z, double amount) {
		if (getWorld() == null || !getWorld().isValid(x, y, z)) {
			return;
		}
		var t = getWorld().getTile(x, y, z);
		t.setFertility(Math.min(1.0, t.getFertility() + amount));
	}

	// ---- render-layer accessors -------------------------------------------
	// The painters (net.hedinger.prototype.render) draw this creature; they
	// read its state through here and never reach into sim internals.

	/** Species hostility index, keyed into the sprite sheets. */
	public int getHostility() {
		return hostile;
	}

	public double getLosRange() {
		return LOS_RANGE;
	}

	public double getLosFov() {
		return LOS_FOV;
	}

	public boolean debugDrawLOS() {
		return drawLOS;
	}

	public boolean debugDrawPing() {
		return drawPing;
	}

	public boolean debugDrawLine() {
		return drawLine;
	}

	public boolean debugDrawTrace() {
		return drawTrace;
	}

	public float getPing() {
		return ping;
	}

	/** Advances the debug ping sweep one frame (render-driven, sim-inert). */
	public void advancePing() {
		ping += 0.3;
		if (ping > LOS_RANGE) {
			ping = -SEARCH_FREQ;
		}
	}

	public double getTargetX() {
		return tX;
	}

	public double getTargetY() {
		return tY;
	}

	public java.util.Collection<NPC> targetsView() {
		return targets.values();
	}

	public java.util.Collection<NPC> focusTargetsView() {
		return focusTargets.values();
	}

	public String getMessage() {
		return message;
	}

	public int getMessageFade() {
		return message_fade;
	}

	public int getMessageFadeMax() {
		return mesage_fade_max;
	}

	/** Ages the floating speech bubble one frame (render-driven, sim-inert). */
	public void fadeMessage() {
		message_fade--;
	}

	@Override
	protected void think() {
		// to be overwritten by other entities
		// ...this is for the generic type:

		// tX = getWorld().getMouseX();
		// tY = getWorld().getMouseY();
		// tZ = getWorld().getMouseZ();

	}

	@Override
	public void kill() {
		recordDeath("unknown"); // fallback tag: real causes were recorded first
		age = -1;
	}

	@Override
	public boolean isFlying() {
		return flying;
	}

	/**
	 * The separation spring: bodies that overlap shove each other apart.
	 *
	 * <p>Two pairs are exempt, and the second is about the air.
	 */
	@Override
	public void collisionCheck() {
		float spring = 0.25f;
		for (NPC npc : targets.values()) {
			// Never shove against something bound to us: a captive/rider we carry, or
			// the host we ride. They move together, so the separation spring would
			// just fight the carry (and stall a hauler pushing against its own load).
			if (npc.getAttachTarget() == this || getAttachTarget() == npc) {
				continue;
			}
			// Nor against something at a different altitude. Flight in this world
			// is not a height — Z is the level a body is on, so a flyer stands in
			// exactly the same cell space as a walker and this spring saw two
			// bodies at one point. The steward's drone barged grazers along the
			// ground it was flying over, and its own quarry away from its emitter.
			//
			// Every other close interaction already asks. A grounded creature
			// cannot seize a flyer out of the air (see grab); biting and mating
			// take flight into account too. The spring was the one that never did.
			if (npc.isFlying() != isFlying()) {
				continue;
			}
			double dx = npc.getX() - getX();
			double dy = npc.getY() - getY();
			if (canTouch(npc)) {
				// The old code went angle = atan2(-dy,-dx) and then
				// cos(angle)*hypot / sin(angle)*hypot -- a transcendental
				// round-trip that exactly reconstructs (-dx, -dy). Push
				// directly away from the neighbour instead.
				dX += -dx * spring;
				dY += -dy * spring;
			}
		}
	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// LOS METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	/**
	 * Whether two bodies are close enough to be in contact.
	 *
	 * <p>A floor is solid to the touch. Standing on a deck you cannot reach the
	 * body one storey under your feet, and it cannot reach you — which the
	 * arithmetic below does not say on its own: it folds the level index into
	 * the distance as though a storey were a tile, so a body directly beneath
	 * reads as 1.0 away, and any pair whose radii sum past that was touching
	 * through the deck plate. The same reading that let sight and a taser cross
	 * a floor.
	 *
	 * <p>The exceptions are the openings — a ramp joining the two floors, or a
	 * hole or drop-shaft under the upper body. A hole is the one place a floor
	 * is not there, so reaching through it is reaching through nothing.
	 *
	 * <p>Nothing in the world exercises this today and it is worth saying so
	 * plainly: entity perception is already filtered to one level, so the
	 * neighbour list this is called against never contains a body from another
	 * floor — measured at zero cross-level calls in six thousand ticks. The
	 * guard is here because the correctness belongs to this method rather than
	 * to a filter in a different class that happens to run first. A caller that
	 * does not have that filter gets the right answer now.
	 */
	protected boolean canTouch(Entity e) {
		if (getLvl() != e.getLvl() && !reachesBetweenFloors(e)) {
			return false;
		}
		// Squared-distance compare: equivalent to distance(e) < minDist for
		// non-negative values, without the per-neighbour sqrt.
		double ddx = e.getX() - X;
		double ddy = e.getY() - Y;
		double ddz = e.getZ() - Z;
		double minDist = e.getSize() / 2 + getSize() / 2;
		return ddx * ddx + ddy * ddy + ddz * ddz < minDist * minDist;
	}

	/** Whether an opening stands between these two bodies' floors, where they
	 *  are: a hole or drop-shaft under the upper one, or either of them on the
	 *  ramp that joins the pair. Adjacent floors only -- an opening reaches one
	 *  storey, and two storeys is a floor with a floor under it. */
	private boolean reachesBetweenFloors(Entity e) {
		if (Math.abs(getLvl() - e.getLvl()) != 1 || getWorld() == null) {
			return false;
		}
		Entity upper = getLvl() > e.getLvl() ? this : e;
		Entity lower = upper == this ? e : this;
		net.hedinger.prototype.engine.Tile over = getWorld().getTile(upper.getX(), upper.getY(), upper.getZ());
		if (over != null && over.isDrop()) {
			return true; // standing over the hole, reaching down through it
		}
		return onRampTo(upper, lower.getLvl()) || onRampTo(lower, upper.getLvl());
	}

	/** Whether {@code b} stands on a ramp whose far end is level {@code other}. */
	private boolean onRampTo(Entity b, int other) {
		net.hedinger.prototype.engine.Tile t = getWorld().getTile(b.getX(), b.getY(), b.getZ());
		if (t == null) {
			return false;
		}
		if (t.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPUP) {
			return b.getLvl() + 1 == other;
		}
		if (t.getType() == net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPDOWN) {
			return b.getLvl() - 1 == other;
		}
		return false;
	}

	protected boolean isInLOS() {
		return getWorld().hasLOS(X, Y, Z, D, tX, tY, tZ, LOS_RANGE, LOS_FOV);
	}

	protected boolean isInLOS(Entity e) {
		if (e == null) {
			return false;
		}

		return getWorld().hasLOS(X, Y, Z, D, e.getX(), e.getY(), e.getZ(), LOS_RANGE, LOS_FOV);
	}

	protected boolean isInLOS(double dist, double fov) {
		return getWorld().hasLOS(X, Y, Z, D, tX, tY, tZ, dist, fov);
	}

	protected boolean isValidMoveDestination() {
		if (!isFlying() && !getWorld().getTile(tX, tY, tZ).isWalkable()) {
			return false;
		}
		if (isFlying() && !getWorld().getTile(tX, tY, tZ).isFlyable()) {
			return false;
		}
		return getWorld().hasLOS(X, Y, Z, D, tX, tY, tZ, 99, Math.PI);
	}

	protected boolean isInLOS(double x, double y, double z) {
		return getWorld().hasLOS(X, Y, Z, D, x, y, z, LOS_RANGE, LOS_FOV);
	}

	protected boolean isInLOS(double x, double y, double z, double dist, double fov) {
		return getWorld().hasLOS(X, Y, Z, D, x, y, z, dist, fov);
	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// MOVEMENT METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	protected void roam(double speed, int turn) {
		boolean bool = isValidMoveDestination();

		if (!bool || tZ != Z) {
			tX = X;
			tY = Y;
			tZ = Z;
			dX = 0;
			dY = 0;
			dZ = 0;
		}

		if (isColliding()) {
			tX = X;
			tY = Y;
			tZ = Z;
		}

		if (distance() < 0.05) {
			double d = 0.5 + Utils.random() * 0.7;
			double a = variation(D, Math.PI * 0.5);
			if (Utils.random() * 4 < 1) {
				a = Utils.random() * 2 * Math.PI;
			}

			tX = X + d * Math.cos(a);
			tY = Y + d * Math.sin(a);
			tZ = Z;

		} else {
			chase(speed, turn);
		}

	}

	protected void roam(double speed, int turn, double direction) {
		boolean bool = isValidMoveDestination();

		if (!bool || tZ != Z) {
			tX = X;
			tY = Y;
			tZ = Z;
			dX = 0;
			dY = 0;
			dZ = 0;
		}

		if (isColliding()) {
			tX = X;
			tY = Y;
			tZ = Z;
		}

		if (distance() < 0.05) {
			double d = 0.5 + Utils.random() * 3;
			double a = variation(direction, Math.PI * 0.25);
			if (Utils.random() * 10 < 1) {
				a = Utils.random() * 2 * Math.PI;
			}

			tX = X + d * Math.cos(a);
			tY = Y + d * Math.sin(a);
			tZ = Z;

		} else {
			chase(speed, turn);
		}

	}

	protected boolean chase(double speed, int turn) {
		if (!isInLOS(tX, tY, tZ, -1, Math.PI)) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		if (Math.abs(dA) < Math.PI * 0.05f) {
			D = angle;
		} else if (dA > 0) {
			D += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			D -= (Math.sqrt(Math.abs(dA)) / turn);
		}

		if (Math.abs(dA) > Math.PI * 0.25) {
			dX = 0;
			dY = 0;
			dZ = 0;
		} else {
			move(speed, D);
		}
		return true;
	}

	protected boolean follow(double speed, int turn, Entity e, double radius) {
		if (e == null) {
			return false;
		}

		if (!isInLOS(e.getX(), e.getY(), e.getZ(), -1, Math.PI)) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		if (dA > 0) {
			D += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			D -= (Math.sqrt(Math.abs(dA)) / turn);
		}

		if (Math.abs(dA) > Math.PI * 0.5) {
			dX = 0;
			dY = 0;
			dZ = 0;
		}
		if (distance(e.getX(), e.getY(), e.getZ()) < radius) {
			roam(speed, turn);
		} else {
			move(speed, D);
		}
		return true;
	}

	protected boolean flee(double speed, int turn, Entity e, double radius) {

		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D + Math.PI;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		double dir = D;

		if (dA > 0) {
			dir += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			dir -= (Math.sqrt(Math.abs(dA)) / turn);
		}

		roam(speed, turn, dir);

		return true;
	}

	protected void turn(double speed, int turn) {
		double angle = Math.atan2(Y - tY, X - tX) + Math.PI;

		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		if (angle > 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}

		double dA = angle - D;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		if (dA > 0) {
			D += (Math.sqrt(Math.abs(dA)) / turn);
		} else if (dA < 0) {
			D -= (Math.sqrt(Math.abs(dA)) / turn);
		}
	}

	int backup_collide = -1;

	protected void backup(double speed) {
		if (backup_collide > 400) {
			backup_collide = -1;
		}

		if (backup_collide == -1) {
			if (D > 2 * Math.PI) {
				D -= 2 * Math.PI;
			}
			if (D < 0) {
				D += 2 * Math.PI;
			}
		} else if (backup_collide == 0) {
			D = Utils.random() * Math.PI * 2;
			backup_collide = 1;
		} else {
			backup_collide++;
		}

		dX = speed * Math.cos(D + Math.PI);
		dY = speed * Math.sin(D + Math.PI);

		dX = variation(dX, dX * 0.1);
		dY = variation(dY, dY * 0.1);

		if (isColliding()) {
			backup_collide = 0;
		}

	}

	protected void move(double speed) {
		move(speed, D);
	}

	protected void move(double speed, double dir) {

		D = dir;

		if (D > 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}

		// Collapse (VITALS.md): a body below its crawl reserve can still crawl —
		// slowly, toward food two tiles away — but nothing more. Clamping at the
		// one movement choke point covers every behaviour and mind alike.
		if (!canExert()) {
			speed = Math.min(speed, this.speed * CRAWL_SPEED);
		}

		dX = speed * Math.cos(D);
		dY = speed * Math.sin(D);

		dX = variation(dX, dX * 0.1);
		dY = variation(dY, dY * 0.1);
	}

	// |///////////////////////////////
	// NAVIGATION METHODS
	// |///////////////////////////////

	protected void generatePath(double x, double y, double z) {
		generatePath(x, y, z, false);
	}

	/** As above, with {@code throughDoors} planning the route as though every
	 *  door on the way were open — for a body that opens them, see
	 *  {@link net.hedinger.prototype.engine.Tile#calcConnected(net.hedinger.prototype.engine.World, boolean, boolean)}. */
	protected void generatePath(double x, double y, double z, boolean throughDoors) {
		path = getWorld().findPath(X, Y, Z, x, y, z, throughDoors, getPixelSize());
		if (path.size() == 0) {
			path = null;
		}
	}

	protected boolean followPath(double speed, int turn) {
		if (path == null) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		if (path.size() == 0) {
			path = null;
			return true;
		}

		if (getWorld().distance(X, Y, Z, tX, tY, tZ) < 0.2) {
			int next = path.pop();
			int c = getWorld().hashCol(next);
			int r = getWorld().hashRow(next);
			int l = getWorld().hashLvl(next);

			tX = c + variation(0.5, 0.2);
			tY = r + variation(0.5, 0.2);
			tZ = l;

			dX = 0;
			dY = 0;
			dZ = 0;
			return true;
		}

		return chase(speed, turn);
	}

	protected boolean followPath2(double speed, int turn) {
		if (path == null) {
			dX = 0;
			dY = 0;
			dZ = 0;
			return false;
		}
		if (path.size() == 0) {
			path = null;
			return false;
		}

		if (getWorld().distance(X, Y, Z, tX, tY, tZ) < 0.5) {
			int next = path.pop();

			int c = getWorld().hashCol(next);
			int r = getWorld().hashRow(next);
			int l = getWorld().hashLvl(next);

			if (path.size() < 2) {
				tX = c + variation(0.5, 0.25);
				tY = r + variation(0.5, 0.25);
			} else {
				tX = c + variation(0.5, 0.005);
				tY = r + variation(0.5, 0.005);
			}
			tZ = l;

			dX = 0;
			dY = 0;
			dZ = 0;
			return true;
		}

		return chase(speed, turn);

	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// TARGET METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	protected boolean lockTarget(NPC target, double variation) {
		if (target == null) {
			return false;
		}

		tX = target.getX() + variation(0, variation);
		tY = target.getY() + variation(0, variation);
		tZ = target.getZ();

		return true;
	}

	protected boolean lockTarget(NPC target) {
		if (target == null) {
			return false;
		}

		tX = target.getX();
		tY = target.getY();
		tZ = target.getZ();

		return true;
	}

	protected NPC getClosestNPC(TreeMap<Double, NPC> list) {
		if (list == null) {
			return null;
		}
		if (list.isEmpty()) {
			return null;
		}

		return list.firstEntry().getValue();
	}

	protected NPC getClosestNPC(TreeMap<Double, NPC> list, int stat) {
		if (list == null) {
			return null;
		}

		for (NPC e : list.values()) {
			if (e != null) {
				if (e.getStatus() == stat) {
					return e;
				}
			}
		}

		return null;
	}

	protected NPC getClosestNPC(TreeMap<Double, NPC> list, int age, boolean older) {
		if (list == null) {
			return null;
		}

		for (NPC e : list.values()) {
			if (e != null) {
				if (older && e.getAge() > age) {
					return e;
				}
				if (!older && e.getAge() < age) {
					return e;
				}
			}
		}

		return null;
	}

	/**
	 * checks to see if target is dead
	 *
	 * @param t
	 *            target entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @return
	 */
	protected boolean killedTarget(NPC t, double range, double fov) {
		if (t == null) {
			return false;
		}

		if (t.isDead()) {
			return true;
		}

		return false;
	}

	/**
	 * checks to see if target is outside seeker LOS
	 *
	 * @param t
	 *            target entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @return
	 */
	protected boolean lostTarget(NPC t, double range, double fov) {
		if (t == null) {
			return false;
		}

		if (!isInLOS(t.getX(), t.getY(), t.getZ(), range, fov)) {
			return true;
		}

		return false;
	}

	/**
	 * checks to see if target is not null, alive, and of valid type
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean validTarget(NPC t, double range, double fov, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		if (t == null) {
			return false;
		}

		if (t.isDead()) {
			return false;
		}

		if (!World.includesType(t.getEntityTypeName(), types) && include) {
			return false;
		}

		if (!World.excludesType(t.getEntityTypeName(), types) && !include) {
			return false;
		}

		return true;
	}

	/**
	 * checks to see if target is not null, alive, and of valid type
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean validTarget(NPC t, double range, double fov, String[] types, boolean include) {
		if (t == null) {
			return false;
		}

		if (t.isDead()) {
			return false;
		}

		if (!World.filterType(t.getEntityTypeName(), types, include)) {
			return false;
		}

		return true;
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 * Uses entity variables for the LOS.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return seeTarget(t, LOS_RANGE, LOS_FOV, types, include);
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, double range, double fov, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return seeTarget(t, range, fov, types, include);
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 * Uses entity variables for the LOS.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, String[] types, boolean include) {
		return seeTarget(t, LOS_RANGE, LOS_FOV, types, include);
	}

	/**
	 * checks to see if target is not null, alive, in LOS, and of valid type.
	 * Uses entity variables for the LOS.
	 *
	 * @param t
	 *            target Entity
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param types
	 *            valid entity types
	 * @return if Entity t is a valid target
	 */
	protected boolean seeTarget(NPC t, double range, double fov, String[] types, boolean include) {
		if (!validTarget(t, range, fov, types, include)) {
			return false;
		}

		if (!isInLOS(t.getX(), t.getY(), t.getZ(), range, fov)) {
			return false;
		}

		return true;
	}

	protected boolean seeTarget(NPC t, double range, double fov) {
		if (t == null) {
			return false;
		}
		if (t.isDead()) {
			return false;
		}

		if (!isInLOS(t.getX(), t.getY(), t.getZ(), range, fov)) {
			return false;
		}

		return true;
	}

	/** Keeps only the nearest k entries of a distance-sorted target map. */
	private TreeMap<Double, NPC> capNearest(TreeMap<Double, NPC> in, int k) {
		if (in == null || in.size() <= k) {
			return in;
		}
		TreeMap<Double, NPC> out = new TreeMap<Double, NPC>();
		for (Double key : in.navigableKeySet()) {
			out.put(key, in.get(key));
			if (out.size() >= k) {
				break;
			}
		}
		return out;
	}

	private TreeMap<Double, NPC> scanTargets(TreeMap<Double, NPC> ts) {
		// Staggered update: instead of each NPC re-scanning its neighbourhood at
		// a random ~1/SEARCH_FREQ chance (which clumps -- many can fire on the
		// same tick), give every NPC a fixed phase from its ID so exactly
		// 1/period of the population does the expensive full scan each tick. Same
		// average refresh rate, evenly spread across ticks. STAGGER lengthens the
		// period to trade perception freshness for speed.
		int period = Math.max(1, SEARCH_FREQ * STAGGER);
		if (((getID() + age) % period) == 0) {
			// Bounded nearest-K gather: cost is O(K), not O(local density).
			return getWorld().searchNearestNPC(X, Y, Z, D, LOS_RANGE, LOS_FOV, getID(), MAX_NEIGHBORS);
		}

		// Revalidate the cached list in place -- no defensive copy needed, the
		// output map is separate and nothing here mutates the source.
		TreeMap<Double, NPC> output = new TreeMap<Double, NPC>();
		if (ts != null) {
			for (NPC e : ts.values()) {
				if (seeTarget(e, LOS_RANGE, LOS_FOV, "", false)) {
					if (isFriendly() && e.isHostile()) {
						if (!isDead() && !e.isDead()) {
							e.mark();
						}
					}
					output.put(distance(e.getX(), e.getY(), e.getZ()), e);
				}
			}
		}

		return capNearest(output, MAX_NEIGHBORS);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED. Uses entity variables for the
	 * Search Frequency and LOS.
	 *
	 * @param ts
	 *            old target list
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param type
	 *            valid entity type
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(TreeMap<Double, NPC> ts, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return getTargets(ts, types, include);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED
	 *
	 * @param ts
	 *            old target list
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param type
	 *            valid entity type
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(int sf, TreeMap<Double, NPC> ts, double range,
			double fov, String type, boolean include) {
		String[] types = new String[1];
		types[0] = type;

		return getTargets(sf, ts, range, fov, types, include);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED. Uses entity variables for the
	 * Search Frequency and LOS.
	 *
	 * @param ts
	 *            old target list
	 * @param type
	 *            valid entity types
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(TreeMap<Double, NPC> ts, String[] types,
			boolean include) {
		return getTargets(SEARCH_FREQ, ts, LOS_RANGE, LOS_FOV, types, include);
	}

	/**
	 * updates, validates and returns a new target list that matches LOS and
	 * Entity type paramters. SF IS ENABLED
	 *
	 * @param sf
	 *            search frequency
	 * @param ts
	 *            old target list
	 * @param range
	 *            range of seeker
	 * @param fov
	 *            field of view of seeker
	 * @param type
	 *            valid entity types
	 * @return updated target list
	 */
	protected TreeMap<Double, NPC> getTargets(int sf, TreeMap<Double, NPC> ts, double range,
			double fov, String[] types, boolean include) {

		// Filters the current perception list (the targets field -- note the ts
		// parameter was historically ignored here) into a fresh map. Iterate the
		// source directly; the old defensive temp copy doubled the boxed-key
		// TreeMap allocations of every think() tick.
		TreeMap<Double, NPC> output = new TreeMap<Double, NPC>();

		for (NPC e : targets.values()) {
			if (isLegalTarget(e, range, fov, types, include)) {
				output.put(distance(e.getX(), e.getY(), e.getZ()), e);
			}
		}

		return output;
	}

	protected boolean isLegalTarget(NPC t, double range, double fov, String[] types, boolean include) {
		if (t == null) {
			return false;
		}
		if (t.isDead()) {
			return false;
		}
		if (!World.filterType(t.getEntityTypeName(), types, include)) {
			return false;
		}

		return true;
	}

	// ======================================================
	// PUBLIC GETTERS AND SETTERS PUBLIC GETTERS AND SETTERS
	// ======================================================

	public int getStatus() {
		return status;
	}

	public Color getColor() {
		return col;
	}

	@Override
	public int getPixelSize() {
		return size;
	}

	/** Whether this body is deliberately operating a fixture this tick --
	 *  what a button (as opposed to a pressure plate) responds to. */
	public boolean wantsInteract() {
		return interactIntent;
	}

	@Override
	public float getSize() {
		return size / (float) ResourceManager.tileSize;
	}

	@Override
	public boolean isHostile() {
		return hostile == 2;
	}

	public boolean isFriendly() {
		return hostile == 0;
	}

	@Override
	public boolean isDetected() {
		return detected > 0;
	}

	public boolean canMate() {
		return false;
	}

	public void eat(int amount) {
		if (isDead() && !isRemoved()) {
			age -= amount;
		}
	}

	/**
	 * Energy in one unit of vegetation. Grass is <b>bulk food</b>: a whole tile
	 * stripped bare is worth well under half of what a body of reference mass is
	 * worth as meat, and it takes a hundred times as long to get. That asymmetry is
	 * the point — it is what makes grazing a full-time occupation and predation an
	 * event, and it is why a herd is spread thin over the map while hunters are few.
	 *
	 * <p>Calibrated against the live world rather than chosen: the crop rate and this
	 * figure multiply into a herbivore's income per tick, so they cannot be set
	 * independently. The floor was originally measured under the satiation-state
	 * mint (60k ticks, crop rate 0.003: below 0.75 predators starved to their
	 * floor, at 0.25 the herd itself stopped breeding); now that energy is
	 * food-backed this figure prices grass for real, and the ecology scenarios —
	 * herd growth, hunter survival, the seeded demo world — are the gate that
	 * re-verifies 0.75 still carries the food chain.
	 */
	public static final double GRASS_ENERGY = 0.75;

	/**
	 * Grazes the tile underfoot: consumes up to {@code demand} vegetation from
	 * the living substrate and returns how much <b>vegetation</b> was actually
	 * eaten (0 on barren ground). This is the herbivore's link to the environment
	 * -- the base of the food chain.
	 *
	 * <p>The return value is grass, not energy: callers measure grazing pressure on
	 * the substrate with it. The conversion into the tank happens here, at
	 * {@link #GRASS_ENERGY} per unit.
	 */
	protected double graze(double demand) {
		World w = getWorld();
		if (w == null || demand <= 0) {
			return 0;
		}
		// A sated body does not strip ground it cannot digest: the bite is
		// bounded by the stomach room left (converted back to grass units).
		double room = stomachRoom() / GRASS_ENERGY;
		double eaten = w.getTile(X, Y, Z).graze(w.getTick(), Math.min(demand, room));
		feed(eaten * GRASS_ENERGY); // grass -> stomach, at grass's poor rate
		return eaten;
	}

	/** Lays pheromone at this creature's feet as a cloud (stigmergic marking):
	 *  reinforces a nearby cloud, or drops a fresh one. */
	protected void depositPheromone(double amount) {
		World w = getWorld();
		if (w != null) {
			w.depositPheromone(X, Y, getLvl(), amount);
		}
	}

	/** Pheromone concentration sensed here (sum of nearby clouds). */
	protected double sensePheromone() {
		World w = getWorld();
		return w == null ? 0 : w.pheromoneAt(X, Y, getLvl());
	}

	/**
	 * Heading toward the strongest pheromone cloud within {@code radius}, for
	 * homing to a nest. Returns {@code NaN} when nothing is in range or this
	 * creature is already at the cloud's centre -- i.e. "you are at the nest".
	 */
	protected double nestDirection(int radius) {
		World w = getWorld();
		return w == null ? Double.NaN : w.pheromoneDirection(X, Y, getLvl(), radius);
	}

	/** Overridden by entities that can breed asexually: a fresh offspring, or null. */
	protected NPC spawnOffspring() {
		return null;
	}

	/** Overridden by entities that can breed sexually: a crossover child of this
	 * entity and a compatible partner, or null. */
	protected NPC spawnOffspring(NPC partner) {
		return null;
	}

	/** Tick of {@code age} the current budding hold began, or -1 when idle. */
	private long breedHoldStart = -1;
	/** The last {@code age} at which budding was attempted, to detect breaks. */
	private long breedLastTry = -1;

	/**
	 * Buds an offspring: reproduction is a <b>held act</b> (VITALS.md §4) — the
	 * caller must keep asking, tick after tick, for {@link #BREED_HOLD_TICKS}
	 * before the child arrives; breaking off (fleeing, doing anything else)
	 * resets the hold, and the energy cost is paid on completion, not intent.
	 * Gated on genuine surplus: energy above the threshold AND both needs low
	 * AND health sound — a parched, starving or wounded body does not bud.
	 * Returns true only on the tick a child is actually born.
	 */
	protected boolean tryReproduce() {
		if (!surplusForBreeding()) {
			breedHoldStart = -1;
			return false;
		}
		if (breedHoldStart < 0 || age - breedLastTry > 1) {
			breedHoldStart = age; // a fresh commitment (or a broken one, restarted)
		}
		breedLastTry = age;
		if (age - breedHoldStart < BREED_HOLD_TICKS) {
			return false; // still committing
		}
		NPC child = spawnOffspring();
		if (child == null) {
			return false;
		}
		energy -= reproCost;
		reproCooldown = reproCooldownTicks();
		breedHoldStart = -1;
		getWorld().spawnEntity(child);
		return true;
	}

	/**
	 * The surplus gate every path to reproduction shares: metabolic, alive, off
	 * cooldown, energy banked past the threshold, both needs low, and health
	 * sound. Breeding is a surplus signal across all four books, not an energy
	 * checkout (VITALS.md §6).
	 */
	protected boolean surplusForBreeding() {
		return metabolic && !isDead() && reproCooldown == 0
				&& energy >= reproThreshold
				&& hunger < NEED_LOW && thirst < NEED_LOW && health >= 60;
	}

	/**
	 * Ticks between births, scaled with the body: half the childhood the
	 * offspring itself will spend growing, so big slow-growing bodies are also
	 * slow breeders and the whole life cycle stays in proportion — derived from
	 * the same two growth constants rather than a third magic number.
	 */
	protected int reproCooldownTicks() {
		double adult = adultSize > 0 ? adultSize : (size > 0 ? size : REF_SIZE);
		return Math.max(REPRO_COOLDOWN, growthTicks(adult) / 2);
	}

	/**
	 * Ready to take part in reproduction this tick: a genomed body passing the
	 * shared surplus gate. What budding and mating both require.
	 */
	protected boolean fertile() {
		return surplusForBreeding() && genome != null;
	}

	/**
	 * Whether this entity and a partner can produce sexual offspring right now:
	 * both fertile and genome-compatible above the mate threshold. Compatibility
	 * is mutual -- each must find the other similar enough (marker-based, the same
	 * recognition {@link Genome#similarityTo} drives mate choice in {@code react})
	 * -- so a pair only breeds when both would choose to.
	 */
	public boolean canMateWith(NPC other) {
		if (other == null || other == this || !fertile() || !other.fertile()) {
			return false;
		}
		if (!isOrganic() || !other.isOrganic()) {
			return false; // machinery does not breed, and nothing breeds with it
		}
		double sim = genome.similarityTo(other.genome);
		return sim >= genome.mateThreshold && sim >= other.genome.mateThreshold;
	}

	/**
	 * Sexual reproduction with a chosen, compatible partner: spawns a crossover
	 * child (each gene drawn from one parent, then mutated) and charges BOTH
	 * parents {@code reproCost} and a cooldown. Putting the partner on cooldown
	 * here means that when it is stepped later this same tick it will not breed
	 * again -- one child per pair per encounter, regardless of stepping order.
	 * Returns true if a child was produced.
	 */
	protected boolean reproduceWith(NPC partner) {
		if (!canMateWith(partner)) {
			return false;
		}
		NPC child = spawnOffspring(partner);
		if (child == null) {
			return false;
		}
		energy -= reproCost;
		partner.energy -= partner.reproCost;
		reproCooldown = reproCooldownTicks();
		partner.reproCooldown = partner.reproCooldownTicks();
		getWorld().spawnEntity(child);
		return true;
	}

	public boolean grab(Entity ent) {

		double distance = distance(ent);
		double minDist = ent.getSize() / 2 + getSize() / 2;

		if (distance > minDist) {
			return false;
		}

		if (ent.getSize() > getSize()) {
			return false;
		}
		if (ent.isFlying() && !isFlying()) {
			return false; // a grounded creature can't seize a flyer out of the air
		}
		if (ent.getCarriedLoad() > 0) {
			return false; // can't seize something already carrying others
		}
		D = Math.atan2(-Y + ent.getY(), -X + ent.getX());
		if (ent.attachToTarget(this)) {
			ent.setGrabbed(true);
			grabbing = ent;
			return true;
		}
		return false;
	}

	public boolean drop() {
		if (grabbing == null) {
			return false;
		}

		grabbing.setGrabbed(false);
		grabbing.detach();
		grabbing = null;

		return true;
	}

	/**
	 * Voluntarily latches onto a <i>larger</i> host in reach and rides it (the
	 * inverse of {@link #grab}: here this creature is the one that moves onto the
	 * other). Refuses a host that is not bigger, out of reach, or if already
	 * attached. The rider's position is then slaved to the host until it
	 * {@link #detach() lets go}.
	 */
	public boolean attachTo(Entity host) {
		if (host == null || getAttachTarget() != null) {
			return false;
		}
		if (attachCooldown > 0) {
			return false; // just bucked off -- can't grab back on yet
		}
		if (getCarriedLoad() > 0 || grabbing != null) {
			return false; // can't be carried while carrying (no carry-and-be-carried)
		}
		// Boarding reach, matching the margin every other close interaction gets
		// (biting, mating, grabbing all allow half a tile beyond touching). Attach
		// was the only one demanding dead-centre contact: two ordinary bodies had
		// to come within about a tenth of a tile, which the collision spring pushing
		// them apart made almost impossible to hit on purpose. A creature that WANTS
		// to climb aboard can now actually manage it.
		double dist = distance(host);
		double minDist = host.getSize() / 2 + getSize() / 2 + ATTACH_REACH;
		if (dist > minDist) {
			return false;
		}
		if (host.getSize() <= getSize()) {
			return false; // only ride something larger than yourself
		}
		return attachToTarget(host);
	}

	/**
	 * draws a text over Entity that will fade out for a given amount of frames
	 *
	 * @param msg
	 *            the message that will be drawn (less than
	 * @param fade
	 *            how long the message will take to fade out
	 */
	public void say(String msg, int fade) {
		if (msg == null) {
			return;
		}
		if (msg.trim().isEmpty()) {
			return;
		}
		if (fade < 0) {
			return;
		}

		message = msg.trim();
		mesage_fade_max = fade;
		message_fade = fade;
	}

	protected double distance() {
		return distance(tX, tY, tZ);
	}

	protected double distanceTarget(double tx, double ty, double tz) {
		return getWorld().distance(X, Y, Z, tx, ty, tz);
	}

	public void mark() {
		detected = 20;
	}

	@Override
	public void unmark() {
		if (detected > 0) {
			detected--;
		}
	}

	@Override
	public void select() {
		selected = true;
	}

	public final static int STATUS_SLEEP = 0;
	public final static int STATUS_IDLE = 1;
	public final static int STATUS_ALERT = 2;
	public final static int STATUS_THREAT = 3;

	public abstract String getNpcTypeName();

	@Override
	public final String getEntityTypeName() {
		return "NPC." + getNpcTypeName();
	}

}
