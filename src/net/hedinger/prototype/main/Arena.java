package net.hedinger.prototype.main;

import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;

/**
 * Builds small, hand-controlled single-level worlds for scenario tests — an
 * open floor bordered by walls, so a specific behavior can be staged and filmed
 * without the randomness of the full world generator.
 */
public final class Arena {

	private Arena() {
	}

	/** Open floor arena, walls only on the border. */
	public static World open(int cols, int rows) {
		World world = new World(cols, rows, 1);
		for (int x = 1; x < cols - 1; x++) {
			for (int y = 1; y < rows - 1; y++) {
				world.setTile(x, y, 0, Tile.TileType.TYPE_FLOOR);
			}
		}
		world.alignTiles();
		return world;
	}

	/** Sets uniform plant cover and fertility across the open floor. */
	public static void seedFlora(World world, float flora, float fertility) {
		for (int x = 0; x < world.getColums(); x++) {
			for (int y = 0; y < world.getRows(); y++) {
				Tile t = world.getTile(x, y, 0);
				if (t.isWalkable()) {
					t.setFertility(fertility);
					t.addFlora(flora);
				}
			}
		}
	}

	/** Carves a small water pool centered at (cx,cy). Call before rendering. */
	public static void pool(World world, int cx, int cy, int radius) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				if (Math.sqrt(dx * dx + dy * dy) <= radius) {
					int x = cx + dx;
					int y = cy + dy;
					if (world.isValid(x, y, 0) && world.getTile(x, y, 0).isWalkable()) {
						world.setTile(x, y, 0, Tile.TileType.TYPE_WATER);
					}
				}
			}
		}
		world.alignTiles();
	}
}
