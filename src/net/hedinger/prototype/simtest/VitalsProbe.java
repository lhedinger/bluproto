package net.hedinger.prototype.simtest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.sim.Worlds;

/**
 * Diagnostic harness (not part of the suite): runs the demo world headless
 * under the VITALS model and reports the ecology's pulse — populations by
 * role over time, deaths by cause, and how much of the herd sits collapsed
 * or deprived at each sample — so a rebalance names its consequences before
 * it ships.
 */
public final class VitalsProbe {

	public static void main(String[] args) {
		long seed = args.length > 0 ? Long.parseLong(args[0]) : 42;
		int ticks = args.length > 1 ? Integer.parseInt(args[1]) : 40000;
		World w = Worlds.demo(seed);
		Map<String, Integer> deaths = new HashMap<>();
		Set<Integer> counted = new HashSet<>();
		System.out.println("tick | prey pred scav para minded | collapsed starving parched | births(gen>0)");
		for (int t = 0; t <= ticks; t++) {
			w.think();
			for (Entity e : w.getEntities()) {
				// isRemoved excludes the steward's ceiling trims: remove() marks a
				// body dead too, but a trim is population control, not a death the
				// ecology produced.
				if (e instanceof TestNPC n && n.isDead() && !n.isRemoved()
						&& !counted.contains(e.getID())) {
					counted.add(e.getID());
					deaths.merge(n.getDeathCause() == null ? "?" : n.getDeathCause(),
							1, Integer::sum);
				}
			}
			if (t % 4000 != 0) {
				continue;
			}
			int prey = 0, pred = 0, scav = 0, para = 0, minded = 0, collapsed = 0, starv = 0,
					parch = 0, born = 0;
			for (Entity e : w.getEntities()) {
				if (!(e instanceof TestNPC n) || n.isDead() || n.isRemoved()) {
					continue;
				}
				String r = n.ecoRole();
				if ("scavenger".equals(r)) {
					scav++;
				} else if ("parasite".equals(r)) {
					para++;
				} else if (n.isMinded()) {
					minded++;
				} else if ("prey".equals(r)) {
					prey++;
				} else if ("predator".equals(r)) {
					pred++;
				}
				if (!n.canExert()) {
					collapsed++;
				}
				if (n.getHunger() >= 0.95) {
					starv++;
				}
				if (n.getThirst() >= 0.95) {
					parch++;
				}
				if (n.generation() > 0) {
					born++;
				}
			}
			System.out.printf("%6d | %4d %4d %4d %4d %6d | %9d %8d %7d | %d%n",
					t, prey, pred, scav, para, minded, collapsed, starv, parch, born);
		}
		System.out.println("deaths by cause: " + deaths);
	}

	private VitalsProbe() {
	}
}
