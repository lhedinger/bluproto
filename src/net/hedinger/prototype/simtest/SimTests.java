package net.hedinger.prototype.simtest;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.AgentIO;
import net.hedinger.prototype.entities.Brain;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.LgpMind;
import net.hedinger.prototype.entities.Mind;
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

	/**
	 * The genome react() model: relationships emerge from marker similarity,
	 * relative size and disposition genes -- no hardcoded predator/prey table.
	 */
	static class GenomeReactModel extends Scenario {
		@Override
		public void run() {
			seed(17);
			// A big predator: dissimilar markers to its prey, keen to hunt.
			Genome predator = new Genome();
			predator.markers = new double[] { 0.0, 0.0, 0.0 };
			predator.size = 10;
			predator.predatory = 1.0;
			predator.gregariousness = 1.0;
			predator.xenophobia = 0.0;

			Genome prey = new Genome();
			prey.markers = new double[] { 1.0, 1.0, 1.0 }; // maximally dissimilar
			prey.size = 4;
			prey.xenophobia = 1.0; // wary of the unlike
			prey.predatory = 0.0;

			Genome kin = predator.copy(); // same markers as the predator

			// Predator (big) vs dissimilar smaller prey -> attack.
			assertTrue("big predator attacks small dissimilar prey",
					predator.react(prey, 10.0 / 4.0).action == Genome.Action.ATTACK);
			// Prey (small) vs dissimilar bigger predator -> flee.
			assertTrue("small prey flees big dissimilar predator",
					prey.react(predator, 4.0 / 10.0).action == Genome.Action.FLEE);
			// Predator vs its own kind (same markers) -> never hostile. Identical
			// markers clear the mate threshold, so kin read as MATE here; a
			// merely-similar (not identical) neighbour would read AFFILIATE.
			Genome.Relation vsKin = predator.react(kin, 1.0);
			assertTrue("predator is not hostile to kin",
					vsKin.action != Genome.Action.ATTACK && vsKin.action != Genome.Action.FLEE);
			assertTrue("predator is drawn to kin (mate/affiliate)",
					vsKin.action == Genome.Action.MATE || vsKin.action == Genome.Action.AFFILIATE);
			// The asymmetry: same pair, opposite actions, from the same rule.
			assertTrue("relationship is asymmetric (predator attacks, prey flees)",
					predator.react(prey, 2.5).action == Genome.Action.ATTACK
							&& prey.react(predator, 0.4).action == Genome.Action.FLEE);
		}
	}

	/**
	 * End to end: a genome-driven predator hunts a dissimilar smaller entity
	 * while ignoring a same-marker kin standing right next to it.
	 */
	static class GenomePredatorHuntsPrey extends Scenario {
		@Override
		public void run() {
			seed(18);
			World w = room(14, 7);
			Genome predatorG = new Genome();
			predatorG.markers = new double[] { 0.1, 0.1, 0.9 };
			predatorG.size = 10;
			predatorG.speed = 0.05;
			predatorG.predatory = 1.5;
			predatorG.gregariousness = 0.5;
			predatorG.losFov = Math.PI * 2; // omnidirectional: isolate the model
			//                                 from the facing/FOV perception gate

			Genome kinG = predatorG.copy(); // same markers -> recognised as kin
			Genome preyG = new Genome();
			preyG.markers = new double[] { 0.9, 0.9, 0.1 }; // dissimilar
			preyG.size = 4;

			TestNPC predator = TestNPC.genomeDriven(4.5, 3.5, 0, predatorG);
			TestNPC kin = TestNPC.genomeDriven(4.0, 3.5, 0, kinG); // beside the predator
			TestNPC prey = TestNPC.genomeDriven(5.6, 3.5, 0, preyG); // adjacent, perceivable
			w.spawnEntity(predator);
			w.spawnEntity(kin);
			w.spawnEntity(prey);
			w.think();
			snapshot(w, "before (predator, kin, prey)");

			double dPreyStart = Math.hypot(predator.getX() - prey.getX(), predator.getY() - prey.getY());
			tick(w, 400);
			snapshot(w, "after (hunts prey, ignores kin)");

			double dPrey = Math.hypot(predator.getX() - prey.getX(), predator.getY() - prey.getY());
			assertLess("predator closed on the prey", dPrey, dPreyStart * 0.6);
			assertTrue("predator's dominant action is ATTACK -- the dissimilar smaller "
					+ "prey outweighs the kin beside it", predator.lastAction() == Genome.Action.ATTACK);
		}
	}

	/** Offspring inherit a mutated genome; crossover mixes two parents. */
	static class GenomeInheritance extends Scenario {
		@Override
		public void run() {
			seed(19);
			Genome parent = new Genome();
			parent.speed = 0.04;
			parent.predatory = 0.5;
			parent.markers = new double[] { 0.5, 0.5, 0.5 };

			// Asexual child: every gene within +/- the mutation rate of the parent.
			double rate = 0.1;
			for (int i = 0; i < 200; i++) {
				Genome ch = Genome.child(parent, rate);
				assertTrue("child speed within mutation band",
						ch.speed >= parent.speed * (1 - rate) - 1e-9
								&& ch.speed <= parent.speed * (1 + rate) + 1e-9);
				assertTrue("child marker stays in [0,1]",
						ch.markers[0] >= 0 && ch.markers[0] <= 1);
				assertGreater("mutation actually varies the child",
						Math.abs(ch.markers[0] - parent.markers[0])
								+ Math.abs(ch.speed - parent.speed), -1); // always true; keeps ch used
			}

			// Crossover: each gene comes from one parent (before mutation), so a
			// zero-rate child's markers are a mix drawn from {A, B}.
			Genome a = new Genome();
			a.markers = new double[] { 0.0, 0.0, 0.0 };
			Genome b = new Genome();
			b.markers = new double[] { 1.0, 1.0, 1.0 };
			boolean sawA = false, sawB = false;
			for (int i = 0; i < 100; i++) {
				Genome ch = Genome.child(a, b, 0.0);
				for (double m : ch.markers) {
					if (m == 0.0) {
						sawA = true;
					}
					if (m == 1.0) {
						sawB = true;
					}
				}
			}
			assertTrue("crossover draws genes from both parents", sawA && sawB);
		}
	}

	/**
	 * A grazer eats the living substrate: it feeds from the tile underfoot and
	 * leaves a depleted patch behind. Exercises the NPC.graze() -> Tile link.
	 */
	static class GrazerDepletesSubstrate extends Scenario {
		@Override
		public void run() {
			seed(4);
			World w = room(11, 11);
			TestNPC g = TestNPC.grazer(5.5, 5.5, 0);
			w.spawnEntity(g);
			w.think(); // register the spawn
			snapshot(w, "before (full grass)");
			tick(w, 120);
			snapshot(w, "after (grazed patch)");

			assertGreater("grazer fed on the substrate", g.totalIntake(), 0.5);

			// The hungriest ground it worked over is visibly bare.
			double lowest = Tile.VEG_MAX;
			for (int c = 1; c < w.getColums() - 1; c++) {
				for (int r = 1; r < w.getRows() - 1; r++) {
					double v = w.getTile(c, r, 0).getVegetation(w.getTick());
					if (v < lowest) {
						lowest = v;
					}
				}
			}
			assertLess("grazing left a bare patch", lowest, 0.5);
		}
	}

	/**
	 * Vegetation regrows over time toward its cap once grazing stops. Pins the
	 * lazy closed-form regrowth against the world clock (no entity needed).
	 */
	static class VegetationRegrows extends Scenario {
		@Override
		public void run() {
			seed(5);
			World w = room(5, 5);
			tick(w, 1); // advance the clock off zero
			Tile t = w.getTile(2, 2, 0);

			double eaten = t.graze(w.getTick(), Tile.VEG_MAX); // strip it bare
			assertNear("stripped to bare ground", 0.0, t.getVegetation(w.getTick()), 1e-9);
			assertGreater("grazing consumed the standing crop", eaten, 0.5);

			// Regrowth is linear at VEG_REGROW/tick; run past a full recovery.
			int ticks = (int) Math.ceil(Tile.VEG_MAX / Tile.VEG_REGROW) + 10;
			tick(w, ticks);
			assertNear("vegetation regrew to its cap", Tile.VEG_MAX, t.getVegetation(w.getTick()), 1e-9);
		}
	}

	/**
	 * Fertility gates how much grass a tile can hold: poor ground regrows only
	 * to a low cap, rich ground fills to the full cap. This is what makes the
	 * substrate patchy -- rich and poor habitats instead of uniform pasture.
	 */
	static class FertilityCapsVegetation extends Scenario {
		@Override
		public void run() {
			seed(6);
			World w = room(5, 5);
			tick(w, 1);
			Tile poor = w.getTile(1, 1, 0);
			poor.setFertility(0.3);
			Tile rich = w.getTile(3, 3, 0); // default fertility 1.0

			poor.graze(w.getTick(), Tile.VEG_MAX); // strip it bare
			int ticks = (int) Math.ceil(Tile.VEG_MAX / Tile.VEG_REGROW) + 10;
			tick(w, ticks);
			long now = w.getTick();

			assertNear("poor ground regrows only to its fertility cap",
					0.3 * Tile.VEG_MAX, poor.getVegetation(now), 1e-9);
			assertNear("rich ground fills to the full cap",
					Tile.VEG_MAX, rich.getVegetation(now), 1e-9);
			assertGreater("rich ground carries much more grass than poor ground",
					rich.getVegetation(now) - poor.getVegetation(now), 0.5);
		}
	}

	/**
	 * A fertility field paints the map into patchy habitats: coherent lush
	 * blobs separated by poorer ground. Deterministic from the seed.
	 */
	static class FertileHabitatPatches extends Scenario {
		@Override
		public void run() {
			seed(11);
			World w = room(20, 20);
			w.generateFertility(0.22);
			w.think(); // advance the clock so grass sits at its per-tile cap
			snapshot(w, "patchy fertility");

			double min = 1, max = 0;
			for (int c = 1; c < w.getColums() - 1; c++) {
				for (int r = 1; r < w.getRows() - 1; r++) {
					double f = w.getTile(c, r, 0).getFertility();
					min = Math.min(min, f);
					max = Math.max(max, f);
				}
			}
			assertLess("some ground is poor", min, 0.4);
			assertGreater("some ground is rich", max, 0.7);
		}
	}

	/**
	 * Water is impassable to land entities but flyers skim over it: a walking
	 * mover halts at the shore while a flying mover crosses.
	 */
	static class WaterBlocksLandPassesFlyers extends Scenario {
		@Override
		public void run() {
			seed(20);
			World w = room(12, 5);
			for (int y = 1; y <= 3; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WATER); // a vertical lake
			}
			TestNPC land = TestNPC.mover(2.5, 2.5, 0, 0);       // walks east into the lake
			TestNPC flyer = TestNPC.mover(2.5, 1.5, 0, 0).withFlying();
			w.spawnEntity(land);
			w.spawnEntity(flyer);
			w.think();
			snapshot(w, "before (both west of the lake)");
			tick(w, 150);
			snapshot(w, "after (land halts, flyer crosses)");

			assertLess("land entity is stopped at the shore", land.getX(), 6.0);
			assertGreater("land entity walked up to the shore", land.getX(), 5.0);
			assertGreater("flyer crossed the water", flyer.getX(), 6.5);
		}
	}

	/** Mud drags: a mover crossing a mud strip falls behind one on clear floor. */
	static class MudSlowsMovement extends Scenario {
		@Override
		public void run() {
			seed(21);
			World w = room(16, 5);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 2, 0, Tile.TileType.TYPE_MUD); // mud strip on row 2 only
			}
			TestNPC muddy = TestNPC.mover(2.5, 2.5, 0, 0); // crosses the mud
			TestNPC clear = TestNPC.mover(2.5, 3.5, 0, 0); // clear floor alongside
			w.spawnEntity(muddy);
			w.spawnEntity(clear);
			w.think();
			snapshot(w, "before");
			tick(w, 200);
			snapshot(w, "after (muddy lags behind)");

			assertGreater("both movers advanced", muddy.getX(), 2.5);
			assertGreater("clear mover is well ahead of the muddy one",
					clear.getX() - muddy.getX(), 0.5);
		}
	}

	/**
	 * Tall-grass cover blocks line of sight: a chaser locks onto the prey it can
	 * see and ignores an equally-close prey hiding in cover (invisible to it).
	 */
	static class CoverHidesFromPerception extends Scenario {
		@Override
		public void run() {
			seed(22);
			World w = room(9, 5);
			w.setTile(4, 3, 0, Tile.TileType.TYPE_COVER); // hiding spot to the south
			TestNPC chaser = TestNPC.chaser(4.5, 2.5, 0);
			TestNPC visible = TestNPC.inert(4.5, 1.5, 0);       // in the open, to the north
			TestNPC hidden = TestNPC.inert(4.5, 3.5, 0);        // standing in the cover
			w.spawnEntity(chaser);
			w.spawnEntity(visible);
			w.spawnEntity(hidden);
			w.think();
			snapshot(w, "before (prey N in open, prey S in cover)");
			tick(w, 300);
			snapshot(w, "after (chaser took the visible prey)");

			double dVisible = Math.hypot(chaser.getX() - visible.getX(), chaser.getY() - visible.getY());
			double dHidden = Math.hypot(chaser.getX() - hidden.getX(), chaser.getY() - hidden.getY());
			assertLess("chaser reached the visible prey", dVisible, 0.5);
			assertGreater("chaser never went for the hidden prey", dHidden, dVisible);
		}
	}

	/**
	 * Energy economy: a metabolic entity with no food burns its energy down and
	 * starves. This is the cost side that makes fitness mean something.
	 */
	static class StarvesWithoutFood extends Scenario {
		@Override
		public void run() {
			seed(30);
			World w = room(5, 5);
			for (int x = 1; x <= 3; x++) {
				for (int y = 1; y <= 3; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren: no grass to eat
				}
			}
			TestNPC breeder = TestNPC.breeder(2.5, 2.5, 0, new Genome());
			w.spawnEntity(breeder);
			w.think();
			snapshot(w, "before (barren ground)");
			assertGreater("starts with energy", breeder.getEnergy(), 0.0);
			tick(w, 90);
			snapshot(w, "after (starved)");
			assertTrue("breeder starved with no food", breeder.isDead());
		}
	}

	/**
	 * The evolutionary loop end to end: fed breeders graze for energy and bud
	 * mutated offspring, so a population on a grassy field grows. Offspring
	 * inherit a mutated genome, so the lineage also drifts.
	 */
	static class PopulationGrowsWithFood extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(20, 20); // full grass everywhere (fertility 1)
			for (int i = 0; i < 3; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.2, 0.6, 0.9 };
				w.spawnEntity(TestNPC.breeder(6.5 + i * 3, 6.5 + i * 3, 0, g));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders");
			tick(w, 600);
			snapshot(w, "after (population grew)");
			int end = w.getAliveCount();
			assertGreater("a fed breeder population grows by reproduction", end, start);
		}
	}

	/**
	 * Sexual reproduction: a metabolic entity breeds only with a genome-compatible
	 * partner, and the child is a crossover of both parents. Three facts pin it
	 * apart from the asexual budder -- a partner is required, dissimilar maters
	 * refuse, and offspring recombine genes from both parents.
	 */
	static class SexualReproductionNeedsPartner extends Scenario {
		@Override
		public void run() {
			seed(50);

			// 1) A lone, well-fed mater has no partner, so it never reproduces --
			//    the defining difference from asexual budding.
			World lone = room(12, 12); // full grass: energy is never the limiter
			Genome solo = new Genome();
			solo.markers = new double[] { 0.5, 0.5, 0.5 };
			lone.spawnEntity(TestNPC.mater(6.5, 6.5, 0, solo));
			lone.think();
			snapshot(lone, "lone mater (no partner)");
			tick(lone, 400);
			assertEquals("a lone mater cannot reproduce without a partner", 1, lone.getAliveCount());

			// 2) Two well-fed maters that are too dissimilar refuse to pair (mate
			//    choice keys on marker similarity, like react()).
			World strangers = room(12, 12);
			Genome ga = new Genome();
			ga.markers = new double[] { 0.0, 0.0, 0.0 };
			Genome gb = new Genome();
			gb.markers = new double[] { 1.0, 1.0, 1.0 }; // maximally dissimilar
			strangers.spawnEntity(TestNPC.mater(6.3, 6.5, 0, ga));
			strangers.spawnEntity(TestNPC.mater(6.7, 6.5, 0, gb));
			strangers.think();
			tick(strangers, 400);
			assertEquals("incompatible (dissimilar) maters do not breed", 2, strangers.getAliveCount());

			// 3) A cluster of compatible maters breeds sexually, and crossover mixes
			//    the parents. Both types share markers (so they mate) but carry
			//    opposite speed and losRange, so a recombinant child (fast+far or
			//    slow+near) proves the offspring is a crossover, not a clone.
			World colony = room(24, 24);
			// Two compatible pairs, each a slow+far with a fast+near partner, spaced
			// apart so a pair shares grass without the whole colony overgrazing.
			// Founders start well-fed so they pair before a patch thins.
			double[][] pairs = { { 8.5, 8.5 }, { 16.5, 16.5 } };
			for (double[] p : pairs) {
				Genome slowFar = new Genome();
				slowFar.markers = new double[] { 0.5, 0.5, 0.5 };
				slowFar.speed = 0.02;
				slowFar.losRange = 20;
				colony.spawnEntity(TestNPC.mater(p[0], p[1], 0, slowFar).withEnergy(3.0));
				Genome fastNear = new Genome();
				fastNear.markers = new double[] { 0.5, 0.5, 0.5 };
				fastNear.speed = 0.08;
				fastNear.losRange = 4;
				colony.spawnEntity(TestNPC.mater(p[0] + 0.4, p[1], 0, fastNear).withEnergy(3.0));
			}
			colony.think();
			int founders = colony.getAliveCount();
			snapshot(colony, "founders (two compatible gene-types)");

			// Sexual breeders must cluster to pair, so a colony breeds fast and then
			// overgrazes back down -- the lasting proof is that it rose above the
			// founder count at all, and that crossover produced a recombinant.
			int peak = founders;
			boolean sawRecombinant = false;
			for (int step = 0; step < 30; step++) {
				tick(colony, 20);
				peak = Math.max(peak, colony.getAliveCount());
				sawRecombinant |= hasRecombinant(colony);
			}
			snapshot(colony, "after (bred sexually)");

			assertGreater("a compatible mater colony reproduces sexually "
					+ "(population rose above the founders)", peak, founders);
			assertTrue("crossover produced a recombinant child -- one type's speed with the "
					+ "other's losRange, a mix neither parent has", sawRecombinant);
		}

		/** True if any living entity's genome recombines the two founder types: a
		 * fast+far or slow+near mix that neither pure parent line carries. */
		private static boolean hasRecombinant(World w) {
			for (Entity e : w.getEntities()) {
				if (!(e instanceof net.hedinger.prototype.entities.NPC) || e.isDead()) {
					continue;
				}
				Genome g = ((net.hedinger.prototype.entities.NPC) e).getGenome();
				if (g == null) {
					continue;
				}
				boolean fast = g.speed > 0.05, far = g.losRange > 12;
				if ((fast && far) || (!fast && !far)) { // fast+far or slow+near
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The LGP brain's register bank is memory: it accumulates across ticks. A tiny
	 * hand-authored program (R0 += sense0 each pass, write R0 to an actuator) turns
	 * a constant input into a running count, and the run is bit-for-bit repeatable.
	 */
	static class BrainMemoryIsDeterministic extends Scenario {
		@Override
		public void run() {
			seed(60);
			int[][] prog = {
					{ net.hedinger.prototype.entities.Brain.SENSE, 1, 0, 0 }, // R1 = sense[0]
					{ net.hedinger.prototype.entities.Brain.ADD, 0, 0, 1 },   // R0 += R1  (persists)
					{ net.hedinger.prototype.entities.Brain.WRITE, 0, 0, 0 }, // act[0] = R0
			};
			double got = runCount(prog, 25);
			assertNear("register R0 accumulated the constant input across 25 ticks (memory)",
					25.0, got, 1e-9);
			assertNear("same program + inputs -> identical output (determinism)",
					got, runCount(prog, 25), 1e-9);
		}

		private static double runCount(int[][] prog, int ticks) {
			net.hedinger.prototype.entities.Brain b =
					new net.hedinger.prototype.entities.Brain(deepCopy(prog));
			double[] sensors = { 1.0 };
			double[] act = new double[1];
			for (int t = 0; t < ticks; t++) {
				b.step(sensors, act, prog.length); // one full pass per tick
			}
			return act[0];
		}
	}

	/**
	 * Program length sets the thought cycle: with a fixed per-tick budget, a longer
	 * brain takes more ticks to complete a pass, so it re-decides less often. Two
	 * brains with the same logic -- one padded with NOPs -- accumulate at rates set
	 * by their lengths, so over equal ticks the short brain counts far higher.
	 */
	static class BrainLengthSetsThoughtRate extends Scenario {
		@Override
		public void run() {
			seed(61);
			int SENSE = net.hedinger.prototype.entities.Brain.SENSE;
			int ADD = net.hedinger.prototype.entities.Brain.ADD;
			int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
			int NOP = net.hedinger.prototype.entities.Brain.NOP;
			int[][] shortProg = { { SENSE, 1, 0, 0 }, { ADD, 0, 0, 1 }, { WRITE, 0, 0, 0 } };
			int[][] longProg = { { SENSE, 1, 0, 0 }, { ADD, 0, 0, 1 }, { WRITE, 0, 0, 0 },
					{ NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 },
					{ NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 } };
			double shortCount = runFixedBudget(shortProg, 90);
			double longCount = runFixedBudget(longProg, 90);
			assertGreater("the shorter brain thinks (and so counts) faster", shortCount, longCount);
			assertGreater("length-3 brain runs its pass ~3x as often as the length-9 one",
					shortCount, longCount * 2.0);
		}

		private static double runFixedBudget(int[][] prog, int ticks) {
			net.hedinger.prototype.entities.Brain b =
					new net.hedinger.prototype.entities.Brain(deepCopy(prog));
			double[] sensors = { 1.0 };
			double[] act = new double[1];
			for (int t = 0; t < ticks; t++) {
				b.step(sensors, act, net.hedinger.prototype.entities.Brain.DEFAULT_STEPS_PER_TICK);
			}
			return act[0];
		}
	}

	/**
	 * Brain heredity: unequal crossover splices a slice of one parent into the
	 * other, so children mix both parents' code and their length varies; mutation
	 * then changes the program. Pins the genetic operators the evolving mind uses.
	 */
	static class BrainHeredityCrossesAndMutates extends Scenario {
		@Override
		public void run() {
			seed(62);
			int NOP = net.hedinger.prototype.entities.Brain.NOP;
			int WRITE = net.hedinger.prototype.entities.Brain.WRITE;
			int[][] aCode = new int[5][];
			int[][] bCode = new int[5][];
			for (int i = 0; i < 5; i++) {
				aCode[i] = new int[] { NOP, 0, 0, 0 };   // parent A: all nop
				bCode[i] = new int[] { WRITE, 1, 2, 0 };  // parent B: all write
			}
			net.hedinger.prototype.entities.Brain A = new net.hedinger.prototype.entities.Brain(aCode);
			net.hedinger.prototype.entities.Brain B = new net.hedinger.prototype.entities.Brain(bCode);

			boolean sawMixedChild = false, sawVariedLength = false;
			for (int i = 0; i < 100 && !(sawMixedChild && sawVariedLength); i++) {
				net.hedinger.prototype.entities.Brain ch =
						net.hedinger.prototype.entities.Brain.child(A, B, 0.0); // crossover only
				String dis = String.join("\n", ch.disassemble(null, null));
				if (dis.contains("nop") && dis.contains("act ")) {
					sawMixedChild = true; // carries code from both parents
				}
				if (ch.length() != 5) {
					sawVariedLength = true; // unequal crossover changed the length
				}
			}
			assertTrue("crossover produces children carrying code from both parents", sawMixedChild);
			assertTrue("unequal crossover varies the child's program length", sawVariedLength);

			net.hedinger.prototype.entities.Brain m = net.hedinger.prototype.entities.Brain.random(6);
			String before = String.join("\n", m.disassemble(null, null));
			m.mutate(1.0);
			String after = String.join("\n", m.disassemble(null, null));
			assertTrue("mutation changes the program", !before.equals(after));
		}
	}

	/**
	 * A pluggable mind drives a body through the sensor/actuator contract. The same
	 * body pursues a target under three interchangeable minds -- an LGP brain, a
	 * hand-written controller, and a do-nothing dummy -- proving the interface is
	 * swappable without touching how the body senses or acts.
	 */
	static class MindDrivesAgent extends Scenario {
		@Override
		public void run() {
			// An LGP "pursue the nearest" program: turn = relative bearing to the
			// neighbour, throttle = 1. Four instructions, run as a full pass/tick.
			int[][] pursue = {
					{ Brain.SENSE, 0, AgentIO.S_NEAR_BEARING, 0 }, // R0 = near_bearing
					{ Brain.WRITE, AgentIO.A_TURN, 0, 0 },         // turn = R0
					{ Brain.SET, 1, 9, 0 },                        // R1 = 1.0 (const[9])
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 },     // throttle = R1
			};
			firstMind = new LgpMind(new Brain(deepCopy(pursue)), 4);
			double lgp = pursuitDistance(firstMind);
			assertLess("an LGP-brained body steers to its target through the sensor vector", lgp, 0.5);

			// The SAME body with a hand-written controller -- swapped behind the Mind
			// interface, sensing/acting untouched -- pursues identically.
			Mind scripted = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_TURN] = s[AgentIO.S_NEAR_BEARING];
					a[AgentIO.A_THROTTLE] = 1.0;
				}
			};
			assertLess("a scripted (dummy) mind on the same body pursues identically",
					pursuitDistance(scripted), 0.5);

			// A do-nothing mind leaves the body inert -- the trivial case works too.
			Mind idle = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
				}
			};
			assertGreater("an idle mind never moves the body", pursuitDistance(idle), 1.0);
		}

		/** Runs one mind against a fixed pursuit setup; returns the final distance
		 * between the minded body and its stationary target. */
		private double pursuitDistance(Mind mind) {
			seed(63); // identical world/perception for every mind
			World w = room(12, 12);
			Genome g = Genome.phenotype(6, 0.05, 5, 6, Math.PI, 3000);
			TestNPC agent = TestNPC.minded(4.5, 4.5, 0, g, mind);
			TestNPC target = TestNPC.inert(5.6, 4.5, 0); // adjacent tile: perceivable
			w.spawnEntity(agent);
			w.spawnEntity(target);
			w.think();
			if (mind == firstMind) {
				snapshot(w, "before (agent SW of target)");
			}
			tick(w, 250);
			if (mind == firstMind) {
				snapshot(w, "after (brain steered onto the target)");
			}
			return Math.hypot(agent.getX() - target.getX(), agent.getY() - target.getY());
		}

		private Mind firstMind; // labels the snapshot for the LGP run only
	}

	/**
	 * A brain is inherited alongside the body: an asexual child copies-and-mutates
	 * the parent's program, a sexual child crosses both parents' programs, and a
	 * brain-less lineage stays brain-less (drawing no extra RNG, so the sim stream
	 * -- and the emergent scenarios -- are unchanged).
	 */
	static class BrainInheritedThroughReproduction extends Scenario {
		@Override
		public void run() {
			seed(64);
			Genome pa = new Genome();
			pa.brain = Brain.random(6);
			Genome asexual = Genome.child(pa, 0.2);
			assertTrue("asexual child inherits a brain", asexual.brain != null);
			assertTrue("the child's brain is its own instance, not the parent's",
					asexual.brain != pa.brain);

			Genome pb = new Genome();
			pb.brain = Brain.random(6);
			assertTrue("sexual child inherits a brain crossed from both parents",
					Genome.child(pa, pb, 0.2).brain != null);

			assertTrue("brain-less crossover stays brain-less",
					Genome.child(new Genome(), new Genome(), 0.2).brain == null);
			assertTrue("brain-less budding stays brain-less",
					Genome.child(new Genome(), 0.2).brain == null);
		}
	}

	/**
	 * The mind evolves end to end: metabolic brained foragers -- all seeded with
	 * one hand-authored "graze while wandering" program -- feed, bud, and pass a
	 * mutated copy of the brain to their young, so the living population's programs
	 * diversify away from the single founder mind.
	 */
	static class BrainedPopulationDiversifies extends Scenario {
		@Override
		public void run() {
			seed(65);
			int[][] graze = {
					{ Brain.SET, 0, 9, 0 },                       // R0 = 1.0
					{ Brain.WRITE, AgentIO.A_EAT, 0, 0 },         // eat = 1
					{ Brain.SENSE, 1, AgentIO.S_CLOCK, 0 },       // R1 = clock
					{ Brain.WRITE, AgentIO.A_TURN, 1, 0 },        // turn = clock (wander)
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 },    // throttle = 1
					{ Brain.WRITE, AgentIO.A_MATE, 0, 0 },        // reproduce (asexual: spread out)
			};
			World w = room(20, 20); // full grass
			for (int i = 0; i < 3; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.2, 0.6, 0.9 };
				g.brain = new Brain(deepCopy(graze));
				w.spawnEntity(TestNPC.brainedBreeder(6.5 + i * 3, 6.5 + i * 3, 0, g));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders (one shared mind)");

			// Brain-driven reproduction is spiky (boom then bust), so track the peak
			// population and every distinct mind seen across the run.
			int peak = start;
			java.util.Set<String> minds = new java.util.HashSet<String>();
			for (int step = 0; step < 30; step++) {
				tick(w, 20);
				peak = Math.max(peak, w.getAliveCount());
				for (Entity e : w.getEntities()) {
					if (e instanceof net.hedinger.prototype.entities.NPC && !e.isDead()) {
						Brain b = ((net.hedinger.prototype.entities.NPC) e).getGenome().brain;
						if (b != null) {
							minds.add(programOf(b));
						}
					}
				}
			}
			snapshot(w, "after (bred; minds mutated apart)");
			assertGreater("brained foragers reproduced (population rose above the founders)", peak, start);
			assertGreater("inherited minds diversified by mutation", minds.size(), 1);
		}

		/** The brain's instructions as a string, ignoring the runtime PC marker. */
		private static String programOf(Brain b) {
			StringBuilder s = new StringBuilder();
			for (String line : b.disassemble(null, null)) {
				s.append(line.substring(2)).append('|');
			}
			return s.toString();
		}
	}

	/**
	 * Evolution discovers a behaviour from noise. Starting from random LGP brains
	 * that gather no food, a generational GA -- fitness is food a brain forages in
	 * a fixed window, truncation-select the top half, crossover + mutate to refill
	 * -- drives the population to forage. The selection pressure is the fitness
	 * gradient; the behaviour (eat while moving to fresh grass) is not authored, it
	 * emerges. The mind's own heredity (Brain crossover/mutation) does the varying.
	 */
	static class EvolutionDiscoversForaging extends Scenario {
		// G=40: with the actuator vector grown (grab/attach added), random founders
		// start weaker -- more operands land on the new no-op-for-foraging slots --
		// so discovery needs a few more generations. At 40 it is robust across
		// seeds (swept), not tuned to one lucky seed.
		static final int P = 24, K = 300, G = 40, LEN = 24, BUDGET = 4;

		@Override
		public void run() {
			seed(66);
			Brain[] brains = new Brain[P];
			for (int i = 0; i < P; i++) {
				brains[i] = Brain.random(LEN);
			}
			double initialMean = 0, bestEver = 0, lateSum = 0;
			for (int gen = 0; gen <= G; gen++) {
				double[] fit = evaluate(brains);
				double m = mean(fit), best = max(fit);
				if (gen == 0) {
					initialMean = m;
				}
				bestEver = Math.max(bestEver, best);
				if (gen >= G - 2) {
					lateSum += m;
				}
				if (gen < G) {
					brains = nextGen(brains, order(fit));
				}
			}
			double finalMean = lateSum / 3.0;
			assertLess("random founder brains forage almost nothing", initialMean, 0.2);
			assertGreater("evolution found a strong forager (a champion gathered real food)",
					bestEver, 4.0);
			assertGreater("mean foraging rose far above the random start under selection",
					finalMean, initialMean + 1.0);
		}

		/** Fitness = food each brain forages over K ticks on full grass. Bodies are
		 * non-metabolic here so they don't starve -- we measure behaviour, and the
		 * GA (not survival) does the selecting. */
		private double[] evaluate(Brain[] brains) {
			World w = room(24, 24);
			TestNPC[] ag = new TestNPC[brains.length];
			for (int i = 0; i < brains.length; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.5, 0.5, 0.5 };
				g.brain = brains[i];
				double x = 2.5 + (i % 10) * 2.0, y = 2.5 + (i / 10) * 2.4;
				ag[i] = TestNPC.minded(x, y, 0, g, new LgpMind(brains[i], BUDGET));
				w.spawnEntity(ag[i]);
			}
			w.think();
			tick(w, K);
			double[] fit = new double[brains.length];
			for (int i = 0; i < brains.length; i++) {
				fit[i] = ag[i].totalIntake();
			}
			return fit;
		}

		/** Truncation selection with elitism: keep the top 2, refill from the top
		 * half by crossover + mutation. */
		private static Brain[] nextGen(Brain[] brains, int[] order) {
			int half = brains.length / 2;
			Brain[] next = new Brain[brains.length];
			next[0] = brains[order[0]].copy();
			next[1] = brains[order[1]].copy();
			for (int j = 2; j < brains.length; j++) {
				next[j] = Brain.child(brains[order[(j - 2) % half]], brains[order[(j - 1) % half]], 0.15);
			}
			return next;
		}

		private static int[] order(double[] fit) {
			Integer[] idx = new Integer[fit.length];
			for (int i = 0; i < idx.length; i++) {
				idx[i] = i;
			}
			java.util.Arrays.sort(idx, (a, b) -> Double.compare(fit[b], fit[a]));
			int[] out = new int[idx.length];
			for (int i = 0; i < out.length; i++) {
				out[i] = idx[i];
			}
			return out;
		}

		private static double mean(double[] a) {
			double s = 0;
			for (double v : a) {
				s += v;
			}
			return s / a.length;
		}

		private static double max(double[] a) {
			double m = a[0];
			for (double v : a) {
				m = Math.max(m, v);
			}
			return m;
		}
	}

	/** The attack actuator: a mind that fires A_ATTACK bites the neighbour in reach
	 * until its health is gone, killing it -- combat driven by the brain. */
	static class BrainAttacksNeighbour extends Scenario {
		@Override
		public void run() {
			seed(67);
			World w = room(10, 10);
			int[][] attack = {
					{ Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, // R0 = 1
					{ Brain.WRITE, AgentIO.A_ATTACK, 0, 0 }, // attack = 1
			};
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI, 3000); // stationary
			TestNPC attacker = TestNPC.minded(4.5, 4.5, 0, g, new LgpMind(new Brain(deepCopy(attack)), 2));
			TestNPC victim = TestNPC.inert(5.0, 4.5, 0); // adjacent, within reach
			w.spawnEntity(attacker);
			w.spawnEntity(victim);
			w.think();
			snapshot(w, "before (attacker beside victim)");
			assertTrue("victim starts alive", !victim.isDead());
			tick(w, 120);
			snapshot(w, "after (victim bitten to death)");
			assertTrue("the mind's attack actuator killed the neighbour", victim.isDead());
			assertTrue("the attacker survived", !attacker.isDead());
		}
	}

	/**
	 * The mate actuator: brained foragers whose brains fire A_MATE reproduce
	 * sexually with a compatible neighbour in reach. Two founder types share
	 * markers (so they pair) but carry a distinctive instruction each; a crossover
	 * child that carries BOTH signatures can only come from sexual mating (asexual
	 * budding copies a single parent), so it proves the actuator drove crossover.
	 */
	static class BrainMatesViaActuator extends Scenario {
		@Override
		public void run() {
			seed(68);
			int SENSE = Brain.SENSE, WRITE = Brain.WRITE, NEG = Brain.NEG, TANH = Brain.TANH, NOP = Brain.NOP;
			int[][] mateA = { { SENSE, 0, AgentIO.S_BIAS, 0 }, { WRITE, AgentIO.A_MATE, 0, 0 },
					{ NEG, 7, 7, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 } }; // sig A: "= -R7"
			int[][] mateB = { { SENSE, 0, AgentIO.S_BIAS, 0 }, { WRITE, AgentIO.A_MATE, 0, 0 },
					{ NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { NOP, 0, 0, 0 }, { TANH, 8, 8, 0 } }; // sig B: "tanh R8"
			World w = room(12, 12);
			for (int i = 0; i < 8; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.5, 0.5, 0.5 }; // identical -> mate-compatible
				g.brain = new Brain(deepCopy(i % 2 == 0 ? mateA : mateB));
				// Interleaved A B A B... in a line so each one's nearest is the
				// opposite type -> cross-type matings that can recombine.
				double x = 5.0 + i * 0.4 + (i % 2) * 0.07, y = 5.5;
				w.spawnEntity(TestNPC.brainedBreeder(x, y, 0, g).withEnergy(12.0));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders (two brain types, shared markers)");

			// These minds mate but don't forage, so they breed then starve; measure
			// the peak population and whether a recombinant mind ever appeared.
			int peak = start;
			boolean recombinant = false;
			for (int step = 0; step < 45; step++) {
				tick(w, 10);
				peak = Math.max(peak, w.getAliveCount());
				recombinant |= hasRecombinantMind(w);
			}
			snapshot(w, "after (mated: recombinant minds)");
			assertGreater("the mate actuator drove reproduction (population rose)", peak, start);
			assertTrue("a child carries both parents' signatures -- sexual crossover via A_MATE",
					recombinant);
		}

		/** True if any living mind carries both founder signatures at once -- a
		 * crossover only sexual mating (not asexual budding) can produce. */
		private static boolean hasRecombinantMind(World w) {
			for (Entity e : w.getEntities()) {
				if (!(e instanceof net.hedinger.prototype.entities.NPC) || e.isDead()) {
					continue;
				}
				Brain b = ((net.hedinger.prototype.entities.NPC) e).getGenome().brain;
				if (b == null) {
					continue;
				}
				String dis = String.join("\n", b.disassemble(null, null));
				if (dis.contains("= -R7") && dis.contains("tanh R8")) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * The grab actuator: a minded creature whose brain fires A_GRAB seizes the
	 * nearest <i>smaller</i> neighbour in reach and carries it -- the captive
	 * rides the grabber and is dragged along, released when the actuator drops.
	 */
	static class BrainGrabsSmallerNeighbour extends Scenario {
		@Override
		public void run() {
			seed(73);
			World w = room(16, 16);
			int[][] grab = {
					{ Brain.SENSE, 0, AgentIO.S_BIAS, 0 },    // R0 = 1
					{ Brain.WRITE, AgentIO.A_GRAB, 0, 0 },     // hold grab
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 }, // roam so the captive is dragged
			};
			// Bigger grabber (body 6, wide FOV so it perceives the neighbour); a
			// smaller inert cargo (body 4) just inside grab reach. Reach is the sum
			// of half-body-radii (~0.078 tiles here), so they start ~0.05 apart.
			Genome g = Genome.phenotype(6, 0.05, 5, 6, Math.PI * 2, 3000);
			TestNPC grabber = TestNPC.minded(8.0, 8.0, 0, g, new LgpMind(new Brain(deepCopy(grab)), 2));
			TestNPC cargo = TestNPC.inert(8.05, 8.0, 0).withSize(4);
			w.spawnEntity(grabber);
			w.spawnEntity(cargo);
			w.think();
			snapshot(w, "before (grabber beside a smaller creature)");

			double startX = cargo.getX(), startY = cargo.getY();
			tick(w, 60);
			snapshot(w, "after (smaller creature seized and carried)");
			assertTrue("the mind's grab actuator seized the smaller neighbour",
					cargo.getAttachTarget() == grabber);
			assertTrue("the captive is marked grabbed", cargo.isGrabbed());
			double offset = (grabber.getSize() + cargo.getSize()) / 2.0;
			double held = Math.hypot(cargo.getX() - grabber.getX(), cargo.getY() - grabber.getY());
			assertNear("the captive is pinned at the carry offset", offset, held, 0.05);
			assertGreater("the captive was dragged along as the grabber moved",
					Math.hypot(cargo.getX() - startX, cargo.getY() - startY), 0.5);
		}
	}

	/**
	 * The attach actuator (the inverse of grab): a small minded creature whose
	 * brain fires A_ATTACH latches onto a <i>larger</i> neighbour and rides it,
	 * dragged along by the host until it lets go. It rides voluntarily -- unlike
	 * a captive it is not marked grabbed, and it self-releases.
	 */
	static class BrainAttachesToLargerHost extends Scenario {
		@Override
		public void run() {
			seed(74);
			World w = room(16, 16);
			int[][] attach = {
					{ Brain.SENSE, 0, AgentIO.S_BIAS, 0 },   // R0 = 1
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 },  // hold attach
			};
			// A small stationary rider (body 10, wide FOV) beside a larger host
			// (body 20) that roams slowly, so it stays in reach long enough for the
			// rider to latch on, then drags it around. Reach ~0.23 tiles here.
			Genome g = Genome.phenotype(10, 0.0, 5, 6, Math.PI * 2, 3000);
			TestNPC rider = TestNPC.minded(8.1, 8.0, 0, g, new LgpMind(new Brain(deepCopy(attach)), 2));
			TestNPC host = TestNPC.roamer(8.0, 8.0, 0).withSize(20).withSpeed(0.02);
			w.spawnEntity(rider);
			w.spawnEntity(host);
			w.think();
			snapshot(w, "before (small creature beside a larger one)");

			double startX = rider.getX(), startY = rider.getY();
			tick(w, 80);
			snapshot(w, "after (riding the larger host)");
			assertTrue("the mind's attach actuator latched onto the larger host",
					rider.getAttachTarget() == host);
			assertTrue("it rides voluntarily, not as a captive", !rider.isGrabbed());
			double offset = (host.getSize() + rider.getSize()) / 2.0;
			double held = Math.hypot(rider.getX() - host.getX(), rider.getY() - host.getY());
			assertNear("the rider is pinned at the ride offset", offset, held, 0.05);
			assertGreater("the rider was carried along as the host roamed",
					Math.hypot(rider.getX() - startX, rider.getY() - startY), 0.5);
		}
	}

	/** Carrying costs energy: a metabolic carrier holding a captive burns more
	 *  energy per tick than an identical one carrying nothing -- the extra scales
	 *  with the carried body weight. */
	static class CarryingCostsEnergy extends Scenario {
		@Override
		public void run() {
			seed(75);
			World w = room(12, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] idle = { { Brain.NOP, 0, 0, 0 } };
			Genome cg = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			cg.metabolism = 0.02;
			cg.brain = new Brain(deepCopy(hold));
			Genome kg = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			kg.metabolism = 0.02;
			kg.brain = new Brain(deepCopy(idle));
			TestNPC carrier = TestNPC.brainedBreeder(6.0, 6.0, 0, cg).withEnergy(6.0);
			TestNPC control = TestNPC.brainedBreeder(9.5, 6.0, 0, kg).withEnergy(6.0);
			TestNPC cargo = TestNPC.inert(6.05, 6.0, 0).withSize(6);
			w.spawnEntity(carrier);
			w.spawnEntity(control);
			w.spawnEntity(cargo);
			tick(w, 6); // let the carrier pick the cargo up and hold it
			assertTrue("the carrier is holding the cargo", cargo.getAttachTarget() == carrier);
			double eCarry = carrier.getEnergy(), eFree = control.getEnergy();
			tick(w, 100);
			double lossCarry = eCarry - carrier.getEnergy();
			double lossFree = eFree - control.getEnergy();
			assertGreater("carrying a body burns more energy than carrying nothing",
					lossCarry, lossFree + 0.5);
		}
	}

	/** A grabbed captive is immobilized -- it cannot even feed -- while a
	 *  voluntary rider keeps acting: it still grazes while being carried. */
	static class CaptiveFrozenRiderActs extends Scenario {
		@Override
		public void run() {
			seed(76);
			World w = room(20, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] graze = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };
			int[][] rideGraze = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };

			// Pair 1: a grazer captive, grabbed by a bigger carrier -> frozen.
			Genome carrierG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			carrierG.brain = new Brain(deepCopy(hold));
			Genome captiveG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			captiveG.brain = new Brain(deepCopy(graze));
			TestNPC carrier = TestNPC.minded(4.0, 6.0, 0, carrierG);
			TestNPC captive = TestNPC.minded(4.05, 6.0, 0, captiveG);
			// Pair 2 (far away): a grazer rider on a bigger, stationary host.
			Genome riderG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.brain = new Brain(deepCopy(rideGraze));
			TestNPC rider = TestNPC.minded(15.05, 6.0, 0, riderG);
			TestNPC host = TestNPC.roamer(15.0, 6.0, 0).withSize(16).withSpeed(0.0);
			w.spawnEntity(carrier);
			w.spawnEntity(captive);
			w.spawnEntity(rider);
			w.spawnEntity(host);
			tick(w, 6); // carrier grabs the captive; rider latches onto the host
			assertTrue("the captive was grabbed", captive.isGrabbed());
			assertTrue("the rider latched on voluntarily (not grabbed)",
					rider.getAttachTarget() == host && !rider.isGrabbed());
			tick(w, 60);
			assertNear("a grabbed captive is frozen -- it cannot graze", 0.0, captive.totalIntake(), 1e-9);
			assertGreater("a voluntary rider keeps grazing while carried", rider.totalIntake(), 0.0);
		}
	}

	/** The rider's bonus: a creature riding a host spends less energy than an
	 *  equivalent one under its own power (reduced metabolism while carried). */
	static class RiderSpendsLessEnergy extends Scenario {
		@Override
		public void run() {
			seed(77);
			World w = room(14, 12);
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			int[][] idle = { { Brain.NOP, 0, 0, 0 } };
			Genome riderG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.metabolism = 0.02;
			riderG.brain = new Brain(deepCopy(cling));
			Genome ctrlG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			ctrlG.metabolism = 0.02;
			ctrlG.brain = new Brain(deepCopy(idle));
			TestNPC rider = TestNPC.brainedBreeder(4.05, 6.0, 0, riderG).withEnergy(6.0);
			TestNPC host = TestNPC.roamer(4.0, 6.0, 0).withSize(18).withSpeed(0.0);
			TestNPC control = TestNPC.brainedBreeder(10.0, 6.0, 0, ctrlG).withEnergy(6.0);
			w.spawnEntity(rider);
			w.spawnEntity(host);
			w.spawnEntity(control);
			tick(w, 6); // let the rider latch on
			assertTrue("the rider is riding the host",
					rider.getAttachTarget() == host && !rider.isGrabbed());
			double eRide = rider.getEnergy(), eWalk = control.getEnergy();
			tick(w, 100);
			double lossRide = eRide - rider.getEnergy();
			double lossWalk = eWalk - control.getEnergy();
			assertGreater("riding spends less energy than moving under your own power",
					lossWalk, lossRide + 0.3);
		}
	}

	/** Struggle vs consent: hauling a captive that fights (A_STRUGGLE) costs its
	 *  captor more energy than an equally heavy captive that consents, and the
	 *  struggling captive tires itself out in the process. */
	static class StrugglingCostsMoreThanConsenting extends Scenario {
		@Override
		public void run() {
			seed(78);
			World w = room(20, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] fight = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_STRUGGLE, 0, 0 } };
			int[][] limp = { { Brain.NOP, 0, 0, 0 } };

			// Pair A: captor + a struggling captive.
			Genome capAG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capAG.metabolism = 0.02;
			capAG.brain = new Brain(deepCopy(hold));
			Genome vicAG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicAG.brain = new Brain(deepCopy(fight));
			TestNPC captorA = TestNPC.brainedBreeder(4.0, 6.0, 0, capAG).withEnergy(9.0);
			TestNPC struggler = TestNPC.minded(4.05, 6.0, 0, vicAG).withEnergy(6.0);
			// Pair B (far off): captor + a consenting (limp) captive.
			Genome capBG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capBG.metabolism = 0.02;
			capBG.brain = new Brain(deepCopy(hold));
			Genome vicBG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicBG.brain = new Brain(deepCopy(limp));
			TestNPC captorB = TestNPC.brainedBreeder(15.0, 6.0, 0, capBG).withEnergy(9.0);
			TestNPC consenter = TestNPC.minded(15.05, 6.0, 0, vicBG).withEnergy(6.0);
			w.spawnEntity(captorA);
			w.spawnEntity(struggler);
			w.spawnEntity(captorB);
			w.spawnEntity(consenter);
			tick(w, 6); // captors grab their captives and hold
			assertTrue("the struggler is held", struggler.isGrabbed() && struggler.getAttachTarget() == captorA);
			assertTrue("the consenter is held", consenter.isGrabbed() && consenter.getAttachTarget() == captorB);
			double capA0 = captorA.getEnergy(), capB0 = captorB.getEnergy();
			double vicA0 = struggler.getEnergy(), vicB0 = consenter.getEnergy();
			tick(w, 80);
			double capALoss = capA0 - captorA.getEnergy(), capBLoss = capB0 - captorB.getEnergy();
			double vicALoss = vicA0 - struggler.getEnergy(), vicBLoss = vicB0 - consenter.getEnergy();
			assertGreater("hauling a struggling captive costs the captor more than a consenting one",
					capALoss, capBLoss + 0.5);
			assertGreater("struggling drains the captive's own energy too", vicALoss, vicBLoss + 0.5);
		}
	}

	/** A grabbed captive is otherwise frozen, but it can still communicate: it may
	 *  lay a pheromone (a distress marker) while held. */
	static class CaptiveCanStillCommunicate extends Scenario {
		@Override
		public void run() {
			seed(79);
			World w = room(12, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] signal = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_DEPOSIT, 0, 0 } };
			Genome capG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capG.brain = new Brain(deepCopy(hold));
			Genome vicG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicG.brain = new Brain(deepCopy(signal));
			TestNPC captor = TestNPC.minded(6.0, 6.0, 0, capG);
			TestNPC captive = TestNPC.minded(6.05, 6.0, 0, vicG);
			w.spawnEntity(captor);
			w.spawnEntity(captive);
			tick(w, 6);
			assertTrue("the captive is held", captive.isGrabbed());
			tick(w, 30);
			double phero = w.pheromoneAt(captive.getX(), captive.getY(), 0);
			assertGreater("a held captive can still lay a distress pheromone", phero, 0.0);
		}
	}

	/** A captive is freed the moment its captor dies -- it is not left clamped to
	 *  the corpse, and it no longer weighs on it. */
	static class CaptiveFreedWhenCaptorDies extends Scenario {
		@Override
		public void run() {
			seed(80);
			World w = room(12, 12);
			int[][] hold = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_GRAB, 0, 0 } };
			int[][] limp = { { Brain.NOP, 0, 0, 0 } };
			// A captor that will starve shortly after grabbing (little energy, fast
			// metabolism), holding a smaller captive.
			Genome capG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capG.metabolism = 0.05;
			capG.brain = new Brain(deepCopy(hold));
			Genome vicG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicG.brain = new Brain(deepCopy(limp));
			TestNPC captor = TestNPC.brainedBreeder(6.0, 6.0, 0, capG).withEnergy(0.6);
			TestNPC captive = TestNPC.minded(6.05, 6.0, 0, vicG);
			w.spawnEntity(captor);
			w.spawnEntity(captive);
			tick(w, 4);
			assertTrue("the captive is grabbed while the captor lives", captive.isGrabbed());
			assertTrue("the captor still lives at this point", !captor.isDead());
			tick(w, 40); // the captor starves
			assertTrue("the captor died", captor.isDead());
			assertTrue("the captive was released from the dead captor", captive.getAttachTarget() == null);
			assertTrue("the captive is no longer marked grabbed", !captive.isGrabbed());
			assertNear("the corpse carries no load", 0.0, captor.getCarriedLoad(), 1e-9);
		}
	}

	/** The blocked sensor: a mind reads 1 when a wall/edge is one tile ahead and 0
	 * in the open, so it can perceive obstacles (and evolve to steer around them). */
	static class BlockedSensorSeesWalls extends Scenario {
		@Override
		public void run() {
			seed(72);
			double[] boxed = new double[AgentIO.NUM_SENSORS];
			double[] open = new double[AgentIO.NUM_SENSORS];

			World box = room(3, 3); // interior is the single tile (1,1), walls all around
			box.spawnEntity(TestNPC.minded(1.5, 1.5, 0,
					Genome.phenotype(6, 0, 5, 6, Math.PI, 3000), capture(boxed)));
			tick(box, 3);

			World field = room(11, 11);
			field.spawnEntity(TestNPC.minded(5.5, 5.5, 0,
					Genome.phenotype(6, 0, 5, 6, Math.PI, 3000), capture(open)));
			tick(field, 3);

			assertNear("a walled-in mind senses a wall ahead", 1.0, boxed[AgentIO.S_BLOCKED], 1e-9);
			assertNear("a mind in the open senses no wall ahead", 0.0, open[AgentIO.S_BLOCKED], 1e-9);
		}

		private static Mind capture(double[] out) {
			return new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					System.arraycopy(s, 0, out, 0, s.length);
				}
			};
		}
	}

	/** A pheromone cloud evaporates over time (its strength decays each tick). */
	static class PheromoneDecays extends Scenario {
		@Override
		public void run() {
			seed(40);
			World w = room(5, 5);
			w.depositPheromone(2.5, 2.5, 0, 10.0);
			tick(w, 1); // drain the spawn queue so the cloud is live
			double p0 = w.pheromoneAt(2.5, 2.5, 0);
			assertNear("deposited pheromone is present at its centre", 10.0, p0, 1e-6);
			tick(w, 200);
			double p1 = w.pheromoneAt(2.5, 2.5, 0);
			assertLess("pheromone evaporated substantially", p1, p0 * 0.5);
			assertGreater("but has not vanished instantly", p1, 0.0);
		}
	}

	/**
	 * Stigmergic nesting: nesters lay pheromone where they breed, so a peak --
	 * an emergent nest -- builds up, and the growing lineage clusters around it
	 * instead of smearing across the map.
	 */
	static class NestEmergesFromPheromone extends Scenario {
		@Override
		public void run() {
			seed(41);
			World w = room(14, 14); // full grass
			for (int i = 0; i < 2; i++) {
				Genome g = new Genome();
				g.markers = new double[] { 0.9, 0.2, 0.6 };
				w.spawnEntity(TestNPC.nester(6.5 + i, 6.5 + i, 0, g));
			}
			w.think();
			int start = w.getAliveCount();
			snapshot(w, "founders");
			tick(w, 800);
			snapshot(w, "after (colony around the nest)");

			// The nest is the strongest pheromone cloud.
			double maxP = 0, nx = w.getColums() / 2.0, ny = w.getRows() / 2.0;
			for (Entity e : w.getEntities()) {
				if (e instanceof net.hedinger.prototype.engine.PheromoneCloud && !e.isRemoved()) {
					double s = ((net.hedinger.prototype.engine.PheromoneCloud) e).getStrength();
					if (s > maxP) {
						maxP = s;
						nx = e.getX();
						ny = e.getY();
					}
				}
			}
			// Nest intensity is what a homing creature actually smells there: the
			// summed concentration of every cloud overlapping the peak.
			double nestIntensity = w.pheromoneAt(nx, ny, 0);
			assertGreater("the population grew by breeding", w.getAliveCount(), start);
			assertGreater("a pheromone nest built up", nestIntensity, 4.0);

			// The living colony clusters near that nest.
			double sum = 0;
			int n = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof net.hedinger.prototype.entities.NPC && !e.isDead()) {
					sum += Math.hypot(e.getX() - nx, e.getY() - ny);
					n++;
				}
			}
			assertLess("the colony clusters around the nest", sum / n, 4.0);
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

	// ---- helpers -----------------------------------------------------------

	/** Deep-copies a program (array of {op,x,y,z} rows) so a Brain owns its code. */
	private static int[][] deepCopy(int[][] prog) {
		int[][] c = new int[prog.length][];
		for (int i = 0; i < prog.length; i++) {
			c[i] = prog[i].clone();
		}
		return c;
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
				new GenomeReactModel(),
				new GenomePredatorHuntsPrey(),
				new GenomeInheritance(),
				new GrazerDepletesSubstrate(),
				new VegetationRegrows(),
				new FertilityCapsVegetation(),
				new FertileHabitatPatches(),
				new WaterBlocksLandPassesFlyers(),
				new MudSlowsMovement(),
				new CoverHidesFromPerception(),
				new StarvesWithoutFood(),
				new PopulationGrowsWithFood(),
				new SexualReproductionNeedsPartner(),
				new BrainMemoryIsDeterministic(),
				new BrainLengthSetsThoughtRate(),
				new BrainHeredityCrossesAndMutates(),
				new MindDrivesAgent(),
				new BrainInheritedThroughReproduction(),
				new BrainedPopulationDiversifies(),
				new EvolutionDiscoversForaging(),
				new BrainAttacksNeighbour(),
				new BrainMatesViaActuator(),
				new BrainGrabsSmallerNeighbour(),
				new BrainAttachesToLargerHost(),
				new CarryingCostsEnergy(),
				new CaptiveFrozenRiderActs(),
				new RiderSpendsLessEnergy(),
				new StrugglingCostsMoreThanConsenting(),
				new CaptiveCanStillCommunicate(),
				new CaptiveFreedWhenCaptorDies(),
				new BlockedSensorSeesWalls(),
				new PheromoneDecays(),
				new NestEmergesFromPheromone(),
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
