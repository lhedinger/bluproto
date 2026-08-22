package net.hedinger.prototype.engine;


import net.hedinger.prototype.entities.Sound;

public abstract class Entity {
	private World world;

	// position variables
	protected double X, Y, Z;
	// velocity variables
	protected double dX, dY, dZ;

	protected double D; // radians

	protected float size = 0; // radius

	protected int size_diameter = 25; // pixels

	private int ID = -1;
	protected int age = 0;
	protected int health = 100; // percent
	protected int lifespan = -1; // live forever
	protected int deathspan = 0; // disappear after death
	protected boolean remove = false;
	protected boolean selected = false;

	private Entity attachTarget = null;
	private double attachAngle = 0;
	private boolean grabbed = false;
	/** Total body weight of everything currently attached to (riding on) this
	 *  entity -- the load a carrier pays to haul around. Kept as a running sum so
	 *  a carrier need not scan the world for its riders each tick. */
	private double carriedLoad = 0;
	/** Accumulated force a carrier has applied trying to buck this entity off its
	 *  back; when it exceeds this rider's grip it is thrown clear. Reset on detach. */
	private double buckPressure = 0;

	/**
	 * Hard ceiling on how far any body may travel in one tick (tiles). This is an
	 * ENGINE limit, not a balance knob: passability is decided one tile at a time
	 * ({@link Tile#isConnected} rejects a step landing more than one tile away), so
	 * a longer step would tunnel through walls, water and hole edges — or, once it
	 * exceeded a tile, be rejected outright and freeze the creature in place. Half
	 * a tile keeps every step inside the neighbour the collision test actually
	 * checks, with margin for the +/-10% jitter movement adds.
	 */
	public static final double MAX_STEP = 0.5;

	/** Ground actually covered on the last tick (tiles) — zero when a move was
	 *  cancelled by a collision, or while being carried. Drives the locomotion
	 *  energy cost in {@code NPC}: creatures pay for distance travelled, not for
	 *  intent. */
	protected double lastStep = 0;

	/** Ground this body covered on its last tick (tiles). */
	public double lastStep() {
		return lastStep;
	}

	protected Sound lastHeardSound = null;

	protected abstract void think();

	protected void collisionCheck() {

	}

	public Entity() {
		X = -1;
		Y = -1;
		Z = -1;
		D = Utils.random() * 2 * Math.PI;
	}

	public Entity(double x, double y, double z) {
		X = x;
		Y = y;
		Z = z;
		D = Utils.random() * 2 * Math.PI;
		dX = 0;
		dY = 0;
		dZ = 0;
	}

	public Entity(double x, double y, double z, double d) {
		X = x;
		Y = y;
		Z = z;
		D = d;
		dX = 0;
		dY = 0;
		dZ = 0;
	}

	public void buildID(World w, int n) {
		world = w;
		if (ID != -1) {
			return;
		}
		int max = world.getColums() * world.getRows() * world.getLevels();
		int mult = 10;
		while (max > mult) {
			mult *= 10;
		}

		ID = world.hashCode(X, Y, Z) + n * mult;
		// System.out.println("Spawning Entity " + ID);
		// ID%max = spawn world location
		// ID/max = spawn index position
	}

	public final boolean run() {
		if (world == null) {
			return false;
		}

		if (remove) {
			return false;
		}

		if (Z == -1) {
			think();
			return true;
		}

		dX = 0;
		dY = 0;
		dZ = 0;

		run_extended();

		if (age >= 0 && health <= 0) {
			// Whatever wore the health down names the death; plain "wounds" if
			// nothing bothered to say.
			recordDeath(lastHarm != null ? lastHarm : "wounds");
			kill();
		}

		if (age >= lifespan && lifespan > -1) {
			recordDeath("old age");
			kill();
		}

		if (age < -deathspan) {
			onCorpseExpired();
			markRemoved();
		}

		if (age >= 0) {
			age++;
			if (world.isValid(X, Y, Z)) {
				think(); // determine movement
				collisionCheck();
				executeMovement(); // update movement
			} else {
				age = -deathspan;
			}
		} else {
			// A corpse ages toward removal: after `deathspan` ticks it drops past
			// -deathspan and is purged next run. Without this a dead body with a
			// positive deathspan only ever cleared if something scavenged it
			// (eat() decrements age), so corpses accumulated forever in a
			// long-running world. Scavenging still speeds it up.
			age--;
		}
		return true;
	}

	protected void run_extended() {
		// to be overwritten by other classes
	}

	/** Called once, on the tick a corpse finishes rotting away (whether it
	 *  decayed on its own or was scavenged down to nothing) — the hook for
	 *  returning what the body was made of to the world. Default: nothing. */
	protected void onCorpseExpired() {
	}

	// |///////////////////////////////
	// |///////////////////////////////////////////////////////////////
	// COLLISION METHODS
	// |///////////////////////////////////////////////////////////////
	// |///////////////////////////////

	private void executeMovement() {

		if (attachTarget != null) {
			dX = 0;
			dY = 0;
			dZ = 0;
			lastStep = 0; // carried, not walking: the host pays for the travel

			double dist = attachTarget.getSize() / 2 + getSize() / 2;

			double dir = attachTarget.getDirection() + attachAngle;
			float dx = (float) (Math.cos(dir) * dist);
			float dy = (float) (Math.sin(dir) * dist);

			X = attachTarget.getX() + dx;
			Y = attachTarget.getY() + dy;
			Z = attachTarget.getZ();
			return;
		}

		// Mud drags: scale this step by the tile the entity stands on -- and
		// by how well this body fits it (a crystal bed drags by size).
		double drag = world.getTile(X, Y, Z).speedFactorFor(getPixelSize());
		if (drag != 1.0) {
			dX *= drag;
			dY *= drag;
		}

		// Engine speed limit. Collision is decided one tile at a time, so an
		// over-long step would either tunnel through terrain or (past a full tile)
		// be rejected wholesale, leaving the creature stuttering in place. Clamping
		// the vector — before the collision test, so what is checked is what is
		// applied — keeps a runaway speed gene physically impossible rather than
		// merely unlucky. Direction is preserved; only the magnitude is capped.
		double step = Math.hypot(dX, dY);
		if (step > MAX_STEP) {
			double k = MAX_STEP / step;
			dX *= k;
			dY *= k;
		}

		if (isOverHole() && !isFlying()) {
			// A pit is not a route, it is gravity: standing over one drops you.
			// FIXME allow flying entities to go down the hole if they wish
			if ((int) Z - 1 < 0) {
				// A drop on the lowest level is bottomless: there is no floor
				// below to land on, so the body simply falls out of the world
				// -- no corpse, nothing to scavenge, just gone.
				remove();
				return;
			}
			dZ = -1;
		} else {
			dZ = rampStep();
			if (isColliding()) {
				// A refused step used to cancel the whole vector, so a body moving
				// at any angle into an obstacle lost the component that was never
				// blocked -- it stopped dead against the edge rather than sliding
				// past it. With no slide, whatever the mind was steering toward
				// kept it pressed there, and "walked into the crystal and got
				// stuck" was the visible result. Try each axis alone before
				// giving the step up entirely.
				double wantX = dX, wantY = dY;
				if (!tryStep(wantX, 0) && !tryStep(0, wantY)) {
					dX = 0;
					dY = 0;
					dZ = 0;
				}
			}
		}

		if (jumpedTile()) {
			world.getTile(X, Y, Z).removeEntity(getID());
			world.getTile(X + dX, Y + dY, Z + dZ).addEntity(getID());
		}

		// What was actually covered, after drag, the speed clamp and any collision
		// cancellation — this is what locomotion is charged for.
		lastStep = Math.hypot(dX, dY);

		X += dX;
		Y += dY;
		Z += dZ;

		dX = 0;
		dY = 0;
		dZ = 0;
	}

	/**
	 * Proposes {@code (sx, sy)} as this tick's step and reports whether the engine
	 * allows it. The step vector is left set to whatever was tried, so a caller
	 * that gets {@code true} can simply stop -- the accepted move is already in
	 * place, level change included.
	 */
	private boolean tryStep(double sx, double sy) {
		if (sx == 0 && sy == 0) {
			return false; // standing still is not a way past an obstacle
		}
		dX = sx;
		dY = sy;
		dZ = rampStep(); // the level turnover depends on dX, so re-read it
		return !isColliding();
	}

	private boolean isOverHole() {
		return world.getTile(X, Y, Z).isDrop();
	}

	/**
	 * How far this step changes the body's level — entirely the ground's doing. A
	 * ramp is floor that spans two levels: its foot joins the level it sits on, its
	 * top the level above. Walk off the top and you come out one level up; walk off
	 * the foot and you come out one level down. The body decides nothing and needs
	 * no sense of height — to a creature a ramp is just ground that leads somewhere,
	 * which is the whole point of expressing it this way instead of as an actuator.
	 *
	 * <p>Ramps run east-west by convention, matching how every generator lays them:
	 * a {@code RAMPUP} climbs eastward, a {@code RAMPDOWN} descends westward. The
	 * change lands at the tile edge, so crossing the ramp itself is ordinary walking.
	 */
	private double rampStep() {
		Tile here = world.getTile(X, Y, Z);
		if (here == null) {
			return 0;
		}
		int col = (int) X, destCol = (int) (X + dX);
		if (destCol == col) {
			return 0; // still on the ramp: the level only turns over at its edge
		}
		if (here.getType() == Tile.TileType.TYPE_RAMPUP && destCol > col) {
			return 1;
		}
		if (here.getType() == Tile.TileType.TYPE_RAMPDOWN && destCol < col) {
			return -1;
		}
		return 0;
	}

	private boolean jumpedTile() {
		if ((int) (X) != (int) (X + dX)) {
			return true;
		}
		if ((int) (Y) != (int) (Y + dY)) {
			return true;
		}
		if ((int) (Z) != (int) (Z + dZ)) {
			return true;
		}
		return false;
	}

	protected boolean isColliding() {
		if (!world.isConnectedSpace(X, Y, Z, X + dX, Y + dY, Z + dZ)) {
			return true;
		}
		// Clearance gates keep an oversized body OUT; they must never seal one
		// IN. Bodies grow into their adult size, so one can enter a duct or a
		// bed as a juvenile and cross the clearance while inside. Gating on the
		// way out too left it permanently immobile -- inside a bed a step is
		// capped well below the half-tile needed to leave, so every escape route
		// was refused and the body never moved again. A body already standing on
		// the restricted ground is on its way out and is let past.
		Tile here = world.getTile(X, Y, Z);

		// A crawl duct only admits small bodies -- ground or flying, a frame
		// wider than the duct's clearance stops at the grille.
		Tile duct = world.getTile(X + dX, Y + dY, Z + dZ);
		if (duct != null && duct.getType() == Tile.TileType.TYPE_DUCT
				&& getPixelSize() > Tile.DUCT_CLEARANCE
				&& (here == null || here.getType() != Tile.TileType.TYPE_DUCT)) {
			return true;
		}
		// Water is impassable to land entities; flyers skim over it.
		if (!isFlying()) {
			Tile dest = world.getTile(X + dX, Y + dY, Z + dZ);
			if (dest != null && dest.isWater()) {
				return true;
			}
			// A crystal bed only admits bodies that fit between its shards;
			// a bigger frame is stopped at the bed's edge. Flyers skim over
			// -- unlike a duct, the bed is open to the air.
			if (dest != null && dest.getType() == Tile.TileType.TYPE_CRYSTAL_BED
					&& getPixelSize() > Tile.CRYSTAL_CLEARANCE
					&& (here == null || here.getType() != Tile.TileType.TYPE_CRYSTAL_BED)) {
				return true;
			}
		}
		return false;
	}

	// ======================================================
	// PUBLIC GETTERS AND SETTERS PUBLIC GETTERS AND SETTERS
	// ======================================================

	public void select() {
	}

	public void unselect() {
		selected = false;
	}

	public void hear(Sound sound) {
		lastHeardSound = sound;
	}

	public World getWorld() {
		return world;
	}

	public void setWorld(World w) {
		world = w;
	}

	public int getCol() {
		return (int) X;
	}

	public int getRow() {
		return (int) Y;
	}

	public int getLvl() {
		return (int) Z;
	}

	public double getX() {
		return X;
	}

	public double getY() {
		return Y;
	}

	public double getZ() {
		return Z;
	}

	public double getDirection() {
		return D;
	}

	public float getSize() {
		return size;
	}

	/** Body radius in pixels -- the unit the tile clearances (duct, crystal
	 *  bed) are measured in. NPCs store their radius in a field of their own,
	 *  so anything gating on body size must use this accessor, not the raw
	 *  {@code size} field, or every NPC reads as size zero. */
	public int getPixelSize() {
		return (int) size;
	}

	public int getAge() {
		return age;
	}

	public int getLifespan() {
		return lifespan;
	}

	/** Ticks this body lies there once dead, before the world reclaims it. */
	public int getDeathspan() {
		return deathspan;
	}

	public double getDX() {
		return dX;
	}

	public double getDY() {
		return dY;
	}

	/** Screen-cull margin in pixels, for the render layer's visibility test. */
	public int getCullMargin() {
		return size_diameter;
	}

	public int getHealth() {
		return health;
	}

	public int getID() {
		return ID;
	}

	public boolean isFlying() {
		return false;
	}

	public boolean isHostile() {
		return false;
	}

	public boolean isDetected() {
		return true;
	}

	public void unmark() {

	}

	public boolean isDead() {
		return (age < 0);
	}

	public boolean isRemoved() {
		return remove;
	}

	public void setGrabbed(boolean grabbed) {
		this.grabbed = grabbed;
	}

	/** True while another entity is carrying this one (a captive grab), as
	 *  opposed to this entity voluntarily riding a host (see attachTarget). */
	public boolean isGrabbed() {
		return grabbed;
	}

	/** The entity this one is attached to (carried by), or null. */
	public Entity getAttachTarget() {
		return attachTarget;
	}

	public boolean attachToTarget(Entity target) {
		if (attachTarget != null) {
			return false;
		}

		double dx = target.getX() - getX();
		double dy = target.getY() - getY();
		attachAngle = Math.atan2(dy, dx) + Math.PI - target.getDirection();

		attachTarget = target;
		target.carriedLoad += getSize(); // this body now weighs on the carrier
		return true;
	}

	public void detach() {
		if (attachTarget != null) {
			attachTarget.carriedLoad -= getSize();
			attachTarget = null;
		}
		buckPressure = 0;
	}

	/** Total weight of everything riding on this entity (0 if it carries nothing). */
	public double getCarriedLoad() {
		return carriedLoad;
	}

	public double getBuckPressure() {
		return buckPressure;
	}

	/** Adds to the bucking force accumulated against this rider's grip. */
	public void addBuckPressure(double amount) {
		buckPressure += amount;
	}

	/**
	 * lowers health by a given amount of points
	 *
	 * @param dmg
	 *            amount of damage done
	 */
	public void damage(int dmg) {
		health -= dmg;
	}

	/**
	 * Damage that knows what dealt it: remembers {@code cause} as the last harm,
	 * so if this wound (or an accumulation ending in it) proves fatal, the corpse
	 * can say what killed it. Callers that hurt creatures should prefer this over
	 * the bare overload.
	 */
	public void damage(int dmg, String cause) {
		lastHarm = cause;
		damage(dmg);
	}

	/** Why this body died ("starvation", "predation", "old age", ...), or null
	 *  while it is alive (or died before causes were tracked). Set once at the
	 *  moment of death; the inspector reports it so a corpse explains itself. */
	private String deathCause = null;
	/** What last hurt this body — promoted to the death cause if fatal. */
	private String lastHarm = null;

	public String getDeathCause() {
		return deathCause;
	}

	/** Records why this body died. The first recorded cause wins: a body that
	 *  starved and was then scavenged still starved. */
	public void recordDeath(String cause) {
		if (deathCause == null) {
			deathCause = cause;
		}
	}

	public void kill() {
		age = -1;
	}

	/**
	 * Ages this corpse forward to {@code progress} of the way through its
	 * decay (0 just died .. 1 gone), so a body can arrive already mostly
	 * rotted instead of having to lie there and get there.
	 *
	 * <p>The one caller is the steward drone's zap, which does not so much
	 * kill a creature as take most of it away: what is left on the deck is a
	 * remnant, not a carcass. Expressing that as decay rather than as a
	 * special kind of death means every system downstream already handles it
	 * — a scavenger's carrion score discounts by exactly this number, the
	 * corpse clears on the ordinary schedule, and what little mass is left
	 * still feeds the ground when it goes.
	 *
	 * <p>Ignored on a living body: decay is a fact about corpses, and aging a
	 * live one backwards would be a way to kill it by side effect.
	 */
	public void decayTo(double progress) {
		if (age >= 0 || deathspan <= 0) {
			return;
		}
		double p = progress < 0 ? 0 : progress > 1 ? 1 : progress;
		int aged = -(int) Math.round(p * deathspan);
		if (aged < age) {
			age = aged; // only ever forward: nothing un-rots
		}
	}

	public void remove() {
		age = -1;
		markRemoved();
	}

	/**
	 * Marks this entity for removal and frees its tile-occupancy slot. All
	 * paths that set the remove flag must come through here: nothing else ever
	 * takes a dead entity's ID out of its tile, so skipping the purge leaks
	 * stale IDs that every subsequent search iterates.
	 */
	protected final void markRemoved() {
		if (remove) {
			return;
		}
		remove = true;
		if (world != null) {
			world.getTile(X, Y, Z).removeEntity(getID());
		}
		detach(); // if we were being carried, stop weighing on the carrier
	}

	@Override
	public String toString() {
		return ID + ": " + getClass().getName() + "/x" + (int) X + "/y" + (int) Y + "/z" + (int) Z;
	}

	public static int round(double value) {
		return (int) (Math.round(value));
	}

	public static double ratio(double value, double max) {
		if (max == 0) {
			return 0;
		}

		double r = value;
		r = r / max;

		return r;
	}

	public static double ratioInv(double value, double max) {
		if (max == 0) {
			return 0;
		}

		if (Math.abs(value) > Math.abs(max)) {
			return 1;
		}

		double r = Math.abs(value);
		r = r / Math.abs(max);

		return (1 - r);
	}

	public static double variation(double origin, double range) {
		return ((origin + range) - (2 * range * Utils.random()));
	}

	public double distance(Entity e) {
		if (e == null) {
			return 0;
		}

		return world.distance(X, Y, Z, e.getX(), e.getY(), e.getZ());
	}

	public double distance(double tx, double ty, double tz) {
		return world.distance(X, Y, Z, tx, ty, tz);
	}

	public abstract String getEntityTypeName();

	public String getType() {
		return "Entity." + getEntityTypeName();
	}

}
