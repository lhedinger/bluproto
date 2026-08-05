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
	public static final int NUM_SENSORS = 23;
	public static final String[] SENSOR_NAMES = {
			"bias", "energy", "food", "phero", "near_prox", "near_bearing",
			"near_sim", "near_sizeadv", "clock", "blocked",
			"item_prox", "item_bearing", "item_kind",
			"prey_prox", "prey_bearing", "threat_prox", "threat_bearing", "kin_bearing",
			"health", "carried", "whisker_l", "whisker_r", "hazard_ahead" };

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
	/** Vertical intent: seek to climb when &gt; 0.5, to descend when &lt; -0.5, hold
	 *  level otherwise. The body executes it only where a ramp or hole actually
	 *  connects the levels (wired in the hybrid-boundary phase), so it is a wish the
	 *  terrain may or may not grant, never teleportation. */
	public static final int A_VERTICAL = 10;
	public static final int NUM_ACT = 11;
	public static final String[] ACT_NAMES = {
			"turn", "throttle", "eat", "deposit", "attack", "mate", "grab", "attach", "struggle",
			"sprint (retired)", "vertical" };

	private AgentIO() {
	}
}
