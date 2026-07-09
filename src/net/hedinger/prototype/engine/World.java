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

	public void think() {
		tick++;
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
	public boolean hasLOS(double x, double y, double z, double dir, double tx, double ty, double tz, double range,
			double fov) {
		if (!isValid(x, y, z)) {
			return false;
		}
		if (!isValid(tx, ty, tz)) {
			return false;
		}

		return levels[(int) z].hasLOS(x, y, dir, tx, ty, range, fov);
	}

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
		return levels[l].setTile(c, r, l, t);
	}

	public void setTile(int c, int r, int l) {
		levels[l].setTile(c, r, l);
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
		int c = hashCol(hash);
		int r = hashRow(hash);
		int l = hashLvl(hash);
		Tile t = getTile(c, r, l);
		if (t == null) {
			return new HashSet<Integer>();
		}
		return t.calcConnected(this, false);
	}

	public Stack<Integer> findPath(double x, double y, double z, double tx, double ty, double tz) {
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
			for (Integer i : getNeighbors(hash)) {
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
