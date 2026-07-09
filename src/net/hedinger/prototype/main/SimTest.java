package net.hedinger.prototype.main;

import java.util.ArrayList;
import java.util.List;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.npcs.Houndeye;

/**
 * Self-contained smoke/invariant tests for the engine and ecosystem. No JUnit
 * dependency — run it directly; it prints PASS/FAIL per check and exits
 * non-zero if anything fails, so it works as a CI gate.
 *
 * java net.hedinger.prototype.main.SimTest
 */
public class SimTest {

	private static int passed = 0;
	private static int failed = 0;

	public static void main(String[] args) {
		PrototypeWorld.stopwatch = new StopWatch();

		testWorldHashRoundTrip();
		testPathfindingReachesGoal();
		testFloraGrowsAndIsGrazed();
		testDayNightCycle();
		testGenomeInheritanceAndMutation();
		testSpawnDeathEvents();
		testEcosystemRunsStably();

		System.out.println();
		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	// ---- individual checks ------------------------------------------------

	private static void testWorldHashRoundTrip() {
		World w = new World(12, 9, 3);
		boolean ok = true;
		for (int z = 0; z < 3 && ok; z++) {
			for (int x = 0; x < 12 && ok; x++) {
				for (int y = 0; y < 9; y++) {
					int h = w.hashCode(x, y, z);
					if (w.hashCol(h) != x || w.hashRow(h) != y || w.hashLvl(h) != z) {
						ok = false;
						break;
					}
				}
			}
		}
		check("world hash <-> (col,row,lvl) round-trips", ok);
	}

	private static void testPathfindingReachesGoal() {
		World world = Ecosystem.build(20, 20, 1);
		// find two open tiles on level 0 and confirm a path connects reachable ones
		int[] a = firstOpen(world, 0);
		boolean found = false;
		if (a != null) {
			for (int x = world.getColums() - 1; x >= 0 && !found; x--) {
				for (int y = world.getRows() - 1; y >= 0; y--) {
					if (world.isOpen(x, y, 0) && !(x == a[0] && y == a[1])) {
						java.util.Stack<Integer> path = world.findPath(a[0] + 0.5, a[1] + 0.5, 0,
								x + 0.5, y + 0.5, 0);
						if (!path.isEmpty()) {
							// path must start adjacent-or-at the goal tile
							int top = path.peek();
							found = world.hashCol(top) == x && world.hashRow(top) == y;
							if (found) {
								break;
							}
						}
					}
				}
			}
		}
		check("findPath returns a stack ending at the goal tile", found);
	}

	private static void testFloraGrowsAndIsGrazed() {
		World world = new World(8, 8, 1);
		world.setTile(3, 3, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_FLOOR);
		world.getTile(3, 3, 0).setFertility(2f);
		float before = world.getTile(3, 3, 0).getFlora();
		for (int t = 0; t < 500; t++) {
			world.think();
		}
		float grown = world.getTile(3, 3, 0).getFlora();
		check("flora grows on fertile floor over time", grown > before);

		float eaten = world.getTile(3, 3, 0).consumeFlora(grown);
		check("grazing consumes flora", eaten > 0 && world.getTile(3, 3, 0).getFlora() < grown);
	}

	private static void testDayNightCycle() {
		World world = new World(4, 4, 1);
		double min = 1, max = 0;
		for (int t = 0; t < World.DAY_LENGTH; t++) {
			world.think();
			double d = world.getDaylight();
			min = Math.min(min, d);
			max = Math.max(max, d);
		}
		check("daylight spans a full day/night range", min < 0.1 && max > 0.9);
	}

	private static void testGenomeInheritanceAndMutation() {
		Genome a = new Genome(0.04, 6, 8, 0.03, 1.0, 800, 0.2);
		Genome b = new Genome(0.05, 7, 9, 0.04, 1.2, 900, 0.5);
		boolean drifted = false;
		boolean inRange = true;
		for (int i = 0; i < 50; i++) {
			Genome child = a.breed(b, 0.1);
			// each gene should be near one of the parents (crossover + small drift)
			if (child.speed < 0.03 || child.speed > 0.06) {
				inRange = false;
			}
			if (child.speed != a.speed && child.speed != b.speed) {
				drifted = true;
			}
			if (child.nocturnality < 0 || child.nocturnality > 1) {
				inRange = false; // clamped genes stay bounded
			}
		}
		check("offspring genes stay near parent values", inRange);
		check("mutation actually perturbs genes", drifted);
	}

	private static void testSpawnDeathEvents() {
		World world = Ecosystem.build(16, 16, 1);
		final int[] spawns = { 0 };
		final int[] deaths = { 0 };
		world.addListener(new World.WorldListener() {
			@Override
			public void onSpawn(Entity e) {
				spawns[0]++;
			}

			@Override
			public void onDeath(Entity e) {
				deaths[0]++;
			}
		});
		// the seeded animals spawn on the first tick; kill one and expect an event
		world.think();
		Houndeye victim = new Houndeye(8.5, 8.5, 0);
		world.spawnEntity(victim);
		world.think(); // victim spawns
		int deathsBefore = deaths[0];
		victim.kill();
		world.think(); // death fires
		check("onSpawn fired for seeded + added entities", spawns[0] > 0);
		check("onDeath fired when an entity was killed", deaths[0] > deathsBefore);
	}

	private static void testEcosystemRunsStably() {
		World world = Ecosystem.build(20, 20, 2);
		boolean crashed = false;
		int finalPop = 0;
		try {
			for (int t = 0; t < 4000; t++) {
				world.think();
			}
			List<String> alive = new ArrayList<String>();
			for (Entity e : world.getEntities()) {
				if (e instanceof NPC && !e.isDead() && !e.isRemoved()) {
					alive.add(((NPC) e).getNpcTypeName());
				}
			}
			finalPop = alive.size();
		} catch (RuntimeException ex) {
			crashed = true;
			ex.printStackTrace();
		}
		check("ecosystem ticks 4000 steps without crashing", !crashed);
		check("population survives the run (no total die-off)", finalPop > 0);
	}

	// ---- helpers ----------------------------------------------------------

	private static int[] firstOpen(World world, int z) {
		for (int x = 0; x < world.getColums(); x++) {
			for (int y = 0; y < world.getRows(); y++) {
				if (world.isOpen(x, y, z)) {
					return new int[] { x, y };
				}
			}
		}
		return null;
	}

	private static void check(String name, boolean ok) {
		if (ok) {
			passed++;
			System.out.println("  PASS  " + name);
		} else {
			failed++;
			System.out.println("  FAIL  " + name);
		}
	}
}
