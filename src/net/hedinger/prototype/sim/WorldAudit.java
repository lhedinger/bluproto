package net.hedinger.prototype.sim;

import java.util.ArrayDeque;
import java.util.Deque;

import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;

/**
 * Offline audit of a generated {@link World}: is every walkable area reachable
 * from every other (by land within a level, or by an underground link between
 * levels), and how fast does the world tick on this machine?
 *
 * <p>Connectivity is a flood fill over walkable tiles whose edges mirror how a
 * land body actually travels between levels (see {@code Entity.updatePos}):
 * <ul>
 *   <li><b>land</b> — a walkable tile links to its four walkable neighbours;</li>
 *   <li><b>fall</b> — walking onto a {@code HOLE} drops the body to the tile
 *       directly below (Z-1), if that tile is walkable;</li>
 *   <li><b>ramp</b> — a ramp is floor spanning two levels, so stepping east off a
 *       {@code RAMPUP} lands on Z+1 and stepping west off a {@code RAMPDOWN} lands
 *       on Z-1.</li>
 * </ul>
 * The four-neighbour model is deliberately conservative: anything it reports as
 * connected truly is, so a "fully connected" verdict is trustworthy.
 *
 * <p>Run it directly to sweep the demo world across sizes:
 * <pre>java -cp &lt;engine-classes&gt; net.hedinger.prototype.sim.WorldAudit</pre>
 */
public final class WorldAudit {

	private WorldAudit() {
	}

	/** The connectivity verdict for one world. */
	public static final class Connectivity {
		public final int walkable;       // total walkable tiles across all levels
		public final int reachable;      // walkable tiles reachable from the largest region
		public final int components;     // number of disjoint walkable regions
		public final int[] unreachablePerLevel;

		Connectivity(int walkable, int reachable, int components, int[] unreachablePerLevel) {
			this.walkable = walkable;
			this.reachable = reachable;
			this.components = components;
			this.unreachablePerLevel = unreachablePerLevel;
		}

		public boolean fullyConnected() {
			return components <= 1 && reachable == walkable;
		}

		public double coverage() {
			return walkable == 0 ? 1.0 : reachable / (double) walkable;
		}
	}

	/**
	 * Directed reachability from the mainland: how much of the walkable space a
	 * body spawned on the largest surface region can actually reach, travelling by
	 * land and through the two-way level links. The level edges are DIRECTED (a
	 * HOLE only drops, a RAMPUP only lifts), so this is a proper directed flood,
	 * not naive component labelling — which would miscount, since a tile reachable
	 * only by descending can't be reached "upward" from the cave. The stations are
	 * two-way (a hole to drop, a ramp beside it to climb), so full forward
	 * coverage means a creature can get anywhere and back.
	 */
	public static Connectivity connectivity(World w) {
		int cols = w.getColums(), rows = w.getRows(), lvls = w.getLevels();
		int surfaceZ = lvls - 1;
		boolean[][][] walk = new boolean[lvls][cols][rows];
		int total = 0;
		for (int z = 0; z < lvls; z++) {
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, z).isWalkable()) {
						walk[z][x][y] = true;
						total++;
					}
				}
			}
		}
		int[] seed = largestSurfaceRegionSeed(walk, surfaceZ, cols, rows);
		boolean[][][] seen = new boolean[lvls][cols][rows];
		int reached = 0;
		if (seed != null) {
			reached = directedFlood(w, walk, seen, seed[0], seed[1], surfaceZ, cols, rows, lvls);
		}
		int[] unreachable = new int[lvls];
		for (int z = 0; z < lvls; z++) {
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					if (walk[z][x][y] && !seen[z][x][y]) {
						unreachable[z]++;
					}
				}
			}
		}
		int components = reached == total ? 1 : 2;
		return new Connectivity(total, reached, components, unreachable);
	}

	/** The first tile of the largest single-level walkable region on the surface —
	 *  the world's mainland, where bodies spawn. */
	private static int[] largestSurfaceRegionSeed(boolean[][][] walk, int surfaceZ, int cols, int rows) {
		boolean[][] seen = new boolean[cols][rows];
		int[] best = null;
		int bestSize = 0;
		for (int x = 0; x < cols; x++) {
			for (int y = 0; y < rows; y++) {
				if (!walk[surfaceZ][x][y] || seen[x][y]) {
					continue;
				}
				Deque<int[]> q = new ArrayDeque<int[]>();
				seen[x][y] = true;
				q.add(new int[] { x, y });
				int size = 0;
				while (!q.isEmpty()) {
					int[] c = q.poll();
					size++;
					int[][] card = { { c[0] + 1, c[1] }, { c[0] - 1, c[1] },
							{ c[0], c[1] + 1 }, { c[0], c[1] - 1 } };
					for (int[] n : card) {
						if (inBounds(n[0], n[1], cols, rows) && walk[surfaceZ][n[0]][n[1]]
								&& !seen[n[0]][n[1]]) {
							seen[n[0]][n[1]] = true;
							q.add(n);
						}
					}
				}
				if (size > bestSize) {
					bestSize = size;
					best = new int[] { x, y };
				}
			}
		}
		return best;
	}

	private static int directedFlood(World w, boolean[][][] walk, boolean[][][] seen,
			int sx, int sy, int sz, int cols, int rows, int lvls) {
		Deque<int[]> q = new ArrayDeque<int[]>();
		seen[sz][sx][sy] = true;
		q.add(new int[] { sx, sy, sz });
		int size = 0;
		while (!q.isEmpty()) {
			int[] c = q.poll();
			int x = c[0], y = c[1], z = c[2];
			size++;
			int[][] card = { { x + 1, y }, { x - 1, y }, { x, y + 1 }, { x, y - 1 } };
			for (int[] n : card) {
				visit(walk, seen, q, n[0], n[1], z, cols, rows); // land
				// Descend: a cardinal HOLE drops to the tile directly below it.
				if (inBounds(n[0], n[1], cols, rows) && z - 1 >= 0
						&& w.getTile(n[0], n[1], z).isDrop()) {
					visit(walk, seen, q, n[0], n[1], z - 1, cols, rows);
				}
			}
			// Climb: walking east off a RAMPUP's top lands on the level above.
			if (z + 1 < lvls && inBounds(x + 1, y, cols, rows)
					&& w.getTile(x, y, z).getType() == Tile.TileType.TYPE_RAMPUP) {
				visit(walk, seen, q, x + 1, y, z + 1, cols, rows);
			}
			// Descend: walking west off a RAMPDOWN's foot lands on the level below.
			if (z - 1 >= 0 && inBounds(x - 1, y, cols, rows)
					&& w.getTile(x, y, z).getType() == Tile.TileType.TYPE_RAMPDOWN) {
				visit(walk, seen, q, x - 1, y, z - 1, cols, rows);
			}
		}
		return size;
	}

	private static void visit(boolean[][][] walk, boolean[][][] seen, Deque<int[]> q,
			int x, int y, int z, int cols, int rows) {
		if (inBounds(x, y, cols, rows) && walk[z][x][y] && !seen[z][x][y]) {
			seen[z][x][y] = true;
			q.add(new int[] { x, y, z });
		}
	}

	private static boolean inBounds(int x, int y, int cols, int rows) {
		return x >= 0 && y >= 0 && x < cols && y < rows;
	}

	/** Ticks/second for {@code warmup + measured} think() calls (median-ish mean). */
	public static double ticksPerSecond(World w, int warmup, int measured) {
		for (int i = 0; i < warmup; i++) {
			w.think();
		}
		long t0 = System.nanoTime();
		for (int i = 0; i < measured; i++) {
			w.think();
		}
		double secs = (System.nanoTime() - t0) / 1e9;
		return secs <= 0 ? Double.POSITIVE_INFINITY : measured / secs;
	}

	// ---- sweep driver ------------------------------------------------------

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 42L;
		int[][] sizes = {
				{ Worlds.COLS, Worlds.ROWS },   // current
				{ 108, 66 },                    // 1.5x each dim (~2.25x area)
				{ 144, 88 },                    // 2x each dim (~4x area)
		};
		System.out.println("world audit (seed " + seed + ")");
		System.out.printf("%-12s %-9s %-11s %-9s %-10s %s%n",
				"size", "tiles/lv", "entities", "conn", "coverage", "perf (ticks/s)");
		for (int[] s : sizes) {
			World w = Worlds.demo(seed, s[0], s[1]);
			Connectivity c = connectivity(w);
			double tps = ticksPerSecond(w, 30, 120);
			String conn = c.fullyConnected() ? "FULL" : (c.components + " parts");
			System.out.printf("%-12s %-9d %-11d %-9s %-9.2f%% %.0f%n",
					s[0] + "x" + s[1], s[0] * s[1], w.getAliveCount(), conn,
					c.coverage() * 100, tps);
			if (!c.fullyConnected()) {
				System.out.printf("    unreachable per level: %s%n",
						java.util.Arrays.toString(c.unreachablePerLevel));
			}
		}
	}
}
