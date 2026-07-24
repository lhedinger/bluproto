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
		INERT, ROAM, CHASE, LISTEN, MOVE, GENOME, GRAZE, BREEDER, NEST, MATER, MINDED, HAUL
	}

	/** Vegetation eaten per tick while grazing (>> the tile's regrowth rate). */
	private static final double GRAZE_DEMAND = 0.05;
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
	private double totalIntake = 0;
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

	/** Sets the starting energy (for metabolic fixtures like the breeder/mater). */
	public TestNPC withEnergy(double e) {
		energy = e;
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
		}
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
			steerToward(haulPickX, haulPickY); // walk to where the item is
			return;
		}
		// Phase 2 -- deliver: carry it to the drop-off coordinate and set it down.
		if (grabbing != null) {
			if (distance(haulDestX, haulDestY, Z) < 0.3) {
				drop(); // arrived: set the load down
				haulDropped = true;
			} else {
				steerToward(haulDestX, haulDestY); // carry it to the drop-off
			}
			return;
		}
		// Phase 3 -- return: walk back home, now empty-handed.
		if (!haulReturned) {
			if (distance(haulHomeX, haulHomeY, Z) < 0.3) {
				haulReturned = true; // home again -- stop
			} else {
				steerToward(haulHomeX, haulHomeY);
			}
		}
	}

	/**
	 * Eases toward a remembered world coordinate: it turns its heading toward the
	 * goal at a limited rate (so course changes are smooth arcs, not instant
	 * snaps) and glides forward once roughly facing it -- the same steering
	 * {@link #chase} uses. It deliberately omits chase's line-of-sight gate, which
	 * is meant for pursuing a <em>visible</em> target and (given the engine's
	 * orthogonal-only sight raycast) fails on any diagonal line, stalling a courier
	 * that is merely navigating to a known coordinate.
	 */
	private void steerToward(double gx, double gy) {
		tX = gx;
		tY = gy;
		tZ = Z;
		double angle = Math.atan2(gy - Y, gx - X);
		if (D >= 2 * Math.PI) {
			D -= 2 * Math.PI;
		}
		if (D < 0) {
			D += 2 * Math.PI;
		}
		if (angle < 0) {
			angle += 2 * Math.PI;
		}
		double dA = angle - D;
		if (dA > Math.PI) {
			dA -= 2 * Math.PI;
		}
		if (dA < -Math.PI) {
			dA += 2 * Math.PI;
		}
		if (Math.abs(dA) < Math.PI * 0.05) {
			D = angle; // locked on
		} else if (dA > 0) {
			D += Math.sqrt(Math.abs(dA)) / turn; // ease clockwise
		} else {
			D -= Math.sqrt(Math.abs(dA)) / turn; // ease counter-clockwise
		}
		if (Math.abs(dA) <= Math.PI * 0.25) {
			move(speed, D); // glide forward once roughly aimed (pivot first if not)
		}
	}

	/** The body/mind loop: sense the world into the vector, let the mind decide,
	 * then apply the actuator vector as intent. The mind never sees the world. */
	private void thinkMinded() {
		if (mind == null) {
			return;
		}
		senseInto(sensors);
		mind.think(sensors, actuators);
		actFrom(actuators);
	}

	/** Fills the egocentric, normalized {@link AgentIO} sensor vector. */
	private void senseInto(double[] s) {
		long now = getWorld().getTick();
		s[AgentIO.S_BIAS] = 1.0;
		s[AgentIO.S_ENERGY] = clampUnit(getEnergy() / 4.0);
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
	}

	/** Applies the actuator vector as engine intent (movement + gated actions). */
	private void actFrom(double[] a) {
		double t = clamp(a[AgentIO.A_TURN], -1, 1);
		double throttle = clampUnit(a[AgentIO.A_THROTTLE]);
		D = wrap(D + t * MAX_TURN); // steer
		if (throttle > 0.02) {
			move(throttle * speed, D);
		}
		if (a[AgentIO.A_EAT] > 0.5) {
			totalIntake += graze(GRAZE_DEMAND);
			eatNearestItem(); // also devour a food (or bite a hazard) in reach
		}
		if (a[AgentIO.A_DEPOSIT] > 0.5) {
			depositPheromone(NEST_DEPOSIT * 0.25);
		}
		if (a[AgentIO.A_ATTACK] > 0.5) {
			attackNearest();
			attackNearestItem(); // an object in reach can be smashed too
		}
		if (a[AgentIO.A_MATE] > 0.5) {
			reproduce();
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
	 * (and dies once its health is gone) and the attacker gains a little energy. */
	private void attackNearest() {
		NPC near = nearestPerceived();
		if (near == null || near == this || near.isDead()) {
			return;
		}
		double reach = (getSize() + near.getSize()) / 2.0 + ATTACK_REACH;
		if (distance(near.getX(), near.getY(), near.getZ()) > reach) {
			return;
		}
		near.damage(ATTACK_DAMAGE);
		energy += BITE_ENERGY; // predation feeds the attacker
	}

	/** The reproduce actuator: mates with the nearest perceived compatible partner
	 * in reach (a crossover child inheriting both crossed minds); with no partner
	 * in reach it buds asexually instead. Reproduction is entirely brain-driven --
	 * nothing reproduces unless its mind fires this actuator. */
	private void reproduce() {
		if (!fertile()) {
			return;
		}
		for (NPC n : targets.values()) {
			if (canMateWith(n)) {
				double reach = (getSize() + n.getSize()) / 2.0 + MATE_REACH;
				if (distance(n.getX(), n.getY(), n.getZ()) <= reach) {
					reproduceWith(n); // sexual: crossover child
					return;
				}
			}
		}
		tryReproduce(); // no compatible partner in reach -> bud asexually
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
		double intake = graze(GRAZE_DEMAND);
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
		if (intake < GRAZE_DEMAND * 0.15) {
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
		// Asexual: a mutated copy of this genome, born at the parent's spot. When the
		// genome carries a brain, Genome.child mutates the inherited program too.
		Genome childG = Genome.child(genome, 0.1);
		if (behavior == Behavior.MINDED) {
			return brainedBreeder(X, Y, Z, childG);
		}
		return behavior == Behavior.NEST ? nester(X, Y, Z, childG) : breeder(X, Y, Z, childG);
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
		return behavior == Behavior.MINDED ? brainedBreeder(X, Y, Z, childG) : mater(X, Y, Z, childG);
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
