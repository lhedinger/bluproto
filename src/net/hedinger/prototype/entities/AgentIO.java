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
	/** Own energy, normalized (0 when the body keeps no energy). */
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
	public static final int NUM_SENSORS = 13;
	public static final String[] SENSOR_NAMES = {
			"bias", "energy", "food", "phero", "near_prox", "near_bearing",
			"near_sim", "near_sizeadv", "clock", "blocked",
			"item_prox", "item_bearing", "item_kind" };

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
	public static final int NUM_ACT = 9;
	public static final String[] ACT_NAMES = {
			"turn", "throttle", "eat", "deposit", "attack", "mate", "grab", "attach", "struggle" };

	private AgentIO() {
	}
}
