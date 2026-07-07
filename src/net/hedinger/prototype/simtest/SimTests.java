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

	/** Standing on a hole: walkers fall through to the level below, flyers hover. */
	static class HoleFallRespectsFlying extends Scenario {
		@Override
		public void run() {
			seed(8);
			World w = room(9, 9, 2); // two levels, both carved open
			w.setTile(4, 4, 1, net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
			w.setTile(6, 6, 1, net.hedinger.prototype.engine.Tile.TileType.TYPE_HOLE);
			TestNPC walker = TestNPC.inert(4.5, 4.5, 1); // on the first hole
			TestNPC flyer = TestNPC.inert(6.5, 6.5, 1).withFlying(); // on the second
			w.spawnEntity(walker);
			w.spawnEntity(flyer);
			w.think();
			snapshot(w, "before (both on level 1 holes)");
			tick(w, 5);
			snapshot(w, "after (walker fell, flyer hovers)");
			assertEquals("walker fell through the hole to the level below", 0, walker.getLvl());
			assertEquals("flyer hovers over the hole", 1, flyer.getLvl());
			tick(w, 50);
			assertEquals("walker rests on the floor below (falls only once)", 0, walker.getLvl());
			assertEquals("flyer still hovering", 1, flyer.getLvl());
			assertNear("walker landed straight down (x)", 4.5, walker.getX(), 0.001);
			assertNear("walker landed straight down (y)", 4.5, walker.getY(), 0.001);
		}
	}

	/**
	 * grab() attaches a smaller entity, which is then carried along; drop()
	 * releases it.
	 *
	 * <p>Two unit facts this scenario pins: NPC size is a PIXEL radius --
	 * getSize() converts to tiles as size/tileSize, so a size-6 NPC has a
	 * ~0.094-tile radius and grab reach is the sum of half-sizes (~0.08 tiles
	 * for a 6+4 pair). And carried entities are positioned by the attachment
	 * branch of executeMovement at exactly that offset from the carrier, with
	 * NO wall or bounds checks -- Entity.run() kills anything placed outside
	 * the world, so keep grab scenarios away from the border.
	 */
	static class GrabCarriesSmallerEntity extends Scenario {
		@Override
		public void run() {
			seed(9);
			World w = room(12, 12);
			TestNPC carrier = TestNPC.roamer(5.5, 5.5, 0).withSize(6);
			TestNPC cargo = TestNPC.inert(5.55, 5.5, 0).withSize(4); // within 0.078 reach
			w.spawnEntity(carrier);
			w.spawnEntity(cargo);
			w.think();
			snapshot(w, "before grab");
			assertTrue("grab succeeds on a smaller, in-reach entity", carrier.grab(cargo));

			double offset = (carrier.getSize() + cargo.getSize()) / 2.0;
			double cargoStartX = cargo.getX();
			double cargoStartY = cargo.getY();
			tick(w, 200);
			snapshot(w, "carrying (tick 200)");
			double carried = Math.hypot(cargo.getX() - carrier.getX(), cargo.getY() - carrier.getY());
			assertNear("carried cargo is pinned at the attachment offset", offset, carried, 0.01);
			assertGreater("cargo was dragged along as the carrier roamed",
					Math.hypot(cargo.getX() - cargoStartX, cargo.getY() - cargoStartY), 0.5);

			assertTrue("drop releases the cargo", carrier.drop());
			assertTrue("a second drop is refused (nothing held any more)", !carrier.drop());
			double maxDist = 0;
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				maxDist = Math.max(maxDist,
						Math.hypot(cargo.getX() - carrier.getX(), cargo.getY() - carrier.getY()));
			}
			assertGreater("after drop the carrier roams away from the inert cargo "
					+ "(while attached their distance is pinned)", maxDist, offset + 0.5);
			snapshot(w, "after drop (tick 500)");
		}
	}

	/** grab() refuses targets that are bigger or out of reach. */
	static class GrabRespectsSizeAndReach extends Scenario {
		@Override
		public void run() {
			seed(10);
			World w = room(12, 12);
			TestNPC grabber = TestNPC.inert(5.5, 5.5, 0).withSize(2);
			// In reach (within (2+6)/2/64 = 0.0625 tiles) but larger: size gate.
			TestNPC tooBig = TestNPC.inert(5.53, 5.5, 0).withSize(6);
			// Small enough but 4 tiles away: reach gate.
			TestNPC tooFar = TestNPC.inert(9.5, 5.5, 0).withSize(1);
			w.spawnEntity(grabber);
			w.spawnEntity(tooBig);
			w.spawnEntity(tooFar);
			w.think();
			assertTrue("cannot grab a bigger entity even in reach", !grabber.grab(tooBig));
			assertTrue("cannot grab beyond reach (sum of half-sizes)", !grabber.grab(tooFar));
			tick(w, 50);
			assertNear("refused targets are not dragged (tooFar x)", 9.5, tooFar.getX(), 0.001);
		}
	}

	/** Closed doors block passage from both sides; open doors admit. */
	static class DoorBlocksAndAdmits extends Scenario {
		@Override
		public void run() {
			seed(11);
			World w = room(10, 5);
			// Contract level: door flags gate isConnectedSpace. Dirs: 0=N 1=E 2=S 3=W.
			assertTrue("open passage eastward",
					w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));
			w.getTile(3, 2, 0).closeDoor(1); // close the source tile's east door
			assertTrue("closed east door blocks the move",
					!w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));
			w.getTile(3, 2, 0).openDoor(1);
			w.getTile(4, 2, 0).closeDoor(3); // destination's west door blocks too
			assertTrue("destination-side closed door blocks the move",
					!w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));
			w.getTile(4, 2, 0).openDoor(3);
			assertTrue("reopened passage admits again",
					w.isConnectedSpace(3.5, 2.5, 0, 4.5, 2.5, 0));

			// Movement level: a mover halts at a closed door, passes when opened.
			w.getTile(5, 2, 0).closeDoor(1);
			TestNPC m = TestNPC.mover(2.5, 2.5, 0, 0); // heading east
			w.spawnEntity(m);
			tick(w, 250); // 250 * 0.04 = 10 tiles of travel if unobstructed
			snapshot(w, "halted at closed door (red bar)");
			assertLess("mover halted at the closed door", m.getX(), 6.0);
			w.getTile(5, 2, 0).openDoor(1);
			tick(w, 150);
			snapshot(w, "passed after opening");
			assertGreater("mover passed through the opened door", m.getX(), 6.0);
		}
	}

	/**
	 * A walker ascends to the level above via a ramp.
	 *
	 * <p>Pins the (non-obvious) ramp mechanic: standing on a RAMPUP tile makes
	 * the move onto the next tile east legal even though that tile is WALL at
	 * this level; executeMovement's in-wall check then pops the entity up one
	 * level (dZ=+1), where it continues on the floor above. A control mover in
	 * a ramp-less row is simply blocked by the same wall.
	 */
	static class RampAscends extends Scenario {
		@Override
		public void run() {
			seed(12);
			World w = room(10, 6, 2);
			// Ramp row (y=2): floor, floor, ..., RAMPUP at x=5, WALL at x=6.
			w.setTile(5, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_RAMPUP);
			w.setTile(6, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			// Control row (y=4): same wall, no ramp.
			w.setTile(6, 4, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			TestNPC climber = TestNPC.mover(2.5, 2.5, 0, 0);
			TestNPC control = TestNPC.mover(2.5, 4.5, 0, 0);
			w.spawnEntity(climber);
			w.spawnEntity(control);
			w.think();
			snapshot(w, "before (both on level 0)");
			tick(w, 400);
			snapshot(w, "after (climber up the ramp, control blocked)");
			assertEquals("climber ascended to the level above", 1, climber.getLvl());
			assertGreater("climber kept walking on the upper floor", climber.getX(), 6.5);
			assertEquals("control (no ramp) is still on the ground level", 0, control.getLvl());
			assertLess("control is blocked by the wall", control.getX(), 6.0);
		}
	}

	/** Diagonal moves are blocked unless BOTH flanking cardinal tiles are open. */
	static class DiagonalCornerCutBlocked extends Scenario {
		@Override
		public void run() {
			seed(13);
			World w = room(8, 8);
			assertTrue("diagonal move with open flanks is allowed",
					w.isConnectedSpace(2.5, 2.5, 0, 3.5, 3.5, 0));
			w.setTile(3, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			assertTrue("one blocked flank forbids the diagonal (no corner cutting)",
					!w.isConnectedSpace(2.5, 2.5, 0, 3.5, 3.5, 0));
			w.setTile(2, 3, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			assertTrue("both flanks blocked still forbids it",
					!w.isConnectedSpace(2.5, 2.5, 0, 3.5, 3.5, 0));
			assertTrue("the cardinal moves themselves are also blocked by the walls",
					!w.isConnectedSpace(2.5, 2.5, 0, 3.5, 2.5, 0));
		}
	}

	/** Walls block perception: a chaser cannot acquire prey it has no line of sight to. */
	static class WallBlocksPerception extends Scenario {
		@Override
		public void run() {
			seed(14);
			// Control: prey diagonal-adjacent with open flanks -> acquired and
			// chased. The chaser spawns facing away and its ~90-degree FOV must
			// sweep around across several perception scans before it acquires,
			// so give it a generous window (~600 ticks).
			World open = room(8, 8);
			TestNPC hunter1 = TestNPC.chaser(2.5, 2.5, 0);
			TestNPC prey1 = TestNPC.inert(3.6, 3.6, 0);
			open.spawnEntity(hunter1);
			open.spawnEntity(prey1);
			tick(open, 600);
			double d1 = Math.hypot(hunter1.getX() - prey1.getX(), hunter1.getY() - prey1.getY());
			assertLess("open flanks: chaser reached the diagonal prey", d1, 0.5);

			// Same geometry with both flanking tiles walled: no line of sight.
			World walled = room(8, 8);
			walled.setTile(3, 2, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			walled.setTile(2, 3, 0, net.hedinger.prototype.engine.Tile.TileType.TYPE_WALL);
			TestNPC hunter2 = TestNPC.chaser(2.5, 2.5, 0);
			TestNPC prey2 = TestNPC.inert(3.6, 3.6, 0);
			walled.spawnEntity(hunter2);
			walled.spawnEntity(prey2);
			tick(walled, 600);
			double d2 = Math.hypot(hunter2.getX() - prey2.getX(), hunter2.getY() - prey2.getY());
			assertGreater("walled corner: prey never acquired, distance stays large", d2, 1.0);
		}
	}

	/** Overlapping bodies push apart (the collision spring). */
	static class CollisionSpringSeparates extends Scenario {
		@Override
		public void run() {
			seed(15);
			World w = room(8, 8);
			TestNPC a = TestNPC.inert(4.5, 4.5, 0);
			TestNPC b = TestNPC.inert(4.52, 4.5, 0); // overlapping (touch range ~0.094)
			w.spawnEntity(a);
			w.spawnEntity(b);
			tick(w, 200);
			double d = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
			assertGreater("overlapping entities were pushed apart", d, 0.05);
			assertTrue("both survived the shove", !a.isDead() && !b.isDead());
		}
	}

	/**
	 * spawnEntity rejects positions outside the world -- mostly.
	 *
	 * <p>Documents a real seam: spawn validation truncates coordinates with
	 * (int) casts, and Java truncates toward zero, so x in (-1, 0) truncates
	 * to column 0 and is ACCEPTED even though it lies outside the world (the
	 * engine's World.toCol handles negatives correctly, but Entity.getCol does
	 * not use it). The runtime validity check in Entity.run() then kills the
	 * entity on its first tick. If spawn validation is ever fixed to reject
	 * these, flip the marked assertions.
	 */
	static class SpawnRejectsOutOfBounds extends Scenario {
		@Override
		public void run() {
			seed(16);
			World w = room(8, 8);
			assertTrue("x <= -1 rejected", !w.spawnEntity(TestNPC.inert(-1.5, 4.5, 0)));
			assertTrue("x past the edge rejected", !w.spawnEntity(TestNPC.inert(8.5, 4.5, 0)));
			assertTrue("level out of range rejected", !w.spawnEntity(TestNPC.inert(4.5, 4.5, 3)));

			// The truncation quirk: fractionally-negative x is accepted...
			TestNPC ghost = TestNPC.inert(-0.5, 4.5, 0);
			assertTrue("x in (-1,0) is ACCEPTED (int-cast truncates toward zero)",
					w.spawnEntity(ghost));
			tick(w, 2);
			// ...but the runtime validity check kills it immediately.
			assertTrue("the out-of-bounds spawn dies on its first tick", ghost.isDead());
			assertEquals("no living actors entered the world", 0, w.getAliveCount());
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
				new HoleFallRespectsFlying(),
				new GrabCarriesSmallerEntity(),
				new GrabRespectsSizeAndReach(),
				new DoorBlocksAndAdmits(),
				new RampAscends(),
				new DiagonalCornerCutBlocked(),
				new WallBlocksPerception(),
				new CollisionSpringSeparates(),
				new SpawnRejectsOutOfBounds(),
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
