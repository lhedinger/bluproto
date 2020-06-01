package net.hedinger.prototype.engine;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;

import net.hedinger.prototype.entities.NPC;

public class World {

	Grid[] levels;
	int cols;
	int rows;
	int lvls;
	int spawnCounter = 0;
	protected Calendar clock;
	protected long gamma = 0;
	protected final long delta = 1000; // milliseconds

	boolean fogofwar = false;

	int peepcount = 0;
	int peepcount_max = 0;
	int max_view_depth = 3;

	HashMap<Integer, Entity> entities;
	HashSet<Entity> spawnQueue;

	public World(int c, int r, int l) {

		if (c < 1 || r < 1 || l < 1) {
			return;
		}

		cols = c;
		rows = r;
		lvls = l;

		entities = new HashMap<Integer, Entity>();

		levels = new Grid[l];
		for (int z = 0; z < l; z++) {
			levels[z] = new Grid(this, c, r, z);
		}

		spawnQueue = new HashSet<Entity>();

		init();
	}

	private void init() {
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				for (int z = 0; z < lvls; z++) {
					if (z > 0 && Math.random() * 3 < 1) {
						setTile(x, y, z, Tile.TileType.TYPE_HOLE);
					} else {
						setTile(x, y, z, Tile.TileType.TYPE_WALL);
					}
				}
			}
		}
	}

	public void think() {
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
			HashMap<Integer, Entity> clone = new HashMap<Integer, Entity>();
			for (Integer i : entities.keySet()) {
				Entity e = entities.get(i);
				if (e != null) {
					if (!e.isRemoved()) {
						clone.put(i, e);
					}
				}
			}
			entities = clone;
		}

		if (spawnQueue.size() > 0) {
			for (Entity e : spawnQueue) {
				if (e != null) {
					entities.put(e.getID(), e);
					getTile(e.getX(), e.getY(), e.getZ()).addEntity(e.getID());
				}
			}
			spawnQueue = null;
			spawnQueue = new HashSet<Entity>();
		}

		for (int z = 0; z < lvls; z++) {
			levels[z].think(this);
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

	public Set<Integer> getRadialEntities(double tx, double ty, double tz, double radius) {
		// return getEntityTiles(x, y, z, radius);
		Set<Integer> ent = new HashSet<Integer>();
		Tile t = getTile(tx, ty, tz);
		if (t == null) {
			return ent;
		}

		ent.addAll(t.getEntities());
		int r = (int) radius;

		int x = -r;
		int y = -r;

		do {
			t = getTile(tx + x, ty + y, tz);

			if (t != null) {
				ent.addAll(t.getEntities());
			}

			x++;
			if (x > r && y < r) {
				x = -r;
				y++;
			}

		} while (x <= r);

		return ent;
	}

	public Set<Integer> getEntityTiles(double x, double y, double z, double radius) {
		Set<Integer> list = new HashSet<Integer>();

		list.addAll(getEntityTile(x, y, z));

		list.addAll(getEntityTiles(x, y, z, radius, (int) x - 1, (int) y - 1, (int) z, 1));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x, (int) y - 1, (int) z, 2));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x + 1, (int) y - 1, (int) z, 3));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x - 1, (int) y, (int) z, 4));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x + 1, (int) y, (int) z, 5));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x - 1, (int) y + 1, (int) z, 6));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x, (int) y + 1, (int) z, 7));
		list.addAll(getEntityTiles(x, y, z, radius, (int) x + 1, (int) y + 1, (int) z, 8));

		return list;
	}

	public Set<Integer> getEntityTile(double x, double y, double z) {
		Set<Integer> list = new HashSet<Integer>();

		Tile t = getTile(x, y, z);

		if (t == null) {
			return list;
		}

		list = t.getEntities();

		return list;
	}

	private Set<Integer> getEntityTiles(double x, double y, double z, double radius, int tx, int ty, int tz, int dir) {
		HashSet<Integer> list = new HashSet<Integer>();

		if (dir == 1)// nw
		{
			if (radius >= distance(x, y, z, tx, ty, tz)) {
				list.addAll(getEntityTile(x, y, z));
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty - 1, tz, dir));
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty - 1, tz, 2));
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty - 1, tz, 4));
			}
		} else if (dir == 2)// n
		{
			if (y + radius >= ty + 1) {
				list.addAll(getEntityTiles(x, y, z, radius, tx, ty - 1, tz, dir));
			}
		} else if (dir == 3)// ne
		{
			if (radius >= distance(x, y, z, tx + 1, ty, tz)) {
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty - 1, tz, dir));
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty - 1, tz, 2));
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty - 1, tz, 5));
			}
		} else if (dir == 4)// w
		{
			if (x + radius >= tx + 1) {
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty, tz, dir));
			}
		} else if (dir == 5)// e
		{
			if (x - radius <= tx) {
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty, tz, dir));
			}
		} else if (dir == 6)// sw
		{
			if (radius >= distance(x, y, z, tx, ty + 1, tz)) {
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty + 1, tz, dir));
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty + 1, tz, 4));
				list.addAll(getEntityTiles(x, y, z, radius, tx - 1, ty + 1, tz, 7));
			}
		} else if (dir == 7)// s
		{
			if (y - radius <= ty) {
				list.addAll(getEntityTiles(x, y, z, radius, tx, ty + 1, tz, dir));
			}
		} else if (dir == 8)// se
		{
			if (radius >= distance(x, y, z, tx + 1, ty + 1, tz)) {
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty + 1, tz, dir));
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty + 1, tz, 5));
				list.addAll(getEntityTiles(x, y, z, radius, tx + 1, ty + 1, tz, 7));
			}
		}
		return list;
	}

	public boolean hasFog() {
		return fogofwar;
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

		return levels[(int) z].searchEntity(x, y, dir, range, fov, types, include, ID);
	}

	public TreeMap<Double, NPC> searchNPC(double x, double y, double z, double dir, double range, double fov,
			String[] types, boolean include, int ID) {
		if (!isValid(x, y, z)) {
			return new TreeMap<Double, NPC>();
		}

		if (types == null) {
			return new TreeMap<Double, NPC>();
		}

		return levels[(int) z].searchNPC(x, y, dir, range, fov, types, include, ID);
	}

	public TreeMap<Double, NPC> searchNPC2(double x, double y, double z, double dir, double range, double fov,
			String[] types, boolean include, int ID) {
		if (!isValid(x, y, z)) {
			return new TreeMap<Double, NPC>();
		}

		if (types == null) {
			return new TreeMap<Double, NPC>();
		}

		TreeMap<Double, NPC> result = new TreeMap<Double, NPC>();
		Set<Integer> ents = getRadialEntities(x, y, z, range);
		for (Integer i : ents) {
			Entity e = entities.get(i);
			if (e != null && e.getLvl() == (int) z) {
				if (e instanceof NPC) {
					NPC npc = (NPC) e;
					if (!e.isDead() && ID != e.getID()) {
						if (filterType(e.getEntityTypeName(), types, include)) {
							double dist = distance(x, y, z, e.getX(), e.getY(), e.getZ());
							if (hasLOS(x, y, z, dir, e.getX(), e.getY(), e.getZ(), range, fov)) {
								result.put(dist, npc);
							}
						}
					}
				}
			}
		}
		return result;

	}

	public TreeMap<Double, NPC> searchNPC3(double x, double y, double z, double dir, double range, double fov, int ID) {
		if (!isValid(x, y, z)) {
			return new TreeMap<Double, NPC>();
		}

		TreeMap<Double, NPC> result = new TreeMap<Double, NPC>();
		Set<Integer> ents = getRadialEntities(x, y, z, 1);
		for (Integer i : ents) {
			Entity e = entities.get(i);
			if (e != null && e.getLvl() == (int) z) {
				if (e instanceof NPC) {
					NPC npc = (NPC) e;
					if (!e.isDead() && ID != e.getID()) {
						double dist = distance(x, y, z, e.getX(), e.getY(), e.getZ());
						if (hasLOS(x, y, z, dir, e.getX(), e.getY(), e.getZ(), range, fov)) {
							result.put(dist, npc);
						}
					}
				}
			}
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
		if ((int) z != (int) z) {
			return false;
		}
		if (!isValid(x, y, z)) {
			return false;
		}
		if (!isValid(tx, ty, tz)) {
			return false;
		}

		return levels[(int) z].hasLOS(x, y, dir, tx, ty, range, fov);
	}

	public boolean isWalkable(int c, int r, int l) {
		if (!isValid(c, r, l)) {
			return false;
		}
		return levels[l].isWalkable(c, r);
	}

	public boolean isWalkable(double x, double y, double z) {
		if (!isValid(x, y, z)) {
			return false;
		}
		return levels[(int) z].isWalkable((int) x, (int) y);
	}

	public boolean isConnected(double x, double y, double z, double x2, double y2, double z2) {
		Tile t = getTile(x, y, z);
		if (t == null) {
			return false;
		}
		return t.isConnected(this, x2, y2, z2);
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
		t.calcConnected(this);
		return t.getConnected();
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
		openset.add(start);// add start node
		// contains distance for
		HashMap<Integer, Integer> camefrom = new HashMap<Integer, Integer>();
		HashMap<Integer, Double> fdist = new HashMap<Integer, Double>();
		HashMap<Integer, Double> gdist = new HashMap<Integer, Double>();
		HashMap<Integer, Double> hdist = new HashMap<Integer, Double>();
		gdist.put(start, 0.0);
		hdist.put(start, distance(x, y, z, tx, ty, tz)); // estimate of
		// distance
		fdist.put(start, hdist.get(start)); // = hdist[start]
		while (!openset.isEmpty()) {
			// System.out.println("looping...");
			int hash = -1;
			double shortest = -1;
			// System.out.println("finding smallest x...");
			for (Integer i : openset) {
				if (i != null) {
					if (fdist.get(i) < shortest || shortest == -1) {
						hash = i;
						shortest = fdist.get(i);
					}
				}
			}

			// System.out.println("comparing hash and goal...");
			// System.out.println(hash+ " =?= " +goal);
			if (hash == goal) {
				// System.out.println("drawing path...");
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
					}
				}
			}
		}
		return new Stack<Integer>();
	}

	public void drawPath(HashMap<Integer, Integer> camefrom, int node) {
		if (camefrom == null) {
			return;
		}
		Integer prev = camefrom.get(node);
		if (prev == null) {
			return;
		}
		if (prev == -1) {
			return;
		}

		drawPath(camefrom, prev);
		// System.out.print(node + ", ");
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

	// ==========================================================================
	// ==========================================================================
	// ==========================================================================

	public boolean isSameTile(double x1, double y1, double z1, double x2, double y2, double z2) {
		if (!isValid(x1, y1, z1)) {
			return false;
		}
		if (!isValid(x2, y2, z2)) {
			return false;
		}

		if ((int) x1 != (int) x2) {
			return false;
		}
		if ((int) y1 != (int) y2) {
			return false;
		}
		if ((int) z1 != (int) z2) {
			return false;
		}

		return true;
	}

	public static double fixAngle(double a) {
		double angle = a;

		while (angle >= 2 * Math.PI) {
			angle -= 2 * Math.PI;
		}
		while (angle < 0) {
			angle += 2 * Math.PI;
		}

		return angle;
	}

	public static double distanceAngle(double a1, double a2) {
		double angle1 = a1;
		double angle2 = a2;

		if (angle1 >= 2 * Math.PI) {
			angle1 -= 2 * Math.PI;
		}
		if (angle1 < 0) {
			angle1 += 2 * Math.PI;
		}

		if (angle2 > 2 * Math.PI) {
			angle2 -= 2 * Math.PI;
		}
		if (angle2 < 0) {
			angle2 += 2 * Math.PI;
		}

		double dA = angle2 - angle1;

		if (dA > Math.PI) {
			dA = -2 * Math.PI + dA;
		}
		if (dA < -Math.PI) {
			dA = 2 * Math.PI + dA;
		}

		return dA;
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

	public static boolean filterType(String type, String[] filter, boolean include) {
		if (include) {
			return includesType(type, filter);
		}

		return excludesType(type, filter);
	}

	public static boolean includesType(String type, String[] filter) {
		for (int i = 0; i < filter.length; i++) {
			if (filter[i].toLowerCase().contains(type.toLowerCase())) {
				return true;
			}
		}

		// System.out.println(printArr(filter) + " does not include " + type);
		return false;
	}

	public static boolean excludesType(String type, String[] filter) {
		for (int i = 0; i < filter.length; i++) {
			if (filter[i].toLowerCase().contains(type.toLowerCase())) {
				// System.out.println(printArr(filter) + " does not exclude " +
				// type);
				return false;
			}
		}

		return true;
	}

	public static String printArr(String[] arr) {
		if (arr == null) {
			return "[NULL]";
		}

		String str = "[";
		for (int i = 0; i < arr.length - 1; i++) {
			str += arr[i] + ", ";
		}

		if (arr.length > 0) {
			str += arr[arr.length - 1];
		}

		return str + "]";
	}
}
