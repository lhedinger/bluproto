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
	public static final double VEG_MAX = 1.0;
	/** Default logistic growth rate (per tick): larger => faster recovery. */
	public static final double VEG_REGROW = 0.002;
	/** A tile grazed below {@link #DEPLETION_LEVEL} of its cap pauses this many
	 *  ticks (~1 min at 33 t/s) before it starts to recover; a tile with grass to
	 *  spare resumes at once. */
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

	/** True where vegetation can grow: grassy floor, or cave fungus beds --
	 *  both feed grazers through the same logistic regrowth model. */
	public boolean growsVegetation() {
		return type == TileType.TYPE_FLOOR || type == TileType.TYPE_FUNGUS;
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
		HashSet<Integer> connected = new HashSet<Integer>();
		for (int x = -1; x <= 1; x++) {
			for (int y = -1; y <= 1; y++) {
				if (!(x == 0 && y == 0)) {
					if (isConnected(w, col + x, row + y, lvl, diagonal, false)) {
						connected.add(w.hashCode(col + x, row + y, lvl));
					}
				}
			}
		}
		return connected;
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
				return false;
			}
		}

		return true;
	}

	public boolean isConnected(World w, double x, double y, double z, boolean diagonal, boolean floorsOnly) {
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
			// Only a ramp joins two levels, and only along its own slope. A ramp is
			// floor that spans the gap: a RAMPUP runs west-low to east-high, a
			// RAMPDOWN east-high to west-low, so walking off the high side leaves you
			// a level up and off the low side a level down. Everything else is a
			// cliff. (dx is source minus destination, so dx < 0 is a step east.)
			if (temp == null || dy != 0 || Math.abs(dx) != 1) {
				return false;
			}
			if (dz < 0 && !(type == TYPE_RAMPUP && dx < 0)) {
				return false; // destination above: only east, off a RAMPUP's top
			}
			if (dz > 0 && !(type == TYPE_RAMPDOWN && dx > 0)) {
				return false; // destination below: only west, off a RAMPDOWN's foot
			}
			return floorsOnly ? temp.isWalkable() : !temp.isSolid();
		} else {
			if (floorsOnly && !temp.isWalkable()) {
				return false;
			}
			if (!floorsOnly && temp.isSolid()) {
				return false;
			}

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

			if (Math.abs(dx) * Math.abs(dy) == 1) // diagonal
			{
				if (!isWalkable() && diagonal) {
					return true;
				}
				if (!isConnected(w, col - dx, row, lvl, false, floorsOnly)
						|| !isConnected(w, col, row - dy, lvl, false, floorsOnly)) {
					return false;
				}
			}

		}

		return true;
	}

	public boolean isWalkable() {
		return type.isOpen() && type != TileType.TYPE_HOLE && type != TileType.TYPE_WATER;
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

	/** Movement multiplier for an entity standing on this tile: mud drags
	 *  hardest, reeds tangle, rubble is slow scrambling ground. */
	public double speedFactor() {
		switch (type) {
		case TYPE_MUD:
			return 0.4;
		case TYPE_REEDS:
			return 0.5;
		case TYPE_RUBBLE:
			return 0.6;
		default:
			return 1.0;
		}
	}

	/** True if this tile blocks line of sight (walls, thicket cover, or the
	 *  reed beds at the water's edge). */
	public boolean blocksSight() {
		return isSolid() || type == TileType.TYPE_COVER || type == TileType.TYPE_REEDS;
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
		TYPE_HOLE(0, true),
		TYPE_FLOOR(1, true),
		TYPE_WALL(2, false),
		TYPE_RAMPUP(3, true),
		TYPE_RAMPDOWN(4, true),
		TYPE_WATER(5, true), // open (flyers pass) but not walkable
		TYPE_MUD(6, true), // walkable, slows movement
		TYPE_COVER(7, true), // walkable, blocks line of sight
		TYPE_STONE(8, true), // walkable bare rock floor; grows no vegetation
		TYPE_FUNGUS(9, true), // cave floor growing grazeable fungus
		TYPE_RUBBLE(10, true), // broken rock; slows movement, sight passes
		TYPE_SAND(11, true), // bare loose ground; grows no vegetation
		TYPE_REEDS(12, true); // wetland stalks; slow AND sight-blocking

		private int value;
		private boolean open;
		private static Map<Integer, TileType> map = new HashMap<>();

		private TileType(int value, boolean open) {
			this.value = value;
			this.open = open;
		}

		public boolean isOpen() {
			return open;
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
