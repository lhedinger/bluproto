package net.hedinger.prototype.entities;

/**
 * The fixed contract between a creature's <b>body</b> and its <b>mind</b>: a
 * normalized sensor vector the body fills from the world, and an actuator vector
 * the body reads back as intent. A {@link Mind} only ever sees these two arrays
 * -- it never touches the world -- so any decision method (an LGP {@link Brain},
 * a neural net, a hand-written controller) is interchangeable behind it, and any
 * entity type can drive the same minds by filling and applying these slots.
 *
 * <p>Sensors are egocentric and bounded so a policy can be reused across bodies:
 * bearings are relative to the creature's heading, magnitudes are squashed into
 * roughly [-1, 1]. Actuators are intent, not commands -- the engine still has
 * final say over whether a move is legal.
 */
public final class AgentIO {

	// ---- sensors (world -> mind) ------------------------------------------
	/** Constant 1.0, so a policy can synthesize thresholds/biases. */
	public static final int S_BIAS = 0;
	/** Own hunger/reserve as a fraction of this body's OWN capacity, ~0..1: 1.0
	 * means a full tank, near 0 means starving. Scaled to the creature's own
	 * (size-dependent) capacity, not an absolute constant, so the same policy
	 * reads "how full am I" the same way in a small body or a large one — which is
	 * what lets appetite-driven behaviour (hunt when hungry, rest when full)
	 * evolve. 0 when the body keeps no energy tank. */
	public static final int S_ENERGY = 1;
	/** Vegetation on the tile underfoot, 0..1. */
	public static final int S_FOOD = 2;
	/** Pheromone underfoot, squashed 0..1. */
	public static final int S_PHERO = 3;
	/** Proximity of the nearest perceived neighbour: 1/(1+dist), 0 if none. */
	public static final int S_NEAR_PROX = 4;
	/** Relative bearing to that neighbour in the heading frame, -1..1 (of PI). */
	public static final int S_NEAR_BEARING = 5;
	/** Marker similarity to that neighbour, 0..1 (kin vs stranger). */
	public static final int S_NEAR_SIM = 6;
	/** Size advantage over that neighbour, tanh(mine/theirs - 1), -1..1. */
	public static final int S_NEAR_SIZEADV = 7;
	/** A slow oscillator (from tick + id), for RNG-free rhythm/exploration. */
	public static final int S_CLOCK = 8;
	/** 1 if the tile straight ahead (in the heading) is impassable, else 0 -- so a
	 * mind can perceive walls/edges and evolve to steer around them. */
	public static final int S_BLOCKED = 9;
	/** Proximity of the nearest perceived inanimate {@link Item}: 1/(1+dist), 0 if
	 * none -- a dedicated object sense, separate from the living-neighbour channel. */
	public static final int S_ITEM_PROX = 10;
	/** Relative bearing to that item in the heading frame, -1..1 (of PI). */
	public static final int S_ITEM_BEARING = 11;
	/** What kind of item it is: +1 food (eat), -1 hazard (avoid), 0 crate/none. */
	public static final int S_ITEM_KIND = 12;
	/** Proximity of the nearest <i>smaller</i> creature (potential prey) within
	 * sight, 1/(1+dist), 0 if none. A dedicated hunt channel, at full sight range
	 * rather than the short facing-gated {@link #S_NEAR_PROX} set, so a hunter can
	 * lock a target from far enough off to run it down. */
	public static final int S_PREY_PROX = 13;
	/** Relative bearing to that smaller creature in the heading frame, -1..1. */
	public static final int S_PREY_BEARING = 14;
	/** Proximity of the nearest <i>larger</i> creature (potential threat) within
	 * sight, 1/(1+dist), 0 if none. A dedicated flee channel, full sight range. */
	public static final int S_THREAT_PROX = 15;
	/** Relative bearing to that larger creature in the heading frame, -1..1. */
	public static final int S_THREAT_BEARING = 16;
	/** Bearing toward the similarity-weighted centre of nearby kin, -1..1 (of PI);
	 * 0 when no kin are in sight. Lets herding/flocking/packing emerge from a single
	 * "which way are my kind" gradient rather than one nearest neighbour. */
	public static final int S_KIN_BEARING = 17;
	/** Own health, 0..1 (1 = unhurt). Lets a wounded creature behave differently. */
	public static final int S_HEALTH = 18;
	/** Carry state: +1 held as a captive, -1 riding a host voluntarily, 0 free --
	 * so struggle/hold-on timing can be conditioned on being carried. */
	public static final int S_CARRIED = 19;
	/** 1 if the tile 45 deg to the LEFT of the heading is impassable, else 0 -- a
	 * whisker, so a mind can tell which way around an obstacle is clear. */
	public static final int S_WHISKER_L = 20;
	/** 1 if the tile 45 deg to the RIGHT of the heading is impassable, else 0. */
	public static final int S_WHISKER_R = 21;
	/** 1 if the tile straight ahead is a drowning/falling hazard (water or an open
	 * hole) for a non-flyer, else 0 -- the sensed half of the body's "don't walk
	 * into water/off a ledge unless you mean it" reflex. */
	public static final int S_HAZARD_AHEAD = 22;
	/** Proximity of the best forageable tile in sight, 1/(1+dist), 0 if none is
	 * worth going to. Unlike {@link #S_FOOD}, which only answers "am I standing on
	 * grass", this is a <b>place</b>: the body scans the ground it can see and
	 * scores each patch by density against distance, so a rich patch far off loses
	 * to a decent one underfoot. That makes it a gradient a policy can climb rather
	 * than a wall it walks into. */
	public static final int S_FORAGE_PROX = 23;
	/** Relative bearing to that patch in the heading frame, -1..1 (of PI). */
	public static final int S_FORAGE_BEARING = 24;
	/** Proximity of the similarity-weighted centre of nearby kin, 1/(1+dist), 0 when
	 * none are in sight — the magnitude {@link #S_KIN_BEARING} was missing, so a
	 * policy can tell "my kind are far off that way" from "I am in the middle of
	 * them". */
	public static final int S_KIN_PROX = 25;
	/** Proximity of the remembered waypoint, 1/(1+dist), 0 when none is marked or it
	 * lies on another level. */
	public static final int S_WAYPOINT_PROX = 26;
	/** Relative bearing to the remembered waypoint, -1..1 (of PI). */
	public static final int S_WAYPOINT_BEARING = 27;
	/**
	 * How the standing {@link #A_SEEK} intent is going — the only channel that tells
	 * a mind whether what it wanted actually happened. Without it a brain can infer
	 * success only from its tank drifting upward, several thought-cycles late.
	 *
	 * <p>Four values, and deliberately not true/false: an intent here is a latched
	 * <i>level</i>, not a call that returns, so there is no moment at which one
	 * "finishes unsuccessfully". Every failure is already {@link #INTENT_INVALID}
	 * (the guards no longer hold — nothing to seek, or the quarry died) or
	 * {@link #INTENT_PENDING} (still closing). A distinct "false" would need the
	 * body to decide when to give up, and how long to persist is far better left to
	 * selection: a mind has {@link #S_CLOCK} and registers enough to evolve its own
	 * patience.
	 *
	 * <p>{@link #INTENT_DONE} is a per-tick pulse rather than a completion flag —
	 * grazing succeeds on every tick spent standing on grass, and a bite lands on
	 * every tick in reach. "The kill finished" is a different claim from "the bite
	 * landed", and only the latter is unambiguous to report.
	 */
	public static final int S_INTENT = 28;
	public static final int NUM_SENSORS = 29;
	public static final String[] SENSOR_NAMES = {
			"bias", "energy", "food", "phero", "near_prox", "near_bearing",
			"near_sim", "near_sizeadv", "clock", "blocked",
			"item_prox", "item_bearing", "item_kind",
			"prey_prox", "prey_bearing", "threat_prox", "threat_bearing", "kin_bearing",
			"health", "carried", "whisker_l", "whisker_r", "hazard_ahead",
			"forage_prox", "forage_bearing", "kin_prox", "waypoint_prox", "waypoint_bearing",
			"intent" };

	// ---- actuators (mind -> body) -----------------------------------------
	/** Steering, -1..1 (fraction of the max turn rate). */
	public static final int A_TURN = 0;
	/** Throttle, 0..1 (fraction of max speed). */
	public static final int A_THROTTLE = 1;
	/** Graze the tile underfoot when > 0.5. */
	public static final int A_EAT = 2;
	/** Lay pheromone when > 0.5. */
	public static final int A_DEPOSIT = 3;
	/** Attack the nearest neighbour when > 0.5. */
	public static final int A_ATTACK = 4;
	/** Mate with the nearest compatible neighbour when > 0.5. */
	public static final int A_MATE = 5;
	/** Grab the nearest <i>smaller</i> neighbour in reach and carry it while
	 *  &gt; 0.5 (a predatory seize); dropping below releases it. */
	public static final int A_GRAB = 6;
	/** Latch onto the nearest <i>larger</i> neighbour in reach and ride it while
	 *  &gt; 0.5 (a voluntary hitch-hike); dropping below lets go. */
	public static final int A_ATTACH = 7;
	/** Resist an attachment, 0..1 -- role-dependent, since a creature can't carry
	 *  and be carried at once. While grabbed, a captive <b>struggles</b>: it makes
	 *  itself costlier to haul and tires itself. While carrying riders, the same
	 *  signal <b>bucks</b>: it spends energy trying to throw them off (a bigger
	 *  parasite comes loose sooner than a small one that clings tighter). */
	public static final int A_STRUGGLE = 8;
	/**
	 * Retired, and inert: the sprint gear is gone. Movement is now charged as
	 * {@code mass * v^2}, so a creature simply asks for the speed it wants through
	 * {@link #A_THROTTLE} and pays for that choice continuously — a separate
	 * "burst" gear with its own surcharge no longer means anything.
	 *
	 * <p>The slot is kept rather than removed on purpose. Brain instructions store
	 * raw actuator indices, so deleting this one would renumber
	 * {@link #A_VERTICAL} and silently change the meaning of every genome already
	 * saved to a file. A mind may still write here; nothing reads it.
	 */
	public static final int A_SPRINT = 9;
	/**
	 * <b>What kind of ground to look for.</b> Names a <i>property</i> a tile may
	 * have, and the forage/tile channel then reports the nearest tile that has it —
	 * so {@code A_SEEK = ±0.1} stops meaning "grass" and means "the ground I asked
	 * for". Sign is not read here; {@code A_SEEK}'s sign still decides approach or
	 * avoidance.
	 *
	 * <p>Properties, not tile types, on purpose. Types are the wrong axis: there are
	 * a great many, their indices are not aligned to the constant pool, and every
	 * new one would need a band of its own. Every tile already answers this handful
	 * of orthogonal questions, they fit the pool exactly, and new terrain inherits
	 * them for free — when temperature arrives it slots in as another property
	 * rather than a special case.
	 *
	 * <p>Which property is worth wanting is deliberately NOT decided here. A mind
	 * names one and discovers for itself whether it pays; hiding in cover, avoiding
	 * ground that bogs you down and heading for open water are all the same
	 * instruction with a different constant.
	 *
	 * <p>{@code 0}=food (the default, so a mind that never writes this forages),
	 * {@code 0.1}=food, {@code 0.25}=blocks sight, {@code 0.5}=slow going,
	 * {@code 1}=water, {@code 2}=solid, {@code 4}=hazardous underfoot.
	 */
	public static final int A_TILE = A_SPRINT; // the retired sprint slot, put back to work
	/**
	 * Retired, and inert: there is no vertical intent to express. A ramp is floor
	 * that spans two levels, so a body changes level by walking across one — the
	 * ground decides, not the mind, and a creature needs no more sense of height
	 * than it needs to know which tile it is standing on. A hole is the other case,
	 * and it is gravity rather than a route: stand over one and you fall.
	 *
	 * <p>Kept rather than removed for the same reason as {@link #A_SPRINT}: brain
	 * instructions store raw actuator indices, so deleting a slot silently rewrites
	 * every genome already saved to a file. A mind may still write here; nothing
	 * reads it.
	 */
	public static final int A_VERTICAL = 10;
	/**
	 * <b>Intent steering.</b> Names a <i>kind of thing to head for</i> instead of a
	 * turn rate: while this is set and that thing is in sight, the body steers
	 * toward it and {@link #A_TURN} is ignored. The sign flips it — a negative
	 * value steers directly away, so fleeing and chasing cost the same one
	 * instruction. Throttle is still the mind's to choose, and walls are still the
	 * mind's to get around: the body supplies a direction, not a route.
	 *
	 * <p>The magnitude selects the target, on bands centred on the constant pool so
	 * a single {@code SET}+{@code WRITE} can name any of them:
	 * {@code 0}=none, {@code ±0.1}=forage patch, {@code ±0.25}=kin,
	 * {@code ±0.5}=prey, {@code ±1}=threat, {@code ±2}=item, {@code ±4}=waypoint.
	 * Naming something the body cannot currently find puts it into a deterministic
	 * search rather than leaving it planted — wanting what you cannot see is a
	 * reason to go looking.
	 *
	 * <p><b>An intent carries through to the act.</b> Where the goal has one
	 * unambiguous thing to do on arrival, the body does it: seeking a forage patch
	 * grazes, seeking prey bites whatever comes into reach, seeking an item takes
	 * it. So "forage" is one instruction rather than a steering loop plus an eat
	 * gate, which is what makes an intent worth its slot under one instruction per
	 * tick. Goals with no unambiguous terminal act — kin, threat, waypoint — stay
	 * pure steering, and <b>avoidance never acts</b>: running from something is not
	 * a reason to bite it.
	 *
	 * <p><b>Speed is not the intent's business.</b> An intent says where to go and
	 * what to do there; how hard to push stays with the mind through
	 * {@link #A_THROTTLE}. That is deliberate — movement costs the square of speed,
	 * so the throttle is precisely where a lineage spends or saves its living, and
	 * it is worth leaving for selection to price rather than deciding on its behalf.
	 *
	 * @see #seekTarget(double)
	 */
	public static final int A_SEEK = 11;
	/** <b>Remember here.</b> Above 0.5, latches the body's current position as its
	 *  one waypoint; below -0.5, forgets it. Paired with {@code A_SEEK = ±4} and the
	 *  waypoint sensors, this is the whole of spatial memory: a creature can mark a
	 *  good patch, wander off, and come back to it. The coordinate lives in the
	 *  body — a mind that had to hold one in its registers could not do arithmetic
	 *  on it anyway, since the instruction set has no divide and no atan2. */
	public static final int A_MARK = 12;
	/** Above 0.5, the body deliberately operates whatever interactable fixture
	 *  it is at -- today that means pressing an intent-driven switch (a
	 *  button, as opposed to a weight-driven pressure plate). Standing on a
	 *  button does nothing by itself: interaction is a choice, which is the
	 *  entire point. */
	public static final int A_INTERACT = 13;
	public static final int NUM_ACT = 14;
	public static final String[] ACT_NAMES = {
			"turn", "throttle", "eat", "deposit", "attack", "mate", "grab", "attach", "struggle",
			"tile", "vertical (retired)", "seek", "mark", "interact" };

	// ---- seek targets (the decoding of A_SEEK's magnitude) ------------------
	public static final int SEEK_NONE = 0;
	public static final int SEEK_FORAGE = 1;
	public static final int SEEK_KIN = 2;
	public static final int SEEK_PREY = 3;
	public static final int SEEK_THREAT = 4;
	public static final int SEEK_ITEM = 5;
	public static final int SEEK_WAYPOINT = 6;

	// ---- intent status (the values of S_INTENT) ----------------------------
	// ---- tile properties (the values of A_TILE) ----------------------------
	public static final int TILE_FOOD = 0;
	public static final int TILE_COVER = 1;
	public static final int TILE_SLOW = 2;
	public static final int TILE_WATER = 3;
	public static final int TILE_SOLID = 4;
	public static final int TILE_HAZARD = 5;

	/** Decodes {@link #A_TILE} into a property, on the same geometric-midpoint bands
	 *  {@link #seekTarget} uses, so each pool constant sits squarely in its own. */
	public static int tileWanted(double v) {
		double m = Math.abs(v);
		if (m < 0.175) {
			return TILE_FOOD; // includes 0: forage is what a silent mind gets
		}
		if (m < 0.375) {
			return TILE_COVER;
		}
		if (m < 0.75) {
			return TILE_SLOW;
		}
		if (m < 1.5) {
			return TILE_WATER;
		}
		if (m < 3.0) {
			return TILE_SOLID;
		}
		return TILE_HAZARD;
	}

	/** No intent is set; the mind is steering by hand or standing still. */
	public static final double INTENT_IDLE = 0;
	/** The guards failed: nothing of that kind is in reach of the senses. */
	public static final double INTENT_INVALID = -1;
	/** Under way — the goal is in sight and the body is closing on it. */
	public static final double INTENT_PENDING = 0.5;
	/** The terminal act fired this tick: grazed, bit, or took. */
	public static final double INTENT_DONE = 1;

	/**
	 * Decodes {@link #A_SEEK}'s magnitude into a target class. The thresholds sit at
	 * the geometric midpoints between the constant-pool values a brain can actually
	 * emit ({@code 0.1, 0.25, 0.5, 1, 2, 4}), so each pool constant lands squarely
	 * inside its own band with the widest possible margin on both sides. A mutation
	 * that nudges a value therefore usually keeps the same intent, and only a real
	 * jump changes what the creature wants — which is what makes the encoding
	 * evolvable rather than brittle.
	 *
	 * <p>The sign is <i>not</i> read here; it selects toward versus away, and the
	 * body applies it.
	 */
	public static int seekTarget(double v) {
		double m = Math.abs(v);
		if (m < 0.05) {
			return SEEK_NONE;
		}
		if (m < 0.175) {
			return SEEK_FORAGE;
		}
		if (m < 0.375) {
			return SEEK_KIN;
		}
		if (m < 0.75) {
			return SEEK_PREY;
		}
		if (m < 1.5) {
			return SEEK_THREAT;
		}
		if (m < 3.0) {
			return SEEK_ITEM;
		}
		return SEEK_WAYPOINT;
	}

	private AgentIO() {
	}
}
