package net.hedinger.prototype.sim;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.NPC;

/**
 * The facility loader: a slow walking machine that carries crates back to the
 * vault.
 *
 * <p>The other machine in this building flies, kills, and answers to the
 * steward. This one does the boring half of the same job — it is what the
 * facility used to run on. The spine was paved from the mouth to the vault's
 * step because that is how supplies came in, and the crates scattered along it
 * are what nobody put away. The loader puts them away, forever, one at a time.
 *
 * <p><b>It walks.</b> That is the design decision the rest follows from, and it
 * is deliberately the opposite of the drone's. A walking body is dragged by the
 * ground it crosses, so mud and rubble and sludge cost it real time where the
 * drone crosses them at cruise; it is stopped by anything a body is stopped by;
 * and it casts its shadow at one art-pixel like everything else that stands,
 * rather than at eight like the one thing that does not. Nothing here has to
 * enforce any of that — {@link Entity} already applies drag to bodies that are
 * not flying, and it already refuses to walk them through walls. Setting
 * {@code flying = false} buys the whole of it.
 *
 * <p>It is also <b>bigger</b> than the drone (20 against 16), which is not
 * decoration: {@code generatePath} routes on body size, so the loader is
 * planned through gaps that admit it and never sent down the crawl duct the
 * drone can take. The building sorts the two machines by shape without being
 * told to.
 *
 * <p>What it does not do is decide anything. There is no steward for haulage
 * and there does not need to be: a crate lying outside the vault is its own
 * standing order, and the work runs out when the floor is clear. That keeps the
 * loader honest in a way the drone had to be argued into — it cannot
 * over-collect, because the thing it is counting is the thing you can see.
 */
public final class FacilityLoader extends NPC {

	/** Body size in pixels. Larger than the drone's 16, so the pathfinder
	 *  routes it through gaps that fit a loader and never down the duct. */
	private static final int CHASSIS = 20;

	/** Tiles per tick, unladen. Half the drone's cruise, and the drone was
	 *  already eased twice for being too quick to read. A hauler that outruns
	 *  the animals it works around stops looking like plant and starts looking
	 *  like a threat, which is the wrong thing for the machine whose whole job
	 *  is tidying up. */
	private static final double CRAWL = 0.06;

	/** Laden, it is slower still. A crate is a load, and a machine that carries
	 *  one at the same speed it walks empty is carrying nothing you can see. */
	private static final double LADEN = 0.75;

	private static final int TURN = 2; // heavier than the drone: turns wider

	/** Ticks between route recomputes, as the drone's. */
	private static final int REPATH = 24;

	/** When to stop following the route and walk straight at the crate. The
	 *  path ends at the crate's TILE, and a tile centre can be half a tile from
	 *  the thing standing in it — which is twice the distance a grab reaches.
	 *  Inside this range the loader closes by eye. */
	private static final double FINAL_APPROACH = 1.5;

	/** How close the loader gets to the drop point before setting a crate down.
	 *  Must stay comfortably under {@link #STOWED}: a crate set down outside
	 *  that ring is instantly the nearest work again, and the machine spends the
	 *  rest of the world's life picking up its own last delivery. */
	private static final double DROP_AT = 1.2;

	/** A crate within this of the drop point is stowed, and is not work. */
	private static final double STOWED = 2.5;

	/** Every door within this many tiles is asked to stay open. */
	private static final double TRANSPONDER = 6.0;

	/** Close enough to the berth to count as parked. */
	private static final double BERTHED = 0.5;

	/** Consecutive failed routes to one crate before it is written off. */
	private static final int GIVE_UP = 3;

	private final double berthX, berthY, berthZ;
	private final double vaultX, vaultY, vaultZ;

	private Item load = null;
	private Item target = null;
	private int repathIn = 0;
	private int lostRoute = 0;
	private double routeX, routeY, routeZ;

	/** Crates this loader has failed to reach. Without it a crate sealed behind
	 *  a wall is a permanent deadlock: it is always the nearest work, the route
	 *  always fails, and the machine stands in the corridor choosing it again
	 *  every tick. The drone learned the same lesson about unreachable prey. */
	private final java.util.Set<Integer> writtenOff = new java.util.HashSet<Integer>();

	public FacilityLoader(double berthX, double berthY, double berthZ,
			double vaultX, double vaultY, double vaultZ) {
		super(berthX, berthY, berthZ, 0.0); // heading ctor draws no RNG
		this.berthX = berthX;
		this.berthY = berthY;
		this.berthZ = berthZ;
		this.vaultX = vaultX;
		this.vaultY = vaultY;
		this.vaultZ = vaultZ;
		this.size = CHASSIS;
		this.speed = CRAWL;
		this.turnRate = TURN;
		this.flying = false; // everything that matters about this machine
		this.hostile = 1; // neither friend nor foe: it is equipment
		// The same safety yellow as the drone and the charge dock's border:
		// one facility, one livery.
		this.col = new java.awt.Color(0xd8b028);
		this.health = 100;
		this.LOS_RANGE = 10;
		this.SEARCH_FREQ = 30;
	}

	@Override
	protected void think() {
		openTheWay();
		if (load != null) {
			haul();
		} else {
			fetch();
		}
	}

	/** Empty-handed: find the nearest crate that wants stowing and walk to it. */
	private void fetch() {
		if (target == null || target.isRemoved() || stowed(target) || carriedByOther(target)) {
			target = pickCrate();
		}
		if (target == null) {
			returnToBerth();
			return;
		}
		// Everything below this line is about a crate within arm's length or
		// nearly so, and none of it means anything through a floor. The engine's
		// distance counts a level as a tile, so a crate on the storey below
		// reads as one tile off — inside FINAL_APPROACH, which then drops the
		// route and walks the machine at a point on its OWN floor where there is
		// nothing. It never arrives, never grabs, and never writes the crate off
		// either, because a write-off needs a route to fail and it stopped
		// routing. Measured: a loader crept a quarter of a tile in 260 ticks and
		// stood there, with a crate on its own floor eight tiles away that it
		// never went for. A stall, not a slow pick.
		boolean sameFloor = target.getLvl() == getLvl();
		double gap = distance(target);
		if (sameFloor && gap <= reachTo(target)) {
			if (grab(target)) {
				load = target;
				target = null;
				speed = CRAWL * LADEN;
				lostRoute = 0;
			} else {
				// Touching it and still refused — spoken for, or too big for
				// this body. Either way it is not this machine's work.
				writtenOff.add(target.getID());
				target = null;
			}
			stop();
			return;
		}
		if (sameFloor && gap <= FINAL_APPROACH) {
			// Close the last stretch by eye rather than by route. The route
			// ends at the crate's tile and a grab reaches about a fifth of a
			// tile, so a machine that stops where the path stops is standing
			// too far away to pick anything up — which it then reads as the
			// crate being unreachable, and writes off a crate it is touching.
			path = null;
			steer(target.getX(), target.getY());
			return;
		}
		if (!walkTo(target.getX(), target.getY(), target.getZ())) {
			if (++lostRoute >= GIVE_UP) {
				writtenOff.add(target.getID());
				target = null;
				lostRoute = 0;
			}
		} else {
			lostRoute = 0;
		}
	}

	/** Laden: walk to the vault and set the crate down. */
	private void haul() {
		if (load.isRemoved()) { // broken open under it, or eaten
			load = null;
			speed = CRAWL;
			return;
		}
		// On the vault's own floor, and only there. Standing directly above or
		// below the drop point reads as 1.0 away, inside DROP_AT — so a loader
		// that had gone downstairs would set the crate down on the plant floor
		// and count the delivery made.
		if (getLvl() == (int) vaultZ && distance(vaultX, vaultY, vaultZ) <= DROP_AT) {
			drop();
			load = null;
			speed = CRAWL;
			stop();
			return;
		}
		if (!walkTo(vaultX, vaultY, vaultZ) && ++lostRoute >= GIVE_UP) {
			// Cannot reach the vault carrying this. Set it down where it stands
			// rather than pacing with it: a crate on the floor is work someone
			// can see, and a machine holding one forever is not.
			drop();
			load = null;
			speed = CRAWL;
			lostRoute = 0;
		}
	}

	/** Nothing to haul: go and stand on the berth. */
	private void returnToBerth() {
		if (onBerth()) {
			stop();
			return;
		}
		walkTo(berthX, berthY, berthZ);
	}

	/**
	 * The nearest crate that is out of the vault, reachable and not spoken for.
	 *
	 * <p>Nearest by straight-line distance rather than by route length, which
	 * is wrong in a building with walls and is the right kind of wrong: a
	 * machine that walks to the crate that merely looks closest is a machine
	 * doing something legible, and the route it then fails to find is handled
	 * by {@link #writtenOff} rather than by a second pathfind per candidate
	 * per tick.
	 *
	 * <p>Straight line within a floor, that is. Across floors it is a world per
	 * storey — see {@link MachineRange}. A crate one level down is not a third
	 * of a tile away however the arithmetic reads; it is down the stairwell and
	 * back.
	 */
	private Item pickCrate() {
		Item best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : getWorld().getEntities()) {
			if (!(e instanceof Item it) || it.isRemoved()) {
				continue;
			}
			if (it.getKind() != Item.Kind.CRATE || stowed(it) || carriedByOther(it)) {
				continue;
			}
			if (writtenOff.contains(it.getID())) {
				continue;
			}
			double d = MachineRange.toChooseBy(this, it);
			if (d < bestD) {
				bestD = d;
				best = it;
			}
		}
		return best;
	}

	private boolean stowed(Item it) {
		return it.getZ() == vaultZ
				&& Math.hypot(it.getX() - vaultX, it.getY() - vaultY) <= STOWED;
	}

	/**
	 * How close this body must be to take hold of another, in tiles.
	 *
	 * <p>Asked of the entities rather than guessed, because guessing it wrong
	 * is silent: {@link NPC#grab} refuses out of range and says nothing about
	 * why, so a machine that stops a tile short does not fail to grab, it fails
	 * to grab FOREVER and concludes the crate is unreachable. Sizes are in
	 * tiles here — {@code NPC.getSize()} divides the pixel radius by the tile
	 * size, and {@code Item} is an NPC — so a crate is 0.125 and this body is
	 * 0.3125, which makes the real reach about a fifth of a tile.
	 */
	/** Standing on the berth: near it, and on its floor. BERTHED is under a
	 *  tile so the floor test changes nothing today — but it is the same
	 *  reading that was wrong twice above, and a berth is a place on a floor
	 *  rather than a column through the building. */
	private boolean onBerth() {
		return getLvl() == (int) berthZ
				&& distance(berthX, berthY, berthZ) <= BERTHED;
	}

	private double reachTo(Item it) {
		return it.getSize() / 2 + getSize() / 2;
	}

	private boolean carriedByOther(Item it) {
		return it.getAttachTarget() != null && it.getAttachTarget() != this;
	}

	/** Routes to a goal and takes one step along it. False when there is no
	 *  route — sealed in, or the goal sealed off. */
	private boolean walkTo(double gx, double gy, double gz) {
		boolean stale = path == null || --repathIn <= 0
				|| Math.abs(routeX - gx) + Math.abs(routeY - gy) > 1.5 || routeZ != gz;
		if (stale) {
			generatePath(gx, gy, gz, true); // through doors: they open for this body
			routeX = gx;
			routeY = gy;
			routeZ = gz;
			repathIn = REPATH;
			// Drop the waypoint the old plan was steering at: a fresh route
			// starts from here, and advance() only takes a new waypoint on
			// arriving at the current one, so a stale one it cannot arrive at
			// is never replaced. This is the wedge that held the drone against
			// a rock corner for a thousand ticks.
			tX = X;
			tY = Y;
			tZ = Z;
		}
		if (path == null) {
			stop();
			return false;
		}
		advance();
		return true;
	}

	/** One step along the route. Flat distance to the waypoint, ignoring level,
	 *  so a ramp waypoint one level up is still reachable — see the drone's
	 *  {@code advance} for why that is the only reading that works. */
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

	/** Turns toward a point and walks. Heavier than the drone: it comes about
	 *  more slowly and will not walk while it is still turning hard. */
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
			stop();
		} else {
			move(speed, D);
		}
	}

	/**
	 * The transponder, as the drone's: every door within range is asked to hold
	 * open, once per tick.
	 *
	 * <p>A heavier machine could have been left to work the building's pressure
	 * plates, which it is certainly heavy enough to press — that was the more
	 * interesting design and it was dropped, because a route planned through
	 * doors is planned through ALL of them, and the first door on the way that
	 * happens not to be plate-wired strands the loader in a corridor holding a
	 * crate. Both machines carry the facility's key; only one of them could have
	 * managed without it.
	 */
	private void openTheWay() {
		for (Entity e : getWorld().getEntities()) {
			if (e instanceof Door d && !d.isRemoved() && d.getLvl() == getLvl()
					&& distance(d.getX() + 0.5, d.getY() + 0.5, d.getZ()) <= TRANSPONDER) {
				d.holdOpen();
			}
		}
	}

	private void stop() {
		tX = X;
		tY = Y;
		tZ = Z;
		dX = 0;
		dY = 0;
		dZ = 0;
	}

	/** The crate this loader is carrying, or null. */
	/** The crate it is on its way to, or null. The counterpart to the drone's
	 *  {@code quarry()}, and here for the same reason: what a machine has
	 *  decided to do next is not visible from where it happens to be standing,
	 *  and a scenario that can only see position can only guess. */
	public Item target() {
		return target;
	}

	public Item load() {
		return load;
	}

	/** Whether it is parked on its berth with nothing to haul. */
	public boolean isBerthed() {
		return load == null && onBerth();
	}

	/** Equipment, not an animal: nothing eats it and nothing rides it. */
	@Override
	public boolean isOrganic() {
		return false;
	}

	@Override
	public String ecoRole() {
		return "loader";
	}

	@Override
	public String getNpcTypeName() {
		return "FacilityLoader";
	}

	/** Indestructible, as the drone is, and for the same reason: it is plant.
	 *  A hauler that a predator can break is a hauler that stops being
	 *  infrastructure and starts being a subplot. */
	@Override
	public void damage(int dmg) {
		// no-op
	}
}
