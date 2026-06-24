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
		TYPE_RAMPDOWN(4, true);

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
