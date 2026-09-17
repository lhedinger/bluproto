package net.hedinger.prototype.entities;

import net.hedinger.prototype.engine.Unit;
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
	protected double glycogen = 1.0;
	// Movement has no separate "gear": a creature simply chooses how fast to go
	// (its throttle, or a behaviour's chosen speed) and the movement cost below
	// prices that choice continuously. There is no sprint flag and no surcharge.

	// --- size-scaled energy model --------------------------------------------
	// Everything scales off a body-size factor, normalised so a reference-size
	// creature is 1.0. The reserve (glycogen, "fully fed") grows in proportion to
	// size, while the resting burn grows only with size^0.75 (Kleiber: a big body
	// burns more in absolute terms but less per unit mass). So a big creature's
	// bigger store outlasts a small one's — large animals fast longer between meals
	// — yet still need bigger meals to top up. Anchored so a reference creature
	// lasts a few minutes on full glycogen.
	/**
	 * One day of world time, in ticks: the world's one clock. Every biological
	 * duration in the simulation is a multiple of it, and the energy unit is
	 * defined as one day of resting burn for a reference body — so
	 * {@link #BASAL_RATE} is exactly {@code 1 / DAY}, and any store's size
	 * can be read straight off as "how many days of lying still it buys".
	 *
	 * <p>Scaling this scales the whole tempo of life coherently: a longer day
	 * is a slower world, not a differently-proportioned one. It is the single
	 * knob for that, which is why it is the one constant here expressed in
	 * ticks rather than in days.
	 *
	 * <p>2000 ticks is 60.6 s at 33 ticks/s — a minute, to the eye. That is
	 * also where the metabolism already sat before the clock was named: a full
	 * reference reserve burned down in almost exactly this span, so the energy
	 * unit had been a day of resting burn all along, by accident.
	 *
	 * <p>Build-time, deliberately. The durations derived from it stay
	 * individually tunable at runtime for experiments; moving the day itself is
	 * a decision about the world, and wants a rebuild and a fresh population.
	 */
	@Unit("ticks")
	public static final int DAY = 2000;

	/** Ticks in {@code days} of world time — the one conversion, so a duration
	 *  can be written in the unit biology quotes it in. */
	public static int days(double days) {
		return (int) Math.round(days * DAY);
	}

	/** Body size the energy model is anchored on (size factor 1.0 here). */
	@Unit("px radius")
	public static final double REF_SIZE = 8.0;
	/** Full energy reserve of a reference-size creature ("fully fed"). */
	@Unit("energy at mass 1")
	public static double GLYCOGEN_PER_MASS = 6.0;
	/** Resting energy/tick a reference-size creature burns — one energy unit
	 *  per {@link #DAY}, which is what makes the energy unit a day of lying
	 *  still. Every store can therefore be read as a fasting time straight off
	 *  its energy: {@link #GLYCOGEN_PER_MASS} of 6 is six days of lying still. */
	@Unit("energy/tick at mass 1")
	public static double BASAL_RATE = 1.0 / DAY;
	/** The neutral {@link Genome#metabolism}; a genome at this value is an
	 *  average burner, and mutations above/below it scale efficiency. */
	@Unit("gene value = pace 1")
	public static double META_REF = 0.02;

	// --- capability pricing ---------------------------------------------------
	// Sight, agility and a big brain are all metabolic tissue, and until now they
	// were free: a lineage could evolve keener eyes, a quicker turn or a longer
	// program at no cost, so those genes random-walked with nothing to hold them
	// and a "free" gene is a constant selection cannot move. Each is now a
	// surcharge on the resting burn, priced AT THE MARGIN around a reference
	// genome: a body at the defaults pays exactly the base rate, a keener/quicker/
	// bigger-brained one pays more, and a myopic/sluggish/simple one pays less. So
	// capability becomes a real trade a lineage can spend or save its living on,
	// which is what makes the body's attributes a factor in optimisation rather
	// than a free skin. All three scale with mass^0.75 like the base burn, because
	// they are tissue a bigger body carries more of. The magnitudes are tunable.
	/** Reference sight (the default genome's {@code losRange * losFov}); a body at
	 *  it pays no perception surcharge. */
	@Unit("tiles·rad")
	public static final double REF_SIGHT = 10.0 * (Math.PI * 0.5);
	/** Reference turn rate and program length — the founder defaults, where agility
	 *  and thought cost nothing extra. */
	@Unit("")
	public static final double REF_TURN = 5, REF_BRAIN_LEN = 16;
	/** Energy/tick per unit of sight area beyond the reference, at mass 1. */
	@Unit("energy/tick per tile·rad")
	public static double PERCEPTION_BURN = 5.0e-6;
	/** Energy/tick per turn-rate step beyond the reference, at mass 1. */
	@Unit("energy/tick per step")
	public static double AGILITY_BURN = 5.0e-6;
	/** Energy/tick per brain instruction beyond the reference, at mass 1 — so a
	 *  longer program is a slower thought AND a hungrier body, pricing intelligence
	 *  against food the way everything else in the economy is priced. */
	@Unit("energy/tick per instruction")
	public static double BRAIN_BURN = 3.0e-6;
	/** The floor a body's total burn cannot drop below, as a fraction of its
	 *  size-based base — so a blind, sluggish, brainless lineage still pays to
	 *  exist and the capability savings cannot drive burn to zero. */
	@Unit("of base burn")
	public static double CAPABILITY_FLOOR = 0.5;
	/**
	 * Energy per unit of LEAN tissue ({@code REF_SIZE} = 1), the same figure
	 * both ways. It is what a metabolic body pays per unit of mass it grows,
	 * what a wound that took flesh is mended at, what a parasite is paid for the
	 * flesh it drinks, and what every mouth is paid per unit of carcass it
	 * takes. One figure on both sides of every transfer, so nothing in the
	 * chain can mint energy: a body is worth exactly what was put into it, and
	 * everything anyone eats was bought with grass by someone.
	 *
	 * <p>A body is worth what it weighs, so the meal tracks the quarry rather
	 * than the effort: an animal that takes twice as many bites to bring down is
	 * not twice as nutritious, it is just slower to eat. This replaced a flat
	 * per-bite payout, under which a mouse and an animal the hunter's own size
	 * were worth exactly the same (measured: 2.49 either way).
	 *
	 * <p>Sized so a body is a meal, not a snack: a reference lean mass is three
	 * gut-fills ({@link #GUT_PER_MASS}), so the fresh third of a lean medium corpse
	 * fills one same-size hunter, and a fat one ({@link #FAT_CAP}) half as much
	 * again. The same figure is what growing up costs -- a child buys two
	 * thirds of its lean mass out of what it eats -- which is why grazing and
	 * digestion ({@link #ASSIMILATION_RATE}) are paced to pay for it in minutes.
	 */
	@Unit("energy per mass")
	public static double LEAN_DENSITY = 31.76;
	/**
	 * Energy per unit of FAT, the material a body stores its surplus in and
	 * draws back on when the gut runs dry.
	 *
	 * <p>Its own constant because fat and lean tissue are not the same stuff:
	 * fat carries roughly six times the energy a gram of wet muscle does, muscle
	 * being three quarters water. It is set equal to {@link #LEAN_DENSITY} here
	 * so nothing moves as the name lands; the real ratio waits on the corpse
	 * pools, which today mix a body's fat into its meat and cannot price the
	 * two apart.
	 */
	@Unit("energy per mass")
	public static double FAT_DENSITY = 31.76;

	/*
	 * Assimilation: how much of a meal actually crosses the gut wall.
	 *
	 * Nothing that eats absorbs all of what it swallows. Flesh is close to the
	 * animal doing the eating and goes across almost whole; plant matter is
	 * mostly structure an animal has no enzyme for, and the greater part of it
	 * passes through. That difference, and not a rule about clades, is why a
	 * grazer eats all day and a hunter eats once: the same gutful is worth
	 * twice as much to the one as to the other.
	 *
	 * What does not cross is egesta, and it is not lost. It drops where the
	 * animal fed and fertilises that ground, so nutrients cycle continuously
	 * instead of only at death, and a herd enriches the range it works.
	 */
	/** Share of PLANT energy that crosses the gut wall; the rest is egesta. */
	@Unit("of plant energy swallowed")
	public static double PLANT_ASSIMILATION = 0.40;
	/** Share of FLESH energy that crosses the gut wall; the rest is egesta. */
	@Unit("of flesh energy swallowed")
	public static double FLESH_ASSIMILATION = 0.85;
	/**
	 * Fertility the ground gets back per unit of food energy that passed
	 * through an animal without being absorbed.
	 *
	 * <p>Matter is worth the same to the ground however it arrives there: a
	 * body that rots returns {@code ROT_FERTILITY} (0.10) per unit of mass, and
	 * a unit of mass is {@link #LEAN_DENSITY} (27) of energy, so a unit of
	 * energy is 0.0037 either way. Written out rather than derived because
	 * either constant can be tuned at runtime and the ground's price should not
	 * move when they are.
	 */
	@Unit("fertility per energy")
	public static double EGESTA_FERTILITY = 0.0037;

	// --- growth: born small, grow into the genome's body ----------------------
	/** Fraction of its adult body a creature is born at. */
	@Unit("of adult size")
	public static double BIRTH_SIZE_FRACTION = 0.35;
	/**
	 * Growth in body radius per tick — the well-fed CEILING, not a guarantee. The
	 * distance to travel, and therefore the length of the nominal childhood,
	 * scales with how big the adult body is: a small grazer is grown in seconds,
	 * the largest possible body takes the longest. At {@link Genome#SIZE_MAX}
	 * (20) the climb from birth size is 13 units, or ~1970 ticks — about one
	 * minute at 33 ticks/s, the shortest childhood such a body can have. A
	 * metabolic grower pays {@link #LEAN_DENSITY} for each step's flesh and slows
	 * to what its surplus affords, so real childhoods stretch with scarcity.
	 */
	@Unit("px radius/tick")
	public static double GROWTH_RATE = 0.0066;

	/** Adult body this creature is growing toward; 0 for a body that does not grow. */
	protected double adultSize = 0;
	/** Continuous size while growing — {@link #size} is this, rounded, because the
	 *  body radius itself is an integer. */
	protected double grownSize = 0;

	/**
	 * Starts this body as a juvenile that will grow into {@code adult}. The
	 * size-derived economy (glycogen, burn, collision reach, what can eat it) follows
	 * the CURRENT body, so a juvenile is genuinely small: cheaper to run, but a
	 * smaller reserve and easy prey.
	 */
	protected void beginGrowth(double adult) {
		adultSize = adult;
		grownSize = Math.max(1, adult * BIRTH_SIZE_FRACTION);
		size = (int) Math.round(grownSize);
	}

	/**
	 * Ticks a body of adult radius {@code adult} spends growing up WHEN NOTHING
	 * slows it: the climb from birth size to full size at the {@link #GROWTH_RATE}
	 * ceiling. The NOMINAL childhood — a metabolic grower pays for its flesh and
	 * stretches this when food is short — and still the right figure for the
	 * clocks derived from it (rot, breeding cadence), which want the lineage's
	 * intrinsic scale rather than one individual's luck. Linear in adult size,
	 * and therefore linear in mass, since {@link #leanMass()} is just size
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

	/** Snaps a growing body to its adult size at once — no economy is charged, so
	 *  this is a fixture convenience (a scenario that wants a grown body without
	 *  ticking one up), NOT something the living world does: there, flesh is grown
	 *  a priced step at a time. */
	protected void finishGrowth() {
		if (adultSize > 0) {
			grownSize = adultSize;
			size = (int) Math.round(grownSize);
		}
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
	 * {@code TRANSPORT_COST * mass * v^2} every tick, where {@code v} is the ground
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
	@Unit("energy/tick at mass 1, speed 1")
	public static double TRANSPORT_COST = 0.2;

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
	public double leanMass() {
		double s = size > 0 ? size : REF_SIZE;
		return s / REF_SIZE;
	}

	/**
	 * Everything this body is hauling, in the same units as {@link #leanMass()} so
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
	 * while {@code leanMass()} is normalised to {@link #REF_SIZE} — hence the
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
	 * "Fully fed" glycogen ceiling: the store grows with size, so a big body banks
	 * more and can go longer between meals (but needs more food to top up).
	 *
	 * <p>Anchored on the ADULT body, deliberately. Growth is a physical change —
	 * how much a body burns, how far it reaches, what can eat it — and pinning the
	 * the store to the grown body keeps the whole reproduction economy (born-fed level,
	 * breeding threshold, breeding cost, and a hunter's "sated" line, all of which
	 * are fractions of this) identical to what it was before creatures grew.
	 * A juvenile-sized store would instead have silently re-gated breeding on maturity
	 * and left young hunters unable ever to count as sated.
	 */
	public double glycogenCapacity() {
		return GLYCOGEN_PER_MASS * adultMass();
	}

	/** What this body's lineage asks per offspring — its
	 *  {@link Genome#reproCostFraction} of the (size-scaled) glycogen, so the price
	 *  is a property of the body and the lineage rather than of how full the
	 *  gut happens to be this tick. What BACKS the price is both books (see
	 *  {@link #reserves()}): a parent with empty glycogen and a full gut can
	 *  still afford a child, and pays for it out of the meal. Public because
	 *  birth conservation is audited against it. What is actually handed over
	 *  is {@link #birthPayment()}. */
	public double reproCost() {
		return genome != null ? genome.reproCostFraction * glycogenCapacity() : reproCost;
	}

	/** Food energy still undigested in the gut: the complement of
	 *  {@link #gutRoom()}, in the same units {@link #feed} consumes. Held
	 *  wealth exactly as glycogen is — it is what the mint runs on — so a birth
	 *  draws on it too. */
	public double gutEnergy() {
		return (1 - hunger) * GUT_PER_MASS * adultMass();
	}

	/** Everything this body holds that a child can be made out of: the banked
	 *  glycogen plus the undigested food in the gut, both already in energy
	 *  units. The two books a birth debits, and the two a newborn is opened
	 *  with — which is what makes the transaction conserve. */
	public double reserves() {
		return Math.max(0, glycogen) + gutEnergy();
	}

	/**
	 * What this body actually hands over for a child right now: its lineage's
	 * price, or everything it holds if that is less. The breeding gate is the
	 * line ({@code reproFraction} of glycogen), and the price is a second gene
	 * that can drift above it; a parent whose price is above its line used to
	 * pay the full price out of glycogen that did not hold it, go negative, and
	 * be clamped back to zero next tick -- and its child was endowed from the
	 * whole of it. Measured: a parent holding 3.6 paid 8.1, and 4.5 energy was
	 * minted at the birth. The child is opened from this, so what it is born
	 * holding is exactly what its parents lost.
	 */
	public double birthPayment() {
		return Math.max(0, Math.min(reproCost(), reserves()));
	}

	/**
	 * Takes {@code amount} out of this body's books for a birth and returns
	 * what was actually taken. Drawn proportionally from glycogen and the
	 * gut, so neither book is a loophole: a parent cannot shelter a birth
	 * behind a full gut, and paying does not selectively empty the reserve the
	 * body needs to keep moving. Never takes more than is there.
	 */
	public double payBirth(double amount) {
		double held = reserves();
		if (amount <= 0 || held <= 0) {
			return 0;
		}
		double share = Math.min(1.0, amount / held);
		double fromGlycogen = Math.max(0, glycogen) * share;
		double fromGut = gutEnergy() * share;
		glycogen = Math.max(0, glycogen) - fromGlycogen;
		double gut = GUT_PER_MASS * adultMass();
		if (gut > 0) {
			hunger = Math.min(1.0, hunger + fromGut / gut);
		}
		return fromGlycogen + fromGut;
	}

	/**
	 * Settles a birth: charges the parents and opens the child's books. The
	 * base implementation only charges, for bodies whose young keep no books
	 * of their own; {@code TestNPC} overrides it with the conserving transfer,
	 * where everything the parents lose turns up in the child.
	 */
	protected void settleBirth(NPC child, NPC partner) {
		payBirth(birthPayment());
		double mass = child.getGenome() != null ? birthMass(child.getGenome().size) : 0;
		double rest = mass - payBirthMass(mass);
		if (partner != null) {
			partner.payBirth(partner.birthPayment());
			partner.payBirthMass(rest);
		}
	}
	/** The energy this body must bank before it breeds — its lineage's
	 *  {@link Genome#reproFraction} of glycogen (a probe for the scenario suite). */
	public double reproThreshold() {
		return reproThreshold;
	}

	protected double reproThreshold = 2.0; // energy needed to bud an offspring
	protected double reproCost = 1.0; // energy spent per offspring
	/**
	 * How much of this LIVING body's lean tissue is still on it, 0..1 of
	 * {@link #leanMass()}. A parasite's drain comes off it and is paid the meat
	 * price for exactly that share, and a mended wound buys it back (see the mend
	 * step). A hunter's bite on a live animal takes nothing here: a bite wounds,
	 * and the hunter is paid nothing until the animal is dead. What is left of
	 * this ledger at death is what the corpse is made of -- see {@link #kill}.
	 */
	protected double lean = 1.0;

	/*
	 * A corpse is not all meat. At death the body divides into three pools, in
	 * body-mass units, and every mouth is told which it may draw from:
	 *
	 *  - fresh meat, FRESH_SHARE of the body: the only thing a hunter eats, and
	 *    what a scavenger takes first. It spoils on its own, slowly then fast
	 *    (spoil()), and what spoils uneaten is not lost -- it turns into
	 *  - decayed meat, SCAVENGER_SHARE of the rest at death plus everything that
	 *    spoiled: inedible to a hunter, a scavenger's living. Once the body has
	 *    turned it rots away on its own over the decay clock (rot()), so a late
	 *    scavenger finds less;
	 *  - bones, the remainder: food to nobody, lying there until the clock runs
	 *    out.
	 *
	 * While any fresh meat is left the body is FRESHLY dead and the decay clock
	 * has not started; when it is gone the body is DECAYING on a time-only clock
	 * to the point it dissolves. Bites never move the clock: a fresh bite brings
	 * the turn on sooner (the spoilage rate runs on how much of the fresh pool is
	 * gone), a decayed bite simply leaves less to rot. What was eaten is gone;
	 * everything else -- meat that rotted uneaten, and the bones -- feeds the
	 * ground when the corpse dissolves.
	 */

	/** Share of a body that is fresh meat at death: the hunters' third. */
	@Unit("of body mass")
	public static final double FRESH_SHARE = 1.0 / 3;
	/** Share of the NON-fresh remainder that is decayed meat a scavenger can eat;
	 *  the rest of it is bone. */
	@Unit("of the non-fresh mass")
	public static final double SCAVENGER_SHARE = 0.5;

	/** Fresh meat on this corpse, in body-mass units; 0 for the living. */
	protected double fresh = 0;
	/** Fresh meat the body had the instant it died: the pool the spoilage curve
	 *  runs over. */
	protected double freshFull = 0;
	/** Decayed meat on this corpse, in body-mass units; 0 for the living. */
	protected double decayed = 0;
	/*
	 * How much of each pool is FAT rather than lean tissue. A carcass is a
	 * mixture and the two materials are not worth the same, so a pool has to
	 * remember what it is made of or a mouthful cannot be priced. Every mouthful
	 * comes off a pool in the proportion the pool holds, and spoilage and rot
	 * move both parts together, so the mixture stays what it was and a fat body
	 * is a richer meal all the way down.
	 */
	/** Fat within {@link #fresh}, in body-mass units. */
	protected double freshFat = 0;
	/** Fat within {@link #decayed}, in body-mass units. */
	protected double decayedFat = 0;
	/** Mass mouths have taken off this corpse, in body-mass units -- the one part
	 *  of the body the ground never gets. */
	protected double eaten = 0;
	/** What the body weighed the instant it died, lean mass and fat together, in
	 *  body-mass units: the whole the corpse's pools are shares of. */
	protected double carcassMass = 0;

	/*
	 * Fat is the body's store. A fed body with full glycogen keeps digesting, and
	 * what glycogen cannot take is laid down as mass at FAT_DENSITY; a body whose
	 * gut has run empty draws that mass back into the gut at the same
	 * price, so fat is spent before health is. Both moves run at the body's
	 * digestion rate. Fat is real mass: it is carried (and paid for) on every
	 * step, it is on the carcass for whoever eats it, and it is what a parent
	 * builds a child's body out of. It is the only thing in the economy that can
	 * turn a long fed life into a rich corpse, and a starved one into bones.
	 */
	/** Mass of fat on this body, in body-mass units, on top of the lean mass. */
	protected double fat = 0;
	/** Growth spends only the glycogen above this share of the store: a juvenile that grew
	 *  itself down to the exhaustion floor could not exert, and a young hunter or
	 *  parasite that cannot bite cannot eat its way back up. Growth is bought
	 *  from surplus, never from the last of the reserve. */
	@Unit("of glycogen")
	public static double GROWTH_RESERVE = 0.25;
	/** How much fat a body can carry, as a share of its lean mass. */
	@Unit("of lean mass")
	public static double FAT_CAP = 0.5;
	/** A body lays down fat only while its gut is fuller than this and its
	 *  glycogen is full: fat is what is left over once everything else is paid. */
	@Unit("hunger")
	public static double FAT_STORE_BELOW = 0.25;
	/** A body draws on its fat once its gut is emptier than this, so the
	 *  gut never pegs -- and starvation never bites -- while any fat is left. */
	@Unit("hunger")
	public static double FAT_DRAW_ABOVE = 0.75;

	/** Fat on this body, in body-mass units. */
	public double fat() {
		return fat;
	}

	/** The most fat this body can carry, in body-mass units. */
	public double fatCap() {
		return FAT_CAP * leanMass();
	}

	/** Fat as a share of what the body could carry, 0..1, for the inspector. */
	public double fatLeft() {
		double c = fatCap();
		return c <= 0 ? 0 : Math.max(0, Math.min(1, fat / c));
	}

	/** Mass a newborn of {@code adultSize} px arrives with, in body-mass units,
	 *  with the same floor and rounding {@code beginGrowth} applies -- so the
	 *  matter is priced as it will be weighed. It comes out of its parents' fat:
	 *  a body is built of what its parents stored, not conjured. */
	public static double birthMass(double adultSize) {
		return Math.round(Math.max(1, BIRTH_SIZE_FRACTION * adultSize)) / REF_SIZE;
	}

	/** Fat this body must hold before it will try to breed: half a child of its
	 *  own lean mass, since a pair pools two halves. A budder still needs the whole
	 *  at the moment of birth -- see {@code spawnOffspring}. */
	protected double fatToBreed() {
		double adult = adultSize > 0 ? adultSize : (size > 0 ? size : REF_SIZE);
		return birthMass(adult) / 2;
	}

	/** Takes up to {@code mass} of fat off this body for a child's birth mass
	 *  and returns what was actually taken. Fat only: the lean mass is not for sale,
	 *  so no parent can die of giving birth. */
	public double payBirthMass(double mass) {
		double taken = Math.max(0, Math.min(mass, fat));
		fat -= taken;
		return taken;
	}

	/** Everything a living body weighs: its lean mass plus its fat. On a corpse,
	 *  what it weighed when it died. */
	public double bodyMass() {
		return isDead() ? carcassMass : leanMass() + fat;
	}

	/** What this corpse weighed the instant it died, lean mass and fat together;
	 *  the whole its pools are shares of. The living lean mass plus fat otherwise. */
	public double carcassMass() {
		return bodyMass();
	}

	/** How long a reference-mass body stays fresh, in ticks -- the one tunable.
	 *  About two hours of world time, which is five seconds of watching. A
	 *  heavier body sits fresh longer, a lighter one less,
	 *  because the spoilage rate is set by the amount of fresh meat itself and
	 *  not by the fraction of it: a big carcass has more to turn. */
	@Unit("ticks")
	public static int FRESH_TICKS = days(0.0825); // about two hours
	/** What gets spoilage going on a body with nothing yet spoiled, as a share of
	 *  a reference body's fresh meat: the rate is proportional to what has already
	 *  turned plus this seed, so it starts at a crawl and compounds. */
	private static final double FRESH_SEED = 0.05;
	/** Shape of the rot once the body has turned. The decayed meat still on it is
	 *  {@code 1 - p^ROT_SHAPE} of what it had, p the decay progress, so it holds
	 *  together early and thins out late -- the curve the corpse is drawn
	 *  dissolving on. 1 is linear; higher keeps meat on a decaying body longer.
	 *  The tunable for how long carrion is worth walking to. */
	@Unit("exponent on decay progress")
	public static double ROT_SHAPE = 2.0;

	/** Fresh meat on this corpse, in body-mass units; 0 for the living and for
	 *  a body that has turned. */
	public double freshMeat() {
		return fresh;
	}

	/** Decayed meat on this corpse, in body-mass units; 0 for the living. */
	public double decayedMeat() {
		return decayed;
	}

	/** The bones: the part of a corpse nobody eats, in body-mass units. */
	public double bones() {
		return isDead() ? leanMass() * (1 - FRESH_SHARE) * (1 - SCAVENGER_SHARE) : 0;
	}

	/** Mass mouths have taken off this corpse so far, in body-mass units. */
	public double eatenMass() {
		return eaten;
	}

	/** Meat anybody could still eat off this corpse: fresh plus decayed. */
	public double edibleMass() {
		return fresh + decayed;
	}

	/** What the meat still on this corpse is worth to whatever eats it: the
	 *  lean at lean's density and the fat at fat's. A carcass is a mixture, so
	 *  its worth is not its mass times one number, and a fat body is the better
	 *  meal for it. */
	public double carrionWorth() {
		double stored = freshFat + decayedFat;
		return Math.max(0, edibleMass() - stored) * LEAN_DENSITY + stored * FAT_DENSITY;
	}

	/** What is physically left of this body: on a corpse the meat still on it
	 *  plus the bones; on the living, the whole body, fat and all. */
	public double remainingMass() {
		return isDead() ? edibleMass() + bones() : bodyMass();
	}

	/** Fresh meat as a fraction of the whole body, 0..1, for the inspector. */
	public double freshLeft() {
		return shareOfBody(fresh);
	}

	/** Decayed meat as a fraction of the whole body, 0..1, for the inspector. */
	public double decayedLeft() {
		return shareOfBody(decayed);
	}

	private double shareOfBody(double mass) {
		double m = isDead() ? carcassMass : leanMass();
		return m <= 0 ? 0 : Math.max(0, Math.min(1, mass / m));
	}

	/**
	 * One tick of spoilage. The rate depends on the fresh-meat value alone: it is
	 * proportional to how much of the fresh pool is already gone (spoiled or
	 * eaten), plus a seed, so a just-dead body spoils very slowly and a
	 * half-turned one quickly. Solved for a reference-mass body it reaches zero at
	 * exactly {@link #FRESH_TICKS}; a body of mass m takes
	 * ln((m + seed)/seed) / ln((1 + seed)/seed) times as long -- twice the mass is
	 * about a fifth longer, not twice. What spoils joins the decayed meat.
	 */
	private void spoil() {
		double k = Math.log((1 + FRESH_SEED) / FRESH_SEED) / Math.max(1, FRESH_TICKS);
		double seed = FRESH_SEED * FRESH_SHARE; // a share of a reference body's fresh meat
		double gone = Math.max(0, freshFull - fresh);
		double turned = Math.min(fresh, k * (gone + seed));
		if (fresh - turned < 1e-9) {
			turned = fresh;
		}
		double turnedFat = fresh > 0 ? turned * (freshFat / fresh) : 0;
		fresh -= turned;
		freshFat = Math.max(0, freshFat - turnedFat);
		decayed += turned; // spoiled, not lost: it is the scavengers' now
		decayedFat += turnedFat;
	}

	/**
	 * One tick of rot on a body that has turned, taken as the clock steps from
	 * where it stands to where this tick leaves it: the decayed meat thins along
	 * {@link #ROT_SHAPE} so it reaches nothing exactly as the clock runs out.
	 * Bites do not change the pace, only what is left to rot.
	 */
	private void rot() {
		if (deathspan <= 0) {
			decayed = 0;
			return;
		}
		double p0 = decayProgress();
		double p1 = Math.min(1.0, (-age + 1) / (double) deathspan);
		double left0 = 1 - Math.pow(p0, ROT_SHAPE);
		double left1 = 1 - Math.pow(p1, ROT_SHAPE);
		double thin = left0 <= 1e-9 ? 0 : Math.max(0, left1 / left0);
		decayed *= thin;
		decayedFat *= thin;
		if (decayed < 1e-9) {
			decayed = 0;
			decayedFat = 0;
		}
	}

	/** Decay forced forward by hand -- the steward drone's zap -- is a body that far
	 *  gone, and a body that far gone is not fresh meat: what was fresh has turned,
	 *  and the decayed meat has rotted as far as the clock says. Without this the
	 *  held clock kept a vaporised remnant lying there for as long as it stayed fresh. */
	@Override
	public void decayTo(double progress) {
		super.decayTo(progress);
		if (progress > 0) {
			decayed += fresh;
			decayedFat += freshFat;
			fresh = 0;
			freshFat = 0;
			double thin = Math.max(0, 1 - Math.pow(Math.min(1, progress), ROT_SHAPE));
			decayed *= thin;
			decayedFat *= thin;
		}
	}

	/** Freshly dead: spoiling, and holding the decay clock. Called by the body's
	 *  dead tick; returns whether the clock is still held after this tick. Once
	 *  the body has turned the clock runs, and the meat rots with it. */
	@Override
	protected boolean decayHeld() {
		if (fresh > 0) {
			spoil();
			if (fresh > 0) {
				return true;
			}
		}
		rot();
		return false;
	}

	/** The flesh still on this body as a fraction of the whole: on the living,
	 *  the living ledger; on a corpse, the meat anybody could still eat. */
	public double meatLeft() {
		return isDead() ? shareOfBody(edibleMass()) : lean;
	}

	/**
	 * A parasite's drain on a LIVING body: takes up to {@code share} of its flesh
	 * and returns the share actually taken -- what the drinker is paid for.
	 * Nothing comes off a corpse this way: the dead are eaten through
	 * {@link #eatCarrion}.
	 */
	public double drainFlesh(double share) {
		if (isDead()) {
			return 0;
		}
		double taken = Math.max(0, Math.min(share, lean));
		lean -= taken;
		if (lean <= 1e-9) {
			lean = 0;
		}
		return taken;
	}

	/**
	 * A mouthful off a corpse: takes up to {@code mass} body-mass units, fresh
	 * meat first -- every mouth prefers it -- and then, unless {@code freshOnly},
	 * decayed meat. Returns what came away and what it is worth, which is what
	 * the eater is paid. A hunter eats fresh only; a scavenger both. The decay clock is
	 * not touched: decay is how long the body has lain there, and a corpse eaten
	 * out still lies there, bones and all, until its time is up.
	 */
	public Mouthful eatCarrion(double mass, boolean freshOnly) {
		if (!isDead() || mass <= 0) {
			return Mouthful.NOTHING;
		}
		double taken = Math.min(mass, fresh);
		double fatTaken = fresh > 0 ? taken * (freshFat / fresh) : 0;
		fresh -= taken;
		freshFat = Math.max(0, freshFat - fatTaken);
		if (fresh < 1e-9) {
			fresh = 0;
			freshFat = 0;
		}
		if (!freshOnly) {
			double more = Math.min(mass - taken, decayed);
			double moreFat = decayed > 0 ? more * (decayedFat / decayed) : 0;
			decayed -= more;
			decayedFat = Math.max(0, decayedFat - moreFat);
			if (decayed < 1e-9) {
				decayed = 0;
				decayedFat = 0;
			}
			taken += more;
			fatTaken += moreFat;
		}
		eaten += taken;
		return new Mouthful(taken, (taken - fatTaken) * LEAN_DENSITY + fatTaken * FAT_DENSITY);
	}

	/**
	 * A mouthful off a carcass: the mass that came away, and what that mass is
	 * worth once the fat in it is priced as fat and the lean as lean.
	 *
	 * <p>Two numbers rather than one because a carcass is a mixture: the same
	 * mass off a fat body and off a starved one are not the same meal, and an
	 * eater cannot work the difference out from the mass alone.
	 */
	public record Mouthful(double mass, double energy) {
		/** Nothing came away: a live body, or one already eaten out. */
		public static final Mouthful NOTHING = new Mouthful(0, 0);
	}
	protected int reproCooldown = 0; // ticks until able to reproduce again
	@Unit("ticks")
	public static int REPRO_COOLDOWN = days(0.05); // a floor, about an hour
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
	@Unit("energy/tick per px held")
	public static double GRIP_ENERGY = 0.10;
	/** Fraction of normal metabolism a voluntary rider pays while carried (its
	 *  bonus for hitching a ride instead of walking). */
	@Unit("x metabolism")
	public static final double RIDER_METABOLISM = 0.5;
	/** How far beyond touching a creature can reach to climb aboard a host, in
	 *  tiles — the same margin biting, mating and grabbing already allow. */
	protected static final double ATTACH_REACH = 0.5;
	/** Extra energy a captor burns per tick per unit of (weight x struggle) -- the
	 *  surcharge for hauling an unwilling captive over a consenting passenger. */
	@Unit("energy/tick per weight x struggle")
	public static final double STRUGGLE_CARRIER_COST = 0.35;
	/** Energy a struggling captive burns itself per tick per unit of struggle --
	 *  fighting is exhausting, so consenting conserves the captive's reserves. */
	protected static final double STRUGGLE_SELF_COST = 0.02;
	/** How much heavier a load counts while the carrier is flying: holding a body
	 *  up through the air is far harder work than dragging it over the ground. */
	@Unit("x carry cost")
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
	public void drainGlycogen(double amount) {
		glycogen -= amount;
		if (glycogen < 0) {
			glycogen = 0;
		}
	}

	/** Adds energy; used when an external source feeds this creature, e.g. eating
	 *  a food {@link Item}. */
	public void addGlycogen(double amount) {
		glycogen += amount;
	}

	public double getGlycogen() {
		return glycogen;
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
		double m34 = Math.pow(leanMass(), 0.75);
		double eff = genome != null ? genome.metabolism / META_REF : 1.0;
		double base = BASAL_RATE * m34 * eff;
		if (genome == null) {
			return base;
		}
		// Capability surcharges, priced at the margin around a reference genome
		// (see the constants): keen sight, quick turns and a long brain each add to
		// the burn, a myopic/sluggish/simple body saves — bounded below so nothing
		// ever exists for free. All scale with mass^0.75, like the base.
		double sight = genome.losRange * genome.losFov;
		double perception = PERCEPTION_BURN * m34 * (sight - REF_SIGHT);
		double agility = AGILITY_BURN * m34 * (genome.turnRate - REF_TURN);
		int brainLen = genome.brain != null ? genome.brain.length() : (int) REF_BRAIN_LEN;
		double thought = BRAIN_BURN * m34 * (brainLen - REF_BRAIN_LEN);
		double burn = base + perception + agility + thought;
		return Math.max(burn, base * CAPABILITY_FLOOR);
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
	@Unit("x search period")
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

		// Growth: a juvenile creeps toward its adult body at a fixed ceiling
		// rate, so the bigger the adult the longer the childhood. New flesh is
		// matter, and matter is paid for: a metabolic body buys each step at the
		// meat price an eater would get for it, out of glycogen — which the mint
		// then refills from the gut, so a growing child is hungrier than an
		// adult of the same current size. Growth yields to survival: it slows to
		// what the surplus above the exhaustion floor affords, stretching childhood
		// when food is poor — a starving juvenile stops growing before it stops
		// living, and GROWTH_RATE becomes the well-fed pace rather than a
		// guarantee. Non-metabolic growers keep no books and grow as before.
		// Everything size-derived — glycogen, resting burn, transport cost,
		// collision reach, and whether a hunter can take it — follows the body
		// it has right now, not the one it will have.
		if (age >= 0 && adultSize > 0 && grownSize < adultSize) {
			double step = Math.min(GROWTH_RATE, adultSize - grownSize);
			if (metabolic) {
				double price = LEAN_DENSITY / REF_SIZE; // energy per pixel grown
				double spare = glycogen - Math.max(EXHAUSTION, GROWTH_RESERVE) * glycogenCapacity();
				step = Math.max(0, Math.min(step, spare / price));
				glycogen -= step * price;
			}
			grownSize = Math.min(adultSize, grownSize + step);
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
			double cap = glycogenCapacity();
			double eff = metaEfficiency();
			// The thirst clock: capacity grows with m, the burn only with m^0.75,
			// so a big body's needs rise slower per unit of reserve (Kleiber's
			// fast). Hunger has no clock of its own any more — appetite arrives
			// through the regeneration drain below, so it tracks what the body
			// actually burns; the resting rhythm anchor (appetite returns in
			// twice the time thirst does) survives as the GUT_PER_MASS identity.
			double pace = Math.pow(leanMass(), -0.25) * eff;
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
			double travel = TRANSPORT_COST * travelEfficiency() * (leanMass() + fat + carriedMass()) * lastStep * lastStep;
			// Regeneration: the body converts the gut's contents into energy
			// over time — food never becomes energy directly (feed() fills the
			// gut), and the mint drains the meal it is minted from, 1:1 in
			// energy units, so a body can never bank more than it actually ate.
			// The worse need still governs the rate, and vigor makes health
			// compound with the rest: an unhealthy body is also a listless one.
			double satiation = 1.0 - Math.max(hunger, thirst);
			double vigor = Math.max(0, health) / 100.0;
			double regen = ASSIMILATION_RATE * Math.pow(leanMass(), 0.75) * eff * satiation * vigor;
			double out = base + grip + travel;
			// Only conversion that lands in the books draws down the gut: at
			// full glycogen the mint stops instead of burning the meal for nothing.
			double minted = Math.min(regen, Math.max(0, cap - glycogen + out));
			hunger = Math.min(1.0, hunger + minted / (GUT_PER_MASS * adultMass()));
			glycogen = Math.min(cap, glycogen + regen - out);
			if (glycogen < 0) {
				glycogen = 0; // collapse, never death — health is the only gate
			}
			// Fat, both ways, at the digestion rate and fat's own density. A
			// gut running empty is refilled out of fat before it can peg; a
			// gut that is full against full glycogen is laid down as fat.
			double gut = GUT_PER_MASS * adultMass();
			double digest = ASSIMILATION_RATE * Math.pow(leanMass(), 0.75) * eff * vigor;
			if (fat > 0 && hunger > FAT_DRAW_ABOVE) {
				double back = Math.min(fat * FAT_DENSITY, digest);
				fat -= back / FAT_DENSITY;
				hunger = Math.max(0, hunger - back / gut);
			} else if (hunger < FAT_STORE_BELOW && glycogen >= cap - 1e-9 && fat < fatCap()) {
				double store = Math.min(digest, Math.min((fatCap() - fat) * FAT_DENSITY, (1 - hunger) * gut));
				fat += store / FAT_DENSITY;
				hunger = Math.min(1.0, hunger + store / gut);
			}
			// A collapsed captor cannot hold: restraint is exertion, and below the
			// exhaustion floor there is none to spend — the grip opens and the captive
			// walks free of a captor that is still alive.
			if (!canExert() && grabbing != null) {
				drop();
			}
			// Health: pegged needs erode it with their cause attached, so the
			// corpse still says what killed it; mending happens only under low
			// needs, at the pace-of-life rate (fast burners heal faster and pay
			// for it in appetite). Wounds themselves come from combat, as ever.
			if (hunger >= DEPRIVED && starving() && age % DEPRIVATION_PERIOD == 0) {
				damage(1, "starvation");
			}
			if (thirst >= DEPRIVED && age % DEPRIVATION_PERIOD == DEPRIVATION_PERIOD / 2) {
				damage(1, "thirst"); // offset phase: two pegged needs erode faster
			}
			if (health < 100 && hunger < NEED_LOW && thirst < NEED_LOW
					&& age % Math.max(1, (int) Math.round(MEND_PERIOD / eff)) == 0) {
				// Flesh that was eaten is bought back at the meat price, one
				// hundredth of the body per point, out of glycogen; a wound that
				// took no flesh (starvation, thirst, poison) closes for free. Every
				// bite is paid per point of health, so a body that regrew what was
				// bitten off it for nothing was a flesh mint: a parasite riding a
				// fed host, or a grazer that shook a hunter off, minted meat.
				if (lean < 1.0) {
					double price = LEAN_DENSITY * leanMass() / 100.0;
					if (glycogen >= price) {
						glycogen -= price;
						lean = Math.min(1.0, lean + 0.01);
						health++;
					}
				} else {
					health++;
				}
			}
		}
	}

	// --- the four books (VITALS.md) ------------------------------------------
	/** Ticks for thirst to rise slaked -> parched at the reference body: 4.5
	 *  days of world time, which is ~4.5 min of watching. The faster of the two
	 *  need clocks, and about right for an animal -- death by thirst takes days,
	 *  not weeks. */
	@Unit("ticks")
	public static double THIRST_PERIOD = 4.5 * DAY;
	/** Ticks for hunger to rise sated -> starving in a RESTING reference body:
	 *  twice {@link #THIRST_PERIOD}, so appetite returns in twice the time
	 *  thirst does — the design's one rhythm anchor. No longer a clock of its
	 *  own: hunger rises only as regeneration drains the gut, and this
	 *  period holds because the gut is sized to it (see {@link #GUT_PER_MASS}).
	 *  Exertion adds appetite on top, which the old clock could not price. */
	@Unit("ticks")
	public static double HUNGER_PERIOD = 9.0 * DAY;
	/** Ticks of standing at water for a full drink -- a couple of hours of world
	 *  time, four seconds of watching: drinking is an act with a duration,
	 *  interruptible by simply walking away. */
	@Unit("ticks")
	public static double DRINK_TICKS = days(0.066);
	/** Gut of a reference body, in vegetation-energy units: eating
	 *  {@code GUT_PER_MASS * adultMass()} worth of food takes hunger from starving
	 *  to sated. Not a free knob: it equals {@code BASAL_RATE *
	 *  HUNGER_PERIOD} (0.0005 * 18000), which is what makes the resting burn
	 *  drain a full gut in exactly {@link #HUNGER_PERIOD} ticks — the
	 *  rhythm anchor, preserved by construction now that hunger has no clock
	 *  of its own. Change either factor and this must follow. */
	@Unit("food energy at mass 1")
	public static double GUT_PER_MASS = 9.0;
	/** Energy regenerated per tick by a fed, watered, healthy reference body —
	 *  before the resting burn nets it down. Anchored so an idle ideal body
	 *  refills empty glycogen in roughly a minute and a half. */
	@Unit("energy/tick at mass 1")
	public static double ASSIMILATION_RATE = 0.006;
	/** Fraction of glycogen kept as the exhaustion floor: below it the body is
	 *  collapsed — it can only crawl (see {@link #move}), not act. Collapse is
	 *  recoverable; death is health's decision alone. */
	@Unit("of glycogen")
	public static double EXHAUSTION = 0.05;
	/** Fraction of the genome's top speed a collapsed body can still make. */
	@Unit("of top speed")
	public static double CRAWL_SPEED = 0.25;
	/** A need at or above this is pegged, and starts eroding health. */
	@Unit("need level")
	public static double DEPRIVED = 0.95;
	/** Needs below this count as low: mending and breeding both require it. */
	@Unit("need level")
	public static double NEED_LOW = 0.5;
	/** Ticks between deprivation damage points: a pegged need kills through
	 *  health in ~2.5 days of world time (2.5 min of watching), slow enough that
	 *  rescue by a meal or a shore is a real possibility. */
	@Unit("ticks per hp lost")
	public static int DEPRIVATION_PERIOD = days(0.025); // ~36 min a point, so ~2.5 days to die
	/** Ticks per mended health point (divided by metabolic efficiency): a body
	 *  mends through from nothing in about eight days of world time, which is
	 *  minutes of fed, watered living to watch. */
	@Unit("ticks per hp mended")
	public static int MEND_PERIOD = days(0.08); // ~2 h a point, so ~8 days to heal through
	/** Ticks a budding (asexual) birth must be held for before it completes —
	 *  about two hours of world time, five seconds of sustained commitment to
	 *  watch; breaking off resets the act. */
	@Unit("ticks")
	public static int BREED_HOLD_TICKS = days(0.0825); // about two hours

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
	 * hunger by its share of the mass-scaled gut. Deliberately never
	 * touches energy — satiation regenerates energy over time (VITALS.md), so
	 * a meal's power arrives gradually and an interrupted meal keeps exactly
	 * what was eaten.
	 */
	public void feed(double amount) {
		if (amount > 0) {
			double gut = GUT_PER_MASS * adultMass();
			swallowed += Math.min(amount, hunger * gut); // only what fit counts
			hunger = Math.max(0, hunger - amount / gut);
		}
	}

	/** Food units this body has digested over its lifetime — what actually fit
	 *  in the gut, not what was merely bitten. The measurable end of
	 *  {@link #feed}, for probes and the scenario suite. */
	private double swallowed = 0;

	public double totalSwallowed() {
		return swallowed;
	}

	/**
	 * Swallows {@code energy} worth of food: the {@code assimilation} share of
	 * it crosses the gut wall and the remainder passes straight through, drops
	 * where the animal is standing and fertilises that ground.
	 *
	 * <p>The one door food comes in by, so no eater can absorb a whole meal by
	 * going round the gate, and nothing an animal fails to digest leaves the
	 * world -- it goes back to the tile it was eaten on.
	 */
	protected void ingest(double energy, double assimilation) {
		if (energy <= 0) {
			return;
		}
		feed(energy * assimilation);
		enrich(X, Y, Z, EGESTA_FERTILITY * energy * (1 - assimilation));
	}

	/** Room left in the gut, in the same vegetation-energy units
	 *  {@link #feed} consumes — what a sated body can still swallow (0). */
	protected double gutRoom() {
		return hunger * GUT_PER_MASS * adultMass();
	}

	/**
	 * Whether an empty gut is actually starvation.
	 *
	 * <p>A body is starving only once it has nothing left to live on: no fat to
	 * mobilise and no glycogen past the exhaustion floor. Until then an empty
	 * gut is appetite, and what an animal does about appetite is go and eat.
	 * Starvation is the body beginning to catabolise itself, which is why it is
	 * the one hunger state that erodes health, and why a fat animal walks out
	 * of a famine a lean one dies in.
	 */
	public boolean starving() {
		return fat <= 1e-9 && glycogen <= EXHAUSTION * glycogenCapacity();
	}

	/** Whether the body has the reserve to act (bite, grab, breed, press):
	 *  below the exhaustion floor it is collapsed and can only crawl. */
	public boolean canExert() {
		return !metabolic || glycogen > EXHAUSTION * glycogenCapacity();
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
		// fell feeds the ground. The bump scales with what is left of the body --
		// the bones and whatever meat rotted uneaten; what mouths took is theirs
		// and is not paid to the ground a second time -- and bleeds into the four
		// neighbouring tiles, so death sites become meadows: grass feeds grazers
		// feed predators feed grass. Items are inanimate and return nothing.
		if (this instanceof Item) {
			return;
		}
		double bump = ROT_FERTILITY * Math.max(0, carcassMass - eaten);
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
		if (age >= 0) {
			// Freshly dead: the lean mass divides into its three pools and the decay
			// clock waits on the fresh one. Flesh drained off it alive is not on it.
			// Fat is all meat -- bones do not get fatter -- half fresh, half
			// decayed, so a fed life leaves a richer body for hunter and scavenger
			// alike, and a starved one leaves bones.
			double m = leanMass();
			carcassMass = m + fat;
			freshFull = FRESH_SHARE * m * lean + fat / 2;
			fresh = freshFull;
			freshFat = fat / 2;
			decayed = (1 - FRESH_SHARE) * SCAVENGER_SHARE * m + fat / 2;
			decayedFat = fat / 2;
			fat = 0;
			eaten = 0;
		}
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

		// Collapse (VITALS.md): a body below its exhaustion floor can still crawl —
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
	@Unit("energy per vegetation")
	public static double PLANT_DENSITY = 1.875;

	/**
	 * Grazes the tile underfoot: consumes up to {@code demand} vegetation from
	 * the living substrate and returns how much <b>vegetation</b> was actually
	 * eaten (0 on barren ground). This is the herbivore's link to the environment
	 * -- the base of the food chain.
	 *
	 * <p>The return value is grass, not energy: callers measure grazing pressure on
	 * the substrate with it. The conversion into food energy happens here, at
	 * {@link #PLANT_DENSITY} per unit, of which {@link #PLANT_ASSIMILATION}
	 * crosses the gut wall and the rest fertilises this very tile.
	 */
	protected double graze(double demand) {
		World w = getWorld();
		if (w == null || demand <= 0) {
			return 0;
		}
		// A sated body does not strip ground it cannot digest: the bite is
		// bounded by the gut room left (converted back to grass units, through
		// the share of a mouthful that will actually get there).
		double room = gutRoom() / (PLANT_DENSITY * PLANT_ASSIMILATION);
		double eaten = w.getTile(X, Y, Z).graze(w.getTick(), Math.min(demand, room));
		ingest(eaten * PLANT_DENSITY, PLANT_ASSIMILATION);
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

	/** Writes the child's ancestry into the world's birth registry — AFTER the
	 *  spawn, because ids are assigned there. This is the only tick on which
	 *  "who came from whom" is knowable at all; see World.recordBirth. A spawn
	 *  that failed (id still -1) writes nothing: there is no child to remember. */
	private void noteBirth(NPC child, NPC a, NPC b) {
		if (child.getID() == -1 || child.getGenome() == null) {
			return;
		}
		getWorld().recordBirth(child.getID(),
				a != null ? a.getID() : -1, b != null ? b.getID() : -1,
				child.generation(), Species.of(child.getGenome()).key());
	}

	/** This body's lineage depth: 0 for a world-seeded creature, a child is one
	 *  past its parent. Overridden where breeding actually tracks it. */
	public int generation() {
		return 0;
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
		settleBirth(child, null);
		reproCooldown = reproCooldownTicks();
		breedHoldStart = -1;
		getWorld().spawnEntity(child);
		noteBirth(child, this, null);
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
				&& glycogen >= reproThreshold && fat >= fatToBreed()
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
		settleBirth(child, partner);
		reproCooldown = reproCooldownTicks();
		partner.reproCooldown = partner.reproCooldownTicks();
		getWorld().spawnEntity(child);
		noteBirth(child, this, partner);
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
