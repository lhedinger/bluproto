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
	private HashSet<Integer> npcs = new HashSet<Integer>();

	boolean door_N = false; // open
	boolean door_E = false; // open
	boolean door_S = false; // open
	boolean door_W = false; // open

	private int variant = 0;

	// environment state: plant cover, soil richness, stimulus deposits
	private float flora = 0;
	private float fertility = 1;
	private final float[] scents = new float[Scent.values().length];

	private static final float FLORA_SEED = 0.05f; // regrowth floor
	private static final float FLORA_GROWTH = 0.0015f;
	private static final float FERTILITY_MAX = 3f;
	private static final float SCENT_MAX = 10f;
	private static final float SCENT_FLOOR = 0.01f;

	public Tile(World w, int x, int y, int z) {
		world = w;

		TileType t = TileType.TYPE_FLOOR;

		if (Math.random() * 2 < 1) {
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
		npcs.add(id);
	}

	public void removeEntity(int id) {
		npcs.remove(id);
	}

	public Set<Integer> getEntities() {
		return npcs;
	}

	public int entityCount() {
		return npcs.size();
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

		if (dz < 0) {
			if ((type != TYPE_RAMPDOWN)) {
				return false;
			}
		} else if (dz > 0) {
			if ((type != TYPE_RAMPUP)) {
				return false;
			}
		} else {
			if (type == TYPE_RAMPDOWN) {
				if (dx > 0) {
					return true;
				}
			}
			if (type == TYPE_RAMPUP) {
				if (dx < 0) {
					return true;
				}
			}
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

	// ======================================================
	// ENVIRONMENT: flora, fertility, scent
	// ======================================================

	public float getFlora() {
		return flora;
	}

	public void addFlora(float amount) {
		flora = clamp(flora + amount, 0, 1);
	}

	/**
	 * Removes up to {@code amount} of plant cover from this tile.
	 *
	 * @return how much was actually consumed
	 */
	public float consumeFlora(float amount) {
		float eaten = Math.min(amount, flora);
		flora -= eaten;
		return eaten;
	}

	public float getFertility() {
		return fertility;
	}

	public void setFertility(float f) {
		fertility = clamp(f, 0, FERTILITY_MAX);
	}

	/** Decay (corpses, droppings) enriches the soil. */
	public void addFertility(float amount) {
		fertility = clamp(fertility + amount, 0, FERTILITY_MAX);
	}

	public float getScent(Scent s) {
		return scents[s.ordinal()];
	}

	public void addScent(Scent s, float amount) {
		int i = s.ordinal();
		scents[i] = clamp(scents[i] + amount, 0, SCENT_MAX);
	}

	/**
	 * One environment tick: scents fade, plants grow. Growth is logistic,
	 * scaled by soil fertility and daylight, and only happens on open floor.
	 */
	public void environmentThink(double daylight) {
		for (Scent s : Scent.values()) {
			int i = s.ordinal();
			if (scents[i] > 0) {
				scents[i] *= s.getDecay();
				if (scents[i] < SCENT_FLOOR) {
					scents[i] = 0;
				}
			}
		}

		if (type == TileType.TYPE_FLOOR) {
			flora = clamp(flora
					+ FLORA_GROWTH * (float) daylight * fertility * (FLORA_SEED + flora) * (1 - flora),
					0, 1);
		}
	}

	private static float clamp(float v, float lo, float hi) {
		if (v < lo) {
			return lo;
		}
		if (v > hi) {
			return hi;
		}
		return v;
	}

	public boolean isWater() {
		return type == TileType.TYPE_WATER;
	}

	public boolean isWalkable() {
		return type.isOpen() && type != TileType.TYPE_HOLE;
	}

	public boolean isFlyable() {
		return type.isOpen();
	}

	public boolean isSolid() {
		return !type.isOpen();
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
		// shallow water: passable (wading), drinkable, nothing grows on it
		TYPE_WATER(5, true);

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
