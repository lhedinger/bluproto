package net.hedinger.prototype.main;

import java.util.TreeMap;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.engine.WorldGenerator;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.npcs.Bullsquid;
import net.hedinger.prototype.entities.npcs.Houndeye;
import net.hedinger.prototype.entities.npcs.Zombie;

/**
 * Runs the simulation without a window and prints population/flora stats,
 * so ecosystem balance can be checked from a terminal:
 *
 * java net.hedinger.prototype.main.HeadlessSim [ticks] [cols] [rows] [lvls]
 */
public class HeadlessSim {

	public static void main(String[] args) {
		int ticks = args.length > 0 ? Integer.parseInt(args[0]) : 20000;
		int cols = args.length > 1 ? Integer.parseInt(args[1]) : 30;
		int rows = args.length > 2 ? Integer.parseInt(args[2]) : 30;
		int lvls = args.length > 3 ? Integer.parseInt(args[3]) : 3;

		PrototypeWorld.stopwatch = new StopWatch();

		WorldGenerator generator = new WorldGenerator(cols, rows, lvls);
		generator.run();
		World world = generator.getWorld();
		world.alignTiles();

		final int[] births = { 0 };
		final int[] deaths = { 0 };
		world.addListener(new World.WorldListener() {
			@Override
			public void onSpawn(Entity e) {
				if (e instanceof NPC) {
					births[0]++;
				}
			}

			@Override
			public void onDeath(Entity e) {
				if (e instanceof NPC) {
					deaths[0]++;
				}
			}
		});

		spawn(world, cols, rows, lvls);

		System.out.println("tick | daylight | flora% | water# | populations (alive)");
		for (int t = 0; t <= ticks; t++) {
			world.think();
			if (t % 2000 == 0) {
				report(world, t, births[0], deaths[0]);
			}
		}
	}

	private static void spawn(World world, int cols, int rows, int lvls) {
		int ratio = (int) Math.round(0.25f * Math.sqrt(rows * cols));
		for (int z = 0; z < lvls; z++) {
			spawnMany(world, cols, rows, z, 3 * ratio, "houndeye");
			spawnMany(world, cols, rows, z, Math.max(3, ratio / 2), "bullsquid");
			if (z == 0) {
				spawnMany(world, cols, rows, z, ratio, "zombie");
			}
		}
	}

	private static void spawnMany(World world, int cols, int rows, int z, int num, String kind) {
		int attempts = 0;
		while (num > 0 && attempts < 5000) {
			attempts++;
			float x = (float) (Math.random() * cols);
			float y = (float) (Math.random() * rows);
			if (!world.isOpen(x, y, z)) {
				continue;
			}
			if (kind.equals("houndeye")) {
				world.spawnEntity(new Houndeye(x, y, z));
			} else if (kind.equals("bullsquid")) {
				world.spawnEntity(new Bullsquid(x, y, z));
			} else {
				world.spawnEntity(new Zombie(x, y, z));
			}
			num--;
		}
	}

	private static void report(World world, int tick, int births, int deaths) {
		TreeMap<String, Integer> pops = new TreeMap<String, Integer>();
		for (Entity e : world.getEntities()) {
			if (e instanceof NPC && !e.isDead() && !e.isRemoved()) {
				String key = ((NPC) e).getNpcTypeName();
				pops.put(key, pops.getOrDefault(key, 0) + 1);
			}
		}

		double flora = 0;
		int floorTiles = 0;
		int waterTiles = 0;
		for (int z = 0; z < world.getLevels(); z++) {
			for (int x = 0; x < world.getColums(); x++) {
				for (int y = 0; y < world.getRows(); y++) {
					if (world.getTile(x, y, z).isWater()) {
						waterTiles++;
					}
					float f = world.getTile(x, y, z).getFlora();
					if (f > 0 || world.getTile(x, y, z).isWalkable()) {
						flora += f;
						floorTiles++;
					}
				}
			}
		}

		// average genome speed per species: watch this drift to see selection
		double heSpeed = 0, bsSpeed = 0;
		int he = 0, bs = 0;
		for (Entity e : world.getEntities()) {
			if (e instanceof NPC && !e.isDead() && !e.isRemoved() && ((NPC) e).getGenome() != null) {
				NPC n = (NPC) e;
				if (n.getNpcTypeName().equals("Houndeye")) {
					heSpeed += n.getGenome().speed;
					he++;
				} else if (n.getNpcTypeName().equals("Bullsquid")) {
					bsSpeed += n.getGenome().speed;
					bs++;
				}
			}
		}

		System.out.printf("%6d | %.2f | %5.1f%% | %s | births=%d deaths=%d | avg speed he=%.4f bs=%.4f%n",
				tick, world.getDaylight(), 100.0 * flora / Math.max(1, floorTiles),
				pops, births, deaths,
				he > 0 ? heSpeed / he : 0, bs > 0 ? bsSpeed / bs : 0);
	}
}
