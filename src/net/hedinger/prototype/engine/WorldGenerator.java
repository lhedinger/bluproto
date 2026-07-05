package net.hedinger.prototype.engine;

import static net.hedinger.prototype.engine.Tile.TileType.TYPE_FLOOR;
import static net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE;
import static net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPDOWN;
import static net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPUP;
import static net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import net.hedinger.prototype.engine.Tile.TileType;
import net.hedinger.prototype.entities.Door;

public class WorldGenerator {

	private static final String ALPHABET = "_ABCDEFGHIKLMNOPQRSTUX1234567890";

	private World world;
	private int cols, rows, lvls;

	public WorldGenerator(int x, int y, int z) {
		cols = x;
		rows = y;
		lvls = z;

		world = new World(cols, rows, lvls);
	}

	public WorldGenerator(World world) {
		this.world = world;
	}

	public void run() {
		for (int i = 0; i < lvls; i++) {
			buildSectors(10, i);
		}
		cleanup();

		// custom stuff

		//
		// for (int x = 1; x <= 3; x++) {
		// for (int y = 1; y <= 3; y++) {
		// world.setTile(x, y, 0, TileType.TYPE_HOLE);
		// }
		// }

		// end custom stuff

		for (int i = 1; i < lvls; i++) {
			placeRampDown(3 + i * 2, 2 + i, i);
		}

		build_doors();
		removeHolesOverWalls();
	}

	public World getWorld() {
		return world;
	}

	public void placeRampDown(int x, int y, int z) {
		world.setTile(x, y, z, TYPE_RAMPDOWN);
		world.setTile(x - 1, y, z, TYPE_HOLE);

		world.setTile(x + 1, y - 1, z, TYPE_FLOOR);
		world.setTile(x + 1, y + 1, z, TYPE_FLOOR);

		world.setTile(x - 1, y, z - 1, TYPE_RAMPUP);
		world.setTile(x, y, z - 1, TYPE_WALL);
	}

	public void removeHolesOverWalls() {
		for (int l = lvls - 1; l > 0; l--) {
			for (int c = 1; c < cols - 1; c++) {
				for (int r = 1; r < rows - 1; r++) {
					if (world.getTile(c, r, l).getType() == TYPE_HOLE
							&& world.getTile(c, r, l - 1).getType() == TYPE_WALL) {
						world.setTile(c, r, l, TYPE_FLOOR);
					}
				}
			}
		}
	}

	public void buildSectors(int num, int z) {
		HashMap<Integer, HashSet<Integer>> sectors = new HashMap<Integer, HashSet<Integer>>();

		for (int i = 0; i < num; i++) {
			HashSet<Integer> sector = new HashSet<Integer>();

			int h = world.hashCode((Utils.random() * (world.cols - 2) + 1), (Utils.random() * (world.rows - 2) + 1), z);

			if (!sectorCollision(h, h, sectors, 4)) {
				sector.add(h);
				sectors.put(h, sector);
			}
		}

		// System.out.println("Generated " + sectors.size() + "/" + num + "
		// sectors");

		int done = 200;
		while (done > 0) {
			for (Integer i : sectors.keySet()) {
				HashSet<Integer> sector = sectors.get(i);

				if (sector == null) {
					break;
				}

				int h = random(sector);
				int x = world.hashCol(h);
				int y = world.hashRow(h);

				TileType type = TYPE_FLOOR;

				if (!sectorCollision(i, h, sectors, 3)) {
					if (world.setTile(x, y, z, type)) {
						sector.add(h);
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x - 1, y - 1, z, type)) {
							sector.add(world.hashCode(x - 1, y - 1, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x - 1, y, z, type)) {
							sector.add(world.hashCode(x - 1, y, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x - 1, y + 1, z, type)) {
							sector.add(world.hashCode(x - 1, y + 1, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x, y - 1, z, type)) {
							sector.add(world.hashCode(x, y - 1, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x, y + 1, z, type)) {
							sector.add(world.hashCode(x, y + 1, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x + 1, y - 1, z, type)) {
							sector.add(world.hashCode(x + 1, y - 1, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x + 1, y, z, type)) {
							sector.add(world.hashCode(x + 1, y, z));
						}
					}
					if (Utils.random() * 3 < 1) {
						if (world.setTile(x + 1, y + 1, z, type)) {
							sector.add(world.hashCode(x + 1, y + 1, z));
						}
					}

				}
			}

			done--;
		}

		for (Integer i : sectors.keySet()) {
			world.setTile(world.hashCol(i) - 1, world.hashRow(i) - 1, z, TYPE_FLOOR);
			world.setTile(world.hashCol(i) - 1, world.hashRow(i), z, TYPE_FLOOR);
			world.setTile(world.hashCol(i) - 1, world.hashRow(i) + 1, z, TYPE_FLOOR);
			world.setTile(world.hashCol(i), world.hashRow(i) - 1, z, TYPE_FLOOR);
			world.setTile(world.hashCol(i), world.hashRow(i), z, TYPE_WALL);
			world.setTile(world.hashCol(i), world.hashRow(i) + 1, z, TYPE_FLOOR);
			world.setTile(world.hashCol(i) + 1, world.hashRow(i) - 1, z, TYPE_FLOOR);
			world.setTile(world.hashCol(i) + 1, world.hashRow(i), z, TYPE_FLOOR);
			world.setTile(world.hashCol(i) + 1, world.hashRow(i) + 1, z, TYPE_FLOOR);

			int s = sectors.get(i).size();
			for (Integer j : sectors.get(i)) {
				if (Utils.random() * s > Math.sqrt(s) * 6) {
					world.setTile(world.hashCol(j), world.hashRow(j), z, TYPE_WALL);
				}
			}
		}

		HashSet<Integer> junctions = new HashSet<Integer>();
		for (int x = 1; x < world.cols - 1; x++) {
			for (int y = 1; y < world.rows - 1; y++) {
				Tile t = world.getTile(x, y, z);
				if (t.getType() == TYPE_WALL) {
					int h = world.hashCode(x, y, z);
					if (!sectorCollision(-1, h, sectors, 1)) {
						if (Utils.random() * 1 < 1) {
							if (!junctionCollision(h, junctions, 5)) {
								world.setTile(h, TYPE_FLOOR);
								junctions.add(h);
							}
						}
					}
				}
			}
		}

		for (int i : junctions) {
			int id = -1;
			int dir1 = closestSector(id, i, sectors);
			float d1 = dir1 / 10;
			id = (int) d1;
			dir1 = Math.round(dir1 - d1 * 10f);
			int dir2 = closestSector(id, i, sectors);
			float d2 = dir2 / 10;
			id = (int) d2;
			dir2 = Math.round(dir2 - d2 * 10f);
			int dir3 = closestSector(id, i, sectors);
			float d3 = dir3 / 10;
			id = (int) d3;
			dir3 = Math.round(dir3 - d3 * 10f);

			sectorConnect(dir1, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);
			if (dir2 >= 0) {
				sectorConnect(dir2, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);
			}
			if (dir3 >= 0) {
				sectorConnect(dir3, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);
			}

			sectorConnect(0, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);
			sectorConnect(1, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);
			sectorConnect(2, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);
			sectorConnect(3, world.hashCol(i), world.hashRow(i), world.hashLvl(i), sectors);

		}

		for (Integer i : sectors.keySet()) {
			Sector s = new Sector(world, i, z);

			for (Integer j : sectors.get(i)) {
				s.add(j);
			}

			world.levels[z].sectors.put(i, s);
		}

	}

	private void sectorConnect(int dir, int x, int y, int z, HashMap<Integer, HashSet<Integer>> sectors) {
		if (!sectorCollision(-1, world.hashCode(x, y, z), sectors, 0)) {
			if (world.setTile(x, y, z, TYPE_FLOOR)) {
				if (dir == 0) {
					sectorConnect(dir, x, y - 1, z, sectors);
				}
				if (dir == 1) {
					sectorConnect(dir, x + 1, y, z, sectors);
				}
				if (dir == 2) {
					sectorConnect(dir, x, y + 1, z, sectors);
				}
				if (dir == 3) {
					sectorConnect(dir, x - 1, y, z, sectors);
				}
			}
		}
	}

	private boolean sectorCollision(int id, int h, HashMap<Integer, HashSet<Integer>> sectors, int r) {

		for (Integer i : sectors.keySet()) {
			if (i != id) {
				HashSet<Integer> sector = sectors.get(i);

				for (Integer j : sector) {
					float d = Math.abs(world.hashCol(h) - world.hashCol(j));
					d = d + Math.abs(world.hashRow(h) - world.hashRow(j));
					d = d / 2;
					if (d <= r) {
						return true;
					}
				}
			}
		}

		return false;
	}

	public void build_doors() {
		for (int z = 0; z < world.lvls; z++) {
			for (int y = 1; y < world.rows - 1; y++) {
				for (int x = 1; x < world.cols - 1; x++) {
					Tile t = world.getTile(x, y, z);
					if (t.getType() == TYPE_FLOOR) // floor
					{
						Tile north, east, south, west, ne, se, sw;
						world.getTile(x - 1, y - 1, z);
						north = world.getTile(x, y - 1, z);
						ne = world.getTile(x + 1, y - 1, z);
						west = world.getTile(x - 1, y, z);
						east = world.getTile(x + 1, y, z);
						sw = world.getTile(x - 1, y + 1, z);
						south = world.getTile(x, y + 1, z);
						se = world.getTile(x + 1, y + 1, z);

						if (east.getType() == TYPE_FLOOR) {
							if (north.isSolid() && south.isSolid() && ne.isSolid() && se.isSolid()) {
								spawnDoor(new Door(x + 1, y, z, 1), z);
								north.setType(TYPE_WALL);
								south.setType(TYPE_WALL);
								ne.setType(TYPE_WALL);
								se.setType(TYPE_WALL);
							}
						}
						if (south.getType() == TYPE_FLOOR) {
							if (east.isSolid() && !west.isSolid() && se.isSolid() && sw.isSolid()) {
								spawnDoor(new Door(x, y + 1, z, 0), z);
								east.setType(TYPE_WALL);
								west.setType(TYPE_WALL);
								se.setType(TYPE_WALL);
								sw.setType(TYPE_WALL);
							}
						}

					}

				}
			}
		}
	}

	public boolean spawnDoor(Door d, int z) {
		if (d == null) {
			return false;
		}
		if (!world.isValid(d.getCol(), d.getRow(), d.getLvl())) {
			return false;
		}
		if (d.getDirection() == 0) {
			if (hasNeighborDoor((int) d.getX(), (int) d.getY(), (int) d.getZ(), 0)) {
				return false;
			}
		} else {
			if (hasNeighborDoor((int) d.getX(), (int) d.getY(), (int) d.getZ(), 1)) {
				return false;
			}
		}

		d.buildID(world, world.spawnCounter);
		world.spawnCounter++;

		world.levels[z].doors.add(d);
		return true;
	}

	public boolean hasNeighborDoor(int x, int y, int z, int d) {
		int max = (int) (Utils.random() * 5) + 1;
		for (Entity door : world.levels[z].doors) {
			if (door.getDirection() == d) {
				if (door.getZ() == z) {
					if (d == 0 && door.getX() == x) {
						if (Math.abs(door.getY() - y) <= max) {
							return true;
						}

					} else if (door.getY() == y) {
						if (Math.abs(door.getX() - x) <= max) {
							return true;
						}
					}
				}
			}

		}
		return false;
	}

	public void cleanup() {
		boolean rescan = false;
		int iteration = 0;
		do {
			world.alignTiles();
			for (int x = 0; x < world.cols; x++) {
				for (int y = 0; y < world.rows; y++) {
					for (int z = 0; z < world.lvls; z++) {
						Tile current = world.getTile(x, y, z);

						if (current.getType() == TYPE_WALL) {
							if (hasLoneDiagonal(current.getTileCode())) {
								world.setTile(x, y, z, TYPE_FLOOR);
								rescan = true;
							}
						}
						if (current.getType() == TYPE_HOLE) {
							if (hasLoneDiagonal(current.getTileCode())) {
								world.setTile(x, y, z, TYPE_FLOOR);
								rescan = true;
							}
						}
						if (current.getType() == TYPE_FLOOR) {
							if (current.getTileCode() == "0") {
								world.setTile(x, y, z, TYPE_WALL);
								rescan = true;
							}
						}

						if (z > 0) {
							Tile below = world.getTile(x, y, z - 1);
							if (current.getType() == TYPE_HOLE && below.getType() == TileType.TYPE_WALL) {
								world.setTile(x, y, z, TYPE_FLOOR);
								rescan = true;
							}
						}

					}
				}
			}
			iteration++;
		} while (rescan && iteration < 100);

	}

	public int random(Set<Integer> set) {
		int s = set.size();

		s = (int) (Utils.random() * s);

		for (int t : set) {
			if (s == 0) {
				return t;
			}
			s--;
		}
		return -1;
	}

	private boolean junctionCollision(int h, HashSet<Integer> junctions, int r) {
		for (Integer j : junctions) {
			float d = Math.abs(world.hashCol(h) - world.hashCol(j));
			d = d + Math.abs(world.hashRow(h) - world.hashRow(j));
			d = d / 2;
			if (d <= r) {
				return true;
			}
		}

		return false;
	}

	private int closestSector(int id, int h, HashMap<Integer, HashSet<Integer>> sectors) {
		int dir = -1;
		int dist = 9999;
		int c = world.hashCol(h);
		int r = world.hashRow(h);
		int tempid = -1;
		for (Integer i : sectors.keySet()) {
			if (id != i) {
				for (Integer j : sectors.get(i)) {
					int x = world.hashCol(j);
					int y = world.hashRow(j);

					if (c == x) {
						if (Math.abs(r - y) < dist) {
							tempid = i;
							dist = Math.abs(r - y);
							dir = 2;
							if (r > y) {
								dir = 0;
							}
						}
					} else if (r == y) {
						if (Math.abs(c - x) < dist) {
							tempid = i;
							dist = Math.abs(c - x);
							dir = 1;
							if (c > x) {
								dir = 3;
							}
						}
					}

				}
			}
		}

		id = tempid;
		return dir + 10 * tempid;
	}

	private boolean hasLoneDiagonal(String tilecode) {

		if (tilecode.contains("1")) {
			if (!(tilecode.contains("2") || tilecode.contains("4"))) {
				return true;
			}
		}
		if (tilecode.contains("3")) {
			if (!(tilecode.contains("2") || tilecode.contains("5"))) {
				return true;
			}
		}
		if (tilecode.contains("6")) {
			if (!(tilecode.contains("4") || tilecode.contains("7"))) {
				return true;
			}
		}
		if (tilecode.contains("8")) {
			if (!(tilecode.contains("5") || tilecode.contains("7"))) {
				return true;
			}
		}

		return false;
	}

}
