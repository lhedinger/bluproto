package net.hedinger.prototype.main;

import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.engine.WorldGenerator;
import net.hedinger.prototype.entities.npcs.Bullsquid;
import net.hedinger.prototype.entities.npcs.Houndeye;
import net.hedinger.prototype.entities.npcs.Zombie;

/**
 * Shared world bootstrap for the non-GUI entry points ({@link HeadlessSim},
 * {@link SimCapture}, {@link SimTest}): generate a world and seed it with the
 * ecosystem cast. Keeps the spawn recipe in one place instead of copied per
 * tool.
 */
public final class Ecosystem {

	private Ecosystem() {
	}

	/** Generates and populates a ready-to-tick world. */
	public static World build(int cols, int rows, int lvls) {
		WorldGenerator generator = new WorldGenerator(cols, rows, lvls);
		generator.run();
		World world = generator.getWorld();
		world.alignTiles();
		populate(world, cols, rows, lvls);
		return world;
	}

	/** Seeds grazing herds, a few predators, and some zombies. */
	public static void populate(World world, int cols, int rows, int lvls) {
		int ratio = (int) Math.round(0.25f * Math.sqrt(rows * cols));
		for (int z = 0; z < lvls; z++) {
			spawn(world, cols, rows, z, 3 * ratio, Kind.HOUNDEYE);
			spawn(world, cols, rows, z, Math.max(3, ratio / 2), Kind.BULLSQUID);
			if (z == 0) {
				spawn(world, cols, rows, z, ratio, Kind.ZOMBIE);
			}
		}
	}

	private enum Kind {
		HOUNDEYE, BULLSQUID, ZOMBIE
	}

	private static void spawn(World world, int cols, int rows, int z, int num, Kind kind) {
		int attempts = 0;
		while (num > 0 && attempts < 5000) {
			attempts++;
			float x = (float) (Math.random() * cols);
			float y = (float) (Math.random() * rows);
			if (!world.isOpen(x, y, z)) {
				continue;
			}
			switch (kind) {
			case HOUNDEYE:
				world.spawnEntity(new Houndeye(x, y, z));
				break;
			case BULLSQUID:
				world.spawnEntity(new Bullsquid(x, y, z));
				break;
			case ZOMBIE:
				world.spawnEntity(new Zombie(x, y, z));
				break;
			}
			num--;
		}
	}
}
