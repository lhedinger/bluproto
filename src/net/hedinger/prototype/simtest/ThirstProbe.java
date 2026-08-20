package net.hedinger.prototype.simtest;

import java.util.HashMap;
import java.util.Map;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.sim.Worlds;

/**
 * Diagnostic harness (not part of the suite): runs the demo world headless and
 * measures how often thirsty creatures stand pressed against terrain instead
 * of moving — the failure the water flow field exists to prevent. Prints an
 * aggregate rate plus detailed samples of the worst offenders so a regression
 * names its own culprit.
 */
public final class ThirstProbe {

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
		int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
		World w = Worlds.demo(seed);
		for (int i = 0; i < 2000; i++) {
			w.think(); // settle: newborn dissolves, first grazing, some drain
		}
		Map<Integer, double[]> last = new HashMap<>(); // id -> [x, y] 33 ticks ago
		long thirsty = 0, stuck = 0, mindedThirsty = 0, mindedStuck = 0;
		Map<String, Integer> stuckAhead = new HashMap<>(); // tile type ahead when stuck
		int samples = 0;
		for (int t = 0; t < ticks; t++) {
			w.think();
			if (t % 33 != 0) {
				continue;
			}
			for (Entity e : w.getEntities()) {
				if (!(e instanceof TestNPC n) || n.isDead() || n.isRemoved()) {
					continue;
				}
				boolean dry = "thirst".equals(n.currentAction())
						|| (n.isMinded() && n.getHydration() < 0.35);
				double[] prev = last.put(e.getID(), new double[] { n.getX(), n.getY() });
				if (!dry || prev == null) {
					continue;
				}
				thirsty++;
				if (n.isMinded()) {
					mindedThirsty++;
				}
				double moved = Math.hypot(n.getX() - prev[0], n.getY() - prev[1]);
				if (moved >= 0.08) {
					continue;
				}
				// Barely moved for a second while wanting water. Near a shore that
				// is drinking; anywhere else it is the stuck we are hunting.
				Tile here = w.getTile(n.getX(), n.getY(), n.getZ());
				boolean atShore = here.getType() == Tile.TileType.TYPE_SHALLOWS || nearWet(w, n);
				if (atShore) {
					continue;
				}
				stuck++;
				if (n.isMinded()) {
					mindedStuck++;
				}
				double d = n.getDirection();
				Tile ahead = w.getTile(n.getX() + Math.cos(d) * 0.7,
						n.getY() + Math.sin(d) * 0.7, n.getZ());
				stuckAhead.merge(ahead.getType().name(), 1, Integer::sum);
				if (samples < 14) {
					samples++;
					System.out.printf(
							"stuck: %s id=%d z=%.0f at (%.2f,%.2f) dir=%.2f ahead=%s"
									+ " hyd=%.2f act=%s steps=%d flow=%.2f moved=%.3f%n",
							n.isMinded() ? "minded" : "eco", n.getID(), n.getZ(),
							n.getX(), n.getY(), d, ahead.getType().name(),
							n.getHydration(), n.currentAction(),
							w.waterStepDistance(n.getX(), n.getY(), n.getZ(), n.isFlying()),
							w.waterFlowDirection(n.getX(), n.getY(), n.getZ(), n.isFlying()),
							moved);
				}
			}
		}
		System.out.printf("thirsty samples: %d (minded %d) · stuck: %d (%.1f%%)"
				+ " · minded stuck: %d (%.1f%%)%n",
				thirsty, mindedThirsty, stuck, 100.0 * stuck / Math.max(1, thirsty),
				mindedStuck, 100.0 * mindedStuck / Math.max(1, mindedThirsty));
		System.out.println("stuck facing: " + stuckAhead);
	}

	/** Standing at (or beside) drinkable water — the drinking posture. */
	private static boolean nearWet(World w, TestNPC n) {
		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				Tile t = w.getTile((int) n.getX() + dx, (int) n.getY() + dy, (int) n.getZ());
				if (t.getType() == Tile.TileType.TYPE_WATER
						|| t.getType() == Tile.TileType.TYPE_SHALLOWS) {
					return true;
				}
			}
		}
		return false;
	}

	private ThirstProbe() {
	}
}
