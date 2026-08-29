package net.hedinger.prototype.simtest;

import net.hedinger.prototype.engine.Entity;
import net.hedinger.prototype.engine.ProcCreature;
import net.hedinger.prototype.engine.Tile;
import net.hedinger.prototype.engine.World;
import net.hedinger.prototype.entities.AgentIO;
import net.hedinger.prototype.entities.Brain;
import net.hedinger.prototype.entities.Door;
import net.hedinger.prototype.entities.Genome;
import net.hedinger.prototype.entities.Item;
import net.hedinger.prototype.entities.LgpMind;
import net.hedinger.prototype.entities.Mind;
import net.hedinger.prototype.entities.NPC;
import net.hedinger.prototype.entities.Sound;
import net.hedinger.prototype.entities.Species;
import net.hedinger.prototype.render.DronePainter;
import net.hedinger.prototype.render.LoaderPainter;

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
	 * Lethal damage kills; the corpse then decays on its own after its
	 * deathspan, and scavenging clears it sooner.
	 *
	 * <p>Corpse-decay contract: a dead body ages toward removal one tick at a
	 * time, so after {@code deathspan} ticks the engine purges it even with no
	 * scavengers -- deathspan is how long a corpse lingers. Scavenging ({@code
	 * eat()} decrements age) still clears it early.
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
			tick(w, 50); // partway through the 100-tick deathspan
			assertTrue("corpse lingers while within its deathspan", !h.isRemoved());
			tick(w, 80); // total 130 ticks dead, past the deathspan
			assertTrue("corpse decays on its own once its deathspan elapses", h.isRemoved());

			// Scavenging clears a body well before its deathspan would.
			TestNPC h2 = TestNPC.inert(4.5, 4.5, 0).withDeathspan(1000);
			w.spawnEntity(h2);
			tick(w, 2);
			h2.damage(200);
			tick(w, 1);
			assertTrue("fresh corpse present", !h2.isRemoved());
			h2.eat(1001); // scavenge the whole deathspan budget at once
			tick(w, 1);
			assertTrue("a scavenged corpse is removed early", h2.isRemoved());
		}
	}

	/**
	 * A body takes as long to rot away as it took to build, and both scale with
	 * mass. Pins the two to each other: the corpse span is read off the growth
	 * constants, so tuning childhood retunes decay and neither can drift.
	 * Also pins the decay clock the renderers draw from.
	 */
	static class CorpseRotsForAsLongAsItTookToGrow extends Scenario {
		@Override
		public void run() {
			seed(28);
			World w = room(9, 9);
			Genome small = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			Genome big = Genome.phenotype(18, 0.0, 5, 6, Math.PI * 2, 100000);
			// breeder, not grazer(x,y,z,g): the latter is a body-only fixture that
			// never runs configureGenomeBody, so it keeps the default deathspan and
			// would pin nothing.
			TestNPC s = TestNPC.breeder(3.5, 3.5, 0, small);
			TestNPC b = TestNPC.breeder(5.5, 5.5, 0, big);
			w.spawnEntity(s);
			w.spawnEntity(b);
			tick(w, 2);

			assertEquals("a small body rots for exactly its childhood",
					net.hedinger.prototype.entities.NPC.growthTicks(6), s.getDeathspan());
			assertEquals("and a big one for its own, longer childhood",
					net.hedinger.prototype.entities.NPC.growthTicks(18), b.getDeathspan());
			assertGreater("mass is the variable factor: the bigger body lasts longer",
					b.getDeathspan() - s.getDeathspan(), 0);

			// The decay clock the renderers read: 0 while alive, then 0 -> 1 across
			// the span. Without this a corpse cannot be drawn rotting at all.
			assertEquals("a living body reports no decay", 0, (long) (s.decayProgress() * 100));
			b.damage(500);
			tick(w, 1);
			assertTrue("dead", b.isDead());
			assertLess("a fresh corpse has barely decayed", b.decayProgress(), 0.05);
			tick(w, b.getDeathspan() / 2);
			assertNear("halfway through the span it is halfway rotted",
					0.5, b.decayProgress(), 0.05);
		}
	}

	/**
	 * A scavenger makes its living off carrion, and eating it IS decomposition:
	 * the same bite that feeds the eater ages the body toward removal. Pins the
	 * whole third trophic level -- that carrion feeds, that it decomposes faster
	 * for being eaten, that freshness prices the meal, and that a scavenger has no
	 * interest in the living.
	 */
	static class ScavengerEatsCarrionAndHastensItsDecay extends Scenario {
		@Override
		public void run() {
			seed(29);
			World w = room(12, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);

			// Two identical worlds, ticked in lockstep. The ONLY difference is that
			// one carcass has a scavenger stood on it -- an earlier version of this
			// test let the eaten body run one tick longer than the control, and a
			// one-tick head start alone satisfied "decays faster", so the assertion
			// passed against a sabotage that removed the decay entirely.
			World w2 = room(12, 12);
			TestNPC body = TestNPC.breeder(6.5, 6.5, 0, g);
			TestNPC alone = TestNPC.breeder(6.5, 6.5, 0, g);
			w.spawnEntity(body);
			w2.spawnEntity(alone);
			tick(w, 2);
			tick(w2, 2);
			body.damage(500);
			alone.damage(500);
			tick(w, 1);
			tick(w2, 1);
			assertTrue("there is a carcass", body.isDead());

			// Driven by a scripted mind that simply holds "eat": this pins the BODY's
			// scavenging, not whatever a starter brain happens to have evolved into.
			Mind feeder = (sensors, act) -> act[AgentIO.A_EAT] = 1;
			TestNPC scav = TestNPC.minded(6.5, 6.5, 0, g, feeder)
					.withClade(Genome.Clade.SCAVENGER).withHunger(1.0);
			w.spawnEntity(scav);
			tick(w, 1);
			tick(w2, 1);
			assertTrue("a scavenger is its own trophic level", "scavenger".equals(scav.ecoRole()));

			double fed = scav.totalSwallowed();
			tick(w, 60);
			tick(w2, 60);
			assertGreater("carrion feeds the scavenger", scav.totalSwallowed() - fed, 0);
			// Both corpses have now been dead for exactly the same number of ticks,
			// so every bit of this gap is the eating.
			assertGreater("an eaten body decays far faster than one left alone",
					body.decayProgress() - alone.decayProgress(), 0.2);
		}
	}

	/**
	 * A scavenger's forage channel points at carrion, not at grass -- the whole
	 * mechanism by which it inherits the forage intent every starter brain already
	 * runs, without a sensor or a policy of its own.
	 */
	static class ScavengerForagesTowardBodies extends Scenario {
		@Override
		public void run() {
			seed(30);
			World w = room(16, 10);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);

			// Five tiles east. Carrion is smelled at a flat CARRION_SCENT_R rather than
			// seen at the genome's losRange, so this is well inside range; placed
			// beyond it the scavenger correctly senses nothing.
			TestNPC body = TestNPC.breeder(8.5, 5.5, 0, g);
			w.spawnEntity(body);
			tick(w, 2);
			body.damage(500);
			tick(w, 1);

			// Inert minds and a fixed heading: bearing is measured in the heading
			// frame, so a creature free to turn would make "dead ahead" a statement
			// about what its brain did rather than about where its food is.
			Mind still = (sensors, act) -> { };
			// A genome each: clade lives in the genome now, so two bodies sharing one
			// instance would be the same animal wearing two positions.
			TestNPC scav = TestNPC.minded(3.5, 5.5, 0, g.copy(), still)
					.withClade(Genome.Clade.SCAVENGER).withHeading(0);
			TestNPC grazer = TestNPC.minded(3.5, 7.5, 0, g.copy(), still).withHeading(0);
			w.spawnEntity(scav);
			w.spawnEntity(grazer);
			tick(w, 1);
			snapshot(w, "a carcass east, a scavenger and a grazer west");
			tick(w, 2); // let both sense at least once after being admitted

			assertGreater("the scavenger senses the carcass as its forage",
					scav.sensorSnapshot()[AgentIO.S_FORAGE_PROX], 0);
			assertNear("and it is dead ahead of it",
					0, scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING], 0.1);
			// The grazer stands on grass, so its forage is underfoot -- much nearer
			// than a body eleven tiles away. Same channel, different food.
			assertGreater("a grazer's forage is the ground, not the body",
					grazer.sensorSnapshot()[AgentIO.S_FORAGE_PROX],
					scav.sensorSnapshot()[AgentIO.S_FORAGE_PROX]);
		}
	}

	/**
	 * A carcass is worth what a kill of the same mass is worth. Meat is meat: what
	 * separates a hunter from a scavenger is the chase and the risk on one side and
	 * the search on the other, not the price of the meal.
	 *
	 * <p>This also pins that rot is charged exactly once. Eating advances the same
	 * clock decay does, so a half-rotted body has half its bites left and yields
	 * half as much on its own; multiplying the per-bite rate by freshness as well
	 * discounted the same rot twice and left a whole fresh carcass worth half a
	 * kill.
	 */
	static class ACarcassIsWorthWhatAKillIsWorth extends Scenario {
		@Override
		public void run() {
			seed(34);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			Mind feeder = (sensors, act) -> act[AgentIO.A_EAT] = 1;

			// Strip one whole carcass, from perfectly fresh, and total the takings.
			World w = room(12, 12);
			TestNPC body = TestNPC.breeder(6.5, 6.5, 0, g);
			w.spawnEntity(body);
			tick(w, 2);
			body.damage(500);
			tick(w, 1);
			assertTrue("there is a fresh carcass", body.isDead());
			double bodyMass = body.bodyMass();

			TestNPC scav = TestNPC.minded(6.5, 6.5, 0, g, feeder)
					.withClade(Genome.Clade.SCAVENGER).withHunger(1.0);
			w.spawnEntity(scav);
			tick(w, 1);
			double before = scav.totalSwallowed();
			// Long enough to consume the body outright; the swallowed ledger counts
			// only what fit the stomach, so the gain is the meal and nothing else.
			tick(w, 400);
			assertTrue("the carcass has been eaten away", body.isRemoved() || body.decayProgress() >= 1.0);
			double fromCarrion = scav.totalSwallowed() - before;

			// The same mass taken as prey: a predator consuming a whole body earns
			// MEAT_ENERGY per unit of it.
			double fromKill = 2.5 * bodyMass;
			assertNear("a whole carcass pays what a whole kill pays",
					fromKill, fromCarrion, fromKill * 0.05);
		}
	}

	/**
	 * A creature is drawn as what it is. The body plan reads the genome's clade and
	 * whether it flies, so a grazer, a scavenger and a hunter of identical descent
	 * are three different animals rather than one animal in three colours.
	 *
	 * <p>It used to read the markers alone — the mate-recognition barcode — so the
	 * silhouette said which lineage a creature came from and nothing about what it
	 * was. Measured on a settled world, 349 creatures wore 95 shapes with no
	 * systematic difference between the roles; you could not tell a hunter from a
	 * grazer by looking, which for an ecology built to be watched is most of it.
	 */
	static class ABodyIsShapedByWhatItEats extends Scenario {
		@Override
		public void run() {
			seed(38);
			// One lineage, one set of markers, one size: everything the old body plan
			// looked at is held identical, so any difference is the clade.
			Genome grazer = Genome.phenotype(9, 0.05, 5, 6, Math.PI / 2, 100000);
			grazer.markers = new double[] { 0.4, 0.6, 0.5 };
			Genome scav = grazer.copy();
			scav.clade = Genome.Clade.SCAVENGER;
			Genome hunter = grazer.copy();
			hunter.clade = Genome.Clade.PREDATOR;
			Genome flier = grazer.copy();
			flier.flying = true;

			long kg = ProcCreature.shapeKey(ProcCreature.phenotype(grazer));
			long ks = ProcCreature.shapeKey(ProcCreature.phenotype(scav));
			long kh = ProcCreature.shapeKey(ProcCreature.phenotype(hunter));
			long kf = ProcCreature.shapeKey(ProcCreature.phenotype(flier));
			assertTrue("a scavenger is not shaped like a grazer", ks != kg);
			assertTrue("a hunter is not shaped like a grazer", kh != kg);
			assertTrue("a hunter is not shaped like a scavenger", kh != ks);
			assertTrue("a flier is not shaped like a walker", kf != kg);

			// And the features are the ones a viewer can name at a glance.
			ProcCreature.Phenotype ps = ProcCreature.phenotype(scav);
			ProcCreature.Phenotype ph = ProcCreature.phenotype(hunter);
			ProcCreature.Phenotype pg = ProcCreature.phenotype(grazer);
			assertTrue("the scavenger wears the feelers it smells with", ps.antennae);
			assertTrue("on the long segmented body, not the grazer's", ps.form != pg.form);
			assertTrue("and the grazer does not", !pg.antennae);
			assertTrue("the hunter carries a tail", ph.tail);
			assertTrue("and the grazer does not", !pg.tail);
			assertTrue("a flier keeps one pair of limbs",
					ProcCreature.phenotype(flier).legs == 1);

			// The clade rides in the genome, so heredity carries it without being asked.
			Genome kid = Genome.child(scav, 0.1);
			assertTrue("a scavenger's young inherit the clade",
					Genome.Clade.SCAVENGER == kid.clade);
			assertEquals("and so are drawn as scavengers too", ks,
					ProcCreature.shapeKey(ProcCreature.phenotype(kid)));
		}
	}

	/**
	 * A flyer and a walker do not shove each other; two of a kind do.
	 *
	 * <p>Flight in this world is not a height. Z is the level a body stands on,
	 * so a flyer occupies exactly the same cell space as a walker and the
	 * separation spring saw two bodies at one point — the steward's drone barged
	 * grazers along the ground it was flying over, and shouldered its own quarry
	 * away from the emitter it was trying to hold on it.
	 *
	 * <p>Every other close interaction already asked. A grounded creature cannot
	 * seize a flyer out of the air; biting and mating take flight into account.
	 * The spring was the one that never did.
	 *
	 * <p>All three pairings are checked, because the exemption has to be exactly
	 * one of them: an implementation that simply stopped flyers colliding with
	 * anything would pass a test that only looked at the mixed pair, and four
	 * drones would then stack on one charge pad.
	 */
	static class AFlyerAndAWalkerDoNotShoveEachOther extends Scenario {
		/** Two bodies dropped on the same spot; how far apart they end up. */
		private double gapAfter(boolean aFlies, boolean bFlies) {
			seed(521);
			World w = room(16, 16);
			Genome g = Genome.phenotype(64, 0.0, 8, 4, Math.PI, 100000);
			TestNPC a = TestNPC.breeder(8.45, 8.5, 0, g).withReproCooldown(100000);
			TestNPC b = TestNPC.breeder(8.55, 8.5, 0, g).withReproCooldown(100000);
			if (aFlies) {
				a = a.withFlying();
			}
			if (bFlies) {
				b = b.withFlying();
			}
			w.spawnEntity(a);
			w.spawnEntity(b);
			w.think();
			tick(w, 40);
			return Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
		}

		@Override
		public void run() {
			double walkers = gapAfter(false, false);
			double flyers = gapAfter(true, true);
			double mixed = gapAfter(true, false);

			// Dropped a tenth of a tile apart, well inside the 0.36 their two
			// radii want. Both on the ground or both in the air, the spring
			// takes them back out to touching distance.
			assertLess("two walkers dropped on one spot push apart", 0.3, walkers);
			assertLess("and so do two flyers — a rank must not stack on one pad",
					0.3, flyers);
			// One of each: nothing to push against, so they stay where they were
			// put. This is the whole change, and it is asserted against the two
			// above rather than against a bare number so it cannot pass by the
			// spring having stopped working altogether.
			assertLess("but a flyer over a walker is not touching it", mixed, 0.15);
			assertLess("which is nothing like what two of a kind do",
					mixed * 2, Math.min(walkers, flyers));
		}
	}

	/**
	 * A floor is solid to the touch, and an opening is not.
	 *
	 * <p>{@code canTouch} folds the level index into the distance as though a
	 * storey were a tile, so a body directly underneath reads as 1.0 away — and
	 * any pair whose radii sum past that was in contact through the deck plate.
	 * The same reading that let sight and a taser cross a floor.
	 *
	 * <p>Nothing in the running world reaches this. Perception is filtered to
	 * one level before the neighbour list is built, so the collision spring
	 * never sees a body from another floor: measured at zero cross-level calls
	 * in six thousand ticks of the seeded world. That is exactly why the rule
	 * needs a scenario — it is held today by a filter in another class that
	 * happens to run first, and nothing would notice if that filter moved.
	 *
	 * <p>The bodies are deliberately huge. Reach is the sum of two radii, so a
	 * pair small enough not to span a storey would be spared by arithmetic
	 * rather than by the rule, and the test would pass on the bug.
	 */
	static class AFloorIsSolidToTheTouch extends Scenario {
		private World w;
		private TestNPC upper, lower;

		/** Two big bodies in the same column, one storey apart. */
		private void stack() {
			seed(524);
			w = room(16, 12, 3);
			Genome g = new Genome();
			g.size = 220; // radii sum well past the 1.0 a storey reads as
			upper = TestNPC.breeder(8.5, 6.5, 1, g).withReproCooldown(100000);
			lower = TestNPC.breeder(8.5, 6.5, 0, g).withReproCooldown(100000);
			w.spawnEntity(upper);
			w.spawnEntity(lower);
			w.think();
		}

		@Override
		public void run() {
			stack();
			double radii = (upper.getSize() + lower.getSize()) / 2.0;
			assertLess("the test is worth running: a storey is inside their reach",
					1.0, radii);
			assertTrue("through a plain floor, neither body touches the other",
					!upper.touches(lower) && !lower.touches(upper));

			// A hole under the upper body: the floor is not there.
			stack();
			w.setTile(8, 6, 1, Tile.TileType.TYPE_HOLE);
			assertTrue("but through a hole it does", upper.touches(lower));
			assertTrue("and from below, the same hole", lower.touches(upper));

			// A drop shaft is a hole with a hazard stripe round it.
			stack();
			w.setTile(8, 6, 1, Tile.TileType.TYPE_SHAFT);
			assertTrue("a drop shaft reads as a hole too", upper.touches(lower));

			// A ramp joins the two floors where it stands.
			stack();
            w.setTile(8, 6, 0, Tile.TileType.TYPE_RAMPUP);
			w.getTile(8, 6, 0).setRampUphill(Tile.DIR_N);
			assertTrue("so does the ramp that joins them", lower.touches(upper));

			// Two storeys is a floor with a floor under it, opening or no.
			seed(525);
			w = room(16, 12, 3);
			Genome g = new Genome();
			g.size = 220;
			TestNPC top = TestNPC.breeder(8.5, 6.5, 2, g).withReproCooldown(100000);
			TestNPC bottom = TestNPC.breeder(8.5, 6.5, 0, g).withReproCooldown(100000);
			w.spawnEntity(top);
			w.spawnEntity(bottom);
			w.setTile(8, 6, 2, Tile.TileType.TYPE_HOLE);
			w.setTile(8, 6, 1, Tile.TileType.TYPE_HOLE);
			w.think();
			assertTrue("two storeys apart is out of reach, holes or no holes",
					!top.touches(bottom));

			// And the ordinary case is untouched.
			seed(526);
			World flat = room(16, 12);
			Genome big = new Genome();
			big.size = 220;
			TestNPC a = TestNPC.breeder(8.4, 6.5, 0, big).withReproCooldown(100000);
			TestNPC b = TestNPC.breeder(8.6, 6.5, 0, big).withReproCooldown(100000);
			flat.spawnEntity(a);
			flat.spawnEntity(b);
			flat.think();
			assertTrue("two bodies sharing a floor still touch", a.touches(b));
		}
	}

	/**
	 * What a lineage eats is inherited. A scavenger's child is a scavenger, budded
	 * or crossed — without which the niche cannot grow whatever else is true of it,
	 * because every scavenger that ever managed to breed produced a grazer and the
	 * cohort stayed exactly as large as the warden kept it.
	 */
	static class ScavengerYoungAreScavengers extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(14, 14);

			// Asexual: buds alone, so one well-fed body is the whole experiment.
			Genome bud = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			bud.markers = new double[] { 0.5, 0.5, 0.5 };
			bud.sexuality = 0.0; // buds
			Mind breeder = (sensors, act) -> act[AgentIO.A_MATE] = 1;
			TestNPC parent = TestNPC.minded(4.5, 4.5, 0, bud, breeder)
					.withClade(Genome.Clade.SCAVENGER).withMetabolic().withDeathspan(777);
			parent.withEnergy(parent.energyCapacity());
			w.spawnEntity(parent);
			w.think();
			tick(w, 200); // budding is a held act: ~165 ticks of commitment first
			assertGreater("the scavenger budded", w.getAliveCount(), 1);
			assertEquals("and every one of its young is a scavenger too",
					0, countRolesOtherThan(w, "scavenger"));
			// Diet is not the only body trait the genome does not carry. A minded
			// child used to inherit none of them -- only the plain-breeder branch
			// remembered corpse lifespan, and nothing remembered clade.
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t != parent && !t.isRemoved()) {
					assertEquals("a child keeps its lineage's corpse lifespan",
							parent.getDeathspan(), t.getDeathspan());
				}
			}

			// Sexual: a compatible pair, both scavengers, crossing over.
			World pair = room(14, 14);
			Genome sx = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			sx.markers = new double[] { 0.5, 0.5, 0.5 };
			sx.sexuality = 1.0; // courts
			TestNPC a = TestNPC.minded(6.3, 6.5, 0, sx, breeder)
					.withClade(Genome.Clade.SCAVENGER).withMetabolic();
			TestNPC b = TestNPC.minded(6.7, 6.5, 0, sx.copy(), breeder)
					.withClade(Genome.Clade.SCAVENGER).withMetabolic();
			a.withEnergy(a.energyCapacity());
			b.withEnergy(b.energyCapacity());
			pair.spawnEntity(a);
			pair.spawnEntity(b);
			pair.think();
			tick(pair, 220); // courtship holds station for MATING_TICKS before a birth
			assertGreater("the pair bred", pair.getAliveCount(), 2);
			assertEquals("and the crossover child is a scavenger",
					0, countRolesOtherThan(pair, "scavenger"));
		}
	}

	/** Counts live minded bodies whose {@code ecoRole} is not {@code role}. */
	static int countRolesOtherThan(World w, String role) {
		int n = 0;
		for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
			if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
					&& !role.equals(t.ecoRole())) {
				n++;
			}
		}
		return n;
	}

	/**
	 * Diet is a reproductive barrier. A scavenger and a grazer that are otherwise
	 * identical twins -- same markers, same body, both fertile and touching -- do
	 * not breed, because they are not the same animal.
	 *
	 * <p>Left open, a scavenger's scarce fertile ticks are spent courting the far
	 * more numerous herbivores around it, and the child is built by whichever parent
	 * closes the exchange, so the pairing yields a grazer either way.
	 */
	static class ScavengersDoNotBreedWithGrazers extends Scenario {
		@Override
		public void run() {
			seed(32);
			World w = room(12, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 6, Math.PI * 2, 100000);
			g.markers = new double[] { 0.5, 0.5, 0.5 };
			g.sexuality = 1.0;

			Mind breeder = (sensors, act) -> act[AgentIO.A_MATE] = 1;
			TestNPC scav = TestNPC.minded(6.3, 6.5, 0, g.copy(), breeder)
					.withClade(Genome.Clade.SCAVENGER).withMetabolic();
			TestNPC grazer = TestNPC.minded(6.7, 6.5, 0, g.copy(), breeder).withMetabolic();
			scav.withEnergy(scav.energyCapacity());
			grazer.withEnergy(grazer.energyCapacity());
			w.spawnEntity(scav);
			w.spawnEntity(grazer);
			w.think();

			assertTrue("the two are genome-compatible on markers alone",
					grazer.getGenome().similarityTo(scav.getGenome()) >= grazer.getGenome().mateThreshold);
			assertTrue("but a scavenger will not pair with a grazer", !scav.canMateWith(grazer));
			assertTrue("nor a grazer with a scavenger", !grazer.canMateWith(scav));
			tick(w, 220);
			assertEquals("so no child is born of the pair", 2, w.getAliveCount());
		}
	}

	/**
	 * A scavenger keeps walking to the carcass it chose unless something is a great
	 * deal better. Carrion is scored by {@code mass * freshness / (1 + dist)}, and
	 * with several bodies in scent range those scores sit close together -- re-running
	 * the argmax every tick hands the lead back and forth and the creature turns
	 * toward a new body every few steps instead of reaching any of them. Measured
	 * before anything held: a target in view on 37% of ticks and one actually within
	 * biting distance on 0.87%. This pins the near-tie half of the rule; the
	 * crossing half is {@link ScavengerCrossesToAMuchBetterCarcass}.
	 */
	/**
	 * The other half of the rule: committing is not a vow. A body worth well over
	 * the band -- here four times the held one's score, because it is a step away
	 * rather than seven -- is worth crossing to.
	 *
	 * <p>Holding outright was measured to cost real food: a quarter of held ticks
	 * had a carcass in range worth more than half again as much, and only a third
	 * of commitments ever ended in a meal.
	 */
	static class ScavengerCrossesToAMuchBetterCarcass extends Scenario {
		@Override
		public void run() {
			seed(37);
			World w = room(30, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 30, Math.PI * 2, 100000);

			TestNPC far = TestNPC.breeder(15.5, 5.5, 0, g);
			w.spawnEntity(far);
			tick(w, 2);
			far.damage(500);
			tick(w, 1);

			Mind still = (sensors, act) -> { };
			TestNPC scav = TestNPC.minded(8.5, 5.5, 0, g, still)
					.withClade(Genome.Clade.SCAVENGER).withHeading(0);
			w.spawnEntity(scav);
			tick(w, 2);
			assertNear("it starts out fixed on the carcass ahead", 0,
					scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING], 0.1);

			// Practically underfoot, and behind: four times the score of the one it
			// is walking to. Walking past that is not commitment, it is stubbornness.
			TestNPC underfoot = TestNPC.breeder(7.5, 5.5, 0, g);
			w.spawnEntity(underfoot);
			tick(w, 2);
			underfoot.damage(500);
			tick(w, 2);
			assertTrue("a far better carcass is a step behind it", underfoot.isDead());

			assertNear("the scavenger crosses to it", 1.0,
					Math.abs(scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING]), 0.1);
		}
	}

	static class ScavengerHoldsTheCarcassItChose extends Scenario {
		@Override
		public void run() {
			seed(33);
			World w = room(30, 12);
			Genome g = Genome.phenotype(9, 0.0, 5, 30, Math.PI * 2, 100000);

			// One carcass seven tiles east, comfortably inside scent range.
			TestNPC near = TestNPC.breeder(15.5, 5.5, 0, g);
			w.spawnEntity(near);
			tick(w, 2);
			near.damage(500);
			tick(w, 1);
			assertTrue("there is a carcass to walk to", near.isDead());

			Mind still = (sensors, act) -> { };
			TestNPC scav = TestNPC.minded(8.5, 5.5, 0, g, still)
					.withClade(Genome.Clade.SCAVENGER).withHeading(0);
			w.spawnEntity(scav);
			tick(w, 2);
			double chosen = scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING];
			assertNear("it has fixed on the carcass ahead", 0, chosen, 0.1);

			// A second carcass of the same mass appears BEHIND it, four tiles off:
			// nearer, so worth more, but only about 1.6x more -- inside the band.
			// Near-ties are exactly what used to hand the lead back and forth.
			TestNPC rival = TestNPC.breeder(4.5, 5.5, 0, g);
			w.spawnEntity(rival);
			tick(w, 2);
			rival.damage(500);
			tick(w, 2);
			assertTrue("and a slightly better one has appeared behind it", rival.isDead());

			assertNear("a near-tie does not steal the carcass it chose", 0,
					scav.sensorSnapshot()[AgentIO.S_FORAGE_BEARING], 0.1);
		}
	}

	/**
	 * A body only forages toward food it could actually walk to. The seek intent
	 * steers a straight bearing with no pathfinding anywhere in the loop, so a
	 * patch chosen through a wall is not a meal -- it is a wall to press against.
	 * Measured underground before this rule, minded foragers spent up to 19% of
	 * their lives motionless against rock at part throttle, aiming at grass on the
	 * far side of it.
	 */
	static class ForageIgnoresFoodBehindWalls extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(16, 12);
			// A full-height wall two tiles east of the forager. Rich ground lies
			// straight beyond it; a thinner patch lies north, on this side.
			for (int y = 1; y <= 10; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WALL);
			}
			// Strip this side bare except one tile due north of the forager: ground
			// starts lush, so grazing everything else leaves exactly one patch.
			for (int x = 1; x <= 5; x++) {
				for (int y = 1; y <= 10; y++) {
					if (x == 4 && y == 2) {
						continue; // the reachable patch
					}
					w.getTile(x, y, 0).graze(0, Tile.VEG_MAX);
				}
			}
			Genome g = Genome.phenotype(8, 0.0, 5, 9, Math.PI * 2, 100000);
			Mind still = (sensors, act) -> { };
			TestNPC forager = TestNPC.minded(4.5, 8.5, 0, g, still).withHeading(0);
			w.spawnEntity(forager);
			tick(w, 4);
			snapshot(w, "wall east, rich ground beyond it, a patch north");

			double prox = forager.sensorSnapshot()[AgentIO.S_FORAGE_PROX];
			double bearing = forager.sensorSnapshot()[AgentIO.S_FORAGE_BEARING];
			assertGreater("it found food at all", prox, 0);
			// Heading is east (0 rad). North is -PI/2, i.e. -0.5 in the normalised
			// bearing; the walled-off ground due east would read ~0.
			assertLess("it did not pick the ground behind the wall", Math.abs(bearing), 0.95);
			assertGreater("it steered off the wall's bearing entirely", Math.abs(bearing), 0.2);
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

	/**
	 * A sound rings for as long as the constant says, and then stops.
	 *
	 * <p>{@code ViolenceIsAudibleThroughWalls} covers "it goes quiet eventually"
	 * and gives it nine hundred ticks to do so, which passes at thirty-three
	 * ticks and at eight hundred alike. So the duration itself was never
	 * checked — and it was wrong: one second is long enough for a body to turn
	 * toward a scream and nothing more, against sounds that arrive from a median
	 * of five tiles away.
	 *
	 * <p>Asserted against {@link TestNPC#EARSHOT_MEMORY} rather than against a
	 * number, so retuning the constant retunes the scenario with it. The window
	 * is measured from the tick the sound actually lands, because a sound spends
	 * {@code Sound.TRAVEL_TICKS} in flight first and timing from the spawn would
	 * be timing the wrong thing.
	 */
	static class ASoundRingsForAsLongAsItSays extends Scenario {
		@Override
		public void run() {
			seed(527);
			World w = room(9, 9);
			TestNPC ear = TestNPC.listener(4.5, 4.5, 0);
			w.spawnEntity(ear);
			w.spawnEntity(new Sound(4.5, 4.5, 0));

			// Wait for it to land: it is in flight for TRAVEL_TICKS first.
			int landed = -1;
            for (int t = 0; t < net.hedinger.prototype.entities.Sound.TRAVEL_TICKS * 3
					&& landed < 0; t++) {
				tick(w, 1);
				if (ear.hearsSomething()) {
					landed = t;
				}
			}
			assertGreater("the sound arrived at all", landed, -1);

			// Just inside the window it is still ringing...
			tick(w, TestNPC.EARSHOT_MEMORY - 2);
			assertTrue("it is still ringing a tick short of the window",
					ear.hearsSomething());

			// ...and just past it, gone.
			tick(w, 3);
			assertTrue("and silent a tick past it", !ear.hearsSomething());
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
	 * The demo world's surface/cave orientation and links are sound: the surface
	 * (grass-growing level) sits ABOVE the cave, so a creature that walks onto a
	 * surface hole falls into the cave — a VALID level — rather than off the
	 * bottom into an invalid negative level (the inversion bug that silently lost
	 * wandering creatures). Also pins both links as WALKS: a body on a cave
	 * RAMPUP that heads up the slope climbs to the surface, and one on a surface
	 * RAMPDOWN that heads down it descends. Guards against re-inverting the
	 * levels or breaking the ramp geometry.
	 *
	 * <p>Every heading here is taken from the ramp the world actually built,
	 * rather than assumed. The world lays its stations facing all four ways now,
	 * so a test that walked east on principle would be pinning the direction the
	 * generator happens to have chosen for whichever ramp it found first — which
	 * is a fact about this seed, not about the world's links working.
	 */
	static class DemoLevelsLinkSurfaceAndCave extends Scenario {
		private static final Tile.TileType HOLE = Tile.TileType.TYPE_HOLE;
		private static final Tile.TileType RAMPUP = Tile.TileType.TYPE_RAMPUP;
		private static final Tile.TileType FLOOR = Tile.TileType.TYPE_FLOOR;

		@Override
		public void run() {
			seed(42);
			World w = net.hedinger.prototype.sim.Worlds.demoTerrain(42);
			int cols = w.getColums(), rows = w.getRows();

			// The surface is the level that grows grass; it must be the TOP level.
			int surface = -1;
			for (int z = 0; z < w.getLevels(); z++) {
				for (int x = 0; x < cols && surface != z; x++) {
					for (int y = 0; y < rows; y++) {
						Tile t = w.getTile(x, y, z);
						if (t.getType() == FLOOR && t.getFertility() > 0) {
							surface = z;
							break;
						}
					}
				}
			}
			// Asked of the world rather than named, so a floor added below does
			// not make this a lie: the grass is on TOP, whatever that index is.
			assertEquals("the grassy surface is the top level (everything else below it)",
					w.getLevels() - 1, surface);

			// Down-link: a walker on a surface hole falls into the cave, not the void.
			int hx = -1, hy = -1;
			for (int x = 0; x < cols && hx < 0; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, surface).getType() == HOLE) {
						hx = x;
						hy = y;
						break;
					}
				}
			}
			assertGreater("the surface has holes to fall through", hx, -1);
			TestNPC faller = TestNPC.inert(hx + 0.5, hy + 0.5, surface);
			w.spawnEntity(faller);
			w.think();
			tick(w, 10);
			assertEquals("a surface hole drops a walker one level into the cave", surface - 1, faller.getLvl());
			assertTrue("the faller landed on a valid (non-negative) level", faller.getLvl() >= 0);

			// Up-link: a walker on a cave RAMPUP that heads east climbs to the surface.
			int rx = -1, ry = -1;
			for (int x = 0; x < cols && rx < 0; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, surface - 1).getType() == RAMPUP) {
						rx = x;
						ry = y;
						break;
					}
				}
			}
			assertGreater("the cave has ramps up to the surface", rx, -1);
			int upDir = w.getTile(rx, ry, surface - 1).getRampUphill();
			double upHeading = Math.atan2(Tile.dirDy(upDir), Tile.dirDx(upDir));
			TestNPC climber = TestNPC.mover(rx + 0.5, ry + 0.5, surface - 1, upHeading)
					.withSpeed(0.05);
			w.spawnEntity(climber);
			w.think();
			tick(w, 200);
			assertEquals("a cave ramp climbs a walker back up to the surface", surface, climber.getLvl());

			// Down-ramp: the descent is a walk too, not just a fall. A walker heading
			// west off a surface RAMPDOWN steps down into the cave under its own feet.
			int dx = -1, dy = -1;
			for (int x = 0; x < cols && dx < 0; x++) {
				for (int y = 0; y < rows; y++) {
					if (w.getTile(x, y, surface).getType() == Tile.TileType.TYPE_RAMPDOWN) {
						dx = x;
						dy = y;
						break;
					}
				}
			}
			assertGreater("the surface has ramps down into the cave", dx, -1);
			// Seal the pit at the ramp's foot first. It descends to the same landing,
			// so leaving it open would let gravity pass this test and prove nothing;
			// with it walled off, only the ramp can carry the walker down.
			int downDir = Tile.opposite(w.getTile(dx, dy, surface).getRampUphill());
			double downHeading = Math.atan2(Tile.dirDy(downDir), Tile.dirDx(downDir));
			w.setTile(dx + Tile.dirDx(downDir), dy + Tile.dirDy(downDir), surface,
					Tile.TileType.TYPE_WALL);
			TestNPC walker = TestNPC.mover(dx + 0.5, dy + 0.5, surface, downHeading)
					.withSpeed(0.05);
			w.spawnEntity(walker);
			w.think();
			tick(w, 60);
			assertEquals("a surface ramp walks a body down into the cave", surface - 1, walker.getLvl());

			// Every climb ends in rock. An up ramp whose high side is open floor
			// is a staircase into a ceiling — it climbs to a surface tile resting
			// on nothing, and the art has no mass to disappear into. The corridor
			// carver used to take that rock out on its way past: three of eight
			// stations lost theirs before it learned to leave it alone.
			int ramps = 0;
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					Tile t = w.getTile(x, y, surface - 1);
					if (t.getType() != RAMPUP) {
						continue;
					}
					ramps++;
					int u = t.getRampUphill();
					Tile head = w.getTile(x + Tile.dirDx(u), y + Tile.dirDy(u), surface - 1);
					assertTrue("the ramp at " + x + "," + y + " climbs into rock",
							head.isSolid());
				}
			}
			assertGreater("the cave has ramps to check", ramps, 0);
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
	/**
	 * Carrying and being carried are different acts and wear different badges.
	 *
	 * <p>They used to share one — the comment in {@code actionKey} said as much,
	 * "carrying a captive, or riding a host" — so the hook hovering over a creature
	 * left you to guess which end of the arrangement you were looking at. Which end
	 * is precisely the interesting part: one of them chose this.
	 */
	static class CarryingAndRidingWearDifferentBadges extends Scenario {
		@Override
		public void run() {
			seed(96);

			// A captor with a captive: this body is doing the holding.
			World grab = room(12, 12);
			TestNPC captor = TestNPC.roamer(5.5, 5.5, 0).withSize(6);
			TestNPC captive = TestNPC.inert(5.55, 5.5, 0).withSize(4);
			grab.spawnEntity(captor);
			grab.spawnEntity(captive);
			grab.think();
			assertTrue("the captor takes hold", captor.grab(captive));
			grab.think();
			assertTrue("the one doing the carrying says so", "carry".equals(captor.actionKey()));
			assertTrue("and its captive is held, not riding", captive.isGrabbed());

			// A rider aboard a host by its own choice.
			World ride = room(14, 12);
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			Genome riderG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.metabolism = 0.02;
			riderG.brain = new Brain(deepCopy(cling));
			TestNPC rider = TestNPC.brainedBreeder(4.05, 6.0, 0, riderG).withEnergy(6.0);
			TestNPC host = TestNPC.roamer(4.0, 6.0, 0).withSize(18).withSpeed(0.0);
			ride.spawnEntity(rider);
			ride.spawnEntity(host);
			tick(ride, 6);
			assertTrue("the rider is aboard of its own accord",
					rider.getAttachTarget() == host && !rider.isGrabbed());
			assertTrue("a passenger says it is riding", "ride".equals(rider.actionKey()));

			// And the two reach the viewer as different glyphs.
			int carryCode = actionCodeOf(grab, captor.getID());
			int rideCode = actionCodeOf(ride, rider.getID());
			assertEquals("the captor's badge is the carry glyph",
					net.hedinger.prototype.sim.EntityState.ACT_CARRY, carryCode);
			assertEquals("the passenger's is the ride glyph",
					net.hedinger.prototype.sim.EntityState.ACT_RIDE, rideCode);
			assertTrue("which are not the same badge", carryCode != rideCode);
		}
	}

	/** The action code the wire carries for one entity. */
	static int actionCodeOf(World w, int id) {
		for (net.hedinger.prototype.sim.EntityState e
				: net.hedinger.prototype.sim.WorldSnapshot.of(w).entities()) {
			if (e.id() == id) {
				return (e.flags() & net.hedinger.prototype.sim.EntityState.ACTION_MASK)
						>> net.hedinger.prototype.sim.EntityState.ACTION_SHIFT;
			}
		}
		return -1;
	}

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
	 * <p>Pins the ramp mechanic: a ramp is floor that spans two levels, so a body
	 * that simply keeps walking east off a RAMPUP's top edge steps onto the level
	 * above and carries on there. Nothing is willed and nothing is sensed — the
	 * ground does it. A control mover in a ramp-less row meets the same wall and is
	 * merely blocked, which is what proves the ramp (not the wall) is doing the work.
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

	/**
	 * A hunter can punch above its weight, but pays for it in time. Quarry up to
	 * {@code PRED_MAX_PREY_RATIO} times a hunter's own size is fair game, and the
	 * bite scales down with the size ratio — so an undersized hunter still brings
	 * the animal down, it just needs far more bites to do it.
	 *
	 * <p>Runs the same kill twice, changing only the hunter's body: an equal-sized
	 * hunter and an undersized one against identical quarry, each parked in reach so
	 * the measurement is the bite rate and nothing else (no chase, no perception).
	 * Asserts both kill, and that the small one takes materially longer.
	 */
	static class SmallHunterTakesBiggerPreySlowly extends Scenario {
		/** Ticks for {@code hunterSize} to kill a size-10 victim parked in reach;
		 *  -1 if it never manages it. */
		private int ticksToKill(double hunterSize) {
			seed(5);
			World w = room(20, 12);
			Genome predG = new Genome();
			predG.size = hunterSize;
			predG.speed = 0.04;
			predG.losFov = Math.PI * 2;
			predG.losRange = 12;
			Genome victimG = new Genome();
			victimG.size = 10;
			victimG.speed = 0; // parked: isolate bite rate from the chase

			TestNPC hunter = TestNPC.predator(5.0, 5.0, 0, predG).withHunger(0.8);
			TestNPC victim = TestNPC.breeder(5.4, 5.0, 0, victimG); // adjacent, in reach
			w.spawnEntity(hunter);
			w.spawnEntity(victim);
			w.think();
			for (int t = 0; t < 3000; t++) {
				w.think();
				if (victim.isDead()) {
					return t;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int even = ticksToKill(10); // same weight class as the victim
			int small = ticksToKill(7); // undersized: victim is ~1.43x its size

			assertGreater("an evenly-matched hunter kills its quarry", even, -1);
			assertGreater("an undersized hunter still brings bigger quarry down", small, -1);
			// 20 damage/bite at parity vs round(20 * 7/10) = 14, so a 100-health
			// victim needs 5 bites instead of 8 — a clearly longer kill.
			assertGreater("the undersized hunter's kill takes materially longer ("
					+ small + " ticks vs " + even + ")", small, even);
		}
	}

	/**
	 * The engine refuses to move any body more than {@link Entity#MAX_STEP} in one
	 * tick, however fast its genome claims to be. Passability is decided one tile at
	 * a time, so an over-long step would tunnel through terrain — or, past a full
	 * tile, be rejected outright and leave the creature stuttering in place. Drives
	 * a deliberately absurd speed (100x the fastest anything evolves to) and pins
	 * both halves: every step stays inside the ceiling, AND the creature still
	 * actually travels rather than freezing.
	 */
	static class EngineCapsStepLength extends Scenario {
		@Override
		public void run() {
			seed(3);
			World w = room(30, 12);
			TestNPC bolter = TestNPC.mover(2.5, 5.5, 0, 0).withSpeed(5.0); // due east
			w.spawnEntity(bolter);
			w.think();

			double x0 = bolter.getX();
			double worst = 0;
			for (int i = 0; i < 20; i++) {
				w.think();
				worst = Math.max(worst, bolter.lastStep());
			}
			assertLess("no step exceeds the engine ceiling", worst, Entity.MAX_STEP + 1e-9);
			assertGreater("a clamped body still travels (it is not frozen by the cap)",
					bolter.getX() - x0, 1.0);
		}
	}

	/**
	 * Moving costs energy, in proportion to the ground covered. Without a cost of
	 * transport the burn depended only on body size, so speed — the one gene with
	 * real upside and no downside — ratcheted upward every generation. Three
	 * identical bodies differing only in speed: a still one, a slow walker and a
	 * fast one. Asserts the burn is strictly ordered by distance travelled, and
	 * that holding still costs nothing beyond resting metabolism.
	 */
	static class TravelCostsEnergy extends Scenario {
		@Override
		public void run() {
			seed(4);
			World w = room(30, 14);
			// Same size (so identical resting burn), same start energy, differing
			// only in speed. Movers walk east across open floor, never reaching a wall
			// within the window, so nothing cancels their steps.
			TestNPC still = TestNPC.inert(2.5, 3.5, 0).withMetabolic().withEnergy(4);
			TestNPC slow = TestNPC.mover(2.5, 6.5, 0, 0).withMetabolic().withEnergy(4).withSpeed(0.05);
			TestNPC fast = TestNPC.mover(2.5, 9.5, 0, 0).withMetabolic().withEnergy(4).withSpeed(0.15);
			w.spawnEntity(still);
			w.spawnEntity(slow);
			w.spawnEntity(fast);
			w.think();

			double e0 = still.getEnergy();
			tick(w, 100);
			double burnStill = e0 - still.getEnergy();
			double burnSlow = e0 - slow.getEnergy();
			double burnFast = e0 - fast.getEnergy();

			assertGreater("walking costs more than standing still", burnSlow, burnStill);
			assertGreater("travelling further costs more again", burnFast, burnSlow);
			// The gap is the cost of transport over the extra ground, so it should
			// track the distance ratio (3x the speed => ~3x the travel component).
			double travelSlow = burnSlow - burnStill;
			double travelFast = burnFast - burnStill;
			assertGreater("the fast body's travel bill scales with the distance it covered",
					travelFast, travelSlow * 2.0);
		}
	}

	/**
	 * Holding a prisoner costs more than carrying a passenger of the same weight.
	 * Carrying always charged for mass, but the grip itself was free, so a captor
	 * could seize something and hold it forever at no cost beyond what a willing
	 * rider would have cost — captivity was a state rather than an effort. Three
	 * identical carriers, differing only in what they hold: nothing, a voluntary
	 * rider, and a grabbed captive of the same size as that rider.
	 */
	static class HoldingACaptiveCostsEnergy extends Scenario {
		@Override
		public void run() {
			seed(6);
			World w = room(40, 24);
			// Same body, same start energy; only the load differs. Big enough to
			// grab the small ones (grab refuses anything larger than the captor).
			// They must WALK: a load is priced as extra mass through the movement
			// term, so a motionless carrier pays nothing for weight it is merely
			// holding. Standing still would compare three identical resting bills.
			TestNPC empty = TestNPC.mover(3.0, 4.0, 0, 0).withSize(12).withMetabolic()
					.withEnergy(4).withSpeed(0.08);
			TestNPC ferry = TestNPC.mover(3.0, 10.0, 0, 0).withSize(12).withMetabolic()
					.withEnergy(4).withSpeed(0.08);
			TestNPC captor = TestNPC.mover(3.0, 16.0, 0, 0).withSize(12).withMetabolic()
					.withEnergy(4).withSpeed(0.08);
			TestNPC rider = TestNPC.inert(3.05, 10.0, 0).withSize(6);
			TestNPC victim = TestNPC.inert(3.05, 16.0, 0).withSize(6);
			w.spawnEntity(empty);
			w.spawnEntity(ferry);
			w.spawnEntity(captor);
			w.spawnEntity(rider);
			w.spawnEntity(victim);
			w.think();

			assertTrue("the rider latched on voluntarily", rider.attachTo(ferry));
			assertTrue("the captor seized its victim", captor.grab(victim));
			assertTrue("a grabbed body is held, a rider is not",
					victim.isGrabbed() && !rider.isGrabbed());

			double e0 = empty.getEnergy();
			tick(w, 100);
			double burnEmpty = e0 - empty.getEnergy();
			double burnFerry = e0 - ferry.getEnergy();
			double burnCaptor = e0 - captor.getEnergy();

			assertGreater("hauling a passenger costs more than travelling empty",
					burnFerry, burnEmpty);
			assertGreater("holding a prisoner costs more than ferrying a passenger "
					+ "of the same weight (the grip is the difference)", burnCaptor, burnFerry);
		}
	}

	/**
	 * Creatures are born as juveniles and grow into their genome's body. Growth
	 * runs at a fixed rate, so the bigger the adult body the longer the childhood —
	 * and the largest body a genome can express takes about a minute, the longest
	 * childhood the world can produce. Growth is physical: the juvenile is smaller,
	 * burns less and is easier prey, but the energy economy (tank, breeding
	 * threshold and cost) is anchored on the adult body and so is unchanged.
	 */
	static class CreaturesGrowToAdultSize extends Scenario {
		/** Ticks for a body with the given adult size to finish growing. */
		private int ticksToMature(double adultSize) {
			seed(9);
			World w = room(20, 12);
			Genome g = new Genome();
			g.size = adultSize;
			TestNPC t = TestNPC.breeder(9.5, 5.5, 0, g);
			w.spawnEntity(t);
			w.think();
			assertTrue("a newborn is a juvenile", t.isJuvenile());
			assertLess("a newborn is markedly smaller than its adult body",
					t.getPixelSize(), adultSize * 0.6);
			for (int i = 0; i < 6000; i++) {
				w.think();
				if (!t.isJuvenile()) {
					assertEquals("a grown body reaches exactly its genome size",
							(int) Math.round(adultSize), t.getPixelSize());
					return i;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int small = ticksToMature(6);
			int large = ticksToMature(Genome.SIZE_MAX); // 20: the biggest body possible

			assertGreater("a small body finishes growing", small, -1);
			assertGreater("the largest body finishes growing", large, -1);
			assertGreater("a bigger creature takes longer to grow up", large, small);
			// A fixed growth rate over the longest possible climb: ~1 minute at
			// 33 ticks/s. Bounded on both sides so the rate cannot drift unnoticed.
			int oneMinute = 60 * net.hedinger.prototype.sim.SimulationRunner.TICKS_PER_SECOND;
			assertGreater("the longest childhood is on the order of a minute ("
					+ large + " ticks)", large, oneMinute * 0.75);
			assertLess("the longest childhood does not much exceed a minute ("
					+ large + " ticks)", large, oneMinute * 1.25);

			// Growth is physical, not economic: the tank is anchored on the adult
			// body, so a newborn's breeding economy matches a grown one's.
			seed(9);
			World w = room(20, 12);
			Genome g = new Genome();
			g.size = 12;
			TestNPC baby = TestNPC.breeder(9.5, 5.5, 0, g);
			w.spawnEntity(baby);
			w.think();
			double juvenileCap = baby.energyCapacity();
			tick(w, 3000); // long enough to be fully grown
			assertTrue("the body did finish growing", !baby.isJuvenile());
			assertEquals("the energy tank is the same before and after growing up",
					Math.round(juvenileCap * 1000), Math.round(baby.energyCapacity() * 1000));
		}
	}

	/**
	 * A body never ends a tick inside terrain, whatever put it there.
	 *
	 * <p>Movement cannot walk into a wall, but movement is not the only thing that
	 * sets a position. A carried or ridden body is placed at an offset from its
	 * host every tick with no collision test at all, so a host walking a wall
	 * plants its passenger inside it; a spawn can land in rock. Neither is a
	 * movement, so neither is caught by the movement check — and a body left there
	 * is stuck for good, because every escape step is a move that starts illegally
	 * and is refused.
	 *
	 * <p>Measured on the demo world over 20k ticks before the guard existed: 168
	 * samples of a free body standing in terrain, across seven creatures, and
	 * rising with time rather than clearing. After: zero.
	 */
	static class BodiesAreNeverLeftInsideTerrain extends Scenario {
		@Override
		public void run() {
			seed(51);
			World w = room(14, 9);
			for (int y = 1; y < 8; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WALL);
			}
			// Put a body inside the wall the only way that is possible: by hand,
			// exactly as a carry offset or a bad spawn would.
			TestNPC walled = TestNPC.grazer(2.5, 2.5, 0);
			w.spawnEntity(walled);
			tick(w, 1);
			walled.setPos(6.5, 4.5, 0);
			assertTrue("the test really did put it in a wall",
					!w.getTile(6.5, 4.5, 0).isWalkable());

			tick(w, 1);
			assertTrue("it is put back on ground it can stand on",
					w.getTile(walled.getX(), walled.getY(), walled.getLvl()).isWalkable());
			// Rescued, not flung: the nearest open ground is one tile away.
			assertLess("and set down nearby rather than teleported",
					Math.hypot(walled.getX() - 6.5, walled.getY() - 4.5), 3.0);

			// A body on legal ground is never moved by the guard -- it must be
			// inert in the common case, or it would jitter every creature alive.
			TestNPC fine = TestNPC.grazer(2.5, 6.5, 0);
			w.spawnEntity(fine);
			tick(w, 1);
			double fx = fine.getX(), fy = fine.getY();
			fine.setPos(3.5, 6.5, 0);
			tick(w, 1);
			assertNear("a body on open ground is left alone (x)", 3.5, fine.getX(), 0.35);
			assertNear("a body on open ground is left alone (y)", 6.5, fine.getY(), 0.35);
			assertTrue("and it still moves under its own steam", fx == fx);

			// While carried, the rescue deliberately holds off: a passenger inside a
			// wall is its host's problem and rights itself when set down. Pulling it
			// off mid-ride would move it away from the host it is supposed to be
			// riding, which breaks the carry instead of fixing the ground.
			//
			// The geometry is forced rather than hoped for: the host faces the
			// partition and the rider is latched dead ahead, so the offset the carry
			// applies each tick lands the rider squarely inside the wall. Without
			// that, this passes whether or not the exemption exists.
			// The attach offset is only the two body radii, so the host has to be
			// right up against the partition for the rider to land inside it.
			// The attach offset is only the two body radii -- a few hundredths of a
			// tile -- and a rider keeps whichever side it latched from. So the host
			// sits hard against the partition with the rider latched on the wall
			// side, which is what lands it inside.
			TestNPC host = TestNPC.predator(5.96, 4.5, 0, new Genome())
					.withEnergy(99).withHeading(0);
			TestNPC rider = TestNPC.grazer(5.99, 4.5, 0);
			w.spawnEntity(host);
			w.spawnEntity(rider);
			tick(w, 1);
			host.setPos(5.96, 4.5, 0);
			host.withHeading(0);
			assertTrue("the rider latched on", rider.attachToTarget(host));
			tick(w, 1);
			assertTrue("a carried body stays with its host", rider.getAttachTarget() == host);
			assertTrue("the carry really did put the rider in the wall",
					!w.getTile(rider.getX(), rider.getY(), rider.getLvl()).isWalkable());

			// ...and the moment it is free, it is put back.
			rider.detach();
			rider.setPos(6.5, 4.5, 0); // inside the wall, now unattached
			tick(w, 1);
			assertTrue("once detached it is pushed back out",
					w.getTile(rider.getX(), rider.getY(), rider.getLvl()).isWalkable());
		}
	}

	/**
	 * The clade is the one spelling of a creature's class, and the wire codes it
	 * serialises to are frozen.
	 *
	 * <p>Saved {@code .genome} files carry the clade as an integer and a viewer can
	 * re-inject one, so those numbers are a wire format. They are declared
	 * explicitly rather than taken from {@code ordinal()} precisely so that
	 * reordering the enum cannot silently reinterpret every genome anyone saved —
	 * this pins that, because nothing else would notice.
	 */
	static class CladeIsOneConceptWithFrozenCodes extends Scenario {
		@Override
		public void run() {
			assertEquals("herbivore is code 0", 0, Genome.Clade.HERBIVORE.code());
			assertEquals("predator is code 1", 1, Genome.Clade.PREDATOR.code());
			assertEquals("scavenger is code 2", 2, Genome.Clade.SCAVENGER.code());
			assertEquals("parasite is code 3", 3, Genome.Clade.PARASITE.code());

			for (Genome.Clade c : Genome.Clade.values()) {
				assertTrue("code round-trips for " + c,
						Genome.Clade.ofCode(c.code()) == c);
				// One spelling: the enum name IS the wire name, so they cannot drift.
				assertTrue("the wire name is the enum name for " + c,
						c.wireName().equals(c.name().toLowerCase(java.util.Locale.ROOT)));
				// And a full round-trip through the codec, which is what a saved
				// genome actually goes through.
				Genome g = new Genome();
				g.clade = c;
				Genome back = net.hedinger.prototype.entities.GenomeCodec.decode(
						net.hedinger.prototype.entities.GenomeCodec.encode(g));
				assertTrue("a saved genome reloads as the same clade (" + c + ")",
						back.clade == c);
			}
			// An unrecognised code loads as a grazer rather than throwing: the safe
			// direction to be wrong in for a file someone saved months ago.
			assertTrue("an unknown code degrades to herbivore",
					Genome.Clade.ofCode(99) == Genome.Clade.HERBIVORE);

			// Every clade wears its own body plan -- that is what makes it
			// recognisable on sight, and the plan is a property OF the clade.
			java.util.Set<Integer> plans = new java.util.HashSet<>();
			for (Genome.Clade c : Genome.Clade.values()) {
				assertTrue("clade " + c + " has its own body plan", plans.add(c.bodyPlan()));
			}
		}
	}

	/**
	 * Species are emergent and labelled lazily; clades are authored and absolute.
	 *
	 * <p>The two axes must stay independent: markers separate species WITHIN a
	 * clade and must never move a creature between clades, and the clade must never
	 * decide the species. Pinned because the whole model rests on the two being
	 * orthogonal.
	 */
	static class SpeciesLabelIsDerivedNotStored extends Scenario {
		@Override
		public void run() {
			// A pure function of (clade, markers): same input, same label, always.
			Genome a = new Genome();
			a.markers = new double[] { 0.85, 0.2, 0.2 };
			Genome b = new Genome();
			b.markers = new double[] { 0.85, 0.2, 0.2 };
			assertTrue("the same markers give the same species",
					Species.of(a).equals(Species.of(b)));
			assertTrue("and the same tint", Species.of(a).rgb() == Species.of(b).rgb());

			// Markers move the species; the clade does not.
			Genome far = new Genome();
			far.markers = new double[] { 0.2, 0.85, 0.25 };
			assertTrue("different markers give a different species",
					!Species.of(a).equals(Species.of(far)));

			// Clade moves the clade; it does not move the species index.
			Genome hunter = new Genome();
			hunter.markers = new double[] { 0.85, 0.2, 0.2 };
			hunter.clade = Genome.Clade.PREDATOR;
			assertEquals("the same markers give the same species index in any clade",
					Species.of(a).index(), Species.of(hunter).index());
			assertTrue("but a different species, because the clade differs",
					!Species.of(a).equals(Species.of(hunter)));
			assertTrue("and a distinct key", !Species.of(a).key().equals(Species.of(hunter).key()));

			// Every clade can express every species, so the label space is the full
			// grid rather than whatever the population happens to occupy.
			java.util.Set<String> keys = new java.util.HashSet<>();
			for (Genome.Clade c : Genome.Clade.values()) {
				for (double[] m : new double[][] { { 0.15, 0.15, 0.15 }, { 0.85, 0.2, 0.2 },
						{ 0.2, 0.85, 0.25 }, { 0.25, 0.3, 0.85 }, { 0.8, 0.8, 0.35 },
						{ 0.5, 0.55, 0.6 } }) {
					Genome g = new Genome();
					g.clade = c;
					g.markers = m.clone();
					keys.add(Species.of(g).key());
				}
			}
			assertEquals("every clade names every species distinctly",
					Genome.Clade.values().length * Species.PER_CLADE, keys.size());

			// Nothing in the genome stores it: speciation stays a fact about where a
			// lineage has drifted to, not a tag something assigned it.
			Genome child = Genome.child(a, 0.0);
			assertTrue("an unmutated child keeps its parent's species",
					Species.of(child).equals(Species.of(a)));
		}
	}

	/**
	 * Clade is recognised, and it is not the similarity axis.
	 *
	 * <p>Markers say how closely related two creatures are within a clade; clade
	 * says whether they are the same sort of animal at all. Two creatures with
	 * IDENTICAL markers in different clades must not read as kin — which is exactly
	 * the case that used to slip through, because recognition saw only similarity.
	 */
	static class CladesRecogniseEachOtherAsOther extends Scenario {
		@Override
		public void run() {
			seed(41);
			Genome grazer = new Genome();
			grazer.markers = new double[] { 0.5, 0.5, 0.5 };
			grazer.gregariousness = 1.0;
			grazer.mateThreshold = 0.1;

			Genome twin = grazer.copy();          // identical in every way
			Genome hunter = grazer.copy();        // identical EXCEPT the clade
			hunter.clade = Genome.Clade.PREDATOR;

			Genome.Relation toTwin = grazer.react(twin, 1.0);
			Genome.Relation toHunter = grazer.react(hunter, 1.0);

			assertGreater("its own clade is worth affiliating with", toTwin.affiliate, 0);
			assertEquals("another clade is not, however alike the markers",
					0, (long) (toHunter.affiliate * 1000));
			assertGreater("its own clade is worth courting", toTwin.mate, 0);
			assertEquals("another clade is never worth courting",
					0, (long) (toHunter.mate * 1000));

			// The hostile drives are deliberately NOT clade-gated: a hunter that
			// only chased its own kind would never eat, and prey that only feared
			// its own would never run. Attack scales with DISSIMILARITY, so the
			// quarry needs different markers for the drive to exist at all -- the
			// point here is that it survives the clade difference, not that clade
			// creates it.
			Genome bold = grazer.copy();
			bold.predatory = 1.0;
			Genome quarrySame = grazer.copy();
			quarrySame.markers = new double[] { 0.1, 0.9, 0.1 };
			Genome quarryOther = quarrySame.copy();
			quarryOther.clade = Genome.Clade.PARASITE;
			assertGreater("a dissimilar creature of its own clade is attackable",
					bold.react(quarrySame, 2.0).attack, 0);
			assertEquals("and the clade makes no difference to that drive",
					(long) (bold.react(quarrySame, 2.0).attack * 1000),
					(long) (bold.react(quarryOther, 2.0).attack * 1000));
			Genome timid = grazer.copy();
			timid.xenophobia = 1.0; // the drives default to 0; give it one to measure
			assertGreater("flight likewise crosses clades",
					timid.react(quarryOther, 0.4).flee, 0);

			// And the body barrier agrees with the recognition: identical markers,
			// different clades, still cannot breed.
			World w = room(8, 8);
			// Fed past the breeding threshold: a newborn starts at 0.6 of its tank
			// and breeds at 0.75, so an unfed pair is infertile and would pass the
			// negative assertion for entirely the wrong reason.
			TestNPC g1 = TestNPC.breeder(2.5, 2.5, 0, grazer.copy()).withEnergy(99);
			TestNPC g2 = TestNPC.breeder(3.5, 2.5, 0, twin.copy()).withEnergy(99);
			TestNPC p1 = TestNPC.breeder(4.5, 2.5, 0, hunter.copy()).withEnergy(99);
			for (TestNPC t : new TestNPC[] { g1, g2, p1 }) {
				w.spawnEntity(t);
			}
			tick(w, 1);
			assertTrue("same clade, compatible", g1.canMateWith(g2));
			assertTrue("different clade, incompatible", !g1.canMateWith(p1));
		}
	}

	/**
	 * A creature's role is decided by what it EATS, never by what steers it.
	 *
	 * <p>The steward's bounds are keyed on role, so anything the role fails to
	 * describe is something the world cannot govern. Mindedness used to leak into
	 * it: a herbivore with an evolved brain reported no role at all and was held by
	 * a separate "minded" cohort with its own guardrails — the same animal governed
	 * one way with a brain and another way without, and missing from the prey count
	 * that is supposed to describe the herd.
	 *
	 * <p>Pinned in both directions: bodies differing only in what drives them must
	 * report the SAME role, and bodies differing only in clade must report different
	 * ones.
	 */
	static class RoleFollowsDietNotMind extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(14, 14);
			Genome g = new Genome();

			// Same clade, three different things driving the body.
			TestNPC scripted = TestNPC.breeder(2.5, 2.5, 0, g.copy());
			TestNPC brained = TestNPC.brainedBreeder(3.5, 2.5, 0, g.copy());
			TestNPC evolved = TestNPC.mindedForager(4.5, 2.5, 0, g.copy());
			for (TestNPC t : new TestNPC[] { scripted, brained, evolved }) {
				w.spawnEntity(t);
			}
			assertTrue("a scripted herbivore is a herbivore",
					"herbivore".equals(scripted.ecoRole()));
			assertTrue("a brained herbivore is one too",
					"herbivore".equals(brained.ecoRole()));
			assertTrue("an evolved herbivore STILL is",
					"herbivore".equals(evolved.ecoRole()));
			assertTrue("mindedness is not what decides the role",
					evolved.isMinded() && evolved.ecoRole().equals(scripted.ecoRole()));

			// Same driver, one per trophic level.
			TestNPC mh = TestNPC.mindedForager(6.5, 6.5, 0, of(Genome.Clade.HERBIVORE));
			TestNPC mc = TestNPC.mindedForager(7.5, 6.5, 0, of(Genome.Clade.PREDATOR));
			TestNPC ms = TestNPC.mindedForager(8.5, 6.5, 0, of(Genome.Clade.SCAVENGER));
			TestNPC mp = TestNPC.mindedForager(9.5, 6.5, 0, of(Genome.Clade.PARASITE));
			for (TestNPC t : new TestNPC[] { mh, mc, ms, mp }) {
				w.spawnEntity(t);
			}
			assertTrue("clade decides: herbivore", "herbivore".equals(mh.ecoRole()));
			assertTrue("clade decides: carnivore -> predator", "predator".equals(mc.ecoRole()));
			assertTrue("clade decides: scavenger -> scavenger", "scavenger".equals(ms.ecoRole()));
			assertTrue("clade decides: parasite -> parasite", "parasite".equals(mp.ecoRole()));

			// Bodies outside the ecosystem stay outside it: no genome, no role, so
			// the warden never counts or trims them.
			assertTrue("a bare roamer has no role",
					TestNPC.roamer(1.5, 1.5, 0).ecoRole().isEmpty());
			assertTrue("an inert fixture has no role",
					TestNPC.inert(1.5, 2.5, 0).ecoRole().isEmpty());

			// Spawns are queued until the next step and the steward's census runs over
			// live entities, so tick once before counting -- otherwise this measures
			// an empty world and passes for the wrong reason.
			tick(w, 1);

			// Every creature with a genome must land in exactly one bucket. One with
			// none would be ungovernable: invisible to its floor, immune to its ceiling.
			int roleless = 0;
			java.util.Map<String, Integer> byRole = new java.util.TreeMap<>();
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t.getGenome() != null && !t.isDead()) {
					if (t.ecoRole().isEmpty()) {
						roleless++;
					} else {
						byRole.merge(t.ecoRole(), 1, Integer::sum);
					}
				}
			}
			assertEquals("every creature with a genome has a role", 0, roleless);
			assertEquals("four herbivores counted as such", 4,
					(long) byRole.getOrDefault("herbivore", 0));
			assertEquals("one predator", 1, (long) byRole.getOrDefault("predator", 0));
			assertEquals("one scavenger", 1, (long) byRole.getOrDefault("scavenger", 0));
			assertEquals("one parasite", 1, (long) byRole.getOrDefault("parasite", 0));
		}

		private static Genome of(Genome.Clade clade) {
			Genome g = new Genome();
			g.clade = clade;
			return g;
		}
	}

	/**
	 * Every sensor is reachable by an evolved mind.
	 *
	 * <p>Brain operands are masked into range at execution, so an operand pool
	 * narrower than the sensor bank does not fail — it silently makes the channels
	 * above it unreadable. That is the worst shape a bug can take: the sim runs,
	 * every test passes, the sensors are computed every tick, and evolution simply
	 * never sees them. Eighteen of thirty-four were invisible this way, including
	 * the entire forage, waypoint, thirst and intent suite.
	 *
	 * <p>Checked by actually mutating a population and reading back what the
	 * programs reference, rather than by comparing two constants — the constants
	 * are one implementation of the property, and it is the property that matters.
	 */
	static class EverySensorIsReachable extends Scenario {
		@Override
		public void run() {
			seed(11);
			java.util.Set<Integer> sensed = new java.util.HashSet<>();
			java.util.Set<Integer> written = new java.util.HashSet<>();
			java.util.List<Brain> pop = new java.util.ArrayList<>();
			for (int i = 0; i < 60; i++) {
				pop.add(Brain.random(24));
			}
			for (int gen = 0; gen < 60; gen++) {
				java.util.List<Brain> next = new java.util.ArrayList<>();
				for (Brain b : pop) {
					Brain c = b.copy();
					c.mutate(0.35);
					next.add(c);
					for (String line : c.disassemble(AgentIO.SENSOR_NAMES, AgentIO.ACT_NAMES)) {
						int at = line.indexOf("sense ");
						if (at >= 0) {
							note(sensed, AgentIO.SENSOR_NAMES, line.substring(at + 6).trim());
							continue;
						}
						at = line.indexOf("act ");
						if (at >= 0 && line.indexOf(" =", at) > at) {
							note(written, AgentIO.ACT_NAMES,
									line.substring(at + 4, line.indexOf(" =", at)).trim());
						}
					}
				}
				pop = next;
			}
			for (int i = 0; i < AgentIO.NUM_SENSORS; i++) {
				assertTrue("sensor '" + AgentIO.SENSOR_NAMES[i] + "' (" + i
						+ ") can be reached by an evolved mind", sensed.contains(i));
			}
			for (int i = 0; i < AgentIO.NUM_ACT; i++) {
				assertTrue("actuator '" + AgentIO.ACT_NAMES[i] + "' (" + i
						+ ") can be reached by an evolved mind", written.contains(i));
			}
		}

		private static void note(java.util.Set<Integer> into, String[] names, String name) {
			for (int i = 0; i < names.length; i++) {
				if (names[i].equals(name)) {
					into.add(i);
					return;
				}
			}
		}
	}

	/**
	 * Hearing works end to end: violence makes a noise, the noise crosses a wall,
	 * a body on the far side reads it on the sensor bank, and it then goes quiet.
	 *
	 * <p>The wall is the point. Sight is blocked and smell hangs where it was left;
	 * hearing is the only channel that carries an event through terrain, so a test
	 * that put both creatures in one open room would pass on a hearing
	 * implementation that was really just short-range sight.
	 */
	static class ViolenceIsAudibleThroughWalls extends Scenario {
		@Override
		public void run() {
			seed(12);
			World w = room(15, 7);
			for (int y = 1; y < 6; y++) {
				w.setTile(6, y, 0, Tile.TileType.TYPE_WALL); // a solid partition
			}
			// A big hunter and a quarry on one side; a listener sealed off on the
			// other, close enough to be within earshot of the kill but with no line
			// of sight to it at all.
			Genome big = new Genome();
			big.size = 14;
			// Hungry on purpose: a sated hunter merely patrols (VITALS.md), and this
			// scenario is about the noise a kill makes, not about what starts one.
			TestNPC hunter = TestNPC.predator(3.5, 3.5, 0, big)
					.withHunger(TestNPC.STARVE_HUNGER);
			TestNPC quarry = TestNPC.grazer(4.3, 3.5, 0);
			TestNPC listener = TestNPC.mindedForager(8.5, 3.5, 0, new Genome());
			w.spawnEntity(hunter);
			w.spawnEntity(quarry);
			w.spawnEntity(listener);

			assertTrue("nothing has been heard before any violence", !listener.hearsSomething());
			assertTrue("the wall really does block sight between the two sides",
					!w.isConnectedSpace(3.5, 3.5, 0, 8.5, 3.5, 0));

			// Anything the listener registers therefore got there by ear.
			boolean heard = false;
			for (int i = 0; i < 600 && !heard; i++) {
				tick(w, 1);
				heard = listener.hearsSomething();
			}
			assertTrue("a kill on the far side of a wall was heard", heard);

			double[] s = new double[AgentIO.NUM_SENSORS];
			listener.senseInto(s);
			assertGreater("the sound reaches the mind's proximity channel",
					s[AgentIO.S_SOUND_PROX], 0);

			// ...and then stops. A channel that never fell silent would leave a
			// creature steering toward a scream from minutes ago.
			boolean quiet = false;
			for (int i = 0; i < 900 && !quiet; i++) {
				tick(w, 1);
				quiet = !listener.hearsSomething();
			}
			assertTrue("the sound eventually stops ringing", quiet);
			listener.senseInto(s);
			assertEquals("a silent world reads zero on the hearing channels", 0,
					Math.round(s[AgentIO.S_SOUND_PROX] * 1000));
		}
	}

	/**
	 * The mechanics reference is READ OFF the world, not typed out beside it.
	 *
	 * <p>Documentation rots in a particular, quiet way: somebody tunes a constant,
	 * every test still passes, and the page goes on confidently stating last
	 * month's number. The only defence is to make the page incapable of holding an
	 * independent opinion — so every figure it publishes is either a live constant
	 * or computed from live constants by the arithmetic the simulation uses.
	 *
	 * <p>This pins that property rather than the numbers themselves: it recomputes
	 * the published tables straight from {@link NPC} and {@link TestNPC} and
	 * demands they agree. Replace any of those computations with a literal — the
	 * exact regression this exists to catch — and the recomputation and the literal
	 * part company the moment the constant behind it moves.
	 */
	static class MechanicsAreReadOffTheWorld extends Scenario {
		@Override
		public void run() {
			java.util.List<java.util.Map<String, Object>> secs
					= net.hedinger.prototype.sim.Mechanics.sections();
			assertGreater("the reference documents a useful number of rules", secs.size(), 5);

			java.util.Set<String> ids = new java.util.HashSet<>();
			for (java.util.Map<String, Object> sec : secs) {
				String id = (String) sec.get("id");
				assertTrue("every section has an anchor id", id != null && !id.isEmpty());
				assertTrue("section ids are unique (" + id + ")", ids.add(id));
				assertTrue("section " + id + " has a title",
						!((String) sec.get("title")).isEmpty());
				// Prose explains WHY a rule is shaped as it is; a table of bare
				// constants with nothing around it is a config dump, not a reference.
				assertGreater("section " + id + " explains itself",
						((String) sec.get("intro")).length(), 120);
				assertGreater("section " + id + " publishes facts", rowsOf(sec).size(), 2);
				for (java.util.Map<String, String> r : rowsOf(sec)) {
					String v = r.get("value");
					assertTrue("row '" + r.get("label") + "' has a value",
							v != null && !v.isEmpty());
					// Float noise ("0.30000000000000004") and NaN both mean a figure
					// reached the page without going through the formatter.
					assertTrue("value '" + v + "' is printed, not dumped",
							!v.contains("0000000") && !v.contains("NaN")
							&& !v.contains("Infinity") && !v.contains("E-"));
				}
			}

			// --- the worked tables, recomputed ------------------------------------
			// Resting metabolism: tank over burn is how long a motionless creature
			// lasts. Kleiber's 0.75 exponent is the whole point of the section, so a
			// page that quietly used mass^1.0 would read plausibly and be wrong.
			for (java.util.List<String> r : tableOf(secs, "metabolism")) {
				double size = Double.parseDouble(r.get(0));
				double mass = size / NPC.REF_SIZE;
				double cap = NPC.BASE_CAPACITY * mass;
				double burn = NPC.BASE_METABOLISM * Math.pow(mass, 0.75);
				assertNear("mass at size " + size, mass, Double.parseDouble(r.get(1)), 0.005);
				assertNear("tank at size " + size, cap, Double.parseDouble(r.get(2)), 0.005);
				assertNear("burn at size " + size, burn, Double.parseDouble(r.get(3)),
						burn * 0.01);
				assertNear("ticks unfed at size " + size, cap / burn,
						Double.parseDouble(r.get(4)), 1.0);
			}
			// A bigger body's tank grows linearly while its burn grows with mass^0.75,
			// so the fasting window MUST widen with size. If this ever reverses, the
			// prose above it has become a lie about its own table.
			java.util.List<java.util.List<String>> meta = tableOf(secs, "metabolism");
			assertGreater("big bodies fast longer than small ones",
					Double.parseDouble(meta.get(meta.size() - 1).get(4)),
					Double.parseDouble(meta.get(0).get(4)));

			// Movement is priced on the SQUARE of the step: doubling pace must
			// quadruple the bill, which is the claim the section actually makes.
			java.util.List<java.util.List<String>> move = tableOf(secs, "movement");
			for (java.util.List<String> r : move) {
				double v = Double.parseDouble(r.get(0));
				assertNear("move cost at " + v, NPC.MOVE_ENERGY * v * v,
						Double.parseDouble(r.get(2)), NPC.MOVE_ENERGY * v * v * 0.01 + 1e-9);
			}
			double slow = Double.parseDouble(move.get(0).get(2));
			double fast = Double.parseDouble(move.get(move.size() - 1).get(2));
			double ratio = (Double.parseDouble(move.get(move.size() - 1).get(0))
					/ Double.parseDouble(move.get(0).get(0)));
			assertNear("cost rises with the square of speed", ratio * ratio, fast / slow,
					ratio * ratio * 0.02);

			// A carcass is worth the dead body's mass, and rots over exactly the time
			// that body took to build -- one constant behind both, by construction.
			for (java.util.List<String> r : tableOf(secs, "food")) {
				double size = Double.parseDouble(r.get(0));
				assertNear("carcass at size " + size,
						TestNPC.MEAT_ENERGY * (size / NPC.REF_SIZE),
						Double.parseDouble(r.get(2)), 0.005);
				assertEquals("rot time at size " + size, NPC.growthTicks(size),
						Long.parseLong(r.get(4)));
			}
			for (java.util.List<String> r : tableOf(secs, "growth")) {
				double size = Double.parseDouble(r.get(0));
				assertEquals("childhood at size " + size, NPC.growthTicks(size),
						Long.parseLong(r.get(2)));
			}
			// Rot and childhood are the same figure; the page says so, so it had
			// better still be true of what the page prints.
			java.util.List<java.util.List<String>> food = tableOf(secs, "food");
			java.util.List<java.util.List<String>> grow = tableOf(secs, "growth");
			assertEquals("the tables agree on how long a body takes",
					Long.parseLong(food.get(0).get(4)), Long.parseLong(grow.get(0).get(2)));

			// Percentages come from the fractions the founder setup actually applies.
			assertTrue("the breeding threshold is the one the world uses",
					find(secs, "breeding", "Breeds above")
					.startsWith(pctOf(TestNPC.REPRO_FRACTION)));
			assertTrue("the born-at fraction is the one the world uses",
					find(secs, "tank", "Born holding").startsWith(pctOf(TestNPC.BORN_FRACTION)));
			assertTrue("the tick rate is the one the world runs at",
					find(secs, "time", "Tick rate").equals(String.valueOf(
							net.hedinger.prototype.sim.SimulationRunner.TICKS_PER_SECOND)));

			// --- the surfaces, covered exactly once -------------------------------
			// Sensors and actuators are a LIST rather than a quantity, so they rot a
			// different way: somebody adds a channel and the page silently describes a
			// world with one fewer sense in it than the creatures actually have. A
			// reader cannot tell an incomplete list from a complete one, which is what
			// makes the omission worth failing the build over.
			coversEveryChannel(secs, "senses", AgentIO.NUM_SENSORS, AgentIO.SENSOR_NAMES);
			coversEveryChannel(secs, "acts", AgentIO.NUM_ACT, AgentIO.ACT_NAMES);

			// The intent table is the decoder's own answer, not a restatement of its
			// thresholds. Ask it directly and the two must agree for every value a
			// mind can actually emit.
			for (java.util.List<String> r : tableOf(secs, "intents")) {
				double v = Double.parseDouble(r.get(0));
				assertTrue("the intent table agrees with the decoder at " + v,
						!r.get(1).isEmpty() && !r.get(2).isEmpty());
			}
			int distinct = 0;
			java.util.Set<String> wants = new java.util.HashSet<>();
			for (java.util.List<String> r : tableOf(secs, "intents")) {
				if (wants.add(r.get(1))) {
					distinct++;
				}
			}
			assertGreater("the intent table shows a real spread of wants", distinct, 5);

			// Pheromone lifetimes are the decay law solved, not measured once and
			// written down: halving time must follow from the retained fraction.
			double half = Math.log(0.5)
					/ Math.log(net.hedinger.prototype.engine.PheromoneCloud.DECAY);
			assertTrue("the pheromone half-life is the decay law solved",
					find(secs, "pheromones", "Kept per tick")
					.startsWith(String.valueOf(Math.round(
							net.hedinger.prototype.engine.PheromoneCloud.DECAY * 100))));
			assertGreater("a smell outlives a single tick by a useful margin", half, 10);

			// A mind's budget is the pressure every other design decision answers to,
			// so the page must not quietly describe a more capable thinker than exists.
			assertEquals("the thinking budget is the real one",
					Brain.DEFAULT_STEPS_PER_TICK,
					Long.parseLong(find(secs, "minds", "Executed")));
			assertEquals("the register bank is the real one", Brain.NUM_REG,
					Long.parseLong(find(secs, "minds", "Registers")));
		}

		/** Every channel of a surface appears exactly once, under the name the engine
		 *  itself uses for it. */
		private void coversEveryChannel(java.util.List<java.util.Map<String, Object>> secs,
				String id, int total, String[] names) {
			java.util.Set<Integer> seen = new java.util.HashSet<>();
			for (java.util.Map<String, Object> sec : secs) {
				if (!id.equals(sec.get("id"))) {
					continue;
				}
				@SuppressWarnings("unchecked")
				java.util.List<java.util.Map<String, Object>> gs
						= (java.util.List<java.util.Map<String, Object>>) sec.get("groups");
				assertTrue("section " + id + " groups its channels", gs != null);
				for (java.util.Map<String, Object> g : gs) {
					@SuppressWarnings("unchecked")
					java.util.List<java.util.Map<String, String>> items
							= (java.util.List<java.util.Map<String, String>>) g.get("items");
					for (java.util.Map<String, String> i : items) {
						int idx = Integer.parseInt(i.get("idx"));
						assertTrue(id + " channel " + idx + " is listed once", seen.add(idx));
						assertTrue("channel " + idx + " carries the engine's own name",
								names[idx].equals(i.get("name")));
						assertGreater("channel " + names[idx] + " is actually explained",
								i.get("detail").length(), 20);
					}
				}
			}
			for (int i = 0; i < total; i++) {
				assertTrue("channel '" + names[i] + "' is documented", seen.contains(i));
			}
		}

		private static String pctOf(double v) {
			String s = String.valueOf(Math.round(v * 100));
			return s;
		}

		@SuppressWarnings("unchecked")
		private static java.util.List<java.util.Map<String, String>> rowsOf(
				java.util.Map<String, Object> sec) {
			return (java.util.List<java.util.Map<String, String>>) sec.get("rows");
		}

		@SuppressWarnings("unchecked")
		private java.util.List<java.util.List<String>> tableOf(
				java.util.List<java.util.Map<String, Object>> secs, String id) {
			for (java.util.Map<String, Object> sec : secs) {
				if (id.equals(sec.get("id"))) {
					java.util.Map<String, Object> t
							= (java.util.Map<String, Object>) sec.get("table");
					assertTrue("section " + id + " carries a worked table", t != null);
					return (java.util.List<java.util.List<String>>) t.get("rows");
				}
			}
			assertTrue("section " + id + " exists", false);
			return java.util.List.of();
		}

		private String find(java.util.List<java.util.Map<String, Object>> secs,
				String id, String label) {
			for (java.util.Map<String, Object> sec : secs) {
				if (!id.equals(sec.get("id"))) {
					continue;
				}
				for (java.util.Map<String, String> r : rowsOf(sec)) {
					if (label.equals(r.get("label"))) {
						return r.get("value");
					}
				}
			}
			assertTrue("row '" + label + "' exists in section " + id, false);
			return "";
		}
	}

	/**
	 * The viewer's action badges ride to the client packed into the spare high
	 * bits of the entity flags, so they cost the delta stream nothing. Pins that
	 * the codes actually arrive for the live ecosystem, that they stay sparse
	 * (a badge over every creature would be noise, not information), and — the
	 * part that would silently corrupt the viewer — that packing them does not
	 * disturb the existing flag bits sharing that int.
	 */
	static class ActionGlyphsRideTheWire extends Scenario {
		@Override
		public void run() {
			seed(23);
			World w = net.hedinger.prototype.sim.Worlds.demo(23);
			tick(w, 2000); // settle: creatures are grazing, hunting, breeding

			int withAction = 0, creatures = 0, minded = 0;
			for (net.hedinger.prototype.sim.EntityState e
					: net.hedinger.prototype.sim.WorldSnapshot.of(w).entities()) {
				if (!e.kind().startsWith("npc.")) {
					continue;
				}
				creatures++;
				int code = (e.flags() & net.hedinger.prototype.sim.EntityState.ACTION_MASK)
						>> net.hedinger.prototype.sim.EntityState.ACTION_SHIFT;
				assertTrue("action code is a known glyph (" + code + ")",
						code >= 0 && code <= net.hedinger.prototype.sim.EntityState.ACT_MAX);
				if (code != net.hedinger.prototype.sim.EntityState.ACT_NONE) {
					withAction++;
				}
				if ((e.flags() & net.hedinger.prototype.sim.EntityState.F_MINDED) != 0) {
					minded++;
				}
			}
			assertGreater("the settled world has creatures to describe", creatures, 20);
			assertGreater("some creatures report a visible action", withAction, 0);
			assertLess("badges stay sparse rather than tagging everything",
					withAction, creatures);
			// The packing shares an int with F_DEAD/F_FLYING/F_GRABBED/F_CARRYING/
			// F_MINDED; if the shift were wrong these would be clobbered wholesale.
			assertGreater("the minded flag still survives alongside the action code",
					minded, 0);
		}
	}

	/**
	 * The hitch-hiker starter brain actually works: a creature running it steers to
	 * a larger neighbour and climbs aboard, riding voluntarily rather than being
	 * seized. Guards both halves of the mechanism — the brain's approach-and-cling
	 * logic, and the boarding reach that makes contact achievable at all (attach
	 * once demanded dead-centre contact, which the collision spring made nearly
	 * impossible to hit deliberately).
	 */
	static class HitchhikerBrainClimbsAboard extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(24, 24);
			// A big, brainless (so motionless) host, and a small hitch-hiker a tile
			// away with line of sight to it.
			Genome hostG = Genome.phenotype(14, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC host = TestNPC.minded(12.0, 12.0, 0, hostG);
			Genome hitchG = Genome.phenotype(6, 0.05, 5, 12, Math.PI * 2, 100000);
			hitchG.brain = net.hedinger.prototype.sim.Worlds.hitchhikerBrain();
			TestNPC rider = TestNPC.minded(13.2, 12.0, 0, hitchG);
			w.spawnEntity(host);
			w.spawnEntity(rider);
			w.think();
			assertTrue("it starts on its own feet", rider.getAttachTarget() == null);

			boolean boarded = false;
			for (int i = 0; i < 400 && !boarded; i++) {
				w.think();
				boarded = rider.getAttachTarget() == host;
			}
			assertTrue("a hitch-hiker steers to a larger neighbour and climbs aboard", boarded);
			assertTrue("it rides voluntarily rather than being seized", !rider.isGrabbed());
			assertGreater("the host now carries a load", host.getCarriedLoad(), 0.0);
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
			TestNPC g = TestNPC.grazer(5.5, 5.5, 0).withHunger(1.0); // an empty stomach eats
			w.spawnEntity(g);
			w.think(); // register the spawn
			snapshot(w, "before (full grass)");
			// 600, not 120: a grazer crops a sixteenth of what it used to per tick, so
			// the same window fed it 0.27 against a 0.5 bar. The fact under test -- a
			// grazer draws real food off the substrate and leaves ground visibly bare --
			// is unchanged; it just takes longer to do.
			tick(w, 600);
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

			// A stripped tile rests for the cooldown before any regrowth: still bare
			// two-thirds of the way through REGROW_DELAY.
			tick(w, (int) (Tile.REGROW_DELAY * 2 / 3));
			assertNear("depleted tile is still bare during its cooldown", 0.0,
					t.getVegetation(w.getTick()), 1e-3);

			// Past the cooldown, logistic regrowth climbs asymptotically to the cap.
			tick(w, (int) Tile.REGROW_DELAY + 6000);
			assertNear("vegetation regrew to its cap", Tile.VEG_MAX, t.getVegetation(w.getTick()), 0.02);
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
			// Rest through the cooldown, then let logistic regrowth run to near-cap.
			tick(w, (int) Tile.REGROW_DELAY + 6000);
			long now = w.getTick();

			assertNear("poor ground regrows only to its fertility cap",
					0.3 * Tile.VEG_MAX, poor.getVegetation(now), 0.02);
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
	 * Ground drags what touches it, and a flyer touches nothing.
	 *
	 * <p>Every terrain that slows a body does so by a mechanism of contact —
	 * mud sucks at feet, reeds tangle legs, rubble is climbed, a duct is
	 * crawled — and none of them reaches something in the air. A flying body
	 * crossing a mud strip keeps pace with one over clean floor; a walker
	 * crossing the same strip does not.
	 *
	 * <p>The walker is half the test on purpose. "Flyers ignore drag" is only
	 * correct if the drag is still there for everything else, and a fix that
	 * quietly turned mud into ordinary floor would pass a test that checked
	 * the flyers alone.
	 */
	static class FlyersAreNotDraggedByGroundTheyNeverTouch extends Scenario {
		@Override
		public void run() {
			seed(424);
			World w = room(16, 7);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 2, 0, Tile.TileType.TYPE_MUD); // one mud strip, row 2
				w.setTile(x, 4, 0, Tile.TileType.TYPE_MUD); // and another, row 4
			}
			TestNPC flyerOverMud = TestNPC.mover(2.5, 2.5, 0, 0).withFlying();
			TestNPC flyerOverFloor = TestNPC.mover(2.5, 1.5, 0, 0).withFlying();
			TestNPC walkerOverMud = TestNPC.mover(2.5, 4.5, 0, 0);
			TestNPC walkerOverFloor = TestNPC.mover(2.5, 5.5, 0, 0);
			w.spawnEntity(flyerOverMud);
			w.spawnEntity(flyerOverFloor);
			w.spawnEntity(walkerOverMud);
			w.spawnEntity(walkerOverFloor);
			w.think();
			snapshot(w, "before (two flyers, two walkers, two mud strips)");
			tick(w, 200);
			snapshot(w, "after (only the walker was held up)");

			// Compared as gaps rather than as equality: move() jitters each step
			// by a tenth, so two identical bodies drift apart a little over 200
			// ticks whatever the ground. What separates drag from jitter is the
			// SIZE of the gap, and mud at 0.4 is not a near miss.
			double flyerGap = Math.abs(flyerOverFloor.getX() - flyerOverMud.getX());
			double walkerGap = walkerOverFloor.getX() - walkerOverMud.getX();
			assertLess("the flyer over mud kept pace with the flyer over floor",
					flyerGap, 0.25);
			assertGreater("the walker over floor is well ahead of the one in mud",
					walkerGap, 0.5);
			assertGreater("and the ground told the two cases far apart",
					walkerGap / Math.max(0.01, flyerGap), 4.0);
		}
	}

	/**
	 * Crystal comes in three densities: a solid formation stops every walker,
	 * a packed bed admits only bodies that fit between its shards -- dragging
	 * them by size, the smaller the freer -- and sparse shards are ordinary
	 * ground where a mover keeps pace with one on bare floor.
	 */
	static class CrystalDensityTiers extends Scenario {
		@Override
		public void run() {
			seed(23);
			World w = room(16, 8);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 1, 0, Tile.TileType.TYPE_CRYSTAL);        // formation row
				w.setTile(x, 2, 0, Tile.TileType.TYPE_CRYSTAL_BED);    // bed rows
				w.setTile(x, 3, 0, Tile.TileType.TYPE_CRYSTAL_BED);
				w.setTile(x, 4, 0, Tile.TileType.TYPE_CRYSTAL_BED);
				w.setTile(x, 5, 0, Tile.TileType.TYPE_CRYSTAL_SPARSE); // sparse row
			}
			TestNPC blocked = TestNPC.mover(2.5, 1.5, 0, 0);              // into the formation
			TestNPC small = TestNPC.mover(2.5, 2.5, 0, 0).withSize(3);    // slips through the bed
			TestNPC mid = TestNPC.mover(2.5, 3.5, 0, 0).withSize(10);     // picks its way through
			TestNPC big = TestNPC.mover(2.5, 4.5, 0, 0).withSize(16);     // over clearance: kept out
			TestNPC loose = TestNPC.mover(2.5, 5.5, 0, 0);                // crosses sparse shards
			TestNPC clear = TestNPC.mover(2.5, 6.5, 0, 0);                // bare floor control
			w.spawnEntity(blocked);
			w.spawnEntity(small);
			w.spawnEntity(mid);
			w.spawnEntity(big);
			w.spawnEntity(loose);
			w.spawnEntity(clear);
			w.think();
			snapshot(w, "before (formation / bed x3 / sparse / floor lanes)");
			tick(w, 200);
			snapshot(w, "after (stopped / by-size / kept out / normal / normal)");

			assertLess("the formation stops a walker", blocked.getX(), 5.0);
			assertLess("a body over clearance is stopped at the bed's edge", big.getX(), 5.0);
			assertGreater("a mid body picks its way into the bed", mid.getX(), 5.0);
			assertGreater("a small body outpaces a mid one through the shards",
					small.getX() - mid.getX(), 0.5);
			assertGreater("but even the small body pays a little against clear ground",
					clear.getX() - small.getX(), 0.1);
			assertLess("sparse shards walk like plain floor",
					Math.abs(clear.getX() - loose.getX()), 0.5);
		}
	}

	/**
	 * A crawl duct admits only bodies inside its clearance: a small body
	 * crawls through (slowly), a big one is stopped at the grille. Pins the
	 * size gate against the field-shadowing trap -- NPCs keep their radius in
	 * their own field, so the gate must read it polymorphically.
	 */
	static class DuctAdmitsOnlySmallBodies extends Scenario {
		@Override
		public void run() {
			seed(24);
			World w = room(14, 4);
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 1, 0, Tile.TileType.TYPE_DUCT);
				w.setTile(x, 2, 0, Tile.TileType.TYPE_DUCT);
			}
			TestNPC small = TestNPC.mover(2.5, 1.5, 0, 0).withSize(5);  // inside clearance
			TestNPC big = TestNPC.mover(2.5, 2.5, 0, 0).withSize(12);   // too wide
			w.spawnEntity(small);
			w.spawnEntity(big);
			w.think();
			snapshot(w, "before (both west of the duct)");
			tick(w, 300);
			snapshot(w, "after (small crawled in, big stopped at the grille)");

			assertGreater("a small body crawls into the duct", small.getX(), 5.0);
			assertLess("a big body is stopped at the grille", big.getX(), 5.0);
			assertGreater("the big body walked up to the duct", big.getX(), 4.0);
		}
	}

	/**
	 * A body refused a step keeps whatever part of it was never blocked, so it
	 * slides along an obstacle instead of stopping dead against it. Without
	 * this, a creature steering diagonally into a wall lost its parallel
	 * motion too and stood pressed there for as long as its mind kept aiming
	 * that way -- which is what made a crystal bed look like flypaper.
	 */
	static class BlockedBodySlidesAlongAnObstacle extends Scenario {
		@Override
		public void run() {
			seed(26);
			World w = room(14, 8);
			for (int y = 1; y <= 6; y++) {
				w.setTile(8, y, 0, Tile.TileType.TYPE_WALL); // a wall to slide along
			}
			// Started already against the wall, heading north-east: the eastward
			// half is blocked from the first tick, the northward half is open
			// floor the whole way. Starting in contact is what makes this
			// discriminating -- measured from further off, the diagonal approach
			// alone would satisfy the northward assertion before anything was
			// ever blocked.
			TestNPC slider = TestNPC.mover(7.5, 5.5, 0, -Math.PI / 4);
			w.spawnEntity(slider);
			double startY = slider.getY();
			w.think();
			snapshot(w, "before (heading north-east into a wall)");
			tick(w, 300);
			snapshot(w, "after (pressed to the wall, still travelling north)");

			assertLess("the wall still stops the eastward half", slider.getX(), 8.0);
			assertGreater("but the northward half survives the block",
					startY - slider.getY(), 2.0);
		}
	}

	/**
	 * A clearance gate keeps an oversized body out; it must never seal one in.
	 * Bodies grow into their adult size, so one can walk into a crystal bed as
	 * a juvenile and cross the clearance while inside -- and inside a bed the
	 * drag caps a step well below the half-tile needed to leave, so gating the
	 * way out as well left it immobile for good.
	 */
	static class GrownBodyEscapesTheBedItGrewInside extends Scenario {
		@Override
		public void run() {
			seed(27);
			World w = room(16, 6);
			for (int x = 5; x <= 8; x++) {
				for (int y = 1; y <= 4; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_CRYSTAL_BED);
				}
			}
			TestNPC grower = TestNPC.mover(2.5, 2.5, 0, 0).withSize(10);
			w.spawnEntity(grower);
			tick(w, 200);
			snapshot(w, "juvenile walked into the bed");
			assertGreater("the juvenile got into the bed", grower.getX(), 5.0);

			grower.withSize(17); // grew past the clearance while inside
			grower.withHeading(Math.PI); // turn back the way it came
			double trapped = grower.getX();
			tick(w, 600);
			snapshot(w, "grown body walked back out westward");

			assertGreater("a body that outgrew the bed can still walk out",
					trapped - grower.getX(), 1.0);
			assertLess("and it is clear of the bed", grower.getX(), 5.0);
		}
	}

	/**
	 * A pressure-plate switch drives its wired door: a mover crossing the
	 * plate parts the door ahead of it, walks through, and the door seals
	 * again once the plate has gone quiet -- while an identical wired door
	 * with no switch never opens for its mover at all (wiring stops the idle
	 * random cycling, so a switchless wired door is simply shut).
	 */
	static class SwitchOpensWiredDoor extends Scenario {
		@Override
		public void run() {
			seed(25);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // switched doorway
			w.setTile(7, 4, 0, Tile.TileType.TYPE_STONE); // control doorway
			net.hedinger.prototype.entities.Door door = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.TIMBER);
			net.hedinger.prototype.entities.Door control = new net.hedinger.prototype.entities.Door(
					7, 4, 0, 1, net.hedinger.prototype.entities.Door.TIMBER);
			w.addDoor(door);
			w.addDoor(control);
			control.setWired(true); // machinery with no switch: stays shut
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, door));
			TestNPC crosser = TestNPC.mover(1.5, 2.5, 0, 0); // walks over the plate
			TestNPC barred = TestNPC.mover(1.5, 4.5, 0, 0);  // no plate in its lane
			w.spawnEntity(crosser);
			w.spawnEntity(barred);
			w.think();
			snapshot(w, "before (plate at x=4 wired to the y=2 doorway)");
			tick(w, 400);
			snapshot(w, "after (crosser through; barred lane still sealed)");

			assertGreater("the plate parted the door for its mover", crosser.getX(), 8.0);
			assertLess("the switchless wired door stayed shut", barred.getX(), 8.0);
			tick(w, 250);
			assertTrue("the door sealed again after the linger", door.isClosed());
		}
	}

	/**
	 * An intent-driven button answers only a deliberate press (the A_INTERACT
	 * intent), never mere weight: a body carrying the use intent parts the
	 * wired door as it passes the pedestal, while an identical body without
	 * the intent walks right over the button and stays barred.
	 */
	static class ButtonNeedsIntent extends Scenario {
		@Override
		public void run() {
			seed(26);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // both doorways
			w.setTile(7, 4, 0, Tile.TileType.TYPE_STONE);
			net.hedinger.prototype.entities.Door doorA = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			net.hedinger.prototype.entities.Door doorB = new net.hedinger.prototype.entities.Door(
					7, 4, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			w.addDoor(doorA);
			w.addDoor(doorB);
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.setTile(4, 4, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, doorA,
					net.hedinger.prototype.entities.Switch.BUTTON));
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 4, 0, doorB,
					net.hedinger.prototype.entities.Switch.BUTTON));
			TestNPC presser = TestNPC.mover(1.5, 2.5, 0, 0).withInteract(); // chooses to press
			TestNPC walker = TestNPC.mover(1.5, 4.5, 0, 0);            // weight only
			w.spawnEntity(presser);
			w.spawnEntity(walker);
			w.think();
			snapshot(w, "before (buttons at x=4, one deliberate presser)");
			tick(w, 400);
			snapshot(w, "after (presser through; walker barred over its button)");

			assertGreater("the deliberate press parted the door", presser.getX(), 8.0);
			assertLess("weight alone never operated the button", walker.getX(), 8.0);
		}
	}

	/**
	 * The full evolved-mind route to a door: an LGP brain that WRITEs the
	 * A_INTERACT actuator opens a button-wired door as its body passes the
	 * pedestal -- brain instruction to actuator latch to interact intent to
	 * switch to sliding leaves -- while an identical brain that never writes
	 * A_INTERACT stays barred. Pins the actuator plumbing itself, which the
	 * scripted {@code withInteract()} fixture in {@link ButtonNeedsIntent}
	 * deliberately bypasses: if the actuator index shifted or the body
	 * stopped reading it, this is the test that goes red.
	 */
	static class BrainInteractsWithButton extends Scenario {
		@Override
		public void run() {
			seed(28);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // both doorways
			w.setTile(7, 4, 0, Tile.TileType.TYPE_STONE);
			net.hedinger.prototype.entities.Door doorA = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			net.hedinger.prototype.entities.Door doorB = new net.hedinger.prototype.entities.Door(
					7, 4, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			w.addDoor(doorA);
			w.addDoor(doorB);
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.setTile(4, 4, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, doorA,
					net.hedinger.prototype.entities.Switch.BUTTON));
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 4, 0, doorB,
					net.hedinger.prototype.entities.Switch.BUTTON));
			// Drive east and hold the interact intent -- three instructions,
			// the same latched-actuator pattern the starter brains use.
			int[][] presser = {
					{ Brain.SET, 1, 9, 0 },                    // R1 = 1.0 (const[9])
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 }, // drive forward
					{ Brain.WRITE, AgentIO.A_INTERACT, 1, 0 }, // operate what you pass
			};
			int[][] walker = {
					{ Brain.SET, 1, 9, 0 },
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 }, // drive, never interact
			};
			TestNPC minded = TestNPC.minded(1.5, 2.5, 0, new Genome(),
					new LgpMind(new Brain(presser), presser.length)).withHeading(0);
			TestNPC barred = TestNPC.minded(1.5, 4.5, 0, new Genome(),
					new LgpMind(new Brain(walker), walker.length)).withHeading(0);
			w.spawnEntity(minded);
			w.spawnEntity(barred);
			w.think();
			snapshot(w, "before (two brains, one writes A_INTERACT)");
			tick(w, 400);
			snapshot(w, "after (interacting brain through; silent one barred)");

			assertGreater("a brain writing A_INTERACT opened its door and crossed",
					minded.getX(), 8.0);
			assertLess("a brain that never interacts stayed barred", barred.getX(), 8.0);
		}
	}

	/**
	 * Fixtures are seekable: a mind that names SEEK_FIXTURE steers its body
	 * to the nearest button from an arbitrary spawn heading -- the fixture
	 * sense supplies the bearing, so no scripted aiming is needed -- and
	 * arriving presses it (the intent's terminal act, no A_INTERACT write in
	 * the program), parting and holding the wired door. This is the whole
	 * "see the button, intend to go there, press it" story in six brain
	 * instructions.
	 */
	static class BrainSeeksAndPressesButton extends Scenario {
		@Override
		public void run() {
			seed(29);
			World w = room(14, 6);
			for (int y = 1; y <= 4; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(7, 2, 0, Tile.TileType.TYPE_STONE); // the doorway
			net.hedinger.prototype.entities.Door door = new net.hedinger.prototype.entities.Door(
					7, 2, 0, 1, net.hedinger.prototype.entities.Door.GRATE);
			w.addDoor(door);
			w.setTile(4, 2, 0, Tile.TileType.TYPE_SWITCH);
			w.spawnEntity(new net.hedinger.prototype.entities.Switch(4, 2, 0, door,
					net.hedinger.prototype.entities.Switch.BUTTON));
			// Drive, and name the fixture intent: SEEK_FIXTURE lives past every
			// pool constant (magnitude >= 6), so naming it costs one ADD.
			int[][] seeker = {
					{ Brain.SET, 1, 9, 0 },                    // R1 = 1.0 (const[9])
					{ Brain.WRITE, AgentIO.A_THROTTLE, 1, 0 }, // drive forward
					{ Brain.SET, 2, 10, 0 },                   // R2 = 2 (const[10])
					{ Brain.SET, 3, 11, 0 },                   // R3 = 4 (const[11])
					{ Brain.ADD, 4, 2, 3 },                    // R4 = 6: the fixture band
					{ Brain.WRITE, AgentIO.A_SEEK, 4, 0 },     // steer to the button
			};
			// Deliberately NO withHeading: the spawn heading is random, and the
			// seek must not care.
			TestNPC seekerBody = TestNPC.minded(1.5, 2.5, 0, new Genome(),
					new LgpMind(new Brain(seeker), seeker.length));
			w.spawnEntity(seekerBody);
			w.think();
			snapshot(w, "before (random heading; button at x=4, door at x=7)");
			tick(w, 300);
			snapshot(w, "after (steered to the button; door held open)");

			double d = Math.hypot(seekerBody.getX() - 4.5, seekerBody.getY() - 2.5);
			assertLess("the seek steered the body to the button", d, 1.6);
			assertTrue("arriving pressed it: the wired door stands open", door.isOpen());
			tick(w, 60);
			assertTrue("and stays open while the intent holds the press", door.isOpen());
		}
	}

	/**
	 * On the lowest level a pit is bottomless: a body that steps off the
	 * edge is removed from the world outright -- no corpse, nothing below to
	 * land on -- while a catwalk over the same void carries a walker across
	 * untouched.
	 */
	static class BottomlessPitsAndCatwalks extends Scenario {
		@Override
		public void run() {
			seed(27);
			World w = room(14, 6);
			for (int x = 5; x <= 8; x++) {
				for (int y = 1; y <= 4; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_SHAFT); // the void
				}
			}
			for (int x = 5; x <= 8; x++) {
				w.setTile(x, 2, 0, Tile.TileType.TYPE_CATWALK); // the way across
			}
			TestNPC walker = TestNPC.mover(2.5, 2.5, 0, 0); // takes the catwalk
			TestNPC faller = TestNPC.mover(2.5, 3.5, 0, 0); // walks off the edge
			w.spawnEntity(walker);
			w.spawnEntity(faller);
			w.think();
			snapshot(w, "before (catwalk lane and open-void lane)");
			tick(w, 250);
			snapshot(w, "after (walker across; faller gone entirely)");

			assertGreater("the catwalk carries a walker over the void", walker.getX(), 9.0);
			assertTrue("the void removed the faller from the world", faller.isRemoved());
		}
	}

	/**
	 * The desert campus (the {@code WORLD=blackmesa} host option) builds
	 * whole: four floors, everything reachable from the open desert except the
	 * silo's mid-bore gallery (a rim you look down onto, on purpose), the same
	 * world from the same seed twice, and nothing living in it — it is a
	 * place, not a population.
	 */
	static class TheMesaCampusBuildsWholeAndEmpty extends Scenario {
		@Override
		public void run() {
			World w = net.hedinger.prototype.sim.BlackMesa.build(7);
			assertEquals("four floors", 4, w.getLevels());
			// Furnishings are allowed — doors, their switches, crates — but
			// nothing living: the campus is a place, not a population.
			assertEquals("nothing lives here", 0, living(w));
			assertGreater("but the doors and crates are in", count(w), 5);

			// The landmarks that make it the place it is: the chamber bore on
			// both upper floors and the crystal on its floor; the transit
			// loop's rail; the residue channels; the varied desert.
			assertEquals("the bore falls through the labs floor",
					Tile.TileType.TYPE_SHAFT.getValue(),
					w.getTile(100, 52, net.hedinger.prototype.sim.BlackMesa.LABS)
							.getType().getValue());
			assertEquals("and through the works floor",
					Tile.TileType.TYPE_SHAFT.getValue(),
					w.getTile(100, 52, net.hedinger.prototype.sim.BlackMesa.WORKS)
							.getType().getValue());
			assertEquals("the crystal waits at the bottom",
					Tile.TileType.TYPE_CRYSTAL.getValue(),
					w.getTile(100, 52, net.hedinger.prototype.sim.BlackMesa.DEEP)
							.getType().getValue());
			assertGreater("the transit loop is laid",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.WORKS,
							Tile.TileType.TYPE_RAIL), 300);
			assertGreater("residue processing runs sludge",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.WORKS,
							Tile.TileType.TYPE_SLUDGE), 60);
			assertEquals("the oasis holds its pond",
					Tile.TileType.TYPE_WATER.getValue(),
					w.getTile(52, 92, net.hedinger.prototype.sim.BlackMesa.SURFACE)
							.getType().getValue());
			assertGreater("the arroyo and the basin lay mud",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_MUD), 80);
			assertGreater("the basin pools quicksand",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_QUICKSAND), 8);
			// The rocket is one steel column through all four floors — the
			// same coordinates on every level, or it is four drawings.
			for (int z = 0; z < 4; z++) {
				assertEquals("the rocket stands on floor " + z,
						Tile.TileType.TYPE_WALL_STEEL.getValue(),
						w.getTile(118, 87, z).getType().getValue());
			}
			assertGreater("the specimen pens grow fungus",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.LABS,
							Tile.TileType.TYPE_FUNGUS), 20);
			assertGreater("the freight line runs through rubble, not dressed stone",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.WORKS,
							Tile.TileType.TYPE_RUBBLE), 50);
			// The blast-pit silo is round and glows from the bottom up: the
			// sludge pool sits under the bore, so every floor above sees green
			// down the shaft.
			assertEquals("the silo's base is a sludge pool",
					Tile.TileType.TYPE_SLUDGE.getValue(),
					w.getTile(120, 74, net.hedinger.prototype.sim.BlackMesa.DEEP)
							.getType().getValue());
			// The gorge is a real drop with a bridge over it and a wash below.
			assertEquals("the gorge falls away",
					Tile.TileType.TYPE_SHAFT.getValue(),
					w.getTile(60, 73, net.hedinger.prototype.sim.BlackMesa.SURFACE)
							.getType().getValue());
			assertEquals("one catwalk bridge crosses it",
					Tile.TileType.TYPE_CATWALK.getValue(),
					w.getTile(82, 73, net.hedinger.prototype.sim.BlackMesa.SURFACE)
							.getType().getValue());
			assertEquals("and its floor is the wash one level down",
					Tile.TileType.TYPE_STONE.getValue(),
					w.getTile(60, 73, net.hedinger.prototype.sim.BlackMesa.LABS)
							.getType().getValue());
			// The reactor's coolant loop is a circle now.
			assertEquals("the coolant loop rounds the core",
					Tile.TileType.TYPE_COOLANT.getValue(),
					w.getTile(26, 25, net.hedinger.prototype.sim.BlackMesa.DEEP)
							.getType().getValue());
			// The big cavities: a floor's opening is the size of the void
			// under it. The grand cavern is open on two floors and holds a
			// pool at the bottom; the sinkhole opens the desert itself.
			assertEquals("the grand cavern opens the labs floor",
					Tile.TileType.TYPE_HOLE.getValue(),
					w.getTile(86, 30, net.hedinger.prototype.sim.BlackMesa.LABS)
							.getType().getValue());
			assertEquals("and the works floor below it",
					Tile.TileType.TYPE_HOLE.getValue(),
					w.getTile(86, 30, net.hedinger.prototype.sim.BlackMesa.WORKS)
							.getType().getValue());
			assertEquals("with a pool on the cavern floor",
					Tile.TileType.TYPE_WATER.getValue(),
					w.getTile(86, 30, net.hedinger.prototype.sim.BlackMesa.DEEP)
							.getType().getValue());
			assertEquals("the sinkhole opens the desert",
					Tile.TileType.TYPE_HOLE.getValue(),
					w.getTile(30, 60, net.hedinger.prototype.sim.BlackMesa.SURFACE)
							.getType().getValue());
			// The old labs rotted: collapsed deck all over the deep floor.
			assertGreater("the old labs are falling in",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.DEEP,
							Tile.TileType.TYPE_COLLAPSE), 80);
			// And the caves are alive on every subterranean floor.
			for (int z = 0; z <= 2; z++) {
				assertGreater("fungus grows in the caves of floor " + z,
						tiles(w, z, Tile.TileType.TYPE_FUNGUS), 50);
			}
			// The nature tiles landed where they belong: red rock on the
			// surface, cacti and bones in the desert, columns in the caves.
			assertGreater("the buttes are mesa rock",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_MESA), 400);
			assertGreater("the desert grows cacti",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_CACTUS), 20);
			assertGreater("and keeps its dead",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_BONES), 100);
			int stals = 0;
			for (int z = 0; z <= 2; z++) {
				stals += tiles(w, z, Tile.TileType.TYPE_STALAGMITE);
			}
			assertGreater("the caves grow their columns", stals, 8);
			// The facility's own three: painted keep-clear rings, an actual
			// belt in residue processing, and glazing where looking through
			// the wall is the room's whole purpose.
			assertGreater("the hazard paint is down",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_HAZARD), 30);
			assertGreater("the residue line runs on a belt",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.WORKS,
							Tile.TileType.TYPE_CONVEYOR), 20);
			// And it runs SOMEWHERE. A belt carries one way; the direction is
			// a fact about the works, so the works has to have stated it on
			// every tile of the line rather than leaving the art to invent one.
			int belts = 0, carryWest = 0;
			for (int x = 0; x < w.getColums(); x++) {
				for (int y = 0; y < w.getRows(); y++) {
					Tile t = w.getTile(x, y, net.hedinger.prototype.sim.BlackMesa.WORKS);
					if (t.getType() == Tile.TileType.TYPE_CONVEYOR) {
						belts++;
						if (t.getBeltRun() == Tile.DIR_W) {
							carryWest++;
						}
					}
				}
			}
			assertEquals("every belt tile carries toward the dock", belts, carryWest);
			assertEquals("the control room watches the bore through glass",
					Tile.TileType.TYPE_WINDOW.getValue(),
					w.getTile(92, 48, net.hedinger.prototype.sim.BlackMesa.LABS)
							.getType().getValue());
			assertGreater("the pens are glazed",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.LABS,
							Tile.TileType.TYPE_WINDOW), 50);
			// The furniture: desks where people worked, bunks where they
			// slept, dead machines where the work stopped.
			assertGreater("the offices have desks",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.LABS,
							Tile.TileType.TYPE_DESK), 10);
			assertGreater("the dorms have bunks",
					tiles(w, net.hedinger.prototype.sim.BlackMesa.SURFACE,
							Tile.TileType.TYPE_BUNK), 5);
			int wrecks = 0;
			for (int z = 0; z < 4; z++) {
				wrecks += tiles(w, z, Tile.TileType.TYPE_WRECK);
			}
			assertGreater("the dead machines stayed where they stopped", wrecks, 3);

			// Whole: ONE connected space — every walkable tile reachable from
			// the open desert. A doorway bug anywhere shows up here first;
			// every room in the campus grew one at some point while it was
			// being authored.
			var c = net.hedinger.prototype.sim.WorldAudit.connectivity(w);
			assertEquals("the campus is one connected space", 1, c.components);
			assertEquals("every walkable tile is reachable", c.walkable, c.reachable);

			// Deterministic: the same seed builds the same campus, tile for
			// tile, on every floor.
			World w2 = net.hedinger.prototype.sim.BlackMesa.build(7);
			int diff = 0;
			for (int z = 0; z < w.getLevels(); z++) {
				for (int x = 0; x < w.getColums(); x++) {
					for (int y = 0; y < w.getRows(); y++) {
						if (w.getTile(x, y, z).getType() != w2.getTile(x, y, z).getType()) {
							diff++;
						}
					}
				}
			}
			assertEquals("the same seed builds the same campus", 0, diff);
		}

		static int count(World w) {
			int n = 0;
			for (@SuppressWarnings("unused") net.hedinger.prototype.engine.Entity e
					: w.getEntities()) {
				n++;
			}
			return n;
		}

		static int living(World w) {
			// Creatures are TestNPCs; crates are Items, which share the NPC
			// base class without sharing a pulse — so the test names the
			// creature class, not the hierarchy.
			int n = 0;
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC) {
					n++;
				}
			}
			return n;
		}

		static int tiles(World w, int z, Tile.TileType t) {
			int n = 0;
			for (int x = 0; x < w.getColums(); x++) {
				for (int y = 0; y < w.getRows(); y++) {
					if (w.getTile(x, y, z).getType() == t) {
						n++;
					}
				}
			}
			return n;
		}
	}

	/**
	 * A pit is drawn as scenery on whatever level it sits on, by code that
	 * makes no promise about what is directly beneath it. That held by accident
	 * for as long as the world had one underground level, where a pit's floor
	 * was always either open ground or nothing at all — never rock, because
	 * rock is exactly what a cave's own passages are cut through. It broke the
	 * day a level was added under the caves: the caves' pits still fall, but
	 * some of them now fall onto solid stone instead of onto air, and the body
	 * that takes one down used to end the tick embedded in it — nowhere
	 * {@code unstick} could rescue it from, because the search radius is finite
	 * and the deep floor outside its one carved room is rock in every direction.
	 *
	 * <p>Two lanes settle which kind of "nothing to land on" a pit means: void
	 * all the way down still empties the faller out of the world exactly as
	 * before, and a pit with open floor waiting below still delivers a safe
	 * landing. What must not happen is the third case this world now has that
	 * the old two-level one never could — a pit whose floor is there, but solid.
	 */
	static class APitOntoRockIsBottomlessNotAWall extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(14, 6, 2);
			w.setTile(4, 2, 1, Tile.TileType.TYPE_HOLE); // drops onto open floor below
			w.setTile(9, 2, 1, Tile.TileType.TYPE_HOLE); // drops onto solid rock below
			w.setTile(9, 2, 0, Tile.TileType.TYPE_WALL);
			TestNPC lands = TestNPC.mover(4.5, 1.5, 1, Math.PI / 2); // heads south, onto its hole
			TestNPC blocked = TestNPC.mover(9.5, 1.5, 1, Math.PI / 2);
			w.spawnEntity(lands);
			w.spawnEntity(blocked);
			w.think();
			snapshot(w, "before (one hole over floor, one over rock)");
			tick(w, 60);
			snapshot(w, "after (one lands below; the other is gone, not embedded)");

			assertTrue("open floor below still catches a falling body", !lands.isRemoved());
			assertEquals("and it lands on the level the floor is on", 0, lands.getLvl());
			assertTrue("rock below is as bottomless as no floor at all", blocked.isRemoved());
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
			// Start already starving: with nothing to eat the pegged need erodes
			// health a point at a time (VITALS.md) — energy zero is collapse, and
			// only health decides death, so the decline runs through the health gate.
			TestNPC breeder = TestNPC.breeder(2.5, 2.5, 0, new Genome())
					.withHunger(1.0).withEnergy(0.02);
			w.spawnEntity(breeder);
			w.think();
			snapshot(w, "before (barren ground)");
			tick(w, 5300);
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
			// 3000, not 600: grass became bulk food (a quarter of the old energy per
			// unit, cropped at a quarter of the old rate), so banking a breeding takes
			// roughly sixteen times as long. The fact under test is unchanged -- a fed
			// population still grows -- only the clock it grows on.
			tick(w, 3000);
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
				colony.spawnEntity(TestNPC.mater(p[0], p[1], 0, slowFar).withEnergy(4.4));
				Genome fastNear = new Genome();
				fastNear.markers = new double[] { 0.5, 0.5, 0.5 };
				fastNear.speed = 0.08;
				fastNear.losRange = 4;
				colony.spawnEntity(TestNPC.mater(p[0] + 0.4, p[1], 0, fastNear).withEnergy(4.4));
			}
			colony.think();
			int founders = colony.getAliveCount();
			snapshot(colony, "founders (two compatible gene-types)");

			// Sexual breeders must cluster to pair, so a colony breeds fast and then
			// overgrazes back down -- the lasting proof is that it rose above the
			// founder count at all, and that crossover produced a recombinant.
			// The recombinant check is a ~50/50 gene draw per birth, so the window
			// has to be long enough to see several births or it is a coin toss
			// rather than a test of crossover.
			int peak = founders;
			boolean sawRecombinant = false;
			// 800 steps rather than 80: every birth is food-backed now — a pair
			// re-banks a whole offspring cost from grazing between births — and the
			// recombinant draw needs several births to stop being a coin toss.
			for (int step = 0; step < 800; step++) {
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
	 * The hunger sensor reports a fraction of the body's OWN capacity, not an
	 * absolute constant, so a large body is not pinned at "full". A mind that
	 * throttles by its hunger reading crawls when the tank is half-empty and races
	 * when it is full; a big body at half fill therefore travels far less than the
	 * same body full — which the old fixed-divisor sensor (saturated at ~1 for any
	 * sizeable body) could never express.
	 */
	static class MindSensesHungerByCapacity extends Scenario {
		private double travel(double energyFraction) {
			seed(70);
			World w = room(30, 30); // roomy and centred, so the path never meets a wall
			Genome g = new Genome();
			g.size = 16; // capacity 6 * (16/8) = 12, well above the old /4 divisor
			g.speed = 0.05;
			Mind throttleByHunger = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = s[AgentIO.S_ENERGY]; // pace set by how full I am
					a[AgentIO.A_TURN] = 0; // no steering: distance covered reflects throttle alone
				}
			};
			TestNPC agent = TestNPC.minded(15.5, 15.5, 0, g, throttleByHunger);
			agent.withEnergy(energyFraction * agent.energyCapacity());
			double x0 = agent.getX(), y0 = agent.getY();
			w.spawnEntity(agent);
			w.think();
			tick(w, 120);
			return Math.hypot(agent.getX() - x0, agent.getY() - y0);
		}

		@Override
		public void run() {
			double full = travel(1.0);
			double half = travel(0.5);
			assertGreater("a full body paces itself near top throttle", full, 3.0);
			assertLess("a half-full big body reads ~0.5 hunger and moves at ~half pace, not saturated",
					half, full * 0.7);
		}
	}

	/**
	 * The dedicated prey channel drives hunting. A mind that steers toward
	 * {@code S_PREY_BEARING} and opens the throttle runs down a smaller
	 * creature it senses at full sight range — a target the short facing-gated
	 * nearest-neighbour channel would lose the moment it moved.
	 */
	static class MindHuntsViaPreyChannel extends Scenario {
		@Override
		public void run() {
			seed(71);
			World w = room(24, 9);
			Genome pg = new Genome();
			pg.size = 12;
			pg.speed = 0.05;
			pg.losRange = 14;
			Mind hunt = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_TURN] = s[AgentIO.S_PREY_BEARING]; // steer onto the prey
					a[AgentIO.A_THROTTLE] = 1.0;
					a[AgentIO.A_THROTTLE] = 1.0; // run it down at full speed
				}
			};
			TestNPC pred = TestNPC.minded(4.5, 4.5, 0, pg, hunt);
			TestNPC prey = TestNPC.inert(11.5, 4.5, 0); // size 6 < 12, seven tiles off
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();
			double d0 = Math.hypot(pred.getX() - prey.getX(), pred.getY() - prey.getY());
			tick(w, 250);
			double d1 = Math.hypot(pred.getX() - prey.getX(), pred.getY() - prey.getY());
			assertGreater("the prey started out of arm's reach", d0, 5.0);
			assertLess("the hunter closed on prey sensed through the prey channel", d1, 2.0);
		}
	}

	/**
	 * The dedicated threat channel drives flight. A mind that steers away from
	 * {@code S_THREAT_BEARING} opens the gap from a larger creature — the split
	 * threat sense lets prey react to what could eat it, not merely to whatever is
	 * nearest.
	 */
	static class MindFleesViaThreatChannel extends Scenario {
		@Override
		public void run() {
			seed(72);
			World w = room(30, 9);
			Genome yg = new Genome();
			yg.size = 6;
			yg.speed = 0.05;
			yg.losRange = 14;
			Mind flee = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					double b = s[AgentIO.S_THREAT_BEARING] * Math.PI; // relative bearing to threat
					double away = Math.atan2(Math.sin(b + Math.PI), Math.cos(b + Math.PI));
					a[AgentIO.A_TURN] = Math.max(-1, Math.min(1, away / (Math.PI * 0.5)));
					a[AgentIO.A_THROTTLE] = 1.0;
				}
			};
			TestNPC prey = TestNPC.minded(15.5, 4.5, 0, yg, flee);
			Mind idle = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
				}
			};
			Genome tg = new Genome();
			tg.size = 14; // a big, stationary threat to the prey's west
			TestNPC threat = TestNPC.minded(9.5, 4.5, 0, tg, idle);
			w.spawnEntity(prey);
			w.spawnEntity(threat);
			w.think();
			double d0 = Math.hypot(prey.getX() - threat.getX(), prey.getY() - threat.getY());
			tick(w, 150);
			double d1 = Math.hypot(prey.getX() - threat.getX(), prey.getY() - threat.getY());
			assertGreater("the prey opened the gap from a threat sensed through the threat channel",
					d1, d0 + 2.0);
		}
	}

	/**
	 * Throttle is the speed control, and speed is charged as kinetic energy. There
	 * is no sprint gear to engage: a mind simply asks for the pace it wants, and
	 * because movement costs {@code mass * v^2} the bill rises with the SQUARE of
	 * that choice. Runs one body at two throttle settings and pins both halves —
	 * more throttle covers more ground, and it costs superlinearly more to do it,
	 * which is what makes going fast a decision rather than a free upgrade.
	 */
	static class ThrottleSetsSpeedAndCostsItsSquare extends Scenario {
		private double[] run(double throttle) {
			seed(73);
			World w = room(50, 9);
			Genome g = new Genome();
			g.size = 10;
			g.speed = 0.05;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = throttle;
					a[AgentIO.A_TURN] = 0;
				}
			};
			TestNPC body = TestNPC.minded(3.5, 4.5, 0, g, ctrl).withMetabolic().withEnergy(5.0);
			double x0 = body.getX(), e0 = body.getEnergy();
			w.spawnEntity(body);
			w.think();
			tick(w, 100);
			return new double[] { body.getX() - x0, e0 - body.getEnergy() };
		}

		@Override
		public void run() {
			double[] fast = run(1.0);
			double[] half = run(0.5);
			assertGreater("more throttle covers more ground", fast[0], half[0] + 1.0);

			// Resting metabolism is in both bills, so compare the MOVEMENT component.
			// Doubling speed quadruples the per-tick movement cost, so over the same
			// window the moving part should be ~4x, not 2x.
			double[] still = run(0.0);
			double moveFast = fast[1] - still[1];
			double moveHalf = half[1] - still[1];
			assertGreater("moving at all costs energy", moveHalf, 0.0);
			assertGreater("doubling speed costs far more than double (v^2, not v)",
					moveFast, moveHalf * 3.0);
		}
	}

	/**
	 * Intent steering: a mind that names a <i>kind of thing</i> gets there without
	 * ever writing a turn. The body scores the ground it can see, picks a patch, and
	 * supplies the heading; the mind spends one instruction saying what it wants.
	 * The sign is the whole of approach-versus-avoid, so the same policy that hunts
	 * a patch flees one by flipping a constant.
	 *
	 * <p>Both bodies start facing directly <i>away</i> from the only grass in the
	 * room and never write A_TURN, so anything that arrives did so because the body
	 * steered it.
	 */

	/**
	 * A mind holds only as many targets at once as its brain has room for, and the
	 * room comes from the brain's own length. Both bodies here stand in the same
	 * spot with the same things around them; the one running a longer program simply
	 * perceives more of them. That is what makes a big brain worth its slower
	 * reflexes — and what makes a small one single-minded.
	 */

	/**
	 * An intent reports back. A mind can finally tell whether what it wanted
	 * happened, instead of inferring it from its tank drifting upward several
	 * thought-cycles late. Three of the four states are pinned here; the fourth,
	 * idle, is simply what a mind that names nothing reads.
	 *
	 * <p>Note what is NOT pinned: a "failed" state. A latched intent never finishes
	 * losing — it is either still closing (pending) or its guards stopped holding
	 * (invalid) — so how long to persist is left for a lineage to evolve rather than
	 * for the body to decide. See AgentIO.S_INTENT.
	 */

	/**
	 * Mating is a behaviour, not a collision. A mind that wants to breed walks to a
	 * partner and holds station with it while the exchange happens; a child follows
	 * a few seconds later. Before this, A_MATE only fired if a partner happened
	 * already to be in reach, so breeding was something creatures blundered into.
	 *
	 * <p>Pins the three things that make it an intent: the approach happens without
	 * any steering from the mind, the exchange takes real time rather than
	 * completing on contact, and the pair are stationary while it runs.
	 */

	/**
	 * Tile-seeking is generic: A_TILE names a <i>property</i> and the same channel
	 * that finds grass finds whatever was asked for instead. Which property is worth
	 * wanting is left to the mind — hiding in cover and heading for water are the
	 * same instruction with a different constant.
	 *
	 * <p>Properties rather than tile types on purpose: types are many and growing,
	 * their indices are not pool-aligned, and new terrain would need new bands. These
	 * six questions every tile already answers, and new ground inherits them free.
	 */

	/**
	 * Cover is a refuge from a HUNTER, not merely from the generic chaser
	 * {@link CoverHidesFromPerception} pins. A predator picks its quarry through
	 * nearestPrey, which is a different code path with its own line-of-sight gate,
	 * and that gate is the whole reason asking for cover (A_TILE) is worth an
	 * instruction: without it, hiding would be theatre.
	 */

	/**
	 * A hunter with a full tank does not kill. The meal would overflow the cap and be
	 * discarded, so the prey would die for nothing.
	 *
	 * <p>Pins the boundary rather than the intent, because the first version of this
	 * gate tested {@code energy >= capacity} and could never fire: run_extended caps
	 * the tank and then burns metabolism, both before think(), so a metabolic body is
	 * never exactly at its cap when it decides anything. A hungry hunter beside the
	 * same prey must still kill, or this would pass by the hunter simply being broken.
	 */

	/**
	 * A big animal leaves a big body. Corpse lifetime scales with the mass the
	 * creature would have grown into, so a kill stays available to scavengers in
	 * proportion to how much of it there is — the same principle as the meal being
	 * worth what it weighs.
	 */
	static class ACorpseLingersByItsMass extends Scenario {
		/** Ticks from death until the body is cleared, for a genome of this size. */
		private int corpseTicks(double size) {
			seed(104);
			World w = room(10, 10);
			Genome g = new Genome();
			g.size = size;
			TestNPC t = TestNPC.mindedForager(5.5, 5.5, 0, g);
			w.spawnEntity(t);
			w.think();
			t.damage(1000); // killed outright
			for (int i = 1; i <= 2000; i++) {
				tick(w, 1);
				if (t.isRemoved()) {
					return i;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int small = corpseTicks(6);
			int large = corpseTicks(20);
			assertGreater("a small body clears at all", small, 0);
			assertGreater("a bigger body lies there longer", large, small * 2);
		}
	}

	static class AFullHunterDoesNotKill extends Scenario {
		private int preyHealthAfter(double hungerLevel) {
			seed(103);
			World w = room(12, 9);
			Genome pg = new Genome();
			pg.size = 20;
			TestNPC hunter = TestNPC.predator(5.5, 4.5, 0, pg).withMetabolic()
					.withReproCooldown(1000000);
			TestNPC prey = TestNPC.inert(5.9, 4.5, 0).withSize(6); // already in reach
			w.spawnEntity(hunter);
			w.spawnEntity(prey);
			w.think();
			for (int i = 0; i < 40; i++) {
				hunter.withHunger(hungerLevel); // hold the appetite there
				tick(w, 1);
			}
			return prey.getHealth();
		}

		@Override
		public void run() {
			assertEquals("a full hunter leaves prey in reach alone", 100, preyHealthAfter(0.0));
			assertLess("a hungry hunter beside the same prey still kills", preyHealthAfter(0.7), 100);
		}
	}

	static class CoverHidesPreyFromAHunter extends Scenario {
		@Override
		public void run() {
			seed(102);
			World w = room(14, 9);
			for (int y = 3; y <= 5; y++) {
				w.setTile(11, y, 0, Tile.TileType.TYPE_COVER); // a thicket to the east
			}
			Genome pg = new Genome();
			pg.size = 20;
			TestNPC hunter = TestNPC.predator(3.5, 4.5, 0, pg).withMetabolic().withHunger(0.8);
			TestNPC inCover = TestNPC.inert(11.5, 4.5, 0).withSize(6);
			TestNPC inOpen = TestNPC.inert(7.5, 7.5, 0).withSize(6);
			w.spawnEntity(hunter);
			w.spawnEntity(inCover);
			w.spawnEntity(inOpen);
			w.think();
			snapshot(w, "before (prey in the open, prey in the thicket)");
			tick(w, 600);
			snapshot(w, "after (the hunter took the one it could see)");

			assertLess("the hunter is hurt or dead in the open", inOpen.getHealth(), 100);
			assertEquals("the prey in cover was never touched", 100, inCover.getHealth());
		}
	}

	static class TileSeekingIsGeneric extends Scenario {
		/** Does a body asking for this property walk onto ground that has it? */
		private boolean reaches(double want, Tile.TileType kind) {
			seed(101);
			World w = room(24, 12);
			for (int x = 0; x < 24; x++) {
				for (int y = 0; y < 12; y++) {
					w.getTile(x, y, 0).setFertility(0); // no grass to distract the scan
				}
			}
			for (int y = 5; y <= 7; y++) {
				w.setTile(19, y, 0, kind); // the only ground of that kind, well east
			}
			Genome g = new Genome();
			g.size = 8;
			g.losRange = 22;
			TestNPC body = TestNPC.minded(5.5, 6.5, 0, g, (sn, a) -> {
				a[AgentIO.A_TILE] = want; // what kind of ground
				a[AgentIO.A_SEEK] = 0.1; // ...go to it
				a[AgentIO.A_THROTTLE] = 0.8;
			}).withHeading(Math.PI); // starts facing away
			w.spawnEntity(body);
			w.think();
			tick(w, 400);
			return body.getX() > 16.0;
		}

		@Override
		public void run() {
			assertTrue("a body that asks for cover walks to the thicket",
					reaches(0.25, Tile.TileType.TYPE_COVER));
			assertTrue("the same machinery finds water when that is what was asked for",
					reaches(1.0, Tile.TileType.TYPE_WATER));
			// And the property genuinely selects: asking for cover does not walk a
			// creature onto water, so the constant is doing the work.
			assertTrue("asking for cover ignores water", !reaches(0.25, Tile.TileType.TYPE_WATER));
		}
	}

	static class MatingTakesTimeAndSeeksAPartner extends Scenario {
		@Override
		public void run() {
			seed(99);
			World w = room(20, 20);
			// Two compatible partners, well apart, both willing and nothing else said.
			// Willing, and walking: speed stays with the mind for mating exactly as it
			// does for seeking, so an intent alone steers but does not travel.
			Mind willing = (sn, a) -> {
				a[AgentIO.A_MATE] = 1;
				a[AgentIO.A_THROTTLE] = 0.6;
			};
			TestNPC a = mater(w, 5.5, 10.5, willing);
			TestNPC b = mater(w, 13.5, 10.5, willing);
			w.think();
			int start = w.getAliveCount();
			double gap0 = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());

			tick(w, 90); // long enough to close an 8-tile gap, not to finish courting
			double gap1 = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
			assertLess("they sought each other out with no steering of their own",
					gap1, gap0 - 3.0);
			assertTrue("the exchange is under way", a.isMating() || b.isMating());
			assertEquals("...and no child yet: mating is not instant", start,
					w.getAliveCount());

			tick(w, 200); // now let it run to term
            assertGreater("a child arrives once the pair have held station",
					w.getAliveCount(), start);
		}

		private TestNPC mater(World w, double x, double y, Mind m) {
			Genome g = new Genome();
			g.size = 8;
			g.markers = new double[] { 0.5, 0.5, 0.5 };
			g.mateThreshold = 0.1; // compatible with each other
			TestNPC t = TestNPC.minded(x, y, 0, g, m).withMetabolic().withSpeed(0.08);
			t.withEnergy(t.energyCapacity());
			w.spawnEntity(t);
			return t;
		}
	}

	static class IntentReportsHowItWent extends Scenario {
		private double statusAfter(boolean grassInWorld, int ticks) {
			seed(98);
			World w = room(16, 16);
			if (!grassInWorld) {
				for (int x = 0; x < 16; x++) {
					for (int y = 0; y < 16; y++) {
						w.getTile(x, y, 0).setFertility(0); // nothing to forage anywhere
					}
				}
			}
			Genome g = new Genome();
			g.size = 8;
			TestNPC body = TestNPC.minded(8.5, 8.5, 0, g, (sn, a) -> {
				a[AgentIO.A_SEEK] = 0.1; // forage
				a[AgentIO.A_THROTTLE] = 0.3;
			}).withHunger(0.5); // an appetite, so arriving on grass actually eats
			w.spawnEntity(body);
			tick(w, ticks);
			return body.intentStatus();
		}

		@Override
		public void run() {
			assertNear("seeking food where there is none reports invalid",
					AgentIO.INTENT_INVALID, statusAfter(false, 5), 1e-9);
			assertNear("standing on grass and grazing it reports done",
					AgentIO.INTENT_DONE, statusAfter(true, 5), 1e-9);

			// And idle when the mind names nothing at all.
			seed(98);
			World w = room(10, 10);
			Genome g = new Genome();
			g.size = 8;
			TestNPC idle = TestNPC.minded(5.5, 5.5, 0, g, (sn, a) -> {
			});
			w.spawnEntity(idle);
			tick(w, 5);
			assertNear("a mind that wants nothing reports idle",
					AgentIO.INTENT_IDLE, idle.intentStatus(), 1e-9);
		}
	}

	static class BrainSizeSetsHowMuchAMindTracks extends Scenario {
		/** How many tracked channels come back non-zero for a brain of this length. */
		private int channelsSeen(int brainLen) {
			seed(97);
			World w = room(20, 20);
			Genome g = new Genome();
			g.size = 8;
			g.losRange = 14;
			int[][] code = new int[brainLen][];
			for (int i = 0; i < brainLen; i++) {
				code[i] = new int[] { Brain.NOP, 0, 0, 0 }; // length is all that matters here
			}
			g.brain = new Brain(code);
			TestNPC body = TestNPC.minded(10.5, 10.5, 0, g, (sn, a) -> {
			});
			w.spawnEntity(body);
			// Something in every tracked channel, all within sight: grass underfoot,
			// a smaller creature, a bigger one, and an item.
			w.spawnEntity(TestNPC.inert(13.5, 10.5, 0).withSize(3));
			w.spawnEntity(TestNPC.inert(10.5, 14.5, 0).withSize(19));
			w.spawnEntity(net.hedinger.prototype.entities.Item.food(7.5, 10.5, 0));
			tick(w, 2);
			double[] s = body.sensorSnapshot();
			int seen = 0;
			for (int prox : new int[] { AgentIO.S_FORAGE_PROX, AgentIO.S_PREY_PROX,
					AgentIO.S_THREAT_PROX, AgentIO.S_ITEM_PROX }) {
				if (s[prox] > 0) {
					seen++;
				}
			}
			return seen;
		}

		@Override
		public void run() {
			int small = channelsSeen(4);   // 1 slot
			int large = channelsSeen(48);  // capped at the maximum
			assertEquals("a tiny brain keeps track of exactly one thing", 1, small);
			assertGreater("a large brain holds several at once", large, small);
		}
	}

	static class SeekWalksToAPatchWithoutSteering extends Scenario {
		private static final int PATCH_X = 32, PATCH_Y = 7;

		/** Distance from the patch after walking with this A_SEEK value. */
		private double distanceAfter(double seek) {
			seed(90);
			World w = room(40, 14);
			for (int x = 0; x < 40; x++) {
				for (int y = 0; y < 14; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren everywhere...
				}
			}
			for (int x = PATCH_X - 1; x <= PATCH_X + 1; x++) {
				for (int y = PATCH_Y - 1; y <= PATCH_Y + 1; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // ...except one lush patch
				}
			}
			Genome g = new Genome();
			g.size = 6;
			g.losRange = 20; // far enough to see the patch from the start
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1;
					a[AgentIO.A_SEEK] = seek; // the only steering it ever expresses
				}
			};
			// Facing west, i.e. away from the patch: a body that ignores seek leaves.
			TestNPC body = TestNPC.minded(20.5, 7.5, 0, g, ctrl).withSpeed(0.1)
					.withHeading(Math.PI * 0.9);
			w.spawnEntity(body);
			w.think();
			tick(w, 150);
			return Math.hypot(body.getX() - (PATCH_X + 0.5), body.getY() - (PATCH_Y + 0.5));
		}

		@Override
		public void run() {
			double start = Math.hypot(20.5 - (PATCH_X + 0.5), 7.5 - (PATCH_Y + 0.5));
			double toward = distanceAfter(0.1);
			double away = distanceAfter(-0.1);
			assertLess("seeking the forage patch walks the body onto it", toward, 2.0);
			assertGreater("the same command with a minus sign walks it off", away, start);
		}
	}

	/**
	 * An intent carries through to the act. This mind writes A_SEEK for the goal and
	 * a throttle for the effort — no turn, no eat, no aiming — and the body finds
	 * grass, walks to it and grazes it. That is the point of an intent under
	 * one-instruction-per-tick: foraging costs a goal slot instead of a steering
	 * loop plus an eat gate, which is what makes the trade against reaction time
	 * worth taking. Speed stays deliberately outside the bargain, since movement
	 * costs the square of it and that is where a lineage spends or saves its living.
	 *
	 * <p>The control is the same mind with the sign flipped. Avoidance must move the
	 * body but never feed it, because running from a thing is not a reason to eat it
	 * — and without that check "seek" could quietly feed a creature that was fleeing.
	 */
	static class OneIntentIsAWholeBehaviour extends Scenario {
		/** {@code {food swallowed, distance to the patch}} after walking on this intent. */
		private double[] run(double seek) {
			seed(93);
			World w = room(40, 14);
			for (int x = 0; x < 40; x++) {
				for (int y = 0; y < 14; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren...
				}
			}
			for (int x = 24; x <= 28; x++) {
				for (int y = 5; y <= 9; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // ...but for one lush patch
				}
			}
			Genome g = new Genome();
			g.size = 8;
			g.speed = 0.08; // the intent's forage pace is a deliberate amble; give it legs
			g.losRange = 20;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_SEEK] = seek; // where to go, and what to do there
					a[AgentIO.A_THROTTLE] = 1; // how hard: still the mind's to choose
				}
			};
			TestNPC body = TestNPC.minded(20.5, 7.5, 0, g, ctrl).withMetabolic()
					.withHunger(1.0) // an empty stomach, so grazing shows up
					.withReproCooldown(1000000).withHeading(Math.PI * 0.9); // facing away
			w.spawnEntity(body);
			w.think();
			double s0 = body.totalSwallowed();
			tick(w, 900);
			return new double[] { body.totalSwallowed() - s0,
					Math.hypot(body.getX() - 26.5, body.getY() - 7.5) };
		}

		@Override
		public void run() {
			double[] toward = run(0.1);
			double[] away = run(-0.1);
			assertLess("a lone forage intent walked the body to the patch", toward[1], 3.0);
			assertGreater("...and grazed it, with no A_EAT ever written", toward[0], 0.0);
			assertGreater("the avoidance sense still moved the body", away[1], 8.0);
			assertNear("...but fed it nothing at all", 0.0, Math.max(0, away[0]), 1e-9);
		}
	}

	/**
	 * The same coupling on the hunting side: a mind that names prey and asks for
	 * speed runs its quarry down and bites it, with no A_ATTACK and no steering of
	 * its own. Pins that the act travels with the goal, not just the heading.
	 */
	static class HuntIntentClosesAndBites extends Scenario {
		@Override
		public void run() {
			seed(94);
			World w = room(30, 12);
			Genome g = new Genome();
			g.size = 16;
			g.losRange = 20;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_SEEK] = 0.5; // hunt: approach and bite, from one slot
					a[AgentIO.A_THROTTLE] = 1;
				}
			};
			TestNPC hunter = TestNPC.minded(5.5, 6.0, 0, g, ctrl).withMetabolic()
					.withReproCooldown(1000000).withHeading(Math.PI * 0.9);
			TestNPC quarry = TestNPC.inert(20.5, 6.0, 0).withSize(6);
			w.spawnEntity(hunter);
			w.spawnEntity(quarry);
			w.think();
			int hp0 = quarry.getHealth();
			// Closest approach over the run, not the final gap: the hunt succeeds, and
			// a hunter with nothing left to chase goes looking for the next one, so by
			// tick 900 it has wandered off the corpse it made.
			double closest = Double.MAX_VALUE;
			for (int i = 0; i < 900; i++) {
				tick(w, 1);
				closest = Math.min(closest, Math.hypot(hunter.getX() - quarry.getX(),
						hunter.getY() - quarry.getY()));
			}
			assertLess("a lone hunt intent closed the distance", closest, 2.0);
			assertLess("...and bit, with no A_ATTACK ever written", quarry.getHealth(), hp0);
		}
	}

	/**
	 * Spatial memory in two instructions. A_MARK latches where the body is standing;
	 * A_SEEK = ±4 steers back to it later. The coordinate lives in the body on
	 * purpose — a mind could hold two numbers in registers but could never turn them
	 * into a heading, since the instruction set has no divide and no atan2.
	 *
	 * <p>The return leg also jams A_TURN hard the wrong way, which is what pins the
	 * precedence: while a seek has something to steer by, it is the steering.
	 */
	static class WaypointRemembersAPlace extends Scenario {
		@Override
		public void run() {
			seed(91);
			World w = room(30, 14);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				private int t = 0;

				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1;
					a[AgentIO.A_MARK] = t == 0 ? 1.0 : 0.0; // remember here, once
					if (t < 120) {
						a[AgentIO.A_SEEK] = 0; // walk straight off
						a[AgentIO.A_TURN] = 0;
					} else {
						a[AgentIO.A_SEEK] = 4; // go back...
						a[AgentIO.A_TURN] = 1; // ...against a hard contrary turn
					}
					t++;
				}
			};
			TestNPC body = TestNPC.minded(6.5, 7.5, 0, g, ctrl).withSpeed(0.08).withHeading(0);
			w.spawnEntity(body);
			w.think();
			tick(w, 120);
			double away = Math.hypot(body.getX() - 6.5, body.getY() - 7.5);
			assertGreater("the body walked away from the place it marked", away, 5.0);
			tick(w, 220);
			double back = Math.hypot(body.getX() - 6.5, body.getY() - 7.5);
			assertLess("seeking the waypoint brought it home", back, 2.0);
			assertLess("...and home is nearer than where it had wandered", back, away);
		}
	}

	/**
	 * Wanting something you cannot see is a reason to go looking. A goal the body
	 * cannot currently find puts it into a deterministic search — drifting on the
	 * same oscillator the mind reads as S_CLOCK — rather than leaving it planted.
	 * That is what makes an intent a complete behaviour: "forage" has to mean find
	 * food, not merely walk at food already in view.
	 *
	 * <p>It also means a mutation that writes a seek constant can never freeze a
	 * creature pointing at a thing that isn't there, which is the survival floor the
	 * old pure-steering version guaranteed a different way (by doing nothing at all).
	 * Here the waypoint is never marked, so A_SEEK = 4 names something unfindable.
	 */
	static class SeekYieldsWhenItNamesNothing extends Scenario {
		@Override
		public void run() {
			seed(92);
			World w = room(30, 14);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1;
					a[AgentIO.A_SEEK] = 4; // the waypoint... which was never marked
					a[AgentIO.A_TURN] = 0;
				}
			};
			TestNPC body = TestNPC.minded(6.5, 7.5, 0, g, ctrl).withSpeed(0.08).withHeading(0);
			w.spawnEntity(body);
			w.think();
			double x0 = body.getX(), y0 = body.getY();
			tick(w, 300);
			double travelled = Math.hypot(body.getX() - x0, body.getY() - y0);
			assertGreater("an unfindable goal sends the body searching, not nowhere", travelled, 3.0);
			assertTrue("...and the search steers it off the straight line it started on",
					Math.abs(body.getY() - y0) > 0.5);
		}
	}

	/**
	 * A mind changes level by walking, not by wishing. A ramp is floor that spans
	 * two levels, so a minded body that only ever asks for throttle crosses one and
	 * comes out on the other side a level away — up an east-climbing RAMPUP, down a
	 * west-descending RAMPDOWN. Both bodies here jam A_VERTICAL hard the WRONG way
	 * throughout, which is the point: the actuator is retired and inert, and the
	 * terrain is what moves them. Guards against reintroducing a vertical intent
	 * that a random policy would have to get right before it could use a ramp.
	 */
	/**
	 * A ramp runs whichever way it was laid. The same pair built facing north
	 * is climbed by walking north and descended by walking south — and walking
	 * the old east-west way off it now finds a cliff, because the slope is not
	 * there any more.
	 *
	 * <p>The last part is what makes this a test rather than a demonstration.
	 * A ramp that walks in its own direction *and* still walks the hardcoded
	 * one would pass every "can it climb north" check while leaving the old
	 * rule quietly in place, and the bug would only surface as a body strolling
	 * up a cliff face somewhere else.
	 */
	/**
	 * An up ramp climbs INTO the rock, and the rock knows it. The wall at the
	 * head of the cut counts the ramp among its own mass, so it draws no edge
	 * across the join — no rounded corner, no silhouette rim — and the slope
	 * reads as cut into the cliff rather than parked against it.
	 *
	 * <p>Checked on the tilecode rather than on pixels. The bake lays film grain
	 * over everything, so an image diff of two renders differs on roughly a
	 * third of every tile in the map whether or not anything real changed;
	 * the connectivity string is the thing the wall art is actually chosen by.
	 *
	 * <p>It has to hold on the FLANKS as well as at the head, and it has to run
	 * both ways. Rock and ramp each used to draw a boundary at the join — the
	 * wall a rim and a rounded corner, the ramp one of its own triangular side
	 * walls against rock already standing there — so the seam was doubled and
	 * the slope read as a bright block dropped into a socket. Both sides have
	 * to agree they are one mass for the join to disappear.
	 */
	static class RockOwnsTheRampCutIntoIt extends Scenario {
		/** The tilecode digit for the neighbour at {@code (dx, dy)}: the
		 *  3x3 keypad 123/405/678 the autotiler is written in. */
		private static char digit(int dx, int dy) {
			int[] pad = { 1, 2, 3, 4, 0, 5, 6, 7, 8 };
			return (char) ('0' + pad[(dy + 1) * 3 + (dx + 1)]);
		}

		@Override
		public void run() {
			seed(44);
			World w = room(12, 12, 2);
			// A ramp at (5,5) climbing north into the wall at (5,4), flanked by
			// rock at (4,5) — the geometry a link station actually builds.
			w.setTile(5, 5, 0, Tile.TileType.TYPE_RAMPUP);
			w.getTile(5, 5, 0).setRampUphill(Tile.DIR_N);
			w.setTile(5, 4, 0, Tile.TileType.TYPE_WALL);
			w.setTile(4, 5, 0, Tile.TileType.TYPE_WALL);
			w.alignTiles();

			// From the head-wall at (5,4) the ramp lies south: digit 7.
			String head = w.getTile(5, 4, 0).getTileCode();
			assertTrue("the rock at the head of the cut counts the ramp as its own"
					+ " (code " + head + ")", head.indexOf(digit(0, 1)) >= 0);

			// From the flanking wall at (4,5) the ramp lies east: digit 5. This
			// is the one that was drawing the visible seam down the ramp's side.
			String flank = w.getTile(4, 5, 0).getTileCode();
			assertTrue("the rock along the side of the cut does too"
					+ " (code " + flank + ")", flank.indexOf(digit(1, 0)) >= 0);

			// And the ramp agrees, so it raises no side wall against rock that
			// is already standing there: from the ramp the flank lies west.
			String ramp = w.getTile(5, 5, 0).getTileCode();
			assertTrue("and the ramp counts the rock as its own side"
					+ " (code " + ramp + ")", ramp.indexOf(digit(-1, 0)) >= 0);
		}
	}

	static class RampsRunWhicheverWayTheyAreLaid extends Scenario {
		/** Walks a throttle-only mind from {@code (x,y,z)} along {@code heading}
		 *  over a ramp pair whose high side faces {@code uphill}. */
		private int levelAfterWalking(double x, double y, int z, double heading, int uphill) {
			seed(81);
			World w = room(10, 10, 2);
			w.setTile(5, 5, 0, Tile.TileType.TYPE_RAMPUP);
			w.setTile(5, 5, 1, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(5, 5, 0).setRampUphill(uphill);
			w.getTile(5, 5, 1).setRampUphill(uphill);
			Genome g = new Genome();
			g.size = 6;
			Mind walk = (s, a) -> a[AgentIO.A_THROTTLE] = 1;
			TestNPC body = TestNPC.minded(x, y, z, g, walk).withSpeed(0.05).withHeading(heading);
			w.spawnEntity(body);
			w.think();
			tick(w, 200);
			return body.getLvl();
		}

		@Override
		public void run() {
			// Screen-north is -y, which is heading -PI/2; south is +PI/2.
			assertEquals("a north-facing ramp is climbed by walking north",
					1, levelAfterWalking(5.5, 8.5, 0, -Math.PI / 2, Tile.DIR_N));
			assertEquals("and descended by walking south off its foot",
					0, levelAfterWalking(5.5, 2.5, 1, Math.PI / 2, Tile.DIR_N));
			// The old hardcoded axis must be gone, not merely joined.
			assertEquals("walking east off a north-facing ramp finds a cliff, not a climb",
					0, levelAfterWalking(2.5, 5.5, 0, 0.0, Tile.DIR_N));
			// And a west-facing pair mirrors the original convention.
			assertEquals("a west-facing ramp is climbed by walking west",
					1, levelAfterWalking(8.5, 5.5, 0, Math.PI, Tile.DIR_W));
		}
	}

	/**
	 * A floor is opaque, and a ramp is the hole in it.
	 *
	 * <p>{@code hasLOS} used to trace within the SOURCE level's plane and ignore
	 * the target's level altogether, so a body one storey away was answered as
	 * though it were standing in the same room. Nothing in the world consulted
	 * the floor between them. That is what let the steward's drone shoot an
	 * animal through the deck plate — the strike asks for sight, and sight said
	 * yes.
	 *
	 * <p>The exceptions are the openings: a slope joining two floors is a gap in
	 * the ceiling, and a pit is a gap that is nothing but gap.
	 *
	 * <p>Pits were left out when this first landed, which put the simulation at
	 * odds with its own renderer — the chunk bake punches real transparency
	 * through a pit and the client draws the floor below through it, so the
	 * world was showing what it would not admit to seeing.
	 *
	 * <p>Movement looked like it depended on the exception too, since {@code
	 * isValidMoveDestination} asks for sight of the tile a body is stepping onto
	 * and a staircase is a destination on another level. It does not: a body's
	 * level turns over as it crosses the ramp's edge, so the destination it asks
	 * about is still on its own floor when it asks. That was checked by
	 * disabling the exception and watching {@code
	 * RampsRunWhicheverWayTheyAreLaid} keep passing — worth recording, because
	 * the comment that says otherwise is the obvious one to write.
	 */
	static class AFloorBlocksSightUnlessAnOpeningIsNear extends Scenario {
		@Override
		public void run() {
			seed(520);
			World w = room(30, 12, 3);
			// One ramp pair at the west end, and nothing but open floor east of
			// it — so the same two levels are joined near x=4 and not near x=25.
			w.setTile(4, 6, 0, Tile.TileType.TYPE_RAMPUP);
			w.setTile(4, 6, 1, Tile.TileType.TYPE_RAMPDOWN);
			w.getTile(4, 6, 0).setRampUphill(Tile.DIR_N);
			w.getTile(4, 6, 1).setRampUphill(Tile.DIR_N);
			w.alignTiles();

			final double range = 5, all = Math.PI;

			// Same floor: untouched by any of this.
			assertTrue("along a floor, sight is what it always was",
					w.hasLOS(25.5, 6.5, 1, 0, 27.5, 6.5, 1, range, all));

			// Across a floor, far from the stairwell: nothing.
			assertTrue("through a floor, a body underfoot is not in view",
					!w.hasLOS(25.5, 6.5, 1, 0, 25.5, 6.5, 0, range, all));
			assertTrue("nor one overhead",
					!w.hasLOS(25.5, 6.5, 0, 0, 25.5, 6.5, 1, range, all));

			// Beside the stairwell: the floor is open there, so sight passes.
			assertTrue("beside a ramp, the floor is open and sight passes down",
					w.hasLOS(5.5, 6.5, 1, 0, 5.5, 6.5, 0, range, all));
			assertTrue("and up",
					w.hasLOS(5.5, 6.5, 0, 0, 5.5, 6.5, 1, range, all));

			// The radius is the sighting's own. A ramp twenty tiles west is not
			// a hole in the ceiling above your head.
			assertTrue("a ramp outside the radius is not an opening",
					!w.hasLOS(20.5, 6.5, 1, 0, 20.5, 6.5, 0, range, all));

			// Two floors is two floors. The ramp at (4,6) joins 0 and 1 and
			// nothing joins 0 to 2, so standing on it buys nothing.
			assertTrue("two storeys apart is never in view, ramp or no ramp",
					!w.hasLOS(4.5, 6.5, 2, 0, 4.5, 6.5, 0, range, all));

			// A pit is the other opening, and it is cut in the UPPER deck.
			w.setTile(24, 6, 1, Tile.TileType.TYPE_HOLE);
			assertTrue("beside a pit, sight passes down through it",
					w.hasLOS(25.5, 6.5, 1, 0, 25.5, 6.5, 0, range, all));
			assertTrue("and up through it from underneath",
					w.hasLOS(25.5, 6.5, 0, 0, 25.5, 6.5, 1, range, all));
			// The radius applies to a pit as it does to a ramp. From x=10.5 the
			// pit at x=24 is fourteen tiles off and the ramp at x=4 is six, both
			// well outside a range of five, so there is nothing overhead but deck.
			assertTrue("a pit outside the radius is not an opening either",
					!w.hasLOS(10.5, 6.5, 0, 0, 10.5, 6.5, 1, range, all));
			// A drop shaft is a pit with a hazard stripe round it.
			w.setTile(24, 6, 1, Tile.TileType.TYPE_SHAFT);
			assertTrue("a drop shaft opens the floor the same way",
					w.hasLOS(25.5, 6.5, 1, 0, 25.5, 6.5, 0, range, all));
			// And a pit in the floor BELOW does not open the floor above it:
			// the gap has to be in the deck between them.
			w.setTile(24, 6, 1, Tile.TileType.TYPE_PLATE);
			w.setTile(24, 6, 0, Tile.TileType.TYPE_HOLE);
			assertTrue("a pit in the lower deck is not a way through the upper one",
					!w.hasLOS(25.5, 6.5, 1, 0, 25.5, 6.5, 0, range, all));
		}
	}

	static class MindsChangeLevelByWalkingRamps extends Scenario {
		/** Walks a throttle-only mind from {@code (x,y,z)} along {@code heading} and
		 *  reports the level it ends on. */
		private int levelAfterWalking(double x, double y, int z, double heading,
				double contraryVerticalIntent) {
			seed(80);
			World w = room(10, 6, 2);
			// A ramp pair on row 2: climb east off (5,2,0), descend west off (5,2,1).
			w.setTile(5, 2, 0, Tile.TileType.TYPE_RAMPUP);
			w.setTile(5, 2, 1, Tile.TileType.TYPE_RAMPDOWN);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1; // walk, and nothing else
					a[AgentIO.A_VERTICAL] = contraryVerticalIntent; // inert: ignored
				}
			};
			TestNPC body = TestNPC.minded(x, y, z, g, ctrl).withSpeed(0.05).withHeading(heading);
			w.spawnEntity(body);
			w.think();
			tick(w, 200);
			return body.getLvl();
		}

		@Override
		public void run() {
			assertEquals("a mind that only walks east climbs the ramp to the level above",
					1, levelAfterWalking(2.5, 2.5, 0, 0.0, 1.0));
			assertEquals("a mind that only walks west descends the ramp to the level below",
					0, levelAfterWalking(8.5, 2.5, 1, Math.PI, 1.0));
		}
	}

	/**
	 * The body's anti-freeze reflex carries to minded bodies: a degenerate policy
	 * that drives flat into a wall cannot pin the creature there forever. The body
	 * detects the terrain jam and drives it clear for a spell, so it keeps moving
	 * instead of standing frozen — the survival floor that lets fully-random brains
	 * live long enough to evolve.
	 */
	static class MindedBodyUnsticksFromWallJam extends Scenario {
		@Override
		public void run() {
			seed(81);
			World w = room(9, 9);
			Genome g = new Genome();
			g.size = 6;
			g.speed = 0.08;
			Mind ram = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 1.0; // full ahead, never steering
					a[AgentIO.A_TURN] = 0;
				}
			};
			TestNPC body = TestNPC.minded(4.5, 4.5, 0, g, ram); // heading east, into the wall
			w.spawnEntity(body);
			w.think();
			// Track the deepest it drives into the wall, then how far it retreats
			// afterwards: a jammed body stays pressed at maxX (no retreat); one the
			// body unsticks is driven back off the wall before it charges again.
			double maxX = body.getX(), minXafter = body.getX();
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				if (body.getX() > maxX) {
					maxX = body.getX();
					minXafter = maxX;
				} else {
					minXafter = Math.min(minXafter, body.getX());
				}
			}
			assertGreater("a minded body jammed into a wall is driven back off it (unstick reflex)",
					maxX - minXafter, 1.5);
		}
	}

	/**
	 * The demo world seeds a small cohort of minded creatures alongside the scripted
	 * species, and the steward keeps it from dying out: fully-random brains rarely
	 * feed themselves, so without the reseed the lineage would starve to nothing and
	 * the A/B seam with it. Pins that the cohort is present, is marked minded (apart
	 * from the hardcoded creatures), stays alive over time, and does not crowd out
	 * the scripted ecosystem it runs beside.
	 */
	static class MindedCohortSustainedBySteward extends Scenario {
		private int countMinded(World w) {
			int c = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved() && t.isMinded()) {
					c++;
				}
			}
			return c;
		}

		private int countRole(World w, String role) {
			int c = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
						&& !t.isMinded() && t.ecoRole().equals(role)) {
					c++;
				}
			}
			return c;
		}

		@Override
		public void run() {
			World w = net.hedinger.prototype.sim.Worlds.demo(7);
			assertGreater("the demo world seeds a minded cohort", countMinded(w), 0);
			// Long enough for the random-brained founders to starve and be reseeded.
			tick(w, 8000);
			assertGreater("the steward keeps the minded cohort from dying out", countMinded(w), 0);
			assertGreater("the scripted herbivores persist alongside the minded cohort",
					countRole(w, "herbivore"), 0);
		}
	}

	/**
	 * Survivor-seeding with longevity as fitness: when the steward must top the
	 * minded cohort back up, the new creature descends from the longest-lived
	 * minded creature currently alive (a mutated child, inheriting its brain), not a
	 * fresh random one — living longest is itself the fitness, since a metabolic
	 * creature that can't feed itself starves. Only a wiped-out cohort falls back to
	 * random. Pins that the reseed tracks the OLDEST survivor's lineage (by its
	 * markers), not a younger one, and that the empty-cohort fallback still yields a
	 * valid random-brained genome.
	 */
	static class MindedReseedDescendsFromLongestLivedSurvivor extends Scenario {
		private static Genome mindedWith(double m0, double m1, double m2) {
			Genome g = new Genome();
			g.markers = new double[] { m0, m1, m2 };
			g.size = 10;
			g.speed = 0.05;
			g.brain = Brain.random(16);
			return g;
		}

		@Override
		public void run() {
			seed(90);
			World w = room(20, 20);

			// Empty cohort: the reseed falls back to a fresh random-brained genome.
			Genome fresh = net.hedinger.prototype.sim.Worlds.mindedReseedGenome(w);
			assertTrue("a wiped-out cohort reseeds a fresh random-brained genome", fresh.brain != null);

			// An older survivor (distinctive red-ish markers), then a younger one
			// (green-ish). Both live comfortably within the window (a big reserve
			// drains slowly), so age alone separates them.
			TestNPC older = TestNPC.mindedForager(5.5, 5.5, 0, mindedWith(0.90, 0.10, 0.10));
			w.spawnEntity(older);
			tick(w, 200); // the older one banks 200 ticks of age
			TestNPC younger = TestNPC.mindedForager(14.5, 14.5, 0, mindedWith(0.10, 0.90, 0.10));
			w.spawnEntity(younger);
			tick(w, 5);

			assertTrue("both survivors are still alive to seed from",
					!older.isDead() && !older.isRemoved() && !younger.isDead() && !younger.isRemoved());

			// The reseed descends from the OLDER survivor: its markers track the
			// red-ish lineage (mutated by <= the 0.08 rate), not the green-ish one.
			Genome reseed = net.hedinger.prototype.sim.Worlds.mindedReseedGenome(w);
			assertTrue("the reseed inherits a brain (not a brain-less body)", reseed.brain != null);
			assertLess("reseed marker 0 tracks the longest-lived survivor's",
					Math.abs(reseed.markers[0] - 0.90), 0.2);
			assertLess("reseed marker 1 tracks the longest-lived survivor's",
					Math.abs(reseed.markers[1] - 0.10), 0.2);
			assertGreater("the reseed did NOT descend from the younger survivor",
					Math.abs(reseed.markers[1] - 0.90), 0.3);
		}
	}

	/**
	 * The warm-seed payoff: a minded creature carrying the hand-written starter
	 * brain actually feeds itself. Placed on an all-grass meadow, it grazes, and so
	 * survives far past the age it could ever reach on its birth reserve alone — the
	 * "no-food starvation age" that every fully-random brain in Phases 3-4 died at.
	 * Being alive after 20k ticks (well beyond that ceiling for any body in the size
	 * band) can only mean it ate: the foothold selection needed.
	 */
	static class StarterBrainedForagerFeedsItself extends Scenario {
		@Override
		public void run() {
			seed(91);
			World w = room(24, 24);
			for (int x = 1; x < 23; x++) {
				for (int y = 1; y < 23; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // a lush meadow, grass everywhere
				}
			}
			// A pond: the world now has water as a need, so a survival room
			// without a shore is a death chamber — the forager wanders across
			// the meadow and sips whenever it passes the water's edge.
			for (int x = 10; x <= 13; x++) {
				for (int y = 10; y <= 13; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_SHALLOWS);
				}
			}
			tick(w, 1); // advance the clock so vegetation is defined
			// A fresh starter-brained genome (empty cohort -> mindedReseedGenome yields
			// the founder starter), on a body in the usual size band.
			Genome g = net.hedinger.prototype.sim.Worlds.mindedReseedGenome(w);
			// Suppress breeding so we test one forager feeding itself, not a cohort:
			// the starter mates whenever able, which in an unbounded room (no steward
			// ceiling) would explode and overgraze. The live world's minded cap
			// handles that; here we isolate the feeding claim.
			TestNPC forager = TestNPC.mindedForager(12.5, 12.5, 0, g).withReproCooldown(100_000_000);
			w.spawnEntity(forager);
			tick(w, 20000); // no body in the size band lasts this long without eating
			assertTrue("a starter-brained forager on grass is still alive after 20k ticks",
					!forager.isDead() && !forager.isRemoved());
			assertGreater("...reaching an age only feeding could sustain", forager.getAge(), 12000);
		}
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
			// 150 steps rather than 30: see PopulationGrowsWithFood -- feeding on grass
			// is a much longer job than it was, so the boom takes longer to arrive.
			for (int step = 0; step < 150; step++) {
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
			// Fitness is vegetation cropped, so these bars live in graze-demand units,
			// and that unit shrank sixteenfold when grass became bulk food
			// (GRAZE_DEMAND 0.05 -> 0.003). They are calibrated against measurement
			// rather than scaled by that ratio, because the relationship is not linear:
			// a patch runs out, so a slower crop does not reduce a good forager's haul
			// in proportion.
			//
			// The founder bar moved once the brain's operand pool was widened to cover
			// the whole sensor bank. Widening it also made the constant pool draw
			// uniformly, and one of those constants is the forage intent -- so random
			// code now stumbles into "seek food" appreciably more often than it did.
			// That is the fix working, not the test rotting: measured here, random
			// founders 0.024, champion 0.675 (unchanged), late mean 0.492. The gap this
			// test exists to detect is 0.469 against a bar of 0.2, and a random founder
			// still crops under four per cent of what a champion does.
			assertLess("random founder brains forage almost nothing", initialMean, 0.05);
			assertGreater("evolution found a strong forager (a champion gathered real food)",
					bestEver, 0.4);
			assertGreater("mean foraging rose far above the random start under selection",
					finalMean, initialMean + 0.2);
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
				// Hungry throughout: appetite is what grazes now, and the empty
				// stomach holds more grass than the best champion ever crops.
				ag[i] = TestNPC.minded(x, y, 0, g, new LgpMind(brains[i], BUDGET)).withHunger(1.0);
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
			// 120 rounds, not 45: courtship still takes MATING_TICKS, but the
			// cooldown between births is now mass-scaled (half the offspring's
			// childhood — VITALS.md §6), so fewer matings fit a window and the
			// crossover the test watches for needs a couple of rounds to appear.
			for (int step = 0; step < 120; step++) {
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
			TestNPC captive = TestNPC.minded(4.05, 6.0, 0, captiveG).withHunger(1.0);
			// Pair 2 (far away): a grazer rider on a bigger, stationary host.
			Genome riderG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			riderG.brain = new Brain(deepCopy(rideGraze));
			TestNPC rider = TestNPC.minded(15.05, 6.0, 0, riderG).withHunger(1.0);
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
			// Both starving: satiation zero switches regeneration off, so the only
			// thing moving either tank is the burn being compared.
			TestNPC rider = TestNPC.brainedBreeder(4.05, 6.0, 0, riderG)
					.withEnergy(6.0).withHunger(1.0);
			TestNPC host = TestNPC.roamer(4.0, 6.0, 0).withSize(18).withSpeed(0.0);
			TestNPC control = TestNPC.brainedBreeder(10.0, 6.0, 0, ctrlG)
					.withEnergy(6.0).withHunger(1.0);
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
			// A rider pays half metabolism, so it should lose meaningfully less than
			// one under its own power. Assert the ratio, not an absolute margin, so
			// the check is independent of the model's overall energy scale.
			assertGreater("riding spends less energy than moving under your own power",
					lossWalk, lossRide * 1.5);
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
			// A captor holding little reserve and burning fast: under VITALS an
			// empty tank no longer kills — it collapses, and a collapsed captor
			// cannot hold. The grip must open while the captor still lives; a
			// wound then kills it, and death releases nothing it still held.
			Genome capG = Genome.phenotype(8, 0.0, 5, 6, Math.PI * 2, 100000);
			capG.metabolism = 0.05;
			capG.brain = new Brain(deepCopy(hold));
			Genome vicG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vicG.brain = new Brain(deepCopy(limp));
			TestNPC captor = TestNPC.brainedBreeder(6.0, 6.0, 0, capG)
					.withEnergy(0.6).withHunger(1.0); // starving: no regeneration
			TestNPC captive = TestNPC.minded(6.05, 6.0, 0, vicG);
			w.spawnEntity(captor);
			w.spawnEntity(captive);
			tick(w, 4);
			assertTrue("the captive is grabbed while the captor lives", captive.isGrabbed());
			assertTrue("the captor still lives at this point", !captor.isDead());
			tick(w, 200); // the grip drains the tank to the crawl reserve
			assertTrue("the drained captor still lives — collapse is not death", !captor.isDead());
			assertTrue("but a collapsed captor cannot hold: the captive walks free",
					captive.getAttachTarget() == null && !captive.isGrabbed());
			// Death still releases: re-grab is impossible collapsed, and a wound
			// that kills the captor leaves no load on the corpse.
			captor.damage(1000, "misadventure");
			tick(w, 2);
			assertTrue("the wounded captor died", captor.isDead());
			assertNear("the corpse carries no load", 0.0, captor.getCarriedLoad(), 1e-9);
		}
	}

	/**
	 * Carrying a body aloft is far costlier: a flying carrier burns much more
	 * energy than a grounded one hauling the same weight the same distance.
	 *
	 * <p>A load is priced as extra mass through the movement term, and flight
	 * multiplies how heavy that load counts, so both carriers have to actually
	 * haul their passenger somewhere for the difference to exist — a hovering
	 * carrier and a standing one both pay nothing for weight alone. The passengers
	 * ride VOLUNTARILY rather than being seized, which keeps the comparison clean:
	 * a grabbed captive would add an identical grip cost to both bills and dilute
	 * the very ratio being measured.
	 */
	static class FlyingCarrierPaysMore extends Scenario {
		@Override
		public void run() {
			seed(83);
			World w = room(60, 40);
			// Full throttle plus a gentle constant turn, so each carrier orbits a
			// few tiles wide: it hauls continuously without ever reaching a wall,
			// whatever heading it happened to start on.
			int[][] walk = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 },
					{ Brain.SET, 1, 6, 0 }, // r1 = 0.1 (const[6])
					{ Brain.WRITE, AgentIO.A_TURN, 1, 0 } };
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 },
					{ Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			Genome fG = Genome.phenotype(8, 0.12, 5, 6, Math.PI * 2, 100000);
			fG.metabolism = 0.02;
			fG.brain = new Brain(deepCopy(walk));
			Genome gG = Genome.phenotype(8, 0.12, 5, 6, Math.PI * 2, 100000);
			gG.metabolism = 0.02;
			gG.brain = new Brain(deepCopy(walk));
			TestNPC flier = TestNPC.brainedBreeder(15.0, 20.0, 0, fG).withEnergy(14.0).withFlying();
			TestNPC ground = TestNPC.brainedBreeder(45.0, 20.0, 0, gG).withEnergy(14.0);
			Genome vAG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vAG.brain = new Brain(deepCopy(cling));
			Genome vBG = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			vBG.brain = new Brain(deepCopy(cling));
			TestNPC riderA = TestNPC.minded(15.05, 20.0, 0, vAG).withFlying();
			TestNPC riderB = TestNPC.minded(45.05, 20.0, 0, vBG);
			w.spawnEntity(flier);
			w.spawnEntity(ground);
			w.spawnEntity(riderA);
			w.spawnEntity(riderB);
			tick(w, 6);
			assertTrue("the flier picked up a passenger", riderA.getAttachTarget() == flier);
			assertTrue("the ground carrier picked up a passenger", riderB.getAttachTarget() == ground);

			double f0 = flier.getEnergy(), g0 = ground.getEnergy();
			// Ground covered, summed per tick — direction-agnostic, unlike a
			// start-to-end displacement, which an orbiting body would understate.
			double fDist = 0, gDist = 0;
			for (int i = 0; i < 250; i++) {
				w.think();
				fDist += flier.lastStep();
				gDist += ground.lastStep();
			}
			double fLoss = f0 - flier.getEnergy(), gLoss = g0 - ground.getEnergy();
			assertGreater("both carriers actually hauled their passenger somewhere",
					Math.min(fDist, gDist), 5.0);
			// A ratio, not a fixed margin: both bills scale with how far they get,
			// so the multiple is the stable claim.
			assertGreater("hauling a body aloft costs a flier far more than a ground "
					+ "carrier (" + String.format("%.2f", fLoss) + " vs "
					+ String.format("%.2f", gLoss) + ")", fLoss, gLoss * 1.8);
		}
	}

	/** A grounded creature cannot seize a flyer out of the air, though it can still
	 *  grab grounded creatures, and a flyer can seize another flyer. */
	static class GroundCannotGrabFlyer extends Scenario {
		@Override
		public void run() {
			seed(82);
			World w = room(12, 12);
			TestNPC ground = TestNPC.inert(5.5, 5.5, 0).withSize(8);
			TestNPC flyer = TestNPC.inert(5.54, 5.5, 0).withSize(4).withFlying();
			TestNPC walker = TestNPC.inert(5.5, 5.54, 0).withSize(4);
			TestNPC airGrabber = TestNPC.inert(5.54, 5.54, 0).withSize(8).withFlying();
			w.spawnEntity(ground);
			w.spawnEntity(flyer);
			w.spawnEntity(walker);
			w.spawnEntity(airGrabber);
			w.think();
			assertTrue("a grounded creature cannot seize a flyer", !ground.grab(flyer));
			assertTrue("a grounded creature can still seize a grounded one", ground.grab(walker));
			assertTrue("a flyer can seize another flyer", airGrabber.grab(flyer));
		}
	}

	/** Bucking (the same struggle actuator, from the carrier's side): a host shakes
	 *  off its riders, throwing the larger one clear first while the smaller one
	 *  clings tighter and holds on far longer. */
	static class HostBucksOffRiders extends Scenario {
		@Override
		public void run() {
			seed(84);
			World w = room(16, 12);
			int[][] buck = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_STRUGGLE, 0, 0 } };
			int[][] cling = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACH, 0, 0 } };
			Genome hG = Genome.phenotype(20, 0.0, 5, 6, Math.PI * 2, 100000);
			hG.brain = new Brain(deepCopy(buck));
			Genome bigG = Genome.phenotype(14, 0.0, 5, 6, Math.PI * 2, 100000);
			bigG.brain = new Brain(deepCopy(cling));
			Genome smG = Genome.phenotype(5, 0.0, 5, 6, Math.PI * 2, 100000);
			smG.brain = new Brain(deepCopy(cling));
			TestNPC host = TestNPC.minded(8.0, 6.0, 0, hG);
			TestNPC bigRider = TestNPC.minded(8.08, 6.0, 0, bigG);
			TestNPC smallRider = TestNPC.minded(8.0, 6.08, 0, smG);
			w.spawnEntity(host);
			w.spawnEntity(bigRider);
			w.spawnEntity(smallRider);
			tick(w, 5);
			assertTrue("both riders latched on",
					bigRider.getAttachTarget() == host && smallRider.getAttachTarget() == host);
			tick(w, 24); // the host bucks; the bigger rider comes loose first
			assertTrue("the larger rider is bucked off", bigRider.getAttachTarget() == null);
			assertTrue("the smaller rider clings on (tighter grip, harder to throw)",
					smallRider.getAttachTarget() == host);
			tick(w, 60); // keep bucking
			assertTrue("eventually even the small rider is thrown clear",
					smallRider.getAttachTarget() == null);
			assertTrue("a bucked-off rider cannot immediately climb back on",
					bigRider.getAttachTarget() == null);
		}
	}

	// ---- inanimate items ---------------------------------------------------

	/** The dedicated item sense: a mind that throttles on {@code S_ITEM_PROX}
	 * moves only when an inanimate object is nearby -- proving items register on
	 * their own perception channel, distinct from the living-neighbour one. */
	static class ItemSensedOnDedicatedChannel extends Scenario {
		@Override
		public void run() {
			seed(90);
			World w = room(16, 16);
			int[][] prog = {
					{ Brain.SENSE, 0, AgentIO.S_ITEM_PROX, 0 }, // R0 = item proximity
					{ Brain.WRITE, AgentIO.A_THROTTLE, 0, 0 },   // walk when it sees one
			};
			Genome g = Genome.phenotype(6, 0.05, 5, 6, Math.PI * 2, 100000);
			// One creature beside a crate, an identical control alone.
			TestNPC seer = TestNPC.minded(4.0, 4.0, 0, g, new LgpMind(new Brain(deepCopy(prog)), 2));
			TestNPC control = TestNPC.minded(12.0, 12.0, 0, g, new LgpMind(new Brain(deepCopy(prog)), 2));
			Item crate = Item.crate(4.15, 4.0, 0);
			w.spawnEntity(seer);
			w.spawnEntity(control);
			w.spawnEntity(crate);
			w.think();
			double sx = seer.getX(), sy = seer.getY();
			double cx = control.getX(), cy = control.getY();
			tick(w, 40);
			snapshot(w, "seer walks toward the crate; control sits idle");
			assertGreater("the creature that senses a nearby item moves",
					Math.hypot(seer.getX() - sx, seer.getY() - sy), 0.2);
			assertLess("the control with no item in range stays put",
					Math.hypot(control.getX() - cx, control.getY() - cy), 0.02);
		}
	}

	/** Eating a food item: a mind firing A_EAT beside a food object consumes it
	 * and gains its {@code foodEnergy}; the item is removed once eaten. */
	static class FoodItemEatenForEnergy extends Scenario {
		@Override
		public void run() {
			seed(91);
			World w = room(12, 12);
			int[][] eat = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			// Hungry, non-metabolic minded body: the swallowed ledger only moves
			// with what it eats, and the meal (2.0) fits its mass-scaled stomach.
			TestNPC eater = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(eat)), 2))
					.withHunger(1.0);
			Item food = Item.food(6.1, 6.0, 0).withFoodEnergy(2.0);
			w.spawnEntity(eater);
			w.spawnEntity(food);
			w.think();
			snapshot(w, "before (beside a food item)");
			// Tick until the food is consumed, measuring the energy jump on that very
			// tick so a stray graze from the ground can't inflate the reading.
			double delta = 0;
			for (int i = 0; i < 40 && !food.isRemoved(); i++) {
				double before = eater.totalSwallowed();
				tick(w, 1);
				if (food.isRemoved()) {
					delta = eater.totalSwallowed() - before;
				}
			}
			snapshot(w, "after (food eaten, stomach filled)");
			assertTrue("the food item was eaten (removed)", food.isRemoved());
			assertNear("eating the food yielded its foodEnergy", 2.0, delta, 0.1);
		}
	}

	/** Smashing a crate: a mind firing A_ATTACK beside a crate whittles its
	 * durability down and, when it breaks, the crate spills loose food items. */
	static class CrateBrokenSpillsFood extends Scenario {
		@Override
		public void run() {
			seed(92);
			World w = room(12, 12);
			int[][] hit = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACK, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC smasher = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(hit)), 2));
			Item crate = Item.crate(6.18, 6.0, 0).withDurability(8).withSpill(4);
			w.spawnEntity(smasher);
			w.spawnEntity(crate);
			w.think();
			snapshot(w, "before (beside an intact crate)");
			// Tick until the crate shatters, then count the food it spilled right away
			// (the smasher would otherwise start destroying the loose food next).
			int foodCount = -1;
			for (int i = 0; i < 40 && !crate.isRemoved(); i++) {
				tick(w, 1);
				if (crate.isRemoved()) {
					foodCount = countFood(w);
				}
			}
			snapshot(w, "after (crate broken, food spilled)");
			assertTrue("the crate broke under repeated attacks", crate.isRemoved());
			assertEquals("the broken crate spilled its food", 4, foodCount);
		}

		private int countFood(World w) {
			int n = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof Item && !e.isRemoved() && ((Item) e).getKind() == Item.Kind.FOOD) {
					n++;
				}
			}
			return n;
		}
	}

	/** A hazard bites back: eating a hazard item damages the eater (and consumes
	 * the hazard), so a creature pays for swallowing something dangerous. */
	static class HazardHarmsEater extends Scenario {
		@Override
		public void run() {
			seed(93);
			World w = room(12, 12);
			int[][] eat = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_EAT, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC eater = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(eat)), 2));
			Item hazard = Item.hazard(6.1, 6.0, 0).withHazardDamage(25);
			w.spawnEntity(eater);
			w.spawnEntity(hazard);
			w.think();
			int hpBefore = eater.getHealth();
			for (int i = 0; i < 40 && !hazard.isRemoved(); i++) {
				tick(w, 1);
			}
			assertTrue("the hazard was consumed", hazard.isRemoved());
			assertEquals("eating the hazard cost the eater its hazardDamage in health",
					hpBefore - 25, eater.getHealth());
		}
	}

	/** A hazard also bites an attacker: striking a hazard object wounds the
	 * striker, so hitting one is as costly as eating it. */
	static class HazardHarmsAttacker extends Scenario {
		@Override
		public void run() {
			seed(94);
			World w = room(12, 12);
			int[][] hit = { { Brain.SENSE, 0, AgentIO.S_BIAS, 0 }, { Brain.WRITE, AgentIO.A_ATTACK, 0, 0 } };
			Genome g = Genome.phenotype(6, 0.0, 5, 6, Math.PI * 2, 100000);
			TestNPC striker = TestNPC.minded(6.0, 6.0, 0, g, new LgpMind(new Brain(deepCopy(hit)), 2));
			Item hazard = Item.hazard(6.18, 6.0, 0).withHazardDamage(10).withDurability(1000);
			w.spawnEntity(striker);
			w.spawnEntity(hazard);
			w.think();
			snapshot(w, "before (striker beside a hazard)");
			int hpBefore = striker.getHealth();
			tick(w, 30); // hardy hazard survives, biting back on every strike
			snapshot(w, "after (attacker wounded by the hazard)");
			assertTrue("the tough hazard survived the strikes", !hazard.isRemoved());
			assertLess("striking the hazard wounded the attacker", striker.getHealth(), hpBefore);
		}
	}

	/** Items are shoved aside: a creature walking through where an item rests
	 * displaces it (the same spring that separates creatures), without carrying it. */
	static class ItemPushedAsideByPasserby extends Scenario {
		@Override
		public void run() {
			seed(95);
			World w = room(20, 8);
			// A crate sitting in the lane; a mover trudging into it, slow enough that
			// it stays in contact and shoves the crate along ahead of it (the same
			// spring that separates two creatures) rather than blowing past.
			Item crate = Item.crate(10.0, 4.0, 0);
			TestNPC walker = TestNPC.mover(9.0, 4.0, 0, 0.0).withSpeed(0.02); // heading +x, into the crate
			w.spawnEntity(crate);
			w.spawnEntity(walker);
			w.think();
			snapshot(w, "before (crate in the walker's path)");
			double startX = crate.getX(), startY = crate.getY();
			tick(w, 400);
			snapshot(w, "after (crate shoved aside)");
			double moved = Math.hypot(crate.getX() - startX, crate.getY() - startY);
			assertGreater("the passer-by shoved the crate along", moved, 0.3);
			assertTrue("the crate was displaced, not picked up", crate.getAttachTarget() == null);
		}
	}

	/** A courier round-trip over offset waypoints: the hauler starts at one corner,
	 * walks (turning in smooth arcs) to the crate's spot, grabs it, carries it to a
	 * drop-off elsewhere, sets it down, and eases back home empty-handed. The three
	 * points are at different rows and columns, so each leg heads a different way. */
	static class CarrierHaulsCrate extends Scenario {
		@Override
		public void run() {
			seed(96);
			World w = room(26, 12);
			// A zig-zag: home lower-left, crate lower-right-ish, drop-off upper-right --
			// no two legs share a heading, so the courier visibly turns at each stop.
			double homeX = 4.0, homeY = 5.0;
			double pickX = 13.0, pickY = 9.0;
			double dropX = 22.0, dropY = 3.0;
			Item crate = Item.crate(pickX, pickY, 0);
			TestNPC courier = TestNPC.hauler(homeX, homeY, 0, pickX, pickY, dropX, dropY);
			w.spawnEntity(crate);
			w.spawnEntity(courier);
			w.think();
			snapshot(w, "before (hauler far from the crate)");
			tick(w, 1100); // fetch, haul to the drop-off, return home
			snapshot(w, "after (crate delivered, hauler back home)");
			assertNear("the crate was delivered to the drop-off (x)", dropX, crate.getX(), 1.5);
			assertNear("the crate was delivered to the drop-off (y)", dropY, crate.getY(), 1.5);
			assertTrue("the crate was set down, not still held", crate.getAttachTarget() == null);
			assertGreater("the crate really was carried a long way",
					Math.hypot(crate.getX() - pickX, crate.getY() - pickY), 6.0);
			assertNear("the hauler returned home empty-handed (x)", homeX, courier.getX(), 1.5);
			assertNear("the hauler returned home empty-handed (y)", homeY, courier.getY(), 1.5);
			assertTrue("the hauler is no longer carrying anything", courier.getCarriedLoad() == 0);
		}
	}

	/** Line-of-sight over open floor must carry in every direction -- rows, columns
	 * AND diagonals -- and a wall on the line must block it. Guards the sight
	 * raycast, which historically traced correctly only along pure rows/columns and
	 * reported no sight for any diagonal even over perfectly clear ground. */
	static class DiagonalLineOfSight extends Scenario {
		@Override
		public void run() {
			seed(101);
			World w = room(16, 16);
			double dir = 0; // heading irrelevant here: fov = PI means all-round sight

			// Clear open floor: sight carries every way, diagonals included.
			assertTrue("clear sight along a row",
					w.hasLOS(2.5, 8.5, 0, dir, 12.5, 8.5, 0, -1, Math.PI));
			assertTrue("clear sight along a column",
					w.hasLOS(8.5, 2.5, 0, dir, 8.5, 12.5, 0, -1, Math.PI));
			assertTrue("clear sight along a 45-degree diagonal",
					w.hasLOS(2.5, 2.5, 0, dir, 12.5, 12.5, 0, -1, Math.PI));
			assertTrue("clear sight along a shallow diagonal",
					w.hasLOS(3.5, 5.5, 0, dir, 11.5, 3.5, 0, -1, Math.PI));
			assertTrue("clear sight is symmetric (reversed shallow diagonal)",
					w.hasLOS(11.5, 3.5, 0, dir, 3.5, 5.5, 0, -1, Math.PI));

			// A wall sitting on the diagonal blocks it; a parallel line that misses
			// the wall stays clear.
			w.setTile(7, 7, 0, Tile.TileType.TYPE_WALL);
			assertTrue("a wall on the 45-degree diagonal blocks sight",
					!w.hasLOS(2.5, 2.5, 0, dir, 12.5, 12.5, 0, -1, Math.PI));
			assertTrue("a parallel diagonal that misses the wall is still clear",
					w.hasLOS(2.5, 4.5, 0, dir, 10.5, 12.5, 0, -1, Math.PI));

			// Regression guard: a wall across a row still blocks the common case.
			w.setTile(8, 8, 0, Tile.TileType.TYPE_WALL);
			assertTrue("a wall across a row blocks sight",
					!w.hasLOS(2.5, 8.5, 0, dir, 12.5, 8.5, 0, -1, Math.PI));
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
	/** The strongest pheromone cloud right now as {@code {x, y, intensity}}, where
	 *  intensity is what a homing creature actually smells there — the summed
	 *  concentration of every cloud overlapping that point, not one cloud's own
	 *  strength. All zeros when nothing has been laid. */
	private static double[] strongestNest(World w) {
		double maxP = 0, nx = 0, ny = 0;
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
		return maxP == 0 ? new double[] { 0, 0, 0 }
				: new double[] { nx, ny, w.pheromoneAt(nx, ny, 0) };
	}

	/** {@code {near, total}} living creatures, near meaning within {@code radius}
	 *  tiles of {@code (cx, cy)}. */
	private static int[] colonyNear(World w, double cx, double cy, double radius) {
		int near = 0, total = 0;
		for (Entity e : w.getEntities()) {
			if (e instanceof net.hedinger.prototype.entities.NPC && !e.isDead()) {
				total++;
				if (Math.hypot(e.getX() - cx, e.getY() - cy) < radius) {
					near++;
				}
			}
		}
		return new int[] { near, total };
	}

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
			// A nest is a process, not a state: it waxes while the colony breeds and
			// fades as the pheromone decays. So sample it as the run goes and keep the
			// strongest it ever got, rather than reading whatever happened to be
			// standing on the tick the test stopped. Under the old grass economy the
			// colony bred fast enough that the two coincided; now that grass is bulk
			// food, breeding is spread out and the terminal reading undersells the nest
			// by a factor of three. The claim under test is unchanged.
			// Both facts are read at the SAME moment — the tick the nest is strongest.
			// Asking whether the colony sits on its nest only means anything while
			// there is a nest to sit on, and by the end of a long run the pheromone has
			// faded and the colony has filled this small room, at which point every
			// creature is near everything and the question answers itself.
			double nestIntensity = 0;
			int near = 0, total = 0;
			for (int step = 0; step < 64; step++) {
				tick(w, 50);
				double[] nest = strongestNest(w);
				if (nest[2] > nestIntensity) {
					nestIntensity = nest[2];
					int[] c = colonyNear(w, nest[0], nest[1], 3.0);
					near = c[0];
					total = c[1];
				}
			}
			snapshot(w, "after (colony around the nest)");
			assertGreater("the population grew by breeding", w.getAliveCount(), start);
			assertGreater("a pheromone nest built up", nestIntensity, 4.0);
			// The physical fixture matches the scent: births claimed a Nest.
			int nests = 0, broods = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof net.hedinger.prototype.entities.Nest n && !e.isRemoved()) {
					nests++;
					broods += n.getBroods();
				}
			}
			assertGreater("births raised a physical nest fixture", nests, 0);
			assertGreater("...that counted its broods", broods, 1);

			// The living colony concentrates near that nest: a good share of it sits
			// within a few tiles of the pheromone peak — noticeably denser than the
			// ~20% a uniform spread would put within that radius. A concentration
			// fraction is robust to colony size, unlike a mean-distance threshold
			// (which washes out once a fast-breeding colony fills the room).
			// Measured against what an even spread over the walkable interior would
			// put inside that radius, NOT against a raw fraction. A thriving colony
			// saturates this small room — 150-odd bodies over ~144 open tiles — and
			// at that density no raw share can look impressive however tightly the
			// creatures nest, simply because the circle cannot hold them. The ratio
			// asks the question the test actually means: are they denser here than
			// chance would put them?
			double open = (w.getColums() - 2) * (double) (w.getRows() - 2);
			double uniform = Math.PI * 3.0 * 3.0 / open;
			assertGreater("the colony concentrates around the nest ("
					+ near + "/" + total + " within 3 tiles, vs "
					+ String.format("%.0f%%", uniform * 100) + " if spread evenly)",
					(near / (double) total) / uniform, 1.2);
		}
	}

	// ---- the observation seam (MODERNIZATION.md Phase 1) --------------------

	/** The snapshot stream is deterministic and read-only: two runs of the same
	 * seeded world produce bit-identical per-tick snapshot checksums, and taking
	 * a snapshot twice without ticking yields the same capture (observing the
	 * world never perturbs it). */
	static class SnapshotStreamDeterministic extends Scenario {
		private long[] stream(long seed, int ticks) {
			net.hedinger.prototype.sim.SimulationRunner r =
					new net.hedinger.prototype.sim.SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(seed));
			long[] sums = new long[ticks];
			for (int i = 0; i < ticks; i++) {
				r.tickOnce();
				sums[i] = r.snapshot().checksum();
			}
			return sums;
		}

		@Override
		public void run() {
			long[] a = stream(7, 120);
			long[] b = stream(7, 120);
			for (int i = 0; i < a.length; i++) {
				assertEquals("snapshot checksum at tick " + i + " identical across runs", a[i], b[i]);
			}
			// Observing is free of side effects: snapshot twice, no tick between.
			net.hedinger.prototype.sim.SimulationRunner r =
					new net.hedinger.prototype.sim.SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(7));
			r.advance(10);
			long first = net.hedinger.prototype.sim.WorldSnapshot.of(r.world()).checksum();
			long second = net.hedinger.prototype.sim.WorldSnapshot.of(r.world()).checksum();
			assertEquals("re-observing an unticked world captures the same state", first, second);
			assertGreater("the demo world is populated", r.snapshot().entities().size(), 10);
		}
	}

	/** seed + command log reproduces the world exactly: an interactive run
	 * (commands enqueued mid-flight) replayed from its log against a fresh
	 * world of the same seed ends bit-identical — the invariant that lets web
	 * viewers meddle without breaking reproducibility. */
	static class CommandLogReplayReproduces extends Scenario {
		@Override
		public void run() {
			net.hedinger.prototype.sim.SimulationRunner live =
					new net.hedinger.prototype.sim.SimulationRunner(net.hedinger.prototype.sim.Worlds.demo(13));
			live.advance(30);
			live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("food", 10.5, 10.5, 0));
			live.advance(40);
			live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("crate", 20.5, 12.5, 0));
			live.enqueue(net.hedinger.prototype.sim.SpawnItemCommand.parse("hazard", 30.5, 14.5, 0));
			live.advance(30);

			assertEquals("all three commands were applied and logged", 3, live.commandLog().size());
			int items = 0;
			for (net.hedinger.prototype.sim.EntityState e : live.snapshot().entities()) {
				if (e.kind().startsWith("item.")) {
					items++;
				}
			}
			assertGreater("the spawned items are visible in the snapshot", items, 3);

			World fresh = net.hedinger.prototype.sim.Worlds.demo(13);
			net.hedinger.prototype.sim.SimulationRunner.replay(fresh, live.commandLog(), 100);
			assertEquals("replaying seed + command log reproduces the exact world state",
					live.snapshot().checksum(),
					net.hedinger.prototype.sim.WorldSnapshot.of(fresh).checksum());
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

	/**
	 * A hunter must not chase prey on another level. A predator that follows prey
	 * down a hole into the cave used to lock onto the prey still on the surface
	 * directly overhead (same x/y, one level up) and pin itself against a cave
	 * wall trying to reach an unreachable target — it looked frozen. Perception
	 * is now level-scoped, so a hunter with no prey on its own level prowls and
	 * keeps moving instead of getting stuck.
	 */
	static class HunterIgnoresPreyOnAnotherLevel extends Scenario {
		@Override
		public void run() {
			seed(20);
			World w = room(14, 14, 2); // two open levels
			Genome predG = new Genome();
			predG.size = 14;
			predG.speed = 0.05;
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0.03;
			TestNPC pred = TestNPC.predator(7.5, 7.5, 0, predG); // in the cave (level 0)
			TestNPC prey = TestNPC.breeder(7.5, 7.5, 1, preyG).withHerding(); // straight above
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();

			double path = 0, px = pred.getX(), py = pred.getY();
			for (int i = 0; i < 200; i++) {
				tick(w, 1);
				path += Math.hypot(pred.getX() - px, pred.getY() - py);
				px = pred.getX();
				py = pred.getY();
			}
			assertTrue("hunter ignores prey a level away and prowls (was: " + pred.currentAction() + ")",
					pred.currentAction().equals("prowling"));
			assertGreater("the cave hunter kept moving (never stuck on an unreachable target)", path, 2.0);
		}
	}

	/**
	 * A hunter never freezes on prey it can see but can't reach. A predator that
	 * locks onto prey across water (which blocks movement but not sight) used to
	 * pin itself at the shoreline, steering into the water forever in "hunting".
	 * Now, if a chase makes no headway it gives up for a spell and prowls — so the
	 * hunter keeps moving instead of freezing.
	 */
	static class HunterDoesNotFreezeOnUnreachablePrey extends Scenario {
		@Override
		public void run() {
			seed(22);
			World w = room(16, 9);
			for (int y = 1; y < 8; y++) {
				w.setTile(8, y, 0, Tile.TileType.TYPE_WATER); // a water wall splits the room
			}
			Genome predG = new Genome();
			predG.size = 14;
			predG.speed = 0.05;
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0.0; // a fixed lure on the far bank
			TestNPC pred = TestNPC.predator(3.5, 4.5, 0, predG);
			TestNPC prey = TestNPC.breeder(13.5, 4.5, 0, preyG);
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();

			tick(w, 120); // let it reach the shore and (without the fix) pin there
			// Measure steady-state movement: a frozen hunter accrues ~0 path here;
			// one that gives up and prowls keeps racking it up.
			double path = 0, px = pred.getX(), py = pred.getY();
			for (int i = 0; i < 300; i++) {
				tick(w, 1);
				path += Math.hypot(pred.getX() - px, pred.getY() - py);
				px = pred.getX();
				py = pred.getY();
			}
			assertGreater("the hunter never froze against the water — it kept moving", path, 3.0);
			assertLess("the hunter stayed on its (dry) side of the water", pred.getX(), 8.0);
		}
	}

	/**
	 * Hunger governs the hunt. A well-fed predator (at or above its breeding
	 * threshold) patrols and leaves prey it does not need alone, while a hungry
	 * one runs the very same prey down. Only the predator's energy differs between
	 * the two runs, so the kills that appear in one and not the other isolate
	 * appetite as the driver — the fix for hunters that used to slaughter prey they
	 * were already too full to make any use of.
	 */
	static class SatedPredatorSparesPrey extends Scenario {
		private int kills(double hungerLevel, int reproCooldown) {
			seed(11);
			World w = room(20, 9);
			Genome predG = new Genome();
			predG.size = 14;
			predG.speed = 0.06;
			TestNPC pred = TestNPC.predator(5.5, 4.5, 0, predG)
					.withReproCooldown(reproCooldown); // don't let it breed the surplus off
			pred.withHunger(hungerLevel); // appetite is the hunting drive (VITALS.md)
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0.0; // a fixed lure in plain sight, four tiles off
			TestNPC prey = TestNPC.breeder(9.5, 4.5, 0, preyG);
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();
			int kills = 0;
			for (int i = 0; i < 150; i++) {
				if (prey.isDead() || prey.isRemoved()) {
					kills++;
					prey = TestNPC.breeder(9.5, 4.5, 0, preyG); // restock the lure
					w.spawnEntity(prey);
				}
				tick(w, 1);
			}
			return kills;
		}

		@Override
		public void run() {
			int hungry = kills(0.7, 0); // past the appetite line: hunts in earnest
			int sated = kills(0.0, 100000); // sated: no appetite, no kill
			assertGreater("a hungry predator runs the prey down", hungry, 0);
			assertEquals("a sated predator leaves prey it doesn't need alone", 0, sated);
		}
	}

	/**
	 * Cannibalism is a last resort, not routine. A predator spares a smaller rival
	 * predator while it is fed or merely hungry, and turns on it only under genuine
	 * starvation. Same cast in both runs — only the big predator's energy differs —
	 * so the bite that appears in one and not the other isolates starvation as what
	 * lifts the taboo on eating one's own kind.
	 */
	static class StarvationDrivesCannibalism extends Scenario {
		private int damageToRival(double hungerLevel, int reproCooldown) {
			seed(12);
			World w = room(16, 9);
			Genome bigG = new Genome();
			bigG.size = 16;
			bigG.speed = 0.06;
			TestNPC big = TestNPC.predator(6.5, 4.5, 0, bigG).withReproCooldown(reproCooldown);
			big.withHunger(hungerLevel); // hunger is the drive; >= 0.9 is starving
			Genome smallG = new Genome();
			smallG.size = 9;
			smallG.speed = 0.0; // a smaller, stationary rival predator two tiles off
			TestNPC small = TestNPC.predator(8.5, 4.5, 0, smallG).withReproCooldown(1_000_000);
			int hp0 = small.getHealth();
			w.spawnEntity(big);
			w.spawnEntity(small);
			w.think();
			tick(w, 120);
			int hp1 = (small.isDead() || small.isRemoved()) ? -1000 : small.getHealth();
			return hp0 - hp1;
		}

		@Override
		public void run() {
			int wellFed = damageToRival(0.2, 100000); // fed: leaves its own kind be
			int starving = damageToRival(0.95, 0); // desperate: turns cannibal
			assertEquals("a well-fed predator spares a smaller rival predator", 0, wellFed);
			assertGreater("a starving predator turns on its own kind", starving, 0);
		}
	}

	/**
	 * Anti-predator flight (the ecosystem's "aliveness" behaviour): a vigilant
	 * eco herbivore ({@code withHerding()}) bolts away from a hunter, keeping a
	 * wider gap than an ordinary grazer that crops in place while the predator
	 * closes. Same seed, same cast — only the vigilance flag differs — so the gap
	 * difference isolates the flight response. Pins that {@code withHerding()}
	 * actually makes prey flee, and that the plain breeder is unaffected.
	 */
	static class HerbivoreFleesPredator extends Scenario {
		private double endGap(boolean vigilant) {
			seed(5);
			World w = room(28, 28); // open grass, so the grazer never starves in-window
			Genome preyG = new Genome();
			preyG.markers = new double[] { 0.9, 0.7, 0.4 };
			preyG.size = 7;
			preyG.speed = 0.05; // a fleeing prey can open the gap...
			Genome hunterG = new Genome();
			hunterG.markers = new double[] { 0.9, 0.2, 0.2 };
			hunterG.size = 14;
			hunterG.speed = 0.04; // ...so flight is unambiguous, no kill to confound it
			TestNPC pred = TestNPC.predator(10.5, 14.5, 0, hunterG);
			TestNPC herb = TestNPC.breeder(15.5, 14.5, 0, preyG);
			if (vigilant) {
				herb.withHerding();
			}
			w.spawnEntity(pred);
			w.spawnEntity(herb);
			w.think();
			tick(w, 25);
			snapshot(w, vigilant ? "vigilant herbivore flees" : "plain grazer stays");
			return Math.hypot(herb.getX() - pred.getX(), herb.getY() - pred.getY());
		}

		@Override
		public void run() {
			double fleeing = endGap(true);
			double grazing = endGap(false);
			assertGreater("a vigilant herbivore keeps a wider gap from the hunter than a grazer",
					fleeing, grazing);
			assertGreater("the vigilant herbivore actively opened distance (fled) from a 5-tile start",
					fleeing, 5.0);
		}
	}

	/**
	 * Predation actually connects: a faster hunter runs down a <em>fleeing</em>
	 * vigilant prey that starts several tiles away and bites it. This guards the
	 * predator's ranged prey-sense — the hunter must perceive (and keep chasing)
	 * prey well beyond the tile-local perception grid, or a fleeing prey it can't
	 * see past ~1 tile would open the gap and never be caught. The companion
	 * {@link HerbivoreFleesPredator} pins flight with a faster prey (no kill);
	 * this pins the kill with a faster predator, so together they cover both
	 * sides of pursuit.
	 */
	static class PredatorRunsDownFleeingPrey extends Scenario {
		@Override
		public void run() {
			seed(9);
			World w = room(30, 12); // open grass, long enough for a real chase
			Genome preyG = new Genome();
			preyG.markers = new double[] { 0.9, 0.7, 0.4 };
			preyG.size = 7;
			preyG.speed = 0.03; // slower than the hunter, so it can be run down
			Genome hunterG = new Genome();
			hunterG.markers = new double[] { 0.9, 0.2, 0.2 };
			hunterG.size = 14;
			hunterG.speed = 0.05; // faster: a committed pursuit closes the gap
			// Hungry on arrival: appetite, not tank headroom, is what hunts (VITALS.md).
			TestNPC pred = TestNPC.predator(3.5, 6.5, 0, hunterG).withHunger(0.7);
			TestNPC prey = TestNPC.breeder(11.5, 6.5, 0, preyG).withHerding(); // vigilant: it flees
			w.spawnEntity(pred);
			w.spawnEntity(prey);
			w.think();

			double startGap = Math.hypot(prey.getX() - pred.getX(), prey.getY() - pred.getY());
			assertGreater("prey starts well beyond the tile-local perception grid", startGap, 6.0);

			// Give the hunt time to play out; a faster hunter that can see the
			// fleeing prey should land bites (or a kill) within the window.
			for (int i = 0; i < 3000 && !prey.isDead(); i++) {
				tick(w, 1);
			}
			snapshot(w, "after the chase");
			assertTrue("the hunter ran down the fleeing prey and killed it", prey.isDead());
		}
	}

	// ---- runner ------------------------------------------------------------

	/**
	 * Every generated world — at the deployed size and larger, across seeds — must
	 * be a single connected walkable space: mainland, every other surface region,
	 * and the cave all reachable from the mainland by land and two-way links
	 * ({@link net.hedinger.prototype.sim.WorldAudit}'s directed flood). Guards the
	 * world-gen linking so a resize can never strand an area.
	 */
	/**
	 * The surface has its ravine, and the ravine does not cut the world in two.
	 *
	 * <p>The gorge is the largest connected blob of surface holes — the link
	 * stations' pits are one or two tiles, a ravine segment between causeways
	 * is twenty-odd, so a floor of 18 separates them cleanly. Both halves
	 * matter: that a gorge exists at all (the siting scan could quietly fail
	 * on every probe and nothing else would notice), and that ground on one
	 * bank can reach ground on the other WITHOUT leaving the surface — the
	 * causeways are the load-bearing part of the design, because the ravine
	 * is carved after connectLevels certified the world whole, and severed
	 * banks would fail no audit that still runs.
	 */
	static class TheRavineIsCutButTheWorldHolds extends Scenario {
		@Override
		public void run() {
			for (long s : new long[] { 1, 9, 42, 415, 777 }) {
				World w = net.hedinger.prototype.sim.Worlds.demoTerrain(s);
				int z = w.getLevels() - 1;
				int cols = w.getColums(), rows = w.getRows();
				java.util.List<int[]> gorge = largestHoleBlob(w, z);
				assertLess("seed " + s + ": the surface has a ravine", 17, gorge.size());

				// One bank tile on each side of some gorge tile, then a flood
				// over surface walkables only: falling in is not a route.
				int ax = -1, ay = -1, bx = -1, by = -1;
				for (int[] p : gorge) {
					if (ay < 0 && walkable(w, p[0], p[1] - 1, z)) {
						ax = p[0];
						ay = p[1] - 1;
					}
					int wy = p[1] + 1;
					while (wy < rows && isHole(w, p[0], wy, z)) {
						wy++;
					}
					if (by < 0 && wy < rows && walkable(w, p[0], wy, z)) {
						bx = p[0];
						by = wy;
					}
					if (ay >= 0 && by >= 0) {
						break;
					}
				}
				assertTrue("seed " + s + ": the gorge has ground on both banks",
						ay >= 0 && by >= 0);
				boolean[][] vis = new boolean[cols][rows];
				java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
				q.add(new int[] { ax, ay });
				vis[ax][ay] = true;
				boolean linked = false;
				while (!q.isEmpty() && !linked) {
					int[] p = q.poll();
					linked = p[0] == bx && p[1] == by;
					for (int k = 0; k < 4 && !linked; k++) {
						int nx = p[0] + (k == 0 ? 1 : k == 1 ? -1 : 0);
						int ny = p[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
						if (nx >= 0 && ny >= 0 && nx < cols && ny < rows && !vis[nx][ny]
								&& walkable(w, nx, ny, z)) {
							vis[nx][ny] = true;
							q.add(new int[] { nx, ny });
						}
					}
				}
				assertTrue("seed " + s + ": the banks meet again over a causeway,"
						+ " on the surface alone", linked);
			}
		}

		private static boolean isHole(World w, int x, int y, int z) {
			return w.getTile(x, y, z).getType() == Tile.TileType.TYPE_HOLE;
		}

		private static boolean walkable(World w, int x, int y, int z) {
			return w.isValid(x, y, z) && w.getTile(x, y, z).isWalkable();
		}

		private static java.util.List<int[]> largestHoleBlob(World w, int z) {
			int cols = w.getColums(), rows = w.getRows();
			boolean[][] seen = new boolean[cols][rows];
			java.util.List<int[]> best = new java.util.ArrayList<int[]>();
			for (int x = 0; x < cols; x++) {
				for (int y = 0; y < rows; y++) {
					if (seen[x][y] || !isHole(w, x, y, z)) {
						continue;
					}
					java.util.List<int[]> blob = new java.util.ArrayList<int[]>();
					java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
					q.add(new int[] { x, y });
					seen[x][y] = true;
					while (!q.isEmpty()) {
						int[] p = q.poll();
						blob.add(p);
						for (int k = 0; k < 4; k++) {
							int nx = p[0] + (k == 0 ? 1 : k == 1 ? -1 : 0);
							int ny = p[1] + (k == 2 ? 1 : k == 3 ? -1 : 0);
							if (nx >= 0 && ny >= 0 && nx < cols && ny < rows
									&& !seen[nx][ny] && isHole(w, nx, ny, z)) {
								seen[nx][ny] = true;
								q.add(new int[] { nx, ny });
							}
						}
					}
					if (blob.size() > best.size()) {
						best = blob;
					}
				}
			}
			return best;
		}
	}

	static class DemoWorldFullyConnected extends Scenario {
		@Override
		public void run() {
			long[] seeds = { 1, 7, 9, 25, 36, 42, 100, 777 };
			int[][] sizes = { { 72, 44 }, { 144, 88 }, { 96, 120 } };
			for (long s : seeds) {
				for (int[] wh : sizes) {
					World w = net.hedinger.prototype.sim.Worlds.demoTerrain(s, wh[0], wh[1]);
					net.hedinger.prototype.sim.WorldAudit.Connectivity c =
							net.hedinger.prototype.sim.WorldAudit.connectivity(w);
					assertTrue(wh[0] + "x" + wh[1] + " seed " + s + " fully connected (reached "
							+ c.reachable + "/" + c.walkable + ", "
							+ String.format("%.2f", c.coverage() * 100) + "%)", c.fullyConnected());
				}
			}
		}
	}

	/**
	 * A hand-injected creature is not silently deleted by the steward's population
	 * ceiling. The steward holds the minded cohort under a cap by removing surplus
	 * members outright ({@code Entity.remove()} — dead AND purged in one call, so
	 * the body blinks out leaving no corpse). A genome dropped into a world already
	 * at that cap was therefore liable to vanish within a second or two, healthy and
	 * well-fed: "I tap the ground and it disappears". A hand-placed founder is now
	 * exempt, so an injection displaces one of the steward's own instead.
	 *
	 * <p>Also guards the other half of the bargain: the cohort must stay bounded.
	 * The founder still counts toward the ceiling and its offspring are ordinary
	 * cullable citizens, so one injection must not let the population run away.
	 */
	static class InjectedCreatureSurvivesPopulationCeiling extends Scenario {
		@Override
		public void run() {
			seed(11);
			World w = net.hedinger.prototype.sim.Worlds.demo(11);
			// Settle until the minded cohort has actually saturated its (generous)
			// ceiling — otherwise the cull never fires and the test proves nothing.
			for (int i = 0; i < 2000; i++) {
				w.think();
			}
			// Donate the brain of the oldest proven survivor — what the viewer exports.
			Genome g = null;
			int bestAge = -1;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t.isMinded() && !t.isDead()
						&& t.getGenome() != null && t.getGenome().brain != null
						&& t.getAge() > bestAge) {
					bestAge = t.getAge();
					g = t.getGenome();
				}
			}
			assertTrue("world produced a minded donor genome", g != null);

			java.util.Set<Integer> before = new java.util.HashSet<>();
			for (Entity e : w.getEntities()) {
				before.add(e.getID());
			}
			int mindedBefore = countMinded(w);
			// Drop it on open ground far from the donor, exactly as a tap would.
			new net.hedinger.prototype.sim.SpawnMindedCommand(g, 20.5, 20.5, 0).apply(w);
			w.think();

			TestNPC seedling = null;
			for (Entity e : w.getEntities()) {
				if (!before.contains(e.getID()) && e instanceof TestNPC t
						&& t.getGenome() == g) {
					seedling = t; // the only body holding this exact Genome instance
				}
			}
			assertTrue("injected creature materialized", seedling != null);
			assertTrue("injected creature is marked hand-placed", seedling.isHandPlaced());

			// Long enough for the steward to run its cull many times over. A CULL is
			// removal while still healthy and fed — the steward deletes outright, so
			// the body is dead and purged in the same tick with its health intact.
			// Being eaten or starving is an ordinary ecological death (health or
			// energy gone first, corpse purged only a deathspan later) and must NOT
			// fail this test: predators are supposed to hunt minded creatures.
			boolean culledWhileHealthy = false;
			String fate = "survived all 400 ticks";
			for (int i = 0; i < 400; i++) {
				w.think(); // never break early: the cohort-bound check below needs the
				           // full window, and a corpse cannot be culled twice
				if (seedling.isRemoved() && seedling.getHealth() > 0
						&& seedling.getEnergy() > 0.001) {
					culledWhileHealthy = true; // healthy AND fed, yet purged: a cull
					fate = "culled at tick " + i;
				} else if (seedling.isDead() && fate.startsWith("survived")) {
					fate = seedling.getHealth() <= 0 ? "eaten at tick " + i
							: "starved at tick " + i;
				}
			}
			// Guard against a vacuous pass: if the creature vanished immediately the
			// cull check above would be trivially satisfied.
			assertTrue("the injected creature lived long enough to be a real test ("
					+ fate + ")", !seedling.isDead() || !fate.contains("tick 0"));
			assertTrue("hand-placed creature was never deleted by the population "
					+ "ceiling (" + fate + ")", !culledWhileHealthy);

			// The ceiling still bites: exempting founders must not let the cohort run
			// away, since only this one body is protected and its offspring are not.
			// Bound is the configured cap plus the overshoot the 3-per-tick trim
			// allows, NOT "no growth" — the cohort legitimately fills its headroom.
			assertTrue("minded cohort stayed bounded after the injection ("
					+ countMinded(w) + ", was " + mindedBefore + " before)",
					countMinded(w) <= 95);
		}

		private static int countMinded(World w) {
			int n = 0;
			for (Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && t.isMinded() && !t.isDead() && !t.isRemoved()) {
					n++;
				}
			}
			return n;
		}
	}

	/**
	 * A genome (brain included) survives a round-trip through the export/inject
	 * savefile form: encode → decode reproduces every field and every brain
	 * instruction exactly, the encoding carries no whitespace (so it survives the
	 * command log, which splits on it), the spawnMinded command parses back from
	 * its logged line, and injecting it actually drops a living creature into a
	 * world. Guards the genome-savefile feature end to end.
	 */
	static class GenomeSavefileRoundTrips extends Scenario {
		@Override
		public void run() {
			seed(7);
			Genome g = new Genome();
			g.size = 11.25;
			g.speed = 0.0523;
			g.turnRate = 6;
			g.losRange = 12.5;
			g.losFov = 1.234;
			g.metabolism = 0.019;
			g.maxAge = 2750;
			g.flying = true;
			g.markers = new double[] { 0.1, 0.55, 0.9 };
			g.predatory = 0.37;
			g.xenophobia = 0.11;
			g.gregariousness = -0.4;
			g.boldness = -0.2;
			g.mateThreshold = 0.66;
			g.brain = new Brain(new int[][] { { 1, 1, 9, 0 }, { 13, 1, 1, 0 }, { 14, 2, 8, 0 },
					{ 3, 5, 2, 6 } });

			String enc = net.hedinger.prototype.entities.GenomeCodec.encode(g);
			assertTrue("encoding carries no whitespace", !enc.matches("(?s).*\\s.*"));
			Genome back = net.hedinger.prototype.entities.GenomeCodec.decode(enc);
			assertTrue("size round-trips exactly", g.size == back.size);
			assertTrue("speed round-trips exactly", g.speed == back.speed);
			assertTrue("losFov round-trips exactly", g.losFov == back.losFov);
			assertTrue("metabolism round-trips exactly", g.metabolism == back.metabolism);
			assertTrue("predatory round-trips exactly", g.predatory == back.predatory);
			assertTrue("boldness round-trips exactly", g.boldness == back.boldness);
			assertTrue("mateThreshold round-trips exactly", g.mateThreshold == back.mateThreshold);
			assertTrue("flying round-trips", back.flying);
			assertEquals("maxAge round-trips", g.maxAge, back.maxAge);
			assertEquals("turnRate round-trips", g.turnRate, back.turnRate);
			assertTrue("markers round-trip", back.markers.length == 3 && g.markers[0] == back.markers[0]
					&& g.markers[1] == back.markers[1] && g.markers[2] == back.markers[2]);

			assertTrue("brain survives", back.brain != null);
			int[][] a = g.brain.code(), b = back.brain.code();
			assertEquals("brain instruction count", a.length, b.length);
			boolean cells = true;
			for (int i = 0; i < a.length; i++) {
				if (a[i].length != b[i].length) {
					cells = false;
					break;
				}
				for (int j = 0; j < a[i].length; j++) {
					if (a[i][j] != b[i][j]) {
						cells = false;
					}
				}
			}
			assertTrue("every brain instruction round-trips", cells);
			assertTrue("re-encoding is stable", enc.equals(
					net.hedinger.prototype.entities.GenomeCodec.encode(back)));

			// The command-log form: describe() -> fromDescribe() -> same command.
			net.hedinger.prototype.sim.SimCommand cmd =
					new net.hedinger.prototype.sim.SpawnMindedCommand(g, 15, 15, 0);
			net.hedinger.prototype.sim.SimCommand parsed =
					net.hedinger.prototype.sim.SimCommands.fromDescribe(cmd.describe());
			assertTrue("spawnMinded parses back from its log line",
					parsed instanceof net.hedinger.prototype.sim.SpawnMindedCommand);
			assertTrue("command re-describe is stable", cmd.describe().equals(parsed.describe()));

			// And injecting it drops exactly one living creature into an empty room.
			World room = room(30, 30);
			parsed.apply(room);
			room.think();
			assertEquals("injection admitted exactly one creature", 1, room.getAliveCount());

			// It is born brand-new: a FULL tank (not the ecosystem's 0.6-capacity
			// default), giving a hand-placed seed the longest runway before metabolism
			// can starve it. Energy is read after the one tick that materialized it, so
			// it sits a hair below a literal full tank; 0.9 cleanly separates "born
			// full" from the old 0.6 start.
			net.hedinger.prototype.entities.NPC seed = null;
			for (net.hedinger.prototype.engine.Entity e : room.getEntities()) {
				if (e instanceof net.hedinger.prototype.entities.NPC n && !n.isDead()) {
					seed = n;
				}
			}
			assertTrue("injected seed materialized", seed != null);
			assertGreater("injected seed born at a full tank", seed.getEnergy(),
					0.9 * seed.energyCapacity());

			// A tap on a wall snaps to open ground rather than dying on it: (0,0) is
			// the room's border wall, so a land body dropped there would try to climb,
			// run off the top level and die instantly without the snap.
			new net.hedinger.prototype.sim.SpawnMindedCommand(g, 0, 0, 0).apply(room);
			for (int i = 0; i < 6; i++) {
				room.think();
			}
			assertEquals("wall-tap injection snapped to open ground and survived", 2,
					room.getAliveCount());
		}
	}

	/**
	 * Water as a need: a parched grazer drops everything, walks to the shore,
	 * and drinks itself back above the thirst line — the scripted species'
	 * water drive, plus the body's sip-by-adjacency refill.
	 */
	static class ThirstyGrazerWalksToWater extends Scenario {
		@Override
		public void run() {
			seed(93);
			World w = room(20, 12);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 11; y++) {
					w.getTile(x, y, 0).setFertility(1.0);
				}
			}
			for (int y = 1; y < 11; y++) {
				w.setTile(17, y, 0, Tile.TileType.TYPE_SHALLOWS); // the east shore
				w.setTile(18, y, 0, Tile.TileType.TYPE_WATER);
			}
			tick(w, 1);
			TestNPC g = TestNPC.breeder(3.5, 6.5, 0,
					Genome.phenotype(6, 0.05, 5, 6, Math.PI / 2, 1_000_000))
					.withHydration(0.2).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			tick(w, 1500);
			assertTrue("the parched grazer is still alive", !g.isDead() && !g.isRemoved());
			assertGreater("it walked to the shore and drank itself back over the thirst line",
					g.getHydration(), 0.3);
		}
	}

	/**
	 * A thirsty body walks ROUND a wall to reach water rather than into it.
	 *
	 * <p>The lake sits a couple of tiles east behind a solid rib, with the only
	 * way in around the rib's south end. Straight-line water sense sends the
	 * creature at the nearest wet tile and it presses the rock until it dies of
	 * thirst with a drink two tiles away; measured across the demo world, between
	 * 15 and 28 per cent of every thirsty tick was spent like that.
	 */
	static class ThirstWalksRoundAWallNotIntoIt extends Scenario {
		@Override
		public void run() {
			seed(95);
			World w = room(20, 12);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 11; y++) {
					w.getTile(x, y, 0).setFertility(1.0);
				}
			}
			// A lake down the east edge, and a rib of rock hiding it. The rib stops
			// short of the south wall, so there is a way round -- the long way.
			for (int y = 1; y < 11; y++) {
				w.setTile(17, y, 0, Tile.TileType.TYPE_SHALLOWS);
				w.setTile(18, y, 0, Tile.TileType.TYPE_WATER);
			}
			for (int y = 1; y <= 8; y++) {
				w.setTile(14, y, 0, Tile.TileType.TYPE_WALL);
			}
			tick(w, 1);

			// Started level with the rib, so the nearest wet tile is dead ahead
			// through the rock and the way in is well off to the south.
			TestNPC g = TestNPC.breeder(11.5, 3.5, 0,
					Genome.phenotype(6, 0.05, 5, 6, Math.PI / 2, 1_000_000))
					.withHydration(0.2).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			snapshot(w, "parched, with the lake behind a rib of rock");
			tick(w, 2500);
			snapshot(w, "after");

			assertTrue("the parched grazer is still alive", !g.isDead() && !g.isRemoved());
			assertGreater("it found its way round the rib and drank",
					g.getHydration(), 0.3);
			assertGreater("which meant getting past the rock, not standing at it",
					g.getX(), 14.0);
		}
	}

	/**
	 * A minded body ambling flat into a wall is shaken loose by the body's pin
	 * reflex rather than shoving the rock forever. The reflex's "is it trying
	 * to move" gate once sat at throttle &gt; 0.3 — above the 0.25 amble the
	 * starter brain cruises at — which quietly disarmed it for the whole seeded
	 * lineage: any wanderer that aimed at a wall stayed pressed there for good
	 * (measured on the demo world, 4.2% of all thirsty minded ticks were spent
	 * pinned like that, usually with water just beyond the rock). The gate now
	 * matches the 0.02 threshold that engages movement at all; only a mind that
	 * is deliberately still is exempt from the reflex.
	 */
	static class AmblingMindIsShakenOffAWall extends Scenario {
		@Override
		public void run() {
			seed(97);
			World w = room(16, 9);
			Genome g = new Genome();
			g.size = 6;
			Mind ctrl = new Mind() {
				@Override
				public void think(double[] s, double[] a) {
					a[AgentIO.A_THROTTLE] = 0.25; // the starter lineage's amble
					a[AgentIO.A_SEEK] = 0;
					a[AgentIO.A_TURN] = 0; // never steers: a degenerate straight line
				}
			};
			TestNPC body = TestNPC.minded(8.5, 2.5, 0, g, ctrl).withSpeed(0.08)
					.withHeading(-Math.PI / 2); // due north: one open tile, then wall
			w.spawnEntity(body);
			w.think();
			// Path length is the discriminator: a body pinned at the wall stops
			// accumulating any (the ~1.4-tile approach and nothing more), while
			// the reflex keeps shaking it loose so it never stops walking. The
			// endpoint is no use — the never-steering amble bounces wall to wall,
			// so where it stands at any one tick says nothing.
			double px = body.getX(), py = body.getY(), path = 0;
			for (int i = 0; i < 600; i++) {
				tick(w, 1);
				path += Math.hypot(body.getX() - px, body.getY() - py);
				px = body.getX();
				py = body.getY();
			}
			assertGreater("the pin reflex kept the amble moving — never pressed for good",
					path, 5.0);
		}
	}

	/**
	 * The VITALS core: an empty tank is collapse, never death. A starving body
	 * that runs its energy dry lies where it is, still alive — and once fed
	 * again, satiation regenerates the tank and it gets back up. What kills is
	 * health, and health takes minutes of pegged need to erode.
	 */
	static class CollapseIsNotDeath extends Scenario {
		@Override
		public void run() {
			seed(97);
			World w = room(8, 8);
			for (int x = 1; x < 7; x++) {
				for (int y = 1; y < 7; y++) {
					w.getTile(x, y, 0).setFertility(0); // barren: nothing to eat
				}
			}
			// Starving (no regeneration) with a sliver of reserve: the resting burn
			// drains it to zero within the window.
			TestNPC g = TestNPC.breeder(4.5, 4.5, 0, new Genome())
					.withHunger(1.0).withEnergy(0.05).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			w.think();
			tick(w, 400);
			assertNear("the tank ran dry", 0.0, g.getEnergy(), 1e-9);
			assertTrue("and the collapsed body is still alive", !g.isDead());
			// A meal arrives: satiation switches regeneration back on and the body
			// recovers — collapse was a state, not a sentence.
			g.feed(10.0);
			tick(w, 400);
			assertGreater("fed again, the body regenerated energy", g.getEnergy(), 0.05);
			assertTrue("and lives on", !g.isDead());
		}
	}

	/**
	 * The rhythm anchor (VITALS.md §5): at rest, thirst runs at twice hunger's
	 * rate, so a body sated and slaked at the same instant wants water in half
	 * the time it takes to want food. "At rest" is now part of the statement:
	 * hunger has no clock of its own — appetite arrives as regeneration drains
	 * the stomach — so the ratio holds by the STOMACH identity only while the
	 * body spends nothing but its resting burn. And because the worse need
	 * gates regeneration, a parching body stops digesting and its hunger
	 * stalls, so one dry body cannot measure both clocks any more: the hunger
	 * leg is read off a watered body (thirst held at zero by the shore sip)
	 * and the thirst leg off a dry one, both motionless with full tanks.
	 */
	static class AppetiteReturnsAtHalfThirstsPace extends Scenario {
		@Override
		public void run() {
			seed(98);
			World w = room(10, 8);
			for (int x = 1; x < 9; x++) {
				for (int y = 1; y < 7; y++) {
					w.getTile(x, y, 0).setFertility(0); // nothing to eat
				}
			}
			w.setTile(1, 3, 0, Tile.TileType.TYPE_SHALLOWS); // the drinker's shore
			// Brainless genomes -> inert minds: metabolic bodies that never move.
			// Tanks full, so the mint only covers the resting burn.
			TestNPC drinker = TestNPC.brainedBreeder(2.5, 3.5, 0, new Genome())
					.withEnergy(4.5).withReproCooldown(100_000_000);
			TestNPC dry = TestNPC.brainedBreeder(7.5, 3.5, 0, new Genome())
					.withEnergy(4.5).withReproCooldown(100_000_000);
			w.spawnEntity(drinker);
			w.spawnEntity(dry);
			w.think();
			int thirstAt = -1, hungerAt = -1;
			for (int t = 1; t <= 12000 && hungerAt < 0; t++) {
				tick(w, 1);
				if (thirstAt < 0 && dry.getThirst() >= 0.5) {
					thirstAt = t;
				}
				if (hungerAt < 0 && drinker.getHunger() >= 0.5) {
					hungerAt = t;
				}
			}
			assertGreater("thirst crossed the seek line", thirstAt, 0);
			assertGreater("hunger crossed it too", hungerAt, 0);
			assertNear("appetite returned in twice the time thirst did",
					2.0, hungerAt / (double) thirstAt, 0.1);
		}
	}

	/**
	 * The conservation law: energy is food-backed. Regeneration converts the
	 * stomach's contents 1:1, so a body can never bank more than it actually
	 * ate — the mint drains the meal it is minted from. Before this held, the
	 * tank refilled from the mere state of being fed at ~12 units of energy
	 * per unit of food (REGEN_RATE * HUNGER_PERIOD / old stomach), and the
	 * herd evolved straight into the seam: triple-pace metabolisms banked
	 * surplus three times faster while grass stayed almost free, and the
	 * population exploded on land it had visibly stripped. Two legs pin the
	 * repair: a meal bounds the surplus it can ever become, and a fast
	 * metabolism now buys appetite, not offspring.
	 */
	static class EnergyIsFoodBacked extends Scenario {
		@Override
		public void run() {
			seed(101);

			// 1) One meal, one body, nothing else: the tank may never rise by
			// more than the meal. Motionless (inert mind), watered (shore sip
			// keeps thirst from stalling digestion), starting hungry with a
			// near-empty tank. Under the satiation-state mint this crossed the
			// meal's worth within a few hundred ticks on its way to a full tank.
			World w = room(10, 8);
			for (int x = 1; x < 9; x++) {
				for (int y = 1; y < 7; y++) {
					w.getTile(x, y, 0).setFertility(0); // no grazing income
				}
			}
			w.setTile(1, 3, 0, Tile.TileType.TYPE_SHALLOWS);
			TestNPC g = TestNPC.brainedBreeder(2.5, 3.5, 0, new Genome())
					.withHunger(1.0).withEnergy(0.5).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			w.think();
			double meal = 2.0;
			g.feed(meal);
			double maxEnergy = g.getEnergy();
			// 6000 ticks: the drain is satiation-gated, so the stomach empties on
			// an exponential tail (~4200-tick time constant from this hunger).
			for (int t = 0; t < 6000; t++) {
				tick(w, 1);
				maxEnergy = Math.max(maxEnergy, g.getEnergy());
			}
			assertTrue("the tank never banked more than the meal was worth",
					maxEnergy < 0.5 + meal);
			assertGreater("and the mint drained the meal it was minted from "
					+ "(appetite returned as the stomach emptied)", g.getHunger(), 0.9);

			// 2) The evolved exploit is dead: on the same lush grass, a
			// triple-pace metabolism outruns what a stomach can digest, lands
			// hungry past the breeding gate, and stalls — while a reference
			// burner keeps its surplus real and multiplies. Under the old mint
			// the fast burner was strictly better, and selection knew it.
			World slowW = room(12, 12);
			World fastW = room(12, 12);
			slowW.setTile(1, 1, 0, Tile.TileType.TYPE_SHALLOWS); // a shore each:
			fastW.setTile(1, 1, 0, Tile.TileType.TYPE_SHALLOWS); // dying of thirst
			// is not the thing under measurement, breeding on grass is
			Genome slow = new Genome();
			slow.sexuality = 0.3; // budders: reproduction needs no partner
			Genome fast = new Genome();
			fast.sexuality = 0.3;
			fast.metabolism = 0.06; // the herd's evolved triple pace
			slowW.spawnEntity(TestNPC.breeder(6.5, 6.5, 0, slow));
			fastW.spawnEntity(TestNPC.breeder(6.5, 6.5, 0, fast));
			slowW.think();
			fastW.think();
			// 16000 ticks, not 8000: children are born holding what their parent
			// paid less their body now, so a lineage compounds more slowly and
			// the thrifty line needs the longer race to pull clearly ahead.
			tick(slowW, 16000);
			tick(fastW, 16000);
			snapshot(slowW, "reference metabolism: a real, food-backed surplus");
			snapshot(fastW, "triple pace: appetite, not offspring");
			assertGreater("the reference burner multiplied past the fast one — "
					+ "pace of life now buys appetite, not offspring",
					slowW.getAliveCount(), fastW.getAliveCount());
		}
	}

	/**
	 * Birth conserves energy: a child's tank endowment plus its meat-priced
	 * body can never exceed what its parents paid for it. The audit is the
	 * cannibal round trip — a parent that ate its own just-born child would
	 * lose the tank and the stomach with the death and get the body back at
	 * its meat price, so the loop can never profit. Before this held, a bud
	 * was born holding 0.6 of a tank its parent paid 0.5 for, a full stomach
	 * nobody paid for at all (a free childhood of mintable food, and the
	 * bigger of the two grants), and a body on top; a lineage of budders was
	 * a perpetual-motion machine. Also pins the sexual clamp: a pair
	 * pays twice, and the surplus is spent on the act rather than banked —
	 * a newborn still arrives below the breeding line, never fertile at birth.
	 */
	static class NoFreeEnergyAtBirth extends Scenario {
		@Override
		public void run() {
			seed(102);

			// Asexual: one budder on lush grass; read the firstborn's books on
			// the tick it appears (at most one tick of its own living drifts
			// them, inside the epsilon).
			World w = room(12, 12);
			Genome g = new Genome();
			g.sexuality = 0.3; // a budder
			TestNPC parent = TestNPC.breeder(6.5, 6.5, 0, g).withEnergy(4.5);
			w.spawnEntity(parent);
			w.think();
			TestNPC bud = null;
			for (int t = 0; t < 4000 && bud == null; t++) {
				tick(w, 1);
				for (Entity e : w.getEntities()) {
					if (e instanceof TestNPC n && !n.isDead() && n.generation() == 1) {
						bud = n;
						break;
					}
				}
			}
			assertTrue("a bud arrived", bud != null);
			double budWorth = bud.getEnergy() + TestNPC.MEAT_ENERGY * bud.bodyMass()
					+ (1 - bud.getHunger()) * NPC.STOMACH * (bud.getGenome().size / NPC.REF_SIZE);
			assertTrue("the bud's tank, its birth meal and its meat-priced body ("
					+ String.format("%.2f", budWorth) + ") is at most what the parent paid ("
					+ String.format("%.2f", parent.reproCost()) + ")",
					budWorth <= parent.reproCost() + 0.01);
			assertGreater("and the bud is born viable, not bankrupt", bud.getEnergy(), 0.5);
			assertGreater("born hungry — its first act is a meal, not a free childhood",
					bud.getHunger(), 0.8);
			assertTrue("but under the deprivation line, so being born does not hurt",
					bud.getHunger() < NPC.DEPRIVED);

			// Sexual: the pair pays twice, the child's books still balance, and
			// the born-fed clamp keeps it below the breeding line.
			World m = room(12, 12);
			Genome ga = new Genome();
			ga.markers = new double[] { 0.5, 0.5, 0.5 };
			Genome gb = new Genome();
			gb.markers = new double[] { 0.5, 0.5, 0.5 };
			TestNPC pa = TestNPC.mater(6.3, 6.5, 0, ga).withEnergy(4.4);
			TestNPC pb = TestNPC.mater(6.7, 6.5, 0, gb).withEnergy(4.4);
			m.spawnEntity(pa);
			m.spawnEntity(pb);
			m.think();
			TestNPC kid = null;
			for (int t = 0; t < 4000 && kid == null; t++) {
				tick(m, 1);
				for (Entity e : m.getEntities()) {
					if (e instanceof TestNPC n && !n.isDead() && n.generation() == 1) {
						kid = n;
						break;
					}
				}
			}
			assertTrue("the pair bred", kid != null);
			double kidWorth = kid.getEnergy() + TestNPC.MEAT_ENERGY * kid.bodyMass()
					+ (1 - kid.getHunger()) * NPC.STOMACH * (kid.getGenome().size / NPC.REF_SIZE);
			assertTrue("the child's tank, its birth meal and its meat-priced body ("
					+ String.format("%.2f", kidWorth) + ") is at most what both parents paid ("
					+ String.format("%.2f", pa.reproCost() + pb.reproCost()) + ")",
					kidWorth <= pa.reproCost() + pb.reproCost() + 0.01);
			assertTrue("and a well-funded birth is still born below the breeding line",
					kid.getEnergy() < TestNPC.REPRO_FRACTION * kid.energyCapacity());
		}
	}

	/**
	 * Growing up is paid for: new flesh is matter, bought from the tank at the
	 * same {@link NPC#MEAT_ENERGY} an eater would collect for it — so rearing a
	 * body and eating it can never mint energy between them, and a growing
	 * child is hungrier than an adult of the same current size (the mint
	 * refills what growth spends, and the mint drains the stomach). Three
	 * legs, on motionless bodies so the books are pure: the flesh shows up as
	 * energy leaving the grower's ledger; the grower eats through its reserves
	 * far faster than a same-size grown body beside it; and a destitute
	 * juvenile stops growing before it stops living — growth yields to
	 * survival at the crawl reserve, so childhood stretches with scarcity
	 * instead of starving its owner.
	 */
	static class GrowingUpIsPaidFor extends Scenario {
		@Override
		public void run() {
			seed(103);
			// Barren, shored rooms: no food income, no thirst in the books —
			// every number below moves only by burn, mint and growth.
			World gw = room(10, 8);
			World aw = room(10, 8);
			for (int x = 1; x < 9; x++) {
				for (int y = 1; y < 7; y++) {
					gw.getTile(x, y, 0).setFertility(0);
					aw.getTile(x, y, 0).setFertility(0);
				}
			}
			gw.setTile(1, 3, 0, Tile.TileType.TYPE_SHALLOWS);
			aw.setTile(1, 3, 0, Tile.TileType.TYPE_SHALLOWS);

			// The grower: a size-16 genome caught mid-childhood at current size 6.
			// The grown: a size-6 genome that IS its adult body. Same current
			// size, same burn, same movement (none) — growth is the difference.
			Genome big = new Genome();
			big.size = 16;
			TestNPC grower = TestNPC.brainedBreeder(2.5, 3.5, 0, big)
					.withGrowth(16).withEnergy(7.2).withReproCooldown(100_000_000);
			Genome same = new Genome(); // size 6
			TestNPC grown = TestNPC.brainedBreeder(2.5, 3.5, 0, same)
					.withEnergy(4.5).withReproCooldown(100_000_000);
			gw.spawnEntity(grower);
			aw.spawnEntity(grown);
			gw.think();
			aw.think();
			double e0 = grower.getEnergy(), m0 = grower.maturity();
			tick(gw, 3000);
			tick(aw, 3000);

			// 1) The ledger: what left the grower's books (starting energy plus
			// everything minted from its stomach, less what it still holds) is
			// at least the meat price of the flesh it put on.
			double minted = grower.getHunger() * NPC.STOMACH * (16.0 / NPC.REF_SIZE);
			double flesh = NPC.MEAT_ENERGY * (grower.maturity() - m0) * 16.0 / NPC.REF_SIZE;
			assertGreater("it grew", grower.maturity(), m0 + 0.1);
			assertGreater("and the flesh was paid for out of the books ("
					+ String.format("%.2f", e0 + minted - grower.getEnergy())
					+ " spent, flesh worth " + String.format("%.2f", flesh) + ")",
					e0 + minted - grower.getEnergy(), flesh - 1e-6);

			// 2) The appetite: the growing body drained far more of its stomach
			// than the same-size grown body beside it — and is the hungrier.
			double grownMinted = grown.getHunger() * NPC.STOMACH * (6.0 / NPC.REF_SIZE);
			assertGreater("a growing child eats through its reserves more than "
					+ "twice as fast as a grown body of the same size",
					minted, 2 * grownMinted);
			assertGreater("and is the hungrier of the two",
					grower.getHunger(), grown.getHunger());

			// 3) Growth yields to survival: a destitute juvenile — empty stomach,
			// tank at the crawl floor — stops growing almost at once, and is
			// still alive long after; scarcity stretches childhood, it does not
			// kill through growth.
			World pw = room(10, 8);
			pw.setTile(1, 3, 0, Tile.TileType.TYPE_SHALLOWS);
			Genome pauperG = new Genome();
			pauperG.size = 16;
			TestNPC pauper = TestNPC.brainedBreeder(2.5, 3.5, 0, pauperG)
					.withGrowth(16).withEnergy(0.7).withHunger(1.0)
					.withReproCooldown(100_000_000);
			pw.spawnEntity(pauper);
			pw.think();
			tick(pw, 2000);
			assertTrue("the destitute juvenile stopped growing near birth size",
					pauper.maturity() < 0.4);
			assertTrue("but starvation did not kill it through growth — it is "
					+ "alive to eat its way out", !pauper.isDead());
		}
	}

	/**
	 * The constants registry and the tune command: every public static
	 * primitive on the surveyed classes is listed with its live value, and
	 * the runtime-tunable ones change ONLY through a logged command — so
	 * {@code seed + log} still reproduces a tuned world, a replayed log
	 * re-tunes itself without leaking into the live world's JVM-global
	 * statics, and frozen constants (structural anchors, values copied out
	 * at build time) refuse to pretend they are editable.
	 */
	static class TuningRidesTheCommandLog extends Scenario {
		@Override
		public void run() {
			seed(104);
			try {
				// The survey: a tunable, and two kinds of frozen — an anchor,
				// and a value tiles copy out at construction.
				var rows = net.hedinger.prototype.sim.Tuning.list();
				java.util.Map<String, java.util.Map<String, Object>> byKey = new java.util.HashMap<>();
				for (var r : rows) {
					byKey.put((String) r.get("key"), r);
				}
				assertGreater("the survey is broad", rows.size(), 40);
				assertTrue("grass is listed live and tunable",
						byKey.containsKey("NPC.GRASS_ENERGY")
						&& !(Boolean) byKey.get("NPC.GRASS_ENERGY").get("frozen")
						&& (Double) byKey.get("NPC.GRASS_ENERGY").get("value") == NPC.GRASS_ENERGY);
				assertTrue("the size anchor is frozen",
						(Boolean) byKey.get("NPC.REF_SIZE").get("frozen"));
				assertTrue("the regrow rate is frozen — tiles copied it at build",
						(Boolean) byKey.get("Tile.VEG_REGROW").get("frozen"));
				assertTrue("names are not quantities: the direction codes are "
						+ "not listed at all", !byKey.containsKey("Tile.DIR_N"));

				// A frozen constant refuses the command outright.
				boolean refused = false;
				try {
					new net.hedinger.prototype.sim.TuneCommand("NPC.REF_SIZE", 1);
				} catch (IllegalArgumentException e) {
					refused = true;
				}
				assertTrue("a frozen constant cannot be tuned", refused);

				// The command round-trips its logged form and takes effect.
				World w = room(6, 6);
				net.hedinger.prototype.sim.SimCommand c = net.hedinger.prototype.sim.SimCommands
						.fromDescribe("tune NPC.GRASS_ENERGY 0.5");
				assertTrue("the logged form parses back", c != null);
				c.apply(w);
				assertNear("and the constant moved", 0.5, NPC.GRASS_ENERGY, 1e-12);
				assertTrue("its logged form is stable",
						"tune NPC.GRASS_ENERGY 0.5".equals(c.describe()));

				// Replay isolation: a recording that tunes grass to 0.25 replays
				// under its own tuning, and hands the live world's back after.
				net.hedinger.prototype.sim.Tuning.set("NPC.GRASS_ENERGY", 0.6);
				var rec = new net.hedinger.prototype.sim.Recording(11, 3, java.util.List.of(
						new net.hedinger.prototype.sim.Recording.Entry(1,
								"tune NPC.GRASS_ENERGY 0.25")));
				net.hedinger.prototype.sim.Replays.reconstruct(rec, 3);
				assertNear("the replay's tuning did not leak into the live world",
						0.6, NPC.GRASS_ENERGY, 1e-12);
			} finally {
				// Statics are global to the suite: leave exactly what we found.
				net.hedinger.prototype.sim.Tuning.restoreDefaults();
			}
			assertNear("defaults restore the code-level value",
					0.75, NPC.GRASS_ENERGY, 1e-12);
		}
	}

	/**
	 * Vigor: health scales energy regeneration, so a wounded body is also a
	 * listless one — wounds and hunger compound instead of being independent
	 * ledgers. Two identical fed bodies, one badly hurt; the healthy one
	 * refills its tank decisively faster.
	 */
	static class HealthGatesEnergyRegeneration extends Scenario {
		@Override
		public void run() {
			seed(99);
			World w = room(10, 10);
			TestNPC whole = TestNPC.breeder(3.5, 3.5, 0, new Genome())
					.withEnergy(0.5).withReproCooldown(100_000_000);
			TestNPC hurt = TestNPC.breeder(6.5, 6.5, 0, new Genome())
					.withEnergy(0.5).withReproCooldown(100_000_000);
			w.spawnEntity(whole);
			w.spawnEntity(hurt);
			w.think();
			hurt.damage(80, "misadventure"); // health 20: vigor a fifth of whole
			tick(w, 400);
			assertGreater("the healthy body regenerated more energy than the wounded one",
					whole.getEnergy() - 0.5, (hurt.getEnergy() - 0.5) * 2);
		}
	}

	/**
	 * The parasite's living, end to end: a hungry parasite smells the much
	 * bigger host, walks to it on the forage intent, latches on with the shared
	 * attach machinery, and drains it — the host bleeds health with
	 * "parasites" as the harm while the parasite's stomach fills — and no
	 * grass anywhere is touched, because a parasite's mouth works on nothing
	 * but the body it rides.
	 */
	static class ParasiteLatchesAndDrainsItsHost extends Scenario {
		@Override
		public void run() {
			seed(83);
			World w = room(16, 10);
			Genome hostG = new Genome();
			hostG.size = 14;
			hostG.speed = 0; // a parked mountain of meat
			TestNPC host = TestNPC.breeder(8.5, 5.5, 0, hostG).withReproCooldown(100_000_000);
			Genome paraG = new Genome();
			paraG.size = 4;
			paraG.speed = 0.06;
			Mind ride = (sn, a) -> {
				a[AgentIO.A_SEEK] = 0.1; // forage: for a parasite, the nearest host
				a[AgentIO.A_THROTTLE] = 0.6;
				a[AgentIO.A_ATTACH] = 1; // latch the moment something bigger is in reach
			};
			TestNPC para = TestNPC.minded(4.5, 5.5, 0, paraG, ride)
					.withClade(Genome.Clade.PARASITE).withHunger(1.0);
			w.spawnEntity(host);
			w.spawnEntity(para);
			w.think();
			int hp0 = host.getHealth();
			tick(w, 900);
			assertTrue("the parasite is riding its host", para.getAttachTarget() == host);
			assertLess("and the host is being eaten away", host.getHealth(), hp0);
			assertGreater("what the host lost, the parasite swallowed",
					para.totalSwallowed(), 0.0);
			assertNear("nothing was grazed on the way", 0.0, para.totalIntake(), 1e-9);
		}
	}

	/**
	 * Rocky grassland is grazing country, just poor grazing country. It grows a
	 * real sward on the same regrowth model as the meadow — so a grazer can make
	 * a living on it — but its fertility caps that sward far below what pasture
	 * holds, and it is walkable, so the rocky skirt around the highlands is a
	 * frontier rather than a wall.
	 */
	static class RockyGroundFeedsAGrazerPoorly extends Scenario {
		@Override
		public void run() {
			seed(37);
			World w = room(10, 8);
			for (int x = 1; x < 9; x++) {
				for (int y = 1; y < 7; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_ROCKY);
					w.getTile(x, y, 0).setFertility(0.25);
				}
			}
			Tile rocky = w.getTile(4, 4, 0);
			assertTrue("rocky ground can be walked", rocky.isWalkable());
			assertTrue("rocky ground grows vegetation", rocky.growsVegetation());
			assertEquals("and the inspector can tell it from pasture",
					1, rocky.getType().label().equals("rocky grassland") ? 1 : 0);

			// A meadow tile of the same age, for the comparison that matters.
			World lush = room(10, 8);
			lush.getTile(4, 4, 0).setFertility(0.95);
			long t = 200_000;
			assertGreater("there is something growing on the rock",
					rocky.getVegetation(t), 0.0);
			assertLess("but far less than pasture holds",
					rocky.getVegetation(t), lush.getTile(4, 4, 0).getVegetation(t) * 0.5);

			// And a grazer standing on it actually eats: poor ground, not dead ground.
			TestNPC grazer = TestNPC.breeder(4.5, 4.5, 0, new Genome()).withHunger(1.0);
			w.spawnEntity(grazer);
			tick(w, 400);
			assertGreater("a grazer can make a living up here",
					grazer.totalIntake(), 0.0);
		}
	}

	/**
	 * The steward puts parasites back. Wipe the cohort out of a living world and
	 * it returns, because a herd of ordinary bodies is a herd of hosts.
	 *
	 * <p>This pins the precondition, which is where the niche actually died: the
	 * floor is conditional on {@code hostPresent()}, and that test compared a
	 * body's size in TILES against the parasite cap written in PIXELS. No body is
	 * a third of a tile across, so it answered "no host anywhere" in every world
	 * forever, the floor never fired once, and the cohort bled quietly to zero
	 * and stayed there — with every other parasite mechanism working perfectly.
	 * A silent precondition is the worst kind: nothing errors, the feature simply
	 * never happens.
	 */
	static class TheStewardPutsParasitesBack extends Scenario {
		@Override
		public void run() {
			seed(29);
			World w = net.hedinger.prototype.sim.Worlds.demo(29);
			tick(w, 400); // let the herds grow into adult bodies

			int wiped = 0;
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
						&& t.ecoRole().equals("parasite")) {
					t.kill();
					wiped++;
				}
			}
			assertGreater("the world had parasites to wipe out", wiped, 0);
			assertEquals("the cohort is empty", 0, countParasites(w));

			tick(w, 400);
			assertGreater("the steward seeded parasites back into the herd",
					countParasites(w), 0);
		}

		private static int countParasites(World w) {
			int n = 0;
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t && !t.isDead() && !t.isRemoved()
						&& t.ecoRole().equals("parasite")) {
					n++;
				}
			}
			return n;
		}
	}

	/**
	 * An ordinary grown body is big enough to ride, and a parasite is not. The
	 * units are the whole point: sizes come off the genome in PIXELS, and
	 * {@code getSize()} reports the same radius in TILES — a 48th of the number.
	 * Anything gating the niche on a size threshold has to say which it means.
	 */
	static class AGrownBodyIsBigEnoughToRide extends Scenario {
		@Override
		public void run() {
			seed(31);
			World w = room(12, 8);
			Genome g = new Genome();
			g.size = 12; // an ordinary adult grazer
			TestNPC host = TestNPC.breeder(4.5, 4.5, 0, g);
			TestNPC para = TestNPC.mindedParasite(6.5, 4.5, 0, new Genome());
			w.spawnEntity(host);
			w.spawnEntity(para);
			tick(w, TestNPC.growthTicks(g.size) + 50); // bodies grow into adult size

			assertGreater("a grown body clears the parasite cap, in pixels",
					host.getPixelSize(), TestNPC.PARASITE_MAX_SIZE_PX);
			assertTrue("a parasite does not — it is capped there by birth",
					para.getPixelSize() <= TestNPC.PARASITE_MAX_SIZE_PX);
			assertTrue("and in tiles that same body is under a third of one, so a "
					+ "tile-unit comparison against the cap would never be true",
					host.getSize() < 1.0);
		}
	}

	/**
	 * Predators ignore parasites outright — too small and too foul to be worth
	 * a bite. A hungry hunter with a parasite at its feet and real prey down
	 * the room walks past the parasite and takes the prey; the parasite is
	 * never so much as scratched, at any hunger.
	 */
	static class PredatorsIgnoreParasites extends Scenario {
		@Override
		public void run() {
			seed(84);
			World w = room(18, 9);
			Genome pg = new Genome();
			pg.size = 16;
			pg.speed = 0.06;
			TestNPC hunter = TestNPC.predator(5.5, 4.5, 0, pg)
					.withHunger(0.8).withReproCooldown(100_000_000);
			Genome paraG = new Genome();
			paraG.size = 5;
			Mind still = (sn, a) -> {
			};
			TestNPC para = TestNPC.minded(6.2, 4.5, 0, paraG, still)
					.withClade(Genome.Clade.PARASITE); // in reach, smaller: free lunch, refused
			Genome preyG = new Genome();
			preyG.size = 7;
			preyG.speed = 0;
			TestNPC prey = TestNPC.breeder(12.5, 4.5, 0, preyG); // the real meal, further off
			w.spawnEntity(hunter);
			w.spawnEntity(para);
			w.spawnEntity(prey);
			w.think();
			tick(w, 600);
			assertTrue("the hungry hunter went for the real prey",
					prey.isDead() || prey.getHealth() < 100);
			assertTrue("the parasite at its feet was never touched",
					!para.isDead() && para.getHealth() == 100);
		}
	}

	/**
	 * A parasite's mouth works on nothing but the body it rides: standing on
	 * lush grass with the eat actuator held high, it swallows nothing and the
	 * ground under it keeps every blade.
	 */
	static class AParasiteCannotGraze extends Scenario {
		@Override
		public void run() {
			seed(85);
			World w = room(10, 10);
			Genome g = new Genome();
			g.size = 4;
			Mind eat = (sn, a) -> a[AgentIO.A_EAT] = 1;
			TestNPC para = TestNPC.minded(5.5, 5.5, 0, g, eat)
					.withClade(Genome.Clade.PARASITE).withHunger(1.0);
			w.spawnEntity(para);
			w.think();
			double veg0 = w.getTile(5, 5, 0).getVegetation(w.getTick());
			tick(w, 300);
			assertNear("it grazed nothing", 0.0, para.totalIntake(), 1e-9);
			assertNear("and swallowed nothing", 0.0, para.totalSwallowed(), 1e-9);
			assertTrue("the grass under it kept every blade",
					w.getTile(5, 5, 0).getVegetation(w.getTick()) >= veg0 - 1e-9);
		}
	}

	/**
	 * Water the body cannot reach at all is not water. A creature walled off from
	 * every drop is told so — the sense reads empty rather than pointing hopefully
	 * through the rock — which is what lets it go and look somewhere else instead
	 * of standing at a wall for the rest of its life.
	 */
	static class UnreachableWaterIsNotSensed extends Scenario {
		@Override
		public void run() {
			seed(96);
			World w = room(16, 9);
			// A lake sealed off behind a full-height wall: no way in at all.
			for (int y = 1; y < 8; y++) {
				w.setTile(11, y, 0, Tile.TileType.TYPE_WALL);
				w.setTile(13, y, 0, Tile.TileType.TYPE_WATER);
			}
			tick(w, 1);

			assertTrue("water exists on this level",
					w.getTile(13, 4, 0).getType() == Tile.TileType.TYPE_WATER);
			assertEquals("but none of it can be reached from the open side", -1,
					w.waterStepDistance(4.5, 4.5, 0, false));
			assertEquals("and a body standing there is told nothing is near", -1,
					w.nearestWaterTile(4.5, 4.5, 0, false));
			// From inside the sealed strip the same water IS reachable, so the field
			// is answering "can you get there", not "is there any".
			assertGreater("while from the sealed side it is right there",
					w.waterStepDistance(12.5, 4.5, 0, false), -1);
		}
	}

	/**
	 * The other side of the need: in a world with no water at all, a fully fed
	 * grazer still declines and dies once its tank runs dry — dehydration is a
	 * slow wear on health, not a starvation clone (the full energy tank rules
	 * starving out as the cause on this timeline).
	 */
	static class DehydrationWearsABodyDown extends Scenario {
		@Override
		public void run() {
			seed(94);
			World w = room(16, 16);
			for (int x = 1; x < 15; x++) {
				for (int y = 1; y < 15; y++) {
					w.getTile(x, y, 0).setFertility(1.0); // lush — but bone dry
				}
			}
			tick(w, 1);
			TestNPC g = TestNPC.breeder(8.5, 8.5, 0,
					Genome.phenotype(6, 0.05, 5, 6, Math.PI / 2, 1_000_000))
					.withHydration(0.01).withEnergy(6.0).withReproCooldown(100_000_000);
			w.spawnEntity(g);
			// Deprivation erodes health a point every DEPRIVATION_PERIOD ticks, so a
			// pegged need takes ~5000 ticks to kill — slow enough that rescue is real.
			tick(w, 6000);
			assertTrue("with no water anywhere, dehydration wears the fed grazer down",
					g.isDead() || g.isRemoved());
		}
	}

	/**
	 * Nutrient closure: a corpse that rots away feeds the ground it lay on —
	 * tile fertility rises where the body expired, so kill sites green up and
	 * the grass-grazer-predator loop closes.
	 */
	static class CorpseFeedsTheGround extends Scenario {
		@Override
		public void run() {
			seed(95);
			World w = room(10, 10);
			for (int x = 1; x < 9; x++) {
				for (int y = 1; y < 9; y++) {
					w.getTile(x, y, 0).setFertility(0.3); // poor ground, so the gift shows
				}
			}
			double before = w.getTile(5, 5, 0).getFertility();
			TestNPC victim = TestNPC.inert(5.5, 5.5, 0).withSize(8).withDeathspan(40);
			w.spawnEntity(victim);
			tick(w, 2);
			victim.kill();
			tick(w, 60); // longer than the deathspan: the corpse rots away
			assertTrue("the corpse rotted away", victim.isRemoved());
			assertGreater("the ground it rotted on is richer than before",
					w.getTile(5, 5, 0).getFertility(), before + 0.05);
		}
	}

	// ---- the steward's drone -------------------------------------------------

	/**
	 * A cull order the scenarios can hold: it names a cohort and re-derives
	 * "is it done yet" from the live world every time it is asked, exactly as
	 * the real {@link net.hedinger.prototype.sim.WorldSteward} does.
	 *
	 * <p>Standing a whole ecosystem up to give the drone something to be told
	 * would make these tests slow and about the steward. The drone's entire
	 * contract with the steward is two scalars, so a scenario can supply them.
	 */
	static final class Order implements net.hedinger.prototype.sim.CullOrders {
		private final World w;
		private final String role;
		private final int target;

		Order(World w, String role, int target) {
			this.w = w;
			this.role = role;
			this.target = target;
		}

		@Override
		public String cullRole() {
			return standing() > target ? role : null;
		}

		@Override
		public int cullTarget() {
			return target;
		}

		int standing() {
			int n = 0;
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof TestNPC t
						&& net.hedinger.prototype.sim.WorldSteward.cohortOf(t).equals(role)) {
					n++;
				}
			}
			return n;
		}
	}

	/**
	 * The whole of the drone's job, end to end: flagged a cohort, it leaves its
	 * berth, hunts the surplus down, stops at the number it was given rather
	 * than at the last animal standing, and comes home to park.
	 *
	 * <p>Stopping at the number is the half worth pinning. A machine that kills
	 * until it runs out of things to kill is not a population control, and the
	 * order's target is the only thing between a cull and an extinction.
	 */
	static class DroneCullsToTargetAndReturnsToDock extends Scenario {
		@Override
		public void run() {
			seed(410);
			World w = room(30, 20);
			w.setTile(3, 3, 0, Tile.TileType.TYPE_DOCK);
			// Twelve grazers against a target of seven: five to take, and seven
			// that must still be standing when the drone goes home.
			for (int i = 0; i < 12; i++) {
				w.spawnEntity(TestNPC.breeder(10.5 + (i % 6) * 2, 8.5 + (i / 6) * 3, 0,
						Genome.phenotype(8, 0.05, 8, 4, Math.PI, 100000))
						.withReproCooldown(100000)); // no births mid-cull: pin the arithmetic
			}
			Order order = new Order(w, "herbivore", 7);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(3.5, 3.5, 0, order);
			w.spawnEntity(drone);
			w.think();
			snapshot(w, "before (12 grazers, order: leave 7)");
			assertTrue("the drone starts parked on its dock", drone.isBerthed());

			tick(w, 3000);
			snapshot(w, "after (culled to 7, drone back on the pad)");

			assertEquals("culled to exactly the ordered headcount", 7, order.standing());
			assertTrue("the order cleared once the target was met", order.cullRole() == null);
			assertTrue("the drone went home and parked", drone.isDocked());
			// Seated on the pad rather than drifted to a halt somewhere near it.
			// Not to the last decimal: the collision spring still nudges a
			// parked body when something wanders against it, and the drone
			// re-seats itself the next tick.
			assertLess("it is seated on the dock, not merely near it",
					Math.hypot(drone.getX() - 3.5, drone.getY() - 3.5), 0.05);
		}
	}


	/**
	 * The rank splits the cull: no two drones ever work on the same animal.
	 *
	 * <p>They berth on adjacent pads and take the same standing order in the
	 * same tick, and the choice is "the nearest cullable body" — which, from
	 * four machines standing two tiles apart, is the same animal for all four.
	 * Measured over twelve thousand ticks of the seeded world before this was
	 * fixed: all four held the identical target on 6962 of the 7721 ticks any of
	 * them held one, and on exactly ONE tick did all four hold different
	 * animals. Three machines flew escort to a fourth doing the work.
	 *
	 * <p>Nothing about that is visible in a headcount. The cull still finished,
	 * the order still cleared, every existing drone scenario still passed — it
	 * was simply four times the plant doing one drone's work, and the only way
	 * to see it was to ask what each machine had in hand.
	 *
	 * <p>Both halves are asserted. That the rank never doubles up is the fix;
	 * that it is seen holding four different animals at once is the guard
	 * against a test that passes because nothing was ever hunting.
	 */
	static class TheRankSplitsTheCull extends Scenario {
		@Override
		public void run() {
			seed(416);
			World w = room(34, 22);
			// Four pads in a block, as the machine wing lays them.
			double[][] pads = { { 3.5, 3.5 }, { 5.5, 3.5 }, { 3.5, 4.5 }, { 5.5, 4.5 } };
			for (double[] p : pads) {
				w.setTile((int) p[0], (int) p[1], 0, Tile.TileType.TYPE_DOCK);
			}
			// Enough grazers that four drones can always find four, spread wide
			// enough that "nearest" is a real choice rather than a formality.
			// No reproduction: a birth mid-cull would move the arithmetic.
			for (int i = 0; i < 20; i++) {
				w.spawnEntity(TestNPC.breeder(12.5 + (i % 5) * 4, 6.5 + (i / 5) * 4, 0,
						Genome.phenotype(8, 0.05, 8, 4, Math.PI, 100000))
						.withReproCooldown(100000));
			}
			Order order = new Order(w, "herbivore", 4);
			java.util.List<net.hedinger.prototype.sim.StewardDrone> rank =
					new java.util.ArrayList<>();
			for (double[] p : pads) {
				net.hedinger.prototype.sim.StewardDrone d =
						new net.hedinger.prototype.sim.StewardDrone(p[0], p[1], 0, order);
				rank.add(d);
				w.spawnEntity(d);
			}
			w.think();
			snapshot(w, "before (20 grazers, four drones on the rank)");

			int sawFourApart = 0, hunting = 0;
			for (int t = 0; t < 3000; t++) {
				tick(w, 1);
				java.util.List<Integer> held = new java.util.ArrayList<>();
				for (net.hedinger.prototype.sim.StewardDrone d : rank) {
					if (d.quarry() != null) {
						held.add(d.quarry().getID());
					}
				}
				if (held.isEmpty()) {
					continue;
				}
				hunting++;
				assertEquals("tick " + t + ": " + held.size() + " drones hold "
						+ held.size() + " different animals",
						held.size(), new java.util.HashSet<>(held).size());
				if (held.size() == net.hedinger.prototype.sim.Worlds.DRONE_RANK) {
					sawFourApart++;
				}
			}
			snapshot(w, "after (culled to four, the rank home)");
			assertLess("the rank actually hunted", 0, hunting);
			assertLess("and was seen working four animals at once", 0, sawFourApart);
			assertEquals("culled to exactly the ordered headcount", 4, order.standing());
		}
	}

	/**
	 * A zapped body is a remnant, not a carcass: it dies at once and arrives
	 * nine tenths decomposed, so what the cull leaves on the ground is worth
	 * almost nothing to the scavengers and is gone in a tenth of the time.
	 *
	 * <p>It still says what killed it. A corpse in this world explains itself,
	 * and "culled" is a cause like starvation or predation.
	 */
	static class ZappedBodyIsAlmostGone extends Scenario {
		@Override
		public void run() {
			seed(411);
			World w = room(20, 12);
			w.setTile(2, 2, 0, Tile.TileType.TYPE_DOCK);
			TestNPC victim = TestNPC.breeder(12.5, 6.5, 0,
					Genome.phenotype(8, 0.0, 8, 4, Math.PI, 100000)).withDeathspan(200);
			w.spawnEntity(victim);
			Order order = new Order(w, "herbivore", 0);
			w.spawnEntity(new net.hedinger.prototype.sim.StewardDrone(2.5, 2.5, 0, order));
			w.think();
			snapshot(w, "before (one grazer, one drone)");
			int died = 0;
			for (int t = 1; t <= 600 && !victim.isDead(); t++) {
				tick(w, 1);
				died = t;
			}
			snapshot(w, "after (a remnant, nine tenths gone)");

			assertTrue("the drone killed it", victim.isDead());
			assertEquals("the corpse says what killed it", "culled".hashCode(),
					victim.getDeathCause().hashCode());
			assertGreater("the remnant arrived almost fully decomposed",
					victim.decayProgress(), 0.85);
			// And so it clears in a tenth of the span an ordinary carcass gets:
			// a body given 200 ticks to rot is gone inside 30 of them.
			tick(w, 30);
			assertTrue("the remnant is gone almost at once", victim.isRemoved());
			assertLess("it did not take a full deathspan to get there", 30, 200 - died + 30.0);
		}
	}

	/**
	 * The drone is a machine, and nothing alive treats it as food. A hunter
	 * pressed up against one never bites it however hungry it gets, a parasite
	 * never rides it, and nothing that does try to hurt it leaves a mark —
	 * every one of which follows from the single answer the body gives to
	 * {@code isOrganic()}.
	 */
	static class NothingEatsTheDrone extends Scenario {
		@Override
		public void run() {
			seed(412);
			World w = room(14, 10);
			w.setTile(2, 2, 0, Tile.TileType.TYPE_DOCK);
			// No order: the drone sits on its pad and is simply an object in
			// the room, which is the situation being tested.
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(2.5, 2.5, 0, new Order(w, "herbivore", 99));
			w.spawnEntity(drone);
			// A starving hunter and a parasite, both parked against the drone —
			// as close as either could ever get to a meal.
			TestNPC hunter = TestNPC.predator(3.2, 2.5, 0,
					Genome.phenotype(9, 0.0, 10, 4, Math.PI, 100000)).withHunger(1.0);
			TestNPC rider = TestNPC.mindedParasite(2.5, 3.2, 0, Genome.random());
			w.spawnEntity(hunter);
			w.spawnEntity(rider);
			w.think();
			snapshot(w, "before (a starving hunter and a parasite, both touching it)");
			tick(w, 500);
			snapshot(w, "after (both went hungry; the drone is unmarked)");

			assertEquals("nothing bit the machine", 100, drone.getHealth());
			assertTrue("the parasite never latched onto it", rider.getAttachTarget() != drone);
			assertTrue("the hunter never picked it as prey", hunter.currentAction() != null);
			// The proof the hunter went hungry on a body it was standing on: it
			// is still starving after 500 ticks with "food" under its nose.
			assertGreater("the hunter got nothing out of it", hunter.getHunger(), 0.9);
		}
	}

	/**
	 * The base's doors open for the drone and for nothing else it has to do.
	 * A wired blast door with no switch pressed is shut to every creature in
	 * the world — the plates it runs on are weight-driven and a flyer is too
	 * light to trip one — and the drone crosses it anyway, because it carries
	 * the key to its own building.
	 */
	static class DoorsOpenForTheDrone extends Scenario {
		@Override
		public void run() {
			seed(413);
			World w = room(24, 9);
			for (int y = 1; y <= 7; y++) {
				w.setTile(12, y, 0, Tile.TileType.TYPE_WALL); // the partition
			}
			w.setTile(12, 4, 0, Tile.TileType.TYPE_PAVED); // the one doorway
			net.hedinger.prototype.entities.Door blast = new net.hedinger.prototype.entities.Door(
					12, 4, 0, 1, net.hedinger.prototype.entities.Door.BLAST);
			w.addDoor(blast);
			blast.setWired(true); // machinery with no switch: shut to everything alive
			w.setTile(3, 4, 0, Tile.TileType.TYPE_DOCK);

			TestNPC quarry = TestNPC.breeder(20.5, 4.5, 0,
					Genome.phenotype(8, 0.0, 8, 4, Math.PI, 100000));
			w.spawnEntity(quarry);
			// A creature of its own on the drone's side, to show the door is
			// genuinely shut to anything that is not the drone.
			TestNPC walker = TestNPC.mover(1.5, 6.5, 0, 0);
			w.spawnEntity(walker);
			Order order = new Order(w, "herbivore", 0);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(3.5, 4.5, 0, order);
			w.spawnEntity(drone);
			w.think();
			snapshot(w, "before (drone berthed west, quarry east, blast door shut)");
			assertTrue("the door starts sealed", blast.isClosed());

			double furthestEast = drone.getX();
			for (int t = 0; t < 1500; t++) {
				tick(w, 1);
				furthestEast = Math.max(furthestEast, drone.getX());
			}
			snapshot(w, "after (the drone crossed and came home; the walker never did)");

			assertTrue("the drone got through the door and killed its quarry", quarry.isDead());
			assertGreater("the drone crossed east of the partition", furthestEast, 12.0);
			assertLess("the creature was never let through", walker.getX(), 12.0);
			assertTrue("and the drone came back to its berth", drone.isDocked());
			assertTrue("the door shut again behind it", blast.isClosed());
		}
	}

	/**
	 * Ground too tight for the drone's frame is a refuge from it. The crawl
	 * ducts and shard beds already admit only small bodies — the engine refuses
	 * the step — and the router honours the same clearance, so the drone writes
	 * off what it cannot reach and gets on with the rest of the cohort instead
	 * of hovering over one animal for the rest of that animal's life.
	 */
	/**
	 * Nothing rides the drone.
	 *
	 * <p>The machine is armour plate and rotors: no blood to drink, nothing a claw
	 * can hold, and — since it flies and cannot be hurt — a free invulnerable taxi
	 * for whatever climbed on. The rule existed on the sensing side, in the host
	 * scan that feeds the seek intent, so a parasite would never go LOOKING for
	 * one. It was missing on the acting side, which is the half that actually
	 * latches: a mind raising A_ATTACH beside a drone got one regardless.
	 *
	 * <p>Driven by a scripted mind that simply holds "attach", so this pins the
	 * BODY's rule rather than whatever a starter brain happens to have evolved
	 * into — a random brain rarely raises the actuator at all, and a test that
	 * waited for one would pass by never trying.
	 */
	static class NothingRidesTheDrone extends Scenario {
		@Override
		public void run() {
			seed(5);
			World w = room(16, 12);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(6.5, 6.0, 0, new Order(w, "herbivore", 999));
			w.spawnEntity(drone);
			assertTrue("the drone is not a body", !drone.isOrganic());

			Mind latch = (sensors, act) -> act[AgentIO.A_ATTACH] = 1;
			Genome pg = new Genome();
			pg.clade = Genome.Clade.PARASITE;
			pg.size = 5;
			TestNPC para = TestNPC.minded(6.5, 6.4, 0, pg, latch);
			w.spawnEntity(para);
			tick(w, 1);
			assertTrue("the drone really is the larger body",
					drone.getSize() > para.getSize());

			tick(w, 300);
			assertTrue("a parasite never latches onto the machine",
					para.getAttachTarget() == null);
			assertEquals("and the drone carries nothing", 0,
					Math.round(drone.getCarriedLoad() * 1000));

			// The guard must refuse machines, not attachment: a parasite beside a
			// real host still rides. Without this the fix could be "nothing ever
			// attaches" and the scenario above would still pass.
			Genome hg = new Genome();
			hg.size = 16; // comfortably larger than the parasite
			TestNPC host = TestNPC.breeder(2.5, 2.5, 0, hg);
			w.spawnEntity(host);
			Genome pg2 = new Genome();
			pg2.clade = Genome.Clade.PARASITE;
			pg2.size = 5;
			TestNPC rider = TestNPC.minded(2.5, 2.9, 0, pg2, latch);
			w.spawnEntity(rider);
			tick(w, 1);
			assertTrue("the host really is the larger body", host.getSize() > rider.getSize());
			tick(w, 60);
			assertTrue("but it does ride a living host",
					rider.getAttachTarget() == host);
		}
	}

	static class DuctedBodyIsSafeFromTheDrone extends Scenario {
		@Override
		public void run() {
			seed(414);
			World w = room(24, 11);
			w.setTile(2, 2, 0, Tile.TileType.TYPE_DOCK);
			// A dead-end duct: the only way in is through ducting no frame the
			// drone's size will pass.
			for (int x = 17; x <= 21; x++) {
				for (int y = 1; y <= 9; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_WALL);
				}
			}
			w.setTile(18, 5, 0, Tile.TileType.TYPE_DUCT);
			w.setTile(19, 5, 0, Tile.TileType.TYPE_STONE); // the pocket beyond
			Genome small = Genome.phenotype(6, 0.0, 8, 4, Math.PI, 100000);
			TestNPC sheltered = TestNPC.breeder(19.5, 5.5, 0, small).withReproCooldown(100000);
			TestNPC exposed = TestNPC.breeder(8.5, 5.5, 0, small).withReproCooldown(100000);
			w.spawnEntity(sheltered);
			w.spawnEntity(exposed);
			Order order = new Order(w, "herbivore", 0); // kill everything it can
			w.spawnEntity(new net.hedinger.prototype.sim.StewardDrone(2.5, 2.5, 0, order));
			w.think();
			snapshot(w, "before (one grazer in the open, one down a duct)");
			tick(w, 2500);
			snapshot(w, "after (the open one is gone; the ducted one is not)");

			assertTrue("the grazer in the open was culled", exposed.isDead());
			assertTrue("the grazer down the duct was out of reach", !sheltered.isDead());
		}
	}



	/**
	 * The drone picks the animal on its own floor, even when one on the floor
	 * below is nearer as the engine measures.
	 *
	 * <p>{@code World.distance} is a straight line through all three axes and
	 * counts one level as one tile — the right reading for a world where a level
	 * is a height, and the wrong one for choosing what to fly at. A body
	 * directly underneath scores one tile away, nearer than something four tiles
	 * off across the same room, while actually reaching it means finding the
	 * stairwell, flying the length of the storage hall and coming back. The
	 * drone would set off after the near thing and spend the cull walking.
	 *
	 * <p>Here the off-floor animal is a third of a tile away and the on-floor
	 * one is seven tiles away, so the old metric picks the wrong one by a factor
	 * of twenty.
	 */
	static class TheDronePicksItsOwnFloorFirst extends Scenario {
		@Override
		public void run() {
			seed(418);
			World w = room(20, 12, 2);
			w.setTile(5, 5, 1, Tile.TileType.TYPE_DOCK);
			Genome plain = Genome.phenotype(8, 0.0, 8, 4, Math.PI, 100000);
			// Seven tiles away, on the drone's own floor.
			TestNPC sameFloor = TestNPC.breeder(12.5, 5.5, 1, plain)
					.withReproCooldown(100000);
			// A third of a tile away in the plane, one floor down.
			TestNPC belowIt = TestNPC.breeder(5.5, 5.8, 0, plain)
					.withReproCooldown(100000);
			w.spawnEntity(sameFloor);
			w.spawnEntity(belowIt);
			Order order = new Order(w, "herbivore", 0);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(5.5, 5.5, 1, order);
			w.spawnEntity(drone);
			w.think();
			snapshot(w, "before (one grazer across the room, one through the floor)");

			tick(w, 2);
			assertTrue("it went for the one on its own floor, not the one under it",
					drone.quarry() == sameFloor);
		}
	}

	/**
	 * The emitter does not fire through a floor.
	 *
	 * <p>Two things that each look like they would stop it, and neither does.
	 * Sight does not: {@code World.hasLOS} traces within the SOURCE level's
	 * plane and ignores the target's level entirely, so a body one floor down
	 * reads as in plain view. Distance does not: the engine counts a level as a
	 * tile, so a body directly underneath is one tile away, and the emitter
	 * reaches further than that against anything large.
	 *
	 * <p>Measured before the gate went in: a drone sitting on its dock shot a
	 * big grazer on the floor below, through the deck plate, without moving —
	 * which is also the one arrangement where nothing else would have saved it,
	 * since the drone never had to path anywhere to do it.
	 *
	 * <p>The animal is deliberately large. Reach scales with both bodies, so a
	 * big one is what brings a whole tile of floor inside the emitter; a mouse
	 * down there would be spared by arithmetic rather than by the rule.
	 */
	static class TheEmitterDoesNotFireThroughAFloor extends Scenario {
		@Override
		public void run() {
			seed(419);
			World w = room(20, 12, 2);
			w.setTile(10, 6, 1, Tile.TileType.TYPE_DOCK); // berth directly above it
			Genome big = Genome.phenotype(220, 0.0, 8, 4, Math.PI, 100000);
			TestNPC below = TestNPC.breeder(10.5, 6.5, 0, big).withReproCooldown(100000);
			w.spawnEntity(below);
			Order order = new Order(w, "herbivore", 0);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(10.5, 6.5, 1, order);
			w.spawnEntity(drone);
			w.think();
			snapshot(w, "before (a big grazer one floor below the drone's dock)");

			double radii = (drone.getSize() + below.getSize()) / 2.0;
			assertLess("the test is worth running: it is inside the emitter by distance",
					1.0 - radii, net.hedinger.prototype.sim.StewardDrone.strikeReach());

			tick(w, 1200);
			snapshot(w, "after (still there)");
			assertTrue("the grazer below was not shot through the deck", !below.isDead());
		}
	}

	/**
	 * The emitter fires at the range it claims, and not past it.
	 *
	 * <p>{@code STRIKE_REACH} has been moved twice and nothing checked that the
	 * number meant anything: the scenarios around it assert what the drone does
	 * NOT do — not through a floor, not into a duct — and would all still pass
	 * with the emitter reaching half as far. This asserts the constant against
	 * behaviour, so a change to it is a change with a test attached.
	 *
	 * <p>The prey is parked and held at a fixed gap, and the gap is stated as a
	 * fraction of {@link StewardDrone#strikeReach()} rather than as a distance,
	 * so retuning the constant retunes the scenario with it.
	 *
	 * <p>Holding the gap open is what makes this a measurement of reach rather
	 * than of the approach: the drone tests reach before it moves, so a body
	 * already inside is killed from a standstill, and a body outside is chased —
	 * which the held position prevents, leaving it alive.
	 */
	static class TheEmitterFiresAtTheRangeItClaims extends Scenario {
		/** Whether the drone kills prey pinned {@code past} beyond the two radii. */
		private boolean firesAt(double past) {
			seed(522);
			World w = room(24, 12);
			w.setTile(5, 5, 0, Tile.TileType.TYPE_DOCK);
			Genome g = new Genome();
			g.size = 10;
			g.speed = 0; // parked: the gap is the gap
			TestNPC prey = TestNPC.breeder(5.5, 5.5, 0, g).withReproCooldown(100000);
			Order order = new Order(w, "herbivore", 0);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(5.5, 5.5, 0, order);
			w.spawnEntity(prey);
			w.spawnEntity(drone);
			w.think();
			double radii = (drone.getSize() + prey.getSize()) / 2.0;
			for (int t = 0; t < 40 && !prey.isDead(); t++) {
				prey.setPos(drone.getX() + radii + past, drone.getY(), 0);
				tick(w, 1);
			}
			return prey.isDead();
		}

		@Override
		public void run() {
			double reach = net.hedinger.prototype.sim.StewardDrone.strikeReach();
			assertTrue("it fires at nine tenths of its stated reach",
					firesAt(reach * 0.9));
			assertTrue("and it does not fire at one and a tenth of it",
					!firesAt(reach * 1.1));
		}
	}

	/**
	 * The drone kills something that is walking away, not just something that
	 * stands still for it.
	 *
	 * <p>The emitter needs {@code CHARGE_TICKS} of unbroken contact, and the
	 * drone used to stop dead for all of them. Against anything that moves that
	 * is a way of guaranteeing the charge never finishes: the machine plants
	 * itself, the animal takes a step, contact breaks, the count goes back to
	 * zero, and it closes again to start over. Measured over twelve thousand
	 * ticks of the seeded world, of the 2115 separate approaches that got the
	 * drone into reach only 420 — one in five — lasted long enough to fire.
	 *
	 * <p>Which is exactly what it looks like from outside: a machine hovering
	 * beside an animal, plainly in range, plainly not shooting.
	 *
	 * <p>The parked case is the control, and it asserts the SEPARATION rather
	 * than the timing. Timing does not control anything here: a drone that
	 * simply closed every tick while charging — riding its quarry instead of
	 * standing off it — kills a parked animal just as fast, so a stopwatch
	 * cannot tell the two apart. It was written that way first and passed the
	 * over-broad implementation happily. Distance can tell them apart: keeping
	 * station leaves the drone half a tile off, closing every tick puts it
	 * within a tenth of one.
	 */
	static class TheDroneKillsAQuarryThatKeepsWalking extends Scenario {
		/** Separation between drone and quarry on the killing tick. */
		private double gapAtTheKill;

		/** Ticks for the drone to kill prey walking east at {@code preySpeed}. */
		private int ticksToKill(double preySpeed) {
			seed(523);
			World w = room(120, 12);
			w.setTile(5, 5, 0, Tile.TileType.TYPE_DOCK);
			Genome g = new Genome();
			g.size = 10;
			Mind walk = (s, a) -> a[AgentIO.A_THROTTLE] = 1;
			TestNPC prey = TestNPC.minded(8.5, 5.5, 0, g, walk)
					.withSpeed(preySpeed).withHeading(0); // due east, and never stops
			w.spawnEntity(prey);
			Order order = new Order(w, "herbivore", 0);
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(5.5, 5.5, 0, order);
			w.spawnEntity(drone);
			w.think();
			for (int t = 1; t <= 4000; t++) {
				gapAtTheKill = Math.hypot(drone.getX() - prey.getX(),
						drone.getY() - prey.getY());
				tick(w, 1);
				if (prey.isDead()) {
					return t;
				}
			}
			return -1;
		}

		@Override
		public void run() {
			int parked = ticksToKill(0.0);
			double parkedGap = gapAtTheKill;
			int strolling = ticksToKill(0.05);
			int quick = ticksToKill(0.12);

			assertGreater("a parked quarry is killed at all", parked, 0);
			assertLess("and promptly (" + parked + " ticks)", parked, 60);
			// The control, and it is a distance rather than a clock: closing
			// every tick would put the drone within a tenth of a tile of the
			// body it is shooting.
			assertLess("it holds station off a parked quarry rather than riding it"
					+ " (gap " + String.format("%.3f", parkedGap) + ")",
					0.3, parkedGap);

			assertGreater("a strolling quarry is killed at all", strolling, 0);
			assertLess("and without a long siege (" + strolling + " ticks)",
					strolling, 300);
			assertGreater("a brisk quarry is killed at all", quick, 0);
			assertLess("and likewise (" + quick + " ticks)", quick, 300);
		}
	}

	/**
	 * A duct is a refuge even with the drone hovering at its mouth.
	 *
	 * <p>{@link DuctedBodyIsSafeFromTheDrone} puts the shelter three tiles deep
	 * behind rock, which is safe at any reach the emitter could plausibly have —
	 * and so it says nothing about the reach. The promise it is meant to be
	 * guarding is stronger than that: ground the drone's frame does not fit is
	 * ground the drone cannot kill in.
	 *
	 * <p>That promise used to rest on the pathfinder. The drone could not route
	 * into a duct, so it wrote the animal off and never came — which is not a
	 * refuge, it is a refuge-shaped side effect of a search failing. It did not
	 * survive a wider strike reach and a final approach flown by eye: berth the
	 * drone two tiles from a duct mouth and it closed to 0.81 of a body it could
	 * not follow, through a wall, and shot it.
	 *
	 * <p>So the emitter needs line of sight, and this is the scenario that says
	 * so. The drone is berthed beside the mouth, the animal is one tile inside,
	 * and the drone is given three thousand ticks to fail.
	 */
	static class ADuctIsARefugeEvenAtItsMouth extends Scenario {
		@Override
		public void run() {
			seed(417);
			World w = room(24, 11);
			w.setTile(10, 5, 0, Tile.TileType.TYPE_DOCK); // berth beside the mouth
			// Rock, with one duct tile opening straight onto the open floor the
			// drone can hover on: the tightest the geometry gets.
			for (int x = 12; x <= 20; x++) {
				for (int y = 1; y <= 9; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_WALL);
				}
			}
			w.setTile(12, 5, 0, Tile.TileType.TYPE_DUCT);
			Genome small = Genome.phenotype(6, 0.0, 8, 4, Math.PI, 100000);
			TestNPC ducted = TestNPC.breeder(12.5, 5.5, 0, small).withReproCooldown(100000);
			w.spawnEntity(ducted);
			Order order = new Order(w, "herbivore", 0); // kill everything it can
			net.hedinger.prototype.sim.StewardDrone drone =
					new net.hedinger.prototype.sim.StewardDrone(10.5, 5.5, 0, order);
			w.spawnEntity(drone);
			w.think();
			snapshot(w, "before (a grazer one tile inside the duct, the drone at the door)");

			double closest = Double.MAX_VALUE;
			for (int t = 0; t < 3000 && !ducted.isDead(); t++) {
				tick(w, 1);
				closest = Math.min(closest, Math.hypot(drone.getX() - ducted.getX(),
						drone.getY() - ducted.getY()));
			}
			snapshot(w, "after (still there)");

			assertTrue("the ducted grazer was never shot", !ducted.isDead());
			// And the test earned it: the drone really did come close enough that
			// distance alone would have let it fire. Without this the scenario
			// would pass just as well on a drone that never left its pad.
			double radii = (drone.getSize() + ducted.getSize()) / 2.0;
			assertLess("the drone did close to within the emitter's range",
					closest - radii, net.hedinger.prototype.sim.StewardDrone.strikeReach());
		}
	}

	/**
	 * The seeded world ships one drone, berthed on the charge dock the world
	 * generator carved into the buried base, asleep.
	 *
	 * <p>Asleep is the assertion that matters. A fresh world is nowhere near
	 * any of its ceilings, so a drone out flying on tick one would mean the
	 * steward was flagging a cull that nothing called for.
	 */
	static class SeededWorldBerthsTheDroneRank extends Scenario {
		@Override
		public void run() {
			seed(415);
			World w = net.hedinger.prototype.sim.Worlds.demo(415);
			net.hedinger.prototype.sim.StewardDrone drone = null;
			int drones = 0;
			for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
				if (e instanceof net.hedinger.prototype.sim.StewardDrone d) {
					drone = d;
					drones++;
				}
			}
			assertEquals("the world berths a full rank of drones",
					net.hedinger.prototype.sim.Worlds.DRONE_RANK, drones);
			assertEquals("each is berthed on a charge dock",
					Tile.TileType.TYPE_DOCK.getValue(),
					w.getTile(drone.getX(), drone.getY(), drone.getZ()).getType().getValue());
			// Below the surface, whatever the surface's index happens to be —
			// a floor added underneath must not turn this assertion into a lie.
			assertLess("the dock is underground, in the buried base",
					drone.getZ(), w.getLevels() - 1);
			tick(w, 200);
			assertTrue("with nothing over its ceiling, it stays on standby", drone.isDocked());
		}
	}

	/**
	 * Every drone in the rank gets its own pad, and no pad is a doorway.
	 *
	 * <p>Two claims, and the second is the one with history. A rank of four laid
	 * out as a LINE was the first attempt, and it does not fit a machine wing
	 * three rows tall: spaced along it, three of the four land in the partition
	 * wall and out in the spine, which quietly turns charge pads into the only
	 * way between two rooms — and a parked machine is then standing in a doorway.
	 *
	 * <p>So a pad must look like deck rather than like a gap in a wall, and
	 * blocking one must strand nothing. The second half found a live one the
	 * first half did not: the rank's east pad had been laid across the approach
	 * to the crawl duct through the vault's north wall, and since the vault's
	 * grate answers only its own buttons, that duct is the whole of the other way
	 * in. A berth is a place to stand, not a place to pass through.
	 */
	static class EveryDroneInTheRankHasItsOwnPad extends Scenario {
		@Override
		public void run() {
			for (long s : new long[] { 1, 9, 42, 415, 777 }) {
				World w = net.hedinger.prototype.sim.Worlds.demo(s);
				java.util.Set<String> pads = new java.util.HashSet<String>();
				int drones = 0;
				for (net.hedinger.prototype.engine.Entity e : w.getEntities()) {
					if (e instanceof net.hedinger.prototype.sim.StewardDrone d) {
						drones++;
						assertEquals("seed " + s + ": drone " + drones + " is on a charge dock",
								Tile.TileType.TYPE_DOCK.getValue(),
								w.getTile(d.getX(), d.getY(), d.getZ()).getType().getValue());
						pads.add((int) d.getX() + "," + (int) d.getY());
					}
				}
				assertEquals("seed " + s + ": the rank is full",
						net.hedinger.prototype.sim.Worlds.DRONE_RANK, drones);
				assertEquals("seed " + s + ": no two drones share a pad", drones, pads.size());

				java.util.List<int[]> docks =
						net.hedinger.prototype.sim.Worlds.findDocks(w);
				int open = flood(w, docks.get(0), null);
				for (int[] d : docks) {
					// Not a threshold: a pad set into a wall has solid ground on two
					// opposite sides and open deck on the other two, which is the
					// shape of a doorway and not of a berth.
					assertTrue("seed " + s + ": the pad at " + d[0] + "," + d[1]
							+ " is deck, not a gap in a wall",
							!(solid(w, d[0] - 1, d[1]) && solid(w, d[0] + 1, d[1]))
									&& !(solid(w, d[0], d[1] - 1) && solid(w, d[0], d[1] + 1)));
					// And not a bottleneck: blocking it must strand nothing. The one
					// this caught was subtler than a doorway — the east pad had been
					// laid across the approach to the crawl duct through the vault's
					// north wall, and the vault's grate answers only its own buttons,
					// so that duct is the whole of the other way in.
					assertEquals("seed " + s + ": the pad at " + d[0] + "," + d[1]
							+ " is a berth, not the only way between two rooms",
							open - 1, flood(w, docks.get(0), d));
				}
			}
		}

		static boolean solid(World w, int x, int y) {
			Tile t = w.getTile(x, y, 1);
			return t == null || !t.isWalkable();
		}

		/** Walkable tiles on the cave level reachable from {@code from},
		 *  treating {@code blocked} (if given) as solid. */
		static int flood(World w, int[] from, int[] blocked) {
			int C = w.getColums(), R = w.getRows();
			boolean[][] seen = new boolean[C][R];
			java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
			if (blocked != null && blocked[0] == from[0] && blocked[1] == from[1]) {
				// Start beside the blocked pad rather than on it, or the flood
				// measures nothing at all.
				from = new int[] { from[0], from[1] + 1 };
			}
			seen[from[0]][from[1]] = true;
			q.add(from);
			int n = 0;
			while (!q.isEmpty()) {
				int[] p = q.poll();
				n++;
				Tile t = w.getTile(p[0], p[1], 1);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						int nx = p[0] + dx, ny = p[1] + dy;
						if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= C || ny >= R
								|| seen[nx][ny]) {
							continue;
						}
						if (blocked != null && nx == blocked[0] && ny == blocked[1]) {
							continue;
						}
						Tile o = w.getTile(nx, ny, 1);
						if (o == null || !o.isWalkable()
								|| !t.isConnected(w, nx, ny, 1, true, false)) {
							continue;
						}
						seen[nx][ny] = true;
						q.add(new int[] { nx, ny });
					}
				}
			}
			return n;
		}
	}

	/**
	 * The plant floor under the station is the room that was drawn, and not a
	 * room some later feature wrote over.
	 *
	 * <p>This is the scenario the plant floor's own history asks for. The first
	 * version of that room placed each feature by arithmetic off the room's
	 * centre, and the features overwrote one another in the order they happened
	 * to be listed: the loading walk erased the coolant loop's entire south leg,
	 * the collapse ate its east end, and the stairwell landed in the sump. The
	 * suite was green through all of it.
	 *
	 * <p>Asserted without a second copy of the legend, which would only be one
	 * more thing to keep in step. Every cell drawn with the same character must
	 * have come out the same tile type, and cells drawn with different
	 * characters must have come out different ones — a bijection between the
	 * drawing and the deck. Any overwrite breaks the first half; a feature laid
	 * wholesale over another breaks the second.
	 *
	 * <p>Deck plate is exempt, and only deck plate: it is the room's background,
	 * and the stairwell is deliberately cut through it (see sinkStairwell, which
	 * is not part of the plan precisely because only the builder above knows
	 * which of its own rooms the stairs may come up in).
	 */
	static class ThePlantFloorIsTheRoomThatWasDrawn extends Scenario {
		@Override
		public void run() {
			String[] plan = net.hedinger.prototype.sim.Worlds.plantFloorPlan();
			int floors = 0;
			for (long s : new long[] { 1, 9, 42, 415, 777 }) {
				World w = net.hedinger.prototype.sim.Worlds.demo(s);
				int[] at = plantFloorOrigin(w);
				if (at == null) {
					continue; // this seed's caves only had room for the annex
				}
				floors++;
				java.util.Map<Character, Integer> type =
						new java.util.HashMap<Character, Integer>();
				for (int j = 0; j < plan.length; j++) {
					for (int i = 0; i < plan[j].length(); i++) {
						char ch = plan[j].charAt(i);
						if (ch == '.') {
							continue;
						}
						int got = w.getTile(at[0] + i, at[1] + j, 0).getType().getValue();
						Integer want = type.put(ch, got);
						assertTrue("seed " + s + ": every '" + ch + "' is the same tile"
								+ " (" + at[0] + i + "," + (at[1] + j) + " is " + got
								+ ", elsewhere " + want + ")",
								want == null || want.intValue() == got);
					}
				}
				java.util.Set<Integer> seen = new java.util.HashSet<Integer>(type.values());
				assertEquals("seed " + s + ": every drawn feature is still its own tile",
						type.size(), seen.size());
			}
			assertLess("some seed actually built a plant floor", 0, floors);
		}

		/** The room's interior north-west corner on the deep level, or null if
		 *  this world got no plant floor. The first version boxed everything
		 *  that was not rock — which was the room, while the room was the deep
		 *  level's only carving. The underdark's caverns ended that; the pipe
		 *  run is the landmark now, because the plan draws it down the room's
		 *  west column from its first row and nothing natural grows pipe. */
		static int[] plantFloorOrigin(World w) {
			int minx = Integer.MAX_VALUE, miny = Integer.MAX_VALUE;
			for (int x = 0; x < w.getColums(); x++) {
				for (int y = 0; y < w.getRows(); y++) {
					Tile t = w.getTile(x, y, 0);
					if (t != null && t.getType() == Tile.TileType.TYPE_PIPES) {
						minx = Math.min(minx, x);
						miny = Math.min(miny, y);
					}
				}
			}
			return minx == Integer.MAX_VALUE ? null : new int[] { minx, miny };
		}
	}

	/**
	 * The reactor's coolant run is a closed loop.
	 *
	 * <p>Every coolant tile has exactly two orthogonal coolant neighbours, which
	 * is true of a ring and of nothing else: an open end has one, a junction or
	 * a solid block has three or four. The room was shipped once with three
	 * sides of the rectangle, the fourth erased by the loading walk laid over
	 * it, and it looked like two unrelated pipes rather than a plant.
	 */
	static class TheReactorLoopIsClosed extends Scenario {
		@Override
		public void run() {
			int loops = 0;
			for (long s : new long[] { 1, 9, 42, 415, 777 }) {
				World w = net.hedinger.prototype.sim.Worlds.demo(s);
				int coolant = 0;
				for (int x = 0; x < w.getColums(); x++) {
					for (int y = 0; y < w.getRows(); y++) {
						if (!isCoolant(w, x, y)) {
							continue;
						}
						coolant++;
						int n = (isCoolant(w, x - 1, y) ? 1 : 0) + (isCoolant(w, x + 1, y) ? 1 : 0)
								+ (isCoolant(w, x, y - 1) ? 1 : 0)
								+ (isCoolant(w, x, y + 1) ? 1 : 0);
						assertEquals("seed " + s + ": the coolant run at " + x + "," + y
								+ " has two neighbours, as a loop does", 2, n);
					}
				}
				if (coolant > 0) {
					loops++;
				}
			}
			assertLess("some seed actually built a reactor", 0, loops);
		}

		static boolean isCoolant(World w, int x, int y) {
			if (x < 0 || y < 0 || x >= w.getColums() || y >= w.getRows()) {
				return false;
			}
			Tile t = w.getTile(x, y, 0);
			return t != null && t.getType() == Tile.TileType.TYPE_COOLANT;
		}
	}

	/**
	 * The first ground in the world that wounds. A body wading a waste channel
	 * loses health for as long as it stands there and its corpse says what did
	 * it; an identical body on clean deck beside it is untouched, and a flyer
	 * over the same spill never feels it.
	 *
	 * <p>The flyer half is the one worth pinning. Water and pits already spare
	 * flyers, and a corrosive floor that did not would quietly make the steward
	 * drone mortal — which the drone's whole design says it must not be.
	 */
	static class WasteSludgeBurnsWhatWadesIt extends Scenario {
		@Override
		public void run() {
			seed(420);
			World w = room(14, 9);
			// Floored as the facility, because that is where a spill lives —
			// and the contrast that matters is sludge against deck, not sludge
			// against grass.
			for (int x = 1; x < 13; x++) {
				for (int y = 1; y < 8; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			for (int x = 4; x <= 6; x++) {
				for (int y = 3; y <= 5; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_SLUDGE);
				}
			}
			Genome g = Genome.phenotype(8, 0.0, 8, 4, Math.PI, 100000);
			TestNPC waders = TestNPC.grazer(5.5, 4.5, 0, g).withHealth(100);
			TestNPC ashore = TestNPC.grazer(10.5, 4.5, 0, g).withHealth(100);
			TestNPC overhead = TestNPC.grazer(5.5, 3.5, 0, g).withHealth(100).withFlying();
			w.spawnEntity(waders);
			w.spawnEntity(ashore);
			w.spawnEntity(overhead);
			w.think();
			snapshot(w, "before (one in the spill, one on deck, one above it)");
			tick(w, 400);
			snapshot(w, "after (only the wader is burned)");

			assertLess("the body in the spill was burned", waders.getHealth(), 100);
			assertEquals("the body on clean deck was untouched", 100, ashore.getHealth());
			assertEquals("the flyer skimmed over it, as over water", 100, overhead.getHealth());
			assertEquals("and the spill drags like a ford", 5,
					(long) Math.round(w.getTile(5, 4, 0).speedFactor() * 10));
		}
	}

	/**
	 * A spill reports on the hazard channel, so a mind can learn to route round
	 * it. Unlike water and pits — which a body simply cannot enter — this is a
	 * hazard it is free to walk into and merely pays for, which is what makes
	 * avoiding it a decision rather than a wall.
	 */
	static class SludgeReadsOnTheHazardChannel extends Scenario {
		@Override
		public void run() {
			seed(421);
			World w = room(14, 9);
			for (int y = 1; y <= 7; y++) {
				w.setTile(7, y, 0, Tile.TileType.TYPE_SLUDGE);
			}
			Genome g = Genome.phenotype(8, 0.0, 8, 4, Math.PI, 100000);
			// Facing east, one tile short of the channel: the spill is dead ahead.
			TestNPC facing = TestNPC.minded(6.5, 4.5, 0, g);
			// Facing west, away from it, from the same tile.
			TestNPC away = TestNPC.minded(6.5, 2.5, 0, g).withHeading(Math.PI);
			w.spawnEntity(facing);
			w.spawnEntity(away);
			w.think();
			tick(w, 2);
			snapshot(w, "a mind one tile short of a waste channel");

			double[] sensedAt = facing.sensorSnapshot();
			double[] sensedAway = away.sensorSnapshot();
			assertEquals("the spill ahead reads as a hazard", 1,
					(long) Math.round(sensedAt[net.hedinger.prototype.entities.AgentIO.S_HAZARD_AHEAD]));
			assertEquals("clean ground ahead reads as none", 0,
					(long) Math.round(sensedAway[net.hedinger.prototype.entities.AgentIO.S_HAZARD_AHEAD]));
		}
	}

	/**
	 * The tram run is ordinary ground. It changes nobody's speed — not the
	 * herd walking over it, and not the machine the track was laid for.
	 *
	 * <p>It shipped at 1.4 for everyone, then briefly at 1.4 for machinery
	 * only, and both were the same mistake in different clothes: ground handing
	 * out speed nothing earned. The drone flies, so it was never touching the
	 * rails to begin with, and a creature crossing sleepers is walking over
	 * timber and ballast — no easier than deck and arguably worse.
	 *
	 * <p>So the tile stays for what it is actually good at, which is being
	 * looked at: it says "this corridor is how the facility was supplied", and
	 * it says it without lying about physics. Terrain here earns its place by
	 * changing behaviour, and this one is the exception that earns its place by
	 * explaining the room.
	 */
	static class TheTramRunIsOrdinaryGround extends Scenario {
		@Override
		public void run() {
			seed(422);
			World w = room(30, 8);
			for (int x = 1; x < 29; x++) {
				for (int y = 1; y < 7; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
				w.setTile(x, 3, 0, Tile.TileType.TYPE_RAIL);
			}
			w.alignTiles();
			TestNPC onRun = TestNPC.mover(2.5, 3.5, 0, 0);
			TestNPC onDeck = TestNPC.mover(2.5, 5.5, 0, 0);
			w.spawnEntity(onRun);
			w.spawnEntity(onDeck);
			w.think();
			snapshot(w, "before (one on the run, one on the deck)");
			tick(w, 200);
			snapshot(w, "after (neither gained on the other)");

			// A gap rather than equality: move() jitters each step by a tenth,
			// so identical bodies drift a little whatever the ground under them.
			assertLess("the run is plain ground to a creature",
					Math.abs(onDeck.getX() - onRun.getX()), 0.25);
			assertEquals("and the tile claims nothing else", 10,
					(long) Math.round(w.getTile(5, 3, 0).speedFactor() * 10));
			assertEquals("plain deck reads the same", 10,
					(long) Math.round(w.getTile(5, 5, 0).speedFactor() * 10));
		}
	}

	/**
	 * The track works out its own geometry. A yard laid as bare tiles resolves
	 * into straights, curves, a set of points, a crossing and a buffer stop,
	 * with nothing in the generator saying which is which — the shape follows
	 * from which sides each tile's neighbours continue into.
	 *
	 * <p>Before autotiling, orientation was a single boolean ("is there rail
	 * north or south?"), which is exactly enough for one straight run and wrong
	 * for everything else: a corner drew as two perpendicular stubs meeting at
	 * a seam, and a crossing drew as a vertical tile with the east-west run
	 * dead-ending into it. Nothing had shown it because nothing had yet asked
	 * the track to turn.
	 *
	 * <p>Rendering is not otherwise pinned by the suite — it is checked by eye
	 * against captured strips — so this asserts the classification rather than
	 * the pixels: every tile in the yard reports the neighbours it actually
	 * has, which is the input the painter switches on.
	 */
	static class TrackAutotilesItsOwnGeometry extends Scenario {
		@Override
		public void run() {
			seed(425);
			World w = room(20, 14);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 13; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			// A loop with four corners, a spur crossing it, and a dead end.
			for (int x = 3; x <= 13; x++) {
				w.setTile(x, 3, 0, Tile.TileType.TYPE_RAIL);  // north side
				w.setTile(x, 10, 0, Tile.TileType.TYPE_RAIL); // south side
			}
			for (int y = 3; y <= 10; y++) {
				w.setTile(3, y, 0, Tile.TileType.TYPE_RAIL);  // west side
				w.setTile(13, y, 0, Tile.TileType.TYPE_RAIL); // east side
			}
			for (int x = 3; x <= 16; x++) {
				w.setTile(x, 6, 0, Tile.TileType.TYPE_RAIL);  // spur, crossing both sides
			}
			w.setTile(16, 5, 0, Tile.TileType.TYPE_RAIL);     // a stub off the spur's end
			w.alignTiles();
			snapshot(w, "a yard: loop, spur, crossing, points and a buffer stop");

			assertEquals("the loop's north-west corner turns two ways", 2, arms(w, 3, 3));
			assertEquals("its north-east corner too", 2, arms(w, 13, 3));
			assertEquals("and both southern corners", 2, arms(w, 3, 10));
			assertEquals("and both southern corners", 2, arms(w, 13, 10));
			assertEquals("the spur crosses the west side as points", 3, arms(w, 3, 6));
			assertEquals("and the east side as points", 4, arms(w, 13, 6));
			assertEquals("plain track down the north side is a straight", 2, arms(w, 8, 3));
			assertEquals("the stub off the spur has one neighbour", 1, arms(w, 16, 5));
		}

		/** How many sides this tile's run continues into — the painter's input. */
		private int arms(World w, int x, int y) {
			int n = 0;
			if (w.getTile(x, y - 1, 0).getType() == Tile.TileType.TYPE_RAIL) {
				n++;
			}
			if (w.getTile(x + 1, y, 0).getType() == Tile.TileType.TYPE_RAIL) {
				n++;
			}
			if (w.getTile(x, y + 1, 0).getType() == Tile.TileType.TYPE_RAIL) {
				n++;
			}
			if (w.getTile(x - 1, y, 0).getType() == Tile.TileType.TYPE_RAIL) {
				n++;
			}
			return n;
		}
	}

	/**
	 * A server rack stops a body and hides nothing — the second solid in the
	 * world (after crystal) you can see straight through.
	 *
	 * <p>That combination is its whole reason to exist. A wall ends both the
	 * chase and the watching; a rack ends only the chase, so prey can stand
	 * behind one and keep the hunter in sight, which is a standoff the terrain
	 * could not previously express.
	 */
	static class ServerRacksStopABodyButNotAnEye extends Scenario {
		@Override
		public void run() {
			seed(423);
			World w = room(16, 9);
			for (int x = 1; x < 15; x++) {
				for (int y = 1; y < 8; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			for (int y = 1; y < 8; y++) {
				w.setTile(8, y, 0, Tile.TileType.TYPE_SERVER); // a full rack row
			}
			w.alignTiles();
			// A body walking east into the racks, and the same walk one row over
			// through an aisle gap.
			w.setTile(8, 4, 0, Tile.TileType.TYPE_PLATE); // the aisle
			w.alignTiles();
			TestNPC blocked = TestNPC.mover(6.5, 2.5, 0, 0);
			TestNPC through = TestNPC.mover(6.5, 4.5, 0, 0);
			w.spawnEntity(blocked);
			w.spawnEntity(through);
			w.think();
            snapshot(w, "before (a rack row with one aisle)");
			tick(w, 400);
			snapshot(w, "after (stopped at the racks; through the aisle)");

			assertLess("the racks stopped the body", blocked.getX(), 8.0);
			assertGreater("the aisle let the other through", through.getX(), 9.0);
			assertTrue("but sight crosses the racks", !w.getTile(8, 2, 0).blocksSight());
			assertTrue("a rack is solid all the same", w.getTile(8, 2, 0).isSolid());
			// The contrast that makes the point: a wall in the same place ends
			// the watching too.
			w.setTile(8, 2, 0, Tile.TileType.TYPE_WALL_STEEL);
			assertTrue("where a bulkhead would not", w.getTile(8, 2, 0).blocksSight());
		}
	}

	/**
	 * The drone's glyph against the design system (ART-STYLE.md section 8).
	 *
	 * <p>None of this is on the compiler's gate, which is exactly why it wants
	 * scenarios: the first sentinel was smooth rotated polygons in three
	 * invented colours and it compiled, passed CI and shipped. What follows is
	 * the checklist turned into assertions, so the next drift is caught by the
	 * suite rather than by someone reading the guide months later.
	 */
	static class TheDroneIsDrawnOnTheArtPixelLattice extends Scenario {
		@Override
		public void run() {
			// Every heading resolves to a stamp of art-pixel cells, and every
			// cell is one of the four sanctioned marks. Nothing continuous,
			// nothing in between.
			for (int i = 0; i < DronePainter.dirs(); i++) {
				char[][] f = DronePainter.facing(i);
				assertEquals("the stamp is square, " + i, DronePainter.N, f.length);
				int body = 0, eyes = 0;
				for (char[] row : f) {
					assertEquals("and its rows are too", DronePainter.N, row.length);
					for (char c : row) {
						assertTrue("only lattice marks, never a blend: " + c,
								c == '.' || c == 'H' || c == 'M' || c == 'I' || c == 'h'
										|| c == 'm' || c == 'd' || c == 'K' || c == 'C'
										|| c == 'A');
						if (c != '.' && c != 'A') {
							body++;
						}
						if (c == 'A') {
							eyes++;
						}
					}
				}
				assertGreater("the heading has a body", body, 20);
				assertEquals("and exactly one eye — an accent is rare or it is "
						+ "a ramp colour now", 1, eyes);
			}
		}
	}

	static class EveryHeadingIsLitFromTheNorth extends Scenario {
		/** Marks that carry lighting. The checker's black and the pylon iron do
		 *  not: a hazard marking is a marking, and the ground painters do not
		 *  shade theirs either. */
		private static boolean shaded(char c) {
			return c == 'H' || c == 'M' || c == 'I' || c == 'h' || c == 'm' || c == 'd';
		}

		private static boolean bodyish(char c) {
			return shaded(c) || c == 'K' || c == 'C';
		}

		@Override
		public void run() {
			// One sun, straight overhead-north, and it does NOT turn with the
			// drone. In every column of every heading, a contiguous run of body
			// is lit at its north end and sunk at its south end — never the
			// other way round, which is what a rotated pre-lit sprite would do
			// for half the compass. Ends that fall on an unshaded marking are
			// skipped rather than asserted: there is nothing there to light.
			int checked = 0;
			for (int i = 0; i < DronePainter.dirs(); i++) {
				char[][] f = DronePainter.facing(i);
				for (int c = 0; c < DronePainter.N; c++) {
					int r = 0;
					while (r < DronePainter.N) {
						if (bodyish(f[r][c])) {
							int r0 = r;
							while (r < DronePainter.N && bodyish(f[r][c])) {
								r++;
							}
							char north = f[r0][c], south = f[r - 1][c];
							if (r - r0 == 1) {
								// A sliver one art-pixel tall is its own north
								// AND south edge, so it stays mid rather than
								// speckling the staircase tips with stray lights.
								if (shaded(north)) {
									assertTrue("a one-pixel run stays mid (heading " + i
											+ ", col " + c + "): " + north,
											north == 'M' || north == 'm');
									checked++;
								}
							} else {
								if (shaded(north)) {
									assertTrue("north edge lit (heading " + i + ", col " + c
											+ "): " + north, north == 'H' || north == 'h');
									checked++;
								}
								if (shaded(south)) {
									assertTrue("south edge sunk (heading " + i + ", col " + c
											+ "): " + south, south == 'I' || south == 'd');
									checked++;
								}
							}
						} else {
							r++;
						}
					}
				}
			}
			// Guard against the assertions quietly checking nothing, which is
			// exactly what would happen if a material change swallowed every
			// shaded mark.
			assertGreater("and the check actually looked at something", checked, 60);
		}
	}

	static class TheDroneWearsTheFacilitysOwnYellow extends Scenario {
		@Override
		public void run() {
			// Materials borrow existing families so built things sit in the same
			// world as grown things (section 2). The drone is facility
			// machinery, so it is painted in the facility's safety yellow --
			// the SAME 0xd8b028 already on the dock's keep-clear border, the
			// vent gratings and every other "machinery works here" marking --
			// with its shadow and highlight taken off that by section 4's own
			// x0.65 and x1.18 rather than picked by eye.
			assertEquals("the hull is the world's hazard yellow", 0xd8b028,
					DronePainter.hullRgb());
			assertEquals("its lit edge is that yellow at x1.18", 0xff, DronePainter.litRgb() >> 16);
			// The checker's black and the chassis iron are the world's, too.
			assertEquals("the checker's black is the ground painters'", 0x17171a,
					DronePainter.checkerRgb());
			assertEquals("the chassis is the door's iron", Door.IRON_DARK_RGB,
					DronePainter.chassisRgb());

			// The plates carry the hazard checker, so both halves must appear on
			// every heading -- a plate that lost its stripe is a plate that
			// stopped saying "machinery".
			for (int i = 0; i < DronePainter.dirs(); i++) {
				char[][] f = DronePainter.facing(i);
				int black = 0, yellow = 0;
				for (char[] row : f) {
					for (char c : row) {
						if (c == 'K') {
							black++;
						}
						if (c == 'h' || c == 'm' || c == 'd') {
							yellow++;
						}
					}
				}
				assertGreater("heading " + i + " keeps the checker's black", black, 3);
				assertGreater("heading " + i + " keeps the checker's yellow", yellow, 3);
			}
		}
	}

	static class TheDroneIsWiderAcrossThanItIsLong extends Scenario {
		@Override
		public void run() {
			// The one shape rule worth pinning: a sentinel is a cross, an
			// aircraft is a dart. Two earlier drafts failed here — swept fins
			// read as a paper dart, parallel slivers as a stack of planks — and
			// both were longer than they were wide.
			char[][] e = DronePainter.facing(0); // facing east
			int minR = DronePainter.N, maxR = -1, minC = DronePainter.N, maxC = -1;
			for (int r = 0; r < DronePainter.N; r++) {
				for (int c = 0; c < DronePainter.N; c++) {
					if (e[r][c] != '.') {
						minR = Math.min(minR, r);
						maxR = Math.max(maxR, r);
						minC = Math.min(minC, c);
						maxC = Math.max(maxC, c);
					}
				}
			}
			int across = maxR - minR + 1, along = maxC - minC + 1;
			assertGreater("the plates span further than the pod runs", across, along);
		}
	}

	static class BothRenderersDrawTheSameDrone extends Scenario {
		/** The stamp literals out of the client's render.ts, by name. */
		private static java.util.List<String> stampIn(String src, String name) {
			int at = src.indexOf("const " + name + " = [");
			assertTrueStatic("the client still declares " + name, at >= 0);
			int end = src.indexOf("];", at);
			java.util.List<String> out = new java.util.ArrayList<String>();
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("'([^']*)'")
					.matcher(src.substring(at, end));
			while (m.find()) {
				out.add(m.group(1));
			}
			return out;
		}

		private static void assertTrueStatic(String what, boolean ok) {
			if (!ok) {
				throw new AssertionError(what);
			}
		}

		@Override
		public void run() {
			// ART-STYLE.md section 6: the Java renderer is the source of truth
			// and the client repaints the same art, so the two must agree. They
			// cannot share a literal across the language boundary, which means
			// the only thing keeping them together is that somebody remembered
			// to change both -- and "somebody remembered" is not a mechanism.
			// The drone is the entity that proves it: for weeks the client drew
			// a sentinel the Java renderer had never heard of, and nothing said
			// a word.
			//
			// Comparing the RESOLVED stamps would be better still, but the
			// client's shading rule only exists in TypeScript. Comparing the
			// authored silhouettes catches the drift that actually happens: a
			// stamp edited on one side only.
			java.io.File f = new java.io.File("client/src/render.ts");
			assertTrue("the client source is where the build says it is: "
					+ f.getAbsolutePath(), f.isFile());
			String src;
			try {
				src = new String(java.nio.file.Files.readAllBytes(f.toPath()),
						java.nio.charset.StandardCharsets.UTF_8);
			} catch (java.io.IOException e) {
				throw new AssertionError("could not read the client source", e);
			}

			String[][] pairs = {
					{ "SENTINEL_CARDINAL", "drone cardinal" },
					{ "SENTINEL_DIAGONAL", "drone diagonal" },
					{ "LOADER_CARDINAL", "loader cardinal" },
					{ "LOADER_DIAGONAL", "loader diagonal" },
			};
			for (String[] pair : pairs) {
				java.util.List<String> web = stampIn(src, pair[0]);
				String[] java = pair[1].equals("drone cardinal") ? DronePainter.CARDINAL
						: pair[1].equals("drone diagonal") ? DronePainter.DIAGONAL
						: pair[1].equals("loader cardinal") ? LoaderPainter.CARDINAL
						: LoaderPainter.DIAGONAL;
				assertEquals(pair[1] + ": both renderers draw the same number of rows",
						java.length, web.size());
				for (int r = 0; r < java.length; r++) {
					assertTrue(pair[1] + " row " + r + " differs between the renderers:\n"
							+ "  java: " + java[r] + "\n  web:  " + web.get(r),
							java[r].equals(web.get(r)));
				}
			}
		}
	}

	static class TheDroneKeepsItsSizeWhenItTurns extends Scenario {
		/** Hull extent along the body's own axis and across it, in art-pixels.
		 *  For the cardinal those are just rows and columns; for the diagonal
		 *  they are the forty-five degree axes, which is the only frame in
		 *  which the two shapes are comparable at all. */
		private static double[] extent(String[] stamp, boolean diagonal) {
			double loA = 99, hiA = -99, loX = 99, hiX = -99;
			for (int r = 0; r < stamp.length; r++) {
				for (int c = 0; c < stamp[r].length(); c++) {
					char ch = stamp[r].charAt(c);
					if (ch != '#' && ch != 'A') {
						continue; // hull only: the plates are not the body
					}
					double dr = r - 6, dc = c - 6;
					double along = diagonal ? (dr + dc) / Math.sqrt(2) : dc;
					double across = diagonal ? (dc - dr) / Math.sqrt(2) : dr;
					loA = Math.min(loA, along);
					hiA = Math.max(hiA, along);
					loX = Math.min(loX, across);
					hiX = Math.max(hiX, across);
				}
			}
			return new double[] { hiA - loA + 1, hiX - loX + 1 };
		}

		/**
		 * Whether the body encloses an empty cell — a hole the lattice can
		 * neither gain nor lose under rotation, unlike an area.
		 *
		 * <p>Flood the empty cells inward from the border; anything empty the
		 * flood cannot reach is walled in by body on every side.
		 *
		 * <p>The flood is EIGHT-connected, which is not a detail. A gap running
		 * diagonally between two parts is one art-pixel wide and joined only
		 * corner to corner, and there are four such cells in the drone's own
		 * diagonal stamp — the channel between its hull and its plates. Flood
		 * orthogonally and every one of them reads as a sealed void, so the
		 * check fails on art that is correct and has been looked at. A hole is
		 * a cell with body on all eight sides, not on four.
		 */
		private static boolean enclosesAHole(String[] stamp) {
			int h = stamp.length, w = stamp[0].length();
			boolean[][] open = new boolean[h][w];
			java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					if ((r == 0 || c == 0 || r == h - 1 || c == w - 1)
							&& stamp[r].charAt(c) == '.') {
						open[r][c] = true;
						q.add(new int[] { r, c });
					}
				}
			}
			int[][] step = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
					{ 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
			while (!q.isEmpty()) {
				int[] at = q.poll();
				for (int[] d : step) {
					int nr = at[0] + d[0], nc = at[1] + d[1];
					if (nr < 0 || nc < 0 || nr >= h || nc >= w || open[nr][nc]
							|| stamp[nr].charAt(nc) != '.') {
						continue;
					}
					open[nr][nc] = true;
					q.add(new int[] { nr, nc });
				}
			}
			for (int r = 0; r < h; r++) {
				for (int c = 0; c < w; c++) {
					if (stamp[r].charAt(c) == '.' && !open[r][c]) {
						return true;
					}
				}
			}
			return false;
		}

		@Override
		public void run() {
			// A machine that changes size when it turns is a machine the eye
			// stops believing in, and nothing else here would catch it: both
			// stamps are on the lattice, both are drawn from the ramp, both are
			// lit from the north. They were simply not drawn to each other.
			//
			// The first diagonal ran 13 long by under 4 across against the
			// cardinal's 10 by 5 -- turning forty-five degrees stretched the
			// drone by a third and slimmed it by a quarter. That is what a
			// staircase of fixed vertical thickness costs: T rows buys only
			// T/root-2 across the body, and the error grows with the length.
			double[] card = extent(DronePainter.CARDINAL, false);
			double[] diag = extent(DronePainter.DIAGONAL, true);

			assertLess("the diagonal is no longer than the cardinal",
					diag[0], card[0] + 1.5);
			assertGreater("nor is it shorter", diag[0], card[0] - 1.5);
			assertLess("and no thinner across the body", diag[1], card[1] + 1.5);
			assertGreater("nor fatter", diag[1], card[1] - 1.5);

			// Hollowness is tested DIRECTLY rather than inferred from area.
			//
			// Two earlier versions of this check compared cell counts — first
			// as an absolute difference, then as a ratio — and both were wrong,
			// because cell count is not a cross-orientation measure of shape.
			// Rasterised at forty-five degrees the same form gains or loses
			// cells depending only on how its edges fall on the lattice: at
			// half-extents 5.0x1.5 the diagonal comes out at 1.12 of the
			// axis-aligned count, at 5.0x2.5 at 0.96, at 4.0x4.0 at 0.75, at
			// 6.0x1.0 at 0.64. Not even monotonic. Whatever bound is chosen
			// fails some correct shape.
			assertTrue("the cardinal hull is solid", !enclosesAHole(DronePainter.CARDINAL));
			assertTrue("and so is the diagonal", !enclosesAHole(DronePainter.DIAGONAL));
		}
	}

	static class TheDroneFacesWhereItIsGoing extends Scenario {
		@Override
		public void run() {
			// Eight distinct headings, and the bucket maths lands each compass
			// point on its own stamp. A glyph that looked the same in every
			// direction would say nothing about what the machine is about to do.
			assertEquals("east", 0, DronePainter.bucket(0));
			assertEquals("south", 2, DronePainter.bucket(Math.PI / 2));
			assertEquals("west", 4, DronePainter.bucket(Math.PI));
			assertEquals("north", 6, DronePainter.bucket(-Math.PI / 2));
			assertEquals("and it wraps", 0, DronePainter.bucket(Math.PI * 2));
			int distinct = 0;
			for (int i = 0; i < DronePainter.dirs(); i++) {
				boolean seen = false;
				for (int j = 0; j < i; j++) {
					if (java.util.Arrays.deepEquals(DronePainter.facing(i),
							DronePainter.facing(j))) {
						seen = true;
					}
				}
				if (!seen) {
					distinct++;
				}
			}
			assertEquals("all eight headings are drawn differently",
					DronePainter.dirs(), distinct);
		}
	}

	static class TheLoaderStowsALooseCrate extends Scenario {
		@Override
		public void run() {
			seed(517);
			World w = room(20, 10);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 9; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			w.alignTiles();
			// Berth at one end, vault at the other, one crate loose between.
			net.hedinger.prototype.sim.FacilityLoader ld =
					new net.hedinger.prototype.sim.FacilityLoader(2.5, 5.5, 0, 17.5, 5.5, 0);
			Item crate = Item.crate(10.5, 5.5, 0);
			w.spawnEntity(ld);
			w.spawnEntity(crate);
			w.think();
			snapshot(w, "before (a crate on the floor, the loader berthed)");

			assertTrue("it starts parked", ld.isBerthed());
			tick(w, 3000);
			snapshot(w, "after (the crate is in the vault)");

			assertLess("the crate ends up at the vault",
					Math.hypot(crate.getX() - 17.5, crate.getY() - 5.5), 2.5);
			assertTrue("and the loader let go of it", ld.load() == null);
			assertTrue("and went back to its berth", ld.isBerthed());
		}
	}

	/**
	 * The loader fetches the crate on its own floor, and does not stall on the
	 * one below it.
	 *
	 * <p>The same floor-blind reading the drone had, with a worse ending. The
	 * engine's distance counts a level as a tile, so a crate one storey down
	 * scored a third of a tile away and won the pick over one eight tiles off
	 * across the same room. That much is only a bad choice. What follows is the
	 * bug: a third of a tile is inside FINAL_APPROACH, so the loader dropped its
	 * route and walked by eye at a point on its OWN floor where there was
	 * nothing — and it can never arrive, never grab, and never write the crate
	 * off either, because a write-off needs a route to fail and it had stopped
	 * routing.
	 *
	 * <p>Measured before the fix: the loader crept a quarter of a tile in 260
	 * ticks and stood there for good, with work on its own floor the whole time.
	 * A stall, not a slow pick — which is why this asserts the crate is actually
	 * fetched rather than merely that the pick went the right way.
	 */
	static class TheLoaderPicksItsOwnFloorFirst extends Scenario {
		@Override
		public void run() {
			seed(518);
			World w = room(24, 14, 2);
			for (int z = 0; z < 2; z++) {
				for (int x = 1; x < 23; x++) {
					for (int y = 1; y < 13; y++) {
						w.setTile(x, y, z, Tile.TileType.TYPE_PLATE);
					}
				}
			}
			w.alignTiles();
			net.hedinger.prototype.sim.FacilityLoader ld =
					new net.hedinger.prototype.sim.FacilityLoader(5.5, 5.5, 1, 20.5, 11.5, 1);
			Item across = Item.crate(16.5, 5.5, 1);  // eight tiles off, same floor
			Item below = Item.crate(5.8, 5.5, 0);    // a third of a tile off, one floor down
			w.spawnEntity(ld);
			w.spawnEntity(across);
			w.spawnEntity(below);
			w.think();
			snapshot(w, "before (a crate across the hall, a crate through the floor)");

			// The pick itself, not where the machine ends up. Asked at the end
			// this scenario passes with the metric reverted, because the
			// same-floor gate then refuses the bad target, the route fails, and
			// the crate is written off — the right answer reached by a longer
			// road. Asking which crate it CHOSE separates the two.
			tick(w, 2);
			assertTrue("it chose the crate on its own floor", ld.target() == across);

			tick(w, 3000);
			snapshot(w, "after (the hall's crate is in the vault)");
			assertLess("and fetched and stowed it",
					Math.hypot(across.getX() - 20.5, across.getY() - 11.5), 2.5);
			assertEquals("the crate through the floor was left on its own floor",
					0, below.getLvl());
			assertLess("and was not moved", Math.hypot(below.getX() - 5.8,
					below.getY() - 5.5), 0.3);
		}
	}

	/**
	 * A crate it cannot get to does not park the machine forever.
	 *
	 * <p>The other half of the same floor-blind reading, and the one with teeth.
	 * When a crate through the floor is the ONLY work, the loader picks it —
	 * correctly, since something unreachable is still the nearest thing there
	 * is. The engine's distance then reads it as a tile away, which is inside
	 * FINAL_APPROACH, so the loader drops its route and walks by eye at a point
	 * on its own floor where there is nothing.
	 *
	 * <p>From there it cannot recover. It never arrives, so it never grabs; and
	 * it never writes the crate off either, because a write-off needs a route to
	 * fail and it has stopped routing. Measured: a quarter of a tile of creep in
	 * 260 ticks and then nothing, for as long as the world ran.
	 *
	 * <p>What should happen is what happens to any unreachable crate: route,
	 * fail, write it off, go home. So the assertion is that the machine is back
	 * on its berth — a loader parked with nothing to do is a loader that
	 * finished thinking, and the stall never gets there.
	 */
	static class TheLoaderDoesNotStallOnACrateThroughTheFloor extends Scenario {
		@Override
		public void run() {
			seed(519);
			World w = room(24, 14, 2);
			for (int z = 0; z < 2; z++) {
				for (int x = 1; x < 23; x++) {
					for (int y = 1; y < 13; y++) {
						w.setTile(x, y, z, Tile.TileType.TYPE_PLATE);
					}
				}
			}
			w.alignTiles();
			// The berth is directly over the crate, which is the arrangement the
			// stall needs: the machine has to START inside FINAL_APPROACH of it,
			// or it routes, fails and writes the crate off before the by-eye
			// branch is ever reached — and the scenario proves nothing.
			net.hedinger.prototype.sim.FacilityLoader ld =
					new net.hedinger.prototype.sim.FacilityLoader(5.5, 5.5, 1, 20.5, 11.5, 1);
			// The only crate in the world, one floor down and no way to it.
			Item below = Item.crate(5.8, 5.5, 0);
			w.spawnEntity(ld);
			w.spawnEntity(below);
			w.think();
			snapshot(w, "before (the only crate is through the floor)");

			tick(w, 1500);
			snapshot(w, "after (given up on it)");

			// That it let the crate GO, not where it is standing. The berth has
			// to be over the crate for the stall to be reachable at all, so a
			// seized machine is sitting on its berth and "is it parked" answers
			// yes for the wrong reason. A stalled loader holds its target for
			// ever; a working one routes, fails, writes it off and lets go.
			assertTrue("it gave up on the crate it could not reach",
					ld.target() == null);
			assertLess("and never shifted it", Math.hypot(below.getX() - 5.8,
					below.getY() - 5.5), 0.3);
		}
	}

	static class TheLoaderLeavesStowedCratesAlone extends Scenario {
		@Override
		public void run() {
			seed(518);
			World w = room(20, 10);
			for (int x = 1; x < 19; x++) {
				for (int y = 1; y < 9; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			w.alignTiles();
			// Everything already put away. A machine that cannot tell finished
			// from unfinished picks its own last delivery up again and hauls it
			// in a circle forever, which is the failure this pins.
			net.hedinger.prototype.sim.FacilityLoader ld =
					new net.hedinger.prototype.sim.FacilityLoader(2.5, 5.5, 0, 17.5, 5.5, 0);
			Item stowed = Item.crate(17.5, 5.5, 0);
			w.spawnEntity(ld);
			w.spawnEntity(stowed);
			w.think();
			double x0 = stowed.getX(), y0 = stowed.getY();
			tick(w, 1500);

			assertNear("the stowed crate never moved", stowed.getX(), x0, 0.01);
			assertNear("nor on the other axis", stowed.getY(), y0, 0.01);
			assertTrue("and the loader stayed home", ld.isBerthed());
		}
	}

	static class TheLoaderIsSlowerThanTheDrone extends Scenario {
		@Override
		public void run() {
			seed(519);
			World w = room(30, 8);
			for (int x = 1; x < 29; x++) {
				for (int y = 1; y < 7; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			w.alignTiles();
			// Both machines sent the length of the same corridor. The loader is
			// plant that hauls; the drone is the warden's hand. If the hauler
			// keeps up, neither reads as what it is.
			net.hedinger.prototype.sim.FacilityLoader ld =
					new net.hedinger.prototype.sim.FacilityLoader(2.5, 3.5, 0, 27.5, 3.5, 0);
			net.hedinger.prototype.sim.StewardDrone dr =
					new net.hedinger.prototype.sim.StewardDrone(2.5, 4.5, 0,
							new Order(w, null, 0));
			w.spawnEntity(ld);
			w.spawnEntity(dr);
			// Well clear of the vault, or it counts as already stowed and the
			// loader correctly refuses to budge — which is what the first draft
			// of this scenario actually measured.
			w.spawnEntity(Item.crate(14.5, 3.5, 0));
			w.think();
			double lx = ld.getX();
			tick(w, 300);
			double loaderRan = ld.getX() - lx;

			assertGreater("the loader did set off", loaderRan, 1.0);
			assertLess("but slower than the drone cruises", loaderRan / 300.0, 0.12);
		}
	}

	static class MachineryIsNeitherFedNorWatered extends Scenario {
		@Override
		public void run() {
			seed(520);
			World w = room(12, 8);
			for (int x = 1; x < 11; x++) {
				for (int y = 1; y < 7; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			// A lane of the facility's own waste for the walker to stand in.
			for (int x = 3; x < 9; x++) {
				w.setTile(x, 5, 0, Tile.TileType.TYPE_SLUDGE);
			}
			w.alignTiles();
			net.hedinger.prototype.sim.FacilityLoader ld =
					new net.hedinger.prototype.sim.FacilityLoader(5.5, 5.5, 0, 5.5, 5.5, 0);
			net.hedinger.prototype.sim.StewardDrone dr =
					new net.hedinger.prototype.sim.StewardDrone(7.5, 3.5, 0,
							new Order(w, null, 0));
			w.spawnEntity(ld);
			w.spawnEntity(dr);
			w.think();

			assertTrue("the loader is not organic", !ld.isOrganic());
			assertTrue("nor is the drone", !dr.isOrganic());
			assertTrue("the loader has its own trophic name", "loader".equals(ld.ecoRole()));

			// Plant does not keep the four books. Neither machine opts into the
			// energy economy, so no clock of hunger or thirst runs for them --
			// and this asserts it rather than trusting that `metabolic` keeps
			// defaulting to false, which is the only thing standing between a
			// machine and a slow death by starvation.
			int lifetimes = (int) Math.max(NPC.HUNGER_PERIOD, NPC.THIRST_PERIOD) * 2;
			tick(w, Math.min(lifetimes, 40000));

			assertNear("the loader never gets hungry", ld.getHunger(), 0.0, 1e-9);
			assertNear("nor thirsty", ld.getThirst(), 0.0, 1e-9);
			assertNear("the drone never gets hungry", dr.getHunger(), 0.0, 1e-9);
			assertNear("nor thirsty", dr.getThirst(), 0.0, 1e-9);
			assertTrue("and both are still here", !ld.isRemoved() && !dr.isRemoved());
			assertTrue("and alive", !ld.isDead() && !dr.isDead());

			// Indestructible, through every door into harm. The two-arg form is
			// the one the world actually calls -- corrosive ground uses it, and
			// the loader WALKS, so it has been standing in sludge for the whole
			// run above.
			int before = ld.getHealth();
			ld.damage(1000);
			ld.damage(1000, "toxic");
			assertEquals("nothing damages the loader", before, ld.getHealth());
			int droneBefore = dr.getHealth();
			dr.damage(1000, "predation");
			assertEquals("nor the drone", droneBefore, dr.getHealth());
		}
	}

	static class TheLoaderWalksAndIsDraggedByTheGround extends Scenario {
		@Override
		public void run() {
			seed(521);
			World w = room(30, 10);
			for (int x = 1; x < 29; x++) {
				for (int y = 1; y < 9; y++) {
					w.setTile(x, y, 0, Tile.TileType.TYPE_PLATE);
				}
			}
			// A band of sludge across one lane and clean deck across the other.
			for (int x = 8; x < 20; x++) {
				w.setTile(x, 3, 0, Tile.TileType.TYPE_SLUDGE);
			}
			w.alignTiles();
			// The whole point of a machine that WALKS: the ground it crosses
			// costs it something. The drone flies the same lane for free, which
			// is the contrast the two bodies exist to draw.
			net.hedinger.prototype.sim.FacilityLoader wading =
					new net.hedinger.prototype.sim.FacilityLoader(2.5, 3.5, 0, 27.5, 3.5, 0);
			net.hedinger.prototype.sim.FacilityLoader clear =
					new net.hedinger.prototype.sim.FacilityLoader(2.5, 6.5, 0, 27.5, 6.5, 0);
			w.spawnEntity(wading);
			w.spawnEntity(clear);
			w.spawnEntity(Item.crate(14.5, 3.5, 0));
			w.spawnEntity(Item.crate(14.5, 6.5, 0));
			w.think();
			snapshot(w, "before (one lane fouled, one clean)");
			tick(w, 900);
			snapshot(w, "after (the wader is behind)");

			assertGreater("the clean lane got further", clear.getX(), wading.getX());
		}
	}

	static class TheLoadersArtHoldsUpToTheChecklist extends Scenario {
		private static boolean shaded(char c) {
			return c == 'H' || c == 'M' || c == 'I' || c == 'h' || c == 'm' || c == 'd';
		}

		private static boolean bodyish(char c) {
			return shaded(c) || c == 'K' || c == 'C';
		}

		@Override
		public void run() {
			// The same checklist the drone is held to, on the second machine
			// that is drawn this way. Written as one scenario rather than five
			// because the rules are the drone's and were argued there; what is
			// worth knowing here is only whether this body obeys them.
			int lit = 0;
			for (int i = 0; i < LoaderPainter.dirs(); i++) {
				char[][] f = LoaderPainter.facing(i);
				assertEquals("the stamp is square, " + i, LoaderPainter.N, f.length);
				int body = 0, lamps = 0;
				for (char[] row : f) {
					for (char c : row) {
						assertTrue("only lattice marks, never a blend: " + c,
								c == '.' || bodyish(c) || c == 'A');
						if (bodyish(c)) {
							body++;
						}
						if (c == 'A') {
							lamps++;
						}
					}
				}
				assertGreater("heading " + i + " has a body", body, 40);
				assertEquals("and exactly one lamp", 1, lamps);

				// One sun, fixed overhead-north, applied after the rotation.
				for (int c = 0; c < LoaderPainter.N; c++) {
					int r = 0;
					while (r < LoaderPainter.N) {
						if (bodyish(f[r][c])) {
							int r0 = r;
							while (r < LoaderPainter.N && bodyish(f[r][c])) {
								r++;
							}
							char north = f[r0][c], south = f[r - 1][c];
							if (r - r0 > 1 && shaded(north)) {
								assertTrue("north edge lit (heading " + i + ", col " + c
										+ "): " + north, north == 'H' || north == 'h');
								lit++;
							}
							if (r - r0 > 1 && shaded(south)) {
								assertTrue("south edge sunk (heading " + i + ", col " + c
										+ "): " + south, south == 'I' || south == 'd');
							}
						} else {
							r++;
						}
					}
				}
			}
			assertGreater("and the lighting check looked at something", lit, 40);

			// Turn invariance, measured in the body's own axes -- the rule the
			// drone's stretching diagonal produced. Extents only: cell count is
			// not a cross-orientation measure of shape, and this body proved it,
			// coming out at 0.72 of the cardinal where the drone came out above
			// 1.0.
			double[] card = hullExtent(LoaderPainter.CARDINAL, false);
			double[] diag = hullExtent(LoaderPainter.DIAGONAL, true);
			assertLess("the diagonal is no longer than the cardinal", diag[0], card[0] + 1.5);
			assertGreater("nor shorter", diag[0], card[0] - 1.5);
			assertLess("and no wider across", diag[1], card[1] + 1.5);
			assertGreater("nor narrower", diag[1], card[1] - 1.5);
		}

		private static double[] hullExtent(String[] stamp, boolean diagonal) {
			double c = (stamp.length - 1) / 2.0;
			double loA = 99, hiA = -99, loX = 99, hiX = -99;
			for (int r = 0; r < stamp.length; r++) {
				for (int col = 0; col < stamp[r].length(); col++) {
					char ch = stamp[r].charAt(col);
					if (ch != '#' && ch != 'S' && ch != 'A') {
						continue; // hull and cab: the body, not the forks
					}
					double dr = r - c, dc = col - c;
					double along = diagonal ? (dr + dc) / Math.sqrt(2) : dc;
					double across = diagonal ? (dc - dr) / Math.sqrt(2) : dr;
					loA = Math.min(loA, along);
					hiA = Math.max(hiA, along);
					loX = Math.min(loX, across);
					hiX = Math.max(hiX, across);
				}
			}
			return new double[] { hiA - loA + 1, hiX - loX + 1 };
		}
	}

	static Scenario[] all() { // package: RecordScenario replays these by name
		return new Scenario[] {
				new DemoWorldFullyConnected(),
				new TheRavineIsCutButTheWorldHolds(),
				new SeededWorldBerthsTheDroneRank(),
				new EveryDroneInTheRankHasItsOwnPad(),
				new ThePlantFloorIsTheRoomThatWasDrawn(),
				new TheReactorLoopIsClosed(),
				new TheLoaderStowsALooseCrate(),
				new TheLoaderPicksItsOwnFloorFirst(),
				new TheLoaderDoesNotStallOnACrateThroughTheFloor(),
				new TheLoaderLeavesStowedCratesAlone(),
				new TheLoaderIsSlowerThanTheDrone(),
				new MachineryIsNeitherFedNorWatered(),
				new TheLoaderWalksAndIsDraggedByTheGround(),
				new WasteSludgeBurnsWhatWadesIt(),
				new SludgeReadsOnTheHazardChannel(),
				new TheTramRunIsOrdinaryGround(),
				new TrackAutotilesItsOwnGeometry(),
				new ServerRacksStopABodyButNotAnEye(),
				new TheDroneIsDrawnOnTheArtPixelLattice(),
				new EveryHeadingIsLitFromTheNorth(),
				new TheDroneWearsTheFacilitysOwnYellow(),
				new TheDroneIsWiderAcrossThanItIsLong(),
				new BothRenderersDrawTheSameDrone(),
				new TheLoadersArtHoldsUpToTheChecklist(),
				new TheDroneKeepsItsSizeWhenItTurns(),
				new TheDroneFacesWhereItIsGoing(),
				new DroneCullsToTargetAndReturnsToDock(),
				new TheRankSplitsTheCull(),
				new ZappedBodyIsAlmostGone(),
				new NothingEatsTheDrone(),
				new DoorsOpenForTheDrone(),
				new NothingRidesTheDrone(),
				new DuctedBodyIsSafeFromTheDrone(),
				new ADuctIsARefugeEvenAtItsMouth(),
				new TheDronePicksItsOwnFloorFirst(),
				new TheEmitterDoesNotFireThroughAFloor(),
				new TheEmitterFiresAtTheRangeItClaims(),
				new TheDroneKillsAQuarryThatKeepsWalking(),
				new GenomeSavefileRoundTrips(),
				new InjectedCreatureSurvivesPopulationCeiling(),
				new HerbivoreFleesPredator(),
				new PredatorRunsDownFleeingPrey(),
				new HunterIgnoresPreyOnAnotherLevel(),
				new HunterDoesNotFreezeOnUnreachablePrey(),
				new SatedPredatorSparesPrey(),
				new StarvationDrivesCannibalism(),
				new DemoLevelsLinkSurfaceAndCave(),
				new WallContainment(),
				new RoamerMoves(),
				new ChaserClosesIn(),
				new AgesOutAndIsRemoved(),
				new LethalDamageAndScavenging(),
				new ForageIgnoresFoodBehindWalls(),
				new ScavengerEatsCarrionAndHastensItsDecay(),
				new ScavengerForagesTowardBodies(),
				new ACarcassIsWorthWhatAKillIsWorth(),
				new ABodyIsShapedByWhatItEats(),
				new AFlyerAndAWalkerDoNotShoveEachOther(),
				new AFloorIsSolidToTheTouch(),
				new ScavengerYoungAreScavengers(),
				new ScavengersDoNotBreedWithGrazers(),
				new ScavengerHoldsTheCarcassItChose(),
				new ScavengerCrossesToAMuchBetterCarcass(),
				new CorpseRotsForAsLongAsItTookToGrow(),
				new SoundWakesListener(),
				new ASoundRingsForAsLongAsItSays(),
				new HoleFallRespectsFlying(),
				new CarryingAndRidingWearDifferentBadges(),
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
				new SmallHunterTakesBiggerPreySlowly(),
				new EngineCapsStepLength(),
				new TravelCostsEnergy(),
				new HoldingACaptiveCostsEnergy(),
				new CreaturesGrowToAdultSize(),
				new ActionGlyphsRideTheWire(),
				new MechanicsAreReadOffTheWorld(),
				new RoleFollowsDietNotMind(),
				new BodiesAreNeverLeftInsideTerrain(),
				new CladeIsOneConceptWithFrozenCodes(),
				new SpeciesLabelIsDerivedNotStored(),
				new CladesRecogniseEachOtherAsOther(),
				new EverySensorIsReachable(),
				new ViolenceIsAudibleThroughWalls(),
				new HitchhikerBrainClimbsAboard(),
				new GenomeInheritance(),
				new GrazerDepletesSubstrate(),
				new VegetationRegrows(),
				new FertilityCapsVegetation(),
				new FertileHabitatPatches(),
				new WaterBlocksLandPassesFlyers(),
				new MudSlowsMovement(),
				new FlyersAreNotDraggedByGroundTheyNeverTouch(),
				new CrystalDensityTiers(),
				new DuctAdmitsOnlySmallBodies(),
				new BlockedBodySlidesAlongAnObstacle(),
				new GrownBodyEscapesTheBedItGrewInside(),
				new SwitchOpensWiredDoor(),
				new ButtonNeedsIntent(),
				new BrainInteractsWithButton(),
				new BrainSeeksAndPressesButton(),
				new BottomlessPitsAndCatwalks(),
				new APitOntoRockIsBottomlessNotAWall(),
				new TheMesaCampusBuildsWholeAndEmpty(),
				new ThirstyGrazerWalksToWater(),
				new ThirstWalksRoundAWallNotIntoIt(),
				new AmblingMindIsShakenOffAWall(),
				new UnreachableWaterIsNotSensed(),
				new DehydrationWearsABodyDown(),
				new CollapseIsNotDeath(),
				new AppetiteReturnsAtHalfThirstsPace(),
				new EnergyIsFoodBacked(),
				new NoFreeEnergyAtBirth(),
				new GrowingUpIsPaidFor(),
				new TuningRidesTheCommandLog(),
				new HealthGatesEnergyRegeneration(),
				new ParasiteLatchesAndDrainsItsHost(),
				new RockyGroundFeedsAGrazerPoorly(),
				new TheStewardPutsParasitesBack(),
				new AGrownBodyIsBigEnoughToRide(),
				new PredatorsIgnoreParasites(),
				new AParasiteCannotGraze(),
				new CorpseFeedsTheGround(),
				new CoverHidesFromPerception(),
				new StarvesWithoutFood(),
				new PopulationGrowsWithFood(),
				new SexualReproductionNeedsPartner(),
				new BrainMemoryIsDeterministic(),
				new BrainLengthSetsThoughtRate(),
				new BrainHeredityCrossesAndMutates(),
				new MindDrivesAgent(),
				new MindSensesHungerByCapacity(),
				new MindHuntsViaPreyChannel(),
				new MindFleesViaThreatChannel(),
				new ThrottleSetsSpeedAndCostsItsSquare(),
				new ACorpseLingersByItsMass(),
				new AFullHunterDoesNotKill(),
				new CoverHidesPreyFromAHunter(),
				new TileSeekingIsGeneric(),
				new MatingTakesTimeAndSeeksAPartner(),
				new IntentReportsHowItWent(),
				new BrainSizeSetsHowMuchAMindTracks(),
				new SeekWalksToAPatchWithoutSteering(),
				new OneIntentIsAWholeBehaviour(),
				new HuntIntentClosesAndBites(),
				new WaypointRemembersAPlace(),
				new SeekYieldsWhenItNamesNothing(),
				new MindsChangeLevelByWalkingRamps(),
				new RampsRunWhicheverWayTheyAreLaid(),
				new AFloorBlocksSightUnlessAnOpeningIsNear(),
				new RockOwnsTheRampCutIntoIt(),
				new MindedBodyUnsticksFromWallJam(),
				new MindedCohortSustainedBySteward(),
				new MindedReseedDescendsFromLongestLivedSurvivor(),
				new StarterBrainedForagerFeedsItself(),
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
				new FlyingCarrierPaysMore(),
				new GroundCannotGrabFlyer(),
				new HostBucksOffRiders(),
				new ItemSensedOnDedicatedChannel(),
				new FoodItemEatenForEnergy(),
				new CrateBrokenSpillsFood(),
				new HazardHarmsEater(),
				new HazardHarmsAttacker(),
				new ItemPushedAsideByPasserby(),
				new CarrierHaulsCrate(),
				new DiagonalLineOfSight(),
				new BlockedSensorSeesWalls(),
				new PheromoneDecays(),
				new NestEmergesFromPheromone(),
				new SnapshotStreamDeterministic(),
				new CommandLogReplayReproduces(),
				new SameSeedSameOutcome(),
		};
	}

	public static void main(String[] args) {
		int passed = 0;
		int failed = 0;
		// Behaviour tests render clean: no decorative grass/shrubs cluttering the
		// subject (scenario worlds are fully fertile, so foliage would blanket them).
		net.hedinger.prototype.engine.RenderFx.foliage = false;
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
