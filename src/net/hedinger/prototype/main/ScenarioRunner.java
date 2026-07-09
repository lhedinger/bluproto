package net.hedinger.prototype.main;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import net.hedinger.prototype.engine.LayerRenderer;
import net.hedinger.prototype.engine.Scent;
import net.hedinger.prototype.engine.StopWatch;
import net.hedinger.prototype.engine.View;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.npcs.Bullsquid;
import net.hedinger.prototype.entities.npcs.Houndeye;

/**
 * Behavior scenario tests. Each scenario stages a specific interaction in a
 * small controlled arena, asserts a measurable outcome (so it doubles as a
 * pass/fail test), and writes PNG frames of it playing out — which
 * scripts/run-scenarios.sh encodes into one MP4 per scenario.
 *
 * java net.hedinger.prototype.main.ScenarioRunner [outBaseDir]
 *
 * Exit code is non-zero if any scenario fails its assertion.
 */
public class ScenarioRunner {

	private static final int W = 800;
	private static final int H = 640;

	public static void main(String[] args) throws IOException {
		String base = args.length > 0 ? args[0] : "capture/scenarios";
		new File(base).mkdirs();

		ResourceManager_loadOnce();
		Renderer renderer = new Renderer(W, H);

		List<Scenario> scenarios = new ArrayList<Scenario>();
		scenarios.add(new HuntScenario());
		scenarios.add(new PanicScenario());
		scenarios.add(new GrazeScenario());
		scenarios.add(new CourtshipScenario());
		scenarios.add(new ScavengeScenario());

		int failures = 0;
		System.out.println("running " + scenarios.size() + " scenarios...\n");
		for (Scenario s : scenarios) {
			boolean ok = s.run(base, renderer);
			System.out.printf("  %-4s %-11s %s%n", ok ? "PASS" : "FAIL", s.name(), s.summary());
			if (!ok) {
				failures++;
			}
		}

		System.out.println("\n" + (scenarios.size() - failures) + " passed, " + failures + " failed");
		if (failures > 0) {
			System.exit(1);
		}
	}

	private static void ResourceManager_loadOnce() {
		net.hedinger.prototype.engine.ResourceManager.loadResources();
		PrototypeWorld.stopwatch = new StopWatch();
	}

	// =====================================================================
	// Scenario framework
	// =====================================================================

	abstract static class Scenario {
		private int outcomeTick = -1;
		private int frameCount = 0;

		abstract String name();

		abstract String description();

		abstract int cols();

		abstract int rows();

		abstract int maxTicks();

		/** ticks advanced per captured frame */
		int frameEvery() {
			return 4;
		}

		/** extra frames filmed after the outcome, for context */
		int padFrames() {
			return 12;
		}

		abstract void setup(World world);

		/** True once the asserted behavior has occurred. */
		abstract boolean success(World world);

		String summary() {
			if (outcomeTick < 0) {
				return description() + " — outcome NOT observed in " + maxTicks() + " ticks";
			}
			return description() + " — observed at tick " + outcomeTick + " (" + frameCount + " frames)";
		}

		boolean run(String base, Renderer renderer) throws IOException {
			World world = Arena.open(cols(), rows());
			setup(world);

			LayerRenderer lr = new LayerRenderer(world);
			lr.build(world); // bakes tile types set in setup() (e.g. water)
			View view = new View(world, lr);

			File frameDir = new File(base + "/" + name(), "frames");
			frameDir.mkdirs();
			clearDir(frameDir);

			boolean succeeded = false;
			int padLeft = padFrames();
			int frame = 0;

			for (int t = 0; t <= maxTicks(); t++) {
				world.think();

				if (!succeeded && success(world)) {
					succeeded = true;
					outcomeTick = t;
				}

				if (t % frameEvery() == 0) {
					BufferedImage img = renderer.paint(world, view);
					ImageIO.write(img, "png", new File(frameDir, String.format("frame_%04d.png", frame++)));
				}

				if (succeeded) {
					// film a short tail past the outcome, then stop
					if (t % frameEvery() == 0 && --padLeft <= 0) {
						break;
					}
				}
			}
			frameCount = frame;
			return succeeded;
		}
	}

	// =====================================================================
	// Concrete scenarios
	// =====================================================================

	/** A hungry predator runs down and kills prey. */
	static final class HuntScenario extends Scenario {
		private static final int HERD = 6;
		private int startCount;

		String name() {
			return "hunt";
		}

		String description() {
			return "predator kills prey";
		}

		int cols() {
			return 16;
		}

		int rows() {
			return 16;
		}

		int maxTicks() {
			return 900;
		}

		void setup(World world) {
			// hungry bullsquid (genome ctor => energy 55, below the hunt
			// threshold) dropped into a small herd
			world.spawnEntity(new Bullsquid(8.5, 8.5, 0, predatorGenome()));
			double cx = 6, cy = 6;
			for (int i = 0; i < HERD; i++) {
				double a = i * 2 * Math.PI / HERD;
				world.spawnEntity(new Houndeye(cx + Math.cos(a), cy + Math.sin(a), 0));
			}
			startCount = HERD;
		}

		boolean success(World world) {
			return countAlive(world, "Houndeye") < startCount;
		}
	}

	/** A herd panics and floods the map with fear scent. */
	static final class PanicScenario extends Scenario {
		String name() {
			return "panic";
		}

		String description() {
			return "herd flees, fear scent spreads";
		}

		int cols() {
			return 18;
		}

		int rows() {
			return 16;
		}

		int maxTicks() {
			return 600;
		}

		void setup(World world) {
			// tight herd on the left, a hungry predator charging from the right
			for (int i = 0; i < 7; i++) {
				world.spawnEntity(new Houndeye(5 + (i % 3) * 0.6, 6 + (i / 3) * 0.6, 0));
			}
			world.spawnEntity(new Bullsquid(12.5, 8.5, 0, predatorGenome()));
		}

		boolean success(World world) {
			return maxScent(world, Scent.FEAR) > 0.5f;
		}
	}

	/** Grazers eat down the plant cover. */
	static final class GrazeScenario extends Scenario {
		private float startFlora;

		String name() {
			return "graze";
		}

		String description() {
			return "herd depletes flora";
		}

		int cols() {
			return 14;
		}

		int rows() {
			return 14;
		}

		int maxTicks() {
			return 700;
		}

		int frameEvery() {
			return 5;
		}

		void setup(World world) {
			// fertility 0 => no regrowth during the window, so grazing shows as
			// a permanent, visible loss of green (energy-limited grazers only
			// remove roughly what they burn, so we need a hungry crowd)
			Arena.seedFlora(world, 0.7f, 0f);
			startFlora = totalFlora(world);
			for (int i = 0; i < 18; i++) {
				double a = i * 2 * Math.PI / 18;
				double r = 1.5 + (i % 3);
				world.spawnEntity(new Houndeye(7 + Math.cos(a) * r, 7 + Math.sin(a) * r, 0,
						herbivoreGenome()));
			}
		}

		boolean success(World world) {
			return totalFlora(world) < startFlora * 0.88f;
		}
	}

	/** Two well-fed adults court and one becomes pregnant. */
	static final class CourtshipScenario extends Scenario {
		String name() {
			return "courtship";
		}

		String description() {
			return "adults mate, one gets pregnant";
		}

		int cols() {
			return 16;
		}

		int rows() {
			return 16;
		}

		int maxTicks() {
			return 1400;
		}

		int frameEvery() {
			return 6;
		}

		void setup(World world) {
			Arena.seedFlora(world, 0.9f, 2.0f); // plenty to top up on
			Arena.pool(world, 4, 4, 1); // and water to drink
			// mature adults (default ctor sets a mature age + full-ish energy)
			for (int i = 0; i < 6; i++) {
				double a = i * 2 * Math.PI / 6;
				world.spawnEntity(new Houndeye(8.5 + Math.cos(a) * 1.2, 8.5 + Math.sin(a) * 1.2, 0));
			}
		}

		boolean success(World world) {
			for (NPC n : npcs(world, "Houndeye")) {
				if (n.isPregnant()) {
					return true;
				}
			}
			return false;
		}
	}

	/** A predator finds a carcass and feeds. */
	static final class ScavengeScenario extends Scenario {
		private Bullsquid squid;

		String name() {
			return "scavenge";
		}

		String description() {
			return "predator feeds at a carcass";
		}

		int cols() {
			return 15;
		}

		int rows() {
			return 15;
		}

		int maxTicks() {
			return 700;
		}

		void setup(World world) {
			// a fresh carcass in the middle...
			Houndeye carcass = new Houndeye(7.5, 7.5, 0);
			world.spawnEntity(carcass);
			carcass.kill();
			// ...and a hungry predator a few tiles away
			squid = new Bullsquid(4.5, 7.5, 0, predatorGenome());
			world.spawnEntity(squid);
		}

		boolean success(World world) {
			// started at energy 55; feeding pushes it well past that
			return squid.getEnergy() > 68;
		}
	}

	// =====================================================================
	// Shared genomes + world queries
	// =====================================================================

	private static Genome predatorGenome() {
		return new Genome(0.05, 8, 10, 0.03, 1.3, 1200, 0.8);
	}

	private static Genome herbivoreGenome() {
		return new Genome(0.035, 6, 7, 0.04, 1.0, 1100, 0.15);
	}

	private static int countAlive(World world, String type) {
		int n = 0;
		for (NPC npc : npcs(world, type)) {
			if (!npc.isDead()) {
				n++;
			}
		}
		return n;
	}

	private static List<NPC> npcs(World world, String type) {
		List<NPC> out = new ArrayList<NPC>();
		for (net.hedinger.prototype.engine.Entity e : world.getEntities()) {
			if (e instanceof NPC && !e.isRemoved() && ((NPC) e).getNpcTypeName().equals(type)) {
				out.add((NPC) e);
			}
		}
		return out;
	}

	private static float totalFlora(World world) {
		float sum = 0;
		for (int x = 0; x < world.getColums(); x++) {
			for (int y = 0; y < world.getRows(); y++) {
				sum += world.getTile(x, y, 0).getFlora();
			}
		}
		return sum;
	}

	private static float maxScent(World world, Scent s) {
		float max = 0;
		for (int x = 0; x < world.getColums(); x++) {
			for (int y = 0; y < world.getRows(); y++) {
				max = Math.max(max, world.getTile(x, y, 0).getScent(s));
			}
		}
		return max;
	}

	private static void clearDir(File dir) {
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				f.delete();
			}
		}
	}
}
