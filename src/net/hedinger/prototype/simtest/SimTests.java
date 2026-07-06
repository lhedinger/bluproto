package net.hedinger.prototype.simtest;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.Sound;

/**
 * Runner for simulation scenario tests: deterministic mini-worlds with
 * hardcoded entities, advanced a fixed number of ticks, then checked against
 * expected outcomes. Run from the repo root:
 *
 * <pre>
 *   javac -d bin $(find src -name '*.java')
 *   java -cp bin net.hedinger.prototype.simtest.SimTests           # all scenarios
 *   java -cp bin net.hedinger.prototype.simtest.SimTests WallContainment  # by name
 *   java -Dsimtest.shots=out/shots -cp bin net.hedinger.prototype.simtest.SimTests
 * </pre>
 *
 * Scenarios use {@link TestNPC} fixtures rather than game species, so the
 * suite tests engine mechanics and survives changes to the bestiary.
 * Exits non-zero if any scenario fails.
 */
public class SimTests {

	// ---- scenarios ---------------------------------------------------------

	/** A roamer sealed in a single open tile can never escape through walls. */
	static class WallContainment extends Scenario {
		@Override
		public void run() {
			seed(1);
			World w = room(3, 3); // interior is exactly the tile (1,1)
			TestNPC r = TestNPC.roamer(1.5, 1.5, 0);
			w.spawnEntity(r);
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				assertTrue("roamer stays inside its 1-tile cell (tick " + i + "), was at "
						+ r.getX() + "," + r.getY(),
						r.getX() >= 1.0 && r.getX() < 2.0 && r.getY() >= 1.0 && r.getY() < 2.0);
			}
			assertTrue("roamer still alive", !r.isDead());
			assertEquals("exactly one living actor", 1, w.getAliveCount());
		}
	}

	/** Movement works: a roamer in an open room leaves its spawn point. */
	static class RoamerMoves extends Scenario {
		@Override
		public void run() {
			seed(2);
			World w = room(9, 9);
			TestNPC r = TestNPC.roamer(4.5, 4.5, 0);
			w.spawnEntity(r);
			w.think(); // register the spawn so it draws
			snapshot(w, "before (tick 0)");
			tick(w, 200);
			snapshot(w, "after (tick 200)");
			double moved = Math.hypot(r.getX() - 4.5, r.getY() - 4.5);
			assertGreater("roamer moved from spawn", moved, 0.5);
			assertTrue("roamer stays inside the room",
					r.getX() >= 1 && r.getX() < 8 && r.getY() >= 1 && r.getY() < 8);
		}
	}

	/**
	 * A chaser closes to contact with prey it can perceive.
	 *
	 * <p>Documents a real perception constraint: target scanning gathers
	 * candidates only from the 3x3 tile box around the NPC (and always has --
	 * the pre-optimization search also used a radius-1 gather), so an NPC is
	 * effectively myopic (~1.5 tiles) no matter how large its LOS_RANGE
	 * constant is. Prey must start in an adjacent tile to be seen at all.
	 */
	static class ChaserClosesIn extends Scenario {
		@Override
		public void run() {
			seed(3);
			World w = room(12, 5);
			TestNPC hunter = TestNPC.chaser(2.5, 2.5, 0);
			TestNPC prey = TestNPC.inert(3.7, 2.5, 0); // adjacent tile: perceivable
			w.spawnEntity(hunter);
			w.spawnEntity(prey);
			w.think();
			snapshot(w, "before (tick 0)");
			tick(w, 300);
			snapshot(w, "after (tick 300)");
			double after = Math.hypot(hunter.getX() - prey.getX(), hunter.getY() - prey.getY());
			assertLess("chaser reached its prey (started 1.2 apart)", after, 0.5);
			assertGreater("chaser actually travelled toward the prey", hunter.getX(), 3.0);
		}
	}

	/** An entity with a finite lifespan ages out, dies and is removed. */
	static class AgesOutAndIsRemoved extends Scenario {
		@Override
		public void run() {
			seed(4);
			World w = room(9, 9);
			TestNPC e = TestNPC.inert(4.5, 4.5, 0).withLifespan(50).withDeathspan(0);
			w.spawnEntity(e);
			tick(w, 10);
			assertTrue("alive during its lifespan", !e.isDead());
			tick(w, 60); // past lifespan 50; deathspan 0 removes immediately
			assertTrue("dead after its lifespan", e.isDead());
			assertTrue("removed (deathspan 0)", e.isRemoved());
			assertEquals("no living actors left", 0, w.getAliveCount());
		}
	}

	/**
	 * Lethal damage kills; the corpse is then scavenged away.
	 *
	 * <p>Documents the real corpse-decay contract: kill() pins age at -1 and
	 * nothing ever decrements it, so a corpse NEVER decays on its own --
	 * deathspan is a scavenging budget, not a timer. Only eat() pushes age
	 * past -deathspan, at which point the engine removes the body.
	 */
	static class LethalDamageAndScavenging extends Scenario {
		@Override
		public void run() {
			seed(5);
			World w = room(9, 9);
			TestNPC h = TestNPC.inert(4.5, 4.5, 0).withDeathspan(100);
			w.spawnEntity(h);
			tick(w, 2);
			assertEquals("one living actor before the hit", 1, w.getAliveCount());
			snapshot(w, "alive");
			h.damage(200); // health is 100
			tick(w, 1);
			snapshot(w, "corpse");
			assertTrue("dead after lethal damage", h.isDead());
			assertEquals("no living actors after the kill", 0, w.getAliveCount());
			tick(w, 500); // well past deathspan (100)
			assertTrue("corpse persists indefinitely without scavengers", !h.isRemoved());
			h.eat(101); // consume the full deathspan budget
			tick(w, 1);
			assertTrue("scavenged corpse is removed", h.isRemoved());
		}
	}

	/** The Sound->hear() channel: a listener is inert until a sound reaches it. */
	static class SoundWakesListener extends Scenario {
		@Override
		public void run() {
			seed(6);
			World w = room(9, 9);
			TestNPC z = TestNPC.listener(4.5, 4.5, 0);
			w.spawnEntity(z);
			tick(w, 100);
			assertTrue("listener has heard nothing yet", !z.hasHeard());
			assertNear("silent listener did not move (x)", 4.5, z.getX(), 0.001);
			assertNear("silent listener did not move (y)", 4.5, z.getY(), 0.001);

			// A sound broadcasts to everything in earshot at the end of its
			// 20-tick lifespan; the listener should hear it and start moving.
			w.spawnEntity(new Sound(4.5, 4.5, 0));
			tick(w, 150);
			assertTrue("listener heard the sound", z.hasHeard());
			double moved = Math.hypot(z.getX() - 4.5, z.getY() - 4.5);
			assertGreater("woken listener moved", moved, 0.05);
		}
	}

	/** The same seed and script produce the exact same end state. */
	static class SameSeedSameOutcome extends Scenario {
		private double[] runOnce() {
			seed(7);
			World w = room(10, 10);
			Entity[] cast = {
					TestNPC.chaser(2.5, 2.5, 0), TestNPC.roamer(7.5, 7.5, 0),
					TestNPC.listener(2.5, 7.5, 0), TestNPC.inert(7.5, 2.5, 0) };
			for (Entity e : cast) {
				w.spawnEntity(e);
			}
			w.spawnEntity(new Sound(5.0, 5.0, 0));
			tick(w, 400);
			double[] state = new double[cast.length * 3];
			for (int i = 0; i < cast.length; i++) {
				state[3 * i] = cast[i].getX();
				state[3 * i + 1] = cast[i].getY();
				state[3 * i + 2] = cast[i].isDead() ? 1 : 0;
			}
			return state;
		}

		@Override
		public void run() {
			double[] a = runOnce();
			double[] b = runOnce();
			for (int i = 0; i < a.length; i++) {
				assertEquals("state component " + i + " identical across runs",
						Double.doubleToLongBits(a[i]), Double.doubleToLongBits(b[i]));
			}
		}
	}

	// ---- runner ------------------------------------------------------------

	private static Scenario[] all() {
		return new Scenario[] {
				new WallContainment(),
				new RoamerMoves(),
				new ChaserClosesIn(),
				new AgesOutAndIsRemoved(),
				new LethalDamageAndScavenging(),
				new SoundWakesListener(),
				new SameSeedSameOutcome(),
		};
	}

	public static void main(String[] args) {
		int passed = 0;
		int failed = 0;
		String shotsDir = System.getProperty("simtest.shots");
		if (shotsDir != null) {
			new java.io.File(shotsDir).mkdirs();
		}
		for (Scenario s : all()) {
			if (args.length > 0 && !s.name().equalsIgnoreCase(args[0])) {
				continue;
			}
			long t0 = System.nanoTime();
			try {
				s.run();
				System.out.printf("PASS  %-28s (%.0f ms)%n", s.name(), (System.nanoTime() - t0) / 1e6);
				passed++;
			} catch (AssertionError e) {
				System.out.printf("FAIL  %-28s %s%n", s.name(), e.getMessage());
				failed++;
			} catch (Exception e) {
				System.out.printf("ERROR %-28s %s%n", s.name(), e);
				e.printStackTrace(System.out);
				failed++;
			}
			writeShots(shotsDir, s);
		}
		System.out.println("----");
		System.out.println(passed + " passed, " + failed + " failed");
		if (failed > 0) {
			System.exit(1);
		}
	}

	/** Composes a scenario's captured frames into one before/after strip PNG. */
	private static void writeShots(String shotsDir, Scenario s) {
		if (shotsDir == null || s.shots().isEmpty()) {
			return;
		}
		try {
			java.awt.image.BufferedImage strip =
					SnapshotRenderer.strip(s.name(), s.shotLabels(), s.shots());
			java.io.File out = new java.io.File(shotsDir, s.name() + ".png");
			javax.imageio.ImageIO.write(strip, "png", out);
			System.out.println("      shot -> " + out.getPath());
		} catch (Exception e) {
			System.out.println("      shot FAILED: " + e);
		}
	}
}
