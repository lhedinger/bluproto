package net.hedinger.prototype.engine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.PriorityQueue;
import java.util.Stack;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

public class World {

	Grid[] levels;
	int cols;
	int rows;
	int lvls;
	int spawnCounter = 0;

	// Monotonic simulation clock, advanced once per think(). Drives lazy
	// vegetation regrowth (and, later, day/night and field dynamics).
	private long tick = 0;

	public long getTick() {
		return tick;
	}

	boolean fogofwar = false;

	int max_view_depth = 3;

	IntObjectMap<Entity> entities;
	LinkedHashSet<Entity> spawnQueue;

	public World(int c, int r, int l) {

		if (c < 1 || r < 1 || l < 1) {
			return;
		}

		cols = c;
		rows = r;
		lvls = l;

		entities = new IntObjectMap<Entity>();

		levels = new Grid[l];
		for (int z = 0; z < l; z++) {
			levels[z] = new Grid(this, c, r, z);
		}

		spawnQueue = new LinkedHashSet<Entity>();

		init();
	}

	private void init() {
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				for (int z = 0; z < lvls; z++) {
					if (z > 0 && Utils.random() * 3 < 1) {
						setTile(x, y, z, Tile.TileType.TYPE_HOLE);
					} else {
						setTile(x, y, z, Tile.TileType.TYPE_WALL);
					}
				}
			}
		}
	}

	/**
	 * Per-tick role census: the entity table bucketed once per tick by level
	 * and ecological guild, so a creature's perception scans walk a dozen
	 * predators (or the corpses, or the switches) instead of the whole world.
	 * The full-table scans this replaces were O(population) per scanning
	 * creature — the sim's dominant cost once herds passed a few hundred.
	 *
	 * <p>Rebuilt at the top of {@link #think()} and read-only for the rest of
	 * the tick. A body that dies mid-tick is caught by the per-candidate
	 * liveness checks every consumer already makes; a body spawned mid-tick
	 * enters the world (and the census) at the next tick boundary anyway.
	 */
	public static final class Census {
		private final java.util.List<java.util.List<NPC>> creatures = new java.util.ArrayList<>();
		private final java.util.List<java.util.List<NPC>> predators = new java.util.ArrayList<>();
		private final java.util.List<java.util.List<NPC>> prey = new java.util.ArrayList<>();
		private final java.util.List<java.util.List<NPC>> corpses = new java.util.ArrayList<>();
		private final java.util.List<java.util.List<net.hedinger.prototype.entities.Switch>> switches =
				new java.util.ArrayList<>();

		private Census(int levels) {
			for (int z = 0; z < levels; z++) {
				creatures.add(new java.util.ArrayList<>());
				predators.add(new java.util.ArrayList<>());
				prey.add(new java.util.ArrayList<>());
				corpses.add(new java.util.ArrayList<>());
				switches.add(new java.util.ArrayList<>());
			}
		}

		static Census build(World w) {
			Census c = new Census(w.getLevels());
			for (Entity e : w.entities.values()) {
				if (e == null || e.isRemoved()) {
					continue;
				}
				int z = e.getLvl();
				if (z < 0 || z >= c.creatures.size()) {
					continue;
				}
				if (e instanceof net.hedinger.prototype.entities.Switch sw) {
					c.switches.get(z).add(sw);
					continue;
				}
				if (!(e instanceof NPC n) || e instanceof net.hedinger.prototype.entities.Item) {
					continue;
				}
				if (n.isDead()) {
					c.corpses.get(z).add(n); // scavengeable while it lasts
					continue;
				}
				c.creatures.get(z).add(n);
				String role = n.ecoRole();
				if ("predator".equals(role)) {
					c.predators.get(z).add(n);
				} else if ("herbivore".equals(role)) {
					c.prey.get(z).add(n);
				}
			}
			return c;
		}

		/** Live non-item bodies on the level (every guild, minded included). */
		public java.util.List<NPC> creatures(int z) {
			return creatures.get(z);
		}

		public java.util.List<NPC> predators(int z) {
			return predators.get(z);
		}

		public java.util.List<NPC> prey(int z) {
			return prey.get(z);
		}

		public java.util.List<NPC> corpses(int z) {
			return corpses.get(z);
		}

		public java.util.List<net.hedinger.prototype.entities.Switch> switches(int z) {
			return switches.get(z);
		}
	}

	private Census census;

	/** This tick's census; built lazily for callers that poke a freshly
	 *  constructed world before its first tick. */
	public Census census() {
		if (census == null) {
			census = Census.build(this);
		}
		return census;
	}

	public void think() {
		tick++;
		census = Census.build(this);
		int removecount = 0;
		for (Entity e : entities.values()) {
			if (e != null) {
				if (!e.run()) {
					removecount++;
				}
			}
		}

		for (Entity e : entities.values()) {
			if (e != null) {
				e.unmark();
			}
		}

		if (removecount * 2 > entities.size()) {
			IntObjectMap<Entity> clone = new IntObjectMap<Entity>();
			for (Entity e : entities.values()) {
				if (e != null && !e.isRemoved()) {
					clone.put(e.getID(), e);
				}
			}
			entities = clone;
		}

		if (spawnQueue.size() > 0) {
			for (Entity e : spawnQueue) {
				// An entity removed while still queued must not claim a tile
				// slot -- its markRemoved() purge already ran (or never will).
				if (e != null && !e.isRemoved()) {
					entities.put(e.getID(), e);
					getTile(e.getX(), e.getY(), e.getZ()).addEntity(e.getID());
				}
			}
			spawnQueue = new LinkedHashSet<Entity>();
		}

		for (int z = 0; z < lvls; z++) {
			levels[z].think(this);
		}
	}

	/**
	 * Paints a coherent fertility field over every tile so vegetation grows
	 * patchy -- lush blobs separated by poor ground. {@code frequency} sets the
	 * patch scale (~0.15 gives blobs a handful of tiles across). Deterministic
	 * from the seed and consumes no RNG state, so it does not perturb the order
	 * in which the rest of world generation draws from the RNG.
	 */
	public void generateFertility(double frequency) {
		for (int z = 0; z < lvls; z++) {
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					getTile(x, y, z).setFertility(Utils.noise2(x, y, frequency));
				}
			}
		}
	}

	public void render(Graphics g, View view, LayerRenderer layerRenderer) {
		Graphics2D g2 = (Graphics2D) g;

		for (int z = 0; z < lvls; z++) {
			levels[z].render(g, view, layerRenderer);
			g2.setStroke(new BasicStroke(0));
			g2.setColor(new Color(0, 0, 0, 150));
			if (z < view.getCamZ()) {
				g2.fillRect(0, 0, (int) g2.getClipBounds().getWidth(), (int) g2.getClipBounds().getHeight());
			}
		}

	}

	public void alignTiles() {
		for (int z = 0; z < lvls; z++) {
			levels[z].alignTiles();
		}
	}

	/**
	 * Entity Spawner NOTE: make sure to instantiate the Entity Class in
	 * parameter
	 *
	 * @param e
	 *            new Enity to be spawned
	 * @return if entity was spawned successfully
	 */

	public boolean spawnEntity(Entity e) {
		if (e == null) {
			return false;
		}
		if (!isValid(e.getCol(), e.getRow(), e.getLvl())) {
			return false;
		}

		e.buildID(this, spawnCounter);
		spawnCounter++;

		spawnQueue.add(e);

		return true;
	}

	public boolean hasFog() {
		return fogofwar;
	}

	/** Read-only view over all entities currently in the world. */
	public Iterable<Entity> getEntities() {
		return entities.values();
	}

	/** Registers a door. Doors are ordinary non-living entities (like crates):
	 *  they ride the entity stream -- think, snapshot, wire -- so a web viewer
	 *  sees them slide instead of meeting an invisible barrier. Perception
	 *  ignores them (every scan filters to NPCs). False if out of bounds. */
	public boolean addDoor(net.hedinger.prototype.entities.Door d) {
		return spawnEntity(d);
	}

	// ---- Pheromone: clouds are entities, not a per-tile scalar field --------

	/**
	 * Lays pheromone at a world point. If a {@link PheromoneCloud} on this level
	 * is already within {@link PheromoneCloud#MERGE_RADIUS}, it is reinforced (so
	 * repeated deposits build one growing peak); otherwise a fresh cloud spawns.
	 */
	public void depositPheromone(double x, double y, int z, double amount) {
		PheromoneCloud nearest = null;
		double best = PheromoneCloud.MERGE_RADIUS * PheromoneCloud.MERGE_RADIUS;
		for (Entity e : entities.values()) {
			if (e instanceof PheromoneCloud && !e.isRemoved() && e.getLvl() == z) {
				double dx = e.getX() - x, dy = e.getY() - y, d = dx * dx + dy * dy;
				if (d < best) {
					best = d;
					nearest = (PheromoneCloud) e;
				}
			}
		}
		if (nearest != null) {
			nearest.reinforce(amount);
		} else {
			spawnEntity(new PheromoneCloud(x, y, z, amount));
		}
	}

	/** Pheromone concentration sensed at a world point: the sum of every cloud on
	 *  this level, each with its radial falloff. */
	public double pheromoneAt(double x, double y, int z) {
		double sum = 0;
		for (Entity e : entities.values()) {
			if (e instanceof PheromoneCloud && !e.isRemoved() && e.getLvl() == z) {
				sum += ((PheromoneCloud) e).concentrationAt(x, y);
			}
		}
		return sum;
	}

	/**
	 * Heading from a point toward the strongest pheromone cloud whose centre is
	 * within {@code radius} tiles, for homing to a nest. Returns {@code NaN} when
	 * nothing is in range or the point is already essentially at that cloud's
	 * centre ("you are at the nest").
	 */
	public double pheromoneDirection(double x, double y, int z, double radius) {
		PheromoneCloud best = null;
		double bestStr = 0, rr = radius * radius;
		for (Entity e : entities.values()) {
			if (e instanceof PheromoneCloud && !e.isRemoved() && e.getLvl() == z) {
				PheromoneCloud c = (PheromoneCloud) e;
				double dx = c.getX() - x, dy = c.getY() - y;
				if (dx * dx + dy * dy <= rr && c.getStrength() > bestStr) {
					bestStr = c.getStrength();
					best = c;
				}
			}
		}
		if (best == null) {
			return Double.NaN;
		}
		double dx = best.getX() - x, dy = best.getY() - y;
		if (dx * dx + dy * dy < 0.36) {
			return Double.NaN; // already at the nest (~0.6 tile)
		}
		return Math.atan2(dy, dx);
	}

	/**
	 * Count of living actors (NPCs that are not dead), used by the HUD overlay.
	 * Excludes transient effects (bullets, explosions, sounds, grenades),
	 * structural entities (doors) and corpses in their death-span.
	 */
	public int getAliveCount() {
		int n = 0;
		for (Entity e : entities.values()) {
			if (e != null && e instanceof NPC && !e.isDead()) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Looks for any visible Entity (follows LOS protocols)
	 *
	 * @param x
	 *            vectorized position of Searcher
	 * @param y
	 *            vectorized position of Searcher
	 * @param z
	 *            vectorized position of Searcher
	 * @param dir
	 *            direction of Searcher (radians)
	 * @param range
	 *            maxium search range for of Searcher (-1 = infinite)
	 * @param fov
	 *            field of view of Searcher (PI = 360 degree search, -1 = See
	 *            through walls)
	 * @param types
	 *            Entity types Searcher will look for ONLY
	 * @param ID
	 *            unique id of the Searcher
	 * @return TreeMap of all found Entities (empty if none are found)
	 */
	public TreeMap<Double, Entity> searchEntity(double x, double y, double z, double dir, double range, double fov,
			String[] types, boolean include, int ID) {
		if (!isValid(x, y, z)) {
			return new TreeMap<Double, Entity>();
		}

		if (types == null) {
			return new TreeMap<Double, Entity>();
		}

		// Restrict the candidate set to entities bucketed in nearby tiles
		// instead of scanning every entity in the world. hasLOS() still applies
		// the exact Euclidean range filter, so widening the tile box by one
		// guarantees we never miss an in-range entity (identical results, O(k)
		// instead of O(n)). A negative range means "unbounded" -> full scan.
		if (range < 0) {
			return levels[(int) z].searchEntity(x, y, dir, range, fov, types, include, ID);
		}

		// Gather candidates straight from the tile box around the searcher --
		// each entity lives in exactly one tile, so there is nothing to dedup.
		// hasLOS() (inside considerEntity) applies the exact range filter.
		// floor(range)+1 rings are a provable superset of everything within
		// Euclidean range (|ex-x| <= range implies a tile-index delta of at
		// most floor(range)+1), so fractional ranges -- bullets search with
		// range = velocity <= 0.4 -- scan 3x3 instead of 5x5.
		TreeMap<Double, Entity> result = new TreeMap<Double, Entity>();
		int r = (int) Math.floor(range) + 1;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				Tile t = getTile(x + dx, y + dy, z);
				if (t == null) {
					continue;
				}
				int n = t.getEntityCount();
				for (int i = 0; i < n; i++) {
					considerEntity(t.getEntityId(i), x, y, z, dir, range, fov, types, include, ID, result);
				}
			}
		}
		return result;
	}

	/**
	 * Everything within a Euclidean radius, <b>ignoring line of sight</b> and
	 * facing — the query a sound needs.
	 *
	 * <p>{@link #searchEntity} is a <i>sight</i> query: it runs every candidate
	 * through {@link #hasLOS}, so a wall between the searcher and the target hides
	 * it. That is right for eyes and wrong for ears. Hearing that stopped at walls
	 * would be nothing but short-range omnidirectional sight, and the one thing
	 * that makes the channel worth its slot — that a scream reaches you from
	 * somewhere you cannot see — would silently not happen.
	 *
	 * <p>Same tile-box gather as the sighted search, so it stays O(k) in local
	 * density rather than scanning the world.
	 */
	public TreeMap<Double, Entity> entitiesWithin(double x, double y, double z, double range, int ID) {
		TreeMap<Double, Entity> result = new TreeMap<Double, Entity>();
		if (!isValid(x, y, z) || range < 0) {
			return result;
		}
		int r = (int) Math.floor(range) + 1;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				Tile t = getTile(x + dx, y + dy, z);
				if (t == null) {
					continue;
				}
				int n = t.getEntityCount();
				for (int i = 0; i < n; i++) {
					Entity e = entities.get(t.getEntityId(i));
					if (e == null || e.getLvl() != (int) z || e.isDead() || e.getID() == ID) {
						continue;
					}
					double d = distance(x, y, z, e.getX(), e.getY(), e.getZ());
					if (d <= range) {
						result.put(d, e);
					}
				}
			}
		}
		return result;
	}

	// Shared per-candidate test for searchEntity's two gather paths, so both
	// produce identical results.
	private void considerEntity(int i, double x, double y, double z, double dir, double range, double fov,
			String[] types, boolean include, int ID, TreeMap<Double, Entity> result) {
		Entity e = entities.get(i);
		if (e != null && e.getLvl() == (int) z && !e.isDead() && ID != e.getID()) {
			if (filterType(e.getEntityTypeName(), types, include)) {
				if (hasLOS(x, y, z, dir, e.getX(), e.getY(), e.getZ(), range, fov)) {
					result.put(distance(x, y, z, e.getX(), e.getY(), e.getZ()), e);
				}
			}
		}
	}

	// Tiles of the 3x3 neighbourhood, nearest-first (centre, then the ring).
	private static final int[][] NEIGHBOUR_ORDER = { { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 }, { 1, 1 },
			{ 1, -1 }, { -1, 1 }, { -1, -1 } };

	/**
	 * Nearest-K neighbour search that bounds cost to O(k) regardless of local
	 * density. It walks the surrounding tiles nearest-first and stops once it has
	 * k results and has examined a fixed candidate budget -- so a dense pile-up
	 * costs the same as a light crowd. Because the centre tile (closest entities)
	 * is visited first, the budget is spent on the nearest candidates, making
	 * this a close approximation of the true nearest-k. At low density (fewer
	 * than the budget of candidates) it returns exactly what a full radial scan
	 * would.
	 */
	public TreeMap<Double, NPC> searchNearestNPC(double x, double y, double z, double dir, double range, double fov,
			int ID, int k) {
		TreeMap<Double, NPC> result = new TreeMap<Double, NPC>();
		if (!isValid(x, y, z) || k <= 0) {
			return result;
		}
		// Collect the nearest k in primitive insertion-sorted arrays; the boxed
		// TreeMap is built once at the end (<= k inserts) instead of paying an
		// insert-plus-evict of boxed keys per candidate.
		double[] dist = new double[k];
		NPC[] found = new NPC[k];
		int count = 0;
		int budget = k * 8; // density-independent cap on candidates examined
		int examined = 0;
		for (int[] d : NEIGHBOUR_ORDER) {
			Tile t = getTile(x + d[0], y + d[1], z);
			if (t == null) {
				continue;
			}
			int occ = t.getEntityCount();
			for (int oi = 0; oi < occ; oi++) {
				if (examined >= budget && count >= k) {
					return buildResult(result, dist, found, count);
				}
				examined++;
				Entity e = entities.get(t.getEntityId(oi));
				if (e == null || e.getLvl() != (int) z || !(e instanceof NPC) || e.isDead() || ID == e.getID()) {
					continue;
				}
				if (!hasLOS(x, y, z, dir, e.getX(), e.getY(), e.getZ(), range, fov)) {
					continue;
				}
				double dd = distance(x, y, z, e.getX(), e.getY(), e.getZ());
				if (count == k && dd >= dist[k - 1]) {
					continue; // farther than the current k nearest
				}
				int pos = count < k ? count : k - 1; // slot to place/overwrite
				while (pos > 0 && dist[pos - 1] > dd) {
					dist[pos] = dist[pos - 1];
					found[pos] = found[pos - 1];
					pos--;
				}
				dist[pos] = dd;
				found[pos] = (NPC) e;
				if (count < k) {
					count++;
				}
			}
		}
		return buildResult(result, dist, found, count);
	}

	private static TreeMap<Double, NPC> buildResult(TreeMap<Double, NPC> result, double[] dist, NPC[] found,
			int count) {
		for (int i = 0; i < count; i++) {
			result.put(dist[i], found[i]);
		}
		return result;
	}

	/**
	 * Checks to see if there is a clean line of sight between Searcher and
	 * Target
	 *
	 * @param x
	 *            vectorized position of Searcher
	 * @param y
	 *            vectorized position of Searcher
	 * @param z
	 *            vectorized position of Searcher
	 * @param dir
	 *            direction fo Searcher
	 * @param x2
	 *            vectorized position of Target
	 * @param y2
	 *            vectorized position of Target
	 * @param z2
	 *            vectorized position of Target
	 * @param range
	 *            maxium search range for of Searcher (-1 = infinite)
	 * @param fov
	 *            field of view of Searcher (PI = 360 degree search)
	 * @return if there is a line of sight (LOS)
	 */
	/**
	 * Line of sight from one point to another, within {@code range} and inside a
	 * cone of {@code fov} either side of {@code dir}.
	 *
	 * <p>A floor is opaque. This used to trace within the SOURCE level's plane
	 * and ignore {@code tz} altogether, so anything one storey away was answered
	 * as though it were standing in the same room — a body under your feet read
	 * as in plain view. That is what let the steward's drone shoot an animal
	 * through the deck plate: the strike asks for sight, sight said yes, and the
	 * floor between them was never consulted by anything.
	 *
	 * <p>The exception is an opening, and a ramp is the opening this world has:
	 * a slope joining two floors is a hole in the ceiling you can see up and
	 * down through. So a cross-floor sighting needs a ramp joining exactly those
	 * two floors within the same radius the sighting itself is bounded by. Far
	 * from a stairwell that is a floor; beside one it is a stairwell.
	 *
	 * <p>Adjacent floors only. Ramps join a level to the one above or below, so
	 * two storeys of separation is two floors and no amount of ramp helps.
	 *
	 * <p>Movement was the thing to check before landing this, because {@code
	 * isValidMoveDestination} asks for sight of the tile a body is stepping onto
	 * and a staircase is a destination on another level — opaque floors looked
	 * like they would stop every creature in the world from climbing anything.
	 * They do not: a body's level turns over as it crosses the ramp's edge, so
	 * the destination it asks about is still on its own floor when it asks.
	 * Checked rather than assumed, by disabling the exception and watching
	 * {@code RampsRunWhicheverWayTheyAreLaid} keep passing. The exception is
	 * here because a stairwell is a hole, not to prop up movement.
	 */
	public boolean hasLOS(double x, double y, double z, double dir, double tx, double ty, double tz, double range,
			double fov) {
		if (!isValid(x, y, z)) {
			return false;
		}
		if (!isValid(tx, ty, tz)) {
			return false;
		}
		if ((int) z != (int) tz && !openBetweenFloors(x, y, (int) z, (int) tz, range)) {
			return false;
		}

		return levels[(int) z].hasLOS(x, y, dir, tx, ty, range, fov);
	}

	/**
	 * Whether a ramp joining these two floors stands within {@code range} of
	 * (x, y) — the one way sight passes between storeys.
	 *
	 * <p>Answered off a per-level list of ramp tiles rather than by sweeping the
	 * radius, because the radius can be ninety-nine and the ramps are a handful.
	 * A negative range means unbounded, as everywhere else here.
	 */
	private boolean openBetweenFloors(double x, double y, int z, int tz, double range) {
		if (Math.abs(z - tz) != 1) {
			return false; // a ramp reaches one floor, not two
		}
		int[] joining = ramps(z, tz > z);
		if (joining.length == 0) {
			return false;
		}
		if (range < 0) {
			return true; // unbounded sight: any ramp on this floor will do
		}
		double rr = range * range;
		for (int packed : joining) {
			double dx = (packed >>> 16) + 0.5 - x;
			double dy = (packed & 0xffff) + 0.5 - y;
			if (dx * dx + dy * dy <= rr) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Every ramp on level {@code l} that leads up (or down), packed
	 * {@code col<<16|row}.
	 *
	 * <p>Built lazily and dropped whenever a tile is written, which is the same
	 * bargain the water fields make further down this file — terrain barely
	 * moves once a world is generated, and the cost of being wrong about that
	 * would be a stale answer about a staircase.
	 */
	private int[] ramps(int l, boolean up) {
		if (rampsUp == null) {
			rampsUp = new int[levels.length][];
			rampsDown = new int[levels.length][];
		}
		int[][] cache = up ? rampsUp : rampsDown;
		if (cache[l] == null) {
			Tile.TileType want = up ? Tile.TileType.TYPE_RAMPUP : Tile.TileType.TYPE_RAMPDOWN;
			java.util.List<Integer> found = new java.util.ArrayList<Integer>();
			for (int c = 0; c < cols; c++) {
				for (int r = 0; r < rows; r++) {
					Tile t = getTile(c, r, l);
					if (t != null && t.getType() == want) {
						found.add((c << 16) | r);
					}
				}
			}
			int[] out = new int[found.size()];
			for (int i = 0; i < out.length; i++) {
				out[i] = found.get(i);
			}
			cache[l] = out;
		}
		return cache[l];
	}

	/** Ramp tiles per level, for {@link #openBetweenFloors}. Null until asked
	 *  for, and thrown away whenever a tile is written. */
	private int[][] rampsUp, rampsDown;

	public boolean isOpen(double x, double y, double z) {
		if (!isValid(x, y, z)) {
			return false;
		}
		return !getTile(x, y, z).isSolid();
	}

	public boolean isConnectedSpace(double x, double y, double z, double x2, double y2, double z2) {
		Tile t = getTile(x, y, z);
		if (t == null) {
			return false;
		}
		return t.isConnected(this, x2, y2, z2, false, false);
	}

	public boolean isValid(int c, int r, int l) {
		if (c < 0 || c >= cols) {
			return false;
		}
		if (r < 0 || r >= rows) {
			return false;
		}
		if (l < 0 || l >= lvls) {
			return false;
		}
		if (levels[l] == null) {
			return false;
		}
		return true;
	}

	public boolean isValid(double x, double y, double z) {
		if (x < 0 || x >= cols) {
			return false;
		}
		if (y < 0 || y >= rows) {
			return false;
		}
		if (z < 0 || z >= lvls) {
			return false;
		}
		if (levels[(int) (z)] == null) {
			return false;
		}
		return true;
	}

	public boolean setTile(int h, Tile.TileType t) {
		return setTile(hashCol(h), hashRow(h), hashLvl(h), t);
	}

	public boolean setTile(int c, int r, int l, Tile.TileType t) {
		if (!this.isValid(c, r, l)) {
			return false;
		}
		rampsUp = null; // a written tile may have been, or become, a staircase
		rampsDown = null;
		return levels[l].setTile(c, r, l, t);
	}

	public void setTile(int c, int r, int l) {
		levels[l].setTile(c, r, l);
	}

	/**
	 * Per-level route-to-water lookup, built lazily by one multi-source BFS out
	 * from every water tile. For each tile it holds the water it should head for
	 * and how many steps away that water is, so a thirsty creature gets an answer
	 * in O(1) — the per-tick tile scans this replaces were quadratic misery, and
	 * terrain never changes during a run, so one build serves the world's life.
	 *
	 * <p>The flood only crosses ground the body could actually cross. That is the
	 * whole point: the old field spread through solid rock, so a creature with a
	 * lake on the far side of a wall was told the lake was near and walked into
	 * the wall — measured, between 15 and 28 per cent of every thirsty tick was
	 * spent pressed against terrain, going nowhere. A tile that genuinely cannot
	 * reach water now says so, and the creature searches instead of shoving.
	 *
	 * <p>Keeping the step count is what lets a body walk AROUND an obstacle rather
	 * than at it: the neighbouring tile with a smaller count is always a step
	 * closer along a real path, so following that gradient is a route.
	 */
	private static final class WaterField {
		final int[][] source; // packed x<<16|y of the water this tile heads for, or -1
		final int[][] steps; // BFS steps to it along passable ground, or -1

		WaterField(int[][] source, int[][] steps) {
			this.source = source;
			this.steps = steps;
		}
	}

	private WaterField[] waterWalk, waterFly;

	/** Packed (x&lt;&lt;16|y) of the nearest water/shallows tile a walking body could
	 *  reach from {@code (x,y,z)}, or -1 when none is reachable. */
	public int nearestWaterTile(double x, double y, double z) {
		return nearestWaterTile(x, y, z, false);
	}

	/** As {@link #nearestWaterTile(double, double, double)}, for a body that may
	 *  or may not fly — a flyer crosses water and drops that would stop a walker. */
	public int nearestWaterTile(double x, double y, double z, boolean flying) {
		WaterField f = isValid(x, y, z) ? waterField(z, flying) : null;
		return f == null ? -1 : f.source[(int) x][(int) y];
	}

	/** Steps along passable ground from {@code (x,y,z)} to the water it should head
	 *  for, or -1 when none is reachable. This is path length, not a straight line:
	 *  water just past a wall is correctly reported as the long way round. */
	public int waterStepDistance(double x, double y, double z, boolean flying) {
		WaterField f = isValid(x, y, z) ? waterField(z, flying) : null;
		return f == null ? -1 : f.steps[(int) x][(int) y];
	}

	/**
	 * Heading from {@code (x,y,z)} toward the neighbouring tile that is one step
	 * closer to water, or NaN when no water is reachable (or this tile is already
	 * at it). Following this each tick walks a real route, corners and all.
	 *
	 * <p>Neighbours are tried in a fixed order and only a strictly smaller step
	 * count wins, so ties break identically on every replay.
	 */
	public double waterFlowDirection(double x, double y, double z, boolean flying) {
		WaterField f = isValid(x, y, z) ? waterField(z, flying) : null;
		if (f == null) {
			return Double.NaN;
		}
		int cx = (int) x, cy = (int) y;
		int here = f.steps[cx][cy];
		if (here <= 0) {
			return Double.NaN; // no route, or standing at the water already
		}
		int c = getColums(), r = getRows();
		int bx = -1, by = -1, best = here;
		for (int k = 0; k < 4; k++) {
			int nx = cx + (k == 0 ? 1 : k == 1 ? -1 : 0);
			int ny = cy + (k == 2 ? 1 : k == 3 ? -1 : 0);
			if (nx < 0 || ny < 0 || nx >= c || ny >= r) {
				continue;
			}
			int d = f.steps[nx][ny];
			if (d >= 0 && d < best) {
				best = d;
				bx = nx;
				by = ny;
			}
		}
		if (bx < 0) {
			return Double.NaN;
		}
		return Math.atan2(by + 0.5 - y, bx + 0.5 - x);
	}

	private WaterField waterField(double z, boolean flying) {
		int l = (int) z;
		if (l < 0 || l >= levels.length) {
			return null;
		}
		WaterField[] cache = flying ? waterFly : waterWalk;
		if (cache == null) {
			cache = new WaterField[levels.length];
			if (flying) {
				waterFly = cache;
			} else {
				waterWalk = cache;
			}
		}
		if (cache[l] == null) {
			cache[l] = buildWaterField(l, flying);
		}
		return cache[l];
	}

	private WaterField buildWaterField(int l, boolean flying) {
		int c = getColums(), r = getRows();
		int[][] near = new int[c][r];
		int[][] steps = new int[c][r];
		java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
		for (int x = 0; x < c; x++) {
			for (int y = 0; y < r; y++) {
				Tile.TileType t = getTile(x, y, l).getType();
				boolean wet = t == Tile.TileType.TYPE_WATER || t == Tile.TileType.TYPE_SHALLOWS;
				near[x][y] = wet ? (x << 16 | y) : -1;
				steps[x][y] = wet ? 0 : -1;
				if (wet) {
					q.add(new int[] { x, y });
				}
			}
		}
		while (!q.isEmpty()) {
			int[] p = q.poll();
			int source = near[p[0]][p[1]];
			int d = steps[p[0]][p[1]];
			for (int k = 0; k < 4; k++) {
				int nx = p[0] + (k == 0 ? 1 : k == 1 ? -1 : 0);
				int ny = p[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
				if (nx < 0 || ny < 0 || nx >= c || ny >= r || near[nx][ny] != -1) {
					continue;
				}
				// Only spread onto ground this kind of body could stand on or cross.
				// A tile the creature cannot enter is not a route to anywhere.
				Tile t = getTile(nx, ny, l);
				if (!(flying ? t.isFlyable() : t.isWalkable())) {
					continue;
				}
				near[nx][ny] = source;
				steps[nx][ny] = d + 1;
				q.add(new int[] { nx, ny });
			}
		}
		return new WaterField(near, steps);
	}

	public Tile getTile(double x, double y, double z) {
		if (!isValid(x, y, z)) {
			return new Tile(toCol(x), toRow(y), toLvl(z), Tile.TileType.TYPE_WALL);
		}
		return getTile((int) x, (int) y, (int) z);
	}

	public Tile getTile(int c, int r, int l) {
		if (!isValid(c, r, l)) {
			return new Tile(c, r, l, Tile.TileType.TYPE_WALL);
		}
		return levels[l].getTile(c, r);
	}

	public static int toCol(double x) {
		if (x < 0) {
			return (int) (x - 1);
		}
		return (int) (x);
	}

	public static int toRow(double y) {
		if (y < 0) {
			return (int) (y - 1);
		}
		return (int) (y);
	}

	public static int toLvl(double z) {
		if (z < 0) {
			return 0;
		}
		return (int) (z);
	}

	public HashSet<Integer> getNeighbors(int hash) {
		return getNeighbors(hash, false);
	}

	/** As above, planning as though every door on the way were open — see
	 *  {@link Tile#calcConnected(World, boolean, boolean)}. */
	public HashSet<Integer> getNeighbors(int hash, boolean throughDoors) {
		return getNeighbors(hash, throughDoors, 0);
	}

	/** As above, for a body of {@code clearance} pixels — 0 asks for the
	 *  clearance-blind graph. */
	public HashSet<Integer> getNeighbors(int hash, boolean throughDoors, int clearance) {
		int c = hashCol(hash);
		int r = hashRow(hash);
		int l = hashLvl(hash);
		Tile t = getTile(c, r, l);
		if (t == null) {
			return new HashSet<Integer>();
		}
		return t.calcConnected(this, false, throughDoors, clearance);
	}

	public Stack<Integer> findPath(double x, double y, double z, double tx, double ty, double tz) {
		return findPath(x, y, z, tx, ty, tz, false);
	}

	/**
	 * A* over the tile graph, from one world point to another. Crosses levels
	 * where a ramp joins them (see {@link Tile#calcConnected(World, boolean,
	 * boolean)}), so a goal on another floor is reachable if a body could walk
	 * there.
	 *
	 * <p>{@code throughDoors} plans as though the doors on the way were open —
	 * the map as it looks to something that opens them by arriving. Everything
	 * else gets the default: a shut door is impassable and the route goes
	 * round it or nowhere.
	 */
	public Stack<Integer> findPath(double x, double y, double z, double tx, double ty, double tz,
			boolean throughDoors) {
		return findPath(x, y, z, tx, ty, tz, throughDoors, 0);
	}

	/** As above, for a body of {@code clearance} pixels: ground too tight for
	 *  it (a crawl duct, a shard bed) is left out of the graph, so the route
	 *  returned is one this body can actually fly. 0 is clearance-blind. */
	public Stack<Integer> findPath(double x, double y, double z, double tx, double ty, double tz,
			boolean throughDoors, int clearance) {
		if (!isValid(x, y, z) || !isValid(tx, ty, tz)) {
			return new Stack<Integer>();
		}
		int start = hashCode(x, y, z);
		int goal = hashCode(tx, ty, tz);
		if (start == -1) {
			return new Stack<Integer>();
		}
		if (goal == -1) {
			return new Stack<Integer>();
		}
		HashSet<Integer> closedset = new HashSet<Integer>();
		HashSet<Integer> openset = new HashSet<Integer>();
		HashMap<Integer, Integer> camefrom = new HashMap<Integer, Integer>();
		HashMap<Integer, Double> fdist = new HashMap<Integer, Double>();
		HashMap<Integer, Double> gdist = new HashMap<Integer, Double>();
		HashMap<Integer, Double> hdist = new HashMap<Integer, Double>();
		// Order the frontier by f-score with a binary heap instead of rescanning
		// the whole open set for the minimum on every step (O(E log V) vs the
		// former O(V^2)). Improved nodes are re-pushed; the closed set skips the
		// resulting stale duplicates when they surface.
		PriorityQueue<Integer> frontier = new PriorityQueue<Integer>((a, b) -> Double.compare(fdist.get(a), fdist.get(b)));
		gdist.put(start, 0.0);
		hdist.put(start, distance(x, y, z, tx, ty, tz)); // estimate of
		// distance
		fdist.put(start, hdist.get(start)); // = hdist[start]
		openset.add(start);// add start node
		frontier.add(start);
		while (!frontier.isEmpty()) {
			int hash = frontier.poll();
			if (closedset.contains(hash)) {
				continue; // stale duplicate from an earlier decrease-key
			}
			if (hash == goal) {
				// drawPath(camefrom, goal);
				return buildStack(camefrom, goal);
			}
			openset.remove(hash);
			closedset.add(hash);
			for (Integer i : getNeighbors(hash, throughDoors, clearance)) {
				if (!closedset.contains(i)) {
					double tempdist = gdist.get(hash) + distance(hash, i);
					boolean tempisbetter = false;
					if (!openset.contains(i)) {
						openset.add(i);
						tempisbetter = true;
					} else if (tempdist < gdist.get(i)) {
						tempisbetter = true;
					}
					if (tempisbetter) {
						camefrom.put(i, hash);
						gdist.put(i, tempdist);
						hdist.put(i, distance(i, goal));
						fdist.put(i, gdist.get(i) + hdist.get(i));
						frontier.add(i);
					}
				}
			}
		}
		return new Stack<Integer>();
	}

	private Stack<Integer> buildStack(HashMap<Integer, Integer> camefrom, int node) {
		Stack<Integer> stack = new Stack<Integer>();
		Integer i = node;
		if (i == null) {
			return null;
		}

		stack.push(i);
		while (i != null) {
			stack.push(i);
			i = camefrom.get(i);
			if (!camefrom.containsKey(i)) {
				i = null;
			}
		}

		return stack;
	}

	public double distance(int node1, int node2) {
		int c1 = hashCol(node1);
		int r1 = hashRow(node1);
		int l1 = hashLvl(node1);
		int c2 = hashCol(node2);
		int r2 = hashRow(node2);
		int l2 = hashLvl(node2);
		if (!isValid(c1, r1, l1) || !isValid(c2, r2, l2)) {
			return 0;
		}

		return distance(c1, r1, l1, c2, r2, l2);
	}

	public double distance(double dx, double dy, double dz) {
		return Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2) + Math.pow(dz, 2));
	}

	public double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
		return distance(Math.abs(x1 - x2), Math.abs(y1 - y2), Math.abs(z1 - z2));
	}

	public int hashCode(int c, int r, int l) {
		if (!isValid(c, r, l)) {
			return -1;
		}
		return c + r * cols + l * rows * cols;
	}

	public int hashCode(double x, double y, double z) {
		if (!isValid(x, y, z)) {
			return -1;
		}
		return hashCode((int) (x), (int) (y), (int) (z));
	}

	public int hashCol(int hc) {
		return hc - hashLvl(hc) * rows * cols - hashRow(hc) * cols;
	}

	public int hashRow(int hc) {
		return (hc - hashLvl(hc) * rows * cols) / cols;
	}

	public int hashLvl(int hc) {
		return hc / (rows * cols);
	}

	public int getColums() {
		return cols;
	}

	public int getRows() {
		return rows;
	}

	public int getLevels() {
		return lvls;
	}

	/**
	 * Case-insensitive substring test with no String allocation (ASCII-safe).
	 * Type names and filters are constant ASCII strings, so this matches
	 * {@code a.toLowerCase().contains(b.toLowerCase())} without the per-call
	 * allocations that dominated a dense-tick profile.
	 */
	static boolean containsIgnoreCase(String haystack, String needle) {
		int n = needle.length();
		if (n == 0) {
			return true;
		}
		int max = haystack.length() - n;
		for (int i = 0; i <= max; i++) {
			if (haystack.regionMatches(true, i, needle, 0, n)) {
				return true;
			}
		}
		return false;
	}

	public static boolean filterType(String type, String[] filter, boolean include) {
		if (include) {
			return includesType(type, filter);
		}

		return excludesType(type, filter);
	}

	public static boolean includesType(String type, String[] filter) {
		for (int i = 0; i < filter.length; i++) {
			if (containsIgnoreCase(filter[i], type)) {
				return true;
			}
		}
		return false;
	}

	public static boolean excludesType(String type, String[] filter) {
		for (int i = 0; i < filter.length; i++) {
			if (containsIgnoreCase(filter[i], type)) {
				return false;
			}
		}
		return true;
	}
}
