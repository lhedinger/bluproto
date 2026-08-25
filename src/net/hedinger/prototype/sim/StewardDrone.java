package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.simtest.TestNPC;

/**
 * The warden's hands. A single flying machine parked on a charge dock in the
 * buried installation, asleep until the {@link WorldSteward} flags a cohort for
 * culling — then it undocks, flies out through the base's doors, hunts down as
 * many of that cohort as the order calls for, and comes home to its berth.
 *
 * <p>It exists to give the ceiling a body. The steward's cap was always
 * enforced by deletion: correct, cheap, and completely invisible — the one
 * moment the warden touches the world looked exactly like nothing happening,
 * and a population that hit its ceiling simply stopped growing for reasons no
 * observer could see. The drone changes nothing about <em>when</em> the world
 * is thinned and everything about whether that is legible. It also gives the
 * intervention a cost the deletion never had: distance. The drone has to get
 * there, which means a cull takes time, happens somewhere in particular, and
 * can be watched.
 *
 * <p>It is a machine, and the sim is told so once, through
 * {@link NPC#isOrganic()}. That single answer drops it out of the prey channel,
 * the parasite's host search and the mating test together — no hunter will ever
 * chase it, no parasite will ever ride it, nothing will try to breed with it.
 * Creatures still <em>see</em> it: it is big, so it reads on the threat channel
 * exactly as any large body does, and a herd scattering out of its way is the
 * point rather than a side effect.
 *
 * <h2>What it is not</h2>
 *
 * <p>Not a predator. It does not eat, does not hunger, keeps none of the four
 * books, never breeds, never tires and cannot be killed — every one of which is
 * deliberate, because a machine that could starve or be brought down would make
 * the world's population ceiling contingent on the machine's own survival. The
 * ceiling is a promise the steward keeps; the drone is only how it is kept
 * where anyone can see. When it cannot keep it, the steward's own backstop
 * still deletes (see {@link WorldSteward}).
 *
 * <p>Not a mind either. It has no genome and no brain — it is scripted, like
 * the world-generator's terrain and unlike the creatures. Nothing here is meant
 * to evolve.
 */
public final class StewardDrone extends NPC {

	/** Body radius in pixels, on the same scale as {@link net.hedinger.prototype.entities.Genome}'s
	 *  {@code SIZE_MIN..SIZE_MAX} (4..20). Deliberately near the top: the drone
	 *  should read as bigger than almost anything alive, so it registers as a
	 *  threat to what it is coming for, and a body fleeing a machine it cannot
	 *  fight is a better picture than one ignoring it. */
	private static final int CHASSIS = 16;

	/**
	 * Cruise speed in tiles/tick — a patrol pace, not a pursuit one.
	 *
	 * <p>Below {@code Genome.SPEED_MAX} (0.3), so a fast body can outrun the
	 * drone in a straight line. That is a real concession and worth naming: the
	 * machine no longer wins every chase on speed. What keeps a cull finishing
	 * anyway is that it does not need to. Sustaining a sprint costs energy as
	 * {@code mass · v²} and no creature holds top speed for long, the drone
	 * never tires, it navigates while its quarry only steers, and it picks the
	 * nearest cullable body rather than committing to one — so an animal that
	 * outruns it has escaped this approach, not the cull.
	 *
	 * <p>Halved from 0.32, where the drone crossed the map in fourteen seconds
	 * and read as something teleporting between kills. Travel is most of the
	 * time a cull takes, so this is the knob that decides whether the
	 * intervention is a thing you watch happen or a thing you notice afterwards.
	 *
	 * <p>Eased a further quarter to 0.12 — four tiles a second, some thirty-eight
	 * end to end — because at 0.16 it still read as hurrying. Each step down has
	 * made the cull more of an event and less of a fact you find out about.
	 *
	 * <p>The price is paid by the largest cohort, and it is worth knowing before
	 * anyone changes this number back. Measured over 16k ticks of the seeded
	 * world, halving the speed cut the cull rate from about 0.05 bodies a tick
	 * to 0.017 — and at that rate the drone can no longer finish a herbivore
	 * cull. The herd settles at 167-174 against a ceiling of 160, which is to
	 * say against the {@code BACKSTOP} rather than against the drone, and the
	 * order never clears. Easing to 0.12 only deepens that. So for the big
	 * cohort the visible machine now accompanies the control rather than being
	 * it; the small cohorts (predators, parasites) it still services to the
	 * full 70% target.
	 *
	 * <p>That is a deliberate trade of throughput for legibility, not an
	 * oversight. If the throughput is wanted back without giving up the pace,
	 * the knob to reach for is {@code CHARGE_TICKS} or {@code BACKSTOP} — not
	 * this one, which is what makes the drone watchable.
	 */
	private static final double CRUISE = 0.12;

	/** Steering divisor for {@link #steer} — lower turns harder. A machine
	 *  pivots about its own axis, so it out-turns anything with legs. */
	private static final int TURN = 3;

	/** Ticks between route rethinks. The quarry is walking while the drone
	 *  flies, so a route computed once goes stale; re-running A* every tick for
	 *  a target a fraction of a tile further on is most of a tick's budget
	 *  spent to learn nothing. */
	private static final int REPATH = 24;

	/** Extra reach beyond the two bodies' radii at which the zap connects,
	 *  matching {@code TestNPC}'s bite. The drone kills at contact, not at
	 *  range: it has to arrive. */
	private static final double STRIKE_REACH = 0.5;

	/** Ticks the emitter spends charging on a held target before it fires.
	 *  Short, but not nothing — it is what makes a kill an event with a
	 *  beginning rather than a body blinking out on contact. */
	private static final int CHARGE_TICKS = 8;

	/**
	 * How far through decay the remnant of a zapped creature arrives: nine
	 * tenths gone, on the tick it dies.
	 *
	 * <p>The zap does not so much kill an animal as take most of it away, and
	 * what is left on the ground is a remnant rather than a carcass. Saying so
	 * as decay rather than as a special kind of death means every system
	 * downstream needs no special case: a scavenger's carrion score already
	 * discounts by exactly this number and will walk past it, the remnant
	 * clears on the ordinary schedule, and the tenth that is left still feeds
	 * the ground when it goes. A cull returns almost nothing to the world,
	 * which is the honest reading of a body that was vaporised.
	 */
	private static final double VAPORISED = 0.9;

	/** How far the drone's transponder reaches, in tiles. Any door this close
	 *  on its own level is held open — see {@link #openTheWay}. */
	private static final double TRANSPONDER = 6.0;

	/** How near the berth counts as docked. Within this the drone stops dead
	 *  and is on standby; the dock is one tile, so this is "over the pad". */
	private static final double BERTHED = 0.4;

	private final CullOrders orders;
	private final double dockX, dockY, dockZ;

	/** Consecutive ticks the router has failed to find a way to the current
	 *  quarry before the drone writes it off. Not one: a route can fail for a
	 *  tick because a door is mid-slide or the quarry is stepping over a tile
	 *  boundary, and giving up on that would be giving up constantly. */
	private static final int GIVE_UP = 3;

	/** How many claims the gather below expects to find -- the size of a rank,
	 *  and only a starting capacity: a world with more drones than this still
	 *  works, it just grows the list once. */
	private static final int DRONE_CLAIMS = 4;

	/** The body being killed right now, held across ticks so the charge is
	 *  spent on one animal rather than restarted on whatever drifts nearest. */
	private NPC quarry = null;
	private int charge = 0;
	private int repathIn = 0;
	private int lostRoute = 0;
	/** Which cohort the standing order last named, so the write-offs below can
	 *  be thrown away when the drone is sent after something else. */
	private String servingRole = null;
	/**
	 * Bodies this drone has failed to find a way to and has stopped trying for,
	 * by entity id.
	 *
	 * <p>Without it one unreachable animal ends the cull: the drone picks the
	 * nearest cullable body, cannot route to it, holds station, and picks the
	 * same one again next tick — for as long as that animal lives, while the
	 * rest of the cohort breeds on around it. Measured, exactly that: a
	 * ceiling of 160 sat pinned at the backstop for three thousand ticks with
	 * the drone hovering over one creature it could not reach.
	 *
	 * <p>What is out of reach is worth knowing rather than fixing. A body in a
	 * crawl duct or down in a shard bed is in ground the drone's frame does not
	 * fit — so the base's ducting and the caves' beds are real refuges from the
	 * machine, which is a better fact about the world than a drone that can go
	 * anywhere.
	 */
	private final java.util.Set<Integer> writtenOff = new java.util.HashSet<Integer>();
	/** Where the current route was computed to, so a target that has walked
	 *  off can be noticed without re-running the search to find out. */
	private double routeX, routeY, routeZ;

	/**
	 * A drone berthed at {@code (dockX, dockY, dockZ)} and taking its orders
	 * from {@code orders} — in the seeded world, the {@link WorldSteward}
	 * itself.
	 *
	 * <p>The orders arrive through the narrow {@link CullOrders} interface
	 * rather than as a reference to the steward, because the drone genuinely
	 * needs nothing else from it: two scalars, re-read every tick. That also
	 * makes the drone testable without standing up a whole ecosystem to give
	 * it something to be told.
	 */
	public StewardDrone(double dockX, double dockY, double dockZ, CullOrders orders) {
		super(dockX, dockY, dockZ, 0.0); // heading ctor draws no RNG
		this.orders = orders;
		this.dockX = dockX;
		this.dockY = dockY;
		this.dockZ = dockZ;
		this.size = CHASSIS;
		this.speed = CRUISE;
		this.turnRate = TURN;
		this.flying = true;
		this.hostile = 1; // neither friend nor foe: it is equipment
		// The facility's safety yellow (0xd8b028), the same hazard colour as its
		// own charge dock's keep-clear border: the drone belongs to the buried
		// base, not to the biosphere, and it should read that way against the
		// grass at a glance. This is what the map dot and the minimap use --
		// the painters carry the full palette themselves -- so it wants to be
		// the body's dominant colour and not a detail of it.
		this.col = new java.awt.Color(0xd8b028);
		this.health = 100;
		this.LOS_RANGE = 14;
		this.SEARCH_FREQ = 20;
	}

	@Override
	protected void think() {
		// The transponder runs whenever the drone does, order or no order: it
		// is how the machine gets about its own building, and it is the last
		// thing that should be conditional on the state machine below.
		openTheWay();

		String role = orders == null ? null : orders.cullRole();
		if (role == null) {
			returnToBerth();
			return;
		}
		hunt(role);
	}

	/**
	 * Works the standing order: hold a target, close on it, kill it, pick the
	 * next.
	 *
	 * <p>Nothing here counts. The drone does not know how many it has killed or
	 * how many are left — it kills what it can reach for as long as the order
	 * stands, and the order stands until the steward's own recount says
	 * otherwise. Keeping the score in one place is what stops the two of them
	 * disagreeing about when to stop, which is the failure that empties a
	 * cohort.
	 */
	private void hunt(String role) {
		if (!role.equals(servingRole)) {
			// A new cohort: everything the last one taught it about what was
			// out of reach was about other animals in other places.
			servingRole = role;
			writtenOff.clear();
			quarry = null;
		}
		if (!holdsQuarry(role)) {
			quarry = pickQuarry(role);
			charge = 0;
			lostRoute = 0;
			repathIn = 0;
		}
		if (quarry == null) {
			// Flagged, but nothing eligible left that it has any way to reach.
			// Hold station rather than fly at random: the steward's backstop
			// covers a cull that cannot finish.
			stop();
			return;
		}
		if (inReach(quarry)) {
			zap();
			return;
		}
		charge = 0; // the emitter loses its charge the moment contact breaks
		if (flyTo(quarry.getX(), quarry.getY(), quarry.getZ())) {
			lostRoute = 0;
		} else if (++lostRoute >= GIVE_UP) {
			writtenOff.add(quarry.getID());
			quarry = null;
			lostRoute = 0;
		}
	}

	/** Whether the body being worked on is still worth working on. */
	private boolean holdsQuarry(String role) {
		return quarry != null && !quarry.isDead() && !quarry.isRemoved()
				&& !writtenOff.contains(quarry.getID())
				&& quarry instanceof TestNPC t && WorldSteward.isCullable(t, role);
	}

	/**
	 * The nearest cullable member of the flagged cohort that no other drone is
	 * already working on, by straight-line distance across every level.
	 *
	 * <p>Nearest, not weakest or oldest or most crowded: the drone is a machine
	 * carrying out a headcount, and every animal in the cohort is
	 * interchangeable to it. Choosing on anything else would be the drone
	 * having an opinion about which animals deserve to live, which is a great
	 * deal more design than a ceiling needs.
	 *
	 * <p>Straight-line, though it will fly a route: the point of the choice is
	 * only to head for the near side of the map, and path length to every
	 * candidate would cost an A* per animal per pick.
	 *
	 * <p>Skipping what the rest of the rank holds is what makes four drones
	 * worth more than one. They berth on adjacent pads and take the same
	 * standing order in the same tick, so "nearest to me" is the same animal
	 * for all four of them: measured over twelve thousand ticks of the seeded
	 * world, all four held the identical target on 6962 of the 7721 ticks any
	 * of them held one at all, and on exactly one tick did all four hold
	 * different animals. Three machines flew escort to a fourth doing the work,
	 * and the cull ran at one drone's pace no matter how many pads the wing
	 * had.
	 *
	 * <p>Found by looking at the other drones rather than kept in a register
	 * they all write to, which is the same choice {@code Worlds.findDocks}
	 * makes about pads and for the same reason: a claim that lives on the
	 * claiming machine cannot go stale, cannot leak when a drone is removed
	 * mid-cull, and needs nothing to clean it up. A drone that dropped its
	 * quarry this tick has already released it.
	 *
	 * <p>Still nothing counted. Not duplicating work is not keeping score --
	 * the drone learns which animals are spoken for, never how many are left,
	 * so the steward's recount stays the only thing that says when to stop.
	 */
	private NPC pickQuarry(String role) {
		java.util.List<NPC> taken = claimsOfTheRank();
		NPC best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : getWorld().getEntities()) {
			if (!(e instanceof TestNPC t) || !WorldSteward.isCullable(t, role)
					|| writtenOff.contains(t.getID()) || taken.contains(t)) {
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

	/**
	 * What the rest of the rank is working on: one entry per drone that holds a
	 * body, this one excluded.
	 *
	 * <p>Gathered once per pick rather than asked per candidate. Asking per
	 * candidate is the obvious way to write it and it is a scan of the whole
	 * entity list inside a loop over the whole entity list -- quadratic in the
	 * world's population to answer a question about four machines.
	 *
	 * <p>A list and not a set, compared by identity and not by id: it holds at
	 * most one entry per drone, which is a length no hash is worth paying for.
	 *
	 * <p>The rank ticks one machine at a time, so a drone that picks earlier in
	 * a tick has already published its claim by the time the next one looks --
	 * no locking, no two-phase assignment, and no tick in which two of them
	 * believe they both have it.
	 */
	private java.util.List<NPC> claimsOfTheRank() {
		java.util.List<NPC> taken = new java.util.ArrayList<NPC>(DRONE_CLAIMS);
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof StewardDrone d && d != this && d.quarry != null) {
				taken.add(d.quarry);
			}
		}
		return taken;
	}

	/** Whether the emitter can reach a body from where the drone is: contact,
	 *  scaled by both bodies like every other strike in the sim. */
	private boolean inReach(NPC n) {
		double reach = (getSize() + n.getSize()) / 2.0 + STRIKE_REACH;
		return distance(n.getX(), n.getY(), n.getZ()) <= reach;
	}

	/**
	 * Holds the emitter on the quarry and, once charged, fires: the body dies
	 * at once and what is left of it is already {@link #VAPORISED} gone.
	 *
	 * <p>Health is not whittled down. A predator bites because biting is
	 * eating, and how much it gets out of the animal is the share of the body
	 * the wound represents — that arithmetic is the whole reason predation
	 * takes several bites. The drone eats nothing, so there is no share to
	 * take and nothing for a partial hit to mean; it either has the animal or
	 * it does not.
	 */
	private void zap() {
		stop();
		lockTarget(quarry);
		if (++charge < CHARGE_TICKS) {
			return;
		}
		quarry.damage(Math.max(1, quarry.getHealth()), "culled");
		quarry.recordDeath("culled");
		quarry.kill();
		quarry.decayTo(VAPORISED);
		quarry = null;
		charge = 0;
	}

	/** Flies home and parks. Idle is not the same as parked: the drone holds
	 *  the berth exactly, so a dock reads as occupied and the machine is where
	 *  the next order will find it. */
	private void returnToBerth() {
		quarry = null;
		charge = 0;
		if (distance(dockX, dockY, dockZ) <= BERTHED) {
			// Seat it on the pad. A machine docks onto its contacts rather
			// than drifting to a halt near them, and a drone parked a third of
			// a tile off its berth reads as a drone that missed.
			X = dockX;
			Y = dockY;
			stop();
			// Home and idle: forget what was out of reach on the last sortie.
			// The world has moved since, and a body that was in a duct then is
			// probably out on the grass now.
			writtenOff.clear();
			servingRole = null;
			return;
		}
		flyTo(dockX, dockY, dockZ);
	}

	/**
	 * One tick of travel toward a point anywhere in the world: follow the
	 * current route, recomputing it when it runs out, goes stale, or the
	 * destination has walked off.
	 *
	 * <p>Unlike every creature in this world the drone actually navigates —
	 * {@code findPath} over the tile graph, which crosses levels by ramp, since
	 * a ramp is floor and the pathfinder walks floor. Creatures steer at a
	 * bearing and would press into the rock; the drone is machinery and is
	 * allowed to know the way.
	 *
	 * <p>It plans <em>through</em> doors. A shut door is not an obstacle to
	 * something that opens doors by arriving, so routing round one would be the
	 * machine believing a wall that is not there — and in the buried base,
	 * whose blast door is normally shut and runs on plates too heavy for a
	 * flyer to trip, believing it would mean never leaving the building. So the
	 * route goes straight at the door and the drone flies into it until the
	 * leaves part; the engine refuses the step in the meantime, which is
	 * exactly the right amount of waiting.
	 */
	private boolean flyTo(double gx, double gy, double gz) {
		boolean stale = path == null || --repathIn <= 0
				|| Math.abs(routeX - gx) + Math.abs(routeY - gy) > 1.5 || routeZ != gz;
		if (stale) {
			route(gx, gy, gz);
		}
		if (path == null) {
			stop(); // nowhere to go: sealed in, or a goal sealed off
			return false;
		}
		advance();
		return true;
	}

	/**
	 * Recomputes the route, remembers what it was computed for, and drops the
	 * waypoint it was steering at.
	 *
	 * <p>Dropping the waypoint is the whole point of the last line. A new route
	 * starts from where the body is now, so the point it was flying at belongs
	 * to a plan that no longer exists — and {@link #advance} takes its next
	 * waypoint only on arriving at the current one, so a stale target that
	 * cannot be arrived at is never replaced. Measured, that is a drone wedged
	 * in a rock corner flying at a point three tiles through the wall, holding
	 * its quarry and its heading, for as long as the animal lived. Zeroing the
	 * waypoint here makes the next {@code advance} take one off the fresh path,
	 * which is by construction a step it can actually fly.
	 */
	private void route(double gx, double gy, double gz) {
		generatePath(gx, gy, gz, true); // through doors: they open for this body
		routeX = gx;
		routeY = gy;
		routeZ = gz;
		repathIn = REPATH;
		tX = X;
		tY = Y;
		tZ = Z;
	}

	/**
	 * Walks one step along the route: take the next waypoint once the current
	 * one is underneath, then steer at it.
	 *
	 * <p>Hand-rolled rather than {@code followPath2}, which gates each step on
	 * line of sight to the waypoint. That gate is right for a creature chasing
	 * something it can see and wrong for a machine following a route it
	 * computed: at a ramp the next waypoint is on another level and never in
	 * sight, so the drone would stop dead at the one place the route needs it
	 * to keep going.
	 *
	 * <p>Distance to the waypoint is measured flat, ignoring the level, for the
	 * same reason. A ramp waypoint sits one level up from the body that is
	 * about to fly onto it, and the level only turns over once the body crosses
	 * the ramp's edge — count that pending turnover as a tile of remaining
	 * distance and the waypoint is never reached; treat a level mismatch as
	 * arrival and the whole route is popped in a handful of ticks. Flat
	 * distance is the only reading that matches how a ramp actually works.
	 */
	private void advance() {
		if (Math.hypot(tX - X, tY - Y) < 0.5) {
			if (path.isEmpty()) {
				path = null;
				stop();
				return;
			}
			int next = path.pop();
			tX = getWorld().hashCol(next) + 0.5;
			tY = getWorld().hashRow(next) + 0.5;
			tZ = getWorld().hashLvl(next);
		}
		steer(tX, tY);
	}

	/**
	 * Turns toward a point and moves, with no line-of-sight gate: a machine
	 * pivots on its axis, so a hard turn is taken standing still and the run
	 * is flown once it is pointed the right way.
	 */
	private void steer(double gx, double gy) {
		double angle = Math.atan2(gy - Y, gx - X);
		double dA = angle - D;
		while (dA > Math.PI) {
			dA -= 2 * Math.PI;
		}
		while (dA < -Math.PI) {
			dA += 2 * Math.PI;
		}
		if (Math.abs(dA) < Math.PI * 0.05) {
			D = angle;
		} else {
			D += Math.signum(dA) * Math.sqrt(Math.abs(dA)) / TURN;
		}
		if (Math.abs(dA) > Math.PI * 0.25) {
			stop(); // still coming about
		} else {
			move(speed, D);
		}
	}

	/**
	 * The transponder: every door within {@link #TRANSPONDER} tiles on this
	 * level is asked to stay open, once per tick.
	 *
	 * <p>Doors already have exactly this mechanism — {@link Door#holdOpen()},
	 * a hold refreshed by each press and outlived by a linger — because
	 * several pressure plates wired to one door had to compose. The drone is
	 * one more thing pressing, and it needs no state of its own: stop asking
	 * and the door shuts behind it on its own schedule.
	 *
	 * <p>Proximity rather than a plate because the drone flies, and the base's
	 * plates are weight-driven by design ("flyers pass over unfelt"). The
	 * machine that lives in the building carries the key to it, which is both
	 * the simplest mechanism and the one that reads: doors open as it comes.
	 */
	private void openTheWay() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof Door d && !d.isRemoved() && d.getLvl() == getLvl()
					&& distance(d.getX() + 0.5, d.getY() + 0.5, d.getZ()) <= TRANSPONDER) {
				d.holdOpen();
			}
		}
	}

	/** Kills this tick's movement and lets the heading stand. */
	private void stop() {
		tX = X;
		tY = Y;
		tZ = Z;
		dX = 0;
		dY = 0;
		dZ = 0;
	}

	/** Whether the drone is sitting on its charge dock, whatever it has been
	 *  told. True for the tick an order arrives, before it has moved. */
	public boolean isBerthed() {
		return distance(dockX, dockY, dockZ) <= BERTHED;
	}

	/** Whether the drone is home <em>and</em> idle — berthed with no standing
	 *  order. The resting state, and what a finished cull looks like. */
	public boolean isDocked() {
		return isBerthed() && (orders == null || orders.cullRole() == null);
	}

	/** The berth this drone belongs to, as a world point. */
	public double[] dock() {
		return new double[] { dockX, dockY, dockZ };
	}

	/** The body it is working on right now, or null. */
	public NPC quarry() {
		return quarry;
	}

	/**
	 * Indestructible, by design and not by oversight: damage lands on it and
	 * does nothing.
	 *
	 * <p>The population ceiling is the promise the steward exists to keep, and
	 * the drone is how that promise is kept in view. A machine that could be
	 * worn down would make the ceiling contingent on the machine surviving the
	 * animals it was sent to thin — a cohort large enough to overwhelm the
	 * drone would be a cohort that had earned the right to keep growing, which
	 * is precisely backwards.
	 */
	@Override
	public void damage(int dmg) {
		// armour plate: nothing in this world scratches it
	}

	/** Not food, not a host, not a mate — see {@link NPC#isOrganic()}. */
	@Override
	public boolean isOrganic() {
		return false;
	}

	/** Its own guild, so the census buckets it as neither predator nor prey and
	 *  the steward's cohorts do not count it as one of theirs. */
	@Override
	public String ecoRole() {
		return "drone";
	}

	@Override
	public String getNpcTypeName() {
		return "StewardDrone";
	}

	/** The tile a drone is berthed on. Kept here rather than in the world
	 *  generator because the dock is the drone's, and something has to be able
	 *  to find one in a generated world without being told where it is. */
	public static final Tile.TileType DOCK_TILE = Tile.TileType.TYPE_DOCK;
}
