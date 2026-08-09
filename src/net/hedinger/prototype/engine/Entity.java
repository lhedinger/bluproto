package net.hedinger.prototype.engine;

import java.awt.Graphics;

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

	protected abstract void draw(Graphics g, View v);

	protected void draw_dead(Graphics g, View v) {

	}

	// protected abstract void draw_dead(Graphics g);

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
			kill();
		}

		if (age >= lifespan && lifespan > -1) {
			kill();
		}

		if (age < -deathspan) {
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

	public void render(Graphics g, View v) {
		if (!isVisible(g, v)) {
			return;
		}

		draw_extended(g);

		if (age < 0) {
			draw_dead(g, v);
		} else {
			draw(g, v);
		}
	}

	protected void run_extended() {
		// to be overwritten by other classes
	}

	protected void draw_extended(Graphics g) {
		// to be overwritten by other classes
	}

	public boolean isVisible(Graphics g, View v) {
		double zk = v.getCamZ() - Z + 1;
		int width = (int) g.getClipBounds().getMaxX();
		int height = (int) g.getClipBounds().getMaxY();

		if (zk <= 0) {
			return false;
		}

		if (pixelX(v, 0) > width + size_diameter) {
			return false;
		}

		if (pixelX(v, 0) < -size_diameter) {
			return false;
		}

		if (pixelY(v, 0) > height + size_diameter) {
			return false;
		}

		if (pixelY(v, 0) < -size_diameter) {
			return false;
		}

		if (age > 0 && world.hasFog()) {
			if (isHostile() && !isDetected()) {
				return false;
			}
		}

		return true;

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
			dZ = -1;
		} else {
			dZ = rampStep();
			if (isColliding()) {
				dX = 0;
				dY = 0;
				dZ = 0;
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
		// A crawl duct only admits small bodies -- ground or flying, a frame
		// wider than the duct's clearance stops at the grille.
		Tile duct = world.getTile(X + dX, Y + dY, Z + dZ);
		if (duct != null && duct.getType() == Tile.TileType.TYPE_DUCT
				&& getPixelSize() > Tile.DUCT_CLEARANCE) {
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
					&& getPixelSize() > Tile.CRYSTAL_CLEARANCE) {
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

	public void kill() {
		age = -1;
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

	protected int toPixel(View v, double tiles) {
		return Utils.toPixel(tiles, (int) Z, v.getCamZ());
	}

	protected int pixelX(View v, double pixelOffset) {
		return v.pixelX(X, Z, round(pixelOffset));
	}

	protected int pixelY(View v, double pixelOffset) {
		return v.pixelY(Y, Z, round(pixelOffset));
	}

	protected int pixelX(View v, double offset, double pixelOffset) {
		return v.pixelX(X - offset, Z, round(pixelOffset));
	}

	protected int pixelY(View v, double offset, double pixelOffset) {
		return v.pixelY(Y - offset, Z, round(pixelOffset));
	}

	public abstract String getEntityTypeName();

	public String getType() {
		return "Entity." + getEntityTypeName();
	}

}
