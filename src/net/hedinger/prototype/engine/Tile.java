package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPDOWN;
import static net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPUP;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class Tile {
	private World world;
	private int col, row, lvl;
	private TileType type;
	private String tilecode = "";
	// Occupant entity IDs as a primitive array: iterated on every search, so
	// boxed-set iteration cost matters. Each entity lives in exactly one tile
	// and IDs are unique, so plain append + swap-remove keeps set semantics.
	private int[] occupants = new int[4];
	private int occupantCount = 0;

	boolean door_N = false; // open
	boolean door_E = false; // open
	boolean door_S = false; // open
	boolean door_W = false; // open

	private int variant = 0;

	// --- vegetation (the living substrate) ---------------------------------
	// A regrowing food resource on walkable ground. Grazers deplete it; it
	// regrows LOGISTICALLY toward a cap -- growth is proportional to how much
	// grass is already standing (and how much headroom is left), so a tile with
	// more grass recovers faster and a nearly-bare one only creeps up. A tile
	// grazed down past DEPLETION_LEVEL first rests for REGROW_DELAY (~1 min)
	// before any regrowth begins. All of this is computed lazily in closed form
	// from the last-grazed tick (the logistic curve has a closed solution), so
	// there is still no per-tick sweep over the map -- a tile costs nothing until
	// something grazes or draws it.
	@Unit("vegetation")
	public static final double VEG_MAX = 1.0;
	/** Default logistic growth rate (per tick): larger => faster recovery. */
	@Unit("vegetation/tick")
	public static final double VEG_REGROW = 0.002;
	/** A tile grazed below {@link #DEPLETION_LEVEL} of its cap pauses this many
	 *  ticks (~1 min at 33 t/s) before it starts to recover; a tile with grass to
	 *  spare resumes at once. */
	@Unit("ticks")
	public static final long REGROW_DELAY = 2000;
	/** Below this fraction of its cap a tile counts as "depleted" and takes the
	 *  {@link #REGROW_DELAY} cooldown before recovering. */
	private static final double DEPLETION_LEVEL = 0.25;
	/** Density (as a fraction of cap) the logistic curve restarts from once a tile
	 *  is stripped bare, so recovery has a seed to climb from (pure proportional
	 *  growth can't lift off from exactly zero). */
	private static final double VEG_SEED = 0.03;
	private double vegStored = VEG_MAX; // density at vegTick
	private long vegTick = 0; // tick vegStored was last written
	private double regrowRate = VEG_REGROW; // per-tile logistic rate (world-gen may slow grass)

	// Fertility scales this tile's vegetation cap: 1 = fully lush, 0 = barren.
	// A fertility field (see World.generateFertility) makes grass grow patchy,
	// so the map has rich and poor habitats instead of uniform pasture.
	private double fertility = 1.0;

	// --- ramp orientation ---------------------------------------------------
	/**
	 * Which way this ramp's HIGH side faces, as a cardinal (see {@link #DIR_N}).
	 * A {@code RAMPUP} lets a body out one level up when it steps toward this
	 * direction; a {@code RAMPDOWN} lets it out one level down when it steps the
	 * other way, off its foot. One value decides both, because a ramp's slope is
	 * one axis with a sign.
	 *
	 * <p>It is STORED rather than read off the neighbours, though the art could
	 * guess it from the layout. A ramp's direction decides where bodies may walk
	 * and what the pathfinder believes; deriving that from the surrounding tiles
	 * would mean carving rock near a ramp could silently turn it around, and the
	 * route someone was walking would stop existing. Stored, the art and the
	 * movement read the same field, so a ramp can never look like it climbs one
	 * way and walk another.
	 *
	 * <p>Defaults to EAST, which is exactly the old hardcoded convention — a
	 * ramp nobody gave a direction to behaves as every ramp did before.
	 */
	private int rampUphill = DIR_E;

	/** Cardinals, in the order the renderer already numbers them. */
	public static final int DIR_N = 0, DIR_E = 1, DIR_S = 2, DIR_W = 3;
	private static final int[] DIR_DX = { 0, 1, 0, -1 };
	private static final int[] DIR_DY = { -1, 0, 1, 0 };

	/** Column step of a cardinal. */
	public static int dirDx(int dir) {
		return DIR_DX[dir & 3];
	}

	/** Row step of a cardinal. */
	public static int dirDy(int dir) {
		return DIR_DY[dir & 3];
	}

	/** The cardinal facing the other way. */
	public static int opposite(int dir) {
		return (dir + 2) & 3;
	}

	/** Which way this ramp's high side faces (see {@link #rampUphill}). */
	public int getRampUphill() {
		return rampUphill;
	}

	/** Points this ramp's high side at {@code dir}. Set by whoever builds the
	 *  ramp; every reader — movement, pathfinding, the connectivity floods and
	 *  the art — takes its answer from here. */
	public void setRampUphill(int dir) {
		rampUphill = dir & 3;
	}

	/** The cardinal a body must step to leave this ramp at the level it joins:
	 *  up the slope off a {@code RAMPUP}, down off a {@code RAMPDOWN}'s foot.
	 *  The single place that rule is written down. */
	public int rampExit() {
		return type == TYPE_RAMPUP ? rampUphill : opposite(rampUphill);
	}

	// --- belt direction -----------------------------------------------------
	/**
	 * Which way a {@code CONVEYOR} carries what sits on it, as a cardinal (see
	 * {@link #DIR_N}).
	 *
	 * <p>A belt is not a track. A rail run is the same run travelled either
	 * way, so the track can work its shape out from the sides the run
	 * continues into and never needs to be told anything. A belt has a near
	 * end and a far end, and no arrangement of neighbouring tiles says which
	 * is which — the same straight line of belt tiles is a different machine
	 * depending on which way it moves.
	 *
	 * <p>So it is STORED, for the reason {@link #rampUphill} is stored. The
	 * art used to ask whether the tiles north and south were belts and draw
	 * along whichever axis answered. That can express two axes but not four
	 * directions: every belt laid along a row pointed west and every belt laid
	 * down a column pointed north, not because anyone chose it but because
	 * that is the way the arithmetic happened to fall out. A belt that cannot
	 * be turned around is a belt whose picture is a coincidence.
	 *
	 * <p>Defaults to WEST, which is exactly that old convention for a belt
	 * laid along a row — a belt nobody gives a direction to keeps the one it
	 * was drawn with before.
	 */
	private int beltRun = DIR_W;

	/** Which way this belt carries (see {@link #beltRun}). */
	public int getBeltRun() {
		return beltRun;
	}

	/** Points this belt's travel at {@code dir}. Set by whoever lays the belt;
	 *  the art takes its answer from here, never from the neighbours. */
	public void setBeltRun(int dir) {
		beltRun = dir & 3;
	}

	// Tall grass is a purely cosmetic overlay: blades drawn on top of the ground
	// that bend aside as entities pass through. It has NO effect on movement,
	// perception, vegetation, or anything the simulation reads -- it is only a
	// render flag (unlike TYPE_COVER, which does hide entities from perception).
	private boolean tallGrass = false;

	public boolean hasTallGrass() {
		return tallGrass;
	}

	public void setTallGrass(boolean on) {
		tallGrass = on;
	}

	// Pheromone is no longer a per-tile field: it lives as PheromoneCloud
	// entities (a centre + radius + decaying strength), deposited and sensed
	// through World.depositPheromone / pheromoneAt / pheromoneDirection.

	public double getFertility() {
		return fertility;
	}

	public void setFertility(double f) {
		fertility = f < 0 ? 0 : (f > 1 ? 1 : f);
	}

	/** Logistic regrow rate for this tile (world-gen can slow grass so grazed
	 *  patches take longer to recover). Larger => faster recovery. */
	public void setRegrowRate(double r) {
		regrowRate = r < 0 ? 0 : r;
	}

	/** The most vegetation this tile can hold, given its fertility. */
	public double vegetationCap() {
		return VEG_MAX * fertility;
	}

	/** True where vegetation can grow: grassy floor, the thin sward rocky
	 *  ground keeps, or cave fungus beds -- all three feed grazers through the
	 *  same logistic regrowth model, and differ only in how much they can hold
	 *  (their fertility). */
	public boolean growsVegetation() {
		return type == TileType.TYPE_FLOOR || type == TileType.TYPE_ROCKY
				|| type == TileType.TYPE_FUNGUS;
	}

	/** Current vegetation density [0, cap], regrown lazily to {@code now} along a
	 *  logistic curve (fast when there is grass to spare, slow when nearly bare),
	 *  after a rest period for tiles that were grazed down to depletion. */
	public double getVegetation(long now) {
		if (!growsVegetation()) {
			return 0;
		}
		double cap = vegetationCap();
		if (cap <= 0) {
			return 0;
		}
		long elapsed = now - vegTick;
		// A grazed-down tile rests before recovering; one with grass to spare
		// starts regrowing immediately.
		long delay = vegStored < DEPLETION_LEVEL * cap ? REGROW_DELAY : 0;
		if (elapsed <= delay) {
			return vegStored > cap ? cap : vegStored;
		}
		// Logistic growth v(t) = cap / (1 + ((cap - v0)/v0) e^(-r t)): the rate is
		// proportional to standing grass and remaining headroom, so more grass
		// regrows faster. Seed a stripped tile so the curve can lift off from zero.
		double v0 = vegStored > cap ? cap : vegStored;
		double seed = VEG_SEED * cap;
		if (v0 < seed) {
			v0 = seed;
		}
		double t = elapsed - delay;
		double v = cap / (1 + ((cap - v0) / v0) * Math.exp(-regrowRate * t));
		return v > cap ? cap : v;
	}

	/**
	 * Consumes up to {@code demand} vegetation, returning how much was actually
	 * eaten. Folds in regrowth since the last touch, then writes the new stored
	 * value and stamp so growth resumes from here.
	 */
	public double graze(long now, double demand) {
		if (!growsVegetation() || demand <= 0) {
			return 0;
		}
		double v = getVegetation(now);
		double eaten = demand < v ? demand : v;
		vegStored = v - eaten;
		vegTick = now;
		return eaten;
	}

	public Tile(World w, int x, int y, int z) {
		world = w;

		TileType t = TileType.TYPE_FLOOR;

		if (Utils.random() * 2 < 1) {
			t = TileType.TYPE_WALL;
		}

		col = x;
		row = y;
		lvl = z;
		type = t;

		if (Utils.random(2) == 1) {
			variant = (1 + Utils.random(10 - 1));
		}

	}

	public Tile(int x, int y, int z, TileType t) {
		col = x;
		row = y;
		lvl = z;
		type = t;

		if (Utils.random(2) == 1) {
			variant = (1 + Utils.random(10 - 1));
		}
	}

	/**
	 * A tile whose variant the caller chooses: draws NO randomness. The
	 * four-arg constructor above rolls a cosmetic variant off the seeded
	 * simulation stream, which is right for world generation and wrong for
	 * everything else — an out-of-bounds fallback, a catalog probe, a
	 * ground-class lookup is a QUERY, and a query that advances the world's
	 * one RNG both burns the stream on the sim thread and races it from any
	 * other (the boot-time catalog bake runs beside the ticking world). Pure
	 * readers construct through here and leave the stream exactly as found.
	 */
	public Tile(int x, int y, int z, TileType t, int variant) {
		col = x;
		row = y;
		lvl = z;
		type = t;
		this.variant = variant;
	}

	public int getVariant() {
		return variant;
	}

	public String getTileCode() {
		return tilecode;
	}

	public void addEntity(int id) {
		if (occupantCount == occupants.length) {
			occupants = java.util.Arrays.copyOf(occupants, occupants.length * 2);
		}
		occupants[occupantCount++] = id;
	}

	public void removeEntity(int id) {
		for (int i = 0; i < occupantCount; i++) {
			if (occupants[i] == id) {
				occupants[i] = occupants[--occupantCount]; // swap-remove
				return;
			}
		}
	}

	public int getEntityCount() {
		return occupantCount;
	}

	public int getEntityId(int idx) {
		return occupants[idx];
	}

	public void setType(TileType t) {
		type = t;
	}

	public TileType getType() {
		return type;
	}

	/**
	 * 0 = North 1 = East 2 = South 3 = West
	 *
	 * @param dir
	 *            which door to open
	 */
	public void openDoor(int dir) {
		if (dir == 0) {
			door_N = false;
		}
		if (dir == 1) {
			door_E = false;
		}
		if (dir == 2) {
			door_S = false;
		}
		if (dir == 3) {
			door_W = false;
			// calcConnected(world);
		}
	}

	/**
	 * 0 = North 1 = East 2 = South 3 = West
	 *
	 * @param dir
	 *            which door to close
	 */
	public void closeDoor(int dir) {
		if (dir == 0) {
			door_N = true;
		}
		if (dir == 1) {
			door_E = true;
		}
		if (dir == 2) {
			door_S = true;
		}
		if (dir == 3) {
			door_W = true;
			// calcConnected(world);
		}
	}

	/** True if the door on the given edge is closed (0=N, 1=E, 2=S, 3=W). */
	public boolean isDoorClosed(int dir) {
		if (dir == 0) {
			return door_N;
		}
		if (dir == 1) {
			return door_E;
		}
		if (dir == 2) {
			return door_S;
		}
		if (dir == 3) {
			return door_W;
		}
		return false;
	}

	public HashSet<Integer> calcConnected(World w, boolean diagonal) {
		return calcConnected(w, diagonal, false);
	}

	/**
	 * Every tile a body could step to from this one, as world hashes — the
	 * pathfinder's edge set.
	 *
	 * <p>Includes the two <b>ramp edges</b>, which the same-level scan below
	 * cannot see. A ramp is floor that spans two levels, so walking east off a
	 * {@code RAMPUP}'s top comes out one level up and west off a
	 * {@code RAMPDOWN}'s foot one level down — the rule {@link
	 * net.hedinger.prototype.engine.Entity}'s movement resolution already
	 * enforces, and the one the world generator's connectivity flood already
	 * follows. Without them here the search graph was one disconnected
	 * component per level, so {@code findPath} silently answered "no route"
	 * for any goal off the caller's own floor even though a body could
	 * plainly walk there. Nothing was wrong with the levels; the map the
	 * pathfinder was reading simply stopped at the ceiling.
	 *
	 * <p>{@code throughDoors} plans as though every door on the way were open.
	 * A shut door is not a wall — it is a wall to whoever cannot open it — so
	 * whether it belongs in the graph depends on who is asking. Bodies get the
	 * honest map (the default): a shut door is impassable and a route is found
	 * around it or not at all. The steward drone, which opens doors by coming
	 * near them, gets the other one, and simply flies at each door until it
	 * parts.
	 */
	public HashSet<Integer> calcConnected(World w, boolean diagonal, boolean throughDoors) {
		return calcConnected(w, diagonal, throughDoors, 0);
	}

	/** As above, refusing any edge into ground too tight for a body of
	 *  {@code clearance} pixels — 0 asks for the clearance-blind graph. */
	public HashSet<Integer> calcConnected(World w, boolean diagonal, boolean throughDoors,
			int clearance) {
		HashSet<Integer> connected = new HashSet<Integer>();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				if (!(x == 0 && y == 0) && fits(w, col + x, row + y, lvl, clearance)
						&& isConnected(w, col + x, row + y, lvl, diagonal, false, throughDoors)) {
					connected.add(w.hashCode(col + x, row + y, lvl));
				}
			}
		}
		if (type == TYPE_RAMPUP || type == TYPE_RAMPDOWN) {
			// The one edge the same-level scan cannot see, taken off whichever
			// side this ramp's slope actually runs to.
			int exit = rampExit();
			int nx = col + dirDx(exit), ny = row + dirDy(exit);
			int nz = type == TYPE_RAMPUP ? lvl + 1 : lvl - 1;
			if (w.isValid(nx, ny, nz) && !w.getTile(nx, ny, nz).isSolid()
					&& fits(w, nx, ny, nz, clearance)) {
				connected.add(w.hashCode(nx, ny, nz));
			}
		}
		return connected;
	}

	/**
	 * Whether a body of {@code clearance} pixels could get into the tile at
	 * {@code (c, r, l)} — the same two gates {@code Entity.isColliding}
	 * enforces when the step is actually taken: a crawl duct admits only small
	 * frames, and a shard bed only bodies that fit between the shards.
	 *
	 * <p>The pathfinder had no notion of how big the traveller was, which cost
	 * nothing while the only things that used it were small. It costs a great
	 * deal for a machine the size of the steward's drone: a route laid through
	 * the base's ventilation ducting is a route the engine then refuses one
	 * step at a time, and the drone presses against the grille until something
	 * else re-plans for it. A route a body cannot fly is not a route.
	 */
	private static boolean fits(World w, int c, int r, int l, int clearance) {
		if (clearance <= 0 || !w.isValid(c, r, l)) {
			return true; // clearance-blind, or out of bounds for isConnected to refuse
		}
		TileType t = w.getTile(c, r, l).getType();
		if (t == TileType.TYPE_DUCT) {
			return clearance <= DUCT_CLEARANCE;
		}
		if (t == TileType.TYPE_CRYSTAL_BED) {
			return clearance <= CRYSTAL_CLEARANCE;
		}
		return true;
	}

	public boolean isConnectedStatic(World w, int x, int y, int z) {

		if (x < 0 || y < 0) {
			return false;
		}

		Tile temp = w.getTile(x, y, z);

		TileType t = temp.getType();

		int dz = lvl - z;

		if (dz < 0) {
			if ((type != TileType.TYPE_RAMPDOWN)) {
				return false;
			}
		} else if (dz > 0) {
			if ((type != TileType.TYPE_RAMPUP)) {
				return false;
			}
		} else {
			if (t != type) {
				return oneMassWithUpRamp(t);
			}
		}

		return true;
	}

	/**
	 * Whether this tile and its neighbour are one mass of rock, because one of
	 * them is an up ramp cut into the other.
	 *
	 * <p>Autotiling otherwise treats a wall and a ramp as two different things
	 * standing side by side, and BOTH of them then draw a boundary: the wall
	 * puts a rounded corner and a silhouette rim across the join, and the ramp
	 * raises one of its own triangular side walls against rock that is already
	 * there. The result is a doubled seam — the slope reads as a bright block
	 * set into a socket rather than as a cut in the cliff.
	 *
	 * <p>So the two agree to be one thing: the wall carries its face over the
	 * join and the ramp drops the wedge it does not need. It is deliberately
	 * every side, not just the one the ramp climbs toward — the flanks are
	 * where the doubled seam was most of the picture.
	 */
	private boolean oneMassWithUpRamp(TileType other) {
		if (isSolid() && other == TileType.TYPE_RAMPUP) {
			return true; // I am the rock, and the ramp is a cut in me
		}
		return type == TileType.TYPE_RAMPUP && !other.isOpen(); // and the reverse
	}

	public boolean isConnected(World w, double x, double y, double z, boolean diagonal, boolean floorsOnly) {
		return isConnected(w, x, y, z, diagonal, floorsOnly, false);
	}

	/** As above, but {@code throughDoors} ignores every door flag on the way —
	 *  the map as it looks to a body that doors open for. See
	 *  {@link #calcConnected(World, boolean, boolean)}. */
	public boolean isConnected(World w, double x, double y, double z, boolean diagonal,
			boolean floorsOnly, boolean throughDoors) {
		Tile temp = w.getTile(World.toCol(x), World.toRow(y), World.toLvl(z));

		if (z < 0) {
			return false;
		}

		int dx = col - World.toCol(x);
		int dy = row - World.toRow(y);
		int dz = lvl - World.toLvl(z);

		if (dx == 0 && dy == 0 && dz == 0) {
			return true;
		}

		if (Math.abs(dx) > 1) {
			return false;
		}
		if (Math.abs(dy) > 1) {
			return false;
		}
		if (Math.abs(dz) > 1) {
			return false;
		}

		if (dz != 0) {
			// Only a ramp joins two levels, and only along its own slope, in the
			// one direction that slope runs: a RAMPUP lets you out a level up
			// off its high side, a RAMPDOWN a level down off its foot. Which way
			// that is comes from the tile (see rampUphill), so a ramp may face
			// any cardinal; everything else is a cliff. (dx/dy are source minus
			// destination, so the step actually taken is their negation.)
			if (temp == null || Math.abs(dx) + Math.abs(dy) != 1) {
				return false; // level changes are cardinal, never diagonal
			}
			if (dz < 0 ? type != TYPE_RAMPUP : type != TYPE_RAMPDOWN) {
				return false;
			}
			int exit = rampExit();
			if (-dx != dirDx(exit) || -dy != dirDy(exit)) {
				return false; // stepping off any other side of the ramp is a cliff
			}
			return floorsOnly ? temp.isWalkable() : !temp.isSolid();
		} else {
			if (floorsOnly && !temp.isWalkable()) {
				return false;
			}
			if (!floorsOnly && temp.isSolid()) {
				return false;
			}

			if (!throughDoors) {
				if (dy > 0 && door_N) {
					return false;
				}
				if (dx < 0 && door_E) {
					return false;
				}
				if (dy < 0 && door_S) {
					return false;
				}
				if (dx > 0 && door_W) {
					return false;
				}
				if (dy > 0 && temp.door_S) {
					return false;
				}
				if (dx < 0 && temp.door_W) {
					return false;
				}
				if (dy < 0 && temp.door_N) {
					return false;
				}
				if (dx > 0 && temp.door_E) {
					return false;
				}
			}

			if (Math.abs(dx) * Math.abs(dy) == 1) // diagonal
			{
				if (!isWalkable() && diagonal) {
					return true;
				}
				if (!isConnected(w, col - dx, row, lvl, false, floorsOnly, throughDoors)
						|| !isConnected(w, col, row - dy, lvl, false, floorsOnly, throughDoors)) {
					return false;
				}
			}

		}

		return true;
	}

	public boolean isWalkable() {
		return type.isOpen() && !isDrop() && type != TileType.TYPE_WATER;
	}

	public boolean isFlyable() {
		return type.isOpen();
	}

	public boolean isSolid() {
		return !type.isOpen();
	}

	/** Water is open (flyers pass) but not walkable — land entities can't enter. */
	public boolean isWater() {
		return type == TileType.TYPE_WATER;
	}

	/**
	 * Whether standing here hurts. The facility's waste channels are the only
	 * such ground, and they are the first in the world at all: until now the
	 * map could stop a body, slow it, hide it or drop it, but never wound it,
	 * so every injury in the world came from another creature or from its own
	 * empty stomach.
	 *
	 * <p>Deliberately walkable rather than solid. A wall is a fact you route
	 * around and forget; a floor that costs you something to cross is a
	 * decision — the short way through the spill against the long way round —
	 * and a decision is the only kind of terrain a mind can get better at. It
	 * also gives {@code S_HAZARD_AHEAD} a third thing to report, on ground a
	 * creature can actually choose to stand on, unlike water and pits.
	 */
	public boolean isCorrosive() {
		return type == TileType.TYPE_SLUDGE;
	}

	/** Movement multiplier for an entity standing on this tile: quicksand
	 *  near-stops, mud drags hardest of the ordinary ground, reeds tangle,
	 *  wading shallows and scrambling rubble slow. */
	public double speedFactor() {
		switch (type) {
		case TYPE_QUICKSAND:
			return 0.2;
		case TYPE_MUD:
			return 0.4;
		case TYPE_REEDS:
			return 0.5;
		case TYPE_SHALLOWS:
			return 0.5;
		case TYPE_RUBBLE:
			return 0.6;
		case TYPE_PIPES:
			return 0.6; // clambering over the runs
		case TYPE_DUCT:
			return 0.5; // crawling
		case TYPE_SLUDGE:
			return 0.5; // wading a viscous spill
		case TYPE_COLLAPSE:
			return 0.6; // picking over fallen ceiling, as with natural rubble
		case TYPE_CRYSTAL_BED:
			return 0.6; // nominal; the true drag is size-scaled, see speedFactorFor
		default:
			return 1.0;
		}
	}

	/**
	 * Movement multiplier for a body of {@code size} (radius) standing on
	 * this tile. Most ground drags every body alike; a crystal bed drags by
	 * fit -- a small body slips between the shards nearly unhindered, one
	 * near the clearance limit picks its way through at a crawl, and
	 * anything above {@link #CRYSTAL_CLEARANCE} never got in at all (the
	 * collision gate stops it at the bed's edge).
	 */
	public double speedFactorFor(double size) {
		if (type == TileType.TYPE_CRYSTAL_BED) {
			return 1.0 - 0.65 * Math.min(1.0, size / CRYSTAL_CLEARANCE);
		}
		return speedFactor();
	}

	/** The biggest body (radius) that fits through a crawl duct; anything
	 *  larger is stopped at the grille. */
	public static final float DUCT_CLEARANCE = 8;

	/** The biggest body (radius) that fits between a crystal bed's shards;
	 *  anything larger is stopped at the bed's edge -- so a bed is a refuge
	 *  the apex hunters cannot follow prey into. */
	public static final float CRYSTAL_CLEARANCE = 13;

	/** True where an unsupported body drops to the level below: natural
	 *  holes, the facility's vertical shafts, and the open air of a level
	 *  above the ground. A pit and a void fall the same way -- the difference
	 *  between them is that a pit is an opening in a floor and a void is the
	 *  absence of one, which is a fact about the art and the map, not about
	 *  what happens to a body that steps off. */
	public boolean isDrop() {
		return type == TileType.TYPE_HOLE || type == TileType.TYPE_SHAFT
				|| type == TileType.TYPE_VOID;
	}

	/** True if this tile blocks line of sight: walls (natural or built),
	 *  thicket cover, reed beds, and enclosed crawl ducts -- but NOT
	 *  crystal, which is solid to movement yet clear to the eye. */
	public boolean blocksSight() {
		// Crystal and server racks are the two solids you can see through: a
		// prism thicket is glass, and a rack is a frame with air in it. Both
		// make cover that stops a body without hiding one, which is a
		// different tactical shape from a wall -- prey watching a hunter it
		// cannot be reached through is a standoff a wall cannot produce.
		if (type == TileType.TYPE_CRYSTAL || type == TileType.TYPE_SERVER
				|| type == TileType.TYPE_CACTUS || type == TileType.TYPE_WINDOW
				|| type == TileType.TYPE_DESK || type == TileType.TYPE_BUNK
				|| type == TileType.TYPE_WRECK) {
			return false;
		}
		return isSolid() || type == TileType.TYPE_COVER || type == TileType.TYPE_REEDS
				|| type == TileType.TYPE_DUCT;
	}

	public void updateTilecode(World world) {
		HashSet<Integer> connected = calcConnectedStatic(world);
		tilecode = calcTilecode(world, connected);
	}

	private HashSet<Integer> calcConnectedStatic(World w) {
		HashSet<Integer> connected = new HashSet<>();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				if (!(x == 0 && y == 0)) {
					if (isConnectedStatic(w, col + x, row + y, lvl)) {
						connected.add(w.hashCode(col + x, row + y, lvl));
					}
				}
			}
		}
		return connected;
	}

	public String calcTilecode(World w, HashSet<Integer> connected) {

		/*
		 * \ 123 405 678 \
		 */

		TreeSet<Integer> dirs = new TreeSet<Integer>();
		String combinations = "";

		for (Integer i : connected) {
			int x = w.hashCol(i) - col;
			int y = w.hashRow(i) - row;

			if (y == -1 && x == -1) {
				dirs.add(1);
			}
			if (y == -1 && x == 0) {
				dirs.add(2);
			}
			if (y == -1 && x == 1) {
				dirs.add(3);
			}
			if (y == 0 && x == -1) {
				dirs.add(4);
			}
			if (y == 0 && x == 1) {
				dirs.add(5);
			}
			if (y == 1 && x == -1) {
				dirs.add(6);
			}
			if (y == 1 && x == 0) {
				dirs.add(7);
			}
			if (y == 1 && x == 1) {
				dirs.add(8);
			}

		}

		if (dirs.size() == 0) {
			return "0";
		}

		for (Integer i : dirs) {
			combinations += i.toString();
		}

		return combinations;
	}

	public enum TileType {
		TYPE_HOLE(0, true, "pit"),
		TYPE_FLOOR(1, true, "grassland"), // living substrate: soil that grows grass
		TYPE_WALL(2, false, "rock wall"),
		TYPE_RAMPUP(3, true, "ramp up"),
		TYPE_RAMPDOWN(4, true, "ramp down"),
		TYPE_WATER(5, true, "deep water"), // open (flyers pass) but not walkable
		TYPE_MUD(6, true, "mud"), // walkable, slows movement
		TYPE_COVER(7, true, "thicket"), // walkable, blocks line of sight
		TYPE_STONE(8, true, "stone floor"), // walkable bare rock floor; grows no vegetation
		TYPE_FUNGUS(9, true, "fungus bed"), // cave floor growing grazeable fungus
		TYPE_RUBBLE(10, true, "rubble"), // broken rock; slows movement, sight passes
		TYPE_SAND(11, true, "sand"), // bare loose ground; grows no vegetation
		TYPE_REEDS(12, true, "reed bed"), // wetland stalks; slow AND sight-blocking
		TYPE_SHALLOWS(13, true, "shallows"), // walkable water fringe; slows like a ford
		TYPE_QUICKSAND(14, true, "quicksand"), // treacherous sand; near-stops whatever enters
		TYPE_CRYSTAL(15, false, "crystal cluster"), // solid mineral cluster; blocks movement, not sight
		TYPE_VENT(16, true, "geothermal vent"), // geothermal vent mouth in cave stone
		TYPE_WALL_BUILT(17, false, "masonry wall"), // man-made masonry wall
		TYPE_PAVED(18, true, "paved corridor"), // man-made paved corridor floor
		TYPE_PLATE(19, true, "deck plate"), // facility steel deck floor
		TYPE_CATWALK(20, true, "catwalk"), // grated walkway; the void shows through
		TYPE_SHAFT(21, true, "drop shaft"), // vertical shaft: open, drops like a hole
		TYPE_PIPES(22, true, "pipe run"), // floor pipe run; slow clambering ground
		TYPE_AIRVENT(23, true, "vent grille"), // louvered ventilation grille in the deck
		TYPE_WALL_CONCRETE(24, false, "concrete wall"), // poured concrete facility wall
		TYPE_WALL_STEEL(25, false, "steel bulkhead"), // riveted steel bulkhead wall
		TYPE_DUCT(26, true, "crawl duct"), // crawlable air duct: small bodies only, concealed
		TYPE_CRYSTAL_BED(27, true, "shard bed"), // packed shard bed: walkable, but it slows
		TYPE_CRYSTAL_SPARSE(28, true, "scattered shards"), // scattered shards on stone: ordinary ground
		TYPE_SWITCH(29, true, "pressure plate"), // pressure-plate floor: a button a body stands on
		TYPE_DOCK(30, true, "charge dock"), // the steward drone's berth: a powered pad it parks on
		TYPE_ROCKY(31, true, "rocky grassland"), // stony ground that keeps a thin sward
		TYPE_SLUDGE(32, true, "waste sludge"), // the facility's spill: walkable, slow, and it burns
		TYPE_RAIL(33, true, "tram rail"), // the transit run: ordinary ground, and a road for the eye
		TYPE_SERVER(34, false, "server bank"), // racks: solid to a body, clear to the eye
		TYPE_TREADPLATE(35, true, "loading deck"), // heavy chequerplate: ground that carried weight
		TYPE_LIGHTGRATE(36, true, "lit grating"), // walkway over a lit plenum, not over a void
		TYPE_COLLAPSE(37, true, "collapsed deck"), // deck under fallen ceiling; slow to cross
		TYPE_COOLANT(38, true, "coolant run"), // rimed pipework: the plant's cold side
		TYPE_EXCHANGER(39, true, "heat exchanger"), // finned deck grille: where the heat goes
		TYPE_MESA(40, false, "mesa rock"), // red desert rock: the surface's own stone, strata on its faces
		TYPE_STALAGMITE(41, false, "stalagmite"), // cave columns: solid, and they block sight like the rock they grew from
		TYPE_CACTUS(42, false, "cactus"), // a standing desert plant: solid to a body, too narrow to hide behind
		TYPE_BONES(43, true, "bone field"), // walkable scatter of old remains on sand
		TYPE_HAZARD(44, true, "hazard striping"), // painted floor: keep-clear marking, walked like the deck it is painted on
		TYPE_CONVEYOR(45, true, "conveyor belt"), // the works' belt line; orientation from its own run
		TYPE_WINDOW(46, false, "window wall"), // glazing in a wall run: stops a body, not a look
		TYPE_DESK(47, false, "desk"), // a workstation: solid furniture, below any eye line
		TYPE_BUNK(48, false, "bunk"), // a made bed: someone sleeps here, or slept
		TYPE_WRECK(49, false, "dead machine"), // a machine that stopped, and stayed
		// Open air on a level above the ground: not a floor with an opening in
		// it, but the absence of any floor. A PIT is a cut in something and so
		// has a rim and a shaded throat; a VOID was never anything, so it has
		// no edge of its own -- what bounds it is the spire or hill standing in
		// it. It draws nothing at all, which is why it needs no painter: the
		// ground pass and the layer renderer both fall through to their default
		// and leave the art-pixels untouched, and an untouched pixel in a served
		// chunk already means "you can see down".
		TYPE_VOID(50, true, "open air");

		private int value;
		private boolean open;
		private final String label;
		private static Map<Integer, TileType> map = new HashMap<>();

		private TileType(int value, boolean open, String label) {
			this.value = value;
			this.open = open;
			this.label = label;
		}

		public boolean isOpen() {
			return open;
		}

		/** Distinct human-readable name for inspectors and tooling. Unlike the
		 *  enum constant ("floor", "vent"...), each label says what the tile IS
		 *  in the world — "grassland", "geothermal vent", "steel bulkhead" —
		 *  and stays tellable-apart as more tile types are added. */
		public String label() {
			return label;
		}

		static {
			for (TileType pageType : TileType.values()) {
				map.put(pageType.value, pageType);
			}
		}

		public static TileType valueOf(int pageType) {
			return map.get(pageType);
		}

		public int getValue() {
			return value;
		}
	}

}
